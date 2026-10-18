package com.novastreams.app.core.auth

import kotlinx.coroutines.flow.StateFlow

/** Состояние авторизации Twitch. */
sealed interface AuthState {
    data object NotSignedIn : AuthState
    data class SignedIn(val user: UserInfo) : AuthState
}

/** Код устройства: пользователь вводит [userCode] на странице [verificationUri]. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

class AuthException(message: String) : Exception(message)

/** Права, запрашиваемые у пользователя. Каждое — по документации Twitch. */
object TwitchScopes {
    /** Получение списка подписок и «кто из подписок в эфире». */
    const val READ_FOLLOWS = "user:read:follows"
    /** Чтение чата. */
    const val CHAT_READ = "chat:read"
    /** Отправка сообщений в чат. */
    const val CHAT_EDIT = "chat:edit"
    /** Создание клипа («Нарезка» в плеере). */
    const val CLIPS_EDIT = "clips:edit"

    val all: String = listOf(READ_FOLLOWS, CHAT_READ, CHAT_EDIT, CLIPS_EDIT).joinToString(" ")
}

interface AuthRepository {
    val state: StateFlow<AuthState>

    /** Client ID приложения Twitch (из сборки или введённый в приложении). Пусто — вход невозможен. */
    suspend fun clientId(): String

    /** true, если токены сохраняются между запусками. */
    val isPersistent: Boolean

    suspend fun startDeviceFlow(): DeviceCode

    /**
     * Ждёт, пока пользователь подтвердит вход; бросает [AuthException] при отказе или истечении кода.
     * [onStatus] получает короткие сообщения о ходе проверки (показываются на экране кода).
     */
    suspend fun awaitAuthorization(code: DeviceCode, onStatus: (String) -> Unit = {})

    /** Проверить подтверждение прямо сейчас, не дожидаясь очередного интервала опроса. */
    fun pollNow() {}

    /**
     * Действующий токен доступа; при необходимости обновляет его.
     * null — пользователь не вошёл или сессия закончилась (в этом случае состояние сбрасывается).
     * Сетевые ошибки пробрасываются как IOException.
     */
    suspend fun validAccessToken(): String?

    /** Обновляет профиль Twitch (имя и аватар) без повторного входа. */
    suspend fun refreshProfile() {}

    suspend fun signOut()
}
