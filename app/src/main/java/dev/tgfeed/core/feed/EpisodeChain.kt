package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import kotlin.math.max
import kotlin.math.min

// ---------------- R142 追剧模式：分P识别（纯函数，单测见 EpisodeChainTest.kt） ----------------

/**
 * 剧集链识别——纯函数、零 Android 依赖。
 *
 * 背景：TG 资源频道常把一部剧"拆条"成多条消息发全集（caption 用 S01E02 / 第N集 /
 * （N） / 上中下 / Part N 之类编号），或把整季塞进一个相册组（mediaAlbumId 同组）。
 * Feed 既有的顺序连播只认"列表下一条"，频道把多部剧交错发、或相册成员与散帖混排
 * 时就会串台。本文件把"哪些条目属于同一部剧"识别出来，并给出链内播放顺序。
 *
 * 三个证据（按强度递减，全部来自任务口径）：
 *  1) 相册组：同 chatId + 同 albumId（≠0）＝同一合集——TG 里同组媒体天然是一批；
 *     相册块与"邻近的编号散帖/其他相册块"（时间邻接＋时长接近）也接链，覆盖
 *     "相册装前几集＋散帖续更"的混排形态；
 *  2) 剧名锚：caption 里编号之前的前缀，归一化（去话题/提及/画质词/标点数字）后
 *     同锚＝同剧名；两锚互为后缀也判同（"本周推荐某剧"与"某剧"这种贴前缀写法）；
 *  3) 时间邻接＋时长接近：锚为空（编号顶在句首）时回落——同频道、编号风格一致、
 *     发布间隔 ≤[ADJACENCY_WINDOW_SEC]、时长比 ≤[DURATION_RATIO_MAX] 才并链。
 *
 * 刻意保守：三条证据都不成立就保持散条（宁漏勿串台）——识别失败的回落是 Feed
 * 顺序连播，行为不劣于现状。识别只对"已加载条目集"生效，翻页加载后续集后链自动
 * 补全（链尾回落正常翻页由调用方保证）。
 * R221 起每频道候选超 [CHAIN_CANDIDATE_CAP] 条时保新弃旧（防平方级爆炸，见其
 * KDoc 的 ANR 实锤背景）。
 */

/** 时间邻接窗口（秒）：相邻两集发布间隔超过它不再并链（14 天＝周更/双周更容错） */
internal const val ADJACENCY_WINDOW_SEC = 14 * 24 * 3600

/** 时长接近上限（倍）：链内最短与最长时长比超过它视为不同内容；时长 0＝未知不参判 */
internal const val DURATION_RATIO_MAX = 2.5f

/** 编号上限：超过按"不是集号"处理（挡 (2023) 这类年份/价格误伤） */
internal const val EPISODE_NUMBER_MAX = 999

/** 括号编号上限：比通栏更严——4 位数字大概率是年份/售价不是集号 */
internal const val PAREN_NUMBER_MAX = 500

/** 剧名锚长度上限（取归一化结果的尾部）：贴长的发布习惯前缀时只认剧名本体 */
internal const val ANCHOR_MAX_LEN = 12

/**
 * R221 ANR 防护：单频道候选上限（有编号或相册成员的视频条目数）。聚类内部有
 * 剧名锚两两配对 O(K²) 与相册块两两 O(B²×块内²)，随候选数平方级增长——R212
 * 无限滚动把频道主页已加载集喂到数千条后整表重算一次可超 5s（小米 2026-10-18
 * 00:26 ANR 尸检实锤：主线程卡在相册块两两配对，Input dispatching timed out →
 * 系统杀进程＝用户口径「闪退」）。超上限只保留 Feed 序在前（最新）的候选参与
 * 聚链，尾部条目以散格呈现——追剧卡少折叠几条、宁漏勿串台的保守语义不变；
 * 调用方（频道主页）另已把聚链移出主线程，本上限是纯函数层对一切调用方
 * （含播放连播 [episodeHop]）的共享兜底。
 */
internal const val CHAIN_CANDIDATE_CAP = 800

/** 编号风格族：空锚聚类时要求风格一致（"第1集"与"EP2"混写视作不同链，防串台） */
internal enum class EpisodeMarkKind { SEASON_EP, EP_WORD, PLAIN_E, ZH_COUNT, PAREN, PART_WORD, ZH_PART }

/**
 * caption 里解析出的单集标识。
 * [number] 集号；[part] 拆段序（0=无；1/2/3=上/中/下，"第3集（上）"＝3 集 1 段）；
 * [anchor] 编号前的前缀归一化（剧名锚，空＝无锚）；
 * [season] 仅 SEASON_EP 有（S01E02 的 01，防两季同锚误并）；[raw] 命中原文（观测用）。
 * 排序/比较一律用 [seqKey]（(集号, 拆段) 字典序），展示用 [number]/[part]。
 */
internal data class EpisodeMark(
    val number: Int,
    val kind: EpisodeMarkKind,
    val raw: String,
    val season: Int?,
    val anchor: String,
    val part: Int = 0,
)

/** 序内比较键：(集号, 拆段) 字典序——"第3集上"(3,1)＜"第3集中"(3,2)＜"第4集"(4,0) */
internal fun EpisodeMark.seqKey(): Int = number * 10 + part

/** 拆段序 → 后缀字（日志标签用） */
internal fun partSuffix(part: Int): String = when (part) {
    1 -> "上"
    2 -> "中"
    3 -> "下"
    else -> ""
}

// ---------------- ① 编号解析：caption → EpisodeMark ----------------

// 编号正则族（任务口径）：S01E02 / EP\d+ / 纯 E\d+ / 第N[集话話回]（中文数字）/
// （N）/ Part N / 上中下（上部·中集·下篇、【上】、独占整行的"上"）
private val RE_SEASON_EP = Regex("""[Ss](\d{1,2})\s*[-._~]?\s*[Ee][Pp]?\s*(\d{1,4})""")
private val RE_EP_WORD = Regex("""(?<![A-Za-z0-9])[Ee][Pp]\s*[-._~]?\s*(\d{1,4})(?!\d)""")
private val RE_PLAIN_E = Regex("""(?<![A-Za-z0-9])[Ee](\d{1,3})(?!\d)""")
private val RE_ZH_COUNT = Regex("""第\s*([一二三四五六七八九十百零〇\d]{1,8})\s*[集话話回]""")
private val RE_PAREN = Regex("""[（(]\s*(\d{1,4})\s*[)）]""")
private val RE_PART_WORD = Regex("""(?i)(?<![A-Za-z0-9])part\s*[-._~]?\s*(\d{1,3})(?!\d)""")

// 上中下三种写法：词缀式（上部/中集/下篇）、括注式（【上】/[中]/（上）)、独占整行（"上"）
private val RE_ZH_PART_AFFIX = Regex("""([上中下])[部集篇]""")
private val RE_ZH_PART_BRACKET = Regex("""[【\[\（(]([上中下])[】\]）)]""")
private val RE_ZH_PART_ALONE = Regex("""(?m)^[上中下]\s*$""")

/** 中文数字值表（一~九；十/百/零在解析器里单独处理） */
private val ZH_DIGITS = mapOf(
    '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
    '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

private data class MarkCandidate(
    val start: Int,
    val number: Int,
    val kind: EpisodeMarkKind,
    val season: Int?,
    val raw: String,
)

/**
 * 解析 caption 的集号：取全串最早出现的编号命中（剧名在前、编号在后的主流写法）。
 * 拆段组合："第3集（上）"这类一集拆三段 → 集号 3 ＋段序 1（排序比较用
 * [EpisodeMark.seqKey]，"第3集上"＜"第3集中"＜"第4集"保序）；上中下与集号命中
 * 相距 ≤[PART_NEAR_CHARS] 才组合（同串里不相干的"下期预告"不掺和）。
 * 无任何命中返回 null（该条不参与链）。
 */
internal fun parseEpisodeMark(caption: String): EpisodeMark? {
    if (caption.isBlank()) return null
    val cands = mutableListOf<MarkCandidate>()
    // 季集式（S01E02）：季号进 season，集号进 number
    RE_SEASON_EP.findAll(caption).forEach { m ->
        val season = m.groupValues[1].toIntOrNull()
        val n = m.groupValues[2].toIntOrNull() ?: return@forEach
        if (n in 1..EPISODE_NUMBER_MAX) {
            cands += MarkCandidate(m.range.first, n, EpisodeMarkKind.SEASON_EP, season, m.value)
        }
    }
    fun add(m: MatchResult?, kind: EpisodeMarkKind, cap: Int = EPISODE_NUMBER_MAX) {
        if (m == null) return
        val n = m.groupValues[1].toIntOrNull() ?: return
        if (n in 1..cap) cands += MarkCandidate(m.range.first, n, kind, null, m.value)
    }
    add(RE_EP_WORD.find(caption), EpisodeMarkKind.EP_WORD)
    add(RE_PLAIN_E.find(caption), EpisodeMarkKind.PLAIN_E)
    RE_ZH_COUNT.findAll(caption).forEach { m ->
        val n = parseZhNumber(m.groupValues[1]) ?: return@forEach
        cands += MarkCandidate(m.range.first, n, EpisodeMarkKind.ZH_COUNT, null, m.value)
    }
    add(RE_PAREN.find(caption), EpisodeMarkKind.PAREN, PAREN_NUMBER_MAX)
    add(RE_PART_WORD.find(caption), EpisodeMarkKind.PART_WORD)
    // 上中下命中（值＋位置＋原文）
    val part = zhPartOf(caption)
    if (cands.isEmpty()) {
        // 只有上中下：上=1 中=2 下=3 当集号
        if (part == null) return null
        return EpisodeMark(part.value, EpisodeMarkKind.ZH_PART, part.raw, null, anchorOf(caption, part.start))
    }
    val best = cands.minByOrNull { it.start } ?: return null
    if (part != null && part.start <= best.start + best.raw.length + PART_NEAR_CHARS) {
        // 拆段组合（一集拆三段）：集号＋段序
        return EpisodeMark(
            best.number, best.kind, "${best.raw}·${part.raw}", best.season,
            anchorOf(caption, best.start), part.value,
        )
    }
    return EpisodeMark(best.number, best.kind, best.raw, best.season, anchorOf(caption, best.start))
}

/** 上中下与集号允许的组合距离（字符）：太远视为不相干的提示语 */
private const val PART_NEAR_CHARS = 12

/** 上中下命中：值（上=1 中=2 下=3）＋起始位置＋原文 */
private data class ZhPart(val value: Int, val start: Int, val raw: String)

private fun zhPartOf(caption: String): ZhPart? {
    RE_ZH_PART_AFFIX.find(caption)?.let {
        return ZhPart(partDigit(it.groupValues[1][0]), it.range.first, it.value)
    }
    RE_ZH_PART_BRACKET.find(caption)?.let {
        return ZhPart(partDigit(it.groupValues[1][0]), it.range.first, it.value)
    }
    RE_ZH_PART_ALONE.find(caption)?.let {
        val c = it.value.trim()[0]
        return ZhPart(partDigit(c), it.range.first, c.toString())
    }
    return null
}

private fun partDigit(c: Char): Int = when (c) {
    '上' -> 1
    '中' -> 2
    '下' -> 3
    else -> 0
}

/**
 * 中文数字解析（1~999）："12" 走纯数字分支；"十一"=11、"二十"=20、"一百零三"=103、
 * "三百二十五"=325；组合非法（未知字符/超界/无有效数字）返回 null。
 */
internal fun parseZhNumber(s: String): Int? {
    if (s.isEmpty()) return null
    if (s.all { it.isDigit() }) {
        return s.toIntOrNull()?.takeIf { it in 1..EPISODE_NUMBER_MAX }
    }
    var total = 0
    var current = 0
    var sawValue = false
    for (c in s) {
        when (c) {
            '零', '〇' -> sawValue = true // 补位无值
            '十' -> {
                total += if (current == 0) 10 else current * 10
                current = 0
                sawValue = true
            }
            '百' -> {
                if (current == 0) return null // "百"单用非法
                total += current * 100
                current = 0
                sawValue = true
            }
            else -> {
                val d = ZH_DIGITS[c] ?: return null
                current = d
                sawValue = true
            }
        }
    }
    val result = total + current
    return if (sawValue && result in 1..EPISODE_NUMBER_MAX) result else null
}

// ---------------- ② 剧名锚：编号前缀归一化 ----------------

private val RE_TAG = Regex("""[#＃@＠]\S+""")
private val RE_NOISE = Regex(
    """(?i)2160|1080[pi]?|720[pi]?|480[pi]?|4k|8k|2k|hdr|60帧|30帧|50帧|""" +
        """高清|超清|蓝光|国语|粤语|中字|双语|双字|无水印|完整版|全集|合集|连载|""" +
        """持续|更新|完结|补档|自剪|搬运|分享|资源|推荐|热门|抢先看|首发""",
)
private val RE_STRIP = Regex("""[\s\p{P}\p{S}\p{N}]+""")

/** 剧名锚归一化：去话题/提及、去画质与发布习惯词、去标点/数字/空白，只留剧名本字 */
internal fun normalizeAnchor(prefix: String): String =
    RE_STRIP.replace(RE_NOISE.replace(RE_TAG.replace(prefix, " "), " "), "")
        .lowercase()
        .takeLast(ANCHOR_MAX_LEN)

/** 编号起始位置之前的文本 → 归一化剧名锚 */
private fun anchorOf(caption: String, markStart: Int): String =
    normalizeAnchor(caption.substring(0, markStart.coerceIn(0, caption.length)))

/** 两锚判同：等值或互为后缀（贴装饰前缀的同一剧名；过短的锚不参与，防误伤） */
internal fun anchorsMatch(a: String, b: String): Boolean {
    if (a.length < 2 || b.length < 2) return false
    if (a == b) return true
    return a.endsWith(b) || b.endsWith(a)
}

// ---------------- ③ 聚类：条目集 → 剧集链 ----------------

/** 链上单集：条目＋编号（相册空 caption 成员为 null） */
internal data class ChainEpisode(val item: VideoItem, val mark: EpisodeMark?)

/** 一条剧集链：同频道、按播放顺序（第 1 集在前） */
internal data class EpisodeChainInfo(val chatId: Long, val episodes: List<ChainEpisode>)

/** 聚类用中间节点：local＝频道内序号（并查集下标） */
private class ChainNode(val local: Int, val item: VideoItem, val mark: EpisodeMark?)

/**
 * 聚合成剧集链：对当前已加载条目集跑一遍三个证据（相册组 → 剧名锚 → 时间邻接），
 * ≥2 名成员的聚落才成为链；跨频道不并（mediaAlbumId 只在单频道内有归组意义）。
 * 只收视频条目（mediaType==0）——连播落点是 VideoPage，图文/音频另有自己的翻页链。
 */
internal fun episodeChains(items: List<VideoItem>): List<EpisodeChainInfo> {
    if (items.size < 2) return emptyList()
    // 分频道收集候选：有编号、或相册成员（albumId≠0）
    val byChannel = LinkedHashMap<Long, MutableList<ChainNode>>()
    for (it in items) {
        if (it.mediaType != 0) continue
        val mark = parseEpisodeMark(it.caption)
        if (mark == null && it.albumId == 0L) continue
        val list = byChannel.getOrPut(it.chatId) { mutableListOf() }
        // R221：候选封顶（平方级算法兜底，见 [CHAIN_CANDIDATE_CAP]）；items 按
        // Feed 序（新→旧）遍历，保新弃旧
        if (list.size >= CHAIN_CANDIDATE_CAP) continue
        list += ChainNode(list.size, it, mark)
    }
    val out = mutableListOf<EpisodeChainInfo>()
    for ((chatId, nodes) in byChannel) {
        if (nodes.size < 2) continue
        val parent = IntArray(nodes.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) {
                val n = parent[c]
                parent[c] = r
                c = n
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // 证据一（相册组）：同 albumId（≠0）直接并——相册共享 caption 的整表同号
        // 成员靠它成链，链内顺序与连播顺延见 orderChainEpisodes/episodeHop
        nodes.groupBy { it.item.albumId }
            .filterKeys { it != 0L }
            .values
            .forEach { grp -> for (k in 1 until grp.size) union(grp[0].local, grp[k].local) }

        // 证据二（剧名锚）：锚判同的散帖按发布时间排序后逐对并——与"最近一个
        // 同季成员"比较（同日异季批次插花不断链），间隔 ≤窗口（周更剧 7 天一集
        // 也连得上）、时长比 ≤上限、季号一致（或未知）
        val anchored = nodes.filter { it.mark != null && it.mark.anchor.isNotBlank() }
        if (anchored.size >= 2) {
            val byAnchor = anchored.groupBy { it.mark!!.anchor }
            // 锚互为后缀的组先归并（"本周推荐某剧"与"某剧"）
            val keys = byAnchor.keys.toList()
            val aParent = IntArray(keys.size) { it }

            fun aFind(x: Int): Int {
                var r = x
                while (aParent[r] != r) r = aParent[r]
                return r
            }

            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    if (anchorsMatch(keys[i], keys[j])) {
                        val ri = aFind(i)
                        val rj = aFind(j)
                        if (ri != rj) aParent[ri] = rj
                    }
                }
            }
            keys.indices.groupBy { aFind(it) }.values.forEach { groupKeys ->
                val members = groupKeys.flatMap { k -> byAnchor[keys[k]]!! }
                    .sortedWith(compareByDescending<ChainNode> { it.item.dateEpochSec }.thenBy { it.item.messageId })
                // 逐对并链时对"最近一个同季（或无季号）成员"比较，而不是紧邻上一条：
                // 同锚批次会被同日异季（S01/S02 两季同日发）或同锚异片（正片/片花）
                // 的成员插花，紧邻比较会把同季相邻集隔断、整条链拆散
                var prevAny: ChainNode? = null
                val prevBySeason = HashMap<Int?, ChainNode>()
                for (m in members) {
                    val s = m.mark!!.season
                    val prev = if (s == null) prevAny else (prevBySeason[s] ?: prevBySeason[null])
                    if (prev != null && prev.item.dateEpochSec != 0 && m.item.dateEpochSec != 0 &&
                        prev.item.dateEpochSec - m.item.dateEpochSec <= ADJACENCY_WINDOW_SEC &&
                        durationCompatible(prev.item, m.item) && seasonCompatible(prev.mark!!, m.mark!!)
                    ) {
                        union(prev.local, m.local)
                    }
                    prevAny = m
                    prevBySeason[s] = m
                }
                // 日期未知的成员（老数据缺省 0）：锚已是主证据，直接并入首个成员
                val head = members.firstOrNull() ?: return@forEach
                members.filter { it.item.dateEpochSec == 0 }.forEach { u ->
                    if (u !== head) union(head.local, u.local)
                }
            }
        }

        // 证据三（时间邻接）：锚为空（编号顶在句首）＋日期已知的散帖，按时间排序
        // 逐对并——风格一致、序键沿新→旧递减（正叙发布的剧在 Feed 里集号递减）、
        // 序键不同（同键＝补发/异剧）、间隔与时长达标才并
        val floating = nodes
            .filter { it.mark != null && it.mark.anchor.isBlank() && it.item.dateEpochSec != 0 }
            .sortedWith(compareByDescending<ChainNode> { it.item.dateEpochSec }.thenBy { it.item.messageId })
        for (k in 1 until floating.size) {
            val newer = floating[k - 1]
            val older = floating[k]
            if (newer.item.dateEpochSec - older.item.dateEpochSec > ADJACENCY_WINDOW_SEC) continue
            if (!durationCompatible(newer.item, older.item)) continue
            if (newer.mark!!.kind != older.mark!!.kind) continue
            if (newer.mark.seqKey() <= older.mark.seqKey()) continue
            union(newer.local, older.local)
        }

        // 证据四（相册邻接）：相册块 ↔ 邻近的编号散帖/其他相册块——拆条发全集常见的
        // "相册装前几集＋散帖续更"混排形态靠它接上；同频道、发布间隔 ≤窗口、时长
        // 兼容才并。相册块自带编号（共享 caption）时再加一道锚一致性（防同日多剧
        // 混发的相册误接别剧散帖）；纯无编号相册只靠时间＋时长。
        val albumBlocks = nodes.groupBy { it.item.albumId }
            .filterKeys { it != 0L }
            .values
            .filter { it.size >= 2 }
        val blockMemberLocals = albumBlocks.flatten().map { it.local }.toHashSet()
        for (bi in albumBlocks.indices) {
            val block = albumBlocks[bi]
            val blockDates = block.map { it.item.dateEpochSec }.filter { it != 0 }
            if (blockDates.isEmpty()) continue
            val bMin = blockDates.min()
            val bMax = blockDates.max()
            val blockAnchor = block.mapNotNull { it.mark?.anchor }.firstOrNull()
            // ① 相册块 ↔ 编号散帖（不在任何相册块里）
            nodes.filter { it.mark != null && it.local !in blockMemberLocals && it.item.dateEpochSec != 0 }
                .forEach { s ->
                    val d = s.item.dateEpochSec
                    if (d < bMin - ADJACENCY_WINDOW_SEC || d > bMax + ADJACENCY_WINDOW_SEC) return@forEach
                    if (!block.all { durationCompatible(it.item, s.item) }) return@forEach
                    val sa = s.mark!!.anchor
                    if (!blockAnchor.isNullOrBlank() && sa.isNotBlank() && !anchorsMatch(blockAnchor, sa)) {
                        return@forEach
                    }
                    union(block.first().local, s.local)
                }
            // ② 相册块 ↔ 相册块（一部剧分两个相册发）
            for (bj in bi + 1 until albumBlocks.size) {
                val other = albumBlocks[bj]
                val otherDates = other.map { it.item.dateEpochSec }.filter { it != 0 }
                if (otherDates.isEmpty()) continue
                val oMin = otherDates.min()
                val oMax = otherDates.max()
                if (oMin > bMax + ADJACENCY_WINDOW_SEC || bMin > oMax + ADJACENCY_WINDOW_SEC) continue
                if (!block.all { a -> other.all { b -> durationCompatible(a.item, b.item) } }) continue
                union(block.first().local, other.first().local)
            }
        }

        // 聚落 ≥2 名成员 → 链；链内按播放顺序排
        nodes.indices.groupBy { find(it) }.values
            .filter { it.size >= 2 }
            .forEach { members ->
                val eps = members.map { nodes[it] }.map { ChainEpisode(it.item, it.mark) }
                out += EpisodeChainInfo(chatId, orderChainEpisodes(eps))
            }
    }
    return out
}

/** 时长兼容：任一未知（0）＝不否决；已知则最长/最短 ≤[DURATION_RATIO_MAX] 倍（>倍数＝不同内容） */
internal fun durationCompatible(a: VideoItem, b: VideoItem): Boolean {
    val x = a.durationSec
    val y = b.durationSec
    if (x <= 0 || y <= 0) return true
    return max(x, y).toFloat() / min(x, y) <= DURATION_RATIO_MAX
}

/** 季号兼容：两边都有季号才要求一致（S01 与 S02 不并；S01 与无季号可并） */
internal fun seasonCompatible(a: EpisodeMark, b: EpisodeMark): Boolean {
    val sa = a.season
    val sb = b.season
    if (sa == null || sb == null) return true
    return sa == sb
}

/**
 * 链内排序（第 1 集在前）：
 *  - 相册块（同 albumId 且 ≥2 名成员）内部按 messageId 升序（TG 相册成员按发送
 *    顺序＝集序）；块排序键取块内最小序键；
 *  - 散帖按序键（集号+拆段）升序（同键按 messageId）；
 *  - 无键成员（相册空 caption 块）按 messageId 插到"最后一个 messageId 更小的
 *    有键成员"之后——按发送位次归位。
 */
internal fun orderChainEpisodes(eps: List<ChainEpisode>): List<ChainEpisode> {
    if (eps.size < 2) return eps
    val blockIds = eps.groupBy { it.item.albumId }
        .filterKeys { it != 0L }
        .filterValues { it.size >= 2 }
        .keys
    val inBlock = eps.filter { it.item.albumId in blockIds }
    val singles = eps.filter { it.item.albumId !in blockIds }

    class Slot(val key: Int?, val messageId: Long, val members: List<ChainEpisode>)

    val slots = mutableListOf<Slot>()
    blockIds.forEach { id ->
        val members = inBlock.filter { it.item.albumId == id }.sortedBy { it.item.messageId }
        if (members.isNotEmpty()) {
            slots += Slot(members.mapNotNull { it.mark?.seqKey() }.minOrNull(), members.first().item.messageId, members)
        }
    }
    singles.forEach { s -> slots += Slot(s.mark?.seqKey(), s.item.messageId, listOf(s)) }
    val keyed = slots.filter { it.key != null }.sortedWith(compareBy({ it.key!! }, { it.messageId }))
    val unkeyed = slots.filter { it.key == null }.sortedBy { it.messageId }
    val result = keyed.flatMap { it.members }.toMutableList()
    for (u in unkeyed) {
        var insertAt = 0
        for (i in result.indices) {
            if (result[i].item.messageId < u.messageId) insertAt = i + 1
        }
        result.addAll(insertAt, u.members)
    }
    return result
}

// ---------------- ④ 连播跳集：当前条目 → 链内下一集 ----------------

/** 连播跳集结果：目标条目＋日志标签（集号，或相册/共享 caption 链的位置序号） */
internal data class EpisodeHop(
    val from: VideoItem,
    val to: VideoItem,
    val fromLabel: String,
    val toLabel: String,
    val chainSize: Int,
)

/**
 * 当前条目（fromKey）在链上的下一集；链尾/不在任何链上返回 null（调用方回落
 * Feed 顺序连播）。
 *
 * 判定口径（比较一律用 [EpisodeMark.seqKey] 序键）：
 *  - 同相册成员位置顺延（整表共享 caption 的同号成员走这里）；
 *  - 候选无编号（相册空 caption 成员）位置顺延；
 *  - 散帖序键严格大于当前有效序键（同键补发跳过；序键更小的乱序忽略）；
 *    当前有效序键：正常取自身序键；相册成员的编号若与同组其他成员同键（共享
 *    caption 抄来的），按链内位置序号×10 计——[1..10] 全集相册跳"第2集"散帖时，
 *    位置 3 的序键 30 与集号 2 的序键 20 比较才不会倒退。
 */
internal fun episodeHop(items: List<VideoItem>, fromKey: String): EpisodeHop? {
    for (chain in episodeChains(items)) {
        val idx = chain.episodes.indexOfFirst { it.item.key == fromKey }
        if (idx < 0) continue
        val cur = chain.episodes[idx]
        val curSeq = effectiveSeq(chain, cur, idx)
        var next: ChainEpisode? = null
        var nextPos = -1
        for (p in idx + 1 until chain.episodes.size) {
            val cand = chain.episodes[p]
            val ok = when {
                cur.item.albumId != 0L && cand.item.albumId == cur.item.albumId -> true
                cand.mark == null -> true
                else -> cand.mark.seqKey() > curSeq
            }
            if (ok) {
                next = cand
                nextPos = p
                break
            }
        }
        if (next == null) return null
        return EpisodeHop(
            from = cur.item,
            to = next.item,
            fromLabel = effectiveLabel(chain, cur, idx),
            toLabel = effectiveLabel(chain, next, nextPos),
            chainSize = chain.episodes.size,
        )
    }
    return null
}

/**
 * 成员的有效序键（比较用）：自身无编号、或编号是同相册共享 caption 抄来的
 * （同组还有别的成员序键与它一致）→ 用链内位置序号×10；否则用自身序键。
 */
private fun effectiveSeq(chain: EpisodeChainInfo, ep: ChainEpisode, pos: Int): Int {
    val m = ep.mark ?: return (pos + 1) * 10
    if (ep.item.albumId != 0L && sharedMarkInAlbum(chain, ep, m)) return (pos + 1) * 10
    return m.seqKey()
}

/** 成员的日志标签：位置式给"5"；编号式给"12"或"3上"（拆段） */
private fun effectiveLabel(chain: EpisodeChainInfo, ep: ChainEpisode, pos: Int): String {
    val m = ep.mark ?: return (pos + 1).toString()
    if (ep.item.albumId != 0L && sharedMarkInAlbum(chain, ep, m)) return (pos + 1).toString()
    return if (m.part != 0) "${m.number}${partSuffix(m.part)}" else m.number.toString()
}

/** 同相册里还有别的成员序键与 ep 一致 → ep 的编号是共享 caption 抄来的（非自身集号） */
private fun sharedMarkInAlbum(chain: EpisodeChainInfo, ep: ChainEpisode, m: EpisodeMark): Boolean =
    chain.episodes.any { other ->
        other !== ep && other.item.albumId == ep.item.albumId && other.mark?.seqKey() == m.seqKey()
    }
