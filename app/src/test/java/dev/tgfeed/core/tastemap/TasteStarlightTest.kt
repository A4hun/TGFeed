package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R302 星辉等级单测（design-rpg-loop 终审稿锚定——TasteAchievements 23 例同款纪律）：
 * 升级曲线边界/记账幂等（重算即迁移）/目击判定窗/雾界有界单调+0.25 封顶/
 * 勋章判据矩阵/账本落盘回路+损坏归零/罗盘八方/解锁表序/飞船地板取高。
 */
class TasteStarlightTest {

    private fun tmpFile(): File = createTempFile(prefix = "starlight", suffix = ".json")

    // ==================== 升级曲线（100×n^1.5 纯函数） ====================

    @Test
    fun `曲线-任务书锚点值`() {
        assertEquals(100L, TasteStarlight.needForLevel(1)) // Lv1→2=100（点 10 颗星）
        assertEquals(283L, TasteStarlight.needForLevel(2)) // 100×2^1.5≈282.8
        assertEquals(2700L, TasteStarlight.needForLevel(9)) // Lv9→10≈3000 量级
        assertEquals(800L, TasteStarlight.needForLevel(4)) // 100×8
    }

    @Test
    fun `曲线-累计门槛与等级互逆`() {
        assertEquals(0L, TasteStarlight.cumNeed(1))
        assertEquals(100L, TasteStarlight.cumNeed(2))
        // levelOf(cumNeed(L))==L；差 1 点降一级（边界紧闭）
        for (lv in 1..12) {
            assertEquals(lv, TasteStarlight.levelOf(TasteStarlight.cumNeed(lv)))
            assertEquals(lv, TasteStarlight.levelOf(TasteStarlight.cumNeed(lv + 1) - 1))
        }
        assertEquals(1, TasteStarlight.levelOf(0))
        assertEquals(1, TasteStarlight.levelOf(99))
        assertEquals(2, TasteStarlight.levelOf(100))
    }

    @Test
    fun `曲线-进度件`() {
        val p = TasteStarlight.progressOf(150) // Lv2 已攒 50/283
        assertEquals(2, p.level)
        assertEquals(50L, p.into)
        assertEquals(283L, p.need)
        assertTrue(p.progress01 > 0.17f && p.progress01 < 0.18f)
        assertTrue(p.gap01 > 0.82f) // 差距大=罗盘不亮
    }

    @Test
    fun `曲线-单调有界（防滑坡锚）`() {
        var prev = 0L
        for (lv in 1..40) {
            val n = TasteStarlight.needForLevel(lv)
            assertTrue("need 单调", n > prev)
            prev = n
        }
    }

    // ==================== 总额重算（重算即迁移） ====================

    @Test
    fun `总额-分项加权和`() {
        val ledger = TasteStarlight.Ledger(
            planets = setOf("1:a", "2:b"),
            cells = setOf(3, 17, 42),
            witness = setOf("1000|1|55", "2000|0|0"),
            milestones = setOf(10, 50),
        )
        // 4 点亮×10 + 2 行星×3 + 3 星域×25 + 7 虫洞×5 + 2 目击×15 + 2 红包×50
        val total = TasteStarlight.totalOf(4, 7, ledger)
        assertEquals(40 + 6 + 75 + 35 + 30 + 100, total)
    }

    @Test
    fun `记账-幂等（重算即迁移的语义锚）`() {
        var l = TasteStarlight.Ledger.EMPTY
        val (l1, added1) = TasteStarlight.addCell(l, 7)
        assertTrue(added1)
        val (l2, added2) = TasteStarlight.addCell(l1, 7) // 同格不重复奖
        assertFalse(added2)
        assertEquals(l1.cells, l2.cells)
        val (p1, pa1) = TasteStarlight.addPlanet(l2, 100L, "音乐")
        assertTrue(pa1)
        val (p2, pa2) = TasteStarlight.addPlanet(p1, 100L, "音乐")
        assertFalse(pa2)
        // 同键不同写法归一（URL 编码键）
        val (p3, pa3) = TasteStarlight.addPlanet(p2, 100L, "音乐")
        assertFalse(pa3)
        assertEquals(p2.planets, p3.planets)
        val (w1, wa1) = TasteStarlight.addWitness(p3, 1234L, UniverseDirector.EventKind.NOVA, 55L)
        assertTrue(wa1)
        val (_, wa2) = TasteStarlight.addWitness(w1, 1234L, UniverseDirector.EventKind.NOVA, 55L)
        assertFalse(wa2) // 同事件实例不重复奖
    }

    @Test
    fun `里程碑红包-跨档即领每档一次`() {
        val (l1, new1) = TasteStarlight.claimMilestones(TasteStarlight.Ledger.EMPTY, 9)
        assertTrue(new1.isEmpty())
        assertTrue(l1.milestones.isEmpty())
        val (l2, new2) = TasteStarlight.claimMilestones(l1, 55) // 跨 10/50 两档一次领
        assertEquals(listOf(10, 50), new2)
        val (l3, new3) = TasteStarlight.claimMilestones(l2, 400) // 再跨 100/300
        assertEquals(listOf(100, 300), new3)
        val (_, new4) = TasteStarlight.claimMilestones(l3, 500) // 无新档不重发
        assertTrue(new4.isEmpty())
    }

    // ==================== 目击判定窗 ====================

    @Test
    fun `目击-井深比窗（终审稿口径 15）`() {
        assertTrue(TasteStarlight.witnessedByWell(0f))
        assertTrue(TasteStarlight.witnessedByWell(14.9f))
        assertFalse(TasteStarlight.witnessedByWell(15f))
        assertFalse(TasteStarlight.witnessedByWell(99f))
    }

    @Test
    fun `目击图鉴-分桶计数`() {
        var l = TasteStarlight.Ledger.EMPTY
        l = TasteStarlight.addWitness(l, 1L, UniverseDirector.EventKind.NOVA, 55L).first
        l = TasteStarlight.addWitness(l, 2L, UniverseDirector.EventKind.NOVA, 56L).first
        l = TasteStarlight.addWitness(l, 3L, UniverseDirector.EventKind.SUPERNOVA, 57L).first
        l = TasteStarlight.addWitness(l, 4L, UniverseDirector.EventKind.COMET, 0L).first
        val c = TasteStarlight.witnessCounts(l)
        assertEquals(2, c.nova)
        assertEquals(1, c.supernova)
        assertEquals(1, c.comet)
    }

    // ==================== 雾界（有界单调 + 封顶红线） ====================

    @Test
    fun `雾界-单调有界`() {
        var prev = 0f
        for (lv in 1..30) {
            val r = TasteStarlight.mistReachOf(lv)
            assertTrue("雾界单调", r >= prev)
            assertTrue("雾界有界", r <= TasteStarlight.MIST_REACH_MAX)
            prev = r
        }
        assertEquals(TasteStarlight.MIST_REACH_BASE, TasteStarlight.mistReachOf(1))
    }

    @Test
    fun `迷雾-降亮封顶且界内零雾`() {
        val reach = 1000f
        assertEquals(0f, TasteStarlight.mistDimOf(500f, reach))
        assertEquals(0f, TasteStarlight.mistDimOf(1000f, reach))
        val mid = TasteStarlight.mistDimOf(1000f + TasteStarlight.MIST_FADE / 2f, reach)
        assertTrue(mid > 0f && mid < TasteStarlight.MIST_DIM_MAX)
        assertEquals(TasteStarlight.MIST_DIM_MAX, TasteStarlight.mistDimOf(99999f, reach))
        // 红线成文：降亮 ≤0.20（终审稿封顶 0.25 内）——绝不锁内容
        assertTrue(TasteStarlight.MIST_DIM_MAX <= 0.25f)
        assertEquals(0f, TasteStarlight.mistDimOf(500f, 0f)) // 无雾界=零雾
    }

    // ==================== 解锁表（八级体验型） ====================

    @Test
    fun `解锁表-序与门禁`() {
        assertEquals(7, TasteStarlight.UNLOCKS.size) // Lv2~Lv8 七档解锁（首批八级=Lv1~8）
        // 等级严格升序
        assertEquals(TasteStarlight.UNLOCKS.map { it.level }, TasteStarlight.UNLOCKS.map { it.level }.sorted())
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), TasteStarlight.UNLOCKS.map { it.level })
        assertFalse(TasteStarlight.isUnlocked(1, "compass"))
        assertTrue(TasteStarlight.isUnlocked(2, "compass"))
        assertTrue(TasteStarlight.isUnlocked(8, "legend_coating"))
        assertEquals("compass", TasteStarlight.nextUnlock(1)?.id)
        assertEquals("porthole", TasteStarlight.nextUnlock(6)?.id)
        assertNull(TasteStarlight.nextUnlock(8))
        assertEquals(2, TasteStarlight.unlocksAt(3).size)
    }

    @Test
    fun `远视-Lv5 起 1200`() {
        assertEquals(TasteNebula.DISCOVER_NEAR_START, TasteStarlight.discoverNearStartOf(1))
        assertEquals(TasteNebula.DISCOVER_NEAR_START, TasteStarlight.discoverNearStartOf(4))
        assertEquals(1200f, TasteStarlight.discoverNearStartOf(5))
        assertEquals(1200f, TasteStarlight.discoverNearStartOf(8))
    }

    @Test
    fun `飞船地板-取高不回收`() {
        // 等级地板：Lv3 波纹 / Lv8 彩虹
        assertEquals(
            TasteAchievements.ShipStyle.MICRO_TRAIL,
            TasteStarlight.shipStyleFloor(1),
        )
        assertEquals(TasteAchievements.ShipStyle.WARP_RIPPLE, TasteStarlight.shipStyleFloor(3))
        assertEquals(TasteAchievements.ShipStyle.RAINBOW_STORM, TasteStarlight.shipStyleFloor(8))
        // 取高：等阶传奇高于 Lv3 地板=等阶原样（不回收）
        assertEquals(
            TasteAchievements.ShipStyle.RAINBOW_STORM,
            TasteStarlight.effectiveShipStyle(TasteAchievements.ShipStyle.RAINBOW_STORM, 3),
        )
        // 青铜 +Lv3=波纹预尝
        assertEquals(
            TasteAchievements.ShipStyle.WARP_RIPPLE,
            TasteStarlight.effectiveShipStyle(TasteAchievements.ShipStyle.MICRO_TRAIL, 5),
        )
    }

    // ==================== 罗盘（八方方位词） ====================

    @Test
    fun `罗盘-八方与零向量`() {
        assertEquals("此处", TasteStarlight.compassLabel(0f, 0f))
        assertEquals("东", TasteStarlight.compassLabel(100f, 0f))
        assertEquals("西", TasteStarlight.compassLabel(-100f, 0f))
        assertEquals("南", TasteStarlight.compassLabel(0f, 100f))
        assertEquals("北", TasteStarlight.compassLabel(0f, -100f))
        assertEquals("东南", TasteStarlight.compassLabel(100f, 100f))
        assertEquals("西南", TasteStarlight.compassLabel(-100f, 100f))
        assertEquals("西北", TasteStarlight.compassLabel(-100f, -100f))
        assertEquals("东北", TasteStarlight.compassLabel(100f, -100f))
    }

    // ==================== 编年史勋章矩阵 ====================

    private fun chronInput(
        lit: Int = 0, jumps: Int = 0, nova: Int = 0, sup: Int = 0,
        comet: Int = 0, cells: Int = 0, ratio: Float = 0f,
    ) = TasteStarlight.ChronicleInput(lit, jumps, nova, sup, comet, cells, ratio)

    @Test
    fun `编年史-七枚键与全零判据`() {
        val medals = TasteStarlight.chronicleMedals(chronInput())
        assertEquals(7, medals.size)
        assertEquals(
            listOf(
                TasteStarlight.CHRON_FIRST_LIGHT, TasteStarlight.CHRON_VOYAGER,
                TasteStarlight.CHRON_NOVA, TasteStarlight.CHRON_SUPERNOVA,
                TasteStarlight.CHRON_COMET, TasteStarlight.CHRON_REGIONS,
                TasteStarlight.CHRON_FULL,
            ),
            medals.map { it.key },
        )
        assertTrue(medals.all { !it.met })
    }

    @Test
    fun `编年史-判据矩阵`() {
        fun met(medals: List<TasteAchievements.Medal>, key: String) = medals.first { it.key == key }.met
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(lit = 1)), TasteStarlight.CHRON_FIRST_LIGHT))
        assertFalse(met(TasteStarlight.chronicleMedals(chronInput(lit = 0)), TasteStarlight.CHRON_FIRST_LIGHT))
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(jumps = 1)), TasteStarlight.CHRON_VOYAGER))
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(nova = 1)), TasteStarlight.CHRON_NOVA))
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(sup = 1)), TasteStarlight.CHRON_SUPERNOVA))
        assertFalse(met(TasteStarlight.chronicleMedals(chronInput(comet = 4)), TasteStarlight.CHRON_COMET))
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(comet = 5)), TasteStarlight.CHRON_COMET))
        assertFalse(met(TasteStarlight.chronicleMedals(chronInput(cells = 4)), TasteStarlight.CHRON_REGIONS))
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(cells = 5)), TasteStarlight.CHRON_REGIONS))
        assertFalse(met(TasteStarlight.chronicleMedals(chronInput(ratio = 0.999f)), TasteStarlight.CHRON_FULL))
        assertTrue(met(TasteStarlight.chronicleMedals(chronInput(ratio = 1f)), TasteStarlight.CHRON_FULL))
    }

    @Test
    fun `最近的下一枚-进度最高未达成`() {
        val medals = TasteStarlight.chronicleMedals(chronInput(lit = 3, comet = 4))
        // 首光已达成；彗星捕手 4/5=0.8 最高 → 最近下一枚
        assertEquals(TasteStarlight.CHRON_COMET, TasteStarlight.nextChronicleKey(medals))
        // 全达成=null
        val done = TasteStarlight.chronicleMedals(
            chronInput(lit = 9, jumps = 2, nova = 1, sup = 1, comet = 7, cells = 6, ratio = 1f),
        )
        assertNull(TasteStarlight.nextChronicleKey(done))
    }

    // ==================== 账本 IO（落盘回路/原子写/损坏归零） ====================

    @Test
    fun `账本-缺失文件=空账诚实缺省`() {
        val f = tmpFile()
        f.delete()
        assertEquals(TasteStarlight.Ledger.EMPTY.planets, TasteStarlight.loadLedger(f).planets)
    }

    @Test
    fun `账本-写读一致与特殊字符`() {
        val f = tmpFile()
        var l = TasteStarlight.Ledger.EMPTY
        l = TasteStarlight.addPlanet(l, 100L, "音乐,盛典\"x\"").first // 逗号/引号/中文全活
        l = TasteStarlight.addCell(l, 7).first
        l = TasteStarlight.addWitness(l, 1234L, UniverseDirector.EventKind.SUPERNOVA, 55L).first
        l = TasteStarlight.claimMilestones(l, 60).first
        TasteStarlight.saveLedger(f, l)
        val back = TasteStarlight.loadLedger(f)
        assertEquals(l.planets, back.planets)
        assertEquals(l.cells, back.cells)
        assertEquals(l.witness, back.witness)
        assertEquals(l.milestones, back.milestones)
        // 序列化是真实 JSON 形（花括号/字段名锚）
        val text = f.readText()
        assertTrue(text.startsWith("{\"v\":1"))
        assertTrue(text.contains("\"witness\""))
    }

    @Test
    fun `账本-损坏与半写归零`() {
        val f = tmpFile()
        f.writeText("not json at all")
        assertEquals(TasteStarlight.Ledger.EMPTY.witness, TasteStarlight.loadLedger(f).witness)
        f.writeText("{\"v\":1,\"planets\":[\"1:abc\",\"2:de") // 半写截断
        val l = TasteStarlight.loadLedger(f) // 绝不抛
        assertNotNull(l)
        f.writeText("{}")
        assertEquals(TasteStarlight.Ledger.EMPTY.cells, TasteStarlight.loadLedger(f).cells)
    }

    @Test
    fun `账本-字符串内括号不劈（解析器强度）`() {
        // planets 值含方括号——arrayBody 字符串感知不把值内 ] 当数组尾
        val l = TasteStarlight.parseLedger("{\"v\":1,\"planets\":[\"1:a]b\"],\"cells\":[3,17]}")
        assertEquals(setOf("1:a]b"), l.planets)
        assertEquals(setOf(3, 17), l.cells)
    }

    // ==================== 三源重算（迁移哲学锚） ====================

    @Test
    fun `三源重算-同账同额（重算即迁移）`() {
        val dir = createTempFile(prefix = "galaxy", suffix = "").apply { delete(); mkdirs() }
        // 点亮 12 颗 + 虫洞 3 次 + 首踏 1 格 + 目击 1 次
        TasteDiscoveryLog.addAll(File(dir, TasteDiscoveryLog.FILE_NAME), (1L..12L).toList(), nowMs = 1000L)
        repeat(3) { TasteVoyageLog.bumpWormhole(File(dir, TasteVoyageLog.FILE_NAME)) }
        var ledger = TasteStarlight.Ledger.EMPTY
        ledger = TasteStarlight.addCell(ledger, 5).first
        ledger = TasteStarlight.addWitness(ledger, 2000L, UniverseDirector.EventKind.NOVA, 7L).first
        val (lm, newMs) = TasteStarlight.claimMilestones(ledger, 12)
        assertEquals(listOf(10), newMs)
        TasteStarlight.saveLedger(File(dir, TasteStarlight.FILE_NAME), lm)
        val book = TasteStarlight.loadProgress(dir)
        assertEquals(120 + 15 + 25 + 15 + 50, book.total) // 225
        assertEquals(2, book.level) // 225 ≥ cumNeed(2)=100，<cumNeed(3)=383
        assertEquals(12, book.litCount)
        assertEquals(3, book.jumps)
        // 重算即迁移：删掉增量账=只剩老账星辉（绝不抛，诚实回退）
        File(dir, TasteStarlight.FILE_NAME).delete()
        val book2 = TasteStarlight.loadProgress(dir)
        assertEquals(120 + 15, book2.total)
        assertEquals(2, book2.level)
    }

    @Test
    fun `Book-罗盘档与换算`() {
        val dir = createTempFile(prefix = "galaxy", suffix = "").apply { delete(); mkdirs() }
        // Lv1 差 100；点亮 9 颗=90——差 10 ≤20% 但 Lv2 未解锁罗盘
        TasteDiscoveryLog.addAll(File(dir, TasteDiscoveryLog.FILE_NAME), (1L..9L).toList(), nowMs = 1L)
        val b1 = TasteStarlight.loadProgress(dir)
        assertEquals(1, b1.level)
        assertFalse(b1.compassLit) // Lv2 才解锁罗盘
        assertEquals(1, b1.lightsToNext) // 差 10=再点亮 1 颗
        // 点亮 30 颗=300 → Lv2（300-100=200/283≈0.71，差≤20%? 0.29>0.2 不亮）
        TasteDiscoveryLog.addAll(File(dir, TasteDiscoveryLog.FILE_NAME), (10L..30L).toList(), nowMs = 2L)
        val b2 = TasteStarlight.loadProgress(dir)
        assertEquals(2, b2.level)
        assertFalse(b2.compassLit)
        // 点亮 33 颗=330 → Lv2 进度 230/283≈0.81 → 差 0.19≤0.2 亮
        TasteDiscoveryLog.addAll(File(dir, TasteDiscoveryLog.FILE_NAME), (31L..33L).toList(), nowMs = 3L)
        val b3 = TasteStarlight.loadProgress(dir)
        assertEquals(2, b3.level)
        assertTrue(b3.compassLit)
        assertEquals(6, b3.lightsToNext) // 差 53≈再点亮 6 颗
    }
}
