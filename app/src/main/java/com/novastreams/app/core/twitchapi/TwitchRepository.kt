package com.novastreams.app.core.twitchapi

import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.FollowedChannel
import com.novastreams.app.core.model.SearchResults
import com.novastreams.app.core.model.Stream

/**
 * Единая точка доступа к данным Twitch. Реализации: [DemoTwitchRepository] (вымышленные данные),
 * [HelixTwitchRepository] (официальный Helix API, нужен вход) и [RoutingTwitchRepository],
 * который выбирает между ними по состоянию авторизации.
 */
interface TwitchRepository {
    val source: DataSource

    /** Пояснение, если фильтр языка не удалось применить (null — всё в порядке или фильтра не было). */
    val languageNote: String? get() = null

    /**
     * Топ эфиров по числу зрителей. [language] — двухбуквенный код в верхнем регистре ("RU");
     * null — все языки. Если на выбранном языке эфиров нет, возвращаются эфиры всех языков.
     */
    suspend fun liveStreams(language: String? = null): List<Stream>

    /** Подписки пользователя, которые сейчас в эфире (в демо-режиме пусто). */
    suspend fun followedLive(): List<Stream>

    /** Все подписки пользователя: онлайн-каналы первыми. */
    suspend fun followedChannels(): List<FollowedChannel>

    /** true, если текущий пользователь подписан (Follow) на канал на Twitch. Без входа — false. */
    suspend fun isFollowing(broadcasterId: String): Boolean = false

    /**
     * Одна «порция» эфиров для бесконечной прокрутки. [categoryId] = null — все эфиры (раздел «Активные сейчас»).
     * [after] — курсор из прошлой порции (null — первая). Реализации без постраничной загрузки
     * отдают всё одной порцией (cursor = null).
     */
    suspend fun streamsPage(categoryId: String?, language: String? = null, after: String? = null): StreamsPage {
        if (after != null) return StreamsPage(emptyList(), null)
        val all = if (categoryId == null) liveStreams(language) else streamsForCategory(categoryId, language)
        return StreamsPage(all, null)
    }

    /**
     * Порция категорий (игр) для экрана «Все категории».
     * [after] — курсор; null — первая страница. Реализации без пагинации отдают topCategories одним куском.
     */
    suspend fun categoriesPage(after: String? = null): CategoriesPage {
        if (after != null) return CategoriesPage(emptyList(), null)
        return CategoriesPage(topCategories(), null)
    }

    suspend fun topCategories(): List<Category>
    /** Эфиры категории; [language] — как в [liveStreams]. */
    suspend fun streamsForCategory(categoryId: String, language: String? = null): List<Stream>
    suspend fun categoryName(categoryId: String): String?
    suspend fun streamForChannel(channelId: String): Stream?
    suspend fun channel(channelId: String): Channel?
    suspend fun search(query: String): SearchResults
}

/** Порция эфиров и курсор следующей (null — больше нет). */
class StreamsPage(val streams: List<Stream>, val cursor: String?)

/** Порция категорий и курсор следующей страницы. */
class CategoriesPage(val categories: List<Category>, val cursor: String?)

/** Понятная пользователю ошибка API (а не сырой текст исключения). */
class ApiException(message: String, val code: Int = 0) : Exception(message)
