package dev.tgfeed.core.tastemap

/**
 * R319 宇宙底幕（CMB 余辉 + 奥伯斯视界 + 「光在路上」暗点）渲染参数**单源**
 * （core 纯件，零 Android 依赖，JVM 单测可跑——TasteTerminalFx 同模式：
 * Canvas（TasteFlightRenderer）与 GL（GlFrameBuilder）双端同式的机制保障，
 * 渲染端只画不算，参数公式一处改两端生效——纪律§一类问题收敛）。
 *
 *  - **温度档**（[temp01Of]/[kelvinOf]/[alphaOf]）：延时摄影光标是 CMB「温度」的
 *    唯一数据源（r315 A①零编造红线——CMB 是创世层不映射频道级数据）——回望越远
 *    越热：光标=现在=深空底色（~3200K 暖黑，alpha 0.02~0.04 极淡）；光标=大爆炸
 *    =暗橙红微光（~2000K，剧场帽 0.12——克制防抢戏）；延时摄影关/量程无效
 *    =0 恒定底色（诚实缺省）。色值由渲染端走 [TasteStarColor.blackbodyRgb]
 *    黑体谱导出（不另立色源）。
 *  - **底纹噪声**（[noise01At]）：value noise 3 octave（640×640 预烘焙——种子=
 *    基因指纹 genomeFp：同库同纹理、换库换底色，确定性铁律非随机贴图）；烘焙
 *    收口 [dev.tgfeed.ui.gl.GlTextureBaker.bakeCmb]（Canvas/GL 双侧消费同一份
 *    IntArray——位图 LRU 键=genomeFp，帧路径零分配零重算）。
 *  - **奥伯斯视界**（C 章）：[OlbersZone]——R_obs=已见频道集合的 sp* 包络（模长
 *    max，装配期既有坐标只读消费零写入）；边界之外=「那边的光还没到」；**「光在
 *    路上」暗点**=金矿快照成员与星池成员的差集（金矿候选未入图=未观看未订阅
 *    ——池含观看∪订阅全宇宙口径，差集天然实现三条件）按球面哈希确定性布点在
 *    R_obs~1.15×R_obs 薄壳层；数量帽 120（差集截断，top 按金矿 items 降序+chatId
 *    破平——诚实截断记账）；**不可点无信息卡**（还没观测到它，点了就冒充已知
 *    ——由渲染端不进命中循环天然满足）。
 *
 * 确定性：全部公式零 Random 零系统钟（哈希=[TasteSpace.h01] 同族单口）——
 * 同数据同底幕（R 系铁律）。
 */
object CmbBackdropFx {

    // ==================== CMB 温度档（A 章①——光标=温度唯一数据源） ====================

    /** 光标=现在的底色色温（深空暖黑——黑体谱导出原料） */
    const val TEMP_K_NOW = 3200f

    /** 光标=大爆炸的底色色温（暗橙红——早期宇宙更热更亮，克制帽防抢戏） */
    const val TEMP_K_BANG = 2000f

    /**
     * 温度归一（0=现在/深空底色，1=大爆炸/暗橙红）：延时摄影开 ∧ 量程有效时
     * =1−光标（光标越早宇宙越热——回望=看更热的时代）；其余（延时摄影关/量程
     * 无效）=0 恒定底色（诚实缺省，r315 A⑤ 单测锚）。
     */
    fun temp01Of(genesisOn: Boolean, genesisT01: Float): Float =
        if (genesisOn) (1f - genesisT01.coerceIn(0f, 1f)) else 0f

    /** 温度归一→色温（3200K→2000K 线性插值——渲染端经 TasteStarColor 出色） */
    fun kelvinOf(temp01: Float): Float =
        TEMP_K_NOW + (TEMP_K_BANG - TEMP_K_NOW) * temp01.coerceIn(0f, 1f)

    /** 常态透明度下限（深空底色档——极淡在场） */
    const val ALPHA_FLOOR = 0.02f

    /** 常态透明度上限（关延时摄影的日常档——A⑤ 验收「常态极淡不抢星」） */
    const val ALPHA_NOW = 0.04f

    /** 剧场透明度上限（延时摄影开=回望叙事主件，仍克制防抢戏） */
    const val ALPHA_CINEMATIC = 0.12f

    /**
     * 底幕整体透明度（0.02~帽，temp01 单调不减）：常态帽 [ALPHA_NOW]，剧场
     * （延时摄影开）帽 [ALPHA_CINEMATIC]——A⑤ 单测锚（alpha 钳域）。
     */
    fun alphaOf(temp01: Float, cinematic: Boolean): Float {
        val cap = if (cinematic) ALPHA_CINEMATIC else ALPHA_NOW
        val t = temp01.coerceIn(0f, 1f)
        return (ALPHA_FLOOR + (cap - ALPHA_FLOOR) * t).coerceIn(ALPHA_FLOOR, cap)
    }

    // ==================== 底纹噪声（A 章②——温度涨落斑驳图） ====================

    /** 预烘焙位图边长（640×640——r315 A②设计值；LRU ≤2 张=当前+上次指纹） */
    const val TEX_SIZE = 640

    /** value noise 倍频程数（2~3 取 3——斑驳大尺度主导+细部微扰） */
    const val NOISE_OCTAVES = 3

    /** 着色量化桶数（Canvas PorterDuff 滤镜缓存粒度——temp01 连续→桶离散帧零分配） */
    const val TINT_BUCKETS = 24

    /** 温度归一→着色量化桶（0 until TINT_BUCKETS——滤镜/缓存键单出口） */
    fun tintBucketOf(temp01: Float): Int =
        (temp01.coerceIn(0f, 1f) * (TINT_BUCKETS - 1).toFloat() + 0.5f).toInt()

    /**
     * 底纹 value noise（3 octave：4/8/16 网格×等权——u/v∈[0,1) 平铺域）：格点哈希
     * 双线性插值，种子=genomeFp 折叠 Int（确定性——同库同纹理；JVM 单测锚）。
     * 渲染端零直接调用（烘焙收口 GlTextureBaker.bakeCmb——帧路径零重算）。
     */
    fun noise01At(u: Float, v: Float, seed: Int): Float {
        var sum = 0f
        var cells = 4
        for (o in 0 until NOISE_OCTAVES) {
            sum += valueNoise1(u, v, cells, seed * 31 + o * 17 + 901)
            cells = cells shl 1
        }
        return (sum / NOISE_OCTAVES).coerceIn(0f, 1f)
    }

    /** 单倍频程 value noise（cells×cells 格点哈希+双线性插值，边界环绕平铺） */
    private fun valueNoise1(u: Float, v: Float, cells: Int, salt: Int): Float {
        val x = u * cells
        val y = v * cells
        val x0 = x.toInt().mod(cells)
        val y0 = y.toInt().mod(cells)
        val x1 = (x0 + 1).mod(cells)
        val y1 = (y0 + 1).mod(cells)
        val fx = x - x.toInt()
        val fy = y - y.toInt()
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val v00 = TasteSpace.h01((y0 * 977L + x0), salt)
        val v10 = TasteSpace.h01((y0 * 977L + x1), salt)
        val v01 = TasteSpace.h01((y1 * 977L + x0), salt)
        val v11 = TasteSpace.h01((y1 * 977L + x1), salt)
        val a = v00 + (v10 - v00) * sx
        val b = v01 + (v11 - v01) * sx
        return a + (b - a) * sy
    }

    // ==================== 奥伯斯视界与「光在路上」暗点（C 章） ====================

    /** 「光在路上」暗点数量帽（差集截断——top 按金矿 items 降序；诚实截断记账） */
    const val DOTS_MAX = 120

    /** 暗点带外缘系数（R_obs×1.15——视界壳与暗点薄壳层的上界） */
    const val SHELL_OUT_K = 1.15f

    /** 视界壳雾补丁数（球面均匀方向——TasteSpace.skyDirection 既有确定性方向表） */
    const val SHELL_PATCHES = 18

    /** 视界壳基础透明度（暗蓝档 0.03~0.05 的中值——克制不抢星） */
    const val SHELL_ALPHA = 0.04f

    /** 暗点基础透明度（帽 0.08 之下——极暗微点「光已出发在途中」） */
    const val DOT_ALPHA_BASE = 0.06f

    /** 暗点透明度帽（C 章②设计值 alpha ≤0.08） */
    const val DOT_ALPHA_MAX = 0.08f

    /** 彗星掠过暗点微亮的屏距半径（px——C 章④被动联动「光快到了」） */
    const val COMET_GLOW_PX = 120f

    /** 彗星掠过暗点的透明度增幅帽（微微一亮非点亮——还在路上） */
    const val COMET_GLOW_BOOST = 0.22f

    /**
     * 奥伯斯差集候选筛选（纯函数——金矿成员−星池成员，池内排除+帽截断）：
     * [gold] 候选对（chatId→金矿 items）；[poolIds] 星池成员集合（含观看∪订阅∪
     * meta∪金矿入图∪图谱全宇宙口径——差集天然=未观看未订阅未入图）。出参：
     * 截断保序（金矿 items 降序+chatId 升序破平——确定性）后的 chatId 列表与
     * 截断计数（raw−kept，探针记账「金矿差集 K 截断 L」）。空金矿=零候选诚实缺省。
     */
    fun olbersCandidatesOf(gold: List<Pair<Long, Int>>, poolIds: Set<Long>): Pair<List<Long>, Int> {
        val diff = gold.filter { it.first !in poolIds && it.second > 0 }
        val kept = diff.sortedWith(
            compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first },
        ).take(DOTS_MAX)
        return kept.map { it.first } to (diff.size - kept.size)
    }

    /**
     * 暗点半径分数（1.0~1.15：chatId 哈希——布点确定性单源；世界位=方向×R_obs×分数）。
     */
    fun dotRadius01(chatId: Long): Float = 1f + (SHELL_OUT_K - 1f) * TasteSpace.h01(chatId, 701)

    /** 暗点球面方向（单位向量——chatId 哈希：均匀球面布点；out=长度 3 复用数组） */
    fun dotDir(chatId: Long, out: FloatArray) {
        val theta = TasteSpace.h01(chatId, 703) * 6.2831853f
        val cosPhi = 2f * TasteSpace.h01(chatId, 705) - 1f
        val sinPhi = kotlin.math.sqrt((1f - cosPhi * cosPhi).coerceAtLeast(0f))
        out[0] = sinPhi * kotlin.math.cos(theta)
        out[1] = sinPhi * kotlin.math.sin(theta)
        out[2] = cosPhi
    }

    /**
     * 奥伯斯带成品（装配一次进帧只读）：[rObs]=观测视界半径（sp* 模长 max，星表
     * 非空时恒 ≥1）；[dots]=暗点世界位（x,y,z×n，n=dots.size/3——差集候选确定性
     * 布点）；[rawDiff]/[truncated]=差集原始计数与截断计数（探针诚实记账）。
     * 星表空=null zone（渲染端整层缺省）；金矿缺席=rObs 仍在（包络只依赖星表）
     * 但 dots 空（差集缺据零暗点——诚实）。
     */
    class OlbersZone(
        val rObs: Float,
        val dots: FloatArray,
        val rawDiff: Int,
        val truncated: Int,
    )

    /**
     * 奥伯斯带装配（纯函数——差集候选→确定性布点）：同差集同布点（chatId 哈希
     * 方向+半径分数，零 Random——C 章⑤单测锚）；rObs ≤0=缺省（空带）。
     */
    fun olbersZoneOf(rObs: Float, candidates: List<Long>, rawDiff: Int, truncated: Int): OlbersZone {
        if (rObs <= 0f || candidates.isEmpty()) return OlbersZone(rObs.coerceAtLeast(0f), FloatArray(0), rawDiff, truncated)
        val dots = FloatArray(candidates.size * 3)
        val d = FloatArray(3)
        for (i in candidates.indices) {
            val id = candidates[i]
            dotDir(id, d)
            val r = rObs * dotRadius01(id)
            dots[i * 3] = d[0] * r
            dots[i * 3 + 1] = d[1] * r
            dots[i * 3 + 2] = d[2] * r
        }
        return OlbersZone(rObs, dots, rawDiff, truncated)
    }

    /**
     * 暗点彗星掠过微亮（C 章④被动联动）：彗星在场时屏距 [distPx]<[COMET_GLOW_PX]
     * 的暗点透明度线性增亮（帽 [DOT_ALPHA_MAX]+[COMET_GLOW_BOOST]——「光快到了」
     * 预告非点亮）；彗星不在场（cometLife ≤0）=原档（域外零成本）。
     */
    fun dotAlphaGlow(baseAlpha: Float, distPx: Float, cometLife: Float): Float {
        if (cometLife <= 0f) return baseAlpha
        val k = 1f - (distPx / COMET_GLOW_PX).coerceIn(0f, 1f)
        if (k <= 0f) return baseAlpha
        return (baseAlpha + COMET_GLOW_BOOST * k).coerceAtMost(DOT_ALPHA_MAX + COMET_GLOW_BOOST)
    }
}
