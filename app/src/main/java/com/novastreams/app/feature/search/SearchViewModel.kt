package com.novastreams.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.SearchResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** null в [results] означает, что запрос ещё не выполнялся. */
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    val query = MutableStateFlow("")
    val results = MutableStateFlow<Load<SearchResults>?>(null)

    /** true — последний поиск шёл по демо-данным (тогда показываем пометку). */
    val isDemo = MutableStateFlow(false)

    val history: StateFlow<List<String>> = container.settings.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        query.value = value
        if (value.isBlank()) results.value = null
    }

    fun submit(text: String = query.value) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            results.value = null
            return
        }
        query.value = clean
        results.value = Load.Loading
        viewModelScope.launch {
            try {
                container.settings.addSearchQuery(clean)
                val found = container.twitch.search(clean)
                isDemo.value = container.twitch.source == DataSource.DEMO
                results.value = Load.Ready(found)
            } catch (e: Exception) {
                results.value = Load.Failed(e.message ?: "Ошибка поиска")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { container.settings.clearSearchHistory() }
    }
}
