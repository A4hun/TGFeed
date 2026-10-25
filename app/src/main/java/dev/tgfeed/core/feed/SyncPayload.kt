package dev.tgfeed.core.feed

import org.json.JSONObject

/**
 * R300 多设备同步协议 V3 载荷编解码（纯件，JVM 单测锚）。
 *
 * 消息形态（Saved Messages 每域一条 marker 消息，超载时按 key 序分片为多片）：
 *   第 1 行 marker：`[TGFEED_SYNC_V3] <domainId> <i>/<n>`（i/n 从 1 起；单片恒 1/1）
 *   第 2 行 JSON 正文：
 *     {"device":"设备型号","v":3,"ts":<写入毫秒>,
 *      "a":{"<key>":<加入毫秒>},   // 集合型域的在册戳（favTs 语义）
 *      "r":{"<key>":<删除毫秒>},   // 删除墓碑
 *      "c":{"<chatId>":<游标>},    // 单调游标域（追更已读水位 lastSeenMessageId）
 *      "t":{"<chatId>":"<标题>"}}  // 展示元数据（订阅标题等；占位降级用）
 *
 * 铁律（design §4/§6）：
 *  - 载荷只搬 key+ts，绝不搬完整 VideoItem（对端按 key 从本机索引还原，缺则占位降级）；
 *  - key 含换行会破坏「marker 独占首行」协议——编码侧转义（\n→\\n），解码侧还原；
 *  - key 不落日志（§七脱敏扩令）——日志只出条数/域 id/字符数。
 */
object SyncPayload {

    /** V3 协议消息识别标记（首行前缀；与进度域 V2 的 [TGFEED_PROGRESS_V1] 并存互不干扰） */
    const val MARKER_PREFIX = "[TGFEED_SYNC_V3]"

    /** 协议版本号 */
    const val VERSION = 3

    /** 一个域的同步状态快照（某一片/全集的载荷本体） */
    data class DomainState(
        val added: Map<String, Long> = emptyMap(),
        val removed: Map<String, Long> = emptyMap(),
        val cursors: Map<String, Long> = emptyMap(),
        val titles: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = added.isEmpty() && removed.isEmpty() && cursors.isEmpty() && titles.isEmpty()
    }

    /** 一片消息的解析结果 */
    data class Shard(
        val domainId: String,
        val index: Int,   // 1..total
        val total: Int,
        val ts: Long,
        val state: DomainState,
    )

    // ---------------- key 转义（防 marker 首行污染；见类注释铁律） ----------------

    fun escapeKey(k: String): String =
        k.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    fun unescapeKey(k: String): String {
        if (!k.contains('\\')) return k
        val sb = StringBuilder(k.length)
        var i = 0
        while (i < k.length) {
            val c = k[i]
            if (c == '\\' && i + 1 < k.length) {
                when (k[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    // ---------------- 编码 ----------------

    /** marker 首行（domainId 只允许 [a-z0-9]+——域常数自己保证，这里防御性过滤） */
    fun markerLine(domainId: String, index: Int, total: Int): String {
        val d = domainId.filter { it in 'a'..'z' || it in '0'..'9' }
        return "$MARKER_PREFIX $d $index/$total"
    }

    /**
     * 组装一片消息全文：marker 首行 + JSON 次行。
     * [device] 设备型号（排查多设备来源用，同 ProgressSync 先例）。
     */
    fun encode(domainId: String, index: Int, total: Int, state: DomainState, ts: Long, device: String): String {
        val a = JSONObject()
        // 「a」保留 ts=0 条目：0=存量未知戳的存在声明（宁多勿丢的并集语义靠它跨设备传播）
        state.added.forEach { (k, v) -> if (v >= 0L) a.put(escapeKey(k), v) }
        val r = JSONObject()
        state.removed.forEach { (k, v) -> if (v > 0L) r.put(escapeKey(k), v) }
        val c = JSONObject()
        state.cursors.forEach { (k, v) -> if (v > 0L) c.put(escapeKey(k), v) }
        val t = JSONObject()
        state.titles.forEach { (k, v) -> if (v.isNotEmpty()) t.put(escapeKey(k), v.take(60)) }
        val body = JSONObject()
            .put("device", device)
            .put("v", VERSION)
            .put("ts", ts)
            .put("a", a)
            .put("r", r)
            .put("c", c)
            .put("t", t)
        return markerLine(domainId, index, total) + "\n" + body.toString()
    }

    // ---------------- 解码 ----------------

    /** 该文本是否 V3 同步消息（marker 独占首行） */
    fun isSyncMessage(text: String): Boolean = text.startsWith("$MARKER_PREFIX ")

    /**
     * 解析一片消息；marker 不合规/正文不是 V3 JSON 返回 null（坏消息跳过不炸）。
     */
    fun decode(text: String): Shard? {
        if (!isSyncMessage(text)) return null
        val firstLine = text.substringBefore('\n')
        val parts = firstLine.removePrefix("$MARKER_PREFIX ").trim().split(' ')
        if (parts.size != 2) return null
        val domainId = parts[0]
        val idxParts = parts[1].split('/')
        if (idxParts.size != 2) return null
        val index = idxParts[0].toIntOrNull() ?: return null
        val total = idxParts[1].toIntOrNull() ?: return null
        if (domainId.isEmpty() || index < 1 || total < 1 || index > total) return null
        val body = text.substringAfter('\n', "")
        if (body.isEmpty()) return null
        return runCatching {
            val root = JSONObject(body)
            if (root.optInt("v", 0) > VERSION) return null // 未来版本：不认不碰
            Shard(
                domainId = domainId,
                index = index,
                total = total,
                ts = root.optLong("ts", 0L),
                state = DomainState(
                    // 「a」保留 ts=0（未知戳存在声明）；「r」/「c」只收正戳
                    added = readTsMap(root.optJSONObject("a"), keepZero = true),
                    removed = readTsMap(root.optJSONObject("r"), keepZero = false),
                    cursors = readTsMap(root.optJSONObject("c"), keepZero = false),
                    titles = readTitleMap(root.optJSONObject("t")),
                ),
            )
        }.getOrNull()
    }

    private fun readTsMap(obj: JSONObject?, keepZero: Boolean): Map<String, Long> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Long>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optLong(k, -1L)
            if (k.isNotEmpty() && (v > 0L || (keepZero && v == 0L))) out[unescapeKey(k)] = v
        }
        return out
    }

    private fun readTitleMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (k.isNotEmpty() && v.isNotEmpty()) out[unescapeKey(k)] = v
        }
        return out
    }

    // ---------------- 快照分片（4096 字符硬顶兜底；diff 为主、快照超载才走到） ----------------

    /**
     * 把一个域的全量状态按 [maxChars] 贪心切成若干片（确定性：各表先按 key 升序排好再装片，
     * 多设备对同一状态切出同样的片；片内 JSON 开销按保守上界估重）。
     * 单片也走本函数（返回 1 片）——编码/解码两侧口径永远一致。
     */
    fun chunk(state: DomainState, maxChars: Int = SyncMergePolicy.MAX_PAYLOAD_CHARS): List<DomainState> {
        if (state.isEmpty) return emptyList()
        // 保守估重：转义后 key 最坏 ×2（JSON 转义再叠一层），数字戳 14 位，结构符 ~8 字符
        fun w(key: String, valueLen: Int): Int = escapeKey(key).length * 2 + valueLen + 20
        val entries = ArrayList<Pair<String, Pair<Long, String?>>>() // (kind+key) -> (ts, title)
        state.added.toSortedMap().forEach { (k, v) -> entries += "a$k" to (v to null) }
        state.removed.toSortedMap().forEach { (k, v) -> entries += "r$k" to (v to null) }
        state.cursors.toSortedMap().forEach { (k, v) -> entries += "c$k" to (v to null) }
        state.titles.toSortedMap().forEach { (k, v) -> entries += "t$k" to (0L to v) }
        val out = ArrayList<DomainState>()
        var a = LinkedHashMap<String, Long>()
        var r = LinkedHashMap<String, Long>()
        var c = LinkedHashMap<String, Long>()
        var t = LinkedHashMap<String, String>()
        var curChars = 80 // marker 行 + JSON 骨架余量
        fun flush() {
            if (a.isNotEmpty() || r.isNotEmpty() || c.isNotEmpty() || t.isNotEmpty()) {
                out += DomainState(a, r, c, t)
                a = LinkedHashMap(); r = LinkedHashMap(); c = LinkedHashMap(); t = LinkedHashMap()
                curChars = 80
            }
        }
        for ((kk, vt) in entries) {
            val kind = kk[0]
            val key = kk.substring(1)
            val weight = w(key, vt.second?.length ?: 14)
            if (curChars > 80 && curChars + weight > maxChars) flush()
            when (kind) {
                'a' -> a[key] = vt.first
                'r' -> r[key] = vt.first
                'c' -> c[key] = vt.first
                't' -> t[key] = vt.second ?: ""
            }
            curChars += weight
        }
        flush()
        return out
    }
}
