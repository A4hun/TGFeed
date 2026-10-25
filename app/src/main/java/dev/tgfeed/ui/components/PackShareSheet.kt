package dev.tgfeed.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.ChannelCollection
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.ui.components.kit.AppSheet
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay

/**
 * 频道礼包·生产侧面板（R137，design-redesign.md §2.1 生产侧）：
 * 把合集/智能房间打包成礼物盒分享——AppSheet 一层装下「盒盖预览 + 安利文案 +
 * 生成主按钮」，生成结果也留在面板里（结果卡：✓ 链接已复制 + 广场同步状态 +
 * 复制/系统分享），不再 AlertDialog 三层套娃、不再 Toast 承载结果。
 *
 * 生成中 = 按钮内嵌小转圈（过程感）；失败 = 面板内错误行（原因 + 重试）。
 * 危险操作「删除合集」只给真合集（智能房间是本地聚类产物，无删除语义），
 * 独立分区 + 与主操作隔 16dp，点后仍走 AppDialog 二次确认（调用方接线）。
 */

/** 生成结果（生产侧 UI 态）：链接 + 是否已同步到广场 */
internal data class PackShareResultUi(
    val url: String,
    /** true = 已广播到广场；false = 未开启广场或服务端节奏未到（对用户只说「稍后」） */
    val posted: Boolean,
)

/**
 * 礼包分享面板：盒盖预览（头像堆叠 + 合集名 + 频道数徽标）→ 安利文案（预填可改）→
 * 主按钮「生成分享链接」→ 结果卡（链接/复制/系统分享/广场状态）。
 * [onDelete] = null 表示目标无删除语义（智能房间），危险区整段不渲染。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // AppSheet（SheetState 默认参，与全 App 调用处同款）
@Composable
internal fun PackShareSheet(
    collection: ChannelCollection,
    members: List<ChannelBrief>,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    busy: Boolean,
    error: String?,
    result: PackShareResultUi?,
    onGenerate: (pitch: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // 安利文案：进面板预填默认（合集名 + 频道数），可改
    var pitch by remember(collection.name) {
        mutableStateOf("「${collection.name}」${collection.channelIds.size} 个频道，点开就能整包收下")
    }
    // 复制反馈的短驻标记（2s 回落，不弹任何东西）
    var copiedTick by remember(collection.name) { mutableStateOf(false) }
    LaunchedEffect(copiedTick) {
        if (copiedTick) {
            delay(2000)
            copiedTick = false
        }
    }
    val context = LocalContext.current

    /** 复制链接到剪贴板（生成成功时已自动复制过，此处是手动补复制） */
    fun copyLink(url: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("频道礼包", url))
        }
        copiedTick = true
    }

    /** 系统分享面板（文案 + 链接一起带走） */
    fun shareLink(url: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$pitch\n$url")
            }
            context.startActivity(Intent.createChooser(send, "分享频道礼包"))
        }
    }

    AppSheet(onDismiss = onDismiss, title = "分享为礼包") {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg),
        ) {
            // ---------------- 盒盖预览：头像堆叠 + 合集名 + 频道数徽标（星黄礼盒渐变底） ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                TgFeedTokens.StarYellow.copy(alpha = 0.22f),
                                TgFeedTokens.StarYellow.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PackAvatarStack(
                    channels = members,
                    avatarLoader = avatarLoader,
                    avatarSize = 36.dp,
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                Column(Modifier.weight(1f)) {
                    Text(
                        collection.name,
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Redeem,
                            contentDescription = null,
                            tint = TgFeedTokens.StarYellow,
                            modifier = Modifier.size(TgFeedTokens.IconSm),
                        )
                        Spacer(Modifier.width(TgFeedTokens.GapXs))
                        Text(
                            "频道包 · ${collection.channelIds.size} 个频道",
                            color = TgFeedTokens.TextMedium,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            when {
                // ---------------- 生成结果卡：链接 + 复制 + 广场状态（面板内闭环，不走 Toast） ----------------
                result != null -> PackShareResultCard(
                    result = result,
                    copiedTick = copiedTick,
                    onCopy = { copyLink(result.url) },
                    onShare = { shareLink(result.url) },
                )
                // ---------------- 生成失败：原因 + 重试（重试 = 再点主按钮） ----------------
                error != null -> PackShareErrorRow(message = error)
                // ---------------- 表单态：一句人话 + 安利文案输入 + 主按钮 ----------------
                else -> {
                    Text(
                        "别人点开链接，就能把这 ${collection.channelIds.size} 个频道整包收进 Telegram",
                        color = TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapMd))
                    OutlinedTextField(
                        value = pitch,
                        onValueChange = { pitch = it },
                        singleLine = true,
                        placeholder = {
                            Text("一句话安利这个频道包", color = TgFeedTokens.TextLow)
                        },
                        shape = RoundedCornerShape(TgFeedTokens.RadiusField),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TgFeedTokens.TextHigh,
                            unfocusedTextColor = TgFeedTokens.TextHigh,
                            cursorColor = TgFeedTokens.TgBlue,
                            focusedBorderColor = TgFeedTokens.TgBlue,
                            unfocusedBorderColor = TgFeedTokens.OverlayHairline,
                            focusedContainerColor = TgFeedTokens.SurfaceHigh,
                            unfocusedContainerColor = TgFeedTokens.SurfaceHigh,
                            focusedPlaceholderColor = TgFeedTokens.TextLow,
                            unfocusedPlaceholderColor = TgFeedTokens.TextLow,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapLg))
                    PackPrimaryButton(
                        text = if (busy) "正在生成…" else "生成分享链接",
                        busy = busy,
                        enabled = !busy && pitch.isNotBlank(),
                        onClick = { onGenerate(pitch) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // ---------------- 危险区（独立分区，与主操作隔 16dp；智能房间无删除语义不渲染） ----------------
            if (onDelete != null) {
                Spacer(Modifier.height(TgFeedTokens.GapLg))
                HorizontalDivider(color = TgFeedTokens.OverlayHairline)
                Spacer(Modifier.height(TgFeedTokens.GapMd))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = TgFeedTokens.WarningRed,
                        modifier = Modifier.size(TgFeedTokens.IconLg),
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapMd))
                    Text(
                        "删除合集",
                        color = TgFeedTokens.WarningRed,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
        }
    }
}

/** 生成结果卡：✓ 已复制 + 链接（TgBlue）+ 广场同步状态行 + 复制/系统分享 */
@Composable
private fun PackShareResultCard(
    result: PackShareResultUi,
    copiedTick: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
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
                "礼包链接已生成，并复制到了剪贴板",
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        // 链接本体（首尾保留中段省略，点不动；复制/分享按钮才是动作位）
        Text(
            middleEllipsized(result.url, head = 26, tail = 14),
            color = TgFeedTokens.TgBlue,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        // 广场状态行：已同步 / 稍后（不解释服务端节奏，对用户只说人话）
        Text(
            if (result.posted) "已同步到广场，广场用户都能刷到这个礼包" else "链接已复制 · 稍后会出现在广场",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCopy) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = TgFeedTokens.TextMedium,
                    modifier = Modifier.size(TgFeedTokens.IconSm),
                )
                Spacer(Modifier.width(TgFeedTokens.GapXs))
                Text(
                    if (copiedTick) "已复制" else "复制链接",
                    color = TgFeedTokens.TextMedium,
                )
            }
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            TextButton(onClick = onShare) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = null,
                    tint = TgFeedTokens.TgBlue,
                    modifier = Modifier.size(TgFeedTokens.IconSm),
                )
                Spacer(Modifier.width(TgFeedTokens.GapXs))
                Text("分享", color = TgFeedTokens.TgBlue)
            }
        }
    }
}

/** 面板内错误行：原因（重试 = 主按钮就在下方/原位，文案里指路） */
@Composable
private fun PackShareErrorRow(message: String) {
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
        )
    }
}

/** 链接显示用的中段省略（首段域名 + 尾段识别位），过长才截 */
private fun middleEllipsized(url: String, head: Int, tail: Int): String =
    if (url.length <= head + tail + 1) url else url.take(head) + "…" + url.takeLast(tail)
