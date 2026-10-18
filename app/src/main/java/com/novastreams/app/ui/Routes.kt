package com.novastreams.app.ui

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "channels"
    const val PROFILE = "profile"
    const val ACTIVE = "active"
    const val CATEGORIES = "categories"
    const val LOGIN = "login"

    const val CHANNEL_ARG = "channelId"
    const val CATEGORY_ARG = "categoryId"

    const val CHANNEL = "channel/{$CHANNEL_ARG}"
    const val PLAYER = "player/{$CHANNEL_ARG}"
    const val CATEGORY = "category/{$CATEGORY_ARG}"

    /** Страница стримера: вкладки «Эфир», «Записи», «Клипы». */
    const val STREAMER = "streamer/{$CHANNEL_ARG}"

    const val VIDEO_KIND_ARG = "videoKind"
    const val VIDEO_ID_ARG = "videoId"
    /** Воспроизведение записи эфира или клипа. */
    const val VIDEO = "video/{$VIDEO_KIND_ARG}/{$VIDEO_ID_ARG}"

    fun channel(id: String) = "channel/$id"
    fun player(id: String) = "player/$id"
    fun category(id: String) = "category/$id"
    fun streamer(id: String) = "streamer/$id"
    fun video(kind: String, id: String) = "video/$kind/$id"
}
