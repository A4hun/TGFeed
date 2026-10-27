package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R381b 频道编年史质感革命 wiring 源码锚（R376WiringSourceTest 同款 user.dir 源
 * 定位模式——Compose 呈现层无 JVM 直测面，以源锚锁**结构面**）：
 *  - **在场源锚**：时间脊柱四件套+峰日金标+stagger 揭示（ChannelHomeScreen）；
 *    六卷卷宗卡层次——卷头卡化 chevron/卷身档案纸面/②律动连续光流/④里程碑小
 *    脊柱（TasteMapScreen）；
 *  - **数据口零改负锚**：ChannelChronicle/FeedChroniclePart/TasteRadar 签名原样；
 *    呈现文件零数据侧调用（of/put/cached/peek 折算缓存口绝迹——呈现层只读）；
 *  - **判词行保留锚**（编年史北极星）+ **R140 穿越机器全套保留锚**（壳/过场/
 *    落地卡/失败卡/随机卡/扩展行/兜底年代卡）；
 *  - **关键常量单源锚**（脊柱几何/光流桶数/紧晕口径/卷宗纸面几何——声明恰一处）。
 *  测试名零 / · . 字符（JVM 非法字符纪律——AGENT-DISCIPLINE §七）。
 */
class R381bWiringSourceTest {

    private val mainSourceRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = File(dir, "src/main/java")
                if (candidate.isDirectory) return candidate
                val nested = File(dir, "app/src/main/java")
                if (nested.isDirectory) return nested
                dir = dir.parentFile ?: return@repeat
            }
            error("找不到主源码根（user.dir=${System.getProperty("user.dir")}）")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun chs(): String = src("dev/tgfeed/ui/ChannelHomeScreen.kt")
    private fun tms(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")
    private fun chronicle(): String = src("dev/tgfeed/core/feed/ChannelChronicle.kt")
    private fun chroniclePart(): String = src("dev/tgfeed/core/feed/FeedChroniclePart.kt")
    private fun tasteRadar(): String = src("dev/tgfeed/core/feed/TasteRadar.kt")

    /** 函数体截取（自签名起做括号配平——MotionTokensPolicyTest 同款扫描器范式） */
    private fun funBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("函数应在场：$signature", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            val c = source[i]
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return source.substring(start, i + 1)
            }
            i++
        }
        return source.substring(start)
    }

    /** 声明恰一处（单源——正文按名引用不算声明；散落字面量即红） */
    private fun assertSingleDeclaration(source: String, declRegex: String, what: String) {
        val count = Regex(declRegex).findAll(source).count()
        assertEquals("$what 应恰有一处声明（单源红线）：$declRegex", 1, count)
    }

    // ==================== ① 时间脊柱呈现件在场源锚（ChannelHomeScreen） ====================

    @Test
    fun `时间脊柱四件套在场源锚（ChannelHomeScreen）`() {
        val chs = chs()
        for (fn in listOf(
            "private fun ChronicleSpineColumn(",
            "private fun ChronicleEraCard(",
            "private fun ChronicleSpineNode(",
            "private fun ChronicleMonthFlowCanvas(",
            "private fun ChroniclePeakDot(",
            "private fun foldChronicleMonthCounts(",
        )) {
            assertTrue("编年史面板新件 $fn 必须在场（R381b 时间脊柱重构）", chs.contains(fn))
        }
    }

    @Test
    fun `旧年块墙退役绝迹锚（方格墙与穿越文字钮）`() {
        val chs = chs()
        assertTrue(
            "ChronicleYearRow 应整件绝迹（R381b 年块灰卡行退役——LP2）",
            !chs.contains("ChronicleYearRow"),
        )
        assertTrue(
            "ChronicleHeatWall 应整件绝迹（R381b GitHub 贡献图式 53x7 方格墙退役——LP1）",
            !chs.contains("ChronicleHeatWall"),
        )
        // LP3：「穿越」逐行重复文字钮退役（整卡可点已是按钮）
        assertTrue(
            "「穿越中/穿越」逐行文字钮应绝迹（LP3）",
            !chs.contains("if (busy) \"穿越中…\" else \"穿越\""),
        )
    }

    @Test
    fun `月度光流带结构锚（12月桶与quadratic轮廓与连续归一）`() {
        val chs = chs()
        assertTrue(
            "月桶折算应引用 CHRONICLE_FLOW_MONTHS 常量（单源）",
            chs.contains("IntArray(CHRONICLE_FLOW_MONTHS)"),
        )
        assertTrue("光流轮廓应为 quadratic 平滑连线", chs.contains("path.quadraticTo("))
        assertTrue(
            "列高连续归一（frac 线性映射非档位——诚实量级）",
            chs.contains("monthCounts[i].toFloat() / yearMax"),
        )
        assertTrue(
            "stagger 步进必须引用 MotionTokens.SheetStaggerStepMs（R173 纪律：不自造节律）",
            chs.contains("MotionTokens.SheetStaggerStepMs"),
        )
        assertTrue(
            "进场揭示走 MutableTransitionState 一次性转场（stagger 只发生在进场）",
            chs.contains("MutableTransitionState(false).apply { targetState = true }"),
        )
        assertTrue(
            "月桶折算是呈现层派生（数据侧零动——foldChronicleMonthCounts）",
            chs.contains("foldChronicleMonthCounts(dayCells)"),
        )
    }

    @Test
    fun `脊柱节点形态锚（暗节点与色温渐变与呼吸族）`() {
        val node = funBody(chs(), "private fun ChronicleSpineNode(")
        assertTrue(
            "覆盖外年块=暗节点 α0.4（设计 §2.2 定案 2）",
            node.contains("if (covered) 1f else 0.4f"),
        )
        assertTrue(
            "脊柱段线应走 era→eraNext 纵向渐变（沿卡序=年代色温谱流动）",
            node.contains("Brush.verticalGradient(listOf(era, eraNext))"),
        )
        assertTrue(
            "节点圆心纵向位应按名引用 ChronicleSpineNodeDotY",
            node.contains("ChronicleSpineNodeDotY.toPx()"),
        )
        assertTrue(
            "busy 呼吸=穿越行同族 700ms 连续指示器（非转场；本文件在 MotionTokensPolicyTest 豁免清单）",
            node.contains("tween(700, easing = LinearEasing)"),
        )
        val card = funBody(chs(), "private fun ChronicleEraCard(")
        assertTrue(
            "stagger 步进公式=封顶段数×SheetStaggerStepMs（R173 不自造节律）",
            card.contains("index.coerceAtMost(CHRONICLE_SPINE_STAGGER_CAP) * MotionTokens.SheetStaggerStepMs"),
        )
    }

    @Test
    fun `月桶折算口径锚（呈现层派生UTC月）`() {
        val fold = funBody(chs(), "private fun foldChronicleMonthCounts(")
        assertTrue(
            "月桶折算应为呈现层私有纯函数（数据侧零动——不进 ChannelChronicle）",
            fold.contains("dayCells: List<Pair<Int, Int>>"),
        )
        assertTrue(
            "月桶口径=UTC 月（与数据侧 UTC 日 epoch 同族）",
            fold.contains("java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(\"UTC\"))"),
        )
        assertTrue(
            "月索引走 Calendar.MONTH（非手写 30 天折算）",
            fold.contains("cal.get(java.util.Calendar.MONTH)"),
        )
    }

    // ==================== ② 六卷卷宗卡层次在场源锚（TasteMapScreen） ====================

    @Test
    fun `卷头卡化锚（卡壳发丝描边与chevron图标）`() {
        val header = funBody(tms(), "private fun ChronicleVolumeHeader(")
        assertTrue("卷头卡化：SurfaceHigh 卡底必须在场（LP6）", header.contains("TgFeedTokens.SurfaceHigh"))
        assertTrue(
            "卷头卡化：0.5dp OverlayHairline 发丝描边必须在场（StarCardFoldHeader 同族）",
            header.contains("0.5.dp") && header.contains("OverlayHairline"),
        )
        assertTrue("卷头卡化：chevron 图标（Icons.Outlined.ExpandMore）必须在场", header.contains("Icons.Outlined.ExpandMore"))
        assertTrue(
            "LP9 修复：chevron 旋转应答（animateFloatAsState + AnimFast）必须在场",
            header.contains("animateFloatAsState") && header.contains("TgFeedTokens.AnimFast"),
        )
        assertTrue(
            "LP6 修复：「文本 glyph 箭头」必须绝迹于卷头",
            !header.contains("▾") && !header.contains("▸"),
        )
        assertTrue(
            "密度红线：卷头恒 1 行（折叠头行数不增）",
            header.contains("labelMedium"),
        )
    }

    @Test
    fun `卷身档案纸面锚（左缩进纸底与装订线）`() {
        val fold = funBody(tms(), "private fun VolumeFold(")
        assertTrue("卷身纸面：左缩进 ChroniclePaperInset 必须在场（LP7）", fold.contains("ChroniclePaperInset"))
        assertTrue(
            "卷身纸面：Surface α0.35 纸底必须在场",
            fold.contains("TgFeedTokens.Surface.copy(alpha = 0.35f)"),
        )
        assertTrue("卷身纸面：1dp 装订发丝线（TextLow）必须在场", fold.contains("TgFeedTokens.TextLow"))
        assertTrue(
            "卷身折叠原动画语言保留（expandVertically + AnimMed）",
            fold.contains("expandVertically(tween(TgFeedTokens.AnimMed))"),
        )
    }

    @Test
    fun `律动卷连续光流锚（消费位替换且本体保留）`() {
        val tms = tms()
        val volumes = funBody(tms, "private fun StarCardChronicleVolumes(")
        assertTrue(
            "②律动卷应消费 ChronicleRhythmFlowCanvas（LP8 正治）",
            volumes.contains("ChronicleRhythmFlowCanvas(chron.monthly)"),
        )
        assertTrue(
            "②律动卷内 MonthlyBarsCanvas 消费应绝迹（LP8 消费位替换）",
            !volumes.contains("MonthlyBarsCanvas"),
        )
        assertTrue(
            "MonthlyBarsCanvas 本体必须保留（R301 世界卡消费位写域外零碰）",
            tms.contains("private fun MonthlyBarsCanvas("),
        )
        assertTrue(
            "R301 世界卡 MonthlyBarsCanvas 消费必须保活",
            tms.contains("MonthlyBarsCanvas(eraNow.monthly)"),
        )
    }

    @Test
    fun `里程碑小脊柱锚（迷你时间轴竖线与节点串）`() {
        val volumes = funBody(tms(), "private fun StarCardChronicleVolumes(")
        assertTrue("④里程碑小脊柱：drawBehind 竖线必须在场", volumes.contains("drawBehind"))
        assertTrue(
            "④里程碑小脊柱：ChronicleMiniSpineX 单源常量引用必须在场",
            volumes.contains("ChronicleMiniSpineX.toPx()"),
        )
        assertTrue(
            "④里程碑行内容保留（dateLabelOf 原样）",
            volumes.contains("ChannelEraPolicy.dateLabelOf(row.ms.dateSec)"),
        )
        assertTrue(
            "④里程碑节点色语义保留（创刊金标判词）",
            volumes.contains("if (row.ms.label == \"创刊\") TgFeedTokens.StarYellow else TgFeedTokens.TgBlue"),
        )
    }

    @Test
    fun `卷头应答与卷宗几何消费锚（chevron旋转与装订线）`() {
        val header = funBody(tms(), "private fun ChronicleVolumeHeader(")
        assertTrue(
            "chevron 旋转目标：收起 0° → 展开 180°（LP9 卷头应答形态）",
            header.contains("if (open) 180f else 0f"),
        )
        assertTrue(
            "chevron 旋转走 graphicsLayer rotationZ（Transform 层零重组）",
            header.contains("rotationZ = chevronRotation"),
        )
        val volumes = funBody(tms(), "private fun StarCardChronicleVolumes(")
        assertTrue(
            "④里程碑小脊柱线宽=1dp 发丝（strokeWidth 消费）",
            volumes.contains("strokeWidth = 1.dp.toPx()"),
        )
        assertTrue(
            "②律动光流画布高应按名引用 ChronicleRhythmFlowHeight",
            tms().contains(".height(ChronicleRhythmFlowHeight)"),
        )
    }

    // ==================== ③ 数据口零改负锚 ====================

    @Test
    fun `数据口零改负锚（三数据件签名原样与呈现只读）`() {
        val cc = chronicle()
        for (anchor in listOf(
            "data class YearBlock(",
            "val dayCells: List<Pair<Int, Int>>",
            "val peakDaySec: Int",
            "data class Chronicle(",
            "fun heatAlpha(count: Int, globalMax: Int): Float",
            "const val CACHE_TTL_MS = 6L * 3600_000",
            "const val MAX_YEAR_BLOCKS = 12",
        )) {
            assertTrue("ChannelChronicle 数据口零改锚必须原样在场：$anchor", cc.contains(anchor))
        }
        val fcp = chroniclePart()
        for (anchor in listOf(
            "const val FIRST_MONTHS_CAP = 12",
            "const val EXTENDED_MONTHS_CAP = 24",
            "internal data class ChronicleResult(",
            "internal suspend fun FeedViewModel.channelChronicleImpl(chatId: Long, monthsCap: Int): ChronicleResult?",
        )) {
            assertTrue("FeedChroniclePart 数据口零改锚必须原样在场：$anchor", fcp.contains(anchor))
        }
        assertTrue(
            "TasteRadar 主构造签名必须原样（写域外零碰）",
            tasteRadar().contains("class TasteRadar("),
        )
        // 呈现文件零数据侧调用（负锚：折算/缓存口绝迹——呈现层只读）
        for (ui in listOf(chs(), tms())) {
            assertTrue("呈现文件不得调用 ChannelChronicle.of（数据折算口）", !ui.contains("ChannelChronicle.of"))
            assertTrue("呈现文件不得调用 ChannelChronicle.put（数据缓存口）", !ui.contains("ChannelChronicle.put"))
            assertTrue("呈现文件不得调用 ChannelChronicle.cached（缓存读口——网关侧）", !ui.contains("ChannelChronicle.cached"))
        }
    }

    // ==================== ④ 判词行保留锚 + R140 机器保留锚 ====================

    @Test
    fun `判词行保留锚（编年史北极星零改）`() {
        val tms = tms()
        assertTrue("判词行北极星注释必须在场", tms.contains("判词行（编年史北极星"))
        assertTrue("判词行 verdict 消费链必须原样", tms.contains("chron?.verdict != null -> chron.verdict"))
        assertTrue("判词行载入态文案必须原样", tms.contains("\"档案判读中…\""))
        assertTrue("薄档案诚实态文案必须原样", tms.contains("档案很薄——深度索引还没爬完这个频道"))
    }

    @Test
    fun `R140穿越机器全套保留锚（面板壳与过场与扩展行）`() {
        val chs = chs()
        for (anchor in listOf(
            "AppSheetMotion(onDismiss = onDismiss, title = \"频道编年史\", anchor = SheetAnchor.LEFT)",
            "private fun ArchaeoTravelStage(",
            "private fun ArchaeoLandedStage(",
            "private fun ArchaeoFailedStage(",
            "private fun ArchaeoRandomCard(",
            "private fun ChronicleExtendRow(",
            "private fun ArchaeoEraCard(",
            "if (!chronicle.atMaxDepth)",
            "onDigEpoch(block.yearsAgo, block.peakDaySec)",
            "ChannelChronicle.summaryLine(c)",
        )) {
            assertTrue("R140 机器保留锚必须在场：$anchor", chs.contains(anchor))
        }
    }

    // ==================== ⑤ 关键常量单源锚 ====================

    @Test
    fun `关键常量单源锚（脊柱几何与光流桶数）`() {
        val chs = chs()
        assertSingleDeclaration(chs, "private val ChronicleSpineSlotWidth = 20\\.dp", "脊柱槽宽")
        assertSingleDeclaration(chs, "private val ChronicleSpineLineWidth = 2\\.dp", "脊柱线宽")
        assertSingleDeclaration(chs, "private val ChronicleSpineNodeDiameter = 8\\.dp", "脊柱节点直径")
        assertSingleDeclaration(chs, "private val ChronicleSpineNodeDotY = 24\\.dp", "节点纵向位")
        assertSingleDeclaration(chs, "private const val CHRONICLE_FLOW_MONTHS = 12", "月度光流桶数")
        assertSingleDeclaration(chs, "private const val CHRONICLE_SPINE_STAGGER_CAP = 6", "stagger 封顶段数")
        assertSingleDeclaration(chs, "private val ChronicleFlowBandHeight = 40\\.dp", "光流带高")
        assertSingleDeclaration(chs, "private val ChroniclePeakDotDiameter = 6\\.dp", "峰日金标点径")
        assertSingleDeclaration(chs, "private val ChroniclePeakDotHalo = 2\\.dp", "峰日金标紧晕")
        // 消费按名（不散落字面量）
        assertTrue("节点绘制应按名引用 ChronicleSpineNodeDiameter", chs.contains("ChronicleSpineNodeDiameter.toPx()"))
        assertTrue("脊柱线绘制应按名引用 ChronicleSpineLineWidth", chs.contains("ChronicleSpineLineWidth.toPx()"))
        assertTrue(
            "峰日金标紧晕口径应按名引用（点半径+晕外扩）",
            chs.contains("ChroniclePeakDotDiameter.toPx() / 2f + ChroniclePeakDotHalo.toPx()"),
        )

        val tms = tms()
        assertSingleDeclaration(tms, "private val ChroniclePaperInset = 8\\.dp", "卷身纸面缩进")
        assertSingleDeclaration(tms, "private val ChronicleMiniSpineX = 7\\.dp", "里程碑小脊柱线位")
        assertSingleDeclaration(tms, "private val ChronicleRhythmFlowHeight = 52\\.dp", "律动光流画布高")
    }

    // ==================== ⑥ 宪法色源锚（零新色源） ====================

    @Test
    fun `宪法色源锚（年代色族与既有token零新色）`() {
        val chs = chs()
        for (sig in listOf(
            "private fun ChronicleSpineColumn(",
            "private fun ChronicleEraCard(",
            "private fun ChronicleSpineNode(",
            "private fun ChronicleMonthFlowCanvas(",
            "private fun ChroniclePeakDot(",
        )) {
            val body = funBody(chs, sig)
            assertTrue(
                "新件 $sig 不得自造色字面量（宪法第一章：色源全既有——年代色族/StarYellow/era）",
                !body.contains("Color(0x"),
            )
        }
        assertTrue(
            "年代色族单源 archaeoEraColor 必须在场（R140 原色族沿用）",
            chs.contains("private fun archaeoEraColor(yearsAgo: Double): Color"),
        )
        val tms = tms()
        // R416 起 ChronicleRhythmFlowCanvas 可见性 private→internal（StarChronicleDossier
        // 六卷卷宗跨文件消费位——非自造色源判词不受可见性影响）
        for (sig in listOf(
            "private fun ChronicleVolumeHeader(",
            "private fun VolumeFold(",
            "internal fun ChronicleRhythmFlowCanvas(",
        )) {
            val body = funBody(tms, sig)
            assertTrue(
                "卷宗新件 $sig 不得自造色字面量（色源=既有 token）",
                !body.contains("Color(0x"),
            )
        }
    }
}
