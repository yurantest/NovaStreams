package com.novastreams.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Токены и данные пользователя. Время истечения — в миллисекундах Unix. */
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val userId: String,
    val login: String,
    val displayName: String,
    val profileImageUrl: String = "",
)

/**
 * Хранилище токенов в EncryptedSharedPreferences (ключ в Android Keystore).
 * Если Keystore на устройстве сломан (бывает на дешёвых ТВ-приставках), токены остаются
 * только в памяти: вход придётся повторять после перезапуска, зато токены не лежат открытым текстом.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences? = try {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "nova_secure_session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    private var memory: StoredSession? = null

    /** true, если токены переживут перезапуск приложения. */
    val isPersistent: Boolean get() = prefs != null

    fun load(): StoredSession? {
        memory?.let { return it }
        val p = prefs ?: return null
        val access = p.getString("access", null) ?: return null
        val refresh = p.getString("refresh", null) ?: return null
        return StoredSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = p.getLong("expires_at", 0L),
            userId = p.getString("user_id", "").orEmpty(),
            login = p.getString("login", "").orEmpty(),
            displayName = p.getString("display_name", "").orEmpty(),
            profileImageUrl = p.getString("avatar", "").orEmpty(),
        ).also { memory = it }
    }

    fun save(session: StoredSession) {
        memory = session
        try {
            prefs?.edit()
                ?.putString("access", session.accessToken)
                ?.putString("refresh", session.refreshToken)
                ?.putLong("expires_at", session.expiresAtMs)
                ?.putString("user_id", session.userId)
                ?.putString("login", session.login)
                ?.putString("display_name", session.displayName)
                ?.putString("avatar", session.profileImageUrl)
                ?.apply()
        } catch (e: Exception) {
            // Запись не удалась — сессия остаётся в памяти до перезапуска.
        }
    }

    fun clear() {
        memory = null
        try {
            prefs?.edit()?.clear()?.apply()
        } catch (e: Exception) {
        }
    }
}
