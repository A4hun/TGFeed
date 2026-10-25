package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.CmbBackdropFx
import dev.tgfeed.core.tastemap.TasteStarGlow
import kotlin.math.sqrt

/**
 * R277 GL 纹理烘焙件（**纯 Kotlin 零 android 依赖**：产出 ARGB IntArray，调用方
 * （TasteGlRenderer）负责贴 GL——数学可 JVM 单测）。
 *
 * R281 注：**行星六件套位图族与黑洞吸积盘纹随 P2 退役**（行星=UV 球真身 PLANET
 * shader 程序表面；吸积盘=GlRingMesh 真 3D 环带+较差自转 shader——位图烘焙路线
 * 是 Canvas 时代的沉没件，_TEXTURE 侧只留环/光刺/光柱/圆盘/雾/白六小件）。
 */
internal object GlTextureBaker {

    const val RING_SIZE = 64
    const val SPIKE_W = 64
    const val SPIKE_H = 8
    const val BEAM_W = 32
    const val BEAM_H = 256
    const val DISC_SIZE = 64
    const val FOG_SIZE = 128
    const val WHITE_SIZE = 4

    /** R319 CMB 底幕纹理边长（[CmbBackdropFx.TEX_SIZE] 单源同值——白噪斑驳 alpha 图） */
    const val CMB_SIZE = CmbBackdropFx.TEX_SIZE

    // ==================== 小件（环/光刺/光柱） ====================

    /**
     * 通用细环纹理（64×64 白环——选中环/锁定环/徽章环/光子环/引力波纹共用一张，
     * 绘制端着色+缩环宽）：径向带 d∈[0.84,0.97] smoothstep 峰 0.905。
     */
    fun bakeRing(): IntArray {
        val size = RING_SIZE
        val c = size / 2f
        val px = IntArray(size * size)
        for (yy in 0 until size) {
            for (xx in 0 until size) {
                val dx = (xx + 0.5f - c) / c
                val dy = (yy + 0.5f - c) / c
                val d = sqrt(dx * dx + dy * dy)
                val a = smooth01((d - 0.84f) / 0.065f) * (1f - smooth01((d - 0.905f) / 0.065f))
                px[yy * size + xx] = ((a * 255f).toInt() shl 24) or 0xFFFFFF
            }
        }
        return px
    }

    /** 衍射光刺（64×8——R339 剖面：水平 (1−u)³ 快衰减 [TasteStarGlow.spikeAlpha01]
     *  单源（根=SPIKE_A 0.75——「刺」不是「棒」半芒即近灭 0.094；绘制端 quad
     *  alpha=星亮×pointFormAlpha 不再叠 SPIKE_A）；纵向三行柔边原样（细线亚
     *  像素抗锯齿）。R242 五停点线性渐变（峰 0.90）随 r 门十字退役——GL 芒形
     *  与 core 剖面单源对齐（Canvas 双子 buildSpike 随 R345 退役批走）。 */
    fun bakeSpike(): IntArray {
        val px = IntArray(SPIKE_W * SPIKE_H)
        for (yy in 0 until SPIKE_H) {
            // 纵向柔边：中心行（3..4）满、2/5 行 0.43、1/6 行 0.16（细线抗锯齿）
            val rowK = when (yy) {
                3, 4 -> 1f
                2, 5 -> 0.43f
                1, 6 -> 0.16f
                else -> 0f
            }
            for (xx in 0 until SPIKE_W) {
                val t = (xx + 0.5f) / SPIKE_W
                // 距心归一 u（中心 0 双向对称——quad 双臂共用同一纹理）
                val u = if (t < 0.5f) 0.5f - t else t - 0.5f
                val a = TasteStarGlow.spikeAlpha01(u * 2f) * rowK
                px[yy * SPIKE_W + xx] = ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF
            }
        }
        return px
    }

    /** 灯塔光柱（32×256——镜像 buildBeam：顶端（远端）透明 → 底端（星端）实——
     *  绘制端贴星心为底；中央亮条 0.72α + 两侧 0.31α） */
    fun bakeBeam(): IntArray {
        val px = IntArray(BEAM_W * BEAM_H)
        for (yy in 0 until BEAM_H) {
            val t = (yy + 0.5f) / BEAM_H
            val a = t // 顶 0（远端）→ 底满（星端）
            for (xx in 0 until BEAM_W) {
                val colK = when {
                    xx in 6..25 -> 0.72f
                    else -> 0.31f
                }
                px[yy * BEAM_W + xx] = (((a * colK).coerceIn(0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF
            }
        }
        return px
    }

    // ==================== R280 P1：雾 / 纯色 ====================

    /**
     * 未探索星云雾纹理（128×128 白件——镜像 fogBitmapOf：径向渐变 心 ~75%→缘透明；
     * 绘制端四边形着色（类别雾色）+alpha=TasteNebula.fogAlpha(dist)）。
     */
    fun bakeFog(): IntArray {
        val size = FOG_SIZE
        val c = size / 2f
        val px = IntArray(size * size)
        for (yy in 0 until size) {
            for (xx in 0 until size) {
                val dx = (xx + 0.5f - c) / c
                val dy = (yy + 0.5f - c) / c
                val d = sqrt(dx * dx + dy * dy).coerceAtMost(1f)
                val a = (1f - d) * 0.75f // 心 0xC0≈0.75 → 缘 0（线性=RadialGradient 单段）
                px[yy * size + xx] = (((a * 255f).toInt()) shl 24) or 0xFFFFFF
            }
        }
        return px
    }

    /** 纯色白件（4×4 全实——虫洞暗幕/引擎脉冲芯等着色四边形的无纹理底） */
    fun bakeWhite(): IntArray = IntArray(WHITE_SIZE * WHITE_SIZE) { -1 } // 0xFFFFFFFF

    // ==================== R319 CMB 底幕（宇宙微波背景余辉） ====================

    /**
     * CMB 底幕斑驳纹理（640×640 白噪 alpha 图——温度涨落那张著名斑驳图的极淡版）：
     * 像素 = 白色 × [CmbBackdropFx.noise01At] 值噪声（3 octave，种子=[seedFp]
     * **基因指纹 genomeFp**——同库同纹理、换库换底色，确定性铁律）。绘制端着色：
     * Canvas=PorterDuff 滤镜（温度档量化桶）/GL=quad 逐像素色调（[GlFrameBuilder.TEX_CMB]
     * 四边形 r,g,b,alpha）——**Canvas/GL 双侧消费同一份烘焙 IntArray（单源）**；
     * 帧路径零重算（位图按 genomeFp 缓存，GL 纹理按 genomeFp 变更才重传）。
     */
    fun bakeCmb(seedFp: Long): IntArray {
        val size = CMB_SIZE
        val px = IntArray(size * size)
        val seed = ((seedFp xor (seedFp ushr 32)).toInt())
        for (yy in 0 until size) {
            val v = (yy + 0.5f) / size
            for (xx in 0 until size) {
                val u = (xx + 0.5f) / size
                val n = CmbBackdropFx.noise01At(u, v, seed)
                // 斑驳对比拉伸：低频底 + 涨落斑（a∈[0,255]——全局透明度绘制端再乘）
                val a = ((n * n * 0.72f + n * 0.28f) * 255f).toInt().coerceIn(0, 255)
                px[yy * size + xx] = (a shl 24) or 0xFFFFFF
            }
        }
        return px
    }

    /**
     * bakeCmb 单条目记忆化（[seedFp] 变更才重烘——410k 噪声求值毫秒级~几十毫秒
     * 只付一次；**预热在 Stage 装载 effect（IO 协程）**，GL 线程上传/Canvas 位图
     * 转换帧内恒缓存命中）。线程安全：@Synchronized（IO 预热/GL/绘制三线程共用）。
     */
    @Synchronized
    fun bakeCmbCached(seedFp: Long): IntArray {
        if (seedFp == cmbMemoSeed && cmbMemo != null) return cmbMemo!!
        val px = bakeCmb(seedFp)
        cmbMemoSeed = seedFp
        cmbMemo = px
        return px
    }

    private var cmbMemoSeed = Long.MIN_VALUE
    private var cmbMemo: IntArray? = null

    // ==================== 纯函数小件 ====================

    /** 实心硬边圆盘（64×64 白盘——d≤0.94 全实、0.94..1 抗锯齿缘；着色绘制端做） */
    fun bakeDisc(): IntArray {
        val size = DISC_SIZE
        val c = size / 2f
        val px = IntArray(size * size)
        for (yy in 0 until size) {
            for (xx in 0 until size) {
                val dx = (xx + 0.5f - c) / c
                val dy = (yy + 0.5f - c) / c
                val d = sqrt(dx * dx + dy * dy)
                val a = 1f - smooth01((d - 0.94f) / 0.06f)
                px[yy * size + xx] = ((a * 255f).toInt() shl 24) or 0xFFFFFF
            }
        }
        return px
    }

    private fun smooth01(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** 确定性 0..1 哈希（R345 前与 TasteFlightRenderer.h01 同式镜像——同种子同散布；现唯一实现） */
    internal fun h01(a: Int, b: Int): Float =
        (((a * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF).toFloat() / 16777216f
}
