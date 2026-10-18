package com.novastreams.app.core.settings

import android.content.Context
import com.novastreams.app.core.stream.DefaultPlaybackQuality
import com.novastreams.app.core.stream.StreamSourceMode
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.novaDataStore by preferencesDataStore(name = "nova_settings")

/** Настройки приложения в Preferences DataStore: акцентный цвет, избранное, история поиска. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val accent = stringPreferencesKey("accent_hex")
        val favorites = stringSetPreferencesKey("favorite_channel_ids")
        val searchHistory = stringPreferencesKey("search_history")
        val clientId = stringPreferencesKey("twitch_client_id")
        val publicClientId = stringPreferencesKey("public_client_id")
        val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
        val languageRecommendations = booleanPreferencesKey("language_recommendations")
        val streamSource = stringPreferencesKey("stream_source")
        val defaultPlaybackQuality = stringPreferencesKey("default_playback_quality")
        val useSurfaceView = booleanPreferencesKey("use_surface_view")
        val videoFillScreen = booleanPreferencesKey("video_fill_screen")
        val videoResizeMode = intPreferencesKey("video_resize_mode")
        val tvClockEnabled = booleanPreferencesKey("tv_clock_enabled")
        val tvClockRight = booleanPreferencesKey("tv_clock_right")
        val tvClockSize = intPreferencesKey("tv_clock_size")
        val hiddenCategories = stringSetPreferencesKey("hidden_categories")
        val notifiedStreamIds = stringSetPreferencesKey("notified_stream_ids")
        val playerDiagnostics = booleanPreferencesKey("player_diagnostics")
        /** true — кружок-курсор в веб-чате; false — только стрелки (как обычный фокус). */
        val webChatCursor = booleanPreferencesKey("web_chat_cursor")
        /** true — чат справа (по умолчанию), false — слева. */
        val chatPanelRight = booleanPreferencesKey("chat_panel_right")
        /** "irc" — нативный чат; "web" — WebView (нужна авторизация). */
        val chatBackend = stringPreferencesKey("chat_backend")
    }

    /** Client ID приложения Twitch, введённый пользователем (если не задан в сборке). Это публичное значение. */
    val clientId: Flow<String> =
        context.novaDataStore.data.map { it[Keys.clientId] ?: "" }

    /**
     * Публичный Client ID для чтения эфиров без входа. По умолчанию — [DEFAULT_PUBLIC_CLIENT_ID],
     * но пользователь может заменить его, если он устареет. Пустое значение = вернуть по умолчанию.
     */
    val publicClientId: Flow<String> =
        context.novaDataStore.data.map {
            it[Keys.publicClientId]?.takeIf { v -> v.isNotBlank() } ?: DEFAULT_PUBLIC_CLIENT_ID
        }

    suspend fun setPublicClientId(value: String) {
        context.novaDataStore.edit { it[Keys.publicClientId] = value.trim() }
    }

    val notificationsEnabled: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.notificationsEnabled] ?: false }

    /** Подстраивать рекомендации под язык системы. По умолчанию включено. */
    val languageRecommendations: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.languageRecommendations] ?: true }

    suspend fun setLanguageRecommendations(enabled: Boolean) {
        context.novaDataStore.edit { it[Keys.languageRecommendations] = enabled }
    }

    /** Скрытые категории: id → название. В хранилище строки «id|название». */
    val hiddenCategories: Flow<Map<String, String>> =
        context.novaDataStore.data.map { prefs ->
            (prefs[Keys.hiddenCategories] ?: emptySet()).mapNotNull { raw ->
                val i = raw.indexOf('|')
                if (i <= 0) null else raw.substring(0, i) to raw.substring(i + 1)
            }.toMap()
        }

    suspend fun hideCategory(id: String, name: String) {
        context.novaDataStore.edit { prefs ->
            val current = (prefs[Keys.hiddenCategories] ?: emptySet()).filterNot { it.startsWith("$id|") }.toSet()
            prefs[Keys.hiddenCategories] = current + "$id|${name.ifBlank { id }}"
        }
    }

    suspend fun unhideCategory(id: String) {
        context.novaDataStore.edit { prefs ->
            prefs[Keys.hiddenCategories] = (prefs[Keys.hiddenCategories] ?: emptySet()).filterNot { it.startsWith("$id|") }.toSet()
        }
    }

    /** Рисовать видео через SurfaceView (легче для слабых приставок). По умолчанию выключено — TextureView. */
    val useSurfaceView: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.useSurfaceView] ?: false }

    suspend fun setUseSurfaceView(enabled: Boolean) {
        context.novaDataStore.edit { it[Keys.useSurfaceView] = enabled }
    }

    /**
     * true = ZOOM (на весь экран, края могут обрезаться) — удобно на телефоне 16:10 в ландшафте.
     * false = FIT (весь кадр стримера, возможны поля) — по умолчанию, как на ТВ 16:9.
     */
    val videoFillScreen: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.videoFillScreen] ?: false }

    suspend fun setVideoFillScreen(enabled: Boolean) {
        context.novaDataStore.edit {
            it[Keys.videoFillScreen] = enabled
            it[Keys.videoResizeMode] = if (enabled) 1 else 0
        }
    }

    /**
     * Навигация в окне «Опросы и баллы» (веб-чат Twitch).
     * true (по умолчанию) — полупрозрачный кружок, как в TV-браузерах.
     * false — без кружка, управление стрелками/фокусом страницы.
     */
    val webChatCursor: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.webChatCursor] ?: true }

    suspend fun setWebChatCursor(enabled: Boolean) {
        context.novaDataStore.edit { it[Keys.webChatCursor] = enabled }
    }

    /** Сторона панели чата на TV (нативный и веб): true — справа, false — слева. */
    val chatPanelRight: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.chatPanelRight] ?: true }

    suspend fun setChatPanelRight(right: Boolean) {
        context.novaDataStore.edit { it[Keys.chatPanelRight] = right }
    }

    /** Бэкенд чата: "irc" (по умолчанию) или "web". */
    val chatBackend: Flow<String> =
        context.novaDataStore.data.map {
            val v = it[Keys.chatBackend]?.lowercase()
            if (v == "web") "web" else "irc"
        }

    suspend fun setChatBackend(backend: String) {
        val v = if (backend.equals("web", ignoreCase = true)) "web" else "irc"
        context.novaDataStore.edit { it[Keys.chatBackend] = v }
    }

    /**
     * Масштаб видео: 0 — весь кадр (возможны чёрные поля), 1 — заполнить с обрезкой краёв,
     * 2 — растянуть на весь экран без полей и без обрезки (картинка слегка искажается).
     * Если новая настройка ещё не сохранялась — берём старую «заполнить экран».
     */
    val videoResizeMode: Flow<Int> = context.novaDataStore.data.map {
        it[Keys.videoResizeMode] ?: if (it[Keys.videoFillScreen] == true) 1 else 0
    }

    // ---- Часы на Android TV (в углу экрана, поверх всего приложения, в том числе поверх плеера) ----
    val tvClockEnabled: Flow<Boolean> = context.novaDataStore.data.map { it[Keys.tvClockEnabled] ?: true }
    /** true — справа (по умолчанию), false — слева. */
    val tvClockRight: Flow<Boolean> = context.novaDataStore.data.map { it[Keys.tvClockRight] ?: true }
    /** 0 — маленькие, 1 — средние (по умолчанию), 2 — большие, 3 — очень большие. */
    val tvClockSize: Flow<Int> = context.novaDataStore.data.map { (it[Keys.tvClockSize] ?: 1).coerceIn(0, 3) }

    suspend fun setTvClockEnabled(v: Boolean) { context.novaDataStore.edit { it[Keys.tvClockEnabled] = v } }
    suspend fun setTvClockRight(v: Boolean) { context.novaDataStore.edit { it[Keys.tvClockRight] = v } }
    suspend fun setTvClockSize(v: Int) { context.novaDataStore.edit { it[Keys.tvClockSize] = v.coerceIn(0, 3) } }

    suspend fun setVideoResizeMode(mode: Int) {
        context.novaDataStore.edit {
            it[Keys.videoResizeMode] = mode.coerceIn(0, 2)
            it[Keys.videoFillScreen] = mode == 1
        }
    }

    /** Официальный embed Twitch или свой ExoPlayer (неофициальный HLS). По умолчанию — официальный. */
    val streamSource: Flow<StreamSourceMode> =
        context.novaDataStore.data.map { StreamSourceMode.fromKey(it[Keys.streamSource]) }

    suspend fun setStreamSource(mode: StreamSourceMode) {
        context.novaDataStore.edit { it[Keys.streamSource] = mode.name }
    }

    /** Качество своего плеера при старте эфира (Авто / потолок по высоте / минимум). */
    val defaultPlaybackQuality: Flow<DefaultPlaybackQuality> =
        context.novaDataStore.data.map { DefaultPlaybackQuality.fromKey(it[Keys.defaultPlaybackQuality]) }

    suspend fun setDefaultPlaybackQuality(quality: DefaultPlaybackQuality) {
        context.novaDataStore.edit { it[Keys.defaultPlaybackQuality] = quality.key }
    }

    /** Журнал плеера (Logcat + оверлей + сохранение в Загрузки). Без пересборки. */
    val playerDiagnostics: Flow<Boolean> =
        context.novaDataStore.data.map { it[Keys.playerDiagnostics] ?: false }

    suspend fun setPlayerDiagnostics(enabled: Boolean) {
        context.novaDataStore.edit { it[Keys.playerDiagnostics] = enabled }
    }





    suspend fun setClientId(value: String) {
        context.novaDataStore.edit { it[Keys.clientId] = value.trim() }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.novaDataStore.edit {
            it[Keys.notificationsEnabled] = enabled
            // При выключении забываем, о чём уже уведомляли: после включения первая проверка снова «тихая».
            if (!enabled) it.remove(Keys.notifiedStreamIds)
        }
    }

    /**
     * Идентификаторы эфиров, о которых уже уведомляли, чтобы не повторять уведомление.
     * null — проверка ещё ни разу не выполнялась (первый запуск делаем «тихим», без уведомлений).
     */
    suspend fun notifiedStreamIds(): Set<String>? =
        context.novaDataStore.data.first()[Keys.notifiedStreamIds]

    suspend fun setNotifiedStreamIds(ids: Set<String>) {
        context.novaDataStore.edit { it[Keys.notifiedStreamIds] = ids }
    }

    val accentHex: Flow<String> =
        context.novaDataStore.data.map { it[Keys.accent] ?: DEFAULT_ACCENT_HEX }

    val favoriteIds: Flow<Set<String>> =
        context.novaDataStore.data.map { it[Keys.favorites] ?: emptySet() }

    val searchHistory: Flow<List<String>> =
        context.novaDataStore.data.map { prefs ->
            prefs[Keys.searchHistory]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        }

    suspend fun setAccentHex(hex: String) {
        context.novaDataStore.edit { it[Keys.accent] = hex }
    }

    suspend fun toggleFavorite(channelId: String) {
        context.novaDataStore.edit { prefs ->
            val current = prefs[Keys.favorites] ?: emptySet()
            prefs[Keys.favorites] =
                if (channelId in current) current - channelId else current + channelId
        }
    }

    suspend fun addSearchQuery(query: String) {
        val clean = query.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        context.novaDataStore.edit { prefs ->
            val current = prefs[Keys.searchHistory]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(8)
            prefs[Keys.searchHistory] = updated.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        context.novaDataStore.edit { it.remove(Keys.searchHistory) }
    }

    companion object {
        const val DEFAULT_ACCENT_HEX = "#9147FF"

        /** Client ID сайта twitch.tv: неофициальный, используется только для чтения без входа. */
        const val DEFAULT_PUBLIC_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        /**
         * Встроенный Client ID приложения NovaStreams (Public-клиент на dev.twitch.tv; Client ID не секретный).
         * Используется для входа и Helix, если пользователь не ввёл свой. Свой Client ID из настроек имеет приоритет.
         */
        const val DEFAULT_LOGIN_CLIENT_ID = "eokh85tv87fwwznwgg0z047tif37id"
    }
}
