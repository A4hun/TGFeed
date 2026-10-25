package dev.tgfeed.core.tastemap

/**
 * R328 延时摄影**生灭回放数据轨**单源（core 纯件，零 Android 依赖，JVM 单测可跑
 * ——r325 C①-4 第一档「延时摄影从回放诞生升级为回放生灭全程」+r325 批D 回放
 * 收口并入本批的数据面）：
 *
 *  - **为何独立新件**：[TasteFlight.bornVisible]（诞生门）的自然对位家=TasteFlight
 *    创世纪节，但该文件在 R320 渲染器冻结期（第一阶段禁碰清单）——死亡侧对位
 *    立独立单源，**套回后也不挪**（零合并面，避免第二阶段二次搬家）；渲染端
 *    视觉消费（白矮星暗核化/超新星爆发时刻闪一下再熄）与命中域联动归第二阶段。
 *  - **原料全本地零新数据**（r325 诚实红线）：StarNode.lastActivitySec（金矿档案
 *    epoch 秒——0/负=无证据）+既有 GenesisSpan 光标——零新 IO 零新查询零新缓存。
 *  - **语义**：光标扫过=看见整条链（星逐颗点亮→老星熄灭成致密残骸——r325 C①-4
 *    「生灭编年史」读法）；「星转入终态视觉」的终态口径=当帧 kind（现行宣判——
 *    无历史账本的诚实妥协：tier 快照账本归第二档挂账 R33x+ 不做）。
 *
 * **回退姿势**（r325 待过目项⑥「diedVisible 门参数化默认开」——常量帽=总闸
 * 等效位）：[DEATH_DELAY_MS] 调大（如 Long.MAX_VALUE/2）=回放窗内恒生=现状
 * 「只回放诞生」行为；数据位零删零改。
 *
 * 确定性：零 Random 零系统钟（光标 t01/span 调用方传——R 系铁律）。
 */
object TasteLifeReplay {

    /**
     * 死亡延迟（毫秒）：光标越过「最近活动+本延迟」=星转入终态视觉。**判据族
     * ALIVE 口径同源**（断更<7 天=本周仍有新料=活着——TasteAstroPhysics
     * publishHistogramFeatures 同界）：<7 天不算死是判据族既有语义，回放门对齐
     * 同界不另立第二套死亡观（纪律§一）。调大=回放永不熄灭=回退位。
     */
    const val DEATH_DELAY_MS = 7L * 86_400_000L

    /**
     * 一颗星的熄灭时刻（毫秒）：lastActivity 有证据=其 epoch 毫秒+死亡延迟；
     **无证据（0/负）=[Long.MAX_VALUE]=恒生不冒充死亡**（诚实红线——r325 C⑤
     * 单测锚点名「无 lastActivity 证据=恒生」）。量程无关（不 clamp：量程外的
     * 近期活动=回放窗内恒生=「还活着」的诚实读法）。
     */
    fun deathAtMs(lastActivitySec: Long): Long =
        if (lastActivitySec <= 0L) Long.MAX_VALUE
        else lastActivitySec * 1000L + DEATH_DELAY_MS

    /**
     * 光标处该星是否已熄灭（diedVisible——[TasteFlight.bornVisible] 的死亡侧
     * 对位）：光标（毫秒）**严格大于**熄灭时刻=已转入终态（r325 C①-4 原式
     * 「光标>lastActivitySec+死亡延迟」）。先死后生数据异常（lastActivity 早于
     * firstSeen）时本门先真——组合口径走 [aliveVisible]（以未诞生优先=看不见）。
     */
    fun diedVisible(lastActivitySec: Long, span: TasteFlight.GenesisSpan, t01: Float): Boolean =
        TasteFlight.cursorMs(span, t01) > deathAtMs(lastActivitySec)

    /**
     * 光标处该星**活着且可见**（组合门——数据面单口）：bornVisible ∧ ¬diedVisible。
     * 消费面：tag 行星生死门（r325 C①-4「星熄则行星隐」——tagSet 只数活星的 tag）
     * +回放统计行（此刻 N 频道·已熄 M 颗）+第二阶段渲染视觉（星熄灭成终态残骸
     * ≠消失——残骸可见性与命中域归渲染端按 kind 分档）。先死后生异常以未诞生
     * 优先（birthVisible 门在前）。
     */
    fun aliveVisible(firstSeenMs: Long, lastActivitySec: Long, span: TasteFlight.GenesisSpan, t01: Float): Boolean =
        TasteFlight.bornVisible(firstSeenMs, span, t01) && !diedVisible(lastActivitySec, span, t01)

    // ==================== 死星终态视觉档（r325 C①-4——渲染端 Canvas/GL 双端消费） ====================

    /**
     * 死星残骸暗核系数（×alpha——光标越过熄灭时刻后的致密残骸亮度：白矮星暗核
     * 化/其余 kind 转残骸暗核；白矮星正常态的「死而不灭」0.92 亮地板在回放已熄
     * 态下让位本档——「暗核化」=熄的读法）。归一=现状亮度（回退位）。
     */
    const val REMNANT_CORE_K = 0.45f

    /**
     * 死闪窗占比（×量程——超新星「爆发时刻闪一下再熄」的窗宽：**量程分数制**=
     * 任意倍速下窗占比恒定（×1 全程 12s 档 ≈0.7s 墙钟可见——倍速同比例缩放，
     * 墙钟域窗在 16 倍速下会不可见，分数制是唯一全速域诚实口径）。
     */
    const val DEATH_FLASH_SPAN_F = 0.06f

    /**
     * 超新星死闪包络（0..1——光标越过熄灭时刻起在窗内线性衰减=「闪一下再熄」；
     * 窗外/未熄/无证据恒 0=渲染端已熄不可见或正常态）。确定性纯函数。
     */
    fun deathFlash01(lastActivitySec: Long, span: TasteFlight.GenesisSpan, t01: Float): Float {
        val deathAt = deathAtMs(lastActivitySec)
        if (deathAt == Long.MAX_VALUE) return 0f
        val age = TasteFlight.cursorMs(span, t01) - deathAt
        if (age <= 0L) return 0f
        val wMs = (span.rangeMs * DEATH_FLASH_SPAN_F).toLong()
        if (wMs <= 0L || age >= wMs) return 0f
        return 1f - age.toFloat() / wMs.toFloat()
    }
}
