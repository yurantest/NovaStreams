package com.novastreams.app

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.novastreams.app.core.settings.SettingsRepository
import com.novastreams.app.ui.AppSplash
import com.novastreams.app.ui.mobile.MobileApp
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.ui.theme.NovaTheme
import com.novastreams.app.ui.theme.parseHexColor
import com.novastreams.app.ui.tv.TvApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // Twitch profile images can change after login; refresh account metadata on return to the app.
        lifecycleScope.launch { appContainer().auth.refreshProfile() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема с тёмным windowBackground должна примениться до super.onCreate,
        // иначе на ТВ/эмуляторе часто мелькает системный чёрный кадр.
        setTheme(R.style.Theme_NovaStreams)
        super.onCreate(savedInstanceState)
        val container = appContainer()
        val isTv = isTelevision()

        // Сохранённый акцент читаем ДО первого кадра: иначе первые ~секунду рисуется стандартный фиолетовый,
        // и только потом подставляется выбранный цвет. Чтение DataStore занимает считанные миллисекунды.
        val savedAccent = try {
            runBlocking { container.settings.accentHex.first() }
        } catch (e: Exception) {
            SettingsRepository.DEFAULT_ACCENT_HEX
        }

        setContent {
            val accentHex by container.settings.accentHex
                .collectAsState(initial = savedAccent)
            val accent = remember(accentHex) {
                parseHexColor(accentHex) ?: Color(0xFF9147FF)
            }
            NovaTheme(accent = accent, isTv = isTv) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(NovaColors.Background)
                        .systemBarsPadding()
                ) {
                    var showSplash by remember { mutableStateOf(true) }
                    if (showSplash) {
                        AppSplash(accent = accent, onFinished = { showSplash = false })
                    } else if (isTv) {
                        TvApp()
                    } else {
                        MobileApp()
                    }
                }
            }
        }
    }

    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }
}
