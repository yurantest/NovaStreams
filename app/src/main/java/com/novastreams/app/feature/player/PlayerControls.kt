package com.novastreams.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors

/** Всё, что нужно нарисовать поверх видео. Состояние хранит HlsPlayer, здесь только отображение. */
data class PlayerUi(
    val isPlaying: Boolean,
    /** Прямой эфир: вместо «позиция / длительность» показываем отметку «В эфире». */
    val isLive: Boolean,
    val positionMs: Long,
    /** Длительность доступного окна перемотки; 0 — неизвестна. */
    val durationMs: Long,
    val qualityLabel: String,
    val speedLabel: String,
    val chatOpen: Boolean,
    val isFullscreen: Boolean = false,
    /** true — ZOOM (весь экран, края могут обрезаться). */
    val videoFillScreen: Boolean = false,
    /** 0 — весь кадр, 1 — обрезка краёв, 2 — растянуть (см. SettingsRepository.videoResizeMode). */
    val videoResizeMode: Int = 0,
    /** Канал в локальном избранном (сердечко закрашено). */
    val isFavorite: Boolean = false,
)

/** Действия кнопок. null — кнопка не показывается (например, чат на маленьком плеере). */
data class PlayerActions(
    val onTogglePlay: () -> Unit,
    val onSeekBack: () -> Unit,
    val onSeekForward: () -> Unit,
    val onSeekToFraction: (Float) -> Unit,
    val onGoLive: () -> Unit,
    val onCycleSpeed: () -> Unit,
    val onCycleQuality: () -> Unit,
    val onToggleChat: (() -> Unit)?,
    val onClip: (() -> Unit)?,
    val onLayout: (() -> Unit)?,
    val onBack: (() -> Unit)?,
    val onFullscreen: (() -> Unit)? = null,
    /** Опросы и баллы (окно чата с сайта Twitch); null — кнопки нет. */
    val onOpenPoll: (() -> Unit)? = null,
    /** Переключить FIT ↔ ZOOM. */
    val onToggleVideoFill: (() -> Unit)? = null,
    /** Сердечко «в избранное»; null — кнопка не показывается. */
    val onToggleFavorite: (() -> Unit)? = null,
    /** Страница канала: записи и клипы. */
    val onOpenChannelPage: (() -> Unit)? = null,
)

/** Круглая кнопка только с иконкой (без подписи). */
@Composable
private fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    large: Boolean = false,
) {
    val accent = LocalNovaColors.current.accent
    val size = if (large) 52.dp else 40.dp
    val iconSize = if (large) 28.dp else 22.dp
    Box(
        modifier = modifier
            .size(size)
            .novaClickable(CircleShape, focusScale = 1.08f, onClick = onClick)
            .clip(CircleShape)
            .background(Color(0xCC101014)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) accent else NovaColors.TextPrimary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Компактная кнопка: иконка + короткая подпись (для скорости/качества). */
@Composable
private fun ChipButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val accent = LocalNovaColors.current.accent
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .novaClickable(shape, focusScale = 1.05f, onClick = onClick)
            .clip(shape)
            .background(Color(0xCC101014))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) accent else NovaColors.TextPrimary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) accent else NovaColors.TextPrimary,
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Тонкая полоса прогресса: тап по ней перематывает. */
@Composable
private fun ProgressBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalNovaColors.current.accent
    Box(
        modifier = modifier
            .height(18.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x55FFFFFF))
        )
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
    }
}

/**
 * Панель управления поверх видео:
 *  сверху — назад / чат / полный экран;
 *  по центру — −10 / play / +10 (только иконки);
 *  снизу — прогресс и компактные кнопки без наложений.
 */
@Composable
fun PlayerControlsOverlay(
    ui: PlayerUi,
    actions: PlayerActions,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
    /** Логин канала для кнопки «Подписаться на сайте»; null — кнопки нет. */
    followLogin: String? = null,
    followingOnTwitch: Boolean = false,
    /** QR держим в HlsPlayer, чтобы не исчезал вместе с панелью управления. */
    onOpenFollowQr: (() -> Unit)? = null,
) {
    val canFollow = !followingOnTwitch && !followLogin.isNullOrBlank() && onOpenFollowQr != null

    Box(modifier = modifier.fillMaxSize().background(Color(0x66000000))) {
        // ——— Верхняя полоса ———
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.onBack?.let { back ->
                IconButton(
                    icon = Icons.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    onClick = back,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.onToggleFavorite?.let {
                    IconButton(
                        icon = if (ui.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (ui.isFavorite) "Убрать из избранного" else "В избранное",
                        onClick = it,
                        active = ui.isFavorite,
                    )
                }
                actions.onOpenChannelPage?.let {
                    IconButton(
                        icon = Icons.Rounded.VideoLibrary,
                        contentDescription = "Открыть канал",
                        onClick = it,
                    )
                }
                // TV/плеер: QR открывается снаружи оверлея — не пропадает вместе с панелью.
                if (canFollow) {
                    IconButton(
                        icon = Icons.Rounded.PersonAdd,
                        contentDescription = "Подписаться на сайте",
                        onClick = { onOpenFollowQr?.invoke() },
                    )
                }
                actions.onToggleChat?.let {
                    IconButton(
                        icon = Icons.Rounded.Chat,
                        contentDescription = "Чат",
                        onClick = it,
                        active = ui.chatOpen,
                    )
                }
                actions.onOpenPoll?.let {
                    IconButton(
                        icon = Icons.Rounded.Poll,
                        contentDescription = "Опросы и баллы",
                        onClick = it,
                    )
                }
            }
        }

        // ——— Центр: перемотка и пауза (только иконки) ———
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                icon = Icons.Rounded.Replay10,
                contentDescription = "Назад 10 секунд",
                onClick = actions.onSeekBack,
            )
            IconButton(
                icon = if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (ui.isPlaying) "Пауза" else "Играть",
                onClick = actions.onTogglePlay,
                modifier = Modifier.focusRequester(playFocus),
                large = true,
            )
            IconButton(
                icon = Icons.Rounded.Forward10,
                contentDescription = "Вперёд 10 секунд",
                onClick = actions.onSeekForward,
            )
        }

        // ——— Нижняя зона: прогресс + действия ———
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.isLive) {
                    Text(
                        text = "В ЭФИРЕ",
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaColors.Live,
                    )
                } else {
                    Text(
                        text = formatTime(ui.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaColors.TextPrimary,
                    )
                }
                if (ui.durationMs > 0) {
                    ProgressBar(
                        fraction = ui.positionMs.toFloat() / ui.durationMs.toFloat(),
                        onSeek = actions.onSeekToFraction,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (ui.isLive) {
                            "−" + formatTime(ui.durationMs - ui.positionMs)
                        } else {
                            formatTime(ui.durationMs)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaColors.TextSecondary,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // «На весь экран» стоит в верхней строке справа: нижняя строка кнопок на узком телефоне
                // не помещается целиком, и последняя кнопка уходила за край экрана.
                actions.onFullscreen?.let {
                    IconButton(
                        icon = if (ui.isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (ui.isFullscreen) "Свернуть" else "На весь экран",
                        onClick = it,
                    )
                }
            }

            // Одна строка компактных кнопок — без переноса и наложений.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.isLive) {
                    ChipButton(
                        icon = Icons.Rounded.PlayArrow,
                        label = "Эфир",
                        onClick = actions.onGoLive,
                    )
                }
                ChipButton(
                    icon = Icons.Rounded.Speed,
                    label = ui.speedLabel,
                    onClick = actions.onCycleSpeed,
                )
                actions.onToggleVideoFill?.let {
                    ChipButton(
                        icon = when (ui.videoResizeMode) {
                            1 -> Icons.Rounded.ZoomOutMap
                            2 -> Icons.Rounded.AspectRatio
                            else -> Icons.Rounded.FitScreen
                        },
                        label = when (ui.videoResizeMode) {
                            1 -> "Обрезка"
                            2 -> "Растянуть"
                            else -> "Кадр"
                        },
                        onClick = it,
                        active = ui.videoResizeMode != 0,
                    )
                }
                ChipButton(
                    icon = Icons.Rounded.HighQuality,
                    label = ui.qualityLabel,
                    onClick = actions.onCycleQuality,
                )
                actions.onClip?.let {
                    IconButton(
                        icon = Icons.Rounded.ContentCut,
                        contentDescription = "Нарезка",
                        onClick = it,
                    )
                }
                actions.onLayout?.let {
                    IconButton(
                        icon = Icons.Rounded.GridView,
                        contentDescription = "Раскладка",
                        onClick = it,
                    )
                }
            }
        }
    }
}


/** Режим масштаба видео (0 кадр, 1 обрезка, 2 растянуть) → режим PlayerView. */
internal fun videoResizeModeOf(mode: Int): Int = when (mode) {
    1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
}
