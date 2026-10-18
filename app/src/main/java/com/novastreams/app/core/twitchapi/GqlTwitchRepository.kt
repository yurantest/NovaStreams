package com.novastreams.app.core.twitchapi

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.FollowedChannel
import com.novastreams.app.core.model.SearchResults
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.seedOf
import com.novastreams.app.core.network.Http
import com.novastreams.app.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * ВНИМАНИЕ: это НЕОФИЦИАЛЬНЫЙ путь. GraphQL (gql.twitch.tv) — внутренний интерфейс сайта twitch.tv,
 * он не документирован, Twitch может изменить или ограничить его в любой момент.
 * Используется только для чтения без входа. Запросы написаны по памяти и в среде разработки НЕ проверялись.
 * Если что-то не работает — приложение покажет понятную ошибку, а не упадёт.
 */
class GqlTwitchRepository(private val settings: SettingsRepository) : TwitchRepository {

    override val source: DataSource = DataSource.TWITCH

    /** Фрагмент с полями эфира — одинаковый во всех запросах. */
    private val streamFields = """
        id title language viewersCount previewImageURL(width: 440, height: 248)
        broadcaster { id login displayName language profileImageURL(width: 70) }
        game { id name }
    """.trimIndent()

    /** Выполняет GraphQL-запрос с переменными и возвращает объект "data". */
    private suspend fun query(text: String, variables: JSONObject = JSONObject()): JSONObject {
        val clientId = settings.publicClientId.first()
        val body = JSONObject().put("query", text).put("variables", variables).toString()
        val result = try {
            Http.execute(
                Request.Builder()
                    .url("https://gql.twitch.tv/gql")
                    .header("Client-Id", clientId)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        } catch (e: IOException) {
            throw ApiException("Нет связи с Twitch. Проверьте интернет")
        }
        val json = result.json()
        if (!result.isSuccess) {
            throw ApiException("Twitch не принял запрос (код ${result.code}). Публичный Client ID мог устареть", result.code)
        }
        val data = json.optJSONObject("data")
        if (data == null || json.has("errors") && data.length() == 0) {
            val msg = json.optJSONArray("errors").objects().firstOrNull()?.optString("message").orEmpty()
            throw ApiException("Ответ Twitch не разобран: ${msg.ifBlank { "неизвестная ошибка" }}")
        }
        return data
    }

    private fun parseStream(node: JSONObject): Stream? {
        val broadcaster = node.optJSONObject("broadcaster") ?: return null
        val game = node.optJSONObject("game")
        val userId = broadcaster.optString("id")
        return Stream(
            id = node.optString("id"),
            channel = Channel(
                id = userId,
                login = broadcaster.optString("login"),
                displayName = broadcaster.optString("displayName"),
                description = "",
                followers = -1,
                artSeed = seedOf(userId),
                avatarUrl = broadcaster.optString("profileImageURL").takeIf { it.isNotBlank() },
            ),
            title = node.optString("title").ifBlank { "Без названия" },
            categoryId = game?.optString("id").orEmpty(),
            categoryName = game?.optString("name")?.ifBlank { null } ?: "Без категории",
            viewers = node.optInt("viewersCount"),
            // Язык эфира: у стрима, иначе у канала (как на сайте / в SmartTwitchTV).
            language = node.optString("language").ifBlank {
                broadcaster.optString("language")
            }.uppercase().takeIf { it.isNotBlank() } ?: "",
            artSeed = seedOf(node.optString("id")),
            thumbnailUrl = node.optString("previewImageURL").takeIf { it.isNotBlank() }?.let(::freshPreview),
        )
    }

    private fun parseStreamEdges(container: JSONObject?): List<Stream> =
        container?.optJSONArray("edges").objects().mapNotNull { it.optJSONObject("node")?.let(::parseStream) }

    private fun parseCategory(node: JSONObject) = Category(
        id = node.optString("id"),
        name = node.optString("name"),
        viewers = if (node.has("viewersCount")) node.optInt("viewersCount") else -1,
        artSeed = seedOf(node.optString("id")),
        boxArtUrl = node.optString("boxArtURL").takeIf { it.isNotBlank() },
    )

    @Volatile
    override var languageNote: String? = null
        private set

    /** Страница эфиров и курсор для следующей страницы (null — страниц больше нет). */
    private class Page(val streams: List<Stream>, val cursor: String?)

    /** Читает до [pages] страниц подряд; [serverFilter] — язык для фильтра сервера или null. */
    private suspend fun loadPages(
        load: suspend (serverFilter: String?, after: String?) -> Page,
        serverFilter: String?,
        pages: Int,
    ): List<Stream> {
        val result = mutableListOf<Stream>()
        var cursor: String? = null
        repeat(pages) {
            val page = load(serverFilter, cursor)
            result += page.streams
            cursor = page.cursor
            if (cursor == null) return result.distinctBy { it.id }
        }
        return result.distinctBy { it.id }
    }

    /**
     * Языковой фильтр как у сайта Twitch и SmartTwitchTV:
     * options: { sort: VIEWER_COUNT, languages: "RU" }.
     * Если сервер вернул смесь — оставляем только нужный язык на клиенте.
     * Без «подмешивания» глобального топа: при включённом фильтре показываем только свой язык.
     */
    private suspend fun withLanguage(
        language: String?,
        load: suspend (serverFilter: String?, after: String?) -> Page,
    ): List<Stream> {
        languageNote = null
        // Страниц читаем немного: главной хватает нескольких десятков эфиров, а каждая страница — отдельный запрос,
        // тяжёлый для слабой приставки и медленной сети.
        if (language == null) return loadPages(load, null, 3)

        val code = language.uppercase()
        val serverFiltered = try {
            loadPages(load, code, 4)
        } catch (_: ApiException) {
            emptyList()
        }

        // Жёстко оставляем только нужный язык (сервер иногда отдаёт лишнее).
        val matching = serverFiltered.filter { it.language.equals(code, ignoreCase = true) }
        if (matching.isNotEmpty()) return matching.distinctBy { it.id }

        // Запасной путь: общая выдача, потом клиентский фильтр.
        val all = try {
            loadPages(load, null, 8)
        } catch (_: ApiException) {
            emptyList()
        }
        val fromAll = all.filter { it.language.equals(code, ignoreCase = true) }
        if (fromAll.isNotEmpty()) {
            languageNote = "Языковой фильтр Twitch недоступен; показаны эфиры на языке $code из общей выдачи"
            return fromAll.distinctBy { it.id }
        }

        languageNote = "Сейчас нет эфиров на языке $code"
        return emptyList()
    }

    /**
     * Аргументы как в SmartTwitchTV:
     * options:{sort:VIEWER_COUNT, languages:"RU"} + after cursor.
     * Не broadcasterLanguages — этот вариант на gql.twitch.tv часто игнорируется.
     */
    private fun streamArgs(language: String?, after: String?, tags: List<String>? = null): String {
        val safeAfter = after?.filter { it.isLetterOrDigit() || it in "=+/_-" }
        val options = buildString {
            append("sort: VIEWER_COUNT")
            if (language != null) {
                val safe = language.filter { it.isLetter() }.uppercase()
                // Language — это enum в схеме Twitch: значение пишется БЕЗ кавычек (languages: RU), как в SmartTwitchTV
                // (',languages:' + код). С кавычками Twitch отвечает: Enum "Language" cannot represent non-enum value.
                if (safe.isNotEmpty()) append(", languages: $safe")
            }
            // Теги — как на странице directory/all/tags/<тег>: tags: ["фильмы"]. Строки экранируем.
            val cleanTags = tags.orEmpty().map { it.replace("\\", "").replace("\"", "").trim() }.filter { it.isNotEmpty() }
            if (cleanTags.isNotEmpty()) append(", tags: [" + cleanTags.joinToString(",") { "\"$it\"" } + "]")
        }
        return ", options: { $options }" +
            (if (safeAfter.isNullOrEmpty()) "" else ", after: \"$safeAfter\"")
    }

    private fun parsePage(container: JSONObject?): Page {
        val edges = container?.optJSONArray("edges").objects()
        val streams = edges.mapNotNull { it.optJSONObject("node")?.let(::parseStream) }
        // Курсор берём у последнего элемента, даже если порция короче 30: сервер при фильтре языка
        // может отдавать меньше, а эфиры дальше всё равно есть. Конец — пустая порция.
        val cursor = edges.lastOrNull()?.optString("cursor")?.takeIf { it.isNotBlank() }
        return Page(streams, cursor)
    }

    override suspend fun liveStreams(language: String?): List<Stream> = withLanguage(language) { lang, after ->
        val data = query("query { streams(first: 30${streamArgs(lang, after)}) { edges { cursor node { $streamFields } } } }")
        parsePage(data.optJSONObject("streams"))
    }

    /** false — сервер отверг серверный фильтр языка; тогда фильтруем на клиенте. */
    @Volatile
    private var serverLanguageOk = true

    /** Одна страница эфиров (30 штук) по категории или всех, с серверным фильтром языка. */
    private suspend fun fetchPage(categoryId: String?, language: String?, after: String?, tags: List<String>? = null): Page =
        if (categoryId == null) {
            val data = query("query { streams(first: 30${streamArgs(language, after, tags)}) { edges { cursor node { $streamFields } } } }")
            parsePage(data.optJSONObject("streams"))
        } else {
            val data = query(
                "query(\$id: ID!) { game(id: \$id) { streams(first: 30${streamArgs(language, after)}) " +
                    "{ edges { cursor node { $streamFields } } } } }",
                JSONObject().put("id", categoryId),
            )
            parsePage(data.optJSONObject("game")?.optJSONObject("streams"))
        }

    /**
     * Порция для бесконечной прокрутки: читаем страницы, пока не наберётся ~24 эфира нужного языка
     * (но не больше 6 запросов за раз). Курсор возвращаем для следующей порции.
     */
    override suspend fun streamsPage(categoryId: String?, language: String?, after: String?): StreamsPage {
        val code = language?.filter { it.isLetter() }?.uppercase()?.takeIf { it.isNotEmpty() }
        val found = mutableListOf<Stream>()
        var cursor = after
        var fetches = 0
        while (fetches < 6 && found.size < 24) {
            // Если Twitch не принял код языка как enum — дальше листаем без серверного фильтра и фильтруем сами.
            val page = try {
                fetchPage(categoryId, if (serverLanguageOk) code else null, cursor)
            } catch (e: ApiException) {
                if (code != null && serverLanguageOk) {
                    serverLanguageOk = false
                    cursor = after
                    found.clear()
                    fetchPage(categoryId, null, cursor)
                } else {
                    throw e
                }
            }
            fetches++
            found += if (code == null) page.streams else page.streams.filter { it.language.equals(code, ignoreCase = true) }
            cursor = page.cursor
            if (cursor == null) break
        }
        if (after == null && found.isEmpty()) {
            languageNote = if (code != null) "Сейчас нет эфиров на языке $code" else null
        } else {
            languageNote = null
        }
        return StreamsPage(found.distinctBy { it.id }, cursor)
    }

    // Без входа подписок нет.
    override suspend fun followedLive(): List<Stream> = emptyList()
    override suspend fun followedChannels(): List<FollowedChannel> = emptyList()

    override suspend fun topCategories(): List<Category> {
        // Краткая лента для главной.
        return categoriesPage(after = null).categories.take(30)
    }

    override suspend fun categoriesPage(after: String?): CategoriesPage {
        val data = if (after.isNullOrBlank()) {
            query(
                "query { games(first: 100) { edges { cursor node { id name viewersCount boxArtURL(width: 285, height: 380) } } } }"
            )
        } else {
            val vars = org.json.JSONObject().put("after", after)
            query(
                "query(\$after: Cursor) { games(first: 100, after: \$after) { edges { cursor node { id name viewersCount boxArtURL(width: 285, height: 380) } } } }",
                vars,
            )
        }
        val edges = data.optJSONObject("games")?.optJSONArray("edges").objects()
        val list = edges.mapNotNull { it.optJSONObject("node")?.let(::parseCategory) }
        val next = edges.lastOrNull()?.optString("cursor")?.takeIf { it.isNotBlank() }
        val cursor = if (list.size >= 50 && next != null) next else null
        return CategoriesPage(list, cursor)
    }

    override suspend fun streamsForCategory(categoryId: String, language: String?): List<Stream> =
        withLanguage(language) { lang, after ->
            val data = query(
                "query(\$id: ID!) { game(id: \$id) { streams(first: 30${streamArgs(lang, after)}) " +
                    "{ edges { cursor node { $streamFields } } } } }",
                JSONObject().put("id", categoryId),
            )
            parsePage(data.optJSONObject("game")?.optJSONObject("streams"))
        }

    override suspend fun categoryName(categoryId: String): String? {
        val data = query("query(\$id: ID!) { game(id: \$id) { name } }", JSONObject().put("id", categoryId))
        return data.optJSONObject("game")?.optString("name")
    }

    override suspend fun streamForChannel(channelId: String): Stream? {
        val data = query(
            "query(\$id: ID!) { user(id: \$id) { stream { $streamFields } } }",
            JSONObject().put("id", channelId),
        )
        return data.optJSONObject("user")?.optJSONObject("stream")?.let(::parseStream)
    }

    override suspend fun channel(channelId: String): Channel? {
        val data = query(
            "query(\$id: ID!) { user(id: \$id) { id login displayName description profileImageURL(width: 70) } }",
            JSONObject().put("id", channelId),
        )
        val u = data.optJSONObject("user") ?: return null
        return Channel(
            id = u.optString("id"),
            login = u.optString("login"),
            displayName = u.optString("displayName"),
            description = u.optString("description"),
            followers = -1,
            artSeed = seedOf(channelId),
            avatarUrl = u.optString("profileImageURL").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Эфиры по ТЕГУ трансляции — то же, что страница twitch.tv/directory/all/tags/<тег>.
     * Для запроса про фильмы пробуем теги «фильмы», «кино», «movies». Русские эфиры идут первыми (4 стр.),
     * затем общий список (2 стр.). Если сервер не принял тег — пустой список, поиск продолжается обычным путём.
     */
    suspend fun streamsByTags(text: String): List<Stream> {
        val matcher = QueryMatcher(text)
        val tags = LinkedHashSet<String>()
        val q = text.trim().lowercase()
        if (q.isNotEmpty()) tags += q
        if (matcher.isMovieQuery) tags += listOf("фильмы", "кино", "movies")
        if (tags.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Stream>()
        for (tag in tags.take(4)) {
            for ((lang, pages) in listOf("RU" to 4, null to 2)) {
                var after: String? = null
                try {
                    repeat(pages) {
                        val page = fetchPage(null, lang, after, listOf(tag))
                        if (page.streams.isEmpty()) return@repeat
                        page.streams.forEach { out.putIfAbsent(it.channel.id, it) }
                        after = page.cursor
                        if (after.isNullOrBlank()) return@repeat
                    }
                } catch (_: Exception) { }
            }
        }
        return out.values.toList()
    }

    override suspend fun search(text: String): SearchResults {
        val q = text.trim()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val qLower = q.lowercase()
        // Слова без окончаний и близкие слова («фильмы» находит «фильм», «кино», movie…).
        val matcher = QueryMatcher(q)

        fun hayMatches(hay: String): Boolean = matcher.matches(hay) || hay.lowercase().contains(qLower)

        val vars = JSONObject().put("q", q)
        val users = query(
            "query(\$q: String!) { searchUsers(userQuery: \$q, first: 25) { edges { node { id login displayName profileImageURL(width: 70) description } } } }",
            vars,
        )
        val categoryQueries = linkedSetOf(q)
        if (qLower.contains("фильм") || qLower.contains("кино")) {
            categoryQueries += listOf("Movies", "Watch Parties")
        }
        val catMap = LinkedHashMap<String, Category>()
        for (cq in categoryQueries) {
            try {
                val games = query(
                    "query(\$q: String!) { searchCategories(userQuery: \$q, first: 15) { edges { node { id name boxArtURL(width: 285, height: 380) } } } }",
                    JSONObject().put("q", cq),
                )
                games.optJSONObject("searchCategories")?.optJSONArray("edges").objects()
                    .mapNotNull { it.optJSONObject("node")?.let(::parseCategory) }
                    .forEach { catMap[it.id] = it }
            } catch (_: Exception) { }
        }

        val channels = users.optJSONObject("searchUsers")?.optJSONArray("edges").objects().mapNotNull { e ->
            e.optJSONObject("node")?.let { n ->
                Channel(
                    id = n.optString("id"),
                    login = n.optString("login"),
                    displayName = n.optString("displayName"),
                    description = n.optString("description"),
                    followers = -1,
                    artSeed = seedOf(n.optString("id")),
                    avatarUrl = n.optString("profileImageURL").takeIf { it.isNotBlank() },
                )
            }
        }

        // Скан эфиров по страницам (GQL first:30 + cursor), язык RU затем все.
        suspend fun scan(lang: String?, pages: Int): List<Stream> {
            val out = ArrayList<Stream>()
            var after: String? = null
            repeat(pages) {
                val page = try {
                    fetchPage(null, lang, after)
                } catch (_: Exception) {
                    return out
                }
                if (page.streams.isEmpty()) return out
                for (s in page.streams) {
                    val hay = listOf(s.title, s.channel.displayName, s.channel.login, s.categoryName)
                        .joinToString(" ")
                    if (hayMatches(hay)) out.add(s)
                }
                after = page.cursor
                if (after.isNullOrBlank()) return out
                if (out.size >= 80) return out
            }
            return out
        }

        val titleHits = LinkedHashMap<String, Stream>()
        // Русский и общий списки сканируем параллельно и глубже прежнего (10 страниц RU вместо 5).
        val (ruHits, allHits) = coroutineScope {
            val ru = async { scan("RU", 10) }
            val all = async { scan(null, 4) }
            ru.await() to all.await()
        }
        (ruHits + allHits).forEach { titleHits[it.channel.id] = it }

        for (cat in catMap.values.take(5)) {
            try {
                var after: String? = null
                repeat(2) {
                    val page = fetchPage(cat.id, "RU", after)
                    if (page.streams.isEmpty() && after == null) {
                        val page2 = fetchPage(cat.id, null, null)
                        page2.streams.forEach { titleHits.putIfAbsent(it.channel.id, it) }
                        return@repeat
                    }
                    page.streams.forEach { titleHits.putIfAbsent(it.channel.id, it) }
                    after = page.cursor
                    if (after.isNullOrBlank()) return@repeat
                }
            } catch (_: Exception) { }
        }

        return SearchResults(
            channels = channels,
            categories = catMap.values.toList(),
            streams = titleHits.values.sortedByDescending { it.viewers }.toList(),
        )
    }
}
