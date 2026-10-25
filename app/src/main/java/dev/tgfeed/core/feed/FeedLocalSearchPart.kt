package dev.tgfeed.core.feed

import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// ---------------- 本地即时搜索（搜索页分层呈现：L3 本地即时层，毫秒级先上屏） ----------------

internal fun <V> FeedViewModel.putLru(map: LinkedHashMap<String, Pair<Long, V>>, key: String, value: Pair<Long, V>, max: Int) {
    synchronized(localCacheLock) {
        map[key] = value
        while (map.size > max) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
    }
}

/** 本地缓存 TTL 读：命中且未过期返回结果，否则 null */
internal fun <V> FeedViewModel.getLru(map: LinkedHashMap<String, Pair<Long, V>>, key: String, now: Long): V? =
    synchronized(localCacheLock) {
        map[key]?.takeIf { now - it.first < LOCAL_SEARCH_CACHE_TTL_MS }?.second
    }

/**
 * 本地即时搜索（SEARCH-ARCH §2.4 L3 本地即时层 + SEARCH-QUALITY §2/§6 重写版，只读不改状态）：
 * 收藏 + 稍后再看 + 观看历史 + 会话内已拉条目 + 本地深度索引，纯本地 0 网络请求。
 *
 * 与旧版（整串 contains 谓词 + 固定源优先级拼接）的差别：
 *  1. 匹配词位化（[SearchTextKit.analyze]）：CJK bigram 覆盖/拉丁整词/同义词组/简繁折叠
 *     ——"宫崎骏合集"能命中"宫崎骏作品高清合集"，简体能命中繁体 caption；
 *  2. 全部源过同一把 [searchRelevance] 尺子打分，按 rel×来源先验 降序返回——
 *     低相关命中不再凭"来自收藏"压过高相关命中（旧版本地段内部无质量序）；
 *  3. 补"会话内已拉条目"源（_items，先验 1.05）：刚刷过想再找回来的条目不再漏
 *     （SEARCH-ARCH L3 设计里有、实现里漏的一路，§3-3）；
 *  4. 深度索引磁盘召回（SEARCH-INVERTED → SQLite FTS5 版，替代旧 §6 逐词位多路
 *     contains 扫描）：[DeepSearchIndex] search 一次调用直接返回物化命中
 *     （精确=FTS5 词位 AND + 段级复核；模糊=词位 OR + bm25 + 同义词扩展，
 *     命中相册自动展开同组媒体），毫秒级与索引规模无关、内存零常驻；命中数达
 *     [LOCAL_INDEX_RECALL_BUDGET] 短路——防超长结果集的全量打分成本。
 *
 * SEARCH-PERF §4 提速（打字 300ms 防抖下每词一次的重复成本收敛）：
 *  5. query 结果缓存：同词 60s 内直接返回（删字回退/同词回退场景零扫描）；
 *  6. 代次作废：连打时旧词扫描在检查点（每 256 条/每召回批）发现已有更新词即中止，
 *     不再为注定被丢弃的旧词结果付完全部扫描成本（UI 侧本就有过期词防护）；
 *  7. 文本折叠走 [foldCached] 缓存（见 searchRelevance），同一条目不重复 fold。
 *  （旧 §4 词位召回缓存随倒排索引落地移除：查表本身微秒级，缓存纯属多余簿记。）
 *
 * 来源先验（保留"我的资产优先"语义，转为乘性系数）：收藏 1.3 / 稍后 1.2 / 历史 1.1 /
 * 会话内 1.05 / 深度索引 1.0。rel=0（词位全不命中）的条目不收录——与旧 contains
 * 谓词的过滤语义一致。**R304 OCR 收录臂例外**：rel=0 的低信息 caption 条目若其 OCR
 * 文本已并入索引词位（item_ocr.applied=1，[ocrIndexedTextsImpl] 只读批查），以
 * [ocrRescueScore] 正当分值救援收录——索引词位配方含 OCR（R292），收录尺同源。
 * 媒体类型 tab / FeedFilter 由搜索页在上屏前自行套用。
 * 挂起函数：内部切 IO 语境（索引首载可能读盘），调用方任意协程可直调。
 */
internal suspend fun FeedViewModel.localSearchInstantImpl(query: String): List<VideoItem> =
    withContext(Dispatchers.IO) {
        val gen = localSearchGen.incrementAndGet()
        val now = System.currentTimeMillis()
        val q = query.trim()
        val fuzzy = prefs.fuzzySearch // 模糊开关每次现读：切开关后下一次打字/重搜即按新口径
        // query 结果缓存命中：直接返回（打字删字回退/同词回退零扫描）。
        // 键带口径位（searchCacheKey）：精确/模糊结果分开缓存，切开关不命中旧口径
        getLru(localQueryCache, searchCacheKey(q, fuzzy), now)?.let { return@withContext it }
        val aq = SearchTextKit.analyze(q)
        if (aq.phrase.isEmpty() && aq.slots.isEmpty()) return@withContext emptyList()
        // 代次检查：连打时本词已被更新的词取代 → 中止（结果注定被 UI 过期词防护丢弃）
        fun stale(): Boolean = gen != localSearchGen.get()
        // key 去重保序（先出现的高先验源占坑），按 rel×先验 降序输出
        val scored = LinkedHashMap<String, Pair<VideoItem, Double>>()
        // R304 OCR 收录臂留证：rel≤0 被弃且具救援资格（[needsOcrRescue]：低信息 caption——
        // 与 apply Job 并词位判定同构的唯一族群）的条目+来源先验，全源扫描完后批查
        // 已并索引的 OCR 文本救援（见下方 OCR 收录段）；高信息/已命中条目不进站零开销
        val ocrDropped = ArrayList<Pair<VideoItem, Double>>()
        fun collect(items: List<VideoItem>, prior: Double) {
            var n = 0
            for (item in items) {
                if ((++n and 0xFF) == 0 && stale()) return // 检查点：每 256 条看一次代次
                if (item.key in scored) continue
                val rel = searchRelevance(item, aq, fuzzy)
                if (rel <= 0.0) {
                    if (needsOcrRescue(rel, item.caption)) ocrDropped += item to prior
                    continue // 无相关性不收录（精确模式=有段缺失；模糊=词位全空）
                }
                scored[item.key] = item to rel * prior
            }
        }
        collect(favoriteItems, 1.3)
        collect(watchLaterItems, 1.2)
        collect(historyReplayItems(), 1.1)
        collect(_items.value, 1.05) // 会话内已拉条目（§3-3）
        if (stale()) return@withContext emptyList()
        // 深度索引召回（SQLite FTS5 磁盘索引版，内存倒排表 OOM 事故的最终解法）：
        // deepIndex.search 一次调用直接返回物化结果（磁盘查询毫秒级、内存零常驻，
        // 相册组已在索引侧展开），旧 searchRefs/materialize 分块物化机制已删除。
        // 索引层只做召回与粗序，收录/精排口径仍由 searchRelevance 统一把关
        // （精确=段级 AND 缺段过滤；模糊=词位覆盖打分）；任何失败只丢这一路。
        runCatching {
            val found = deepIndex.search(q, fuzzy)
            var budget = LOCAL_INDEX_RECALL_BUDGET
            for (item in found) {
                if (budget <= 0 || stale()) break // 短路：召回够多就停；代次变迁即弃
                if (item.key in scored) continue
                val rel = searchRelevance(item, aq, fuzzy)
                if (rel <= 0.0) {
                    if (needsOcrRescue(rel, item.caption)) ocrDropped += item to 1.0 // 索引腿先验
                    continue
                }
                scored[item.key] = item to rel * 1.0
                budget--
            }
        }.onFailure { e ->
            // 兜底保留（含 OOM），但不再静默：记录查询词与索引规模，便于线上排查
            val stats = runCatching { deepIndex.totalStats() }.getOrNull()
            android.util.Log.e(
                "FeedViewModel",
                "深度索引召回失败 query=\"$q\" fuzzy=$fuzzy " +
                    "频道数=${stats?.first ?: -1} 总条数=${stats?.second ?: -1} 已收录=${scored.size}",
                e,
            )
        }
        // —— R304 OCR 收录臂（修 V9-③ 实锤的「词位进索引≠召回生效」第二现场）——
        // 原尺全灭的低信息条目批查已并索引的 OCR 文本（item_ocr.applied=1 唯一真值，
        // 只读连接同 OcrHitLookup 族零阻塞纪律），命中者以「caption+OCR 合并文本」过
        // 同一把 [textMatchScore] 核（[ocrRescueScore]）拿正当分值收录，乘原来源先验——
        // 不是开后门豁免。试点期（applied=1 恒空）/表不在/任何异常=空映射零变化；
        // 代次已旧则跳过（结果注定被 UI 过期防护丢弃，与既有部分结果缓存口径一致）。
        if (ocrDropped.isNotEmpty() && !stale()) {
            val ocrTexts = runCatching { ocrIndexedTextsImpl(ocrDropped.map { it.first }) }
                .getOrDefault(emptyMap())
            for ((item, prior) in ocrDropped) {
                if (item.key in scored) continue
                val ocr = ocrTexts[item.key] ?: continue
                val rel = ocrRescueScore(
                    capFold = foldCached(item.key, item.caption),
                    ocrText = ocr,
                    audioFold = if (item.mediaType == 2) {
                        foldCached(item.key + "|a", item.audioTitle + " " + item.audioPerformer)
                    } else {
                        ""
                    },
                    channelFold = foldCached("ch:" + item.chatId, item.channelTitle),
                    aq = aq,
                    fuzzy = fuzzy,
                )
                if (rel <= 0.0) continue // OCR 文本也不含查询词位：救援失败，维持丢弃
                scored[item.key] = item to rel * prior
            }
        }
        val out = scored.values.sortedByDescending { it.second }.map { it.first }
        putLru(localQueryCache, searchCacheKey(q, fuzzy), now to out, LOCAL_QUERY_CACHE_MAX)
        out
    }

/**
 * 口味词建议（搜索页空态"猜你想搜"chips 数据源）：
 * 透传 [TasteRadar.extractTasteKeywords] 的前 [limit] 个词——观看记录提取、
 * 纯本地同步计算（store 全走内存缓存，开销可忽略）。
 * tasteRadar 引擎本体保持私有，这里只开这个只读小口；任何异常返回空表。
 */
internal fun FeedViewModel.tasteSuggestionsImpl(limit: Int = 6): List<String> =
    runCatching { tasteRadar.extractTasteKeywords().take(limit) }.getOrDefault(emptyList())

/** R46 方案 b：本地缓存白嫖名单的回看窗口（近 7 天观看事件涉及的频道=用户最近刷过的频道） */
internal const val LOCAL_GRAB_LOOKBACK_MS = 7L * 24 * 60 * 60_000L

/** 本地即时搜索缓存 TTL（毫秒）：query 结果与词位召回同口径；索引后台缓增长，短 TTL 平衡新鲜度 */
private const val LOCAL_SEARCH_CACHE_TTL_MS = 60_000L

/** 本地即时搜索 query 结果缓存容量 */
private const val LOCAL_QUERY_CACHE_MAX = 30
