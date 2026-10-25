package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R266 转发存储合并判定纯函数的回归锚（RepostMergePolicy）：
 *  - D1 canonical=组内首发（dateEpochSec,messageId 最小；同刻 messageId 升序兜底）；
 *  - D2 caption 覆盖列（相同→null 共享；不同→忠实保留含空串）；
 *  - R12 组可合并闸（mediaType/sizeBytes 全同才并——宁不并不错并）。
 * 纯函数零 Android 依赖，JVM 直测。
 */
class RepostMergePolicyTest {

    /** 测试用组行（date, msgId 二元组即裁决原料） */
    private data class Row(val date: Long, val msgId: Long, val tag: String)

    private fun row(date: Long, msgId: Long, tag: String = "") = Row(date, msgId, tag)

    // ---------------- D1 canonical 首发裁决 ----------------

    @Test
    fun `D1首发裁决_两行早者胜`() {
        val a = row(100, 5, "A")
        val b = row(200, 6, "B")
        val r = RepostMergePolicy.canonicalOf(listOf(b, a), { it.date }, { it.msgId })
        assertSame(a, r)
    }

    @Test
    fun `D1首发裁决_多行最小日期胜_输入序无关`() {
        val rows = listOf(row(300, 1), row(100, 9), row(200, 2))
        val r = RepostMergePolicy.canonicalOf(rows, { it.date }, { it.msgId })
        assertEquals(100L, r.date)
        assertEquals(9L, r.msgId)
    }

    @Test
    fun `D1首发裁决_同刻messageId小者胜`() {
        // 转发风暴同秒多发（同频道抢发）：messageId 升序稳定兜底（R204 W4 同口径）
        val a = row(100, 7)
        val b = row(100, 3)
        val r = RepostMergePolicy.canonicalOf(listOf(a, b), { it.date }, { it.msgId })
        assertEquals(3L, r.msgId)
    }

    @Test
    fun `D1首发裁决_全同刻全同id_首行稳定`() {
        val a = row(100, 5, "A")
        val b = row(100, 5, "B")
        val r = RepostMergePolicy.canonicalOf(listOf(a, b), { it.date }, { it.msgId })
        assertSame(a, r) // 严格平手=首行（确定性——重跑同结果，幂等前提）
    }

    @Test
    fun `D1首发裁决_单行即自身`() {
        val a = row(100, 5)
        assertSame(a, RepostMergePolicy.canonicalOf(listOf(a), { it.date }, { it.msgId }))
    }

    @Test
    fun `D1首发裁决_跨年跨度早者胜`() {
        // 实测最大组内跨度 7.85 年——同一文件跨 8 年被转发，首发语义仍稳
        val old = row(1_500_000_000L, 10)
        val new = row(1_750_000_000L, 20)
        assertSame(old, RepostMergePolicy.canonicalOf(listOf(new, old), { it.date }, { it.msgId }))
    }

    @Test
    fun `D1首发裁决_日期零值参与排序`() {
        // dateEpochSec=0 防御值（异常行）——0 最小会当选 canonical；裁决函数不藏私
        // （调用方批迁只处理正常爬取行，0 日期组如实按规则裁决）
        val zero = row(0, 5)
        val normal = row(100, 3)
        assertSame(zero, RepostMergePolicy.canonicalOf(listOf(normal, zero), { it.date }, { it.msgId }))
    }

    @Test
    fun `D1首发裁决_大组首发稳定`() {
        // 最大组 14,732 副本——大组首发裁决仍是单趟最小值
        val rows = (1..1000).map { row(1000L + (it * 37 % 500), it.toLong()) }
        val minDate = rows.minOf { it.date }
        val minMsg = rows.filter { it.date == minDate }.minOf { it.msgId }
        val r = RepostMergePolicy.canonicalOf(rows, { it.date }, { it.msgId })
        assertEquals(minDate, r.date)
        assertEquals(minMsg, r.msgId)
    }

    // ---------------- D2 caption 覆盖列 ----------------

    @Test
    fun `D2覆盖列_相同即null共享`() {
        assertNull(RepostMergePolicy.captionOverride("同一份文案", "同一份文案"))
    }

    @Test
    fun `D2覆盖列_双空即null`() {
        assertNull(RepostMergePolicy.captionOverride("", ""))
    }

    @Test
    fun `D2覆盖列_独立文案忠实保留`() {
        assertEquals("转发者自己的话", RepostMergePolicy.captionOverride("转发者自己的话", "首发文案"))
    }

    @Test
    fun `D2覆盖列_转发者清空文案保留空串`() {
        // 空串≠null：转发者清空文案是独立语义（忠实保留），不回退 canonical 文案
        assertEquals("", RepostMergePolicy.captionOverride("", "首发文案"))
    }

    @Test
    fun `D2覆盖列_canonical空而转发非空保留`() {
        assertEquals("转发补的文案", RepostMergePolicy.captionOverride("转发补的文案", ""))
    }

    @Test
    fun `D2覆盖列_大小写敏感逐字节比较`() {
        // 逐字节相等才算同文案（fold 归一是搜索层口径，存储层保真不折叠）
        assertEquals("ABC", RepostMergePolicy.captionOverride("ABC", "abc"))
    }

    // ---------------- R12 组可合并闸 ----------------

    @Test
    fun `R12冲突闸_全同放行`() {
        assertTrue(RepostMergePolicy.groupMergeable(listOf(0 to 1024L, 0 to 1024L, 0 to 1024L)))
    }

    @Test
    fun `R12冲突闸_单件空组放行`() {
        assertTrue(RepostMergePolicy.groupMergeable(emptyList()))
        assertTrue(RepostMergePolicy.groupMergeable(listOf(1 to 99L)))
    }

    @Test
    fun `R12冲突闸_类型分歧拒并`() {
        assertFalse(RepostMergePolicy.groupMergeable(listOf(0 to 1024L, 1 to 1024L)))
    }

    @Test
    fun `R12冲突闸_体积分歧拒并`() {
        assertFalse(RepostMergePolicy.groupMergeable(listOf(0 to 1024L, 0 to 2048L)))
    }

    @Test
    fun `R12冲突闸_末行分歧同样拒并`() {
        // 分歧在任意位置都拦（不只首对——宁不并不错并）
        assertFalse(RepostMergePolicy.groupMergeable(listOf(0 to 1L, 0 to 1L, 0 to 2L)))
    }

    @Test
    fun `R12冲突闸_双字段同时分歧拒并`() {
        assertFalse(RepostMergePolicy.groupMergeable(listOf(0 to 1L, 2 to 2L)))
    }
}
