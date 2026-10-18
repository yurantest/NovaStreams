package com.novastreams.app.core.video

import com.novastreams.app.core.model.seedOf
import com.novastreams.app.core.network.Http
import com.novastreams.app.core.settings.SettingsRepository
import com.novastreams.app.core.twitchapi.ApiException
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Записи эфиров и клипы канала. НЕОФИЦИАЛЬНЫЙ путь: тот же gql.twitch.tv, что и у сайта
 * (публичный Client ID из настроек), работает и без входа. Запросы написаны по памяти и в среде
 * разработки НЕ проверялись — при сбое экран покажет понятную ошибку.
 */
class VideoRepository(private val settings: SettingsRepository) {

    private suspend fun gql(text: String, variables: JSONObject): JSONObject {
        val clientId = settings.publicClientId.first()
        val body = JSONObject().put("query", text).put("variables", variables).toString()
        val result = try {
            Http.execute(
                Request.Builder()
                    .url("https://gql.twitch.tv/gql")
                    .header("Client-Id", clientId)
                    .header("Origin", "https://www.twitch.tv")
                    .header("Referer", "https://www.twitch.tv/")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        } catch (e: IOException) {
            throw ApiException("Нет связи с Twitch. Проверьте интернет")
        }
        if (!result.isSuccess) {
            throw ApiException("Twitch не принял запрос (код ${result.code}). Публичный Client ID мог устареть", result.code)
        }
        val json = result.json()
        val data = json.optJSONObject("data")
        if (data == null) {
            val msg = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
            throw ApiException("Ответ Twitch не разобран: ${msg.ifBlank { "неизвестная ошибка" }}")
        }
        return data
    }

    private fun safeLogin(login: String): String =
        login.lowercase().filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' }
            .ifEmpty { throw ApiException("Некорректное имя канала") }

    private fun safeCursor(after: String?): String? =
        after?.filter { it.isLetterOrDigit() || it in "=+/_-" }?.takeIf { it.isNotEmpty() }

    /** «2026-10-12T18:30:00Z» → «12.10.2026». */
    private fun dateOf(iso: String?): String {
        if (iso.isNullOrBlank() || iso.length < 10) return ""
        val p = iso.substring(0, 10).split("-")
        return if (p.size == 3) "${p[2]}.${p[1]}.${p[0]}" else ""
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ——— Списки ———

    /** Записи эфиров канала (новые первыми): прошедшие трансляции, хайлайты и загрузки. */
    suspend fun vods(login: String, after: String? = null): VideosPage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val cursor = safeCursor(after)
        val data = gql(
            "query(\$login: String!, \$after: Cursor) { user(login: \$login) { videos(first: 30, after: \$after, sort: TIME) " +
                "{ edges { cursor node { id title lengthSeconds viewCount createdAt previewThumbnailURL(width: 440, height: 248) game { name } } } } } }",
            JSONObject().put("login", safeLogin(login)).put("after", cursor ?: JSONObject.NULL),
        )
        val edges = data.optJSONObject("user")?.optJSONObject("videos")?.optJSONArray("edges").objects()
        val items = edges.mapNotNull { e ->
            val n = e.optJSONObject("node") ?: return@mapNotNull null
            val id = n.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChannelVideo(
                id = id,
                kind = VideoKind.VOD,
                title = n.optString("title").ifBlank { "Без названия" },
                durationSec = n.optInt("lengthSeconds"),
                views = n.optInt("viewCount"),
                dateText = dateOf(n.optString("createdAt")),
                thumbnailUrl = n.optString("previewThumbnailURL").takeIf { it.isNotBlank() },
                categoryName = n.optJSONObject("game")?.optString("name").orEmpty(),
                artSeed = seedOf(id),
            )
        }
        VideosPage(items, edges.lastOrNull()?.optString("cursor")?.takeIf { it.isNotBlank() && items.isNotEmpty() })
    }

    /** Клипы канала, самые просматриваемые первыми, за выбранный период. */
    suspend fun clips(login: String, period: ClipPeriod, after: String? = null): VideosPage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val cursor = safeCursor(after)
        // period и sort — перечисления схемы Twitch: значения пишутся без кавычек (период берётся из нашего enum).
        val data = gql(
            "query(\$login: String!, \$after: Cursor) { user(login: \$login) { clips(first: 30, after: \$after, " +
                "criteria: { period: ${period.gql}, sort: VIEWS_DESC }) " +
                "{ edges { cursor node { id slug title viewCount durationSeconds createdAt thumbnailURL curator { displayName } game { name } } } } } }",
            JSONObject().put("login", safeLogin(login)).put("after", cursor ?: JSONObject.NULL),
        )
        val edges = data.optJSONObject("user")?.optJSONObject("clips")?.optJSONArray("edges").objects()
        val items = edges.mapNotNull { e ->
            val n = e.optJSONObject("node") ?: return@mapNotNull null
            val slug = n.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChannelVideo(
                id = slug,
                kind = VideoKind.CLIP,
                title = n.optString("title").ifBlank { "Без названия" },
                durationSec = n.optDouble("durationSeconds", 0.0).toInt(),
                views = n.optInt("viewCount"),
                dateText = dateOf(n.optString("createdAt")),
                thumbnailUrl = n.optString("thumbnailURL").takeIf { it.isNotBlank() },
                categoryName = n.optJSONObject("game")?.optString("name").orEmpty(),
                curator = n.optJSONObject("curator")?.optString("displayName").orEmpty(),
                artSeed = seedOf(slug),
            )
        }
        VideosPage(items, edges.lastOrNull()?.optString("cursor")?.takeIf { it.isNotBlank() && items.isNotEmpty() })
    }

    // ——— Воспроизведение ———

    suspend fun resolve(kind: VideoKind, id: String): ResolvedVideo = when (kind) {
        VideoKind.VOD -> ResolvedVideo.Hls(vodHlsUrl(id))
        VideoKind.CLIP -> ResolvedVideo.Files(clipFiles(id))
    }

    /** Плейлист записи: токен GraphQL + usher.ttvnw.net/vod. */
    private suspend fun vodHlsUrl(videoId: String): String {
        val id = videoId.filter { it.isDigit() }.ifEmpty { throw ApiException("Некорректный номер записи") }
        val data = gql(
            "query(\$id: ID!) { videoPlaybackAccessToken(id: \$id, params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { value signature } }",
            JSONObject().put("id", id),
        )
        val node = data.optJSONObject("videoPlaybackAccessToken")
        val token = node?.optString("value").orEmpty()
        val sig = node?.optString("signature").orEmpty()
        if (token.isBlank() || sig.isBlank()) {
            throw ApiException("Twitch не выдал доступ к записи. Возможно, она удалена или доступна только подписчикам")
        }
        return "https://usher.ttvnw.net/vod/$id.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("player", "twitchweb")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("supported_codecs", "h264")
            .addQueryParameter("p", Random.nextInt(100_000, 999_999).toString())
            .addQueryParameter("sig", sig)
            .addQueryParameter("token", token)
            .addQueryParameter("type", "any")
            .build()
            .toString()
    }

    /** Файлы клипа: токен + список mp4 разного качества. */
    private suspend fun clipFiles(slug: String): List<ResolvedVideo.Variant> {
        val safe = slug.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { throw ApiException("Некорректный клип") }
        val data = gql(
            "query(\$slug: ID!) { clip(slug: \$slug) { playbackAccessToken(params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { value signature } " +
                "videoQualities { sourceURL quality frameRate } } }",
            JSONObject().put("slug", safe),
        )
        val clip = data.optJSONObject("clip") ?: throw ApiException("Клип не найден (возможно, удалён)")
        val tokenNode = clip.optJSONObject("playbackAccessToken")
        val token = tokenNode?.optString("value").orEmpty()
        val sig = tokenNode?.optString("signature").orEmpty()
        val qualities = clip.optJSONArray("videoQualities").objects()
        if (qualities.isEmpty()) throw ApiException("Twitch не отдал файлы клипа")
        val variants = qualities.mapNotNull { q ->
            val src = q.optString("sourceURL").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val height = q.optString("quality").toIntOrNull() ?: 0
            val fps = q.optDouble("frameRate", 30.0)
            val url = if (token.isNotBlank() && sig.isNotBlank()) {
                src + (if (src.contains('?')) "&" else "?") + "sig=" + sig + "&token=" + URLEncoder.encode(token, "UTF-8")
            } else src
            height to ResolvedVideo.Variant(
                label = if (height > 0) "${height}p" + (if (fps >= 50) "60" else "") else "Исходное",
                url = url,
            )
        }
        if (variants.isEmpty()) throw ApiException("Twitch не отдал файлы клипа")
        return variants.sortedByDescending { it.first }.map { it.second }
    }
}
