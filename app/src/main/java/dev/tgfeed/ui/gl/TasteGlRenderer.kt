package dev.tgfeed.ui.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.ui.TasteFlightFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * R277 GL 场景渲染器（GLSurfaceView.Renderer）· **R281 P2 超越期扩建**：
 *
 *  - **帧输入**：[frameProvider]（Compose 侧注入——与 Canvas 路径同一帧组装段，
 *    TasteFlightFrame 契约不变）；GL 线程拉取→[GlFrameBuilder] 装配→上传→回放；
 *  - **EGL 丢失重建纪律**：全部 GL 句柄（着色器/VBO/纹理/网格/FBO 后处理组）
 *    只活在 GL 线程，onSurfaceCreated 全量重建——纹理缓存（texMap）随上下文清空
 *    重烘（烘焙件=纯函数 IntArray，零资产损失）；
 *  - **帧零分配纪律的 GL 平移**：VBO 初始化期 glBufferData 预分配满帽，帧路径只
 *    glBufferSubData；着色器只初始化期编译；FloatBuffer 直接缓冲常驻；
 *  - **R281 后处理**（任务③）：场景渲进 [GlPostFx] 场景 FBO（带深度 RB——真球/
 *    环带/岩石真穿插的几何闸）→ 亮部提取 → quarter 高斯模糊 → bloom+镜头光斑
 *    合成上屏；
 *  - **R281 深度状态机**（三态）：0=关（点/线/四边形/日冕/星云/条带——Canvas
 *    叠加语义）/1=测+写（真球/行星/视界/岩石）/2=测不写（环带）；
 *  - **崩溃上报**：onDrawFrame 全体 try/catch——异常即 [onFatal]（一次）+自闭；
 *  - **名签发布**：装配产出的名签候选拷成不可变快照 @Volatile 换装。
 */
internal class TasteGlRenderer(
    @Volatile var frameProvider: () -> TasteFlightFrame?,
    private val onFatal: (Throwable) -> Unit,
    private val onHealthyMilestone: () -> Unit,
) : GLSurfaceView.Renderer {

    private val builder = GlFrameBuilder()
    private val postFx = GlPostFx()

    /** 名签快照（GL 线程写/主线程读——@Volatile 原子换装） */
    @Volatile
    var labelSnapshot: List<GlLabelCand> = emptyList()
        private set

    @Volatile
    var selLabelSnapshot: GlFrameBuilder.SelectedLabel? = null
        private set

    /** GL 侧帧统计（探针机读：帧计数/末帧装配分解/自杀标记） */
    @Volatile
    var frameSeq = 0L
        private set

    @Volatile
    private var disabled = false
    private var fatalReported = false

    // ---- GL 句柄（全在 GL 线程，onSurfaceCreated 重建） ----
    private var progPoint = 0
    private var progLine = 0
    private var progQuad = 0
    private var progSphere = 0
    private var progCorona = 0
    private var progSky = 0
    private var progKepler = 0
    private var progWPoint = 0
    private var progStrip = 0
    private var progPlanet = 0
    private var progRing = 0
    private var progRock = 0
    private var progNebula = 0
    private var progSphereI = 0 // R349 低模球 instancing（SPHERE_I——2~8px 批档）
    private var vboPoint = 0
    private var vboLine = 0
    private var vboQuad = 0
    private var vboSphere = 0
    private var vboUnitQuad = 0
    private var iboSphere = 0
    private var sphereIndexCount = 0
    // R349 低模球三件（mesh/IBO/实例——VBO 句柄预算清点：onSurfaceCreated 生成
    // 17 个（原 14+新 3），映射位见下三行；EGL 丢失重建随上下文全量重生成）
    private var vboSphereLod = 0
    private var iboSphereLod = 0
    private var lodSphereIndexCount = 0
    private var vboSphereInst = 0
    private var vboSky = 0
    private var vboKep = 0
    private var vboWp = 0
    private var vboStrip = 0
    private var vboRockMesh = 0
    private var vboRockInst = 0
    private var vboRing = 0
    private var iboRing = 0
    private var ringIndexCount = 0
    private val texMap = HashMap<Int, Int>()

    // R349 SPHERE_I uniform 位（uVP 仅 VS/uCamPos 仅 FS——零跨阶段同名，Mali 对齐
    // 纪律结构性满足；GLSL 名见 GlShaders.SPHERE_I_VS/FS）
    private var uVpSphereI = -1
    private var uCamPosSphereI = -1

    /** R319 CMB 纹理当前种子（帧字段 cmbSeedFp——变更窗才重传；MIN_VALUE=未传） */
    private var cmbTexSeedR319 = Long.MIN_VALUE

    // uniform 位置缓存（初始化期一次查定）
    private var uViewportPoint = -1
    private var uViewportLine = -1
    private var uViewportQuad = -1
    private var uTexQuad = -1
    private var uVpSphere = -1
    private var uCenterSphere = -1
    private var uRadiusSphere = -1
    private var uCamPosSphere = -1
    private var uCoreSphere = -1
    private var uStarSphere = -1
    private var uEdgeSphere = -1
    private var uAlphaSphere = -1
    private var uGrainSphere = -1
    private var uSeedSphere = -1
    private var uSpinSphere = -1
    private var uTimeSphere = -1
    private var uSpotSphere = -1
    private var uFlameSphere = -1
    private var uHotSphere = -1
    private var uLimbSphere = -1 // R311：临边昏暗系数（色温从动）
    // R321 潮汐形变 uniform（契约跟改申报件——SPHERE_VS uTideAxis/uTideGrow；
    // 缺省 0=恒等式，每实例上传防状态残留）
    private var uTideAxisSphere = -1
    private var uTideGrowSphere = -1
    private var uViewportCorona = -1
    private var uCenterCorona = -1
    private var uHalfCorona = -1
    private var uCoreCorona = -1
    private var uAlphaCorona = -1
    private var uSeedCorona = -1
    private var uTimeCorona = -1
    private var uFlameCorona = -1 // R311：日珥拱活动烈度
    // R281 新程序 uniform 位
    private var uVpSky = -1
    private var uCamPosSky = -1
    private var uTimeSky = -1
    private var uVpKep = -1
    private var uCenterKep = -1
    private var uPeriKep = -1
    private var uIncSinKep = -1
    private var uIncCosKep = -1
    private var uSemiRKep = -1
    private var uEccKep = -1
    private var uFocalKep = -1
    private var uVpWp = -1
    private var uFocalWp = -1
    private var uVpStrip = -1
    private var uTimeStrip = -1
    private var uSeedStrip = -1
    private var uVpPlanet = -1
    private var uCenterPlanet = -1
    private var uRadiusPlanet = -1
    private var uCamPosPlanet = -1
    private var uLightPlanet = -1
    private var uBasePlanet = -1
    private var uSeedPlanet = -1
    private var uSpinPlanet = -1
    private var uGasPlanet = -1
    private var uAlphaPlanet = -1
    private var uVpRing = -1
    private var uCenterRing = -1
    private var uRadiusRing = -1
    private var uTiltXRing = -1
    private var uTiltZRing = -1
    private var uCamPosRing = -1
    private var uLightRing = -1
    private var uColorRing = -1
    private var uPlanetRRing = -1
    private var uAlphaRing = -1
    private var uSeedRing = -1
    private var uTimeRing = -1
    private var uModeRing = -1
    private var uPrecRing = -1 // R350 遗迹环进动角（槽 15——0=恒等）
    private var uVpRock = -1
    private var uCamPosRock = -1
    private var uTimeRock = -1
    private var uViewportNeb = -1
    private var uCenterNeb = -1
    private var uHalfNeb = -1
    private var uColorNeb = -1
    private var uAlphaNeb = -1
    private var uSeedNeb = -1
    private var uTimeNeb = -1

    private var viewW = 0
    private var viewH = 0

    // ---- 常驻直接缓冲（帧零分配：容量满帽一次分配） ----
    private val pointNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_POINTS * GlFrameBuilder.POINT_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val lineNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_LINE_VERTS * GlFrameBuilder.LINE_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quadNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_QUADS * 6 * GlFrameBuilder.QUAD_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val kepNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_KEP_VERTS * GlFrameBuilder.KEP_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val wpNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_WPOINTS * GlFrameBuilder.WP_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val stripNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_STRIP_VERTS * GlFrameBuilder.STRIP_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val rockInstNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_ROCKS * GlFrameBuilder.ROCK_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** R349 低模球实例直接缓冲（帧零分配：容量满帽 96×24 一次分配） */
    private val lowSphereInstNio: FloatBuffer = ByteBuffer
        .allocateDirect(GlFrameBuilder.MAX_SPHERE_I * GlFrameBuilder.SPHERE_I_STRIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val vpMatrix = FloatArray(16)
    private val scratchV = FloatArray(16)
    private val scratchP = FloatArray(16)

    // ---- R281 深度状态机（0=关 1=测+写 2=测不写） ----
    private var depthMode = -1

    // ---- GL 侧帧率自计（探针：5s 一条——输出脱敏只有计数） ----
    private var fpsWindowStartNs = 0L
    private var fpsFrames = 0
    private var fpsSlow = 0 // >1.5× vsync 中位的掉帧（粗口径：>25ms）

    // ---- R376 LOD 回退序状态机（重排后——**状态由渲染器持有**，政策件只出纯函数
    //      effective*；GL 线程 5s 自计窗驱动：慢帧>2% **连续 2 窗**升一级（旧单窗
    //      即升=装配风暴/瞬时尖峰即触发形态降级的时序病根）/连续 2 窗 <1.0% 降一级
    //      缓回（恢复阈 0.5%→1.0%=死区 [0.5,2]→[1,2] 减半——「3D 消失后不回来」
    //      的高水位粘滞减半；LOD 连续性由 blend 保证）。
    //      level 0=常态 1=①全低模（mesh∞——几何降级零掉档） 2=②帽 64
    //      3=③帽 32 4=④阈值上抬（bias 1.5——形态降级最后手段）逐帧推入 ----
    private var lodLevel = 0
    private var lodHealthyWins = 0
    private var lodBadWins = 0

    // ---- R383 P3 raymarch 体积状态机（design-nebula §5.2 触发门+降级链——渲染器
    //      持态 R376 lodLevel 同构，档位语义单源=GlVolumePolicy 纯件）：
    //      触发门=帧字段 complexes 直读（volumeFade01>0.012 入窗——P2 nearFade
    //      ≤0.012 早退同阈镜像；多团在窗取 dist/R 最小者=单团门控）；降级链
    //      5s 窗慢帧>2% 连续 2 窗降一级（64步半→40步半→40步quarter→退出回
    //      billboard）+恢复连续 2 窗 <1% 升一级（退出档 3 窗粘滞防拍动）；
    //      换档/退出/窗沿全过 volRamp 0.8s 坡道（smoothstep 缓动=C1——帧间亮度
    //      增量 ≤0.12×Δramp≪8% 贴脸窗判据）。----
    private val volCall = GlVolumeCall()
    private var volumeTier = GlVolumePolicy.TIER_START
    private var volBadWins = 0
    private var volHealthyWins = 0

    /** 体积渐入坡道原值（0..1 线性推进，应用侧 smoothstep 缓动） */
    private var volRamp = 0f

    /** 帧时钟（ramp 推进 dt——帧零分配） */
    private var volLastFrameNs = 0L

    /** 贴脸窗在场旗（探针去重——进窗/出窗各一条） */
    private var volWindowOpen = false

    /** 本统计窗内体积趟是否跑过（降级链只测自己的税——窗末复位） */
    private var volWindowHadVolume = false

    /** R383 ramp 全程秒（0.8s——C1 观感连续；帧间亮度增量 0.12×dt/0.8≈0.0025） */
    private val volRampSec = 0.8f

    /** 回退级 → 低模实例帽（②③两级收缩——「96→64→32」字面；④=帽 32+bias 双旋钮） */
    private fun lodInstanceCapOf(level: Int): Int = when (level) {
        2 -> 64
        3, 4 -> 32
        else -> GlFrameBuilder.MAX_SPHERE_I
    }

    /**
     * R355 屏幕密度（dp→px 换算的**渲染器持态**——主线程写（GlSceneView 读
     * Android Resources.displayMetrics.density 推入）/GL 线程读 @Volatile；
     * 换算单口=GlFrameBuilder.build 帧首一次×三阈值入帧字段——全族消费）。
     * 缺省 1f=mdpi 恒等档。
     */
    @Volatile
    var lodDensity = 1f

    // ---- 健康会话自愈（GlEnginePolicy.shouldResetCrashCount 的里程源——30s 零异常） ----
    private var sessionStartNs = 0L
    private var healed = false

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            // 着色器十三件（编译失败抛异常→onFatal 回退账）+后处理三件套
            progPoint = GlShaders.buildProgram(GlShaders.POINT_VS, GlShaders.POINT_FS)
            progLine = GlShaders.buildProgram(GlShaders.LINE_VS, GlShaders.LINE_FS)
            progQuad = GlShaders.buildProgram(GlShaders.QUAD_VS, GlShaders.QUAD_FS)
            progSphere = GlShaders.buildProgram(GlShaders.SPHERE_VS, GlShaders.SPHERE_FS)
            progCorona = GlShaders.buildProgram(GlShaders.CORONA_VS, GlShaders.CORONA_FS)
            progSky = GlShaders.buildProgram(GlShaders.SKY_VS, GlShaders.SKY_FS)
            progKepler = GlShaders.buildProgram(GlShaders.KEPLER_VS, GlShaders.KEPLER_FS)
            progWPoint = GlShaders.buildProgram(GlShaders.WPOINT_VS, GlShaders.POINT_FS)
            progStrip = GlShaders.buildProgram(GlShaders.STRIP_VS, GlShaders.STRIP_FS)
            progPlanet = GlShaders.buildProgram(GlShaders.PLANET_VS, GlShaders.PLANET_FS)
            progRing = GlShaders.buildProgram(GlShaders.RING_VS, GlShaders.RING_FS)
            progRock = GlShaders.buildProgram(GlShaders.ROCK_VS, GlShaders.ROCK_FS)
            progNebula = GlShaders.buildProgram(GlShaders.NEBULA_VS, GlShaders.NEBULA_FS)
            progSphereI = GlShaders.buildProgram(GlShaders.SPHERE_I_VS, GlShaders.SPHERE_I_FS)
            postFx.recreate()
            uViewportPoint = GLES30.glGetUniformLocation(progPoint, "uViewport")
            uViewportLine = GLES30.glGetUniformLocation(progLine, "uViewport")
            uViewportQuad = GLES30.glGetUniformLocation(progQuad, "uViewport")
            uTexQuad = GLES30.glGetUniformLocation(progQuad, "uTex")
            uVpSphere = GLES30.glGetUniformLocation(progSphere, "uVP")
            uCenterSphere = GLES30.glGetUniformLocation(progSphere, "uCenter")
            uRadiusSphere = GLES30.glGetUniformLocation(progSphere, "uRadius")
            uCamPosSphere = GLES30.glGetUniformLocation(progSphere, "uCamPos")
            uCoreSphere = GLES30.glGetUniformLocation(progSphere, "uCore")
            uStarSphere = GLES30.glGetUniformLocation(progSphere, "uStar")
            uEdgeSphere = GLES30.glGetUniformLocation(progSphere, "uEdge")
            uAlphaSphere = GLES30.glGetUniformLocation(progSphere, "uAlpha")
            uGrainSphere = GLES30.glGetUniformLocation(progSphere, "uGrainAmp")
            uSeedSphere = GLES30.glGetUniformLocation(progSphere, "uSeed")
            uSpinSphere = GLES30.glGetUniformLocation(progSphere, "uSpin")
            uTimeSphere = GLES30.glGetUniformLocation(progSphere, "uTime")
            uSpotSphere = GLES30.glGetUniformLocation(progSphere, "uSpotAmp")
            uFlameSphere = GLES30.glGetUniformLocation(progSphere, "uFlameAmp")
            uHotSphere = GLES30.glGetUniformLocation(progSphere, "uHot")
            uLimbSphere = GLES30.glGetUniformLocation(progSphere, "uLimbK") // R311
            // R321 潮汐形变（契约跟改申报件——SPHERE_VS 新 uniform 位置查询）
            uTideAxisSphere = GLES30.glGetUniformLocation(progSphere, "uTideAxis")
            uTideGrowSphere = GLES30.glGetUniformLocation(progSphere, "uTideGrow")
            uViewportCorona = GLES30.glGetUniformLocation(progCorona, "uViewport")
            uCenterCorona = GLES30.glGetUniformLocation(progCorona, "uCenterPx")
            uHalfCorona = GLES30.glGetUniformLocation(progCorona, "uHalfPx")
            uCoreCorona = GLES30.glGetUniformLocation(progCorona, "uCore")
            uAlphaCorona = GLES30.glGetUniformLocation(progCorona, "uAlpha")
            uSeedCorona = GLES30.glGetUniformLocation(progCorona, "uSeed")
            uTimeCorona = GLES30.glGetUniformLocation(progCorona, "uTime")
            uFlameCorona = GLES30.glGetUniformLocation(progCorona, "uFlameAmp") // R311
            uVpSky = GLES30.glGetUniformLocation(progSky, "uVP")
            uCamPosSky = GLES30.glGetUniformLocation(progSky, "uCamPos")
            uTimeSky = GLES30.glGetUniformLocation(progSky, "uTime")
            uVpKep = GLES30.glGetUniformLocation(progKepler, "uVP")
            uCenterKep = GLES30.glGetUniformLocation(progKepler, "uCenter")
            uPeriKep = GLES30.glGetUniformLocation(progKepler, "uPeri")
            uIncSinKep = GLES30.glGetUniformLocation(progKepler, "uIncSin")
            uIncCosKep = GLES30.glGetUniformLocation(progKepler, "uIncCos")
            uSemiRKep = GLES30.glGetUniformLocation(progKepler, "uSemiR")
            uEccKep = GLES30.glGetUniformLocation(progKepler, "uEcc")
            uFocalKep = GLES30.glGetUniformLocation(progKepler, "uFocalPx")
            uVpWp = GLES30.glGetUniformLocation(progWPoint, "uVP")
            uFocalWp = GLES30.glGetUniformLocation(progWPoint, "uFocalPx")
            uVpStrip = GLES30.glGetUniformLocation(progStrip, "uVP")
            uTimeStrip = GLES30.glGetUniformLocation(progStrip, "uTime")
            uSeedStrip = GLES30.glGetUniformLocation(progStrip, "uSeed")
            uVpPlanet = GLES30.glGetUniformLocation(progPlanet, "uVP")
            uCenterPlanet = GLES30.glGetUniformLocation(progPlanet, "uCenter")
            uRadiusPlanet = GLES30.glGetUniformLocation(progPlanet, "uRadius")
            uCamPosPlanet = GLES30.glGetUniformLocation(progPlanet, "uCamPos")
            uLightPlanet = GLES30.glGetUniformLocation(progPlanet, "uLightDir")
            uBasePlanet = GLES30.glGetUniformLocation(progPlanet, "uBase")
            uSeedPlanet = GLES30.glGetUniformLocation(progPlanet, "uSeed")
            uSpinPlanet = GLES30.glGetUniformLocation(progPlanet, "uSpin")
            uGasPlanet = GLES30.glGetUniformLocation(progPlanet, "uGas")
            uAlphaPlanet = GLES30.glGetUniformLocation(progPlanet, "uAlpha")
            uVpRing = GLES30.glGetUniformLocation(progRing, "uVP")
            uCenterRing = GLES30.glGetUniformLocation(progRing, "uCenter")
            uRadiusRing = GLES30.glGetUniformLocation(progRing, "uRadius")
            uTiltXRing = GLES30.glGetUniformLocation(progRing, "uTiltX")
            uTiltZRing = GLES30.glGetUniformLocation(progRing, "uTiltZ")
            uCamPosRing = GLES30.glGetUniformLocation(progRing, "uCamPos")
            uLightRing = GLES30.glGetUniformLocation(progRing, "uLightDir")
            uColorRing = GLES30.glGetUniformLocation(progRing, "uColor")
            uPlanetRRing = GLES30.glGetUniformLocation(progRing, "uPlanetR")
            uAlphaRing = GLES30.glGetUniformLocation(progRing, "uAlpha")
            uSeedRing = GLES30.glGetUniformLocation(progRing, "uSeed")
            uTimeRing = GLES30.glGetUniformLocation(progRing, "uTime")
            uModeRing = GLES30.glGetUniformLocation(progRing, "uMode")
            // R350 遗迹环进动角（预留槽 15 启用——uPrec 仅 VS 声明=零跨阶段同名
            // uniform，Mali 精度对齐纪律结构性满足；0=恒等既有调用逐位不变）
            uPrecRing = GLES30.glGetUniformLocation(progRing, "uPrec")
            uVpRock = GLES30.glGetUniformLocation(progRock, "uVP")
            uCamPosRock = GLES30.glGetUniformLocation(progRock, "uCamPos")
            uTimeRock = GLES30.glGetUniformLocation(progRock, "uTime")
            // R349 SPHERE_I（uVP=VS 侧/uCamPos=FS 侧——零跨阶段同名）
            uVpSphereI = GLES30.glGetUniformLocation(progSphereI, "uVP")
            uCamPosSphereI = GLES30.glGetUniformLocation(progSphereI, "uCamPos")
            uViewportNeb = GLES30.glGetUniformLocation(progNebula, "uViewport")
            uCenterNeb = GLES30.glGetUniformLocation(progNebula, "uCenterPx")
            uHalfNeb = GLES30.glGetUniformLocation(progNebula, "uHalfPx")
            uColorNeb = GLES30.glGetUniformLocation(progNebula, "uColor")
            uAlphaNeb = GLES30.glGetUniformLocation(progNebula, "uAlpha")
            uSeedNeb = GLES30.glGetUniformLocation(progNebula, "uSeed")
            uTimeNeb = GLES30.glGetUniformLocation(progNebula, "uTime")

            // VBO 族预分配满帽（帧路径只 SubData）+静态网格三件（球/岩石/环带/天幕）
            // R349 清点：17 个（原 14 + 低模球 mesh/IBO/实例三件——ids[14..16]）
            val ids = IntArray(17)
            GLES30.glGenBuffers(17, ids, 0)
            vboPoint = ids[0]
            vboLine = ids[1]
            vboQuad = ids[2]
            vboSphere = ids[3]
            iboSphere = ids[4]
            vboUnitQuad = ids[5]
            vboSky = ids[6]
            vboKep = ids[7]
            vboWp = ids[8]
            vboStrip = ids[9]
            vboRockMesh = ids[10]
            vboRockInst = ids[11]
            vboRing = ids[12]
            iboRing = ids[13]
            vboSphereLod = ids[14]
            iboSphereLod = ids[15]
            vboSphereInst = ids[16]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPoint)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_POINTS * GlFrameBuilder.POINT_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboLine)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_LINE_VERTS * GlFrameBuilder.LINE_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboQuad)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_QUADS * 6 * GlFrameBuilder.QUAD_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboKep)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_KEP_VERTS * GlFrameBuilder.KEP_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboWp)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_WPOINTS * GlFrameBuilder.WP_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboStrip)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_STRIP_VERTS * GlFrameBuilder.STRIP_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRockInst)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_ROCKS * GlFrameBuilder.ROCK_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)
            // R349 低模球实例 VBO（动态——帧路径只 SubData 满帽预分配）
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphereInst)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GlFrameBuilder.MAX_SPHERE_I * GlFrameBuilder.SPHERE_I_STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)

            // 真球网格（静态——初始化期一次上传）
            val sphereVerts = GlSphereMesh.buildVertices()
            val sphereIdx = GlSphereMesh.buildIndices()
            sphereIndexCount = sphereIdx.size
            val vb = ByteBuffer.allocateDirect(sphereVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(sphereVerts).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphere)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sphereVerts.size * 4, vb, GLES30.GL_STATIC_DRAW)
            val ib = ByteBuffer.allocateDirect(sphereIdx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(sphereIdx).flip()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboSphere)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, sphereIdx.size * 2, ib, GLES30.GL_STATIC_DRAW)

            // R349 低模球网格（静态——8×6=96 三角；同一生成式换参数 [GlSphereMesh.LOD_*]）
            val lodVerts = GlSphereMesh.buildVertices(GlSphereMesh.LOD_SLICES, GlSphereMesh.LOD_STACKS)
            val lodIdx = GlSphereMesh.buildIndices(GlSphereMesh.LOD_SLICES, GlSphereMesh.LOD_STACKS)
            lodSphereIndexCount = lodIdx.size
            val lvb = ByteBuffer.allocateDirect(lodVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            lvb.put(lodVerts).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphereLod)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lodVerts.size * 4, lvb, GLES30.GL_STATIC_DRAW)
            val lib = ByteBuffer.allocateDirect(lodIdx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            lib.put(lodIdx).flip()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboSphereLod)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, lodIdx.size * 2, lib, GLES30.GL_STATIC_DRAW)

            // 岩石多面体网格（静态——三角汤 240 顶点×6f）
            val rockVerts = GlRockMesh.buildVertices()
            val rb2 = ByteBuffer.allocateDirect(rockVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            rb2.put(rockVerts).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRockMesh)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, rockVerts.size * 4, rb2, GLES30.GL_STATIC_DRAW)

            // 环带网格（静态——索引化圆环面片）
            val ringVerts = GlRingMesh.buildVertices()
            val ringIdx = GlRingMesh.buildIndices()
            ringIndexCount = ringIdx.size
            val rvb = ByteBuffer.allocateDirect(ringVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            rvb.put(ringVerts).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRing)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, ringVerts.size * 4, rvb, GLES30.GL_STATIC_DRAW)
            val rib = ByteBuffer.allocateDirect(ringIdx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            rib.put(ringIdx).flip()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboRing)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ringIdx.size * 2, rib, GLES30.GL_STATIC_DRAW)

            // 天幕静态 VBO（760 方向星——dir3/color3/baseA/phase/size 九浮点；
            // R281 GPU 投影化：CPU 投影/闪烁哈希从帧路径归零）
            val skyN = TasteSpace.SKY_STARS
            val skyArr = FloatArray(skyN * 9)
            val dirBuf = FloatArray(3)
            var so = 0
            for (i in 0 until skyN) {
                TasteSpace.skyDirection(i, dirBuf)
                val tierHash = TasteSpace.h01(i.toLong() * 13L + 5L, 211)
                val tier = when {
                    tierHash < 0.05f -> 0
                    tierHash < 0.23f -> 1
                    else -> 2
                }
                val phase = TasteSpace.h01(i.toLong() * 17L + 3L, 213) * 6.2831853f
                val color = if (tier == 0) 0xFFEAF2FF.toInt() else 0xFFD9E4F2.toInt()
                val widths = floatArrayOf(2.3f, 1.5f, 1.0f)
                val baseA = floatArrayOf(0.80f, 0.52f, 0.30f)
                skyArr[so++] = dirBuf[0]; skyArr[so++] = dirBuf[1]; skyArr[so++] = dirBuf[2]
                skyArr[so++] = ((color shr 16) and 0xFF) / 255f
                skyArr[so++] = ((color shr 8) and 0xFF) / 255f
                skyArr[so++] = (color and 0xFF) / 255f
                skyArr[so++] = baseA[tier]
                skyArr[so++] = phase
                skyArr[so++] = widths[tier]
            }
            val skb = ByteBuffer.allocateDirect(skyArr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            skb.put(skyArr).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSky)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, skyArr.size * 4, skb, GLES30.GL_STATIC_DRAW)

            // 日冕单位四边形（静态——[−1,1]² 两三角；屏像素摆放在着色器做）
            val unitQuad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
            val uqb = ByteBuffer.allocateDirect(unitQuad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            uqb.put(unitQuad).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUnitQuad)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, unitQuad.size * 4, uqb, GLES30.GL_STATIC_DRAW)

            // 静态纹理六件（环/光刺/光柱/圆盘/雾/纯色——行星位图族与吸积盘纹 R281 退役）
            texMap.clear()
            texMap[GlFrameBuilder.TEX_RING] = GlShaders.uploadTexture(GlTextureBaker.bakeRing(), GlTextureBaker.RING_SIZE, GlTextureBaker.RING_SIZE)
            texMap[GlFrameBuilder.TEX_SPIKE] = GlShaders.uploadTexture(GlTextureBaker.bakeSpike(), GlTextureBaker.SPIKE_W, GlTextureBaker.SPIKE_H)
            texMap[GlFrameBuilder.TEX_BEAM] = GlShaders.uploadTexture(GlTextureBaker.bakeBeam(), GlTextureBaker.BEAM_W, GlTextureBaker.BEAM_H)
            texMap[GlFrameBuilder.TEX_DISC] = GlShaders.uploadTexture(GlTextureBaker.bakeDisc(), GlTextureBaker.DISC_SIZE, GlTextureBaker.DISC_SIZE)
            texMap[GlFrameBuilder.TEX_FOG] = GlShaders.uploadTexture(GlTextureBaker.bakeFog(), GlTextureBaker.FOG_SIZE, GlTextureBaker.FOG_SIZE)
            texMap[GlFrameBuilder.TEX_WHITE] = GlShaders.uploadTexture(GlTextureBaker.bakeWhite(), GlTextureBaker.WHITE_SIZE, GlTextureBaker.WHITE_SIZE)

            // 全局状态：单一混合模式（Canvas SRC_OVER 等价）；深度函数默认 LESS
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            depthMode = 0
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glClearColor(0f, 0f, 0f, 1f) // InkBlack=纯黑（深空底色同 Compose 背景）
            Log.i("TasteMapX", "R281 GL: 上下文建成（ES3 程序×16=14+R383 体积两件 raymarch/深度合成+后处理 FBO 组+球网格 ${GlSphereMesh.SLICES}×${GlSphereMesh.STACKS}+R349 低模球 ${GlSphereMesh.LOD_SLICES}×${GlSphereMesh.LOD_STACKS} instanced ${GlFrameBuilder.MAX_SPHERE_I} 帽+岩石汤 ${GlRockMesh.SOUP_VERTS}v+环带 ${GlRingMesh.SEGMENTS} 段+天幕静态 VBO ${TasteSpace.SKY_STARS}+纹理×6+VBO×17）")
        } catch (t: Throwable) {
            reportFatal(t)
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        // 视口由 postFx.beginScene/合成 pass 各自设置（FBO 组尺寸幂等重建在帧首）
    }

    override fun onDrawFrame(unused: GL10?) {
        if (disabled) return
        try {
            drawFrame()
        } catch (t: Throwable) {
            reportFatal(t)
        }
    }

    private fun drawFrame() {
        val t0 = System.nanoTime()
        val f = frameProvider() ?: return
        if (viewW <= 0 || viewH <= 0) return
        postFx.ensureSize(viewW, viewH)
        // ---- R319 CMB 底幕纹理动态上传（种子=帧字段 cmbSeedFp 基因指纹——变更窗
        //      才重烘重传：烘焙 GlTextureBaker.bakeCmbCached 已由装载 effect 预热，
        //      此处上传 ~ms 级；帧内只查种子相等=零成本。旧纹理随之释放） ----
        if (f.cmbOn) {
            val seed = f.cmbSeedFp
            if (seed != cmbTexSeedR319) {
                texMap.remove(GlFrameBuilder.TEX_CMB)?.let { old ->
                    GLES30.glDeleteTextures(1, intArrayOf(old), 0)
                }
                texMap[GlFrameBuilder.TEX_CMB] = GlShaders.uploadTexture(
                    GlTextureBaker.bakeCmbCached(seed), GlTextureBaker.CMB_SIZE, GlTextureBaker.CMB_SIZE,
                )
                cmbTexSeedR319 = seed
            }
        }
        // ---- R376 LOD 回退序重排参数推入（状态本线程持有——每帧三入参平移政策件
        //      旋钮；帧率窗末升降级见 drawFrame 尾段）；R355 密度入参同推（渲染器
        //      持态→builder 单口换算）。
        //      **重排判词（用户 11:2x 实锤「3D 模型进来时是对的，过一会儿 3D 样式
        //      消失」正治）**：旧序 L1=bias×1.5（floor 抬升→中距球整带降点档=形态
        //      降级放第一级——装配行星预取风暴/雾重载窗的单窗慢帧>2% 即触发=「过
        //      一会儿 3D 消失」直接根因；r362b 小米实录 5.6% 慢帧触发 L2→L3）。
        //      新序=**最小视觉伤害优先**：L1=mesh∞（几何降级零掉档——着色 shader
        //      全同只是网格密度 8×6，球全在场）→L2=帽64→L3=帽32（超帽最小屏径
        //      优先出球籍=最小球先让位）→L4=+bias1.5（**形态降级=最后手段**——
        //      四级+双窗升级=持续 40s 重载才动形态）----
        builder.lodMeshBias = if (lodLevel >= 1) Float.POSITIVE_INFINITY else 1f
        builder.lodInstanceCap = lodInstanceCapOf(lodLevel)
        builder.lodSolidBias = if (lodLevel >= 4) 1.5f else 1f
        builder.lodDensity = lodDensity
        builder.build(f, viewW.toFloat(), viewH.toFloat())
        val t1 = System.nanoTime()

        // ---- 缓冲上传（七件套一次/帧——初始化期已预分配，帧内零 glBufferData） ----
        if (builder.pointCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPoint)
            pointNio.clear()
            pointNio.put(builder.pointBuf, 0, builder.pointCount * GlFrameBuilder.POINT_STRIDE)
            pointNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.pointCount * GlFrameBuilder.POINT_STRIDE * 4, pointNio)
        }
        if (builder.lineVertCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboLine)
            lineNio.clear()
            lineNio.put(builder.lineBuf, 0, builder.lineVertCount * GlFrameBuilder.LINE_STRIDE)
            lineNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.lineVertCount * GlFrameBuilder.LINE_STRIDE * 4, lineNio)
        }
        if (builder.quadCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboQuad)
            quadNio.clear()
            quadNio.put(builder.quadBuf, 0, builder.quadCount * 6 * GlFrameBuilder.QUAD_STRIDE)
            quadNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.quadCount * 6 * GlFrameBuilder.QUAD_STRIDE * 4, quadNio)
        }
        if (builder.kepVertCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboKep)
            kepNio.clear()
            kepNio.put(builder.kepBuf, 0, builder.kepVertCount * GlFrameBuilder.KEP_STRIDE)
            kepNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.kepVertCount * GlFrameBuilder.KEP_STRIDE * 4, kepNio)
        }
        if (builder.wpCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboWp)
            wpNio.clear()
            wpNio.put(builder.wpBuf, 0, builder.wpCount * GlFrameBuilder.WP_STRIDE)
            wpNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.wpCount * GlFrameBuilder.WP_STRIDE * 4, wpNio)
        }
        if (builder.stripVertCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboStrip)
            stripNio.clear()
            stripNio.put(builder.stripBuf, 0, builder.stripVertCount * GlFrameBuilder.STRIP_STRIDE)
            stripNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.stripVertCount * GlFrameBuilder.STRIP_STRIDE * 4, stripNio)
        }
        if (builder.rockCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRockInst)
            rockInstNio.clear()
            rockInstNio.put(builder.rockBuf, 0, builder.rockCount * GlFrameBuilder.ROCK_STRIDE)
            rockInstNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.rockCount * GlFrameBuilder.ROCK_STRIDE * 4, rockInstNio)
        }
        // R349 低模球实例上传（满帽预分配——帧内零 glBufferData）
        if (builder.lowSphereCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphereInst)
            lowSphereInstNio.clear()
            lowSphereInstNio.put(builder.lowSphereBuf, 0, builder.lowSphereCount * GlFrameBuilder.SPHERE_I_STRIDE)
            lowSphereInstNio.flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, builder.lowSphereCount * GlFrameBuilder.SPHERE_I_STRIDE * 4, lowSphereInstNio)
        }
        val t2 = System.nanoTime()

        // ---- VP 矩阵（帧级一算——天幕/开普勒/世界点/真球/行星/环/岩石/条带共用） ----
        GlCameraMatrix.viewProjectionOf(f.cam, viewW.toFloat(), viewH.toFloat(), f.lens, vpMatrix, scratchV, scratchP)
        val focalPx = TasteSpace.focalOf(viewW.toFloat(), viewH.toFloat()) * f.lens
        val tSec = f.engine.timeSec

        // ---- 场景 pass（渲进 FBO——后处理链的原料） ----
        postFx.beginScene()
        var lastProg = 0
        for (k in 0 until builder.opCount) {
            when (builder.opType[k]) {
                GlFrameBuilder.OP_SKY -> {
                    setDepthMode(0)
                    GLES30.glUseProgram(progSky)
                    lastProg = progSky
                    GLES30.glUniformMatrix4fv(uVpSky, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCamPosSky, f.cam.px, f.cam.py, f.cam.pz)
                    GLES30.glUniform1f(uTimeSky, tSec)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSky)
                    bindSkyAttribs()
                    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, TasteSpace.SKY_STARS)
                }
                GlFrameBuilder.OP_POINTS -> {
                    setDepthMode(0)
                    if (lastProg != progPoint) {
                        GLES30.glUseProgram(progPoint)
                        GLES30.glUniform2f(uViewportPoint, viewW.toFloat(), viewH.toFloat())
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPoint)
                        bindPointAttribs()
                        lastProg = progPoint
                    }
                    GLES30.glDrawArrays(GLES30.GL_POINTS, builder.opA[k], builder.opB[k])
                }
                GlFrameBuilder.OP_LINES -> {
                    setDepthMode(0)
                    if (lastProg != progLine) {
                        GLES30.glUseProgram(progLine)
                        GLES30.glUniform2f(uViewportLine, viewW.toFloat(), viewH.toFloat())
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboLine)
                        bindLineAttribs()
                        lastProg = progLine
                    }
                    GLES30.glDrawArrays(GLES30.GL_LINES, builder.opA[k], builder.opB[k])
                }
                GlFrameBuilder.OP_QUADS -> {
                    setDepthMode(0)
                    if (lastProg != progQuad) {
                        GLES30.glUseProgram(progQuad)
                        GLES30.glUniform2f(uViewportQuad, viewW.toFloat(), viewH.toFloat())
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboQuad)
                        bindQuadAttribs()
                        GLES30.glUniform1i(uTexQuad, 0)
                        lastProg = progQuad
                    }
                    val tex = texMap[builder.opA[k]]
                    if (tex != null && tex != 0) {
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, builder.opB[k] * 6, builder.opC[k] * 6)
                    }
                }
                GlFrameBuilder.OP_SPHERE -> {
                    setDepthMode(1) // 真球深度写（行星/视界互挡的几何闸）
                    GLES30.glUseProgram(progSphere)
                    lastProg = progSphere
                    val i = builder.opA[k] * GlFrameBuilder.SPHERE_STRIDE
                    val sb = builder.sphereBuf
                    GLES30.glUniformMatrix4fv(uVpSphere, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCenterSphere, sb[i], sb[i + 1], sb[i + 2])
                    GLES30.glUniform1f(uRadiusSphere, sb[i + 3])
                    GLES30.glUniform1f(uAlphaSphere, sb[i + 4])
                    GLES30.glUniform3f(uCoreSphere, sb[i + 5], sb[i + 6], sb[i + 7])
                    GLES30.glUniform3f(uStarSphere, sb[i + 8], sb[i + 9], sb[i + 10])
                    GLES30.glUniform3f(uEdgeSphere, sb[i + 11], sb[i + 12], sb[i + 13])
                    GLES30.glUniform1f(uGrainSphere, sb[i + 14])
                    GLES30.glUniform1f(uSeedSphere, sb[i + 15])
                    GLES30.glUniform1f(uSpinSphere, sb[i + 16])
                    GLES30.glUniform1f(uTimeSphere, sb[i + 17])
                    GLES30.glUniform1f(uSpotSphere, sb[i + 18])
                    GLES30.glUniform1f(uFlameSphere, sb[i + 19])
                    GLES30.glUniform1f(uHotSphere, sb[i + 20])
                    GLES30.glUniform1f(uLimbSphere, sb[i + 21]) // R311：临边昏暗系数
                    // R321 潮汐形变（契约跟改申报件——uTideAxis/uTideGrow 缺省 0=
                    // 恒等式；slots 22..26=轴 3+grow 2；未形变星=(0,0,0)/(0,0) 恒等
                    // 逐位=旧输出——每实例上传防上一实例状态残留）
                    GLES30.glUniform3f(uTideAxisSphere, sb[i + 22], sb[i + 23], sb[i + 24])
                    GLES30.glUniform2f(uTideGrowSphere, sb[i + 25], sb[i + 26])
                    GLES30.glUniform3f(uCamPosSphere, f.cam.px, f.cam.py, f.cam.pz)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphere)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboSphere)
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
                }
                GlFrameBuilder.OP_SPHERE_I -> {
                    // R349 低模球 instanced 批（OP_ROCK 先例同款——divisor=1+
                    // 复位纪律；A=起始实例 B=实例数一次 draw；深度写开=真球互挡）
                    setDepthMode(1)
                    GLES30.glUseProgram(progSphereI)
                    lastProg = progSphereI
                    GLES30.glUniformMatrix4fv(uVpSphereI, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCamPosSphereI, f.cam.px, f.cam.py, f.cam.pz)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphereLod)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphereInst)
                    bindSphereInstAttribs()
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboSphereLod)
                    GLES30.glDrawElementsInstanced(
                        GLES30.GL_TRIANGLES, lodSphereIndexCount, GLES30.GL_UNSIGNED_SHORT, 0,
                        builder.opB[k],
                    )
                    unbindSphereInstDivisors()
                }
                GlFrameBuilder.OP_CORONA -> {
                    setDepthMode(0)
                    GLES30.glUseProgram(progCorona)
                    lastProg = progCorona
                    val i = builder.opA[k] * GlFrameBuilder.CORONA_STRIDE
                    val cb = builder.coronaBuf
                    GLES30.glUniform2f(uViewportCorona, viewW.toFloat(), viewH.toFloat())
                    GLES30.glUniform2f(uCenterCorona, cb[i], cb[i + 1])
                    GLES30.glUniform1f(uHalfCorona, cb[i + 2])
                    GLES30.glUniform1f(uSeedCorona, cb[i + 3])
                    GLES30.glUniform1f(uTimeCorona, cb[i + 4])
                    GLES30.glUniform3f(uCoreCorona, cb[i + 5], cb[i + 6], cb[i + 7])
                    GLES30.glUniform1f(uAlphaCorona, cb[i + 8])
                    GLES30.glUniform1f(uFlameCorona, cb[i + 9]) // R311：日珥拱活动烈度
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUnitQuad)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
                }
                GlFrameBuilder.OP_KEPLER_LINES, GlFrameBuilder.OP_KEPLER_POINTS -> {
                    setDepthMode(0)
                    GLES30.glUseProgram(progKepler)
                    lastProg = progKepler
                    val si = builder.opA[k] * GlFrameBuilder.KEP_SYS_STRIDE
                    val kb = builder.kepSysBuf
                    GLES30.glUniformMatrix4fv(uVpKep, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCenterKep, kb[si], kb[si + 1], kb[si + 2])
                    GLES30.glUniform1f(uPeriKep, kb[si + 3])
                    GLES30.glUniform1f(uIncSinKep, kb[si + 4])
                    GLES30.glUniform1f(uIncCosKep, kb[si + 5])
                    GLES30.glUniform1f(uSemiRKep, kb[si + 6])
                    GLES30.glUniform1f(uEccKep, kb[si + 7])
                    GLES30.glUniform1f(uFocalKep, focalPx)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboKep)
                    bindKeplerAttribs()
                    GLES30.glDrawArrays(
                        if (builder.opType[k] == GlFrameBuilder.OP_KEPLER_LINES) GLES30.GL_LINES else GLES30.GL_POINTS,
                        builder.opB[k], builder.opC[k],
                    )
                }
                GlFrameBuilder.OP_WPOINTS -> {
                    setDepthMode(if (builder.opC[k] == 1) 2 else 0) // opC=1=深度测（黑洞盘粒子被视界遮挡）
                    GLES30.glUseProgram(progWPoint)
                    lastProg = progWPoint
                    GLES30.glUniformMatrix4fv(uVpWp, 1, false, vpMatrix, 0)
                    GLES30.glUniform1f(uFocalWp, focalPx)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboWp)
                    bindWPointAttribs()
                    GLES30.glDrawArrays(GLES30.GL_POINTS, builder.opA[k], builder.opB[k])
                }
                GlFrameBuilder.OP_STRIP -> {
                    setDepthMode(0)
                    GLES30.glUseProgram(progStrip)
                    lastProg = progStrip
                    GLES30.glUniformMatrix4fv(uVpStrip, 1, false, vpMatrix, 0)
                    GLES30.glUniform1f(uTimeStrip, tSec)
                    GLES30.glUniform1f(uSeedStrip, 0.37f)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboStrip)
                    bindStripAttribs()
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, builder.opA[k], builder.opB[k])
                }
                GlFrameBuilder.OP_PLANET -> {
                    setDepthMode(1)
                    GLES30.glUseProgram(progPlanet)
                    lastProg = progPlanet
                    val i = builder.opA[k] * GlFrameBuilder.PLANET_STRIDE
                    val pb = builder.planetBuf
                    GLES30.glUniformMatrix4fv(uVpPlanet, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCenterPlanet, pb[i], pb[i + 1], pb[i + 2])
                    GLES30.glUniform1f(uRadiusPlanet, pb[i + 3])
                    GLES30.glUniform3f(uLightPlanet, pb[i + 4], pb[i + 5], pb[i + 6])
                    GLES30.glUniform3f(uBasePlanet, pb[i + 7], pb[i + 8], pb[i + 9])
                    GLES30.glUniform1f(uSpinPlanet, pb[i + 10])
                    GLES30.glUniform1f(uSeedPlanet, pb[i + 11])
                    GLES30.glUniform1f(uGasPlanet, pb[i + 12])
                    GLES30.glUniform1f(uAlphaPlanet, pb[i + 13])
                    GLES30.glUniform3f(uCamPosPlanet, f.cam.px, f.cam.py, f.cam.pz)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSphere)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboSphere)
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
                }
                GlFrameBuilder.OP_RING -> {
                    setDepthMode(2) // 环带=测不写（与母球真穿插；环间互序=画家序）
                    GLES30.glUseProgram(progRing)
                    lastProg = progRing
                    val i = builder.opA[k] * GlFrameBuilder.RING_STRIDE
                    val rb = builder.ringBuf
                    GLES30.glUniformMatrix4fv(uVpRing, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCenterRing, rb[i], rb[i + 1], rb[i + 2])
                    GLES30.glUniform1f(uRadiusRing, rb[i + 3])
                    GLES30.glUniform1f(uTiltXRing, rb[i + 4])
                    GLES30.glUniform1f(uTiltZRing, rb[i + 5])
                    GLES30.glUniform3f(uLightRing, rb[i + 6], rb[i + 7], rb[i + 8])
                    GLES30.glUniform1f(uSeedRing, rb[i + 9])
                    GLES30.glUniform1f(uAlphaRing, rb[i + 10])
                    GLES30.glUniform1f(uModeRing, rb[i + 11])
                    GLES30.glUniform3f(uColorRing, rb[i + 12], rb[i + 13], rb[i + 14])
                    // R350 进动角（RING_STRIDE 预留槽 15 启用——契约跟改：既有
                    // 调用（土星环/吸积盘）槽 15 恒 0=rotY(0) 逐位恒等；mode=2
                    // 遗迹环=remnantRingPrecessDeg 真值（弧度））
                    GLES30.glUniform1f(uPrecRing, rb[i + 15])
                    GLES30.glUniform1f(uPlanetRRing, rb[i + 3])
                    GLES30.glUniform1f(uTimeRing, tSec)
                    GLES30.glUniform3f(uCamPosRing, f.cam.px, f.cam.py, f.cam.pz)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRing)
                    bindRingAttribs()
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboRing)
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, ringIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
                }
                GlFrameBuilder.OP_ROCK -> {
                    setDepthMode(1)
                    GLES30.glUseProgram(progRock)
                    lastProg = progRock
                    GLES30.glUniformMatrix4fv(uVpRock, 1, false, vpMatrix, 0)
                    GLES30.glUniform3f(uCamPosRock, f.cam.px, f.cam.py, f.cam.pz)
                    GLES30.glUniform1f(uTimeRock, tSec)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRockMesh)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
                    GLES30.glEnableVertexAttribArray(1)
                    GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRockInst)
                    bindRockInstAttribs()
                    GLES30.glDrawArraysInstanced(
                        GLES30.GL_TRIANGLES, 0, GlRockMesh.SOUP_VERTS,
                        builder.opB[k],
                    )
                    unbindRockInstDivisors()
                }
                GlFrameBuilder.OP_NEBULA -> {
                    setDepthMode(0)
                    GLES30.glUseProgram(progNebula)
                    lastProg = progNebula
                    val i = builder.opA[k] * GlFrameBuilder.NEB_STRIDE
                    val nb = builder.nebBuf
                    GLES30.glUniform2f(uViewportNeb, viewW.toFloat(), viewH.toFloat())
                    GLES30.glUniform2f(uCenterNeb, nb[i], nb[i + 1])
                    GLES30.glUniform1f(uHalfNeb, nb[i + 2])
                    GLES30.glUniform1f(uSeedNeb, nb[i + 3])
                    GLES30.glUniform1f(uTimeNeb, nb[i + 4])
                    GLES30.glUniform3f(uColorNeb, nb[i + 5], nb[i + 6], nb[i + 7])
                    GLES30.glUniform1f(uAlphaNeb, nb[i + 8])
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUnitQuad)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
                }
            }
        }
        val t3 = System.nanoTime()

        // ---- R383 P3 体积趟（贴脸窗触发门+降级链 ramp——**挂位=blur 前**：
        //      场景回放完毕、endSceneComposite 深度消费前；inactive 帧零成本跳过） ----
        if (computeVolumeR383(f, tSec, focalPx, viewW, viewH)) {
            postFx.renderVolume(volCall, viewW, viewH)
            volWindowHadVolume = true
        }

        // ---- 后处理链（亮部提取→模糊→bloom+光斑合成上屏） ----
        setDepthMode(0)
        GLES30.glDepthMask(true)
        postFx.endSceneComposite(builder.flareBuf, builder.flareCount)
        val t4 = System.nanoTime()

        // ---- 名签快照发布（不可变换装——叠加层 Compose Canvas 读） ----
        labelSnapshot = if (builder.labelCands.isEmpty()) emptyList() else ArrayList(builder.labelCands)
        selLabelSnapshot = builder.selectedLabel()
        frameSeq++

        // ---- 健康会话里程（自愈闸：连续健康 30s 零异常 → 触发一次清零回调） ----
        val nowNs = System.nanoTime()
        if (sessionStartNs == 0L) sessionStartNs = nowNs
        if (!healed && nowNs - sessionStartNs >= GlEnginePolicy.HEALTHY_RESET_MS * 1_000_000L) {
            healed = true
            Log.i("TasteMapX", "R277 GL 健康会话: 连续 ${GlEnginePolicy.HEALTHY_RESET_MS / 1000}s 零异常——触发崩溃计数自愈")
            onHealthyMilestone()
        }

        // ---- GL 侧帧率自计（5s 一条探针——R281 分段装配/上传/回放/后处理分解） ----
        fpsFrames++
        val dtMs = (t4 - t0) / 1_000_000f
        if (dtMs > 25f) fpsSlow++
        val elapsed = nowNs - fpsWindowStartNs
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = nowNs
        } else if (elapsed >= 5_000_000_000L) {
            val sec = elapsed / 1_000_000_000f
            // ---- R376 LOD 回退序升降级（重排+滞回对称化：慢帧>2% **连续 2 窗**升
            //      一级（单窗装配风暴/瞬时尖峰不再即触发——旧序单窗即升=L1 bias
            //      立即抬 floor 抬掉中距球=「3D 消失」时序病根）/连续 2 窗 <1.0%
            //      降一级缓回（死区减半）；statLodLine 记账 bias/帽当前值——V51-③
            //      断言原料；升级行一条诚实日志） ----
            val jankPct = if (fpsFrames > 0) fpsSlow * 100f / fpsFrames else 0f
            if (jankPct > 2f && fpsFrames >= 30) {
                lodBadWins++
                lodHealthyWins = 0
                if (lodBadWins >= 2 && lodLevel < 4) {
                    lodLevel++
                    lodBadWins = 0
                    android.util.Log.i("TasteMapX", "R349 LOD回退: 升级至L$lodLevel（慢帧=${"%.1f".format(jankPct)}%——R376序:①全低模②帽64③帽32④bias1.5）")
                }
            } else if (jankPct < 1.0f) {
                lodBadWins = 0
                lodHealthyWins++
                if (lodHealthyWins >= 2 && lodLevel > 0) {
                    lodLevel--
                    lodHealthyWins = 0
                    android.util.Log.i("TasteMapX", "R349 LOD回退: 缓降至L$lodLevel（连续健康窗——非突变）")
                }
            } else {
                lodHealthyWins = 0
                lodBadWins = 0
            }
            // ---- R383 体积降级链（design-nebula §5.2——GlVolumePolicy 单源+渲染器
            //      持态；**只在本窗体积趟真跑过时判**（测自己的税非全场噪声）；
            //      5s 窗慢帧>2% 连续 2 窗降一级→40步半→40步quarter→0=退出回
            //      billboard（P2 行为原样观感降级语义连续）；恢复连续 2 窗<1%
            //      升一级（退出档 3 窗粘滞防拍动）——诚实降级 GlEnginePolicy 同
            //      口径：帧率门不绿=降档，仍不绿=退出（降级链在库） ----
            if (volWindowHadVolume) {
                if (jankPct > 2f && fpsFrames >= 30) {
                    volBadWins++
                    volHealthyWins = 0
                    if (volBadWins >= 2 && volumeTier > GlVolumePolicy.TIER_EXIT) {
                        volumeTier = GlVolumePolicy.demote(volumeTier)
                        volBadWins = 0
                        Log.i(
                            "TasteMapX",
                            "R383 体积降级: 档=${GlVolumePolicy.tierLabel(volumeTier)}（慢帧=${"%.1f".format(jankPct)}% 连续2窗——降级链 64步半→40步半→40步quarter→退出回billboard）",
                        )
                    }
                } else if (jankPct < 1.0f) {
                    volBadWins = 0
                    volHealthyWins++
                    val need = GlVolumePolicy.healthyWinsToPromote(volumeTier)
                    if (volHealthyWins >= need && volumeTier < GlVolumePolicy.TIER_HALF_HIGH) {
                        volumeTier = GlVolumePolicy.promote(volumeTier)
                        volHealthyWins = 0
                        Log.i(
                            "TasteMapX",
                            "R383 体积升档: 档=${GlVolumePolicy.tierLabel(volumeTier)}（连续健康窗缓回——退出档须3窗粘滞防拍动）",
                        )
                    }
                } else {
                    volHealthyWins = 0
                    volBadWins = 0
                }
                // 体积窗取证件（每统计窗一条——10s 帧率门=2 窗口径的判读原料）
                Log.i(
                    "TasteMapX",
                    "R383 体积窗: tier=$volumeTier 步=${GlVolumePolicy.stepsOf(volumeTier)} 分辨率=1/${GlVolumePolicy.resDivisorOf(volumeTier)} ramp=${"%.2f".format(volRamp)} 慢帧=${"%.1f".format(jankPct)}%",
                )
            } else {
                volHealthyWins = 0
                volBadWins = 0
            }
            volWindowHadVolume = false
            Log.i(
                "TasteMapX",
                "R281 GL帧率: %.1ffps 慢帧=%.1f%% 末帧(点=${builder.pointCount} 线=${builder.lineVertCount} 四边形=${builder.quadCount} 球=${builder.statSphereStars} 冕=${builder.statCoronas} 行星=${builder.statPlanets} 环=${builder.ringCount} 岩石=${builder.statRocks} 可见星=${builder.statVisibleStars} 微星=${builder.statMicroStars} op=${builder.opCount} 装配=%.2fms(景%.2f/星%.2f/效%.2f) 上传=%.2fms 回放=%.2fms 后处理=%.2fms)".format(
                    fpsFrames / sec, fpsSlow * 100f / fpsFrames,
                    (t1 - t0) / 1_000_000f, builder.statSceneMs, builder.statStarsMs, builder.statFxMs,
                    (t2 - t1) / 1_000_000f, (t3 - t2) / 1_000_000f, (t4 - t3) / 1_000_000f,
                ),
            )
            fpsWindowStartNs = nowNs
            fpsFrames = 0
            fpsSlow = 0
        }
    }

    // ==================== R383 P3 体积触发门（design-nebula §5.2） ====================

    /**
     * 体积趟触发判定+参数束填充（每帧一算——≤4 团 O(4) 微成本；帧零分配=volCall
     * 持单例改字段）：
     *  - 触发门=**贴脸窗单团门控**：逐团 [DeepSpaceNebula.volumeFade01]>0.012 入窗
     *    （P2 nearFade≤0.012 早退同阈镜像——crossfade 两半边同门），多团在窗取
     *    dist/R 最小者（§六「同屏至多 1 团贴脸窗内才开 raymarch」）；
     *  - **α 能量守恒互补 crossfade**：体积 alpha 帽=emissionAlpha01(ion01)（与
     *    billboard 同帽单源——P2 uniform 变体对接）×volumeFade01（互补半边）
     *    ×smoothstep(ramp)（换档/退出 C1 坡道）——billboard 半边=nearFade01
     *    （P2 已在库零改），两权恒和 1=总外轮廓恒定；R385 创世纪 gas 档两半边
     *    同乘 [DeepSpaceNebula.genesisGasMult01] 同帽钳制（能量守恒跨半边成立）；
     *  - 相机几何=scratchV 视图矩阵行向量（right/up/fwd——GlCameraMatrix 约定
     *    列主序）+tan 半角（(w/2)/focal——NDC→相机系光线比）+vpMatrix（深度
     *    裁剪重建同 VP）；
     *  - 窗沿注记：出窗即停趟（volumeFade01 已≈0.012→贡献≈0.0014 不可见——窗沿
     *    连续性由 smoothstep 窗本身承载，ramp 只管离散换档/退出的 C1）。
     */
    private fun computeVolumeR383(f: TasteFlightFrame, tSec: Float, focalPx: Float, w: Int, h: Int): Boolean {
        var best: DeepSpaceNebula.Complex? = null
        var bestScore = Float.MAX_VALUE
        var bestFade = 0f
        if (volumeTier > GlVolumePolicy.TIER_EXIT || volRamp > 0.01f) {
            val cam = f.cam
            for (c in f.complexes) {
                val dx = c.cx - cam.px
                val dy = c.cy - cam.py
                val dz = c.cz - cam.pz
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                val fade = DeepSpaceNebula.volumeFade01(dist, c.radius)
                if (fade <= 0.012f) continue
                val score = dist / c.radius.coerceAtLeast(1f)
                if (score < bestScore) {
                    bestScore = score
                    best = c
                    bestFade = fade
                }
            }
        }
        val rampTarget = if (best != null && volumeTier > GlVolumePolicy.TIER_EXIT) 1f else 0f
        // ramp 推进（帧时 dt——0.8s 全程；clamp 0.1s 防后台恢复帧巨步）
        val nowNs = System.nanoTime()
        val dtSec = if (volLastFrameNs == 0L) 0.016f
            else ((nowNs - volLastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        volLastFrameNs = nowNs
        volRamp = if (rampTarget > volRamp) {
            (volRamp + dtSec / volRampSec).coerceAtMost(rampTarget)
        } else {
            (volRamp - dtSec / volRampSec).coerceAtLeast(rampTarget)
        }
        // 贴脸窗在场探针（进窗/出窗各一条——去重）
        val windowOpen = best != null && volumeTier > GlVolumePolicy.TIER_EXIT
        if (windowOpen != volWindowOpen) {
            volWindowOpen = windowOpen
            Log.i(
                "TasteMapX",
                if (windowOpen) "R383 体积: 贴脸窗激活（dist=${"%.2f".format(bestScore)}R 档=${GlVolumePolicy.tierLabel(volumeTier)}——billboard 渐出+raymarch 渐入 crossfade）"
                else "R383 体积: 贴脸窗关闭（回远景 billboard 单边——crossfade 能量守恒归位）",
            )
        }
        if (best == null || volRamp <= 0.01f) return false
        val c = best
        val eased = volRamp * volRamp * (3f - 2f * volRamp)
        volCall.cpxCx = c.cx
        volCall.cpxCy = c.cy
        volCall.cpxCz = c.cz
        volCall.cpxRadius = c.radius.coerceAtLeast(1f)
        volCall.seed = DeepSpaceNebula.churnSeedOf(c.cellKey, c.mass)
        volCall.haMix01 = c.haMix01
        // R385 创世纪 gas 档（design §3.3-3）：**与 billboard 半边同乘同帽**——
        // crossfade α 能量守恒跨半边成立（P3「与 billboard 同帽单源」不变量的
        // 维护性扩展：两半边共享同一 [DeepSpaceNebula.genesisGasMult01] 单源乘子
        // 与 CPX_ALPHA_MAX 钳，贴脸窗沿亮度跳变 ≤8% 判据不破——只乘 billboard 不
        // 乘体积=窗沿跳变可超 8% 破能量守恒，故此单点接触为最小必要面）
        val gasMultR385 = DeepSpaceNebula.genesisGasMult01(f.genesisOn && f.genesisSpan.valid, f.genesisT01)
        volCall.alphaCap = (DeepSpaceNebula.emissionAlpha01(c.ion01) * gasMultR385)
            .coerceAtMost(DeepSpaceNebula.CPX_ALPHA_MAX) * bestFade * eased
        GlPalette.rgbF(DeepSpaceNebula.HA_ARGB, volCall.colorHa)
        GlPalette.rgbF(DeepSpaceNebula.OIII_ARGB, volCall.colorOiii)
        volCall.camX = f.cam.px
        volCall.camY = f.cam.py
        volCall.camZ = f.cam.pz
        // 视图矩阵行向量=相机基（GlCameraMatrix 约定：列主序 m[col*4+row]——
        // right=(m00,m10,m20) 即 (out[0],out[4],out[8]) 同式）
        volCall.camRight[0] = scratchV[0]; volCall.camRight[1] = scratchV[4]; volCall.camRight[2] = scratchV[8]
        volCall.camUp[0] = scratchV[1]; volCall.camUp[1] = scratchV[5]; volCall.camUp[2] = scratchV[9]
        volCall.camFwd[0] = scratchV[2]; volCall.camFwd[1] = scratchV[6]; volCall.camFwd[2] = scratchV[10]
        volCall.tanX = (w * 0.5f) / focalPx.coerceAtLeast(1f)
        volCall.tanY = (h * 0.5f) / focalPx.coerceAtLeast(1f)
        System.arraycopy(vpMatrix, 0, volCall.vp, 0, 16)
        volCall.timeSec = tSec
        volCall.steps = GlVolumePolicy.stepsOf(volumeTier)
        volCall.resDiv = GlVolumePolicy.resDivisorOf(volumeTier)
        return true
    }

    // ==================== 深度状态机 ====================

    private fun setDepthMode(m: Int) {
        if (m == depthMode) return
        when (m) {
            0 -> GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            1 -> {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthMask(true)
            }
            2 -> {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthMask(false)
            }
        }
        depthMode = m
    }

    // ==================== 顶点属性绑定 ====================

    private fun bindPointAttribs() {
        val stride = GlFrameBuilder.POINT_STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 3, GLES30.GL_FLOAT, false, stride, 32)
    }

    private fun bindLineAttribs() {
        val stride = GlFrameBuilder.LINE_STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 8)
    }

    private fun bindQuadAttribs() {
        val stride = GlFrameBuilder.QUAD_STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 16)
    }

    /** 天幕属性（dir3/color3/baseA/phase/size——stride 36B） */
    private fun bindSkyAttribs() {
        val stride = 9 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 28)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, 32)
    }

    /** 开普勒属性（nu/coef/sizeW/color4——stride 28B） */
    private fun bindKeplerAttribs() {
        val stride = GlFrameBuilder.KEP_STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 1, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 12)
    }

    /** 世界点属性（pos3/sizeW/core/color4/coreColor3——stride 48B） */
    private fun bindWPointAttribs() {
        val stride = GlFrameBuilder.WP_STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 20)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 3, GLES30.GL_FLOAT, false, stride, 36)
    }

    /** 世界三角带属性（pos3/uv2/color4——stride 36B） */
    private fun bindStripAttribs() {
        val stride = GlFrameBuilder.STRIP_STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 20)
    }

    /** 环带属性（pos3/radTheta2——stride 20B） */
    private fun bindRingAttribs() {
        val stride = GlRingMesh.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 12)
    }

    /** 岩石实例属性（posScale4/spinAxis4/color4/lightEmber4/seed1——divisor=1） */
    private fun bindRockInstAttribs() {
        val stride = GlFrameBuilder.ROCK_STRIDE * 4
        for (loc in 2..6) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribDivisor(loc, 1)
        }
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, stride, 32)
        GLES30.glVertexAttribPointer(5, 4, GLES30.GL_FLOAT, false, stride, 48)
        GLES30.glVertexAttribPointer(6, 1, GLES30.GL_FLOAT, false, stride, 64)
    }

    /** 岩石实例除数复位（divisor 残留会污染后续非 instanced draw——驱动态卫生） */
    private fun unbindRockInstDivisors() {
        for (loc in 2..6) {
            GLES30.glVertexAttribDivisor(loc, 0)
            GLES30.glDisableVertexAttribArray(loc)
        }
    }

    /** R349 低模球实例属性（位+径/芯+alpha/星+颗粒/缘+种子/自转+时间+黑子+火苗/
     *  热星+临边+ambient+预留——location 2..7 divisor=1，[GlFrameBuilder.SPHERE_I_STRIDE] 语义） */
    private fun bindSphereInstAttribs() {
        val stride = GlFrameBuilder.SPHERE_I_STRIDE * 4
        for (loc in 2..7) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribDivisor(loc, 1)
        }
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, stride, 32)
        GLES30.glVertexAttribPointer(5, 4, GLES30.GL_FLOAT, false, stride, 48)
        GLES30.glVertexAttribPointer(6, 4, GLES30.GL_FLOAT, false, stride, 64)
        GLES30.glVertexAttribPointer(7, 4, GLES30.GL_FLOAT, false, stride, 80)
    }

    /** R349 低模球实例除数复位（OP_ROCK 同款驱动态卫生——divisor 残留防污染） */
    private fun unbindSphereInstDivisors() {
        for (loc in 2..7) {
            GLES30.glVertexAttribDivisor(loc, 0)
            GLES30.glDisableVertexAttribArray(loc)
        }
    }

    /** 崩溃上报（一次——自杀后帧黑屏等 Compose 换装；账本落盘由回调方做） */
    private fun reportFatal(t: Throwable) {
        disabled = true
        if (!fatalReported) {
            fatalReported = true
            Log.e("TasteMapX", "R281 GL 致命: ${t.javaClass.simpleName}: ${t.message}——R345 诚实降级（诊断行+重试）落账", t)
            onFatal(t)
        }
    }
}
