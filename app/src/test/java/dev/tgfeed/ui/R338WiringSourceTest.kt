package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R338 图片界面横屏适配 wiring 源码锚（R325WiringSourceTest 同款 user.dir 源定位
 * 模式——组合侧接线无 JVM 直测面，以源锚锁结构；行为半体由
 * ContentAwareMaxWidthTest ⑥节（feedPhotoShapeAspect 门矩阵+满高形状）承重）：
 * - **D1 列宽门**：FeedScreen knownAspect 图片分支消费 [feedPhotoShapeAspect]
 *   （图片档不再恒 0f 档位宽）；
 * - **D1 负锚**：粘滞写值图片页保持档位宽（视频未知比的种子值逐字节现状——
 *   视频零变化红线）+ 视频两级链（videoAspectBoard 记账表）原样在场；
 * - **D2 底衬退役**：PhotoCard 既有 landscapeChrome 单门 → PhotoImageArea
 *   suppressBackdrop 参数（默认 false=竖屏/矮横屏现状）+ 底衬绘制 if(!) 门；
 * - **D5 分段条限宽**：widthIn(infoMaxWidth) 第二消费点（与信息区限宽同字段
 *   同款——widthIn 在 fillMaxWidth 之前的顺序教训两处一致）；
 * - **P2/P3 既有件在场**：右轨政策档字面量（R225 同刀）不回破；
 * - **政策单口**：contentAwareFeedMaxWidth 本体零改（唯一签名 1 次）+
 *   门函数唯一签名（零第二份宽度公式）。
 */
class R338WiringSourceTest {

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

    private fun read(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun feedScreen(): String = read("dev/tgfeed/ui/FeedScreen.kt")

    private fun photoCard(): String = read("dev/tgfeed/ui/components/PhotoCard.kt")

    private fun responsive(): String = read("dev/tgfeed/ui/Responsive.kt")

    // ==================== D1 列宽门（FeedScreen 图片分支） ====================

    @Test
    fun `D1列宽门-FeedScreen图片分支消费门函数`() {
        val fs = feedScreen()
        // 图片分支：门函数消费（非视频不再恒 0f——横图横屏同视频放宽）
        assertTrue(
            "FeedScreen knownAspect 图片分支必须消费 feedPhotoShapeAspect（R338 D1）",
            Regex("feedPhotoShapeAspect\\(").containsMatchIn(fs),
        )
        // 空条目防御参数形态（无条目 null→原链 0f）
        assertTrue(
            "门函数调用必须带无条目防御（shapeItem?.mediaType ?: -1）",
            Regex("shapeItem\\?\\.mediaType \\?: -1").containsMatchIn(fs),
        )
        // null 合流回原 0f 链（音频/无条目逐字节现状）
        assertTrue(
            "null 必须合流回 else 0f 原链（音频零介入）",
            Regex("\\) \\?: 0f").containsMatchIn(fs),
        )
    }

    @Test
    fun `D1负锚-粘滞写值图片页保持档位宽`() {
        val fs = feedScreen()
        // 图片页不进粘滞账：写值分支（视频腿种子逐字节现状——视频零变化红线）
        assertTrue(
            "粘滞写值必须按 mediaType 分流（图片页=档位宽旧值）",
            Regex("stickyTarget\\.value = if \\(shapeItem\\?\\.mediaType == 1\\)").containsMatchIn(fs),
        )
        assertTrue(
            "图片页粘滞写值必须取 feedContentMaxWidth 档位宽（与改前同值）",
            Regex("feedContentMaxWidth\\(ws, LocalConfiguration\\.current\\.screenWidthDp\\)")
                .containsMatchIn(fs),
        )
    }

    @Test
    fun `D1负锚-视频两级链原样在场`() {
        val fs = feedScreen()
        // 视频两级来源（元数据预判→ExoPlayer 实测校正）与 mediaType==0 门原样
        assertTrue(
            "视频两级链（videoAspectBoard 记账表消费）必须原样在场",
            Regex("videoAspectBoard\\[shapeItem\\.key\\]").containsMatchIn(fs),
        )
        assertTrue(
            "视频分支 mediaType == 0 门必须原样",
            Regex("shapeItem\\.mediaType == 0").containsMatchIn(fs),
        )
        // 实测/预判 15% 口径原样（R159b 校正链零碰）
        assertTrue(
            "实测-预判 15% 校正口径必须原样",
            Regex("/ predicted < 0\\.15f -> predicted").containsMatchIn(fs),
        )
    }

    // ==================== D2 卡内底衬横屏退役（PhotoCard） ====================

    @Test
    fun `D2底衬退役-suppressBackdrop参数与绘制门`() {
        val pc = photoCard()
        // 参数默认 false = 竖屏/矮横屏现状（零变化红线结构锚）
        assertTrue(
            "PhotoImageArea 必须带 suppressBackdrop: Boolean = false 默认参数",
            Regex("suppressBackdrop: Boolean = false").containsMatchIn(pc),
        )
        // 绘制门：if (!suppressBackdrop) 包底衬 Image
        assertTrue(
            "底衬绘制必须挂 if (!suppressBackdrop) 门",
            Regex("if \\(!suppressBackdrop\\) \\{").containsMatchIn(pc),
        )
        // 两个调用位（图集页/单图）都接 landscapeChrome 单门
        val wired = Regex("suppressBackdrop = landscapeChrome != null").findAll(pc).count()
        assertEquals(
            "suppressBackdrop 必须在图集/单图两个调用位都接线（实得 $wired 处）",
            2,
            wired,
        )
    }

    // ==================== D5 分段条限宽（第二消费点） ====================

    @Test
    fun `D5分段条限宽-infoMaxWidth第二消费点在场`() {
        val pc = photoCard()
        // 信息区（R225）+ 分段条（R338）同字段同款两处
        val caps = Regex("Modifier\\.widthIn\\(max = landscapeChrome\\.infoMaxWidth\\)")
            .findAll(pc).count()
        assertEquals(
            "widthIn(infoMaxWidth) 消费点应为 2（信息区 R225+分段条 R338；实得 $caps 处）",
            2,
            caps,
        )
        // 分段条 TopCenter 锚既有（限宽后仍居中）
        assertTrue(
            "分段条 TopCenter 锚必须在场",
            Regex("\\.align\\(Alignment\\.TopCenter\\)").containsMatchIn(pc),
        )
    }

    // ==================== P2/P3 既有件在场（R225 同刀不回破） ====================

    @Test
    fun `P2P3右轨既有接线-政策档字面量不回破`() {
        val pc = photoCard()
        for (anchor in listOf(
            "landscapeChrome\\?\\.railTouchMin \\?: 40\\.dp",
            "landscapeChrome\\?\\.railIconSize \\?: TgFeedTokens\\.RailIconSize",
            "landscapeChrome\\?\\.railGap \\?: TgFeedTokens\\.RailGap",
            "landscapeFeedChromeOrNull\\(",
        )) {
            assertTrue("右轨 R225 政策档接线锚丢失：$anchor", Regex(anchor).containsMatchIn(pc))
        }
    }

    // ==================== 政策单口（Responsive 零第二份） ====================

    @Test
    fun `政策单口-contentAware本体零改门函数唯一签名`() {
        val r = responsive()
        // contentAwareFeedMaxWidth 函数本体零改：唯一签名（无第二份宽度公式）
        assertEquals(
            "contentAwareFeedMaxWidth 签名必须唯一（本体零改）",
            1,
            Regex("fun contentAwareFeedMaxWidth\\(").findAll(r).count(),
        )
        // 新门函数唯一签名
        assertEquals(
            "feedPhotoShapeAspect 门函数签名必须唯一（政策单口）",
            1,
            Regex("fun feedPhotoShapeAspect\\(").findAll(r).count(),
        )
        // 门函数三段语义骨架在场（类型门/缺尺寸 0f/给比）
        assertTrue(
            "门函数类型门 mediaType != 1 必须在场",
            Regex("if \\(mediaType != 1\\) return null").containsMatchIn(r),
        )
        assertTrue(
            "门函数缺尺寸 0f 兜底必须在场",
            Regex("if \\(width <= 0 \\|\\| height <= 0\\) return 0f").containsMatchIn(r),
        )
    }
}
