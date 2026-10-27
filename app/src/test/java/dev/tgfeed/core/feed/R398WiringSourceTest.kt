package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R398 播放编排四期 wiring 源码锚（R392bWiringSourceTest 同款 user.dir 源定位
 * ——VM/UI 接线无 JVM 直测面处以源锚锁结构面；单行 contains 判式=防 CRLF
 * 检出形态漂移的既有教训锚，零跨行 \n 锚；r398_notes §2/§3）：
 *  - core 锚：#10 匹配纯函数（caption 忽略大小写/不回绕）+段前缀剪除+
 *    #9 台下载纯件三口；
 *  - VM 锚：#9 串行批（downloadImpl 直调=下载系统复用零新内部+15min 兜底+
 *    幂等跳过）+离线挂点（openPlayQueueImpl 两口链式）+#10 跳播链（setResume
 *    落点+planFromEntryId 复用一期开单链）；
 *  - 委托锚：downloadPlayStation/jumpToQueueSemantic 两口；
 *  - UI 锚：QueueScreen 跳到讲…搜索条+无果 Toast；QueueStationsScreen
 *    下载整单钮+已下载 N/M 徽记；
 *  - 探针行锚：三计数口（台下载/开单离线接线/语义跳转——V70 断言面）；
 *  - 红线负锚：下载引擎（FeedDownloadPart）+一期 core（PlayQueue.kt）+
 *    播放本体（GridScreen/VideoPage/VideoLoad）+路由（MainActivity）
 *    零 R398 引用=「下载/播放引擎内部零改只编排」实证锚。
 */
class R398WiringSourceTest {

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
            error("找不到主源码根(user.dir=${System.getProperty("user.dir")})")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在:${file.path}", file.isFile)
        return file.readText()
    }

    private fun jumpCore(): String = src("dev/tgfeed/core/feed/QueueSemanticJump.kt")

    private fun dlCore(): String = src("dev/tgfeed/core/feed/StationDownloads.kt")

    private fun dlPart(): String = src("dev/tgfeed/core/feed/FeedStationDownloadPart.kt")

    private fun jumpPart(): String = src("dev/tgfeed/core/feed/FeedQueueJumpPart.kt")

    private fun queuePart(): String = src("dev/tgfeed/core/feed/FeedPlayQueuePart.kt")

    private fun vm(): String = src("dev/tgfeed/core/feed/FeedViewModel.kt")

    private fun queueUi(): String = src("dev/tgfeed/ui/QueueScreen.kt")

    private fun stationsUi(): String = src("dev/tgfeed/ui/QueueStationsScreen.kt")

    private fun downloadEngine(): String = src("dev/tgfeed/core/feed/FeedDownloadPart.kt")

    private fun coreOne(): String = src("dev/tgfeed/core/feed/PlayQueue.kt")

    private fun gridUi(): String = src("dev/tgfeed/ui/GridScreen.kt")

    private fun videoPage(): String = src("dev/tgfeed/ui/components/VideoPage.kt")

    private fun videoLoad(): String = src("dev/tgfeed/ui/components/VideoLoad.kt")

    private fun mainActivity(): String = src("dev/tgfeed/MainActivity.kt")

    // ---------------- ① core 锚 ----------------

    @Test
    fun `语义匹配纯函数_单源_忽略大小写判式`() {
        assertTrue(
            "findNextSemanticMatch 单源（QueueSemanticJump.kt 新件）",
            jumpCore().contains("fun findNextSemanticMatch("),
        )
        assertTrue("大小写不敏感判式（caption 本地包含）", jumpCore().contains("ignoreCase = true"))
        // 零网络承诺=签名参数表只有 entries/query/fromIndex 三参（无 gateway）
        assertTrue("签名三参零网关", jumpCore().contains("entries: List<PlayQueueEntry>,"))
    }

    @Test
    fun `段前缀剪除_单源_默认路径零变判式`() {
        assertTrue("dropPlanPrefixBefore 单源", jumpCore().contains("fun dropPlanPrefixBefore("))
        // entryId=null 原样返回（一期行为零变判词）——单行判式锚
        assertTrue("null 原样判式", jumpCore().contains("if (entryId == null) return plan"))
    }

    @Test
    fun `台下载纯件_三口单源`() {
        assertTrue("StationDownloadCount 徽记数据单源", dlCore().contains("data class StationDownloadCount("))
        assertTrue("stationDownloadItems 单源", dlCore().contains("fun stationDownloadItems("))
        assertTrue("stationDownloadCount 单源", dlCore().contains("fun stationDownloadCount("))
        assertTrue("applyDownloadedSegments 单源", dlCore().contains("fun applyDownloadedSegments("))
    }

    // ---------------- ② VM 锚 ----------------

    @Test
    fun `台下载串行批_downloadImpl直调_下载系统复用零新内部`() {
        assertTrue("downloadPlayStationImpl 单源", dlPart().contains("internal fun FeedViewModel.downloadPlayStationImpl("))
        // 串行批=对既有单条下载链逐条直调（下载引擎本体零刀只调用）
        assertTrue("downloadImpl(item) 直调恰一次", dlPart().contains("downloadImpl(item) // 既有单条下载链"))
        assertTrue("15min 兜底常量单源", dlPart().contains("internal const val STATION_DOWNLOAD_PER_ITEM_MS"))
    }

    @Test
    fun `台下载幂等跳过_串行等终态判式`() {
        // 断续幂等：台账在/在途跳过（pending 过滤判式）
        assertTrue(
            "已下载+在途跳过判式",
            dlPart().contains("it.key !in downloaded && it.key !in running"),
        )
        // 串行=等终态再发下一条（读改写竞态编排侧规避判词）
        assertTrue("等终态判式", dlPart().contains("map[item.key] is DownloadStatus.Done || map[item.key] is DownloadStatus.Failed"))
    }

    @Test
    fun `离线挂点_开单两口链式_默认路径零变`() {
        // planFromEntryId 默认 null（一期开单行为零变——签名锚单行）
        assertTrue(
            "openPlayQueueImpl 默认参数签名锚",
            queuePart().contains("internal fun FeedViewModel.openPlayQueueImpl(fromStart: Boolean, planFromEntryId: String? = null)"),
        )
        // 两口链式：先剪后接线（被剪段不浪费接线）
        assertTrue(
            "段前缀剪除→离线接线链式",
            queuePart().contains("dropPlanPrefixBefore(buildPlayPlan(queue, counts, seed), planFromEntryId)"),
        )
        assertTrue("离线接线挂点单源", dlPart().contains("internal fun FeedViewModel.applyStationOfflinePlan("))
    }

    @Test
    fun `路径还原_拼接还原判式_不继承相对路径疑病`() {
        assertTrue("localStationDownloadUri 单源", dlPart().contains("internal fun localStationDownloadUri("))
        // deleteDownloadedFile 同款拼接还原（绝对直出/相对拼外部存储根）
        assertTrue(
            "拼外部存储根还原判式",
            dlPart().contains("File(Environment.getExternalStorageDirectory(), path).absolutePath"),
        )
    }

    @Test
    fun `跳播链_setResume落点_复用一期开单链`() {
        assertTrue("jumpToQueueSemanticImpl 单源", jumpPart().contains("internal fun FeedViewModel.jumpToQueueSemanticImpl("))
        // 断点=跳播落点（一期语义：跳到=从这继续听）
        assertTrue("setResume 落点判式", jumpPart().contains("playQueueModel.setResume(matched.id, matched.item.key)"))
        // 复用既有跳转链=一期开单链+段前缀（planFromEntryId）
        assertTrue(
            "openPlayQueueImpl 复用判式",
            jumpPart().contains("openPlayQueueImpl(fromStart = false, planFromEntryId = matched.id)"),
        )
    }

    // ---------------- ③ 委托锚 ----------------

    @Test
    fun `VM委托两口_台下载与语义跳转`() {
        assertTrue("downloadPlayStation 委托", vm().contains("fun downloadPlayStation(stationId: String): Int"))
        assertTrue("jumpToQueueSemantic 委托", vm().contains("fun jumpToQueueSemantic(query: String): Boolean"))
    }

    // ---------------- ④ UI 锚 ----------------

    @Test
    fun `QueueScreen跳到讲搜索条_跳播钮_无果Toast`() {
        assertTrue("搜索条占位文案", queueUi().contains("跳到讲…"))
        assertTrue("跳播接线（vm.jumpToQueueSemantic）", queueUi().contains("vm.jumpToQueueSemantic(q)"))
        assertTrue("无果 Toast 如实", queueUi().contains("没找到讲「\$q」的条目"))
    }

    @Test
    fun `QueueStationsScreen下载整单钮_已下载徽记`() {
        assertTrue("徽记派生接线（stationDownloadCount）", stationsUi().contains("downloadCount = stationDownloadCount(station, downloadedKeys)"))
        assertTrue("下载整单接线（vm.downloadPlayStation）", stationsUi().contains("vm.downloadPlayStation(station.id)"))
        assertTrue("下载整单钮图标", stationsUi().contains("Icons.Outlined.FileDownload"))
        assertTrue("已下载 N/M 徽记文案", stationsUi().contains("已下载 "))
        // R397 既有锚不被本批破坏（分屏钮原样在）
        assertTrue("R397 分屏钮原样（零碰实证）", stationsUi().contains("分屏播放 \$seedCount 条"))
    }

    // ---------------- ⑤ 探针行锚（V70 断言面） ----------------

    @Test
    fun `探针行三计数口_计数口径零内容`() {
        assertTrue("台下载探针", dlPart().contains("台下载:台尾="))
        assertTrue("开单离线接线探针", dlPart().contains("开单离线接线:下载段="))
        assertTrue("语义跳转探针（命中+无果两口）", jumpPart().contains("语义跳转:命中 key尾=") && jumpPart().contains("语义跳转:无果"))
        // 探针 tag=R398 计数口径（零台名零频道名零条目内容=脱敏铁律）
        assertTrue("台下载探针零内容判式", dlPart().contains("（计数口径,零内容）"))
    }

    // ---------------- ⑥ 红线负锚 ----------------

    @Test
    fun `下载引擎零碰_FeedDownloadPart零R398引用`() {
        assertEquals(
            "下载引擎内部零改只编排（downloadImpl/saveToGallery/MediaStore 全零刀，本批只调用）",
            0,
            Regex("R398").findAll(downloadEngine()).count(),
        )
    }

    @Test
    fun `一期core零碰_PlayQueue零R398引用`() {
        assertEquals("一期 core 结论零碰只消费（模型/装配/源零刀）", 0, Regex("R398").findAll(coreOne()).count())
    }

    @Test
    fun `播放本体零碰_GridScreen与播放链UI零R398引用`() {
        assertEquals("GridScreen 零碰", 0, Regex("R398").findAll(gridUi()).count())
        assertEquals("VideoPage 零碰", 0, Regex("R398").findAll(videoPage()).count())
        assertEquals("VideoLoad 零碰", 0, Regex("R398").findAll(videoLoad()).count())
    }

    @Test
    fun `路由零改_MainActivity零R398引用`() {
        assertEquals(
            "MainActivity 零改（跳播=QueueScreen 既有 onBack 剥落，下载=台卡原地动作无路由变化）",
            0,
            Regex("R398").findAll(mainActivity()).count(),
        )
    }
}
