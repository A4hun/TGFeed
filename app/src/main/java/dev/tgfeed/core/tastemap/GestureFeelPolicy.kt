package dev.tgfeed.core.tastemap

import kotlin.math.pow
import kotlin.math.sin

/**
 * R268 星图手势手感政策件（design-gesture.md 的代码化身——**星图全部手感常数的
 * 唯一出处**，纯 Kotlin 零 Android 依赖，JVM 单测锚定）：
 *
 *  - **背景**：六轮手势补丁（R206→R210→R231→R237→R241→R247）各修各的局部，阻尼
 *    系数/惯性衰减/判定阈值散在 4 个文件 20+ 处。本件把散落常数一次性收口——
 *    消费方（TasteSpace/TasteFlightGestures/TasteMapScreen）只许引用本件，
 *    **任何新手感参数只许先进本件**（单测锚定后才准接线，design §九归口纪律）；
 *  - **基准=R247 用户真机拍板值原样不动**（收口不改手感——所有数值锚定既有实现）；
 *  - **唯二行为级修正（用户 2026-10-20 终审全批）**：
 *    ①fling 初速「末事件×60」→ **≤[FLING_VELOCITY_WINDOW_MS]ms 时间窗测速**
 *    （[FlingVelocityWindow]——120Hz 屏旧式低估约一半初速=隐性手感分裂）；
 *    ②引导浮层文案「星野跟手」→「像转头看四周」口径（文案在 TasteMapScreen，
 *    键升 v3——非本件口径，此处仅挂账）；
 *  - **冻结件挂账**：TasteFlight.kt（旧引擎）的 FLING_DECAY_PER_FRAME=0.94 /
 *    FLING_EPS=24 为死路径镜像值，一行不碰（ARCHITECTURE §三冻结契约）——其语义
 *    出处=本件（[FLING_DECAY_PER_FRAME]/[FLING_EPS_UNITS] 同源）。
 *
 * 体感哲学（design §一五条总则）：跟手诚实（1:1 线性）/松手滑行（全手势同一惯性
 * 曲线）/判定容忍人体（比率容忍非绝对阈）/一锁定制死/每个手势有即时屏幕反馈。
 */
object GestureFeelPolicy {

    // ==================== 单指转头（环顾——单指/双指拖动共用） ====================

    /** 转头灵敏度基准：横扫 0.7×屏宽 = 转 90°（街景手感——R247 拍板值） */
    const val TURN_FULL_FRACTION = 0.7f

    /** pitch 钳位（度）：±88° 防万向锁翻转（留 2° 余量） */
    const val PITCH_LIMIT_DEG = 88f

    /** 拖拽灵敏度（度/像素）：横扫 [TURN_FULL_FRACTION]×屏宽 = 90° */
    fun turnDegPerPx(screenW: Float): Float = 90f / (TURN_FULL_FRACTION * screenW.coerceAtLeast(1f))

    // ==================== 双指捏合飞行（主飞行通道） ====================

    /**
     * 捏合飞行行程增益（世界单位/每事件倍率增量）：张开 10% ≈ 前进 72 单位。
     * R247 精调拍板：1200→720（−40%——用户真机实锤「双指缩放太过灵敏了」）。
     */
    const val PINCH_FLY_GAIN = 720f

    /** 单事件飞行限幅（世界单位）：持续张开 ≈ 2880 单位/秒巡航上限（护栏防毛刺甩飞） */
    const val FLY_EVENT_CAP = 80f

    /** 捏合事件倍率钳位（判定层护栏——异常事件不放大成飞行冲刺） */
    const val PINCH_EVENT_FACTOR_MIN = 0.25f
    const val PINCH_EVENT_FACTOR_MAX = 4f

    /** 捏合行程（世界单位）：(factor−1)×[PINCH_FLY_GAIN]，限幅 ±[FLY_EVENT_CAP] */
    fun pinchFlyUnits(zoomFactor: Float): Float =
        ((zoomFactor - 1f) * PINCH_FLY_GAIN).coerceIn(-FLY_EVENT_CAP, FLY_EVENT_CAP)

    // ==================== R303c P3：跨数量级乘法缩放（§7.1 Google Earth 式） ====================

    /** 乘法区下沿（世界单位）：低于=近地档保现行线性行程（贴脸看内容不冲过头） */
    const val PINCH_MULT_ZONE = 2000f

    /** 乘法事件位移帽（世界单位/事件——高空毛刺事件防瞬移；≈25% 银河半径） */
    const val PINCH_MULT_EVENT_CAP = 1500f

    /**
     * **乘法缩放行程**（R303c §7.1 用户批准拍板项⑥「速度∝当前高度」）：
     * dRef≥[PINCH_MULT_ZONE] 时每事件位移=(factor−1)·dRef——一满手势（factor 累计
     * ≈2）≈距离 ×2，**L4→L2 全程 5~6 次捏合**（现行线性 720u/factor 要 ~75 次——
     * 「跨数量级」被线性曲线杀死的设计原文实锤）；近地面（dRef<zone）回落现行
     * [pinchFlyUnits] 线性（细控不冲过头）。手势集合零新增（R268 归口纪律——
     * 只换曲线函数）。锚指通道零触碰（第二通道保旧手感）。
     *
     * @param dRef 相机到最近星距离（「当前高度」口径——Google Earth 海拔语义；
     *   调用方事件级 O(n) 计算，非帧级）
     */
    fun pinchFlyUnitsAlt(zoomFactor: Float, dRef: Float): Float {
        if (dRef < PINCH_MULT_ZONE) return pinchFlyUnits(zoomFactor)
        return ((zoomFactor - 1f) * dRef).coerceIn(-PINCH_MULT_EVENT_CAP, PINCH_MULT_EVENT_CAP)
    }

    // ==================== 锚指飞行（用户原创手势，第二飞行通道） ====================

    /**
     * 锚指+动指纵向手势的飞行速率（世界单位/屏高冲程）。
     * R247 精调拍板：3200→4800（+50%——用户真机实锤锚指「可以稍微灵敏一些」）。
     */
    const val ANCHOR_FLY_PER_SCREEN_H = 4800f

    /** 锚指成立比率容差：锚指位移 < 动指位移×本值（真机颤抖容差，R237 实锤修法） */
    const val ANCHOR_MOVE_RATIO = 0.3f

    /** 动指纵向显著判定：|dy| ≥ engage 且 |dy| > |dx|×本值（纵向主导才锁飞行） */
    const val ANCHOR_VERTICAL_DOMINANCE = 1.15f

    /** 锚指绝对静止子集阈（dp——适配层转 px；R231 任务书 8dp 原值） */
    const val ANCHOR_SLOP_DP = 8f

    /** 动指纵向显著判定阈（dp，≈1.75×slop——适配层转 px） */
    const val ANCHOR_ENGAGE_DP = 14f

    /** 锚指飞行速率换算（世界单位/像素）：一满屏冲程 = [ANCHOR_FLY_PER_SCREEN_H] */
    fun anchorFlyPerPx(screenH: Float): Float = ANCHOR_FLY_PER_SCREEN_H / screenH.coerceAtLeast(1f)

    // ==================== 松手惯性统一曲线（所有拖动手势共用） ====================

    /** 惯性衰减（每 [FLING_FRAME_MS]ms 一帧 ×本值——指数衰减，帧率无关按真实 dt 归一） */
    const val FLING_DECAY_PER_FRAME = 0.94f

    /** 衰减基准帧长（ms）：v(t)=v₀×[FLING_DECAY_PER_FRAME]^(dt/本值)——60/120Hz 同曲线 */
    const val FLING_FRAME_MS = 16.7f

    /** 惯性停机阈（角速度 度/秒；飞行速度 世界单位/秒——防无限爬行） */
    const val FLING_EPS_DEG = 4f
    const val FLING_EPS_UNITS = 30f

    /** 惯性接管延迟（ms）：手势结束 >本值 后帧循环才接管（手势期速度只记录不应用） */
    const val FLING_TAKEOVER_IDLE_MS = 110L

    /**
     * R310 小模型惯性初速上限（度/秒——松手测速钳位）：时间窗测速对「1ms 间隔+
     * 大跳变」的噪声事件对仍可能虚高，钳 540°/s=1.5 圈/秒（ generous 上限——
     * 人手横扫实测 <400°/s，钳的是毛刺不是手感）。主图转头惯性未钳位=同类挂账
     * （旧链实测无毛刺史，不动旧手感）。
     */
    const val MINI_MODEL_FLING_MAX_DEG_SEC = 540f

    /**
     * fling 初速测速窗（ms）：最近 ≤本值 的事件位移和÷各自事件间隔和（提案①
     * 用户终审批准——旧式「末事件×60」按 60Hz 假设，120Hz 屏低估约一半初速，
     * 松手滑行比 60Hz 设备短一截=隐性手感分裂；时间窗测速帧率无关）。
     */
    const val FLING_VELOCITY_WINDOW_MS = 100L

    /** 惯性速度衰减：dtMs 毫秒的指数衰减（[FLING_DECAY_PER_FRAME]^(dt/[FLING_FRAME_MS])） */
    fun decayVel(v: Float, dtMs: Float): Float =
        v * FLING_DECAY_PER_FRAME.pow(dtMs / FLING_FRAME_MS)

    // ==================== 点选热区 ====================

    /** 恒星命中兜底热区（dp）：小星至少本值屏半径（点得中小目标） */
    const val STAR_HIT_MIN_DP = 22f

    /** 行星/尘埃命中宽容半径（dp）：行星点小、尘埃更小——宽热区换可点性 */
    const val PLANET_HIT_DP = 14f

    /** 关联弧命中带宽（dp）：弧线视直径级——好点中即运镜 */
    const val ARC_HIT_DP = 14f

    // ==================== 运镜（双击 GOTO/探索/弧跳/feed 跳入/虫洞同一口径） ====================

    /** 巡航停船距离 = 引力井半径×本值（到达即行星层浮现「接近它自然看见旁边的小行星」） */
    const val CRUISE_STOP_WELL_FACTOR = 4.6f

    /** 巡航停船距离钳位（世界单位） */
    const val CRUISE_STOP_MIN = 80f
    const val CRUISE_STOP_MAX = 260f

    /** 巡航运镜时间常数 τ（秒）：指数趋近先快后慢=「被引力捕获的吸星感」（R231 拍板） */
    const val CRUISE_TAU_SEC = 0.9f

    /** 单击注目轻推：向星前进到剩余 本值×距离 处停（注视感不吞星，R206 原语义） */
    const val GLANCE_STOP_FACTOR = 0.45f

    /** 单击注目轻推停船下限（世界单位） */
    const val GLANCE_STOP_MIN = 120f

    /** 单击注目轻推 τ（秒——干脆，视线先到的轻推进） */
    const val GLANCE_TAU_SEC = 0.18f

    /** 巡航停船距离（世界单位）：井半径×[CRUISE_STOP_WELL_FACTOR] 钳 [[CRUISE_STOP_MIN],[CRUISE_STOP_MAX]] */
    fun cruiseStop(wellRadius: Float): Float =
        (wellRadius * CRUISE_STOP_WELL_FACTOR).coerceIn(CRUISE_STOP_MIN, CRUISE_STOP_MAX)

    /** 单击注目轻推停船（世界单位）：距离×[GLANCE_STOP_FACTOR] 下限 [GLANCE_STOP_MIN] */
    fun glanceStop(dist: Float): Float =
        (dist * GLANCE_STOP_FACTOR).coerceAtLeast(GLANCE_STOP_MIN)

    // ==================== R342 航行节奏（距离正比时长+下限——用户 20:2x 拍板） ====================

    /**
     * 航行时长下限（秒）：近星一眨眼到但有加速感——「贴脸邻星 1.2s」档。
     * 用户拍板口径「近星 1.2s / 远星 3~4s 的跨光年感」的下端锚。
     */
    const val VOYAGE_MIN_SEC = 1.2f

    /** 航行时长上限（秒）：跨银河星程也不拖戏——3~4s 档取中 */
    const val VOYAGE_MAX_SEC = 3.6f

    /** 近星标定距（世界单位）：≈680 星×R5000 球的典型邻星间距量级（≈(V/N)^⅓） */
    const val VOYAGE_NEAR_UNITS = 900f

    /** 远星标定距（世界单位）：跨银河星程口径（银心位 7000 同量级） */
    const val VOYAGE_FAR_UNITS = 8000f

    /**
     * **航行时长公式**（R342 D3——线性正比+双端钳位）：
     * T(d)=1.2+2.4·(d−[VOYAGE_NEAR_UNITS])/([VOYAGE_FAR_UNITS]−900) 钳
     * [[VOYAGE_MIN_SEC],[VOYAGE_MAX_SEC]]——「距离正比时长+下限」的字面兑现：
     * 旧机制 τ 恒 0.9s 与距离无关（指数趋近初速=D0/τ 随距离线性暴涨=「嗖一下
     * 穿越很多光年」）；本公式让远星飞行真的更久（近 900u→1.2s / 远 8000u→
     * 3.6s / 中 4450u→2.4s），配合 focusFlight voyage 模式（easePose
     * smoothstep=加速-巡航-缓出）给「真的在航行」的距离感。距离口径=触发时
     * 相机到目标 N 体当帧位的直线距（点=所见）。
     */
    fun voyageSec(dist: Float): Float =
        (VOYAGE_MIN_SEC + (VOYAGE_MAX_SEC - VOYAGE_MIN_SEC) *
            (dist - VOYAGE_NEAR_UNITS) / (VOYAGE_FAR_UNITS - VOYAGE_NEAR_UNITS))
            .coerceIn(VOYAGE_MIN_SEC, VOYAGE_MAX_SEC)

    // ==================== 望远镜 ± 按钮（焦距倍率——与飞行正交只改放大） ====================

    /** 倍率步进（×步进/÷步退——「换目镜」） */
    const val LENS_STEP = 1.5f

    /** 倍率量程钳位 */
    const val LENS_MIN = 1f
    const val LENS_MAX = 3.2f

    /** 倍率钳位到量程 [[LENS_MIN],[LENS_MAX]] */
    fun lensClamp(v: Float): Float = v.coerceIn(LENS_MIN, LENS_MAX)

    // ==================== 飞进行星内容门 / 信息卡手势 ====================

    /** 飞进行星门宽（世界单位，加在行星世界半径上）：相机距行星小于此值=到达「降落」 */
    const val FLY_INTO_GATE_UNITS = 22f

    /** 信息卡 drag-to-dismiss 触发距离（dp）：起手时滚动在顶才累计；净下滑≥本值且纵向主导收卡 */
    const val CARD_DISMISS_DRAG_DP = 64f

    // ==================== R302 曲速巡航（星辉 Lv6 解锁——长按星空白处持续冲刺） ====================

    /** 曲速巡航速度（世界单位/秒——≈0.41×SPEED_CAP 的冲刺档：明显快于惯性滑行，
     *  又不至于贴脸吞星；让位=abortGalaxyTourR271 单口同停） */
    const val WARP_CRUISE_UNITS_PER_SEC = 900f

    /** 曲速巡航加速时间常数（秒——启动不踹脸，指数趋近满速） */
    const val WARP_CRUISE_TAU_SEC = 0.7f

    // ==================== 锚指环即时反馈族（候选暗环→锁定亮环+方向箭头） ====================

    /** 锁定亮环半径（dp） */
    const val ANCHOR_RING_RADIUS_DP = 26f

    /** 锁定亮环描边宽（dp——方向箭头描边同族同值） */
    const val ANCHOR_RING_STROKE_DP = 2.5f

    /** 候选锚指暗环半径（dp） */
    const val ANCHOR_CANDIDATE_RING_RADIUS_DP = 22f

    /** 候选锚指暗环描边宽（dp） */
    const val ANCHOR_CANDIDATE_RING_STROKE_DP = 1.5f

    /** 方向箭头臂长（dp——chevron 上=前进/下=后退） */
    const val ANCHOR_ARROW_ARM_DP = 7f

    /** 方向箭头与环的间距（dp） */
    const val ANCHOR_ARROW_GAP_DP = 12f

    /** 脉冲基线/振幅/角速度：alpha 脉冲 = 基线+振幅×sin(t×角速度) */
    const val ANCHOR_PULSE_BASE = 0.72f
    const val ANCHOR_PULSE_AMP = 0.28f
    const val ANCHOR_PULSE_SPEED = 5.2f

    /** 锚环脉冲系数（0.44..1.0——呼吸感「环是活的」） */
    fun anchorPulse(tSec: Float): Float =
        ANCHOR_PULSE_BASE + ANCHOR_PULSE_AMP * sin(tSec * ANCHOR_PULSE_SPEED)

    // ==================== 提案①：fling 初速时间窗测速（R268b 用户终审批准） ====================

    /**
     * 时间窗测速器（**纯 Kotlin 零分配环形缓冲**——手势热路径可用）：
     * 旧式「末事件位移×60」按 60Hz 假设，120Hz 屏（用户小米）末事件只有 8.3ms
     * 位移 → 初速低估约一半 → 松手滑行短一截（隐性手感分裂）。本件改测
     * **最近 ≤[FLING_VELOCITY_WINDOW_MS]ms 的事件位移和 ÷ 各自事件间隔和**——
     * 帧率无关（60/120Hz 同手速同初速，单测锚定）。
     *
     * 口径：
     *  - [push] 每事件批一推（tMs=事件时间戳毫秒，d=该批位移，单位=调用方口径——
     *    度/世界单位皆可）；[velocity] 出 单位/秒；
     *  - 样本的事件间隔=与前一推的时间差（**含窗外紧邻一推作锚**——「位移和÷时间
     *    和」的字面口径）；手势首推无前序间隔不计（d 无从归属——防除零/防虚高）；
     *  - 两推间隔 > 窗宽 = 手势间休止（抬手重落/拖动中途停顿）→ **自动清窗**：
     *    旧手势残速不污染新一轮初速；
     *  - 窗内样本不足 2 或间隔和 ≤0 → 0（无速可测=不 fling，宁可不飞不可乱飞）。
     */
    class FlingVelocityWindow(capacity: Int = 32) {
        private val cap = capacity.coerceAtLeast(4)
        private val tMs = LongArray(cap)
        private val dlt = FloatArray(cap)
        private var head = 0 // 下一写位（环形）
        private var size = 0

        fun reset() {
            size = 0
            head = 0
        }

        /** 推入一次位移样本（tMs 须单调非降——事件时间戳天然满足） */
        fun push(tMs: Long, d: Float) {
            if (size > 0) {
                val lastT = this.tMs[(head + cap - 1) % cap]
                if (tMs - lastT > FLING_VELOCITY_WINDOW_MS) reset() // 休止=新一轮手势
            }
            this.tMs[head] = tMs
            dlt[head] = d
            head = (head + 1) % cap
            if (size < cap) size++
        }

        /** 当前速度（单位/秒）：窗内样本位移和÷各自事件间隔和 */
        fun velocity(nowMs: Long): Float {
            if (size < 2) return 0f
            val startMs = nowMs - FLING_VELOCITY_WINDOW_MS
            var sumD = 0f
            var sumDtMs = 0L
            val newest = (head + cap - 1) % cap
            var k = 0
            while (k < size) {
                val idx = (newest - k + cap) % cap // 最新→最老
                val t = tMs[idx]
                if (t <= startMs) break // 出窗（更老的更出窗）
                if (k + 1 >= size) break // 窗内最老样本无前序间隔——不计（手势首推）
                val prevT = tMs[(idx - 1 + cap) % cap]
                sumD += dlt[idx]
                sumDtMs += (t - prevT).coerceAtLeast(0L)
                k++
            }
            if (sumDtMs <= 0L) return 0f
            return sumD * 1000f / sumDtMs
        }
    }
}
