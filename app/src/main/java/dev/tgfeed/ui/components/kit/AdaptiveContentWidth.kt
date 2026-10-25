package dev.tgfeed.ui.components.kit

import android.content.res.Configuration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.WindowSize
import dev.tgfeed.ui.rememberWindowSize

/**
 * 二级页自适应内容列最大宽（列表/表单/文本流类页面共用）。
 *
 * 取代各页硬编码的 widthIn(max = 480.dp)：固定 480dp 是给超宽横屏定的规格，
 * 平板竖屏（sw600~840dp）下内容列只剩约 55%~60% 屏宽、左右大片黑空，
 * 观感像"没适配屏幕"（实测 2032×3048px/400dpi 平板竖屏：812.8dp 宽，
 * 480/812.8 ≈ 59%，即用户投诉的"挤在中间一条窄列"）。
 *
 * 三档规则（依据：列表行/表单行过长伤阅读——一行横跨超宽屏时眼球回扫换行
 * 容易串行，所以任何档都不铺满超宽屏；但平板竖屏也不能窄成"手机壳"，否则
 * 两侧留白浪费过半屏幕）：
 * - 手机竖屏：Dp.Infinity 不限宽，铺满（手机竖屏本就窄，无需居中列）；
 * - 平板非超宽（sw≥600，竖屏或普通横屏）：min(屏宽 × 0.86, 640dp) 居中——
 *   812.8dp 平板竖屏得 640dp（约 79% 屏宽），明显变宽、不再是手机壳观感；
 *   上限 640dp 保住行长（列表主行约 40~55 个全角字符，仍在舒适阅读区）；
 * - 超宽横屏（横屏且 宽/高 > 2，或横屏宽 ≥1600dp）：480dp 居中，维持原横屏
 *   规格——矮屏 + 超宽画幅下内容列窄一些，视线不用大幅横扫。
 *   注：手机横屏宽高比普遍 > 2（19.5:9、20:9），几乎全部落进这档，行为与
 *   原 480dp 一致；平板横屏（本机 1219×812.8dp、宽高比 1.5）落中间档。
 *
 * 平板/手机判定复用 [rememberWindowSize]（smallestScreenWidthDp ≥ 600 分界，
 * 不会被横屏手机误判成平板）；超宽判定用当窗宽高比与宽度。
 * 旋转/折叠屏开合时 LocalConfiguration 变化自动触发重组，列宽即时跟随。
 */
@Composable
fun adaptiveContentMaxWidth(): Dp {
    val config = LocalConfiguration.current
    val w = config.screenWidthDp
    val h = config.screenHeightDp.coerceAtLeast(1)
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val ultraWideLandscape = landscape && (w.toFloat() / h > 2f || w >= 1600)
    return when (rememberWindowSize()) {
        WindowSize.PhonePortrait -> Dp.Infinity
        WindowSize.TabletPortrait, WindowSize.TabletLandscape ->
            if (ultraWideLandscape) 480.dp else minOf(w * 0.86f, 640f).dp
        // PhoneLandscape：超宽横屏维持原 480dp 居中；16:9 老机横屏（非超宽）也走
        // 480dp——横屏矮屏里纵向滚动是主交互，列宽保持原规格不放大
        WindowSize.PhoneLandscape -> 480.dp
    }
}

/**
 * 自适应内容列宽修饰符：`widthIn(max = adaptiveContentMaxWidth())` 的封装，
 * 二级页统一换用它，别再各页硬编码数字。
 *
 * 使用纪律（P1-6 七处同病的教训）：本修饰符必须排在 fillMaxSize()/fillMaxWidth()
 * **之前**——fillMax* 会先把约束钉成全宽，其后的 widthIn 是死代码。
 * 居中由外层 Box(contentAlignment = Alignment.TopCenter) 负责，本修饰符只管限宽。
 */
@Composable
fun Modifier.adaptiveContentWidth(): Modifier = this.widthIn(max = adaptiveContentMaxWidth())
