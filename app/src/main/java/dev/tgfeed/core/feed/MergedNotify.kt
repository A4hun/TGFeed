package dev.tgfeed.core.feed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.tgfeed.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R148 订阅更新汇总通知中枢：到货（需求订阅关键词命中，原 DemandSubscription 侧）与
 * 追更（订阅频道新帖，原 FeedFollowPart 侧）合并为**同一个渠道、同一条汇总通知**。
 *
 * 用户口径（R148 投诉②）："到货通知跟追更通知完全可以合并，他俩有点相（像）"——
 * 旧实现两条渠道（demand_arrivals / follow_updates）各弹各的，同一时刻抽屉里可能出现
 * 两条语义重叠的通知。本中枢收口为：
 *  - 单渠道：沿用追更渠道 id [CHANNEL_ID]（老用户已存在的渠道偏好无缝继承），显示名
 *    改"订阅更新"；旧"到货通知"渠道（demand_arrivals）在 attach 时删除，系统设置里
 *    不再出现第二个开关；
 *  - 单通知：固定 [SUMMARY_ID] 一条汇总通知，任一侧来新事件即整条覆盖更新
 *    （"3 个频道有更新 · 2 条你订阅的关键词到货"式标题 + 明细大文本），不堆叠。
 *
 * 累积口径（"汇总"的语义）：
 *  - 会话内存累积两侧事件（频道更新按 chatId 覆盖、到货按词覆盖，容量上限掐最老）；
 *  - **通知已不在抽屉 = 旧账清零**：发新通知前查 activeNotifications——上一条被点开
 *    （autoCancel）或划走（delete）后，本次只报新事件，绝不把用户已处理过的旧事件
 *    换个数字再喊一遍（重新弹已划掉的内容=变相轰炸，反目标）；
 *  - 事件带时间戳，超 [STALE_MS]（24h）自然淘汰，防极端场景下账目无限挂账；
 *  - 进程重启丢内存账目：可接受——两侧各自的去重/基线账本（DemandSubscription
 *    notified 表、LocalStore lastSeenMessageIds）都是落盘的，重启后不会重复触发事件，
 *    只是"重启前未点开的事件"不再计入新通知的汇总数字（抽屉里旧通知仍在，点开照常路由）。
 *
 * 点按路由（"点开进相应页面"）：
 *  - 账内有到货事件 → 沿用到货通知的既有深链口径：ACTION_VIEW + 首条到货 t.me 链接、
 *    显式组件直指 MainActivity（不进系统选择器，走 MainActivity.parseDeepLink 全形态管线）；
 *  - 纯频道更新 → 拉起 MainActivity 落 Feed 聚合首页（追更强信号位会把订阅频道 24h
 *    新帖置顶，见 scanFreshSubscriptionItems），与旧追更通知行为一致。
 *
 * 通知 id 区间规划（全 App 口径，勿冲突）：
 *  - 本汇总：[SUMMARY_ID]=68000（单条固定）；
 *  - 后台音频 1001/1007/1009、索引爬取 1009、digest 7001：不动；
 *  - 那年今日回忆杀 71002（R203，渠道 on_this_day）：独立段（71000 段=digest 族
 *    次位），在下方两清扫区间之外，无需豁免；
 *  - 旧追更段 2000..67535（基数+频道哈希）、旧到货段 70000..70099（基数+词槽位）：
 *    R148 起不再发新通知，attach/每次发通知时清扫抽屉里这两个区间的存量残骸
 *    （digest 7001 数值恰在旧追更段内，清扫时显式跳过，绝不误删每日摘要）。
 *
 * 线程模型：事件入口（追更轮询协程 / 收割派发协程）都在 IO 线程，内部对象锁串行；
 * notify 是 NotificationManager 的线程安全调用。DemandSubscription 的"通知真正发出才
 * 记去重"契约由 [onDemandArrival] 的 Boolean 返回值延续（false = 未发出，调用方回退
 * 去重表下轮补报）。
 */
object MergedNotify {

    private const val TAG = "MergedNotify"

    /** 汇总通知渠道：沿用旧追更渠道 id（继承用户既有渠道级偏好），显示名改为"订阅更新" */
    private const val CHANNEL_ID = "follow_updates"

    /** 汇总通知渠道名（系统设置里看到的名字） */
    private const val CHANNEL_NAME = "订阅更新"

    /** 旧到货渠道 id：attach 时删除（合并后不再有第二个渠道） */
    private const val LEGACY_DEMAND_CHANNEL_ID = "demand_arrivals"

    /**
     * 汇总通知 id：落在旧追更段上界(67535)与旧到货段下界(70000)之间的空档，
     * 与全部既有段零冲突；单条固定 id = 覆盖更新语义（新一轮事件整条刷新，不堆叠）
     */
    private const val SUMMARY_ID = 68000

    /** 旧追更通知 id 段（基数 2000 + 频道哈希 16 位）：清扫抽屉残骸用 */
    private const val LEGACY_FOLLOW_MIN = 2000
    private const val LEGACY_FOLLOW_MAX = 67535

    /** 旧到货通知 id 段（基数 70000 + 词槽位 0..99）：清扫抽屉残骸用 */
    private const val LEGACY_DEMAND_MIN = 70000
    private const val LEGACY_DEMAND_MAX = 70099

    /** 每日 digest 通知 id：数值恰在旧追更段区间内，清扫时必须显式跳过（绝不误删） */
    private const val DIGEST_NOTIFY_ID = 7001

    /** 汇总里频道更新条目上限（超出掐最老，标题/明细都收敛） */
    private const val MAX_FOLLOW_ENTRIES = 12

    /** 汇总里到货词条目上限（超出掐最老） */
    private const val MAX_DEMAND_ENTRIES = 8

    /** 事件自然淘汰窗：超窗旧账不再进汇总（正常路径走"通知不在抽屉即清零"，本窗只是兜底） */
    private const val STALE_MS = 24L * 3600 * 1000

    /** 应用上下文（attach 注入；null = 未挂接，全程静默空转） */
    @Volatile
    private var appContext: Context? = null

    /** 渠道/清扫一次性闸（幂等 attach） */
    private val attached = AtomicBoolean(false)

    /** 事件账本与发通知串行锁（对象锁；notify 本身线程安全，锁只保账目合并的原子性） */
    private val lock = Any()

    /** 追更侧事件（订阅频道有新帖）：chatId → 最新一条（同频道覆盖更新，保留插入序） */
    private val followUpdates = LinkedHashMap<Long, FollowUpdate>()

    /** 到货侧事件（需求订阅词命中）：fold 词 → 最新一批（同词覆盖更新） */
    private val demandHits = LinkedHashMap<String, DemandHit>()

    /** 追更侧事件载荷（pollSubscribedChannelsOnce 每轮收集后整批上交） */
    data class FollowUpdate(
        val chatId: Long,
        val title: String,
        /** 新帖正文摘要（旧实现通知正文同一口径：caption 首行，空回落"有新内容更新"） */
        val text: String,
        val atMs: Long = System.currentTimeMillis(),
    )

    /** 到货侧事件载荷（DemandSubscription 命中词 + 该词本批新链接） */
    data class DemandHit(
        val word: String,
        /** 首条新链接（点按深链目标 + 明细展示名） */
        val firstLink: String,
        /** 本批新链接数（明细"等 N 条"） */
        val linkCount: Int,
        val atMs: Long = System.currentTimeMillis(),
    )

    /**
     * 挂接（幂等）：存 applicationContext + 建渠道（改名"订阅更新"）+ 删旧到货渠道 +
     * 清扫抽屉里旧分体通知残骸。由 DemandSubscription.attach 顺带调用（其挂接点在
     * AppPrefs.init——App.onCreate 即执行，进程启动早期完成迁移清扫，不等首条通知）。
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (!attached.compareAndSet(false, true)) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // 同 id 重复 create 会原地更新显示名（渠道行为项不可改，名称/描述可以）——
            // 老用户看到渠道从"追更"更名为"订阅更新"，渠道级开关偏好原样继承
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
            nm.deleteNotificationChannel(LEGACY_DEMAND_CHANNEL_ID)
            sweepLegacyLocked(nm)
            Log.i(TAG, "汇总通知已挂接：渠道=$CHANNEL_NAME 单条 id=$SUMMARY_ID，旧到货渠道已删除")
        }.onFailure { Log.w(TAG, "汇总通知挂接失败（静默，不影响其余链路）: ${it.message}") }
    }

    /**
     * 追更侧事件上交（pollSubscribedChannelsOnce 一轮的全部"有基线且出新帖"频道，
     * 替代旧版逐频道各发一条通知）。纯尽力而为（旧追更通知同口径）：通知权限没给/
     * 系统异常都静默吞掉，下一轮再试。
     */
    fun onFollowUpdates(updates: List<FollowUpdate>) {
        if (updates.isEmpty()) return
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        runCatching {
            synchronized(lock) {
                resetIfDismissedLocked(nm)
                pruneStaleLocked()
                for (u in updates) followUpdates[u.chatId] = u
                trimFollowLocked()
                postLocked(ctx, nm)
            }
        }.onFailure { Log.w(TAG, "追更汇总通知失败（静默，下轮再试）: ${it.message}") }
    }

    /**
     * 到货侧事件上交（DemandSubscription 命中订阅词的新链接批次）。
     * 返回 false = 通知未真正发出（权限未授予/系统异常），调用方据此回退去重表，
     * 下轮收割还能补报（与旧 notifyArrival 的契约一致）。
     */
    fun onDemandArrival(word: String, freshLinks: List<String>): Boolean {
        if (word.isBlank() || freshLinks.isEmpty()) return false
        val ctx = appContext ?: return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        val posted = runCatching {
            synchronized(lock) {
                resetIfDismissedLocked(nm)
                pruneStaleLocked()
                demandHits[word] = DemandHit(word, freshLinks.first(), freshLinks.size)
                trimDemandLocked()
                postLocked(ctx, nm)
            }
        }.getOrDefault(false)
        if (posted) {
            Log.i(TAG, "到货并入汇总通知：「$word」新链接 ${freshLinks.size} 条")
        } else {
            Log.w(TAG, "到货汇总通知未发出（系统异常），调用方将回退去重下轮补报")
        }
        return posted
    }

    // ---------------- 账本维护（持锁调用） ----------------

    /**
     * 上一条汇总通知已不在抽屉（被点开 autoCancel / 被划走删除）→ 旧账清零：
     * 本次只报新事件。免广播接收器（deleteIntent 需要注册接收器/manifest 改动），
     * 用发通知前现查 activeNotifications 达成同语义；代价是"点开之后、下一条之前"
     * 的窗口内旧账仍在内存——但那窗口里没有任何可见通知，无感知。
     */
    private fun resetIfDismissedLocked(nm: NotificationManager) {
        val active = nm.activeNotifications
        val stillThere = active.any { it.id == SUMMARY_ID }
        if (!stillThere && (followUpdates.isNotEmpty() || demandHits.isNotEmpty())) {
            followUpdates.clear()
            demandHits.clear()
            Log.d(TAG, "上一条汇总通知已不在抽屉：旧账清零，本次只报新事件")
        }
        // 顺带清旧分体通知残骸（R148 前版本遗留），幂等
        sweepLegacyLocked(nm)
    }

    /** 旧分体通知清扫：抽屉里属于旧追更/旧到货 id 段的残骸撤下（digest 7001 显式跳过） */
    private fun sweepLegacyLocked(nm: NotificationManager) {
        runCatching {
            for (sbn in nm.activeNotifications) {
                val id = sbn.id
                val legacy = (id in LEGACY_FOLLOW_MIN..LEGACY_FOLLOW_MAX) ||
                    (id in LEGACY_DEMAND_MIN..LEGACY_DEMAND_MAX)
                if (legacy && id != DIGEST_NOTIFY_ID && id != SUMMARY_ID) {
                    nm.cancel(id)
                    Log.i(TAG, "清扫旧版分体通知残骸 id=$id（R148 已合并为单条汇总通知）")
                }
            }
        }
    }

    /** 事件自然淘汰：超 [STALE_MS] 的旧账丢弃（"通知不在抽屉即清零"主路径的兜底） */
    private fun pruneStaleLocked() {
        val now = System.currentTimeMillis()
        if (followUpdates.isNotEmpty()) {
            followUpdates.values.removeAll { now - it.atMs > STALE_MS }
        }
        if (demandHits.isNotEmpty()) {
            demandHits.values.removeAll { now - it.atMs > STALE_MS }
        }
    }

    private fun trimFollowLocked() {
        while (followUpdates.size > MAX_FOLLOW_ENTRIES) {
            followUpdates.remove(followUpdates.keys.first())
        }
    }

    private fun trimDemandLocked() {
        while (demandHits.size > MAX_DEMAND_ENTRIES) {
            demandHits.remove(demandHits.keys.first())
        }
    }

    // ---------------- 通知构建与点按路由（持锁调用） ----------------

    /**
     * 发/覆盖更新汇总通知（账本已合并完毕）。返回 false = 未能真正发出（notify 异常）。
     * 标题 = 计数摘要（"3 个频道有更新 · 2 条你订阅的关键词到货"，只报非零段）；
     * 正文 = 前两行明细（折叠态），BigTextStyle 展开全量明细；点按路由见类头注释。
     */
    private fun postLocked(ctx: Context, nm: NotificationManager): Boolean {
        val follows = followUpdates.values.toList()
        val demands = demandHits.values.toList()
        if (follows.isEmpty() && demands.isEmpty()) return false
        val title = buildTitle(follows.size, demands.size)
        val lines = buildDetailLines(follows, demands)
        val collapsed = lines.take(2).joinToString("；") + if (lines.size > 2) " 等" else ""
        // 点按路由：有到货 → 深链直达首条到货内容（ACTION_VIEW + 显式组件，不进选择器）；
        // 纯频道更新 → 拉起 MainActivity 落 Feed（追更置顶区承接"看新帖"诉求）
        val tapIntent = if (demands.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(demands.first().firstLink), ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            SUMMARY_ID,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            // 项目 res 下暂无单色状态栏图标（同旧追更/到货/digest 的既有口径），借系统图标兜底
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(SUMMARY_ID, notification) }
            .onFailure { Log.w(TAG, "汇总通知 notify 失败: ${it.message}") }
            .isSuccess
    }

    /** 标题计数摘要：非零段拼接（"N 个频道有更新 · M 条你订阅的关键词到货"） */
    private fun buildTitle(followCount: Int, demandCount: Int): String {
        val parts = ArrayList<String>(2)
        if (followCount > 0) parts += if (followCount == 1) "1 个频道有更新" else "$followCount 个频道有更新"
        if (demandCount > 0) {
            parts += if (demandCount == 1) "你订阅的关键词到货了" else "$demandCount 条你订阅的关键词到货"
        }
        return parts.joinToString(" · ")
    }

    /** 明细行（BigText 全量）：频道行在前（关系更长期），到货行在后（更紧急的行动项） */
    private fun buildDetailLines(follows: List<FollowUpdate>, demands: List<DemandHit>): List<String> {
        val out = ArrayList<String>(follows.size + demands.size)
        for (f in follows) {
            val name = f.title.ifBlank { "订阅频道" }
            out += if (f.text.isBlank()) "「$name」有更新" else "「$name」${f.text}"
        }
        for (d in demands) {
            val label = linkLabel(d.firstLink)
            out += if (d.linkCount > 1) "「${d.word}」$label 等 ${d.linkCount} 条" else "「${d.word}」$label"
        }
        return out
    }

    /** 链接展示名：@用户名 优先，其次频道 id，最后裸链接（与搜索页答案区块同口径） */
    private fun linkLabel(link: String): String {
        val username = AnswerStore.usernameFromLink(link)
        if (username.isNotBlank()) return "@$username"
        val chatId = AnswerStore.chatIdFromLink(link)
        return if (chatId != 0L) "频道 $chatId" else link
    }
}
