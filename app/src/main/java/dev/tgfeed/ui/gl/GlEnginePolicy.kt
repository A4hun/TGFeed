package dev.tgfeed.ui.gl

/**
 * R277 渲染引擎政策件（**纯 Kotlin 零 android 依赖**——JVM 单测全矩阵锚定）：
 * 「渲染引擎」的解析与诊断决策的唯一出处（AGENT-DISCIPLINE §一：同样的判定
 * 不许在 AppPrefs/设置页/星图装配各写一份）。
 *
 * **R345 GL-only 收口（用户 21:0x 裁决「新引擎已经好了，把老的干掉」——Canvas
 * 老渲染引擎退役）**：
 *  - 本政策件不再产出 Canvas 档（旧回退语义整体退役）；
 *  - 探针闸：ES3 离屏上下文探测失败=设备不可用 → [Engine.DIAGNOSTIC]（**诚实
 *    降级**：星图黑屏+「渲染引擎诊断」行+R277 探针日志——失败可见可修，不静默
 *    换渲染器；重试路径由调用方显式重建 GL 场景）；
 *  - 崩溃计数（帧内异常+会话旗标 native 崩覆盖）保留为**诊断账本**：
 *    [CRASH_FALLBACK_THRESHOLD] 起诊断行升级「反复崩溃」提示；引擎恒 GL——
 *    账本不再参与引擎选择（旧「自动回退 Canvas」闸随档位退役）；
 *  - 旧偏好键语义收口：render_engine=1（Canvas 档）已退役——读侧
 *    [legacyCanvasPref] 标记旧值（调用侧日志一行「映射 GL」后照常 GL）。
 */
internal object GlEnginePolicy {

    /** AppPrefs render_engine 键值域：自动（默认——R345 后与 OpenGL 档同效，GL 唯一引擎） */
    const val PREF_AUTO = 0

    /**
     * AppPrefs render_engine 键值域：**R345 退役值**——Canvas 档位删除后读到旧值 1
     * 一律映射 GL（[legacyCanvasPref] 标记→调用侧日志一行）。常数保留（防御读域）。
     */
    const val PREF_CANVAS = 1

    /** AppPrefs render_engine 键值域：强制 OpenGL（R345 后与自动档同效） */
    const val PREF_OPENGL = 2

    /**
     * 崩溃计数提示线：GL 会话崩溃累计 ≥ 此值，诊断行升级「反复崩溃」提示
     * （R345 前语义=自动档永久回退 Canvas 闸——无 Canvas 可回后收口为纯诊断口径：
     * 提示可见+自愈清零，引擎选择不再受账本影响）。
     */
    const val CRASH_FALLBACK_THRESHOLD = 2

    /**
     * 健康会话自愈门（ms）：GL 连续健康渲染 ≥ 30s 且零帧内异常 → 崩溃计数清零。
     * 立规由（16384 实锤）：会话旗标闸无法区分「native 崩溃」与「am force-stop」
     * （强停不交生命周期回调=旗标必然残留）——无自愈时共享测试机两轮排窗强停即把
     * 账本抬高（假阳性）；真实崩溃（会话活不过 30s 反复死）照样越线。
     */
    const val HEALTHY_RESET_MS = 30_000L

    /** 自愈判定（纯函数）：计数非零 ∧ 健康时长过门 → 清零（GL 渲染器里程回调消费） */
    fun shouldResetCrashCount(healthySessionMs: Long, crashCount: Int): Boolean =
        crashCount > 0 && healthySessionMs >= HEALTHY_RESET_MS

    /**
     * 旧 Canvas 档偏好判定（R345 读侧收口）：true=读到退役值 1——调用侧日志一行
     * 「Canvas 档已退役——映射 GL」后照常 GL（无行为分叉，纯诚实观测）。
     */
    fun legacyCanvasPref(pref: Int): Boolean = pref == PREF_CANVAS

    /**
     * 解析生效引擎（单一判定口，GL-only）：
     *  - es3 可用 → [Engine.OPENGL]（reason=gl_only——恒 GL，无 Canvas 可回）；
     *  - es3 缺失 → [Engine.DIAGNOSTIC]（reason=es3_missing——诚实降级：黑屏+
     *    「渲染引擎诊断」行+R277 探针日志，重试路径显式可见）。
     * 旧 Choice 入参与崩溃计数入参随 Canvas 档退役（引擎选择与账本解耦——
     * 账本归诊断行/自愈口径）。
     */
    fun resolve(es3Available: Boolean): Resolved =
        if (es3Available) Resolved(Engine.OPENGL, "gl_only")
        else Resolved(Engine.DIAGNOSTIC, "es3_missing")

    /** 解析结果（[engine]=实际生效引擎；[reason]=诊断串——logcat 探针/诊断行共用） */
    enum class Engine { OPENGL, DIAGNOSTIC }

    class Resolved(
        val engine: Engine,
        val reason: String,
    )

    /**
     * 会话旗标冷启动决算（native 崩溃覆盖闸的判读件——纯函数）：
     * 上次会话 GL 旗标仍立（=进程死在 GL 渲染中，干净退出/降级都会先清旗）→
     * 计数 +1；返回新计数。旗标未立=上次干净，计数原样保留（不随时间清零——
     * 保守口径：同机连续崩才越线）。已知假阳性=am force-stop（不交生命周期回调
     * =旗标残留）——由 [shouldResetCrashCount] 健康自愈兜底（真实崩溃的会话
     * 活不过自愈门，强停的会话能活到清零）。
     */
    fun crashCountOnColdStart(sessionActiveWas: Boolean, crashCount: Int): Int =
        if (sessionActiveWas) crashCount + 1 else crashCount
}
