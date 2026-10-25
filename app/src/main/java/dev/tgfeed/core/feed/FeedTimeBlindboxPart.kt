package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

// ---------------- R204 TL-3 时间盲盒（随机时刻+随机频道+该秒起播；R202 索引采样消费方） ----------------

/**
 * TL-3 纯策略（JVM 可单测）：随机时刻采样窗序列与揭示文案。
 * 数据原语 = R202 [DeepSearchIndex.timeWindowItems]（idx_items_date 索引导航，
 * 有界 LIMIT，绝不全表扫）+ [DeepSearchIndex.dateBounds]（采样量程）。
 */
object TimeBlindboxPolicy {

    /** 首掷窗半径（秒）：±30 分钟——「该秒起播」的紧凑语义（全网发布密度下约十余条候选） */
    const val FIRST_WINDOW_HALF_SEC = 30L * 60

    /** 落空扩窗倍率（每重掷 ×8：30 分钟 → 4 小时 → 32 小时），与 archaeoRandomDig 重掷 ≤3 同族 */
    const val WIDEN_FACTOR = 8L

    /** 重掷上限（含首掷；用尽仍空 = 手气诚实兜底「这个年代太安静」） */
    const val MAX_ROLLS = 3

    /** 单窗采样上限（有界红线；随机挑一条，取多了也只用一条） */
    const val WINDOW_SAMPLE_LIMIT = 60

    /**
     * 一掷的窗序列（纯函数）：锚点 + 逐掷扩窗半径（[FIRST_WINDOW_HALF_SEC] × [WIDEN_FACTOR]^n）。
     * 窗序列固定可测——「首掷紧、落空放宽」的手感与 archaeoRandomDig 兜底同族。
     */
    fun windowHalfSecs(): List<Long> =
        (0 until MAX_ROLLS).map { FIRST_WINDOW_HALF_SEC * WIDEN_FACTOR.pow(it) }

    private fun Long.pow(n: Int): Long {
        var out = 1L
        repeat(n) { out *= this }
        return out
    }

    /** 随机锚点（量程 [minSec, maxSec] 均匀；量程非法返回 null） */
    fun randomAnchorSec(minSec: Long, maxSec: Long, random: Random = Random.Default): Long? {
        if (maxSec <= minSec) return null
        return minSec + (random.nextDouble() * (maxSec - minSec)).toLong()
    }

    /** 从候选里随机挑一条（「随机频道」由均匀抽样自然涌现；空表 null） */
    fun <T> pickRandom(items: List<T>, random: Random = Random.Default): T? =
        if (items.isEmpty()) null else items[random.nextInt(items.size)]

    /** 候选净化（跨频道同片去重保最早——与 R202 横截面同口径的盲盒轻量版） */
    fun dedupeItems(items: List<VideoItem>): List<VideoItem> {
        // fileUniqueId → 组内最早副本；无指纹条目各自成组（不参与去重）
        val groups = LinkedHashMap<String, VideoItem>() // 指纹 → 组内最早副本（槽序=首见序）
        val pass = ArrayList<VideoItem>() // 无指纹条目原样过
        for (it in items) {
            val fp = it.fileUniqueId.takeIf { f -> f.isNotBlank() }
            if (fp == null) {
                pass += it
                continue
            }
            val prev = groups[fp]
            if (prev == null) {
                groups[fp] = it
            } else if (it.dateEpochSec < prev.dateEpochSec) {
                groups[fp] = it
            }
        }
        // 组槽保持首见顺序，槽内取最早副本；无指纹条目追加在后（保持原序）
        val out = ArrayList<VideoItem>(groups.size + pass.size)
        out += groups.values
        out += pass
        return out
    }
}

/** 一掷结果：随机锚点时刻 + 抽中的条目（「该秒起播」的落点） */
internal data class TimeBlindboxRoll(
    val anchorSec: Long,
    val item: VideoItem,
    /** 该窗候选条数（揭示卡副文案：那个时刻全网还有 N 条在发） */
    val windowCount: Int,
)

/**
 * 掷一次时间盲盒（UI 协程直调；IO 切换内聚）：
 * bounds（R202 索引就绪才有）→ 随机锚点 → 逐掷扩窗 [timeWindowItems] 采样 →
 * 净化去重 → 随机挑一条。索引未建/未就绪/量程非法/掷尽仍空 → null（UI 分态呈现）。
 */
internal suspend fun FeedViewModel.rollTimeBlindboxImpl(): TimeBlindboxRoll? =
    withContext(Dispatchers.IO) {
        val bounds = runCatching { deepIndex.dateBounds() }.getOrNull() ?: return@withContext null
        val minSec = bounds.first
        val maxSec = bounds.second
        val anchor = TimeBlindboxPolicy.randomAnchorSec(minSec, maxSec) ?: return@withContext null
        for (half in TimeBlindboxPolicy.windowHalfSecs()) {
            val items = runCatching {
                deepIndex.timeWindowItems(
                    DeepSearchIndex.TimeWindowQuery(
                        fromSec = (anchor - half).coerceAtLeast(1L),
                        toSec = anchor + half,
                        channelIds = null,
                        limit = TimeBlindboxPolicy.WINDOW_SAMPLE_LIMIT,
                    ),
                )
            }.getOrDefault(emptyList())
            val pool = TimeBlindboxPolicy.dedupeItems(items)
            val picked = TimeBlindboxPolicy.pickRandom(pool)
            if (picked != null) {
                android.util.Log.i(
                    "FeedViewModel",
                    "时间盲盒：锚点=$anchor 窗半宽=$half 候选=${pool.size} 抽中key尾号=${picked.key.takeLast(8)}",
                )
                return@withContext TimeBlindboxRoll(anchor, picked, pool.size)
            }
        }
        null
    }
