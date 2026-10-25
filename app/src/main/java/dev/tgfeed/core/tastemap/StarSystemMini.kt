package dev.tgfeed.core.tastemap

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R310 世界卡·恒星系 3D 小模型（**纯 Kotlin 零 Android 依赖，JVM 单测锚定**）——
 * 用户需求「恒星加行星的模型，支持用户 360° 无死角拖动观测、可点击交互」的数学底座：
 *
 *  - **模型空间**：恒星=原点真球（半径 [STAR_R]=1.0 归一），tag 行星绕它转——轨道
 *    参数全确定性（同数据同模型），公式**镜像主图渲染链同一组**
 *    （planetOrbitRadius/planetWorldRadius/planetOrbitPoint/galaxyOrbitInclination/
 *    buildPlanetBody 的环与自转旗——镜像纪律：改就一起改，注释互指）；
 *  - **轨道相机**（[OrbitCam]）：拖动=相机绕原点转（等价于转模型——模型查看器
 *    惯例），yaw 无限环绕（wrap）、pitch 钳 ±[GestureFeelPolicy.PITCH_LIMIT_DEG]
 *    （88°——360° 无死角含顶视/底视，留 2° 防万向锁，与主图同一钳位哲学）；
 *    手感同族：deg/px 走 [GestureFeelPolicy.turnDegPerPx]、惯性测速走
 *    [GestureFeelPolicy.FlingVelocityWindow]、衰减走 [GestureFeelPolicy.decayVel]、
 *    停机阈走 [GestureFeelPolicy.FLING_EPS_DEG]——零私抄魔数；
 *  - **命中**（[pickPlanet]）：与主图点行星同一族判定——TasteSpace.project 投影
 *    行星心→屏空间距离≤热区（max(行星投影半径×[HIT_R_FACTOR],
 *    GestureFeelPolicy.PLANET_HIT_DP 适配 px)），重叠时深度近者胜；
 *  - **渲染等价锚**：[camOf] 产 SpaceCam → GlCameraMatrix.viewProjectionOf 的 GL
 *    矩阵与本件 pickPlanet 的 TasteSpace.project 逐位同式（R277 已有单测锚）——
 *    所见即所点。
 *
 * 渲染壳（ui/gl/GlMiniSystemView.kt）消费本件快照；本件零渲染零线程知识。
 */
object StarSystemMini {

    /** 模型空间恒星半径（归一锚——轨道/行星尺寸全以它为 1） */
    const val STAR_R = 1f

    /** 迷你模型行星上限（主图近距档 15 的下缘收口——小窗 10 颗清朗；TagOrbitCloud=7 同族） */
    const val MAX_PLANETS = 10

    /** 轨道圈折线分段数（渲染端 VBO 预分配口径——64 段+闭合回绕点=65 顶点） */
    const val ORBIT_SEGMENTS = 64

    /** 迷你相机焦距倍率（主图 FOV=100° 对小窗太宽；×本值≈53°——模型畸变收敛） */
    const val MINI_LENS = 2.0f

    /** 初始俯仰（度）：轨道面斜上方入画——3D 感第一眼就在（平视=轨道压成线） */
    const val INITIAL_PITCH_DEG = 24f

    /** 命中热区=行星投影半径×本值（指尖级宽容——与主图 PLANET_HIT_DP 兜底取大者） */
    const val HIT_R_FACTOR = 1.6f

    /** 取景边距（包络×本值=可视半径占窗短边半幅的比例上限——留呼吸边） */
    const val FIT_FILL = 0.90f

    /** 环外缘×行星半径（镜像 GlRingMesh.OUTER——取景包络用；core 不引 ui 件） */
    private const val RING_OUTER_COEF = 2.35f

    /**
     * 一颗行星的小模型参数（装配期定死，渲染/命中共用——不可变值对象）。
     *
     * @property tag 标签原形（展示/换绑用，不含 #）
     * @property orbitSemi 轨道半长轴（模型单位；=STAR_R×(1.9+3.4×orbitR01)）
     * @property peri 近日点偏角 ϖ（TasteTagPlanets.keplerPeriapsis 原值）
     * @property ecc 偏心率（恒星在焦点的真椭圆——轨道线/位置同一组）
     * @property worldR 行星半径（模型单位，≤0.30×轨道半径护栏同主图）
     * @property x/y/z 行星心（t=0 确定性相位——R250 静态面板语义承接：非逐帧动画）
     * @property gas 气态旗（h01(tagSeed,53)>0.5——程序表面选档）
     * @property ringed 带环旗（h01(tagSeed,51)<0.33——土星环几何开关）
     * @property ringTiltX/ringTiltZ 环面倾角（系统倾角+哈希自倾——主图同式）
     * @property spin0 自转初相（h01(tagSeed,61)×2π——静态锚，表面纹方位）
     * @property seedF 噪声种子（(tagSeed and 0x7FFF)——主图同口径）
     * @property hueShiftDeg 行星色相对恒星体色的偏移（(tagSeed%29)−14——主图同式）
     */
    class MiniPlanet(
        val tag: String,
        val orbitSemi: Float,
        val peri: Float,
        val ecc: Float,
        val worldR: Float,
        val x: Float,
        val y: Float,
        val z: Float,
        val gas: Boolean,
        val ringed: Boolean,
        val ringTiltX: Float,
        val ringTiltZ: Float,
        val spin0: Float,
        val seedF: Float,
        val hueShiftDeg: Float,
    )

    /**
     * 一个恒星系的小模型（不可变，装配期定死）：
     * [planets]=top-N（count 降序原序=行星表序）；[incRad]=系统轨道面倾角；
     * [extent]=取景包络半径（最远点+环/本体余量——fitDist 原料）。
     */
    class MiniSystem(
        val planets: List<MiniPlanet>,
        val incRad: Float,
        val extent: Float,
    )

    /**
     * 行星表 → 小模型（纯函数，确定性——同数据同模型）：
     * 镜像主图几何链（见类头互指注释）；恒星心=原点、恒星半径=[STAR_R]。
     * [planets] 超 [maxPlanets] 按原序留重（与主图 LOD 留重同族）。
     */
    fun layout(
        chatId: Long,
        planets: List<TasteTagPlanets.TagPlanet>,
        maxPlanets: Int = MAX_PLANETS,
    ): MiniSystem {
        // 系统倾角（镜像 ui galaxyOrbitInclination：seed=(chatId%100000)，±0.6rad≈±34°）
        val seed = (chatId % 100_000L).toInt()
        val inc = (TasteTagPlanets.h01(seed, 71) - 0.5f) * 1.2f
        val incSin = sin(inc)
        val incCos = cos(inc)
        var extent = STAR_R
        val out = ArrayList<MiniPlanet>(min(planets.size, maxPlanets))
        for (p in planets.take(maxPlanets)) {
            val tagSeed = p.tag.hashCode()
            // 轨道半长轴（镜像 planetOrbitRadius：bodyR=STAR_R 归一）
            val aW = STAR_R * (1.9f + 3.4f * p.orbitR01)
            val peri = TasteTagPlanets.keplerPeriapsis(p)
            // t=0 相位（静态模型——「每次打开同一布局」不变式与 TagOrbitCloud 同族）
            val nu = TasteTagPlanets.keplerTheta(p, 0f)
            val rOrb = TasteTagPlanets.keplerOrbitRadius(p, aW, nu)
            val ang = nu + peri
            // 行星心（镜像 planetOrbitPoint：星心=原点——水平面圆×系统倾角）
            val ct = cos(ang)
            val st = sin(ang)
            val x = ct * rOrb
            val y = -st * rOrb * incSin
            val z = st * rOrb * incCos
            // 行星半径（镜像 planetWorldRadius：系数 0.115~0.20×恒星半径，钳 0.30×
            // 轨道半长轴——主图锚=半长轴 rW 非即时半径）
            var worldR = STAR_R * TasteTagPlanets.planetRadiusCoef(p)
            val cap = aW * 0.30f
            if (worldR > cap) worldR = cap
            // 环参（镜像 buildPlanetBody：ringed/自倾/进动同哈希族）
            val ringed = TasteTagPlanets.h01(tagSeed, 51) < 0.33f
            val ringTiltX = inc + (TasteTagPlanets.h01(tagSeed, 55) - 0.5f) * 0.9f
            val ringTiltZ = (TasteTagPlanets.h01(tagSeed, 57) - 0.5f) * 0.7f
            // 取景包络：远日点 a(1+e) + 环/本体径向余量
            val apo = aW * (1f + p.ecc)
            val rim = if (ringed) worldR * RING_OUTER_COEF else worldR
            extent = max(extent, apo + rim)
            out += MiniPlanet(
                tag = p.tag,
                orbitSemi = aW,
                peri = peri,
                ecc = p.ecc,
                worldR = worldR,
                x = x, y = y, z = z,
                gas = TasteTagPlanets.h01(tagSeed, 53) > 0.5f,
                ringed = ringed,
                ringTiltX = ringTiltX,
                ringTiltZ = ringTiltZ,
                spin0 = TasteTagPlanets.h01(tagSeed, 61) * 6.2831853f,
                seedF = (tagSeed and 0x7FFF).toFloat(),
                hueShiftDeg = ((tagSeed % 29 + 29) % 29 - 14).toFloat(),
            )
        }
        return MiniSystem(out, inc, extent)
    }

    // ==================== 轨道相机（拖动旋转+惯性阻尼） ====================

    /**
     * 小模型轨道相机状态（调用方持有，UI 线程写/渲染线程读——外层同步）：
     * 机位=以原点为球心、[dist] 为半径的球面（yaw/pitch 球坐标），恒瞄准原点。
     * [dragging]=true 时惯性冻结（手势期速度只记录不应用——主图同纪律）。
     */
    class OrbitCam {
        var yawDeg = 0f
        var pitchDeg = INITIAL_PITCH_DEG
        var yawVelDeg = 0f
        var pitchVelDeg = 0f
        var dragging = false
        /** 本次手势累计生效角（度——探针口径：拖动全程的模型转角） */
        var gestureYawDeg = 0f
        var gesturePitchDeg = 0f
        val yawWin = GestureFeelPolicy.FlingVelocityWindow()
        val pitchWin = GestureFeelPolicy.FlingVelocityWindow()

        /** 手势起手：惯性清零+测速窗重置+累计归零（残速不污染新一轮——窗口族自带休止清窗双保险） */
        fun dragStart() {
            dragging = true
            yawVelDeg = 0f
            pitchVelDeg = 0f
            gestureYawDeg = 0f
            gesturePitchDeg = 0f
            yawWin.reset()
            pitchWin.reset()
        }

        /**
         * 拖动一步（号族与 TasteSpace.turnBy 一致：右滑 dx>0=yaw 增=向右转、
         * 上滑 dy<0=pitch 增=升机位俯视）；测速窗只记**实际生效**角增量（pitch 钳位
         * 吃掉的部分不入窗——松手初速不虚高）。
         */
        fun dragBy(tMs: Long, dxPx: Float, dyPx: Float, degPerPx: Float) {
            if (dxPx == 0f && dyPx == 0f) return
            val dYaw = dxPx * degPerPx
            val oldPitch = pitchDeg
            yawDeg = TasteSpace.wrapDeg(yawDeg + dYaw)
            pitchDeg = (pitchDeg - dyPx * degPerPx)
                .coerceIn(-GestureFeelPolicy.PITCH_LIMIT_DEG, GestureFeelPolicy.PITCH_LIMIT_DEG)
            gestureYawDeg += dYaw
            gesturePitchDeg += pitchDeg - oldPitch
            yawWin.push(tMs, dYaw)
            pitchWin.push(tMs, pitchDeg - oldPitch)
        }

        /** 松手：时间窗测速→惯性初速（钳 [GestureFeelPolicy.MINI_MODEL_FLING_MAX_DEG_SEC] 防毛刺甩飞） */
        fun dragEnd(nowMs: Long) {
            dragging = false
            yawVelDeg = yawWin.velocity(nowMs)
                .coerceIn(-GestureFeelPolicy.MINI_MODEL_FLING_MAX_DEG_SEC, GestureFeelPolicy.MINI_MODEL_FLING_MAX_DEG_SEC)
            pitchVelDeg = pitchWin.velocity(nowMs)
                .coerceIn(-GestureFeelPolicy.MINI_MODEL_FLING_MAX_DEG_SEC, GestureFeelPolicy.MINI_MODEL_FLING_MAX_DEG_SEC)
        }

        /**
         * 惯性一步（松手滑行）：先积分后衰减（dt 毫秒，帧率无关——decayVel 政策件
         * 同族曲线）；返回 false=双轴速度均低于停机阈且已清零（渲染端停止出帧）。
         * 手势期（[dragging]）不调本口。
         */
        fun stepInertia(dtMs: Float): Boolean {
            if (dragging) return false
            if (yawVelDeg == 0f && pitchVelDeg == 0f) return false
            val dtSec = dtMs / 1000f
            yawDeg = TasteSpace.wrapDeg(yawDeg + yawVelDeg * dtSec)
            pitchDeg = (pitchDeg + pitchVelDeg * dtSec)
                .coerceIn(-GestureFeelPolicy.PITCH_LIMIT_DEG, GestureFeelPolicy.PITCH_LIMIT_DEG)
            yawVelDeg = GestureFeelPolicy.decayVel(yawVelDeg, dtMs)
            pitchVelDeg = GestureFeelPolicy.decayVel(pitchVelDeg, dtMs)
            if (kotlin.math.abs(yawVelDeg) < GestureFeelPolicy.FLING_EPS_DEG &&
                kotlin.math.abs(pitchVelDeg) < GestureFeelPolicy.FLING_EPS_DEG
            ) {
                yawVelDeg = 0f
                pitchVelDeg = 0f
                return false
            }
            return true
        }

        /** 快照（渲染线程消费的不可变帧态） */
        fun snapshot(): CamPose = CamPose(yawDeg, pitchDeg, dragging)
    }

    /** 轨道相机姿态（不可变——跨线程快照） */
    class CamPose(val yawDeg: Float, val pitchDeg: Float, val dragging: Boolean)

    /**
     * 轨道机位 → SpaceCam（GL 矩阵与命中投影共用同一相机——所见即所点）：
     * 机位=球面 (sin yaw·cos pitch, sin pitch, cos yaw·cos pitch)×[dist]，瞄准原点。
     * yaw=0/pitch=0=机位 +Z 正视；pitch 正=升机位（俯视轨道面）。
     */
    fun camOf(pose: CamPose, dist: Float): TasteSpace.SpaceCam {
        val yaw = Math.toRadians(pose.yawDeg.toDouble())
        val pitch = Math.toRadians(pose.pitchDeg.toDouble())
        val cp = cos(pitch).toFloat()
        val ex = sin(yaw).toFloat() * cp * dist
        val ey = sin(pitch).toFloat() * dist
        val ez = cos(yaw).toFloat() * cp * dist
        // 瞄准原点（aimAt 同式内联——零暂存分配）
        val aimYaw = Math.toDegrees(atan2(-ex.toDouble(), -ez.toDouble())).toFloat()
        val len = sqrt(ex * ex + ey * ey + ez * ez)
        val aimPitch = if (len < 1e-4f) {
            0f
        } else {
            Math.toDegrees(asin((-ey / len).coerceIn(-1f, 1f)).toDouble()).toFloat()
        }
        return TasteSpace.SpaceCam(ex, ey, ez, aimYaw, aimPitch)
    }

    /**
     * 取景距离（模型单位）：系统包络 [extent] 全入窗——投影半径 ≤ 窗短边半幅×
     * [FIT_FILL]（r_px=focal×extent/dist 反解；focal=TasteSpace.focalOf×[MINI_LENS]）。
     * **近裁护栏**：dist−extent ≥ TasteSpace.NEAR+1（转到任何角度最近行星都不进
     * 近裁剔除区——project/GL 双侧同阈）。防御：窗口异常（半幅<1px）→ 包络×4 保底。
     */
    fun fitDist(extent: Float, wPx: Float, hPx: Float, lens: Float = MINI_LENS): Float {
        val nearGuard = extent + TasteSpace.NEAR + 1f
        val halfMin = min(wPx, hPx) / 2f
        if (halfMin < 1f) return maxOf(extent * 4f, nearGuard)
        val focal = TasteSpace.focalOf(wPx, hPx) * lens
        return maxOf(extent * focal / (halfMin * FIT_FILL), nearGuard)
    }

    // ==================== 点选命中 ====================

    /**
     * 点选行星（与主图同一族屏空间判定）：投影各行星心→屏距≤热区（max(行星投影
     * 半径×[HIT_R_FACTOR], [slopPx])）者入围，**深度近者胜**（前后叠压时点得到
     * 前景那颗——真 3D 语义）；**恒星面遮罩**：点在恒星投影盘内=点的是恒星不算
     * 行星（被恒星挡死的行星不许选中——诚实命中）。[out4]=投影暂存（长≥4，
     * 调用方供——热路径零分配）。
     *
     * @return 命中行星下标（[sys].planets 序）；未命中=-1。
     */
    fun pickPlanet(
        sxPx: Float,
        syPx: Float,
        wPx: Float,
        hPx: Float,
        cam: TasteSpace.SpaceCam,
        sys: MiniSystem,
        slopPx: Float,
        out4: FloatArray,
        lens: Float = MINI_LENS,
    ): Int {
        val focal = TasteSpace.focalOf(wPx, hPx) * lens
        val cx = wPx / 2f
        val cy = hPx / 2f
        // 恒星面遮罩（星心=原点）
        if (TasteSpace.project(0f, 0f, 0f, cam, focal, cx, cy, out4)) {
            val starPx = STAR_R * out4[3] * 1.02f
            val dx = sxPx - out4[0]
            val dy = syPx - out4[1]
            if (dx * dx + dy * dy <= starPx * starPx) return -1
        }
        var best = -1
        var bestDepth = Float.MAX_VALUE
        for (i in sys.planets.indices) {
            val p = sys.planets[i]
            if (!TasteSpace.project(p.x, p.y, p.z, cam, focal, cx, cy, out4)) continue
            val prPx = p.worldR * out4[3]
            val hitR = max(prPx * HIT_R_FACTOR, slopPx)
            val dx = sxPx - out4[0]
            val dy = syPx - out4[1]
            if (dx * dx + dy * dy <= hitR * hitR && out4[2] < bestDepth) {
                best = i
                bestDepth = out4[2]
            }
        }
        return best
    }
}
