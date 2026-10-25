package dev.tgfeed.core.feed

/**
 * R163 智能索引分级 · 时机窗口状态机 + 探索配额（B2）。
 *
 * 设计文档：index-tiering-design.md §7/§8.3。三件事的纯函数实现：
 *  1. **巡游爬取放行闸**：充电 ∧ 非计量网络 ∧ 真闲置才放行（拆 R158 实锤的
 *     「退后台 20s 起跑烧 CPU」）——用户正在等的爬取（搜索深挖 M9/关注补全 M1/
 *     手动建索引）**不走此闸**，由 B4 调度侧从优先队列直发；
 *  2. **饿死兜底**：窗口条件连续不满足时分级放宽（充电→结算宽限），防「默认
 *     开启后索引永不更新」的退化；计量网络是**永不放宽的硬线**；
 *  3. **探索配额（ε-greedy）**：巡游派工槽按 10 槽周期轮转，固定比例给探索车道
 *     （探针未探频道 + 丰度升档频道加深）——制度性保证未知好频道的曝光通道，
 *     不靠队列偶然排空。
 *
 * 零 Android 依赖；系统状态（前台/在播/计量/充电/结算时刻）由 B4 采集喂入。
 * 信号源既有事实：appInForeground（MainActivity onStart/onStop 置位）、
 * networkMetered（ConnectivityManager 系统回调 StateFlow）、batterySaver（60s 轮询）
 * 均现成；充电状态需 B4 新增 60s 轮询（BatteryManager，零权限，与 batterySaver
 * 同款节拍——不碰 MainActivity，见设计 §7.2）。
 *
 * 纪律①（行为变化默认保守）：[CrawlTimingConfig.smartTiming] 默认 **false**——
 * 现状等效（无窗口判定，退后台即爬，与今天行为完全一致）。默认值翻转等用户终审
 * 设计文档后由 B6 接线层决定；若届时默认生效，交付报告必须附「改了什么」一页说明。
 */
object IndexCrawlGate {

    // ---------------- 配置 ----------------

    /**
     * 时机配置（B6 接 AppPrefs）。默认值=现状等效/开关默认关（纪律①）。
     */
    data class CrawlTimingConfig(
        /** 智能时机总开关（默认关=现状：巡游爬取不受窗口约束，退后台即爬） */
        val smartTiming: Boolean = false,
        /** 闲置判定的结算宽限（毫秒）：最近一次观看结算后这么久内不算闲置——
         *  防刚退后台（结算尾巴还在飞/用户可能马上回来）就起跑 */
        val idleSettleGraceMs: Long = IDLE_SETTLE_GRACE_MS_DEFAULT,
        /** 探索车道占巡游派工槽比例（百分比，10 的倍数有效；0=探索关=现状等效） */
        val exploreQuotaPct: Int = EXPLORE_QUOTA_PCT_DEFAULT,
    )

    /**
     * 时机系统状态快照（B4 每次派工判定前采集）。
     * [lastWatchSettleMs]=0 表示本会话从未有观看结算（冷启动即闲置候选）。
     */
    data class TimingInputs(
        /** App 在前台（appInForeground）——前台一律挂起（§7.1，保守口径） */
        val foreground: Boolean,
        /** 有播放在跑（含后台音频在听/PiP 小窗）——用户在消费内容=挂起 */
        val playbackActive: Boolean,
        /** 默认网络计量（移动数据等按流量计费）——硬线，永不放宽 */
        val networkMetered: Boolean,
        /** 充电中（BatteryManager STATUS_CHARGING/FULL） */
        val charging: Boolean,
        /** 最近一次观看结算时刻（毫秒；0=本会话无） */
        val lastWatchSettleMs: Long = 0L,
        val nowMs: Long,
        /** 饿死兜底当前放宽级 0/1/2（AppPrefs 持久化跨重启，B4/B6 接线） */
        val starveLevel: Int = 0,
    )

    // ---------------- 判定原语 ----------------

    /**
     * 用户闲置（一级口径）：非前台 ∧ 无播放 ∧ 结算宽限已过。
     * 前台一律不算闲置（用户在浏览也是交互，且 TDLib 请求通道与首拉/换台抢——
     * R145 实证爬虫把 ensurePlayable 挤到 10~15s）。
     */
    fun isUserIdle(i: TimingInputs, c: CrawlTimingConfig): Boolean {
        if (i.foreground || i.playbackActive) return false
        if (i.lastWatchSettleMs <= 0L) return true
        return i.nowMs - i.lastWatchSettleMs >= c.idleSettleGraceMs
    }

    /**
     * 巡游爬取（无人等待的维护/探索/轮转）放行主闸。
     *
     * 真值表（smartTiming 开）：
     *  - 计量网络 → **false（硬线，任何放宽级都不放）**；
     *  - 兜底 0 级（默认）：充电 ∧ 闲置；
     *  - 兜底 1 级（24h 窗口累计<30min）：闲置（去充电）；
     *  - 兜底 2 级（再 24h 无窗口）：后台 ∧ 无播放（再松结算宽限）。
     * smartTiming 关 → 恒 true（现状等效）。
     */
    fun roamingCrawlAllowed(i: TimingInputs, c: CrawlTimingConfig): Boolean {
        if (!c.smartTiming) return true // 现状等效：退后台即爬，无窗口判定
        if (i.networkMetered) return false // 硬线：流量费用，永不放宽
        val backgroundQuiet = !i.foreground && !i.playbackActive
        return when (i.starveLevel.coerceIn(0, 2)) {
            0 -> i.charging && backgroundQuiet && settleGracePassed(i, c)
            1 -> backgroundQuiet && settleGracePassed(i, c)
            else -> backgroundQuiet
        }
    }

    /** 结算宽限是否已过（0=本会话无结算，直接过） */
    private fun settleGracePassed(i: TimingInputs, c: CrawlTimingConfig): Boolean =
        i.lastWatchSettleMs <= 0L || i.nowMs - i.lastWatchSettleMs >= c.idleSettleGraceMs

    /**
     * 轮转全量兜底车道闸（机制④，§8.3）：**仅一级窗口**（充电+非计量+闲置，不受
     * 饿死放宽——轮转是最奢侈的爬取，只配最好的窗口）且常规/探索队列皆空
     * （优先级最低）。分档/智能时机任一关闭 → 轮转不存在（现状等效）。
     */
    fun rotatorCrawlAllowed(
        i: TimingInputs,
        c: CrawlTimingConfig,
        tieringEnabled: Boolean,
        queuesIdle: Boolean,
    ): Boolean =
        tieringEnabled && c.smartTiming && !i.networkMetered && i.charging &&
            isUserIdle(i, c) && queuesIdle

    // ---------------- 饿死兜底推进（§7.4） ----------------

    /** 一级窗口累计低于该值即视为「窗口不足」（24h 口径，30 分钟） */
    const val STARVE_MIN_WINDOW_MS = 30L * 60_000L

    /** 兜底定级的评估粒度（24h；两级放宽各隔一个粒度） */
    const val STARVE_LEVEL_INTERVAL_MS = 24L * 3600_000L

    /**
     * 饿死兜底级重估（0/1/2 封顶）：
     *  - 一级窗口 24h 累计 ≥ [STARVE_MIN_WINDOW_MS] → **归 0**（窗口充足，回到最严格条件最省电）；
     *  - 0 级且距上次定级 ≥ [STARVE_LEVEL_INTERVAL_MS] 且窗口累计不足 → 1；
     *  - 1 级且距上次定级 ≥ [STARVE_LEVEL_INTERVAL_MS] 且窗口累计为 0（真的一窗没有）→ 2；
     *  - 2 级封顶。
     *
     * @param sinceLevelMs 距上次定级（或归零）的时长——B4 在真窗口放行后重置
     */
    fun nextStarveLevel(level: Int, windowAccumMs24h: Long, sinceLevelMs: Long): Int {
        if (windowAccumMs24h >= STARVE_MIN_WINDOW_MS) return 0 // 窗口充足：收回放宽
        val lvl = level.coerceIn(0, 2)
        return when (lvl) {
            0 -> if (sinceLevelMs >= STARVE_LEVEL_INTERVAL_MS) 1 else 0
            1 -> if (sinceLevelMs >= STARVE_LEVEL_INTERVAL_MS && windowAccumMs24h <= 0L) 2 else 1
            else -> 2
        }
    }

    // ---------------- R165 预算耗尽派工退避（热循环止血） ----------------

    /**
     * 派工轮询间歇（毫秒）：正常态。预算有余量时维持现状节奏（0.5s/拍）。
     * （原 FeedIndexPart 私有 INDEX_DISPATCH_TICK_MS 迁此，唯一出处）
     */
    const val DISPATCH_TICK_MS = 500L

    /**
     * 预算耗尽/健康喊停时的派工退避间歇（毫秒）：15s。
     *
     * R165 实锤（16384 夜间）：预算耗尽后 indexChannel 在采样闸秒退，worker 瞬间
     * 完工 → 槽位空 → 500ms tick 立即补派，枚举拍（5min）又反复回灌未完成频道
     * → 自维持热循环，实测 836 行日志/分钟（≈14 派工/秒）整夜不停：readMeta 查询
     * × CursorWindow × 白嫖 5 页 onlyLocal × W 级日志，GC 每轮 free 90 万对象，
     * native 水位一路推到 03:53 mmap OOM。退避把「耗尽期空转」压回 1/30。
     */
    const val DISPATCH_DRY_TICK_MS = 15_000L

    /**
     * 派工轮询间歇决策（纯函数，单测覆盖）：
     *  - 健康监视器喊停（内存压力 SEVERE/主线程楔死/onTrimMemory 暂停窗）→ 退避间歇；
     *  - 爬取泳道预算余量 ≤ 0（派了也是秒退空转）→ 退避间歇；
     *  - R208 风暴闸持有（开机静默期/系统高载）→ 退避间歇（派工时机闸同家族）；
     *  - 其余 → 正常态间歇。
     * [budgetRemaining] 传负数按 0 处理（防御）。
     * [stormHeld] 默认 false=既有调用点与单测零变化（R208 唯一传参方=巡游派工循环）。
     */
    fun dispatchTickDelayMs(
        budgetRemaining: Int,
        healthCrawlPaused: Boolean,
        stormHeld: Boolean = false,
    ): Long = when {
        healthCrawlPaused -> DISPATCH_DRY_TICK_MS
        budgetRemaining <= 0 -> DISPATCH_DRY_TICK_MS
        stormHeld -> DISPATCH_DRY_TICK_MS
        else -> DISPATCH_TICK_MS
    }

    // ---------------- R165 白嫖冷却（harvestLocalCache 防重扫） ----------------

    /**
     * 白嫖单频道冷却间隔（毫秒）：同一频道 1h 内不重复白嫖。
     * 16384 夜实锤：0 产频道每个派工周期都重跑 5 页 GetChatHistory(onlyLocal)
     * （日志「本地缓存白嫖 采到 0 条」整夜重复）——TDLib 响应对象与 SQLite 读
     * 是深索引夜间 churn 的常驻源。1h 覆盖足够（本地缓存增量来自用户浏览，
     * 用户浏览本身是低频事件）。
     */
    const val HARVEST_LOCAL_MIN_INTERVAL_MS = 60L * 60_000L

    /**
     * 白嫖冷却判定（纯函数，单测覆盖）：距上次尝试 ≥ 冷却间隔才放行。
     * lastAttemptMs=0（从未白嫖）在系统时钟口径下恒放行。
     */
    fun shouldHarvestLocal(lastAttemptMs: Long, nowMs: Long): Boolean =
        nowMs - lastAttemptMs >= HARVEST_LOCAL_MIN_INTERVAL_MS

    // ---------------- 探索配额（机制②，ε-greedy，§7.5） ----------------

    /** 配额有效上限（50%——探索超过一半就本末倒置了） */
    private const val EXPLORE_QUOTA_MAX_PCT = 50

    /**
     * 巡游派工槽是否归探索车道：10 槽一个周期，前 N 槽探索（N=quota/10，向下取整）。
     * quota 30 → 每 10 槽 3 个探索槽（ε-greedy 制度化）；quota 0 → 永假（现状等效，
     * 全部常规车道）。
     *
     * @param slotOrdinal 巡游派工序号（B4 维护的单调计数器，从 0 起；只用于周期相位）
     */
    fun isExploreSlot(slotOrdinal: Int, c: CrawlTimingConfig): Boolean {
        val perTen = c.exploreQuotaPct.coerceIn(0, EXPLORE_QUOTA_MAX_PCT) / 10
        if (perTen <= 0) return false
        val slot = if (slotOrdinal < 0) -slotOrdinal else slotOrdinal // 防御负序号
        return slot % 10 < perTen
    }

    // ---------------- 默认参数值（CrawlTimingConfig 默认字段唯一出处） ----------------

    /** 结算宽限默认：10 分钟 */
    const val IDLE_SETTLE_GRACE_MS_DEFAULT = 10L * 60_000L

    /** 探索配额默认：30%（每 10 槽 3 探索）——分档开启时才生效 */
    const val EXPLORE_QUOTA_PCT_DEFAULT = 30
}
