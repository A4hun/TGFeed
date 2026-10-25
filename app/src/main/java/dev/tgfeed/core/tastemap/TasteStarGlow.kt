package dev.tgfeed.core.tastemap

import kotlin.math.exp
import kotlin.math.pow

/**
 * R339 恒星光两形态引擎（r339 设计定稿的参数单源——纯 Kotlin 零 Android 依赖，
 * JVM 单测可跑；TasteStarColor 同模式同目录）。用户 20:0x 拍板原话：「**透明
 * 圆圈本质上不适合表示光——真实宇宙的发光只有两种形态：远处=刺眼的点、近处
 * =发光的球，没有中间态；圆盘晕去掉**」——R334 的 PSF 三层宽晕（L2@2.2r 内晕
 * /L3@3.6r 外晕淡尾）+远星紧贴微晕+球缘光渗全部退役（用户连续四轮否决宽晕的
 * 最终裁决），本件=两形态世界的参数单源。
 *
 * **形态系统（距离驱动）**：
 * - **远观档 99%**：纯色点（色温色+亮度变化，零晕盘——盘光外径帽
 *   [LIGHT_EXTENT_MAX_R]=2r）；点体剖面=紧刺曲线（大饱和核+陡降+快尾）。
 * - **远观档 top ~5 最亮**：衍射星芒（[SPIKE_COUNT] 四芒细十字或
 *   [SPIKE_COUNT_RICH] 六芒）+极紧眩光（[GLARE_R_PX]≤2px）——真实望远镜/相机
 *   观感；芒长∝亮度（[spikeLenPx]）、沿芒 α 快衰减（[spikeAlpha01]）。
 * - **贴脸档**：真 3D 发光球=R311 既有资产（真米粒+胞界暗脉/Hα 色球壳/日珥
 *   拱/临边昏暗——渲染端球位图/SPHERE_FS，本件零新做）。
 * - **两形态之间**：没有中间形态，只有**光滑 crossfade**（R349 重锚：
 *   [TasteLodPolicy.POINT_CEIL_DP]~[TasteLodPolicy.SOLID_FLOOR_DP]=[1.2,1.6]dp
 *   smoothstep 窗——核点膨胀成球 [coreSwellCoef]、点/球形态 alpha 能量守恒
 *   交接 [pointFormAlpha]+[ballFormAlpha]、星芒随点形态让位；窗位单源=
 *   [TasteLodPolicy]——本件零第二套委托；R355 阈值 dp 化+**球=真 3D 即刻生效**
 *   （blend 只留点淡出侧——用户终裁三合一）。
 *
 * **宽晕退役处置（一阶段 compile-safe 刀法）**：L3 族（L3_R/L3_A/
 * outerHaloAlpha/OUTER_ALPHA_MAX/TOTAL_R_MAX）渲染器零引用已物理删除；L2/FAR/
 * CLOSEUP 被渲染器位图绘制引用（物理删除破编译）→ **Deprecated 死亡值**（alpha
 * 恒 0——渲染端 drawHaloLayer a≤0.008 早退=屏上零出光零成本），**二阶段随位图
 * 三档绘制拆除物理移除**（中档分支须先重接实心盘+核兜底再删 L1——L1 是中档
 * 承重件，见 [L1_A] KDoc）。死亡层按活值入 [lightAlphaOf] 锚函数——复活即破锚。
 *
 * 职责边界（r333 §2.1 延续）：本件**只做参数档与剖面数学，不发明颜色**——桶三段
 * 色全由 [TasteStarColor] 导出（点体位图烘焙消费）；芒色/眩光色=渲染端直接吃
 * 星色（bodyColor/coreC——零新色源）。
 *
 * **盘光/非盘光边界（[LIGHT_EXTENT_MAX_R] 帽的适用域——防误伤既有资产）**：
 * 受帽=一切「以星为心、径向对称、半透明」的光层（点体位图/眩光/已死宽尾层）；
 * 不受帽=①星芒芒刺（线状光≠圆盘——裁决明文的 top-5 形态，芒长绝对 px 帽
 * [SPIKE_LEN_MAX_PX] 封顶）②物质壳层（超新星遗迹丝缕壳=R317b 静态壳/星风泡/
 * 日冕日珥——被吹出的物质不是光）③静态身份环（徽章/选中/锁定/光子环）。
 *
 * 工程纪律：全函数零分配纯浮点（烘焙期除外）、确定性（零 Random 零系统钟）。
 * 首版值声明：紧剖面/星芒/crossfade 全部参数=首版建议值（r334「首版值待调参」
 * 挂账的延续——V38 实测后单件调参，r333 §3.6 回退面最小口径）。
 */
object TasteStarGlow {

    // ==================== ① 色温桶（8 K 桶+3 旧档兜底——点体位图烘焙的取色单源） ====================

    /** K 桶数（tempK 域 [2400,11800] 按宽 [BUCKET_K_WIDTH] 均分） */
    const val K_BUCKETS = 8

    /** 旧档兜底桶 id：暖星（超新星外的 warm kind 族） */
    const val BUCKET_WARM = 8

    /** 旧档兜底桶 id：冷星（超新星蓝白炽/致密星 cool 档——「爆发蓝白档」） */
    const val BUCKET_COOL = 9

    /** 旧档兜底桶 id：白星（白洞/流星 white 档） */
    const val BUCKET_WHITE = 10

    /** 桶总数（8 K 桶+3 旧档——缓存 LRU 键域） */
    const val BUCKET_COUNT = 11

    /** K 桶宽（K）——黑体谱上 ΔHue≤6° 的零观感损失论证口径 */
    private const val BUCKET_K_WIDTH = 1200f

    /** 旧档暖桶锚 K（与渲染端 TEMP_WARM 档观感族对应——身份色靠 bodyColor tint 层） */
    private const val LEGACY_WARM_K = 3400f

    /** 旧档白桶锚 K（太阳黄白带） */
    private const val LEGACY_WHITE_K = 5800f

    /** 旧档冷桶锚 K（蓝白带——超新星「爆发蓝白档=cool 兜底桶」） */
    private const val LEGACY_COOL_K = 9200f

    /** 温度档 id（与 TasteFlightRenderer.TEMP_WARM/COOL/WHITE 及 GlPalette 同值同改） */
    const val TIER_WARM = 0
    const val TIER_COOL = 1
    const val TIER_WHITE = 2

    /** tempK→K 桶号（确定性；域外钳边界桶） */
    fun bucketOfK(tempK: Float): Int =
        (((tempK - TasteStarColor.TEMP_MIN_K) / BUCKET_K_WIDTH).toInt()).coerceIn(0, K_BUCKETS - 1)

    /** 旧温度档（0/1/2）→兜底桶 id（特殊 kind 身份色不被色温轴污染——r333 §2.1） */
    fun legacyBucketOf(tempTier: Int): Int = when (tempTier) {
        TIER_WARM -> BUCKET_WARM
        TIER_COOL -> BUCKET_COOL
        else -> BUCKET_WHITE
    }

    /** 桶锚色温（K 桶=桶中点 K；旧档=锚 K）——烘焙期三段色的取温单口 */
    fun anchorKelvinOf(bucket: Int): Float = when (bucket.coerceIn(0, BUCKET_COUNT - 1)) {
        BUCKET_WARM -> LEGACY_WARM_K
        BUCKET_COOL -> LEGACY_COOL_K
        BUCKET_WHITE -> LEGACY_WHITE_K
        else -> TasteStarColor.TEMP_MIN_K + (bucket + 0.5f) * BUCKET_K_WIDTH
    }

    /** 白热核饱和帽（点体剖面中心平台向白提——真实摄影里核永远趋白饱和） */
    private const val CORE_SAT_MAX = 0.12f

    /** 长尾明度系数（尾段=edgeColorOfK×0.85 明度——色相不动） */
    private const val TAIL_V_MUL = 0.85f

    /** 桶芯色（黑体 T×1.35 再白热化 S 钳 [CORE_SAT_MAX]——全 TasteStarColor 导出零新色源） */
    fun coreColorOfBucket(bucket: Int): Int =
        whiteHotOf(TasteStarColor.coreColorOfK(anchorKelvinOf(bucket)))

    /** 桶晕色（黑体 T 柔光档域色——starColorOfK 零构成证据口径） */
    fun haloColorOfBucket(bucket: Int): Int =
        TasteStarColor.starColorOfK(anchorKelvinOf(bucket), 0)

    /** 桶尾色（黑体 T×0.60 缘色×[TAIL_V_MUL] 明度——临边红移的长尾淡色） */
    fun tailColorOfBucket(bucket: Int): Int {
        val c = TasteStarColor.edgeColorOfK(anchorKelvinOf(bucket))
        val max = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toFloat()
        if (max <= 0f) return c
        val min = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toFloat()
        val s = (max - min) / max
        return TasteStarColor.hsvToArgb(TasteStarColor.hueOfArgb(c), s, max / 255f * TAIL_V_MUL)
    }

    /** 白热化（S 钳 [CORE_SAT_MAX]——色相/明度不动） */
    private fun whiteHotOf(argb: Int): Int {
        val max = maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF).toFloat()
        if (max <= 0f) return argb
        val min = minOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF).toFloat()
        val s = (max - min) / max
        return TasteStarColor.hsvToArgb(TasteStarColor.hueOfArgb(argb), minOf(s, CORE_SAT_MAX), max / 255f)
    }

    // ==================== ② 点形态剖面（紧刺曲线——「刺眼的点」的径向形状） ====================

    /** 点体位图边长（px）——64×64×4B≈16KB 每桶（点体 L1+R321 星风泡共用烘焙管线） */
    const val PSF_SIZE_PX = 64

    /** 核平台外缘（d01 归一径向）——饱和核=「刺眼」的本体（R334 0_18→R339 0_26 放大） */
    private const val PLATEAU_D = 0.26f

    /** 陡降段外缘（指数 I 段终点，alpha 降至 [STEEP_END_A]） */
    private const val STEEP_END_D = 0.55f

    /** 快尾段终点（II 段归零位——R334 0_98→R339 0_86 收紧：尾更短更快死） */
    private const val TAIL_END_D = 0.86f

    /** 陡降段终点 alpha（两段衔接值） */
    private const val STEEP_END_A = 0.30f

    /** 陡降段 σ（exp(−(0.55−0.26)/σ)=0.30 反解≈0.2409——比 R334 0_325 更陡） */
    private const val STEEP_SIGMA = 0.2409f

    /** 快尾幂律指数（R334 2_2→R339 2_6：尾衰减更快——「紧」的量化） */
    private const val TAIL_POWER = 2.6f

    /**
     * 点形态径向剖面（d01∈[0,1]→0..1）：大饱和核平台→指数陡降→幂律快尾归零。
     * **径向单调非增**（「圆环」反例哨的锚函数——空心 stroke 环会在峰值外违反；
     * r333 §2.6 ③ 哨在 R339 保留——剖面族仍是单源单测可锚的纯函数）。
     */
    fun psfAlphaOf(d01: Float): Float {
        val d = d01.coerceIn(0f, 1f)
        return when {
            d <= PLATEAU_D -> 1f
            d <= STEEP_END_D -> exp(-(d - PLATEAU_D) / STEEP_SIGMA)
            d <= TAIL_END_D -> STEEP_END_A *
                ((TAIL_END_D - d) / (TAIL_END_D - STEEP_END_D)).pow(TAIL_POWER)
            else -> 0f
        }
    }

    /** 芯→晕混色带（d01 域：平台芯色→中段域色——[CORE_BLEND_LO] 与核平台外缘同值） */
    private const val CORE_BLEND_LO = 0.26f
    private const val CORE_BLEND_HI = 0.42f

    /** 晕→尾混色带（d01 域：中段域色→尾色——随快尾收紧重排） */
    private const val TAIL_BLEND_LO = 0.58f
    private const val TAIL_BLEND_HI = 0.82f

    /**
     * 点体像素（桶位图烘焙单口）：alpha=[psfAlphaOf]，色=桶三段色按径向带混
     * （芯色→晕色→尾色，两段线性 RGB 过渡防带状断层）。绘制零 shader 零
     * ColorFilter——一次烘焙全进像素。
     */
    fun psfArgbOf(bucket: Int, d01: Float): Int {
        val a = psfAlphaOf(d01)
        if (a <= 0f) return 0
        val d = d01.coerceIn(0f, 1f)
        val c = when {
            d <= CORE_BLEND_LO -> coreColorOfBucket(bucket)
            d <= CORE_BLEND_HI -> TasteStarColor.blendRgb01(
                haloColorOfBucket(bucket), coreColorOfBucket(bucket),
                (CORE_BLEND_HI - d) / (CORE_BLEND_HI - CORE_BLEND_LO),
            )
            d <= TAIL_BLEND_LO -> haloColorOfBucket(bucket)
            d <= TAIL_BLEND_HI -> TasteStarColor.blendRgb01(
                tailColorOfBucket(bucket), haloColorOfBucket(bucket),
                (TAIL_BLEND_HI - d) / (TAIL_BLEND_HI - TAIL_BLEND_LO),
            )
            else -> tailColorOfBucket(bucket)
        }
        val a255 = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a255 shl 24) or (c and 0xFFFFFF)
    }

    // ==================== ③ 点体层（中档星的本体盘——非外晕） ====================

    /** L1 点体（中档 10~crossfade 窗）：dst 半径 r×1.0（**星本体盘不是外晕**）、
     *  alpha A×0.95。**中档承重件**：渲染端中档分支位图档只画 L1（L2 已死亡值
     * 早退）——L1 死=中档星整颗消失（else 兜底不生效）；R339 二阶段删位图三档
     * 绘制时必须先重接实心盘+核兜底再动它。 */
    const val L1_R = 1.0f
    const val L1_A = 0.95f

    // ==================== ④ 宽晕退役族（死亡值——二阶段随位图三档绘制物理移除） ====================

    /** L2 内晕死亡层（原 r×2.2/A×0.30——r333 §2.2 内晕档）。R339 用户裁决退役：
     *  透明圆盘=伪造中间形态。**[L2_A] 死亡值 0**（渲染端 drawHaloLayer a≤0.008
     *  早退=屏上零出光零成本）；半径锚值保留仅供二阶段 diff 定位与锚函数登记。 */
    @Deprecated("R339 宽晕退役：L2 内晕死亡（用户 20:0x「没有中间态」）——二阶段随渲染端位图三档绘制删除")
    const val L2_R = 2.2f

    /** L2 内晕 alpha 死亡值（恒 0——复活即破「r大于2r 处 alpha 恒 0」反锚与死亡值断言） */
    @Deprecated("R339 宽晕退役：L2 内晕 alpha 死亡值（恒 0）——二阶段物理移除")
    const val L2_A = 0f

    /** 远星紧贴微晕死亡层（原 r×1.6/A×0.55——r334「点+紧贴微晕」档）。「零晕盘」
     *  裁决连紧贴微晕一起死：远星回归纯实心温度色点。 */
    @Deprecated("R339 宽晕退役：远星微晕死亡（「零晕盘」）——二阶段随位图三档绘制删除")
    const val FAR_R = 1.6f

    /** 远星微晕 alpha 死亡值（恒 0） */
    @Deprecated("R339 宽晕退役：远星微晕 alpha 死亡值（恒 0）——二阶段物理移除")
    const val FAR_A = 0f

    /** 贴脸球缘光渗死亡层（原 r×1.35/A×0.06）。贴脸形态=球本体（R311 四件资产）
     *  ——球外渗光圈=伪造中间形态（用户第 5 次否决圆圈族）。 */
    @Deprecated("R339 宽晕退役：球缘光渗死亡（贴脸=球本体）——二阶段随位图三档绘制删除")
    const val CLOSEUP_R = 1.35f

    /** 贴脸球缘光渗 alpha 死亡值（恒 0） */
    @Deprecated("R339 宽晕退役：球缘光渗 alpha 死亡值（恒 0）——二阶段物理移除")
    const val CLOSEUP_A = 0f

    /**
     * 贴脸球缘光渗 alpha（R339 死亡值公式：starAlpha×[CLOSEUP_A](恒 0)×boost恒0）。
     * R334 原式含 OUTER_ALPHA_MAX 钳——光晕帽概念随宽晕退役已删（原帽 0_10 的
     * 唯一外证 StarwindFxTest 已解耦为字面锚）。
     */
    @Deprecated("R339 宽晕退役：恒 0 死亡公式——二阶段随位图三档绘制物理移除")
    fun closeupHaloAlpha(starAlpha: Float, boost: Float): Float = starAlpha * CLOSEUP_A * boost

    // ==================== ⑤ 盘光外径帽+锚函数（「宽晕退役」反锚的运行时护栏） ====================

    /** 盘光外径帽（r 倍数）：一切径向对称盘光层的光到此为止——「r大于2r 处 alpha
     *  恒 0」断言的帽本体（用户裁决「没有中间态」的量化护栏；R334 TOTAL_R_MAX
     *  3_6 的继任——紧了 1_6 倍）。适用域=盘光族（点体/眩光/死亡层）——星芒芒刺
     *  （线状光）与物质壳层（遗迹壳/星风泡）不在域内（类 KDoc 边界声明）。 */
    const val LIGHT_EXTENT_MAX_R = 2.0f

    /**
     * 星盘光包络（「宽晕退役」反锚的锚函数）：星半径 rPx 的恒星在距心 dPx 处的
     * **形态层**盘光 alpha（不含星亮——星亮是均匀缩放因子，渲染端乘）。全层族
     * **按活值入算**：L1 点体+眩光（两形态生效层）与 L2/FAR/CLOSEUP（死亡层——
     * 死亡值复活即在本函数显形=单测反锚捕获，护栏是运行时的不是文档性的）。
     * 断言域：dPx大于2×rPx 恒 0（径向 sweep 锚）；dPx≤2r 时为各层 max 包络。
     */
    fun lightAlphaOf(rPx: Float, dPx: Float): Float {
        if (rPx <= 0f) return 0f
        var a = 0f
        // 生效盘光层（两形态世界）
        if (dPx < rPx * L1_R) a = maxOf(a, psfAlphaOf(dPx / (rPx * L1_R)) * L1_A)
        if (dPx < GLARE_R_PX) a = maxOf(a, glareAlphaOf(dPx))
        // 宽晕退役死亡层（活值入算——恒 0；复活即破锚）
        if (dPx < rPx * L2_R) a = maxOf(a, L2_A * psfAlphaOf(dPx / (rPx * L2_R)))
        if (dPx < rPx * FAR_R) a = maxOf(a, FAR_A * psfAlphaOf(dPx / (rPx * FAR_R)))
        if (dPx < rPx * CLOSEUP_R) a = maxOf(a, CLOSEUP_A * psfAlphaOf(dPx / (rPx * CLOSEUP_R)))
        return a
    }

    // ==================== ⑥ 星芒族（top ~5 最亮星的衍射星芒——远观档） ====================

    /** 星芒星帽（亮度降序 top 门——「最亮少数」的量化口径；候选域=个体绘制
     *  **点形态**星 [lodPointCeilPx]≤r<effectiveSolidFloor（R349 形态线——R355
     *  dp 化=渲染器帧字段；resolved 无芒：3D 真身不参选；点云层星不拆云）。
     *  星芒=几条线段微成本（5 星×≤6 芒+眩光点——全景比 R334 的 64 张位图
     *  drawBitmap 还省）。 */
    const val SPIKE_MAX_STARS = 5

    /** 芒数：四芒细十字（0°/90°/180°/270°——真实望远镜/相机衍射观感主档） */
    const val SPIKE_COUNT = 4

    /** 芒数富档：六芒（60° 均布——「四芒或六芒」双口径；二阶段可全 4 芒或 top1
     *  六芒加冕取用） */
    const val SPIKE_COUNT_RICH = 6

    /** 芒长下限（px）——门内最暗星基线芒长（亮度 01 域下沿） */
    const val SPIKE_LEN_MIN_PX = 10f

    /** 芒长上限（px）——亮度 top1 满档芒长（绝对 px 帽防芒刺刷屏） */
    const val SPIKE_LEN_MAX_PX = 26f

    /** 芒长（px）=[SPIKE_LEN_MIN_PX]+(上限−下限)×亮度01——**芒长∝亮度**的线性
     *  单源公式（亮度01=渲染端归一：top1=1 递减；缺省 0=下限）。 */
    fun spikeLenPx(brightness01: Float): Float =
        SPIKE_LEN_MIN_PX + (SPIKE_LEN_MAX_PX - SPIKE_LEN_MIN_PX) * brightness01.coerceIn(0f, 1f)

    /** 芒宽（px）——细光刺（恒宽不随 r 膨胀成「光柱」；细十字的「细」字锚） */
    const val SPIKE_WIDTH_PX = 1.2f

    /** 芒基 alpha（星亮满档时芒根强度——根亮刺利） */
    const val SPIKE_A = 0.75f

    /** 沿芒衰减幂（alpha=[SPIKE_A]×(1−u)^幂——**α 快衰减**：半芒即近灭 0_094） */
    const val SPIKE_FALLOFF_POWER = 3.0f

    /**
     * 沿芒 alpha（u01∈[0,1] 芒根→芒尖）：[SPIKE_A]×(1−u)^幂——单调非增快衰减
     * （「刺」不是「棒」：半芒衰减到 0_094——真实衍射芒的径向锐利感）。
     * 渲染端线段量化取值（段均 alpha 或分档）由二阶段决定——本函数=连续剖面单源。
     */
    fun spikeAlpha01(u01: Float): Float {
        val u = u01.coerceIn(0f, 1f)
        return SPIKE_A * (1f - u).pow(SPIKE_FALLOFF_POWER)
    }

    // ==================== ⑦ 眩光（星芒星中心的白热饱和核——极紧） ====================

    /** 眩光半径帽（px）——「**极紧眩光 ≤2px**」的字面锚（绝对 px：内 1px 平台
     *  满档→2px 归零；对 r≥2_2px 的个体绘制星恒小于 0_91r——盘光外径帽内结构性
     *  成立） */
    const val GLARE_R_PX = 2f

    /** 眩光峰值 alpha（白热饱和核——刺眼本体的亮度档） */
    const val GLARE_A = 0.95f

    /** 眩光平台外缘（u=d/[GLARE_R_PX]）——内半平台满档（饱和核）外半快衰减 */
    private const val GLARE_PLATEAU_U = 0.5f

    /** 眩光衰减幂（平台外 (1−v)^幂——2px 硬截止前的快落） */
    private const val GLARE_FALLOFF_POWER = 2f

    /**
     * 眩光径向 alpha（dPx 距心 px）：[GLARE_A] 平台（d≤1px）→快衰减→2px 硬归零。
     * **单调非增**+**≤2px 极紧**（径向单调哨在眩光剖面上的同款锚）。
     */
    fun glareAlphaOf(dPx: Float): Float {
        val d = dPx.coerceAtLeast(0f)
        if (d >= GLARE_R_PX) return 0f
        val u = d / GLARE_R_PX
        if (u <= GLARE_PLATEAU_U) return GLARE_A
        val v = (u - GLARE_PLATEAU_U) / (1f - GLARE_PLATEAU_U)
        return GLARE_A * (1f - v).pow(GLARE_FALLOFF_POWER)
    }

    // ==================== ⑧ 点→球 crossfade（R349 重锚：政策件单源委托——零第二套；R355 dp 化） ====================

    /**
     * crossfade 因子（rPx→0..1）：**R349 委托 [TasteLodPolicy.blend01]**（r341
     * §2.3 对表协议「R339 先落地→收编进政策件」落法——恒星球门槛 30→2 后旧
     * 24~30 贴脸窗全域已在球档内=结构性空置；窗位重锚 [POINT_CEIL_DP,
     * SOLID_FLOOR_DP]=[1.2,1.6]dp，smoothstep C1 曲线族+能量守恒式全族平移**零第二
     * 套**——本族不再是独立参数源，只为 R339 语义名保留委托出口）。R355：阈值
     * 参透传（px 帧字段显式传参——**缺省=mdpi 恒等档**；渲染器主路传帧字段）。
     */
    fun crossfade01(
        rPx: Float,
        solidFloorPx: Float = TasteLodPolicy.SOLID_FLOOR_DP,
        pointCeilPx: Float = TasteLodPolicy.POINT_CEIL_DP,
    ): Float = TasteLodPolicy.blend01(rPx, solidFloorPx, pointCeilPx)

    /**
     * 点形态 alpha 因子（1−k）——点体/星芒的贴脸让位系数（球满档=0：星芒随点
     * 形态退场=远观档专属件；点档恒 1）。R349：≡[TasteLodPolicy.pointFadeK]
     * 同义单源（r341 §2.1 pointFadeK；resolved 无芒无闪的退场门）。R355：阈值参
     * 透传（缺省 mdpi 档）+**终裁 blend 只留点淡出侧的本系数=形态交接唯一存活侧**。
     */
    fun pointFormAlpha(
        rPx: Float,
        solidFloorPx: Float = TasteLodPolicy.SOLID_FLOOR_DP,
        pointCeilPx: Float = TasteLodPolicy.POINT_CEIL_DP,
    ): Float = TasteLodPolicy.pointFadeK(rPx, solidFloorPx, pointCeilPx)

    /** 球形态 alpha 因子（k）——R311 球体四件资产的登场系数（球满档=1；R355 阈值参透传缺省 mdpi 档） */
    fun ballFormAlpha(
        rPx: Float,
        solidFloorPx: Float = TasteLodPolicy.SOLID_FLOOR_DP,
        pointCeilPx: Float = TasteLodPolicy.POINT_CEIL_DP,
    ): Float = TasteLodPolicy.blend01(rPx, solidFloorPx, pointCeilPx)

    /**
     * 核点膨胀系数（rPx→核半径/r）：点形态=[PLATEAU_D]（紧刺核 0_26）→球满档
     * **连续膨胀到 1_0**（球核=整盘）——「放大时核点膨胀成球，半径连续过渡」
     * 的公式本体（球核半径 r 与 SPHERE 球半径 r 衔接——与 R311 质感件的半径
     * 连续锚）。单调连续（[TasteLodPolicy.blend01] 同窗插值——R349 重锚 1_2~1_6dp）。
     * R355：阈值参透传（渲染器传帧字段——点核膨胀随密度档窗口走）。
     */
    fun coreSwellCoef(
        rPx: Float,
        solidFloorPx: Float = TasteLodPolicy.SOLID_FLOOR_DP,
        pointCeilPx: Float = TasteLodPolicy.POINT_CEIL_DP,
    ): Float = PLATEAU_D + (1f - PLATEAU_D) * TasteLodPolicy.blend01(rPx, solidFloorPx, pointCeilPx)

    /**
     * 档 id（R349 换轨：形态档三态委托 [TasteStarGlow] 侧的兼容读出——政策单口
     * 见 [TasteLodPolicy.formTierOf]；TIER_NONE/FORM_POINT=点档、TIER_CROSSFADE/
     * FORM_BLEND=blend 带、TIER_CLOSEUP/FORM_SOLID=3D 真身——旧五档 FAR/MID
     * 中间态随 30→2 门槛消亡，档常数随笔5 常量退役批收口）。R355：阈值参透传
     * （缺省 mdpi 档）。
     */
    fun tierOf(
        rPx: Float,
        solidFloorPx: Float = TasteLodPolicy.SOLID_FLOOR_DP,
        pointCeilPx: Float = TasteLodPolicy.POINT_CEIL_DP,
    ): Int = TasteLodPolicy.formTierOf(rPx, solidFloorPx, pointCeilPx)

    // ==================== ⑩ shimmer（亮度微颤——纯 alpha 零半径项） ====================

    /** 微闪烁幅度（±8% 纯 alpha，0.5~2.4Hz——渲染端 tw 公式原样保留，此为域锚） */
    const val SHIMMER_AMP = 0.08f

    // ==================== ⑪ 变星半径项档（R349：r<SOLID_FLOOR 恒 0——球门 1_6dp；R355 阈值参透传） ====================

    /** 贴脸档变星径向搏动系数（真变星物理——只在看得见球时成立） */
    const val CEPHEID_R_COEF_CLOSEUP = 0.10f

    /**
     * 造父变星半径项系数（r333 §2.3）：r<[solidFloorPx] 恒 0（R349 换轨——
     * 「真变星径向搏动只在看得见球时成立」的球门=形态线，旧 30px 贴脸窗随门槛
     * 消亡；全景档光晕禁慢胀缩纪律原样）；r≥球门=[CEPHEID_R_COEF_CLOSEUP]。
     * 亮度调制保留（渲染端 alpha 支路不动——变星=亮度调制非尺寸调制）。
     * R355：球门参透传（渲染器传帧字段 [lodSolidFloorPx]——缺省 mdpi 档）。
     */
    fun cepheidRadiusCoef(
        rPx: Float,
        solidFloorPx: Float = TasteLodPolicy.SOLID_FLOOR_DP,
    ): Float =
        if (rPx >= solidFloorPx) CEPHEID_R_COEF_CLOSEUP else 0f

    // ==================== ⑫ 超新星静态遗迹壳（物质壳——非盘光，不受 2r 帽） ====================

    /** 超新星遗迹壳半径系数（r 倍数——恒定不随相位跑；r333 §2.3-1 定稿 2.2）。
     *  物质壳=被抛出的遗骸非光晕——[LIGHT_EXTENT_MAX_R] 盘光帽的域外件（类 KDoc
     *  边界：防误伤 R317b 静态壳资产）。 */
    const val SUPERNOVA_SHELL_R = 2.2f

    /** 遗迹壳 alpha（恒 0.30×A——不做衰减包络；丝缕弧参数走 TasteTerminalFx 同源族） */
    const val SHELL_ALPHA = 0.30f

    // ==================== ⑬ 预算帽（帧预算的量化护栏） ====================

    /** 点体位图星帽（按 r 降序 top 截断——R334 遗产，过渡期口径；**R339 二阶段
     *  随位图三档绘制退役**，新预算=[SPIKE_MAX_STARS] 星芒 top 门——星芒几条线
     *  段微成本全景比 64 张位图还省） */
    const val HALO_MAX_STARS = 64

    /** 懒烘焙每帧帽（点体 L1+R321 星风泡共用烘焙账——星风泡资产牵连，本帽不死；
     *  首帧桶缺=该星当帧走现状降级——1~2 帧内全桶热） */
    const val BAKE_PER_FRAME_MAX = 2

    /** 桶位图 LRU 帽（张——11 桶域内+1 wind；64px×4B≈16KB 每张总 192KB） */
    const val HALO_CACHE_MAX = 12

    // ==================== ⑭ 发现时刻光晕盛放（亮度语义——挂账二阶段重语义） ====================

    /** 盛放窗（秒）：alpha 瞬时抬满→线性回落到稳态 */
    const val REVEAL_HALO_SEC = 0.85f

    /** 盛放峰值因子（R339 注记：宽晕死亡后本因子只乘得 L1 点体亮度——「点亮」
     *  语义仍成立；二阶段改语义=点+星芒亮度盛放，函数名中的 halo 是历史包袱
     *  （改名破渲染器引用——二阶段接线时一并处理），挂账非静默） */
    const val REVEAL_HALO_K = 1.4f

    /** 盛放因子（ageSec小于0=未发现 1；窗内 [REVEAL_HALO_K]→1 线性；窗外恒 1） */
    fun revealHaloK(ageSec: Float): Float {
        if (ageSec < 0f) return 1f
        if (ageSec >= REVEAL_HALO_SEC) return 1f
        return 1f + (REVEAL_HALO_K - 1f) * (1f - ageSec / REVEAL_HALO_SEC)
    }
}
