package dev.tgfeed.core.feed

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R65 热词榜（热词源）存储：hot_words.json——对配置的主搜索 bot 走菜单协议
 * （/start 主菜单 → 点"热搜"按钮（callback m=hotkey）→ 三子榜 tab × 各榜翻页）
 * 收割到的全网热搜词表（soso 功能地图 §1.3 借鉴点⑤落地）。
 *
 * 定位与纪律（AnswerStore 同款风格）：
 *  - GroupHarvester 是**单写入方**（收割侧每日一轮，见 maybeHarvestHotWords）；
 *    协议走查在 TdLibGateway.harvestHotWordBoard（只返回词表，不碰存储），
 *    词表由 GroupHarvester 拿到后写入本表——网关不持存储、本表不发请求，职责互锁；
 *  - 消费侧只读：发现页热词区（DiscoverScreen）与"猜你想搜"（TasteRadar
 *    第四级原料）经 [freshWords] 取新鲜词表；过期/从未收割返回空表，
 *    由调用方走各自既有口径兜底（"没有/过期用现有口径"）；
 *  - @Synchronized + @Volatile 内存缓存（进程内唯一事实源）+ "临时文件 + rename"
 *    原子落盘，与 AnswerStore/LocalStore 同口径；
 *  - 节流戳（lastAttemptTs）与词表同文件：**真正发起网络流程**即推进节流戳
 *    （成败都占当日额度，防"失败→下个轮询周期重试→再失败"的 flood 烧毁循环，
 *    与 TasteRadar 巡逻戳同纪律）；失败不更新词表（words/collectedAt 保持旧值），
 *    旧词表因 collectedAt 不动而继续服役——429/限流静默降级的语义落点。
 *
 * 文件格式：
 * ```json
 * {"words": ["词1", "词2"], "collectedAt": 0, "lastAttemptTs": 0}
 * ```
 * 旧版本/缺字段按空表 + 从未收割处理，天然兼容。
 */
class HotWordBoard(dir: File) {

    /** 词表快照：[words] 榜序（子榜 a→g→p、页序）去重后的词；[collectedAt] 收录时刻（毫秒，0 = 从未成功收录） */
    data class Snapshot(val words: List<String>, val collectedAt: Long)

    private class BoardState(
        val words: ArrayList<String> = ArrayList(),
        var collectedAt: Long = 0L,
        var lastAttemptTs: Long = 0L,
    )

    private val file = File(dir, "hot_words.json")

    /** @Volatile 内存缓存（写时同步重建引用）；null = 尚未加载（首访问 lazy 读盘） */
    @Volatile
    private var stateCache: BoardState? = null

    /** 上次收割尝试时刻（毫秒；0 = 从未尝试）。每日节流的判定依据 */
    @Synchronized
    fun lastAttemptTs(): Long = loadLocked().lastAttemptTs

    /**
     * 记录一次收割尝试（词表不动）：网关真正发起网络流程时回调本方法——
     * 失败也推进节流戳（旧词表因 collectedAt 不动而继续可用），同值/回拨不重写。
     */
    @Synchronized
    fun recordAttempt(ts: Long) {
        val st = loadLocked()
        if (ts <= st.lastAttemptTs) return
        st.lastAttemptTs = ts
        flushLocked()
    }

    /**
     * 收割成功落词：全量替换词表 + 收录时刻 + 推进节流戳，原子写。
     * 词表在网关侧已完成清洗（长度/链接/广告词闸，与 sanitizeKeyword 同口径），
     * 本层不再判词，只做 trim/去空白/去重；空表不落盘（空 = 降级信号，不是清空语义）。
     */
    @Synchronized
    fun saveWords(words: List<String>, ts: Long) {
        val clean = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        val st = loadLocked()
        st.words.clear()
        st.words.addAll(clean)
        st.collectedAt = ts
        if (ts > st.lastAttemptTs) st.lastAttemptTs = ts
        flushLocked()
    }

    /** 词表快照（只读拷贝，调用方改不动库内对象） */
    @Synchronized
    fun snapshot(): Snapshot {
        val st = loadLocked()
        return Snapshot(ArrayList(st.words), st.collectedAt)
    }

    /**
     * 新鲜词表（消费侧唯一入口）：收录时刻距现在 ≤ [ttlMs] 才返回词表，
     * 过期/从未收割返回空表——调用方据此回落既有口径兜底。
     */
    fun freshWords(ttlMs: Long = HOT_WORD_TTL_MS): List<String> {
        val s = snapshot()
        if (s.collectedAt <= 0L) return emptyList()
        if (System.currentTimeMillis() - s.collectedAt > ttlMs) return emptyList()
        return s.words
    }

    // ---------------- 落盘 ----------------

    @Synchronized
    private fun loadLocked(): BoardState {
        stateCache?.let { return it }
        val st = BoardState()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONArray("words")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optString(i).trim()
                    if (w.isNotEmpty()) st.words.add(w)
                }
            }
            st.collectedAt = root.optLong("collectedAt", 0L)
            st.lastAttemptTs = root.optLong("lastAttemptTs", 0L)
        }.onFailure { Log.w(TAG, "hot_words.json 读取失败（按空榜重建）: ${it.message}") }
        stateCache = st
        return st
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（与 AnswerStore/LocalStore 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        runCatching {
            val root = JSONObject()
            val arr = JSONArray()
            for (w in st.words) arr.put(w)
            root.put("words", arr)
            root.put("collectedAt", st.collectedAt)
            root.put("lastAttemptTs", st.lastAttemptTs)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）：拷内容后删临时文件
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "hot_words.json 落盘失败（下轮重试）: ${it.message}") }
    }

    companion object {
        private const val TAG = "HotWordBoard"

        /**
         * 词表新鲜度 TTL（3 天）：收割每日一轮，单日失败/限流不丢词源；
         * 超期未再收录（bot 协议变更/长期限流）自动判过期，消费侧回落既有口径
         */
        const val HOT_WORD_TTL_MS: Long = 3L * 24 * 3600 * 1000

        /** 每日收割节流窗口（22 小时，与 TasteRadar 巡逻戳同口径：允许自然漂移仍一天一次） */
        const val HARVEST_INTERVAL_MS: Long = 22L * 3600 * 1000
    }
}
