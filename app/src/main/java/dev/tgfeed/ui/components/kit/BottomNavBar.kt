package dev.tgfeed.ui.components.kit

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.core.td.MyProfile
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.ui.components.rememberDecodedAvatarImage
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.TopLevelRoute
import dev.tgfeed.ui.theme.TgFeedTokens

/** 底栏底色（InkBlack 纯黑：与沉浸 Feed 同底，不与内容争色） */
// 2026-10-17 用户两轮裁决：①去死黑（InkBlack→OverlayBgStrong 磨砂）②磨砂也不要——
// **完全透明无底**（与右轨/信息区等界面浮层同口径：图标直接浮在画面上）。
// 旧 BarBg 常量与发丝线已移除；图标可读性靠选中色对比（AccentRed/TextMedium）。

/** 竖屏底栏高：56dp（icon 24dp + label 12sp，M3 NavigationBar 紧凑档，design-proposal §2.1） */
internal val BottomBarHeightPortrait = 56.dp

/** 横屏底栏高：44dp icon-only（省 12dp 竖向给媒体——§2.1） */
internal val BottomBarHeightLandscape = 44.dp

/** 横屏 dock 限宽（§2.6：与内容列对齐约 360dp，避免全宽横条在超宽屏孤悬两端） */
private val BottomBarLandscapeMaxWidth = 360.dp

/**
 * 底栏当前让位高度（0.dp = 底栏隐藏）：Feed 底部 chrome（进度细线/底部信息区/
 * 音频卡控件排/图片卡信息区/各一级页列表底衬）读它抬升，与底栏显隐同拍同曲线
 * （220ms emphasized tween）动画——清屏/全屏时底栏退场，内容 chrome 同步落回原位，
 * 不留缝也不跳变。MainActivity 挂载底栏处 provide。
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

/**
 * R226 底部锚定浮动元素统一避让底栏（共享钥匙，全仓复用）：dock 可见时整体抬
 * [LocalBottomBarInset]（竖 56 / 横 44dp——与底栏显隐同拍同曲线动画），dock 隐藏
 * （清屏/全屏/二级页/分屏/PiP）时零偏移、照常贴底。
 *
 * 用户实锤（2026-10-18 全局性遮挡抢点）：音频迷你播放条贴屏底正好埋进三导航图标，
 * 点按先命中导航——屏幕级底部锚定的 overlay/迷你条/胶囊/CTA 一律走本扩展消费
 * inset，**不许各处手写 56/44 数字**（与 88fcb74 sheet 族、R173 chrome 层同一把钥匙）。
 * 卡片/格内底角元素不适用（锚定宿主是卡片不是屏幕，卡内 chrome 已各自消费）。
 */
@Composable
fun Modifier.avoidBottomBar(): Modifier = this.padding(bottom = LocalBottomBarInset.current)

/** 当前方向该用的底栏高度（竖 56 / 横 44；旋转自动跟随） */
@Composable
internal fun bottomBarHeight(): Dp =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        BottomBarHeightLandscape
    } else {
        BottomBarHeightPortrait
    }

/**
 * 底部导航栏（R173 第一刀，design-proposal §2.1）：3 位——首页 / 发现 / 我的。
 *
 * - 「我的」座位 = 账号真头像圆 28dp（getMyProfile 链路，无头像回落首字母圆/
 *   人像图标，同 MineScreen 口径）——抖音式用户入口在底栏右端，单手可达；
 * - 选中态：图标 + label 转 AccentRed（选中语义色），**无指示条**（沉浸 feed
 *   里少一条横线）；未选中 TextMedium；
 * - 点按反馈：scale 0.94（AnimFast=150ms），无 ripple；
 * - R189 双击刷新规则（底栏三键同口径）：单击已激活 = 无操作（原有路由动作不
 *   重放——点已激活页零副作用）；双击已激活 = onTabDoubleTap（刷新该页的流：
 *   首页=当前 feed、发现=refreshDiscovery、我的=profile 重拉，接线在 MainActivity）；
 *   单击未激活 = 原切换路由。判定走 rememberDoubleTapRule 共享窗口状态机；
 * - 高度：竖屏 56dp（icon+label）/ 横屏 44dp icon-only + 360dp dock 居中；
 * - 底完全透明无底色无发丝线（2026-10-17 用户裁决：与其他界面浮层同口径，图标直浮画面）；
 * - 3 位每格热区 ≥110dp（weight 均分），误触率低于 5 位挤排。
 *
 * 显隐/让位（清屏全退、二级页盖顶、分屏隐藏、PiP）由挂载方（MainActivity）
 * 经 AnimatedVisibility + [LocalBottomBarInset] 统一驱动，组件自身不管路由。
 */
@Composable
internal fun BottomNavBar(
    current: TopLevelRoute,
    onTabTap: (TopLevelRoute) -> Unit,
    gateway: TdGateway?,
    // R189 双击已激活页 = 刷新（首页/发现/我的各刷各的流）；默认空实现兼容旧调用
    onTabDoubleTap: (TopLevelRoute) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier
            .widthIn(max = if (landscape) BottomBarLandscapeMaxWidth else Dp.Infinity)
            .fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(bottomBarHeight()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavItem(
                icon = Icons.Outlined.Home,
                label = "首页",
                selected = current == TopLevelRoute.HOME,
                landscape = landscape,
                onClick = { onTabTap(TopLevelRoute.HOME) },
                onRefresh = { onTabDoubleTap(TopLevelRoute.HOME) },
                modifier = Modifier.weight(1f),
            )
            BottomNavItem(
                icon = Icons.Outlined.Explore,
                label = "发现",
                selected = current == TopLevelRoute.DISCOVER,
                landscape = landscape,
                onClick = { onTabTap(TopLevelRoute.DISCOVER) },
                onRefresh = { onTabDoubleTap(TopLevelRoute.DISCOVER) },
                modifier = Modifier.weight(1f),
            )
            BottomNavAvatarSeat(
                gateway = gateway,
                selected = current == TopLevelRoute.MINE,
                landscape = landscape,
                onClick = { onTabTap(TopLevelRoute.MINE) },
                onRefresh = { onTabDoubleTap(TopLevelRoute.MINE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 底栏图标位（首页/发现共用）：24dp 图标 +（竖屏）12sp label；选中 AccentRed
 * 加粗，未选中 TextMedium；点按 scale 0.94（AnimFast）无 ripple。
 * R189：单击已激活 = 无操作（双击才刷新——判定在 rememberDoubleTapRule）。
 */
@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    landscape: Boolean,
    onClick: () -> Unit,
    // R189 双击已激活 = 刷新该页的流
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "barItemScale",
    )
    val tint = if (selected) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium
    // R189 双击刷新规则（底栏三键同口径）：未激活单击=切换（立即）、已激活单击=
    // 无操作、已激活双击=onRefresh——共享窗口状态机，单一实现
    val handleTap = dev.tgfeed.ui.rememberDoubleTapRule(
        active = selected,
        onSwitch = onClick,
        onRefresh = onRefresh,
    )
    Column(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = handleTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        if (!landscape) {
            Text(
                label,
                color = tint,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * 底栏「我的」座位：账号真头像圆 28dp（点 = 进我的页）。
 * 链路与 MineScreen 账号卡同源：TdLibGateway.getMyProfile（网关侧有进程内缓存，
 * 重复组合不重复请求）；无头像回落首字母圆（TgBlue 底白字），再回落人像图标；
 * 演示模式（Mock 网关）/加载中 = 人像图标占位。选中态给头像勾 1.5dp AccentRed
 * 圆环（label 同步转红）。
 * R189：单击已激活 = 无操作、双击已激活 = onRefresh（profile 重拉）。
 */
@Composable
private fun BottomNavAvatarSeat(
    gateway: TdGateway?,
    selected: Boolean,
    landscape: Boolean,
    onClick: () -> Unit,
    // R189 双击已激活 = 刷新（我的页 profile 重拉，接线在 MainActivity）
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 进组合拉一次（网关侧进程内缓存；avatarBytes 引用相等即 Compose 状态去重）
    var profile by remember(gateway) { mutableStateOf<MyProfile?>(null) }
    LaunchedEffect(gateway) {
        profile = (gateway as? TdLibGateway)?.getMyProfile()
    }
    // R194④：解码收编共享件（rememberDecodedAvatarImage——IO 解码纯解码口，
    // 字节来源=profile 快照；旧写法组合期主线程解码）
    val avatarBitmap = rememberDecodedAvatarImage(profile?.avatarBytes)
    val initial = (profile?.name ?: profile?.username)
        ?.firstOrNull { !it.isWhitespace() }
        ?.uppercase()

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "barAvatarScale",
    )
    val tint = if (selected) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium
    // R189 双击刷新规则（与图标位同口径——共享窗口状态机）
    val handleTap = dev.tgfeed.ui.rememberDoubleTapRule(
        active = selected,
        onSwitch = onClick,
        onRefresh = onRefresh,
    )
    Column(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = handleTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .let { if (selected) it.border(1.5.dp, TgFeedTokens.AccentRed, CircleShape) else it },
            contentAlignment = Alignment.Center,
        ) {
            when {
                avatarBitmap != null -> Image(
                    bitmap = avatarBitmap,
                    contentDescription = "我的",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(CircleShape),
                )
                initial != null -> Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(TgFeedTokens.TgBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initial,
                        color = TgFeedTokens.TextHigh,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                else -> Icon(
                    Icons.Outlined.Person,
                    contentDescription = "我的",
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (!landscape) {
            Text(
                "我的",
                color = tint,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
