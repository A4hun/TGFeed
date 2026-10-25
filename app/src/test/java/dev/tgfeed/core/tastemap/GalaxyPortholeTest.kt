package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R301 双形态咬合深化的纯函数锚（design-dual-form.md 三件落地的 JVM 直测层）：
 *  - 邻域小装配：边合并口径（无向对求和/search_hit 剔除/自环与 0 号位剔除/确定性
 *    破平序）、2 跳边筛选、环形布局（确定性/中心锚/节点数帽/半径随权重单调）；
 *  - 快照指纹 key：稳定性与敏感性（邻域内容变=key 变——R254 指纹族失效锚）；
 *  - tag 时期弧：SQL 形状锚（R269 tagItemsJoinSql 先例——27dd5a8 误删 FROM 的
 *    教训）、月分桶与峰月判词；
 *  - 世界卡相机：orbitAround（保距+瞄准+角推进）、easePose（端点精确+最短角差）。
 */
class GalaxyPortholeTest {

    // ==================== 邻域边合并 ====================

    @Test
    fun `邻域合并-无向对求和且降序破平确定`() {
        val edges = listOf(
            GalaxyPorthole.RawEdge(-100, -200, "link", 3),
            GalaxyPorthole.RawEdge(-200, -100, "forward_src", 2), // 反向边=同一无向对
            GalaxyPorthole.RawEdge(-100, -300, "comment", 7),
            GalaxyPorthole.RawEdge(-400, -500, "link", 9), // 不含中心星=不进
        )
        val out = GalaxyPorthole.mergedNeighborWeights(-100, edges)
        assertEquals(listOf(-300L to 7, -200L to 5), out)
    }

    @Test
    fun `邻域合并-search_hit 弱证据与自环剔除`() {
        val edges = listOf(
            GalaxyPorthole.RawEdge(-100, -200, "search_hit", 99), // 弱证据不进
            GalaxyPorthole.RawEdge(-100, -100, "link", 5), // 自环剔除
            GalaxyPorthole.RawEdge(0, -100, "link", 4), // 0 号位剔除
            GalaxyPorthole.RawEdge(-100, -200, "link", 1),
        )
        val out = GalaxyPorthole.mergedNeighborWeights(-100, edges)
        assertEquals(listOf(-200L to 1), out)
    }

    @Test
    fun `邻域合并-中心 0 号位恒空`() {
        val edges = listOf(GalaxyPorthole.RawEdge(-100, -200, "link", 3))
        assertTrue(GalaxyPorthole.mergedNeighborWeights(0L, edges).isEmpty())
    }

    @Test
    fun `二跳边-两端都在邻域集合且不含中心星`() {
        val edges = listOf(
            GalaxyPorthole.RawEdge(-200, -300, "link", 2), // 邻居互连 ✓
            GalaxyPorthole.RawEdge(-100, -200, "link", 5), // 含中心星 ✗
            GalaxyPorthole.RawEdge(-200, -900, "link", 5), // 对端不在邻域 ✗
        )
        val pairs = GalaxyPorthole.secondHopPairs(edges, setOf(-200L, -300L), -100L)
        assertEquals(1, pairs.size)
    }

    // ==================== 舷窗布局 ====================

    @Test
    fun `布局-确定性同输入同图`() {
        val nb = listOf(-2L to 9, -3L to 4, -4L to 16)
        val titles = mapOf(-2L to "A", -3L to "B", -4L to "C")
        val a = GalaxyPorthole.layoutNeighborhood(-1L, "我", nb, titles)
        val b = GalaxyPorthole.layoutNeighborhood(-1L, "我", nb.reversed(), titles) // 输入乱序=自重排
        assertEquals(a.nodes.map { it.chatId }, b.nodes.map { it.chatId })
        assertEquals(a.nodes.map { it.x to it.y }, b.nodes.map { it.x to it.y })
    }

    @Test
    fun `布局-中心星锚世界中心且 nodes0 恒为中心`() {
        val l = GalaxyPorthole.layoutNeighborhood(
            -1L, "我", listOf(-2L to 3, -3L to 5), emptyMap(),
        )
        val center = l.nodes[0]
        assertTrue(center.isCenter)
        assertEquals(GalaxyPorthole.CENTER, center.x, 1e-4f)
        assertEquals(GalaxyPorthole.CENTER, center.y, 1e-4f)
        assertEquals(-1L, center.chatId)
    }

    @Test
    fun `布局-节点数帽与半径随权重单调`() {
        val nb = (1L..30L).map { it to (31 - it).toInt() } // 30 颗降序
        val l = GalaxyPorthole.layoutNeighborhood(99L, "我", nb, emptyMap())
        assertEquals(GalaxyPorthole.MAX_NEIGHBORS + 1, l.nodes.size)
        val r1 = l.nodes[1].radius // 权重最大者
        val rLast = l.nodes.last().radius
        assertTrue(r1 > rLast)
    }

    @Test
    fun `布局-边=中心全连加二跳互连`() {
        val nb = listOf(-2L to 8, -3L to 5)
        val second = GalaxyPorthole.secondHopPairs(
            listOf(GalaxyPorthole.RawEdge(-2, -3, "link", 2)), setOf(-2L, -3L), -1L,
        )
        val l = GalaxyPorthole.layoutNeighborhood(-1L, "我", nb, emptyMap(), second)
        // 2 条主边（中心-两邻）+1 条二跳
        assertEquals(3, l.edges.size)
        assertTrue(l.edges.take(2).all { it.aIdx == 0 })
    }

    // ==================== 快照指纹 key ====================

    @Test
    fun `指纹key-稳定且对邻域内容敏感`() {
        val nb = listOf(-2L to 9, -3L to 4)
        val k1 = GalaxyPorthole.snapshotCacheKey(-1L, "美食", nb)
        val k2 = GalaxyPorthole.snapshotCacheKey(-1L, "美食", nb)
        assertEquals(k1, k2) // 稳定
        assertNotEquals(k1, GalaxyPorthole.snapshotCacheKey(-1L, "旅行", nb)) // tag 变
        assertNotEquals(k1, GalaxyPorthole.snapshotCacheKey(-1L, "美食", listOf(-2L to 9))) // 邻域变
        assertNotEquals(k1, GalaxyPorthole.snapshotCacheKey(-9L, "美食", nb)) // 中心星变
    }

    // ==================== tag 时期弧 ====================

    @Test
    fun `时期弧SQL-形状锚（FROM加JOIN加三谓词加LIMIT）`() {
        val sql = GalaxyPorthole.tagEraSql()
        // R269 教训锚：27dd5a8 误删 FROM 全设备空召回——形状必须有 JVM 可测锚
        assertTrue(sql.contains("FROM tag_items"))
        assertTrue(sql.contains("JOIN items"))
        assertTrue(sql.contains("t.tag=?"))
        assertTrue(sql.contains("t.chatId=?"))
        assertTrue(sql.contains("i.dateEpochSec>0"))
        assertTrue(sql.contains("LIMIT ${GalaxyPorthole.ERA_ROW_CAP}"))
    }

    @Test
    fun `月分桶-跨月聚合与峰月判词`() {
        // epochDay 锚：2024-01-01=19723（UTC）；2024-03-01=+31+29=19783（闰年二月）
        val secs = longArrayOf(
            19783L * 86400, // 2024-03-01
            19789L * 86400, // 2024-03-07（同月第二日）
            19850L * 86400, // 2024-05-07
        )
        val monthly = GalaxyPorthole.tagMonthly(secs)
        assertEquals(2, monthly.size)
        assertEquals(2, monthly[0].count) // 三月 2 条
        assertEquals(1, monthly[1].count)
        assertEquals("2024年3月", GalaxyPorthole.peakMonthLabel(monthly))
    }

    @Test
    fun `月分桶-空输入与非法秒诚实空`() {
        assertTrue(GalaxyPorthole.tagMonthly(LongArray(0)).isEmpty())
        assertTrue(GalaxyPorthole.tagMonthly(longArrayOf(0L, -5L)).isEmpty())
        assertNull(GalaxyPorthole.peakMonthLabel(emptyList()))
    }

    // ==================== 行星标记锚点 ====================

    @Test
    fun `行星标记-相位确定性且在双环内缘带`() {
        val a = GalaxyPorthole.tagMarkerOffset(0.25f, 0.5f)
        val b = GalaxyPorthole.tagMarkerOffset(0.25f, 0.5f)
        assertEquals(a[0], b[0], 1e-4f)
        assertEquals(a[1], b[1], 1e-4f)
        val r = kotlin.math.hypot(a[0], a[1] / 0.62f) // 压扁还原后的轨道半径
        assertTrue(r >= GalaxyPorthole.RING_INNER * 0.5f)
        assertTrue(r <= GalaxyPorthole.RING_INNER * 1.01f) // 不与邻星环抢位
    }

    // ==================== 世界卡相机（TasteSpace 新件） ====================

    @Test
    fun `orbitAround-保距且重新瞄准目标`() {
        val cam = TasteSpace.SpaceCam(100f, 20f, 0f, 0f, 0f) // 目标=原点，半径 sqrt(100²+20²?)=水平 100
        val out = TasteSpace.orbitAround(cam, 0f, 0f, 0f, 0.1f)
        // 水平半径保持（环绕不动径向）
        assertEquals(100f, kotlin.math.hypot(out.px, out.pz), 1e-3f)
        assertEquals(20f, out.py, 1e-4f) // 高度不变
        // 瞄准目标：aimAt 反解应回到 yaw/pitch（误差内）
        val aim = FloatArray(2)
        TasteSpace.aimAt(out, 0f, 0f, 0f, aim)
        assertEquals(aim[0], out.yawDeg, 1e-3f)
        assertEquals(aim[1], out.pitchDeg, 1e-3f)
        // 角推进实锤：位置确实绕轴转了（不是原地只转头）
        assertTrue(out.px < 100f)
    }

    @Test
    fun `orbitAround-同位退化零推力`() {
        val cam = TasteSpace.SpaceCam(0f, 0f, 0f, 30f, 10f)
        val out = TasteSpace.orbitAround(cam, 0f, 0f, 0f, 0.2f)
        assertEquals(cam, out)
    }

    @Test
    fun `easePose-端点精确与最短角差`() {
        val from = TasteSpace.SpaceCam(0f, 0f, 0f, 170f, 0f)
        val to = TasteSpace.SpaceCam(100f, 50f, 200f, -170f, 10f)
        assertEquals(from, TasteSpace.easePose(from, to, 0f))
        assertEquals(to, TasteSpace.easePose(from, to, 1f))
        // 最短角差：170 → -170 走 +20° 路（经 ±180 环绕）而非 -340°；
        // smoothstep(0.5)=0.5 → yaw=170+20×0.5=180（wrap 到 ±180 界）
        val mid = TasteSpace.easePose(from, to, 0.5f)
        assertEquals(180f, kotlin.math.abs(mid.yawDeg), 1e-3f)
        // 位置中点（smoothstep(0.5)=0.5）
        assertEquals(50f, mid.px, 1e-3f)
        assertEquals(100f, mid.pz, 1e-3f)
    }

    @Test
    fun `easePose-单调推进不回弹`() {
        val from = TasteSpace.SpaceCam(0f, 0f, 0f, 0f, 0f)
        val to = TasteSpace.SpaceCam(100f, 0f, 0f, 90f, 0f)
        var prev = -1f
        for (i in 0..10) {
            val p = TasteSpace.easePose(from, to, i / 10f)
            assertTrue(p.px >= prev)
            prev = p.px
        }
    }
}
