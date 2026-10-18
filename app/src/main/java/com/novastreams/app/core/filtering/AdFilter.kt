package com.novastreams.app.core.filtering

import com.novastreams.app.core.model.Stream

/**
 * Расширяемый модуль фильтрации.
 *
 * Что он МОЖЕТ: скрывать элементы собственного интерфейса и отфильтровывать метаданные
 * (например, по ключевым словам в названии трансляции).
 *
 * Чего он НЕ делает: не вмешивается в видеопоток, не обходит DRM, авторизацию и защиту Twitch.
 * Реклама, встроенная в сам поток, этим модулем не блокируется, гарантий блокировки нет.
 */
interface ContentFilter {
    val id: String
    fun apply(streams: List<Stream>): List<Stream>
}

/** Пока пустой фильтр: ничего не меняет. Место для будущих правил. */
class PassThroughFilter : ContentFilter {
    override val id: String = "pass-through"
    override fun apply(streams: List<Stream>): List<Stream> = streams
}

/** Прячет эфиры из категорий, которые пользователь скрыл в настройках (id категорий берутся у владельца). */
class HiddenCategoriesFilter(private val hiddenIds: () -> Set<String>) : ContentFilter {
    override val id: String = "hidden-categories"
    override fun apply(streams: List<Stream>): List<Stream> {
        val hidden = hiddenIds()
        return if (hidden.isEmpty()) streams else streams.filter { it.categoryId !in hidden }
    }
}

/** Применяет фильтры по очереди; ошибка в любом из них не ломает приложение. */
class FilterPipeline(private val filters: List<ContentFilter> = listOf(PassThroughFilter())) {
    fun apply(streams: List<Stream>): List<Stream> =
        filters.fold(streams) { acc, filter ->
            try {
                filter.apply(acc)
            } catch (e: Exception) {
                acc
            }
        }
}
