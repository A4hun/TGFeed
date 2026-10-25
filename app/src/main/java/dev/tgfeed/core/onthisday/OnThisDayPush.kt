package dev.tgfeed.core.onthisday

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.tgfeed.MainActivity
import dev.tgfeed.core.diag.DiagLog
import dev.tgfeed.core.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * R203 RT-2 那年今日回忆杀推送（TL-2 的推送版；DailyDigest R63 骨架照抄）。
 *
 * 三纪律逐条照抄（任务书红线）：
 *  - **调度**：进程内协程——启动补查一次 + 睡到下一个闸点（[AppPrefs.onThisDayPushHour]，
 *    默认 10 点晨间；与 digest 傍晚 20 点错开防同刻双通知）醒来自查，循环往复。
 *    已知局限同 digest：app 整天不打开就不发（冷启动才补查），可接受。
 *  - **幂等**：通知真正发出才把 [AppPrefs.lastOnThisDayPushDate] 落为当天；
 *    无存货/索引未就绪/无权限/开关关都不落日期（补报机会留给下一次触发）。
 *    「每年今日至多 1 条」= 当日单条（多周年有货时 [pickYearForPush] 择一）。
 *  - **文案脱敏**：标题与正文只有年数与条数（「3 年前的今天，你的频道传过 12 条
 *    内容，来看看」）——零频道名零内容标题（R63 digest 先例同红线）。
 *
 * 触发=「有内容的周年日才推」（[MIN_PUSH_ITEMS] 存货闸）；时间索引未就绪
 * （R202 后台构建中）时数据层空手 → 自然不推，建成后下一次触发补上。
 *
 * 点通知 → [MainActivity] 带 [EXTRA_OPEN_ON_THIS_DAY_YEAR] extra 拉起
 * （launchMode=singleTop：冷启 onCreate / 热启 onNewIntent 双路消费），直开
 * TL-2 那年今日页并定位到该周年年轮。
 *
 * 全程异常自吞：留存钩子绝不拖累启动链或崩溃。
 */
class OnThisDayPush(
    private val context: Context,
    private val prefs: AppPrefs,
    private val source: OnThisDaySource,
) {

    /** 启动调度（AppContainer.init 调一次）：启动补查 + 每日闸点循环 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            checkOnce("启动补查")
            while (true) {
                delay(msUntilNextGate())
                checkOnce("到点检查")
            }
        }
    }

    /** 单次判定：开关/当日已发/到点/有内容周年日 四闸全过才发；判定行进 logcat */
    private suspend fun checkOnce(reason: String) = withContext(Dispatchers.IO) {
        runCatching {
            if (!prefs.onThisDayPushEnabled) {
                DiagLog.log(TAG, "那年今日判定[$reason]：开关关闭，跳过")
                return@runCatching
            }
            val today = LocalDate.now().toString()
            val last = prefs.lastOnThisDayPushDate
            val gateHour = prefs.onThisDayPushHour
            val due = LocalTime.now().hour >= gateHour
            if (!due || last == today) {
                DiagLog.log(
                    TAG,
                    "那年今日判定[$reason]：今天=$today 上次=${last.ifEmpty { "从未" }} " +
                        "闸点=${gateHour}点 到点=$due 当日已发=${last == today} → 不发",
                )
                return@runCatching
            }
            val zone = ZoneId.systemDefault()
            val stats = when (val r = source.yearStats(LocalDate.now(), zone)) {
                is OnThisDayOutcome.Unavailable -> {
                    DiagLog.log(TAG, "那年今日判定[$reason]：数据不可用（${r.reason}）→ 不发")
                    return@runCatching
                }
                is OnThisDayOutcome.Ready -> r.value
            }
            val pick = pickYearForPush(stats)
            if (pick == null) {
                DiagLog.log(
                    TAG,
                    "那年今日判定[$reason]：${MAX_YEARS} 个年轮均无存货（<${MIN_PUSH_ITEMS} 条）→ 不发",
                )
                return@runCatching
            }
            val posted = postOnThisDay(pick.yearsAgo, pick.itemCount)
            DiagLog.log(
                TAG,
                "那年今日判定[$reason]：${pick.yearsAgo} 年前窗内 ${pick.itemCount} 条/" +
                    "${pick.channelCount} 个频道 → " +
                    if (posted) "已发通知" else "通知未发出（无通知权限或系统异常）",
            )
            if (posted) prefs.lastOnThisDayPushDate = today // 幂等闸：发出才落日期
        }.onFailure {
            DiagLog.log(TAG, "那年今日判定[$reason]异常（静默跳过）: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 距下一个闸点的毫秒数（今天闸点已过则取明天；兜底 1 分钟防时区边界忙等——digest 同款） */
    private fun msUntilNextGate(): Long {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val gateHour = prefs.onThisDayPushHour
        val gateToday = LocalDate.now().atTime(gateHour, 0).atZone(zone).toInstant().toEpochMilli()
        val target = if (gateToday > nowMs) {
            gateToday
        } else {
            LocalDate.now().plusDays(1).atTime(gateHour, 0).atZone(zone).toInstant().toEpochMilli()
        }
        return (target - nowMs).coerceAtLeast(60_000L)
    }

    /**
     * 发回忆杀通知：独立渠道（系统设置里可单独关），点击带 extra 拉起 MainActivity
     * 直开那年今日页（消费器见 MainActivity onCreate/onNewIntent 的 extra 分支）。
     * 未授权（R52 起只问一次）静默不发返回 false（不落日期，授权后下次触发补报）。
     */
    private fun postOnThisDay(yearsAgo: Int, itemCount: Int): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "那年今日", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (!nm.areNotificationsEnabled()) return false
        val pi = PendingIntent.getActivity(
            context,
            NOTIFY_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_ON_THIS_DAY_YEAR, yearsAgo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // 借系统图标兜底（同 digest/追更口径：res 暂无单色状态栏图标）
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(PUSH_TITLE)
            .setContentText(pushContentText(yearsAgo, itemCount))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(NOTIFY_ID, notification) }.isSuccess
    }

    companion object {
        private const val TAG = "OnThisDayPush"

        /** 通知渠道 id：独立于 digest(daily_digest)/订阅更新(follow_updates)，系统设置单独可关 */
        private const val CHANNEL_ID = "on_this_day"

        /**
         * 通知 id：**71002**（71000 段——digest 7001 段旁的次年今日位）。
         * 刻意避开 MergedNotify 清扫区间（旧追更 2000..67535 / 旧到货 70000..70099），
         * 且 digest 的 7001 落在旧追更段内靠显式跳过保命——本 id 不需要豁免（段位
         * 规划见 MergedNotify 头注释）。
         */
        private const val NOTIFY_ID = 71002

        /**
         * 通知点进直开 TL-2 页的 intent extra：值为周年年轮（1..5）。
         * MainActivity onCreate / onNewIntent 双路消费（singleTop 热启走后者）。
         */
        const val EXTRA_OPEN_ON_THIS_DAY_YEAR = "open_on_this_day_year"
    }
}
