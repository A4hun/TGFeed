package dev.tgfeed.core.tastemap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R242 沉浸式宇宙相机核心（**纯数学，零 Android 依赖，单测可跑**）——用户终极愿景
 * 「沉浸在没有边际的深空宇宙里——向上看向下看向左看向右，四周三维空间都能看见
 * 恒星；选择一个方向探索，能找到恒星，接近它自然看见旁边的小行星」的相机模型
 * 落地：
 *
 *  - **第一人称在宇宙中**（替换 R206/R231 的「轨道俯瞰平面星图」相机）：相机是
 *    3D 空间中的一个质点 [SpaceCam]（位置 + **yaw/pitch 全方位视角**），星体分布
 *    在以初始相机位为球心的**球面壳层**中（[spherify]：近/中/远三壳全方向包围）；
 *  - **投影=透视除法 + 视锥剔除**：世界点 → 相机正交基（前/右/上）→ 透视除法
 *    sx=cx+focal·x/z；深度即 z（近裁 [NEAR] 后剔除）；
 *  - **飞行=相机沿视线移动**（[flyForward]）：前方的星越来越大越来越近（近大远
 *    小=真透视——3D 感的核心）；亮度随距离衰减（[fogOf]）；
 *  - **手势**：单指拖拽=视角旋转（[turnBy]：左滑=向左转头/上滑=抬头——街景语义
 *    内容跟手）；双指张开=前进/捏合=后退（[flyBy]）；松手惯性（[drift]：角速度+
 *    飞行速度双通道衰减）；
 *  - **双击恒星=锁定+自动运镜**（[approach]）：位置指数趋近「星前停船点」+
 *    yaw/pitch 最短角差平滑转向——穿越空间的连续运镜，无跳变；
 *  - **天幕星**（[skyDirection]）：确定性方向星（方向锚定=无穷远语义，不随飞行
 *    视差）——「没有边际」的背景半边；3 团缓转星云同口径。
 *
 * 与旧引擎的分工：**TasteFlight.kt 原样保留**（旧相机 API+创世纪+FxEngine 粒子
 * 引擎+取景函数——ARCHITECTURE「公共 API 只加不改」的字面落地，旧单测全绿）；
 * App 渲染/交互消费方整体迁移到本引擎。海报链路（ShareCard）零依赖零改动；
 * 布局管线（MDS+微扰+展开）产出 x/y/z 原值不动——海报继续消费，App 侧经
 * [spherify] 派生球面坐标（双口径，同 R231 flightZ 先例）。
 */
object TasteSpace {

    // ==================== 量纲与常数 ====================

    /** 近裁面（世界单位）：深度低于此值的点不绘制不命中（透视除法防护） */
    const val NEAR = 14f

    /** 长轴视场角（度）：focal=max(w,h)/(2·tan(FOV/2))——横屏水平 100°/竖屏垂直 100° */
    const val FOV_DEG = 100f

    // ---- 手势手感常数（R268b 起全部收口 [GestureFeelPolicy] 唯一出处——本文件
    //      零手感魔数；转头/捏合/锚指/惯性/俯仰钳位全从政策件取值，design-gesture.md
    //      §九归口纪律：任何新手感参数只许先进政策件） ----

    // ---- 天体壳层（任务书口径：以初始相机位=世界原点为球心） ----

    /** 近壳内缘/外缘（世界单位）：稀疏大星——你身边的星（<500） */
    const val SHELL_NEAR_MIN = 90f
    const val SHELL_NEAR_MAX = 480f

    /** 中壳（500~2000）：中等恒星——飞一会儿能到 */
    const val SHELL_MID_MIN = 560f
    const val SHELL_MID_MAX = 1960f

    /** 远壳（2000~8000）：远处的光点——远方目标 */
    const val SHELL_FAR_MIN = 2100f
    const val SHELL_FAR_MAX = 7800f

    // ---- spherize 布局镜像常数（TasteDepthSpread 同值同注释——core 内两文件互为
    //      镜像，改就一起改；布局侧产出 flightZ，本侧把它复原成带号/带内位） ----

    /** 纵深半幅镜像（TasteDepthSpread.Z_HALF=420） */
    private const val Z_HALF = 420f

    /** 椭球渐缩半径镜像（TasteDepthSpread.TAPER_FACTOR×330=445.5） */
    private const val TAPER_R = 445.5f

    /** 三带带心（z01 单位：近 −0.64 / 中 0 / 远 +0.64） */
    private const val BAND_PITCH = 0.64f

    /** 带半宽（z01 单位） */
    private const val BAND_HALF = 0.27f

    /** 高度角全跨度（度）：每带覆盖 ±75°（上下左右都有星；极区 15° 由天幕星补） */
    const val ELEV_SPAN_DEG = 150f

    /** 口味中心混合半径（世界单位）：方位角在此内与哈希角混合（中心方向不稳） */
    private const val CENTER_BLEND_R = 60f

    /** 世界系口味云团中心（镜像 TasteMapScreen 世界坐标系） */
    private const val TASTE_CX = 500f
    private const val TASTE_CY = 500f

    // ---- 天幕（装饰层——确定性方向星；量纲：方向单位向量 × 距离由渲染端给） ----

    /** 天幕星数（方向锚定=无穷远：不随飞行视差，「没有边际」的背景半边） */
    const val SKY_STARS = 760

    /** 天幕星云数（缓转装饰） */
    const val SKY_NEBULAS = 3

    // ==================== 相机 ====================

    /**
     * 第一人称宇宙相机：位置 (px,py,pz)（世界系，Y 轴向上）+ 朝向（[yawDeg] 水平
     * 角：0=朝 +Z，正=向右转；[pitchDeg] 俯仰：正=抬头）。不可变值对象，帧循环
     * copy 更新。
     */
    data class SpaceCam(
        val px: Float,
        val py: Float,
        val pz: Float,
        val yawDeg: Float,
        val pitchDeg: Float,
    ) {
        companion object {
            /** 初始相机：宇宙原点（球面壳层的球心——被星全方向包围）、朝 +Z 平视 */
            val ORIGIN = SpaceCam(0f, 0f, 0f, 0f, 0f)
        }
    }

    /** 焦距（像素）：长轴 FOV 口径——横屏=水平视场/竖屏=垂直视场 100° */
    fun focalOf(w: Float, h: Float): Float =
        max(w, h) / (2f * kotlin.math.tan(Math.toRadians((FOV_DEG / 2f).toDouble()))).toFloat()

    /**
     * 视线方向（世界系单位向量，写 [out] 长 ≥3）：
     * `f = (sinYaw·cosPitch, sinPitch, cosYaw·cosPitch)`——yaw=0 朝 +Z、正=向右
     * （+X）；pitch 正=抬头（+Y）。
     */
    fun lookDirection(yawDeg: Float, pitchDeg: Float, out: FloatArray): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(pitch).toFloat()
        out[0] = sin(yaw).toFloat() * cp
        out[1] = sin(pitch).toFloat()
        out[2] = cos(yaw).toFloat() * cp
        return out
    }

    /**
     * 相机正交基（前/右/上，各单位向量，写 [out] 长 ≥9：f(r0-2)/r(3-5)/u(6-8)）：
     * r=(cosYaw, 0, −sinYaw)（与 pitch 无关——转右时右向量跟着转）；u=f×r。
     * 渲染/命中/投影全家共用一份缓存（每帧一算，零逐星三角函数）。
     */
    fun basis(yawDeg: Float, pitchDeg: Float, out: FloatArray): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val sy = sin(yaw).toFloat()
        val cy = cos(yaw).toFloat()
        val sp = sin(pitch).toFloat()
        val cp = cos(pitch).toFloat()
        // f
        out[0] = sy * cp
        out[1] = sp
        out[2] = cy * cp
        // r
        out[3] = cy
        out[4] = 0f
        out[5] = -sy
        // u = f × r
        out[6] = out[1] * out[5] - out[2] * out[4]
        out[7] = out[2] * out[3] - out[0] * out[5]
        out[8] = out[0] * out[4] - out[1] * out[3]
        return out
    }

    /**
     * 3D 世界 → 屏幕投影（透视除法 + 近裁剔除）：写 [out] 长 ≥4：
     *  `[0]=sx  [1]=sy  [2]=深度 z（相机系）  [3]=sizeK=focal/z（屏像素/世界单位）`。
     * 返回 false=点在近裁面后（不绘制不命中）。调用方再做屏外裕量剔除。
     * [basisArr]=[basis] 的缓存（渲染端每帧一算；传 null 则现算——测试/一次性口径）。
     */
    fun project(
        x: Float,
        y: Float,
        z: Float,
        cam: SpaceCam,
        focal: Float,
        cx: Float,
        cy: Float,
        out: FloatArray,
        basisArr: FloatArray? = null,
    ): Boolean {
        val b = basisArr ?: basis(cam.yawDeg, cam.pitchDeg, SCRATCH_BASIS)
        val dx = x - cam.px
        val dy = y - cam.py
        val dz = z - cam.pz
        // 相机系坐标：深度沿前向、横向沿右向、纵向沿上向
        val depth = dx * b[0] + dy * b[1] + dz * b[2]
        if (depth <= NEAR) return false
        val rx = dx * b[3] + dy * b[4] + dz * b[5]
        val uy = dx * b[6] + dy * b[7] + dz * b[8]
        val k = focal / depth
        out[0] = cx + rx * k
        out[1] = cy - uy * k
        out[2] = depth
        out[3] = k
        return true
    }

    /** 投影用一次性基缓存（basisArr=null 路径专用——非线程安全，测试单线程口径） */
    private val SCRATCH_BASIS = FloatArray(9)

    /**
     * 屏幕坐标 → 视线射线（世界系单位向量，写 [out] 长 ≥3）——点选恒星用：
     * `dir = normalize(f + r·ndcX + u·ndcY)`，ndc=(屏点−屏心)/focal（y 翻转——
     * 屏幕 y 向下、世界上向）。
     */
    fun screenRay(
        sx: Float,
        sy: Float,
        cam: SpaceCam,
        focal: Float,
        cx: Float,
        cy: Float,
        out: FloatArray,
    ): FloatArray {
        val b = basis(cam.yawDeg, cam.pitchDeg, SCRATCH_BASIS)
        val nx = (sx - cx) / focal
        val ny = -(sy - cy) / focal
        var dx = b[0] + b[3] * nx + b[6] * ny
        var dy = b[1] + b[4] * nx + b[7] * ny
        var dz = b[2] + b[5] * nx + b[8] * ny
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len > 1e-8f) { dx /= len; dy /= len; dz /= len }
        out[0] = dx
        out[1] = dy
        out[2] = dz
        return out
    }

    /** 相机到世界点距离（世界单位——井深比/尘埃深度带/发现感的判定量） */
    fun distTo(cam: SpaceCam, x: Float, y: Float, z: Float): Float {
        val dx = x - cam.px
        val dy = y - cam.py
        val dz = z - cam.pz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** 世界点在相机前方（深度>[NEAR]）——边/流光/网格的整段剔除判定 */
    fun ahead(x: Float, y: Float, z: Float, cam: SpaceCam, basisArr: FloatArray? = null): Boolean {
        val b = basisArr ?: basis(cam.yawDeg, cam.pitchDeg, SCRATCH_BASIS)
        val dx = x - cam.px
        val dy = y - cam.py
        val dz = z - cam.pz
        return dx * b[0] + dy * b[1] + dz * b[2] > NEAR
    }

    // ==================== 手势 ====================

    /**
     * 单指拖拽 = 视角旋转（街景语义：内容跟手）：
     * 右滑（dx>0）=向右转头（yaw 增）；上滑（dy<0）=抬头（pitch 增）——
     * 「左滑=向左转头，上滑=抬头」（用户原话方向锚定）。pitch 钳 ±[GestureFeelPolicy.PITCH_LIMIT_DEG]。
     */
    fun turnBy(cam: SpaceCam, dxPx: Float, dyPx: Float, degPerPx: Float): SpaceCam {
        if (dxPx == 0f && dyPx == 0f) return cam
        val yaw = wrapDeg(cam.yawDeg + dxPx * degPerPx)
        val pitch = (cam.pitchDeg - dyPx * degPerPx)
            .coerceIn(-GestureFeelPolicy.PITCH_LIMIT_DEG, GestureFeelPolicy.PITCH_LIMIT_DEG)
        return SpaceCam(cam.px, cam.py, cam.pz, yaw, pitch)
    }

    /**
     * 双指捏合 = 前进/后退（张开=factor>1 沿视线前进 / 捏合=factor<1 后退）：
     * 行程口径收口 [GestureFeelPolicy.pinchFlyUnits]（(factor−1)×增益，单事件限幅
     * ±80——持续张开≈巡航上限）。后退同样限幅（不倒车冲刺）。
     */
    fun flyBy(cam: SpaceCam, zoomFactor: Float): SpaceCam {
        val d = GestureFeelPolicy.pinchFlyUnits(zoomFactor)
        if (d == 0f) return cam
        return flyForward(cam, d)
    }

    /**
     * 双指捏合 = 前进/后退（**R303c 乘法曲线档**，§7.1 Google Earth 式速度∝当前
     * 高度）：行程收口 [GestureFeelPolicy.pinchFlyUnitsAlt]（高空乘法=一满手势距离
     * ×2、L4→L2 5~6 捏；近地面回落现行 [flyBy] 线性细控）；其余与 [flyBy] 同
     * （沿视线，朝向不动）。手势集合零新增（R268 归口——只换曲线）。
     *
     * @param dRef 相机到最近星距离（当前高度口径——消费方事件级算好传入）
     */
    fun flyByAlt(cam: SpaceCam, zoomFactor: Float, dRef: Float): SpaceCam {
        val d = GestureFeelPolicy.pinchFlyUnitsAlt(zoomFactor, dRef)
        if (d == 0f) return cam
        return flyForward(cam, d)
    }

    /**
     * 飞行 = 相机沿视线方向移动（[dist] 正=前进/负=后退；视线由 yaw/pitch 定——
     * 朝向不变，只动位置）：前方的星越来越大越来越近。
     */
    fun flyForward(cam: SpaceCam, dist: Float): SpaceCam {
        if (dist == 0f) return cam
        val b = basis(cam.yawDeg, cam.pitchDeg, SCRATCH_BASIS)
        return SpaceCam(
            cam.px + b[0] * dist,
            cam.py + b[1] * dist,
            cam.pz + b[2] * dist,
            cam.yawDeg,
            cam.pitchDeg,
        )
    }

    /**
     * 惯性一步（松手滑行）：角速度（度/秒）转头 + 飞行速度（世界单位/秒）沿当前
     * 视线前进，dtSec 秒。速度衰减由调用方回写持有（相机值对象/速度引擎侧可变，
     * 职责分开——TasteFlight.drift 同款纪律）。
     */
    fun drift(cam: SpaceCam, yawVel: Float, pitchVel: Float, flyVel: Float, dtSec: Float): SpaceCam {
        var c = cam
        if (yawVel != 0f || pitchVel != 0f) {
            val yaw = wrapDeg(c.yawDeg + yawVel * dtSec)
            val pitch = (c.pitchDeg + pitchVel * dtSec)
                .coerceIn(-GestureFeelPolicy.PITCH_LIMIT_DEG, GestureFeelPolicy.PITCH_LIMIT_DEG)
            c = SpaceCam(c.px, c.py, c.pz, yaw, pitch)
        }
        if (flyVel != 0f) c = flyForward(c, flyVel * dtSec)
        return c
    }

    /** 角度归一到 (−180,180]（yaw 环绕/最短角差的基准） */
    fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** 最短角差（to−from，经 wrapDeg——approach 的朝向插值用） */
    fun angDiff(fromDeg: Float, toDeg: Float): Float = wrapDeg(toDeg - fromDeg)

    /** 朝向某世界点的目标角（写 [out] 长 ≥2：[0]=yawDeg [1]=pitchDeg） */
    fun aimAt(cam: SpaceCam, x: Float, y: Float, z: Float, out: FloatArray): FloatArray {
        val dx = x - cam.px
        val dy = y - cam.py
        val dz = z - cam.pz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) {
            out[0] = cam.yawDeg
            out[1] = cam.pitchDeg
            return out
        }
        out[0] = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        out[1] = Math.toDegrees(asin((dy / len).coerceIn(-1f, 1f).toDouble())).toFloat()
        return out
    }

    /**
     * 双击运镜一步（指数趋近）：目标=世界点前 [stopDist] 处的停船位 + 朝向平滑
     * 转向目标（每帧按当前位姿解——星不动=收敛到「停在星前、看着星」）。
     * k=1−exp(−dt/τ)（τ≈0.9s 电影感长运镜）。已在停船距离内=只转头不再逼近。
     */
    fun approach(cam: SpaceCam, tx: Float, ty: Float, tz: Float, stopDist: Float, k: Float): SpaceCam {
        val aim = aimAt(cam, tx, ty, tz, SCRATCH_AIM)
        val yaw = cam.yawDeg + angDiff(cam.yawDeg, aim[0]) * k
        val pitch = (cam.pitchDeg + angDiff(cam.pitchDeg, aim[1]) * k)
            .coerceIn(-GestureFeelPolicy.PITCH_LIMIT_DEG, GestureFeelPolicy.PITCH_LIMIT_DEG)
        val dx = tx - cam.px
        val dy = ty - cam.py
        val dz = tz - cam.pz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        var nx = cam.px
        var ny = cam.py
        var nz = cam.pz
        if (len > stopDist + 0.5f && len > 1e-4f) {
            // 停船位 = 星位 − 视星方向×stopDist（沿连线退 stopDist）
            val gx = tx - dx / len * stopDist
            val gy = ty - dy / len * stopDist
            val gz = tz - dz / len * stopDist
            nx = cam.px + (gx - cam.px) * k
            ny = cam.py + (gy - cam.py) * k
            nz = cam.pz + (gz - cam.pz) * k
        }
        return SpaceCam(nx, ny, nz, wrapDeg(yaw), pitch)
    }

    /** aimAt 暂存（approach 内部专用） */
    private val SCRATCH_AIM = FloatArray(2)

    /**
     * R301 世界卡相机环绕一步：相机在水平面绕目标点 (sx,sy,sz) 转过 [dYawRad]
     * （保距——到目标的水平半径与高度不变），并重新瞄准目标。环绕角速度由调用方
     * 按帧 dt 给（rad/s×dtSec）。目标与相机同位（半径<1e-3）时原样返回（零推力
     * 不动相机——退化防御）。
     */
    fun orbitAround(cam: SpaceCam, sx: Float, sy: Float, sz: Float, dYawRad: Float): SpaceCam {
        val dx = cam.px - sx
        val dz = cam.pz - sz
        val r = sqrt(dx * dx + dz * dz)
        if (r < 1e-3f || dYawRad == 0f) return cam
        val ang = atan2(dz.toDouble(), dx.toDouble()).toFloat() + dYawRad
        val nx = sx + r * cos(ang)
        val nz = sz + r * sin(ang)
        val moved = SpaceCam(nx, cam.py, nz, cam.yawDeg, cam.pitchDeg)
        val aim = aimAt(moved, sx, sy, sz, SCRATCH_AIM)
        return SpaceCam(nx, cam.py, nz, aim[0], aim[1])
    }

    /**
     * R301 世界卡关窗回位插值：[p01]∈[0,1] smoothstep 缓动（两头柔中间匀——回位
     * 不生硬），位置线性、朝向走最短角差（wrapDeg 族同口径）。p01=0 恒返 [from]、
     * 1 恒返 [to]（端点精确——回位落点零残差）。
     */
    fun easePose(from: SpaceCam, to: SpaceCam, p01: Float): SpaceCam {
        val t = p01.coerceIn(0f, 1f)
        val k = t * t * (3f - 2f * t)
        return SpaceCam(
            from.px + (to.px - from.px) * k,
            from.py + (to.py - from.py) * k,
            from.pz + (to.pz - from.pz) * k,
            wrapDeg(from.yawDeg + angDiff(from.yawDeg, to.yawDeg) * k),
            from.pitchDeg + angDiff(from.pitchDeg, to.pitchDeg) * k,
        )
    }

    /**
     * 运镜到位判定：进停船位 12 单位 ∧ 两轴角差 <2°（「锁定+到达」的单一出口，
     * 双击/feed 跳入/弧跳/回到全景共用）。
     */
    fun approachArrived(cam: SpaceCam, tx: Float, ty: Float, tz: Float, stopDist: Float): Boolean {
        val dx = tx - cam.px
        val dy = ty - cam.py
        val dz = tz - cam.pz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len > stopDist + 12f) return false
        val aim = aimAt(cam, tx, ty, tz, SCRATCH_AIM)
        return abs(angDiff(cam.yawDeg, aim[0])) < 2f && abs(angDiff(cam.pitchDeg, aim[1])) < 2f
    }

    // ==================== 亮度衰减 ====================

    /**
     * 距离亮度衰减（0..1）：近=1 / 中距 ~0.8 / 远壳 ~0.5 / 8000 ≈0.15（钳下限
     * 0.15——远方的光点可见不消失，用户口径「远处的光点=远方目标」）。
     * R247 立体感加深：参考距 2800→1900、指数 1.15→1.35、下限 0.22→0.15——
     * 近亮远暗对比 ~6:1（旧 ~4:1），配合微星深度分档（渲染层）恢复「近远深度差异」。
     */
    fun fogOf(depth: Float): Float =
        (1f / (1f + (depth / 1900f).pow(1.35f))).coerceIn(0.15f, 1f)

    // ==================== 天体分布（球面壳层化） ====================

    /**
     * 确定性 0..1 哈希（与 FxEngine.h01 同式——同 chatId 同散布，单测同口径锚定）。
     */
    internal fun h01(a: Long, b: Int): Float =
        (((a * -0x61c8897L and 0x7FFFFFFFL) xor (b * 0x1b873593L and 0x7FFFFFFFL)) and 0xFFFFFFL).toFloat() / 16777216f

    /**
     * 口味坐标 → 宇宙球面坐标（**App 口径空间系**，写 [out] 长 ≥3：x/y/z，Y 向上）：
     *
     *  - **方位角**=口味平面角（口味簇→天空扇区：朝那个方向看=那类内容的星）；
     *    口味中心 [CENTER_BLEND_R] 内方位不稳定（ρ→0 角度发散）→ 与 chatId 哈希角
     *    向量混合（确定性；中心星=「最典型口味」散布全天球各向）；
     *  - **带复原**：flightZ÷(Z_HALF×椭球渐缩因子)（因子按 XY 半径同式重算=精确
     *    复原 TasteDepthSpread 的三带结构）→ 近/中/远带号 + 带内位 inBand01；
     *  - **高度角**=(inBand01−0.5)×[ELEV_SPAN_DEG]（每带全天球纵向覆盖——上下左右
     *    前后都有星）；
     *  - **半径**=带号壳层（近 [SHELL_NEAR_MIN,MAX]/中/远）×哈希带内散布——任务书
     *    「近层<500 稀疏大星/中层 500~2000/远层 2000~8000」的口径；近带=常看星
     *    （TasteDepthSpread 活跃度语义承接：你身边的星=你最常看的）。
     *
     * 全确定性（chatId 哈希）——同数据同宇宙；**口味 x/y/z 原值不动**（海报链路
     * 继续消费旧坐标=零回归，双口径同 R231 flightZ 先例）。
     */
    fun spherify(tasteX: Float, tasteY: Float, flightZ: Float, chatId: Long, out: FloatArray) {
        val dx = tasteX - TASTE_CX
        val dy = tasteY - TASTE_CY
        val rxy = sqrt(dx * dx + dy * dy)
        // ---- 带复原（TasteDepthSpread 同式重算渐缩因子） ----
        val taper = if (rxy >= TAPER_R) 0f else sqrt(1f - (rxy / TAPER_R) * (rxy / TAPER_R))
        val factor = 0.35f + 0.65f * taper
        val z01 = flightZ / (Z_HALF * factor)
        val band: Int
        var inBand01: Float
        when {
            z01 < -0.32f -> {
                band = 0 // 近带（z01 ≈ −0.64±0.27）
                inBand01 = ((z01 + BAND_PITCH) / (2f * BAND_HALF) + 0.5f)
            }
            z01 > 0.32f -> {
                band = 2 // 远带（z01 ≈ +0.64±0.27）
                inBand01 = ((z01 - BAND_PITCH) / (2f * BAND_HALF) + 0.5f)
            }
            else -> {
                band = 1 // 中带
                inBand01 = (z01 / (2f * BAND_HALF) + 0.5f)
            }
        }
        inBand01 = inBand01.coerceIn(0f, 1f)
        // ---- 方位角（口味角 + 中心哈希混合） ----
        var theta = atan2(-dy, dx) // 口味 y 向下（旧世界系）→ 空间系取反为北
        if (rxy < CENTER_BLEND_R) {
            val w = rxy / CENTER_BLEND_R
            val hAng = h01(chatId, 31) * 2f * PI.toFloat()
            // 向量混合（角度插值无环绕问题）
            val vx = cos(theta) * w + cos(hAng) * (1f - w)
            val vy = sin(theta) * w + sin(hAng) * (1f - w)
            theta = atan2(vy, vx)
        }
        // ---- 高度角（每带全天球纵向覆盖） ----
        val phi = Math.toRadians(((inBand01 - 0.5f) * ELEV_SPAN_DEG).toDouble())
        // ---- 半径（带号壳层 + 哈希带内散布） ----
        val r = when (band) {
            0 -> SHELL_NEAR_MIN + (SHELL_NEAR_MAX - SHELL_NEAR_MIN) * h01(chatId, 21)
            1 -> SHELL_MID_MIN + (SHELL_MID_MAX - SHELL_MID_MIN) * h01(chatId, 22)
            else -> SHELL_FAR_MIN + (SHELL_FAR_MAX - SHELL_FAR_MIN) * h01(chatId, 23)
        }
        val cp = cos(phi).toFloat()
        out[0] = r * cp * cos(theta)
        out[1] = r * sin(phi).toFloat()
        out[2] = r * cp * sin(theta)
    }

    /**
     * 天幕星方向（确定性，写 [out] 长 ≥3：世界系单位方向）：均匀球面（u1=方位
     * 2π、u2=余弦均匀）；渲染端以 `cam + dir×远距` 画（方向锚定=无穷远语义：
     * 不随飞行视差、永远包围——「没有边际」）。tier/颜色由调用方按同式哈希取。
     */
    fun skyDirection(i: Int, out: FloatArray) {
        val u1 = h01(i.toLong() * 7L + 13L, 101)
        val u2 = h01(i.toLong() * 11L + 29L, 103)
        val theta = u1 * 2f * PI.toFloat()
        val cphi = 2f * u2 - 1f // 余弦均匀
        val sphi = sqrt((1f - cphi * cphi).coerceAtLeast(0f))
        out[0] = sphi * cos(theta)
        out[1] = cphi
        out[2] = sphi * sin(theta)
    }

    // ==================== R249 团块化球面（z 轴追加「远处看有厚度」的修法） ====================

    /** 团块成员方位微抖（弧度 ±）：团内哈希散布（口味角序由 memberAzOff 承担） */
    private const val BLOB_AZ_JITTER = 0.06f

    /** 团块成员高度微抖（弧度 ±9°）：团=浓核+哈希弥散 */
    private const val BLOB_ELEV_JITTER = 1.2f // R252b 第五次修复：0.16→1.2 弧度（±69°）——治「64% 星压在赤道平面」根因

    /**
     * R249 团块化球面坐标（**v2 只加不改**——[spherify] 原样保留给野星/旧口径）：
     * 用户追加实证（16384）：「远处看是一张平面」的病根=远壳无差别点墙（几何
     * 三维但深度线索被抹平）——修法=**团块 vs 空隙的三维结构对比**：
     *
     *  - **团锚**（布局期网格聚类算好传入）：[cluAzRad]=团方位（团均值口味角——
     *    口味→方向语义保留）；[cluElevRad]=团高度角（团哈希 ±62°——**各团强制
     *    错开高度**，同方位不同高度的团不合并成片=厚度的来源）；[cluRadSlot01]
     *    =团在带内的径向槽（团哈希——同方位不同团半径也错开）；
     *  - **成员**：[memberAzOff]=成员相对团方位的角序偏移（布局期把成员口味角差
     *    压缩 0.4 传入——**团内口味角序保留**）；半径=带区间×(0.12+0.76·槽
     *    +0.12·哈希)；高度=团高度±[BLOB_ELEV_JITTER]；
     *  - **带语义不动**：flightZ→近/中/远带（常看星近带）与 [spherify] 同一
     *    复原式——同口味团在三带各出一个子团=「你的口味在近处亮着、同口味的
     *    陌生频道在远处同方向」；
     *  - 全确定性（chatId 哈希）——同数据同宇宙；**口味 x/y/z 原值不动**（海报
     *    零回归）；野星仍走 [spherify]（全天球满覆盖——R242 沉浸包围不破）。
     */
    fun spherifyClustered(
        tasteX: Float,
        tasteY: Float,
        flightZ: Float,
        chatId: Long,
        cluAzRad: Float,
        cluElevRad: Float,
        cluRadSlot01: Float,
        memberAzOff: Float,
        out: FloatArray,
    ) {
        val dx = tasteX - TASTE_CX
        val dy = tasteY - TASTE_CY
        val rxy = sqrt(dx * dx + dy * dy)
        val band = bandIndexOf(flightZ, rxy)
        // 半径：带区间 ×（团槽 76% + 头尾垫 12%×2——团永不贴带边界）
        val r = when (band) {
            0 -> SHELL_NEAR_MIN + (SHELL_NEAR_MAX - SHELL_NEAR_MIN) *
                (0.12f + 0.76f * cluRadSlot01 + 0.12f * h01(chatId, 33))
            1 -> SHELL_MID_MIN + (SHELL_MID_MAX - SHELL_MID_MIN) *
                (0.12f + 0.76f * cluRadSlot01 + 0.12f * h01(chatId, 33))
            else -> SHELL_FAR_MIN + (SHELL_FAR_MAX - SHELL_FAR_MIN) *
                (0.12f + 0.76f * cluRadSlot01 + 0.12f * h01(chatId, 33))
        }
        val theta = cluAzRad + memberAzOff + (h01(chatId, 35) - 0.5f) * 2f * BLOB_AZ_JITTER
        val phi = (cluElevRad + (h01(chatId, 37) - 0.5f) * 2f * BLOB_ELEV_JITTER).coerceIn(-1.5f, 1.5f)
        val cp = cos(phi)
        out[0] = r * cp * cos(theta)
        out[1] = r * sin(phi)
        out[2] = r * cp * sin(theta)
    }

    /** 带复原：flightZ÷(Z_HALF×椭球渐缩因子)→带号（[spherify]/[spherifyClustered] 共用同一式） */
    private fun bandIndexOf(flightZ: Float, rxy: Float): Int {
        val taper = if (rxy >= TAPER_R) 0f else sqrt(1f - (rxy / TAPER_R) * (rxy / TAPER_R))
        val factor = 0.35f + 0.65f * taper
        val z01 = flightZ / (Z_HALF * factor)
        return when {
            z01 < -0.32f -> 0
            z01 > 0.32f -> 2
            else -> 1
        }
    }

    // ==================== R256 均匀球面分布 V3（用户第 6 次拍板：换方法不是调参数） ====================

    /** V3 近距（世界单位）：体量最大频道的球面半径（任务书口径「大频道近 400~800」的上沿锚） */
    const val V3_NEAR = 400f

    /** V3 远距（世界单位）：体量最小频道的球面半径（任务书口径「小频道远 2000~6000」） */
    const val V3_FAR = 6000f

    /**
     * R256 V3 均匀球面分配（**扔掉 MDS xy 推导**——旧 [spherify]/[spherifyClustered]
     * 的带复原/椭球渐缩/高度带整链废弃，两函数原样保留仅为旧口径单测锚定，布局
     * 不再消费）。用户原话「每颗星直接分配一个 3D 球面位置——上下左右前后都有星」：
     *
     *  - **方位角 θ**=[tasteAngle] 口味角直传（MDS 口味角度 atan2(y,x)→球面方位角：
     *    相似的频道分到相近方向——口味→方向是旧链唯一保留语义）；
     *  - **俯仰角 φ**=(h01(chatId,51)−0.5)·π 独立均匀 [-π/2,+π/2]（**与口味无关**
     *    ——就是均匀铺满天球；抬头低头四周都有星=3D 包围感）；
     *  - **半径 r**=V3_NEAR+(V3_FAR−V3_NEAR)·(1−volume01)（**从数据量来**：大频道
     *    近、小频道远——近大远小的深度线索即体量本身）。
     *
     * 全确定性（chatId 哈希）——同数据同宇宙；写 [out] 长 ≥3（Y 向上、球心=原点=
     * 初始相机位——你在星星中间）。
     */
    fun spherifyV3(tasteAngle: Float, chatId: Long, volume01: Float, out: FloatArray) {
        val theta = tasteAngle
        val phi = (h01(chatId, 51) - 0.5f) * PI.toFloat()
        val r = V3_NEAR + (V3_FAR - V3_NEAR) * (1f - volume01.coerceIn(0f, 1f))
        val cp = cos(phi)
        out[0] = r * cp * cos(theta)
        out[1] = r * sin(phi)
        out[2] = r * cp * sin(theta)
    }

    // ==================== R267 近星吞没保护（贴脸大色块糊屏根治） ====================

    /**
     * 吞没保护阈（屏短边比例）：天体投影半径 > min(屏宽,屏高)×本值 → 渐隐+钳制。
     * 任务书口径「>屏宽 35%」；竖屏短边=宽。红米 1216×2688 → 阈 425px 半径。
     */
    const val SWALLOW_MAX_R_FRAC = 0.35f

    /**
     * 吞没渐隐起点（屏短边比例）：投影半径超此值即开始渐隐——16384 实锤教训：
     * 只钳 35% 阈会把「巨大半透明圆球糊屏」合法化（满 alpha 大色块贴脸）；
     * 贴脸星必须在糊屏之前就淡出（穿越=星在你周围化开，不是拍你一脸）。
     * 12% 起点=光球质感展示窗（30px~0.12×短边）完整保留。
     */
    const val SWALLOW_FADE_START_FRAC = 0.12f

    /**
     * 吞没渐隐因子（纯函数）：r≤fadeStart → 1（零改动）；fadeStart..max 线性 →0；
     * r≥max → 0（绘制端同时把 r 钳到 max——超大球完全透明且有界）。与既有
     * depth<90 深度渐隐（物理距离归零段）接力叠乘：本函数管「投影尺寸爆炸」，
     * 深度渐隐管「穿星无缝」，两段无缝接力。
     */
    fun swallowFade(rPx: Float, fadeStartPx: Float, maxPx: Float): Float = when {
        maxPx <= fadeStartPx -> 1f // 防御（小屏异常）——不拦
        rPx <= fadeStartPx -> 1f
        rPx >= maxPx -> 0f
        else -> 1f - (rPx - fadeStartPx) / (maxPx - fadeStartPx)
    }

    // ==================== 飞船尾迹光流（R235 迁移口径） ====================

    /**
     * 参考星层光流（**真身=深度 z_ref 平面的光学流**，写 [out] 长 ≥3）：
     *  - `[0]/[1]` 平移流（px/s）：转头（yaw/pitch 角速度）→ 星野反向平移
     *    （turnRight→星左移/抬头→星下移）；
     *  - `[2]` 径向涨缩率（1/s，作用于屏心离心距）：前飞 v → 星野向外涌
     *    （rate=+v/z_ref：屏心离心距 ×此率=该点径向速度；z_ref=1100 参考深度）。
     * 位置/角速度全从相机帧间差分——ShipTrailEngine 流场喂给端（streak 取向与
     * 长度的原料，「星星从身边掠过」的数学）。
     */
    fun opticalFlow(prev: SpaceCam, cam: SpaceCam, focal: Float, dtSec: Float, out: FloatArray) {
        if (dtSec <= 1e-4f) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return }
        val yawRate = Math.toRadians(angDiff(prev.yawDeg, cam.yawDeg).toDouble()).toFloat() / dtSec
        val pitchRate = Math.toRadians(angDiff(prev.pitchDeg, cam.pitchDeg).toDouble()).toFloat() / dtSec
        out[0] = -yawRate * focal // 转右（yawRate>0）→ 星左移
        out[1] = pitchRate * focal // 抬头（pitchRate>0）→ 星下移
        val dx = cam.px - prev.px
        val dy = cam.py - prev.py
        val dz = cam.pz - prev.pz
        val v = sqrt(dx * dx + dy * dy + dz * dz) / dtSec
        out[2] = v / 1100f // 前飞 → 星向外涌（屏心离心距×正率=径向外流）
    }
}
