package dev.tgfeed.core.tastemap

import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem
import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * R233 行星层纯函数（口味星图二期·零 Android 依赖，单测可跑）：
 *
 * **行星=tag（话题），资源条目=行星的卫星尘埃环**（设计 §1.5）。一个频道的
 * 近端条目样本（[VideoItem.itemTags]，R228 item_tags 落库口径）聚合成它的
 * 行星系统——轨道参数全部**确定性**（同数据同布局绝不跳位，全布局一以贯之纪律）：
 *
 *  - 轨道半径 ∝ `log1p(该 tag 条数)` 的**反比**——越核心的 tag 离恒星越近
 *    （引力越强，设计原文口径）；
 *  - 大小 ∝ `sqrt(count/maxCount)`（同款 √ 缩放，钳在 0..1）；
 *  - 轨道相位=确定性哈希（[h01]，同 FxEngine 同式）——**每次打开位置一致**；
 *  - 公转角速度=**开普勒第三定律**（R249：ω=C/a^1.5，[keplerOmegaOf]——近轨道
 *    公转快、远轨道慢；量程钳 [OMEGA_MIN, OMEGA_MAX]=R233 可点性校准界沿用）；
 *    engine.timeSec 会话内从 0 起步 → 开场相位=哈希相位不变式保持；
 *  - **R249 开普勒轨道三件**：[ecc]（第一定律：严格椭圆，恒星在焦点，e≤0.26=
 *    内圈近日点恒在恒星面外）+ [keplerTheta]（第二定律：平近点角→牛顿迭代→
 *    真近点角，近日点快远日点慢）+ [keplerOrbitRadius]（r(ν)=a(1−e²)/(1+e·cosν)）。
 *
 * 尘埃环（设计 §1.5「考古的钥匙」）：**轨道相位=发布时间**——`dateEpochSec` 映射到
 * 角度，一圈=该 tag 样本的时间跨度（[dustAngleOf]）——贴脸看一颗行星，尘埃的角度
 * 分布一眼读出「这个 tag 在该频道是最近爆发还是远古存货」。
 *
 * 数据口径（诚实标注）：样本=每频道**近端 [SAMPLE_LIMIT] 条**（channelItemsPage
 * 索引导航有界），R228 存量回填进行中时更早条目 item_tags=NULL → 样本天然偏新，
 * 「按近端样本观测」与金矿 6h 快照同一档诚实口径，绝不冒充全量。
 * R236：[build] 的 [ledger] 参数（tags 表 topTags 账本）把**行星排序/计数**升
 * 级为全频道历史口径（治样本偏差挂账）；尘埃环/播放批仍近端样本（LOD 护栏）。
 */
object TasteTagPlanets {

    /** 行星样本=每频道近端条数（channelItemsPage limit 口径；索引导航回表恒 ≤ 此值） */
    const val SAMPLE_LIMIT = 240

    /** 装配保留行星上限（按计数留重；LOD 近距档最多消费 15——余量给排序稳定性） */
    const val MAX_PLANETS = 24

    /** 中距档行星数（井圈投影半径过中距阈：大行星先行，任务书「中距=大行星 top5」档） */
    const val LOD_MID_PLANETS = 6

    /** 近距档行星数（井圈投影半径过近距阈 ≈80dp：更多行星+轨道线，任务书「top 10-15」档） */
    const val LOD_NEAR_PLANETS = 15

    /** 关联弧判据指纹保留 tag 数（TasteTagAffinity 消费——轻量 Map） */
    const val FINGERPRINT_TAGS = 24

    /** 轨道半径下界（×井半径）：最核心的 tag 也留出恒星本体呼吸空间 */
    private const val ORBIT_MIN = 0.24f

    /** 轨道半径跨度（×井半径）：ORBIT_MIN+ORBIT_SPAN=最外圈 */
    private const val ORBIT_SPAN = 0.68f

    /** 公转角速度下界（rad/s）——最慢 ~26 分钟一圈：**确定性相位「每次打开位置一致」
     *  的可点性配套**（真机实测：omega≥0.03 时截图→定位→注入的 ~10s 循环里行星
     *  沿轨道走出命中半径——公转活着但要让自动化/手指都追得上） */
    private const val OMEGA_MIN = 0.004f

    /** 公转角速度上界（rad/s）——R249 开普勒域钳（同可点性校准；最快 ~3.5 分钟一圈） */
    const val OMEGA_MAX = 0.030f

    /** 开普勒第三定律常数（ω=C/a^1.5，a=orbitR01）：中位轨道（a≈0.58）≈0.010 rad/s
     *  与 R233 旧中位同拍——「近轨道快远轨道慢」的对比在此常数下内圈/外圈差 ~5 倍 */
    private const val PLANET_KEPLER_C = 0.0044f

    // ---- LOD 井深比阈值（ratio=相机到恒星 3D 世界距离/井半径，**越小越近**；
    //      无量纲——与设备/fitScale/分辨率全无关，任务书「远看零行星→中距 top5→
    //      近距 top10-15→贴脸尘埃」四档的判定量） ----

    /** 行星开始浮现的井深比上限（井圈初入——top tag 先亮） */
    const val APPEAR_RATIO = 6.0f

    /** top 10-15 行星全现+轨道线亮档的井深比上限（近距档） */
    const val FULL_RATIO = 3.2f

    /**
     * 尘埃环档（贴脸——时间=相位的考古视图）：**深度锚**（星深 d=flightZ+dist_eff
     * 的世界单位带 [DUST_GAP_MIN, DUST_GAP_MAX]，越近越浓）——真机校准：井深比
     * 版（2.2×well）对居中大星落在近裁剔除界（140）以下=尘埃永远画不出来；深度
     * 带 [150,260] 恰好骑在「feed 跳入停船点 260 → 一冲程俯冲到 150」的进近走廊
     * 上（配合行星层井深比门 APPEAR 双闸——小井频道行星层未亮时尘埃不冒头）。
     */
    const val DUST_GAP_MIN = 150f

    /** 尘埃档深度带上限（=feed 跳入停船点：到达即行星、俯冲渐入尘埃） */
    const val DUST_GAP_MAX = 260f

    /** 浮现斜坡窗宽（井深比单位）：每颗行星各自的淡入带 */
    const val APPEAR_RAMP = 0.8f

    /**
     * 一颗行星（tag）的轨道参数与尘埃环原料（不可变值对象——装配期定死，渲染期零计算
     * 除投影/公转推进）。
     *
     * @property tag 标签词（展示原形，不含 #）
     * @property count 该 tag 在样本内的条数
     * @property orbitR01 轨道半长轴（0..1，×井半径=世界轨道半长轴；频率越高越靠内）
     * @property sizeR01 行星大小比例（0..1，sqrt 缩放；渲染端乘井圈视半径）
     * @property phase01 确定性哈希相位（0..1——**平近点角 M0**，每次打开位置一致的不变式锚）
     * @property omegaRadPerSec 公转角速度（rad/s，**开普勒第三定律** [keplerOmegaOf]
     *   ——近轨道快远轨道慢，钳 [OMEGA_MIN, OMEGA_MAX]）
     * @property ecc 轨道偏心率（**开普勒第一定律**：严格椭圆，恒星在焦点；R249
     *   [keplerEccentricity] 确定性哈希 0.14~0.24——内圈近日点恒在恒星面外）
     * @property itemIdx 该 tag 的条目在样本表中的下标（**发布时间升序**——尘埃环
     * 相位原料；UI 层持有样本 List&lt;VideoItem&gt;，本表只存下标不复制条目）
     * @property dustMinSec / dustMaxSec 该 tag 条目的时间跨度（[dustAngleOf] 归一域；
     * 相等=同时段发布——尘埃全聚同一角度，是「考古」要读的爆发信号本身）
     * @property compV / compP / compA R311 行星色原料：该 tag 条目的内容构成三票
     * （mediaType 0=视频/1=图片/2=音频——「行星色=内容构成：视频暖/图片冷/音频紫」，
     * TasteStarColor.planetColorOf 消费；装配期从近端样本统计，零新增请求）
     */
    class TagPlanet(
        val tag: String,
        val count: Int,
        val orbitR01: Float,
        val sizeR01: Float,
        val phase01: Float,
        val omegaRadPerSec: Float,
        val itemIdx: IntArray,
        val dustMinSec: Long,
        val dustMaxSec: Long,
        val ecc: Float = 0f,
        val compV: Int = 0,
        val compP: Int = 0,
        val compA: Int = 0,
    )

    /**
     * 一个频道的行星系统（不可变，装配期定死）。UI 层惰性装配（L2 引力井触发，
     * 内存 LRU）后与样本条目表（List&lt;VideoItem&gt;）配对持有——尘埃环/点尘埃
     * 播放批共用同一样本，零二次查询。
     */
    class PlanetSystem(
        val chatId: Long,
        /** 行星表（count 降序、tag 升序破平——确定性序） */
        val planets: List<TagPlanet>,
        /** 样本条数（「近端 N 条样本」诚实口径的 N） */
        val sampleCount: Int,
        /** 样本内带 tag 条目数（其余条目无 tag——行星层数据覆盖面诊断） */
        val taggedCount: Int,
    )

    /**
     * 样本 → 行星系统（纯函数，确定性）：
     *  - 聚合键=tag 折叠归一（[SearchTextKit.fold]——tags 表 tag 列/R228 searchHashtag
     *    比对同一把尺子；display 原形保留）；
     *  - 排序=count 降序 → tag 折叠键升序（同计数并列时字典序=稳定不跳位）；
     *    超 [MAX_PLANETS] 按此序留重；
     *  - 每颗行星的 [TagPlanet.itemIdx] 按发布时间升序（dateEpochSec 相等按
     *    messageId 升序破平——TG 相册连发天然聚集=尘埃弧成段的诚实结构）；
     *  - 无 tag 样本 → 空系统（诚实空态：该频道近端样本还没有考古材料）。
     *
     * R236 [ledger]（topTags 账本，null/空=回退样本聚合）：
     *  - **行星排序与计数换全量频道账本**（tags 表 PK chatId 前缀，毫秒级）——
     *    治「近端 240 样本偏差」挂账（频道近期换话题不再顶掉历史主话题）；
     *  - [TagPlanet.itemIdx]/尘埃环/播放批仍取自近端样本（LOD 护栏不变；账本里
     *    有、样本里没有的 tag → 行星在场、尘埃空——诚实缺省，优于旧口径的
     *    整颗行星缺席）；dustMin/Max=样本跨度（无样本条目=0）；
     *  - ledger pair 首元素=display 首见原形（tags 表 display 列——行星标签显示
     *    口径不降级），计数=账本 itemCount。
     */
    fun build(chatId: Long, items: List<VideoItem>, ledger: List<Pair<String, Int>>? = null): PlanetSystem {
        // 聚合：fold 键 → 计数 + 首见展示形 + 条目引用表
        val foldIndex = HashMap<String, Acc>(64)
        var tagged = 0
        for (i in items.indices) {
            val tags = items[i].itemTags ?: continue
            if (tags.isEmpty()) continue
            tagged++
            for (tag in tags) {
                if (tag.isBlank()) continue
                val fold = SearchTextKit.fold(tag)
                val acc = foldIndex.getOrPut(fold) { Acc(tag, 0, ArrayList(8)) }
                acc.count++
                acc.idx += i
            }
        }
        if (ledger == null || ledger.isEmpty()) {
            // 回退路径（回填窗口期/无 tag 频道）：样本聚合，行为与 R233 原版一致
            return buildFromSample(chatId, items, foldIndex, tagged)
        }
        // 账本路径：排序/计数=全量账本（topTags）；itemIdx/尘埃=样本折叠键命中。
        // fold 去重防御（同折叠键多 display 保留计数高者——topTags 天然唯一，双保险）
        val byFold = LinkedHashMap<String, Pair<String, Int>>(ledger.size)
        for ((display, count) in ledger) {
            val fold = SearchTextKit.fold(display)
            if (fold.isEmpty()) continue
            val prev = byFold[fold]
            if (prev == null || count > prev.second) byFold[fold] = display to count
        }
        val ranked = byFold.entries
            .sortedWith(compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }.thenBy { it.key })
            .take(MAX_PLANETS)
        val maxCount = ranked.firstOrNull()?.value?.second ?: 0
        val lnMax = ln1p(maxCount.toFloat())
        val planets = ranked.map { (fold, pair) ->
            TagPlanet(
                tag = pair.first,
                count = pair.second,
                orbitR01 = if (maxCount <= 1) ORBIT_MIN + ORBIT_SPAN * 0.5f
                else ORBIT_MIN + ORBIT_SPAN * (1f - ln1p(pair.second.toFloat()) / lnMax),
                sizeR01 = if (maxCount <= 0) 0f else sqrt(pair.second.toFloat() / maxCount),
                phase01 = h01(chatId.foldHash(), fold.hashCode()),
                omegaRadPerSec = keplerOmegaOf(
                    if (maxCount <= 1) ORBIT_MIN + ORBIT_SPAN * 0.5f
                    else ORBIT_MIN + ORBIT_SPAN * (1f - ln1p(pair.second.toFloat()) / lnMax),
                ),
                itemIdx = planetItemIdx(items, foldIndex[fold]),
                dustMinSec = 0L,
                dustMaxSec = 0L,
                ecc = keplerEccentricity(fold.hashCode()),
            )
        }
        return PlanetSystem(chatId, withDustSpan(planets, items), items.size, tagged)
    }

    /** 样本聚合路径（ledger 缺席的回退；R233 原版行为） */
    private fun buildFromSample(
        chatId: Long,
        items: List<VideoItem>,
        foldIndex: HashMap<String, Acc>,
        tagged: Int,
    ): PlanetSystem {
        val maxCount = foldIndex.values.maxOfOrNull { it.count } ?: 0
        val ranked = foldIndex.entries
            .sortedWith(compareByDescending<Map.Entry<String, Acc>> { it.value.count }.thenBy { it.key })
            .take(MAX_PLANETS)
        val lnMax = ln1p(maxCount.toFloat())
        val planets = ranked.map { (fold, acc) ->
            TagPlanet(
                tag = acc.display,
                count = acc.count,
                orbitR01 = if (maxCount <= 1) ORBIT_MIN + ORBIT_SPAN * 0.5f
                else ORBIT_MIN + ORBIT_SPAN * (1f - ln1p(acc.count.toFloat()) / lnMax),
                sizeR01 = if (maxCount <= 0) 0f else sqrt(acc.count.toFloat() / maxCount),
                phase01 = h01(chatId.foldHash(), fold.hashCode()),
                omegaRadPerSec = keplerOmegaOf(
                    if (maxCount <= 1) ORBIT_MIN + ORBIT_SPAN * 0.5f
                    else ORBIT_MIN + ORBIT_SPAN * (1f - ln1p(acc.count.toFloat()) / lnMax),
                ),
                itemIdx = planetItemIdx(items, acc),
                dustMinSec = 0L,
                dustMaxSec = 0L,
                ecc = keplerEccentricity(fold.hashCode()),
            )
        }
        return PlanetSystem(chatId, withDustSpan(planets, items), items.size, tagged)
    }

    /** 聚合累加器（fold 键 → 首见展示形/计数/样本条目引用表） */
    private class Acc(val display: String, var count: Int, val idx: ArrayList<Int>)

    /** 行星的样本条目下标（发布时间升序，dateEpochSec 相等按 messageId 破平） */
    private fun planetItemIdx(items: List<VideoItem>, acc: Acc?): IntArray {
        if (acc == null) return IntArray(0)
        return acc.idx.sortedWith(
            compareBy<Int> { items[it].dateEpochSec }.thenBy { items[it].messageId },
        ).toIntArray()
    }

    /** 行星尘埃跨度回填（itemIdx 首末样本的发布时刻；空尘埃保持 0/0——诚实缺省）；
     *  R311 同口回填内容构成三票（compV/compP/compA=mediaType 0/1/2 计数——行星色
     *  原料；空尘埃=0/0/0 哨兵，planetColorOf 走恒星光回落） */
    private fun withDustSpan(planets: List<TagPlanet>, items: List<VideoItem>): List<TagPlanet> =
        planets.map { p ->
            if (p.itemIdx.isEmpty()) {
                p
            } else {
                var cv = 0
                var cp = 0
                var ca = 0
                for (idx in p.itemIdx) {
                    when (items.getOrNull(idx)?.mediaType ?: 0) {
                        1 -> cp++
                        2 -> ca++
                        else -> cv++
                    }
                }
                TagPlanet(
                    p.tag, p.count, p.orbitR01, p.sizeR01, p.phase01, p.omegaRadPerSec, p.itemIdx,
                    items[p.itemIdx.first()].dateEpochSec.toLong(),
                    items[p.itemIdx.last()].dateEpochSec.toLong(),
                    p.ecc,
                    cv, cp, ca,
                )
            }
        }

    /**
     * 尘埃环相位：发布时间 → 角度（弧度，0..2π）。
     * 一圈=该 tag 的时间跨度（设计原文）；跨度 ≤0（同时段/单条）→ 恒 0——
     * **同时发布聚在同一角度是「考古」要读的信号本身**（爆发=亮弧），不做假散布。
     */
    fun dustAngleOf(dateSec: Long, minSec: Long, maxSec: Long): Float {
        val span = maxSec - minSec
        if (span <= 0L) return 0f
        val t = ((dateSec - minSec).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        return (t * 6.283185307179586).toFloat()
    }

    /**
     * 关联弧/信息卡共用的频道 tag 指纹（fold 键 → 计数，top [limit] 留重）：
     * 轻量 Map（~24 项），全池大频道后台预取与焦点星按需现算共用本口。
     * R236：折叠键换 [SearchTextKit.fold]（tags 表 tag 列同把尺子——R236 装配侧
     * 换 topTags 账本指纹后键域一致；简繁/全角变体不再裂成两个键）。
     */
    fun fingerprintOf(items: List<VideoItem>, limit: Int = FINGERPRINT_TAGS): Map<String, Int> {
        val counts = HashMap<String, Int>(64)
        for (item in items) {
            val tags = item.itemTags ?: continue
            for (tag in tags) {
                if (tag.isBlank()) continue
                counts.merge(SearchTextKit.fold(tag), 1, Int::plus)
            }
        }
        if (counts.size <= limit) return counts
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .associate { it.key to it.value }
    }

    /**
     * 确定性 0..1 哈希（FxEngine.h01 同式镜像——星级散布同款手法，同参数同值）。
     */
    internal fun h01(a: Int, b: Int): Float =
        (((a * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF).toFloat() / 16777216f

    // ==================== 轨道几何单口（渲染绘制与命中判定同一式——类问题收敛） ====================

    // ---- R249 开普勒轨道三件（第一/第二/第三定律——渲染/命中/飞进行星门共用单口） ----

    /**
     * 轨道偏心率（**开普勒第一定律**：恒星在焦点的严格椭圆）：确定性哈希
     * 0.14~0.24——椭圆感可见且内圈（a=1.9×恒星半径档）近日点 a(1−e)≥1.44×恒星
     * 半径=恒在恒星面外（R247「行星骑恒星脸上」的护栏不破）。
     */
    fun keplerEccentricity(tagHash: Int): Float = 0.14f + 0.10f * h01(tagHash, 917)

    /**
     * 公转角速度（**开普勒第三定律**：T²∝a³ → ω=C/a^1.5——近轨道公转快、远轨道
     * 慢），钳 [OMEGA_MIN, OMEGA_MAX]（R233 可点性校准界——上下界都不破自动化窗）。
     */
    fun keplerOmegaOf(orbitR01: Float): Float =
        TasteAstroPhysics.keplerOmega(orbitR01.coerceAtLeast(0.05f), PLANET_KEPLER_C)
            .coerceIn(OMEGA_MIN, OMEGA_MAX)

    /**
     * 近日点偏角 ϖ（弧度，确定性哈希）：椭圆长轴在轨道面内的取向——**取值即不变**
     * （纯哈希无时间项），渲染轨道环折线与行星位置共用（同一椭圆）。
     */
    fun keplerPeriapsis(planet: TagPlanet): Float = h01(planet.tag.hashCode(), 919) * 6.2831853f

    /**
     * 行星当前真近点角 ν（**开普勒第二定律**——等面积：近日点快、远日点慢）：
     * 平近点角 M=phase01·2π+ω·t（时间均匀走）→ 牛顿迭代解偏近点角 E → ν。
     * 与 [keplerOrbitRadius]/[keplerPeriapsis] 配对=椭圆上的位置（角度 ν+ϖ）。
     */
    fun keplerTheta(planet: TagPlanet, tSec: Float): Float =
        TasteAstroPhysics.keplerTrueAnomaly(
            planet.phase01 * 6.2831853f + planet.omegaRadPerSec * tSec,
            planet.ecc,
        )

    /**
     * 开普勒轨道半径（**第一定律**：r(ν)=a(1−e²)/(1+e·cosν)——ν=0 近日点
     * a(1−e)，ν=π 远日点 a(1+e)；[aW]=世界半长轴，渲染/命中同一式）。
     */
    fun keplerOrbitRadius(planet: TagPlanet, aW: Float, trueAnomaly: Float): Float =
        TasteAstroPhysics.orbitRadius(aW, planet.ecc, trueAnomaly)

    /** 轨道椭圆 y 半轴压扁比（盘面观感）：世界 y 偏移 = 0.62×轨道半径×sinθ */
    const val ORBIT_FLATTEN = 0.62f

    /**
     * 行星半径系数下限（×恒星视觉半径——最小行星占比；「上限压小防吃掉恒星」）。
     * R247 精调：0.035→0.115——用户口径「行星是恒星的伴星不是同等大小」（目标比
     * 例 1:5~1:8；R242 渲染端曾把井半径误当锚传入=中位星行星达恒星 2/3 的双星观感，
     * R247 渲染端换正锚=本系数回归设计语义）。
     */
    private const val PLANET_R_MIN_COEF = 0.115f

    /** 行星半径系数跨度（×恒星视觉半径——大小 ∝ 频率的增益，上限 0.20×恒星=1:5） */
    private const val PLANET_R_SPAN_COEF = 0.085f

    /** 尘埃点尺寸系数（×恒星视半径）：贴脸时恒星数百 dp，尘埃=米粒级微点 */
    private const val DUST_R_COEF = 0.011f

    /** 尘埃点尺寸下限（px）：远档也可见的最小点 */
    private const val DUST_R_MIN_PX = 1.1f

    /**
     * 行星浮现度（0..1）：**井深比版**（[ratio] 越小越近）。第 [index] 颗（count
     * 降序序）的阈值斜坡——top tag 在 [APPEAR_RATIO] 先亮、第 [total] 颗在
     * [FULL_RATIO] 到齐（「中距 top5 → 近距 top10-15」的连续 LOD，渲染器零计时器
     * 状态——浮现=阈值斜坡不是动画）。[total]=渲染档位数（min(size, LOD_NEAR)）。
     */
    fun appearAlpha(ratio: Float, index: Int, total: Int): Float =
        appearAlpha(ratio, index, total, APPEAR_RAMP)

    /**
     * R328 行星成型贴脸拍坡道参重载（API 只加不改——现状调用面零破坏）：坡道
     * 窗宽入参化（公式单一出处=本函数，3 参旧签名委托本件）。**批 C 消费口径**
     * （TasteBirthFx KDoc 点名接线位）：成型态（birth.planetsForming）渲染端把
     * [TasteBirthFx.formingRampWidth]（APPEAR_RAMP×FORMING_RAMP_MULT≈1.44
     * ——出场更慢=「正在聚成」读法）喂本参；非成型态走 3 参旧签名
     * =现状零改。[ramp]≤0 防御档=阶跃（零宽坡道极限——正常调用域不达）。
     */
    fun appearAlpha(ratio: Float, index: Int, total: Int, ramp: Float): Float {
        if (total <= 0) return 0f
        val appearRatio = FULL_RATIO + (APPEAR_RATIO - FULL_RATIO) * (total - index) / total
        if (ramp <= 0f) return if (ratio < appearRatio) 1f else 0f
        return ((appearRatio - ratio) / ramp).coerceIn(0f, 1f)
    }

    /** 近距亮档系数（0..1）：井深比进 FULL 档=1（轨道线亮档的驱动量） */
    fun nearBoost(ratio: Float): Float =
        ((APPEAR_RATIO - ratio) / (APPEAR_RATIO - FULL_RATIO)).coerceIn(0f, 1f)

    /**
     * 尘埃档浮现度（0..1）：星深 [DUST_GAP_MIN, DUST_GAP_MAX] 带渐显（越近越浓；
     * 行星层井深比门由调用方把关——行星未亮的星不谈尘埃）。
     */
    fun dustAlpha(starDepth: Float): Float =
        ((DUST_GAP_MAX - starDepth) / (DUST_GAP_MAX - DUST_GAP_MIN)).coerceIn(0f, 1f)

    /** 行星半径系数（×恒星**视觉**半径——与恒星表观大小成比例，防吃掉恒星；
     * R247 口径 0.115~0.20=行星:恒星 1:8.7~1:5，渲染端 planetWorldRadius 消费） */
    fun planetRadiusCoef(planet: TagPlanet): Float =
        PLANET_R_MIN_COEF + PLANET_R_SPAN_COEF * planet.sizeR01

    /** 尘埃点半径（px）：恒星视半径×系数（贴脸时恒星大、尘埃=米粒级），下限 1.1px */
    fun dustRadiusPx(starApparentPx: Float): Float =
        maxOf(DUST_R_MIN_PX, starApparentPx * DUST_R_COEF)

    /** 行星当前公转角（弧度）：哈希相位 + 确定性角速度×会话秒 */
    fun planetTheta(planet: TagPlanet, tSec: Float): Float =
        planet.phase01 * 6.2831853f + planet.omegaRadPerSec * tSec

    /** 尘埃带径向抖动系数（×轨道半径）：薄带的厚度（确定性哈希） */
    fun dustRadiusCoef(seed: Int, idx: Int): Float = 0.94f + 0.16f * h01(seed, idx * 31 + 3)

    // ==================== R309 行星微光可发现性（用户实锤「行星全黑融进背景看不见」） ====================
    // 设计口径：行星不发光（对），但夜面/背光面**不许死黑**——暗背景里必须可被发现；
    // 同时**绝不能亮过恒星**（恒星光核明度≈1.0 且吃 bloom 溢出——bloom 阈 0.70，
    // 本组常数全链路把行星夜面/缘光压在 ≤0.30 量级，天花板远低于恒星）。
    // 单一口径双端消费：Canvas=planetBitmapOf 位图烘焙、GL=PLANET_FS/ROCK_FS
    // shader 字符串内插（const val 编译期常量可入 const 模板）。

    /** 夜面环境光下限（反射恒星光感——星光环境的冷底光；GL 旧值 0.06=死黑实锤区） */
    const val PLANET_NIGHT_FLOOR = 0.16f

    /** 夜面环境光冷色偏移 R（星光色温——蓝移，三通道同族分行） */
    const val PLANET_NIGHT_TINT_R = 0.74f

    /** 夜面环境光冷色偏移 G */
    const val PLANET_NIGHT_TINT_G = 0.84f

    /** 夜面环境光冷色偏移 B（>1=蓝移；乘积后被 albedo 压回安全区） */
    const val PLANET_NIGHT_TINT_B = 1.12f

    /** 夜面轮廓缘光下限（fresnel 缘光保底——剪影可辨；GL 旧值 0.19 贴边一线太弱） */
    const val PLANET_RIM_FLOOR = 0.30f

    /** 岩石（小行星/尘埃岩）夜面环境光下限（GL ROCK_FS 旧值 0.20——同类扫描同病） */
    const val ROCK_NIGHT_FLOOR = 0.26f
}

/** Long chatId → 稳定 Int 哈希（高低位混合——负 chatId 的 toInt 截断不塌缩） */
private fun Long.foldHash(): Int = (this xor (this shr 32)).toInt()
