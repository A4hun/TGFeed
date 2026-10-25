package dev.tgfeed.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 窗口尺寸档：手机/平板 × 竖屏/横屏。
 * 判定：smallestScreenWidthDp >= 600 为平板（AOSP sw600 分界，与方向无关），叠加方向。
 * 注意：不能用 screenWidthDp——横屏手机宽度普遍 ≥600dp，会被误判成平板
 * （PhoneLandscape 档近乎不可达，手机横屏全吃平板规则）。实测审查发现的坑。
 * 旋转/折叠屏开合时 LocalConfiguration 变化自动触发重组，布局即时跟随。
 */
enum class WindowSize { PhonePortrait, PhoneLandscape, TabletPortrait, TabletLandscape }

@Composable
fun rememberWindowSize(): WindowSize {
    val config = LocalConfiguration.current
    val tablet = config.smallestScreenWidthDp >= 600
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    return when {
        tablet && landscape -> WindowSize.TabletLandscape
        tablet -> WindowSize.TabletPortrait
        landscape -> WindowSize.PhoneLandscape
        else -> WindowSize.PhonePortrait
    }
}

/**
 * Feed 内容区限宽——档位规则（纯函数）：
 * - 手机竖屏铺满；手机横屏限 600dp 居中（巨屏视频不拉变形）
 * - 平板竖屏 min(屏宽×0.85, 900dp)：参考抖音平板——内容区接近全宽只留窄边。
 *   旧值 0.75w/720dp 两侧各白扔 12.5% 屏宽（10 寸平板合计浪费 200dp+ 全黑边）
 * - 平板横屏 min(屏宽×0.56, 880dp)：横屏纵向空间少，内容区居中、较旧值 0.5w/800dp 略放宽
 *
 * R159 从 @Composable 拆出纯函数：列宽要"随内容形态现算"（见 [contentAwareFeedMaxWidth]），
 * 单测与 FeedScreen 现算链需在非组合点复用同一套档位规则；@Composable 版
 * [getFeedContentMaxWidth] 保留为薄壳（LocalConfiguration 取屏宽后转调），调用方零改动。
 */
fun feedContentMaxWidth(windowSize: WindowSize, screenWidthDp: Int): Dp = when (windowSize) {
    WindowSize.PhonePortrait -> Dp.Infinity
    WindowSize.PhoneLandscape -> 600.dp
    WindowSize.TabletPortrait -> minOf(screenWidthDp * 0.85f, 900f).dp
    WindowSize.TabletLandscape -> minOf(screenWidthDp * 0.56f, 880f).dp
}

/** [feedContentMaxWidth] 的组合入口（屏宽经 LocalConfiguration 现取，旋转/折叠自动跟随） */
@Composable
fun WindowSize.getFeedContentMaxWidth(): Dp =
    feedContentMaxWidth(this, LocalConfiguration.current.screenWidthDp)

/**
 * R159 横屏内容放宽阈值：宽高比 > 1.2 视为"横屏内容"。
 * 取 1.2 而非 1.0：接近正方形的片（1:1、5:4 杂拍）放宽收益微薄，且在横屏设备上
 * 方片/竖片保持竖长列本就是正确形态——阈值以上的宽片（16:9、2.35:1 等）才有
 * "接近铺满"的收益。
 */
const val CONTENT_WIDEN_ASPECT = 1.2f

/**
 * R159 内容感知列宽：让列宽感知内容形态——横屏设备上的横屏内容不再被夹在窄中栏
 * （实测华为平板 2560×1600 横屏：横屏视频被 56% 屏宽的中栏夹住，44% 屏幕在放
 * 模糊底衬，浪费近半屏幕）。
 *
 * 规则：
 * - 竖屏档（手机/平板）：完全走档位宽（[feedContentMaxWidth]），内容形态不参与——
 *   竖屏满屏列是主消费形态，零改动（回归红线）；
 * - 横屏档 + 横屏内容（[contentAspect] > [CONTENT_WIDEN_ASPECT]）：
 *   列宽 = min(屏高 × 宽高比, 屏宽 × 0.92)。屏高×比 = 内容在满屏高时的自然宽度
 *   （再宽就要竖向裁切/留黑边），92% 屏宽封顶给两侧留窄边（保居中观感、
 *   躲开圆角/挖孔）；
 * - 横屏档 + 竖屏/方片/比未知（aspect ≤ 阈值或 ≤0）：档位宽，与 R158 前完全一致；
 * - 兜底①：放宽结果永不低于档位宽——窄横屏（如手机横屏 360dp 屏高、比刚过阈值）
 *   min(屏高×比, 0.92屏宽) 可能比 600dp 档位还窄，此时维持档位宽（只放宽不收窄，
 *   回归红线）；
 * - 兜底②：屏宽/屏高 ≤0（异常 Configuration）直接档位宽。
 *
 * [contentAspect]：内容宽高比（宽/高），两级来源由调用方解析后传入：
 * ①条目元数据/封面位图代理（加载态即生效的预判）②起播后 ExoPlayer videoSize
 * 校正；≤0 = 未知 → 档位宽（元数据缺失回落现有宽度，不许回归）。
 */
fun contentAwareFeedMaxWidth(
    windowSize: WindowSize,
    screenWidthDp: Int,
    screenHeightDp: Int,
    contentAspect: Float,
): Dp {
    val tierWidth = feedContentMaxWidth(windowSize, screenWidthDp)
    val landscapeTier = windowSize == WindowSize.PhoneLandscape || windowSize == WindowSize.TabletLandscape
    if (!landscapeTier || contentAspect <= CONTENT_WIDEN_ASPECT) return tierWidth
    if (screenWidthDp <= 0 || screenHeightDp <= 0) return tierWidth
    val widened = minOf(screenHeightDp * contentAspect, screenWidthDp * 0.92f).dp
    return maxOf(tierWidth, widened)
}

/**
 * R338 图片条目的 feed 列宽形态比（纯函数——图片卡横屏适配对齐视频 V2 件）：
 * 用户实锤「图片界面没有像视频界面那样做好适配特别是横屏」——R159 的列宽随画面比
 * 放宽只对 mediaType==0（视频）开放，图片恒档位宽（横图在横屏只显半屏高）。
 * 本门把图片档接进同一条 [contentAwareFeedMaxWidth] 现算链（同一政策单口，零第二份）：
 * - mediaType==1 且元数据有尺寸 → width/height 比（横屏+横图放宽到
 *   min(屏高×比, 0.92屏宽)——与视频同式；竖图/方图/竖屏档在 contentAware 内恒
 *   档位宽 = 现状，竖图在档位列内本已满高零新尺寸逻辑）；
 * - mediaType==1 但元数据缺尺寸 → 0f（形态已知=不放宽，档位宽兜底不许回归）；
 * - 其他类型（0 视频/2 音频/未知条目）→ null（本门零介入：视频走既有两级
 *   预判/实测链+粘滞兜底、音频恒档位宽——返回值语义与 FeedScreen 的
 *   knownAspect 口径对齐：null=未知走粘滞，0f=已知不放宽）。
 *
 * 图片是单级来源（元数据即终比，无 ExoPlayer 起播校正）——PhotoSize 最大档
 * 宽高在取数链（GatewayFeedFetch 图片分支，图片条目 width/height 直写
 * VideoItem）就已就位，组合期帧同步可用。
 */
fun feedPhotoShapeAspect(mediaType: Int, width: Int, height: Int): Float? {
    if (mediaType != 1) return null
    if (width <= 0 || height <= 0) return 0f
    return width.toFloat() / height
}

// ---------------- R225 横屏信息/操作区可伸展政策（视频/图文卡 chrome 共用） ----------------

/**
 * R225 横屏 chrome 可伸展档的度量集（纯数据）：
 * 用户实锤（小米 3:2 平板横屏截图）——横屏下列宽被竖屏设计的固定 dp 主宰：
 * 底部信息区 [fillMaxWidth] 横贯整列（左重）、右轨 30dp 图标挤在列右缘一条竖带（右轻
 * 且触区<48dp）。本政策给出横屏「有纵向余量时」的放大/限宽度量；**null = 原行为**
 * （竖屏/矮横屏调用方走旧链——竖屏零变化红线结构性成立）。
 *
 * @param railIconSize 右轨图标视觉尺寸（替代 30dp×uiScale 的横屏放大档）
 * @param railTouchMin 右轨单格触区最小边长（48dp 铁律锚——RailItem 触区盒）
 * @param railGap 右轨行距（B 档压缩，保列高不压顶栏）
 * @param railAvatarSize 列顶频道头像尺寸（A 档放大）
 * @param infoMaxWidth 底部信息区限宽（复用 R171 [SheetMaxWidth] 可读宽口径）
 */
data class LandscapeFeedChrome(
    val railIconSize: Dp,
    val railTouchMin: Dp,
    val railGap: Dp,
    val railAvatarSize: Dp,
    val infoMaxWidth: Dp,
)

/**
 * R225 横屏 chrome 可伸展判据（纯函数，单测锚定 [LandscapeChromePolicyTest]）：
 *
 * 触发条件 = 横屏档 且 屏高够放整列右轨。右轨总高（头像 + 头像行距 + 5 格（触区+行距））：
 * - A 档（高横屏，屏高 ≥680dp）：56 + 26 + 5×(48+26) = 452dp
 * - B 档（中横屏，屏高 ≥636dp）：48 + 26 + 5×(48+20) = 408dp
 * 阈值推导：总高 + 右轨底距 102dp（R194⑥ 热区口径，本刀不动）+ 顶部预留 110dp
 * （顶栏底口径，16416 实测）——A 需 ≥664（取 680 留 16dp 余量）、B 需 ≥620（取 636）。
 * 设备落点：16384（812dp）/用户 3:2 平板（903dp+）→ A；16416 与华为 1600（640dp）→ B
 * （列顶余量 20dp，r178 余量 133px 实测口径验算吻合）；手机横屏（360~430dp）→ null。
 *
 * **48dp 触区铁律 A/B 两档都保**；矮横屏返回 null（现状触区 <48dp 为 r178 已记录的
 * 存量条件——触区放大与 102dp 底距+顶栏几何冲突，需独立热区重设计，挂账不静默）。
 * 竖屏两档恒 null（零变化红线）；屏高非正（异常 Configuration）防御性 null。
 */
fun landscapeFeedChromeOrNull(windowSize: WindowSize, screenHeightDp: Int): LandscapeFeedChrome? {
    val landscape = windowSize == WindowSize.PhoneLandscape || windowSize == WindowSize.TabletLandscape
    if (!landscape || screenHeightDp <= 0) return null
    return when {
        // A 档：高横屏（余量 16dp+）——图标 40/触区 48/行距 26/头像 56
        screenHeightDp >= 680 -> LandscapeFeedChrome(
            railIconSize = 40.dp,
            railTouchMin = 48.dp,
            railGap = 26.dp,
            railAvatarSize = 56.dp,
            infoMaxWidth = SheetMaxWidth,
        )
        // B 档：中横屏（余量 16dp+，行距压缩保列顶不压顶栏）——图标 36/触区 48/行距 20
        screenHeightDp >= 636 -> LandscapeFeedChrome(
            railIconSize = 36.dp,
            railTouchMin = 48.dp,
            railGap = 20.dp,
            railAvatarSize = 48.dp,
            infoMaxWidth = SheetMaxWidth,
        )
        // 矮横屏（手机横屏为主）：不触发，调用方走原链（存量口径不变）
        else -> null
    }
}

/** 网格默认列数 */
val WindowSize.gridDefaultCols: Int
    get() = when (this) {
        WindowSize.PhonePortrait -> 2
        WindowSize.PhoneLandscape -> 4
        WindowSize.TabletPortrait -> 3
        WindowSize.TabletLandscape -> 5
    }

/** 网格捏合缩放档位 */
val WindowSize.gridColSteps: IntArray
    get() = when (this) {
        WindowSize.PhonePortrait -> intArrayOf(2, 3, 4)
        WindowSize.PhoneLandscape -> intArrayOf(3, 4, 5, 6)
        WindowSize.TabletPortrait -> intArrayOf(2, 3, 4)
        WindowSize.TabletLandscape -> intArrayOf(4, 5, 6, 7)
    }

/**
 * 控件/间距缩放系数（dp 通道）：手机竖屏基准 1.0。
 * 平板同样 dp 下物理尺寸更大、观看距离更远，图标/按钮/间距整体放大避免"小人国"；
 * 手机横屏纵向空间紧张，整体略收防浮层溢出屏上缘。
 * 只乘在 dp 尺寸上（布局/热区/进度条粗细）；文本缩放走 [fontScale] 通道，两者分离。
 */
val WindowSize.uiScale: Float
    get() = when (this) {
        WindowSize.PhonePortrait -> 1f
        WindowSize.PhoneLandscape -> 0.92f
        WindowSize.TabletPortrait -> 1.15f
        WindowSize.TabletLandscape -> 1.1f
    }

/**
 * 文本缩放系数（sp 通道）：经 LocalDensity.fontScale 下发——只放大 sp 文本，
 * dp 布局、手势热区、点击判定一律不受影响（比整体 dp 缩放安全的文字专用通道）。
 * 用法：`Density(density.density, density.fontScale * windowSize.fontScale)` 包一层。
 */
val WindowSize.fontScale: Float
    get() = when (this) {
        WindowSize.PhonePortrait -> 1f
        WindowSize.PhoneLandscape -> 0.95f
        WindowSize.TabletPortrait -> 1.12f
        WindowSize.TabletLandscape -> 1.08f
    }

/**
 * 音频卡封面最大边长：封面宽是"内容区×0.78"，平板限宽放宽后会爆出
 * 600dp+ 巨图（竖屏一页都放不下），按档封顶。
 * 手机竖屏 0.78w 远低于 420dp 恒不触发、行为不变；手机横屏内容区限宽 600dp，
 * 封面 468→420dp 略收——横屏矮屏里封面本就得靠滚动看全，略收反而少滚一截。
 */
val WindowSize.audioCoverMax: Dp
    get() = when (this) {
        WindowSize.PhonePortrait, WindowSize.PhoneLandscape -> 420.dp
        WindowSize.TabletPortrait -> 460.dp
        WindowSize.TabletLandscape -> 420.dp
    }

// ---------------- R171 二级页适配：封面墙网格列数 + 底部弹层限宽 ----------------

/**
 * R171 封面墙格子最小宽：频道主页/搜索结果这类 3:4 封面墙网格的
 * [GridCells.Adaptive] minSize 锚（政策唯一真源，两墙共用，勿在调用方私写数字）。
 *
 * 背景：两墙原为 `GridCells.Fixed(3)`——固定 3 列横扫一切屏宽，平板上格子被拉到
 * 巨宽（华为平板 640dp 竖屏格宽 212dp、1024dp 横屏 340dp；主力高分平板 812.8dp 竖屏
 * 270dp），即用户实锤的"二级页没适配屏幕分辨率"（R171）。
 *
 * 取 105dp 的口径（按 Compose Adaptive 公式 `列数 = max(1, (可用px+间距px)/(minPx+间距px))`
 * 以各密度 px 域验算，见 [mediaWallColumns]）：
 * - 手机竖屏 360/393dp（2x~3.5x 全密度）→ 3 列，与旧 Fixed(3) 一致（回归红线，
 *   含搜索墙 12dp 横向内衬后的可用宽）；
 * - 华为测试机（1600×2560，640dp 竖 @2.5）→ 5 列（格宽 126dp）、1024dp 横 → 9 列；
 * - 主力高分平板（812.8dp 竖 @2.5）→ 7 列（格宽 ~114dp）——格子物理尺寸恒定、
 *   列数随屏宽涌现，任何分屏/折叠/未知分辨率自动跟随。
 * 320dp 古董机降为 2 列（挂账：设备已近绝迹，2 列行长反而更舒适，不为其加特判）。
 */
val MediaWallCellMin = 105.dp

/**
 * [MediaWallCellMin] 的列数口径（纯函数，单测锚）：与 [GridCells.Adaptive] 同公式同取整——
 * foundation 1.7.2 字节码实证为 `max(1, (可用px + 间距px) / (minPx + 间距px))` 整除，
 * px 均按 [density] 四舍五入（roundToPx 语义）——**px 域验算不是可省的近似**：
 * 105dp @2.5 取整成 263px（=105.2dp），dp 域直除会把 640dp 竖屏误判 6 列、真机实为 5 列。
 *
 * 生产路径走 `GridCells.Adaptive(MediaWallCellMin)`（LazyGrid 用实测宽现算，分屏/内衬/
 * 挖孔自适应）；本函数供单测在无设备环境锚定"某屏某密度出几列"的政策表，公式漂移
 * （升 Compose 版本）时单测红灯提示人工复核。
 *
 * [cellMinDp]（R175）：格子最小宽锚，默认 [MediaWallCellMin]；pinch 档位层拿它
 * 在任意偏好/最密下界上复算列数（与生产 GridCells.Adaptive(同 dp) 逐字节同口径）。
 */
fun mediaWallColumns(
    availableWidthDp: Float,
    density: Float,
    spacingDp: Float = 0f,
    cellMinDp: Float = MediaWallCellMin.value,
): Int {
    if (availableWidthDp <= 0f || density <= 0f) return 1
    val availablePx = (availableWidthDp * density).roundToInt()
    val spacingPx = (spacingDp * density).roundToInt()
    val minPx = (cellMinDp * density).roundToInt()
    return maxOf(1, (availablePx + spacingPx) / (minPx + spacingPx))
}

/**
 * R171 底部弹层（ModalBottomSheet）统一限宽：平板巨屏上铺满整宽的面板没法看
 * （ActionSheet/FilterSheet/AudioCard/VideoHearts 既有手工规格即 560dp，此处收敛成
 * 共享件；AppSheet 统一壳与裸用面板一律走它，勿再私写数字）。
 * 手机竖屏屏宽 < 560dp 自然不触发（widthIn 语义：只在超宽时收）。
 * material3 1.3.0 默认值为 640dp（BottomSheetDefaults.SheetMaxWidth，字节码实证），
 * 与本口径不同——差异是既定事实（多数面板早已 560），统一到 560 而非 640。
 */
val SheetMaxWidth = 560.dp

// ---------------- R175 封面墙 pinch 列数档位（MediaWall 家族：档位=封面大小偏好） ----------------

/**
 * R175 pinch 最密档的格子最小宽（= 0.8 × [MediaWallCellMin]）：用户原话「有的用户
 * 喜欢密集、有的喜欢封面大」——密集偏好允许捏到比 R171 默认更密一档以上；84dp 的
 * 3:4 格子仍放得下时长角标（OverlayInfoChip），再小就不可读了。与 105 锚同文件
 * 同族，勿在调用方私写数字。
 */
val MediaWallPinchCellFloor = 84.dp

/**
 * R175 pinch 档位序列的最少列数：2 列（华为平板 640dp 下格宽即 317dp+，再大没意义）。
 * 上限不设常量——按当前屏宽在 [mediaWallPinchDetents] 现算（屏宽涌现）。
 */
const val MediaWallPinchMinColumns = 2

/**
 * R175 pinch 列数档位序列（纯函数）：[MediaWallPinchMinColumns .. maxCols] 的连续整数，
 * maxCols = 当前屏宽按 [MediaWallPinchCellFloor] 能容纳的列数（如华为平板 640dp 竖 →
 * [2..7]、1024dp 横 → [2..12]、手机 393dp → [2..4]）。比 R171 默认锚多出的头部
 * 档位即"密集偏好"空间。档位按列数表达（用户心智「几列」），落盘时换算成
 * [mediaWallDetentCellMin] 的格子宽（跨设备口径自洽的偏好载体）。
 */
fun mediaWallPinchDetents(availableWidthDp: Float, density: Float, spacingDp: Float = 0f): IntArray {
    val maxCols = mediaWallColumns(availableWidthDp, density, spacingDp, MediaWallPinchCellFloor.value)
    val lo = MediaWallPinchMinColumns
    val hi = maxOf(maxCols, lo)
    return IntArray(hi - lo + 1) { lo + it }
}

/**
 * R175 档位 [columns] 的代表格子最小宽（dp，Float）：取「恰好 columns 列满宽」的
 * 紧口径。px 域推导（dp 域直算会被取整打穿）：
 * n 列 ⟺ n ≤ (totalPx+spPx)/(minPx+spPx) < n+1，紧上界 minPx = totalPx/n − spPx，
 * 再留 1px 安全余量——dp→px→dp 往返最多 ±0.5px 漂移，紧口径 +1px 会掉进 n−1 列
 * （Adaptive 整除公式），−1px 则恒 ≥ n 列且远够不到 n+1（total ≥ 2 列 px 时），
 * 档位落点的确定性由此保证（单测往返不变式锚定）。
 * 返回值交给 `GridCells.Adaptive(x.dp)` 后实际列数恒等于 [columns]。
 */
fun mediaWallDetentCellMin(columns: Int, availableWidthDp: Float, density: Float, spacingDp: Float = 0f): Float {
    if (availableWidthDp <= 0f || density <= 0f || columns < 1) return MediaWallCellMin.value
    val totalPx = (availableWidthDp * density).roundToInt()
    val spPx = (spacingDp * density).roundToInt()
    val minPx = (totalPx / columns - spPx - 1).coerceAtLeast(1)
    return minPx / density
}

/**
 * R175 把（跨设备带来的）存档偏好夹回当前屏的合法档域：
 * 下界 [MediaWallPinchCellFloor]（不密过最密档），上界 = 2 列代表宽（不大于 2 列格子）。
 * 退化屏（连 2 列都放不下）上界可能低于下界——只夹下界，列数交给 Adaptive 的
 * max(1,·) 兜底。未捏过/异常值由调用方先回落默认锚。
 */
fun clampMediaWallCellPref(
    cellMinDp: Float,
    availableWidthDp: Float,
    density: Float,
    spacingDp: Float = 0f,
): Float {
    if (availableWidthDp <= 0f || density <= 0f) return MediaWallCellMin.value
    val floor = MediaWallPinchCellFloor.value
    val ceil = mediaWallDetentCellMin(MediaWallPinchMinColumns, availableWidthDp, density, spacingDp)
    return if (ceil >= floor) cellMinDp.coerceIn(floor, ceil) else cellMinDp.coerceAtLeast(floor)
}

/**
 * R175 手势→档位映射（纯函数）：捏开（scale > 1，双指张开）→ 列数变少、格子变大；
 * 捏合（scale < 1）→ 列数变多、格子变小。目标 = round(起始列数 / scale) 夹进档位域。
 * 起始列数锚定在手势开始瞬间（不是逐帧累乘）——跨阈值往返无累积漂移；双指中途
 * 换指/第三指落下由调用方重锚（防 span 跳变）。
 */
fun mediaWallPinchTargetColumns(startColumns: Int, scale: Float, detents: IntArray): Int {
    if (detents.isEmpty()) return startColumns
    if (startColumns <= 0) return detents.first()
    val raw = if (scale > 0f) startColumns / scale else startColumns.toFloat()
    return raw.roundToInt().coerceIn(detents.first(), detents.last())
}
