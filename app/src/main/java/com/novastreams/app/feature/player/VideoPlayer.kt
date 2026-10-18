@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novastreams.app.feature.player

import androidx.activity.compose.BackHandler
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import kotlinx.coroutines.flow.first
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.video.ResolvedVideo
import com.novastreams.app.core.video.VideoKind
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.theme.LocalIsTv
import com.novastreams.app.ui.theme.NovaColors
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val vodSpeeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

private fun vodSpeedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "x"

private class VodQuality(val label: String, val group: TrackGroup, val trackIndex: Int) {
    val height: Int get() = label.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
}

/**
 * Плеер записи эфира или клипа. Отдельный от плеера прямого эфира: тут есть перемотка и время,
 * запоминание места просмотра, нет «Эфир» и нет обработки рекламных сегментов.
 * Панель управления — та же, что и у прямого эфира.
 */
@Composable
fun VideoPlayer(
    kind: VideoKind,
    id: String,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer()
    var attempt by remember(kind, id) { mutableStateOf(0) }
    var playbackError by remember(kind, id, attempt) { mutableStateOf<String?>(null) }

    val resolved = produceState<Load<ResolvedVideo>>(initialValue = Load.Loading, kind, id, attempt) {
        value = Load.Loading
        value = try {
            Load.Ready(withTimeout(25_000) { container.videos.resolve(kind, id) })
        } catch (e: TimeoutCancellationException) {
            Load.Failed("Twitch слишком долго не отвечает. Проверьте интернет")
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Не удалось получить ссылку на видео")
        }
    }

    val loaded = resolved.value
    val failure = playbackError ?: (loaded as? Load.Failed)?.message
    when {
        failure != null -> VideoError(failure, onRetry = { attempt++ }, onBack = onBack, modifier = modifier)
        loaded is Load.Ready -> LoadedVideoPlayer(
            kind = kind,
            id = id,
            source = loaded.value,
            modifier = modifier,
            isFullscreen = isFullscreen,
            onFullscreen = onFullscreen,
            onBack = onBack,
            onFatalError = { playbackError = it },
        )
        else -> Box(modifier.background(Color.Black)) { LoadingState() }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LoadedVideoPlayer(
    kind: VideoKind,
    id: String,
    source: ResolvedVideo,
    modifier: Modifier,
    isFullscreen: Boolean,
    onFullscreen: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onFatalError: (String) -> Unit,
) {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    val progress = remember { context.appContainer().videoProgress }

    // Клип — набор mp4: по умолчанию берём лучшее качество не выше 720p.
    val variants = (source as? ResolvedVideo.Files)?.variants.orEmpty()
    var variantIndex by remember(source) {
        mutableStateOf(
            variants.indexOfFirst { (it.label.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0) in 1..720 }
                .let { if (it < 0) 0 else it }
        )
    }

    fun mediaItemFor(index: Int): MediaItem = when (source) {
        is ResolvedVideo.Hls -> MediaItem.fromUri(source.url)
        is ResolvedVideo.Files -> MediaItem.fromUri(source.variants[index.coerceIn(0, source.variants.lastIndex)].url)
    }

    val useSurface = remember {
        runCatching { kotlinx.coroutines.runBlocking { context.appContainer().settings.useSurfaceView.first() } }.getOrDefault(false)
    }
    val videoFillScreen by context.appContainer().settings.videoFillScreen.collectAsState(initial = false)
    val videoResizeMode by context.appContainer().settings.videoResizeMode.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    val player = remember(source) {
        // Настройки декодера для слабых приставок (идея из разбора ExoPlayer-плееров): асинхронная очередь буферов MediaCodec
        // (до Android 12 по умолчанию выключена и снижает «микро-подвисания» картинки) и запасной декодер, если основной не справился.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            // Записи продолжаем с того места, где остановились (клипы короткие — с начала).
            val start = if (kind == VideoKind.VOD) progress.get(id) else 0L
            setMediaItem(mediaItemFor(variantIndex), start)
            prepare()
            playWhenReady = true
        }
    }

    // ---- Состояние интерфейса ----
    var isPlaying by remember(player) { mutableStateOf(false) }
    var buffering by remember(player) { mutableStateOf(true) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var speedIndex by remember(player) { mutableStateOf(1) }
    var qualities by remember(player) { mutableStateOf<List<VodQuality>>(emptyList()) }
    var qualityIndex by remember(player) { mutableStateOf(-1) }
    var message by remember(player) { mutableStateOf<String?>(null) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
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
        val limit = if (player.duration != C.TIME_UNSET) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, limit))
    }

    fun savePosition() {
        if (kind != VideoKind.VOD) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = if (player.duration == C.TIME_UNSET) 0L else player.duration
        // Почти досмотрели — в следующий раз начнём сначала.
        progress.put(id, if (dur > 0 && pos > dur - 15_000) 0L else if (pos < 10_000) 0L else pos)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    // Досмотрели: оставляем паузу на последнем кадре и показываем панель.
                    isPlaying = false
                    showControls()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (source !is ResolvedVideo.Hls) return
                val found = mutableListOf<VodQuality>()
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        if (f.height <= 0) continue
                        found += VodQuality("${f.height}p" + if (f.frameRate >= 50f) "60" else "", group.mediaTrackGroup, i)
                    }
                }
                qualities = found.sortedByDescending { it.height }.distinctBy { it.label }
                if (qualityIndex >= qualities.size) qualityIndex = -1
            }

            override fun onPlayerError(error: PlaybackException) {
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                onFatalError(
                    when {
                        http?.responseCode == 403 -> "Эта запись доступна только подписчикам канала"
                        http?.responseCode == 404 -> "Запись или клип удалены"
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Нет связи с Twitch. Проверьте интернет"
                        else -> "Не удалось воспроизвести видео (${error.errorCodeName})"
                    }
                )
            }
        }
        player.addListener(listener)
        onDispose {
            savePosition()
            player.removeListener(listener)
            player.release()
        }
    }

    // Не гасим экран во время просмотра.
    val view = LocalView.current
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

    // В фоне — пауза и сохранение места.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                savePosition()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Позиция дважды в секунду; место просмотра — раз в 5 секунд.
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            durationMs = if (d == C.TIME_UNSET) 0L else d.coerceAtLeast(0L)
            if (++tick % 10 == 0 && player.isPlaying) savePosition()
            delay(500)
        }
    }

    if (isTv) {
        BackHandler {
            if (!controlsVisible) showControls()
            else onBack?.invoke()
        }
    }

LaunchedEffect(controlsVisible, lastTouch) {
        if (controlsVisible && isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible) {
        if (!isTv) return@LaunchedEffect
        try {
            if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus()
        } catch (_: Exception) {
            // Фокус не критичен.
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    val actions = PlayerActions(
        onTogglePlay = {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            if (player.isPlaying) player.pause() else player.play()
            touch()
        },
        onSeekBack = { seekBy(-10_000); touch() },
        onSeekForward = { seekBy(10_000); touch() },
        onSeekToFraction = { fraction ->
            if (durationMs > 0 && player.isCurrentMediaItemSeekable) player.seekTo((durationMs * fraction).toLong())
            touch()
        },
        onGoLive = {},
        onCycleSpeed = {
            speedIndex = (speedIndex + 1) % vodSpeeds.size
            player.setPlaybackSpeed(vodSpeeds[speedIndex])
            touch()
        },
        onCycleQuality = {
            when {
                source is ResolvedVideo.Files && variants.size > 1 -> {
                    // Другой файл клипа: переключаемся с того же места.
                    val pos = player.currentPosition
                    variantIndex = (variantIndex + 1) % variants.size
                    player.setMediaItem(mediaItemFor(variantIndex), pos)
                    player.prepare()
                    player.playWhenReady = true
                }
                source is ResolvedVideo.Hls && qualities.isNotEmpty() -> {
                    qualityIndex = if (qualityIndex + 1 >= qualities.size) -1 else qualityIndex + 1
                    val params = player.trackSelectionParameters.buildUpon()
                    if (qualityIndex < 0) params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    else params.setOverrideForType(TrackSelectionOverride(qualities[qualityIndex].group, qualities[qualityIndex].trackIndex))
                    player.trackSelectionParameters = params.build()
                }
                else -> message = "Другого качества нет"
            }
            touch()
        },
        onToggleChat = null,
        onClip = null,
        onLayout = null,
        onBack = onBack,
        onToggleFavorite = null,
        onFullscreen = onFullscreen?.let { toggle -> { toggle(); touch() } },
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

    val exoPlayer = player
    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controlsVisible) {
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
                detectTapGestures {
                    if (controlsVisible) controlsVisible = false else showControls()
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(if (useSurface) com.novastreams.app.R.layout.nova_player_view_surface else com.novastreams.app.R.layout.nova_player_view, null) as PlayerView).apply {
                    useController = false
                    this.player = exoPlayer
                    // Запись смотрим целиком, без обрезки краёв кадра.
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
            CircularProgressIndicator(color = NovaColors.TextPrimary, modifier = Modifier.align(Alignment.Center))
        }

        if (controlsVisible) {
            val qualityLabel = when {
                source is ResolvedVideo.Files -> variants.getOrNull(variantIndex)?.label ?: "Авто"
                qualityIndex < 0 -> "Авто"
                else -> qualities.getOrNull(qualityIndex)?.label ?: "Авто"
            }
            PlayerControlsOverlay(
                ui = PlayerUi(
                    isPlaying = isPlaying,
                    isLive = false,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    qualityLabel = qualityLabel,
                    speedLabel = vodSpeedLabel(vodSpeeds[speedIndex]),
                    chatOpen = false,
                    isFullscreen = isFullscreen,
                    videoFillScreen = videoResizeMode == 1,
                    videoResizeMode = videoResizeMode,
                ),
                actions = actions,
                playFocus = playFocus,
                modifier = Modifier.fillMaxSize(),
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

@Composable
private fun VideoError(
    message: String,
    onRetry: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Видео не запустилось",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(text = "Повторить", onClick = onRetry, modifier = Modifier.focusRequester(focus))
                if (onBack != null) AccentButton(text = "Назад", onClick = onBack, filled = false)
            }
        }
    }
}
