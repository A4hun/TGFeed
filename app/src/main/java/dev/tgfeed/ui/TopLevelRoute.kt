package dev.tgfeed.ui

import dev.tgfeed.core.feed.AggregateFeedSource
import dev.tgfeed.core.feed.FeedSource
import dev.tgfeed.core.feed.FollowingFeedSource
import dev.tgfeed.core.feed.WeightedAggregateFeedSource

/**
 * R173 顶层路由状态机（design-proposal §2.0「三层分家」的 L0 页面域），
 * R177 第三刀横滑 pager 化后页位即路由；R229 起四页；**R239 回三页**——
 * 时间线升首页一等 tab（关注|时间线|推荐，AppContainer.homeTimelineOn 驱动的
 * feed 页 overlay 层），顶层 PURSUE 页撤销。
 *
 * 首页 / 发现 / 我的 三个一级目的地 + 底栏渲染裁决 + 返回键矩阵，全部纯函数、
 * 零 Compose 依赖，单测锚定（TopLevelRouteTest）。
 *
 * **枚举序 = pager 页序**（提案 §2.2 横滑序列 `首页(0) ←→ 发现(1) ←→ 我的(2)`
 * ——R239 时间线从顶层第 2 页撤位回首页 tab 后页序复原）：[TopLevelRoute.entries]
 * 的声明顺序被 [topLevelRouteToPage]/[topLevelPageToRoute] 依赖（ordinal 映射），
 * TopLevelRouteTest 首测钉死该契约——插入或重排枚举值必须连带改 pager 页序。
 *
 * **底栏三座位与页位恢复 1:1**（R229~R238 期间追更页无座位的错位形态随撤页
 * 消失）：首页/发现/我的各归其座。
 *
 * 现状：MainActivity 用顶层 HorizontalPager 承载三页（R177/R239）；路由载体是
 * AppContainer.topLevelPage（单值页码 + 一次性滚页请求），本状态机把「当前在哪个
 * 一级页」与「底栏此刻该不该在」从隐式顺序里提炼成显式可测函数。
 */
enum class TopLevelRoute {
    /** 首页（推荐/关注/时间线三 tab 的沉浸 Feed；底栏左端座位；pager 第 0 页） */
    HOME,

    /** 发现（候选频道试吃 + 热榜入口行 sheet；底栏中位座位；pager 第 1 页） */
    DISCOVER,

    /** 我的（账号与各管理页入口；底栏右端真头像座位；pager 第 2 页） */
    MINE,
}

/**
 * 路由快照：AppContainer 路由态的只读投影（收集时机在 MainActivity 组合期）。
 * R177 起 showDiscover/showMine 两位布尔收敛为 [topLevelPage] 单值页码（横滑结构上
 * 排除叠加态——第一刀 when 链里「发现 > 我的」的优先级仲裁不复存在）。
 */
data class TopLevelRouteSnapshot(
    val showFeed: Boolean,
    val viewMode: String,
    /** 当前顶层页码（0/1/2；AppContainer.topLevelPage 的投影） */
    val topLevelPage: Int,
)

/**
 * 解析当前一级页：
 * 1. showFeed 且单屏模式（viewMode=="feed"）→ 页码映射的枚举（越界页码 → null，
 *    防御性：容器位被写坏时宁可报「无一级页」也不乱指）；
 * 2. 其余 → null：无一级页在台前——含分屏模式（viewMode=="grid"，底栏隐藏的
 *    裁决默认值）、登录/启动等待（showFeed=false）。
 * 返回 null ≠「底栏隐藏」的同义词：二级页压在一级页上时（如搜索页开着、发现页
 * 垫底），本函数仍返回垫底的一级页——底栏显隐另见 [resolveBottomBarVisible]。
 */
fun resolveTopLevelRoute(s: TopLevelRouteSnapshot): TopLevelRoute? =
    if (s.showFeed && s.viewMode == "feed") topLevelPageToRoute(s.topLevelPage) else null

/** 页位 ↔ 路由双射（枚举序契约见 [TopLevelRoute]；越界 → null） */
fun topLevelPageToRoute(page: Int): TopLevelRoute? = TopLevelRoute.entries.getOrNull(page)

/** 路由 → pager 页位（ordinal；页序契约由单测钉死） */
fun topLevelRouteToPage(route: TopLevelRoute): Int = route.ordinal

/**
 * 返回键矩阵（R177 任务书第 3 条，design-proposal §2.5「一级页间返回不跨页面域」）：
 * 在发现/我的页按返回 = 先回首页（不是退出 App 的确认）；在首页 = null（不拦截，
 * 走页内返回栈：discoverReturnPending/searchReturnPending/channelHome 双击退出链，
 * 以及 R239 起时间线 overlay 的 BackHandler——声明在 feed 处理器之后=先吃）。
 * AppContainer.closeDiscover/closeMine 的落点决策共用本定义（单一定义不许各写一份）。
 */
fun topLevelBackTargetPage(page: Int): Int? = if (page in 1..TopLevelRoute.entries.lastIndex) 0 else null

/**
 * 底栏可见性输入（一级页快照 + 覆盖层/沉浸态旗标）。
 *
 * @param overlayPageOpen 二级页/覆盖层是否有开着的（搜索/设置/下载/报告/体检/
 *   星图/观看历史/片单管理/频道主页覆盖层）——抖音同款：二级页盖住底栏；
 * @param feedChromeHidden Feed 主层的沉浸隐藏信号（清屏/全屏/搜索音频页/
 *   源面板开窗，由 FeedScreen 经 AppContainer.feedChromeHidden 上报）——
 *   清屏的定义就是 chrome 全退，底栏残留就不是清屏（design-proposal §2.1）；
 * @param inPip 系统画中画中（与双击退出横幅同一开关，§2.7）。
 */
data class BottomBarVisibilityInput(
    val topLevel: TopLevelRoute?,
    val overlayPageOpen: Boolean,
    val feedChromeHidden: Boolean,
    val inPip: Boolean,
)

/**
 * 底栏此刻是否渲染：一级页在台前、无二级页/覆盖层压顶、Feed 沉浸 chrome 未退、
 * 不在画中画。悬浮窗（FloatingPlayerHost）不算覆盖层——它 z 序天然压过底栏，
 * 两者并存（design-proposal §2.7 零改动）。
 */
fun resolveBottomBarVisible(i: BottomBarVisibilityInput): Boolean =
    i.topLevel != null && !i.overlayPageOpen && !i.feedChromeHidden && !i.inPip

/**
 * 底栏「首页」点击时的潜进源裁决（裁决默认值⑥）：当前源不是关注/推荐
 * （=潜进了收藏夹/单频道/关键词/盲盒等）→ 点首页 = 清潜进源回推荐流；
 * 关注与推荐都是首页域的一等流，点了保持不动（不刷新、不打断）。
 * null（VM 不可用）不动，只走路由。
 */
fun shouldResetToAggregate(source: FeedSource?): Boolean =
    source != null &&
        source !is FollowingFeedSource &&
        source !is AggregateFeedSource &&
        source !is WeightedAggregateFeedSource
