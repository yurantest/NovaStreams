package com.novastreams.app.core.twitchapi

import com.novastreams.app.core.auth.AuthRepository
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.FollowedChannel
import com.novastreams.app.core.model.SearchResults
import com.novastreams.app.core.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Выбирает источник данных:
 *  - пользователь вошёл → официальный Helix API;
 *  - не вошёл → публичное чтение через GraphQL (как на сайте без логина).
 * Демо-данных нет: при ошибке исключение уходит наверх, интерфейс предлагает «Обновить».
 */
class RoutingTwitchRepository(
    private val auth: AuthRepository,
    private val helix: TwitchRepository,
    private val gql: TwitchRepository,
) : TwitchRepository {

    @Volatile
    override var source: DataSource = DataSource.TWITCH
        private set

    /** Раньше — причина демо; теперь всегда null (демо отключено). */
    @Volatile
    var notice: String? = null
        private set

    override val languageNote: String?
        get() = (if (signedIn) helix else gql).languageNote

    private val signedIn: Boolean get() = auth.state.value is AuthState.SignedIn

    // Все обращения к Twitch (разбор JSON, фильтрация списков) выполняются в фоновом пуле потоков, а не на главном
    // потоке, который рисует интерфейс: ViewModel запускает корутины на главном, и без withContext разбор ответов
    // шёл прямо там — на слабых процессорах это даёт «подвисания» при загрузке списков.
    private suspend fun <T> route(call: suspend (TwitchRepository) -> T): T = withContext(Dispatchers.Default) {
        val primary = if (signedIn) helix else gql
        val result = try {
            call(primary)
        } catch (e: org.json.JSONException) {
            throw ApiException("Не удалось разобрать ответ Twitch")
        }
        source = DataSource.TWITCH
        notice = null
        result
    }

    override suspend fun liveStreams(language: String?): List<Stream> = route { it.liveStreams(language) }
    override suspend fun streamsPage(categoryId: String?, language: String?, after: String?): StreamsPage =
        route { it.streamsPage(categoryId, language, after) }
    override suspend fun topCategories(): List<Category> = route { it.topCategories() }
    override suspend fun categoriesPage(after: String?): CategoriesPage =
        route { it.categoriesPage(after) }
    override suspend fun streamsForCategory(categoryId: String, language: String?): List<Stream> =
        route { it.streamsForCategory(categoryId, language) }
    override suspend fun categoryName(categoryId: String): String? = route { it.categoryName(categoryId) }
    override suspend fun streamForChannel(channelId: String): Stream? = route { it.streamForChannel(channelId) }
    override suspend fun channel(channelId: String): Channel? = route { it.channel(channelId) }
    override suspend fun search(query: String): SearchResults {
        val base = route { it.search(query) }
        // Поиск по тегу (как directory/all/tags/<тег> на сайте): публичный GQL, работает и после входа,
        // потому что Helix не умеет фильтровать эфиры по тегу.
        val byTag = try {
            withContext(Dispatchers.Default) { (gql as? GqlTwitchRepository)?.streamsByTags(query).orEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
        if (byTag.isEmpty()) return base
        val merged = LinkedHashMap<String, Stream>()
        byTag.forEach { merged[it.channel.id] = it }
        base.streams.forEach { merged.putIfAbsent(it.channel.id, it) }
        return base.copy(streams = merged.values.sortedByDescending { it.viewers })
    }

    /** Подписки только после входа; без входа — пустой список (не демо). */
    override suspend fun followedLive(): List<Stream> = withContext(Dispatchers.Default) {
        if (signedIn) helix.followedLive().also { source = DataSource.TWITCH; notice = null }
        else emptyList()
    }

    override suspend fun isFollowing(broadcasterId: String): Boolean =
        route { it.isFollowing(broadcasterId) }

    override suspend fun followedChannels(): List<FollowedChannel> = withContext(Dispatchers.Default) {
        if (signedIn) helix.followedChannels().also { source = DataSource.TWITCH; notice = null }
        else emptyList()
    }
}
