package dev.tgfeed.core.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import dev.tgfeed.core.td.VideoItem
import kotlin.math.cos
import kotlin.math.sin

/**
 * 分享卡片：把当前视频渲染成一张 1080×1350 竖版图片（封面 + 标题 + 链接 + 品牌条），
 * 保存到公共相册 `Pictures/TGFeed/`，之后由用户走系统相册自带分享。
 *
 * 布局（自上而下，单位 px）：
 *  - 封面区 1080×760：centerCrop 铺满；无封面时深灰底 + 居中 "TGFeed" 水印字
 *  - 内容区（黑底，左右留边 56）：
 *      频道名：白色加粗 56px，单行截断
 *      caption：灰 #B0B0B0，40px，最多两行，超出补 "…"
 *      链接：TG 蓝 #2AABEE，36px，单行截断
 *  - 品牌条：底部 100px 高、#111 底，居中白字 "TGFeed · 把 Telegram 刷成抖音"
 *
 * 全部用 Paint + Canvas.drawText 手绘，系统字体自带中文字形，无需内置字体文件。
 *
 * 另含 Wrapped 风观看报告分享卡（[generateReport]，1080×1440：人格 + 总时长 + TOP3 频道
 * + 热力图缩略），与 [shareImage]（存相册 → 系统分享面板）配套，供观看报告页使用。
 *
 * R53 新增称号勋章版分享卡（[generateBadgeShare]，1080×1920：当前称号 + 观看数据摘要
 * + 各类目最高级已达成勋章精选 + 品牌落款）。隐私红线：全卡只有数字与称号/勋章，
 * 绝不出现具体频道名、内容标题、搜索词。
 */
object ShareCard {

    // ---- 画布尺寸 ----
    private const val CARD_W = 1080
    private const val CARD_H = 1350

    // ---- 布局参数（px）----
    private const val COVER_H = 760          // 封面区高度
    private const val CONTENT_PAD_X = 56     // 内容区左右留边
    private const val BRAND_H = 100          // 底部品牌条高度
    private const val TITLE_SIZE = 56f       // 频道名字号
    private const val CAPTION_SIZE = 40f     // caption 字号
    private const val CAPTION_LINE_GAP = 16f // caption 行距
    private const val LINK_SIZE = 36f        // 链接字号

    // ---- 颜色 ----
    private const val COLOR_BG = Color.BLACK
    private const val COLOR_COVER_FALLBACK = 0xFF1C1C1E.toInt() // 无封面时的深灰底
    private const val COLOR_TITLE = Color.WHITE
    private const val COLOR_CAPTION = 0xFFB0B0B0.toInt()
    private const val COLOR_LINK = 0xFF2AABEE.toInt()           // Telegram 蓝
    private const val COLOR_BRAND_BG = 0xFF111111.toInt()
    private const val COLOR_BRAND_TEXT = 0xFFE8E8E8.toInt()

    /** 相册子目录：Pictures/TGFeed（与 FrameCapture 一致） */
    private const val ALBUM_NAME = "TGFeed"

    /**
     * 生成分享卡片位图。
     * @param cover 视频封面；null 时用深灰底 + 水印字占位
     * @param link  视频 t.me 链接；null/空串则跳过链接行
     * （context 目前手绘卡片用不到，保留在签名里便于后续按 dp/主题适配）
     */
    fun generate(@Suppress("UNUSED_PARAMETER") context: Context, item: VideoItem, cover: Bitmap?, link: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 整卡黑底
        canvas.drawColor(COLOR_BG)

        // ---- 封面区：centerCrop 铺满 1080×COVER_H ----
        if (cover != null && !cover.isRecycled) {
            // 目标宽高中较大缩放比 = centerCrop（铺满后裁掉溢出部分）
            val scale = maxOf(
                CARD_W.toFloat() / cover.width,
                COVER_H.toFloat() / cover.height,
            )
            val drawW = cover.width * scale
            val drawH = cover.height * scale
            val dst = RectF(
                (CARD_W - drawW) / 2f,
                (COVER_H - drawH) / 2f,
                (CARD_W + drawW) / 2f,
                (COVER_H + drawH) / 2f,
            )
            canvas.save()
            canvas.clipRect(0f, 0f, CARD_W.toFloat(), COVER_H.toFloat())
            canvas.drawBitmap(cover, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            // 无封面占位：深灰底 + 居中水印字
            canvas.drawRect(0f, 0f, CARD_W.toFloat(), COVER_H.toFloat(), Paint().apply {
                color = COLOR_COVER_FALLBACK
            })
            val wm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF3A3A3C.toInt()
                textSize = 96f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("TGFeed", CARD_W / 2f, COVER_H / 2f - (wm.descent() + wm.ascent()) / 2f, wm)
        }

        val textWidth = CARD_W - CONTENT_PAD_X * 2
        var cursorY = COVER_H.toFloat()

        // ---- 频道名：白色加粗大号，单行 ----
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = TITLE_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        cursorY += 72f + TITLE_SIZE // 顶部留白 72 + 一行字高
        canvas.drawText(
            ellipsize(item.channelTitle, titlePaint, textWidth.toFloat()),
            CONTENT_PAD_X.toFloat(), cursorY, titlePaint,
        )

        // ---- caption：灰色，最多两行，手动断行（breakText 按像素宽切，中英文都安全）----
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = CAPTION_SIZE
        }
        if (item.caption.isNotBlank()) {
            val lines = wrapText(item.caption.replace('\n', ' '), captionPaint, textWidth.toFloat(), maxLines = 2)
            cursorY += 40f // 与频道名的间距
            lines.forEach { line ->
                cursorY += CAPTION_SIZE
                canvas.drawText(line, CONTENT_PAD_X.toFloat(), cursorY, captionPaint)
                cursorY += CAPTION_LINE_GAP
            }
        }

        // ---- 链接：TG 蓝小字，单行 ----
        if (!link.isNullOrBlank()) {
            val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_LINK
                textSize = LINK_SIZE
            }
            cursorY += 32f + LINK_SIZE
            canvas.drawText(
                ellipsize(link, linkPaint, textWidth.toFloat()),
                CONTENT_PAD_X.toFloat(), cursorY, linkPaint,
            )
        }

        // ---- 底部品牌条 ----
        val brandTop = (CARD_H - BRAND_H).toFloat()
        canvas.drawRect(0f, brandTop, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply {
            color = COLOR_BRAND_BG
        })
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_TEXT
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "TGFeed · 把 Telegram 刷成抖音",
            CARD_W / 2f,
            brandTop + BRAND_H / 2f - (brandPaint.descent() + brandPaint.ascent()) / 2f,
            brandPaint,
        )

        return bitmap
    }

    /**
     * 把卡片位图写入公共相册 `Pictures/TGFeed/`（照 FrameCapture 的 MediaStore 两段式）。
     * API 29+ 无需权限；更低版本/任何失败返回 false（由调用方 Toast 兜底），不抛异常。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean =
        insertToGallery(context, bitmap) != null

    /**
     * 把卡片位图写入公共相册并返回 content Uri（[saveToGallery] 的 Uri 版）：
     * 系统分享面板可直接引用该 Uri（ACTION_SEND + EXTRA_STREAM + 读授权），无需 FileProvider。
     * 失败/API 29 以下返回 null，不抛异常。
     */
    private fun insertToGallery(context: Context, bitmap: Bitmap): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val displayName = "tgfeed_card_${System.currentTimeMillis()}.jpg"
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1) // 挂起态：写入期间其他 App 不可见
            }
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    // 卡片无透明通道，JPEG 92 与 FrameCapture 一致
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) error("卡片压缩失败")
                } ?: error("无法打开相册输出流")
                // 写完解除挂起，相册立即可见
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) } // 失败时清掉半成品记录
                return null
            }
            uri
        } catch (e: Exception) {
            null
        }
    }

    /**
     * "分享"入口：FileProvider 需 manifest 声明（不在本模块边界内），
     * 故简化为——生成卡片存相册 + Toast 提示，用户再从系统相册分享。
     * 会切到 IO 之外也可调用：生成与保存都比较重，建议调用方放后台线程。
     */
    fun share(context: Context, bitmap: Bitmap) {
        val ok = saveToGallery(context, bitmap)
        Toast.makeText(
            context,
            if (ok) "卡片已存到相册 Pictures/$ALBUM_NAME，可到相册分享" else "卡片保存失败",
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * 系统分享任意卡片位图（观看报告卡等）：先存公共相册拿 content Uri，再直接拉系统分享面板
     * （ACTION_SEND + EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION）。
     * FileProvider 需 manifest 声明（不在本文件边界内），故借道相册 Uri；相册写入失败则只 Toast。
     * 须在后台线程调用（内部有磁盘 IO）；Toast 与起 Activity 自动切回主线程。
     */
    fun shareImage(context: Context, bitmap: Bitmap) {
        val uri = insertToGallery(context, bitmap)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (uri == null) {
                Toast.makeText(context, "卡片保存失败", Toast.LENGTH_LONG).show()
                return@post
            }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(android.content.Intent.createChooser(send, "分享观看报告"))
            }.onFailure {
                Toast.makeText(context, "没找到可分享的应用，卡片已存到相册 Pictures/$ALBUM_NAME", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 观看报告分享卡（Wrapped 风，1080×1440） ====================

    /** 报告卡画布尺寸（3:4 竖版，比视频卡多出的高度给热力图与 TOP 频道） */
    private const val REPORT_W = 1080
    private const val REPORT_H = 1440
    private const val REPORT_PAD_X = 64

    /** 强调红（与 ui/theme TgFeedTokens.AccentRed 同值；core 层不引用 compose token，靠注释保持同步） */
    private val COLOR_ACCENT_RED = 0xFFFE2C55.toInt()

    /** 电报蓝（同 TgFeedTokens.TgBlue；报告卡仅用于顶部品牌行一处） */
    private val COLOR_REPORT_BLUE = 0xFF54A9EB.toInt()

    /**
     * 观看报告分享卡数据（UI 层把 WatchReport 投影成纯字符串/数字，core.playback 不反向依赖 core.feed）：
     * 时长一律预先格式化（"X 小时 X 分钟"），heatmapSec 恒 84 格（近 12 周，-1 = 未来日占位）。
     */
    data class ReportCardData(
        val personaTitle: String,                    // 主人格（emoji + 称号，由产出端按 WatchPersona.emoji/title 拼好，如 "🦉 夜猫子"）
        val personaTagline: String,                  // 一句调侃文案
        val totalText: String,                       // 已格式化的累计观看时长
        val streakDays: Int,                         // 连续观看天数
        val activeDays: Int,                         // 累计观看天数
        val topChannels: List<Pair<String, String>>, // TOP3 频道：名称 to 已格式化时长
        val heatmapSec: List<Long>,                  // 近 12 周逐日观看秒数（84 格；-1 = 未来日）
        val brandLine: String,                       // 顶部品牌行（"TGFeed · 观看报告 · M月d日"）
    )

    /**
     * 生成观看报告分享卡（Wrapped 风，1080×1440 竖版）。
     * 配色纪律与 App 内一致：黑底 + 白阶文字，全卡只有 AccentRed（人格标签/排名/热力）与
     * TgBlue（顶部品牌行）两个强调色。
     *
     * 布局（自上而下，单位 px，左右留边 64）：
     *  品牌行（蓝 34 Bold）→ "我的观看人格"（红 36 Bold）→ 人格大字（白 100 Bold，含 emoji）
     *  → 调侃文案（灰 40，单行截断）→ 发丝分割线 → "累计观看"（灰 34）→ 总时长（白 84 Bold）
     *  → "连续 X 天 · 活跃 Y 天"（灰 34）→ "最常看的频道"（灰 34）→ TOP3 行（红排名 + 白名 + 右对齐灰时长）
     *  → "近 12 周观看热力"（灰 34）→ 12×7 圆角格子（44px，白阶→红 4 档）→ 底部品牌条（同视频卡）。
     */
    fun generateReport(data: ReportCardData): Bitmap {
        val bitmap = Bitmap.createBitmap(REPORT_W, REPORT_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)
        val textWidth = (REPORT_W - REPORT_PAD_X * 2).toFloat()
        // 文本游标：每段先 y += 字号（drawText 的 y 是基线），再加段间距
        var y = 64f

        // ---- 顶部品牌行（电报蓝小字） ----
        val brandTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_REPORT_BLUE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 34f
        canvas.drawText(ellipsize(data.brandLine, brandTopPaint, textWidth), REPORT_PAD_X.toFloat(), y, brandTopPaint)
        y += 44f

        // ---- "我的观看人格" 红色小标签 ----
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT_RED
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 36f
        canvas.drawText("我的观看人格", REPORT_PAD_X.toFloat(), y, labelPaint)
        y += 24f

        // ---- 人格大字（emoji + 名称） ----
        val personaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 100f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 100f
        canvas.drawText(ellipsize(data.personaTitle, personaPaint, textWidth), REPORT_PAD_X.toFloat(), y, personaPaint)
        y += 28f

        // ---- 调侃文案（单行，超长截断） ----
        val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 40f
        }
        y += 40f
        canvas.drawText(ellipsize(data.personaTagline, taglinePaint, textWidth), REPORT_PAD_X.toFloat(), y, taglinePaint)
        y += 28f

        // ---- 发丝分割线（白 8%） ----
        canvas.drawRect(
            REPORT_PAD_X.toFloat(), y, (REPORT_W - REPORT_PAD_X).toFloat(), y + 2f,
            Paint().apply { color = 0x14FFFFFF },
        )
        y += 36f

        // ---- 累计观看：小标签 + 大数字 + 天数副行 ----
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 34f
        }
        y += 34f
        canvas.drawText("累计观看", REPORT_PAD_X.toFloat(), y, subPaint)
        y += 12f
        val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 84f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 84f
        canvas.drawText(ellipsize(data.totalText, totalPaint, textWidth), REPORT_PAD_X.toFloat(), y, totalPaint)
        y += 14f
        y += 34f
        canvas.drawText("连续 ${data.streakDays} 天 · 活跃 ${data.activeDays} 天", REPORT_PAD_X.toFloat(), y, subPaint)
        y += 32f

        // ---- TOP3 频道：红排名 + 白名称 + 右对齐灰时长 ----
        y += 34f
        canvas.drawText("最常看的频道", REPORT_PAD_X.toFloat(), y, subPaint)
        val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT_RED
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 44f
        }
        val durPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 40f
        }
        data.topChannels.take(3).forEachIndexed { i, (name, dur) ->
            y += 24f + 44f
            canvas.drawText("${i + 1}", REPORT_PAD_X.toFloat(), y, rankPaint)
            // 名称可用宽 = 总宽 − 排名列 60 − 时长预留 260
            canvas.drawText(ellipsize(name, namePaint, textWidth - 60f - 260f), REPORT_PAD_X + 60f, y, namePaint)
            val durW = durPaint.measureText(dur)
            canvas.drawText(dur, REPORT_W - REPORT_PAD_X - durW, y, durPaint)
        }
        y += 32f

        // ---- 近 12 周热力图：12 列（周）× 7 行（周一在上），圆角格子 44px，整格区居中 ----
        // 纵向预算核算：到这里 y≈970，格子区高 7×44+6×8=356 → 底 1326 < 品牌条顶 1340 ✓
        y += 34f
        canvas.drawText("近 12 周观看热力", REPORT_PAD_X.toFloat(), y, subPaint)
        y += 20f
        val cell = 44f
        val gap = 8f
        val gridW = 12 * cell + 11 * gap
        val gridX = REPORT_PAD_X + (textWidth - gridW) / 2f
        val maxHeat = data.heatmapSec.filter { it > 0 }.maxOrNull() ?: 0L
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        data.heatmapSec.take(84).forEachIndexed { idx, sec ->
            val col = idx / 7
            val row = idx % 7
            val left = gridX + col * (cell + gap)
            val top = y + row * (cell + gap)
            cellPaint.color = reportHeatColor(sec, maxHeat)
            canvas.drawRoundRect(RectF(left, top, left + cell, top + cell), 8f, 8f, cellPaint)
        }

        // ---- 底部品牌条（与视频卡同款） ----
        val brandBarTop = (REPORT_H - BRAND_H).toFloat()
        canvas.drawRect(0f, brandBarTop, REPORT_W.toFloat(), REPORT_H.toFloat(), Paint().apply {
            color = COLOR_BRAND_BG
        })
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_TEXT
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "TGFeed · 把 Telegram 刷成抖音",
            REPORT_W / 2f,
            brandBarTop + BRAND_H / 2f - (brandPaint.descent() + brandPaint.ascent()) / 2f,
            brandPaint,
        )
        return bitmap
    }

    /** 报告卡热力格配色：无观看/未来 = 白阶底，其余按当日相对峰值分 4 档加深到 AccentRed（与报告页热力图同口径） */
    private fun reportHeatColor(sec: Long, maxSec: Long): Int {
        if (sec < 0) return 0x0DFFFFFF                 // 未来日占位（白 5%）
        if (sec <= 0 || maxSec <= 0) return 0x14FFFFFF // 无观看（白 8%）
        val frac = sec.toFloat() / maxSec.toFloat()
        val alpha = when {
            frac < 0.25f -> 0x4D
            frac < 0.50f -> 0x80
            frac < 0.75f -> 0xB3
            else -> 0xFF
        }
        return (alpha shl 24) or 0xFE2C55
    }

    // ==================== 称号勋章分享卡（R53，1080×1920） ====================

    /** 称号勋章分享卡画布尺寸（9:16 竖版，适配系统分享预览与相册全屏查看） */
    private const val BADGE_CARD_W = 1080
    private const val BADGE_CARD_H = 1920
    private const val BADGE_CARD_PAD = 64

    /** 统计小块高度与圆角（观看次数/连续观看/累计活跃 三枚一排） */
    private const val BADGE_STAT_H = 180f
    private const val BADGE_STAT_RADIUS = 24f

    /** 精选勋章网格：3 列，圆形 emoji 托直径与名签字号 */
    private const val BADGE_GRID_COLS = 3
    private const val BADGE_CIRCLE = 150f
    private const val BADGE_NAME_SIZE = 34f

    /**
     * 称号勋章分享卡数据（UI 层把 WatchReport 投影成纯字符串/数字，core.playback 不反向依赖 core.feed）。
     * 隐私红线：本结构只承载称号、勋章、统计数字与品牌文案——调用方严禁塞入频道名/内容标题/搜索词。
     */
    data class BadgeShareData(
        val personaTitle: String,                  // 当前称号（emoji + 名称，由产出端拼好，如 "🦉 夜猫子"）
        val personaTagline: String,                // 称号调侃文案（单行截断）
        val totalText: String,                     // 已格式化的累计观看时长
        val eventCount: Int,                       // 累计观看次数
        val streakDays: Int,                       // 连续观看天数
        val activeDays: Int,                       // 累计观看天数
        val badgeAchieved: Int,                    // 已达成勋章数
        val badgeTotal: Int,                       // 勋章总数
        val topBadges: List<Pair<String, String>>, // 精选勋章（emoji to 名称，各类目最高级，至多 6 枚）
        val brandLine: String,                     // 顶部品牌行（"TGFeed · 观看报告 · M月d日"）
    )

    /**
     * 生成称号勋章版观看报告分享卡（1080×1920 竖版）。
     * 配色纪律与 App 内一致：黑底 + 白阶文字，强调色只有 AccentRed（小标签/勋章描边）与
     * TgBlue（顶部品牌行）两个。
     *
     * 布局（自上而下，单位 px，左右留边 64）：
     *  品牌行（蓝 34 Bold）→ "我的称号"（红 38 Bold）→ 称号大字（白 110 Bold，含 emoji）
     *  → 调侃文案（灰 40，单行截断）→ 发丝分割线 → "累计观看"（灰 34）→ 总时长（白 92 Bold）
     *  → 三枚统计小块（圆角深底：观看次数/连续观看/累计活跃，白 48 Bold 数值 + 灰 30 标签）
     *  → "勋章墙精选"（红 38 Bold）+ 右对齐灰 "已达成 N/M"（32）
     *  → 精选勋章网格（3 列至多 2 行：AccentRed 描边圆托 + emoji 68 + 名签白 34 居中）
     *  → 底部品牌条（"来自 TGFeed · 把 Telegram 刷成抖音" 落款，同视频卡样式）。
     * 一枚勋章都没达成时网格区换一句灰字引导，不画空网格。
     */
    fun generateBadgeShare(data: BadgeShareData): Bitmap {
        val bitmap = Bitmap.createBitmap(BADGE_CARD_W, BADGE_CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)
        val textWidth = (BADGE_CARD_W - BADGE_CARD_PAD * 2).toFloat()
        // 文本游标：每段先 y += 字号（drawText 的 y 是基线），再加段间距
        var y = 96f

        // ---- 顶部品牌行（电报蓝小字） ----
        val brandTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_REPORT_BLUE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 34f
        canvas.drawText(ellipsize(data.brandLine, brandTopPaint, textWidth), BADGE_CARD_PAD.toFloat(), y, brandTopPaint)
        y += 56f

        // ---- 称号区：红色小标签 + 称号大字 + 调侃文案 ----
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT_RED
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 38f
        canvas.drawText("我的称号", BADGE_CARD_PAD.toFloat(), y, labelPaint)
        y += 28f
        val personaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 110f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 110f
        canvas.drawText(ellipsize(data.personaTitle, personaPaint, textWidth), BADGE_CARD_PAD.toFloat(), y, personaPaint)
        y += 28f
        val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 40f
        }
        y += 40f
        canvas.drawText(ellipsize(data.personaTagline, taglinePaint, textWidth), BADGE_CARD_PAD.toFloat(), y, taglinePaint)
        y += 60f

        // ---- 发丝分割线（白 8%） ----
        canvas.drawRect(
            BADGE_CARD_PAD.toFloat(), y, (BADGE_CARD_W - BADGE_CARD_PAD).toFloat(), y + 2f,
            Paint().apply { color = 0x14FFFFFF },
        )
        y += 60f

        // ---- 数据区：累计观看大数字 + 三枚统计小块 ----
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 34f
        }
        y += 34f
        canvas.drawText("累计观看", BADGE_CARD_PAD.toFloat(), y, subPaint)
        y += 16f
        val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 92f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 92f
        canvas.drawText(ellipsize(data.totalText, totalPaint, textWidth), BADGE_CARD_PAD.toFloat(), y, totalPaint)
        y += 40f

        // 三枚统计小块：圆角深底（白 8%）+ 居中白数值 + 灰标签（纯数字口径，无频道名）
        val statGap = 24f
        val statW = (textWidth - statGap * 2) / 3f
        val statTop = y
        val statBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
        val statValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val statLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        val stats = listOf(
            "${data.eventCount} 次" to "观看次数",
            "${data.streakDays} 天" to "连续观看",
            "${data.activeDays} 天" to "累计活跃",
        )
        stats.forEachIndexed { i, (value, label) ->
            val left = BADGE_CARD_PAD + i * (statW + statGap)
            canvas.drawRoundRect(RectF(left, statTop, left + statW, statTop + BADGE_STAT_H), BADGE_STAT_RADIUS, BADGE_STAT_RADIUS, statBgPaint)
            val cx = left + statW / 2f
            canvas.drawText(value, cx, statTop + 80f, statValuePaint)
            canvas.drawText(label, cx, statTop + 134f, statLabelPaint)
        }
        y = statTop + BADGE_STAT_H + 72f

        // ---- 勋章区：红色小标签 + 右对齐灰达成计数 + 精选网格 ----
        y += 38f
        canvas.drawText("勋章墙精选", BADGE_CARD_PAD.toFloat(), y, labelPaint)
        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 32f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            "已达成 ${data.badgeAchieved}/${data.badgeTotal}",
            (BADGE_CARD_W - BADGE_CARD_PAD).toFloat(), y, countPaint,
        )
        y += 40f

        if (data.topBadges.isEmpty()) {
            // 一枚勋章都没达成：灰字引导占位，不画空网格
            y += 40f
            canvas.drawText("还没有达成勋章，再刷几天就有了", BADGE_CARD_PAD.toFloat(), y, taglinePaint)
        } else {
            // 精选网格：3 列，行结构 = 圆托 150 + 22 间距 + 名签 34；行距 48
            val cellGap = 32f
            val cellW = (textWidth - cellGap * (BADGE_GRID_COLS - 1)) / BADGE_GRID_COLS
            val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 68f
                textAlign = Paint.Align.CENTER
            }
            val circleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFE2C55 } // AccentRed 12% 底
            val circleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_ACCENT_RED
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val badgeNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_TITLE
                textSize = BADGE_NAME_SIZE
                textAlign = Paint.Align.CENTER
            }
            val rowH = BADGE_CIRCLE + 22f + BADGE_NAME_SIZE
            data.topBadges.forEachIndexed { idx, (emoji, name) ->
                val rowIdx = idx / BADGE_GRID_COLS
                val colIdx = idx % BADGE_GRID_COLS
                val rowTop = y + rowIdx * (rowH + 48f)
                val left = BADGE_CARD_PAD + colIdx * (cellW + cellGap)
                val cx = left + cellW / 2f
                val cy = rowTop + BADGE_CIRCLE / 2f
                canvas.drawCircle(cx, cy, BADGE_CIRCLE / 2f, circleBgPaint)
                canvas.drawCircle(cx, cy, BADGE_CIRCLE / 2f, circleBorderPaint)
                // 基线修正：让 emoji 视觉居中于圆心
                canvas.drawText(emoji, cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f, emojiPaint)
                canvas.drawText(ellipsize(name, badgeNamePaint, cellW), cx, rowTop + rowH, badgeNamePaint)
            }
        }

        // ---- 品牌条上方的居中口号（灰小字，与内容区底部分离，位置钉在品牌条上方不随内容高浮动） ----
        val sloganPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8A8E.toInt()
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "数字与勋章不说谎 · 这是我在 TGFeed 的观看足迹",
            BADGE_CARD_W / 2f,
            (BADGE_CARD_H - BRAND_H - 72).toFloat(),
            sloganPaint,
        )

        // ---- 底部品牌条（"来自 tgfeed" 落款，与视频卡同款） ----
        val brandBarTop = (BADGE_CARD_H - BRAND_H).toFloat()
        canvas.drawRect(0f, brandBarTop, BADGE_CARD_W.toFloat(), BADGE_CARD_H.toFloat(), Paint().apply {
            color = COLOR_BRAND_BG
        })
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_TEXT
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "来自 TGFeed · 把 Telegram 刷成抖音",
            BADGE_CARD_W / 2f,
            brandBarTop + BRAND_H / 2f - (brandPaint.descent() + brandPaint.ascent()) / 2f,
            brandPaint,
        )
        return bitmap
    }

    // ==================== 口味星图海报 + 点云绘制（R143/R147/R152，1080×1440） ====================

    /** 星图海报画布尺寸（3:4 竖版，与观看报告卡同幅面） */
    private const val TASTE_MAP_W = 1080
    private const val TASTE_MAP_H = 1440
    private const val TASTE_MAP_PAD = 64

    // ---- 3D 视觉参数（R147b 轨道相机 + R152 数据点云：App 交互画布与海报共用同一套投影） ----

    /** 轨道相机距离（世界单位）：相机绕云团中心 (500,500,0) 环绕，此为其轨道半径 */
    private const val TASTE_CAM_D = 1650f

    /** 深度雾距离（世界单位）：点离相机每远此值透明度约减半（远点淡出——数据点云的深度线索） */
    private const val TASTE_FOG = 900f

    /** 世界坐标系边长镜像（布局侧 TasteMapScreen.WORLD=1000f；core 层不反向依赖 UI，靠注释同步） */
    private const val TASTE_WORLD = 1000f

    /** 基准面网格每方向格数（5 格 6 线含边框——Plotly 3D 散点图的地面参照惯例） */
    private const val TASTE_GRID_DIV = 5

    /**
     * 星系轨道相机投影（R147b 唯一实现：App 命中测试/选中环与 [drawTasteMapField]/海报共用）：
     * 世界 (x,y,z) → 屏幕 (sx,sy) + 透视系数（[out] 长度 ≥3，调用方持有复用）。
     *  - 局部系：以星系中心为原点，x-y 为盘面、z 为盘法线（3D 力导向布局产出）；
     *  - 相机：轨道球坐标看向中心。[pitchDeg] 语义（俯视起算，R147b）：
     *    0°=正俯视（盘面摊开全圆）、90°=正侧视（碟状薄带）、38°=斜俯视初始角；
     *    [yawDeg] 绕盘法线环绕（水平拖拽旋转星系）——上下左右任意角度可观测；
     *  - 投影：针孔透视（视距 [TASTE_CAM_D]，透视系数=CAM_D/(CAM_D+深度)，近大远小）；
     *  - 缩放：[scale] 乘在焦距上（pinch/按钮=推拉镜头）；[out][2] 透视系数（星半径、
     *    边宽、雾浓度都由它调制）。
     */
    fun tasteProject(
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        scale: Float,
        centerX: Float,
        centerY: Float,
        out: FloatArray,
    ): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()
        // 俯视起算：cp=俯视程度（盘面在屏幕上的完整度），sp=侧视程度（盘厚可见度）
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        val lx = x - TASTE_WORLD / 2f
        val ly = y - TASTE_WORLD / 2f
        // 相机轴：right=(-sy,cy,0)｜up=(-cp·cy,-cp·sy,sp)｜toCam=(sp·cy,sp·sy,cp)
        val cx3 = -sy * lx + cy * ly
        val cy3 = (-cp * cy) * lx + (-cp * sy) * ly + sp * z
        val cz3 = (sp * cy) * lx + (sp * sy) * ly + cp * z
        val d = cz3 + TASTE_CAM_D
        val persp = TASTE_CAM_D / (if (d > 200f) d else 200f)
        val f = persp * scale
        out[0] = centerX + cx3 * f
        out[1] = centerY - cy3 * f
        out[2] = persp
        return out
    }

    /** 预解好的相机三角值（帧首解一次，星循环逐星复用，避免 300 星×每星 4 次 trig） */
    class TasteCamTrig internal constructor(val cy: Float, val sy: Float, val cp: Float, val sp: Float)

    /** 解一次 yaw/pitch 的三角值（[drawTasteMapField] 帧首调用，星循环复用） */
    fun tasteCamTrig(yawDeg: Float, pitchDeg: Float): TasteCamTrig {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        return TasteCamTrig(cos(yaw).toFloat(), sin(yaw).toFloat(), cos(pitch).toFloat(), sin(pitch).toFloat())
    }

    /**
     * 深度雾系数（约 0.5..1）：星离相机越远越淡——远星隐入背景的"看穿星系"层次感。
     * 由透视系数反推远离量（persp=CAM_D/d → away=CAM_D(1/persp−1)），雾=1/(1+away/FOG)。
     */
    fun tasteFog(persp: Float): Float {
        val away = TASTE_CAM_D / persp - TASTE_CAM_D
        return 1f / (1f + maxOf(0f, away) / TASTE_FOG)
    }

    // ---- 取景框（R152c 鲁棒取景：App 默认取景与海报共用） ----

    /**
     * 全量投影包围盒（scale=1、中心=0 的纯数学形态）：返回 [minX, minY, 宽, 高]，
     * 半径余量 1.35 倍（屏幕空间标记口径：radius × 透视）。用于缩小下限/全貌海报。
     */
    fun tasteBoundsFull(stars: List<TasteMapStar>, yawDeg: Float, pitchDeg: Float): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val pt = FloatArray(3)
        for (s in stars) {
            tasteProject(s.x, s.y, s.z, yawDeg, pitchDeg, 1f, 0f, 0f, pt)
            val r = s.radius * pt[2] * 1.35f
            minX = minOf(minX, pt[0] - r)
            maxX = maxOf(maxX, pt[0] + r)
            minY = minOf(minY, pt[1] - r)
            maxY = maxOf(maxY, pt[1] + r)
        }
        if (minX > maxX || minY > maxY || (maxX - minX) < 1f || (maxY - minY) < 1f) {
            return floatArrayOf(-TASTE_WORLD / 2f, -TASTE_WORLD / 2f, TASTE_WORLD, TASTE_WORLD)
        }
        return floatArrayOf(minX, minY, (maxX - minX).coerceAtLeast(80f), (maxY - minY).coerceAtLeast(80f))
    }

    /**
     * 鲁棒取景框（R152c 真机反馈"点挤一角空大半屏"的根治，App 默认取景与海报共用）：
     * max 包围盒被少量远置点撑大数倍（未知极近满距 + 离群频道），主体云团被缩成一角。改为——
     *  - 只统计 hasComp 的星（看过/有索引基因/有投票 = 口味云团本体）；
     *  - 逐轴 p2..p98 分位框（默认收进 96% 的主体点，极端点初始略出画——位置不动
     *    只是取景，缩小镜头随时看全貌）；
     *  - 名签星（top 观看频道）强制并入：用户最关心的头部频道必须首屏可见；
     *  - 退化保护：证据星不足/框宽 <1 时回退全量盒。
     */
    fun tasteBoundsRobust(stars: List<TasteMapStar>, yawDeg: Float, pitchDeg: Float): FloatArray {
        val pt = FloatArray(3)
        val xs = ArrayList<Float>(stars.size)
        val ys = ArrayList<Float>(stars.size)
        var lxMin = Float.MAX_VALUE
        var lxMax = -Float.MAX_VALUE
        var lyMin = Float.MAX_VALUE
        var lyMax = -Float.MAX_VALUE
        for (s in stars) {
            tasteProject(s.x, s.y, s.z, yawDeg, pitchDeg, 1f, 0f, 0f, pt)
            if (s.hasComp) {
                xs.add(pt[0])
                ys.add(pt[1])
            }
            if (s.label != null) {
                val r = s.radius * pt[2] * 1.35f
                lxMin = minOf(lxMin, pt[0] - r)
                lxMax = maxOf(lxMax, pt[0] + r)
                lyMin = minOf(lyMin, pt[1] - r)
                lyMax = maxOf(lyMax, pt[1] + r)
            }
        }
        if (xs.size < 3) return tasteBoundsFull(stars, yawDeg, pitchDeg)
        xs.sort()
        ys.sort()
        val m = xs.size
        val lo = (m * 0.02f).toInt().coerceIn(0, m - 1)
        val hi = (m * 0.98f).toInt().coerceIn(0, m - 1)
        var minX = xs[lo]
        var maxX = xs[hi]
        var minY = ys[lo]
        var maxY = ys[hi]
        if (lxMin <= lxMax) { // 名签星强制并入
            minX = minOf(minX, lxMin)
            maxX = maxOf(maxX, lxMax)
            minY = minOf(minY, lyMin)
            maxY = maxOf(maxY, lyMax)
        }
        if (minX > maxX || minY > maxY || (maxX - minX) < 1f || (maxY - minY) < 1f) {
            return tasteBoundsFull(stars, yawDeg, pitchDeg)
        }
        return floatArrayOf(minX, minY, (maxX - minX).coerceAtLeast(80f), (maxY - minY).coerceAtLeast(80f))
    }

    /**
     * 星图单颗点（世界坐标）：x/y/z 与 radius 都是 UI 侧 MDS 嵌入产出的世界单位；
     * z 为深度轴（嵌入第三主成分）；colorArgb 为 ARGB Int；label 非空才画名签
     * （低缩放/海报只给头部频道名，防糊屏）；chatId 供焦点星邻居高亮判定（App 点星联动）；
     * hasComp 为 R152c 鲁棒取景标记（有任何构成证据=口味云团本体，默认 true 兜底）。
     */
    data class TasteMapStar(
        val x: Float,
        val y: Float,
        val z: Float = 0f,
        val radius: Float,
        val colorArgb: Int,
        val alpha: Float,
        val label: String? = null,
        val chatId: Long = 0L,
        val hasComp: Boolean = true,
    )

    /**
     * 星图一条无向边（两端世界坐标+深度）：alpha 已按边权归一（0..1），绘制端不再重算；
     * a/b 为端点 chatId（a<b），[drawTasteMapField] 的焦点邻边高亮按它判定。
     */
    data class TasteMapEdge(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val z1: Float = 0f,
        val z2: Float = 0f,
        val a: Long = 0L,
        val b: Long = 0L,
        val alpha: Float,
    )

    /** 星图图例项：色点 + 文案（视频/图文/音频/暗星……） */
    data class TasteMapLegendEntry(
        val label: String,
        val colorArgb: Int,
    )

    /**
     * 口味星图海报数据（UI 层投影成纯坐标/颜色/文案，core.playback 不反向依赖布局实现）。
     * headline/subLine 构成标题区（"我的口味星图" + "N 个频道 · 互链 M 条 · 累计观看 X 小时"）；
     * gridHalf/gridZ 为 R152 基准面网格参数（布局产出：半幅 + 所在深度，≤0 不画网格）。
     */
    data class TasteMapCardData(
        val headline: String,
        val subLine: String,
        val stars: List<TasteMapStar>,
        val edges: List<TasteMapEdge>,
        val gridHalf: Float = 0f,
        val gridZ: Float = 0f,
        val zSpan: Float = 160f,
        val legend: List<TasteMapLegendEntry>,
        val brandLine: String,
    )

    /**
     * 基准面细网格（R152）：世界 z=[gridZ]、x/y 覆盖 ±[halfWorld] 的参照平面，经
     * [tasteProject] 同一套相机投影画到屏幕（透视下直线仍为直线——射影变换性质，
     * 两端点投影连线即精确线形）。线宽 1px 级、透明度按两端深度雾衰减、外框略实——
     * 干净的数据可视化参照面，不是星系装饰。
     */
    fun drawTasteMapGrid(
        canvas: Canvas,
        yawDeg: Float,
        pitchDeg: Float,
        scale: Float,
        centerX: Float,
        centerY: Float,
        halfWorld: Float,
        gridZ: Float,
    ) {
        if (halfWorld <= 0f) return
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()
        val trig = tasteCamTrig(yawDeg, pitchDeg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val step = 2f * halfWorld / TASTE_GRID_DIV
        // 每条线两端点投影 + 平均雾定透明度；边框线（k=0/格）略粗略实
        for (k in 0..TASTE_GRID_DIV) {
            val off = -halfWorld + k * step
            val border = k == 0 || k == TASTE_GRID_DIV
            for (axis in 0..1) {
                // axis 0：沿 x 的横线（y 固定）；axis 1：沿 y 的竖线（x 固定）
                val x1 = if (axis == 0) -halfWorld else off
                val y1 = if (axis == 0) off else -halfWorld
                val x2 = if (axis == 0) halfWorld else off
                val y2 = if (axis == 0) off else halfWorld
                // 内联投影（与 tasteProject 同式，lx/ly 为云团中心系）
                val p1x = -trig.sy * x1 + trig.cy * y1
                val p1y = (-trig.cp * trig.cy) * x1 + (-trig.cp * trig.sy) * y1 + trig.sp * gridZ
                val p1z = (trig.sp * trig.cy) * x1 + (trig.sp * trig.sy) * y1 + trig.cp * gridZ
                val d1 = p1z + TASTE_CAM_D
                val persp1 = TASTE_CAM_D / (if (d1 > 200f) d1 else 200f)
                val sx1 = centerX + p1x * persp1 * scale
                val sy1 = centerY - p1y * persp1 * scale
                val p2x = -trig.sy * x2 + trig.cy * y2
                val p2y = (-trig.cp * trig.cy) * x2 + (-trig.cp * trig.sy) * y2 + trig.sp * gridZ
                val p2z = (trig.sp * trig.cy) * x2 + (trig.sp * trig.sy) * y2 + trig.cp * gridZ
                val d2 = p2z + TASTE_CAM_D
                val persp2 = TASTE_CAM_D / (if (d2 > 200f) d2 else 200f)
                val sx2 = centerX + p2x * persp2 * scale
                val sy2 = centerY - p2y * persp2 * scale
                // 纯屏外的网格线整条裁掉
                if ((sx1 < -4f && sx2 < -4f) || (sx1 > cw + 4f && sx2 > cw + 4f) ||
                    (sy1 < -4f && sy2 < -4f) || (sy1 > ch + 4f && sy2 > ch + 4f)
                ) continue
                val fogAvg = (tasteFog(persp1) + tasteFog(persp2)) * 0.5f
                val a = (if (border) 0.10f else 0.05f) + 0.05f * fogAvg
                paint.alpha = (a * 255f).toInt()
                paint.strokeWidth = (if (border) 1.3f else 0.9f) * scale.coerceIn(0.5f, 2.5f)
                canvas.drawLine(sx1, sy1, sx2, sy2, paint)
            }
        }
    }

    // （R152 删除清单：光晕精灵缓存/星云位图——用户方向纠偏"不是星系漩涡背景，更像一簇
    //  高维向量映射到三维空间"，星系贴图装饰全删；投影/雾/相机全保留。R152b 终版按用户
    //  追加回归**背景星点**：纯装饰的细密深空星点做底幕——不做旋臂不做星云不做大星系，
    //  暗且小、静态零视差，克制到明显是背景装饰、绝不抢数据点的前景视觉）

    // ---- 背景星点（R152b：深空装饰底幕，半分辨率位图缓存——每帧一次 drawBitmap） ----

    /** 星点位图缓存：键 = (width shl 20) or height——画布尺寸变才重建；
     *  App 交互画布（主线程）与海报导出（IO 线程）都可能取，用 ConcurrentHashMap 原子建图 */
    private val tasteDustCache = java.util.concurrent.ConcurrentHashMap<Long, Bitmap>()

    /** 缓存条目上限：超限整体清空重建（画布变尺寸罕见，一次性成本可接受） */
    private const val TASTE_DUST_CACHE_MAX = 4

    /** 贴图笔（只在初始化时配置，跨线程只读复用；FILTER 放大柔边正合远景星点气质） */
    private val tasteDustPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 深空星点背景（纯装饰层，R152b 按用户追加回归——"之前版本那种装饰性背景星星"）：
     * 细密暗小的**静态**星点铺满深底，给数据点云当"夜空"底幕，与 R147/R151 星系路线的
     * 区别在克制——没有臂带没有星云没有银河横带，只有无穷远的零视差星点（相机旋转时
     * 它不动：无穷远背景的正确物理）：
     *  - 亮度幂律三档：多数 3~8% 白（微尘）/ 少数 9~16%（可见背景星）/ 极少数 18~26%
     *    （远处亮星）——比数据点（32%~100% 彩色、半径 3~60px）暗小一个量级，不抢戏；
     *  - 半径 0.5~2px、冷暖色温微差（约 1/5 偏蓝、1/10 偏暖）——星场的照片感；
     *  - 位置/亮度/色温全由网格坐标哈希决定（无 Random 无状态，每帧结果稳定）；
     *  - 静态不闪（用户口径"静态或极微弱闪烁即可"——取静态：数据点云要稳定读数，
     *    也免掉闲置期的重绘心跳）；
     *  - 半分辨率位图缓存 + 每帧一次 drawBitmap：上千星点零逐帧逐点成本（点半径按
     *    2 倍预放大补偿，过滤放大后的柔边正合远景星点气质）。
     */
    fun drawTasteMapDust(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val w = width.toInt()
        val h = height.toInt()
        val key = (w.toLong() shl 20) or h.toLong()
        var dust = tasteDustCache[key]
        if (dust == null) {
            if (tasteDustCache.size >= TASTE_DUST_CACHE_MAX) tasteDustCache.clear()
            dust = buildTasteDustBitmap(w, h)
            tasteDustCache[key] = dust
        }
        canvas.drawBitmap(dust, null, RectF(left, top, left + w, top + h), tasteDustPaint)
    }

    /** 渲染一张半分辨率星点位图（确定性哈希撒点，三档亮度幂律 + 冷暖色温微差——克制档） */
    private fun buildTasteDustBitmap(w: Int, h: Int): Bitmap {
        val sw = (w / 2).coerceAtLeast(1)
        val sh = (h / 2).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = 22f // 半分辨率网格 22px（折合全分辨率 44px），1/3 格落星——细密但不密
        val cols = (sw / cell).toInt() + 1
        val rows = (sh / cell).toInt() + 1
        for (yy in 0 until rows) {
            for (xx in 0 until cols) {
                val hash = (xx * 73856093 xor yy * 19349663) and 0xFFFFFF
                if (hash % 3 != 0) continue
                val px = xx * cell + (hash shr 5) % 22
                val py = yy * cell + (hash shr 11) % 22
                // 三档亮度（幂律）：多数微尘 / 少数可见星 / 极少数远处亮星——克制不抢数据点
                val tier = hash % 89
                val alpha: Int
                val radius: Float
                when {
                    tier < 4 -> {
                        alpha = 46 + (hash shr 16) % 21 // 18%~26% 白：远处可见亮星
                        radius = 1.5f + (hash % 20) * 0.02f
                    }
                    tier < 20 -> {
                        alpha = 24 + (hash shr 16) % 17 // 9%~16% 白：可见背景星
                        radius = 1.0f + (hash % 20) * 0.02f
                    }
                    else -> {
                        alpha = 8 + (hash shr 16) % 13 // 3%~8% 白：微尘（占绝对多数）
                        radius = 0.55f + (hash % 16) * 0.025f
                    }
                }
                // 色温微差：多数冷白、约 1/5 偏蓝、约 1/10 偏暖——星场的照片感
                paint.color = when (hash % 11) {
                    0, 1 -> 0xFF9FB6E8.toInt()
                    2 -> 0xFFE8D9B0.toInt()
                    else -> 0xFFE6ECF5.toInt()
                }
                paint.alpha = alpha
                c.drawCircle(px, py, radius, paint)
            }
        }
        return bmp
    }

    /**
     * 点云绘制（R152c 真机实拍精修版：App 内交互图与导出海报共用一套笔触）：
     *  - 投影：[tasteProject]（针孔透视，近大远小），深度雾（[tasteFog]）调制一切笔触
     *    ——点大小编码观看时长幂律、颜色编码主导内容类型（低饱和柔光档）、亮度编码新鲜度+互动，全数据驱动；
     *  - 边：两端各自投影的**细线**（拓扑关系，1px 级线宽，随深度雾变淡）；[focusId] ≠ 0
     *    时两遍绘制——普通边垫底、焦点星的邻边后画加亮（邻居预览高亮），邻居集合在
     *    边循环里顺手收齐（点循环给邻居画环，零二次遍历）；
     *  - 点（真机"光晕糊成大色块"收敛）：实心小圆（抗锯齿、清晰锐利）为数据本体；
     *    紧贴薄晕=1.32 倍半径的低透同色盘（晕与本体外缘之间只留 0.3 倍半径宽的柔光圈
     *    ——微发光感，绝不糊块）；前排大点一圈白描边；无微闪无光芒（点云要稳定读数）；
     *  - 名签（真机"频道名叠成一团"根治）：两遍制——点循环只收候选，循环后按半径大者
     *    优先贪心放置：撞车先试上/下偏移一行、再试换左侧挂（贴右缘的星），全撞则放弃
     *    只留胜者；label 只由数据端给 top 观看频道挂（小星不挂牌，App 侧点星另出签）；
     *  - 裁剪：屏幕外 +1.5 倍半径直接跳过。
     * 调用方须传按 cz 深度升序排好的点表（远→近画家序，绘制端零排序零分配）。
     * [labelTextSize] 由调用方按画布密度给（海报 28px，App 内 dp 换算）。
     */
    fun drawTasteMapField(
        canvas: Canvas,
        stars: List<TasteMapStar>,
        edges: List<TasteMapEdge>,
        yawDeg: Float,
        pitchDeg: Float,
        scale: Float,
        centerX: Float,
        centerY: Float,
        labelMinPx: Float,
        labelTextSize: Float,
        focusId: Long = 0L,
    ) {
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()
        val pt = FloatArray(3)
        // 帧首解一次三角（点循环逐点复用，避免 300 点 × 每点 4 次 trig）
        val trig = tasteCamTrig(yawDeg, pitchDeg)
        // 焦点邻居集合：焦点点的连线对端（点循环画环用）
        val neighborIds = if (focusId != 0L) HashSet<Long>() else null

        // ---- 边两遍：普通边垫底，焦点邻边压上（邻居预览高亮） ----
        if (edges.isNotEmpty()) {
            val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val edgeFocusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE6FFFFFF.toInt() // 焦点邻边：亮白
            }
            for (pass in 0..1) {
                val wantFocus = pass == 1
                for (e in edges) {
                    val isFocus = focusId != 0L && (e.a == focusId || e.b == focusId)
                    if (isFocus != wantFocus) continue
                    tasteProject(e.x1, e.y1, e.z1, yawDeg, pitchDeg, scale, centerX, centerY, pt)
                    val x1 = pt[0]
                    val y1 = pt[1]
                    val p1 = pt[2]
                    tasteProject(e.x2, e.y2, e.z2, yawDeg, pitchDeg, scale, centerX, centerY, pt)
                    val x2 = pt[0]
                    val y2 = pt[1]
                    val p2 = pt[2]
                    // 端点任一在屏内才画（纯屏外的连线整条裁掉）
                    if ((x1 < 0f && x2 < 0f) || (x1 > cw && x2 > cw) ||
                        (y1 < 0f && y2 < 0f) || (y1 > ch && y2 > ch)
                    ) continue
                    if (isFocus) {
                        // 邻边高亮：亮白实线 + 稍粗（线宽随端点透视均值缩放）
                        edgeFocusPaint.strokeWidth = (2.0f * scale * (p1 + p2) * 0.5f).coerceIn(1.1f, 4.2f)
                        edgeFocusPaint.alpha = ((e.alpha * 1.6f).coerceIn(0.25f, 0.9f) * 255f).toInt()
                        canvas.drawLine(x1, y1, x2, y2, edgeFocusPaint)
                        neighborIds?.add(if (e.a == focusId) e.b else e.a)
                    } else {
                        // 普通边：数据点云的细线——1px 级、低透明、深度雾淡出
                        val depthFade = (tasteFog(p1) + tasteFog(p2)) * 0.5f
                        edgePaint.strokeWidth = (0.9f * scale * (p1 + p2) * 0.5f).coerceIn(0.55f, 1.8f)
                        edgePaint.alpha = (e.alpha.coerceIn(0.03f, 0.5f) * depthFade * 255f).toInt()
                        canvas.drawLine(x1, y1, x2, y2, edgePaint)
                    }
                }
            }
        }
        // ---- 点：远→近画家序（调用方已排序），实心小圆 + 紧贴薄晕 + 前排描边 + 邻居环 ----
        val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        val core = Paint(Paint.ANTI_ALIAS_FLAG)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt() // 邻居环：白 60%
            style = Paint.Style.STROKE
            strokeWidth = 1.3f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            textSize = labelTextSize
        }
        // R152c 名签两遍制：点循环只收集候选（屏坐标/半径/文本），循环后按半径优先
        // 贪心放置 + 重叠避让（真机反馈"频道名叠成一团"——撞车先试上下偏移一行，
        // 仍撞则放弃只留胜者；小星本来就不进候选：label 只在数据端给 top 频道挂）
        val labelCands = ArrayList<TasteLabelCand>(12)
        // 逐点：与 tasteProject 同一套内联投影（trig 已帧首解好；pitch 俯视起算）
        for (s in stars) {
            val lx = s.x - TASTE_WORLD / 2f
            val ly = s.y - TASTE_WORLD / 2f
            val cx3 = -trig.sy * lx + trig.cy * ly
            val cy3 = (-trig.cp * trig.cy) * lx + (-trig.cp * trig.sy) * ly + trig.sp * s.z
            val cz3 = (trig.sp * trig.cy) * lx + (trig.sp * trig.sy) * ly + trig.cp * s.z
            val d = cz3 + TASTE_CAM_D
            val persp = TASTE_CAM_D / (if (d > 200f) d else 200f)
            val f = persp * scale
            val sx = centerX + cx3 * f
            val sy = centerY - cy3 * f
            val fog = tasteFog(persp)
            // R152c 标记改屏幕空间尺寸：r = radius × 透视系数（不含 scale）——缩放只改
            // 点距不改点径（数据可视化散点惯例）。真机实病根：真实数据的口味云团很紧，
            // 鲁棒取景一放大 scale 到 10 倍级，世界单位半径的点全体膨胀成大色块糊成一片；
            // 屏幕空间标记下放大镜头=拉开点距看结构，点始终是清晰小圆。透视系数保留
            // （±30% 近大远小仍是深度线索）
            val r = s.radius * persp // 点大小：观看时长幂律 × 透视近大远小（不随缩放膨胀）
            if (r <= 0.55f) continue
            // 屏外裁剪（留 1.5 倍半径余量——薄晕+描边范围）
            if (sx < -r * 1.5f || sx > cw + r * 1.5f || sy < -r * 1.5f || sy > ch + r * 1.5f) continue
            val baseA = (s.alpha * fog).coerceIn(0.05f, 1f)
            // 紧贴薄晕（R152c 真机"光晕糊成大色块"收敛：2.0 倍大盘→1.32 倍薄环，
            // 只在晕与本体之间留 0.3 倍半径宽的柔光圈——微发光感，不糊成块）
            if (r >= 3.5f) {
                halo.color = ((baseA * 0.16f * 255f).toInt() shl 24) or (s.colorArgb and 0xFFFFFF)
                canvas.drawCircle(sx, sy, r * 1.32f, halo)
            }
            // 实心点（数据本体：清晰锐利）
            core.color = (baseA * 255f).toInt() shl 24 or (s.colorArgb and 0xFFFFFF)
            canvas.drawCircle(sx, sy, r, core)
            // 前排大点白描边：近处大点更"锐"的层次提示
            if (r >= 7f && persp > 1.05f) {
                rim.color = ((baseA * 0.55f * 255f).toInt() shl 24) or 0xFFFFFF
                rim.strokeWidth = maxOf(0.8f, r * 0.05f)
                canvas.drawCircle(sx, sy, r + rim.strokeWidth, rim)
            }
            // 焦点邻居细环（邻居预览：连线那头是谁，一眼看清）
            if (neighborIds != null && s.chatId != focusId && s.chatId in neighborIds) {
                ringPaint.alpha = (baseA * 200f).toInt()
                canvas.drawCircle(sx, sy, r + 5f, ringPaint)
            }
            // 名签候选收集（循环后统一避让放置）
            if (s.label != null && r >= labelMinPx) {
                labelCands.add(TasteLabelCand(sx, sy, r, s.label, (baseA * 255f).toInt()))
            }
        }
        // ---- 名签避让放置（R206 提取共享：placeTasteLabels 唯一实现，App 飞行渲染器共用） ----
        placeTasteLabels(canvas, labelCands, labelPaint, labelTextSize)
    }

    /** 名签放置候选（R152c 两遍制：点循环收集，循环后按半径优先贪心避让；
     *  R206 升 internal——App 飞行渲染器与海报共用同一个避让实现，纪律§一单一定义） */
    internal class TasteLabelCand(
        val sx: Float,
        val sy: Float,
        val r: Float,
        val text: String,
        val alpha: Int,
    )

    /**
     * 名签避让放置（R152c 两遍制的唯一实现，R206 从 drawTasteMapField 提取为
     * 共享件——纯函数搬家，行为与提取前逐字节等价）：半径大者优先（头部频道是
     * 胜者），撞车先试上/下偏移一行、再试换左侧挂（贴右缘的星），全撞则放弃
     * 只留胜者。App 交互星图（TasteFlightRenderer）与导出海报共用本函数。
     */
    internal fun placeTasteLabels(
        canvas: Canvas,
        cands: List<TasteLabelCand>,
        labelPaint: Paint,
        labelTextSize: Float,
    ) {
        if (cands.isEmpty()) return
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()
        labelPaint.textSize = labelTextSize
        val sorted = if (cands.size > 1) cands.sortedByDescending { it.r } else cands
        val placed = ArrayList<RectF>(sorted.size)
        val lineH = labelTextSize * 1.25f
        val box = RectF()
        for (cand in sorted) {
            val text = ellipsize(cand.text, labelPaint, 300f)
            val tw = labelPaint.measureText(text)
            val th = lineH * 0.85f
            var drawX = -1f
            var drawY = 0f
            // 锚点候选：右侧原位 → 右上移一行 → 右下移一行 → 左侧三连（贴右缘的星换左侧挂）
            val rightX = cand.sx + cand.r + 6f
            val leftX = cand.sx - cand.r - 6f - tw
            val anchors = listOf(
                rightX to cand.sy,
                rightX to cand.sy - lineH,
                rightX to cand.sy + lineH,
                leftX to cand.sy,
                leftX to cand.sy - lineH,
                leftX to cand.sy + lineH,
            )
            for ((ax, ay) in anchors) {
                // 出画裁掉（左右各留 4px 边）
                if (ax < 4f || ax + tw > cw - 4f) continue
                box.set(ax, ay - th, ax + tw, ay + th * 0.25f)
                var hit = false
                for (p in placed) if (RectF.intersects(p, box)) { hit = true; break }
                if (!hit) { drawX = ax; drawY = ay; break }
            }
            if (drawX >= 0f) {
                labelPaint.alpha = cand.alpha
                canvas.drawText(text, drawX, drawY - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
                placed.add(RectF(box))
            }
            // 全锚点撞车：放弃该名签（只留胜者）——比叠成一团可读
        }
    }

    /**
     * 生成口味星图海报（1080×1440 竖版，Wrapped 风传播物料；R152 起与 App 同一套数据点云绘制）。
     * 布局（自上而下，左右留边 64）：品牌行（蓝）→ 大标题（白 72 Bold）→ 副行（灰 38：
     * 频道数/互链数/累计观看）→ 方法注脚（灰 28：高维口味向量 · 余弦相似度 3D 投影）
     * → 发丝分割线 → 点云区（深空星点底幕 + 网格基准面 + 透视点云：投影包围盒等比适配
     * 居中、只给头部频道挂名签）→ 图例行（色点 + 文案，数据端给定）→ 底部品牌条（同视频卡）。
     * 点云内所有元素与 App 内交互星图共用 [tasteProject]/[drawTasteMapGrid]/
     * [drawTasteMapField]，一套笔触两处呈现。
     */
    fun generateTasteMap(data: TasteMapCardData): Bitmap {
        val bitmap = Bitmap.createBitmap(TASTE_MAP_W, TASTE_MAP_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)
        var y = 72f

        // ---- 顶部品牌行（电报蓝小字，同报告卡） ----
        val brandTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_REPORT_BLUE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 34f
        canvas.drawText(ellipsize(data.brandLine, brandTopPaint, (TASTE_MAP_W - TASTE_MAP_PAD * 2).toFloat()), TASTE_MAP_PAD.toFloat(), y, brandTopPaint)
        y += 64f

        // ---- 大标题 + 副行 ----
        val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
        }
        y += 72f
        canvas.drawText(ellipsize(data.headline, headlinePaint, (TASTE_MAP_W - TASTE_MAP_PAD * 2).toFloat()), TASTE_MAP_PAD.toFloat(), y, headlinePaint)
        y += 24f
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textSize = 38f
        }
        y += 38f
        canvas.drawText(ellipsize(data.subLine, subPaint, (TASTE_MAP_W - TASTE_MAP_PAD * 2).toFloat()), TASTE_MAP_PAD.toFloat(), y, subPaint)
        y += 34f

        // ---- 方法注脚（R152：声明这张图怎么来的——数据可视化的诚实标注） ----
        val methodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A8494.toInt()
            textSize = 28f
        }
        y += 28f
        canvas.drawText("高维口味向量 · 余弦相似度 3D 投影 · 点大小 = 观看时长", TASTE_MAP_PAD.toFloat(), y, methodPaint)
        y += 30f

        // ---- 发丝分割线（白 8%） ----
        canvas.drawRect(
            TASTE_MAP_PAD.toFloat(), y, (TASTE_MAP_W - TASTE_MAP_PAD).toFloat(), y + 2f,
            Paint().apply { color = 0x14FFFFFF },
        )
        y += 24f

        // ---- 点云区：轨道相机投影包围盒适配（与 App 同一投影），网格基准面垫底 + 点云 ----
        val fieldLeft = TASTE_MAP_PAD.toFloat()
        val fieldTop = y
        val fieldW = (TASTE_MAP_W - TASTE_MAP_PAD * 2).toFloat()
        val legendH = 64f
        val fieldBottom = (TASTE_MAP_H - BRAND_H - legendH - 48).toFloat() // 给图例与品牌条让位
        val fieldH = fieldBottom - fieldTop

        if (data.stars.isNotEmpty() && fieldW > 0f && fieldH > 0f) {
            // 海报定角（静态"黄金角度"：斜俯视 52° + 环绕 25°——簇平面与第三维同时可见）
            val yaw = 25f
            val pitch = 52f
            // 远→近画家序：按该视角下深度（cz3 = sp·cy·lx + sp·sy·ly + cp·z）排序，同深大点后画
            val trig = tasteCamTrig(yaw, pitch)
            val sorted = data.stars.sortedWith(
                compareBy(
                    { it.z * trig.cp + (it.y - TASTE_WORLD / 2f) * (trig.sp * trig.sy) + (it.x - TASTE_WORLD / 2f) * (trig.sp * trig.cy) },
                    { it.radius },
                ),
            )
            // 投影取景框（R152c 鲁棒版，与 App 默认取景同一套——聚焦口味云团 p2..p98 +
            // 名签星并入；离群点/未知极在海报边缘外，位置不变只是取景）
            val bounds = tasteBoundsRobust(sorted, yaw, pitch)
            val minX = bounds[0]
            val minY = bounds[1]
            val bw = bounds[2]
            val bh = bounds[3]
            val fit = minOf(fieldW / bw, fieldH / bh) * 0.97f
            // 取景框中心平移到内容矩形中心（共享投影以画布中心为轴，这里补偏移）
            val cX = minX + bw / 2f
            val cY = minY + bh / 2f
            val centerX = fieldLeft + fieldW / 2f - cX * fit
            val centerY = fieldTop + fieldH / 2f - cY * fit
            // 深空星点底幕（纯装饰，与 App 同一撒点器）→ 网格基准面（数据参照）→ 点云
            drawTasteMapDust(canvas, fieldLeft, fieldTop, fieldW, fieldH)
            if (data.gridHalf > 0f) {
                drawTasteMapGrid(canvas, yaw, pitch, fit, centerX, centerY, data.gridHalf, data.gridZ)
            }
            drawTasteMapField(
                canvas, sorted, data.edges, yaw, pitch, fit, centerX, centerY,
                labelMinPx = 7f, labelTextSize = 28f,
            )
        }

        // ---- 图例行：色点 16 + 文案 30，横向铺开 ----
        if (data.legend.isNotEmpty()) {
            val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_CAPTION
                textSize = 30f
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rowW = (TASTE_MAP_W - TASTE_MAP_PAD * 2).toFloat() / data.legend.size
            val cy = fieldBottom + 44f
            data.legend.forEachIndexed { i, entry ->
                val cx = TASTE_MAP_PAD + i * rowW + rowW / 2f
                // 条目整体（点+字）在格内居中
                val textW = legendPaint.measureText(entry.label)
                val startX = cx - (16f + 12f + textW) / 2f
                dotPaint.color = entry.colorArgb
                canvas.drawCircle(startX + 8f, cy - 10f, 8f, dotPaint)
                canvas.drawText(entry.label, startX + 16f + 12f, cy, legendPaint)
            }
        }

        // ---- 底部品牌条（与视频卡同款） ----
        val brandBarTop = (TASTE_MAP_H - BRAND_H).toFloat()
        canvas.drawRect(0f, brandBarTop, TASTE_MAP_W.toFloat(), TASTE_MAP_H.toFloat(), Paint().apply {
            color = COLOR_BRAND_BG
        })
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BRAND_TEXT
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "来自 TGFeed · 把 Telegram 刷成抖音",
            TASTE_MAP_W / 2f,
            brandBarTop + BRAND_H / 2f - (brandPaint.descent() + brandPaint.ascent()) / 2f,
            brandPaint,
        )
        return bitmap
    }

    // ---- 文本工具 ----

    /** 单行截断：超宽时截断并补 "…" */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val avail = maxWidth - paint.measureText(ellipsis)
        if (avail <= 0) return ellipsis
        val n = paint.breakText(text, true, avail, null)
        return text.substring(0, n).trimEnd() + ellipsis
    }

    /** 手动断行：按像素宽逐行切，最多 maxLines 行；最后一行放不下时截断补 "…" */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>(maxLines)
        var rest = text
        while (rest.isNotEmpty() && lines.size < maxLines) {
            val n = paint.breakText(rest, true, maxWidth, null)
            if (n <= 0) break
            if (n >= rest.length) {
                lines.add(rest)
                return lines
            }
            if (lines.size == maxLines - 1) {
                // 最后一行：截断补省略号
                lines.add(ellipsize(rest, paint, maxWidth))
                return lines
            }
            lines.add(rest.substring(0, n).trimEnd())
            rest = rest.substring(n).trimStart()
        }
        return lines
    }
}
