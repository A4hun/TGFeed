package dev.tgfeed.core.tastemap

import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_ARROW_ARM_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_ARROW_GAP_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_CANDIDATE_RING_RADIUS_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_CANDIDATE_RING_STROKE_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_ENGAGE_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_FLY_PER_SCREEN_H
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_MOVE_RATIO
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_PULSE_AMP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_PULSE_BASE
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_PULSE_SPEED
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_RING_RADIUS_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_RING_STROKE_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_SLOP_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ANCHOR_VERTICAL_DOMINANCE
import dev.tgfeed.core.tastemap.GestureFeelPolicy.ARC_HIT_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.CARD_DISMISS_DRAG_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.CRUISE_STOP_MAX
import dev.tgfeed.core.tastemap.GestureFeelPolicy.CRUISE_STOP_MIN
import dev.tgfeed.core.tastemap.GestureFeelPolicy.CRUISE_STOP_WELL_FACTOR
import dev.tgfeed.core.tastemap.GestureFeelPolicy.CRUISE_TAU_SEC
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLING_DECAY_PER_FRAME
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLING_EPS_DEG
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLING_EPS_UNITS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLING_FRAME_MS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLING_TAKEOVER_IDLE_MS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLING_VELOCITY_WINDOW_MS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLY_EVENT_CAP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.FLY_INTO_GATE_UNITS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.GLANCE_STOP_FACTOR
import dev.tgfeed.core.tastemap.GestureFeelPolicy.GLANCE_STOP_MIN
import dev.tgfeed.core.tastemap.GestureFeelPolicy.GLANCE_TAU_SEC
import dev.tgfeed.core.tastemap.GestureFeelPolicy.LENS_MAX
import dev.tgfeed.core.tastemap.GestureFeelPolicy.LENS_MIN
import dev.tgfeed.core.tastemap.GestureFeelPolicy.LENS_STEP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.PINCH_EVENT_FACTOR_MAX
import dev.tgfeed.core.tastemap.GestureFeelPolicy.PINCH_EVENT_FACTOR_MIN
import dev.tgfeed.core.tastemap.GestureFeelPolicy.PINCH_FLY_GAIN
import dev.tgfeed.core.tastemap.GestureFeelPolicy.PINCH_MULT_EVENT_CAP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.PITCH_LIMIT_DEG
import dev.tgfeed.core.tastemap.GestureFeelPolicy.PLANET_HIT_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.STAR_HIT_MIN_DP
import dev.tgfeed.core.tastemap.GestureFeelPolicy.TURN_FULL_FRACTION
import dev.tgfeed.core.tastemap.GestureFeelPolicy.VOYAGE_FAR_UNITS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.VOYAGE_MAX_SEC
import dev.tgfeed.core.tastemap.GestureFeelPolicy.VOYAGE_MIN_SEC
import dev.tgfeed.core.tastemap.GestureFeelPolicy.VOYAGE_NEAR_UNITS
import dev.tgfeed.core.tastemap.GestureFeelPolicy.anchorFlyPerPx
import dev.tgfeed.core.tastemap.GestureFeelPolicy.anchorPulse
import dev.tgfeed.core.tastemap.GestureFeelPolicy.cruiseStop
import dev.tgfeed.core.tastemap.GestureFeelPolicy.decayVel
import dev.tgfeed.core.tastemap.GestureFeelPolicy.glanceStop
import dev.tgfeed.core.tastemap.GestureFeelPolicy.lensClamp
import dev.tgfeed.core.tastemap.GestureFeelPolicy.pinchFlyUnits
import dev.tgfeed.core.tastemap.GestureFeelPolicy.pinchFlyUnitsAlt
import dev.tgfeed.core.tastemap.GestureFeelPolicy.turnDegPerPx
import dev.tgfeed.core.tastemap.GestureFeelPolicy.voyageSec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * R268b 手势手感政策件单测（design-gesture.md §九的数值锚——**防私自调参回归**：
 * 每个常数锚死 R247 用户拍板值；派生函数锚不变量）：
 *  - 横扫 0.7 屏=90° / 张开 10%=72 单位 / 惯性半衰 ≈187ms / 停机阈 / 停船钳位；
 *  - 提案①测速窗：60/120Hz 同手速同初速（手感不分裂）/窗外样本剔除/休止清窗/
 *    首推不计/零间隔防御/环回绕。
 */
class GestureFeelPolicyTest {

    // ==================== 数值锚（R247 拍板值——改动即破坏用户已验收手感） ====================

    @Test
    fun `数值锚-转头与俯仰`() {
        assertEquals(0.7f, TURN_FULL_FRACTION, 0f)
        assertEquals(88f, PITCH_LIMIT_DEG, 0f)
    }

    @Test
    fun `数值锚-捏合飞行`() {
        assertEquals(720f, PINCH_FLY_GAIN, 0f)
        assertEquals(80f, FLY_EVENT_CAP, 0f)
        assertEquals(0.25f, PINCH_EVENT_FACTOR_MIN, 0f)
        assertEquals(4f, PINCH_EVENT_FACTOR_MAX, 0f)
    }

    @Test
    fun `数值锚-锚指飞行`() {
        assertEquals(4800f, ANCHOR_FLY_PER_SCREEN_H, 0f)
        assertEquals(0.3f, ANCHOR_MOVE_RATIO, 0f)
        assertEquals(1.15f, ANCHOR_VERTICAL_DOMINANCE, 0f)
        assertEquals(8f, ANCHOR_SLOP_DP, 0f)
        assertEquals(14f, ANCHOR_ENGAGE_DP, 0f)
    }

    @Test
    fun `数值锚-惯性统一曲线`() {
        assertEquals(0.94f, FLING_DECAY_PER_FRAME, 0f)
        assertEquals(16.7f, FLING_FRAME_MS, 0f)
        assertEquals(4f, FLING_EPS_DEG, 0f)
        assertEquals(30f, FLING_EPS_UNITS, 0f)
        assertEquals(110L, FLING_TAKEOVER_IDLE_MS)
        assertEquals(100L, FLING_VELOCITY_WINDOW_MS)
    }

    @Test
    fun `数值锚-点选热区`() {
        assertEquals(22f, STAR_HIT_MIN_DP, 0f)
        assertEquals(14f, PLANET_HIT_DP, 0f)
        assertEquals(14f, ARC_HIT_DP, 0f)
    }

    @Test
    fun `数值锚-运镜`() {
        assertEquals(4.6f, CRUISE_STOP_WELL_FACTOR, 0f)
        assertEquals(80f, CRUISE_STOP_MIN, 0f)
        assertEquals(260f, CRUISE_STOP_MAX, 0f)
        assertEquals(0.9f, CRUISE_TAU_SEC, 0f)
        assertEquals(0.45f, GLANCE_STOP_FACTOR, 0f)
        assertEquals(120f, GLANCE_STOP_MIN, 0f)
        assertEquals(0.18f, GLANCE_TAU_SEC, 0f)
    }

    // ==================== R342 航行节奏（距离正比时长+下限——用户 20:2x 拍板） ====================

    @Test
    fun `数值锚-R342航行节奏`() {
        assertEquals(1.2f, VOYAGE_MIN_SEC, 0f)
        assertEquals(3.6f, VOYAGE_MAX_SEC, 0f)
        assertEquals(900f, VOYAGE_NEAR_UNITS, 0f)
        assertEquals(8000f, VOYAGE_FAR_UNITS, 0f)
    }

    @Test
    fun `航行-距离正比时长与双端钳位`() {
        // 锚点值：近星 900u=下限 1.2s / 远星 8000u=上限 3.6s（用户口径「近星
        // 1.2s 远星 3~4s」）
        assertEquals(1.2f, voyageSec(900f), 1e-4f)
        assertEquals(3.6f, voyageSec(8000f), 1e-4f)
        // 下限：贴脸近星与零/负距防御恒下限（「+下限」的字面兑现）
        assertEquals(1.2f, voyageSec(0f), 1e-4f)
        assertEquals(1.2f, voyageSec(200f), 1e-4f)
        // 上限：跨银河远星恒上限（3~4s 档不拖戏）
        assertEquals(3.6f, voyageSec(20000f), 1e-4f)
        // 线性正比字面口径：等距步进=等时长步进（中点=中值 4450u→2.4s）
        assertEquals(2.4f, voyageSec(4450f), 1e-4f)
        assertEquals(voyageSec(2000f) - voyageSec(1000f), voyageSec(3000f) - voyageSec(2000f), 1e-4f)
        // 严格单调不降+全域域界 [MIN,MAX]（900..8000 逐百采样）
        var prev = voyageSec(900f)
        var d = 900f
        while (d < 8000f) {
            d += 100f
            val t = voyageSec(d)
            assertTrue("voyageSec 必须随距离单调不降（d=$d）", t >= prev)
            assertTrue("voyageSec 全域钳界（d=$d t=$t）", t in VOYAGE_MIN_SEC..VOYAGE_MAX_SEC)
            prev = t
        }
        // 远近两档比值>2.5（「跨光年感」量化锚——V46-③ 断言同口径：远星飞行
        // 真的更久，旧 τ 恒 0.9s 的时长无差=「嗖一下」病根的负向对照）
        assertTrue(
            "远近档比值=${voyageSec(8000f) / voyageSec(900f)} 应>2.5（跨光年感量化锚）",
            voyageSec(8000f) / voyageSec(900f) > 2.5f,
        )
    }

    @Test
    fun `数值锚-望远镜与飞入门与收卡`() {
        assertEquals(1.5f, LENS_STEP, 0f)
        assertEquals(1f, LENS_MIN, 0f)
        assertEquals(3.2f, LENS_MAX, 0f)
        assertEquals(22f, FLY_INTO_GATE_UNITS, 0f)
        assertEquals(64f, CARD_DISMISS_DRAG_DP, 0f)
    }

    @Test
    fun `数值锚-锚指环族`() {
        assertEquals(26f, ANCHOR_RING_RADIUS_DP, 0f)
        assertEquals(2.5f, ANCHOR_RING_STROKE_DP, 0f)
        assertEquals(22f, ANCHOR_CANDIDATE_RING_RADIUS_DP, 0f)
        assertEquals(1.5f, ANCHOR_CANDIDATE_RING_STROKE_DP, 0f)
        assertEquals(7f, ANCHOR_ARROW_ARM_DP, 0f)
        assertEquals(12f, ANCHOR_ARROW_GAP_DP, 0f)
        assertEquals(0.72f, ANCHOR_PULSE_BASE, 0f)
        assertEquals(0.28f, ANCHOR_PULSE_AMP, 0f)
        assertEquals(5.2f, ANCHOR_PULSE_SPEED, 0f)
    }

    // ==================== 派生函数不变量 ====================

    @Test
    fun `转头-横扫零点七屏等于九十度`() {
        for (w in floatArrayOf(1080f, 1216f, 1440f, 2688f)) {
            assertEquals(90f, turnDegPerPx(w) * TURN_FULL_FRACTION * w, 0.01f)
        }
        // 红米 1216px：0.1057°/px（一次自然横扫把视野转过 1/3 圈）
        assertEquals(0.1057f, turnDegPerPx(1216f), 0.001f)
        // 防御：零宽不除零
        assertTrue(turnDegPerPx(0f).isFinite())
    }

    @Test
    fun `捏合-张开百分之十等于七十二单位且限幅`() {
        assertEquals(72f, pinchFlyUnits(1.1f), 0.01f)
        assertEquals(-72f, pinchFlyUnits(0.9f), 0.01f)
        // 单事件限幅：极端倍率也不超过 FLY_EVENT_CAP
        assertEquals(FLY_EVENT_CAP, pinchFlyUnits(40f), 0f)
        assertEquals(-FLY_EVENT_CAP, pinchFlyUnits(0.01f), 0f)
        assertEquals(0f, pinchFlyUnits(1f), 0f)
    }

    // ==================== R303c P3：乘法缩放（§7.1 跨数量级） ====================

    @Test
    fun `乘法缩放-近地面回落现行线性`() {
        // dRef<ZONE：与旧口径逐位一致（贴脸细控零变化——回退语义）
        assertEquals(pinchFlyUnits(1.1f), pinchFlyUnitsAlt(1.1f, 0f), 0f)
        assertEquals(pinchFlyUnits(1.1f), pinchFlyUnitsAlt(1.1f, 1999f), 0f)
        assertEquals(pinchFlyUnits(0.9f), pinchFlyUnitsAlt(0.9f, 260f), 0f)
        assertEquals(0f, pinchFlyUnitsAlt(1f, 5000f), 0f)
    }

    @Test
    fun `乘法缩放-高空档位移∝当前高度`() {
        // dRef=6000（L4 银河位）：10% 张开=600 单位（线性档只有 72——高空灵敏 8×）
        assertEquals(600f, pinchFlyUnitsAlt(1.1f, 6000f), 0.01f)
        assertEquals(-600f, pinchFlyUnitsAlt(0.9f, 6000f), 0.01f)
        // dRef 单调：同一 factor 高度越高位移越大（速度∝当前高度的字面断言）
        var prev = -1f
        for (d in 2000..7000 step 500) {
            val v = pinchFlyUnitsAlt(1.05f, d.toFloat())
            assertTrue("dRef=$d 处 v=$v 应 ≥ $prev（单调）", v >= prev)
            prev = v
        }
    }

    @Test
    fun `乘法缩放-事件帽防瞬移`() {
        // 极端倍率 4×@6000=18000 → 钳 1500（毛刺事件不放大成瞬移）
        assertEquals(PINCH_MULT_EVENT_CAP, pinchFlyUnitsAlt(4f, 6000f), 0f)
        assertEquals(-PINCH_MULT_EVENT_CAP, pinchFlyUnitsAlt(0.25f, 6000f), 0f)
    }

    @Test
    fun `乘法缩放-满手势距离翻倍与跨数量级行程`() {
        // 单事件 factor=2.0 属极端毛刺档 → 事件帽钳 1500（防瞬移是设计行为）
        assertEquals(PINCH_MULT_EVENT_CAP, pinchFlyUnitsAlt(2f, 6000f), 0f)
        // 一满手势=多事件累积（真实手势每事件 factor≈1.05）：15 事件 ×1.05 →
        // 距离 ×2.08（「每满手势距离×2」的诚实口径——事件级合成非单事件）
        var d = 3000f
        repeat(15) { d += pinchFlyUnitsAlt(1.05f, d) }
        assertEquals(3000f * 1.05f.pow(15), d, 6f)
        // L4→L2 行程核算：每满手势 15 事件 ×0.95（累计 ×0.46），6000→300 需
        // ~4 满手势（设计「5~6 捏」档；旧线性口径同等行程 ~83 捏事件=被线性
        // 曲线杀死的跨数量级实锤）
        var dd = 6000f
        var pinches = 0
        while (dd > 300f && pinches < 12) {
            repeat(15) { dd += pinchFlyUnitsAlt(0.95f, dd) }
            pinches++
        }
        assertTrue("L4→L2 满手势次数=$pinches 应在 4~7 档（跨数量级达成）", pinches in 4..7)
    }

    @Test
    fun `锚指-一满屏冲程等于四千八单位`() {
        assertEquals(4800f, anchorFlyPerPx(2688f) * 2688f, 0.5f)
        // 红米 2688px 屏：1.79 单位/px
        assertEquals(1.79f, anchorFlyPerPx(2688f), 0.01f)
        assertTrue(anchorFlyPerPx(0f).isFinite())
    }

    @Test
    fun `惯性-半衰期约187ms且帧率无关`() {
        // 0.94^(t/16.7)=0.5 → t≈187ms
        assertEquals(0.5f, decayVel(1f, 187f), 0.005f)
        // 减到 10% ≈ 620ms
        assertEquals(0.1f, decayVel(1f, 620f), 0.01f)
        // 帧率无关：按真实 dt 归一（两帧 8.35ms = 一帧 16.7ms）
        assertEquals(decayVel(100f, 16.7f), decayVel(decayVel(100f, 8.35f), 8.35f), 0.001f)
        // dt=0 不衰减
        assertEquals(100f, decayVel(100f, 0f), 0f)
    }

    @Test
    fun `停船-巡航井乘四点六钳八十到二百六`() {
        assertEquals(138f, cruiseStop(30f), 0.01f) // 30×4.6=138 量程内
        assertEquals(80f, cruiseStop(10f), 0f) // 46→下限
        assertEquals(260f, cruiseStop(100f), 0f) // 460→上限
    }

    @Test
    fun `停船-单击注目四五折下限一百二`() {
        assertEquals(450f, glanceStop(1000f), 0.01f)
        assertEquals(120f, glanceStop(100f), 0f) // 45→下限
    }

    @Test
    fun `望远镜-倍率钳位量程`() {
        assertEquals(1f, lensClamp(0.5f), 0f)
        assertEquals(3.2f, lensClamp(5f), 0f)
        assertEquals(2f, lensClamp(2f), 0f)
    }

    @Test
    fun `锚环-脉冲基线值与值域`() {
        assertEquals(ANCHOR_PULSE_BASE, anchorPulse(0f), 1e-4f)
        // 全周期采样：脉冲 ∈ [基线−振幅, 基线+振幅] = [0.44, 1.0]
        var t = 0f
        while (t < 10f) {
            val p = anchorPulse(t)
            assertTrue(p >= ANCHOR_PULSE_BASE - ANCHOR_PULSE_AMP - 1e-4f)
            assertTrue(p <= ANCHOR_PULSE_BASE + ANCHOR_PULSE_AMP + 1e-4f)
            t += 0.013f
        }
    }

    // ==================== 提案①：FlingVelocityWindow 时间窗测速 ====================

    @Test
    fun `测速窗-六十赫兹匀速初速正确`() {
        // 60Hz：每 16~17ms 一事件，每事件 100px → 真速 6250px/s
        val w = GestureFeelPolicy.FlingVelocityWindow()
        var t = 0L
        repeat(6) {
            t += 16L
            w.push(t, 100f)
        }
        // 首推无前序间隔不计：Σd=500px/Σdt=80ms=6250px/s
        assertEquals(6250f, w.velocity(t), 1f)
    }

    @Test
    fun `测速窗-一百二十赫兹与六十赫兹同手速同初速（手感不分裂）`() {
        // 同一物理一划（每 8ms 50px = 6250px/s）：旧式「末事件×60」在 120Hz 报
        // 3000、60Hz 报 6000（约一半分裂）；时间窗测速两侧同报 6250。
        val w120 = GestureFeelPolicy.FlingVelocityWindow()
        var t = 0L
        repeat(12) {
            t += 8L
            w120.push(t, 50f)
        }
        assertEquals(6250f, w120.velocity(t), 1f)

        val w60 = GestureFeelPolicy.FlingVelocityWindow()
        var t60 = 0L
        repeat(6) {
            t60 += 16L
            w60.push(t60, 100f)
        }
        assertEquals(w60.velocity(t60), w120.velocity(t), 1f)
    }

    @Test
    fun `测速窗-窗外样本剔除`() {
        val w = GestureFeelPolicy.FlingVelocityWindow()
        // 10px/30ms=333px/s；窗宽 100ms：t=150 查时 t=0/30 出窗
        w.push(0L, 10f)
        w.push(30L, 10f)
        w.push(60L, 10f)
        w.push(90L, 10f)
        w.push(120L, 10f)
        // 窗内：t=120(dt30)/90(dt30)/60(dt30) → Σd=30/Σdt=90=333px/s
        assertEquals(333.3f, w.velocity(150L), 1f)
    }

    @Test
    fun `测速窗-休止超窗宽自动清窗（旧手势残速不污染新一轮）`() {
        val w = GestureFeelPolicy.FlingVelocityWindow()
        var t = 0L
        repeat(6) {
            t += 8L
            w.push(t, 50f)
        }
        // 抬手 476ms 后新一轮手势首事件：清窗——单样本无速可测
        w.push(t + 476L, 50f)
        assertEquals(0f, w.velocity(t + 476L), 0f)
        // 第二事件起恢复测速（只算新一轮）
        w.push(t + 484L, 50f)
        assertEquals(6250f, w.velocity(t + 484L), 1f)
    }

    @Test
    fun `测速窗-单样本与零间隔防御`() {
        val w = GestureFeelPolicy.FlingVelocityWindow()
        assertEquals(0f, w.velocity(0L), 0f) // 空窗
        w.push(100L, 80f)
        assertEquals(0f, w.velocity(100L), 0f) // 单样本无间隔
        w.push(100L, 80f) // 零间隔（同 ms 批事件）：不除零不 NaN
        assertEquals(0f, w.velocity(100L), 0f)
        w.push(108L, 80f)
        assertTrue(w.velocity(108L).isFinite())
    }

    @Test
    fun `测速窗-环形缓冲回绕后测速仍准`() {
        val w = GestureFeelPolicy.FlingVelocityWindow() // cap 32
        var t = 0L
        repeat(40) { // 超容量回绕
            t += 10L
            w.push(t, 10f)
        }
        // 10px/10ms=1000px/s：回绕后窗内样本间隔链完整
        assertEquals(1000f, w.velocity(t), 1f)
    }

    @Test
    fun `测速窗-减速尾速如实变小（按住减速松手不猛窜）`() {
        val w = GestureFeelPolicy.FlingVelocityWindow()
        var t = 0L
        // 先快后停：前 5 事件 50px/8ms，后 5 事件 2px/8ms
        repeat(5) {
            t += 8L
            w.push(t, 50f)
        }
        repeat(5) {
            t += 8L
            w.push(t, 2f)
        }
        val v = w.velocity(t)
        // 窗内混合：远小于纯快段 6250、且贴近慢段（慢段占窗内权重随时间增大）
        assertTrue(v < 3000f)
        assertTrue(v > 0f)
    }
}
