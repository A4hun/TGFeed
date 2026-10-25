package dev.tgfeed.ui.gl

import kotlin.math.sqrt

/**
 * R281 岩石多面体网格（**纯 Kotlin 零 android 依赖**——小行星/尘埃/彗核/流星体
 * 的几何底座；初始化期一次建成全层共享）。
 *
 * 形制：二十面体细分一次（42 独立顶点 80 面）→ **三角形汤**（非索引——每面三
 * 顶点携带平坦面法线，低多边形凿面感=岩块质感本身）。不规则性不在网格里烘死——
 * ROCK 着色器按实例种子对单位方向做径向位移（同一方向位移相同=汤缝两侧视觉
 * 水密），一个网格服务全部变体（变体=种子函数，零额外面片）。
 *
 * 面表拓扑：12 顶点（±1,±t,0)/(0,±1,±t)/(±t,0,±1) 按添加序编号，20 面由
 * 邻接分析推出（每顶点五邻环）；**绕序由 buildVertices 逐面自动定向**（法线与
 * 面心外点积为负即翻转——面表只载拓扑，方向永不翻车）。
 *
 * 顶点布局：位置 3f（单位方向）+ 面法线 3f = 6 float/顶点，共 240 顶点。
 */
internal object GlRockMesh {

    /** 黄金比（二十面体构造常数） */
    private const val T = 1.618033988749895

    /** 细分后面数（20×4）与三角汤顶点数（80×3） */
    const val FACE_COUNT = 80
    const val SOUP_VERTS = FACE_COUNT * 3

    /** 顶点步长（float）：px,py,pz,nx,ny,nz */
    const val STRIDE = 6

    /** 细分后独立顶点数（12 原始 + 30 棱中点） */
    const val VERT_COUNT = 42

    /**
     * 二十面体面表（拓扑正确性=每棱被恰好两个面共享；绕序由构建器自动定向）。
     * 顶点编号与 buildVertices 添加序一致：0..3=(±1,±t,0) 4..7=(0,±1,±t) 8..11=(±t,0,±1)。
     */
    private val FACES = intArrayOf(
        0, 1, 2, 0, 2, 8, 0, 8, 4, 0, 4, 6, 0, 6, 1,
        1, 2, 7, 1, 7, 5, 1, 5, 6,
        2, 3, 7, 2, 8, 3,
        3, 7, 9, 3, 9, 10, 3, 10, 8,
        4, 6, 11, 4, 11, 10, 4, 10, 8,
        5, 6, 11, 5, 11, 9, 5, 9, 7,
        9, 11, 10,
    )

    /**
     * 三角汤顶点数组（[SOUP_VERTS]×[STRIDE] float）：每面三顶点（外向绕序），
     * 法线=该面平坦法线（normalize((b−a)×(c−a))，外向由自动定向保证）。
     */
    fun buildVertices(): FloatArray {
        // ---- 二十面体 12 顶点（单位球面上；添加序=面表编号） ----
        val verts = FloatArray(VERT_COUNT * 3)
        var n = 0
        fun addV(x: Double, y: Double, z: Double): Int {
            val len = sqrt(x * x + y * y + z * z)
            verts[n * 3] = (x / len).toFloat()
            verts[n * 3 + 1] = (y / len).toFloat()
            verts[n * 3 + 2] = (z / len).toFloat()
            return n++
        }
        for (s1 in intArrayOf(1, -1)) for (s2 in intArrayOf(1, -1)) {
            addV(s1 * 1.0, s2 * T, 0.0)
            addV(0.0, s1 * 1.0, s2 * T)
            addV(s2 * T, 0.0, s1 * 1.0)
        }
        // ---- 细分一次：棱中点缓存（棱键=小端×64+大端——同棱共享中点不裂缝） ----
        val midCache = HashMap<Int, Int>(30)
        fun midOf(a: Int, b: Int): Int {
            val lo = minOf(a, b); val hi = maxOf(a, b)
            val key = lo * 64 + hi
            midCache[key]?.let { return it }
            val idx = addV(
                (verts[a * 3] + verts[b * 3]) / 2.0,
                (verts[a * 3 + 1] + verts[b * 3 + 1]) / 2.0,
                (verts[a * 3 + 2] + verts[b * 3 + 2]) / 2.0,
            )
            midCache[key] = idx
            return idx
        }
        val subFaces = IntArray(FACE_COUNT * 3)
        var w = 0
        for (f in 0 until 20) {
            val a = FACES[f * 3]; val b = FACES[f * 3 + 1]; val c = FACES[f * 3 + 2]
            val ab = midOf(a, b); val bc = midOf(b, c); val ca = midOf(c, a)
            subFaces[w++] = a; subFaces[w++] = ab; subFaces[w++] = ca
            subFaces[w++] = ab; subFaces[w++] = b; subFaces[w++] = bc
            subFaces[w++] = ca; subFaces[w++] = bc; subFaces[w++] = c
            subFaces[w++] = ab; subFaces[w++] = bc; subFaces[w++] = ca
        }
        // ---- 三角汤发射（逐面自动定向：法线·面心<0 即翻转绕序=外向保证） ----
        val out = FloatArray(SOUP_VERTS * STRIDE)
        var o = 0
        for (f in 0 until FACE_COUNT) {
            var ia = subFaces[f * 3]; var ib = subFaces[f * 3 + 1]; val ic = subFaces[f * 3 + 2]
            val ax = verts[ia * 3]; val ay = verts[ia * 3 + 1]; val az = verts[ia * 3 + 2]
            val bx = verts[ib * 3]; val by = verts[ib * 3 + 1]; val bz = verts[ib * 3 + 2]
            val cx = verts[ic * 3]; val cy = verts[ic * 3 + 1]; val cz = verts[ic * 3 + 2]
            var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
            var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
            var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            val fcx = (ax + bx + cx) / 3f; val fcy = (ay + by + cy) / 3f; val fcz = (az + bz + cz) / 3f
            if (nx * fcx + ny * fcy + nz * fcz < 0f) {
                val t = ia; ia = ib; ib = t // 翻转绕序=法线外向
                nx = -nx; ny = -ny; nz = -nz
            }
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            nx /= nl; ny /= nl; nz /= nl
            o = emitVert(out, o, verts[ia * 3], verts[ia * 3 + 1], verts[ia * 3 + 2], nx, ny, nz)
            o = emitVert(out, o, verts[ib * 3], verts[ib * 3 + 1], verts[ib * 3 + 2], nx, ny, nz)
            o = emitVert(out, o, cx, cy, cz, nx, ny, nz)
        }
        return out
    }

    private fun emitVert(
        out: FloatArray, o: Int,
        x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float,
    ): Int {
        out[o] = x; out[o + 1] = y; out[o + 2] = z
        out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz
        return o + STRIDE
    }
}
