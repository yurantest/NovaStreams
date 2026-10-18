package com.novastreams.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.BuildConfig
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.followersText
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.feature.auth.AuthViewModel
import com.novastreams.app.feature.player.ChannelPlayer
import com.novastreams.app.feature.settings.AccountSection
import com.novastreams.app.feature.settings.AdsSection
import com.novastreams.app.feature.settings.DefaultQualitySection
import com.novastreams.app.feature.settings.HiddenCategoriesSection
import com.novastreams.app.feature.settings.StreamSourceSection
import com.novastreams.app.feature.settings.TvClockSection
import com.novastreams.app.feature.settings.VideoSurfaceSection
import com.novastreams.app.feature.settings.VideoFillSection
import com.novastreams.app.feature.settings.WebChatNavSection
import com.novastreams.app.feature.settings.PlayerLogSection
import com.novastreams.app.feature.streams.PagedStreamsViewModel
import com.novastreams.app.feature.settings.DataSourceSection
import com.novastreams.app.feature.settings.RecommendationsSection
import com.novastreams.app.ui.components.ChatPanel
import com.novastreams.app.feature.channel.ChannelContent
import com.novastreams.app.feature.channel.ChannelViewModel
import com.novastreams.app.feature.player.PlayerPlaceholder
import com.novastreams.app.feature.settings.AccentEditor
import com.novastreams.app.feature.settings.SettingsViewModel
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.SectionTitle
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

private val Pad = 40.dp

/** Пробует выставить фокус; ошибка не критична — пользователь переместит его D-pad. */
@Composable
private fun rememberAutoFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            requester.requestFocus()
        } catch (e: Exception) {
        }
    }
    return requester
}

@Composable
private fun channelVm(channelId: String): ChannelViewModel {
    val container = LocalContext.current.appContainer()
    return viewModel(key = "tv_channel_$channelId", factory = vmFactory { ChannelViewModel(container, channelId) })
}

@Composable
fun TvChannelScreen(channelId: String, onBack: () -> Unit, onOpenPlayer: () -> Unit, onOpenStreamer: () -> Unit = {}) {
    val vm = channelVm(channelId)
    val state by vm.state.collectAsState()
    val favorite by vm.isFavorite.collectAsState()
    val messages by vm.chatMessages.collectAsState()
    val status by vm.chatStatus.collectAsState()
    val chatError by vm.chatError.collectAsState()
    val emotes by vm.emotes.collectAsState()
    // На ТВ чат стоит справа от плеера и сворачивается.
    var chatCollapsed by rememberSaveable { mutableStateOf(false) }
    // Чат справа от плеера виден — подключаем его (раньше на странице канала он оставался на «Подключение…»).
    // С задержкой: пока идёт анимация перехода с экрана плеера, не грузим чат (на слабой приставке это давало рывок).
    androidx.compose.runtime.LaunchedEffect(chatCollapsed, state) {
        if (!chatCollapsed && state is Load.Ready) {
            kotlinx.coroutines.delay(700)
            vm.setChatActive(true)
        }
    }
    val chatWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (chatCollapsed) 220.dp else 360.dp,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "chatWidth",
    )

    when (val s = state) {
        is Load.Loading -> LoadingState()
        is Load.Failed -> MessageState(
            title = "Не удалось открыть канал",
            message = s.message,
            actionLabel = "Назад",
            onAction = onBack,
        )
        is Load.Ready -> Row(Modifier.fillMaxSize()) {
            TvChannelBody(
                content = s.value,
                favorite = favorite,
                onToggleFavorite = vm::toggleFavorite,
                onOpenPlayer = onOpenPlayer,
                onOpenStreamer = onOpenStreamer,
                onBack = onBack,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ChatPanel(
                messages = messages,
                status = status,
                error = chatError,
                emotes = emotes,
                onSend = vm::sendChat,
                onErrorShown = vm::clearChatError,
                collapsed = chatCollapsed,
                onToggleCollapse = { chatCollapsed = !chatCollapsed },
                collapseAsIcon = true,
                collapseLabel = if (chatCollapsed) "Показать чат" else "Свернуть чат",
                modifier = Modifier.width(chatWidth).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TvChannelBody(
    content: ChannelContent,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenStreamer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = content.channel
    val stream = content.stream
    val focus = rememberAutoFocus()

    Column(
        modifier = modifier.padding(horizontal = Pad, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Небольшое окно плеера: полноэкранный режим открывается кнопкой «Смотреть».
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
        ) {
            PlayerPlaceholder(
                stream = stream,
                modifier = Modifier.fillMaxSize(),
                compact = true,
                title = if (stream != null) "Нажмите «Смотреть»" else "Канал сейчас не в эфире",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(channel.displayName, channel.artSeed, 56.dp, imageUrl = channel.avatarUrl)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NovaColors.TextPrimary,
                )
                // Число подписчиков Twitch часто не отдаёт — тогда строки просто нет.
                followersText(channel.followers)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextSecondary)
                }
            }
        }
        Text(
            text = stream?.title ?: "Сейчас не в эфире",
            style = MaterialTheme.typography.titleLarge,
            color = NovaColors.TextPrimary,
            maxLines = 2,
        )
        if (stream != null) {
            Text(
                text = stream.categoryName + " · " + formatViewers(stream.viewers) + " зрителей",
                style = MaterialTheme.typography.bodyLarge,
                color = NovaColors.TextSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton(text = "Смотреть", onClick = onOpenPlayer, modifier = Modifier.focusRequester(focus))
            AccentButton(
                text = if (favorite) "В избранном" else "В избранное",
                onClick = onToggleFavorite,
                filled = false,
            )
            AccentButton(text = "Записи и клипы", onClick = onOpenStreamer, filled = false)
            AccentButton(text = "Назад", onClick = onBack, filled = false)
        }
    }
}

/** Полноэкранный плеер на ТВ: управление D-pad, чат справа по кнопке. */
@Composable
fun TvPlayerScreen(channelId: String, onBack: () -> Unit) {
    val vm = channelVm(channelId)
    val state by vm.state.collectAsState()
    val messages by vm.chatMessages.collectAsState()
    val status by vm.chatStatus.collectAsState()
    val chatError by vm.chatError.collectAsState()
    val emotes by vm.emotes.collectAsState()
    val favorite by vm.isFavorite.collectAsState()
    val followingOnTwitch by vm.followingOnTwitch.collectAsState()

    // При открытии плеера сразу сверяем Follow, чтобы скрыть кнопку «Подписаться».
    androidx.compose.runtime.LaunchedEffect(channelId) {
        vm.refreshFollowingStatus()
    }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    val content = (state as? Load.Ready)?.value
    val chatRight by LocalContext.current.appContainer().settings.chatPanelRight
        .collectAsState(initial = true)
    val chatBackend by LocalContext.current.appContainer().settings.chatBackend
        .collectAsState(initial = "irc")
    // Веб-чат доступен и без входа в приложение: без входа на сайте он только для чтения.
    val useWebChat = chatBackend == "web"

    androidx.compose.runtime.LaunchedEffect(chatOpen, useWebChat) {
        // IRC-подключение только для нативного чата.
        if (chatOpen && !useWebChat) vm.setChatActive(true)
    }

    // IRC: Back закрывает панель здесь.
    // WebView: Back обрабатывает TwitchWebPanel (ввод → история → модалка → закрыть).
    BackHandler(enabled = chatOpen && !useWebChat) {
        chatOpen = false
    }

    // Веб-чат создаётся один раз при первом открытии и дальше живёт в фоне:
    // «Назад» / кнопка чата только сворачивают и разворачивают панель, страница не перезагружается.
    var webChatCreated by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(chatOpen, useWebChat) {
        if (chatOpen && useWebChat) webChatCreated = true
    }

    val sidePanel: @Composable () -> Unit = {
        if (useWebChat) {
            if (content != null && (webChatCreated || chatOpen)) {
                val panelWidth by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (chatOpen) 440.dp else 0.dp,
                    animationSpec = androidx.compose.animation.core.tween(180),
                    label = "webChatWidth",
                )
                Box(
                    Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    // Внутренняя ширина фиксирована, чтобы WebView не пересчитывал вёрстку при анимации.
                    com.novastreams.app.ui.components.TwitchWebPanel(
                        channelLogin = content.channel.login,
                        onClose = { chatOpen = false },
                        panelAlpha = 1f,
                        active = chatOpen,
                        modifier = Modifier
                            .align(if (chatRight) Alignment.CenterEnd else Alignment.CenterStart)
                            .requiredWidth(440.dp)
                            .fillMaxHeight()
                            .background(NovaColors.Background),
                    )
                }
            }
        } else androidx.compose.animation.AnimatedVisibility(
            visible = chatOpen,
            enter = androidx.compose.animation.expandHorizontally(
                animationSpec = androidx.compose.animation.core.tween(180),
                expandFrom = if (chatRight) Alignment.End else Alignment.Start,
            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.shrinkHorizontally(
                animationSpec = androidx.compose.animation.core.tween(150),
                shrinkTowards = if (chatRight) Alignment.End else Alignment.Start,
            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
        ) {
            ChatPanel(
                messages = messages,
                status = status,
                error = chatError,
                emotes = emotes,
                onSend = vm::sendChat,
                onErrorShown = vm::clearChatError,
                collapsed = false,
                onToggleCollapse = { chatOpen = false },
                collapseAsIcon = true,
                collapseLabel = "Закрыть чат",
                requestInitialFocus = chatOpen,
                modifier = Modifier.width(340.dp).fillMaxHeight(),
            )
        }
    }

    val playerBox: @Composable RowScope.() -> Unit = {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (content != null) {
                ChannelPlayer(
                    channelLogin = content.channel.login,
                    stream = content.stream,
                    isDemo = content.isDemo,
                    modifier = Modifier.fillMaxSize(),
                    channelId = content.channel.id,
                    onBack = onBack,
                    chatOpen = chatOpen,
                    onToggleChat = { chatOpen = !chatOpen },
                    onOpenPoll = null,
                    isFavorite = favorite,
                    onToggleFavorite = vm::toggleFavorite,
                    followingOnTwitch = followingOnTwitch,
                    onFollowStatusRefresh = vm::refreshFollowingStatus,
                )
            } else {
                LoadingState()
            }
        }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        if (chatRight) {
            playerBox()
            sidePanel()
        } else {
            sidePanel()
            playerBox()
        }
    }

    if (!chatOpen) {
        TvClockOverlay(isPlayerScreen = true)
    }
}

@Composable
fun TvCategoryScreen(categoryId: String, onBack: () -> Unit, onOpenChannel: (String) -> Unit) {
    val container = LocalContext.current.appContainer()
    // Эфиры категории подгружаются по мере прокрутки (бесконечный список).
    val vm: PagedStreamsViewModel = viewModel(
        key = "category_$categoryId",
        factory = vmFactory { PagedStreamsViewModel(container, categoryId) },
    )
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.CATEGORY) { force -> if (force) vm.refresh() else vm.refreshIfStale() }
    val title by vm.title.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = Pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AccentButton(text = "Назад", onClick = onBack, filled = false)
            Text(
                title ?: "Категория",
                style = MaterialTheme.typography.headlineLarge,
                color = NovaColors.TextPrimary,
            )
            val hiddenMap by container.hiddenCategories.collectAsState()
            val isHidden = categoryId in hiddenMap
            val hideScope = androidx.compose.runtime.rememberCoroutineScope()
            AccentButton(
                text = if (isHidden) "Показывать категорию" else "Скрыть категорию",
                onClick = {
                    hideScope.launch {
                        if (isHidden) container.settings.unhideCategory(categoryId)
                        else container.settings.hideCategory(categoryId, title ?: categoryId)
                    }
                },
                filled = false,
            )
        }
        Spacer(Modifier.height(16.dp))
        TvStreamsGrid(
            vm = vm,
            onOpenChannel = onOpenChannel,
            emptyTitle = "В этой категории нет эфиров",
            emptyMessage = "Загляните позже",
        )
    }
}

/** «Активные сейчас»: все эфиры на языке системы (по региону приложения), подгрузка при прокрутке. */
@Composable
fun TvActiveScreen(onOpenChannel: (String) -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: PagedStreamsViewModel = viewModel(
        key = "active_now",
        factory = vmFactory { PagedStreamsViewModel(container, null) },
    )
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.ACTIVE) { force -> if (force) vm.refresh() else vm.refreshIfStale() }
    val language by vm.language.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(
            text = "Активные сейчас",
            style = MaterialTheme.typography.headlineLarge,
            color = NovaColors.TextPrimary,
            modifier = Modifier.padding(horizontal = Pad),
        )
        Text(
            text = if (language != null) "Эфиры на языке: $language (по языку системы), по числу зрителей"
            else "Все языки (подбор по языку выключен или язык не поддерживается)",
            style = MaterialTheme.typography.bodyMedium,
            color = NovaColors.TextSecondary,
            modifier = Modifier.padding(horizontal = Pad),
        )
        Spacer(Modifier.height(16.dp))
        TvStreamsGrid(
            vm = vm,
            onOpenChannel = onOpenChannel,
            emptyTitle = "Сейчас нет эфиров",
            emptyMessage = "Загляните позже",
        )
    }
}

@Composable
fun TvSettingsScreen(onOpenChannel: (String) -> Unit, onLogin: () -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(container) })
    val authVm: AuthViewModel = viewModel(factory = vmFactory { AuthViewModel(container) })
    val auth by authVm.authState.collectAsState()
    val hasClientId by vm.hasOwnClientId.collectAsState()
    val favorites by vm.favoriteChannels.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Pad, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineLarge, color = NovaColors.TextPrimary)
        }
        item {
            TvSection {
                AccountSection(
                    auth = auth,
                    hasClientId = hasClientId,
                    onLogin = onLogin,
                    onLogout = authVm::logout,
                    // На ТВ вход идёт по коду, поэтому кнопка называется иначе.
                    loginLabel = "Войти по коду",
                )
            }
        }
        // Уведомления на ТВ не показываем: приставка обычно не в режиме ожидания, смысла нет.
        item { TvSection { AccentEditor(vm, okToEdit = true) } }
        item { TvSection { RecommendationsSection(vm) } }
        item { TvSection { AdsSection() } }
        item { TvSection { StreamSourceSection(vm) } }
        item { TvSection { DefaultQualitySection(vm) } }
        item { TvSection { TvClockSection(vm) } }
        item { TvSection { VideoSurfaceSection(vm) } }
        item { TvSection { VideoFillSection(vm) } }
        item { TvSection { WebChatNavSection(vm) } }
        item { TvSection { HiddenCategoriesSection(vm) } }
        item { TvSection { DataSourceSection(vm, okToEdit = true) } }
        item { TvSection { PlayerLogSection(vm) } }
        item {
            Column {
                SectionTitle("Избранные каналы")
                Spacer(Modifier.height(10.dp))
                if (favorites.isEmpty()) {
                    Text(
                        "Пока пусто. Добавляйте каналы в избранное на странице канала.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaColors.TextSecondary,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(favorites, key = { it.id }) { channel ->
                            TvChannelTile(channel, onClick = { onOpenChannel(channel.id) }, isFavorite = true)
                        }
                    }
                }
            }
        }
        item {
            Column {
                SectionTitle("О приложении")
                Spacer(Modifier.height(6.dp))
                Text(
                    "NovaStreams ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NovaColors.TextPrimary,
                )
                Text(
                    "Неофициальный клиент Twitch. Не связан с Twitch Interactive, Inc.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                )
            }
        }
    }
}

/** Карточка-раздел настроек на ТВ. */
@Composable
private fun TvSection(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(NovaColors.Card)
            .padding(20.dp)
    ) { content() }
}
