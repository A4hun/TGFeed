package dev.tgfeed.core.tastemap

import dev.tgfeed.core.feed.CrawlThroughputPolicy
import dev.tgfeed.core.feed.IndexTierPolicy
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R249 天体物理引擎纯函数（星系物理层·零 Android 依赖，单测可跑）——用户终极愿景
 * 「根据万有引力、开普勒定律、天体物理学，整个星系图谱就运动了起来活了起来」的
 * 数学底座：
 *
 *  - **天体物理映射**（数据↔天文学深度对齐，任务书 ①）：
 *    质量=条数对数 [mass01Of]（引力强度单一量纲——天文质量本就是对数感）；
 *    光度=近 7 天发布量（科普文案与吸积盘亮度原料）；温度谱=[spectralClassOf]
 *    （活跃率→O/B/A/F/G/K/M——高活跃=O/B 蓝白炽热/低活跃=M 红暗）；年龄=频道
 *    首见至今（文案「诞生 N 天」）；
 *  - **黑洞**（[isBlackHoleCandidate] 奥本海默三重门）：条数 ≥ 全库 P95 分位
 *    ∧ ≥ 均值×8（用户口径「超过所有频道平均值的相当于奥本海默极限」）∧ ≥800
 *    条绝对下限（小库不冒充）——**分类矩阵外置**：六态判据一字不动，黑洞=布局期
 *    后置晋升（质量终态盖过瞬态/稳态，top [MAX_BLACK_HOLES] 颗）；
 *  - **R317 生死判据族**（发布直方图证据链——生死判据单一出处 S2）：黑洞终态
 *    宣判矩阵「**大而坍缩非大而晋**」（爆发史+骤停史取证）+ 中子星简并判据 +
 *    夸克星苛刻判据 + 白洞收割零回馈门 + 类星体 accretion01 活动态修饰（不改
 *    枚举）——证据源 [DeepSearchIndex.chatPublishHistogram]（S3 点查件）；
 *  - **R326 诞生侧判据族**（采集/发现状态证据链——与终态判据族同文件=**生死判据
 *    单一出处** S1'）：四态宣判矩阵 [judgeBirthStates]（星云候选/吸积盘中/原恒星
 *    年轻位+行星成型修饰位——判据门全本地 O(1) 零 IO：终态>诞生互斥+缺据回落
 *    绝不冒充）+[birthPoolOf] 池选+[goldEvidenceOf]/[probeProgress01] 单口——
 *    **注记制**（StarNode.birth 字段）零新枚举，宣判接线归 R327 wiring；
 *  - **开普勒三定律**（任务书 ②）：第三定律 [keplerOmega]（ω=C/a^1.5——近轨道
 *    快远轨道慢）；第二定律 [solveKeplerE]+[keplerTrueAnomaly]（平近点角→牛顿
 *    迭代偏近点角→真近点角=等面积：近日点快远日点慢）；第一定律 [orbitRadius]
 *    （r(ν)=a(1−e²)/(1+e·cosν)——恒星在焦点的严格椭圆）；
 *  - **星系微运动**（5s 低频有界漂移）：[driftAmpOf]/[driftOmegaOf]（微轨幅度与
 *    开普勒角速度——距主导引力井越近摆越快）/[attractorPullOf]（引力吸引趋势：
 *    漂移中心向井偏移=「大频道周围的星被吸引」）/[binaryAmpOf]/[binaryOmegaOf]
 *    （双星互相绕转的有界化：反相椭圆摆动，ω 同式开普勒第三定律）；
 *  - **引力透镜**（[lensOffset] 渲染期屏幕空间弯曲）：偏移 ∝ mass01·wellR²/
 *    (d+0.35·wellR)（1/d 衰减≈爱因斯坦环量级）；井后的星弯曲更强（behindFactor
 *    深度比）；黑洞=径向+切向旋涡偏移（吸积旋臂观感）；幅度钳 ≤0.22·d ∧ ≤46px
 *    （绝不越过井心=不产生星图读错）；
 *  - **天体科普**（[factLine] 任务书 ③）：每类天体一条「天文知识×频道数据」融合
 *    文案（信息卡天体档案段后加行的单一出处）；缺据走诚实回落，绝不编造数字。
 *
 * 工程纪律（任务书「帧预算 60fps」）：引力=渲染期变换不重算物理——井表/吸引域
 * 装配期烘焙（见 ui TasteGalaxyPhysics），本对象只出公式；微运动=极低频更新
 * （每 5s 一拍），不做逐帧物理模拟。
 */
object TasteAstroPhysics {

    // ==================== 天体物理映射（任务书 ①） ====================

    /**
     * 恒星质量（0..1 归一）：**ln1p(条数)/ln1p(全池 max)**——引力强度/引力井
     * 强度的单一量纲（对数口径=与 [TasteGalaxyClassifier.volumeRankOf] 同哲学：
     * 天文质量本就是对数感，线性会让头部黑洞吞掉整个量程）。
     */
    fun mass01Of(items: Int, maxItems: Int): Float =
        if (items > 0 && maxItems > 0) ln1p(items.toFloat()) / ln1p(maxItems.toFloat()) else 0f

    /**
     * 光谱型（温度谱）：活跃率→O/B/A/F/G/K/M 七档（任务书「恒星光度/温度=活跃率
     * （高活跃=O/B 型蓝白炽热 / 低活跃=M 型红暗）」）：
     *  - recent7 ≥0：score=recent7/14 钳 0..1（每周 14 条+=最炽热）；
     *  - recent7 无金矿证据：按断更天数回落（<7 天 0.5 / <60 天 0.25 / <180 天
     *    0.10 / 更久 0.02）；
     *  - 双缺=诚实 G 型（中性缺省，不冒炽热不冒暗红）。
     * 类序 idx=round((1−score)·6)：0=O … 6=M。
     */
    enum class SpectralClass(val label: String) {
        O("O 型 · 蓝白炽热"),
        B("B 型 · 蓝白"),
        A("A 型 · 白"),
        F("F 型 · 黄白"),
        G("G 型 · 太阳的黄"),
        K("K 型 · 橙红"),
        M("M 型 · 暗红"),
    }

    /** 光谱型判定（[SpectralClassOf] 的判据单口；lastActivityDays=-1=未知） */
    fun spectralClassOf(recent7: Int, lastActivityDays: Long): SpectralClass {
        val score: Float = if (recent7 >= 0) {
            // R317：饱和口径常量化（RECENT7_SATURATION——与 accretion01Of 单一出处）
            (recent7.toFloat() / RECENT7_SATURATION).coerceIn(0f, 1f)
        } else when {
            lastActivityDays < 0L -> return SpectralClass.G
            lastActivityDays < 7L -> 0.35f // 一周内活跃=太阳般的黄
            lastActivityDays < 60L -> 0.20f // 两个月内=橙红
            lastActivityDays < 180L -> 0.08f // 半年内=暗红
            else -> 0.02f
        }
        val idx = ((1f - score) * 6f + 0.5f).toInt().coerceIn(0, 6)
        return SpectralClass.entries[idx]
    }

    // ==================== 黑洞（奥本海默极限） ====================

    /** 黑洞分位门：条数对数分位 ≥0.95（全库 P95，任务书口径一） */
    const val BLACK_HOLE_RANK = 0.95f

    /**
     * 黑洞均值门：条数 ≥ 均值×1.0（用户原话口径「超过所有频道平均值的相当于
     * 奥本海默极限」——16384 实库标定：均值门若取 ×8（示例 15 倍的保守下沿）
     * 会把爬取上限档（~50k=超大聚合频道）全拒之门外=零黑洞实锤；×1+P95 合取
     * 后再由布局封顶 top4=黑洞恒为「库里最重的天体」，绝不矮子里拔将军）。
     */
    const val BLACK_HOLE_MEAN_MULT = 1.0f

    /** 黑洞绝对体量下限（条）：小库防「矮子里拔将军」——没有足够的星就不成星系 */
    const val BLACK_HOLE_MIN_ITEMS = 800

    /** 黑洞晋升上限（颗）：星系奇观的稀有性（真实星系 SMBH 亦稀有） */
    const val MAX_BLACK_HOLES = 4

    /**
     * 黑洞候选判定（奥本海默三重门——三条件**合取**：P95 分位 ∧ 超过全库均值
     * ∧ 绝对下限；meanItems≤0（无全库统计）=诚实跳过）。
     * **注意**：本函数只判资格；晋升与封顶（top [MAX_BLACK_HOLES]）在布局期完成
     * ——分类矩阵 [TasteGalaxyClassifier.classifyChannel] 一字不动（R223b 红线）。
     */
    fun isBlackHoleCandidate(items: Int, volumeRank01: Float, meanItems: Float): Boolean =
        items >= BLACK_HOLE_MIN_ITEMS &&
            volumeRank01 >= BLACK_HOLE_RANK &&
            meanItems > 0f &&
            items >= BLACK_HOLE_MEAN_MULT * meanItems

    // ==================== R317 生死判据族（发布直方图证据链——单文件收敛） ====================
    //
    // 用户实锤（21:56+裁决）：黑洞形成「过于儿戏、数据支撑站不住脚」——纯体量三重门
    // 的「够大就晋」判废，改为「**大而坍缩，非大而晋**」：死亡方式由数据事件驱动
    // （发布直方图的爆发史/骤停史取证），每个终态有「它曾经是谁」的前史可讲。
    // 证据源=[DeepSearchIndex.chatPublishHistogram]（S3 点查件，日桶双臂口径），
    // 本段全部纯函数（判据单一出处 S2）；宣判接线（布局期后置晋升）归批 1 wiring。

    /** 近 7 天发布量饱和口径（每周 14 条+=最炽热）——光谱型与 [accretion01Of] 共用单一出处（纪律§一） */
    const val RECENT7_SATURATION = 14

    /** 爆发峰绝对下限（30 天桶条数）——防「1 vs 0」级噪声把偶发更新当爆发 */
    const val BURST_MIN_COUNT = 8

    /** 爆发峰倍率：30 天桶 ≥ 3×该频道桶基线均值=「爆发过」 */
    const val BURST_BASELINE_MULT = 3f

    /** 骤停档：死前 60 天桶密度 ≥1.5×基线=瞬间断流（核心坍缩瞬间） */
    const val ABRUPT_DENSITY_MULT = 1.5f

    /** 缓熄档：死前 60 天桶密度 <0.5×基线=慢慢淡出 */
    const val SLOW_FADE_DENSITY_MULT = 0.5f

    /** 死前收尾窗（天）——骤停/缓熄判据的观察窗 */
    const val DEATH_WINDOW_DAYS = 60L

    /** 黑洞已坍缩门：断更 >60 天（严格大于）∧ 骤停档 */
    const val COLLAPSED_DAYS = 60L

    /** 黑洞活跃过门：直方图爆发峰 ∨ 近 7 天 ≥4 条 */
    const val TERMINAL_ACTIVE_RECENT7 = 4

    /** 吸积盛宴门=黑洞「正在坍缩」分支（recent7≥8/周）——类星体相同门（一名两用：正在坍缩=正在进食） */
    const val ACCRETION_FEAST_RECENT7 = 8

    /** 中子星简并门：对数体量分位 ≥P90 */
    const val NEUTRON_RANK = 0.90f

    /** 中子星简并门：绝对体量下限（与 [BLACK_HOLE_MIN_ITEMS] 同值同哲学——「没有足够的星就不成星系」单一出处） */
    const val NEUTRON_MIN_ITEMS = BLACK_HOLE_MIN_ITEMS

    /** 中子星死亡线：断更 >90 天（严格大于，比黑洞坍缩门更久——余烬更冷） */
    const val NEUTRON_DEAD_DAYS = 90L

    /** 夸克星烈度门：爆发峰桶值 ≥ 其余中子候选最大值的 1.5×（「密度更极端」的烈度代理） */
    const val QUARK_BURST_MULT = 1.5f

    /** 夸克星全库上限（理应个位数甚至零——极罕见中间态） */
    const val MAX_QUARK = 1

    /** 收尾形态（发布直方图尾部形状——判据族与 diff 报告共用诊断口径） */
    enum class DeathPattern {
        /** 一周内仍有发布——活着，谈不上收尾 */
        ALIVE,
        /** 骤停：死前 60 天密度 ≥1.5×基线（瞬间断流=核心坍缩瞬间） */
        ABRUPT_STOP,
        /** 缓熄：死前 60 天密度 <0.5×基线（慢慢淡出） */
        SLOW_FADE,
        /** 平稳收尾：密度居中（不骤不缓） */
        STEADY_TAIL,
    }

    /**
     * 发布直方图特征（判据族原料——[publishHistogramFeatures] 单口提取）：
     *  - [baselinePer30d]=非空 30 天桶均值（GROUP BY 语义自然读法=活跃期基线）；
     *  - [burstBucketSec]/[burstPeakCount]=爆发峰（最大 30 天桶，平手取最近——
     *    「爆发在死亡前」判据取最近峰）；[hasBurstPeak]=过 [BURST_MIN_COUNT] 与
     *    [BURST_BASELINE_MULT] 双门；
     *  - [deathPattern]=死前 [DEATH_WINDOW_DAYS] 天窗密度（日桶直取，无 30 天对齐
     *    误差）对照基线的收尾档；
     *  - [deadDays]/[lifetimeDays]=断更天数/活跃寿命（**日粒度精度 ±1 天**——对
     *    60/90 天门零影响）；首末日=直方图首末桶。
     */
    data class PublishFeatures(
        val items: Int,
        val firstSec: Long,
        val lastSec: Long,
        val baselinePer30d: Float,
        val burstBucketSec: Long,
        val burstPeakCount: Int,
        val hasBurstPeak: Boolean,
        val deathPattern: DeathPattern,
        val deadDays: Long,
        val lifetimeDays: Long,
    )
    /**
     * 发布直方图特征提取（纯函数；30 天桶=日桶聚合 epoch 对齐 /30——判据族工作粒度）。
     * 输入=[DeepSearchIndex.chatPublishHistogram] 直供的日桶直方图（(dayIndex, count)
     * 升序；dayIndex×86400=桶起点秒）；**空表/全零=null=缺据**（判据族不宣判——
     * 缺据不出，绝不冒充终态）。
     */
    fun publishHistogramFeatures(dayHistogram: List<Pair<Int, Int>>, nowSec: Long): PublishFeatures? {
        if (dayHistogram.isEmpty()) return null
        var items = 0
        var firstDay = Int.MAX_VALUE
        var lastDay = Int.MIN_VALUE
        // 30 天桶聚合（epoch 对齐：bucket=day/30——与判据族「30 天桶」口径单一出处）
        val byBucket = HashMap<Int, Int>(dayHistogram.size / 3 + 1)
        for ((day, count) in dayHistogram) {
            if (day <= 0 || count <= 0) continue // 0 值日期行/空桶不入证
            items += count
            if (day < firstDay) firstDay = day
            if (day > lastDay) lastDay = day
            val b = day / 30
            byBucket.merge(b, count, Int::plus)
        }
        if (items <= 0 || firstDay == Int.MAX_VALUE) return null
        val firstSec = firstDay * 86_400L
        val lastSec = lastDay * 86_400L
        val baseline = items.toFloat() / byBucket.size
        // 爆发峰：最大 30 天桶（平手取最近——Key 按 (count, bucket) 降序）
        var peakCount = 0
        var peakBucket = -1
        for ((b, cnt) in byBucket) {
            if (cnt > peakCount || (cnt == peakCount && b > peakBucket)) {
                peakCount = cnt
                peakBucket = b
            }
        }
        val hasBurst = peakCount >= BURST_MIN_COUNT && peakCount >= BURST_BASELINE_MULT * baseline
        // 死前 60 天窗密度（日桶直取——窗=[末日前 59 天, 末日] 恰 60 天：桶起点对齐
        // 的稀疏证据下恰含 2 个桶起点，稳态通道密度=基线不误判骤停；窗内条数折算每 30 天）
        val deadDays = (nowSec - lastSec) / 86_400L
        val pattern: DeathPattern
        if (deadDays < 7L) {
            pattern = DeathPattern.ALIVE
        } else {
            val winStartDay = lastDay - (DEATH_WINDOW_DAYS - 1L).toInt()
            var winCount = 0
            for ((day, count) in dayHistogram) {
                if (day >= winStartDay && day <= lastDay) winCount += count
            }
            val densityPer30 = winCount / (DEATH_WINDOW_DAYS / 30f)
            pattern = when {
                densityPer30 >= ABRUPT_DENSITY_MULT * baseline -> DeathPattern.ABRUPT_STOP
                densityPer30 < SLOW_FADE_DENSITY_MULT * baseline -> DeathPattern.SLOW_FADE
                else -> DeathPattern.STEADY_TAIL
            }
        }
        return PublishFeatures(
            items, firstSec, lastSec, baseline,
            peakBucket * 30L * 86_400L, peakCount, hasBurst, pattern,
            deadDays, (lastSec - firstSec) / 86_400L,
        )
    }

    /** 终态宣判候选（装配口收集——判据输入全本地既有字段） */
    class TerminalEvidence(
        /** 频道 id（破平与诊断用） */
        val chatId: Long,
        /** 体量条数（装配口径 max(索引条数, 金矿条数)——三重门/简并门输入） */
        val items: Int,
        /** 对数体量分位（[TasteGalaxyClassifier.volumeRankOf] 单源） */
        val volumeRank01: Float,
        /** 近 7 天发布条数（-1=无金矿档案） */
        val recent7: Int,
        /** 发布直方图特征（null=点查缺据→**不宣判**，诚实回落六态） */
        val histogram: PublishFeatures?,
    )

    /** 终态宣判档（诊断口径——装配探针/宣判 diff 报告消费；kind=null=回落六态矩阵） */
    enum class TerminalReason {
        /** 黑洞·大而坍缩：活跃过 ∧ 断更>60 天 ∧ 骤停档 */
        BLACK_HOLE_COLLAPSED,
        /** 黑洞·正在坍缩：吸积盛宴期（recent7≥8——类星体相） */
        BLACK_HOLE_FEASTING,
        /** 过黑洞坍缩门但超 top≤4 帽——诚实回落六态（星系奇观稀有性） */
        BLACK_HOLE_CAPPED,
        /** 中子星·简并态：爆发→骤停的坍缩余烬 */
        NEUTRON_STAR,
        /** 夸克星·极端中间态：爆发烈度全库 outlier */
        QUARK_STAR,
        /** 静默巨兽：三重门过但未过坍缩判据（无爆发史/缓熄未骤停/未在盛宴）——回落六态（红巨星等） */
        SILENT_GIANT,
        /** 未过任何终态门——六态矩阵照判 */
        NOT_TERMINAL,
    }

    /** 终态宣判结果（[judgeTerminalStates] 出口——同一证据流三判互斥；data class=确定性单测等值锚） */
    data class TerminalVerdict(
        val chatId: Long,
        /** 宣判天体类型（null=回落六态矩阵——缺据/未过门/超帽） */
        val kind: CelestialKind?,
        val reason: TerminalReason,
    )

    /**
     * 终态候选池上限（S3 有界预算口——r315 预登记降级方案「候选集再收缩」的落形：
     * snap2 实测 P90 简并门池 56 颗超设计预算 30，直方图点查成本 ∝ 频道条数
     * （173~441ms/5 万条级频道），**池选在点查之前**——按体量降序截断，被截者
     * 回落六态不宣判=诚实截断）。wiring 批以此口径预筛后才逐个点查直方图。
     */
    const val TERMINAL_POOL_CAP = 30

    /**
     * 终态候选池（装配口预筛——**先池选后点查**）：简并门（rank01≥[NEUTRON_RANK] ∧
     * items≥[NEUTRON_MIN_ITEMS]；三重门过者为其子集）过者按体量降序+chatId 破平
     * 截断 ≤[TERMINAL_POOL_CAP]。输入=证据壳（histogram=null——池选后 wiring 逐个
     * 点查 [DeepSearchIndex.chatPublishHistogram] 补特征再进 [judgeTerminalStates]）。
     * 纯函数确定性（同输入同输出）。
     */
    fun terminalPoolOf(candidates: List<TerminalEvidence>): List<TerminalEvidence> =
        candidates.asSequence()
            .filter { it.volumeRank01 >= NEUTRON_RANK && it.items >= NEUTRON_MIN_ITEMS }
            .sortedWith(compareByDescending<TerminalEvidence> { it.items }.thenBy { it.chatId })
            .take(TERMINAL_POOL_CAP)
            .toList()

    /**
     * 终态宣判矩阵（S2 核心——优先级：夸克星>黑洞>中子星>六态矩阵；六态判据矩阵
     * 与 [isBlackHoleCandidate] 三重门**一字不动**，本函数只在其上叠加事件证据）：
     *  - **黑洞（大而坍缩）**：三重门 ∧ 活跃过（[hasBurstPeak] ∨ recent7≥
     *    [TERMINAL_ACTIVE_RECENT7]）∧（已坍缩（断更>[COLLAPSED_DAYS] ∧ 骤停档）
     *    ∨ 正在坍缩（recent7≥[ACCRETION_FEAST_RECENT7] 吸积盛宴=类星体相））——
     *    体量降序+chatId 破平取 top≤[MAX_BLACK_HOLES]（R249 晋升同口径），溢出=
     *    [TerminalReason.BLACK_HOLE_CAPPED] 回落；
     *  - **中子星（坍缩余烬口径）**：简并门（rank01≥[NEUTRON_RANK] ∧ items≥
     *    [NEUTRON_MIN_ITEMS] ∧ **未过三重门**——三重门过者的中子条件已被黑洞
     *    优先吸收，两读法行为收敛）∧ 爆发峰在死亡前 90 天内（峰桶与死亡窗相交）
     *    ∧ 断更>[NEUTRON_DEAD_DAYS] ∧ 骤停档——**无帽**（诚实：库里有就有没有
     *    就零）；
     *  - **夸克星（更苛刻）**：中子星门全过 ∧ 爆发烈度全库 top1（爆发峰桶值 ≥
     *    其余中子候选最大值的 [QUARK_BURST_MULT]×——「密度更极端」代理；**须存在
     *    对照候选**：单颗无对照不冒充「更极端」）——≤[MAX_QUARK] 颗；
     *  - **静默巨兽**：三重门过但未过坍缩判据——[TerminalReason.SILENT_GIANT]
     *    回落六态（「大而未坍缩=还是巨星」）。
     * **诚实红线**：直方图缺据（histogram=null）的候选不宣判任何终态（回落六态
     * ——绝不冒充）；输出序=输入序（确定性：同输入同输出）。
     */
    fun judgeTerminalStates(candidates: List<TerminalEvidence>, meanItems: Float): List<TerminalVerdict> {
        if (candidates.isEmpty()) return emptyList()
        val byId = HashMap<Long, TerminalEvidence>(candidates.size * 2)
        for (c in candidates) byId[c.chatId] = c
        // ---- 门 1：黑洞（三重门 ∧ 事件证据；体量降序+chatId 破平 top≤4） ----
        val triplePass = HashSet<Long>() // 三重门过（黑洞体量域——中子星门排除域）
        val bhGate = HashMap<Long, TerminalReason>() // chatId -> 过坍缩门的档（COLLAPSED/FEASTING）
        val bhSilent = HashSet<Long>() // 三重门过但未过坍缩判据（静默巨兽）
        for (c in candidates) {
            if (!isBlackHoleCandidate(c.items, c.volumeRank01, meanItems)) continue
            triplePass.add(c.chatId)
            val feasting = c.recent7 >= ACCRETION_FEAST_RECENT7
            val h = c.histogram
            if (h == null) {
                // 直方图缺据：盛宴档仍可判（recent7 即事件证据）；否则诚实静默
                // （体量过门无事件史=大而未坍缩——缺据不冒充坍缩）
                if (feasting) bhGate[c.chatId] = TerminalReason.BLACK_HOLE_FEASTING else bhSilent.add(c.chatId)
                continue
            }
            val activeEver = h.hasBurstPeak || c.recent7 >= TERMINAL_ACTIVE_RECENT7
            val collapsed = h.deathPattern == DeathPattern.ABRUPT_STOP && h.deadDays > COLLAPSED_DAYS
            when {
                feasting -> bhGate[c.chatId] = TerminalReason.BLACK_HOLE_FEASTING
                activeEver && collapsed -> bhGate[c.chatId] = TerminalReason.BLACK_HOLE_COLLAPSED
                else -> bhSilent.add(c.chatId) // 大而未坍缩（无爆发史/缓熄未骤停/未在盛宴）
            }
        }
        val promoted = HashSet<Long>() // 帽内宣判黑洞（top≤MAX_BLACK_HOLES）
        if (bhGate.isNotEmpty()) {
            val ranked = bhGate.keys.mapNotNull { byId[it] }
                .sortedWith(compareByDescending<TerminalEvidence> { it.items }.thenBy { it.chatId })
            for (c in ranked.take(MAX_BLACK_HOLES)) promoted.add(c.chatId)
        }
        // ---- 门 2：中子星（简并门+爆发骤停；未过三重门；无帽） ----
        val neutronQualified = ArrayList<TerminalEvidence>()
        for (c in candidates) {
            if (c.chatId in triplePass) continue // 未过黑洞门口径——三重门过者的中子条件已被黑洞优先吸收
            if (c.volumeRank01 < NEUTRON_RANK || c.items < NEUTRON_MIN_ITEMS) continue
            val h = c.histogram ?: continue // 缺据不宣判
            // 爆发峰在死亡前 90 天内：峰桶 [start, start+30d) 与死亡窗 [last-90d, last] 相交
            val burstEndSec = h.burstBucketSec + 30L * 86_400L
            val burstNearDeath = h.hasBurstPeak &&
                h.burstBucketSec <= h.lastSec &&
                burstEndSec >= h.lastSec - NEUTRON_DEAD_DAYS * 86_400L
            if (burstNearDeath && h.deadDays > NEUTRON_DEAD_DAYS &&
                h.deathPattern == DeathPattern.ABRUPT_STOP
            ) {
                neutronQualified += c
            }
        }
        // ---- 门 3：夸克星（中子门全过 ∧ 烈度 outlier；≤MAX_QUARK；须有对照） ----
        var quarkId = -1L
        if (neutronQualified.size >= 2) {
            val byPeak = neutronQualified.sortedWith(
                compareByDescending<TerminalEvidence> { it.histogram!!.burstPeakCount }.thenBy { it.chatId },
            )
            val top = byPeak[0]
            val secondPeak = byPeak[1].histogram!!.burstPeakCount
            if (top.histogram!!.burstPeakCount >= QUARK_BURST_MULT * secondPeak) {
                quarkId = top.chatId
                neutronQualified.remove(top) // 互斥：同一证据流三判不重叠
            }
        }
        val neutronIds = HashSet<Long>(neutronQualified.size)
        for (c in neutronQualified) neutronIds.add(c.chatId)
        // ---- 出口：输入序回填（确定性——同输入同输出） ----
        return candidates.map { c ->
            when {
                c.chatId in promoted -> TerminalVerdict(c.chatId, CelestialKind.BLACK_HOLE, bhGate[c.chatId]!!)
                c.chatId == quarkId -> TerminalVerdict(c.chatId, CelestialKind.QUARK_STAR, TerminalReason.QUARK_STAR)
                c.chatId in neutronIds -> TerminalVerdict(c.chatId, CelestialKind.NEUTRON_STAR, TerminalReason.NEUTRON_STAR)
                c.chatId in bhGate -> TerminalVerdict(c.chatId, null, TerminalReason.BLACK_HOLE_CAPPED)
                c.chatId in bhSilent -> TerminalVerdict(c.chatId, null, TerminalReason.SILENT_GIANT)
                else -> TerminalVerdict(c.chatId, null, TerminalReason.NOT_TERMINAL)
            }
        }
    }

    // ==================== 类星体·黑洞活动态修饰（R317 S2——不改枚举） ====================
    //
    // r315 H 段关键设计决策：类星体=正在吸积的超大质量黑洞（天文上的子集关系，
    // 非并列天体）——**不改 CelestialKind**，黑洞 kind 上的活动度修饰态。
    // 「正在坍缩」分支（黑洞宣判矩阵）与「类星体相」（修饰档）共用同一盛宴门
    // [ACCRETION_FEAST_RECENT7]：一名两用=同一事件（吸积盛宴）。

    /**
     * 吸积活动度 accretion01（黑洞修饰态·0..1 归一）：recent7/[RECENT7_SATURATION]
     * 归一（与光谱型同源饱和口径——纪律§一单一出处）；recent7<0（缺据）=0 静默档
     * （**诚实红线**：缺据不冒充类星体相）。消费方：渲染进食档参数（批 1 wiring）。
     */
    fun accretion01Of(recent7: Int): Float =
        if (recent7 < 0) 0f else (recent7.toFloat() / RECENT7_SATURATION).coerceIn(0f, 1f)

    /**
     * 类星体相判定（黑洞的活动修饰档）：黑洞体量门 ∧ recent7≥[ACCRETION_FEAST_RECENT7]
     * （吸积盛宴期）。调用方先过黑洞门（终态宣判或既有晋升），本函数只判活动相位
     * ——静默黑洞（accretion 低）=现状静默视界，零行为漂移。
     */
    fun isQuasarPhase(recent7: Int): Boolean = recent7 >= ACCRETION_FEAST_RECENT7

    // ==================== 白洞判据（R317 S2——收割零回馈门） ====================
    //
    // 用户裁决口径（79c594f）：白洞=「只发不收的 bot/采集频道」——对你纯单向供血的
    // 频道型源泉：你从它收割了大量内容，却从未向它回馈过任何互动。与脉冲星分工：
    // 脉冲星=「自动应答的服务性」（bot 实体档不动）；白洞=「被收割零回馈的源泉」。

    /** 白洞收割门：累计观看 ≥3h（「它持续供血」） */
    const val WHITE_HOLE_MIN_WATCH_SEC = 3L * 3600L

    /** 白洞收割门等效档：看过条数 ≥30（watchSec 缺失时的替代证据） */
    const val WHITE_HOLE_MIN_WATCHED = 30

    /** 白洞晋升上限（颗）：与黑洞对称——星系奇观稀有性 */
    const val MAX_WHITE_HOLES = 4

    /**
     * 白洞候选（装配口收集——字段全 MapStarRaw 既有）：
     * 互动三账哨兵=-1=无互动账（旧库缺据——**不宣判**，绝不冒充零回馈）。
     */
    class WhiteholeEvidence(
        val chatId: Long,
        /** 累计观看秒数（watch_events 全量） */
        val watchSec: Long,
        /** 条目级看过条数（history.json 窗口口径） */
        val watchedCount: Int,
        /** 金矿档位（1=T1 系统验货=爬虫收割重仓；-1=无档） */
        val goldTier: Int,
        /** 点赞条数（-1=互动账缺据） */
        val likeCount: Int,
        /** 收藏条数（-1=互动账缺据） */
        val favCount: Int,
        /** 弹幕条数（-1=互动账缺据） */
        val danmakuCount: Int,
        /** 实体档（null=频道型——bot 仍归脉冲星，白洞只判频道） */
        val entityKind: CelestialKind?,
    )

    /**
     * 白洞宣判（收割零回馈门，纯函数）：
     *  - **高收割门**（任一）：watchSec≥[WHITE_HOLE_MIN_WATCH_SEC] ∨ watchedCount≥
     *    [WHITE_HOLE_MIN_WATCHED] ∨ goldTier==1（T1 爬虫收割重仓）；
     *  - **零回馈门**（合取）：like==fav==dan==0 ∧ 三账**齐全**（任一 -1=缺据不判
     *    ——互动账缺失的旧库频道绝不冒充「零回馈」）；订阅不算回馈（单向关注）；
     *  - **频道型**：entityKind==null（bot 实体档归脉冲星，PULSAR 判据不动）；
     *  - top≤[MAX_WHITE_HOLES]（收割量 watchSec 降序+chatId 破平——确定性）。
     * 返回宣判 chatId 列表（收割量降序）。
     */
    fun judgeWhiteHoles(candidates: List<WhiteholeEvidence>): List<Long> {
        if (candidates.isEmpty()) return emptyList()
        val passed = ArrayList<WhiteholeEvidence>()
        for (c in candidates) {
            if (c.entityKind != null) continue // 频道型门（bot/群组/私聊不判）
            if (c.likeCount < 0 || c.favCount < 0 || c.danmakuCount < 0) continue // 互动账缺据=不判
            if (c.likeCount != 0 || c.favCount != 0 || c.danmakuCount != 0) continue // 有任一回馈绝不判
            val harvested = c.watchSec >= WHITE_HOLE_MIN_WATCH_SEC ||
                c.watchedCount >= WHITE_HOLE_MIN_WATCHED ||
                c.goldTier == 1
            if (harvested) passed += c
        }
        return passed
            .sortedWith(compareByDescending<WhiteholeEvidence> { it.watchSec }.thenBy { it.chatId })
            .take(MAX_WHITE_HOLES)
            .map { it.chatId }
    }

    // ==================== R326 诞生侧判据族（S1'——与终态判据族同文件=生死判据单一出处） ====================
    //
    // r325 病根：终态侧每个态由数据事件宣判（怎么死的可讲前史），诞生侧每个态都是
    // 「顺带渲染」（怎么生的无凭无据）——本段把诞生侧升格为判据：与终态侧共享「形」
    // （单文件矩阵+合取门+缺据回落+池选帽+确定性纯函数），各取本侧的诚实真源（死亡
    // =内容事件史→发布直方图；诞生=采集/发现状态→爬虫档位+金矿账+tag 账，全本地
    // O(1) 零 IO——「对称的形，非对称的源」）。四态优先级：终态>诞生（③年轻位>
    // ②吸积>①星云候选——诞生链位「更晚」者胜）；planetsForming 只作修饰位。宣判
    // 接线（StarNode.birth 注记进星图）归批 B（R327）；呈现归批 3/批 5/批 C。

    /** 诞生史点查池上限（镜像 [TERMINAL_POOL_CAP] 姿势——池内小频道毫秒级点查的有界预算口） */
    const val BIRTH_POOL_CAP = 30

    /**
     * 死亡侧档位（**终态>诞生互斥门**——「死透的星不坐月子」r325 优先级纪律）：终态
     * 宣判四档（黑洞/中子星/夸克星/白洞）+六态死亡档（白矮星）。死亡侧频道不出任何
     * 诞生态注记（planetsForming 同步压制）。R327 migrateLifeVerdicts 的互斥剔除
     * 消费同一口（互斥规则单点——纪律§一：终态>诞生不许两处各写一份）。
     */
    fun isDeathSideKind(kind: CelestialKind): Boolean = when (kind) {
        CelestialKind.BLACK_HOLE, CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR,
        CelestialKind.WHITE_HOLE, CelestialKind.WHITE_DWARF -> true
        else -> false
    }

    /**
     * 金矿证据单口（S2'——星级位「原料在场」判定）：goldItems>0=金矿已收录未收割
     * （r303 本体论口径：金矿候选=新星原料）。**域界声明**：本口只判星级位；批 5
     * 发射星云的团级门（成员星云候选占比≥50%）组合本口消费——同一数据两消费零
     * 重复实现（星级位归本件，团级门归批 5 侧组合）。
     */
    fun goldEvidenceOf(goldItems: Int): Boolean = goldItems > 0

    /**
     * 探针进度归一（0..1——[accretion01Of] 的诞生侧镜像单口）：已聚合条数 ÷ 升档线
     * （[IndexTierPolicy.CAP_T2_DEFAULT] 单一出处引用——T2 条数上限 2000=升 T1 的
     * 线，「内容够好升深索引=核心点燃」）。缺据/零聚合=0 静默档（**诚实红线**：没
     * 有聚条不冒充聚合进度）。消费方：吸积行「升档线 P%」（R327）+聚合动画进度
     * 亮度 [TasteBirthFx.accretionCoreK]（批 C）。
     */
    fun probeProgress01(probeItems: Int): Float =
        if (probeItems <= 0) 0f
        else (probeItems.toFloat() / IndexTierPolicy.CAP_T2_DEFAULT).coerceIn(0f, 1f)

    /**
     * 诞生宣判候选（R327 半场收集——字段全本地既有面，零新 IO）：
     *  - kind/firstSeenMs/watchSec/subscribed：fresh.stars 既有（③对位+①②雾团口径）；
     *  - goldItems：assembly.raws 既有（金矿快照 6h TTL）；
     *  - persistedTier：[DeepSearchIndex.tiers] 批量读（毫秒级单查；行缺失/查询
     *    失败→-1=LEGACY——诚实缺据）；
     *  - tieringEnabled：vm.indexTiering 开关（false=恒 LEGACY→②不判——诞生侧
     *    最大的诚实约束：用户不开智能分档，链中段永远缺；结构上过
     *    [CrawlThroughputPolicy.effectiveTier] 单口不可绕）；
     *  - probeItems：本地索引已聚合条数（②吸积行「已聚 N 条」+进度亮度原料）；
     *  - ledgerTags：tagFingerprints[chatId] 有料 tag 数（④行星成型门——R303c P1
     *    装配期已收集，零新 IO）。
     */
    class BirthEvidence(
        /** 频道 id（破平与诊断用） */
        val chatId: Long,
        /** 累计观看秒数（watchSec==0=从未看过——①②未探索门第一支，R235 雾团同口径） */
        val watchSec: Long,
        /** 是否订阅（订阅=已探索——①②门第二支，R235 雾团同口径） */
        val subscribed: Boolean,
        /** 现役天体档（六态/实体分档产出——③只对位不重判；终态档位走 [isDeathSideKind] 互斥门） */
        val kind: CelestialKind,
        /** 频道首见毫秒（0=无证据——池选新度序原料+③诚实红线双保险门） */
        val firstSeenMs: Long,
        /** 金矿已收录条数（0=快照缺失/未收录——①缺据不判） */
        val goldItems: Int,
        /** 落库索引档位（meta.tier 原值；-1=LEGACY/无行——经 effectiveTier 单口过闸） */
        val persistedTier: Int,
        /** 智能分档开关（false=恒 LEGACY=②缺据——r325 待用户过目清单①） */
        val tieringEnabled: Boolean,
        /** 本地索引已聚合条数（②原料——[probeProgress01] 分子） */
        val probeItems: Int,
        /** tag 账本有料 tag 数（0=账本空/回填窗——④缺据不判） */
        val ledgerTags: Int,
    )

    /** 诞生态（镜像 [TerminalReason]——诊断口径，探针/宣判 diff 报告消费） */
    enum class BirthStage {
        /** ① 星云候选：原料在场（未探索∧金矿已收录未收割）——呈现归批 5 发射星云（团级门消费 [goldEvidenceOf]） */
        NEBULA_CANDIDATE,
        /** ② 吸积盘中：小样探测中/开始爬（未探索∧有效档位 T2——爬虫正在聚合） */
        ACCRETING,
        /** ③ 原恒星年轻位：六态 PROTOSTAR 对位（首见<90 天——不重判，点燃演出资格+诞生史行原料） */
        PROTOSTAR_YOUNG,
        /** 无诞生态注记（四门全不过/缺据/终态已判——现状原样） */
        NOT_BIRTH,
    }

    /**
     * 诞生注记（[judgeBirthStates] 出口——**StarNode.birth 注记载体**，缓存 v5 载荷
     * 原料——R327；data class=确定性单测等值锚+换装深等/diff 检出面）。
     */
    data class BirthFeatures(
        val chatId: Long,
        /** 诞生态（NOT_BIRTH=无注记——死星/四门全不过/缺据） */
        val stage: BirthStage,
        /** 行星成型修饰位（叠加在②③上——独立不立；终态侧恒 false） */
        val planetsForming: Boolean,
        /** 诞生时刻毫秒（0=无首见证据——birthPoolOf 首见新度序原料；镜像 firstSeenMs 采集面） */
        val firstSeenMs: Long,
        /** T2 已聚合条数（吸积行「已聚 N 条」原料——仅②态非零；本地 items 口径不依赖池点查） */
        val probeItems: Int,
        /** 有料 tag 数（min(账本数, [TasteTagPlanets.MAX_PLANETS]=24)——仅成型态非零；
         *  文案「K 个话题绕行中」与轨道系统实际绕行数同一上限不虚报——单一出处引用） */
        val tagsCount: Int,
        /** 诞生史增强位（birthPoolOf 池内 chatPublishHistogram 点查回填——R327；
         *  缺据 null=诞生史行整行不出，绝不编造） */
        val hist: PublishFeatures? = null,
    )

    /**
     * 诞生宣判矩阵（S1' 核心——四态四门**全本地 O(1) 零 IO**；输入序=输出序确定性）：
     *  - **③ 原恒星年轻位**：kind==PROTOSTAR（六态判据**对位不重判**——首见<90 天在
     *    [TasteGalaxyClassifier.classifyChannel] 已判）∧ firstSeenMs>0（诚实红线
     *    双保险：零证据不冒充年轻）；
     *  - **② 吸积盘中**：未探索（watchSec==0∧!subscribed——R235 雾团同口径）∧
     *    有效档位==T2（[CrawlThroughputPolicy.effectiveTier] 单口过闸：分档关恒
     *    LEGACY→**不判**——诞生侧最大诚实约束）；
     *  - **① 星云候选**：未探索（同上）∧ [goldEvidenceOf]（金矿已收录未收割=原料
     *    在场；快照缺失恒普通雾现状原样，绝不冒充摇篮）；
     *  - **④ 行星成型修饰位**：年轻（kind==PROTOSTAR ∨ ②态）∧ ledgerTags≥1——
     *    叠加在②③上独立不立（tag 账本空→不判，整行不出）；
     *  - **终态>诞生互斥**：[isDeathSideKind]→NOT_BIRTH 且 planetsForming 压制。
     * **诚实红线**（缺据反例单测锚）：金矿缺→不判①；分档关/tiers 缺行→不判②；
     * tag 账本空→不判④；firstSeenMs==0→不判③；demo/新装（索引缺席全零）→四态
     * 全零=宇宙年轻（镜像 R317b demo 诚实回落，不做特判）。
     */
    fun judgeBirthStates(candidates: List<BirthEvidence>): List<BirthFeatures> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.map { c ->
            val stage: BirthStage
            val planetsForming: Boolean
            if (isDeathSideKind(c.kind)) {
                // 终态>诞生：死亡盖过诞生——死透的星不坐月子（修饰位同步压制）
                stage = BirthStage.NOT_BIRTH
                planetsForming = false
            } else {
                val unexplored = c.watchSec == 0L && !c.subscribed
                val t2 = CrawlThroughputPolicy.effectiveTier(c.persistedTier, c.tieringEnabled) ==
                    IndexTierPolicy.IndexTier.T2
                stage = when {
                    c.kind == CelestialKind.PROTOSTAR && c.firstSeenMs > 0L -> BirthStage.PROTOSTAR_YOUNG
                    unexplored && t2 -> BirthStage.ACCRETING
                    unexplored && goldEvidenceOf(c.goldItems) -> BirthStage.NEBULA_CANDIDATE
                    else -> BirthStage.NOT_BIRTH
                }
                // ④ 修饰位：年轻（③对位 ∨ ②吸积中）∧ tag 账本非空（缺账不冒充边角料）
                planetsForming = (c.kind == CelestialKind.PROTOSTAR || stage == BirthStage.ACCRETING) &&
                    c.ledgerTags > 0
            }
            BirthFeatures(
                chatId = c.chatId,
                stage = stage,
                planetsForming = planetsForming,
                firstSeenMs = c.firstSeenMs,
                probeItems = if (stage == BirthStage.ACCRETING) c.probeItems.coerceAtLeast(0) else 0,
                tagsCount = if (planetsForming) minOf(c.ledgerTags, TasteTagPlanets.MAX_PLANETS) else 0,
                hist = null, // 诞生史增强位：池内点查回填（R327 wiring）——缺据 null 整行不出
            )
        }
    }

    /**
     * 诞生史点查候选池（**先池选后点查**——镜像 [terminalPoolOf] 姿势）：仅
     * stage!=NOT_BIRTH（注记在场）者按**首见新度降序**（年轻频道先入池——诞生史
     * 「诞生 K 天」对越年轻的星越有读数价值）+chatId 破平截断 ≤[BIRTH_POOL_CAP]。
     * 池内成员由 R327 半场补 chatPublishHistogram（诞生池全是小频道——T2≤capT2，
     * 单颗毫秒~十几毫秒，30 颗合计百 ms 量级——r325 A① 三层复用方案第二层）。
     * 纯函数确定性（同输入同输出）。
     */
    fun birthPoolOf(features: List<BirthFeatures>): List<BirthFeatures> =
        features.asSequence()
            .filter { it.stage != BirthStage.NOT_BIRTH }
            .sortedWith(compareByDescending<BirthFeatures> { it.firstSeenMs }.thenBy { it.chatId })
            .take(BIRTH_POOL_CAP)
            .toList()

    // ==================== 开普勒三定律（任务书 ②） ====================

    /**
     * 开普勒第三定律角速度：ω = C/(a·√a)（T² ∝ a³ 的导数形——**近轨道快远轨道
     * 慢**）。[a]=半长轴（任意一致量纲），[c]=场景常数（调用方按期望周期定）。
     */
    fun keplerOmega(a: Float, c: Float): Float = c / (a * sqrt(a.coerceAtLeast(1e-4f)))

    /**
     * 开普勒方程求解（第二定律的发动机）：M = E − e·sinE，牛顿迭代 3 轮
     * （e≤0.3 时残差 <1e-5——行星偏心率域足够）；E 初值=M（小 e 收敛快）。
     */
    fun solveKeplerE(meanAnomaly: Float, ecc: Float): Float {
        var e = meanAnomaly
        repeat(3) {
            val f = e - ecc * sin(e) - meanAnomaly
            val fp = (1f - ecc * cos(e)).coerceAtLeast(0.05f)
            e -= f / fp
        }
        return e
    }

    /**
     * 真近点角 ν（第二定律→角位置的出口）：E→ν=2·atan2(√(1+e)·sin(E/2),
     * √(1−e)·cos(E/2))。与 [orbitRadius] 配对=行星在椭圆上的位置（近日点快、
     * 远日点慢——等面积定律的字面落地）。
     */
    fun keplerTrueAnomaly(meanAnomaly: Float, ecc: Float): Float {
        val e = solveKeplerE(meanAnomaly, ecc)
        return 2f * atan2(sqrt(1f + ecc) * sin(e / 2f), sqrt((1f - ecc).coerceAtLeast(1e-4f)) * cos(e / 2f))
    }

    /**
     * 开普勒第一定律轨道半径（**恒星在焦点的严格椭圆**）：
     * r(ν)=a(1−e²)/(1+e·cosν)——ν=±π（远日点）=a(1+e)，ν=0（近日点）=a(1−e)。
     */
    fun orbitRadius(a: Float, ecc: Float, trueAnomaly: Float): Float {
        val oneMinusE2 = (1f - ecc * ecc).coerceAtLeast(1e-4f)
        return a * oneMinusE2 / (1f + ecc * cos(trueAnomaly)).coerceAtLeast(1e-4f)
    }

    // ==================== 星系微运动（5s 低频有界漂移） ====================

    /** 微轨角速度常数：ω=C/(a√a)——a=600（中壳典型距）时周期约 17 分钟 */
    private const val DRIFT_C = 90f

    /** 微轨幅度（世界单位）：A=4+0.012·a，钳 [4,22]——「幅度极小不破坏导航记忆」 */
    fun driftAmpOf(a: Float): Float = (4f + 0.012f * a).coerceIn(4f, 22f)

    /** 微轨角速度（开普勒第三定律）：距主导井越近摆越快；钳 [0.002, 0.06] rad/s */
    fun driftOmegaOf(a: Float): Float = keplerOmega(a.coerceAtLeast(30f), DRIFT_C).coerceIn(0.002f, 0.06f)

    /**
     * 引力吸引趋势（漂移中心向井偏移的幅度）：pull=0.03·a·(0.4+0.6·attractorMass01)
     * 钳 [2,24]——「大频道周围的星会被吸引（向它靠拢的趋势）」的有界化（全引力
     * 坍缩会拉碎口味簇，任务书「幅度极小」条款的工程落法）。
     */
    fun attractorPullOf(a: Float, attractorMass01: Float): Float =
        (a * 0.03f * (0.4f + 0.6f * attractorMass01)).coerceIn(2f, 24f)

    /** 双星微轨角速度常数：d=400（典型星间距）时周期约 5 分钟 */
    private const val BINARY_C = 160f

    /** 双星摆动幅度：0.06·d 钳 [5,30]（两星反相——分离度呼吸式变化） */
    fun binaryAmpOf(d: Float): Float = (0.06f * d).coerceIn(5f, 30f)

    /** 双星绕转角速度（开普勒第三定律同式——两颗星互相绕转） */
    fun binaryOmegaOf(d: Float): Float = keplerOmega(d.coerceAtLeast(40f), BINARY_C).coerceIn(0.002f, 0.08f)

    // ==================== 引力透镜（渲染期屏幕空间弯曲） ====================

    /** 透镜影响域（px）：井视半径×7.5，钳 [70,430]——域外零成本早退 */
    fun lensInfluenceOf(wellRpx: Float): Float = (wellRpx * 7.5f).coerceIn(70f, 430f)

    /** 透镜偏移幅度上限（px）：井心保护（≤0.22·d 绝不让星越过井）与绝对上限 */
    private const val LENS_MAX_PX = 46f

    /**
     * 引力透镜屏幕偏移（**渲染期查表弯曲**——任务书「rendering 时对黑洞附近星的
     * 投影位置加偏移」的字面口径）：写 [out]（长 ≥2：dx/dy 偏移量），返回 false=
     * 星在影响域外（零成本早退路径）。
     *  - [dxPx]/[dyPx]=星−井的屏幕向量；[wellRpx]=井的投影视半径（近井强远井弱
     *    的自然衰减）；[mass01]=井质量（黑洞 1.0/巨星 0.35）；[behindFactor]=深度
     *    比因子（井后方的星弯曲更强，钳 0.5~1.5 由调用方算）；[swirl]=黑洞旋涡
     *    （径向+切向混合偏移=吸积旋臂观感；巨星纯径向）。
     *  - mag=1.3·mass01·wellR²/(d+0.35·wellR)（1/d 衰减≈爱因斯坦环 θ∝1/b 量级）
     *    ×behindFactor，钳 min(0.22·d, [LENS_MAX_PX])。
     */
    fun lensOffset(
        dxPx: Float,
        dyPx: Float,
        wellRpx: Float,
        mass01: Float,
        behindFactor: Float,
        swirl: Boolean,
        out: FloatArray,
    ): Boolean {
        val d = sqrt(dxPx * dxPx + dyPx * dyPx)
        if (d < 1f || d > lensInfluenceOf(wellRpx)) return false
        var mag = 1.3f * mass01 * wellRpx * wellRpx / (d + 0.35f * wellRpx) * behindFactor
        mag = mag.coerceAtMost(minOf(0.22f * d, LENS_MAX_PX))
        var ox = -dxPx / d // 指向井（径向）
        var oy = -dyPx / d
        if (swirl) {
            // 旋涡：径向 0.55 + 切向 0.8（归一）——星被拉向井同时绕井转=旋臂观感
            val tx = -oy
            val ty = ox
            var bx = 0.55f * ox + 0.8f * tx
            var by = 0.55f * oy + 0.8f * ty
            val l = sqrt(bx * bx + by * by)
            if (l > 1e-6f) { bx /= l; by /= l }
            ox = bx
            oy = by
        }
        out[0] = ox * mag
        out[1] = oy * mag
        return true
    }

    // ==================== 天体科普（信息卡彩蛋行，任务书 ③） ====================

    /**
     * 天体科普文案（**单一出处**——信息卡「天体档案」段后加行；每类天体一条
     * 「天文知识×频道数据」融合文案，任务书示例口径：数据+天文类比）：
     *  - [items]=条数存量；[recent7]/[prev7]=近两周发布（-1=无金矿证据）；
     *  - [lastActivityDays]/[firstSeenDays]=-1=未知（缺据字段不编数字，走诚实回落）；
     *  - [meanItems]=全库均值（0=未知——黑洞倍数行缺据降级）；[partnerTitle]=双星
     *    伴侣频道名（null=无双星）；
     *  - [hist]=R317b 终态宣判注入的发布直方图特征（null=未宣判/缺据——黑洞
     *    前史行不出，绝不编造）；类星体相行由 recent7 直判（[isQuasarPhase]）
     *    ——不需要 hist（盛宴门=近期证据非历史证据）；
     *  - [birth]=R327 诞生宣判注入的诞生态注记（null=未宣判/缺据——诞生四行
     *    全不出，绝不编造）：吸积行（②态——probeItems 本地口径+升档线 P%）/
     *    诞生史行（池内点查回填的 hist 缺据整行不出——节奏读法走断更口径
     *    [PublishFeatures.deadDays]） /行星成型行（修饰位——tagsCount 帽
     *    [TasteTagPlanets.MAX_PLANETS] 与轨道系统同上限）/循环行（星云候选
     *    ∧[nearDeathLabel] 空间共在——克制位，共在不成立整行不出）；
     *  - [nearDeathLabel]=R327 循环行原料：近旁终态星的天体名（调用方 sp 最近
     *    邻一次查询产出；null=无终态星共在）；
     *  - [partnerSharedW]/[partnerTrojanW]=R321 双星段增强行原料（0=缺据整行不
     *    出，绝不编造）：同源证据 W（潮汐预警行——调用方经
     *    [RocheTidalFx.warningSharedW] 撞车档门筛过的 top 对值）/共源上游 W
     *    （特洛伊群行——[TrojanFx.sharedUpstreamWeight] 交集语义）。
     */
    fun factLine(
        kind: CelestialKind,
        items: Int,
        recent7: Int,
        prev7: Int,
        lastActivityDays: Long,
        firstSeenDays: Long,
        meanItems: Float,
        partnerTitle: String?,
        partnerWeight: Int,
        hist: PublishFeatures? = null,
        birth: BirthFeatures? = null,
        nearDeathLabel: String? = null,
        partnerSharedW: Int = 0,
        partnerTrojanW: Int = 0,
    ): String = buildString {
        when (kind) {
            CelestialKind.STAR -> {
                val cls = spectralClassOf(recent7, lastActivityDays)
                append("☀ 恒星：主序星靠氢核聚变稳定发光，一生大部分时间都停留在这个阶段——")
                if (recent7 >= 0) {
                    append("这颗${cls.label}恒星本周发布 $recent7 条")
                } else if (lastActivityDays >= 0) {
                    append("这颗${cls.label}恒星已 $lastActivityDays 天没有新的辐射")
                } else {
                    append("这颗${cls.label}恒星按本地索引持续观测中")
                }
            }
            CelestialKind.PROTOSTAR -> {
                append("✧ 原恒星：吸积盘中的恒星胚胎，积攒够质量就会点燃核心进入主序——")
                if (firstSeenDays >= 0) {
                    append("这个频道诞生 $firstSeenDays 天，还在吸积")
                } else {
                    append("首帖尚未被索引到，诞生时间未知")
                }
            }
            CelestialKind.RED_GIANT -> {
                append("🟠 红巨星：核心氢耗尽，外层膨胀数百倍、表面冷却变红——")
                append(if (items > 0) "存量 ${fmtItems(items)}" else "存量可观")
                if (lastActivityDays >= 60) append("，但 ${lastActivityDays / 30} 个月没更新了")
            }
            CelestialKind.WHITE_DWARF -> {
                append("⚪ 白矮星：燃料耗尽后坍缩成的致密残骸，靠余热还能发光上百亿年——")
                if (lastActivityDays >= 0) append("这个频道已停更 $lastActivityDays 天")
                else append("这个频道早已沉寂")
                if (items > 0) append("，曾有 ${fmtItems(items)}")
            }
            CelestialKind.SUPERNOVA -> {
                append("☄ 超新星：恒星爆发，亮度可以短暂超过整个星系——")
                if (recent7 >= 3) {
                    append("本周更新 $recent7 条")
                    if (prev7 > 0) append("，是上周的 ${"%.1f".format(recent7.toFloat() / prev7)} 倍")
                    else if (prev7 == 0) append("，而上周一条都没有")
                } else {
                    append("这颗星正在爆发，本地索引马上会记下它的光")
                }
            }
            CelestialKind.PULSAR -> {
                append("📡 脉冲星：高速自转的中子星，射电束像灯塔一样周期性扫过地球——")
                append("这是个 bot，每条自动消息都是它的脉冲")
            }
            CelestialKind.CLUSTER -> {
                append("🌌 星团：被引力束缚的恒星群，绕共同质心缓慢旋转——")
                append("这个群组的成员与消息构成了它的光云")
            }
            CelestialKind.METEOR -> {
                append("💫 流星：闯入星系的过客，不被任何引力井捕获——")
                append("这是你与真人的私聊，不属于内容星系")
            }
            CelestialKind.BLACK_HOLE -> {
                append("⚫ 黑洞：质量超过奥本海默极限后坍缩，连光也无法逃逸——")
                if (items > 0) {
                    append(fmtItems(items))
                    if (meanItems > 0f) {
                        append("，是全库平均的 ${"%.1f".format(items / meanItems)} 倍")
                    } else {
                        append("，是星系里最重的天体")
                    }
                }
                // ---- R317b 类星体相行（黑洞活动态修饰——accretion 高=吸积盛宴
                //      中的黑洞=类星体；盛宴门 [isQuasarPhase]=近期证据直判，
                //      缺据（recent7<0）恒不出） ----
                if (isQuasarPhase(recent7)) {
                    append(" · ✴ 类星体相：吸积盛宴中（近 7 天 $recent7 条 ≥$ACCRETION_FEAST_RECENT7 条盛宴门）")
                    append("——吸积盘的亮度正盖过整个星系")
                }
                // ---- R317b 前史行（宣判注入的直方图特征——「它曾经是谁」；
                //      缺据（hist=null 未宣判/点查失败）整行不出，绝不编造） ----
                if (hist != null) {
                    append(" · 前史：爆发巅峰 30 天 ${hist.burstPeakCount} 条")
                    append(" · 活跃寿命 ${hist.lifetimeDays} 天")
                    if (hist.deadDays > 0) append(" · 停更至今 ${hist.deadDays} 天")
                }
            }
            CelestialKind.NEUTRON_STAR -> {
                append("🌀 中子星：大质量恒星燃尽爆发后坍缩成的致密核心，一勺物质就有上亿吨——")
                if (items > 0) append("它曾有 ${fmtItems(items)} 的体量")
                if (lastActivityDays >= 0) {
                    append(if (items > 0) "，爆发骤停至今 " else "爆发骤停至今 ")
                    append("$lastActivityDays 天")
                }
            }
            CelestialKind.QUARK_STAR -> {
                append("❖ 夸克星：密度高到连中子都被压碎的极端中间态，人类至今没有确认过一颗——")
                if (items > 0) append("全库爆发烈度最极端的存在（${fmtItems(items)}）")
                if (lastActivityDays >= 0) append("，已沉寂 $lastActivityDays 天")
            }
            CelestialKind.WHITE_HOLE -> {
                append("⛲ 白洞：黑洞的时间反演解，只出不进——你从它收割了大量内容，")
                append("却从未回馈过一次互动（订阅不算回馈，那是单向关注）")
                if (items > 0) append("；它至今仍在供血（${fmtItems(items)}）")
            }
        }
        // ---- R327 诞生四行（r325 D①——缺据整行不出：birth=null=未宣判/缺据四行
        //      全零，与 hist 参同模式 API 只加不改） ----
        if (birth != null) {
            // 吸积行（②态——本地 items 口径，不依赖池点查；分档关照不出=判据门先拦）
            if (birth.stage == BirthStage.ACCRETING) {
                append(" · 它正在诞生：小样探测中（已聚 ${birth.probeItems} 条·升档线 ")
                append("${(probeProgress01(birth.probeItems) * 100).toInt()}%）——尘埃正落向核心")
            }
            // 诞生史行（池内点查回填的 hist——缺据整行不出，绝不编造；节奏读法走
            // 发布史断更口径（deadDays<7=本周仍有新料——publishHistogramFeatures
            // ALIVE 门同源），「早期密度乘数」无字段不冒充）
            val birthHist = birth.hist
            if (birthHist != null) {
                append(" · ✧ 诞生史：")
                if (firstSeenDays >= 0) append("诞生 $firstSeenDays 天 · ")
                append("发布 ${birthHist.items} 条")
                if (birthHist.deadDays in 0..6) {
                    append(" · 聚得正快（本周仍有新料）")
                } else if (birthHist.deadDays > 6) {
                    append(" · 已放缓（${birthHist.deadDays} 天无新料）")
                }
            }
            // 行星成型行（修饰位——叠加②③；tagsCount 与轨道系统同上限不虚报）
            if (birth.planetsForming) {
                append(" · 它的行星正在成型：${birth.tagsCount} 个话题绕行中（吸积盘的边角料正聚成行星）")
            }
            // 循环行（星云候选∧近旁终态星空间共在——克制位：零数据编造，因果是
            // 呈现语言（引力透镜同纪律）；共在不成立=整行不出）
            if (birth.stage == BirthStage.NEBULA_CANDIDATE && !nearDeathLabel.isNullOrBlank()) {
                append(" · 🌫 它躺在${nearDeathLabel}的余烬旁——上一代恒星的物质正养育新星的摇篮")
            }
        }
        if (partnerTitle != null && partnerTitle.isNotBlank()) {
            append(" · ☯ 双星系统：两颗星互相绕转（开普勒第三定律——离得越近绕得越快）")
            append("，与「$partnerTitle」有 $partnerWeight 条关联证据")
            // ---- R321 洛希潮汐预警行（r315-D③/r331 预清单：重复撞车对（同源分
            //      top——调用方 warningSharedW 门筛）的双星段行；缺据/非 top=0
            //      整行不出，绝不编造） ----
            if (partnerSharedW > 0) {
                append(" · ⚠ 潮汐撕裂：两频道内容高度同源，正互相撕扯但永不合并——同源证据 $partnerSharedW 条")
            }
            // ---- R321 特洛伊群行（r315-I③/r331 预清单：共源上游在平衡点驻留；
            //      无共源=0 整行不出——不冒充驿站） ----
            if (partnerTrojanW > 0) {
                append(" · ☾ 特洛伊群：两频道共同的上游在平衡点驻留——共源证据 $partnerTrojanW 条")
            }
        }
    }

    /** 体量条数人性化（与 App 口径一致：≥1 万显示 x.x 万条）；
     *  R312：升 internal——AstroFacts 科普文案复用同一口径（纪律§一单一出处） */
    internal fun fmtItems(count: Int): String =
        if (count >= 10000) "%.1f 万条".format(count / 10000f) else "$count 条"
}
