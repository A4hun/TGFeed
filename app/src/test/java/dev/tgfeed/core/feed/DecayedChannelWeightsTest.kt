package dev.tgfeed.core.feed

import dev.tgfeed.core.store.WatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * [decayedChannelWeights]（FeedSource.kt 顶层纯函数）的行为单测：
 * 权重公式 clamp(0.3 + 衰减加权平均观看比例 × 0.7 + 互动加分, 0.3, 2.0)。
 *
 * 覆盖：时间衰减曲线、skip 负贡献 clamp、7 天过期回落探索兜底、
 * 互动加分叠加与封顶、脏数据（ts≤0 / 未来时间戳）防御、WEIGHT_SAMPLE=50 截断。
 */
class DecayedChannelWeightsTest {

    private companion object {
        /** 与被测实现同口径的衰减常数（λ=7 天），用于构造边界时刻 */
        const val DECAY_MS = 7L * 24 * 3600 * 1000

        /** 浮点比较容差 */
        const val EPS = 1e-9

        const val NOW = 1_000_000_000_000L // 固定基准时刻，测试可重复
    }

    private fun ev(chatId: Long, watchSec: Int, durationSec: Int, ts: Long) =
        WatchEvent(chatId = chatId, watchSec = watchSec, durationSec = durationSec, ts = ts)

    /** 看完一整条（ratio=1.0）的事件 */
    private fun fullWatch(chatId: Long, ts: Long) = ev(chatId, 30, 30, ts)

    /** 秒划事件（watchSec < SKIP_FAST_SEC，额外倒扣 0.5） */
    private fun skip(chatId: Long, ts: Long) = ev(chatId, 1, 30, ts)

    @Test
    fun `空事件且无互动加分返回空表`() {
        // 行为：没有任何输入信号时不产出权重条目（调用方按 EXPLORE_WEIGHT 缺省）
        assertEquals(emptyMap<Long, Double>(), decayedChannelWeights(emptyList(), NOW))
    }

    @Test
    fun `单个新鲜完播事件权重为满值 1`() {
        // 行为：ratio=1.0 的完播事件 → avg=1 → 基础权重 0.3+1×0.7=1.0（单事件 decay 分子分母抵消）
        val w = decayedChannelWeights(listOf(fullWatch(1L, NOW)), NOW)
        assertEquals(1.0, w.getValue(1L), EPS)
    }

    @Test
    fun `时间衰减让新鲜行为的权重影响大于陈旧行为`() {
        // 行为：同样一组"完播+秒划"，完播在近端（decay≈1）秒划在 6 天前（decay≈0.42）的频道，
        // 权重必须高于"秒划在近端、完播在 6 天前"的频道——越新的行为越代表当前口味。
        val sixDaysAgo = NOW - 6L * 24 * 3600 * 1000
        val w = decayedChannelWeights(
            listOf(
                fullWatch(1L, NOW), skip(1L, sixDaysAgo), // 频道1：近端完播
                skip(2L, NOW), fullWatch(2L, sixDaysAgo), // 频道2：近端秒划
            ),
            NOW,
        )
        assertTrue(
            "近端完播频道 ${w.getValue(1L)} 应显著高于近端秒划频道 ${w.getValue(2L)}",
            w.getValue(1L) > w.getValue(2L),
        )
        // 数值精确复核（按公式手算 decay 加权平均）
        val dFresh = 1.0
        val dOld = exp(-(6.0 * 24 * 3600 * 1000) / DECAY_MS)
        val avg1 = (dFresh * 1.0 + dOld * -0.5) / (dFresh + dOld)
        assertEquals(0.3 + avg1 * 0.7, w.getValue(1L), EPS)
    }

    @Test
    fun `skip 事件的负贡献被 clamp 到 0 不击穿权重下限`() {
        // 行为：频道全是近期秒划 → 衰减加權平均为负 → clamp 到 0 → 权重正好落在下限 0.3
        // （负贡献能把 avg 从 1 往下拉，但 avg 本身不低于 0，权重不会击穿 0.3）
        val w = decayedChannelWeights(
            listOf(skip(1L, NOW), skip(1L, NOW - 1000), skip(1L, NOW - 2000)),
            NOW,
        )
        assertEquals(0.3, w.getValue(1L), EPS)
    }

    @Test
    fun `秒划比刚起步的非秒划更伤权重`() {
        // 行为：skip（watchSec<2）按 -0.5 倒扣，比"看了 2 秒就走"（ratio=0.2 中性）更重
        val w = decayedChannelWeights(
            listOf(skip(1L, NOW), ev(2L, watchSec = 2, durationSec = 10, ts = NOW)),
            NOW,
        )
        assertEquals(0.3, w.getValue(1L), EPS)
        assertEquals(0.3 + 0.2 * 0.7, w.getValue(2L), EPS)
        assertTrue(w.getValue(1L) < w.getValue(2L))
    }

    @Test
    fun `最近事件超过 7 天的频道回落探索权重`() {
        // 行为：最新事件在 λ 窗口之外 → 权重过期，整频道回落 EXPLORE_WEIGHT（0.6），
        // 无论历史数据多好看
        val stale = NOW - DECAY_MS - 60_000L
        val w = decayedChannelWeights(listOf(fullWatch(1L, stale), fullWatch(1L, stale - 1000)), NOW)
        assertEquals(EXPLORE_WEIGHT, w.getValue(1L), EPS)
    }

    @Test
    fun `最近事件恰好在 7 天边界内不过期`() {
        // 行为边界：nowMs - latestTs == DECAY_WINDOW_MS 时条件取 `>`，恰好不触发过期回落
        val w = decayedChannelWeights(listOf(fullWatch(1L, NOW - DECAY_MS)), NOW)
        assertEquals(1.0, w.getValue(1L), EPS)
    }

    @Test
    fun `互动加分叠加在基础权重上并封顶 2`() {
        // 行为：满基础权重 1.0 + 加分 1.5 → clamp 到上限 2.0（互动加分恒非负，下限不会击穿）
        val w = decayedChannelWeights(
            listOf(fullWatch(1L, NOW)),
            NOW,
            interactionBoost = mapOf(1L to 1.5),
        )
        assertEquals(2.0, w.getValue(1L), EPS)
    }

    @Test
    fun `只有互动没有观看事件的频道按探索权重打底再叠加`() {
        // 行为："只点赞没看过"的频道按 EXPLORE_WEIGHT 打底 + boost——拿不到正反馈的漏洞被堵上
        val w = decayedChannelWeights(emptyList(), NOW, interactionBoost = mapOf(9L to 0.5))
        assertEquals(EXPLORE_WEIGHT + 0.5, w.getValue(9L), EPS)
    }

    @Test
    fun `过期频道的互动加分叠加在探索权重上`() {
        // 行为：基础权重过期回落 0.6 后仍叠加互动加分（0.6+0.15=0.75），不受 7 天过期影响
        val stale = NOW - DECAY_MS - 60_000L
        val w = decayedChannelWeights(
            listOf(fullWatch(1L, stale)),
            NOW,
            interactionBoost = mapOf(1L to 0.15),
        )
        assertEquals(EXPLORE_WEIGHT + 0.15, w.getValue(1L), EPS)
    }

    @Test
    fun `未来时间戳的事件按最新处理不衰减放大`() {
        // 行为：改了系统时间导致 ts>now 时 decay=exp(正数)>1，封顶 1 防放大——
        // 单事件 avg=1 → 权重 1.0，与"刚刚发生"一致
        val w = decayedChannelWeights(listOf(fullWatch(1L, NOW + 86_400_000L)), NOW)
        assertEquals(1.0, w.getValue(1L), EPS)
    }

    @Test
    fun `无时间戳的脏事件不参与计算`() {
        // 行为：ts<=0 的脏数据跳过；频道最新 ts<=0 时整体视为无有效数据 → 探索权重
        val onlyDirty = decayedChannelWeights(listOf(ev(1L, 30, 30, ts = 0L)), NOW)
        assertEquals(EXPLORE_WEIGHT, onlyDirty.getValue(1L), EPS)
        // 同频道混一条正常事件：脏事件被跳过，只剩正常完播 → 1.0
        val mixed = decayedChannelWeights(
            listOf(ev(1L, 30, 30, ts = 0L), fullWatch(1L, NOW)),
            NOW,
        )
        assertEquals(1.0, mixed.getValue(1L), EPS)
    }

    @Test
    fun `观看比例 clamp 到 1 防止脏数据放大`() {
        // 行为：watchSec > durationSec 的脏记录（结算异常）ratio 封顶 1.0，不产生超满权重
        val w = decayedChannelWeights(listOf(ev(1L, watchSec = 999, durationSec = 10, ts = NOW)), NOW)
        assertEquals(1.0, w.getValue(1L), EPS)
    }

    @Test
    fun `每频道只取最近 50 条事件参与计算`() {
        // 行为：WEIGHT_SAMPLE=50——watch_events 时间倒序（新在前），取前 50 条。
        // 前 50 条全完播、第 51 条起全秒划 → 秒划被截断不生效，权重仍为 1.0；
        // 反转列表（旧数据在前）则前 50 条全是秒划 → 权重 0.3。两次断言共同锁定"取最近 50 条"。
        val newestFirst = (1..50).map { fullWatch(1L, NOW - it * 1000L) } +
            (51..100).map { skip(1L, NOW - it * 1000L) }
        assertEquals(1.0, decayedChannelWeights(newestFirst, NOW).getValue(1L), EPS)

        val oldestFirst = newestFirst.reversed()
        assertEquals(0.3, decayedChannelWeights(oldestFirst, NOW).getValue(1L), EPS)
    }
}
