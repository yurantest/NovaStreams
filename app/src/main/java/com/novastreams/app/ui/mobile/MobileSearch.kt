package com.novastreams.app.ui.mobile

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.followersText
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.feature.search.SearchViewModel
import com.novastreams.app.ui.components.ArtBox
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.SectionTitle
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

@Composable
fun MobileSearchScreen(
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: SearchViewModel = viewModel(factory = vmFactory { SearchViewModel(container) })
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val history by vm.history.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Поиск",
            style = MaterialTheme.typography.headlineMedium,
            color = NovaColors.TextPrimary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Каналы, категории, название эфира…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        when (val r = results) {
            null -> HistoryList(history, onPick = vm::submit, onClear = vm::clearHistory)
            is Load.Loading -> LoadingState()
            is Load.Failed -> MessageState(
                title = "Ошибка поиска",
                message = r.message,
                actionLabel = "Повторить",
                onAction = { vm.submit() },
            )
            is Load.Ready -> {
                if (r.value.isEmpty) {
                    MessageState(
                        title = "Ничего не найдено",
                        message = "Попробуйте другое название или категорию",
                    )
                } else {
                    ResultsList(r.value.streams, r.value.channels, r.value.categories, onOpenChannel, onOpenCategory)
                }
            }
        }
    }
}

@Composable
private fun HistoryList(history: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) {
        MessageState(
            title = "История поиска пуста",
            message = "Введите запрос, чтобы найти канал или категорию",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Недавние запросы")
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Очистить",
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalNovaColors.current.accent,
                    modifier = Modifier
                        .novaClickable(RoundedCornerShape(8.dp), focusScale = 1f, onClick = onClear)
                        .padding(8.dp),
                )
            }
        }
        items(history, key = { it }) { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.bodyLarge,
                color = NovaColors.TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .novaClickable(RoundedCornerShape(10.dp), focusScale = 1f) { onPick(item) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ResultsList(
    streams: List<Stream>,
    channels: List<Channel>,
    categories: List<Category>,
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (streams.isNotEmpty()) {
            item { SectionTitle("В эфире") }
            items(streams, key = { "live_" + it.channel.id }) { stream ->
                StreamCard(
                    stream = stream,
                    width = 280.dp,
                    onClick = { onOpenChannel(stream.channel.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (channels.isNotEmpty()) {
            item {
                Column {
                    if (streams.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    SectionTitle("Каналы")
                }
            }
            items(channels, key = { "ch_" + it.id }) { channel ->
                ResultRow(
                    onClick = { onOpenChannel(channel.id) },
                    leading = { Avatar(channel.displayName, channel.artSeed, 44.dp, imageUrl = channel.avatarUrl) },
                    title = channel.displayName,
                    subtitle = channel.description.takeIf { it.isNotBlank() }
                        ?: followersText(channel.followers).orEmpty(),
                )
            }
        }
        if (categories.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(6.dp))
                    SectionTitle("Категории")
                }
            }
            items(categories, key = { "cat_" + it.id }) { category ->
                ResultRow(
                    onClick = { onOpenCategory(category.id) },
                    leading = {
                        ArtBox(
                            seed = category.artSeed,
                            imageUrl = category.boxArtUrl,
                            modifier = Modifier
                                .width(44.dp)
                                .height(58.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    },
                    title = category.name,
                    subtitle = if (category.viewers >= 0) formatViewers(category.viewers) + " зрителей" else "",
                )
            }
        }
    }
}

@Composable
private fun ResultRow(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .novaClickable(shape, focusScale = 1.02f, onClick = onClick)
            .clip(shape)
            .background(NovaColors.Card)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
            )
        }
    }
}
