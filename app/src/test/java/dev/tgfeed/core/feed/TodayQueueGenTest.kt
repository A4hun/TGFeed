package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R391 #7 今日单生成器行为例（JVM 直测，PlayQueueModelTest 同纪律——纯函数
 * 零 Android 依赖，时钟/种子全注入=同输入同输出测试确定性）。
 *
 * 锚面（r391_notes §3.1 定案）：
 *  - 诚实空态：双侧原料皆空 → null（不编造不硬凑）；单侧空=单侧收敛；
 *  - 探索侧：权重门（EXPLORE_WEIGHT_GATE）+降序+并列 chatId 升序破平+topN 上限；
 *  - 重温侧：收藏分桶（未看过优先=「收藏了还没看」最强信号）+topN 上限；
 *  - 混编：两侧交替穿插（探索一段、回味一条），一侧先尽=余量尾追；
 *  - 产物：今日台字段（name/orderMode=MANUAL/断点 null/id 前缀）+条目 id 单内唯一
 *   （收藏可同频道多条=chatId 尾会撞的自纠锚）+标题缺失回落。
 */
class TodayQueueGenTest {

    private fun item(key: String, chatId: Long, msgId: Long = key.hashCode().toLong() and 0xFFFF) =
        VideoItem(
            key = key,
            chatId = chatId,
            messageId = msgId,
            channelTitle = "频道$chatId",
            caption = "内容$key",
            durationSec = 30,
            width = 720,
            height = 1280,
            fileId = 7,
            sizeBytes = 1024L,
            playUri = "http://example.com/$key",
            dateEpochSec = 1_700_000_000 + msgId.toInt(),
        )

    // ---------------- 诚实空态（#7 铁律） ----------------

    @Test
    fun `双侧原料皆空_返回null不编造`() {
        val out = buildTodayQueue(TodayTasteInput(nowMs = 1_000L, seed = 7L))
        assertNull("权重空+收藏空=诚实空态 null（不编造空台）", out)
    }

    @Test
    fun `权重全在探索门下_原料缺返回null`() {
        // 0.3=decayedChannelWeights 过期回落档/互动打底同档——不冒充今日口味
        val out = buildTodayQueue(
            TodayTasteInput(
                channelWeights = mapOf(-1L to 0.3, -2L to 0.4, -3L to 0.549),
                nowMs = 1_000L,
                seed = 7L,
            ),
        )
        assertNull("权重全在门(0.55)下=null（过期口味不冒充）", out)
    }

    @Test
    fun `收藏独有_纯重温单收敛`() {
        val favs = listOf(item("f1", -10L), item("f2", -11L))
        val out = buildTodayQueue(
            TodayTasteInput(favorites = favs, nowMs = 1_000L, seed = 7L),
        )
        assertNotNull("单侧原料在场=单侧收敛（缺侧自然收敛不硬凑）", out)
        assertEquals(
            "纯重温单=收藏条目原序",
            listOf("f1", "f2"),
            out!!.entries.map { (it as PlayQueueEntry.Item).item.key },
        )
    }

    @Test
    fun `权重独有_纯探索单收敛`() {
        val out = buildTodayQueue(
            TodayTasteInput(
                channelWeights = mapOf(-1L to 0.9, -2L to 0.8),
                channelTitles = mapOf(-1L to "甲", -2L to "乙"),
                nowMs = 1_000L,
                seed = 7L,
            ),
        )
        assertNotNull(out)
        assertEquals(
            "纯探索单=权重降序台",
            listOf(-1L, -2L),
            out!!.entries.map { (it as PlayQueueEntry.Channel).chatId },
        )
        assertEquals("台标题=拼表供应", listOf("甲", "乙"), out.entries.map { (it as PlayQueueEntry.Channel).title })
    }

    // ---------------- 探索侧（权重门+降序+破平+上限） ----------------

    @Test
    fun `探索侧_权重门滤_降序_并列chatId升序破平`() {
        val out = buildTodayQueue(
            TodayTasteInput(
                // -5(0.55 恰在门上=入) / -1(0.3 门下=滤) / -3 与 -4 并列 0.9=chatId 升序破平
                //（负数域升序=-4 < -3 在前——红修笔6b 自纠：首稿期望把负数升序当 -3 在前写反）
                channelWeights = mapOf(-1L to 0.3, -5L to 0.55, -3L to 0.9, -4L to 0.9, -2L to 0.7),
                nowMs = 1_000L,
                seed = 7L,
            ),
        )!!
        assertEquals(
            "门上按权重降序+并列 chatId 升序（确定性破平）",
            listOf(-4L, -3L, -2L, -5L),
            out.entries.map { (it as PlayQueueEntry.Channel).chatId },
        )
    }

    @Test
    fun `探索侧_超过上限取top5`() {
        // 升序区间构造 8 台（-8..-1 全过门）；-1L..-8L 是空区间（downTo 才是降序）
        val weights = (-8L..-1L).associateWith { 0.9 }
        val out = buildTodayQueue(
            TodayTasteInput(channelWeights = weights, nowMs = 1_000L, seed = 7L),
        )!!
        assertEquals(
            "探索台数上限 EXPLORE_CHANNELS_MAX=5",
            TodayQueueRecipe.EXPLORE_CHANNELS_MAX,
            out.entries.size,
        )
        assertEquals(
            "8 台并列 0.9=chatId 升序（-8 最小在前）取前 5",
            listOf(-8L, -7L, -6L, -5L, -4L),
            out.entries.map { (it as PlayQueueEntry.Channel).chatId },
        )
    }

    @Test
    fun `探索侧_标题缺失回落频道id`() {
        val out = buildTodayQueue(
            TodayTasteInput(
                channelWeights = mapOf(-42L to 0.9),
                channelTitles = emptyMap(),
                nowMs = 1_000L,
                seed = 7L,
            ),
        )!!
        assertEquals("标题缺失回落「频道 <id>」（SmartRooms 同口径）", "频道 -42", (out.entries[0] as PlayQueueEntry.Channel).title)
    }

    // ---------------- 重温侧（分桶+上限） ----------------

    @Test
    fun `重温侧_未看过的收藏优先`() {
        val favs = listOf(
            item("seen1", -10L),   // 已看（真重温桶）
            item("fresh1", -10L),  // 未看（「收藏了还没看」最强信号桶）
            item("seen2", -11L),   // 已看
            item("fresh2", -11L),  // 未看
        )
        val out = buildTodayQueue(
            TodayTasteInput(favorites = favs, watchedKeys = setOf("seen1", "seen2"), nowMs = 1_000L, seed = 7L),
        )!!
        assertEquals(
            "未看桶在前（桶内收藏时间倒序原序）+已看桶在后",
            listOf("fresh1", "fresh2", "seen1", "seen2"),
            out.entries.map { (it as PlayQueueEntry.Item).item.key },
        )
    }

    @Test
    fun `重温侧_超过上限取top8`() {
        val favs = (1..12).map { item("f$it", -10L) }
        val out = buildTodayQueue(
            TodayTasteInput(favorites = favs, nowMs = 1_000L, seed = 7L),
        )!!
        assertEquals(
            "重温条数上限 FAVORITE_ITEMS_MAX=8",
            TodayQueueRecipe.FAVORITE_ITEMS_MAX,
            out.entries.size,
        )
    }

    // ---------------- 混编（交替穿插+尾追） ----------------

    @Test
    fun `混编_台与收藏交替穿插`() {
        val out = buildTodayQueue(
            TodayTasteInput(
                channelWeights = mapOf(-1L to 0.9, -2L to 0.8, -3L to 0.7),
                favorites = listOf(item("f1", -10L), item("f2", -11L)),
                nowMs = 1_000L,
                seed = 7L,
            ),
        )!!
        // 交替：台1、藏1、台2、藏2、台3（收藏先尽=台余量尾追）
        assertEquals(
            listOf("ch", "it", "ch", "it", "ch"),
            out.entries.map { if (it is PlayQueueEntry.Channel) "ch" else "it" },
        )
        assertEquals(
            "台按权重降序穿插",
            listOf(-1L, -2L, -3L),
            out.entries.filterIsInstance<PlayQueueEntry.Channel>().map { it.chatId },
        )
        assertEquals(
            "收藏按分桶序穿插",
            listOf("f1", "f2"),
            out.entries.filterIsInstance<PlayQueueEntry.Item>().map { it.item.key },
        )
    }

    @Test
    fun `混编_台先尽收藏余量尾追`() {
        val out = buildTodayQueue(
            TodayTasteInput(
                channelWeights = mapOf(-1L to 0.9),
                favorites = listOf(item("f1", -10L), item("f2", -11L), item("f3", -12L)),
                nowMs = 1_000L, seed = 7L,
            ),
        )!!
        // 台1、藏1 → 台尽 → 藏2、藏3 尾追
        assertEquals(
            listOf("ch", "it", "it", "it"),
            out.entries.map { if (it is PlayQueueEntry.Channel) "ch" else "it" },
        )
    }

    // ---------------- 产物字段与 id 唯一性 ----------------

    @Test
    fun `产物字段_今日台_MANUAL档_断点null_id前缀`() {
        val out = buildTodayQueue(
            TodayTasteInput(
                channelWeights = mapOf(-1L to 0.9),
                favorites = listOf(item("f1", -10L)),
                nowMs = 123_456L,
                seed = 99L,
            ),
        )!!
        assertEquals("name=今日台", TodayQueueRecipe.STATION_NAME, out.name)
        assertEquals("推荐序即手排序（一期判词直接表达）", PlayQueueOrder.MANUAL, out.orderMode)
        assertNull("今日单新断点 null=从头播", out.resumeEntryId)
        assertNull(out.resumeKey)
        assertTrue("id=qs_today 前缀（seed+nowMs 组合）", out.id.startsWith("qs_today_99_123456"))
        assertEquals("updatedAt=nowMs", 123_456L, out.updatedAt)
    }

    @Test
    fun `条目id_同频道多条收藏_单内绝对唯一`() {
        // 同频道多条收藏：首稿 chatId 尾方案会撞 key（LazyColumn key 崩溃面）——
        // 掺序号自纠锚
        val favs = (1..5).map { item("sameCh$it", -10L) }
        val out = buildTodayQueue(
            TodayTasteInput(favorites = favs, nowMs = 1_000L, seed = 7L),
        )!!
        val ids = out.entries.map { it.id }
        assertEquals("同频道多条收藏 id 仍单内唯一", ids.size, ids.toSet().size)
    }

    @Test
    fun `确定性_同输入同输出`() {
        // 独立构造两份同内容输入（非 copy——copy 与原对象恒等无断言价值）
        fun make() = TodayTasteInput(
            channelWeights = mapOf(-1L to 0.9, -2L to 0.8),
            channelTitles = mapOf(-1L to "甲"),
            favorites = listOf(item("f1", -10L), item("f2", -11L)),
            watchedKeys = setOf("f1"),
            nowMs = 42_000L,
            seed = 3L,
        )
        assertEquals(
            "时钟/种子全注入=同输入同输出（测试确定性不赌机器时间）",
            buildTodayQueue(make()),
            buildTodayQueue(make()),
        )
    }

    // ---------------- R402 今日台上插（覆盖双插毒态修——V65-③ R401 抓实） ----------------

    private fun station(id: String, name: String = "台$id", updatedAt: Long = 1_000L) =
        PlayQueue(id = id, name = name, entries = emptyList(), updatedAt = updatedAt)

    @Test
    fun `覆盖旧今日台_单条在列_总条数不变_零双插`() {
        val old = station("qs_old", TodayQueueRecipe.STATION_NAME)
        val other = station("qs_other")
        val generated = station("qs_new", TodayQueueRecipe.STATION_NAME)
        val out = upsertTodayStation(listOf(old, other), generated)
        assertEquals("旧今日台被换,总条数不变", 2, out.size)
        assertEquals("今日台唯一（旧写法此处=2 条同 id=崩溃面）", 1, out.count { it.name == TodayQueueRecipe.STATION_NAME })
        assertEquals("qs_new", out.first { it.name == TodayQueueRecipe.STATION_NAME }.id)
        assertTrue("普通台不受影响", out.any { it.id == "qs_other" })
    }

    @Test
    fun `毒态重复今日台_自愈收敛为单条`() {
        val dup = station("qs_new", TodayQueueRecipe.STATION_NAME)
        val poisoned = listOf(station("qs_old", TodayQueueRecipe.STATION_NAME), dup, dup)
        val out = upsertTodayStation(poisoned, station("qs_new", TodayQueueRecipe.STATION_NAME))
        assertEquals("历史毒态重复条全收敛", 1, out.size)
        assertEquals("qs_new", out[0].id)
    }

    @Test
    fun `同id重入_不双插`() {
        val existing = station("qs_x")
        val out = upsertTodayStation(listOf(existing), station("qs_x"))
        assertEquals("同 id 重入守门（单条）", 1, out.size)
    }

    @Test
    fun `新增路_满员淘汰最旧`() {
        val stations = (1..PlayQueueModel.MAX_STATIONS).map { station("qs_$it", updatedAt = it * 100L) }
        val out = upsertTodayStation(stations, station("qs_new", updatedAt = 99_999L))
        assertEquals(PlayQueueModel.MAX_STATIONS, out.size)
        assertTrue("最旧（qs_1）被淘汰", out.none { it.id == "qs_1" })
        assertTrue("新台在列", out.any { it.id == "qs_new" })
    }

    @Test
    fun `空列表_直接追加`() {
        val out = upsertTodayStation(emptyList(), station("qs_new"))
        assertEquals(1, out.size)
    }
}
