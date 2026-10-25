package dev.tgfeed.core.feed

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 广告频道分类器（P2 防守：**伪装资源频道**识别 + 黑名单自持）。
 *
 * 背景（用户洞察实锤）：有一批"伪装资源频道"——象征性发几部资源、本质是广告频道，
 * 靠买下知名资源搜索群的前几页坑位引流。共同点：
 *  1. 频道总资源数很少（几百个不到 1000）；
 *  2. 头部内容/频道名/简介含博彩广告词；
 *  3. 被收割到的链接带广告追踪参数的比例高（买量引流的标准打法）。
 *
 * 打三特征"广告嫌疑分"，≥[BLACKLIST_SCORE] 分判定广告频道进黑名单：
 *  - F1 规模嫌疑：图谱侧资源证据很少（入边 count 合计 < [MIN_RESOURCE_EVIDENCE]，
 *    且成员数未知或 < [SMALL_MEMBER_CAP]）——本地可观测的"资源总数 < 1000"代理
 *    （真资源大频道会被群友反复搜出/被互链转发，证据必然累积；深度索引条数不在
 *    本模块可达范围，用图谱证据 + 成员数兜底，风险见下）；
 *  - F2 名称嫌疑：频道标题（图谱 nodeTitle 供给）或链接用户名段命中
 *    [AdBlacklist.isSpam] 博彩词表（词表复用收割侧 P0 口径，一处维护两处消费）；
 *  - F3 链接广告占比：收割观测到指向该频道的链接 ≥[MIN_LINKS_FOR_RATIO] 条，
 *    且带广告参数的占比 ≥ 1/[SPAM_RATIO_DENOM]（广告链接在 recordLinks 已被
 *    AdBlacklist.isSpamLink 拦截不入库，这里把"被拦截次数"当嫌疑证据沉淀）。
 *
 * 单特征均不致死（新合法频道天然 F1=1；名字恰饭不代表频道是广告；个别广告链接
 * 可能是群友误发），≥2 才拉黑——宁可漏判不可误杀（误杀 = 真资源频道从发现页消失）。
 *
 * 黑名单剔除全链：
 *  - 写侧（本类）：拉黑后 [onHarvestedLink] 恒返回 true，收割不再为其建图谱边/答案；
 *  - 发现页读侧：DiscoveryRanker.rank 打分前经 [isBlockedRuntime] 硬过滤（静态快照，
 *    ranker 纯函数定位不变、零注入改动）；
 *  - 存量答案：已入库的历史答案不追溯清理，靠 AnswerStore LRU 自然淘汰（风险点，见交付）。
 *
 * 持久化 ad_channels.json（与 AnswerStore/DiscoveryGraph 同构：@Synchronized 公开方法、
 * @Volatile 内存缓存唯一事实源、变更阈值触发、"临时文件 + rename" 原子落盘）：
 * ```json
 * {"stats":   {"<key>": {"links": 0, "spamLinks": 0, "firstSeen": 0, "lastSeen": 0}},
 *  "blocked": {"<key>": {"ts": 0, "score": 2, "reason": "F1+F3"}}}
 * ```
 * key 口径：chatId 已知 = "c<chatId>"；仅知用户名 = "u<小写用户名>"（收割链接多为
 * t.me/<name> 形式，chatId 懒解析前只能按名记账；解析后两条身份各自独立计分，
 * 任一身份拉黑即剔除——发现页 Ranker 侧 id 与 username 双查）。
 */
class AdChannelClassifier(
    dir: File,
    /** 图谱只读证据源（F1/F2 取数）；可空——空时 F1 退化为"仅按收割观测链接数"判规模 */
    private val graph: DiscoveryGraph?,
) {

    /** 单频道观测统计（links/spamLinks 为收割累计观测，含被拦截未入库的广告链接） */
    private class ChannelStat(
        var links: Int,
        var spamLinks: Int,
        var firstSeen: Long,
        var lastSeen: Long,
    )

    /** 黑名单条目（reason 为特征组合串，诊断/日志用） */
    private class BlockedEntry(val ts: Long, val score: Int, val reason: String)

    private class StoreState(
        val stats: HashMap<String, ChannelStat> = HashMap(),
        val blocked: LinkedHashMap<String, BlockedEntry> = LinkedHashMap(),
    )

    private val file = File(dir, "ad_channels.json")

    /** @Volatile 内存缓存（写时同步重建引用）；null = 尚未加载（首次访问时 lazy 读盘） */
    @Volatile
    private var stateCache: StoreState? = null

    /** 距上次落盘以来的变更次数（flush 节流；拉黑动作本身立即落盘，不走阈值） */
    private var dirtyCount = 0

    init {
        // 构造即预热：把磁盘黑名单发布到运行时快照，收割器懒构造前 Ranker 也能查到存量
        runCatching { publishRuntimeSnapshot(loadLocked()) }
    }

    // ---------------- 收割入库闸（GroupHarvester.recordLinks 唯一调用点） ----------------

    /**
     * 收割到一条指向某频道的 t.me 链接时的统一闸门：记录嫌疑证据 + 判定是否丢弃。
     *
     * 返回 true = 该链接应丢弃（链接本身带广告参数，或目标频道已在黑名单）——
     * 完全覆盖并替代原 `AdBlacklist.isSpamLink(link)` 拦截语义，调用点替换一行即可。
     * 邀请链接/无法识别身份的链接不做统计（由调用方既有口径另行过滤）。
     */
    fun onHarvestedLink(link: String): Boolean {
        val spamLink = AdBlacklist.isSpamLink(link)
        val chatId = AnswerStore.chatIdFromLink(link)
        val username = AnswerStore.usernameFromLink(link).lowercase()
        if (chatId == 0L && username.isEmpty()) return spamLink // 无身份可记：维持原拦截语义
        if (isBlocked(chatId, username)) return true // 已拉黑：直接丢弃，不再计分（防反复刷日志）
        recordObservation(chatId, username, spamLink)
        return spamLink || isBlocked(chatId, username) // 本次观测可能刚把它顶进黑名单
    }

    /** 目标是否已进广告黑名单（chatId 与用户名双身份任一命中即真） */
    @Synchronized
    fun isBlocked(chatId: Long, username: String): Boolean {
        val st = loadLocked()
        if (chatId != 0L && st.blocked.containsKey("c$chatId")) return true
        if (username.isNotEmpty() && st.blocked.containsKey("u$username")) return true
        return false
    }

    /** 诊断快照（设置页/日志用）：观测频道数 / 黑名单数 */
    @Synchronized
    fun stats(): Pair<Int, Int> {
        val st = loadLocked()
        return st.stats.size to st.blocked.size
    }

    // ---------------- 三特征打分 ----------------

    /**
     * 记一次链接观测并重算该频道嫌疑分；越 [BLACKLIST_SCORE] 即入黑名单（立即落盘 +
     * 发布运行时快照）。幂等：已拉黑的身份由 [onHarvestedLink] 前置拦截，不会走到这。
     */
    @Synchronized
    private fun recordObservation(chatId: Long, username: String, spamLink: Boolean) {
        val now = System.currentTimeMillis()
        val st = loadLocked()
        val key = if (chatId != 0L) "c$chatId" else "u$username"
        val stat = st.stats.getOrPut(key) { ChannelStat(0, 0, now, now) }
        stat.links++
        if (spamLink) stat.spamLinks++
        stat.lastSeen = now

        // F1 规模嫌疑：图谱资源证据很少（"频道总资源数 < 1000"的本地代理）。
        // 图谱不可达/仅用户名身份时证据按 0 计——此时 F1 恒成立但单特征不致死，
        // 拉知名度较高的新频道不会被单特征误杀（需再叠 F2/F3 才拉黑）
        val evidence = if (chatId != 0L) graph?.evidenceCountOf(chatId) ?: 0 else 0
        val memberCount = if (chatId != 0L) graph?.memberCountOf(chatId) ?: -1 else -1
        val smallScale = evidence < MIN_RESOURCE_EVIDENCE &&
            (memberCount < 0 || memberCount < SMALL_MEMBER_CAP)

        // F2 名称嫌疑：图谱标题（已知时）或链接用户名段命中博彩广告词表
        val title = if (chatId != 0L) graph?.nodeTitle(chatId).orEmpty() else ""
        val nameHit = AdBlacklist.isSpam(title) || AdBlacklist.isSpam(username)

        // F3 链接广告占比：观测样本足够（≥3 条）且 ≥1/3 带广告参数
        val spamHeavy = stat.links >= MIN_LINKS_FOR_RATIO &&
            stat.spamLinks * SPAM_RATIO_DENOM >= stat.links

        var score = 0
        val reasons = StringBuilder()
        if (smallScale) { score++; reasons.append("F1") }
        if (nameHit) { score++; reasons.append(if (reasons.isEmpty()) "F2" else "+F2") }
        if (spamHeavy) { score++; reasons.append(if (reasons.isEmpty()) "F3" else "+F3") }

        if (score >= BLACKLIST_SCORE) {
            st.blocked[key] = BlockedEntry(now, score, reasons.toString())
            publishRuntimeSnapshot(st)
            flushLocked(st) // 拉黑是重要结论：立即落盘，不等阈值
            Log.w(TAG, "广告频道拉黑 key=$key score=$score ($reasons) 链接=${stat.links} 广告链接=${stat.spamLinks}")
        } else {
            markDirtyLocked()
        }
    }

    /** 把黑名单发布到进程内运行时快照（Ranker 静态查询的唯一数据源） */
    private fun publishRuntimeSnapshot(st: StoreState) {
        val ids = HashSet<Long>()
        val names = HashSet<String>()
        for (key in st.blocked.keys) {
            if (key.startsWith("c")) key.removePrefix("c").toLongOrNull()?.let { ids += it }
            else if (key.startsWith("u")) names += key.removePrefix("u")
        }
        runtimeBlockedIds = ids
        runtimeBlockedNames = names
    }

    // ---------------- 落盘 ----------------

    @Synchronized
    private fun loadLocked(): StoreState {
        stateCache?.let { return it }
        val st = StoreState()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONObject("stats")?.let { s ->
                for (k in s.keys()) {
                    runCatching {
                        val o = s.getJSONObject(k)
                        st.stats[k] = ChannelStat(
                            links = o.optInt("links"),
                            spamLinks = o.optInt("spamLinks"),
                            firstSeen = o.optLong("firstSeen"),
                            lastSeen = o.optLong("lastSeen"),
                        )
                    }
                }
            }
            root.optJSONObject("blocked")?.let { b ->
                for (k in b.keys()) {
                    runCatching {
                        val o = b.getJSONObject(k)
                        st.blocked[k] = BlockedEntry(
                            ts = o.optLong("ts"),
                            score = o.optInt("score", BLACKLIST_SCORE),
                            reason = o.optString("reason"),
                        )
                    }
                }
            }
        }.onFailure { Log.w(TAG, "ad_channels.json 读取失败（按空库重建）: ${it.message}") }
        stateCache = st
        return st
    }

    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked(stateCache ?: return)
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（AnswerStore 同口径） */
    private fun flushLocked(st: StoreState) {
        runCatching {
            val root = JSONObject()
            val statsObj = JSONObject()
            for ((k, s) in st.stats) {
                statsObj.put(k, JSONObject()
                    .put("links", s.links)
                    .put("spamLinks", s.spamLinks)
                    .put("firstSeen", s.firstSeen)
                    .put("lastSeen", s.lastSeen))
            }
            root.put("stats", statsObj)
            val blockedObj = JSONObject()
            for ((k, b) in st.blocked) {
                blockedObj.put(k, JSONObject()
                    .put("ts", b.ts)
                    .put("score", b.score)
                    .put("reason", b.reason))
            }
            root.put("blocked", blockedObj)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "ad_channels.json 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    companion object {
        private const val TAG = "AdChannelClassifier"

        /** 广告嫌疑分拉黑阈值：三特征中命中两条即判广告频道（单特征均不致死，防误杀） */
        private const val BLACKLIST_SCORE = 2

        /** F1：图谱入边证据合计低于此值视为"资源总数很少"（本地可观测代理阈值） */
        private const val MIN_RESOURCE_EVIDENCE = 3

        /** F1：成员数已知且低于此值视为小规模频道（"几百个不到 1000"口径；-1 未知不据此排除） */
        private const val SMALL_MEMBER_CAP = 1000

        /** F3：广告占比判定的最小观测样本数（<3 条链接不谈占比，防单次误伤） */
        private const val MIN_LINKS_FOR_RATIO = 3

        /** F3：广告链接占比 ≥ 1/3 记嫌疑（spamLinks × 3 ≥ links） */
        private const val SPAM_RATIO_DENOM = 3

        /** 变更多少次自动落盘（观测是高频动作，阈值比拉黑落盘大；拉黑本身立即落盘） */
        private const val FLUSH_AFTER_MUTATIONS = 15

        // ---- 运行时黑名单快照（发现页剔除全链的静态查询口） ----
        // Ranker 是纯函数打分器、由 FeedViewModel 每次刷新临时构造，无法注入本分类器实例；
        // 用进程内 @Volatile 快照做最小布线：分类器实例每次加载/变更黑名单时重写快照，
        // Ranker 打分前静态查询。快照只增不改语义由实例保证（黑名单无解除入口）。

        @Volatile
        private var runtimeBlockedIds: Set<Long> = emptySet()

        @Volatile
        private var runtimeBlockedNames: Set<String> = emptySet()

        /**
         * 发现页/搜索消费口的统一剔除判定：chatId 或用户名（小写比较）任一命中即剔除。
         * 分类器从未实例化过（收割未跑过）时快照为空 = 无剔除，天然向下兼容。
         */
        fun isBlockedRuntime(chatId: Long, username: String?): Boolean {
            if (chatId != 0L && chatId in runtimeBlockedIds) return true
            val name = username?.trim()?.removePrefix("@")?.lowercase().orEmpty()
            return name.isNotEmpty() && name in runtimeBlockedNames
        }
    }
}
