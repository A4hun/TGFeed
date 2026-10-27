package dev.tgfeed.ui

import dev.tgfeed.core.feed.channelTagItemsPageSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R408b 行星世界卡代表作动态装载：SQL 纯函数锚 + 共享件纯函数直测 + 源码接线锚。
 *  - SQL 锚：channelTagItemsPageSql 双表结构（FROM 不许误删，R269 同款）+ chat+tag 域谓词
 *    + messageId 倒序 + LIMIT/OFFSET 分页；
 *  - 纯函数：previewDimsOf（N 与半批口径）/mergePreviewBatch（去重+空批封窗+护栏封窗）；
 *  - 源锚：恒星信息卡与行星世界卡同走共享件；行星卡数据源=channelTagItemsPage，
 *    旧 take(6) 快照截断不复活；点播标签原样。
 */
class R408bWiringSourceTest {

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

    private fun screen(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")

    private fun index(): String = src("dev/tgfeed/core/feed/DeepSearchIndex.kt")

    /** 截取 PlanetWorldCard 函数体（到下一个顶层 @Composable 为止） */
    private fun planetCardBody(): String {
        val s = screen()
        val start = s.indexOf("private fun PlanetWorldCard(")
        assertTrue("PlanetWorldCard 必须存在", start >= 0)
        val end = s.indexOf("\n@Composable", start).let { if (it < 0) s.length else it }
        return s.substring(start, end)
    }

    @Test
    fun SQL形状含双表结构与频道tag域谓词() {
        val sql = channelTagItemsPageSql("items.a,items.b")
        assertTrue(sql.startsWith("SELECT items.a,items.b FROM tag_items CROSS JOIN items "))
        assertTrue(sql.contains("ON items.rowid = tag_items.rowid_ref"))
        assertTrue(sql.contains("WHERE tag_items.chatId=? AND tag_items.tag=?"))
        assertTrue(sql.contains("ORDER BY items.messageId DESC"))
        assertTrue(sql.endsWith("LIMIT ? OFFSET ?"))
        // 绑定位恰 4 个（chatId/tag/limit/offset——与调用侧 arrayOf 四元对位）
        assertEquals(4, sql.count { it == '?' })
    }

    @Test
    fun 查询口走纯函数并按四元绑定() {
        val s = index()
        assertTrue(s.contains("fun channelTagItemsPage(chatId: Long, tag: String, limit: Int, offset: Int): List<VideoItem>"))
        assertTrue(s.contains("channelTagItemsPageSql(ITEM_COLS_FROM_ITEMS)"))
        assertTrue(s.contains("arrayOf(chatId.toString(), key, limit.toString(), offset.toString())"))
    }

    @Test
    fun 可见数N与半批口径() {
        // 屏宽 400dp：(400-40-32)/92=3，半批 2，初载 5
        val d = previewDimsOf(400, 16)
        assertEquals(3, d.n)
        assertEquals(2, d.half)
        assertEquals(5, d.initialCount)
        // 极窄屏下限 2
        assertEquals(2, previewDimsOf(100, 16).n)
        // 宽屏 1200dp：(1200-72)/92=12，半批 6
        assertEquals(12, previewDimsOf(1200, 16).n)
        assertEquals(6, previewDimsOf(1200, 16).half)
    }

    @Test
    fun 懒载批合并去重与封窗() {
        val cur = listOf(1, 2, 3)
        val m = mergePreviewBatch(cur, listOf(3, 4, 5), 120) { it }
        assertEquals(listOf(1, 2, 3, 4, 5), m.items)
        assertEquals(2, m.freshCount)
        assertFalse(m.exhausted)
        // 空批=封窗
        val e = mergePreviewBatch(cur, emptyList(), 120) { it }
        assertTrue(e.exhausted)
        assertEquals(cur, e.items)
        // 全重=封窗
        assertTrue(mergePreviewBatch(cur, listOf(1, 2), 120) { it }.exhausted)
        // 护栏封顶=封窗
        val g = mergePreviewBatch(cur, listOf(4, 5), 5) { it }
        assertTrue(g.exhausted)
        assertEquals(5, g.items.size)
    }

    @Test
    fun 恒星信息卡收编共享件() {
        val s = screen()
        assertTrue(s.contains("val previewWin = remember { PreviewWindowState() }"))
        assertTrue(s.contains("val hourWin = remember { PreviewWindowState() }"))
        assertTrue(s.contains("val previewDims = rememberPreviewDims()"))
        assertTrue(s.contains("PreviewNearEndEffect(previewListState, previewHalf, st.chatId, previewWin.exhausted)"))
        assertTrue(s.contains("previewWin.appendBatch(previewHalf)"))
        assertTrue(s.contains("hourWin.appendBatch(previewHalf)"))
        // 旧私有三态变量不复活
        assertFalse(s.contains("var previewExhausted by"))
        assertFalse(s.contains("var hourItems by"))
        // 共享件内单份触发线/护栏
        assertEquals(1, Regex("""\(info\.totalItemsCount - 1 - lastFull\) < """).findAll(s).count())
        assertTrue(s.contains("if (offset <= 0 || offset >= PREVIEW_MAX)"))
    }

    @Test
    fun 行星世界卡走频道tag索引分页并回落样本() {
        val b = planetCardBody()
        assertTrue(b.contains("vm.deepIndex.channelTagItemsPage(id, tag, initialCount, 0)"))
        assertTrue(b.contains("vm.deepIndex.channelTagItemsPage(id, tag, limit, offset)"))
        assertTrue(b.contains("val coverDims = rememberPreviewDims()"))
        assertTrue(b.contains("PreviewNearEndEffect(coverListState, coverDims.half"))
        assertTrue(b.contains("coverWin.appendBatch(coverDims.half)"))
        assertTrue(b.contains("PreviewThumbRow("))
        // 回落样本=封窗（样本不分页）
        assertTrue(b.contains("coverWin.setInitial(latestSample, Int.MAX_VALUE)"))
        // 点播标签与批口径原样（批=当前已装载窗）
        assertTrue(b.contains("onPlayDust(item, covers, \"行星世界 · #\${target.tag}\")"))
        assertTrue(b.contains("val covers = coverWin.items"))
        // 旧 6 条快照截断不复活（同 tag 其他恒星 pills 的 take(6) 不在此锚范围）
        assertFalse(b.contains("getOrNull(it) }.take(6)"))
    }
}
