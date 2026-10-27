package dev.tgfeed.core.feed

import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem

/**
 * R413 关键词屏蔽判定唯一出处（零 Android 依赖，JVM 单测直测）。
 *
 * 口径：
 *  - 关键词入库前经 [normalize]：去首尾空白、内部连续空白收成一个空格，长度 1~[MAX_LEN]；
 *    越界/空白词拒绝（返回 null）；
 *  - 去重按 SearchTextKit.fold 归一后比较（大小写/全半角/繁简同一把尺），保留首个原形；
 *  - 匹配 = 条目文本（标题/caption/结构化 tag/音频标题与歌手，可选频道名）fold 后子串包含
 *    任一关键词 fold；关键词在 [compile] 时预 fold 一次，逐条只 fold 条目文本；
 *  - 空集合恒不命中（不误杀）。
 */
object KeywordMutePolicy {

    /** 单个屏蔽词最大长度（字符数，normalize 后） */
    const val MAX_LEN = 30

    /** 屏蔽词总数上限（防设置页/匹配成本无界增长） */
    const val MAX_WORDS = 200

    private val WS = Regex("\\s+")

    /** 规范化单个屏蔽词；不合法（空白/超长）返回 null */
    fun normalize(raw: String): String? {
        val w = raw.replace('　', ' ').trim().replace(WS, " ")
        if (w.isEmpty() || w.length > MAX_LEN) return null
        return w
    }

    /** 批量规范化 + 按 fold 去重（保插入序，保留首个原形），截断到 [MAX_WORDS] */
    fun sanitize(words: Iterable<String>): Set<String> {
        val seen = HashSet<String>()
        val out = LinkedHashSet<String>()
        for (raw in words) {
            val w = normalize(raw) ?: continue
            if (seen.add(SearchTextKit.fold(w))) out += w
            if (out.size >= MAX_WORDS) break
        }
        return out
    }

    /** [words] 中是否已有与 [word] fold 等价的词 */
    fun containsEquivalent(words: Iterable<String>, word: String): Boolean {
        val f = SearchTextKit.fold(normalize(word) ?: return false)
        return words.any { SearchTextKit.fold(it) == f }
    }

    /** 预编译匹配器：关键词只 fold 一次 */
    class Matcher internal constructor(
        private val folded: List<String>,
        val matchChannelTitle: Boolean,
    ) {
        val isEmpty: Boolean get() = folded.isEmpty()
        val size: Int get() = folded.size

        /** 文本级判定（各段独立 fold 后匹配，段间不拼接，防跨段误命中） */
        fun hits(
            title: String?,
            caption: String?,
            tags: List<String>?,
            channelTitle: String? = null,
            extra: List<String> = emptyList(),
        ): Boolean {
            if (folded.isEmpty()) return false
            if (hitText(title) || hitText(caption)) return true
            tags?.forEach { if (hitText(it)) return true }
            extra.forEach { if (hitText(it)) return true }
            if (matchChannelTitle && hitText(channelTitle)) return true
            return false
        }

        /** 条目级判定（标题/caption/tag/音频元数据，按开关含频道名） */
        fun hits(item: VideoItem): Boolean {
            if (folded.isEmpty()) return false
            return hits(
                title = item.parsedTitle,
                caption = item.caption,
                tags = item.itemTags,
                channelTitle = item.channelTitle,
                extra = if (item.mediaType == 2) listOf(item.audioTitle, item.audioPerformer) else emptyList(),
            )
        }

        private fun hitText(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            val f = SearchTextKit.fold(s)
            for (k in folded) if (f.contains(k)) return true
            return false
        }
    }

    /** 编译匹配器；非法词静默丢弃 */
    fun compile(words: Collection<String>, matchChannelTitle: Boolean): Matcher {
        if (words.isEmpty()) return EMPTY
        val folded = sanitize(words).map { SearchTextKit.fold(it) }.distinct()
        return Matcher(folded, matchChannelTitle)
    }

    /** 空匹配器（恒不命中） */
    val EMPTY: Matcher = Matcher(emptyList(), false)
}
