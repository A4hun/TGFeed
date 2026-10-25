package dev.tgfeed.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.tgfeed.ui.components.kit.TopLevelPageScrollGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R177/R181 手势消歧矩阵单测（design-proposal §2.2 成文矩阵的逐行枚举）。
 *
 * 矩阵行 ↔ 用例对照（行号见 TopLevelGesturePolicy.kt 文件头表）：
 * #1 双指→捏合域；#2 zoom>1→平移域；#3 进度条→seek；#4 图集内横滑→翻图；
 * #5 图集末页残余动量→级联门槛裁决（R181：过门槛且轨道在场→级联进轨道，否则吞）；
 * #6 竖滑→页内域（含轴锁）；#7 首页页位横滑→媒体轨道域（R181 主路），
 * 发现/我的页位→页面域；#8 清屏态横滑→视频域（顶层禁用）；#9 压顶关闸；
 * #10 时间线台前→横向手势就地终局+顶层用户横滑封闸（R374）。外加吞并与放行的数值口径。
 */
class TopLevelGesturePolicyTest {

    private fun input(
        pointerCount: Int = 1,
        zoomScale: Float = 1f,
        onSeekBar: Boolean = false,
        inGallery: Boolean = false,
        galleryAtBoundary: Boolean = false,
        chromeHidden: Boolean = false,
        horizontalFirst: Boolean = false,
        verticalFirst: Boolean = false,
        railActive: Boolean = false,
        spillFlingIntent: Boolean = false,
    ) = GestureDisambiguationInput(
        pointerCount, zoomScale, onSeekBar, inGallery,
        galleryAtBoundary, chromeHidden, horizontalFirst, verticalFirst,
        railActive, spillFlingIntent,
    )

    // ---------------- #1 双指：捏合域永远最先（Initial pass 执法） ----------------

    @Test
    fun `双指落下归捏合域_即使落在图集或进度条上`() {
        assertEquals(GestureDomain.PINCH, resolveGestureDomain(input(pointerCount = 2)))
        assertEquals(
            GestureDomain.PINCH,
            resolveGestureDomain(input(pointerCount = 2, inGallery = true, horizontalFirst = true)),
        )
        assertEquals(
            GestureDomain.PINCH,
            resolveGestureDomain(input(pointerCount = 3, onSeekBar = true)),
        )
    }

    // ---------------- #2 缩放态平移：手势归内容本体 ----------------

    @Test
    fun `缩放态单指拖动归平移域_翻页切页都不得抢`() {
        assertEquals(GestureDomain.OBJECT_PAN, resolveGestureDomain(input(zoomScale = 1.4f, verticalFirst = true)))
        assertEquals(
            GestureDomain.OBJECT_PAN,
            resolveGestureDomain(input(zoomScale = 2f, inGallery = true, horizontalFirst = true)),
        )
    }

    @Test
    fun `清屏态且已放大_横滑仍归平移域_先于清屏禁横滑判定`() {
        // pinchZoomTracking 接管条件 twoFinger || zoom>1 的矩阵映射：zoom>1 恒归对象域
        assertEquals(
            GestureDomain.OBJECT_PAN,
            resolveGestureDomain(input(zoomScale = 1.3f, chromeHidden = true, horizontalFirst = true)),
        )
    }

    // ---------------- #3 进度条：seek 自管 ----------------

    @Test
    fun `进度条热区归seek域`() {
        assertEquals(GestureDomain.OBJECT_SEEK, resolveGestureDomain(input(onSeekBar = true, horizontalFirst = true)))
    }

    // ---------------- #4 图集内横滑：对象域翻图 ----------------

    @Test
    fun `图集内横滑翻图归对象域`() {
        assertEquals(
            GestureDomain.OBJECT_GALLERY,
            resolveGestureDomain(input(inGallery = true, horizontalFirst = true)),
        )
    }

    // ---------------- #5 边界残余动量：级联门槛裁决（R181） ----------------

    @Test
    fun `图集末页慢甩残余动量被吞_不升级任何层`() {
        // 低于级联门槛（慢推/慢甩）：R177 防误触语义保留——就地吞掉
        assertEquals(
            GestureDomain.MOMENTUM_SWALLOWED,
            resolveGestureDomain(input(inGallery = true, galleryAtBoundary = true, horizontalFirst = true, railActive = true)),
        )
    }

    @Test
    fun `图集末页明确甩尾级联进轨道_R181 甲案`() {
        // 甩尾速度过级联门槛且轨道在场（首页页位）：残余动量升级成轨道步进（L0→L1）
        assertEquals(
            GestureDomain.MOMENTUM_CASCADED,
            resolveGestureDomain(input(inGallery = true, galleryAtBoundary = true, horizontalFirst = true, railActive = true, spillFlingIntent = true)),
        )
    }

    @Test
    fun `发现或我的页位的甩尾不级联_轨道不在场保持吞没`() {
        // railActive=false（非首页页位）：甩尾再快也没有轨道可级联——R177 原语义
        assertEquals(
            GestureDomain.MOMENTUM_SWALLOWED,
            resolveGestureDomain(input(inGallery = true, galleryAtBoundary = true, horizontalFirst = true, spillFlingIntent = true)),
        )
    }

    @Test
    fun `吞并口径_横向全吞纵向全放`() {
        // 图集末页 fling：x 剩余动量吞掉（不切页），y 剩余动量放行（feed 翻页链在上层）
        val (swallowX, swallowY) = swallowedSpill(availableX = -1800f, availableY = 240f)
        assertEquals(-1800f, swallowX, 0f)
        assertEquals(0f, swallowY, 0f)
    }

    // ---------------- 运行时执法件直锚（guard 的两条通道与纯函数同口径） ----------------

    @Test
    fun `guard 拖拽通道_横向剩余全吞纵向放行`() {
        val consumed = Offset(360f, 0f)
        val available = Offset(-96f, -420f)
        val got = TopLevelPageScrollGuard.onPostScroll(consumed, available, NestedScrollSource.Drag)
        assertEquals(-96f, got.x, 0f)
        assertEquals(0f, got.y, 0f)
    }

    @Test
    fun `guard fling 通道_末页甩尾动量就地终止`() = runBlocking {
        val got = TopLevelPageScrollGuard.onPostFling(Velocity.Zero, Velocity(1600f, -300f))
        assertEquals(1600f, got.x, 0f)
        assertEquals(0f, got.y, 0f)
    }

    // ---------------- #6 竖滑：页内域 + 轴锁 ----------------

    @Test
    fun `竖滑归页内域_feed 翻页`() {
        assertEquals(GestureDomain.FEED_VERTICAL, resolveGestureDomain(input(verticalFirst = true)))
    }

    @Test
    fun `竖滑锁轴后横向分量不升级页面域_残余仍被吞`() {
        // 竖滑赢家手势中的横向剩余 dispatch 到页级 guard 同样被吞（MOMENTUM_SWALLOWED
        // 的 y 分量为 0 放行）——矩阵 #5/#6 联合语义
        val (swallowX, swallowY) = swallowedSpill(availableX = 96f, availableY = -420f)
        assertEquals(96f, swallowX, 0f)
        assertEquals(0f, swallowY, 0f)
    }

    // ---------------- #7 横滑：首页页位归媒体轨道域（R181 主路），其余页位归页面域 ----------------

    @Test
    fun `首页页位单指横滑归媒体轨道域_横滑切媒体tab不再直接切大页`() {
        assertEquals(GestureDomain.MEDIA_RAIL, resolveGestureDomain(input(horizontalFirst = true, railActive = true)))
    }

    @Test
    fun `发现或我的页位单指横滑归页面域_R177现状原样`() {
        // 轨道层不在场（railActive=false）：横滑直接切顶层大页
        assertEquals(GestureDomain.TOP_LEVEL_PAGE, resolveGestureDomain(input(horizontalFirst = true)))
    }

    @Test
    fun `图集外单指横滑归轨道域_单图卡同口径`() {
        assertEquals(
            GestureDomain.MEDIA_RAIL,
            resolveGestureDomain(input(inGallery = false, horizontalFirst = true, railActive = true)),
        )
    }

    @Test
    fun `轨道闸关闭时横滑不归轨道域_闸关路径顶层pager同关`() {
        // railActive=false 且 chrome 在场（=轨道闸关但顶层闸开着——理论瞬态，防御口径）：
        // 归页面域（顶层 pager 的闸在 MainActivity 侧同信号控制，两者不会分头行动）
        assertEquals(
            GestureDomain.TOP_LEVEL_PAGE,
            resolveGestureDomain(input(horizontalFirst = true, chromeHidden = false, railActive = false)),
        )
    }

    // ---------------- #8 清屏态：顶层禁用，手势归视频 ----------------

    @Test
    fun `清屏态横滑归视频域_顶层禁用轨道同关`() {
        // chromeHidden 优先于轨道判定：清屏态横滑归视频（L1 轨道闸与顶层 pager 闸同关）
        assertEquals(
            GestureDomain.VIDEO,
            resolveGestureDomain(input(chromeHidden = true, horizontalFirst = true, railActive = true)),
        )
    }

    @Test
    fun `顶层手势闸_chrome隐藏即关闸`() {
        assertTrue(topLevelSwipeEnabled(feedChromeHidden = false))
        assertFalse(topLevelSwipeEnabled(feedChromeHidden = true))
    }

    @Test
    fun `顶层手势闸_覆盖层压顶关闸_压顶期间横滑不许穿透切页`() {
        // 频道主页覆盖层/系统 PiP：pager 保持组合但被压顶——横滑是底层页面的导航手势，
        // 压顶期间关闸（其余二级页走整页替换、pager 离场，天然不冲突）
        assertTrue(topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = false))
        assertFalse(topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = true))
        assertFalse(topLevelSwipeEnabled(feedChromeHidden = true, overlayOpen = true))
    }

    // ---------------- #10 时间线台前：顶层用户横滑封闸（R374） ----------------

    @Test
    fun `时间线台前顶层闸关_横向手势就地终局不切页`() {
        // R374 根因：R308 overlaySwipeHost 竖轴 slop 先越界即弃权，残余未消费横分量被
        // 顶层 pager slop 探测拾走（guard 只吞 nestedScroll 派发残余）→「有概率」甩进
        // 发现页；封闸=闸比竞速可靠（与 #8 清屏行同落法）
        assertFalse(
            topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = false, timelineInFront = true),
        )
    }

    @Test
    fun `时间线台前判定_仅首页页位在场成立_后台保活不误杀发现我的横滑`() {
        // homeTimelineOn 只在首页域内有意义（保活语义）：经底栏去发现/我的后保持原值，
        // 裸值封闸会把那两页自身的横滑切页（#7' 行）一并误杀——台前必须 ∧ 首页页位
        assertTrue(timelineOverlayInFront(homeTimelineOn = true, topLevelPage = TopLevelRoute.HOME.ordinal))
        assertFalse(timelineOverlayInFront(homeTimelineOn = true, topLevelPage = TopLevelRoute.DISCOVER.ordinal))
        assertFalse(timelineOverlayInFront(homeTimelineOn = true, topLevelPage = TopLevelRoute.MINE.ordinal))
        assertFalse(timelineOverlayInFront(homeTimelineOn = false, topLevelPage = TopLevelRoute.HOME.ordinal))
    }

    @Test
    fun `时间线不在场_顶层闸保持开_消歧纯矩阵零改`() {
        // 负锚：普通 feed 态（homeTimelineOn=false）闸开；封闸发生在闸层（userScrollEnabled），
        // 矩阵查表函数本身零改——横滑归属判定原样（级联口径不碰）
        assertTrue(topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = false, timelineInFront = false))
        assertEquals(
            GestureDomain.MEDIA_RAIL,
            resolveGestureDomain(input(horizontalFirst = true, railActive = true)),
        )
        assertEquals(
            GestureDomain.TOP_LEVEL_PAGE,
            resolveGestureDomain(input(horizontalFirst = true)),
        )
    }

    // ---------------- 兜底：纯点按类 ----------------

    @Test
    fun `未定轴手势归视频域_点按双击长按的原有归属`() {
        assertEquals(GestureDomain.VIDEO, resolveGestureDomain(input()))
    }
}
