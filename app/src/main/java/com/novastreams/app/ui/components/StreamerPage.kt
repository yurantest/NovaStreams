package com.novastreams.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.followersText
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.core.video.ChannelVideo
import com.novastreams.app.core.video.ClipPeriod
import com.novastreams.app.core.video.VideoKind
import com.novastreams.app.core.video.formatDuration
import com.novastreams.app.feature.streamer.StreamerTab
import com.novastreams.app.feature.streamer.StreamerViewModel
import com.novastreams.app.feature.streamer.VideoListState
import com.novastreams.app.ui.theme.LocalIsTv
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory
import kotlinx.coroutines.delay

/**
 * Открывает страницу стримера по номеру канала. Навигация задаёт значение один раз в корне приложения,
 * поэтому карточкам и плееру не нужно знать про NavController (долгое нажатие на карточку, кнопка в плеере).
 */
val LocalChannelPageOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Карточка записи или клипа: превью с длительностью, название, дата и просмотры. */
@Composable
fun VideoCard(video: ChannelVideo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .novaClickable(shape, onClick = onClick)
            .clip(shape)
            .background(NovaColors.Card),
    ) {
        ArtBox(
            seed = video.artSeed,
            imageUrl = video.thumbnailUrl,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Text(
                text = formatDuration(video.durationSec),
                style = MaterialTheme.typography.labelMedium,
                color = NovaColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xB3101014))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = formatViewers(video.views) + " просм.",
                style = MaterialTheme.typography.labelMedium,
                color = NovaColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xB3101014))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(video.dateText, video.categoryName, if (video.curator.isNotBlank()) "клип: ${video.curator}" else "")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Страница стримера — общая для телефона и ТВ: шапка (аватар, имя, избранное) и три вкладки:
 * «Эфир» (смотреть, если в эфире), «Записи» (прошлые трансляции), «Клипы».
 */
@Composable
fun StreamerScreen(
    channelId: String,
    onBack: () -> Unit,
    onWatchLive: () -> Unit,
    onOpenVideo: (VideoKind, String) -> Unit,
    /** Кнопка «Назад» в шапке — только на телефоне (на ТВ есть кнопка пульта). */
    showBackButton: Boolean,
    backButton: @Composable () -> Unit = {},
) {
    val container = LocalContext.current.appContainer()
    val isTv = LocalIsTv.current
    val vm: StreamerViewModel = viewModel(
        key = "streamer_$channelId",
        factory = vmFactory { StreamerViewModel(container, channelId) },
    )
    val state by vm.state.collectAsState()
    val favorite by vm.isFavorite.collectAsState()
    val tab by vm.tab.collectAsState()
    val vods by vm.vods.collectAsState()
    val clips by vm.clips.collectAsState()
    val period by vm.period.collectAsState()
    val pad = if (isTv) 40.dp else 16.dp

    when (val s = state) {
        is Load.Loading -> LoadingState()
        is Load.Failed -> MessageState(
            title = "Не удалось открыть канал",
            message = s.message,
            actionLabel = "Повторить",
            onAction = vm::load,
        )
        is Load.Ready -> {
            val channel = s.value.channel
            val stream = s.value.stream
            val tabFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (isTv) try { tabFocus.requestFocus() } catch (_: Exception) { }
            }
            Column(Modifier.fillMaxSize().padding(top = if (isTv) 24.dp else 8.dp)) {
                // ——— Шапка ———
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = pad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBackButton) {
                        backButton()
                        Spacer(Modifier.width(10.dp))
                    }
                    Avatar(channel.displayName, channel.artSeed, if (isTv) 56.dp else 48.dp, imageUrl = channel.avatarUrl)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = channel.displayName,
                                style = if (isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                color = NovaColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (stream != null) {
                                Spacer(Modifier.width(8.dp))
                                LiveBadge()
                            }
                        }
                        val sub = followersText(channel.followers) ?: channel.description.takeIf { it.isNotBlank() }
                        if (sub != null) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = NovaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    AccentButton(
                        text = if (favorite) "В избранном" else "В избранное",
                        onClick = vm::toggleFavorite,
                        filled = !favorite,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // ——— Вкладки ———
                Row(Modifier.padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StreamerTab.entries.forEach { t ->
                        AccentButton(
                            text = t.title,
                            onClick = { vm.selectTab(t) },
                            filled = t == tab,
                            modifier = if (t == StreamerTab.LIVE) Modifier.focusRequester(tabFocus) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                when (tab) {
                    StreamerTab.LIVE -> LiveTab(
                        streamTitle = stream?.title,
                        category = stream?.categoryName,
                        viewers = stream?.viewers,
                        thumbnailUrl = stream?.thumbnailUrl,
                        artSeed = stream?.artSeed ?: channel.artSeed,
                        isLive = stream != null,
                        login = channel.login,
                        onWatchLive = onWatchLive,
                        onShowVods = { vm.selectTab(StreamerTab.VODS) },
                        onShowClips = { vm.selectTab(StreamerTab.CLIPS) },
                        pad = pad,
                    )
                    StreamerTab.VODS -> VideoGrid(
                        state = vods,
                        emptyText = vm.emptyText(VideoKind.VOD),
                        onLoadMore = { vm.loadMore(StreamerTab.VODS) },
                        onOpen = { onOpenVideo(it.kind, it.id) },
                        pad = pad,
                    )
                    StreamerTab.CLIPS -> Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.padding(horizontal = pad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Период:", style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextSecondary)
                            ClipPeriod.entries.forEach { p ->
                                AccentButton(text = p.title, onClick = { vm.setPeriod(p) }, filled = p == period)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        VideoGrid(
                            state = clips,
                            emptyText = vm.emptyText(VideoKind.CLIP),
                            onLoadMore = { vm.loadMore(StreamerTab.CLIPS) },
                            onOpen = { onOpenVideo(it.kind, it.id) },
                            pad = pad,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTab(
    streamTitle: String?,
    category: String?,
    viewers: Int?,
    thumbnailUrl: String?,
    artSeed: Int,
    isLive: Boolean,
    login: String,
    onWatchLive: () -> Unit,
    onShowVods: () -> Unit,
    onShowClips: () -> Unit,
    pad: androidx.compose.ui.unit.Dp,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = pad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isLive) {
            ArtBox(
                seed = artSeed,
                imageUrl = thumbnailUrl,
                modifier = Modifier
                    .fillMaxWidth(if (LocalIsTv.current) 0.5f else 1f)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                if (viewers != null) ViewersChip(viewers, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            Text(
                text = streamTitle ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!category.isNullOrBlank()) {
                Text(category, style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextSecondary)
            }
            AccentButton(text = "Смотреть эфир", onClick = onWatchLive)
        } else {
            Text(
                text = "Сейчас не в эфире",
                style = MaterialTheme.typography.titleLarge,
                color = NovaColors.TextPrimary,
            )
            Text(
                text = "Загляните в записи прошлых трансляций или в клипы.",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(text = "Записи", onClick = onShowVods)
                AccentButton(text = "Клипы", onClick = onShowClips, filled = false)
            }
        }
        FollowOnSiteButton(login = login)
    }
}

/** Сетка карточек с подгрузкой по мере прокрутки; при ошибке сети повторяет сама через 3 секунды. */
@Composable
private fun VideoGrid(
    state: VideoListState,
    emptyText: String,
    onLoadMore: () -> Unit,
    onOpen: (ChannelVideo) -> Unit,
    pad: androidx.compose.ui.unit.Dp,
) {
    val gridState = rememberLazyGridState()
    val isTv = LocalIsTv.current

    // Как в списках эфиров: проверка повторяется после каждой загрузки, поэтому у конца списка подгрузка не «застревает».
    LaunchedEffect(gridState, state.items.size, state.loading, state.error, state.ended) {
        if (state.ended) return@LaunchedEffect
        if (state.error != null) {
            delay(3_000)
            onLoadMore()
            return@LaunchedEffect
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (state.items.isNotEmpty() && last >= state.items.size - 4) onLoadMore() }
    }

    when {
        state.items.isEmpty() && (state.loading || !state.started) -> LoadingState()
        state.items.isEmpty() && state.error != null -> MessageState(
            title = "Не удалось загрузить",
            message = state.error,
            actionLabel = "Повторить",
            onAction = onLoadMore,
        )
        state.items.isEmpty() -> MessageState("Пусто", emptyText)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isTv) 260.dp else 160.dp),
            state = gridState,
            contentPadding = PaddingValues(start = pad, end = pad, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.kind.routeName + it.id }) { video ->
                VideoCard(video, onClick = { onOpen(video) })
            }
            if (state.loading || state.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = if (state.error != null) "Не удалось подгрузить: ${state.error}. Повторяем…" else "Загружаем ещё…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}
