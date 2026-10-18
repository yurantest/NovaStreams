package com.novastreams.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.novastreams.app.core.video.VideoKind
import com.novastreams.app.feature.player.VideoPlayer
import com.novastreams.app.ui.theme.LocalIsTv

/**
 * Экран просмотра записи эфира или клипа. На ТВ — всегда на весь экран, управление D-pad.
 * На телефоне кнопка «на весь экран» разворачивает в альбомную ориентацию (так же, как у прямого эфира).
 */
@Composable
fun VideoScreen(kind: VideoKind, id: String, onBack: () -> Unit) {
    val isTv = LocalIsTv.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (!isTv) {
        VideoFullscreenEffect(fullscreen)
        BackHandler(enabled = fullscreen) { fullscreen = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoPlayer(
            kind = kind,
            id = id,
            modifier = Modifier.fillMaxSize(),
            isFullscreen = fullscreen,
            onFullscreen = if (isTv) null else ({ fullscreen = !fullscreen }),
            onBack = { if (fullscreen) fullscreen = false else onBack() },
        )
    }
}

/** Альбомная ориентация и скрытые системные панели; при выходе всё возвращается как было. */
@Composable
private fun VideoFullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findHostActivity() ?: return
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
