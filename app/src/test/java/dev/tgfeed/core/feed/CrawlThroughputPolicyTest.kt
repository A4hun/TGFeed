package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * R255 深索引吞吐策略单测（纯函数 [CrawlThroughputPolicy] 锚定）。
 *
 * 覆盖四处判定的语义矩阵与现状锚：
 *  - 档位闸：分档关=一律 LEGACY（历史残留 tier 行不泄漏——16416 实测 348 毒行的病根）；
 *  - 探针 cap 下限：T3 cap=0 的探针死锁解锁；
 *  - 升档复活矩阵：触顶终态的对称收口；
 *  - 配速间隔：预算摊满整小时的口径 + 防御（ops≤0 不配速）；
 *  - 反射锚：CRAWL_BUDGET_WINDOW_MS 与 FeedViewModel 构造 FloodBudget(3_600_000L) 同源。
 */
class CrawlThroughputPolicyTest {

    // ---- W2 档位闸：分档关不读 meta.tier ----

    @Test
    fun `档位闸_分档关_一律LEGACY_不读落库档位`() {
        // 历史残留行（R163 B7 注入 T1/T3、evict 复位 tier=3）在关闭态全部按 LEGACY
        for (persisted in intArrayOf(-1, 0, 1, 2, 3)) {
            assertEquals(
                "persisted=$persisted 分档关必须按 LEGACY",
                IndexTierPolicy.IndexTier.LEGACY,
                CrawlThroughputPolicy.effectiveTier(persisted, tieringEnabled = false),
            )
        }
    }

    @Test
    fun `档位闸_分档开_落库档位原样`() {
        assertEquals(IndexTierPolicy.IndexTier.LEGACY, CrawlThroughputPolicy.effectiveTier(-1, true))
        assertEquals(IndexTierPolicy.IndexTier.T0, CrawlThroughputPolicy.effectiveTier(0, true))
        assertEquals(IndexTierPolicy.IndexTier.T1, CrawlThroughputPolicy.effectiveTier(1, true))
        assertEquals(IndexTierPolicy.IndexTier.T2, CrawlThroughputPolicy.effectiveTier(2, true))
        assertEquals(IndexTierPolicy.IndexTier.T3, CrawlThroughputPolicy.effectiveTier(3, true))
    }

    @Test
    fun `档位闸_分档关的LEGACY参数等于现状锚`() {
        // 纪律①行为零变化：分档关时四路径参数必须与分档前逐项相等
        val cfg = IndexTierPolicy.TieringConfig() // enabled=false 缺省
        val tier = CrawlThroughputPolicy.effectiveTier(3, tieringEnabled = false) // T3 毒行
        assertEquals(IndexTierPolicy.CURRENT_TAIL_MAX_ITEMS, IndexTierPolicy.tierCap(tier, cfg))
        assertEquals(IndexTierPolicy.CURRENT_HEAD_REFRESH_MS, IndexTierPolicy.headRefreshIntervalMs(tier, cfg))
        assertTrue(IndexTierPolicy.deepenAllowed(tier))
        assertFalse(IndexTierPolicy.samplingGateExempt(tier))
    }

    // ---- W3 探针解锁：cap 下限 = 探针页预算 × 页大小 ----

    @Test
    fun `探针cap下限_T3零上限_解锁为探针页数`() {
        // T3 tierCap=0 + 探针 1 页 → 下限 100（探针首次能真正取页算丰度）
        val cap = CrawlThroughputPolicy.itemCapFor(baseCap = 0, probePages = 1, pageSize = 100)
        assertEquals(100, cap)
    }

    @Test
    fun `探针cap下限_T2两千上限_高于探针页_原样`() {
        assertEquals(2000, CrawlThroughputPolicy.itemCapFor(baseCap = 2000, probePages = 1, pageSize = 100))
    }

    @Test
    fun `探针cap下限_多页探针_页数累计封顶`() {
        assertEquals(300, CrawlThroughputPolicy.itemCapFor(baseCap = 0, probePages = 3, pageSize = 100))
    }

    @Test
    fun `探针cap下限_非探针档_原样返回`() {
        // probePages=Int.MAX_VALUE（非探针档）→ baseCap 原样（现状锚，含 0=T3 关档态
        // 由 W2 档位闸拦在前——不会走到这里，防御口径仍锁定）
        assertEquals(50_000, CrawlThroughputPolicy.itemCapFor(50_000, Int.MAX_VALUE, 100))
        assertEquals(0, CrawlThroughputPolicy.itemCapFor(0, Int.MAX_VALUE, 100))
    }

    // ---- W1 配套：升档复活矩阵 ----

    @Test
    fun `升档复活_触顶终态_新档上限大于条数_复活`() {
        assertTrue(CrawlThroughputPolicy.shouldReviveExhausted(wasExhausted = true, itemCount = 15_000, newTierCap = 50_000))
    }

    @Test
    fun `升档复活_未完成态_永不复活`() {
        // 只有触顶终态（exhausted=1）才谈复活；在爬频道复活毫无意义
        assertFalse(CrawlThroughputPolicy.shouldReviveExhausted(wasExhausted = false, itemCount = 0, newTierCap = 50_000))
    }

    @Test
    fun `升档复活_条数已达新档上限_不复活`() {
        // 25k 条 LEGACY→T1（cap 20k）：条数≥新上限 → 保持终态（cap 收紧由爬取期停）
        assertFalse(CrawlThroughputPolicy.shouldReviveExhausted(true, itemCount = 25_000, newTierCap = 20_000))
    }

    @Test
    fun `升档复活_边界_条数恰等于上限_不复活`() {
        assertFalse(CrawlThroughputPolicy.shouldReviveExhausted(true, itemCount = 20_000, newTierCap = 20_000))
    }

    @Test
    fun `升档复活_空频道_复活`() {
        // 0 条 + 任何正上限 → 复活（真到底频道的误复活成本=一轮零请求 DONE，可接受）
        assertTrue(CrawlThroughputPolicy.shouldReviveExhausted(true, itemCount = 0, newTierCap = 2_000))
    }

    // ---- W5 配速间隔 ----

    @Test
    fun `配速间隔_预算6000_摊满一小时`() {
        assertEquals(600L, CrawlThroughputPolicy.rateIntervalMs(3_600_000L, 6_000))
    }

    @Test
    fun `配速间隔_预算2000_旧默认口径`() {
        assertEquals(1_800L, CrawlThroughputPolicy.rateIntervalMs(3_600_000L, 2_000))
    }

    @Test
    fun `配速间隔_零预算哑元实例_不配速`() {
        // OnThisDaySource 卫星只读实例 maxOpsPerHour=0 → Long.MAX_VALUE（防配速卡死）
        assertEquals(Long.MAX_VALUE, CrawlThroughputPolicy.rateIntervalMs(3_600_000L, 0))
        assertEquals(Long.MAX_VALUE, CrawlThroughputPolicy.rateIntervalMs(3_600_000L, -1))
    }

    @Test
    fun `配速间隔_下限600 coerce_最大间隔`() {
        assertEquals(6_000L, CrawlThroughputPolicy.rateIntervalMs(3_600_000L, 600))
    }

    // ---- 反射锚：CRAWL_BUDGET_WINDOW_MS 与 FeedViewModel 构造 FloodBudget 同源 ----

    @Test
    fun `预算窗口常量镜像同步_反射机检`() {
        // DeepSearchIndex.CRAWL_BUDGET_WINDOW_MS（R255 新增）必须与 FeedViewModel 构造
        // FloodBudget(3_600_000L, …) 的窗口值同源同值——两份数字漂移=配速口径错位
        val field = DeepSearchIndex::class.java.getDeclaredField("CRAWL_BUDGET_WINDOW_MS")
        field.isAccessible = true
        assertEquals(3_600_000L, field.getLong(null))
    }

    // ---- R284 时效感知：时效车道判定 ----

    @Test
    fun `时效车道_不自毁_永不紧急`() {
        assertFalse(CrawlThroughputPolicy.isTtlUrgentLane(0))
        assertFalse(CrawlThroughputPolicy.isTtlUrgentLane(-1)) // 负值防御=无时效语义
    }

    @Test
    fun `时效车道_七天含边界_进时效车道`() {
        assertTrue(CrawlThroughputPolicy.isTtlUrgentLane(1)) // 1 秒=最急
        assertTrue(CrawlThroughputPolicy.isTtlUrgentLane(86_400)) // 1 天
        assertTrue(CrawlThroughputPolicy.isTtlUrgentLane(604_800)) // 7 天=用户实锤口径（含边界）
    }

    @Test
    fun `时效车道_超七天_不进车道只批内提权`() {
        assertFalse(CrawlThroughputPolicy.isTtlUrgentLane(604_801))
        assertFalse(CrawlThroughputPolicy.isTtlUrgentLane(2_592_000)) // 30 天
        assertFalse(CrawlThroughputPolicy.isTtlUrgentLane(Int.MAX_VALUE))
    }

    // ---- R284 时效感知：紧迫度（双曲线+新鲜度加成） ----

    @Test
    fun `紧迫度_不自毁_恒零`() {
        assertEquals(0, CrawlThroughputPolicy.ttlUrgencyScore(0, 1_700_000_000L, 1_700_000_100L))
        assertEquals(0, CrawlThroughputPolicy.ttlUrgencyScore(-5, 1_700_000_000L, 1_700_000_100L))
    }

    @Test
    fun `紧迫度_双曲线_越短越紧迫`() {
        val now = 1_700_000_000L
        val fresh = now - 3_600L // 1 小时前=满新鲜度加成 999
        // 基底=604800×1000/ttl：1d=7000 / 7d=1000 / 30d=233 / 90d=77（整除口径）
        assertEquals(7000 + 999, CrawlThroughputPolicy.ttlUrgencyScore(86_400, fresh, now))
        assertEquals(1000 + 999, CrawlThroughputPolicy.ttlUrgencyScore(604_800, fresh, now))
        assertEquals(233 + 999, CrawlThroughputPolicy.ttlUrgencyScore(2_592_000, fresh, now))
        assertEquals(77 + 999, CrawlThroughputPolicy.ttlUrgencyScore(7_776_000, fresh, now))
    }

    @Test
    fun `紧迫度_同新鲜度下_TTL单调递减`() {
        val now = 1_700_000_000L
        val fresh = now - 3_600L
        val ttls = listOf(3_600, 86_400, 604_800, 2_592_000, 7_776_000, 31_536_000)
        val scores = ttls.map { CrawlThroughputPolicy.ttlUrgencyScore(it, fresh, now) }
        for (i in 1 until scores.size) {
            assertTrue("ttl=${ttls[i - 1]}(${scores[i - 1]}) 应 > ttl=${ttls[i]}(${scores[i]})", scores[i - 1] > scores[i])
        }
    }

    @Test
    fun `紧迫度_新鲜度分桶_越新鲜越优先`() {
        val now = 1_700_000_000L
        val ttl = 604_800 // 7 天定住 TTL 维，只看新鲜度
        assertEquals(1000 + 999, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 3_600L, now)) // 1h 内
        assertEquals(1000 + 999, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 86_400L, now)) // 恰 1d 边界
        assertEquals(1000 + 500, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 2 * 86_400L, now)) // 2d
        assertEquals(1000 + 500, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 7 * 86_400L, now)) // 恰 7d 边界
        assertEquals(1000 + 200, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 15 * 86_400L, now)) // 15d
        assertEquals(1000 + 200, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 30 * 86_400L, now)) // 恰 30d 边界
        assertEquals(1000 + 50, CrawlThroughputPolicy.ttlUrgencyScore(ttl, now - 90 * 86_400L, now)) // 更老
        assertEquals(1000, CrawlThroughputPolicy.ttlUrgencyScore(ttl, 0L, now)) // 未知新鲜度=零加成
    }

    @Test
    fun `紧迫度_加成不喧宾夺主_短TTL低新鲜仍高于长TTL满新鲜`() {
        val now = 1_700_000_000L
        val shortStale = CrawlThroughputPolicy.ttlUrgencyScore(86_400, 0L, now) // 1d+零加成=7000
        val longFresh = CrawlThroughputPolicy.ttlUrgencyScore(604_800, now, now) // 7d+满加成=1999
        assertTrue(shortStale > longFresh)
    }

    @Test
    fun `紧迫度_极端短TTL_封顶9999不溢出`() {
        val now = 1_700_000_000L
        assertEquals(9_999, CrawlThroughputPolicy.ttlUrgencyScore(1, now, now)) // 1 秒 TTL
        assertEquals(9_999, CrawlThroughputPolicy.ttlUrgencyScore(60, now, now)) // 1 分钟
    }

    // ---- R284 时效感知：覆盖闸（头刷间隔钳制） ----

    @Test
    fun `覆盖闸_不自毁_原间隔直通现状锚`() {
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(6 * 3_600_000L, 0))
        assertEquals(Long.MAX_VALUE, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(Long.MAX_VALUE, 0))
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(6 * 3_600_000L, -3))
    }

    @Test
    fun `覆盖闸_七天自毁_基准6h不变`() {
        // 6h ≪ 3.5d 本就达标——钳制是 min 语义，绝不把达标频道拖慢
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(6 * 3_600_000L, 604_800))
    }

    @Test
    fun `覆盖闸_七天自毁_死档7d探活钳到3天半`() {
        // 「7 天的群至少 3~4 天一覆盖，别真等到第七天」= TTL/2 口径
        val deadProbe = 7L * 24 * 3_600_000L
        assertEquals(604_800_000L / 2, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(deadProbe, 604_800))
    }

    @Test
    fun `覆盖闸_一天自毁_钳到12h`() {
        // min 语义：基准 6h ≪ 12h 本就达标、维持不变（绝不把达标频道拖慢）；
        // 死档 7d 探活这类长基准才被钳到 TTL/2=12h（「TTL 窗口内爬满一轮」的咬人面）
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(6 * 3_600_000L, 86_400))
        assertEquals(12 * 3_600_000L, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(7L * 24 * 3_600_000L, 86_400))
    }

    @Test
    fun `覆盖闸_极端值不溢出`() {
        // Int.MAX_VALUE 秒（≈68 年）×1000 仍远不及 Long 上限；min 语义=基准直通
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(6 * 3_600_000L, Int.MAX_VALUE))
    }

    // ---- R284 反射锚：时效口径常量锁同步（防两份数字漂移） ----

    @Test
    fun `时效常量锚_七天车道线与覆盖除数_反射机检`() {
        // TTL_URGENT_LANE_MAX_SEC=7 天是用户实锤口径（资源机器人群 7 天自毁）；
        // TTL_COVERAGE_DIVISOR=2 是「3~4 天一覆盖」口径——改动任一=验收语义漂移
        val cls = CrawlThroughputPolicy::class.java
        fun staticInt(name: String): Int = cls.getDeclaredField(name).getInt(null)
        assertEquals(604_800, staticInt("TTL_URGENT_LANE_MAX_SEC"))
        assertEquals(2, staticInt("TTL_COVERAGE_DIVISOR"))
        assertEquals(9_999, staticInt("TTL_URGENCY_SCORE_MAX"))
    }

    // ---- R286 活跃新鲜度：头刷提频闸（min 钳制，只缩不伸） ----

    @Test
    fun `活跃闸_未知新鲜度_原间隔直通现状锚`() {
        // lastMsgSec<=0（存量库缺省/未见过服务器新鲜度）→ base 逐项相等（现状锚）
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, 0L, 1_700_000_000L))
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, -5L, 1_700_000_000L))
        assertEquals(Long.MAX_VALUE, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(Long.MAX_VALUE, 0L, 1_700_000_000L))
    }

    @Test
    fun `活跃闸_热频道_收敛到30min`() {
        val now = 1_700_000_000L
        val hot = now - 600L // 10 分钟前有新帖
        assertEquals(30 * 60_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, hot, now))
        // 边界：恰 1h 前仍算热
        assertEquals(30 * 60_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, now - 3_600L, now))
    }

    @Test
    fun `活跃闸_活跃频道_收敛到2h`() {
        val now = 1_700_000_000L
        assertEquals(2 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, now - 2 * 3_600L, now))
        // 边界：恰 24h 前仍算活跃；24h+1s 出窗直通
        assertEquals(2 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, now - 86_400L, now))
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, now - 86_401L, now))
    }

    @Test
    fun `活跃闸_冷频道_原间隔直通`() {
        val now = 1_700_000_000L
        assertEquals(6 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, now - 7 * 86_400L, now))
    }

    @Test
    fun `活跃闸_只缩不伸_短基准不被拖慢`() {
        val now = 1_700_000_000L
        // T0 基准 1h 遇活跃档（2h 目标）：min 语义=维持 1h 不变
        assertEquals(3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(3_600_000L, now - 2 * 3_600L, now))
        // T0 基准 1h 遇热档（30min 目标）：收敛到 30min
        assertEquals(30 * 60_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(3_600_000L, now - 60L, now))
    }

    @Test
    fun `活跃闸_死档7d探活遇突活跃_收敛抢鲜`() {
        val now = 1_700_000_000L
        val deadProbe = 7L * 24 * 3_600_000L
        assertEquals(30 * 60_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(deadProbe, now - 120L, now))
        assertEquals(2 * 3_600_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(deadProbe, now - 3 * 3_600L, now))
        assertEquals(deadProbe, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(deadProbe, now - 30 * 86_400L, now))
    }

    @Test
    fun `活跃闸_时钟漂移_未来时间戳按热档`() {
        val now = 1_700_000_000L
        // 服务器时间戳在未来（负差）落热档=按最新鲜处理（防御口径锁死）
        assertEquals(30 * 60_000L, CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(6 * 3_600_000L, now + 600L, now))
    }

    // ---- R286 索引新鲜度：滞后告警线 ----

    @Test
    fun `告警线_任一侧未知_不报警`() {
        assertFalse(CrawlThroughputPolicy.isFreshnessLagging(0L, 1_700_000_000L, 1_800L))
        assertFalse(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 0L, 1_800L))
        assertFalse(CrawlThroughputPolicy.isFreshnessLagging(0L, 0L, 1_800L))
    }

    @Test
    fun `告警线_gap超地板_报警`() {
        // 热频道应有间隔 30min：地板 1h 起判——gap 59min 不报（宽限内），61min 报
        assertFalse(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 1_700_000_000L + 59 * 60L, 1_800L))
        assertTrue(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 1_700_000_000L + 61 * 60L, 1_800L))
    }

    @Test
    fun `告警线_冷频道按自身间隔宽限_不误报`() {
        // 冷频道应有间隔 6h：gap 2h（地板 1h 之上但宽限内）不报；7h 才报
        assertFalse(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 1_700_000_000L + 2 * 3_600L, 6 * 3_600L))
        assertTrue(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 1_700_000_000L + 7 * 3_600L, 6 * 3_600L))
    }

    @Test
    fun `告警线_死档7d间隔_周更帖子不误报`() {
        // 死档应有间隔 7d：gap 3d 不报（宽限内）
        assertFalse(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 1_700_000_000L + 3 * 86_400L, 7 * 86_400L))
        assertTrue(CrawlThroughputPolicy.isFreshnessLagging(1_700_000_000L, 1_700_000_000L + 8 * 86_400L, 7 * 86_400L))
    }

    // ---- R286 反射锚：活跃度口径常量锁同步（防两份数字漂移） ----

    @Test
    fun `活跃度常量锚_窗口与间隔与告警地板_反射机检`() {
        val cls = CrawlThroughputPolicy::class.java
        fun staticLong(name: String): Long = cls.getDeclaredField(name).getLong(null)
        assertEquals(3_600L, staticLong("ACTIVITY_HOT_WINDOW_SEC"))
        assertEquals(86_400L, staticLong("ACTIVITY_ACTIVE_WINDOW_SEC"))
        assertEquals(30 * 60_000L, staticLong("HEAD_REFRESH_HOT_MS"))
        assertEquals(2 * 3_600_000L, staticLong("HEAD_REFRESH_ACTIVE_MS"))
        assertEquals(3_600L, staticLong("FRESHNESS_LAG_ALERT_FLOOR_SEC"))
    }
}
