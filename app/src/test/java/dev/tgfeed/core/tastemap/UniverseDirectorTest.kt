package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R282 宇宙导演器单测：节奏闸五门（最小间隔/分钟全局/分钟分类/并发/同星冷却）/
 * 死线弃演/确定性对拍/场景候选分类门/排程散布确定性/星云漂移有界+确定性/
 * 流星节奏档/账本 round-trip+环帽/回放确定性/热身跨重启抑复燃。
 */
class UniverseDirectorTest {

    private val cfg = UniverseDirector.Config()
    private val t0 = 1_700_000_000_000L

    private fun tickOne(s: UniverseDirector.State, nowMs: Long, c: UniverseDirector.Config = cfg) =
        mutableListOf<UniverseDirector.Directive>().also { UniverseDirector.tick(s, nowMs, c, it) }

    // ---------------- 节奏闸 ----------------

    @Test
    fun `节奏闸-全局最小间隔`() {
        val s = UniverseDirector.State()
        UniverseDirector.enqueueCrawl(s, t0, cfg, 11, LongArray(0), 0)
        UniverseDirector.enqueueCrawl(s, t0 + 1_000, cfg, 12, LongArray(0), 0)
        val d1 = tickOne(s, t0)
        assertEquals(1, d1.size) // 第一颗彗星放行
        val d2 = tickOne(s, t0 + 1_000)
        assertEquals(0, d2.size) // 1s 内第二颗被最小间隔闸挂起
        assertTrue(s.rejected[UniverseDirector.Reject.MIN_INTERVAL.ordinal] > 0)
        val d3 = tickOne(s, t0 + 5_100)
        assertEquals(1, d3.size) // 间隔够后补发
        assertEquals(UniverseDirector.EventKind.COMET, d3[0].kind)
    }

    @Test
    fun `节奏闸-每分钟分类上限`() {
        val s = UniverseDirector.State()
        // 10 次爬虫脉冲、间隔 6s（过最小间隔）——彗星分钟帽=2
        for (i in 0 until 10) {
            UniverseDirector.enqueueCrawl(s, t0 + i * 6_000L, cfg, 100 + i, LongArray(0), 0)
        }
        var comets = 0
        for (i in 0 until 10) {
            comets += tickOne(s, t0 + i * 6_000L).count { it.kind == UniverseDirector.EventKind.COMET }
        }
        assertEquals(2, comets) // 分钟帽 2——第三颗起挂起
        // 窗滑动后（61s 外）挂起的彗星到期补发
        val later = tickOne(s, t0 + 61_500L)
        assertEquals(1, later.count { it.kind == UniverseDirector.EventKind.COMET })
    }

    @Test
    fun `节奏闸-每分钟全局上限`() {
        // 全局帽 5/分：彗星帽拉高、间隔 5.2s 连发——第 6 个事件进不了窗
        val wide = UniverseDirector.Config(cometPerMinute = 20, novaPerMinute = 20, minIntervalMs = 1_000L)
        val s = UniverseDirector.State()
        var fired = 0
        for (i in 0 until 8) {
            UniverseDirector.enqueueCrawl(s, t0 + i * 6_000L, wide, 200 + i, LongArray(0), 0)
            fired += tickOne(s, t0 + i * 6_000L, wide).size
        }
        assertEquals(5, fired) // 全局帽 5——第 6~8 挂起（窗内）
        assertTrue(s.rejected[UniverseDirector.Reject.GLOBAL_MINUTE_CAP.ordinal] > 0)
    }

    @Test
    fun `节奏闸-同屏并发上限`() {
        // 并发帽 3：间隔 0.1s 连排 6 新星（活跃窗 4.5s）——同屏 ≤3
        val tightWin = UniverseDirector.Config(
            minIntervalMs = 100L, novaPerMinute = 60, globalPerMinute = 60,
            startDelayMs = 0L, spreadWindowMs = 300L,
        )
        val stars = (1L..6L).map { id ->
            UniverseDirector.StarSig(id, CelestialKind.STAR, 3, 0)
        }
        val cands = UniverseDirector.sceneCandidates(stars)
        assertEquals(6, cands.size)
        val s2 = UniverseDirector.State()
        UniverseDirector.scheduleSceneEvents(s2, t0, tightWin, cands)
        var maxConc = 0
        var fired = 0
        for (k in 0..300) {
            val now = t0 + k * 100L
            fired += tickOne(s2, now, tightWin).size
            maxConc = maxOf(maxConc, UniverseDirector.concurrentCount(s2, now))
        }
        assertEquals(6, fired) // 全部最终出演（挂起等并发窗）
        assertTrue("并发 $maxConc 不得超过 3", maxConc <= 3)
        assertTrue(s2.rejected[UniverseDirector.Reject.CONCURRENT_CAP.ordinal] > 0)
    }

    @Test
    fun `节奏闸-同星冷却`() {
        val s = UniverseDirector.State()
        UniverseDirector.enqueueCrawl(s, t0, cfg, 5, longArrayOf(777001L), 1)
        tickOne(s, t0) // 彗星先出（最小间隔占位）
        val dNova = tickOne(s, t0 + 700L + 5_100L)
        assertEquals(1, dNova.count { it.kind == UniverseDirector.EventKind.NOVA && it.chatId == 777001L })
        // 冷却期内同星再来一颗 → 挂起记 STAR_COOLDOWN
        UniverseDirector.enqueueCrawl(s, t0 + 30_000L, cfg, 6, longArrayOf(777001L), 1)
        tickOne(s, t0 + 30_000L) // 彗星
        val held = tickOne(s, t0 + 36_000L)
        assertEquals(0, held.count { it.chatId == 777001L })
        assertTrue(s.rejected[UniverseDirector.Reject.STAR_COOLDOWN.ordinal] > 0)
    }

    @Test
    fun `节奏闸-死线弃演`() {
        // 星 X 跨重启账本 30s 前演过新星（冷却 120s）——新候选死线 +60s
        // 等不到冷却结束=弃演（DEADLINE_DROP 落账）
        val s = UniverseDirector.State()
        UniverseDirector.warmUp(
            s,
            listOf(UniverseDirector.Entry(t0 - 30_000L, UniverseDirector.EventKind.NOVA, 888001L, 44, 600)),
            t0, cfg,
        )
        UniverseDirector.enqueueCrawl(s, t0, cfg, 8, longArrayOf(888001L), 1)
        tickOne(s, t0) // 彗星放行（chatId=0 不吃星冷却）
        val held = tickOne(s, t0 + 5_200L)
        assertEquals(0, held.count { it.chatId == 888001L }) // 冷却挂起
        val late = tickOne(s, t0 + 61_000L) // 超死线
        assertEquals(0, late.count { it.chatId == 888001L })
        assertTrue(s.rejected[UniverseDirector.Reject.DEADLINE_DROP.ordinal] > 0)
        // 会话账：同星同种已演——再排程不进队
        val s2 = UniverseDirector.State()
        UniverseDirector.enqueueCrawl(s2, t0, cfg, 7, longArrayOf(888001L), 1)
        tickOne(s2, t0)
        tickOne(s2, t0 + 5_200L) // 新星 X 出演
        UniverseDirector.scheduleSceneEvents(
            s2, t0 + 200_000L, cfg,
            listOf(UniverseDirector.SceneCandidate(888001L, UniverseDirector.EventKind.NOVA, 0.6f)),
        )
        assertTrue(tickOne(s2, t0 + 250_000L).isEmpty())
    }

    // ---------------- 确定性 ----------------

    @Test
    fun `确定性-同输入序列同指令序列`() {
        val stars = listOf(
            UniverseDirector.StarSig(101L, CelestialKind.SUPERNOVA, 12, 0), // 超新星
            UniverseDirector.StarSig(102L, CelestialKind.STAR, 4, 0),       // 新星
            UniverseDirector.StarSig(103L, CelestialKind.SUPERNOVA, 20, 5), // 超新星
            UniverseDirector.StarSig(104L, CelestialKind.STAR, 1, 1),       // 不演
        )
        fun runScript(): List<String> {
            val s = UniverseDirector.State()
            UniverseDirector.scheduleSceneEvents(s, t0, cfg, UniverseDirector.sceneCandidates(stars))
            UniverseDirector.enqueueCrawl(s, t0 + 3_000L, cfg, 42, longArrayOf(102L), 1)
            val sig = ArrayList<String>()
            for (k in 0..500) {
                val now = t0 + k * 1_000L
                for (d in tickOne(s, now)) {
                    sig += "${d.kind}|${d.chatId}|${d.seed}|${d.startMs}|${d.burstParticles}|${d.flashTtlSec}|${d.cometLifeSec}"
                }
            }
            return sig
        }
        val a = runScript()
        val b = runScript()
        assertTrue(a.isNotEmpty())
        assertEquals(a, b) // 双状态对拍：逐指令全字段相等
    }

    // ---------------- 场景候选分类门 ----------------

    @Test
    fun `候选分类-超新星与新星门`() {
        val stars = listOf(
            UniverseDirector.StarSig(201L, CelestialKind.SUPERNOVA, 12, 0),  // 超新星演出
            UniverseDirector.StarSig(202L, CelestialKind.SUPERNOVA, 9, 3),   // 超新星演出
            UniverseDirector.StarSig(203L, CelestialKind.SUPERNOVA, 6, 1),   // 不演（<8 演出级门）
            UniverseDirector.StarSig(204L, CelestialKind.STAR, 4, 0),        // 新星（复活）
            UniverseDirector.StarSig(205L, CelestialKind.STAR, 7, 0),        // 新星
            UniverseDirector.StarSig(206L, CelestialKind.STAR, 1, 0),        // 不演（太弱）
            UniverseDirector.StarSig(207L, CelestialKind.STAR, 5, -1),       // 不演（缺据诚实）
            // R320：黑洞改走宣判波档（GRAVITY_WAVE——黑洞宣判事件高优先；旧口径
            // 「黑洞不演」=它不演爆发叙事，现在它演涟漪——r315 E 触发源字面）
            UniverseDirector.StarSig(208L, CelestialKind.BLACK_HOLE, 30, 0), // 宣判波
            UniverseDirector.StarSig(209L, CelestialKind.PULSAR, 30, 0),     // 脉冲星不演
            UniverseDirector.StarSig(210L, CelestialKind.STAR, 0, 0),        // 死星不演
        )
        val cands = UniverseDirector.sceneCandidates(stars)
        val byId = cands.associateBy { it.chatId }
        assertEquals(UniverseDirector.EventKind.SUPERNOVA, byId[201L]?.kind)
        assertEquals(UniverseDirector.EventKind.SUPERNOVA, byId[202L]?.kind)
        assertEquals(UniverseDirector.EventKind.NOVA, byId[204L]?.kind)
        assertEquals(UniverseDirector.EventKind.NOVA, byId[205L]?.kind)
        assertEquals(UniverseDirector.EventKind.GRAVITY_WAVE, byId[208L]?.kind)
        assertEquals(5, cands.size) // 203/206/207/209/210 全不演（208 宣判波入列）
        // 排序：宣判波（高优先）→ 超新星 → recent7 降序 → chatId 升序
        assertEquals(listOf(208L, 201L, 202L, 205L, 204L), cands.map { it.chatId })
    }

    // ---------------- 排程 ----------------

    @Test
    fun `排程-散布窗内确定性定刻`() {
        val cands = listOf(
            UniverseDirector.SceneCandidate(301L, UniverseDirector.EventKind.NOVA, 0.6f),
            UniverseDirector.SceneCandidate(302L, UniverseDirector.EventKind.SUPERNOVA, 0.9f),
        )
        val s = UniverseDirector.State()
        UniverseDirector.scheduleSceneEvents(s, t0, cfg, cands)
        // 换场重排：清排程档留爬虫档
        UniverseDirector.enqueueCrawl(s, t0, cfg, 9, LongArray(0), 0)
        UniverseDirector.scheduleSceneEvents(s, t0 + 1_000L, cfg, cands)
        val fires = ArrayList<Pair<Long, UniverseDirector.Directive>>()
        for (k in 0..420) {
            val now = t0 + 1_000L + k * 1_000L
            for (d in tickOne(s, now)) fires += now to d
        }
        // 两候选都出演；发火时刻落在 [24s, 24s+360s] 散布窗
        val nova = fires.firstOrNull { it.second.chatId == 301L }
        val sup = fires.firstOrNull { it.second.chatId == 302L }
        assertTrue(nova != null && sup != null)
        assertTrue(nova!!.second.startMs >= t0 + 1_000L + cfg.startDelayMs)
        assertTrue(nova.second.startMs <= t0 + 1_000L + cfg.startDelayMs + cfg.spreadWindowMs + 1_000L)
        assertTrue(sup!!.second.startMs <= t0 + 1_000L + cfg.startDelayMs + cfg.spreadWindowMs + 1_000L)
    }

    // ---------------- 星云漂移 ----------------

    @Test
    fun `漂移-有界且确定`() {
        val out = FloatArray(3)
        val cx = 300f
        val cy = -120f
        val cz = 480f
        val r = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
        val bound = r * (kotlin.math.sin(cfg.driftSwayRad) + cfg.driftRadialAmp) + 0.5f
        var phase = 0.0
        repeat(400) {
            UniverseDirector.applyDrift(cx, cy, cz, 0, phase, cfg, out)
            val dx = out[0] - cx
            val dy = out[1] - cy
            val dz = out[2] - cz
            val off = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            assertTrue("漂移离锚 $off 超界 $bound", off <= bound)
            phase += 0.011 // 约 2.6s 一步
        }
        // 确定性：同槽同相同值
        val a = FloatArray(3)
        val b = FloatArray(3)
        UniverseDirector.applyDrift(cx, cy, cz, 2, 3.75, cfg, a)
        UniverseDirector.applyDrift(cx, cy, cz, 2, 3.75, cfg, b)
        assertTrue(a.contentEquals(b))
        // 错频：异槽不同相
        val c = FloatArray(3)
        UniverseDirector.applyDrift(cx, cy, cz, 3, 3.75, cfg, c)
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun `漂移-tick 积分推进且活跃微加速`() {
        val s = UniverseDirector.State()
        tickOne(s, t0) // 首拍只锚钟
        assertEquals(0.0, UniverseDirector.driftTurnsOf(s), 1e-9)
        tickOne(s, t0 + 1_000L)
        val calm = UniverseDirector.driftTurnsOf(s)
        assertTrue(calm > 0.0)
        // 掉帧钳：一拍隔 30s 只按 500ms 进
        tickOne(s, t0 + 31_000L)
        val afterGap = UniverseDirector.driftTurnsOf(s) - calm
        assertEquals(0.5 / cfg.driftPeriodSec, afterGap, 1e-6)
    }

    // ---------------- 流星节奏档 ----------------

    @Test
    fun `流星档-并发减速与虫洞让位`() {
        val tight = UniverseDirector.Config(minIntervalMs = 100L)
        val s = UniverseDirector.State()
        assertEquals(1.0f, UniverseDirector.meteorPaceOf(s, t0, tight), 1e-4f)
        // 彗星（活跃窗 ~2.9s）+ 新星（4.5s）交叠期=并发 2 → 0.5
        UniverseDirector.enqueueCrawl(s, t0, tight, 21, longArrayOf(555001L), 1)
        tickOne(s, t0, tight) // 彗星发车
        tickOne(s, t0 + 800L, tight) // 新星出演
        assertEquals(0.5f, UniverseDirector.meteorPaceOf(s, t0 + 1_000L, tight), 1e-4f)
        // 彗星寿尽（t0+2.9s）新星犹在（t0+800+4.5s）=并发 1 → 0.75
        assertEquals(0.75f, UniverseDirector.meteorPaceOf(s, t0 + 3_100L, tight), 1e-4f)
        // 虫洞 transit → 0（穿越期星轨让位）
        s.transitHold = true
        assertEquals(0.0f, UniverseDirector.meteorPaceOf(s, t0 + 3_100L, tight), 1e-4f)
        s.transitHold = false
        // 活跃窗尽（全灭后）→ 回 1.0
        assertEquals(1.0f, UniverseDirector.meteorPaceOf(s, t0 + 30_000L, tight), 1e-4f)
    }

    // ---------------- 账本 ----------------

    @Test
    fun `账本-roundTrip 与环帽`() {
        val entries = mutableListOf<UniverseDirector.Entry>()
        repeat(400) { i ->
            UniverseDirector.appendEntry(
                entries,
                UniverseDirector.Entry(t0 + i * 1_000L, UniverseDirector.EventKind.values()[i % 3], 900000L + i, i, 600),
            )
        }
        assertEquals(UniverseDirector.JOURNAL_CAP, entries.size) // 环帽挤最老
        assertEquals(t0 + (400 - UniverseDirector.JOURNAL_CAP) * 1_000L, entries.first().epochMs)
        val text = UniverseDirector.serializeJournal(entries)
        val back = UniverseDirector.parseJournal(text)
        assertEquals(entries.size, back.size)
        assertEquals(entries.last().epochMs, back.last().epochMs)
        assertEquals(entries.last().kind, back.last().kind)
        assertEquals(entries.last().chatId, back.last().chatId)
        // 坏行容错
        val dirty = "abc|x|y|z|w\n${t0}|1|123|7|600\n1|2|3\n${t0 + 5}|9|1|1|1\n"
        val parsed = UniverseDirector.parseJournal(dirty)
        assertEquals(1, parsed.size) // 只有中间好行（kind=9 越界也弃）
        assertEquals(123L, parsed[0].chatId)
    }

    @Test
    fun `回放-确定性重演原种子原序`() {
        val entries = listOf(
            UniverseDirector.Entry(t0 + 3_000L, UniverseDirector.EventKind.NOVA, 1001L, 55, 600),
            UniverseDirector.Entry(t0 + 1_000L, UniverseDirector.EventKind.COMET, 0L, 77, 350),
            UniverseDirector.Entry(t0 - 90_000_000L, UniverseDirector.EventKind.SUPERNOVA, 1002L, 99, 900), // 窗外
            UniverseDirector.Entry(t0 + 2_000L, UniverseDirector.EventKind.SUPERNOVA, 1003L, 88, 800),
        )
        val now = t0 + 10_000L
        val a = UniverseDirector.replayEntries(entries, now)
        val b = UniverseDirector.replayEntries(entries, now)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].kind, b[i].kind)
            assertEquals(a[i].seed, b[i].seed)
            assertEquals(a[i].startMs, b[i].startMs)
        }
        assertEquals(3, a.size) // 窗外一件不进
        // 原相对序（按 epochMs 排序）+ 等距 1.5s 重排程
        assertEquals(UniverseDirector.EventKind.COMET, a[0].kind)
        assertEquals(UniverseDirector.EventKind.SUPERNOVA, a[1].kind)
        assertEquals(UniverseDirector.EventKind.NOVA, a[2].kind)
        assertEquals(now, a[0].startMs)
        assertEquals(now + 1_500L, a[1].startMs)
        assertEquals(now + 3_000L, a[2].startMs)
        assertEquals(77, a[0].seed) // 原种子保留=同轨迹
    }

    @Test
    fun `热身-跨重启节奏记忆抑复燃`() {
        // 账本：40s 前星 X 演过新星 + 一分钟内已发 5 件
        val entries = mutableListOf<UniverseDirector.Entry>()
        for (i in 0 until 5) {
            entries += UniverseDirector.Entry(t0 - 50_000L + i * 2_000L, UniverseDirector.EventKind.COMET, 0L, 300 + i, 500)
        }
        entries += UniverseDirector.Entry(t0 - 40_000L, UniverseDirector.EventKind.NOVA, 666001L, 44, 600)
        val s = UniverseDirector.State()
        UniverseDirector.warmUp(s, entries, t0, cfg)
        // 分钟窗记忆：全局已发 5+1=6 ≥ 帽 5——新脉冲被全局帽挂起
        UniverseDirector.enqueueCrawl(s, t0, cfg, 66, LongArray(0), 0)
        assertTrue(tickOne(s, t0).isEmpty())
        assertTrue(s.rejected[UniverseDirector.Reject.GLOBAL_MINUTE_CAP.ordinal] > 0)
        // 同星冷却记忆：星 X 冷却期内不重演（窗滑到能发时先验冷却）
        val s2 = UniverseDirector.State()
        UniverseDirector.warmUp(s2, listOf(entries.last()), t0, cfg)
        UniverseDirector.enqueueCrawl(s2, t0, cfg, 67, longArrayOf(666001L), 1)
        tickOne(s2, t0) // 彗星放行（chatId=0 不吃星冷却）
        val held = tickOne(s2, t0 + 5_200L)
        assertEquals(0, held.count { it.chatId == 666001L })
        assertTrue(s2.rejected[UniverseDirector.Reject.STAR_COOLDOWN.ordinal] > 0)
    }

    @Test
    fun `指令组曲-种类参数锚`() {
        val comet = UniverseDirector.directiveOf(UniverseDirector.EventKind.COMET, 0L, 5, 1.0f, t0)
        assertTrue(comet.cometLifeSec > 3.5f) // 满强度 3.6s
        assertEquals(0, comet.burstParticles)
        val nova = UniverseDirector.directiveOf(UniverseDirector.EventKind.NOVA, 1L, 5, 0.5f, t0)
        assertEquals(14, nova.burstParticles)
        assertEquals(4.5f, nova.flashTtlSec, 1e-4f)
        val sup = UniverseDirector.directiveOf(UniverseDirector.EventKind.SUPERNOVA, 1L, 5, 1.0f, t0)
        assertEquals(32, sup.burstParticles)
        assertEquals(7.0f, sup.flashTtlSec, 1e-4f)
        assertTrue(sup.burstSpeedPx > nova.burstSpeedPx) // 超新星更烈
        assertTrue(sup.durationMs > nova.durationMs)
    }

    // ---------------- R320 引力波（GRAVITY_WAVE 档——r315 S6/r317 挂账⑥ 兑现） ----------------

    @Test
    fun `宣判波候选-坍缩族高优先排首白洞不入`() {
        val stars = listOf(
            UniverseDirector.StarSig(201L, CelestialKind.SUPERNOVA, 12, 0), // 超新星演出（涟漪=指令第二层）
            UniverseDirector.StarSig(202L, CelestialKind.BLACK_HOLE, 10, 0), // 宣判波
            UniverseDirector.StarSig(203L, CelestialKind.NEUTRON_STAR, -1, 0), // 宣判波（缺据基档）
            UniverseDirector.StarSig(204L, CelestialKind.QUARK_STAR, 30, 0), // 宣判波
            // 白洞：宣判波不入（源非坍缩）；NOVA 档按既有口径判（缺据 prev7<0=
            // 不演——白洞的爆发演出语义归 R282 既有门，本批零改）
            UniverseDirector.StarSig(205L, CelestialKind.WHITE_HOLE, 10, -1),
            UniverseDirector.StarSig(206L, CelestialKind.PULSAR, 10, 0), // 不演
            UniverseDirector.StarSig(207L, CelestialKind.STAR, 3, 0), // 复活档
        )
        val cands = UniverseDirector.sceneCandidates(stars)
        // 坍缩族三值全进 GRAVITY_WAVE 档；白洞/脉冲星零候选
        assertEquals(3, cands.count { it.kind == UniverseDirector.EventKind.GRAVITY_WAVE })
        assertEquals(0, cands.count { it.chatId == 205L })
        assertEquals(0, cands.count { it.chatId == 206L })
        // 高优先排首：GRAVITY_WAVE（黑洞宣判事件高优先）> 超新星 > 复活
        assertEquals(UniverseDirector.EventKind.GRAVITY_WAVE, cands.first().kind)
        assertTrue(
            cands.indexOfFirst { it.kind == UniverseDirector.EventKind.SUPERNOVA } >
                cands.indexOfLast { it.kind == UniverseDirector.EventKind.GRAVITY_WAVE },
        )
        // 强度对接既有公式（ supernovaIntensityOf 单口）+缺据 0.72 基档诚实缺省
        val bh = cands.first { it.chatId == 202L }
        assertEquals(UniverseDirector.supernovaIntensityOf(10), bh.intensity01, 1e-4f)
        assertTrue(bh.intensity01 in 0.60f..1f)
        val ns = cands.first { it.chatId == 203L }
        assertEquals(0.72f, ns.intensity01, 1e-4f)
    }

    @Test
    fun `宣判波-排程发火指令参数与节奏闸分类帽`() {
        val tight = UniverseDirector.Config(
            minIntervalMs = 100L, gravityWavePerMinute = 1, globalPerMinute = 30,
            startDelayMs = 0L, spreadWindowMs = 0L,
        )
        val s = UniverseDirector.State()
        UniverseDirector.scheduleSceneEvents(
            s, t0, tight,
            listOf(
                UniverseDirector.SceneCandidate(301L, UniverseDirector.EventKind.GRAVITY_WAVE, 1.0f),
                UniverseDirector.SceneCandidate(302L, UniverseDirector.EventKind.GRAVITY_WAVE, 1.0f),
                UniverseDirector.SceneCandidate(303L, UniverseDirector.EventKind.GRAVITY_WAVE, 1.0f),
            ),
        )
        // 分类帽=1/分：同窗只放第一颗，其余挂起（KIND_MINUTE_CAP 落账）
        val d1 = tickOne(s, t0, tight)
        assertEquals(1, d1.size)
        assertEquals(UniverseDirector.EventKind.GRAVITY_WAVE, d1[0].kind)
        val d2 = tickOne(s, t0 + 1_000L, tight)
        assertEquals(0, d2.size)
        assertTrue(s.rejected[UniverseDirector.Reject.KIND_MINUTE_CAP.ordinal] > 0)
        // 发火指令参数锚：演出级闪光 9.0s+宣判档波前参数位（R327 对接口）
        val d = d1[0]
        assertEquals(9.0f, d.flashTtlSec, 1e-4f)
        assertEquals(9_000L, d.durationMs)
        assertEquals(GravityWaveFx.verdictAmpPxOf(1.0f), d.waveAmpPx, 1e-4f)
        assertEquals(GravityWaveFx.waveLifeSecOf(1.0f), d.waveLifeSec, 1e-4f)
        // 旧三值序号不动（枚举排尾=账本 ordinal 兼容）
        assertEquals(3, UniverseDirector.EventKind.GRAVITY_WAVE.ordinal)
    }

    @Test
    fun `指令组曲-GRAVITY_WAVE 波前参数位与旧档涟漪层`() {
        // 超新星指令=涟漪第二视觉层（「同 Directive」r315 E④ 字面——TTL 7.0 过演出门）
        val sup = UniverseDirector.directiveOf(UniverseDirector.EventKind.SUPERNOVA, 1L, 5, 1.0f, t0)
        assertEquals(GravityWaveFx.rippleAmpPxOf(1.0f), sup.waveAmpPx, 1e-4f)
        assertEquals(GravityWaveFx.waveLifeSecOf(1.0f), sup.waveLifeSec, 1e-4f)
        assertTrue(sup.waveAmpPx in 2.2f..4.0f)
        // GRAVITY_WAVE=宣判档（1.5×幅度——质量更大的事件=更强的波）
        val gw = UniverseDirector.directiveOf(UniverseDirector.EventKind.GRAVITY_WAVE, 9L, 5, 0.8f, t0)
        assertEquals(GravityWaveFx.verdictAmpPxOf(0.8f), gw.waveAmpPx, 1e-4f)
        assertTrue(gw.waveAmpPx > sup.waveAmpPx * 0.8f)
        assertEquals(GravityWaveFx.waveLifeSecOf(0.8f), gw.waveLifeSec, 1e-4f)
        assertTrue(gw.flashTtlSec > sup.flashTtlSec)
        // NOVA/COMET 恒零波前参数（复燃不是质量剧变）
        val nova = UniverseDirector.directiveOf(UniverseDirector.EventKind.NOVA, 1L, 5, 1.0f, t0)
        assertEquals(0f, nova.waveAmpPx, 1e-6f)
        val comet = UniverseDirector.directiveOf(UniverseDirector.EventKind.COMET, 0L, 5, 1.0f, t0)
        assertEquals(0f, comet.waveAmpPx, 1e-6f)
    }

    @Test
    fun `入队口-错拍入队同星去重零星防御`() {
        // enqueueGravityWave=R327 跃迁 diff 对接口（错拍 700ms 尾随——爬虫档同款）
        val s = UniverseDirector.State()
        UniverseDirector.enqueueGravityWave(s, t0, cfg, 401L, 0.9f)
        UniverseDirector.enqueueGravityWave(s, t0, cfg, 401L, 0.5f) // 同星同种在队去重
        UniverseDirector.enqueueGravityWave(s, t0, cfg, 0L, 0.9f) // 零星防御
        val early = tickOne(s, t0 + 500L)
        assertEquals(0, early.size) // 700ms 错拍未到
        val fired = tickOne(s, t0 + 800L)
        assertEquals(1, fired.size)
        assertEquals(UniverseDirector.EventKind.GRAVITY_WAVE, fired[0].kind)
        assertEquals(401L, fired[0].chatId)
        assertEquals(0.9f, fired[0].intensity01, 1e-4f) // 首入队的强度档保留（去重不覆盖）
    }

    @Test
    fun `账本-GRAVITY_WAVE 往返与旧三值兼容`() {
        val entries = listOf(
            UniverseDirector.Entry(t0, UniverseDirector.EventKind.COMET, 0L, 1, 500),
            UniverseDirector.Entry(t0 + 1_000L, UniverseDirector.EventKind.GRAVITY_WAVE, 501L, 2, 900),
        )
        val back = UniverseDirector.parseJournal(UniverseDirector.serializeJournal(entries))
        assertEquals(2, back.size)
        assertEquals(UniverseDirector.EventKind.GRAVITY_WAVE, back[1].kind)
        assertEquals(3, back[1].kind.ordinal) // 序号=ordinal 排尾兼容
        // 回放重演含 GRAVITY_WAVE（可回放原则——同种子同涟漪）
        val replay = UniverseDirector.replayEntries(entries, t0 + 5_000L)
        assertEquals(UniverseDirector.EventKind.GRAVITY_WAVE, replay[1].kind)
        assertEquals(GravityWaveFx.verdictAmpPxOf(0.9f), replay[1].waveAmpPx, 1e-4f)
    }
}
