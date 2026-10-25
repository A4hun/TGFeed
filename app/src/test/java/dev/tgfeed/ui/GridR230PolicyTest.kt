package dev.tgfeed.ui

import dev.tgfeed.core.store.GridCanvasSlot
import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R230 分屏播放行为重设计纯函数单测：
 *  - [pickAdvanceCandidate]：池内循环换片的已看感知择档（实锤③「已看过不再出现」）
 *  - [filterRestorableSlots]：快照恢复的已看过滤（重开分屏不见已看槽位）
 *  - [TopPullTracker]：顶缘 overscroll 累计（实锤④ 上滑加载触发器）
 *  - [buildFloatingQueue]：小窗连播队列构建（追加4）
 */
class GridR230PolicyTest {

    private fun item(
        key: String,
        uid: String = "",
        mediaType: Int = 0,
    ) = VideoItem(
        key = key,
        chatId = 1L,
        messageId = 0L,
        channelTitle = "频道A",
        caption = "",
        durationSec = 10,
        width = 640,
        height = 480,
        fileId = -1,
        sizeBytes = 0L,
        playUri = null,
        fileUniqueId = uid,
        mediaType = mediaType,
    )

    private fun slot(slotId: String, contentKey: String = slotId) = GridCanvasSlot(
        slotId = slotId,
        contentKey = contentKey,
        chatId = 1L,
        gridX = 0,
        gridY = 0,
        spanW = 1,
        spanH = 1,
    )

    // ==================== pickAdvanceCandidate ====================

    @Test
    fun `未见未播未展的首选（档0）`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        val pick = pickAdvanceCandidate(pool, 0, emptySet(), emptySet(), emptySet(), emptySet())
        assertEquals("b" to 1, pick!!.let { it.first.key to it.second })
    }

    @Test
    fun `已看条目从首选档剔除落到未播档`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        // b 已看：档0 落空，b 落在档1（未播+未见 也命中不了——b 已见），实际应挑 c（档0）
        val pick = pickAdvanceCandidate(pool, 0, emptySet(), emptySet(), setOf("b"), emptySet())
        assertEquals("c", pick!!.first.key)
    }

    @Test
    fun `整池首选档全已见时回落到未播档（允许已见兜底供给）`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        val pick = pickAdvanceCandidate(
            pool, 0, emptySet(), emptySet(),
            setOf("a", "b", "c"), emptySet(),
        )
        // 全部已看 → 档2（未播未展）兜底，步进序第一个
        assertEquals("b", pick!!.first.key)
    }

    @Test
    fun `本格已播过的避开（换片不回头）`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        val pick = pickAdvanceCandidate(pool, 0, setOf("b"), emptySet(), emptySet(), emptySet())
        assertEquals("c", pick!!.first.key)
    }

    @Test
    fun `他格展示中的避开（同屏不撞片）`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        val pick = pickAdvanceCandidate(pool, 0, emptySet(), setOf("b"), emptySet(), emptySet())
        assertEquals("c", pick!!.first.key)
    }

    @Test
    fun `fileUniqueId 跨频道同片也按已看剔除`() {
        val pool = listOf(item("a"), item("b", uid = "u1"), item("c"))
        val pick = pickAdvanceCandidate(pool, 0, emptySet(), emptySet(), emptySet(), setOf("u1"))
        assertEquals("c", pick!!.first.key)
    }

    @Test
    fun `游标回绕取模（尾到头循环）`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        val pick = pickAdvanceCandidate(pool, 2, emptySet(), setOf("a"), emptySet(), emptySet())
        // 从 c（下标 2）起：step1 绕回 a（displayed 命中档1），step2=b 档0 胜出
        assertEquals("b", pick!!.first.key)
        assertEquals(1, pick.second)
    }

    @Test
    fun `池只有一条返回 null（原地重播）`() {
        val pool = listOf(item("a"))
        assertNull(pickAdvanceCandidate(pool, 0, emptySet(), emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun `全池已播过且都在他格展示时返回 null（宁重播不复制撞片）`() {
        val pool = listOf(item("a"), item("b"), item("c"))
        val pick = pickAdvanceCandidate(
            pool, 0, setOf("b", "c"), setOf("b", "c"), emptySet(), emptySet(),
        )
        assertNull(pick)
    }

    // ==================== filterRestorableSlots ====================

    @Test
    fun `当前内容已看的槽位不复活`() {
        val slots = listOf(slot("a"), slot("b"))
        val items = mapOf("a" to item("a"), "b" to item("b"))
        val kept = filterRestorableSlots(slots, items, setOf("b"), emptySet())
        assertEquals(listOf("a"), kept.map { it.slotId })
    }

    @Test
    fun `基底内容已看但当前内容未见时保留（格上展示的是当前内容）`() {
        val s = slot("base", contentKey = "cur")
        val items = mapOf("base" to item("base"), "cur" to item("cur"))
        val kept = filterRestorableSlots(listOf(s), items, setOf("base"), emptySet())
        assertEquals(1, kept.size)
    }

    @Test
    fun `当前内容已看且基底同条目时不复活`() {
        val s = slot("a")
        val items = mapOf("a" to item("a"))
        val kept = filterRestorableSlots(listOf(s), items, setOf("a"), emptySet())
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `fileUniqueId 轨命中也剔除（跨频道同片）`() {
        val s = slot("a")
        val items = mapOf("a" to item("a", uid = "u1"))
        val kept = filterRestorableSlots(listOf(s), items, emptySet(), setOf("u1"))
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `双轨合并判看（盘账+会话轨由调用方并入）`() {
        val slots = listOf(slot("a"), slot("b"))
        val items = mapOf("a" to item("a"), "b" to item("b"))
        val kept = filterRestorableSlots(slots, items, setOf("a"), emptySet())
        assertEquals(listOf("b"), kept.map { it.slotId })
    }

    @Test
    fun `条目缺失按 contentKey 直判（不因缺本体放走已看）`() {
        val s = slot("a", contentKey = "ghost")
        // ghost 无本体但 key 命中已看 → 剔
        assertTrue(filterRestorableSlots(listOf(s), emptyMap(), setOf("ghost"), emptySet()).isEmpty())
        // 无本体且未见 → 保留（交给复活链/同频道替位链裁决）
        assertEquals(1, filterRestorableSlots(listOf(s), emptyMap(), emptySet(), emptySet()).size)
    }

    // ==================== TopPullTracker ====================

    @Test
    fun `顶缘下拉累计过阈值触发一次并清零`() {
        var now = 0L
        val t = TopPullTracker(thresholdPx = 100f, cooldownMs = 1000L, clock = { now })
        // 不在顶：不累计
        assertEquals(false, t.onPan(atTop = false, downward = true, panPx = 60f))
        // 在顶累计 60+50=110 ≥ 100 → 触发
        assertEquals(false, t.onPan(atTop = true, downward = true, panPx = 60f))
        assertEquals(true, t.onPan(atTop = true, downward = true, panPx = 50f))
        // 清零后再拉 90 不触发
        assertEquals(false, t.onPan(atTop = true, downward = true, panPx = 90f))
    }

    @Test
    fun `冷却窗内不连发`() {
        var now = 0L
        val t = TopPullTracker(thresholdPx = 100f, cooldownMs = 1000L, clock = { now })
        assertEquals(true, t.onPan(atTop = true, downward = true, panPx = 150f))
        // 冷却内（now 未走）即使再拉满也不触发
        assertEquals(false, t.onPan(atTop = true, downward = true, panPx = 150f))
        // 冷却窗外恢复触发
        now = 1001L
        assertEquals(true, t.onPan(atTop = true, downward = true, panPx = 150f))
    }

    @Test
    fun `上拉或离开顶缘即复位累计`() {
        var now = 0L
        val t = TopPullTracker(thresholdPx = 100f, cooldownMs = 1000L, clock = { now })
        assertEquals(false, t.onPan(atTop = true, downward = true, panPx = 60f))
        // 上拉复位
        assertEquals(false, t.onPan(atTop = true, downward = false, panPx = -30f))
        assertEquals(false, t.onPan(atTop = true, downward = true, panPx = 60f))
        // 离开顶缘（已滚进画布）复位
        assertEquals(false, t.onPan(atTop = false, downward = true, panPx = 60f))
        assertEquals(false, t.onPan(atTop = true, downward = true, panPx = 60f))
    }

    // ==================== buildFloatingQueue ====================

    @Test
    fun `只取视频且排除播放中与弹出条本身（封面格可入队）`() {
        val pool = listOf(
            item("v1"),
            item("p1", mediaType = 1),
            item("a1", mediaType = 2),
            item("v2"),            // 正在播放中 → 排除（防同片双声）
            item("cover"),         // 画布封面格内容（未播）→ 可入队
            item("pop", uid = "u"), // 弹出条本身 → 排除
            item("v3"),
        )
        val q = buildFloatingQueue(
            pool = pool,
            playingKeys = setOf("v2"),
            excludeKey = "pop",
        )
        assertEquals(listOf("v1", "cover", "v3"), q.map { it.key })
    }

    @Test
    fun `封顶 FLOATING_QUEUE_SIZE 条`() {
        val pool = (1..40).map { item("v$it") }
        val q = buildFloatingQueue(pool, emptySet(), "none")
        assertEquals(FLOATING_QUEUE_SIZE, q.size)
        assertEquals("v1", q.first().key)
    }
}
