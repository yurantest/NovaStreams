package com.novastreams.app.core.model

/** Источник данных. DEMO — локальные демонстрационные данные, TWITCH — реальный API (пока не подключён). */
enum class DataSource { DEMO, TWITCH }

data class Category(
    val id: String,
    val name: String,
    val viewers: Int,
    /** Индекс в палитре локальных заглушек обложек. */
    val artSeed: Int,
    /** Обложка категории (реальный режим); null — локальная заглушка. */
    val boxArtUrl: String? = null,
)

data class Channel(
    val id: String,
    val login: String,
    val displayName: String,
    val description: String,
    /** Число подписчиков; -1 — неизвестно (Twitch отдаёт его только с правами модератора). */
    val followers: Int,
    val artSeed: Int,
    val avatarUrl: String? = null,
)

data class Stream(
    val id: String,
    val channel: Channel,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val viewers: Int,
    val language: String,
    val isLive: Boolean = true,
    val artSeed: Int,
    val thumbnailUrl: String? = null,
)

/** Канал из подписок пользователя; [stream] не null, если он сейчас в эфире. */
data class FollowedChannel(val channel: Channel, val stream: Stream?)

data class ChatMessage(
    val id: Long,
    val author: String,
    val text: String,
    /** Индекс цвета ника в палитре чата (если Twitch не прислал свой цвет). */
    val colorSeed: Int,
    /** Цвет ника от Twitch в виде #RRGGBB; null — использовать палитру приложения. */
    val colorHex: String? = null,
    /** true — сообщение отправлено самим пользователем из этого приложения. */
    val isOwn: Boolean = false,
    /** Смайлики Twitch в сообщении (позиции — в символах Unicode, как их присылает Twitch). */
    val twitchEmotes: List<EmoteSpan> = emptyList(),
)

/** Смайлик Twitch внутри текста: символы от [start] до [end] включительно, [id] — номер смайлика. */
data class EmoteSpan(val start: Int, val end: Int, val id: String)

data class SearchResults(
    val channels: List<Channel>,
    val categories: List<Category>,
    /** Эфиры: live-каналы и совпадения по названию трансляции. */
    val streams: List<Stream> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && categories.isEmpty() && streams.isEmpty()
}

/** Результат загрузки для экранов: загрузка / данные / ошибка. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun formatViewers(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 10_000 -> String.format("%dK", count / 1000)
    count >= 1_000 -> String.format("%.1fK", count / 1000.0)
    else -> count.toString()
}

fun followersText(count: Int): String? =
    if (count < 0) null else formatViewers(count) + " подписчиков"

/** Стабильный индекс заглушки по идентификатору, чтобы цвет не прыгал между запусками. */
fun seedOf(id: String): Int = (id.hashCode() and 0x7fffffff) % 8
