package com.novastreams.app.core.auth

import com.novastreams.app.BuildConfig
import com.novastreams.app.core.network.Http
import com.novastreams.app.core.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.IOException

/**
 * Авторизация Twitch для публичного клиента (Client Secret не используется) одним способом:
 * Device Code Grant (код показывается в приложении, подтверждается на странице Twitch).
 * Адрес перенаправления не нужен. Токены хранятся в [TokenStore] (зашифрованно).
 */
class TwitchAuthRepository(
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
) : AuthRepository {

    // Refresh-токен одноразовый, поэтому обновление идёт под замком: два запроса сразу сломали бы сессию.
    private val refreshLock = Mutex()

    private val _state = MutableStateFlow<AuthState>(
        tokens.load()?.toState() ?: AuthState.NotSignedIn
    )
    override val state: StateFlow<AuthState> = _state

    override val isPersistent: Boolean get() = tokens.isPersistent

    /**
     * Какой Client ID использовать. Приоритет: свой, введённый пользователем в настройках →
     * заданный при сборке (local.properties) → встроенный по умолчанию.
     */
    override suspend fun clientId(): String {
        val own = settings.clientId.first().trim()
        if (own.isNotEmpty()) return own
        val fromBuild = BuildConfig.TWITCH_CLIENT_ID.trim()
        if (fromBuild.isNotEmpty()) return fromBuild
        return SettingsRepository.DEFAULT_LOGIN_CLIENT_ID
    }

    // ---------- Вход по коду (Device Code Grant) ----------

    override suspend fun startDeviceFlow(): DeviceCode {
        val id = clientId()
        if (id.isEmpty()) throw AuthException("Сначала введите свой Client ID в настройках")
        val result = Http.postForm(
            "https://id.twitch.tv/oauth2/device",
            mapOf("client_id" to id, "scopes" to TwitchScopes.all),
        )
        val json = result.json()
        if (!result.isSuccess) {
            throw AuthException(json.optString("message", "Twitch отклонил запрос (код ${result.code})"))
        }
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresInSec = json.optInt("expires_in", 1800),
            intervalSec = json.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    // Сигнал «проверить сейчас»: кнопка на экране кода и возврат в приложение из браузера будят опрос досрочно.
    private val pollNudge = Channel<Unit>(Channel.CONFLATED)

    override fun pollNow() {
        pollNudge.trySend(Unit)
    }

    override suspend fun awaitAuthorization(code: DeviceCode, onStatus: (String) -> Unit) {
        val id = clientId()
        var interval = code.intervalSec
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        var attempt = 0
        pollNudge.tryReceive() // старый сигнал не нужен
        onStatus("Ждём подтверждения на странице Twitch…")
        while (System.currentTimeMillis() < deadline) {
            // Ждём интервал, но досрочная проверка (pollNow) прерывает ожидание.
            withTimeoutOrNull(interval * 1000L) { pollNudge.receive() }
            attempt++
            val result = try {
                Http.postForm(
                    "https://id.twitch.tv/oauth2/token",
                    mapOf(
                        "client_id" to id,
                        "scopes" to TwitchScopes.all,
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
            } catch (e: IOException) {
                // Нет сети (например, пока приложение в фоне): не сдаёмся, пробуем снова.
                onStatus("Проверка №$attempt: нет связи с Twitch, повторяю…")
                continue
            }
            val json = result.json()
            if (result.isSuccess) {
                onStatus("Подтверждено, завершаем вход…")
                finishLogin(json.getString("access_token"), json.optString("refresh_token", ""))
                return
            }
            when (val message = json.optString("message")) {
                "authorization_pending" -> onStatus("Проверка №$attempt: Twitch пока не получил подтверждение")
                "slow_down" -> {                         // Twitch просит опрашивать реже
                    interval += 5
                    onStatus("Проверка №$attempt: Twitch просит подождать")
                }
                else -> throw AuthException(
                    if (message.isEmpty()) "Вход не подтверждён (код ${result.code})"
                    else "Twitch ответил: $message (код ${result.code})"
                )
            }
        }
        throw AuthException("Время на подтверждение входа истекло")
    }

    // ---------- Общее ----------

    override suspend fun validAccessToken(): String? {
        val session = tokens.load() ?: return null
        if (session.expiresAtMs - System.currentTimeMillis() > 60_000L) return session.accessToken
        return refreshLock.withLock {
            // Пока мы ждали замок, другой вызов мог уже обновить токен.
            val current = tokens.load() ?: return@withLock null
            if (current.expiresAtMs - System.currentTimeMillis() > 60_000L) return@withLock current.accessToken
            refresh(current)
        }
    }

    /** Повторно запрашивает актуальные данные текущего пользователя у Helix. Токен не меняет. */
    override suspend fun refreshProfile() {
        if (tokens.load() == null) return
        val accessToken = try { validAccessToken() } catch (_: Exception) { null } ?: return
        // Re-read after validAccessToken(): it may have rotated the refresh token.
        val current = tokens.load() ?: return
        val id = clientId()
        try {
            val result = Http.execute(
                Request.Builder()
                    .url("https://api.twitch.tv/helix/users")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Id", id)
                    .build()
            )
            if (!result.isSuccess) return
            val user = result.json().optJSONArray("data")?.optJSONObject(0) ?: return
            val updated = current.copy(
                displayName = user.optString("display_name").takeIf { it.isNotBlank() } ?: current.displayName,
                profileImageUrl = user.optString("profile_image_url").takeIf { it.isNotBlank() } ?: current.profileImageUrl,
            )
            tokens.save(updated)
            _state.value = updated.toState()
        } catch (_: Exception) {
            // При временной ошибке сети сохраняем ранее известный профиль.
        }
    }

    override suspend fun signOut() {
        val session = tokens.load()
        tokens.clear()
        _state.value = AuthState.NotSignedIn
        if (session != null) {
            // Просим Twitch отозвать токен; если сети нет — не страшно, локально вход уже сброшен.
            try {
                Http.postForm(
                    "https://id.twitch.tv/oauth2/revoke",
                    mapOf("client_id" to clientId(), "token" to session.accessToken),
                )
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun refresh(session: StoredSession): String? {
        // Если refresh-токена нет, после истечения токена нужно войти заново.
        if (session.refreshToken.isBlank()) {
            tokens.clear()
            _state.value = AuthState.NotSignedIn
            return null
        }
        val result = Http.postForm(
            "https://id.twitch.tv/oauth2/token",
            mapOf(
                "client_id" to clientId(),
                "grant_type" to "refresh_token",
                "refresh_token" to session.refreshToken,
            ),
        )
        val json = result.json()
        if (!result.isSuccess) {
            // 400/401 — refresh-токен недействителен (истёк или уже использован): нужно войти заново.
            if (result.code == 400 || result.code == 401) {
                tokens.clear()
                _state.value = AuthState.NotSignedIn
                return null
            }
            throw IOException("Не удалось обновить токен (код ${result.code})")
        }
        val updated = session.copy(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token", session.refreshToken),
            expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 14_000L) * 1000L,
        )
        tokens.save(updated)
        return updated.accessToken
    }

    /** Проверяет токен у Twitch, узнаёт пользователя, сохраняет сессию и переводит состояние в «вошёл». */
    private suspend fun finishLogin(accessToken: String, refreshToken: String) {
        val clientId = clientId()

        // /validate подтверждает, что токен рабочий и выдан именно нашему приложению.
        val validate = try {
            Http.execute(
                Request.Builder()
                    .url("https://id.twitch.tv/oauth2/validate")
                    .header("Authorization", "OAuth $accessToken")
                    .build()
            )
        } catch (e: IOException) {
            throw AuthException("Нет связи с Twitch. Проверьте интернет")
        }
        if (!validate.isSuccess) throw AuthException("Ошибка авторизации")
        val v = validate.json()
        if (v.optString("client_id") != clientId) throw AuthException("Ошибка авторизации")

        val userId = v.optString("user_id")
        val login = v.optString("login")
        val expiresAt = System.currentTimeMillis() + v.optLong("expires_in", 14_000L) * 1000L

        // Имя и аватарка берём из Helix; если запрос не удался, остаётся login без картинки.
        var display = login
        var avatar = ""
        try {
            val users = Http.execute(
                Request.Builder()
                    .url("https://api.twitch.tv/helix/users")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Id", clientId)
                    .build()
            )
            if (users.isSuccess) {
                val first = users.json().optJSONArray("data")?.optJSONObject(0)
                display = first?.optString("display_name").takeUnless { it.isNullOrBlank() } ?: login
                avatar = first?.optString("profile_image_url").orEmpty()
            }
        } catch (e: Exception) {
        }

        val session = StoredSession(accessToken, refreshToken, expiresAt, userId, login, display, avatar)
        tokens.save(session)
        _state.value = session.toState()
    }
}

private fun StoredSession.toState(): AuthState = AuthState.SignedIn(
    UserInfo(
        id = userId,
        login = login,
        displayName = displayName.ifBlank { login },
        profileImageUrl = profileImageUrl,
    )
)
