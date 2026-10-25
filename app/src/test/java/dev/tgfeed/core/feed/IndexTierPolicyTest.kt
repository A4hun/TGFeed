package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IndexTierPolicy]（R163 B1 档位策略纯函数）的行为单测：
 * D/R 评分卡（含各项封顶/负分量）、f(D,R) 定档矩阵、升档即时 vs 降档滞回、
 * 档位→爬取参数映射的「现状锚」（LEGACY 与 DeepSearchIndex 既有常数逐项相等，
 * 防分档开关关闭时行为漂移）、探针页预算、常规车道入队资格。
 *
 * 关键行为：**丰度升档不需要用户先关注**（死亡循环击断点，设计 §3.1/§3.5 机制①）。
 */
class IndexTierPolicyTest {

    private companion object {
        const val EPS = 1e-9
        val TIERING_ON = IndexTierPolicy.TieringConfig(enabled = true)
    }

    // ---------------- 构造助手 ----------------

    /** 全零需求信号（未订阅、无接触） */
    private fun noDemand(subscribed: Boolean = false) = IndexTierPolicy.DemandSignals(subscribed = subscribed)

    /** 满分丰度：20 条/周（密度满）+ 全视频 + 全剧集规整 + caption 全非空且 80 字 */
    private fun richProbe() = IndexTierPolicy.RichnessSignals(
        probed = true, sampleItems = 100, sampleSpanDays = 35.0, // 100/35*7 = 20/周 恰满
        videoShare = 1.0, episodeHitRate = 1.0, captionNonEmptyRate = 1.0, avgCaptionLen = 80.0,
    )

    /** 空样本（未探针） */
    private fun unprobed() = IndexTierPolicy.RichnessSignals(probed = false)

    // ---------------- 纪律①：分档关闭 = 现状等效 ----------------

    @Test
    fun `分档关闭恒返回LEGACY现状等效`() {
        // 行为：enabled=false（默认）时无论信号多强都判 LEGACY——开关关=行为零变化
        val d = IndexTierPolicy.evaluateTier(
            IndexTierPolicy.TieringConfig(), // 默认 enabled=false
            IndexTierPolicy.IndexTier.T3, noDemand(subscribed = true), richProbe(), downStreak = 0,
        )
        assertEquals(IndexTierPolicy.IndexTier.LEGACY, d.tier)
        assertFalse(d.promoteUrgent)
    }

    @Test
    fun `LEGACY档爬取参数与现状锚逐项相等`() {
        // 行为：现状锚——分档关闭时 cap/头刷/采样闸/deepen 全等于既有常数，
        // 不出现第二份数字；镜像与 DeepSearchIndex 真值的同步由反射机测单独上锁
        val legacy = IndexTierPolicy.IndexTier.LEGACY
        val cfg = IndexTierPolicy.TieringConfig()
        assertEquals(IndexTierPolicy.CURRENT_TAIL_MAX_ITEMS, IndexTierPolicy.tierCap(legacy, cfg))
        assertEquals(IndexTierPolicy.CURRENT_HEAD_REFRESH_MS, IndexTierPolicy.headRefreshIntervalMs(legacy, cfg))
        assertFalse(IndexTierPolicy.samplingGateExempt(legacy)) // LEGACY 不豁免采样闸=现状
        assertTrue(IndexTierPolicy.deepenAllowed(legacy))       // LEGACY 加深照跑=现状
        assertTrue(IndexTierPolicy.headRefreshEnqueued(legacy))
    }

    @Test
    fun `现状锚镜像与DeepSearchIndex真值同步_反射机检`() {
        // 行为：AGENT-DISCIPLINE 单一数字源——镜像值必须与上游 private companion 的
        // 真值一致（const val 编译为 outer 类 public static 字段，反射直读；
        // R158 若调整这些常数，此测试红——提醒同步镜像，防两份数字漂移）
        val cls = DeepSearchIndex::class.java
        fun staticLong(name: String): Long = cls.getDeclaredField(name).getLong(null)
        fun staticInt(name: String): Int = cls.getDeclaredField(name).getInt(null)
        assertEquals(staticInt("TAIL_MAX_ITEMS"), IndexTierPolicy.CURRENT_TAIL_MAX_ITEMS)
        assertEquals(staticLong("HEAD_REFRESH_MIN_INTERVAL_MS"), IndexTierPolicy.CURRENT_HEAD_REFRESH_MS)
        assertEquals(staticInt("PAGE_SIZE"), IndexTierPolicy.CURRENT_PAGE_SIZE)
    }

    @Test
    fun `B3库版本与evict扳机锚点_反射机检`() {
        // 行为：R163 B3 落码锚——DB v6（tier 列迁移在场）与修订令版 evict 扳机
        // （磁盘水位线默认 2GiB；全库行数硬顶 205 万已废——死数字剪裁活偏好）
        // 锁定真值，防后续改动静默漂移；单轮收删频道数钳在 1..8（渐进收敛档——
        // 防 0（永不收敛）与巨值（一次删狠）两头翻车）
        // R202 增 v7 锚：idx_items_date 时间索引版本在场（R203/R204 依赖该口径）
        // R228 增 v8 锚：items + parsed_title/item_tags（caption 结构化两列）在场
        // + 回填节流参数（用户拍板批 500 行/秒级错峰，调松=错峰红线松弛）
        // R236 增 v9 锚：tags/tag_items 双表（聚合账本+元素级倒排——tag 毫秒级
        // 检索的结构钥匙）+ 回填节流参数同口径锚
        // R262b 增 v10 锚：meta + dead 列（死档标记/7d 探活，DeadChannelPolicy 口径）
        // R266 增 v11 锚：repost_refs 别名表+posts_view+idx_items_fuid（转发存储合并）
        // R284 增 v12 锚：meta + ttlSec 列（自毁 TTL 时效感知调度，枚举顺手落库）
        // R286 增 v13 锚：meta + lastMsgSec 列（服务器最新帖秒——活跃度头刷提频闸
        // + 索引新鲜度滞后告警线的判定原料，枚举顺手落库、只更新不建行）
        // R292 增 v14 锚：item_ocr 封面 OCR 独立表（识别文本绝不混 caption——
        // 引擎换代整表重建；applied=0 试点期生产检索零变化）
        val cls = DeepSearchIndex::class.java
        fun staticInt(name: String): Int = cls.getDeclaredField(name).getInt(null)
        fun staticLong(name: String): Long = cls.getDeclaredField(name).getLong(null)
        fun staticStr(name: String): String = cls.getDeclaredField(name).get(null) as String
        assertEquals(14, staticInt("DB_VERSION"))
        assertEquals(2L * 1024 * 1024 * 1024, IndexTierPolicy.EVICT_MIN_FREE_BYTES_DEFAULT)
        val perPass = staticInt("EVICT_MAX_CHANNELS_PER_PASS")
        assertTrue(perPass in 1..8)
        // 修订令回归：全库行数硬顶必须已删干净（残留=旧扳机复活）
        assertTrue(runCatching { staticInt("EVICT_GLOBAL_BUDGET_ITEMS") }.isFailure)
        // R228 v8 结构化两列锚（列名即 SQL 拼接口径——改名=查询/UPDATE 全线漂移）
        assertEquals("parsed_title", staticStr("PARSED_TITLE_COL"))
        assertEquals("item_tags", staticStr("ITEM_TAGS_COL"))
        // R228 回填节流红线锚（用户口径「批 500 条/秒级错峰」）
        assertEquals(500, staticInt("BACKFILL_BATCH_ROWS"))
        assertEquals(1_000L, staticLong("BACKFILL_BATCH_GAP_MS"))
        // R236 v9 tags 双表锚（表/索引名即 SQL 拼接口径——改名=查询/清理全线漂移；
        // tag_items 三索引=毫秒级召回/evict 清理/delta 点查的结构钥匙）
        assertEquals("tags", staticStr("TAGS_TABLE"))
        assertEquals("tag_items", staticStr("TAG_ITEMS_TABLE"))
        assertEquals("idx_tags_tag", staticStr("TAGS_TAG_INDEX"))
        assertEquals("idx_tag_items_chat", staticStr("TAG_ITEMS_CHAT_INDEX"))
        assertEquals("idx_tag_items_rowid", staticStr("TAG_ITEMS_ROWID_INDEX"))
        // R236 回填节流同口径锚（批 500/有写入 1s；零写入短间隔防 3.5M 行沙漠空转）
        assertEquals(500, staticInt("TAGS_BACKFILL_BATCH_ROWS"))
        assertEquals(1_000L, staticLong("TAGS_BACKFILL_BATCH_GAP_MS"))
        assertEquals(200L, staticLong("TAGS_BACKFILL_IDLE_GAP_MS"))
        // R266 v11 转发合并锚（表/索引/视图名即 SQL 拼接口径——改名=迁移/两段法/
        // 外部双臂全线漂移；批迁节流同 R228/R236 用户口径红线）
        assertEquals("repost_refs", staticStr("REPOSTS_TABLE"))
        assertEquals("idx_refs_chat_msg", staticStr("REFS_CHAT_INDEX"))
        assertEquals("idx_refs_album", staticStr("REFS_ALBUM_INDEX"))
        assertEquals("idx_refs_fuid", staticStr("REFS_FUID_INDEX"))
        assertEquals("idx_refs_date", staticStr("REFS_DATE_INDEX"))
        assertEquals("idx_items_fuid", staticStr("FUID_INDEX_NAME"))
        assertEquals("posts_view", staticStr("POSTS_VIEW"))
        assertEquals(500, staticInt("MERGE_BATCH_ROWS"))
        assertEquals(1_000L, staticLong("MERGE_BATCH_GAP_MS"))
        assertEquals(200L, staticLong("MERGE_IDLE_GAP_MS"))
        // DDL 唯一性：指纹索引名与 DDL 内名字一致（探测/建库幂等口共用一名）
        val fuidDdl = cls.getDeclaredField("CREATE_ITEMS_FUID_INDEX_SQL").get(null) as String
        assertTrue("CREATE INDEX IF NOT EXISTS idx_items_fuid ON items(fileUniqueId)" == fuidDdl)
        // R292 v14 item_ocr 锚（表/索引名即 SQL 拼接口径；试点上限 1 万=任务书口径，
        // 调大=未经终审的放量红线松弛）
        assertEquals("item_ocr", staticStr("ITEM_OCR_TABLE"))
        assertEquals("idx_item_ocr_chat", staticStr("ITEM_OCR_CHAT_INDEX"))
        assertEquals("idx_item_ocr_applied", staticStr("ITEM_OCR_APPLIED_INDEX"))
        assertEquals(10_000, staticInt("COVER_OCR_PILOT_LIMIT"))
    }

    @Test
    fun `R202时间窗原语有界红线锚_反射机检`() {
        // 行为：时间窗采样硬顶与 IN 分块上限锁定（有界红线是 R183 全表扫禁令的
        // 工程化——常量被调大=红线松弛，测试红提醒）；时间索引名唯一（探测/建库
        // 幂等口共用一名，两份名字=漂移源头）。硬顶的公共唯一出处=
        // TimeWindowPolicy.QUERY_LIMIT_MAX（R203 挂账解法），DeepSearchIndex 侧零镜像
        assertEquals(600, TimeWindowPolicy.QUERY_LIMIT_MAX)
        val cls = DeepSearchIndex::class.java
        fun staticInt(name: String): Int = cls.getDeclaredField(name).getInt(null)
        // 硬顶镜像已删（残留=双出处漂移复活）
        assertTrue(runCatching { staticInt("TIME_WINDOW_MAX_LIMIT") }.isFailure)
        val chunk = staticInt("TIME_WINDOW_IN_CHUNK")
        assertTrue(chunk in 2..999) // 老设备 SQLite 占位符上限之内
        // DDL 唯一性：索引名与 DDL 内的名字一致（防两处手写漂移）
        val ddl = cls.getDeclaredField("CREATE_ITEMS_DATE_INDEX_SQL").get(null) as String
        val idxName = cls.getDeclaredField("DATE_INDEX_NAME").get(null) as String
        assertTrue("CREATE INDEX IF NOT EXISTS $idxName ON items(dateEpochSec, chatId)" == ddl)
    }

    @Test
    fun `修订令evict扳机矩阵_真实约束`() {
        // 行为：扳机=水位线 ∨ 用户预算（任一命中即触发；0=该道扳机关）。
        // 全库行数不在判定里——体积是关注结构的涌现结果，不设死顶
        val wm = 2L * 1024 * 1024 * 1024
        val budget = 5L * 1024 * 1024 * 1024
        val base = IndexTierPolicy.TieringConfig(enabled = true)
        // 默认配置：水位线 2GiB、预算 0（关）
        assertFalse(IndexTierPolicy.evictNeeded(freeBytes = wm, dbBytes = Long.MAX_VALUE, config = base))
        assertTrue(IndexTierPolicy.evictNeeded(freeBytes = wm - 1, dbBytes = 0L, config = base))
        // 水位线关（≤0）+ 预算关 → 永不触发（库再大也不删）
        val bothOff = base.copy(evictMinFreeBytes = 0, evictIndexBudgetBytes = 0)
        assertFalse(IndexTierPolicy.evictNeeded(freeBytes = 0, dbBytes = Long.MAX_VALUE, config = bothOff))
        // 用户预算：库超预算触发，与水位线无关
        val budgetOnly = base.copy(evictMinFreeBytes = 0, evictIndexBudgetBytes = budget)
        assertFalse(IndexTierPolicy.evictNeeded(freeBytes = 0, dbBytes = budget, config = budgetOnly))
        assertTrue(IndexTierPolicy.evictNeeded(freeBytes = Long.MAX_VALUE, dbBytes = budget + 1, config = budgetOnly))
        // 测不到 freeBytes（Long.MAX_VALUE 兜底）只看预算道
        assertFalse(IndexTierPolicy.evictNeeded(freeBytes = Long.MAX_VALUE, dbBytes = budget, config = budgetOnly))
    }

    @Test
    fun `档位persisted往返与未知值回LEGACY`() {
        // 行为：落库序数反解闭环；存量库未知值（含 v6 前缺省 -1）→ LEGACY 向后兼容
        for (t in IndexTierPolicy.IndexTier.entries) {
            assertEquals(t, IndexTierPolicy.IndexTier.fromPersisted(t.persisted))
        }
        assertEquals(IndexTierPolicy.IndexTier.LEGACY, IndexTierPolicy.IndexTier.fromPersisted(99))
        assertEquals(IndexTierPolicy.IndexTier.LEGACY, IndexTierPolicy.IndexTier.fromPersisted(-1))
    }

    // ---------------- R 评分卡 ----------------

    @Test
    fun `满分丰度样本得满分`() {
        // 行为：密度满+全视频+全规整+caption 满信息量 → R=1.0（无死链）
        assertEquals(1.0, IndexTierPolicy.richnessScore(richProbe()), EPS)
    }

    @Test
    fun `未探针或零样本R为零_无证据不臆测`() {
        // 行为：probed=false 或 sampleItems=0 → R=0（档位判定靠 probed 标志区分「测过且低」与「没测过」）
        assertEquals(0.0, IndexTierPolicy.richnessScore(unprobed()), EPS)
        assertEquals(
            0.0,
            IndexTierPolicy.richnessScore(richProbe().copy(sampleItems = 0)),
            EPS,
        )
    }

    @Test
    fun `密度分封顶二十条每周_高频频道不独大`() {
        // 行为：日更百条（100 条/1 天=700/周）与 20/周 的密度分相同（封顶归一）
        val capped = IndexTierPolicy.richnessScore(richProbe().copy(sampleSpanDays = 1.0))
        assertEquals(1.0, capped, EPS)
    }

    @Test
    fun `死链率是负分量_满频道也被拖下水`() {
        // 行为：deadRate=1 → R 减 0.5（死媒体进丰度分本体，设计 §9.2）
        val r = IndexTierPolicy.richnessScore(richProbe().copy(deadRate = 1.0))
        assertEquals(0.5, r, EPS)
        // 死链率本身封顶（>1 不无限扣）
        assertEquals(r, IndexTierPolicy.richnessScore(richProbe().copy(deadRate = 3.0)), EPS)
    }

    @Test
    fun `caption长度封顶八十字_信息量只计到八十字`() {
        // 行为：avgCaptionLen 200 与 80 的信息量分相同（封顶归一，防长文频道独大）
        val longCap = IndexTierPolicy.richnessScore(richProbe().copy(avgCaptionLen = 200.0))
        assertEquals(1.0, longCap, EPS)
    }

    @Test
    fun `低密度低价值样本得低分`() {
        // 行为：半年 100 条（3.8/周）+ 零视频 + 零规整 + 空 caption → R 显著低于升档线
        val r = IndexTierPolicy.richnessScore(
            IndexTierPolicy.RichnessSignals(
                probed = true, sampleItems = 100, sampleSpanDays = 180.0,
                videoShare = 0.0, episodeHitRate = 0.0, captionNonEmptyRate = 0.0, avgCaptionLen = 0.0,
            ),
        )
        assertTrue("R=$r 应低于升档线", r < IndexTierPolicy.RICHNESS_UP_SCORE)
    }

    // ---------------- D 评分卡 ----------------

    @Test
    fun `需求分公式各项与封顶`() {
        // 行为：W×1 + C×10 + A×0.05 + I×3 − N×1.5；W 封顶 50、A 封顶 120s
        val d = IndexTierPolicy.DemandSignals(
            subscribed = false, watchEvents = 50, completionRate = 1.0,
            avgWatchSec = 120.0, positiveInteractions = 10, negativeFeedback = 0,
        )
        assertEquals(50.0 + 10.0 + 6.0 + 30.0, IndexTierPolicy.demandScore(d), EPS)
        // 封顶：超量观看/超长停留不再加分
        val capped = IndexTierPolicy.demandScore(d.copy(watchEvents = 500, avgWatchSec = 600.0))
        assertEquals(IndexTierPolicy.demandScore(d), capped, EPS)
        // 负反馈扣分
        val neg = IndexTierPolicy.demandScore(d.copy(negativeFeedback = 10))
        assertEquals(IndexTierPolicy.demandScore(d) - 15.0, neg, EPS)
    }

    // ---------------- 定档矩阵 f(D,R) ----------------

    @Test
    fun `订阅频道恒T0_新订阅触发插队_已T0维持不重复插队`() {
        // 行为：M1——订阅是 T0 唯一判据；档位升高才 promoteUrgent（幂等）
        val from = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T2, noDemand(subscribed = true), unprobed(), 0)
        assertEquals(IndexTierPolicy.IndexTier.T0, from.tier)
        assertTrue(from.promoteUrgent)
        val stay = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T0, noDemand(subscribed = true), unprobed(), 0)
        assertEquals(IndexTierPolicy.IndexTier.T0, stay.tier)
        assertFalse(stay.promoteUrgent)
    }

    @Test
    fun `丰度升档不需要用户先关注_零接触高分样本自动升T1`() {
        // 行为：死亡循环击断点（设计 §3.1）——D 全零（没订阅没互动没强证据），
        // 仅探针测出高丰度即升 T1 深索引，搜索覆盖不由关注列表独裁
        val d = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T3, noDemand(), richProbe(), 0)
        assertEquals(IndexTierPolicy.IndexTier.T1, d.tier)
        assertTrue(d.promoteUrgent)
        // 已在 T1 则维持原位（幂等，不重复插队）
        val stay = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T1, noDemand(), richProbe(), 0)
        assertEquals(IndexTierPolicy.IndexTier.T1, stay.tier)
        assertFalse(stay.promoteUrgent)
    }

    @Test
    fun `互动分过线升T1_强证据即时升T1`() {
        // 行为：M3（S≥20）与 M9/M6（strongEvidence）都是即时升档线
        val s = IndexTierPolicy.DemandSignals(
            subscribed = false, watchEvents = 25, completionRate = 0.5, // 25+5=30 ≥ 20
        )
        assertEquals(
            IndexTierPolicy.IndexTier.T1,
            IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T2, s, unprobed(), 0).tier,
        )
        assertEquals(
            IndexTierPolicy.IndexTier.T1,
            IndexTierPolicy.evaluateTier(
                TIERING_ON, IndexTierPolicy.IndexTier.T3,
                noDemand().copy(strongEvidence = true), unprobed(), 0,
            ).tier,
        )
    }

    @Test
    fun `零接触零数据维持T3待探`() {
        // 行为：M6 前置——未探针、无观看、无强证据、当前 T3 → 维持 T3（等探索配额/首触）
        val d = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T3, noDemand(), unprobed(), 0)
        assertEquals(IndexTierPolicy.IndexTier.T3, d.tier)
        assertFalse(d.promoteUrgent)
    }

    @Test
    fun `降档滞回_连续两窗低于线才降一级_T2不再降`() {
        // 行为：M5/M7——升档即时、降档要 downStreak≥2（防样本波动抖动）；
        // T2 是保底档（T3 只走长期闲置 [isOnDemandIdle]，不走评分滞回）
        val weak = noDemand() // S=0 ≤ 3；R=0（未探针）≤ 0.25
        val one = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T1, weak, unprobed(), 1)
        assertEquals(IndexTierPolicy.IndexTier.T1, one.tier) // 1 窗不足：维持
        val two = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T1, weak, unprobed(), 2)
        assertEquals(IndexTierPolicy.IndexTier.T2, two.tier) // 2 窗：降 T2
        assertFalse(two.promoteUrgent)                        // 降档不插队
        val floor = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T2, weak, unprobed(), 5)
        assertEquals(IndexTierPolicy.IndexTier.T2, floor.tier) // T2 保底不降 T3
    }

    @Test
    fun `中度信号维持原档`() {
        // 行为：S 在升降线之间、R 未过升档线 → 保持 current（T1/T2 原位维护）
        val mid = IndexTierPolicy.DemandSignals(subscribed = false, watchEvents = 10, avgWatchSec = 30.0) // S=11.5
        assertEquals(
            IndexTierPolicy.IndexTier.T2,
            IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T2, mid, unprobed(), 0).tier,
        )
        assertEquals(
            IndexTierPolicy.IndexTier.T1,
            IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T1, mid, unprobed(), 0).tier,
        )
    }

    @Test
    fun `长期闲置降T3判定_三十天边界`() {
        // 行为：M6——最后接触距今 >30 天（或从未接触）→ T3 候选（B4 组合调用）
        val now = 1_000_000_000_000L
        val d30 = IndexTierPolicy.ON_DEMAND_IDLE_MS
        assertTrue(IndexTierPolicy.isOnDemandIdle(now - d30 - 1, now))
        assertFalse(IndexTierPolicy.isOnDemandIdle(now - d30 + 1, now))
        assertTrue(IndexTierPolicy.isOnDemandIdle(0L, now)) // 从未接触也算闲置
    }

    // ---------------- 档位 → 爬取参数映射 ----------------

    @Test
    fun `档位参数映射与设计总表一致`() {
        // 行为：T0 全量+头刷提频+采样闸豁免；T1/T2 上限+现状 6h 头刷；T3 全关
        assertEquals(IndexTierPolicy.CURRENT_TAIL_MAX_ITEMS, IndexTierPolicy.tierCap(IndexTierPolicy.IndexTier.T0, TIERING_ON))
        assertEquals(TIERING_ON.capT1, IndexTierPolicy.tierCap(IndexTierPolicy.IndexTier.T1, TIERING_ON))
        assertEquals(TIERING_ON.capT2, IndexTierPolicy.tierCap(IndexTierPolicy.IndexTier.T2, TIERING_ON))
        assertEquals(0, IndexTierPolicy.tierCap(IndexTierPolicy.IndexTier.T3, TIERING_ON))
        assertEquals(TIERING_ON.headRefreshMsT0, IndexTierPolicy.headRefreshIntervalMs(IndexTierPolicy.IndexTier.T0, TIERING_ON))
        assertEquals(
            IndexTierPolicy.CURRENT_HEAD_REFRESH_MS,
            IndexTierPolicy.headRefreshIntervalMs(IndexTierPolicy.IndexTier.T1, TIERING_ON),
        )
        assertEquals(
            IndexTierPolicy.CURRENT_HEAD_REFRESH_MS,
            IndexTierPolicy.headRefreshIntervalMs(IndexTierPolicy.IndexTier.T2, TIERING_ON),
        )
        assertEquals(Long.MAX_VALUE, IndexTierPolicy.headRefreshIntervalMs(IndexTierPolicy.IndexTier.T3, TIERING_ON))
        assertTrue(IndexTierPolicy.samplingGateExempt(IndexTierPolicy.IndexTier.T0))   // 唯一豁免档
        assertFalse(IndexTierPolicy.samplingGateExempt(IndexTierPolicy.IndexTier.T1))  // 其余照采样=现状
        assertTrue(IndexTierPolicy.deepenAllowed(IndexTierPolicy.IndexTier.T0))
        assertFalse(IndexTierPolicy.deepenAllowed(IndexTierPolicy.IndexTier.T1))       // T1/T2 触顶即终态
        assertFalse(IndexTierPolicy.headRefreshEnqueued(IndexTierPolicy.IndexTier.T3)) // T3 停维护刷新
    }

    @Test
    fun `探针页预算按页大小换算`() {
        // 行为：probeItems=100（默认）→ 1 页；250 → 3 页（ceil）
        assertEquals(1, IndexTierPolicy.probePageBudget(TIERING_ON))
        assertEquals(3, IndexTierPolicy.probePageBudget(TIERING_ON.copy(probeItems = 250)))
    }

    @Test
    fun `常规车道入队资格_T3无数据不进常规队列`() {
        // 行为：宇宙收敛（§8.2）——T3 只走探索车道；白嫖建行（有 items）不受阻
        assertFalse(IndexTierPolicy.regularLaneEligible(IndexTierPolicy.IndexTier.T3, hasItems = false))
        assertTrue(IndexTierPolicy.regularLaneEligible(IndexTierPolicy.IndexTier.T3, hasItems = true))
        assertTrue(IndexTierPolicy.regularLaneEligible(IndexTierPolicy.IndexTier.T2, hasItems = false))
        assertTrue(IndexTierPolicy.regularLaneEligible(IndexTierPolicy.IndexTier.LEGACY, hasItems = false))
    }

    // ---------------- R191 手动钉住（钉住策略/取消钉复位口径） ----------------

    @Test
    fun `钉住恒T0_分档关也生效_与总开关正交`() {
        // 行为：钉住=显式用户动作（R128 点赞回写「知情后开」先例）——置于总开关判定
        // 之前：分档关（默认）时钉住照样 T0（参数面与 LEGACY 仅采样豁免/头刷提频有差）
        for (cur in IndexTierPolicy.IndexTier.entries) {
            val d = IndexTierPolicy.evaluateTier(
                IndexTierPolicy.TieringConfig(), // enabled=false（默认）
                cur, noDemand(), unprobed(), downStreak = 0, manualPinned = true,
            )
            assertEquals("钉住（分档关）自 $cur 应判 T0", IndexTierPolicy.IndexTier.T0, d.tier)
        }
    }

    @Test
    fun `钉住自低档升T0触发urgent_已T0维持不重复插队`() {
        // 行为：钉入自 T1/T2/T3/LEGACY = 一次迁移事件 → promoteUrgent（优先车道即刻
        // 深挖，M1 订阅补全同一插队口）；已是 T0 维持无 urgent（幂等）
        for (cur in listOf(
            IndexTierPolicy.IndexTier.T1, IndexTierPolicy.IndexTier.T2,
            IndexTierPolicy.IndexTier.T3, IndexTierPolicy.IndexTier.LEGACY,
        )) {
            val d = IndexTierPolicy.evaluateTier(TIERING_ON, cur, noDemand(), unprobed(), 0, manualPinned = true)
            assertEquals(IndexTierPolicy.IndexTier.T0, d.tier)
            assertTrue("钉入自 $cur 应 urgent", d.promoteUrgent)
        }
        val stay = IndexTierPolicy.evaluateTier(TIERING_ON, IndexTierPolicy.IndexTier.T0, noDemand(), unprobed(), 0, manualPinned = true)
        assertEquals(IndexTierPolicy.IndexTier.T0, stay.tier)
        assertFalse(stay.promoteUrgent)
    }

    @Test
    fun `钉住永不降档_弱信号高滞回计数都不降`() {
        // 行为：用户意志高于 D/R 信号——双弱（S=0、R=0）+ downStreak=9（远超滞回窗）
        // + 分档开，钉住频道仍 T0（自动评估的降档路径对钉住一律无效）
        val d = IndexTierPolicy.evaluateTier(
            TIERING_ON, IndexTierPolicy.IndexTier.T1,
            noDemand(), unprobed(), downStreak = 9, manualPinned = true,
        )
        assertEquals(IndexTierPolicy.IndexTier.T0, d.tier)
    }

    @Test
    fun `钉住优先于订阅与强证据_未关注可钉`() {
        // 行为：钉=深索引授权不等关注——未订阅钉住即 T0；订阅+钉住同为 T0（语义合流）
        val notSubscribed = IndexTierPolicy.evaluateTier(
            TIERING_ON, IndexTierPolicy.IndexTier.T3, noDemand(), unprobed(), 0, manualPinned = true,
        )
        assertEquals(IndexTierPolicy.IndexTier.T0, notSubscribed.tier)
        val subscribed = IndexTierPolicy.evaluateTier(
            TIERING_ON, IndexTierPolicy.IndexTier.T1,
            noDemand().copy(strongEvidence = true), unprobed(), 0, manualPinned = true,
        )
        assertEquals(IndexTierPolicy.IndexTier.T0, subscribed.tier)
    }

    @Test
    fun `取消钉复位口径_分档开重估_分档关回LEGACY现状`() {
        // 行为：manualUnpinRestoreTier——分档开=null（调用方复位 LEGACY 后完整重估，
        // 钉住期间的 T0 不被「维持现状」锚住）；分档关=LEGACY（现状等效复位）
        assertNull(IndexTierPolicy.manualUnpinRestoreTier(tieringEnabled = true))
        assertEquals(IndexTierPolicy.IndexTier.LEGACY, IndexTierPolicy.manualUnpinRestoreTier(tieringEnabled = false))
    }
}
