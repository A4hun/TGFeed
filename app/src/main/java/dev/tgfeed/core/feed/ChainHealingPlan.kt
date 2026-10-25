package dev.tgfeed.core.feed

import dev.tgfeed.core.td.ChainNoteRow
import dev.tgfeed.core.td.computeAdjacentNotes

// ---------------- R285 存量链式回填规划（纯函数——DB 行视图进、治疗计划出，零 IO 零 Android） ----------------

// TDLib messageId 的 2^20 序号间距事实由判定核 [dev.tgfeed.core.td.TD_MSG_ID_SEQ_SHIFT]
// 唯一持有（纪律§一：严禁第二份位移常量）——本规划件把原始 messageId 透传进核，
// 序号换算在核内统一完成。

/** R285 规划输入行（单频道 items 行视图；调用方按 messageId DESC 新→旧序喂入） */
internal class ChainPlanRow(
    val rowid: Long,
    val messageId: Long,
    val albumId: Long,
    val caption: String,
)

/**
 * R285 治疗计划行：
 *  - [viaChain2]=true：链②库内相册共享（同 chatId+albumId 组内有非空 caption 成员）
 *    ——[note] 留空（共享文案以写侧 collectAlbumRowsLocked 的库内重读为准，防读写
 *    窗口期 ingest 新到成员的漂移），[albumId] 携带组键；
 *  - [viaChain2]=false：链④库内邻近批注——[note]=批注正文（判定核产出）。
 *  - [memberCount]/[memberIndex]：CaptionParser 解析上下文（相册=组大小与组内
 *    messageId 升序位次；散媒体=（1,0)）。
 */
internal class ChainHealing(
    val rowid: Long,
    val albumId: Long,
    val note: String,
    val viaChain2: Boolean,
    val memberCount: Int,
    val memberIndex: Int,
)

/**
 * 存量链式回填规划（单频道一趟）：
 *  1. **链②**：组内有非空 caption 成员的相册 → 组内空 caption 成员列入治疗
 *    （与 collectAlbumRowsLocked 的「首个非空成员=共享文案」同源：规划只定组，
 *    文案写侧重读；隔断判定与判定核共享同一张组级证据表，②>④ 互斥天然成立）；
 *  2. **链④**：行视图映射后进 [computeAdjacentNotes] 严格相邻模式——
 *     空 caption 行=候选；**散媒体带文行**（albumId=0 且 [CaptionParser.hasNonTagText]，
 *     剥 #标签后仍有字母数字）映射为文字帖替身（库内无文字帖行——用户实锤形态
 *     「相册+统一文字批注」的批注帖是 MessageText 不入库，只能由带文散媒体代任
 *     批注源； ping 守卫由核内判定）；**带文相册成员**只服务本组链②=透明媒体；
 *     **纯标签行**（有字母数字但剥光即空）=透明媒体（不当源——m1 实锤跨帖污染）；
 *     无字母数字行映射为文字帖喂核内 ping 守卫（重置批注态，与网关语义一致）。
 *  幂等：救回行 caption 落库后不再满足「空 caption」前提，重跑零产出。
 */
internal fun planChainHealing(rows: List<ChainPlanRow>): List<ChainHealing> {
    if (rows.isEmpty()) return emptyList()
    // 组级证据与成员序（messageId 升序=TG 相册发送序=成员序，CaptionAlbumContext 同口径）
    val albumCaps = HashMap<Long, String>()
    val albumMembers = HashMap<Long, ArrayList<ChainPlanRow>>()
    for (r in rows) {
        if (r.albumId == 0L) continue
        albumMembers.getOrPut(r.albumId) { ArrayList(8) } += r // rows 为 DESC，反转后升序
    }
    for (members in albumMembers.values) {
        members.reverse()
        for (m in members) {
            if (m.caption.isNotBlank()) {
                // collectAlbumRowsLocked 同口径：组内首个非空（messageId 升序）=共享文案
                albumCaps.putIfAbsent(m.albumId, m.caption)
            }
        }
    }
    val out = ArrayList<ChainHealing>()
    // ① 链②：组内有共享证据 → 空成员列入治疗
    for ((albumId, members) in albumMembers) {
        if (!albumCaps.containsKey(albumId)) continue
        for ((idx, m) in members.withIndex()) {
            if (m.caption.isBlank()) {
                out += ChainHealing(m.rowid, albumId, "", viaChain2 = true, members.size, idx)
            }
        }
    }
    // ② 链④：判定核严格相邻模式（DB 无文字帖行，id 缺口=不可见消息，宁断勿错）
    // 行 id 透传原始 messageId——序号域换算在核内（TD_MSG_ID_SEQ_SHIFT 唯一出处）
    val kernelRows = rows.map { r ->
        when {
            r.caption.isBlank() -> ChainNoteRow(r.messageId, r.albumId, text = null, caption = "")
            r.albumId != 0L ->
                // 带文相册成员：透明媒体（只服务本组链②，不外溢批注、不重置批注态）
                ChainNoteRow(r.messageId, r.albumId, text = null, caption = r.caption)
            CaptionParser.hasNonTagText(r.caption) || !r.caption.any { it.isLetterOrDigit() } ->
                // 散媒体带文行 → 文字帖替身（ping 形态同样喂 text——核内守卫负责重置）
                ChainNoteRow(r.messageId, 0L, text = r.caption, caption = "")
            else ->
                // 纯标签行（有字母数字但剥标签即空）：透明媒体——不当批注源
                ChainNoteRow(r.messageId, 0L, text = null, caption = r.caption)
        }
    }
    val (notes, _) = computeAdjacentNotes(kernelRows, strictAdjacency = true)
    if (notes.isNotEmpty()) {
        val byId = HashMap<Long, ChainPlanRow>(rows.size)
        for (r in rows) byId[r.messageId] = r
        for ((msgId, note) in notes) {
            val r = byId[msgId] ?: continue
            val members = if (r.albumId != 0L) albumMembers[r.albumId] else null
            val (cnt, idx) = if (members != null) {
                var i = -1
                for (k in members.indices) if (members[k].rowid == r.rowid) { i = k; break }
                if (i >= 0) members.size to i else 1 to 0
            } else 1 to 0
            out += ChainHealing(r.rowid, r.albumId, note, viaChain2 = false, cnt, idx)
        }
        // ③ 链④→②接管（16416 实证抓出的形态缝：全空 10 人相册被链④按
        // ANNOTATE_MAX_MEDIA=6 上限只批到 6 名成员——这 6 名一落库即成组内共享
        // 载体，相册是完整内容单元（R219 形态A），批注既落一员即视为全组共享，
        // 余下空成员转链②写路径（collectAlbumRowsLocked 重读共享文案）收口，
        // 消除「④劈开相册半愈」形态）。只补未直接被④批注的空成员（去重）。
        val notedRowids = HashSet<Long>(out.size)
        for (h in out) if (!h.viaChain2) notedRowids += h.rowid
        val extendGroups = LinkedHashSet<Long>()
        for ((msgId, _) in notes) {
            val r = byId[msgId] ?: continue
            if (r.albumId != 0L) extendGroups += r.albumId
        }
        for (albumId in extendGroups) {
            val members = albumMembers[albumId] ?: continue
            for ((idx, m) in members.withIndex()) {
                if (m.caption.isBlank() && m.rowid !in notedRowids) {
                    out += ChainHealing(m.rowid, albumId, "", viaChain2 = true, members.size, idx)
                }
            }
        }
    }
    return out
}
