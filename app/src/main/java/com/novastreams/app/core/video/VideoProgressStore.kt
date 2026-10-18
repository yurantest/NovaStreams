package com.novastreams.app.core.video

import android.content.Context

/** Запоминает, где пользователь остановился в записи эфира (только на этом устройстве). */
class VideoProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("video_progress", Context.MODE_PRIVATE)

    fun get(videoId: String): Long = prefs.getLong(videoId, 0L)

    fun put(videoId: String, positionMs: Long) {
        // Хранилище не растёт бесконечно: храним не больше ~200 записей.
        if (prefs.all.size > 200) prefs.edit().clear().apply()
        if (positionMs <= 0L) prefs.edit().remove(videoId).apply()
        else prefs.edit().putLong(videoId, positionMs).apply()
    }
}
