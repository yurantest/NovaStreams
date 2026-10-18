package com.novastreams.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novastreams.app.ui.theme.NovaColors
import kotlinx.coroutines.delay

/**
 * Заставка при запуске (телефон и Android TV): название и спиннер цвета акцента.
 * Системный «чёрный кадр» до этого экрана убирается тёмным windowBackground в теме.
 */
@Composable
fun AppSplash(
    accent: Color,
    minVisibleMs: Long = 700L,
    onFinished: () -> Unit,
) {
    var readyToHide by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (readyToHide) 0f else 1f,
        animationSpec = tween(400),
        label = "splashAlpha",
    )

    LaunchedEffect(Unit) {
        delay(minVisibleMs)
        readyToHide = true
        delay(420)
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NovaColors.Background)
            .alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Nova",
                    color = NovaColors.TextPrimary,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "Streams",
                    color = accent,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = accent,
                trackColor = NovaColors.Surface,
                strokeWidth = 3.dp,
            )
        }
    }
}
