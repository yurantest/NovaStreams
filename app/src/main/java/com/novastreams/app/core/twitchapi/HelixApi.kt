package com.novastreams.app.core.twitchapi

import com.novastreams.app.core.auth.AuthRepository
import com.novastreams.app.core.network.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Тонкий клиент Helix: подставляет Client-Id и токен пользователя, превращает ошибки в понятные сообщения.
 * Базовые правила — по документации Twitch: заголовки Authorization: Bearer и Client-Id.
 */
class HelixApi(private val auth: AuthRepository) {

    /** GET с параметрами; один параметр может повторяться (например, id=1&id=2). */
    suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JSONObject {
        val token = auth.validAccessToken()
            ?: throw ApiException("Нужно войти через Twitch", 401)
        val clientId = auth.clientId()

        val url = "https://api.twitch.tv/helix/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

        val result = Http.execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Client-Id", clientId)
                .build()
        )
        if (result.isSuccess) return result.json()

        val serverMessage = result.json().optString("message")
        when (result.code) {
            401 -> {
                // Токен отозван или недействителен — сбрасываем вход, пользователь войдёт заново.
                auth.signOut()
                throw ApiException("Сессия Twitch закончилась, войдите снова", 401)
            }
            429 -> throw ApiException("Слишком много запросов к Twitch, попробуйте через минуту", 429)
            in 500..599 -> throw ApiException("Twitch временно недоступен (код ${result.code})", result.code)
            else -> throw ApiException(
                serverMessage.ifBlank { "Ошибка Twitch (код ${result.code})" },
                result.code,
            )
        }
    }

    /**
     * Создаёт клип последних секунд эфира (POST /helix/clips, нужно право clips:edit).
     * Возвращает адрес, где клип можно доработать. Клип создаётся на стороне Twitch, не в приложении.
     */
    suspend fun createClip(broadcasterId: String): String {
        val token = auth.validAccessToken() ?: throw ApiException("Нужно войти через Twitch", 401)
        val url = "https://api.twitch.tv/helix/clips".toHttpUrl().newBuilder()
            .addQueryParameter("broadcaster_id", broadcasterId)
            .build()
        val result = Http.execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Client-Id", auth.clientId())
                .post(ByteArray(0).toRequestBody())
                .build()
        )
        if (result.isSuccess) {
            val item = result.json().optJSONArray("data")?.optJSONObject(0)
            return item?.optString("edit_url").orEmpty().ifBlank { "https://clips.twitch.tv/" + item?.optString("id").orEmpty() }
        }
        throw ApiException(
            when (result.code) {
                401 -> "Для клипов нужно войти заново: появилось новое право доступа"
                404 -> "Клип создать нельзя: канал не в эфире"
                else -> result.json().optString("message").ifBlank { "Не удалось создать клип (код ${result.code})" }
            },
            result.code,
        )
    }

    /** Преобразует сетевые сбои в ApiException для единообразного показа в интерфейсе. */
    suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: IOException) {
        throw ApiException("Нет связи с Twitch. Проверьте интернет")
    }
}
