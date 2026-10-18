package com.novastreams.app.ui.tv

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.feature.home.HomeContent
import com.novastreams.app.feature.home.HomeViewModel
import com.novastreams.app.ui.components.ArtBox
import com.novastreams.app.ui.components.CategoryCard
import com.novastreams.app.ui.components.HintBanner
import com.novastreams.app.ui.components.LiveBadge
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.SectionTitle
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

private val TvHPadding = 40.dp

@Composable
fun TvHomeScreen(
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: HomeViewModel = viewModel(factory = vmFactory { HomeViewModel(container) })
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.HOME) { force -> if (force) vm.refresh() else vm.refreshIfStale() }
    val state by vm.state.collectAsState()

    when (val s = state) {
        is Load.Loading -> LoadingState()
        is Load.Failed -> MessageState(
            title = "Не удалось загрузить",
            message = s.message,
            actionLabel = "Повторить",
            onAction = { vm.load(soft = false) },
        )
        is Load.Ready -> TvHomeBody(s.value, onOpenChannel, onOpenCategory)
    }
}

@Composable
private fun TvHomeBody(
    content: HomeContent,
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        content.languageNote?.let { note ->
            item { HintBanner(note, Modifier.padding(horizontal = TvHPadding)) }
        }
        if (!content.signedIn) {
            item {
                HintBanner(
                    "Войдите в настройках (значок шестерёнки слева), чтобы видеть подписки",
                    Modifier.padding(horizontal = TvHPadding),
                )
            }
        }
        // Подписки в эфире — над популярными.
        if (content.followedLive.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Подписки в эфире", Modifier.padding(horizontal = TvHPadding))
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = TvHPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(content.followedLive, key = { "f_" + it.id }) { stream ->
                            StreamCard(stream, width = 300.dp, onClick = { onOpenChannel(stream.channel.id) })
                        }
                    }
                }
            }
        }
        // Главный блок — из «Активных сейчас», только если подписок в эфире нет.
        content.featured?.takeIf { content.followedLive.isEmpty() }?.let { featured ->
            item { TvHero(featured) { onOpenChannel(featured.channel.id) } }
        }
        // Активные сейчас (язык пользователя) — выше популярных.
        if (content.activeNow.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Активные сейчас", Modifier.padding(horizontal = TvHPadding))
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = TvHPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        val activeItems = if (content.followedLive.isEmpty() && content.featured != null) {
                            content.activeNow.drop(1)
                        } else {
                            content.activeNow
                        }
                        items(activeItems, key = { "a_" + it.id }) { stream ->
                            StreamCard(stream, width = 300.dp, onClick = { onOpenChannel(stream.channel.id) })
                        }
                    }
                }
            }
        }
        // Глобальный топ по зрителям — ниже.
        if (content.popular.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Популярные эфиры", Modifier.padding(horizontal = TvHPadding))
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = TvHPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(content.popular, key = { "p_" + it.id }) { stream ->
                            StreamCard(stream, width = 300.dp, onClick = { onOpenChannel(stream.channel.id) })
                        }
                    }
                }
            }
        }
        item {
            Column {
                SectionTitle("Категории", Modifier.padding(horizontal = TvHPadding))
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = TvHPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(content.categories, key = { it.id }) { category ->
                        CategoryCard(category, width = 150.dp, onClick = { onOpenCategory(category.id) })
                    }
                }
            }
        }
    }
}

/**
 * Крупный блок: превью слева, информация справа. Кнопки «Смотреть» нет: весь блок сам является
 * фокусируемой «карточкой» — навёл пультом и нажал OK, эфир открывается.
 */
@Composable
private fun TvHero(stream: Stream, onWatch: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // Фокус не критичен: пользователь может переместить его D-pad.
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TvHPadding)
            .focusRequester(focus)
            .novaClickable(RoundedCornerShape(24.dp), focusScale = 1.02f, onClick = onWatch)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ArtBox(
            seed = stream.artSeed,
            imageUrl = stream.thumbnailUrl,
            modifier = Modifier
                .width(440.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            LiveBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stream.channel.displayName,
                style = MaterialTheme.typography.headlineLarge,
                color = NovaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stream.title,
                style = MaterialTheme.typography.titleLarge,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stream.categoryName + " · " + formatViewers(stream.viewers) + " зрителей",
                style = MaterialTheme.typography.bodyLarge,
                color = NovaColors.TextSecondary,
            )
        }
    }
}
