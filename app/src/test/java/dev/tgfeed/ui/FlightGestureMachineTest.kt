package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.GestureFeelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R231 飞行手势状态机单测（用户原创手势方案的判定锚）：
 * 单指平移直通（无 slop=跟手零回归）/ 双指捏合产 zoom 零滚转泄漏 / 双指平移 /
 * 锚指+动指纵向=纵深锁定（与捏合平移互斥）/ 多冲程续推 / 锚指抬起终结余指冻结 /
 * 阈内抖动静默 / 双指扭转 rotation 度产出。
 *
 * R237 真机场景组（用户实锤「一指按住另一指拖动=没反应」的判定锚）：
 * 锚指颤抖（漂移超 8dp 绝对静止阈）+动指显著上推=纵深锁定（比率判定：
 * 锚漂 <0.3×动指位移；中间比率带 0.3~0.7 捏合优先）/ 双指同步纵向=平移不误判 /
 * 锚点反馈快照（候选暗环→锁定亮环→终结环隐）。
 * R241：FLIGHT 产出（moveDy）由消费方定义为俯仰 pitch（上滑=仰头）——机器
 * 判定逻辑零改动，本组用例即俯仰手势的判定锚（历史用例名「纵深」=模式旧称）。
 */
class FlightGestureMachineTest {

    // R268b：判定阈与产线同一出处（政策件锚定值——调参回归双测齐红）
    private val slop = GestureFeelPolicy.ANCHOR_SLOP_DP
    private val engage = GestureFeelPolicy.ANCHOR_ENGAGE_DP

    private fun machine(): FlightGestureMachine = FlightGestureMachine(slop, engage)

    private fun batch(
        m: FlightGestureMachine,
        moves: List<Triple<Long, Float, Float>>,
    ): FlightGestureMachine.Out? {
        m.beginBatch()
        for ((id, x, y) in moves) m.onMove(id, x, y)
        return m.endBatch()
    }

    private fun down(m: FlightGestureMachine, id: Long, x: Float, y: Float) {
        m.beginBatch()
        m.onDown(id, x, y)
        m.endBatch()
    }

    private fun up(m: FlightGestureMachine, id: Long) {
        m.beginBatch()
        m.onUp(id)
        m.endBatch()
    }

    @Test
    fun `单指-平移直通无slop`() {
        val m = machine()
        down(m, 1L, 100f, 100f)
        val out = batch(m, listOf(Triple(1L, 110f, 100f)))
        assertNotNull(out)
        assertEquals(10f, out!!.panX, 0.01f)
        assertEquals(0f, out.panY, 0.01f)
        assertEquals(1f, out.zoomFactor, 0f)
        // 下拖同向跟手
        val out2 = batch(m, listOf(Triple(1L, 110f, 160f)))
        assertEquals(0f, out2!!.panX, 0.01f)
        assertEquals(60f, out2.panY, 0.01f)
    }

    @Test
    fun `双指捏合-产zoom零滚转零平移`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1000f)
        // 越过 slop：两指相向收拢（各 100px）→ 变换锁定
        var out = batch(m, listOf(Triple(1L, 1100f, 1000f), Triple(2L, 1400f, 1000f)))
        assertNull("锁定批不产出（基线重置）", out)
        // 捏合继续：间距 300→200
        out = batch(m, listOf(Triple(1L, 1150f, 1000f), Triple(2L, 1350f, 1000f)))
        assertNotNull(out)
        assertEquals(200f / 300f, out!!.zoomFactor, 0.01f)
        assertEquals(0f, out.panX, 0.01f) // 质心不动：纯捏合
        assertEquals(0f, out.panY, 0.01f)
        assertEquals(0f, out.rotationDeg, 0.01f) // 缩放绝不带滚转（R210 锚）
    }

    @Test
    fun `双指平移-质心跟手`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1000f)
        batch(m, listOf(Triple(1L, 1060f, 1000f), Triple(2L, 1560f, 1000f))) // 锁定
        val out = batch(m, listOf(Triple(1L, 1160f, 1000f), Triple(2L, 1660f, 1000f)))
        assertNotNull(out)
        assertEquals(100f, out!!.panX, 0.01f)
        assertEquals(0f, out.panY, 0.01f)
        assertEquals(1f, out.zoomFactor, 0.001f) // 间距不变：纯平移
    }

    @Test
    fun `飞行-锚指加动指上推锁纵深`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f) // 锚
        down(m, 2L, 1500f, 1200f) // 动
        var out = batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1500f, 1180f)))
        assertNull("锁定批不产出", out)
        // 上推 40px（dy<0）
        out = batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1500f, 1140f)))
        assertNotNull(out)
        assertTrue("应为纵深产出", out!!.flight)
        assertEquals(-40f, out.moveDy, 0.01f)
        assertEquals(0f, out.panX, 0f) // 纵深与平移互斥
        assertEquals(1f, out.zoomFactor, 0f)
    }

    @Test
    fun `飞行-动指横向推不走纵深（旧行为保留）`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f) // 锚（静止）
        down(m, 2L, 1500f, 1200f)
        // 动指横向显著、锚指静止：非纵深 → 变换（旧行为）
        var out = batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1600f, 1200f)))
        assertNull("锁定批不产出", out)
        out = batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1700f, 1200f)))
        assertNotNull(out)
        assertTrue("横向推应走变换", !out!!.flight)
        assertEquals(50f, out.panX, 0.01f)
    }

    @Test
    fun `飞行-锁定后与捏合互斥（锚指滑动不再是捏合）`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1200f)
        batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1500f, 1160f))) // 纵深锁定
        // 锚指横滑 100px + 动指上推 30px：纵深照出（一锁定制死），绝不变缩放/平移
        val out = batch(m, listOf(Triple(1L, 1100f, 1000f), Triple(2L, 1500f, 1130f)))
        assertNotNull(out)
        assertTrue(out!!.flight)
        assertEquals(-30f, out.moveDy, 0.01f)
        assertEquals(1f, out.zoomFactor, 0f)
        assertEquals(0f, out.panX, 0f)
    }

    @Test
    fun `飞行-动指抬起锚还在可续推（多冲程）`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f) // 锚
        down(m, 2L, 1500f, 1200f)
        batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1500f, 1160f))) // 锁定
        val o1 = batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1500f, 1120f)))
        assertEquals(-40f, o1!!.moveDy, 0.01f)
        // 动指抬起：锚还在 → FLIGHT_ARMED（产出停）
        var out = up(m, 2L).let { batch(m, emptyList()) }
        assertNull(out)
        // 补指续推：新指落下即新动指
        down(m, 3L, 1400f, 900f)
        out = batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(3L, 1400f, 850f)))
        assertNotNull(out)
        assertTrue(out!!.flight)
        assertEquals(-50f, out.moveDy, 0.01f)
    }

    @Test
    fun `飞行-锚指抬起手势终结余指冻结`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f) // 锚
        down(m, 2L, 1500f, 1200f)
        batch(m, listOf(Triple(1L, 1000f, 1000f), Triple(2L, 1500f, 1160f))) // 锁定
        up(m, 1L) // 锚走 → DEAD
        // 余指（原动指）滑动：冻结不产出（防手势结束跳变）
        val out = batch(m, listOf(Triple(2L, 1500f, 1100f)))
        assertNull(out)
        // 全部抬起 → 复位，新手势可用
        up(m, 2L)
        down(m, 5L, 800f, 800f)
        val out2 = batch(m, listOf(Triple(5L, 850f, 800f)))
        assertNotNull("新手势应恢复单指平移", out2)
        assertEquals(50f, out2!!.panX, 0.01f)    }

    @Test
    fun `阈内-双指微抖静默`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1000f)
        // 两指各动 3px（<slop）：不锁定不产出
        val out = batch(m, listOf(Triple(1L, 1003f, 1000f), Triple(2L, 1503f, 1000f)))
        assertNull(out)
        // 继续微抖：仍静默
        assertNull(batch(m, listOf(Triple(1L, 1005f, 1000f), Triple(2L, 1505f, 1000f))))
    }

    @Test
    fun `扭转-rotation度直传`() {
        val m = machine()
        down(m, 1L, 1000f, 900f)
        down(m, 2L, 1400f, 1300f)
        // 越过 slop 锁变换（基线=(1010,910)/(1410,1310)）
        batch(m, listOf(Triple(1L, 1010f, 910f), Triple(2L, 1410f, 1310f)))
        // 扭转：p1→(950,950)、p2→(1450,1350)：夹角 45°→atan2(400,500)≈38.66°
        val out = batch(m, listOf(Triple(1L, 950f, 950f), Triple(2L, 1450f, 1350f)))
        assertNotNull(out)
        assertEquals(-6.34f, out!!.rotationDeg, 0.5f)
        assertTrue(out.zoomFactor > 1f) // 间距同时张开
    }

    @Test
    fun `两指-锁定前抬一指退化为单指平移`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1000f)
        batch(m, listOf(Triple(1L, 1003f, 1000f), Triple(2L, 1503f, 1000f))) // 未锁定
        up(m, 2L)
        val out = batch(m, listOf(Triple(1L, 1060f, 1000f)))
        assertNotNull(out)
        assertEquals(57f, out!!.panX, 0.01f) // 基线=(1003,1000)：从微抖位起算
        assertEquals(1f, out.zoomFactor, 0f)
    }

    // ==================== R237 真机场景（锚指颤抖=比率判定） ====================

    @Test
    fun `真机-锚指颤抖25px动指上推90px锁纵深`() {
        // 用户实锤：真人手指做不到绝对不动（微颤抖 1~5dp）——R231 绝对静止阈
        // 在 MuMu 注入成立、真机恒超被捏合抢走。比率判定：25 < 0.3×90=27 ✓
        val m = machine()
        down(m, 1L, 1000f, 1000f) // 锚（会颤抖）
        down(m, 2L, 1500f, 1200f) // 动
        // 第一批：锚漂 8px + 动指上推 30px（比率 0.27 但 30 < engage=14？30≥14 ✓）
        var out = batch(m, listOf(Triple(1L, 1004f, 1006f), Triple(2L, 1500f, 1170f)))
        if (out != null) {
            // 30px 已过 engage：本批可能直接锁定（锚漂 7.2 < 0.3×30=9 ✓）
            assertTrue("锁定即纵深", out.flight)
        }
        // 第二批：累计锚漂 25px、动指累计上推 90px——比率判定应锁纵深
        out = batch(m, listOf(Triple(1L, 1005f, 1015f), Triple(2L, 1500f, 1110f)))
        if (out != null) {
            assertTrue("真机颤抖+显著上推=纵深（不被捏合抢走）", out.flight)
            assertEquals(-60f, out.moveDy, 0.01f)
        }
        // 若前两批已锁纵深，第三批任何动指位移都应产出 dive（一锁定制死）
        val out3 = batch(m, listOf(Triple(1L, 1006f, 1016f), Triple(2L, 1500f, 1080f)))
        assertNotNull("锁定后应持续产出", out3)
        assertTrue(out3!!.flight)
        assertEquals(-30f, out3.moveDy, 0.01f)
    }

    @Test
    fun `真机-锚指漂10px动指45px锁纵深（纯比率路径）`() {
        val m = machine()
        down(m, 1L, 800f, 900f)
        down(m, 2L, 1100f, 1300f)
        // 锚漂 10.8px（>slop=8 绝对静止不成立，但 < 0.3×45=13.5 比率成立）+
        // 动指纵向 45px ≥ engage ✓ → 纯比率路径锁纵深
        val out = batch(m, listOf(Triple(1L, 806f, 909f), Triple(2L, 1100f, 1255f)))
        // 本批锁定不产出；下一批验证纵深产出
        if (out != null) assertTrue(out.flight)
        val out2 = batch(m, listOf(Triple(1L, 806f, 909f), Triple(2L, 1100f, 1200f)))
        assertNotNull("锁定后应产出纵深", out2)
        assertTrue("锚漂小于比率阈=纵深不被抢", out2!!.flight)
        assertEquals(-55f, out2.moveDy, 0.01f)
    }

    @Test
    fun `真机-中间比率带走变换（捏合优先保底）`() {
        // 锚漂 40px + 动指纵向 90px：比率 0.44 ∈ (0.3, 0.7) → 捏合优先（父代理方案）
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1200f)
        var out = batch(m, listOf(Triple(1L, 1020f, 1020f), Triple(2L, 1500f, 1155f))) // 40 / 45
        // 45≥engage、锚漂 40 ≥ 0.3×45=13.5 且 ≥ slop → 变换（中间带）
        if (out != null) assertTrue("中间比率带=变换优先", !out.flight)
        out = batch(m, listOf(Triple(1L, 1035f, 1035f), Triple(2L, 1500f, 1110f))) // 累计 49.5 / 90
        assertNotNull(out)
        assertTrue("比率 0.55 仍中间带=变换", !out!!.flight)
        assertTrue(out.panX != 0f || out.panY != 0f || out.zoomFactor != 1f)
    }

    @Test
    fun `真机-双指同步上推走平移（对称运动不误判纵深）`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1000f)
        // 两指一起上移（对称）=平移照旧
        var out = batch(m, listOf(Triple(1L, 1000f, 950f), Triple(2L, 1500f, 950f)))
        if (out != null) assertTrue("双指同步纵向=平移", !out.flight)
        out = batch(m, listOf(Triple(1L, 1000f, 900f), Triple(2L, 1500f, 900f)))
        assertNotNull(out)
        assertTrue(!out!!.flight)
        assertEquals(-50f, out.panY, 0.01f) // 质心增量（基线=上批质心）
    }

    @Test
    fun `真机-锚点反馈快照候选暗环锁定亮环`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1200f)
        // 未锁定（动指 6px < engage）：候选锚指暗环（位移较小的指）
        batch(m, listOf(Triple(1L, 1001f, 1000f), Triple(2L, 1503f, 1196f)))
        assertTrue("双指在场=候选暗环可见", m.ui.visible)
        assertTrue("未锁定=暗环档", !m.ui.engaged)
        assertEquals(1001f, m.ui.x, 0.01f)
        assertEquals(1000f, m.ui.y, 0.01f)
        // 纵深锁定：亮环钉在锚指
        batch(m, listOf(Triple(1L, 1002f, 1002f), Triple(2L, 1500f, 1140f)))
        assertTrue(m.ui.visible)
        assertTrue("锁定=亮环档", m.ui.engaged)
        assertEquals("环跟随锚指当前位", 1002f, m.ui.x, 0.01f)
        assertEquals(1002f, m.ui.y, 0.01f)
        // 锚指抬起=手势终结：环隐
        up(m, 1L)
        batch(m, emptyList())
        assertTrue("手势终结=环隐", !m.ui.visible)
    }

    @Test
    fun `真机-变换域无锚环`() {
        val m = machine()
        down(m, 1L, 1000f, 1000f)
        down(m, 2L, 1500f, 1000f)
        batch(m, listOf(Triple(1L, 1100f, 1000f), Triple(2L, 1400f, 1000f))) // 捏合锁定
        assertTrue("变换域无锚环", !m.ui.visible)
    }
}
