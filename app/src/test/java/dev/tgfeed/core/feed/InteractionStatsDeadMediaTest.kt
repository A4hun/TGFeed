package dev.tgfeed.core.feed

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [InteractionStats] R163 B5 死媒体聚合口（[InteractionStats.deadChannelStats] /
 * [InteractionStats.recordDeadMedia]）的行为单测：
 * per-chatId 死链/曝光聚合、dislike/skip 不掺入（sub 过滤）、7 天窗口边界
 * （用 stateFile JSON 注入带历史时间戳的事件——record* 只会打当下时间戳，窗口
 * 边界只能从落盘侧构造）、无死链返回空表。
 *
 * 事件 JSON 字段口径（与 persistAsync 同款）：k=Kind 序数（IMPRESSION=0/WATCH=1/
 * INTERACTION=2/NEGATIVE=3/SEEK=4）、s=sub、c=chatId、t=ts 毫秒。
 */
class InteractionStatsDeadMediaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var stateFile: File

    @Before
    fun setUp() {
        stateFile = tempFolder.newFile("interaction_stats.json")
    }

    /** 写一份带指定时间戳的事件流水（绕过 record* 的当下时间戳，构造窗口边界） */
    private fun writeEvents(vararg raw: String) {
        val arr = JSONArray()
        for (r in raw) {
            val parts = r.split(",")
            arr.put(
                JSONObject()
                    .put("k", parts[0].toInt())   // Kind 序数
                    .put("s", parts[1])            // sub
                    .put("c", parts[2].toLong())   // chatId
                    .put("w", 0).put("d", 0)
                    .put("t", parts[3].toLong()),  // ts 毫秒
            )
        }
        JSONObject().put("events", arr).let { stateFile.writeText(it.toString()) }
    }

    private val day = 24L * 3600 * 1000
    private fun now() = System.currentTimeMillis()

    @Test
    fun `recordDeadMedia加曝光聚合出per频道死链率`() {
        // 行为：记账口（NEGATIVE + sub=deadmedia）与曝光各自按 chatId 聚合，
        // rate=死链/曝光；两频道互不串
        val stats = InteractionStats(stateFile)
        repeat(4) { stats.recordImpression(100L) }
        repeat(2) { stats.recordDeadMedia(100L) } // 频道100：2/4 = 0.5
        repeat(10) { stats.recordImpression(200L) }
        repeat(1) { stats.recordDeadMedia(200L) } // 频道200：1/10 = 0.1
        val dead = stats.deadChannelStats()
        assertEquals(2, dead.size)
        assertEquals(2, dead.getValue(100L).deadEvents)
        assertEquals(4, dead.getValue(100L).impressions)
        assertEquals(0.5, dead.getValue(100L).rate, 1e-9)
        assertEquals(1, dead.getValue(200L).deadEvents)
        assertEquals(0.1, dead.getValue(200L).rate, 1e-9)
    }

    @Test
    fun `dislike与skip不掺入死链统计`() {
        // 行为：只有 sub=deadmedia 的 NEGATIVE 计入——dislike/skip 走同 Kind 但
        // 死链率与负反馈率是两个口径，互不污染
        val stats = InteractionStats(stateFile)
        repeat(5) { stats.recordImpression(300L) }
        stats.recordNegative("dislike", 300L)
        stats.recordNegative("skip", 300L)
        assertTrue(stats.deadChannelStats().isEmpty()) // 无 deadmedia → 空表
        stats.recordDeadMedia(300L)
        assertEquals(1, stats.deadChannelStats().getValue(300L).deadEvents)
    }

    @Test
    fun `窗口边界_七天前的死链不计七天半前的曝光仍计`() {
        // 行为：统计窗口 7 天（落盘留存 8 天）——7.5 天前的 deadmedia 在盘内
        //（加载不被剪）但不进死链统计；6 天前的曝光（分母）与死链（分子）照计
        val n = now()
        writeEvents(
            "3,deadmedia,400,${n - 5 * day}",          // 窗内死链（频道400）
            "3,deadmedia,400,${n - 5 * day}",
            "3,deadmedia,400,${n - 15 * day / 2}",     // 7.5 天前死链：盘内、窗外
            "0,,400,${n - 6 * day}",                   // 窗内曝光
            "0,,400,${n - 6 * day}",
            "0,,400,${n - 6 * day}",
            "3,deadmedia,500,${n - 8 * day - 1000}",   // 8 天+1s 前：加载时直接剪除
        )
        val dead = InteractionStats(stateFile).deadChannelStats()
        assertEquals(1, dead.size) // 频道500 被加载剪枝，频道400 窗内
        val s = dead.getValue(400L)
        assertEquals(2, s.deadEvents) // 7.5 天前那笔不计
        assertEquals(3, s.impressions)
        assertEquals(2.0 / 3, s.rate, 1e-9)
    }

    @Test
    fun `无死链事件返回空表_零开销直通`() {
        // 行为：只有曝光/观看没有 deadmedia → 空表（消费方 demotionFactors 空表直通）
        val n = now()
        writeEvents("0,,600,${n - day}", "1,,600,${n - day}")
        assertTrue(InteractionStats(stateFile).deadChannelStats().isEmpty())
    }

    @Test
    fun `死链无曝光分母为零_rate防御为零`() {
        // 行为：死链在窗内但曝光数为 0（异常现场）——rate=0.0 防除零，不抛
        val n = now()
        writeEvents("3,deadmedia,700,${n - day}")
        val s = InteractionStats(stateFile).deadChannelStats().getValue(700L)
        assertEquals(0, s.impressions)
        assertEquals(0.0, s.rate, 1e-9)
    }

    @Test
    fun `记账与落盘加载合并_文件老事件加内存新记账`() {
        // 行为：懒加载先读盘，record* 后续记账 merge 进同一环形缓冲——
        // 重启后（文件恢复）+ 本会话新死链共同构成当前死链率
        val n = now()
        writeEvents("3,deadmedia,800,${n - 6 * day}", "0,,800,${n - 6 * day}")
        val stats = InteractionStats(stateFile)
        stats.recordDeadMedia(800L) // 先触发懒加载再记账（顺序：record 内 ensureLoaded）
        repeat(4) { stats.recordImpression(800L) }
        val s = stats.deadChannelStats().getValue(800L)
        assertEquals(2, s.deadEvents)
        assertEquals(5, s.impressions)
    }

    // ---------------- R163 B4：channelInteractionCounts（需求信号聚合口） ----------------

    @Test
    fun `per频道正负互动计数_like与dislike进deadmedia剔除`() {
        // 行为：positive=INTERACTION（like/favorite/download 计数）；negative=NEGATIVE
        // 剔除 deadmedia（死链已进丰度分 R 负分量，需求侧不重复计负）——dislike/skip 计负
        val stats = InteractionStats(stateFile)
        stats.recordInteraction("like", 100L)
        stats.recordInteraction("favorite", 100L)
        stats.recordInteraction("download", 100L)
        stats.recordNegative("dislike", 100L)
        stats.recordNegative("skip", 100L)
        stats.recordNegative("skip", 100L)
        stats.recordDeadMedia(100L) // 不进需求侧负计数
        stats.recordInteraction("like", 200L) // 别的频道不串
        val counts = stats.channelInteractionCounts()
        assertEquals(2, counts.size)
        val c = counts.getValue(100L)
        assertEquals(3, c.positive)
        assertEquals(3, c.negative) // dislike 1 + skip 2
        assertEquals(1, counts.getValue(200L).positive)
    }

    @Test
    fun `无互动事件返回空表_零开销直通`() {
        val stats = InteractionStats(stateFile)
        stats.recordImpression(300L) // 只有曝光——不是互动
        assertTrue(stats.channelInteractionCounts().isEmpty())
    }

    @Test
    fun `窗口边界_七天前的互动不计`() {
        // 行为：7.5 天前的 like（盘内、窗外）不计；窗内 dislike 计——
        // 与 deadChannelStats 同一 7 天窗口径
        val n = now()
        writeEvents(
            "2,like,900,${n - 15 * day / 2}", // 7.5 天前：窗外
            "3,dislike,900,${n - day}",       // 窗内
        )
        val c = InteractionStats(stateFile).channelInteractionCounts().getValue(900L)
        assertEquals(0, c.positive)
        assertEquals(1, c.negative)
    }
}
