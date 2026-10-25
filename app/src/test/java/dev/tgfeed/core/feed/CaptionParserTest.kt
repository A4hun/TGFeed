package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R228 CaptionParser 单测（任务书 A 层验收：**30+ 条**覆盖用户描述的各种发帖习惯）。
 *
 * 用户口径（任务书原话）：TG 频道发帖习惯多样——相册 6 张图配一段 caption，有的行带
 * #标签，有的纯文字，有的混合，中间空格/空行分割。逐行匹配（第 N 行→成员 N）+
 * tag 结构化（相册级+条目级）都要；段数≠成员数回退（R219 兼容）。
 *
 * 纯函数零依赖：直接构造 caption 字符串 + [CaptionAlbumContext] 断言
 * [CaptionParseResult] 各字段。
 */
class CaptionParserTest {

    private fun parse(caption: String, members: Int, index: Int): CaptionParseResult =
        CaptionParser.parse(caption, CaptionAlbumContext(members, index))

    // ---------------- ① 逐行精确匹配（用户核心诉求：第 N 行→成员 N） ----------------

    @Test
    fun `六行六成员_第N行归成员N_混合tag`() {
        val cap = """
            舞出精彩A期 #舞团
            舞出精彩B期
            舞出精彩C期 #精选 #舞团
            舞出精彩D期
            舞出精彩E期 #演出
            舞出精彩F期
        """.trimIndent()
        val r3 = parse(cap, 6, 2)
        assertEquals("舞出精彩C期", r3.perItemTitle)
        assertEquals(listOf("精选", "舞团"), r3.perItemTags)
        assertNull(r3.albumTitle)
        val r1 = parse(cap, 6, 0)
        assertEquals("舞出精彩A期", r1.perItemTitle)
        assertEquals(listOf("舞团"), r1.perItemTags)
        val r6 = parse(cap, 6, 5)
        assertEquals("舞出精彩F期", r6.perItemTitle)
        assertTrue(r6.perItemTags.isEmpty())
    }

    @Test
    fun `空行分段与逐行分段同结果`() {
        val cap = "一号线\n\n二号线\n\n三号线"
        val names = listOf("一号线", "二号线", "三号线")
        for (i in 0 until 3) {
            assertEquals(names[i], parse(cap, 3, i).perItemTitle)
        }
    }

    @Test
    fun `无空行的六行也逐行匹配`() {
        val cap = "甲\n乙\n丙\n丁\n戊\n己"
        assertEquals("戊", parse(cap, 6, 4).perItemTitle)
    }

    @Test
    fun `行内混合文字与标签_标题剥离标签`() {
        val r = parse("看#电影 合集", 1, 0)
        assertEquals("看 合集", r.perItemTitle)
        assertEquals(listOf("电影"), r.perItemTags)
    }

    @Test
    fun `成员标签不泄漏到其他成员`() {
        val cap = "一号 #t1\n二号\n三号 #t3"
        assertTrue(parse(cap, 3, 1).perItemTags.isEmpty())
        assertEquals(listOf("t3"), parse(cap, 3, 2).perItemTags)
    }

    // ---------------- ② 序号模式（「第N张/N./①」——强化匹配置信度） ----------------

    @Test
    fun `第N张序号_按序号值映射`() {
        val cap = "第1张 开场舞\n第2张 独舞\n第3张 群舞"
        assertEquals("独舞", parse(cap, 3, 1).perItemTitle)
    }

    @Test
    fun `第N张中文数字`() {
        val cap = "第一张 开场\n第二张 独舞\n第三张 群舞\n第四张 谢幕"
        assertEquals("群舞", parse(cap, 4, 2).perItemTitle)
    }

    @Test
    fun `数字点号序号`() {
        val cap = "1. 开场\n2. 独舞\n3. 群舞"
        assertEquals("独舞", parse(cap, 3, 1).perItemTitle)
    }

    @Test
    fun `顿号序号`() {
        val cap = "1、开场\n2、独舞\n3、群舞"
        assertEquals("群舞", parse(cap, 3, 2).perItemTitle)
    }

    @Test
    fun `圆圈数字①②③`() {
        val cap = "① 开场\n② 独舞\n③ 群舞"
        assertEquals("独舞", parse(cap, 3, 1).perItemTitle)
    }

    @Test
    fun `括号与方括号序号`() {
        assertEquals("独舞", parse("(1) 开场\n(2) 独舞\n(3) 群舞", 3, 1).perItemTitle)
        assertEquals("群舞", parse("【1】开场\n【2】独舞\n【3】群舞", 3, 2).perItemTitle)
    }

    @Test
    fun `序号乱序_按值不按位置`() {
        val cap = "第3张 群舞\n第1张 开场\n第2张 独舞"
        assertEquals("开场", parse(cap, 3, 0).perItemTitle)
        assertEquals("独舞", parse(cap, 3, 1).perItemTitle)
        assertEquals("群舞", parse(cap, 3, 2).perItemTitle)
    }

    @Test
    fun `页眉加六序号行_页眉归相册级`() {
        val cap = "本周精选合集 #精选\n第1张 A\n第2张 B\n第3张 C\n第4张 D\n第5张 E\n第6张 F"
        val r = parse(cap, 6, 3)
        assertEquals("D", r.perItemTitle)
        assertEquals("本周精选合集", r.albumTitle)
        assertEquals(listOf("精选"), r.albumTags)
    }

    @Test
    fun `序号越界段归相册级`() {
        // 6 成员但出现「第8张」——越界段不点名任何成员，归 leftovers
        val cap = "第1张 A\n第2张 B\n第3张 C\n第4张 D\n第5张 E\n第6张 F\n第8张 花絮"
        val r = parse(cap, 6, 0)
        assertEquals("A", r.perItemTitle)
        assertEquals("花絮", r.albumTitle)
    }

    @Test
    fun `散号不触发序号模式_回退`() {
        // 只有两个有效序号但成员 5 个（覆盖 40% < 60%）→ 序号模式拒绝 → 段数 5=5 精确计数
        // ——「1. xxx」「2. yyy」资源清单式散号场景（无相册语义时宁可走位置或回退）
        val cap = "1. 资源甲\n2. 资源乙\n简介丙\n简介丁\n简介戊"
        val r = parse(cap, 5, 0)
        // 精确计数模式命中（5 文字段=5 成员）：标题仍剥掉行号噪音
        assertEquals("资源甲", r.perItemTitle)
    }

    @Test
    fun `单条目序号不触发_标题剥行号`() {
        // memberCount=1：distinct<2 不可能进序号模式；单段=单成员 → 标题剥「1. 」
        val r = parse("1. 完整版资源", 1, 0)
        assertEquals("完整版资源", r.perItemTitle)
    }

    // ---------------- ③ 回退模式（段数≠成员数——R219 兼容红线） ----------------

    @Test
    fun `R219真实形态_三段五成员_整段回退`() {
        val cap = "舞出精彩 美玥 1期\n\n资源简介：\n美玥 1期\n\n#美玥 #舞团"
        val r = parse(cap, 5, 2)
        assertNull(r.perItemTitle)
        assertEquals("舞出精彩 美玥 1期", r.albumTitle)
        assertEquals(setOf("美玥", "舞团"), r.albumTags.toSet())
        // item_tags 落库口径：全文所有标签（tag 查询能命中回退相册）
        assertEquals(setOf("美玥", "舞团"), r.storedTags().toSet())
    }

    @Test
    fun `段数多于成员数_回退`() {
        val cap = "一\n二\n三\n四\n五\n六\n七"
        assertNull(parse(cap, 3, 0).perItemTitle)
    }

    @Test
    fun `段数少于成员数_回退`() {
        val cap = "一\n二"
        assertNull(parse(cap, 6, 0).perItemTitle)
        assertEquals("一", parse(cap, 6, 0).albumTitle)
    }

    @Test
    fun `纯标签段不占成员位_三段三成员不误逐行`() {
        // 「标题/简介/#标签」三段式 + 恰好 3 成员：标签段剔出计数后只剩 2 文字段≠3 → 回退
        val cap = "标题甲\n简介乙\n#tag"
        val r = parse(cap, 3, 1)
        assertNull(r.perItemTitle)
        assertEquals(listOf("tag"), r.albumTags)
    }

    @Test
    fun `六成员六行加尾部标签行_标签行归相册级`() {
        val cap = "A\nB\nC\nD\nE\nF\n#合集 #精选"
        val r = parse(cap, 6, 5)
        assertEquals("F", r.perItemTitle)
        assertEquals(setOf("合集", "精选"), r.albumTags.toSet())
        assertNull(r.albumTitle)
    }

    // ---------------- ④ 单条目（非相册）口径 ----------------

    @Test
    fun `单行单条_标题=整行`() {
        val r = parse("舞出精彩 美玥 第1期", 1, 0)
        assertEquals("舞出精彩 美玥 第1期", r.perItemTitle)
    }

    @Test
    fun `多行单条_回退NULL_消费方回退caption首行`() {
        val r = parse("标题\n简介正文\n#tag", 1, 0)
        assertNull(r.perItemTitle)
        assertEquals(listOf("tag"), r.storedTags())
    }

    @Test
    fun `空行分段的多行单条_同样回退防摊平`() {
        // 单成员保守闸：1 段=1 成员会把多行文案摊平成标题（换行丢失）——空行分段也拦
        val r = parse("标题行\n\n简介段\n\n#标签段", 1, 0)
        assertNull(r.perItemTitle)
        assertEquals("标题行", r.albumTitle)
    }

    @Test
    fun `空caption_零结果`() {
        val r = parse("", 3, 0)
        assertNull(r.perItemTitle)
        assertTrue(r.storedTags().isEmpty())
        assertNull(r.albumTitle)
    }

    @Test
    fun `纯空白行caption_零结果`() {
        val r = parse("  \n \n", 2, 0)
        assertNull(r.albumTitle)
    }

    @Test
    fun `越界memberIndex_零结果`() {
        assertNull(parse("甲\n乙", 2, 2).perItemTitle)
        assertNull(parse("甲\n乙", 2, -1).albumTitle)
    }

    @Test
    fun `ctx为null_按单条口径`() {
        val r = CaptionParser.parse("单行标题", null)
        assertEquals("单行标题", r.perItemTitle)
    }

    // ---------------- ⑤ 标签提取（结构化 tag） ----------------

    @Test
    fun `全角＃同认`() {
        assertEquals(listOf("电影"), CaptionParser.extractHashtags("＃电影 合集"))
    }

    @Test
    fun `标签词身只认字母数字下划线`() {
        // 「#电影-推荐」：连字符截断词身 → 标签=电影（与 hashtagHits 词边界同口径）
        assertEquals(listOf("电影"), CaptionParser.extractHashtags("#电影-推荐"))
    }

    @Test
    fun `下划线标签完整提取`() {
        assertEquals(listOf("top_10"), CaptionParser.extractHashtags("看 #top_10 榜单"))
    }

    @Test
    fun `跨行标签全部提取`() {
        assertEquals(setOf("a", "b", "c"), CaptionParser.extractHashtags("#a\n#b\n\n#c").toSet())
    }

    @Test
    fun `重复标签去重保首次原形`() {
        assertEquals(listOf("美玥"), CaptionParser.extractHashtags("#美玥 #美玥 #美玥"))
    }

    @Test
    fun `无标签_空表`() {
        assertTrue(CaptionParser.extractHashtags("纯文字简介：无任何话题标记").isEmpty())
    }

    @Test
    fun `段中嵌入与段尾标签都提取`() {
        val cap = "看#第一 回顾\n纯文字行\n结尾 #第二"
        val r = parse(cap, 3, 0)
        assertEquals(listOf("第一"), r.perItemTags)
        assertEquals(listOf("第二"), parse(cap, 3, 2).perItemTags)
    }

    @Test
    fun `storedTags_条目级与相册级并集去重`() {
        val cap = "本周合集 #合集\n第1张 A #甲\n第2张 B #乙"
        val r = parse(cap, 2, 0)
        assertEquals(listOf("甲", "合集"), r.storedTags())
        assertEquals(listOf("乙", "合集"), parse(cap, 2, 1).storedTags())
    }

    // ---------------- ⑥ 边界与保守闸 ----------------

    @Test
    fun `纯emoji行成标题保留`() {
        val cap = "🈯 第一\n🈯 第二"
        assertEquals("🈯 第一", parse(cap, 2, 0).perItemTitle)
    }

    @Test
    fun `纯标签段被点名_标题空但标签归该成员`() {
        // 「3. #花絮」：成员 3 的段是纯标签——标题 null（回退），标签归成员 3
        val cap = "第1张 A\n第2张 B\n3. #花絮"
        val r = parse(cap, 3, 2)
        assertNull(r.perItemTitle)
        assertEquals(listOf("花絮"), r.perItemTags)
    }

    @Test
    fun `四位数不当地当序号`() {
        // 「2023.」不触发 N. 标记（1~2 位限制）——年份/价格防误伤（EpisodeMark 同哲学）
        val cap = "2023.05 合集一\n2023.06 合集二"
        assertEquals("2023.05 合集一", parse(cap, 2, 0).perItemTitle)
    }

    @Test
    fun `多行段join成单标题`() {
        // 两行一段（无空行分割的段内换行）＝一个成员的完整描述
        val cap = "成员一\n描述甲\n\n成员二\n描述乙"
        assertEquals("成员一 描述甲", parse(cap, 2, 0).perItemTitle)
        assertEquals("成员二 描述乙", parse(cap, 2, 1).perItemTitle)
    }

    @Test
    fun `段落式精确匹配_空行分三段三成员`() {
        val cap = "第一段标题\n\n第二段标题\n\n第三段标题"
        assertEquals("第二段标题", parse(cap, 3, 1).perItemTitle)
    }

    @Test
    fun `跨页自愈语义_memberCount变化重解析`() {
        // 4 成员时（首批入库）逐行不成立 → 回退；6 成员到齐后重解析 → 逐条
        val cap = "A\nB\nC\nD\nE\nF"
        assertNull(parse(cap, 4, 0).perItemTitle)
        assertEquals("A", parse(cap, 6, 0).perItemTitle)
        assertEquals("E", parse(cap, 6, 4).perItemTitle)
    }

    @Test
    fun `成员序边界_首与尾`() {
        val cap = "A\nB\nC"
        assertEquals("A", parse(cap, 3, 0).perItemTitle)
        assertEquals("C", parse(cap, 3, 2).perItemTitle)
    }

    @Test
    fun `序号段部分点名_未点名成员标题空回退`() {
        // 6 成员 6 段：4 个序号点名（4 ≥ ceil(60%×6)=4 置信闸过）+2 个未点名段归相册级；
        // 成员 5/6 无段 → 标题 null（消费方回退 caption 首行）、标签只吃相册级
        val cap = "第1张 A #甲\n第2张 B\n第3张 C\n第4张 D\n花絮E\n花絮F #合集"
        assertEquals("A", parse(cap, 6, 0).perItemTitle)
        val r5 = parse(cap, 6, 4)
        assertNull(r5.perItemTitle)
        assertEquals(setOf("合集"), r5.storedTags().toSet())
        assertEquals("花絮E", r5.albumTitle)
    }

    @Test
    fun `P页码式序号`() {
        val cap = "P1 封面\nP2 内页一\nP3 内页二"
        assertEquals("内页一", parse(cap, 3, 1).perItemTitle)
    }

    @Test
    fun `两成员两行最小相册`() {
        val cap = "左图 #左\n右图 #右"
        assertEquals(listOf("左"), parse(cap, 2, 0).perItemTags)
        assertEquals(listOf("右"), parse(cap, 2, 1).perItemTags)
    }

    @Test
    fun `回退模式_纯文字无标签`() {
        val r = parse("一段\n两段\n三段", 2, 0)
        assertNull(r.perItemTitle)
        assertTrue(r.albumTags.isEmpty())
        assertEquals("一段", r.albumTitle)
    }

    // ---------------- ⑦ titleLine 共享件（D 层：parsed_title 优先，NULL 回退首行） ----------------

    @Test
    fun `titleLine_parsedTitle优先`() {
        val item = dev.tgfeed.core.td.VideoItem(
            key = "k", chatId = 1L, messageId = 1L, channelTitle = "c",
            caption = "完整原文第一行\n第二行", durationSec = 0, width = 0, height = 0,
            fileId = 0, sizeBytes = 0, playUri = null,
            parsedTitle = "逐条解析标题",
        )
        assertEquals("逐条解析标题", item.titleLine())
    }

    @Test
    fun `titleLine_NULL回退caption首个非空行`() {
        val item = dev.tgfeed.core.td.VideoItem(
            key = "k", chatId = 1L, messageId = 1L, channelTitle = "c",
            caption = "\n  \n首行正文\n第二行", durationSec = 0, width = 0, height = 0,
            fileId = 0, sizeBytes = 0, playUri = null,
        )
        assertEquals("首行正文", item.titleLine())
    }

    @Test
    fun `titleLine_空白parsedTitle也回退`() {
        val item = dev.tgfeed.core.td.VideoItem(
            key = "k", chatId = 1L, messageId = 1L, channelTitle = "c",
            caption = "标题", durationSec = 0, width = 0, height = 0,
            fileId = 0, sizeBytes = 0, playUri = null, parsedTitle = "   ",
        )
        assertEquals("标题", item.titleLine())
    }
}
