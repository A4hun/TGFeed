package dev.tgfeed.core.digest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.core.app.NotificationCompat
import dev.tgfeed.MainActivity
import dev.tgfeed.core.diag.DiagLog
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.store.SubscribedChatsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * R63 每日 digest 推送（留存钩子：傍晚一条"你追的频道今天有更新"系统通知拉回 app）。
 *
 * 数据口径（诚实、只读）：
 *  - 订阅集合：直读 filesDir/store/subscriptions.json 的 chatId（LocalStore 是该文件的
 *    唯一写入方且归 FeedViewModel 持有，本模块只读解析、不建第二个 LocalStore 实例；
 *    R203 起解析收口共享件 SubscribedChatsReader，与那年今日族同一口径）；
 *  - 新内容计数：直连深度搜索索引库 filesDir/deepindex/search.db（DeepSearchIndex 本体
 *    归 FeedViewModel 私有，本模块按既有 DB 路径与表结构只读查询；库不存在/查询失败按 0 处理）。
 *    "今天有更新" = 订阅频道里 dateEpochSec（消息发布时间，索引条目唯一时间戳）落在
 *    今天 00:00 之后的已索引条数；索引没覆盖的频道、还没爬到的新内容天然不纳入——
 *    宁少报不虚报。
 *  - 文案脱敏：通知只有条数与品牌，不出现任何频道名/内容标题。
 *
 * 调度（最简可靠方案，项目无 WorkManager 依赖）：
 *  app 进程内协程——启动即补查一次"今天是否该发"，再睡到下一个闸点（默认 20:00，
 *  AppPrefs.digestHour 可调）醒来自查，循环往复。已知局限：app 整天不打开就不发
 *  （冷启动才检查），可接受；进程被杀则当日闹钟随之消失，下次启动补查兜底。
 *
 * 幂等：通知真正发出才把 AppPrefs.lastDigestDate 落为当天，当日重复触发只打判定日志不重发；
 * 无新内容/无权限/开关关闭时不落日期（补报机会留给下一次触发）。
 * 全程异常自吞：留存钩子绝不能拖累启动链或崩溃。
 */
class DailyDigest(
    private val context: Context,
    private val prefs: AppPrefs,
) {

    /**
     * 启动调度（由 AppContainer.init 调一次）：启动补查 + 每日闸点循环。
     * 循环挂在传入的 app 级 scope 上，随进程存活；delay 不持锁不占线程。
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            checkOnce("启动补查")
            while (true) {
                delay(msUntilNextGate())
                checkOnce("到点检查")
            }
        }
    }

    /** 单次判定：开关/当日已发/到点/有内容 四闸全过才发；判定行进 logcat（验证口径：条数+是否发） */
    private suspend fun checkOnce(reason: String) = withContext(Dispatchers.IO) {
        runCatching {
            if (!prefs.digestEnabled) {
                DiagLog.log(TAG, "digest 判定[$reason]：开关关闭，跳过")
                return@runCatching
            }
            val today = LocalDate.now().toString()
            val last = prefs.lastDigestDate
            val gateHour = prefs.digestHour
            val due = LocalTime.now().hour >= gateHour
            if (!due || last == today) {
                DiagLog.log(
                    TAG,
                    "digest 判定[$reason]：今天=$today 上次=${last.ifEmpty { "从未" }} " +
                        "闸点=${gateHour}点 到点=$due 当日已发=${last == today} → 不发",
                )
                return@runCatching
            }
            val count = countTodayNewItems(todayStartEpochSec())
            if (count <= 0) {
                DiagLog.log(TAG, "digest 判定[$reason]：订阅频道今日新索引 0 条 → 不发")
                return@runCatching
            }
            val posted = postDigest(count)
            DiagLog.log(
                TAG,
                "digest 判定[$reason]：订阅频道今日新索引 $count 条 → " +
                    if (posted) "已发通知" else "通知未发出（无通知权限或系统异常）",
            )
            if (posted) prefs.lastDigestDate = today // 幂等闸：发出才落日期，当日重触发直接跳过
        }.onFailure {
            DiagLog.log(TAG, "digest 判定[$reason]异常（静默跳过）: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 距下一个闸点的毫秒数（今天闸点已过则取明天闸点） */
    private fun msUntilNextGate(): Long {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val gateHour = prefs.digestHour
        val gateToday = LocalDate.now().atTime(gateHour, 0).atZone(zone).toInstant().toEpochMilli()
        val target = if (gateToday > nowMs) {
            gateToday
        } else {
            LocalDate.now().plusDays(1).atTime(gateHour, 0).atZone(zone).toInstant().toEpochMilli()
        }
        // 兜底最小 1 分钟：防时区/夏令时边界算出 0 或负数导致忙等
        return (target - nowMs).coerceAtLeast(60_000L)
    }

    /** 今天 00:00 的 epoch 秒（本机时区，与 items.dateEpochSec 的 UTC 秒口径可比） */
    private fun todayStartEpochSec(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    /**
     * 统计订阅频道今日新索引条数（纯读，IO 线程）：
     * chatId 分块拼 IN（块内 ≤500，远离老设备 999 占位符上限）。
     * R428 改 OPEN_READONLY：本模块只发 SELECT 绝不写库，与 FeedFirstSeenPart
     * 首发溯源同库（deepindex/search.db）只读句柄同款——只读句柄绝不触发
     * WAL 恢复/建 -shm 的写路径，也杜绝误写风险。
     */
    private fun countTodayNewItems(sinceEpochSec: Long): Int {
        val chatIds = loadSubscribedChatIds()
        if (chatIds.isEmpty()) return 0
        val dbFile = File(context.filesDir, "deepindex/search.db")
        if (!dbFile.isFile) return 0 // 索引从未建立：不开库不建库（同 DeepSearchIndex.hasIndex 口径）
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            var total = 0
            // R266 v11 双臂（D4 现状口径：每次转发各计一份——今日新转发也算新内容）：
            // items 臂 + refs 臂（repost_refs）同形各查，Kotlin 侧相加；refs 臂独立
            // 容错（升级窗口期 v10 库文件无此表——缺席=存量形态直通）
            val hasRefs = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='repost_refs'",
                null,
            ).use { it.moveToFirst() }
            for (chunk in chatIds.chunked(500)) {
                val args = ArrayList<String>(chunk.size + 1)
                args += sinceEpochSec.toString()
                for (id in chunk) args += id.toString()
                val sql = "SELECT COUNT(*) FROM items WHERE dateEpochSec>=? AND chatId IN(" +
                    chunk.joinToString(",") { "?" } + ")"
                db.rawQuery(sql, args.toTypedArray()).use { c ->
                    if (c.moveToFirst()) total += c.getInt(0)
                }
                if (hasRefs) {
                    runCatching {
                        val rSql = "SELECT COUNT(*) FROM repost_refs WHERE dateEpochSec>=? AND chatId IN(" +
                            chunk.joinToString(",") { "?" } + ")"
                        db.rawQuery(rSql, args.toTypedArray()).use { c ->
                            if (c.moveToFirst()) total += c.getInt(0)
                        }
                    }
                }
            }
            return total
        } finally {
            runCatching { db.close() }
        }
    }

    /** 订阅频道 chatId 集合：SubscribedChatsReader 共享只读器（R203 收敛，解析口径不变） */
    private fun loadSubscribedChatIds(): List<Long> = SubscribedChatsReader.load(context)

    /**
     * 发 digest 系统通知：独立渠道（用户可在系统设置里单独关掉），点击拉起 MainActivity。
     * 未授予通知权限（R52 起只问一次，没授权不追问）时静默不发，返回 false
     * （调用方据此不落当日日期，授权后下次触发还能补报）。
     */
    private fun postDigest(count: Int): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        // minSdk 26，渠道 API 原生可用；IMPORTANCE_DEFAULT 与追更/到货通知同档（有横幅与提示音）
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "每日摘要", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (!nm.areNotificationsEnabled()) return false
        val pi = PendingIntent.getActivity(
            context,
            NOTIFY_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // 项目 res 下暂无单色状态栏图标（同追更/后台音频的既有口径），借系统图标兜底
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("你追的频道今天有更新")
            .setContentText("今天更新了 $count 条新内容，来看看")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(NOTIFY_ID, notification) }.isSuccess
    }

    companion object {
        private const val TAG = "DailyDigest"

        /** 通知渠道 id：独立于追更(follow_updates)/到货(demand_arrivals)，系统设置里可单独关 */
        private const val CHANNEL_ID = "daily_digest"

        /** 通知 id：固定单条（当天重发语义 = 覆盖更新同一条）；避开音频 1001/追更 2000 段/到货 6000 段 */
        private const val NOTIFY_ID = 7001
    }
}
