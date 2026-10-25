package dev.tgfeed.core.feed

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
 * 搜索索引构建前台服务（R45 结构修复，根因 R4）。
 *
 * 职责只有一件事：索引 bulk 构建期把进程挂进前台——App 退后台/熄屏后爬取协程
 * 继续跑。爬取本体在 FeedViewModel 的协程里（断点页级落盘，随时可中断续爬），
 * 本服务只是"保活挂牌"壳，与 SystemFloatingService 同一先例。
 * 爬取队列清空即摘牌停止，不常驻。
 *
 * 通知纪律：IMPORTANCE_MIN 静默渠道——不出声、不震动、不占状态栏图标区，
 * 只在通知栏最底部折叠显示"正在构建搜索索引 x/y"。点通知本体回 App。
 */
class IndexCrawlService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val done = intent?.getIntExtra(EXTRA_DONE, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
        // startForegroundService 拉起后约 5 秒内必须挂牌，先挂再说；
        // 极端时序（系统限速重启）下 startForeground 可能抛异常，宁可丢挂牌不崩服务。
        // 重复调用幂等：同 NOTIFICATION_ID 覆盖更新即进度刷新
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(done, total),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.onFailure {
            android.util.Log.w(TAG, "前台挂牌失败: ${it.message}")
        }
        // 2026-10-17 P0 热修（用户平板重启循环实锤）：START_STICKY→NOT_STICKY。
        // 爬虫服务禁止系统级自动复活（重启后系统恢复前台 app+sticky 服务双重复活=
        // 开机风暴循环——100% 占空比 73s 顶崩老设备）。爬虫的正确重启路径=
        // App 冷启链路主动判断后调度（R208 全套治理：开机静默期+负载闸）。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划卡清任务 = 用户明确不要了：摘牌停服务（爬取断点已按页落盘，下次启动续爬）
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** 最简静默通知：一行标题 + 进度文案，无任何按钮 */
    private fun buildNotification(done: Int, total: Int): android.app.Notification {
        ensureNotificationChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // res 下暂无单色状态栏图标，借系统图标兜底（同 AudioBackgroundService 先例）
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("正在构建搜索索引")
            .setContentText(if (total > 0) "已处理 $done/$total 个会话" else "正在准备爬取队列…")
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
                NotificationChannel(CHANNEL_ID, "搜索索引构建", NotificationManager.IMPORTANCE_MIN),
            )
        }
    }

    companion object {
        private const val TAG = "IndexCrawlService"
        private const val CHANNEL_ID = "index_crawl"
        private const val NOTIFICATION_ID = 1009
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        /**
         * 有爬取工作即挂牌/刷新进度（重复调用幂等，onStartCommand 重挂牌覆盖更新通知）。
         * 调用方（FeedViewModel 爬取调度）已对调用频率做状态去重，不会每拍都打。
         */
        fun startOrUpdate(context: Context, done: Int, total: Int) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, IndexCrawlService::class.java)
                        .putExtra(EXTRA_DONE, done)
                        .putExtra(EXTRA_TOTAL, total),
                )
            }.onFailure {
                android.util.Log.w(TAG, "前台服务启动被拒: ${it.message}")
            }
        }

        /** 爬取队列清空后摘牌；服务未运行时为无操作 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, IndexCrawlService::class.java)) }
        }
    }
}
