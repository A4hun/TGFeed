package dev.tgfeed.ui.components.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.SheetMaxWidth
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 拖拽关窗阈值：面板被拖离落座的行程占比超过它即判意图为关窗（回座与关窗的分界） */
private const val SHEET_DRAG_DISMISS_FRACTION = 0.25f

/** 段落进场位移：8dp（design-proposal §4.3「fadeIn(150ms) + slideIn 8dp(220ms)」） */
private val SheetStaggerSlide = 8.dp

/** 抽屉左缘抓取条宽：整高可横向拖拽关窗的「左缘」热区（窄条不叠内容，零手势冲突） */
private val DrawerEdgeStrip = 24.dp

/**
 * 面板锚定形态（R173 裁决：**按触发点就近锚定**——触发点在顶栏（☰ 汉堡）→ 左抽屉；
 * 触发点在屏幕下方（长按面板/动作格）→ 底部 sheet。此原则入动效规范，新弹层照此选形态）。
 */
enum class SheetAnchor {
    /** 底部 sheet：从屏底上滑（触发点在屏幕下方的动作） */
    BOTTOM,

    /** 左侧抽屉：从左缘滑入、全高（☰ 汉堡的标准语义——Material 抽屉） */
    LEFT,
}

/**
 * AppSheetMotion 内容侧关窗 API：[dismiss] 请求关窗（面板先播退场动画再真正离开组合）；
 * [dismiss] 的参数 afterClosed = 面板完全收起后、离开组合前的收尾位——「关窗过渡到
 * 下一个面板」的链路（源面板→内容筛选/手气不错）在这里接，两个面板永不叠层。
 */
internal interface SheetDismissApi {
    fun dismiss(afterClosed: () -> Unit = {})
}

/**
 * AppSheet 动效壳（R173，design-proposal §4.3 + 触发点就近锚定裁决）：与 [AppSheet]
 * 同一视觉壳（[AppSheetHandle] 拖拽条 / [AppSheetTitleRow] 标题行 / SheetMaxWidth
 * 限宽 / RadiusLg 圆角 / Surface 底色全部共享件），进出动效按提案规范执行：
 *
 * - 面板滑入/滑出：spring(0.88/420)（chrome 无过冲临界，观感 ≈350ms）——
 *   [SheetAnchor.BOTTOM] 沿 Y 轴从屏底上滑；[SheetAnchor.LEFT] 沿 X 轴从左缘滑入
 *   （☰ 汉堡语义 = 抽屉；用户实锤「触发点在左上、面板从底下弹很奇怪」后的裁决）；
 * - scrim 收编 R162 token OverlayBg：进场 tween 180ms（先快后慢）、退场 150ms（利落）；
 * - 段落 stagger：内容侧 [SheetStaggeredSection]（0/+40/+80ms 三段），退场无 stagger；
 * - 底部形态高度上限 [MotionTokens.SheetMaxViewportFraction]（65vh）；抽屉形态全高、
 *   宽度 min(92% 屏宽, SheetMaxWidth)、内容让开状态栏；
 * - 关窗手势：底部形态 = 头部抓取区（拖拽条+标题行）下拉；抽屉形态 = 头部抓取区 +
 *   左缘整高抓取条横拖。**朝锚定边方向**拖过阈值即关窗（拖拽释放后续接同一枚 spring
 *   从当前位置滑出——手势延续，不走第二段动画），未过阈值 spring 回座。
 *
 * 生命周期契约：调用方 `if (open) XxxSheet(onDismiss = { open = false })` 挂载即进场；
 * 所有关窗路径（×/遮罩点击/系统返回/拖拽）都在壳内先播完退场动画**再**回调
 * [onDismiss]——「先收再开」弹层纪律（design-redesign §3.3）由壳统一保证。
 *
 * 与 [AppSheet] 的分工：AppSheet 走 M3 ModalBottomSheet（dialog 窗口、系统级
 * 惯性拖拽），适合普通面板；本壳为提案点名的 sheet 动效规范件（源面板 R173 首个
 * 消费者，抽屉形态），其余面板后续按表逐个迁移，不在本刀强推。
 */
@Composable
internal fun AppSheetMotion(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    anchor: SheetAnchor = SheetAnchor.BOTTOM,
    content: @Composable ColumnScope.(SheetDismissApi) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // 面板位置：0=落座，1=整幅沉在锚定侧之外（进场自 1 起，退场归 1）
    val panelOffset = remember { Animatable(1f) }
    // scrim 透明度：与面板 spring 各走各的时长曲线（180 进/150 退，先快后慢/先慢后快）
    val scrimAlpha = remember { Animatable(0f) }
    // 面板沿滑入轴的实测长度 px（底部=高，抽屉=宽）；测得之前先把面板顶出屏外（防首帧闪现）
    var panelSpanPx by remember { mutableStateOf(0) }
    // 退场中标记：动画已在播，后续关窗请求一律吞掉（防双动画/双回调）
    var dismissing by remember { mutableStateOf(false) }
    val sheetSpring = spring<Float>(
        dampingRatio = MotionTokens.SheetSpringDamping,
        stiffness = MotionTokens.SheetSpringStiffness,
    )

    // 进场：scrim 与面板同拍启动（面板 spring 前段快，观感上 scrim 先就位）
    LaunchedEffect(Unit) {
        launch {
            scrimAlpha.animateTo(1f, tween(MotionTokens.SheetScrimInMs, easing = FastOutSlowInEasing))
        }
        panelOffset.animateTo(0f, sheetSpring)
    }

    // 统一退场入口（×/遮罩/返回键/拖拽过阈值共用）：动画从当前位置续播到全出，
    // 落定后才回调 onDismiss（+可选收尾）。afterClosed = 完全收起后的「先收再开」挂点
    fun dismissNow(afterClosed: () -> Unit = {}) {
        if (dismissing) return
        dismissing = true
        keyboard?.hide()
        scope.launch {
            launch {
                scrimAlpha.animateTo(0f, tween(MotionTokens.SheetScrimOutMs, easing = FastOutLinearInEasing))
            }
            panelOffset.animateTo(1f, sheetSpring)
            onDismiss()
            afterClosed()
        }
    }

    // 内容侧关窗 API（薄包装，晚于 dismissNow 声明以便捕获）
    val sheetApi = remember {
        object : SheetDismissApi {
            override fun dismiss(afterClosed: () -> Unit) = dismissNow(afterClosed)
        }
    }

    // 系统返回 = 关窗（与 ModalBottomSheet 同语义；声明在页内 BackHandler 之后，优先接管）
    BackHandler(onBack = { dismissNow {} })

    Box(modifier.fillMaxSize()) {
        // scrim：R162 OverlayBg token（P2 红线——合法 chrome 只准用共享 token），
        // 点击即关窗；alpha 走 draw 相位读值，退场期间二次点击由 dismissing 吞掉
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(TgFeedTokens.OverlayBg)
                .pointerInput(Unit) { detectTapGestures { dismissNow {} } },
        )
        if (anchor == SheetAnchor.LEFT) {
            DrawerPanel(
                scope = scope,
                title = title,
                panelOffset = panelOffset,
                panelSpanPx = panelSpanPx,
                onSpanMeasured = { panelSpanPx = it },
                isDismissing = { dismissing },
                sheetSpring = sheetSpring,
                dismissNow = ::dismissNow,
                content = content,
                sheetApi = sheetApi,
            )
        } else {
            BottomPanel(
                scope = scope,
                title = title,
                panelOffset = panelOffset,
                panelSpanPx = panelSpanPx,
                onSpanMeasured = { panelSpanPx = it },
                isDismissing = { dismissing },
                sheetSpring = sheetSpring,
                dismissNow = ::dismissNow,
                content = content,
                sheetApi = sheetApi,
            )
        }
    }
}

/**
 * 底部 sheet 面板（[SheetAnchor.BOTTOM]）：限宽→顶圆角→Surface→拖拽条→标题行→内容；
 * 高度上限 65vh；头部抓取区（拖拽条+标题行）下拉关窗。
 */
@Composable
private fun BoxScope.BottomPanel(
    scope: CoroutineScope,
    title: String?,
    panelOffset: Animatable<Float, *>,
    panelSpanPx: Int,
    onSpanMeasured: (Int) -> Unit,
    isDismissing: () -> Boolean,
    sheetSpring: SpringSpec<Float>,
    dismissNow: (afterClosed: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(SheetDismissApi) -> Unit,
    sheetApi: SheetDismissApi,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .widthIn(max = SheetMaxWidth)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = if (panelSpanPx > 0) panelOffset.value * panelSpanPx else 10_000f
            }
            .onSizeChanged { onSpanMeasured(it.height) }
            .clip(RoundedCornerShape(topStart = TgFeedTokens.RadiusLg, topEnd = TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.Surface)
            // 65vh 内容上限（§4.2：内容瘦身后 sheet 回到面板体量）
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * MotionTokens.SheetMaxViewportFraction).dp),
    ) {
        // 头部抓取区：拖拽条 + 标题行，下拉关窗手势只挂这里——
        // 面板内部的列表滚动/输入框选区不与本手势打架（全表面拖拽需 nestedScroll
        // 消歧，超出本刀，挂账见 r173_notes）
        Column(
            Modifier.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (panelSpanPx > 0 && !isDismissing()) {
                            // 下拉（dragAmount>0）= 增大 offset = 关窗方向
                            val next = (panelOffset.value + dragAmount / panelSpanPx).coerceIn(0f, 1f)
                            scope.launch { panelOffset.snapTo(next) }
                        }
                    },
                    onDragEnd = {
                        if (!isDismissing()) {
                            scope.launch {
                                if (panelOffset.value > SHEET_DRAG_DISMISS_FRACTION) {
                                    // 过阈值：从当前位置续接退场（手势延续，同一枚 spring）
                                    dismissNow {}
                                } else {
                                    // 未过阈值：与进场同一枚 spring 回座
                                    panelOffset.animateTo(0f, sheetSpring)
                                }
                            }
                        }
                    },
                )
            },
        ) {
            AppSheetHandle()
            if (title != null) {
                AppSheetTitleRow(title = title, onClose = { dismissNow {} })
                Spacer(Modifier.height(TgFeedTokens.GapSm))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                // 先 nav 后 ime：inset 消费链自动去重叠（键盘弹起时不会 nav+ime 双垫）
                .navigationBarsPadding()
                .imePadding()
                // 2026-10-17 弹窗被透明 dock 挡底修复：sheet 族消费 dock inset（同 AppSheet）
                .padding(bottom = LocalBottomBarInset.current),
            content = { content(sheetApi) },
        )
    }
}

/**
 * 左侧抽屉面板（[SheetAnchor.LEFT]，R173 裁决：☰ 触发点在顶栏左缘 → 抽屉就近锚定）：
 * 全高、宽 min(92% 屏宽, SheetMaxWidth)、右缘圆角、内容让开状态栏；左缘整高抓取条 +
 * 头部抓取区横拖关窗（朝锚定边=向左拖过阈值关窗，Material 抽屉标准语义）。
 */
@Composable
private fun BoxScope.DrawerPanel(
    scope: CoroutineScope,
    title: String?,
    panelOffset: Animatable<Float, *>,
    panelSpanPx: Int,
    onSpanMeasured: (Int) -> Unit,
    isDismissing: () -> Boolean,
    sheetSpring: SpringSpec<Float>,
    dismissNow: (afterClosed: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(SheetDismissApi) -> Unit,
    sheetApi: SheetDismissApi,
) {
    // 横拖手势（左缘条与头部抓取区共用）：向左拖 = 增大 offset = 关窗方向
    val onHorizontalDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Float) -> Unit =
        { change, dragAmount ->
            change.consume()
            if (panelSpanPx > 0 && !isDismissing()) {
                val next = (panelOffset.value - dragAmount / panelSpanPx).coerceIn(0f, 1f)
                scope.launch { panelOffset.snapTo(next) }
            }
        }
    val onHorizontalDragEnd: () -> Unit = {
        if (!isDismissing()) {
            scope.launch {
                if (panelOffset.value > SHEET_DRAG_DISMISS_FRACTION) {
                    // 过阈值：从当前位置续接退场（手势延续，同一枚 spring；偏置标记由 dismissNow 自置）
                    dismissNow {}
                } else {
                    panelOffset.animateTo(0f, sheetSpring)
                }
            }
        }
    }
    val dragModifier = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onHorizontalDrag = onHorizontalDrag,
            onDragEnd = onHorizontalDragEnd,
        )
    }

    Row(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.92f)
            .widthIn(max = SheetMaxWidth)
            .graphicsLayer {
                translationX = if (panelSpanPx > 0) -panelOffset.value * panelSpanPx else -10_000f
            }
            .onSizeChanged { onSpanMeasured(it.width) }
            .clip(RoundedCornerShape(topEnd = TgFeedTokens.RadiusLg, bottomEnd = TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.Surface),
    ) {
        // 左缘整高抓取条（「抽屉左缘滑关」热区）：窄条在内容旁不叠加，零手势冲突
        Box(
            Modifier
                .width(DrawerEdgeStrip)
                .fillMaxHeight()
                .then(dragModifier),
        )
        Column(
            Modifier
                .fillMaxHeight()
                // 抽屉全高：头部与内容一起让开状态栏（拖拽条不钻进系统栏底下）
                .statusBarsPadding(),
        ) {
            // 头部抓取区（拖拽条+标题行）横拖关窗（与左缘条同一手势语义）
            Column(dragModifier) {
                AppSheetHandle()
                if (title != null) {
                    AppSheetTitleRow(title = title, onClose = { dismissNow {} })
                    Spacer(Modifier.height(TgFeedTokens.GapSm))
                }
            }
            Column(
                Modifier
                    .fillMaxHeight()
                    // 导航条（底）与键盘让位
                    .navigationBarsPadding()
                    .imePadding(),
                content = { content(sheetApi) },
            )
        }
    }
}

/**
 * sheet 段落进场（design-proposal §4.3 stagger）：三段 t=0/+40/+80ms，每段
 * fadeIn(AnimFast=150ms) + 8dp 位移(AnimMed=220ms)；段内整行同拍登场，不逐 chip。
 * 退场无 stagger（ExitTransition.None）——内容跟随面板整体滑出，「退场要利落」。
 *
 * 实现注记：MutableTransitionState(false→true) 的一次性进场模式（官方用法），
 * 面板每次挂载（每次开窗会话）都会重播各自的 stagger。
 */
@Composable
internal fun SheetStaggeredSection(delayMs: Int, content: @Composable () -> Unit) {
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val slidePx = with(LocalDensity.current) { SheetStaggerSlide.roundToPx() }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(TgFeedTokens.AnimFast, delayMillis = delayMs)) +
            slideInVertically(
                tween(TgFeedTokens.AnimMed, delayMillis = delayMs, easing = FastOutSlowInEasing),
            ) { slidePx },
        exit = ExitTransition.None,
    ) {
        content()
    }
}
