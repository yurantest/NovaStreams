package com.novastreams.app.core.twitchapi

import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.DataSource
import com.novastreams.app.core.model.FollowedChannel
import com.novastreams.app.core.model.SearchResults
import com.novastreams.app.core.model.Stream
import kotlinx.coroutines.delay

/** Демонстрационная реализация: данные берутся из [DemoData], сеть не используется. */
class DemoTwitchRepository : TwitchRepository {

    override val source: DataSource = DataSource.DEMO

    override suspend fun liveStreams(language: String?): List<Stream> {
        delay(350)
        val all = DemoData.streams.sortedByDescending { it.viewers }
        return language?.let { lang -> all.filter { it.language.equals(lang, ignoreCase = true) } }
            ?.takeIf { it.isNotEmpty() } ?: all
    }

    override suspend fun followedLive(): List<Stream> = emptyList()

    override suspend fun followedChannels(): List<FollowedChannel> =
        DemoData.channels
            .map { ch -> FollowedChannel(ch, DemoData.streams.firstOrNull { it.channel.id == ch.id }) }
            .sortedWith(compareByDescending<FollowedChannel> { it.stream != null }.thenBy { it.channel.displayName })

    override suspend fun topCategories(): List<Category> {
        delay(200)
        return DemoData.categories.sortedByDescending { it.viewers }
    }

    override suspend fun streamsForCategory(categoryId: String, language: String?): List<Stream> {
        val inCategory = DemoData.streams.filter { it.categoryId == categoryId }
        return language?.let { lang -> inCategory.filter { it.language.equals(lang, ignoreCase = true) } }
            ?.takeIf { it.isNotEmpty() } ?: inCategory
    }

    override suspend fun categoryName(categoryId: String): String? =
        DemoData.categories.firstOrNull { it.id == categoryId }?.name

    override suspend fun streamForChannel(channelId: String): Stream? =
        DemoData.streams.firstOrNull { it.channel.id == channelId }

    override suspend fun channel(channelId: String): Channel? =
        DemoData.channels.firstOrNull { it.id == channelId }

    override suspend fun search(query: String): SearchResults {
        delay(250)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val channels = DemoData.channels.filter {
            it.displayName.lowercase().contains(q) || it.login.lowercase().contains(q)
        }
        val categories = DemoData.categories.filter { it.name.lowercase().contains(q) }
        val streams = DemoData.streams.filter {
            val hay = listOf(it.title, it.channel.displayName, it.channel.login, it.categoryName)
                .joinToString(" ").lowercase()
            hay.contains(q)
        }
        return SearchResults(channels = channels, categories = categories, streams = streams)
    }
}
