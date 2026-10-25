package dev.tgfeed.core.feed

/**
 * R294 P3 物理压实（显式保 rowid 重建换装）策略唯一出处（纯函数件，JVM 单测直测；
 * DeepSearchIndex 只做执行落形——AGENT-DISCIPLINE §一「判定收敛共享件」同族先例：
 * IndexHealPolicy/DeadChannelPolicy/IndexMergePolicy）。
 *
 * 病灶（R266+R289 双挂账收口）：存储合并批迁收编删行、evict 整频道删除、R289
 * writable_schema 强删坏对象——死行/孤儿页只进 freelist，**库文件永不自瘦**
 * （6GB 库越跑越肥）。裸 VACUUM 是成文红线（storage-merge-design §6.3：items 是
 * key TEXT PRIMARY KEY 的非 IPK 表，VACUUM 可重排 rowid → tag_items.rowid_ref /
 * repost_refs.canonRowid / fts 回表对齐全灭）。唯一合法压实=**显式保 rowid 重建
 * 换装**：暂存库重放 schema → 逐表 `INSERT INTO t(rowid,…) SELECT rowid,…`（rowid
 * 显式随行=对齐链全保）→ 对账 → 改名换装（R254 tmp+rename 门/R289 坏档改名先例）。
 * orphan 页随换装自然消灭（不被任何表引用的页根本不会被拷进新库）。
 *
 * 形态缝联动（R294 任务②同包）：FTS5 形态库的 fts 是 contentless 虚表——
 * 不可拷贝不可 rebuild——暂存库只建空 fts，换装后由 R289 后台重灌的
 * RECOMPUTE 臂（insert 同源配方现算词位）收口；LIKE 形态库的 tokens 列随
 * items 行整列拷走，零额外动作。
 */
object IndexCompactPolicy {

    // ---------------- 闸（值不值得/能不能压——判定唯一出处） ----------------

    /**
     * 压实收益下限：自由页字节 ≥64MB **且**占库比 ≥10% 才值得换装（小碎片靠
     * freelist 自然复用即可——压实是 GB 级拷贝的重活，收益太薄不值得打断写入）。
     */
    const val MIN_COMPACT_FREE_BYTES = 64L * 1024 * 1024

    /** 见 [MIN_COMPACT_FREE_BYTES]（占比腿） */
    const val MIN_COMPACT_FREE_RATIO = 0.10

    /** 磁盘安全余量：可用空间 ≥ 净数据量 × 1.1（暂存库与主库瞬态双占，防压到一半写满盘） */
    const val DISK_MARGIN = 1.1

    /** 值不值得压（设置页预估口径与执行前硬闸同源） */
    fun shouldCompact(freeBytes: Long, totalBytes: Long): Boolean =
        freeBytes >= MIN_COMPACT_FREE_BYTES && totalBytes > 0 &&
            freeBytes.toDouble() / totalBytes.toDouble() >= MIN_COMPACT_FREE_RATIO

    /** 磁盘够不够（usable=目录可用字节；live=净数据字节=(pageCount-freePages)*pageSize） */
    fun enoughDisk(usableBytes: Long, liveBytes: Long): Boolean =
        liveBytes >= 0L && usableBytes.toDouble() >= liveBytes.toDouble() * DISK_MARGIN

    // ---------------- 拷贝计划（显式保 rowid；列清单真值唯一出处） ----------------

    /**
     * 单表拷贝计划：表名+显式列清单（**不含 rowid**——[copySql] 恒把 rowid 放首位
     * 显式拷，非 IPK 表的 rowid 稳定性由此保证；item_ocr 的 rowid 是真实列故在清单内）。
     */
    data class TableCopyPlan(val table: String, val columns: List<String>)

    /** items 全字段（=ITEM_COLS 18 列；单测与 CREATE_ITEMS_SQL 正则对拍防漂移） */
    val ITEMS_COLUMNS: List<String> = listOf(
        "key", "chatId", "messageId", "albumId", "mediaType", "coverFileId", "caption",
        "channelTitle", "fileId", "fileUniqueId", "mimeType", "durationSec", "sizeBytes",
        "width", "height", "dateEpochSec", "parsed_title", "item_tags",
    )

    /** meta 全字段（13 列；爬取进度字段同表搬迁——压实不改写任何语义） */
    val META_COLUMNS: List<String> = listOf(
        "chatId", "cursor", "exhausted", "imported", "mediaPass", "needDeepen", "sweepCursor",
        "lastHeadRefreshTs", "sampled", "tier", "dead", "ttlSec", "lastMsgSec",
    )

    /** tags 聚合账本全字段 */
    val TAGS_COLUMNS: List<String> = listOf("chatId", "tag", "display", "itemCount", "firstSeenSec", "lastSeenSec")

    /** tag_items 倒排全字段（rowid_ref 是 items.rowid 引用——随行保 rowid 是对齐生死线） */
    val TAG_ITEMS_COLUMNS: List<String> = listOf("tag", "rowid_ref", "chatId")

    /** repost_refs 别名表全字段（canonRowid 同理；R266 v11。表名与 DeepSearchIndex.REPOSTS_TABLE
     *  同源锚=IndexCompactPolicyTest 反射对拍——其 companion 私有，此处直写字面量） */
    val REPOST_REFS_COLUMNS: List<String> = listOf(
        "key", "chatId", "messageId", "albumId", "mediaType", "durationSec", "coverFileId",
        "channelTitle", "dateEpochSec", "fuid", "canonRowid", "caption",
    )

    /** item_ocr 全字段（rowid 是真实列=items.rowid 对齐锚，R292 v14） */
    val ITEM_OCR_COLUMNS: List<String> = listOf("rowid", "chatId", "text", "engineVer", "applied", "ts")

    /**
     * 拷贝计划（表序=拷贝序——items 大头先行，早失败早止损）：
     *  - [sourceHasTokens]：源库 items 带 tokens 列（LIKE 形态）→ items 拷贝带该列；
     *    FTS5 形态=false→不带（fts 换装后 R289 重灌 RECOMPUTE 臂收口）；
     *  - [hasReposts]/[hasItemOcr]：源库表在场探测（v11/v14 前老库直通跳过——
     *    hasRepostsTable 同款先例）。
     */
    fun copyPlan(sourceHasTokens: Boolean, hasReposts: Boolean, hasItemOcr: Boolean): List<TableCopyPlan> {
        val out = ArrayList<TableCopyPlan>(6)
        out += TableCopyPlan("items", if (sourceHasTokens) ITEMS_COLUMNS + "tokens" else ITEMS_COLUMNS)
        out += TableCopyPlan("meta", META_COLUMNS)
        out += TableCopyPlan("tags", TAGS_COLUMNS)
        out += TableCopyPlan("tag_items", TAG_ITEMS_COLUMNS)
        if (hasReposts) out += TableCopyPlan("repost_refs", REPOST_REFS_COLUMNS)
        if (hasItemOcr) out += TableCopyPlan("item_ocr", ITEM_OCR_COLUMNS)
        return out
    }

    /** 单表拷贝 SQL（唯一出处）：rowid 恒首位显式保（非 IPK 表 rowid 重排红线由此绕开） */
    fun copySql(plan: TableCopyPlan, sourceSchema: String = "src"): String {
        val cols = plan.columns.joinToString(",")
        return "INSERT INTO ${plan.table}(rowid,$cols) SELECT rowid,$cols FROM $sourceSchema.${plan.table}"
    }

    // ---------------- 文件命名与作用域锚 ----------------

    /** 压实暂存库名（deepindex 目录；一次性件——成败都删，门房只删 .json 天然不碰） */
    const val COMPACT_STAGING_NAME = "search.db.compact-tmp"

    /** 换装前旧库备份名（换装成功+重开探针过后即删——压实本意=回收，旧档不留） */
    const val PRECOMPACT_BACKUP_NAME = "search.db.precompact"

    /**
     * 压实操作域锚（红线——与 [IndexHealPolicy.isHealScopePath] 同族）：允许触碰的
     * 相对路径仅限 search.db 三件套/暂存件/备份件（各含 -wal/-shm）；prefs//store//
     * galaxy//tdlib/ 及一切其他路径恒 false。单测锚定，防未来改动静默扩域。
     */
    fun isCompactScopePath(relPath: String): Boolean {
        val base = relPath.substringAfterLast('/')
        for (stem in listOf("search.db", COMPACT_STAGING_NAME, PRECOMPACT_BACKUP_NAME)) {
            if (base == stem || base == "$stem-wal" || base == "$stem-shm") return true
        }
        return false
    }

    // ---------------- 对账口径（换装前 staging↔src 逐表比对，唯一出处） ----------------

    /**
     * 单表行集指纹 SQL：COUNT/SUM(rowid)/MAX(rowid) 三元组（拷贝保真判据——
     * 行数+rowid 集合强信号；SUM 对 7.4M 行 × rowid ≤ 2.7e13 远在 INTEGER64 内）。
     */
    fun tableChecksumSql(table: String, schemaPrefix: String = ""): String =
        "SELECT COUNT(*), COALESCE(SUM(rowid),0), COALESCE(MAX(rowid),0) FROM $schemaPrefix$table"

    /**
     * rowid 引用悬空计数 SQL（tag_items/item_ocr 指 items.rowid、repost_refs.canonRowid
     * 同指——压实后必须与原库同值（健康库恒 0；原库既有悬空如实保留=逐行保真不洗数据））。
     * LEFT JOIN rowid 点查形态（B-tree 主键逐行穿透，非笛卡尔）。
     */
    fun danglingCountSql(table: String, refColumn: String, schemaPrefix: String = ""): String =
        "SELECT COUNT(*) FROM $schemaPrefix$table LEFT JOIN ${schemaPrefix}items " +
            "ON ${schemaPrefix}items.rowid = $schemaPrefix$table.$refColumn " +
            "WHERE ${schemaPrefix}items.rowid IS NULL"
}
