package dev.tgfeed.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import dev.tgfeed.core.tastemap.GestureFeelPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * R231 飞行手势状态机（纯 Kotlin 零 Compose 依赖，单测可跑）——用户原创手势
 * 方案的判定核心：
 *
 *  - **两指捏合=缩放 / 两指一起移=平移 / 双指旋转=滚转**（现有语义照旧，数学
 *    逐项复刻 detectTransformGestures：pan=质心增量 / zoom=指距比 / rotation=
 *    夹角差——仍走 TasteFlight.gestureStep 唯一映射，R210 红线不破）；
 *  - **一指按住（近静止）+ 另一指纵向显著（≥engage 且 |dy| 占优）= 纵向手势
 *    （FLIGHT 模式）**——机器本身语义无关：只产出动指的 dy 增量（[Out.moveDy]，
 *    R231 消费为 dive 纵深飞行 / **R241 起消费为俯仰 pitch**——上滑=pitch 增加
 *    即仰头，墙→棚顶）；**一锁定制死（与捏合/平移互斥）**：锁定后本冲程不再
 *    改判；动指抬起锚指还在=再补指续推（多冲程），锚指抬起=手势终结（余指
 *    冻结防跳变）；
 *  - **R237 比率判定（真机实锤修法）**：R231 的「锚指绝对静止 <8dp」在 MuMu
 *    注入（零抖动）成立、真人手指微颤抖 1~5dp 恒超阈 → 被判成「两指都在动」
 *    → 捏合抢走=俯冲永不识别。改为**相对判定**：锚指位移 <动指位移×
 *    [GestureFeelPolicy.ANCHOR_MOVE_RATIO]（∨ 仍 <slopPx 的绝对静止子集）即锚指
 *    成立——锚指允许颤抖，只要动指动得显著多；中间地带（0.3~0.7 比率带）捏合
 *    优先保底。R268b：判定常数全部收口 GestureFeelPolicy（本类零手感魔数）。
 *  - 判定时机：两指落定后 UNDECIDED 态逐事件评估（=「头 3 事件」窗口超集——
 *    晚起手也能锁），谁先越过阈值谁定模式（阈内抖动静默）；单指起手=平移
 *    直通（无 slop——跟手零回归）。
 *
 * 输入协议：每帧事件批 beginBatch → onDown/onMove/onUp（每变更一次）→
 * endBatch 产出 [Out]（或 null=本批无产出）。Out 为机器持有的单例复用对象
 * （调用方同步消费，不持有引用——手势路径零分配纪律）。
 *
 * R237 锚点视觉反馈：[ui]（[AnchorUi] 读侧快照）随每批更新——FLIGHT/ARMED=
 * 锚指位亮环、UNDECIDED 双指在场=候选锚指暗环（**锁定前就有屏幕信号**，
 * 用户知道手势被看见了）。适配层批后同步进调用方持有实例，零分配零重组。
 */
internal class FlightGestureMachine(
    /** 锚指静止判定阈（px，R231 任务书 8dp——R237 起仅为比率判定的绝对静止子集） */
    private val slopPx: Float,
    /** 动指纵向显著判定阈（px，≈1.75×slop） */
    private val engagePx: Float,
) {
    /** 手势锚点反馈快照（读侧——适配层同步给 UI 绘制；非快照状态无重组成本） */
    internal class AnchorUi {
        var visible = false      // 双指在场且未锁变换（候选/锁定锚指）
        var x = 0f               // 锚指当前屏幕位（环跟随手指）
        var y = 0f
        var engaged = false      // 纵深模式已锁定（亮环档）；false=待判暗环档
        var dirUp = true         // 最近一帧动指方向（true=上推；箭头朝向用——R241 上推=俯仰增加/仰头）

        fun reset() {
            visible = false; x = 0f; y = 0f; engaged = false; dirUp = true
        }

        /** 从机器快照整值同步（每批一次——调用方持有实例零分配） */
        fun syncFrom(o: AnchorUi) {
            visible = o.visible
            x = o.x
            y = o.y
            engaged = o.engaged
            dirUp = o.dirUp
        }
    }

    // R268b：锚指判定常数（比率容差/纵向主导/倍率钳位）收口 GestureFeelPolicy
    // 唯一出处——本类零手感魔数（见 classify/emitTransform 直引）。

    /** 手势帧输出（机器复用单例；flight=true 时只有 moveDy，否则只有 pan/zoom/rotation） */
    internal class Out {
        var flight = false          // 纵向手势产出（FLIGHT 模式——语义由消费方定义：R231 dive → R241 俯仰）
        var moveDy = 0f             // 动指本批 Y 增量（px，屏幕向下为正；消费方换符号：R241 上推=俯仰增加）
        var panX = 0f               // 平移增量（px，右下正——与 detectTransformGestures 同口径）
        var panY = 0f
        var zoomFactor = 1f         // 指距比（>1 张开=放大）
        var rotationDeg = 0f        // 夹角差（度，顺时针正——度直传 gestureStep）
        var pivotX = 0f             // 缩放质心（px）
        var pivotY = 0f
        var probe: String? = null   // 模式切换探针（logcat 取证用，读后即焚）

        fun reset() {
            flight = false; moveDy = 0f; panX = 0f; panY = 0f
            zoomFactor = 1f; rotationDeg = 0f; pivotX = 0f; pivotY = 0f
        }
    }

    /** 一根手指的跟踪态：start=冲程基线（分类用）、last=上一事件位（增量口径） */
    private class Ptr(x0: Float, y0: Float) {
        var startX = x0
        var startY = y0
        var x = x0
        var y = y0
        var lastX = x0
        var lastY = y0

        fun rebase(x0: Float, y0: Float) {
            startX = x0; startY = y0; x = x0; y = y0; lastX = x0; lastY = y0
        }
    }

    private enum class Mode { UNDECIDED, TRANSFORM, FLIGHT, FLIGHT_ARMED, DEAD }

    private val pointers = LinkedHashMap<Long, Ptr>()
    private var mode = Mode.UNDECIDED
    private var movingId = -1L
    private var anchorId = -1L
    private val out = Out()
    /** R237 锚点视觉反馈快照（读侧——见类 KDoc） */
    val ui = AnchorUi()

    // TRANSFORM 基线（上批质心 / 首两指距离与夹角）
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var hasPairBaseline = false

    fun reset() {
        pointers.clear()
        mode = Mode.UNDECIDED
        movingId = -1
        anchorId = -1
        hasPairBaseline = false
        out.reset()
        ui.reset()
    }

    fun beginBatch() {
        out.reset()
        out.probe = null
    }

    fun onDown(id: Long, x: Float, y: Float) {
        when (mode) {
            Mode.DEAD -> return // 手势已终结（锚指走了）：余指冻结直到全部抬起
            Mode.FLIGHT, Mode.FLIGHT_ARMED -> {
                // 纵深模式补指=新动指（多冲程）；锚指恒为按住不动的原指
                if (anchorId in pointers) {
                    if (movingId !in pointers) {
                        movingId = id
                        if (mode == Mode.FLIGHT_ARMED) {
                            mode = Mode.FLIGHT
                            out.probe = "R241 手势: 俯仰续推"
                        }
                    }
                    pointers[id] = Ptr(x, y)
                } else {
                    pointers[id] = Ptr(x, y)
                }
            }
            else -> {
                pointers[id] = Ptr(x, y)
                if (pointers.size == 2) {
                    // 第二指落定：进入分类待判（两指冲程基线=当前位）
                    mode = Mode.UNDECIDED
                    for (p in pointers.values) p.rebase(p.x, p.y)
                    hasPairBaseline = false
                } else if (pointers.size > 2 && mode == Mode.TRANSFORM) {
                    // 第三指：平移质心基线重置（防质心跳变），zoom/rotation 仍取首两指
                    rebaseCentroid()
                }
            }
        }
    }

    fun onMove(id: Long, x: Float, y: Float) {
        val p = pointers[id] ?: return
        p.x = x
        p.y = y
    }

    fun onUp(id: Long) {
        when (mode) {
            Mode.FLIGHT -> {
                if (id == anchorId) {
                    mode = Mode.DEAD // 锚指抬起=纵深手势终结
                    out.probe = "R241 手势: 俯仰松手"
                } else if (id == movingId) {
                    mode = Mode.FLIGHT_ARMED // 动指抬起：锚还在，等补指续推
                }
                pointers.remove(id)
            }
            Mode.FLIGHT_ARMED -> {
                if (id == anchorId) mode = Mode.DEAD
                pointers.remove(id)
            }
            Mode.DEAD -> {
                pointers.remove(id)
                if (pointers.isEmpty()) reset()
            }
            else -> {
                pointers.remove(id)
                if (pointers.isEmpty()) {
                    reset()
                } else {
                    hasPairBaseline = false // 剩单指：zoom/rotation 停，平移照流
                    if (mode == Mode.UNDECIDED && pointers.size == 1) {
                        // 两指未判定先抬一指：退化为单指平移
                        mode = Mode.TRANSFORM
                        rebaseCentroid()
                    } else if (mode == Mode.TRANSFORM) {
                        rebaseCentroid()
                    }
                }
            }
        }
    }

    /** 事件批收口：产出本批手势（null=无产出）；每批同步 [ui] 锚点反馈快照 */
    fun endBatch(): Out? {
        if (pointers.isEmpty()) {
            ui.visible = false
            ui.engaged = false
            return null
        }
        when (mode) {
            Mode.DEAD -> ui.visible = false
            Mode.FLIGHT_ARMED -> updateAnchorUi() // 亮环保持（锚还在，等补指续推）
            Mode.FLIGHT -> {
                updateAnchorUi() // 亮环钉在锚指当前位置（环跟随手指）
                emitFlight()
            }
            Mode.UNDECIDED -> {
                if (pointers.size == 1) {
                    // 单指起手=平移直通（无 slop——跟手零回归）
                    mode = Mode.TRANSFORM
                    rebaseCentroid()
                    ui.visible = false
                    return null
                }
                if (pointers.size >= 2) {
                    classify()
                    // 锚定后本批 ui 已由 classify/engageFlight 写入；未锁定=候选锚指暗环
                    if (mode == Mode.UNDECIDED) updateCandidateAnchor()
                    else if (mode == Mode.TRANSFORM) ui.visible = false // 锁变换同批清环
                }
                return null // 锁定批只切模式不产出（基线重置下批起流出）
            }
            Mode.TRANSFORM -> {
                ui.visible = false // 捏合/平移域：无锚概念
                emitTransform()
            }
        }
        return out.takeIf { it.flight || it.panX != 0f || it.panY != 0f || it.zoomFactor != 1f || it.rotationDeg != 0f }
    }

    /** FLIGHT/ARMED 的锚指亮环同步（锚指不在=环隐） */
    private fun updateAnchorUi() {
        val a = pointers[anchorId]
        if (a != null) {
            ui.visible = true
            ui.engaged = true
            ui.x = a.x
            ui.y = a.y
        } else {
            ui.visible = false
        }
    }

    // ---- UNDECIDED 分类：一锁定制死（本批只切模式不产出，基线重置下批起流出） ----
    // R237 比率判定：按位移大小取动指/锚指——锚指允许颤抖（<0.3×动指位移），
    // 只要动指纵向动得显著多；中间比率带（0.3~0.7）捏合优先（父代理 d1f71f3 方案）。
    private fun classify() {
        val it2 = pointers.entries.take(2).map { it.key to it.value }
        val (id1, p1) = it2[0]
        val (id2, p2) = it2[1]
        val d1x = p1.x - p1.startX
        val d1y = p1.y - p1.startY
        val d2x = p2.x - p2.startX
        val d2y = p2.y - p2.startY
        val m1 = hypot(d1x, d1y)
        val m2 = hypot(d2x, d2y)
        // 纵深：一指近静止（<slop 绝对静止 ∨ <0.3×动指位移——真机颤抖容差）+
        // 另一指纵向显著（≥engage 且 |dy|>|dx|×1.15）——判定常数收口 GestureFeelPolicy
        fun vertical(dx: Float, dy: Float) =
            abs(dy) >= engagePx && abs(dy) > abs(dx) * GestureFeelPolicy.ANCHOR_VERTICAL_DOMINANCE

        fun anchorTolerant(mSmall: Float, mBig: Float) =
            mSmall < slopPx || mSmall < GestureFeelPolicy.ANCHOR_MOVE_RATIO * mBig
        when {
            m1 >= m2 && vertical(d1x, d1y) && anchorTolerant(m2, m1) -> engageFlight(id1, id2)
            m2 > m1 && vertical(d2x, d2y) && anchorTolerant(m1, m2) -> engageFlight(id2, id1)
            maxOf(m1, m2) > slopPx -> {
                mode = Mode.TRANSFORM
                out.probe = "R237 手势: 变换锁定（锚漂=${"%.0f".format(minOf(m1, m2))}px " +
                    "动指=${"%.0f".format(maxOf(m1, m2))}px 比率=${"%.2f".format(if (maxOf(m1, m2) > 0f) minOf(m1, m2) / maxOf(m1, m2) else 0f)}）"
                rebaseCentroid()
                rebasePair()
            }
            else -> {} // 双指均未过阈：静默
        }
    }

    /** UNDECIDED 未锁定态的候选锚指（位移较小的指）暗环——锁定前就有反馈（零分配） */
    private fun updateCandidateAnchor() {
        var p1: Ptr? = null
        var p2: Ptr? = null
        for (e in pointers.entries) {
            if (p1 == null) p1 = e.value else {
                p2 = e.value
                break
            }
        }
        val a = p1 ?: return
        val b = p2 ?: return
        val m1 = hypot(a.x - a.startX, a.y - a.startY)
        val m2 = hypot(b.x - b.startX, b.y - b.startY)
        val cand = if (m1 <= m2) a else b
        ui.visible = true
        ui.engaged = false
        ui.x = cand.x
        ui.y = cand.y
    }

    private fun engageFlight(moving: Long, anchor: Long) {
        mode = Mode.FLIGHT
        movingId = moving
        anchorId = anchor
        // 动指的增量基线=当前位（锁定前的位移不灌进第一帧 dive）
        pointers[moving]?.let { it.lastX = it.x; it.lastY = it.y }
        // R237 亮环立起（锁定即反馈——用户知道手势已被识别）
        val a = pointers[anchor]
        if (a != null) {
            ui.visible = true
            ui.engaged = true
            ui.x = a.x
            ui.y = a.y
            val drift = hypot(a.x - a.startX, a.y - a.startY)
            val mv = pointers[moving]
            val mvLen = if (mv != null) hypot(mv.x - mv.startX, mv.y - mv.startY) else 0f
            out.probe = "R241 手势: 俯仰锁定（锚漂=${"%.0f".format(drift)}px 动指=${"%.0f".format(mvLen)}px）"
        } else {
            out.probe = "R241 手势: 俯仰锁定"
        }
    }

    // ---- 产出：变换（pan=质心增量 / zoom=首两指距比 / rotation=夹角差） ----
    private fun emitTransform() {
        val n = pointers.size
        var cx = 0f
        var cy = 0f
        for (p in pointers.values) { cx += p.x; cy += p.y }
        cx /= n
        cy /= n
        out.panX = cx - lastCx
        out.panY = cy - lastCy
        out.pivotX = cx
        out.pivotY = cy
        if (n >= 2) {
            val pair = pointers.values.take(2)
            val p1 = pair[0]
            val p2 = pair[1]
            val dist = hypot(p2.x - p1.x, p2.y - p1.y)
            val angle = Math.toDegrees(atan2(p2.y - p1.y, p2.x - p1.x).toDouble()).toFloat()
            if (hasPairBaseline && dist > 1f) {
                out.zoomFactor = (dist / lastDist).coerceIn(
                    GestureFeelPolicy.PINCH_EVENT_FACTOR_MIN,
                    GestureFeelPolicy.PINCH_EVENT_FACTOR_MAX,
                )
                var dr = angle - lastAngle
                while (dr > 180f) dr -= 360f
                while (dr < -180f) dr += 360f
                out.rotationDeg = dr
            }
            lastDist = dist
            lastAngle = angle
            hasPairBaseline = true
        } else {
            hasPairBaseline = false
            out.zoomFactor = 1f
            out.rotationDeg = 0f
        }
        lastCx = cx
        lastCy = cy
    }

    // ---- 产出：纵向手势（动指 dy 增量；锚指/被动指位移不产出——消费方 R241 起为俯仰） ----
    private fun emitFlight() {
        val m = pointers[movingId] ?: return
        out.flight = true
        out.moveDy = m.y - m.lastY
        if (out.moveDy != 0f) ui.dirUp = out.moveDy < 0f // 上推（dy<0）=俯仰增加（仰头）
        m.lastX = m.x
        m.lastY = m.y
    }

    private fun rebaseCentroid() {
        if (pointers.isEmpty()) return
        var cx = 0f
        var cy = 0f
        for (p in pointers.values) { cx += p.x; cy += p.y }
        lastCx = cx / pointers.size
        lastCy = cy / pointers.size
    }

    private fun rebasePair() {
        if (pointers.size >= 2) {
            val pair = pointers.values.take(2)
            lastDist = hypot(pair[1].x - pair[0].x, pair[1].y - pair[0].y)
            lastAngle = Math.toDegrees(atan2(pair[1].y - pair[0].y, pair[1].x - pair[0].x).toDouble()).toFloat()
            hasPairBaseline = true
        } else {
            hasPairBaseline = false
        }
    }
}

/**
 * R231 飞行手势 Compose 适配层（替换 detectTransformGestures 的调用点）：
 * 原始事件批喂 [FlightGestureMachine]（同一节点事件必经），产出才消费
 * position change（tap 兄弟层只吃 down/up，不受影响）。判定/量纲全在机器与
 * TasteFlight，本层只做接线。awaitEachGesture 每轮=一次完整手势（首指落下
 * →全部抬起），机器跨轮 reset。
 *
 * R237 [anchorOut]：调用方持有的锚点反馈快照实例（每批同步——零分配零重组，
 * draw 相位直读；传 null=不关心视觉反馈）。
 */
internal suspend fun PointerInputScope.detectFlightGestures(
    slopPx: Float,
    engagePx: Float,
    onGesture: (FlightGestureMachine.Out) -> Unit,
    anchorOut: FlightGestureMachine.AnchorUi? = null,
) {
    val machine = FlightGestureMachine(slopPx, engagePx)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        machine.reset()
        machine.beginBatch()
        machine.onDown(down.id.value, down.position.x, down.position.y)
        machine.endBatch() // 首事件只登记不产出
        while (true) {
            val event = awaitPointerEvent()
            machine.beginBatch()
            for (c in event.changes) {
                if (!c.pressed) {
                    machine.onUp(c.id.value)
                } else if (!c.previousPressed) {
                    machine.onDown(c.id.value, c.position.x, c.position.y)
                } else {
                    machine.onMove(c.id.value, c.position.x, c.position.y)
                }
            }
            val out = machine.endBatch()
            anchorOut?.syncFrom(machine.ui)
            if (out != null) {
                for (c in event.changes) {
                    if (c.pressed && c.positionChanged()) c.consume()
                }
                onGesture(out)
            }
            if (event.changes.none { it.pressed }) {
                machine.reset()
                anchorOut?.syncFrom(machine.ui)
                break
            }
        }
    }
}

/**
 * R241 方向调试开关（拖拽 y 方向远程翻转——「方向反」第三报的根治通道）：
 * Settings.Global 键 `taste_pan_y_flip`——`adb shell settings put global
 * taste_pan_y_flip 1` 立即写入、≤5s 后生效，**无需重装无需重启**；0/缺省=现状
 * 数学（代码/MuMu/单测三方正确的方向）。
 *  - 读取成本：后台协程周期轮询缓存进 [yFlip]（@Volatile 读），**手势线程零
 *    provider 查询**（60fps 红线——Settings 读取是 binder 往返，绝不能进事件路径）；
 *  - 消费点：TasteMapScreen 手势消费单一入口（panY/moveDy 一次取反——pan 与
 *    惯性 fling 同源变量自动一致；zoom/roll 不受影响）；
 *  - 权限模型：Settings.Global 读对 App 放行、写需 WRITE_SECURE_SETTINGS（adb
 *    shell 持有）——真机远程诊断的安全通道（与 R184 agent_verify_search_quota
 *    同工具族）。日志：值变化时打 TasteMap 标签（logcat 取证）。
 */
internal object TastePanDirProbe {
    /** 拖拽 y 方向翻转（true=dy 取反——方向类手势的纵向全量翻转）。轮询协程写、手势线程读 */
    @Volatile
    var yFlip = false

    private var announced = -1

    /** 轮询循环（LaunchedEffect 挂起调用；IO 线程读 Settings，5s 周期） */
    suspend fun pollLoop(context: android.content.Context) {
        val resolver = context.contentResolver
        while (true) {
            val v = withContext(Dispatchers.IO) {
                runCatching {
                    android.provider.Settings.Global.getInt(resolver, "taste_pan_y_flip", 0)
                }.getOrDefault(0)
            }
            yFlip = v == 1
            if (v != announced) {
                announced = v
                android.util.Log.i("TasteMap", "R241 方向调试: taste_pan_y_flip=$v（y翻转=${if (yFlip) "开" else "关"}）")
            }
            delay(5_000L)
        }
    }
}
