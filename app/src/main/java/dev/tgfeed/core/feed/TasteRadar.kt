package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * 口味雷达（WOW-SEARCH W5 落地）：把"搜索框的输入只来自用户手指"反过来——
 * 从观看习惯里自动提取口味词，每天用 SearchPublicPosts 的**每日免费配额**
 * 自动巡逻全网公开帖子，命中的新频道聚合为发现页候选（来源标"雷达"）。
 *
 * 链路口径（全部复用既有实证零件）：
 *  - 口味词提取（R54 原料换血 + R65 第四级，四级优先级）：① 群答案库 hintTitle 分词
 *    （hits 加权，真资源名是主原料）→ ② caption 内容词（观看时长加权积累表）
 *    → ③ 全网热词榜补充（R65 热词源：HotWordBoard 每日收割，新鲜闸 3 天）
 *    → ④ watch_events 频道权重 × 频道标题分词（兜底，仅前三级不足时补齐）。
 *    统一过黑名单压掉"视频/合集/资源"等通用词，取前 [TASTE_KEYWORD_COUNT] 个。
 *  - 巡逻：逐词调 TdLibGateway.searchPublicPosts（starCount 恒 0，红线继承
 *    SEARCH-ARCH §5.3）；每词之间隔 [PATROL_WORD_INTERVAL_MS] 防 flood wait；
 *    每轮最多巡逻词数 = 提取出的词数（≤5），与每日免费配额量级匹配。
 *  - 配额闸：每词发出前读网关侧的配额快照（FoundPublicPosts.searchLimits），
 *    remainingFreeQueryCount ≤ 0 或 areLimitsExceeded 即提前收工——绝不付费巡逻。
 *  - 节流：每天最多巡逻一次（[PATROL_INTERVAL_MS] 窗口）。lastPatrolTs 落盘在
 *    stateFile（独立小 JSON，LocalStore 同款"临时文件 + rename"原子写）——
 *    LocalStore 在任务冻结边界内，不往里加字段，节流戳自立门户。
 *  - Mock 网关：无服务端搜索，patrol 直接空转返回空表（Mock 优雅降级红线）。
 *
 * 单写入方/线程模型：patrol 由 FeedViewModel 在 IO 协程里串行触发（VM 侧另有防重入闸），
 * 本类内部无可变共享状态（除节流戳文件），无并发隐患。
 */
class TasteRadar(
    private val store: LocalStore,
    private val gateway: TdGateway,
    // 频道标题补充源：FeedViewModel 注入内存态标题表（订阅+频道列表+当前 Feed 条目），
    // 让"看过但未订阅"的频道也能贡献口味词；缺省只用 LocalStore 订阅表
    private val titleProvider: () -> Map<Long, String> = { emptyMap() },
    // 节流戳落盘位置（{"lastPatrolTs": Long}）；null = 仅内存节流（重启后当天可再巡逻一次）
    private val stateFile: File? = null,
) {

    // ---------------- 口味词提取 ----------------

    /**
     * 提取口味词表（同步、纯本地；store/答案库/热词榜都走内存缓存，开销可忽略，零网络）。
     *
     * R54 原料换血 + R65 第四级——按优先级四级取词（旧版只靠频道标题，拆出来全是行业通用词）：
     *  1. 答案库标题词（主原料）：群答案库里攒的 hintTitle 是应答按钮配对出的
     *     真资源名（入库前已净身 2..40 字符），远比频道标题的行业自定位具体。
     *     词得分 = 命中答案的 hits 之和（同一资源被多人搜出 = 置信度信号），
     *     3~4 字 CJK 整段专名双倍加权（完整资源名压过泛两字词）；bot 深链答案
     *     的"标题"是按钮标签而非资源名，不进原料；
     *  2. caption 内容词（次原料，保留 ×3 口径）：观看时按观看时长积累的
     *     caption 分词表，表内按权重降序（×3 只影响同表排序语义，跨表靠优先级）；
     *  3. 全网热词榜（R65 补充）：每日收割的"全网在搜什么"（HotWordBoard，
     *     新鲜闸 3 天；收割侧已过 AdBlacklist 合规闸）——与口味词互补，
     *     优先级定在 caption 词之后、频道标题兜底之前；
     *  4. 频道标题词（兜底）：仅当 1+2+3 拼不满 [TASTE_KEYWORD_COUNT] 时补齐——
     *     打分口径不变（watch_events 频道权重 × 标题分词，秒划不计、单条封顶
     *     [MAX_EVENT_WATCH_SEC] 秒防挂机污染），保证新装机/答案库空时不空屏。
     * [STOP_WORDS] 黑名单对四级原料统一生效（穷人版 IDF：通用词不携带口味信息）。
     * 可读性过滤由 [tokenize] 天然完成，不重复劳动：拉丁词须 ≥2 字符且含字母
     * （纯数字不收）、emoji 不匹配任何分词正则（纯 emoji 不产词）、CJK 单字丢弃。
     * 返回前 [TASTE_KEYWORD_COUNT] 个词；四级原料全空返回空表。
     */
    fun extractTasteKeywords(): List<String> {
        // ---- 原料一：答案库标题词（主原料）----
        val answerScore = HashMap<String, Double>()
        var answerTitleCount = 0
        val answers = runCatching {
            (gateway as? TdLibGateway)?.groupHarvester?.answerStore?.allAnswers()
        }.getOrNull().orEmpty()
        for (a in answers) {
            if (a.hintTitle.isBlank()) continue
            // bot 深链答案的"标题"是应答按钮标签（实测几乎全是同一句功能话术），
            // 不是资源名，不进口味原料
            if (a.link.contains("?start=")) continue
            answerTitleCount++
            for (term in tokenizeProperName(a.hintTitle)) {
                if (term in STOP_WORDS) continue
                // 整段专名双倍加权：3~4 字 CJK 整段在本分词口径下只可能来自整段收录
                // （bigram 恒为 2 字），它是完整资源名，双倍权重压过泛两字词
                val w = a.hits.toDouble() * (if (term.length >= 3 && term.any { it in '一'..'鿿' }) 2.0 else 1.0)
                answerScore.merge(term, w, Double::plus)
            }
        }
        val answerWords = answerScore.entries.sortedByDescending { it.value }.map { it.key }

        // ---- 原料二：caption 内容词（真内容口味，观看时长加权、秒划不喂的既有口径）----
        val captionWords = runCatching { store.loadTasteWords() }.getOrDefault(emptyMap())
        val captionScore = HashMap<String, Double>()
        for ((term, w) in captionWords) {
            if (term in STOP_WORDS) continue
            captionScore.merge(term, w * 3.0, Double::plus)
        }
        val contentWords = captionScore.entries.sortedByDescending { it.value }.map { it.key }

        // 主原料优先、内容词次之，去重拼接
        val picked = (answerWords + contentWords).distinct().toMutableList()

        // ---- 原料三（R65 新增）：全网热词榜补充（"全网在搜什么"，与口味词互补）----
        // 优先级定在 caption 词之后、频道标题兜底之前：榜词是外部热度信号（不代表
        // 本机口味），但比频道标题的行业自定位更接近真实搜索意图。
        // 词表来自 HotWordBoard（每日收割，freshWords 自带 3 天新鲜闸——过期/缺席
        // 返回空表，本级自然跳过）；收割入库前已过 AdBlacklist 合规闸，
        // 这里再过一遍 STOP_WORDS（通用词不携带口味信息，与各级原料同口径）
        var boardWordCount = 0
        if (picked.size < TASTE_KEYWORD_COUNT) {
            val boardWords = runCatching {
                (gateway as? TdLibGateway)?.groupHarvester?.hotWordBoard?.freshWords()
            }.getOrNull().orEmpty()
            for (w in boardWords) {
                if (picked.size >= TASTE_KEYWORD_COUNT) break
                if (w in STOP_WORDS) continue
                if (!picked.contains(w)) {
                    picked.add(w)
                    boardWordCount++
                }
            }
        }

        // ---- 原料四：频道标题词（兜底，仅当主原料+内容词+热词榜不足时补齐）----
        var fallbackCount = 0
        if (picked.size < TASTE_KEYWORD_COUNT) {
            val events = runCatching { store.loadWatchEvents() }.getOrDefault(emptyList())
            if (events.isNotEmpty()) {
                // 频道观看权重聚合（秒划/负反馈不算口味证据）
                val channelWeight = HashMap<Long, Double>()
                for (e in events) {
                    if (e.watchSec < SKIP_FAST_SEC) continue
                    channelWeight.merge(e.chatId, e.watchSec.coerceAtMost(MAX_EVENT_WATCH_SEC).toDouble(), Double::plus)
                }
                if (channelWeight.isNotEmpty()) {
                    // 频道标题表：注入源优先（含"看过未订阅"频道），订阅表兜底
                    val titles = HashMap<Long, String>()
                    runCatching { titleProvider() }.getOrDefault(emptyMap())
                        .forEach { (id, t) -> if (t.isNotBlank()) titles[id] = t }
                    runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
                        .forEach { if (it.title.isNotBlank()) titles.putIfAbsent(it.chatId, it.title) }
                    val termScore = HashMap<String, Double>()
                    for ((chatId, w) in channelWeight) {
                        val title = titles[chatId] ?: continue // 无标题频道无法贡献口味词
                        for (term in tokenize(title)) {
                            if (term in STOP_WORDS) continue
                            termScore.merge(term, w, Double::plus)
                        }
                    }
                    for (term in termScore.entries.sortedByDescending { it.value }.map { it.key }) {
                        if (picked.size >= TASTE_KEYWORD_COUNT) break
                        if (!picked.contains(term)) {
                            picked.add(term)
                            fallbackCount++
                        }
                    }
                }
            }
        }
        val keywords = picked.take(TASTE_KEYWORD_COUNT)
        // R65 起词表可能含热词榜收割词——日志只记结构性计数，绝不利词表原文
        Log.d(TAG, "extractTasteKeywords: 答案标题=$answerTitleCount 答案词=${answerWords.size} 内容词=${captionWords.size} 热词榜词=$boardWordCount 兜底频道词=$fallbackCount 词表数=${keywords.size}")
        return keywords
    }

    /**
     * 分词（与 W1 共现词典同口径）：拉丁/数字按整词切（小写、≥2 字符、须含字母）；
     * CJK 连续段单字丢弃，2 字整段收，3~4 字整段 + 滑窗 bigram 都收
     * （"柚子猫"这类整体专名靠整段命中，bigram 兜底更长标题里的碎片词）。
     */
    internal fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        LATIN_TOKEN_REGEX.findAll(text.lowercase()).forEach { m ->
            val t = m.value
            if (t.any { it.isLetter() }) out += t // 纯数字（年份/番号）不收
        }
        CJK_RUN_REGEX.findAll(text).forEach { m ->
            val run = m.value
            when {
                run.length == 1 -> Unit // 单字不成词
                run.length == 2 -> out += run
                else -> {
                    if (run.length <= MAX_CJK_WORD_LEN) out += run // 短整段当专名收
                    for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
                }
            }
        }
        return out
    }

    /**
     * 资源名专用分词（R54 答案库标题词专用，与 [tokenize] 的唯一差异在 CJK 短整段）：
     * 3~4 字整段收为整体专名后**不再**滑窗出内部 bigram——资源名场景里整段就是
     * 完整名字（如四字剧名），再拆 bigram 只会产出"防失/失联"这类半截碎片词，
     * 既难看又稀释真专名的得分。>4 字长段仍只出 bigram（与 [tokenize] 同口径），
     * 拉丁词规则不变（≥2 字符、须含字母）。同标题内去重后返回。
     */
    private fun tokenizeProperName(text: String): List<String> {
        val out = ArrayList<String>()
        LATIN_TOKEN_REGEX.findAll(text.lowercase()).forEach { m ->
            val t = m.value
            if (t.any { it.isLetter() }) out += t // 纯数字不收
        }
        CJK_RUN_REGEX.findAll(text).forEach { m ->
            val run = m.value
            when {
                run.length == 1 -> Unit // 单字不成词
                run.length == 2 -> out += run
                run.length <= MAX_CJK_WORD_LEN -> out += run // 短整段=完整专名，只收整段
                else -> for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
            }
        }
        return out.distinct()
    }

    // ---------------- P2：DIN 目标注意力的词画像出口 ----------------

    /**
     * DIN 目标注意力的长期词权重画像（P2，FeedViewModel 页内重排消费）：
     * 口味词表（观看时从 caption 积累的 word→得分，loadTasteWords）滤掉 [STOP_WORDS]
     * 后按最高分归一化到 (0,1]——通用词不过滤会把所有候选的相关度分整体抬高，
     * 相对区分度不变但绝对阈值（P2_ACTIVE_SCORE）随之失真。
     * store 读走内存缓存，开销可忽略；任何异常返回空表（调用方按无画像直通处理）。
     */
    internal fun dinWordProfile(): Map<String, Double> {
        val raw = runCatching { store.loadTasteWords() }.getOrDefault(emptyMap())
        if (raw.isEmpty()) return emptyMap()
        val max = raw.entries.filter { it.key !in STOP_WORDS }.maxOfOrNull { it.value } ?: 0.0
        if (max <= 0.0) return emptyMap()
        return raw.entries
            .filter { it.key !in STOP_WORDS && it.value > 0.0 }
            .associate { it.key to it.value / max }
    }

    /**
     * 打分专用分词（P2：DIN 相关度分 / 马尔可夫转移记账的候选词集）：
     * [tokenize] 之后滤 [STOP_WORDS] 并去重——打分语境里"视频/合集"这类通用词
     * 只灌水不带口味信息（与 extractTasteKeywords 的读侧过滤同口径）。
     */
    internal fun tokenizeForScoring(text: String): List<String> =
        tokenize(text).filter { it.isNotBlank() && it !in STOP_WORDS }.distinct()

    // ---------------- 每日巡逻 ----------------

    /**
     * 巡逻一轮（挂起，**必须 IO 协程调用**）：逐口味词发一次 SearchPublicPosts
     * （offset="" 只取首页 [PATROL_PAGE_LIMIT] 条——巡逻要的是"有没有新频道"，
     *  不翻页，把免费配额留给更多词），命中频道按命中条数聚合去重返回。
     *
     * 闸与节流（任一不满足即空转返回空表，不抛错）：
     *  - Mock/占位网关：无服务端搜索，直接空转；
     *  - 每日一次：距上次巡逻 < [PATROL_INTERVAL_MS] 跳过（节流戳见 stateFile）；
     *  - 口味词表为空（观看数据不足）跳过；
     *  - 配额闸：每词发出前查网关配额快照，免费额度耗尽/超限即提前收工（红线：
     *    starCount 恒 0 由网关方法签名保证，这里保证"额度没了就不再问"）。
     *
     * 节流戳在词表非空后、首发查询前落盘：即使本轮全失败也占用今天的巡逻额度，
     * 防"失败→下分钟重试→再失败"的配额烧毁循环（免费配额是日级稀缺资源）。
     * 单词失败只跳过该词（runCatching 静默），不影响其余词。
     */
    suspend fun patrol(): List<ChannelBrief> {
        val real = gateway as? TdLibGateway ?: return emptyList() // Mock 优雅降级
        val now = System.currentTimeMillis()
        val last = readLastPatrolTs()
        if (last > 0L && now - last < PATROL_INTERVAL_MS) {
            Log.d(TAG, "patrol: 距上次巡逻 ${(now - last) / 3_600_000}h，未达每日节流窗口，跳过")
            return emptyList()
        }
        // 词表长度按搜索页建议数提取，巡逻只取前 [PATROL_MAX_WORDS] 个（配额闸）
        val keywords = extractTasteKeywords().take(PATROL_MAX_WORDS)
        if (keywords.isEmpty()) {
            Log.d(TAG, "patrol: 口味词表为空（观看数据不足），跳过")
            return emptyList()
        }
        writeLastPatrolTs(now)
        val hitCount = LinkedHashMap<Long, Int>() // 命中频道 → 命中条数
        val titles = HashMap<Long, String>()
        var queried = 0
        for ((index, kw) in keywords.withIndex()) {
            // 配额闸：上次响应已告知额度耗尽/超限即停（首发查询无快照，天然放行）
            val quota = real.publicPostQuota
            if (quota != null && (quota.third || quota.first <= 0)) {
                Log.i(TAG, "patrol: 公开搜索免费配额耗尽（剩 ${quota.first}/${quota.second}），本轮提前收工")
                break
            }
            val items = runCatching {
                real.searchPublicPosts(kw, offset = "", limit = PATROL_PAGE_LIMIT).first
            }.onFailure {
                Log.w(TAG, "patrol: 口味词「$kw」巡逻失败（跳过该词）: ${it.message}")
            }.getOrNull() ?: continue
            queried++
            for (item in items) {
                if (item.chatId == 0L) continue
                hitCount.merge(item.chatId, 1, Int::plus)
                if (item.channelTitle.isNotBlank()) titles.putIfAbsent(item.chatId, item.channelTitle)
            }
            // 词间喘息防 flood wait（最后一词后不必再等）
            if (index < keywords.lastIndex) delay(PATROL_WORD_INTERVAL_MS)
        }
        Log.i(TAG, "patrol: 巡逻 $queried/${keywords.size} 词 $keywords，命中频道 ${hitCount.size} 个")
        return hitCount.entries
            .sortedByDescending { it.value } // 多词共同命中的频道排前（交叉佐证=更对味）
            .take(PATROL_MAX_CHANNELS)
            .map { (id, _) -> ChannelBrief(id, titles[id] ?: "频道 $id", null) }
    }

    // ---------------- 节流戳（stateFile 落盘；LocalStore 冻结，自立小文件） ----------------

    /** 读上次巡逻时间戳；文件缺失/损坏按 0（从未巡逻）处理，不崩溃 */
    private fun readLastPatrolTs(): Long {
        val f = stateFile ?: return 0L
        return runCatching {
            if (!f.isFile) return@runCatching 0L
            JSONObject(f.readText()).optLong("lastPatrolTs", 0L)
        }.getOrDefault(0L)
    }

    /** 写巡逻时间戳：临时文件 + rename 原子落盘（LocalStore 同款纪律）；失败仅记日志 */
    private fun writeLastPatrolTs(ts: Long) {
        val f = stateFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("lastPatrolTs", ts).toString())
            if (!tmp.renameTo(f)) {
                runCatching { f.writeText(JSONObject().put("lastPatrolTs", ts).toString()) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "patrol: 节流戳落盘失败（内存态不受影响）: ${it.message}") }
    }

    companion object {
        private const val TAG = "TasteRadar"

        /** 每日节流窗口：22 小时（比 24h 略短，允许"每天晚饭后打开 App"这类自然漂移，仍能一天一次） */
        private const val PATROL_INTERVAL_MS = 22L * 3600 * 1000

        /** 单条事件权重封顶（秒）：防挂机/忘关播放把单频道权重刷爆 */
        private const val MAX_EVENT_WATCH_SEC = 300

        /** 口味词表长度（搜索页"猜你想搜"建议数；R54 三级原料拼接后按此截断） */
        private const val TASTE_KEYWORD_COUNT = 8

        /** 每轮巡逻的词数上限（每日免费配额有限，逐词一次查询，词数=查询数） */
        private const val PATROL_MAX_WORDS = 5

        /** 每词巡逻条数（只取首页，不翻页） */
        private const val PATROL_PAGE_LIMIT = 20

        /** 词间间隔（毫秒）：串行发词防 flood wait */
        private const val PATROL_WORD_INTERVAL_MS = 600L

        /** 一轮巡逻产出的频道候选上限 */
        private const val PATROL_MAX_CHANNELS = 20

        /** CJK 整段专名最大字数（超过则只出 bigram） */
        private const val MAX_CJK_WORD_LEN = 4

        /** 拉丁/数字词：≥2 字符（调用侧再要求至少含一个字母，滤掉纯数字） */
        private val LATIN_TOKEN_REGEX = Regex("[a-z0-9]{2,}")

        /** CJK 连续段 */
        private val CJK_RUN_REGEX = Regex("[一-鿿]+")

        /**
         * 通用词黑名单（穷人版 IDF）：这些词在资源频道标题里遍地都是，不携带口味信息。
         * 覆盖分词器的全部产出形态：单字（防御，分词器实际不产单字）、bigram、
         * 3~4 字整段（如"官方频道"）、拉丁整词。可按实测词表随时增补。
         */
        private val STOP_WORDS = setOf(
            // 单字防御（分词器单字已丢弃，双保险）
            "的", "了", "号", "吧",
            // 中文通用 bigram
            "视频", "频道", "合集", "资源", "分享", "更新", "官方", "最新", "每日",
            "精选", "推荐", "大全", "社区", "中心", "高清", "免费", "在线", "播放",
            "观看", "下载", "福利", "影视", "娱乐", "天下", "世界", "之家",
            // 中文 3~4 字整段（整段专名收词形态的配套黑名单）
            "官方频道", "每日更新", "资源分享", "视频合集", "精选合集", "在线观看",
            // 英文通用词
            "video", "videos", "channel", "channels", "official", "daily", "new",
            "best", "free", "the", "and", "for", "hub", "club", "world", "top",
        )
    }
}
