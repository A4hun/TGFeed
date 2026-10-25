package dev.tgfeed.ui

import dev.tgfeed.core.feed.GRID_SEED_FETCH_MAX
import dev.tgfeed.core.feed.gridBatchSeedAppend
import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R397 分屏批量入口全覆盖锚测试：
 *  - 纯函数 [gridBatchSeedAppend]（批量种子去重：序保持/片单既撞剔除/批内同 key
 *    首保/空入/无上限口径）；
 *  - 上限口径：[GRID_SEED_FETCH_MAX]=48（对齐 GridScreen 画布记忆口径
 *    GRID_CANVAS_MAX_SLOTS——合集现拉路的唯一网络成本上限）；
 *  - wiring 源码锚（R3xxWiringSourceTest 同款 user.dir 源定位）：五入口「分屏播放」
 *    字面量 + 统一机制容器口 + 探针计数行格式 + GridScreen 零刀（播放本体零碰红线）。
 */
class R397GridSeedTest {

    private fun item(key: String, uid: String = "") = VideoItem(
        key = key,
        chatId = 1L,
        messageId = 0L,
        channelTitle = "频道A",
        caption = "",
        durationSec = 10,
        width = 640,
        height = 480,
        fileId = -1,
        sizeBytes = 0L,
        playUri = null,
        fileUniqueId = uid,
        mediaType = 0,
    )

    // ==================== gridBatchSeedAppend 纯函数 ====================

    @Test
    fun `空入返回空表`() {
        assertTrue(gridBatchSeedAppend(emptyList(), emptyList()).isEmpty())
        assertTrue(gridBatchSeedAppend(listOf(item("a")), emptyList()).isEmpty())
    }

    @Test
    fun `incoming 原序保持（入格序=画布排位序）`() {
        val out = gridBatchSeedAppend(emptyList(), listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf("a", "b", "c"), out.map { it.key })
    }

    @Test
    fun `片单既撞剔除（单条路 key 判轨批量化）`() {
        val existing = listOf(item("a"), item("c"))
        val out = gridBatchSeedAppend(existing, listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf("b"), out.map { it.key })
    }

    @Test
    fun `批内同 key 首保（批内去重兜底）`() {
        val out = gridBatchSeedAppend(emptyList(), listOf(item("a"), item("a"), item("b"), item("a")))
        assertEquals(listOf("a", "b"), out.map { it.key })
    }

    @Test
    fun `无上限口径——本地在位列表全量放行（画布流式排位条目数无硬闸）`() {
        // 60 条（>GRID_CANVAS_MAX_SLOTS=48）全量入列：种子纯函数不设新上限——
        // 画布承载沿流式排位/画布记忆前 N 槽位既有口径，格数闸不在种子路
        val incoming = (0 until 60).map { item("k$it") }
        val out = gridBatchSeedAppend(emptyList(), incoming)
        assertEquals(60, out.size)
        assertEquals(incoming.map { it.key }, out.map { it.key })
    }

    @Test
    fun `去重不误伤——fileUniqueId 不同 key 的同指纹条目按 key 轨各自放行`() {
        // 沿 App.addToGrid 单条 key 判轨（不引入 fileUniqueId 第二判轨）：key 不同即
        // 各自入列（跨频道转发同片撞 key 判轨的口径收口在容器/聚合源侧，不偷偷加宽）
        val out = gridBatchSeedAppend(
            emptyList(),
            listOf(item("a", uid = "f1"), item("b", uid = "f1")),
        )
        assertEquals(listOf("a", "b"), out.map { it.key })
    }

    // ==================== 上限口径 ====================

    @Test
    fun `合集现拉上限 48=画布记忆口径对齐`() {
        // 数值=GridScreen.GRID_CANVAS_MAX_SLOTS（private 无法复用，单源常量+KDoc 互指；
        // 本断言锁数值防漂移——两常量若需变更须同拍）
        assertEquals(48, GRID_SEED_FETCH_MAX)
    }

    // ==================== wiring 源码锚（六入口+统一机制） ====================

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

    /** 「分屏播放 N 条」钮字面量（实数口径）+ 回调接线名 */
    private fun assertEntryButton(file: String, label: String) {
        val text = src(file)
        assertTrue("$file 必须含「分屏播放」入口钮文案", text.contains("分屏播放 $label"))
        assertTrue("$file 必须接线 onBatchToGrid 回调", text.contains("onBatchToGrid"))
    }

    @Test
    fun `入口一_搜索结果页钮`() {
        // N=mergedResults.size（混排去重+排序+类型筛选后=屏幕所显，所见即所种）
        assertEntryButton("dev/tgfeed/ui/SearchScreen.kt", "\${mergedResults.size}")
    }

    @Test
    fun `入口二_收藏夹钮`() {
        assertEntryButton("dev/tgfeed/ui/FavoritesScreen.kt", "\${items.size}")
    }

    @Test
    fun `入口三_频道主页媒体墙钮_吃当前tab口径`() {
        // gridItems=当前 tab 墙格（作品=类型筛选+剧集链聚合散格/合集=图集代表格）
        assertEntryButton("dev/tgfeed/ui/ChannelHomeScreen.kt", "\${gridItems.size}")
    }

    @Test
    fun `入口四_合集UI行尾钮_现拉路`() {
        val text = src("dev/tgfeed/ui/components/CollectionsDialog.kt")
        // 钮面无数判词（纯频道集合无在位条目数，带数=编造）——行尾钮字面量不带 \$
        assertTrue("合集行尾钮必须在场（无数形态）", text.contains("\"分屏播放\""))
        assertTrue("现拉路必须接统一件", text.contains("fetchCollectionSeed"))
        assertTrue("宿主收口回调必须接线", text.contains("onBatchToGrid"))
    }

    @Test
    fun `入口六_话题标签页钮`() {
        assertEntryButton("dev/tgfeed/ui/HashtagTimelineScreen.kt", "\${wall.size}")
    }

    @Test
    fun `统一机制容器口与探针计数行`() {
        val app = src("dev/tgfeed/App.kt")
        assertTrue("容器口 addBatchToGrid 必须在场（VM 入口=片单持有者容器）", app.contains("fun addBatchToGrid("))
        assertTrue("探针计数行格式（零内容脱敏：来源=面名 条数=实增）", app.contains("R397 批量入分屏:来源="))
        assertTrue("探针必须报实增数", app.contains("条数=\${append.size}"))
        val seed = src("dev/tgfeed/core/feed/GridSeed.kt")
        assertTrue("纯函数单源件必须在场", seed.contains("fun gridBatchSeedAppend("))
        assertTrue("现拉路单源件必须在场", seed.contains("suspend fun fetchCollectionSeed("))
    }

    @Test
    fun `宿主收口单口与四个来源标签`() {
        val main = src("dev/tgfeed/MainActivity.kt")
        assertTrue("宿主收口单口必须在场", main.contains("private fun seedGridAndOpen("))
        for (label in listOf("搜索", "收藏", "频道主页", "话题")) {
            assertTrue("MainActivity 来源标签「$label」必须接线", main.contains("seedGridAndOpen(\"$label\""))
        }
        // 合集来源的宿主收口在 FeedScreen（CollectionsDialog 的宿主）
        val feed = src("dev/tgfeed/ui/FeedScreen.kt")
        assertTrue("合集来源宿主收口在 FeedScreen", feed.contains("seedGridAndOpen(\"合集\"".replace("seedGridAndOpen", "addBatchToGrid")))
    }

    @Test
    fun `红线_GridScreen 零刀_播放本体零碰`() {
        // R397 机制不进分屏播放本体：GridScreen 不得出现批量入分屏机制字面量
        //（种子路全在容器/调用方侧；画布/池/授权闸/换片链零接触）
        val grid = src("dev/tgfeed/ui/GridScreen.kt")
        assertTrue("GridScreen 零 R397 刀", !grid.contains("R397"))
        assertTrue("GridScreen 零 addBatchToGrid 机制", !grid.contains("addBatchToGrid"))
    }
}
