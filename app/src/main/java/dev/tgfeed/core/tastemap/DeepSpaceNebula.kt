package dev.tgfeed.core.tastemap

import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * R373 深空星云复合体纯件（design-nebula.md §3.2/§4.1/§4.3/§5.1 与 §十一 富集锚
 * ——用户终审七项全批+增补；core 零 Android 依赖，JVM 单测可跑，TasteNebula 与
 * EmissionNebulaFx 同族模式）：给宇宙补「星际介质」的星系级低频云气层——既有
 * 雾团/摇篮是团级语义墨水（R235 8×8 高频格聚类），本件是密度场涌现的大尺度
 * 气体结构（摇篮复合体+富集反射云双源并置）。
 *
 * - 双源并置（§十一 富集锚——同一低通管线两原料，类收敛）：
 *   - 摇篮场=未探索星（watchSec==0 且 !subscribed，R235 同口径）逐星投质量，
 *     金矿候选（TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE 注记——装配期
 *     已有零新请求）质量乘 [GOLD_WEIGHT]——云=还没变成星的东西，金矿候选
 *     聚集处=云最密处；
 *   - 富集场=全星表（含已探索星——与摇篮场的差异位即语义：热区=用户最爱看
 *     的星域）逐星质量 1；
 *   - 两场同走 [lowpassField]（64×64 网格 splat+可分离高斯 σ=[GAUSS_SIGMA]
 *     世界单位——口味平面 1000 幅员与 R235 雾团同场不同频段：雾=8×8 高频格
 *     聚类、复合体=低通低频结构，单一质量场两频段消费=类收敛元规则一）。
 * - 提取（§3.2）：密度过阈（峰值相对门+平坦场绝对门双守——均匀场不出假云）
 *   连通域，按总质量留重 ≤[MAX_COMPLEXES] 团；每团=质心/半径/质量/主轴。
 *   哈希确定性（同数据同云）：星按 chatId 排序后全场求和（浮点顺序稳定），
 *   连通域按格序行优先、留重按质量降序+格键破平——零 Random 零系统钟。
 * - 位置真源 agnostic（§3.2）：密度场在口味平面（聚类语义场——R235 同场）；
 *   世界质心/半径=成员星 sp 坐标（星表位置真源——V3 还是涌现布局谁产的就
 *   跟谁）质量加权均值+RMS 幅员，布局换轨零接口改自动跟随。
 * - 两级颜色（§4.1——各单源，零 hue(t)，禁令 12 不犯）：
 *   - 复合体=发射线物理色板 [HA_ARGB]（Hα 玫红 656nm）与 [OIII_ARGB]（OIII
 *     青 500.7nm）fBm 权混场（[emissionLayerColor]——FogFlocFx.fbm01 单源
 *     驱动逐层混权，层间交错读作场）；电离侧亮暗=嵌入候选活跃度（R323 判据
 *     件 EmissionNebulaFx.isIonizingSource O/B 光谱型）驱动 alpha
 *     （[emissionAlpha01]）——光色由电离源温度决定，数据锚定；
 *   - 富集云=被反射星场色温从动（成员 tempK 均值走 TasteStarColor.blackbodyRgb
 *     ——判词一「光的颜色由温度与消光决定」）压暗 [REFLECT_DIM]；零 tempK
 *     证据=中性暗蓝灰诚实缺省（[ENRICH_NEUTRAL_ARGB]——没有温度证据不冒充
 *     彩色）；
 *   - 雾团与摇篮类别色=R235 与 R323 单源零改零接触。
 * - 诚实缺省（§3.2 终审修订版——两源各自诚实）：全库看完=摇篮云零（未探索
 *   场空=你的宇宙都点亮了，缺据不出）而富集微光仍在（热区场非空）；空库=
 *   两源全零；演示宇宙（MockTdGateway）预标定保底（[demoCalibration]=true
 *   时 [DEMO_SAMPLES] 固定样本表并入——演示专用负段 chatId 与固定口味位，
 *   真实库 TdLibGateway 永不触发）。
 * - 亮度预算表落常量（§4.3 克制档终稿——量级压缩注释自认：真实星云
 *   肉眼不可见，长曝光摄影才显形，本层为「梦幻感可读性优先」的量级压缩非真实
 *   亮度，与 R358 FOG_ALPHA_MAX=0.13 同判词）：未探索雾团 0.13（R358 定稿，
 *   丝缕承载前提）；发射摇篮联动约 0.21（EMISSION_ALPHA_CAP=两倍雾帽——
 *   R358 零碰）；复合体远景云气帽 [CPX_ALPHA_MAX]=0.12（本批）；富集反射
 *   云帽 [ENRICH_ALPHA_MAX]=0.08（终审增补——反射云暗于发射云=物理诚实：
 *   它只是反射星光）。三代病负锚同守（R252 大圆片/全屏光雾/大色块糊屏）：
 *   帽上叠加丝缕形态+丝间留空+近距渐出窗三重防线。
 *
 * 消费端：GL GlFrameBuilder.buildComplexesR373（远景档——复合体 blob 走雾族
 * NEBULA_FS 同管线 uniform 变体零新程序）。GL 帧预算=雾团行 MAX_NEBULAS=24
 * 之外独立预算行 ≤16 quads（复合体 ≤4×2~3 视差层+富集 ≤4 单层——ui 侧
 * MAX_NEBULA_CPX 帽，与雾行合并审计定总帽 40）。
 */
object DeepSpaceNebula {

    // ==================== 密度场常量（§3.2） ====================

    /** 密度场网格划分数（64×64——口味平面 1000 幅员；R235 雾团 8×8 同场高频段，本件低频段） */
    const val GRID_DIV = 64

    /** 高斯低通 σ（世界单位=口味平面单位，§3.2 原值——约四分之一幅员的星系级低频结构） */
    const val GAUSS_SIGMA = 250f

    /**
     * 金矿候选质量加权（§3.2「金矿候选聚集处=云最密处」——候选质量 3：一个候选
     * 的云密度贡献=三颗普通未探索星；非候选未探索星质量 1，已探索星摇篮场质量 0）。
     */
    const val GOLD_WEIGHT = 3f

    /** 摇篮复合体数帽（§3.2「按总质量留重 ≤4 团」——帧预算） */
    const val MAX_COMPLEXES = 4

    /** 富集反射云数帽（§十一 富集锚——与复合体同帽 4：双源并置对称的帧预算行） */
    const val MAX_ENRICH_CLOUDS = 4

    /** 摇篮场峰值相对门（密度 ≥ 峰值×0.55 过阈——相对结构门，任意库规模同判） */
    const val CPX_PEAK_GATE = 0.55f

    /**
     * 摇篮场平坦绝对门（密度 ≥ 均值×1.30——均匀场不出假云：σ=250 场的峰均比
     * 上限=4096×k2d(0)≈2.55；镜像守恒后均匀网格实测峰均比≈1.10（角星折返自
     * 叠）被 1.30 拒；倒过读=聚集团须占全库摇篮质量约五分之一以上才成云——
     * 「星系级结构」的诚实浓度门槛；实测对照：演示双簇 1.87/单簇 2.77 过门）。
     */
    const val CPX_FLAT_GATE = 1.30f

    /** 摇篮连通域最小总质量（<4 质量单位不成复合体——孤星不是星系级结构，诚实门槛） */
    const val CPX_MIN_MASS = 4f

    /** 富集场峰值相对门（高密度星区——与摇篮门同式） */
    const val ENRICH_PEAK_GATE = 0.60f

    /**
     * 富集场平坦绝对门（密度 ≥ 均值×1.20——同款均匀场防线；全星表基数大峰
     * 均比天然偏低（双簇热区实测 1.27~2.1 过门），门相应比摇篮侧宽一档）。
     */
    const val ENRICH_FLAT_GATE = 1.20f

    /** 富集连通域最小总质量（<8 颗星的散布不成「高密度星区」） */
    const val ENRICH_MIN_MASS = 8f

    /**
     * 连通域面积护栏（域格数 > 网格×此值=整图级病理域拒收）：σ=250 低频场的
     * **合法合并域可达 30~40% 网格面积**（复合体=复合结构——多簇在低频场阈下
     * 连通成一体是设计语义非病：实证 demo 双簇 578 单位间距在 55% 峰阈下连通
     * =1329 格=32.5%）；70% 只拦「整图糊」级病理域（近平坦场的全域连通），
     * 平坦场另有 [CPX_FLAT_GATE]/[ENRICH_FLAT_GATE] 第一道防线——本护栏是
     * 二道兜底非主闸。
     */
    const val REGION_CELL_FRAC = 0.70f

    // ==================== 亮度预算（§4.3——全表自认见类 KDoc） ====================

    /** 复合体远景云气帽 0.12（§4.3 克制档终稿——量级压缩注释自认见类 KDoc） */
    const val CPX_ALPHA_MAX = 0.12f

    /** 富集反射云帽 0.08（§十一 终审增补——反射云暗于发射云=物理诚实） */
    const val ENRICH_ALPHA_MAX = 0.08f

    /** 富集反射色温压暗系数（反射微光=星光的暗回声——暗于被反射星本体） */
    private const val REFLECT_DIM = 0.45f

    // ==================== 发射线色板（§4.1——零 hue(t)） ====================

    /** Hα 玫红（656.3nm 氢α 发射线——发射星云主导线之一；HSV 338° 固定档） */
    val HA_ARGB: Int = TasteStarColor.hsvToArgb(338f, 0.62f, 0.58f)

    /** OIII 青（500.7nm 双电离氧——发射星云与行星状星云配色主导线；HSV 172° 固定档） */
    val OIII_ARGB: Int = TasteStarColor.hsvToArgb(172f, 0.55f, 0.62f)

    /** 富集云零温度证据中性缺省（暗蓝灰——R235 CATEGORY_HUE_NONE 同诚实姿势） */
    val ENRICH_NEUTRAL_ARGB: Int = TasteStarColor.hsvToArgb(215f, 0.12f, 0.40f)

    // ==================== 半径幅员（世界单位——成员 sp 幅员涌现） ====================

    /** 复合体半径=成员 RMS 离心距 ×1.6（罩住成员又不过幅）钳 [CPX_R_MIN, CPX_R_MAX] */
    const val CPX_R_RMS_MULT = 1.6f

    /** 复合体世界半径下限（比雾团 240 大一个量级=「更大更稀」的复合体尺度下沿） */
    const val CPX_R_MIN = 800f

    /** 复合体世界半径上限（巨幅护栏——投影端另有 2.5×对角线巨雾护栏双保险） */
    const val CPX_R_MAX = 2200f

    /** 富集云半径=成员 RMS ×1.2（微光云区贴热区——比复合体克制）钳 [600, 1600] */
    const val ENRICH_R_RMS_MULT = 1.2f

    /** 富集云世界半径下限 */
    const val ENRICH_R_MIN = 600f

    /** 富集云世界半径上限 */
    const val ENRICH_R_MAX = 1600f

    // ==================== 近距渐出窗（P3 crossfade 的远景半边——单边落地） ====================

    /**
     * 近距渐出窗内沿（×半径）：P2 远景 billboard 在 0.8R 窗内渐出（design §5.2
     * 触发门的远景半边——P3 raymarch 落地后补齐互补渐入半边即成原式 crossfade；
     * 本批单边渐出零 FBO 零深度合成零对向层）。不渐出则飞近=整屏丝缕墙=R252
     * 「全屏光雾」负锚回潮，此窗是负锚防线非新语义。
     */
    const val NEAR_FADE_INNER = 0.10f

    /** 近距渐出窗外沿（×半径=0.8R——§5.2 触发门同值） */
    const val NEAR_FADE_OUTER = 0.80f

    // ==================== 帧输入（装配期一次烘焙——渲染期零计算除投影） ====================

    /**
     * 密度场原料（一颗在图频道）：[x] 与 [y]=口味平面位（密度场原料——R235 同场）；
     * [spX]/[spY]/[spZ]=世界真位（质心与半径消费——位置真源 agnostic：V3 还是
     * 涌现布局谁产的就跟谁）；[goldCandidate]=NEBULA_CANDIDATE 注记（质量加权）；
     * [recent7] 与 [lastActivityDays]=嵌入候选活跃度（电离侧亮暗原料——R323 判据
     * 件消费口径，装配期算好传入保持纯函数确定性）；[tempK]=富集反射色温原料
     * （0=无证据）。
     */
    data class StarIn(
        val chatId: Long,
        val x: Float,
        val y: Float,
        val spX: Float,
        val spY: Float,
        val spZ: Float,
        val watchSec: Long,
        val subscribed: Boolean,
        val goldCandidate: Boolean,
        val recent7: Int,
        val lastActivityDays: Long,
        val tempK: Float,
    )

    /**
     * 一团深空复合体（摇篮——不可变，装配期定死）：[cx]/[cy]/[cz]=世界质心（成员
     * sp 质量加权均值）；[radius]=世界半径（成员 RMS 幅员涌现）；[mass]=总质量
     * （诚实账——探针与留重依据）；[axisRad]=主轴角（口味平面 PCA——数据档案位，
     * P4 语义联动消费；视觉各向异性仍走 NEBULA_FS 种子基因零碰）；[cellKey]=
     * 质心格键（确定性身份锚——种子与排序原料）；[haMix01]=Hα 与 OIII 基础权混
     * 场权（格键哈希——发射线色板的复合体间异构）；[ion01]=电离侧亮度（嵌入
     * 候选活跃度 0..1——alpha 调制原料）；[members]=成员星数（诚实账）。
     */
    class Complex(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val radius: Float,
        val mass: Float,
        val axisRad: Float,
        val cellKey: Int,
        val haMix01: Float,
        val ion01: Float,
        val members: Int,
    )

    /**
     * 一片富集反射微光云（热区——终审增补）：位置与半径同复合体口径（成员 sp
     * 均值+RMS 幅员）；[colorArgb]=被反射星场色温（成员 tempK 均值黑体谱压暗；
     * 零证据=中性缺省）；[cellKey]=质心格键（身份锚）；[members]=成员星数。
     */
    class EnrichCloud(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val radius: Float,
        val colorArgb: Int,
        val cellKey: Int,
        val members: Int,
    )

    /** 装配产出（双源并置一次交付）：摇篮复合体+富集云+[cradleMass]（探针账） */
    class Result(
        val complexes: List<Complex>,
        val enrichClouds: List<EnrichCloud>,
        val cradleMass: Float,
    )

    /** 连通域留重条目（内部——质量降序+代表格键破平） */
    private class RegionCand<T>(val mass: Float, val key: Int, val item: T)

    // ==================== 主口（纯函数——确定性） ====================

    /**
     * 双源密度场 → 摇篮复合体+富集云（§3.2 与 §十一 富集锚）：
     *  1. 星表 chatId 升序（浮点求和顺序稳定=同数据同云铁律）；
     *  2. 摇篮场（未探索质量）与富集场（全星表质量）同走 [lowpassField]——同一
     *     低通管线两原料（类收敛）；
     *  3. 过阈（峰值相对门与平坦绝对门取大）连通域 → 按总质量留重（质量降序+
     *     格键破平）≤[MAX_COMPLEXES] 与 ≤[MAX_ENRICH_CLOUDS]；
     *  4. 每团：世界质心/半径（成员 sp 质量加权）+主轴+格键身份+发射线权混+
     *     电离侧活跃度；
     *  5. [demoCalibration]=true（演示宇宙——装配侧 gateway 判 MockTdGateway）时
     *     [DEMO_SAMPLES] 并入原料（预标定保底形态；真实库永不触发）。
     *
     * 诚实缺省：全库看完=摇篮 0 团而富集照算（两源各自诚实）；空库=双零；
     * 均匀场=平坦绝对门全拒（无聚集=无云）。
     */
    fun complexesOf(stars: List<StarIn>, demoCalibration: Boolean = false): Result {
        val src = when {
            !demoCalibration -> stars
            stars.isEmpty() -> DEMO_SAMPLES
            else -> stars + DEMO_SAMPLES
        }
        if (src.isEmpty()) return Result(emptyList(), emptyList(), 0f)
        // chatId 升序（确定性求和序——同数据同云；demo 样本负段 chatId 与真实
        // id 天然错段，混排稳定）
        val sorted = src.sortedBy { it.chatId }
        val n = sorted.size
        val cell = IntArray(n)
        val cradleMass = FloatArray(n)
        val enrichMass = FloatArray(n)
        var totalCradle = 0f
        val cellSize = 1000f / GRID_DIV
        for (i in 0 until n) {
            val s = sorted[i]
            val gx = (s.x / cellSize).toInt().coerceIn(0, GRID_DIV - 1)
            val gy = (s.y / cellSize).toInt().coerceIn(0, GRID_DIV - 1)
            cell[i] = gy * GRID_DIV + gx
            val m = cradleMassOf(s)
            cradleMass[i] = m
            enrichMass[i] = 1f
            totalCradle += m
        }
        // ---- 双源并置：同一低通管线两原料 ----
        val fieldA = lowpassField(cradleMass, cell, n)
        val fieldB = lowpassField(enrichMass, cell, n)
        val complexes = extractRegions(
            fieldA, cradleMass, cell, n, CPX_PEAK_GATE, CPX_FLAT_GATE, CPX_MIN_MASS, MAX_COMPLEXES,
        ) { members -> buildComplex(sorted, members) }
        val clouds = extractRegions(
            fieldB, enrichMass, cell, n, ENRICH_PEAK_GATE, ENRICH_FLAT_GATE, ENRICH_MIN_MASS, MAX_ENRICH_CLOUDS,
        ) { members -> buildEnrichCloud(sorted, members) }
        return Result(complexes, clouds, totalCradle)
    }

    // ==================== 消费口（GL 侧只画不算——单源公式） ====================

    /**
     * 近距渐出（0..1，§5.2 触发门远景半边）：dist ≥ 0.8R=满档远景；随接近在
     * [NEAR_FADE_INNER]R..[NEAR_FADE_OUTER]R 窗内 smoothstep 渐出（P3 raymarch
     * 落地后此窗即 crossfade 互补半边的接入位）。
     */
    fun nearFade01(dist: Float, radius: Float): Float {
        val r = radius.coerceAtLeast(1f)
        return smoothstep(NEAR_FADE_INNER * r, NEAR_FADE_OUTER * r, dist)
    }

    /**
     * R383 raymarch 体积渐入（0..1，§5.2 触发门贴脸半边——[nearFade01] 的互补
     * 函数）：**α 能量守恒互补 crossfade**——billboard 权=[nearFade01]、体积权=
     * 本函数，两权恒和为 1（同一 smoothstep 窗单一出处导出：总外轮廓恒定、
     * 帧间亮度跳变 ≤8%、C¹ 无迟滞——点→球 crossfade 同构，§4.3「等效帽 0.12
     * 与远景 crossfade 能量守恒」）。等效帽单源：体积 alpha 上限=[CPX_ALPHA_MAX]
     * 口（消费侧=emissionAlpha01 同帽×本权）。
     */
    fun volumeFade01(dist: Float, radius: Float): Float = 1f - nearFade01(dist, radius)

    /**
     * 复合体发射档 alpha（帽 [CPX_ALPHA_MAX]×电离侧亮度 0.55..1.0）：嵌入候选
     * 活跃度（[ion01]——R323 isIonizingSource O/B 判据件驱动）调制亮暗——零候选
     * =暗云（约 0.066），全员炽热=帽顶 0.12；帽恒不被电离增益越过（克制档）。
     */
    fun emissionAlpha01(ion01: Float): Float =
        CPX_ALPHA_MAX * (0.55f + 0.45f * ion01.coerceIn(0f, 1f))

    /** 富集反射云 alpha（帽 [ENRICH_ALPHA_MAX] 平档——反射恒暗于发射=物理诚实） */
    fun enrichAlpha01(): Float = ENRICH_ALPHA_MAX

    /**
     * 发射线层色（Hα 玫红与 OIII 青 fBm 权混场——§4.1）：[haMix01]=复合体基础权
     * （格键哈希）；[k]=视差层序——层间混权=fBm（FogFlocFx.fbm01 单源）抖动
     * ±0.3，多层交错读作逐块权混场（同程序 uniform 变体：色在装配与绘制侧变，
     * NEBULA_FS 零新 uniform——R358 锚守）。确定性（同键同层同色）。
     */
    fun emissionLayerColor(haMix01: Float, cellKey: Int, k: Int): Int {
        val w = (haMix01 + (FogFlocFx.fbm01(cellKey + 911, k * 1.9f + 0.7f) - 0.5f) * 0.6f)
            .coerceIn(0f, 1f)
        return TasteStarColor.blendRgb01(HA_ARGB, OIII_ARGB, w)
    }

    /**
     * 复合体 churn 种子（质心格键哈希——§4.2「种子=复合体质心格键哈希，R351
     * 确定性身份锚同构」）：格键×0.731+质量×0.137 折叠 Float（渲染端 FogFloc
     * 与 NEBULA_FS uSeed 各走各口——雾族同式）。
     */
    fun churnSeedOf(cellKey: Int, mass: Float): Float = cellKey * 0.731f + mass * 0.137f

    // ==================== R385 P4 语义联动口（design-nebula §3.3——P2 结论零碰只加函数） ====================

    /**
     * R385 所在复合体（§3.3-1「新星诞生演出起飞点=所在复合体」的容纳判定单口）：
     * 世界位距质心 <[Complex.radius] 的最近复合体（多团容纳取最近——确定性破平
     * 距离序）；**-1=不在任何云内**（诚实缺省：老星复活非云生→零起飞；面板
     * 候选计数同口消费）。纯函数零分配（复数团 ≤[MAX_COMPLEXES] 微成本）。
     */
    fun complexIndexOf(x: Float, y: Float, z: Float, complexes: List<Complex>): Int {
        var best = -1
        var bestD2 = Float.MAX_VALUE
        for (i in complexes.indices) {
            val c = complexes[i]
            val dx = x - c.cx
            val dy = y - c.cy
            val dz = z - c.cz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < c.radius * c.radius && d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        return best
    }

    /** 创世纪 gas 增浓系数帽（§3.3-3「早期=云更浓」——满档 +35%；总增益由消费端以 [CPX_ALPHA_MAX] 钳） */
    const val GENESIS_GAS_BOOST = 0.35f

    /**
     * R385 创世纪 gas 档乘子（§3.3-3「滑杆早期=云更浓，与 CMB 温度档同源联动」）：
     * 温度归一=[CmbBackdropFx.temp01Of] **单源消费**（同一光标→同一温度→同一 gas
     * 档——类收敛元规则一；r315 A①「CMB 是创世层不映射频道级数据」同判词：gas
     * 档只随光标不随库）。乘子 1..1+[GENESIS_GAS_BOOST]；延时摄影关/量程无效=1
     * 恒等（诚实缺省零行为）。消费端须以 [CPX_ALPHA_MAX] 钳——克制档帽不被创世纪
     * 越过（量级压缩自认：真实早期宇宙 gas fraction 接近 1，本档是「更浓可读」的
     * 压缩非写实）。「云先凝、星后亮」：早期 gas fraction 高=摇篮云更浓，星随光标
     * 逐颗点亮（bornVisible 既有零接触）。
     */
    fun genesisGasMult01(genesisOn: Boolean, genesisT01: Float): Float =
        1f + GENESIS_GAS_BOOST * CmbBackdropFx.temp01Of(genesisOn, genesisT01)

    /** 创世纪反射云黯淡地板（0.15——星未亮的早期反射云近熄未灭：气体还在只是没光可反射） */
    const val GENESIS_REFLECT_FLOOR = 0.15f

    /**
     * R385 创世纪反射云乘子（富集微光=星光的暗回声——星未亮的早期 temp01→1 黯至
     * [GENESIS_REFLECT_FLOOR]）：反射云不随 gas 档增浓（它的亮来自星光非气体自重
     * ——诚实：不为不存在的星光造亮；地板>0=云本体在场只是未被照亮，「星后亮」
     * 的另一半叙事）。延时摄影关=1 恒等。
     */
    fun genesisReflectMult01(genesisOn: Boolean, genesisT01: Float): Float =
        1f - (1f - GENESIS_REFLECT_FLOOR) * CmbBackdropFx.temp01Of(genesisOn, genesisT01)

    /** R385 平衡位移滑移窗（毫秒——R303c 口径：数据换代云形 2s 滑入不跳变） */
    const val SLIDE_MS = 2000L

    /**
     * R385 平衡位移滑移（§3.3-3「数据变化走装配期重算+2s 平衡位移滑移（R303c
     * 口径），diff 非静默」）：数据换代后 [t01]∈0..1 窗内复合体按格键身份锚
     * （[Complex.cellKey]——质心格键确定性身份）匹配新旧——**质心/半径线性滑移**
     * （平衡位不跳变）；新云半径自 0 凝出（「云先凝」的字面演出）；消散云即刻
     * 退场（由 UI 侧 diff 探针非静默宣判——数据换代有账可查）。prev=null 或
     * t01≥1=恒等内容直通（调用方帧路径自行短路免分配——本函数防御性两档仍
     * 成立）。C1 smoothstep 缓动；确定性（同输入同滑移）。
     */
    fun slideOf(
        curCpx: List<Complex>,
        curClouds: List<EnrichCloud>,
        prev: Result?,
        t01: Float,
    ): Result {
        if (prev == null) return Result(curCpx, curClouds, 0f)
        val t = t01.coerceIn(0f, 1f)
        if (t >= 1f) return Result(curCpx, curClouds, 0f)
        val e = t * t * (3f - 2f * t)
        val cpx = ArrayList<Complex>(curCpx.size)
        for (c in curCpx) {
            val p = prev.complexes.firstOrNull { it.cellKey == c.cellKey }
            cpx += if (p == null) {
                // 新云：半径自 0 凝出（mass/ion01 等语义字段即当代真值）
                Complex(c.cx, c.cy, c.cz, c.radius * e, c.mass, c.axisRad, c.cellKey, c.haMix01, c.ion01, c.members)
            } else {
                Complex(
                    p.cx + (c.cx - p.cx) * e,
                    p.cy + (c.cy - p.cy) * e,
                    p.cz + (c.cz - p.cz) * e,
                    p.radius + (c.radius - p.radius) * e,
                    c.mass, c.axisRad, c.cellKey, c.haMix01, c.ion01, c.members,
                )
            }
        }
        val clouds = ArrayList<EnrichCloud>(curClouds.size)
        for (c in curClouds) {
            val p = prev.enrichClouds.firstOrNull { it.cellKey == c.cellKey }
            clouds += if (p == null) {
                EnrichCloud(c.cx, c.cy, c.cz, c.radius * e, c.colorArgb, c.cellKey, c.members)
            } else {
                EnrichCloud(
                    p.cx + (c.cx - p.cx) * e,
                    p.cy + (c.cy - p.cy) * e,
                    p.cz + (c.cz - p.cz) * e,
                    p.radius + (c.radius - p.radius) * e,
                    c.colorArgb, c.cellKey, c.members,
                )
            }
        }
        return Result(cpx, clouds, prev.cradleMass)
    }

    // ==================== 摇篮质量单口（场数组与建团共用——零复制） ====================

    /** 摇篮质量（未探索 1/候选 3/已探索 0——§3.2 原料口径单一出处） */
    private fun cradleMassOf(s: StarIn): Float =
        if (s.watchSec == 0L && !s.subscribed) (if (s.goldCandidate) GOLD_WEIGHT else 1f) else 0f

    // ==================== 低通管线（同一函数两原料——类收敛单源） ====================

    /** 网格总数（64×64） */
    private const val N_CELLS = GRID_DIV * GRID_DIV

    /** 连通域面积护栏绝对值（网格×[REGION_CELL_FRAC]） */
    private val REGION_CELL_MAX = (N_CELLS * REGION_CELL_FRAC).toInt()

    /**
     * 质量场低通（64×64 splat+可分离高斯 σ=[GAUSS_SIGMA]（格宽 15.625→σ≈16 格，
     * 核半径 2σ=32 格=65 抽头）：JVM 纯算 O(n)+两趟 4096×65——665 星全图毫秒级
     * （装配期一次，remember(mapData) 键控数据换代重算）。核归一 Σ=1 且**边界
     * 镜像反射**（越界折返对称位——质量守恒：低通只重分布不损耗；clamp 截断
     * 会把边缘星 33% 质量漏出平面外压塌均值=均匀场假云，实证见 r373_notes）。
     */
    private fun lowpassField(mass: FloatArray, cell: IntArray, n: Int): FloatArray {
        val raw = FloatArray(N_CELLS)
        for (i in 0 until n) raw[cell[i]] += mass[i]
        val kern = LOWPASS_KERNEL
        val taps = kern.size
        val half = taps / 2
        // 反射索引表（[−half, GRID_DIV−1+half] → [0, GRID_DIV−1] 镜像折返）
        val refl = IntArray(GRID_DIV + taps)
        for (i in refl.indices) {
            val v = i - half
            refl[i] = if (v < 0) -v else if (v >= GRID_DIV) 2 * GRID_DIV - 2 - v else v
        }
        val tmp = FloatArray(N_CELLS)
        // 横向趟（行内卷积，边界镜像）
        for (gy in 0 until GRID_DIV) {
            val row = gy * GRID_DIV
            for (gx in 0 until GRID_DIV) {
                var acc = 0f
                val base = gx
                for (t in 0 until taps) {
                    acc += raw[row + refl[base + t]] * kern[t]
                }
                tmp[row + gx] = acc
            }
        }
        // 纵向趟
        val out = FloatArray(N_CELLS)
        for (gx in 0 until GRID_DIV) {
            for (gy in 0 until GRID_DIV) {
                var acc = 0f
                val base = gy
                for (t in 0 until taps) {
                    acc += tmp[refl[base + t] * GRID_DIV + gx] * kern[t]
                }
                out[gy * GRID_DIV + gx] = acc
            }
        }
        return out
    }

    /** 低通核（σ=250 世界单位折格宽 15.625→16 格，半径 2σ=32 格→65 抽头；Σ归一） */
    private val LOWPASS_KERNEL: FloatArray = run {
        val sigmaCells = GAUSS_SIGMA / (1000f / GRID_DIV)
        val half = (2f * sigmaCells).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * half + 1)
        var sum = 0f
        for (i in k.indices) {
            val d = (i - half).toFloat()
            val v = exp(-(d * d) / (2f * sigmaCells * sigmaCells))
            k[i] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        k
    }

    // ==================== 连通域提取（确定性） ====================

    /**
     * 过阈连通域提取（4 连通 BFS 行优先）+按总质量留重（质量降序→代表格键升序
     * 破平）→逐域 [build] 出对象（null=诚实跳过，如退化质心）。域成员星=星表
     * （chatId 序）中格位落在域格集内者——每团云可溯源到「你的金矿候选聚集」。
     */
    private fun <T> extractRegions(
        field: FloatArray,
        mass: FloatArray,
        cell: IntArray,
        n: Int,
        peakGate: Float,
        flatGate: Float,
        minMass: Float,
        cap: Int,
        build: (members: IntArray) -> T?,
    ): List<T> {
        var peak = 0f
        var sum = 0f
        for (v in field) {
            if (v > peak) peak = v
            sum += v
        }
        if (peak <= 0f) return emptyList() // 空场诚实（全探索完或空库——摇篮源）
        val mean = sum / field.size
        val threshold = maxOf(peak * peakGate, mean * flatGate)
        if (threshold >= peak) return emptyList() // 平坦场全拒（均匀=无聚集=无云）
        val mask = BooleanArray(N_CELLS)
        for (i in 0 until N_CELLS) mask[i] = field[i] >= threshold
        val visited = BooleanArray(N_CELLS)
        val stack = IntArray(N_CELLS)
        val regionMask = BooleanArray(N_CELLS)
        val ranked = ArrayList<RegionCand<T>>(cap)
        for (seed in 0 until N_CELLS) {
            if (!mask[seed] || visited[seed]) continue
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            regionMask.fill(false)
            var minCell = seed
            var cellsN = 0
            while (sp > 0) {
                val c = stack[--sp]
                regionMask[c] = true
                cellsN++
                if (c < minCell) minCell = c
                val cx = c % GRID_DIV
                val cy = c / GRID_DIV
                if (cx > 0 && mask[c - 1] && !visited[c - 1]) { visited[c - 1] = true; stack[sp++] = c - 1 }
                if (cx < GRID_DIV - 1 && mask[c + 1] && !visited[c + 1]) { visited[c + 1] = true; stack[sp++] = c + 1 }
                if (cy > 0 && mask[c - GRID_DIV] && !visited[c - GRID_DIV]) { visited[c - GRID_DIV] = true; stack[sp++] = c - GRID_DIV }
                if (cy < GRID_DIV - 1 && mask[c + GRID_DIV] && !visited[c + GRID_DIV]) { visited[c + GRID_DIV] = true; stack[sp++] = c + GRID_DIV }
            }
            // 面积护栏：整图级病理域拒收（二道兜底——主闸是平坦绝对门；合法合并
            // 域 30~40% 见 REGION_CELL_FRAC KDoc 实证账）
            if (cellsN > REGION_CELL_MAX) continue
            // 成员星（chatId 序遍历=确定性；域内格集判定）
            var regionMass = 0f
            var mCount = 0
            for (i in 0 until n) {
                if (regionMask[cell[i]]) mCount++
            }
            if (mCount <= 0) continue
            val members = IntArray(mCount)
            var w = 0
            for (i in 0 until n) {
                if (regionMask[cell[i]]) {
                    members[w++] = i
                    regionMass += mass[i]
                }
            }
            if (regionMass < minMass) continue // 诚实门槛：质量不足不成云
            val item = build(members) ?: continue
            ranked += RegionCand(regionMass, minCell, item)
        }
        if (ranked.isEmpty()) return emptyList()
        ranked.sortWith(compareByDescending<RegionCand<T>> { it.mass }.thenBy { it.key })
        val out = ArrayList<T>(cap)
        for (r in ranked) {
            if (out.size >= cap) break
            out += r.item
        }
        return out
    }

    // ==================== 建团（质心/半径/主轴/身份/色板锚） ====================

    /** 摇篮复合体建团（成员=域内摇篮质量>0 星；世界质心 sp 加权+RMS 半径+PCA 主轴+质心格键身份） */
    private fun buildComplex(sorted: List<StarIn>, members: IntArray): Complex? {
        var mass = 0f
        var sx = 0f
        var sy = 0f
        var sz = 0f
        var tx = 0f
        var ty = 0f
        var count = 0
        for (i in members) {
            val s = sorted[i]
            val m = cradleMassOf(s)
            if (m <= 0f) continue // 已探索星非摇篮成员（富集源另有其云）
            mass += m
            count++
            sx += m * s.spX
            sy += m * s.spY
            sz += m * s.spZ
            tx += m * s.x
            ty += m * s.y
        }
        if (mass <= 0f || count <= 0) return null
        val cx = sx / mass
        val cy = sy / mass
        val cz = sz / mass
        if (sqrt(cx * cx + cy * cy + cz * cz) < 1e-3f) return null // 退化世界锚（无真位）诚实跳过
        // RMS 幅员（质量加权——罩住成员）
        var rms = 0f
        for (i in members) {
            val s = sorted[i]
            val m = cradleMassOf(s)
            if (m <= 0f) continue
            val dx = s.spX - cx
            val dy = s.spY - cy
            val dz = s.spZ - cz
            rms += m * (dx * dx + dy * dy + dz * dz)
        }
        rms = sqrt(rms / mass)
        val radius = (CPX_R_RMS_MULT * rms).coerceIn(CPX_R_MIN, CPX_R_MAX)
        // 口味质心+主轴（PCA——数据档案位）
        val mcx = tx / mass
        val mcy = ty / mass
        var vxx = 0f
        var vyy = 0f
        var vxy = 0f
        for (i in members) {
            val s = sorted[i]
            val m = cradleMassOf(s)
            if (m <= 0f) continue
            val dx = s.x - mcx
            val dy = s.y - mcy
            vxx += m * dx * dx
            vyy += m * dy * dy
            vxy += m * dx * dy
        }
        val axisRad = 0.5f * atan2(2f * vxy, vxx - vyy)
        val cellSize = 1000f / GRID_DIV
        val key = mcyCellKey(mcx, mcy, cellSize)
        // 电离侧活跃度（嵌入候选 O/B 判据件——R323 单口）
        var cand = 0
        var ob = 0
        for (i in members) {
            val s = sorted[i]
            if (!s.goldCandidate || cradleMassOf(s) <= 0f) continue
            cand++
            if (EmissionNebulaFx.isIonizingSource(s.recent7, s.lastActivityDays)) ob++
        }
        val ion01 = if (cand > 0) (ob.toFloat() / cand).coerceIn(0f, 1f) else 0f
        return Complex(
            cx, cy, cz, radius, mass, axisRad, key,
            TasteSpace.h01(key.toLong(), 913), ion01, count,
        )
    }

    /** 富集反射云建团（成员=域内全部星——含已探索；色=被反射星场色温从动） */
    private fun buildEnrichCloud(sorted: List<StarIn>, members: IntArray): EnrichCloud? {
        var count = 0
        var sx = 0f
        var sy = 0f
        var sz = 0f
        var tx = 0f
        var ty = 0f
        var tempSum = 0f
        var tempN = 0
        for (i in members) {
            val s = sorted[i]
            count++
            sx += s.spX
            sy += s.spY
            sz += s.spZ
            tx += s.x
            ty += s.y
            if (s.tempK > 0f) {
                tempSum += s.tempK
                tempN++
            }
        }
        if (count <= 0) return null
        val cx = sx / count
        val cy = sy / count
        val cz = sz / count
        if (sqrt(cx * cx + cy * cy + cz * cz) < 1e-3f) return null
        var rms = 0f
        for (i in members) {
            val s = sorted[i]
            val dx = s.spX - cx
            val dy = s.spY - cy
            val dz = s.spZ - cz
            rms += dx * dx + dy * dy + dz * dz
        }
        rms = sqrt(rms / count)
        val radius = (ENRICH_R_RMS_MULT * rms).coerceIn(ENRICH_R_MIN, ENRICH_R_MAX)
        val cellSize = 1000f / GRID_DIV
        val key = mcyCellKey(tx / count, ty / count, cellSize)
        val color = if (tempN > 0) {
            rgbScale(TasteStarColor.blackbodyRgb(tempSum / tempN), REFLECT_DIM)
        } else {
            ENRICH_NEUTRAL_ARGB
        }
        return EnrichCloud(cx, cy, cz, radius, color, key, count)
    }

    /** 质心格键（口味质心落格行优先键——确定性身份锚） */
    private fun mcyCellKey(mcx: Float, mcy: Float, cellSize: Float): Int {
        val gx = (mcx / cellSize).toInt().coerceIn(0, GRID_DIV - 1)
        val gy = (mcy / cellSize).toInt().coerceIn(0, GRID_DIV - 1)
        return gy * GRID_DIV + gx
    }

    /** RGB 等比压暗（反射微光——色温从动只压亮度不动色相） */
    private fun rgbScale(argb: Int, f: Float): Int {
        val r = (((argb shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** smoothstep（C1 连续——近距渐出窗的数学件） */
    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (b <= a) return if (x >= b) 1f else 0f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ==================== 演示宇宙预标定样本（MockTdGateway 保底——真实库永不触发） ====================

    /**
     * 演示样本表（§3.2「演示宇宙（MockTdGateway）预标定保底」）：固定 40 颗
     * 预标定样本（chatId 负段 -8801e12 区隔真实 id；世界位走 spherifyV3 同口径
     * 数学——与真实星同一布局族）。摇篮簇两处（其一 5 候选含 2 颗 O/B 炙热源
     * 驱动电离亮暗展示位）+热区一处（16 已探索星色温 3200~9800K——富集反射
     * 色温从动展示位）+散布星。诚实红线：仅 [complexesOf](demoCalibration=true)
     * 装配侧（gateway 判 MockTdGateway）可触发；真实库（TdLibGateway）永不
     * 掺入。确定性（零 Random——同调同表）。
     */
    private val DEMO_SAMPLES: List<StarIn> by lazy { buildDemoSamples() }

    /** 演示样本 chatId 基（负段远区隔——真实 TG id 同号段不撞） */
    private const val DEMO_ID_BASE = -8_801_000_000_000L

    private fun buildDemoSamples(): List<StarIn> {
        val out = ArrayList<StarIn>(44)
        val sp = FloatArray(3)
        // 世界位=V3 同口径（口味角+哈希 φ+体量半径——与真实星同布局数学）
        fun demo(
            id: Long, x: Float, y: Float, vol01: Float,
            explored: Boolean, gold: Boolean, recent7: Int, lastActDays: Long, tempK: Float,
        ) {
            val theta = atan2(-(y - 500f), x - 500f)
            TasteSpace.spherifyV3(theta, id, vol01, sp)
            out += StarIn(
                id, x, y, sp[0], sp[1], sp[2],
                if (explored) 3600L else 0L, explored, gold, recent7, lastActDays, tempK,
            )
        }
        fun jitter(id: Long, ch: Int, span: Float): Float =
            (TasteSpace.h01(id, ch) - 0.5f) * span
        // ---- 摇篮簇 A（主保底）：14 未探索+5 候选（recent7 12/11=O/B 炙热、8/3=温）----
        for (i in 0 until 14) {
            val id = DEMO_ID_BASE + i
            val gold = i < 5
            val recent7 = when (i) {
                0 -> 12
                1 -> 11
                2 -> 8
                3 -> 3
                else -> -1
            }
            demo(
                id, 700f + jitter(id, 31, 130f), 350f + jitter(id, 32, 130f),
                0.30f + 0.02f * i, false, gold,
                if (gold) recent7 else -1, if (gold) 2L else 45L, 0f,
            )
        }
        // ---- 摇篮簇 B：8 未探索+2 候选（温档）----
        for (i in 0 until 8) {
            val id = DEMO_ID_BASE + 100 + i
            val gold = i < 2
            demo(
                id, 240f + jitter(id, 33, 120f), 700f + jitter(id, 34, 120f),
                0.34f + 0.02f * i, false, gold,
                if (gold) 6 else -1, if (gold) 5L else 60L, 0f,
            )
        }
        // ---- 热区（富集保底）：16 已探索星，色温 3200~9800K ----
        for (i in 0 until 16) {
            val id = DEMO_ID_BASE + 200 + i
            demo(
                id, 430f + jitter(id, 35, 110f), 560f + jitter(id, 36, 110f),
                0.45f + 0.02f * i, true, false, 4 + i % 7, 3L,
                3200f + 412f * i,
            )
        }
        // ---- 全场散布：6 已探索+2 未探索（背景密度——热区相对性原料） ----
        for (i in 0 until 6) {
            val id = DEMO_ID_BASE + 300 + i
            demo(
                id, 120f + 130f * i, 880f - jitter(id, 37, 90f),
                0.5f, true, false, 2, 12L, 4300f + 700f * i,
            )
        }
        for (i in 0 until 2) {
            val id = DEMO_ID_BASE + 400 + i
            demo(
                id, 880f - 60f * i, 120f + 50f * i, 0.4f, false, false, -1, 90L, 0f,
            )
        }
        return out
    }
}
