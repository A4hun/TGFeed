package dev.tgfeed.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** R420 后台定时同步调度：按设置间隔排唯一周期任务；0=关闭即取消 */
object BackgroundSyncScheduler {
    private const val UNIQUE = "r420_bg_sync"

    fun apply(context: Context, rawMinutes: Int) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val minutes = BackgroundSyncPolicy.normalizeMinutes(rawMinutes)
        if (!BackgroundSyncPolicy.shouldSchedule(rawMinutes)) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
