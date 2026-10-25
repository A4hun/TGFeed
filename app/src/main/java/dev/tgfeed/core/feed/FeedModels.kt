package dev.tgfeed.core.feed

import dev.tgfeed.core.store.PlaylistMeta
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.flow.StateFlow

/** 下载状态（UI 用于操作栏图标反馈） */
sealed interface DownloadStatus {
    /**
     * 下载中。progress ∈ [0,1]；-1 表示进度未知（Mock 网关或 GetFile 查询失败），
     * 约定 UI 见 -1 只显示转圈、不显示百分比。
     * 注意：由 data object 改为 data class 后，when 里的对象引用分支
     * （`DownloadStatus.Running ->`，见 ActionRail）必须改成 `is DownloadStatus.Running ->`；
     * 所有 `is Running` 判断不受影响。
     */
    data class Running(val progress: Float = 0f) : DownloadStatus
    data class Done(val path: String) : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
}

/**
 * Feed 内容筛选器（方向 / 时长 / 发布时间）。
 * 全 0 默认值 = 不筛选；各维度取交集（AND）。
 * 挂在 loadMore 的过滤链上（与屏蔽/不感兴趣同一处），对一切 FeedSource 生效。
 *
 * 方向约定：竖屏 = height > width（窄高），横屏 = width > height（宽扁），
 * 正宽高等边归横屏；宽高缺失（<=0）的条目在定向筛选时一律滤掉（无法判定）。
 */
/** 深度索引状态（设置页可见性）：已建频道数/总条目数/是否在建/总会话数/已查询/库大小/轮次 */
data class IndexStatus(
    val indexedChannels: Int,
    val totalItems: Int,
    val building: Boolean,
    val totalChats: Int,
    val loaded: Boolean = false, // false = 还没读过（显示"查询中"，不闪"没建"）
    val dbBytes: Long = 0L, // 索引库磁盘占用总字节（主文件+日志文件）；0 = 库还没建/未统计
    val round: Int = 0,     // 自动索引当前爬取轮次（autoBuildDeepIndex 常驻循环维护）；0 = 未开跑
    val sampledChannels: Int = 0, // R46：按月采样覆盖的频道数（口径诚实：区分"全量/采样"）
    // R266 v11 D6 诚实副标：总条目里含已收编转发 N 条（totalItems 本身=含转发口径；
    // 0=无转发/合并未跑——UI 不渲染副标）
    val repostItems: Int = 0,
)

data class FeedFilter(
    val orientation: Int = 0,      // 0 全部 / 1 竖屏 / 2 横屏
    val minDurationSec: Int = 0,   // 0=不限
    val maxDurationSec: Int = 0,   // 0=不限
    val dateFromEpochSec: Int = 0, // 0=不限
) {
    /** 单条是否通过筛选；各维度 AND。未知字段（时长/宽高/日期 <=0）在该维度启用时视为不通过 */
    fun matches(item: VideoItem): Boolean {
        // 方向：仅在有宽高时判定；未知尺寸在定向筛选下直接滤掉
        if (orientation != ORIENTATION_ALL) {
            if (item.width <= 0 || item.height <= 0) return false
            val portrait = item.height > item.width
            if (orientation == ORIENTATION_PORTRAIT && !portrait) return false
            if (orientation == ORIENTATION_LANDSCAPE && portrait) return false
        }
        // 时长：未知时长（<=0）在启用了时长区间时滤掉
        if (minDurationSec > 0 || maxDurationSec > 0) {
            if (item.durationSec <= 0) return false
            if (minDurationSec > 0 && item.durationSec < minDurationSec) return false
            if (maxDurationSec > 0 && item.durationSec > maxDurationSec) return false
        }
        // 发布时间：未知日期（<=0）在启用了起始时间时滤掉
        if (dateFromEpochSec > 0 && item.dateEpochSec < dateFromEpochSec) return false
        return true
    }

    companion object {
        const val ORIENTATION_ALL = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}

/**
 * P1-d 全站热榜条目：plazaTrending() 的 key 解析成内容卡后的展示模型。
 * 热度分 = 赞×3 + 在看×1（PlazaService.plazaTrending 口径）；封面用消息自带
 * minithumbnail（零额外下载），解析不到消息的条目封面为 null（UI 回落占位）。
 */
data class PlazaBoardEntry(
    val key: String,            // 视频 key（"tg:<chatId>:<messageId>"，与 Feed 侧天然对齐）
    val chatId: Long,
    val messageId: Long,
    val heat: Int,              // 热度分（L×3 + W×1）
    val watching: Int,          // 5 分钟窗口内「在看」人数
    val likes: Int,             // 全站点赞数
    val title: String,          // 标题（caption 首行；解析不到回落频道名/占位）
    val channelTitle: String,   // 频道名（可空：频道页也拉不到时为空串）
    val coverBytes: ByteArray?, // 封面 minithumbnail 字节；null = 无封面
)

// ---------------- 搜索联动·"资源群秒回"展示模型（FeedViewModel.liveAskSearch 配套） ----------------

/**
 * 秒回区单条链接答案（带来源实体名，UI"来源标"小字/角标用）。
 * eager 解析口径（用户拍板"直接展示成内容，不跳转"）：
 * [item] 非空 = 已解析成可播条目（UI 内联内容卡：封面+标题+时长，点击原地播放）；
 * null = 解析不出/没轮到 → UI 回落频道行（[title] 非空 = 频道标题，空 = @用户名直报），
 * [openChatId] 非 0 即可点打开该频道 Feed（与发现页"试吃"同链）。
 * [title] 图谱零网络标题快照（DiscoveryGraph.nodeTitle；chatId==0 的用户名链接恒空，
 * 由 resolveAnswerTitles 懒解析回填 answerLinkChatCache 后出真标题）。
 */
data class LiveLinkAnswer(
    val answer: GroupAnswer,
    val fromTitle: String,
    val item: dev.tgfeed.core.td.VideoItem?,
    val title: String,
    /** 可打开的频道 chatId（answer.chatId 或用户名链接解析回填值；0 = 还不可点） */
    val openChatId: Long = 0L,
)

/** 秒回区单条纯文本答案（bot/群的无链接回复，答案卡展示；[fromTitle] 来源实体名） */
data class LiveTextAnswer(val fromTitle: String, val text: String)

/**
 * 历史答案区（"群友搜到过"）单条行：原始答案 + eager 解析产物（与 LiveLinkAnswer 同口径）。
 * [item] 非空排前面（可播内容卡）；null 沉底频道行（[title] 空 = @用户名直报），
 * [openChatId] 非 0 即可点打开该频道 Feed。
 */
data class GroupAnswerRow(
    val answer: GroupAnswer,
    val item: dev.tgfeed.core.td.VideoItem?,
    val title: String,
    /** 可打开的频道 chatId（answer.chatId 或用户名链接解析回填值；0 = 还不可点） */
    val openChatId: Long = 0L,
)

/**
 * 搜索联动·"资源群秒回"区状态（FeedViewModel.liveAsk StateFlow 元素）：
 * 显式提交一次 = 一轮。[query] 是所属关键词——UI 只对"当前已提交词"展示
 * （与 groupAnswers 同口径，防打字换词时旧词秒回结果在新词里闪现）；
 * [asking] 仍有实体在监听窗口内（轻量等待态）；[entities] 本轮发词的实体数；
 * [links]/[texts] 实时流入（窗口期应答每有变化即刷新）。
 */
data class LiveAskState(
    val query: String,
    val asking: Boolean,
    val entities: Int,
    val links: List<LiveLinkAnswer>,
    val texts: List<LiveTextAnswer>,
    /**
     * R366 liveAsk 写闸（§十 缺口收口）：true = 账号写保护测试闸开，本轮发词被拦
     * （零发送零等待）——UI 如实提示「写保护中」，不落假「等待应答」长转。
     */
    val writeBlocked: Boolean = false,
)

/**
 * R68 频道卡元数据（源面板"直达频道"段的鲜活信号行 + "最近看过优先"排序依据）：
 * 索引口径（search.db 只读 GROUP BY 快照，与 R66 追更信号同源）+ 观看口径
 * （watch_events 现算每频道最近一次观看时刻），全部本地只读、零网络。
 */
data class ChannelCardMeta(
    val indexCount: Int = 0,    // 深度索引收录条数；0 = 索引未覆盖（UI 不显示信号行）
    val todayCount: Int = 0,    // 今日更新条数（条目发布时间 ≥ 本地今日零点）
    val latestSec: Long = 0L,   // 最新条目发布时间（秒）；0 = 索引未覆盖
    val lastWatchMs: Long = 0L, // 最近一次观看时刻（毫秒）；0 = 从没看过
)

/** 单个频道的观看统计（观看报告 Top 频道用）：本周观看秒数降序 */
data class ChannelWatchStat(
    val chatId: Long,
    val title: String,   // 频道名（多来源拼表，缺省"未知频道"）
    val watchSec: Long,  // 本周观看秒数（恒 > 0，纯负反馈频道不上榜）
)

/** 观看报告时间范围：今日（00:00 起）/ 本周（周一 00:00 起）/ 总计（事件表现存全部） */
enum class WatchRange { TODAY, WEEK, ALL }

/** 话题标签统计（观看报告"常看话题"）：tag 不含 # 前缀；watchSec 为该话题吃到的加权观看秒数 */
data class TopicStat(
    val tag: String,
    val watchSec: Long,
)

/** 近 7 天逐日观看量条目：dayStartMs 为本地日界毫秒，watchSec 为当日观看秒数（可为 0） */
data class DayWatch(
    val dayStartMs: Long,
    val watchSec: Long,
)

/**
 * 单个时间范围的观看聚合（[FeedViewModel.buildWatchReport] 按今日/本周/总计各算一份）。
 * 类型分布（videoSec/photoSec/audioSec）与话题均为近似推导，口径见 buildWatchReport 注释。
 */
data class WatchRangeStats(
    val totalSec: Long = 0L,                     // 范围内总观看秒数
    val eventCount: Int = 0,                     // 范围内事件条数（空态判断依据）
    val topChannels: List<ChannelWatchStat> = emptyList(), // Top 5 频道
    val videoSec: Long = 0L,                     // 类型分布：视频观看秒数
    val photoSec: Long = 0L,                     // 类型分布：图文观看秒数
    val audioSec: Long = 0L,                     // 类型分布：音频观看秒数
    val hourHistogram: List<Long> = List(24) { 0L }, // 24 小时直方图（index = 小时）
    val completedCount: Int = 0,                 // 完播条数（仅视频参与判定）
    val completableCount: Int = 0,               // 可判完播的视频事件数（完播率分母）
    val topTopics: List<TopicStat> = emptyList(),// 常看话题 Top 8（按加权观看秒数降序）
) {
    /** 该范围内没有任何观看事件 */
    val isEmpty: Boolean get() = eventCount == 0

    /** 最常观看时段（小时 0..23）；直方图全 0 时返回 -1（无有效时段） */
    val peakHour: Int get() =
        hourHistogram.indices.maxByOrNull { hourHistogram[it] }
            ?.takeIf { hourHistogram[it] > 0L } ?: -1

    /** 视频完播率（0..1）；无可判定事件时为 null（UI 不显示该指标） */
    val completionRate: Float? get() =
        if (completableCount > 0) completedCount.toFloat() / completableCount.toFloat() else null
}

/**
 * 观看报告聚合结果（[FeedViewModel.buildWatchReport] 产出，全默认值 = 空报告）。
 * 旧字段（weekTotalSec … weekEventCount）保持"本周"窗口口径不变；
 * 三档范围（今日/本周/总计）的完整统计走 [ranges]；趋势与连续天数为日粒度派生。
 */
data class WatchReport(
    val weekTotalSec: Long = 0L,                 // 本周总观看秒数
    val todayTotalSec: Long = 0L,                // 本日总观看秒数
    val topChannels: List<ChannelWatchStat> = emptyList(), // Top 5 频道（本周窗口）
    val videoSec: Long = 0L,                     // 类型分布：视频观看秒数（本周窗口）
    val photoSec: Long = 0L,                     // 类型分布：图文观看秒数（本周窗口）
    val audioSec: Long = 0L,                     // 类型分布：音频观看秒数（本周窗口）
    val hourHistogram: List<Long> = List(24) { 0L }, // 24 小时直方图（本周窗口，index = 小时）
    val weekEventCount: Int = 0,                 // 本周窗口内的事件条数
    val ranges: Map<WatchRange, WatchRangeStats> = emptyMap(), // 三档范围聚合（空报告为空表）
    val dailyTrend: List<DayWatch> = emptyList(),// 近 7 天逐日观看量（日界升序，末位=今日）
    val streakDays: Int = 0,                     // 连续观看天数（今日未看则从昨日回溯）
    val activeDays: Int = 0,                     // 累计观看天数（事件表窗口内，watchSec>0 归日）
    // —— 花样层（全局口径 = 事件表现存全部；人格规则表与阈值见 buildWatchReport 花样层注释）——
    val persona: WatchPersona? = null,           // 主人格（空报告为 null；非空报告至少给兜底人格"见习刷手"）
    val personaSubs: List<WatchPersona> = emptyList(), // 副标签（至多 2 个，显著度次于主人格）
    val heatmap: List<DayWatch> = emptyList(),   // 近 12 周逐日观看量（84 格，日界升序；未来日 watchSec=-1 占位）
    val records: List<WatchRecordItem> = emptyList(),  // "最"时刻（3~4 条，不够排面的不上榜）
    val badges: List<WatchBadge> = emptyList(),  // 成就徽章（200 枚 / 14 类；已按类目分组、组内达成的在前）
) {
    /** 是否为空报告（"总计"口径无任何观看事件；空报告兜底也回落到周窗口条数判断） */
    val isEmpty: Boolean get() = (ranges[WatchRange.ALL]?.eventCount ?: weekEventCount) == 0

    /** 最常观看时段（小时 0..23，本周窗口口径）；直方图全 0 时返回 -1（无有效时段） */
    val peakHour: Int get() =
        hourHistogram.indices.maxByOrNull { hourHistogram[it] }
            ?.takeIf { hourHistogram[it] > 0L } ?: -1
}

/**
 * 观看人格（观看报告"人格卡"与分享卡共用）：
 * emoji 随规则命中直接写入（UI 与分享卡不再维护 id→emoji 映射表）；
 * tagline 为一句调侃文案（中文互联网语境，在 buildWatchReport 花样层随规则命中写入）。
 */
data class WatchPersona(
    val id: String,      // 规则 id（80 条规则 + 兜底 rookie）
    val title: String,   // 人格名（2–4 字，如"夜猫子"）
    val emoji: String,   // 人格 emoji（如"🦉"）
    val tagline: String, // 一句调侃文案
)

/** "最"时刻条目（观看报告"最时刻卡"）：label 指标名，value 大数字，sub 日期/频道等副信息（可带梗味后缀） */
data class WatchRecordItem(
    val label: String,
    val value: String,
    val sub: String,
)

/** 成就徽章（观看报告"徽章墙"）：emoji 直接渲染（UI 不再维护 id→图标映射）；category 为分组小节名；condition 为达成条件文案（UI 长按弹窗展示） */
data class WatchBadge(
    val id: String,        // 徽章 id
    val name: String,      // 徽章名（如"七日连看"）
    val emoji: String,     // 徽章 emoji（如"📿"）
    val category: String,  // 分组名（里程碑/时长/天数/连续/频道/完播/速度/收藏/下载/搜索/时段/类型/共鸣/特殊）
    val condition: String, // 达成条件（如"连续 7 天都有观看"）
    val achieved: Boolean,
)

/**
 * 播放列表（UI 卡片模型，FeedViewModel 由 PlaylistMeta 投影产出）：
 * id/名称/条数/最新更新时间（updatedAt 毫秒，只在内容增删时推进）。
 */
data class Playlist(
    val id: String,
    val name: String,
    val count: Int,
    val updatedAt: Long,
)

// ---------------- 发现页升级·UI 投影模型（refreshDiscovery 发布段配套） ----------------

/** 发现页"刚冒出来的"横滑小卡模型：频道摘要 + 图谱首见/最近再被搜出时间戳（GoldmineHooks fresh 表投影） */
data class DiscoveryFreshCard(
    val brief: ChannelBrief,
    val firstTs: Long,
    val lastTs: Long,
)

/** 发现页顶部统计胶囊模型：今日收割词事件数 / 今日首见新频道数 / 广告黑名单累计拦截数（为 0 的段 UI 不渲染） */
data class DiscoveryHarvestStats(
    val harvestedWords: Int,
    val freshChannels: Int,
    val blockedAds: Int,
)

/** R62 发现页收录规模播报模型：今日新收录频道数（24h 首见）/ 图谱累计节点计数（有入边的目标节点总数） */
data class DiscoveryIntakeStats(
    val todayNew: Int,
    val totalNodes: Int,
)

/**
 * 发现页候选卡预览包（v3 真图化）：最近条目总数（卡片"最近 N 条"计数）+
 * 逐条真缩略图字节（TdLibGateway.loadCoverBytes 通道产物）。
 * thumbs 元素 null = 该条无封面文件或下载失败（UI 回落频道稳定色渐变色块，
 * 不放 minithumbnail 糊图）。
 */
data class DiscoveryPreview(
    val totalCount: Int,
    val thumbs: List<ByteArray?>,
)
