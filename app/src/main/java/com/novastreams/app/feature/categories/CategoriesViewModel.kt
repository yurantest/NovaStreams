package com.novastreams.app.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Полный каталог категорий Twitch с подгрузкой по прокрутке. */
class CategoriesViewModel(private val container: AppContainer) : ViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private var cursor: String? = null
    private var busy = false
    private var loadedAt = 0L
    private var replaceOnNext = false

    /** Загружает каталог заново; прежний остаётся на экране, пока не придёт первая страница. */
    fun refresh() {
        if (busy) return
        cursor = null
        _endReached.value = false
        replaceOnNext = true
        loadMore()
    }

    fun refreshIfStale(maxAgeMs: Long = com.novastreams.app.ui.components.STALE_AFTER_MS) {
        if (!busy && loadedAt != 0L && System.currentTimeMillis() - loadedAt > maxAgeMs) refresh()
    }

    init {
        loadMore()
    }

    fun loadMore() {
        if (busy || _endReached.value) return
        busy = true
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val page = container.twitch.categoriesPage(cursor)
                if (replaceOnNext) {
                    if (page.categories.isNotEmpty()) _categories.value = page.categories.distinctBy { it.id }
                    replaceOnNext = false
                } else {
                    _categories.value = (_categories.value + page.categories).distinctBy { it.id }
                }
                if (page.categories.isNotEmpty()) loadedAt = System.currentTimeMillis()
                cursor = page.cursor
                if (cursor == null || page.categories.isEmpty()) {
                    _endReached.value = true
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось загрузить категории"
            } finally {
                _loading.value = false
                busy = false
            }
        }
    }

    fun retry() {
        if (_categories.value.isEmpty()) {
            cursor = null
            _endReached.value = false
        }
        loadMore()
    }
}
