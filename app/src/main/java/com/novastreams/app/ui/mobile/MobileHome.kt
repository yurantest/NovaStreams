package com.novastreams.app.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.Load
import com.novastreams.app.feature.home.HomeContent
import com.novastreams.app.feature.home.HomeViewModel
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.components.CategoryCard
import com.novastreams.app.ui.components.HintBanner
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.SectionTitle
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHomeScreen(
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: HomeViewModel = viewModel(factory = vmFactory { HomeViewModel(container) })
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.HOME) { force -> if (force) vm.refresh() else vm.refreshIfStale() }
    val state by vm.state.collectAsState()
    val auth by container.auth.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val pullState = rememberPullToRefreshState()

    Column(Modifier.fillMaxSize()) {
        HomeTopBar(onOpenSearch, onOpenProfile, auth)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.load(soft = true) },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                is Load.Loading -> LoadingState()
                is Load.Failed -> MessageState(
                    title = "Не удалось загрузить",
                    message = s.message,
                    actionLabel = "Повторить",
                    onAction = { vm.load(soft = false) },
                )
                is Load.Ready -> {
                    val empty = s.value.activeNow.isEmpty()
                        && s.value.popular.isEmpty()
                        && s.value.categories.isEmpty()
                        && s.value.followedLive.isEmpty()
                    if (empty) {
                        EmptyHomeState(onRefresh = { vm.load(soft = false) })
                    } else {
                        HomeBody(s.value, onOpenChannel, onOpenCategory, onOpenProfile)
                    }
                }
            }
        }
    }
}


@Composable
private fun EmptyHomeState(onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "😔",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Не удалось загрузить эфиры",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Проверьте интернет и нажмите «Обновить»",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            AccentButton(text = "Обновить", onClick = onRefresh)
        }
    }
}

@Composable
private fun HomeTopBar(onOpenSearch: () -> Unit, onOpenProfile: () -> Unit, auth: AuthState) {
    val accent = LocalNovaColors.current.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Nova",
            style = MaterialTheme.typography.headlineMedium,
            color = NovaColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Streams",
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        TopIcon(onClick = onOpenSearch) {
            Icon(Icons.Rounded.Search, contentDescription = "Поиск", tint = NovaColors.TextPrimary)
        }
        Spacer(Modifier.width(8.dp))
        TopIcon(onClick = onOpenProfile) {
            val signedIn = auth as? AuthState.SignedIn
            if (signedIn != null) {
                Avatar(
                    name = signedIn.user.displayName,
                    seed = signedIn.user.id.hashCode(),
                    size = 44.dp,
                    imageUrl = signedIn.user.profileImageUrl.takeIf { it.isNotBlank() },
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = "Профиль", tint = NovaColors.TextPrimary)
            }
        }
    }
}

@Composable
internal fun TopIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .novaClickable(CircleShape, onClick = onClick)
            .clip(CircleShape)
            .background(NovaColors.Card),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun HomeBody(
    content: HomeContent,
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenProfile: () -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        content.languageNote?.let { note ->
            item { HintBanner(note, Modifier.padding(horizontal = 16.dp)) }
        }
        if (!content.signedIn) {
            item {
                SignInPrompt(onClick = onOpenProfile, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        if (content.followedLive.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Подписки в эфире", Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(content.followedLive, key = { "f_" + it.id }) { stream ->
                            StreamCard(stream, width = 260.dp, onClick = { onOpenChannel(stream.channel.id) })
                        }
                    }
                }
            }
        }
        // Главная карточка — из «Активных сейчас», только если нет блока подписок.
        content.featured?.takeIf { content.followedLive.isEmpty() }?.let { featured ->
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    StreamCard(
                        stream = featured,
                        width = screenWidth - 32.dp,
                        onClick = { onOpenChannel(featured.channel.id) },
                        corner = 20.dp,
                    )
                }
            }
        }
        // Активные сейчас (язык пользователя) — выше популярных.
        if (content.activeNow.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Активные сейчас", Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val activeItems = if (content.followedLive.isEmpty() && content.featured != null) {
                            content.activeNow.drop(1)
                        } else {
                            content.activeNow
                        }
                        items(activeItems, key = { "a_" + it.id }) { stream ->
                            StreamCard(stream, width = 260.dp, onClick = { onOpenChannel(stream.channel.id) })
                        }
                    }
                }
            }
        }
        // Глобальный топ по зрителям — ниже.
        if (content.popular.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Популярные эфиры", Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(content.popular, key = { "p_" + it.id }) { stream ->
                            StreamCard(stream, width = 260.dp, onClick = { onOpenChannel(stream.channel.id) })
                        }
                    }
                }
            }
        }
        item {
            Column {
                SectionTitle("Категории", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(content.categories, key = { it.id }) { category ->
                        CategoryCard(category, width = 120.dp, onClick = { onOpenCategory(category.id) })
                    }
                }
            }
        }
        if (content.recent.isNotEmpty()) {
            item {
              Column {
                SectionTitle("Недавно открытые", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(content.recent, key = { it.id }) { channel ->
                        Column(
                            modifier = Modifier
                                .width(76.dp)
                                .novaClickable(RoundedCornerShape(14.dp)) { onOpenChannel(channel.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(channel.displayName, channel.artSeed, 60.dp, imageUrl = channel.avatarUrl)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = channel.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = NovaColors.TextSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
              }
            }
        }
    }
}

/** Приглашение войти: показывается, пока пользователь не вошёл. */
@Composable
internal fun SignInPrompt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NovaColors.Card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Войдите через Twitch",
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
            )
            Text(
                text = "Ваши подписки, чат и уведомления",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
            )
        }
        com.novastreams.app.ui.components.AccentButton(text = "Войти", onClick = onClick)
    }
}
