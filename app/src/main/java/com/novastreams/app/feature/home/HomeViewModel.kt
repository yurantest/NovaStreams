package com.novastreams.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.Load
import com.novastreams.app.core.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch

data class HomeContent(
    /** Подписки, которые сейчас в эфире. Пусто, если пользователь не вошёл. */
    val followedLive: List<Stream>,
    /**
     * Главная трансляция: из блока «Активные сейчас» (язык пользователя),
     * иначе — из глобального топа.
     */
    val featured: Stream?,
    /**
     * Эфиры на языке пользователя (или все, если языковой фильтр выключен),
     * отсортированные по числу зрителей. Показываются выше «Популярных».
     */
    val activeNow: List<Stream>,
    /**
     * Глобальный топ по зрителям (без приоритета языка).
     * Показывается ниже «Активных сейчас».
     */
    val popular: List<Stream>,
    val categories: List<Category>,
    val recent: List<Channel>,
    val source: DataSource,
    /** Причина, по которой показаны демо-данные (null — данные настоящие). */
    val notice: String?,
    val signedIn: Boolean,
    /** Пояснение, если фильтр языка не сработал (null — всё в порядке). */
    val languageNote: String? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<Load<HomeContent>>(Load.Loading)
    val state: StateFlow<Load<HomeContent>> = _state

    /** true во время «потянуть вниз» / повторной загрузки, когда старый контент ещё на экране. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private var loadJob: Job? = null

    /** Когда данные главной в последний раз успешно обновились. */
    private var loadedAt = 0L

    /** Обновить сейчас, не убирая показанное (индикатор обновления вместо экрана загрузки). */
    fun refresh() = load(soft = true)

    /** Обновить, только если данным больше [maxAgeMs] (после сна приставки, при возврате на экран). */
    fun refreshIfStale(maxAgeMs: Long = com.novastreams.app.ui.components.STALE_AFTER_MS) {
        if (loadJob?.isActive == true) return
        if (_state.value is Load.Loading) return
        if (System.currentTimeMillis() - loadedAt > maxAgeMs) load(soft = true)
    }

    init {
        load(soft = false)
        // Вошли или вышли — главную нужно собрать заново (drop(1) пропускает текущее значение).
        viewModelScope.launch {
            container.auth.state.drop(1).collect { load(soft = false) }
        }
        // Скрыли или вернули категорию — главная пересобирается без неё.
        viewModelScope.launch {
            container.hiddenCategories.drop(1).collect { refresh() }
        }
    }

    /**
     * @param soft false — полный экран загрузки; true — оставляем старые данные и крутим индикатор обновления.
     */
    fun load(soft: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (soft && _state.value is Load.Ready) {
                _refreshing.value = true
            } else {
                _state.value = Load.Loading
            }
            try {
                val content = fetchHome()
                val old = (_state.value as? Load.Ready)?.value
                val nothingNew = content.followedLive.isEmpty() && content.activeNow.isEmpty() && content.popular.isEmpty()
                if (soft && old != null && nothingNew) {
                    // Сеть ещё не поднялась (приставка только проснулась) — не затираем показанное пустотой.
                } else {
                    _state.value = Load.Ready(content)
                    loadedAt = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // При soft-обновлении не затираем уже показанный экран одной сетевой ошибкой.
                if (!soft || _state.value !is Load.Ready) {
                    _state.value = Load.Failed(e.message ?: "Не удалось загрузить данные")
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun fetchHome(): HomeContent = supervisorScope {
        val signedIn = container.auth.state.value is AuthState.SignedIn
        val language = container.recommendationLanguage()

        // Каждая секция грузится отдельно с повторами: одна «неуспевшая» строка
        // не должна обнулять остальные блоки главной.
        val activeDeferred = async {
            safeList {
                container.filters.apply(container.twitch.liveStreams(language))
            }
        }
        val categoriesDeferred = async {
            safeList { container.twitch.topCategories() }
        }
        val followedDeferred = async {
            if (signedIn) {
                // Подписки не фильтруем: скрытые категории касаются только рекомендаций.
                safeList {
                    container.twitch.followedLive()
                }
            } else {
                emptyList()
            }
        }

        val languageStreams = activeDeferred.await()
        val categories = categoriesDeferred.await()
        val followed = followedDeferred.await()

        val source = container.twitch.source
        val languageNote = container.twitch.languageNote
        val followedIds = followed.map { it.id }.toSet()
        val preferredLanguage = language?.uppercase()

        val activeNow = languageStreams
            .filter { it.id !in followedIds }
            .let { list ->
                if (preferredLanguage != null) {
                    list.filter {
                        it.language.equals(preferredLanguage, ignoreCase = true)
                    }.sortedByDescending { it.viewers }
                } else {
                    list.sortedByDescending { it.viewers }
                }
            }

        // «Популярные» — топ по зрителям ТОЛЬКО на языке пользователя (раньше брался мировой топ с десятками тысяч
        // англоязычных зрителей). Эти эфиры убираем из «Активные сейчас», чтобы секции не дублировались.
        val popular = if (language != null) activeNow.take(POPULAR_COUNT) else emptyList()
        val popularIds = popular.map { it.id }.toSet()
        val featured = activeNow.firstOrNull()
        // Первым оставляем featured: экраны сами пропускают первый элемент, когда показывают его в «шапке».
        val activeRest = listOfNotNull(featured) + activeNow.filter { it.id !in popularIds && it.id != featured?.id }

        val recent = emptyList<Channel>()

        HomeContent(
            followedLive = followed,
            featured = featured,
            activeNow = activeRest,
            popular = popular,
            categories = categories,
            recent = recent,
            source = source,
            notice = container.twitch.notice,
            signedIn = signedIn,
            languageNote = languageNote,
        )
    }

    /**
     * До 4 попыток с паузой 1 → 2 → 3 с. Не бросает наружу: при полном провале — пустой список,
     * чтобы соседние блоки главной всё равно отрисовались.
     */
    private suspend fun <T> safeList(block: suspend () -> List<T>): List<T> {
        var waitMs = 1000L
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < 3) {
                    delay(waitMs)
                    waitMs = (waitMs + 1000L).coerceAtMost(4000L)
                }
            }
        }
        return emptyList()
    }
}

private const val POPULAR_COUNT = 10
