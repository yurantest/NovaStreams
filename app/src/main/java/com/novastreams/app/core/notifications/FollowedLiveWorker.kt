package com.novastreams.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novastreams.app.MainActivity
import com.novastreams.app.appContainer
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.twitchapi.ApiException
import kotlinx.coroutines.flow.first

/**
 * Раз в ~15 минут спрашивает у Twitch, какие подписки в эфире, и сообщает о новых.
 * Первый запуск после включения «тихий»: он только запоминает, кто уже в эфире.
 */
class FollowedLiveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()

        if (!container.settings.notificationsEnabled.first()) return Result.success()
        if (container.auth.state.value !is AuthState.SignedIn) return Result.success()
        // Без разрешения на уведомления (Android 13+) смысла проверять нет.
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()

        val live = try {
            container.twitch.followedLive()
        } catch (e: ApiException) {
            // Временная ошибка (нет сети, лимит запросов) — попробуем в следующий раз.
            return Result.retry()
        }

        val previous = container.settings.notifiedStreamIds()
        val currentIds = live.map { it.id }.toSet()

        if (previous != null) {
            val fresh = live.filter { it.id !in previous }
            if (fresh.isNotEmpty()) {
                createChannel()
                // Отдельные уведомления для первых трёх, остальные — одним сообщением.
                fresh.take(MAX_SEPARATE).forEach { stream ->
                    notify(
                        id = stream.id.hashCode(),
                        title = "${stream.channel.displayName} в эфире",
                        text = "${stream.title} · ${stream.categoryName}",
                    )
                }
                if (fresh.size > MAX_SEPARATE) {
                    notify(
                        id = SUMMARY_ID,
                        title = "Ещё ${fresh.size - MAX_SEPARATE} каналов в эфире",
                        text = "Откройте приложение, чтобы посмотреть подписки",
                    )
                }
            }
        }
        // Запоминаем текущих: ушёл в офлайн и вернулся — уведомим снова.
        container.settings.setNotifiedStreamIds(currentIds)
        return Result.success()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        // HIGH + звук по умолчанию — видно в шторке и слышно, когда приложение закрыто.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Эфиры подписок",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Когда канал из подписок выходит в эфир"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notify(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            applicationContext,
            id,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.novastreams.app.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
        } catch (e: SecurityException) {
            // Разрешение отозвано между проверкой и отправкой — пропускаем.
        }
    }

    private companion object {
        const val CHANNEL_ID = "followed_live_v2"
        const val MAX_SEPARATE = 3
        const val SUMMARY_ID = 1
    }
}
