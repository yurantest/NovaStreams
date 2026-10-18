package com.novastreams.app.core.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Включает и выключает периодическую проверку «кто из подписок вышел в эфир».
 * WorkManager не умеет чаще, чем раз в 15 минут, и Android может откладывать запуск (экономия батареи),
 * поэтому уведомления приходят с задержкой, а не мгновенно.
 */
object NotificationScheduler {

    private const val WORK_NAME = "followed_live_check"

    fun apply(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // Периодически в фоне (даже когда UI закрыт). Android может сдвигать интервал.
            val periodic = PeriodicWorkRequestBuilder<FollowedLiveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            // Сразу одна проверка (не ждать 15 минут после включения).
            val once = OneTimeWorkRequestBuilder<FollowedLiveWorker>()
                .setConstraints(constraints)
                .build()
            manager.enqueueUniqueWork(WORK_NAME + "_once", ExistingWorkPolicy.REPLACE, once)
        } else {
            manager.cancelUniqueWork(WORK_NAME)
            manager.cancelUniqueWork(WORK_NAME + "_once")
        }
    }
}
