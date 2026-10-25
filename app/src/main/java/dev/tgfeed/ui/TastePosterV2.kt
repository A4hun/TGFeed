package dev.tgfeed.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.tgfeed.core.playback.ShareCard
import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.TasteAstroPhysics
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteGalaxyClassifier
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteSpace.SpaceCam
import dev.tgfeed.core.tastemap.TasteStarColor
import dev.tgfeed.core.tastemap.TasteTerminalFx
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R322 导出海报 V2（R315 M 章——用户「导出海报有点老旧了」的现代化件）：
 * **「你此刻宇宙的电影剧照+观测报告」**——与星图本体同数据源同渲染口径，
 * R311/R312/R314 的星图新资产（连续色温/天体谱/光年口径）第一次变现到分享侧。
 *
 * 与 V1（[ShareCard.generateTasteMap]）的口径分界（双口径债收敛——M 章主刀）：
 *  - **星点=真渲染口径快照**：sp 三轴空间坐标 × 当前视角重投影（cam 快照
 *    机制=[TasteVoyagePoster] 既有同款）× R311 连续色温三段色（
 *    [TasteStarColor.coreColorOfK] 与 [TasteStarColor.edgeColorOfK] 直接调用
 *    ——海报星点与 App 同色源）× 体量主轴半径 bodyRadius（旧 radius 口径
 *    随 V1 一并退役留库，StarNode 旧字段不删）；
 *  - **光年数据行**（R314 资产变现）：[lightYearLineOf] 纯函数——「宇宙跨度：
 *    光行 X（最老的星诞生 N 天前）」，宇宙化替代旧日期标签；缺据=行不出现；
 *  - **天体谱徽章行**（R312/R317 资产变现）：[badgeChipsOf] 纯函数——kindCounts
 *    chips（黑洞 N/中子星 M/白洞 K…计数徽章=天体图鉴快照）；无特殊天体=诚实
 *    退化为恒星计数单徽章；
 *  - **版式现代化**：1080×1440 幅面不变（分享物料族一致性）+暗色底+渐晕
 *    （暗角收边）+深空星点底幕（[ShareCard.drawTasteMapDust] 同源撒点器）+
 *    大标题留白+数据角标（Wrapped 报告风）+底部品牌条（BRAND_H 同族）；
 *    旧图例行退役（V1 图例解释的是旧三档类型色——V2 星色是连续色温谱，
 *    无档可解，图例职责由徽章行接管）。
 *
 * 工程纪律：
 *  - 纯函数（[lightYearLineOf]/[badgeChipsOf]）零 Android 依赖零系统钟（nowMs
 *    注入）——M 章⑤单测锚位；重投影复用 [TasteSpace.project]（TasteSpaceTest
 *    既有锚——TasteVoyagePoster 同款单测锚模式，不自建投影）；
 *  - 名签避让复用 [ShareCard.placeTasteLabels]（R206 唯一实现——纪律§一）；
 *  - 特殊天体笔刷（黑洞/中子星/夸克星/白洞/进食档）=**R317b [TasteTerminalFx]
 *    形状参数单源的海报静态版**（渲染端 Canvas/GL 同式消费同一参数——本海报
 *    为第三端同源；动态件（自转/呼吸/缓漂）冻结为 t=0 静态签名）；
 *  - 生成走 IO 线程一次性位图（600 星毫秒级——离屏生成件与 60fps 无关，
 *    耗时由导出壳记账）；
 *  - CMB 底幕纹理=挂账（R315 A 段批 2 未落——落地后接 genomeFp 种子同 App）。
 */
object TastePosterV2 {

    /** 海报尺寸（与经典口味星图海报同幅——分享物料族一致性） */
    private const val W = 1080
    private const val H = 1440

    /** 左右留边（同经典海报） */
    private const val PAD = 64

    /** 底部品牌条高（ShareCard.BRAND_H 同值镜像——改就一起改） */
    private const val BRAND_H = 108

    // ---- 色板（ShareCard 家族值镜像——改就一起改；core 常量不可见故本地锚定） ----
    private val COLOR_BG = 0xFF0D1117.toInt()
    private val COLOR_TITLE = 0xFFF2F5FA.toInt()
    private val COLOR_CAPTION = 0xFF9AA6B5.toInt()
    private val COLOR_BRAND_BLUE = 0xFF54A9EB.toInt()
    private val COLOR_BRAND_BG = 0xFF101820.toInt()

    // ---- R249 黑洞笔刷常量（渲染器同值镜像——改就一起改；不引渲染器防双份依赖） ----

    /** 光子环半径系数（TasteFlightRenderer.PHOTON_RING 同值镜像） */
    private const val PHOTON_RING = 1.22f

    /** 黑洞视界存在感半径放大（渲染器 L1832 同式 r*1.45） */
    private const val BLACK_HOLE_R_BUMP = 1.45f

    /** 吸积盘暖色（TasteFlightRenderer.DISK_COOL 同值镜像） */
    private val DISK_COOL = 0xFFD89058.toInt()

    /** 光子环暖白（渲染器黑洞笔刷同值镜像） */
    private val PHOTON_COLOR = 0xFFFFF3DC.toInt()

    /** 视界纯黑（吞光——渲染器黑洞笔刷底色同观感） */
    private val HORIZON_COLOR = 0xFF05070C.toInt()

    /** 超新星暖 tint（渲染器 SUPERNOVA_TINT 同值镜像） */
    private val SUPERNOVA_TINT = 0xFFFFE9C4.toInt()

    /** 致密星（中子/夸克星）蓝白身份色（渲染器 COMPACT_TINT 同值镜像） */
    private val COMPACT_TINT = 0xFFBFD8FF.toInt()

    /** 白洞暖白身份色（渲染器 WHITEHOLE_TINT 同值镜像） */
    private val WHITEHOLE_TINT = 0xFFF6EFFF.toInt()

    /** 红巨星暖偏相移（渲染器 warmShift 的 +28 度同值镜像） */
    private const val WARM_SHIFT_DEG = 28f

    // ---- 星体三档（海报静态 LOD——App 渲染器三档口径的海报版阈值） ----

    /** 近星球档下限（三段色径向渐变光球+外晕） */
    private const val NEAR_STAR_R = 18f

    /** 中间档下限（温度色亮点盘+微弱白热芯） */
    private const val MID_STAR_R = 6f

    /** 名签挂靠最小投影像素（V1 labelMinPx=7f 同口径——小星不挂签） */
    private const val LABEL_MIN_PX = 7f

    // ==================== 纯函数（M 章⑤单测锚位——零 Android 零系统钟） ====================

    /** 天体谱徽章一枚（label=用户语言名，count=计数；kind=退化恒星档时为 STAR） */
    class PosterBadge(val kind: CelestialKind, val label: String, val count: Int)

    /**
     * M② 光年数据行（R314 资产变现——宇宙化替代旧日期标签）：
     * 「宇宙跨度：光行 X（最老的星诞生 N 天前）」——X=[TasteFlight.lightTravelOf]
     * （量程=最老星首见到最新星首见的跨度 rangeMs），N=最老星首见距今（[nowMs]
     * 注入——确定性）。[span] 无效（无首见证据）=null：行不出现（缺据不出红线，
     * 绝不冒充古老宇宙）。N 不足一天=「最老的星今天诞生」（新装/演示库诚实口径）。
     */
    fun lightYearLineOf(span: TasteFlight.GenesisSpan, nowMs: Long): String? {
        if (!span.valid) return null
        val days = (nowMs - span.minMs) / 86_400_000L
        if (days < 0L) return null // 未来时间戳=数据异常，诚实不出现
        val ageText = if (days < 1L) "最老的星今天诞生" else "最老的星诞生 $days 天前"
        return "宇宙跨度：光行 ${TasteFlight.lightTravelOf(span.rangeMs)}（$ageText）"
    }

    /**
     * M③ 天体谱徽章行（天体图鉴快照——「你的宇宙里此刻有什么」）：
     * 非 STAR 的 kindCounts 各成一徽章（label=[TasteGalaxyClassifier.labelOf]
     * 单一出处；序=枚举 ordinal——R223b 布局日志同口径确定性）；STAR 是缺省态
     * 不占徽章位。**诚实退化**：无任何特殊天体（含 kindCounts 空的装配失败态）
     * =单徽章「恒星 N」（N 取 kindCounts[STAR]，缺则 [fallbackStarCount]——
     * M 章⑤「无宣判天体=徽章行退化为恒星计数」的字面落法）。零/负计数过滤。
     */
    fun badgeChipsOf(kindCounts: Map<CelestialKind, Int>, fallbackStarCount: Int): List<PosterBadge> {
        val specials = ArrayList<PosterBadge>(kindCounts.size)
        for (kind in CelestialKind.entries) {
            if (kind == CelestialKind.STAR) continue
            val count = kindCounts[kind] ?: 0
            if (count <= 0) continue
            specials.add(PosterBadge(kind, TasteGalaxyClassifier.labelOf(kind), count))
        }
        if (specials.isNotEmpty()) return specials
        val starCount = kindCounts[CelestialKind.STAR] ?: fallbackStarCount
        return listOf(PosterBadge(CelestialKind.STAR, TasteGalaxyClassifier.labelOf(CelestialKind.STAR), starCount))
    }

    // ==================== 海报数据（屏侧组装——绘制原料） ====================

    /** V2 单星：sp 三轴（R242 空间口径）+ 体量口径三件（App 渲染器同源消费位） */
    data class V2Star(
        val spX: Float,
        val spY: Float,
        val spZ: Float,
        /** 体量主轴半径（App 渲染器/命中测试同口径——旧 radius 退役） */
        val bodyRadius: Float,
        /** 体量口径颜色（starColorOfK 黑体连续色温×构成微调——App 同源） */
        val bodyColor: Int,
        /** R311 连续色温（K；0=实体/特殊档身份色——三段色 useK 门同 App） */
        val tempK: Float,
        val kind: CelestialKind,
        /** 体量口径亮度 */
        val alpha: Float,
        val chatId: Long,
        /** 近 7 天条数（金矿口径；-1=无证据——黑洞进食档 accretion01 原料） */
        val recent7: Int,
        /** 名签（头部频道——App 侧选好；null=无签） */
        val label: String?,
    )

    /** V2 海报数据（屏侧一次性组装——cam/focalPxDevice=「当前视角」快照原料） */
    class V2CardData(
        val stars: List<V2Star>,
        val cam: SpaceCam,
        /** 设备端有效焦距（px）——海报按 fieldW/deviceCanvasW 等比换算保构图 */
        val focalPxDevice: Float,
        val deviceCanvasW: Int,
        /** 副行（N 个频道 · M 条互链 · 累计观看 X——组装侧文案） */
        val subLine: String,
        /** 光年数据行（[lightYearLineOf] 产出；null=行不出现——缺据诚实缺省） */
        val lightYearLine: String?,
        /** 天体谱徽章行（[badgeChipsOf] 产出） */
        val badges: List<PosterBadge>,
        /** 方法注脚（诚实标注——新版式口径声明） */
        val methodNote: String,
        /** 品牌行（TGFeed · 口味星图 · M月d日） */
        val brandLine: String,
    )

    // ==================== 生成（IO 线程——一次性位图） ====================

    /** 生成 V2 海报位图（1080×1440 竖版；600 星一次绘制毫秒级） */
    fun generate(data: V2CardData): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(COLOR_BG)
        var y = 108f

        // ---- 品牌行（电报蓝小字——家族首行不变） ----
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_BLUE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(data.brandLine, PAD.toFloat(), y, brandPaint)
        y += 64f

        // ---- 大标题（大留白——Wrapped 报告风） ----
        val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 76f
        canvas.drawText("我的口味星图", PAD.toFloat(), y, headlinePaint)
        y += 36f

        // ---- 副行（数据角标之一） ----
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 38f
        }
        y += 38f
        canvas.drawText(data.subLine, PAD.toFloat(), y, subPaint)
        y += 34f

        // ---- 光年数据行（R314 资产变现——缺据 null 行不出现=诚实缺省） ----
        if (data.lightYearLine != null) {
            val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_BRAND_BLUE
                textSize = 34f
            }
            y += 40f
            canvas.drawText(data.lightYearLine, PAD.toFloat(), y, lightPaint)
            y += 26f
        }

        // ---- 天体谱徽章行（chips——两行自适应换行） ----
        y += 12f
        y = drawBadgeRow(canvas, data.badges, y)

        // ---- 发丝分割线 ----
        canvas.drawRect(
            PAD.toFloat(), y, (W - PAD).toFloat(), y + 2f,
            Paint().apply { color = 0x14FFFFFF },
        )
        y += 26f

        // ---- 星图快照区（当前视角重投影） ----
        val fieldLeft = PAD.toFloat()
        val fieldTop = y
        val fieldW = (W - PAD * 2).toFloat()
        val noteH = 92f
        val fieldBottom = (H - BRAND_H - noteH - 36).toFloat()
        val fieldH = fieldBottom - fieldTop
        if (data.stars.isNotEmpty() && fieldW > 0f && fieldH > 0f) {
            val cX = fieldLeft + fieldW / 2f
            val cY = fieldTop + fieldH / 2f
            // 焦距等比换算：设备画布宽 → 海报视场宽（「当前视角」的构图保真）
            val ratio = if (data.deviceCanvasW > 0) fieldW / data.deviceCanvasW else 1f
            val focal = data.focalPxDevice * ratio
            val proj = FloatArray(4)
            // 深空星点底幕（ShareCard 同一撒点器——同源管线件）
            ShareCard.drawTasteMapDust(canvas, fieldLeft, fieldTop, fieldW, fieldH)
            // 星：远→近画家序（R242 空间口径——深度=星到相机距离）
            val cam = data.cam
            val sorted = data.stars.sortedByDescending {
                val dx = it.spX - cam.px
                val dy = it.spY - cam.py
                val dz = it.spZ - cam.pz
                dx * dx + dy * dy + dz * dz
            }
            val labelCands = ArrayList<ShareCard.TasteLabelCand>(8)
            for (s in sorted) {
                if (!TasteSpace.project(s.spX, s.spY, s.spZ, cam, focal, cX, cY, proj)) continue
                val sx = proj[0]
                val sy = proj[1]
                if (sx < fieldLeft - 40f || sx > fieldLeft + fieldW + 40f ||
                    sy < fieldTop - 40f || sy > fieldBottom + 40f
                ) continue
                // 半径=体量主轴半径×真透视（proj[3]=focal/深度）×构图换算比
                var r = (s.bodyRadius * proj[3] * ratio).coerceIn(1.0f, 64f)
                val alpha = s.alpha.coerceIn(0.06f, 1f)
                when (s.kind) {
                    CelestialKind.BLACK_HOLE -> {
                        // R322：黑洞海报静态签名（黑洞是频道晋升档——渲染成普通星=口径
                        // 背叛；视界 bump 与 App 同式 r*1.45+进食档 accretion01 同源）
                        r = maxOf(r * BLACK_HOLE_R_BUMP, 2.4f)
                        drawBlackHole(
                            canvas, sx, sy, r, alpha, TasteAstroPhysics.accretion01Of(s.recent7),
                            (s.chatId % 100_000L).toInt(),
                        )
                    }
                    CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR -> {
                        // R317b CompactStarFx 海报静态版（形状参数 TasteTerminalFx 单源）
                        drawCompactStar(canvas, s, sx, sy, r, alpha, quark = s.kind == CelestialKind.QUARK_STAR)
                    }
                    CelestialKind.WHITE_HOLE -> {
                        // R317b WhiteholeFx 海报静态版（白核+双向喷流——t=0 冻结）
                        drawWhitehole(canvas, s, sx, sy, r, alpha)
                    }
                    else -> drawStarBody(canvas, s, sx, sy, r, alpha)
                }
                // 名签候选收集（循环后统一避让放置——V1 两遍制同构）
                if (s.label != null && r >= LABEL_MIN_PX) {
                    labelCands.add(ShareCard.TasteLabelCand(sx, sy, r, s.label, (alpha * 255f).toInt()))
                }
            }
            // 名签避让（R206 共享件唯一实现——App 星图与海报共用同款）
            if (labelCands.isNotEmpty()) {
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xCCFFFFFF.toInt() // V1 名签同色（白 80%）
                }
                ShareCard.placeTasteLabels(canvas, labelCands, labelPaint, 30f)
            }
            // 渐晕（暗角收边——摄影剧照口径；星点之上垫暗角=镜头感）
            drawVignette(canvas, fieldLeft, fieldTop, fieldW, fieldH)
        }

        // ---- 方法注脚（诚实标注——V2 口径声明） ----
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A8494.toInt()
            textSize = 26f
        }
        canvas.drawText(data.methodNote, PAD.toFloat(), fieldBottom + 54f, notePaint)

        // ---- 底部品牌条（ShareCard 同款） ----
        val brandTop = (H - BRAND_H).toFloat()
        canvas.drawRect(0f, brandTop, W.toFloat(), H.toFloat(), Paint().apply { color = COLOR_BRAND_BG })
        val brandCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_BLUE
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "TGFeed · 把 Telegram 刷成抖音",
            W / 2f,
            brandTop + BRAND_H / 2f - (brandCenterPaint.descent() + brandCenterPaint.ascent()) / 2f,
            brandCenterPaint,
        )
        return bmp
    }

    // ==================== 绘制件（私有） ====================

    /**
     * 普通星体（主序+特殊 kind 身份色档）：
     * useK 门=[TasteFlightRenderer.drawGlowStar] 同式（tempK>0 且 kind==STAR——
     * 三段色只给主序星，特殊 kind 身份色不被色温轴污染）。三档 LOD=App 渲染器
     * 三档口径的海报静态版：近星球档（三段色径向渐变+外晕）/中间档（亮点盘+芯）/
     * 远档（纯亮点）。红巨星暖偏/超新星 tint=渲染器 warm/tint 参数静态镜像。
     */
    private fun drawStarBody(canvas: Canvas, s: V2Star, sx: Float, sy: Float, r: Float, alpha: Float) {
        val useK = s.tempK > 0f && s.kind == CelestialKind.STAR
        var color = s.bodyColor
        if (s.kind == CelestialKind.RED_GIANT) color = warmShift(color)
        else if (s.kind == CelestialKind.SUPERNOVA) color = TasteStarColor.blendRgb01(color, SUPERNOVA_TINT, 0.5f)
        if (r >= NEAR_STAR_R) {
            val coreC = if (useK) TasteStarColor.coreColorOfK(s.tempK) else color
            val edgeC = if (useK) TasteStarColor.edgeColorOfK(s.tempK) else color
            // 外晕（astrophoto bloom——极淡缘色光；芯亮外柔）
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            haloPaint.shader = RadialGradient(
                sx, sy, r * 2.2f,
                intArrayOf(withAlpha(edgeC, alpha * 0.30f), withAlpha(edgeC, 0f)),
                floatArrayOf(0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(sx, sy, r * 2.2f, haloPaint)
            // 三段色光球：芯（更热更蓝白）→星色→缘（临边更冷更暗）
            val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            ballPaint.shader = RadialGradient(
                sx, sy, r,
                intArrayOf(
                    withAlpha(coreC, alpha),
                    withAlpha(color, alpha * 0.96f),
                    withAlpha(edgeC, alpha * 0.88f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(sx, sy, r, ballPaint)
        } else if (r >= MID_STAR_R) {
            // 中间态：温度色亮点盘+微弱白热核心（App 中间态同构）
            val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            diskPaint.color = (alpha * 0.92f * 255f).toInt() shl 24 or (color and 0xFFFFFF)
            canvas.drawCircle(sx, sy, r, diskPaint)
            if (useK) {
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                corePaint.color = (alpha * 255f).toInt() shl 24 or
                    (TasteStarColor.coreColorOfK(s.tempK) and 0xFFFFFF)
                canvas.drawCircle(sx, sy, r * 0.45f, corePaint)
            }
        } else {
            // 远星纯亮点：实心温度色圆（真实宇宙里远处星星就是一个亮点）
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            dotPaint.color = (alpha * 255f).toInt() shl 24 or (color and 0xFFFFFF)
            canvas.drawCircle(sx, sy, maxOf(r, 0.8f), dotPaint)
        }
    }

    /**
     * R249 黑洞笔刷的海报静态签名（形状参数=TasteTerminalFx/渲染器同值镜像——
     * 改就一起改）：吸积微光晕（暖盘色渐变环，亮度×进食档 [TasteTerminalFx.accretionDiskK]）
     * →视界纯黑（吞光）→光子环（暖白细环——刚好逃逸的光；盛宴档
     * [TasteTerminalFx.isQuasarPhase01] 过门=增亮 [TasteTerminalFx.QUASAR_PHOTON_BOOST]
     * +双向喷流登场=类星体相）。动态件（旋转盘/引力波纹）不进静态海报——静态
     * 可辨即口径达成。
     */
    private fun drawBlackHole(canvas: Canvas, sx: Float, sy: Float, r: Float, alpha: Float, acc01: Float, seed: Int) {
        val quasar = TasteTerminalFx.isQuasarPhase01(acc01)
        val diskK = TasteTerminalFx.accretionDiskK(acc01)
        // 1) 吸积微光晕（外淡内亮暖盘色；视界内截止——进食档亮度同源）
        val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        diskPaint.shader = RadialGradient(
            sx, sy, r * 2.3f,
            intArrayOf(
                withAlpha(DISK_COOL, 0f),
                withAlpha(DISK_COOL, alpha * 0.36f * diskK),
                withAlpha(DISK_COOL, 0f),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(sx, sy, r * 2.3f, diskPaint)
        // 2) 视界（纯黑——吞光；alpha 拉满=黑洞的本分）
        val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        horizonPaint.color = HORIZON_COLOR
        canvas.drawCircle(sx, sy, r, horizonPaint)
        // 3) 光子环（暖白细环——渲染器同值：色/线宽同式/半径系数 PHOTON_RING；
        //    盛宴档增亮=吸积供能）
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val photonA = alpha * 0.85f * (if (quasar) 1f + TasteTerminalFx.QUASAR_PHOTON_BOOST else 1f)
        ringPaint.color = ((photonA.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (PHOTON_COLOR and 0xFFFFFF)
        ringPaint.strokeWidth = maxOf(0.9f, r * 0.055f)
        canvas.drawCircle(sx, sy, r * PHOTON_RING, ringPaint)
        // 4) 类星体相喷流（盛宴门过→双向外喷——与白洞共用「固定轴外喷」原语族；
        //    海报静态版=两道渐弱光锥线，t=0 冻结呼吸；轴向=chatId 哈希确定性）
        if (quasar && r >= 2f) {
            drawJets(canvas, sx, sy, r, alpha * 0.55f, TasteTerminalFx.whiteholeJetLen(r), PHOTON_COLOR, seed)
        }
    }

    /**
     * R317b CompactStarFx 海报静态版（中子星/夸克星——形状参数 TasteTerminalFx
     * 单源；渲染端 drawCompactStarR317b 同式，t=0 冻结缓漂）：蓝白极亮致密核
     * （[TasteTerminalFx.COMPACT_CORE_ALPHA_MIN] 死而不灭档；夸克
     * [TasteTerminalFx.QUARK_CORE_BOOST] 提亮）+遗迹环（R249 超新星丝缕弧族
     * 复用——相位冻结=死透的物质壳）；夸克星加内致密环（第二道）。
     */
    private fun drawCompactStar(canvas: Canvas, s: V2Star, sx: Float, sy: Float, r: Float, alpha: Float, quark: Boolean) {
        val coreA = if (quark) {
            (alpha + TasteTerminalFx.QUARK_CORE_BOOST).coerceAtMost(1f)
        } else {
            maxOf(alpha, TasteTerminalFx.COMPACT_CORE_ALPHA_MIN)
        }
        // 核：体量色×蓝白身份色混色（特殊 kind 身份色不被色温轴污染——渲染器同式）
        val color = TasteStarColor.blendRgb01(s.bodyColor, COMPACT_TINT, 0.5f)
        drawStarBody(canvas, s.copy(bodyColor = color), sx, sy, maxOf(r, 1.4f), coreA)
        if (r >= 2f) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val seed = (s.chatId % 100_000L).toInt()
            val ringR = r * TasteTerminalFx.REMNANT_RING_R
            val fil = TasteTerminalFx.remnantFilaments(seed)
            for (k in 0 until fil) {
                val rr = ringR * TasteTerminalFx.remnantArcRCoef(seed, k)
                val sweep = TasteTerminalFx.remnantArcSweep(seed, k)
                val base = TasteTerminalFx.remnantArcBaseDeg(seed, k, 0f)
                ringPaint.color = ((alpha * 0.34f * 255f).toInt() shl 24) or (COMPACT_TINT and 0xFFFFFF)
                ringPaint.strokeWidth = maxOf(0.8f, r * 0.075f)
                val rect = RectF(sx - rr, sy - rr * 0.82f, sx + rr, sy + rr * 0.82f)
                canvas.drawArc(rect, base, sweep, false, ringPaint)
            }
            if (quark) {
                // 夸克星内致密环（第二道——中子星无此环）
                ringPaint.color = ((alpha * 0.30f * 255f).toInt() shl 24) or (COMPACT_TINT and 0xFFFFFF)
                ringPaint.strokeWidth = maxOf(0.7f, r * 0.06f)
                canvas.drawCircle(sx, sy, r * TasteTerminalFx.QUARK_INNER_RING_R, ringPaint)
            }
        }
    }

    /**
     * R317b WhiteholeFx 海报静态版（白洞——形状参数 TasteTerminalFx 单源；渲染端
     * drawWhiteholeR317b 同式，t=0 冻结呼吸）：白核（[TasteTerminalFx.WHITEHOLE_CORE_ALPHA_MIN]
     * 恒在供血档）+双向喷流（固定哈希轴 [TasteTerminalFx.whiteholeJetAngleDeg]——
     * 只出不进的稳态外喷；静态版=两道渐弱光锥线）。
     */
    private fun drawWhitehole(canvas: Canvas, s: V2Star, sx: Float, sy: Float, r: Float, alpha: Float) {
        val coreA = maxOf(alpha, TasteTerminalFx.WHITEHOLE_CORE_ALPHA_MIN)
        // 核：体量色×暖白身份色混色（渲染器 tint 同式）
        val color = TasteStarColor.blendRgb01(s.bodyColor, WHITEHOLE_TINT, 0.5f)
        drawStarBody(canvas, s.copy(bodyColor = color), sx, sy, maxOf(r, 1.3f), coreA)
        if (r >= 1.6f) {
            val seed = (s.chatId % 100_000L).toInt()
            val breath = TasteTerminalFx.whiteholeJetBreath(0f, seed)
            val len = TasteTerminalFx.whiteholeJetLen(r).coerceIn(30f, 140f)
            drawJets(canvas, sx, sy, r, alpha * breath * 0.43f, len, WHITEHOLE_TINT, seed)
        }
    }

    /**
     * 双向喷流（海报静态版——白洞/类星体相共用原语族：两道渐弱光锥线；
     * 轴向=[TasteTerminalFx.whiteholeJetAngleDeg]（chatId 哈希种子——确定性，
     * 与渲染端同式；屏位不进种子=同星同形铁律））。
     */
    private fun drawJets(canvas: Canvas, sx: Float, sy: Float, r: Float, alpha: Float, len: Float, tint: Int, seed: Int) {
        if (len <= r) return
        val angRad = Math.toRadians(TasteTerminalFx.whiteholeJetAngleDeg(seed).toDouble())
        val ca = cos(angRad).toFloat()
        val sa = sin(angRad).toFloat()
        val jetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = maxOf(2.4f, r * 0.20f)
            strokeCap = Paint.Cap.ROUND
        }
        jetPaint.color = ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (tint and 0xFFFFFF)
        val rEdge = r * 0.98f
        canvas.drawLine(sx + ca * rEdge, sy + sa * rEdge, sx + ca * len, sy + sa * len, jetPaint)
        canvas.drawLine(sx - ca * rEdge, sy - sa * rEdge, sx - ca * len, sy - sa * len, jetPaint)
    }

    /** 渐晕（暗角收边——中心透明到四角暗蓝黑；对角半径=四角最暗的摄影口径） */
    private fun drawVignette(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w / 2f
        val cy = top + h / 2f
        val rad = sqrt(w * w + h * h) / 2f
        val vPaint = Paint()
        vPaint.shader = RadialGradient(
            cx, cy, rad,
            intArrayOf(0x00000000, 0x00000000, 0x73070B12.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, left + w, top + h, vPaint)
    }

    /**
     * 天体谱徽章行（chips 两行自适应）：pill 暗底+发丝描边+「label 灰 · count 白」
     * 双色文案（数据角标=Wrapped 报告风）。单 chip 超宽=放弃该枚（诚实截断）。
     * 返回行底部 y（供分割线接续）。
     */
    private fun drawBadgeRow(canvas: Canvas, badges: List<PosterBadge>, startY: Float): Float {
        if (badges.isEmpty()) return startY
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 28f
        }
        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x16222C38.toInt()
        }
        val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = 0x1FFFFFFF
        }
        val chipH = 52f
        val maxRight = (W - PAD).toFloat()
        var x = PAD.toFloat()
        var y = startY
        for (b in badges) {
            val labelW = labelPaint.measureText(b.label)
            val countW = countPaint.measureText(b.count.toString())
            val chipW = 20f + labelW + 10f + countW + 20f
            if (x + chipW > maxRight && x > PAD) {
                // 换行（两行内通常足够：≤11 枚非 STAR kind）
                x = PAD.toFloat()
                y += chipH + 12f
            }
            if (x + chipW > maxRight) break // 单 chip 超宽=放弃（诚实截断）
            canvas.drawRoundRect(x, y, x + chipW, y + chipH, chipH / 2f, chipH / 2f, chipPaint)
            canvas.drawRoundRect(x, y, x + chipW, y + chipH, chipH / 2f, chipH / 2f, chipStroke)
            val textY = y + chipH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(b.label, x + 20f, textY, labelPaint)
            canvas.drawText(b.count.toString(), x + 20f + labelW + 10f, textY, countPaint)
            x += chipW + 14f
        }
        return y + chipH + 8f
    }

    /** 红巨星暖偏（渲染器 warmShift 静态镜像：色相 +28 度） */
    private fun warmShift(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = (hsv[0] + WARM_SHIFT_DEG) % 360f
        return Color.HSVToColor(hsv)
    }

    /** ARGB 透明度替换（渐变色组组装用） */
    private fun withAlpha(argb: Int, a: Float): Int =
        ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (argb and 0xFFFFFF)
}
