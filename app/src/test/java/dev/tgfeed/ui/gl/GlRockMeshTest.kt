package dev.tgfeed.ui.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R281 GlRockMesh 单测（岩石多面体三角汤的结构锚）：
 * 顶点数/单位球面/法线单位且外向/细分拓扑（12 角位×5 次、30 棱中点×6 次）/确定性。
 */
class GlRockMeshTest {

    @Test
    fun `三角汤规模与顶点布局`() {
        val v = GlRockMesh.buildVertices()
        assertEquals(GlRockMesh.SOUP_VERTS * GlRockMesh.STRIDE, v.size)
        assertEquals(240, GlRockMesh.SOUP_VERTS)
    }

    @Test
    fun `全部顶点在单位球面上且法线单位外向`() {
        val v = GlRockMesh.buildVertices()
        for (i in 0 until GlRockMesh.SOUP_VERTS) {
            val b = i * GlRockMesh.STRIDE
            val pr = sqrt(v[b] * v[b] + v[b + 1] * v[b + 1] + v[b + 2] * v[b + 2])
            assertEquals("顶点 $i 应在单位球面上", 1f, pr, 1e-5f)
            val nr = sqrt(v[b + 3] * v[b + 3] + v[b + 4] * v[b + 4] + v[b + 5] * v[b + 5])
            assertEquals("顶点 $i 法线应单位长", 1f, nr, 1e-5f)
        }
        // 外向：每面法线与面心点积为正
        for (f in 0 until GlRockMesh.FACE_COUNT) {
            val b = f * 3 * GlRockMesh.STRIDE
            val fcx = (v[b] + v[b + 6] + v[b + 12]) / 3f
            val fcy = (v[b + 1] + v[b + 7] + v[b + 13]) / 3f
            val fcz = (v[b + 2] + v[b + 8] + v[b + 14]) / 3f
            val dot = v[b + 3] * fcx + v[b + 4] * fcy + v[b + 5] * fcz
            assertTrue("面 $f 法线应外向", dot > 0f)
        }
    }

    @Test
    fun `细分拓扑——12 角位各 5 面 30 棱中点各 6 面`() {
        val v = GlRockMesh.buildVertices()
        // 位置频次表（汤中同一位置重复出现=共享顶点视觉水密）
        val freq = HashMap<String, Int>()
        for (i in 0 until GlRockMesh.SOUP_VERTS) {
            val b = i * GlRockMesh.STRIDE
            // 位级一致才算同位（三分量 raw bits 拼接键——零碰撞）
            val key = "${v[b].toRawBits()}_${v[b + 1].toRawBits()}_${v[b + 2].toRawBits()}"
            freq[key] = (freq[key] ?: 0) + 1
        }
        val counts = freq.values.sorted()
        assertEquals("独立顶点数=42", GlRockMesh.VERT_COUNT, counts.size)
        assertEquals("12 个角位各出现 5 次", 12, counts.count { it == 5 })
        assertEquals("30 个棱中点各出现 6 次", 30, counts.count { it == 6 })
    }

    @Test
    fun `确定性——两次构建逐位相等`() {
        val a = GlRockMesh.buildVertices()
        val b = GlRockMesh.buildVertices()
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `面法线与棱方向正交（平坦着色成立）`() {
        val v = GlRockMesh.buildVertices()
        for (f in 0 until GlRockMesh.FACE_COUNT) {
            val b = f * 3 * GlRockMesh.STRIDE
            val e1x = v[b + 6] - v[b]; val e1y = v[b + 7] - v[b + 1]; val e1z = v[b + 8] - v[b + 2]
            val d = e1x * v[b + 3] + e1y * v[b + 4] + e1z * v[b + 5]
            assertTrue("面 $f 法线应与棱正交", abs(d) < 1e-4f)
        }
    }
}
