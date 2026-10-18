package com.novastreams.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novastreams.app.ui.theme.LocalIsTv
import com.novastreams.app.ui.theme.NovaColors

/**
 * «Подписаться на сайте»: у Twitch нет API, которым стороннее приложение могло бы подписать пользователя на канал
 * (кнопка «Follow» есть только на самом сайте), поэтому открываем страницу канала на twitch.tv.
 * Телефон — браузер поверх приложения (Custom Tab). ТВ (или если браузера нет) — окно с QR-кодом и ссылкой:
 * на приставках браузера часто нет, а страницу можно открыть на телефоне.
 */
@Composable
fun FollowOnSiteButton(
    login: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    /** Уже Follow на Twitch — кнопку не показываем. */
    followingOnTwitch: Boolean = false,
) {
    if (followingOnTwitch) return

    val context = LocalContext.current
    val isTv = LocalIsTv.current
    var showDialog by remember { mutableStateOf(false) }
    val url = "https://www.twitch.tv/" + login.lowercase().filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' }

    AccentButton(
        text = "Подписаться на сайте",
        filled = filled,
        modifier = modifier,
        onClick = {
            if (isTv) {
                showDialog = true
            } else {
                try {
                    androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                } catch (e: Exception) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e2: Exception) {
                        showDialog = true // нет браузера — покажем ссылку и QR
                    }
                }
            }
        },
    )

    if (showDialog) {
        FollowQrDialog(channelUrl = url, onDismiss = { showDialog = false })
    }
}

/**
 * Маленькое окно с QR на страницу канала (Follow на сайте).
 * Не уводит с экрана плеера — закрывается кнопкой или «Назад».
 */
@Composable
fun FollowQrDialog(channelUrl: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(NovaColors.Card)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Подписка на сайте Twitch",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Наведите камеру телефона на код и нажмите Follow на странице. Плеер можно не закрывать.",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            QrCode(content = channelUrl, size = 200.dp)
            Spacer(Modifier.height(10.dp))
            Text(channelUrl, style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextPrimary)
            Spacer(Modifier.height(14.dp))
            AccentButton(text = "Закрыть", onClick = onDismiss)
        }
    }
}
