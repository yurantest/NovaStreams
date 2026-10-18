package com.novastreams.app.core.stream

/** Откуда берётся видео. Переключается в настройках («Источник потока»). */
enum class StreamSourceMode(
    val title: String,
    val description: String,
) {
    /**
     * Свой плеер (ExoPlayer): HLS через GraphQL + Usher.
     * Неофициально, может сломаться; зато свой UI, качество, предзагрузка.
     */
    EXPERIMENTAL(
        title = "Свой плеер (HLS)",
        description = "Неофициальная ссылка на поток (GraphQL + Usher). Быстрый старт, свои настройки качества. Может перестать работать, если Twitch изменит API.",
    ),

    /**
     * Официальный встроенный плеер Twitch (iframe embed в WebView).
     * Реклама и правила Twitch как на сайте.
     */
    OFFICIAL(
        title = "Официальный плеер Twitch",
        description = "Встроенный плеер Twitch (embed). Официальный способ, реклама Twitch сохраняется. Меньше настроек качества в приложении.",
    );

    companion object {
        /** По умолчанию — свой HLS-плеер (как сейчас в приложении). */
        fun fromKey(key: String?): StreamSourceMode =
            entries.firstOrNull { it.name == key } ?: EXPERIMENTAL
    }
}
