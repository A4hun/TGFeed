package dev.tgfeed.core.td

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R184 resolve-once 缓存纯函数单测：命中/记录/失效移除/TTL 过期/容量淘汰/
 * 用户名归一（大小写/@前缀/空白）/持久化往返（跨进程恢复）。
 */
class ResolveOnceCacheTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("r184_resolve").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private var nowMs = 1_000_000L
    private val clock = { nowMs }

    private fun cache(persist: Boolean = true) =
        ResolveOnceCache(if (persist) tmp else null, clock)

    @Test
    fun `记录后命中`() {
        val c = cache()
        c.record("SomeChannel", -100123, "标题A", true)
        val e = c.lookup("somechannel")!!
        assertEquals(-100123L, e.chatId)
        assertEquals("标题A", e.title)
        assertTrue(e.isChannel)
    }

    @Test
    fun `用户名归一大写与@前缀等价`() {
        val c = cache()
        c.record("@SomeChannel", -100123, "标题A", true)
        assertEquals(-100123L, c.lookup("SOMECHANNEL")!!.chatId)
        assertEquals(-100123L, c.lookup(" @SomeChannel ")!!.chatId)
    }

    @Test
    fun `未记录与空名`() {
        val c = cache()
        assertNull(c.lookup("never_recorded"))
        c.record("", 1, "t", true) // 空名不记录
        c.record("  ", 1, "t", true)
        c.record("x", 0L, "t", true) // chatId=0 不记录
        assertNull(c.lookup(""))
        assertNull(c.lookup("x"))
        assertEquals(0, c.size())
    }

    @Test
    fun `remove失效后miss`() {
        val c = cache()
        c.record("chan", -100123, "t", true)
        c.remove("CHAN") // 归一后命中同一 key
        assertNull(c.lookup("chan"))
    }

    @Test
    fun `TTL三十天外按miss并清理`() {
        val c = cache()
        c.record("chan", -100123, "t", true)
        nowMs += 30L * 24 * 3600_000L + 1
        assertNull(c.lookup("chan"))
        assertEquals(0, c.size())
    }

    @Test
    fun `容量硬顶按atMs淘汰最老`() {
        val c = ResolveOnceCache(tmp, clock, maxEntries = 3)
        c.record("a", 1, "t", true)
        nowMs += 1000
        c.record("b", 2, "t", true)
        nowMs += 1000
        c.record("c", 3, "t", true)
        nowMs += 1000
        c.record("d", 4, "t", true) // 超容量：a 最老被淘汰
        assertNull(c.lookup("a"))
        assertEquals(2L, c.lookup("b")!!.chatId)
        assertEquals(4L, c.lookup("d")!!.chatId)
        assertEquals(3, c.size())
    }

    @Test
    fun `同名记录刷新atMs免于淘汰`() {
        val c = ResolveOnceCache(tmp, clock, maxEntries = 2)
        c.record("a", 1, "t", true)
        nowMs += 1000
        c.record("b", 2, "t", true)
        nowMs += 1000
        c.record("a", 1, "t", true) // a 刷新到最新
        nowMs += 1000
        c.record("c", 3, "t", true) // 超容量：b 最老被淘汰
        assertEquals(1L, c.lookup("a")!!.chatId)
        assertNull(c.lookup("b"))
        assertEquals(3L, c.lookup("c")!!.chatId)
    }

    @Test
    fun `持久化往返跨进程恢复`() {
        val a = cache()
        a.record("chanA", -100123, "标题A", true)
        a.record("chanB", 55, "标题B", false)
        a.flush()
        val b = cache() // 新实例=模拟重启
        assertEquals(-100123L, b.lookup("chana")!!.chatId)
        assertEquals(55L, b.lookup("chanb")!!.chatId)
        assertEquals(false, b.lookup("chanb")!!.isChannel)
        assertEquals(2, b.size())
    }

    @Test
    fun `remove后落盘重启不复活`() {
        val a = cache()
        a.record("chanA", -100123, "t", true)
        a.remove("chanA")
        a.flush()
        val b = cache()
        assertNull(b.lookup("chanA"))
    }

    @Test
    fun `纯内存态不落盘不炸`() {
        val c = cache(persist = false)
        c.record("chan", 1, "t", true)
        assertEquals(1L, c.lookup("chan")!!.chatId)
        c.flush() // 空转不炸
    }

    @Test
    fun `脏文件按空缓存重建不炸`() {
        File(tmp, "resolve_once.json").writeText("broken {")
        val c = cache()
        assertNull(c.lookup("x"))
        c.record("x", 9, "t", true)
        assertEquals(9L, c.lookup("x")!!.chatId)
    }
}
