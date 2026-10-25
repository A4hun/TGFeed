package dev.tgfeed.core.onthisday

import android.content.Context
import android.util.Log
import dev.tgfeed.core.feed.DeepSearchIndex
import dev.tgfeed.core.feed.FloodBudget
import dev.tgfeed.core.feed.TimeWindowPolicy
import dev.tgfeed.core.store.SubscribedChatsReader
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * R203 那年今日·时间查询接缝（TL-2 页面与 RT-2 推送共用的数据通道）。
 *
 * **口径声明（R202 接口公告对齐）**：本模块的日期窗查询统一走 DeepSearchIndex
 * .timeWindowItems（R202 共享原语，KDoc 原话「R203/R204 的时间查询统一走这里，
 * 不要再开新的日期口径」）——不另写时间窗 SQL。窗口换算：本件 [EpochRange] 左闭
 * 右开 [from, toExcl)，timeWindowItems 闭区间 [from, to]，调用处传 toExcl-1 精确等价。
 *
 * **就绪闸（诚实三态）**：R202 时间索引 idx_items_date 在大库上由后台单飞构建
 * （分钟级），构建完成前 timeWindowItems 空手返回。本接缝据此区分：
 *  - [Reason.NO_DATABASE]：深索引库从未建立（演示模式/全新安装）→「还没有时间档案」；
 *  - [Reason.INDEX_PENDING]：库在但时间索引未建成（构建中）→「时间档案整理中」
 *    （空结果 + dateIndexReady 流仍假 = 未就绪；探到索引时流会被 timeWindowItems
 *    内部顺手点亮——openDb 未先跑的进程里查询自身就能点亮）；
 *  - 查询就绪但空 → 真无货（「那年今日暂无存货」——调用方按内容空态处理）。
 *
 * 绝不退化成无索引全表扫（R183 红线；timeWindowItems 内部已守，本层不绕）。
 */
interface OnThisDaySource {

    /** 年轮统计（页面 pills 徽标 + 推送「有内容的周年日」判定与择年原料） */
    suspend fun yearStats(
        today: LocalDate,
        zone: ZoneId,
        maxYears: Int = MAX_YEARS,
    ): OnThisDayOutcome<List<OnThisDayYearStat>>

    /**
     * 时间窗查询（订阅频道横截面）。
     * @param limit 采样上限（内部钳到 R202 的 TIME_WINDOW_MAX_LIMIT——有界红线）
     */
    suspend fun query(window: EpochRange, limit: Int): OnThisDayOutcome<List<VideoItem>>
}

/** 统一结果封套：Ready 载荷 / Unavailable 附原因（三态见 [Reason]） */
sealed interface OnThisDayOutcome<out T> {
    data class Ready<T>(val value: T) : OnThisDayOutcome<T>
    data class Unavailable(val reason: Reason) : OnThisDayOutcome<Nothing>
}

/** 不可用原因（空态文案的唯一判据——UI 不猜） */
enum class Reason { NO_SUBSCRIPTIONS, NO_DATABASE, INDEX_PENDING }

/**
 * 深索引实现（唯一实现；接缝留给 R202 API 形态变化时的一处换线点）。
 *
 * @param context 订阅表读取用（SubscribedChatsReader 只读）
 * @param indexProvider DeepSearchIndex 实例提供者。两族消费者各自注入：
 *  - 页面侧：共享 FeedViewModel 的 [DeepSearchIndex]（`deepIndex`，VM 私有单源）；
 *  - 推送侧：null = 卫星只读实例（见 [satellite]）。
 */
class DeepSearchOnThisDaySource(
    private val context: Context,
    private val indexProvider: (() -> DeepSearchIndex)? = null,
) : OnThisDaySource {

    override suspend fun yearStats(
        today: LocalDate,
        zone: ZoneId,
        maxYears: Int,
    ): OnThisDayOutcome<List<OnThisDayYearStat>> = withContext(Dispatchers.IO) {
        runCatching {
            val idx = index() ?: return@runCatching OnThisDayOutcome.Unavailable(Reason.NO_DATABASE)
            val chatIds = SubscribedChatsReader.load(context)
            if (chatIds.isEmpty()) return@runCatching OnThisDayOutcome.Unavailable(Reason.NO_SUBSCRIPTIONS)
            // 统计口径：每轮一次有界采样（上限=R202 硬顶），条数/频道数为采样内计数
            //（600 封顶——极端高产日计数保守少报，方向安全：宁少报不虚报）
            val chans = chatIds.toSet()
            val stats = (1..maxYears.coerceAtLeast(0)).map { y ->
                val w = onThisDayWindow(anniversaryDate(today, y), zone)
                val rows = idx.timeWindowItems(
                    DeepSearchIndex.TimeWindowQuery(
                        fromSec = w.fromSec,
                        toSec = w.toSecExcl - 1,
                        channelIds = chans,
                        limit = STATS_SAMPLE_LIMIT,
                    ),
                )
                OnThisDayYearStat(
                    yearsAgo = y,
                    itemCount = rows.size,
                    channelCount = rows.map { it.chatId }.distinct().size,
                )
            }
            stats.toOutcome(idx)
        }.getOrElse {
            Log.w(TAG, "那年今日年轮统计失败（按索引未就绪口径处理）: ${it.javaClass.simpleName}: ${it.message}")
            OnThisDayOutcome.Unavailable(Reason.INDEX_PENDING)
        }
    }

    override suspend fun query(window: EpochRange, limit: Int): OnThisDayOutcome<List<VideoItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val idx = index() ?: return@runCatching OnThisDayOutcome.Unavailable(Reason.NO_DATABASE)
                val chatIds = SubscribedChatsReader.load(context)
                if (chatIds.isEmpty()) return@runCatching OnThisDayOutcome.Unavailable(Reason.NO_SUBSCRIPTIONS)
                val rows = idx.timeWindowItems(
                    DeepSearchIndex.TimeWindowQuery(
                        fromSec = window.fromSec,
                        toSec = window.toSecExcl - 1,
                        channelIds = chatIds.toSet(),
                        limit = limit,
                    ),
                )
                rows.toOutcome(idx)
            }.getOrElse {
                Log.w(TAG, "那年今日窗查询失败（按索引未就绪口径处理）: ${it.javaClass.simpleName}: ${it.message}")
                OnThisDayOutcome.Unavailable(Reason.INDEX_PENDING)
            }
        }

    /** 空结果与「索引未就绪」的区分：就绪流仍假 = 构建中（timeWindowItems 探到即点亮，见类注释） */
    private fun <T> List<T>.toOutcome(idx: DeepSearchIndex): OnThisDayOutcome<List<T>> =
        if (isEmpty() && !idx.dateIndexReady.value) {
            OnThisDayOutcome.Unavailable(Reason.INDEX_PENDING)
        } else {
            OnThisDayOutcome.Ready(this)
        }

    /**
     * 卫星只读实例（无 VM 场景——RT-2 推送在 AppContainer 域跑，拿不到 FeedViewModel
     * 的 deepIndex）：只调 timeWindowItems → openGoldDb 只读连接，**永不 openDb**
     * （无迁移/建索引/爬取副作用，crawlBudget 传零预算哑元——只读路径永不消费）。
     * 懒建单实例（openGoldDb 连接随实例复用，不逐查询开新连接）。
     */
    private val satellite: DeepSearchIndex by lazy {
        DeepSearchIndex(
            File(context.filesDir, "deepindex"),
            FloodBudget(3_600_000L, 0),
        )
    }

    /** 索引实例：注入 provider 优先；无则卫星实例；库文件不存在直接 null（不开库不建库，hasIndex 同口径） */
    private fun index(): DeepSearchIndex? {
        val idx = indexProvider?.invoke() ?: satellite
        return if (idx.hasIndex()) idx else null
    }

    private companion object {
        private const val TAG = "OnThisDaySource"

        /**
         * 年轮统计采样上限：TimeWindowPolicy.QUERY_LIMIT_MAX 公共唯一出处
         * （R202 阶段 B 上移的共享常量——时间族硬顶单一出处，勿再开私有镜像）。
         */
        private const val STATS_SAMPLE_LIMIT = TimeWindowPolicy.QUERY_LIMIT_MAX
    }
}
