package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R248 星名点亮账本单测：JSON 解析/落盘回路/原子性入口/幂等/损坏诚实缺省。
 */
class TasteDiscoveryLogTest {

    private fun tmpFile(): File = createTempFile(prefix = "disc", suffix = ".json")

    @Test
    fun `解析-空与空数组`() {
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.parse("[]"))
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.parse(""))
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.parse("null"))
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.parse("[ ]"))
    }

    @Test
    fun `解析-基本数组与容错`() {
        val ids = TasteDiscoveryLog.parse("[1, 2, 3]")
        assertEquals(setOf(1L, 2L, 3L), ids)
        // 非数字段跳过、0 跳过（0=无效 chatId）、空段跳过
        assertEquals(setOf(7L, 9L), TasteDiscoveryLog.parse("[7,,x,0,9]"))
    }

    @Test
    fun `落盘回路-缺失文件=空集`() {
        val f = tmpFile()
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.load(f))
    }

    @Test
    fun `落盘回路-写读一致`() {
        val f = tmpFile()
        val out = TasteDiscoveryLog.addAll(f, listOf(100L, 200L, 300L))
        assertEquals(setOf(100L, 200L, 300L), out)
        assertEquals(setOf(100L, 200L, 300L), TasteDiscoveryLog.load(f))
        // 文件是真 JSON 数组格式（任务书口径 discovered_stars.json）
        val text = f.readText().trim()
        assertTrue(text.startsWith("[") && text.endsWith("]"))
    }

    @Test
    fun `落盘-幂等与合并`() {
        val f = tmpFile()
        TasteDiscoveryLog.addAll(f, listOf(1L, 2L))
        val again = TasteDiscoveryLog.addAll(f, listOf(2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), again)
        assertEquals(again, TasteDiscoveryLog.load(f))
        // 单颗口同一条写链
        TasteDiscoveryLog.add(f, 4L)
        assertEquals(setOf(1L, 2L, 3L, 4L), TasteDiscoveryLog.load(f))
    }

    @Test
    fun `落盘-空批量不写文件`() {
        val f = tmpFile()
        f.delete() // createTempFile 会先建文件——先删净再验「不建账」
        TasteDiscoveryLog.addAll(f, emptyList())
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.load(f))
        assertTrue(!f.exists())
    }

    @Test
    fun `损坏-半写或垃圾=空集诚实缺省`() {
        val f = tmpFile()
        f.writeText("[1, 2, 3") // 半写（无闭括号）
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.load(f))
        f.writeText("garbage!")
        assertEquals(emptySet<Long>(), TasteDiscoveryLog.load(f))
    }

    // ==================== R250 发现日期拓展（两级解析/幂等不改史） ====================

    @Test
    fun `R250 解析-对象条目带日期`() {
        val dated = TasteDiscoveryLog.parseDated("""[{"id":123,"ts":1699200000000},45]""")
        assertEquals(1699200000000L, dated[123L] ?: 0L)
        assertEquals(0L, dated[45L] ?: -1L) // 裸数字=存量=日期未知
        assertEquals(setOf(123L, 45L), TasteDiscoveryLog.parse("""[{"id":123,"ts":1699200000000},45]"""))
    }

    @Test
    fun `R250 解析-对象容错缺id零id坏ts`() {
        val dated = TasteDiscoveryLog.parseDated("""[{"ts":5},{"id":0,"ts":9},{"id":7},{"id":8,"ts":"x"},6]""")
        // 缺 id/零 id 段容错跳过；id=7 无 ts → 0；id=8 坏 ts → 0（日期未知不冒充）；
        // 裸 6 → 0
        assertEquals(3, dated.size)
        assertEquals(0L, dated[7L] ?: -1L)
        assertEquals(0L, dated[8L] ?: -1L)
        assertEquals(0L, dated[6L] ?: -1L)
    }

    @Test
    fun `R250 解析-对象内逗号不劈`() {
        val dated = TasteDiscoveryLog.parseDated("""[{"id":11,"ts":5},{"id":22,"ts":6}]""")
        assertEquals(2, dated.size)
        assertEquals(5L, dated[11L] ?: 0L)
        assertEquals(6L, dated[22L] ?: 0L)
    }

    @Test
    fun `R250 解析-同id双现保带日期者`() {
        val dated = TasteDiscoveryLog.parseDated("""[9,{"id":9,"ts":1699200000000}]""")
        assertEquals(1699200000000L, dated[9L] ?: 0L)
    }

    @Test
    fun `R250 落盘-新发现记时戳且旧id不改史`() {
        val f = tmpFile()
        TasteDiscoveryLog.addAll(f, listOf(100L), nowMs = 1699200000000L)
        assertEquals(1699200000000L, TasteDiscoveryLog.loadDated(f)[100L] ?: 0L)
        // 旧 id 幂等入账：保留原 ts（不改史）；新 id 记新时刻
        TasteDiscoveryLog.addAll(f, listOf(100L, 200L), nowMs = 1699300000000L)
        val dated = TasteDiscoveryLog.loadDated(f)
        assertEquals(1699200000000L, dated[100L] ?: 0L)
        assertEquals(1699300000000L, dated[200L] ?: 0L)
    }

    @Test
    fun `R250 落盘-存量裸id读回零日期`() {
        val f = tmpFile()
        f.writeText("[11, 12]")
        assertEquals(0L, TasteDiscoveryLog.loadDated(f)[11L] ?: -1L)
        assertEquals(setOf(11L, 12L), TasteDiscoveryLog.load(f))
    }

    @Test
    fun `R250 落盘-混合数组往返`() {
        val f = tmpFile()
        f.writeText("""[{"id":5,"ts":1699200000000},6]""")
        TasteDiscoveryLog.addAll(f, listOf(6L), nowMs = 1699300000000L)
        val dated = TasteDiscoveryLog.loadDated(f)
        assertEquals(1699200000000L, dated[5L] ?: 0L) // 存量 ts 保留
        assertEquals(0L, dated[6L] ?: 0L) // 裸 id 已在账=幂等不改史（日期未知保持未知——不冒充）
        // 写回仍是真 JSON 数组（任务书口径）
        val text = f.readText().trim()
        assertTrue(text.startsWith("[") && text.endsWith("]"))
    }
}
