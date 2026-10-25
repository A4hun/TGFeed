package dev.tgfeed.core.feed

/**
 * R289 索引库损坏自愈：策略唯一出处（纯函数件，JVM 单测直测；判定/状态机/口径
 * 全部收口此处，DeepSearchIndex 只做执行落形——AGENT-DISCIPLINE §一「类问题收敛
 * 成共享件」同族先例：DeadChannelPolicy/CrawlThroughputPolicy/IndexMergePolicy）。
 *
 * 病灶背景（用户令）：断电/秒杀写坏 search.db 时，现状=openDb 打开即抛异常、
 * 调用方 runCatching 全兜底成空结果——深搜/爬取全瘫且**永不自愈**（靠祈祷）。
 * 本件给出三层的判定真值：
 *  1. **有界检测**：openDb 同步路径只跑毫秒级抽样探针（6GB 库全量 quick_check
 *     是分钟级红线，绝不进首开路径）——关键表 COUNT+均布 rowid 采样页+
 *     二级索引 INDEXED BY 穿刺，病灶落点即分级原料。R289b 误报根治（R290b V7
 *     实锤：无 WHERE+非覆盖 INDEXED BY 在 Android SQLite 3.32.2 恒抛
 *     no query solution→健康索引被误 DROP）：穿刺 SQL 唯一生成口
 *     [indexProbePlans]（覆盖列 rowid+WHERE 前导列安全形状），错误一律过
 *     [classifyDbError] 分流——计划/用法错误绝非损坏，只有真损坏签名进自愈；
 *  2. **自愈分级**：轻损（索引/衍生表坏——可重建）→ 重建受损对象；
 *     重损（主表 items/meta 坏）→ 备份坏档→重建空库→meta 打捞（爬取进度字段
 *     复位、花名册与调度提示保留）→ 爬虫自然回填。**用户数据（收藏/历史/设置）
 *     绝不在自愈范围**——它们不在 search.db（LocalStore=store/、设置=prefs/、
 *     R254 成品缓存=galaxy/，本件操作域只限 deepindex/search.db 三件套与
 *     corrupt 备份件）；
 *  3. **防线**：损坏计数+连续损坏回退只读（防「重建→又坏→再重建」烧盘活锁）。
 */
object IndexHealPolicy {

    // ---------------- 损坏分级 ----------------

    /**
     * 探针层（病灶落点）：探针按层推进，任一层抛 SQLite 异常即定位到该层。
     * 落点即分级原料（[classify]）——主表/模式层坏=重损，索引/衍生层坏=轻损。
     */
    enum class ProbeStage {
        /** sqlite_master 模式层（含打开即炸/首条 PRAGMA 即炸的头损坏） */
        SCHEMA,

        /** meta 表（爬取游标账本——主表之一） */
        META,

        /** items 表体（MAX(rowid)+均布采样页点查） */
        ITEMS_BODY,

        /** 二级索引（INDEXED BY 穿刺——索引页坏但表体好） */
        SECONDARY_INDEX,

        /** 衍生层：tags/tag_items（可从 items 重聚）/repost_refs/fts（可重建） */
        DERIVED,
    }

    /** 损坏分级 */
    enum class DamageClass {
        NONE,

        /** 轻损：索引/衍生层坏——重建受损对象即可（表体数据零损失） */
        LIGHT_INDEX,

        /** 重损：主表（items/meta）或模式层坏——备份坏档+重建空库+打捞 */
        HEAVY,
    }

    /** 自愈动作（[decide] 输出） */
    enum class HealAction {
        NONE,

        /** 轻损修复：重建受损索引/衍生表（大表索引转后台单飞） */
        REBUILD_OBJECTS,

        /** 重损修复：备份坏档+重建空库+meta 打捞+爬虫自然回填 */
        HEAVY_REBUILD,

        /** 只读回退：连续损坏达顶——不再重建，OPEN_READONLY 读多少算多少 */
        READ_ONLY,
    }

    /** 病灶层 → 损坏级（唯一出处） */
    fun classify(stage: ProbeStage): DamageClass = when (stage) {
        ProbeStage.SCHEMA, ProbeStage.META, ProbeStage.ITEMS_BODY -> DamageClass.HEAVY
        ProbeStage.SECONDARY_INDEX, ProbeStage.DERIVED -> DamageClass.LIGHT_INDEX
    }

    // ---------------- 有界探针口径 ----------------

    /** items 表体均布采样点数（每点 rowid 点查 3~4 页；16 点≈64 页，毫秒级） */
    const val ITEMS_SAMPLE_POINTS = 16

    /** 每张二级索引的穿刺点查数（根→叶一次穿透即覆盖该索引数层页） */
    const val INDEX_PROBE_PER_INDEX = 2

    /**
     * 大表索引同步重建红线：索引缺失且表行数上界（MAX(rowid) 估计）超此值时，
     * **绝不**在 openDb 同步路径 CREATE INDEX（分钟级长事务，首开秒级红线，
     * R202 v7/R266 v11 同款纪律）——转后台单飞重建。空库/小库照旧同步瞬时建。
     */
    const val SYNC_INDEX_BUILD_MAX_ROWS = 200_000L

    /**
     * items 均布采样 rowid 集（纯函数）：[1, maxRowid] 均分 samples+1 段取内点。
     * maxRowid<=0（空表）→ 空数组（无页可采=直通）。采样是**点查**（B-tree
     * 根→叶穿透），成本与库大小无关——6GB 库的安全来自「点查」而非「少查」。
     */
    fun sampleRowids(maxRowid: Long, samples: Int = ITEMS_SAMPLE_POINTS): LongArray {
        if (maxRowid <= 0L || samples <= 0) return LongArray(0)
        return LongArray(samples) { k -> maxRowid * (k + 1) / (samples + 1) }
            .distinct()
            .toLongArray()
    }

    // ---------------- R289b 探针形状与错误分流（误报根治病灶件） ----------------

    /**
     * SQLite 错误分流（R290b V7 误报实锤后的铁律）：只有真损坏签名才允许进自愈。
     * 「no query solution」「no such index」等是**计划/用法错误**（规划器拒用
     * INDEXED BY 做非覆盖全扫、对象缺席），健康库上也会抛——当损坏处理就是
     * 「DROP 健康索引+24h 只读锁死」的事故链起点。
     */
    enum class DbErrorClass {
        /** 真损坏签名（malformed/corrupt/文件头非库）——唯一允许进自愈的类 */
        DAMAGE,

        /** 计划错误/用法错误/未知错误——绝不触发自愈（宁可按原契约上抛/探针跳过） */
        NON_DAMAGE,
    }

    /** 真损坏文案签名（小写包含匹配；SQLite/安卓框架全系版本口径——含中断页/坏页遍历） */
    private val DAMAGE_MESSAGE_SIGNATURES = listOf(
        "malformed", // database disk image is malformed / malformed database schema
        "corrupt", // database corruption ... / SQLITE_CORRUPT 文案族
        "not a database", // 文件头损坏（SQLITE_NOTADB：file is not a database）
    )

    /**
     * 错误文案分流（唯一出处）：命中真损坏签名=DAMAGE；null/计划错误/未知文案=
     * NON_DAMAGE（保守方向——探针只读不修复，漏检代价=维持 R289 前「靠祈祷」现状，
     * 远低于误报 DROP 健康对象的代价）。
     */
    fun classifyDbError(message: String?): DbErrorClass {
        val m = message?.lowercase(java.util.Locale.US) ?: return DbErrorClass.NON_DAMAGE
        return if (DAMAGE_MESSAGE_SIGNATURES.any { it in m }) DbErrorClass.DAMAGE else DbErrorClass.NON_DAMAGE
    }

    /**
     * L3 索引穿刺规格（清单真值在 DeepSearchIndex.INDEX_PROBE_SPECS；SQL 生成唯
     * 一出处=[indexProbePlans]）。R289b 安全形状铁律（R290b V7 实锤：无 WHERE+非覆盖
     * 列的 INDEXED BY 在 Android SQLite 3.32.2 恒抛 no query solution——健康索引被
     * 误 DROP；PC 新版 sqlite3 该形状合法=彩排盲区）：
     *  ①SELECT 只取 rowid——rowid 是每张索引的隐式覆盖列，**永不触表**=规划器
     *    必可解（覆盖扫描形状 3.32.2 真机静库矩阵实测合法）；
     *  ②WHERE 恒压前导列——有 meta 样本走等值穿刺，无样本走恒真范围下界，
     *    规划器必有范围/等值扫描解（双保险，专席修法建议①落形）。
     */
    data class IndexProbeSpec(
        /** 索引名（sqlite_master 锚） */
        val indexName: String,
        /** 宿主表名 */
        val table: String,
        /** 索引前导列名（WHERE 压它=范围/等值扫描必走该索引本体） */
        val leadColumn: String,
        /** 无样本兜底的恒真范围下界字面量（"0"=非负整数列；"''"=文本列；LONG_MIN+1=全符号整数列） */
        val leadLowLiteral: String,
        /** 前导列是否吃 meta chatId 样本（chatId 前导的索引=true，样本在=等值穿刺更贴真实访问） */
        val usesChatSample: Boolean = false,
        /** chatId 样本序号（不同索引错开样本点=覆盖更多频道域页） */
        val chatSampleOrdinal: Int = 0,
        /** 是否追加右叶穿刺（时间索引双发：左叶范围+右叶 DESC——INDEX_PROBE_PER_INDEX=2 锚） */
        val probeRightLeaf: Boolean = false,
    )

    /**
     * L3 穿刺 SQL 生成（唯一出处）：每条=「SELECT rowid（覆盖）+ WHERE 前导列 +
     * INDEXED BY」安全形状。[chatIdSamples]=L1 meta 层顺手取的样本（空=全新/空库，
     * 走恒真范围兜底形状——照样覆盖+前导列，规划器必可解）。
     */
    fun indexProbePlans(specs: List<IndexProbeSpec>, chatIdSamples: List<Long>): List<Pair<String, String>> {
        val plans = ArrayList<Pair<String, String>>(specs.size + 1)
        for (spec in specs) {
            val sample = if (spec.usesChatSample) chatIdSamples.getOrNull(spec.chatSampleOrdinal) else null
            val where = if (sample != null) {
                "${spec.leadColumn}=$sample"
            } else {
                "${spec.leadColumn}>=${spec.leadLowLiteral}"
            }
            plans += spec.indexName to
                "SELECT rowid FROM ${spec.table} INDEXED BY ${spec.indexName} WHERE $where LIMIT 1"
            if (spec.probeRightLeaf) {
                plans += spec.indexName to
                    "SELECT rowid FROM ${spec.table} INDEXED BY ${spec.indexName} WHERE $where ORDER BY ${spec.leadColumn} DESC LIMIT 1"
            }
        }
        return plans
    }

    // ---------------- 守卫状态机（损坏计数 + 只读回退，防反复重建活锁） ----------------

    /**
     * 守卫状态（index_heal_guard.json 往返；落盘字段名=属性名直写）。
     * 文件必须存活于库重建之外（search.db 是被重建对象，计数不能存库里）。
     */
    data class GuardState(
        /** 连续损坏次数（健康衰减窗内未清零的累计；见 [HEALTHY_DECAY_MS]） */
        val consecutiveCorruptions: Int = 0,
        /** 历史损坏总次数（观测口径，不参与判定） */
        val totalCorruptions: Int = 0,
        /** 上次损坏墙钟毫秒 */
        val lastCorruptionAtMs: Long = 0L,
        /** 上次自愈完成墙钟毫秒（观测口径） */
        val lastHealAtMs: Long = 0L,
        /** 上次健康打开墙钟毫秒（探针通过） */
        val lastHealthyAtMs: Long = 0L,
        /**
         * 只读回退截止墙钟毫秒（0=非只读）。>0 即「曾经进过只读」——健康打开
         * 成功才清零（[onHealthy]）；只读窗过期后的重试失败直接续窗（不重新
         * 计 1/2/3——重试都失败说明损坏是持续性的，每日最多 1 次重建尝试）。
         */
        val readOnlyUntilMs: Long = 0L,
    )

    /** 连续损坏达此次数 → 回退只读（防反复重建活锁的扳机） */
    const val READONLY_TRIGGER_CONSECUTIVE = 3

    /** 只读回退冷却窗：24h（窗内只读不重建；窗后允许单次重试自愈） */
    const val READONLY_COOLDOWN_MS = 24L * 3600_000L

    /** 健康衰减窗：距上次损坏超 6h 的再损坏算新一轮（偶发单次损坏不累积） */
    const val HEALTHY_DECAY_MS = 6L * 3600_000L

    /** 当前是否应只读直通（唯一判定口） */
    fun shouldEnterReadOnly(guard: GuardState, nowMs: Long): Boolean =
        nowMs < guard.readOnlyUntilMs

    /**
     * 损坏事件状态转移（探针失败/打开即炸时调用一次）：
     *  - 健康衰减：距上次损坏 >[HEALTHY_DECAY_MS] 且不在只读史中 → 连续计数从 1
     *    重新起算（偶发损坏不累积成只读）；
     *  - 只读史中（readOnlyUntilMs>0，即只读窗后重试再败）→ 连续计数照涨不衰减
     *    ——本次必续只读窗（每日最多 1 次重建尝试，零活锁）；
     *  - 连续计数 ≥[READONLY_TRIGGER_CONSECUTIVE] → 只读窗续到 now+冷却窗。
     */
    fun onCorruption(guard: GuardState, nowMs: Long): GuardState {
        val decayed = guard.readOnlyUntilMs <= 0L &&
            nowMs - guard.lastCorruptionAtMs > HEALTHY_DECAY_MS
        val consec = if (decayed) 1 else guard.consecutiveCorruptions + 1
        return guard.copy(
            consecutiveCorruptions = consec,
            totalCorruptions = guard.totalCorruptions + 1,
            lastCorruptionAtMs = nowMs,
            readOnlyUntilMs = if (consec >= READONLY_TRIGGER_CONSECUTIVE) {
                nowMs + READONLY_COOLDOWN_MS
            } else {
                guard.readOnlyUntilMs
            },
        )
    }

    /**
     * 健康事件状态转移（打开+探针全过时调用一次）：健康衰减窗外 → 连续计数与
     * 只读史清零（库已稳定健康，偶发史一笔勾销；硬件恢复后第一次健康打开即
     * 自动解除只读）；窗内只记 lastHealthyAtMs 不动计数（保守，防抖动清零）。
     */
    fun onHealthy(guard: GuardState, nowMs: Long): GuardState =
        if (nowMs - guard.lastCorruptionAtMs > HEALTHY_DECAY_MS) {
            guard.copy(
                consecutiveCorruptions = 0,
                lastHealthyAtMs = nowMs,
                readOnlyUntilMs = 0L,
            )
        } else {
            guard.copy(lastHealthyAtMs = nowMs)
        }

    /** 自愈完成打点（观测口径；轻/重修复落盘后调用） */
    fun onHealed(guard: GuardState, nowMs: Long): GuardState =
        guard.copy(lastHealAtMs = nowMs)

    /**
     * 损坏级 × 守卫状态 → 自愈动作（openDb 唯一裁决口）。
     * 只读判定先于分级：该只读时无论轻重都只读（不再重建）。
     */
    fun decide(damage: DamageClass, guardAfterCorruption: GuardState, nowMs: Long): HealAction {
        if (damage == DamageClass.NONE) return HealAction.NONE
        if (shouldEnterReadOnly(guardAfterCorruption, nowMs)) return HealAction.READ_ONLY
        return when (damage) {
            DamageClass.LIGHT_INDEX -> HealAction.REBUILD_OBJECTS
            DamageClass.HEAVY -> HealAction.HEAVY_REBUILD
            DamageClass.NONE -> HealAction.NONE
        }
    }

    // ---------------- R294 fts 轻损重灌源形态缝（R292 挂账收口；r294_notes 偏差申报在案） ----------------

    /**
     * FTS5 虚表影子表后缀全集（fts_data/fts_idx/fts_config/fts_docsize/fts_content）。
     * R294 F1 彩排实锤：writable_schema 强删 fts 虚表只删主行——影子行残留会让环回
     * CREATE VIRTUAL TABLE 撞「table 'fts_data' already exists」必炸（修复失败→计数
     * 涨→三坏只读的事故链起点）。强删路径必须连带清影子行（孤儿页残留由 R294 P3
     * 压实收口——两挂账同根同源）。清单锚定，防未来影子族扩列漏删。
     */
    val FTS5_SHADOW_SUFFIXES: List<String> = listOf("_data", "_idx", "_config", "_docsize", "_content")

    /**
     * fts 重灌源形态：FTS5 形态库的 items 表**从未建 tokens 列**（ensureTokensColumn
     * 只在 LIKE 兜底路径跑）——旧重灌 `INSERT INTO fts SELECT rowid,tokens FROM items`
     * 在该形态必产空 fts，叠加内存态待办丢失（进程死亡）→ 下次 openDb useFts5 置真
     * +空 fts=**搜索静默全空**（r292 挂账实锤链）。
     */
    enum class FtsRefillSource {
        /** items.tokens 列直灌（LIKE 形态库——列由 LIKE 兜底路径建且随写入填充真词位） */
        TOKENS_SQL,

        /** 词位现算回灌（FTS5 形态库——insert 同源配方从 caption/channelTitle/OCR 现算） */
        RECOMPUTE,
    }

    /**
     * 重灌源判定（唯一出处）：tokens 列在场**且**有真词位内容=快速 SQL 灌；
     * 列缺席（FTS5 形态）或列在但全空（旧版缝补建的空列）=换源现算——
     * 绝不允许「快速 SQL 灌 0 行然后宣布完成」的空 fts 结局。
     */
    fun ftsRefillSource(tokensColumnPresent: Boolean, tokensContentPresent: Boolean): FtsRefillSource =
        if (tokensColumnPresent && tokensContentPresent) FtsRefillSource.TOKENS_SQL else FtsRefillSource.RECOMPUTE

    /**
     * fts 空表缝探测（唯一出处，openDb 毫秒级）：fts5 可用 ∧ fts 表在场 ∧
     * items 非空 ∧ fts 空 = 重灌待办（轻损修复后进程死亡丢内存待办/旧版空 fts 缝/
     * LIKE 库跨机迁到 FTS5 设备的形态升级——三族同缝，本探针一并收口）。
     * 全新库（items 空）与 LIKE 形态（无 fts 表/fts5 不可用）天然不命中零误报。
     */
    fun ftsRefillNeeded(fts5Capable: Boolean, ftsPresent: Boolean, itemsNonempty: Boolean, ftsEmpty: Boolean): Boolean =
        fts5Capable && ftsPresent && itemsNonempty && ftsEmpty

    // ---------------- 重损修复口径 ----------------

    /**
     * 坏档备份名（UTC 时间戳，恒定格式保测试确定性）：search.db.corrupt-yyyyMMdd-HHmmss。
     * 配套 -wal/-shm 同后缀改名（WAL 残留会被当新库日志重放，BackupManager 同口径）。
     */
    fun corruptBackupName(dbName: String, nowMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return "$dbName.corrupt-${fmt.format(java.util.Date(nowMs))}"
    }

    /**
     * 坏档备份保留份数（只留最新 1 份，旧的删——索引库 GB 级体积，备份堆积
     * 会把磁盘挤爆制造二次事故；最新一份足够人工抢救通道）。
     */
    const val MAX_CORRUPT_BACKUPS = 1

    /**
     * meta 打捞列白名单（重损重建时从坏档能救的列）：chatId（花名册）+ tier
     * （用户偏好信号）+ ttlSec/lastMsgSec（调度提示）+ sampled（覆盖口径诚实）。
     * **爬取进度字段全部复位缺省**（cursor/exhausted/mediaPass/sweepCursor/
     * imported/dead/needDeepen）——空库挂 exhausted=1=永不再爬的假死，必须复位
     * 让爬虫自然回填。列级存在性由调用方 PRAGMA table_info 探测（v9 快照无
     * ttlSec/lastMsgSec/dead——探测缺席即跳过，ensureMetaColumn 同款纪律）。
     */
    val SALVAGE_META_COLUMNS: List<String> = listOf("chatId", "tier", "ttlSec", "lastMsgSec", "sampled")

    /**
     * 自愈操作域锚（防线——用户数据绝不在自愈范围）：允许触碰的库目录相对路径
     * 仅限 search.db 三件套与 corrupt 备份件；prefs//store//galaxy//tdlib/ 及一切
     * 其他路径恒 false。单测锚定，防未来改动静默扩域。
     */
    fun isHealScopePath(relPath: String): Boolean {
        val base = relPath.substringAfterLast('/')
        if (!base.startsWith("search.db")) return false
        return base == "search.db" || base == "search.db-wal" || base == "search.db-shm" ||
            base.startsWith("search.db.corrupt-")
    }
}
