package com.novastreams.app.ui.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.followersText
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.feature.channel.ChannelContent
import com.novastreams.app.feature.channel.ChannelViewModel
import com.novastreams.app.feature.player.ChannelPlayer
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.components.ChatPanel
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

@Composable
private fun channelVm(channelId: String): ChannelViewModel {
    val container = LocalContext.current.appContainer()
    return viewModel(key = "channel_$channelId", factory = vmFactory { ChannelViewModel(container, channelId) })
}

@Composable
internal fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .novaClickable(CircleShape, onClick = onBack)
            .clip(CircleShape)
            .background(Color(0x99101014)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад", tint = NovaColors.TextPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileChannelScreen(channelId: String, onBack: () -> Unit, onOpenStreamer: () -> Unit = {}) {
    val vm = channelVm(channelId)
    val state by vm.state.collectAsState()
    val favorite by vm.isFavorite.collectAsState()
    val followingOnTwitch by vm.followingOnTwitch.collectAsState()
    val messages by vm.chatMessages.collectAsState()
    val status by vm.chatStatus.collectAsState()
    val chatError by vm.chatError.collectAsState()
    val emotes by vm.emotes.collectAsState()
    // Чат под плеером можно свернуть, чтобы освободить место.
    var chatCollapsed by rememberSaveable { mutableStateOf(false) }
    // Плеер на весь экран: разворачивается в альбомную ориентацию, чат включается кнопкой поверх видео.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Телефон повернули в альбомную ориентацию — экран ведёт себя как полноэкранный: иначе плеер 16:9
    // от ширины выше экрана, и кнопки управления уезжают за край.
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val fs = fullscreen || landscape
    var overlayChat by rememberSaveable { mutableStateOf(false) }
        val chatBackend by LocalContext.current.appContainer().settings.chatBackend
        .collectAsState(initial = "irc")
    // Веб-чат доступен и без входа в приложение: без входа на сайте он только для чтения.
    val useWebChat = chatBackend == "web"

    val channelReady = state is Load.Ready
    androidx.compose.runtime.LaunchedEffect(chatCollapsed, overlayChat, fs, channelReady, useWebChat) {
        val chatVisible = overlayChat || (!fs && !chatCollapsed)
        if (chatVisible && !useWebChat) vm.setChatActive(true)
    }

    FullscreenEffect(fullscreen)
    // WebView-чат сам обрабатывает Back (история/модалки); IRC-оверлей закрываем здесь.
    BackHandler(enabled = fs && !(overlayChat && useWebChat)) {
        if (overlayChat) overlayChat = false else if (fullscreen) fullscreen = false else onBack()
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is Load.Loading -> LoadingState()
            is Load.Failed -> MessageState(
                title = "Не удалось открыть канал",
                message = s.message,
                actionLabel = "Повторить",
                onAction = vm::load,
            )
            is Load.Ready -> {
                val content = s.value
                val channel = content.channel
                val stream = content.stream
                // imePadding: при открытой клавиатуре колонка сжимается, а не перекрывается.
                // Когда клавиатура видна в портрете — прячем плеер и инфо, чату отдаём весь экран.
                val imeVisible = WindowInsets.isImeVisible
                Column(Modifier.fillMaxSize().imePadding()) {
                    // Плеер: в fullscreen — всегда; в обычном режиме — только пока нет клавиатуры.
                    if (fs || !imeVisible) {
                    Box(
                        modifier = if (fs) Modifier.weight(1f).fillMaxWidth()
                        else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    ) {
                        ChannelPlayer(
                            channelLogin = channel.login,
                            stream = stream,
                            isDemo = content.isDemo,
                            compact = !fs,
                            channelId = channel.id,
                            modifier = Modifier.fillMaxSize(),
                            chatOpen = overlayChat,
                            isFullscreen = fs,
                            onFullscreen = { fullscreen = !fullscreen },
                            // Кнопки «Чат» и «Назад» нужны только в полноэкранном режиме.
                            onToggleChat = if (fs) ({ overlayChat = !overlayChat }) else null,
                            onOpenPoll = null,
                            onBack = if (fs) ({ if (fullscreen) fullscreen = false else onBack() }) else null,
                            // Сердечко (локальное избранное) — в обоих режимах плеера.
                            isFavorite = favorite,
                            onToggleFavorite = vm::toggleFavorite,
                        followingOnTwitch = followingOnTwitch,

                        onFollowStatusRefresh = vm::refreshFollowingStatus,
                        )
                        // Чат поверх видео справа: свайп вправо или полупрозрачная стрелка с края.
                        if (fs && overlayChat) {
                            PlayerChatOverlay(
                                onClose = { overlayChat = false },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .imePadding(),
                            ) {
                                if (useWebChat) {
                                    com.novastreams.app.ui.components.TwitchWebPanel(
                                        channelLogin = channel.login,
                                        onClose = { overlayChat = false },
                                        panelAlpha = 1f,
                                        showCloseButton = false,
                                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                                    )
                                } else {
                                    ChatPanel(
                                        messages = messages,
                                        status = status,
                                        error = chatError,
                                        emotes = emotes,
                                        onSend = vm::sendChat,
                                        onErrorShown = vm::clearChatError,
                                        // Закрытие — стрелка сбоку / свайп (PlayerChatOverlay).
                                        onToggleCollapse = null,
                                        modifier = Modifier
                                            .width(300.dp)
                                            .fillMaxHeight()
                                            .alpha(0.92f),
                                    )
                                }
                            }
                        }
                    }
                    } // плеер скрыт, пока открыта клавиатура (портрет)
                    if (!fs) {
                        // Описание канала не нужно при наборе сообщения.
                        if (!imeVisible) {
                            ChannelInfo(content, favorite, followingOnTwitch, vm::toggleFavorite, onOpenStreamer, onResumeFollowCheck = vm::refreshFollowingStatus)
                        }
                        // Чат под плеером: IRC или WebView по настройке.
                        if (useWebChat) {
                            if (!chatCollapsed) {
                                com.novastreams.app.ui.components.TwitchWebPanel(
                                    channelLogin = channel.login,
                                    onClose = { chatCollapsed = true },
                                    panelAlpha = 1f,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            } else {
                                AccentButton(
                                    text = "Показать чат (Web)",
                                    onClick = { chatCollapsed = false },
                                    filled = false,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                )
                            }
                        } else {
                            ChatPanel(
                                messages = messages,
                                status = status,
                                error = chatError,
                                emotes = emotes,
                                onSend = vm::sendChat,
                                onErrorShown = vm::clearChatError,
                                modifier = if (chatCollapsed) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(),
                                collapsed = chatCollapsed,
                                onToggleCollapse = { chatCollapsed = !chatCollapsed },
                                collapseAsIcon = true,
                                collapseLabel = if (chatCollapsed) "Показать чат" else "Свернуть чат",
                            )
                        }
                    }
                }
            }
        }
        if (!fs) BackButton(onBack, Modifier.padding(12.dp))
    }
}

/**
 * Полноэкранный режим: альбомная ориентация и скрытые системные панели.
 * При выходе (или закрытии экрана) всё возвращается как было.
 */


/**
 * Оверлей чата в полноэкранном плеере: свайп вправо закрывает,
 * слева — полупрозрачная «стрелка» для тех, кому свайп неудобен.
 */
@Composable
private fun PlayerChatOverlay(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (total > 72f) onClose()
                        total = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        // Только жест «увести чат вправо» (как смахивание панели).
                        if (dragAmount > 0) total += dragAmount else total = 0f
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Узкая полоска-ручка у левого края панели.
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = "Закрыть чат",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
        }
        content()
    }
}

@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Название канала, строка об эфире и кнопка избранного. */
@Composable
private fun ChannelInfo(
    content: ChannelContent,
    favorite: Boolean,
    followingOnTwitch: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenStreamer: () -> Unit,
    onResumeFollowCheck: () -> Unit = {},
) {
    val channel = content.channel
    val stream = content.stream
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(channel.displayName, channel.artSeed, 44.dp, imageUrl = channel.avatarUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = NovaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        stream != null ->
                            stream.title + " · " + stream.categoryName + " · " + formatViewers(stream.viewers)
                        else -> followersText(channel.followers) ?: "Сейчас не в эфире"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton(
                text = if (favorite) "В избранном" else "В избранное",
                onClick = onToggleFavorite,
                filled = !favorite,
            )
            AccentButton(text = "Записи и клипы", onClick = onOpenStreamer, filled = false)
        }
        Spacer(Modifier.height(8.dp))
        // После возврата из браузера (Follow на сайте) обновляем статус подписки.
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(channel.id, lifecycleOwner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onResumeFollowCheck()
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        com.novastreams.app.ui.components.FollowOnSiteButton(
            login = channel.login,
            followingOnTwitch = followingOnTwitch,
        )
    }
}

@Composable
fun MobilePlayerScreen(channelId: String, onBack: () -> Unit) {
    val vm = channelVm(channelId)
    val state by vm.state.collectAsState()
    val favorite by vm.isFavorite.collectAsState()
    val followingOnTwitch by vm.followingOnTwitch.collectAsState()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val fs = fullscreen || landscape
    FullscreenEffect(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }
    // При открытии сразу сверяем Follow, чтобы скрыть кнопку «Подписаться».
    androidx.compose.runtime.LaunchedEffect(channelId) {
        vm.refreshFollowingStatus()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val content = (state as? Load.Ready)?.value
        if (content != null) {
            ChannelPlayer(
                channelLogin = content.channel.login,
                stream = content.stream,
                isDemo = content.isDemo,
                channelId = content.channel.id,
                modifier = Modifier.fillMaxSize(),
                compact = !fs,
                isFullscreen = fs,
                onFullscreen = { fullscreen = !fullscreen },
                onBack = if (fs) ({ if (fullscreen) fullscreen = false else onBack() }) else null,
                isFavorite = favorite,
                onToggleFavorite = vm::toggleFavorite,
                followingOnTwitch = followingOnTwitch,
                onFollowStatusRefresh = vm::refreshFollowingStatus,
            )
        } else {
            LoadingState()
        }
        if (!fs) BackButton(onBack, Modifier.padding(12.dp))
    }
}

@Composable
fun MobileCategoryScreen(categoryId: String, onBack: () -> Unit, onOpenChannel: (String) -> Unit) {
    val container = LocalContext.current.appContainer()
    // Эфиры категории подгружаются по мере прокрутки (бесконечный список).
    val vm: com.novastreams.app.feature.streams.PagedStreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "category_$categoryId",
        factory = com.novastreams.app.vmFactory { com.novastreams.app.feature.streams.PagedStreamsViewModel(container, categoryId) },
    )
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.CATEGORY) { force -> if (force) vm.refresh() else vm.refreshIfStale() }
    val streams by vm.streams.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val title by vm.title.collectAsState()
    val note by vm.note.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val width = LocalConfiguration.current.screenWidthDp.dp - 32.dp

    // Когда до конца списка остаётся меньше пяти карточек — просим следующую порцию.
    // loading и error в ключах: проверка повторяется после каждой загрузки, даже если новых карточек не прибавилось.
    LaunchedEffect(listState, streams.size, loading, error) {
        if (error != null) {
            kotlinx.coroutines.delay(3_000)
            vm.loadMore()
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (streams.isNotEmpty() && last >= streams.size - 5) vm.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                text = title ?: "Категория",
                style = MaterialTheme.typography.headlineMedium,
                color = NovaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val hiddenMap by container.hiddenCategories.collectAsState()
            val isHidden = categoryId in hiddenMap
            val hideScope = androidx.compose.runtime.rememberCoroutineScope()
            AccentButton(
                text = if (isHidden) "Показывать" else "Скрыть",
                onClick = {
                    hideScope.launch {
                        if (isHidden) container.settings.unhideCategory(categoryId)
                        else container.settings.hideCategory(categoryId, title ?: categoryId)
                    }
                },
                filled = false,
            )
        }
        when {
            streams.isEmpty() && loading -> LoadingState()
            streams.isEmpty() && error != null -> MessageState("Ошибка", error ?: "", actionLabel = "Повторить", onAction = vm::loadMore)
            streams.isEmpty() -> MessageState("В этой категории нет эфиров", note ?: "Загляните позже")
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(streams, key = { it.id }) { stream ->
                    StreamCard(stream = stream, width = width, onClick = { onOpenChannel(stream.channel.id) })
                }
                if (loading) {
                    item { Text("Загружаем ещё…", color = NovaColors.TextSecondary) }
                }
            }
        }
    }
}
