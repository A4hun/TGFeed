package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * flood 预算·滑动窗口限速器（P0 防守骨架 #1）。
 *
 * 语义：固定长度滑动窗口（默认 1 小时）内最多放行 [maxOpsPerHour] 次操作，
 * 每次操作 [tryAcquire] 记账；窗口外的旧时间戳自动驱逐。耗尽即拒绝——
 * 调用方必须按"静默降级"处理（收割跳过本轮 / 查询返回空），绝不重试轰炸。
 *
 * 车道隔离：收割车道与查询车道是独立实例（互不占预算），
 * 由 TdLibGateway（查询车道：searchViaBot）与 GroupHarvester（收割车道）分别持有。
 *
 * [maxOpsPerHour] 可变：调用方每轮从 AppPrefs 刷新，设置改动免重启生效。
 * 进程内内存实现（不落盘）：重启清零——防风控口径上这是"更宽松"，可接受。
 *
 * R378 刀1 主收割保底份额（机制乙判词：收割 60/h 池原是单令牌桶，池内零车道
 * 零优先级零保底——发现实证/白名单/Resolver 辅助管线可吃满整池把主收割页面
 * 拉取饿死，24h 零新答案的结构性病根）：构造第三参 [mainReservePercent] 给
 * 主车道留保底令牌（收割池实例传 [HARVEST_MAIN_RESERVE_PERCENT]），辅助类
 * 记账走 [tryAcquireAux]（拒绝条件=合计+保底>总量）；其余车道实例默认 0
 * （[tryAcquireAux] 与 [tryAcquire] 逐位同语义=语义零变）。**重分配≠加量**：
 * 池总量常量零改，总请求率零上升红线（与 SearchQuotaLedger 保底区同概念族）。
 */
class FloodBudget(
    private val windowMs: Long,
    @Volatile var maxOpsPerHour: Int,
    @Volatile var mainReservePercent: Int = 0,
) {

    /** 窗口内的一次记账：时刻 + 是否辅助车道（纯内部表示，诊断口分道计数用） */
    private class Op(val atMs: Long, val isAux: Boolean)

    private val lock = Any()

    /** 窗口内的操作记账（毫秒时刻+车道标记，升序） */
    private val ops = ArrayDeque<Op>()

    /** 驱逐窗口外旧记账（调用方须持 [lock]） */
    private fun evictLocked(nowMs: Long) {
        while (ops.isNotEmpty() && nowMs - ops.first().atMs >= windowMs) ops.removeFirst()
    }

    /** 主车道保底令牌数（持 [lock]；percent=0 即零保底） */
    private fun reserveTokensLocked(): Int = maxOpsPerHour * mainReservePercent / 100

    /** 尝试记账 [cost] 次主车道操作：预算够则记账放行（true），不够则拒绝（false，不记账） */
    fun tryAcquire(cost: Int = 1): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        evictLocked(now)
        if (ops.size + cost > maxOpsPerHour) return false
        repeat(cost) { ops.addLast(Op(now, false)) }
        true
    }

    /**
     * 辅助车道记账（R378 刀1）：拒绝条件=窗口内全部记账+本次+保底令牌>总量——
     * [reserveTokensLocked] 枚令牌只有主车道 [tryAcquire] 能花（辅助合计被拒在
     * 总量−保底以内，主收割保底可用 ≥保底枚）。percent=0 时拒绝条件退化为与
     * [tryAcquire] 逐位同语义（查询/秒回/爬取/OnThisDay 四实例零行为变）。
     */
    fun tryAcquireAux(cost: Int = 1): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        evictLocked(now)
        if (ops.size + cost + reserveTokensLocked() > maxOpsPerHour) return false
        repeat(cost) { ops.addLast(Op(now, true)) }
        true
    }

    /** 剩余额度（主车道视角：总量−窗内全部记账；诊断/日志用） */
    fun remaining(): Int = synchronized(lock) {
        evictLocked(System.currentTimeMillis())
        (maxOpsPerHour - ops.size).coerceAtLeast(0)
    }

    /** 窗内已记账总数（诊断探针：收割轮末「窗内总用=」行） */
    fun windowedCount(): Int = synchronized(lock) {
        evictLocked(System.currentTimeMillis())
        ops.size
    }

    /** 窗内辅助车道记账数（诊断探针：收割轮末「（辅助=）」行——保底份额观测口） */
    fun windowedAuxCount(): Int = synchronized(lock) {
        evictLocked(System.currentTimeMillis())
        ops.count { it.isAux }
    }

    /** 主车道保底令牌数（诊断探针：收割轮首「主保底=」行） */
    fun mainReserve(): Int = synchronized(lock) {
        evictLocked(System.currentTimeMillis())
        reserveTokensLocked()
    }
}

/**
 * 广告黑名单（P0 防守骨架 #2）：博彩/引流垃圾内容过滤。
 *
 * 实锤依据（2026-10 侦察）：目标搜索群深度索引的 36 条媒体几乎全是
 * "PG电子/棋牌/注册送彩金"类赌博广告图——收割入库前必须过滤，否则
 * 图谱边与答案库会被广告彻底污染（发现页会把赌场频道当资源推荐）。
 *
 * 口径选择：只收高精确度博彩/引流词，**不过滤成人内容词**（资源群本身即成人内容生态，
 * "资源/福利"等是该场景的正常内容词，误杀=功能报废）。
 */
object AdBlacklist {

    /** 文本垃圾关键词（小写 contains 匹配）：全部来自搜索群实采广告原文的高鉴别度片段 */
    private val SPAM_KEYWORDS = listOf(
        "pg电子", "捕鱼", "百家乐", "六合彩", "彩票", "投注", "彩金", "注册送", "首存", "日存",
        "洗码", "代理佣金", "永久扶持", "流水返利", "娱乐城", "信誉平台", "开业放水", "盘口",
        "极速开户", "棋牌", "u存u取", "usdt", "保证不输钱", "新人必红", "新人必赢", "赢钱",
        "官方投注", "招代理客服",
    )

    /** 链接垃圾特征（小写 contains）：带广告追踪参数的深链 */
    private val SPAM_LINK_PATTERNS = listOf("?ad=", "&ad=", "ad=kw")

    /**
     * 文本是否垃圾广告（caption/正文/按钮文本拼一起后判定）。
     * 空串不算垃圾（无文本的消息由其他闸处理）。
     */
    fun isSpam(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return SPAM_KEYWORDS.any { it in lower }
    }

    /** 链接是否带广告追踪参数（作用于归一化后的 t.me 全形式链接） */
    fun isSpamLink(link: String): Boolean {
        val lower = link.lowercase()
        return SPAM_LINK_PATTERNS.any { it in lower }
    }

    /**
     * 是否人机验证门消息（"请选择计算结果👇"类）：含"验证"且含验证动作词。
     * 仅作诊断计数——此类消息无链接本就不入库，识别出来只为让日志说人话。
     */
    fun looksLikeCaptcha(text: String): Boolean =
        "验证" in text && listOf("人机", "计算", "请选择", "点击").any { it in text }
}

/**
 * 答案库带关键词的只读投影（R211 直荐管线/发现页来源词共用同一读口）：
 * [keyword] = 群友在搜索群里发过的原词（折叠键的原文形态），[answer] = 该词的一条结果。
 */
data class KeywordAnswer(
    val keyword: String,
    val answer: GroupAnswer,
)

/**
 * 答案库单条结果：一个"搜索关键词 → 资源频道/消息链接"的映射条目。
 *
 * @param link 归一化的 t.me 全形式链接（https://t.me/…）
 * @param chatId t.me/c/<id> 直链可得的频道 id；用户名链接未知填 0（消费侧懒解析）
 * @param username t.me/<name> 链接的用户名段（不含查询参数）；直链填 ""
 * @param fromGroup 证据来源群 chatId
 * @param lastTs 最近命中时间（毫秒）
 * @param hits 累计命中次数（同一 (关键词,链接) 重复出现 = 多人搜出同资源，置信度信号）
 */
data class GroupAnswer(
    val link: String,
    val chatId: Long,
    val username: String,
    val fromGroup: Long,
    val lastTs: Long,
    val hits: Int,
    /** 标题线索（bot 应答内联按钮"文本↔链接"配对，按钮文本通常就是频道名）：
     *  SearchPublicChat 被 429 封死时（实测封号级 retry after 15000s+）答案区标题的
     *  零网络兜底；空 = 无线索。旧数据无此字段，反序列化缺省 ""，天然兼容 */
    val hintTitle: String = "",
    /** 频道成员数（个；R48）：应答行尾成员数 token / 网页预览页文案解析产物，
     *  0 = 未知（排序沉底、行尾不展示）。旧数据无此字段，反序列化缺省 0，天然兼容 */
    val members: Int = 0,
)

/**
 * 答案消息的"链接→标题线索"配对产物（R48 成员数提取升级版）：标题 + 行尾成员数。
 * [members] 单位=个，解析失败/无成员数 token = 0（未知）。
 */
data class HintTitle(
    val title: String,
    val members: Int = 0,
)

/**
 * 搜索相关实体（多源收割的统一收割对象）：群组/频道 或 bot 私聊。
 *
 *  - [KIND_GROUP] 群组/频道：走群消息监控——别人的关键词提问 + bot 应答都能采
 *    （配对口径：bot 应答 replyTo 指向本群人类文本消息）；
 *  - [KIND_BOT] bot 私聊：走私聊监控——别人发给 bot 的查询看不见，
 *    只能采 bot 回复给本人的应答，关键词配对本人发过的最近文本消息。
 *
 * @param src 发现来源（诊断日志/持久缓存用）：manual=用户手动清单 /
 *   name=群名关键词 / desc=群简介"发关键词"类说明 /
 *   botname=bot 名或用户名关键词 / botconfig=searchViaBot 既有配置
 */
data class SearchEntity(
    val chatId: Long,
    val kind: Int,
    val title: String,
    val src: String,
) {
    companion object {
        const val KIND_GROUP = 0
        const val KIND_BOT = 1
    }
}

/**
 * 答案库（answer_store.json，GroupHarvester 单写入方自持文件，DiscoveryGraph 同款风格）：
 * 搜索词 → 结果链接列表 + 各群收割游标。
 *
 * 为什么不进 LocalStore：TdLibGateway 刻意不持 LocalStore（单写入方语义，见网关 L172 注释），
 * GroupHarvester 由网关持有，同理自持文件；@Synchronized + 内存缓存唯一事实源 +
 * "临时文件 + rename" 原子落盘 + 变更阈值触发 flush，与 DiscoveryGraph 全同构。
 *
 * 文件格式：
 * ```json
 * {"cursors": {"<chatId>": lastSeenMessageId},
 *  "answers": {"<foldedKw>": {"keyword": 原文, "lastTs": 0, "hits": 0,
 *    "results": [{"link":"","chatId":0,"username":"","fromGroup":0,"lastTs":0,"hits":1}]}},
 *  "adDepths": {"<chatId>": {"pages": 2, "ts": 0}}}
 * ```
 * （adDepths 为深挖自适应扩展：该实体头部广告坑位页数的学习值，每轮收割实测重写、
 *  [AD_DEPTH_TTL_MS] 未再证自动失效；新增可选字段，老版本文件缺失按无学习处理，天然兼容。）
 */
class AnswerStore(private val dir: File) {

    /** 单个关键词的聚合条目（results 以 link 去重） */
    private class KeywordEntry(
        var keyword: String, // 原始关键词（首次写入时的形态，仅展示用）
        var lastTs: Long,
        var hits: Int,
        val results: LinkedHashMap<String, GroupAnswer> = LinkedHashMap(), // key = link
    )

    /** 广告坑位深度学习值（pages=头部连续广告页数，ts=最近实测时间；TTL 未再证失效） */
    private class AdDepth(var pages: Int, var ts: Long)

    private class StoreState(
        val cursors: HashMap<Long, Long> = HashMap(),
        val answers: LinkedHashMap<String, KeywordEntry> = LinkedHashMap(), // key = fold 后的关键词
        val adDepths: HashMap<Long, AdDepth> = HashMap(), // 深挖自适应：每实体广告坑位页数
    )

    private val file = File(dir, "answer_store.json")

    /** @Volatile 内存缓存（写时同步重建引用）；null = 尚未加载（首次访问时 lazy 读盘） */
    @Volatile
    private var stateCache: StoreState? = null

    /** 距上次落盘以来的变更次数（flush 节流） */
    private var dirtyCount = 0

    // ---------------- 游标 ----------------

    /** 群收割游标（该群已收割到的最大 messageId；0 = 从未收割） */
    @Synchronized
    fun cursor(chatId: Long): Long = loadLocked().cursors[chatId] ?: 0L

    /** 推进游标（只前进不后退）；变更计数但不立即落盘（flush 阈值统一管） */
    @Synchronized
    fun advanceCursor(chatId: Long, messageId: Long) {
        val st = loadLocked()
        val cur = st.cursors[chatId] ?: 0L
        if (messageId <= cur) return
        st.cursors[chatId] = messageId
        markDirtyLocked()
    }

    // ---------------- 广告坑位深度（深挖自适应学习） ----------------

    /**
     * 该实体已学习的头部广告坑位深度（页数）：下一轮收割直接跳过前 N 页的入库
     * （拉取仍走预算——GetChatHistory 只能顺序翻页，无法物理跳过）。
     * 超过 [AD_DEPTH_TTL_MS] 未再实测证实的按 0 处理（广告坑位会轮换/撤投，学习值必须可过期）。
     */
    @Synchronized
    fun adDepthPages(chatId: Long): Int {
        val d = loadLocked().adDepths[chatId] ?: return 0
        if (System.currentTimeMillis() - d.ts > AD_DEPTH_TTL_MS) return 0
        return d.pages
    }

    /**
     * 写入本轮实测广告坑位深度（含 0——头部已无广告时自校正清零，防学习值僵化误跳真资源）；
     * 值无变化只刷 ts 续期（同 recordNodeTitle 的防刷口径：内容没变不算变更，避免触发落盘）。
     */
    @Synchronized
    fun recordAdDepth(chatId: Long, pages: Int) {
        val st = loadLocked()
        val cur = st.adDepths[chatId]
        val now = System.currentTimeMillis()
        if (pages <= 0) {
            if (cur != null) { st.adDepths.remove(chatId); markDirtyLocked() }
            return
        }
        if (cur == null) {
            st.adDepths[chatId] = AdDepth(pages, now)
            markDirtyLocked()
        } else if (cur.pages != pages) {
            cur.pages = pages
            cur.ts = now
            markDirtyLocked()
        } else {
            cur.ts = now // 同值续期不记变更（TTL 靠 ts，续期丢失最多重学一轮，可接受）
        }
    }

    // ---------------- 答案 ----------------

    /**
     * 记录一条答案：关键词（调用方已过滤广告/超长）→ 链接。
     * 同 (foldedKw, link) 命中累加 hits 并刷新时间；关键词总数超 [MAX_KEYWORDS]
     * 按 lastTs 最老驱逐；单词结果超 [MAX_RESULTS_PER_KW] 按 lastTs 最老驱逐。
     */
    @Synchronized
    fun recordAnswer(keyword: String, fromGroup: Long, link: String, hintTitle: String = "", members: Int = 0) {
        val folded = SearchTextKit.fold(keyword).trim()
        if (folded.isEmpty()) return
        val now = System.currentTimeMillis()
        val st = loadLocked()
        val entry = st.answers.getOrPut(folded) { KeywordEntry(keyword.trim(), now, 0) }
        entry.lastTs = now
        entry.hits++
        val existing = entry.results[link]
        if (existing != null) {
            entry.results[link] = existing.copy(
                lastTs = now,
                hits = existing.hits + 1,
                // 线索升级：旧记录没线索而新应答带按钮标题，补上（不覆盖已有线索）
                hintTitle = existing.hintTitle.ifBlank { hintTitle },
                // 成员数升级（R48）：旧记录未知而新应答带成员数，补上（不覆盖已知值）
                members = if (existing.members > 0) existing.members else members,
            )
        } else {
            // 单词结果数封顶：超了掐最老（LinkedHashMap 无法按值排序，手动扫最老）
            if (entry.results.size >= MAX_RESULTS_PER_KW) {
                entry.results.entries.minByOrNull { it.value.lastTs }?.let { entry.results.remove(it.key) }
            }
            entry.results[link] = GroupAnswer(
                link = link,
                chatId = chatIdFromLink(link),
                username = usernameFromLink(link),
                fromGroup = fromGroup,
                lastTs = now,
                hits = 1,
                hintTitle = hintTitle,
                members = members,
            )
        }
        // 关键词总数封顶：按 lastTs 最老驱逐
        while (st.answers.size > MAX_KEYWORDS) {
            val oldest = st.answers.entries.minByOrNull { it.value.lastTs }?.key ?: break
            st.answers.remove(oldest)
        }
        markDirtyLocked()
    }

    /**
     * 查询答案（供未来"本地未命中→答案库直出"搜索路径）：fold 归一后精确命中；
     * 结果按 hits/lastTs 降序。无命中返回空表。
     */
    @Synchronized
    fun query(keyword: String): List<GroupAnswer> {
        val folded = SearchTextKit.fold(keyword).trim()
        val entry = loadLocked().answers[folded] ?: return emptyList()
        return entry.results.values.sortedWith(compareByDescending<GroupAnswer> { it.hits }.thenByDescending { it.lastTs })
    }

    /** 诊断快照：群游标数 / 关键词数 / 答案总数（设置页或日志用） */
    @Synchronized
    fun stats(): Triple<Int, Int, Int> {
        val st = loadLocked()
        return Triple(st.cursors.size, st.answers.size, st.answers.values.sumOf { it.results.size })
    }

    /**
     * 全部答案的只读快照（R54 口味雷达原料：逐条取 hintTitle 分词）。
     * 纯内存扫描（首访问才 lazy 读盘），返回新建 List，调用方改不动库内对象。
     */
    @Synchronized
    fun allAnswers(): List<GroupAnswer> {
        val st = loadLocked()
        return st.answers.values.flatMap { it.results.values }
    }

    /**
     * 全部「关键词 → 答案」的只读投影（R211：直荐注册表与发现页来源词共用读口——
     * [allAnswers] 把关键词丢了，群友搜过的词（「爱情公寓第4集」类）只能从这里取）。
     * 同款纯内存扫描 + 新建 List 防御；keyword 用条目原文形态（展示用）。
     */
    @Synchronized
    fun allKeywordAnswers(): List<KeywordAnswer> {
        val st = loadLocked()
        return st.answers.values.flatMap { e -> e.results.values.map { a -> KeywordAnswer(e.keyword, a) } }
    }

    // ---------------- 解析回填（EntityResolver 专用） ----------------

    /**
     * 全部 chatId 未知（=0）且带用户名的答案引用的小写用户名集合
     * （EntityResolver 的优先级源：这些名字直接决定答案可用性与角标可见性，先解析）。
     * 纯内存扫描，O(答案总数)，调用方按轮次调用一次即可。
     */
    @Synchronized
    fun unresolvedUsernames(): Set<String> {
        val st = loadLocked()
        val out = HashSet<String>()
        for (e in st.answers.values) {
            for (a in e.results.values) {
                if (a.chatId == 0L && a.username.isNotBlank()) out += a.username.lowercase()
            }
        }
        return out
    }

    /**
     * 按链接回填标题线索（429 封死期的零网络兜底链配套）：全词库扫描，只升级
     * hintTitle 为空白的记录（已有线索不覆盖）；[members] 顺带升级成员数未知的记录
     * （R48，已知值不覆盖）；返回升级条数（0 = 无需变更不触发落盘）。
     */
    @Synchronized
    fun backfillHintTitle(link: String, hintTitle: String, members: Int = 0): Int {
        if (link.isBlank() || (hintTitle.isBlank() && members <= 0)) return 0
        val st = loadLocked()
        var upgraded = 0
        for (e in st.answers.values) {
            val existing = e.results[link] ?: continue
            val newTitle = existing.hintTitle.ifBlank { hintTitle }
            val newMembers = if (existing.members > 0) existing.members else members
            if (newTitle != existing.hintTitle || newMembers != existing.members) {
                e.results[link] = existing.copy(hintTitle = newTitle, members = newMembers)
                upgraded++
            }
        }
        if (upgraded > 0) markDirtyLocked()
        return upgraded
    }

    /**
     * 把某用户名（大小写不敏感）下全部 chatId==0 的答案回填为已解析 chatId；
     * 返回回填条数（0 = 无需变更，不触发落盘）。幂等：二次调用自然空转。
     */
    @Synchronized
    fun backfillChatId(username: String, chatId: Long): Int {
        val name = username.trim().removePrefix("@").lowercase()
        if (name.isEmpty() || chatId == 0L) return 0
        val st = loadLocked()
        var filled = 0
        for (e in st.answers.values) {
            for ((link, ans) in e.results) {
                if (ans.chatId == 0L && ans.username.lowercase() == name) {
                    e.results[link] = ans.copy(chatId = chatId)
                    filled++
                }
            }
        }
        if (filled > 0) markDirtyLocked()
        return filled
    }

    /**
     * 某用户名答案的最近引用群 chatId（EntityResolver 补边证据源：
     * 图谱 resolveQueue 条目被淘汰后，答案库里仍留有其收割来源群，按 lastTs 最新取）。
     * 无任何引用返回 0（调用方按"无引用方"跳过补边，回填仍照常）。
     */
    @Synchronized
    fun latestFromGroupOf(username: String): Long {
        val name = username.trim().removePrefix("@").lowercase()
        if (name.isEmpty()) return 0L
        val st = loadLocked()
        var bestTs = 0L
        var from = 0L
        for (e in st.answers.values) {
            for (a in e.results.values) {
                if (a.username.lowercase() == name && a.lastTs >= bestTs) {
                    bestTs = a.lastTs
                    from = a.fromGroup
                }
            }
        }
        return from
    }

    // ---------------- 落盘 ----------------

    @Synchronized
    private fun loadLocked(): StoreState {
        stateCache?.let { return it }
        val st = StoreState()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONObject("cursors")?.let { c ->
                for (k in c.keys()) st.cursors[k.toLong()] = c.getLong(k)
            }
            root.optJSONObject("answers")?.let { a ->
                for (k in a.keys()) {
                    val e = a.getJSONObject(k)
                    val entry = KeywordEntry(
                        keyword = e.optString("keyword", k),
                        lastTs = e.optLong("lastTs"),
                        hits = e.optInt("hits"),
                    )
                    e.optJSONArray("results")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            val ans = GroupAnswer(
                                link = r.optString("link"),
                                chatId = r.optLong("chatId"),
                                username = r.optString("username", ""),
                                fromGroup = r.optLong("fromGroup"),
                                lastTs = r.optLong("lastTs"),
                                hits = r.optInt("hits", 1),
                                hintTitle = r.optString("hintTitle", ""),
                                // R48 成员数字段：旧数据无此字段，缺省 0（未知）兼容
                                members = r.optInt("members", 0),
                            )
                            if (ans.link.isNotBlank()) entry.results[ans.link] = ans
                        }
                    }
                    if (entry.results.isNotEmpty()) st.answers[k] = entry
                }
            }
            root.optJSONObject("adDepths")?.let { d ->
                for (k in d.keys()) {
                    runCatching {
                        val o = d.getJSONObject(k)
                        val pages = o.optInt("pages")
                        if (pages > 0) st.adDepths[k.toLong()] = AdDepth(pages, o.optLong("ts"))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "answer_store.json 读取失败（按空库重建）: ${it.message}") }
        stateCache = st
        return st
    }

    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked()
    }

    /** 立即落盘（进程退出前由网关 close() 链调用）；无变更时空转 */
    @Synchronized
    fun flush() {
        if (stateCache == null) return // 从未加载/变更，不落盘（绝不凭空建文件）
        flushLocked()
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（与 LocalStore L1464 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        runCatching {
            val root = JSONObject()
            val cursors = JSONObject()
            for ((k, v) in st.cursors) cursors.put(k.toString(), v)
            root.put("cursors", cursors)
            val answers = JSONObject()
            for ((k, e) in st.answers) {
                val eo = JSONObject()
                eo.put("keyword", e.keyword)
                eo.put("lastTs", e.lastTs)
                eo.put("hits", e.hits)
                val arr = JSONArray()
                for (r in e.results.values) {
                    arr.put(JSONObject()
                        .put("link", r.link)
                        .put("chatId", r.chatId)
                        .put("username", r.username)
                        .put("fromGroup", r.fromGroup)
                        .put("lastTs", r.lastTs)
                        .put("hits", r.hits)
                        .put("hintTitle", r.hintTitle)
                        .put("members", r.members))
                }
                eo.put("results", arr)
                answers.put(k, eo)
            }
            root.put("answers", answers)
            val adDepths = JSONObject()
            for ((k, d) in st.adDepths) {
                adDepths.put(k.toString(), JSONObject().put("pages", d.pages).put("ts", d.ts))
            }
            root.put("adDepths", adDepths)
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）：拷内容后删临时文件
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "answer_store.json 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    companion object {
        private const val TAG = "AnswerStore"

        /** 变更多少次自动落盘（收割是低频动作，阈值小、落盘勤，丢数据窗口小） */
        private const val FLUSH_AFTER_MUTATIONS = 10

        /** 广告坑位深度学习值有效期（7 天）：坑位会轮换/撤投，超期未再证实自动失效 */
        private const val AD_DEPTH_TTL_MS = 7L * 24 * 3600 * 1000

        /** 关键词总数硬顶（LRU 按 lastTs 驱逐）；搜索群关键词生命力短，2000 足够 */
        private const val MAX_KEYWORDS = 2_000

        /** 单关键词结果数硬顶（防热搜词无限膨胀；超出掐最老） */
        private const val MAX_RESULTS_PER_KW = 30

        /** t.me/c/<id>/<msgId> 直链 → chatId（Telegram 公开约定：-100 前缀 + 内部 id）；非直链返回 0 */
        internal fun chatIdFromLink(link: String): Long {
            val path = link.removePrefix("https://t.me/")
            if (!path.startsWith("c/")) return 0L
            val inner = path.removePrefix("c/").substringBefore('/').toLongOrNull() ?: return 0L
            return "-100$inner".toLongOrNull() ?: 0L
        }

        /** t.me/<name> 链接的用户名段（去掉消息 id 段与查询参数）；直链/邀请链返回 "" */
        internal fun usernameFromLink(link: String): String {
            val path = link.removePrefix("https://t.me/")
            if (path.startsWith("c/") || path.startsWith("+") || path.startsWith("joinchat")) return ""
            return path.substringBefore('/').substringBefore('?')
        }
    }
}

/**
 * 资源搜索实体收割器（P1 核心管道，**全程只读，绝不发任何消息**）。
 *
 * 背景（2026-10 侦察实锤）："超级搜索 搜万物看万片【全网资源】"(-1003031746047) 这类群
 * 的玩法是用户在群里发关键词、bot 回复一组资源 t.me 链接（文本实体/inline 按钮两种载体）。
 * 本收割器把这个公开问答流被动沉淀成两类本地资产：
 *  1. **发现维度**：bot 应答链接 → DiscoveryGraph 新增 [EDGE_GROUP_SEARCH] 边
 *    （经由网关 recordGroupSearchLink → sniffLink；用户名链接由 EntityResolver
 *    持久解析管线消化——解析出 chatId 即回填答案库/金矿并按证据补写边）；
 *  2. **搜索维度**："关键词 → 结果链接"映射 → [AnswerStore]（answer_store.json），
 *    未来"本地未命中 → 答案库直出/预填"的数据地基。
 *
 * 多源升级（从"单群"到"自动发现所有搜索相关实体"）：
 *  - 自动发现：每轮收割前扫描全会话（网关 listDiscoveryProfiles，1h TTL），
 *    按特征分类出搜索相关实体（规则见 companion 关键词表：群名/群简介/bot 名与用户名），
 *    外加 searchViaBot 既有配置的 bot 无条件并入；手动清单（prefs.searchGroupChatIds）
 *    永远并入且手动优先；清单按 chatId 去重、持久化到 prefs.harvestDiscoveredJson、
 *    每小时重扫一次（用户新加的群自动纳入）；
 *  - 多源收割：群组/频道走群消息监控（别人的提问+bot 应答都采），
 *    bot 私聊走私聊监控（别人的查询看不见，采 bot 回复+配对本人发过的关键词）；
 *  - 预算均分：flood 预算总量不变（floodHarvestPerHour 一个池），
 *    每轮按实体数均分——实体多了每个少分点，清单膨胀不会放大请求总量。
 *
 * 防守骨架（P0）全部内置：
 *  - flood 令牌桶：GetChatHistory 页拉取与 GetUser 实证共用滑动窗口小时预算，耗尽即收工；
 *  - 广告黑名单：博彩/引流文本与带广告参数链接在入库前拦截（侦察实锤群内广告极重）；
 *  - 归属过滤：只采信"bot 身份（白名单快通 / GetUser 实证 UserTypeBot）
 *    且 replyTo 指向本群人类文本消息"的回复——其他群友消息、bot 互聊、主动广告帖不入答案库。
 *
 * 生命周期：由 TdLibGateway 构造持有（授权 Ready 后 start()），close() 时 stop()。
 * 主循环节奏：每 [AppPrefs.groupHarvestPollMinutes] 分钟一轮，每轮对实体清单逐个增量收割；
 * 一切失败静默降级（记日志、不动游标、下轮再来），绝不影响主流程。
 */
class GroupHarvester(
    private val gateway: TdLibGateway,
    private val prefs: AppPrefs,
    filesDir: File,
) {

    /** 答案库（含各群收割游标）；filesDir 根目录，与 discovery_graph.json 同级 */
    val answerStore = AnswerStore(filesDir)

    /** 搜索实体信誉分（R64）：秒回链应答表现记账，liveAskEntities 按信誉降序挑实体 */
    val reputation = EntityReputation(filesDir)

    /** R65 热词榜（热词源）存储：hot_words.json——主搜索 bot 菜单协议收割的全网热搜词表 */
    val hotWordBoard = HotWordBoard(filesDir)

    /** 广告频道分类器（P2 防守：伪装资源频道三特征识别 + 黑名单自持，ad_channels.json） */
    val adClassifier = AdChannelClassifier(filesDir, gateway.discoveryGraph)

    /**
     * flood 预算·收割车道（每轮开头从 prefs 刷新额度上限）。
     * R378 刀1：第三参=主车道保底份额（收割池唯一的保底实例）——辅助管线
     * （发现实证/白名单/Resolver）走 tryAcquireAux 被拒在总量−保底以内，
     * 主收割页面拉取保底可用 ≥保底枚；池总量常量零改（重分配≠加量）。
     */
    val harvestBudget = FloodBudget(WINDOW_MS, prefs.floodHarvestPerHour, HARVEST_MAIN_RESERVE_PERCENT)

    /**
     * 用户名→chatId 持久解析器（断链修复）：消化图谱 resolveQueue 积压与收割新链接的
     * 用户名，解析成功即回填 answer_store/goldmine 的 chatId 并按证据补写
     * group_search/link 边。与收割主循环共享 harvestBudget（同属被动只读管线，
     * 共用一个 flood 池）；start()/stop() 挂在收割器同名方法上，启动即消化积压。
     */
    val entityResolver = EntityResolver(filesDir, gateway, gateway.discoveryGraph, answerStore, adClassifier, harvestBudget)

    /**
     * R211 搜索群直荐管线：用户勾选群（显式用户行为）bot 应答频道 → USER 车道快速
     * 验证 → 直荐注册表（发现页「你的搜索群里有人搜过「X」」直荐卡数据源）。
     * 拉模型扫答案库（收割/秒回两写入口全收）；start()/stop() 挂收割器同名方法，
     * 每轮收割后 kick 提前唤醒（不等空闲巡检周期）。
     */
    val groupRecommend = SearchGroupRecommender(filesDir, gateway, answerStore) { loadUserSelectedGroups() }

    /**
     * R377 发现页懒加载门（单源类，实例挂本类——收割/解析/直荐三管线的构造持有者，
     * 零签名改动）：冷启恒关门，用户首开发现页授权（[DiscoveryLazyGate.onUserOpen]），
     * 门内常驻循环照既有预算干活。EntityResolver/SearchGroupRecommender/FeedViewModel
     * 经 gateway.groupHarvester 既有通道取用（零新构造参数）。
     */
    val discoveryGate = DiscoveryLazyGate()

    /** 收割协程作用域（SupervisorJob：单群失败不扩散；stop() 整体取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopJob: Job? = null

    /** bot 身份进程内缓存：userId → 是否 bot（正负结果都缓存——人不会变 bot，bot 不会变人） */
    private val botFlagCache = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /** 自动发现清单进程内缓存（手动清单不进本缓存，每轮现读 prefs 并入；持久化见 prefs.harvestDiscoveredJson） */
    @Volatile
    private var discoveredEntities: List<SearchEntity> = emptyList()

    /** 上次自动发现扫描时刻（毫秒；0 = 本进程未扫过，首个收割周期必扫） */
    @Volatile
    private var lastDiscoveryMs = 0L

    /**
     * 本会话已完成特征实证的对象（重扫命中即免记账免请求——网关侧还有一层进程内结果缓存，
     * 这层集合保证"缓存命中"连预算记账都省掉）：
     * 配置的 bot 名（小写）/ 私聊 userId / 超级群 id。负结果同样记忆
     * （人不会变 bot、简介判定不常变；失败不记入，下轮重扫重试）。
     */
    private val probedBotNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val probedUserIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val probedSupergroupIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 诊断：本会话累计拦截的人机验证门消息数（设置页/日志可参考） */
    @Volatile
    var captchaSeen = 0
        private set

    /**
     * 启动主循环（幂等；授权 Ready 后由网关调用）。
     * 循环体内每轮都重读开关/清单/节奏/预算配置——设置改动下一轮即生效，无需重启。
     * R377 懒门：关门（冷启→用户首开发现页）/窗过期 → 本轮空转（15min 节奏保留），
     * 门内照既有预算干活——加门不是拆预算；探针=「收割轮候门」D 级一关门段一行。
     */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            while (isActive) {
                val enabled = runCatching { prefs.groupHarvestEnabled }.getOrDefault(false)
                val ready = gateway.state.value is GatewayState.Ready
                val gateOpen = enabled && ready && discoveryGate.sessionActive()
                if (enabled && ready && !gateOpen) discoveryGate.noteGateHold("收割轮")
                if (gateOpen) {
                    // R65 热词榜（热词源）：每日一次对主搜索 bot 走菜单协议收割全网热搜词。
                    // 独立子协程与群收割解耦（SupervisorJob 兄弟协程）：慢实体深挖不挡热词榜，
                    // 热词榜走查慢也不拖群收割；flood 车道本就分离（热词榜走查询车道）。
                    // 注意：与本收割器"全程只读"的主路径不同，本流程会向配置的主搜索 bot
                    // 发一条 /start（菜单协议要求发起动作，其余走查全是按钮回调+轮询只读），
                    // 整轮走查询车道预算记 1 次，随本开关一并启停；限流/失败静默降级
                    // （旧词表继续服役），绝不影响收割主流程
                    scope.launch {
                        runCatching { maybeHarvestHotWords() }
                            .onFailure { Log.w(TAG, "热词榜收割轮次失败（下轮重试）: ${it.message}") }
                    }
                    harvestAllGroupsGuarded()
                }
                val minutes = runCatching { prefs.groupHarvestPollMinutes }.getOrDefault(15)
                delay(minutes * 60_000L)
            }
        }
        // 解析管线与收割开关解耦：就算暂停收割，存量 resolveQueue 积压也该继续消化
        entityResolver.start()
        // R211 直荐管线同随收割器起（自身扫答案库节奏独立，kick 由收割轮触发）
        groupRecommend.start()
        Log.i(TAG, "GroupHarvester 已启动（只读监控，轮询 ${prefs.groupHarvestPollMinutes}min，自动发现每小时重扫）")
    }

    /** 停止主循环并兜底落盘（幂等；网关 close() 链调用） */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        entityResolver.stop()
        groupRecommend.stop()
        runCatching { answerStore.flush() }
        runCatching { reputation.flush() } // R64：实体信誉兜底落盘（保住退出前的尾巴）
    }

    /** 诊断快照（设置页/日志用）：游标数/关键词数/答案数/自动发现实体数/预算剩余 */
    fun stats(): String {
        val (groups, keywords, answers) = answerStore.stats()
        return "游标=$groups 关键词=$keywords 答案=$answers 自动实体=${discoveredEntities.size} " +
            "热词榜=${hotWordBoard.snapshot().words.size} " + // R65 热词源词表规模
            "收割预算剩=${harvestBudget.remaining()} 验证门=$captchaSeen ${entityResolver.stats()} " +
            "直荐=${groupRecommend.store.stats()}" // R211 直荐注册表规模
    }

    // ---------------- 一轮收割 ----------------

    /** 单飞闸：主循环轮与 [kickOnce] 并发时只跑一个 harvestAllGroups（防同轮双收割双记账） */
    private val harvestInFlight = AtomicBoolean(false)

    /**
     * 单轮收割的单飞闸包装（主循环与 [kickOnce] 共用）：主循环侧语义零改——挂起等
     * 本轮跑完再进 15min 歇（既有顺序节拍保留）；kickOnce 侧与在飞轮互斥跳过。
     * 失败记账壳原样（runCatching+Log.w 下轮重试）。
     */
    private suspend fun harvestAllGroupsGuarded() {
        if (!harvestInFlight.compareAndSet(false, true)) return
        try {
            runCatching { harvestAllGroups() }
                .onFailure { Log.w(TAG, "收割轮次失败（下轮重试）: ${it.message}") }
        } finally {
            harvestInFlight.set(false)
        }
    }

    /**
     * R377 懒门开门路径的「开一轮（预算内收割）」（镜像 groupRecommend.kick 模式）：
     * enabled+Ready 检查 + 单飞闸（与主循环轮互斥）+ 跑一轮 [harvestAllGroups]——
     * 预算/熔断/账本语义在函数内照记零绕过（自动发现重扫+白名单解析+实体页拉
     * 全走既有闸，§九统一发送口纪律零新发送口）。
     * 调用源唯一=FeedViewModel.onDiscoverPageSettled 授权路径（用户点开发现页=
     * 意图在场时刻），不是新的后台暗道；不含热词榜（/start 写路径留给主循环节拍）。
     */
    fun kickOnce() {
        val enabled = runCatching { prefs.groupHarvestEnabled }.getOrDefault(false)
        if (!enabled) return
        if (gateway.state.value !is GatewayState.Ready) return
        scope.launch { harvestAllGroupsGuarded() }
    }

    private suspend fun harvestAllGroups() {
        // 每轮开头刷新预算上限（设置改动即生效）
        harvestBudget.maxOpsPerHour = runCatching { prefs.floodHarvestPerHour }.getOrDefault(60)
        // 实体清单：手动（永远并入、优先）+ 自动发现（每小时重扫；发现扫描本身计预算）
        val entities = currentEntities()
        if (entities.isEmpty()) return
        // 白名单只在存在群组类实体时才值得解析（纯 bot 私聊清单零请求跳过）
        val whitelist = if (entities.any { it.kind == SearchEntity.KIND_GROUP }) resolveWhitelist() else emptySet()
        // 预算均分：剩余额度 / 实体数，至少保底 1（保底也可能因总预算耗尽而实际拉不到）。
        // 发现扫描与白名单解析先于均分记账——它们是"建清单"的固定成本，不参与均分
        val share = (harvestBudget.remaining() / entities.size).coerceAtLeast(1)
        Log.i(
            TAG,
            "本轮收割实体=${entities.size}（群=${entities.count { it.kind == SearchEntity.KIND_GROUP }}" +
                " bot=${entities.count { it.kind == SearchEntity.KIND_BOT }}） 每实体预算≈$share 总余量=${harvestBudget.remaining()}" +
                " 主保底=${harvestBudget.mainReserve()}",
        )
        for (e in entities) {
            runCatching { harvestEntity(e, whitelist, share) }
                .onFailure { Log.w(TAG, "收割实体失败 chatId=${e.chatId} kind=${e.kind}（跳过）: ${it.message}") }
        }
        // R378 轮末份额探针（设备腿观测口）：窗内总用/辅助/池余——保底份额的活体
        // 证据行（辅助恒 ≤总量−保底=刀1 判据；主收割用=总用−辅助）
        Log.i(
            TAG,
            "本轮收工份额账：窗内总用=${harvestBudget.windowedCount()}（辅助=${harvestBudget.windowedAuxCount()}）池余=${harvestBudget.remaining()}",
        )
        runCatching { answerStore.flush() }
        runCatching { reputation.flush() } // R64：轮末顺手落盘，与答案库同节奏
        // R211 收割轮新答案即至：kick 直荐管线立即验证（不等空闲巡检周期），
        // 用户勾选群的 bot 应答 15 分钟收割窗内即可直荐入池
        runCatching { groupRecommend.kick() }
    }

    /** 群清单解析：逗号分隔 chatId；非法项丢弃 */
    private fun parseGroupIds(raw: String): List<Long> =
        raw.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()

    // ---------------- R65 热词榜收割调度（每日一次，节流戳落盘 hot_words.json） ----------------

    /**
     * R65 热词榜收割调度：每日一次（[HotWordBoard.HARVEST_INTERVAL_MS] 节流窗口，
     * 节流戳落盘与词表同文件）对配置的主搜索 bot 走菜单协议收割全网热搜词榜
     * （协议走查在 TdLibGateway.harvestHotWordBoard，本方法只管节奏与落盘）。
     *
     * 节流纪律：网关**真正发起网络流程**（预算记账通过、会话解析成功）即经
     * onAttempt 回调推进节流戳——此后失败也占当日额度（防"失败→下周期重试→再失败"
     * 烧毁循环）；预算耗尽/熔断静默时网关未发一弹、不回调，节流戳不推进
     * （下个轮询周期再试，小时级预算恢复后自然补上）。
     * 429/超时/无产出全部静默降级：本轮不更新，旧词表继续服役。
     */
    private suspend fun maybeHarvestHotWords() {
        val now = System.currentTimeMillis()
        val last = hotWordBoard.lastAttemptTs()
        if (last > 0 && now - last < HotWordBoard.HARVEST_INTERVAL_MS) return
        val words = gateway.harvestHotWordBoard(
            onAttempt = { hotWordBoard.recordAttempt(System.currentTimeMillis()) },
        )
        if (words.isNullOrEmpty()) {
            Log.w(TAG, "热词榜收割：本轮无产出（限流/失败/协议未命中，静默降级，旧词表继续服役）")
            return
        }
        hotWordBoard.saveWords(words, System.currentTimeMillis())
        Log.i(TAG, "热词榜收割：入库 ${words.size} 词（收录时刻已落盘）")
    }

    // ---------------- 实体清单：自动发现 + 手动并入 ----------------

    /**
     * 当前收割实体清单：手动清单（prefs.searchGroupChatIds）永远并入且手动优先——
     * 同 id 已在自动清单时沿用自动侧的 kind（用户把 bot 私聊 id 手填进群清单也能按私聊收）；
     * **R183 宝藏二：用户勾选的已加入搜索群（[loadUserSelectedGroups]）与手动清单同优先级
     * 并入**（用户显式确认过自己的群——知情可控口径；勾选即生效，下轮收割开始收）；
     * 自动发现受 [AppPrefs.harvestAutoDiscover] 开关控制，按 chatId 去重。
     * 勾选语义与自动发现正交：用户可以「勾选自己的群 + 关掉自动发现」达成只收
     * 自己群的精确模式（发现页宝藏二 = 纯用户自己的群的数据）。
     */
    private suspend fun currentEntities(): List<SearchEntity> {
        val auto = if (runCatching { prefs.harvestAutoDiscover }.getOrDefault(true)) {
            refreshDiscoveryIfDue()
        } else {
            emptyList()
        }
        return mergeHarvestEntities(parseGroupIds(prefs.searchGroupChatIds), loadUserSelectedGroups(), auto)
    }

    /**
     * 搜索联动只读口（搜索页"资源群秒回"主动发词的实体清单来源）：手动清单 + 用户勾选群
     * + 自动发现缓存。与 [currentEntities] 的差异：**绝不触发网络重扫**（refreshDiscovery
     * IfDue 可能发实证请求），只读进程内缓存 + prefs 持久缓存——搜索提交热路径零网络成本。
     * 进程内缓存为空时从持久缓存兜底装载（收割主循环还没跑过/刚重启时也有存量清单可用）；
     * 从未发现过任何实体时至少回手动清单（可能为空表，调用方按"无实体"静默收场）。
     * R211：用户勾选群并入（勾选=最强搜索群信号，秒回可达——否则勾选群永远收不到
     * 秒问词、答案库收不到该群 live 答案）；合并口径与 [currentEntities] 同一实现
     * [mergeHarvestEntities]（零镜像）。
     */
    fun knownSearchEntities(): List<SearchEntity> {
        var auto = discoveredEntities
        if (auto.isEmpty()) {
            auto = loadDiscoveredCache()
            if (auto.isNotEmpty()) discoveredEntities = auto // 顺手暖进程内缓存，后续调用零磁盘
        }
        return mergeHarvestEntities(parseGroupIds(prefs.searchGroupChatIds), loadUserSelectedGroups(), auto)
    }

    // ---------------- R183 宝藏二：用户已加入搜索群勾选（知情可控，用户亲自拍板） ----------------

    /**
     * 用户勾选纳入收割的已加入搜索群（prefs JSON 读侧；损坏按空表重建不崩）。
     * 勾选=用户对自己群的显式确认——这些群的消息流收割产物（答案库/群搜边/热词）
     * 是发现页宝藏二的活数据源。
     */
    fun loadUserSelectedGroups(): List<SearchEntity> {
        val raw = runCatching { prefs.harvestUserGroupsJson }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("chatId", 0L)
                if (id == 0L) return@mapNotNull null
                SearchEntity(id, SearchEntity.KIND_GROUP, o.optString("title", ""), o.optString("src", "user"))
            }.distinctBy { it.chatId }
        }.getOrElse {
            Log.w(TAG, "用户搜索群勾选缓存解析失败（按空表重建）: ${it.message}")
            emptyList()
        }
    }

    /**
     * 勾选/取消勾选一个已加入搜索群（设置页「我的搜索群」卡调用；全量重写，幂等）。
     * 勾选即生效：下一个收割周期（[AppPrefs.groupHarvestPollMinutes] 分钟内）开始收该群；
     * 取消后该群从清单移除（已收数据留存——答案库/游标不动，用户再勾上从游标续收）。
     */
    fun setUserGroupSelected(chatId: Long, title: String, src: String, selected: Boolean) {
        if (chatId == 0L) return
        runCatching {
            val cur = LinkedHashMap<Long, SearchEntity>()
            for (e in loadUserSelectedGroups()) cur[e.chatId] = e
            if (selected) {
                cur[chatId] = SearchEntity(chatId, SearchEntity.KIND_GROUP, title, src)
            } else {
                cur.remove(chatId)
            }
            val arr = JSONArray()
            for (e in cur.values) {
                arr.put(
                    JSONObject()
                        .put("chatId", e.chatId)
                        .put("title", e.title)
                        .put("src", e.src),
                )
            }
            prefs.harvestUserGroupsJson = arr.toString()
        }.onFailure { Log.w(TAG, "用户搜索群勾选写盘失败（重试下次勾选）: ${it.message}") }
    }

    /**
     * R197 群选择器「建议」角标的群名规则现算口（公开只读）：
     * 直接包既有 [GROUP_NAME_KEYWORDS]/[GROUP_NAME_EXCLUDES] 判定（与
     * [discoverEntities] 同一套规则，零镜像零改写）——关键词检测在新选择器里
     * **只作提示不筛列表**（用户裁决：改成用户手动选，不要替他筛选）；
     * 新加入的群不必等 1h 自动发现重扫，开 sheet 即时现算角标。
     */
    fun looksLikeSearchGroupByTitle(title: String): Boolean =
        matchesKeywords(title, GROUP_NAME_KEYWORDS, GROUP_NAME_EXCLUDES)

    /**
     * 检测到的已加入搜索群列表（R197 前「设置卡」展示源，**纯读零网络**——
     * knownSearchEntities 同款口径：进程内缓存空时从持久缓存兜底，绝不触发重扫）。
     * 检测规则=自动发现的现成特征分类（群名/简介关键词表+排除词，即用户指定的
     * 「GroupHarvester 现成的搜索群识别规则」）；展示序：用户已勾选在前 →
     * 自动检测命中 → 手动清单兜底。用户加入新搜索群 → 下一次自动发现重扫（≤1h）
     * 出现在本列表，勾上即纳入——「加入即生效」的扩容口径。
     */
    fun knownUserSearchGroups(): List<SearchEntity> {
        var auto = discoveredEntities
        if (auto.isEmpty()) {
            auto = loadDiscoveredCache()
            if (auto.isNotEmpty()) discoveredEntities = auto
        }
        val byId = LinkedHashMap<Long, SearchEntity>()
        // 已勾选优先展示（用户确认过的排前面，设置页一屏即见）
        for (e in loadUserSelectedGroups()) byId[e.chatId] = e
        // 自动检测命中的群组类实体（bot 私聊不是「群」，不进勾选卡）
        for (e in auto.filter { it.kind == SearchEntity.KIND_GROUP }) byId.putIfAbsent(e.chatId, e)
        // 手动清单兜底（手填 chatId 的群也在卡上可勾选/可关）
        for (raw in parseGroupIds(prefs.searchGroupChatIds)) {
            byId.putIfAbsent(raw, SearchEntity(raw, SearchEntity.KIND_GROUP, "手动群", "manual"))
        }
        return byId.values.toList()
    }

    /**
     * 自动发现清单：进程内缓存未到 [DISCOVERY_TTL_MS] 直接返回；
     * 冷启动先从 prefs 持久缓存兜底（存量清单立即开工，避免重启即重付特征实证请求；
     * 重扫时刻也从持久时间戳恢复——"每小时重扫"的节奏跨进程重启仍然准确）。
     * 扫描失败保留旧清单、不更新扫描时刻（下个收割周期即重试，不等一小时）。
     */
    private suspend fun refreshDiscoveryIfDue(): List<SearchEntity> {
        if (discoveredEntities.isEmpty()) {
            discoveredEntities = loadDiscoveredCache()
            if (discoveredEntities.isNotEmpty()) {
                lastDiscoveryMs = runCatching { prefs.harvestDiscoveredAtMs }.getOrDefault(0L)
                Log.i(TAG, "自动发现：从持久缓存恢复 ${discoveredEntities.size} 个实体（扫描时刻已恢复）")
            }
        }
        val now = System.currentTimeMillis()
        if (lastDiscoveryMs > 0 && now - lastDiscoveryMs < DISCOVERY_TTL_MS) return discoveredEntities
        val fresh = runCatching { discoverEntities() }
            .onFailure { Log.w(TAG, "自动发现扫描失败（沿用旧清单，下轮重试）: ${it.message}") }
            .getOrNull() ?: return discoveredEntities
        discoveredEntities = fresh
        lastDiscoveryMs = now
        saveDiscoveredCache(fresh)
        return fresh
    }

    /**
     * 自动发现主流程：
     *  1. searchViaBot 既有配置的 bot（searchBotUsername + searchBotFallbacks）无条件并入
     *    （src=botconfig——用户显式配置过的搜索 bot 是最强信号）；
     *  2. 全量会话枚举（网关 listDiscoveryProfiles，1h TTL）按特征分类：
     *     - 群/频道：标题命中 [GROUP_NAME_KEYWORDS] 且不含排除词 → src=name；
     *       标题不中的超级群/频道再查简介，命中"发关键词"类说明 → src=desc（基础群无简介可查）；
     *     - 私聊：GetUser 实证 UserTypeBot，且名字/用户名命中 [BOT_NAME_KEYWORDS] → src=botname
     *      （标题不中的私聊也查用户名——bot 名可能是 emoji，用户名才是稳定特征）；
     *  3. 旧清单里本轮枚举没见到的实体保守保留（GetChats 只回已加载前缀，缺席≠已退群）；
     *     但 src=name 且本轮见到了却没再命中的，按最新结论剔除（群改名即退编）。
     *
     * 实证类请求（SearchPublicChat/GetUser/GetSupergroupFullInfo）逐次计收割预算，
     * 且单次扫描总量封顶 [MAX_ENRICH_PER_SCAN]；本会话已实证过的对象（probedXxx 集合）
     * 重扫零记账零请求——首扫后稳态近零成本，预算大头留给真正的消息收割。
     */
    private suspend fun discoverEntities(): List<SearchEntity> {
        val out = LinkedHashMap<Long, SearchEntity>()
        var enrichLeft = MAX_ENRICH_PER_SCAN

        // 1) 配置的搜索 bot（src=botconfig）
        val configuredBots = (listOf(prefs.searchBotUsername) + prefs.searchBotFallbacks.split(','))
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        for (name in configuredBots) {
            if (name !in probedBotNames) {
                // R378 刀1 换道：发现实证归辅助车道（tryAcquireAux，受保底份额约束；
                // 触发链零碰=R377 懒门域边界内守，只换记账口）
                if (enrichLeft <= 0 || !harvestBudget.tryAcquireAux()) break
                enrichLeft--
            }
            val chatId = gateway.resolveBotChatId(name) ?: continue // 失败下轮重扫重试
            probedBotNames += name
            out[chatId] = SearchEntity(chatId, SearchEntity.KIND_BOT, "@$name", "botconfig")
        }

        // 2) 全量枚举分类
        val profiles = gateway.listDiscoveryProfiles()
        val seenIds = HashSet<Long>(profiles.size * 2)
        for (p in profiles) {
            seenIds += p.chatId
            if (out.containsKey(p.chatId)) continue
            when (p.chatKind) {
                TdLibGateway.DiscoveryProfile.CHAT_SUPERGROUP,
                TdLibGateway.DiscoveryProfile.CHAT_BASICGROUP -> {
                    if (matchesKeywords(p.title, GROUP_NAME_KEYWORDS, GROUP_NAME_EXCLUDES)) {
                        out[p.chatId] = SearchEntity(p.chatId, SearchEntity.KIND_GROUP, p.title, "name")
                    } else if (p.chatKind == TdLibGateway.DiscoveryProfile.CHAT_SUPERGROUP &&
                        p.supergroupId !in probedSupergroupIds
                    ) {
                        // 标题不中的超级群/频道：查简介找"发关键词"类玩法说明
                        // （R378 刀1 换道：简介实证归辅助车道 tryAcquireAux）
                        if (enrichLeft <= 0 || !harvestBudget.tryAcquireAux()) continue
                        enrichLeft--
                        val desc = gateway.fetchSupergroupDescription(p.supergroupId) ?: continue
                        probedSupergroupIds += p.supergroupId
                        if (matchesKeywords(desc, GROUP_DESC_KEYWORDS, emptyList())) {
                            out[p.chatId] = SearchEntity(p.chatId, SearchEntity.KIND_GROUP, p.title, "desc")
                        }
                    }
                }
                TdLibGateway.DiscoveryProfile.CHAT_PRIVATE -> {
                    if (p.userId == 0L) continue
                    if (p.userId !in probedUserIds) {
                        // （R378 刀1 换道：私聊实证归辅助车道 tryAcquireAux）
                        if (enrichLeft <= 0 || !harvestBudget.tryAcquireAux()) continue
                        enrichLeft--
                    }
                    val (isBot, username) = gateway.probeUserBotProfile(p.userId) ?: continue
                    probedUserIds += p.userId
                    if (!isBot) continue
                    if (matchesKeywords(p.title, BOT_NAME_KEYWORDS, BOT_NAME_EXCLUDES) ||
                        matchesKeywords(username, BOT_NAME_KEYWORDS, BOT_NAME_EXCLUDES)
                    ) {
                        out[p.chatId] = SearchEntity(
                            p.chatId, SearchEntity.KIND_BOT,
                            p.title.ifBlank { "@$username" }, "botname",
                        )
                    }
                }
                else -> {} // 秘密聊天等其他类型：发现直接忽略
            }
        }

        // 3) 旧实体合并：本轮缺席的保守保留；name 源见到了却未再命中的剔除
        for (e in discoveredEntities) {
            if (e.src == "name" && e.chatId in seenIds && !out.containsKey(e.chatId)) continue
            out.putIfAbsent(e.chatId, e)
        }
        val list = out.values.toList().take(MAX_DISCOVERED_ENTITIES)
        Log.i(
            TAG,
            "自动发现完成：实体=${list.size}（枚举=${profiles.size} 实证请求=${MAX_ENRICH_PER_SCAN - enrichLeft}）",
        )
        return list
    }

    /** 关键词 contains 匹配（小写归一）：排除词命中一票否决；关键词命中其一即 true */
    private fun matchesKeywords(text: String, keywords: List<String>, excludes: List<String>): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (excludes.any { it in lower }) return false
        return keywords.any { it in lower }
    }

    /** 自动发现清单 → prefs JSON（GroupHarvester 唯一写入方；手动清单不进缓存） */
    private fun saveDiscoveredCache(list: List<SearchEntity>) {
        runCatching {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject()
                        .put("chatId", e.chatId)
                        .put("kind", e.kind)
                        .put("title", e.title)
                        .put("src", e.src)
                )
            }
            prefs.harvestDiscoveredJson = arr.toString()
            prefs.harvestDiscoveredAtMs = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "发现清单持久化失败（下轮重写）: ${it.message}") }
    }

    /** prefs JSON → 自动发现清单；损坏按空表重建（不崩，重扫会重新填充） */
    private fun loadDiscoveredCache(): List<SearchEntity> {
        val raw = runCatching { prefs.harvestDiscoveredJson }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("chatId", 0L)
                if (id == 0L) return@mapNotNull null
                SearchEntity(
                    id, o.optInt("kind", SearchEntity.KIND_GROUP),
                    o.optString("title", ""), o.optString("src", "cache"),
                )
            }.distinctBy { it.chatId }
        }.getOrElse {
            Log.w(TAG, "发现清单缓存解析失败（按空表重建）: ${it.message}")
            emptyList()
        }
    }

    /**
     * 白名单用户名 → userId 解析（网关侧进程内缓存；失败的本轮跳过下轮重试）。
     * R378 刀3 缓存免重记：peekBotUserId 命中→直接收编**零记账零网络**（记账与
     * 真实发送对齐——稳态 32 令牌/h 纯浪费归零；冷启动首轮 8 令牌一次性缓存重建）；
     * 未命中→辅助车道记账+真解析（失败下轮重试口径原样，受保底份额约束）。
     * 预算耗尽时本轮白名单有多少算多少，trustAllBots 路径仍可工作（GetUser
     * 实证兜住识别能力）。
     */
    private suspend fun resolveWhitelist(): Set<Long> {
        val names = prefs.harvestBotUsernames.split(',')
            .map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }.distinct()
        val out = LinkedHashSet<Long>()
        for (n in names) {
            // 刀3：缓存命中先于记账口（时序锚）——网关侧 botUserIdCache 暖后零烧
            val cached = gateway.peekBotUserId(n)
            if (cached != null) {
                out += cached
                continue
            }
            // R378 刀1：未命中才烧辅助令牌真解析（受主收割保底份额约束）
            if (!harvestBudget.tryAcquireAux()) break
            val id = runCatching { gateway.resolveBotUserId(n) }.getOrNull() ?: continue
            out += id
        }
        return out
    }

    /**
     * 单实体增量收割：从游标往后拉新消息（GetChatHistory 新→旧分页）。
     * 群组/频道与 bot 私聊共用同一游标+分页骨架，差异只在配对解析（processBatch 按 kind 分支）。
     *
     * 预算双闸：每页拉取前先过本实体份额闸（[share]，均分口径——实体多了每个少分点，
     * flood 预算总量不变），再过全局预算闸；任一耗尽即收工，绝不让排前面的实体吃光预算。
     *
     * 游标口径（防 flood 优先）：成功拉到多少算多少，游标直接推进到已见最大 id——
     * 预算耗尽导致的中段漏收**不回补**（下轮从新区间继续；收割是机会型资产，允许有洞）。
     * 首跑（游标=0）只拉最新一页建基线，避免历史雪崩。
     *
     * 深挖·资源导向自适应翻页（用户直觉口径："一直挖到没有想要的资源为止"）：
     *  - 入库闸（广告密度过滤）：页广告密度 ≥[AD_PAGE_SPAM_RATIO]（页内 t.me 链接
     *    spam 命中占比）判纯广告坑位页，**跳过入库**——头部广告坑位不带翻页停顿，
     *    径直越过；学习跳过区（上轮实锤的坑位深度）同样直接跳过入库；
     *  - 停机条件（资源导向）：越过头部坑位带进入资源带后，每页评估"有没有真资源"
     *    （= 本页有非广告、非邀请、未拉黑的过闸链接），有就继续翻；
     *    **连续 [EMPTY_PAGE_STOP]=2 页无真资源才停**（第一页空可能是偶然，再确认一页；
     *    第二页还空说明资源带见底——"第八页驴唇不对马嘴、再翻一页果然没有 → 停"）；
     *  - 单实体单轮硬顶 [MAX_PAGES_PER_CYCLE]=8 页（防失控；跳过的广告页照样记
     *    flood 预算——GetChatHistory 只能顺序翻页，无法物理跳过）；
     *  - per-entity 学习：本轮实测头部连续广告页数写回 AnswerStore（adDepthPages），
     *    下轮开头直接按学习值跳过前 N 页入库；学习值 7 天未再证自动失效，
     *    实测为 0 自校正清零——广告坑位轮换/撤投时不会僵化误跳真资源。
     */
    private suspend fun harvestEntity(entity: SearchEntity, whitelistIds: Set<Long>, share: Int) {
        val chatId = entity.chatId
        var spent = 0
        // 份额闸：份额未用完且全局预算有余额才记账放行
        fun tryShare(): Boolean {
            if (spent >= share) return false
            if (!harvestBudget.tryAcquire()) return false
            spent++
            return true
        }
        val cursor = answerStore.cursor(chatId)
        val learnedSkip = answerStore.adDepthPages(chatId) // 上轮学习的广告坑位深度
        val collected = ArrayList<TdApi.Message>()
        var from = 0L
        var pageIdx = 0            // 本轮已拉页数（含跳过的广告坑位页）
        var maxSeenId = 0L         // 本轮全部已见消息最大 id（含跳过页——游标要越过广告坑位）
        var skipRegionAds = 0      // 学习跳过区内实测仍为广告的页数（学习自校正输入）
        var leadingAdPages = 0     // 跳过区之后仍连续命中的广告坑位页数（学习自校正输入）
        var inLeadingAds = true    // 仍处于头部广告坑位带（学习跳过区 + 连续广告页）：只挖不停
        var emptyStreak = 0        // 资源带内连续无真资源页数（≥[EMPTY_PAGE_STOP] 停机）
        while (pageIdx < MAX_PAGES_PER_CYCLE) {
            if (!tryShare()) {
                Log.w(TAG, "份额/预算耗尽，本轮收工 chatId=$chatId（用=$spent/份=$share 总剩=${harvestBudget.remaining()}）")
                break
            }
            val page = gateway.fetchRawMessages(chatId, from, 0, PAGE_SIZE)
                ?: break // 拉取失败：本轮放弃（游标保持，下轮重试同区间）
            if (page.isEmpty()) break
            val fresh = if (cursor > 0L) page.filter { it.id > cursor } else page
            if (fresh.isNotEmpty()) maxSeenId = maxOf(maxSeenId, fresh.maxOf { it.id })
            val (linkTotal, linkSpam, linkResource) = pageLinkSignals(fresh)
            // 页广告密度 ≥[AD_PAGE_SPAM_RATIO] 判纯广告坑位（无链接页密度按 0：不是广告页但也没资源）
            val isAdPage = linkTotal > 0 &&
                linkSpam.toDouble() / linkTotal >= AD_PAGE_SPAM_RATIO
            val inLearnedSkip = pageIdx < learnedSkip
            if (inLearnedSkip && isAdPage) skipRegionAds++
            if (inLeadingAds) {
                when {
                    // 学习跳过区：上轮实锤的坑位，直接跳过入库，不影响停机计数（仍实测密度自校正）
                    inLearnedSkip -> {}
                    // 头部新实锤广告坑位页：跳过入库继续挖，不计入无资源停机（坑位不等于资源见底）
                    isAdPage -> leadingAdPages++
                    // 首个非广告页：越过坑位带进入资源带，入库并启用资源导向停机
                    else -> {
                        inLeadingAds = false
                        collected += fresh
                        if (linkResource == 0) emptyStreak = 1
                    }
                }
            } else {
                // 资源带：广告页仍跳过入库（入库闸）；停机看"本页有没有真资源"——
                // 广告坑位页与无链接页都算无真资源，连续两页即资源带见底
                if (!isAdPage) collected += fresh
                if (!isAdPage && linkResource > 0) emptyStreak = 0 else emptyStreak++
            }
            pageIdx++
            // 首跑只建基线一页；拉到已见区间（fresh<page）或不足一页（到头）即停；
            // 资源带内连续 [EMPTY_PAGE_STOP] 页无真资源停机（坑位带内永不触发——只挖不停）
            if (cursor == 0L || fresh.size < page.size || page.size < PAGE_SIZE) break
            if (!inLeadingAds && emptyStreak >= EMPTY_PAGE_STOP) break
            from = page.minOf { it.id }
        }
        // 坑位深度学习自校正：本轮实测头部连续广告页数 = 跳过区实测仍为广告的
        // + 跳过区后新发现的广告页；实测为 0 写 0 清零（头部已无广告）。一轮没拉到
        // 任何页（预算耗尽/拉取失败）不写——无观测不更新，保留上轮学习值
        if (pageIdx > 0) {
            val newDepth = skipRegionAds + leadingAdPages
            if (newDepth != learnedSkip) {
                answerStore.recordAdDepth(chatId, newDepth)
                Log.i(TAG, "chatId=$chatId 广告坑位深度学习: $learnedSkip → $newDepth 页")
            }
        }
        // 游标越过全部已见消息（含跳过的广告坑位页）——不把广告页留到下轮重复拉
        if (maxSeenId > 0L) answerStore.advanceCursor(chatId, maxSeenId)
        if (collected.isEmpty()) return
        processBatch(entity, collected, whitelistIds)
    }

    /**
     * 一页消息的链接三指标（深挖判据共用，单趟统计）：(链接总数, spam 链接数, 真资源链接数)。
     *  - spam = 带广告追踪参数（[AdBlacklist.isSpamLink]）或指向已拉黑广告频道
     *    （[AdChannelClassifier.isBlocked]，黑名单频道在坑位里反复出现是最强广告信号）；
     *  - 真资源 = 非 spam 且非邀请链（t.me/+…/joinchat 不入库也不算资源，与 recordLinks 同口径）
     *    ——停机条件的"本页有没有真资源"判据（入库闸之后的过闸链接计数；
     *    bot 归属等更严过滤在 processBatch，这里的口径是"值得继续挖"的宽松上限）。
     * 纯本地正则判定，零网络零预算。
     */
    private fun pageLinkSignals(msgs: List<TdApi.Message>): Triple<Int, Int, Int> {
        var total = 0
        var spam = 0
        var resource = 0
        for (m in msgs) {
            for (link in gateway.extractMessageTmeLinks(m)) {
                total++
                if (AdBlacklist.isSpamLink(link)) {
                    spam++
                    continue
                }
                if (adClassifier.isBlocked(
                        AnswerStore.chatIdFromLink(link),
                        AnswerStore.usernameFromLink(link).lowercase(),
                    )
                ) {
                    spam++
                    continue
                }
                val path = link.removePrefix("https://t.me/")
                if (path.startsWith("+") || path.startsWith("joinchat", ignoreCase = true)) continue
                resource++
            }
        }
        return Triple(total, spam, resource)
    }

    // ---------------- 配对解析 ----------------

    /**
     * 一批新消息的配对解析（归属过滤主战场），按实体类型分两路：
     *
     * 群模式（KIND_GROUP）：只认"sender 是 bot 且（带链接 或 回复本群消息）"的消息为应答候选；
     * 关键词配对优先同页原消息，fallback 到 replyTo 内嵌 content / quote 片段。
     *
     * 私聊模式（KIND_BOT）：对方身份在发现阶段已实证 UserTypeBot，**零请求采信**，
     * 免白名单/GetUser 实证；别人发给 bot 的查询看不见，关键词配对本人发过的
     * 最近文本消息（[extractKeywordPrivate]）。自己的消息永不采（isOutgoing 跳过），
     * 但留作配对原料（byId 全集含本人消息）。
     */
    private suspend fun processBatch(
        entity: SearchEntity,
        msgs: List<TdApi.Message>,
        whitelistIds: Set<Long>,
    ) {
        val chatId = entity.chatId
        val isBotChat = entity.kind == SearchEntity.KIND_BOT
        val byId = msgs.associateBy { it.id }
        var pairs = 0
        var links = 0
        var spamBlocked = 0
        for (m in msgs) {
            if (m.isOutgoing) continue // 自己的消息永不采（本收割器零发送，双保险）
            val senderUser = (m.senderId as? TdApi.MessageSenderUser)?.userId
            if (senderUser == null && !isBotChat) continue // 群模式：频道身份发言（MessageSenderChat）不可能是应答 bot
            val msgLinks = gateway.extractMessageTmeLinks(m)
            // R211 @用户名提及（bot 应答正文里的 @channel——t.me 链接提取的既有口径不认它）：
            // 与 t.me 链接同形态归一，应答形态闸与白名单外 GetUser 实证触发一并认它
            // （提及即资源指名）。bot 自指过滤在 recordLinks 前统一做；@人（非频道）
            // 提及由下游非频道闸（直荐 isChannel + 聚合过滤）兜底，不靠这里猜。
            val mentionLinks = gateway.extractMessageMentionLinks(m)
            val answerLinkCount = msgLinks.size + mentionLinks.size
            val replyTo = m.replyTo as? TdApi.MessageReplyToMessage
            val repliesToThisChat = replyTo != null && replyTo.chatId == chatId
            // 应答形态闸：既无链接（含提及）又不回复本会话消息的，不可能是搜索应答——
            // 在 bot 身份实证（GetUser，计预算）之前先挡掉，省请求
            if (answerLinkCount == 0 && !repliesToThisChat) continue
            // bot 身份判定：私聊模式零请求采信（发现阶段已实证）；群模式走
            // 白名单快速通道（零请求），白名单外仅在"带链接（含提及）"（应答实锤形态）
            // 时才值得 GetUser 实证——无链接消息也走实证会把预算烧在普通群友身上
            // （聊天群动辄几百个活人 sender，逐个 GetUser 是预算黑洞）
            val isBot = when {
                isBotChat -> true
                senderUser == null -> false // 防御：上面已 continue，正常到不了这里
                else -> senderUser in whitelistIds ||
                    (answerLinkCount > 0 &&
                        runCatching { prefs.harvestTrustAllBots }.getOrDefault(true) &&
                        probeIsBot(senderUser))
            }
            if (!isBot) continue
            // 广告过滤前置（P0 #2）：R61 组件级判定——正文/caption 含垃圾词的 bot 消息
            // 整条约谈（原语义保留，链接也不收）；仅按钮含垃圾词不连坐整条
            if (AdBlacklist.isSpam(extractBodyText(m))) {
                spamBlocked++
                continue
            }
            // 仅按钮含垃圾词：逐组件剔除垃圾按钮的链接，正文与干净按钮链接照常收
            val (cleanLinks, droppedBtnLinks, bodyLinkCount) = gateway.extractMessageAnswerLinksFiltered(m)
            if (droppedBtnLinks > 0) {
                Log.d(
                    TAG,
                    "chatId=$chatId 按钮含垃圾词但正文干净：剔按钮链接 $droppedBtnLinks 条" +
                        "保留正文链接 $bodyLinkCount 条",
                )
            }
            if (cleanLinks.isEmpty() && mentionLinks.isEmpty()) {
                // 无链接 bot 消息（R211 含提及口径——纯 @提及应答不会漏到这里之后）：
                // 人机验证门只记诊断（"请选择计算结果👇"实锤形态，
                // 判定文本仍用含按钮的全文本——验证门动作词常写在按钮上）
                if (AdBlacklist.looksLikeCaptcha(extractAllText(m))) {
                    captchaSeen++
                    Log.w(TAG, "chatId=$chatId 疑似人机验证门（累计 $captchaSeen）")
                }
                continue
            }
            // 配对（P0 #3 归属过滤核心）：群模式优先认回复本群消息的配对（bot 主动发帖
            // 只建边）；配不上时 R211 回看兜底——搜索群主流形态是「群友发词 → bot 秒回
            // 结果」不带回复引用，回看本批内应答之前紧邻窗口的人类文本消息当词源。
            // 私聊模式配对本人最近发出的文本消息（搜索 bot 很少用 replyTo，同形态）。
            var keyword: String? = null
            if (isBotChat) {
                keyword = extractKeywordPrivate(m, replyTo, byId)
            } else {
                if (repliesToThisChat && replyTo != null) {
                    keyword = extractKeyword(m, replyTo, byId, whitelistIds)
                }
                if (keyword == null) {
                    keyword = extractKeywordFallback(m, byId, whitelistIds)
                }
            }
            // R211 提及链接并入（自指防污染：已知搜索 bot 配置名 + 私聊实体自身名——
            // "更多结果 @bot"回链不是资源答案；群模式其他 bot 自提及为已知残余，
            // 由解析后非频道/答案消费侧闸兜底）
            val mergedLinks = if (mentionLinks.isEmpty()) cleanLinks else {
                val selfNames = gateway.knownSearchBotNames().map { it.lowercase() }.toHashSet()
                if (isBotChat) selfNames += entity.title.removePrefix("@").lowercase()
                cleanLinks + mentionLinks.filterNot { link ->
                    AnswerStore.usernameFromLink(link).lowercase() in selfNames
                }
            }
            // 内联按钮"文本↔链接"配对线索随答案入库（429 封死 SearchPublicChat 时的
            // 零网络标题兜底——jisou 类 bot 的按钮文本通常就是频道名）
            // R60 自指防污染：私聊实体应答者即 bot 本身（title=@名）；群模式应答 bot
            // 用户名结构未知传 null，由网关"已知搜索 bot 配置名"口径兜底
            recordLinks(
                chatId, mergedLinks, keyword, gateway.extractLinkTitleHints(m),
                responderUsername = if (isBotChat) entity.title.removePrefix("@") else null,
            )
            links += mergedLinks.size
            if (keyword != null) pairs++
        }
        Log.i(
            TAG,
            "收割 chatId=$chatId(${entity.src}/${if (isBotChat) "bot私聊" else "群"}): 新消息=${msgs.size} " +
                "应答对=$pairs 链接=$links 广告拦截=$spamBlocked 预算剩=${harvestBudget.remaining()}",
        )
    }

    /**
     * 私聊 bot 应答的关键词配对：别人的查询看不见，只能配对**本人发过的**关键词——
     *  1. replyTo 指向的消息在本批内：直接取（私聊里本人提问正是查询词，
     *     与群模式"拒配本人消息"相反——私聊中本人消息是唯一可见的查询源）；
     *  2. replyTo 内嵌 content 兜底（与群模式同口径）；
     *  3. 否则取本批内该应答之前 [PAIR_WINDOW_SEC] 秒内本人最近一条文本消息
     *    （消息 id 单调递增，id 小=更早；maxByOrNull(id) 即"最近一条"）。
     * 任一来源都要过 [sanitizeKeyword]（长度/链接/广告/指令前缀过滤）。
     */
    private fun extractKeywordPrivate(
        reply: TdApi.Message,
        replyTo: TdApi.MessageReplyToMessage?,
        byId: Map<Long, TdApi.Message>,
    ): String? {
        val orig = replyTo?.let { byId[it.messageId] }
        if (orig != null) {
            if (reply.date - orig.date !in 0..PAIR_WINDOW_SEC) return null
            return sanitizeKeyword((orig.content as? TdApi.MessageText)?.text?.text)
        }
        val embedded = (replyTo?.content as? TdApi.MessageText)?.text?.text
        if (embedded != null) {
            if (replyTo != null && replyTo.originSendDate > 0 &&
                reply.date - replyTo.originSendDate !in 0..PAIR_WINDOW_SEC
            ) return null
            return sanitizeKeyword(embedded)
        }
        val candidate = byId.values
            .filter { it.isOutgoing && it.id < reply.id && reply.date - it.date in 0..PAIR_WINDOW_SEC }
            .maxByOrNull { it.id }
        return sanitizeKeyword((candidate?.content as? TdApi.MessageText)?.text?.text)
    }

    /** GetUser 实证 bot（预算记账；结果进程内缓存；预算耗尽按非 bot 静默降级） */
    private suspend fun probeIsBot(userId: Long): Boolean {
        botFlagCache[userId]?.let { return it }
        if (!harvestBudget.tryAcquire()) return false
        val isBot = runCatching { gateway.isBotUser(userId) }.getOrDefault(false)
        botFlagCache[userId] = isBot
        return isBot
    }

    /**
     * R211 回看配对（群模式兜底，[pickFallbackQuestion] 的收割侧薄壳）：
     * bot 应答没用 replyTo 时（搜索群主流形态：发词→秒回结果不带引用），
     * 回看本批内应答之前的紧邻窗口里最近一条人类文本消息当词源。
     */
    private fun extractKeywordFallback(
        reply: TdApi.Message,
        byId: Map<Long, TdApi.Message>,
        whitelistIds: Set<Long>,
    ): String? {
        val replySender = (reply.senderId as? TdApi.MessageSenderUser)?.userId
        return sanitizeKeyword(
            pickFallbackQuestion(reply.date, reply.id, replySender, byId.values.toList(), whitelistIds),
        )
    }

    /**
     * 从应答消息提取"用户关键词"（归属过滤的配对侧）：
     *  1. 同页原消息（最准）：必须非 bot 所发（防 bot 互聊配对）、必须是文本消息、
     *     且在 [PAIR_WINDOW_SEC] 时间窗内（迟到的应答不配——可能是人工回复而非搜索结果）；
     *  2. replyTo 内嵌 content（javap 实证 1.8.67 可空携带被回复消息内容）；
     *  3. quote 引用片段兜底（可能只是部分文本，仍优于没有）。
     * 任一来源都要过 [sanitizeKeyword]（长度/链接/广告/指令前缀过滤）。
     */
    private fun extractKeyword(
        reply: TdApi.Message,
        replyTo: TdApi.MessageReplyToMessage,
        byId: Map<Long, TdApi.Message>,
        whitelistIds: Set<Long>,
    ): String? {
        val orig = byId[replyTo.messageId]
        if (orig != null) {
            if (orig.isOutgoing) return null
            val origSender = (orig.senderId as? TdApi.MessageSenderUser)?.userId
            if (origSender != null && origSender in whitelistIds) return null // bot→bot 不算用户提问
            // 时间窗：应答距提问超过 5 分钟视为人工回复/无关对话
            if (orig.date > 0 && reply.date - orig.date !in 0..PAIR_WINDOW_SEC) return null
            val text = (orig.content as? TdApi.MessageText)?.text?.text
            return sanitizeKeyword(text)
        }
        // 原消息不在本批（恰在窗口边界外）：内嵌 content 兜底，originSendDate 做时间窗校验
        val embedded = (replyTo.content as? TdApi.MessageText)?.text?.text
        if (embedded != null) {
            if (replyTo.originSendDate > 0 && reply.date - replyTo.originSendDate !in 0..PAIR_WINDOW_SEC) return null
            return sanitizeKeyword(embedded)
        }
        // quote 片段兜底（originSendDate 时间窗校验同上）
        val quote = replyTo.quote?.text?.text
        if (quote != null && replyTo.originSendDate > 0 &&
            reply.date - replyTo.originSendDate !in 0..PAIR_WINDOW_SEC
        ) return null
        return sanitizeKeyword(quote)
    }

    /**
     * 关键词清洗：trim 后 1..64 字符、不含 t.me 链接、不是 bot 指令、自身不是广告词。
     * 不满足返回 null（该应答只建边不进答案库）。
     */
    private fun sanitizeKeyword(text: String?): String? {
        val kw = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (kw.isEmpty() || kw.length > MAX_KEYWORD_CHARS) return null
        if (kw.startsWith("/")) return null // /start /help 等指令不是资源关键词
        if (kw.contains("t.me/", ignoreCase = true)) return null
        if (AdBlacklist.isSpam(kw)) return null
        return kw
    }

    /**
     * 消息正文文本（R61 组件级广告判定的"正文段"）：正文/媒体 caption，不含按钮——
     * 按钮垃圾词由网关 extractMessageAnswerLinksFiltered 逐组件剔链，不再连坐整条。
     */
    private fun extractBodyText(m: TdApi.Message): String =
        when (val c = m.content) {
            is TdApi.MessageText -> c.text?.text.orEmpty()
            is TdApi.MessagePhoto -> c.caption?.text.orEmpty()
            is TdApi.MessageVideo -> c.caption?.text.orEmpty()
            is TdApi.MessageAudio -> c.caption?.text.orEmpty()
            is TdApi.MessageDocument -> c.caption?.text.orEmpty()
            else -> ""
        }

    /** 消息全文本（人机验证门诊断用）：正文/媒体 caption + inline 按钮文本与链接 */
    private fun extractAllText(m: TdApi.Message): String {
        val sb = StringBuilder()
        when (val c = m.content) {
            is TdApi.MessageText -> sb.append(c.text?.text.orEmpty())
            is TdApi.MessagePhoto -> sb.append(c.caption?.text.orEmpty())
            is TdApi.MessageVideo -> sb.append(c.caption?.text.orEmpty())
            is TdApi.MessageAudio -> sb.append(c.caption?.text.orEmpty())
            is TdApi.MessageDocument -> sb.append(c.caption?.text.orEmpty())
            else -> {}
        }
        (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows?.forEach { row ->
            row?.forEach { btn ->
                sb.append(' ').append(btn?.text.orEmpty())
                (btn?.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url?.let { sb.append(' ').append(it) }
            }
        }
        return sb.toString()
    }

    /**
     * 链接入库：图谱边（group_search 类型，广告参数链接剔除）+ 有关键词时进答案库。
     * 邀请链接（t.me/+…/joinchat）由网关 sniffLink 口径天然丢弃，答案库同样跳过
     * （无法归因到稳定频道，且广告最爱用短命邀请链）。
     *
     * R60 自指防污染：先整表剔除"指向搜索实体自身的深链"（t.me/<应答bot>?start=…
     * 相关热词回链——soso 功能地图实证形态，点开=替用户再搜一次，**不是资源答案**），
     * 再进金矿钩子/建边/入库——自指链连热词计数与图谱首见证据都不该贡献，
     * 入库会造成答案递归自指污染（答案里套着搜索引擎自己）。
     *
     * @param responderUsername 应答实体用户名（私聊 bot 实体=bot 自身 @名；群模式结构
     *   未知传 null，由网关"已知搜索 bot 配置名"口径兜底判定）
     */
    private fun recordLinks(
        groupChatId: Long,
        links: List<String>,
        keyword: String?,
        linkHints: Map<String, HintTitle> = emptyMap(),
        responderUsername: String? = null,
    ) {
        val cleanLinks = links.filterNot { link ->
            gateway.isSelfReferentialSearchLink(link, responderUsername).also { selfRef ->
                if (selfRef) {
                    Log.d(TAG, "recordLinks: 剔除自指深链 $link（指向搜索实体自身的热词回链，非资源答案）")
                }
            }
        }
        if (cleanLinks.isEmpty()) return
        // P4-A 金矿钩子（热词计数 + 图谱首见登记）：必须在下方建边/入库前调用——
        // "图谱从未见过"判定依赖未建边的现场；过滤与比对全部自闭环在 GoldmineHooks 内
        GoldmineHooks.onHarvested(keyword, cleanLinks)
        for (link in cleanLinks) {
            // 广告频道分类器闸（P2 伪装资源频道）：广告参数链接/已拉黑广告频道一律丢弃并记嫌疑，
            // 完全覆盖原 AdBlacklist.isSpamLink 拦截语义
            if (adClassifier.onHarvestedLink(link)) continue
            val path = link.removePrefix("https://t.me/")
            if (path.startsWith("+") || path.startsWith("joinchat", ignoreCase = true)) continue
            gateway.recordGroupSearchLink(groupChatId, link)
            // 解析管线入队（顺序刻意在 recordGroupSearchLink 之后：图谱先攒引用证据，
            // 已解析的名字在这里即决补边并出队，不留下重复证据）；用户名链接才入队，
            // 直链/邀请链在 enqueueHarvest 内部自然跳过
            entityResolver.enqueueHarvest(groupChatId, link)
            if (keyword != null) answerStore.recordAnswer(
                keyword, groupChatId, link,
                linkHints[link]?.title.orEmpty(), linkHints[link]?.members ?: 0,
            )
        }
    }

    companion object {
        private const val TAG = "GroupHarvester"

        /**
         * 发现图谱新边类型（P1 #3）：资源搜索群 bot 应答链接。
         * DiscoveryGraph.recordEdge 的 type 是自由字符串，无需改图谱代码；
         * DiscoveryRanker.edgeTypeWeight 对未知类型按 link 档 1.0 前向兼容（L481 注释口径）。
         */
        const val EDGE_GROUP_SEARCH = "group_search"

        /** 滑动窗口长度（1 小时） */
        private const val WINDOW_MS = 3_600_000L

        /**
         * R378 刀1 主收割保底份额（%）：收割池 60/h 原是单令牌桶，辅助管线（发现
         * 实证 30+白名单 32+Resolver 常驻）重启首时窗可吃满整池把主收割饿死（R372
         * 机制乙判词：重启后首时窗 62>60、轮首总余量 22 实锚自洽——24h 零新答案
         * 的结构性病根）。50%=辅助合计 ≤30/60、主收割保底 ≥30/60（与
         * SearchQuotaLedger 保底区同概念族收敛）；池总量常量零改零新池。
         */
        private const val HARVEST_MAIN_RESERVE_PERCENT = 50

        /** 每页拉取条数（增量收割：新消息新→旧，50 条/页足够覆盖 15 分钟的群内流量） */
        private const val PAGE_SIZE = 50

        /**
         * 单实体单周期翻页硬顶（深挖自适应的安全阀，含跳过的广告坑位页）：
         * 正常路径"广告页跳过 → 首个真资源页入库即停"远用不到此值；硬顶防的是
         * "整页全是广告链接"的极端实体一页接一页深挖烧穿份额预算
         */
        private const val MAX_PAGES_PER_CYCLE = 8

        /** 页广告密度阈值：页内 t.me 链接 spam 命中占比 ≥50% 判纯广告坑位页（入库闸：跳过入库） */
        private const val AD_PAGE_SPAM_RATIO = 0.5

        /** 资源带停机条件：连续多少页无真资源（过闸链接为 0）即判资源带见底收工 */
        private const val EMPTY_PAGE_STOP = 2

        /** 应答配对时间窗（秒）：bot 应答通常在秒内；超窗视为人工对话不配对 */
        private const val PAIR_WINDOW_SEC = 300

        /** 关键词最长字符（超长的"回复"多半是转发文案/广告而非搜索词） */
        private const val MAX_KEYWORD_CHARS = 64

        /** 自动发现重扫间隔（1 小时）：用户新加的搜索群最迟 1 小时自动纳入收割 */
        private const val DISCOVERY_TTL_MS = 3_600_000L

        /**
         * 单次发现扫描的实证类请求上限（SearchPublicChat/GetUser/GetSupergroupFullInfo 合计，
         * 另受收割预算硬闸）。首扫后 probedXxx 集合+网关进程缓存命中，稳态重扫近零请求；
         * 首扫没扫完的（人类私聊/冷门群）随后续每小时扫描逐步收敛
         */
        private const val MAX_ENRICH_PER_SCAN = 30

        /** 自动发现实体数硬顶（防误分类/缓存无限膨胀；超出按发现顺序截断。手动清单不受此限） */
        private const val MAX_DISCOVERED_ENTITIES = 50

        /** 群/频道名关键词（小写 contains 命中其一即"搜索相关"） */
        private val GROUP_NAME_KEYWORDS = listOf("搜", "资源", "找片", "寻片", "搜片", "搜万物", "找资源", "search")

        /** 群名排除词（防"人力资源/招聘/搜救"类正常群被误收：命中排除词一票否决） */
        private val GROUP_NAME_EXCLUDES = listOf("人力", "招聘", "求职", "搜救")

        /** 群简介"发关键词"类玩法说明词（contains 命中即搜索群——简介是运营者的玩法声明，精度高） */
        private val GROUP_DESC_KEYWORDS = listOf("发关键词", "发送关键词", "发片名", "发剧名")

        /** bot 名/用户名关键词（小写 contains；须先经 UserTypeBot 实证，防误伤同名人类用户） */
        private val BOT_NAME_KEYWORDS = listOf("搜", "soso", "jisou", "search", "找片", "寻片")

        /** bot 名排除词（防"索尼"类品牌 bot 误收） */
        private val BOT_NAME_EXCLUDES = listOf("索尼", "搜救")
    }
}

/**
 * 收割实体三源合并（R183 抽纯函数锚：手动清单 → 用户勾选群 → 自动发现，
 * 前源优先、按 chatId 去重；手动 id 命中自动清单时沿用自动侧 kind——用户把
 * bot 私聊 id 手填进群清单也能按私聊收，旧语义原样保留）。供
 * [GroupHarvester.currentEntities] 与单测共用同一实现（零镜像）。
 */
internal fun mergeHarvestEntities(
    manualIds: List<Long>,
    userSelected: List<SearchEntity>,
    auto: List<SearchEntity>,
): List<SearchEntity> {
    val byId = LinkedHashMap<Long, SearchEntity>()
    for (raw in manualIds) {
        val known = auto.firstOrNull { it.chatId == raw }
        byId[raw] = known?.copy(src = "manual")
            ?: SearchEntity(raw, SearchEntity.KIND_GROUP, "手动群", "manual")
    }
    // R183 宝藏二：用户勾选群（手动清单之后、自动发现之前——同优先级档）
    for (e in userSelected) byId.putIfAbsent(e.chatId, e)
    for (e in auto) byId.putIfAbsent(e.chatId, e)
    return byId.values.toList()
}

/**
 * R211 回看配对时间窗（秒）：比 replyTo 配对窗（GroupHarvester.PAIR_WINDOW_SEC=300）
 * 刻意收紧——回看是在**没有引用锚点**的情况下猜提问，活跃闲聊群里窗口越宽越容易
 * 张冠李戴；搜索 bot 的"发词→秒回"形态 90 秒窗已绰绰有余。
 */
internal const val FALLBACK_PAIR_WINDOW_SEC = 90

/**
 * R211 回看配对纯函数（单测锚点）：从收割批候选消息集里挑应答之前
 * [FALLBACK_PAIR_WINDOW_SEC] 窗内最近的一条「人类文本消息」当关键词源。
 * 排除：本人消息（isOutgoing）、应答者自己的消息（bot 补发/编辑的后续）、
 * 白名单 bot 消息（bot 互聊不是提问）、频道身份发言（MessageSenderChat）、
 * 非文本消息（媒体消息的文本不在正文里）。返回原文文本（清洗在调用侧）。
 */
internal fun pickFallbackQuestion(
    replyDateSec: Int,
    replyId: Long,
    replySenderId: Long?,
    candidates: List<TdApi.Message>,
    whitelistIds: Set<Long>,
): String? {
    val best = candidates.asSequence()
        .filter { !it.isOutgoing && it.id < replyId }
        .filter { replyDateSec - it.date in 0..FALLBACK_PAIR_WINDOW_SEC }
        .mapNotNull { c ->
            val sender = (c.senderId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
            if (sender == replySenderId) return@mapNotNull null
            if (sender in whitelistIds) return@mapNotNull null
            val text = (c.content as? TdApi.MessageText)?.text?.text ?: return@mapNotNull null
            c to text
        }
        .maxByOrNull { it.first.id }
        ?: return null
    return best.second
}
