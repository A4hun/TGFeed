package dev.tgfeed.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R159 内容感知列宽单测（纯函数全枚举）：
 *  - [feedContentMaxWidth]：四档档位规则与旧实现逐档一致（重构红线：纯拆不改行为）；
 *  - [contentAwareFeedMaxWidth]：横屏档+横屏内容放宽、其余全档位不变（回归红线）；
 *  - ⑥ [feedPhotoShapeAspect]（R338）：图片条目形态门矩阵——图片给比/缺尺寸 0f
 *    兜底/视频音频 null 零介入；图片满高策略形状（横图 92% 屏宽封顶=与视频同式、
 *    竖图/竖屏档恒档位宽=现状零变化）。
 *
 * 夹具基准：华为平板 2560×1600 横屏 = 1024dp×640dp（400dpi，density 2.5）——
 * 用户实测基线即此设备（56% 屏宽中栏，硬边 x=563/1997）。
 */
class ContentAwareMaxWidthTest {

    // ---------------- 夹具 ----------------

    /** 华为平板横屏：1024×640dp，旧档位宽 = 1024×0.56 = 573.44dp */
    private val hwW = 1024
    private val hwH = 640
    private val hwTier = (1024 * 0.56f).dp

    /** 16384 高分平板横屏（R225 验证夹具）：1219×812dp 落 A 档，档位宽=682.64dp */
    private val hiW = 1219
    private val hiH = 812

    /** 普通手机横屏：780×360dp，旧档位宽恒 600dp */
    private val phW = 780
    private val phH = 360

    private fun assertDp(expected: Dp, actual: Dp) {
        assertEquals("期望 $expected dp，实得 $actual", expected.value, actual.value, 0.01f)
    }

    // ---------------- ① 档位规则（纯拆重构，行为逐档不变） ----------------

    @Test
    fun `档位规则_手机竖屏铺满`() {
        assertDp(Dp.Infinity, feedContentMaxWidth(WindowSize.PhonePortrait, 400))
    }

    @Test
    fun `档位规则_手机横屏恒600dp`() {
        assertDp(600.dp, feedContentMaxWidth(WindowSize.PhoneLandscape, phW))
    }

    @Test
    fun `档位规则_平板竖屏按屏宽085封顶900`() {
        // 640dp 屏宽（华为平板竖转）×0.85=544dp，未触顶
        assertDp(544.dp, feedContentMaxWidth(WindowSize.TabletPortrait, 640))
        // 1200dp 屏宽×0.85=1020 → 触 900dp 顶
        assertDp(900.dp, feedContentMaxWidth(WindowSize.TabletPortrait, 1200))
    }

    @Test
    fun `档位规则_平板横屏按屏宽056封顶880`() {
        // 华为平板：1024×0.56=573.44dp（用户基线）
        assertDp(hwTier, feedContentMaxWidth(WindowSize.TabletLandscape, hwW))
        // 1800dp 屏宽×0.56=1008 → 触 880dp 顶
        assertDp(880.dp, feedContentMaxWidth(WindowSize.TabletLandscape, 1800))
    }

    // ---------------- ② 横屏档 + 横屏内容：放宽 ----------------

    @Test
    fun `华为平板横屏_16比9横片_放宽到92屏宽封顶`() {
        // min(640×1.7778=1137.8, 1024×0.92=942.08) = 942.08dp（屏高×比未触顶，屏宽封顶生效）
        // px 口径：942.08×2.5≈2355px，两侧各 ≈102px（4%），基线 573dp=1434px 对比近翻倍
        val w = contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 16f / 9f)
        assertDp((1024 * 0.92f).dp, w)
        assertTrue("放宽必须显著大于档位宽", w > hwTier)
    }

    @Test
    fun `华为平板横屏_超宽片235比1_仍92屏宽封顶`() {
        // min(640×2.35=1504, 942.08)：横片再宽也不超 92% 屏宽
        assertDp((1024 * 0.92f).dp, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 2.35f))
    }

    @Test
    fun `华为平板横屏_刚过阈值121_按屏高乘比放宽`() {
        // min(640×1.21=774.4, 942.08)=774.4dp（屏高×比生效，屏宽未触顶）
        assertDp(774.4.dp, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 1.21f))
    }

    @Test
    fun `手机横屏_16比9_放宽到屏高乘比`() {
        // min(360×1.7778=640, 780×0.92=717.6)=640dp > 600dp 档位 → 放宽生效
        val w = contentAwareFeedMaxWidth(WindowSize.PhoneLandscape, phW, phH, 16f / 9f)
        assertDp(640.dp, w)
        assertTrue(w > 600.dp)
    }

    // ---------------- ③ 回归红线：不放宽的形态维持档位宽 ----------------

    @Test
    fun `阈值边界_正好120不放宽`() {
        // 规则是"宽高比 > 1.2"（严格大于）：1.2 整不触发
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, CONTENT_WIDEN_ASPECT))
    }

    @Test
    fun `横屏档_竖屏片维持档位宽`() {
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 9f / 16f))
        assertDp(600.dp, contentAwareFeedMaxWidth(WindowSize.PhoneLandscape, phW, phH, 0.5625f))
    }

    @Test
    fun `横屏档_方片与比未知维持档位宽`() {
        // aspect=0 = 元数据缺失/未知：回落现有宽度（不许回归）
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 0f))
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 1f))
    }

    @Test
    fun `竖屏档_任何形态都不参与_手机竖屏恒铺满`() {
        // 竖屏档（手机/平板）零变化：即使横片也不放宽（竖屏满屏列是主消费形态）
        assertDp(Dp.Infinity, contentAwareFeedMaxWidth(WindowSize.PhonePortrait, 400, 800, 16f / 9f))
        assertDp(544.dp, contentAwareFeedMaxWidth(WindowSize.TabletPortrait, 640, 1024, 16f / 9f))
        assertDp(Dp.Infinity, contentAwareFeedMaxWidth(WindowSize.PhonePortrait, 400, 800, 0f))
    }

    // ---------------- ④ 防御兜底 ----------------

    @Test
    fun `窄横屏_放宽值低于档位宽时维持档位宽_只放宽不收窄`() {
        // 手机横屏 360dp 高 × 1.21 = 435.6dp < 600dp 档位：兜底取档位宽（防回归收窄）
        assertDp(600.dp, contentAwareFeedMaxWidth(WindowSize.PhoneLandscape, phW, phH, 1.21f))
    }

    @Test
    fun `尺寸异常_非正数不进放宽分支`() {
        // 屏高非正：放宽分支不进，回落档位宽（573.44dp）
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, 0, 16f / 9f))
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, -1, 16f / 9f))
        // 屏宽非正：档位规则本身同源退化（min(0×0.56,880)=0dp，旧规则既有口径），
        // 本函数不额外造宽度——断言"与档位宽一致"而非具体值
        assertDp(
            feedContentMaxWidth(WindowSize.TabletLandscape, 0),
            contentAwareFeedMaxWidth(WindowSize.TabletLandscape, 0, hwH, 16f / 9f),
        )
    }

    // ---------------- ⑤ 阈值口径 ----------------

    @Test
    fun `阈值常量_严格大于12才放宽`() {
        assertEquals(1.2f, CONTENT_WIDEN_ASPECT, 0f)
        assertDp(hwTier, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 1.19f))
        assertTrue(contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hwW, hwH, 1.21f) > hwTier)
    }

    // ---------------- ⑥ R338 图片形态门与满高形状（feedPhotoShapeAspect） ----------------

    @Test
    fun `图片形态门_类型矩阵_图片给比视频音频null零介入`() {
        // 视频（0）走既有两级预判/实测链：本门 null=调用方原链，零介入
        assertNull(feedPhotoShapeAspect(0, 1920, 1080))
        // 音频（2）恒档位宽：null → FeedScreen else 分支 0f 原链逐字节现状
        assertNull(feedPhotoShapeAspect(2, 500, 500))
        // 无条目（mediaType 未知）
        assertNull(feedPhotoShapeAspect(-1, 0, 0))
        // 图片（1）：元数据给比（取数链 PhotoSize 最大档宽高直写 VideoItem）
        assertEquals(16f / 9f, feedPhotoShapeAspect(1, 1920, 1080)!!, 0.0001f)
        assertEquals(0.75f, feedPhotoShapeAspect(1, 750, 1000)!!, 0.0001f)
    }

    @Test
    fun `图片形态门_元数据缺尺寸_0f档位宽兜底不许回归`() {
        // 0f = 形态已知不放宽（contentAware 0f → 档位宽）——与旧「图文→档位宽」同值
        assertEquals(0f, feedPhotoShapeAspect(1, 0, 0)!!, 0f)
        assertEquals(0f, feedPhotoShapeAspect(1, 1920, 0)!!, 0f)
        assertEquals(0f, feedPhotoShapeAspect(1, 0, 1080)!!, 0f)
        // 防御：非正尺寸一律 0f
        assertEquals(0f, feedPhotoShapeAspect(1, -5, -3)!!, 0f)
    }

    @Test
    fun `图片满高形状_16384横屏_横图92屏宽封顶竖图档位宽`() {
        // 16384 横屏 1219×812dp（A 档）：16:9 图放宽到 0.92×1219=1121.5dp
        //（屏高×比 1443.6 未触顶，屏宽封顶生效）——列内 Fit 满高 ≈631dp，
        // 与视频同款「按屏高满」语义（改前 682.6dp 列内只显 384dp 高=半屏小图）
        val w = contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hiW, hiH, 16f / 9f)
        assertDp((1219 * 0.92f).dp, w)
        assertTrue("横图放宽必须显著大于档位宽 682.6dp", w > (1219 * 0.56f).dp)
        // 竖图 3:4（比 ≤1.2）：档位宽——竖图在档位列内本已满高（812×0.75=609dp 宽）
        // 零新尺寸逻辑，两侧留深色边交 R338 D2 底衬退役档
        assertDp(
            (1219 * 0.56f).dp,
            contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hiW, hiH, 0.75f),
        )
        // 元数据缺失 0f：档位宽兜底（不许回归）
        assertDp((1219 * 0.56f).dp, contentAwareFeedMaxWidth(WindowSize.TabletLandscape, hiW, hiH, 0f))
    }

    @Test
    fun `图片满高形状_竖屏档_任何形态恒档位宽零变化`() {
        // 竖屏零变化负锚：手机竖屏铺满/平板竖屏 0.85w 帽——图片形态不参与
        //（竖屏满屏列是主消费形态，R159 既有红线在图片档继续成立）
        assertDp(Dp.Infinity, contentAwareFeedMaxWidth(WindowSize.PhonePortrait, 400, 800, 16f / 9f))
        assertDp(544.dp, contentAwareFeedMaxWidth(WindowSize.TabletPortrait, 640, 1024, 16f / 9f))
        assertDp(544.dp, contentAwareFeedMaxWidth(WindowSize.TabletPortrait, 640, 1024, 0.75f))
    }
}
