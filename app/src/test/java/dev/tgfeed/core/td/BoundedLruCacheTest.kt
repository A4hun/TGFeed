package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [BoundedLruCache]（R169 F7 家族共享件，coverBytesCache 的有界底座）的行为单测：
 * 使命=「进程级缓存只进不出」的族级止血（R165 灾变链燃料）——锚定三件事：
 *  1. 条数上限真生效（超限必淘汰，绝无「摆设上限」——R97 教训）；
 *  2. 访问序 LRU（get 也刷新顺位：被用过的后淘汰，最少用的先淘汰）；
 *  3. 写入经 transform 加工（coverBytesCache 的降采样入口对全部写入点生效）。
 */
class BoundedLruCacheTest {

    @Test
    fun `超上限必淘汰_最老键先出局`() {
        val cache = BoundedLruCache<Int, String>(maxEntries = 3)
        cache[1] = "a"
        cache[2] = "b"
        cache[3] = "c"
        cache[4] = "d" // 超限：1 最久未用，先出局
        assertEquals(3, cache.size())
        assertNull(cache[1])
        assertEquals("b", cache[2])
        assertEquals("c", cache[3])
        assertEquals("d", cache[4])
    }

    @Test
    fun `get 刷新顺位_最近被访问者免于淘汰`() {
        val cache = BoundedLruCache<Int, String>(maxEntries = 3)
        cache[1] = "a"
        cache[2] = "b"
        cache[3] = "c"
        cache[1] // 访问 1：顺位刷新为最新
        cache[4] = "d" // 淘汰的是 2（最久未访问），不是 1
        assertEquals("a", cache[1])
        assertNull(cache[2])
        assertEquals("d", cache[4])
    }

    @Test
    fun `写入经 transform 加工_降采样入口对全部写入点生效`() {
        val seen = mutableListOf<String>()
        val cache = BoundedLruCache<Int, String>(maxEntries = 8) { v ->
            seen.add(v)
            v.trim()
        }
        cache[1] = "  a  "
        cache[2] = "  b  "
        assertEquals("a", cache[1])
        assertEquals("b", cache[2])
        assertEquals(listOf("  a  ", "  b  "), seen) // 每次写入都过加工（无旁路）
    }

    @Test
    fun `默认 transform 原样直通_引用不变`() {
        val cache = BoundedLruCache<String, Any>(maxEntries = 2)
        val obj = Any()
        cache["k"] = obj
        assertSame(obj, cache["k"])
    }

    @Test
    fun `同键重写覆盖旧值_不涨条数`() {
        val cache = BoundedLruCache<Int, ByteArray>(maxEntries = 2)
        cache[1] = byteArrayOf(1)
        cache[1] = byteArrayOf(2)
        assertEquals(1, cache.size())
        assertEquals(2.toByte(), cache[1]!![0]) // Byte 对 Byte：Int 装箱不等（JUnit 经典坑）
    }

    @Test
    fun `上限非正直接拒绝_防手滑传 0 变成立即清空`() {
        assertThrows(IllegalArgumentException::class.java) { BoundedLruCache<Int, Int>(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { BoundedLruCache<Int, Int>(maxEntries = -1) }
    }

    @Test
    fun `clear 清空_重建口径`() {
        val cache = BoundedLruCache<Int, String>(maxEntries = 2)
        cache[1] = "a"
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache[1])
    }
}
