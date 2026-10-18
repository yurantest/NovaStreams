package com.novastreams.app.feature.streams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.model.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Список эфиров с подгрузкой по мере прокрутки.
 * [categoryId] = null — раздел «Активные сейчас» (все эфиры на языке системы).
 */
class PagedStreamsViewModel(
    private val container: AppContainer,
    private val categoryId: String?,
) : ViewModel() {

    private val _streams = MutableStateFlow<List<Stream>>(emptyList())
    val streams: StateFlow<List<Stream>> = _streams

    /** true — идёт загрузка (первая или очередная порция). */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    /** Название категории (для заголовка). */
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title

    /** Язык фильтра ("RU") или null — все языки. */
    private val _language = MutableStateFlow<String?>(null)
    val language: StateFlow<String?> = _language

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    private var cursor: String? = null
    private var busy = false

    /** Сколько раз подряд подгрузка не добавила ни одного нового эфира (чтобы не крутиться вечно). */
    private var emptyLoads = 0
    private var initialized = false

    /** Когда список в последний раз успешно загружался (0 — ещё нет). */
    private var loadedAt = 0L

    /** true — первая порция заменит прежний список (обновление), а не добавится к нему. */
    private var replaceOnNext = false

    /** Загружает список заново; пока идёт загрузка, прежние карточки остаются на экране. */
    fun refresh() {
        if (busy) return
        cursor = null
        _endReached.value = false
        emptyLoads = 0
        replaceOnNext = true
        loadMore()
    }

    /** Обновляет, если данным больше [maxAgeMs]. */
    fun refreshIfStale(maxAgeMs: Long = com.novastreams.app.ui.components.STALE_AFTER_MS) {
        if (!busy && loadedAt != 0L && System.currentTimeMillis() - loadedAt > maxAgeMs) refresh()
    }

    init {
        loadMore()
        // «Активные сейчас»: скрыли/вернули категорию — список пересобирается. Страница самой категории не фильтруется.
        if (categoryId == null) {
            viewModelScope.launch {
                container.hiddenCategories.drop(1).collect { refresh() }
            }
        }
    }

    /** Подгружает следующую порцию; вызывается при прокрутке к концу списка и по кнопке «Повторить». */
    fun loadMore() {
        if (busy || _endReached.value) return
        busy = true
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                if (!initialized) {
                    _language.value = container.recommendationLanguage()
                    _title.value = categoryId?.let { container.twitch.categoryName(it) }
                    initialized = true
                }
                // Если порция целиком отфильтрована (скрытые категории и т.п.), читаем дальше — до 3 раз.
                val replace = replaceOnNext
                var list: List<Stream> = if (replace) emptyList() else _streams.value
                val startSize = list.size
                var attempts = 0
                while (attempts < 3) {
                    attempts++
                    val page = container.twitch.streamsPage(categoryId, _language.value, cursor)
                    val before = list.size
                    list = (list + (if (categoryId == null) container.filters.apply(page.streams) else page.streams)).distinctBy { it.id }
                    // При обновлении пустой ответ не затирает прежний список (сеть могла ещё не подняться).
                    if (list.isNotEmpty() || !replace) _streams.value = list
                    cursor = page.cursor
                    _note.value = container.twitch.languageNote
                    if (cursor == null) {
                        _endReached.value = true
                        break
                    }
                    if (list.size > before) break
                }
                replaceOnNext = false
                if (list.isNotEmpty()) loadedAt = System.currentTimeMillis()
                // Несколько порций подряд без новых эфиров — считаем, что список закончился.
                if (list.size == startSize && !_endReached.value) {
                    emptyLoads++
                    if (emptyLoads >= 3) _endReached.value = true
                } else {
                    emptyLoads = 0
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Ошибка загрузки"
            } finally {
                busy = false
                _loading.value = false
            }
        }
    }
}
