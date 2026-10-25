package dev.tgfeed.core.tastemap

/**
 * R235 成就体系纯函数（口味星图四期·RPG 层·零 Android 依赖单测可跑）。
 *
 * **飞船跟成就体系和勋章体系挂钩——成就勋章越高飞船越酷**（用户愿景原话）：
 * 等阶判据全部来自**本地统计**（深索引频道数/观看账/收藏/探索 tag/分享计数/
 * 星图天体分档——零 Search 族，R184 红线），勋章=单项进度，等阶=该阶全部勋章
 * 达成才晋升（[evaluate] 纯函数：同输入同输出，重算即迁移——与天体分档器
 * 「星系活着」同一哲学）。
 *
 * 等阶表（任务书原文口径）：
 *  - 青铜探索者：默认（发现 0 频道也持有——基础飞船：白色微尾）；
 *  - 白银：发现 50 频道 ∧ 观看 10 小时（引擎光效+航迹微光）；
 *  - 黄金：发现 100 频道 ∧ 观看 50 小时 ∧ 收藏 30 条（曲速引擎+拖尾粒子）；
 *  - 钻石：发现 200 频道 ∧ 探索 100 个 tag ∧ 分享 10 次（跃迁特效+护盾光环）；
 *  - 传奇：发现 300+ 频道 ∧ 观看 200 小时 ∧ 全六态见过（传说级飞船+专属粒子风暴）。
 *
 * 「全六态」口径（诚实标注）：设计 §1.2 六行表里「恒星（缺省）」与恒星同 enum
 * 值（降级兼容行），六类取**五态恒星+脉冲星**（[SIX_KINDS]）——KDoc 成文，
 * 绝不冒充第六种不存在的 enum。
 *
 * 「发现 N 频道」口径=星图在图**频道恒星数**（六态恒星，不含脉冲星/星团/流星
 * 实体——「你的宇宙里有多少颗恒星」的诚实读法）；「分享次数」=TasteVoyageLog
 * 本地计数（feed 分享枢纽两动作+海报导出 funnel 累计）。
 *
 * **R248 探索里程碑**（点亮机制联动）：[discoveredStars]（点亮恒星=飞近点亮∪
 * 已知频道的并集数）驱动 4 枚里程碑勋章（10/50/100/300 颗）——**只加不改**：
 * 不参与等阶晋升（五阶判据原样），面板「探索里程碑」独立组渲染。
 */
object TasteAchievements {

    // ==================== 等阶 ====================

    /** 成就等阶（飞船酷度的单一驱动源——[ShipStyle] 随它升档） */
    enum class Rank {
        /** 青铜探索者：默认——基础飞船（白色微尾） */
        BRONZE,
        /** 白银：引擎光效+航迹微光（蓝光尾+引擎脉冲） */
        SILVER,
        /** 黄金：曲速引擎+拖尾粒子（金拖尾粒子） */
        GOLD,
        /** 钻石：跃迁特效+护盾光环（跃迁波纹） */
        DIAMOND,
        /** 传奇：传说级飞船+专属粒子风暴（星尘色粒子风暴——R348 C1 彩虹退役） */
        LEGEND,
    }

    /** 「全六态见过」的目标天体集：五态恒星+脉冲星（口径见对象 KDoc） */
    val SIX_KINDS: Set<CelestialKind> = setOf(
        CelestialKind.STAR,
        CelestialKind.PROTOSTAR,
        CelestialKind.RED_GIANT,
        CelestialKind.WHITE_DWARF,
        CelestialKind.SUPERNOVA,
        CelestialKind.PULSAR,
    )

    // ---- R331 终态奇观册（天体图鉴另册——勋章外奇观口径） ----

    /**
     * **终态奇观四席**（R331 天体图鉴奇观册）：黑洞/中子星/夸克星/白洞——全部
     * **宣判制天体**（判据族 [TasteAstroPhysics.judgeTerminalStates]/
     * [TasteAstroPhysics.judgeWhiteHoles] 产出，非六态矩阵直判），按播报稀有度序
     * 排列（夸克>黑洞>中子星>白洞——[AstroFacts] rarityRankOf 头部四档同序）。
     *
     * **勋章外奇观口径（R315-L③）**：终态奇观不入等阶判据（黑洞不入等阶结构
     * 先例延续——[evaluate] 零改：见齐天体形态勋章仍只数 [SIX_KINDS]）；图鉴
     * 「还没见过」清单与目击计数走奇观册另收（消费单口见
     * [AstroFacts.unseenWonderHints]/[AstroFacts.wonderCountsLine]——黑洞与三新
     * kind 同册=同类同修，不修三漏一，纪律§一）。
     */
    val WONDER_KINDS: List<CelestialKind> = listOf(
        CelestialKind.QUARK_STAR,
        CelestialKind.BLACK_HOLE,
        CelestialKind.NEUTRON_STAR,
        CelestialKind.WHITE_HOLE,
    )

    // ---- 阈值常量（任务书原文数字，单一出处） ----

    /** 白银阶：发现频道数 */
    const val SILVER_CHANNELS = 50

    /** 白银阶：观看小时数 */
    const val SILVER_WATCH_HOURS = 10L

    /** 黄金阶：发现频道数 */
    const val GOLD_CHANNELS = 100

    /** 黄金阶：观看小时数 */
    const val GOLD_WATCH_HOURS = 50L

    /** 黄金阶：收藏条数 */
    const val GOLD_FAVS = 30

    /** 钻石阶：发现频道数 */
    const val DIAMOND_CHANNELS = 200

    /** 钻石阶：探索 tag 数 */
    const val DIAMOND_TAGS = 100

    /** 钻石阶：分享次数 */
    const val DIAMOND_SHARES = 10

    /** 传奇阶：发现频道数（「300+」） */
    const val LEGEND_CHANNELS = 300

    /** 传奇阶：观看小时数 */
    const val LEGEND_WATCH_HOURS = 200L

    // ---- R248 探索里程碑（点亮口径——用户原创「名字可以搞成探索形式的点亮机制」的
    //      成就联动；**不参与等阶晋升**（R235 五阶判据结构红线——只加不改），面板
    //      「探索里程碑」独立组渲染） ----

    /** 点亮恒星里程碑：初级（颗） */
    const val EXPLORE_MILESTONE_10 = 10

    /** 点亮恒星里程碑：中级（颗） */
    const val EXPLORE_MILESTONE_50 = 50

    /** 点亮恒星里程碑：高级（颗） */
    const val EXPLORE_MILESTONE_100 = 100

    /** 点亮恒星里程碑：顶级（颗） */
    const val EXPLORE_MILESTONE_300 = 300

    // ==================== 统计输入 ====================

    /**
     * 成就统计原料（装配口收集，全部本地只读）：
     *  - [discoveredChannels]：星图在图频道恒星数（六态恒星数）；
     *  - [watchSec]：watch_events 全量累计秒（与星图 totalWatchSec 同源）；
     *  - [favCount]：内存收藏表条数；
     *  - [exploredTags]：你消费过的内容（观看历史/收藏/稍后再看/当前条目）里
     *    itemTags 去重数（「按消费样本观测」诚实口径）；
     *  - [shareCount]：TasteVoyageLog 本地分享计数（feed 分享+海报导出 funnel）；
     *  - [kindsSeen]：星图天体分档表（kindCounts 的键集）；
     *  - [discoveredStars]（R248）：**点亮恒星数**（飞近点亮∪已知频道——与
     *    [discoveredChannels]「宇宙口径」相区分：点亮=你亲眼飞近看过的星）。
     */
    data class AchievementStats(
        val discoveredChannels: Int,
        val watchSec: Long,
        val favCount: Int,
        val exploredTags: Int,
        val shareCount: Int,
        val kindsSeen: Set<CelestialKind>,
        val discoveredStars: Int = 0,
    )

    /** 单项勋章（进度条的原料）：[current]/[target] 比例即进度 01 */
    class Medal(
        /** 勋章键（稳定 id——面板/海报共用） */
        val key: String,
        /** 用户语言名（面板行/海报徽章） */
        val label: String,
        /** 当前值 */
        val current: Long,
        /** 目标值（0=无此维度——如六态 0/1 口径） */
        val target: Long,
        /** 达成态（current ≥ target） */
        val met: Boolean,
        /** 单位（「频道/小时/条/个/次」——文案原料） */
        val unit: String,
    ) {
        /** 进度 0..1（target=0 恒 1=已达成） */
        val progress01: Float
            get() = if (target <= 0L) 1f else (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    }

    /** 等阶评定结果：当前等阶+全阶勋章进度+下一阶差距（面板/海报共用单一数据源） */
    class AchievementReport(
        val rank: Rank,
        /** 全部五阶的勋章进度（面板逐条画） */
        val medals: List<Medal>,
        /** 下一阶（null=已传奇） */
        val nextRank: Rank?,
        /** 距下一阶还差的勋章标签串（面板「下一阶差距」行；null=无） */
        val nextGapLine: String?,
    )

    /** 等阶的用户语言名（面板标题/海报徽章行共用单一出处） */
    fun labelOf(rank: Rank): String = when (rank) {
        Rank.BRONZE -> "青铜探索者"
        Rank.SILVER -> "白银领航员"
        Rank.GOLD -> "黄金远航者"
        Rank.DIAMOND -> "钻石跃迁者"
        Rank.LEGEND -> "传奇星海旅人"
    }

    /** 等阶徽章色（ARGB——面板色点/海报徽章/飞船尾迹基色提示共用） */
    fun colorOf(rank: Rank): Int = when (rank) {
        Rank.BRONZE -> 0xFFB08D57.toInt()
        Rank.SILVER -> 0xFFC4D3E8.toInt()
        Rank.GOLD -> 0xFFE8C46A.toInt()
        Rank.DIAMOND -> 0xFF9FE8E4.toInt()
        Rank.LEGEND -> 0xFFFF9EE8.toInt()
    }

    /**
     * 等阶评定（纯函数）：
     *  - 勋章=每阶的条件逐项（[Medal.current] 填当前值——**未解锁阶的勋章也出进度**，
     *    面板能预读「下一阶差距」）；
     *  - 晋升=该阶全部勋章 met（六态勋章 current=已见类数/target=6）；
     *  - 青铜恒持有（无勋章条件——「默认」）。
     */
    fun evaluate(s: AchievementStats): AchievementReport {
        val watchHours = s.watchSec / 3600L
        val sixSeen = SIX_KINDS.count { it in s.kindsSeen }.toLong()
        val medals = listOf(
            Medal("silver_channels", "发现频道", s.discoveredChannels.toLong(), SILVER_CHANNELS.toLong(), s.discoveredChannels >= SILVER_CHANNELS, "频道"),
            Medal("silver_watch", "观看时长", watchHours, SILVER_WATCH_HOURS, watchHours >= SILVER_WATCH_HOURS, "小时"),
            Medal("gold_channels", "发现频道", s.discoveredChannels.toLong(), GOLD_CHANNELS.toLong(), s.discoveredChannels >= GOLD_CHANNELS, "频道"),
            Medal("gold_watch", "观看时长", watchHours, GOLD_WATCH_HOURS, watchHours >= GOLD_WATCH_HOURS, "小时"),
            Medal("gold_favs", "收藏内容", s.favCount.toLong(), GOLD_FAVS.toLong(), s.favCount >= GOLD_FAVS, "条"),
            Medal("diamond_channels", "发现频道", s.discoveredChannels.toLong(), DIAMOND_CHANNELS.toLong(), s.discoveredChannels >= DIAMOND_CHANNELS, "频道"),
            Medal("diamond_tags", "探索话题", s.exploredTags.toLong(), DIAMOND_TAGS.toLong(), s.exploredTags >= DIAMOND_TAGS, "个 tag"),
            Medal("diamond_shares", "分享次数", s.shareCount.toLong(), DIAMOND_SHARES.toLong(), s.shareCount >= DIAMOND_SHARES, "次"),
            Medal("legend_channels", "发现频道", s.discoveredChannels.toLong(), LEGEND_CHANNELS.toLong(), s.discoveredChannels >= LEGEND_CHANNELS, "频道"),
            Medal("legend_watch", "观看时长", watchHours, LEGEND_WATCH_HOURS, watchHours >= LEGEND_WATCH_HOURS, "小时"),
            Medal("legend_kinds", "见齐天体形态", sixSeen, SIX_KINDS.size.toLong(), sixSeen >= SIX_KINDS.size, "类"),
            // R248 探索里程碑（点亮口径）：只出进度与达成态——**不参与等阶晋升**
            // （等阶判据=上方五阶键，原样零改；R235 结构红线）
            Medal("explore_m10", "点亮恒星", s.discoveredStars.toLong(), EXPLORE_MILESTONE_10.toLong(), s.discoveredStars >= EXPLORE_MILESTONE_10, "颗"),
            Medal("explore_m50", "点亮恒星", s.discoveredStars.toLong(), EXPLORE_MILESTONE_50.toLong(), s.discoveredStars >= EXPLORE_MILESTONE_50, "颗"),
            Medal("explore_m100", "点亮恒星", s.discoveredStars.toLong(), EXPLORE_MILESTONE_100.toLong(), s.discoveredStars >= EXPLORE_MILESTONE_100, "颗"),
            Medal("explore_m300", "点亮恒星", s.discoveredStars.toLong(), EXPLORE_MILESTONE_300.toLong(), s.discoveredStars >= EXPLORE_MILESTONE_300, "颗"),
        )
        fun met(key: String): Boolean = medals.first { it.key == key }.met
        val rank = when {
            met("legend_channels") && met("legend_watch") && met("legend_kinds") -> Rank.LEGEND
            met("diamond_channels") && met("diamond_tags") && met("diamond_shares") -> Rank.DIAMOND
            met("gold_channels") && met("gold_watch") && met("gold_favs") -> Rank.GOLD
            met("silver_channels") && met("silver_watch") -> Rank.SILVER
            else -> Rank.BRONZE
        }
        val nextRank = when (rank) {
            Rank.BRONZE -> Rank.SILVER
            Rank.SILVER -> Rank.GOLD
            Rank.GOLD -> Rank.DIAMOND
            Rank.DIAMOND -> Rank.LEGEND
            Rank.LEGEND -> null
        }
        val nextGapLine = nextRank?.let { nr ->
            val keys = when (nr) {
                Rank.SILVER -> listOf("silver_channels", "silver_watch")
                Rank.GOLD -> listOf("gold_channels", "gold_watch", "gold_favs")
                Rank.DIAMOND -> listOf("diamond_channels", "diamond_tags", "diamond_shares")
                Rank.LEGEND -> listOf("legend_channels", "legend_watch", "legend_kinds")
                Rank.BRONZE -> emptyList()
            }
            medals.filter { it.key in keys && !it.met }
                .joinToString(" · ") { "${it.label}还差 ${maxOf(it.target - it.current, 0L)} ${it.unit}" }
                .ifBlank { "下一阶就在眼前" }
        }
        return AchievementReport(rank, medals, nextRank, nextGapLine)
    }

    // ==================== 飞船酷度（ShipStyle 单口） ====================

    /** 飞船特效档（尾迹渲染器 switch 的单一数据源——「勋章越高飞船越酷」的落点） */
    enum class ShipStyle {
        /** 青铜：白色微尾（细线+微光——低速几乎隐形） */
        MICRO_TRAIL,
        /** 白银：蓝光尾+引擎脉冲（尾迹亮蓝+屏心引擎脉冲晕） */
        BLUE_PULSE,
        /** 黄金：金拖尾粒子（金色粒子沿尾迹散布） */
        GOLD_PARTICLES,
        /** 钻石：跃迁波纹+护盾光环（速度过阈扩散波纹环+屏角护盾弧） */
        WARP_RIPPLE,
        /** 传奇：星尘风暴（R348 C1 彩虹退役——单色星尘+全特效叠加；等阶=密度/尾长档差） */
        RAINBOW_STORM,
    }

    /** 尾迹基色（ARGB——渲染器/海报徽章共用） */
    fun shipColorOf(style: ShipStyle): Int = when (style) {
        ShipStyle.MICRO_TRAIL -> 0xFFE6ECF5.toInt() // 柔白
        ShipStyle.BLUE_PULSE -> 0xFF8FC6FF.toInt()  // 引擎蓝
        ShipStyle.GOLD_PARTICLES -> 0xFFE8C46A.toInt() // 拖尾金
        ShipStyle.WARP_RIPPLE -> 0xFF9FE8E4.toInt() // 跃迁青
        ShipStyle.RAINBOW_STORM -> 0xFFF6F1E2.toInt() // 星尘暖白（R348 C1 彩虹退役——星色系白段/中性暖白；色相旋转撤后粉底=无语义装饰色故一并收口）
    }

    /** 等阶 → 飞船特效档（面板文案「引擎光效/曲速引擎/跃迁特效/传说级」的代码落点） */
    fun shipStyleOf(rank: Rank): ShipStyle = when (rank) {
        Rank.BRONZE -> ShipStyle.MICRO_TRAIL
        Rank.SILVER -> ShipStyle.BLUE_PULSE
        Rank.GOLD -> ShipStyle.GOLD_PARTICLES
        Rank.DIAMOND -> ShipStyle.WARP_RIPPLE
        Rank.LEGEND -> ShipStyle.RAINBOW_STORM
    }

    /** 飞船描述行（面板等阶卡：用户语言的「这艘飞船酷在哪」） */
    fun shipDescOf(style: ShipStyle): String = when (style) {
        ShipStyle.MICRO_TRAIL -> "基础飞船 · 白色微尾"
        ShipStyle.BLUE_PULSE -> "引擎光效 · 蓝光尾 + 引擎脉冲"
        ShipStyle.GOLD_PARTICLES -> "曲速引擎 · 金拖尾粒子"
        ShipStyle.WARP_RIPPLE -> "跃迁引擎 · 跃迁波纹 + 护盾光环"
        ShipStyle.RAINBOW_STORM -> "传说级飞船 · 星尘风暴"
    }
}
