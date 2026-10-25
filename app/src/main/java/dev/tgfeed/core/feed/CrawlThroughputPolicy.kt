package dev.tgfeed.core.feed

/**
 * R255 深索引吞吐策略（纯函数，零 Android 依赖，单测锚定 [CrawlThroughputPolicyTest]）。
 *
 * 动机（基线实测 2026-10-19 23:49~23:53，16384/16416 双真库）：
 *  - **16384 索引速度 = 0 条/小时**（四采样零增长）：全部派工槽被「触顶僵尸」
 *    吃光——条数上限已到的频道每轮秒退（INTERRUPTED 被当失败计）→3 轮转旧路
 *    兜底烧预算零产出→枚举拍重新入队→无限空转；
 *  - **16416 ≈ 240 条/小时**：外加「档位参数泄漏」——meta.tier 历史残留行
 *    （R163 B7 注入 180×T1 + 168×T3、evict 复位 tier=3）在分档开关**关闭**时
 *    仍被 tierCap/headRefreshIntervalMs/deepenAllowed 照读：T3 条上限=0 秒退、
 *    T1=20000 提前封口、T3 头刷间隔=MAX_VALUE（违反 R163 B3「分档关=现状锚」
 *    设计意图——r163_notes「分档关时参数映射以配置为闸不读档位」的声明与实现不符）。
 *
 * 本文件是六处判定的**唯一实现处**（DeepSearchIndex 薄委托，防多份数字漂移）：
 *  1. [effectiveTier]：分档关 → 恒 LEGACY（不读 meta.tier）——档位闸（W2）；
 *  2. [itemCapFor]：探针档条上限下限=探针页预算×页大小（T3 cap=0 的探针死锁
 *     解锁——W3：探针在首个 `total>=itemCap`（0>=0）检查即死，从未取到过一页）；
 *  3. [shouldReviveExhausted]：触顶即终态（W1）的对称收口——升档且新档上限>
 *     现有条数时复活完成态回未到底队列（cap 会随档位变化，终态不能是单向门）；
 *  4. [rateIntervalMs]：平滑配速间隔（W5）=窗口时长/预算上限——突发耗尽→干涸
 *     →冻结死链的拆除；瞬时峰值反而低于旧突发形态（429 风险下降）；
 *  5. [isTtlUrgentLane]/[ttlUrgencyScore]：R284 时效维度——自毁群（TTL≤7 天）
 *     进时效优先车道显著提权，批内排序副键=TTL 双曲线+新鲜度加成；
 *  6. [ttlAwareHeadRefreshIntervalMs]：R284 时效覆盖闸——自毁群一轮覆盖周期
 *     ≤TTL/2（7 天→3.5 天，别真等到第七天）；
 *  7. [activityAwareHeadRefreshIntervalMs]：R286 活跃新鲜度闸——频道近期有新帖
 *     （服务器 lastMessage 距今 ≤1h 热 / ≤24h 活跃）时头刷间隔收敛
 *     （min 钳制，只缩不伸），治「头刷间隔长的频道新内容进索引慢」；
 *  8. [isFreshnessLagging]：R286 索引新鲜度滞后告警线——服务器有新帖且索引头
 *     落后超线（频道自身应有间隔与 1h 地板取大）即滞后。
 *
 * R284 时效与分档**正交**（独立加速维度，不改档位语义）：T3 待探索群若 7 天
 * 自毁也要先抢爬（调度次序提前），条数上限/探针深度仍归 IndexTierPolicy 裁决。
 * R286 活跃度同样正交：min 钳制与 tier/dead/ttl 三刀可任意叠加，只缩不伸。
 */
internal object CrawlThroughputPolicy {

    /**
     * 档位闸（W2）：爬取参数取档位的**有效值**。
     * 分档关（[tieringEnabled]=false）→ 恒 [IndexTierPolicy.IndexTier.LEGACY]
     * （=现状锚：条上限 50000/头刷 6h/加深开/采样闸不豁免）——**不读落库档位**。
     * 分档开 → 落库档位原样（B4 setTier 落档权威）。
     */
    fun effectiveTier(persistedTier: Int, tieringEnabled: Boolean): IndexTierPolicy.IndexTier =
        if (tieringEnabled) IndexTierPolicy.IndexTier.fromPersisted(persistedTier)
        else IndexTierPolicy.IndexTier.LEGACY

    /**
     * 条数上限（W3 探针解锁）：[baseCap]=档位原始上限；[probePages]=本轮探针页
     * 预算（[Int.MAX_VALUE]=非探针档）。探针档下限=探针页数×页大小——探针的
     * 语义就是「取 ~一页条目算丰度分升档」，cap=0 会让探针在首个 cap 检查即死
     * （R163 探针从未真正可用过的根因）。非探针档原样返回（现状锚）。
     */
    fun itemCapFor(baseCap: Int, probePages: Int, pageSize: Int): Int =
        if (probePages != Int.MAX_VALUE) maxOf(baseCap, probePages * pageSize) else baseCap

    /**
     * 升档复活（W1 配套）：触顶终态（[wasExhausted]）的频道在**新档条数上限 >
     * 现有条数**时清完成态回「未到底」队列（调度器自然续爬）。
     *  - 降档/新上限≤条数 → 不复活（cap 收紧由爬取期 cap 检查自然停，不删存量——
     *    R163b 修订令口径）；
     *  - 真到底（非触顶）频道误复活 → sweep 相位已全到底（mediaPass=3）→ 一轮
     *    零请求 DONE 即刻回终态（复活成本=一次派工，无网络请求）——诚实口径可接受。
     */
    fun shouldReviveExhausted(wasExhausted: Boolean, itemCount: Int, newTierCap: Int): Boolean =
        wasExhausted && itemCount < newTierCap

    /**
     * 平滑配速间隔（W5，毫秒）：`窗口时长 / 每小时预算上限`。
     *  - 6000 次/h → 600ms/请求：预算摊满整小时持续消费，吞吐=预算上限本身；
     *  - 旧形态 8 车道突发 ~16 页/s → 预算分钟级耗尽 → 干涸 55min → 队列空 FGS
     *    摘牌 → 进程冻结 → 滑窗恢复无人消费（R158 ③实测死链）——配速拆除该链；
     *  - [opsPerHour] ≤0（读不到/哑元实例）→ [Long.MAX_VALUE]=不配速（防御：
     *    配速永远不能比现状更慢地卡死通道）。
     */
    fun rateIntervalMs(windowMs: Long, opsPerHour: Int): Long =
        if (opsPerHour <= 0) Long.MAX_VALUE else windowMs / opsPerHour

    // ---------------- R284 时效感知（自毁 TTL 调度维度） ----------------

    /**
     * 时效优先车道门槛（秒）：TTL ∈ (0, [TTL_URGENT_LANE_MAX_SEC]] = 显著提权
     * （FeedIndexPart 时效优先车道——优先车道之后、巡游/探索之前派工）。
     * 取值=用户实锤口径：资源搜索机器人类的群消息 7 天自毁，不赶紧爬就永远消失。
     */
    const val TTL_URGENT_LANE_MAX_SEC = 7 * 86_400 // 604800 = 7 天

    /** 时效车道判定：ttlSec ∈ (0, [TTL_URGENT_LANE_MAX_SEC]]（0=不自毁，永不紧急） */
    fun isTtlUrgentLane(ttlSec: Int): Boolean = ttlSec in 1..TTL_URGENT_LANE_MAX_SEC

    /** 时效紧迫度上限（枚举批内排序副键分值；0=无时效压力） */
    const val TTL_URGENCY_SCORE_MAX = 9_999

    /** 紧迫度双曲线基底封顶（新鲜度加成 0..999 的余量载体；基底+加成≤[TTL_URGENCY_SCORE_MAX]） */
    private const val TTL_URGENCY_BASE_MAX = 9_000

    /**
     * 时效紧迫度（0..[TTL_URGENCY_SCORE_MAX]，越大越优先；枚举批内排序**副键**——
     * 主键仍是缺口档 0 未爬过/1 未到底/2 头刷到期，时效只在档内抢先后不越位）：
     *  - [ttlSec]<=0（不自毁）→ 0；
     *  - 基底双曲线：[TTL_URGENT_LANE_MAX_SEC]×1000/ttlSec —— 1 天=7000、
     *    7 天=1000、30 天≈233、90 天≈77（越短越紧迫；基底封顶 [TTL_URGENCY_BASE_MAX]）；
     *  - 新鲜度加成 0..999（[lastMessageEpochSec] 距今越近加成越大——活跃自毁群
     *    单位时间蒸发的内容更多：「TTL 越短+内容越新鲜=越优先」的新鲜度维；
     *    加成<双曲线档距，只在 TTL 相近时当决胜手，不喧宾夺主）；
     *  - 未知新鲜度（lastMessageEpochSec<=0）→ 零加成（保守，不凭空抬）。
     */
    fun ttlUrgencyScore(ttlSec: Int, lastMessageEpochSec: Long, nowEpochSec: Long): Int {
        if (ttlSec <= 0) return 0
        val base = (TTL_URGENT_LANE_MAX_SEC * 1_000L / ttlSec).toInt().coerceAtMost(TTL_URGENCY_BASE_MAX)
        val bonus = when {
            lastMessageEpochSec <= 0L -> 0
            nowEpochSec - lastMessageEpochSec <= 86_400L -> 999
            nowEpochSec - lastMessageEpochSec <= 7 * 86_400L -> 500
            nowEpochSec - lastMessageEpochSec <= 30 * 86_400L -> 200
            else -> 50
        }
        return (base + bonus).coerceAtMost(TTL_URGENCY_SCORE_MAX)
    }

    /** 自毁覆盖窗口除数：一轮覆盖周期目标 = TTL/N（7 天→3.5 天=「3~4 天一覆盖」用户口径） */
    const val TTL_COVERAGE_DIVISOR = 2

    /**
     * 时效覆盖闸（头刷间隔钳制，毫秒）：自毁群一轮覆盖周期 ≤ ttlSec×1000/[TTL_COVERAGE_DIVISOR]，
     * 与档位/死档基准间隔取 **min**——时效是独立于 tier 的加速维度（7 天自毁群基准 6h
     * 不变（6h≪3.5d 本就达标）；死档 7d 探活遇 7d 自毁→3.5d；1 天自毁钳制目标 12h——
     * 基准短于 12h（如 6h）照样维持不变，钳制只咬长基准如死档 7d→12h）。
     * [ttlSec]<=0 → [baseIntervalMs] 原样直通（现状锚：全库 ttlSec=0 时集合与
     * R284 前逐项相等）。Int→Long 先乘后除，Int.MAX_VALUE 秒也不溢出。
     */
    fun ttlAwareHeadRefreshIntervalMs(baseIntervalMs: Long, ttlSec: Int): Long =
        if (ttlSec <= 0) baseIntervalMs
        else minOf(baseIntervalMs, ttlSec * 1_000L / TTL_COVERAGE_DIVISOR)

    // ---------------- R286 活跃新鲜度（头刷提频 + 滞后告警线） ----------------

    /** 活跃热窗（秒）：服务器 lastMessage 距今 ≤1h = 热频道（头刷收敛到 [HEAD_REFRESH_HOT_MS]） */
    const val ACTIVITY_HOT_WINDOW_SEC = 3_600L

    /** 活跃窗（秒）：≤24h = 活跃频道（头刷收敛到 [HEAD_REFRESH_ACTIVE_MS]） */
    const val ACTIVITY_ACTIVE_WINDOW_SEC = 86_400L

    /** 热频道头刷间隔目标（毫秒）：30min——「刚发的」半小时内进索引的保新底线 */
    const val HEAD_REFRESH_HOT_MS = 30 * 60_000L

    /** 活跃频道头刷间隔目标（毫秒）：2h（现状 LEGACY 6h 的 1/3） */
    const val HEAD_REFRESH_ACTIVE_MS = 2 * 3_600_000L

    /**
     * 活跃新鲜度闸（头刷间隔钳制，毫秒）：频道近期有新帖（[lastMsgEpochSec]=服务器
     * lastMessage.date，枚举拍顺手落 meta.lastMsgSec，零新增请求）时收敛头刷间隔：
     *  - 热（≤[ACTIVITY_HOT_WINDOW_SEC]）→ min(base, [HEAD_REFRESH_HOT_MS])；
     *  - 活跃（≤[ACTIVITY_ACTIVE_WINDOW_SEC]）→ min(base, [HEAD_REFRESH_ACTIVE_MS])；
     *  - 更老/未知（lastMsgEpochSec<=0，存量库天然如此）→ [baseIntervalMs] 原样直通
     *    （现状锚：全库 lastMsgSec=0 时集合与 R286 前逐项相等）。
     * min 钳制只缩不伸：T0 基准 1h 遇热频道→30min；死档 7d 探活遇突活跃→收敛抢鲜
     * （死档复活本就靠 persistPage fresh>0 钩，头刷提前到=复活发现更快）。
     * 预算论证：热 30min/活跃 2h 各 ×页成本 3 ops，真实活跃分布下 ≤~1000 ops/h，
     * 对 6000/h 预算零压力；突发峰值仍由 W5 配速闸铺平（429 风险不升）。
     * 时钟漂移防御：lastMsgEpochSec 在未来（负差）落热档=按最新鲜处理，语义安全。
     */
    fun activityAwareHeadRefreshIntervalMs(baseIntervalMs: Long, lastMsgEpochSec: Long, nowEpochSec: Long): Long =
        when {
            lastMsgEpochSec <= 0L -> baseIntervalMs
            nowEpochSec - lastMsgEpochSec <= ACTIVITY_HOT_WINDOW_SEC ->
                minOf(baseIntervalMs, HEAD_REFRESH_HOT_MS)
            nowEpochSec - lastMsgEpochSec <= ACTIVITY_ACTIVE_WINDOW_SEC ->
                minOf(baseIntervalMs, HEAD_REFRESH_ACTIVE_MS)
            else -> baseIntervalMs
        }

    /** 滞后告警线地板（秒）：gap 判定下限，防活跃高频频道按自身 30min 间隔过严误报 */
    const val FRESHNESS_LAG_ALERT_FLOOR_SEC = 3_600L

    /**
     * 索引新鲜度滞后告警（R286 新鲜度度量的判定核）：
     *  - 索引新鲜度定义：频道索引头 [indexHeadEpochSec]=库内最新条目发布秒；
     *    滞后 gap = [lastMsgEpochSec]（服务器最新帖）− [indexHeadEpochSec]；
     *  - 告警线 = max([FRESHNESS_LAG_ALERT_FLOOR_SEC], [expectedIntervalSec])
     *    ——按频道自身应有头刷间隔宽限（冷频道周更帖子不误报；热频道 30min 间隔
     *    落后 1h+ 必报警），gap 超线即滞后；
     *  - 任一侧 ≤0（从未爬到条目/未知服务器新鲜度）→ false（未爬过属缺口档 0
     *    管辖的另一类问题，不在本告警域）。
     */
    fun isFreshnessLagging(indexHeadEpochSec: Long, lastMsgEpochSec: Long, expectedIntervalSec: Long): Boolean =
        indexHeadEpochSec > 0L && lastMsgEpochSec > 0L &&
            lastMsgEpochSec - indexHeadEpochSec > maxOf(FRESHNESS_LAG_ALERT_FLOOR_SEC, expectedIntervalSec)
}
