package com.novastreams.app.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novastreams.app.feature.streams.PagedStreamsViewModel
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.components.StreamCard
import com.novastreams.app.ui.theme.NovaColors

private val GridPad = 40.dp

/**
 * Сетка эфиров на весь экран с подгрузкой по мере прокрутки (общая для категории и «Активные сейчас»).
 * Число колонок считается по ширине экрана, карточки растягиваются без пустых полей.
 */
@Composable
fun TvStreamsGrid(
    vm: PagedStreamsViewModel,
    onOpenChannel: (String) -> Unit,
    emptyTitle: String,
    emptyMessage: String,
) {
    val streams by vm.streams.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val endReached by vm.endReached.collectAsState()
    val note by vm.note.collectAsState()
    val gridState = rememberLazyGridState()

    // Когда до конца списка остаётся меньше шести карточек — просим следующую порцию.
    // В ключах есть loading и error: когда загрузка закончилась (даже если новых карточек не прибавилось),
    // условие проверяется заново — иначе у самого конца списка подгрузка «застревала» до следующей прокрутки.
    LaunchedEffect(gridState, streams.size, endReached, loading, error) {
        if (error != null) {
            // Ошибка сети: повторяем сами через пару секунд, прокручивать дальше некуда.
            delay(3_000)
            vm.loadMore()
            return@LaunchedEffect
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (streams.isNotEmpty() && last >= streams.size - 6) vm.loadMore()
            }
    }

    when {
        streams.isEmpty() && loading -> LoadingState()
        streams.isEmpty() && error != null -> MessageState(
            title = "Не удалось загрузить",
            message = error ?: "",
            actionLabel = "Повторить",
            onAction = vm::loadMore,
        )
        streams.isEmpty() -> MessageState(emptyTitle, note ?: emptyMessage)
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val gap = 18.dp
            val avail = maxWidth - GridPad * 2
            val cols = (((avail + gap) / (260.dp + gap)).toInt()).coerceAtLeast(2)
            val cardWidth = (avail - gap * (cols - 1)) / cols
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                contentPadding = PaddingValues(start = GridPad, end = GridPad, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(streams, key = { it.id }) { stream ->
                    StreamCard(stream, width = cardWidth, onClick = { onOpenChannel(stream.channel.id) })
                }
                // Подвал: индикатор подгрузки или кнопка повтора при ошибке.
                if (loading || error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = if (error != null) "Не удалось подгрузить: $error. Повторяем…" else "Загружаем ещё…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NovaColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
