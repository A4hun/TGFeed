package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.CmbBackdropFx
import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.core.tastemap.EclipseFx
import dev.tgfeed.core.tastemap.EmissionNebulaFx
import dev.tgfeed.core.tastemap.FogFlocFx
import dev.tgfeed.core.tastemap.GravityWaveFx
import dev.tgfeed.core.tastemap.ProperMotionFx
import dev.tgfeed.core.tastemap.RocheTidalFx
import dev.tgfeed.core.tastemap.StarwindFx
import dev.tgfeed.core.tastemap.TasteAchievements
import dev.tgfeed.core.tastemap.TasteAstroPhysics
import dev.tgfeed.core.tastemap.TasteBirthFx
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteLifeReplay
import dev.tgfeed.core.tastemap.TasteLinkLinesPolicy
import dev.tgfeed.core.tastemap.TasteLodPolicy
import dev.tgfeed.core.tastemap.TasteNebula
import dev.tgfeed.core.tastemap.TasteShipFx
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteStarColor
import dev.tgfeed.core.tastemap.TasteStarGlow
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.core.tastemap.TasteTagSpread
import dev.tgfeed.core.tastemap.TasteTerminalFx
import dev.tgfeed.core.tastemap.TasteWormhole
import dev.tgfeed.core.tastemap.TrojanFx
import dev.tgfeed.ui.TasteFlightFrame
import dev.tgfeed.ui.galaxyArcControl
import dev.tgfeed.ui.galaxyH01
import dev.tgfeed.ui.galaxyOrbitInclination
import dev.tgfeed.ui.galaxyWellRatio
import dev.tgfeed.ui.planetOrbitPoint
import dev.tgfeed.ui.planetOrbitRadius
import dev.tgfeed.ui.planetWorldRadius
import dev.tgfeed.ui.youngBirthStarR328
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R277 GL 帧装配器（**纯 Kotlin 零 android 依赖**——DTO→GL 顶点的唯一装配口，
 * JVM 单测锚定；「画什么/怎么画」解耦的 GL 半边）：
 *
 * 输入=与 Canvas 渲染器同一帧契约 [TasteFlightFrame]；输出=预分配缓冲（点精灵/
 * 线段/纹理四边形/真球实例）+画家序 draw op 流+名签候选——TasteGlRenderer 只负责
 * 上传与回放（帧路径零 glGen/glBufferData/着色器编译）。
 *
 * 数学全部复用 core 单口（TasteSpace.project 投影/TasteAstroPhysics 透镜/
 * TasteTagPlanets 开普勒/TasteNebula 发现感）或与 Canvas 渲染器**同式镜像**
 * （常量逐值一致——P1 收敛挂账：共享笔刷参数件进 core，两处镜像只留一份）。
 *
 * 画家序纪律（与 Canvas 逐位同序）：op 流按**类型切换即时冲刷**记录——
 * addPoint/addLine/addQuad/addSphere 每次换类型先把上一类型的攒批落成 op，
 * 批内连续合并；同键四边形相邻合并=少 draw call。
 *
 * 帧零分配纪律：全部缓冲字段级预分配复用；build 内零数组分配（名签候选为
 * ArrayList 复用——发布侧（渲染器）负责拷贝成不可变快照换装）。
 */
internal class GlFrameBuilder {

    companion object {
        // ---- 原 Canvas TasteFlightRenderer 镜像常量（R345 退役后本表为唯一源） ----
        // R349：三门槛镜像常量退役（MICRO_STAR_PX 2.2/STAR_BALL_MIN_R 30/
        //   STAR_CORE_MIN_R 10——形态线换轨后零代码引用，单源收口
        //   [TasteLodPolicy]（POINT_CEIL_DP/SOLID_FLOOR_DP/LOWMESH_MAX_DP——
        //   R355 dp 基准×密度单口换算入帧字段）——
        //   「同值同改」双端镜像族终结；绝迹锚=R349WiringSourceTest）
        // R339：旧 12f 斜刺门常量随 r 门十字退役物理删除
        //   （GL 侧星芒门=[TasteStarGlow.SPIKE_MAX_STARS] 亮度 top 门；Canvas 侧
        //   同名常量随 R345 Canvas 退役批走）
        private const val REVEAL_BATCH = 200
        private const val LABEL_CAP = 8
        private const val UNDISCOVERED_PULSE_MIN = 0.74f
        private const val GRID_Y = -300f
        private const val GRID_EXT = 848f
        private const val GRID_STEP = 212f
        private const val DISK_OUTER = 2.35f
        private const val DISK_INNER = 1.62f
        private const val DISK_PARTICLES = 18
        private const val PHOTON_RING = 1.22f
        // R334：引力波纹 6s 扩散环退役（镜像 Canvas——真引力波=R320 波前星点位移
        // 已顶替其语义），RIPPLE_SEC 死常量随删
        private val MICRO_TIER_W = floatArrayOf(2.4f, 1.7f, 1.15f)
        private val MICRO_TIER_A = floatArrayOf(0.86f, 0.55f, 0.32f)
        // ---- R280 P1 镜像常量（R345 Canvas 退役后本表为唯一源） ----
        private const val REVEAL_FX_SEC = 2.5f
        private const val REVEAL_NAME_MS = 1200f
        private const val CORONA_MIN_R = 90f
        private const val CORONA_STREAMS = 12
        private const val SHIP_STREAK_N = 18
        private val SPREAD_BUCKET_ALPHA = floatArrayOf(0.30f, 0.46f, 0.62f)
        private const val CONST_COLOR = 0xFFDDEBFF.toInt()
        private const val CONST_DOT_COLOR = 0xFFEAF4FF.toInt()
        private const val HINT_COLOR = 0xFFEAF4FF.toInt()
        private const val TRAIL_COLOR = 0xFFDDEBFF.toInt()
        private const val METEOR_COLOR = 0xFFEAF4FF.toInt()
        private const val COMET_COLOR = 0xFFF2F7FF.toInt()
        private const val FLOW_COLOR = 0xFFEAF4FF.toInt()
        private const val WORM_COLOR = 0xFF9ECFFF.toInt()
        // R348 C2：灯塔扫掠角速度（°/s）——宪法二章 14 条裁决 8~12°/s 取中 10：
        // 66°/s 探照灯语感退场，改缓慢仪式感扫掠（36s/圈）；在播标记语义（在播=哪里）保真
        private const val BEACON_SWEEP_DEG_S = 10f
        // R321 特洛伊簇微亮点色（0xEAF4FF——Canvas trojanPaint 同值同改）
        private const val TROJAN_COLOR = 0xFFEAF4FF.toInt()

        /** 缓冲护栏（超帽静默截断——星图 600 星满池口径 2 倍余量） */
        const val MAX_POINTS = 8192
        const val MAX_LINE_VERTS = 16384
        const val MAX_QUADS = 1024
        /** 全模真球帽（R349 后语义收窄：**全模/逐实例 uniform 路径帽**——r≥8px
         *  贴脸档；低模批档走 [MAX_SPHERE_I] 独立帽。日冕同帽（贴脸件）） */
        const val MAX_SPHERES = 48
        const val MAX_OPS = 1024

        /** draw op 类型（渲染器回放分发） */
        const val OP_POINTS = 1
        const val OP_LINES = 2
        const val OP_QUADS = 3
        const val OP_SPHERE = 4
        const val OP_CORONA = 5
        // ---- R281 新 op（P2 超越期：GPU 投影化+真几何+真身建模） ----
        const val OP_SKY = 6           // 天幕静态 VBO（GPU 投影——无参数）
        const val OP_KEPLER_LINES = 7  // 轨道线（A=系统描述槽 B=起始顶点 C=顶点数）
        const val OP_KEPLER_POINTS = 8 // 尘埃点（同上行参）
        const val OP_WPOINTS = 9       // 世界点（A=起始点 B=点数——星团成员球等）
        const val OP_STRIP = 10        // 世界三角带（A=起始顶点 B=顶点数——彗星双尾）
        const val OP_PLANET = 11       // 行星真球（A=行星实例槽）
        const val OP_RING = 12         // 真 3D 环带（A=环实例槽——土星环/吸积盘双模）
        const val OP_ROCK = 13         // 岩石实例（A=起始实例 B=实例数——instancing 一 draw）
        const val OP_NEBULA = 14       // 体积星云程序雾（A=星云实例槽）
        // ---- R349 低模球 instanced 批（A=起始实例 B=实例数——OP_ROCK 同款一 draw/连续段） ----
        const val OP_SPHERE_I = 15

        /** 纹理键（渲染器映射 GL 纹理 id） */
        const val TEX_RING = 1
        const val TEX_SPIKE = 2
        const val TEX_BEAM = 3
        const val TEX_DISC = 5 // 实心硬边圆盘（星爆闪提亮盘/引擎脉冲芯）
        const val TEX_FOG = 6 // 径向软雾（流星彗头晕/引擎脉冲——R280 P1）
        const val TEX_WHITE = 7 // 纯色白件（虫洞暗幕等无纹理着色四边形底）
        // R319：CMB 底幕纹理键（640 斑驳白噪 alpha 图——**动态纹理**：种子=帧字段
        // cmbSeedFp（基因指纹），渲染器在 genomeFp 变更窗才重烘重传，帧内只查相等）
        const val TEX_CMB = 8
        // R281 注：行星位图族（PLANET_TEX_BASE）与黑洞吸积盘纹（TEX_DISK）随
        // 行星真球化/吸积盘真 3D 环带化整体退役（位图路线=Canvas 时代的沉没件）。

        /** 点精灵步长（float/点）：x,y,size,coreRatio + 边缘 rgba + 核心 rgb */
        const val POINT_STRIDE = 11

        /** 线段步长（float/顶点）：x,y,r,g,b,a */
        const val LINE_STRIDE = 6

        /** 四边形步长（float/顶点）：x,y,u,v,r,g,b,a（6 顶点/quad） */
        const val QUAD_STRIDE = 8

        /** 真球实例步长（float/颗）：位 3 + 世界径 1 + alpha 1 + 三段色 9 + 颗粒振幅 1 + 噪声种子 1 + 自转角 1 + 时间 1 + 黑子幅 1 + 火苗幅 1 + 热星旗 1 + 临边昏暗系数 1（R311 uLimbK——色温从动）+ R321 潮汐槽 5（轴 3+grow 2——uTideAxis/uTideGrow 契约跟改申报件）+ 预留 4（R321 24→32：8 对齐） */
        const val SPHERE_STRIDE = 32

        /**
         * R349 低模球实例步长（float/颗，6×vec4 属性对齐）：位 3 + 世界径 1 +
         * 芯色 3 + alpha 1（已乘 blend k）+ 星色 3 + 颗粒幅 1 + 缘色 3 + 种子 1 +
         * 自转 1 + 时间 1 + 黑子幅 1 + 火苗幅 1 + 热星旗 1 + 临边系数 1 +
         * **槽 22=R355 退役位恒 0（旧 blend 带底光 ambient——平盘期彻底删除：
         * 真 3D 即刻生效；槽复归预留无消费）** + 预留 1（恒 0）。
         * **R321 槽位纪律**：全 24 槽装填时**无条件逐槽写**（含预留槽恒 0——帧间
         * 复用残留结构性不可达；低模档无潮汐槽=形变亚 10px 不可辨，潮汐=全模档
         * 专享 r349_notes §2.5 裁决）。
         */
        const val SPHERE_I_STRIDE = 24

        /** R349 低模球实例帽（r341 §4.2「MAX 低模球实例 96」——回退序③收 64/32；
         *  超帽=最小屏径优先出球籍回点档（[selectLowSpheresR349] 预选+截断记账）） */
        const val MAX_SPHERE_I = 96

        /** 日冕实例步长（float/颗）：屏位 2 + 半幅 1 + 种子 1 + 时间 1 + 核色 3 + alpha 1 + 火苗幅 1（R311 日珥拱活动烈度原料） */
        const val CORONA_STRIDE = 10

        // ---- R281 新缓冲步长 ----
        /** 开普勒顶点步长：ν 1 + 径向系数 1 + 世界尺寸 1 + rgba 4 */
        const val KEP_STRIDE = 7

        /** 开普勒系统描述步长：心 3 + 近日点角 1 + 倾角 sin/cos 2 + 半长轴 1 + 偏心率 1 */
        const val KEP_SYS_STRIDE = 8

        /** 世界点步长：位 3 + 世界尺寸 1 + coreRatio 1 + rgba 4 + 核心 rgb 3 = 12 */
        const val WP_STRIDE = 12

        /** 世界三角带步长：位 3 + uv 2 + rgba 4 */
        const val STRIP_STRIDE = 9

        /** 行星实例步长：心 3 + 世界径 1 + 光向 3 + 基色 3 + 自转 1 + 种子 1 + 气态旗 1 + alpha 1 */
        const val PLANET_STRIDE = 14

        /** 环实例步长：心 3 + 行星径 1 + 倾角 X/Z 2 + 光向 3 + 种子 1 + alpha 1 + 模式 1 + 色 3 + 进动角 1（R350 预留槽 15 启用——0=恒等；mode=2 遗迹环真值） */
        const val RING_STRIDE = 16

        /** 岩石实例步长：位+径 4 + 轴+角 4 + 色+alpha 4 + 光向+热缘 4 + 种子 1 */
        const val ROCK_STRIDE = 17

        /**
         * 星云实例步长：屏位 2 + 半幅 1 + 种子 1 + 时间 1 + 色 3 + alpha 1 +
         * **频率标度 1**（R388 刀2 槽 9=uFreqScale 装配值——TasteGlRenderer
         * OP_NEBULA 回放上传；雾族行恒 1f=既有基因恒等，复合体行=clamp(rPx/
         * [cpxFilRefPx], [CPX_FIL_FLOOR], 1)）
         */
        const val NEB_STRIDE = 10

        /** 光斑源步长：ndc 2 + 色 3 + 强度 1 + 正对度 1 */
        const val FLARE_STRIDE = 7

        /** R281 新缓冲帽 */
        const val MAX_KEP_VERTS = 4096
        const val MAX_KEP_SYS = 160
        const val MAX_WPOINTS = 1024
        const val MAX_STRIP_VERTS = 512
        const val MAX_PLANETS = 32
        const val MAX_RINGS = 16
        const val MAX_ROCKS = 64
        /**
     * 星云实例帽（R351 雾絮化 12→24 扩容——帽账：雾 6 团×blob 3=18 顶格+发射
     * 电离侧辉 ≤6=24 顶格；奥伯斯 18 向壳共用此帽——雾满时壳让位的溢帽静默
     * 截断语义保持（R319b 后既有行为），本批零语义变化只扩容。nebBuf
     * 12×9=108→24×9=216 浮点=预分配一次性扩容（R388 槽 10 后=24×10=240——频率
     * 标度槽随步长同步扩容），帧零分配纪律保持）。
     */
    const val MAX_NEBULAS = 24

    /**
     * R373 复合体独立预算行帽（design-nebula §5.1「≤4 团×层×blob ≤16 quads
     * 独立预算行」）：复合体 ≤4×（2~3 视差层 blob）=12 顶格+富集反射云 ≤4×1
     * 单层=4——合计 16 顶格。**与 MAX_NEBULAS=24 雾行合并审计定帽 40**：
     * 雾行（雾团+奥伯斯壳+发射侧辉）独享原 24 帽零挤压（P1 结论零碰——复合体
     * 走 [addNebulaCpx] 独立计数写入 nebBuf 的 24 槽后段），总帽=nebBuf 容量
     * （24+16）×NEB_STRIDE 浮点（R388 槽 10 后=400）=预分配一次性扩容，帧零
     * 分配纪律保持。
     */
    const val MAX_NEBULA_CPX = 16
        const val MAX_FLARES = 3

        /** 彗尾世界深度（屏轨迹反投影的锚定深——彗尾=世界系真几何） */
        private const val COMET_DEPTH = 520f

        /**
         * R386 复合体远档裁剪阈（**dp 基准**——R355 家族密度岔路翻正：旧 150px
         * 裸 px 阈（px 口径未乘密度）在 400dpi（2.5×）下表观 60dp 即出多层，mdpi
         * 设计基线 150dp 同云在小米被抬进多层档=密度岔路病；px 阈=本值×密度，
         * build 帧首单口换算入 [cpxFarMinPx]——mdpi 恒等档 150px 数值原样=基线
         * 路由零变化，跨密度同 dp 同路由同形态）：复合体投影半径低于此值=远档
         * 裁至单层（design §5.1「远档随距离裁剪省填充率」——fbm3 octave 恒定
         * （R358 uniform 四件锚所迫），以层数裁剪达成）。**形态判词（用户 08:2x
         * 小米实锤：星云复合体=无结构弥散光雾球，无丝缕无留空）**：远距多层=
         * ±0.5R 层深+≤0.45R blob 错位在远距投影近同心叠，各层 fbm 丝缕互填丝间
         * 留空+层心 falloff 叠亮=均匀大团（R252 家族三代病回潮）；远档单层=
         * NEBULA_FS 脊线丝缕+丝间留空单型干净在场——**远=单层即丝缕形态保义**。
         */
        const val CPX_FAR_MIN_DP = 150f

        /**
         * R388 刀1 复合体远档隐没带内沿（**dp 基准**——px 阈=本值×密度入帧字段
         * [cpxFarHideMinPx]，R386 [CPX_FAR_MIN_DP] 同式先例）：复合体投影半径
         * 低于此值=**看不清就不画**（直接 continue 零装填——诚实缺省同族：亚
         * 分辨圆饼读作星场；含 [buildComplexesR373] 富集反射微光块——同一病类
         * 同带）。判词（用户 2026-10-25 14:1x 裁决根治刀）：星图全景纯星空，
         * 飞近才见云——R351 弥散保义下圆包络不可锐边治，**「不画」是唯一根治位**。
         * 校准轮 1（2026-10-25 16:2x 小米实测）：60→90dp——带下沿抬至亚分辨
         * 区上沿（225px@2.5 档 quad 半径以下丝缕不可辨）。
         */
        const val CPX_FAR_HIDE_MIN_DP = 90f

        /**
         * R388 刀1 复合体远档隐没带外沿（dp 基准——px 阈=本值×密度入帧字段
         * [cpxFarHideMaxPx]）：带内 [CPX_FAR_HIDE_MIN_DP]..本值 C¹ smoothstep
         * 渐隐至零（[farHide01] 单源），带外满档=交 R388 刀2 频率锁定档（判词链
         * 「星场→稀粗丝」的交界带）。**消光账同步**：cpxPresence（消光半边）
         * 乘同一淡出因子——看不见的云不减光（物理一致：隐没复合体在星层
         * pass1 不再挡光，R385 P4 消费口其余零碰）。
         * 校准轮 1（16:2x 小米全景像素定量：病灶复合体辉斑 1376×896px/等效
         * R452px=181dp/平均亮度 24.5——rPx0 量级 550~700px≈220~280dp）：110→
         * 200dp（500px@2.5）——带外沿抬至覆盖中小复合体全景档；大复合体
         * （>200dp）交刀2 稀粗丝档不再整团隐没（判词链「远=稀粗丝」正位）。
         */
        const val CPX_FAR_HIDE_MAX_DP = 200f

        /**
         * R388 刀2 丝缕频率锁定参考屏半径（**dp 基准**——px 阈=本值×密度入帧
         * 字段 [cpxFilRefPx]）：复合体/富集 quad 投影半径 rPx≥此值=scale 1
         * （**既有设计基因原样**——近档纱幔多层视差=梦的本体零碰）；rPx<此值=
         * scale=clamp(rPx/此值, [CPX_FIL_FLOOR], 1)（NEBULA_FS 第五件
         * uFreqScale——丝缕噪声频率×scale：远小 quad 低频=**粗丝留空大**，
         * 任何屏距丝缕像素宽恒定）。判词：「丝缕基因的尺度变换非基因改变」——
         * 同一噪声场 zoom（z=种子身份槽不动）；R358 判词「丝间留空看得见底星」
         * 在远距由恒定丝宽真正成立。终态链：星场（极远）→稀粗丝（远）→纱幔
         * （近）→raymarch 体积（贴脸，P3 零碰）。
         * 校准轮 1（2026-10-25 16:2x 小米实测）：220→300dp（750px@2.5）——
         * 病灶复合体全景 blob rPx 300~688px 原多落 REF 上方=scale 1 全基因
         * 两层叠=饼回潮；REF 抬至其上使全景 blob 落 0.40~0.92 标度域=稀粗
         * 丝档（刀2 首次真正咬到病灶）。
         */
        const val CPX_FIL_REF_DP = 300f

        /**
         * R388 刀2 频率标度下限（纯比值零密度口径）：超远 quad 防退化单团
         * （scale 地板 0.35≈图案 2.9× 粗——再低=一个噪声胞元糊满 quad=新病）。
         */
        const val CPX_FIL_FLOOR = 0.35f

        /** 尘埃岩石化门槛（px——尘点视尺寸 ≥ 此值时前 24 粒换岩石真身） */
        private const val DUST_ROCK_MIN_PX = 3.0f

        /** 每系统尘埃岩石实例上限（前景大粒=多面体，余者保点精灵=诚实 LOD） */
        private const val DUST_ROCK_CAP = 24

        // op 流的类型切换标记
        private const val T_NONE = 0
        private const val T_POINTS = 1
        private const val T_LINES = 2
        private const val T_QUADS = 3
        private const val T_SPHERE = 4
        private const val T_IMMED = 5 // R281 即时 op（无攒批——冲刷既有批后直接落 op）
        // R349 低模球攒批（连续装填=一次 instanced draw——OP_ROCK 批语义的
        // switchType 纪律平移：换类型冲刷 ≤数段）
        private const val T_LOW_SPHERE = 6
    }

    // ==================== 输出缓冲（全预分配——帧零分配） ====================

    val pointBuf = FloatArray(MAX_POINTS * POINT_STRIDE)
    var pointCount = 0
        private set

    val lineBuf = FloatArray(MAX_LINE_VERTS * LINE_STRIDE)
    var lineVertCount = 0
        private set

    val quadBuf = FloatArray(MAX_QUADS * 6 * QUAD_STRIDE)
    var quadCount = 0
        private set

    val sphereBuf = FloatArray(MAX_SPHERES * SPHERE_STRIDE)
    var sphereCount = 0
        private set

    /**
     * R349 低模球实例缓冲（2~8px 批档——SPHERE_I instancing；[SPHERE_I_STRIDE]
     * 语义；攒批段=一次 glDrawElementsInstanced）。R321 槽位纪律：装填全槽
     * 无条件写（预留槽恒 0——帧间复用残留结构性不可达）。
     */
    val lowSphereBuf = FloatArray(MAX_SPHERE_I * SPHERE_I_STRIDE)
    var lowSphereCount = 0
        private set

    /** 低模批已发射实例数（攒批发射水位——opA 起点；帧首随计数复位） */
    private var lowEmitted = 0

    /** 日冕实例缓冲（贴脸星 r≥[CORONA_MIN_R]——与真球同槽同序；渲染器逐实例 uniform 回放） */
    val coronaBuf = FloatArray(MAX_SPHERES * CORONA_STRIDE)
    var coronaCount = 0
        private set

    // ==================== R281 新缓冲（全预分配——帧零分配） ====================

    /** 开普勒顶点缓冲（轨道线+尘埃点共用——ν/径向系数/世界尺寸/色；GPU 轨道求值） */
    val kepBuf = FloatArray(MAX_KEP_VERTS * KEP_STRIDE)
    var kepVertCount = 0
        private set

    /** 开普勒系统描述缓冲（每槽一轨道参数集——op 按槽索引） */
    val kepSysBuf = FloatArray(MAX_KEP_SYS * KEP_SYS_STRIDE)
    var kepSysCount = 0
        private set

    /** 世界点缓冲（星团成员 3D 球分布等——GPU 投影+近大远小） */
    val wpBuf = FloatArray(MAX_WPOINTS * WP_STRIDE)
    var wpCount = 0
        private set

    /** 世界三角带缓冲（彗星双尾——GL_TRIANGLE_STRIP 回放） */
    val stripBuf = FloatArray(MAX_STRIP_VERTS * STRIP_STRIDE)
    var stripVertCount = 0
        private set

    /** 行星真球实例缓冲 */
    val planetBuf = FloatArray(MAX_PLANETS * PLANET_STRIDE)
    var planetCount = 0
        private set

    /** 真 3D 环带实例缓冲（土星环+黑洞吸积盘） */
    val ringBuf = FloatArray(MAX_RINGS * RING_STRIDE)
    var ringCount = 0
        private set

    /** 岩石实例缓冲（小行星/尘埃岩/彗核/流星体——instancing 一 draw） */
    val rockBuf = FloatArray(MAX_ROCKS * ROCK_STRIDE)
    var rockCount = 0
        private set

    /** 体积星云实例缓冲（双层视差程序雾+R373 复合体独立行后段——总帽 24+16=40 槽） */
    val nebBuf = FloatArray((MAX_NEBULAS + MAX_NEBULA_CPX) * NEB_STRIDE)
    var nebCount = 0
        private set

    /** R373 复合体行已装填数（独立预算行计数——帧首随计数复位） */
    var nebCpxCount = 0
        private set

    /** 镜头光斑源（top3 亮星——ndc 位/色/强度/正对度；合成 shader 消费） */
    val flareBuf = FloatArray(MAX_FLARES * FLARE_STRIDE)
    var flareCount = 0
        private set

    /** draw op 流（画家序回放单）：type + 3 参数（POINTS/LINES：起点/点数；QUADS：纹理键/起始 quad/quad 数；SPHERE：实例槽） */
    val opType = IntArray(MAX_OPS)
    val opA = IntArray(MAX_OPS)
    val opB = IntArray(MAX_OPS)
    val opC = IntArray(MAX_OPS)
    var opCount = 0
        private set

    /** 名签候选（复用表——渲染器读后拷贝发布；与 Canvas 同式：已知星∧r≥门∧cap 8） */
    val labelCands = ArrayList<GlLabelCand>(LABEL_CAP + 4)

    /** 选中星名签（叠加层直绘——镜像 Canvas 选中名签段；null=无） */
    class SelectedLabel(val sx: Float, val sy: Float, val r: Float, val title: String)

    /** 帧装配产出统计（探针/单测锚） */
    var statVisibleStars = 0
        private set
    var statMicroStars = 0
        private set
    var statSphereStars = 0
        private set
    var statCoronas = 0
        private set

    /** R281 分段装配耗时（ms——平板装配余量治理的分解探针） */
    var statSceneMs = 0f
        private set
    var statStarsMs = 0f
        private set
    var statFxMs = 0f
        private set
    var statPlanets = 0
        private set

    /**
     * R399 行星微点计数（count 轴微点腿+远档微点档——「未浮现」态行星的可见
     * 形态 census；探针行「行星=N 微点行星=M」对账口径=缩放各档吞门退役实锚）。
     */
    var statPlanetDots = 0
        private set
    var statRocks = 0
        private set

    // ==================== R349 LOD 政策件入参与探针（r341 §4.1/§4.3；R355 密度入参） ====================

    /**
     * 回退序①②③的逐帧入参（**状态由 TasteGlRenderer 持有**——GL 线程 5s 帧率
     * 窗升降级后推入；JVM 单测可直接置位演练回退档：bias=1.5=阈值上抬/
     * meshBias=∞=全低模/instanceCap=③收缩帽）。
     */
    var lodSolidBias = 1f
    var lodMeshBias = 1f
    var lodInstanceCap = MAX_SPHERE_I

    /**
     * R355 屏幕密度（dp→px 换算入参——**渲染器持态推入**：Android Resources 的
     * 屏密度读数（读侧在 GlSceneView）；缺省 1f=mdpi 恒等档=JVM 单测基线）。
     * 消费=build() 帧首单口换算（一处吃 lodDensity 全族消费——别处零乘法）。
     */
    var lodDensity = 1f

    /**
     * 本帧 effective solid floor（build 首算——[TasteLodPolicy.effectiveSolidFloor]
     * ×密度：1.6dp×density×bias；初值=mdpi 恒等档——build 必重算）。
     */
    private var lodSolidFloorPx = TasteLodPolicy.SOLID_FLOOR_DP

    /**
     * 本帧 effective low-mesh 线（build 首算——[TasteLodPolicy.effectiveLowMeshMax]
     * ×密度：4dp×density×meshBias；初值=mdpi 恒等档）。
     */
    private var lodLowMeshMaxPx = TasteLodPolicy.LOWMESH_MAX_DP

    /**
     * R355 本帧点档上限 px（build 首算——[TasteLodPolicy.pointCeilPx]：1.2dp×
     * density；微星并点/星芒域下沿/blend 窗下沿全族消费；初值=mdpi 恒等档）。
     */
    private var lodPointCeilPx = TasteLodPolicy.POINT_CEIL_DP

    /**
     * R399 本帧行星微点直径地板 px（build 首算——[TasteLodPolicy.microDotPx]：
     * max(2.2px, 1.2dp×density)；count 轴微点腿+buildPlanetBody 点腿尺寸地板
     * 共消费——亚像素行星=最小可见亮点不蒸发；初值=裸像素下限=mdpi 恒等档）。
     */
    private var lodMicroDotPx = TasteLodPolicy.MICRO_DOT_MIN_PX

    /**
     * R386 本帧复合体远档阈 px（build 首算——[CPX_FAR_MIN_DP]×密度：150dp×
     * density；buildComplexesR373 远档层数裁剪消费——密度岔路闭合（400dpi 档
     * 375px：同 dp 同路由=跨密度形态一致）；初值=mdpi 恒等档）。
     */
    private var cpxFarMinPx = CPX_FAR_MIN_DP

    /**
     * R388 刀1 本帧复合体远档隐没带内沿 px（build 首算——[CPX_FAR_HIDE_MIN_DP]
     * ×density；buildComplexesR373 带下不画消费——初值=mdpi 恒等档）。
     */
    private var cpxFarHideMinPx = CPX_FAR_HIDE_MIN_DP

    /**
     * R388 刀1 本帧复合体远档隐没带外沿 px（build 首算——[CPX_FAR_HIDE_MAX_DP]
     * ×density；[farHide01] 带内 C¹ 渐隐消费——初值=mdpi 恒等档）。
     */
    private var cpxFarHideMaxPx = CPX_FAR_HIDE_MAX_DP

    /**
     * R388 刀2 本帧丝缕频率锁定参考屏半径 px（build 首算——[CPX_FIL_REF_DP]×
     * density；buildComplexesR373 每 quad scale=clamp(rPx/此值, [CPX_FIL_FLOOR],
     * 1) 装填槽 9——初值=mdpi 恒等档）。
     */
    private var cpxFilRefPx = CPX_FIL_REF_DP

    /** R349 低模球计数（探针/单测锚——[statSphereStars] 的低模半边） */
    var statSphereLow = 0
        private set

    /** R349 全模球计数（探针/单测锚——[statSphereStars] 的全模半边） */
    var statSphereFull = 0
        private set

    /** R349 blend 带双绘对数（探针/单测锚——带内点+球双态叠加的星数） */
    var statBlendPairs = 0
        private set

    /** R349 低模帽截断数（探针/单测锚——球籍被踢回点档的诚实降级记账） */
    var statLodDropped = 0
        private set

    /** R349 低模球籍预选位（星索引→true=本帧持籍；帧首重选——最小屏径优先出球籍） */
    private var lowSphereOk = BooleanArray(0)

    /** R349 statLodLine 探针节拍（buildStars 收口自增——statTickR339 同族 ~6s 一条） */
    private var statTickR349 = 0

    /** R349 低模球籍预选排名槽（屏径降序插入排序——flare/spike 族同姿势帧零分配） */
    private val lowSelScore = FloatArray(MAX_SPHERE_I)
    private val lowSelIdx = IntArray(MAX_SPHERE_I)
    private var lowSelN = 0

    /** R339 星芒门候选数（探针/单测锚——点形态个体绘制星
     *  [lodPointCeilPx]≤r<effectiveSolidFloor 的亮度 top 门域（R349 形态线/R355
     *  dp 帧字段——密度档域）
     *  （R349 形态线；点云层星不拆云/3D 真身非点源不参选——resolved 无芒） */
    var statSpikeCand = 0
        private set

    // ==================== R388 刀1/刀2 探针（校准腿计数行——statLodLine 消费） ====================

    /** R388 探针：本帧最大复合体/富集 quad 半径 px（装配期记——校准轮判读原料） */
    var statCpxRpx = 0f
        private set

    /** R388 探针：同 quad 远档隐没因子（farHide01 带内渐隐活值） */
    var statCpxFarHide = 0f
        private set

    /** R388 探针：同 quad 丝缕频率标度（刀2 scale 活值） */
    var statCpxFilScale = 0f
        private set

    /** R339 星芒入选星数（探针/单测锚——top 门截断后实际发芒的星数，
     *  ≤[TasteStarGlow.SPIKE_MAX_STARS]） */
    var statSpikeStars = 0
        private set

    /** R339 星芒 top 门星索引→亮度01（top1=1 递减；0=未选——帧首预扫
     *  [selectSpikesR339] 写入，pass2 buildStarBody 消费；按星表长增长重建） */
    private var spikeB01 = FloatArray(0)

    /** R339 星芒 top 门排名槽（分数降序插入排序——R281 flare 族同姿势帧零分配） */
    private val spikeScore = FloatArray(TasteStarGlow.SPIKE_MAX_STARS)
    private val spikeStarIdx = IntArray(TasteStarGlow.SPIKE_MAX_STARS)
    private var spikeCount = 0

    /** R339 星芒门探针节拍（buildStars 收口自增——statTickR321 同族口径，~6s 一条） */
    private var statTickR339 = 0

    // ---- 复用暂存 ----
    private val proj = FloatArray(4)
    private val basisArr = FloatArray(9)
    private val dispScratch = FloatArray(3)
    private val lensScratch = FloatArray(2)
    private val mpos = FloatArray(3)
    private val hsv = FloatArray(3)
    private val rgbA = FloatArray(3)
    private val rgbB = FloatArray(3)
    private val rgbC = FloatArray(3)
    private val rayScratch = FloatArray(3)   // R281 彗星反投影（screenRay 出参）
    private val fiber3 = FloatArray(3)       // R350 纤维点串出参（shell/torusFiberPoint）
    private val stripCenter = FloatArray(96 * 3) // R281 彗尾带心世界点（≤96 节）
    private val stripAcross = FloatArray(96 * 3) // R281 彗尾横向单位向量
    private val flareScore = FloatArray(MAX_FLARES)
    private var order = IntArray(0)   // 槽位序（排序后 order[vi]=槽位号——
    private var slotStar = IntArray(0) // 槽位→星表索引（配对纪律见下）
    private var pSx = FloatArray(0)
    private var pSy = FloatArray(0)
    private var pDepth = FloatArray(0)
    private var pR = FloatArray(0)
    private var pA = FloatArray(0)
    private var pWx = FloatArray(0)
    private var pWy = FloatArray(0)
    private var pWz = FloatArray(0)
    private val wellSx = FloatArray(64)
    private val wellSy = FloatArray(64)
    private val wellDepth = FloatArray(64)
    private val wellRpx = FloatArray(64)

    // ---- R320 引力波/日月食复用件（原镜像 Canvas TasteFlightRenderer——公式单源；R345 后本件唯一
    //      GravityWaveFx/EclipseFx，双端同式；帧零分配） ----
    /** 波心屏位（≤2 波——build 首预扫波源星投影写；NaN=波心不可用该帧零位移） */
    private val waveCx = FloatArray(GravityWaveFx.WAVE_CAP)
    private val waveCy = FloatArray(GravityWaveFx.WAVE_CAP)
    private val waveRad = FloatArray(GravityWaveFx.WAVE_CAP)
    private val waveAge = FloatArray(GravityWaveFx.WAVE_CAP)
    private val waveAmp = FloatArray(GravityWaveFx.WAVE_CAP)
    private var waveN = 0

    /** 波前位移暂存（GravityWaveFx.displaceAt 出参） */
    private val waveScratch = FloatArray(2)

    /** 雾团屏位账（星际消光判定原料——buildNebulas 投影时顺手记 ≤8 团） */
    private val fogSx = FloatArray(8)
    private val fogSy = FloatArray(8)
    private val fogRpx = FloatArray(8)
    private val fogDepth = FloatArray(8)
    private val fogPresence = FloatArray(8)
    private var fogN = 0

    // ---- R385 复合体屏位账+消光+enrich 脉冲（design-nebula §3.3-2/§3.3-3——帧零
    //      分配槽位预分配；屏位账=buildComplexesR373 投影时顺手记 ≤4 团，复合体
    //      索引序（presence=0=本帧未入账——出屏/近窗裁剪），星层 pass1 消费） ----

    /** 复合体屏位账（复合体级消光判定原料——云心投影/视半径/深度/在场度） */
    private val cpxSx = FloatArray(4)
    private val cpxSy = FloatArray(4)
    private val cpxRpx = FloatArray(4)
    private val cpxDepth = FloatArray(4)
    private val cpxPresence = FloatArray(4)
    private var cpxN = 0

    /** 超新星 enrich 复合体脉冲账（槽位=复合体序——CYCLE_PULSE 共在门 max；0=门外零行为） */
    private val cpxPulse = FloatArray(4)

    /** 复合体级消光柱密度账（星索引 → 0..1——pass2 出 dim+色温偏移；帧首清零） */
    private var pExtCpx = FloatArray(0)

    // ---- R323 发射星云扫描账（镜像 Canvas scanEmissionFogsR323——帧零分配
    //      槽位预分配；有发射雾才扫=普通雾零成本负锚） ----

    /** 最近 O/B 电离源世界位（槽位=雾团序） */
    private val fogIonX = FloatArray(8)
    private val fogIonY = FloatArray(8)
    private val fogIonZ = FloatArray(8)

    /** 最近 O/B 电离源距离平方（破平比较账） */
    private val fogIonD2 = FloatArray(8)

    /** 电离源在场位（false=该雾无 O/B 源——无侧辉无电离调制） */
    private val fogIonValid = BooleanArray(8)

    /** 循环联动脉冲（超新星×星胚——空间共在门 max；0=门外零行为） */
    private val fogPulse = FloatArray(8)

    /** 星胚偏移暂存（EmissionNebulaFx.embryoOffset01 出参——帧内复用零分配） */
    private val r323EmbOut = FloatArray(2)

    // ---- R351 雾絮化暂存（FogFlocFx blob 族出参——帧零分配两枚；每雾 ~13 次
    //      fbm 求值 <0.01ms，装配层一次算好渲染端只画不算） ----

    /** blob alpha 配比包（FogFlocFx.blobAlphaPack01 出参——≤BLOB_COUNT_MAX=3 槽；Σ恒 1.35 零增益） */
    private val flocAlphaPack = FloatArray(FogFlocFx.BLOB_COUNT_MAX)

    /** blob 世界系错位向（FogFlocFx.blobOffset01 出参——雾半径 r 单位倍数） */
    private val flocOff = FloatArray(3)

    /** 凌星食深（星索引 → 0..0.04——pass2 恒星 alpha 乘子；帧首清零） */
    private var pEcl = FloatArray(0)

    /** 消光重合度（星索引 → 0..1——pass2 出 dim+色温偏移；帧首清零） */
    private var pExtOv = FloatArray(0)
    private var selLabel: SelectedLabel? = null
    /** 焦点邻居表（buildEdges 焦点遍填充——邻居细环消费；镜像 Canvas neighborIds） */
    private val neighborIds = HashSet<Long>(16)

    // ---- R321 近距关系批槽位账（潮汐/特洛伊/星风——镜像 Canvas；帧零分配：
    //      按星表容量增长复用，scanBinariesR321 帧首预扫写入，域外恒零） ----

    /** 洛希形变量（星索引槽位；0=域外——pass2 零成本单比较） */
    private var tideDef = FloatArray(0)

    /** 潮汐朝向轴世界单位向（SPHERE_VS uTideAxis 原料——glUniform3f 逐实例上传） */
    private var tideAxW = FloatArray(0)
    private var tideAyW = FloatArray(0)
    private var tideAzW = FloatArray(0)

    /** 潮汐朝向轴屏角（rad——中间/远档 FOG 拉长四边形旋转角+尾方向） */
    private var tideAngRad = FloatArray(0)

    /** 两星屏距（潮汐尾长度锚） */
    private var tideDpx = FloatArray(0)

    /** 潮汐预扫触碰位（星索引——帧首复位零整表填充） */
    private val tideTouched = IntArray(16)
    private var tideTouchedN = 0

    /** 双星对预扫 chatId→星索引缓存（场景级——星表 identity 变更才重建） */
    private var binIdxCache: HashMap<Long, Int>? = null
    private var binIdxCacheFor: List<dev.tgfeed.ui.TasteFxStar>? = null

    /** 特洛伊焦点簇在场对数（探针） */
    private var trojanPairsLive = 0

    /** L 点/粒子暂存（扫描与绘制共用——帧零分配） */
    private val trojanOut = FloatArray(9)
    private val trojanP = FloatArray(3)

    // 配对纪律（**与 Canvas 渲染器的关键分叉点**，r277_notes「排序配对裁决」节）：
    // Canvas 版 sortDesc(order=星索引, key=pDepth=槽位序) 的键/值索引口径不一致
    // （星索引去读槽位数组=配对打乱——仲裁实验 SortPairingArbiterTest 实锤）；
    // 本侧修正口径：order 排**槽位号**（键 pDepth 同口径槽位索引），pass2 经
    // slotStar 槽位→星表取身份——身份/屏位/半径严格同槽配对。

    // ---- op 流类型切换状态（画家序记录件） ----
    private var lastType = T_NONE
    private var pointsEmitted = 0
    private var linesEmitted = 0
    private var pendingQuadTex = 0
    private var pendingQuadStart = 0
    private var pendingQuadCount = 0

    // ==================== 主入口 ====================

    /**
     * 装配一帧（GL 线程调用；读 f 各快照字段——线程安全论证见 r277_notes 锁图）。
     * [w]/[h]=GL 视口像素（与 Canvas 的 nc.width/height 同口径）。
     */
    fun build(f: TasteFlightFrame, w: Float, h: Float) {
        // ---- 帧首清零 ----
        pointCount = 0
        lineVertCount = 0
        quadCount = 0
        sphereCount = 0
        lowSphereCount = 0
        lowEmitted = 0
        coronaCount = 0
        kepVertCount = 0
        kepSysCount = 0
        wpCount = 0
        stripVertCount = 0
        planetCount = 0
        ringCount = 0
        rockCount = 0
        nebCount = 0
        nebCpxCount = 0
        flareCount = 0
        opCount = 0
        labelCands.clear()
        selLabel = null
        lastType = T_NONE
        pointsEmitted = 0
        linesEmitted = 0
        pendingQuadCount = 0
        statVisibleStars = 0
        statMicroStars = 0
        statSphereStars = 0
        statSphereLow = 0
        statSphereFull = 0
        statBlendPairs = 0
        statLodDropped = 0
        statCoronas = 0
        statPlanets = 0
        statPlanetDots = 0
        statRocks = 0
        statSpikeCand = 0
        statSpikeStars = 0
        statCpxRpx = 0f
        statCpxFarHide = 0f
        statCpxFilScale = 0f
        flareScore.fill(0f)
        // R349 回退旋钮入参 → 本帧 effective 阈值（政策件纯函数单源——点档上限
        // POINT_CEIL 恒 1.2dp 不随 bias 动：形态线换轨+数量线正交）；
        // R355 密度单口换算（**全帧唯一一处吃 lodDensity**——三阈值一次入帧字段，
        // 全族消费：dp 基准 1.2/1.6/4dp ×密度（渲染器持态推入——Android 读侧在
        // GlSceneView））；
        // R386 复合体远档阈同块第四条（dp 基准 150dp×密度——密度岔路闭合；直乘
        // 一条=R355 锚的诚实放行位（R355WiringSourceTest 已扩锚），别处仍零乘法）
        lodPointCeilPx = TasteLodPolicy.pointCeilPx(lodDensity)
        lodSolidFloorPx = TasteLodPolicy.effectiveSolidFloor(lodDensity, lodSolidBias)
        lodLowMeshMaxPx = TasteLodPolicy.effectiveLowMeshMax(lodDensity, lodMeshBias)
        // R399 微点地板（R355 密度单口换算同块第八条——政策件单源，别处零乘法）
        lodMicroDotPx = TasteLodPolicy.microDotPx(lodDensity)
        cpxFarMinPx = CPX_FAR_MIN_DP * lodDensity
        // R388 刀1 隐没带双阈同块第五/六条（dp 基准 60/110dp×密度——「看不清
        // 就不画」带下 continue+带内渐隐；同 dp 同形态=跨密度一致，mdpi 恒等档
        // 60/110px 数值原样=基线路由零变化）
        cpxFarHideMinPx = CPX_FAR_HIDE_MIN_DP * lodDensity
        cpxFarHideMaxPx = CPX_FAR_HIDE_MAX_DP * lodDensity
        // R388 刀2 频率锁定参考屏半径第七条（dp 基准 220dp×密度——阈上 scale=1
        // 既有基因原样；mdpi 恒等档 220px 数值原样）
        cpxFilRefPx = CPX_FIL_REF_DP * lodDensity
        if (w <= 0f || h <= 0f) return
        // 注：空星表不早退——天幕+网格照常（Canvas 同口径：空宇宙≠黑屏）

        val cx = w / 2f
        val cy = h / 2f
        val cam = f.cam
        val focal = TasteSpace.focalOf(w, h) * f.lens
        val t = f.engine.timeSec
        TasteSpace.basis(cam.yawDeg, cam.pitchDeg, basisArr)

        // ---- R320 帧首三账（镜像 Canvas scanWaveOriginsR320）：波心预扫+凌星食深/
        //      消光重合度清零+雾账清零 ----
        scanWaveOriginsR320(f, cam, focal, w, h, cx, cy)
        // ---- R321 双星对帧首预扫（洛希潮汐/特洛伊簇槽位账——镜像 Canvas；≤6 对
        //      微成本，域外恒零） ----
        scanBinariesR321(f, cam, focal, w, h, cx, cy)

        val tScene0 = System.nanoTime()
        // 0.5) R319 宇宙底幕·CMB 余辉（最底层 TEX_CMB quad——关=纯黑零产出；
        //      温度档黑体谱着色与 Canvas drawCmbBackdropR319 同式单源 CmbBackdropFx）
        buildCmbR319(f, w, h, cx, cy)
        // 1) 天幕 760 方向星（R281：静态 VBO+GPU 投影——CPU 投影/哈希/三角全归零，
        //    画家序原位=首个 op；方向/闪烁语义由 SKY 着色器同式承接）
        emitImmediate(OP_SKY, 0, 0, 0)
        // 2) 盘面参照网格
        buildGrid(cam, focal, w, h, cx, cy)
        // 2.45) R373 深空星云复合体+富集反射云（design-nebula §5.1 画位=画家序 2.5
        //       层雾团前——远风景画在近距墨水之下；复合体 blob 走雾族 NEBULA_FS 同
        //       管线独立预算行 [addNebulaCpx]，零新程序零 NEBULA_FS 文本接触）
        buildComplexesR373(f, cam, focal, w, h, cx, cy)
        // 2.5) R235 未探索星云（R281：双层视差程序雾——雾画在网格后星层前）
        buildNebulas(f, cam, focal, w, h, cx, cy)
        // 2.6) R319 宇宙底幕·奥伯斯视界壳+「光在路上」暗点（星层前——与 Canvas
        //      drawOlbersR319 同式单源；壳=addNebula 雾族、暗点=addPoint 批）
        buildOlbersR319(f, cam, focal, w, h, cx, cy)
        // 3) 今晚星轨暗光路（空间折线，极低透明）
        buildRoute(f, cam, focal, w, h, cx, cy)
        // 4) 互链边两遍制 + 连线流光 + 4.5) 关联弧（虚线）
        buildEdges(f, cam, focal, w, h, cx, cy)
        buildFlow(f, cam, focal, w, h, cx, cy)
        buildArcs(f, cam, focal, w, h, cx, cy)
        // 4.6) R234 尘埃带（关键字传播） + 4.7) 星座轨迹
        buildSpread(f, cam, focal, w, h, cx, cy, t)
        buildConstellation(f, cam, focal, w, h, cx, cy, t)
        val tStars0 = System.nanoTime()
        // 5) 星（逐帧深度序；微星点云/软心点/真球三档 + 行星系统 + 黑洞 + 身份层）
        buildStars(f, cam, focal, w, h, cx, cy, t)
        val tFx0 = System.nanoTime()
        // 5.5) R235 飞船航迹（屏空间） + 6) 流星/彗星/爆散 + 6.5) 虫洞隧道
        buildShipTrail(f, w, h, cx, cy, t)
        buildMeteor(f, cam, focal, w, h, cx, cy)
        buildComet(f, cam, focal, w, h, cx, cy)
        buildBursts(f)
        buildWormhole(f, w, h, cx, cy)
        // 收尾冲刷（switchType 纪律下只剩最后一个类型有攒批）
        flushPending()
        val tEnd = System.nanoTime()
        statSceneMs = (tStars0 - tScene0) / 1_000_000f
        statStarsMs = (tFx0 - tStars0) / 1_000_000f
        statFxMs = (tEnd - tFx0) / 1_000_000f
    }

    // ==================== 网格 ====================

    /**
     * R320 波心预扫（镜像 Canvas——事件星当帧屏投影位为心）：对 engine 波前账
     * （≤2）逐波找星投影；波源星不在星表/近裁后/出屏=该波心 NaN=零位移（诚实
     * 缺省）。网格（[buildGrid]）与星点（[buildStars] pass1）消费同一份波心账。
     */
    private fun scanWaveOriginsR320(
        f: TasteFlightFrame,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
    ) {
        fogN = 0
        waveN = 0
        // R385 复合体屏位账清零（复合体级消光——presence=0 即未入账，帧首统一归零）
        cpxN = 0
        val e = f.engine
        val stars = f.stars
        if (pEcl.size < stars.size) pEcl = FloatArray(stars.size)
        if (pExtOv.size < stars.size) pExtOv = FloatArray(stars.size)
        if (pExtCpx.size < stars.size) pExtCpx = FloatArray(stars.size)
        java.util.Arrays.fill(pEcl, 0, stars.size, 0f)
        java.util.Arrays.fill(pExtOv, 0, stars.size, 0f)
        java.util.Arrays.fill(pExtCpx, 0, stars.size, 0f)
        val n = e.waveCount
        if (n <= 0) return
        // N 体渲染位（与 buildStars pass1 同式——预扫先锚一帧插值；重复调用幂等）
        val grav = f.gravity
        val usePhys = grav != null && grav.n == stars.size
        if (usePhys && f.nowMs > 0L) grav!!.beginRenderFrame(f.nowMs)
        val rX = grav?.renderX
        val rY = grav?.renderY
        val rZ = grav?.renderZ
        for (i in 0 until n) {
            val chatId = e.waveChatIds[i]
            waveAmp[i] = 0f
            waveCx[i] = Float.NaN
            if (chatId == 0L) continue
            var si = -1
            for (k in stars.indices) {
                if (stars[k].chatId == chatId) { si = k; break }
            }
            if (si < 0) continue
            val s = stars[si]
            val px = if (usePhys) rX!![si] else s.x
            val py = if (usePhys) rY!![si] else s.y
            val pz = if (usePhys) rZ!![si] else s.z
            if (!TasteSpace.project(px, py, pz, cam, focal, cx, cy, proj, basisArr)) continue
            if (!GravityWaveFx.originOnScreen(proj[0], proj[1], w, h)) continue
            waveCx[i] = proj[0]
            waveCy[i] = proj[1]
            waveAge[i] = e.timeSec - e.waveStartSec[i]
            waveRad[i] = GravityWaveFx.waveRadiusPx(waveAge[i], e.waveLifeSec[i])
            waveAmp[i] = e.waveAmpPx[i]
            waveN = i + 1
        }
    }

    /**
     * R320 波前位移叠加（镜像 Canvas waveOffsetR320——波位移逐波累加写 [out] 长 ≥2；
     * 返回是否有位移发生；无波/域外早退=out 原样透传 false）。
     */
    private fun waveOffsetR320(x: Float, y: Float, out: FloatArray): Boolean {
        var xx = x
        var yy = y
        var any = false
        for (i in 0 until waveN) {
            if (waveAmp[i] <= 0f || waveCx[i].isNaN()) continue
            if (GravityWaveFx.displaceAt(xx, yy, waveCx[i], waveCy[i], waveRad[i], waveAge[i], waveAmp[i], out)) {
                xx += out[0]
                yy += out[1]
                any = true
            }
        }
        out[0] = xx
        out[1] = yy
        return any
    }

    // ==================== R321 近距关系批（洛希潮汐+拉格朗日特洛伊+星风——镜像 Canvas） ====================

    /**
     * R321 双星对帧首预扫（镜像 Canvas scanBinariesR321——潮汐形变量/朝向轴/屏距
     * 槽位账）：≤6 宣判对×1 次世界距+2 次投影微成本；世界轴（uTideAxis 原料——
     * 归一单位向）+屏角（FOG 拉长四边形/尾）+屏距（尾长锚）；域外恒零=零成本
     * 早退；beginRenderFrame 幂等（scanWaveOriginsR320 同式先锚一帧插值）。
     */
    private fun scanBinariesR321(
        f: TasteFlightFrame,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
    ) {
        trojanPairsLive = 0
        val bins = f.binaries
        if (tideTouchedN > 0) resetTideR321()
        if (bins.isEmpty()) return
        val stars = f.stars
        val n = stars.size
        if (tideDef.size < n) {
            tideDef = FloatArray(n)
            tideAxW = FloatArray(n)
            tideAyW = FloatArray(n)
            tideAzW = FloatArray(n)
            tideAngRad = FloatArray(n)
            tideDpx = FloatArray(n)
            tideTouchedN = 0
        }
        if (tideTouchedN > 0) resetTideR321()
        var idx = binIdxCache
        if (idx == null || binIdxCacheFor !== stars) {
            idx = HashMap(n * 2)
            for (si in stars.indices) idx[stars[si].chatId] = si
            binIdxCache = idx
            binIdxCacheFor = stars
        }
        val grav = f.gravity
        val usePhys = grav != null && grav.n == stars.size
        if (usePhys && f.nowMs > 0L) grav!!.beginRenderFrame(f.nowMs)
        val rX = grav?.renderX
        val rY = grav?.renderY
        val rZ = grav?.renderZ
        for (bin in bins) {
            val ia = idx[bin.a] ?: continue
            val ib = idx[bin.b] ?: continue
            if (ia == ib) continue
            val sa = stars[ia]
            val sb = stars[ib]
            val ax = if (usePhys) rX!![ia] else sa.x
            val ay = if (usePhys) rY!![ia] else sa.y
            val az = if (usePhys) rZ!![ia] else sa.z
            val bx = if (usePhys) rX!![ib] else sb.x
            val by = if (usePhys) rY!![ib] else sb.y
            val bz = if (usePhys) rZ!![ib] else sb.z
            val dxw = bx - ax
            val dyw = by - ay
            val dzw = bz - az
            val d = sqrt(dxw * dxw + dyw * dyw + dzw * dzw)
            if (d < 1f) continue
            val def01 = RocheTidalFx.def01Of(d, RocheTidalFx.dRocheOf(sa.bodyR, sb.bodyR))
            if (def01 <= 0f) continue // 域外零成本早退
            if (!TasteSpace.project(ax, ay, az, cam, focal, cx, cy, proj, basisArr)) continue
            val axPx = proj[0]
            val ayPx = proj[1]
            if (!TasteSpace.project(bx, by, bz, cam, focal, cx, cy, proj, basisArr)) continue
            val dpx = hypot(proj[0] - axPx, proj[1] - ayPx)
            if (dpx < 2f) continue
            val angA = atan2(proj[1] - ayPx, proj[0] - axPx)
            if (tideTouchedN < tideTouched.size) {
                tideDef[ia] = def01; tideAngRad[ia] = angA; tideDpx[ia] = dpx
                tideDef[ib] = def01; tideAngRad[ib] = angA + 3.14159265f; tideDpx[ib] = dpx
                // 世界轴（归一单位向——uTideAxis 逐实例上传；A 朝 B/B 朝 A）
                tideAxW[ia] = dxw / d; tideAyW[ia] = dyw / d; tideAzW[ia] = dzw / d
                tideAxW[ib] = -dxw / d; tideAyW[ib] = -dyw / d; tideAzW[ib] = -dzw / d
                tideTouched[tideTouchedN++] = ia
                tideTouched[tideTouchedN++] = ib
            }
        }
    }

    /** 潮汐槽位复位（帧首零整表填充——只清上一帧触碰位 ≤12） */
    private fun resetTideR321() {
        for (k in 0 until tideTouchedN) {
            val si = tideTouched[k]
            tideDef[si] = 0f
            tideAngRad[si] = 0f
            tideDpx[si] = 0f
            tideAxW[si] = 0f
            tideAyW[si] = 0f
            tideAzW[si] = 0f
        }
        tideTouchedN = 0
    }

    /**
     * R321 潮汐尾（镜像 Canvas drawTideTailR321——点精灵族：3~5 微点 + 哈希垂直
     * 微抖；中点前截止单源；色=星色）。KEPLER 求值=轨道件专用（世界系 GPU 求值），
     * 尾是屏空间微点——addPoint 点族同观感（r315「KEPLER 点族」的 GL 侧语义对应）。
     */
    private fun emitTideTailR321(
        s: dev.tgfeed.ui.TasteFxStar,
        sx: Float,
        sy: Float,
        alpha: Float,
        tide: Float,
        angRad: Float,
        dpx: Float,
        color: Int,
        seed: Int,
    ) {
        val len = RocheTidalFx.tailLenPx(tide, dpx)
        if (len <= 1f) return
        val n = RocheTidalFx.tailDots(seed)
        val ca = cos(angRad)
        val sa = sin(angRad)
        val ta = RocheTidalFx.tailAlphaOf(tide) * alpha
        val cr = ((color shr 16) and 0xFF) / 255f
        val cg = ((color shr 8) and 0xFF) / 255f
        val cb = (color and 0xFF) / 255f
        for (k in 1..n) {
            val u = k.toFloat() / (n + 1f)
            val dist = len * u
            val jit = (galaxyH01(seed, k * 31 + 313) - 0.5f) * 3.2f
            addPoint(
                sx + ca * dist - sa * jit, sy + sa * dist + ca * jit,
                2.2f, 0.45f, cr, cg, cb, ta,
            )
        }
    }

    /**
     * R321 拉格朗日特洛伊簇（镜像 Canvas drawTrojanClustersR321——点族：焦点件门
     * +无共源零渲染；L4/L5 每帧从两星当帧位导出=簇随绕转刚性跟随）。
     */
    private fun buildTrojanClustersR321(
        f: TasteFlightFrame,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
    ) {
        val bins = f.binaries
        if (bins.isEmpty()) return
        val focusA = f.selectedId
        val focusL = f.lockChatId
        if (focusA == 0L && focusL == 0L) return
        val stars = f.stars
        val idx = binIdxCache ?: return
        val grav = f.gravity
        val usePhys = grav != null && grav.n == stars.size
        if (usePhys && f.nowMs > 0L) grav!!.beginRenderFrame(f.nowMs)
        val rX = grav?.renderX
        val rY = grav?.renderY
        val rZ = grav?.renderZ
        val cr = ((TROJAN_COLOR shr 16) and 0xFF) / 255f
        val cg = ((TROJAN_COLOR shr 8) and 0xFF) / 255f
        val cb = (TROJAN_COLOR and 0xFF) / 255f
        for (bin in bins) {
            if (bin.trojanW <= 0) continue // 无共源零尘埃（诚实）
            if (bin.a != focusA && bin.b != focusA && bin.a != focusL && bin.b != focusL) continue
            val ia = idx[bin.a] ?: continue
            val ib = idx[bin.b] ?: continue
            val sa = stars[ia]
            val sb = stars[ib]
            val ax = if (usePhys) rX!![ia] else sa.x
            val ay = if (usePhys) rY!![ia] else sa.y
            val az = if (usePhys) rZ!![ia] else sa.z
            val bx = if (usePhys) rX!![ib] else sb.x
            val by = if (usePhys) rY!![ib] else sb.y
            val bz = if (usePhys) rZ!![ib] else sb.z
            val sep = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
            if (sep < 1f) continue
            val n = TrojanFx.particleCountOf(bin.trojanW)
            if (n <= 0) continue
            val bright = TrojanFx.brightness01Of(bin.trojanW)
            val seed = (minOf(bin.a, bin.b) % 100_000L).toInt()
            val size = 2.0f + 1.6f * bright // 粒径双通道（点精灵 size=直径）
            val a = 0.30f + 0.50f * bright
            for (side in floatArrayOf(1f, -1f)) {
                TrojanFx.lPointOf(ax, ay, az, bx, by, bz, side, trojanOut)
                for (k in 0 until n) {
                    TrojanFx.particleOf(
                        trojanOut[0], trojanOut[1], trojanOut[2],
                        trojanOut[3], trojanOut[4], trojanOut[5],
                        trojanOut[6], trojanOut[7], trojanOut[8],
                        sep, seed, k, trojanP,
                    )
                    if (!TasteSpace.project(trojanP[0], trojanP[1], trojanP[2], cam, focal, cx, cy, proj, basisArr)) continue
                    if (proj[0] < -40f || proj[0] > w + 40f || proj[1] < -40f || proj[1] > h + 40f) continue
                    addPoint(proj[0], proj[1], size, 0.45f, cr, cg, cb, a)
                }
            }
            trojanPairsLive++
        }
        if (trojanPairsLive > 0 && statTickR321 % 360 == 0) {
            android.util.Log.i("TasteMapX", "R321 拉格朗日GL: 焦点特洛伊簇在场=${trojanPairsLive}对")
        }
    }

    /** R321 探针节拍（buildStars 收口自增——r249ProbeTick 同族口径） */
    private var statTickR321 = 0

    /**
     * R321 星风流（镜像 Canvas drawStarwindStreamR321——贴脸档焦点件：纯 t 函数
     * 槽相位零常驻状态；沿确定性风轴两侧 8 槽白色微点，0→80px 漂移渐散）。
     */
    private fun buildStarwindStreamR321(
        s: dev.tgfeed.ui.TasteFxStar,
        sx: Float,
        sy: Float,
        alpha: Float,
        seed: Int,
        t: Float,
    ) {
        val strength = StarwindFx.strength01Of(s.recent7)
        val rate = StarwindFx.streamRateOf(strength)
        val axis = StarwindFx.windAxisRad(seed)
        val ca = cos(axis)
        val sa = sin(axis)
        for (k in 0 until StarwindFx.STREAM_SLOTS) {
            val phase = StarwindFx.streamPhase01(t, rate, seed, k)
            val alive = StarwindFx.streamAlive01(phase)
            if (alive <= 0f) continue
            val dist = StarwindFx.STREAM_SPREAD_PX * (phase / StarwindFx.STREAM_LIVE_FRAC)
            val side = if (k % 2 == 0) 1f else -1f
            val size = StarwindFx.STREAM_DOT_PX * 2f * (0.45f + 0.55f * alive)
            val a = alpha * (0.16f + 0.38f * alive)
            addPoint(
                sx + ca * dist * side, sy + sa * dist * side,
                size, 0.45f, 0.918f, 0.957f, 1.0f, a,
            )
        }
    }

    /**
     * R323 自行矢量小尾巴（镜像 Canvas drawProperMotionTailR323——焦点弧同族
     * 细线 addLine+头点 addPoint；长度∝强度帽、方向=构成差映射 2D；参数
     * [ProperMotionFx] 单源=双端同式；静态标注零持续位移源（保形））。
     */
    private fun buildProperMotionTailR323(
        s: dev.tgfeed.ui.TasteFxStar,
        sx: Float,
        sy: Float,
        r: Float,
        alpha: Float,
    ) {
        val d = s.drift ?: return
        val start = r * 1.25f + 4f // 星缘外起步（不压星本体——Canvas 同式）
        val len = ProperMotionFx.tailLenPx(d.strength01)
        val a = ProperMotionFx.tailAlpha01(d.strength01) * alpha
        val ex = sx + d.dirX * (start + len)
        val ey = sy + d.dirY * (start + len)
        addLine(sx + d.dirX * start, sy + d.dirY * start, ex, ey, 1f, 1f, 1f, a)
        // 头点 ×2=GL 点族口径（buildStarwindStreamR321 同族：GL size≈2×Canvas 粒径）
        addPoint(ex, ey, ProperMotionFx.TAIL_HEAD_PX * 2f, 0.6f, 1f, 1f, 1f, a)
    }

    private fun buildGrid(cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        val n = ((GRID_EXT * 2f) / GRID_STEP).toInt()
        val wOn = waveN > 0
        for (k in 0..n) {
            val off = -GRID_EXT + k * GRID_STEP
            for (axis in 0..1) {
                val x1 = if (axis == 0) off else -GRID_EXT
                val z1 = if (axis == 0) -GRID_EXT else off
                val x2 = if (axis == 0) off else GRID_EXT
                val z2 = if (axis == 0) GRID_EXT else off
                if (!TasteSpace.project(x1, GRID_Y, z1, cam, focal, cx, cy, proj, basisArr)) continue
                var sx1 = proj[0]; var sy1 = proj[1]; val p1 = proj[2]
                if (!TasteSpace.project(x2, GRID_Y, z2, cam, focal, cx, cy, proj, basisArr)) continue
                var sx2 = proj[0]; var sy2 = proj[1]
                if (wOn) {
                    // R320 网格顶点波前位移（「背景网格形变」——r315 E 两层涟漪之二）
                    if (waveOffsetR320(sx1, sy1, waveScratch)) { sx1 = waveScratch[0]; sy1 = waveScratch[1] }
                    if (waveOffsetR320(sx2, sy2, waveScratch)) { sx2 = waveScratch[0]; sy2 = waveScratch[1] }
                }
                if ((sx1 < -4f && sx2 < -4f) || (sx1 > w + 4f && sx2 > w + 4f) ||
                    (sy1 < -4f && sy2 < -4f) || (sy1 > h + 4f && sy2 > h + 4f)
                ) {
                    continue
                }
                addLine(sx1, sy1, sx2, sy2, 1f, 1f, 1f, 0.05f + 0.05f * TasteSpace.fogOf(p1))
            }
        }
    }

    // ==================== R319 宇宙底幕（CMB 余辉 + 奥伯斯视界 + 光在路上暗点） ====================
    // 镜像 Canvas drawCmbBackdropR319/drawOlbersR319（常量逐值一致，改就一起改；
    // 参数公式单源 CmbBackdropFx——双端同式的机制保障）。帧预算：CMB=1 quad；
    // 壳=≤18 addNebula；暗点=1 批 addPoint+彗星增亮 ≤12 点。

    /** 奥伯斯视界壳雾色（暗蓝档——Canvas 侧镜像常量逐值一致） */
    private val OLBERS_SHELL_ARGBC = 0xFF16273C.toInt()

    /** 「光在路上」暗点色（暗蓝白微点——Canvas 侧镜像常量同上） */
    private val OLBERS_DOT_ARGBC = 0xFFB9CCE6.toInt()

    /** 壳方向暂存（TasteSpace.skyDirection 逐拍求值——18 次×确定性哈希，零分配） */
    private val olbersDir = FloatArray(3)

    /**
     * R319 CMB 余辉底幕（最底层 TEX_CMB 全屏 quad——1 draw call）：温度档（延时
     * 摄影光标——temp01Of 单源）→黑体谱着色（TasteStarColor 单源）×全局 alpha
     * （alphaOf 单源钳域）。**开关门：关=纯黑零产出（首行 return）**。纹理=渲染器
     * 动态上传（种子=帧字段 cmbSeedFp 基因指纹，变更窗才重传——bakeCmbCached 单源）。
     */
    private fun buildCmbR319(f: TasteFlightFrame, w: Float, h: Float, cx: Float, cy: Float) {
        if (!f.cmbOn) return
        val genesis = f.genesisOn && f.genesisSpan.valid
        val temp01 = CmbBackdropFx.temp01Of(genesis, f.genesisT01)
        val alpha = CmbBackdropFx.alphaOf(temp01, genesis)
        if (alpha <= 0f) return
        val rgb = TasteStarColor.blackbodyRgb(CmbBackdropFx.kelvinOf(temp01))
        addQuad(
            TEX_CMB, cx, cy, w / 2f, h / 2f, 0f, 0f,
            ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, alpha,
        )
    }

    /**
     * R319 奥伯斯视界壳+「光在路上」暗点（镜像 Canvas drawOlbersR319）：壳=R_obs
     * 球面 18 向 addNebula 雾（拉远才可辨——相机离原点 smoothstep，深处归零）；
     * 暗点=差集确定性布点 1 批 addPoint+彗星掠过微亮（dotAlphaGlow 单源）。
     * **开关门与 null zone=整层零产出**。
     */
    private fun buildOlbersR319(
        f: TasteFlightFrame,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
    ) {
        if (!f.cmbOn) return
        val zone = f.cmbZone ?: return
        val rObs = zone.rObs
        if (rObs <= 0f) return
        // ---- 视界壳（18 向雾补丁；透明度=拉远才可辨） ----
        val camDist = TasteSpace.distTo(cam, 0f, 0f, 0f)
        val shellK = ((camDist - rObs * 0.35f) / (rObs * 0.65f)).coerceIn(0f, 1f)
        val shellAlpha = CmbBackdropFx.SHELL_ALPHA * (shellK * shellK * (3f - 2f * shellK))
        if (shellAlpha > 0.004f) {
            val sr = ((OLBERS_SHELL_ARGBC shr 16) and 0xFF) / 255f
            val sg = ((OLBERS_SHELL_ARGBC shr 8) and 0xFF) / 255f
            val sb = (OLBERS_SHELL_ARGBC and 0xFF) / 255f
            val patchHalf = rObs * 1.1f
            for (i in 0 until CmbBackdropFx.SHELL_PATCHES) {
                TasteSpace.skyDirection(i, olbersDir)
                val wx = olbersDir[0] * rObs
                val wy = olbersDir[1] * rObs
                val wz = olbersDir[2] * rObs
                if (!TasteSpace.ahead(wx, wy, wz, cam, basisArr)) continue
                if (!TasteSpace.project(wx, wy, wz, cam, focal, cx, cy, proj, basisArr)) continue
                val rPx = TasteNebula.fogPx(patchHalf, proj[3])
                if (rPx > 2.5f * hypot(w, h)) continue // 巨雾护栏（nebulae 同款）
                val sx = proj[0]
                val sy = proj[1]
                if (sx < -rPx || sx > w + rPx || sy < -rPx || sy > h + rPx) continue
                addNebula(sx, sy, rPx, 0.5f, 0f, sr, sg, sb, shellAlpha)
            }
        }
        // ---- 「光在路上」暗点（差集确定性布点——1 批 addPoint+彗星增亮点） ----
        val dots = zone.dots
        val n = dots.size / 3
        if (n == 0) return
        val e = f.engine
        val cometOn = e.cometLife > 0f
        val cometX = e.cometX * w
        val cometY = e.cometY * h
        val dr = ((OLBERS_DOT_ARGBC shr 16) and 0xFF) / 255f
        val dg = ((OLBERS_DOT_ARGBC shr 8) and 0xFF) / 255f
        val db = (OLBERS_DOT_ARGBC and 0xFF) / 255f
        var count = 0
        var i = 0
        while (i < n && count < CmbBackdropFx.DOTS_MAX) {
            val wx = dots[i * 3]
            val wy = dots[i * 3 + 1]
            val wz = dots[i * 3 + 2]
            if (TasteSpace.ahead(wx, wy, wz, cam, basisArr) &&
                TasteSpace.project(wx, wy, wz, cam, focal, cx, cy, proj, basisArr)
            ) {
                val sx = proj[0]
                val sy = proj[1]
                if (sx in -4f..(w + 4f) && sy in -4f..(h + 4f)) {
                    var a = CmbBackdropFx.DOT_ALPHA_BASE
                    var size = 1.7f
                    if (cometOn) {
                        // C 章④被动联动：彗星掠过处暗点微微一亮（「光快到了」预告）
                        a = CmbBackdropFx.dotAlphaGlow(a, hypot(sx - cometX, sy - cometY), e.cometLife)
                        if (a > CmbBackdropFx.DOT_ALPHA_BASE) size = 2.6f
                    }
                    addPoint(sx, sy, size, 0.4f, dr, dg, db, a)
                    count++
                }
            }
            i++
        }
    }

    // ==================== 边 / 弧 ====================

    /** 互链边（镜像 Canvas drawEdges；R314 政策件 TasteLinkLinesPolicy 单一裁决）：
     *  linkLines=开=两遍制实体直线网（旧行为）；关（默认）=全景零划线，仅焦点星
     *  互链画细弱引力弧（galaxyArcControl 贝塞尔 20 段——Canvas 同式）。 */
    private fun buildEdges(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        neighborIds.clear()
        if (f.edges.isEmpty()) return
        val phys = f.gravity
        val focusId = f.selectedId
        if (!TasteLinkLinesPolicy.drawMesh(f.linkLines)) {
            // ---- R314 默认档：仅焦点引力弧 ----
            if (!TasteLinkLinesPolicy.drawFocusArcs(f.linkLines, focusId, f.genesisOn)) return
            for (e in f.edges) {
                val isFocus = e.a == focusId || e.b == focusId
                if (!isFocus) continue
                neighborIds.add(if (e.a == focusId) e.b else e.a)
                var eax = e.ax
                var eay = e.ay
                var eaz = e.az
                var ebx = e.bx
                var eby = e.by
                var ebz = e.bz
                if (phys != null) {
                    if (phys.dispIntoChat(e.a, dispScratch)) { eax += dispScratch[0]; eay += dispScratch[1]; eaz += dispScratch[2] }
                    if (phys.dispIntoChat(e.b, dispScratch)) { ebx += dispScratch[0]; eby += dispScratch[1]; ebz += dispScratch[2] }
                }
                if (!TasteSpace.ahead(eax, eay, eaz, cam, basisArr) ||
                    !TasteSpace.ahead(ebx, eby, ebz, cam, basisArr)
                ) {
                    continue
                }
                TasteSpace.project(eax, eay, eaz, cam, focal, cx, cy, proj, basisArr)
                val x1 = proj[0]; val y1 = proj[1]; val p1 = proj[2]
                TasteSpace.project(ebx, eby, ebz, cam, focal, cx, cy, proj, basisArr)
                val x2 = proj[0]; val y2 = proj[1]; val p2 = proj[2]
                if ((x1 < 0f && x2 < 0f) || (x1 > w && x2 > w) || (y1 < 0f && y2 < 0f) || (y1 > h && y2 > h)) continue
                galaxyArcControl(eax, eay, eaz, ebx, eby, ebz, mpos)
                TasteSpace.project(mpos[0], mpos[1], mpos[2], cam, focal, cx, cy, proj, basisArr)
                val x3 = proj[0]
                val y3 = proj[1]
                val depthFade = (TasteSpace.fogOf(p1) + TasteSpace.fogOf(p2)) * 0.5f
                val a = TasteLinkLinesPolicy.focusArcAlpha(e.alpha) * depthFade
                var px = x1
                var py = y1
                for (k in 1..TasteLinkLinesPolicy.FOCUS_ARC_SEGMENTS) {
                    val tt = k / TasteLinkLinesPolicy.FOCUS_ARC_SEGMENTS.toFloat()
                    val u = 1f - tt
                    val qx = u * u * x1 + 2f * u * tt * x3 + tt * tt * x2
                    val qy = u * u * y1 + 2f * u * tt * y3 + tt * tt * y2
                    addLine(px, py, qx, qy, 1f, 1f, 1f, a)
                    px = qx
                    py = qy
                }
            }
            return
        }
        // ---- 旧行为（linkLines=开）：两遍制实体直线网 ----
        val twoPass = focusId != 0L
        val passCount = if (twoPass) 2 else 1
        for (pass in 0 until passCount) {
            val wantFocus = pass == 1
            for (e in f.edges) {
                val isFocus = twoPass && (e.a == focusId || e.b == focusId)
                if (isFocus != wantFocus) continue
                if (isFocus) neighborIds.add(if (e.a == focusId) e.b else e.a)
                var eax = e.ax
                var eay = e.ay
                var eaz = e.az
                var ebx = e.bx
                var eby = e.by
                var ebz = e.bz
                if (phys != null) {
                    if (phys.dispIntoChat(e.a, dispScratch)) { eax += dispScratch[0]; eay += dispScratch[1]; eaz += dispScratch[2] }
                    if (phys.dispIntoChat(e.b, dispScratch)) { ebx += dispScratch[0]; eby += dispScratch[1]; ebz += dispScratch[2] }
                }
                if (!TasteSpace.ahead(eax, eay, eaz, cam, basisArr) ||
                    !TasteSpace.ahead(ebx, eby, ebz, cam, basisArr)
                ) {
                    continue
                }
                TasteSpace.project(eax, eay, eaz, cam, focal, cx, cy, proj, basisArr)
                val x1 = proj[0]; val y1 = proj[1]; val p1 = proj[2]
                TasteSpace.project(ebx, eby, ebz, cam, focal, cx, cy, proj, basisArr)
                val x2 = proj[0]; val y2 = proj[1]; val p2 = proj[2]
                if ((x1 < 0f && x2 < 0f) || (x1 > w && x2 > w) || (y1 < 0f && y2 < 0f) || (y1 > h && y2 > h)) continue
                if (isFocus) {
                    addLine(x1, y1, x2, y2, 1f, 1f, 1f, (e.alpha * 1.6f).coerceIn(0.25f, 0.9f))
                } else {
                    val depthFade = (TasteSpace.fogOf(p1) + TasteSpace.fogOf(p2)) * 0.5f
                    addLine(x1, y1, x2, y2, 1f, 1f, 1f, e.alpha.coerceIn(0.03f, 0.5f) * depthFade)
                }
            }
        }
    }

    /** 关联弧（焦点态虚线：空间二次贝塞尔 20 段采样——(k×10)%18<10 取舍成虚线观感） */
    private fun buildArcs(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        if (f.arcs.isEmpty() || f.genesisOn) return
        val phys = f.gravity
        for (arc in f.arcs) {
            var aax = arc.ax
            var aay = arc.ay
            var aaz = arc.az
            var abx = arc.bx
            var aby = arc.by
            var abz = arc.bz
            if (phys != null) {
                if (arc.fromChatId != 0L && phys.dispIntoChat(arc.fromChatId, dispScratch)) { aax += dispScratch[0]; aay += dispScratch[1]; aaz += dispScratch[2] }
                if (phys.dispIntoChat(arc.toChatId, dispScratch)) { abx += dispScratch[0]; aby += dispScratch[1]; abz += dispScratch[2] }
            }
            if (!TasteSpace.ahead(aax, aay, aaz, cam, basisArr) || !TasteSpace.ahead(abx, aby, abz, cam, basisArr)) continue
            TasteSpace.project(aax, aay, aaz, cam, focal, cx, cy, proj, basisArr)
            val x1 = proj[0]; val y1 = proj[1]; val p1 = proj[2]
            TasteSpace.project(abx, aby, abz, cam, focal, cx, cy, proj, basisArr)
            val x2 = proj[0]; val y2 = proj[1]; val p2 = proj[2]
            if ((x1 < 0f && x2 < 0f) || (x1 > w && x2 > w) || (y1 < 0f && y2 < 0f) || (y1 > h && y2 > h)) continue
            galaxyArcControl(aax, aay, aaz, abx, aby, abz, mpos)
            TasteSpace.project(mpos[0], mpos[1], mpos[2], cam, focal, cx, cy, proj, basisArr)
            val x3 = proj[0]
            val y3 = proj[1]
            val fog = (TasteSpace.fogOf(p1) + TasteSpace.fogOf(p2)) * 0.5f
            val a = arc.alpha * fog
            GlPalette.rgbF(arc.colorArgb, rgbA)
            var px = x1
            var py = y1
            for (k in 1..20) {
                val tt = k / 20f
                val u = 1f - tt
                val qx = u * u * x1 + 2f * u * tt * x3 + tt * tt * x2
                val qy = u * u * y1 + 2f * u * tt * y3 + tt * tt * y2
                if ((k * 10) % 18 < 10) {
                    addLine(px, py, qx, qy, rgbA[0], rgbA[1], rgbA[2], a)
                }
                px = qx
                py = qy
            }
        }
    }

    // ==================== R280 P1：星云 / 星轨 / 流光 / 尘埃带 / 星座轨迹 ====================

    /**
     * R388 刀1 复合体远档隐没因子（0..1，C¹ smoothstep——「看不清就不画」的
     * **带内渐隐半边单源**：带下 0/带内渐升/带外 1）。三口同因子消费（物理
     * 一致铁律）：①复合体 blob 装 alpha；②富集反射微光 alpha；③消光账
     * cpxPresence——看不见的云不减光。带下 0 由调用方 continue 收口（本函数
     * 只算带，不做门）。
     */
    private fun farHide01(rPx: Float): Float {
        val lo = cpxFarHideMinPx
        val hi = cpxFarHideMaxPx
        if (hi <= lo) return if (rPx >= hi) 1f else 0f // 防御（异常密度档）——不拦
        val k = ((rPx - lo) / (hi - lo)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /**
     * R373 深空星云复合体+富集反射云（design-nebula §5.1 远景档——雾族管线家族
     * 扩展，P1 结论零碰零改写）：
     *  - 复合体 blob 走 NEBULA_FS **同程序 uniform 变体**（零新程序零 NEBULA_FS
     *    文本接触——R358 四件与 uniform 四件锚零碰）：丝缕/各向异性拉伸/暗尘埃
     *    带形态由 shader 既在结构自带（blob 尺度放大=丝缕尺度放大——「复合体只是
     *    更大更稀的种子变体」§4.2）；
     *  - 每复合体 2~3 视差层=[FogFlocFx] blob 族单源沿用（种子=质心格键哈希
     *    [DeepSpaceNebula.churnSeedOf]——R351 确定性身份锚同构）；层深 ±0.5R
     *    （R281 雾 ±0.35R 的大尺度外推：k=0 前层 +0.5R、k≥1 后层 −0.5R——飞行
     *    时层间错动=梦的本体）；
     *  - 发射线色板=Hα 玫红与 OIII 青 fBm 权混场（[DeepSpaceNebula.emissionLayerColor]
     *    逐层装配侧混色——同程序 uniform 变体的色变体半边）；alpha=电离侧活跃度
     *    调制（[emissionAlpha01] 帽 0.12×0.55..1.0）×近距渐出窗（[nearFade01]
     *    §5.2 触发门的远景半边单边落地——P3 raymarch 落地后补齐互补半边；不
     *    渐出则飞近=整屏丝缕墙=R252 全屏光雾负锚回潮）；
     *  - **远档裁剪**：投影半径 <[cpxFarMinPx]（=[CPX_FAR_MIN_DP] 150dp×密度，
     *    R386 dp 口径——旧裸 px 阈的密度岔路闭合）的远复合体裁至单层（fbm3
     *    octave 恒定——R358「uniform 仍四件」锚所迫无法加档位 uniform；以层数
     *    裁剪达成「远=低频省填充率」目标+**丝缕形态保义**：远距多层近同心叠=
     *    各层丝缕互填留空=R252 光雾球回潮（用户小米实锤），单层=脊线丝缕+
     *    留空干净在场）；
     *  - 富集微光云 ≤4×单层（帽 0.08 平档[DeepSpaceNebula.enrichAlpha01]——反射
     *    恒暗于发射=物理诚实；色=被反射星场色温 core 单源烘焙）；
     *  - **独立预算行 [MAX_NEBULA_CPX]=16**（[addNebulaCpx] 独立计数写入 nebBuf
     *    后段——雾行 24 帽零挤压零截断语义变化）；巨雾护栏/出屏剪裁与雾族同款。
     *  - **R385 P4 语义联动消费**（§3.3——P2 既有结构零改只扩）：创世纪 gas 档
     *    （[DeepSpaceNebula.genesisGasMult01] 帽内钳+反射云 [genesisReflectMult01]
     *    星未亮黯淡）+超新星 enrich 脉冲（[scanComplexPulseR385]——CYCLE_PULSE
     *    共在窗单源直引）+**复合体屏位账**（云心投影/视半径/深度/在场度 ≤4 团——
     *    星层 pass1 复合体级消光判定原料，镜像 R320 雾屏位账口径）。
     *  - **R388 刀1 远档隐没**（用户 2026-10-25 14:1x 裁决根治刀——「看不清就
     *    不画」）：复合体+富集反射微光同一病类同带（隐没带 [CPX_FAR_HIDE_MIN_DP]
     *    ..[CPX_FAR_HIDE_MAX_DP] dp 基准×密度）——带下直接不画（亚分辨圆饼读
     *    作星场=诚实缺省同族），带内 [farHide01] C¹ 渐隐至零；**消光账
     *    cpxPresence 乘同一淡出因子**（看不见的云不减光——P4 消费口其余零碰）；
     *    观感=星图全景纯星空，飞近才见云（R351 弥散保义：圆包络不锐边治，
     *    「不画」是唯一根治位）。
     */
    private fun buildComplexesR373(
        f: TasteFlightFrame,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
    ) {
        if (f.complexes.isEmpty() && f.enrichClouds.isEmpty()) return
        val diag = hypot(w, h)
        val t = f.engine.timeSec
        // ---- R385 P4 语义联动消费（§3.3-3：创世纪 gas 档与 CMB 温度档同源——两云
        //      型分档（摇篮 gas 增浓帽内钳/反射云星未亮黯淡）+超新星 enrich 脉冲
        //      预扫（CYCLE_PULSE 共在窗单源直引）） ----
        val genesis = f.genesisOn && f.genesisSpan.valid
        val gasMult = DeepSpaceNebula.genesisGasMult01(genesis, f.genesisT01)
        val reflectMult = DeepSpaceNebula.genesisReflectMult01(genesis, f.genesisT01)
        scanComplexPulseR385(f, t)
        cpxN = minOf(f.complexes.size, cpxSx.size)
        for (ci in 0 until cpxN) {
            cpxPresence[ci] = 0f // 本帧未入账档（投影失败/出屏/巨雾护栏——星层跳过）
            val c = f.complexes[ci]
            val dist = TasteSpace.distTo(cam, c.cx, c.cy, c.cz)
            val nearFade = DeepSpaceNebula.nearFade01(dist, c.radius)
            if (nearFade <= 0.012f) continue
            // R388 刀1：远档隐没带（看不清就不画——带下 continue 零装填；带内
            // farHide01 C¹ 渐隐；投影失败=恒 1 既有路径不拦，blob 级守卫兜底）
            var farHide = 1f
            if (TasteSpace.project(c.cx, c.cy, c.cz, cam, focal, cx, cy, proj, basisArr)) {
                val hideR = TasteNebula.fogPx(c.radius, proj[3])
                if (hideR < cpxFarHideMinPx) continue
                farHide = farHide01(hideR)
                if (farHide <= 0.012f) continue // 带下沿（同 nearFade 地板口径——零值装填零成本）
            }
            // R385 gas 档：帽内钳（克制档帽不被创世纪越过）+enrich 脉冲叠加（事件窗
            // ≤7s 瞬态——R323 星胚点脉冲同款加法口径，峰值 0.12+0.10=0.22 与 R358
            // 发射摇篮联动档 ~0.21 同量级先例）
            val baseCap = (DeepSpaceNebula.emissionAlpha01(c.ion01) * gasMult)
                .coerceAtMost(DeepSpaceNebula.CPX_ALPHA_MAX)
            val baseA = baseCap * nearFade + cpxPulse[ci]
            // R385 复合体屏位账（复合体级消光原料——云心投影/视半径/深度/在场度；
            // 在场度=clamped 发射 alpha×nearFade/帽：贴脸窗内消光半边随 billboard
            // 渐出让位给 raymarch over 合成（P3 已成的物理减光）——防同一柱密度
            // 两个 LOD 表示双重减光）
            if (TasteSpace.project(c.cx, c.cy, c.cz, cam, focal, cx, cy, proj, basisArr)) {
                val rPx0 = TasteNebula.fogPx(c.radius, proj[3])
                if (rPx0 <= 2.5f * diag &&
                    proj[0] > -rPx0 && proj[0] < w + rPx0 && proj[1] > -rPx0 && proj[1] < h + rPx0
                ) {
                    cpxSx[ci] = proj[0]
                    cpxSy[ci] = proj[1]
                    cpxRpx[ci] = rPx0
                    cpxDepth[ci] = proj[2]
                    // R388 刀1：在场度乘同一淡出因子（消光半边同步出让——看不见
                    // 的云不减光，物理一致；P4 消费口其余零碰）
                    cpxPresence[ci] = (baseCap * nearFade * farHide) / DeepSpaceNebula.CPX_ALPHA_MAX
                }
            }
            val seed = DeepSpaceNebula.churnSeedOf(c.cellKey, c.mass)
            val flocSeed = FogFlocFx.flocSeedOf(seed)
            var blobCount = FogFlocFx.blobCountOf(flocSeed)
            // 远档裁剪：远小 quad 裁至单层（R386 dp 口径阈 cpxFarMinPx——密度岔路
            // 闭合；只裁层数=丝缕基因全保留：种子/NEBULA_FS/octave 零接触，形态判词见常量 KDoc）
            if (blobCount > 1 && TasteSpace.project(c.cx, c.cy, c.cz, cam, focal, cx, cy, proj, basisArr) &&
                TasteNebula.fogPx(c.radius, proj[3]) < cpxFarMinPx
            ) {
                blobCount = 1
            }
            FogFlocFx.blobAlphaPack01(flocSeed, flocAlphaPack, blobCount)
            for (k in 0 until blobCount) {
                // 层深 ±0.5R（§4.2——R281 ±0.35R 的大尺度外推）
                val depthOff = (if (k == 0) 0.5f else -0.5f) * c.radius
                FogFlocFx.blobOffset01(flocSeed, k, flocOff)
                val lx = c.cx + basisArr[0] * depthOff + flocOff[0] * c.radius
                val ly = c.cy + basisArr[1] * depthOff + flocOff[1] * c.radius
                val lz = c.cz + basisArr[2] * depthOff + flocOff[2] * c.radius
                if (!TasteSpace.ahead(lx, ly, lz, cam, basisArr)) continue
                if (!TasteSpace.project(lx, ly, lz, cam, focal, cx, cy, proj, basisArr)) continue
                val sx = proj[0]
                val sy = proj[1]
                val layerBase = if (k == 0) FogFlocFx.FRONT_R else FogFlocFx.BACK_R
                val rPx = TasteNebula.fogPx(
                    c.radius * layerBase * FogFlocFx.blobRadius01(flocSeed, k), proj[3],
                )
                if (rPx > 2.5f * diag) continue // 巨雾护栏（雾族同款）
                if (sx < -rPx || sx > w + rPx || sy < -rPx || sy > h + rPx) continue
                GlPalette.rgbF(DeepSpaceNebula.emissionLayerColor(c.haMix01, c.cellKey, k), rgbA)
                // R388 刀1：blob 装 alpha 乘同一淡出因子（baseA 行原字面零改——
                // 淡出乘在装填口；脉冲随乘=隐没云不发脉冲微光）
                // R388 刀2：丝缕频率标度（每 quad 自身 rPx 口径——任何屏距丝缕
                // 像素宽恒定；阈值上=1 既有基因，下=clamp 至地板）
                val filScale = (rPx / cpxFilRefPx).coerceIn(CPX_FIL_FLOOR, 1f)
                if (rPx > statCpxRpx) { statCpxRpx = rPx; statCpxFarHide = farHide; statCpxFilScale = filScale }
                addNebulaCpx(
                    sx, sy, rPx, seed + k * 13.7f, t,
                    rgbA[0], rgbA[1], rgbA[2], baseA * flocAlphaPack[k] * farHide, filScale,
                )
            }
        }
        for (e in f.enrichClouds) {
            val dist = TasteSpace.distTo(cam, e.cx, e.cy, e.cz)
            val nearFade = DeepSpaceNebula.nearFade01(dist, e.radius)
            if (nearFade <= 0.012f) continue
            val flocSeed = FogFlocFx.flocSeedOf(DeepSpaceNebula.churnSeedOf(e.cellKey, e.members.toFloat()))
            if (!TasteSpace.project(e.cx, e.cy, e.cz, cam, focal, cx, cy, proj, basisArr)) continue
            val sx = proj[0]
            val sy = proj[1]
            // 单层微光块（blob 半径系数收缩=逐云异构——弥散保义零纹理零剖面锐化）
            val rPx = TasteNebula.fogPx(
                e.radius * FogFlocFx.blobRadius01(flocSeed, 0), proj[3],
            )
            if (rPx > 2.5f * diag) continue
            if (sx < -rPx || sx > w + rPx || sy < -rPx || sy > h + rPx) continue
            // R388 刀1：反射微光同一病类同带（看不清就不画——带下 continue+带内
            // 同一 farHide01 单源渐隐；billboard 半径=本 blob rPx 口径）
            if (rPx < cpxFarHideMinPx) continue
            val farHide = farHide01(rPx)
            GlPalette.rgbF(e.colorArgb, rgbA)
            // R385 反射云创世纪档：星未亮的早期黯至地板（反射=星光的暗回声——
            // 诚实不为不存在的星光造亮；gas 档不作用于反射云）
            // R388 刀1：同带淡出因子随乘（同一病类同带——隐没带物理一致）
            // R388 刀2：反射微光同一频率锁定口径（rPx 本 quad 自身口径）
            val filScale = (rPx / cpxFilRefPx).coerceIn(CPX_FIL_FLOOR, 1f)
            if (rPx > statCpxRpx) { statCpxRpx = rPx; statCpxFarHide = farHide; statCpxFilScale = filScale }
            addNebulaCpx(
                sx, sy, rPx, DeepSpaceNebula.churnSeedOf(e.cellKey, e.members.toFloat()), t,
                rgbA[0], rgbA[1], rgbA[2], DeepSpaceNebula.enrichAlpha01() * reflectMult * nearFade * farHide,
                filScale,
            )
        }
    }

    /**
     * R385 超新星遗迹 enrich 最近云预扫（design §3.3-3：空间共在门沿用 R323
     * [EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2]=300² **单源直引零重定义**——星胚
     * 脉动增亮消费口同源；R323 emissionFogs 链零接触）：活跃超新星
     * （[TasteFlight.FxEngine.supernovaFlashes] GL 线程直读——R323 同口径竞态
     * 良性）世界位×复合体质心距²<共在窗→复合体整体增亮脉冲（[EmissionNebulaFx.
     * cyclePulse01] 同口消费 CYCLE_PULSE 参数族）多源取 max。「最近云」由共在窗
     * +max 脉冲实现（复合体质心间距≫300 窗径——窗内即最近）。零超新星=恒零
     * 门外零行为（早退零扫描）；事件窗 ≤7s O(n×4)——R323 发射雾扫描同构预算。
     */
    private fun scanComplexPulseR385(f: TasteFlightFrame, t: Float) {
        for (i in cpxPulse.indices) cpxPulse[i] = 0f
        val complexes = f.complexes
        if (complexes.isEmpty()) return
        val sn = f.engine.supernovaFlashes
        if (sn.isEmpty()) return
        val n = minOf(complexes.size, cpxPulse.size)
        for (s in f.stars) {
            val ttl = runCatching { sn[s.chatId] }.getOrNull() // GL 线程直读主线程账——竞态良性
            if (ttl == null || ttl <= 0f) continue
            for (i in 0 until n) {
                val c = complexes[i]
                val dx = s.x - c.cx
                val dy = s.y - c.cy
                val dz = s.z - c.cz
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 >= EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2) continue
                val pulse = EmissionNebulaFx.cyclePulse01(
                    d2 / EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2, true, t, c.cellKey,
                )
                if (pulse > cpxPulse[i]) cpxPulse[i] = pulse
            }
        }
    }

    /**
     * R235 未探索星云（**R281 体积化真身 + R351 雾絮化**：每雾 2~3 blob 哈希
     * 错位叠画——[FogFlocFx] 单源（fBm 值噪声 blob 族）；前后双层族保义
     * k=0 前（+0.35×半径深错位×0.90 基径）/k≥1 后（−0.35×1.10）沿相机前向
     * 真深度差（飞近层间真视差），叠**世界系 3D 错位向**（哈希球面 2 DOF×fBm
     * 模长 ≤0.45r——绕飞真视差，中距离不再读作「同心 fbm 椭圆贴图」）；域扭曲
     * fbm 密度场 shader 丝缕缓 churn（NEBULA_FS 块内结构原样——絮化≠锐化，
     * 弥散语义红线：blob 边=既有径向 falloff 零纹理零剖面锐化）；alpha=fogAlpha
     * (dist) 同式×配比包（Σ恒 1.35 零增益——絮化只重分布不放大）。
     * R323 发射星云分化两型（镜像 Canvas drawUnexploredNebulas——判据/参数全
     * [EmissionNebulaFx] 单源=双端同式）：发射=亮档（alpha×[EmissionNebulaFx.
     * EMISSION_ALPHA_MULT] 帽 0.10+饱和度升 rgb）+电离侧辉（朝最近 O/B 星侧偏置
     * addNebula）+星胚 ≤8 addPoint（基 alpha 0.05~0.10×电离调制+超新星脉冲——
     * R328 挂账联动口）；普通=R235 现状语义原样（两型共用 blob 绘制段=r344 §二
     * B 表「雾团（普通+发射）」行授权同絮）。
     */
    private fun buildNebulas(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        if (f.nebulae.isEmpty()) return
        val diag = hypot(w, h)
        val t = f.engine.timeSec
        // ---- R323 发射星云预扫（对位防御：平行表长度不齐=零发射零扫零成本） ----
        val emissionFogs = f.emissionFogs
        val aligned = emissionFogs.size == f.nebulae.size
        var anyEmission = false
        if (aligned) {
            for (e in emissionFogs) {
                if (e.emission) { anyEmission = true; break }
            }
        }
        if (anyEmission) scanEmissionFogsR323(f)
        for (i in f.nebulae.indices) {
            val c = f.nebulae[i]
            val dist = TasteSpace.distTo(cam, c.cx, c.cy, c.cz)
            val a = TasteNebula.fogAlpha(dist)
            if (a <= 0.012f) continue
            // R320 星际消光账（镜像 Canvas——雾心投影顺手记；GL 雾为双层视差真身，
            // 消光几何以**雾心**（无层错位）为准=与 Canvas 同口径同式）
            if (fogN < fogSx.size) {
                if (TasteSpace.project(c.cx, c.cy, c.cz, cam, focal, cx, cy, proj, basisArr)) {
                    val rPx0 = TasteNebula.fogPx(c.radius, proj[3])
                    if (rPx0 <= 2.5f * diag &&
                        proj[0] > -rPx0 && proj[0] < w + rPx0 && proj[1] > -rPx0 && proj[1] < h + rPx0
                    ) {
                        fogSx[fogN] = proj[0]
                        fogSy[fogN] = proj[1]
                        fogRpx[fogN] = rPx0
                        fogDepth[fogN] = proj[2]
                        fogPresence[fogN] = a / TasteNebula.FOG_ALPHA_MAX
                        fogN++
                    }
                }
            }
            val e = if (aligned) emissionFogs[i] else null
            val emission = e != null && e.emission
            // R323 亮档色（饱和度升——装配期烘焙；普通雾=类别色原样）
            if (emission) GlPalette.rgbF(e!!.brightArgb, rgbA) else GlPalette.rgbF(c.colorArgb, rgbA)
            // R323 发射档 alpha（×1.6 帽 0.10；普通雾原值）
            val fogA = if (emission) EmissionNebulaFx.emissionAlphaOf(a) else a
            val seed = (c.cx * 0.7f + c.cy * 1.3f + c.cz * 1.7f) // 团位哈希=确定性 churn 相位
            // R351 雾絮化：团位哈希折叠 Int→blob 族单源（FogFlocFx——同雾 2~3 blob
            // 哈希错位叠画；churn Float 原样另传 shader 各走各口）
            val flocSeed = FogFlocFx.flocSeedOf(seed)
            val blobCount = FogFlocFx.blobCountOf(flocSeed)
            FogFlocFx.blobAlphaPack01(flocSeed, flocAlphaPack, blobCount)
            for (k in 0 until blobCount) {
                // 前后层族保义：k=0=前层（+0.35×半径真深度差×0.90 基径）、
                // k≥1=后层族（−0.35×1.10——R281 原值直引零调）
                val depthOff = (if (k == 0) FogFlocFx.LAYER_DEPTH_OFF else -FogFlocFx.LAYER_DEPTH_OFF) * c.radius
                FogFlocFx.blobOffset01(flocSeed, k, flocOff)
                val lx = c.cx + basisArr[0] * depthOff + flocOff[0] * c.radius
                val ly = c.cy + basisArr[1] * depthOff + flocOff[1] * c.radius
                val lz = c.cz + basisArr[2] * depthOff + flocOff[2] * c.radius
                if (!TasteSpace.ahead(lx, ly, lz, cam, basisArr)) continue
                if (!TasteSpace.project(lx, ly, lz, cam, focal, cx, cy, proj, basisArr)) continue
                val sx = proj[0]
                val sy = proj[1]
                val layerBase = if (k == 0) FogFlocFx.FRONT_R else FogFlocFx.BACK_R
                val rPx = TasteNebula.fogPx(
                    c.radius * layerBase * FogFlocFx.blobRadius01(flocSeed, k), proj[3],
                )
                if (rPx > 2.5f * diag) continue // 巨雾护栏（同 Canvas）
                if (sx < -rPx || sx > w + rPx || sy < -rPx || sy > h + rPx) continue
                addNebula(
                    sx, sy, rPx, seed + k * 13.7f, t,
                    rgbA[0], rgbA[1], rgbA[2], fogA * flocAlphaPack[k],
                )
            }
            if (emission) {
                // ---- R323 电离侧辉+星胚（镜像 Canvas——雾心投影消费：O/B 源投影→
                //      屏向偏置侧辉 addNebula；星胚 addPoint 点族） ----
                if (TasteSpace.project(c.cx, c.cy, c.cz, cam, focal, cx, cy, proj, basisArr)) {
                    val sx = proj[0]
                    val sy = proj[1]
                    val rPx = TasteNebula.fogPx(c.radius, proj[3])
                    var ionDirX = 0f
                    var ionDirY = 0f
                    var ionValid = false
                    if (i < fogIonValid.size && fogIonValid[i] &&
                        TasteSpace.project(fogIonX[i], fogIonY[i], fogIonZ[i], cam, focal, cx, cy, proj, basisArr)
                    ) {
                        var ddx = proj[0] - sx
                        var ddy = proj[1] - sy
                        val dl = hypot(ddx, ddy)
                        if (dl > 2f) {
                            ddx /= dl
                            ddy /= dl
                            ionDirX = ddx
                            ionDirY = ddy
                            ionValid = true
                            val glowA = EmissionNebulaFx.ionizationSideGlowAlpha(fogA, 1f)
                            val off = EmissionNebulaFx.IONIZATION_OFFSET_COEF * rPx
                            val gr = EmissionNebulaFx.IONIZATION_RADIUS_COEF * rPx
                            addNebula(
                                sx + ddx * off, sy + ddy * off, gr, seed + 31.3f, t,
                                rgbA[0], rgbA[1], rgbA[2], glowA,
                            )
                        }
                    }
                    for (k in 0 until e!!.embryoCount) {
                        EmissionNebulaFx.embryoOffset01(k, e.seed, r323EmbOut)
                        var ionCos = 0f
                        if (ionValid) {
                            val m = hypot(r323EmbOut[0], r323EmbOut[1])
                            ionCos = if (m > 1e-4f) {
                                (r323EmbOut[0] * ionDirX + r323EmbOut[1] * ionDirY) / m
                            } else {
                                0f
                            }
                        }
                        var alpha = EmissionNebulaFx.embryoAlpha01(k, e.seed, ionCos)
                        if (i < fogPulse.size) alpha += fogPulse[i]
                        // ×2=GL 点族口径（星风流同族：GL size≈2×Canvas 粒径）
                        val size = EmissionNebulaFx.embryoSizePx(k, e.seed) * 2f
                        addPoint(
                            sx + r323EmbOut[0] * rPx, sy + r323EmbOut[1] * rPx,
                            size, 0.6f,
                            rgbA[0], rgbA[1], rgbA[2], alpha.coerceIn(0f, 1f),
                        )
                    }
                }
            }
        }
    }

    /**
     * R323 发射星云帧首预扫（镜像 Canvas scanEmissionFogsR323——双端同式：O/B
     * 电离源单遍星表扫（[EmissionNebulaFx.isIonizingSource] core 单口）+超新星
     * 脉冲位（[TasteFlight.FxEngine.supernovaFlashes] GL 线程直读——竞态良性，
     * starFlashes 同口径）×空间共在窗 max 脉冲；帧零分配槽位预分配）。
     */
    private fun scanEmissionFogsR323(f: TasteFlightFrame) {
        val nebN = minOf(f.nebulae.size, fogIonValid.size)
        for (i in 0 until fogIonValid.size) {
            fogIonValid[i] = false
            fogIonD2[i] = Float.MAX_VALUE
            fogPulse[i] = 0f
        }
        val t = f.engine.timeSec
        val nowSec = f.nowMs / 1000L
        val sn = f.engine.supernovaFlashes
        val snActive = sn.isNotEmpty()
        for (s in f.stars) {
            if (snActive) {
                val ttl = runCatching { sn[s.chatId] }.getOrNull() // GL 线程直读主线程账——竞态良性
                if (ttl != null && ttl > 0f) {
                    for (i in 0 until nebN) {
                        if (f.emissionFogs[i].emission) {
                            val c = f.nebulae[i]
                            val dx = s.x - c.cx
                            val dy = s.y - c.cy
                            val dz = s.z - c.cz
                            val d2 = dx * dx + dy * dy + dz * dz
                            val pulse = EmissionNebulaFx.cyclePulse01(
                                d2 / EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2, true, t,
                                f.emissionFogs[i].seed,
                            )
                            if (pulse > fogPulse[i]) fogPulse[i] = pulse
                        }
                    }
                }
            }
            val lastActDays = if (s.lastActivitySec > 0L) {
                (nowSec - s.lastActivitySec) / 86_400L
            } else {
                -1L
            }
            if (EmissionNebulaFx.isIonizingSource(s.recent7, lastActDays)) {
                for (i in 0 until nebN) {
                    if (!f.emissionFogs[i].emission) continue
                    val c = f.nebulae[i]
                    val dx = s.x - c.cx
                    val dy = s.y - c.cy
                    val dz = s.z - c.cz
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 < fogIonD2[i]) {
                        fogIonD2[i] = d2
                        fogIonX[i] = s.x
                        fogIonY[i] = s.y
                        fogIonZ[i] = s.z
                        fogIonValid[i] = true
                    }
                }
            }
        }
    }

    /** 今晚星轨暗光路（镜像 draw() 段 3：空间折线 alpha=14/255）。
     *  R314：星轨并入「星座」层——开关关=暗光路不画（Canvas 侧同门径） */
    private fun buildRoute(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        if (!f.showConstellation) return
        val e = f.engine
        if (e.routeSize < 2) return
        var px = 0f
        var py = 0f
        var has = false
        for (i in 0 until e.routeSize) {
            if (!TasteSpace.ahead(e.routeX[i], e.routeY[i], e.routeZ[i], cam, basisArr)) {
                has = false
                continue
            }
            TasteSpace.project(e.routeX[i], e.routeY[i], e.routeZ[i], cam, focal, cx, cy, proj, basisArr)
            if (has && proj[0] > -80f && proj[0] < w + 80f && proj[1] > -80f && proj[1] < h + 80f) {
                addLine(px, py, proj[0], proj[1], 1f, 1f, 1f, 14f / 255f)
            }
            px = proj[0]
            py = proj[1]
            has = true
        }
    }

    /** 连线流光（镜像 drawFlow：沿边插值点，fade=4t(1−t)，fog 渐远） */
    private fun buildFlow(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        val e = f.engine
        if (e.flowActive <= 0 || f.edges.isEmpty()) return
        val cr = ((FLOW_COLOR shr 16) and 0xFF) / 255f
        val cg = ((FLOW_COLOR shr 8) and 0xFF) / 255f
        val cb = (FLOW_COLOR and 0xFF) / 255f
        for (k in 0 until e.flowActive) {
            val ei = e.flowEdge[k]
            if (ei < 0 || ei >= f.edges.size) continue
            val edge = f.edges[ei]
            val ft = e.flowT[k]
            val x = edge.ax + (edge.bx - edge.ax) * ft
            val y = edge.ay + (edge.by - edge.ay) * ft
            val z = edge.az + (edge.bz - edge.az) * ft
            if (!TasteSpace.ahead(x, y, z, cam, basisArr)) continue
            TasteSpace.project(x, y, z, cam, focal, cx, cy, proj, basisArr)
            val sx = proj[0]
            val sy = proj[1]
            if (sx < -8f || sx > w + 8f || sy < -8f || sy > h + 8f) continue
            val fog = TasteSpace.fogOf(proj[2])
            val fade = (4f * ft * (1f - ft)).coerceIn(0f, 1f)
            addPoint(sx, sy, (1.5f + 1.1f * fog) * 2f, 0.72f, cr, cg, cb, (0.20f + 0.45f * edge.alpha) * fog * fade)
        }
    }

    /** R234 尘埃带（镜像 drawSpread：源脉冲双环+带贝塞尔 20 段+粒子桶+行进微光） */
    private fun buildSpread(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float, t: Float) {
        val spread = f.spread ?: return
        val cursor = f.spreadCursorMs
        if (cursor < spread.t0Ms) return
        val sr = ((spread.colorArgb shr 16) and 0xFF) / 255f
        val sg = ((spread.colorArgb shr 8) and 0xFF) / 255f
        val sb = (spread.colorArgb and 0xFF) / 255f
        // 源恒星首发标记（亮环脉冲双环）
        if (TasteSpace.ahead(spread.sx, spread.sy, spread.sz, cam, basisArr)) {
            TasteSpace.project(spread.sx, spread.sy, spread.sz, cam, focal, cx, cy, proj, basisArr)
            val sxs = proj[0]
            val sys = proj[1]
            if (sxs > -60f && sxs < w + 60f && sys > -60f && sys < h + 60f) {
                val pulse = 0.80f + 0.20f * sin(t * 1.7f)
                addRingQuad(sxs, sys, 12f * pulse, spread.colorArgb, 205f / 255f)
                addRingQuad(sxs, sys, 19f * (2f - pulse), spread.colorArgb, 70f / 255f)
            }
        }
        for (band in spread.bands) {
            if (cursor < band.firstMs) continue
            if (!TasteSpace.ahead(band.ax, band.ay, band.az, cam, basisArr) ||
                !TasteSpace.ahead(band.bx, band.by, band.bz, cam, basisArr)
            ) {
                continue
            }
            TasteSpace.project(band.ax, band.ay, band.az, cam, focal, cx, cy, proj, basisArr)
            val x1 = proj[0]
            val y1 = proj[1]
            TasteSpace.project(band.bx, band.by, band.bz, cam, focal, cx, cy, proj, basisArr)
            val x2 = proj[0]
            val y2 = proj[1]
            if ((x1 < 0f && x2 < 0f) || (x1 > w && x2 > w) || (y1 < 0f && y2 < 0f) || (y1 > h && y2 > h)) continue
            TasteSpace.project(band.cx, band.cy, band.cz, cam, focal, cx, cy, proj, basisArr)
            val x3 = proj[0]
            val y3 = proj[1]
            // 带基底曲线（弱透明——二次贝塞尔 20 段折线）
            var bpx = x1
            var bpy = y1
            for (k in 1..20) {
                val uu = k / 20f
                val iu = 1f - uu
                val qx = iu * iu * x1 + 2f * iu * uu * x3 + uu * uu * x2
                val qy = iu * iu * y1 + 2f * iu * uu * y3 + uu * uu * y2
                addLine(bpx, bpy, qx, qy, sr, sg, sb, 34f / 255f)
                bpx = qx
                bpy = qy
            }
            // 粒子（真实条目：光标越过发布时刻可见；三档 alpha 桶同值）
            val pms = band.particleMs
            val pxs = band.particleX
            val pys = band.particleY
            val pzs = band.particleZ
            val pbk = band.particleBucket
            for (i in pxs.indices) {
                if (cursor < pms[i]) continue
                if (!TasteSpace.ahead(pxs[i], pys[i], pzs[i], cam, basisArr)) continue
                TasteSpace.project(pxs[i], pys[i], pzs[i], cam, focal, cx, cy, proj, basisArr)
                if (proj[0] < -40f || proj[0] > w + 40f || proj[1] < -40f || proj[1] > h + 40f) continue
                val bk = pbk[i].toInt()
                if (bk !in 0 until TasteTagSpread.ALPHA_BUCKETS) continue
                addPoint(proj[0], proj[1], 2.2f, 0.72f, sr, sg, sb, SPREAD_BUCKET_ALPHA[bk])
            }
            // 行进微光（源→下游方向标记，~8s 一趟）
            val seed = (band.toChatId % 100_000L).toInt()
            val u = (t * 0.125f + galaxyH01(seed, 3)) % 1f
            val iu = 1f - u
            val fx = iu * iu * band.ax + 2f * iu * u * band.cx + u * u * band.bx
            val fy = iu * iu * band.ay + 2f * iu * u * band.cy + u * u * band.by
            val fz = iu * iu * band.az + 2f * iu * u * band.cz + u * u * band.bz
            if (TasteSpace.ahead(fx, fy, fz, cam, basisArr)) {
                TasteSpace.project(fx, fy, fz, cam, focal, cx, cy, proj, basisArr)
                if (proj[0] > -40f && proj[0] < w + 40f && proj[1] > -40f && proj[1] < h + 40f) {
                    val fog = TasteSpace.fogOf(proj[2])
                    addPoint(
                        proj[0], proj[1], (1.6f + 0.7f * sin(t * 3f + seed)) * 2f, 0.72f,
                        sr, sg, sb, (0.42f * fog).coerceIn(0.10f, 0.42f),
                    )
                }
            }
        }
    }

    /** R234 星座轨迹（镜像 drawConstellation：时间序渐亮折线+站点点+头光脉冲） */
    private fun buildConstellation(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float, t: Float) {
        if (!f.showConstellation) return
        val trail = f.constellation ?: return
        if (trail.n < 2) return
        val lr = ((CONST_COLOR shr 16) and 0xFF) / 255f
        val lg = ((CONST_COLOR shr 8) and 0xFF) / 255f
        val lb = (CONST_COLOR and 0xFF) / 255f
        val dr = ((CONST_DOT_COLOR shr 16) and 0xFF) / 255f
        val dg = ((CONST_DOT_COLOR shr 8) and 0xFF) / 255f
        val db = (CONST_DOT_COLOR and 0xFF) / 255f
        var px = 0f
        var py = 0f
        var has = false
        val lastInv = 1f / (trail.n - 1).coerceAtLeast(1)
        for (i in 0 until trail.n) {
            if (!TasteSpace.ahead(trail.xs[i], trail.ys[i], trail.zs[i], cam, basisArr)) {
                has = false
                continue
            }
            TasteSpace.project(trail.xs[i], trail.ys[i], trail.zs[i], cam, focal, cx, cy, proj, basisArr)
            val sx = proj[0]
            val sy = proj[1]
            val grad = i * lastInv
            if (has && sx > -80f && sx < w + 80f && sy > -80f && sy < h + 80f) {
                addLine(px, py, sx, sy, lr, lg, lb, 0.11f + 0.32f * grad)
            }
            if (sx > -40f && sx < w + 40f && sy > -40f && sy < h + 40f) {
                addPoint(sx, sy, (1.7f + 0.8f * grad) * 2f, 0.72f, dr, dg, db, 0.45f + 0.55f * grad)
                if (i == trail.n - 1) {
                    val pulse = 0.7f + 0.3f * sin(t * 3.2f)
                    addPoint(sx, sy, (5.5f + 1.6f * pulse) * 2f, 0.3f, dr, dg, db, 64f / 255f)
                }
            }
            px = sx
            py = sy
            has = true
        }
    }

    // ==================== 星 ====================

    private fun buildStars(
        f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float, t: Float) {
        val genesis = f.genesisOn && f.genesisSpan.valid
        val playing = f.playingChatId
        val stars = f.stars
        val knownSet = f.discoveredIds
        val flashes = f.engine.starFlashes
        // ---- R248 探索暗示预扫（镜像 Canvas：最近未知频道星——屏缘 chevron 目标） ----
        var hintSi = -1
        var hintD2 = Float.MAX_VALUE
        for (si in stars.indices) {
            val s = stars[si]
            if (s.chatId in knownSet || !isChannelStarKind(s.kind)) continue
            val dxh = s.x - cam.px
            val dyh = s.y - cam.py
            val dzh = s.z - cam.pz
            val d2 = dxh * dxh + dyh * dyh + dzh * dzh
            if (d2 < hintD2) {
                hintD2 = d2
                hintSi = si
            }
        }

        // ---- pass1：投影+剔除+半径/亮度（与 Canvas drawStars pass1 同式） ----
        if (order.size < stars.size) {
            order = IntArray(stars.size)
            slotStar = IntArray(stars.size)
            pSx = FloatArray(stars.size)
            pSy = FloatArray(stars.size)
            pDepth = FloatArray(stars.size)
            pR = FloatArray(stars.size)
            pA = FloatArray(stars.size)
            pWx = FloatArray(stars.size)
            pWy = FloatArray(stars.size)
            pWz = FloatArray(stars.size)
        }
        val grav = f.gravity
        val usePhys = grav != null && grav.n == stars.size
        if (usePhys && f.nowMs > 0L) grav!!.beginRenderFrame(f.nowMs)
        val rX = grav?.renderX
        val rY = grav?.renderY
        val rZ = grav?.renderZ
        val wellCount = (grav?.wellCount ?: 0).coerceAtMost(64)
        val wIdx = grav?.wellIdx
        val wMass = grav?.wellMass01
        val wSwirl = grav?.wellSwirl
        var lensOn = false
        if (grav != null && wellCount > 0) {
            lensOn = true
            for (wl in 0 until wellCount) {
                val wi = wIdx!![wl]
                if (!TasteSpace.project(rX!![wi], rY!![wi], rZ!![wi], cam, focal, cx, cy, proj, basisArr)) {
                    wellRpx[wl] = -1f
                    continue
                }
                wellSx[wl] = proj[0]
                wellSy[wl] = proj[1]
                wellDepth[wl] = proj[2]
                wellRpx[wl] = stars[wi].bodyR * proj[3]
                if (wellRpx[wl] < 2f) wellRpx[wl] = 2f
            }
        }
        var visN = 0
        val swallowStart = minOf(w, h) * TasteSpace.SWALLOW_FADE_START_FRAC
        val swallowMax = minOf(w, h) * TasteSpace.SWALLOW_MAX_R_FRAC
        for (si in stars.indices) {
            val s = stars[si]
            val px = if (usePhys) rX!![si] else s.x
            val py = if (usePhys) rY!![si] else s.y
            val pz = if (usePhys) rZ!![si] else s.z
            if (!TasteSpace.project(px, py, pz, cam, focal, cx, cy, proj, basisArr)) continue
            // ---- R249 引力透镜（同 Canvas 查表弯曲——TasteAstroPhysics 单口） ----
            if (lensOn) {
                for (wl in 0 until wellCount) {
                    if (wellRpx[wl] <= 0f) continue
                    val wi = wIdx!![wl]
                    if (wi == si) continue
                    val dxL = proj[0] - wellSx[wl]
                    val dyL = proj[1] - wellSy[wl]
                    val infl = TasteAstroPhysics.lensInfluenceOf(wellRpx[wl])
                    if (dxL * dxL + dyL * dyL > infl * infl) continue
                    val behind = (proj[2] / wellDepth[wl]).coerceIn(0.5f, 1.5f)
                    if (TasteAstroPhysics.lensOffset(dxL, dyL, wellRpx[wl], wMass!![wl], behind, wSwirl!![wl], lensScratch)) {
                        proj[0] += lensScratch[0]
                        proj[1] += lensScratch[1]
                    }
                }
            }
            // ---- R320 引力波波前位移（透镜偏移同插入位——镜像 Canvas pass1 星位叠加） ----
            if (waveN > 0 && waveOffsetR320(proj[0], proj[1], waveScratch)) {
                proj[0] = waveScratch[0]
                proj[1] = waveScratch[1]
            }
            val depth = proj[2]
            val sizeK = proj[3]
            var r = s.bodyR * sizeK
            when (s.kind) {
                CelestialKind.RED_GIANT -> r *= 1.8f
                CelestialKind.PROTOSTAR -> r *= 0.85f
                CelestialKind.WHITE_DWARF -> r = min(r, 3.5f * sizeK)
                CelestialKind.SUPERNOVA -> r = max(r * 1.15f, 2.2f)
                CelestialKind.BLACK_HOLE -> r = max(r * 1.45f, 2.4f)
                // R317b 致密星小体档+白洞本体（镜像 Canvas——双端同式）
                CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR -> r = min(r * 0.95f, 4.2f * sizeK)
                CelestialKind.WHITE_HOLE -> r = max(r * 1.1f, 1.6f)
                else -> {}
            }
            if (r <= 0.55f) continue
            val extent = when (s.kind) {
                CelestialKind.RED_GIANT -> r * 2.6f
                CelestialKind.PROTOSTAR -> r * 2.4f
                CelestialKind.SUPERNOVA -> r * 3.0f
                CelestialKind.CLUSTER -> r * 2.9f
                CelestialKind.METEOR -> r * 4.2f
                CelestialKind.BLACK_HOLE -> r * 4.6f
                // R317b：遗迹环/喷流外延（镜像 Canvas）
                CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR -> r * 2.6f
                CelestialKind.WHITE_HOLE -> TasteTerminalFx.whiteholeJetLen(r)
                else -> r * 2.9f
            }
            if (proj[0] < -extent || proj[0] > w + extent || proj[1] < -extent || proj[1] > h + extent) continue
            var baseA = (s.bodyAlpha * TasteSpace.fogOf(depth)).coerceIn(0.05f, 1f)
            if (s.unexplored) {
                // R302 星域迷雾（镜像 Canvas——只降亮不锁内容）：离已点亮质心超
                // 雾界渐暗 ≤20%（封顶红线 0.25 内）；先减后 boost=飞近恒能盖过雾
                if (f.mistReach > 0f) {
                    val mdx = px - f.mistCx
                    val mdy = py - f.mistCy
                    val mdz = pz - f.mistCz
                    baseA *= 1f - dev.tgfeed.core.tastemap.TasteStarlight.mistDimOf(
                        kotlin.math.sqrt(mdx * mdx + mdy * mdy + mdz * mdz), f.mistReach,
                    )
                }
                // R302 远视（镜像 Canvas）：渐显起点随星辉等级（Lv5 起 700→1200）
                val boost = TasteNebula.discoveryBoost(depth, f.discoverNearStart)
                if (boost > 0f) {
                    baseA = (baseA + boost).coerceAtMost(1f)
                    r *= 1f + 0.35f * (boost / TasteNebula.DISCOVER_BOOST_MAX)
                }
            }
            if (r > swallowStart) {
                baseA *= TasteSpace.swallowFade(r, swallowStart, swallowMax)
                if (r > swallowMax) r = swallowMax
            }
            // ---- R320 星际消光（镜像 Canvas：星深>雾深=真实遮星+屏距<雾视半径 →
            //      变暗 ×0.8 帽+色温偏移记 pExtOv——pass2 主序连续色温支路消费） ----
            if (fogN > 0) {
                var extOv = 0f
                for (fi in 0 until fogN) {
                    if (depth <= fogDepth[fi]) continue // 星在雾前（或同深）=不遮
                    val fdx = proj[0] - fogSx[fi]
                    val fdy = proj[1] - fogSy[fi]
                    val fr = fogRpx[fi]
                    if (fdx * fdx + fdy * fdy > fr * fr) continue // 屏距域外早退
                    val ov = EclipseFx.extinctionOverlap01(hypot(fdx, fdy), fr) *
                        fogPresence[fi]
                    if (ov > extOv) extOv = ov // 多雾取最重（叠加近似——消光线性域内）
                }
                if (extOv > 0f) {
                    baseA *= 1f - EclipseFx.extinctionDim01(extOv)
                    pExtOv[si] = extOv
                }
            }
            // ---- R385 复合体级消光（design §3.3-2：星深>云深=云在星与相机之间→
            //      变暗偏红——柱密度=屏面重合度（extinctionOverlap01 团级几何单源
            //      复用）×在场度，透射下限 0.75 候选带；多变暗帽与色温偏移两源
            //      各自线性域、取最重柱——叠加近似同团级口径；穿云前方星群集体
            //      减光偏红=物理即氛围） ----
            if (cpxN > 0) {
                var extCpx = 0f
                for (ci in 0 until cpxN) {
                    if (cpxPresence[ci] <= 0f) continue // 本帧未入账（出屏/近窗/护栏）
                    if (depth <= cpxDepth[ci]) continue // 星在云前（或同深）=不遮
                    val cdx = proj[0] - cpxSx[ci]
                    val cdy = proj[1] - cpxSy[ci]
                    val cr = cpxRpx[ci]
                    if (cdx * cdx + cdy * cdy > cr * cr) continue // 屏距域外早退
                    val col = EclipseFx.extinctionCpxColumn01(
                        EclipseFx.extinctionOverlap01(hypot(cdx, cdy), cr),
                        cpxPresence[ci],
                    )
                    if (col > extCpx) extCpx = col // 多云取最重柱（线性域叠加近似）
                }
                if (extCpx > 0f) {
                    baseA *= 1f - EclipseFx.extinctionCpxDim01(extCpx)
                    pExtCpx[si] = extCpx
                }
            }
            order[visN] = visN // 槽位序（排序键=pDepth 槽位索引——同口径配对）
            slotStar[visN] = si
            pSx[visN] = proj[0]
            pSy[visN] = proj[1]
            pDepth[visN] = depth
            pR[visN] = r
            pA[visN] = baseA
            pWx[visN] = px
            pWy[visN] = py
            pWz[visN] = pz
            visN++
        }
        statVisibleStars = visN
        if (visN > 1) sortDesc(order, pDepth, 0, visN - 1)

        // ---- R339 星芒门预扫（帧首 pass1 后/pass2 前——亮度降序 top 门；候选域=
        //      个体绘制点形态星（R349 形态线 [lodPointCeilPx, effectiveSolidFloor)
        //      ——点云层星不拆云+resolved 无芒）；pass2 buildStarBody 消费
        //      [spikeB01] 发芒 ----
        selectSpikesR339(stars, visN)

        // ---- R349 低模球籍预选（同插入位——最小屏径优先出球籍；pass2
        //      addLowSphere 消费 [lowSphereOk]） ----
        selectLowSpheresR349(stars, visN)

        // ---- R320 凌星深度预扫（镜像 Canvas scanTransitDepthR320——pass2 恒星
        //      装配前算食深；倾角反例系统自然零深度不造食） ----
        scanTransitDepthR320(f, cam, focal, w, h, cx, cy, t, usePhys, grav)

        // ---- pass2：深度序装配（远→近画家序；类型切换即冲刷=与 Canvas 逐位同序） ----
        val revealDone = f.revealT >= 1f
        for (vi in 0 until visN) {
            val slot = order[vi]
            val si = slotStar[slot]
            val s = stars[si]
            val sx = pSx[slot]
            val sy = pSy[slot]
            val depth = pDepth[slot]
            var r = pR[slot]
            var baseA = pA[slot]
            if (!revealDone) {
                val batch = vi / REVEAL_BATCH
                val raw = (f.revealT * 3f - batch).coerceIn(0f, 1f)
                val eased = 1f - (1f - raw) * (1f - raw) * (1f - raw)
                if (eased <= 0f) continue
                baseA *= eased
            }
            var birthPop = 1f
            // R328 生灭回放死态旗（镜像 Canvas——白矮星「死而不灭」亮地板让位暗核档）
            var remnantDead = false
            if (genesis) {
                val age = TasteFlight.birthAgeMs(s.firstSeenMs, f.genesisSpan, f.genesisT01)
                if (age < 0L) continue
                // ---- R328 生灭回放（r325 C①-4 第一档——TasteLifeReplay 单口，无证据
                //      恒生；镜像 Canvas）：光标越过熄灭时刻=星转终态视觉——超新星=
                //      爆发时刻闪一下再熄（死闪窗内亮+膨胀，窗外 continue 已熄不可
                //      见）；白矮星/其余 kind=致密残骸暗核化 ----
                if (TasteLifeReplay.diedVisible(s.lastActivitySec, f.genesisSpan, f.genesisT01)) {
                    if (s.kind == CelestialKind.SUPERNOVA) {
                        val flash = TasteLifeReplay.deathFlash01(s.lastActivitySec, f.genesisSpan, f.genesisT01)
                        if (flash <= 0f) continue // 窗外=已熄（遗迹环归正常态演出）
                        baseA = (baseA + flash).coerceAtMost(1f)
                        r *= 1f + 0.8f * flash
                    } else {
                        remnantDead = true
                        baseA *= TasteLifeReplay.REMNANT_CORE_K
                    }
                }
                birthPop = (age / 900f).coerceIn(0f, 1f)
                val eased = 1f - (1f - birthPop) * (1f - birthPop) * (1f - birthPop)
                r *= 0.15f + 0.85f * eased
                baseA *= 0.1f + 0.9f * eased
            }
            // 微星合并（R349：<点档上限 频道天体→点云；R247 深度三档——并点线
            // 2.2→点档上限随形态线统一；R355 dp 化=帧字段 [lodPointCeilPx]）
            if (revealDone && !genesis && r < lodPointCeilPx && canMicroMerge(s.kind)) {
                val tier = if (depth < 900f) 0 else if (depth < 2600f) 1 else 2
                val bucket = GlPalette.microBucketOf(s.bodyColor, baseA >= 0.72f, hsv)
                val mc = GlPalette.microColors[bucket]
                // R343 A1：微星 18 桶同频呼吸退役（r344 审计 A 级——桶级齐闪=假
                // 运动；远星恒稳更真；镜像 Canvas 同刀；帧预算减一次 sin）
                val a = MICRO_TIER_A[tier] * 0.90f
                addPoint(
                    sx, sy, MICRO_TIER_W[tier], 0.72f,
                    ((mc shr 16) and 0xFF) / 255f, ((mc shr 8) and 0xFF) / 255f, (mc and 0xFF) / 255f, a,
                )
                statMicroStars++
                continue
            }
            val isPlaying = s.chatId != 0L && s.chatId == playing
            if (isPlaying) baseA = (baseA * 1.4f + 0.30f).coerceAtMost(1f)
            val seed = (s.chatId % 100_000L).toInt()
            // R349 resolved 无闪（r341 §5.2 物理立规）：微闪 ±8% 幅度×pointFadeK
            // ——blend 进 3D 即退场（3D 档星不眨眼=天文常识即用户「真实」审美；
            // tw 仍是点/球双态公因子——能量守恒恒等式不受影响）
            val tw = 1f + 0.08f * TasteLodPolicy.pointFadeK(r, lodSolidFloorPx, lodPointCeilPx) *
                sin(t * (0.5f + 1.9f * galaxyH01(seed, 61)) + galaxyH01(seed, 67) * 6.28f)
            var alpha = baseA * tw
            // R320 凌星食深（镜像 Canvas：恒星微暗 1−深度——真实测光量级 1.3%~4.0%）
            if (pEcl[si] > 0f) alpha *= 1f - pEcl[si]
            // ---- R311 造父变星（周期性更新频道=变星——真光变曲线快升慢降+周期-光度
            //      关系；R334 半径项退役（镜像 Canvas——r333 §2.3：0.35→
            //      TasteStarGlow.cepheidRadiusCoef，r<球门 恒 0=光晕禁慢胀缩/球门上
            //      0.10=真变星径向搏动只在看得见球时成立；R355 球门=密度档帧字段；
            //      亮度调制保留） ----
            if (s.pulsePeriodSec > 0f) {
                val shape = TasteStarColor.cepheidShape(t / s.pulsePeriodSec + s.pulsePhase)
                alpha *= 1f + s.pulseAmp * (shape * 2f - 1f)
                r *= 1f + TasteStarGlow.cepheidRadiusCoef(r, lodSolidFloorPx) * s.pulseAmp * (shape * 2f - 1f)
            }
            if (depth < 90f) alpha *= (depth / 90f).coerceIn(0f, 1f)
            val known = s.chatId in knownSet
            if (!known && isChannelStarKind(s.kind)) {
                alpha *= UNDISCOVERED_PULSE_MIN + (1f - UNDISCOVERED_PULSE_MIN) * (0.5f + 0.5f * sin(t * 1.35f + seed * 0.61f))
            }
            // ---- R248 发现时刻特效（镜像 Canvas：双扩散环+亮度脉冲，REVEAL_FX_SEC 后自熄） ----
            var revealAtMs = 0L
            if (f.revealIds.isNotEmpty() && f.nowMs > 0L) {
                val ra = f.revealIds[s.chatId]
                if (ra != null) {
                    revealAtMs = ra
                    val ageSec = (f.nowMs - ra).coerceAtLeast(0L) / 1000f
                    if (ageSec < REVEAL_FX_SEC) {
                        alpha = (alpha * (1f + 0.35f * (1f - ageSec / REVEAL_FX_SEC))).coerceAtMost(1f)
                        // ---- R328 批C 点燃拍（镜像 Canvas——r325 C②）：年轻星核心
                        //      白闪 300ms 一次性窗（ignitionFlash01 单源：t=0 峰值
                        //      线性衰减窗外恒 0；判别=youngBirthStarR328 单口） ----
                        if (youngBirthStarR328(s.kind, s.birth)) {
                            alpha = (alpha + TasteBirthFx.ignitionFlash01(ageSec)).coerceAtMost(1f)
                        }
                    }
                }
            }

            // ---- 主刷三档 + kind 身份层 ----
            val temp = tempOfStar(s)
            // R281 镜头光斑源收集（top3 亮星——合成 shader 的程序光斑原料；
            // 黑洞=暗体不收（其吸积盘辉光由 bloom 亮部提取自然承接））
            if (s.kind != CelestialKind.BLACK_HOLE && r >= 24f) {
                collectFlare(sx, sy, alpha, r, temp, w, h)
            }
            when (s.kind) {
                CelestialKind.BLACK_HOLE -> buildBlackHole(
                    sx, sy, r, alpha, seed, t, depth, focal, pWx[slot], pWy[slot], pWz[slot], cam, cx, cy,
                    acc01 = TasteAstroPhysics.accretion01Of(s.recent7), // R317b 进食档（core 单口）
                )
                CelestialKind.PULSAR -> {
                    // 脉冲星：极小亮核+周期闪灭双束（频率=确定性哈希 0.5~2Hz——同 Canvas 式）
                    val hz = 0.5f + 1.5f * galaxyH01(seed, 11)
                    val phase = (t * hz) % 1f
                    val on = phase < 0.62f
                    val blink = if (on) 1f - (phase / 0.62f) * 0.30f else 0.30f
                    buildStarBody(
                        s, sx, sy, max(r, 1.4f), alpha * (0.55f + 0.45f * blink),
                        GlPalette.TEMP_COOL, seed, t, s.bodyColor, depth, focal, pWx[slot], pWy[slot], pWz[slot],
                        spikeB01 = spikeB01Of(si),
                        si = si, // R349 低模球籍预选账
                    )
                    if (on && r >= 1.6f) {
                        val len = (r * 5f + 30f).coerceIn(30f, 110f)
                        val rot = (t * hz * 120f) % 360f
                        val beamA = blink * alpha * 96f / 255f
                        addQuad(TEX_BEAM, sx, sy, 6f, len / 2f, -len / 2f, rot, 1f, 1f, 1f, beamA)
                        addQuad(TEX_BEAM, sx, sy, 6f, len / 2f, -len / 2f, rot + 180f, 1f, 1f, 1f, beamA)
                    }
                }
                else -> {
                    var color = s.bodyColor
                    if (s.kind == CelestialKind.RED_GIANT) color = GlPalette.warmShift(color, hsv)
                    if (s.kind == CelestialKind.SUPERNOVA) color = GlPalette.blendRgb(color, GlPalette.SUPERNOVA_TINT, 0.5f)
                    // R317b 身份色（镜像 Canvas：致密星蓝白/白洞暖白——特殊 kind 不被
                    // 色温轴污染惯例）
                    if (s.kind == CelestialKind.NEUTRON_STAR || s.kind == CelestialKind.QUARK_STAR) {
                        color = GlPalette.blendRgb(color, GlPalette.COMPACT_TINT, 0.5f)
                    }
                    if (s.kind == CelestialKind.WHITE_HOLE) {
                        color = GlPalette.blendRgb(color, GlPalette.WHITEHOLE_TINT, 0.5f)
                    }
                    var a2 = if (s.kind == CelestialKind.WHITE_DWARF) {
                        // R328 生灭回放死态=暗核化让位（镜像 Canvas——「死而不灭」亮
                        // 地板只属正常态致密残骸；回放已熄态走 REMNANT_CORE_K 暗核档）
                        if (remnantDead) alpha else max(alpha, 0.92f)
                    } else alpha
                    // R317b 致密星「死而不灭」档+夸克增亮（镜像 Canvas）
                    if (s.kind == CelestialKind.NEUTRON_STAR) a2 = max(a2, TasteTerminalFx.COMPACT_CORE_ALPHA_MIN)
                    if (s.kind == CelestialKind.QUARK_STAR) a2 = (a2 + TasteTerminalFx.QUARK_CORE_BOOST).coerceAtMost(1f)
                    if (s.kind == CelestialKind.WHITE_HOLE) a2 = max(a2, TasteTerminalFx.WHITEHOLE_CORE_ALPHA_MIN)
                    // R328 批C 聚合拍核心进度亮度（镜像 Canvas——accretionCoreK 单源
                    // 消费：0.7+0.3×已聚合量÷升档线；无注记=零调制=现状亮度）
                    if (s.kind == CelestialKind.PROTOSTAR) {
                        val birthNote = s.birth
                        if (birthNote != null) {
                            a2 *= TasteBirthFx.accretionCoreK(TasteAstroPhysics.probeProgress01(birthNote.probeItems))
                        }
                    }
                    val a3 = if (s.kind == CelestialKind.SUPERNOVA) a2 * 1.1f else a2
                    // R281 流星体真身（任务④）+R349 形态线换轨：**r≥[lodPointCeilPx]
                    // =哈希多面体岩石+烧蚀热缘**（圆星体贴纸退役；旧 6px 门随
                    // r341 表「岩石 METEOR ≥2」统一；R355 dp 化=点档上限帧字段）；
                    // blend 带 [1.2,1.6)dp 点×(1−k)+岩石×k（能量守恒同族——r341
                    // §2.2 表「岩石：点+岩石实例 blend」）
                    val meteorRock = s.kind == CelestialKind.METEOR && r >= lodPointCeilPx
                    if (meteorRock) {
                        val rockK = TasteLodPolicy.blend01(r, lodSolidFloorPx, lodPointCeilPx)
                        if (rockK > 0.01f) {
                            val worldR = r * depth / focal
                            GlPalette.rgbF(s.bodyColor, rgbA)
                            var mlx = cam.px - pWx[slot]; var mly = cam.py - pWy[slot]; var mlz = cam.pz - pWz[slot]
                            val mll = sqrt(mlx * mlx + mly * mly + mlz * mlz).coerceAtLeast(1e-6f)
                            mlx /= mll; mly /= mll; mlz /= mll
                            val rockStart = rockCount
                            addRock(
                                pWx[slot], pWy[slot], pWz[slot], worldR,
                                spin = t * (0.4f + 0.5f * galaxyH01(seed, 73)) + galaxyH01(seed, 79) * 6.2831853f,
                                axisX = galaxyH01(seed, 81) - 0.5f, axisY = galaxyH01(seed, 83) - 0.5f, axisZ = galaxyH01(seed, 87) - 0.5f,
                                cr = rgbA[0] * 0.55f + 0.20f, cg = rgbA[1] * 0.55f + 0.17f, cb = rgbA[2] * 0.55f + 0.14f,
                                alpha = a3 * rockK,
                                lx = mlx, ly = mly, lz = mlz, // 头灯式受光（私聊流星体=闯入者观感）
                                ember = 1.1f, seedRock = seed.toFloat(),
                            )
                            flushRocks(rockStart)
                        }
                        if (rockK < 0.99f) {
                            // blend 带点形态（×(1−k) 淡出——统一点形：coreSwell 核+核色；
                            // R355 阈值参透传=密度档窗口）
                            val mCoreC = GlPalette.coreColorOf(GlPalette.TEMP_WHITE)
                            addPoint(
                                sx, sy, max(r, 0.8f) * 2f, TasteStarGlow.coreSwellCoef(r, lodSolidFloorPx, lodPointCeilPx),
                                ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f, a3 * 0.92f * (1f - rockK),
                                ((mCoreC shr 16) and 0xFF) / 255f, ((mCoreC shr 8) and 0xFF) / 255f, (mCoreC and 0xFF) / 255f,
                            )
                        }
                    } else {
                        val tempBody = when (s.kind) {
                            CelestialKind.METEOR -> GlPalette.TEMP_WHITE
                            // R317b 致密星蓝白核/白洞白核（镜像 Canvas——身份色不被色温轴污染）
                            CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR -> GlPalette.TEMP_COOL
                            CelestialKind.WHITE_HOLE -> GlPalette.TEMP_WHITE
                            else -> temp
                        }
                        // R320 消光色温偏移（主序连续色温支路专用——pExtOv 星索引账）
                        // R385 复合体级偏移并入（pExtCpx 星索引账——两源各自线性域
                        // 相加：团级雾账+复合体云账同一尘埃散射物理同一常量单源，
                        // R311 身份色不污染惯例沿 buildStarBody 内守卫）
                        // R321 tideSi=潮汐槽位账星索引（-1=无对；域外 tide=0 恒等）；
                        // R349 si=低模球籍预选账（同值独立传参——PULSAR 等无潮汐调用方同传）
                        val extShiftK320 = if (pExtOv[si] > 0f) EclipseFx.extinctionShiftK(pExtOv[si]) else 0f
                        val extShiftK385 = if (pExtCpx[si] > 0f) EclipseFx.extinctionCpxShiftK(pExtCpx[si]) else 0f
                        buildStarBody(s, sx, sy, r, a3, tempBody, seed, t, color, depth, focal, pWx[slot], pWy[slot], pWz[slot], extShiftK320 + extShiftK385, si, spikeB01Of(si), si)
                    }
                    when (s.kind) {
                        CelestialKind.SUPERNOVA -> {
                            // ---- R350 3D 遗迹壳（r341 §3.3-A——弧线族双端退役收官笔）：
                            //      2D 8 段折线弧（y×0.82 屏空间假透视——转身即露馅）
                            //      整族退役 → **球面纤维点云壳**（WPOINT 世界点 GPU
                            //      投影——星团成员球同族既有件零新程序）：每缕=球面
                            //      大圆弧点串（起角/扫角/半径系数 remnant* 单源直引
                            //      +面方位同族通道）；半径恒定（相位冻结缓漂 0.4°/s
                            //      语义原样——静态哨保义）；点亮度=SHELL_ALPHA×A×
                            //      沿线侵蚀（丝缕断续观感）；密度两档（lodLowMeshMaxPx
                            //      分界——R355 dp 化帧字段）；预算 ≤4 缕×≤12 点
                            //      =≤48 WPOINT/体；深度=不测不写（叠加层语义与现
                            //      弧线同位）。**演出语义零变化红线**：TTL/死亡闪窗/
                            //      爆发提亮照旧（r/alpha 数据驱动自动随壳）；R334
                            //      注记语义全保（色=星色含 SUPERNOVA_TINT 身份混色
                            //      /alpha 恒 SHELL_ALPHA·A 不做衰减包络/「爆发」=
                            //      a3×1.1 提亮+flashStar 一次性脉冲）。门=2f 字面→
                            //      [lodSolidFloorPx]（R349 §2.8「2f 留 R343 消费」
                            //      收口——R355 密度化同族：常态 1.6dp×density；
                            //      回退序①bias 抬升时壳延后登场=诚实降级族） ----
                            if (r >= lodSolidFloorPx) {
                                val shellRWorld = r * TasteStarGlow.SUPERNOVA_SHELL_R * depth / focal
                                val fil = TasteTerminalFx.remnantFilaments(seed)
                                val n = TasteTerminalFx.remnantFiberPoints(r, lodLowMeshMaxPx)
                                val cr = ((color shr 16) and 0xFF) / 255f
                                val cg = ((color shr 8) and 0xFF) / 255f
                                val cb = (color and 0xFF) / 255f
                                val fa = TasteStarGlow.SHELL_ALPHA * alpha
                                val sizeW = max(1.1f, r * 0.22f) * depth / focal
                                val wxS = pWx[slot]
                                val wyS = pWy[slot]
                                val wzS = pWz[slot]
                                val wpStart = wpCount
                                for (k in 0 until fil) {
                                    val rrW = shellRWorld * TasteTerminalFx.remnantArcRCoef(seed, k)
                                    for (j in 0 until n) {
                                        val u = j.toFloat() / (n - 1)
                                        TasteTerminalFx.shellFiberPoint(seed, k, u, t, fiber3, 0)
                                        addWPoint(
                                            wxS + fiber3[0] * rrW,
                                            wyS + fiber3[1] * rrW,
                                            wzS + fiber3[2] * rrW,
                                            sizeW, 0.72f, cr, cg, cb,
                                            fa * TasteTerminalFx.remnantFiberErode01(seed, k, u),
                                        )
                                    }
                                }
                                flushWPoints(wpStart)
                            }
                        }
                        CelestialKind.PROTOSTAR -> {
                            // ---- R328 批C 聚合拍（镜像 Canvas——r325 A②「吸积壳+尘埃粒
                            //      螺旋内落」双端同式，参数 TasteBirthFx 单源）：壳环呼吸
                            //      （accretionShellBreath）+焦点件门（ACCRETION_FOCUS
                            //      _MIN_PX）内螺旋粒（addPoint 点族——r325 A② GL 侧
                            //      指定原语；壳缘亮入→飞行满亮→核心吸收淡出） ----
                            if (r >= 1.6f) {
                                val breath = TasteBirthFx.accretionShellBreath(t, seed)
                                addRingQuad(sx, sy, r * 2.1f, GlPalette.PROTOSTAR_TINT, alpha * 0.30f * breath)
                                if (r >= TasteBirthFx.ACCRETION_FOCUS_MIN_PX) {
                                    val particleN = TasteBirthFx.accretionParticles(seed)
                                    val pr = ((GlPalette.PROTOSTAR_TINT shr 16) and 0xFF) / 255f
                                    val pg = ((GlPalette.PROTOSTAR_TINT shr 8) and 0xFF) / 255f
                                    val pb = (GlPalette.PROTOSTAR_TINT and 0xFF) / 255f
                                    for (k in 0 until particleN) {
                                        val rad01 = TasteBirthFx.accretionInfallRadius01(t, seed, k)
                                        val ang = TasteBirthFx.accretionSpiralAngleRad(t, seed, k)
                                        val env = TasteBirthFx.accretionParticleAlpha01(rad01)
                                        addPoint(
                                            sx + cos(ang) * r * 2.1f * rad01,
                                            sy + sin(ang) * r * 2.1f * rad01,
                                            2.2f, 0.5f,
                                            pr, pg, pb, alpha * 0.35f * env,
                                        )
                                    }
                                }
                            }
                        }
                        // R281 星团真身（任务④）：成员星 3D 球分布（世界点云 GPU 投影——
                        // 绕飞团内真视差，非屏空间圆阵）；核心球仍走主刷三档
                        CelestialKind.CLUSTER -> buildClusterBall(sx, sy, r, alpha, seed, t, s.members, depth, focal, pWx[slot], pWy[slot], pWz[slot])
                        CelestialKind.METEOR -> {
                            val ang = galaxyH01(seed, 17) * 6.2831853f
                            val len = r * 3.2f + 6f
                            addLine(sx, sy, sx - cos(ang) * len, sy - sin(ang) * len, 0.81f, 0.88f, 0.95f, alpha * 0.40f)
                        }
                        // ---- R350 3D 遗迹环（中子星/夸克星——r341 §3.3-B；弧线族
                        //      双端退役第二刀）：addRingQuad 2D 环+8 段折线弧整族
                        //      退役 → **RingMesh mode=2 窄带**（带中心=REMNANT_
                        //      RING_R 1.9×核径±BAND_HALF 单源内插——无较差自转
                        //      streaks/COMPACT_TINT 色/测不写深度与致密球穿插/
                        //      慢进动 0.4°/s=RING_STRIDE 预留槽 15 启用）+**环面
                        //      纤维点串**（与超新星壳同一「球面/环面纤维点串」族
                        //      一类一件——面内径 remnantArcRCoef 缕间散布+离面波幅
                        //      =真 3D 管；面方位/进动与环带同族——环带/丝缕同面族）；
                        //      夸克星第二道内环=QUARK_INNER_RING_R 既有系数同 mode
                        //      （uRadius 换算 1.30/1.90——内环同比收窄=更致密读法）。
                        //      **致密核球=R349 形态线既有接线零改**（buildStarBody
                        //      低模 SPHERE_I 路径自动消费——蓝白 TEMP_COOL 核；世界
                        //      径帽 4.2 判据零碰）。量级零调红线：主环 a3×0.34/
                        //      内环 a3×0.30 原值直引 ----
                        CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR -> {
                            if (r >= lodSolidFloorPx) {
                                val coreWorldR = r * depth / focal
                                val tiltX = TasteTerminalFx.remnantRingTiltX(seed)
                                val tiltZ = TasteTerminalFx.remnantRingTiltZ(seed)
                                val precRad = Math.toRadians(TasteTerminalFx.remnantRingPrecessDeg(seed, t).toDouble()).toFloat()
                                val crT = ((GlPalette.COMPACT_TINT shr 16) and 0xFF) / 255f
                                val cgT = ((GlPalette.COMPACT_TINT shr 8) and 0xFF) / 255f
                                val cbT = (GlPalette.COMPACT_TINT and 0xFF) / 255f
                                val wxS = pWx[slot]
                                val wyS = pWy[slot]
                                val wzS = pWz[slot]
                                // 遗迹环主带（mode=2；光向域外传相机向占位——mode=2 不吃影）
                                addRing(
                                    wxS, wyS, wzS, coreWorldR, tiltX, tiltZ,
                                    cam.px - wxS, cam.py - wyS, cam.pz - wzS,
                                    seed.toFloat(), a3 * 0.34f, mode = 2f,
                                    cr = crT, cg = cgT, cb = cbT,
                                    precRad = precRad,
                                )
                                // 环面纤维点串（旧 8 段折线弧的 3D 升维——深度=不测
                                // 不写与壳同族叠加层语义；密度档分界=帧字段 R355）
                                val fil = TasteTerminalFx.remnantFilaments(seed)
                                val n = TasteTerminalFx.remnantFiberPoints(r, lodLowMeshMaxPx)
                                val fa = a3 * 0.34f
                                val sizeW = max(1.1f, r * 0.22f) * depth / focal
                                val cxT = cos(tiltX); val sxT = sin(tiltX)
                                val czT = cos(tiltZ); val szT = sin(tiltZ)
                                val cpY = cos(precRad); val spY = sin(precRad)
                                val majorR = coreWorldR * TasteTerminalFx.REMNANT_RING_R
                                val wpStart = wpCount
                                for (k in 0 until fil) {
                                    for (j in 0 until n) {
                                        val u = j.toFloat() / (n - 1)
                                        TasteTerminalFx.torusFiberPoint(seed, k, u, t, majorR, fiber3, 0)
                                        // 局部 → 世界（先 X 倾后 Z 倾再 Y 进动——与
                                        // RING_VS 三旋逐位同式=环带/丝缕同面族）
                                        val lx = fiber3[0]
                                        val ly0 = fiber3[1]
                                        val lz = fiber3[2]
                                        val y1 = ly0 * cxT - lz * sxT
                                        val z1 = ly0 * sxT + lz * cxT
                                        val x2 = lx * czT - y1 * szT
                                        val y2 = lx * szT + y1 * czT
                                        val xW = x2 * cpY + z1 * spY
                                        val zW = -x2 * spY + z1 * cpY
                                        addWPoint(
                                            wxS + xW, wyS + y2, wzS + zW,
                                            sizeW, 0.72f, crT, cgT, cbT,
                                            fa * TasteTerminalFx.remnantFiberErode01(seed, k, u),
                                        )
                                    }
                                }
                                flushWPoints(wpStart)
                                if (s.kind == CelestialKind.QUARK_STAR) {
                                    // 夸克星内致密环（第二道——中子星无此环；
                                    // QUARK_INNER_RING_R/REMNANT_RING_R 换算=
                                    // 带中心落 1.30×核径）
                                    addRing(
                                        wxS, wyS, wzS,
                                        coreWorldR * TasteTerminalFx.QUARK_INNER_RING_R / TasteTerminalFx.REMNANT_RING_R,
                                        tiltX, tiltZ,
                                        cam.px - wxS, cam.py - wyS, cam.pz - wzS,
                                        seed.toFloat(), a3 * 0.30f, mode = 2f,
                                        cr = crT, cg = cgT, cb = cbT,
                                        precRad = precRad,
                                    )
                                }
                            }
                        }
                        // ---- R317b 白洞双向喷流（固定哈希轴+缓呼吸——镜像 Canvas；
                        //      与脉冲星旋转扫掠束语义区分：白洞不扫掠恒外喷） ----
                        CelestialKind.WHITE_HOLE -> {
                            if (r >= 1.6f) {
                                val len = TasteTerminalFx.whiteholeJetLen(r).coerceIn(30f, 140f)
                                val breath = TasteTerminalFx.whiteholeJetBreath(t, seed)
                                val rot = TasteTerminalFx.whiteholeJetAngleDeg(seed)
                                val ja = breath * a3 * 110f / 255f
                                addQuad(TEX_BEAM, sx, sy, 5f, len / 2f, -len / 2f, rot, 1f, 1f, 1f, ja)
                                addQuad(TEX_BEAM, sx, sy, 5f, len / 2f, -len / 2f, rot + 180f, 1f, 1f, 1f, ja)
                            }
                        }
                        else -> {}
                    }
                }
            }

            // ---- R321 潮汐尾+星风流（镜像 Canvas——**主刷 when 块外**：尾是物质流
            //      非星体形变（黑洞对成员也撕尾——Canvas 同口径）；流=贴脸焦点件
            //      纯 t 函数；域外单比较零成本） ----
            if (si < tideDef.size && tideDef[si] > 0f) {
                emitTideTailR321(s, sx, sy, alpha, tideDef[si], tideAngRad[si], tideDpx[si], s.bodyColor, seed)
            }
            // R349 星风流门随全模线（旧 STAR_CORE_MIN_R=10 中间档线退役——
            // 贴脸焦点件语义=resolved 全模球；effLowMax 随回退序②同走=诚实降级）
            if (r >= lodLowMeshMaxPx && (s.chatId == f.selectedId || s.chatId == f.lockChatId) &&
                StarwindFx.isWindy(s.recent7, s.degree)
            ) {
                buildStarwindStreamR321(s, sx, sy, alpha, seed, t)
            }

            // ---- R323 自行矢量小尾巴（镜像 Canvas drawProperMotionTailR323——
            //      选中/聚焦星焦点件；drift=null（缺据/零漂移）零绘制零成本 ----
            if (s.drift != null && (s.chatId == f.selectedId || s.chatId == f.lockChatId)) {
                buildProperMotionTailR323(s, sx, sy, r, alpha)
            }

            // ---- R248 发现时刻特效（镜像 Canvas 清偿——r333 §2.3-4：双扩散环
            //      退役；GL 无位图光晕（POINT_FS 软边+bloom 承担——r333 §2.4），
            //      「点亮」盛放=既有 REVEAL alpha 脉冲（上方已乘）进 bloom 亮部
            //      提取的自然抬升；R328 点燃第三道环=一次性 300ms 瞬态保留） ----
            if (revealAtMs != 0L && f.nowMs > 0L) {
                val ageSec = (f.nowMs - revealAtMs).coerceAtLeast(0L) / 1000f
                if (ageSec < REVEAL_FX_SEC) {
                    // ---- R328 批C 点燃扩散环（镜像 Canvas——年轻星专属第三道白环：
                    //      核→壳一次性扩散；ignitionRingR01/Alpha01 单源，窗外恒 0
                    //      =addRingQuad alpha 域早退零成本） ----
                    if (youngBirthStarR328(s.kind, s.birth)) {
                        val ir01 = TasteBirthFx.ignitionRingR01(ageSec)
                        if (ir01 > 0f) {
                            addRingQuad(sx, sy, r * (1f + 1.1f * ir01), 0xFFFFFFFF.toInt(), TasteBirthFx.ignitionRingAlpha01(ageSec))
                        }
                    }
                }
            }
            // ---- 彗星新频道爆闪（镜像 Canvas 清偿——r333 §2.3-3：光环脉冲环删，
            //      L1 亮斑瞬时增亮（inten 包络已有）保留；GL 光刺=SPIKE 既有件照旧） ----
            if (!flashes.isEmpty()) {
                val ttl = runCatching { flashes[s.chatId] }.getOrNull() // GL 线程直读主线程账——竞态良性
                if (ttl != null && ttl > 0f) {
                    val inten = (ttl / 2.5f).coerceIn(0f, 1f)
                    val flashC = GlPalette.coreColorOf(temp)
                    addQuad(
                        TEX_DISC, sx, sy, r * (0.9f + 0.5f * inten), r * (0.9f + 0.5f * inten), 0f, 0f,
                        ((flashC shr 16) and 0xFF) / 255f, ((flashC shr 8) and 0xFF) / 255f, (flashC and 0xFF) / 255f,
                        inten * 0.62f,
                    )
                }
            }
            // ---- 创世纪诞生环（r333 §2.3-5 扩散环清偿——镜像 Canvas：诞生环退役，
            //      凝聚缓动（r×0.15→1.0 eased）+alpha 包络讲完「从尘埃聚成星」） ----
            // ---- R233 行星系统 ----
            if (revealDone && !genesis) {
                val entry = f.planetSys[s.chatId]
                if (entry != null && isChannelStarKind(s.kind)) {
                    buildPlanetSystem(f, s, entry, focal, t, w, h, cx, cy, cam, alpha, pWx[slot], pWy[slot], pWz[slot])
                }
            }
            // ---- R376 T1 徽章环退役（常驻灰圈正治）----
            //      旧件：tier==1 星 r≥3px 恒画 r×1.35 暖棕环（徽章色值×α×0.5——
            //      色值字面零保留，签名级绝迹锚可 grep）——用户小米截图像素实锚
            //      （环@1.35r、RGB(97,89,69)=徽章色×有效α0.45、
            //      方位 std 8.3=真环）+四代否决史（R256 大圆盘/R267b 外悬圈/R333 灰环/
            //      R339 宽晕）后的第五代同族否决：「乍一看像光晕，真实宇宙不该有灰色
            //      半透明圆圈」。R333/R339 白名单「静态身份环=标记不是光」未经用户对
            //      灰读法批准——常驻低饱和环=读灰圈非读徽章。tier1 身份由体量主轴
            //      （星径/亮度∝频道体量）+名签承担，标记职能不依赖环。
            // ---- 灯塔光柱（在播频道双光束缓慢扫掠——R348 C2 降速 66→10°/s：
            //      纹理自下而上（远→星）衰减；恒亮+呼吸案弃选（双柱方向感本身在讲
            //      「在播=哪里」，恒了=静态装饰环邻域）——宪法二章 14 条；
            //      画家序：在选中/锁定环之前） ----
            if (isPlaying) {
                val len = (r * 7f + 90f).coerceIn(90f, 260f)
                val sweep = (t * BEACON_SWEEP_DEG_S) % 360f
                addQuad(TEX_BEAM, sx, sy, 15f, len / 2f, -len / 2f, sweep, 1f, 1f, 1f, 0.31f)
                addQuad(TEX_BEAM, sx, sy, 15f, len / 2f, -len / 2f, sweep + 180f, 1f, 1f, 1f, 0.31f)
            }
            // ---- 焦点邻居细环（R280 补——neighborIds 由 buildEdges 焦点遍填充） / 选中环 / 锁定环 ----
            if (f.selectedId != 0L && s.chatId != f.selectedId && s.chatId in neighborIds) {
                addRingQuad(sx, sy, r + 5f, 0xCCFFFFFF.toInt(), alpha * 200f / 255f)
            }
            if (s.chatId == f.selectedId && f.selectedId != 0L) {
                if (known && s.title.isNotBlank()) selLabel = SelectedLabel(sx, sy, r, s.title)
                addRingQuad(sx, sy, r * 1.6f + 5f, 0xFFFFFFFF.toInt(), 0.80f)
            }
            if (s.chatId == f.lockChatId && f.lockChatId != 0L && s.chatId != f.selectedId) {
                addRingQuad(sx, sy, r * 1.9f + 7f, 0xFFFFFFFF.toInt(), 0.75f)
                addRingQuad(sx, sy, r * 1.45f + 5f, 0xFFFFFFFF.toInt(), 0.47f)
            }
            // ---- 名签候选（R280 打字机真身：发现时刻 1.2s 内逐字亮出——装配器内
            //      截字（缓出同 Canvas 式），叠加层零改动） ----
            if (known && r >= f.labelMinPx && s.title.isNotBlank()) {
                var labelText = s.title
                var labelAlpha = alpha
                if (revealAtMs != 0L && f.nowMs > 0L) {
                    val ageMs = (f.nowMs - revealAtMs).coerceAtLeast(0L).toFloat()
                    if (ageMs < REVEAL_NAME_MS) {
                        val pr = ageMs / REVEAL_NAME_MS
                        val eased = 1f - (1f - pr) * (1f - pr) * (1f - pr)
                        val n = (eased * s.title.length).toInt().coerceIn(1, s.title.length)
                        labelText = s.title.substring(0, n)
                        labelAlpha = (alpha * 0.65f + 0.35f * eased).coerceAtMost(1f)
                    }
                }
                labelCands.add(GlLabelCand(sx, sy, r, labelText, (labelAlpha.coerceIn(0f, 1f) * 255f).toInt()))
            }
        }
        if (labelCands.size > LABEL_CAP) {
            labelCands.sortByDescending { it.r }
            while (labelCands.size > LABEL_CAP) labelCands.removeAt(labelCands.size - 1)
        }

        // ---- R248 探索暗示（屏缘 chevron——镜像 Canvas：最近未知星在屏外/背后时，
        //      屏缘微弱脉冲箭头指向它；屏内不画=未知星自身呼吸脉冲即引导） ----
        buildHintChevron(stars, hintSi, cam, focal, w, h, cx, cy, t, f.hintBoost)

        // ---- R321 拉格朗日特洛伊簇（星层收口——焦点双星对的 L4/L5 微亮点群；
        //      无焦点/无共源零成本早退）+探针节拍（R376 星风泡退役：泡计数/泡探针
        //      行随绘制本体一并退役——零在场零记账零日志噪音） ----
        buildTrojanClustersR321(f, cam, focal, w, h, cx, cy)
        statTickR321++
        // R339 星芒门探针（V38-⑤ 票面口径『R339 星芒门: 候选=.. 帽=5 入选=..』——
        // ~6s 一条亮度 top 门诚实记账；候选=门域个体星数，入选=top 截断后发芒数）
        statTickR339++
        if (statTickR339 % 360 == 0) {
            android.util.Log.i(
                "TasteMapX",
                "R339 星芒门: 候选=${statSpikeCand} 帽=${TasteStarGlow.SPIKE_MAX_STARS} 入选=${statSpikeStars}",
            )
        }
        // ---- R349 statLodLine 探针（r341 §4.1 口径——纯计数零内容（脱敏红线
        //      天然达标）+回退序①②③记账（bias/帽当前值——V51-③ 断言原料）；
        //      R355 密度+px 阈值行（两密度档 3D 出现一致性断言原料——V55：同视觉
        //      缩放级别下 芯=点档上限/低模线/球门 三 px 阈值×密度与计数族互证）；
        //      ~6s 一条（statTickR339 同族节拍）；
        //      R388 刀1/刀2 校准腿计数行（复合体 quad 半径/隐没因子/丝缕标度
        //      ——带与 REF 实测判读原料，纯计数脱敏） ----
        statTickR349++
        if (statTickR349 % 360 == 0) {
            android.util.Log.i(
                "TasteMapX",
                "R349 LOD: 可见星=${statVisibleStars} 微点=${statMicroStars} 低模球=${statSphereLow} 全模球=${statSphereFull} blend对=${statBlendPairs} 行星=${statPlanets} 微点行星=${statPlanetDots} 岩石=${statRocks} 截断=${statLodDropped} bias=${lodSolidBias} mesh=${if (lodMeshBias > 100f) "全低模" else "常态"} 帽=${lodInstanceCap} 密度=${lodDensity} 阈px=${lodPointCeilPx}/${lodSolidFloorPx}/${lodLowMeshMaxPx} R388复合体rPx=${statCpxRpx} 隐没=${statCpxFarHide} 丝缕标度=${statCpxFilScale}",
            )
        }
    }

    /**
     * R339 星芒 top 门预扫（帧首 pass1 后：候选域=R349 形态线**点档侧**
     * [lodPointCeilPx]≤r<effectiveSolidFloor（R355 dp 化=帧字段两端；微星并点层
     * 不拆云+**resolved 天体无芒**（r341 §5.2 物理口径）——球档/岩石=非点源不
     * 参选；blend 带内星芒随 pointFadeK 让位）+黑洞（暗体无衍射）域外（流星岩
     * 石≥球门已被上界排除）；亮度=α×r（[collectFlare] 亮星口径同式）降序
     * [TasteStarGlow.SPIKE_MAX_STARS] 插入排序截断——帧零分配（flare 族姿势）；
     * 入选星写 [spikeB01]=分数/榜首（top1=1 递减——[TasteStarGlow.spikeLenPx]
     * 芒长∝亮度的归一原料）。pass1 的 pA/pR 即排序原料（twinkle ±8%/reveal
     * 渐显不改名次量级——预扫近似诚实）。
     */
    private fun selectSpikesR339(stars: List<dev.tgfeed.ui.TasteFxStar>, visN: Int) {
        spikeCount = 0
        statSpikeCand = 0
        if (spikeB01.size < stars.size) {
            spikeB01 = FloatArray(stars.size) // 增长重建（新建零填充）
        } else {
            spikeB01.fill(0f)
        }
        for (vi in 0 until visN) {
            val slot = order[vi]
            val si = slotStar[slot]
            val s = stars[si]
            val rPx = pR[slot]
            if (rPx < lodPointCeilPx) continue
            if (rPx >= lodSolidFloorPx) continue
            if (s.kind == CelestialKind.BLACK_HOLE) continue
            statSpikeCand++
            val score = pA[slot] * rPx
            if (spikeCount >= TasteStarGlow.SPIKE_MAX_STARS && score <= spikeScore[spikeCount - 1]) {
                continue
            }
            var k = if (spikeCount < TasteStarGlow.SPIKE_MAX_STARS) spikeCount else TasteStarGlow.SPIKE_MAX_STARS - 1
            if (spikeCount < TasteStarGlow.SPIKE_MAX_STARS) spikeCount++
            while (k > 0 && spikeScore[k - 1] < score) {
                spikeScore[k] = spikeScore[k - 1]
                spikeStarIdx[k] = spikeStarIdx[k - 1]
                k--
            }
            spikeScore[k] = score
            spikeStarIdx[k] = si
        }
        statSpikeStars = spikeCount
        if (spikeCount > 0 && spikeScore[0] > 0f) {
            val top = spikeScore[0]
            for (k in 0 until spikeCount) {
                spikeB01[spikeStarIdx[k]] = (spikeScore[k] / top).coerceIn(0f, 1f)
            }
        }
    }

    /** R339 星芒亮度01 取值（星索引→top 门归一亮度；域外/未选=0 零芒） */
    private fun spikeB01Of(si: Int): Float = if (si in spikeB01.indices) spikeB01[si] else 0f

    /**
     * R349 低模球籍预选（帧首 pass1 后——r341 §4.2 超帽策略「**最小屏径优先出
     * 球籍回点档**」）：候选域=r∈[lodPointCeilPx, effectiveLowMeshMax)（R355
     * dp 化帧字段两端；blend 带球+solid 低模球双源同池竞争），**屏径
     * top-[lodInstanceCap] 插入排序**（flare/spike 族同姿势帧零分配）——最小者
     * 无籍=回点档（α×0.92 全亮点——与球 α 本就能量配平，帽界换籍帧间跳变 ≤8%
     * 预算内）+statLodDropped 记账。预选=pass1 近似口径（pass2 的 genesis 半径
     * 坡道/reveal 批次早退不在此账——selectSpikes 同族诚实近似，实产籍空置无害）。
     */
    private fun selectLowSpheresR349(stars: List<dev.tgfeed.ui.TasteFxStar>, visN: Int) {
        if (lowSphereOk.size < stars.size) {
            lowSphereOk = BooleanArray(stars.size)
        } else {
            java.util.Arrays.fill(lowSphereOk, 0, stars.size, false)
        }
        val cap = lodInstanceCap.coerceAtMost(MAX_SPHERE_I)
        lowSelN = 0
        for (vi in 0 until visN) {
            val slot = order[vi]
            val rPx = pR[slot]
            if (rPx < lodPointCeilPx) continue
            if (rPx >= lodLowMeshMaxPx) continue
            val si = slotStar[slot]
            if (lowSelN >= cap && rPx <= lowSelScore[cap - 1]) continue
            var k = if (lowSelN < cap) lowSelN else cap - 1
            if (lowSelN < cap) lowSelN++
            while (k > 0 && lowSelScore[k - 1] < rPx) {
                lowSelScore[k] = lowSelScore[k - 1]
                lowSelIdx[k] = lowSelIdx[k - 1]
                k--
            }
            lowSelScore[k] = rPx
            lowSelIdx[k] = si
        }
        for (k in 0 until lowSelN) lowSphereOk[lowSelIdx[k]] = true
    }

    /**
     * R320 凌星深度预扫（镜像 Canvas scanTransitDepthR320——pass1 后/pass2 前：
     * 恒星屏心+视半径+行星开普勒位置全既有，逐行星-恒星屏距判
     * [EclipseFx.transitDepth01]；倾角反例=有的系统永远零深度（不造食）。
     * 与 buildPlanetSystem 同门同闸（井深比/吞没早退/浮现序/半径下限）。
     */
    private fun scanTransitDepthR320(
        f: TasteFlightFrame,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        t: Float,
        usePhys: Boolean,
        grav: dev.tgfeed.ui.TasteGalaxyPhysics?,
    ) {
        val sys = f.planetSys
        if (sys.isEmpty()) return
        val stars = f.stars
        val rX = grav?.renderX
        val rY = grav?.renderY
        val rZ = grav?.renderZ
        val starPxMax = minOf(w, h) * TasteSpace.SWALLOW_MAX_R_FRAC
        val orb = mpos // 轨道点暂存（第二投影缓冲——buildPlanetSystem 同款复用）
        for (si in stars.indices) {
            val s = stars[si]
            val entry = sys[s.chatId] ?: continue
            val scx = if (usePhys) rX!![si] else s.x
            val scy = if (usePhys) rY!![si] else s.y
            val scz = if (usePhys) rZ!![si] else s.z
            val ratio = galaxyWellRatio(scx, scy, scz, s.vol, f.wellMaxVol, cam)
            if (ratio >= TasteTagPlanets.APPEAR_RATIO) continue // 行星未浮现档=零凌星判定
            val distStar = TasteSpace.distTo(cam, scx, scy, scz)
            if (distStar <= TasteSpace.NEAR) continue
            val sizeK = focal / distStar
            val starPxRaw = s.bodyR * sizeK
            if (starPxRaw > starPxMax * 3f) continue // 深吞没早退（buildPlanetSystem 同款）
            val starPx = minOf(starPxRaw, starPxMax)
            if (!TasteSpace.project(scx, scy, scz, cam, focal, cx, cy, proj, basisArr)) continue
            val starSx = proj[0]
            val starSy = proj[1]
            // R340 恒星相机系深度（凌星前置判定原料——proj[2] 在行星投影处会被覆写，先存）
            val starDepthR340 = proj[2]
            val planets = entry.system.planets
            val maxShow = min(planets.size, TasteTagPlanets.LOD_NEAR_PLANETS)
            if (maxShow <= 0) continue
            val inc = galaxyOrbitInclination((s.chatId % 100_000L).toInt())
            var depth = 0f
            // R328 成型档凌星门同闸（**与 buildPlanetSystem 同门**——绘制坡道放缓后
            // 判定域随坡道同门：成型态行星慢浮现=慢进凌星判定）
            val rampW = if (s.birth?.planetsForming == true) {
                TasteBirthFx.formingRampWidth(TasteTagPlanets.APPEAR_RAMP)
            } else {
                TasteTagPlanets.APPEAR_RAMP
            }
            for (i in 0 until maxShow) {
                val p = planets[i]
                val a = TasteTagPlanets.appearAlpha(ratio, i, maxShow, rampW)
                if (a <= 0f) break // 浮现序门与绘制同闸（阈值随序单调）
                val rW = planetOrbitRadius(p, s.bodyR)
                val peri = TasteTagPlanets.keplerPeriapsis(p)
                val nu = TasteTagPlanets.keplerTheta(p, t)
                val rOrb = TasteTagPlanets.keplerOrbitRadius(p, rW, nu)
                planetOrbitPoint(scx, scy, scz, rOrb, nu + peri, inc, orb)
                if (!TasteSpace.project(orb[0], orb[1], orb[2], cam, focal, cx, cy, proj, basisArr)) continue
                val rP = planetWorldRadius(p, s.bodyR, rW) * proj[3]
                if (rP < 1.5f) continue // 绘制同门（画不出的行星不判）
                val dTr = hypot(proj[0] - starSx, proj[1] - starSy)
                // R340 凌星前置深度分量（黑圈回归根治——掩星行星不遮恒星：食深只吃真前遮挡）
                val d = EclipseFx.transitDepthFront01(rP, starPx, dTr, proj[2], starDepthR340)
                if (d > depth) depth = d
            }
            if (depth > 0f) pEcl[si] = depth
        }
    }

    /** R248 屏缘探索暗示（镜像 Canvas drawStars 尾段：chevron 双线+信号小环）；
     *  R302 [hintBoost]=罗盘档（Lv2 升级差≤20% 提亮+加粗——与 Canvas 同口径） */
    private fun buildHintChevron(
        stars: List<dev.tgfeed.ui.TasteFxStar>, hintSi: Int,
        cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float, t: Float,
        hintBoost: Boolean,
    ) {
        if (hintSi < 0) return
        val s = stars[hintSi]
        val b = basisArr
        val dx = s.x - cam.px
        val dy = s.y - cam.py
        val dz = s.z - cam.pz
        val camDepth = dx * b[0] + dy * b[1] + dz * b[2]
        var rx = dx * b[3] + dy * b[4] + dz * b[5]
        var uy = dx * b[6] + dy * b[7] + dz * b[8]
        val onScreen = camDepth > TasteSpace.NEAR && run {
            val k = focal / camDepth
            val px = cx + rx * k
            val py = cy - uy * k
            px > -0.2f * w && px < 1.2f * w && py > -0.2f * h && py < 1.2f * h
        }
        if (onScreen) return
        if (camDepth <= TasteSpace.NEAR) { rx = -rx; uy = -uy } // 背后：镜像=转身去找
        var dirX = rx
        var dirY = -uy
        val len = max(sqrt(dirX * dirX + dirY * dirY), 1e-4f)
        dirX /= len
        dirY /= len
        val mx = w / 2f - 34f
        val my = h / 2f - 34f
        val scale = min(mx / max(kotlin.math.abs(dirX), 1e-4f), my / max(kotlin.math.abs(dirY), 1e-4f))
        val ex = cx + dirX * scale
        val ey = cy + dirY * scale
        val pulse = if (hintBoost) {
            0.50f + 0.30f * (0.5f + 0.5f * sin(t * 2.1f))
        } else {
            0.16f + 0.14f * (0.5f + 0.5f * sin(t * 2.1f))
        }
        val hr = ((HINT_COLOR shr 16) and 0xFF) / 255f
        val hg = ((HINT_COLOR shr 8) and 0xFF) / 255f
        val hb = (HINT_COLOR and 0xFF) / 255f
        addRingQuad(ex, ey, 3.2f, HINT_COLOR, pulse)
        val ang = atan2(dirY, dirX)
        val tip = 11f
        val back = 5f
        val wing = 6.5f
        val c = cos(ang)
        val sn = sin(ang)
        val cwx = cos(ang + 1.5708f)
        val cwy = sin(ang + 1.5708f)
        addLine(ex - c * back + cwx * wing, ey - sn * back + cwy * wing, ex + c * tip, ey + sn * tip, hr, hg, hb, pulse)
        addLine(ex - c * back - cwx * wing, ey - sn * back - cwy * wing, ex + c * tip, ey + sn * tip, hr, hg, hb, pulse)
    }

    // ==================== R280 P1 屏空间特效层（飞船航迹/流星/彗星/爆散/虫洞） ====================

    /** R235 飞船航迹（流场 streak+引擎脉冲+拖尾粒子+跃迁波纹+护盾四弧；
     *  R348 C1 拖尾粒子单色化——彩虹档并入单色星尘桶） */
    private fun buildShipTrail(f: TasteFlightFrame, w: Float, h: Float, cx: Float, cy: Float, t: Float) {
        val engine = f.ship ?: return
        val style = f.shipStyle
        val speed01 = engine.speed01
        val baseAlpha = TasteShipFx.alphaOf(style, speed01)
        val color = TasteAchievements.shipColorOf(style)
        val cr = ((color shr 16) and 0xFF) / 255f
        val cg = ((color shr 8) and 0xFF) / 255f
        val cb = (color and 0xFF) / 255f
        val minSide = min(w, h)
        val radius = minSide * 0.5f
        // 流场 streak（全档——黄金角环布 18 点）
        val streakMax = TasteShipFx.streakLenOf(style, speed01, minSide)
        if (baseAlpha > 0f && streakMax > 1f) {
            val vU = engine.flowVelU
            val vV = engine.flowVelV
            val rate = engine.flowRateS
            for (i in 0 until SHIP_STREAK_N) {
                val ang = i * 2.39996f
                val rad = radius * (0.22f + 0.68f * sqrt((i + 0.5f) / SHIP_STREAK_N))
                val ppx = cx + cos(ang) * rad
                val ppy = cy + sin(ang) * rad
                val lx = vU + (ppx - cx) * rate
                val ly = vV + (ppy - cy) * rate
                val spd = hypot(lx, ly)
                if (spd < 40f) continue
                val sLen = (spd * 0.05f).coerceIn(4f, streakMax)
                val ux = lx / spd
                val uy = ly / spd
                val flick = 0.70f + 0.30f * sin(t * 13f + i * 2.399f)
                addLine(
                    ppx - ux * sLen * 0.5f, ppy - uy * sLen * 0.5f,
                    ppx + ux * sLen * 0.5f, ppy + uy * sLen * 0.5f,
                    cr, cg, cb, baseAlpha * flick,
                )
            }
            // 引擎脉冲（白银+：屏心脉动晕+芯）
            if (style.ordinal >= TasteAchievements.ShipStyle.BLUE_PULSE.ordinal) {
                val pulse = 0.72f + 0.28f * sin(t * 4.2f)
                val scaleR = (radius / 540f).coerceIn(0.6f, 1.6f)
                addQuad(TEX_FOG, cx, cy, (26f + 12f * pulse) * scaleR, (26f + 12f * pulse) * scaleR, 0f, 0f, cr, cg, cb, baseAlpha * 110f / 255f * pulse)
                addQuad(TEX_DISC, cx, cy, 3.6f * scaleR, 3.6f * scaleR, 0f, 0f, cr, cg, cb, baseAlpha * 170f / 255f)
            }
        }
        // 拖尾粒子（黄金+传奇=单色星尘桶——R348 C1 彩虹退役：传奇档色相随 t 旋转的
        //      8 桶彩虹（幼儿园视觉母题——宪法二章 12 条「宇宙里没有随时间变彩虹色
        //      的粒子」）整支撤除，与黄金档收口单一循环；色=shipColorOf 星尘暖白
        //      （光的颜色由温度与消光决定，不由成就等级决定）；等阶语义由粒子密度/
        //      尾长档差承担（streakCap 1.0/alpha 0.34 既有档差原样）；帧预算反减
        //      一次/粒/帧的 HSV→RGB 换算）
        val pCount = engine.pCount
        if (pCount > 0 && TasteShipFx.hasParticlesOf(style)) {
            for (i in 0 until pCount) {
                if (engine.pLife[i] <= 0f) continue
                val ppx = cx + engine.pDx[i]
                val ppy = cy + engine.pDy[i]
                if (ppx < -40f || ppx > w + 40f || ppy < -40f || ppy > h + 40f) continue
                addPoint(ppx, ppy, 2.6f, 0.72f, cr, cg, cb, baseAlpha * 235f / 255f)
            }
        }
        // 跃迁波纹（钻石+：扩散环）
        if (engine.rCount > 0 && TasteShipFx.hasRipplesOf(style)) {
            for (i in 0 until engine.rCount) {
                val q = (engine.rAge[i] / TasteShipFx.RIPPLE_LIFE).coerceIn(0f, 1f)
                addRingQuad(cx, cy, radius * (0.20f + 0.85f * q), color, (1f - q) * (1f - q) * 150f / 255f)
            }
        }
        // 护盾光环（钻石+：屏角四段弧——椭圆弧 10 段折线近似）
        if (style.ordinal >= TasteAchievements.ShipStyle.WARP_RIPPLE.ordinal) {
            val shieldA = (44f + 20f * sin(t * 2.1f)) / 255f
            val rx2 = w / 2f - 12f
            val ry2 = h / 2f - 12f
            val starts = floatArrayOf(-65f, 25f, 115f, 205f)
            for (arc in starts) {
                var lx = 0f
                var ly = 0f
                var started = false
                for (seg in 0..10) {
                    val ad = Math.toRadians((arc + 40f * seg / 10f).toDouble()).toFloat()
                    // Canvas drawArc 屏系 y 向下角度顺时——同式（cos 横/sin 纵取屏系直绘）
                    val ax2 = cx + cos(ad) * rx2
                    val ay2 = cy + sin(ad) * ry2
                    if (started) addLine(lx, ly, ax2, ay2, cr, cg, cb, shieldA)
                    lx = ax2
                    ly = ay2
                    started = true
                }
            }
        }
    }

    /** 今晚星轨流星（镜像 drawMeteor：空间尾迹环旧→新渐亮+头点微晕）。
     *  R314：并入「星座」门径——开关关=流星不重播（Canvas 侧同门径） */
    private fun buildMeteor(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        if (!f.showConstellation) return
        val e = f.engine
        if (!e.meteorAlive) return
        val lr = ((TRAIL_COLOR shr 16) and 0xFF) / 255f
        val lg = ((TRAIL_COLOR shr 8) and 0xFF) / 255f
        val lb = (TRAIL_COLOR and 0xFF) / 255f
        val mr = ((METEOR_COLOR shr 16) and 0xFF) / 255f
        val mg = ((METEOR_COLOR shr 8) and 0xFF) / 255f
        val mb = (METEOR_COLOR and 0xFF) / 255f
        if (e.trailSize >= 2) {
            val oldest = (e.trailHead - e.trailSize + e.trailCap) % e.trailCap
            var px = 0f
            var py = 0f
            var has = false
            for (i in 0 until e.trailSize) {
                val idx = (oldest + i) % e.trailCap
                if (!TasteSpace.ahead(e.trailX[idx], e.trailY[idx], e.trailZ[idx], cam, basisArr)) {
                    has = false
                    continue
                }
                TasteSpace.project(e.trailX[idx], e.trailY[idx], e.trailZ[idx], cam, focal, cx, cy, proj, basisArr)
                if (has && proj[0] > -60f && proj[0] < w + 60f && proj[1] > -60f && proj[1] < h + 60f) {
                    addLine(px, py, proj[0], proj[1], lr, lg, lb, (i.toFloat() / e.trailSize) * 0.55f)
                }
                px = proj[0]
                py = proj[1]
                has = true
            }
        }
        e.meteorPos(mpos)
        if (TasteSpace.ahead(mpos[0], mpos[1], mpos[2], cam, basisArr)) {
            TasteSpace.project(mpos[0], mpos[1], mpos[2], cam, focal, cx, cy, proj, basisArr)
            if (proj[0] > -40f && proj[0] < w + 40f && proj[1] > -40f && proj[1] < h + 40f) {
                val pulse = 0.75f + 0.25f * sin(e.timeSec * 5f)
                addQuad(TEX_FOG, proj[0], proj[1], 7f * pulse, 7f * pulse, 0f, 0f, mr, mg, mb, 60f / 255f)
                addPoint(proj[0], proj[1], 5.2f, 0.80f, mr, mg, mb, 235f / 255f)
                // R281 流星头=岩石真身（烧蚀热缘翻滚块——尾迹世界系本已 3D）
                val mSeed = (mpos[0] * 31 + mpos[2] * 17).toInt()
                val mdx = cam.px - mpos[0]; val mdy = cam.py - mpos[1]; val mdz = cam.pz - mpos[2]
                val mdl = sqrt(mdx * mdx + mdy * mdy + mdz * mdz).coerceAtLeast(1e-6f)
                val rockStart = rockCount
                addRock(
                    mpos[0], mpos[1], mpos[2], 1.3f,
                    spin = e.timeSec * 4.2f, axisX = 0.3f, axisY = 0.9f, axisZ = 0.2f,
                    cr = 0.95f, cg = 0.88f, cb = 0.78f, alpha = 1f,
                    lx = mdx / mdl, ly = mdy / mdl, lz = mdz / mdl,
                    ember = 1.5f, seedRock = mSeed.toFloat(),
                )
                flushRocks(rockStart)
            }
        }
    }

    /**
     * 彗星（**R281 真身**——任务④）：屏轨迹**反投影入世界**（screenRay×[COMET_DEPTH]）
     * =真视差几何：彗核=哈希多面体岩石（翻滚+烧蚀热缘）+彗发雾晕+**世界系三角带
     * 双尾**（离子尾直而窄=蓝白；尘埃尾宽而散=暖白离轴飘——fbm 侵蚀 shader）。
     * 旧折线尾+屏空间尘埃点整体退役。
     */
    private fun buildComet(f: TasteFlightFrame, cam: TasteSpace.SpaceCam, focal: Float, w: Float, h: Float, cx: Float, cy: Float) {
        val e = f.engine
        if (e.cometLife <= 0f) return
        val cr = ((COMET_COLOR shr 16) and 0xFF) / 255f
        val cg = ((COMET_COLOR shr 8) and 0xFF) / 255f
        val cb = (COMET_COLOR and 0xFF) / 255f
        val t = e.timeSec
        val fade = (e.cometLife / 0.6f).coerceIn(0f, 1f)
        val headSx = e.cometX * w
        val headSy = e.cometY * h
        val cometSeed = (e.cometX * 7919 + e.cometY * 104729).toInt()
        // ---- 彗核世界位（屏位反投影——轨迹语义不变，几何世界化） ----
        TasteSpace.screenRay(headSx, headSy, cam, focal, cx, cy, rayScratch)
        val hwx = cam.px + rayScratch[0] * COMET_DEPTH
        val hwy = cam.py + rayScratch[1] * COMET_DEPTH
        val hwz = cam.pz + rayScratch[2] * COMET_DEPTH
        // 彗核=岩石多面体（翻滚+烧蚀热缘；光向=头灯式——闯入者无宿主恒星语境）
        val rockStart = rockCount
        addRock(
            hwx, hwy, hwz, 2.6f,
            spin = t * 3.1f + galaxyH01(cometSeed, 3) * 6.2831853f,
            axisX = galaxyH01(cometSeed, 5) - 0.5f, axisY = galaxyH01(cometSeed, 7) - 0.5f, axisZ = galaxyH01(cometSeed, 11) - 0.5f,
            cr = 0.92f, cg = 0.90f, cb = 0.86f,
            alpha = fade,
            lx = -rayScratch[0], ly = -rayScratch[1], lz = -rayScratch[2],
            ember = 1.4f, seedRock = cometSeed.toFloat(),
        )
        flushRocks(rockStart)
        // ---- 世界系三角带双尾（带心=轨迹反投影点串；横向=相机朝向圆柱公告板） ----
        if (e.cometTrailSize >= 2) {
            val n = min(e.cometTrailSize, e.trailCap).coerceAtMost(94)
            val oldest = (e.cometTrailSize - n) % e.trailCap
            // 节 0=彗核头
            stripCenter[0] = hwx; stripCenter[1] = hwy; stripCenter[2] = hwz
            var m = 1
            for (i in 0 until n - 1) {
                val idx = (oldest + (n - 1 - i) + e.trailCap) % e.trailCap // 新→旧
                TasteSpace.screenRay(e.cometTrailX[idx] * w, e.cometTrailY[idx] * h, cam, focal, cx, cy, rayScratch)
                stripCenter[m * 3] = cam.px + rayScratch[0] * COMET_DEPTH
                stripCenter[m * 3 + 1] = cam.py + rayScratch[1] * COMET_DEPTH
                stripCenter[m * 3 + 2] = cam.pz + rayScratch[2] * COMET_DEPTH
                m++
            }
            // 尾梢外延（离子尾冲出轨迹史 30%）
            val tailDx = stripCenter[(m - 1) * 3] - stripCenter[(m - 2) * 3]
            val tailDy = stripCenter[(m - 1) * 3 + 1] - stripCenter[(m - 2) * 3 + 1]
            val tailDz = stripCenter[(m - 1) * 3 + 2] - stripCenter[(m - 2) * 3 + 2]
            stripCenter[m * 3] = stripCenter[(m - 1) * 3] + tailDx * 1.6f
            stripCenter[m * 3 + 1] = stripCenter[(m - 1) * 3 + 1] + tailDy * 1.6f
            stripCenter[m * 3 + 2] = stripCenter[(m - 1) * 3 + 2] + tailDz * 1.6f
            m++
            // 横向向量（垂直轴×视线=圆柱公告板）
            for (i in 0 until m) {
                val px0 = stripCenter[max(0, i - 1) * 3]
                val py0 = stripCenter[max(0, i - 1) * 3 + 1]
                val pz0 = stripCenter[max(0, i - 1) * 3 + 2]
                val px1 = stripCenter[min(m - 1, i + 1) * 3]
                val py1 = stripCenter[min(m - 1, i + 1) * 3 + 1]
                val pz1 = stripCenter[min(m - 1, i + 1) * 3 + 2]
                val ax = px1 - px0; val ay = py1 - py0; val az = pz1 - pz0
                val vx = stripCenter[i * 3] - cam.px
                val vy = stripCenter[i * 3 + 1] - cam.py
                val vz = stripCenter[i * 3 + 2] - cam.pz
                var ox = ay * vz - az * vy
                var oy = az * vx - ax * vz
                var oz = ax * vy - ay * vx
                val ol = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(1e-6f)
                ox /= ol; oy /= ol; oz /= ol
                stripAcross[i * 3] = ox; stripAcross[i * 3 + 1] = oy; stripAcross[i * 3 + 2] = oz
            }
            // 离子尾（窄直蓝白）+ 尘埃尾（宽散暖白——离轴渐飘）
            val ionA = 0.50f * fade
            val dustTailA = 0.30f * fade
            for (layer in 0..1) {
                val dustLayer = layer == 1
                val lr2 = if (dustLayer) 1f else 0.72f
                val lg2 = if (dustLayer) 0.88f else 0.83f
                val lb2 = if (dustLayer) 0.72f else 1f
                val la = if (dustLayer) dustTailA else ionA
                val start = stripVertCount
                for (i in 0 until m) {
                    val u = i.toFloat() / (m - 1)
                    var width = (1.6f + i * 1.05f) * (if (dustLayer) 2.1f else 1f)
                    var cxs = stripCenter[i * 3]
                    var cys = stripCenter[i * 3 + 1]
                    var czs = stripCenter[i * 3 + 2]
                    if (dustLayer) {
                        // 尘埃尾离轴漂移（横向平方散开=扇形尾）
                        val drift = i * i * 0.10f
                        cxs += stripAcross[i * 3] * drift
                        cys += stripAcross[i * 3 + 1] * drift
                        czs += stripAcross[i * 3 + 2] * drift
                    }
                    val ox = stripAcross[i * 3]; val oy = stripAcross[i * 3 + 1]; val oz = stripAcross[i * 3 + 2]
                    addStripVert(cxs - ox * width, cys - oy * width, czs - oz * width, u, -1f, lr2, lg2, lb2, la)
                    addStripVert(cxs + ox * width, cys + oy * width, czs + oz * width, u, 1f, lr2, lg2, lb2, la)
                }
                if (stripVertCount > start) emitImmediate(OP_STRIP, start, stripVertCount - start, 0)
            }
        }
        // 彗发（雾晕+亮核——屏空间近景件保留）
        addQuad(TEX_FOG, headSx, headSy, 12f, 12f, 0f, 0f, cr, cg, cb, 70f / 255f * fade)
        addPoint(headSx, headSy, 6.4f, 0.80f, cr, cg, cb, 245f / 255f * fade)
    }

    /** 点星爆散（镜像 drawBursts：生命周期透明度/半径收缩） */
    private fun buildBursts(f: TasteFlightFrame) {
        val e = f.engine
        for (i in 0 until e.burstCount) {
            val lifeFrac = (e.blife[i] / e.bmax[i]).coerceIn(0f, 1f)
            addPoint(
                e.bx[i], e.by[i], e.bsize[i] * (0.35f + 0.65f * lifeFrac) * 2f, 0.72f,
                1f, 1f, 1f, lifeFrac * 220f / 255f,
            )
        }
    }

    /** R235 虫洞隧道（R348 C3：24 静态径向条纹退役——贴面「超时空隧道」trope 装饰；
     *  保留暗幕压底（对比底）+双隧道环（定径框架），穿越真运动在相机
     *  （TasteWormhole.camPathSpace 前冲鼓包）+到达爆散白闪瞬态（TasteMapScreen 到达口） */
    private fun buildWormhole(f: TasteFlightFrame, w: Float, h: Float, cx: Float, cy: Float) {
        val p = f.wormholeP01
        if (p < 0f || p > 1f) return
        val inten = TasteWormhole.tunnelIntensity(p)
        if (inten <= 0.02f) return
        val radius = min(w, h) * 0.5f
        // 暗幕（压暗星域=拉出扭曲的对比底）
        addQuad(TEX_WHITE, cx, cy, w / 2f, h / 2f, 0f, 0f, 0x06 / 255f, 0x09 / 255f, 0x10 / 255f, inten * 64f / 255f)
        // R348 C3：24 径向条纹绘制退役（角度哈希静态、不参与宇宙语义——真前冲在相机）
        addRingQuad(cx, cy, radius * 0.42f, WORM_COLOR, inten * 96f / 255f)
        addRingQuad(cx, cy, radius * 0.64f, WORM_COLOR, inten * 54f / 255f)
    }

    /** 温度谱选档（镜像 astroTempOf：活跃率→光谱型；无证据回退 kind/色相档） */
    private fun tempOfStar(s: dev.tgfeed.ui.TasteFxStar): Int {
        val fallback = GlPalette.tempClassOf(s.kind, s.bodyColor, hsv)
        if (s.recent7 < 0) return fallback
        return when (TasteAstroPhysics.spectralClassOf(s.recent7, -1L)) {
            TasteAstroPhysics.SpectralClass.O, TasteAstroPhysics.SpectralClass.B,
            TasteAstroPhysics.SpectralClass.A,
            -> GlPalette.TEMP_COOL
            TasteAstroPhysics.SpectralClass.F, TasteAstroPhysics.SpectralClass.G -> GlPalette.TEMP_WHITE
            TasteAstroPhysics.SpectralClass.K, TasteAstroPhysics.SpectralClass.M -> GlPalette.TEMP_WARM
        }
    }

    /**
     * 星体形态线（R349 全员 3D LOD——r341 政策表逐类统一；R355 dp 化）：**全类
     * 同尺三态** [TasteLodPolicy]：r<1.2dp 点档（亚像素安静色点）/[1.2,1.6)dp
     * blend 带（点 α×0.92×(1−k) 淡出——**R355 终裁：唯一存活侧**；球=真 3D 球面
     * 从第一像素在场，ambient 平涂退役）/≥1.6dp 3D 真身（<[lodLowMeshMaxPx]
     * 低模 SPHERE_I instanced 批/≥全模 UV 球+贴脸日冕）。恒星球门槛 30→2=
     * 「扁圈」绝迹的最大宗一刀（宪法二章 10 条）；R339b 24~30 贴脸窗随门槛
     * 结构性空置——crossfade 族整体重锚 [1.2,1.6]dp（零第二套）。点形态统一
     * （含 <1.2dp 远点档）：coreRatio=coreSwellCoef+coreC 核色+α×0.92——点档
     * 边界零跳变（k=0 连续；旧 10px 边界 0.80→0.26 跳变随中间档消亡顺带根治）。
     * 尾段=R339 衍射星芒（亮度 top 门）——**resolved 无芒**（pointFadeK 让位）。
     * 窗两端=密度档帧字段（[lodPointCeilPx]/[lodSolidFloorPx]——build 单口换算）。
     */
    private fun buildStarBody(
        s: dev.tgfeed.ui.TasteFxStar,
        sx: Float, sy: Float, r: Float, alpha: Float,
        temp: Int, seed: Int, t: Float, color: Int, depth: Float, focal: Float,
        wx: Float, wy: Float, wz: Float,
        /** R320 星际消光色温偏移（≤0——主序连续色温支路专用；镜像 Canvas） */
        extShiftK: Float = 0f,
        /** R321 潮汐星索引（-1=无潮汐——scanBinariesR321 槽位账；0=域外恒等） */
        tideSi: Int = -1,
        /** R339 星芒亮度01（top 门入选星 [selectSpikesR339] 归一——0=未选零芒） */
        spikeB01: Float = 0f,
        /** R349 星索引（低模球籍预选账 [selectLowSpheresR349]——tideSi 同值独立传参） */
        si: Int = -1,
    ) {
        // ---- R334/R339 连续色温支路（原双支路各自计算收口单源：中档点核色/
        //      贴脸日冕芯色/星芒眩光核色共用——useK 判据与消光偏移后有效色温
        //      tempKEff 镜像 Canvas 口径） ----
        val useK = s.tempK > 0f && s.kind == CelestialKind.STAR
        val tempKEff = if (useK && extShiftK < 0f) {
            (s.tempK + extShiftK).coerceIn(TasteStarColor.TEMP_MIN_K, TasteStarColor.TEMP_MAX_K)
        } else {
            s.tempK
        }
        val coreC = if (useK) TasteStarColor.coreColorOfK(tempKEff) else GlPalette.coreColorOf(temp)
        // ---- R376 星风泡退役（宽场灰圈主根因正治）----
        //      旧件：windy 星恒画 6.0~8.5×r 淡环（TROJAN_COLOR×α0.04~0.09）——用户
        //      小米宽场截图泡专项实测 36/689 颗带环、环位半径比 6.0/7.0/8.0/8.5×r
        //      与 BUBBLE_R_MIN/MAX 代码常量逐位吻合（数字指纹）、环带 RGB(16,16,16)
        //      =淡蓝白×α 混黑=灰白；16384 活体探针实锚（泡在场 24 颗/景）=
        //      宽场常态 24 颗大圈。α0.04~0.09 进不了 bloom 阈值（R333 判词：低 α 环
        //      =平的灰圈不上辉光）——R339 宽晕退役后此件以「物质壳」白名单幸存，
        //      用户四代否决史+本代「乍一看像光晕」=对灰圈形态类整体否决。星风语义
        //      不灭：贴脸星风流/尘埃不对称/科普 whyChain 星风行全保留（StarwindFx
        //      单源——退役的只是远观大圈视觉本体）。
        // ---- R321 潮汐形变（贴脸档真球=shader 顶点缩放；点档=TEX_FOG 旋转
        //      拉长四边形——addQuad 原生 rotDeg+非对称半轴纯 CPU 屏空间件；
        //      低模批档无潮汐槽（2~8px 形变亚 10px 不可辨——r349_notes §2.5）） ----
        val tide = if (tideSi >= 0 && tideSi < tideDef.size) tideDef[tideSi] else 0f
        // ---- R349 形态线三态（r341 §2.2 表：k=blend01——blend 带点先球后=球盖点，
        //      画家序与 R339b 中间支路同式；球籍被截断的降级星=回点档全亮点
        //      （α×0.92 与球 α 本就能量配平——帽界换籍帧间跳变 ≤8% 预算内）；
        //      R355 dp 化=窗两端帧字段透传） ----
        val k = TasteLodPolicy.blend01(r, lodSolidFloorPx, lodPointCeilPx)
        val solidLow = r < lodLowMeshMaxPx
        // 球形态可用性先判（点 alpha 依赖——帽满/无籍=回点档全亮）
        val spherePossible = k > 0.01f && (
            if (solidLow) {
                lowSphereCount < lodInstanceCap && si >= 0 && si < lowSphereOk.size && lowSphereOk[si]
            } else {
                sphereCount < MAX_SPHERES
            }
            )
        val pointK = if (spherePossible) 1f - k else 1f
        if (k < 0.99f || !spherePossible) {
            // 点形态（blend 带内 ×(1−k) 淡出——点档/降级星全亮）
            if (tide > 0f) {
                addQuad(
                    TEX_FOG, sx, sy,
                    max(r, 0.8f) * RocheTidalFx.scaleXOf(tide), max(r, 0.8f) * RocheTidalFx.scaleYOf(tide),
                    0f, Math.toDegrees(tideAngRad[tideSi].toDouble()).toFloat(),
                    ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f,
                    alpha * 0.45f * pointK,
                )
            }
            addPoint(
                sx, sy, max(r, 0.8f) * 2f, TasteStarGlow.coreSwellCoef(r, lodSolidFloorPx, lodPointCeilPx),
                ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f, alpha * 0.92f * pointK,
                ((coreC shr 16) and 0xFF) / 255f, ((coreC shr 8) and 0xFF) / 255f, (coreC and 0xFF) / 255f,
            )
        }
        if (spherePossible) {
            if (solidLow) {
                // 低模球批档（SPHERE_I instancing——[1.6,4)dp 密度档；R355 终裁
                // **ambient 平涂退役**：球=真 3D 球面从第一像素在场——blend 只留
                // 点淡出侧（上方 pointK=1−k——点的 α 随球浮现渐退）
                addLowSphere(
                    s, color, temp, alpha * k, seed, t, depth, focal, r, wx, wy, wz, extShiftK,
                    si,
                )
                if (k > 0.01f && k < 0.99f) statBlendPairs++
            } else {
                // 全模 UV 球（≥4dp——R280 贴脸质感档；alpha×k 满档恒等；终裁
                // 「全模型门槛降 4dp」=中距离上段即全模质感）
                addSphere(s, color, temp, alpha * k, seed, t, depth, focal, r, wx, wy, wz, extShiftK)
                // R321 潮汐槽回填（刚装填的末实例 slots 22..26——轴 3+grow 2；提取
                // backfillTideR321 单口=blend 带内球淡入同回填共用）
                backfillTideR321(tideSi, tide)
                // R280 贴脸日冕（r≥[CORONA_MIN_R]——程序冕流 shader 四边形，真身非烘焙）；
                // R311：核色走连续色温支路（useK 同 addSphere 判据）+日珥拱活动烈度入槽；
                // R320：消光偏移后有效色温（−300K 帽内变红——镜像 Canvas tempKEff）；
                // R339：芯色=提升单源 coreC（原支路局部计算收口）
                if (r >= CORONA_MIN_R && alpha > 0.05f) {
                    val activity01 = if (s.recent7 > 0) (s.recent7 / 14f).coerceIn(0f, 1f) else 0f
                    addCorona(sx, sy, r, seed, t, coreC, alpha, 0.45f + 0.55f * activity01)
                }
                if (k > 0.01f && k < 0.99f) statBlendPairs++
            }
        } else if (k > 0.01f) {
            // 球籍被截断（帽满/无籍——r341 §4.2 超帽策略最小屏径优先出球籍的
            // 被踢侧）：回点档全亮点（上方已发）+诚实记账
            statLodDropped++
        }
        // ---- R339 衍射星芒（亮度 top 门 [TasteStarGlow.SPIKE_MAX_STARS]——R242
        //      r≥4 门十字退役换亮度门（用户两形态裁决「top ~5 最亮=星芒」口径）：
        //      芒长=[spikeLenPx] 10~26px ∝亮度（top1 满 26px 帽）/细十字宽
        //      [SPIKE_WIDTH_PX] 1.2px 恒宽不随 r 膨胀成光柱/α 剖面=TEX_SPIKE 烘焙
        //      (1−u)³ 快衰减（根=SPIKE_A 0.75——「刺」不是「棒」半芒即近灭）/
        //      四芒 [SPIKE_COUNT]+top1 六芒加冕 [SPIKE_COUNT_RICH]（60° 均布）/
        //      芒色=星色 color（色温色零新色源）+**pointFadeK 让位**（r341 §5.2
        //      物理立规「resolved 天体无芒」：blend 进 3D 即退场，球档归零——
        //      芒骑在球上=红旗；R339b pointFormAlpha 同义已单源 [TasteLodPolicy]） ----
        if (spikeB01 > 0f && alpha > 0.02f) {
            val pfa = TasteLodPolicy.pointFadeK(r, lodSolidFloorPx, lodPointCeilPx)
            if (pfa > 0.01f) {
                val len = TasteStarGlow.spikeLenPx(spikeB01)
                val halfW = TasteStarGlow.SPIKE_WIDTH_PX * 0.5f
                val aArm = alpha * pfa
                val cr = ((color shr 16) and 0xFF) / 255f
                val cg = ((color shr 8) and 0xFF) / 255f
                val cb = (color and 0xFF) / 255f
                if (spikeB01 >= 1f) {
                    // top1 六芒加冕（60° 均布=0°/60°/120° 三 quad 双臂）
                    addQuad(TEX_SPIKE, sx, sy, len, halfW, 0f, 0f, cr, cg, cb, aArm)
                    addQuad(TEX_SPIKE, sx, sy, len, halfW, 0f, 60f, cr, cg, cb, aArm)
                    addQuad(TEX_SPIKE, sx, sy, len, halfW, 0f, 120f, cr, cg, cb, aArm)
                } else {
                    // 四芒细十字（0°/90° 两 quad 双臂）
                    addQuad(TEX_SPIKE, sx, sy, len, halfW, 0f, 0f, cr, cg, cb, aArm)
                    addQuad(TEX_SPIKE, sx, sy, len, halfW, 0f, 90f, cr, cg, cb, aArm)
                }
                // 眩光（≤[TasteStarGlow.GLARE_R_PX] 2px 白热饱和核——点精灵
                // coreRatio 0.5 平台内半≈glareAlphaOf 剖面同构；核色=coreC）
                addPoint(
                    sx, sy, TasteStarGlow.GLARE_R_PX * 2f, 0.5f,
                    cr, cg, cb, TasteStarGlow.GLARE_A * alpha * pfa,
                    ((coreC shr 16) and 0xFF) / 255f, ((coreC shr 8) and 0xFF) / 255f, (coreC and 0xFF) / 255f,
                )
            }
        }
    }

    /**
     * R321 潮汐槽回填（真球末实例 slots 22..26——轴 3+grow 2；未形变=恒等值
     * (0,0,0)/(0,0) 逐位=旧输出；addSphere 帽满跳过=本帧零形变诚实；R339
     * crossfade 窗内球淡入实例同回填——从 r≥STAR_BALL_MIN_R 支路提取单口）。
     */
    private fun backfillTideR321(tideSi: Int, tide: Float) {
        if (tide <= 0f || sphereCount <= 0) return
        if (tideSi < 0 || tideSi >= tideDef.size) return
        val i = (sphereCount - 1) * SPHERE_STRIDE
        sphereBuf[i + 22] = tideAxW[tideSi]
        sphereBuf[i + 23] = tideAyW[tideSi]
        sphereBuf[i + 24] = tideAzW[tideSi]
        sphereBuf[i + 25] = RocheTidalFx.scaleXOf(tide) - RocheTidalFx.scaleYOf(tide)
        sphereBuf[i + 26] = RocheTidalFx.scaleYOf(tide) - 1f
    }

    /**
     * 真球实例参数装填（SPHERE_STRIDE 语义见 companion；三段色=ballBitmapOf 同参数
     * HSV 方程；世界半径=px 半径反解（r=worldR×focal/depth——投影矩阵与
     * TasteSpace.project 逐位同式有 GlCameraMatrix 单测锚，屏位不漂））。
     * R349：着色参数槽 5..21 与低模批 [SPHERE_I_STRIDE] **逐位同构**（共享
     * [fillStarSphereParamsR349] 单源——全模/低模同一色温/颗粒/自转方程，8px
     * 换模零 pop 的装配半边）；返回 false=全模帽满（调用方回点档诚实降级）。
     */
    private fun addSphere(
        s: dev.tgfeed.ui.TasteFxStar, color: Int, temp: Int, alpha: Float,
        seed: Int, t: Float, depth: Float, focal: Float, rPx: Float,
        wx: Float, wy: Float, wz: Float,
        /** R320 消光色温偏移（镜像 Canvas——只作用于 useK 主序支路） */
        extShiftK: Float = 0f,
    ): Boolean {
        if (sphereCount >= MAX_SPHERES) return false
        switchType(T_SPHERE)
        val i = sphereCount * SPHERE_STRIDE
        sphereBuf[i] = wx
        sphereBuf[i + 1] = wy
        sphereBuf[i + 2] = wz
        sphereBuf[i + 3] = rPx * depth / focal
        sphereBuf[i + 4] = alpha.coerceIn(0f, 1f)
        fillStarSphereParamsR349(s, color, temp, seed, t, extShiftK, sphereBuf, i)
        // R321 潮汐槽清零（恒等值——**防缓冲复用的上一实例残留**：sphereBuf 是帧
        // 间复用缓冲，非形变星不清零会吃到上一帧同槽形变星的轴/grow；形变由
        // buildStarBody 在装填后回填覆盖）
        sphereBuf[i + 22] = 0f
        sphereBuf[i + 23] = 0f
        sphereBuf[i + 24] = 0f
        sphereBuf[i + 25] = 0f
        sphereBuf[i + 26] = 0f
        sphereCount++
        statSphereStars++
        statSphereFull++
        emitOp(OP_SPHERE, sphereCount - 1, 0, 0)
        return true
    }

    /**
     * R349 星球着色参数槽 5..21 共源装填（全模 [sphereBuf] 与低模 [lowSphereBuf]
     * 逐位同构——一类一件：三段色温带/颗粒振幅/噪声种子/自转角/时间/黑子幅/
     * 火苗幅/热星旗/临边昏暗系数，两 mesh 档同一方程同一来源；R311/R320 消光
     * 色温支路原式平移）。
     */
    private fun fillStarSphereParamsR349(
        s: dev.tgfeed.ui.TasteFxStar, color: Int, temp: Int,
        seed: Int, t: Float, extShiftK: Float,
        buf: FloatArray, i: Int,
    ) {
        // 三段色（ballBitmapOf 同参数：芯/星色/缘）；
        // R311 色彩引擎支路：主序 STAR 且 tempK>0 → 芯/缘=黑体谱连续导出（T×1.35/
        // T×0.60——TasteStarColor 单源），旧三档桶在主序星上退役（特殊 kind 照旧）；
        // R320：消光偏移后有效色温（域钳黑体谱界——镜像 Canvas tempKEff）
        val useK = s.tempK > 0f && s.kind == CelestialKind.STAR
        val tempKEff = if (useK && extShiftK < 0f) {
            (s.tempK + extShiftK).coerceIn(TasteStarColor.TEMP_MIN_K, TasteStarColor.TEMP_MAX_K)
        } else {
            s.tempK
        }
        GlPalette.hsvOf(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.38f, 0.62f)
        hsv[2] = hsv[2].coerceIn(0.62f, 0.88f)
        val starH = hsv[0]
        val starS = hsv[1]
        val starV = hsv[2]
        if (useK) {
            GlPalette.rgbF(TasteStarColor.coreColorOfK(tempKEff), rgbA)
        } else {
            when (temp) {
                GlPalette.TEMP_WARM -> GlPalette.hsvToRgbF(28f, 0.42f, 1f, rgbA)
                GlPalette.TEMP_COOL -> GlPalette.hsvToRgbF(215f, 0.10f, 1f, rgbA)
                else -> GlPalette.hsvToRgbF(starH, starS * 0.35f, 1f, rgbA)
            }
        }
        GlPalette.hsvToRgbF(starH, starS, starV, rgbB)
        if (useK) {
            GlPalette.rgbF(TasteStarColor.edgeColorOfK(tempKEff), rgbC)
        } else {
            when (temp) {
                GlPalette.TEMP_WARM -> GlPalette.hsvToRgbF(358f, (starS + 0.25f).coerceAtMost(0.9f), starV * 0.30f, rgbC)
                GlPalette.TEMP_COOL -> GlPalette.hsvToRgbF(48f, 0.52f, starV * 0.55f, rgbC)
                else -> GlPalette.hsvToRgbF((starH + 14f) % 360f, starS, starV * 0.38f, rgbC)
            }
        }
        buf[i + 5] = rgbA[0]; buf[i + 6] = rgbA[1]; buf[i + 7] = rgbA[2]
        buf[i + 8] = rgbB[0]; buf[i + 9] = rgbB[1]; buf[i + 10] = rgbB[2]
        buf[i + 11] = rgbC[0]; buf[i + 12] = rgbC[1]; buf[i + 13] = rgbC[2]
        // 颗粒振幅=活动烈度（R267b 同式：0.16+0.22×activity01——静默星也有表面感）
        val activity01 = if (s.recent7 > 0) (s.recent7 / 14f).coerceIn(0f, 1f) else 0f
        buf[i + 14] = 0.16f + 0.22f * activity01
        buf[i + 15] = galaxyH01(seed, 373) * 97f // 噪声种子偏移（确定性）
        // 自转角（数据方程同式：recent7→ω；黑子自转语义由颗粒采样位缓转承接）
        val omegaRot = if (s.recent7 > 0) (0.006f + 0.02f * (s.recent7 / 14f).coerceIn(0f, 1f)) else 0.004f
        buf[i + 16] = t * omegaRot * 6.2831853f
        // R280 真球增强三参：时间（火苗缓漂）/黑子幅（metallicity=内容多样性）/火苗幅
        buf[i + 17] = t
        val gSum = (s.gVideo + s.gPhoto + s.gAudio).coerceAtLeast(1)
        val dominant = maxOf(s.gVideo, s.gPhoto, s.gAudio).toFloat() / gSum
        buf[i + 18] = (1f - dominant).coerceIn(0f, 1f) // metallicity 0..1
        buf[i + 19] = (0.45f + 0.55f * activity01) // 火苗幅（活动烈度驱动）
        buf[i + 20] = if (temp == GlPalette.TEMP_COOL) 1f else 0f // 热星=亮斑 faculae
        // R311 临边昏暗系数（uLimbK——TasteStarColor 单源：连续档=色温从动，旧桶=映射档）；
        // R320：连续档读偏移后有效色温（消光变红——镜像 Canvas）
        buf[i + 21] = TasteStarColor.limbKOfTemp01(
            if (useK && tempKEff > 0f) {
                TasteStarColor.temp01OfKelvin(tempKEff)
            } else if (s.tempK > 0f) {
                TasteStarColor.temp01OfKelvin(s.tempK)
            } else {
                when (temp) {
                    GlPalette.TEMP_WARM -> 0.18f
                    GlPalette.TEMP_COOL -> 0.80f
                    else -> 0.55f
                }
            },
        )
    }

    /**
     * R349 低模球实例装填（[1.6,4)dp 密度档——SPHERE_I instancing；着色参数槽与
     * [addSphere] 共源 [fillStarSphereParamsR349]；**R321 槽位纪律：全 24 槽
     * 无条件逐槽写**（含预留槽 22/23 恒 0——帧间复用残留结构性不可达）；无
     * 潮汐槽（低模档形变亚 10px 不可辨——潮汐=全模档专享）。**R355 终裁：槽
     * 22=旧 blend 带均匀底光 ambient 退役位**（平盘期彻底删除——球=真 3D 球面
     * 从第一像素在场；槽复归预留恒 0 无消费）。球籍=帧首
     * [selectLowSpheresR349] 预选（最小屏径优先）+运行帽双闸；返回
     * false=帽满/无籍（调用方回点档全亮点——α×0.92 与球 α 本就能量配平，帽界
     * 换籍帧间跳变 ≤8% 预算内）。
     */
    private fun addLowSphere(
        s: dev.tgfeed.ui.TasteFxStar, color: Int, temp: Int, alpha: Float,
        seed: Int, t: Float, depth: Float, focal: Float, rPx: Float,
        wx: Float, wy: Float, wz: Float, extShiftK: Float,
        si: Int,
    ): Boolean {
        if (lowSphereCount >= lodInstanceCap) return false
        if (si < 0 || si >= lowSphereOk.size || !lowSphereOk[si]) return false
        switchType(T_LOW_SPHERE)
        val i = lowSphereCount * SPHERE_I_STRIDE
        lowSphereBuf[i] = wx
        lowSphereBuf[i + 1] = wy
        lowSphereBuf[i + 2] = wz
        lowSphereBuf[i + 3] = rPx * depth / focal
        lowSphereBuf[i + 4] = alpha.coerceIn(0f, 1f)
        fillStarSphereParamsR349(s, color, temp, seed, t, extShiftK, lowSphereBuf, i)
        // 槽 22=R355 退役位恒 0（旧 blend 带均匀底光——平盘期彻底删除：真 3D
        // 即刻生效；槽复归预留）；槽 23=预留恒 0（R321 清零纪律：无条件写）
        lowSphereBuf[i + 22] = 0f
        lowSphereBuf[i + 23] = 0f
        lowSphereCount++
        statSphereStars++
        statSphereLow++
        return true
    }

    /** 日冕实例装填（CORONA_STRIDE 语义见 companion；四边形半幅=1.9r 覆盖冕流最大伸展；
     *  [flameAmp]=活动烈度代理（0.45+0.55·activity01——R311 日珥拱高度的原料）） */
    private fun addCorona(sx: Float, sy: Float, rPx: Float, seed: Int, t: Float, coreColor: Int, alpha: Float, flameAmp: Float) {
        if (coronaCount >= MAX_SPHERES) return
        val i = coronaCount * CORONA_STRIDE
        coronaBuf[i] = sx
        coronaBuf[i + 1] = sy
        coronaBuf[i + 2] = rPx * 1.9f
        coronaBuf[i + 3] = galaxyH01(seed, 211) * 97f
        coronaBuf[i + 4] = t
        coronaBuf[i + 5] = ((coreColor shr 16) and 0xFF) / 255f
        coronaBuf[i + 6] = ((coreColor shr 8) and 0xFF) / 255f
        coronaBuf[i + 7] = (coreColor and 0xFF) / 255f
        coronaBuf[i + 8] = alpha.coerceIn(0f, 1f)
        coronaBuf[i + 9] = flameAmp.coerceIn(0f, 1f)
        coronaCount++
        statCoronas++
        emitOp(OP_CORONA, coronaCount - 1, 0, 0)
    }

    // ==================== 黑洞 ====================

    /**
     * 黑洞（**R281 真 3D 化**：屏空间压扁椭圆+黑盘贴纸 → 真几何三件套）：
     *  - **事件视界=纯黑真球**（UV 球网格——深度写开，真遮挡吸积盘后缘；
     *    点精灵尺寸上限的 TEX_DISC 规避件退役）；
     *  - **吸积盘=真 3D 倾斜环带**（GlRingMesh+较差自转 streaks shader——
     *    侧视角真椭圆、穿盘面真划过；屏空间 DISK_FLATTEN 椭圆法根治病除）；
     *  - 盘粒子搬到**环面真轨道**（内环开普勒快 ω∝1/r^1.5 同式——随环倾斜真 3D）；
     *  - 光子环/引力波纹=屏空间件保留（透镜效应语义诚实）。
     */
    private fun buildBlackHole(
        sx: Float, sy: Float, r: Float, alpha: Float, seed: Int, t: Float,
        depth: Float, focal: Float, wx: Float, wy: Float, wz: Float,
        cam: TasteSpace.SpaceCam, cx: Float, cy: Float,
        /** R317b 进食档：accretion01（recent7 归一——[TasteAstroPhysics.accretion01Of] 单口） */
        acc01: Float,
    ) {
        // R317b 进食档系数（core 单源）：盘亮度（静默 0.55~盛宴 1.0）+类星体相门
        val diskK = TasteTerminalFx.accretionDiskK(acc01)
        val feast = TasteTerminalFx.isQuasarPhase01(acc01)
        // R349 形态线（r341 表行 8）：视界/盘门 2.2→[lodSolidFloorPx] 统一；
        // 视界黑球 blend 带 α×k 淡入（k=blend01——<点档上限 纯光子环点/
        // [1.2,1.6)dp 黑球渐显；R355 dp 化=窗两端帧字段）
        val bhK = TasteLodPolicy.blend01(r, lodSolidFloorPx, lodPointCeilPx)
        if (r >= lodSolidFloorPx) {
            val worldR = r * depth / focal
            val tiltX = (galaxyH01(seed, 83) - 0.5f) * 1.1f
            val tiltZ = (galaxyH01(seed, 87) - 0.5f) * 0.55f
            // 画家序三件套：①视界黑球（深度写）→ ②吸积盘环带（深度测——前缘过/后缘挡）
            // → ③盘粒子（世界点带深度测——被视界真遮挡）；光子环/波纹最后叠加
            addHorizon(wx, wy, wz, worldR)
            // 吸积盘真 3D 环带（mode=1——内炽外冷+较差自转；光向不使用传相机向占位）
            addRing(
                wx, wy, wz, worldR, tiltX, tiltZ,
                cam.px - wx, cam.py - wy, cam.pz - wz,
                seed.toFloat(), alpha * 0.95f * diskK, mode = 1f,
                cr = 1f, cg = 1f, cb = 1f,
            )
            // 盘粒子=环面真轨道（局部 XZ 圆 → 同 RING_VS 的 tiltX/tiltZ 旋转 → 世界）
            val cxT = cos(tiltX); val sxT = sin(tiltX)
            val czT = cos(tiltZ); val szT = sin(tiltZ)
            val omegaOuter = 0.55f
            val omegaInner = omegaOuter * Math.pow((DISK_OUTER / DISK_INNER).toDouble(), 1.5).toFloat()
            val px2w = depth / focal
            val wpStart = wpCount
            for (ring in 0..1) {
                val inner = ring == 1
                val omega = if (inner) omegaInner else omegaOuter
                val baseR = if (inner) worldR * DISK_INNER else worldR * DISK_OUTER
                for (k in 0 until DISK_PARTICLES) {
                    val hBase = if (inner) k * 31 + 67 else k * 31 + 61
                    val hR = if (inner) k * 31 + 69 else k * 31 + 63
                    val ang = galaxyH01(seed, hBase) * 6.2831853f + t * omega
                    val rr = baseR * ((if (inner) 0.82f else 0.86f) + (if (inner) 0.36f else 0.28f) * galaxyH01(seed, hR))
                    // 环面局部 → 世界（先 X 倾后 Z 倾——与 RING_VS 同式）
                    val lx = cos(ang) * rr
                    val lz = sin(ang) * rr
                    val y1 = -lz * sxT
                    val z1 = lz * cxT
                    val x2 = lx * czT - y1 * szT
                    val y2 = lx * szT + y1 * czT
                    val sizePx = max(1.0f, r * (if (inner) 0.12f else 0.10f))
                    if (inner) {
                        addWPoint(wx + x2, wy + y2, wz + z1, sizePx * px2w, 0.72f, 1f, 0.914f, 0.769f, alpha * 0.80f * diskK)
                    } else {
                        addWPoint(wx + x2, wy + y2, wz + z1, sizePx * px2w, 0.72f, 0.847f, 0.565f, 0.345f, alpha * 0.55f * diskK)
                    }
                }
            }
            flushWPointsDepth(wpStart, depthTest = true)
        } else {
            // 远档黑洞（r<effectiveSolidFloor）：无盘细节——黑球 α×k blend 淡入
            // （k=0 纯光子环点=r341 表「<1.5 光子环点」口径）+光子环即可
            if (bhK > 0.01f) {
                addHorizon(wx, wy, wz, r * depth / focal, bhK)
            }
        }
        // 光子环（细亮暖白——刚好逃逸的光；屏空间透镜语义件保留；R317b 进食档：
        // 亮度随进食速率增，盛宴再加 [TasteTerminalFx.QUASAR_PHOTON_BOOST]）
        var photonA = alpha * 0.85f * (0.78f + 0.22f * diskK)
        if (feast) photonA = (photonA + TasteTerminalFx.QUASAR_PHOTON_BOOST).coerceAtMost(1f)
        addRingQuad(sx, sy, r * PHOTON_RING, 0xFFFFF3DC.toInt(), photonA)
        // R317b 类星体相喷流（盛宴门过——镜像 Canvas：双向束+快呼吸；喷流轴向与
        // 白洞同族固定哈希轴 TasteTerminalFx.whiteholeJetAngleDeg——非脉冲星扫掠）
        if (feast && r >= 1.8f) {
            val len = (r * 6.5f + 40f).coerceIn(40f, 170f)
            val flick = 0.80f + 0.20f * sin(t * 2.4f + galaxyH01(seed, 31) * 6.2831853f)
            val rot = TasteTerminalFx.whiteholeJetAngleDeg(seed)
            val ja = flick * alpha * 128f / 255f
            addQuad(TEX_BEAM, sx, sy, 6f, len / 2f, -len / 2f, rot, 1f, 1f, 1f, ja)
            addQuad(TEX_BEAM, sx, sy, 6f, len / 2f, -len / 2f, rot + 180f, 1f, 1f, 1f, ja)
        }
        // R334：引力波纹（6s 一轮扩散环）退役（镜像 Canvas——r333 §2.3-2：R320
        // GRAVITY_WAVE 波前星点位移=真引力波身已落地，装饰环语义完整让位；吸积
        // 盘/光子环/视界=静态身份件照旧）
    }

    // ==================== 星团成员球 ====================

    /**
     * R281 星团真身（任务④）：成员星 **3D 球分布**（世界点云——GPU 投影近大远小，
     * 绕飞团内真视差；Canvas/旧 GL 的屏空间圆阵尘点整体消解）。
     * 成员数/分层/尺寸式与旧尘点同参数（8+min(18,mem/800) 粒三档）。R343 A2：
     * 刚体缓旋（swirl 同式 t×0.05）退役——全团同 ω 唱片转=假运动（r344 审计
     * A 级）；成员各自开普勒轨道化归 B 级升维。
     */
    private fun buildClusterBall(
        sx: Float, sy: Float, r: Float, alpha: Float, seed: Int, t: Float, members: Int,
        depth: Float, focal: Float, wx: Float, wy: Float, wz: Float,
    ) {
        // R349 形态线（r341 表行 10）：成员云门 2.5→[lodSolidFloorPx]=2 随母体
        // 统一（核球≥2 低模=buildStarBody 主路自动；成员粒粒径下限 0.7~1.4px
        // 保粒语义不动）
        if (r < lodSolidFloorPx) return
        val mem = if (members > 0) members else 0
        val scaleCoef = if (mem > 0) {
            (kotlin.math.ln1p(mem.toFloat()) / kotlin.math.ln1p(60_000f)).coerceIn(0.15f, 1f)
        } else {
            0.35f
        }
        val dots = 8 + min(18, mem / 800)
        val cloudR = r * (1.5f + 1.5f * scaleCoef)
        val swirl = 0f // R343 A2：星团刚体缓旋退役（r344 审计 A 级——全团同 ω 唱片转=假运动；成员各自开普勒归 B 级升维；镜像 Canvas 同刀）
        val tierA = floatArrayOf(0.42f, 0.27f, 0.16f)
        val tierW = floatArrayOf(0.16f, 0.11f, 0.08f)
        val tierMin = floatArrayOf(1.4f, 1.0f, 0.7f)
        val cc = GlPalette.CLUSTER_COLOR
        val cr = ((cc shr 16) and 0xFF) / 255f
        val cg = ((cc shr 8) and 0xFF) / 255f
        val cb = (cc and 0xFF) / 255f
        val px2w = depth / focal // px → 世界（团心深处换算——成员个体深度由 GPU 投影细分）
        val wpStart = wpCount
        for (k in 0 until dots) {
            // 3D 球散布（均匀方向 hash + 半径偏置三档——屏圆阵的体积化）
            val u1 = galaxyH01(seed, k * 31 + 7)
            val u2 = galaxyH01(seed, k * 31 + 97)
            val hz = u2
            val tier = if (hz < 0.28f) 0 else if (hz < 0.65f) 1 else 2
            val bias = if (tier == 0) 1f else if (tier == 1) 0.78f else 0.52f
            val rr = (r * 1.25f + galaxyH01(seed, k * 31 + 13) * cloudR) * bias
            val theta = u1 * 6.2831853f + swirl // 缓旋=方位角真旋转
            val cphi = 2f * galaxyH01(seed, k * 31 + 17) - 1f
            val sphi = sqrt((1f - cphi * cphi).coerceAtLeast(0f))
            val ox = sphi * cos(theta) * rr * px2w
            val oy = cphi * rr * px2w
            val oz = sphi * sin(theta) * rr * px2w
            val sizePx = max(tierMin[tier], r * tierW[tier])
            addWPoint(
                wx + ox, wy + oy, wz + oz, sizePx * px2w, 0.72f,
                cr, cg, cb, alpha * tierA[tier], cr, cg, cb,
            )
        }
        flushWPoints(wpStart)
    }

    // ==================== 行星系统 ====================

    /**
     * 行星系统（**R281 真球化**——任务①⑤）：
     *  - 轨道线=**KEPLER 程序 GPU 求值**（顶点只载 ν 角——17 顶点/轨纯浮点拷贝，
     *    三角函数+投影全在 VS；r(ν)=a(1−e²)/(1+e·cosν) 与 planetOrbitPoint 同式镜像）；
     *  - 行星本体=**UV 球真身**（PLANET shader：真 3D 平行光=行星→恒星世界向量，
     *    晨昏线/相位随视角真变；程序表面=气态条带/岩质陨坑+极冠；大气 fresnel）；
     *  - 土星环=**真 3D 倾斜环带**（GlRingMesh+深度缓冲与行星球真穿插——
     *    「前后两弧法」贴纸退役；穿环面飞行=环带从头顶划过）；
     *  - 尘埃=**KEPLER 尘点 GPU 求值**（480 粒/系统纯浮点拷贝）+近档前景
     *    [DUST_ROCK_CAP] 粒=**岩石多面体真身**（翻滚+恒星光照）。
     */
    private fun buildPlanetSystem(
        f: TasteFlightFrame,
        s: dev.tgfeed.ui.TasteFxStar,
        entry: dev.tgfeed.ui.TastePlanetEntry,
        focal: Float, t: Float, w: Float, h: Float, cx: Float, cy: Float,
        cam: TasteSpace.SpaceCam, starAlpha: Float,
        scx: Float, scy: Float, scz: Float,
    ) {
        val ratio = galaxyWellRatio(scx, scy, scz, s.vol, f.wellMaxVol, cam)
        if (ratio >= TasteTagPlanets.APPEAR_RATIO) {
            // R399 吞门退役：未浮现档=并入母星光晕的行星色微点（判词「优雅降级
            // 而非消失」——行星永不整吞；重层（轨道线/宜居带/尘埃/球身）照旧全灭
            // =降级本身省 draw，性能刀收益零回退）
            buildPlanetMicroDotsR399(s, entry, cam, focal, t, w, h, cx, cy, scx, scy, scz, ratio)
            return
        }
        val distStar = TasteSpace.distTo(cam, scx, scy, scz)
        if (distStar <= TasteSpace.NEAR) return
        val sizeK = focal / distStar
        val starPxRaw = s.bodyR * sizeK
        val starPxMax = minOf(w, h) * TasteSpace.SWALLOW_MAX_R_FRAC
        if (starPxRaw > starPxMax * 3f) return
        val starPx = minOf(starPxRaw, starPxMax)
        val planets = entry.system.planets
        val maxShow = min(planets.size, TasteTagPlanets.LOD_NEAR_PLANETS)
        if (maxShow <= 0) return
        // ---- R328 批C 行星成型贴脸拍（镜像 Canvas——TasteBirthFx 成型档调制，
        //      TasteTagPlanets 几何零改）：成型态（birth.planetsForming）=坡道放缓
        //      （formingRampWidth）+尘埃凝缩（formingDustRadiusCoef） ----
        val formingOn = s.birth?.planetsForming == true
        val rampW = if (formingOn) {
            TasteBirthFx.formingRampWidth(TasteTagPlanets.APPEAR_RAMP)
        } else {
            TasteTagPlanets.APPEAR_RAMP
        }
        val orbitBoost = TasteTagPlanets.nearBoost(ratio)
        val dustA = if (s.chatId == f.dustChatId) TasteTagPlanets.dustAlpha(distStar) else 0f
        val seed = (s.chatId % 100_000L).toInt()
        val inc = galaxyOrbitInclination(seed)
        val incSin = sin(inc)
        val incCos = cos(inc)
        val starCr = ((s.bodyColor shr 16) and 0xFF) / 255f
        val starCg = ((s.bodyColor shr 8) and 0xFF) / 255f
        val starCb = (s.bodyColor and 0xFF) / 255f
        // R320 凌星判定原料：恒星屏心（行星-恒星屏距的锚——与预扫同一式）
        val starVis = TasteSpace.project(scx, scy, scz, cam, focal, cx, cy, proj, basisArr)
        val starSx = if (starVis) proj[0] else Float.NaN
        val starSy = if (starVis) proj[1] else Float.NaN
        // R340 恒星相机系深度（凌星前置判定原料——proj[2] 在行星投影处会被覆写，先存；
        // 恒星不可见=NaN 同扇区判非凌星（starSx.isNaN() 既有门在前））
        val starDepthR340 = if (starVis) proj[2] else Float.NaN
        // R399 count 轴微点基座：微星三档深度语言同源（<900/<2600/远——域内系统
        // 恒 tier0，远档并入光晕读法=与星点同屏亮度族）
        val microTierA = MICRO_TIER_A[if (distStar < 900f) 0 else if (distStar < 2600f) 1 else 2]
        var formOn = true // R399：a≤0 段（浮现坡道后段）重层截断——微点腿续走不整吞
        for (i in 0 until maxShow) {
            val p = planets[i]
            // R328 成型档坡道（镜像 Canvas——rampW 成型态=放缓/非成型=APPEAR_RAMP 逐位等值）
            val a = TasteTagPlanets.appearAlpha(ratio, i, maxShow, rampW)
            if (a <= 0f) formOn = false // R399：off 段重层截断（阈值单调）；旧 break 整吞退役
            val rW = planetOrbitRadius(p, s.bodyR)
            val peri = TasteTagPlanets.keplerPeriapsis(p)
            // ---- 宜居带环带（R311 重做：纯色描边圆 → 尘埃/冰粒环带——用户批准）：
            //      均迹线=极淡 KEPLER 线（保轨道可读）+冰粒=KEPLER 点（ν 角+径向
            //      系数+尺寸+逐粒 alpha 装填——GPU 轨道求值既有件，装配纯浮点拷贝；
            //      聚块成弧成缝=TasteStarColor 三段谐波场，alpha<0.10 整粒跳过=环缝） ----
            // （R399：off 段重层不画——微点腿不进轨道线/宜居带）
            if (formOn && (i < TasteTagPlanets.LOD_MID_PLANETS || orbitBoost > 0.5f)) {
                val sysIdx = addKepSys(scx, scy, scz, peri, incSin, incCos, rW, p.ecc)
                if (sysIdx >= 0) {
                    // 均迹线（极淡 0.045——旧 (0.10+0.08·boost) 纯色圆圈退役）
                    var start = kepVertCount
                    val guideA = TasteStarColor.HABITABLE_GUIDE_ALPHA * (0.5f + 0.5f * orbitBoost) * a * starAlpha
                    for (k in 0..16) {
                        addKepVert(k / 16f * 6.2831853f, 1f, 0f, starCr, starCg, starCb, guideA)
                    }
                    if (kepVertCount > start) emitImmediate(OP_KEPLER_LINES, sysIdx, start, kepVertCount - start)
                    // 冰粒环带（28 粒上限；冰粒色=白蓝冰底×恒星光染色 0.30）
                    start = kepVertCount
                    val gs = p.tag.hashCode()
                    val habIce = GlPalette.blendRgb(TasteStarColor.HABITABLE_ICE_ARGB, s.bodyColor, 0.30f)
                    val iceR = ((habIce shr 16) and 0xFF) / 255f
                    val iceG = ((habIce shr 8) and 0xFF) / 255f
                    val iceB = (habIce and 0xFF) / 255f
                    val grainSizeW = TasteTagPlanets.dustRadiusPx(starPx) * 1.05f * distStar / focal
                    for (k in 0 until TasteStarColor.HABITABLE_GRAINS) {
                        val gAng = TasteStarColor.habitableGrainAngle(gs, k)
                        val ga = TasteStarColor.habitableGrainAlpha(gs, gAng)
                        if (ga < 0.10f) continue // 环缝（聚块场暗带整粒跳过）
                        addKepVert(
                            gAng,
                            TasteStarColor.habitableGrainCoef(gs, k),
                            grainSizeW * TasteStarColor.habitableGrainSize(gs, k),
                            iceR, iceG, iceB,
                            (ga * a * starAlpha).coerceIn(0f, 1f),
                        )
                    }
                    if (kepVertCount > start) emitImmediate(OP_KEPLER_POINTS, sysIdx, start, kepVertCount - start)
                }
            }
            // ---- 行星本体（真球实例；真 3D 平行光=行星→恒星世界向量；R320 凌星=
            //      剪影暗球+缘光环——判定与预扫 scanTransitDepthR320 同一式单源） ----
            val nu = TasteTagPlanets.keplerTheta(p, t)
            val rOrb = TasteTagPlanets.keplerOrbitRadius(p, rW, nu)
            planetOrbitPoint(scx, scy, scz, rOrb, nu + peri, inc, mpos)
            if (TasteSpace.project(mpos[0], mpos[1], mpos[2], cam, focal, cx, cy, proj, basisArr)) {
                val worldR = planetWorldRadius(p, s.bodyR, rW)
                val rP = worldR * proj[3]
                // R340 凌星前置判定（黑圈回归根治）：加「行星在恒星与相机之间」深度门——
                // 背后掩星行星不再进剪影档（走受光路径，GL 深度测试被恒星球真遮挡）；
                // 真前+屏面重叠=金星凌日观感保真。恒星出屏（starSx NaN/depth NaN）=非凌星。
                // （R399 外提自 rP 门——微点腿同门剪影读法；判定式零改）
                val transiting = !starSx.isNaN() &&
                    EclipseFx.isTransitingFront(
                        rP, starPx, hypot(proj[0] - starSx, proj[1] - starSy),
                        proj[2], starDepthR340,
                    )
                // ---- R399 count 轴微点腿：「未浮现」态（a<1——坡道淡出中/off）=
                //      行星色微点×(1−a) 镜像淡入（R349 blend 带双态叠加同族语言，
                //      count 轴交接零跳变）；凌星中=暗微点（背影剪影的亚像素读法
                //      同门）——行星永不整吞（判词 r399_notes §2） ----
                val dotA = TasteTagPlanets.microDotAlpha01(ratio, i, maxShow, rampW) * microTierA
                if (dotA > 0.01f) {
                    if (transiting) {
                        addPoint(proj[0], proj[1], lodMicroDotPx, 0.75f, 0.03f, 0.04f, 0.06f, dotA * 0.94f)
                    } else {
                        val dotColor = TasteStarColor.planetColorOf(p.compV, p.compP, p.compA, s.bodyColor, p.tag.hashCode())
                        addPoint(
                            proj[0], proj[1], lodMicroDotPx, 0.75f,
                            ((dotColor shr 16) and 0xFF) / 255f, ((dotColor shr 8) and 0xFF) / 255f,
                            (dotColor and 0xFF) / 255f, dotA,
                        )
                    }
                    statPlanetDots++
                }
                // ---- R399 画不出门退役：rP>0 即入装填（旧 1.5px 裸像素门=亚像素
                //      行星连点腿都不给=隐身缝；亚像素由 buildPlanetBody 点腿尺寸
                //      地板兜底=最小可见亮点）；屏界裕量=微点地板口径 ----
                val edgeM = (rP * 3f).coerceAtLeast(lodMicroDotPx)
                if (formOn && rP > 0f &&
                    proj[0] > -edgeM && proj[0] < w + edgeM && proj[1] > -edgeM && proj[1] < h + edgeM
                ) {
                    buildPlanetBody(s, p, proj[0], proj[1], mpos[0], mpos[1], mpos[2], worldR, rP, a, scx, scy, scz, t, inc, transiting)
                }
            }
            // ---- 尘埃环（KEPLER 尘点 GPU 求值；近档前景粒=岩石真身） ----
            // （R399：off 段尘埃不画——重层截断同 break 旧语义）
            if (formOn && dustA > 0f) {
                val items = entry.items
                GlPalette.hsvOf(s.bodyColor, hsv)
                hsv[2] = maxOf(hsv[2], 0.85f)
                hsv[1] = hsv[1].coerceAtMost(0.40f)
                val dustColor = GlPalette.hsvToRgb(hsv[0], hsv[1], hsv[2])
                val dr = ((dustColor shr 16) and 0xFF) / 255f
                val dg = ((dustColor shr 8) and 0xFF) / 255f
                val db = (dustColor and 0xFF) / 255f
                val dotSizePx = TasteTagPlanets.dustRadiusPx(starPx)
                val dotSizeW = dotSizePx * distStar / focal // 世界尺寸（GPU 投影近大远小）
                val rockMode = dotSizePx >= DUST_ROCK_MIN_PX
                val sysIdx = addKepSys(scx, scy, scz, peri, incSin, incCos, rW, p.ecc)
                val start = kepVertCount
                val rockStart = rockCount
                var dustN = 0
                var rockN = 0
                // R321 星风尘埃不对称（镜像 Canvas——渲染期相位密度偏移：宿主有星风
                // → 背风密/迎风稀逐粒 keep 概率（哈希判死活）；无风 asym=0 恒保留零改）
                val windAsymR321 = if (StarwindFx.isWindy(s.recent7, s.degree)) {
                    StarwindFx.dustAsym01(StarwindFx.strength01Of(s.recent7))
                } else {
                    0f
                }
                val windAxisR321 = if (windAsymR321 > 0f) StarwindFx.windAxisRad(seed) else 0f
                for (idx in p.itemIdx) {
                    if (dustN + rockN >= 480) break
                    val item = items.getOrNull(idx) ?: continue
                    val ang = TasteTagPlanets.dustAngleOf(item.dateEpochSec.toLong(), p.dustMinSec, p.dustMaxSec)
                    // R321 迎风侧概率丢弃（±0.35 帽内——轨道可读性不撕碎）
                    if (windAsymR321 > 0f) {
                        val keep = StarwindFx.dustKeep01(ang - windAxisR321, windAsymR321)
                        if (galaxyH01(seed, idx * 31 + 509) > keep) continue
                    }
                    // R328 凝缩档（镜像 Canvas——成型态尘埃散布向行星轨道收缩）
                    val coef = if (formingOn) {
                        TasteBirthFx.formingDustRadiusCoef(seed, idx)
                    } else {
                        TasteTagPlanets.dustRadiusCoef(seed, idx)
                    }
                    if (rockMode && rockN < DUST_ROCK_CAP) {
                        // 前景大粒=岩石多面体（CPU 求轨道位 24 粒——三角预算有界）
                        val jr = TasteTagPlanets.keplerOrbitRadius(p, rW, ang) * coef
                        planetOrbitPoint(scx, scy, scz, jr, ang + peri, inc, mpos)
                        val rseed = seed + idx * 31
                        addRock(
                            mpos[0], mpos[1], mpos[2], dotSizeW * 0.9f,
                            spin = t * (0.3f + 0.6f * galaxyH01(rseed, 5)) + galaxyH01(rseed, 9) * 6.2831853f,
                            axisX = galaxyH01(rseed, 11) - 0.5f, axisY = galaxyH01(rseed, 13) - 0.5f, axisZ = galaxyH01(rseed, 17) - 0.5f,
                            cr = dr * 0.72f, cg = dg * 0.72f, cb = db * 0.72f,
                            alpha = (dustA * 0.92f).coerceIn(0f, 1f),
                            lx = scx - mpos[0], ly = scy - mpos[1], lz = scz - mpos[2],
                            ember = 0f, seedRock = rseed.toFloat(),
                        )
                        rockN++
                    } else {
                        addKepVert(ang, coef, dotSizeW, dr, dg, db, dustA * 0.62f)
                        dustN++
                    }
                }
                if (sysIdx >= 0 && kepVertCount > start) {
                    emitImmediate(OP_KEPLER_POINTS, sysIdx, start, kepVertCount - start)
                }
                flushRocks(rockStart)
            }
        }
    }

    /**
     * R399 远档行星微点档（ratio≥[TasteTagPlanets.APPEAR_RATIO] 的「未浮现」态——
     * 旧整系统 return 吞门退役）：**并入母星光晕的行星色微点**（真开普勒屏位
     * 开普勒链零改——与域内装配同一式单源）；亮度=微星三档深度语言（远档=dim
     * 并入光晕读法）；top [LOD_MID_PLANETS] 帽=远档微点与中距 top 集同拍（点预算
     * 纪律：全场系统×K 有界）；深位行星在坡道窗内退场（域内微点=1 的连续收口
     * =门边界零跳变——[TasteTagPlanets.microDotAlpha01] 单源）。
     * **重层全灭照旧**（轨道线/宜居带/尘埃/球身——降级本身省 draw，性能刀收益
     * 零回退）；凌星链零碰（远档零食深判定——预扫同门本就不及此域）。
     */
    private fun buildPlanetMicroDotsR399(
        s: dev.tgfeed.ui.TasteFxStar,
        entry: dev.tgfeed.ui.TastePlanetEntry,
        cam: TasteSpace.SpaceCam,
        focal: Float,
        t: Float,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        scx: Float, scy: Float, scz: Float,
        ratio: Float,
    ) {
        val planets = entry.system.planets
        val maxShow = min(planets.size, TasteTagPlanets.LOD_NEAR_PLANETS)
        if (maxShow <= 0) return
        val distStar = TasteSpace.distTo(cam, scx, scy, scz)
        if (distStar <= TasteSpace.NEAR) return
        val sizeK = focal / distStar
        val starPxRaw = s.bodyR * sizeK
        val starPxMax = minOf(w, h) * TasteSpace.SWALLOW_MAX_R_FRAC
        if (starPxRaw > starPxMax * 3f) return // 深吞没早退（与域内同款——本域实际不可达，诚实门）
        // 恒星屏位（点云锚）：出屏（近裁后/屏界外）即整系统微点免单
        if (!TasteSpace.project(scx, scy, scz, cam, focal, cx, cy, proj, basisArr)) return
        val starSx = proj[0]
        val starSy = proj[1]
        // 屏界裕量=星点云半径（微点并入母星光晕=行星簇与星同屏同界）
        val edgeM = (starPxRaw * 5.5f).coerceAtLeast(lodMicroDotPx)
        if (starSx < -edgeM || starSx > w + edgeM || starSy < -edgeM || starSy > h + edgeM) return
        // R328 成型档坡道参（与域内同拍——深位退场窗随成型态放缓）
        val rampW = if (s.birth?.planetsForming == true) {
            TasteBirthFx.formingRampWidth(TasteTagPlanets.APPEAR_RAMP)
        } else {
            TasteTagPlanets.APPEAR_RAMP
        }
        val microTierA = MICRO_TIER_A[if (distStar < 900f) 0 else if (distStar < 2600f) 1 else 2]
        val seed = (s.chatId % 100_000L).toInt()
        val inc = galaxyOrbitInclination(seed)
        // 坡道窗内深位行星退场途中=全量评值；窗外只扫 top-K（点预算纪律）
        val n = if (ratio < TasteTagPlanets.APPEAR_RATIO + rampW) maxShow
        else min(maxShow, TasteTagPlanets.LOD_MID_PLANETS)
        for (i in 0 until n) {
            val p = planets[i]
            val da = TasteTagPlanets.microDotAlpha01(ratio, i, maxShow, rampW)
            if (da <= 0.01f) continue
            val rW = planetOrbitRadius(p, s.bodyR)
            val peri = TasteTagPlanets.keplerPeriapsis(p)
            val nu = TasteTagPlanets.keplerTheta(p, t)
            val rOrb = TasteTagPlanets.keplerOrbitRadius(p, rW, nu)
            planetOrbitPoint(scx, scy, scz, rOrb, nu + peri, inc, mpos)
            if (!TasteSpace.project(mpos[0], mpos[1], mpos[2], cam, focal, cx, cy, proj, basisArr)) continue
            if (proj[0] < -edgeM || proj[0] > w + edgeM || proj[1] < -edgeM || proj[1] > h + edgeM) continue
            // 微点=行星色单源（与球身同色——与恒星光晕可区分）；尺寸=微点地板
            val dotColor = TasteStarColor.planetColorOf(p.compV, p.compP, p.compA, s.bodyColor, p.tag.hashCode())
            addPoint(
                proj[0], proj[1], lodMicroDotPx, 0.75f,
                ((dotColor shr 16) and 0xFF) / 255f, ((dotColor shr 8) and 0xFF) / 255f,
                (dotColor and 0xFF) / 255f, microTierA * da,
            )
            statPlanetDots++
        }
    }

    /**
     * 行星真球实例装填（任务①——planetBitmapOf 六件套位图整体退役）：
     * 光向=行星→恒星归一世界向量（真相位的光源）；土星环=真 3D 环带 op
     * （倾角=系统倾角+哈希自倾，深度缓冲与母球真穿插）；气态/岩质=程序表面
     * shader 参数旗。
     */
    private fun buildPlanetBody(
        s: dev.tgfeed.ui.TasteFxStar,
        p: TasteTagPlanets.TagPlanet,
        psx: Float, psy: Float,
        wx: Float, wy: Float, wz: Float,
        worldR: Float, rP: Float, appear: Float,
        scx: Float, scy: Float, scz: Float, t: Float, inc: Float,
        /** R320 凌星中（背影剪影——暗球+缘光环，镜像 Canvas 暗盘+1px 亮环） */
        transiting: Boolean = false,
    ) {
        val tagSeed = p.tag.hashCode()
        // R311 行星色=色彩引擎单口（内容构成三票 视频暖/图片冷/音频紫+恒星光染色
        // +tag 哈希抖动——旧「恒星色相+±14° 抖动」一族色公式退役=「千篇一律」病根）
        val color = TasteStarColor.planetColorOf(p.compV, p.compP, p.compA, s.bodyColor, tagSeed)
        val pr = ((color shr 16) and 0xFF) / 255f
        val pg = ((color shr 8) and 0xFF) / 255f
        val pb = (color and 0xFF) / 255f
        // R349 形态线（r341 表行 2）：行星门槛 2.5→[lodSolidFloorPx]+blend 接线
        // （[1.2,1.6)dp 点×(1−k)+行星球×k——能量守恒族同式；点 0.9/球 0.96 满档本就
        // ≤6% 阶差，带内连续交接无新台阶；环带/剪影缘光环=满档才发；R355 dp
        // 化=窗两端帧字段）
        val k = TasteLodPolicy.blend01(rP, lodSolidFloorPx, lodPointCeilPx)
        if (k < 0.99f) {
            // 点形态（blend 带内 ×(1−k) 淡出；R320 凌星档=暗点——背影剪影的
            // 亚像素读法）；R399 尺寸地板=max(rP×2, 微点地板)——亚像素行星
            // =最小可见亮点不蒸发（「画不出门」退役的形态轴兜底）
            val dotSize = (rP * 2f).coerceAtLeast(lodMicroDotPx)
            if (transiting) {
                addPoint(psx, psy, dotSize, 0.75f, 0.03f, 0.04f, 0.06f, appear * 0.94f * (1f - k))
            } else {
                addPoint(psx, psy, dotSize, 0.75f, pr, pg, pb, appear * 0.9f * (1f - k))
            }
        }
        if (k > 0.01f) {
            if (transiting) {
                // ---- R320 凌星剪影（镜像 Canvas）：行星在恒星与相机之间=只见夜面背影
                //      （暗球——色压近黑）+大气折光缘光环（ring quad@rP）；土星环不画
                //      （背影无受光面）——恒星微暗由 scanTransitDepthR320 食深账承担 ----
                var lxT = scx - wx
                var lyT = scy - wy
                var lzT = scz - wz
                val ll = sqrt(lxT * lxT + lyT * lyT + lzT * lzT).coerceAtLeast(1e-6f)
                lxT /= ll; lyT /= ll; lzT /= ll
                addPlanet(
                    wx, wy, wz, worldR, lxT, lyT, lzT, pr * 0.08f, pg * 0.08f, pb * 0.10f,
                    t * (0.10f + 0.22f * galaxyH01(tagSeed, 59)) + galaxyH01(tagSeed, 61) * 6.2831853f,
                    (tagSeed and 0x7FFF).toFloat(), 0f, appear * 0.96f * k,
                )
                statPlanets++
                if (k > 0.99f) addRingQuad(psx, psy, rP, 0xFFE8F0FF.toInt(), appear * 0.55f)
                return
            }
            val gas = galaxyH01(tagSeed, 53) > 0.5f
            val ringed = galaxyH01(tagSeed, 51) < 0.33f
            // 真 3D 平行光（行星→恒星——绕飞真相位/晨昏线的光源向量）
            var lx = scx - wx
            var ly = scy - wy
            var lz = scz - wz
            val ll = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-6f)
            lx /= ll; ly /= ll; lz /= ll
            val spin = t * (0.10f + 0.22f * galaxyH01(tagSeed, 59)) + galaxyH01(tagSeed, 61) * 6.2831853f
            addPlanet(
                wx, wy, wz, worldR, lx, ly, lz, pr, pg, pb,
                spin, (tagSeed and 0x7FFF).toFloat(), if (gas) 1f else 0f, appear * 0.96f * k,
            )
            statPlanets++
            if (ringed && k > 0.99f) {
                // 土星环真 3D 环带：倾角=系统倾角+哈希自倾（±26°）+面内进动角
                // （满档才发——blend 带内随母球淡入后登场的连续读法）
                addRing(
                    wx, wy, wz, worldR,
                    tiltX = inc + (galaxyH01(tagSeed, 55) - 0.5f) * 0.9f,
                    tiltZ = (galaxyH01(tagSeed, 57) - 0.5f) * 0.7f,
                    lx, ly, lz,
                    (tagSeed and 0x7FFF).toFloat(), appear * 0.85f, mode = 0f,
                    cr = pr, cg = pg, cb = pb,
                )
            }
        }
    }

    // ==================== 缓冲装填件（类型切换即冲刷=画家序） ====================

    private fun addPoint(
        x: Float, y: Float, size: Float, coreRatio: Float,
        r: Float, g: Float, b: Float, a: Float,
        coreR: Float = r, coreG: Float = g, coreB: Float = b,
    ) {
        if (pointCount >= MAX_POINTS) return
        switchType(T_POINTS)
        val i = pointCount * POINT_STRIDE
        pointBuf[i] = x
        pointBuf[i + 1] = y
        pointBuf[i + 2] = size
        pointBuf[i + 3] = coreRatio
        pointBuf[i + 4] = r
        pointBuf[i + 5] = g
        pointBuf[i + 6] = b
        pointBuf[i + 7] = a.coerceIn(0f, 1f)
        pointBuf[i + 8] = coreR
        pointBuf[i + 9] = coreG
        pointBuf[i + 10] = coreB
        pointCount++
    }

    private fun addLine(x1: Float, y1: Float, x2: Float, y2: Float, r: Float, g: Float, b: Float, a: Float) {
        if (lineVertCount + 2 > MAX_LINE_VERTS) return
        switchType(T_LINES)
        val i = lineVertCount * LINE_STRIDE
        val aa = a.coerceIn(0f, 1f)
        lineBuf[i] = x1
        lineBuf[i + 1] = y1
        lineBuf[i + 2] = r
        lineBuf[i + 3] = g
        lineBuf[i + 4] = b
        lineBuf[i + 5] = aa
        lineBuf[i + 6] = x2
        lineBuf[i + 7] = y2
        lineBuf[i + 8] = r
        lineBuf[i + 9] = g
        lineBuf[i + 10] = b
        lineBuf[i + 11] = aa
        lineVertCount += 2
    }

    /**
     * 纹理四边形（旋转矩形——局部轴 (±halfW,±halfH) 绕中心旋 [rotDeg]（屏系 y 向下，
     * 视觉与 Canvas nc.rotate 一致）；[offY]=沿局部 +Y 偏心（灯塔光柱锚星缘外用）。
     */
    private fun addQuad(
        texKey: Int, cxQ: Float, cyQ: Float, halfW: Float, halfH: Float,
        offY: Float, rotDeg: Float, r: Float, g: Float, b: Float, a: Float,
    ) {
        if (quadCount >= MAX_QUADS) return
        // 连续同键合并（省 draw call）——不同键先冲刷（仍在 quad 模态内）
        if (pendingQuadCount > 0 && texKey != pendingQuadTex) flushQuads()
        switchType(T_QUADS)
        if (pendingQuadCount == 0) {
            pendingQuadTex = texKey
            pendingQuadStart = quadCount
        }
        pendingQuadCount++
        val rad = Math.toRadians(rotDeg.toDouble()).toFloat()
        val ca = cos(rad)
        val sa = sin(rad)
        val qcx = cxQ + sa * offY
        val qcy = cyQ + ca * offY
        val i = quadCount * 6 * QUAD_STRIDE
        writeQuadVert(i, qcx, qcy, -halfW, -halfH, ca, sa, 0f, 0f, r, g, b, a)
        writeQuadVert(i + QUAD_STRIDE, qcx, qcy, halfW, -halfH, ca, sa, 1f, 0f, r, g, b, a)
        writeQuadVert(i + QUAD_STRIDE * 2, qcx, qcy, halfW, halfH, ca, sa, 1f, 1f, r, g, b, a)
        writeQuadVert(i + QUAD_STRIDE * 3, qcx, qcy, -halfW, -halfH, ca, sa, 0f, 0f, r, g, b, a)
        writeQuadVert(i + QUAD_STRIDE * 4, qcx, qcy, halfW, halfH, ca, sa, 1f, 1f, r, g, b, a)
        writeQuadVert(i + QUAD_STRIDE * 5, qcx, qcy, -halfW, halfH, ca, sa, 0f, 1f, r, g, b, a)
        quadCount++
    }

    private fun writeQuadVert(
        i: Int, cxQ: Float, cyQ: Float, lx: Float, ly: Float,
        ca: Float, sa: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float,
    ) {
        // 屏系 y 向下：局部 (lx,ly) 以 (x'=lx·ca+ly·sa, y'=−lx·sa+ly·ca) 旋转——
        // 与 Canvas nc.rotate(deg) 的视觉转向一致（屏系 y 翻转=旋转方向镜像）
        quadBuf[i] = cxQ + lx * ca + ly * sa
        quadBuf[i + 1] = cyQ - lx * sa + ly * ca
        quadBuf[i + 2] = u
        quadBuf[i + 3] = v
        quadBuf[i + 4] = r
        quadBuf[i + 5] = g
        quadBuf[i + 6] = b
        quadBuf[i + 7] = a.coerceIn(0f, 1f)
    }

    /** 通用环四边形（TEX_RING 细环带纹理峰在 0.905——半轴=rPx/0.905 使环落目标半径） */
    private fun addRingQuad(sx: Float, sy: Float, rPx: Float, colorArgb: Int, alpha: Float) {
        if (rPx <= 0.6f || alpha <= 0.004f) return
        val half = rPx / 0.905f
        addQuad(
            TEX_RING, sx, sy, half, half, 0f, 0f,
            ((colorArgb shr 16) and 0xFF) / 255f, ((colorArgb shr 8) and 0xFF) / 255f, (colorArgb and 0xFF) / 255f, alpha,
        )
    }

    // ==================== R281 新缓冲装填件 ====================

    /** 开普勒系统描述装填（返回槽号——超帽返回 -1=调用方放弃该轨） */
    private fun addKepSys(
        cx: Float, cy: Float, cz: Float,
        peri: Float, incSin: Float, incCos: Float, semiR: Float, ecc: Float,
    ): Int {
        if (kepSysCount >= MAX_KEP_SYS) return -1
        val i = kepSysCount * KEP_SYS_STRIDE
        kepSysBuf[i] = cx; kepSysBuf[i + 1] = cy; kepSysBuf[i + 2] = cz
        kepSysBuf[i + 3] = peri
        kepSysBuf[i + 4] = incSin; kepSysBuf[i + 5] = incCos
        kepSysBuf[i + 6] = semiR; kepSysBuf[i + 7] = ecc
        return kepSysCount++
    }

    /** 开普勒顶点装填（ν/径向系数/世界尺寸/rgba——GPU 轨道求值原料） */
    private fun addKepVert(nu: Float, coef: Float, sizeW: Float, r: Float, g: Float, b: Float, a: Float) {
        if (kepVertCount >= MAX_KEP_VERTS) return
        val i = kepVertCount * KEP_STRIDE
        kepBuf[i] = nu; kepBuf[i + 1] = coef; kepBuf[i + 2] = sizeW
        kepBuf[i + 3] = r; kepBuf[i + 4] = g; kepBuf[i + 5] = b
        kepBuf[i + 6] = a.coerceIn(0f, 1f)
        kepVertCount++
    }

    /** 世界点装填（星团成员球等——GPU 投影近大远小；批边界由调用方 flushWPoints 封口） */
    private fun addWPoint(
        x: Float, y: Float, z: Float, sizeW: Float, coreRatio: Float,
        r: Float, g: Float, b: Float, a: Float,
        coreR: Float = r, coreG: Float = g, coreB: Float = b,
    ) {
        if (wpCount >= MAX_WPOINTS) return
        val i = wpCount * WP_STRIDE
        wpBuf[i] = x; wpBuf[i + 1] = y; wpBuf[i + 2] = z
        wpBuf[i + 3] = sizeW; wpBuf[i + 4] = coreRatio
        wpBuf[i + 5] = r; wpBuf[i + 6] = g; wpBuf[i + 7] = b; wpBuf[i + 8] = a.coerceIn(0f, 1f)
        wpBuf[i + 9] = coreR; wpBuf[i + 10] = coreG; wpBuf[i + 11] = coreB
        wpCount++
    }

    /** 世界点批次封口（一个星团的成员一批 op——调用方在每批末调用） */
    private fun flushWPoints(batchStart: Int) {
        if (wpCount > batchStart) emitImmediate(OP_WPOINTS, batchStart, wpCount - batchStart, 0)
    }

    /** 世界点批次封口（**深度测试版**——opC=1=渲染器开深度测（不写）：黑洞盘粒子被视界真遮挡） */
    private fun flushWPointsDepth(batchStart: Int, depthTest: Boolean) {
        if (wpCount > batchStart) emitImmediate(OP_WPOINTS, batchStart, wpCount - batchStart, if (depthTest) 1 else 0)
    }

    /** 世界三角带顶点装填（彗星尾——TRIANGLE_STRIP 顺序由调用方保证） */
    private fun addStripVert(x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) {
        if (stripVertCount >= MAX_STRIP_VERTS) return
        val i = stripVertCount * STRIP_STRIDE
        stripBuf[i] = x; stripBuf[i + 1] = y; stripBuf[i + 2] = z
        stripBuf[i + 3] = u; stripBuf[i + 4] = v
        stripBuf[i + 5] = r; stripBuf[i + 6] = g; stripBuf[i + 7] = b
        stripBuf[i + 8] = a.coerceIn(0f, 1f)
        stripVertCount++
    }

    /** 行星真球实例装填（PLANET_STRIDE 语义见 companion） */
    private fun addPlanet(
        cx: Float, cy: Float, cz: Float, radius: Float,
        lx: Float, ly: Float, lz: Float,
        br: Float, bg: Float, bb: Float,
        spin: Float, seed: Float, gas: Float, alpha: Float,
    ) {
        if (planetCount >= MAX_PLANETS) return
        val i = planetCount * PLANET_STRIDE
        planetBuf[i] = cx; planetBuf[i + 1] = cy; planetBuf[i + 2] = cz
        planetBuf[i + 3] = radius
        planetBuf[i + 4] = lx; planetBuf[i + 5] = ly; planetBuf[i + 6] = lz
        planetBuf[i + 7] = br; planetBuf[i + 8] = bg; planetBuf[i + 9] = bb
        planetBuf[i + 10] = spin; planetBuf[i + 11] = seed
        planetBuf[i + 12] = gas; planetBuf[i + 13] = alpha.coerceIn(0f, 1f)
        emitImmediate(OP_PLANET, planetCount, 0, 0)
        planetCount++
    }

    /**
     * 真 3D 环带实例装填（RING_STRIDE 语义见 companion；mode 0=土星环 1=吸积盘
     * 2=致密遗迹环——R350 窄带档；[precRad]=进动角弧度，**槽 15 预留位启用**：
     * 缺省 0=既有调用（土星环/吸积盘）rotY(0) 逐位恒等——R321 加字段纪律照抄
     * （全槽位无条件写契约保持）；mode=2 遗迹环=[TasteTerminalFx
     * .remnantRingPrecessDeg] 换弧度真值）。
     */
    private fun addRing(
        cx: Float, cy: Float, cz: Float, planetR: Float,
        tiltX: Float, tiltZ: Float,
        lx: Float, ly: Float, lz: Float,
        seed: Float, alpha: Float, mode: Float,
        cr: Float, cg: Float, cb: Float,
        precRad: Float = 0f,
    ) {
        if (ringCount >= MAX_RINGS) return
        val i = ringCount * RING_STRIDE
        ringBuf[i] = cx; ringBuf[i + 1] = cy; ringBuf[i + 2] = cz
        ringBuf[i + 3] = planetR
        ringBuf[i + 4] = tiltX; ringBuf[i + 5] = tiltZ
        ringBuf[i + 6] = lx; ringBuf[i + 7] = ly; ringBuf[i + 8] = lz
        ringBuf[i + 9] = seed; ringBuf[i + 10] = alpha.coerceIn(0f, 1f)
        ringBuf[i + 11] = mode
        ringBuf[i + 12] = cr; ringBuf[i + 13] = cg; ringBuf[i + 14] = cb
        // 槽 15（R350 启用=进动角弧度；0=恒等——R321 无条件写纪律保持）
        ringBuf[i + 15] = precRad
        emitImmediate(OP_RING, ringCount, 0, 0)
        ringCount++
    }

    /** 岩石实例装填（ROCK_STRIDE 语义见 companion；同批连续实例=一次 instanced draw） */
    private fun addRock(
        x: Float, y: Float, z: Float, scale: Float,
        spin: Float, axisX: Float, axisY: Float, axisZ: Float,
        cr: Float, cg: Float, cb: Float, alpha: Float,
        lx: Float, ly: Float, lz: Float,
        ember: Float, seedRock: Float,
    ) {
        if (rockCount >= MAX_ROCKS) return
        val i = rockCount * ROCK_STRIDE
        rockBuf[i] = x; rockBuf[i + 1] = y; rockBuf[i + 2] = z; rockBuf[i + 3] = scale
        rockBuf[i + 4] = axisX; rockBuf[i + 5] = axisY; rockBuf[i + 6] = axisZ; rockBuf[i + 7] = spin
        rockBuf[i + 8] = cr; rockBuf[i + 9] = cg; rockBuf[i + 10] = cb; rockBuf[i + 11] = alpha.coerceIn(0f, 1f)
        rockBuf[i + 12] = lx; rockBuf[i + 13] = ly; rockBuf[i + 14] = lz; rockBuf[i + 15] = ember
        rockBuf[i + 16] = seedRock
        rockCount++
        statRocks++
    }

    /** 岩石批次封口（连续装填的实例落成一次 instanced draw op） */
    private fun flushRocks(batchStart: Int) {
        if (rockCount > batchStart) emitImmediate(OP_ROCK, batchStart, rockCount - batchStart, 0)
    }

    /**
     * 体积星云实例装填（NEB_STRIDE 语义见 companion；**槽 9 频率标度恒 1f**
     * ——R388 刀2 雾族行既有基因恒等：本批病灶=复合体行，雾族远档形态另案）
     */
    private fun addNebula(sx: Float, sy: Float, halfPx: Float, seed: Float, t: Float, r: Float, g: Float, b: Float, a: Float) {
        if (nebCount >= MAX_NEBULAS) return
        val i = nebCount * NEB_STRIDE
        nebBuf[i] = sx; nebBuf[i + 1] = sy; nebBuf[i + 2] = halfPx
        nebBuf[i + 3] = seed; nebBuf[i + 4] = t
        nebBuf[i + 5] = r; nebBuf[i + 6] = g; nebBuf[i + 7] = b
        nebBuf[i + 8] = a.coerceIn(0f, 1f)
        nebBuf[i + 9] = 1f // R388 刀2：雾族行频率标度恒 1（uFreqScale=1=既有基因）
        emitImmediate(OP_NEBULA, nebCount, 0, 0)
        nebCount++
    }

    /**
     * R373 复合体行实例装填（雾族同管线独立预算行——**写 nebBuf 的 [MAX_NEBULAS]
     * 槽后段**：opA=MAX_NEBULAS+序号，TasteGlRenderer OP_NEBULA 回放按 opA×
     * NEB_STRIDE 寻址零改动即通吃两行；独立计数 [nebCpxCount] 帽
     * [MAX_NEBULA_CPX]=16——雾行 24 帽零挤压（复合体先画在雾前也不抢雾的槽：
     * 两行分段计数=画家序与预算序解耦）。R388 刀2：[freqScale]=丝缕频率标度
     * （槽 9→uFreqScale——clamp 值由调用方单源算好传入，本口只装填零第二份
     * 判定）。
     */
    private fun addNebulaCpx(
        sx: Float, sy: Float, halfPx: Float, seed: Float, t: Float,
        r: Float, g: Float, b: Float, a: Float, freqScale: Float,
    ) {
        if (nebCpxCount >= MAX_NEBULA_CPX) return
        val i = (MAX_NEBULAS + nebCpxCount) * NEB_STRIDE
        nebBuf[i] = sx; nebBuf[i + 1] = sy; nebBuf[i + 2] = halfPx
        nebBuf[i + 3] = seed; nebBuf[i + 4] = t
        nebBuf[i + 5] = r; nebBuf[i + 6] = g; nebBuf[i + 7] = b
        nebBuf[i + 8] = a.coerceIn(0f, 1f)
        nebBuf[i + 9] = freqScale // R388 刀2：丝缕频率标度（1=既有设计基因原样）
        emitImmediate(OP_NEBULA, MAX_NEBULAS + nebCpxCount, 0, 0)
        nebCpxCount++
    }

    /**
     * 事件视界纯黑真球（黑洞——复用真球网格+shader：三段色全黑/颗粒零=纯黑体；
     * 深度写开=吸积盘后缘真遮挡的几何闸）。R349：alpha 参数=blend 带淡入
     * （r341 表行 8 脚注³——「blend 期球 alpha×k 淡入，成本恒低」；缺省 1=既有
     * 调用恒等）。
     */
    private fun addHorizon(wx: Float, wy: Float, wz: Float, worldR: Float, alpha: Float = 1f) {
        if (sphereCount >= MAX_SPHERES) return
        switchType(T_SPHERE)
        val i = sphereCount * SPHERE_STRIDE
        sphereBuf[i] = wx; sphereBuf[i + 1] = wy; sphereBuf[i + 2] = wz
        sphereBuf[i + 3] = worldR
        sphereBuf[i + 4] = alpha.coerceIn(0f, 1f)
        for (k in 5..13) sphereBuf[i + k] = 0f // 三段色全黑
        sphereBuf[i + 14] = 0f // 颗粒零
        sphereBuf[i + 15] = 0f
        sphereBuf[i + 16] = 0f
        sphereBuf[i + 17] = 0f
        sphereBuf[i + 18] = 0f
        sphereBuf[i + 19] = 0f
        sphereBuf[i + 20] = 0f
        // R321 潮汐槽清零（恒等值——视界球零形变；防缓冲复用残留同 addSphere）
        sphereBuf[i + 22] = 0f
        sphereBuf[i + 23] = 0f
        sphereBuf[i + 24] = 0f
        sphereBuf[i + 25] = 0f
        sphereBuf[i + 26] = 0f
        sphereCount++
        statSphereStars++
        statSphereFull++ // R349 全模路径计数（视界=全模球网格——探针口径一致）
        emitOp(OP_SPHERE, sphereCount - 1, 0, 0)
    }

    /**
     * 镜头光斑源收集（top3 亮星插入排序——强度=(r/90 钳)×alpha；正对度=
     * 1−|ndc|/1.2 钳——合成 shader 的「正对亮星」门）。
     */
    private fun collectFlare(sx: Float, sy: Float, alpha: Float, r: Float, temp: Int, w: Float, h: Float) {
        val strength = (r / 90f).coerceIn(0f, 1f) * alpha
        if (strength < 0.10f) return
        val score = alpha * r
        if (flareCount >= MAX_FLARES && score <= flareScore[MAX_FLARES - 1]) return
        // 插入排序（≤3 槽）
        var slot = if (flareCount < MAX_FLARES) flareCount else MAX_FLARES - 1
        if (flareCount < MAX_FLARES) flareCount++
        while (slot > 0 && flareScore[slot - 1] < score) {
            flareScore[slot] = flareScore[slot - 1]
            System.arraycopy(flareBuf, (slot - 1) * FLARE_STRIDE, flareBuf, slot * FLARE_STRIDE, FLARE_STRIDE)
            slot--
        }
        flareScore[slot] = score
        val nx = 2f * sx / w - 1f
        val ny = 1f - 2f * sy / h
        val centered = (1f - sqrt(nx * nx + ny * ny) / 1.2f).coerceIn(0f, 1f)
        val core = GlPalette.coreColorOf(temp)
        val i = slot * FLARE_STRIDE
        flareBuf[i] = nx; flareBuf[i + 1] = ny
        flareBuf[i + 2] = ((core shr 16) and 0xFF) / 255f
        flareBuf[i + 3] = ((core shr 8) and 0xFF) / 255f
        flareBuf[i + 4] = (core and 0xFF) / 255f
        flareBuf[i + 5] = strength
        flareBuf[i + 6] = centered
    }

    /**
     * 即时 op（R281 新族共用——无攒批语义：先冲刷既有点/线/四边形攒批再落 op，
     * 画家序=添加序与 Canvas 逐位同序的纪律平移）。
     */
    private fun emitImmediate(type: Int, a: Int, b: Int, c: Int) {
        switchType(T_IMMED)
        emitOp(type, a, b, c)
    }

    // ==================== op 流与冲刷 ====================

    /**
     * 类型切换（画家序记录核心）：换类型先把上一类型的攒批落成 op——
     * 点/线/四边形/球在 op 流中的顺序=添加顺序（与 Canvas 逐位同序）。
     */
    private fun switchType(t: Int) {
        if (t == lastType) return
        when (lastType) {
            T_POINTS -> if (pointCount > pointsEmitted) {
                emitOp(OP_POINTS, pointsEmitted, pointCount - pointsEmitted, 0)
                pointsEmitted = pointCount
            }
            T_LINES -> if (lineVertCount > linesEmitted) {
                emitOp(OP_LINES, linesEmitted, lineVertCount - linesEmitted, 0)
                linesEmitted = lineVertCount
            }
            T_QUADS -> flushQuads()
            T_LOW_SPHERE -> flushLowSpheresR349()
        }
        lastType = t
    }

    /** R349 低模球攒批发射（连续段落成一次 instanced draw op——画家序换类型冲刷） */
    private fun flushLowSpheresR349() {
        if (lowSphereCount > lowEmitted) {
            emitOp(OP_SPHERE_I, lowEmitted, lowSphereCount - lowEmitted, 0)
            lowEmitted = lowSphereCount
        }
    }

    private fun flushQuads() {
        if (pendingQuadCount > 0) {
            emitOp(OP_QUADS, pendingQuadTex, pendingQuadStart, pendingQuadCount)
            pendingQuadCount = 0
        }
    }

    /** 帧尾冲刷（switchType 纪律下只剩最后一个类型有攒批） */
    private fun flushPending() {
        when (lastType) {
            T_POINTS -> if (pointCount > pointsEmitted) {
                emitOp(OP_POINTS, pointsEmitted, pointCount - pointsEmitted, 0)
                pointsEmitted = pointCount
            }
            T_LINES -> if (lineVertCount > linesEmitted) {
                emitOp(OP_LINES, linesEmitted, lineVertCount - linesEmitted, 0)
                linesEmitted = lineVertCount
            }
            T_QUADS -> flushQuads()
            T_LOW_SPHERE -> flushLowSpheresR349()
        }
        lastType = T_NONE
    }

    private fun emitOp(type: Int, a: Int, b: Int, c: Int) {
        if (opCount >= MAX_OPS) return
        opType[opCount] = type
        opA[opCount] = a
        opB[opCount] = b
        opC[opCount] = c
        opCount++
    }

    /** 帧装配完成后读选中星名签（叠加层消费） */
    fun selectedLabel(): SelectedLabel? = selLabel

    // ==================== 小件 ====================

    /** 微星合并适用域（镜像 canMicroMerge：频道类天体；特殊天体不并） */
    private fun canMicroMerge(kind: CelestialKind): Boolean = when (kind) {
        CelestialKind.STAR, CelestialKind.PROTOSTAR, CelestialKind.RED_GIANT, CelestialKind.WHITE_DWARF -> true
        else -> false
    }

    /** 行星层适用域（镜像 isChannelStarKind：六态恒星+黑洞） */
    private fun isChannelStarKind(kind: CelestialKind): Boolean = when (kind) {
        CelestialKind.STAR, CelestialKind.PROTOSTAR, CelestialKind.RED_GIANT,
        CelestialKind.WHITE_DWARF, CelestialKind.SUPERNOVA, CelestialKind.BLACK_HOLE,
        -> true
        else -> false
    }

    /** 原地快排（深度降序——R345 前镜像 Canvas TasteFlightRenderer.sortDesc：小段插排+中位三取样；退役后本件为唯一实现） */
    private fun sortDesc(idx: IntArray, key: FloatArray, low: Int, high: Int) {
        var lo = low
        var hi = high
        while (lo < hi) {
            if (hi - lo < 10) {
                for (i in lo + 1..hi) {
                    val v = idx[i]
                    val kv = key[v]
                    var j = i - 1
                    while (j >= lo && key[idx[j]] < kv) {
                        idx[j + 1] = idx[j]
                        j--
                    }
                    idx[j + 1] = v
                }
                return
            }
            val mid = (lo + hi) / 2
            if (key[idx[mid]] > key[idx[lo]]) { val tmp = idx[mid]; idx[mid] = idx[lo]; idx[lo] = tmp }
            if (key[idx[hi]] > key[idx[lo]]) { val tmp = idx[hi]; idx[hi] = idx[lo]; idx[lo] = tmp }
            if (key[idx[mid]] > key[idx[hi]]) { val tmp = idx[mid]; idx[mid] = idx[hi]; idx[hi] = tmp }
            val pivot = key[idx[hi]]
            var i = lo - 1
            var j = hi
            while (true) {
                do { i++ } while (key[idx[i]] > pivot)
                do { j-- } while (key[idx[j]] < pivot)
                if (i >= j) break
                val tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp
            }
            val tmp = idx[i]; idx[i] = idx[hi]; idx[hi] = tmp
            if (i - lo < hi - i) {
                sortDesc(idx, key, lo, i - 1)
                lo = i + 1
            } else {
                sortDesc(idx, key, i + 1, hi)
                hi = i - 1
            }
        }
    }
}

/** GL 名签候选（叠加层 placeTasteLabels 的原料——与 ShareCard.TasteLabelCand 同构解耦） */
internal class GlLabelCand(
    val sx: Float,
    val sy: Float,
    val r: Float,
    val text: String,
    val alpha: Int,
)
