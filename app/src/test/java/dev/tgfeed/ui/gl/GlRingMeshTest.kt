package dev.tgfeed.ui.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * R281 GlRingMesh 单测（真 3D 环带几何的结构锚）：
 * 顶点/索引规模/环带半径域/索引域合法/环面共面（y=0）/确定性。
 */
class GlRingMeshTest {

    @Test
    fun `环带规模与索引域`() {
        val v = GlRingMesh.buildVertices()
        val idx = GlRingMesh.buildIndices()
        assertEquals(GlRingMesh.vertexCount * GlRingMesh.STRIDE, v.size)
        assertEquals((GlRingMesh.SEGMENTS + 1) * 2, GlRingMesh.vertexCount)
        assertEquals(GlRingMesh.SEGMENTS * 6, idx.size)
        for (i in idx) {
            val iv = i.toInt() and 0xFFFF
            assertTrue("索引应在顶点域内", iv >= 0 && iv < GlRingMesh.vertexCount)
        }
    }

    @Test
    fun `顶点在内外半径圆环上且共面`() {
        val v = GlRingMesh.buildVertices()
        for (i in 0 until GlRingMesh.vertexCount) {
            val b = i * GlRingMesh.STRIDE
            val r = sqrt(v[b] * v[b] + v[b + 2] * v[b + 2])
            val expect = if (i % 2 == 0) GlRingMesh.INNER else GlRingMesh.OUTER
            assertEquals("顶点 $i 环半径", expect, r, 1e-5f)
            assertEquals("顶点 $i 应在环平面", 0f, v[b + 1], 1e-7f)
            assertEquals("径向 01", (i % 2).toFloat(), v[b + 3], 1e-7f)
        }
    }

    @Test
    fun `确定性——两次构建逐位相等`() {
        assertTrue(GlRingMesh.buildVertices().contentEquals(GlRingMesh.buildVertices()))
        assertTrue(GlRingMesh.buildIndices().contentEquals(GlRingMesh.buildIndices()))
    }
}
