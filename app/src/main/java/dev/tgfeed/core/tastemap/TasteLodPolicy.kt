package dev.tgfeed.core.tastemap

/**
 * R349 天体全员 3D 化 LOD 政策件（r341 设计定稿的实施单口——**纯 Kotlin 零
 * Android 依赖**，JVM 单测可跑；全仓「屏半径→形态档」的**唯一判定出处**，
 * AGENT-DISCIPLINE §一类收敛：同样的门槛数字不许在多个文件各写一份）。
 *
 * R355 **阈值 DPI 化**（用户 08:0x 实锤「小米上根本不是 3D 效果」——根因=形态线
 * 阈值用裸像素：高密度屏（400dpi）上 2px 视觉极小、低密度屏轻松过线=设备间
 * 视觉不一致+3D 出现时机失真）：三常数改 **dp 基准**——「分辨出形状=3D」的
 * 「形状」=**视觉尺寸非设备像素**（宪法判词 dp 口径重述）；密度换算**单口**
 * （[pointCeilPx]/[effectiveSolidFloor]/[effectiveLowMeshMax]——dp×density×bias
 * →px，一处乘 density 全族消费）；真机密度由渲染器读 Android
 * Resources.displayMetrics.density 推入（GlFrameBuilder.lodDensity——帧首一次
 * 换算入帧字段），本件零 Android 依赖红线不动。
 *
 * 政策宪法（r341 §0 / 宪法二章 10 条——用户 20:1x 拍板）：
 * **只要屏幕上能分辨出形状的天体就是 3D 体；扁圈只属于亚像素点档**——
 *  - 亚像素（<1.2dp）＝安静色点（真实远景＝点——R256 判词在点档侧继续成立；
 *    「亚像素」的视觉重述=肉眼不可分辨圆盘的半径域）；
 *  - 1.2~1.6dp＝blend 带（点×(1−k)+3D 真身×k 双态叠加——无中间形态；**R355
 *    真机终裁：blend 只留点淡出侧——球=真 3D 球面从第一像素在场（ambient 平涂
 *    底光整体退役——SPHERE_SHADE_BODY 无平盘路径）**）；
 *  - ≥1.6dp＝**3D 真身实例**（mesh+光照+自转；<4dp 低模 8×6 / ≥4dp 全模 24×16
 *    ——用户终裁「全模型门槛降 4dp」）；
 *  - **中距离扁平档（sprite/圆圈/盘/2D 弧线冒充 3D 体）彻底消灭**。
 *
 * 两条判定线正交共存（r341 §0）：**形态线**（本件管辖——与天体类别无关全类
 * 同尺）×**数量线**（TasteTagPlanets.LOD_* 既有口径零改——几颗行星/几粒尘埃）。
 *
 * crossfade 参数族地位（r341 §2.3 对表协议——R339b 先落地收编）：本件=点→3D
 * 入口段 crossfade 的**唯一一套**（R339b 的 24~30 贴脸窗随恒星球门槛 30→2 结构性
 * 空置——窗位重锚 [POINT_CEIL,SOLID_FLOOR] dp 口径，smoothstep C¹ 曲线族+0.92
 * 能量守恒式全族平移零第二套；[TasteStarGlow] 的 crossfade 族翻为对本件的委托）。
 * r341 §5.2 物理立规：**resolved 天体无芒无闪**——衍射芒/闪烁是未分辨点源的
 * 镜头/大气效应，消费方（星芒/微闪/点核 alpha）一律乘 [pointFadeK] 退场。
 *
 * 帧预算回退序（r341 §4.3 三级——**纯函数带偏置参数，状态由渲染器持有**；
 * **R376 重排为最小视觉伤害优先序**）：①降模 [effectiveLowMeshMax]（meshBias→∞=
 * 全模档整体降 8×6 低模，三角量砍 8/9——**几何降级零掉档，球全在场**=R376 L1）
 * →②③实例帽收缩（渲染器侧收 96→64→32——最小屏径优先出球籍回点档，最小球先
 * 让位=R376 L2/L3）→④阈值上抬 [effectiveSolidFloor]（bias×1.5：resolvable 下限
 * 上移，带内天体经 blend 连续回点档=**形态降级最后手段**（R376 L4）——旧序①
 * 置首=用户 11:2x 实锤「3D 模型进来时是对的，过一会儿 3D 样式消失」时序病根：
 * 慢帧单窗即抬 floor 抬掉中距球整带；R376 升级加连续 2 窗门+形态降级压到 L4）。
 * 回退全程 statLodLine 探针记账（bias/帽/密度当前值）。
 *
 * 工程纪律：全函数零分配纯浮点、确定性（零 Random 零系统钟）；阈值即政策
 * （改数=改政策——探针口径与单测锚随行）；**px 阈值一律显式传参消费**
 * （缺省=mdpi 恒等档 density=1——JVM 锚/无密度委托方专用；主渲染路径传帧
 * 字段——R355WiringSourceTest 源锚锁死渲染器内禁裸缺省调用）。
 */
object TasteLodPolicy {

    // ==================== ① 形态线阈值（dp 基准——用户拍板数，全类同尺） ====================

    /** 点档上限（dp）：亚像素安静色点（微星并点线——r341 §2.2 表「<1.5px＝点档」的
     *  dp 视觉重述；1.2dp=肉眼刚分辨圆盘的半径阈——R355 标定论证见笔记 §1.1）。 */
    const val POINT_CEIL_DP = 1.2f

    /** 3D 全权重下限（dp）：crossfade 终点/blend01=1 的形态线（r341 §2.2 表
     *  「≥2px＝3D 真身」的 dp 视觉重述——「分辨出形状」=1.6dp 半径；400dpi 上
     *  =4px——旧 2px@400dpi 视觉仅 0.8dp=小米实锤病根）。 */
    const val SOLID_FLOOR_DP = 1.6f

    /** 低模球上界（dp）：<[此值] 低模 8×6；≥此值 全模 24×16（r341 §2.1 mesh 档
     *  ——用户终裁「全模型门槛降 4dp」）。 */
    const val LOWMESH_MAX_DP = 4.0f

    /**
     * R399 微点直径地板下限（**裸像素**）：与尘埃档 [DUST 最小点]（TasteTagPlanets
     * 私有 DUST_R_MIN_PX=1.1 半径=2.2 直径，「远档也可见的最小点」）同拍的保底
     * ——dp 线在小密度屏塌缩时由它兜住「最小可见」（见 [microDotPx]）。
     */
    const val MICRO_DOT_MIN_PX = 2.2f

    /**
     * 密度换算单口（R355——**全帧唯一一处乘 density**）：Android
     * displayMetrics.density 真值域 ldpi(0.75)~xxhdpi+(3.5) 防呆钳 [0.75,4.0]
     * （域外/缺省收敛 mdpi 侧——密度缺失时阈值不塌零）。
     */
    private fun densityScale(density: Float): Float = density.coerceIn(0.75f, 4f)

    // ==================== ② 帧首单口换算（dp→px——一处换算全族消费） ====================

    /**
     * 点档上限 px（[POINT_CEIL_DP]×density——帧装配一次入帧字段；bias 不动
     * 点档上限：形态线换轨+数量线正交，r341 §0）。
     */
    fun pointCeilPx(density: Float): Float = POINT_CEIL_DP * densityScale(density)

    /**
     * R399 行星微点直径地板（px）：`max([MICRO_DOT_MIN_PX], [pointCeilPx])`——
     * 「最小可见亮点」的形态线口径（1.2dp=肉眼刚分辨圆盘的半径阈复用为微点直径；
     * 低密度屏由裸像素下限兜底=与微星/尘埃点同族语言）。消费=行星 count 轴微点腿
     * （GlFrameBuilder 帧字段 [lodMicroDotPx]——build 首算）+ buildPlanetBody 点腿
     * 尺寸地板（亚像素行星不蒸发）。判词引 r399_notes §2。
     */
    fun microDotPx(density: Float): Float = maxOf(MICRO_DOT_MIN_PX, pointCeilPx(density))

    /**
     * effective solid floor（回退序①——r341 §4.3）：bias≥1 阈值上抬
     * （1.6dp×density×1.5：resolvable 下限上移，带内天体经 blend 连续回点档
     * =诚实降级；bias=1 恒等常态）。恢复帧率后渲染器缓降回 1（非突变——
     * LOD 连续性由 blend 保证）。
     */
    fun effectiveSolidFloor(density: Float, bias: Float): Float =
        SOLID_FLOOR_DP * densityScale(density) * bias.coerceAtLeast(1f)

    /**
     * effective low-mesh 线（回退序②——r341 §4.3）：全模线随 meshBias 上抬
     * （×∞=全模档整体降 8×6 低模——三角量砍 8/9，贴脸质感暂损=诚实降级；
     * meshBias=1 恒等常态）。
     */
    fun effectiveLowMeshMax(density: Float, meshBias: Float): Float =
        LOWMESH_MAX_DP * densityScale(density) * meshBias.coerceAtLeast(1f)

    // ==================== ③ 形态档 id（互斥完备——单测锚） ====================

    /** 点档（<POINT_CEIL——安静色点/微星并点） */
    const val FORM_POINT = 0

    /** blend 带（[POINT_CEIL, SOLID_FLOOR)——点×(1−k)+3D×k 双态叠加） */
    const val FORM_BLEND = 1

    /** 3D 真身（≥SOLID_FLOOR——低模/全模由 [meshTierOf] 细分两档） */
    const val FORM_SOLID = 2

    /** mesh 低模档 id（r341 §2.1 MESH_LOW=0：8×6 网格） */
    const val MESH_LOW = 0

    /** mesh 全模档 id（r341 §2.1 MESH_FULL=1：24×16 UV 球） */
    const val MESH_FULL = 1

    // ==================== ④ crossfade 曲线（R339 族共用——唯一一套） ====================

    /**
     * crossfade 因子（rPx→0..1）：smoothstep（C¹ 连续——穿越带时形态交接速度无
     * 跳变；r341 §5.1「无状态机无迟滞」——半径抖动落在带内只引起两态权重微摆）。
     * 窗外恒 0/1（点档/3D 档两形态零污染）；[solidFloorPx]=回退序①的 effective
     * 阈值（带随 bias 拉宽=拉宽回点档的单旋钮——r341 §5.1）；[pointCeilPx]=窗
     * 下沿（帧字段——R355 密度档）。**缺省=mdpi 恒等档**（density=1 时 dp≡px
     * ——JVM 锚/无密度委托方；主渲染路径必须显式传帧字段——R355 源锚）。
     */
    fun blend01(rPx: Float, solidFloorPx: Float = SOLID_FLOOR_DP, pointCeilPx: Float = POINT_CEIL_DP): Float {
        if (rPx <= pointCeilPx) return 0f
        if (rPx >= solidFloorPx) return 1f
        val u = (rPx - pointCeilPx) / (solidFloorPx - pointCeilPx)
        return u * u * (3f - 2f * u)
    }

    /**
     * 点形态退场系数（=1−[blend01]——r341 §2.1 pointFadeK；与 R339b
     * TasteStarGlow.pointFormAlpha 同义已单源）：芒/闪烁/点形态 alpha 一律乘此
     * 系数退场（resolved 天体无芒无闪——r341 §5.2 物理立规）。**R355 终裁：
     * blend 只留点淡出侧**（本系数=交接唯一存活侧——球=真 3D 即刻生效）。
     */
    fun pointFadeK(rPx: Float, solidFloorPx: Float = SOLID_FLOOR_DP, pointCeilPx: Float = POINT_CEIL_DP): Float =
        1f - blend01(rPx, solidFloorPx, pointCeilPx)

    /**
     * 形态档（rPx→[FORM_POINT]/[FORM_BLEND]/[FORM_SOLID] 三态互斥——r341 §6.2
     * 四案（点/带/低模/全模）的形态半边，mesh 半边=[meshTierOf]）。
     */
    fun formTierOf(rPx: Float, solidFloorPx: Float = SOLID_FLOOR_DP, pointCeilPx: Float = POINT_CEIL_DP): Int = when {
        rPx < pointCeilPx -> FORM_POINT
        rPx < solidFloorPx -> FORM_BLEND
        else -> FORM_SOLID
    }

    /**
     * mesh 档（3D 档内细节档：rPx≥[lowMeshMaxPx]=全模 24×16，否则低模 8×6——
     * r341 §2.2 表「低模球<8px→UV 球≥8px」的 dp 重述（≥4dp 全模——用户终裁）；
     * [lowMeshMaxPx]=回退序② effective 线（bias→∞=全低模））。
     */
    fun meshTierOf(rPx: Float, lowMeshMaxPx: Float = LOWMESH_MAX_DP): Int =
        if (rPx >= lowMeshMaxPx) MESH_FULL else MESH_LOW
}
