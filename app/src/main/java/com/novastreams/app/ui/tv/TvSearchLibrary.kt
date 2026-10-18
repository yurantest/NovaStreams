package com.novastreams.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.followersText
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.feature.library.LibraryChannel
import com.novastreams.app.feature.library.LibraryTab
import com.novastreams.app.feature.library.LibraryViewModel
import com.novastreams.app.feature.streams.PagedStreamsViewModel
import com.novastreams.app.ui.components.LibraryTabBar
import com.novastreams.app.feature.search.SearchViewModel
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.components.CategoryCard
import com.novastreams.app.ui.components.HintBanner
import com.novastreams.app.ui.components.LiveBadge
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.SectionTitle
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

private val Pad = 40.dp

@Composable
fun TvSearchScreen(
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: SearchViewModel = viewModel(factory = vmFactory { SearchViewModel(container) })
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val history by vm.history.collectAsState()

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(
            text = "Поиск",
            style = MaterialTheme.typography.headlineLarge,
            color = NovaColors.TextPrimary,
            modifier = Modifier.padding(horizontal = Pad),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.padding(horizontal = Pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Каналы, категории, название эфира…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            AccentButton(text = "Искать", onClick = { vm.submit() })
        }
        Spacer(Modifier.height(18.dp))

        when (val r = results) {
            null -> {
                if (history.isEmpty()) {
                    MessageState("История поиска пуста", "Введите запрос, чтобы найти канал или категорию")
                } else {
                    Column {
                        SectionTitle("Недавние запросы", Modifier.padding(horizontal = Pad))
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Pad, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(history, key = { it }) { item ->
                                AccentButton(text = item, onClick = { vm.submit(item) }, filled = false)
                            }
                            item {
                                AccentButton(text = "Очистить", onClick = vm::clearHistory, filled = false)
                            }
                        }
                    }
                }
            }
            is Load.Loading -> LoadingState()
            is Load.Failed -> MessageState(
                title = "Ошибка поиска",
                message = r.message,
                actionLabel = "Повторить",
                onAction = { vm.submit() },
            )
            is Load.Ready -> {
                if (r.value.isEmpty) {
                    MessageState("Ничего не найдено", "Попробуйте другое имя канала, игру или слова из названия эфира")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (r.value.streams.isNotEmpty()) {
                            item {
                                Column {
                                    SectionTitle("В эфире", Modifier.padding(horizontal = Pad))
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = Pad, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        items(r.value.streams, key = { "live_" + it.channel.id }) { stream ->
                                            StreamCard(
                                                stream = stream,
                                                width = 260.dp,
                                                onClick = { onOpenChannel(stream.channel.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (r.value.channels.isNotEmpty()) {
                            item {
                                Column {
                                    SectionTitle("Каналы", Modifier.padding(horizontal = Pad))
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = Pad, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        items(r.value.channels, key = { it.id }) { channel ->
                                            TvChannelTile(channel, onClick = { onOpenChannel(channel.id) })
                                        }
                                    }
                                }
                            }
                        }
                        if (r.value.categories.isNotEmpty()) {
                            item {
                                Column {
                                    SectionTitle("Категории", Modifier.padding(horizontal = Pad))
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = Pad, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        items(r.value.categories, key = { it.id }) { category ->
                                            CategoryCard(category, width = 150.dp, onClick = { onOpenCategory(category.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvChannelTile(
    channel: Channel,
    onClick: () -> Unit,
    isFavorite: Boolean = false,
    isLive: Boolean = false,
) {
    val shape = RoundedCornerShape(18.dp)
    val accent = LocalNovaColors.current.accent
    Column(
        modifier = Modifier
            .width(250.dp)
            .novaClickable(shape, onClick = onClick)
            .clip(shape)
            .background(NovaColors.Card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(channel.displayName, channel.artSeed, 52.dp, imageUrl = channel.avatarUrl)
            Spacer(Modifier.weight(1f))
            if (isLive) LiveBadge()
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = NovaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = followersText(channel.followers).orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaColors.TextSecondary,
        )
        if (isFavorite) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "В избранном",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
        }
    }
}

@Composable
fun TvLibraryScreen(onOpenChannel: (String) -> Unit, onOpenLive: (String) -> Unit = onOpenChannel) {
    val container = LocalContext.current.appContainer()
    val vm: LibraryViewModel = viewModel(factory = vmFactory { LibraryViewModel(container) })
    // Вкладка «Общие»: каналы в эфире на языке приложения, подгрузка при прокрутке.
    val common: PagedStreamsViewModel = viewModel(
        key = "channels_common",
        factory = vmFactory { PagedStreamsViewModel(container, null) },
    )
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.LIBRARY) { force ->
        if (force) { vm.refresh(); common.refresh() } else { vm.refreshIfStale(); common.refreshIfStale() }
    }
    val tab by vm.tab.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val followed by vm.state.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val streams by common.streams.collectAsState()
    val commonLoading by common.loading.collectAsState()
    val commonEnd by common.endReached.collectAsState()
    val commonError by common.error.collectAsState()
    val language by common.language.collectAsState()

    // Вкладка «Подписки» есть только после входа; если вышли — возвращаемся на «Общие».
    val tabs = if (signedIn) LibraryTab.values().toList() else listOf(LibraryTab.COMMON, LibraryTab.FAVORITES)
    val shown = if (tab in tabs) tab else LibraryTab.COMMON

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(
            text = "Каналы",
            style = MaterialTheme.typography.headlineLarge,
            color = NovaColors.TextPrimary,
            modifier = Modifier.padding(horizontal = Pad),
        )
        Spacer(Modifier.height(14.dp))
        LibraryTabBar(tabs, shown, vm::selectTab, Modifier.padding(horizontal = Pad))
        Spacer(Modifier.height(12.dp))
        when (shown) {
            LibraryTab.COMMON -> {
                Text(
                    text = if (language != null) "Каналы в эфире на языке: $language (по языку приложения)"
                    else "Каналы в эфире, все языки",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = Pad),
                )
                Spacer(Modifier.height(8.dp))
                val items = streams
                    .distinctBy { it.channel.id }
                    .map { LibraryChannel(it.channel, isLive = true, viewers = it.viewers, isFavorite = it.channel.id in favoriteIds) }
                when {
                    items.isEmpty() && commonError != null -> MessageState(
                        title = "Не удалось загрузить",
                        message = commonError.orEmpty(),
                        actionLabel = "Повторить",
                        onAction = common::loadMore,
                    )
                    items.isEmpty() && commonLoading -> LoadingState()
                    items.isEmpty() -> MessageState("Сейчас нет каналов в эфире", "Загляните позже")
                    else -> TvChannelsGrid(
                        items = items,
                        loadingMore = commonLoading && !commonEnd,
                        onLoadMore = common::loadMore,
                        onOpenChannel = onOpenChannel,
                        onOpenLive = onOpenLive,
                        error = commonError,
                    )
                }
            }
            LibraryTab.FOLLOWED -> when (val s = followed) {
                is Load.Loading -> LoadingState()
                is Load.Failed -> MessageState(
                    title = "Не удалось загрузить",
                    message = s.message,
                    actionLabel = "Повторить",
                    onAction = vm::load,
                )
                is Load.Ready -> if (s.value.isEmpty()) {
                    MessageState("Подписок пока нет", "Подпишитесь на каналы на Twitch — они появятся здесь")
                } else {
                    TvChannelsGrid(s.value, false, {}, onOpenChannel, onOpenLive)
                }
            }
            LibraryTab.FAVORITES -> when (val s = favorites) {
                is Load.Loading -> LoadingState()
                is Load.Failed -> MessageState(
                    title = "Не удалось загрузить",
                    message = s.message,
                    actionLabel = "Повторить",
                    onAction = vm::reloadFavorites,
                )
                is Load.Ready -> if (s.value.isEmpty()) {
                    MessageState(
                        "Избранное пусто",
                        "Нажмите сердечко в плеере или «В избранное» на странице канала. Список хранится только на этом устройстве",
                    )
                } else {
                    TvChannelsGrid(s.value, false, {}, onOpenChannel, onOpenLive)
                }
            }
        }
    }
}

/** Сетка каналов; при прокрутке к концу просит следующую порцию ([onLoadMore]). */
@Composable
private fun TvChannelsGrid(
    items: List<LibraryChannel>,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenLive: (String) -> Unit,
    error: String? = null,
) {
    val gridState = rememberLazyGridState()
    // Как только виден один из последних элементов — подгружаем ещё. loadingMore и error в ключах: проверка
    // повторяется после каждой загрузки, даже если новых каналов не прибавилось (иначе у конца списка «застревает»).
    LaunchedEffect(gridState, items.size, loadingMore, error) {
        if (error != null) {
            kotlinx.coroutines.delay(3_000)
            onLoadMore()
            return@LaunchedEffect
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= items.size - 6) onLoadMore() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(250.dp),
        contentPadding = PaddingValues(start = Pad, end = Pad, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.channel.id }) { entry: LibraryChannel ->
            TvChannelTile(
                channel = entry.channel,
                isFavorite = entry.isFavorite,
                isLive = entry.isLive,
                // Эфир на ТВ открывается сразу в полноэкранном плеере, офлайн-канал — страницей канала.
                onClick = { if (entry.isLive) onOpenLive(entry.channel.id) else onOpenChannel(entry.channel.id) },
            )
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Загружаем ещё…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
