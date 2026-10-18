package com.novastreams.app.feature.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.ByteArrayDataSource
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Filters Twitch's *leading* stitched-ad segments from media playlists before ExoPlayer parses them.
 * It deliberately does not rewrite mid-rolls: removing arbitrary entries from a live playlist can
 * desynchronise HLS media sequence numbers. Non-playlist requests are passed through unchanged.
 *
 * Twitch currently marks these segments with EXTINF titles such as "Amazon|24742". The original
 * URL/token request is untouched; this operates only on playlist response bytes.
 */
@UnstableApi
class TwitchAdFilteringDataSource private constructor(
    private val upstream: DataSource,
) : DataSource {
    private var active: DataSource = upstream
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        active = upstream
        val length = upstream.open(dataSpec)
        opened = true
        if (!isPlaylist(dataSpec.uri, upstream.responseHeaders)) return length

        val bytes = try {
            readAll(upstream)
        } finally {
            upstream.close()
        }
        val original = bytes.toString(StandardCharsets.UTF_8)
        val filtered = removeLeadingAdSegments(original)
        // Важно: upstream уже прочитан и закрыт. Раньше при «без рекламы» плейлист
        // качался второй раз — лишняя задержка на каждый reload live-плейлиста.
        active = ByteArrayDataSource(filtered.toByteArray(StandardCharsets.UTF_8))
        return active.open(DataSpec(dataSpec.uri))
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        if (!opened) return
        opened = false
        active.close()
    }

    private fun isPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull().orEmpty().lowercase()
        return contentType.contains("mpegurl") || contentType.contains("vnd.apple.mpegurl") ||
            uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) ||
            uri.host.orEmpty().contains("playlist.ttvnw.net", ignoreCase = true)
    }

    private fun readAll(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun removeLeadingAdSegments(playlist: String): String {
        if (!playlist.startsWith("#EXTM3U")) return playlist
        val lines = playlist.split('\n')
        val out = ArrayList<String>(lines.size)
        var seenContent = false
        var skipAdUri = false
        var removed = 0
        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r')
            if (skipAdUri) {
                if (line.isNotBlank() && !line.startsWith('#')) {
                    skipAdUri = false
                    continue
                }
                // Preserve intervening metadata tags, if any.
                if (line.startsWith("#")) out += line
                continue
            }
            if (line.startsWith("#EXTINF:") && !seenContent) {
                val title = line.substringAfter(',', "")
                if (title.contains("Amazon", ignoreCase = true)) {
                    removed++
                    skipAdUri = true
                    continue
                }
                seenContent = true
            }
            if (line.isNotBlank() && !line.startsWith('#')) seenContent = true
            out += line
        }
        if (removed == 0) return playlist
        // Keep HLS sequence numbering aligned with the first retained segment.
        for (i in out.indices) {
            if (out[i].startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                val old = out[i].substringAfter(':').trim().toLongOrNull() ?: 0L
                out[i] = "#EXT-X-MEDIA-SEQUENCE:${old + removed}"
                break
            }
        }
        PlayerLog.add("HLS: пропущено рекламных сегментов перед эфиром: $removed")
        return out.joinToString("\n")
    }

    class Factory : DataSource.Factory {
        private val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Origin" to "https://www.twitch.tv",
                    "Referer" to "https://www.twitch.tv/",
                    "Accept" to "*/*",
                )
            )

        override fun createDataSource(): DataSource = TwitchAdFilteringDataSource(httpFactory.createDataSource())
    }
}
