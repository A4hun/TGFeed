package dev.tgfeed.ui

/**
 * R160 加载态一次成型：底衬（全屏模糊背景层）的采纳/上报/页背景纯判定规则。
 *
 * 背景（用户实测 P0"加载态分层登场"）：旧链路里外层底衬的换主挂在
 * settledPage（落定）+ 滚动期间整段撤黑 + 落定后 300ms 渐变，导致新页进场时
 * 「黑竖条 → 框内背景图 → 框外背景图」三段分层登场、观感像加载两遍。
 * 新口径让换主时机对齐 pager 的 currentPage 翻转（拖拽/甩动过半即翻，与
 * R159 内容感知列宽的形态解析同一时机）：翻页过半瞬间，列内封面占位与
 * 框外全屏底衬同帧同图登场；落定时一切就位，无二次重组。
 *
 * 二修（反向翻页竞态根治）：上报与采纳都收窄到"仅当前页"。一修版曾保留
 * settledPage 子句放行离开中旧页的 isActive 重发——反向翻页实测新页刚换主
 * 1ms 后即被旧页夺回、落定才纠正（r160_verify3.mp4 日志实锤），故删除。
 *
 * R336（2026-10-22 用户拍板 A 方案）：图片档底衬退役——图片项上报恒拒收
 * （[shouldAdoptBackdropForMedia]）+ 在场清场模式（[photoBackdropClearTransition]），
 * 视频腿口径逐字节不变（回归红线）。诊断史注记：R194 终案④ 同图双绘在图片卡
 * 露馅（Fit 留边=全屏模糊底衬与卡内清晰图两层皮）。
 *
 * 纯函数拆出的目的：Compose 侧保持薄壳（调用零逻辑），规则矩阵进单测枚举。
 */
object BackdropPolicy {

    /**
     * 外层是否采纳某页的底衬上报：只认当前页（[currentPage]——拖拽/甩动过半即翻转）。
     * - 当前页的上报即采纳：翻页过半瞬间新页封面生效（同帧换主），不等落定
     *   （旧口径"框外背景图最后才到"的根因）。
     * - 离开中的旧页（settledPage 尚真但 currentPage 已走）上报恒被挡——
     *   底衬归属全程无夺回，不回黑（旧逻辑靠落定后清空）。
     * - 快甩连翻多页：currentPage 逐页翻转，上报逐页被采纳追逐，终态归属落定页。
     */
    fun shouldAdoptBackdrop(page: Int, currentPage: Int): Boolean = page == currentPage

    /**
     * R336 图片档底衬退役（用户 2026-10-22 18:5x 拍板 A 方案）：外层是否采纳某页的
     * 底衬上报——在 [shouldAdoptBackdrop]（仅当前页守卫）之上加媒体类型档：
     * **图片项（mediaType==1，含图集翻页重报）恒拒收**。机理：R194 终案④「同图
     * 双绘」在图片卡上露馅——视频本体铺满挡住底衬无感，图片卡 Fit 留边=外层全屏
     * 模糊底衬与卡内清晰图两层皮（华为平板 56% 列宽下 44% 屏幕在放模糊底图实锤）。
     * 图片项在场时底衬由外层清场观察者淡出至纯深色（FeedScreen R336 清场链，模式
     * 见 [photoBackdropClearTransition]）。视频（0）/音频（2）项：与
     * [shouldAdoptBackdrop] 逐字节同口径——音频卡实勘不走上报链（背景图=卡内自绘
     * 满幅模糊层），本规则对其为永真通过，零行为面。老函数保留不删（API 只加不改：
     * 既有枚举测试与调用面零迁移）。
     */
    fun shouldAdoptBackdropForMedia(page: Int, currentPage: Int, mediaType: Int): Boolean =
        page == currentPage && mediaType != 1

    /**
     * R336 图片项在场底衬清场的换图模式（既有 [dev.tgfeed.ui.components.BackdropTransition]
     * 复用——零新动画体系）：离散轨道切换窗收口（[discretePending]——切 tab/换列后
     * 的第一拍）走 THROUGH_DARK 暗场溶解（旧图暗下、到位即图片档终态，与视频腿
     * 「窗收口暗场溶解」同语义）；常态翻页走 SOFT 软退场（BackdropStackFade 对
     * null 目标整层 exitSpec 软退场到 0，露出根 Box 纯黑=「淡出至纯深色」）。
     * 单测锚定：两态恒为既有枚举值（不引入第四种模式）。
     */
    internal fun photoBackdropClearTransition(
        discretePending: Boolean,
    ): dev.tgfeed.ui.components.BackdropTransition =
        if (discretePending) dev.tgfeed.ui.components.BackdropTransition.THROUGH_DARK
        else dev.tgfeed.ui.components.BackdropTransition.SOFT

    /**
     * 视频页底衬上报动作（[backdropReportAction] 的取值）。
     */
    enum class BackdropReportAction {
        /** 离场/预排版页：报 null——外层 currentPage 守卫恒挡掉，不清屏 */
        QUIET_NULL,
        /** 当前页但 ZOOM 填满/手动旋转：显式报 null 清屏（黑边语义与旧一致） */
        CLEAR_NULL,
        /** 当前页 FIT 且有图：上报换主/换帧 */
        REPORT,
        /** 当前页 FIT 但暂无封面：不报——外层保留上一条底衬（模糊近似色），不许黑窗 */
        SKIP_KEEP_OLD,
    }

    /**
     * 视频页底衬上报动作矩阵。
     * - [isCurrent] 当前页（拖拽过半即翻转）；[useZoom] 填满模式；[rotatedOdd] 手动
     *   旋转 90°/270°；[hasFrame] 封面或实时帧是否就绪。
     * - [zoomedOut] R174b 自由缩放：当前 zoom<1（视频缩到窗口中间）——填满页此刻
     *   有可见留边，按 FIT 同口径上报底衬（「周围透底衬」）；回到 ≥1x 恢复
     *   CLEAR_NULL（视频重新铺满、底衬不可见，行为零变化）。缩放跨沿只翻布尔
     *   （derivedStateOf 去抖），effect 按翻转重跑。
     * - 一修版曾让 (isActive||isCurrent) 上报：反向翻页时离开中的旧页（isActive 尚真）
     *   重发旧封面夺回底衬，故收窄到仅 isCurrent。
     */
    fun backdropReportAction(
        isCurrent: Boolean,
        useZoom: Boolean,
        rotatedOdd: Boolean,
        hasFrame: Boolean,
        zoomedOut: Boolean = false,
    ): BackdropReportAction = when {
        !isCurrent -> BackdropReportAction.QUIET_NULL
        // 2026-10-17 竖屏 ZOOM 黑底退役（用户实锤：竖屏没背景图）：
        // ZOOM 填满模式也上报底衬（视频本体盖住列区不可见，但列外/信息区/
        // 进度条周围透出模糊镜像）——与 FIT 同口径。仅手动旋转保留清屏。
        rotatedOdd -> BackdropReportAction.CLEAR_NULL
        hasFrame -> BackdropReportAction.REPORT
        else -> BackdropReportAction.SKIP_KEEP_OLD
    }

    /**
     * 视频页根背景是否不透明黑。
     * - 2026-10-17 竖屏 ZOOM 黑底退役（用户实锤「竖屏没背景图」）：一律透明。
     *   ZOOM 填满模式视频本体盖住列区（底衬不可见不影响），列外/信息区/进度条
     *   周围透出模糊镜像——与 FIT 同口径。R160 老红线的防黑窗已由换主时机
     *   提前+底衬恒在场兜底，黑底反而制造了竖屏纯黑死角。
     * - 手动旋转（rotatedOdd）仍走 CLEAR_NULL 黑边（用户主动转离内容）。
     * - [zoomedOut] R174b：填满页缩到 <1x 时留边区透底衬（跨沿零跳变）。
     */
    fun isVideoPageBackgroundOpaque(useZoom: Boolean, zoomedOut: Boolean = false): Boolean =
        false

    /**
     * 底衬归属是否跨条目换主（[ownerKey] 当前归属 vs [itemKey] 上报方）。
     * 换主 = 新页进场，要求与列内封面占位同帧同图登场（判据并入
     * [isBackdropSnapSwitch] 的 snap 口径）。
     */
    fun isBackdropOwnerSwitch(ownerKey: String?, itemKey: String): Boolean = ownerKey != itemKey

    /**
     * 底衬换图是否走 snap()（同帧切换）而不是 300ms 渐变——三类口径：
     * - 跨条目换主（[ownerSwitch]）：同帧 snap——与列内封面占位同帧同图登场（R160）；
     * - 本条目 live 制度首拍（[liveRegimeStart]，R159b 起播一拍）：封面底衬首次
     *   换成 R89 live 实帧的那一拍，与首帧上屏同拍合并进「占位→画面」——不许
     *   背景换血自成一段可见变化（旧实现轮询固定 delay(500) 才查首拍，起播后
     *   ~0.5s 底衬才换血 = 用户实测「背景跳变」主犯）；
     * - 同条目内 live 帧常规更新（R89，~500ms 一拍）：保持 300ms 短渐变口径。
     */
    fun isBackdropSnapSwitch(ownerSwitch: Boolean, liveRegimeStart: Boolean): Boolean =
        ownerSwitch || liveRegimeStart

    /**
     * R194② 轨道切换窗底衬冻结时长（毫秒）：切媒体 tab/换列的恢复窗内
     * （内容域转场 + 快照恢复 + resumeScroll 回滚 + 封面解码）底衬不采纳
     * 任何换图上报（旧帧冻结在屏），窗收口后当前页一次性重报软切到位——
     * 恢复窗内连环换主（旧页码条目→落位条目→封面→live 首拍，每拍全屏一换）
     * 是用户实测「切 tab 背景疯狂闪烁，一口气加载到最新一帧」的机理链。
     * 覆盖口径：转场 320ms + 回滚/封面典型 200ms 量级。
     */
    const val TRACK_SWITCH_FREEZE_MS = 500L

    /**
     * R194① 底衬渲染降采样目标短边（px）：模糊层以「短边≈本值」的离屏分辨率
     * 渲染再整体放大铺满（源图短边本就 ~120px，≈原生分辨率零细节损失）。
     * 动机：Compose Crossfade 双层并存渲染期间，两张全屏模糊位图（blur 作用于
     * 全屏分辨率）叠加是切换帧的 GPU 掉帧贡献者（用户实测「闪一下就过去了」
     * 的掉帧嫌疑项）；降采样后模糊像素量随分辨率平方下降。
     */
    const val BACKDROP_RENDER_SHORT_EDGE_PX = 144

    /**
     * R194① 底衬渲染降采样倍率：layer 短边 ÷ [BACKDROP_RENDER_SHORT_EDGE_PX]，
     * 至少 1（小屏不升采样）。调用侧：内容按 1/f 尺寸布局 + blur 半径同除 f，
     * 外层 graphicsLayer 以 f 倍放大铺满（RenderEffect 作用于节点自身分辨率，
     * 放大发生在合成期——模糊成本 ∝ (短边/f)²）。
     */
    fun backdropDownsampleFactor(layerShortEdgePx: Float): Float {
        if (layerShortEdgePx <= BACKDROP_RENDER_SHORT_EDGE_PX) return 1f
        return layerShortEdgePx / BACKDROP_RENDER_SHORT_EDGE_PX
    }
}
