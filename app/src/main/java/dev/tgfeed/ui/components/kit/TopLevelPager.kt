package dev.tgfeed.ui.components.kit

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.abs

/**
 * R177 手势消歧矩阵的页级执法件（design-proposal §2.2 唯一真坑的类级修复，
 * 矩阵成文与纯函数见 [dev.tgfeed.ui.TopLevelGesturePolicy]）。
 *
 * 装在每个顶层页内容根上（[TopLevelPagerHost] 内已完成）：它位于一切内层横滚容器
 * （图集 pager / Stories 横滑条 / 发现页横滑卡行 / 任意 LazyRow）与顶层 pager 的
 * scrollable 之间——内层容器到达边界后的**残余横向动量**（drag 剩余位移与 fling
 * 剩余速度）在此就地吞掉，不再向上派发给顶层 pager。
 *
 * 为什么必须显式拦截：同向嵌套 scrollable 的剩余量走 nestedScroll 的 post 派发链
 * （子先父后）上抛，顶层 pager 的 scrollable connection 会消费它——「翻完图集继续
 * 甩」会被翻译成「切到发现页」。纵向剩余量必须原样放行（feed 翻页链路在顶层 pager
 * 之上，吞掉 y 就是杀死竖滑翻页）。
 *
 * 次级收益（矩阵 #6 行）：竖滑手势被 feed VerticalPager 锁轴后，残余的横向分量
 * dispatch 到本连接同样被吞——手势轴锁语义从「凑巧不冲突」升级为「显式保证」。
 */
object TopLevelPageScrollGuard : NestedScrollConnection {

    /** 拖拽剩余：横向全吞（不升级页面域）、纵向全放（feed 翻页链路在上层）——数值口径单一来源 [dev.tgfeed.ui.swallowedSpill] */
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val (swallowX, swallowY) = dev.tgfeed.ui.swallowedSpill(available.x, available.y)
        return Offset(swallowX, swallowY)
    }

    /** fling 剩余速度：横向全吞（图集末页甩尾不切页）、纵向全放——同一口径 */
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val (swallowX, swallowY) = dev.tgfeed.ui.swallowedSpill(available.x, available.y)
        return Velocity(swallowX, swallowY)
    }
}

/**
 * 顶层页落定转场（R232 用户实锤④「横滑切页硬切」：R177 的 PageSpring 1.0/380
 * 档案化，改 **tween(AnimMed=220, EmphasizedEasing)** slide+fade）：
 * 手势松手的 snap fling 与路由点按的 animateScrollToPage 共用同一枚——两条路径
 * 落定观感一致（R177 §2.2「点底栏=与手势同一条动画路径」的单一消费点纪律保持，
 * 换的只是这枚 spec 本身）。slide=pager 物理位移，fade=相邻页待命 α0.5→1
 * （[MotionTokens.PageStandbyAlpha]，R177 §5.2 行不动）；220ms emphasized
 * 减速尾与底栏显隐同一节律族（MotionTokens.EmphasizedEasing 既有消费面）。
 * 升降档只动本函数一处（R194⑤ 教训注记：220ms 用在**页位移落定**可感——若真机
 * 复核仍判无感，升 AnimSlow 也是这一处）。
 */
internal fun pageSettleSpec(): FiniteAnimationSpec<Float> = tween(
    durationMillis = TgFeedTokens.AnimMed,
    easing = MotionTokens.EmphasizedEasing,
)

/**
 * 顶层 HorizontalPager 宿主（R177 第三刀，design-proposal §2.2；R229 四页→R239
 * 回三页）：首页(0) ←→ 发现(1) ←→ 我的(2)，与 TopLevelRoute 枚举序一一对应。
 *
 * 本件只管「怎么滑」；「滑到哪」由调用方（MainActivity）的 PagerState 与
 * AppContainer 路由状态机负责。内置：
 * - **落定转场**：[PagerDefaults.flingBehavior] 的 snapAnimationSpec 换成
 *   [pageSettleSpec]（tween AnimMed/Emphasized——R232 slide+fade，手势松手落定；
 *   点按路径的 animateScrollToPage 由 MainActivity 同用 [pageSettleSpec]）；
 * - **页级动量拦截**：每页内容根挂 [TopLevelPageScrollGuard]（消歧矩阵 #5/#6 行）；
 * - **相邻页视差**：跟手位移 + 0.92 缩放 + α0.5 待命（MotionTokens.PageStandby*，
 *   §5.2「顶层页相邻页待命」行——与 feed 翻页 0.94/α0.55 同族再退半档）；偏移计算
 *   在 graphicsLayer 块内，滚动期间只重绘不重组（feed 翻页同款手法）；
 * - **顶层手势闸**：[userScrollEnabled]（清屏/沉浸态关闸——chrome 隐藏时手势归视频，
 *   任务书口径；覆盖层压顶关闸；**R374 起时间线台前（homeTimelineOn ∧ 首页页位）
 *   同关闸**——时间线已是一等公民界面，其台前期的横向手势归 R308 overlaySwipeHost
 *   就地终局、顶层用户横滑甩页通道封死，L3 步进仅剩底栏点按/程序滚页通道，见
 *   TopLevelGesturePolicy 矩阵 #10 行）；点按路径不受影响（animateScrollToPage
 *   不走手势通道）。
 *
 * 生命周期（R189 保活制，R177 B 节挂账升级兑现；R229 四页→R239 三页）：
 * beyondViewportPageCount=2——3 页全部常驻组合，首页滑去发现/我的再滑回，原视频
 * 原位还在（无 dispose/重建、无重拉；R239 起首页 tab 内的时间线 overlay
 * （homeTimelineOn）不改此保活——时间线是 feed 页内的层，切页时两层都常驻；
 * R374 起时间线台前期间**用户**横滑切页通道封闸（timelineInFront 位——顶层
 * pager 不响应用户手势），切页只剩底栏点按/程序滚页，保活语义原样：经底栏去
 * 发现/我的后 homeTimelineOn 保持原值，回首页时间线原样恢复）。
 * 当年 R177 推迟此形态的两笔账单一并兑付：
 * - **BackHandler 门控**：三页 handler 同时在册（Compose 后注册者优先，页序注册
 *   → 非激活页的返回处理器会吃掉激活页的返回语义）——调用方须给页体传
 *   「settledPage == 本页」的 pageActive，页内 BackHandler 全部挂 enabled=pageActive
 *   （MainActivity feedPage 双处理器+时间线 overlay 层 / DiscoverScreen 页级+盲盒 /
 *   MineScreen 页级）；
 * - **不可见暂停闸**：R180 navGate（AppContainer.feedPageActive 合成信号）天然
 *   承接——保活态下离场页组合着但非激活 = setNavGate 一刀切暂停+记账，滑回抬闸
 *   续播；离场页的 DisposableEffect 不再触发（releaseAll 兜底消失，闸是唯一停播点，
 *   正是 R177 头注释预言的「channelHomeOpen 同款播放闸」场景，R180 已把件备好）。
 *   时间线 overlay 的前台门控（PursueViewModel.setActive）同源 pageActive——
 *   离场页不轮询（R208 风暴闸之外的第二道省账闸）。
 * 冷启动零增量（R177 B 节承诺保持）：初始页 0 + beyond 2 → 发现/我的在冷启动
 * 即组合，但发现页 refreshDiscovery 走落定门控（settledPage==1 才触发，R177 C4）、
 * 时间线 bootstrap 走 setActive 门控（时间线 tab 打开才起拉取——冷启动窗零请求
 * 零网络）、MineScreen getMyProfile 走网关进程内缓存——零网络零带宽增量。
 * 二级页（设置/搜索等 when 整页替换）仍让 pager 整体离场——保活只管横滑往返域，
 * R182「关掉设置回我的页」落点记忆机制原样。
 */
@Composable
internal fun TopLevelPagerHost(
    state: PagerState,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
    pageContent: @Composable PagerScope.(page: Int) -> Unit,
) {
    HorizontalPager(
        state = state,
        modifier = modifier,
        userScrollEnabled = userScrollEnabled,
        // R189 三页常驻（保活制，R229 三→四页→R239 回三页）：离场页不再 dispose
        // ——状态冻结原位，滑回即还原。配套门控见类头注释（BackHandler pageActive
        // + navGate 不可见暂停闸 + 时间线 overlay setActive 门控）
        beyondViewportPageCount = 2,
        flingBehavior = PagerDefaults.flingBehavior(
            state = state,
            snapAnimationSpec = pageSettleSpec(),
        ),
    ) { page ->
        Box(
            Modifier
                .fillMaxSize()
                // 页级动量拦截（矩阵 #5/#6 行）：本 Box 是页内一切横滚容器的
                // nestedScroll 祖先——边界残余动量在此就地吞掉
                .nestedScroll(TopLevelPageScrollGuard)
                // 相邻页待命视差（§5.2 行）：0 = 本页落定；|offset| → 1 = 相邻页待命
                .graphicsLayer {
                    val pageOffset = (state.currentPage - page) + state.currentPageOffsetFraction
                    val t = abs(pageOffset).coerceIn(0f, 1f)
                    val scale = 1f - (1f - MotionTokens.PageStandbyScale) * t
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (1f - MotionTokens.PageStandbyAlpha) * t
                },
        ) {
            pageContent(page)
        }
    }
}
