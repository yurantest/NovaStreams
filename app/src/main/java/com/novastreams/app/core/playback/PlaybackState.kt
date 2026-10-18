package com.novastreams.app.core.playback

/**
 * Состояние воспроизведения.
 *
 * Первый этап: реального воспроизведения нет, плеер показывает честную заглушку.
 * План: Media3/ExoPlayer + HLS-источник, полученный поддерживаемым Twitch способом
 * (проверить актуальные условия использования и документацию перед реализацией).
 */
sealed interface PlaybackState {
    data object NotAvailable : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data class Error(val message: String) : PlaybackState
}

object PlaybackSupport {
    /** true, когда подключено реальное воспроизведение. */
    const val isRealPlaybackAvailable: Boolean = false
}
