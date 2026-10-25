package dev.tgfeed.core.feed

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * 搜索实体信誉分（R64 网关治理包·任务A）：按历史应答表现给"资源群秒回"的搜索实体打分，
 * liveAskEntities 的挑选序从"KIND_BOT 优先静态序"升级为"信誉分降序"。
 *
 * 记账口径（全部发生在 TdLibGateway.askSearchEntity / resolveLinkVideoItem 主动问答链）：
 *  - asked     被发词次数（关键词真实发出才记；预算耗尽/发送失败不记——没发出去不算拷问）；
 *  - answered  应答次数（监听窗口内有链接或文本回来；一轮多次变化只记首次）；
 *  - latMs     应答首条延迟累计毫秒（均值口径 = latMs/answered，日志展示用）；
 *  - resOk/resTotal 答案链接解析成功/总数——用户点击秒回答案链接后 GetMessageLinkInfo
 *    反解的终局成败：链接指向的消息或频道真实存在记成功，死链/已删记失败。
 *    归因靠进程内"链接→来源实体"映射（noteLinkSource 登记，noteResolveOutcome 一次性消费）。
 *
 * 信誉分（LinUCB 探索精神的轻量版，不过度设计）：
 *  score = [W_RESP]*应答率 + [W_RES]*解析率 + 探索项 [EXPLORE_C]/sqrt(1+asked)
 *  - 解析率无样本按 [RES_RATE_NEUTRAL] 中性处理（没被点过链接的实体不赏不罚）；
 *  - 探索项随发词次数衰减：新实体拿保底分防饿死（未知优先于已证差），
 *    老牌实体分数收敛到真实表现（已证好优先于未知）。
 *
 * 持久化 entity_reputation.json（filesDir 根目录，与 answer_store.json 同级）：
 * @Synchronized 公开方法 + @Volatile 内存缓存唯一事实源 + "临时文件+rename" 原子落盘 +
 * 变更阈值触发 flush，与 AnswerStore 全同构；老版本无此文件按空表重建，天然兼容。
 * 文件格式：{"entities": {"<chatId>": {"asked":0,"answered":0,"latMs":0,"resOk":0,"resTotal":0}}}
 * 链接→来源实体映射只存进程内不落盘（重启后未归因链接不记分，可接受）。
 */
class EntityReputation(private val dir: File) {

    /** 单实体累计表现（latMs 为首条应答延迟累计毫秒，均值口径见类注释） */
    private class Rep {
        var asked = 0
        var answered = 0
        var latMs = 0L
        var resOk = 0
        var resTotal = 0
    }

    private val file = File(dir, "entity_reputation.json")

    /** @Volatile 内存缓存（写时持锁变更）；null = 尚未加载（首访问 lazy 读盘） */
    @Volatile
    private var stateCache: HashMap<Long, Rep>? = null

    /** 距上次落盘以来的变更次数（flush 节流） */
    private var dirtyCount = 0

    /** 链接→来源实体 chatId（解析归因用；进程内 LRU，不落盘） */
    private val linkSource = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > LINK_SOURCE_MAX
    }

    // ---------------- 记账（秒回链调用） ----------------

    /** 发词成功记一次（预算耗尽/发送失败不记——没真发出去不算拷问过它） */
    @Synchronized
    fun noteAsked(chatId: Long) {
        loadLocked().getOrPut(chatId) { Rep() }.asked++
        markDirtyLocked()
    }

    /** 本轮首次应答记一次（latencyMs=发词到首条应答的墙钟延迟；一轮只记首次，由调用方保证） */
    @Synchronized
    fun noteAnswered(chatId: Long, latencyMs: Long) {
        val r = loadLocked().getOrPut(chatId) { Rep() }
        r.answered++
        r.latMs += latencyMs.coerceAtLeast(0L)
        markDirtyLocked()
    }

    /** 登记答案链接的来源实体（解析归因凭据；进程内映射，重启即弃） */
    fun noteLinkSource(link: String, chatId: Long) {
        if (link.isBlank()) return
        synchronized(linkSource) { linkSource[link] = chatId }
    }

    /**
     * 答案链接解析终局记账：ok=true=链接有效（消息级直达或频道主页——message==null 是
     * 频道链的正常语义，不算失败），false=死链/已删。一次性归因：同链接重复点击不重复记分。
     * 非秒回链登记的链接（映射未命中）静默忽略。
     */
    @Synchronized
    fun noteResolveOutcome(link: String, ok: Boolean) {
        val src = synchronized(linkSource) { linkSource.remove(link) } ?: return
        val r = loadLocked().getOrPut(src) { Rep() }
        r.resTotal++
        if (ok) r.resOk++
        markDirtyLocked()
        Log.d(TAG, "解析归因：chatId=$src ${if (ok) "成功" else "失败"}（累计 ${r.resOk}/${r.resTotal}）")
    }

    // ---------------- 打分（liveAskEntities 挑选序） ----------------

    /** 信誉分：应答率与解析率加权 + 随发词次数衰减的探索项（口径见类注释） */
    @Synchronized
    fun score(chatId: Long): Double {
        val r = loadLocked()[chatId] ?: return SCORE_NEW_ENTITY
        val respRate = if (r.asked > 0) r.answered.toDouble() / r.asked else 0.0
        val resRate = if (r.resTotal > 0) r.resOk.toDouble() / r.resTotal else RES_RATE_NEUTRAL
        return W_RESP * respRate + W_RES * resRate + EXPLORE_C / sqrt(1.0 + r.asked)
    }

    /**
     * 结构性日志片段（不带实体标题——日志纪律：不落真实频道名）：
     * chatId=… 分=… 发词/应答=…/… 解析=…/… 首应=…ms
     */
    @Synchronized
    fun scoreLine(chatId: Long): String {
        val r = loadLocked()[chatId]
        if (r == null) {
            return "chatId=$chatId 分=${fmt(SCORE_NEW_ENTITY)} 发词/应答=0/0 解析=0/0 首应=-"
        }
        val avgLat = if (r.answered > 0) "${r.latMs / r.answered}ms" else "-"
        return "chatId=$chatId 分=${fmt(score(chatId))} 发词/应答=${r.asked}/${r.answered} " +
            "解析=${r.resOk}/${r.resTotal} 首应=$avgLat"
    }

    /** 立即落盘（收割轮末/进程退出前兜底调用）；从未加载/变更时空转（绝不凭空建文件） */
    @Synchronized
    fun flush() {
        if (stateCache == null) return
        flushLocked()
    }

    // ---------------- 内部（与 AnswerStore 同构） ----------------

    @Synchronized
    private fun loadLocked(): HashMap<Long, Rep> {
        stateCache?.let { return it }
        val st = HashMap<Long, Rep>()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONObject("entities")?.let { ents ->
                for (k in ents.keys()) {
                    runCatching {
                        val o = ents.getJSONObject(k)
                        val r = Rep()
                        r.asked = o.optInt("asked")
                        r.answered = o.optInt("answered")
                        r.latMs = o.optLong("latMs")
                        r.resOk = o.optInt("resOk")
                        r.resTotal = o.optInt("resTotal")
                        st[k.toLong()] = r
                    }
                }
            }
        }.onFailure { Log.w(TAG, "entity_reputation.json 读取失败（按空表重建）: ${it.message}") }
        stateCache = st
        return st
    }

    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked()
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（AnswerStore 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        runCatching {
            val ents = JSONObject()
            for ((k, r) in st) {
                ents.put(
                    k.toString(),
                    JSONObject()
                        .put("asked", r.asked)
                        .put("answered", r.answered)
                        .put("latMs", r.latMs)
                        .put("resOk", r.resOk)
                        .put("resTotal", r.resTotal),
                )
            }
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(JSONObject().put("entities", ents).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）：拷内容后删临时文件
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "entity_reputation.json 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    companion object {
        private const val TAG = "EntityReputation"

        /**
         * 变更多少次自动落盘。秒回是低频事件（一轮搜索仅 3 次发词记账），阈值大了
         * 内存账撑不到落盘就随进程死掉（实测阈值 10 时连续数轮搜索都不落盘）——
         * 定 3 = 单轮搜索即落一次，丢数据窗口一轮以内
         */
        private const val FLUSH_AFTER_MUTATIONS = 3

        /** 链接→来源实体映射进程内存量硬顶（LRU 最久未用先淘汰；单轮秒回链接数十条量级） */
        private const val LINK_SOURCE_MAX = 500

        /** 应答率权重（秒回场景主信号：发词了回不回） */
        private const val W_RESP = 0.6

        /** 解析率权重（次信号：回的链接点不点得开） */
        private const val W_RES = 0.4

        /** 解析率无样本时的中性取值（没被点过链接不赏不罚） */
        private const val RES_RATE_NEUTRAL = 0.5

        /** 探索项系数：score += EXPLORE_C/sqrt(1+asked)，随发词次数衰减（LinUCB 探索精神） */
        private const val EXPLORE_C = 0.5

        /** 新实体探索保底分 = 中性解析率分 + 满格探索项（未知优于已证差、劣于已证好） */
        private const val SCORE_NEW_ENTITY = W_RES * RES_RATE_NEUTRAL + EXPLORE_C

        private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
    }
}
