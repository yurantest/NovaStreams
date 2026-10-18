package com.novastreams.app.feature.player

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сохранение журналов плеера в файлы: каждый просмотр — отдельный .txt.
 *  - Android 10+ (API 29+): папка «Загрузки» (Download) через MediaStore, разрешения не нужны
 *    (в корень памяти системой писать запрещено).
 *  - Android 9 и ниже: корень внутренней памяти; нужно разрешение WRITE_EXTERNAL_STORAGE.
 */
object PlayerLogExport {

    /** Нужно ли запрашивать разрешение на запись (только Android 9 и ниже). */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Результат: сколько файлов сохранено и куда, либо текст ошибки. */
    fun saveAll(context: Context): String {
        val sessions = PlayerLog.unsaved()
        if (sessions.isEmpty()) return "Нечего сохранять: новых просмотров нет"
        var ok = 0
        var error: String? = null
        for (s in sessions) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(s.startedAtMs))
            val name = "NovaStreams_${stamp}_${s.label}.txt"
            val text = s.lines.joinToString("\n") + "\n"
            try {
                write(context, name, text)
                s.saved = true
                ok++
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
        val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "в папку «Загрузки» (Download)" else "в корень внутренней памяти"
        return if (ok > 0) "Сохранено файлов: $ok $where" + (error?.let { "; ошибка: $it" } ?: "")
        else "Не удалось сохранить: ${error ?: "неизвестная ошибка"}"
    }

    private fun write(context: Context, name: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore не создал файл")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("Не удалось открыть файл для записи")
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory()
            File(root, name).writeText(text)
        }
    }
}
