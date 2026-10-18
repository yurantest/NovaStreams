package com.novastreams.app.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novastreams.app.appContainer
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.Stream
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.LocalChannelPageOpener
import com.novastreams.app.ui.theme.LocalIsTv
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Плеер канала на ExoPlayer + HLS (GraphQL + Usher).
 * Демо / не в эфире → заглушка. При ошибке — «Повторить».
 */
@Composable
fun ChannelPlayer(
    channelLogin: String,
    stream: Stream?,
    isDemo: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    channelId: String = "",
    onBack: (() -> Unit)? = null,
    chatOpen: Boolean = false,
    onToggleChat: (() -> Unit)? = null,
    onOpenPoll: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    followingOnTwitch: Boolean = false,
    onFollowStatusRefresh: (() -> Unit)? = null,
) {
    when {
        isDemo -> PlayerPlaceholder(
            stream = stream,
            modifier = modifier,
            compact = compact,
            title = "Воспроизведение недоступно",
            subtitle = "Войдите через Twitch или проверьте интернет.",
        )

        stream == null -> PlayerPlaceholder(
            stream = null,
            modifier = modifier,
            compact = compact,
            title = "Канал сейчас не в эфире",
            subtitle = "Когда трансляция начнётся, здесь появится плеер.",
        )

        else -> ExoChannelPlayer(
            channelLogin = channelLogin,
            channelId = channelId,
            modifier = modifier,
            chatOpen = chatOpen,
            onToggleChat = onToggleChat,
            onOpenPoll = onOpenPoll,
            onBack = onBack,
            onFullscreen = onFullscreen,
            isFullscreen = isFullscreen,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            followingOnTwitch = followingOnTwitch,
            onFollowStatusRefresh = onFollowStatusRefresh,
        )
    }
}

@Composable
private fun ExoChannelPlayer(
    channelLogin: String,
    channelId: String,
    modifier: Modifier,
    chatOpen: Boolean,
    onToggleChat: (() -> Unit)?,
    onOpenPoll: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onFullscreen: (() -> Unit)?,
    isFullscreen: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    followingOnTwitch: Boolean = false,
    onFollowStatusRefresh: (() -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer()
    val isTv = LocalIsTv.current
    val openChannelPage = LocalChannelPageOpener.current
    val scope = rememberCoroutineScope()
    var attempt by remember(channelLogin) { mutableStateOf(0) }
    var playbackError by remember(channelLogin, attempt) { mutableStateOf<String?>(null) }

    val url = produceState<Load<String>>(initialValue = Load.Loading, channelLogin, attempt) {
        value = Load.Loading
        value = try {
            Load.Ready(withTimeout(25_000) { container.hlsStreams.liveHlsUrl(channelLogin) })
        } catch (e: TimeoutCancellationException) {
            Load.Failed("Twitch слишком долго не отвечает. Проверьте интернет")
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Не удалось получить ссылку на поток")
        }
    }

    val loaded = url.value
    val failure = playbackError ?: (loaded as? Load.Failed)?.message

    // ---- Обрыв эфира у стримера ----
    // Стример может отвалиться на минуту-другую (перезапуск OBS, обрыв связи). Вместо экрана
    // ошибки плеера показываем «ждём стримера» и сами опрашиваем канал: как только плейлист
    // снова отдаётся — перезапускаем плеер. Сдаёмся только через WAIT_LIMIT_MS.
    var gaveUp by remember(channelLogin, attempt) { mutableStateOf(false) }
    var ended by remember(channelLogin, attempt) { mutableStateOf(false) }
    var waitLive by remember(channelLogin, attempt) { mutableStateOf<Boolean?>(null) }
    var waitSeconds by remember(channelLogin, attempt) { mutableStateOf(0) }
    LaunchedEffect(failure != null, attempt) {
        if (failure == null) return@LaunchedEffect
        val started = System.currentTimeMillis()
        var pause = 3_000L
        while (System.currentTimeMillis() - started < WAIT_LIMIT_MS) {
            val until = System.currentTimeMillis() + pause
            while (System.currentTimeMillis() < until) {
                delay(1_000)
                waitSeconds = ((System.currentTimeMillis() - started) / 1000).toInt()
            }
            pause = (pause + 2_000L).coerceAtMost(10_000L)
            if (channelId.isNotBlank()) {
                try {
                    waitLive = container.twitch.streamForChannel(channelId) != null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Нет ответа API — просто пробуем плейлист.
                }
            }
            if (waitLive == false) continue // канал офлайн — токен просить рано
            try {
                container.hlsStreams.invalidate(channelLogin)
                withTimeout(15_000) { container.hlsStreams.liveHlsUrl(channelLogin) }
                attempt++ // плейлист снова жив — перезапускаем плеер
                return@LaunchedEffect
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ещё не вернулся — ждём дальше.
            }
        }
        gaveUp = true
        if (waitLive == false) ended = true
    }

    when {
        failure != null && !gaveUp -> WaitingForStreamer(
            channelId = channelId,
            live = waitLive,
            seconds = waitSeconds,
            onRetryNow = { attempt++ },
            onBack = onBack,
            modifier = modifier,
        )

        ended -> BroadcastEnded(
            channelId = channelId,
            onCheckAgain = {
                ended = false
                attempt++
            },
            onBack = onBack,
            modifier = modifier,
        )

        failure != null -> PlayerError(
            message = failure,
            onRetry = { attempt++ },
            modifier = modifier,
        )

        loaded is Load.Ready -> HlsPlayer(
            hlsUrl = loaded.value,
            modifier = modifier,
            chatOpen = chatOpen,
            isFullscreen = isFullscreen,
            isFavorite = isFavorite,
            followLogin = channelLogin,
            followingOnTwitch = followingOnTwitch,
            onFollowStatusRefresh = onFollowStatusRefresh,
            actionsFromOutside = ExternalPlayerActions(
                onToggleFavorite = onToggleFavorite,
                onBack = onBack,
                onToggleChat = onToggleChat,
                onOpenPoll = onOpenPoll,
                onFullscreen = onFullscreen,
                onOpenChannelPage = channelId.takeIf { it.isNotBlank() }?.let { id ->
                    { openChannelPage(id) }
                },
                onClip = if (channelId.isNotBlank()) ({
                    show: (String) -> Unit ->
                    scope.launch {
                        if (container.auth.state.value !is AuthState.SignedIn) {
                            show("Чтобы сделать клип, войдите через Twitch")
                        } else {
                            show(
                                try {
                                    "Клип создан: " + container.helixApi.createClip(channelId)
                                } catch (e: Exception) {
                                    e.message ?: "Не удалось создать клип"
                                }
                            )
                        }
                    }
                    Unit
                }) else null,
                onLayout = if (isTv && onBack != null) ({}) else null,
            ),
            onFatalError = { playbackError = it },
        )

        else -> Box(modifier) { LoadingState() }
    }
}

private const val WAIT_LIMIT_MS = 15 * 60_000L

/** Эфир оборвался: ждём, пока стример вернётся. Плеер перезапустится сам. */
@Composable
private fun WaitingForStreamer(
    channelId: String,
    live: Boolean?,
    seconds: Int,
    onRetryNow: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val openChannelPage = LocalChannelPageOpener.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // Фокус не критичен.
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = LocalNovaColors.current.accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Ждём стримера…",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
            )
            Text(
                text = (if (live == false) "Канал сейчас не в эфире. " else "Связь с эфиром потеряна. ") +
                    "Как только трансляция вернётся, она запустится сама. Ожидание: ${seconds / 60}:${"%02d".format(seconds % 60)}",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(
                    text = "Проверить сейчас",
                    onClick = onRetryNow,
                    modifier = Modifier.focusRequester(focus),
                )
                if (channelId.isNotBlank()) {
                    AccentButton(text = "Записи и клипы", onClick = { openChannelPage(channelId) }, filled = false)
                }
                if (onBack != null) AccentButton(text = "Назад", onClick = onBack, filled = false)
            }
        }
    }
}

@Composable
private fun PlayerError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Плеер не запустился",
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
            AccentButton(text = "Повторить", onClick = onRetry)
        }
    }
}

/**
 * Эфир закончился: понятная плашка вместо ошибки. Дальше можно открыть записи и клипы канала,
 * вернуться назад или проверить ещё раз (если стример просто переподключается, плеер запустится сам).
 */
@Composable
private fun BroadcastEnded(
    channelId: String,
    onCheckAgain: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val openChannelPage = LocalChannelPageOpener.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // Фокус не критичен: пользователь переместит его D-pad.
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Трансляция закончилась",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
            )
            Text(
                text = "Стример завершил эфир. Посмотрите запись или клипы.",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (channelId.isNotBlank()) {
                    AccentButton(
                        text = "Записи и клипы",
                        onClick = { openChannelPage(channelId) },
                        modifier = Modifier.focusRequester(focus),
                    )
                }
                AccentButton(text = "Проверить снова", onClick = onCheckAgain, filled = false)
                if (onBack != null) AccentButton(text = "Назад", onClick = onBack, filled = false)
            }
        }
    }
}
