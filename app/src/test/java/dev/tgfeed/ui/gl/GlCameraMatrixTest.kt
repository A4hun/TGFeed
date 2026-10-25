package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.TasteSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * R277 GlCameraMatrix 单测（**真球档与点云不错位的核心不变式**）：
 * 对任意世界点，`P×V×(x,y,z,1)` 透视除法后的 NDC 与 [TasteSpace.project] 的
 * 屏像素满足 `ndcX = 2·sx/w − 1`、`ndcY = 1 − 2·sy/h`——GL 真球档与 CPU 投影的
 * 点/线/四边形在同一屏位（两套投影数学的等价锚）。
 */
class GlCameraMatrixTest {

    private val vp = FloatArray(16)
    private val v = FloatArray(16)
    private val p = FloatArray(16)
    private val proj = FloatArray(4)

    /** 矩阵变换一个世界点到 NDC（列主序——clip = M × (x,y,z,1)，ndc = clip.xy/clip.w） */
    private fun projectNdc(x: Float, y: Float, z: Float, m: FloatArray): FloatArray {
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        return floatArrayOf(cx / cw, cy / cw)
    }

    /** 双口径投影一致性断言（单点） */
    private fun assertConsistent(
        cam: TasteSpace.SpaceCam, x: Float, y: Float, z: Float, w: Float, h: Float, lens: Float,
    ) {
        GlCameraMatrix.viewProjectionOf(cam, w, h, lens, vp, v, p)
        val ok = TasteSpace.project(x, y, z, cam, TasteSpace.focalOf(w, h) * lens, w / 2f, h / 2f, proj)
        assertTrue("锚点须在视锥内（测试点构造错误）", ok)
        val ndc = projectNdc(x, y, z, vp)
        val expectX = 2f * proj[0] / w - 1f
        val expectY = 1f - 2f * proj[1] / h
        assertEquals(expectX, ndc[0], 1e-4f)
        assertEquals(expectY, ndc[1], 1e-4f)
    }

    @Test
    fun `原点平视相机 正前方点 映到屏心`() {
        val cam = TasteSpace.SpaceCam.ORIGIN
        // yaw=0 朝 +Z：正前方 (0,0,500) → 屏心 → NDC (0,0)
        assertConsistent(cam, 0f, 0f, 500f, 1080f, 2400f, 1f)
        GlCameraMatrix.viewProjectionOf(cam, 1080f, 2400f, 1f, vp, v, p)
        val ndc = projectNdc(0f, 0f, 500f, vp)
        assertEquals(0f, ndc[0], 1e-5f)
        assertEquals(0f, ndc[1], 1e-5f)
    }

    @Test
    fun `多相机位姿×多点 双口径逐位一致`() {
        val cams = listOf(
            TasteSpace.SpaceCam(0f, 0f, 0f, 0f, 0f),
            TasteSpace.SpaceCam(120f, -40f, 300f, 37.5f, 22f),
            TasteSpace.SpaceCam(-800f, 500f, 1200f, -121f, -63f),
            TasteSpace.SpaceCam(60f, 8f, -90f, 178f, 87.9f),
        )
        val pts = arrayOf(
            floatArrayOf(0f, 0f, 500f),
            floatArrayOf(300f, -120f, 900f),
            floatArrayOf(-1500f, 400f, 3200f),
            floatArrayOf(50f, 60f, 20f), // 近点（NEAR=14 之后）
            floatArrayOf(4000f, -2600f, 7000f),
        )
        for (cam in cams) {
            for (pt in pts) {
                // 只测视锥内点（背后的点 project 返回 false=剔除，两套口径同剔除）
                if (TasteSpace.project(pt[0], pt[1], pt[2], cam, 1006f, 540f, 1200f, proj)) {
                    assertConsistent(cam, pt[0], pt[1], pt[2], 1080f, 2400f, 1f)
                }
            }
        }
    }

    @Test
    fun `lens 倍率双口径同参（无障碍加减的焦距倍率）`() {
        val cam = TasteSpace.SpaceCam(10f, 20f, 30f, 45f, 15f)
        assertConsistent(cam, 200f, -100f, 800f, 1080f, 2400f, 1.5f)
        assertConsistent(cam, 200f, -100f, 800f, 1080f, 2400f, 0.6f)
    }

    @Test
    fun `屏系 y 翻转符号锚（世界上方=屏上方=NDC 正 y）`() {
        // 相机原点平视：世界上方点（+y）应映到屏上半（sy<屏心）=NDC y 正
        val cam = TasteSpace.SpaceCam.ORIGIN
        GlCameraMatrix.viewProjectionOf(cam, 1080f, 2400f, 1f, vp, v, p)
        val ndc = projectNdc(0f, 200f, 500f, vp)
        assertTrue("世界上方应映到 NDC 正 y", ndc[1] > 0f)
        // 世界右方（+x，yaw=0 的右侧）→ NDC 正 x
        val ndc2 = projectNdc(200f, 0f, 500f, vp)
        assertTrue("世界右方应映到 NDC 正 x", ndc2[0] > 0f)
    }

    @Test
    fun `近裁面语义一致（depth 小于 NEAR 的点两套口径同弃）`() {
        val cam = TasteSpace.SpaceCam.ORIGIN
        // depth=10 < NEAR=14：CPU 口径剔除
        assertTrue(!TasteSpace.project(0f, 0f, 10f, cam, 1006f, 540f, 1200f, proj))
        // GL 口径：clip.w=depth<NEAR（球网格顶点由装配器按 pass1 同式剔除——此处锚矩阵本身的 w 口径）
        GlCameraMatrix.viewProjectionOf(cam, 1080f, 2400f, 1f, vp, v, p)
        val m = vp
        val cw = m[3] * 0f + m[7] * 0f + m[11] * 10f + m[15]
        assertTrue("clip.w 应等于相机系深度", abs(cw - 10f) < 1e-3f)
    }
}
