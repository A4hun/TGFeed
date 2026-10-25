package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteGalaxyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R322 TastePosterV2 数据组装纯函数单测（M 章⑤单测锚——r315 设计「V2 数据组装
 * 纯函数（kindCounts 到徽章行、genesisSpan 到光年行、缺据诚实缺省——无宣判天体
 * =徽章行退化为恒星计数）」的落位）。
 *
 * 重投影口径不自建单测：V2 复用 [dev.tgfeed.core.tastemap.TasteSpace.project]
 * （TasteSpaceTest 既有锚——TasteVoyagePoster 同款单测锚模式）。
 * 全部构造数据（零真实频道名/tag——脱敏红线）。
 */
class TastePosterV2Test {

    private val DAY = 86_400_000L

    // ---------------- 光年数据行（lightYearLineOf） ----------------

    @Test
    fun `光年行_缺据无证据返回null`() {
        // 无首见证据（GenesisSpan 0/0=maxMs<=minMs=valid false）
        assertNull(TastePosterV2.lightYearLineOf(TasteFlight.GenesisSpan(0L, 0L), 1_700_000_000_000L))
        assertNull(TastePosterV2.lightYearLineOf(TasteFlight.GenesisSpan(500L, 500L), 1_700_000_000_000L))
    }

    @Test
    fun `光年行_未来时间戳数据异常返回null`() {
        val now = 1_700_000_000_000L
        assertNull(TastePosterV2.lightYearLineOf(TasteFlight.GenesisSpan(now + DAY, now + 2 * DAY), now))
    }

    @Test
    fun `光年行_跨度光行与最老星天数口径`() {
        val now = 1_700_000_000_000L
        // 量程=98 天（最老 100 天前到最新 2 天前）→ 光行 98 光日；最老星 100 天前
        val span = TasteFlight.GenesisSpan(now - 100 * DAY, now - 2 * DAY)
        val line = TastePosterV2.lightYearLineOf(span, now)
        assertNotNull(line)
        assertTrue(line!!.startsWith("宇宙跨度"))
        assertTrue(line.contains("光行 98 光日"))
        assertTrue(line.contains("最老的星诞生 100 天前"))
    }

    @Test
    fun `光年行_光年档换算`() {
        val now = 1_700_000_000_000L
        // 量程=730 天（2 回归年）→ 2.0 光年；最老星 800 天前
        val span = TasteFlight.GenesisSpan(now - 800 * DAY, now - 70 * DAY)
        val line = TastePosterV2.lightYearLineOf(span, now)!!
        assertTrue(line.contains("2.0 光年"))
        assertTrue(line.contains("800 天前"))
    }

    @Test
    fun `光年行_新库最老星今天诞生`() {
        val now = 1_700_000_000_000L
        // 最老星 5 小时前首见=不足一天 → 「今天诞生」诚实口径
        val span = TasteFlight.GenesisSpan(now - 5 * 3_600_000L, now - 1 * 3_600_000L)
        val line = TastePosterV2.lightYearLineOf(span, now)!!
        assertTrue(line.contains("最老的星今天诞生"))
        assertTrue(line.contains("4 光时"))
    }

    @Test
    fun `光年行_确定性同输入同输出`() {
        val now = 1_700_000_000_000L
        val span = TasteFlight.GenesisSpan(now - 365 * DAY, now - 10 * DAY)
        assertEquals(
            TastePosterV2.lightYearLineOf(span, now),
            TastePosterV2.lightYearLineOf(span, now),
        )
    }

    // ---------------- 天体谱徽章行（badgeChipsOf） ----------------

    @Test
    fun `徽章行_无特殊天体退化为恒星计数`() {
        // 只有 STAR（六态全缺省的小库）=单徽章恒星计数（M 章⑤字面锚）
        val chips = TastePosterV2.badgeChipsOf(mapOf(CelestialKind.STAR to 500), 432)
        assertEquals(1, chips.size)
        assertEquals("恒星", chips[0].label)
        assertEquals(500, chips[0].count)
        assertEquals(CelestialKind.STAR, chips[0].kind)
    }

    @Test
    fun `徽章行_空表装配失败态回落fallback计数`() {
        val chips = TastePosterV2.badgeChipsOf(emptyMap(), 432)
        assertEquals(1, chips.size)
        assertEquals("恒星", chips[0].label)
        assertEquals(432, chips[0].count)
    }

    @Test
    fun `徽章行_STAR排除且枚举序确定`() {
        val kc = mapOf(
            CelestialKind.STAR to 560,
            CelestialKind.RED_GIANT to 12,
            CelestialKind.BLACK_HOLE to 4,
            CelestialKind.WHITE_DWARF to 8,
            CelestialKind.PULSAR to 3,
        )
        val chips = TastePosterV2.badgeChipsOf(kc, 0)
        // 序=枚举 ordinal（R223b 布局日志同口径）：RED_GIANT(2)<WHITE_DWARF(3)<
        // PULSAR(5)<BLACK_HOLE(8)；STAR 缺省态不占徽章位
        assertEquals(
            listOf(
                CelestialKind.RED_GIANT,
                CelestialKind.WHITE_DWARF,
                CelestialKind.PULSAR,
                CelestialKind.BLACK_HOLE,
            ),
            chips.map { it.kind },
        )
        assertEquals(12, chips[0].count)
        assertEquals("黑洞", chips[3].label)
        assertEquals(4, chips[3].count)
    }

    @Test
    fun `徽章行_零计数过滤`() {
        val kc = mapOf(
            CelestialKind.STAR to 10,
            CelestialKind.SUPERNOVA to 0,
            CelestialKind.PROTOSTAR to 7,
        )
        val chips = TastePosterV2.badgeChipsOf(kc, 0)
        assertEquals(listOf(CelestialKind.PROTOSTAR), chips.map { it.kind })
    }

    @Test
    fun `徽章行_R317三新值前向兼容`() {
        // R317 枚举已扩而宣判接线未落（kindCounts 现役不含三值）——纯函数天然
        // 前向兼容：三值出现时自动成徽章（零值过滤照常）
        val kc = mapOf(
            CelestialKind.NEUTRON_STAR to 1,
            CelestialKind.QUARK_STAR to 0,
            CelestialKind.WHITE_HOLE to 2,
        )
        val chips = TastePosterV2.badgeChipsOf(kc, 0)
        assertEquals(listOf(CelestialKind.NEUTRON_STAR, CelestialKind.WHITE_HOLE), chips.map { it.kind })
        assertEquals("中子星", chips[0].label)
        assertEquals("白洞", chips[1].label)
    }

    @Test
    fun `徽章行_标签labelOf单一出处全枚举对位`() {
        // label 全走 TasteGalaxyClassifier.labelOf（单一出处）——全枚举非空对位
        val kc = CelestialKind.entries.associateWith { 1 }
        val chips = TastePosterV2.badgeChipsOf(kc, 0)
        assertEquals(CelestialKind.entries.size - 1, chips.size) // STAR 排除
        for (chip in chips) {
            assertEquals(TasteGalaxyClassifier.labelOf(chip.kind), chip.label)
            assertTrue(chip.label.isNotBlank())
        }
    }
}
