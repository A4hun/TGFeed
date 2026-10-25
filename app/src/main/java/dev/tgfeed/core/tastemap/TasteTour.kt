package dev.tgfeed.core.tastemap

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R271 星图新用户开局引导运镜政策件（**巡航常数与决策的唯一出处**——纯 Kotlin
 * 零 Android 依赖，JVM 单测锚定；GestureFeelPolicy 同款收口纪律）：
 *
 *  - **剧本**（四段，全部复用 SpaceCam/focusFlight 既有相机链——不新造相机轮子）：
 *    推近（全景→亮星前中距）→ 绕亮星半圈（始终盯星）→ 点亮段（井×4.6 停船，
 *    R248 发现机制井深比 4.6<10 自动点亮）→ 驻留看清 → 缓出回全景；
 *  - **频控**：每用户最多自动播 [MAX_AUTO_PLAYS] 次（AppPrefs 计数落盘）；
 *    第二次需「星数量大幅增长」（当前星数 ≥ 基线×[GROWTH_FACTOR]）；
 *  - **让位**：用户任何手势立即中断（接线在 TasteMapScreen 九处相机接管点——
 *    本件只供「段/参数」语义，让位动作在消费侧）。
 */
object TasteTour {

    // ==================== 频控 ====================

    /** 每用户自动巡航次数硬顶（开播即计——中断也算播过：频控=防打扰语义） */
    const val MAX_AUTO_PLAYS = 2

    /** 「星数量大幅增长」口径：当前在图星数 ≥ 上次巡航基线×本值（第二次开播闸） */
    const val GROWTH_FACTOR = 1.5f

    /** 触发下限：在图星数少于此值不成「宇宙」（巡航无意义——诚实不开） */
    const val MIN_STARS = 8

    /** 启航延迟（ms）：装配/揭示动画（REVEAL_MS=1500）播完再带飞，窗内手势即取消 */
    const val START_DELAY_MS = 1800L

    /**
     * 自动开播判定：计数到顶永不播；首播无条件；第二播需星数大幅增长（基线×
     * [GROWTH_FACTOR] 且严格变多；基线 0=脏数据宽容放行）。
     */
    fun shouldAutoPlay(count: Int, baseStars: Int, nowStars: Int): Boolean {
        if (count >= MAX_AUTO_PLAYS) return false
        if (count <= 0) return true
        return nowStars > baseStars && nowStars >= baseStars * GROWTH_FACTOR
    }

    // ==================== 段号与参数 ====================

    const val LEG_PUSH = 0 // 推近：全景→亮星前
    const val LEG_ORBIT = 1 // 绕转：绕亮星半圈
    const val LEG_LIGHT = 2 // 点亮：飞向未探索星（井×4.6 停船）
    const val LEG_DWELL = 3 // 驻留：盯星看点亮动画
    const val LEG_EASE = 4 // 缓出：回全景

    fun legName(leg: Int): String = when (leg) {
        LEG_PUSH -> "推近"
        LEG_ORBIT -> "绕转"
        LEG_LIGHT -> "点亮"
        LEG_DWELL -> "驻留"
        LEG_EASE -> "缓出"
        else -> "?"
    }

    /** 推近段停船距离 = 绕转半径×本值（中距推近——「从全景缓慢推近」的落点） */
    const val PUSH_STOP_FACTOR = 2.2f

    /** 推近段停船下限 = 绕转半径×本值（不越过绕转轨道——推近落点恒在轨道外） */
    const val PUSH_MIN_FACTOR = 1.3f

    /** 推近段相对现距的目标比例（亮星原本就近时仍真推近：现距×本值——16384 实锤
     *  「亮星在 2.2×绕转半径内→推近段零平移只剩转头」空心段的修法） */
    const val PUSH_DIST_FACTOR = 0.6f

    /**
     * 推近段停船距离（世界单位）：亮星远（现距 > [PUSH_STOP_FACTOR]×绕转半径）→
     * 停 [PUSH_STOP_FACTOR]×绕转半径；亮星近 → 停 max([PUSH_MIN_FACTOR]×绕转半径,
     * 现距×[PUSH_DIST_FACTOR])——**推近段恒有真平移**（approach 只进不退，停距 ≥
     * 现距时整段零平移=空心段，本函数把停距收到现距内侧）。
     */
    fun pushStop(orbitR: Float, dist: Float): Float =
        (orbitR * PUSH_STOP_FACTOR).coerceAtMost((orbitR * PUSH_MIN_FACTOR).coerceAtLeast(dist * PUSH_DIST_FACTOR))

    /** 绕转段时长（秒——半圈缓入缓出，电影感不晕眩） */
    const val ORBIT_SEC = 6.0f

    /** 绕转扫角（弧度）：半圈=π */
    const val ORBIT_SWEEP_RAD = Math.PI.toFloat()

    /** 绕转半径收口窗口（段前本值比例内从到位实测半径缓收到停船半径——无径向跳变） */
    const val ORBIT_RADIAL_BLEND = 0.25f

    /** 驻留时长（ms——R248 打字机名签 1.2s+扩散环播完，用户看清「这颗星亮了」） */
    const val DWELL_MS = 2600L

    /** 缓出段 τ（秒——比巡航 0.9s 更缓的离场） */
    const val EASE_TAU_SEC = 1.3f

    /** 单段超时兜底（秒——N 体漂移/收敛角闸卡死时强行推进下一段，巡航不挂死） */
    const val LEG_TIMEOUT_SEC = 16f

    /** 轨迹探针节流（ms——相机位随时间曲线的采样粒度） */
    const val PROBE_MS = 250L

    // ==================== 选星（纯函数） ====================

    /** 巡航选星输入（StarNode 的裁剪投影——core 不反向依赖 ui 私有类） */
    class TourStar(
        val id: Long,
        val itemsCount: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        val known: Boolean,
    )

    /**
     * 亮星（绕转主角）：**已点亮星优先**（绕转段井深比 4.6<10——选已知星绕转才不抢
     * 点亮段的「发现时刻」），已知池空回退全体；池内体量最大者（亮度=体量驱动）。
     */
    fun pickBright(stars: List<TourStar>): TourStar? {
        if (stars.isEmpty()) return null
        val known = stars.filter { it.known }
        val pool = if (known.isNotEmpty()) known else stars
        return pool.maxByOrNull { it.itemsCount }
    }

    /**
     * 点亮目标（未探索星）：未知 ∧ 非亮星；**有体量优先**（itemsCount>0=到达即行星层
     * 浮现的完整终幕；空池回退全体未知）；池内取离亮星最近者（轨道终点短跳——
     * 读得懂的运镜，不横穿宇宙）。
     */
    fun pickLightTarget(stars: List<TourStar>, brightId: Long): TourStar? {
        val bright = stars.firstOrNull { it.id == brightId } ?: return null
        val unknown = stars.filter { !it.known && it.id != brightId }
        if (unknown.isEmpty()) return null
        val pool = unknown.filter { it.itemsCount > 0 }.ifEmpty { unknown }
        return pool.minByOrNull { s ->
            val dx = s.x - bright.x
            val dy = s.y - bright.y
            val dz = s.z - bright.z
            dx * dx + dy * dy + dz * dz
        }
    }

    // ==================== 绕转轨道数学（纯函数——消费侧帧循环逐帧取值） ====================

    /** 绕转缓动（smoothstep 缓入缓出——起步/收角不硬） */
    fun orbitEase(p: Float): Float {
        val t = p.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 绕转角（弧度）：0 → [ORBIT_SWEEP_RAD]（半圈），缓入缓出 */
    fun orbitTheta(p: Float): Float = ORBIT_SWEEP_RAD * orbitEase(p)

    /** 绕转半径：段前 [ORBIT_RADIAL_BLEND] 窗口内从 r0（推近到位实测）smoothstep 收到 r1（停船半径） */
    fun orbitRadiusAt(r0: Float, r1: Float, p: Float): Float {
        val q = (p / ORBIT_RADIAL_BLEND).coerceIn(0f, 1f)
        return r0 + (r1 - r0) * orbitEase(q)
    }

    /**
     * 绕转轨道平面基（写 [out] 长 ≥6：[0..2]=u 起点半径方向，[3..5]=v 扫角方向）：
     * u=normalize(相机位−星心)（θ=0 即推近到位点——无跳变）；法向 n=u×世界上叉积
     * （u 近竖直时回退 ×(1,0,0)）；v=n×u。返回 false=相机贴星心（退化不可绕）。
     */
    fun orbitBasis(
        px: Float, py: Float, pz: Float,
        cx: Float, cy: Float, cz: Float,
        out: FloatArray,
    ): Boolean {
        var ux = px - cx
        var uy = py - cy
        var uz = pz - cz
        val len = sqrt(ux * ux + uy * uy + uz * uz)
        if (len < 1e-3f) return false
        ux /= len; uy /= len; uz /= len
        // n = u × up（up=(0,1,0)）：展开 (uy*0−uz*1, uz*0−ux*0, ux*1−uy*0) = (−uz, 0, ux)
        var nx = -uz
        var ny = 0f
        var nz = ux
        var nl = sqrt(nx * nx + nz * nz)
        if (nl < 1e-3f) {
            // u≈竖直：n = u × (1,0,0)，展开 (uy*0−uz*0, uz*1−ux*0, ux*0−uy*1) = (0, uz, −uy)
            nx = 0f; ny = uz; nz = -uy
            nl = sqrt(ny * ny + nz * nz)
            if (nl < 1e-3f) return false
        }
        nx /= nl; ny /= nl; nz /= nl
        // v = n × u
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        out[0] = ux; out[1] = uy; out[2] = uz
        out[3] = vx; out[4] = vy; out[5] = vz
        return true
    }

    /** 绕转轨道点（写 [out] 长 ≥3）：星心 + r·(u·cosθ + v·sinθ) */
    fun orbitPos(
        cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        r: Float, theta: Float,
        out: FloatArray,
    ): FloatArray {
        val c = cos(theta)
        val s = sin(theta)
        out[0] = cx + r * (ux * c + vx * s)
        out[1] = cy + r * (uy * c + vy * s)
        out[2] = cz + r * (uz * c + vz * s)
        return out
    }
}
