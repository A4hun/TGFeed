package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.core.tastemap.EmissionNebulaFx
import dev.tgfeed.core.tastemap.ProperMotionFx
import dev.tgfeed.core.tastemap.TasteAchievements
import dev.tgfeed.core.tastemap.TasteAstroPhysics
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteFlight.FxEngine
import dev.tgfeed.core.tastemap.TasteNebula
import dev.tgfeed.core.tastemap.TasteShipFx
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteSpace.SpaceCam
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.core.td.VideoItem
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.sin

// ==================== R345 Canvas 老渲染引擎退役（用户 21:0x 裁决「新引擎已经好了，把老的干掉」） ====================
// 本文件原为 Canvas 渲染器 TasteFlightRenderer（5112 行——git 历史即注释），R345 整类
// 物理删除：Canvas 侧绘制函数族（draw* 全家）/位图烘焙/桶缓存/Paint 常量族全数退役。
// 保留共享件（GL 引擎消费——删除红线「装配/布局/数据准备/帧契约/物理场绝不能删」）：
//  - 帧契约：TasteFlightFrame + TasteFxStar/Edge/BinaryFx/PlanetEntry/Arc/Spread*/ConstTrail
//    （GlFrameBuilder 装配与 TasteMapScreen 组装段的唯一输入——TasteGlScene frameProvider）；
//  - 装配几何纯函数族：galaxyWellRadius/Ratio/H01/OrbitInclination/planetOrbitPoint/
//    planetOrbitRadius/planetWorldRadius/galaxyArcControl（GlFrameBuilder 显式 import 消费）。
// GL 镜像件（GlFrameBuilder/GlPalette/GlTextureBaker/私有 sortDesc）自 R345 起为唯一实现。

/**
 * R206 渲染用星（core 口径 + 展示字段）：**R242 空间口径**——x/y/z=球面化宇宙坐标
 * （TasteSpace.spherify 产出，Y 向上、以初始相机位为原点；StarNode 的 x/y/z 旧
 * 坐标留给海报=双口径零回归）。chatId 供命中/邻居/灯塔/爆闪；firstSeenMs 供创世纪
 * 凝聚。
 *
 * [kind]=天体分档（笔刷 switch 的单一数据源）；[tier]=R163 档位（T1 验货徽章环）；
 * [bodyR]/[bodyAlpha]/[bodyColor]=体量主轴口径（App 渲染消费）；[members]=星团
 * 尘点云规模；[vol]=体量条数（引力井半径原料）；[unexplored]=发现感渐显适用域；
 * [title]=R248 频道名（**已发现的星**名签消费——未发现星无名=神秘感；与 [label]
 * top8 旧口径并存：发现集合由帧侧传入，名签出场收口见 drawStars）。
 */
internal class TasteFxStar(
    val chatId: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val radius: Float,        // 旧观看口径（海报/兼容消费——本渲染器不消费）
    val colorArgb: Int,       // 旧口径颜色
    val alpha: Float,         // 旧口径亮度
    val label: String?,
    val firstSeenMs: Long,
    val hasComp: Boolean,
    val kind: CelestialKind = CelestialKind.STAR,
    val tier: Int = -1,
    val bodyR: Float = radius,
    val bodyAlpha: Float = alpha,
    val bodyColor: Int = colorArgb,
    val members: Int = -1,
    val vol: Int = 0,
    val unexplored: Boolean = false,
    val title: String = "",
    /** R249：近 7 天条数（-1=无金矿证据）——温度谱（光谱型选光晕色温档）原料 */
    val recent7: Int = -1,
    /** R249 v2：内容构成三票（金属丰度=多样性→表面特征数量——恒星 3D 方程原料） */
    val gVideo: Int = 0,
    val gPhoto: Int = 0,
    val gAudio: Int = 0,
    /** R311 色彩引擎：连续色温（K，黑体谱——0=旧三档 temp 桶；主序恒星恒 >0） */
    val tempK: Float = 0f,
    /** R311 造父变星：搏动周期秒（0=非变星）/振幅（周期-光度关系）/初相 0..1 */
    val pulsePeriodSec: Float = 0f,
    val pulseAmp: Float = 0f,
    val pulsePhase: Float = 0f,
    /** R328 批C 演出原料：诞生注记（null=未宣判/缺据——聚合拍进度亮度/行星成型
     *  凝缩调制/点燃拍年轻判别的原料；TasteBirthFx 单源消费，判据族注记直供） */
    val birth: TasteAstroPhysics.BirthFeatures? = null,
    /** R328 批C 演出原料：最近活动 epoch 秒（0=无证据恒生——生灭回放 diedVisible
     *  渲染门原料，TasteLifeReplay 单口消费） */
    val lastActivitySec: Long = 0L,
    /** R321 星风门原料：无向连接数（互链/共现边——R248 现役字段；0=无外流通道
     *  无风；[StarwindFx.isWindy] 双门消费） */
    val degree: Int = 0,
    /** R323 自行漂移注记（null=未入池/缺据/零漂移=零矢量；[ProperMotionFx] 单源
     *  出值——选中/聚焦星的极细小尾巴焦点件消费） */
    val drift: ProperMotionFx.Drift? = null,
)

/** 渲染用边：两端空间坐标 + 端点 chatId（焦点邻边高亮）+ 边权透明度 */
internal class TasteFxEdge(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val bx: Float,
    val by: Float,
    val bz: Float,
    val a: Long,
    val b: Long,
    val alpha: Float,
)

/**
 * R321 双星对渲染输入（洛希潮汐形变/拉格朗日特洛伊簇的原料——帧字段
 * [TasteFlightFrame.binaries] 直供）：两端 chatId+**共源上游 W**
 * （[TrojanFx] 亮度/粒数原料；0=无共源=特洛伊簇零渲染不冒充驿站）。
 * 同源 W（潮汐预警行）不进渲染层——factLine 信息卡侧消费（装配 BinaryPair）。
 */
internal class TasteBinaryFx(
    val a: Long,
    val b: Long,
    val trojanW: Int,
)

// ==================== R233 行星层 / 内容关联弧（ui 内共享口径件） ====================

/** 行星系统渲染条目（渲染与命中/播放共用；system=core 轨道参数、items=近端样本条目） */
internal class TastePlanetEntry(
    val chatId: Long,
    val system: TasteTagPlanets.PlanetSystem,
    val items: List<VideoItem>,
)

/** 内容关联弧（焦点态渲染/命中共用）：端点=空间坐标（烘焙进弧——渲染零查表）；
 *  R249 v2：双端 chatId 齐备（N 体位移端点跟随） */
internal class TasteArc(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val bx: Float,
    val by: Float,
    val bz: Float,
    val toChatId: Long,
    val colorArgb: Int,
    val alpha: Float,
    /** R249：源端 chatId（0=未知——不跟随） */
    val fromChatId: Long = 0L,
)

// ==================== R234 尘埃带 / 星座轨迹（渲染/烘焙模型） ====================

/** 尘埃带单条弧带（源恒星 → 下游频道）：端点/控制点与全部粒子空间坐标装配期烘焙 */
internal class TasteSpreadBand(
    val toChatId: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val bx: Float,
    val by: Float,
    val bz: Float,
    val cx: Float,
    val cy: Float,
    val cz: Float,
    val firstMs: Long,
    val itemCount: Int,
    val particleMs: LongArray,
    val particleX: FloatArray,
    val particleY: FloatArray,
    val particleZ: FloatArray,
    val particleBucket: ByteArray,
)

/** 尘埃带渲染模型（关键字传播可视化——每粒尘埃=一条真实带该 tag 的条目） */
internal class TasteSpreadRender(
    val tag: String,
    val sourceChatId: Long,
    val sx: Float,
    val sy: Float,
    val sz: Float,
    val t0Ms: Long,
    val t1Ms: Long,
    val colorArgb: Int,
    val recalled: Int,
    val channelCount: Int,
    val sampled: Boolean,
    val bands: List<TasteSpreadBand>,
)

/** 星座轨迹（今晚的你走过了这片星域）：站点串空间折线 + 每站时间戳 */
internal class TasteConstTrail(
    val n: Int,
    val xs: FloatArray,
    val ys: FloatArray,
    val zs: FloatArray,
    val tsMs: LongArray,
)

/** R233 井半径基座（世界单位）：最小频道的井（行星轨道的内骨架外圈） */
internal const val GALAXY_WELL_BASE = 20f

/** R233 井半径体量增益（世界单位）：×ln1p(条数)/ln1p(全池 max)——大频道引力井大 */
internal const val GALAXY_WELL_K = 34f

/** R233 引力井半径（世界单位）：渲染 LOD 与命中/轮询三方共用单一出处 */
internal fun galaxyWellRadius(vol: Int, maxVol: Int): Float =
    GALAXY_WELL_BASE + GALAXY_WELL_K *
        (if (vol > 0 && maxVol > 0) ln1p(vol.toFloat()) / ln1p(maxVol.toFloat()) else 0.5f)

/**
 * R242 井深比（无量纲，**越小越近**）：**|星位−相机位| 3D 距离** ÷ 井半径——
 * 井深比判定改用新相机的距离（任务书口径）；无量纲=与设备/分辨率无关。
 */
internal fun galaxyWellRatio(
    starX: Float,
    starY: Float,
    starZ: Float,
    vol: Int,
    maxVol: Int,
    cam: SpaceCam,
): Float {
    val wellR = galaxyWellRadius(vol, maxVol)
    if (wellR <= 0f) return Float.MAX_VALUE
    return TasteSpace.distTo(cam, starX, starY, starZ) / wellR
}

/** 确定性 0..1 哈希（与 FxEngine.h01 同式——渲染/命中/飞进行星共用，top-level 单口） */
internal fun galaxyH01(a: Int, b: Int): Float =
    (((a * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF).toFloat() / 16777216f

/** R242 系统轨道倾角（±34°，chatId 哈希——渲染/命中/飞进行星同一式） */
internal fun galaxyOrbitInclination(seed: Int): Float = (galaxyH01(seed, 71) - 0.5f) * 1.2f

/**
 * R242 行星系统轨道点（世界系）：绕星心水平面 3D 圆 + 系统级哈希倾角——空间
 * 任意视角都有立体轨道感（**渲染/命中/飞进行星同一式——单口**）。写 [out] 长 ≥3。
 */
internal fun planetOrbitPoint(
    starX: Float, starY: Float, starZ: Float,
    radiusW: Float, theta: Float, incRad: Float, out: FloatArray,
) {
    val ct = cos(theta)
    val st = sin(theta)
    out[0] = starX + ct * radiusW
    out[1] = starY - st * radiusW * sin(incRad)
    out[2] = starZ + st * radiusW * cos(incRad)
}

/**
 * R247 行星系统轨道半径（世界单位）：**恒星视觉半径 bodyR 锚定**——
 * `bodyR×(1.9+3.4×orbitR01)`（∈1.9~5.0×恒星视觉半径）。R242 旧口径
 * `orbitR01×wellR` 与恒星视半径量纲解耦（井=条数对数口径 vs 体量√口径）：
 * 大星内圈轨道可小于恒星本体=行星骑恒星脸上（用户「跟恒星重叠」实锤）；
 * 换锚后内圈恒 >1.9×恒星半径（光核+本体之外），行星永远在恒星面外绕行。
 * 渲染/命中/飞进行星门四方同一式（消灭 4 处内联 `orbitR01×wellR`——类收敛单口）；
 * tag 频率仍调制圈序（高频靠内语义不变，TasteTagPlanets 数据层零改动）。
 */
internal fun planetOrbitRadius(p: TasteTagPlanets.TagPlanet, starBodyR: Float): Float =
    starBodyR * (1.9f + 3.4f * p.orbitR01)

/**
 * R247 行星世界半径（世界单位）：**恒星视觉半径锚定**——`bodyR×planetRadiusCoef`
 * （planetRadiusCoef∈0.115~0.20=行星:恒星 1:8.7~1:5——用户口径「行星是恒星的伴星
 * 不是同等大小」；R242 旧口径传 wellR（井=条数对数口径）对中位星行星可达恒星的
 * 2/3=双星观感实锤——系数的核心语义「×恒星视半径」本就如此，本侧换正锚），
 * 上限 0.30×轨道半径（防吞轨护栏，常态不触发）。真透视长大语义保留（飞近行星
 * 真的变大直到充满屏幕）；渲染/命中/飞进行星门共用。
 */
internal fun planetWorldRadius(p: TasteTagPlanets.TagPlanet, starBodyR: Float, rW: Float): Float =
    (starBodyR * TasteTagPlanets.planetRadiusCoef(p)).coerceAtMost(rW * 0.30f)

/**
 * R233 关联弧控制点（空间坐标）：两端中点 + XY 垂直偏移 14% 弦长 + z 抬升 10%
 * 弦长——绘制与命中采样同一式（写 [out] 长 ≥3）。
 */
internal fun galaxyArcControl(
    ax: Float,
    ay: Float,
    az: Float,
    bx: Float,
    by: Float,
    bz: Float,
    out: FloatArray,
) {
    val dxw = bx - ax
    val dyw = by - ay
    val len = hypot(dxw, dyw)
    if (len < 1f) {
        out[0] = (ax + bx) * 0.5f
        out[1] = (ay + by) * 0.5f
        out[2] = (az + bz) * 0.5f
        return
    }
    out[0] = (ax + bx) * 0.5f - dyw * 0.14f
    out[1] = (ay + by) * 0.5f + dxw * 0.14f
    out[2] = (az + bz) * 0.5f + len * 0.10f
}

/**
 * R242 单帧渲染输入（DrawScope 侧组装）：**cam=SpaceCam 全向相机**（位置+
 * yaw/pitch）+ [lens]=焦距倍率（+/- 无障碍按钮）；星/边/弧/带/轨迹=球面化空间
 * 坐标（TasteMapScreen 侧烘焙）。
 */
internal class TasteFlightFrame(
    val cam: SpaceCam,
    val stars: List<TasteFxStar>,
    val edges: List<TasteFxEdge>,
    val engine: FxEngine,
    val genesisOn: Boolean,
    val genesisSpan: TasteFlight.GenesisSpan,
    val genesisT01: Float,
    val playingChatId: Long,
    val selectedId: Long,
    val labelMinPx: Float,
    val labelTextPx: Float,
    val revealT: Float = 1f,
    val lens: Float = 1f,
    /** 双击运镜锁定星（目标环渲染——「锁定+飞向它」的锁定态） */
    val lockChatId: Long = 0L,
    // ---- R233 行星层 + 内容关联弧 ----
    val planetSys: Map<Long, TastePlanetEntry> = emptyMap(),
    val arcs: List<TasteArc> = emptyList(),
    val dustChatId: Long = 0L,
    val wellMaxVol: Int = 0,
    // ---- R234 尘埃带 + 星座轨迹 ----
    val spread: TasteSpreadRender? = null,
    val spreadCursorMs: Long = Long.MAX_VALUE,
    val constellation: TasteConstTrail? = null,
    val showConstellation: Boolean = false,
    // ---- R235 RPG 层 ----
    val nebulae: List<TasteNebula.NebulaCluster> = emptyList(),
    val ship: TasteShipFx.ShipTrailEngine? = null,
    val shipStyle: TasteAchievements.ShipStyle = TasteAchievements.ShipStyle.MICRO_TRAIL,
    val wormholeP01: Float = -1f,
    // ---- R248 星名点亮（发现机制帧输入——默认值=旧行为零变化） ----
    /** 已发现（点亮）星集合：persisted ∪ 已看过 ∪ 已订阅 ∪ 特殊天体 ∪ 会话新发现 */
    val discoveredIds: Set<Long> = emptySet(),
    /** 会话新发现（chatId → 发现时刻 epoch ms——打字机名签+扩散环的动画驱动；旧条目由消费方剪枝） */
    val revealIds: Map<Long, Long> = emptyMap(),
    /** 当帧 epoch ms（reveal 动画的时钟——0=无动画） */
    val nowMs: Long = 0L,
    // ---- R249 星系物理场（引力井表+微轨漂移数组——null=旧行为零变化） ----
    /** 物理场（井/漂移渲染期直读；step 由屏幕侧 5s 一拍推进） */
    val gravity: TasteGalaxyPhysics? = null,
    // ---- R302 星辉层（design-rpg-loop 成长循环——默认值=旧行为零变化） ----
    /** Lv5 远视：未探索星渐显起点（世界单位——TasteStarlight.discoverNearStartOf 出值） */
    val discoverNearStart: Float = TasteNebula.DISCOVER_NEAR_START,
    /** Lv2 罗盘：升级差 ≤20% 时屏缘 chevron 提亮（「差一点升级」的指引档） */
    val hintBoost: Boolean = false,
    /** 星域迷雾：已点亮星簇质心（世界系——雾的距离参照；无点亮星=无雾） */
    val mistCx: Float = 0f,
    val mistCy: Float = 0f,
    val mistCz: Float = 0f,
    /** 雾界（世界单位——TasteStarlight.mistReachOf 等级函数出值；≤0=无雾） */
    val mistReach: Float = 0f,
    // ---- R314 星图治理（默认值=旧构造点零变化） ----
    /** 互链连线网开关（AppPrefs taste_link_lines）：true=旧行为（全图实体直线网）；
     *  false=默认不划线（仅焦点星互链画细弱引力弧——政策件 TasteLinkLinesPolicy） */
    val linkLines: Boolean = true,
    // ---- R319 宇宙底幕（taste_cmb 单键一层：CMB 余辉+奥伯斯视界+「光在路上」暗点；
    //      默认值=旧构造点零变化——关=纯黑宇宙整层缺席） ----
    /** 底幕开关（AppPrefs taste_cmb 默认开——「要纯黑就全黑」） */
    val cmbOn: Boolean = false,
    /** CMB 纹理种子（装配期基因指纹 genomeFp——同库同纹理，位图/纹理按它缓存） */
    val cmbSeedFp: Long = 0L,
    /** 奥伯斯带（R_obs+暗点世界位+差集记账；null=星表空缺据整层缺省） */
    val cmbZone: dev.tgfeed.core.tastemap.CmbBackdropFx.OlbersZone? = null,
    // ---- R321 近距关系批（洛希+拉格朗日+星风——默认值=旧构造点零变化） ----
    /** 双星对渲染输入（潮汐形变/特洛伊簇原料；空=零行为——洛希形变与簇全缺省） */
    val binaries: List<TasteBinaryFx> = emptyList(),
    // ---- R323 发射星云批（r315 K 章——默认值=旧构造点零变化） ----
    /** 发射星云平行表（与 [nebulae] **同序同长**：发射判据/星胚粒数/亮档色/种子；
     *  普通雾=emission false 零绘制；driftNebulaeR282 摇曳保序=星胚随雾心跟动） */
    val emissionFogs: List<EmissionNebulaFx.Fog> = emptyList(),
    // ---- R373 深空星云复合体（design-nebula §3.2/§5.1/§十一——默认值=旧构造点零变化） ----
    /** 深空复合体（摇篮——未探索质量场低通涌现 ≤4 团；空=复合体层零绘制零成本早退；
     *  R282 漂移摇曳保序=与雾族同口径亚感知） */
    val complexes: List<DeepSpaceNebula.Complex> = emptyList(),
    /** 富集反射微光云（终审增补——全星表密度场热区 ≤4 片；空=零绘制） */
    val enrichClouds: List<DeepSpaceNebula.EnrichCloud> = emptyList(),
)

// （R345 尾注：类 TasteFlightRenderer 本体已整类删除——恢复/考古走 git 历史；
//  旧 render_engine=Canvas 偏好读侧归一化见 ui/gl/GlEnginePolicy.legacyCanvasPref。）
