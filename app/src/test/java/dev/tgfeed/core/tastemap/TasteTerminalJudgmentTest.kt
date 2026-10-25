package dev.tgfeed.core.tastemap

import dev.tgfeed.core.tastemap.TasteAstroPhysics.DeathPattern
import dev.tgfeed.core.tastemap.TasteAstroPhysics.TerminalReason
import dev.tgfeed.core.tastemap.TasteAstroPhysics.WhiteholeEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R317 生死判据族单测（r315 单测锚总表——本包最重的单测面）：
 *  - **直方图特征**：爆发峰双门（8 条绝对门/3× 基线门·边界含/不含）/骤停 vs 缓熄
 *    （1.5×/0.5× 边界）/活跃寿命与断更（日粒度）/缺据 null；
 *  - **终态宣判矩阵**：黑洞已坍缩/正在坍缩（盛宴=类星体相）/静默巨兽反例（大而
 *    未坍缩=还是巨星——用户实锤「儿戏」病根治）/中子星正反例（爆发峰死亡窗/
 *    断更 90 线）/夸克星苛刻门（烈度 outlier·须对照·等峰不判）/三判互斥与优先级/
 *    top≤4 帽+chatId 破平/缺据诚实回落/确定性；
 *  - **类星体 accretion01**：归一/缺据零值/盛宴门；
 *  - **白洞门**：零回馈反例/收割门三路/bot 排除/互动账缺据不判/top-N 确定性。
 *
 * 直方图构造约定：计数落在 30 天桶起点（dayIndex 为 30 的倍数）——判据数学可
 * 精确手算；nowDay=20000、末日桶=daysAgo 200（deadDays=200）。
 */
class TasteTerminalJudgmentTest {

    /** 固定时钟（判据只看相对天数——零系统钟，确定性） */
    private val nowSec = 20_000L * 86_400L + 43_200L // dayIndex 20000 的正午

    /** daysAgo→dayIndex 直方图构造 */
    private fun dayHist(vararg pairs: Pair<Int, Int>): List<Pair<Int, Int>> {
        val nowDay = (nowSec / 86_400L).toInt()
        return pairs.map { (daysAgo, cnt) -> (nowDay - daysAgo) to cnt }
    }

    private fun features(vararg pairs: Pair<Int, Int>) =
        TasteAstroPhysics.publishHistogramFeatures(dayHist(*pairs), nowSec)

    // ==================== 直方图特征（爆发峰/骤停/缓熄/寿命） ====================

    @Test
    fun `特征-爆发峰3x基线门边界`() {
        // 10 桶×100 + 1 桶 375：items=1375、桶 11、基线 125 → 375 ≥ 3×125（恰等=含）
        assertTrue(features(200 to 375, *(230..500 step 30).map { it to 100 }.toTypedArray())!!.hasBurstPeak)
        // 374 < 375：差一条都不算爆发（严格门）
        assertTrue(!features(200 to 374, *(230..500 step 30).map { it to 100 }.toTypedArray())!!.hasBurstPeak)
    }

    @Test
    fun `特征-爆发峰8条绝对门`() {
        // 基线 2.55（28 条/11 桶）×3=7.6 <8：绝对门接管——8 条过/7 条不过
        val steady = (230..500 step 30).map { it to 2 }.toTypedArray()
        assertTrue(features(200 to 8, *steady)!!.hasBurstPeak)
        assertTrue(!features(200 to 7, *steady)!!.hasBurstPeak)
    }

    @Test
    fun `特征-骤停与缓熄边界`() {
        // 9 桶×100 + 死前 2 桶 w：ABRUPT ⟺ w ≥ 168.75（w=169 过/168 回平稳收尾档）
        val steady = (260..500 step 30).map { it to 100 }.toTypedArray()
        assertEquals(
            DeathPattern.ABRUPT_STOP,
            features(200 to 169, 230 to 169, *steady)!!.deathPattern,
        )
        assertEquals(
            DeathPattern.STEADY_TAIL,
            features(200 to 168, 230 to 168, *steady)!!.deathPattern,
        )
        // SLOW ⟺ w < 45（44 过/45 回平稳收尾档）
        assertEquals(DeathPattern.SLOW_FADE, features(200 to 44, 230 to 44, *steady)!!.deathPattern)
        assertEquals(DeathPattern.STEADY_TAIL, features(200 to 45, 230 to 45, *steady)!!.deathPattern)
    }

    @Test
    fun `特征-一周内仍发布=活着`() {
        val f = features(0 to 50, *(30..300 step 30).map { it to 50 }.toTypedArray())!!
        assertEquals(DeathPattern.ALIVE, f.deathPattern)
        assertEquals(0L, f.deadDays)
    }

    @Test
    fun `特征-寿命断更与缺据`() {
        // 中子星形状：末日桶 450 + 前 10 桶×50 → items 950/寿命 300 天/断更 200 天
        val f = features(200 to 450, *(230..500 step 30).map { it to 50 }.toTypedArray())!!
        assertEquals(950, f.items)
        assertEquals(300L, f.lifetimeDays)
        assertEquals(200L, f.deadDays)
        assertEquals(86.36f, f.baselinePer30d, 0.01f)
        assertTrue(f.hasBurstPeak)
        assertEquals(450, f.burstPeakCount)
        assertEquals(DeathPattern.ABRUPT_STOP, f.deathPattern)
        // 缺据：空直方图=null（判据族不宣判——绝不冒充）
        assertNull(TasteAstroPhysics.publishHistogramFeatures(emptyList(), nowSec))
    }

    @Test
    fun `特征-确定性同输入两跑逐位一致`() {
        val h = dayHist(200 to 450, *(230..500 step 30).map { it to 50 }.toTypedArray())
        assertEquals(
            TasteAstroPhysics.publishHistogramFeatures(h, nowSec),
            TasteAstroPhysics.publishHistogramFeatures(h, nowSec),
        )
    }

    // ==================== 终态宣判矩阵 ====================

    /** 黑洞坍缩形状：末日桶 4000 + 前 10 桶×1000（爆发过+骤停+断更 200 天） */
    private val bhCollapseHist = listOf(200 to 4000) + (230..500 step 30).map { it to 1000 }

    /** 中子星形状：末日桶 450 + 前 10 桶×50（爆发在死亡前+骤停+断更 200 天） */
    private val neutronHist = listOf(200 to 450) + (230..500 step 30).map { it to 50 }

    private fun ev(
        chatId: Long, items: Int, rank: Float, recent7: Int = 0,
        hist: List<Pair<Int, Int>>? = bhCollapseHist,
    ) = TasteAstroPhysics.TerminalEvidence(
        chatId, items, rank, recent7,
        hist?.let { TasteAstroPhysics.publishHistogramFeatures(dayHist(*it.toTypedArray()), nowSec) },
    )

    private fun judge(vararg cands: TasteAstroPhysics.TerminalEvidence, mean: Float = 2500f) =
        TasteAstroPhysics.judgeTerminalStates(cands.toList(), mean)

    @Test
    fun `黑洞-大而坍缩正例（爆发史+断更骤停）`() {
        val v = judge(ev(1, 42_000, 0.99f)).single()
        assertEquals(CelestialKind.BLACK_HOLE, v.kind)
        assertEquals(TerminalReason.BLACK_HOLE_COLLAPSED, v.reason)
    }

    @Test
    fun `黑洞-正在坍缩=吸积盛宴（类星体相；直方图缺据仍可判）`() {
        // recent7≥8 即事件证据——缺直方图不挡盛宴档
        val v = judge(ev(1, 42_000, 0.99f, recent7 = 8, hist = null)).single()
        assertEquals(CelestialKind.BLACK_HOLE, v.kind)
        assertEquals(TerminalReason.BLACK_HOLE_FEASTING, v.reason)
        // 直方图在场（无爆发史）也判盛宴——盛宴分支优先于坍缩判据
        val v2 = judge(
            ev(1, 42_000, 0.99f, recent7 = 8, hist = listOf(200 to 100) + (230..500 step 30).map { it to 100 }),
        ).single()
        assertEquals(TerminalReason.BLACK_HOLE_FEASTING, v2.reason)
    }

    @Test
    fun `静默巨兽-反例（大而未坍缩=还是巨星——儿戏病根治）`() {
        // 稳态发布无爆发史：三重门过也不判黑洞（体量过门≠够大就晋）
        val v = judge(ev(1, 42_000, 0.99f, hist = (200..500 step 30).map { it to 1000 })).single()
        assertNull(v.kind)
        assertEquals(TerminalReason.SILENT_GIANT, v.reason)
        // 爆发史在但缓熄未骤停：同样大而未坍缩
        val v2 = judge(
            ev(
                1, 42_000, 0.99f,
                hist = listOf(200 to 50, 230 to 50, 260 to 50) +
                    (290..470 step 30).map { it to 500 } + listOf(500 to 3000),
            ),
        ).single()
        assertEquals(TerminalReason.SILENT_GIANT, v2.reason)
    }

    @Test
    fun `黑洞-缺直方图且非盛宴=缺据不冒充坍缩`() {
        val v = judge(ev(1, 42_000, 0.99f, hist = null)).single()
        assertNull(v.kind)
        assertEquals(TerminalReason.SILENT_GIANT, v.reason)
    }

    @Test
    fun `中子星-正例（简并门+爆发骤停余烬）`() {
        val v = judge(ev(1, 950, 0.92f, hist = neutronHist)).single()
        assertEquals(CelestialKind.NEUTRON_STAR, v.kind)
        assertEquals(TerminalReason.NEUTRON_STAR, v.reason)
    }

    @Test
    fun `中子星-反例三件（分位不足、断更不足90、爆发太早）`() {
        // 简并门分位不足（0.89 < P90）
        assertEquals(TerminalReason.NOT_TERMINAL, judge(ev(1, 950, 0.89f, hist = neutronHist)).single().reason)
        // 断更 80 天 ≤ 90 天线（末日桶=day 19920 → deadDays=80）
        val dead80 = listOf(80 to 450) + (110..380 step 30).map { it to 50 }
        assertEquals(TerminalReason.NOT_TERMINAL, judge(ev(1, 950, 0.92f, hist = dead80)).single().reason)
        // 爆发峰不在死亡前 90 天内（峰在 500 天前的远古桶）
        val earlyBurst = (200..470 step 30).map { it to 50 } + listOf(500 to 450)
        assertEquals(TerminalReason.NOT_TERMINAL, judge(ev(1, 950, 0.92f, hist = earlyBurst)).single().reason)
    }

    @Test
    fun `中子星-缺直方图不宣判`() {
        assertEquals(TerminalReason.NOT_TERMINAL, judge(ev(1, 950, 0.92f, hist = null)).single().reason)
    }

    @Test
    fun `夸克星-烈度outlier须对照（等峰不判、互斥）`() {
        // A 峰 450 ≥ 1.5×其余最大 200 → A 晋夸克星；B/C 留中子星（三判互斥）
        // （B/C 体量 850≥800 简并门——700 不入门无对照集）
        val bHist = listOf(200 to 200) + (230..500 step 30).map { it to 50 }
        val vs = judge(
            ev(1, 950, 0.92f, hist = neutronHist),
            ev(2, 850, 0.91f, hist = bHist),
            ev(3, 850, 0.91f, hist = bHist),
        )
        assertEquals(CelestialKind.QUARK_STAR, vs[0].kind)
        assertEquals(TerminalReason.QUARK_STAR, vs[0].reason)
        assertEquals(CelestialKind.NEUTRON_STAR, vs[1].kind)
        assertEquals(CelestialKind.NEUTRON_STAR, vs[2].kind)
        // 单颗中子候选无对照——不冒充「更极端」
        val single = judge(ev(1, 950, 0.92f, hist = neutronHist)).single()
        assertEquals(CelestialKind.NEUTRON_STAR, single.kind)
        // 等峰（450 vs 450）无 outlier——不判夸克
        val twin = judge(ev(1, 950, 0.92f, hist = neutronHist), ev(2, 950, 0.92f, hist = neutronHist))
        assertTrue(twin.all { it.kind == CelestialKind.NEUTRON_STAR })
    }

    @Test
    fun `夸克星-全库上限MAX_QUARK为1`() {
        assertEquals(1, TasteAstroPhysics.MAX_QUARK)
    }

    @Test
    fun `矩阵-黑洞优先于中子星（同一证据流不双判）`() {
        // 三重门过 + 中子形状（爆发+骤停+断更）→ 黑洞（坍缩口径吸收，不双判中子星）
        val v = judge(ev(1, 42_000, 0.99f, hist = neutronHist)).single()
        assertEquals(CelestialKind.BLACK_HOLE, v.kind)
        assertEquals(TerminalReason.BLACK_HOLE_COLLAPSED, v.reason)
    }

    @Test
    fun `矩阵-top4帽与chatId破平（体量降序）`() {
        // 6 颗全过坍缩门：top4=43000/42000/41000/40000(chatId 3 破平压过 9)；9 与 4 超帽回落
        val vs = judge(
            ev(0, 43_000, 0.99f), ev(1, 42_000, 0.99f), ev(2, 41_000, 0.99f),
            ev(9, 40_000, 0.99f), ev(3, 40_000, 0.99f), ev(4, 39_000, 0.99f),
        ).associateBy { it.chatId }
        assertEquals(CelestialKind.BLACK_HOLE, vs[0]!!.kind)
        assertEquals(CelestialKind.BLACK_HOLE, vs[1]!!.kind)
        assertEquals(CelestialKind.BLACK_HOLE, vs[2]!!.kind)
        assertEquals(CelestialKind.BLACK_HOLE, vs[3]!!.kind) // 40000 破平：chatId 3 胜
        assertEquals(TerminalReason.BLACK_HOLE_CAPPED, vs[9]!!.reason) // 40000 破平败者
        assertNull(vs[9]!!.kind)
        assertEquals(TerminalReason.BLACK_HOLE_CAPPED, vs[4]!!.reason) // 体量第 6 超帽
    }

    @Test
    fun `矩阵-空表与确定性`() {
        assertTrue(judge().isEmpty())
        val cands = arrayOf(
            ev(1, 42_000, 0.99f), ev(2, 950, 0.92f, hist = neutronHist),
            ev(3, 42_000, 0.99f, hist = null),
        )
        assertEquals(judge(*cands), judge(*cands))
    }

    @Test
    fun `池选-简并门预筛与体量截断（先池选后点查的有界预算口）`() {
        assertEquals(30, TasteAstroPhysics.TERMINAL_POOL_CAP)
        // 32 颗简并门过者 + 1 颗小频道（不入池）+ 1 颗分位不足（不入池）
        val cands = (0 until 32).map { ev(100L + it, 900 + it, 0.93f, hist = null) } +
            listOf(ev(200, 700, 0.95f, hist = null), ev(201, 50_000, 0.50f, hist = null))
        val pool = TasteAstroPhysics.terminalPoolOf(cands)
        // 截断 ≤30：体量降序前 30（900+31..900+0 里大的先）+小频道/低分位剔除
        assertEquals(30, pool.size)
        assertTrue(pool.all { it.volumeRank01 >= TasteAstroPhysics.NEUTRON_RANK && it.items >= TasteAstroPhysics.NEUTRON_MIN_ITEMS })
        // 体量降序+chatId 破平（931 最大在首位）
        assertEquals(931, pool.first().items)
        // 确定性：同输入两跑一致
        assertEquals(pool, TasteAstroPhysics.terminalPoolOf(cands))
    }

    // ==================== 类星体 accretion01（黑洞活动态修饰——不改枚举） ====================

    @Test
    fun `accretion01-归一与缺据零值`() {
        assertEquals(0f, TasteAstroPhysics.accretion01Of(-1), 1e-6f) // 缺据=静默档（不冒充类星体相）
        assertEquals(0f, TasteAstroPhysics.accretion01Of(0), 1e-6f)
        assertEquals(0.5f, TasteAstroPhysics.accretion01Of(7), 1e-4f)
        assertEquals(1f, TasteAstroPhysics.accretion01Of(14), 1e-6f) // 每周 14 条=饱和
        assertEquals(1f, TasteAstroPhysics.accretion01Of(28), 1e-6f) // 钳 0..1
        assertEquals(3f / 14f, TasteAstroPhysics.accretion01Of(3), 1e-4f)
    }

    @Test
    fun `类星体相-盛宴门为8`() {
        assertTrue(TasteAstroPhysics.isQuasarPhase(8))
        assertTrue(TasteAstroPhysics.isQuasarPhase(100))
        assertTrue(!TasteAstroPhysics.isQuasarPhase(7))
        assertTrue(!TasteAstroPhysics.isQuasarPhase(-1))
        assertEquals(8, TasteAstroPhysics.ACCRETION_FEAST_RECENT7)
    }

    // ==================== 白洞门（收割零回馈） ====================

    private fun wh(
        chatId: Long, watchSec: Long = 4 * 3600L, watched: Int = 35, tier: Int = -1,
        like: Int = 0, fav: Int = 0, dan: Int = 0, entity: CelestialKind? = null,
    ) = WhiteholeEvidence(chatId, watchSec, watched, tier, like, fav, dan, entity)

    @Test
    fun `白洞-正例（高收割+零回馈+频道型）`() {
        assertEquals(listOf(1L), TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1))))
    }

    @Test
    fun `白洞-零回馈反例（有任一互动绝不判）`() {
        assertTrue(TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, like = 1))).isEmpty())
        assertTrue(TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, fav = 2))).isEmpty())
        assertTrue(TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, dan = 3))).isEmpty())
    }

    @Test
    fun `白洞-收割门三路与不足`() {
        // watchSec 恰 3h（边界含）；watchedCount 恰 30；goldTier==1（T1 重仓）——三路任一过门
        assertEquals(listOf(1L), TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, watchSec = 3 * 3600L, watched = 0))))
        assertEquals(listOf(1L), TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, watchSec = 0, watched = 30))))
        assertEquals(listOf(1L), TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, watchSec = 0, watched = 0, tier = 1))))
        // 三路全不足（2.99h+0 条+无档）=不判
        assertTrue(
            TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, watchSec = 3 * 3600L - 1, watched = 0))).isEmpty(),
        )
    }

    @Test
    fun `白洞-bot排除与互动账缺据`() {
        // bot 实体档归脉冲星（PULSAR 判据不动）——收割再大也不判白洞
        assertTrue(TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, entity = CelestialKind.PULSAR))).isEmpty())
        // 互动账缺据（-1 哨兵）=旧库无账——绝不冒充零回馈
        assertTrue(TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, like = -1))).isEmpty())
        assertTrue(TasteAstroPhysics.judgeWhiteHoles(listOf(wh(1, dan = -1))).isEmpty())
    }

    @Test
    fun `白洞-top4帽与确定性（收割量降序+chatId破平）`() {
        val cands = listOf(
            wh(11, watchSec = 6 * 3600L), wh(12, watchSec = 5 * 3600L),
            wh(21, watchSec = 4 * 3600L), wh(20, watchSec = 4 * 3600L), // 破平：chatId 20 先
            wh(15, watchSec = 0, watched = 31), wh(16, watchSec = 0, watched = 31), // 收割弱档垫底
        )
        // 6 颗过门取 top4：6h/5h/4h(chatId 20)/4h(chatId 21)——15/16 超帽
        assertEquals(listOf(11L, 12L, 20L, 21L), TasteAstroPhysics.judgeWhiteHoles(cands))
        assertEquals(TasteAstroPhysics.MAX_WHITE_HOLES, 4)
        // 确定性：同输入两跑一致
        assertEquals(
            TasteAstroPhysics.judgeWhiteHoles(cands),
            TasteAstroPhysics.judgeWhiteHoles(cands),
        )
    }

    // ==================== R317b 信息卡接线（类星体相行+前史行——factLine hist 注入） ====================

    /** factLine 便捷口（黑洞默认参——R317b 接线锚专用段） */
    private fun bhLine(recent7: Int, hist: TasteAstroPhysics.PublishFeatures?): String =
        TasteAstroPhysics.factLine(
            CelestialKind.BLACK_HOLE, 42_000, recent7, -1, 100L, 900L, 2500f, null, 0, hist,
        )

    @Test
    fun `信息卡-类星体相行（accretion高时出·盛宴门直判不需要hist）`() {
        // recent7≥8（盛宴门）→ 相行在场（含门槛数字与「盖过星系」）
        val feast = bhLine(recent7 = 12, hist = null)
        assertTrue(feast.contains("类星体相"))
        assertTrue(feast.contains("12 条"))
        assertTrue(feast.contains("吸积盛宴"))
        // 静默黑洞（recent7=7<8）→ 相行不出（黑洞身份行照旧）
        val silent = bhLine(recent7 = 7, hist = null)
        assertTrue(!silent.contains("类星体相"))
        assertTrue(silent.contains("黑洞"))
        // 缺据（-1=无金矿证据）→ 相行不出（不冒充盛宴——诚实红线）
        assertTrue(!bhLine(recent7 = -1, hist = null).contains("类星体相"))
    }

    @Test
    fun `信息卡-前史行（hist注入出·缺据整行不出）`() {
        val hist = TasteAstroPhysics.publishHistogramFeatures(
            dayHist(*(listOf(200 to 4000) + (230..500 step 30).map { it to 1000 }).toTypedArray()),
            nowSec,
        )!!
        val line = bhLine(recent7 = 0, hist = hist)
        // 前史行三段：爆发巅峰 30 天 4000 条 · 活跃寿命 300 天 · 停更至今 200 天
        assertTrue(line.contains("前史"))
        assertTrue(line.contains("4000 条"))
        assertTrue(line.contains("活跃寿命 300 天"))
        assertTrue(line.contains("停更至今 200 天"))
        // 缺据（hist=null 未宣判/点查失败）→ 前史行整条不出（绝不编造）
        val noHist = bhLine(recent7 = 0, hist = null)
        assertTrue(!noHist.contains("前史"))
        // 活着（deadDays=0）→ 停至今段省略（活着不冒充停更）
        val alive = TasteAstroPhysics.publishHistogramFeatures(
            dayHist(*(listOf(0 to 400) + (30..300 step 30).map { it to 50 }).toTypedArray()),
            nowSec,
        )!!
        val aliveLine = bhLine(recent7 = 0, hist = alive)
        assertTrue(aliveLine.contains("前史"))
        assertTrue(!aliveLine.contains("停更至今"))
    }

    @Test
    fun `信息卡-hist只影响黑洞行（中子星行不注入前史）`() {
        val hist = TasteAstroPhysics.publishHistogramFeatures(
            dayHist(*(listOf(200 to 450) + (230..500 step 30).map { it to 50 }).toTypedArray()),
            nowSec,
        )!!
        val ns = TasteAstroPhysics.factLine(
            CelestialKind.NEUTRON_STAR, 950, 0, -1, 200L, 900L, 2500f, null, 0, hist,
        )
        // 中子星 core 行已含「爆发骤停至今」（R317 core）——hist 参不改变中子星行为（默认参零破坏）
        assertTrue(ns.contains("中子星"))
        assertTrue(ns.contains("200 天"))
        val nsNoHist = TasteAstroPhysics.factLine(
            CelestialKind.NEUTRON_STAR, 950, 0, -1, 200L, 900L, 2500f, null, 0, null,
        )
        assertEquals(nsNoHist, ns) // hist 注入对非黑洞分支零影响（API 只加不改锚）
    }
}
