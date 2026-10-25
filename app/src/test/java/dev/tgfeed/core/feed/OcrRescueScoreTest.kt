package dev.tgfeed.core.feed

import dev.tgfeed.core.td.SearchTextKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R304 OCR 收录救援尺纯函数单测（V9-③ 实锤缺陷「OCR-only 条目收录层 rel=0 全灭」的修复锚）：
 *  1. [textMatchScore] 算分核逐臂口径（抽取后与旧 searchRelevance 内联实现逐分一致）；
 *  2. [needsOcrRescue] 救援资格（rel≤0 ∧ 低信息 caption——防稀释红线：高信息恒不救）；
 *  3. [ocrRescueScore] 救援尺（OCR 并入 caption 臂过同一把核——正当分值非豁免；
 *     OCR 不含词位时维持 0=维持丢弃，即「关闸/无 OCR」回归零变化的结构性保证）。
 * 测试词全部人造（无真实频道/OCR 数据，脱敏令合规）。
 */
class OcrRescueScoreTest {

    private fun aq(q: String) = SearchTextKit.analyze(q)
    private fun fold(s: String) = SearchTextKit.fold(s)

    // ---------------- textMatchScore 算分核（抽取等价性锚） ----------------

    @Test
    fun `精确模式 caption AND 基准分与整串加成`() {
        // 段级 AND 全中 +1.0；整串连续出现再 +2.0
        assertEquals(
            1.0,
            textMatchScore(fold("今日电影合集精选"), "", "", aq("电影 合集"), fuzzy = false),
            1e-9,
        )
        assertEquals(
            3.0,
            textMatchScore(fold("今日电影合集精选"), "", "", aq("电影合集"), fuzzy = false),
            1e-9,
        )
    }

    @Test
    fun `精确模式缺段全灭`() {
        assertEquals(
            0.0,
            textMatchScore(fold("只有电影没有别的"), "", "", aq("电影 小说"), fuzzy = false),
            1e-9,
        )
    }

    @Test
    fun `精确模式频道名弱先验与音频臂`() {
        assertEquals(
            0.6,
            textMatchScore("", "", fold("某某电影频道"), aq("电影"), fuzzy = false),
            1e-9,
        )
        assertEquals(
            1.0,
            textMatchScore("", fold("song title 某曲"), "", aq("某曲"), fuzzy = false),
            1e-9,
        )
        // 音频臂空串=无音频臂（mediaType≠2 调用方传空）
        assertEquals(0.0, textMatchScore("", "", "", aq("某曲"), fuzzy = false), 1e-9)
    }

    @Test
    fun `模糊模式词位覆盖与短语加成`() {
        // 单词位全中：整串短语 +2.0 叠加词位全中 +1.6 = 3.6
        assertEquals(
            3.6,
            textMatchScore(fold("看电影"), "", "", aq("电影"), fuzzy = true),
            1e-9,
        )
        // 两词位中一：1.2×0.5=0.6（>0 即收录）
        assertEquals(
            0.6,
            textMatchScore(fold("看电影"), "", "", aq("电影 小说"), fuzzy = true),
            1e-9,
        )
        // 完整短语命中 +2.0 叠加词位
        assertEquals(
            3.6,
            textMatchScore(fold("今日电影合集"), "", "", aq("电影合集"), fuzzy = true),
            1e-9,
        )
        // 全不命中=0
        assertEquals(
            0.0,
            textMatchScore(fold("完全无关"), "", "", aq("电影"), fuzzy = true),
            1e-9,
        )
    }

    // ---------------- needsOcrRescue 资格谓词（防稀释红线锚） ----------------

    @Test
    fun `救援资格=rel全灭且低信息caption`() {
        assertTrue(needsOcrRescue(0.0, "")) // 空 caption=最缺信号
        assertTrue(needsOcrRescue(0.0, "更新")) // 短 caption
        assertTrue(needsOcrRescue(-1.0, "每日更新"))
        assertFalse(needsOcrRescue(0.5, "")) // rel>0 已收录，不双计
        // 防稀释红线：高信息 caption 即使 rel=0 也永不进救援
        //（其 OCR 词从未并入索引——与 apply Job 判定同构）
        assertFalse(needsOcrRescue(0.0, "这是一段有实质内容的视频说明文字"))
        assertFalse(needsOcrRescue(0.0, "1234567890")) // 10 字符达上限
    }

    // ---------------- ocrRescueScore 救援尺（V9-③ 核心修复锚） ----------------

    @Test
    fun `OCR-only条目精确模式救援收录`() {
        val capFold = fold("更新") // 低信息 caption，原尺全灭
        val query = aq("zq8w2xkp") // 人造拉丁词，只存在于 OCR 文本
        // 原尺实锤全灭（V9-③ 死法复现）
        assertEquals(0.0, textMatchScore(capFold, "", "", query, fuzzy = false), 1e-9)
        assertTrue(needsOcrRescue(0.0, "更新"))
        // 救援：OCR 文本含该词 → AND 基准 1.0+整串 2.0=3.0（与 caption 命中同尺同分，
        // 正当分值非豁免）
        assertEquals(
            3.0,
            ocrRescueScore(capFold, "海报编号 zq8w2xkp 留念", "", "", query, fuzzy = false),
            1e-9,
        )
    }

    @Test
    fun `OCR整串连续出现给最强信号分`() {
        val capFold = fold("更新")
        val query = aq("zq8w2xkp 纪念")
        // 两段都在 OCR 里但不连续 → 只有 AND 基准 1.0
        assertEquals(
            1.0,
            ocrRescueScore(capFold, "zq8w2xkp 字样与纪念章分列", "", "", query, fuzzy = false),
            1e-9,
        )
        // 整串连续出现 → 1.0+2.0=3.0（与 caption 整串命中同档）
        assertEquals(
            3.0,
            ocrRescueScore(capFold, "封面大字 zq8w2xkp 纪念 版", "", "", query, fuzzy = false),
            1e-9,
        )
    }

    @Test
    fun `精确模式OCR缺段仍全灭维持丢弃`() {
        val capFold = fold("更新")
        val query = aq("zq8w2xkp 纪念")
        // OCR 只含一段 → AND 缺段=0，维持丢弃（救援尺不放宽口径）
        assertEquals(
            0.0,
            ocrRescueScore(capFold, "只有 zq8w2xkp 没有别的", "", "", query, fuzzy = false),
            1e-9,
        )
    }

    @Test
    fun `模糊模式OCR部分覆盖给比例分`() {
        val capFold = fold("更新")
        val query = aq("电影 小说")
        // 两词位 OCR 只中一：1.2×0.5=0.6（>0 收录，与 caption 部分覆盖同尺）
        assertEquals(
            0.6,
            ocrRescueScore(capFold, "海报写着电影海报四字", "", "", query, fuzzy = true),
            1e-9,
        )
        // 全中 1.6
        assertEquals(
            1.6,
            ocrRescueScore(capFold, "海报写着电影与小说", "", "", query, fuzzy = true),
            1e-9,
        )
    }

    @Test
    fun `空OCR与空查询恒零`() {
        val query = aq("zq8w2xkp")
        assertEquals(0.0, ocrRescueScore(fold("更新"), "", "", "", query, fuzzy = false), 1e-9)
        assertEquals(0.0, ocrRescueScore(fold("更新"), "   ", "", "", query, fuzzy = true), 1e-9)
        // 空查询（phrase 空）恒零——关闸/无词位场景零变化
        assertEquals(
            0.0,
            ocrRescueScore(fold("更新"), "zq8w2xkp", "", "", aq("  "), fuzzy = false),
            1e-9,
        )
    }

    @Test
    fun `OCR不含查询词位维持丢弃`() {
        // 关闸/试点期回归的结构性保证：OCR 文本与查询无关 → 救援分=0 → 维持 rel≤0 丢弃
        val capFold = fold("更新")
        val query = aq("zq8w2xkp")
        assertEquals(
            0.0,
            ocrRescueScore(capFold, "完全无关的图里文字", "", "", query, fuzzy = false),
            1e-9,
        )
        assertEquals(
            0.0,
            ocrRescueScore(capFold, "完全无关的图里文字", "", "", query, fuzzy = true),
            1e-9,
        )
    }

    @Test
    fun `救援不偷频道名与音频分`() {
        // rel=0 已证频道名/音频臂贡献为 0；救援重过同核该两臂恒 0（不借 OCR 抬非 caption 臂）
        val capFold = fold("更新")
        val query = aq("zq8w2xkp")
        assertEquals(
            3.0, // 单词精确命中=1.0+2.0，频道/音频零加成
            ocrRescueScore(
                capFold, "zq8w2xkp", fold("无关曲名"), fold("无关频道"), query,
                fuzzy = false,
            ),
            1e-9,
        )
    }

    @Test
    fun `简繁折叠对OCR文本同尺生效`() {
        // fold 同源：繁体查询命中简体 OCR（与 caption 臂归一口径一致）
        val capFold = fold("更新")
        val query = aq("電影") // fold 后=电影
        assertEquals(
            3.0, // 单词精确命中=1.0+2.0
            ocrRescueScore(capFold, "海报写着电影", "", "", query, fuzzy = false),
            1e-9,
        )
    }
}
