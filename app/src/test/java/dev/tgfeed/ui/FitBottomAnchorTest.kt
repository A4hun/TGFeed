package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R194⑥ 悬停式顶锚纯函数单测（全矩阵枚举）。
 * R188c 的 fitBottomAnchorShiftPx（底锚）被架构师 17:10 布局终案取代删除——
 * 旧矩阵随函数退役（git 历史/r188_notes 可考），本套件只锚新口径。
 *
 * 口径红线（终案原文）：
 * - **两缘收紧**：内容带上缘平移到顶锚预留线（tab 底 + 小间距）、下缘不得
 *   侵入底缘悬停线（navbar+dock+小间距）；带高超出预算 → 底缘悬停优先，
 *   顶锚让位（顶部富余由 chrome 遮）；
 * - **富余进中**：预算富余全部落在带下缘↔底缘悬停线之间（自然留白）；
 * - **竖容器恒 0**（TikTok 居中惯例，R188 双口径零回归沿用）；
 * - 内容比/容器尺寸未知恒 0（加载态兜底=居中）；
 * - 高向适配（内容不比容器宽）恒 0——满高无垂直 letterbox，平移必裁画面
 *  （R50 裁切不可接受红线；布局级限高挂账 r194_notes）。
 */
class FitBottomAnchorTest {

    // ---------------- ① 顶锚与富余进中 ----------------

    @Test
    fun `悬停锚_宽内容顶锚到预留线_富余进中`() {
        // 干净数字：容器 2000×1000（比 2.0），内容比 4.0 → 内容高 500、居中上缘
        // 250；顶预留 150 → shift = 150−250 = −100（上移）；带 [150, 650]，
        // 底缘悬停线 850 → 富余 200 全在带底↔悬停线之间（自然留白）
        assertEquals(
            -100f,
            FitBottomAnchor.fitHoverAnchorShiftPx(2000, 1000, 4f, 150f, 150f),
            0f,
        )
    }

    @Test
    fun `悬停锚_用户平板3比2列_16比9顶锚_富余全部进中`() {
        // 终案现场：列 2804×2032 + 16:9（内容高 1577.3、居中上缘 227.3）；
        // 顶预留 = 顶栏底(157 实测档) + 12dp(27) = 184 → shift = 184−227.3 =
        // −43.3（上移）；带底 = 227.3+1577.3−43.3 = 1761.3；底悬停线 = 2032 −
        // (navbar 0 + dock 99 + 12dp 27) = 1906 → 富余 144.7 全在带底↔dock 间
        assertEquals(
            -43.3f,
            FitBottomAnchor.fitHoverAnchorShiftPx(2804, 2032, 16f / 9f, 184f, 126f),
            0.2f,
        )
    }

    @Test
    fun `悬停锚_几何自洽_带上缘落在预留线`() {
        // 平移量语义自检：带上缘 = 居中上缘 + shift = topReserve（顶锚语义）
        val w = 2804
        val h = 2032
        val ratio = 16f / 9f
        val shift = FitBottomAnchor.fitHoverAnchorShiftPx(w, h, ratio, 184f, 126f)
        val centeredTop = (h - w / ratio) / 2f
        assertEquals(184f, centeredTop + shift, 0.01f)
    }

    // ---------------- ② 带高超出预算：底缘悬停红线优先 ----------------

    @Test
    fun `悬停锚_带高超出预算_底缘悬停红线优先_顶锚让位`() {
        // 带高 900、容器 1200×1000、顶/底预留各 100：预算 800 < 900——顶锚 shift =
        // 100−50 = 50 会让带底到 1050 > H−100；封顶 shift = 居中上缘50 − 底
        // 预留100 = −50 → 带 [0, 900]：底缘恰在悬停线，顶部富余由 chrome 遮
        assertEquals(
            -50f,
            FitBottomAnchor.fitHoverAnchorShiftPx(1200, 1000, 1200f / 900f, 100f, 100f),
            0.01f,
        )
    }

    @Test
    fun `悬停锚_带底恒不侵入底缘悬停线`() {
        // 任意参数组合（预算内/外）带底 = 居中底 + shift ≤ H − bottomReserve
        for (ratio in listOf(1.4f, 1.6f, 16f / 9f, 2.35f, 3f)) {
            for (reserve in listOf(80f, 126f, 200f)) {
                val shift = FitBottomAnchor.fitHoverAnchorShiftPx(2804, 2032, ratio, 184f, reserve)
                val contentH = 2804f / ratio
                val centeredBottom = (2032f + contentH) / 2f
                assertTrueLike(centeredBottom + shift <= 2032f - reserve + 0.01f, "ratio=$ratio reserve=$reserve 带底越线")
            }
        }
    }

    private fun assertTrueLike(cond: Boolean, msg: String) {
        org.junit.Assert.assertTrue(msg, cond)
    }

    // ---------------- ③ 红线：竖容器/未知/高向适配/满高 恒 0 ----------------

    @Test
    fun `悬停锚_竖容器恒零_居中红线沿用`() {
        assertEquals(
            0f,
            FitBottomAnchor.fitHoverAnchorShiftPx(1080, 2340, 16f / 9f, 200f, 200f),
            0f,
        )
    }

    @Test
    fun `悬停锚_比未知与容器未知恒零`() {
        assertEquals(
            0f,
            FitBottomAnchor.fitHoverAnchorShiftPx(2000, 1000, 0f, 150f, 150f),
            0f,
        )
        assertEquals(
            0f,
            FitBottomAnchor.fitHoverAnchorShiftPx(0, 1440, 16f / 9f, 150f, 150f),
            0f,
        )
        assertEquals(
            0f,
            FitBottomAnchor.fitHoverAnchorShiftPx(2353, 0, 16f / 9f, 150f, 150f),
            0f,
        )
    }

    @Test
    fun `悬停锚_高向适配恒零_满高平移必裁画面挂账成文`() {
        // 4:3 内容进 1.667 横列：高向适配满高，左右留边——平移必裁画面（R50
        // 红线），布局级限高挂账（r194_notes），本函数恒 0
        assertEquals(
            0f,
            FitBottomAnchor.fitHoverAnchorShiftPx(1920, 1080, 4f / 3f, 150f, 150f),
            0f,
        )
        // 内容比恰等容器比（满高边界）：同口径 0
        assertEquals(
            0f,
            FitBottomAnchor.fitHoverAnchorShiftPx(2560, 1440, 16f / 9f, 150f, 150f),
            0f,
        )
    }

    // ---------------- ④ 预留语义边界 ----------------

    @Test
    fun `悬停锚_预留全零_带上缘平移到屏顶_字面语义锚`() {
        // 预留 0 的字面语义 = 顶锚到 0（贴屏顶）；调用方对全屏态（chrome 全退）
        // 直接门控 shift=0 居中（VideoPage fullscreen 门），不经本分支
        assertEquals(
            -250f,
            FitBottomAnchor.fitHoverAnchorShiftPx(2000, 1000, 4f, 0f, 0f),
            0f,
        )
    }
}
