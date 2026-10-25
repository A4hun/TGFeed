package dev.tgfeed.ui

import dev.tgfeed.ui.BackdropPolicy.BackdropReportAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R160 加载态一次成型：底衬策略单测（纯函数全枚举）。
 * 用户实测 P0"加载态分层登场"的修复口径：
 * - 换主时机从落定（settledPage）提前到翻页过半（currentPage 翻转）；
 * - 滚动期间不再撤黑（黑竖条根因）；
 * - 未落定页不再强制黑底（黑盒根因）；
 * - 二修：上报/采纳双收窄到仅当前页，离开中旧页的重发恒被挡（反向翻页夺回竞态）；
 * - ZOOM 填满（竖屏抖音式）行为零变化（回归红线）。
 * - R336 图片档底衬退役（A 方案）：图片项上报恒拒收（含图集翻页重报）+ 在场清场
 *   模式（SOFT/THROUGH_DARK 双态）——视频/音频口径逐字节不变（回归红线⑧）。
 */
class BackdropPolicyTest {

    // ---------------- ① 采纳守卫：只认当前页（过半） ----------------

    @Test
    fun `采纳_落定页即当前页恒采纳`() {
        assertTrue(BackdropPolicy.shouldAdoptBackdrop(page = 3, currentPage = 3))
    }

    @Test
    fun `采纳_拖拽过半未落定时当前页即采纳`() {
        // 翻页中：currentPage 已翻到新页 4——新页上报即生效，不等落定
        assertTrue(BackdropPolicy.shouldAdoptBackdrop(page = 4, currentPage = 4))
    }

    @Test
    fun `采纳_离开中旧页的重发被挡_反向翻页夺回竞态根治`() {
        // 反向翻页过半：currentPage 已走、settledPage 尚在旧页 3——旧页
        // （isActive 尚真触发的重发）既非当前页，上报恒被挡，不夺回底衬
        assertFalse(BackdropPolicy.shouldAdoptBackdrop(page = 3, currentPage = 4))
    }

    @Test
    fun `采纳_快甩途中已离开的中间页上报被挡`() {
        // 连翻：currentPage 冲到 5，中间页 4 的迟发上报被挡，终态归属落定页
        assertFalse(BackdropPolicy.shouldAdoptBackdrop(page = 4, currentPage = 5))
        assertTrue(BackdropPolicy.shouldAdoptBackdrop(page = 5, currentPage = 5))
    }

    @Test
    fun `采纳_邻页预排版上报被挡`() {
        // 预排版邻页（未成为当前页）不上报不采纳，不干扰外层底衬归属
        assertFalse(BackdropPolicy.shouldAdoptBackdrop(page = 6, currentPage = 3))
    }

    // ---------------- ② 上报动作矩阵 ----------------

    @Test
    fun `上报_翻页过半未落定即换主`() {
        // 修复核心：新页进场瞬间（isCurrent 已翻 true、isActive 还 false）就上报换主
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = false, rotatedOdd = false, hasFrame = true) == BackdropReportAction.REPORT,
        )
    }

    @Test
    fun `上报_离开中的页静默null_守卫挡掉不清屏`() {
        // 二修：离开中的页（一修版 isActive 尚真会重发旧封面夺回底衬）改报
        // QUIET_NULL——外层 currentPage 守卫恒挡，底衬保留新页的图
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = false, useZoom = false, rotatedOdd = false, hasFrame = true) == BackdropReportAction.QUIET_NULL,
        )
    }

    @Test
    fun `上报_邻页预排版静默null`() {
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = false, useZoom = false, rotatedOdd = false, hasFrame = false) == BackdropReportAction.QUIET_NULL,
        )
    }

    @Test
    fun `上报_当前页暂无封面不报_保留旧底衬不黑窗`() {
        // 新页封面未到（cover=null）：跳过上报——外层保留上一条底衬（模糊近似色），
        // 不许清成黑窗；封面到了（cover 键变化）effect 重跑、同帧换主
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = false, rotatedOdd = false, hasFrame = false) == BackdropReportAction.SKIP_KEEP_OLD,
        )
    }

    @Test
    fun `上报_ZOOM填满模式也上报底衬`() {
        // 2026-10-17 竖屏 ZOOM 黑底退役：ZOOM 也上报（视频本体盖列区不影响，
        // 列外/信息区透出模糊镜像——用户实锤竖屏没背景图）
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = true, rotatedOdd = false, hasFrame = true) == BackdropReportAction.REPORT,
        )
    }

    @Test
    fun `上报_手动旋转90度显式清屏`() {
        // 画面层等比放大盖满全屏（口径不变）
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = false, rotatedOdd = true, hasFrame = true) == BackdropReportAction.CLEAR_NULL,
        )
    }

    // ---------------- ③ 页背景：未落定不再黑盒 ----------------

    @Test
    fun `页背景_ZOOM填满也透明`() {
        // 2026-10-17 竖屏 ZOOM 黑底退役：一律透明（用户实锤竖屏没背景图）
        assertFalse(BackdropPolicy.isVideoPageBackgroundOpaque(useZoom = true))
    }

    @Test
    fun `页背景_FIT模式透明_含未落定`() {
        // 横屏 FIT：落定与否都透明（外层底衬透上来；旧 !isActive 黑底制造黑竖条，已删）
        assertFalse(BackdropPolicy.isVideoPageBackgroundOpaque(useZoom = false))
    }

    // ---------------- ④ 换主 vs 换帧：snap 渐变分野 ----------------

    @Test
    fun `换主判定_跨条目snap`() {
        assertTrue(BackdropPolicy.isBackdropOwnerSwitch(ownerKey = "a", itemKey = "b"))
        assertTrue(BackdropPolicy.isBackdropOwnerSwitch(ownerKey = null, itemKey = "b"))
    }

    @Test
    fun `换主判定_同条目live帧走渐变`() {
        assertFalse(BackdropPolicy.isBackdropOwnerSwitch(ownerKey = "b", itemKey = "b"))
    }

    // ---------------- ⑤ R159b 起播一拍：snap 判定并入 live 制度首拍 ----------------

    @Test
    fun `snap判定_跨条目换主同帧切换`() {
        // R160 口径回归红线：换主即 snap（与列内封面占位同帧同图登场）
        assertTrue(BackdropPolicy.isBackdropSnapSwitch(ownerSwitch = true, liveRegimeStart = false))
    }

    @Test
    fun `snap判定_live制度首拍同帧切换`() {
        // R159b 修复核心：本条封面底衬首次换成 R89 live 实帧的那一拍与首帧上屏
        // 同拍合并——不许背景换血自成一段可见变化（修前像素实锤：verify4
        // 3.188s 起播同帧外带 -37 阶跃 + 300ms 异步溶解 + 外右带永久 -27）
        assertTrue(BackdropPolicy.isBackdropSnapSwitch(ownerSwitch = false, liveRegimeStart = true))
    }

    @Test
    fun `snap判定_同条目live常规跟随走渐变`() {
        // R89 口径回归红线：live 制度开启后的常规换帧维持 300ms 短渐变（~500ms 一拍）
        assertFalse(BackdropPolicy.isBackdropSnapSwitch(ownerSwitch = false, liveRegimeStart = false))
    }

    @Test
    fun `snap判定_换主与live首拍同拍仍snap`() {
        // 翻回仍在播的缓存页：换主上报直接带 live 帧——snap 结论不因复合而变
        assertTrue(BackdropPolicy.isBackdropSnapSwitch(ownerSwitch = true, liveRegimeStart = true))
    }

    // ---------------- ⑥ R174b 自由缩放：缩小态（zoom<1）列 ----------------

    @Test
    fun `缩小态_ZOOM填满改报底衬_周围透底衬`() {
        // 填满页缩到 <1x：有可见留边，按 FIT 同口径上报（封面模糊底衬铺窗口）；
        // 回到 ≥1x 恢复 CLEAR_NULL（视频重新铺满、底衬不可见，行为零变化）
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = true, rotatedOdd = false, hasFrame = true, zoomedOut = true) == BackdropReportAction.REPORT,
        )
    }

    @Test
    fun `缩小态_ZOOM填满无帧不报_保留旧底衬`() {
        // 与 FIT 的 SKIP_KEEP_OLD 同口径：不许黑窗，外层保留上一条模糊近似色
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = true, rotatedOdd = false, hasFrame = false, zoomedOut = true) == BackdropReportAction.SKIP_KEEP_OLD,
        )
    }

    @Test
    fun `缩小态_离开中的页仍静默null_守卫优先`() {
        // 翻页竞争中缩小的旧页不上报不夺回（二修守卫先于一切列分支）
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = false, useZoom = true, rotatedOdd = false, hasFrame = true, zoomedOut = true) == BackdropReportAction.QUIET_NULL,
        )
    }

    @Test
    fun `缩小态_手动旋转维持清屏口径_挂账成文`() {
        // rotatedOdd 自带列内满幅底衬（keepPlaceholderBackdrop），无外层底衬通道；
        // 缩小留边走列内底衬/黑底，不并本轮——矩阵恒 CLEAR_NULL，口径成文防误改
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = false, rotatedOdd = true, hasFrame = true, zoomedOut = true) == BackdropReportAction.CLEAR_NULL,
        )
    }

    @Test
    fun `缩小态_页背景透明`() {
        // 填满页缩到 <1x：页根改透明（留边区透外层底衬；跨沿瞬间留边面积≈0 无跳变）
        assertFalse(BackdropPolicy.isVideoPageBackgroundOpaque(useZoom = true, zoomedOut = true))
    }

    @Test
    fun `缩小态_默认参数回归_常态口径零变化`() {
        // 2026-10-17 竖屏 ZOOM 黑底退役后：常态（非 zoomedOut）ZOOM 也透明+上报
        assertFalse(BackdropPolicy.isVideoPageBackgroundOpaque(useZoom = true))
        assertTrue(
            BackdropPolicy.backdropReportAction(isCurrent = true, useZoom = true, rotatedOdd = false, hasFrame = true) == BackdropReportAction.REPORT,
        )
    }

    // ---------------- ⑦ R194①② 轨道切换冻结窗 + 渲染降采样 ----------------

    @Test
    fun `冻结窗_常数锚_500ms_覆盖转场与恢复窗`() {
        // 320ms 内容域转场 + resumeScroll 回滚/封面解码典型窗——窗长单一来源
        assertEquals(500L, BackdropPolicy.TRACK_SWITCH_FREEZE_MS)
    }

    @Test
    fun `降采样_短边不超目标_倍率1_不升采样`() {
        // 小屏/短边 ≤144px：倍率 1（原口径渲染，零变化）
        assertEquals(1f, BackdropPolicy.backdropDownsampleFactor(144f), 1e-6f)
        assertEquals(1f, BackdropPolicy.backdropDownsampleFactor(100f), 1e-6f)
        // 非正防御
        assertEquals(1f, BackdropPolicy.backdropDownsampleFactor(0f), 1e-6f)
    }

    @Test
    fun `降采样_大屏短边按目标折算_1080归75`() {
        // 1080px 短边（手机全屏档）：倍率 7.5——模糊像素量 ÷56.25（平方收益）
        assertEquals(7.5f, BackdropPolicy.backdropDownsampleFactor(1080f), 1e-6f)
        // 平板 2032px 短边：14.08…
        assertEquals(2032f / 144f, BackdropPolicy.backdropDownsampleFactor(2032f), 1e-4f)
    }

    @Test
    fun `降采样_目标短边常数锚_近似源图原生分辨率`() {
        // 源图短边 ~120px：渲染目标 144px ≈ 原生（零细节损失）；常数防漂移
        assertEquals(144, BackdropPolicy.BACKDROP_RENDER_SHORT_EDGE_PX)
    }

    // ---------------- ⑧ R336 图片档底衬退役：收养门媒体类型规则 + 清场模式 ----------------

    @Test
    fun `收养_图片项当前页上报恒拒_双绘露馅根治`() {
        // R336 A 方案核心：图片卡 Fit 留边=外层全屏模糊底衬与卡内清晰图两层皮
        //（华为平板 56% 列宽下 44% 屏幕在放模糊底图实锤）——图片项上报一律不
        // 收养（含图集翻页重报——同为图片项上报，同口径拒收）
        assertFalse(BackdropPolicy.shouldAdoptBackdropForMedia(page = 3, currentPage = 3, mediaType = 1))
    }

    @Test
    fun `收养_图片项离开中旧页上报同拒_页守卫先行`() {
        // 页守卫（R160 二修口径）先行挡掉：图片档页守卫+类型守卫双保险矩阵成文
        assertFalse(BackdropPolicy.shouldAdoptBackdropForMedia(page = 3, currentPage = 4, mediaType = 1))
        assertFalse(BackdropPolicy.shouldAdoptBackdropForMedia(page = 4, currentPage = 3, mediaType = 1))
    }

    @Test
    fun `收养_视频项口径逐字节不变_回归红线`() {
        // R194 主功能红线：视频项（mediaType=0）下新守卫与旧守卫逐字节同值
        assertTrue(BackdropPolicy.shouldAdoptBackdropForMedia(page = 3, currentPage = 3, mediaType = 0))
        // 旧页/邻页照旧被挡（页守卫语义原样）
        assertFalse(BackdropPolicy.shouldAdoptBackdropForMedia(page = 2, currentPage = 3, mediaType = 0))
        assertFalse(BackdropPolicy.shouldAdoptBackdropForMedia(page = 4, currentPage = 3, mediaType = 0))
    }

    @Test
    fun `收养_音频项照旧通过_实勘不走上报链`() {
        // AudioCard 实勘（r336_notes §1）：零 onBackdropChange——背景图=卡内自绘
        // 满幅模糊层，不进 feed 底衬链。本规则对音频恒真通过：即便未来接上报口
        // 也不受 R336 影响（只动图片档）
        assertTrue(BackdropPolicy.shouldAdoptBackdropForMedia(page = 5, currentPage = 5, mediaType = 2))
    }

    @Test
    fun `收养_新旧守卫全档等价锚_防未来单边漂移`() {
        // 等价性锚（全档枚举）：mediaType!=1 时两函数恒同值——将来只改一处必红
        for (mt in intArrayOf(0, 2)) {
            for (page in intArrayOf(1, 3, 4)) {
                for (cur in intArrayOf(1, 3, 4)) {
                    assertEquals(
                        BackdropPolicy.shouldAdoptBackdrop(page, cur),
                        BackdropPolicy.shouldAdoptBackdropForMedia(page, cur, mt),
                    )
                }
            }
        }
        // 图片档：即便页守卫过门也拒（新守卫独有语义——含 page==currentPage 满门位）
        for (page in intArrayOf(1, 3, 4)) {
            for (cur in intArrayOf(1, 3, 4)) {
                assertFalse(BackdropPolicy.shouldAdoptBackdropForMedia(page, cur, 1))
            }
        }
    }

    @Test
    fun `清场模式_常态翻页走SOFT_软退场至纯深色`() {
        // 图片项在场清场（FeedScreen R336 观察者驱动）：常态翻页=SOFT——
        // BackdropStackFade 对 null 目标整层 exitSpec 软退场到 0，露根 Box 纯黑
        assertEquals(
            dev.tgfeed.ui.components.BackdropTransition.SOFT,
            BackdropPolicy.photoBackdropClearTransition(discretePending = false),
        )
    }

    @Test
    fun `清场模式_离散轨道切换窗收口走THROUGH_DARK_与视频腿同语义`() {
        // 切 tab/换列后的第一拍（discretePending）：暗场溶解——旧图暗下、到位即
        // 图片档终态；与视频腿「窗收口暗场溶解」同语义（零新动画体系）
        assertEquals(
            dev.tgfeed.ui.components.BackdropTransition.THROUGH_DARK,
            BackdropPolicy.photoBackdropClearTransition(discretePending = true),
        )
    }
}
