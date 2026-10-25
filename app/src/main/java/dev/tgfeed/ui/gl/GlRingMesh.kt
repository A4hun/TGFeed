package dev.tgfeed.ui.gl

import kotlin.math.cos
import kotlin.math.sin

/**
 * R281 真 3D 环带网格（**纯 Kotlin 零 android 依赖**——土星环与黑洞吸积盘共用
 * 的环形几何底座；初始化期一次建成）。
 *
 * 形制：局部 XZ 平面（法线 +Y）上的**圆环面片**——内半径 [INNER]×行星半径、
 * 外半径 [OUTER]×行星半径（可见带形由环着色器的径向轮廓决定：土星带 1.30~2.20
 * 含卡西尼缝、吸积盘带 1.62~2.35——几何只管满带，镂空在 shader alpha 里做，
 * 一网格双天体）。倾斜/进动角=实例 uniform（模型旋转在环着色器 VS 里做），
 * 深度缓冲与母行星球真穿插——Canvas「前后两弧法」补丁文化就此消解。
 *
 * 顶点布局：位置 3f（cosθ, 0, sinθ × r）+ 径向 01 1f + 角向 01 1f = 5 float；
 * 索引化三角形（每环段两三角），[SEGMENTS] 环段。
 */
internal object GlRingMesh {

    /** 环段数（一周 quad 数——128=贴脸也圆） */
    const val SEGMENTS = 128

    /** 内半径（×行星半径）——吸积盘带内缘 1.62 的几何余量 */
    const val INNER = 1.24f

    /** 外半径（×行星半径）——吸积盘外缘 2.35（土星可见带 2.20 收入其中） */
    const val OUTER = 2.35f

    /** 顶点步长（float）：px,py,pz, radial01, theta01 */
    const val STRIDE = 5

    /** 顶点数=(SEGMENTS+1)×2（接缝复制一格——零接缝处理） */
    val vertexCount: Int get() = (SEGMENTS + 1) * 2

    /** 索引数=SEGMENTS×6 */
    val indexCount: Int get() = SEGMENTS * 6

    /** 顶点数组：每环段内/外两顶点（radial01=0 内缘/1 外缘；theta01=θ/2π） */
    fun buildVertices(): FloatArray {
        val out = FloatArray(vertexCount * STRIDE)
        var o = 0
        for (i in 0..SEGMENTS) {
            val th = (i.toDouble() / SEGMENTS) * 2.0 * Math.PI
            val c = cos(th).toFloat()
            val s = sin(th).toFloat()
            val t01 = i.toFloat() / SEGMENTS
            // 内缘顶点
            out[o++] = c * INNER; out[o++] = 0f; out[o++] = s * INNER
            out[o++] = 0f; out[o++] = t01
            // 外缘顶点
            out[o++] = c * OUTER; out[o++] = 0f; out[o++] = s * OUTER
            out[o++] = 1f; out[o++] = t01
        }
        return out
    }

    /** 索引数组（GL_TRIANGLES；顶点数 258 < Short 上限） */
    fun buildIndices(): ShortArray {
        val out = ShortArray(indexCount)
        var o = 0
        for (i in 0 until SEGMENTS) {
            val a = i * 2       // 本段内缘
            val b = i * 2 + 1   // 本段外缘
            val c = i * 2 + 2   // 下段内缘
            val d = i * 2 + 3   // 下段外缘
            // 顶面向上（+Y 法线绕序）；双面都画（背面剔除关——从环下方看也在）
            out[o++] = a.toShort(); out[o++] = b.toShort(); out[o++] = c.toShort()
            out[o++] = b.toShort(); out[o++] = d.toShort(); out[o++] = c.toShort()
        }
        return out
    }
}
