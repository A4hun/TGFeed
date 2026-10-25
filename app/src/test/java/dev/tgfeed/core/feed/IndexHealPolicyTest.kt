package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IndexHealPolicy]（R289 索引库损坏自愈策略纯函数件）的行为单测：
 * 损坏分级矩阵、有界探针口径、守卫状态机（损坏计数/只读回退/健康衰减）、
 * 备份命名口径、自愈操作域红线锚。
 *
 * 关键行为：**连续损坏达顶回退只读**（防「重建→又坏→再重建」烧盘活锁）；
 * **用户数据绝不在自愈范围**（isHealScopePath 只放 search.db 三件套+corrupt 备份）。
 */
class IndexHealPolicyTest {

    // ---------------- 损坏分级矩阵（病灶层→损坏级唯一出处） ----------------

    @Test
    fun `分级矩阵_主表与模式层重损_索引与衍生层轻损`() {
        // 行为：SCHEMA/META/ITEMS_BODY（主表/模式层）=重损（备份+重建+打捞）；
        // SECONDARY_INDEX/DERIVED（索引/衍生表——可重建）=轻损
        assertEquals(IndexHealPolicy.DamageClass.HEAVY, IndexHealPolicy.classify(IndexHealPolicy.ProbeStage.SCHEMA))
        assertEquals(IndexHealPolicy.DamageClass.HEAVY, IndexHealPolicy.classify(IndexHealPolicy.ProbeStage.META))
        assertEquals(IndexHealPolicy.DamageClass.HEAVY, IndexHealPolicy.classify(IndexHealPolicy.ProbeStage.ITEMS_BODY))
        assertEquals(IndexHealPolicy.DamageClass.LIGHT_INDEX, IndexHealPolicy.classify(IndexHealPolicy.ProbeStage.SECONDARY_INDEX))
        assertEquals(IndexHealPolicy.DamageClass.LIGHT_INDEX, IndexHealPolicy.classify(IndexHealPolicy.ProbeStage.DERIVED))
    }

    // ---------------- 有界探针口径 ----------------

    @Test
    fun `采样行均布且去重_空表直通`() {
        // 行为：均布内点（首尾不取 0/max 本身——防边界页偏差聚集）；空表/负值=空集直通
        val samples = IndexHealPolicy.sampleRowids(1_700_000L, 16)
        assertEquals(16, samples.size)
        assertEquals(samples.distinct().size, samples.size) // 全域互斥
        assertTrue(samples.all { it > 0L && it < 1_700_000L })
        // 均布锚：第 k 点 = max*(k+1)/17
        assertEquals(100_000L, samples[0])
        assertEquals(1_600_000L, samples[15])
        assertEquals(0, IndexHealPolicy.sampleRowids(0L).size)
        assertEquals(0, IndexHealPolicy.sampleRowids(-5L).size)
        assertEquals(0, IndexHealPolicy.sampleRowids(100L, 0).size)
    }

    @Test
    fun `有界口径常量锚_全量检查绝不进首开`() {
        // 行为：采样点/穿刺数/同步建索引红线锁定——调大=首开秒级红线松弛，测试红提醒
        assertEquals(16, IndexHealPolicy.ITEMS_SAMPLE_POINTS)
        assertEquals(2, IndexHealPolicy.INDEX_PROBE_PER_INDEX)
        assertEquals(200_000L, IndexHealPolicy.SYNC_INDEX_BUILD_MAX_ROWS)
    }

    // ---------------- 守卫状态机：损坏计数 + 只读回退（防活锁） ----------------

    private val t0 = 1_760_000_000_000L // 固定纪元锚（测试确定性）

    @Test
    fun `首次损坏计数一_不进只读`() {
        val g = IndexHealPolicy.onCorruption(IndexHealPolicy.GuardState(), t0)
        assertEquals(1, g.consecutiveCorruptions)
        assertEquals(1, g.totalCorruptions)
        assertEquals(t0, g.lastCorruptionAtMs)
        assertEquals(0L, g.readOnlyUntilMs)
        assertFalse(IndexHealPolicy.shouldEnterReadOnly(g, t0))
        // 轻/重损都照常修复
        assertEquals(
            IndexHealPolicy.HealAction.REBUILD_OBJECTS,
            IndexHealPolicy.decide(IndexHealPolicy.DamageClass.LIGHT_INDEX, g, t0),
        )
        assertEquals(
            IndexHealPolicy.HealAction.HEAVY_REBUILD,
            IndexHealPolicy.decide(IndexHealPolicy.DamageClass.HEAVY, g, t0),
        )
    }

    @Test
    fun `连续三坏进只读_窗内只读窗外放行重试`() {
        var g = IndexHealPolicy.GuardState()
        g = IndexHealPolicy.onCorruption(g, t0)
        g = IndexHealPolicy.onCorruption(g, t0 + 1_000)
        g = IndexHealPolicy.onCorruption(g, t0 + 2_000) // 第三连坏
        assertEquals(3, g.consecutiveCorruptions)
        assertEquals(t0 + 2_000 + IndexHealPolicy.READONLY_COOLDOWN_MS, g.readOnlyUntilMs)
        // 窗内：只读（无论轻重损都不再重建=防活锁核心断言）
        val inWindow = t0 + 3_600_000L
        assertTrue(IndexHealPolicy.shouldEnterReadOnly(g, inWindow))
        assertEquals(
            IndexHealPolicy.HealAction.READ_ONLY,
            IndexHealPolicy.decide(IndexHealPolicy.DamageClass.LIGHT_INDEX, g, inWindow),
        )
        assertEquals(
            IndexHealPolicy.HealAction.READ_ONLY,
            IndexHealPolicy.decide(IndexHealPolicy.DamageClass.HEAVY, g, inWindow),
        )
        // 窗外：放行一次重试
        val outWindow = g.readOnlyUntilMs + 1
        assertFalse(IndexHealPolicy.shouldEnterReadOnly(g, outWindow))
    }

    @Test
    fun `只读窗后重试失败直接续窗_不重新计一二三`() {
        // 行为：只读史中（readOnlyUntilMs>0）重试再败=连续计数照涨不衰减、
        // 只读窗直接续 24h——每日最多 1 次重建尝试，零活锁
        var g = IndexHealPolicy.GuardState()
        repeat(3) { g = IndexHealPolicy.onCorruption(g, t0 + it * 1_000L) }
        val retryAt = g.readOnlyUntilMs + 60_000L // 窗后重试
        g = IndexHealPolicy.onCorruption(g, retryAt) // 重试失败
        assertEquals(4, g.consecutiveCorruptions) // 不衰减重计
        assertEquals(retryAt + IndexHealPolicy.READONLY_COOLDOWN_MS, g.readOnlyUntilMs) // 直接续窗
        assertEquals(
            IndexHealPolicy.HealAction.READ_ONLY,
            IndexHealPolicy.decide(IndexHealPolicy.DamageClass.HEAVY, g, retryAt + 1_000),
        )
    }

    @Test
    fun `健康衰减_六小时间隔外的损坏算新一轮`() {
        // 行为：偶发单次损坏不累积成只读——距上次损坏超 6h 且无只读史 → 计数从 1 重起
        var g = IndexHealPolicy.GuardState()
        g = IndexHealPolicy.onCorruption(g, t0)
        g = IndexHealPolicy.onCorruption(g, t0 + 1_000) // 连续 2
        assertEquals(2, g.consecutiveCorruptions)
        val later = t0 + 1_000 + IndexHealPolicy.HEALTHY_DECAY_MS + 1 // 距最近一次损坏 >6h（衰减自最近一次起算）
        g = IndexHealPolicy.onCorruption(g, later) // 衰减窗外=新一轮
        assertEquals(1, g.consecutiveCorruptions)
        assertEquals(3, g.totalCorruptions) // 总数不衰减（观测口径）
        assertEquals(0L, g.readOnlyUntilMs)
    }

    @Test
    fun `健康打开衰减窗外清零连续计数与只读史`() {
        var g = IndexHealPolicy.GuardState()
        repeat(3) { g = IndexHealPolicy.onCorruption(g, t0 + it * 1_000L) }
        assertTrue(IndexHealPolicy.shouldEnterReadOnly(g, t0 + 3_000))
        // 硬件恢复/换机：只读窗过期后打开健康 → 清零（自动解除只读回退）
        val healthyAt = g.readOnlyUntilMs + 1
        g = IndexHealPolicy.onHealthy(g, healthyAt)
        assertEquals(0, g.consecutiveCorruptions)
        assertEquals(0L, g.readOnlyUntilMs)
        assertEquals(healthyAt, g.lastHealthyAtMs)
        assertFalse(IndexHealPolicy.shouldEnterReadOnly(g, healthyAt + 1))
        // 窗内健康打开（自愈刚完）：只记健康时刻，不动计数（保守防抖动清零）
        var g2 = IndexHealPolicy.GuardState()
        g2 = IndexHealPolicy.onCorruption(g2, t0)
        g2 = IndexHealPolicy.onHealthy(g2, t0 + 1_000)
        assertEquals(1, g2.consecutiveCorruptions)
        assertEquals(t0 + 1_000, g2.lastHealthyAtMs)
    }

    @Test
    fun `decide无损伤直通_NONE`() {
        val g = IndexHealPolicy.GuardState()
        assertEquals(
            IndexHealPolicy.HealAction.NONE,
            IndexHealPolicy.decide(IndexHealPolicy.DamageClass.NONE, g, t0),
        )
    }

    @Test
    fun `防线常量锚_三连坏二十四小时冷却六小时衰减`() {
        assertEquals(3, IndexHealPolicy.READONLY_TRIGGER_CONSECUTIVE)
        assertEquals(24L * 3600_000L, IndexHealPolicy.READONLY_COOLDOWN_MS)
        assertEquals(6L * 3600_000L, IndexHealPolicy.HEALTHY_DECAY_MS)
    }

    // ---------------- 重损修复口径 ----------------

    @Test
    fun `坏档备份名口径_UTC时间戳恒定格式`() {
        // 行为：search.db.corrupt-yyyyMMdd-HHmmss（UTC 恒定——设备时区漂移不影响命名）
        val name = IndexHealPolicy.corruptBackupName("search.db", t0)
        assertTrue(name.startsWith("search.db.corrupt-"))
        assertTrue(Regex("^search\\.db\\.corrupt-\\d{8}-\\d{6}$").matches(name))
    }

    @Test
    fun `备份保留份数锚_只留最新一份`() {
        // 行为：GB 级索引库的备份堆积=磁盘二次事故——保留份数锁 1（调大=体积红线松弛）
        assertEquals(1, IndexHealPolicy.MAX_CORRUPT_BACKUPS)
    }

    @Test
    fun `meta打捞列白名单_爬取进度字段绝不打捞`() {
        // 行为：打捞=花名册+档位+调度提示+覆盖口径；爬取进度字段（cursor/exhausted/
        // mediaPass/sweepCursor/imported/dead/needDeepen）绝不打捞——空库挂 exhausted=1
        // =永不再爬的假死（复位让爬虫自然回填是重损修复的核心语义）
        val cols = IndexHealPolicy.SALVAGE_META_COLUMNS
        assertTrue("chatId" in cols)
        assertTrue("tier" in cols)
        assertTrue("ttlSec" in cols)
        assertTrue("lastMsgSec" in cols)
        assertTrue("sampled" in cols)
        for (forbidden in listOf("cursor", "exhausted", "mediaPass", "sweepCursor", "imported", "dead", "needDeepen")) {
            assertFalse("爬取进度字段 $forbidden 绝不在打捞白名单", forbidden in cols)
        }
    }

    @Test
    fun `自愈操作域红线_用户数据绝不在范围`() {
        // 行为：只放 search.db 三件套+corrupt 备份；收藏/历史（store/）、设置（prefs/）、
        // 登录态（tdlib/）、R254 成品缓存（galaxy/）、其他杂件恒 false（红线锚）
        assertTrue(IndexHealPolicy.isHealScopePath("search.db"))
        assertTrue(IndexHealPolicy.isHealScopePath("search.db-wal"))
        assertTrue(IndexHealPolicy.isHealScopePath("search.db-shm"))
        assertTrue(IndexHealPolicy.isHealScopePath("deepindex/search.db")) // 带目录前缀
        assertTrue(IndexHealPolicy.isHealScopePath("search.db.corrupt-20261021-131500"))
        assertTrue(IndexHealPolicy.isHealScopePath("search.db.corrupt-20261021-131500-wal"))
        // 红线：以下一律 false
        assertFalse(IndexHealPolicy.isHealScopePath("prefs/settings.json"))
        assertFalse(IndexHealPolicy.isHealScopePath("store/localstore.bin"))
        assertFalse(IndexHealPolicy.isHealScopePath("galaxy/taste_map_cache_v2.bin"))
        assertFalse(IndexHealPolicy.isHealScopePath("galaxy/genome_rollup_cache_v2.bin"))
        assertFalse(IndexHealPolicy.isHealScopePath("tdlib/db.sqlite"))
        assertFalse(IndexHealPolicy.isHealScopePath("index_heal_guard.json")) // 守卫文件不可被当坏档删
        assertFalse(IndexHealPolicy.isHealScopePath("search.db2")) // 前缀近似≠白名单
        assertFalse(IndexHealPolicy.isHealScopePath("search.db.bak"))
        assertFalse(IndexHealPolicy.isHealScopePath(""))
    }

    // ---------------- 执行层锚（反射机检：DeepSearchIndex 常量与策略真值同步） ----------------

    @Test
    fun `执行层常量锚_反射机检`() {
        // 行为：守卫文件名/重建节流锁定（改名=门房白名单与文件 IO 全线漂移；
        // 节流调松=错峰红线松弛——R228/R236 用户口径同族）
        val cls = DeepSearchIndex::class.java
        fun staticStr(name: String): String = cls.getDeclaredField(name).get(null) as String
        fun staticInt(name: String): Int = cls.getDeclaredField(name).getInt(null)
        fun staticLong(name: String): Long = cls.getDeclaredField(name).getLong(null)
        assertEquals("index_heal_guard.json", staticStr("HEAL_GUARD_FILE_NAME"))
        assertEquals(5_000, staticInt("HEAL_FTS_REBUILD_BATCH"))
        assertEquals(1_000L, staticLong("HEAL_REBUILD_BATCH_GAP_MS"))
        // 索引名→DDL 映射一致性：键=DDL 内索引名（防两处手写漂移=重建错对象）
        @Suppress("UNCHECKED_CAST")
        val map = cls.getDeclaredField("INDEX_DDL_BY_NAME").apply { isAccessible = true }.get(null) as Map<String, String>
        assertEquals(9, map.size) // items×2 + tags×1 + tag_items×2 + refs×4（date/fuid 归专属单飞不在列）
        for ((name, ddl) in map) {
            assertTrue("DDL 内索引名与键一致：$name", ddl.contains("IF NOT EXISTS $name"))
        }
        assertFalse("idx_items_date 归 R202 专属单飞不在自愈登记列", map.containsKey("idx_items_date"))
        assertFalse("idx_items_fuid 归 R266 专属单飞不在自愈登记列", map.containsKey("idx_items_fuid"))
    }

    @Test
    fun `门房白名单锚_守卫文件免误杀`() {
        // 行为：index_heal_guard.json[.tmp] 必须过门房白名单（R262b 西西弗斯洞同族
        // 教训：状态文件被门房删=只读回退史归零=活锁闸失效）
        assertTrue(isBackfillStateFileName("index_heal_guard.json"))
        assertTrue(isBackfillStateFileName("index_heal_guard.json.tmp"))
    }

    // ---------------- R289b 错误分流（计划错误绝非损坏——健康对象零 DROP 红线） ----------------

    @Test
    fun `错误分流_计划与用法错误绝非损坏`() {
        // 行为（R290b V7 误报实锤铁律）：「no query solution」「no such index」等
        // 计划/用法错误在健康库上也会抛——分流必须恒 NON_DAMAGE，绝不进自愈
        for (msg in listOf(
            "no query solution", // V7 误报原文（无 WHERE+非覆盖 INDEXED BY，SQLite 3.32.2）
            "Error: no query solution",
            "no such index: idx_refs_fuid", // 索引缺席（后台未建/已 DROP）≠损坏
            "no such table: items",
            "no such column: foo",
            "near \"SELECT\": syntax error",
            "duplicate column name: ttlSec", // 迁移重入雷（R286 ensureMetaColumn 实锤）
            "database is locked",
            "disk I/O error (code 778)", // 保守方向：非损坏签名不进自愈（漏检=维持 R289 前现状）
            "",
        )) {
            assertEquals(
                "计划/用法错误绝不进自愈：$msg",
                IndexHealPolicy.DbErrorClass.NON_DAMAGE,
                IndexHealPolicy.classifyDbError(msg),
            )
        }
        assertEquals(IndexHealPolicy.DbErrorClass.NON_DAMAGE, IndexHealPolicy.classifyDbError(null))
    }

    @Test
    fun `错误分流_真损坏签名才进自愈`() {
        // 行为：malformed/corrupt/not a database 全系文案（含中断页/坏页遍历/头损坏）
        // =DAMAGE——大小写不敏感、安卓框架 code 后缀不影响
        for (msg in listOf(
            "database disk image is malformed",
            "database disk image is malformed (code 11)",
            "malformed database schema (idx_items_date) - invalid rootpage",
            "file is not a database",
            "file is not a database (code 26)",
            "database corruption at line 66841 of 6b5b774c",
            "Database corruption at page 123",
        )) {
            assertEquals(
                "真损坏签名必进自愈：$msg",
                IndexHealPolicy.DbErrorClass.DAMAGE,
                IndexHealPolicy.classifyDbError(msg),
            )
        }
    }

    // ---------------- R289b 穿刺形状锚（V7 误报根治病灶件） ----------------

    @Test
    fun `穿刺形状锚_覆盖列加前导列WHERE_绝无裸扫`() {
        // 行为（V7 实锤的安全形状铁律）：每条生成 SQL 必须
        //  ①SELECT rowid（索引隐式覆盖列=永不触表=规划器必可解）；
        //  ②WHERE 压该索引前导列（等值或恒真范围——绝不出现「无 WHERE 裸扫」形状）；
        //  ③INDEXED BY 锁该索引本体
        val specs = reflectProbeSpecs()
        assertEquals(9, specs.size) // items×4(date 双发算一)+tags×1+tag_items×2+refs×2
        val plans = IndexHealPolicy.indexProbePlans(specs, listOf(-1001L, -1002L))
        val plansNoSample = IndexHealPolicy.indexProbePlans(specs, emptyList())
        assertEquals(10, plans.size) // date 右叶双发
        assertEquals(10, plansNoSample.size)
        for ((name, sql) in plans + plansNoSample) {
            assertTrue("覆盖列=rowid 永不触表：$sql", sql.startsWith("SELECT rowid FROM "))
            assertTrue("INDEXED BY 锁本体：$sql", sql.contains(" INDEXED BY $name "))
            assertTrue("WHERE 前导列恒在场（裸扫形状=V7 误报根源，绝不复发）：$sql", sql.contains(" WHERE "))
            assertTrue("有界 LIMIT 1：$sql", sql.endsWith(" LIMIT 1"))
            val spec = specs.first { it.indexName == name }
            assertTrue("WHERE 必压前导列 ${spec.leadColumn}：$sql", sql.contains("WHERE ${spec.leadColumn}"))
            assertTrue("宿主表一致：$sql", sql.contains(" FROM ${spec.table} "))
        }
    }

    @Test
    fun `穿刺生成器_样本等值与恒真范围兜底_右叶双发`() {
        val specs = reflectProbeSpecs()
        // 有样本：chatId 前导索引走等值穿刺（样本序错开=覆盖不同频道域页）
        val withSample = IndexHealPolicy.indexProbePlans(specs, listOf(-1001L, -1002L)).toMap()
        assertTrue(withSample.getValue("idx_items_chat_msg").contains("WHERE chatId=-1001 LIMIT 1"))
        assertTrue(withSample.getValue("idx_items_album").contains("WHERE chatId=-1002 LIMIT 1"))
        assertTrue(withSample.getValue("idx_tag_items_chat").contains("WHERE chatId=-1001 LIMIT 1"))
        assertTrue(withSample.getValue("idx_refs_chat_msg").contains("WHERE chatId=-1001 LIMIT 1"))
        // 非 chatId 前导不吃样本：恒真范围兜底
        assertTrue(withSample.getValue("idx_tag_items_rowid").contains("WHERE rowid_ref>=0 LIMIT 1"))
        assertTrue(withSample.getValue("idx_items_fuid").contains("WHERE fileUniqueId>='' LIMIT 1"))
        assertTrue(withSample.getValue("idx_tags_tag").contains("WHERE tag>='' LIMIT 1"))
        assertTrue(withSample.getValue("idx_refs_fuid").contains("WHERE fuid>='' LIMIT 1"))
        // 无样本：chatId 前导走全符号恒真范围（chatId 可为负，下界=LONG_MIN+1）
        val bare = IndexHealPolicy.indexProbePlans(specs, emptyList()).toMap()
        assertTrue(bare.getValue("idx_items_chat_msg").contains("WHERE chatId>=-9223372036854775807 LIMIT 1"))
        // 时间索引右叶双发：范围+DESC（INDEX_PROBE_PER_INDEX=2 锚），且 DESC 发也带 WHERE
        val datePlans = IndexHealPolicy.indexProbePlans(specs, emptyList()).filter { it.first == "idx_items_date" }
        assertEquals(2, datePlans.size)
        assertTrue(datePlans[0].second.contains("WHERE dateEpochSec>=0 LIMIT 1"))
        assertTrue(datePlans[1].second.contains("WHERE dateEpochSec>=0 ORDER BY dateEpochSec DESC LIMIT 1"))
    }

    @Test
    fun `穿刺规格反射锚_前导列与索引DDL一致`() {
        // 行为：spec 的 leadColumn/table 必须=索引 DDL 的宿主表与前导列（防两处手写
        // 漂移=穿刺打偏）。DDL 真值=INDEX_DDL_BY_NAME + date/fuid 两专属单飞 DDL
        val specs = reflectProbeSpecs()
        val cls = DeepSearchIndex::class.java
        @Suppress("UNCHECKED_CAST")
        val ddlByName = cls.getDeclaredField("INDEX_DDL_BY_NAME").apply { isAccessible = true }
            .get(null) as Map<String, String>
        fun ddlOf(name: String): String = ddlByName[name]
            ?: cls.getDeclaredField(
                if (name == "idx_items_date") "CREATE_ITEMS_DATE_INDEX_SQL" else "CREATE_ITEMS_FUID_INDEX_SQL",
            ).apply { isAccessible = true }.get(null) as String
        val expected = setOf(
            "idx_items_chat_msg", "idx_items_album", "idx_items_date", "idx_items_fuid",
            "idx_tags_tag", "idx_tag_items_chat", "idx_tag_items_rowid",
            "idx_refs_chat_msg", "idx_refs_fuid",
        )
        assertEquals(expected, specs.map { it.indexName }.toSet())
        for (spec in specs) {
            val ddl = ddlOf(spec.indexName)
            assertTrue("宿主表一致：${spec.indexName}", ddl.contains(" ON ${spec.table}("))
            assertTrue("前导列一致：${spec.indexName} 前导=${spec.leadColumn}", ddl.contains(" ON ${spec.table}(${spec.leadColumn}"))
        }
    }

    /** 反射读 DeepSearchIndex.INDEX_PROBE_SPECS（形状锚用例共用） */
    @Suppress("UNCHECKED_CAST")
    private fun reflectProbeSpecs(): List<IndexHealPolicy.IndexProbeSpec> =
        DeepSearchIndex::class.java.getDeclaredField("INDEX_PROBE_SPECS").apply { isAccessible = true }
            .get(null) as List<IndexHealPolicy.IndexProbeSpec>

    // ---------------- R294 fts 轻损重灌源形态缝（r292 挂账收口） ----------------

    @Test
    fun `fts影子表后缀清单锚_强删连带清影子行`() {
        // 行为（F1 彩排实锤病灶）：writable_schema 强删 fts 虚表必须连带清影子行，
        // 否则环回 CREATE VIRTUAL TABLE 撞 fts_data already exists 必炸——清单=FTS5
        // 全部影子后缀，漂移（改序/漏项）即红
        assertEquals(
            listOf("_data", "_idx", "_config", "_docsize", "_content"),
            IndexHealPolicy.FTS5_SHADOW_SUFFIXES,
        )
    }

    @Test
    fun `重灌源判定_tokens列在场且有真词位才走快速SQL_否则换源现算`() {
        // 行为（形态缝铁律）：FTS5 形态库 items 从未建 tokens 列；列在但全空=旧版缝
        // 补建的污染列——两族都绝不允许走快速 SQL（灌 0 行宣布完成=空 fts 搜索静默
        // 全空）；只有「列在场 ∧ 有非空词位」的 LIKE 形态库走快速直灌
        assertEquals(
            IndexHealPolicy.FtsRefillSource.TOKENS_SQL,
            IndexHealPolicy.ftsRefillSource(tokensColumnPresent = true, tokensContentPresent = true),
        )
        assertEquals(
            IndexHealPolicy.FtsRefillSource.RECOMPUTE,
            IndexHealPolicy.ftsRefillSource(tokensColumnPresent = false, tokensContentPresent = false),
        )
        assertEquals(
            IndexHealPolicy.FtsRefillSource.RECOMPUTE,
            IndexHealPolicy.ftsRefillSource(tokensColumnPresent = true, tokensContentPresent = false),
        )
        assertEquals(
            IndexHealPolicy.FtsRefillSource.RECOMPUTE,
            IndexHealPolicy.ftsRefillSource(tokensColumnPresent = false, tokensContentPresent = true),
        )
    }

    @Test
    fun `fts空表缝探测_四条件矩阵_零误报零漏报`() {
        // 行为：fts5 可用 ∧ fts 在场 ∧ items 非空 ∧ fts 空 = 重灌待办（四条件缺一不命中）
        // 命中=fts5 可用+fts 在场+items 非空+fts 空（轻损修复后进程死亡丢待办/旧版缝）
        assertTrue(IndexHealPolicy.ftsRefillNeeded(fts5Capable = true, ftsPresent = true, itemsNonempty = true, ftsEmpty = true))
        // 零误报矩阵：健康 FTS5 库（fts 非空）/全新库（items 空）/LIKE 形态（无 fts/fts5 不可用）
        assertFalse(IndexHealPolicy.ftsRefillNeeded(fts5Capable = true, ftsPresent = true, itemsNonempty = true, ftsEmpty = false))
        assertFalse(IndexHealPolicy.ftsRefillNeeded(fts5Capable = true, ftsPresent = true, itemsNonempty = false, ftsEmpty = true))
        assertFalse(IndexHealPolicy.ftsRefillNeeded(fts5Capable = true, ftsPresent = false, itemsNonempty = true, ftsEmpty = true))
        assertFalse(IndexHealPolicy.ftsRefillNeeded(fts5Capable = false, ftsPresent = false, itemsNonempty = true, ftsEmpty = true))
        assertFalse(IndexHealPolicy.ftsRefillNeeded(fts5Capable = false, ftsPresent = false, itemsNonempty = false, ftsEmpty = true))
    }
}
