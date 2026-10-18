package com.novastreams.app.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.chat.ChatHub
import com.novastreams.app.core.chat.ChatStatus
import com.novastreams.app.core.emotes.EmoteIndex
import com.novastreams.app.core.model.ChatMessage
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChannelContent(
    val channel: Channel,
    val stream: Stream?,
    val isDemo: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val container: AppContainer,
    private val channelId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<Load<ChannelContent>>(Load.Loading)
    val state: StateFlow<Load<ChannelContent>> = _state

    val isFavorite: StateFlow<Boolean> = container.settings.favoriteIds
        .map { channelId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Подписка (Follow) на самом Twitch — только проверка; создать Follow через API нельзя. */
    private val _followingOnTwitch = MutableStateFlow(false)
    val followingOnTwitch: StateFlow<Boolean> = _followingOnTwitch

    // Чат живёт в ChatHub (на уровне приложения): экран его только «берёт» и отдаёт, соединение не пересоздаётся.
    private val entry = MutableStateFlow<ChatHub.Entry?>(null)
    private val idleMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val idleStatus = MutableStateFlow<ChatStatus>(ChatStatus.Connecting)
    private val idleEmotes = MutableStateFlow(EmoteIndex.Empty)

    val chatMessages: StateFlow<List<ChatMessage>> = entry
        .flatMapLatest { it?.session?.messages ?: idleMessages }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chatStatus: StateFlow<ChatStatus> = entry
        .flatMapLatest { it?.session?.status ?: idleStatus }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatStatus.Connecting)

    val chatError = MutableStateFlow<String?>(null)

    /** Смайлики канала для чата и окна выбора (пока грузятся — пусто, чат показывает слова как есть). */
    val emotes: StateFlow<EmoteIndex> = entry
        .flatMapLatest { it?.emotes ?: idleEmotes }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EmoteIndex.Empty)

    init {
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            try {
                // Канал и эфир грузим параллельно — плеер стартует быстрее.
                val (channel, stream) = coroutineScope {
                    val c = async { container.twitch.channel(channelId) }
                    val st = async { container.twitch.streamForChannel(channelId) }
                    c.await() to st.await()
                }
                if (channel == null) {
                    _state.value = Load.Failed("Канал не найден")
                    return@launch
                }
                _state.value = Load.Ready(ChannelContent(channel, stream, isDemo = false))
                // Follow — лёгкий запрос Helix; без него кнопка «Подписаться» висит у уже подписанных.
                // Чат/смайлики по-прежнему только по setChatActive (не конкурируют с HLS).
                refreshFollowingStatus()
            } catch (e: Exception) {
                _state.value = Load.Failed(e.message ?: "Не удалось загрузить канал")
            }
        }
    }

    /**
     * Чат и смайлики включаем только когда панель чата реально открыта.
     * Пока смотрим только эфир — полоса и CPU только у плеера.
     */
    fun setChatActive(active: Boolean) {
        if (!active) return // соединение не рвём: свернули панель — сообщения продолжают приходить
        val content = (_state.value as? Load.Ready)?.value ?: return
        val current = entry.value
        if (current == null || current.channelId != content.channel.id) {
            entry.value = container.chats.acquire(content.channel.id, content.channel.login)
        }
        entry.value?.let { container.chats.ensureEmotes(it) }
    }

    fun sendChat(text: String) {
        if (entry.value == null) setChatActive(true)
        val current = entry.value
        chatError.value = if (current == null) "Чат ещё не подключён" else current.session.send(text)
    }

    fun clearChatError() {
        chatError.value = null
    }

    /** Обновить, подписан ли пользователь на канал на Twitch (после возврата из браузера). */
    fun refreshFollowingStatus() {
        viewModelScope.launch {
            _followingOnTwitch.value = try {
                container.twitch.isFollowing(channelId)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { container.settings.toggleFavorite(channelId) }
    }

    override fun onCleared() {
        // Не закрываем чат: ChatHub держит соединение ещё 10 минут (вернулись на канал — всё уже подключено).
        entry.value?.let { container.chats.release(it) }
        entry.value = null
    }
}
