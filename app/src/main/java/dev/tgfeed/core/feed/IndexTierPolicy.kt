package dev.tgfeed.core.feed

/**
 * R163 智能索引分级 · 档位策略纯函数（B1）。
 *
 * 设计文档：index-tiering-design.md §3-§6。本文件是档位模型的**唯一权威实现**：
 * 需求分 D / 丰度分 R 的评分卡、f(D,R) 定档矩阵、滞回迁移、档位→爬取参数映射
 * 全部收敛在此，调用方（B4 调度侧）只聚合信号喂入，**不许在别处再写一份阈值**
 * （AGENT-DISCIPLINE：同样的数字与逻辑不许在多个文件各写一份）。
 *
 * 双正交信号（v2 核心，拆「探索性死亡循环」）：
 *  - D（需求确定性）：订阅/互动/搜索命中——用户**已经**要什么；
 *  - R（价值可能性）：探针样本的丰度分——这个频道**本身**值不值得深建。
 *    **R 升档不需要用户先关注**：资源很全但用户还没发现的频道，探针测出高分
 *    即自动升 T1 深索引，搜索覆盖不再由关注列表独裁。
 *
 * 零 Android 依赖、零 IO、零 R158 地盘依赖——现状等效锚用**字面量镜像**
 * （DeepSearchIndex 的 companion object 私有，public const 也引不到——同
 * FeedFollowPart.kt:81 引 DB_NAME 的既有先例）；镜像与上游的同步由
 * IndexTierPolicyTest 反射机检上锁（防第二份数字漂移）。
 *
 * 纪律①（行为变化默认保守）：[IndexTieringConfig.enabled] 默认 **false**——分档
 * 未启用时全库隐式走 [IndexTier.LEGACY]（现状等效：cap/头刷/采样闸/deepen 全等于
 * 既有常数，行为零变化）。默认值翻转等用户终审设计文档后由 B6 接线层决定。
 * 现状锚（R128 点赞回写先例：行为变化类默认关，知情后开）。
 *
 * R191 手动钉住（manual pin）：档位模型新增最高优先级规则——钉住频道恒 T0、
 * 自动评估不降档（用户意志高于 D/R 信号）；与总开关正交（钉住=显式用户动作，
 * 分档关也生效，见 [evaluateTier] 规则 0 与 [manualUnpinRestoreTier]）。钉住集合
 * 持久化与行内进度台账见 ManualDeepIndexPart。
 */
object IndexTierPolicy {

    // ---------------- 档位枚举 ----------------

    /**
     * 索引档位。persisted = meta.tier 落库 Int 序数（v6 迁移列）。
     * [LEGACY] 是兼容档：分档关闭时的现状等效行为（persisted=-1，存量库无列时的
     * 读取缺省同义）；它不是任何一个真档位，仅保证「开关关=行为不变」。
     */
    enum class IndexTier(val persisted: Int) {
        LEGACY(-1),
        /** T0 精索引=订阅频道：全量到底+采样闸豁免+头刷提频 */
        T0(0),
        /** T1 深索引：互动中档 ∨ 丰度升档（无需关注）——大上限+快车道 */
        T1(1),
        /** T2 探针浅索引：探针起步，轮转慢车道加深至上限 */
        T2(2),
        /** T3 按需：零接触零数据，等探索配额/首触 */
        T3(3);

        companion object {
            /** 落库值反解（未知值/存量缺省 → LEGACY，向后兼容） */
            fun fromPersisted(v: Int): IndexTier = entries.firstOrNull { it.persisted == v } ?: LEGACY
        }
    }

    // ---------------- 配置（B6 接 AppPrefs；默认值=现状等效/开关默认关） ----------------

    /**
     * 分档配置。全部带默认值：enabled=false → 一切现状等效（纪律①）。
     * T0 上限刻意**不设**字段——分档开启时也直接沿用 [DeepSearchIndex.TAIL_MAX_ITEMS]
     * 现状常数（T0 的语义就是"现状的全量"，引入可配反而制造第二份数字）。
     */
    data class TieringConfig(
        /** 分档总开关（默认关=现状等效：全库 LEGACY，爬取参数与今天完全一致） */
        val enabled: Boolean = false,
        /** 探针深度（条）——恰好一个清扫页（[DeepSearchIndex.PAGE_SIZE]=100）起步 */
        val probeItems: Int = PROBE_ITEMS_DEFAULT,
        /** T1 条数上限（LRU 淘汰线） */
        val capT1: Int = CAP_T1_DEFAULT,
        /** T2 条数上限（LRU 淘汰线） */
        val capT2: Int = CAP_T2_DEFAULT,
        /** T0 头部刷新节流（毫秒）——分档开启时的提频档；其余档沿用现状 6h 常数 */
        val headRefreshMsT0: Long = HEAD_REFRESH_T0_DEFAULT,
        /**
         * evict 磁盘水位线（字节，分档开时默认生效）：索引库所在分区**剩余可用空间**
         * 低于此值才触发收删（StatFs.availableBytes，零权限）。≤0 = 关闭水位线扳机
         * （只剩用户预算一道）。修订令（用户原话）：不搞全库行数硬顶——「用死数字
         * 剪裁活偏好」与按关注分档是同族病；体积是「用户关注了多少好频道」的
         * 涌现结果，只有真实约束（磁盘将满）才配当扳机。
         */
        val evictMinFreeBytes: Long = EVICT_MIN_FREE_BYTES_DEFAULT,
        /**
         * evict 用户存储预算（字节，AppPrefs 设置项）：**索引库体积**超过此值触发
         * 收删。0 = 关（默认关=只看磁盘水位线）。
         */
        val evictIndexBudgetBytes: Long = 0L,
    )

    // ---------------- 信号聚合输入（B4 从既有信号源聚合后喂入） ----------------

    /**
     * 需求信号 D（近 30 天窗，与 ChannelHealth 互动衰减口径同窗）。
     * 数据源：WatchEvent（store.loadWatchEvents）+ InteractionStats 五指标 +
     * 离散强证据（搜索命中/收藏/频道页访问）。
     */
    data class DemandSignals(
        val subscribed: Boolean,
        val watchEvents: Int = 0,
        /** 完播率 0..1（watchSec/durationSec ≥0.9 的事件占比；分母=有真实时长事件） */
        val completionRate: Double = 0.0,
        val avgWatchSec: Double = 0.0,
        /** 正互动：like + favorite + download */
        val positiveInteractions: Int = 0,
        /** 负反馈：dislike + skip */
        val negativeFeedback: Int = 0,
        /** 离散强证据（本地搜索命中/收藏稍后/频道页直出）——不等评分窗直接进 D 中档 */
        val strongEvidence: Boolean = false,
    )

    /**
     * 丰度信号 R（探针样本聚合，§3.3）。
     * 数据源：items 只读聚合（loadIndexChannelStats 同款 GROUP BY）+ 最近 ≤500 行
     * caption 逐条过 [parseEpisodeMark]（EpisodeChain 纯函数复用）+ InteractionStats
     * 的 deadmedia 事件率。
     */
    data class RichnessSignals(
        /** 是否已完成探针（有样本数据）；false = R 无意义（T3 候选） */
        val probed: Boolean = false,
        val sampleItems: Int = 0,
        /** 样本时间跨度（天）——密度分母 */
        val sampleSpanDays: Double = 0.0,
        /** 视频条目占比 0..1（媒体三相位内 video 份额；「媒体占比」的近似口径，见设计 §3.3 诚实边界） */
        val videoShare: Double = 0.0,
        /** EpisodeMark 解析命中率 0..1（剧集链纯函数现算） */
        val episodeHitRate: Double = 0.0,
        /** caption 非空率 0..1 */
        val captionNonEmptyRate: Double = 0.0,
        val avgCaptionLen: Double = 0.0,
        /** 近 7 天死链率 0..1（deadmedia/曝光；无曝光数据=0 不误伤） */
        val deadRate: Double = 0.0,
    )

    /** 档位决策（[evaluateTier] 产出）：新档 + 日志口径 + 是否触发快车道插队 */
    data class TierDecision(
        val tier: IndexTier,
        val reason: String,
        /** 升档（含 M1 关注补全/M4 丰度升档/M3 互动升档）→ 调度侧插队加深（B4 接线） */
        val promoteUrgent: Boolean = false,
    )

    // ---------------- 评分卡（阈值唯一权威处） ----------------

    /** D：观看事件数封顶（防单日刷屏频道独大） */
    private const val DEMAND_WATCH_EVENTS_CAP = 50

    /** D：平均停留秒封顶（与评分权重 0.05 配合 → 停留项上限 6 分） */
    private const val DEMAND_AVG_WATCH_SEC_CAP = 120.0

    /** D 升档线（≥ 即 D 中档；持续一个重评窗后升 T1——见 evaluateTier） */
    const val DEMAND_UP_SCORE = 20.0

    /** D 降档线（≤ 且连续两个复评窗低于，滞回防抖） */
    const val DEMAND_DOWN_SCORE = 3.0

    /** R：密度封顶（条/周，20/周即满密度分） */
    private const val R_DENSITY_CAP_PER_WEEK = 20.0

    /** R：caption 平均长度封顶（80 字即满信息量一半） */
    private const val R_CAPTION_LEN_CAP = 80

    /** R 升档线（探针完成即评，命中升 T1，无需关注——死亡循环击断点） */
    const val RICHNESS_UP_SCORE = 0.55

    /** R 降档线（连续两个复评窗低于） */
    const val RICHNESS_DOWN_SCORE = 0.25

    /** 降档滞回窗数：连续 N 个复评窗低于降档线才降（防样本波动抖动） */
    const val DEMOTION_STREAK_WINDOWS = 2

    /** 长期闲置降 T3 判定：最后一次接触距今超过该时长（M6 口径 30 天） */
    const val ON_DEMAND_IDLE_MS = 30L * 24 * 3600 * 1000L

    /** 需求分 D（S）。W×1 + C×10 + A×0.05 + I×3 − N×1.5（各项见设计 §3.4） */
    fun demandScore(d: DemandSignals): Double {
        val w = minOf(d.watchEvents, DEMAND_WATCH_EVENTS_CAP).toDouble()
        val a = minOf(d.avgWatchSec, DEMAND_AVG_WATCH_SEC_CAP)
        return w +
            d.completionRate * 10.0 +
            a * 0.05 +
            d.positiveInteractions * 3.0 -
            d.negativeFeedback * 1.5
    }

    /**
     * 丰度分 R（∈[-0.5, 1.0]）。未探针（无样本）返回 0.0——「无证据不臆测」，
     * 档位判定侧以 probed 标志区分「测过且低分」与「没测过」。
     * 0.30·密度 + 0.25·video 份额 + 0.25·剧集规整 + 0.20·信息量 − 0.50·死链
     */
    fun richnessScore(r: RichnessSignals): Double {
        if (!r.probed || r.sampleItems <= 0) return 0.0
        val perWeek = r.sampleItems / maxOf(r.sampleSpanDays, 1.0) * 7.0
        val density = minOf(perWeek, R_DENSITY_CAP_PER_WEEK) / R_DENSITY_CAP_PER_WEEK
        val info = r.captionNonEmptyRate * 0.5 +
            minOf(r.avgCaptionLen, R_CAPTION_LEN_CAP.toDouble()) / R_CAPTION_LEN_CAP * 0.5
        return 0.30 * density + 0.25 * r.videoShare + 0.25 * r.episodeHitRate +
            0.20 * info - 0.50 * minOf(r.deadRate, 1.0)
    }

    // ---------------- 定档矩阵 f(D, R) ----------------

    /**
     * 档位重估（迁移表 M1-M5/M7 的判定本体）。
     *
     * 规则（优先级自上而下）：
     *  0. **手动钉住（R191）→ 恒 T0**：用户意志高于一切 D/R 信号——滞回与降档线
     *     对钉住频道一律无效（[manualPinned] 传入 true 即命中）。本条置于分档总开关
     *     判定**之前**：钉住是显式用户动作（同 R128 点赞回写「知情后开」先例），
     *     分档关时也生效——参数面 T0 与 LEGACY 仅采样豁免（怪兽频道不按月采样、
     *     全量翻页）与头刷提频（分档开时 1h）两处有差，其余逐项相等；
     *  1. 分档关闭 → 恒 LEGACY（现状等效，行为零变化）；
     *  2. 订阅 → T0（M1 即时升，urgent）；
     *  2.5. **退订复位（M2，R263 补）**：规则 0/2 未命中而 current==T0 = 退订残留——
     *     T0 不是「维持现状」可锚住的稳态，按信号落档：升档线命中 → T1；零接触零
     *     数据 → T3；其余 → T2（存量不删，配额收窄自下轮爬取生效）；
     *  3. 未订阅：升档线**即时**（strongEvidence ∨ S≥[DEMAND_UP_SCORE] ∨ R≥[RICHNESS_UP_SCORE]）
     *     → T1（M3/M4，升档不需要关注）；
     *  4. 零数据零接触 → T3（M6 前置：未探针、无观看、无强证据）；
     *  5. 降档线**滞回**（S≤[DEMAND_DOWN_SCORE] ∧ R≤[RICHNESS_DOWN_SCORE] 连续
     *     [DEMOTION_STREAK_WINDOWS] 窗）→ T1 降 T2（M5/M7；T2 不再降——T3 只走长期闲置 [isOnDemandIdle]）；
     *  6. **T2 主入口（M4 探针完成未升档 / M6 首触 / LEGACY 初始分类，R263 断层修复）**：
     *     已探针或有接触、升档线全未命中 → T2 慢车道（T1 不在此直降——只走规则 5
     *     滞回，防样本波动抖动）。修订前本规则缺失：T2 仅滞回降档可达、LEGACY/T3
     *     中带被「维持现状」永久锚住（实库实锤 T2=0、LEGACY 淤积，见 r263_notes.md）；
     *  7. 其余保持 current（仅 T1 滞回中可达——分档开启后 LEGACY 被评估必消散到真档位）。
     *
     * @param downStreak 调用方持有的「连续低于降档线的复评窗计数」（升档/触发降档后归零）
     * @param manualPinned R191 手动钉住（左抽屉频道行「深度索引」按钮写入的钉住集合
     *   成员）：true = 恒 T0 不降档（见规则 0）；调用方聚合 D/R 的开销可省（本分支
     *   不读信号）
     */
    fun evaluateTier(
        config: TieringConfig,
        current: IndexTier,
        demand: DemandSignals,
        richness: RichnessSignals,
        downStreak: Int,
        manualPinned: Boolean = false,
    ): TierDecision {
        if (manualPinned) {
            return if (current == IndexTier.T0) {
                TierDecision(IndexTier.T0, "手动钉住（维持 T0，自动评估不降档）")
            } else {
                // 从任何低于 T0 的档（含 LEGACY 过渡态）钉入都算一次迁移事件——
                // urgent 走优先车道即刻深挖（与 M1 订阅补全同一车道）
                TierDecision(IndexTier.T0, "手动钉住（T0 等待遇：全量上限+头刷提频+采样豁免）", promoteUrgent = true)
            }
        }
        if (!config.enabled) return TierDecision(IndexTier.LEGACY, "分档未启用（现状等效）")
        if (demand.subscribed) {
            return if (current == IndexTier.T0) {
                TierDecision(IndexTier.T0, "订阅频道（维持 T0）")
            } else {
                TierDecision(IndexTier.T0, "新订阅（M1）：插队补全全量", promoteUrgent = true)
            }
        }
        val s = demandScore(demand)
        val r = richnessScore(richness)
        // M2 退订复位（R263 补）：规则 0（钉住）/规则 2（订阅）均未命中而 current 仍是
        // T0 = 退订残留——T0 不是「维持现状」可锚住的稳态（修订前退订频道恒留 T0 永不
        // 降档，与 T2 断层同属「过渡态/事件终态被维持规则锚住」一类病）。按信号落档，
        // 降级不插队（无人在等；存量不删，配额收窄自下轮爬取触顶生效）。
        if (current == IndexTier.T0) {
            return when {
                demand.strongEvidence || s >= DEMAND_UP_SCORE ||
                    (richness.probed && r >= RICHNESS_UP_SCORE) ->
                    TierDecision(IndexTier.T1, "退订复位（M2）：需求/丰度仍在线上（D=$s R=$r）→ T1 深索引（存量保留）")
                !richness.probed && demand.watchEvents == 0 ->
                    TierDecision(IndexTier.T3, "退订复位（M2）：零接触零数据 → T3 待探索")
                else ->
                    TierDecision(IndexTier.T2, "退订复位（M2）：未达升档线（D=$s R=$r）→ T2 慢车道（存量保留）")
            }
        }
        // 升档线：即时（含探针首评 M4——丰度升档不需要用户先关注）
        if (demand.strongEvidence) {
            return promoteIfNeeded(current, IndexTier.T1, "搜索/收藏/频道页强证据（M9/M6）：升 T1")
        }
        if (s >= DEMAND_UP_SCORE) {
            return promoteIfNeeded(current, IndexTier.T1, "互动分 $s ≥ $DEMAND_UP_SCORE（M3）：升 T1")
        }
        if (r >= RICHNESS_UP_SCORE && richness.probed) {
            return promoteIfNeeded(current, IndexTier.T1, "丰度分 $r ≥ $RICHNESS_UP_SCORE（M4 探针升档，无需关注）")
        }
        // 零数据零接触 → T3（M6 前置：未探针、无观看、无强证据；
        // LEGACY 同入——分档刚开启时的初始分类：从未触碰的频道直落 T3 等探索）
        if (!richness.probed && demand.watchEvents == 0 && !demand.strongEvidence &&
            (current == IndexTier.T3 || current == IndexTier.LEGACY)
        ) {
            return TierDecision(IndexTier.T3, "零接触零数据（待探索配额/首触）")
        }
        // 降档：滞回（连续 N 窗同时低于 D/R 降档线才降一级）
        val demoteLine = s <= DEMAND_DOWN_SCORE && richnessScore(richness) <= RICHNESS_DOWN_SCORE
        if (demoteLine && downStreak >= DEMOTION_STREAK_WINDOWS && current == IndexTier.T1) {
            return TierDecision(IndexTier.T2, "D=$s R=$r 连续 $downStreak 窗低于降档线（M5/M7）：T1→T2（存量保留）")
        }
        // M4 探针完成未升档 / M6 首触 / LEGACY 初始分类（R263 断层修复主规则）：
        // 已探针或有接触、升档线全未命中 → T2 探针浅索引（轮转慢车道加深至 capT2）。
        // 这是 T2 的**主入口**——修订前 T2 仅 M5/M7 滞回降档可达（实库 T1 中双弱候选
        // 为 0，该入口等同坏死），LEGACY/T3 中带被「维持现状」永久锚住 → 全库 T2=0、
        // LEGACY 淤积（251/629，r263 快照重放实锤）。T1 不在此直降：只走上方滞回。
        // 落 T2 不插队（urgent=false）——慢车道维护档，无人在等。
        if (current != IndexTier.T1) {
            return if (current == IndexTier.T2) {
                TierDecision(IndexTier.T2, "T2 慢车道维持（D=$s R=$r 未达升档线）")
            } else {
                TierDecision(IndexTier.T2, "探针/首触未达升档线（M4/M6 落档）：T2 慢车道（D=$s R=$r）")
            }
        }
        // 保持（仅 T1 滞回中可达——分档开启后 LEGACY 被评估必消散到真档位）
        return TierDecision(current, "维持现状（D=$s R=$r）")
    }

    /** 升档包装：档位升高才 urgent（已是目标档则原位维持）。
     *  序数方向注意：persisted 越小档越高（T0=0 最高、T3=3 最低）——升档=序数变小；
     *  LEGACY(-1) 是"分档刚开启的过渡态"，定向任何真档位都视为一次迁移事件。 */
    private fun promoteIfNeeded(current: IndexTier, target: IndexTier, reason: String): TierDecision {
        val promoted = current == IndexTier.LEGACY || current.persisted > target.persisted
        return if (promoted) {
            TierDecision(target, reason, promoteUrgent = true)
        } else {
            TierDecision(current, reason + "（已在该档，维持）")
        }
    }

    /** M6 长期闲置降 T3：零接触超 [ON_DEMAND_IDLE_MS]（调用方持 lastContactMs；0=从未接触也算闲置） */
    fun isOnDemandIdle(lastContactMs: Long, nowMs: Long): Boolean =
        nowMs - lastContactMs > ON_DEMAND_IDLE_MS || lastContactMs <= 0L

    // ---------------- 档位 → 爬取参数映射（§3.2 总表的唯一实现处） ----------------

    /** 条数上限（LRU 淘汰线）。LEGACY/T0=现状上限（[CURRENT_TAIL_MAX_ITEMS]）；T3 无数据无上限语义 */
    fun tierCap(tier: IndexTier, config: TieringConfig): Int = when (tier) {
        IndexTier.LEGACY, IndexTier.T0 -> CURRENT_TAIL_MAX_ITEMS
        IndexTier.T1 -> config.capT1
        IndexTier.T2 -> config.capT2
        IndexTier.T3 -> 0
    }

    /** 头部刷新节流（毫秒）。LEGACY/T1/T2=现状 6h；T0=提频档；T3=永不（不入队） */
    fun headRefreshIntervalMs(tier: IndexTier, config: TieringConfig): Long = when (tier) {
        IndexTier.LEGACY, IndexTier.T1, IndexTier.T2 -> CURRENT_HEAD_REFRESH_MS
        IndexTier.T0 -> config.headRefreshMsT0
        IndexTier.T3 -> Long.MAX_VALUE
    }

    /** 头部刷新是否入队（枚举拍预筛 + T3 长期闲置频道停维护刷新） */
    fun headRefreshEnqueued(tier: IndexTier): Boolean = tier != IndexTier.T3

    /** 怪兽采样闸豁免：仅 T0（关注频道就是要全量，10 万条也爬）；LEGACY 不豁免=现状 */
    fun samplingGateExempt(tier: IndexTier): Boolean = tier == IndexTier.T0

    /** 加深续爬（deepenLegacy）允许：LEGACY（现状照跑）/ T0；T1/T2 触顶即终态 */
    fun deepenAllowed(tier: IndexTier): Boolean =
        tier == IndexTier.LEGACY || tier == IndexTier.T0

    /** 探针模式：首爬只跑 N 个清扫页（1 页=[CURRENT_PAGE_SIZE] 条；探针后由档位决定后续深度） */
    fun probePageBudget(config: TieringConfig): Int =
        (config.probeItems + CURRENT_PAGE_SIZE - 1) / CURRENT_PAGE_SIZE

    /** 常规车道入队资格（枚举拍过滤口径：T3 不进常规队列，宇宙探索走探索车道——§8.2） */
    fun regularLaneEligible(tier: IndexTier, hasItems: Boolean): Boolean =
        tier != IndexTier.T3 || hasItems

    // ---------------- evict 扳机（修订令：真实约束替代全库行数硬顶） ----------------

    /**
     * 收删扳机（纯函数，DeepSearchIndex 喂入实测值）：
     *  - [freeBytes] = 索引库所在分区剩余可用空间（StatFs.availableBytes，字节；
     *    测不到传 [Long.MAX_VALUE]——没证据不触发，闸更保守）；
     *  - [dbBytes] = 索引库文件体积（File.length()，字节）；
     *  - 触发 = 水位线（freeBytes < [TieringConfig.evictMinFreeBytes]，仅当线 >0）
     *    ∨ 用户预算（dbBytes > [TieringConfig.evictIndexBudgetBytes]，仅当预算 >0）。
     * 收删顺序/保护（T3>T2 最冷先删、T0/T1/LEGACY 绝不淘汰）不变——扳机与
     * 执行正交，B7 已验证的原子性/保护性照旧。
     */
    fun evictNeeded(freeBytes: Long, dbBytes: Long, config: TieringConfig): Boolean =
        (config.evictMinFreeBytes > 0 && freeBytes < config.evictMinFreeBytes) ||
            (config.evictIndexBudgetBytes > 0 && dbBytes > config.evictIndexBudgetBytes)

    // ---------------- R191 手动钉住（取消钉的复位口径） ----------------

    /**
     * 取消钉住后的档位复位口径（纯函数，ManualDeepIndexPart 消费）：
     *  - 分档开 → **null = 走完整重估**（调用方先把档位复位 LEGACY 再评估——钉住期间
     *    的 T0 不能被「维持现状」规则锚住：用户意志撤回后，档位应回到 D/R 信号的
     *    本来面目，而不是永远赖在 T0）；
     *  - 分档关 → LEGACY（现状等效复位：钉住时写入的 T0 相对分档关现状只差采样
     *    豁免一道，复位即回「分档关=行为零变化」的完整现状）。
     */
    fun manualUnpinRestoreTier(tieringEnabled: Boolean): IndexTier? =
        if (tieringEnabled) null else IndexTier.LEGACY

    // ---------------- 默认参数值（TieringConfig 默认字段唯一出处） ----------------

    /** 探针深度默认：100 条（恰好 1 个清扫页，1 次 SearchChatMessages 请求） */
    const val PROBE_ITEMS_DEFAULT = 100

    /** T1 上限默认 */
    const val CAP_T1_DEFAULT = 20_000

    /** T2 上限默认 */
    const val CAP_T2_DEFAULT = 2_000

    /** T0 头刷提频默认：1h（关注频道追更红点灵敏度） */
    const val HEAD_REFRESH_T0_DEFAULT = 1L * 60 * 60_000L

    /**
     * 磁盘水位线默认：剩余可用 < 2GiB 触发收删（分档开时的默认扳机；用户可在
     * 设置里另设索引库存储预算，两道扳机任一命中即触发）。修订令：全库行数
     * 硬顶（205 万）废除——「不要给人家限制死」，体积=关注结构的涌现结果。
     */
    const val EVICT_MIN_FREE_BYTES_DEFAULT = 2L * 1024 * 1024 * 1024

    // ---------------- 现状锚（字面量镜像，反射机检锁同步） ----------------

    /** 现状锚·单频道条数上限（= DeepSearchIndex.TAIL_MAX_ITEMS 镜像；
     *  伴生对象私有引不到，同步由 IndexTierPolicyTest 反射机检上锁） */
    const val CURRENT_TAIL_MAX_ITEMS = 50_000

    /** 现状锚·头部刷新节流（= DeepSearchIndex.HEAD_REFRESH_MIN_INTERVAL_MS 镜像，6h） */
    const val CURRENT_HEAD_REFRESH_MS = 6L * 60 * 60_000L

    /** 现状锚·清扫页大小（= DeepSearchIndex.PAGE_SIZE 镜像，条/页） */
    const val CURRENT_PAGE_SIZE = 100
}
