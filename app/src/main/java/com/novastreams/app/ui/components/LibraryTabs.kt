package com.novastreams.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novastreams.app.feature.library.LibraryTab

/** Переключатель вкладок экрана «Каналы»: выбранная — залитая кнопка, остальные — контурные. */
@Composable
fun LibraryTabBar(
    tabs: List<LibraryTab>,
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        tabs.forEach { tab ->
            AccentButton(text = tab.title, onClick = { onSelect(tab) }, filled = tab == selected)
        }
    }
}
