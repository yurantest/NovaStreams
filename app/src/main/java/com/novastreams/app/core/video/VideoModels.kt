package com.novastreams.app.core.video

/** Что именно воспроизводим: запись эфира (VOD) или клип. */
enum class VideoKind(val routeName: String) {
    VOD("vod"),
    CLIP("clip");

    companion object {
        fun fromRoute(name: String?): VideoKind = if (name == CLIP.routeName) CLIP else VOD
    }
}

/** Карточка записи или клипа в списке канала. [id] — номер записи или slug клипа. */
data class ChannelVideo(
    val id: String,
    val kind: VideoKind,
    val title: String,
    val durationSec: Int,
    val views: Int,
    /** Дата публикации в виде ДД.ММ.ГГГГ (пусто, если Twitch не прислал). */
    val dateText: String,
    val thumbnailUrl: String?,
    val categoryName: String,
    /** Только клипы: кто создал клип. */
    val curator: String = "",
    val artSeed: Int,
)

/** Порция списка и курсор следующей (null — больше нет). */
class VideosPage(val items: List<ChannelVideo>, val cursor: String?)

/** Период для списка клипов. */
enum class ClipPeriod(val gql: String, val title: String) {
    WEEK("LAST_WEEK", "Неделя"),
    MONTH("LAST_MONTH", "Месяц"),
    ALL("ALL_TIME", "Всё время"),
}

/** Готовый к воспроизведению источник. */
sealed interface ResolvedVideo {
    /** HLS-плейлист записи эфира. */
    data class Hls(val url: String) : ResolvedVideo

    /** Клип: несколько mp4 разного качества (от лучшего к худшему). */
    data class Files(val variants: List<Variant>) : ResolvedVideo

    data class Variant(val label: String, val url: String)
}

fun formatDuration(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}
