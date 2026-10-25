package dev.tgfeed.core.tastemap

import kotlin.math.ln1p

/**
 * R223b 天体分档器（口味星图一期·纯函数，零 Android 依赖单测可跑）：
 *
 * 「口味星图」全宇宙版的天体生命周期判定——判据**全部来自本地深索引与既有快照**
 * （R184 零 Search 族红线），渲染隐喻与天文学严格性冲突时视觉直觉优先、注释诚实
 * 标注（设计文档 §1.1 设计原则）。
 *
 * - **六态恒星**（频道=恒星，状态可随时间迁移——同一频道重算即迁移，分档器是
 *   纯函数，「星系活着」的静态证据面）：
 *   | 态 | 判据（全本地） | 优先级位 |
 *   |---|---|---|
 *   | 超新星 | 爆发中：近 7 天条数 ≥ [SUPERNOVA_MIN_RECENT7] 且 > [SUPERNOVA_RATIO]×前 7 天（金矿 recent7/prev7） | 1（瞬态最强，盖过死亡档） |
 *   | 白矮星 | 断更 > [DWARF_DAYS] 天 且 条数 > 0（金矿 lastActivity；DS-3 断更口径加深档：14 天=停更候选、180 天=死亡） | 2（死亡终态） |
 *   | 红巨星 | 巨存量低活跃：条数对数进入全宇宙 top 30% 分位（volumeRank01 ≥ [GIANT_RANK]）且 断更 > [ACTIVE_DAYS] 天 | 3（膨胀冷却） |
 *   | 原恒星 | 新频道：首见 < [PROTOSTAR_DAYS] 天（创世纪双源口径：索引最早条目 × 首次观看取早） | 4（新生） |
 *   | 恒星 | 活跃频道：最近活动 < [ACTIVE_DAYS] 天 且有条目；**以及一切判据数据缺失的诚实缺省**（绝不冒充特殊天体） | 5（缺省） |
 *   | 恒星（缺省） | 任何判据数据缺失（金矿未建/无索引） | 同上（设计文档「降级兼容」行） |
 * - **非频道天体**（实体类型由装配口判，不经本矩阵）：[PULSAR]=bot /
 *   [CLUSTER]=群组 / [METEOR]=真人用户私聊（设计 §1.4）。
 * - **黑洞**（R249）：**不经本矩阵**——[TasteAstroPhysics.isBlackHoleCandidate] 的
 *   奥本海默三重门（P95 分位 ∧ 均值×8 ∧ 绝对下限）由布局期后置晋升（top
 *   [TasteAstroPhysics.MAX_BLACK_HOLES] 颗）：判据矩阵与全部旧判据**一字不动**，
 *   黑洞=超大聚合频道的质量终态（盖过瞬态/稳态——爆发中的黑洞=吸积盛宴）。
 *
 * 优先级依据：瞬态事件（爆发）> 终态（死亡）> 稳态（冷却/新生/主序）——超新星是
 * 正在猛烈发光的过程必须盖过一切稳态；白矮星先于红巨星防「已死频道冒充膨胀中」；
 * 原恒星与红巨星理论互斥居多（首见<90 天 → 最近活动必然<90 天），冲突时红巨星
 * 先判（巨存量频道的体量信息比「较新」更稀缺）。
 *
 * 断更/活跃口径=本地索引 head（金矿快照 6h TTL）——月采样频道 lastActivity 只反映
 * head 窗，图例与信息卡诚实标注「按本地索引观测」（设计 §1.2 说明，风险 #7）。
 */
enum class CelestialKind {
    /** 恒星（主序）：活跃频道，或任何判据缺据的诚实缺省——现有星笔刷原样 */
    STAR,

    /** 原恒星：首见 < 90 天的新频道（蓝白偏色 + 吸积壳——还没点燃的感觉） */
    PROTOSTAR,

    /** 红巨星：巨存量低活跃（半径膨胀 + 暖偏 + 稀薄大外晕——曾经巨大现在冷却） */
    RED_GIANT,

    /** 白矮星：断更 > 180 天（小而极亮白核——死而不灭，高密度高亮度残骸） */
    WHITE_DWARF,

    /** 超新星：近 7 天条数 ≥ 阈值 且 > 2× 前 7 天（爆发光环 + 扩散遗迹环） */
    SUPERNOVA,

    /** 脉冲星：bot（极小亮核 + 周期闪灭双束——「周期性射电信号」=应答服务性） */
    PULSAR,

    /** 星团：群组（核心亮核 + 成员尘点云——memberCount=星团直径，规模可视化） */
    CLUSTER,

    /** 流星：真人用户私聊（星系外围游荡的小流星体——不属于内容星系） */
    METEOR,

    /**
     * 黑洞（R249）：超大聚合频道（条数过奥本海默三重门后布局期晋升——判据不在本
     * 矩阵，见 [TasteAstroPhysics.isBlackHoleCandidate]）。渲染=事件视界+旋转吸积盘
     * +引力透镜；不进 [TasteAchievements.SIX_KINDS]（等阶判据结构零改——黑洞是
     * 勋章体系外的天体奇观）。
     */
    BLACK_HOLE,

    /**
     * 中子星（R317 S1 枚举哨点）：大质量频道爆发后骤停的坍缩余烬——简并态阶梯
     * 入谱（[TasteAstroPhysics] 终态宣判矩阵判据，布局期后置晋升同黑洞模式；
     * 六态判据矩阵一字不动）。渲染笔刷归批 1 wiring（CompactStarFx 蓝白核+遗迹环）。
     */
    NEUTRON_STAR,

    /**
     * 夸克星（R317 S1 枚举哨点）：比中子星更极端的稀有中间态——全库理应 ≤1 颗
     * 甚至零（[TasteAstroPhysics.MAX_QUARK]；判据=中子星门 ∧ 爆发烈度全库 outlier）。
     * 枚举尾部只加不改序（哨点方案：扩值不破坏六态矩阵既有语义）。
     */
    QUARK_STAR,

    /**
     * 白洞（R317 S1 枚举哨点）：被你大量收割却零回馈的频道型源泉（只发不收——
     * [TasteAstroPhysics.judgeWhiteHoles] 判据；bot 实体档仍归脉冲星不动）。
     * 与黑洞对称的星系奇观（top≤4）。
     */
    WHITE_HOLE,
}

/** 天体分档纯函数集合（判据矩阵与阈值常量单一出处，单测锚定） */
object TasteGalaxyClassifier {

    /** 恒星活跃判据：最近活动距今天数上限（超过=低活跃） */
    const val ACTIVE_DAYS = 60L

    /** 原恒星判据：频道首见距今天数上限 */
    const val PROTOSTAR_DAYS = 90L

    /** 白矮星判据：断更天数下限（严格大于） */
    const val DWARF_DAYS = 180L

    /** 超新星判据：近 7 天条数下限（防「1 vs 0」级噪声把偶发更新当爆发） */
    const val SUPERNOVA_MIN_RECENT7 = 3

    /** 超新星判据：近 7 天 / 前 7 天增速比（严格大于） */
    const val SUPERNOVA_RATIO = 2.0

    /** 红巨星判据：全宇宙条数对数分位下限（≥0.70 = top 30% 体量） */
    const val GIANT_RANK = 0.70f

    /**
     * 频道天体分档输入（装配口收集，字段全部现成本地只读）：
     * 未知证据的哨兵约定——[lastActivitySec]/[recent7]/[prev7] 负值或 0 = 无金矿
     * 证据（[items] 允许 0=未索引但首见/分位仍有意义）。
     */
    data class ChannelEvidence(
        /** 深索引条数（基因 GROUP BY；0=未索引） */
        val items: Int,
        /** 最近活动（epoch 秒，金矿 head 最新行；≤0=未知） */
        val lastActivitySec: Long,
        /** 近 7 天发布条数（金矿；-1=无金矿档案） */
        val recent7: Int,
        /** 再前 7 天发布条数（金矿；-1=无金矿档案） */
        val prev7: Int,
        /** 频道首见毫秒（索引最早条目×首次观看取早；0=无证据） */
        val firstSeenMs: Long,
        /** 全宇宙条数对数分位 0..1（1=最大体量；0=无索引） */
        val volumeRank01: Float,
    )

    /**
     * 频道六态分档（纯函数：同输入同输出——重算即状态迁移，无隐藏状态）。
     * 判据矩阵与优先级见本对象 KDoc；任何数据缺失走 [CelestialKind.STAR]
     * 诚实缺省（设计「降级兼容」行：绝不冒充特殊天体）。
     */
    fun classifyChannel(e: ChannelEvidence, nowMs: Long): CelestialKind {
        // ---- 1) 超新星（瞬态最强：正在猛烈发光盖过一切稳态） ----
        // recent7 哨兵 -1（金矿缺失）不满足 ≥3 门槛 → 诚实跳过
        if (e.recent7 >= SUPERNOVA_MIN_RECENT7 && e.prev7 >= 0 &&
            e.recent7 > SUPERNOVA_RATIO * e.prev7
        ) {
            return CelestialKind.SUPERNOVA
        }
        val daysSinceActivity = if (e.lastActivitySec > 0L) {
            (nowMs / 1000L - e.lastActivitySec) / 86_400L
        } else {
            -1L // 无最近活动证据
        }
        // ---- 2) 白矮星（死亡终态：>180 天且确有条目） ----
        if (e.items > 0 && daysSinceActivity > DWARF_DAYS) {
            return CelestialKind.WHITE_DWARF
        }
        // ---- 3) 红巨星（巨存量低活跃：top 30% 分位且 >60 天） ----
        if (daysSinceActivity > ACTIVE_DAYS && e.volumeRank01 >= GIANT_RANK) {
            return CelestialKind.RED_GIANT
        }
        // ---- 4) 原恒星（新生：首见 <90 天；无证据=0 不算年轻） ----
        if (e.firstSeenMs > 0L && nowMs - e.firstSeenMs < PROTOSTAR_DAYS * 86_400_000L) {
            return CelestialKind.PROTOSTAR
        }
        // ---- 5) 恒星（活跃主序 + 一切缺据诚实缺省） ----
        return CelestialKind.STAR
    }

    /**
     * 全宇宙条数对数分位（红巨星判据的分位输入，装配期对全池频道一次算）：
     * rank01 = 「对数体量严格小于本频道的频道数 / (有效频道数-1)」，1.0=最大体量。
     * 有效频道=[items]>0；条数 0 或单频道池 → 0（永不冒充巨存量）。对数口径压
     * 重尾（设计 §1.2：条数对数进入 top 30% 分位）。
     */
    fun volumeRankOf(items: Int, allItems: IntArray): Float {
        if (items <= 0 || allItems.isEmpty()) return 0f
        val key = ln1p(items.toFloat())
        var smaller = 0
        var valid = 0
        for (v in allItems) {
            if (v <= 0) continue
            valid++
            if (ln1p(v.toFloat()) < key) smaller++
        }
        return if (valid <= 1) 0f else smaller.toFloat() / (valid - 1)
    }

    /** 天体类型的用户语言名（信息卡「天体档案」段/图例共用单一出处） */
    fun labelOf(kind: CelestialKind): String = when (kind) {
        CelestialKind.STAR -> "恒星"
        CelestialKind.PROTOSTAR -> "原恒星"
        CelestialKind.RED_GIANT -> "红巨星"
        CelestialKind.WHITE_DWARF -> "白矮星"
        CelestialKind.SUPERNOVA -> "超新星"
        CelestialKind.PULSAR -> "脉冲星"
        CelestialKind.CLUSTER -> "星团"
        CelestialKind.METEOR -> "流星"
        CelestialKind.BLACK_HOLE -> "黑洞"
        CelestialKind.NEUTRON_STAR -> "中子星"
        CelestialKind.QUARK_STAR -> "夸克星"
        CelestialKind.WHITE_HOLE -> "白洞"
    }
}
