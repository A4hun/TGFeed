package dev.tgfeed.core.feed

import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.VideoItem

/** 相册内的子目录名：Movies/TGFeed */
internal const val ALBUM_NAME = "TGFeed"

/**
 * 进程会话标记：类加载时生成一次（= 每进程一个）。
 * feed_resume.json 写入时带上它、读取只认本会话——杀进程重开后新会话标记
 * 必然对不上旧记录，记忆播放由此限定为"仅会话内"（跨进程"接着看"走播放历史）。
 * 同进程内 VM 重建（页面重建等）companion 不重新加载，标记不变，恢复仍有效。
 */
internal val RESUME_SESSION_ID: String = java.util.UUID.randomUUID().toString()

/** R52 首屏快照上屏后先发制人换发 fileId 的条数（覆盖视口 + 预载位即可，多烧请求无益） */
internal const val SNAPSHOT_REVIVE_AHEAD = 3

/** 单条观看时长上限（秒）：挂机封顶 60 分钟 */
internal const val MAX_WATCH_SEC = 60L * 60L

/** 真实观看时长心跳间隔（毫秒）：每跳确认 isPlaying 才累计（间隔×当前倍速）内容毫秒（P0-2/R110） */
internal const val WATCH_HEARTBEAT_MS = 2_000L

/** 续播进度最小记录阈值（毫秒）：不足 5 秒不记 */
internal const val PROGRESS_MIN_MS = 5_000L

/** 视为"看完"的进度比例（千分比）：posMs ≥ durationSec × 970 即清除续播点（= 时长的 97%） */
internal const val PROGRESS_DONE_PER_MILLE = 970L

/** 下载进度轮询间隔（毫秒） */
internal const val PROGRESS_POLL_MS = 1_000L

/** 预载深度决策的事件样本条数（该频道最近 N 条） */
internal const val PRELOAD_SAMPLE = 20

/** 预载深度决策的最小样本数：低于此数不做判断，用默认深度 */
internal const val PRELOAD_MIN_EVENTS = 5

/** 高跳过率阈值：平均观看比例低于此值 → 预载深度 1 */
internal const val SKIP_RATIO_THRESHOLD = 0.15

/** 完播判定：观看比例 ≥ 此值视为完播 */
internal const val COMPLETE_RATIO = 0.9

/** 高完播率阈值：完播率高于此值 → 预载深度 3 */
internal const val COMPLETE_RATE_THRESHOLD = 0.7

/** 预载深度档位：高跳过 / 默认 / 高完播 */
internal const val PRELOAD_AHEAD_MIN = 1
internal const val PRELOAD_AHEAD_DEFAULT = 2
internal const val PRELOAD_AHEAD_MAX = 3

/** 内容筛选"整页滤空自动续拉"的单次 loadMore 连拉页数上限（防极端条件下无限拉取） */
internal const val FILTER_MAX_PULLS = 3

/**
 * 预取阈值（onPage）：当前页之后剩余不足 N 条即触发 loadMore。旧值 4 太晚——
 * 一轮聚合拉取秒级到十几秒（40 频道窗口并发 SearchChatMessages + caption 邻居展开），
 * 快刷 4 条只要几秒，剩余太少必到底干等
 */
internal const val LOAD_MORE_REMAINING_TRIGGER = 8

/** 队列保底：任何时刻当前位置之后不足 N 条可播就续拉补足（轮内连拉 + 轮尾链式） */
internal const val FEED_QUEUE_MIN = 10

/** 队列保底链式续拉的连续空轮上限：连续 N 轮 0 入列即停（真没货，等用户刷新/新帖） */
internal const val AUTO_CHAIN_DRY_MAX = 2

/** 媒体 tab 抽空后复活探测轮的连拉窗口上限（探新帖足够，防全量重扫空转） */
internal const val REVIVE_PROBE_MAX_PULLS = 2

/**
 * R216 媒体 tab 干墙熔断：本轮入列为零且列表非空时的连拉页数上限。
 * 已看/判重墙下（页页回货全被过滤链吃光），旧行为一轮干爬满额 12 窗
 * （12×40 SearchChatMessages≈数百请求、数十秒~分钟级零产出）——收窄成半额，
 * 供给受阻更快进入轮尾判定（R216 触底自愈武装计数、链式续拉闸）。
 * 冷启动深挖（列表为空——稀疏类型要靠连拉找首批货）与有入列的轮
 * （既有队列保底 break 先行）不受影响。
 */
internal const val MEDIA_TAB_DRY_PULLS_CAP = 6

// R337：原 REWATCH_ARM_DRY_ROUNDS（R94 触底自愈武装阈值）随 rewatchRelaxSeen
// 兜底旗一并退役——供给饥饿不再武装重温（已看排除永不静默解除，见 SeenDedupPolicy
// 契约与 loadMore 尾段「R337 供给饥饿」观测行）。

/** 历史回溯注入节拍：loadMore 每处理 N 页从深度索引抽样注入一次老内容（可配） */
internal const val BACKFILL_EVERY_PAGES = 3

/** 历史回溯单次注入条数（与本页同口径过滤链取满为止，不足则少注） */
internal const val BACKFILL_PER_PAGE = 5

/** R92 保底选池内容分层快照的内存缓存 TTL（与频道清单 5 分钟缓存同节拍） */
internal const val INDEXED_MEDIA_TIERS_TTL_MS = 5 * 60 * 1000L

/**
 * R170 快车道种子路径对「tier 扫描进行中」的最长等待（毫秒）：首开 tap 撞上启动
 * 预热扫描尾巴时等它收尾而不是退回类型盲种子（退回=快车道空手回落整窗慢扫）。
 * 换整窗路径 7~22s，等 3s 稳赚；有陈值/扫描已结束时等待为 0。
 */
internal const val TIER_SEED_WAIT_MS = 3_000L

// ---------------- R170b 预热协调器（WarmupCoordinator 配套，关注流秒开体系） ----------------

/**
 * R170b 预热任务起跑宽限（毫秒）：主 Feed 首拉落定（ColdStartQuietGate）后追加的
 * 错峰窗口——R158 实锤爬虫风暴/派生扫描都排在首拉落定后的头几秒，预热再让这几秒。
 */
internal const val WARMUP_START_GRACE_MS = 5_000L

/** R170b 预热任务失败重试的线性退避基数（毫秒）：第 n 次重试前等 基数×n */
internal const val WARMUP_RETRY_BACKOFF_MS = 15_000L

/** R170b 关注流快照预热的优先级（用户可感知 > 纯后台保鲜，数字大者先跑） */
internal const val WARMUP_PRIORITY_FOLLOWING = 10

/** R170b tier 快照刷新的优先级（纯后台保鲜，让位关注流） */
internal const val WARMUP_PRIORITY_TIER = 5

/**
 * R170b 关注流快照预热的拉取页长：与真实首拉同口径（limit=100，perChannel 配额
 * 随订阅数自适应）——预热结果即真实浏览的首页形态，垫底零跳变。
 */
internal const val FOLLOWING_WARM_PAGE_LIMIT = 100

/** 历史回溯过采样倍数：索引含图文/音频，视频 tab 下会被过滤链吃掉一部分，过采样保抽满 */
internal const val BACKFILL_OVERSAMPLE = 4

/** R98 每页新面孔保底：每页硬性保底的新面孔条目数下限（典型 ~20 条页的 15%） */
internal const val NEW_FACE_PER_PAGE_MIN = 3

/** R98 每页新面孔保底：每页硬性保底的新面孔条目数上限（20 条页 4 条 = 20% 封顶） */
internal const val NEW_FACE_PER_PAGE_MAX = 4

/** R98 新面孔页内占比上限：配额按 ⌊页大小 × 本值⌋ 钳进 [MIN, MAX]——克制占比，主体仍是兴趣 */
internal const val NEW_FACE_MAX_RATIO = 0.20

/** R98 新面孔名册大小：按"最久未露面"升序取前 N 个有货频道作本批供给池（跨页轮换） */
internal const val NEW_FACE_ROSTER_SIZE = 24

/** R98 新面孔补位过采样倍数：索引含各类型内容，过滤链会吃掉一部分，过采样保补满 */
internal const val NEW_FACE_OVERSAMPLE = 6

/** R98 新面孔补位抽样轮数上限：首轮被过滤链吃到欠补时放大排除键再抽一轮 */
internal const val NEW_FACE_SAMPLE_ROUNDS = 2

/** R98 新面孔插入的页头保护条数：前 N 条留给兴趣主序最强内容，新面孔不抢头屏 */
internal const val NEW_FACE_HEAD_PROTECT = 3

/**
 * 互动加分定标（P1 #1，CES 口径，来源：ALGO-RESEARCH §4 小红书
 * CES=赞×1 藏×1 评×4 转×4 关注×8 + §1 TikTok 信号强度分级；
 * 任务口径：读完×1 / 点开·收藏×2 / 评论·转发·分享×4 / 不感兴趣×-4）：
 * 基准单位 0.15 沿用旧点赞值（历史落盘的 boost 表语义不突变），其余按倍数定标：
 *  ×1  点赞/读完        = 0.15
 *  ×2  点开/收藏        = 0.30
 *  ×4  评论/转发/分享   = 0.60（UI 暂无评论/分享入口，常量预留，接线即用）
 *  ×4  下载（深度保存） = 0.60（视同分享级强信号，替代旧拍脑袋值 0.5）
 *  ×-4 不感兴趣         = -0.60（负 delta 削既有光环，当日即生效，见 toggleNotInterested）
 */
internal const val BOOST_LIKE = 0.15
internal const val BOOST_FAVORITE = 0.3
internal const val BOOST_COMMENT = 0.6
internal const val BOOST_SHARE = 0.6
internal const val BOOST_DOWNLOAD = 0.6
internal const val BOOST_DISLIKE = -0.6

/** 单频道互动加分封顶：+1.0（基础权重最高 1.0，最终权重上限 2.0） */
internal const val INTERACTION_BOOST_CAP = 1.0

/**
 * 完播率分档边界与加分（P1 #2 观看质量信号，见 applyWatchQualityBoost）：
 * watchSec/durationSec <25% 负向 / 25~60% 中性偏正 / 60~95% 正 / ≥95% 强正；
 * 量级取 CES 基准的 1/5~1/3（观看高频、互动低频，共用封顶防饱和稀释）。
 */
internal const val QUALITY_TIER1_MAX = 0.25
internal const val QUALITY_TIER2_MAX = 0.60
internal const val QUALITY_TIER3_MAX = 0.95
internal const val WATCH_QUALITY_LT25 = -0.03
internal const val WATCH_QUALITY_25_60 = 0.01
internal const val WATCH_QUALITY_60_95 = 0.03
internal const val WATCH_QUALITY_GE95 = 0.05

/**
 * 会话内实时适应（P1 #4，内存态不落盘）：
 * 快划阈值 3s（任务口径，比权重侧 skip 的 2s 略宽——会话降权是更强后果，
 * 阈值放宽防误伤）；连续 2 次快划或 1 次不感兴趣 ×0.5，连续 2 次完播/互动 ×1.3；
 * 系数合成后的权重 clamp 到 [0.1, 2.0]（0.3 底 ×0.5=0.15，0.1 留给探索档 ×0.5 的极端）。
 */
internal const val SESSION_SKIP_SEC = 3
internal const val SESSION_STREAK_THRESHOLD = 2
internal const val SESSION_DOWN_FACTOR = 0.5
internal const val SESSION_UP_FACTOR = 1.3
internal const val SESSION_WEIGHT_FLOOR = 0.1
internal const val SESSION_WEIGHT_CAP = 2.0

/** 会话信号分类（noteSessionSignal 入参）：快划 / 不感兴趣 / 强正（完播·互动）/ 中性 */
internal const val SESSION_SIGNAL_SKIP = 0
internal const val SESSION_SIGNAL_DISLIKE = 1
internal const val SESSION_SIGNAL_STRONG_POS = 2
internal const val SESSION_SIGNAL_NEUTRAL = 3

/** 互动加分表持久化：独立 SharedPreferences 文件名与其中的 JSON map 键 */
internal const val BOOST_PREFS_NAME = "interaction_boost"
internal const val KEY_BOOST_MAP = "map"

/**
 * R75-D 互动加分时间衰减窗口（30 天，毫秒）：读取侧线性衰减，
 * 系数 = 1 − 距上次互动/窗口，超窗归 0——老互动不再永久抬权重
 */
internal const val BOOST_DECAY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000L

/** 图文/音频观看进画像的最小有效时长（毫秒）：低于此值视为噪声曝光不记 */
internal const val MIN_MEDIA_VIEW_MS = 1_000L

/** 省电模式轮询间隔（毫秒）：60s——省电切换是低频事件，轮询代替广播监听即可 */
internal const val BATTERY_SAVER_POLL_MS = 60_000L

/** 跨设备接力自动推送节流（毫秒）：saveProgress 落盘后 30s 内最多触发一次推送 */
internal const val AUTO_PUSH_THROTTLE_MS = 30_000L

/** 追更置顶：新鲜窗口（毫秒）——24 小时内发布才算"新内容" */
internal const val FRESH_WINDOW_MS = 24L * 3600 * 1000

/** 追更置顶：置顶条数上限 */
internal const val FRESH_PIN_LIMIT = 5

/** 追更置顶：每频道直拉的最新条数（只看最新一小页，纯读不推进追更进度） */
internal const val FRESH_SCAN_PAGE_SIZE = 10

/** 追更置顶：单次最多扫描的订阅频道数（订阅极多时收敛开销，顺序即订阅先后） */
internal const val FRESH_SCAN_MAX_CHANNELS = 30

/** 追更置顶：置顶条目的 caption 前缀（VideoItem 不加字段，用最省事的文案角标） */
internal const val FRESH_PIN_PREFIX = "🔔 "

// ---- R66 追更红点 + 低频宝藏频道上浮 ----

/** 追更查看账本文件名（filesDir 下，VM 自持小 JSON，tmp+rename 原子写；LocalStore 冻结故自立门户） */
internal const val FOLLOW_VISIT_FILE = "follow_visit.json"

/** 低频阈值（条/周）：发帖频率低于此值的频道视为"低频宝藏频道"（频率估计口径见 postsPerWeek） */
internal const val SCARCITY_LOW_POSTS_PER_WEEK = 2.0

/** 频率估计的时间跨度地板（7 天）：新收录频道样本少，防跨度趋零把频率吹成无穷大 */
internal const val SCARCITY_MIN_SPAN_MS = 7L * 24 * 3600 * 1000

/** 稀缺上浮的新鲜窗口（48h）：低频频道只有"较新条目"才上浮，陈年旧帖不加权 */
internal const val SCARCITY_FRESH_WINDOW_MS = 48L * 3600 * 1000

/** 稀缺上浮乘性系数（VM 页内排序路径）：×2.5 再封顶 SESSION_WEIGHT_CAP——低频频道
 *  绝对量小，明显上浮几乎不占高频频道曝光位（论证见 applyScarcityBoost 注释） */
internal const val SCARCITY_BOOST_FACTOR = 2.5

/** 稀缺上浮加性折算（源内排序路径）：EXPLORE_WEIGHT(0.6)+0.9=1.5 ≈ 0.6×2.5，两路径口径对齐 */
internal const val SCARCITY_SOURCE_BONUS = 0.9

/** 永久屏蔽阈值：同一内容指纹"不感兴趣"累计达到此次数 → 进 blocked_items.json 永久屏蔽 */
internal const val PERMANENT_BLOCK_THRESHOLD = 2

// ---- P1.5 行为信号采集（重看/拖拽/开声音/全屏/序列动量/会话疲劳） ----

/** #1 重看加分的回看次数阈值：同一条目本会话回看达到此次数触发一次频道加分 */
internal const val REPLAY_STRONG_THRESHOLD = 2

/** #1 重看加分值：比点赞（BOOST_LIKE=0.15）高一点——翻回来再看是比点一下更强的意愿信号 */
internal const val BOOST_REPLAY = 0.2

/** #3 主动开声音（取消静音）加分：与点赞同级（任务口径"不低于点赞"） */
internal const val BOOST_UNMUTE = 0.15

/** #3 主动全屏加分：与点赞同级（"不低于点赞"）；旋转自动全屏不计（非主动） */
internal const val BOOST_FULLSCREEN = 0.15

/** #5 会话疲劳阈值：本次会话真实观看（心跳口径）超过 45 分钟后探索配额翻倍 */
internal const val SESSION_FATIGUE_MS = 45L * 60 * 1000

/** #5 疲劳补位节拍：与现有探索机制（每 10 位 1 格 ≈10%）错开，再补 1 格 → 合计 ≈20% */
internal const val EXPLORE_EXTRA_EVERY = 10

/** #5 疲劳补位起始偏移：原生探索格落在位 9,19,29…，补位插在位 4,14,24…（相间分布） */
internal const val EXPLORE_EXTRA_OFFSET = 4

/** #5 疲劳补位最小页长：太短的页补位无意义 */
internal const val EXPLORE_EXTRA_MIN_PAGE = 10

/** #5 补位窗口约束镜像（FeedSource 私有 WINDOW=5/WINDOW_MAX=2 的同值镜像——源冻结不可直接引用） */
internal const val EXPLORE_EXTRA_WINDOW = 5
internal const val EXPLORE_EXTRA_WINDOW_MAX = 2

/** #4 会话动量词表逐事件衰减系数：最近的观看主题占主导，换主题自然消退 */
internal const val SESSION_TOPIC_DECAY = 0.85

/** #4 动量词低权剪枝线：衰减低于此值即从词表移除 */
internal const val SESSION_TOPIC_MIN_WEIGHT = 0.1

/** #4 动量词激活阈值：权重达到此值才算"当前热词"参与页内加权 */
internal const val SESSION_TOPIC_ACTIVE_WEIGHT = 0.5

/** #4 参与页内打分的头部热词数 / 词表容量上限 */
internal const val SESSION_TOPIC_TOP_WORDS = 30
internal const val SESSION_TOPIC_MAX_WORDS = 60

/** #4 同主题条目有界前移轮数（每轮至多越过 1 个非同主题条目，即最多前移 2 位） */
internal const val SESSION_TOPIC_PROMOTE_MAX = 2

// ---- P2 新生代算法第一批（DIN 目标注意力 + 双轨画像 + 马尔可夫转移，见 applyP2SequenceRank） ----

/** P2 页内重排：候选 P2 分（DIN 相关度 + 马尔可夫加分）达到此阈值才算"活跃"参与前移 */
internal const val P2_ACTIVE_SCORE = 0.8

/** P2 页内重排：活跃条目越过前驱的最小分差（防同分区域内的无意义抖动换位） */
internal const val P2_SWAP_MARGIN = 0.3

/** P2 页内重排：有界前移轮数（每轮至多越过一个前驱，即最多前移 3 位） */
internal const val P2_PROMOTE_MAX = 3

/** P2 DIN 词画像的会话轨增益：会话热词归一化后 ×0.5 融入长期词画像（"现在想看"稍响于"一直爱看"） */
internal const val DIN_SESSION_WORD_GAIN = 0.5

/** P2 马尔可夫转移分的加权系数：transitionScore ∈ [0,1] 映射到与 DIN 分同量级的加分（强转移 ≈ +0.6） */
internal const val MARKOV_BONUS_WEIGHT = 2.0

// ---- R79 隐式反馈链（秒划强化 + 观看节奏画像，实现见 ImplicitFeedback/InterestProfile） ----

/**
 * 节奏分型 → 秒划惩罚系数：浅尝辄止型 0.5（对什么都划得快，秒划噪声大减半）、
 * 深度观看型 1.0（秒划是真负反馈满格）、其余 0.8（样本不足/均衡/连刷的保守默认）
 */
internal const val RHYTHM_SKIP_SCALE_SKIM = 0.5
internal const val RHYTHM_SKIP_SCALE_DEEP = 1.0
internal const val RHYTHM_SKIP_SCALE_DEFAULT = 0.8

/** 连刷型节奏加成：当前连刷频道的小幅加分（加性注入，硬多样性约束不动） */
internal const val BINGE_CHANNEL_BOOST = 0.10

/** 订阅加成：订阅=最强主动兴趣声明，主 Feed 给固定不衰减加分（加性注入，硬约束不动） */
internal const val SUBSCRIPTION_BOOST = 0.35

/** 订阅频道权重乘性加成比例（视频 tab 排序用，跟现有权重缩放） */
internal const val SUBSCRIPTION_WEIGHT_RATIO = 0.5

// ---- R298 真推荐引擎（design-recommendation.md：四路召回的预算/配比唯一出处） ----

/** R2 tag 兴趣路：参与逐页轮转的头部兴趣 tag 数（账本 topInterestTags 取前 N） */
internal const val INTEREST_TOP_TAGS = 16

/** R2 每页消费的 tag 数（轮转步长，防每页都是同一批 tag） */
internal const val INTEREST_TAGS_PER_PAGE = 4

/** R2 路每页目标注入占拉页条数比例（设计预算表：R2≈30%） */
internal const val INTEREST_RECALL_RATIO = 0.30

/** R2 路单页注入钳制（条）：小页至少 2 条有存在感，大页封顶防淹没现状路 */
internal const val INTEREST_RECALL_MIN = 2
internal const val INTEREST_RECALL_MAX = 40

/** R2 单 tag 过采样倍数（已看/判重/筛选会吃掉一部分，过采样保预算取满） */
internal const val INTEREST_RECALL_OVERSAMPLE = 4

/** R3 图谱路：C 轴头部频道数（图谱邻居查询的引用方集） */
internal const val GRAPH_SOURCE_TOP_CHANNELS = 8

/** R3 图谱路：邻居频道候选数（link/forward_src/co_watch 三路并集取前 N） */
internal const val GRAPH_NEIGHBOR_LIMIT = 12

/** R3 路每页目标注入占拉页条数比例（设计预算表：R3≈10%） */
internal const val GRAPH_RECALL_RATIO = 0.10

/** R3 路单页注入钳制（条） */
internal const val GRAPH_RECALL_MIN = 1
internal const val GRAPH_RECALL_MAX = 12

/** R3 每个邻居频道直出的近端条数（channelItemsPage 最新一页截取） */
internal const val GRAPH_ITEMS_PER_NEIGHBOR = 3

/** R3 金矿验货闸：邻居频道近 7 天新帖数下限（tier≥0 系统验货徽章可豁免本闸） */
internal const val GRAPH_GOLD_MIN_RECENT7 = 1
