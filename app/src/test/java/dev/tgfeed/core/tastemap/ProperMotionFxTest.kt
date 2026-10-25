package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * R323 自行单测锚（r315 J⑤ 单测锚表全项——[ProperMotionFx] core 纯件）：
 * 两窗构成差→矢量方向映射（三轴单调）/强度归一+帽/缺窗与低样本 null（诚实
 * 缺省）/零漂移判定（差<阈值=零矢量「没动」也是结论）/两窗归桶边界/确定性。
 * 测试名零 JVM 非法字符（§七——点号斜杠间隔号全禁，数值区间用中文/下划线）。
 */
class ProperMotionFxTest {

    private val nowSec = 1_800_000_000L

    /** 造一天桶表（day 天前，媒体 0/1/2，条数） */
    private fun day(daysAgo: Int, mediaType: Int, count: Int) =
        Triple(((nowSec - daysAgo * 86_400L) / 86_400L).toInt(), mediaType, count)

    // ==================== 两窗归桶（windowVotes） ====================

    @Test
    fun `两窗归桶-近窗远窗与窗外日桶`() {
        val entries = listOf(
            day(10, 0, 5),  // 近窗视频 5
            day(120, 1, 7), // 远窗图文 7
            day(300, 2, 9), // 两窗外（更早历史不计——构成差只看两窗）
            day(1, 2, 3),   // 近窗音频 3
        )
        val (near, far) = ProperMotionFx.windowVotes(entries, nowSec)
        assertEquals(5, near.video)
        assertEquals(0, near.photo)
        assertEquals(3, near.audio)
        assertEquals(0, far.video)
        assertEquals(7, far.photo)
        assertEquals(0, far.audio)
    }

    @Test
    fun `两窗归桶-窗界日桶与未来桶防御`() {
        // nowSec=1_800_000_000：day 20800（起点 1,797,120,000 ≥ nearStart 1,792,224,000）
        // =近窗；day 20700（1,788,480,000 ∈ [farStart 1,784,448,000, nearStart)）=远窗；
        // day 20600（1,779,840,000 < farStart）=两窗外；day 20840（1,800,576,000 > nowSec）
        // =未来桶（时钟偏斜防御）窗外。
        val entries = listOf(
            Triple(20800, 0, 2),
            Triple(20700, 1, 3),
            Triple(20600, 2, 4),
            Triple(20840, 0, 5),
        )
        val (near, far) = ProperMotionFx.windowVotes(entries, nowSec)
        assertEquals(2, near.video)
        assertEquals(0, near.audio)
        assertEquals(3, far.photo)
        assertEquals(0, far.video)
        assertEquals(0, far.audio) // 窗外+未来桶均不计
        // 未知媒体类型（防御档——现实不出现）：不入票不编造
        val (n2, f2) = ProperMotionFx.windowVotes(listOf(Triple(20800, 7, 9)), nowSec)
        assertEquals(0, n2.total)
        assertEquals(0, f2.total)
    }

    // ==================== 缺窗与低样本 null（诚实缺省） ====================

    @Test
    fun `缺窗-任一窗低于8条null不判`() {
        // 近窗 7 条 <8：构成比是噪声——null（远窗充足也不判）
        val near7 = ProperMotionFx.Votes(7, 0, 0)
        val farOk = ProperMotionFx.Votes(30, 30, 30)
        assertNull(ProperMotionFx.driftOf(near7, farOk))
        // 远窗 7 条 <8：同 null
        assertNull(ProperMotionFx.driftOf(ProperMotionFx.Votes(30, 30, 30), ProperMotionFx.Votes(0, 7, 0)))
        // 双窗齐 8 条=最低可判样本（纯翻转构成=L1 满档）
        assertNotNull(ProperMotionFx.driftOf(ProperMotionFx.Votes(8, 0, 0), ProperMotionFx.Votes(0, 0, 8)))
    }

    @Test
    fun `零漂移-两窗同构成null没动也是结论`() {
        // 完全同构成：L1=0<门——null（不硬找方向）
        assertNull(ProperMotionFx.driftOf(ProperMotionFx.Votes(30, 20, 10), ProperMotionFx.Votes(60, 40, 20)))
        // 微差在门内（L1≈0.03<0.20）：同样 null
        assertNull(ProperMotionFx.driftOf(ProperMotionFx.Votes(32, 20, 10), ProperMotionFx.Votes(60, 40, 20)))
    }

    // ==================== 方向映射（三轴——三基色语义同族） ====================

    /** 三基色基向（hue 锚→单位向） */
    private fun anchor(hueDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(hueDeg.toDouble())
        return cos(rad).toFloat() to sin(rad).toFloat()
    }

    private fun dotOf(d: ProperMotionFx.Drift, a: Pair<Float, Float>): Float =
        d.dirX * a.first + d.dirY * a.second

    @Test
    fun `方向映射-视频升主导朝红移基向`() {
        // Δ视频 +0.4 主导（Δ=(+0.4,−0.2,−0.2)——份额差恒和为零，主导升票=方向
        // 最贴近其基向）：视频化=朝口味角红移方向（20° 暖红）
        val d = ProperMotionFx.driftOf(ProperMotionFx.Votes(30, 10, 10), ProperMotionFx.Votes(10, 20, 20))
        assertNotNull(d)
        val dotV = dotOf(d!!, anchor(TasteNebula.CATEGORY_HUE_VIDEO))
        assertTrue(dotV > 0f)
        assertTrue(dotV > dotOf(d, anchor(TasteNebula.CATEGORY_HUE_PHOTO)))
        assertTrue(dotV > dotOf(d, anchor(TasteNebula.CATEGORY_HUE_AUDIO)))
    }

    @Test
    fun `方向映射-图文升主导朝青基向`() {
        val d = ProperMotionFx.driftOf(ProperMotionFx.Votes(10, 30, 10), ProperMotionFx.Votes(20, 10, 20))
        assertNotNull(d)
        val dotP = dotOf(d!!, anchor(TasteNebula.CATEGORY_HUE_PHOTO))
        assertTrue(dotP > 0f)
        assertTrue(dotP > dotOf(d, anchor(TasteNebula.CATEGORY_HUE_VIDEO)))
        assertTrue(dotP > dotOf(d, anchor(TasteNebula.CATEGORY_HUE_AUDIO)))
    }

    @Test
    fun `方向映射-音频升主导朝紫基向`() {
        val d = ProperMotionFx.driftOf(ProperMotionFx.Votes(10, 10, 30), ProperMotionFx.Votes(20, 20, 10))
        assertNotNull(d)
        val dotA = dotOf(d!!, anchor(TasteNebula.CATEGORY_HUE_AUDIO))
        assertTrue(dotA > 0f)
        assertTrue(dotA > dotOf(d, anchor(TasteNebula.CATEGORY_HUE_VIDEO)))
        assertTrue(dotA > dotOf(d, anchor(TasteNebula.CATEGORY_HUE_PHOTO)))
    }

    @Test
    fun `方向映射-合成向单位化`() {
        // 双票合成：归一后恒单位模（任何非退化差向量）
        val d = ProperMotionFx.driftOf(ProperMotionFx.Votes(30, 30, 0), ProperMotionFx.Votes(10, 10, 40))
        assertNotNull(d)
        val len = kotlin.math.sqrt(d!!.dirX * d.dirX + d.dirY * d.dirY)
        assertEquals(1.0, len.toDouble(), 1e-4)
        val r = ProperMotionFx.driftOf(ProperMotionFx.Votes(20, 0, 0), ProperMotionFx.Votes(0, 10, 10))
        assertNotNull(r)
        val lenR = kotlin.math.sqrt(r!!.dirX * r.dirX + r.dirY * r.dirY)
        assertEquals(1.0, lenR.toDouble(), 1e-4)
        // 三轴单调：视频票在总变化中的占比升=方向向视频基向转（对齐度单调升）
        // a：Δ=(+0.4,−0.4,0)（视频升图文降、音频平）→对齐度 0.79；
        // b：Δ=(+0.6,−0.2,−0.4)（视频主导更强）→对齐度 0.98
        val a = ProperMotionFx.driftOf(ProperMotionFx.Votes(40, 10, 0), ProperMotionFx.Votes(20, 30, 0))
        val b = ProperMotionFx.driftOf(ProperMotionFx.Votes(40, 10, 0), ProperMotionFx.Votes(10, 20, 20))
        assertNotNull(a); assertNotNull(b)
        val uv = anchor(TasteNebula.CATEGORY_HUE_VIDEO)
        assertTrue(dotOf(b!!, uv) > dotOf(a!!, uv))
    }

    // ==================== 强度归一+帽 ====================

    @Test
    fun `强度归一-门后单调钳01`() {
        val far = ProperMotionFx.Votes(160, 120, 120) // 份额 0.4/0.3/0.3（total 400）
        // L1≈0.21（刚过门）→ 强度≈0.01
        val weak = ProperMotionFx.driftOf(ProperMotionFx.Votes(202, 99, 99), far)
        // L1≈0.50 → 强度≈0.30
        val mid = ProperMotionFx.driftOf(ProperMotionFx.Votes(260, 70, 70), far)
        // L1=1.2=帽 → 强度=1
        val full = ProperMotionFx.driftOf(ProperMotionFx.Votes(400, 0, 0), far)
        assertNotNull(weak); assertNotNull(mid); assertNotNull(full)
        assertEquals(0.01f, weak!!.strength01, 1e-3f)
        assertEquals(0.30f, mid!!.strength01, 1e-4f)
        assertEquals(1f, full!!.strength01, 1e-5f)
        assertTrue(weak.strength01 < mid.strength01 && mid.strength01 < full.strength01) // 单调
    }

    @Test
    fun `尾巴参数-长度与透明度域单调`() {
        assertEquals(
            ProperMotionFx.TAIL_LEN_MIN_PX,
            ProperMotionFx.tailLenPx(0f),
            1e-4f,
        )
        assertEquals(
            ProperMotionFx.TAIL_LEN_MIN_PX + ProperMotionFx.TAIL_LEN_SPAN_PX,
            ProperMotionFx.tailLenPx(1f),
            1e-4f,
        )
        assertTrue(ProperMotionFx.tailLenPx(0.5f) > ProperMotionFx.tailLenPx(0.2f))
        // 域外钳（防御——正常调用域 0..1）
        assertEquals(ProperMotionFx.tailLenPx(0f), ProperMotionFx.tailLenPx(-0.5f), 1e-4f)
        assertTrue(ProperMotionFx.tailAlpha01(0f) < ProperMotionFx.tailAlpha01(1f))
    }

    // ==================== 确定性（同直方图同矢量——R 系铁律） ====================

    @Test
    fun `确定性-同两窗同矢量`() {
        val near = ProperMotionFx.Votes(50, 30, 20)
        val far = ProperMotionFx.Votes(20, 50, 30)
        val a = ProperMotionFx.driftOf(near, far)
        val b = ProperMotionFx.driftOf(near, far)
        assertEquals(a, b)
        // 两窗聚合确定性：同表同序同计数
        val entries = listOf(day(5, 0, 3), day(100, 1, 4), day(150, 2, 5))
        assertEquals(
            ProperMotionFx.windowVotes(entries, nowSec),
            ProperMotionFx.windowVotes(entries, nowSec),
        )
    }
}
