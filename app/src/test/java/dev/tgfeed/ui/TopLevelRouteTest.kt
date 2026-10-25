package dev.tgfeed.ui

import android.content.Context
import dev.tgfeed.core.feed.AggregateFeedSource
import dev.tgfeed.core.feed.ChannelFeedSource
import dev.tgfeed.core.feed.FavoritesFeedSource
import dev.tgfeed.core.feed.FollowingFeedSource
import dev.tgfeed.core.feed.WeightedAggregateFeedSource
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.TdGateway
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R173/R177/R229/R239 顶层路由状态机单测（纯函数锚，design-proposal §2.0/§2.1/§2.2）：
 * - 枚举序 = pager 页序契约（首页(0) ←→ 发现(1) ←→ 我的(2)，ordinal
 *   映射被 MainActivity pager 与 AppContainer 页码依赖；R229 追更插页四页→
 *   R239 时间线撤顶层页回首页 tab，页序复原三页）；
 * - 一级页解析吃单值页码（R177：showDiscover/showMine 两位布尔收敛为 topLevelPage，
 *   叠加态结构性不存在）；分屏/未就绪/登录前解析为 null（底栏隐藏的裁决基础）；
 * - 页位 ↔ 路由双射 + 越界防御；
 * - 返回键矩阵：发现/我的在台前按返回 = 回首页（不是退出 App），首页 = null
 *   不拦截；
 * - 底栏可见性四条件（一级页在台前/无覆盖/沉浸 chrome 未退/非 PiP）；
 * - 潜进源点首页回推荐流的裁决（关注/推荐不动，其余源全回）。
 * 网关/存储替身手法沿用 FollowingFeedSourceTest（mockk Context + 临时目录）。
 */
class TopLevelRouteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    /** 最小假网关：本测只借源类型的语义，不触发任何拉取 */
    private class StubGateway : TdGateway {
        override val state: StateFlow<GatewayState> = MutableStateFlow(GatewayState.Ready)
        override val connectionState: StateFlow<String> = MutableStateFlow("已连接")
        override val proxyHint: StateFlow<String?> = MutableStateFlow(null)
        override var includeCommentResources: Boolean = false

        override suspend fun submitPhone(phone: String) {}
        override suspend fun submitCode(code: String) {}
        override suspend fun submitPassword(password: String) {}
        override suspend fun listChannels(): List<dev.tgfeed.core.td.ChannelBrief> = emptyList()
        override suspend fun fetchVideos(
            request: dev.tgfeed.core.td.FeedRequest,
            cursor: String?,
            limit: Int,
        ): dev.tgfeed.core.td.FeedPage = dev.tgfeed.core.td.FeedPage(emptyList(), null)

        override suspend fun ensurePlayable(
            item: dev.tgfeed.core.td.VideoItem,
            priority: Int,
        ): String = error("本测不触发播放")
    }

    private fun snap(
        feed: Boolean = false,
        mode: String = "feed",
        page: Int = 0,
    ) = TopLevelRouteSnapshot(
        showFeed = feed,
        viewMode = mode,
        topLevelPage = page,
    )

    // ---------------- 枚举序 = pager 页序契约 ----------------

    @Test
    fun `枚举声明序钉死 pager 页序_插入或重排枚举必须连带改页序`() {
        // design-proposal §2.2 横滑序列 + R229 插页 + R239 撤页复原：
        // 首页(0) ←→ 发现(1) ←→ 我的(2)（时间线=首页 tab 非顶层页）
        assertEquals(
            listOf(TopLevelRoute.HOME, TopLevelRoute.DISCOVER, TopLevelRoute.MINE),
            TopLevelRoute.entries,
        )
    }

    // ---------------- 一级页解析（单值页码） ----------------

    @Test
    fun `单屏 Feed 页码 0 1 2 分别解析为首页 发现 我的`() {
        assertEquals(TopLevelRoute.HOME, resolveTopLevelRoute(snap(feed = true, page = 0)))
        assertEquals(TopLevelRoute.DISCOVER, resolveTopLevelRoute(snap(feed = true, page = 1)))
        assertEquals(TopLevelRoute.MINE, resolveTopLevelRoute(snap(feed = true, page = 2)))
    }

    @Test
    fun `页码越界解析为空_容器位写坏时宁可无一级页也不乱指`() {
        assertNull(resolveTopLevelRoute(snap(feed = true, page = 3)))
        assertNull(resolveTopLevelRoute(snap(feed = true, page = -1)))
    }

    @Test
    fun `分屏模式解析为空_底栏隐藏的裁决基础`() {
        assertNull(resolveTopLevelRoute(snap(feed = true, mode = "grid", page = 2)))
    }

    @Test
    fun `未进 Feed_登录或启动等待时解析为空`() {
        assertNull(resolveTopLevelRoute(snap(page = 1)))
    }

    // ---------------- 页位 ↔ 路由双射 ----------------

    @Test
    fun `页位路由双射往返闭合`() {
        TopLevelRoute.entries.forEach { route ->
            assertEquals(route, topLevelPageToRoute(topLevelRouteToPage(route)))
        }
        (0..TopLevelRoute.entries.lastIndex).forEach { page ->
            val route = topLevelPageToRoute(page)
            assertEquals(page, topLevelRouteToPage(route!!))
        }
    }

    @Test
    fun `越界页位映射为空路由`() {
        assertNull(topLevelPageToRoute(-1))
        assertNull(topLevelPageToRoute(TopLevelRoute.entries.size))
    }

    // ---------------- 返回键矩阵（任务书第 3 条） ----------------

    @Test
    fun `发现页按返回目标为首页`() {
        assertEquals(0, topLevelBackTargetPage(1))
    }

    @Test
    fun `我的页按返回目标为首页`() {
        assertEquals(0, topLevelBackTargetPage(2))
    }

    @Test
    fun `首页按返回不拦截_走页内返回栈与双击退出链`() {
        assertNull(topLevelBackTargetPage(0))
    }

    @Test
    fun `越界页码不拦截返回`() {
        assertNull(topLevelBackTargetPage(-1))
        assertNull(topLevelBackTargetPage(TopLevelRoute.entries.size))
    }

    // ---------------- 底栏可见性 ----------------

    @Test
    fun `一级页在台前且无压顶则底栏可见`() {
        assertTrue(
            resolveBottomBarVisible(
                BottomBarVisibilityInput(
                    TopLevelRoute.HOME,
                    overlayPageOpen = false,
                    feedChromeHidden = false,
                    inPip = false,
                ),
            ),
        )
    }

    @Test
    fun `无一级页则底栏不可见`() {
        assertFalse(
            resolveBottomBarVisible(
                BottomBarVisibilityInput(
                    null,
                    overlayPageOpen = false,
                    feedChromeHidden = false,
                    inPip = false,
                ),
            ),
        )
    }

    @Test
    fun `二级页或覆盖层压顶则底栏不可见`() {
        assertFalse(
            resolveBottomBarVisible(
                BottomBarVisibilityInput(
                    TopLevelRoute.DISCOVER,
                    overlayPageOpen = true,
                    feedChromeHidden = false,
                    inPip = false,
                ),
            ),
        )
    }

    @Test
    fun `Feed 沉浸 chrome 退场则底栏不可见_清屏全退`() {
        assertFalse(
            resolveBottomBarVisible(
                BottomBarVisibilityInput(
                    TopLevelRoute.HOME,
                    overlayPageOpen = false,
                    feedChromeHidden = true,
                    inPip = false,
                ),
            ),
        )
    }

    @Test
    fun `画中画中底栏不可见`() {
        assertFalse(
            resolveBottomBarVisible(
                BottomBarVisibilityInput(
                    TopLevelRoute.HOME,
                    overlayPageOpen = false,
                    feedChromeHidden = false,
                    inPip = true,
                ),
            ),
        )
    }

    // ---------------- 潜进源点首页的裁决 ----------------

    @Test
    fun `关注流点首页不动`() {
        assertFalse(shouldResetToAggregate(FollowingFeedSource(StubGateway(), store)))
    }

    @Test
    fun `推荐流两个实现点首页都不动`() {
        assertFalse(shouldResetToAggregate(AggregateFeedSource(StubGateway())))
        assertFalse(shouldResetToAggregate(WeightedAggregateFeedSource(StubGateway(), store)))
    }

    @Test
    fun `潜进源点首页回推荐流`() {
        assertTrue(shouldResetToAggregate(FavoritesFeedSource(store)))
        assertTrue(shouldResetToAggregate(ChannelFeedSource(StubGateway(), 1L)))
    }

    @Test
    fun `源不可知时不动_只走路由`() {
        assertFalse(shouldResetToAggregate(null))
    }
}
