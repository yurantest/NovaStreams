package com.novastreams.app.core.twitchapi

/**
 * Превью эфира Twitch лежит по постоянному адресу и обновляется раз в ~5 минут. Если на момент первого запроса
 * превью ещё не готово, Twitch отдаёт свою заглушку (камера), и она застревает в кэше картинок под тем же адресом.
 * Добавляем «номер пятиминутки», чтобы адрес менялся вместе с превью и заглушка/старый кадр не залипали.
 */
internal fun freshPreview(url: String): String {
    if (url.isBlank()) return url
    val bucket = System.currentTimeMillis() / 300_000L
    return url + (if (url.contains('?')) "&" else "?") + "t=" + bucket
}
