package com.novastreams.app.feature.player

import androidx.activity.compose.BackHandler
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import kotlinx.coroutines.flow.first
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novastreams.app.appContainer
import com.novastreams.app.core.stream.DefaultPlaybackQuality
import com.novastreams.app.ui.components.FollowQrDialog
import com.novastreams.app.ui.theme.LocalIsTv
import com.novastreams.app.ui.theme.NovaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/** Одно доступное качество из HLS-плейлиста. */
private class QualityOption(val label: String, val group: TrackGroup, val trackIndex: Int) {
    val height: Int
        get() = label.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
}

/** Индекс в списке qualities (от высокого к низкому) или -1 = адаптивный «Авто». */
private fun pickDefaultQualityIndex(
    qualities: List<QualityOption>,
    pref: DefaultPlaybackQuality,
): Int {
    if (qualities.isEmpty()) return -1
    return when (pref) {
        DefaultPlaybackQuality.AUTO -> -1
        DefaultPlaybackQuality.LOWEST -> qualities.lastIndex
        else -> {
            val maxH = pref.maxHeight ?: return -1
            val idx = qualities.indexOfFirst { it.height in 1..maxH }
            if (idx >= 0) idx else qualities.lastIndex
        }
    }
}

private fun applyQualityToPlayer(player: ExoPlayer, qualities: List<QualityOption>, index: Int) {
    val params = player.trackSelectionParameters.buildUpon()
    if (index < 0 || index >= qualities.size) {
        params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        // Снять искусственный потолок, если был «Авто».
        params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
    } else {
        val q = qualities[index]
        params.setOverrideForType(TrackSelectionOverride(q.group, q.trackIndex))
    }
    player.trackSelectionParameters = params.build()
}



@OptIn(UnstableApi::class)
private fun twitchMediaSource(hlsUrl: String): HlsMediaSource =
    HlsMediaSource.Factory(TwitchAdFilteringDataSource.Factory())
        // Быстрее prepare: треки из master-плейлиста без скачивания первого медиа-чанка.
        .setAllowChunklessPreparation(true)
        .createMediaSource(MediaItem.fromUri(hlsUrl))

private val speeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "x"

/**
 * Свой плеер на ExoPlayer для HLS-ссылки [hlsUrl]. Видео рисует PlayerView без своего контроллера,
 * поверх — наш [PlayerControlsOverlay] с автоскрытием через 3 секунды.
 *
 * Управление на ТВ (панель скрыта): OK — пауза и показ панели, ←/→ — перемотка на 10 с, ↑/↓ — громкость.
 * Когда панель видна, D-pad перемещает фокус между кнопками.
 */
@OptIn(UnstableApi::class)
@Composable
fun HlsPlayer(
    hlsUrl: String,
    modifier: Modifier = Modifier,
    actionsFromOutside: ExternalPlayerActions = ExternalPlayerActions(),
    chatOpen: Boolean = false,
    isFullscreen: Boolean = false,
    isFavorite: Boolean = false,
    /** Логин канала — кнопка QR «Подписаться на сайте» в оверлее. */
    followLogin: String? = null,
    followingOnTwitch: Boolean = false,
    /** Перепроверить Follow на Twitch (после QR / по таймеру). */
    onFollowStatusRefresh: (() -> Unit)? = null,
    onFatalError: (String) -> Unit,
) {
    val context = LocalContext.current
    val diagnosticsOn by LocalContext.current.appContainer().settings.playerDiagnostics.collectAsState(initial = false)
    androidx.compose.runtime.SideEffect { PlayerDiagnosticsGate.enabled = diagnosticsOn }
    val isTv = LocalIsTv.current
    val preferredQuality by context.appContainer().settings.defaultPlaybackQuality
        .collectAsState(initial = DefaultPlaybackQuality.P720)
    val videoFillScreen by context.appContainer().settings.videoFillScreen
        .collectAsState(initial = false)
    val videoResizeMode by context.appContainer().settings.videoResizeMode.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    // Счётчик мягких переподключений: pre-roll/mid-roll часто рвут HLS, это не «смерть» потока.
    val recoveryAttempts = remember(hlsUrl) { AtomicInteger(0) }
    val recoveryHandler = remember { Handler(Looper.getMainLooper()) }
    // Чтобы настройку качества применить один раз, когда плейлист уже отдал дорожки.
    var appliedPreferredQuality by remember(hlsUrl) { mutableStateOf(false) }

    val useSurface = remember {
        runCatching { kotlinx.coroutines.runBlocking { context.appContainer().settings.useSurfaceView.first() } }.getOrDefault(false)
    }

    val player = remember(hlsUrl, preferredQuality) {
        // Быстрый старт + запас буфера на слабых приставках.
        val loadControl = DefaultLoadControl.Builder()
            // Старт быстрее (меньше ждать до первого кадра); min≈max — «drip-feeding», меньше рывков на live.
            .setBufferDurationsMs(
                /* minBufferMs */ 20_000,
                /* maxBufferMs */ 20_000,
                /* bufferForPlaybackMs */ 1_200,
                /* bufferForPlaybackAfterRebufferMs */ 2_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            // После каждой остановки на буферизации держимся дальше от края эфира (по умолчанию всего +50 мс):
            // на слабом канале это даёт запас и реже приводит к вылету за live-окно (BEHIND_LIVE_WINDOW).
            .setTargetLiveOffsetIncrementOnRebufferMs(1_000)
            .build()
        // Низкая начальная оценка битрейта + осторожный ABR — не хватает сразу 1080p на слабой ТВ.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(2_500_000L) // ~2.5 Мбит — ближе к реальному Wi‑Fi, 1080p не «вечный буфер»
            .build()
        val trackSelector = DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs */ 12_000,
                /* maxDurationForQualityDecreaseMs */ 6_000,
                /* minDurationToRetainAfterDiscardMs */ 8_000,
                /* bandwidthFraction */ 0.75f,
            ),
        )
        // Декодер для слабых приставок: асинхронная очередь буферов MediaCodec и запасной декодер.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .build()
            .apply {
                // Потолок только из настройки пользователя. Жёсткий «только ≤480p» ломал стримы,
                // у которых в плейлисте один вариант (например чистый 1080p) — не оставалось ни одного трека.
                val maxH = preferredQuality.maxHeight
                if (maxH != null) {
                    val h = if (maxH <= 0) 144 else maxH
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(4096, h)
                        .build()
                }
                setMediaSource(twitchMediaSource(hlsUrl))
                prepare()
                playWhenReady = true
            }
    }

    // Пока открыт плеер — не даём системе погасить экран (иначе «подсветка сама гаснет»).
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        var window = (context as? android.app.Activity)?.window
        var ctx: android.content.Context? = context
        while (window == null && ctx is android.content.ContextWrapper) {
            ctx = ctx.baseContext
            window = (ctx as? android.app.Activity)?.window
        }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            view.keepScreenOn = previous
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- Состояние, которое показывает интерфейс ----
    var isPlaying by remember(player) { mutableStateOf(false) }
    var buffering by remember(player) { mutableStateOf(true) }

    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var isLive by remember(player) { mutableStateOf(true) }
    var speedIndex by remember(player) { mutableStateOf(1) }
    var qualities by remember(player) { mutableStateOf<List<QualityOption>>(emptyList()) }
    // -1 = «Авто» (ExoPlayer выбирает сам), иначе индекс в списке qualities.
    var qualityIndex by remember(player) { mutableStateOf(-1) }
    // Выбранное качество по названию: после перезапуска плейлиста (BEHIND_LIVE_WINDOW и т.п.) объекты дорожек
    // новые, старый «override» перестаёт совпадать и ExoPlayer молча уходит в авто — возвращаем выбор по названию.
    var chosenQualityLabel by remember(player) { mutableStateOf<String?>(null) }

    var message by remember(player) { mutableStateOf<String?>(null) }
    var debugShown by remember(player) { mutableStateOf(false) }
    var stalledLong by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var showFollowQr by remember(hlsUrl) { mutableStateOf(false) }
    // Проверка Follow только после того, как пользователь открыл «Подписаться» (не фоном всегда).
    var followCheckAfterQr by remember(hlsUrl) { mutableStateOf(false) }
    LaunchedEffect(followCheckAfterQr, followingOnTwitch) {
        if (!followCheckAfterQr || followingOnTwitch) {
            if (followingOnTwitch) followCheckAfterQr = false
            return@LaunchedEffect
        }
        // Сразу после закрытия QR и ещё раз через ~20 с — успеть нажать Follow на телефоне.
        onFollowStatusRefresh?.invoke()
        kotlinx.coroutines.delay(20_000)
        if (!followingOnTwitch) onFollowStatusRefresh?.invoke()
        followCheckAfterQr = false
    }
    var lastTouch by remember(player) { mutableLongStateOf(System.nanoTime()) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun touch() {
        lastTouch = System.nanoTime()
    }

    fun showControls() {
        controlsVisible = true
        touch()
    }

    fun seekBy(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val target = player.currentPosition + deltaMs
        val limit = if (player.duration != C.TIME_UNSET) player.duration else Long.MAX_VALUE
        player.seekTo(target.coerceIn(0L, limit))
    }

    // ---- Слушатель ExoPlayer ----
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // Успешный старт/возврат к эфиру — сбрасываем счётчик ошибок.
                if (state == Player.STATE_READY) {
                    recoveryAttempts.set(0)
                }
                // Для live STATE_ENDED бывает на стыке рекламы — уходим к краю эфира, а не в «ошибку».
                if (state == Player.STATE_ENDED) {
                    try {
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.playWhenReady = true
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Собираем список качеств из видеодорожек плейлиста: от лучшего к худшему.
                val found = mutableListOf<QualityOption>()
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        if (f.height <= 0) continue
                        val fps = if (f.frameRate >= 50f) "60" else ""
                        found += QualityOption("${f.height}p$fps", group.mediaTrackGroup, i)
                    }
                }
                qualities = found.sortedByDescending { it.height }.distinctBy { it.label }
                if (qualityIndex >= qualities.size) qualityIndex = -1
                if (appliedPreferredQuality) {
                    chosenQualityLabel?.let { keep ->
                        val ni = qualities.indexOfFirst { it.label == keep }
                        if (ni >= 0) {
                            qualityIndex = ni
                            if (player.trackSelectionParameters.overrides[qualities[ni].group] == null) {
                                applyQualityToPlayer(player, qualities, ni)
                            }
                        }
                    }
                }
                // Первый раз после появления дорожек — применить качество из настроек.
                if (!appliedPreferredQuality && qualities.isNotEmpty()) {
                    appliedPreferredQuality = true
                    val idx = pickDefaultQualityIndex(qualities, preferredQuality)
                    qualityIndex = idx
                    chosenQualityLabel = qualities.getOrNull(idx)?.label
                    applyQualityToPlayer(player, qualities, idx)
                    // Если после потолка не осталось подходящего видео (стрим только 1080p, а лимит 480) —
                    // снимаем ограничение, иначе ExoPlayer крутит буфер без картинки.
                    if (idx < 0) {
                        val maxH = preferredQuality.maxHeight
                        val anyFits = maxH == null || maxH <= 0 ||
                            qualities.any { it.height in 1..(if (maxH <= 0) 144 else maxH) }
                        if (!anyFits) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                                .build()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = error.errorCode
                val behindLive = code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                val networkIsh = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    code == PlaybackException.ERROR_CODE_TIMEOUT ||
                    code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    behindLive

                // После TikTok/нагрузки на Wi‑Fi сегменты не успевают → вылет за live-окно.
                // Мгновенный prepare на ещё забитой сети снова падает; ждём и пробуем снова
                // на том же hlsUrl (без нового токена). «Повторить» не должен быть единственным выходом.
                val n = recoveryAttempts.incrementAndGet()
                val maxSoft = if (networkIsh) 24 else 8
                if (n > maxSoft) {
                    onFatalError("Не удалось воспроизвести поток (${error.errorCodeName})")
                    return
                }

                message = "Переподключение к эфиру… ($n)"
                val delayMs = when {
                    behindLive -> (800L * n).coerceAtMost(6_000L)
                    networkIsh -> (1_000L * n).coerceAtMost(8_000L)
                    else -> 500L
                }
                val url = hlsUrl
                recoveryHandler.postDelayed({
                    try {
                        if (behindLive) {
                            // Сначала только к краю эфира — URL тот же, токен не трогаем.
                            player.seekToDefaultPosition()
                            player.prepare()
                            player.playWhenReady = true
                        } else {
                            player.stop()
                            player.setMediaSource(twitchMediaSource(url))
                            player.prepare()
                            player.playWhenReady = true
                        }
                    } catch (_: Exception) {
                        try {
                            player.stop()
                            player.setMediaSource(twitchMediaSource(url))
                            player.prepare()
                            player.playWhenReady = true
                        } catch (_: Exception) {
                            if (n >= maxSoft) {
                                onFatalError("Не удалось воспроизвести поток (${error.errorCodeName})")
                            }
                        }
                    }
                }, delayMs)
            }
        }
        player.addListener(listener)
        onDispose {
            recoveryHandler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.release()
        }
    }

    // Журнал отдельно от жизненного цикла плеера — галочка в настройках без пересоздания ExoPlayer.
    DisposableEffect(player, diagnosticsOn) {
        if (!diagnosticsOn) return@DisposableEffect onDispose { }
        val diagnostics = DiagnosticsListener(player)
        val label = android.net.Uri.parse(hlsUrl).lastPathSegment.orEmpty()
            .removeSuffix(".m3u8").filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "stream" }
        PlayerLog.reset(label)
        player.addAnalyticsListener(diagnostics)
        onDispose { player.removeAnalyticsListener(diagnostics) }
    }

    // Долгая буферизация — флаг для спиннера/статуса, журнал на экран сам не вылезает.
    LaunchedEffect(player, buffering) {
        stalledLong = false
        if (buffering) {
            delay(6_000)
            stalledLong = true
        }
    }

    // Журнал на экране только вручную (0 / Info / Menu): первое нажатие — показать, второе — скрыть.
    // Сам при старте эфира не появляется.

    // ---- Остановка в фоне ----
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- Позиция только когда видна панель (скрыта — не гоняем Compose на слабых приставках) ----
    LaunchedEffect(player, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        while (controlsVisible) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            durationMs = if (d == C.TIME_UNSET) 0L else d.coerceAtLeast(0L)
            isLive = player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic
            delay(500)
        }
    }

    // TV: «Назад» сразу выходит из плеера (панель управления не показываем). Чат при открытой панели закрывает экран.
    // Пока открыт чат, «Назад» обрабатывает экран плеера: закрывает панель чата (соединение остаётся в ChatHub).
    if (isTv) {
        BackHandler(enabled = !chatOpen) {
            if (showFollowQr) {
                showFollowQr = false
            } else {
                // Звук и декодирование останавливаем сразу: освобождение плеера при выходе тяжёлое, пусть не мешает анимации.
                try { player.pause() } catch (_: Exception) { }
                actionsFromOutside.onBack?.invoke()
            }
        }
    }

    // ---- Автоскрытие панели через 3 секунды без действий ----
    LaunchedEffect(controlsVisible, lastTouch, showFollowQr) {
        if (controlsVisible && !showFollowQr) {
            delay(3_000)
            // QR открыт — панель можно скрыть, само окно QR останется.
            if (!showFollowQr) controlsVisible = false
        }
    }

    // ---- Фокус на ТВ: при показе панели — на «Пауза», при скрытии — на корень (ловит кнопки пульта) ----
    LaunchedEffect(controlsVisible) {
        if (!isTv) return@LaunchedEffect
        try {
            if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus()
        } catch (e: Exception) {
            // Фокус не критичен: пользователь переместит его D-pad.
        }
    }

    // ---- Чат свернули: возвращаем фокус плееру ----
    // Веб-чат при сворачивании остаётся живым (скрытый WebView), поэтому фокус сам в плеер не возвращается.
    // Первый запрос ждёт, пока canFocus снова станет true; второй — на случай, если WebView ещё держал фокус.
    LaunchedEffect(chatOpen) {
        if (!isTv || chatOpen) return@LaunchedEffect
        repeat(2) {
            delay(60)
            try {
                if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus()
            } catch (e: Exception) {
                // Фокус не критичен: пользователь переместит его D-pad.
            }
        }
    }

    // ---- Сообщение поверх видео пропадает само ----
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    val actions = PlayerActions(
        onTogglePlay = {
            if (player.isPlaying) player.pause() else player.play()
            touch()
        },
        onSeekBack = { seekBy(-10_000); touch() },
        onSeekForward = { seekBy(10_000); touch() },
        onSeekToFraction = { fraction ->
            if (durationMs > 0 && player.isCurrentMediaItemSeekable) player.seekTo((durationMs * fraction).toLong())
            touch()
        },
        onGoLive = { player.seekToDefaultPosition(); player.play(); touch() },
        onCycleSpeed = {
            speedIndex = (speedIndex + 1) % speeds.size
            player.setPlaybackSpeed(speeds[speedIndex])
            touch()
        },
        onCycleQuality = {
            if (qualities.isEmpty()) {
                message = "Качества пока не загружены"
            } else {
                qualityIndex = if (qualityIndex + 1 >= qualities.size) -1 else qualityIndex + 1
                chosenQualityLabel = qualities.getOrNull(qualityIndex)?.label
                applyQualityToPlayer(player, qualities, qualityIndex)
            }
            touch()
        },
        onToggleChat = actionsFromOutside.onToggleChat?.let { toggle -> { toggle(); touch() } },
        onOpenPoll = actionsFromOutside.onOpenPoll?.let { open -> { open(); touch() } },
        onClip = actionsFromOutside.onClip?.let { clip ->
            { clip { text -> message = text }; touch() }
        },
        onLayout = actionsFromOutside.onLayout?.let { layout ->
            {
                // TODO: раскладка 2×2 (несколько потоков) пока не реализована — это заглушка.
                layout()
                message = "Раскладка 2×2 пока не реализована"
                touch()
            }
        },
        onBack = actionsFromOutside.onBack,
        onToggleFavorite = actionsFromOutside.onToggleFavorite?.let { toggle -> { toggle(); touch() } },
        onOpenChannelPage = actionsFromOutside.onOpenChannelPage?.let { open -> { open(); touch() } },
        onFullscreen = actionsFromOutside.onFullscreen?.let { toggle -> { toggle(); touch() } },
        onToggleVideoFill = {
            val next = (videoResizeMode + 1) % 3
            scope.launch { context.appContainer().settings.setVideoResizeMode(next) }
            message = when (next) {
                1 -> "Обрезка: экран заполнен, края кадра обрезаются"
                2 -> "Растянуть: без полей и обрезки, картинка слегка искажается"
                else -> "Весь кадр (возможны чёрные поля)"
            }
            touch()
        },
    )

    // Внутри apply{} имя player означало бы свойство самого PlayerView, поэтому берём отдельную переменную.
    val exoPlayer = player

    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(rootFocus)
            // Пока открыт чат (IRC/Web) — плеер не держит фокус, D-pad уходит в панель чата.
            .focusProperties { canFocus = !chatOpen }
            .focusable(enabled = !chatOpen)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Чат или веб-окно «Опросы»: не трогаем стрелки/OK — ими управляет панель (курсор / фокус).
                if (isTv && chatOpen && event.key in setOf(
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    )) {
                    return@onPreviewKeyEvent false
                }
                // Info / Menu / 0 на пульте — показать или скрыть диагностику.
                if (PlayerDiagnosticsGate.active() && (event.key == Key.Info || event.key == Key.Menu || event.key == Key.Zero)) {
                    debugShown = !debugShown
                    return@onPreviewKeyEvent true
                }
                if (controlsVisible) {
                    // Панель видна: кнопки управляются фокусом, мы только продлеваем показ.
                    touch()
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // Первое OK — только показать панель; пауза — второе OK по кнопке «Пауза».
                        showControls()
                        true
                    }
                    Key.DirectionLeft -> { seekBy(-10_000); message = "−10 с"; true }
                    Key.DirectionRight -> { seekBy(10_000); message = "+10 с"; true }
                    Key.DirectionUp -> {
                        player.volume = (player.volume + 0.1f).coerceAtMost(1f)
                        message = "Громкость ${(player.volume * 100).toInt()}%"
                        true
                    }
                    Key.DirectionDown -> {
                        player.volume = (player.volume - 0.1f).coerceAtLeast(0f)
                        message = "Громкость ${(player.volume * 100).toInt()}%"
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                // Касание экрана показывает или прячет панель.
                detectTapGestures {
                    if (controlsVisible) controlsVisible = false else showControls()
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // PlayerView из XML с TextureView: интерфейс всегда поверх видео на любой приставке.
                (LayoutInflater.from(ctx).inflate(if (useSurface) com.novastreams.app.R.layout.nova_player_view_surface else com.novastreams.app.R.layout.nova_player_view, null) as PlayerView).apply {
                    useController = false          // свой контроллер на Compose
                    this.player = exoPlayer
                    // По умолчанию FIT; ZOOM — по желанию (кнопка «Экран» в панели).
                    resizeMode = videoResizeModeOf(videoResizeMode)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = {
                    it.player = exoPlayer
                    it.resizeMode = videoResizeModeOf(videoResizeMode)
                },
        )

        if (buffering) {
            CircularProgressIndicator(
                color = NovaColors.TextPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            // Понятная подпись вместо голого индикатора.
            Text(
                text = "Подключаемся к эфиру…",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            )
        }

        if (controlsVisible) {
            PlayerControlsOverlay(
                ui = PlayerUi(
                    isPlaying = isPlaying,
                    isLive = isLive,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    qualityLabel = if (qualityIndex == -1) "Авто" else qualities.getOrNull(qualityIndex)?.label ?: "Авто",
                    speedLabel = speedLabel(speeds[speedIndex]),
                    chatOpen = chatOpen,
                    isFavorite = isFavorite,
                    isFullscreen = isFullscreen,
                    videoFillScreen = videoResizeMode == 1,
                    videoResizeMode = videoResizeMode,
                ),
                actions = actions,
                playFocus = playFocus,
                modifier = Modifier.fillMaxSize(),
                followLogin = followLogin,
                followingOnTwitch = followingOnTwitch,
                onOpenFollowQr = {
                    showFollowQr = true
                    // Пока открыт QR — не прячем панель сразу.
                    controlsVisible = true
                },
            )
        }

        // QR живёт отдельно от панели управления: панель может скрыться, окно остаётся.
        if (showFollowQr) {
            val login = followLogin?.lowercase()?.filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' }.orEmpty()
            if (login.isNotEmpty()) {
                FollowQrDialog(
                    channelUrl = "https://www.twitch.tv/$login",
                    onDismiss = {
                    showFollowQr = false
                    followCheckAfterQr = true
                },
                )
            } else {
                showFollowQr = false
            }
        }

        if (PlayerDiagnosticsGate.active() && debugShown) {
            val logLines by PlayerLog.lines.collectAsState()
            Text(
                text = logLines.takeLast(14).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8FFB8),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000))
                    .padding(8.dp),
            )
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC101014))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Действия, которыми владеет не плеер, а экран вокруг него: назад, чат, клип, раскладка.
 * null — кнопки нет.
 */
data class ExternalPlayerActions(
    val onBack: (() -> Unit)? = null,
    val onToggleChat: (() -> Unit)? = null,
    /** Опросы и баллы: окно чата с сайта Twitch поверх стрима. */
    val onOpenPoll: (() -> Unit)? = null,
    /** Сердечко «в избранное» (локальное, на устройстве). */
    val onToggleFavorite: (() -> Unit)? = null,
    /** Открыть страницу стримера (записи и клипы). */
    val onOpenChannelPage: (() -> Unit)? = null,
    /** Получает функцию показа сообщения поверх видео. */
    val onClip: (((String) -> Unit) -> Unit)? = null,
    val onLayout: (() -> Unit)? = null,
    val onFullscreen: (() -> Unit)? = null,
)
