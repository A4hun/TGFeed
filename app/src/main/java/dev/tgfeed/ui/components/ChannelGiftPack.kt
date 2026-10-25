package dev.tgfeed.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.plaza.PlazaPack
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.ChannelPackJoin
import dev.tgfeed.core.td.ChannelPackPreview
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppSheet
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 频道礼包·礼物盒物种（R137 消费侧，design-redesign.md §2.1）：
 * 礼包不再是"一行文字 + TextButton"，而是热榜页里的特殊视觉物种——
 * 有盒盖封面（频道头像堆叠 + 星黄渐变底 + 频道数徽标）、有"拆开看看"仪式
 * （先看内容再收，AppSheet 预览成员清单）、有开盒反馈（收下成功卡片翻面
 * scale+fade 600ms → "已收下 ✓"），失败态一律落在面板内的错误行（原因 + 重试）。
 *
 * 本文件只放消费侧组件；生产侧（合集 → 礼包）面板见 CollectionsDialog.kt
 * 与 PackShareSheet。色彩全部走 TgFeedTokens，语义色按设计规范锁定：
 * StarYellow = 礼盒/新鲜，SuccessGreen = 已收下/已在列表，AccentRed = 主按钮。
 */

/** 礼包免加入预览的 UI 态（消费侧）：Loading / 就绪 / 链接失效或网络失败 */
internal sealed interface PackPeekUi {
    data object Loading : PackPeekUi
    data class Ready(val preview: ChannelPackPreview) : PackPeekUi
    data object Failed : PackPeekUi
}

/** 已收下记忆（url 维度，会话级）：礼包名 + 本次新收频道数（0 = 此前已全收） */
internal data class PackClaimedUi(
    val folderName: String,
    val chatCount: Int,
)

/**
 * 礼物盒卡：满宽特殊物种卡（§3.2 二档）——顶部 2:1 盒盖封面区 + 礼包名/安利文案 +
 * 「N 人分享过」社交证明 + 全宽红主按钮「拆开看看」。收下成功后整卡翻面成
 * 「已收下 ✓」态（不再显示按钮）。peek 未就绪时封面走占位（礼包图标），
 * peek 失败卡片仍可整体渲染（按钮照常，拆开面板内再给原因与重试）。
 */
@Composable
internal fun PlazaGiftPackCard(
    pack: PlazaPack,
    peek: PackPeekUi,
    claimed: PackClaimedUi?,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    onOpen: () -> Unit,
) {
    val preview = (peek as? PackPeekUi.Ready)?.preview
    // 收下成功翻面：scale+fade 结果揭示。R178 收编：design-redesign §2.1-4 的 600ms 是
    // 动效总表（design-proposal §5.2 揭示行）之前的旧节奏，且本文件下方 packJoin 同族
    // 揭示已是 AnimSlow——同文件两种节奏=类违规，统一为同一揭示节律（AnimSlow=320），
    // 结果揭示统一 scaleIn(0.92→1)
    AnimatedContent(
        targetState = claimed != null,
        transitionSpec = {
            (fadeIn(tween(TgFeedTokens.AnimSlow)) + scaleIn(initialScale = 0.92f, animationSpec = tween(TgFeedTokens.AnimSlow)))
                .togetherWith(fadeOut(tween(TgFeedTokens.AnimSlow)))
        },
        label = "packOpen",
    ) { opened ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                .background(TgFeedTokens.SurfaceHigh),
        ) {
            // ---------------- 盒盖封面（2:1）：头像堆叠 + 频道数徽标 + 礼盒渐变底 ----------------
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                TgFeedTokens.StarYellow.copy(alpha = 0.22f),
                                TgFeedTokens.StarYellow.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (opened) {
                    // 已收下态：封面中央落一枚成功章
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(TgFeedTokens.OverlayBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = "已收下",
                            tint = TgFeedTokens.SuccessGreen,
                            modifier = Modifier.size(TgFeedTokens.IconXl),
                        )
                    }
                } else if (preview != null && preview.channels.isNotEmpty()) {
                    // 频道头像堆叠：礼包内容的直观预告（44dp 三层叠）
                    PackAvatarStack(
                        channels = preview.channels,
                        avatarLoader = avatarLoader,
                        avatarSize = 44.dp,
                    )
                } else if (peek is PackPeekUi.Loading) {
                    // 预览在途：轻占位（不转圈——封面加载是次要信息，正文先行）
                    Icon(
                        Icons.Outlined.Redeem,
                        contentDescription = "频道礼包",
                        tint = TgFeedTokens.StarYellow.copy(alpha = 0.55f),
                        modifier = Modifier.size(TgFeedTokens.IconXl),
                    )
                } else {
                    // 预览失败/空包：静态礼盒图标兜底
                    Icon(
                        Icons.Outlined.Redeem,
                        contentDescription = "频道礼包",
                        tint = TgFeedTokens.StarYellow,
                        modifier = Modifier.size(TgFeedTokens.IconXl),
                    )
                }
                // 右上角频道数徽标（预览就绪才有——数字是封面最值钱的信息）
                if (!opened && preview != null && preview.channels.isNotEmpty()) {
                    Text(
                        "${preview.channels.size} 个频道",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(TgFeedTokens.GapMd)
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(TgFeedTokens.OverlayBg)
                            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
                    )
                }
            }
            // ---------------- 正文：礼包名 + 安利文案 + 社交证明 ----------------
            Column(Modifier.padding(TgFeedTokens.GapLg)) {
                val name = preview?.folderName?.takeIf { it.isNotBlank() }
                Text(
                    name ?: pack.pitch.ifBlank { "一份来自广场的频道礼包" },
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 安利文案（预览就绪时作副行；未就绪时上面那行就是它，不重复）
                val pitchLine = if (name != null && pack.pitch.isNotBlank() && pack.pitch != name) {
                    pack.pitch
                } else {
                    null
                }
                if (pitchLine != null) {
                    Spacer(Modifier.height(TgFeedTokens.GapXs))
                    Text(
                        pitchLine,
                        color = TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(TgFeedTokens.GapSm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Redeem,
                        contentDescription = null,
                        tint = TgFeedTokens.StarYellow,
                        modifier = Modifier.size(TgFeedTokens.IconSm),
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Text(
                        "${pack.sharers} 人分享过",
                        color = TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.height(TgFeedTokens.GapMd))
                if (opened && claimed != null) {
                    // 已收下态：聚合反馈行（scale 翻面后落定，不再有按钮）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                            .background(TgFeedTokens.SuccessGreen.copy(alpha = 0.12f))
                            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = TgFeedTokens.SuccessGreen,
                            modifier = Modifier.size(TgFeedTokens.IconSm),
                        )
                        Spacer(Modifier.width(TgFeedTokens.GapXs))
                        Text(
                            if (claimed.chatCount > 0) {
                                "已收下 · ${claimed.chatCount} 个频道进了你的频道列表"
                            } else {
                                "已收下 · 这些频道此前都在你的列表里"
                            },
                            color = TgFeedTokens.TextMedium,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    // 拆开看看：全卡唯一主按钮（Button 级强调，红）
                    PackPrimaryButton(
                        text = "拆开看看",
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 拆开面板（AppSheet）：先看再收的预览仪式——成员清单（头像 + 名 + 在/不在你的列表）
 * + 底部主按钮「全部收下」；收下成功就地翻成结果卡（✓ + 去刷刷看直达），
 * 失败/链接失效一律面板内错误行（原因 + 重试），绝不 Toast 裸抛。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // AppSheet（SheetState 默认参，与全 App 调用处同款）
@Composable
internal fun GiftPackOpenSheet(
    pack: PlazaPack,
    peek: PackPeekUi,
    joining: Boolean,
    result: ChannelPackJoin?,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    onJoin: () -> Unit,
    onRetryPeek: () -> Unit,
    onGoBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = (peek as? PackPeekUi.Ready)?.preview
    AppSheet(
        onDismiss = onDismiss,
        title = preview?.folderName?.takeIf { it.isNotBlank() } ?: "频道礼包",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg),
        ) {
            when {
                peek is PackPeekUi.Loading -> AppLoading(
                    text = "正在拆开盒子…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                )
                peek is PackPeekUi.Failed -> PackErrorRow(
                    message = "链接打不开，可能已失效；也检查下代理/网络",
                    retryText = "重试",
                    onRetry = onRetryPeek,
                )
                else -> {
                    val members = preview?.channels.orEmpty()
                    // 收下成功后成员状态点全部翻成"已在你的列表"（聚合反馈与明细一致）
                    val joinedNow = result is ChannelPackJoin.Joined
                    val addedIds = preview?.addedIds ?: emptySet()
                    val isInList: (Long) -> Boolean = { id -> joinedNow || id in addedIds }
                    // 分享者的安利文案（面板内的"是谁、为什么推荐"——社交证明的正文版）
                    val pitchLine = pack.pitch
                        .takeIf { it.isNotBlank() && it != preview?.folderName }
                    if (pitchLine != null) {
                        Text(
                            "「$pitchLine」",
                            color = TgFeedTokens.TextMedium,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(TgFeedTokens.GapSm))
                    }
                    if (members.isEmpty()) {
                        Text(
                            "这个礼包里没有频道",
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = TgFeedTokens.GapXl),
                        )
                    } else {
                        // 成员清单：源面板频道卡同款行（圆形头像/字母兜底 + 名 + 状态点）
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(members, key = { it.id }) { ch ->
                                PackMemberRow(
                                    channel = ch,
                                    already = isInList(ch.id),
                                    avatarLoader = avatarLoader,
                                )
                            }
                        }
                        // 口径一行：已有 N 个在你的列表，其余是这次要新收的
                        val already = members.count { isInList(it.id) }
                        if (already > 0) {
                            Spacer(Modifier.height(TgFeedTokens.GapSm))
                            Text(
                                if (already == members.size) "这些频道都已在你的列表里" else "$already 个已在你的列表，其余 ${members.size - already} 个将新加入",
                                color = TgFeedTokens.TextLow,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(TgFeedTokens.GapLg))
                    // 收下区：成功 → 结果卡；失败 → 错误行 + 重试；未收 → 主按钮
                    AnimatedContent(
                        targetState = result,
                        transitionSpec = {
                            (fadeIn(tween(TgFeedTokens.AnimSlow)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(TgFeedTokens.AnimSlow)))
                                .togetherWith(fadeOut(tween(TgFeedTokens.AnimSlow)))
                        },
                        label = "packJoin",
                    ) { r ->
                        when (r) {
                            is ChannelPackJoin.Joined -> PackJoinedResult(r, onGoBrowse)
                            is ChannelPackJoin.Failed -> PackErrorRow(
                                message = r.message,
                                retryText = "再试一次",
                                onRetry = onJoin,
                            )
                            null -> PackPrimaryButton(
                                text = if (joining) "收下中…" else "全部收下（${members.size} 个频道）",
                                busy = joining,
                                enabled = !joining,
                                onClick = onJoin,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
        }
    }
}

/** 收下成功结果卡：✓ 结论句 + 「去刷刷看」直达（切聚合源回 Feed，新频道自然进流） */
@Composable
private fun PackJoinedResult(
    joined: ChannelPackJoin.Joined,
    onGoBrowse: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SuccessGreen.copy(alpha = 0.10f))
            .padding(TgFeedTokens.GapLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = TgFeedTokens.SuccessGreen,
                modifier = Modifier.size(TgFeedTokens.IconLg),
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                if (joined.chatCount > 0) {
                    "已收下：${joined.chatCount} 个频道进了「${joined.folderName}」"
                } else {
                    "「${joined.folderName}」的频道此前都已收下"
                },
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        Text(
            "新频道会陆续出现在你的推荐里",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        PackPrimaryButton(
            text = "去刷刷看",
            onClick = onGoBrowse,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 面板内错误行：原因 + 下一步动作（重试），替代 Toast 裸抛 */
@Composable
private fun PackErrorRow(
    message: String,
    retryText: String,
    onRetry: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.WarningRed.copy(alpha = 0.10f))
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = TgFeedTokens.WarningRed,
            modifier = Modifier.size(TgFeedTokens.IconLg),
        )
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        Text(
            message,
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(retryText, color = TgFeedTokens.TgBlue)
        }
    }
}

/** 频道成员行：40dp 圆形头像（字母兜底）+ 名 + 状态点（绿=已在列表 / 黄=将新加入） */
@Composable
private fun PackMemberRow(
    channel: ChannelBrief,
    already: Boolean,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackChannelAvatar(channel = channel, avatarLoader = avatarLoader, size = 40.dp)
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        Text(
            channel.title,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (already) TgFeedTokens.SuccessGreen else TgFeedTokens.StarYellow),
            )
            Spacer(Modifier.width(TgFeedTokens.GapXs))
            Text(
                if (already) "已在你的列表" else "将新加入",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 头像堆叠（封面元素）：最多三层圆形头像，重叠 12dp，边圈 1.5dp 分层 */
@Composable
internal fun PackAvatarStack(
    channels: List<ChannelBrief>,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 36.dp,
) {
    val show = channels.take(3)
    if (show.isEmpty()) return
    val step = avatarSize - 12.dp
    Box(
        modifier
            .size(width = avatarSize + step * (show.size - 1), height = avatarSize),
        contentAlignment = Alignment.CenterStart,
    ) {
        show.forEachIndexed { i, ch ->
            PackChannelAvatar(
                channel = ch,
                avatarLoader = avatarLoader,
                size = avatarSize,
                modifier = Modifier.offset(x = step * i),
            )
        }
    }
}

/**
 * 单个频道头像：真头像异步加载（网关侧进程内缓存），回落字母圆
 * （首字 + 按 id 稳定的柔和底色，与源面板频道卡同一套视觉语言）。
 */
@Composable
internal fun PackChannelAvatar(
    channel: ChannelBrief,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // R194④：头像解码收编共享件（rememberChannelAvatarImage——IO 解码，R170 家族）
    val avatarImage = rememberChannelAvatarImage(channel.id, avatarLoader)
    val shape = CircleShape
    if (avatarImage != null) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                bitmap = avatarImage,
                contentDescription = channel.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(packAvatarColor(channel.id))
                    .border(1.5.dp, TgFeedTokens.Surface, shape),
            )
        }
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(packAvatarColor(channel.id))
                .border(1.5.dp, TgFeedTokens.Surface, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                channel.title.firstOrNull()?.uppercase() ?: "?",
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 主按钮（红胶囊，Button 级强调）：busy 时内嵌小转圈；每卡/每面板只许一个 */
@Composable
internal fun PackPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
        colors = ButtonDefaults.buttonColors(
            containerColor = TgFeedTokens.AccentRed,
            contentColor = TgFeedTokens.TextHigh,
            disabledContainerColor = TgFeedTokens.AccentRed.copy(alpha = 0.35f),
            disabledContentColor = TgFeedTokens.TextMedium,
        ),
        contentPadding = PaddingValues(
            horizontal = TgFeedTokens.GapXl,
            vertical = TgFeedTokens.GapMd,
        ),
        modifier = modifier.height(46.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = TgFeedTokens.TextHigh,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent,
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 字母头像底色板（与源面板频道卡 AvatarPalette 同款柔和色）：按频道 id 哈希稳定取色。
 */
private val PackAvatarPalette = listOf(
    Color(0xFF5C6BC0), // 柔靛蓝
    Color(0xFF26A69A), // 柔青绿
    Color(0xFFEF5350), // 柔红
    Color(0xFFAB47BC), // 柔紫
    Color(0xFFFFA726), // 柔橙
    Color(0xFF42A5F5), // 柔蓝
    Color(0xFF66BB6A), // 柔绿
    Color(0xFFEC407A), // 柔粉
)

private fun packAvatarColor(channelId: Long): Color =
    PackAvatarPalette[Math.floorMod(channelId, PackAvatarPalette.size)]
