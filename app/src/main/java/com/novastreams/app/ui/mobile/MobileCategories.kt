package com.novastreams.app.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.appContainer
import com.novastreams.app.feature.categories.CategoriesViewModel
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.CategoryCard
import com.novastreams.app.ui.components.LoadingState
import com.novastreams.app.ui.components.MessageState
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun MobileCategoriesScreen(onOpenCategory: (String) -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: CategoriesViewModel = viewModel(factory = vmFactory { CategoriesViewModel(container) })
    com.novastreams.app.ui.components.ScreenRefresh(com.novastreams.app.ui.Routes.CATEGORIES) { force -> if (force) vm.refresh() else vm.refreshIfStale() }
    val categories by vm.categories.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val endReached by vm.endReached.collectAsState()
    val gridState = rememberLazyGridState()
    val width = ((LocalConfiguration.current.screenWidthDp - 16 * 2 - 12) / 3).dp

    LaunchedEffect(gridState, categories.size, loading, endReached) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            last >= total - 6 && total > 0
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && !loading && !endReached) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Категории",
            style = MaterialTheme.typography.headlineMedium,
            color = NovaColors.TextPrimary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 8.dp),
        )
        when {
            categories.isEmpty() && loading -> LoadingState()
            categories.isEmpty() && error != null -> MessageState(
                title = "Не удалось загрузить",
                message = error ?: "",
                actionLabel = "Повторить",
                onAction = vm::retry,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(categories, key = { it.id }) { cat ->
                    CategoryCard(cat, width = width, onClick = { onOpenCategory(cat.id) })
                }
                if (loading) {
                    item(span = { GridItemSpan(3) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NovaColors.TextSecondary)
                        }
                    }
                }
                if (error != null && categories.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error ?: "", color = NovaColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            AccentButton(text = "Ещё", onClick = vm::retry)
                        }
                    }
                }
            }
        }
    }
}
