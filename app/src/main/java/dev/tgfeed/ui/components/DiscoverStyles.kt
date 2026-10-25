package dev.tgfeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 发现页视觉语法的唯一实现家（R207 统一治理，规范全文见 docs/DISCOVER-DESIGN.md §10）：
 *
 * 病史：R183 重构 + R197/R204/R205/R202 多轮加料每轮自带配色——红一色相干 9 种
 * 语义角色、徽章淡彩底出 0.12/0.14/0.16 三档散装 alpha、入口行骨架手抄 5 份逐行
 * 换装（用户实锤「UI 配色花里胡哨」）。
 *
 * 纪律（§10 一句话版）：
 * 1. 强调色 4 语义上限：AccentRed=可点主行动（骰子一枚）/ TgBlue=提交型主按钮
 *    （Material primary）/ SuccessGreen=状态确认 / WarningRed=破坏性动作+⚠ 警示——
 *    其余一律中性（TextHigh/TextMedium/TextLow/Surface/SurfaceHigh/OverlayHairline）。
 * 2. 徽章/胶囊一个形：[DiscoverPillBadge]（Pill+0.5dp 发丝+labelSmall+GapSm/2dp，
 *    底两档 onImage=OverlayBgStrong / inline=Surface）；淡彩底（copy alpha）废除。
 * 3. 入口行一个式：[DiscoverEntryRow]（图标 TextMedium+标题+副文案+中性尾徽章）。
 * 4. 区块标题一级：[DiscoverSectionTitle]（中性引条+titleMedium）。
 * 5. 信息条一个壳：[DiscoverInfoStrip]（RadiusSm+Surface+中性引条+bodySmall）。
 * 数据强调走中性阶差（top3=TextHigh 其余 TextLow；🔥 emoji 自带热度符号），
 * 不用色相；身份色（字母头像调色板/封面回落渐变）不受本纪律约束。
 *
 * 新元素进发现页必须从这里取形取色（遮罩领域另见 OverlayStyles.kt/scrim-audit.md）；
 * PR 里见手抄壳或散装色即打回。
 */

// ---------------- §10.2 徽章/胶囊统一件 ----------------

/**
 * 发现页徽章统一形：全圆角胶囊 + 0.5dp 发丝描边 + labelSmall + GapSm/2dp。
 * 底两档：onImage=true 压图（OverlayBgStrong，任意画面可读——R162 压图角标同语言）；
 * inline 行内（Surface）。字色：onImage=TextHigh / inline=TextMedium；
 * warn=true 走警示语义（WarningRed 字 + 中性底，R204 DS-3 ⚠ 口径）；
 * accent=true 走提交型主行动语义（TgBlue 字 + 中性底，R211「去选择」引导）。
 * 淡彩底（color.copy(alpha)）一律不许回来——那是嘉年华本体。
 */
@Composable
internal fun DiscoverPillBadge(
    text: String,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
    warn: Boolean = false,
    accent: Boolean = false,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Box(
        modifier
            .clip(shape)
            .background(if (onImage) TgFeedTokens.OverlayBgStrong else TgFeedTokens.Surface)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, shape)
            .padding(horizontal = TgFeedTokens.GapSm, vertical = 2.dp),
    ) {
        Text(
            text,
            color = when {
                warn -> TgFeedTokens.WarningRed
                accent -> TgFeedTokens.TgBlue
                onImage -> TgFeedTokens.TextHigh
                else -> TgFeedTokens.TextMedium
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

// ---------------- §10.3 入口行统一件 ----------------

/**
 * 发现页入口行统一式（深功能区全部入口共用）：
 * [图标 18dp · TextMedium] [标题 labelLarge · TextHigh + 副文案 labelSmall · TextLow]
 * [尾徽章 DiscoverPillBadge inline · 中性]；行壳 Pill+SurfaceHigh+发丝描边+可点。
 * 图标色不逐行换装（旧病：五行红/蓝/黄/蓝/红轮播）——区分交给图标形+文案。
 *
 * emphasis=true（R211 引导档）：零勾选搜索群等「未解锁金矿」引导态——图标升
 * TextHigh、副文案升 TextMedium（中性阶差强调）+ 尾徽章走 accent（TgBlue 提交型
 * 主行动语义，§10 四语义内）。仍是同一个形同一个式，只是档位升一阶。
 */
@Composable
internal fun DiscoverEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    trailingBadge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (emphasis) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = if (emphasis) TgFeedTokens.TextMedium else TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        if (trailingBadge != null) {
            DiscoverPillBadge(trailingBadge, accent = emphasis)
        }
    }
}

// ---------------- §10.4 区块标题统一件 ----------------

/**
 * 发现页区块标题统一一级：3×14dp 中性引导条（TextMedium）+ titleMedium 粗体白字。
 * 热词/刚冒出来/类目/本周口碑/反向探索内联头共用——不设第二制式、引条不染色
 * （旧病：红引条 ×5 处把「主行动色」稀释成装饰）。
 */
@Composable
internal fun DiscoverSectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DiscoverLeadBar()
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        Text(
            text,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

// ---------------- §10.5 信息条统一件 ----------------

/**
 * 发现页信息条统一壳：RadiusSm+Surface 底+3×14dp 中性引条+一行 bodySmall。
 * 基因鉴定/客观推荐语/群搜来源词/未来事实行共用——引条不按文案换色
 * （旧病：绿=鉴定/蓝=可信/红=热度三套引条，信息类型由文案本身表达）。
 */
@Composable
internal fun DiscoverInfoStrip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.Surface)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscoverLeadBar()
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        Text(
            text,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------- 中性引导件（标题条/信息条/列表点共用族） ----------------

/** 3×14dp 全圆角中性引导竖条（TextMedium）——区块标题与信息条的同一语言 */
@Composable
private fun DiscoverLeadBar() {
    Box(
        Modifier
            .width(3.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.TextMedium),
    )
}

/** 4dp 中性引导圆点（TextMedium）——统计分段/收录播报/证据行的同一语言 */
@Composable
internal fun DiscoverLeadDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(TgFeedTokens.TextMedium),
    )
}
