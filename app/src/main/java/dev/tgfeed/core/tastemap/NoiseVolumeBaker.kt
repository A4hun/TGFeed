package dev.tgfeed.core.tastemap

import kotlin.math.floor

/**
 * R392 星图性能正治·噪声载体革命——平铺 3D fbm 烘焙器（笔A 件，纯 Kotlin 零 Android）。
 *
 * 用途：NEBULA_FS / RAYMARCH_FS 的噪声载体。原两条 FS 逐像素跑 4 次 3 octave 3D fbm
 * （96 次 hash 每像素、highp，中端 Mali 填充率被打穿，实测 20fps 档——r392_notes §1 账）；
 * 本件把同一噪声场从逐像素 ALU 换成预烘焙 64³ R8 3D 纹理（256KB）一次采样。
 * 判词引 r392_notes §2：
 *  - 判词①（载体革命）：噪声的构造（4 次采样配比/域扭曲/脊线/门/色温分层）逐字保留在
 *    shader 侧，噪声的载体从即时演算改预烘焙——同一噪声场，换载体不换基因；
 *  - 判词②（种子语义平移）：原 q.z=uSeed 切片改作窗内平移偏移（seedOff=fract(seed×三质数)），
 *    同一「同雾同絮」确定性身份，场统计分布不变。
 *
 * 窗/周期对表（与 shader 侧单源对表）：
 *  - [WINDOW]=16 为 q 空间周期窗——shader 采样坐标 ×0.0625（=1/16）后 REPEAT 平铺无缝；
 *  - fbm 三层周期：基频 16 格（=WINDOW）、octave2（p×2+7.3）周期 32、octave3（p×4+13.1）周期 64；
 *  - 噪声数学逐字移植自 GlShaders.NOISE_3D（hash3/vnoise3/fbm3；GLSL fract=x−floor(x)，
 *    负值取小数正部——Kotlin 的 % 是截断余数不是 fract）。
 *
 * 平铺机制两件：①入口裹窗（wrap 到 [0,WINDOW)，2 的幂周期取模全程精确——整周期平移后
 * 位级同值，三层 octave 在固定量级带求值，跨周期零 fp32 进位差）；②vnoise3Tiled 的 8 个
 * 格点角坐标在哈希前对周期取数学模（floorMod 语义，负值同裹）——窗界两侧角点同源，
 * 纹理 REPEAT 天然无缝。
 *
 * 耗时预期：一次性 100~300ms（64³×24 hash——GlTextureBaker.bakeCmb 同款一次性口径），
 * 进程级 [bakeMemo] 单焙后页内重进零成本；不做磁盘缓存（一次性税不值得 I/O 面）。
 * 确定性：全链纯函数零 Random 零系统钟——同版本同纹理（R 系铁律）。
 */
object NoiseVolumeBaker {

    /** 体素边长（64³ R8 = 256KB——GL_R8 单通道 3D 纹理） */
    const val SIZE = 64

    /** q 空间周期窗（shader 侧 0.0625=1/16 与它对表——texture(p×0.0625+seedOff) REPEAT） */
    const val WINDOW = 16f

    // ==================== 烘焙（一次性税——判词①载体革命） ====================

    /**
     * 烘焙整体：SIZE³ 字节，x 最快、y 次之、z 切片（GL_TEXTURE_3D 直传序，
     * idx=x+SIZE×(y+SIZE×z)）；采样点=体素中心映射 q 域 [0,WINDOW)：
     * q=(i+0.5)/SIZE×WINDOW；值=fbm3Tiled clamp [0,1] ×255 取整转 Byte。
     * 纯函数零 Random 零系统钟——两次调用逐字节相等。
     */
    fun bake(): ByteArray {
        val out = ByteArray(SIZE * SIZE * SIZE)
        var idx = 0
        var z = 0
        while (z < SIZE) {
            val qz = (z + 0.5f) / SIZE * WINDOW
            var y = 0
            while (y < SIZE) {
                val qy = (y + 0.5f) / SIZE * WINDOW
                var x = 0
                while (x < SIZE) {
                    val qx = (x + 0.5f) / SIZE * WINDOW
                    out[idx] = quantize(fbm3Tiled(qx, qy, qz))
                    idx++
                    x++
                }
                y++
            }
            z++
        }
        return out
    }

    @Volatile
    private var memo: ByteArray? = null

    /**
     * 进程级单焙（@Volatile 双检 + synchronized 锁）：重复调用返回**同一引用**，
     * 页内重进零成本；EGL 丢失重建由渲染器重走上传链（纹理对象重传非重焙）。
     */
    fun bakeMemo(): ByteArray {
        memo?.let { return it }
        synchronized(this) {
            memo?.let { return it }
            val baked = bake()
            memo = baked
            return baked
        }
    }

    /** 量化：clamp [0,1] ×255 截断取整转 Byte（与 shader R8 归一化语义对表） */
    private fun quantize(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f).toInt().toByte()

    // ==================== fbm3 双形态（平铺载体 / 无 wrap 参考） ====================

    /**
     * 平铺版 fbm3：周期 [WINDOW]=16（三轴同）。入口先裹窗（wrap 到 [0,WINDOW)），
     * 三层 octave 周期 16/32/64（p×2+7.3 域 32、p×4+13.1 域 64），权重式原样：
     * (n1×0.5 + n2×0.25 + n3×0.125) / 0.875。
     */
    fun fbm3Tiled(x: Float, y: Float, z: Float): Float {
        val xm = wrapF(x, WINDOW)
        val ym = wrapF(y, WINDOW)
        val zm = wrapF(z, WINDOW)
        val n1 = vnoise3Tiled(xm, ym, zm, WINDOW)
        val n2 = vnoise3Tiled(xm * 2f + 7.3f, ym * 2f + 7.3f, zm * 2f + 7.3f, WINDOW * 2f)
        val n3 = vnoise3Tiled(xm * 4f + 13.1f, ym * 4f + 13.1f, zm * 4f + 13.1f, WINDOW * 4f)
        return (n1 * 0.5f + n2 * 0.25f + n3 * 0.125f) / 0.875f
    }

    /**
     * 无 wrap 直译参考（GlShaders.NOISE_3D 的 fbm3 逐字移植，无限域）——
     * 测试对照专用（分布 parity：平铺版与之同族同统计基因——mean/std/门比例对表）。
     */
    fun fbm3Reference(x: Float, y: Float, z: Float): Float {
        val n1 = vnoise3(x, y, z)
        val n2 = vnoise3(x * 2f + 7.3f, y * 2f + 7.3f, z * 2f + 7.3f)
        val n3 = vnoise3(x * 4f + 13.1f, y * 4f + 13.1f, z * 4f + 13.1f)
        return (n1 * 0.5f + n2 * 0.25f + n3 * 0.125f) / 0.875f
    }

    // ==================== 噪声数学（GlShaders.NOISE_3D 逐字移植——GLSL fract 语义） ====================

    /** GLSL fract = x − floor(x)（负值取小数正部——Kotlin 的 % 是截断余数不是 fract） */
    private fun fractF(x: Float): Float = x - floorF(x)

    /** fp32 floor（Double 往返精确——|x|<2^24 时 floor 值整数精确可表示，之外 x 本即整数） */
    private fun floorF(x: Float): Float = floor(x.toDouble()).toFloat()

    /** 数学取模（floorMod 语义，周期为 2 的幂时全程精确）：结果 ∈ [0,period)，负值正确裹回 */
    private fun wrapF(v: Float, period: Float): Float = v - floorF(v / period) * period

    /** GLSL mix(a,b,t) = a×(1−t)+b×t */
    private fun mixF(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t

    /** hash3 逐字移植：p=fract(p×0.3183099+(0.1,0.17,0.13))；p×=17；fract(三积×和) */
    private fun hash3(x: Float, y: Float, z: Float): Float {
        var px = fractF(x * 0.3183099f + 0.1f)
        var py = fractF(y * 0.3183099f + 0.17f)
        var pz = fractF(z * 0.3183099f + 0.13f)
        px *= 17f
        py *= 17f
        pz *= 17f
        return fractF(px * py * pz * (px + py + pz))
    }

    /** vnoise3 逐字移植（无限域——floor/fract/8 角 hash/三线性 u=f×f×(3−2f)） */
    private fun vnoise3(x: Float, y: Float, z: Float): Float {
        val ix = floorF(x)
        val iy = floorF(y)
        val iz = floorF(z)
        return trilerp(
            ix, iy, iz,
            ix + 1f, iy + 1f, iz + 1f,
            x - ix, y - iy, z - iz
        )
    }

    /** vnoise3 平铺版：8 个格点角坐标在哈希前对 [period] 取数学模（floorMod——负值同裹） */
    private fun vnoise3Tiled(x: Float, y: Float, z: Float, period: Float): Float {
        val ix = floorF(x)
        val iy = floorF(y)
        val iz = floorF(z)
        return trilerp(
            wrapF(ix, period), wrapF(iy, period), wrapF(iz, period),
            wrapF(ix + 1f, period), wrapF(iy + 1f, period), wrapF(iz + 1f, period),
            x - ix, y - iy, z - iz
        )
    }

    /** 8 角 hash + 三线性（u=f×f×(3−2f)——与 GLSL 段同序同式同嵌套） */
    private fun trilerp(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        fx: Float, fy: Float, fz: Float
    ): Float {
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val uz = fz * fz * (3f - 2f * fz)
        val c000 = hash3(x0, y0, z0)
        val c100 = hash3(x1, y0, z0)
        val c010 = hash3(x0, y1, z0)
        val c110 = hash3(x1, y1, z0)
        val c001 = hash3(x0, y0, z1)
        val c101 = hash3(x1, y0, z1)
        val c011 = hash3(x0, y1, z1)
        val c111 = hash3(x1, y1, z1)
        return mixF(
            mixF(mixF(c000, c100, ux), mixF(c010, c110, ux), uy),
            mixF(mixF(c001, c101, ux), mixF(c011, c111, ux), uy),
            uz
        )
    }
}
