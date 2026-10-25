package dev.tgfeed.core.plaza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TL-9 弹幕时间旅行（R205 v1 纯回放）单测：
 *  - [D] 行文法（parseDanmakuLine 单一定义：合法 key/白名单/截断/空内容）；
 *  - 广场历史行 → 时间轴（时间取所在消息时刻、升序、非 [D] 行忽略、多行消息逐行）；
 *  - 信任层（单发送者协议消息超限整窗忽略；[W] 采样行不计入——fetchPlaza R130 同口径）。
 *
 * 数据纪律：测试数据全部合成（发送者 u:1/u:2 代称），无真实广场内容。
 */
class DanmakuTimelineTest {

    private val base = 1_700_000_000L

    private fun row(
        msgId: Long,
        sender: String,
        text: String,
        dateSec: Long,
    ) = PlazaHistoryRow(messageId = msgId, text = text, dateSec = dateSec, sender = sender)

    // ---------------- [D] 行文法（PlazaService.parseDanmakuLine 单一定义） ----------------

    @Test
    fun `文法_合法行_拆出key与内容`() {
        val (key, content) = PlazaService.parseDanmakuLine("[D] tg:-1001234567890:42 好活")!!
        assertEquals("tg:-1001234567890:42", key)
        assertEquals("好活", content)
    }

    @Test
    fun `文法_非D前缀_闲聊行_返回null`() {
        assertNull(PlazaService.parseDanmakuLine("[W] tg:1:2"))
        assertNull(PlazaService.parseDanmakuLine("[L] tg:1:2"))
        assertNull(PlazaService.parseDanmakuLine("群友闲聊"))
        assertNull(PlazaService.parseDanmakuLine("[D]"))
    }

    @Test
    fun `文法_key不合规或内容为空_返回null`() {
        assertNull(PlazaService.parseDanmakuLine("[D] tg:abc:1 内容")) // key 白名单不过
        assertNull(PlazaService.parseDanmakuLine("[D] tg:1:2    ")) // 空内容（trim 后空）
    }

    @Test
    fun `文法_内容截断42字`() {
        val long = "好".repeat(60)
        val (_, content) = PlazaService.parseDanmakuLine("[D] tg:1:2 $long")!!
        assertEquals(PlazaService.MAX_DANMAKU_CHARS, content.length)
    }

    // ---------------- 时间轴解析 ----------------

    @Test
    fun `解析_D行成事件_时刻取所在消息时刻_升序输出`() {
        val rows = listOf(
            row(100, "u:2", "[D] tg:1:5 晚来的弹幕", base + 7200),
            row(99, "u:1", "[D] tg:1:5 第一条弹幕", base),
            row(98, "u:1", "群友闲聊（非协议）", base + 60),
            row(97, "u:2", "[W] tg:1:5", base + 30),
            row(96, "u:2", "[L] tg:1:5", base + 45),
        )
        val tl = parseDanmakuTimeline(rows)
        assertEquals(2, tl.events.size)
        assertEquals(listOf(base, base + 7200), tl.events.map { it.timestampSec })
        assertEquals("第一条弹幕", tl.events.first().text)
        // key 反解出 chatId/messageId（「哪条视频」的线索）
        assertEquals(1L, tl.events.first().chatId)
        assertEquals(5L, tl.events.first().messageId)
        assertEquals(5, tl.scannedRows)
    }

    @Test
    fun `解析_一条消息多行_逐行成多条事件`() {
        val rows = listOf(
            row(100, "u:1", "[D] tg:1:5 第一条\n[D] tg:1:5 第二条", base),
        )
        val tl = parseDanmakuTimeline(rows)
        assertEquals(2, tl.events.size)
        assertEquals(listOf("第一条", "第二条"), tl.events.map { it.text })
    }

    @Test
    fun `解析_不合规D行跳过_合规行保留`() {
        val rows = listOf(
            row(100, "u:1", "[D] tg:bad:key 内容\n[D] tg:1:5 合规", base),
        )
        val tl = parseDanmakuTimeline(rows)
        assertEquals(1, tl.events.size)
        assertEquals("合规", tl.events.first().text)
    }

    @Test
    fun `解析_空输入_空时间轴`() {
        val tl = parseDanmakuTimeline(emptyList())
        assertTrue(tl.events.isEmpty())
        assertEquals(0, tl.scannedRows)
    }

    // ---------------- 信任层（fetchPlaza 同口径） ----------------

    @Test
    fun `信任层_刷屏发送者整窗忽略`() {
        // u:spam 连发 11 条协议消息（> SPAM_PROTOCOL_LIMIT=10）→ 全部忽略；
        // u:normal 1 条照常
        val rows = buildList {
            repeat(11) { i -> add(row(200L - i, "u:spam", "[D] tg:1:5 刷屏$i", base + i)) }
            add(row(150, "u:normal", "[D] tg:1:5 正常弹幕", base + 100))
        }
        val tl = parseDanmakuTimeline(rows)
        assertEquals(1, tl.events.size)
        assertEquals("正常弹幕", tl.events.first().text)
    }

    @Test
    fun `信任层_W采样行不计入刷屏_重度观看者不被误伤`() {
        // R130 口径：[W] 是系统自动采样，不计入协议消息计数——发 20 条 [W] 也不触发
        val rows = buildList {
            repeat(20) { i -> add(row(300L - i, "u:watcher", "[W] tg:1:${i + 1}", base + i)) }
            add(row(280, "u:watcher", "[D] tg:1:5 看了还想说一句", base + 60))
        }
        val tl = parseDanmakuTimeline(rows)
        assertEquals(1, tl.events.size)
        assertEquals("看了还想说一句", tl.events.first().text)
    }

    @Test
    fun `信任层_边界值_恰好等于上限不忽略`() {
        // 恰好 SPAM_PROTOCOL_LIMIT（=10）条协议消息：不超限，保留
        val rows = buildList {
            repeat(PlazaService.SPAM_PROTOCOL_LIMIT) { i -> add(row(400L - i, "u:edge", "[D] tg:1:5 边界$i", base + i)) }
        }
        val tl = parseDanmakuTimeline(rows)
        assertEquals(PlazaService.SPAM_PROTOCOL_LIMIT, tl.events.size)
    }

    @Test
    fun `信任层_同限值常量_与fetchPlaza口径同源`() {
        // 单一定义守护：解析层用的就是 PlazaService 的常量（防两处口径漂移）
        assertEquals(10, PlazaService.SPAM_PROTOCOL_LIMIT)
        assertNotNull(PlazaService.KEY_REGEX)
    }
}
