package com.novastreams.app.core.stream

import com.novastreams.app.core.network.Http
import com.novastreams.app.core.settings.SettingsRepository
import com.novastreams.app.core.twitchapi.ApiException
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * НЕОФИЦИАЛЬНЫЙ способ получить HLS-ссылку прямого эфира:
 *  1) GraphQL (gql.twitch.tv) — токен value + signature;
 *  2) usher.ttvnw.net — плейлист .m3u8.
 * Рекламу модуль не блокирует и не обходит.
 */
class HlsStreamRepository(private val settings: SettingsRepository) {

    private data class CachedUrl(val url: String, val atMs: Long)

    /** Короткий кэш HLS-ссылок: токен usher живёт недолго, 90 с достаточно для «открыл карточку → плеер». */
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    private val cacheTtlMs = 90_000L

    /** Сбросить кэш ссылки канала: после обрыва эфира старый токен/плейлист уже не годятся. */
    fun invalidate(login: String) {
        val safeLogin = login.lowercase().filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' }
        urlCache.remove(safeLogin)
    }

    /**
     * Заранее получить токен и плейлист (без открытия плеера).
     * Вызывать при фокусе карточки эфира — к нажатию OK ссылка уже в кэше.
     */
    suspend fun prefetch(login: String) {
        try {
            val url = liveHlsUrl(login)
            // Прогрев CDN: скачать master-плейлист, чтобы первый prepare в плеере не ждал сеть с нуля.
            warmPlaylist(url)
        } catch (_: Exception) {
            // Предзагрузка best-effort — ошибку увидит плеер при реальном открытии.
        }
    }

    suspend fun liveHlsUrl(login: String): String {
        val safeLogin = login.lowercase().filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' }
        if (safeLogin.isEmpty()) throw ApiException("Некорректное имя канала")

        val now = System.currentTimeMillis()
        urlCache[safeLogin]?.let { cached ->
            if (now - cached.atMs < cacheTtlMs) return cached.url
        }

        val mutex = inFlight.getOrPut(safeLogin) { Mutex() }
        return mutex.withLock {
            val now2 = System.currentTimeMillis()
            urlCache[safeLogin]?.let { cached ->
                if (now2 - cached.atMs < cacheTtlMs) return@withLock cached.url
            }
            val fetched = fetchLiveHlsUrl(safeLogin)
            urlCache[safeLogin] = CachedUrl(fetched, System.currentTimeMillis())
            fetched
        }
    }

    private suspend fun warmPlaylist(url: String) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
                .get()
                .build()
            Http.execute(req)
        } catch (_: Exception) {
        }
    }

    private suspend fun fetchLiveHlsUrl(safeLogin: String): String {
        val (token, signature) = accessToken(safeLogin)

        // Параметры usher как у веб-клиента Twitch / streamlink.
        // fast_bread=true — более «живой» путь CDN (быстрее подхват сегментов).
        val url = "https://usher.ttvnw.net/api/channel/hls/$safeLogin.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("allow_spectre", "true")
            .addQueryParameter("player", "twitchweb")
            .addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("reassignments_supported", "true")
            .addQueryParameter("fast_bread", "true")
            // Только H.264: слабые ТВ не тянут AV1/H.265, меньше лишних вариантов в master.
            .addQueryParameter("supported_codecs", "h264")
            .addQueryParameter("play_session_id", java.util.UUID.randomUUID().toString().replace("-", ""))
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("p", Random.nextInt(100_000, 999_999).toString())
            .addQueryParameter("sig", signature)
            .addQueryParameter("token", token)
            .addQueryParameter("type", "any")
            .build()
        return url.toString()
    }

    /**
     * GraphQL-переменные пишутся как ${'$'}name, чтобы Kotlin не считал их своими
     * интерполяциями (в обычной строке то же даёт "\$name").
     */
    /** Стабильный id на процесс — меньше «холодных» ответов GQL. */
    private val processSessionId: String =
        java.util.UUID.randomUUID().toString().replace("-", "")
    private val processDeviceId: String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    private fun sessionId(): String = processSessionId
    private fun deviceId(): String = processDeviceId

    private suspend fun accessToken(login: String): Pair<String, String> {
        val clientId = settings.publicClientId.first()

        val query = """
            query PlaybackAccessToken(${'$'}login: String!, ${'$'}playerType: String!) {
              streamPlaybackAccessToken(
                channelName: ${'$'}login,
                params: {
                  platform: "web",
                  playerBackend: "mediaplayer",
                  playerType: ${'$'}playerType
                }
              ) {
                value
                signature
              }
            }
        """.trimIndent()

        // playerType "site" + platform "web" — как у веб-плеера Twitch (проверенная связка).
        val variables = JSONObject()
            .put("login", login)
            .put("playerType", "site")

        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()

        val result = try {
            Http.execute(
                Request.Builder()
                    .url("https://gql.twitch.tv/gql")
                    .header("Client-Id", clientId)
                    .header("Client-Session-Id", sessionId())
                    .header("X-Device-Id", deviceId())
                    .header("Origin", "https://www.twitch.tv")
                    .header("Referer", "https://www.twitch.tv/")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        } catch (e: IOException) {
            throw ApiException("Нет связи с Twitch. Проверьте интернет")
        }
        if (!result.isSuccess) {
            throw ApiException(
                "Twitch не выдал доступ к потоку (код ${result.code}). Публичный Client ID мог устареть",
                result.code,
            )
        }
        val node = result.json().optJSONObject("data")?.optJSONObject("streamPlaybackAccessToken")
        val value = node?.optString("value").orEmpty()
        val signature = node?.optString("signature").orEmpty()
        if (value.isBlank() || signature.isBlank()) {
            // Иногда GQL отдаёт 200 с errors[] вместо data — покажем это явно.
            val gqlError = result.json().optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("message")
                .orEmpty()
            throw ApiException(
                when {
                    gqlError.isNotBlank() -> "Twitch: $gqlError"
                    else -> "Twitch не выдал токен плеера (канал не в эфире или способ перестал работать)"
                }
            )
        }
        return value to signature
    }
}
