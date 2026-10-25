package dev.tgfeed.core.td

// ---------------- R285 链④批注判定核（纯函数行视图——网关与 DB 存量回填同一实现） ----------------

/**
 * TDLib messageId 的序号位数：messageId = 消息序号 << 20（相邻消息 id 差 2^20，
 * R285 快照 742 万行 0 例外实锤——r285_notes.md m4/m5）。核内一切相邻判定
 * （seed 跨页接续、严格相邻闸）一律先 >> 本值落回序号域再比——026dae3 实锤：
 * 拿原始 id 差比 1..7 恒假=跨页批注接续永不生效。**唯一出处**（网关与 DB 路径
 * 共用本核，严禁各写一份位移）。
 */
internal const val TD_MSG_ID_SEQ_SHIFT = 20

/**
 * 链④批注判定核的行视图（[computeAdjacentNotes] 的输入单元）：
 *  - 网关路径：TdApi.Message 映射——MessageText → [text]=正文；媒体 → [caption]=
 *    mediaCaptionOf（非媒体内容 caption 空串，与旧 buildAdjacentNotes 内联口径一致）；
 *  - DB 存量路径（R285 链式回填）：items 行映射——空 caption 行=候选；散媒体带文行
 *    （剥 #标签后仍有字母数字正文，CaptionParser.hasNonTagText 口径）→ [text]=caption
 *    替身（库内无文字帖行，只能由带文散媒体代任批注源——近似口径如实注释）；带文
 *    相册成员/纯标签行=透明媒体（不当源、不重置）。
 *  - [id]：两路径统一传 **TDLib 原始 messageId**（核内自行落序号域）。
 */
internal class ChainNoteRow(
    val id: Long,
    val albumId: Long,
    val text: String?,
    val caption: String,
)

/**
 * 链④批注判定核（R285 从 TdLibGateway.buildAdjacentNotes 抽出的纯函数实现——
 * 「先发一批媒体（caption 空），再发一条文字说明」：文字帖（数组中更靠前=更新）
 * 关联给紧随的连续空 caption 媒体，产出 id → 批注正文）。
 *
 * 行为契约（与抽核前 buildAdjacentNotes 逐项一致，CaptionOverridePriorityTest 锚定）：
 *  - ping 帖守卫（R219）：纯提醒帖（无字母数字正文的 🔔/🔥/分割线）不进批注位；
 *  - 相册隔断（R219）：链②命中的成员（相册有共享 caption）清批注态——带自身
 *    caption 的相册是完整内容单元，批注不得穿透过界串到上一批内容。判定用
 *    [albumCapsExtra] 垫底 + 本段新见的合并表（与调用方喂给链②的表同源）；
 *  - 带 caption 的媒体行透明：不批注、不重置、不占计数（批注只发给空 caption 行）；
 *  - 跨页接续：[seedNote] 仅当与本段首条**序号**相邻（026dae3：双方 >> [TD_MSG_ID_SEQ_SHIFT]
 *    落序号域后差 1..ANNOTATE_MAX_MEDIA+1）才作数；
 *  - 计数上界：一条文字帖最多关联 [ANNOTATE_MAX_MEDIA] 条，遇下一条文字帖重起批。
 *
 * R285 新增 [strictAdjacency] 模式（默认关=网关语义不变）：候选与上一条（批注源或
 * 上一个被批注者）id 差必须==1，否则批注链诚实断裂（清批注态）——DB 存量路径专用：
 * 库内无文字帖行，id 出现缺口=中间隔着不可见消息（可能正是真批注帖），跨缺口批注
 * 语义不可证（快照实测：无闸时同一 caption 跨帖污染几十行）。宁漏勿错。
 *
 * 返回 Pair(id → 批注正文, 本段结束时的活跃批注状态)；页尾状态由调用方喂给下一段/
 * 写回 tailNoteStore，无活跃批注时为 null。
 */
internal fun computeAdjacentNotes(
    rows: List<ChainNoteRow>,
    seedNote: TdLibGateway.TailNote? = null,
    albumCapsExtra: Map<Long, String> = emptyMap(),
    strictAdjacency: Boolean = false,
): Pair<Map<Long, String>, TdLibGateway.TailNote?> {
    if (rows.isEmpty()) return emptyMap<Long, String>() to null
    // 链②相册共享表（外部缓存垫底 + 本段新见覆盖）：链②证据源，同时是"批注不得
    // 穿透完整相册"的隔断判定表——与调用方喂给 TitleChainContext.albumCaps 的表同源
    val albumCaps = HashMap<Long, String>()
    albumCaps.putAll(albumCapsExtra)
    for (r in rows) {
        if (r.albumId == 0L) continue
        if (r.caption.isNotBlank()) albumCaps[r.albumId] = r.caption
    }
    // 形态B：批注帖关联（数组新→旧，文字在前媒体在后）；跨页 seed 先做 id 邻接校验
    var pendingText: String? = null
    var pendingMsgId = 0L
    var pendingCount = 0
    var lastId = 0L // 严格模式：上一条成功接续的 id（批注源或上一个被批注者）
    val head = rows.first()
    if (seedNote != null &&
        // 026dae3 实锤修复（随抽核沉入本核，两路径同享）：TDLib messageId=序号<<20
        // ——原始 id 差比 1..7 恒假=跨页批注接续永不生效。先落序号域再比。
        ((seedNote.textMsgId shr TD_MSG_ID_SEQ_SHIFT) - (head.id shr TD_MSG_ID_SEQ_SHIFT)) in 1L..(ANNOTATE_MAX_MEDIA + 1L)
    ) {
        pendingText = seedNote.text
        pendingMsgId = seedNote.textMsgId
        pendingCount = seedNote.used
        lastId = seedNote.textMsgId
    }
    val out = HashMap<Long, String>()
    for (r in rows) {
        val text = r.text
        if (text != null) {
            // R219 ping 帖守卫：纯提醒帖（🔔/🔥/分割线——无任何字母数字正文的）不进
            // 批注位。频道常把 ping 帖顶在相册帖后面（"有更新了"），旧版它会被当形态B
            // 批注写进空 caption 相册成员，相册自己的正文标题反被顶掉——用户实锤：
            // 视频标题位只剩一个铃铛图标、没有文字。
            val t = text.trim()
            pendingText = if (t.isNotEmpty() && t.any { it.isLetterOrDigit() }) t else null
            pendingMsgId = r.id
            pendingCount = 0
            lastId = r.id
        } else if (r.caption.isBlank()) {
            // 链④只收"无链②证据"的成员（相册共享 caption 在链序 ②>④ 天然优先——
            // R219 形态A>形态B 的优先级裁决由链吸收，不再建表抢先）；链②命中的成员
            // 清批注态——带自身 caption 的相册是完整内容单元，隔断它上方文字帖对
            // 更旧媒体的批注（防批注穿透过界串到上一批内容）
            when {
                r.albumId != 0L && albumCaps[r.albumId] != null -> {
                    pendingText = null
                    pendingMsgId = 0L
                    pendingCount = 0
                }
                pendingText != null && pendingCount < ANNOTATE_MAX_MEDIA &&
                    (!strictAdjacency ||
                        (lastId shr TD_MSG_ID_SEQ_SHIFT) - (r.id shr TD_MSG_ID_SEQ_SHIFT) == 1L) -> {
                    out[r.id] = pendingText!!
                    pendingCount++
                    lastId = r.id
                }
                strictAdjacency && pendingText != null &&
                    (lastId shr TD_MSG_ID_SEQ_SHIFT) - (r.id shr TD_MSG_ID_SEQ_SHIFT) != 1L -> {
                    // 严格模式：候选与上一条 id 不相邻=中间隔着不可见消息（库内无文字帖
                    // 行，缺口里可能正是真批注帖）——批注链诚实断裂，宁漏勿错
                    pendingText = null
                    pendingMsgId = 0L
                    pendingCount = 0
                }
            }
        }
        // 带 caption 的媒体行：透明（不批注、不重置、不占计数）——网关语义原样
    }
    val tail = pendingText?.let { TdLibGateway.TailNote(pendingMsgId, it, pendingCount) }
    return out to tail
}
