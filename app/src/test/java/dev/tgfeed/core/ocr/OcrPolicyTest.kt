package dev.tgfeed.core.ocr

import dev.tgfeed.core.feed.isBackfillStateFileName
import dev.tgfeed.core.td.SearchTextKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R292 封面 OCR 试点（T1a）纯函数回归锚：
 *  1. [OcrPolicy] 低信息判定与并入后缀（防 BM25 信号稀释红线：高信息 caption 恒不并）；
 *  2. [CoverOcrBackfillState]/[CoverOcrApplyState] 断点编解码（容缺/坏串不崩，v8r2 同口径）；
 *  3. 试点报告编码（全聚合零原文的结构保证）与长度分桶；
 *  4. 门房白名单覆盖 cover_ocr_ 族（R262b 西西弗斯洞防线）。
 * 全部 JVM 直测，零 Android 依赖。
 */
class OcrPolicyTest {

    // ---------------- 低信息 caption 判定 ----------------

    @Test
    fun `空与短 caption 判低信息`() {
        assertTrue(OcrPolicy.isLowInfoCaption(""))
        assertTrue(OcrPolicy.isLowInfoCaption("   "))
        assertTrue(OcrPolicy.isLowInfoCaption("更新"))
        assertTrue(OcrPolicy.isLowInfoCaption("每日更新中")) // 5 字
        assertTrue(OcrPolicy.isLowInfoCaption("123456789")) // 9 字符
    }

    @Test
    fun `达到上限的 caption 不判低信息`() {
        assertFalse(OcrPolicy.isLowInfoCaption("1234567890")) // 10 字符=达上限
        assertFalse(OcrPolicy.isLowInfoCaption("这是一段有实质内容的视频说明文字"))
    }

    @Test
    fun `低信息判定走 fold 归一口径`() {
        // 全角字符 fold 后归一，长度按归一后计（与索引词位同一把尺）
        assertTrue(OcrPolicy.isLowInfoCaption("ＡＢＣ"))
    }

    // ---------------- 并入后缀（防稀释红线） ----------------

    @Test
    fun `低信息加非空OCR给后缀`() {
        val suffix = OcrPolicy.ocrIndexSuffix("更新", "海报标题文字")
        assertEquals(" 海报标题文字", suffix)
    }

    @Test
    fun `高信息 caption 恒不并OCR`() {
        // 防 BM25 信号稀释：有实质 caption 的条目词位零变化（r291 锚②）
        assertEquals("", OcrPolicy.ocrIndexSuffix("这是一段有实质内容的视频说明文字", "海报标题文字"))
    }

    @Test
    fun `空或空白OCR恒不并`() {
        assertEquals("", OcrPolicy.ocrIndexSuffix("更新", null))
        assertEquals("", OcrPolicy.ocrIndexSuffix("更新", ""))
        assertEquals("", OcrPolicy.ocrIndexSuffix("更新", "   "))
    }

    @Test
    fun `并入判定是纯函数-同输入恒同输出`() {
        // contentless fts 删除要求建/删两侧逐字节一致——同 (caption,ocr) 必须恒同结论
        val a = OcrPolicy.ocrIndexSuffix("更新", "某海报字")
        val b = OcrPolicy.ocrIndexSuffix("更新", "某海报字")
        assertEquals(a, b)
    }

    @Test
    fun `fold后并入文本与建表同尺`() {
        // 配方= fold(caption+" "+channelTitle+suffix).take(600)——本测锁定 suffix 形状
        // 进 fold 后是合法可词位化文本（CJK 走 bigram，拉丁整词）
        val caption = "更新"
        val merged = SearchTextKit.fold(caption + " 某频道" + OcrPolicy.ocrIndexSuffix(caption, "海报 abc"))
        val tokens = SearchTextKit.tokenize(merged, forIndex = true)
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.contains("abc")) // 拉丁整词进词位
    }

    // ---------------- 断点编解码 ----------------

    @Test
    fun `回填断点编解码往返一致`() {
        val s = CoverOcrBackfillState(
            cursor = 12345L, processed = 678, nonempty = 200, empty = 400, failed = 78,
            done = false, buckets = "0,50,120,25,5",
        )
        val decoded = decodeCoverOcrBackfillState(encodeCoverOcrBackfillState(s))
        assertEquals(s, decoded)
    }

    @Test
    fun `分桶 CSV 与 IntArray 互转容缺`() {
        assertTrue(parseOcrBuckets("1,2,3,4,5").contentEquals(intArrayOf(1, 2, 3, 4, 5)))
        assertTrue(parseOcrBuckets("7,8").contentEquals(intArrayOf(7, 8, 0, 0, 0))) // 短款补零
        assertTrue(parseOcrBuckets("坏串").contentEquals(IntArray(5))) // 坏串全零不崩
        assertEquals("1,2,3,4,5", formatOcrBuckets(intArrayOf(1, 2, 3, 4, 5)))
        assertEquals("9,0,0,0,0", formatOcrBuckets(intArrayOf(9))) // 短款补齐
    }

    @Test
    fun `回填断点坏串容缺不崩`() {
        val d = decodeCoverOcrBackfillState("{残串")
        assertEquals(0L, d.cursor)
        assertEquals(0, d.processed)
        assertFalse(d.done)
        assertTrue(decodeCoverOcrBackfillState("{\"cursor\":9,\"done\":true}").done)
    }

    @Test
    fun `apply断点编解码往返一致`() {
        val s = CoverOcrApplyState(cursor = 777L, applied = 100, skipped = 55, done = true)
        assertEquals(s, decodeCoverOcrApplyState(encodeCoverOcrApplyState(s)))
    }

    // ---------------- 试点报告（全聚合零原文结构保证） ----------------

    @Test
    fun `试点报告只含聚合计数`() {
        val json = encodeCoverOcrPilotReport(
            engineVer = 1, processed = 10_000, nonempty = 3_200, empty = 6_100, failed = 700,
            lenBuckets = intArrayOf(0, 500, 2000, 600, 100),
            startedAtMs = 1_000L, finishedAtMs = 2_000L, fullBackfill = false,
        )
        assertTrue(json.contains("\"processed\":10000"))
        assertTrue(json.contains("\"hitRatePctX10\":320")) // 3200/10000 = 32.0%
        assertTrue(json.contains("\"lenBuckets\":[0,500,2000,600,100]"))
        assertFalse(json.contains("text")) // 结构上无文本字段位
    }

    @Test
    fun `试点报告零处理不除零`() {
        val json = encodeCoverOcrPilotReport(1, 0, 0, 0, 0, IntArray(5), 0L, 0L, false)
        assertTrue(json.contains("\"hitRatePctX10\":0"))
    }

    @Test
    fun `长度分桶边界`() {
        assertEquals(0, ocrLenBucket(0))
        assertEquals(1, ocrLenBucket(9))
        assertEquals(2, ocrLenBucket(10))
        assertEquals(2, ocrLenBucket(49))
        assertEquals(3, ocrLenBucket(50))
        assertEquals(3, ocrLenBucket(199))
        assertEquals(4, ocrLenBucket(200))
    }

    // ---------------- 门房白名单（R262b 西西弗斯洞防线） ----------------

    @Test
    fun `cover_ocr 族断点与报告全在白名单内`() {
        assertTrue(isBackfillStateFileName("cover_ocr_backfill_v1.json"))
        assertTrue(isBackfillStateFileName("cover_ocr_backfill_v1.json.tmp"))
        assertTrue(isBackfillStateFileName("cover_ocr_apply_v1.json"))
        assertTrue(isBackfillStateFileName("cover_ocr_apply_v1.json.tmp"))
        assertTrue(isBackfillStateFileName("cover_ocr_pilot_report.json"))
        assertTrue(isBackfillStateFileName("cover_ocr_backfill_v2.json")) // 未来换代
    }
}
