package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R142 分P识别纯函数单测（识别逻辑必须测）：
 *  ① [parseEpisodeMark]：编号正则族（S01E02/EP\d+/第N[集话話回]/（N）/上中下/Part N），
 *     中文数字解析、组合编号（第3集上＝31）、年份误伤挡板、剧名锚归一化；
 *  ② [episodeChains]：三证据聚类（相册组/剧名锚/时间邻接+时长接近）、链内排序、
 *     跨频道不并、间隔/时长/季号/风格的防串台守卫；
 *  ③ [episodeHop]：链内下一集（含相册位置顺延、同号补发跳过）、链尾回落 null。
 */
class EpisodeChainTest {

    // ---------------- 夹具 ----------------

    /** 构造视频条目：dateEpochSec 按"发布越早值越小"由调用方给 */
    private fun video(
        key: String,
        chatId: Long = -100L,
        caption: String = "",
        date: Int,
        messageId: Long,
        durationSec: Int = 45 * 60,
        albumId: Long = 0L,
        mediaType: Int = 0,
    ) = VideoItem(
        key = key,
        chatId = chatId,
        messageId = messageId,
        channelTitle = "频道$chatId",
        caption = caption,
        durationSec = durationSec,
        width = 1280,
        height = 720,
        fileId = -1,
        sizeBytes = 1L,
        playUri = null,
        dateEpochSec = date,
        albumId = albumId,
        mediaType = mediaType,
    )

    /** 一部剧的散帖条目集：第 1..n 集，发布顺序＝集序（E1 最早），Feed 序（新→旧）返回 */
    private fun series(
        chatId: Long = -100L,
        title: String = "某剧",
        count: Int = 3,
        perEpGapSec: Int = 3600,
        durationSec: Int = 45 * 60,
        baseDate: Int = 1_700_000_000,
        numbering: (Int) -> String = { "第${it}集" },
        keyPrefix: String = "s",
        messageIdBase: Long = 1000L,
    ): List<VideoItem> {
        val items = (1..count).map { n ->
            video(
                key = "${keyPrefix}${chatId}_$n",
                chatId = chatId,
                caption = "$title ${numbering(n)} 1080P",
                date = baseDate - (count - n) * perEpGapSec, // E1 最早
                messageId = messageIdBase + n,
                durationSec = durationSec,
            )
        }
        return items.reversed() // Feed 序：新→旧
    }

    private fun keysOf(items: List<VideoItem>) = items.map { it.key }

    // ---------------- ① parseEpisodeMark：编号正则族 ----------------

    @Test
    fun `季集式 S01E02 取集号存季号`() {
        val m = parseEpisodeMark("权力的游戏 S01E05 1080P")!!
        assertEquals(5, m.number)
        assertEquals(1, m.season)
        assertEquals(EpisodeMarkKind.SEASON_EP, m.kind)
    }

    @Test
    fun `EP 数字与纯 E 数字`() {
        assertEquals(12, parseEpisodeMark("无职转生 EP12")!!.number)
        assertEquals(3, parseEpisodeMark("ep 3 国语")!!.number)
        assertEquals(7, parseEpisodeMark("E07 中字")!!.number)
        // S01E02 不被纯 E 误抢（E 前面是数字）
        assertEquals(2, parseEpisodeMark("S01E02")!!.number)
    }

    @Test
    fun `中文数字集号一至九百九十九`() {
        assertEquals(1, parseEpisodeMark("第1集")!!.number)
        assertEquals(12, parseEpisodeMark("第十二集")!!.number)
        assertEquals(24, parseEpisodeMark("第二十四集")!!.number)
        assertEquals(103, parseEpisodeMark("第一百零三集")!!.number)
        assertEquals(325, parseEpisodeMark("第三百二十五话")!!.number)
        assertEquals(20, parseEpisodeMark("第20回")!!.number)
    }

    @Test
    fun `括号编号与 Part 编号`() {
        assertEquals(5, parseEpisodeMark("资源合集（5）")!!.number)
        assertEquals(12, parseEpisodeMark("part 12 生肉")!!.number)
        assertEquals(3, parseEpisodeMark("PART3 1080P")!!.number)
    }

    @Test
    fun `上中下当集号与拆段组合`() {
        assertEquals(1, parseEpisodeMark("上部")!!.number)
        assertEquals(2, parseEpisodeMark("中集")!!.number)
        assertEquals(3, parseEpisodeMark("下篇")!!.number)
        assertEquals(1, parseEpisodeMark("上")!!.number) // 独占整行
        // 拆段组合：第3集拆三段 → 集号3＋段序1/2/3，序键与第4集保序
        assertEquals(3, parseEpisodeMark("第3集（上）")!!.number)
        assertEquals(1, parseEpisodeMark("第3集（上）")!!.part)
        assertEquals(2, parseEpisodeMark("第3集 中集")!!.part)
        assertEquals(3, parseEpisodeMark("第3集（下）")!!.part)
        assertTrue(parseEpisodeMark("第3集（上）")!!.seqKey() < parseEpisodeMark("第3集 中集")!!.seqKey())
        assertTrue(parseEpisodeMark("第3集 中集")!!.seqKey() < parseEpisodeMark("第3集（下）")!!.seqKey())
        assertTrue(parseEpisodeMark("第3集（下）")!!.seqKey() < parseEpisodeMark("第4集")!!.seqKey())
    }

    @Test
    fun `无编号与误伤挡板`() {
        assertNull(parseEpisodeMark("纯标题没有编号"))
        assertNull(parseEpisodeMark("1080P 高清资源"))
        assertNull(parseEpisodeMark("")) // 空 caption
        assertNull(parseEpisodeMark("补档（2023）")) // 括号 4 位年份：不是集号
        // 1080P 的 1080 超"纯 E"位数上限，E 字样不被误读
        assertNull(parseEpisodeMark("E1080P 高清"))
    }

    @Test
    fun `剧名锚归一化去装饰`() {
        assertEquals("某剧", parseEpisodeMark("【4K】【某剧】第1集")!!.anchor)
        assertEquals("某剧", parseEpisodeMark("#推荐 某剧 第2集")!!.anchor)
        assertEquals("某剧", parseEpisodeMark("@某频道 某剧（3）")!!.anchor)
        // 编号顶句首 → 无锚（回落时间邻接证据）
        assertEquals("", parseEpisodeMark("第1集 某剧")!!.anchor)
        // 互为后缀判同
        assertTrue(anchorsMatch("本周推荐某剧", "某剧"))
        assertFalse(anchorsMatch("剧甲", "剧乙"))
    }

    // ---------------- ② episodeChains：三证据聚类 ----------------

    @Test
    fun `同频道时间邻接同剧聚合一条链且按集序排`() {
        val items = series(count = 12)
        val chains = episodeChains(items)
        assertEquals(1, chains.size)
        assertEquals(12, chains[0].episodes.size)
        // 链内第 1 集在前（Feed 列表本身是新→旧，链序要翻回播放序）
        assertEquals(keysOf(items.reversed()), chains[0].episodes.map { it.item.key })
    }

    @Test
    fun `跨频道不并链`() {
        val a = series(chatId = -100L, count = 3)
        val b = series(chatId = -200L, count = 3)
        val chains = episodeChains(a + b)
        assertEquals(2, chains.size)
        assertTrue(chains.all { it.episodes.size == 3 })
        assertTrue(chains.any { it.chatId == -100L && it.episodes.all { e -> e.item.chatId == -100L } })
    }

    @Test
    fun `不同剧名锚不串台`() {
        val a = series(title = "剧甲", count = 3)
        val b = series(title = "剧乙", count = 3)
        val chains = episodeChains(a + b)
        assertEquals(2, chains.size)
        assertTrue(chains.all { it.episodes.size == 3 })
    }

    @Test
    fun `时间间隔超窗不并链`() {
        // 两批各 3 集：批内小时级，批间 60 天
        val old = series(count = 3, keyPrefix = "old")
        val recent = series(
            count = 3, keyPrefix = "new",
            baseDate = 1_700_000_000 + 60 * 24 * 3600,
        )
        val chains = episodeChains(old + recent)
        assertEquals(2, chains.size) // 旧批/新批各一条
    }

    @Test
    fun `时长差距过大不并链`() {
        // 同剧名锚，但一批正片 45min、一批片花 5min：时长守卫拆开
        val a = series(title = "某剧", count = 3, durationSec = 45 * 60)
        val b = series(
            title = "某剧", count = 3, durationSec = 5 * 60,
            keyPrefix = "t", messageIdBase = 2000L,
        )
        val chains = episodeChains(a + b)
        assertTrue(chains.none { it.episodes.size == 6 })
    }

    @Test
    fun `季号不同不并链`() {
        // 同剧名锚的两季（S01/S02）：季号守卫拆开，不出现 6 集大链
        val s1 = series(count = 3, numbering = { "S01E%02d".format(it) })
        val s2 = series(
            count = 3, numbering = { "S02E%02d".format(it) },
            keyPrefix = "t", messageIdBase = 2000L,
        )
        val chains = episodeChains(s1 + s2)
        assertTrue(chains.none { it.episodes.size == 6 })
        assertEquals(2, chains.size)
    }

    @Test
    fun `相册组直接成链且按发送序`() {
        // 拆条发全集：一个相册 4 名成员，共享 caption（网关形态A：同组空成员补同文案）
        val base = 1_700_000_000
        val members = (1..4).map { n ->
            video(
                key = "alb_$n",
                caption = "某剧全集 第1-4集",
                date = base,
                messageId = 900L + n,
                albumId = 77L,
            )
        }
        val chains = episodeChains(members)
        assertEquals(1, chains.size)
        assertEquals(listOf("alb_1", "alb_2", "alb_3", "alb_4"), chains[0].episodes.map { it.item.key })
    }

    @Test
    fun `相册组与后续散帖续接成链`() {
        // 相册装 1-4 集，后面频道又用散帖发 5、6 集
        val base = 1_700_000_000
        val album = (1..4).map { n ->
            video(
                key = "alb_$n",
                caption = "某剧 相册合集",
                date = base,
                messageId = 900L + n,
                albumId = 77L,
            )
        }
        val singles = listOf(
            video(key = "s5", caption = "某剧 第5集", date = base + 3600, messageId = 950L),
            video(key = "s6", caption = "某剧 第6集", date = base + 7200, messageId = 951L),
        )
        val chains = episodeChains((album + singles).reversed())
        // 相册块（无编号）按 messageId 插位，散帖按集号 → 播放序 alb1..4, s5, s6
        assertEquals(1, chains.size)
        assertEquals(
            listOf("alb_1", "alb_2", "alb_3", "alb_4", "s5", "s6"),
            chains[0].episodes.map { it.item.key },
        )
    }

    @Test
    fun `图文音频不参与链`() {
        val base = 1_700_000_000
        val items = listOf(
            video(key = "v1", caption = "第1集", date = base, messageId = 1L),
            video(key = "p1", caption = "第2集", date = base - 60, messageId = 2L, mediaType = 1),
            video(key = "a1", caption = "第3集", date = base - 120, messageId = 3L, mediaType = 2),
        )
        assertTrue(episodeChains(items).isEmpty())
    }

    @Test
    fun `无编号且无相册的散条不成链`() {
        val base = 1_700_000_000
        val items = listOf(
            video(key = "x1", caption = "随便一个视频", date = base, messageId = 1L),
            video(key = "x2", caption = "又一个视频", date = base - 60, messageId = 2L),
        )
        assertTrue(episodeChains(items).isEmpty())
    }

    // ---------------- ③ episodeHop：链内下一集 ----------------

    @Test
    fun `链内下一集与链尾回落`() {
        val items = series(count = 3) // Feed 序：E3,E2,E1
        val hop1 = episodeHop(items, "s-100_1")!!
        assertEquals("s-100_2", hop1.to.key)
        assertEquals("1", hop1.fromLabel)
        assertEquals("2", hop1.toLabel)
        assertEquals("s-100_3", episodeHop(items, "s-100_2")!!.to.key)
        // 链尾：E3 后没有下一集
        assertNull(episodeHop(items, "s-100_3"))
        // 不在链上
        assertNull(episodeHop(items, "not_exist"))
    }

    @Test
    fun `同号补发跳过`() {
        val base = 1_700_000_000
        val e2 = video(key = "e2", caption = "某剧 第2集", date = base, messageId = 12L)
        val e2re = video(key = "e2re", caption = "某剧 第2集 720P补发", date = base + 600, messageId = 13L)
        val e3 = video(key = "e3", caption = "某剧 第3集", date = base + 1200, messageId = 14L)
        val items = listOf(e3, e2re, e2) // Feed 新→旧
        val hop = episodeHop(items, "e2")!!
        assertEquals("e3", hop.to.key) // 跳过同号补发
    }

    @Test
    fun `链内下一集关系无环且必达链尾`() {
        // 防环回归锚点：从链上任一成员出发反复跳下一集，步数有限（≤链长）且
        // 不回到走过的成员——链尾回落 null 由调用方接顺序连播（FeedScreen 侧），
        // 顺序翻页落回链成员时靠 VM 侧"目标已播过不回头"守卫断环（见 FeedEpisodePart）
        val items = series(count = 12)
        for (start in 1..12) {
            var curKey = "s-100_$start"
            val walked = mutableSetOf(curKey)
            var steps = 0
            while (true) {
                val hop = episodeHop(items, curKey) ?: break
                curKey = hop.to.key
                steps++
                // 严格前进：走过的成员不再出现（出现即环）
                assertTrue("跳集成环：start=$start 重访 $curKey", walked.add(curKey))
                assertTrue("步数超链长：start=$start steps=$steps", steps <= 12)
            }
            // 12 集链从第 k 集出发走 (12-k) 步到链尾
            assertEquals(12 - start, steps)
        }
    }

    @Test
    fun `相册共享 caption 成员位置顺延`() {
        val base = 1_700_000_000
        val members = (1..3).map { n ->
            video(
                key = "alb_$n",
                caption = "某剧全集 共3集", // 无编号：走相册组证据
                date = base,
                messageId = 900L + n,
                albumId = 55L,
            )
        }
        val items = members // 相册成员（列表序随意）
        val hop1 = episodeHop(items, "alb_1")!!
        assertEquals("alb_2", hop1.to.key)
        assertEquals("1", hop1.fromLabel) // 位置序号
        assertEquals("2", hop1.toLabel)
        assertEquals("alb_3", episodeHop(items, "alb_2")!!.to.key)
        assertNull(episodeHop(items, "alb_3")) // 相册链尾
    }

    @Test
    fun `共享编号相册跳散帖不倒退`() {
        // 相册整表共享 caption "某剧 第1集至第6集"（每名成员都被补成同文案 → 同号 1）
        val base = 1_700_000_000
        val album = (1..6).map { n ->
            video(
                key = "alb_$n",
                caption = "某剧 第1集", // 网关共享 caption 补全后的形态：整表同号
                date = base,
                messageId = 900L + n,
                albumId = 66L,
            )
        }
        // 更早的散帖"第2集"（比相册还旧）：有效集号按位置算，位置 6 的成员不该倒退到它
        val oldSingle = video(key = "old2", caption = "某剧 第2集", date = base - 7200, messageId = 800L)
        val items = album + listOf(oldSingle)
        val hop = episodeHop(items, "alb_6")
        assertNull(hop) // 相册链尾：第6名成员之后没有编号更大的目标（old2 的 2 < 位置 6）
    }

    @Test
    fun `缺集跳号连播`() {
        // Feed 里只有 E1 和 E3（E2 被过滤/未加载）：E1 播完直接连 E3
        val items = series(count = 3).filterNot { it.key.endsWith("_2") }
        val hop = episodeHop(items, "s-100_1")!!
        assertEquals("s-100_3", hop.to.key)
    }

    // ---------------- R221：候选封顶（CHAIN_CANDIDATE_CAP，ANR 防护） ----------------

    @Test
    fun `R221 候选恰在上限不截断`() {
        // 上限边界：恰好 CAP 条时全员进链（保新弃旧只在超过时发生）
        val items = series(count = CHAIN_CANDIDATE_CAP)
        assertEquals(CHAIN_CANDIDATE_CAP, episodeChains(items).single().episodes.size)
    }

    @Test
    fun `R221 候选超上限保新弃旧`() {
        // R212 无限滚动把已加载集喂到数千条后，聚类的平方级路径（锚两两/相册块两两）
        // 是小米 00:26 ANR 实锤主线程风暴点——封顶后最老的多余条目以散格呈现（不进链），
        // 最新 CAP 条链语义不变
        val extra = 60
        val items = series(count = CHAIN_CANDIDATE_CAP + extra) // Feed 序：新→旧
        val chains = episodeChains(items)
        assertEquals(1, chains.size) // 同剧同锚+时间邻接：仍是一条链
        assertEquals(CHAIN_CANDIDATE_CAP, chains[0].episodes.size) // 恰封顶
        val inChain = chains[0].episodes.map { it.item.key }.toSet()
        assertTrue("最新一条必须在链里", inChain.contains(items.first().key))
        // 最老 extra 条被截掉（散格呈现，不折叠进追剧卡）
        items.takeLast(extra).forEach {
            assertFalse("最老条目应被截掉: ${it.key}", inChain.contains(it.key))
        }
    }
}
