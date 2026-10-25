package dev.tgfeed.core.tastemap

import dev.tgfeed.core.tastemap.TasteGalaxyClassifier.ChannelEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R223b 天体分档器单测：六态判据矩阵（阈值边界）/优先级（瞬态>终态>稳态>缺省）/
 * 降级兼容（任何缺据=恒星诚实缺省）/状态迁移（纯函数重算即迁移——红巨星随
 * 时间推进到白矮星）/体量分位（对数口径/零条与单频道池退化）。
 */
class TasteGalaxyClassifierTest {

    private val nowMs = 1_700_000_000_000L // 固定时钟：判据只看相对天数

    /** 常规活跃频道（最近活动 10 天前、中位体量、无爆发） */
    private fun active(
        items: Int = 500,
        lastActivityDays: Long = 10,
        recent7: Int = -1,
        prev7: Int = -1,
        firstSeenDays: Long = 400,
        rank01: Float = 0.5f,
    ) = ChannelEvidence(
        items = items,
        lastActivitySec = nowMs / 1000L - lastActivityDays * 86_400L,
        recent7 = recent7,
        prev7 = prev7,
        firstSeenMs = nowMs - firstSeenDays * 86_400_000L,
        volumeRank01 = rank01,
    )

    // ==================== 六态判据矩阵 ====================

    @Test
    fun `恒星-活跃中位体量走主序`() {
        assertEquals(CelestialKind.STAR, TasteGalaxyClassifier.classifyChannel(active(), nowMs))
    }

    @Test
    fun `原恒星-首见90天内`() {
        assertEquals(CelestialKind.PROTOSTAR, TasteGalaxyClassifier.classifyChannel(active(firstSeenDays = 89), nowMs))
    }

    @Test
    fun `原恒星-首见恰好90天不算新生（严格小于）`() {
        assertEquals(CelestialKind.STAR, TasteGalaxyClassifier.classifyChannel(active(firstSeenDays = 90), nowMs))
    }

    @Test
    fun `红巨星-巨存量且低活跃`() {
        assertEquals(
            CelestialKind.RED_GIANT,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 100, rank01 = 0.85f), nowMs),
        )
    }

    @Test
    fun `红巨星-分位恰好top30边界命中`() {
        assertEquals(
            CelestialKind.RED_GIANT,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 61, rank01 = 0.70f), nowMs),
        )
    }

    @Test
    fun `红巨星-分位不足70走恒星`() {
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 100, rank01 = 0.69f), nowMs),
        )
    }

    @Test
    fun `红巨星-活跃仅61天即低活跃（严格大于60）`() {
        assertEquals(
            CelestialKind.RED_GIANT,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 61, rank01 = 0.9f), nowMs),
        )
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 60, rank01 = 0.9f), nowMs),
        )
    }

    @Test
    fun `白矮星-断更超180天且有条目`() {
        assertEquals(
            CelestialKind.WHITE_DWARF,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 181), nowMs),
        )
    }

    @Test
    fun `白矮星-断更恰180天不算（严格大于）`() {
        assertEquals(
            CelestialKind.RED_GIANT, // 180 天且高分位 → 红巨星（死亡档未达）
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 180, rank01 = 0.9f), nowMs),
        )
    }

    @Test
    fun `白矮星-零条目不判死（无索引证据不冒充）`() {
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(active(items = 0, lastActivityDays = 400), nowMs),
        )
    }

    @Test
    fun `超新星-近7天超2倍前7天`() {
        assertEquals(
            CelestialKind.SUPERNOVA,
            TasteGalaxyClassifier.classifyChannel(active(recent7 = 6, prev7 = 2), nowMs),
        )
    }

    @Test
    fun `超新星-恰2倍不算（严格大于）`() {
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(active(recent7 = 4, prev7 = 2), nowMs),
        )
    }

    @Test
    fun `超新星-样本量门槛防1v0噪声`() {
        // recent7=2 不足门槛：偶发更新不当爆发
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(active(recent7 = 2, prev7 = 0), nowMs),
        )
        // recent7=3 过门槛且 3 > 2×1
        assertEquals(
            CelestialKind.SUPERNOVA,
            TasteGalaxyClassifier.classifyChannel(active(recent7 = 3, prev7 = 1), nowMs),
        )
    }

    @Test
    fun `超新星-金矿缺失哨兵不误判`() {
        // recent7/prev7 = -1（无金矿档案）：爆发档诚实跳过，主序收场
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(active(recent7 = -1, prev7 = -1), nowMs),
        )
    }

    // ==================== 优先级与降级 ====================

    @Test
    fun `优先级-爆发瞬态盖过死亡终态`() {
        // 纯函数允许矛盾输入（实际数据不出现）：爆发先判
        assertEquals(
            CelestialKind.SUPERNOVA,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 200, recent7 = 6, prev7 = 2), nowMs),
        )
    }

    @Test
    fun `优先级-死亡终态先于红巨星`() {
        // 断更 400 天 + top 体量：白矮星（已死不冒充膨胀中）
        assertEquals(
            CelestialKind.WHITE_DWARF,
            TasteGalaxyClassifier.classifyChannel(active(lastActivityDays = 400, rank01 = 0.99f), nowMs),
        )
    }

    @Test
    fun `优先级-红巨星先于原恒星（体量信息更稀缺）`() {
        // 首见 80 天 + 断更 70 天 + 巨存量：红巨星（判据矩阵文档化的取舍）
        assertEquals(
            CelestialKind.RED_GIANT,
            TasteGalaxyClassifier.classifyChannel(active(firstSeenDays = 80, lastActivityDays = 70, rank01 = 0.9f), nowMs),
        )
    }

    @Test
    fun `降级兼容-全缺据按恒星渲染（绝不冒充特殊天体）`() {
        val e = ChannelEvidence(items = 0, lastActivitySec = 0L, recent7 = -1, prev7 = -1, firstSeenMs = 0L, volumeRank01 = 0f)
        assertEquals(CelestialKind.STAR, TasteGalaxyClassifier.classifyChannel(e, nowMs))
    }

    @Test
    fun `降级兼容-有索引无金矿活动证据走新生或主序不判死`() {
        // lastActivity=0（未知）：白矮/红巨星都不判（没有证据不冒充死亡）
        assertEquals(
            CelestialKind.PROTOSTAR,
            TasteGalaxyClassifier.classifyChannel(
                ChannelEvidence(500, 0L, -1, -1, nowMs - 30L * 86_400_000L, 0.9f),
                nowMs,
            ),
        )
        assertEquals(
            CelestialKind.STAR,
            TasteGalaxyClassifier.classifyChannel(
                ChannelEvidence(500, 0L, -1, -1, nowMs - 400L * 86_400_000L, 0.9f),
                nowMs,
            ),
        )
    }

    // ==================== 状态迁移（星系活着） ====================

    @Test
    fun `迁移-红巨星随时间推进到白矮星（重算即迁移）`() {
        val e = ChannelEvidence(
            items = 40_000, lastActivitySec = nowMs / 1000L - 100L * 86_400L,
            recent7 = -1, prev7 = -1, firstSeenMs = nowMs - 900L * 86_400_000L, volumeRank01 = 0.95f,
        )
        // 100 天断更：红巨星
        assertEquals(CelestialKind.RED_GIANT, TasteGalaxyClassifier.classifyChannel(e, nowMs))
        // 同一频道 81 天后重算（断更 181 天）：白矮星——「星系活着」的静态证据面
        assertEquals(CelestialKind.WHITE_DWARF, TasteGalaxyClassifier.classifyChannel(e, nowMs + 81L * 86_400_000L))
    }

    @Test
    fun `迁移-恒星爆发成超新星再回落`() {
        val base = active(lastActivityDays = 3, rank01 = 0.5f)
        assertEquals(CelestialKind.STAR, TasteGalaxyClassifier.classifyChannel(base, nowMs))
        val burst = base.copy(recent7 = 9, prev7 = 3)
        assertEquals(CelestialKind.SUPERNOVA, TasteGalaxyClassifier.classifyChannel(burst, nowMs))
        // 下一次快照（一周后）增速对照翻面：回主序
        val calm = burst.copy(recent7 = 2, prev7 = 9)
        assertEquals(CelestialKind.STAR, TasteGalaxyClassifier.classifyChannel(calm, nowMs))
    }

    // ==================== 体量分位 ====================

    @Test
    fun `分位-对数口径排名`() {
        // 4 频道池：1/10/100/10000 条——对数压重尾后 100 排第 2/3 位
        val all = intArrayOf(1, 10, 100, 10_000)
        assertEquals(1.0f, TasteGalaxyClassifier.volumeRankOf(10_000, all), 1e-6f)
        assertEquals(0.0f, TasteGalaxyClassifier.volumeRankOf(1, all), 1e-6f)
        assertTrue(TasteGalaxyClassifier.volumeRankOf(100, all) in 0.6f..0.7f)
    }

    @Test
    fun `分位-零条目与单频道池退化到0`() {
        assertEquals(0f, TasteGalaxyClassifier.volumeRankOf(0, intArrayOf(5, 10)))
        assertEquals(0f, TasteGalaxyClassifier.volumeRankOf(7, intArrayOf(7)))
        assertEquals(0f, TasteGalaxyClassifier.volumeRankOf(7, intArrayOf(0)))
    }

    @Test
    fun `分位-条数相同分位并列一致`() {
        val all = intArrayOf(100, 100, 100, 100)
        // 4 个同体量：严格小于计数=0 → 分位 0（谁也不冒充更大）
        assertEquals(0f, TasteGalaxyClassifier.volumeRankOf(100, all), 1e-6f)
    }

    // ==================== 用户语言名单一出处 ====================

    @Test
    fun `标签-十二枚举各有着落`() {
        val labels = CelestialKind.entries.map { TasteGalaxyClassifier.labelOf(it) }
        assertEquals(12, labels.size) // R249：+黑洞（矩阵外布局期晋升）；R317：+中子星/夸克星/白洞（终态宣判矩阵）
        assertEquals(setOf(12), setOf(labels.size)) // 无空名无重名
        assertTrue(
            labels.containsAll(
                listOf("恒星", "原恒星", "红巨星", "白矮星", "超新星", "脉冲星", "星团", "流星", "黑洞", "中子星", "夸克星", "白洞"),
            ),
        )
    }
}
