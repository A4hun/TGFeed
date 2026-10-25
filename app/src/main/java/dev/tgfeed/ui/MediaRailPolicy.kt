package dev.tgfeed.ui

import dev.tgfeed.core.feed.AggregateFeedSource
import dev.tgfeed.core.feed.FeedSource
import dev.tgfeed.core.feed.FollowingFeedSource
import dev.tgfeed.core.feed.WeightedAggregateFeedSource
import kotlin.math.abs

/**
 * R181 四层滑动轨道纯策略（用户设计：嵌套横滑层级重构）。
 *
 * 轨道模型（由内向外，方向全仓统一 **左滑 = 前进 = 轨道序 +1**——与 R177 顶层
 * pager「首页左滑→发现」同一约定，用户红线：不许一层左滑前进另一层后退）：
 *
 * ```
 * L0 图集照片（对象域，HorizontalPager 现状；边界甩尾可级联进 L1）
 * L1 媒体类型条：全部(-1) → 视频(0) → 图文(1) → 音频(2)     ← 横滑主路
 * L2 一等公民：  推荐(0) → 关注(1)                            ← L1 边界级联
 * L3 顶层页：    首页(0) → 发现(1) → 我的(2)                  ← L2 边界级联（R177 下沉一级）
 * ```
 *
 * 完整左滑链：推荐·全部→…→推荐·音频→关注（同一媒体 tab 保持）→（关注·音频
 * 边界）→发现→我的。轨道序论证：若把顶栏视觉序 [关注|推荐] 当轨道序（关注在前），
 * 「关注左滑到头」只能到达推荐或死边界，与用户原话「推荐左滑到头直接无缝进入
 * 关注栏继续滑；再往外到头才是发现/我的大页」矛盾——唯一自洽轨道=推荐在前。
 * 顶栏视觉序与轨道序的镜像关系记为裁决项（报告成文），本刀不动顶栏。
 *
 * 溢出级联替代吞没（R177 TopLevelPageScrollGuard 语义的下沉演进）：
 * - **L1 步进**（主路）：拖拽过 commit 距离或 fling ≥ [FLING_VELOCITY_DP] → 步一格
 *   媒体 tab；一次手势只步一格（pager 语义）。
 * - **级联**（L1→L2 / L2→L3 / L0→L1）：只在边界处、且 fling 速度 ≥
 *   [CASCADE_VELOCITY_DP]（明确甩动意图）才升级；慢漂移/慢推不级联
 *   （R177 图集防误触初衷延续）。一次 fling 只升一级（用户模型=逐层往外走）。
 * - **潜进源**（收藏夹/单频道/关键词/盲盒等）：L1 步进照常（媒体带在所有源上
 *   渲染且过滤成立）；L1 边界无 L2 可升 → 直连 L3（任务书第 5 条「没有则边界
 *   直接连 L3」口径）。
 *
 * L2 换栏 = 现有 [FeedViewModel.showFollowing]/[showAggregate] 同一动作（媒体 tab
 * 保持，与顶栏 tab 点击语义逐字一致——收敛：一次实现两个触发点）。L3 级联走
 * AppContainer.requestTopLevelPage（与底栏点按同一滚页通道、同一落定 spring）。
 *
 * 纯函数拆出目的（TopLevelRoute/TopLevelGesturePolicy 同款惯例）：轨道裁决进单测
 * 枚举（MediaRailPolicyTest），Compose 侧保持薄壳（手势循环只做「测速+查表+执行」
 * 三个动作，数值口径全部在此钉死，调用方按密度转 px）。
 */
object MediaRailPolicy {

    // ---------------- 轨道常量（媒体 tab 域 = VM 的 mediaTypeTab 值域） ----------------

    /** 媒体 tab：全部 */
    const val TAB_ALL = -1

    /** 媒体 tab：视频（VM 默认值） */
    const val TAB_VIDEO = 0

    /** 媒体 tab：图文 */
    const val TAB_PHOTO = 1

    /** 媒体 tab：音频 */
    const val TAB_AUDIO = 2

    /**
     * L1 轨道序（全部→视频→图文→音频，与 tab 带视觉序一致；左滑 = 序 +1）。
     * mediaTypeTab 的值即轨道下标——VM 值域 -1..2 与轨道序天然同构，无需映射表。
     */
    val TAB_RAIL = intArrayOf(TAB_ALL, TAB_VIDEO, TAB_PHOTO, TAB_AUDIO)

    /**
     * 轨道步参考宽（dp）：跟手 fraction 的分母 + 拖拽 commit 距离（0.5 步 = 44dp，
     * 与底栏 targetPage「拖拽过半换目标」同款半程语义）。
     */
    const val DRAG_STEP_DP = 88f

    /**
     * L1 步进 fling 速度门槛（dp/s）：松手速度过此线 = 甩动意图，无需拖够
     * commit 距离即可步进。低于此线且未拖够距离 = 慢漂移 → 不动（防误触）。
     */
    const val FLING_VELOCITY_DP = 400f

    /**
     * 级联 fling 速度门槛（dp/s）：L1 边界升级 L2、L2 边界升级 L3、图集 L0 边界
     * 级联进 L1 的「明确甩动意图」线（高于普通 fling——级联动作跨栏，误触代价
     * 更高，R177 图集防误触初衷）。真机手感对照后可调（单一定义处）。
     */
    const val CASCADE_VELOCITY_DP = 1200f

    // ---------------- 轨道几何（方向纯函数） ----------------

    /**
     * L1 轨道步进：当前 tab 的下一/上一格；到边界返回 null（级联判定的入口条件）。
     * 左滑（forward=true）= 轨道序 +1 = 全部→视频→图文→音频。
     */
    fun stepTab(tab: Int, forward: Boolean): Int? {
        val idx = TAB_RAIL.indexOf(tab)
        if (idx < 0) return null // 防御：未知 tab 值不步进
        val next = if (forward) idx + 1 else idx - 1
        return TAB_RAIL.getOrNull(next)
    }

    /** L2 轨道步进：推荐→关注（左滑前进）；到边界返回 null。 */
    fun stepColumn(column: FeedColumn, forward: Boolean): FeedColumn? = when (column) {
        FeedColumn.RECOMMEND -> if (forward) FeedColumn.FOLLOWING else null
        FeedColumn.FOLLOWING -> if (forward) null else FeedColumn.RECOMMEND
    }

    /**
     * 当前源 → L2 轨道位：聚合推荐（普通/加权两实现都算，与 FeedScreen 的
     * aggregateActive 判定口径一致）= 推荐；关注流 = 关注；潜进源 = null
     * （无 L2 层，L1 边界直连 L3）。
     */
    fun columnOf(source: FeedSource?): FeedColumn? = when (source) {
        is FollowingFeedSource -> FeedColumn.FOLLOWING
        is AggregateFeedSource, is WeightedAggregateFeedSource -> FeedColumn.RECOMMEND
        else -> null
    }

    /**
     * 拖拽 commit 距离（px）：轨道步参考宽的半程——「拖过半程 = commit」与
     * 「高亮翻转」同拍（底栏 targetPage 半程翻转同款范式，单一来源不各写一份）。
     */
    fun commitDistancePx(stepPx: Float): Float = stepPx / 2f

    // ---------------- 手势定局（速度门槛判定） ----------------

    /**
     * 一次 L1 横滑手势的定局：方向（左滑=前进）+ 意图强度（FLING=速度过线 /
     * DRAG=慢速拖拽过 commit 距离）+ NONE（慢漂移，两线都未过 → 防误触不动）。
     *
     * 速度与位移方向冲突时速度赢（pager 松手语义：甩动方向覆盖位置）。
     */
    fun resolveCommit(
        dragDeltaX: Float,
        velocityX: Float,
        commitDistancePx: Float,
        flingVelocityPx: Float,
    ): RailCommit = when {
        velocityX <= -flingVelocityPx -> RailCommit.FORWARD_FLING
        velocityX >= flingVelocityPx -> RailCommit.BACKWARD_FLING
        dragDeltaX <= -commitDistancePx -> RailCommit.FORWARD_DRAG
        dragDeltaX >= commitDistancePx -> RailCommit.BACKWARD_DRAG
        else -> RailCommit.NONE
    }

    /**
     * R308 时间线 overlay 横滑归宿（用户实锤：「时间线页左滑正常应该返回推荐
     * 界面」——回上一级语义；右滑=对称去关注，顶栏视觉序 [关注|时间线|推荐] 的
     * 左右邻居）。时间线=feed 页内 overlay（R239），不在 L2 轨道序上，其横滑
     * 归宿单独钉死在此（方向语义单一来源纪律）。与既有 L2 轨道拼合后的完整
     * 左滑链：时间线→推荐→关注→发现→我的（R181「推荐左滑到头进关注」原样
     * 保持，本函数只定义时间线位的前后邻居）。NONE=慢漂移不动（防误触）。
     */
    fun resolveTimelineSwipe(commit: RailCommit): FeedColumn? = when (commit) {
        RailCommit.FORWARD_FLING, RailCommit.FORWARD_DRAG -> FeedColumn.RECOMMEND
        RailCommit.BACKWARD_FLING, RailCommit.BACKWARD_DRAG -> FeedColumn.FOLLOWING
        RailCommit.NONE -> null
    }

    /**
     * 内层横滚容器（图集 L0/Stories/LazyRow）边界甩尾的级联定局：横向剩余速度
     * 过级联门槛才有升级资格（慢推/慢甩 = R177 吞没语义，返回 NONE 上抛给
     * TopLevelPageScrollGuard 原样吞掉）。拖拽通道（onPostScroll）不走本函数——
     * 慢推一律不级联。
     */
    fun commitFromSpill(velocityX: Float, cascadeVelocityPx: Float): RailCommit = when {
        velocityX <= -cascadeVelocityPx -> RailCommit.FORWARD_FLING
        velocityX >= cascadeVelocityPx -> RailCommit.BACKWARD_FLING
        else -> RailCommit.NONE
    }

    // ---------------- 轨道裁决（级联矩阵的代码化） ----------------

    /**
     * 手势定局 → 轨道动作（矩阵从内向外查表）：
     * ① L1 有下一格 → [RailAction.StepTab]（无论 FLING/DRAG 意图，主路门槛已过）；
     * ② L1 边界 → 级联需 |velocity| ≥ cascadeVelocityPx（慢漂移不级联 → None）；
     * ③ L2 有下一栏 → [RailAction.SwitchColumn]（媒体 tab 保持，与顶栏 tab 点击
     *    同一语义）；潜进源（column=null）无此层直落 ④；
     * ④ L3 顶层页 ±1（越界 → None：推荐首端右滑/我的末端左滑天然死端）。
     */
    fun resolveStep(
        commit: RailCommit,
        velocityX: Float,
        cascadeVelocityPx: Float,
        mediaTab: Int,
        column: FeedColumn?,
        topPage: Int,
        // 顶层页数单一事实源（R229 四页化后 3→枚举 size——级联界随枚举自动伸缩）
        topLevelPageCount: Int = TopLevelRoute.entries.size,
    ): RailAction {
        if (commit == RailCommit.NONE) return RailAction.None
        val forward = commit == RailCommit.FORWARD_FLING || commit == RailCommit.FORWARD_DRAG
        // ① L1 步进（主路：慢拖过半程或普通 fling 都可，级联门槛只在边界生效）
        stepTab(mediaTab, forward)?.let { return RailAction.StepTab(it) }
        // ② 边界级联门槛：明确甩动意图才升级（慢漂移死在原地 = R177 防误触初衷）
        if (abs(velocityX) < cascadeVelocityPx) return RailAction.None
        // ③ L2 换栏（潜进源无此层，直连 L3）
        if (column != null) {
            stepColumn(column, forward)?.let { return RailAction.SwitchColumn(it) }
        }
        // ④ L3 顶层页
        val nextPage = topPage + if (forward) 1 else -1
        return if (nextPage in 0 until topLevelPageCount) RailAction.StepTopPage(nextPage) else RailAction.None
    }

    /**
     * 一步到位便捷入口（Compose 薄壳用）：手势结束 → 轨道动作。
     * 输入为 px 域（调用方已按密度换算），dp 常量的换算不散落两处。
     */
    fun resolveGesture(
        dragDeltaX: Float,
        velocityX: Float,
        stepPx: Float,
        flingVelocityPx: Float,
        cascadeVelocityPx: Float,
        mediaTab: Int,
        column: FeedColumn?,
        topPage: Int,
        topLevelPageCount: Int = TopLevelRoute.entries.size,
    ): RailAction = resolveStep(
        resolveCommit(dragDeltaX, velocityX, commitDistancePx(stepPx), flingVelocityPx),
        velocityX,
        cascadeVelocityPx,
        mediaTab,
        column,
        topPage,
        topLevelPageCount,
    )
}

/** L2 一等公民轨道位（轨道序：推荐(0) → 关注(1)，左滑前进——用户原话锚定） */
enum class FeedColumn {
    /** 推荐流（聚合/加权聚合，VM 默认源）——轨道起点 */
    RECOMMEND,

    /** 关注流（订阅频道完整内容场）——推荐左滑到头的下一站 */
    FOLLOWING,
}

/** 一次 L1 横滑手势的定局：方向 × 意图强度（左滑 = FORWARD = dx/vx 为负） */
enum class RailCommit {
    FORWARD_FLING,
    BACKWARD_FLING,
    FORWARD_DRAG,
    BACKWARD_DRAG,

    /** 慢漂移：速度未过 fling 线且位移未过 commit 距离——不动（防误触） */
    NONE,
}

/** 轨道动作（手势/级联的执行目标，Compose 侧按类型分发到 VM/容器通道） */
sealed interface RailAction {
    /** L1 步进：切媒体 tab（VM.setMediaTypeTab → refresh） */
    data class StepTab(val tab: Int) : RailAction

    /** L2 换栏：推荐↔关注（showAggregate/showFollowing，媒体 tab 保持） */
    data class SwitchColumn(val column: FeedColumn) : RailAction

    /** L3 顶层页步进（requestTopLevelPage → animateScrollToPage，与底栏点按同路） */
    data class StepTopPage(val page: Int) : RailAction

    /** 无动作（慢漂移/死端/越界） */
    object None : RailAction
}

/**
 * L1 拖拽的跟手视觉快照（媒体 tab 带消费，底栏 targetPage 范式移植）：
 * 拖拽中按 |delta|/stepPx 算 fraction，翻转到目标 tab 的高亮发生在
 * fraction ≥ 0.5（与 commit 距离同拍）；越界方向（边界拖拽）无目标 → null。
 */
data class RailDragVisual(
    /** 拖拽目标 tab（轨道方向上的相邻 tab；越界时调用方不构造本对象） */
    val targetTab: Int,
    /** 跟手进度 0..1（|delta| / 轨道步宽，封顶 1） */
    val fraction: Float,
)
