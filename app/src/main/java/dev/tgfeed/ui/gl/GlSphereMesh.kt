package dev.tgfeed.ui.gl

import kotlin.math.cos
import kotlin.math.sin

/**
 * R277 真球网格生成件（**纯 Kotlin 零 android 依赖**——单位 UV 球：顶点=法线
 * （单位球上位置即法线），索引化三角形带；初始化期一次建成全层共享）。
 *
 * 试点口径（20:20 追加令②）：24 经×16 纬≈768 三角形——「绕着恒星飞有真正的
 * 侧面」的几何底座；法线即世界方向（星心为原点），fragment 侧 3D 噪声锚法线=
 * 绕飞视差/自转颗粒的采样位。
 *
 * R349 LOD 双档（r341 §2.1 mesh 档——门槛线 [TasteLodPolicy.LOWMESH_MAX_DP]
 * =4dp，R355 dp 化）：[buildVertices]/[buildIndices] 参数化——全模 24×16（≥4dp
 * 贴脸质感档）与低模 8×6（96 三角；[1.6,4)dp instancing 批档
 * [GlSphereMesh.LOD_SLICES/LOD_STACKS]）同一生成式（一类一件：换模=换参数非换函数）。
 */
internal object GlSphereMesh {

    /** 经向切片数（赤道一周 quad 数）——全模档 */
    const val SLICES = 24

    /** 纬向层数（极到极 quad 数）——全模档 */
    const val STACKS = 16

    /** 经向切片数——R349 低模档（8×6=48 格=96 三角；r341 §4.2「低模球 96 tris」字面） */
    const val LOD_SLICES = 8

    /** 纬向层数——R349 低模档 */
    const val LOD_STACKS = 6

    /**
     * R409b 高模贴脸档经向切片数（96×64=12288 三角；顶点 97×65=6305 < Short 上限）。
     * 用户「近距离看恒星建模精度低」：24 切剪影弦高 r(1−cos(π/24))≈0.0086r——屏半径
     * 1000px 时折边≈8.6px 肉眼可辨；96 切≈0.00054r≈0.5px=亚像素。仅屏半径超
     * [HI_MIN_RADIUS_PX] 的全模真球/行星换此档（远中档零变化）。
     */
    const val HI_SLICES = 96

    /** R409b 高模贴脸档纬向层数 */
    const val HI_STACKS = 64

    /** R409b 换高模门槛（屏半径 px）：24 切在此半径弦高≈0.9px——门内全模已亚像素级 */
    const val HI_MIN_RADIUS_PX = 100f

    /** 顶点数 = (STACKS+1)×(SLICES+1)（纬向接缝复制一列——零接缝处理） */
    val vertexCount: Int get() = (STACKS + 1) * (SLICES + 1)

    /** 低模顶点数（(6+1)×(8+1)=63） */
    val lodVertexCount: Int get() = (LOD_STACKS + 1) * (LOD_SLICES + 1)

    /** 三角形数（全模 768——r341 §4.2 预算账字面锚） */
    val triangleCount: Int get() = STACKS * SLICES * 2

    /** 三角形数（低模 96——r341 §4.2「低模球 96 tris×≤96=9.2k」预算账字面锚） */
    val lodTriangleCount: Int get() = LOD_STACKS * LOD_SLICES * 2

    /**
     * 顶点（法线）数组：每顶点 3 float（x,y,z 单位球面）——Y 轴为极轴
     * （与 TasteSpace 世界系 Y 向上同轴；颗粒采样直吃法线）。[slices]/[stacks]
     * 缺省=全模档（既有调用零改）。
     */
    fun buildVertices(slices: Int = SLICES, stacks: Int = STACKS): FloatArray {
        val out = FloatArray((stacks + 1) * (slices + 1) * 3)
        var i = 0
        for (stack in 0..stacks) {
            val phi = Math.PI * (stack.toDouble() / stacks - 0.5) // −π/2..π/2（北正）
            val cp = cos(phi)
            val y = sin(phi)
            for (slice in 0..slices) {
                val theta = 2.0 * Math.PI * slice / slices
                out[i++] = (cp * cos(theta)).toFloat()
                out[i++] = y.toFloat()
                out[i++] = (cp * sin(theta)).toFloat()
            }
        }
        return out
    }

    /**
     * 索引数组（GL_TRIANGLES；每格两三角）：(stacks)×(slices)×6 个 Short——
     * 顶点数 25×17=425 < Short 上限，零压力（低模 63 顶点同构）。[slices]/
     * [stacks] 缺省=全模档。
     */
    fun buildIndices(slices: Int = SLICES, stacks: Int = STACKS): ShortArray {
        val out = ShortArray(stacks * slices * 6)
        var i = 0
        val row = slices + 1
        for (stack in 0 until stacks) {
            for (slice in 0 until slices) {
                val a = stack * row + slice
                val b = a + row
                // 外向绕序（从球外看逆时针——GL 默认正面 CCW；P0 背面剔除关闭，
                // 绕序仅为完备性）
                out[i++] = a.toShort()
                out[i++] = b.toShort()
                out[i++] = (a + 1).toShort()
                out[i++] = (a + 1).toShort()
                out[i++] = b.toShort()
                out[i++] = (b + 1).toShort()
            }
        }
        return out
    }
}
