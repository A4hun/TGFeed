package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.ui.SheetMaxWidth
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------- 双击飘心：多心爆散层（抖音观感） ----------------
// （R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动；与 LikeHearts.kt 的
//   单心版 [HeartBurst]/[LikeHeartsLayer] 是两套并存实现——本层是视频页专用的
//   多心爆散版，粒度更细（大小/倾角/横漂/上浮/时长各自随机））
// R138 起本文件扩为"点赞可见反馈"家族文件（design-redesign.md §2.3 P0-③）：
//   爆散心含 👍 emoji 粒子分支（回写开启时混入，区别纯本地赞），
//   并收纳场景化引导气泡 [LikeWritebackGuideBubble]、送达胶囊 [LikeWritebackSyncedPill]、
//   状态查询面板 [LikeWritebackStatusSheet]；回写状态的派发与观测在 VideoPlayerListener.kt。

/**
 * 一颗爆散心的参数（x/y 为手势层内像素坐标，即双击落点）。
 * 每颗心自带随机大小/倾角/横向漂移/上浮高度/时长，互不相同的飘散轨迹
 * 正是抖音"一次双击炸出一簇心"的观感来源。
 */
internal class HeartParticle(
    val id: Long,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val sizeDp: Float,
    val driftDp: Float,
    val riseDp: Float,
    val durationMs: Int,
    /** R138：null = 红心（既有观感不变）；"👍" = 表情粒子（回写开启时混入的一两颗） */
    val glyph: String? = null,
)

/**
 * 双击爆散的一簇粒子（R138 从 VideoPage 内联构造收编进本文件，分布口径零改动）：
 * 3-5 颗，第一颗是给足存在感的主心（大），其余为陪衬小心，各自错开消散。
 * [writebackOn]（回写总闸开启）时陪衬心里随机混 1-2 颗 👍 emoji 粒子并略放大——
 * 主心仍是红心：👍 是"这一下赞到了频道"的混音，不是整体换肤。
 */
internal fun likeBurstParticles(x: Float, y: Float, writebackOn: Boolean): List<HeartParticle> {
    val count = Random.nextInt(3, 6)
    // 👍 只落在陪衬位（1..count-1）：主心位恒为红心，纯本地赞与回写赞的观感锚点一致
    val thumbs = if (writebackOn) {
        (1 until count).shuffled().take(Random.nextInt(1, 3)).toSet()
    } else {
        emptySet()
    }
    return List(count) { i ->
        HeartParticle(
            id = Random.nextLong(),
            x = x,
            y = y,
            rotation = Random.nextInt(-28, 29).toFloat(),
            sizeDp = when {
                i == 0 -> Random.nextInt(84, 105).toFloat()
                i in thumbs -> Random.nextInt(56, 80).toFloat() // 👍 略大于普通陪衬心，混入可辨
                else -> Random.nextInt(44, 72).toFloat()
            },
            driftDp = Random.nextInt(-90, 91).toFloat(),
            riseDp = Random.nextInt(150, 260).toFloat(),
            durationMs = Random.nextInt(680, 1100),
            glyph = if (i in thumbs) "👍" else null,
        )
    }
}

/** 双击爆心层：逐颗渲染，动画走完回调移除 */
@Composable
internal fun HeartBurstLayer(
    hearts: List<HeartParticle>,
    onHeartDone: (Long) -> Unit,
) {
    hearts.forEach { heart ->
        BurstHeart(heart, onHeartDone)
    }
}

/**
 * 单颗爆散心：出场过冲弹跳（0.3x→1.35x→1x），随进度横向漂移+上浮，
 * 倾角缓慢回正，末段 30% 淡出后回调移除。动画时长由粒子自带（错开消散）。
 * R138：glyph 非空时走 emoji 绘制分支（文字阴影替代图标投影），轨迹/缩放/淡出与红心同一套物理。
 */
@Composable
private fun BurstHeart(heart: HeartParticle, onDone: (Long) -> Unit) {
    val progress = remember(heart.id) { Animatable(0f) }
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(heart.id) {
        progress.animateTo(
            1f,
            tween(durationMillis = heart.durationMs, easing = FastOutSlowInEasing),
        )
        currentOnDone(heart.id)
    }
    val p = progress.value
    // 爆散物理与中心对齐（心的中心对准双击点；随进度横向漂移 + 上浮）两分支共用
    val placement = Modifier
        .offset {
            IntOffset(
                (heart.x - heart.sizeDp.dp.toPx() / 2f +
                    heart.driftDp.dp.toPx() * p).roundToInt(),
                (heart.y - heart.sizeDp.dp.toPx() / 2f -
                    heart.riseDp.dp.toPx() * p).roundToInt(),
            )
        }
        .size(heart.sizeDp.dp)
    if (heart.glyph == null) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = LikeRed,
            // 柔光投影让红心压在亮画面上也立体；放在 graphicsLayer 前，弹跳连同投影一起动
            modifier = placement
                .overlayIconShadow()
                .burstAnim(heart, p),
        )
    } else {
        // emoji 粒子：固定框内居中绘制（中心对齐口径与红心一致），
        // 亮画面可读性走 OverlayTextShadow（emoji 自带颜色，不另上色）
        Box(
            modifier = placement.burstAnim(heart, p),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = heart.glyph,
                fontSize = (heart.sizeDp * 0.78f).sp,
                style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
            )
        }
    }
}

/** 爆散的缩放/倾角/淡出物理（两分支共用）：快速过冲到 1.35x 回落 1x，尾段缓缩淡出 */
private fun Modifier.burstAnim(heart: HeartParticle, p: Float): Modifier = graphicsLayer {
    // 缩放曲线：快速过冲到 1.35x，回落 1x，尾段缓缩到 0.87x
    val scale = when {
        p < 0.18f -> 0.3f + p / 0.18f * 1.05f
        p < 0.35f -> 1.35f - (p - 0.18f) / 0.17f * 0.35f
        else -> 1f - (p - 0.35f) * 0.2f
    }
    scaleX = scale
    scaleY = scale
    // 倾角随飘散缓慢回正一半，多颗心不会一直歪得乱
    rotationZ = heart.rotation * (1f - p * 0.5f)
    alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
}

// ---------------- 点赞回写可见反馈（R138，design-redesign.md §2.3） ----------------

/**
 * 送达胶囊：回写网关受理成功时顶部闪现"已同步到频道"——回写成功的可感知反馈。
 * （👍 粒子只表达"已开启并发出"；成败以本胶囊为准。失败仍静默不打扰，可长按点赞钮查。）
 * 与 BoostStatusPill 同槽位同规格（顶部胶囊家族，VideoPageOverlays.kt）。
 */
@Composable
internal fun BoxScope.LikeWritebackSyncedPill() {
    Text(
        "👍 已同步到频道",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 56.dp)
            // R162 收敛共享 token（原 0.55 散装字面量）
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * 场景化引导气泡（§2.3.1）：首次双击点赞（回写决策未做）时，在双击落点上方浮出
 * 一次性轻引导——知情同意放在行为现场，而不是指望设置页里 11sp 灰字。
 * 非阻断：不拦手势不模态，8 秒未选择自动淡出（不替用户做决定，下次首次双击再现；
 * 超时与"划走即收"由调用方持有锚点状态）。
 * [仅此点赞] = 本条保持纯本地且不再问；[开启回写] = 打开总闸（本条刚点的赞随即补发）。
 */
@Composable
internal fun BoxScope.LikeWritebackGuideBubble(
    anchor: Offset,
    pageWidthPx: Int,
    onEnableWriteback: () -> Unit,
    onKeepLocal: () -> Unit,
) {
    // 先量后摆：measure/layout 先于 draw，第一帧 onSizeChanged 已回——气泡水平居中于
    // 双击落点、垂直悬在其上方；落点贴近屏顶时翻到落点下方，水平方向夹在页内不越界
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(TgFeedTokens.AnimMed))
    }
    val maxBubbleWidth = with(LocalDensity.current) { (pageWidthPx - 24).coerceAtLeast(200).toDp() }
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .onSizeChanged { bubbleSize = it }
            .graphicsLayer {
                // 结果揭示族动效（§3.4）：fade + 0.92→1 缩放，AnimMed 节律
                alpha = appear.value
                val s = 0.92f + 0.08f * appear.value
                scaleX = s
                scaleY = s
            }
            .offset {
                val margin = 12.dp.toPx()
                val w = bubbleSize.width
                val x = if (w > 0 && pageWidthPx > w + margin * 2) {
                    (anchor.x - w / 2f).coerceIn(margin, pageWidthPx - w - margin)
                } else {
                    anchor.x
                }
                val aboveY = anchor.y - bubbleSize.height - 24.dp.toPx()
                val y = if (aboveY < 48.dp.toPx()) anchor.y + 48.dp.toPx() else aboveY
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .background(TgFeedTokens.SurfaceHigh.copy(alpha = 0.94f), RoundedCornerShape(TgFeedTokens.RadiusMd))
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusMd))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .widthIn(max = maxBubbleWidth),
    ) {
        Text(
            "让 👍 回到频道？",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "开启后你的赞会公开显示在原频道，频道主可见。",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onKeepLocal) {
                Text("仅此点赞", color = TgFeedTokens.TextMedium)
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onEnableWriteback,
                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TgFeedTokens.AccentRed,
                    contentColor = Color.White,
                ),
            ) {
                Text("开启回写", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 状态查询面板（§2.3.3）：长按点赞钮的轻量出口——本条回写状态结论先行
 * （已送达 / 未开启 / 失败静默 / 尚无记录），按状态给 [去 Telegram 看] / [关闭回写] / [开启回写]。
 * 设置页开关保留做总闸：本面板的启停按钮写的正是同一 prefs 键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LikeWritebackStatusSheet(
    status: LikeWritebackStatus,
    writebackOn: Boolean,
    realGateway: Boolean,
    liked: Boolean,
    channelTitle: String,
    onEnableWriteback: () -> Unit,
    onDisableWriteback: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 平板上限宽居中（与 VideoActionSheet 同规格）；R171：字面量收敛进 Responsive.SheetMaxWidth
        sheetMaxWidth = SheetMaxWidth,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = TgFeedTokens.RadiusLg, topEnd = TgFeedTokens.RadiusLg),
        containerColor = TgFeedTokens.Surface,
        contentColor = TgFeedTokens.TextHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                // R226 sheet 族漏网补齐（88fcb74 同款钥匙）：长按点赞状态 sheet 从
                // Feed 视频右轨就地开——dock 可见域，内容消费 inset 抬到栏上沿
                .padding(bottom = LocalBottomBarInset.current + 16.dp),
        ) {
            Text("点赞回写 · 让 👍 回到频道", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // 结论先行：一句话说清本条状态，不说实现词
            val line = when {
                !writebackOn && status == LikeWritebackStatus.DELIVERED ->
                    "回写已关闭。这条此前送出的 👍 仍留在频道消息上，可在 Telegram 客户端里手动移除。"
                !writebackOn ->
                    if (liked) "回写未开启——这条的赞只保留在本机，不会发到频道。"
                    else "回写未开启——你的赞只会保留在本机，不会发到频道。"
                !realGateway -> "演示模式没有真实频道，点赞不会发送。"
                status == LikeWritebackStatus.DELIVERED ->
                    "已给「$channelTitle」这条消息点了 👍，在 Telegram 里对频道公开可见。"
                status == LikeWritebackStatus.IN_FLIGHT -> "正在把 👍 送给频道…"
                status == LikeWritebackStatus.FAILED ->
                    "这条的 👍 没能送到频道（网络抖动或频道不开放表情回应），本地点赞不受影响。"
                else ->
                    if (liked) "这条的赞点在回写开启之前，还没补送——取消赞再重新双击即可再试。"
                    else "这条还没有回写过。双击视频点赞后，👍 会同步给原频道。"
            }
            Text(line, color = TgFeedTokens.TextMedium, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                // 已送达：给"去 Telegram 看"原频道消息的出口（品牌蓝主按钮）
                if (status == LikeWritebackStatus.DELIVERED) {
                    Button(onClick = onOpenInTelegram, shape = RoundedCornerShape(TgFeedTokens.RadiusPill)) {
                        Text("去 Telegram 看")
                    }
                }
                when {
                    // 总闸开着：面板内直接可关（写同一 prefs，设置页同步反映）
                    writebackOn -> TextButton(onClick = onDisableWriteback) {
                        Text("关闭回写", color = TgFeedTokens.TextMedium)
                    }
                    // 总闸关着且本条未送达过：长按出口也是开启出口（红色主行动）
                    status != LikeWritebackStatus.DELIVERED -> Button(
                        onClick = onEnableWriteback,
                        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TgFeedTokens.AccentRed,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("开启回写")
                    }
                }
            }
        }
    }
}
