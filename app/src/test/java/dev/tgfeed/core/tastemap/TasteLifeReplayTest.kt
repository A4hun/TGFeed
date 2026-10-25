package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R328 延时摄影生灭回放数据轨单测（[TasteLifeReplay]——r325 C⑤ 单测锚点名：
 * diedVisible 门「光标跨界正反例/无 lastActivity 证据=恒生不冒充死亡」+
 * 批D「回放统计行升级」的数据面锚）：
 *  - **死亡时刻代数**：有证据=sec×1000+死亡延迟；无证据（0/负）=MAX_VALUE
 *    恒生（诚实红线——无证据不冒充死亡）；
 *  - **死亡延迟口径**：7 天=判据族 ALIVE 同界（断更<7 天=活着——不另立第二套
 *    死亡观）；
 *  - **光标跨界**：严格大于（光标==熄灭时刻不算熄——r325 C①-4 原式）；
 *  - **组合门**：aliveVisible=bornVisible∧¬diedVisible（先死后生数据异常以
 *    未诞生优先=看不见；星熄则行星隐的 tag 生死门同口）；
 *  - **回放端点诚实**：lastActivity 在量程外（仍活跃到今天）=回放窗内恒生；
 *  - **确定性**：纯函数同输入同输出（零 Random 零系统钟——R 系铁律）。
 */
class TasteLifeReplayTest {

    private val dayMs = 86_400_000L

    @Test
    fun `死亡时刻-代数与无证据恒生`() {
        // 有证据：sec→ms+死亡延迟（epoch 毫秒口径）
        assertEquals(
            "有证据=sec×1000+死亡延迟",
            100_000L + TasteLifeReplay.DEATH_DELAY_MS,
            TasteLifeReplay.deathAtMs(100L),
        )
        assertEquals(
            "大秒值不溢出截断（Long 域）",
            1_790_000_000L * 1000L + TasteLifeReplay.DEATH_DELAY_MS,
            TasteLifeReplay.deathAtMs(1_790_000_000L),
        )
        // 无证据（0/负）=MAX_VALUE 恒生不冒充死亡（r325 C⑤ 点名红线）
        assertEquals(Long.MAX_VALUE, TasteLifeReplay.deathAtMs(0L))
        assertEquals(Long.MAX_VALUE, TasteLifeReplay.deathAtMs(-1L))
        assertEquals(Long.MAX_VALUE, TasteLifeReplay.deathAtMs(Long.MIN_VALUE))
    }

    @Test
    fun `死亡延迟-判据族ALIVE同界七天`() {
        // 7 天=ALIVE 口径同界（断更<7 天=本周仍有新料=活着——TasteAstroPhysics
        // publishHistogramFeatures 同界；回放门对齐不另立第二套死亡观）
        assertEquals(7L * dayMs, TasteLifeReplay.DEATH_DELAY_MS)
        // 帽=总闸等效位：调大即回放永不熄灭（r325 待过目项⑥ 回退位——常量在单源处）
        assertTrue("死亡延迟应为正（回退位=调大非删码）", TasteLifeReplay.DEATH_DELAY_MS > 0L)
    }

    @Test
    fun `已熄门-光标跨界严格大于`() {
        // 量程 [0, maxMs]；lastActivity=64s → 熄灭时刻=64_000+7 天=604_864_000
        // （64 对齐=Float 可精确表示——cursorMs 走 Long×Float，边界例须避开舍入）
        val lastAct = 64L
        val deathAt = TasteLifeReplay.deathAtMs(lastAct)
        assertEquals(64_000L + TasteLifeReplay.DEATH_DELAY_MS, deathAt)
        // 量程端点==熄灭时刻：光标==熄灭时刻不算熄（严格大于——r325 C①-4 原式
        // 「光标>lastActivitySec+死亡延迟」）
        val spanEq = TasteFlight.GenesisSpan(0L, deathAt)
        assertFalse("光标==熄灭时刻不熄（严格大于）", TasteLifeReplay.diedVisible(lastAct, spanEq, 1f))
        // 量程端点比熄灭时刻多 64ms：光标>熄灭时刻=已熄
        val spanGt = TasteFlight.GenesisSpan(0L, deathAt + 64L)
        assertTrue("光标越界 64ms 即熄", TasteLifeReplay.diedVisible(lastAct, spanGt, 1f))
        // 回放中段（量程=熄灭时刻×2——0.25/0.75 浮点精确）：熄灭时刻前恒生/后已熄
        val spanMid = TasteFlight.GenesisSpan(0L, deathAt * 2L)
        assertFalse("熄灭前恒生", TasteLifeReplay.diedVisible(lastAct, spanMid, 0.25f))
        assertTrue("熄灭后已熄", TasteLifeReplay.diedVisible(lastAct, spanMid, 0.75f))
    }

    @Test
    fun `已熄门-无证据光标到量程末端仍恒生`() {
        val span = TasteFlight.GenesisSpan(0L, 10L * dayMs)
        for (t01 in floatArrayOf(0f, 0.3f, 0.7f, 1f)) {
            assertFalse(
                "无 lastActivity 证据=恒生不冒充死亡（t01=$t01）",
                TasteLifeReplay.diedVisible(0L, span, t01),
            )
        }
        // 确定性：同输入同输出
        assertEquals(
            TasteLifeReplay.diedVisible(0L, span, 0.7f),
            TasteLifeReplay.diedVisible(0L, span, 0.7f),
        )
    }

    @Test
    fun `组合门-生死门与先死后生异常优先未诞生`() {
        // 正常星：早诞生/近期活动——量程 [0, 100 天]，firstSeen=10 天，
        // lastActivity=50 天 → 熄灭时刻 50 天+7 天=57 天
        val span = TasteFlight.GenesisSpan(0L, 100L * dayMs)
        val born10d = 10L * dayMs
        val lastAct50d = (50L * dayMs) / 1000L
        // t01=0.3（光标 30 天）：已诞生未熄→活着
        assertTrue(TasteLifeReplay.aliveVisible(born10d, lastAct50d, span, 0.3f))
        // t01=0.6（光标 60 天）：已诞生已熄→不活着（残骸态——渲染端按 kind 出终态
        // 视觉，tag 行星随熄隐）
        assertFalse(TasteLifeReplay.aliveVisible(born10d, lastAct50d, span, 0.6f))
        assertTrue(TasteLifeReplay.diedVisible(lastAct50d, span, 0.6f))
        // t01=0.05（光标 5 天）：未诞生→看不见（组合门 born 在前）
        assertFalse(TasteLifeReplay.aliveVisible(born10d, lastAct50d, span, 0.05f))
        // 先死后生数据异常（lastActivity 早于 firstSeen——金矿/索引口径不一致）：
        // 熄灭窗内未诞生=以未诞生优先（看不见），不冒充「已熄的活星」
        val lateBorn = 80L * dayMs
        val earlyAct = (20L * dayMs) / 1000L // 熄灭时刻 27 天 < 诞生 80 天
        assertFalse(TasteLifeReplay.aliveVisible(lateBorn, earlyAct, span, 0.35f)) // 27<光标35<80：未诞生
        assertFalse(TasteLifeReplay.aliveVisible(lateBorn, earlyAct, span, 0.9f)) // 光标 90：已诞生但早已熄
    }

    @Test
    fun `回放端点-量程外仍活跃星恒生`() {
        // lastActivity 在量程末端之后（频道活跃到今天——量程=首见 min/max）：
        // 回放窗内恒生（「还活着」的诚实读法——r325 C①-4）
        val span = TasteFlight.GenesisSpan(0L, 100L * dayMs)
        val activeToday = (120L * dayMs) / 1000L // 活动时刻 120 天 > 量程末端 100 天
        for (t01 in floatArrayOf(0.5f, 0.9f, 1f)) {
            assertFalse(
                "量程外仍活跃=回放窗内恒生（t01=$t01）",
                TasteLifeReplay.diedVisible(activeToday, span, t01),
            )
            assertTrue(
                "对应组合门恒真（已诞生未熄）",
                TasteLifeReplay.aliveVisible(10L * dayMs, activeToday, span, t01),
            )
        }
    }

    @Test
    fun `组合门-无证据恒生且与born门正交`() {
        val span = TasteFlight.GenesisSpan(0L, 100L * dayMs)
        // 无 lastActivity 证据：aliveVisible 退化为 bornVisible（恒生门零干扰）
        for (t01 in floatArrayOf(0.05f, 0.5f, 1f)) {
            assertEquals(
                "无证据=aliveVisible 与 bornVisible 等值（t01=$t01）",
                TasteFlight.bornVisible(10L * dayMs, span, t01),
                TasteLifeReplay.aliveVisible(10L * dayMs, 0L, span, t01),
            )
        }
        // 首见无证据（0=量程末端才诞生——TasteFlight 口径）+活动无证据：
        // 回放窗内除末端外不可见
        assertFalse(TasteLifeReplay.aliveVisible(0L, 0L, span, 0.5f))
        assertTrue(TasteLifeReplay.aliveVisible(0L, 0L, span, 1f))
    }

    @Test
    fun `死闪包络-窗口线性衰减与窗外零`() {
        // 量程 [0, 100 天]；lastActivity=64s → 熄灭时刻=64_000+7 天=604_864_000
        // （64 对齐=Float 可精确表示——cursorMs 走 Long×Float 的口径纪律）
        val span = TasteFlight.GenesisSpan(0L, 100L * dayMs)
        val lastAct = 64L
        val deathAt = TasteLifeReplay.deathAtMs(lastAct)
        // 未熄（光标在熄灭时刻前）恒 0；无证据恒 0
        assertEquals(0f, TasteLifeReplay.deathFlash01(lastAct, span, 0.02f), 0f)
        assertEquals(0f, TasteLifeReplay.deathFlash01(0L, span, 1f), 0f)
        // 死闪窗=量程×DEATH_FLASH_SPAN_F：窗内线性 1→0（衰减单调不减为减）+域 0..1
        var prev = 1.01f
        for (t01 in floatArrayOf(0.08f, 0.12f, 0.16f, 0.2f, 0.3f)) {
            val f = TasteLifeReplay.deathFlash01(lastAct, span, t01)
            assertTrue("死闪域 0..1：t01=$t01 f=$f", f in 0f..1f)
            assertTrue("死闪线性衰减（随光标推进非增）：prev=$prev now=$f", f <= prev)
            prev = f
        }
        // 窗起点=峰值邻域（刚越过熄灭时刻≈1——0.08 光标=熄灭后不久）
        assertTrue(
            "死闪峰值邻域（刚越熄灭时刻≈1）",
            TasteLifeReplay.deathFlash01(lastAct, span, 0.08f) > 0.8f,
        )
        // 窗外=已熄恒 0（光标 0.9 → 远超熄灭时刻+窗=渲染端 continue 不可见）
        assertEquals(0f, TasteLifeReplay.deathFlash01(lastAct, span, 0.9f), 0f)
        // 确定性
        assertEquals(
            TasteLifeReplay.deathFlash01(lastAct, span, 0.12f),
            TasteLifeReplay.deathFlash01(lastAct, span, 0.12f),
            0f,
        )
    }

    @Test
    fun `死星视觉档-参数域与回退位`() {
        // 暗核系数域（0,1)：熄=暗但可见（暗核化非消失——残骸读法）；归一=现状亮度
        assertTrue(
            "暗核系数应在 (0,1)（暗而可见）",
            TasteLifeReplay.REMNANT_CORE_K > 0f && TasteLifeReplay.REMNANT_CORE_K < 1f,
        )
        // 死闪窗占比域：小分数（爆发一拍非半程）且正
        assertTrue(
            "死闪窗占比应为正小分数",
            TasteLifeReplay.DEATH_FLASH_SPAN_F > 0f && TasteLifeReplay.DEATH_FLASH_SPAN_F < 0.2f,
        )
    }
}
