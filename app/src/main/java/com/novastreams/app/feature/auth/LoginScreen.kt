package com.novastreams.app.feature.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.QrCode
import com.novastreams.app.ui.components.TwitchWebPanel
import com.novastreams.app.ui.theme.LocalIsTv
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

/**
 * Экран входа. Единственный способ — вход по коду (Device Code): приложение показывает код,
 * вы вводите его на странице Twitch (на телефоне, компьютере или в браузере этого же устройства).
 * Адрес перенаправления не нужен, токен приложение получает само.
 */
@Composable
fun LoginScreen(onClose: () -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: AuthViewModel = viewModel(factory = vmFactory { AuthViewModel(container) })
    val ui by vm.ui.collectAsState()
    val auth by vm.authState.collectAsState()
    val isTv = LocalIsTv.current

    LaunchedEffect(auth) {
        if (auth is AuthState.SignedIn) onClose()
    }

    BackHandler {
        if (ui.deviceCode != null) vm.cancel() else onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NovaColors.Background)
    ) {
        when {
            ui.busy -> LoadingState()

            ui.deviceCode != null -> DeviceCodeContent(
                userCode = ui.deviceCode!!.userCode,
                uri = cleanActivationUri(ui.deviceCode!!.verificationUri),
                fullLink = activationLink(ui.deviceCode!!.verificationUri, ui.deviceCode!!.userCode),
                canOpenPage = !isTv,
                // На ТВ показываем QR со ссылкой на страницу подтверждения: навёл камеру телефона — страница открылась.
                qrLink = if (isTv) activationLink(ui.deviceCode!!.verificationUri, ui.deviceCode!!.userCode) else null,
                status = ui.deviceStatus,
                onCheckNow = vm::checkNow,
                onCancel = { vm.cancel(); onClose() },
            )

            ui.error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Не удалось войти",
                    style = MaterialTheme.typography.titleLarge,
                    color = NovaColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = ui.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                AccentButton(text = "Повторить", onClick = { vm.startDeviceLogin() })
                Spacer(Modifier.height(12.dp))
                AccentButton(text = "Закрыть", onClick = onClose, filled = false)
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Вход в Twitch",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NovaColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (isTv) {
                        "Вход идёт по коду: вы откроете страницу Twitch на телефоне или компьютере, введёте код, войдёте в аккаунт и разрешите доступ."
                    } else {
                        "Приложение покажет короткий код. Вы откроете страницу Twitch, введёте код, войдёте в аккаунт и разрешите доступ к подпискам и чату — приложение получит разрешение само."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = NovaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                AccentButton(
                    text = "Войти по коду",
                    onClick = { vm.startDeviceLogin() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                AccentButton(
                    text = "Отмена",
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    filled = false,
                )
            }
        }
    }
}

@Composable
private fun DeviceCodeContent(
    userCode: String,
    uri: String,
    fullLink: String,
    canOpenPage: Boolean,
    qrLink: String?,
    status: String,
    onCheckNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    // ТВ: подтвердить код можно прямо на этом экране — страница Twitch открывается внутри приложения
    // (вход в аккаунт, если нужен, и «Разрешить»), телефон не нужен.
    var webLogin by rememberSaveable { mutableStateOf(false) }
    if (webLogin) {
        BackHandler { webLogin = false; onCheckNow() }
        TwitchWebPanel(
            channelLogin = "",
            startUrl = fullLink,
            title = "Вход в Twitch",
            // Навигатор по странице стрелками пульта нужен только на ТВ; на телефоне работает касание.
            remoteNavigator = qrLink != null,
            onClose = { webLogin = false; onCheckNow() },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    // Вернулись в приложение (например, из браузера) — сразу проверяем, не подтвердили ли вход.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onCheckNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Код для входа",
            style = MaterialTheme.typography.headlineSmall,
            color = NovaColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Откройте на телефоне или компьютере:",
            style = MaterialTheme.typography.bodyLarge,
            color = NovaColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = uri,
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (qrLink != null) {
            // ТВ: код слева, QR справа.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.displayMedium,
                    color = NovaColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(content = qrLink, size = 200.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Наведите камеру телефона",
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaColors.TextSecondary,
                    )
                }
            }
        } else {
            Text(
                text = userCode,
                style = MaterialTheme.typography.displaySmall,
                color = NovaColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "После подтверждения вернитесь в приложение (кнопка «Назад» или «×» в окне Twitch) — вход завершится сам, " +
                "в течение нескольких секунд. Twitch после подтверждения остаётся на своей странице и сам в приложение не возвращает: " +
                "токен приходит приложению напрямую.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (canOpenPage) {
            AccentButton(
                text = "Открыть страницу Twitch",
                onClick = {
                    try {
                        // Custom Tab открывается поверх приложения: после подтверждения достаточно нажать «Назад» или «×».
                        androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                            .launchUrl(context, Uri.parse(fullLink))
                    } catch (e: Exception) {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(fullLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e2: Exception) {
                            // Нет браузера — пользователь введёт адрес вручную.
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }
        run {
            // Вход внутри приложения (встроенное окно Twitch): браузер не нужен. На ТВ — без телефона.
            AccentButton(
                text = if (qrLink != null) "Войти на этом экране (без телефона)" else "Войти в приложении (без браузера)",
                onClick = { webLogin = true },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Вход в аккаунт на этой странице также включает чат с сайта (опросы, баллы) без отдельного входа.",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
        }
        AccentButton(text = "Проверить вход сейчас", onClick = onCheckNow)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton(
                text = "Копировать код",
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("code", userCode))
                },
            )
            AccentButton(
                text = "Отмена",
                onClick = onCancel,
                filled = false,
            )
        }
    }
}

/**
 * Ссылка для QR: страница подтверждения Twitch + код устройства параметром (если страница его не подхватит,
 * код вводится вручную — параметр ничему не мешает).
 */
private fun activationLink(verificationUri: String, userCode: String): String =
    Uri.parse(verificationUri).buildUpon().clearQuery().appendQueryParameter("device-code", userCode).build().toString()

/** Чистый адрес для показа: Twitch иногда присылает verification_uri уже с ?device-code=..., код показываем отдельно. */
private fun cleanActivationUri(verificationUri: String): String =
    Uri.parse(verificationUri).buildUpon().clearQuery().build().toString()
