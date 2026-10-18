package com.novastreams.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import com.novastreams.app.ui.theme.LocalNovaColors

/**
 * Кликабельный элемент с заметным, но спокойным фокусом для D-pad:
 * тонкая рамка цвета акцента и лёгкое увеличение (без свечения).
 * Центральная кнопка пульта (DPAD_CENTER/Enter) срабатывает как клик.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.novaClickable(
    shape: Shape,
    focusScale: Float = 1.04f,
    /** Долгое нажатие (на ТВ — удержание OK, на телефоне — долгое касание); null — не обрабатывается. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val accent = LocalNovaColors.current.accent
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = when {
        pressed -> 0.98f
        focused -> focusScale
        else -> 1f
    }
    val scale by animateFloatAsState(targetValue = target, animationSpec = tween(120), label = "focusScale")
    this
        .onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .then(if (focused) Modifier.border(2.dp, accent, shape) else Modifier)
        .focusable()
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
