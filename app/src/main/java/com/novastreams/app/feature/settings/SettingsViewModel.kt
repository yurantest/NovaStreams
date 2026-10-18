package com.novastreams.app.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.BuildConfig
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.notifications.NotificationScheduler
import com.novastreams.app.core.settings.LanguageSupport
import com.novastreams.app.core.settings.SettingsRepository
import com.novastreams.app.core.stream.DefaultPlaybackQuality
import com.novastreams.app.core.stream.StreamSourceMode
import com.novastreams.app.ui.theme.colorToHex
import com.novastreams.app.ui.theme.parseHexColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Состояние редактирования акцента: введённый текст, результат проверки и предпросмотр. */
data class AccentEditorState(
    val input: String,
    val isValid: Boolean,
    val preview: Color?,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val draft = MutableStateFlow<String?>(null)

    /** Сохранённый акцент (из DataStore). */
    val savedAccentHex: StateFlow<String> = container.settings.accentHex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_ACCENT_HEX)

    val editor: StateFlow<AccentEditorState> = combine(draft, savedAccentHex) { d, saved ->
        val text = d ?: saved
        val parsed = parseHexColor(text)
        AccentEditorState(input = text, isValid = parsed != null, preview = parsed)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AccentEditorState(SettingsRepository.DEFAULT_ACCENT_HEX, true, parseHexColor(SettingsRepository.DEFAULT_ACCENT_HEX)),
    )

    // ---------- Источник данных (Client ID) ----------

    /** Свой Client ID для входа. Если он задан в сборке (local.properties), поле в настройках игнорируется. */
    val ownClientId: StateFlow<String> = container.settings.clientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Публичный Client ID для чтения без входа (по умолчанию — значение по умолчанию из настроек). */
    val publicClientId: StateFlow<String> = container.settings.publicClientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_PUBLIC_CLIENT_ID)

    /** Client ID для входа есть всегда: свой, из сборки или встроенный по умолчанию. */
    val hasOwnClientId: StateFlow<Boolean> = MutableStateFlow(true)

    fun saveOwnClientId(value: String) {
        viewModelScope.launch { container.settings.setClientId(value) }
    }

    fun savePublicClientId(value: String) {
        viewModelScope.launch { container.settings.setPublicClientId(value) }
    }

    /** Возвращает публичный Client ID к значению по умолчанию. */
    fun resetPublicClientId() {
        viewModelScope.launch { container.settings.setPublicClientId("") }
    }

    // ---------- Рекомендации по языку ----------

    val languageRecommendations: StateFlow<Boolean> = container.settings.languageRecommendations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Язык системы в виде кода Twitch; null — такой язык не поддерживается. */
    val systemLanguage: String? get() = LanguageSupport.systemTwitchLanguage()

    fun setLanguageRecommendations(enabled: Boolean) {
        viewModelScope.launch { container.settings.setLanguageRecommendations(enabled) }
    }

    // ---------- Скрытые категории ----------

    val hiddenCategories: StateFlow<Map<String, String>> = container.hiddenCategories

    fun unhideCategory(id: String) {
        viewModelScope.launch { container.settings.unhideCategory(id) }
    }

    // ---------- Поверхность видео ----------

    val useSurfaceView: StateFlow<Boolean> = container.settings.useSurfaceView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUseSurfaceView(enabled: Boolean) {
        viewModelScope.launch { container.settings.setUseSurfaceView(enabled) }
    }

    val videoFillScreen: StateFlow<Boolean> = container.settings.videoFillScreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val videoResizeMode: StateFlow<Int> = container.settings.videoResizeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val tvClockEnabled: StateFlow<Boolean> = container.settings.tvClockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val tvClockRight: StateFlow<Boolean> = container.settings.tvClockRight
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val tvClockSize: StateFlow<Int> = container.settings.tvClockSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    fun setTvClockEnabled(v: Boolean) { viewModelScope.launch { container.settings.setTvClockEnabled(v) } }
    fun setTvClockRight(v: Boolean) { viewModelScope.launch { container.settings.setTvClockRight(v) } }
    fun setTvClockSize(v: Int) { viewModelScope.launch { container.settings.setTvClockSize(v) } }

    fun setVideoResizeMode(mode: Int) {
        viewModelScope.launch { container.settings.setVideoResizeMode(mode) }
    }

    fun setVideoFillScreen(enabled: Boolean) {
        viewModelScope.launch { container.settings.setVideoFillScreen(enabled) }
    }

    val webChatCursor: StateFlow<Boolean> = container.settings.webChatCursor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWebChatCursor(enabled: Boolean) {
        viewModelScope.launch { container.settings.setWebChatCursor(enabled) }
    }

    val chatPanelRight: StateFlow<Boolean> = container.settings.chatPanelRight
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setChatPanelRight(right: Boolean) {
        viewModelScope.launch { container.settings.setChatPanelRight(right) }
    }

    val chatBackend: StateFlow<String> = container.settings.chatBackend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "irc")

    fun setChatBackend(backend: String) {
        viewModelScope.launch { container.settings.setChatBackend(backend) }
    }

    // ---------- Источник потока ----------

    val streamSource: StateFlow<StreamSourceMode> = container.settings.streamSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamSourceMode.EXPERIMENTAL)

    fun setStreamSource(mode: StreamSourceMode) {
        viewModelScope.launch { container.settings.setStreamSource(mode) }
    }

    val defaultPlaybackQuality: StateFlow<DefaultPlaybackQuality> = container.settings.defaultPlaybackQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultPlaybackQuality.P720)

    fun setDefaultPlaybackQuality(quality: DefaultPlaybackQuality) {
        viewModelScope.launch { container.settings.setDefaultPlaybackQuality(quality) }
    }

    val playerDiagnostics: StateFlow<Boolean> = container.settings.playerDiagnostics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setPlayerDiagnostics(enabled: Boolean) {
        viewModelScope.launch { container.settings.setPlayerDiagnostics(enabled) }
    }





    // ---------- Избранное ----------

    val favoriteIds: StateFlow<Set<String>> = container.settings.favoriteIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val favoriteChannels = MutableStateFlow<List<Channel>>(emptyList())

    init {
        viewModelScope.launch {
            container.settings.favoriteIds.collect { ids ->
                // Данные канала берём по одному; тот, что не удалось загрузить, просто пропускаем.
                favoriteChannels.value = ids.mapNotNull { id ->
                    try {
                        container.twitch.channel(id)
                    } catch (e: Exception) {
                        null
                    }
                }.sortedBy { it.displayName.lowercase() }
            }
        }
    }

    // ---------- Уведомления ----------

    val notificationsEnabled: StateFlow<Boolean> = container.settings.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setNotificationsEnabled(enabled)
            NotificationScheduler.apply(container.appContext, enabled)
        }
    }

    // ---------- Акцент ----------

    fun onHexInput(text: String) {
        draft.value = text
    }

    /** Применяет введённый HEX, только если он корректен. Предпросмотр уже виден через [editor]. */
    fun applyDraft() {
        val color = parseHexColor(editor.value.input) ?: return
        selectColor(color)
    }

    fun selectColor(color: Color) {
        draft.value = null
        viewModelScope.launch { container.settings.setAccentHex(colorToHex(color)) }
    }

    fun resetAccent() {
        draft.value = null
        viewModelScope.launch { container.settings.setAccentHex(SettingsRepository.DEFAULT_ACCENT_HEX) }
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { container.settings.toggleFavorite(channelId) }
    }
}
