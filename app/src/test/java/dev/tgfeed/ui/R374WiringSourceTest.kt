package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R374 时间线台前横滑封页 wiring 源码锚（R367 同款 user.dir 源定位模式）。
 * 根因判词（用户 11:0x 实锤「时间线界面有概率左右滑动时进入发现界面——不太对，
 * 时间线已是一等公民界面」）：R239 时间线升首页一等 tab 后，顶层 pager 的**用户**
 * 横滑切页通道未随升级封口——R308 overlaySwipeHost 走「竖轴 slop 先越界即弃权」
 * 让位纪律，对角/带竖漂移的横滑（竖轴先越界）在其弃权后，内层竖滚列表只消费 y，
 * 残余**未消费**横向分量被顶层 pager 的 slop 探测拾走（页根 TopLevelPageScrollGuard
 * 只吞 nestedScroll 派发链的残余，管不到指针层未消费量）→ 甩进发现页。
 * 「有概率」= 竖/横 slop 竞速时灵时不灵。
 * 修法：时间线台前（homeTimelineOn ∧ 首页页位）封顶层 pager 的用户横滑
 * （[topLevelSwipeEnabled] 增 timelineInFront 位 → userScrollEnabled=false）——
 * 闸比竞速可靠（与矩阵 #8 清屏行同落法，animateScrollToPage 不走手势通道，
 * 底栏点按/程序滚页不受影响）；台前判定不裸用 homeTimelineOn：保活语义下
 * 经底栏去发现/我的后该值保持原值，裸值会把发现/我的页位自身的横滑切页
 * （#7' 行）一并误杀。
 * 红线：普通 feed 态（homeTimelineOn=false）横滑 L2→L3 级联零改
 * （MediaRailPolicy 级联口径不碰）；时间线纵向滚动/下拉刷新/双击刷新零改；
 * 时间线页头三 tab 点按零改；R308 overlaySwipeHost 在场锚（时间线横滑自身
 * 归宿=左滑回推荐/右滑去关注保持）。
 */
class R374WiringSourceTest {

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

    /**
     * ① userScrollEnabled 接线源锚：MainActivity pager 挂载点把时间线台前判定
     * 接进顶层手势闸（封闸的运行时真身在挂载处，不在策略层闭门造车）。
     */
    @Test
    fun pager_mount_wires_timeline_in_front_into_top_level_gate() {
        val text = src("dev/tgfeed/MainActivity.kt")
        val mount = text.indexOf("TopLevelPagerHost(")
        assertTrue("TopLevelPagerHost 挂载点应在场", mount >= 0)
        val window = text.substring(mount, minOf(text.length, mount + 2400))
        assertTrue(
            "挂载点 userScrollEnabled 应接线 timelineInFront 位（时间线台前封闸）",
            window.contains("timelineInFront = dev.tgfeed.ui.timelineOverlayInFront("),
        )
        assertTrue(
            "台前判定输入=homeTimelineOn 与 topLevelPage 两位事实源（容器台前页+overlay 在场）",
            window.contains("container.homeTimelineOn.value") &&
                window.contains("container.topLevelPage.value"),
        )
    }

    /**
     * ② 闸位正锚+老位负锚：timelineInFront 关闸；chrome/overlay 两老位语义零改
     * （R374 只增位不动既有两位）。
     */
    @Test
    fun top_level_gate_seals_when_timeline_in_front_and_old_bits_unchanged() {
        assertFalse(
            "时间线台前：顶层用户横滑闸关（横向手势就地终局）",
            topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = false, timelineInFront = true),
        )
        assertTrue(
            "时间线不在场：闸开（普通 feed 态横滑链路原样）",
            topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = false, timelineInFront = false),
        )
        // 老位零改（负锚）：#8 清屏行 / #9 压顶行语义原样
        assertTrue(topLevelSwipeEnabled(feedChromeHidden = false))
        assertFalse(topLevelSwipeEnabled(feedChromeHidden = true))
        assertFalse(topLevelSwipeEnabled(feedChromeHidden = false, overlayOpen = true))
        assertFalse(topLevelSwipeEnabled(feedChromeHidden = true, overlayOpen = true, timelineInFront = true))
    }

    /**
     * ③ 台前判定纯函数：仅 homeTimelineOn ∧ 首页页位成立——后台保活态（经底栏去
     * 发现/我的后 homeTimelineOn 保持原值）不误杀那两页自身的横滑切页（#7' 行）。
     */
    @Test
    fun timeline_in_front_requires_home_page() {
        assertTrue("时间线在首页台前", timelineOverlayInFront(homeTimelineOn = true, topLevelPage = 0))
        assertFalse(
            "底栏去发现后时间线后台保活：非台前（发现页横滑照常）",
            timelineOverlayInFront(homeTimelineOn = true, topLevelPage = 1),
        )
        assertFalse(
            "底栏去我的后时间线后台保活：非台前（我的页横滑照常）",
            timelineOverlayInFront(homeTimelineOn = true, topLevelPage = 2),
        )
        assertFalse(
            "普通 feed 态：非台前（横滑级联链路原样）",
            timelineOverlayInFront(homeTimelineOn = false, topLevelPage = 0),
        )
    }

    /**
     * ④ 负锚：R308 overlaySwipeHost 在场（时间线横滑自身归宿保持——左滑回推荐/
     * 右滑去关注/慢漂移不动）+ MediaRailPolicy 级联口径零改（级联门槛单源不碰）。
     */
    @Test
    fun overlay_swipe_host_mounted_and_rail_policy_unchanged() {
        val pursue = src("dev/tgfeed/ui/PursueScreen.kt")
        assertTrue(
            "R308 overlaySwipeHost 应仍在 PursueScreen 根在场（封顶层闸后时间线横滑归宿仍在 overlay 层）",
            pursue.contains(".overlaySwipeHost(enabled = pageActive)"),
        )
        assertEquals(FeedColumn.RECOMMEND, MediaRailPolicy.resolveTimelineSwipe(RailCommit.FORWARD_FLING))
        assertEquals(FeedColumn.RECOMMEND, MediaRailPolicy.resolveTimelineSwipe(RailCommit.FORWARD_DRAG))
        assertEquals(FeedColumn.FOLLOWING, MediaRailPolicy.resolveTimelineSwipe(RailCommit.BACKWARD_FLING))
        assertEquals(FeedColumn.FOLLOWING, MediaRailPolicy.resolveTimelineSwipe(RailCommit.BACKWARD_DRAG))
        assertNull(MediaRailPolicy.resolveTimelineSwipe(RailCommit.NONE))
        assertEquals("级联门槛单源零改", 1200f, MediaRailPolicy.CASCADE_VELOCITY_DP)
    }

    /**
     * ⑤ 负锚：guard 吞没口径零改（timelineInFront 封闸在闸层，不动 nestedScroll
     * 派发链的吞没行为——时间线纵向滚动/残余动量路径原样）。
     */
    @Test
    fun guard_swallow_scope_unchanged() {
        val (swallowX, swallowY) = swallowedSpill(availableX = -800f, availableY = 300f)
        assertEquals(-800f, swallowX, 0f)
        assertEquals(0f, swallowY, 0f)
    }
}
