package com.novastreams.app.core.twitchapi

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.novastreams.app.core.auth.AuthRepository
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.FollowedChannel
import com.novastreams.app.core.model.SearchResults
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.seedOf
import org.json.JSONArray
import org.json.JSONObject

/** Превращает JSONArray в список объектов (в Kotlin нет встроенного удобного способа). */
internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

/**
 * Реальные данные через официальный Helix API. Работает только после входа
 * (без Client Secret токен приложения получить нельзя, нужен токен пользователя).
 */
class HelixTwitchRepository(
    private val api: HelixApi,
    private val auth: AuthRepository,
) : TwitchRepository {

    override val source: DataSource = DataSource.TWITCH

    // Twitch отдаёт адреса картинок с шаблоном {width}x{height} — подставляем нужный размер.
    private fun thumb(template: String) =
        template.replace("{width}", "440").replace("{height}", "248")

    // Поиск категорий в Helix отдаёт обложку фиксированного размера «…-52x72.jpg» (не шаблон) — отсюда «мыльные» картинки;
    // размер в конце адреса заменяем на нормальный.
    private fun boxArt(template: String) =
        template.replace("{width}", "285").replace("{height}", "380")
            .replace(Regex("-\\d+x\\d+(\\.\\w+)$"), "-285x380$1")

    private fun myId(): String =
        (auth.state.value as? AuthState.SignedIn)?.user?.id
            ?: throw ApiException("Нужно войти через Twitch", 401)

    /** Аватарки каналов по их id. Один запрос принимает до 100 id, поэтому режем на пачки. */
    private suspend fun avatars(ids: List<String>): Map<String, String> {
        val result = HashMap<String, String>()
        ids.distinct().chunked(100).forEach { chunk ->
            val json = api.get("users", chunk.map { "id" to it })
            json.optJSONArray("data").objects().forEach { u ->
                result[u.optString("id")] = u.optString("profile_image_url")
            }
        }
        return result
    }

    private suspend fun parseStreams(json: JSONObject): List<Stream> {
        val items = json.optJSONArray("data").objects()
        val pics = avatars(items.map { it.optString("user_id") })
        return items.map { s ->
            val userId = s.optString("user_id")
            Stream(
                id = s.optString("id"),
                channel = Channel(
                    id = userId,
                    login = s.optString("user_login"),
                    displayName = s.optString("user_name"),
                    description = "",
                    followers = -1,
                    artSeed = seedOf(userId),
                    avatarUrl = pics[userId]?.takeIf { it.isNotBlank() },
                ),
                title = s.optString("title").ifBlank { "Без названия" },
                categoryId = s.optString("game_id"),
                categoryName = s.optString("game_name").ifBlank { "Без категории" },
                viewers = s.optInt("viewer_count"),
                language = s.optString("language").uppercase(),
                artSeed = seedOf(s.optString("id")),
                thumbnailUrl = thumb(s.optString("thumbnail_url")).takeIf { it.isNotBlank() }?.let(::freshPreview),
            )
        }
    }

    @Volatile
    override var languageNote: String? = null
        private set

    /**
     * Запрос эфиров с языком (ISO 639-1, строчными).
     * При включённом фильтре НЕ подмешиваем другие языки — только выбранный.
     */
    private suspend fun streamsByLanguage(base: List<Pair<String, String>>, language: String?): List<Stream> {
        languageNote = null
        if (language == null) return parseStreams(api.get("streams", base))
        val filtered = parseStreams(api.get("streams", base + ("language" to language.lowercase())))
        if (filtered.isEmpty()) {
            languageNote = "Сейчас нет эфиров на языке $language"
        }
        return filtered
    }

    override suspend fun liveStreams(language: String?): List<Stream> = api.guarded {
        // Twitch сам сортирует по числу зрителей: самые популярные первыми.
        streamsByLanguage(listOf("first" to "100", "type" to "live"), language)
    }

    override suspend fun followedLive(): List<Stream> = api.guarded {
        parseStreams(api.get("streams/followed", listOf("user_id" to myId(), "first" to "100")))
    }

    override suspend fun followedChannels(): List<FollowedChannel> = api.guarded {
        // Берём первые 100 подписок. TODO: листать дальше по курсору, если подписок больше.
        val follows = api.get("channels/followed", listOf("user_id" to myId(), "first" to "100"))
            .optJSONArray("data").objects()
        val live = followedLive().associateBy { it.channel.id }
        val pics = avatars(follows.map { it.optString("broadcaster_id") })
        follows.map { f ->
            val id = f.optString("broadcaster_id")
            val stream = live[id]
            val channel = stream?.channel ?: Channel(
                id = id,
                login = f.optString("broadcaster_login"),
                displayName = f.optString("broadcaster_name"),
                description = "",
                followers = -1,
                artSeed = seedOf(id),
                avatarUrl = pics[id]?.takeIf { it.isNotBlank() },
            )
            FollowedChannel(channel, stream)
        }.sortedWith(
            compareByDescending<FollowedChannel> { it.stream != null }
                .thenByDescending { it.stream?.viewers ?: 0 }
                .thenBy { it.channel.displayName.lowercase() }
        )
    }

    private fun parseHelixGame(g: org.json.JSONObject): Category = Category(
        id = g.optString("id"),
        name = g.optString("name"),
        viewers = -1, // Helix /games/top не отдаёт зрителей категории
        artSeed = seedOf(g.optString("id")),
        boxArtUrl = boxArt(g.optString("box_art_url")).takeIf { it.isNotBlank() },
    )

    override suspend fun isFollowing(broadcasterId: String): Boolean = api.guarded {
        val data = api.get(
            "channels/followed",
            listOf(
                "user_id" to myId(),
                "broadcaster_id" to broadcasterId,
                "first" to "1",
            ),
        )
        (data.optJSONArray("data")?.length() ?: 0) > 0
    }

    override suspend fun topCategories(): List<Category> = api.guarded {
        // На главной достаточно топа; полный список — через categoriesPage.
        api.get("games/top", listOf("first" to "30")).optJSONArray("data").objects().map(::parseHelixGame)
    }

    override suspend fun categoriesPage(after: String?): CategoriesPage = api.guarded {
        val params = mutableListOf("first" to "100")
        if (!after.isNullOrBlank()) params += "after" to after
        val json = api.get("games/top", params)
        val list = json.optJSONArray("data").objects().map(::parseHelixGame)
        val cursor = json.optJSONObject("pagination")?.optString("cursor")?.takeIf { it.isNotBlank() }
        CategoriesPage(list, cursor)
    }

    override suspend fun streamsForCategory(categoryId: String, language: String?): List<Stream> = api.guarded {
        streamsByLanguage(listOf("game_id" to categoryId, "first" to "100"), language)
    }

    override suspend fun categoryName(categoryId: String): String? = api.guarded {
        api.get("games", listOf("id" to categoryId)).optJSONArray("data").objects()
            .firstOrNull()?.optString("name")
    }

    override suspend fun streamForChannel(channelId: String): Stream? = api.guarded {
        parseStreams(api.get("streams", listOf("user_id" to channelId))).firstOrNull()
    }

    override suspend fun channel(channelId: String): Channel? = api.guarded {
        api.get("users", listOf("id" to channelId)).optJSONArray("data").objects().firstOrNull()?.let { u ->
            Channel(
                id = u.optString("id"),
                login = u.optString("login"),
                displayName = u.optString("display_name"),
                description = u.optString("description"),
                followers = -1,
                artSeed = seedOf(channelId),
                avatarUrl = u.optString("profile_image_url").takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun search(query: String): SearchResults = api.guarded {
        val q = query.trim()
        if (q.isEmpty()) return@guarded SearchResults(emptyList(), emptyList(), emptyList())

        val qLower = q.lowercase()
        val tokens = qLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Слова без окончаний и близкие слова («фильмы» находит «фильм», «кино», movie…).
        val matcher = QueryMatcher(q)

        fun hayMatches(hay: String): Boolean = matcher.matches(hay) || hay.lowercase().contains(qLower)

        // Расширение запроса для категорий: «фильмы» → Movies / Watch Parties и т.п.
        val categoryQueries = linkedSetOf(q)
        when {
            qLower.contains("фильм") || qLower.contains("кино") -> {
                categoryQueries += listOf("Movies", "Watch Parties", "Movie", "Films")
            }
            qLower.contains("музыка") || qLower.contains("music") -> {
                categoryQueries += listOf("Music", "Music & Performing Arts")
            }
            qLower.contains("ирт") || qLower.contains("just chatting") || qLower.contains("общен") -> {
                categoryQueries += listOf("Just Chatting", "Talk Shows & Podcasts")
            }
            qLower.contains("арт") || qLower.contains("рисов") -> {
                categoryQueries += listOf("Art", "ASMR")
            }
        }

        val allChJson = api.get("search/channels", listOf("query" to q, "first" to "25"))
        val liveJson = api.get(
            "search/channels",
            listOf("query" to q, "first" to "25", "live_only" to "true"),
        )

        val catMap = LinkedHashMap<String, Category>()
        for (cq in categoryQueries) {
            try {
                api.get("search/categories", listOf("query" to cq, "first" to "15"))
                    .optJSONArray("data").objects().forEach { g ->
                        val id = g.optString("id")
                        if (id.isNotBlank()) {
                            catMap[id] = Category(
                                id = id,
                                name = g.optString("name"),
                                viewers = -1,
                                artSeed = seedOf(id),
                                boxArtUrl = boxArt(g.optString("box_art_url")).takeIf { it.isNotBlank() },
                            )
                        }
                    }
            } catch (_: Exception) { }
        }

        fun channelFromSearch(c: org.json.JSONObject): Channel = Channel(
            id = c.optString("id"),
            login = c.optString("broadcaster_login"),
            displayName = c.optString("display_name"),
            description = c.optString("title"),
            followers = -1,
            artSeed = seedOf(c.optString("id")),
            avatarUrl = c.optString("thumbnail_url").takeIf { it.isNotBlank() },
        )

        fun streamFromSearch(c: org.json.JSONObject): Stream? {
            if (!c.optBoolean("is_live", true)) return null
            val ch = channelFromSearch(c)
            val title = c.optString("title").ifBlank { ch.displayName }
            return Stream(
                id = "search-${ch.id}",
                channel = ch,
                title = title,
                categoryId = c.optString("game_id"),
                categoryName = c.optString("game_name"),
                viewers = -1,
                language = c.optString("broadcaster_language"),
                isLive = true,
                artSeed = seedOf(ch.id),
                thumbnailUrl = null,
            )
        }

        val channels = allChJson.optJSONArray("data").objects().map(::channelFromSearch)
        val fromLiveSearch = liveJson.optJSONArray("data").objects().mapNotNull(::streamFromSearch)

        // Скан эфиров по страницам: сначала RU (типичный запрос «фильмы»), потом общий топ.
        // Helix search/channels почти не ищет по title — без этого «фильмы» даёт пусто.
        suspend fun scanStreams(language: String?, maxPages: Int): List<Stream> {
            val out = ArrayList<Stream>()
            var cursor: String? = null
            repeat(maxPages) {
                val params = mutableListOf("first" to "100")
                if (language != null) params += "language" to language
                if (!cursor.isNullOrBlank()) params += "after" to cursor
                val json = try {
                    api.get("streams", params)
                } catch (_: Exception) {
                    return out
                }
                val page = parseStreams(json)
                if (page.isEmpty()) return out
                for (s in page) {
                    val hay = listOf(s.title, s.channel.displayName, s.channel.login, s.categoryName)
                        .joinToString(" ")
                    if (hayMatches(hay)) out.add(s)
                }
                cursor = json.optJSONObject("pagination")?.optString("cursor")?.takeIf { it.isNotBlank() }
                if (cursor == null) return out
                if (out.size >= 50) return out
            }
            return out
        }

        val titleHits = LinkedHashMap<String, Stream>()
        // Русский и общий списки сканируем параллельно; глубина больше прежней (6×100 русских эфиров).
        val (ruHits, allHits) = coroutineScope {
            val ru = async { scanStreams("ru", 6) }
            val all = async { scanStreams(null, 3) }
            ru.await() to all.await()
        }
        (fromLiveSearch + ruHits + allHits).forEach {
            titleHits[it.channel.id] = it
        }

        // Эфиры из найденных категорий (Movies и т.д.) — тоже в «В эфире», с фильтром по фразе если категория широкая
        for (cat in catMap.values.take(5)) {
            try {
                val inCat = streamsByLanguage(listOf("game_id" to cat.id, "first" to "50"), "ru")
                    .ifEmpty { streamsByLanguage(listOf("game_id" to cat.id, "first" to "30"), null) }
                for (s in inCat) {
                    val hay = listOf(s.title, s.channel.displayName, s.categoryName).joinToString(" ")
                    // Категория уже по запросу — берём все; если запрос длинный — ещё фильтр по title
                    if (categoryQueries.size > 1 || hayMatches(hay) || tokens.any { hay.lowercase().contains(it) }) {
                        titleHits.putIfAbsent(s.channel.id, s)
                    }
                }
            } catch (_: Exception) { }
        }

        // Результаты search/channels не содержат зрителей и превью: подтягиваем настоящие эфиры по id каналов.
        val real = HashMap<String, Stream>()
        titleHits.values.filter { it.viewers < 0 }.map { it.channel.id }.distinct().chunked(100).forEach { chunk ->
            try {
                parseStreams(api.get("streams", chunk.map { "user_id" to it } + ("first" to "100")))
                    .forEach { real[it.channel.id] = it }
            } catch (_: Exception) { }
        }

        SearchResults(
            channels = channels,
            categories = catMap.values.toList(),
            streams = titleHits.values.map { real[it.channel.id] ?: it }.sortedByDescending { it.viewers }.toList(),
        )
    }
}
