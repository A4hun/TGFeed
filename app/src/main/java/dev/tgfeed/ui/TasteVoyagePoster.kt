package dev.tgfeed.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import dev.tgfeed.core.playback.ShareCard
import dev.tgfeed.core.tastemap.TasteAchievements
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteSpace.SpaceCam
import dev.tgfeed.core.tastemap.TasteVoyageLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * R235④ 航行日志海报（口味星图四期·RPG 层）——**ShareCard 同源管线零改 ShareCard**：
 * 保存链复用 [ShareCard.saveToGallery]（MediaStore 公共相册）、底幕复用
 * [ShareCard.drawTasteMapDust]、品牌条样式同款镜像；绘制本体独立成文件
 * （generateTasteMap 一行不碰=经典海报零回归红线的字面落实）。
 *
 * 构图（1080×1440 竖版，自上而下）：
 *  - 品牌行（电报蓝小字）→ 大标题「我的航行日志」→ 等阶行（徽章色点+等阶名+飞船描述）
 *    → 五阶徽章点（达成实心/未达空心）；
 *  - **星图快照（当前视角）**：R242 [TasteSpace.project] + 用户当前 SpaceCam 重投影
 *    （「快照=当前视角」——焦距按海报视场宽/设备画布宽等比换算，构图与屏上所见
 *    一致）+ 深空星点底幕（ShareCard 同一撒点器）；
 *  - **星轨路径**：今日（亮渐变折线+站点点）+本周（暗折线）——「今天/本周走过的路」；
 *  - 统计行（探索恒星/话题 tag/星域星云/星轨站数）+ 方法注脚 + 底部品牌条。
 */
object TasteVoyagePoster {

    /** 海报尺寸（与经典口味星图海报同幅——分享物料族一致性） */
    private const val W = 1080
    private const val H = 1440

    /** 左右留边（同经典海报） */
    private const val PAD = 64

    /** 底部品牌条高（ShareCard.BRAND_H 同值镜像——改就一起改） */
    private const val BRAND_H = 108

    // ---- 色板（ShareCard 同族值镜像——改就一起改；core 常量不可见故本地锚定） ----
    private const val COLOR_BG = 0xFF0D1117.toInt()
    private const val COLOR_TITLE = 0xFFF2F5FA.toInt()
    private const val COLOR_CAPTION = 0xFF9AA6B5.toInt()
    private const val COLOR_BRAND_BLUE = 0xFF54A9EB.toInt()
    private const val COLOR_BRAND_BG = 0xFF101820.toInt()

    /** 今日星轨主色（暖白——「今晚的你走过这片星域」） */
    private const val TRAIL_TODAY = 0xFFFFE9C4.toInt()

    /** 本周星轨次色（暗青白——今日的前情） */
    private const val TRAIL_WEEK = 0xFF8FB6D9.toInt()

    /** 航行日志单星（空间坐标 + App 口径展示三件——快照重投影的原料） */
    class VoyageStar(
        val x: Float,
        val y: Float,
        val z: Float,
        val radiusWorld: Float,
        val colorArgb: Int,
        val alpha: Float,
        val chatId: Long,
    )

    /** 星轨折线（空间坐标 + 每站时间戳——时间序渐变的原料） */
    class VoyageTrail(
        val n: Int,
        val xs: FloatArray,
        val ys: FloatArray,
        val zs: FloatArray,
        val tsMs: LongArray,
    )

    /** 海报数据（屏侧一次性组装——cam/lens/focalPxDevice=「当前视角」快照原料） */
    class VoyageCardData(
        val stars: List<VoyageStar>,
        val cam: SpaceCam,
        val lens: Float,
        /** 设备端有效焦距（px）——海报按 fieldW/deviceCanvasW 等比换算保构图 */
        val focalPxDevice: Float,
        val deviceCanvasW: Int,
        val weekTrail: VoyageTrail?,
        val todayTrail: VoyageTrail?,
        /** 成就评定（等阶/勋章徽章单一数据源；null=未评定=诚实占位） */
        val report: TasteAchievements.AchievementReport?,
        /** 在图频道恒星数（统计行） */
        val starCount: Int,
        /** 未探索星云数（统计行「星域」） */
        val nebulaCount: Int,
    )

    /** 生成航行日志海报位图（IO 线程调用——600 星一次绘制毫秒级） */
    fun generate(data: VoyageCardData): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(COLOR_BG)
        var y = 108f

        // ---- 品牌行（电报蓝小字） ----
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_BLUE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        val dateLine = SimpleDateFormat("M月d日").format(Date())
        canvas.drawText("TGFeed · 口味星图 · 航行日志 · $dateLine", PAD.toFloat(), y, brandPaint)
        y += 72f

        // ---- 大标题 ----
        val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 72f
        canvas.drawText("我的航行日志", PAD.toFloat(), y, headlinePaint)
        y += 28f

        // ---- 等阶行（徽章色点 + 等阶名 + 飞船描述） ----
        val rep = data.report
        val rank = rep?.rank ?: TasteAchievements.Rank.BRONZE
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TasteAchievements.colorOf(rank) }
        canvas.drawCircle(PAD + 12f, y - 12f, 12f, dotPaint)
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 38f
        }
        canvas.drawText(
            "${TasteAchievements.labelOf(rank)} · ${TasteAchievements.shipDescOf(TasteAchievements.shipStyleOf(rank))}",
            PAD + 40f, y, subPaint,
        )
        y += 46f

        // ---- 五阶徽章点（达成实心/未达空心——勋章徽章行） ----
        val metRanks = HashSet<TasteAchievements.Rank>()
        metRanks += TasteAchievements.Rank.BRONZE // 默认持有
        if (rep != null) {
            for (r in TasteAchievements.Rank.values()) {
                if (r == TasteAchievements.Rank.BRONZE) continue
                // 该阶全部勋章达成=拥有该阶徽章（evaluate 的晋升判据同口径）
                val keys = when (r) {
                    TasteAchievements.Rank.SILVER -> listOf("silver_channels", "silver_watch")
                    TasteAchievements.Rank.GOLD -> listOf("gold_channels", "gold_watch", "gold_favs")
                    TasteAchievements.Rank.DIAMOND -> listOf("diamond_channels", "diamond_tags", "diamond_shares")
                    TasteAchievements.Rank.LEGEND -> listOf("legend_channels", "legend_watch", "legend_kinds")
                    else -> emptyList()
                }
                if (keys.isNotEmpty() && rep.medals.filter { it.key in keys }.all { it.met }) metRanks += r
            }
        }
        val badgeDot = Paint(Paint.ANTI_ALIAS_FLAG)
        val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        var bx = PAD + 12f
        for (r in TasteAchievements.Rank.values()) {
            val c = TasteAchievements.colorOf(r)
            if (r in metRanks) {
                badgeDot.color = c
                canvas.drawCircle(bx, y, 11f, badgeDot)
            } else {
                badgeRing.color = (0x66 shl 24) or (c and 0xFFFFFF)
                canvas.drawCircle(bx, y, 10f, badgeRing)
            }
            bx += 46f
        }
        y += 30f

        // ---- 发丝分割线 ----
        canvas.drawRect(PAD.toFloat(), y, (W - PAD).toFloat(), y + 2f, Paint().apply { color = 0x14FFFFFF })
        y += 24f

        // ---- 星图快照（当前视角重投影） ----
        val fieldLeft = PAD.toFloat()
        val fieldTop = y
        val fieldW = (W - PAD * 2).toFloat()
        val statsH = 150f
        val fieldBottom = (H - BRAND_H - statsH - 40).toFloat()
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
            // 星：远→近画家序（R242 空间口径——深度=|星−相机|，排序按到相机距离）
            val cam = data.cam
            val sorted = data.stars.sortedByDescending {
                val dx = it.x - cam.px
                val dy = it.y - cam.py
                val dz = it.z - cam.pz
                dx * dx + dy * dy + dz * dz
            }
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (s in sorted) {
                if (!TasteSpace.project(s.x, s.y, s.z, cam, focal, cX, cY, proj)) continue
                val sx = proj[0]
                val sy = proj[1]
                if (sx < -40f || sx > fieldLeft + fieldW + 40f || sy < fieldTop - 40f || sy > fieldBottom + 40f) continue
                // 半径=世界半径×真透视（proj[3]=focal/深度）×构图换算比
                val r = (s.radiusWorld * proj[3] * ratio).coerceIn(1.2f, 64f)
                starPaint.color = ((s.alpha.coerceIn(0.06f, 1f) * 255f).toInt() shl 24) or (s.colorArgb and 0xFFFFFF)
                canvas.drawCircle(sx, sy, r, starPaint)
            }
            // ---- 星轨路径：本周（暗）→ 今日（亮渐变）----
            val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TRAIL_WEEK
                strokeWidth = 3f
                alpha = 70
            }
            drawTrail(canvas, data.weekTrail, data.cam, focal, cX, cY, weekPaint, null, fieldLeft, fieldTop, fieldW, fieldBottom)
            val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRAIL_TODAY }
            drawTrail(canvas, data.todayTrail, data.cam, focal, cX, cY, todayPaint, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRAIL_TODAY }, fieldLeft, fieldTop, fieldW, fieldBottom)
        }

        // ---- 统计行 + 方法注脚 ----
        val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 32f
        }
        val tagCount = rep?.medals?.firstOrNull { it.key == "diamond_tags" }?.current?.toInt() ?: 0
        val stops = data.todayTrail?.n ?: 0
        var sy = fieldBottom + 52f
        canvas.drawText(
            "探索恒星 ${data.starCount} · 话题 $tagCount · 星域 ${data.nebulaCount} · 今日航程 ${stops} 站",
            PAD.toFloat(), sy, statsPaint,
        )
        sy += 40f
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A8494.toInt()
            textSize = 26f
        }
        canvas.drawText("口味星图按本地深索引投影 · 轨迹=观看历史 · 勋章=本地统计", PAD.toFloat(), sy, notePaint)

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

    /** 星轨折线绘制（站点投影→折线；[headDotPaint]!=null 时最新站画脉冲头光圆点） */
    private fun drawTrail(
        canvas: Canvas,
        trail: VoyageTrail?,
        cam: SpaceCam,
        focal: Float,
        cX: Float,
        cY: Float,
        linePaint: Paint,
        headDotPaint: Paint?,
        fieldLeft: Float,
        fieldTop: Float,
        fieldW: Float,
        fieldBottom: Float,
    ) {
        val trail = trail ?: return
        if (trail.n < 2) return
        val proj = FloatArray(4)
        var px = 0f
        var py = 0f
        var has = false
        val lastInv = 1f / (trail.n - 1).coerceAtLeast(1)
        for (i in 0 until trail.n) {
            if (!TasteSpace.project(trail.xs[i], trail.ys[i], trail.zs[i], cam, focal, cX, cY, proj)) {
                has = false
                continue
            }
            val sx = proj[0]
            val sy = proj[1]
            val onField = sx > fieldLeft - 60f && sx < fieldLeft + fieldW + 60f && sy > fieldTop - 60f && sy < fieldBottom + 60f
            if (has && onField) {
                if (headDotPaint != null) {
                    // 今日轨迹：时间序渐变（越新越亮 0.24→0.86）
                    val grad = i * lastInv
                    linePaint.alpha = ((0.24f + 0.62f * grad) * 255f).toInt()
                    linePaint.strokeWidth = 3.4f + 2.6f * grad
                }
                canvas.drawLine(px, py, sx, sy, linePaint)
            }
            if (onField) {
                linePaint.alpha = if (headDotPaint != null) 200 else 90
                if (headDotPaint != null && i == trail.n - 1) {
                    // 最新站：头光（「此刻你在这里」）
                    headDotPaint.alpha = 90
                    canvas.drawCircle(sx, sy, 14f, headDotPaint)
                    headDotPaint.alpha = 240
                    canvas.drawCircle(sx, sy, 5.5f, headDotPaint)
                } else {
                    canvas.drawCircle(sx, sy, if (headDotPaint != null) 4.2f else 3.0f, linePaint)
                }
            }
            px = sx
            py = sy
            has = true
        }
    }
}

/**
 * R235 航行账本的 ui 侧文件解析（core 零 Android 依赖——Context → File 的薄适配）。
 * FeedScreen 分享枢纽两动作与星图双海报导出共用本口（成就「分享 N 次」funnel）。
 */
object TasteVoyageStore {
    fun voyageFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "galaxy"), TasteVoyageLog.FILE_NAME)

    /** 分享计数 +1（返回新计数；IO 极轻——调用方可在主线程直接用或自选调度） */
    fun bumpShare(context: Context): Int =
        runCatching { TasteVoyageLog.bumpShare(voyageFile(context)).shareCount }.getOrDefault(0)
}
