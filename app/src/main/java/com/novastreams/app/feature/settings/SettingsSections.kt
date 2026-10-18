package com.novastreams.app.feature.settings

import com.novastreams.app.appContainer

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novastreams.app.BuildConfig
import com.novastreams.app.core.stream.DefaultPlaybackQuality
import com.novastreams.app.core.stream.StreamSourceMode
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.seedOf
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.NovaTextField
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.theme.NovaColors

/** Аккаунт: кнопка входа или аватарка, ник и кнопка выхода. */
@Composable
fun AccountSection(
    auth: AuthState,
    hasClientId: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    loginLabel: String = "Войти через Twitch",
) {
    Column(modifier = modifier) {
        when (auth) {
            is AuthState.NotSignedIn -> {
                Text(
                    text = "Вход через Twitch не выполнен",
                    style = MaterialTheme.typography.titleMedium,
                    color = NovaColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (hasClientId) {
                        "Вход нужен для подписок, чата и уведомлений."
                    } else {
                        "Нужен Client ID для входа (блок «Источник данных» ниже)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                AccentButton(text = loginLabel, onClick = onLogin)
            }

            is AuthState.SignedIn -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        name = auth.user.displayName,
                        seed = seedOf(auth.user.id),
                        size = 56.dp,
                        imageUrl = auth.user.profileImageUrl.takeIf { it.isNotBlank() },
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = auth.user.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = NovaColors.TextPrimary,
                        )
                        Text(
                            text = "@" + auth.user.login,
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaColors.TextSecondary,
                        )
                    }
                    AccentButton(text = "Выйти", onClick = onLogout, filled = false)
                }
            }
        }
    }
}

/** «Рекомендации»: подбор списка эфиров под язык системы. */
@Composable
fun RecommendationsSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val enabled by vm.languageRecommendations.collectAsState()
    val lang = vm.systemLanguage
    Column(modifier = modifier) {
        Text(
            text = "Рекомендации",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = (if (enabled) "[✓] " else "[  ] ") + "Подстраивать рекомендации под язык системы",
            onClick = { vm.setLanguageRecommendations(!enabled) },
            filled = false,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Текущий язык: " + (lang ?: "не поддерживается, показываются все эфиры"),
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }
}



/** Список скрытых категорий с кнопкой «Вернуть». Скрыть категорию можно на её странице. */
@Composable
fun HiddenCategoriesSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val hidden by vm.hiddenCategories.collectAsState()
    Column(modifier = modifier) {
        Text(
            text = "Скрытые категории",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Эфиры этих категорий не показываются на главной и в «Активных сейчас». Скрыть категорию можно кнопкой на её странице. Подписки не скрываются.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        if (hidden.isEmpty()) {
            Text(
                text = "Скрытых категорий нет",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextSecondary,
            )
        } else {
            hidden.forEach { (id, name) ->
                AccentButton(
                    text = "Вернуть: $name",
                    onClick = { vm.unhideCategory(id) },
                    filled = false,
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** Тип поверхности видео своего плеера: TextureView (по умолчанию) или SurfaceView. */
@Composable
fun VideoSurfaceSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val enabled by vm.useSurfaceView.collectAsState()
    Column(modifier = modifier) {
        Text(
            text = "Поверхность видео",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = (if (enabled) "[✓] " else "[  ] ") + "SurfaceView (легче для слабых приставок)",
            onClick = { vm.setUseSurfaceView(!enabled) },
            filled = false,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Применяется при следующем открытии плеера. Если после включения кнопки и журнал оказались под видео — выключите.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }
}

/** Источник потока и качество своего плеера. */
/**
 * Пояснение: отдельной «официальной HLS-ссылки» для стороннего ExoPlayer у Twitch нет.
 * Официально — только их embed/сайт; в приложении всегда свой плеер + неофициальный токен.
 */
@Composable
fun VideoFillSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val mode by vm.videoResizeMode.collectAsState()
    Column(modifier = modifier) {
        Text(
            text = "Масштаб видео",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "«Кадр» — весь кадр, по бокам возможны чёрные поля. «Обрезка» — заполняет экран, края кадра обрезаются. «Растянуть» — без полей и без обрезки, картинка немного искажается.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = when (mode) {
                1 -> "Сейчас: обрезка краёв (ZOOM)"
                2 -> "Сейчас: растянуть (FILL)"
                else -> "Сейчас: весь кадр (FIT)"
            },
            filled = mode != 0,
            onClick = { vm.setVideoResizeMode((mode + 1) % 3) },
        )
    }
}

@Composable
fun WebChatNavSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val chatRight by vm.chatPanelRight.collectAsState()
    val backend by vm.chatBackend.collectAsState()
    Column(modifier = modifier) {
        Text(
            text = "Чат",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Одна кнопка «Чат» в плеере. Нативный (IRC) — быстрее. WebView — чат с сайта: опросы и баллы. Без входа в Twitch он показывает сообщения только для чтения; чтобы писать и голосовать, войдите в Twitch (на ТВ — кнопкой «Войти на этом экране» при входе).",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = if (backend == "web") "Тип: WebView (сайт)" else "Тип: нативный (IRC)",
            filled = true,
            onClick = {
                vm.setChatBackend(if (backend == "web") "irc" else "web")
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Сторона панели (TV)",
            style = MaterialTheme.typography.titleSmall,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        AccentButton(
            text = if (chatRight) "Сторона: справа" else "Сторона: слева",
            filled = false,
            onClick = { vm.setChatPanelRight(!chatRight) },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Управление WebView на TV",
            style = MaterialTheme.typography.titleSmall,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Используются только кнопки пульта: стрелки перемещают фокус по странице, OK выбирает элемент, Назад закрывает клавиатуру или возвращается из окна чата. Круговой курсор отключён.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }
}

@Composable
fun StreamSourceSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = "Источник потока",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Видео всегда идёт через ваш плеер (ExoPlayer). Ссылку на HLS Twitch " +
                "в открытом Helix API не отдаёт — её получают так же, как другие сторонние клиенты " +
                "(GraphQL-токен + usher). «Официальный» вариант — только встроенный сайт/embed Twitch, " +
                "без своей ссылки для ExoPlayer; на Android TV такой embed обычно неудобен или не работает. " +
                "Переключить на «официальную ссылку» и оставить тот же плеер технически нельзя.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }
}

@Composable
fun DefaultQualitySection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val quality by vm.defaultPlaybackQuality.collectAsState()
    Column(modifier = modifier) {
        Text(
            text = "Качество по умолчанию",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Для своего плеера (ExoPlayer). «Авто» берёт максимальное и может долго буферизоваться на слабом интернете. «До 720p» обычно самый стабильный вариант.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultPlaybackQuality.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { q ->
                        AccentButton(
                            text = q.title,
                            onClick = { vm.setDefaultPlaybackQuality(q) },
                            filled = quality == q,
                        )
                    }
                }
            }
        }
    }
}

/** «Реклама»: честное пояснение, без обещаний блокировки. */
@Composable
fun AdsSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Реклама",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Приложение не блокирует рекламу в видео: если Twitch вставляет её в трансляцию, " +
                "вы её увидите в плеере. В режиме «Свой плеер» ссылка на поток неофициальная (можно переключить на официальный embed в «Источник потока»). Модуль фильтрации скрывает только элементы списков " +
                "в самом приложении, правил по умолчанию в нём нет.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }
}

/** Client ID у Twitch — строка из строчных латинских букв и цифр; проверяем только общий вид. */
private fun looksLikeClientId(value: String): Boolean =
    value.isEmpty() || Regex("^[a-z0-9]{20,40}$").matches(value.trim())

/** «Источник данных»: свой Client ID для входа и публичный Client ID для чтения без входа. */
@Composable
fun DataSourceSection(vm: SettingsViewModel, modifier: Modifier = Modifier, okToEdit: Boolean = false) {
    val own by vm.ownClientId.collectAsState()
    val public by vm.publicClientId.collectAsState()
    var ownDraft by remember(own) { mutableStateOf(own) }
    var publicDraft by remember(public) { mutableStateOf(public) }
    val ownValid = looksLikeClientId(ownDraft)
    val publicValid = publicDraft.isNotBlank() && looksLikeClientId(publicDraft)

    Column(modifier = modifier) {
        Text(
            text = "Источник данных",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))

        // --- Свой Client ID: нужен для входа ---
        NovaTextField(
            value = ownDraft,
            onValueChange = { ownDraft = it.trim() },
            label = "Свой Client ID (необязательно)",
            isError = !ownValid,
            okToEdit = okToEdit,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (ownValid) {
                "Оставьте поле пустым — используется встроенный Client ID приложения. Если он перестанет работать, " +
                    "создайте своё приложение на dev.twitch.tv/console (тип Public, OAuth Redirect URL — https://localhost, " +
                    "в приложении он не используется) и вставьте сюда его Client ID: он будет иметь приоритет. Вход идёт по коду."
            } else {
                "Похоже на неверный Client ID: он состоит из латинских букв и цифр."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (ownValid) NovaColors.TextSecondary else NovaColors.Live,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = "Сохранить",
            onClick = { if (ownValid) vm.saveOwnClientId(ownDraft) },
        )

        Spacer(Modifier.height(18.dp))

        // --- Публичный Client ID: чтение без входа ---
        NovaTextField(
            value = publicDraft,
            onValueChange = { publicDraft = it.trim() },
            label = "Публичный Client ID (чтение без входа)",
            isError = publicDraft.isNotBlank() && !publicValid,
            okToEdit = okToEdit,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Неофициальный способ: ID сайта twitch.tv. Если трансляции без входа перестанут грузиться, " +
                "замените его здесь. Для входа и подписок он не используется.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton(
                text = "Сохранить",
                onClick = { if (publicValid) vm.savePublicClientId(publicDraft) },
            )
            AccentButton(text = "По умолчанию", onClick = vm::resetPublicClientId, filled = false)
        }
    }
}

/** Уведомления «подписка вышла в эфир». Нужен вход; на Android 13+ запрашивается разрешение. */
@Composable
fun NotificationsSection(vm: SettingsViewModel, signedIn: Boolean, modifier: Modifier = Modifier) {
    val enabled by vm.notificationsEnabled.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setNotificationsEnabled(true)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Уведомления",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (signedIn) {
                "Сообщим, когда кто-то из ваших подписок выйдет в эфир. Проверка идёт примерно раз в 15 минут, " +
                    "поэтому уведомление приходит с задержкой."
            } else {
                "Войдите через Twitch, чтобы получать уведомления о подписках."
            },
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        if (signedIn) {
            Spacer(Modifier.height(10.dp))
            AccentButton(
                text = if (enabled) "Выключить уведомления" else "Включить уведомления",
                filled = !enabled,
                onClick = {
                    when {
                        enabled -> vm.setNotificationsEnabled(false)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> vm.setNotificationsEnabled(true)
                    }
                },
            )
        }
    }
}

@Composable
fun PlayerLogSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled by vm.playerDiagnostics.collectAsState()
    val lines by com.novastreams.app.feature.player.PlayerLog.lines.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }

    fun save() {
        status = com.novastreams.app.feature.player.PlayerLogExport.saveAll(context)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else status = "Без разрешения на запись файл сохранить нельзя"
    }

    Column(modifier = modifier) {
        Text(
            text = "Отладка плеера",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Журнал буфера, сегментов и ошибок. Без пересборки: включили — смотрите эфир — сохранили файл.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = if (enabled) "Отладка: включена" else "Отладка: выключена",
            filled = enabled,
            onClick = { vm.setPlayerDiagnostics(!enabled) },
        )
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            AccentButton(
                text = "Сохранить журнал в файл",
                onClick = {
                    if (com.novastreams.app.feature.player.PlayerLogExport.needsLegacyPermission()) {
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        save()
                    }
                },
            )
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Последний просмотр:",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
            )
            Text(
                text = if (lines.isEmpty()) "Пока пусто: откройте эфир с включённой отладкой."
                else lines.takeLast(40).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
            )
        }
    }
}


/** Часы на Android TV: показывать ли, с какой стороны и какого размера. */
@Composable
fun TvClockSection(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val enabled by vm.tvClockEnabled.collectAsState()
    val right by vm.tvClockRight.collectAsState()
    val size by vm.tvClockSize.collectAsState()
    val sizeNames = listOf("маленькие", "средние", "большие", "очень большие")
    Column(modifier = modifier) {
        Text(
            text = "Часы на экране",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = (if (enabled) "[✓] " else "[  ] ") + "Показывать часы",
            onClick = { vm.setTvClockEnabled(!enabled) },
            filled = false,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = "Сторона: " + if (right) "справа" else "слева",
            onClick = { vm.setTvClockRight(!right) },
            filled = false,
        )
        Spacer(Modifier.height(8.dp))
        AccentButton(
            text = "Размер: " + sizeNames[size.coerceIn(0, 3)],
            onClick = { vm.setTvClockSize((size + 1) % 4) },
            filled = false,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Часы стоят в верхнем углу поверх всего приложения, в том числе поверх видео. Нажатие на кнопку размера переключает по кругу.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }
}
