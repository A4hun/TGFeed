package dev.tgfeed.core.td

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * R184 resolve-once 缓存：username → chatId 持久化（filesDir/resolve_once.json）。
 *
 * 物理依据：SearchPublicChat 的副作用不只是解析——它同时在 TDLib 本地**建立会话**。
 * 会话建立后 GetChat(chatId) 恒命中（毫秒级本地读，不属于搜索族账号桶）。所以
 * 「同一用户名解析过一次」之后的一切重解析（深链复验/答案行点击/收割白名单/广场
 * 查找）都可以用 GetChat 直通替代——**永不重解析**。
 *
 * 案发教训：既有 8 张解析缓存（answerLinkChatCache/botUserIdCache/botChatIdCache/
 * answerTitleDead 等）全是进程内内存态，验证轮的 force-stop 循环把缓存打回零，
 * 同一批频道名反复重解析（R182 深链复验/R183 开频道连开的实锤形态）。
 *
 * 语义细节：
 *  - key = 小写规范用户名（TG 用户名不区分大小写）；
 *  - 命中 = GetChat(chatId) 验证会话仍在（真频道不会消失；失效即移除条目走真解析）；
 *  - 记录 = 任意泳道解析成功后统一回写（发送口 searchPublicChatGuardedEx 内）；
 *  - 标题/类型以 GetChat 现值为准（缓存里的只做零网络兜底，天然自愈过期）；
 *  - 容量 [MAX_ENTRIES] 条按 atMs 淘汰最老；TTL [TTL_MS]（30 天——用户名迁移罕见，
 *    且 GetChat 验证兜住绝大多数失配）。
 *
 * 纯函数可测：构造注入 [clock]；[dir] 传 null 即纯内存态（单测用）。
 */
class ResolveOnceCache(
    private val dir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 容量硬顶（注入供单测小容量场景；生产用 [MAX_ENTRIES] 缺省） */
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /** 一条解析记录（title/isChannel 是解析当时的快照——查询方优先 GetChat 现值） */
    class Entry(val chatId: Long, val title: String, val isChannel: Boolean, val atMs: Long)

    /** 内存态（唯一事实源；null = 未加载）。访问全程 @Synchronized（低频操作，锁开销可忽略） */
    private var entries: LinkedHashMap<String, Entry>? = null

    private val file: File? get() = dir?.let { File(it, FILE_NAME) }

    // ---------------- 查询 / 记录 ----------------

    /**
     * 命中查询（纯读零网络）：返回缓存条目或 null。**命中不等于会话在**——调用方
     * （发送口内）随后 GetChat(chatId) 验证；失败即 [remove] 并走真解析。
     * TTL 外的条目按 miss 处理并惰性清理。
     */
    @Synchronized
    fun lookup(username: String): Entry? {
        val key = normalize(username) ?: return null
        val st = loadLocked()
        val e = st[key] ?: return null
        if (clock() - e.atMs > TTL_MS) {
            st.remove(key)
            flushLocked()
            return null
        }
        return e
    }

    /**
     * 记录一条成功解析（幂等：刷新 atMs——最近用过的更值得留）。超容量按 atMs
     * 淘汰最老。解析是超低频事件（每次一条真搜索），逐条落盘换取精确持久
     * （阈值节流会在重启时丢尾巴——假防守）；进程退出由网关 close() 兜底 flush。
     */
    @Synchronized
    fun record(username: String, chatId: Long, title: String, isChannel: Boolean) {
        val key = normalize(username) ?: return
        if (chatId == 0L) return
        val st = loadLocked()
        st[key] = Entry(chatId, title, isChannel, clock())
        while (st.size > maxEntries) {
            val oldest = st.entries.minByOrNull { it.value.atMs } ?: break
            st.remove(oldest.key)
        }
        flushLocked()
    }

    /** 失效移除（GetChat 验证失败/命中异频道的时刻调用，防死信缓存） */
    @Synchronized
    fun remove(username: String) {
        val key = normalize(username) ?: return
        val st = loadLocked()
        if (st.remove(key) != null) flushLocked()
    }

    /** 诊断：条目数 */
    @Synchronized
    fun size(): Int {
        val st = loadLocked()
        pruneTtlLocked(st)
        return st.size
    }

    /** 进程退出前兜底落盘（网关 close() 链调用；纯内存态空转） */
    @Synchronized
    fun flush() {
        if (dir == null || entries == null) return
        flushLocked()
    }

    // ---------------- 内部（持锁） ----------------

    private fun normalize(username: String): String? {
        val k = username.trim().removePrefix("@").lowercase()
        return k.takeIf { it.isNotEmpty() }
    }

    private fun loadLocked(): LinkedHashMap<String, Entry> {
        entries?.let { return it }
        val st = LinkedHashMap<String, Entry>()
        runCatching {
            val f = file ?: return@runCatching
            if (!f.isFile) return@runCatching
            val root = JSONObject(f.readText())
            val o = root.optJSONObject("entries") ?: return@runCatching
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                val chatId = e.optLong("chatId", 0L)
                if (chatId == 0L) continue
                st[k] = Entry(chatId, e.optString("title", ""), e.optBoolean("isChannel", false), e.optLong("atMs", 0L))
            }
        }.onFailure { Log.w(TAG, "resolve_once.json 读取失败（按空缓存重建）: ${it.message}") }
        entries = st
        if (st.isNotEmpty()) Log.i(TAG, "resolve-once 缓存已从磁盘恢复：${st.size} 条解析记录")
        pruneTtlLocked(st)
        return st
    }

    /** TTL 剪枝（惰性：加载/查询时；剪枝即落盘保持精确） */
    private fun pruneTtlLocked(st: LinkedHashMap<String, Entry>) {
        val cutoff = clock() - TTL_MS
        val before = st.size
        if (before > 0) {
            st.entries.removeAll { it.value.atMs < cutoff }
            if (st.size != before) flushLocked()
        }
    }

    /** 持锁落盘：tmp+rename 原子写（AnswerStore 同口径）；纯内存态空转 */
    private fun flushLocked() {
        val st = entries ?: return
        val f = file ?: return
        runCatching {
            val root = JSONObject().put("v", 1)
            val o = JSONObject()
            for ((k, e) in st) {
                o.put(
                    k,
                    JSONObject()
                        .put("chatId", e.chatId)
                        .put("title", e.title)
                        .put("isChannel", e.isChannel)
                        .put("atMs", e.atMs),
                )
            }
            root.put("entries", o)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "resolve_once.json 落盘失败（下轮重试）: ${it.message}") }
    }

    companion object {
        private const val TAG = "ResolveOnceCache"

        /** 落盘文件名（filesDir 根，与 flood_breaker.json 同级） */
        private const val FILE_NAME = "resolve_once.json"

        /** 容量硬顶（超出按 atMs 淘汰最老） */
        private const val MAX_ENTRIES = 2000

        /** TTL：30 天（用户名迁移罕见；GetChat 验证兜住失配） */
        private const val TTL_MS = 30L * 24 * 3600_000L
    }
}
