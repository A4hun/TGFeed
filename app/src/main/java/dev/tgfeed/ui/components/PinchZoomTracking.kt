package dev.tgfeed.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.PinchCleanPolicy
import kotlin.math.abs

/**
 * R174 双指捏合缩放手势（从 VideoPage 抽出的共享件——纪律§一收敛：PhotoCard
 * 移植不许抄第二份私有手势）。
 *
 * **挂载位（R174 真机实锤修复）**：必须挂在「事件必然流经的祖先节点」的修饰链上
 * （如 VideoPage 的页面根 Box / PhotoCard 的卡片根 Box）——Compose 兄弟层命中
 * 测试是排他的：后声明者（前景）独占命中路径，背后的兄弟层一个事件都收不到
 * （R170 横滑层判别实验同因；旧版 pinch Box 声明在 tap 层之前=死层，捏合功能
 * 自 R131 拆分起在生产上从未生效）。祖先节点的 pointerInput 天然在完整命中路径
 * 上，且 Initial pass（root→leaf）先于一切后代的 Main pass——本层消费位移后，
 * pager 的翻页判定、tap 层的 slop 都只看得到已消费事件。
 *
 * 策略（VideoPage 产线方案搬运+口径修正）：
 * 双指一旦按下（multiTouch）立即在 Initial pass 消费全部位移事件——否则双指
 * 落下后的头几帧若纵向分量先过 touch slop，事件流就被外层 VerticalPager 抢走
 * 开始翻页（此后缩放层再也拿不稳这次手势，慢速/偏竖向的捏合「完全没反应」）；
 * 位移被消费后后代手势层的 tap/长按 slop 同样过不去，双指操作期间不会误触发
 * 暂停/功能面板。只消费「有位移」的事件、down/up 不碰：单指且未缩放时完全放行；
 * 已缩放（zoom≠1）时单指位移归本层平移画面（复位回 1x 后即可正常翻页）。
 *
 * R174b 增量（自由缩放三件套之一/之二，去 1x 地板 + pan+zoom 一体）：
 * - **去地板**：scale 软界 [ZOOM_MIN_SCALE=0.25, 4]，不再钳 1f 下限——视频可
 *   缩到窗口中间（周围透底衬，页背景/底衬接线见 BackdropPolicy.zoomedOut 列）；
 * - **平移夹域**（R194③ 自由平移改刀）：夹「内容带」而不是「图层」——可见内容
 *   可拖到窗内任意角落（缩小态零出屏），放大态部分出屏走保守界（窗轴可见
 *   ≥75%，见 PinchCleanPolicy.freePanMaxOffsetAxis）；平移在任意缩放态生效
 *   （含 <1x：双指质心位移=平移，视频跟手指走；单指过 10dp slop 同接管）；
 * - **收尾吸附**：|zoom−1|<ε 时手势收尾吸回整 1x（ε=0.1%，见
 *   [PinchCleanPolicy.ZOOM_SNAP_EPSILON]——防浮点残值卡复位 chip/平移闸）；
 *   R194③ 起吸附另要求 offset 残值在旧夹域量级（大位移近 1x 态合法驻留）。
 *
 * R174 增量（清屏联动口径，[PinchCleanPolicy] 配套）：
 * - [onZoomChange] 只在「缩放真的变化」的帧上抛（calculateZoom≠1）——
 *   已放大态的双指平移、加载/复位期间的杂帧不会误触发 chrome 重映射；
 *   restore 后两指平移画面（zoom 不变）不会把刚恢复的 chrome 再次清场。
 * - [onGestureStart] 在本层首次接管事件流时回调（调用方取消双击复位弹簧等
 *   正在动画写 zoomScale 的协程，避免双写互搏）。
 */
internal fun Modifier.pinchZoomTracking(
    key: Any?,
    zoomScale: MutableState<Float>,
    zoomOffset: MutableState<Offset>,
    onZoomChange: (oldZoom: Float, newZoom: Float) -> Unit = { _, _ -> },
    onGestureStart: () -> Unit = {},
    // R194③ 自由平移：内容带宽高比（宽/高）提供者——FIT letterbox 内容（视频带/
    // 图片）夹域按内容矩形算（可见内容可拖到窗内任意角落）；null=内容铺满视口
    // （ZOOM 填满/比未知），走旧图层口径。每事件现读（实测比/封面到货后自动换带）
    contentAspect: () -> Float? = { null },
    // R194③ 平移基点（内容静止位相对窗心的偏移，如 R188 贴底下锚 shift）——
    // 夹域围绕基点而不是窗心（贴底态内容可到上角，不被 shift 顶出下界）
    panBase: () -> Offset = { Offset.Zero },
    // R299 B2 查看器态闸：false=本层全放行（查看器是卡根最顶兄弟层 modal 全消费，
    // 卡级捏合必须整体让位，否则查看器内捏合会同时驱动清屏映射——同一手势两主）。
    // 每手势开场（awaitFirstDown 之后）+每事件现读：关=本轮零消费、等抬手挂起
    // 收尾（R306：闸判严禁放在开场挂起点之前——awaitEachGesture 块间零挂起，
    // 秒退即主线程死循环自旋，V11 ANR 实锤）
    enabled: () -> Boolean = { true },
    // R299 B2 手势收尾钩：手指全抬后回传收尾缩放值（查看器进入判定「收尾≥阈值」
    // 的取数点——onZoomChange 是逐帧语义，收尾判定只能在手势结束时刻做）
    onGestureEnd: (endScale: Float) -> Unit = {},
): Modifier = this
    .pointerInput(key) {
        awaitEachGesture {
            // R306（V11 ANR 真机实锤，traces=r292_shots/r290c_v11_anr.txt）：
            // awaitEachGesture 契约=block 必须以「挂起等下一手势」开场——其块间
            // 只跑 awaitAllPointersUp（空闲时零挂起直返）。enabled 闸若放在开场
            // 直接 return（R299 旧写法），查看器开启期间每次手势收尾后块秒退 →
            // 块间零挂起 → 主线程 while 同步死循环自旋（main Runnable 钉死在本块、
            // 235% CPU、不自愈、ANR 双发）。故先无条件 awaitFirstDown 挂起，
            // 闸判放其后：查看器开=本轮手势零消费全放行，等抬手由外层
            // awaitAllPointersUp 正常挂起收尾——真不消费、真退让、零自旋。
            awaitFirstDown(requireUnconsumed = false)
            // R299 B2：查看器态全放行（不消费任何事件——单指/双指/位移全归查看器层）
            if (!enabled()) return@awaitEachGesture
            // 双指捏合：两根手指不可能同时落下——第一根先落先动，pager 已经把
            // 竖滑抢走翻页了，第二根落下时手势已归 pager（捏合永远轮不到）。
            // 修法：盯"第二根手指落下"这个瞬间——它一落，立刻在 Initial pass
            // 消费全部位移（pager 的 Main pass 只能看到已消费的事件，抢不走），
            // 然后手写缩放+平移（detectTransformGestures 的识别延迟会输给 pager）
            var twoFinger = false
            var branchEngaged = false
            // 缩放态（≠1x，含 R174b 起的 <1x）的单指手势要过 slop 才转平移接管：
            // 否则按下事件（positionChanged 对 down 恒真）会被本层消费，tap 层的
            // detectTapGestures 永远收不到干净 down——缩放态单击（清屏恢复/暂停/
            // 复位 chip）全死（R174 真机实锤修）
            var singlePan = false
            val panSlopPx = 10.dp.toPx()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // R306 防御：查看器中途开启（现行入口都在手势边界翻闸，但状态翻转
                // 时序不做假设）——即刻停手，本事件起零消费全放行；收尾吸附与
                // onGestureEnd 只在「enabled 期间完整收尾」的手势上跑（下方 break 路径）
                if (!enabled()) return@awaitEachGesture
                val pressed = event.changes.filter { it.pressed }
                // R178 探针降频（父代理指令，R174 验收已过）：per-event 级日志全删
                // （本层每事件一条 + zoom 逐帧一条的刷屏源）；状态机转移级一行日志
                // 保留在 PinchCleanHost.dispatch——排查时看那一条就够
                if (pressed.size >= 2) twoFinger = true
                // slop 判定只累计「已在按下的指针」的位移（down 帧的 change 语义
                // 不算移动）；tap 层在系统 slop（更小）处先取消 tap，本层接管后
                // 与之无冲突
                if (!twoFinger && !singlePan && zoomScale.value != 1f) {
                    val moved = event.changes
                        .filter { !it.changedToDown() }
                        .map { it.positionChange().getDistance() }
                        .sum()
                    if (moved > panSlopPx) singlePan = true
                }
                if (twoFinger || singlePan) {
                    if (!branchEngaged) {
                        branchEngaged = true
                        onGestureStart() // 让位：取消正在动画写 zoom 的复位弹簧
                    }
                    // 双指在场（或单指平移已过 slop）：Initial pass 消费全部位移
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    val zoom = event.calculateZoom()
                    // 双指质心位移：pan+zoom 一体（R174b——视频跟手指走，
                    // <1x 缩小态同样生效，不再只放大态应用）
                    val pan = event.calculatePan()
                    val oldScale = zoomScale.value
                    // 软界 0.25~4（R174b 去 1x 地板）：超界钳值不弹回
                    val newScale = (oldScale * zoom)
                        .coerceIn(PinchCleanPolicy.ZOOM_MIN_SCALE, PinchCleanPolicy.ZOOM_MAX_SCALE)
                    // R194③ 自由平移夹域：按「内容带」算（PinchCleanPolicy.
                    // freePanMaxOffsetAxis——可见内容可拖到窗内任意角落，放大态
                    // 部分出屏走保守界）。旧对称式 |s−1|·边/2 夹的是图层（含
                    // letterbox 余量），可见内容被锁在窗心附近=用户实测
                    // 「拖不到屏幕各个角落」的根因。内容静止位有基点偏移（R188
                    // 贴底 shift）时夹域围绕基点。
                    val base = panBase()
                    val (c0w, c0h) = contentAspect()?.let { a ->
                        PinchCleanPolicy.fitContentSpan(a, size.width.toFloat(), size.height.toFloat())
                    } ?: (size.width.toFloat() to size.height.toFloat())
                    val maxX = PinchCleanPolicy.freePanMaxOffsetAxis(c0w * newScale, size.width.toFloat())
                    val maxY = PinchCleanPolicy.freePanMaxOffsetAxis(c0h * newScale, size.height.toFloat())
                    zoomScale.value = newScale
                    zoomOffset.value = Offset(
                        // 内容带中心 = 基点 + offset；钳内容带中心在 ±max 内再回减基点
                        (zoomOffset.value.x + base.x + pan.x).coerceIn(-maxX, maxX) - base.x,
                        (zoomOffset.value.y + base.y + pan.y).coerceIn(-maxY, maxY) - base.y,
                    )
                    // 清屏联动只挂缩放变化：平移帧（zoom==1）不动 chrome 映射
                    if (newScale != oldScale) {
                        onZoomChange(oldScale, newScale)
                    }
                }
                if (pressed.isEmpty()) break
            }
            // 手势收尾贴 1x（R174b）：|z−1|<ε 吸回整 1x——防浮点残值让复位 chip
            // 判据（≠1）永真/单指平移闸吞翻页。
            // R194③ 吸附判据补 offset 侧：自由平移后 |z−1|<ε 不再蕴含 offset≈0
            // （旧夹域把 offset 锁死在 |s−1|·边/2≈ε 量级内，新夹域允许大位移）——
            // 带大位移的近 1x 态是合法驻留态（复位 chip 在场可复位），不许被吸附
            // 吞掉跳回窗心；offset 残值仍在旧夹域量级（≤|s−1|·边/2+1px）才吸。
            val snapScale = abs(zoomScale.value - 1f) < PinchCleanPolicy.ZOOM_SNAP_EPSILON
            val residueX = abs(zoomOffset.value.x) <= abs(zoomScale.value - 1f) * size.width / 2f + 1f
            val residueY = abs(zoomOffset.value.y) <= abs(zoomScale.value - 1f) * size.height / 2f + 1f
            val snapped = snapScale && residueX && residueY
            if (snapped) {
                zoomScale.value = 1f
                zoomOffset.value = Offset.Zero
            }
            // R174b 转移级探针（R178 降频令口径：每手势一条非逐帧）：收尾几何终值
            // ——zoom/offset 真身不在状态机里，dispatch 日志看不见 <1x 段（α 恒 1
            // 无转移），自由缩放的实机取证锚点在此
            android.util.Log.d(
                "R174b",
                "pinch end z=${"%.3f".format(zoomScale.value)} " +
                    "off=(${zoomOffset.value.x.toInt()},${zoomOffset.value.y.toInt()})" +
                    if (snapped) " snap" else "",
            )
            // R299 B2：收尾缩放值上抛（查看器进入判定取数点；吸附后的真值）
            onGestureEnd(zoomScale.value)
        }
    }
