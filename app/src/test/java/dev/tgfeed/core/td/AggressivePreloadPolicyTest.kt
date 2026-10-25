package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [aggressivePreloadAction]（R195 WiFi 激进整载决策纯函数）+ [aggrStableForMs]
 * （稳定窗）的行为单测。
 *
 * 使命锚定：①带宽跑满（WiFi 下当前视频整文件后台下满）②R166 红线——整载必须
 * 可抢占（seek 抢占闸取消它）可重启（稳定窗后重发）③计量/开关/停播/滑走四路
 * 止损 ④下一条预载（架构师追加：滑到=秒播零加载圈）。
 * 矩阵口径：A 档（当前在播，conns 驱动）× B 档（下一条预载，队列推送驱动）×
 * 完成/兜底/计量/开关/抢占/停滞/停播/作废/槽位。
 */
class AggressivePreloadPolicyTest {

    /** 便捷拼装：默认=A 档健康在播、开关开、非计量、未完成、不在兜底、槽空 */
    private fun facts(
        tier: AggrTier = AggrTier.CURRENT,
        enabled: Boolean = true,
        metered: Boolean = false,
        streaming: Boolean = true,
        unstreamedForMs: Long = 0L,
        stableForMs: Long = AGGR_STABLE_MS,
        completed: Boolean = false,
        inFallback: Boolean = false,
        issued: Boolean = false,
        preemptedAfterIssue: Boolean = false,
        stalledForMs: Long = 0L,
        queued: Boolean = true,
        queuedForMs: Long = 1_000L,
        slotOccupiedByOther: Boolean = false,
        appInForeground: Boolean = true,
    ) = AggrFileFacts(
        tier, enabled, metered, streaming, unstreamedForMs, stableForMs, completed,
        inFallback, issued, preemptedAfterIssue, stalledForMs, queued, queuedForMs,
        slotOccupiedByOther, appInForeground,
    )

    /** 决策入口（纯函数包装，用例只写输入差异与期望动作） */
    private fun act(f: AggrFileFacts): AggrAction = aggressivePreloadAction(f)

    // ---------------- A 档·当前在播 ----------------

    @Test
    fun `A档_起播稳定5秒即启动整载`() {
        assertEquals(AggrAction.START, act(facts(stableForMs = 5_000L)))
        assertEquals(AggrAction.START, act(facts(stableForMs = 60_000L)))
    }

    @Test
    fun `A档_起播未稳不足5秒不启动`() {
        assertEquals(AggrAction.NONE, act(facts(stableForMs = 4_999L)))
        assertEquals(AggrAction.NONE, act(facts(stableForMs = 0L)))
    }

    @Test
    fun `A档_未在播不启动`() {
        assertEquals(AggrAction.NONE, act(facts(streaming = false, unstreamedForMs = 2_000L)))
    }

    @Test
    fun `A档_已发运行中保持`() {
        assertEquals(AggrAction.NONE, act(facts(issued = true, preemptedAfterIssue = false)))
    }

    @Test
    fun `A档_被抢占后未稳不重启_稳定后重启`() {
        // 抢占闸已把整载取消（preemptedAfterIssue=true），但 seek 后窗口未稳
        assertEquals(AggrAction.NONE, act(facts(issued = true, preemptedAfterIssue = true, stableForMs = 4_999L)))
        // 稳定窗到点（无新连接注册/无新抢占）→ 重发
        assertEquals(AggrAction.RESTART, act(facts(issued = true, preemptedAfterIssue = true, stableForMs = 5_000L)))
    }

    @Test
    fun `A档_无钩子外杀_停滞10秒重启`() {
        // 抢占钩子覆盖不到的取消路径（TDLib 内错等）：issued 但连续不活跃 ≥10s
        assertEquals(AggrAction.NONE, act(facts(issued = true, stalledForMs = 9_999L)))
        assertEquals(AggrAction.RESTART, act(facts(issued = true, stalledForMs = 10_000L)))
    }

    @Test
    fun `A档_槽被其他整载占用时发起方等待`() {
        // 全局唯一槽：分屏第二路在播想发整载，但另一文件已在载 → 等
        assertEquals(AggrAction.NONE, act(facts(slotOccupiedByOther = true)))
        assertEquals(
            AggrAction.NONE,
            act(facts(issued = true, preemptedAfterIssue = true, stableForMs = 6_000L, slotOccupiedByOther = true)),
        )
    }

    @Test
    fun `A档_停播超宽限取消_短缝不误杀`() {
        // 滑走/播完（供流归零）>5s：已发取消（保住「滑走后不再磨完」语义）
        assertEquals(AggrAction.CANCEL_STOPPED, act(facts(issued = true, streaming = false, unstreamedForMs = 5_000L)))
        assertEquals(AggrAction.DROP, act(facts(issued = false, streaming = false, unstreamedForMs = 5_000L)))
        // 连接 churn 短缝（<5s）不动
        assertEquals(AggrAction.NONE, act(facts(issued = true, streaming = false, unstreamedForMs = 4_999L)))
        assertEquals(AggrAction.NONE, act(facts(issued = false, streaming = false, unstreamedForMs = 2_000L)))
    }

    // ---------------- 计量 / 开关 ----------------

    @Test
    fun `计量网络_已发即取消_未发不发`() {
        assertEquals(AggrAction.CANCEL_METERED, act(facts(issued = true, metered = true)))
        assertEquals(AggrAction.NONE, act(facts(issued = false, metered = true)))
        assertEquals(AggrAction.NONE, act(facts(issued = false, metered = true, tier = AggrTier.NEXT)))
    }

    @Test
    fun `开关关_已发即取消_未发不发`() {
        assertEquals(AggrAction.CANCEL_DISABLED, act(facts(issued = true, enabled = false)))
        assertEquals(AggrAction.NONE, act(facts(issued = false, enabled = false)))
    }

    // ---------------- 完成 / 兜底 ----------------

    @Test
    fun `文件已完成_已发DONE未发DROP`() {
        assertEquals(AggrAction.DONE, act(facts(issued = true, completed = true)))
        assertEquals(AggrAction.DROP, act(facts(issued = false, completed = true)))
    }

    @Test
    fun `整下兜底表内让位_DROP`() {
        // R166 兜底 p32 拥有该文件：无论是否 issued 安静离场（同锚被兜底整载合并）
        assertEquals(AggrAction.DROP, act(facts(issued = true, inFallback = true)))
        assertEquals(AggrAction.DROP, act(facts(issued = false, inFallback = true)))
    }

    // ---------------- B 档·下一条预载 ----------------

    @Test
    fun `B档_在队前台槽空即启动`() {
        // B 档条目不在播（在播即接管升 A 档，由循环同步层处理）
        assertEquals(AggrAction.START, act(facts(tier = AggrTier.NEXT, streaming = false, queued = true, queuedForMs = 1_000L)))
        // 槽被占（当前条整载在飞）：等
        assertEquals(AggrAction.NONE, act(facts(tier = AggrTier.NEXT, streaming = false, slotOccupiedByOther = true)))
        // 退后台：不预载（R158 P0-2 后台静默哲学）
        assertEquals(AggrAction.NONE, act(facts(tier = AggrTier.NEXT, streaming = false, appInForeground = false)))
    }

    @Test
    fun `B档_滑走作废_已发取消未发摘牌`() {
        // 新推送不含它（滑走/换目标）：issued → 取消（trace aggrInvalidate）；未发 → 摘牌
        assertEquals(AggrAction.CANCEL_INVALIDATED, act(facts(tier = AggrTier.NEXT, issued = true, queued = false)))
        assertEquals(AggrAction.DROP, act(facts(tier = AggrTier.NEXT, issued = false, queued = false)))
    }

    @Test
    fun `B档_队列过期作废`() {
        // 离开 Feed 后陈队列（无新推送）：TTL 10min 过期即作废
        assertEquals(
            AggrAction.CANCEL_INVALIDATED,
            act(facts(tier = AggrTier.NEXT, streaming = false, issued = true, queuedForMs = AGGR_NEXT_QUEUE_TTL_MS)),
        )
        assertEquals(
            AggrAction.DROP,
            act(facts(tier = AggrTier.NEXT, streaming = false, issued = false, queuedForMs = AGGR_NEXT_QUEUE_TTL_MS)),
        )
        // 未过期：正常在队条目（槽空闲即照常启动——过期边界差 1ms 不误杀）
        assertEquals(
            AggrAction.START,
            act(facts(tier = AggrTier.NEXT, streaming = false, issued = false, queuedForMs = AGGR_NEXT_QUEUE_TTL_MS - 1)),
        )
    }

    @Test
    fun `B档_槽让位给当前条需求`() {
        // 占槽的 B 档在 A 档需求面前让位（CANCEL_TAKEOVER），已下字节保留
        assertEquals(
            AggrAction.CANCEL_TAKEOVER,
            act(facts(tier = AggrTier.NEXT, issued = true, slotOccupiedByOther = true)),
        )
    }

    @Test
    fun `B档_在播时不发_A档接管`() {
        // 滑到预载条目=开始供流：升级 A 档由循环同步层处理；策略侧 NEXT+streaming 不发
        assertEquals(AggrAction.NONE, act(facts(tier = AggrTier.NEXT, streaming = true)))
    }

    @Test
    fun `B档_计量与开关同门`() {
        assertEquals(AggrAction.CANCEL_METERED, act(facts(tier = AggrTier.NEXT, issued = true, metered = true)))
        assertEquals(AggrAction.CANCEL_DISABLED, act(facts(tier = AggrTier.NEXT, issued = true, enabled = false)))
        assertEquals(AggrAction.NONE, act(facts(tier = AggrTier.NEXT, issued = false, metered = true)))
    }

    @Test
    fun `B档_完成与兜底同A档口径`() {
        assertEquals(AggrAction.DONE, act(facts(tier = AggrTier.NEXT, issued = true, completed = true)))
        assertEquals(AggrAction.DROP, act(facts(tier = AggrTier.NEXT, issued = false, completed = true)))
        assertEquals(AggrAction.DROP, act(facts(tier = AggrTier.NEXT, issued = true, inFallback = true)))
    }

    // ---------------- 稳定窗纯函数 ----------------

    @Test
    fun `稳定窗取新连接注册与抢占的较近者`() {
        // 无抢占：以最近一次连接注册为准（起播/seek 重开都落在新连接上）
        assertEquals(2_000L, aggrStableForMs(nowMs = 10_000L, lastConnMs = 8_000L, lastPreemptMs = 0L))
        // 抢占更近：以抢占为准（seek 跳变重锚=不稳定事件）
        assertEquals(1_000L, aggrStableForMs(nowMs = 10_000L, lastConnMs = 8_000L, lastPreemptMs = 9_000L))
        // 连接注册更近（抢占后用户又 seek 重开新连接）：以连接注册为准
        assertEquals(500L, aggrStableForMs(nowMs = 10_000L, lastConnMs = 9_500L, lastPreemptMs = 9_000L))
    }

    @Test
    fun `稳定窗_新连接注册重置稳定期`() {
        // 播了 60s 无事（稳定 60s），第 58s 新连接注册（seek 重开）→ 稳定期只剩 2s < 5s
        val stable = aggrStableForMs(nowMs = 60_000L, lastConnMs = 58_000L, lastPreemptMs = 0L)
        assertTrue(stable < AGGR_STABLE_MS)
    }

    // ---------------- 整载槽占用判定纯函数 ----------------

    @Test
    fun `槽判定_他者在载且自己未发为占`() {
        assertTrue(aggrSlotOccupiedFor(AggrTier.CURRENT, issuedSelf = false, issuedOther = true, currentWantsSlot = false))
        assertTrue(aggrSlotOccupiedFor(AggrTier.NEXT, issuedSelf = false, issuedOther = true, currentWantsSlot = false))
        // 自持者不算「被占」（持槽者视角；双 issued 是循环不该出现的态，函数侧保守放行）
        assertTrue(!aggrSlotOccupiedFor(AggrTier.NEXT, issuedSelf = true, issuedOther = true, currentWantsSlot = false))
        assertTrue(!aggrSlotOccupiedFor(AggrTier.CURRENT, issuedSelf = true, issuedOther = true, currentWantsSlot = false))
    }

    @Test
    fun `槽判定_A档意向面前B档全视为占_未发不抢槽`() {
        // 真机实锤的乒乓根因：B1 让位后同拍 B2 抢槽——A 档意向期间 B 档一律视占
        assertTrue(aggrSlotOccupiedFor(AggrTier.NEXT, issuedSelf = false, issuedOther = false, currentWantsSlot = true))
        assertTrue(aggrSlotOccupiedFor(AggrTier.NEXT, issuedSelf = true, issuedOther = false, currentWantsSlot = true))
        // A 档自己（持槽或发起）不受 A 档意向影响
        assertTrue(!aggrSlotOccupiedFor(AggrTier.CURRENT, issuedSelf = true, issuedOther = false, currentWantsSlot = true))
        assertTrue(!aggrSlotOccupiedFor(AggrTier.CURRENT, issuedSelf = false, issuedOther = false, currentWantsSlot = true))
    }

    @Test
    fun `槽判定_无人持有且A档无意向为空`() {
        assertTrue(!aggrSlotOccupiedFor(AggrTier.NEXT, issuedSelf = false, issuedOther = false, currentWantsSlot = false))
        assertTrue(!aggrSlotOccupiedFor(AggrTier.CURRENT, issuedSelf = false, issuedOther = false, currentWantsSlot = false))
    }
}
