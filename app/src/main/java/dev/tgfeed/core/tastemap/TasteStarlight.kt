package dev.tgfeed.core.tastemap

import java.io.File
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * R302 星辉等级（design-rpg-loop.md 终审稿落地——RPG 成长循环的单成长轴，
 * **纯 Kotlin 零 Android 依赖**，JVM 单测可跑；TasteDiscoveryLog/TasteVoyageLog
 * 同款账本纪律）。
 *
 * 用户愿景（终审稿 §2.1）：**探索行为是唯一的成长货币**——点亮/首踏星域/虫洞/
 * 目击宇宙事件攒星辉 → 升级 → 解锁**体验型能力**（看得更远/更酷飞船/新交互）→
 * 新能力让探索更远。与 R235 等阶双轨：等阶=身份（被动累计，结构零触碰），
 * 星辉=成长（行为咬合——本件）。
 *
 * **重算即迁移**（TasteAchievements「同输入同输出」同哲学）：账本不存等级、
 * 不存星辉总额——只存**点亮账之外**的增量语义（行星点亮/首踏星域格/目击事件/
 * 里程碑红包）；点亮数=读 TasteDiscoveryLog 账本、虫洞数=读 TasteVoyageLog
 * 账本（各账不动，星辉只读——终审稿 §7「星辉账本=成长语义总线」）。
 * [loadProgress] 三源重算=天然迁移：旧机升级、账本手删、口径调整，重算即新账。
 *
 * **数值地狱防火墙**（终审稿 §4 红线成文）：全系统只有三个数——星辉总额
 * （一根轴）/目击图鉴（种类×次数）/解锁表（8 级查表）。无体力/无日常/无签到/
 * 无排行榜/无赛季/无抽卡/无随机奖励——一切成长可预测（纯函数查表）。
 *
 * 账本文件=galaxy/starlight.json（ui 侧 File(filesDir/galaxy, [FILE_NAME])——
 * 星图自持账本不进 LocalStore 共享层）；手写 JSON（org.json 在 JVM 单测不可用）；
 * 原子写（临时文件+rename）；父目录自建；损坏/缺失=空账诚实缺省（等级回 Lv1
 * 可容忍——行为账主体在点亮/虫洞两老账，飞一飞即回，绝不抛）。
 */
object TasteStarlight {

    /** 账本文件名（ui 侧 File(filesDir/galaxy, 此名)） */
    const val FILE_NAME = "starlight.json"

    // ==================== 行为星辉表（终审稿 §2.1 原文数字，单一出处） ====================

    /** 点亮恒星（首次飞近点亮） */
    const val AWARD_LIGHT = 10

    /** 点亮行星（首次飞近一颗 tag 行星到 FULL 阈） */
    const val AWARD_PLANET = 3

    /** 首踏星域（首次点亮某星云团格内任何恒星） */
    const val AWARD_NEBULA_FIRST = 25

    /** 虫洞跳跃（账本=TasteVoyageLog jump 计数——星辉只读） */
    const val AWARD_WORMHOLE = 5

    /** 目击宇宙事件（新星/超新星发火时相机在邻域；彗星=在场即目击——口径见下） */
    const val AWARD_WITNESS = 15

    /** 点亮里程碑达成红包（10/50/100/300——档表复用 TasteAchievements 常量单一出处） */
    const val AWARD_MILESTONE = 50

    /** 里程碑档（=R248 探索里程碑四档同一组数——点亮语义同一尺，不另立数字） */
    val MILESTONE_TIERS: IntArray = intArrayOf(
        TasteAchievements.EXPLORE_MILESTONE_10,
        TasteAchievements.EXPLORE_MILESTONE_50,
        TasteAchievements.EXPLORE_MILESTONE_100,
        TasteAchievements.EXPLORE_MILESTONE_300,
    )

    /**
     * 目击邻域判据（井深比口径——终审稿 §3「新星发火时相机在邻域（井深比<15)」）：
     * 新星/超新星的目击=发火瞬间相机对该星的井深比 < 此值；**彗星无位置锚**
     * （chatId=0 天幕横扫）=在场即目击（诚实口径——你抬头它正好划过）。
     */
    const val WITNESS_WELL_RATIO = 15f

    /** 星事件目击判定（纯函数——井深比单尺；[ratio]=galaxyWellRatio 同式计算值） */
    fun witnessedByWell(ratio: Float): Boolean = ratio < WITNESS_WELL_RATIO

    // ==================== 升级曲线（纯函数单一出处——终审稿 §2.1） ====================

    /**
     * 升级所需星辉（level → level+1）：`100 × level^1.5`（终审稿原文曲线）。
     * Lv1→2=100（点 10 颗星）、Lv9→10≈2700——「头两级一今晚，十级一两个月」。
     */
    fun needForLevel(level: Int): Long =
        (100.0 * level.coerceAtLeast(1).toDouble().pow(1.5)).roundToInt().toLong()

    /** 到达 [level] 的累计门槛（Σ need(1..level-1)——等级 1 恒 0） */
    fun cumNeed(level: Int): Long {
        var sum = 0L
        var n = 1
        while (n < level) {
            sum += needForLevel(n)
            n++
        }
        return sum
    }

    /** 星辉总额 → 等级（重算即迁移的落点——等级永远是总额的纯函数） */
    fun levelOf(total: Long): Int {
        var level = 1
        var acc = 0L
        while (true) {
            val next = acc + needForLevel(level)
            if (total < next) return level
            acc = next
            level++
        }
    }

    /** 升级进度（[into]/[need]=本级已攒/本级所需；[progress01] 进度环原料） */
    class Progress(
        val total: Long,
        val level: Int,
        val into: Long,
        val need: Long,
    ) {
        val progress01: Float
            get() = if (need <= 0L) 1f else (into.toFloat() / need.toFloat()).coerceIn(0f, 1f)

        /** 距升级差距比例 0..1（罗盘亮起判据=≤[COMPASS_GAP_01]） */
        val gap01: Float
            get() = 1f - progress01
    }

    /** 总额 → 升级进度（纯函数） */
    fun progressOf(total: Long): Progress {
        val level = levelOf(total)
        val into = total - cumNeed(level)
        return Progress(total, level, into, needForLevel(level))
    }

    // ==================== 解锁表（终审稿 §2.2——八级全是体验型能力） ====================

    /** 解锁件（数据驱动清单——UI/渲染/相机三侧同读一份；[id] 稳定键） */
    class Unlock(val level: Int, val id: String, val label: String, val desc: String)

    /** Lv2 星图罗盘：升级差 ≤此比例（20%）时屏缘 chevron 变亮+「再点亮 N 颗即升级」 */
    const val COMPASS_GAP_01 = 0.20f

    /** Lv5 远视：未探索星发现感渐显起点（世界单位——TasteNebula.DISCOVER_NEAR_START 的等级函数） */
    const val FARSIGHT_NEAR_START = 1200f

    /** 解锁表（终审稿 §2.2 八级——等级升序；只加不改） */
    val UNLOCKS: List<Unlock> = listOf(
        Unlock(2, "compass", "星图罗盘", "差一点升级时，屏缘星光为你指路"),
        Unlock(3, "warp_ripple", "跃迁波纹", "钻石等阶的跃迁波纹，先尝为快"),
        Unlock(4, "witness_atlas", "目击图鉴", "宇宙事件收集册开卷"),
        Unlock(5, "farsight", "远视", "未探索星在 1200 世界单位外即开始渐显"),
        Unlock(6, "warp_cruise", "曲速巡航", "长按星空白处=持续冲刺，任意手势停止"),
        Unlock(7, "porthole", "星图舷窗", "feed 里不跳页看见「这条内容在星图哪里」"),
        Unlock(8, "legend_coating", "传奇涂装", "星尘风暴，等级给你的预尝"),
    )

    /** [level] 已授予的解锁件（查表即得——无概率无随机） */
    fun unlocksAt(level: Int): List<Unlock> = UNLOCKS.filter { it.level <= level }

    /** 下一个未授予的解锁件（null=八级全通——等级继续涨，账继续记） */
    fun nextUnlock(level: Int): Unlock? = UNLOCKS.firstOrNull { it.level > level }

    /** [id] 是否已解锁（能力门禁单口——UI/渲染/手势同查） */
    fun isUnlocked(level: Int, id: String): Boolean =
        UNLOCKS.any { it.id == id && it.level <= level }

    /**
     * 飞船特效等级地板（终审稿 §2.2「预尝」：Lv3 跃迁波纹/Lv8 传奇涂装——与等阶
     * 档**取高不回收**：等阶五阶仍是身份天花板，等级给的是「先尝到」）。
     */
    fun shipStyleFloor(level: Int): TasteAchievements.ShipStyle = when {
        level >= 8 -> TasteAchievements.ShipStyle.RAINBOW_STORM
        level >= 3 -> TasteAchievements.ShipStyle.WARP_RIPPLE
        else -> TasteAchievements.ShipStyle.MICRO_TRAIL
    }

    /** 等阶档与等级地板取高（不回收：等阶高于地板=等阶档原样） */
    fun effectiveShipStyle(rankStyle: TasteAchievements.ShipStyle, level: Int): TasteAchievements.ShipStyle {
        val floor = shipStyleFloor(level)
        return if (floor.ordinal > rankStyle.ordinal) floor else rankStyle
    }

    /** 远视发现感起点（等级函数——Lv5 起 700→1200 世界单位，「看得更远」的字面落地） */
    fun discoverNearStartOf(level: Int): Float =
        if (level >= 5) FARSIGHT_NEAR_START else TasteNebula.DISCOVER_NEAR_START

    // ==================== 星域迷雾（终审稿 §2.3——「远方」的视觉叙事，绝不锁内容） ====================

    /** 雾界基准（世界单位——Lv1 已点亮质心外此距内无雾） */
    const val MIST_REACH_BASE = 800f

    /** 雾界每级展宽（世界单位） */
    const val MIST_REACH_PER_LEVEL = 400f

    /** 雾界上限（世界单位——有界：雾终究散得完，「远方是邀请不是收费墙」） */
    const val MIST_REACH_MAX = 6000f

    /** 降亮上限（0..1——终审稿红线：降亮 ~20%，迷雾层 alpha 封顶 0.25；
     *  本件取 0.20 双红线内，星光恒可辨） */
    const val MIST_DIM_MAX = 0.20f

    /** 降亮过渡带（世界单位——雾界外此带内从 0 渐变到满档，不生硬边界） */
    const val MIST_FADE = 600f

    /** 雾界（等级函数——有界单调：等级越高/点亮越多，雾散得越远） */
    fun mistReachOf(level: Int): Float =
        (MIST_REACH_BASE + MIST_REACH_PER_LEVEL * (level.coerceAtLeast(1) - 1)).coerceAtMost(MIST_REACH_MAX)

    /**
     * 迷雾降亮（0..[MIST_DIM_MAX]）：[dist]=星到已点亮质心的世界距离，
     * [reach]=[mistReachOf] 的雾界——界内零雾，界外过渡带内渐到满档。
     * **只降亮不锁内容**：星恒在、恒可飞、恒可点（终审稿红线）。
     */
    fun mistDimOf(dist: Float, reach: Float): Float {
        if (reach <= 0f) return 0f
        val t = ((dist - reach) / MIST_FADE).coerceIn(0f, 1f)
        return MIST_DIM_MAX * t
    }

    // ==================== 账本（增量语义四件——重算即迁移） ====================

    /**
     * 星辉账本（不可变值对象——只存点亮/虫洞两老账之外的增量语义）：
     *  - [planets]：已点亮行星键 `"chatId:tagUrlEnc"`（+3/颗）；
     *  - [cells]：已首踏星域格键（TasteNebula 聚类格行优先序——+25/格）；
     *  - [witness]：已记账目击键 `"epochMs|kindOrdinal|chatId"`（+15/次）；
     *  - [milestones]：已领红包里程碑档（+50/档）。
     */
    class Ledger(
        val planets: Set<String>,
        val cells: Set<Int>,
        val witness: Set<String>,
        val milestones: Set<Int>,
    ) {
        companion object {
            val EMPTY = Ledger(emptySet(), emptySet(), emptySet(), emptySet())
        }
    }

    /** 星辉总额（纯函数——三源重算：[litCount]=点亮账条数、[jumps]=虫洞账计数） */
    fun totalOf(litCount: Int, jumps: Int, ledger: Ledger): Long =
        litCount.toLong() * AWARD_LIGHT +
            ledger.planets.size.toLong() * AWARD_PLANET +
            ledger.cells.size.toLong() * AWARD_NEBULA_FIRST +
            jumps.toLong() * AWARD_WORMHOLE +
            ledger.witness.size.toLong() * AWARD_WITNESS +
            ledger.milestones.size.toLong() * AWARD_MILESTONE

    /** 行星点亮账本键（UI 去重与本账同一出处——`"chatId:tagUrlEnc"`） */
    fun planetKeyOf(chatId: Long, tag: String): String = "$chatId:${urlEnc(tag)}"

    /** 行星点亮记账（幂等——已点亮同键星辉不变；返回 (新账, 是否新点)） */
    fun addPlanet(ledger: Ledger, chatId: Long, tag: String): Pair<Ledger, Boolean> {
        val key = planetKeyOf(chatId, tag)
        if (key in ledger.planets) return ledger to false
        return Ledger(
            ledger.planets + key, ledger.cells, ledger.witness, ledger.milestones,
        ) to true
    }

    /** 首踏星域记账（幂等——同格只奖一次） */
    fun addCell(ledger: Ledger, cellKey: Int): Pair<Ledger, Boolean> {
        if (cellKey < 0 || cellKey in ledger.cells) return ledger to false
        return Ledger(
            ledger.planets, ledger.cells + cellKey, ledger.witness, ledger.milestones,
        ) to true
    }

    /** 目击记账（幂等——同事件实例只奖一次；[epochMs]/[kind]/[chatId]=事件身份） */
    fun addWitness(ledger: Ledger, epochMs: Long, kind: UniverseDirector.EventKind, chatId: Long): Pair<Ledger, Boolean> {
        val key = "$epochMs|${kind.ordinal}|$chatId"
        if (key in ledger.witness) return ledger to false
        return Ledger(
            ledger.planets, ledger.cells, ledger.witness + key, ledger.milestones,
        ) to true
    }

    /**
     * 里程碑红包（[litCount]=点亮账条数——跨档即领，每档一次；返回 (新账, 新领档表)）。
     * 幂等：已领档不重发（重算即迁移下「领过」也沉在分项账里）。
     */
    fun claimMilestones(ledger: Ledger, litCount: Int): Pair<Ledger, List<Int>> {
        val newOnes = MILESTONE_TIERS.filter { litCount >= it && it !in ledger.milestones }
        if (newOnes.isEmpty()) return ledger to emptyList()
        return Ledger(
            ledger.planets, ledger.cells, ledger.witness, ledger.milestones + newOnes,
        ) to newOnes
    }

    /** 目击图鉴三分项（图鉴组/勋章原料——按事件种类数次数） */
    class WitnessCounts(val nova: Int, val supernova: Int, val comet: Int)

    /** 目击账按种类分桶（纯函数） */
    fun witnessCounts(ledger: Ledger): WitnessCounts {
        var nova = 0
        var sup = 0
        var com = 0
        for (k in ledger.witness) {
            when (witnessKindOrdinal(k)) {
                UniverseDirector.EventKind.NOVA.ordinal -> nova++
                UniverseDirector.EventKind.SUPERNOVA.ordinal -> sup++
                UniverseDirector.EventKind.COMET.ordinal -> com++
            }
        }
        return WitnessCounts(nova, sup, com)
    }

    /** 目击键取 kindOrdinal（`"epochMs|kindOrdinal|chatId"`——坏键=-1 跳过） */
    private fun witnessKindOrdinal(key: String): Int {
        val i1 = key.indexOf('|')
        if (i1 < 0) return -1
        val i2 = key.indexOf('|', i1 + 1)
        if (i2 < 0) return -1
        return key.substring(i1 + 1, i2).toIntOrNull() ?: -1
    }

    // ==================== 探索者编年史（终审稿 §3——行为叙事型勋章组 7 枚） ====================

    /**
     * 编年史勋章判据原料（装配口收集，全本地只读）：
     *  - [litCount]：点亮账条数（飞近点亮——TasteDiscoveryLog）；
     *  - [jumps]：虫洞跳跃数（TasteVoyageLog）；
     *  - [nova]/[supernova]/[comet]：目击分桶（本账 witness）；
     *  - [cells]：已首踏星域格数；
     *  - [litRatio01]：点亮率（已知并集口径——与面板「已探索 N/M」同一尺）。
     */
    class ChronicleInput(
        val litCount: Int,
        val jumps: Int,
        val nova: Int,
        val supernova: Int,
        val comet: Int,
        val cells: Int,
        val litRatio01: Float,
    )

    /** 编年史勋章键（稳定 id——面板/指引共用；与 R235/R248 既有键零碰撞） */
    const val CHRON_FIRST_LIGHT = "chron_first_light"
    const val CHRON_VOYAGER = "chron_voyager"
    const val CHRON_NOVA = "chron_nova_witness"
    const val CHRON_SUPERNOVA = "chron_supernova_witness"
    const val CHRON_COMET = "chron_comet_catcher"
    const val CHRON_REGIONS = "chron_region_walker"
    const val CHRON_FULL = "chron_full_light"

    /** 彗星捕手目标（目击彗星次数） */
    const val COMET_TARGET = 5

    /** 星域行者目标（不同星云格数） */
    const val REGIONS_TARGET = 5

    /**
     * 编年史勋章组（纯函数——每枚一段探索叙事；**不参与等阶晋升**（R235 红线同族），
     * 只出进度与达成态；复用 [TasteAchievements.Medal] 类=面板渲染零新件）：
     * 首光/穿越者/新星目击者/超新星见证人/彗星捕手/星域行者/全图之光。
     */
    fun chronicleMedals(input: ChronicleInput): List<TasteAchievements.Medal> = listOf(
        TasteAchievements.Medal(
            CHRON_FIRST_LIGHT, "首光", input.litCount.toLong(), 1L,
            input.litCount >= 1, "颗",
        ),
        TasteAchievements.Medal(
            CHRON_VOYAGER, "穿越者", input.jumps.toLong(), 1L,
            input.jumps >= 1, "次",
        ),
        TasteAchievements.Medal(
            CHRON_NOVA, "新星目击者", input.nova.toLong(), 1L,
            input.nova >= 1, "次",
        ),
        TasteAchievements.Medal(
            CHRON_SUPERNOVA, "超新星见证人", input.supernova.toLong(), 1L,
            input.supernova >= 1, "次",
        ),
        TasteAchievements.Medal(
            CHRON_COMET, "彗星捕手", input.comet.toLong(), COMET_TARGET.toLong(),
            input.comet >= COMET_TARGET, "次",
        ),
        TasteAchievements.Medal(
            CHRON_REGIONS, "星域行者", input.cells.toLong(), REGIONS_TARGET.toLong(),
            input.cells >= REGIONS_TARGET, "个星域",
        ),
        TasteAchievements.Medal(
            CHRON_FULL, "全图之光", (input.litRatio01 * 100f).toLong(), 100L,
            input.litRatio01 >= 1f, "%",
        ),
    )

    /**
     * 「最近的下一枚」（成就面板=航海图的指引核心）：未达成勋章里进度最高者
     *（并列=表序——确定性）；全达成=null。返回勋章键（文案/星图目标由 UI 侧
     * 按键解析——文案要拼方向与差额，方向原料只在 UI 有）。
     */
    fun nextChronicleKey(medals: List<TasteAchievements.Medal>): String? {
        var best: TasteAchievements.Medal? = null
        for (m in medals) {
            if (m.met) continue
            val b = best
            if (b == null || m.progress01 > b.progress01) best = m
        }
        return best?.key
    }

    /** 八方罗盘（世界系绝对方位——「西南方向雾里有未踏星域」的方向词单口；
     *  [dx]/[dz]=目标−相机的世界位移；零向量=「此处」） */
    fun compassLabel(dx: Float, dz: Float): String {
        if (dx * dx + dz * dz < 1e-6f) return "此处"
        // 世界系：+x=东，+z=南（屏幕俯视角口径）；八向 45° 分扇
        val ang = Math.toDegrees(kotlin.math.atan2(dz.toDouble(), dx.toDouble())).toFloat()
        val a = ((ang % 360f) + 360f) % 360f
        return when (((a + 22.5f) / 45f).toInt() % 8) {
            0 -> "东"
            1 -> "东南"
            2 -> "南"
            3 -> "西南"
            4 -> "西"
            5 -> "西北"
            6 -> "北"
            else -> "东北"
        }
    }

    // ==================== 账本 IO（手写 JSON/原子写/父目录自建/损坏归零） ====================

    /** 读账本（文件缺失/损坏/半写=空账诚实缺省，绝不抛） */
    fun loadLedger(file: File): Ledger {
        if (!file.isFile) return Ledger.EMPTY
        return runCatching { parseLedger(file.readText()) }.getOrDefault(Ledger.EMPTY)
    }

    /** 原子写（临时文件+rename；失败静默——增量账丢一次可容忍，绝不抛） */
    fun saveLedger(file: File, ledger: Ledger) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(serializeLedger(ledger))
            if (!tmp.renameTo(file)) {
                file.writeText(serializeLedger(ledger))
                tmp.delete()
            }
        }
    }

    /** 序列化（`{"v":1,"planets":[...],"cells":[...],"witness":[...],"milestones":[...]}`） */
    fun serializeLedger(ledger: Ledger): String {
        val sb = StringBuilder(ledger.planets.size * 24 + ledger.witness.size * 24 + 128)
        sb.append("{\"v\":1,\"planets\":[")
        ledger.planets.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEsc(k)).append('"')
        }
        sb.append("],\"cells\":[")
        ledger.cells.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append(c)
        }
        sb.append("],\"witness\":[")
        ledger.witness.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEsc(k)).append('"')
        }
        sb.append("],\"milestones\":[")
        ledger.milestones.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append(m)
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 解析（坏件静默跳过——诚实缺省；缺字段=空集） */
    fun parseLedger(text: String): Ledger {
        val t = text.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return Ledger.EMPTY
        return Ledger(
            planets = stringArrayOf(t, "planets"),
            cells = intArrayOf(t, "cells"),
            witness = stringArrayOf(t, "witness"),
            milestones = intArrayOf(t, "milestones"),
        )
    }

    /** 提取 `"field":[...]` 的字符串数组（手写扫描——引号感知；坏段跳过） */
    private fun stringArrayOf(json: String, field: String): Set<String> {
        val body = arrayBody(json, field) ?: return emptySet()
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"') {
                val sb = StringBuilder()
                var j = i + 1
                while (j < body.length) {
                    val ch = body[j]
                    if (ch == '\\' && j + 1 < body.length) {
                        sb.append(
                            when (body[j + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> body[j + 1]
                            },
                        )
                        j += 2
                    } else if (ch == '"') {
                        break
                    } else {
                        sb.append(ch)
                        j++
                    }
                }
                if (j < body.length) {
                    val v = sb.toString()
                    if (v.isNotEmpty()) out += v
                    i = j + 1
                } else {
                    break // 半写截断=到此为止（诚实缺省）
                }
            } else {
                i++
            }
        }
        return out
    }

    /** 提取 `"field":[...]` 的整数数组 */
    private fun intArrayOf(json: String, field: String): Set<Int> {
        val body = arrayBody(json, field) ?: return emptySet()
        val out = LinkedHashSet<Int>()
        for (part in body.split(',')) {
            val v = part.trim().toIntOrNull() ?: continue
            out += v
        }
        return out
    }

    /** 抠 `"field":[ ... ]` 的方括号体（字符串感知——字符串内的 [ ] 不算） */
    private fun arrayBody(json: String, field: String): String? {
        val m = Regex("\"$field\"\\s*:\\s*\\[").find(json) ?: return null
        var i = m.range.last + 1
        var depth = 1
        var inStr = false
        var esc = false
        val start = i
        while (i < json.length) {
            val ch = json[i]
            when {
                esc -> esc = false
                inStr && ch == '\\' -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '[' -> depth++
                !inStr && ch == ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i)
                }
            }
            i++
        }
        return null // 半写截断
    }

    /** JSON 字符串转义（只转必要两字符+控制符——手写 JSON 纪律） */
    private fun jsonEsc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** tag URL 编码（账本键安全字符——tag 可含任意 Unicode/分隔符） */
    private fun urlEnc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    // ==================== 三源重算（重算即迁移的单口） ====================

    /**
     * 星辉全账（面板/HUD/门禁的单一读数）：分项账+总额+进度+解锁+图鉴。
     */
    class Book(
        val ledger: Ledger,
        val litCount: Int,
        val jumps: Int,
        val total: Long,
        val progress: Progress,
        val counts: WitnessCounts,
    ) {
        val level: Int get() = progress.level

        /** 已授予解锁件 */
        val unlocks: List<Unlock> get() = unlocksAt(level)

        /** 下一解锁件（null=八级全通） */
        val nextUnlock: Unlock? get() = TasteStarlight.nextUnlock(level)

        /** 罗盘亮起（Lv2 已解锁 ∧ 升级差 ≤[COMPASS_GAP_01]） */
        val compassLit: Boolean
            get() = isUnlocked(level, "compass") && progress.gap01 <= COMPASS_GAP_01

        /** 「再点亮 N 颗即升级」（罗盘文案——点亮是最常见来源的诚实换算） */
        val lightsToNext: Int
            get() = ceil((progress.need - progress.into).toDouble() / AWARD_LIGHT).toInt().coerceAtLeast(1)
    }

    /**
     * 三源重算（**重算即迁移**——等级/总额永远是账本的纯函数）：
     * 点亮账（discovered_stars.json）+ 虫洞账（voyage.txt）+ 增量账（starlight.json）。
     * [galaxyDir]=File(filesDir,"galaxy")；三文件各自损坏归零（老账纪律同族）。
     */
    fun loadProgress(galaxyDir: File): Book {
        val litCount = TasteDiscoveryLog.load(File(galaxyDir, TasteDiscoveryLog.FILE_NAME)).size
        val jumps = TasteVoyageLog.load(File(galaxyDir, TasteVoyageLog.FILE_NAME)).wormholeJumps
        val ledger = loadLedger(File(galaxyDir, FILE_NAME))
        val total = totalOf(litCount, jumps, ledger)
        return Book(ledger, litCount, jumps, total, progressOf(total), witnessCounts(ledger))
    }
}
