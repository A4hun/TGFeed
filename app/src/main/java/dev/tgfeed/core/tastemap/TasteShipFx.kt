package dev.tgfeed.core.tastemap

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R235 飞船航迹引擎（口味星图四期·RPG 层·纯 Kotlin 状态机+纯函数，单测可跑）。
 *
 * **「你在飞=后面拖的尾迹」的第一人称实现口径（诚实标注）**：相机即飞船
 * （TasteFlight.FlightCam 世界位 (x, y, −dist_eff)），但投影模型里**相机自身平面上的世界点
 * 恒被近裁面剔除**（depth = z + dist_eff = 0 < DEPTH_CULL）——世界坐标的「相机
 * 轨迹线」永远不可见（数学事实，不是缺陷）。第一人称飞船的「尾迹」在本引擎里
 * =**参考星流场**（z=0 平面星层的光流）：相机平移时星野整体反向流动、俯冲/
 * 拉远时视距变化产生径向涨缩——流场矢量就是「你拖出来的尾迹」的真身，速度
 * 越快尾迹越长（任务书「dive 速率联动」——dist_eff 变化率正是流场径向分量）。
 *
 * 结构（FxEngine 同款纪律）：
 *  - **纯函数**（[flowOf]/[speed01Of]/[streakLenOf]…）：流场/速度归一/尾长/粒子
 *    速率/波纹阈值的单一出处，单测锚定；
 *  - **状态机** [ShipTrailEngine]：定长环形数组（粒子 ≤[PARTICLE_CAP]、波纹
 *    ≤[RIPPLE_CAP]），帧步进零分配；持有 prevCam/平滑速度/流场分量——渲染器
 *    只读不写（「渲染器无逐帧状态」纪律沿档）。
 *
 * 等阶→酷度（[TasteAchievements.ShipStyle] 驱动，本文件只管量的部分）：
 *  - 青铜 MICRO_TRAIL：只画流场细线（微光白）——低速几乎隐形（诚实）；
 *  - 白银 BLUE_PULSE：+引擎脉冲（屏心脉动晕，速度驱动）；
 *  - 黄金 GOLD_PARTICLES：+金拖尾粒子（沿流场漂移消散）；
 *  - 钻石 WARP_RIPPLE：+跃迁波纹（速度过阈边沿触发扩散环）+护盾光环（渲染器
 *    屏角弧）；
 *  - 传奇 RAINBOW_STORM：单色星尘（R348 C1 彩虹退役——色相随时间旋转撤除，
 *    色=shipColorOf 星尘暖白同源；等阶语义由粒子密度/尾长档差承担）。
 */
object TasteShipFx {

    // ---- 速度量纲（世界单位/秒 → 归一） ----

    /** 速度归一上限（世界单位/秒）：≈一屏俯冲的量级——speed01 到 1 = 全特效满档 */
    const val SPEED_CAP = 2200f

    /** 速度平滑系数（帧间指数平滑 0..1——抖动不闪脸，加速有惯性感） */
    const val SPEED_SMOOTH = 0.35f

    /** 流场速度下限（速度 01）：低于此尾迹完全隐形（静止=零尾迹，诚实） */
    const val FADE_FLOOR = 0.06f

    // ---- 尾长（速度联动：尾迹长度 ∝ 速度 01，等阶只调上限——「勋章越高越酷」） ----

    /** 尾长基准（×min(w,h)）：speed01=1 时的满档尾长 */
    const val STREAK_BASE = 0.30f

    /** 等阶尾长系数（青铜 0.6 → 传奇 1.0：同速度下高级船尾更长） */
    fun streakCapOf(style: TasteAchievements.ShipStyle): Float = when (style) {
        TasteAchievements.ShipStyle.MICRO_TRAIL -> 0.60f
        TasteAchievements.ShipStyle.BLUE_PULSE -> 0.72f
        TasteAchievements.ShipStyle.GOLD_PARTICLES -> 0.84f
        TasteAchievements.ShipStyle.WARP_RIPPLE -> 0.92f
        TasteAchievements.ShipStyle.RAINBOW_STORM -> 1.00f
    }

    /** 尾长（×min(画布宽高)）：基长 × speed01^0.7（次线性——低速即显、高速饱和） */
    fun streakLenOf(style: TasteAchievements.ShipStyle, speed01: Float, minSide: Float): Float {
        if (speed01 <= FADE_FLOOR) return 0f
        val s = Math.pow(speed01.toDouble(), 0.7).toFloat()
        return STREAK_BASE * streakCapOf(style) * s * minSide
    }

    // ---- 粒子（黄金+：拖尾粒子） ----

    /** 粒子环容量（护栏上限——帧预算件：单次 drawPoints ≤64 点） */
    const val PARTICLE_CAP = 64

    /** 粒子速率上限（个/秒，speed01=1 满档） */
    const val PARTICLE_RATE_MAX = 46f

    /** 粒子寿命（秒） */
    const val PARTICLE_LIFE = 0.85f

    /** 等阶是否有拖尾粒子（黄金/传奇） */
    fun hasParticlesOf(style: TasteAchievements.ShipStyle): Boolean = when (style) {
        TasteAchievements.ShipStyle.GOLD_PARTICLES, TasteAchievements.ShipStyle.RAINBOW_STORM -> true
        else -> false
    }

    /** 粒子速率（个/秒） = 上限 × speed01²（低速克制——帧预算与观感双护栏） */
    fun particleRateOf(style: TasteAchievements.ShipStyle, speed01: Float): Float =
        if (hasParticlesOf(style) && speed01 > FADE_FLOOR) PARTICLE_RATE_MAX * speed01 * speed01 else 0f

    // ---- 跃迁波纹（钻石+） ----

    /** 波纹环容量（同屏 ≤3 环） */
    const val RIPPLE_CAP = 3

    /** 波纹触发速度阈（speed01：过阈瞬间边沿触发——「跃迁」的仪式感） */
    const val RIPPLE_SPEED01 = 0.55f

    /** 波纹生命周期（秒——扩散环的时长） */
    const val RIPPLE_LIFE = 1.4f

    /** 波纹再武装滞回（speed01 回落此值以下才允许下一次触发——防抖） */
    const val RIPPLE_REARM_01 = 0.40f

    /** 等阶是否有跃迁波纹（钻石/传奇） */
    fun hasRipplesOf(style: TasteAchievements.ShipStyle): Boolean = when (style) {
        TasteAchievements.ShipStyle.WARP_RIPPLE, TasteAchievements.ShipStyle.RAINBOW_STORM -> true
        else -> false
    }

    // ---- 流场（参考星层光流：尾迹的真身） ----

    /**
     * 流场增量（纯函数）：prevCam → cam 一帧的参考星层（z=0 平面）光流。
     *
     *  - 平移分量 [outU]/[outV]（像素）：相机走 (dx, dy)，z=0 星整体反向流动
     *    （星屏位 u=(x−cam.x)·k——cam 增即星左移）；量纲=一帧像素位移，除 dt 得
     *    流速（调用方持有复用数组，帧内零分配）；
     *  - 涨缩比 [outS]：k_now/k_prev（focal_eff/d 的帧间比）——>1 径向涨（俯冲
     *    dist_eff 收缩=星野向外涌），<1 径向缩（拉远）。roll 是绕屏心等距变换
     *    （星流方向随画布系走——渲染端无需逆旋）。
     */
    fun flowOf(
        prevCam: TasteFlight.FlightCam,
        cam: TasteFlight.FlightCam,
        fitScale: Float,
        out: FloatArray,
    ) {
        val kNow = TasteFlight.focalEff(fitScale, cam) / TasteFlight.camDist(cam).coerceAtLeast(1f)
        val kPrev = TasteFlight.focalEff(fitScale, prevCam) / TasteFlight.camDist(prevCam).coerceAtLeast(1f)
        out[0] = -(cam.x - prevCam.x) * kNow
        out[1] = (cam.y - prevCam.y) * kNow
        out[2] = if (kPrev > 1e-6f) kNow / kPrev else 1f
    }

    /** 相机世界速度（世界单位/秒）：3D 位移范数 / dt（含平移+纵深+缩放全程） */
    fun speedOf(prevCam: TasteFlight.FlightCam, cam: TasteFlight.FlightCam, dtSec: Float): Float {
        if (dtSec <= 1e-4f) return 0f
        val dx = cam.x - prevCam.x
        val dy = cam.y - prevCam.y
        val dd = TasteFlight.camDist(prevCam) - TasteFlight.camDist(cam) // dist_eff 收缩=前飞量
        return sqrt(dx * dx + dy * dy + dd * dd) / dtSec
    }

    /** 速度归一 0..1（钳 [SPEED_CAP]） */
    fun speed01Of(speed: Float): Float = (speed / SPEED_CAP).coerceIn(0f, 1f)

    /** 尾迹可见透明度（0..1：FADE_FLOOR 起坡到 1——静止=零尾迹） */
    fun alphaOf(style: TasteAchievements.ShipStyle, speed01: Float): Float {
        if (speed01 <= FADE_FLOOR) return 0f
        val a = ((speed01 - FADE_FLOOR) / (1f - FADE_FLOOR)).coerceIn(0f, 1f)
        return when (style) {
            TasteAchievements.ShipStyle.MICRO_TRAIL -> 0.16f * a        // 微尾档
            TasteAchievements.ShipStyle.BLUE_PULSE -> 0.26f * a
            TasteAchievements.ShipStyle.GOLD_PARTICLES -> 0.30f * a
            TasteAchievements.ShipStyle.WARP_RIPPLE -> 0.32f * a
            TasteAchievements.ShipStyle.RAINBOW_STORM -> 0.34f * a
        }
    }

    /** 确定性 0..1 哈希（TasteTagPlanets.h01 同式镜像——粒子种子/闪面共用） */
    internal fun h01(a: Int, b: Int): Float =
        (((a * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF).toFloat() / 16777216f

    // ==================== 状态机 ====================

    /**
     * 飞船航迹状态机（TasteMapScreen 持有 remember、帧循环 step、渲染器只读）：
     * 平滑速度+流场分量+粒子/波纹环，全部定长数组零逐帧分配。
     *
     * 粒子为**屏幕空间**（屏心相对坐标 dx/dy——渲染端画在 (cx+dx, cy+dy)）：
     * 出生=流场反方向尾区（尾迹越长粒子出生域越远）+速度=流场矢量与径向涨缩
     * 的合成（沿尾迹漂移消散）；世界坐标方案在相机平面恒被近裁面剔除
     * （对象 KDoc 的数学事实）。[minSidePx]=画布短边（归一半径换 px 的参照）。
     */
    class ShipTrailEngine {
        // ---- 相机轨迹（速度/流场原料） ----
        private var prevX = Float.NaN
        private var prevY = Float.NaN
        private var prevZoom = Float.NaN
        private var prevDive = Float.NaN

        /** 平滑速度 01（帧循环写入，渲染端只读） */
        var speed01 = 0f
            private set

        /** 一帧流场平移分量（像素：星层反向流动方向）+涨缩比（粒子出生取向用） */
        var flowU = 0f
            private set
        var flowV = 0f
            private set
        var flowScale = 1f
            private set

        /** 流场速度（px/s：flowU/dt）与径向涨缩率（(s−1)/dt——渲染端 streak 取向
         *  与长度的原料：局部流速 = (flowVelU + rx·flowRateS, flowVelV + ry·flowRateS)） */
        var flowVelU = 0f
            private set
        var flowVelV = 0f
            private set
        var flowRateS = 0f
            private set

        /** 会话秒（引擎内累计——粒子相位/波纹用） */
        var timeSec = 0f
            private set

        // ---- 粒子环（屏心相对坐标 + 速度 px/s + 寿命 + 种子） ----
        internal val pDx = FloatArray(PARTICLE_CAP)
        internal val pDy = FloatArray(PARTICLE_CAP)
        internal val pVx = FloatArray(PARTICLE_CAP)
        internal val pVy = FloatArray(PARTICLE_CAP)
        internal val pLife = FloatArray(PARTICLE_CAP)
        internal val pSeed = IntArray(PARTICLE_CAP)
        internal var pCount = 0
        private var spawnSerial = 0

        // ---- 波纹环（边沿触发：速度过阈瞬间的扩散事件；age 秒） ----
        internal val rAge = FloatArray(RIPPLE_CAP)
        internal var rCount = 0
        private var rippleArmed = true

        private var spawnAcc = 0f
        private val scratch = FloatArray(3)

        // ---- R242 空间相机基准（stepSpace 专用——旧 FlightCam 基准并存互不干扰） ----
        private var spPrevX = Float.NaN
        private var spPrevY = Float.NaN
        private var spPrevZ = Float.NaN
        private var spPrevYaw = 0f
        private var spPrevPitch = 0f

        /**
         * R242 空间相机帧步进（SpaceCam 口径——旋转平移流+前飞径向涨缩）：
         *  - 速度=|Δ位|/dt（3D 世界位移范数——转头不产生速度，飞了才算飞）；
         *  - 流场=[TasteSpace.opticalFlow]（转头→星野反向平移 / 前飞→径向外涌——
         *    「星星从身边掠过」的数学真身）；
         *  - 粒子/波纹逻辑与旧 [step] 完全共享（advanceAfterMotion 单口——不是第二
         *    份拷贝）。
         */
        fun stepSpace(cam: TasteSpace.SpaceCam, focal: Float, dtSec: Float, style: TasteAchievements.ShipStyle, minSidePx: Float) {
            timeSec += dtSec
            if (spPrevX.isNaN() || dtSec <= 1e-4f) {
                snapshotSpace(cam)
                return
            }
            val prev = TasteSpace.SpaceCam(spPrevX, spPrevY, spPrevZ, spPrevYaw, spPrevPitch)
            val dx = cam.px - prev.px
            val dy = cam.py - prev.py
            val dz = cam.pz - prev.pz
            val inst = sqrt(dx * dx + dy * dy + dz * dz) / dtSec
            val target = speed01Of(inst)
            speed01 = if (speed01 <= 0f) target else speed01 + (target - speed01) * SPEED_SMOOTH
            // 流场（px/s + 径向率）
            TasteSpace.opticalFlow(prev, cam, focal, dtSec, scratch)
            flowVelU = scratch[0]
            flowVelV = scratch[1]
            flowRateS = scratch[2]
            // 旧字段口径同步（粒子出生方向用帧位移、径向用帧比——渲染端消费同族）
            flowU = flowVelU * dtSec
            flowV = flowVelV * dtSec
            flowScale = 1f + flowRateS * dtSec
            snapshotSpace(cam)
            advanceAfterMotion(dtSec, style, minSidePx)
        }

        private fun snapshotSpace(cam: TasteSpace.SpaceCam) {
            spPrevX = cam.px
            spPrevY = cam.py
            spPrevZ = cam.pz
            spPrevYaw = cam.yawDeg
            spPrevPitch = cam.pitchDeg
        }

        /**
         * 帧步进（帧循环每拍调用；渲染前完成状态更新）：
         * [style] 传入以门控粒子/波纹的产出（青铜/白银不白养粒子）；
         * [minSidePx]=画布短边 px（粒子出生域的参照）。
         */
        fun step(cam: TasteFlight.FlightCam, fitScale: Float, dtSec: Float, style: TasteAchievements.ShipStyle, minSidePx: Float) {
            timeSec += dtSec
            // 首帧：只记基准不产出
            if (prevX.isNaN() || dtSec <= 1e-4f) {
                snapshot(cam)
                return
            }
            val prev = TasteFlight.FlightCam(prevX, prevY, prevZoom, 0f, prevDive)
            // 速度：瞬时 → 指数平滑（SPEED_SMOOTH）
            val inst = speedOf(prev, cam, dtSec)
            val target = speed01Of(inst)
            speed01 = if (speed01 <= 0f) target else speed01 + (target - speed01) * SPEED_SMOOTH
            // 流场分量（渲染端 streak 取向与涨缩用）
            flowOf(prev, cam, fitScale, scratch)
            flowU = scratch[0]
            flowV = scratch[1]
            flowScale = scratch[2]
            val inv = 1f / dtSec
            flowVelU = flowU * inv
            flowVelV = flowV * inv
            flowRateS = (flowScale - 1f) * inv
            snapshot(cam)
            advanceAfterMotion(dtSec, style, minSidePx)
        }

        /**
         * 运动后共用步进（粒子+波纹——旧 [step] 与 R242 [stepSpace] 单口共享，
         * 不是第二份拷贝：速度/流场字段就位后调用）。
         */
        private fun advanceAfterMotion(dtSec: Float, style: TasteAchievements.ShipStyle, minSidePx: Float) {
            // ---- 粒子步进+出生（屏幕空间：沿流场漂移） ----
            stepParticles(dtSec)
            val rate = particleRateOf(style, speed01)
            if (rate > 0f) {
                spawnAcc += rate * dtSec
                while (spawnAcc >= 1f) {
                    spawnAcc -= 1f
                    spawnParticle(minSidePx)
                }
            } else {
                spawnAcc = 0f
            }
            // ---- 波纹（边沿触发+滞回再武装） ----
            for (i in 0 until rCount) rAge[i] += dtSec
            var compact = 0
            for (i in 0 until rCount) {
                if (rAge[i] < RIPPLE_LIFE) rAge[compact++] = rAge[i]
            }
            rCount = compact
            if (hasRipplesOf(style)) {
                if (rippleArmed && speed01 >= RIPPLE_SPEED01) {
                    if (rCount < RIPPLE_CAP) rAge[rCount++] = 0f
                    rippleArmed = false
                } else if (!rippleArmed && speed01 < RIPPLE_REARM_01) {
                    rippleArmed = true
                }
            } else {
                rippleArmed = true // 无波纹等阶保持武装（换等阶即触发的语义简单直白）
            }
        }

        private fun snapshot(cam: TasteFlight.FlightCam) {
            prevX = cam.x
            prevY = cam.y
            prevZoom = cam.zoom
            prevDive = cam.dive
        }

        private fun stepParticles(dtSec: Float) {
            for (i in 0 until pCount) {
                if (pLife[i] <= 0f) continue
                pLife[i] -= dtSec
                if (pLife[i] > 0f) {
                    pDx[i] += pVx[i] * dtSec
                    pDy[i] += pVy[i] * dtSec
                }
            }
            // 淘汰已死（前缀压缩——count 上限 64，O(n) 常数级）
            var compact = 0
            for (i in 0 until pCount) {
                if (pLife[i] > 0f) {
                    if (compact != i) {
                        pDx[compact] = pDx[i]; pDy[compact] = pDy[i]
                        pVx[compact] = pVx[i]; pVy[compact] = pVy[i]
                        pLife[compact] = pLife[i]; pSeed[compact] = pSeed[i]
                    }
                    compact++
                }
            }
            pCount = compact
        }

        /**
         * 粒子出生（屏心相对坐标系）：
         *  - 出生域=流场反方向（尾迹方向）扇形——尾迹长粒子域远；
         *  - 速度=流场矢量（px/s）×哈希速率系数 + 径向涨缩分量（俯冲时星野外涌
         *    的方向粒子也被推开——「尾迹被速度甩开」的观感）。
         */
        private fun spawnParticle(minSidePx: Float) {
            if (pCount >= PARTICLE_CAP) return // 护栏：满环丢新（速率上限永不触）
            val seed = (spawnSerial * 2654435761L).toInt()
            spawnSerial++
            val idx = pCount
            // 流向单位向量（尾迹方向 = 星层流动方向 = −(相机平移方向)）
            val fx = flowU
            val fy = flowV
            val fl = hypot(fx, fy)
            val dirX = if (fl > 1e-3f) fx / fl else 0f
            val dirY = if (fl > 1e-3f) fy / fl else 0f
            val spread = (h01(seed, 3) - 0.5f) * 1.6f // 扇形散布角（弧度级）
            val cosS = cos(spread)
            val sinS = sin(spread)
            val rx = dirX * cosS - dirY * sinS
            val ry = dirX * sinS + dirY * cosS
            val dist01 = 0.16f + 0.30f * h01(seed, 5) // 出生半径（×短边）
            pDx[idx] = rx * dist01 * minSidePx
            pDy[idx] = ry * dist01 * minSidePx
            // 流速（px/s）=帧位移/帧时；速率系数哈希 0.7..1.2
            val rateCoef = 0.7f + 0.5f * h01(seed, 9)
            val invDt = 1f / 0.0167f // 帧率无关的近似换算基（60fps 基准——观感量级即可）
            var vx = flowU * invDt * rateCoef
            var vy = flowV * invDt * rateCoef
            // 径向涨缩分量：半径 R 处的径向速度 ≈ R×(s−1)/dt（俯冲外涌）
            val radial = (flowScale - 1f) * invDt * 0.55f
            if (radial != 0f) {
                val rl = hypot(pDx[idx], pDy[idx])
                if (rl > 1f) {
                    vx += pDx[idx] / rl * radial * rl * 0.02f
                    vy += pDy[idx] / rl * radial * rl * 0.02f
                }
            }
            pVx[idx] = vx
            pVy[idx] = vy
            pLife[idx] = PARTICLE_LIFE * (0.72f + 0.56f * h01(seed, 13))
            pSeed[idx] = seed
            pCount++
        }
    }
}
