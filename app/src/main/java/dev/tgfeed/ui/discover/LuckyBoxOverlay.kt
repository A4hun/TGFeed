package dev.tgfeed.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.R
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay

/**
 * 发现页 v3 定稿「手气不错」开盲盒全屏 overlay（A2 精致大片风配套）。
 *
 * 交互流程（由 DiscoverScreen 驱动，本件只负责呈现与节律）：
 *  1. 点骰子 → 全屏纯黑底 + AI 盲盒图（霓虹红盒体）进入摇动段：盒体 ±6° 快速摇动
 *     （AnimFast 半摆）+ 1.0↔1.07 脉冲缩放（AnimSlow 呼吸）+ 红色辉光晕同步脉动，
 *     总长 [LUCKY_BOX_SHAKE_MS] = 5 × AnimSlow = 1.6s（落在产品档 1.2~1.8s 窗内，
 *     节律全程走 TgFeedTokens 动效档，不造第二种速度）；
 *     摇动期间点屏幕任意处 = 跳过等待直接揭示（手感响应优先，仪式感不打折）；
 *  2. 揭示段：卡片淡入 + 轻微放大（fadeIn AnimMed + scaleIn AnimSlow）——
 *     封面 16:9（抽盒瞬间已并行预取的真缩略图；未就位回落频道稳定色对角渐变，
 *     与候选卡同一兜底口径）+ 频道名压渐变左下（A2 大标题位）+ 成员数芯片压图右上
 *     （A2 时长胶囊位：深底描边小胶囊）+「手气如何？」+ 霓虹红「直接试吃」主按钮；
 *  3. 「直接试吃」→ onTaste（调用方：试吃留痕 + 打开频道 Feed）；点空白/系统返回 = 关闭。
 *
 * 覆盖层为不透明纯黑全屏（页面最顶层声明），状态栏/导航条区域一并盖住；
 * 系统返回键收编在 DiscoverScreen 侧（后声明的 enabled BackHandler 优先）。
 *
 * @param candidate 抽中的候选频道（来自当前候选池，VM 已过滤已加入/已屏蔽/软拒绝）
 * @param revealed false = 摇动段；true = 揭示段（时序由 [onShakeDone] 回调驱动）
 * @param coverImage 预取到的封面真图（null = 未就位/无封面，回落渐变色块）
 * @param coverFallbackColor 封面兜底的频道稳定色（与候选卡 DiscoverThumbFallback 同口径取色）
 * @param memberCountText 成员数预格式化文本（null = 未知，封面右上芯片不渲染）
 * @param onShakeDone 摇动时长耗尽（或用户点屏跳过）→ 调用方把 revealed 置 true（幂等）
 * @param onTaste 「直接试吃」
 * @param onDismiss 点空白关闭（揭示段）
 */
@Composable
fun LuckyBoxOverlay(
    candidate: ChannelBrief,
    revealed: Boolean,
    coverImage: ImageBitmap?,
    coverFallbackColor: Color,
    memberCountText: String?,
    onShakeDone: () -> Unit,
    onTaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 摇动段计时：时长耗尽自动揭示；revealed 翻 true 后本分支退出组合，计时器自然取消
    if (!revealed) {
        LaunchedEffect(candidate.id) {
            delay(LUCKY_BOX_SHAKE_MS)
            onShakeDone()
        }
    }
    // 根容器：不透明纯黑全屏；摇动段点屏 = 跳过等待，揭示段点空白 = 关闭。
    // 无 ripple（indication = null）：全屏手势不画波纹，仪式感不碎
    val rootInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack)
            .clickable(interactionSource = rootInteraction, indication = null) {
                if (revealed) onDismiss() else onShakeDone()
            }
            // 系统条区域也盖黑（background 在上游已全屏），padding 只约束内容落位
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (!revealed) {
            LuckyBoxShaking(Modifier.align(Alignment.Center))
        }
        AnimatedVisibility(
            visible = revealed,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(TgFeedTokens.AnimMed)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(TgFeedTokens.AnimSlow)),
        ) {
            LuckyBoxRevealCard(
                candidate = candidate,
                coverImage = coverImage,
                coverFallbackColor = coverFallbackColor,
                memberCountText = memberCountText,
                onTaste = onTaste,
            )
        }
    }
}

/** 摇动段：红色辉光晕 + 盲盒图（摇动 + 脉冲）+ 提示文案 */
@Composable
private fun LuckyBoxShaking(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "luckyBoxShake")
    // 盒体摇动：±6° 快速来回（AnimFast 半摆 = 急促的开箱前摇感）
    val rotation by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(TgFeedTokens.AnimFast, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "luckyBoxRotation",
    )
    // 脉冲缩放：1.0 ↔ 1.07（AnimSlow 呼吸档，与全 App 骨架脉动同节律）
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(TgFeedTokens.AnimSlow),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "luckyBoxPulse",
    )
    // 辉光晕同步脉动（token 红派生 alpha，不造新色）
    val glowAlpha by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(
            animation = tween(TgFeedTokens.AnimSlow),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "luckyBoxGlow",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            // 红色辉光晕：盒体背后的径向渐变圆，随脉冲呼吸（A2 霓虹红场域）
            Box(
                Modifier
                    .size(300.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                TgFeedTokens.AccentRed.copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Image(
                painter = painterResource(R.drawable.discover_box),
                contentDescription = "盲盒",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(240.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                        scaleX = pulse
                        scaleY = pulse
                    },
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapLg))
        Text(
            "手气不错，开箱中…",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        Spacer(Modifier.height(TgFeedTokens.GapXs))
        Text(
            "点屏幕直接揭晓",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** 揭示段卡片：封面（名压左下 / 成员数芯片压右上）+「手气如何？」+「直接试吃」 */
@Composable
private fun LuckyBoxRevealCard(
    candidate: ChannelBrief,
    coverImage: ImageBitmap?,
    coverFallbackColor: Color,
    memberCountText: String?,
    onTaste: () -> Unit,
) {
    // 卡片本体吞掉点击（点卡不关盒；关闭走空白区，与文案"点空白处关闭"一致）
    val cardInteraction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth(0.86f)
            .widthIn(max = 360.dp)
            .clickable(interactionSource = cardInteraction, indication = null) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---------- 封面 16:9：真图优先，无图回落频道稳定色对角渐变（与候选卡同一兜底口径） ----------
        val coverShape = RoundedCornerShape(TgFeedTokens.RadiusLg)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(coverShape)
                .background(TgFeedTokens.Surface)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, coverShape),
        ) {
            if (coverImage != null) {
                Image(
                    bitmap = coverImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(coverFallbackColor.copy(alpha = 0.85f), TgFeedTokens.InkBlack),
                            ),
                        ),
                )
            }
            // R162 零遮罩：底部渐变遮罩（与候选卡封面同参数）删除——压图频道名改
            // withOverlayShadow 描边可读（视频页干净基准同款），封面原色不染
            // 成员数芯片压图右上（A2 时长胶囊位：深底描边小胶囊）
            if (memberCountText != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(TgFeedTokens.GapSm)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.OverlayBgStrong)
                        .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .padding(horizontal = TgFeedTokens.GapSm, vertical = 2.dp),
                ) {
                    Text(
                        memberCountText,
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            // 频道名压封面左下（A2 大标题位；两行封顶，省略号收尾）。
            // R162：可读性走描边（渐变遮罩已删）
            Text(
                candidate.title,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleLarge.withOverlayShadow(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(TgFeedTokens.GapMd),
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapLg))
        Text(
            "手气如何？",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        // 「直接试吃」：A2 霓虹红主按钮（页面唯一红 CTA，与骰子同锚点色）
        Button(
            onClick = onTaste,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
            colors = ButtonDefaults.buttonColors(
                containerColor = TgFeedTokens.AccentRed,
                contentColor = Color.White,
            ),
        ) {
            Text("直接试吃", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        Text(
            "点空白处关闭",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** 盲盒摇动时长：5 × AnimSlow(320ms) = 1.6s，落在产品档 1.2~1.8s 窗内（节律不造第二种速度） */
private val LUCKY_BOX_SHAKE_MS = TgFeedTokens.AnimSlow * 5L
