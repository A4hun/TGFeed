package dev.tgfeed.ui

import androidx.compose.ui.unit.dp
import dev.tgfeed.core.store.GridCanvasSlot
import dev.tgfeed.core.td.VideoItem

/**
 * 自由画布的几何与排位模型（R129 从 GridScreen.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：画布几何常量（单元尺寸 / 列数 / 横屏扁格比例 / 连续缩放域 / span 上限 /
 * PK 擂台格位）+ 逻辑格位 [CellRect] + 流式排位 [flowLayout] + 擂台钉位 [pkCellRect]。
 * 状态编排、换片链、渲染与手势仍在 GridScreen.kt 与各格子文件。
 */

/** 画布逻辑单元边长：每条内容默认占 1×1 单元，大格占 2×1 / 2×2 */
internal val CELL_SIZE = 120.dp

/** 画布逻辑宽度（单元数）：固定 4 单位宽，高度随条目流式排位增长 */
internal const val CANVAS_COLS = 4

/** 横屏逻辑单元高宽比（高 = 宽 × 10/16）：横屏视口矮，正方形单元会把格子拉得过高，扁格一屏能放下两行 */
internal const val LANDSCAPE_CELL_HEIGHT_FRACTION = 10f / 16f

/** 横屏 refit 高度基准：按"这么几行的高度 = 视口高"反推 scale 上限 */
internal const val LANDSCAPE_FIT_ROWS = 2

/**
 * 连续缩放范围（双指捏合，跟手无档位）。
 * R217 ⑥ 域放开（用户实锤「双指缩放应有固定上下限」）：旧 0.5~2.2 上下限太窄，
 * 放开到 0.1~10——极小端格子进 chromeless LOD（短边<80dp 只剩纯画面），极大端
 * 单格铺满数屏；refit/密度档/快照恢复钳制全走本域常量（一处改全局生效）。
 * clampTranslation 逻辑不动：内容窄于视口水平居中、矮于视口顶对齐，全域成立
 */
internal const val MIN_SCALE = 0.1f
internal const val MAX_SCALE = 10f

/**
 * span 上限（R149 ③ 由 2 提到 4）：用户实测反馈"右下角拖柄只支持上下扩大不支持左右，
 * 而且放大有限制"——横版视频默认就占 2×1（宽向本已顶到旧上限 2），拖柄横向拖动
 * 无档可换，只剩竖向 1→2 一档。放开到 4 后：横版格 2×1 → 3×1 → 4×1（整行铺满），
 * 竖向最高 4 行；宽度上限同时被 [flowLayout] 钳到画布列数（4 列），不会越界。
 */
internal const val MAX_SPAN = 4

/**
 * R217 ⑤ 宽高比感知的默认 span（masonry v1）：按内容宽高比首派格位——
 * 横向内容（w/h > [ASPECT_SPAN_FACTOR]）→ 2×1，纵向内容（h/w 同倍超宽）→ 1×2，
 * 方形/未知 → 1×1。竖版视频不再被塞进方格 Crop，横竖内容混排出砌墙式错落
 * （flowLayout 本就支持任意 spanW×spanH，2×2 拖拽档位已验证 spanH>1 全链路可用：
 * 格位/可见集/钳制/命中测试/幽灵/拖柄全按 c.w×c.h 折算）。
 * 阈值与旧「横版 2×1」首派口径同值（1.3），一处定义全局生效（纪律§一）。
 * 真·无限高瀑布流（LazyStaggeredGrid 级）工程量过大，按任务书 v1 口径挂账
 * （r217_notes 修复分期）。
 */
internal const val ASPECT_SPAN_FACTOR = 1.3f

/** [aspectSpan] 的纯判定（单元 span）：无尺寸信息（0/负）回落 1×1 */
internal fun aspectSpan(width: Int, height: Int): Pair<Int, Int> = when {
    width <= 0 || height <= 0 -> 1 to 1
    width > height * ASPECT_SPAN_FACTOR -> 2 to 1
    height > width * ASPECT_SPAN_FACTOR -> 1 to 2
    else -> 1 to 1
}

/** PK 擂台模式：单格占的画布单元宽（两格左右对半，正好铺满 CANVAS_COLS=4 列） */
internal const val PK_CELL_W = 2

/** PK 擂台模式：单格占的画布单元行数（2 行高的大格，画布总行数固定为它） */
internal const val PK_CELL_H = 2

/**
 * 画布逻辑格位：以单元为单位的矩形（x/y = 列/行，w/h = 占格数）。
 * R415：字段改 Float 以承载按行装箱（[justifiedLayout]）的分数格位；
 * 旧等分流式排位 / PK 钉位仍走 Int 次构造（值恒为整数）。
 */
internal data class CellRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    constructor(x: Int, y: Int, w: Int, h: Int) : this(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
}

// ==================== R415 按行装箱（justified rows） ====================

/** 装箱宽高比钳制下限（超竖内容最窄按 1:2 计，防竖条格过窄） */
internal const val JUSTIFY_MIN_ASPECT = 0.5f

/** 装箱宽高比钳制上限（超宽内容最宽按 2.4:1 计，防横条格独占一行仍过扁） */
internal const val JUSTIFY_MAX_ASPECT = 2.4f

/** 无尺寸信息（0 或负）时的回落宽高比：按主流横版 16:9 计 */
internal const val JUSTIFY_DEFAULT_ASPECT = 16f / 9f

/** 目标行高（单元数）：行高在此附近自适应，与旧 1×1 单元密度同档 */
internal const val JUSTIFY_TARGET_ROW_H = 1f

/** 像素宽高比 → 装箱用宽高比（无尺寸回落默认、极端值钳制，一处定义） */
internal fun justifyAspect(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) {
        JUSTIFY_DEFAULT_ASPECT
    } else {
        (width.toFloat() / height).coerceIn(JUSTIFY_MIN_ASPECT, JUSTIFY_MAX_ASPECT)
    }

/**
 * 按行装箱纯函数：按槽位顺序把内容逐行排进 [cols] 单元宽的画布，同行等高，
 * 行内宽度按各自宽高比分配，行高自适应使整行恰好填满画布宽。
 * 贪心收行：加入一条后行高 ≤ [targetRowH] 即收行，若"不加这条"的行高更接近目标
 * 则这条挪到下一行。尾行（未凑满）不拉伸：按目标行高、左对齐。
 * [cellAspect] = 单元像素宽/高（竖屏 1，横屏扁格 16/10），用于把像素宽高比换算到单元坐标。
 * 返回 槽位id→格位 与 画布总行数（向上取整）。
 */
internal fun justifiedLayout(
    slotIds: List<String>,
    aspects: Map<String, Float>,
    cols: Int,
    cellAspect: Float = 1f,
    targetRowH: Float = JUSTIFY_TARGET_ROW_H,
): Pair<Map<String, CellRect>, Int> {
    val result = LinkedHashMap<String, CellRect>()
    val width = cols.toFloat()
    // 行高（单元）= 画布宽 × 单元宽高比 ÷ 行内宽高比之和
    fun rowH(sum: Float): Float = width * cellAspect / sum
    var y = 0f
    val row = ArrayList<Pair<String, Float>>()
    var sum = 0f
    fun emit(h: Float) {
        var x = 0f
        for ((id, a) in row) {
            val w = a * h / cellAspect
            result[id] = CellRect(x, y, w, h)
            x += w
        }
        y += h
        row.clear()
        sum = 0f
    }
    for (id in slotIds) {
        val a = (aspects[id] ?: JUSTIFY_DEFAULT_ASPECT).coerceIn(JUSTIFY_MIN_ASPECT, JUSTIFY_MAX_ASPECT)
        val withH = rowH(sum + a)
        if (withH > targetRowH) {
            row += id to a
            sum += a
            continue
        }
        if (row.isNotEmpty() && kotlin.math.abs(rowH(sum) - targetRowH) < kotlin.math.abs(withH - targetRowH)) {
            emit(rowH(sum))
            row += id to a
            sum = a
            if (rowH(sum) <= targetRowH) emit(rowH(sum))
        } else {
            row += id to a
            sum += a
            emit(withH)
        }
    }
    if (row.isNotEmpty()) emit(targetRowH)
    val total = kotlin.math.ceil(y - 1e-3f).toInt().coerceAtLeast(if (result.isEmpty()) 0 else 1)
    return result to total
}

/**
 * 流式排位：按槽位顺序把每条的 spanW×spanH 矩形放进固定 [cols] 列宽的画布，
 * 从上到下逐行扫描第一个放得下且不与已占格冲突的位置（2×2 大格占四宫）。
 * 每行用一个 Int 位图掩码记录占用（cols ≤ 8 够用）。
 * 返回 槽位id→格位 与 画布总行数。
 */
internal fun flowLayout(
    slotIds: List<String>,
    spans: Map<String, Pair<Int, Int>>,
    cols: Int,
): Pair<Map<String, CellRect>, Int> {
    val rows = HashMap<Int, Int>()
    val result = LinkedHashMap<String, CellRect>()
    for (id in slotIds) {
        val span = spans[id] ?: (1 to 1)
        val w = span.first.coerceIn(1, minOf(MAX_SPAN, cols))
        val h = span.second.coerceIn(1, MAX_SPAN)
        var y = 0
        var placed = false
        while (!placed) {
            var x = 0
            while (x + w <= cols) {
                val mask = ((1 shl w) - 1) shl x
                var free = true
                for (dy in 0 until h) {
                    if (((rows[y + dy] ?: 0) and mask) != 0) {
                        free = false
                        break
                    }
                }
                if (free) {
                    for (dy in 0 until h) rows[y + dy] = (rows[y + dy] ?: 0) or mask
                    result[id] = CellRect(x, y, w, h)
                    placed = true
                    break
                }
                x++
            }
            y++
        }
    }
    val total = (rows.keys.maxOrNull() ?: -1) + 1
    return result to total
}

/** PK 擂台模式格位：第 0 格钉左半 (0,0)、第 1 格钉右半，各占 2×2 单元（不走流式排位） */
internal fun pkCellRect(index: Int): CellRect =
    CellRect(if (index <= 0) 0 else CANVAS_COLS - PK_CELL_W, 0, PK_CELL_W, PK_CELL_H)

// ==================== R230 分屏播放行为重设计（纯函数区） ====================

/**
 * 已看身份判定（双轨：key 精确 + fileUniqueId 跨频道同片）。
 * 与 LocalStore.loadSeenIdentities / gridLoadMore 的已看硬剔除同一口径，
 * 一处定义供恢复过滤与池内择片共用（纪律§一：同一判据不许多处各写一份）。
 */
internal fun isSeenIdentity(
    key: String,
    fileUniqueId: String,
    seenKeys: Set<String>,
    seenUids: Set<String>,
): Boolean = key in seenKeys || (fileUniqueId.isNotEmpty() && fileUniqueId in seenUids)

/**
 * R230 ③ 池内循环换片的择片纯判定（从 GridScreen.advanceFromPool 抽出，单测锚定）。
 * 档位（小者优先，同档取步进序首个）：
 *  0 未播过 && 未在他格展示 && 未见（首选）
 *  1 未播过 && 未见（允许同屏撞片）
 *  2 未播过 && 未在他格展示
 *  3 未在他格展示（本格全播过，纯循环重播）
 * 全池候选都「已播过且在他格展示」→ 返回 null（调用方原地重播，与旧三档 pass
 * 落空的兜底同语义：宁可重播不复制同屏撞片）。
 * R230 变化：已看内容（显示轨+真播轨两本账）从首选两档里剔除——分屏展示过且
 * 点过播放的不再被换片链主动供给，只在池子见底时兜底回供。
 * [displayed] 由调用方预先剔除当前格内容（沿用旧三档口径）。
 * 返回 (条目, 池内下标)；池只有一条/无候选 → null（调用方走兜底重播）。
 */
internal fun pickAdvanceCandidate(
    pool: List<VideoItem>,
    startIdx: Int,
    played: Set<String>,
    displayed: Set<String>,
    seenKeys: Set<String>,
    seenUids: Set<String>,
): Pair<VideoItem, Int>? {
    var best: Pair<VideoItem, Int>? = null
    var bestTier = 5
    for (step in 1 until pool.size) {
        val idx = (startIdx + step).mod(pool.size)
        val it = pool[idx]
        val tier: Int? = when {
            it.key !in played && it.key !in displayed &&
                !isSeenIdentity(it.key, it.fileUniqueId, seenKeys, seenUids) -> 0
            it.key !in played && !isSeenIdentity(it.key, it.fileUniqueId, seenKeys, seenUids) -> 1
            it.key !in played && it.key !in displayed -> 2
            it.key !in displayed -> 3
            else -> null // 已播过且在他格展示：不复制撞片（落空兜底=原地重播）
        }
        if (tier != null && tier < bestTier) {
            best = it to idx
            bestTier = tier
            if (tier == 0) break
        }
    }
    return best
}

/**
 * R230 ③ 快照恢复的已看过滤（纯函数）：[slots] 中「当前内容（contentKey）」命中
 * 已看双轨（R150B 显示轨 + R94 真播轨，同本 seen_ids.log）的槽位判为已看——重开
 * 分屏不再复活（用户口径「分屏格子墙的『下次不再打扰』」）。条目在表时带
 * fileUniqueId 跨频道判看，条目缺失按 contentKey 直判（不因缺本体放走已看）。
 * 基底内容（slotId）不单独判：格上展示的是当前内容；基底仅在回退路径登场，
 * 由恢复替位链与供给侧已看滤兜底。返回可恢复槽位（保持原序）。
 */
internal fun filterRestorableSlots(
    slots: List<GridCanvasSlot>,
    items: Map<String, VideoItem>,
    seenKeys: Set<String>,
    seenUids: Set<String>,
): List<GridCanvasSlot> = slots.filter { s ->
    val content = items[s.contentKey]
    !isSeenIdentity(s.contentKey, content?.fileUniqueId ?: "", seenKeys, seenUids)
}

/** R230 ④ 翻页方向：BOTTOM=画布底追加（既有）；TOP=画布顶预补（上滑长出新内容） */
internal enum class GridLoadDirection { BOTTOM, TOP }

/**
 * R230 ④ 顶部 overscroll 累计器（小状态机，纯 Kotlin 可单测）：
 * 画布钳在顶（atTop）且手指继续下拉（downward）时累计位移；过阈值触发一次顶部预补
 * 并清零（继续拉可再触发）；离开顶/上拉/非下拉方向即复位。触发后 [cooldownMs] 内
 * 不再触发（拉住不放防连发）。
 */
internal class TopPullTracker(
    private val thresholdPx: Float,
    private val cooldownMs: Long,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var accum = 0f
    // 初始置于「很久以前」：首个触发不受冷却窗误拦（时钟起点近 0 的测试/冷启场景）
    private var lastFireAt = Long.MIN_VALUE / 2

    /** 每次画布手势回调喂入；返回 true = 本次应触发顶部预补 */
    fun onPan(atTop: Boolean, downward: Boolean, panPx: Float): Boolean {
        if (!atTop || !downward || panPx <= 0f) {
            accum = 0f
            return false
        }
        accum += panPx
        val now = clock()
        if (accum >= thresholdPx && now - lastFireAt >= cooldownMs) {
            accum = 0f
            lastFireAt = now
            return true
        }
        return false
    }
}

/** R230 追加4 小窗连播队列长度：悬浮落区弹出时带上分屏池的后续供给条数 */
internal const val FLOATING_QUEUE_SIZE = 16

/**
 * R230 追加4 小窗连播队列构建（纯函数）：池内后续视频（仅视频——悬浮窗只服务画面）、
 * 排除弹出条目本身、排除**正在授权播放中**的格子内容（防同片双声）；封面格（未授权
 * 未播）内容可入队——R230 手动播放模型下它们不出声，窗与封面同片无冲突。
 * 16384 实锤修正：旧版把「画布上展示过」全部排除，池≈展示集时队列被饿死（弹出的窗
 * 播完无下文，与「连播」预期相悖）。
 */
internal fun buildFloatingQueue(
    pool: List<VideoItem>,
    playingKeys: Set<String>,
    excludeKey: String,
): List<VideoItem> = pool.filter {
    it.mediaType == 0 && it.key != excludeKey && it.key !in playingKeys
}.take(FLOATING_QUEUE_SIZE)

