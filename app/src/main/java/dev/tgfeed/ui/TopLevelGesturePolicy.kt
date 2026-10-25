package dev.tgfeed.ui

/**
 * R177/R181 手势消歧矩阵（design-proposal.md §2.2 成文矩阵的代码化；R181 嵌套
 * 横滑层级重构后四层轨道口径）。
 *
 * 四层轨道模型（由内向外；方向全仓统一 **左滑 = 前进 = 轨道序 +1**）：
 * L0 图集照片（对象域）→ L1 媒体类型条（全部/视频/图文/音频，横滑主路）→
 * L2 一等公民（推荐↔关注）→ L3 顶层页（首页↔发现↔我的）。同一次触摸只能归
 * 一个域，消费次序从手势系统的实际裁决链自内向外推：
 *
 * | # | 条件 | 归属域 | 执法件（运行时真身） |
 * |---|------|--------|----------------------|
 * | 1 | 双指落下（任意层级、任意位置） | 捏合域 | pinchZoomTracking 在 Initial pass 双指即消费全部位移——外层两个 pager（竖/横）与 L1 轨道层的 Main pass slop 永远凑不够（R174 现产线方案） |
 * | 2 | 当前内容已放大（zoom>1）的单指拖动 | 对象域·平移 | 同层缩放手势件消费（翻页/切页/轨道都不得抢——「双击复位回 1x 后才恢复」） |
 * | 3 | 落点在进度条上 | 对象域·seek | SeekGestureEngine 自管 pointerInput（b2ce911 收编），down 即消费 |
 * | 4 | 图集内横滑（未到边界） | 对象域·翻图 | 内层 HorizontalPager 先消费（Main pass 子先父后 + slop 消费取消外层探测） |
 * | 5 | 图集末页/内层横滚容器（Stories/LazyRow）边界残余动量 | **级联门槛裁决**：≥[MediaRailPolicy.CASCADE_VELOCITY_DP] 且轨道在场（首页页位）→ [GestureDomain.MOMENTUM_CASCADED]（甩尾步进轨道 L1）；否则 [GestureDomain.MOMENTUM_SWALLOWED] 就地吞掉 | R181 [MediaRailCascadeConnection]（FeedScreen 根，nestedScroll 链序在页级 guard 之内）消费过门槛甩尾并触发轨道步进；低于门槛原样上抛 → [dev.tgfeed.ui.components.kit.TopLevelPageScrollGuard] 吞（R177 慢推/慢甩防误触语义保留） |
 * | 6 | 竖滑（竖轴 slop 先越界） | 页内域 | feed VerticalPager 消费；该手势锁轴后残余的横向分量同被 #5 的口径处理（不许中途改判） |
 * | 7 | 单指横滑、chrome 在场、zoom=1、**首页页位轨道闸开** | **媒体轨道域**（R181 主路：横滑切媒体 tab；级联矩阵见 [MediaRailPolicy.resolveStep]） | [railGestureLoop]（FeedScreen 根 pointerInput）横向 slop 越界即消费——顶层 pager 的 slop 探测出局（#4 同款「内层先消费」机制） |
 * | 7' | 单指横滑、chrome 在场、发现/我的页位（无轨道层） | 页面域 | 顶层 HorizontalPager（R177 现状原样——那两页横滑直接切大页） |
 * | 8 | 清屏/沉浸态（chrome 隐藏）的横滑 | 视频域 | 顶层 pager userScrollEnabled=false 且 L1 轨道闸同关（同一 [topLevelSwipeEnabled] 信号）——chrome 隐藏时手势归视频（R174 feedChromeHidden 信号） |
 * | 9 | 覆盖层压顶（频道主页/系统 PiP，pager 保持组合被压顶） | 压顶层自管 | 顶层手势闸与 L1 轨道闸同关闸（[topLevelSwipeEnabled] 的 overlayOpen 位）——压顶期间横滑不许穿透 |
 * | 10 | 时间线台前（R374 封口：R239 时间线升首页一等 tab 后遗留的用户实锤「时间线左右滑有概率进发现」） | overlay 自身域 | 时间线台前=横向手势就地终局：R308 [overlaySwipeHost] 在 overlay 根消费（左滑回推荐/右滑去关注/慢漂移不动——残余横分量不再升级）；顶层 pager userScrollEnabled 关闸（[topLevelSwipeEnabled] 的 timelineInFront 位）——L3 步进仅剩底栏点按/程序滚页通道（requestTopLevelPage→animateScrollToPage 不走手势通道）。根因：overlay 内竖轴 slop 先越界时 R308 宿主按让位纪律弃权、内层竖滚只消费 y，残余**未消费**横向分量被顶层 pager 的 slop 探测拾走（页根 guard 只吞 nestedScroll 派发链的残余、管不到指针层未消费量）——「有概率」=竖/横 slop 竞速时灵时不灵；封闸把该通道连根拔掉（与 #8 同一落法：闸比竞速可靠） |
 *
 * #5 的类问题清单（AGENT-DISCIPLINE §一，同类现场全量扫描）：图集 pager（提案点名）+
 * Stories 横滑条 + 发现页「刚冒出来的」横滑卡行 + 页内一切 LazyRow——首页页位上
 * 甩尾过级联门槛一律级联进轨道（一处连接全类覆盖，不逐容器打补丁）；发现/我的
 * 两页无轨道语义，保持 R177 吞没。
 *
 * 纯函数拆出的目的（BackdropPolicy/PlaybackStartPolicy/PinchCleanPolicy 同款惯例）：
 * 矩阵的裁决逻辑进单测枚举，Compose 侧保持薄壳（连接件只做「查表执行」动作）。
 * 轨道的步进/级联/方向数值口径不在本文件——单一来源 [MediaRailPolicy]（轨道专用），
 * 本文件只管「这次触摸归哪个域」。
 */
enum class GestureDomain {
    /** 捏合域：双指缩放/清屏（R174 pinchZoomTracking，Initial pass） */
    PINCH,

    /** 对象域·缩放态平移：zoom>1 的单指拖动归内容本体（平移画面） */
    OBJECT_PAN,

    /** 对象域·进度条横拖：SeekGestureEngine 自管消费 */
    OBJECT_SEEK,

    /** 对象域·图集翻图：内层 HorizontalPager（未到边界） */
    OBJECT_GALLERY,

    /**
     * 边界残余动量**级联进轨道**（R181）：内层横滚容器边界甩尾 ≥ 级联门槛
     * 且轨道在场（首页页位）——甩尾速度消费并步进轨道（L0→L1 级联，
     * 图集翻完继续甩 = 切下一个媒体 tab，破坏性小故级联优先于吞没）
     */
    MOMENTUM_CASCADED,

    /**
     * 边界残余动量就地吞掉（R177 语义保留面）：低于级联门槛（慢推/慢甩）或
     * 轨道不在场（发现/我的页位）——不升级任何层（防误触初衷）
     */
    MOMENTUM_SWALLOWED,

    /** 页内域：feed 竖滑翻页（VerticalPager） */
    FEED_VERTICAL,

    /**
     * 媒体轨道域（R181 主路）：首页页位单指横滑归 L1 轨道（切媒体 tab，
     * 步进/级联裁决见 [MediaRailPolicy]）；顶层 pager 在本行出局
     */
    MEDIA_RAIL,

    /** 页面域：顶层横滑切页（发现/我的页位的单指横滑，R177 现状） */
    TOP_LEVEL_PAGE,

    /** 视频对象域：点按/双击/长按；清屏态下的横滑也归此（顶层手势闸关闭） */
    VIDEO,
}

/**
 * 消歧输入：一次触摸手势的可观测特征快照（slop 竞速结果由 Compose 手势系统裁决后传入，
 * 本函数只做矩阵查表——裁决链的真身在运行时，矩阵的**次序**在此钉死并可测）。
 */
data class GestureDisambiguationInput(
    /** 当前按下的手指数（≥2 = 捏合域接管） */
    val pointerCount: Int = 1,
    /** 当前内容缩放（>1 = 平移域；清屏态必然 >1） */
    val zoomScale: Float = 1f,
    /** 落点是否在进度条（seek）热区上 */
    val onSeekBar: Boolean = false,
    /** 是否落在图集（内层 HorizontalPager）上 */
    val inGallery: Boolean = false,
    /** 图集/内层横滚容器是否已在其边界页（末页/首页——残余动量将按级联门槛裁决） */
    val galleryAtBoundary: Boolean = false,
    /** Feed chrome 是否处于沉浸隐藏态（清屏/全屏/源面板开窗——R174 信号） */
    val chromeHidden: Boolean = false,
    /** 横轴 touch slop 先越界（异向轴锁的胜者；false 且竖轴也未越界 = 纯点按类） */
    val horizontalFirst: Boolean = false,
    /** 竖轴 touch slop 先越界 */
    val verticalFirst: Boolean = false,
    /**
     * R181 轨道层是否在场可接管（首页页位 FeedScreen 组合中 + 手势闸开）：
     * true 时 #7 行的单指横滑归 [GestureDomain.MEDIA_RAIL]（切媒体 tab），
     * false（发现/我的页位、或闸关着——闸关走 #8）时保持 [GestureDomain.TOP_LEVEL_PAGE]。
     */
    val railActive: Boolean = false,
    /**
     * R181 边界甩尾的级联意图：内层横滚容器 fling 剩余速度 ≥
     * [MediaRailPolicy.CASCADE_VELOCITY_DP]（由 [MediaRailPolicy.commitFromSpill]
     * 判定后传入布尔，速度门槛数值单一来源在轨道策略层）。
     * 仅 #5 行消费：true 且轨道在场 → 级联；false → 吞没。
     */
    val spillFlingIntent: Boolean = false,
)

/**
 * 矩阵查表（次序见文件头表格：双指 > seek > 缩放平移 > 图集 > 竖滑 > 横滑 > 视频兜底）。
 * 注意 [GestureDisambiguationInput.zoomScale] > 1 时即使 chromeHidden 也归平移域——
 * 缩放态的单指手势永远归内容本体（先于「清屏禁横滑」判定，与 pinchZoomTracking 的
 * 接管条件 twoFinger || zoom>1 逐字对齐）。
 */
fun resolveGestureDomain(g: GestureDisambiguationInput): GestureDomain = when {
    g.pointerCount >= 2 -> GestureDomain.PINCH
    g.onSeekBar -> GestureDomain.OBJECT_SEEK
    g.zoomScale > 1f -> GestureDomain.OBJECT_PAN
    g.inGallery && g.horizontalFirst ->
        if (g.galleryAtBoundary) {
            if (g.spillFlingIntent && g.railActive) {
                GestureDomain.MOMENTUM_CASCADED
            } else {
                GestureDomain.MOMENTUM_SWALLOWED
            }
        } else {
            GestureDomain.OBJECT_GALLERY
        }
    g.verticalFirst -> GestureDomain.FEED_VERTICAL
    g.horizontalFirst ->
        if (g.chromeHidden) {
            GestureDomain.VIDEO
        } else if (g.railActive) {
            GestureDomain.MEDIA_RAIL
        } else {
            GestureDomain.TOP_LEVEL_PAGE
        }
    else -> GestureDomain.VIDEO
}

/**
 * 顶层横滑手势闸（§2.2 清屏行 + 任务书口径「chrome 隐藏时手势归视频」）：
 * feedChromeHidden（R174 的清屏/全屏/源面板/搜索音频页信号）时顶层 pager 的
 * userScrollEnabled 关闸。与 [resolveGestureDomain] 的 #8 行同口径，独立成函数
 * 供 MainActivity pager 挂载点直取（布尔闸不必构造整个输入快照）。
 * R181 起同一位信号**三消费方**：顶层 pager / L1 轨道拖拽闸 / L0 甩尾级联闸
 * （[dev.tgfeed.ui.components.kit.mediaRailHost] 的 enabled 同源直取）。
 *
 * [overlayOpen]：覆盖层压在 pager 之上仍保持 pager 组合的场景（频道主页覆盖层、
 * 系统画中画的 pipMode 满窗视频——其余二级页走 when 链整页替换、pager 已离场）。
 * 覆盖层在手势上不该被穿透：横滑属于底层页面的导航手势，压顶期间关闸。
 *
 * [timelineInFront]（R374 矩阵 #10 行）：时间线 overlay 台前（[timelineOverlayInFront]
 * 判定——homeTimelineOn ∧ 首页页位）时顶层 pager 的用户横滑关闸。**本位只有
 * pager 消费方传入**（MainActivity 挂载点）：L1/L0 轨道闸消费方不传（时间线
 * overlay 不透明整层盖顶，轨道层在其之下收不到事件——传了也是死逻辑）；
 * 点按路由不受影响（animateScrollToPage 不走手势通道，保活语义原样）。
 */
fun topLevelSwipeEnabled(
    feedChromeHidden: Boolean,
    overlayOpen: Boolean = false,
    timelineInFront: Boolean = false,
): Boolean = !feedChromeHidden && !overlayOpen && !timelineInFront

/**
 * R374 时间线台前判定（矩阵 #10 行的输入位）：时间线 overlay **实际盖在首页
 * 页位上**才叫台前 = [homeTimelineOn] ∧ 当前台前页是首页（[TopLevelRoute.HOME]
 * 的 ordinal 页位）。
 *
 * 为什么不裸用 homeTimelineOn：它只在首页域内有意义（AppContainer 保活语义
 * ——经底栏去发现/我的后保持原值，回首页时间线原样恢复）；裸值封闸会把
 * 发现/我的页位**自身**的横滑切页（#7' 行）一并误杀——时间线在后台保活时
 * 那两页的手势语义零改动（负锚）。页位输入=AppContainer.topLevelPage（路由侧
 * 即刻写入+滑动落定回写的同一位事实源）。
 */
fun timelineOverlayInFront(homeTimelineOn: Boolean, topLevelPage: Int): Boolean =
    homeTimelineOn && topLevelPage == TopLevelRoute.HOME.ordinal

/**
 * 边界残余动量的吞并与放行（#5 行低于门槛路径的数值口径，[dev.tgfeed.ui.components.kit.
 * TopLevelPageScrollGuard] 的 onPostScroll/onPostFling 同款）：
 * 横向剩余全吞（不升级页面域）、纵向剩余全放（feed 翻页链路在上层）。
 * 返回值 = guard 声明消费掉的量（供单测枚举矩阵：吞 x 放 y）。
 * R181 增量语义：过级联门槛的横向甩尾在 guard **之前**已被 MediaRailCascadeConnection
 * 消费（级联进轨道），能走到 guard 的横向剩余 = 低于门槛或轨道不在场——吞并面收窄
 * 为「慢推/慢甩 + 非首页页位」，口径本身不变。
 */
fun swallowedSpill(availableX: Float, availableY: Float): Pair<Float, Float> =
    availableX to 0f
