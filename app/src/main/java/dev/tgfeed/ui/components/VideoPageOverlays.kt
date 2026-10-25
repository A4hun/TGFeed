package dev.tgfeed.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.roundToInt

/**
 * 视频页的提示与反馈浮层件（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：长按 2x/连续步进胶囊、中央暂停/恢复反馈、缓冲加载列、自动重连提示、
 * 加载失败终态浮层、续播提示、±10s 提示、定时关闭提示、智能降档胶囊。
 * 显隐守卫与状态编排仍在 VideoPage.kt（组合时机零改动）；
 * 全部为 [BoxScope] 扩展，Modifier.align 依赖调用方页 Box 作用域（保持原解析）。
 * R162 收敛：各胶囊底色统一 TgFeedTokens.OverlayBg（原 0.55/0.6/0.65 三种散装
 * alpha），文字一律 withOverlayShadow 描边——见 scrim-audit.md P2。
 */

/**
 * 3.5) 长按快进/连续步进提示：顶部胶囊，避开状态栏与顶栏；锁定时换文案；清屏时隐藏
 */
@Composable
internal fun BoxScope.BoostStatusPill(
    continuousSeek: Boolean,
    continuousForward: Boolean,
    boostLocked: Boolean,
) {
    Text(
        when {
            continuousSeek ->
                if (continuousForward) "连续快进中 · 松手停止" else "连续快退中 · 松手停止"
            boostLocked -> "已锁定 2x · 点击解除"
            else -> "2x 快进中"
        },
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 56.dp)
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** 4) 暂停时中央常驻 ▶ */
@Composable
internal fun BoxScope.CentralPauseIcon(uiScale: Float) {
    Icon(
        Icons.Filled.PlayArrow,
        contentDescription = "已暂停",
        tint = Color.White.copy(alpha = 0.85f),
        modifier = Modifier
            .align(Alignment.Center)
            .size(96.dp * uiScale),
    )
}

/** 4) 恢复播放时闪现 ⏸ */
@Composable
internal fun BoxScope.CentralResumeFlashIcon(uiScale: Float) {
    Icon(
        Icons.Filled.Pause,
        contentDescription = "继续播放",
        tint = Color.White.copy(alpha = 0.7f),
        modifier = Modifier
            .align(Alignment.Center)
            .size(72.dp * uiScale),
    )
}

/**
 * 缓冲转圈是加载关键反馈，清屏/全屏下保留；
 * 流式播放期间后台下载仍在推进，缓冲时叠加加载百分比与已加载大小
 *（R78 口径统一：与起播占位同一句式"正在加载"，Mock/进度未知回落"正在加载…"
 * 兜底文案，不再只有裸转圈）；失败终态（loadFailed）下不再转圈——失败浮层取而代之
 */
@Composable
internal fun BoxScope.BufferingColumn(
    dlProgress: Float,
    sizeBytes: Long,
) {
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 缓冲转圈同为页面级规格：40dp / 3dp / TextHigh
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = TgFeedTokens.TextHigh,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(10.dp))
        // 加载文案兜底：进度未知（Mock/进度流未接上，dlProgress=-1）也要有"正在加载…"，
        // 不留"黑底上一个裸转圈"（修视频加载黑屏无任何提示的另一条路径）
        Text(
            if (dlProgress >= 0f && dlProgress < 1f) {
                "正在加载 ${(dlProgress * 100).roundToInt().coerceIn(0, 100)}%" +
                    if (sizeBytes > 0) {
                        " · 已加载 ${formatSize((sizeBytes * dlProgress).toLong())}"
                    } else {
                        ""
                    }
            } else {
                "正在加载…"
            },
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium.withOverlayShadow(),
        )
    }
}

/**
 * 4.15) 自动重试中提示（R78：failCount 只记真网络类失败，不可重试病因已先分流，
 * 故本提示只在网络类重试时出现）：硬错误后 buffering=false，退避等待期
 * （playUri 尚未清空、player 还在）画面只剩黑底——这是该窗口唯一的可见反馈，
 * 全程无需用户操作。缓冲中的转圈/加载百分比已够表达"在拉"
 * （buffering=true 时不叠加本提示）；重试发出后 player=null，
 * 加载占位分支的"正在加载…"接管，本提示也让位
 */
@Composable
internal fun BoxScope.ReconnectPill(failCount: Int) {
    Text(
        "网络波动，正在重连（第 $failCount 次）…",
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium.withOverlayShadow(),
        modifier = Modifier
            .align(Alignment.Center)
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 4.2) 加载失败兜底：两条到达路径（R78 分诊）——
 *  ① 真网络类自动重试链 4 次全败（fatalReason==null）：胶囊整面可点 = 手动重试
 *    （整条链复位重拉，复位闭包由调用方 [onManualRetry] 持有），另给"换一条看"出口；
 *  ② 不可重试的稳定失败（fatalReason!=null：解码失败/资源失效）：重试救不了，
 *    不给假重试按钮，胶囊不可点，直接亮明病因 + 引导"换一条看"。
 * 必须挂在顶层而不是 player==null 的占位分支——失败时播放器往往已 acquire，
 * 黑的是 PlayerView，占位分支不参与组合，失败 UI 藏在那里永远不可见。
 *
 * R324 离线终态（第三条到达路径——离线分诊并入 ② 的文案通道）：
 *  [offline]=true 时即使 fatalReason 非空也**保持可点**（离线是环境态不是内容坏，
 *  联网后人工点按重试有真实意义——与解码失败类「不假装在努力」的不可点终态
 *  区分）；[onWatchLater] 非空时给"稍后再看"出口（N2 边界动作降级：离线提示+
 *  稍后再看兜底链——先收着，联网后从稍后再看清单进）。两参默认值=既有调用
 *  行为逐字节不变。
 */
@Composable
internal fun BoxScope.LoadFailedOverlay(
    fatalReason: String?,
    onManualRetry: () -> Unit,
    onSkip: () -> Unit,
    // R324：离线终态（可点重试 + 稍后再看槽的开关；默认 false=旧行为）
    offline: Boolean = false,
    onWatchLater: (() -> Unit)? = null,
) {
    val fatal = fatalReason
    Column(
        Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBg)
            .then(
                // 手动重试 = 整条链复位重来（含换路副本回到原条目、病因清零）；
                // 网络类终态与离线终态（R324）可点——稳定终态不假装在努力，
                // 离线是环境态（联网即愈），人工重试有真实意义
                if (fatal == null || offline) {
                    Modifier.clickable(onClick = onManualRetry)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "加载失败",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.withOverlayShadow(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // 网络类：如实告知已自动重试过；稳定终态：直接亮明病因
            fatal ?: "网络不给力，已自动重试 $AUTO_RETRY_MAX 次仍未成功",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall.withOverlayShadow(),
        )
        if (fatal == null || offline) {
            Spacer(Modifier.height(8.dp))
            Text(
                "点按重试",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge.withOverlayShadow(),
            )
        }
        // R324 离线终态专属出口：稍后再看（N2 边界动作降级——离线提示+稍后再看
        // 兜底链；嵌套 clickable 优先于外层胶囊，同"换一条看"位形）
        if (offline && onWatchLater != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                "稍后再看",
                color = TgFeedTokens.TgBlue,
                style = MaterialTheme.typography.bodyLarge.withOverlayShadow(),
                modifier = Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .clickable(onClick = onWatchLater)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        // 换片/跳过选项：点击跳到下一页（嵌套 clickable 优先于外层胶囊的整面点击）
        Text(
            "换一条看 →",
            color = TgFeedTokens.TgBlue,
            style = MaterialTheme.typography.bodyLarge.withOverlayShadow(),
            modifier = Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .clickable(onClick = onSkip)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * 4.3) 续播提示：顶部胶囊"已从 X:XX 继续播放"，2.5s 自动消失；
 * 位置在 148dp——给搬到 100dp 的 ±10s 步进提示让位，避免两条胶囊叠字
 */
@Composable
internal fun BoxScope.ResumeHintPill(progressToRestore: Long) {
    Text(
        "已从 ${formatDuration((progressToRestore / 1000).toInt())} 继续播放",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 148.dp)
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * 4.4) 双击/连续步进的快退快进提示：顶部居中胶囊（100dp，紧随 56dp 的 2x 胶囊之下），
 * 700ms 自动消失。修"指示与字体重合"：旧位置在画面正中央，会压弹幕
 * （弹幕滚过画面上 2/3 区域）与中央暂停/恢复图标；顶部居中后避开底部
 * 信息区（标题/进度条）、弹幕区与中央反馈区，且自带黑底衬保证可读
 */
@Composable
internal fun BoxScope.SeekHintPill(hint: String) {
    Text(
        hint,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium.withOverlayShadow(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 100.dp)
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 4.5) 定时关闭到点提示：中央胶囊，2.6s 自动消失；清屏/全屏下也保留（关键反馈） */
@Composable
internal fun BoxScope.SleepPausePill() {
    Text(
        "定时关闭已暂停",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge.withOverlayShadow(),
        modifier = Modifier
            .align(Alignment.Center)
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 4.6) 降档可见（GAPS3 #10）：智能降档生效时右上角胶囊"已降档 XXXp（省流模式）"，
 * 3 秒后淡出（degradeHint 计时器驱动）；清屏时隐藏，全屏下保留（与退出钮不撞位）
 */
@Composable
internal fun BoxScope.DegradeQualityPill(
    visible: Boolean,
    effectiveQualityHeight: Int,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 56.dp, end = 16.dp),
        // R178 收编三档 + 纪律二修正：退场 300>进场 200 违反「退场永远 ≤ 进场」——
        // 改 AnimMed 进 / AnimFast 出（220/150，胶囊 3s 到点淡出更利落）
        enter = fadeIn(tween(TgFeedTokens.AnimMed)),
        exit = fadeOut(tween(TgFeedTokens.AnimFast)),
    ) {
        Text(
            "已降档 ${effectiveQualityHeight}p（省流模式）",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
            modifier = Modifier
                .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
