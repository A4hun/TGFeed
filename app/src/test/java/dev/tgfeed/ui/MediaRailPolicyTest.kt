package dev.tgfeed.ui

import android.content.Context
import dev.tgfeed.core.feed.AggregateFeedSource
import dev.tgfeed.core.feed.ChannelFeedSource
import dev.tgfeed.core.feed.FavoritesFeedSource
import dev.tgfeed.core.feed.FeedSource
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R181 四层滑动轨道纯策略单测（MediaRailPolicy 的逐项枚举）。
 *
 * 覆盖面：
 * - 方向语义钉死（左滑=前进=dx/vx 为负，四层同向——用户红线不许一层前进一层后退）；
 * - 轨道几何（媒体 tab 步进/边界、L2 换栏/边界、源→轨道位映射）；
 * - 手势定局（fling 门槛 / 拖拽 commit 距离 / 慢漂移 NONE / 速度覆盖位移）；
 * - 级联矩阵（L1 步进 → 边界级联门槛 → L2 → L3 / 潜进源直连 L3 / 死端越界）；
 * - L0 甩尾级联门槛（commitFromSpill，慢推/慢甩不级联）。
 */
class MediaRailPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        // 网关/存储替身手法沿用 TopLevelRouteTest（mockk Context + 临时目录）
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

    // 测试用 px 域（策略函数与单位无关，取整数便于断言）
    private val stepPx = 300f
    private val flingPx = 1000f
    private val cascadePx = 3000f
    private val commitPx = MediaRailPolicy.commitDistancePx(stepPx) // 150f

    // ---------------- 方向语义：左滑 = 前进（四层同向钉死） ----------------

    @Test
    fun `左滑为前进_L1轨道序与tab带视觉序一致`() {
        // 左滑（forward）从全部出发逐格推进；右滑逐格回退；到边界 null
        assertEquals(0, MediaRailPolicy.stepTab(-1, forward = true))   // 全部→视频
        assertEquals(1, MediaRailPolicy.stepTab(0, forward = true))   // 视频→图文
        assertEquals(2, MediaRailPolicy.stepTab(1, forward = true))   // 图文→音频
        assertNull(MediaRailPolicy.stepTab(2, forward = true))        // 音频到头（左滑边界）
        assertNull(MediaRailPolicy.stepTab(-1, forward = false))      // 全部到头（右滑边界）
        assertEquals(-1, MediaRailPolicy.stepTab(0, forward = false)) // 视频→全部
    }

    @Test
    fun `左滑为前进_L2轨道序推荐在前_关注在后`() {
        // 用户原话锚定：推荐左滑到头无缝进入关注栏——轨道序推荐(0)→关注(1)
        assertEquals(FeedColumn.FOLLOWING, MediaRailPolicy.stepColumn(FeedColumn.RECOMMEND, forward = true))
        assertNull(MediaRailPolicy.stepColumn(FeedColumn.FOLLOWING, forward = true)) // 关注左滑=L2 边界
        assertEquals(FeedColumn.RECOMMEND, MediaRailPolicy.stepColumn(FeedColumn.FOLLOWING, forward = false))
        assertNull(MediaRailPolicy.stepColumn(FeedColumn.RECOMMEND, forward = false)) // 推荐右滑=L2 边界
    }

    @Test
    fun `左滑为前进_位移与速度同号约定`() {
        // dx<0 / vx<0 = FORWARD（与 R177 顶层 pager「首页左滑→发现」同一符号约定）
        assertEquals(RailCommit.FORWARD_FLING, MediaRailPolicy.resolveCommit(-100f, -2000f, commitPx, flingPx))
        assertEquals(RailCommit.BACKWARD_FLING, MediaRailPolicy.resolveCommit(100f, 2000f, commitPx, flingPx))
        assertEquals(RailCommit.FORWARD_DRAG, MediaRailPolicy.resolveCommit(-200f, 0f, commitPx, flingPx))
        assertEquals(RailCommit.BACKWARD_DRAG, MediaRailPolicy.resolveCommit(200f, 0f, commitPx, flingPx))
    }

    // ---------------- 轨道位映射（源 → L2） ----------------

    private fun columnOf(source: FeedSource?): FeedColumn? = MediaRailPolicy.columnOf(source)

    @Test
    fun `聚合与加权聚合都算推荐_关注流算关注_潜进源无L2`() {
        assertEquals(FeedColumn.FOLLOWING, columnOf(FollowingFeedSource(StubGateway(), store)))
        assertEquals(FeedColumn.RECOMMEND, columnOf(AggregateFeedSource(StubGateway())))
        assertEquals(FeedColumn.RECOMMEND, columnOf(WeightedAggregateFeedSource(StubGateway(), store)))
        assertNull(columnOf(FavoritesFeedSource(store)))    // 潜进源：收藏夹
        assertNull(columnOf(ChannelFeedSource(StubGateway(), 1L))) // 潜进源：单频道
        assertNull(columnOf(null))                          // VM 不可用
    }

    // ---------------- 手势定局：门槛与防误触 ----------------

    @Test
    fun `慢漂移不动作_速度不过线且位移不过commit距离`() {
        assertEquals(RailCommit.NONE, MediaRailPolicy.resolveCommit(-80f, -300f, commitPx, flingPx))
        assertEquals(RailCommit.NONE, MediaRailPolicy.resolveCommit(0f, 0f, commitPx, flingPx))
    }

    @Test
    fun `速度覆盖位移_甩动方向赢过位置方向`() {
        // 拖向右但松手向左甩：按甩动方向定局（pager 松手语义）
        assertEquals(
            RailCommit.FORWARD_FLING,
            MediaRailPolicy.resolveCommit(200f, -2000f, commitPx, flingPx),
        )
        assertEquals(
            RailCommit.BACKWARD_FLING,
            MediaRailPolicy.resolveCommit(-200f, 2000f, commitPx, flingPx),
        )
    }

    @Test
    fun `commit距离是轨道步宽的半程_与高亮翻转同拍`() {
        assertEquals(150f, MediaRailPolicy.commitDistancePx(300f), 0f)
        assertEquals(stepPx / 2f, MediaRailPolicy.commitDistancePx(stepPx), 0f)
    }

    // ---------------- 级联矩阵（resolveStep 逐行） ----------------

    @Test
    fun `L1有下一格就步进_慢拖与普通fling都可`() {
        // 慢拖过半程（DRAG 意图）：主路不需要甩动意图
        assertEquals(
            RailAction.StepTab(1),
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_DRAG, 0f, cascadePx, 0, FeedColumn.RECOMMEND, 0),
        )
        // 普通 fling（速度过 fling 线但未过级联线）：同样步进
        assertEquals(
            RailAction.StepTab(-1),
            MediaRailPolicy.resolveStep(RailCommit.BACKWARD_FLING, 1500f, cascadePx, 0, FeedColumn.FOLLOWING, 0),
        )
    }

    @Test
    fun `L1边界慢漂移不级联_死在原地`() {
        // 音频左滑边界 + 慢拖：无动作（用户设计：慢漂移不级联）
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_DRAG, 0f, cascadePx, 2, FeedColumn.RECOMMEND, 0),
        )
        // 边界 + 弱 fling（过 fling 线未过级联线）：同样不级联
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, 1500f, cascadePx, 2, FeedColumn.RECOMMEND, 0),
        )
    }

    @Test
    fun `L1边界明确甩动升级L2_推荐左滑进关注`() {
        assertEquals(
            RailAction.SwitchColumn(FeedColumn.FOLLOWING),
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, -3500f, cascadePx, 2, FeedColumn.RECOMMEND, 0),
        )
        // 关注右滑边界回推荐（轨道对称）
        assertEquals(
            RailAction.SwitchColumn(FeedColumn.RECOMMEND),
            MediaRailPolicy.resolveStep(RailCommit.BACKWARD_FLING, 3500f, cascadePx, -1, FeedColumn.FOLLOWING, 0),
        )
    }

    @Test
    fun `L2边界升级L3_关注左滑到头进发现_一次只升一级`() {
        // 关注·音频边界左甩 → 发现（页 1）；不跨级跳到我的（页 2）
        assertEquals(
            RailAction.StepTopPage(1),
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, -3500f, cascadePx, 2, FeedColumn.FOLLOWING, 0),
        )
        // 推荐·全部边界右甩：L2 边界 + L3 越界（首页页 0 右滑）→ 死端
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveStep(RailCommit.BACKWARD_FLING, 3500f, cascadePx, -1, FeedColumn.RECOMMEND, 0),
        )
    }

    @Test
    fun `潜进源无L2_L1边界甩动直连L3`() {
        // 任务书第 5 条口径：没有 L2 就直接连 L3（收藏夹·音频边界左甩 → 发现）
        assertEquals(
            RailAction.StepTopPage(1),
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, -3500f, cascadePx, 2, null, 0),
        )
        // 潜进源·全部边界右甩：L3 越界 → 死端
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveStep(RailCommit.BACKWARD_FLING, 3500f, cascadePx, -1, null, 0),
        )
    }

    @Test
    fun `NONE定局不产生动作`() {
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveStep(RailCommit.NONE, -3500f, cascadePx, 0, FeedColumn.RECOMMEND, 0),
        )
    }

    @Test
    fun `顶层页越界防御_L3末端边界`() {
        // 页位越界防御：topPage=3 前进（我的末端——R229 四页化后真尽头）→ None
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, -3500f, cascadePx, 2, FeedColumn.FOLLOWING, 3),
        )
    }

    // ---------------- L0 甩尾级联门槛（内层横滚容器边界） ----------------

    @Test
    fun `甩尾速度过级联线才有资格_慢推慢甩一律NONE`() {
        assertEquals(
            RailCommit.FORWARD_FLING,
            MediaRailPolicy.commitFromSpill(-3500f, cascadePx),
        )
        assertEquals(
            RailCommit.BACKWARD_FLING,
            MediaRailPolicy.commitFromSpill(3500f, cascadePx),
        )
        // 低于级联线：NONE → 上抛给 guard 吞（R177 语义保留）
        assertEquals(RailCommit.NONE, MediaRailPolicy.commitFromSpill(-1500f, cascadePx))
        assertEquals(RailCommit.NONE, MediaRailPolicy.commitFromSpill(0f, cascadePx))
        assertEquals(RailCommit.NONE, MediaRailPolicy.commitFromSpill(2999f, cascadePx))
    }

    @Test
    fun `门槛线上的临界值_等于即过线`() {
        assertEquals(RailCommit.FORWARD_FLING, MediaRailPolicy.commitFromSpill(-cascadePx, cascadePx))
        // 非边界处速度恰等于级联线：L1 步进照常（级联门槛只在边界生效）
        assertEquals(
            RailAction.StepTab(1),
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, -cascadePx, cascadePx, 0, FeedColumn.RECOMMEND, 0),
        )
        // 边界处速度恰等于级联线：abs(v)>=cascade 判定成立 → 级联生效
        assertEquals(
            RailAction.SwitchColumn(FeedColumn.FOLLOWING),
            MediaRailPolicy.resolveStep(RailCommit.FORWARD_FLING, -cascadePx, cascadePx, 2, FeedColumn.RECOMMEND, 0),
        )
    }

    // ---------------- 一步到位入口（Compose 薄壳消费的契约） ----------------

    @Test
    fun `resolveGesture端到端_拖拽半程步进_边界弱甩不动_边界强甩级联`() {
        // 拖过半程 → 步进一格（全部→视频）
        assertEquals(
            RailAction.StepTab(0),
            MediaRailPolicy.resolveGesture(-160f, 0f, stepPx, flingPx, cascadePx, -1, FeedColumn.RECOMMEND, 0),
        )
        // 边界弱甩（速度 1500 < 3000）→ 不动
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveGesture(-280f, -1500f, stepPx, flingPx, cascadePx, 2, FeedColumn.RECOMMEND, 0),
        )
        // 边界强甩（速度 3500 ≥ 3000）→ 换栏进关注
        assertEquals(
            RailAction.SwitchColumn(FeedColumn.FOLLOWING),
            MediaRailPolicy.resolveGesture(-280f, -3500f, stepPx, flingPx, cascadePx, 2, FeedColumn.RECOMMEND, 0),
        )
        // 慢漂移（-80px 且速度 0）→ 不动
        assertEquals(
            RailAction.None,
            MediaRailPolicy.resolveGesture(-80f, 0f, stepPx, flingPx, cascadePx, 0, FeedColumn.RECOMMEND, 0),
        )
    }

    // ---------------- R308 时间线 overlay 横滑归宿（用户实锤：左滑=返回推荐） ----------------

    @Test
    fun `时间线左滑回推荐_右滑去关注_慢漂移不动`() {
        // 左滑=前进 → 推荐（用户实锤语义：「时间线页左滑正常应该返回推荐界面」）
        assertEquals(
            FeedColumn.RECOMMEND,
            MediaRailPolicy.resolveTimelineSwipe(
                MediaRailPolicy.resolveCommit(-160f, 0f, commitPx, flingPx),
            ),
        )
        assertEquals(
            FeedColumn.RECOMMEND,
            MediaRailPolicy.resolveTimelineSwipe(
                MediaRailPolicy.resolveCommit(-30f, -1200f, commitPx, flingPx),
            ),
        )
        // 右滑=后退 → 关注（顶栏视觉序 [关注|时间线|推荐] 的对称邻居）
        assertEquals(
            FeedColumn.FOLLOWING,
            MediaRailPolicy.resolveTimelineSwipe(
                MediaRailPolicy.resolveCommit(160f, 0f, commitPx, flingPx),
            ),
        )
        assertEquals(
            FeedColumn.FOLLOWING,
            MediaRailPolicy.resolveTimelineSwipe(
                MediaRailPolicy.resolveCommit(30f, 1200f, commitPx, flingPx),
            ),
        )
        // 慢漂移两线都未过 → NONE → 不动（防误触），且绝不能落成切页
        assertNull(
            MediaRailPolicy.resolveTimelineSwipe(
                MediaRailPolicy.resolveCommit(-80f, 0f, commitPx, flingPx),
            ),
        )
        assertNull(MediaRailPolicy.resolveTimelineSwipe(RailCommit.NONE))
    }

    @Test
    fun `时间线横滑方向与L2轨道同向_左滑前进链时间线在推荐之前`() {
        // R181 红线：方向四层同向（左滑=前进）——时间线 overlay 层的方向口径同钉死：
        // 完整左滑链 时间线→推荐→关注（时间线=overlay 不占轨道位，但方向语义同源）
        assertEquals(
            FeedColumn.RECOMMEND,
            MediaRailPolicy.resolveTimelineSwipe(RailCommit.FORWARD_FLING),
        )
        assertEquals(
            FeedColumn.RECOMMEND,
            MediaRailPolicy.resolveTimelineSwipe(RailCommit.FORWARD_DRAG),
        )
        assertEquals(
            FeedColumn.FOLLOWING,
            MediaRailPolicy.resolveTimelineSwipe(RailCommit.BACKWARD_FLING),
        )
        assertEquals(
            FeedColumn.FOLLOWING,
            MediaRailPolicy.resolveTimelineSwipe(RailCommit.BACKWARD_DRAG),
        )
        // 与既有 L2 轨道拼合：推荐前进=关注（R181 原样），即 时间线→推荐→关注
        assertEquals(
            FeedColumn.FOLLOWING,
            MediaRailPolicy.stepColumn(FeedColumn.RECOMMEND, forward = true),
        )
    }
}
