package com.novastreams.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novastreams.app.appContainer
import com.novastreams.app.ui.theme.NovaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Часы в верхнем углу экрана Android TV. Сторона и размер задаются в настройках.
 * Не получают фокус и не перехватывают кнопки пульта.
 */
@Composable
fun TvClockOverlay(modifier: Modifier = Modifier, isPlayerScreen: Boolean = false) {
    val settings = LocalContext.current.appContainer().settings
    val enabled by settings.tvClockEnabled.collectAsState(initial = true)
    val right by settings.tvClockRight.collectAsState(initial = true)
    val size by settings.tvClockSize.collectAsState(initial = 1)
    if (!enabled) return

    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            val now = System.currentTimeMillis()
            text = format.format(Date(now))
            // Просыпаемся в начале следующей минуты.
            kotlinx.coroutines.delay(60_000L - now % 60_000L + 50L)
        }
    }

    val fontSize = when (size) {
        0 -> 16.sp
        1 -> 24.sp
        2 -> 34.sp
        else -> 48.sp
    }
    Box(
        modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = if (isPlayerScreen) 76.dp else 14.dp)
    ) {
        Text(
            text = text,
            color = NovaColors.TextPrimary,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(if (right) Alignment.TopEnd else Alignment.TopStart)
                // В плеере часы опускаются ниже верхней панели и становятся полупрозрачными.
                // Это сохраняет видимость времени, не конкурируя с кнопками управления.
                .alpha(if (isPlayerScreen) 0.58f else 1f)
                .background(Color.Black.copy(alpha = if (isPlayerScreen) 0.28f else 0.45f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
