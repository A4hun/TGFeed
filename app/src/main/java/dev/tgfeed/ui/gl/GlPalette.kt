package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.CelestialKind

/**
 * R277 GL 侧颜色数学（**纯 Kotlin 零 android 依赖**——android.graphics.Color 在
 * JVM 单测桩下静默返回 0（isReturnDefaultValues），星色/色温/微星桶的 HSV 数学
 * 必须自写；R345 前与 Canvas TasteFlightRenderer 同名件同式镜像（Canvas 退役后本件唯一；P1 收敛挂账：提取共享
 * 调色件进 core——见 r277_notes「文件清单」注）。
 *
 * 常量口径 R345 前与 TasteFlightRenderer 逐值一致（退役后本件为唯一源）。
 */
internal object GlPalette {

    // ---- 色温档（原镜像 TasteFlightRenderer：六态映射+色相分类——R345 后唯一源） ----
    const val TEMP_WARM = 0
    const val TEMP_COOL = 1
    const val TEMP_WHITE = 2

    /** 暖星核色（奶白热） */
    const val CORE_WARM = 0xFFF8E9CF.toInt()

    /** 冷星核色（蓝白炽） */
    const val CORE_COOL = 0xFFE6EFFF.toInt()

    /** 白星核色（纯白） */
    const val CORE_WHITE = 0xFFF7F9FF.toInt()

    /** 超新星暖光偏色 */
    const val SUPERNOVA_TINT = 0xFFFFE9C4.toInt()

    /** R317b 致密星蓝白偏色（中子星/夸克星——R345 前与 TasteFlightRenderer.COMPACT_TINT 同值；现唯一源） */
    const val COMPACT_TINT = 0xFFBFD8FF.toInt()

    /** R317b 白洞喷流偏色（R345 前与 TasteFlightRenderer.WHITEHOLE_TINT 同值；现唯一源） */
    const val WHITEHOLE_TINT = 0xFFF6EFFF.toInt()

    /** 原恒星蓝白偏色 */
    const val PROTOSTAR_TINT = 0xFFA9D4FF.toInt()

    /** 星团基色（暖米白） */
    const val CLUSTER_COLOR = 0xFFE3DCC6.toInt()

    /** 白矮星白核色 */
    const val WHITE_DWARF_CORE = 0xFFF6FAFF.toInt()

    /** 黑洞吸积盘热色（内环炽白金）/冷色（外环橙红） */
    const val DISK_HOT = 0xFFFFE9C4.toInt()
    const val DISK_COOL = 0xFFD89058.toInt()

    // ---- 微星点云（镜像：色相 8 扇区×明暗 2 档+纯白/暗灰 2 桶） ----
    const val MICRO_BUCKETS = 18
    const val MICRO_DIM = 16
    const val MICRO_WHITE = 17

    /** 微星桶代表色表（初始化期一次算定——镜像 microColors） */
    val microColors: IntArray = IntArray(MICRO_BUCKETS).also { colors ->
        for (b in 0 until MICRO_BUCKETS) {
            colors[b] = when (b) {
                MICRO_DIM -> 0xFF96A9C9.toInt()
                MICRO_WHITE -> 0xFFF2F6FF.toInt()
                else -> {
                    val sector = b / 2
                    val bright = b % 2 == 1
                    hsvToRgb(sector * 45f + 22f, 0.49f, if (bright) 0.80f else 0.66f)
                }
            }
        }
    }

    /** ARGB → HSV（写 [out] 长 3：h∈[0,360) s∈[0,1] v∈[0,1]——与 android Color.colorToHSV 同式） */
    fun hsvOf(argb: Int, out: FloatArray) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b).toFloat()
        val min = minOf(r, g, b).toFloat()
        val delta = max - min
        out[2] = max / 255f
        out[1] = if (max <= 0f) 0f else delta / max
        if (delta <= 0f) {
            out[0] = 0f
            return
        }
        var h = when {
            max == r.toFloat() -> ((g - b) / delta) % 6f
            max == g.toFloat() -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
        if (h < 0f) h += 360f
        out[0] = h
    }

    /** HSV → ARGB（alpha=0xFF——与 android Color.HSVToColor 同式） */
    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** HSV → RGB float 三通道（写 [out] 长 3，0..1——着色器 uniform 原料） */
    fun hsvToRgbF(h: Float, s: Float, v: Float, out: FloatArray) {
        val argb = hsvToRgb(h, s, v)
        out[0] = ((argb shr 16) and 0xFF) / 255f
        out[1] = ((argb shr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
    }

    /** ARGB → RGB float 三通道（写 [out] 长 3，0..1——alpha 通道丢弃） */
    fun rgbF(argb: Int, out: FloatArray) {
        out[0] = ((argb shr 16) and 0xFF) / 255f
        out[1] = ((argb shr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
    }

    /** 微星量化桶 id（镜像 microBucketOf：低饱和归白/暗桶，其余 8 扇区×明暗 2 档） */
    fun microBucketOf(argb: Int, bright: Boolean, hsvScratch: FloatArray): Int {
        hsvOf(argb, hsvScratch)
        if (hsvScratch[1] < 0.18f) return if (bright) MICRO_WHITE else MICRO_DIM
        val sector = (hsvScratch[0] / 45f).toInt().coerceIn(0, 7)
        return sector * 2 + (if (bright) 1 else 0)
    }

    /** 色温档（镜像 tempClassOf：六态映射优先，恒星按色相暖冷） */
    fun tempClassOf(kind: CelestialKind, colorArgb: Int, hsvScratch: FloatArray): Int {
        when (kind) {
            CelestialKind.SUPERNOVA, CelestialKind.WHITE_DWARF,
            CelestialKind.PROTOSTAR, CelestialKind.PULSAR,
            -> return TEMP_COOL
            CelestialKind.RED_GIANT -> return TEMP_WARM
            else -> {}
        }
        hsvOf(colorArgb, hsvScratch)
        val h = hsvScratch[0]
        return when {
            (h in 10f..70f) || h >= 340f -> TEMP_WARM
            h in 170f..310f -> TEMP_COOL
            else -> TEMP_WHITE
        }
    }

    /** 色温档核色（镜像 drawGlowStar 三档取色） */
    fun coreColorOf(temp: Int): Int = when (temp) {
        TEMP_WARM -> CORE_WARM
        TEMP_COOL -> CORE_COOL
        else -> CORE_WHITE
    }

    /** RGB 线性混色（镜像 blendArgb——alpha 丢弃） */
    fun blendRgb(a: Int, b: Int, mix: Float): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * mix).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * mix).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * mix).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }

    /** HSV 明度缩放（镜像 shiftValue——行星/环的亮暗版原料） */
    fun shiftValue(argb: Int, mul: Float, hsvScratch: FloatArray): Int {
        hsvOf(argb, hsvScratch)
        hsvScratch[2] = (hsvScratch[2] * mul).coerceIn(0.12f, 0.97f)
        return hsvToRgb(hsvScratch[0], hsvScratch[1], hsvScratch[2])
    }

    /** 红巨星暖偏（镜像 warmShift：色相 +28°） */
    fun warmShift(argb: Int, hsvScratch: FloatArray): Int {
        hsvOf(argb, hsvScratch)
        hsvScratch[0] = (hsvScratch[0] + 28f) % 360f
        hsvScratch[1] = (hsvScratch[1] + 0.05f).coerceAtMost(0.60f)
        return hsvToRgb(hsvScratch[0], hsvScratch[1], hsvScratch[2])
    }

    /** 色相 12 扇区（镜像 hsvSectorOf：低饱和归 0 档=中性） */
    fun hsvSectorOf(argb: Int, hsvScratch: FloatArray): Int {
        hsvOf(argb, hsvScratch)
        if (hsvScratch[1] < 0.15f) return 0
        return (hsvScratch[0] / 30f).toInt() % 12
    }
}
