package com.novastreams.app.feature.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * Диагностический журнал плеера: только НАБЛЮДАЕТ, ничего не меняет в потоке.
 * Строки видны в Logcat (тег NovaPlayer) и на экране плеера (кнопка Info/Menu/0 на пульте
 * или автоматически, если плеер буферизуется дольше 6 секунд).
 * Токены и подписи Twitch в журнал не пишутся: логируется только хост и короткое имя файла без query.
 */
/**
 * Устаревший compile-time флаг. Реальное включение — настройка «Отладка плеера» в приложении
 * ([PlayerDiagnosticsGate] и DataStore). Оставлен true, чтобы код журнала компилировался;
 * без галочки в настройках записи не пишутся.
 */
const val DIAGNOSTICS_ENABLED = true

/** Вкл/выкл журнала без пересборки (синхронизируется с настройкой). */
object PlayerDiagnosticsGate {
    @Volatile
    var enabled: Boolean = false

    fun active(): Boolean = enabled
}

/** Один просмотр: метка (канал), время начала и строки журнала. */
class LogSession(val label: String, val startedAtMs: Long) {
    val lines = ArrayList<String>()
    /** true — этот просмотр уже сохранён в файл (чтобы не дублировать при повторном нажатии). */
    var saved = false
}

object PlayerLog {
    private const val TAG = "NovaPlayer"
    private const val MAX_LINES = 400
    private const val MAX_SESSIONS = 30

    private val sessions = ArrayList<LogSession>()
    private var current: LogSession? = null

    /** Строки текущего (последнего) просмотра — их показывают панель плеера и «Настройки». */
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** Новый просмотр канала [label]: начинается отдельный журнал, прошлые остаются для сохранения. */
    @Synchronized
    fun reset(label: String = "stream") {
        val session = LogSession(label, System.currentTimeMillis())
        sessions += session
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        current = session
        _lines.value = emptyList()
        add("канал: $label; Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}); ${android.os.Build.MODEL}")
    }

    @Synchronized
    fun add(message: String) {
        if (!PlayerDiagnosticsGate.active()) return
        val session = current ?: return
        val t = (System.currentTimeMillis() - session.startedAtMs) / 1000.0
        val line = String.format(java.util.Locale.US, "%6.1fs %s", t, message)
        Log.d(TAG, line)
        if (session.lines.size < MAX_LINES) session.lines += line
        _lines.value = session.lines.takeLast(MAX_LINES).toList()
    }

    /** Просмотры, ещё не сохранённые в файл. */
    @Synchronized
    fun unsaved(): List<LogSession> = sessions.filter { !it.saved && it.lines.isNotEmpty() }.toList()
}

/** Слушатель событий ExoPlayer, пишущий в [PlayerLog]. Нужен opt-in: AnalyticsListener — нестабильный API media3. */
@UnstableApi
class DiagnosticsListener(private val player: ExoPlayer) : AnalyticsListener {
    // Чтобы не засорять журнал, каждую «особую» строку плейлиста пишем один раз.
    private val seenTags = HashSet<String>()
    private var loadingSince = 0L

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?$state"
    }

    private fun shortUri(info: LoadEventInfo): String {
        val u = info.uri
        val name = u.lastPathSegment.orEmpty().take(24)
        return "${u.host}/…$name"
    }

    private fun kind(data: MediaLoadData) = when (data.dataType) {
        C.DATA_TYPE_MANIFEST -> "плейлист"
        C.DATA_TYPE_MEDIA -> "сегмент"
        else -> "тип${data.dataType}"
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        PlayerLog.add("состояние: ${stateName(state)}")
    }

    override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
        PlayerLog.add(if (isLoading) "загрузка началась" else "загрузка остановлена (буфер ${player.totalBufferedDuration / 1000} с)")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        PlayerLog.add("${kind(mediaLoadData)} ок: ${loadEventInfo.loadDurationMs} мс, ${loadEventInfo.bytesLoaded / 1024} КБ, ${shortUri(loadEventInfo)}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        PlayerLog.add("ОШИБКА ${kind(mediaLoadData)}: ${error.javaClass.simpleName} ${error.message.orEmpty().take(60)} (${loadEventInfo.loadDurationMs} мс, отмена=$wasCanceled)")
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        PlayerLog.add("оценка канала: ${bitrateEstimate / 1000} кбит/с")
    }

    /** Какое качество реально играет (видно, когда и почему оно меняется). */
    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        if (mediaLoadData.trackType != C.TRACK_TYPE_VIDEO) return
        val f = mediaLoadData.trackFormat ?: return
        PlayerLog.add("качество: ${f.height}p, ${f.bitrate / 1000} кбит/с (выбор: ${mediaLoadData.trackSelectionReason})")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        PlayerLog.add("PLAYER ERROR: ${error.errorCodeName}")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        PlayerLog.add("пропущено кадров: $droppedFrames за $elapsedMs мс (слабое устройство?)")
    }

    /** При каждом обновлении плейлиста смотрим его служебные теги (только чтение). */
    override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
        val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist ?: return
        val segs = playlist.segments
        // Что за служебные теги присылает Twitch: пишем каждый уникальный один раз.
        playlist.tags.forEach { tag ->
            if (tag.startsWith("#EXT-X-DATERANGE") || tag.contains("CUE", ignoreCase = true) ||
                tag.contains("stitched", ignoreCase = true) || tag.contains("ad", ignoreCase = true)
            ) {
                val key = tag.take(160)
                if (seenTags.add(key)) PlayerLog.add("тег: $key")
            }
        }
        // Сегменты с пометкой «live» в заголовке EXTINF — обычный эфир; другие названия стоит увидеть.
        val titles = segs.takeLast(3).joinToString(",") { it.title.orEmpty().ifEmpty { "-" }.take(12) }
        PlayerLog.add("плейлист: сегментов ${segs.size}, посл. названия [$titles], seq=${playlist.mediaSequence}")
    }
}
