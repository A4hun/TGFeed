package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R372 「群友正在搜」雷达 wiring 源码锚（R361-R367 同款 user.dir 源定位模式）。
 *
 * 根因判词（用户 10:5x 拍板「功能有但弄得并不是很好」）：
 *  - 断流不上屏：收割 fetchRawMessages 裸 send 无超时（R366 三期漏网第 4 处）挂起+
 *    共享预算池被辅助管线吃光 → 热词 24h 定窗空 → 发现页热词区整区隐藏（数据在库
 *    睡觉：16384 实勘 48h 留存内 377 活词 407 事件一滴滴不出）；
 *  - 词-频道割裂：用户要的「群友正在搜 XXX，返回了 YYY 频道」关联呈现不存在——
 *    热词行只有词、「刚冒出来的」只有频道、候选卡来源词行被金矿丰度排序稀释；
 *  - 窗口死板：24h 统计窗只用 48h 留存一半，断流一天即全黑。
 * 修法四件：①fetchRawMessages 超时兜底（20s 拉历史档）；②热词窗 24h→48h 满留存
 * 档（统计胶囊仍 24h 今日口径，两窗分轨）；③雷达频道卡（热词头部词→答案库返回
 * 频道，hits/lastTs/members 证据行，诚实门三闸+非频道闸）；④聚合器纯本地零网络
 * （零 SendMessage 零搜索族调用点——纯被动红线，用户核心前提「不碰限流」）。
 */
class R372WiringSourceTest {

    private val mainSourceRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = File(dir, "src/main/java")
                if (candidate.isDirectory) return candidate
                val nested = File(dir, "app/src/main/java")
                if (nested.isDirectory) return nested
                dir = dir.parentFile ?: return@repeat
            }
            error("找不到主源码根（user.dir=${System.getProperty("user.dir")}）")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun radarPart(): String = src("dev/tgfeed/core/feed/FeedDiscoveryRadarPart.kt")

    private fun discoverScreen(): String = src("dev/tgfeed/ui/DiscoverScreen.kt")

    /** DiscoverScreen 雷达段切片（「R372 雷达频道卡行」注释起至下一区段注释） */
    private fun radarSectionSlice(text: String): String {
        val start = text.indexOf("R372 雷达频道卡行")
        assertTrue("雷达段应在场（DiscoverScreen）", start >= 0)
        val end = text.indexOf("刚冒出来的\"横滑区", start)
        return if (end > start) text.substring(start, end) else text.substring(start)
    }

    /** DiscoverRadarCard 组件体切片（声明起至下一顶层 private composable/函数） */
    private fun radarCardBody(text: String): String {
        val start = text.indexOf("private fun DiscoverRadarCard")
        assertTrue("DiscoverRadarCard 组件应在场", start >= 0)
        val rest = text.substring(start)
        val next = rest.indexOf("\nprivate fun", 10)
        return if (next > 0) rest.substring(0, next) else rest
    }

    /**
     * ①收割页拉取超时兜底：fetchRawMessages 必须 withTdTimeout 包裹（20s 拉历史档），
     * what 标签含 GetChatHistory（挂起证据行可读）——裸 send 是收割轮卡死的根因位。
     */
    @Test
    fun fetch_raw_messages_timeout_wrapped() {
        val text = src("dev/tgfeed/core/td/TdLibGateway.kt")
        val fnStart = text.indexOf("suspend fun fetchRawMessages")
        assertTrue("fetchRawMessages 应在场", fnStart >= 0)
        val body = text.substring(fnStart, fnStart + 900)
        assertTrue("fetchRawMessages 应裹 withTdTimeout", body.contains("withTdTimeout("))
        assertTrue("超时档应为拉历史放宽档（PLAZA_TD_HISTORY_TIMEOUT_MS）", body.contains("PLAZA_TD_HISTORY_TIMEOUT_MS"))
        assertTrue("what 标签应含 GetChatHistory（证据行可读）", body.contains("GetChatHistory"))
        // 超时 null 回落走既有失败语义（不推进游标），runCatching 既有壳保留
        assertTrue("失败语义壳应在场（runCatching+getOrNull）", body.contains("getOrNull()"))
    }

    /**
     * ②雷达窗 48h 单源 + 统计窗分轨：窗常量唯一出处（聚合器文件）；refreshDiscovery
     * 词行读雷达窗、统计胶囊仍读 24h 今日口径——两窗分轨各说各话，不许镜像。
     */
    @Test
    fun radar_window_single_source_and_stats_track_split() {
        val part = radarPart()
        // R372b 修：常量按 48h 满档表达式书写（48L*3600*1000），旧锚 (\\d+)L 只捕获到
        // 表达式头部的「48」≠48h 毫秒值=写法假红——改整行锚（表达式逐字=单源+值双证）
        val decls = Regex("internal const val DISCOVERY_RADAR_WORD_WINDOW_MS[^\\n]*").findAll(part).toList()
        assertEquals("DISCOVERY_RADAR_WORD_WINDOW_MS 必须唯一出处（单源）", 1, decls.size)
        assertEquals(
            "雷达窗必须 48h（GoldmineHooks 留存窗满档）",
            "internal const val DISCOVERY_RADAR_WORD_WINDOW_MS = 48L * 3600 * 1000",
            decls.first().value.trim(),
        )
        val kdoc = part.substring(part.indexOf("DISCOVERY_RADAR_WORD_WINDOW_MS") - 900, part.indexOf("DISCOVERY_RADAR_WORD_WINDOW_MS"))
        assertTrue("窗常量 KDoc 应记 R372 判词（48h 满留存档）", kdoc.contains("R372"))

        val feed = src("dev/tgfeed/core/feed/FeedDiscoveryPart.kt")
        val radarRead = feed.indexOf("GoldmineHooks.hotKeywords(DISCOVERY_RADAR_WORD_WINDOW_MS")
        val statsRead = feed.indexOf("GoldmineHooks.hotKeywords(DISCOVERY_HOT_WORD_WINDOW_MS")
        assertTrue("词行应读雷达窗（48h）", radarRead > 0)
        assertTrue("统计胶囊应仍读今日窗（24h 分轨不许镜像）", statsRead > 0)
        // 词行发布=雷达窗读数（hotAll 不得再直供词行）
        val wordsPublish = feed.indexOf("_discoveryHotWords.value = radarHot.take(")
        assertTrue("词行发布应取雷达窗读数", wordsPublish > radarRead && wordsPublish < radarRead + 400)
        // 雷达卡发布与词行同批（同一刷新链）
        assertTrue("雷达卡应在词行同批发布", feed.indexOf("_discoveryRadarCards.value = buildRadarCards(") > wordsPublish)
    }

    /**
     * ③纯被动负锚：聚合器文件 + DiscoverScreen 雷达两切片零 SendMessage 零搜索族
     * 调用点（SearchPublicChat/searchChatsOnServer/SearchPublicPosts/client.send）——
     * 用户核心前提「纯监控只读，不触发对频道的搜索（不碰限流）」的源码级红线。
     */
    @Test
    fun radar_passive_zero_send_and_zero_search_family() {
        val forbidden = listOf(
            "SendMessage", "SearchPublicChat", "searchChatsOnServer", "SearchPublicPosts", "client.send",
        )
        val part = radarPart()
        for (token in forbidden) {
            assertTrue("聚合器文件禁止出现 $token（纯被动红线）", !part.contains(token))
        }
        val screen = discoverScreen()
        val slices = listOf(radarSectionSlice(screen), radarCardBody(screen))
        for ((i, slice) in slices.withIndex()) {
            for (token in forbidden) {
                assertTrue("DiscoverScreen 雷达切片$i 禁止出现 $token（纯被动红线）", !slice.contains(token))
            }
        }
        // 卡点击链=试吃留痕+chatId 直开（本地身份链零搜索零写），不得引新解析口
        val section = radarSectionSlice(screen)
        assertTrue("卡点击应走 onOpenChannel(ChannelBrief)（chatId 直开）", section.contains("onOpenChannel(ChannelBrief("))
    }

    /**
     * ④诚实门三闸单源：chatId==0（未解析）/hintTitle 空白（无名不冒充）/已加入
     * （投诉2 同口径）三闸全部在聚合器单函数内；词空=空表（UI 整段隐藏的契约上游）；
     * UI 侧卡行只读 isNotEmpty 门（不许第二套过滤）。
     */
    @Test
    fun radar_honest_gates_single_source() {
        val part = radarPart()
        val fn = part.indexOf("internal fun FeedViewModel.buildRadarCards")
        assertTrue("buildRadarCards 聚合器应在场", fn >= 0)
        val body = part.substring(fn)
        val gate1 = body.indexOf("if (a.chatId == 0L) continue")
        val gate2 = body.indexOf("if (a.hintTitle.isBlank()) continue")
        val gate3 = body.indexOf("if (a.chatId in joinedNow) continue")
        val nonChannel = body.indexOf("if (a.chatId in nonChannelIds) continue")
        assertTrue("诚实门①（chatId==0 不推）应在场", gate1 > 0)
        assertTrue("诚实门②（hintTitle 空白不推）应在场", gate2 > 0)
        assertTrue("诚实门③（已加入不推·投诉2 同口径）应在场", gate3 > 0)
        assertTrue("R211 非频道闸应在场", nonChannel > 0)
        // 词空=空表（诚实空态上游契约）
        assertTrue("热词空应早退空表", body.contains("if (real == null || hotWords.isEmpty()) return emptyList()"))
        // UI 侧只读门（卡行 isNotEmpty），不得出现第二套 chatId/标题过滤
        val section = radarSectionSlice(discoverScreen())
        assertTrue("UI 卡行应只读 isNotEmpty 门", section.contains("if (radarCards.isNotEmpty())"))
        assertTrue("UI 侧不得第二套过滤（hintTitle/chatId 判定不进 UI 切片）", !section.contains("hintTitle.isBlank()"))
    }

    /**
     * ⑤雷达卡证据行单源：hits/lastTs/members/word 字段模型单处定义；「N 次命中」
     * 证据行文案唯一出处（DiscoverRadarCard）；时间档复用 freshAgeLabel 相对件
     * （不加第三份相对时间件，R204 口径）。
     */
    @Test
    fun radar_card_evidence_single_source() {
        val part = radarPart()
        assertTrue("RadarChannelCard 模型应含 hits/lastTs/members/word 证据字段",
            listOf("val hits: Int", "val lastTs: Long", "val members: Int", "val word: String").all { part.contains(it) })
        val screen = discoverScreen()
        val hitsLines = Regex("\\$\\{card.hits} 次命中").findAll(screen).toList()
        assertEquals("「N 次命中」证据行文案必须唯一出处（单源）", 1, hitsLines.size)
        val cardBody = radarCardBody(screen)
        assertTrue("证据行应复用 freshAgeLabel 相对档（不加第三份相对时间件）", cardBody.contains("freshAgeLabel(card.lastTs)"))
        assertTrue("来源词行应在卡上（搜「X」词→频道配对锚）", cardBody.contains("搜「\${card.word}」"))
        // 聚合器一词一卡上限常量单源
        val limits = Regex("internal const val DISCOVERY_RADAR_CARD_LIMIT = (\\d+)").findAll(part).toList()
        assertEquals("DISCOVERY_RADAR_CARD_LIMIT 必须唯一出处", 1, limits.size)
    }
}
