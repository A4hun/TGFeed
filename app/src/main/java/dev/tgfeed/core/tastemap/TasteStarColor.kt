package dev.tgfeed.core.tastemap

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * R311 色彩引擎纯函数（恒星质感+色彩引擎·零 Android 依赖，单测可跑）——用户实锤
 * 「一团气」「千篇一律」+「年龄越大的恒星颜色越红」+「数据量越大恒星越热（偏暖/亮），
 * 数据少越冷」的成文落法。**黑体色温谱/三轴色温/构成微调/造父变星/行星色全部
 * 单一口径**，Canvas（TasteFlightRenderer）与 GL（GlFrameBuilder）双端同调——
 * 镜像纪律收敛：黑体谱只此一份（改即双端同改，不再有 GlPalette 式镜像漂移）。
 *
 * - **黑体色温谱**（[blackbodyRgb]）：kelvin→ARGB 连续值（Tanner Helland 式分段
 *   近似，钳域 [TEMP_MIN_K, TEMP_MAX_K]——2400K 暗红→5800K 太阳黄白→11800K 蓝白炽）。
 *   连续值=「两颗星不撞色」的机制（离散档必撞色，连续谱天然颗颗有别）。
 * - **恒星色温=三轴合成连续值**（[starKelvinOf]）：
 *   活跃轴（活跃率→基础温度——爆发蓝白炽/停更暗红）；年龄轴（firstSeen 距今——
 *   越老越红，用户原话口径）；体量轴（ln1p 体量归一→大频道热亮蓝白/小频道冷暗红，
 *   用户 21:05 双轴令：大小+冷暖双轴说话）；chatId 微抖 ±[JITTER_K]（同参数星
 *   亦连续可分——哈希确定，每颗星色温独特）。
 * - **恒星三段色连续导出**（[coreColorOfK]/[edgeColorOfK]）：芯=黑体(T×1.35 更热
 *   更蓝白)、盘面=黑体(T)×构成色微调 [CONTENT_TINT_MIX]、缘=黑体(T×0.60)（临边
 *   =视线穿透更冷的高层光球=更红更暗——真实恒星照的成因）。
 * - **行星色=内容构成**（[planetColorOf]）：tag 条目 mediaType 三票圆插值
 *   （视频暖 [PLANET_HUE_VIDEO] / 图片冷 [PLANET_HUE_PHOTO] / 音频紫
 *   [PLANET_HUE_AUDIO]——「一眼读出这颗行星什么料」）+宿主恒星光微调
 *   [PLANET_STAR_TINT_MIX]（行星反照星光染色——物理说得通）+tag 哈希次级抖动。
 * - **造父变星真光变**（[cepheidOf]/[cepheidShape]）：周期性更新频道=变星——
 *   P_real=频道平均更新间隔（天），P_vis=保序映射到秒级搏动周期；**周期-光度
 *   关系**（真造父：P 长=光度大→振幅大）；**快升慢降不对称波形**（升相
 *   [CEPHEID_RISE_FRAC]≈28% 周期=真造父光变曲线形）。数据缺省=非变星（诚实）。
 *
 * 工程纪律：全函数零分配（除显式 new 对象出口）、确定性（同输入同输出——哈希
 * 微抖非随机）、60fps 渲染期消费的只有 [cepheidShape]/[blackbodyRgb] 级的纯浮点。
 */
object TasteStarColor {

    // ==================== 黑体色温谱 ====================

    /** 色温钳域下限（K）：暗红冷星（M9 矮星档） */
    const val TEMP_MIN_K = 2400f

    /** 色温钳域上限（K）：蓝白炽星（B/A 型档——再热人眼读不出更蓝） */
    const val TEMP_MAX_K = 11800f

    /** 同参数星防撞色微抖幅度（±K，chatId 哈希驱动，确定性） */
    private const val JITTER_K = 90f

    /**
     * 黑体色温 → ARGB（Tanner Helland 式分段近似—— kelvin∈[1000K,40000K] 域的
     * 工程近似；本引擎钳 [TEMP_MIN_K, TEMP_MAX_K]。R/G 对数曲线+B 对数曲线，
     * 连续输入连续输出=两颗星色温永不撞色）。
     */
    fun blackbodyRgb(kelvin: Float): Int {
        val t = (kelvin.coerceIn(1000f, 40000f)) / 100f
        // R：≤66 满档；>66 对数衰减
        val r = if (t <= 66f) 255f
        else (329.6987f * (t - 60f).pow(-0.1332f)).coerceIn(0f, 255f)
        // G：≤66 对数上升；>66 对数缓降
        val g = if (t <= 66f) (99.4708f * ln(t) - 161.1196f).coerceIn(0f, 255f)
        else (288.1222f * (t - 60f).pow(-0.0755f)).coerceIn(0f, 255f)
        // B：≥66 满档；≤19 零；其间对数上升
        val b = when {
            t >= 66f -> 255f
            t <= 19f -> 0f
            else -> (138.5177f * ln(t - 10f) - 305.0448f).coerceIn(0f, 255f)
        }
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    // ==================== 恒星色温（三轴合成） ====================

    /** 活跃轴基础温度带（K）：活跃爆发=蓝白炽 / 死寂=暗红 */
    private const val ACT_TEMP_LOW = 3050f
    private const val ACT_TEMP_HIGH = 10600f

    /** 年龄轴最大红移量（K）：越老越红（用户原话「年龄越大的恒星颜色越红」） */
    private const val AGE_SHIFT_K = 2400f

    /** 年龄轴满档天数（≈3 年——TG 频道生态的「老年」量程） */
    private const val AGE_FULL_DAYS = 1095f

    /** 体量轴偏移量（±K）：大频道热亮蓝白/小频道冷暗红（用户 21:05 双轴令） */
    private const val VOLUME_SHIFT_K = 2600f

    /**
     * 恒星色温（K，连续值——三轴合成）：
     *  - [activity01]：活跃率 0..1（金矿 recent7/14 钳；无证据调用方先按断更天数
     *    回落——与 TasteAstroPhysics.spectralClassOf 同口径的连续化）；
     *  - [ageDays]：频道首见距今天数（<0=无证据=年龄轴中立不偏移——无证据不冒充古老）；
     *  - [volume01]：体量归一 0..1（ln1p(vol)/ln1p(max) 对数口径——天文质量对数感）；
     *  - [chatId]：防撞色微抖种子（±[JITTER_K]，确定性哈希）。
     */
    fun starKelvinOf(activity01: Float, ageDays: Float, volume01: Float, chatId: Long): Float {
        val act = activity01.coerceIn(0f, 1f)
        var k = ACT_TEMP_LOW + (ACT_TEMP_HIGH - ACT_TEMP_LOW) * act
        if (ageDays >= 0f) {
            k -= AGE_SHIFT_K * (ageDays / AGE_FULL_DAYS).coerceIn(0f, 1f)
        }
        k += VOLUME_SHIFT_K * (volume01.coerceIn(0f, 1f) - 0.5f)
        // 微抖：同参数星亦连续可分（确定性——chatId 哈希）
        k += (h01Long(chatId, 911) * 2f - 1f) * JITTER_K
        return k.coerceIn(TEMP_MIN_K, TEMP_MAX_K)
    }

    /** 活跃率原料（金矿 recent7→0..1；与渲染层 grainAmp/omegaRot 的 recent7/14 同口径） */
    fun activity01Of(recent7: Int): Float =
        if (recent7 > 0) (recent7 / 14f).coerceIn(0f, 1f) else 0f

    /**
     * 无金矿证据的活跃率回落（断更天数——spectralClassOf 同口径连续化：
     * <7 天≈0.50 / <60 天≈0.28 / <180 天≈0.12 / 更久 0.03；<0=未知给中性 0.35）。
     */
    fun activityFallback01(lastActivityDays: Long): Float = when {
        lastActivityDays < 0L -> 0.35f
        lastActivityDays < 7L -> 0.50f
        lastActivityDays < 60L -> 0.28f
        lastActivityDays < 180L -> 0.12f
        else -> 0.03f
    }

    /** 体量归一（ln1p 对数口径——与 mass01Of 同哲学；maxVol≤0=无统计=中立 0.5） */
    fun volume01Of(vol: Int, maxVol: Int): Float =
        if (vol > 0 && maxVol > 0) ln1p(vol.toFloat()) / ln1p(maxVol.toFloat()) else 0.5f

    // ==================== 恒星三段色（连续导出——旧三档 temp 桶在主序星上退役） ====================

    /** 芯色倍率（芯=更热更蓝白——光球深层温度更高） */
    private const val CORE_TEMP_MUL = 1.35f

    /** 缘色倍率（临边=更冷更红——视线斜穿高层冷光球） */
    private const val EDGE_TEMP_MUL = 0.60f

    /** 构成色微调混合比（「星的颜色=频道内容的颜色」——微调非夺主：色温主轴不动） */
    private const val CONTENT_TINT_MIX = 0.22f

    // ---- 柔光档（R152c 真机反馈「纯饱和色廉价」的保留：黑体谱出口统一降饱和+
    //      明度带——色温主轴（色相）不动，观感留「暗夜柔光」档） ----
    private const val STAR_SAT_MAX = 0.58f
    private const val STAR_SAT_MIN = 0.16f
    private const val STAR_VAL_MIN = 0.66f
    private const val STAR_VAL_MAX = 0.92f

    /** 芯色（黑体 T×[CORE_TEMP_MUL]） */
    fun coreColorOfK(kelvin: Float): Int = blackbodyRgb(kelvin * CORE_TEMP_MUL)

    /** 缘色（黑体 T×[EDGE_TEMP_MUL]——临边昏暗的色相伴生） */
    fun edgeColorOfK(kelvin: Float): Int = blackbodyRgb(kelvin * EDGE_TEMP_MUL)

    /**
     * 恒星盘面色（ARGB）：黑体(T) × 内容构成色微调 [CONTENT_TINT_MIX]（RGB 线性混）
     * → **柔光档**（S 钳 [STAR_SAT_MIN, STAR_SAT_MAX]、V 钳 [STAR_VAL_MIN,
     * STAR_VAL_MAX]——纯饱和黑体色直接上屏太「廉价糖果」，降饱和留色相=色温轴
     * 可读且与宇宙暗夜底不违和）。
     *  [contentArgb]≤0 / 低饱和=无构成证据 → 纯黑体色走柔光档（诚实不染色）。
     */
    fun starColorOfK(kelvin: Float, contentArgb: Int): Int {
        val base = blackbodyRgb(kelvin)
        val mixed = if (contentArgb ushr 24 == 0) base else blendRgb01(base, contentArgb, CONTENT_TINT_MIX)
        return softGrade(mixed)
    }

    /** 柔光档（HSV 域 S/V 钳制，色相不动——恒星色出口的统一观感闸） */
    fun softGrade(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b).toFloat()
        val min = minOf(r, g, b).toFloat()
        val v = max / 255f
        val s = if (max <= 0f) 0f else (max - min) / max
        return hsvToArgb(
            hueOfArgb(argb),
            s.coerceIn(STAR_SAT_MIN, STAR_SAT_MAX),
            v.coerceIn(STAR_VAL_MIN, STAR_VAL_MAX),
        )
    }

    /**
     * 内容构成色（索引基因三票 v/p/a 圆插值——20°/125°/265° 三基相与 R152c
     * 同源；全零票返回 0=无构成哨兵）。饱和/明度柔光档（0.50/0.80）。
     */
    fun compositionArgb(v: Long, p: Long, a: Long): Int {
        val sum = v + p + a
        if (sum <= 0L) return 0
        val rad = Math.PI / 180.0
        val hx = v * kotlin.math.sin(HUE_VIDEO * rad) + p * kotlin.math.sin(HUE_PHOTO * rad) + a * kotlin.math.sin(HUE_AUDIO * rad)
        val hy = v * kotlin.math.cos(HUE_VIDEO * rad) + p * kotlin.math.cos(HUE_PHOTO * rad) + a * kotlin.math.cos(HUE_AUDIO * rad)
        var hue = Math.toDegrees(kotlin.math.atan2(hx, hy)).toFloat()
        if (hue < 0f) hue += 360f
        return hsvToArgb(hue, 0.50f, 0.80f)
    }

    /** 内容构成三基相（度）——R152c 星色同源（视频橙/图文绿/音频紫） */
    private const val HUE_VIDEO = 20f
    private const val HUE_PHOTO = 125f
    private const val HUE_AUDIO = 265f

    // ==================== 行星色（内容构成+恒星光染色） ====================

    /** 行星构成基相（度）：视频=暖橙 / 图片=冷蓝 / 音频=紫 */
    private const val PLANET_HUE_VIDEO = 24f
    private const val PLANET_HUE_PHOTO = 208f
    private const val PLANET_HUE_AUDIO = 274f

    /** 宿主恒星光染色混合比（行星反照率里带恒星色温——同系行星一族色） */
    private const val PLANET_STAR_TINT_MIX = 0.18f

    /** 行星哈希次级抖动（±度——同构成行星颗颗有别，防读错类型的幅度内） */
    private const val PLANET_HUE_JITTER = 10f

    /**
     * 行星色（ARGB）：tag 内容构成三票（[vCount]/[pCount]/[aCount]=该 tag 条目
     * mediaType 计数）圆插值定色相，全零票=回退恒星光中性带（恒星色降饱和版——
     * 行星不发光=反射恒星光，缺据不发明颜色）；宿主星光微调
     * [PLANET_STAR_TINT_MIX]+tag 哈希 ±[PLANET_HUE_JITTER]°；柔光档 S/V 钳
     * （行星明度天花板远低于恒星——bloom 阈纪律不破）。
     */
    fun planetColorOf(vCount: Int, pCount: Int, aCount: Int, starArgb: Int, tagSeed: Int): Int {
        val sum = vCount + pCount + aCount
        val rad = Math.PI / 180.0
        val hue: Float
        if (sum > 0) {
            val hx = vCount * kotlin.math.sin(PLANET_HUE_VIDEO * rad) +
                pCount * kotlin.math.sin(PLANET_HUE_PHOTO * rad) +
                aCount * kotlin.math.sin(PLANET_HUE_AUDIO * rad)
            val hy = vCount * kotlin.math.cos(PLANET_HUE_VIDEO * rad) +
                pCount * kotlin.math.cos(PLANET_HUE_PHOTO * rad) +
                aCount * kotlin.math.cos(PLANET_HUE_AUDIO * rad)
            var h0 = Math.toDegrees(kotlin.math.atan2(hx, hy)).toFloat()
            if (h0 < 0f) h0 += 360f
            hue = h0
        } else {
            // 无构成证据=恒星光中性带（反照星光——物理诚实回落）
            hue = hueOfArgb(starArgb)
        }
        val jit = (h01(tagSeed, 71) * 2f - 1f) * PLANET_HUE_JITTER
        var h = (hue + jit + 360f) % 360f
        // 恒星光染色（微调非夺主）：色相向恒星色相拉近 18%
        h = lerpHueDeg(h, hueOfArgb(starArgb), PLANET_STAR_TINT_MIX)
        return hsvToArgb(h, 0.46f, 0.68f)
    }

    // ==================== 造父变星（真光变曲线） ====================

    /** 变星判据：条数下限（「周期性」统计意义下限——样本太少谈不上周期） */
    private const val CEPHEID_MIN_ITEMS = 24

    /** 变星判据：数据跨度下限（天——至少一个月的发布史才谈周期） */
    private const val CEPHEID_MIN_SPAN_DAYS = 30f

    /** 变星判据：平均更新间隔带（天——快于 0.4 天=日更多次型不归变星，慢于 45 天≈停更） */
    private const val CEPHEID_MIN_PERIOD_DAYS = 0.4f
    private const val CEPHEID_MAX_PERIOD_DAYS = 45f

    /** 变星判据：近两周节奏稳定比带（recent7/prev7 ∈ [0.4, 2.5]——节奏突变不算搏动） */
    private const val CEPHEID_STEADY_LO = 0.4f
    private const val CEPHEID_STEADY_HI = 2.5f

    /** 升相占周期比（真造父变星：快升——亮度极速爬升、缓慢回落的不对称锯齿） */
    const val CEPHEID_RISE_FRAC = 0.28f

    /** 可视搏动周期带（秒）：P_real 保序映射后的屏上周期 */
    private const val CEPHEID_VIS_MIN_SEC = 4f
    private const val CEPHEID_VIS_MAX_SEC = 26f

    /** 振幅带（周期-光度关系：P 长=光度大=振幅大；钳幅防出戏） */
    private const val CEPHEID_AMP_MIN = 0.10f
    private const val CEPHEID_AMP_MAX = 0.24f

    /**
     * 一颗变星的搏动参数（装配期算定，渲染期纯查表）：
     * [periodSec]=可视周期（保序映射）；[amplitude]=亮度振幅（周期-光度关系）；
     * [phase01]=哈希初相（颗颗错相）。
     */
    class Cepheid(val periodSec: Float, val amplitude: Float, val phase01: Float)

    /**
     * 造父变星判定+参数（纯函数；数据缺省返回 null=非变星——绝不冒充变星）：
     * 「周期性更新频道」的诚实代理判据=有条数统计意义 ∧ 跨度够 ∧ 均距在周期带 ∧
     * 近两周节奏稳定（recent7/prev7 不比出突变）。全数据来自索引库+金矿快照
     * （零新增请求红线不破）。
     */
    fun cepheidOf(
        items: Int,
        firstDateSec: Long,
        lastActivitySec: Long,
        recent7: Int,
        prev7: Int,
        chatId: Long,
    ): Cepheid? {
        if (items < CEPHEID_MIN_ITEMS) return null
        if (firstDateSec <= 0L || lastActivitySec <= firstDateSec) return null
        val spanDays = (lastActivitySec - firstDateSec).toFloat() / 86_400f
        if (spanDays < CEPHEID_MIN_SPAN_DAYS) return null
        val periodDays = spanDays / items
        if (periodDays < CEPHEID_MIN_PERIOD_DAYS || periodDays > CEPHEID_MAX_PERIOD_DAYS) return null
        // 节奏稳定门：近两周都要有发布且比值不带突变（爆发/骤降=超新星/停更语义，非搏动）
        if (recent7 < 1 || prev7 < 1) return null
        val ratio = recent7.toFloat() / prev7.toFloat()
        if (ratio < CEPHEID_STEADY_LO || ratio > CEPHEID_STEADY_HI) return null
        // 可视周期保序映射：Pvis=5.2×P^0.35（P=0.4→4.2s / P=2.4d→8.4s / P=45d→26s）
        val visSec = (5.2f * periodDays.pow(0.35f)).coerceIn(CEPHEID_VIS_MIN_SEC, CEPHEID_VIS_MAX_SEC)
        // 周期-光度关系（真造父 ln 律）：P 长=光度大=振幅大
        val lum01 = (ln(periodDays / CEPHEID_MIN_PERIOD_DAYS) /
            ln(CEPHEID_MAX_PERIOD_DAYS / CEPHEID_MIN_PERIOD_DAYS)).coerceIn(0f, 1f)
        val amp = (CEPHEID_AMP_MIN + (CEPHEID_AMP_MAX - CEPHEID_AMP_MIN) * lum01)
            .coerceIn(CEPHEID_AMP_MIN, CEPHEID_AMP_MAX)
        return Cepheid(visSec, amp, h01Long(chatId, 733))
    }

    /**
     * 造父变星光变波形（快升慢降不对称——真光变曲线形）：[phase01]∈[0,1) →
     * 归一亮度形 0..1（升相 [CEPHEID_RISE_FRAC] 内 ^0.65 快升，其后 ^1.35 慢降）。
     * 渲染期消费：亮度因子=1+amp×(shape×2−1)。
     */
    fun cepheidShape(phase01: Float): Float {
        val u = ((phase01 % 1f) + 1f) % 1f
        return if (u < CEPHEID_RISE_FRAC) {
            (u / CEPHEID_RISE_FRAC).pow(0.65f)
        } else {
            1f - ((u - CEPHEID_RISE_FRAC) / (1f - CEPHEID_RISE_FRAC)).pow(1.35f)
        }
    }

    /**
     * 变星亮度因子（渲染期单口）：[tSec]=会话秒；periodSec≤0=非变星恒 1（零成本
     * 早退）。返回 1±amplitude 的搏动系数（亮度乘性因子）。
     */
    fun cepheidLuma(tSec: Float, c: Cepheid?): Float {
        if (c == null || c.periodSec <= 0f) return 1f
        return 1f + c.amplitude * (cepheidShape(tSec / c.periodSec + c.phase01) * 2f - 1f)
    }

    // ==================== 临边昏暗（真实化：系数随色温从动——Canvas 位图与 GL shader 同一出处） ====================

    /** 色温归一（kelvin→0..1，钳域内） */
    fun temp01OfKelvin(kelvin: Float): Float =
        ((kelvin - TEMP_MIN_K) / (TEMP_MAX_K - TEMP_MIN_K)).coerceIn(0f, 1f)

    /**
     * 临边昏暗线性律系数 u（I=1−u(1−μ)−0.18(1−μ)²——二次项常数双端同值）：
     * 冷星 u=0.85 强昏暗/热星 u=0.40（恒星大气模型实测档——冷星临边吃光深=昏暗更狠）。
     */
    fun limbKOfTemp01(temp01: Float): Float = 0.85f - 0.45f * temp01.coerceIn(0f, 1f)

    /** 临边昏暗二次项系数（双端同值——渲染器各自内嵌进 shader/位图数学） */
    const val LIMB_QUAD = 0.18f

    // ==================== 宜居带环带（尘埃/冰粒环纹理——双端同参数单源） ====================

    /** 环带颗粒数（每轨道——28 粒成弧成缝的聚块感；帧预算有界） */
    const val HABITABLE_GRAINS = 28

    /** 颗粒径向抖动幅度（×轨道半径——环带厚度：真环带不是几何圆） */
    const val HABITABLE_RADIAL_JITTER = 0.03f

    /** 中心均迹线 alpha（极淡——保轨道可读性，颗粒才是主体） */
    const val HABITABLE_GUIDE_ALPHA = 0.045f

    /** 颗粒 alpha 带（聚块谐波调制前的底/顶） */
    const val HABITABLE_ALPHA_MIN = 0.06f
    const val HABITABLE_ALPHA_MAX = 0.30f

    /** 冰粒基色（白蓝——水冰/硅酸盐尘埃的反射色；渲染端再叠恒星光染色） */
    const val HABITABLE_ICE_ARGB = 0xFFDDE8F2.toInt()

    /**
     * 环带第 [k] 粒的角位（弧度，0..2π——确定性哈希：同星同轨每次打开同一环带）
     */
    fun habitableGrainAngle(seed: Int, k: Int): Float =
        (k.toFloat() / HABITABLE_GRAINS + h01(seed, k * 31 + 401) * 0.7f / HABITABLE_GRAINS) * 6.2831853f

    /**
     * 环带第 [k] 粒的 alpha（三段谐波聚块——真环带的成弧成缝感；相位哈希星/轨各异）：
     * clump=0.5+0.5·sin(3θ+φ1)·sin(5θ+φ2) 型（两谐波拍频出聚块，角位连续场非逐粒独立——
     * 相邻粒 alpha 相关=「弧」观感）。
     */
    fun habitableGrainAlpha(seed: Int, grainAng: Float): Float {
        val p1 = h01(seed, 409) * 6.2831853f
        val p2 = h01(seed, 419) * 6.2831853f
        val clump = 0.5f + 0.5f * kotlin.math.sin(grainAng * 3f + p1) * kotlin.math.sin(grainAng * 5f + p2)
        return HABITABLE_ALPHA_MIN + (HABITABLE_ALPHA_MAX - HABITABLE_ALPHA_MIN) * (0.25f + 0.75f * clump)
    }

    /** 环带第 [k] 粒的径向系数（1±[HABITABLE_RADIAL_JITTER]——环带厚度） */
    fun habitableGrainCoef(seed: Int, k: Int): Float =
        1f + (h01(seed, k * 31 + 403) * 2f - 1f) * HABITABLE_RADIAL_JITTER

    /** 环带第 [k] 粒的尺寸系数（×基准粒径——0.6~1.6 的真颗粒大小散布） */
    fun habitableGrainSize(seed: Int, k: Int): Float = 0.6f + h01(seed, k * 31 + 407) * 1.0f

    // ==================== 内部数学件（HSV/RGB 纯 Kotlin——零 Android 依赖单测可跑） ====================

    /** ARGB → 色相（度）；零饱和=0 */
    fun hueOfArgb(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b).toFloat()
        val min = minOf(r, g, b).toFloat()
        val delta = max - min
        if (delta <= 0f) return 0f
        var h = when {
            max == r.toFloat() -> ((g - b) / delta) % 6f
            max == g.toFloat() -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
        if (h < 0f) h += 360f
        return h
    }

    /** HSV → ARGB（h°∈[0,360) s/v∈[0,1]，alpha=0xFF——与 android Color.HSVToColor 同式） */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** RGB 线性混色（alpha 丢弃，0xFF 输出） */
    fun blendRgb01(a: Int, b: Int, mix: Float): Int {
        val m = mix.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * m).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * m).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * m).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** 色相插值（最短弧——358°→2° 不绕远路） */
    fun lerpHueDeg(a: Float, b: Float, t: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return (a + d * t.coerceIn(0f, 1f) + 360f) % 360f
    }

    /** 确定性 0..1 哈希（Int 种子——与 TasteTagPlanets.h01 同式镜像） */
    fun h01(a: Int, b: Int): Float =
        (((a * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF).toFloat() / 16777216f

    /** 确定性 0..1 哈希（Long 种子——chatId 高低位混合后复用同式） */
    fun h01Long(a: Long, b: Int): Float = h01(((a xor (a shr 32)).toInt()), b)
}
