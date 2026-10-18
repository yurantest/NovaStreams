package com.novastreams.app.feature.streamer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.video.ChannelVideo
import com.novastreams.app.core.video.ClipPeriod
import com.novastreams.app.core.video.VideoKind
import com.novastreams.app.feature.channel.ChannelContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StreamerTab(val title: String) {
    LIVE("Эфир"),
    VODS("Записи"),
    CLIPS("Клипы"),
}

/** Состояние одного списка (записи или клипы). */
data class VideoListState(
    val items: List<ChannelVideo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Список закончился (или пуст) — подгружать больше нечего. */
    val ended: Boolean = false,
    /** Первая порция уже запрошена. */
    val started: Boolean = false,
)

/** Страница стримера: сведения о канале и два списка — записи эфиров и клипы. */
class StreamerViewModel(
    private val container: AppContainer,
    private val channelId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<Load<ChannelContent>>(Load.Loading)
    val state: StateFlow<Load<ChannelContent>> = _state

    val isFavorite: StateFlow<Boolean> = container.settings.favoriteIds
        .map { channelId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _tab = MutableStateFlow(StreamerTab.LIVE)
    val tab: StateFlow<StreamerTab> = _tab

    private val _vods = MutableStateFlow(VideoListState())
    val vods: StateFlow<VideoListState> = _vods

    private val _clips = MutableStateFlow(VideoListState())
    val clips: StateFlow<VideoListState> = _clips

    private val _period = MutableStateFlow(ClipPeriod.ALL)
    val period: StateFlow<ClipPeriod> = _period

    private var vodCursor: String? = null
    private var clipCursor: String? = null
    private var vodBusy = false
    private var clipBusy = false
    // Сколько раз подряд порция не добавила новых карточек: защита от бесконечной подгрузки.
    private var vodEmpty = 0
    private var clipEmpty = 0

    init {
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            try {
                val (channel, stream) = coroutineScope {
                    val c = async { container.twitch.channel(channelId) }
                    val s = async { runCatching { container.twitch.streamForChannel(channelId) }.getOrNull() }
                    c.await() to s.await()
                }
                if (channel == null) {
                    _state.value = Load.Failed("Канал не найден")
                    return@launch
                }
                _state.value = Load.Ready(ChannelContent(channel, stream, isDemo = false))
            } catch (e: Exception) {
                _state.value = Load.Failed(e.message ?: "Не удалось загрузить канал")
            }
        }
    }

    fun selectTab(tab: StreamerTab) {
        _tab.value = tab
        when (tab) {
            StreamerTab.VODS -> if (!_vods.value.started) loadMoreVods()
            StreamerTab.CLIPS -> if (!_clips.value.started) loadMoreClips()
            StreamerTab.LIVE -> Unit
        }
    }

    fun setPeriod(period: ClipPeriod) {
        if (period == _period.value) return
        _period.value = period
        clipCursor = null
        clipEmpty = 0
        _clips.value = VideoListState()
        loadMoreClips()
    }

    fun toggleFavorite() {
        viewModelScope.launch { container.settings.toggleFavorite(channelId) }
    }

    fun loadMore(tab: StreamerTab) {
        when (tab) {
            StreamerTab.VODS -> loadMoreVods()
            StreamerTab.CLIPS -> loadMoreClips()
            StreamerTab.LIVE -> Unit
        }
    }

    private fun login(): String? = (_state.value as? Load.Ready)?.value?.channel?.login

    private fun loadMoreVods() {
        val login = login() ?: return
        if (vodBusy || _vods.value.ended) return
        vodBusy = true
        _vods.value = _vods.value.copy(loading = true, error = null, started = true)
        viewModelScope.launch {
            try {
                val page = container.videos.vods(login, vodCursor)
                val before = _vods.value.items.size
                val merged = (_vods.value.items + page.items).distinctBy { it.id }
                vodCursor = page.cursor
                vodEmpty = if (merged.size == before) vodEmpty + 1 else 0
                _vods.value = _vods.value.copy(
                    items = merged,
                    ended = page.cursor == null || vodEmpty >= 3,
                )
            } catch (e: Exception) {
                _vods.value = _vods.value.copy(error = e.message ?: "Не удалось загрузить записи")
            } finally {
                vodBusy = false
                _vods.value = _vods.value.copy(loading = false)
            }
        }
    }

    private fun loadMoreClips() {
        val login = login() ?: return
        if (clipBusy || _clips.value.ended) return
        clipBusy = true
        val period = _period.value
        _clips.value = _clips.value.copy(loading = true, error = null, started = true)
        viewModelScope.launch {
            try {
                val page = container.videos.clips(login, period, clipCursor)
                // Пока ждали ответ, пользователь мог сменить период — тогда порция уже не нужна.
                if (period != _period.value) return@launch
                val before = _clips.value.items.size
                val merged = (_clips.value.items + page.items).distinctBy { it.id }
                clipCursor = page.cursor
                clipEmpty = if (merged.size == before) clipEmpty + 1 else 0
                _clips.value = _clips.value.copy(
                    items = merged,
                    ended = page.cursor == null || clipEmpty >= 3,
                )
            } catch (e: Exception) {
                if (period == _period.value) {
                    _clips.value = _clips.value.copy(error = e.message ?: "Не удалось загрузить клипы")
                }
            } finally {
                clipBusy = false
                if (period == _period.value) _clips.value = _clips.value.copy(loading = false)
                else loadMoreClips()
            }
        }
    }

    /** Подпись для пустого списка. */
    fun emptyText(kind: VideoKind): String = when (kind) {
        VideoKind.VOD -> "У канала нет записей эфиров (или стример их не сохраняет)"
        VideoKind.CLIP -> "За этот период клипов нет"
    }
}
