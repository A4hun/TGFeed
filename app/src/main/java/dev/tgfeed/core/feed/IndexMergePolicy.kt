package dev.tgfeed.core.feed

/**
 * R287 多设备索引合并/共享·判定纯函数（JVM 可单测，零 Android 依赖）。
 *
 * 场景（用户实锤）：同一账号在手机/平板/模拟器各爬各的=多倍配额消耗+索引互不相通。
 * 导出数据包在另一台设备「合并导入」时，两台各自爬的索引按 key（tg:chatId:messageId）
 * 与 fileUniqueId（内容指纹，R266 v11 语义）去重合并，冲突字段取信息更全的一侧。
 *
 * 裁决口径唯一出处（防多处私抄漂移；执行侧=IndexImportMerge）：
 *  - **链式非空胜**：文本字段空→非空胜；数值字段 0/-1=未知→非零胜；
 *    parsed_title NULL→非 NULL 胜；item_tags 取**并集**（标签是集合语义，并集=更全）；
 *  - **双非空异值**：caption/channelTitle 取长者（信息更全——TG caption 可被编辑，
 *    无编辑时刻可取，长度是「更全」的唯一本地证据；等长保本机=稳）；dateEpochSec
 *    双非零异值保本机（同级证据不动=稳，防跨设备时钟微差抖动）。
 *  - **meta 台账合并**（配额协同核心——爬过的标记随迁，对端不重爬）：
 *    cursor/sweepCursor=**并集覆盖感知**（[mergeCursorUnion]，见函数注释）；
 *    mediaPass=max（相位推进单调）；exhausted=OR（任一侧到底=并集到底——
 *    配额优先取舍：触顶终态（R255 W1 cap 出队）随并集可能过保完成态，
 *    过保方向=少爬=省配额，与任务目的一致）；lastHeadRefreshTs=max（头刷节流
 *    合并=同账号两地不重复烧头刷配额）；sampled/imported=OR；
 *    tier=本机行在恒保本机（档位是本机 B4 评估的政策产物，不随包走）；
 *    ttlSec=max（自毁是频道事实，保守向=宁多爬不丢时效内容）；
 *    dead/needDeepen=**触知型**：一侧「未碰过」（cursor=0∧mediaPass=-1∧exhausted=0
 *    ∧sampled=0）时取碰过侧的值；双侧都碰过 → dead=AND（一侧爬出内容即非死档）、
 *    needDeepen=AND（一侧已加深/证伪即不残留标记——省配额向）。
 *  - **删除零同步**（用户明示不做）：合并只增不删，任何本机行不因包缺席而被删。
 */
object IndexMergePolicy {

    // ---------------- 分类裁决（ITEMS 相位逐行） ----------------

    /** 来源 items 行的本机落点分类 */
    enum class MergeAction {
        /** 本机 items 同 key 已存在 → 字段合并（非空胜） */
        MERGE_ITEM,

        /** 本机 refs 同 key 已存在（该发布已被 R266 收编）→ 发布侧字段非空胜刷新 */
        MERGE_REF,

        /** key 双侧皆无但内容指纹本机已有 → 转 refs 挂本机 canonical（R266 同语义） */
        BECOME_REF,

        /** 全新内容 → 整行新插 */
        INSERT_NEW,
    }

    /**
     * 分类闸（优先级从上到下；[fuidItemRowid]/[fuidRefCanonRowid] 为本机按内容指纹
     * 点查命中行，0=未命中；指纹为空串时调用方恒传 0——空指纹无内容身份不查重）。
     */
    fun classify(
        localItemExists: Boolean,
        localRefExists: Boolean,
        fuidItemRowid: Long,
        fuidRefCanonRowid: Long,
    ): MergeAction = when {
        localItemExists -> MergeAction.MERGE_ITEM
        localRefExists -> MergeAction.MERGE_REF
        fuidItemRowid > 0L -> MergeAction.BECOME_REF
        fuidRefCanonRowid > 0L -> MergeAction.BECOME_REF
        else -> MergeAction.INSERT_NEW
    }

    /**
     * R12 冲突防御闸的跨设备变体：同指纹两行的内容一致性核对。
     * mediaType 严格相等；sizeBytes 任一侧为 0（旧数据未采到=未知，非冲突）放行，
     * 双非零必须相等。任何真分歧=指纹误判嫌疑，宁保留完整行也不错并（跳过收编、
     * 整行新插，留待人工核查——与 R266「宁不并不错并」同口径）。
     */
    fun contentCompatible(mediaTypeA: Int, sizeBytesA: Long, mediaTypeB: Int, sizeBytesB: Long): Boolean {
        if (mediaTypeA != mediaTypeB) return false
        if (sizeBytesA != 0L && sizeBytesB != 0L && sizeBytesA != sizeBytesB) return false
        return true
    }

    // ---------------- items 字段合并（链式非空胜） ----------------

    /** items 行一侧的字段视图（本机/来源同构；解析两列 null=未解析存量） */
    class ItemSide(
        val caption: String,
        val channelTitle: String,
        val mimeType: String,
        val albumId: Long,
        val coverFileId: Int,
        val fileId: Int,
        val durationSec: Int,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val dateEpochSec: Long,
        val parsedTitle: String?,
        val itemTags: List<String>?,
    )

    /** 合并产出（merged 字段全集 + 动作标记——执行侧据此决定 UPDATE/fts 重算/tags delta） */
    class MergedItem(
        val caption: String,
        val channelTitle: String,
        val mimeType: String,
        val albumId: Long,
        val coverFileId: Int,
        val fileId: Int,
        val durationSec: Int,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val dateEpochSec: Long,
        val parsedTitle: String?,
        val itemTags: List<String>?,
        /** caption 或 channelTitle 变了 → fts/tokens 词位必须重算（词位=二者同串配方） */
        val captionSideChanged: Boolean,
        /** item_tags 并集比本机原值多了标签 → tags/tag_items 增集 delta */
        val tagsGrew: Boolean,
        /** 任何字段有变 → 才发 UPDATE（全同零动作，省无效写） */
        val changed: Boolean,
    )

    /** 文本非空胜：本机空+对端非空→对端；双非空异值→长者胜，等长保本机 */
    private fun pickText(local: String, incoming: String): String = when {
        local.isEmpty() -> incoming
        incoming.isEmpty() -> local
        incoming.length > local.length -> incoming
        else -> local
    }

    /** 数值零=未知→非零胜；双非零异值保本机（同级证据不动=稳） */
    private fun pickLong(local: Long, incoming: Long): Long =
        if (local == 0L && incoming != 0L) incoming else local

    private fun pickInt(local: Int, incoming: Int): Int =
        if (local == 0 && incoming != 0) incoming else local

    /**
     * item_tags 并集（null=未解析语义保留：双侧 null→null；单侧 null→另一侧；
     * 双非 null→按原形精确串去重并集，本机序先、对端补尾）。
     * fold 级归一由执行侧 tags delta 聚合器同一把尺完成（applyTagDeltaBatchLocked
     * 内部 fold+首见原形保序），本层不引入 SearchTextKit 依赖（保 JVM 可测）。
     */
    fun unionItemTags(local: List<String>?, incoming: List<String>?): List<String>? {
        if (local == null) return incoming
        if (incoming == null) return local
        if (incoming.isEmpty()) return local
        if (local.isEmpty()) return incoming
        val out = LinkedHashSet<String>(local.size + incoming.size)
        out.addAll(local)
        out.addAll(incoming)
        return out.toList()
    }

    /** items 同 key 冲突行的字段合并（任务书：非空胜/取信息更全的一侧） */
    fun mergeItemFields(local: ItemSide, incoming: ItemSide): MergedItem {
        val caption = pickText(local.caption, incoming.caption)
        val channelTitle = pickText(local.channelTitle, incoming.channelTitle)
        val mimeType = if (local.mimeType.isEmpty()) incoming.mimeType else local.mimeType
        val albumId = pickLong(local.albumId, incoming.albumId)
        // coverFileId 未知值=-1（建表缺省），与 0/正值区分
        val coverFileId = if (local.coverFileId < 0 && incoming.coverFileId >= 0) incoming.coverFileId else local.coverFileId
        val fileId = pickInt(local.fileId, incoming.fileId)
        val durationSec = pickInt(local.durationSec, incoming.durationSec)
        val sizeBytes = pickLong(local.sizeBytes, incoming.sizeBytes)
        val width = pickInt(local.width, incoming.width)
        val height = pickInt(local.height, incoming.height)
        val dateEpochSec = pickLong(local.dateEpochSec, incoming.dateEpochSec)
        val parsedTitle = local.parsedTitle ?: incoming.parsedTitle
        val itemTags = unionItemTags(local.itemTags, incoming.itemTags)
        val captionSideChanged = caption != local.caption || channelTitle != local.channelTitle
        val tagsGrew = itemTags != null &&
            (local.itemTags == null || itemTags.size > local.itemTags.size)
        val changed = caption != local.caption || channelTitle != local.channelTitle ||
            mimeType != local.mimeType || albumId != local.albumId ||
            coverFileId != local.coverFileId || fileId != local.fileId ||
            durationSec != local.durationSec || sizeBytes != local.sizeBytes ||
            width != local.width || height != local.height ||
            dateEpochSec != local.dateEpochSec || parsedTitle != local.parsedTitle ||
            itemTags != local.itemTags
        return MergedItem(
            caption = caption, channelTitle = channelTitle, mimeType = mimeType,
            albumId = albumId, coverFileId = coverFileId, fileId = fileId,
            durationSec = durationSec, sizeBytes = sizeBytes, width = width, height = height,
            dateEpochSec = dateEpochSec, parsedTitle = parsedTitle, itemTags = itemTags,
            captionSideChanged = captionSideChanged, tagsGrew = tagsGrew, changed = changed,
        )
    }

    // ---------------- meta 台账合并（配额协同） ----------------

    /**
     * meta 行一侧（ttlSec 可空=来源库 v12 之前无此列；lastMsgSec 可空=v13 之前无此列
     * ——R286 新列，合并语义=max（与 lastHeadRefreshTs 同款最新胜）；tier 恒有值——
     * 本机行在时恒保本机档位，新行随迁对端值，故合并函数不需要知道对端有无 tier 列）。
     */
    class MetaSide(
        val cursor: Long,
        val exhausted: Boolean,
        val imported: Boolean,
        val mediaPass: Int,
        val needDeepen: Boolean,
        val sweepCursor: Long,
        val lastHeadRefreshTs: Long,
        val sampled: Boolean,
        val tier: Int,
        val dead: Boolean,
        val ttlSec: Int?,
        val lastMsgSec: Long? = null,
    )

    /** 「碰过」判定：有爬取痕迹（cursor/相位/完成态/采样任一非缺省）。未碰过的行不提供政策证据 */
    fun touched(m: MetaSide): Boolean =
        m.cursor > 0L || m.mediaPass >= 0 || m.exhausted || m.sampled

    /**
     * 游标并集覆盖裁决（cursor/sweepCursor 同一把尺）。messageId 游标语义：
     * **值越小=爬得越深**（「已覆盖到的最老 messageId」，向下翻页推进），0=未开始占位。
     *
     * 为什么不能无脑取 min：单侧游标声称的是「从该侧开爬时的频道顶部向下连续覆盖
     * 到 cursor」。两台设备开爬时点不同（频道在长），可能爬出**脱节区间**
     * （A=[顶A..3000]，B=[顶B..110000]，A 顶 < B 游标 → 中间空洞）。声称 min=3000
     * 会把空洞判成已覆盖且永不再爬（续爬从游标向下、头刷撞已知即停）。
     *
     * 裁决：
     *  - 任一侧 0=未开始 → 取另一侧（随迁语义，零成本）；
     *  - 任一侧已到底（exhausted）→ 取 min：到底方的完成态使 cursor 不再驱动
     *    续爬（调度只剩头刷），min 是最强诚实声明且空洞由到底方兜无
     *    （到底=从顶到底全扫，其区间覆盖到它开爬时的顶部）；
     *  - 双侧在爬：**交叠判据** 本机游标 ≤ 对端顶 且 对端游标 ≤ 本机顶
     *    （顶=该侧该频道 items 最大 messageId，索引点查 µs 级）——交叠则并集
     *    连续，取 min（更深侧，续爬零重扫）；脱节则取 max（保守声称：续爬把
     *    空洞与重叠段一起扫掉，重扫冲突页幂等零新增，代价是页请求不是空洞）。
     */
    fun mergeCursorUnion(
        localCursor: Long,
        incomingCursor: Long,
        localTopMsgId: Long,
        incomingTopMsgId: Long,
        eitherExhausted: Boolean,
    ): Long {
        if (localCursor == 0L) return incomingCursor
        if (incomingCursor == 0L) return localCursor
        if (eitherExhausted) return minOf(localCursor, incomingCursor)
        val overlap = localCursor <= incomingTopMsgId && incomingCursor <= localTopMsgId
        return if (overlap) minOf(localCursor, incomingCursor) else maxOf(localCursor, incomingCursor)
    }

    /** 触知型布尔合并：双碰→AND；单碰→取碰侧；双不碰→本机（双不碰=无双证明，保缺省） */
    private fun mergeTouchedFlag(local: Boolean, localTouched: Boolean, incoming: Boolean, incomingTouched: Boolean): Boolean =
        when {
            localTouched && incomingTouched -> local && incoming
            incomingTouched -> incoming
            else -> local
        }

    /**
     * meta 行合并（本机行已存在时；新行=对端整行随迁，不经本函数）。
     * [localTopMsgId]/[incomingTopMsgId]=双侧该频道 items 最大 messageId
     * （0=该侧无此频道条目→交叠判据自动保守化）。字段口径见类注释；
     * 返回行的 tier 恒=local.tier（档位本机政策产物不动）。
     */
    fun mergeMeta(
        local: MetaSide,
        incoming: MetaSide,
        localTopMsgId: Long,
        incomingTopMsgId: Long,
    ): MetaSide {
        val lt = touched(local)
        val it = touched(incoming)
        val eitherExhausted = local.exhausted || incoming.exhausted
        return MetaSide(
            cursor = mergeCursorUnion(local.cursor, incoming.cursor, localTopMsgId, incomingTopMsgId, eitherExhausted),
            exhausted = eitherExhausted,
            imported = local.imported || incoming.imported,
            mediaPass = maxOf(local.mediaPass, incoming.mediaPass),
            needDeepen = mergeTouchedFlag(local.needDeepen, lt, incoming.needDeepen, it),
            sweepCursor = mergeCursorUnion(local.sweepCursor, incoming.sweepCursor, localTopMsgId, incomingTopMsgId, eitherExhausted),
            lastHeadRefreshTs = maxOf(local.lastHeadRefreshTs, incoming.lastHeadRefreshTs),
            sampled = local.sampled || incoming.sampled,
            tier = local.tier,
            dead = mergeTouchedFlag(local.dead, lt, incoming.dead, it),
            ttlSec = maxOf(local.ttlSec ?: 0, incoming.ttlSec ?: 0),
            // R286 v13 新列（活跃度头刷新用）：max=最新胜（架构师转达口径，2026-10-21；
            // 双侧都遇不到此列（v<13 互导）→ null=列缺席，执行侧不写该列）
            lastMsgSec = if (local.lastMsgSec == null && incoming.lastMsgSec == null) {
                null
            } else {
                maxOf(local.lastMsgSec ?: 0L, incoming.lastMsgSec ?: 0L)
            },
        )
    }
}
