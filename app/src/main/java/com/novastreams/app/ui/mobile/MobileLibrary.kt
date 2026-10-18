package com.novastreams.app.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.feature.library.LibraryChannel
import com.novastreams.app.feature.library.LibraryTab
import com.novastreams.app.feature.library.LibraryViewModel
import com.novastreams.app.feature.streams.PagedStreamsViewModel
import com.novastreams.app.ui.components.LibraryTabBar
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.ui.components.HintBanner
import com.novastreams.app.ui.components.LiveBadge
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

@Composable
fun MobileLibraryScreen(onOpenChannel: (String) -> Unit) {
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

    // «Подписки» только после входа.
    val tabs = if (signedIn) LibraryTab.values().toList() else listOf(LibraryTab.COMMON, LibraryTab.FAVORITES)
    val shown = if (tab in tabs) tab else LibraryTab.COMMON

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Каналы",
            style = MaterialTheme.typography.headlineMedium,
            color = NovaColors.TextPrimary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 8.dp),
        )
        LibraryTabBar(tabs, shown, vm::selectTab, Modifier.padding(horizontal = 16.dp), spacing = 8.dp)
        Spacer(Modifier.height(8.dp))
        when (shown) {
            LibraryTab.COMMON -> {
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
                    else -> ChannelsList(
                        items = items,
                        header = if (language != null) "Каналы в эфире на языке: $language (по языку приложения)" else null,
                        loadingMore = commonLoading && !commonEnd,
                        onLoadMore = common::loadMore,
                        onOpenChannel = onOpenChannel,
                        onToggleFavorite = vm::toggleFavorite,
                        error = commonError,
                    )
                }
            }
            LibraryTab.FOLLOWED -> when (val st = followed) {
                is Load.Loading -> LoadingState()
                is Load.Failed -> MessageState("Не удалось загрузить", st.message, actionLabel = "Повторить", onAction = vm::load)
                is Load.Ready -> if (st.value.isEmpty()) {
                    MessageState("Подписок пока нет", "Подпишитесь на каналы на Twitch — они появятся здесь")
                } else {
                    ChannelsList(st.value, null, false, {}, onOpenChannel, vm::toggleFavorite)
                }
            }
            LibraryTab.FAVORITES -> when (val st = favorites) {
                is Load.Loading -> LoadingState()
                is Load.Failed -> MessageState("Не удалось загрузить", st.message, actionLabel = "Повторить", onAction = vm::reloadFavorites)
                is Load.Ready -> if (st.value.isEmpty()) {
                    MessageState(
                        "Избранное пусто",
                        "Нажмите сердечко в плеере или у канала в списке. Список хранится только на этом устройстве",
                    )
                } else {
                    ChannelsList(st.value, null, false, {}, onOpenChannel, vm::toggleFavorite)
                }
            }
        }
    }
}

/** Список каналов; при прокрутке к концу просит следующую порцию ([onLoadMore]). */
@Composable
private fun ChannelsList(
    items: List<LibraryChannel>,
    header: String?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    error: String? = null,
) {
    val listState = rememberLazyListState()
    // loadingMore и error в ключах: проверка повторяется после каждой загрузки, даже если новых каналов не прибавилось.
    LaunchedEffect(listState, items.size, loadingMore, error) {
        if (error != null) {
            kotlinx.coroutines.delay(3_000)
            onLoadMore()
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= items.size - 5) onLoadMore() }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            item {
                Text(header, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
            }
        }
        items(items, key = { it.channel.id }) { entry ->
            ChannelRow(
                entry = entry,
                onOpen = { onOpenChannel(entry.channel.id) },
                onToggleFavorite = { onToggleFavorite(entry.channel.id) },
            )
        }
        if (loadingMore) {
            item {
                Text("Загружаем ещё…", style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun ChannelRow(entry: LibraryChannel, onOpen: () -> Unit, onToggleFavorite: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val accent = LocalNovaColors.current.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NovaColors.Card)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .novaClickable(shape, focusScale = 1.02f, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(entry.channel.displayName, entry.channel.artSeed, 46.dp, imageUrl = entry.channel.avatarUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.channel.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isLive) {
                        Spacer(Modifier.width(8.dp))
                        LiveBadge()
                    }
                }
                Text(
                    text = entry.channel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FavoriteButtonBox(onToggleFavorite) {
            Icon(
                imageVector = if (entry.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (entry.isFavorite) "Убрать из избранного" else "В избранное",
                tint = if (entry.isFavorite) accent else NovaColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun FavoriteButtonBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(44.dp)
            .novaClickable(CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
