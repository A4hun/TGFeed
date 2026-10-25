package dev.tgfeed.ui.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.tgfeed.MainActivity

/**
 * 系统悬浮窗保活前台服务。
 *
 * 职责只有一件事：有系统悬浮窗在飞时把进程挂进前台（退桌面/切别的 App 后，
 * 播放器与 WindowManager 窗口不被系统回收），全部关窗即停。
 * 窗口本体由 [SystemFloatingController] 直接管理——WindowManager 增删 View
 * 不依赖服务存活，本服务只是"保活挂牌"壳。
 *
 * 通知纪律：IMPORTANCE_MIN 静默渠道——不出声、不震动、不进状态栏图标区，
 * 只在通知栏最底部折叠存在（凭证界面彻底沉默原则：别打扰）。点通知本体回 App。
 *
 * 生命周期：onDestroy（含划卡清任务触发的 stopSelf）→ 通知控制器回收全部
 * 悬浮窗与播放器；进程被杀时系统也会自动摘除本进程的所有悬浮窗 View，双保险。
 */
class SystemFloatingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 拉起后约 5 秒内必须挂牌，先挂再说；
        // 极端时序（系统限速重启）下 startForeground 可能抛异常，宁可丢挂牌不崩服务
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }.onFailure {
            android.util.Log.w("SysFloat", "前台挂牌失败: ${it.message}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划卡清任务 = 用户明确不要了：停服务 → onDestroy 回收全部悬浮窗，
        // 防"任务没了悬浮窗还在出声"的幽灵态
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 服务销毁 = 悬浮窗体系整体回收（摘窗 + 播放器还池 + 后台闸豁免复原）
        SystemFloatingController.onServiceDestroyed()
        super.onDestroy()
    }

    /** 最简静默通知：一行标题，无任何按钮 */
    private fun buildNotification(): android.app.Notification {
        ensureNotificationChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // res 下暂无单色状态栏图标，借系统图标兜底（同 AudioBackgroundService 先例）
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("悬浮窗播放中")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                // IMPORTANCE_MIN：静默 + 折叠到通知栏最底部，不占状态栏
                NotificationChannel(CHANNEL_ID, "悬浮窗播放", NotificationManager.IMPORTANCE_MIN),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "system_floating_overlay"
        private const val NOTIFICATION_ID = 1007

        /**
         * 有悬浮窗在飞时拉起（重复调用幂等，onStartCommand 重挂牌覆盖更新）。
         * targetSdk 34 下后台启动 FGS 受限，但本服务几乎总在"用户前台操作开窗"
         * 路径拉起；onStop 自动弹悬浮窗的边角路径有 SYSTEM_ALERT_WINDOW 授权的
         * 后台启动豁免兜底，仍包 runCatching 防崩（起不来则悬浮窗随进程存亡，
         * 不额外报错打扰）。
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SystemFloatingService::class.java),
                )
            }.onFailure {
                android.util.Log.w("SysFloat", "前台服务启动被拒: ${it.message}")
            }
        }

        /** 全部关窗后停服务；未运行时为无操作 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SystemFloatingService::class.java)) }
        }
    }
}
