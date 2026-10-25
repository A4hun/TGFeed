package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R235 未探索星云单测：网格聚类确定性/最小成员门槛/类别色投票与缺省/留重上限/
 * 雾透明度远可见近消散/发现感增益近亮远零/看过或订阅的星不进雾。
 */
class TasteNebulaTest {

    private fun star(
        chatId: Long,
        x: Float,
        y: Float,
        watchSec: Long = 0L,
        subscribed: Boolean = false,
        gVideo: Int = 0,
        gPhoto: Int = 0,
        gAudio: Int = 0,
    ) = TasteNebula.NebulaStarIn(chatId, x, y, 100f, watchSec, subscribed, gVideo, gPhoto, gAudio)

    @Test
    fun `聚类-最小成员门槛与过滤`() {
        // 2 颗未探索星（同格）+1 颗看过+1 颗订阅：不成云
        val clusters = TasteNebula.nebulaClusters(
            listOf(
                star(1, 100f, 100f), star(2, 110f, 105f),
                star(3, 105f, 108f, watchSec = 500L), // 看过：不进雾
                star(4, 103f, 102f, subscribed = true), // 订阅：不进雾
            ),
        )
        assertTrue(clusters.isEmpty())
        // 3 颗未探索星：成云
        val ok = TasteNebula.nebulaClusters(
            listOf(star(1, 100f, 100f), star(2, 110f, 105f), star(3, 120f, 108f)),
        )
        assertEquals(1, ok.size)
        assertEquals(3, ok[0].count)
    }

    @Test
    fun `聚类-质心半径与确定性`() {
        val stars = listOf(
            star(1, 100f, 100f), star(2, 110f, 105f), star(3, 120f, 108f), star(4, 115f, 102f),
        )
        val a = TasteNebula.nebulaClusters(stars)
        val b = TasteNebula.nebulaClusters(stars.shuffled()) // 顺序无关：同数据同星云
        assertEquals(a.size, b.size)
        assertEquals(a[0].cx, b[0].cx, 1e-4f)
        assertEquals(a[0].cy, b[0].cy, 1e-4f)
        assertEquals(a[0].radius, b[0].radius, 1e-4f)
        assertEquals(a[0].colorArgb, b[0].colorArgb)
        // 质心=成员均值
        assertEquals((100f + 110f + 120f + 115f) / 4f, a[0].cx, 1e-3f)
        // 半径 ≥ 最大成员离心距
        assertTrue(a[0].radius > 0f)
    }

    @Test
    fun `聚类-类别色投票与中性缺省`() {
        // 视频多数派：暖色相（20° 档）
        val video = TasteNebula.nebulaClusters(
            listOf(
                star(1, 100f, 100f, gVideo = 10), star(2, 110f, 105f, gVideo = 5), star(3, 120f, 108f, gPhoto = 3),
            ),
        )
        assertEquals("视频", video[0].dominant)
        // 全零基因：未知缺省（中性蓝灰，色相 215 档）
        val none = TasteNebula.nebulaClusters(
            listOf(star(1, 100f, 100f), star(2, 110f, 105f), star(3, 120f, 108f)),
        )
        assertEquals("未知", none[0].dominant)
        // 图文多数
        val photo = TasteNebula.nebulaClusters(
            listOf(
                star(1, 100f, 100f, gPhoto = 10), star(2, 110f, 105f, gPhoto = 5), star(3, 120f, 108f, gAudio = 3),
            ),
        )
        assertEquals("图文", photo[0].dominant)
        // 音频多数
        val audio = TasteNebula.nebulaClusters(
            listOf(
                star(1, 100f, 100f, gAudio = 10), star(2, 110f, 105f, gAudio = 5), star(3, 120f, 108f, gVideo = 3),
            ),
        )
        assertEquals("音频", audio[0].dominant)
        // 类别色互异
        assertTrue(video[0].colorArgb != photo[0].colorArgb)
        assertTrue(audio[0].colorArgb != photo[0].colorArgb)
    }

    @Test
    fun `聚类-留重上限与远格分立`() {
        // 远隔两格各一团 + 数量差距（大团留重）
        val stars = ArrayList<TasteNebula.NebulaStarIn>()
        for (i in 0 until 9) {
            stars += star(100L + i, 80f + i * 5f, 80f + i * 3f) // 第一格大团（9 星）
        }
        for (i in 0 until 3) {
            stars += star(200L + i, 900f + i * 5f, 900f + i * 3f) // 末格小团（3 星）
        }
        val clusters = TasteNebula.nebulaClusters(stars)
        assertEquals(2, clusters.size)
        assertEquals(9, clusters[0].count) // 大团先（成员数降序）
        // 超上限留重：7 团 → 只留 6（MAX_CLUSTERS 帧预算）
        val many = ArrayList<TasteNebula.NebulaStarIn>()
        var id = 0
        for (gy in 0 until 7) {
            for (i in 0 until 3 + gy) { // 各团递增：确定性留最大 6 团
                many += star(id++.toLong(), 60f + gy * 130f, 60f + gy * 130f)
            }
        }
        val capped = TasteNebula.nebulaClusters(many)
        assertEquals(TasteNebula.MAX_CLUSTERS, capped.size)
        // 留下的是成员最多的 6 团（8,9,7,6,5,4——去掉最小 3 团）
        assertTrue(capped.all { it.count >= 4 })
    }

    @Test
    fun `雾-远可见近消散`() {
        val far = TasteNebula.fogAlpha(2000f)
        val mid = TasteNebula.fogAlpha(900f)
        val near = TasteNebula.fogAlpha(300f)
        assertTrue(far > mid)
        assertTrue(mid > near)
        assertEquals(0f, near, 1e-4f) // 飞进星域：雾让位
        assertEquals(TasteNebula.FOG_ALPHA_MAX, far, 1e-4f)
        // R358 候选带守卫（用户 2026-10-23 拍板方案 A——R252「≤0.06 暗噪点档」
        // 被丝缕承载裁决取代：带上界 0.16 ≪ 旧病 0.26=防「大圆片」回潮新锚）
        assertTrue(TasteNebula.FOG_ALPHA_MAX in 0.10f..0.16f)
    }

    @Test
    fun `发现感-近亮远零单调`() {
        val far = TasteNebula.discoveryBoost(1000f)
        val edge = TasteNebula.discoveryBoost(700f)
        val near = TasteNebula.discoveryBoost(200f)
        assertEquals(0f, far, 1e-6f)
        assertEquals(0f, edge, 1e-6f) // 起点距离=零增益（现状不动）
        assertTrue(near > 0f)
        assertTrue(near <= TasteNebula.DISCOVER_BOOST_MAX)
        // 单调：更近更亮
        assertTrue(TasteNebula.discoveryBoost(300f) > TasteNebula.discoveryBoost(500f))
        assertEquals(TasteNebula.DISCOVER_BOOST_MAX, TasteNebula.discoveryBoost(100f), 1e-4f)
    }

    @Test
    fun `雾半径-下限保底`() {
        assertEquals(TasteNebula.FOG_MIN_PX, TasteNebula.fogPx(1f, 0.01f), 1e-3f)
        assertTrue(TasteNebula.fogPx(120f, 0.5f) > 40f)
    }
}
