package dev.tgfeed.core.onthisday

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * R203 那年今日策展引擎单测：日期窗（±1 天/闰年回落）/策展集成（窗防御、广告剔除、
 * 同片保首发、频道轮转上限、时间倒序终序——管线收编 TimeWindowPolicy.refineCrossSection
 * 后经本件包装层的端到端锚定）/推送择年（最低存货/广度优先/平手取久远）/文案（用户向+
 * 脱敏锚定）。
 *
 * 数据纪律（AGENT-DISCIPLINE §七）：频道名一律用「频道A/频道B」代称，广告样例
 * 用过滤词表里的通用话术，不引真实频道/标题。
 */
class OnThisDayPolicyTest {

    private val zone = ZoneOffset.UTC

    /** 可带全策展字段的条目构造（key/chatId/dateEpochSec/caption/fileUniqueId/messageId） */
    private fun item(
        key: String,
        chatId: Long,
        dateEpochSec: Int,
        caption: String = "内容$key",
        fileUniqueId: String = "",
        messageId: Long = 0L,
    ): VideoItem = VideoItem(
        key = key,
        chatId = chatId,
        messageId = messageId,
        channelTitle = "频道$chatId",
        caption = caption,
        durationSec = 0,
        width = 0,
        height = 0,
        fileId = -1,
        sizeBytes = 0,
        playUri = null,
        dateEpochSec = dateEpochSec,
        fileUniqueId = fileUniqueId,
    )

    // ---------------- 日期窗 ----------------

    @Test
    fun `日期窗等于目标日加减一天共三天`() {
        // 2026-10-17 00:00 UTC 起：窗=[10-16 00:00, 10-19 00:00) —— 覆盖 16/17/18 三天
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        val day = 86_400L
        val expectStart = LocalDate.of(2026, 10, 16).atStartOfDay(zone).toEpochSecond()
        assertEquals(expectStart, w.fromSec)
        assertEquals(expectStart + 3 * day, w.toSecExcl)
        assertTrue(w.contains(expectStart))            // 左闭
        assertFalse(w.contains(expectStart + 3 * day)) // 右开
    }

    @Test
    fun `闰日周年回落到平年二月末`() {
        // 2024-02-29 的 1 年前周年：java.time 语义回落 2023-02-28（诚实口径，不炸）
        assertEquals(LocalDate.of(2023, 2, 28), anniversaryDate(LocalDate.of(2024, 2, 29), 1))
    }

    @Test
    fun `周年目标日等于今日减年数`() {
        assertEquals(LocalDate.of(2023, 10, 17), anniversaryDate(LocalDate.of(2026, 10, 17), 3))
    }

    // ---------------- 策展管线 ----------------

    @Test
    fun `无时间戳与窗外条目被防御剔除`() {
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        val c = curateOnThisDay(
            listOf(
                item("in1", 1L, dayStart.toInt()),                    // 窗内
                item("out1", 1L, (dayStart - 86_400 * 5).toInt()),    // 窗外（5 天前）
                item("zero", 1L, 0),                                  // 老数据缺省 0
            ),
            w,
        )
        assertEquals(listOf("in1"), c.items.map { it.key })
        // 统计行（TimeWindowPolicy.statsOf）：total=精炼后条数；窗外/无时间戳不计
        assertEquals(1, c.stats.total)
        assertEquals(1, c.stats.channels)
    }

    @Test
    fun `广告话术条目被剔除`() {
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        val c = curateOnThisDay(
            listOf(
                item("ad", 1L, dayStart.toInt(), caption = "商务合作私聊客服"),
                item("ok", 2L, dayStart.toInt()),
            ),
            w,
        )
        assertEquals(listOf("ok"), c.items.map { it.key })
    }

    @Test
    fun `同片跨频道去重保首发`() {
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        val c = curateOnThisDay(
            listOf(
                // 同 fileUniqueId：频道2 早发（首发），频道1 晚发（转发）——保频道2 的那条
                item("late", 1L, (dayStart + 100).toInt(), fileUniqueId = "FP-X"),
                item("first", 2L, dayStart.toInt(), fileUniqueId = "FP-X"),
                item("solo", 3L, dayStart.toInt(), fileUniqueId = ""), // 空指纹不参与去重
            ),
            w,
        )
        assertEquals(setOf("first", "solo"), c.items.map { it.key }.toSet())
    }

    @Test
    fun `单频道最多两条_怪兽频道不垄断横截面`() {
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        val monster = (0 until 50).map { item("m$it", 1L, (dayStart + it).toInt()) }
        val other = (0 until 3).map { item("o$it", 2L, (dayStart + it).toInt()) }
        val c = curateOnThisDay(monster + other, w, limit = 20)
        // 频道1 只有 2 条（上限闸），频道2 条数不受影响——横截面广度保住
        assertEquals(2, c.items.count { it.chatId == 1L })
        assertTrue(c.items.any { it.chatId == 2L })
        assertEquals(2, c.stats.channels)
    }

    @Test
    fun `频道轮转_每频道取最新再取次新`() {
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        // 频道1 三条（时间旧→新 a1<a2<a3），频道2 两条（b1<b2）
        val c = curateOnThisDay(
            listOf(
                item("a1", 1L, dayStart.toInt()),
                item("a2", 1L, (dayStart + 10).toInt()),
                item("a3", 1L, (dayStart + 20).toInt()),
                item("b1", 2L, (dayStart + 15).toInt()),
                item("b2", 2L, (dayStart + 25).toInt()),
            ),
            w,
        )
        // 每频道前 2 新（a3/a2、b2/b1），终序时间倒序：b2 > a3 > b1 > a2
        assertEquals(listOf("b2", "a3", "b1", "a2"), c.items.map { it.key })
    }

    @Test
    fun `limit 截断精选数`() {
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val w = onThisDayWindow(LocalDate.of(2026, 10, 17), zone)
        // 6 个频道各 1 条 → 轮转 1 轮全取；limit=4 截前 4（时间倒序）
        val items = (0 until 6).map { item("c$it", (100 + it).toLong(), (dayStart + it).toInt()) }
        val c = curateOnThisDay(items, w, limit = 4)
        assertEquals(4, c.items.size)
        assertEquals(4, c.stats.total) // 统计 total=精炼后条数（limit 内），与列表一致
    }

    // ---------------- 推送择年 ----------------

    @Test
    fun `存货不足最低条的年轮不推`() {
        assertNull(
            pickYearForPush(
                listOf(OnThisDayYearStat(yearsAgo = 1, itemCount = 2, channelCount = 1)),
            ),
        )
        assertNull(pickYearForPush(emptyList()))
    }

    @Test
    fun `频道广度优先_平手取更久远年轮`() {
        // 广度优先：频道数多者胜——即便它年轮更近、条数更少（横截面广度=记忆浓度）
        val wide = OnThisDayYearStat(yearsAgo = 1, itemCount = 30, channelCount = 5)
        val narrow = OnThisDayYearStat(yearsAgo = 5, itemCount = 60, channelCount = 3)
        assertEquals(wide, pickYearForPush(listOf(wide, narrow)))
        // 平手（广度同为 7）：取更久远年轮（回忆杀随距离加深）
        val y3 = OnThisDayYearStat(yearsAgo = 3, itemCount = 12, channelCount = 7)
        val y5 = OnThisDayYearStat(yearsAgo = 5, itemCount = 10, channelCount = 7)
        assertEquals(y5, pickYearForPush(listOf(y3, y5)))
    }

    // ---------------- 文案（脱敏锚定） ----------------

    @Test
    fun `周年头部文案用户向`() {
        assertEquals("3 年前的今天，5 个频道在传这些", anniversaryHeadline(3, 5))
    }

    @Test
    fun `任意日期头部文案`() {
        assertEquals(
            "2023 年 10 月 17 日，2 个频道在传这些",
            dateHeadline(LocalDate.of(2023, 10, 17), 2),
        )
    }

    @Test
    fun `推送文案只有年数条数_零频道名零标题`() {
        val text = pushContentText(3, 12)
        assertEquals("3 年前的今天，你的频道传过 12 条内容，来看看", text)
        // 脱敏红线锚定：文案不含任何频道名/标题字样（样例喂给候选也不外泄）
        val secretChannel = "机密频道名"
        val secretTitle = "机密内容标题"
        val dayStart = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toEpochSecond()
        val c = curateOnThisDay(
            listOf(
                item("s", 1L, dayStart.toInt(), caption = secretTitle).let {
                    it.copy(channelTitle = secretChannel)
                },
            ),
            onThisDayWindow(LocalDate.of(2026, 10, 17), zone),
        )
        val pushText = pushContentText(2, c.stats.total)
        assertFalse(pushText.contains(secretChannel))
        assertFalse(pushText.contains(secretTitle))
    }

    // ---------------- R215 ② feed 那年今日卡（个人观看历史记忆） ----------------

    /** 观看时刻毫秒：目标日的某时刻（UTC）换算 */
    private fun atMs(date: LocalDate, hour: Int = 12): Long =
        (date.atStartOfDay(zone).toEpochSecond() + hour * 3600L) * 1000L

    @Test
    fun `历史记忆按观看时刻命中年轮窗`() {
        val today = LocalDate.of(2026, 10, 17)
        // 3 年前的今天前后各看了一条（10-16 与 10-18 各一条——±1 天窗共三天）
        val history = listOf(
            item("a", 1L, 0) to atMs(LocalDate.of(2023, 10, 16)),
            item("b", 2L, 0) to atMs(LocalDate.of(2023, 10, 18)),
            // 干扰项：其它日期看的（不在任何年轮窗内）
            item("c", 3L, 0) to atMs(LocalDate.of(2026, 10, 17)),
        )
        val memory = onThisDayHistoryMemory(history, today, zone)
        assertEquals(3, memory!!.yearsAgo)
        assertEquals(listOf("b", "a"), memory.items.map { it.key }) // 观看时刻倒序
    }

    @Test
    fun `择年条数优先_平手取更久远`() {
        val today = LocalDate.of(2026, 10, 17)
        val history = listOf(
            // 1 年前窗：2 条；4 年前窗：3 条（条数优先，取 4 年前）
            item("y1a", 1L, 0) to atMs(LocalDate.of(2025, 10, 17)),
            item("y1b", 2L, 0) to atMs(LocalDate.of(2025, 10, 17)),
            item("y4a", 1L, 0) to atMs(LocalDate.of(2022, 10, 17)),
            item("y4b", 2L, 0) to atMs(LocalDate.of(2022, 10, 16)),
            item("y4c", 3L, 0) to atMs(LocalDate.of(2022, 10, 18)),
        )
        val memory = onThisDayHistoryMemory(history, today, zone)
        assertEquals(4, memory!!.yearsAgo)
        assertEquals(3, memory.items.size)
        // 平手：3 年前 2 条 vs 2 年前 2 条 → 取更久远（3 年前）
        val tie = listOf(
            item("t3a", 1L, 0) to atMs(LocalDate.of(2023, 10, 17)),
            item("t3b", 2L, 0) to atMs(LocalDate.of(2023, 10, 17)),
            item("t2a", 3L, 0) to atMs(LocalDate.of(2024, 10, 17)),
            item("t2b", 4L, 0) to atMs(LocalDate.of(2024, 10, 17)),
        )
        assertEquals(3, onThisDayHistoryMemory(tie, today, zone)!!.yearsAgo)
    }

    @Test
    fun `低于最低记忆条数不成卡`() {
        val today = LocalDate.of(2026, 10, 17)
        // 2 年前窗只有 1 条（< MEMORY_MIN_ITEMS=2）——不成卡
        val history = listOf(item("only", 1L, 0) to atMs(LocalDate.of(2024, 10, 17)))
        assertNull(onThisDayHistoryMemory(history, today, zone))
    }

    @Test
    fun `空历史与无命中返回空`() {
        assertNull(onThisDayHistoryMemory(emptyList(), LocalDate.of(2026, 10, 17), zone))
        // 全部条目都不落在任何年轮窗（近一周看的）
        val history = listOf(item("x", 1L, 0) to atMs(LocalDate.of(2026, 10, 15)))
        assertNull(onThisDayHistoryMemory(history, LocalDate.of(2026, 10, 17), zone))
    }

    @Test
    fun `观看时刻边界_窗左闭右开`() {
        val today = LocalDate.of(2026, 10, 17)
        val winStart = onThisDayWindow(LocalDate.of(2023, 10, 17), zone).fromSec * 1000L
        val winEnd = onThisDayWindow(LocalDate.of(2023, 10, 17), zone).toSecExcl * 1000L
        // 窗起点（=10-16 00:00）命中左闭；窗终点不命中（右开）
        val history = listOf(
            item("in1", 1L, 0) to winStart,
            item("out", 2L, 0) to winEnd,
            item("in2", 3L, 0) to winEnd - 1, // 终点前 1ms 命中
        )
        val memory = onThisDayHistoryMemory(history, today, zone)
        assertEquals(listOf("in2", "in1"), memory!!.items.map { it.key })
    }

    @Test
    fun `maxYears 钳制不越界`() {
        val today = LocalDate.of(2026, 10, 17)
        // 只有 6 年前有存货（> MAX_YEARS=5）：默认口径不成卡；maxYears 放宽后命中
        val history = listOf(
            item("a", 1L, 0) to atMs(LocalDate.of(2020, 10, 17)),
            item("b", 2L, 0) to atMs(LocalDate.of(2020, 10, 17)),
        )
        assertNull(onThisDayHistoryMemory(history, today, zone))
        assertEquals(6, onThisDayHistoryMemory(history, today, zone, maxYears = 6)!!.yearsAgo)
    }
}
