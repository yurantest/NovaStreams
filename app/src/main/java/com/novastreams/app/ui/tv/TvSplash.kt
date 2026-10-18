package com.novastreams.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.novastreams.app.ui.AppSplash

/** Обратная совместимость: та же заставка, что и на телефоне. */
@Composable
fun TvSplash(
    accent: Color,
    minVisibleMs: Long = 1_200L,
    onFinished: () -> Unit,
) {
    AppSplash(accent = accent, minVisibleMs = minVisibleMs, onFinished = onFinished)
}
