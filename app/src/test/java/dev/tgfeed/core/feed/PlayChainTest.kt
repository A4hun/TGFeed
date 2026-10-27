package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R392 #5 接棒解析行为例(resolvePlayChain 纯函数 JVM 直测——PlayQueueModelTest/
 * TodayQueueGenTest 同纪律;显示序=updatedAt 降序判词的逐例兑现,r392b_notes §2.2)。
 */
class PlayChainTest {

    private fun station(
        id: String,
        updatedAt: Long,
        chainTo: String? = null,
    ) = PlayQueue(id = id, name = "台$id", updatedAt = updatedAt, chainTo = chainTo)

    // ---------------- 三态基本语义 ----------------

    @Test
    fun `不接棒_chainTo为null_返回null`() {
        val active = station("qs_a", 100, chainTo = null)
        assertNull("不接=播完即停(一期语义保留)", resolvePlayChain(active, listOf(active, station("qs_b", 50))))
    }

    @Test
    fun `下一台哨兵_按显示序updatedAt降序取下一台`() {
        // 底账序 [C(旧), A, B];显示序=updatedAt 降序=[A(100), B(80), C(50)]
        val a = station("qs_a", 100, chainTo = PlayQueue.CHAIN_NEXT)
        val b = station("qs_b", 80)
        val c = station("qs_c", 50)
        val next = resolvePlayChain(a, listOf(c, a, b))
        assertEquals("CHAIN_NEXT=显示序下一台(非底账下一台=所见即所得)", "qs_b", next?.id)
    }

    @Test
    fun `下一台哨兵_当前已是显示序末台_返回null不循环`() {
        val a = station("qs_a", 100)
        val c = station("qs_c", 50, chainTo = PlayQueue.CHAIN_NEXT)
        assertNull("末台无下家=自然停,循环开台=后续期诚实挂账", resolvePlayChain(c, listOf(a, c)))
    }

    @Test
    fun `指定台id_命中该台`() {
        val a = station("qs_a", 100, chainTo = "qs_c")
        val b = station("qs_b", 80)
        val c = station("qs_c", 50)
        assertEquals("指定台命中(与显示序无关)", "qs_c", resolvePlayChain(a, listOf(a, b, c))?.id)
    }

    @Test
    fun `指定台失联_返回null诚实降级`() {
        val a = station("qs_a", 100, chainTo = "qs_gone")
        assertNull("目标台已删/坏档=自然停不硬指", resolvePlayChain(a, listOf(a, station("qs_b", 80))))
    }

    // ---------------- 防御与边界 ----------------

    @Test
    fun `自指_返回null配置谬误防御`() {
        val a = station("qs_a", 100, chainTo = "qs_a")
        assertNull("接自己=配置谬误跳过", resolvePlayChain(a, listOf(a)))
    }

    @Test
    fun `活跃台不在列表_返回null`() {
        val orphan = station("qs_x", 100, chainTo = PlayQueue.CHAIN_NEXT)
        assertNull("哨兵/失联活跃台=无处可接", resolvePlayChain(orphan, listOf(station("qs_a", 50), station("qs_b", 10))))
    }

    @Test
    fun `空台列表_一切链向返回null`() {
        assertNull("空列表+哨兵", resolvePlayChain(station("qs_a", 100, chainTo = PlayQueue.CHAIN_NEXT), emptyList()))
        assertNull("空列表+指定台", resolvePlayChain(station("qs_a", 100, chainTo = "qs_b"), emptyList()))
    }

    @Test
    fun `updatedAt并列_稳定排序保底账原序破平`() {
        // A/B 同 updatedAt=100,底账序 [A, B]:显示序稳定排序=[A, B],A 的下一台=B
        val a = station("qs_a", 100, chainTo = PlayQueue.CHAIN_NEXT)
        val b = station("qs_b", 100)
        assertEquals("并列时底账原序破平(synchronized 稳定排序语义)", "qs_b", resolvePlayChain(a, listOf(a, b))?.id)
    }

    // ---------------- 哨兵域隔离 ----------------

    @Test
    fun `哨兵常量与台id域不撞`() {
        assertEquals("哨兵字面单源(next)", "next", PlayQueue.CHAIN_NEXT)
        // 台 id 域=qs_ 前缀/legacy/qs_today 前缀族,与 "next" 字面永不碰撞——
        // 三态编码( null / next / id )无歧义的结构前提
        for (id in listOf("qs_1729_0001", PlayQueue.LEGACY_ID, "qs_today_1729")) {
            assertTrue("台 id 不得等于哨兵:$id", id != PlayQueue.CHAIN_NEXT)
        }
    }
}
