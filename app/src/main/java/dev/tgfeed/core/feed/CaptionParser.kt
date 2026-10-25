package dev.tgfeed.core.feed

import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem

// ---------------- R228 caption 结构化解析（纯函数，单测见 CaptionParserTest.kt） ----------------

/**
 * 相册上下文：成员数与当前成员序号（0 基；按 messageId 升序＝TG 相册发送序，
 * 与 EpisodeChain「相册块内 messageId 升序＝集序」同口径）。
 * null / memberCount<1 / memberIndex 越界 → 调用方按 (1, 0) 单条口径处理。
 */
data class CaptionAlbumContext(
    val memberCount: Int,
    val memberIndex: Int,
)

/**
 * caption 结构化解析结果（**一条成员的视角**——同一相册的每个成员各解析一次，
 * 输入同一份共享 caption + 各自的 [CaptionAlbumContext]）。
 *
 *  - [perItemTitle]：本成员独立标题（所在段去掉 #标签与段首序号标记后的纯文字）。
 *    null = 未逐条命中（回退模式/段是纯标签）——消费方回退 caption 首行（R219 口径）；
 *  - [perItemTags]：本成员条目级 #标签（原形，无 # 前缀；fold 归一去重）；
 *  - [albumTitle]：相册级标题——逐条模式下＝头/尾公共段（未点名/纯标签段）的文字；
 *    回退模式下＝首个非空行（整段共享，R219 现状语义）；
 *  - [albumTags]：相册级标签——逐条模式下＝未命中段的标签；回退模式下＝全文所有标签。
 *
 * 落库映射（DeepSearchIndex v8）：parsed_title = [perItemTitle]（NULL=回退），
 * item_tags = [storedTags]（条目级 ∪ 相册级——相册级标签对每个成员都成立，
 * tag 查询据此精准命中到成员行）。
 */
data class CaptionParseResult(
    val perItemTitle: String?,
    val perItemTags: List<String>,
    val albumTitle: String?,
    val albumTags: List<String>,
) {
    /** 落库 item_tags：条目级 ∪ 相册级（回退模式=全文所有标签）；fold 归一去重保原形 */
    fun storedTags(): List<String> = distinctByFolded(perItemTags + albumTags)

    companion object {
        internal val EMPTY = CaptionParseResult(null, emptyList(), null, emptyList())
    }
}

/**
 * R228 caption 结构化解析器（纯函数、零 Android 依赖、零 IO）。
 *
 * 背景（用户原话需求）：TG 频道发帖习惯多样——相册 6 张图配一段 caption，有的行带
 * #标签、有的纯文字、有的混合，中间空格/空行分割。要求「逐行匹配」：caption 按行拆
 * → 按序匹配成员（第 N 行→成员 N）→ 每条有独立标题+tag；所有 #标签提取为相册级+
 * 条目级标签；解析在爬虫入库时落库（展示不重算——本文件只负责判定，落库在
 * DeepSearchIndex ingest）。
 *
 * 解析策略（任务书口径）：
 *  1. 按行/空行分段 → 段数≈成员数 → 逐段匹配（段 N→成员 N）；
 *  2. 每段提取 #标签（词身口径与 SearchTextKit.isTagBodyChar/UI 可点片段三方同源）+ 剩余纯文字
 *     =该成员标题；
 *  3. 段数≠成员数 → 回退：整段=相册共享标题+相册级 tag（现状行为=R219 兼容，
 *     perItemTitle=null → 消费方回退 caption 首行，存量零破坏）；
 *  4. 「第N张/N./①」段首序号模式识别（强化匹配置信度）：显式点名覆盖 ≥60% 成员
 *     即按**序号值**（非位置）映射，未点名段（页眉/页脚/纯标签行）归相册级。
 *
 * 刻意保守（与 EpisodeChain 同哲学：宁漏勿错）：
 *  - 纯 #标签段不占成员位——「首行标题+资源简介+标签行」三段式（R219 实证频道发帖
 *    形态）即使段数碰巧=成员数也不会误逐行（标签行被剔出计数后段数对不上→回退）；
 *  - 序号模式要求 ≥2 个 distinct 有效序号且覆盖 ≥60% 成员——零星「1.」「2.」散号
 *    （资源清单/步骤文）不触发；
 *  - 单行 caption 的单条目（成员数=1）标题=整行（与 caption 首行等价，行为不变）；
 *    多行单条目→回退（与现状一致）。
 */
internal object CaptionParser {

    /**
     * #标签提取正则：词身 = 字母（含 CJK）/数字/下划线（[\p{L}\p{N}_]），# 与全角 ＃
     * 同认——与 [SearchTextKit.hashtagHits] 的词边界判定、BottomInfoPanel 可点片段
     * 正则（`#([\p{L}\p{N}_]+)`）三方同口径（R167 单一尺子纪律）。
     */
    private val RE_HASHTAG = Regex("""[#＃]([\p{L}\p{N}_]+)""")

    // ---- 段首序号标记（「第N张/N./①」识别——任务书第 4 条） ----
    // 「第N张/第N张图/第N图/第N个/第N幅」（中文数字复用 EpisodeChain.parseZhNumber）
    private val RE_MARK_ZH = Regex("""^第\s*([一二三四五六七八九十百零〇\d]{1,8})\s*(?:张图|张|图|个|幅)""")
    // ①..⑳（U+2460..U+2473，值为码位-0x2460+1）
    private val RE_MARK_CIRCLED = Regex("""^[①-⑳]""")
    // 「N. / N、 / N）」等标点尾随式（1~2 位数字防「2023.05」年份误伤——4 位不匹配）
    private val RE_MARK_NUM_TAIL = Regex("""^\d{1,2}\s*[.．、,:：)）]""")
    // 「(N) / （N）」括注式
    private val RE_MARK_NUM_WRAP = Regex("""^[（(]\s*(\d{1,2})\s*[)）]""")
    // 「[N] / 【N】」方括式
    private val RE_MARK_NUM_BRACKET = Regex("""^[\[【]\s*(\d{1,2})\s*[\]】]""")
    // 「P1 / P1.」（写真图集常用页码式）
    private val RE_MARK_P = Regex("""^[Pp]\s*(\d{1,2})(?=[\s.．、,:：_)\]】]|$)""")

    private val RE_WS = Regex("""\s+""")

    /** 逐段匹配的中间结构：assigned=成员序（1 基）→段原文；leftovers=未命中段（头/尾/纯标签） */
    private data class SegmentMatch(val assigned: Map<Int, String>, val leftovers: List<String>)

    /**
     * 解析一条成员视角的 caption。
     * [ctx] null → 单条口径 (1, 0)（非相册条目/调用方无相册上下文）。
     */
    fun parse(caption: String, ctx: CaptionAlbumContext?): CaptionParseResult {
        val memberCount = ctx?.memberCount ?: 1
        val memberIndex = ctx?.memberIndex ?: 0
        if (caption.isBlank()) return CaptionParseResult.EMPTY
        if (memberCount < 1 || memberIndex !in 0 until memberCount) return CaptionParseResult.EMPTY
        val paraSegs = paragraphsOf(caption)
        val lineSegs = linesOf(caption)
        // 单成员保守闸：多行 caption 的单条目（非相册/1 成员相册）不逐行——「1 段=1 成员」
        // 的段落语义撞上「标题/简介/标签」多行单文案会把全文摊平成标题（换行丢失+标题
        // 膨胀），回退保 R219「原文逐字保留」显示口径（perItemTitle=null→消费方回退
        // caption 首行）。单行单条照常（标题=整行，与 caption 首行等价，行为不变）。
        if (memberCount == 1 && lineSegs.size > 1) return fallbackResult(caption)
        val matched = numberedMatch(paraSegs, memberCount)
            ?: numberedMatch(lineSegs, memberCount)
            ?: exactMatch(paraSegs, memberCount)
            ?: exactMatch(lineSegs, memberCount)
        return matched?.let { perItemResult(it, memberIndex) } ?: fallbackResult(caption)
    }

    // ---------------- 分段 ----------------

    /** 空行分段：连续非空行合为一段（段内行以空格 join）；全空行剔除 */
    private fun paragraphsOf(caption: String): List<String> {
        val out = ArrayList<String>()
        val buf = ArrayList<String>()
        for (raw in caption.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                if (buf.isNotEmpty()) out += buf.joinToString(" ")
                buf.clear()
            } else buf += t
        }
        if (buf.isNotEmpty()) out += buf.joinToString(" ")
        return out
    }

    /** 逐行分段：非空行各成一段（已 trim） */
    private fun linesOf(caption: String): List<String> =
        caption.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    // ---------------- 匹配 ----------------

    /**
     * 序号模式：段首「第N张/N./①」点名映射（按序号值，非位置）。
     * 置信度闸：distinct 有效序号 ≥2 且 ≥ceil(60%×成员数)——防零星散号
     * （资源清单「1. 」「2. 」）误触发；单段不可能通过（distinct≥2 需 ≥2 段）。
     */
    private fun numberedMatch(segs: List<String>, memberCount: Int): SegmentMatch? {
        if (segs.size < 2) return null
        val assigned = HashMap<Int, String>()
        val leftovers = ArrayList<String>()
        for (seg in segs) {
            val m = leadingMarkerOf(seg, memberCount)
            if (m != null && !assigned.containsKey(m)) assigned[m] = seg else leftovers += seg
        }
        val need = (memberCount * 3 + 4) / 5 // ceil(60%)
        if (assigned.size < 2 || assigned.size < need) return null
        return SegmentMatch(assigned, leftovers)
    }

    /**
     * 精确计数模式：有文字的段数恰=成员数 → 位置映射（段 N→成员 N）。
     * 纯 #标签段不占成员位（剔出计数）：既是「标题/简介/#标签」三段式的防误伤闸，
     * 也让「6 行成员行+1 行尾部标签行」天然成立（7 段中 6 文字段=6 成员，标签行归相册级）。
     */
    private fun exactMatch(segs: List<String>, memberCount: Int): SegmentMatch? {
        val textSegs = segs.filter { titleTextOf(it) != null }
        if (textSegs.size != memberCount) return null
        val assigned = HashMap<Int, String>(textSegs.size)
        for (i in textSegs.indices) assigned[i + 1] = textSegs[i]
        return SegmentMatch(assigned, segs.filter { titleTextOf(it) == null })
    }

    /** 段首序号标记 → 成员序（1 基）；无标记/超界返回 null */
    private fun leadingMarkerOf(seg: String, memberCount: Int): Int? {
        val s = seg.trim()
        if (s.isEmpty()) return null
        RE_MARK_ZH.find(s)?.let { m ->
            val v = parseZhNumber(m.groupValues[1])
            if (v != null && v in 1..memberCount) return v
        }
        RE_MARK_NUM_WRAP.find(s)?.let { m ->
            val v = m.groupValues[1].toIntOrNull()
            if (v != null && v in 1..memberCount) return v
        }
        RE_MARK_NUM_BRACKET.find(s)?.let { m ->
            val v = m.groupValues[1].toIntOrNull()
            if (v != null && v in 1..memberCount) return v
        }
        RE_MARK_P.find(s)?.let { m ->
            val v = m.groupValues[1].toIntOrNull()
            if (v != null && v in 1..memberCount) return v
        }
        RE_MARK_NUM_TAIL.find(s)?.let { m ->
            val v = m.value.takeWhile { it.isDigit() }.toIntOrNull()
            if (v != null && v in 1..memberCount) return v
        }
        RE_MARK_CIRCLED.find(s)?.let { m ->
            val v = m.value[0].code - 0x2460 + 1
            if (v in 1..memberCount) return v
        }
        return null
    }

    // ---------------- 段 → 标题/标签 ----------------

    /**
     * 段 → 标题纯文字：去 #标签（补空格防「看#电影合集」粘词）、去段首序号标记
     * （「第1张 」「1. 」是行号不是内容）、空白收敛。全剥空 → null（纯标签段）。
     */
    private fun titleTextOf(seg: String): String? {
        var s = RE_HASHTAG.replace(seg, " ").trim()
        for (re in MARK_STRIP_ORDER) s = re.replaceFirst(s, "")
        s = s.trim().replace(RE_WS, " ")
        return s.takeIf { it.isNotEmpty() }
    }

    /** 标题剥序号标记的顺序（段首锚定的各正则——重复 replaceFirst 幂等无害） */
    private val MARK_STRIP_ORDER = listOf(
        RE_MARK_ZH, RE_MARK_NUM_WRAP, RE_MARK_NUM_BRACKET, RE_MARK_P, RE_MARK_NUM_TAIL, RE_MARK_CIRCLED,
    )

    /** #标签提取（原形、无 # 前缀）；fold 归一去重（「美玥/美玥」只留首个原形） */
    internal fun extractHashtags(text: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (m in RE_HASHTAG.findAll(text)) {
            val raw = m.groupValues[1]
            if (seen.add(SearchTextKit.fold(raw))) out += raw
        }
        return out
    }

    /**
     * R285 链④库内批注源守卫：剥掉 #标签后仍含字母数字正文 = 真·文字批注
     * （纯标签行「#A #B」不当批注源——快照测算 m1 实锤：无此守卫时纯标签 caption
     * 跨帖污染注给几十行）。词身口径与 [extractHashtags] 同一正则（单一尺子）；
     * ping 守卫（无字母数字即重置批注态）在判定核 [computeAdjacentNotes] 内，
     * 与本守卫各守各位。
     */
    internal fun hasNonTagText(text: String): Boolean =
        RE_HASHTAG.replace(text, " ").any { it.isLetterOrDigit() }

    // ---------------- 结果装配 ----------------

    /** 逐条模式：本成员段（assigned[memberIndex+1]）拆标题+标签；未命中段归相册级 */
    private fun perItemResult(m: SegmentMatch, memberIndex: Int): CaptionParseResult {
        val own = m.assigned[memberIndex + 1]
        return CaptionParseResult(
            perItemTitle = own?.let { titleTextOf(it) },
            perItemTags = own?.let { extractHashtags(it) } ?: emptyList(),
            albumTitle = m.leftovers.firstNotNullOfOrNull { titleTextOf(it) },
            albumTags = distinctByFolded(m.leftovers.flatMap { extractHashtags(it) }),
        )
    }

    /** 回退模式（段数≠成员数）：perItemTitle=null（消费方回退 caption 首行=R219 现状） */
    private fun fallbackResult(caption: String): CaptionParseResult =
        CaptionParseResult(
            perItemTitle = null,
            perItemTags = emptyList(),
            albumTitle = caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
            albumTags = extractHashtags(caption),
        )
}

/** fold 归一去重（保首个原形与顺序；相册级/条目级标签合流共用口径） */
private fun distinctByFolded(tags: List<String>): List<String> {
    val out = ArrayList<String>(tags.size)
    val seen = HashSet<String>(tags.size)
    for (t in tags) if (seen.add(SearchTextKit.fold(t))) out += t
    return out
}

// ---------------- R228 消费方共享件（R219 挂账①「caption→标题行派生」的统一出口） ----------------

/**
 * 条目标题行：**parsed_title（逐条解析标题）优先，NULL/空白回退 caption 首个非空行**
 * （任务书 D 层「toVideoItem 优先读 parsed_title（NULL 回退 caption 首行）」的唯一实现）。
 *
 * 既有各标题派生点（分享/下载记录/音频通知等 `caption.lineSequence().firstOrNull()` 家族）
 * 换用本函数即获得 R228 语义；未解析条目（parsedTitle=null——存量未回填/网络直取路径）
 * 行为与旧口径逐字一致（首非空行 trim）。
 */
fun VideoItem.titleLine(): String =
    parsedTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
