package dev.tgfeed.ui

import dev.tgfeed.core.feed.SearchSortOrder
import dev.tgfeed.core.feed.applySearchSortOrder
import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R371 搜索页两件 wiring 源码锚+排序器行为（R367 同款 user.dir 源定位模式）。
 *
 * 判词：
 *  - ①返回重搜根因=容器令牌 pendingSearchQuery 与搜索页实际提交词脱钩（全仓唯一写
 *    入方是 openSearch 入口；页内打词/改词从不回写）——返回键消费器
 *    openSearch(令牌)→initialQuery 拿到空串/旧词：空串=整页重置建议面板（用户
 *    10:4x 实锤「预览完返回他会重新搜索」）、旧词=顶回旧词且 R107/H5「结果属于
 *    本词」判定失守→真空开搜分支真开火（旧词重扇出+liveAsk 重发）。修法=doSearch/
 *    submitSearch 提交点回写令牌+页内清态镜像清零（令牌恒镜像已提交词）。
 *  - ②多维度排序（用户「业界该有的能力咱们都得具备」）：四档单源件
 *    core/feed/SearchSort.kt；默认档 RELEVANCE=现状混排**原样不动**（负锚）；
 *    纯客户端零新网络；档位 AppPrefs.searchSortOrder 跨会话记忆（缺省 RELEVANCE）。
 */
class R371WiringSourceTest {

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

    private fun searchScreen(): String = src("dev/tgfeed/ui/SearchScreen.kt")

    /** 测试用最小条目（dateEpochSec/durationSec/chatId 是排序域字段） */
    private fun item(key: String, chatId: Long, date: Int, dur: Int) = VideoItem(
        key = key, chatId = chatId, messageId = 0, channelTitle = "c", caption = "",
        durationSec = dur, width = 0, height = 0, fileId = 0, sizeBytes = 0, playUri = null,
        dateEpochSec = date,
    )

    // ---------------- ② 排序：单源与默认档负锚 ----------------

    /**
     * 排序档枚举单源（SearchSort.kt 唯一定义）+默认档负锚（RELEVANCE 分支原样返回
     * 不重排）+反解兜底（未知值回落默认档）+AppPrefs 键单源缺省 RELEVANCE。
     */
    @Test
    fun sort_single_source_enum_and_default_anchor() {
        val sortFile = src("dev/tgfeed/core/feed/SearchSort.kt")
        val defs = Regex("enum class SearchSortOrder").findAll(sortFile).toList()
        assertTrue("SearchSortOrder 枚举必须唯一出处（单源），实到 ${defs.size} 处", defs.size == 1)
        for (name in listOf("RELEVANCE", "NEWEST", "CHANNEL_HEAT", "DURATION")) {
            assertTrue("四档应含 $name", Regex("$name\\(").containsMatchIn(sortFile))
        }
        // 默认档负锚：RELEVANCE 分支=原样返回（谁在默认档偷加排序键此锚即红）
        assertTrue(
            "RELEVANCE 分支应原样返回 items（默认档不改现状混排）",
            Regex("SearchSortOrder\\.RELEVANCE -> items").containsMatchIn(sortFile),
        )
        // 反解兜底：未知/空值回落默认档
        assertEquals(SearchSortOrder.RELEVANCE, SearchSortOrder.fromName("garbage"))
        assertEquals(SearchSortOrder.RELEVANCE, SearchSortOrder.fromName(null))
        assertEquals(SearchSortOrder.NEWEST, SearchSortOrder.fromName("NEWEST"))
        // 持久化键单源+缺省值（AppPrefs.searchSortOrder 属性唯一出处——getter/setter
        // 成对共用一个键串，属性声明唯一即单源；缺省 RELEVANCE）
        val prefs = src("dev/tgfeed/core/prefs/AppPrefs.kt")
        val prefProps = Regex("var searchSortOrder\\b").findAll(prefs).toList()
        assertTrue("searchSortOrder 属性应唯一声明（单源），实到 ${prefProps.size} 处", prefProps.size == 1)
        val prefKeys = Regex("\"search_sort_order\"").findAll(prefs).toList()
        assertTrue("键串应只在 getter/setter 成对出现（实到 ${prefKeys.size} 处）", prefKeys.size == 2)
        assertTrue(
            "缺省档应为 RELEVANCE（默认=现状混排）",
            Regex("getString\\(\"search_sort_order\", \"RELEVANCE\"\\)").containsMatchIn(prefs),
        )
    }

    /** 行为锚：默认档不重排——顺序与实例逐一原样（本地在前混排不被破坏）。 */
    @Test
    fun sort_default_keeps_merged_order() {
        val input = listOf(
            item("a", 1, 100, 30) to true,
            item("b", 2, 200, 0) to false,
            item("c", 3, 0, 60) to false,
        )
        val out = applySearchSortOrder(input, SearchSortOrder.RELEVANCE)
        assertEquals(3, out.size)
        input.forEachIndexed { i, pair ->
            assertSame("默认档第 $i 位应原实例原序", pair.first, out[i].first)
            assertEquals(pair.second, out[i].second)
        }
    }

    /** 行为锚：三维度降序+未知沉底+并列保混排原序（稳定排序）。 */
    @Test
    fun sort_dimensions_desc_and_unknown_sink() {
        val input = listOf(
            item("a", 1, 100, 30) to true,
            item("b", 2, 200, 0) to false,
            item("c", 3, 0, 60) to false,
        )
        // 最新：dateEpochSec 降序，0（未知/老收藏）沉底（b=200 > a=100 > c=0）
        assertEquals(
            listOf("b", "a", "c"),
            applySearchSortOrder(input, SearchSortOrder.NEWEST).map { it.first.key },
        )
        // 频道热度：成员数降序，不在表（未知）沉底
        val heat = applySearchSortOrder(input, SearchSortOrder.CHANNEL_HEAT, mapOf(1L to 50, 3L to 10))
        assertEquals(listOf("a", "c", "b"), heat.map { it.first.key })
        // 频道热度无表=全未知=原序保持（诚实降级不乱动）
        assertEquals(
            listOf("a", "b", "c"),
            applySearchSortOrder(input, SearchSortOrder.CHANNEL_HEAT).map { it.first.key },
        )
        // 时长：durationSec 降序，0（图片/未知）沉底
        assertEquals(
            listOf("c", "a", "b"),
            applySearchSortOrder(input, SearchSortOrder.DURATION).map { it.first.key },
        )
        // 并列保原序（稳定排序：b/c 同热度档并列时不互换）
        val tie = applySearchSortOrder(input, SearchSortOrder.CHANNEL_HEAT, mapOf(2L to 50, 3L to 50))
        assertEquals(listOf("b", "c", "a"), tie.map { it.first.key })
    }

    // ---------------- ① 返回不重搜：令牌回写接线 ----------------

    /**
     * 令牌回写三锚：doSearch 提交点（lastSearched=q 之后）+submitSearch 提交点+
     * 页内清态镜像清零——令牌恒镜像页面已提交词，返回消费器 openSearch(令牌) 恒恢复
     * 本词（R107/H5「结果属于本词」判定恒命中=零重搜）。
     */
    @Test
    fun return_no_research_token_sync_wiring() {
        val text = searchScreen()
        // doSearch：回写在 lastSearched = q 之后（时序锚——先记词后回写令牌）
        val doSearchBody = text.substring(text.indexOf("val doSearch: (String) -> Unit"))
        val ls1 = doSearchBody.indexOf("lastSearched = q")
        val sync1 = doSearchBody.indexOf("appContainer.pendingSearchQuery.value = q")
        assertTrue("doSearch 提交点应回写令牌", sync1 > 0)
        assertTrue("doSearch 令牌回写应在 lastSearched 置词之后", ls1 in 0 until sync1)
        // submitSearch：同样回写（第二提交点）
        val submitBody = text.substring(text.indexOf("val submitSearch: (String) -> Unit"))
        assertTrue(
            "submitSearch 提交点应回写令牌",
            submitBody.indexOf("appContainer.pendingSearchQuery.value = q") > 0,
        )
        // 页内清态：BackHandler 清回初始态时镜像清零
        val backBody = text.substring(text.indexOf("androidx.activity.compose.BackHandler"))
        assertTrue(
            "页内清态应镜像清零令牌",
            backBody.substring(0, backBody.indexOf("onBack()")).contains("appContainer.pendingSearchQuery.value = \"\""),
        )
        // 消费器仍读令牌（MainActivity 返回消费——令牌回写修的就是这条链的供给）
        val main = src("dev/tgfeed/MainActivity.kt")
        assertTrue(
            "返回消费器应仍走 openSearch(pendingSearchQuery)",
            main.contains("container.openSearch(container.pendingSearchQuery.value)"),
        )
    }

    /**
     * 返回恢复分支零重搜锚：resultsBelongToInitial=true 的 else 分支内零 doSearch/
     * liveAskSearch 调用（只补跑本地层）+判定式不再要求结果非空（R371 同族洞收口：
     * 0 命中/在飞会话的词返回时不再当新词重扇出）+R371 探针行在场（与
     * FeedSearchPart 的「R371 全量搜索入口」入口行互为反证对）。
     */
    @Test
    fun return_restore_branch_zero_refire_anchor() {
        val text = searchScreen()
        val effectStart = text.indexOf("LaunchedEffect(Unit) {")
        assertTrue("进页 LaunchedEffect(Unit) 应在场", effectStart > 0)
        val guardDef = text.indexOf("val resultsBelongToInitial")
        val guardEnd = text.indexOf("\n", text.indexOf("vm.fullSearchQuery == initialQuery.trim()", guardDef))
        val guardLine = text.substring(guardDef, guardEnd)
        assertTrue(
            "返回页判定应只比会话关联词（R371 同族洞收口：isNotEmpty 赘条件已拆——0 命中词返回不再重扇出）",
            guardLine.contains("vm.fullSearchQuery == initialQuery.trim()") &&
                !guardLine.contains("isNotEmpty"),
        )
        val elseStart = text.indexOf("} else {", guardDef)
        assertTrue("返回恢复 else 分支应在场", elseStart > 0)
        // remember 初值同口径（第二现场：防抖 effect 不把返回词当新词重扇出）
        val rememberInit = text.substring(
            text.indexOf("var lastSearched by remember"),
            text.indexOf("// 金矿 P3"),
        )
        assertTrue(
            "lastSearched 初值判定应同口径只比会话关联词",
            rememberInit.contains("vm.fullSearchQuery == initialQuery.trim()") &&
                !rememberInit.contains("isNotEmpty"),
        )
        val elseEnd = text.indexOf("LaunchedEffect(query)", elseStart)
        val restoreBranch = text.substring(elseStart, elseEnd)
        assertTrue(
            "返回恢复分支应挂 R371 探针行（词长口径零内容）",
            restoreBranch.contains("R371 返回恢复零重搜"),
        )
        assertTrue(
            "返回恢复分支零 doSearch（不重扇出）",
            !restoreBranch.contains("doSearch("),
        )
        assertTrue(
            "返回恢复分支零 liveAskSearch（不重问搜索实体=quota 零浪费）",
            !restoreBranch.contains("liveAskSearch("),
        )
        // fullSearch 入口探针（FeedSearchPart）在场——logcat 反证锚
        val searchPart = src("dev/tgfeed/core/feed/FeedSearchPart.kt")
        assertTrue(
            "fullSearch 入口应挂 R371 探针行",
            searchPart.contains("R371 全量搜索入口"),
        )
    }
}
