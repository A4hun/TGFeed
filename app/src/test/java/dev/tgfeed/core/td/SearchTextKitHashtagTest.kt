package dev.tgfeed.core.td

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchTextKit] R167 #话题精确词匹配的纯函数单测：
 * [SearchTextKit.isHashtagQuery]（# 前缀 + 单词条分路判定）与
 * [SearchTextKit.hashtagHits]（# 标记 + 词边界命中）。
 *
 * 口径锚：词身字符集 = UI 可点片段提取正则 `#([\p{L}\p{N}_]+)` 同口径
 * （点击上抛的 tag 必是词身全集，边界判定同口径才不漏不误）；双侧 fold
 * （简繁/全角/大小写归一）由调用方完成——测试内显式 fold 模拟调用现场
 * （DeepSearchIndex.search 复滤 / HashtagSearchFeedSource 源内复滤同款）。
 */
class SearchTextKitHashtagTest {

    /** 模拟调用现场：文本与 tag 双侧 fold 后进 hashtagHits */
    private fun hits(rawText: String, rawTag: String): Boolean =
        SearchTextKit.hashtagHits(SearchTextKit.fold(rawText), SearchTextKit.fold(rawTag))

    // ---------------- isHashtagQuery：hashtag/普通查询分路 ----------------

    @Test
    fun `井号开头单词条是真话题查询`() {
        assertTrue(SearchTextKit.isHashtagQuery("#电影"))
        assertTrue(SearchTextKit.isHashtagQuery("#flzvip"))
        assertTrue(SearchTextKit.isHashtagQuery("＃tag")) // 全角标记同认
        assertTrue(SearchTextKit.isHashtagQuery("  #tag  ")) // 首尾空白容忍
        assertTrue(SearchTextKit.isHashtagQuery("##tag")) // 多重前导标记（词身不含标记）
    }

    @Test
    fun `无标记或带空白都是普通查询`() {
        assertFalse(SearchTextKit.isHashtagQuery("电影")) // 裸词
        assertFalse(SearchTextKit.isHashtagQuery("#")) // 空词身
        assertFalse(SearchTextKit.isHashtagQuery("＃"))
        assertFalse(SearchTextKit.isHashtagQuery("#tag 合集")) // 多段 → 普通段落匹配
        assertFalse(SearchTextKit.isHashtagQuery("# tag")) // 标记后空白
        assertFalse(SearchTextKit.isHashtagQuery("")) // 空串
    }

    // ---------------- hashtagHits：# 标记 + 词边界 ----------------

    @Test
    fun `标准命中_标记起词边界收`() {
        assertTrue(hits("看 #电影 合集", "电影")) // 后随空格=边界
        assertTrue(hits("#电影推荐", "电影推荐")) // 串尾=边界（完整标签）
        assertTrue(hits("必看#电影。", "电影")) // 后随中文标点=边界；标记前无空白也认
    }

    @Test
    fun `裸子串不命中_防误伤`() {
        assertFalse(hits("今日电影合集", "电影")) // 无 # 标记
        assertFalse(hits("分享tag给大家", "tag"))
    }

    @Test
    fun `词身延续不命中_那是另一个标签`() {
        assertFalse(hits("看 #电影推荐 合集", "电影")) // 推=词身字符
        assertFalse(hits("#tagging 看看", "tag"))
        assertFalse(hits("#tag_x 合集", "tag")) // 下划线=词身
        assertFalse(hits("…#12345…", "123")) // 数字词身延续
    }

    @Test
    fun `词身全集命中`() {
        assertTrue(hits("#tag_x 合集", "tag_x"))
        assertTrue(hits("#12345 下载", "12345"))
        assertTrue(hits("#flzvip 独家", "flzvip"))
    }

    @Test
    fun `fold归一_简繁全角大小写`() {
        assertTrue(hits("#電影 合集", "电影")) // 繁体文本简体查询（双侧 fold）
        assertTrue(hits("#电影 合集", "電影")) // 简体文本繁体查询
        assertTrue(hits("#TAG 分享", "tag")) // 大小写
        assertTrue(hits("＃电影 合集", "电影")) // 全角标记
    }

    @Test
    fun `多重标记_从第二个标记起命中`() {
        // UI 提取正则在 "##tag" 上匹配第二个 #；hashtagHits 逐位扫描天然同口径
        assertTrue(hits("##tag", "tag"))
    }

    @Test
    fun `标记前置字符不查_宁多勿漏口径`() {
        // TG 实体解析要求 # 前是空白/串首；索引复滤放宽只认后边界——
        // 召回优先（宁多勿漏），"url#tag" 形态前置误命中面实测罕见
        assertTrue(hits("abc#tag 合集", "tag"))
    }

    @Test
    fun `空词或空文本不命中`() {
        assertFalse(hits("#电影", ""))
        assertFalse(hits("", "电影"))
    }
}
