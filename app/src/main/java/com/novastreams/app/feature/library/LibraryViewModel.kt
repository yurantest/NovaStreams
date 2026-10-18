package com.novastreams.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.FollowedChannel
import com.novastreams.app.core.model.Load
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryChannel(
    val channel: Channel,
    val isLive: Boolean,
    val viewers: Int,
    /** Избранное внутри приложения (у Twitch нет API, чтобы подписаться из стороннего приложения). */
    val isFavorite: Boolean,
)

/** Вкладки экрана «Каналы». */
enum class LibraryTab(val title: String) {
    /** Каналы, которые сейчас в эфире на языке приложения (регион). Без входа. */
    COMMON("Общие"),

    /** Подписки на Twitch. Показывается только после входа. */
    FOLLOWED("Подписки"),

    /** Локальное избранное: хранится на этом устройстве, с аккаунтом Twitch не связано. */
    FAVORITES("Избранное"),
}

/**
 * Экран «Каналы» из трёх вкладок: «Общие» (по региону), «Подписки» (после входа) и «Избранное» (локальное).
 * Вкладка «Общие» берёт данные из PagedStreamsViewModel (создаётся в самом экране), здесь — подписки и избранное.
 */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val tab = MutableStateFlow(LibraryTab.COMMON)

    fun selectTab(value: LibraryTab) {
        tab.value = value
    }

    private val loaded = MutableStateFlow<Load<List<FollowedChannel>>>(Load.Loading)

    /** Откуда данные: по нему экран решает, показывать ли плашку «демо». */
    val source = MutableStateFlow(DataSource.TWITCH)

    /** Причина показа демо-данных (null — данные настоящие). */
    val notice = MutableStateFlow<String?>(null)

    /** Вошёл ли пользователь: без входа вкладки «Подписки» нет. */
    val signedIn: StateFlow<Boolean> = container.auth.state
        .map { it is AuthState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.auth.state.value is AuthState.SignedIn)

    /** Идентификаторы избранных каналов (нужны вкладке «Общие», чтобы показать закрашенное сердечко). */
    val favoriteIds: StateFlow<Set<String>> = container.settings.favoriteIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Подписки Twitch: онлайн первыми, избранные выше. */
    val state: StateFlow<Load<List<LibraryChannel>>> = combine(
        loaded,
        container.settings.favoriteIds,
    ) { load, favorites ->
        when (load) {
            is Load.Loading -> Load.Loading
            is Load.Failed -> load
            is Load.Ready -> Load.Ready(
                load.value
                    .map {
                        LibraryChannel(
                            channel = it.channel,
                            isLive = it.stream != null,
                            viewers = it.stream?.viewers ?: 0,
                            isFavorite = it.channel.id in favorites,
                        )
                    }
                    // sortedBy стабилен: внутри групп сохраняется порядок «онлайн первыми, по зрителям».
                    .sortedByDescending { it.isFavorite }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)

    // ---------- Избранное (локальное) ----------

    private val favoriteLoaded = MutableStateFlow<Load<List<LibraryChannel>>>(Load.Loading)
    val favorites: StateFlow<Load<List<LibraryChannel>>> = favoriteLoaded

    /** Уже загруженные данные каналов: при добавлении/удалении сердечка не перезапрашиваем остальные. */
    private val channelCache = HashMap<String, Channel>()

    init {
        load()
        viewModelScope.launch {
            container.auth.state.drop(1).collect { load() }
        }
        viewModelScope.launch {
            container.settings.favoriteIds.collectLatest { ids -> loadFavorites(ids) }
        }
    }

    private suspend fun loadFavorites(ids: Set<String>) {
        if (ids.isEmpty()) {
            favoriteLoaded.value = Load.Ready(emptyList())
            return
        }
        // Первая загрузка — «крутилка»; при изменениях список обновляется без неё.
        if (favoriteLoaded.value !is Load.Ready) favoriteLoaded.value = Load.Loading
        try {
            val items = coroutineScope {
                ids.map { id ->
                    async {
                        try {
                            val channel = channelCache[id]
                                ?: container.twitch.channel(id)?.also { channelCache[id] = it }
                                ?: return@async null
                            // Статус «в эфире» нужен всегда свежий.
                            val stream = try {
                                container.twitch.streamForChannel(id)
                            } catch (e: Exception) {
                                null
                            }
                            LibraryChannel(channel, isLive = stream != null, viewers = stream?.viewers ?: 0, isFavorite = true)
                        } catch (e: Exception) {
                            // Канал, который не удалось загрузить, просто пропускаем.
                            null
                        }
                    }
                }.mapNotNull { it.await() }
            }
            favoriteLoaded.value = Load.Ready(
                items.sortedWith(compareByDescending<LibraryChannel> { it.isLive }.thenBy { it.channel.displayName.lowercase() })
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            favoriteLoaded.value = Load.Failed(e.message ?: "Не удалось загрузить избранное")
        }
    }

    private var loadedAt = 0L

    /** [soft] = true: показанный список остаётся на экране, ошибка его не затирает (обновление). */
    fun load(soft: Boolean = false) {
        if (!soft || loaded.value !is Load.Ready) loaded.value = Load.Loading
        viewModelScope.launch {
            try {
                val result = container.twitch.followedChannels()
                source.value = container.twitch.source
                notice.value = container.twitch.notice
                loaded.value = Load.Ready(result)
                loadedAt = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!soft || loaded.value !is Load.Ready) {
                    loaded.value = Load.Failed(e.message ?: "Не удалось загрузить каналы")
                }
            }
        }
    }

    /** Обновляет подписки и статус «в эфире» у избранных, не показывая экран загрузки. */
    fun refresh() {
        load(soft = true)
        reloadFavorites()
    }

    fun refreshIfStale(maxAgeMs: Long = com.novastreams.app.ui.components.STALE_AFTER_MS) {
        if (loadedAt != 0L && System.currentTimeMillis() - loadedAt > maxAgeMs) refresh()
    }

    fun reloadFavorites() {
        viewModelScope.launch { loadFavorites(favoriteIds.value) }
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { container.settings.toggleFavorite(channelId) }
    }
}
