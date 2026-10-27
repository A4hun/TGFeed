package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R398 #10 队列语义跳转行为例（findNextSemanticMatch/dropPlanPrefixBefore
 * 纯函数 JVM 直测——PlayChainTest/TodayQueueGenTest 同纪律；r398_notes §3.1
 * 定案的逐例兑现）。
 */
class QueueSemanticJumpTest {

    private fun item(
        key: String,
        caption: String,
        skipped: Boolean = false,
    ) = PlayQueueEntry.Item(
        id = "eq_$key",
        item = VideoItem(
            key = key,
            chatId = -10L,
            messageId = key.hashCode().toLong(),
            channelTitle = "频道",
            caption = caption,
            durationSec = 30,
            width = 720,
            height = 1280,
            fileId = 7,
            sizeBytes = 1024L,
            playUri = null,
        ),
        skipped = skipped,
    )

    private fun channel(key: String, title: String) =
        PlayQueueEntry.Channel(id = "eq_$key", chatId = -20L, title = title)

    // ---------------- 匹配基本语义 ----------------

    @Test
    fun `命中_caption包含query返回该条目`() {
        val hit = item("a", "今天讲一下热带雨林的生态")
        val out = findNextSemanticMatch(listOf(channel("c", "随便台"), hit), "雨林", 0)
        assertEquals("caption 包含即命中（条目 id 双凭据之一）", "eq_a", out?.id)
        assertEquals("item key=跳播落点凭据之二", "a", out?.item?.key)
    }

    @Test
    fun `大小写不敏感_query与caption大小写互异仍命中`() {
        val hit = item("a", "Deep Dive: Kotlin Coroutines")
        val out = findNextSemanticMatch(listOf(hit), "kotlin", 0)
        assertEquals("query 小写/原文大写=命中", "eq_a", out?.id)
        val out2 = findNextSemanticMatch(listOf(item("b", "kotlin coroutines")), "KOTLIN", 0)
        assertEquals("query 大写/原文小写=命中", "eq_b", out2?.id)
    }

    @Test
    fun `多重命中取首个_扫描序即条目序所见即所得`() {
        val first = item("a", "讲雨林一期")
        val second = item("b", "讲雨林二期")
        val out = findNextSemanticMatch(listOf(first, second, channel("c", "x")), "雨林", 0)
        assertSame("首个命中（后续命中不抢）", first, out)
    }

    @Test
    fun `空查询_trim后空返回null无操作诚实`() {
        val entries = listOf(item("a", "讲雨林"))
        assertNull("空串", findNextSemanticMatch(entries, "", 0))
        assertNull("纯空白（空格）", findNextSemanticMatch(entries, "   ", 0))
        assertNull("纯空白（tab）", findNextSemanticMatch(entries, "\t", 0))
    }

    @Test
    fun `无果_找不到返回null诚实零网络`() {
        val out = findNextSemanticMatch(listOf(item("a", "讲雨林"), channel("c", "台")), "量子力学", 0)
        assertNull("无匹配=null（UI Toast 无果如实）", out)
    }

    // ---------------- 条目类型与跳过判词 ----------------

    @Test
    fun `跳过条目不参与_播放期排除开单不出现`() {
        val skippedHit = item("a", "讲雨林", skipped = true)
        val laterHit = item("b", "也讲雨林")
        val out = findNextSemanticMatch(listOf(skippedHit, laterHit), "雨林", 0)
        assertSame("skipped 条目被跳过，取后续命中", laterHit, out)
        assertNull("全部命中条目都跳过=null", findNextSemanticMatch(listOf(skippedHit), "雨林", 0))
    }

    @Test
    fun `频道条目永不匹配_内容非本地零网络判词`() {
        // 频道条目只有 chatId+title——title 含 query 也不算命中（台名匹配非
        // 内容匹配；频道段「讲什么」要现拉，零网络承诺下无从判定）
        val ch = channel("c", "雨林频道")
        assertNull("title 含 query 的频道条目不命中", findNextSemanticMatch(listOf(ch), "雨林", 0))
        // 纯频道单=恒无果（UI 搜索条如实无果判词同源）
        assertNull(
            "纯频道单搜什么恒 null",
            findNextSemanticMatch(listOf(channel("c1", "A"), channel("c2", "B")), "A", 0),
        )
    }

    // ---------------- fromIndex 边界与不回绕 ----------------

    @Test
    fun `fromIndex起扫含自身_断点后位语义`() {
        val a = item("a", "讲雨林一期")
        val b = item("b", "讲雨林二期")
        assertSame("fromIndex 含自身（指向命中条目=命中它）", b, findNextSemanticMatch(listOf(a, b), "雨林", 1))
    }

    @Test
    fun `不回绕_尾部之后无果即null不偷跑头部`() {
        val a = item("a", "讲雨林")
        val b = item("b", "讲别的")
        // 断点在 b（fromIndex=1）：a 在头部命中也不回绕
        assertNull("头部命中不回绕（单向扫描诚实）", findNextSemanticMatch(listOf(a, b), "雨林", 1))
    }

    @Test
    fun `fromIndex负数收敛0_越界空扫null`() {
        val a = item("a", "讲雨林")
        assertSame("负数=从头扫", a, findNextSemanticMatch(listOf(a), "雨林", -3))
        assertNull("越界=空扫无果", findNextSemanticMatch(listOf(a), "雨林", 5))
    }

    @Test
    fun `空条目表_任何query返回null`() {
        assertNull(findNextSemanticMatch(emptyList(), "雨林", 0))
    }

    // ---------------- 段前缀剪除（深位保落判词） ----------------

    @Test
    fun `entryId为null_原样返回一期行为零变`() {
        val plan = listOf(
            PlaySegment.Channel("eq_c", -20L, "台"),
            PlaySegment.Single("eq_a", (item("a", "x")).item),
        )
        assertSame("null=不剪（默认开单路径逐字零变）", plan, dropPlanPrefixBefore(plan, null))
    }

    @Test
    fun `命中段剪除前缀_匹配段前移首页`() {
        val plan = listOf(
            PlaySegment.Channel("eq_c1", -1L, "台一"),
            PlaySegment.Single("eq_a", item("a", "讲雨林").item),
            PlaySegment.Single("eq_b", item("b", "讲别的").item),
        )
        val out = dropPlanPrefixBefore(plan, "eq_a")
        assertEquals("前缀（含频道段）剪除，匹配段起头", 2, out.size)
        assertEquals("首位=匹配段", "eq_a", out.first().entryId)
        assertEquals("匹配段之后原样保留", "eq_b", out[1].entryId)
    }

    @Test
    fun `命中段已在首位_无前缀可剪原样`() {
        val plan = listOf(
            PlaySegment.Single("eq_a", item("a", "讲雨林").item),
            PlaySegment.Channel("eq_c", -20L, "台"),
        )
        assertSame("首位=原样（同一实例零拷贝）", plan, dropPlanPrefixBefore(plan, "eq_a"))
    }

    @Test
    fun `失联entryId_原样返回诚实降级`() {
        val plan = listOf(
            PlaySegment.Single("eq_a", item("a", "x").item),
            PlaySegment.Channel("eq_c", -20L, "台"),
        )
        assertSame("段不在 plan（队列编辑换代防御）=原样全量开单", plan, dropPlanPrefixBefore(plan, "eq_gone"))
    }

    @Test
    fun `空plan_任何entryId原样空`() {
        assertTrue(dropPlanPrefixBefore(emptyList(), null).isEmpty())
        assertTrue(dropPlanPrefixBefore(emptyList(), "eq_a").isEmpty())
    }
}
