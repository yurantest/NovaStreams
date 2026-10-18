package com.novastreams.app.core.stream

/**
 * Качество по умолчанию при старте своего (ExoPlayer) плеера.
 * Ограничивает максимальную высоту кадра или фиксирует самый лёгкий поток.
 */
enum class DefaultPlaybackQuality(
    val key: String,
    /** null — без потолка (настоящий «Авто»). */
    val maxHeight: Int?,
    val title: String,
) {
    AUTO("auto", null, "Авто"),
    P1080("1080", 1080, "До 1080p"),
    P720("720", 720, "До 720p"),
    P480("480", 480, "До 480p"),
    P360("360", 360, "До 360p"),
    LOWEST("lowest", 0, "Минимальное");

    companion object {
        fun fromKey(key: String?): DefaultPlaybackQuality =
            entries.firstOrNull { it.key == key || it.name == key } ?: P720
    }
}
