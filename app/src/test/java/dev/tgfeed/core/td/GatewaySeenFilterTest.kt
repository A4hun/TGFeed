package dev.tgfeed.core.td

import android.content.Context
import dev.tgfeed.core.store.LocalStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R150 已看剔除链的单测（修"全局刷新后又推已看视频"）：
 *  - [filterSeenIdentities]（GatewayMediaTab.kt 文件级纯函数）：双轨判定——
 *    key 精确命中 / fileUniqueId 指纹命中（跨频道转发同片）/ 无指纹条目只走 key 轨；
 *    双集皆空时整体直通（开关关/重温兜底态/未注入 provider 的零开销退化）；
 *    命中计数供"seen 过滤命中 N 条"证据日志；
 *  - 端到端链路（B：分屏显示即看过）：markGridDisplayedSeen 落账侧的等价行为
 *    store.markSeen 批量幂等落账 → 同片换频道重发（key 不同、指纹相同）经
 *    filterSeenIdentities 命中剔除——分屏画布上看过的内容不再回主 Feed。
 */
class GatewaySeenFilterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        // 假 Context：filesDir 指向 JUnit 临时目录，LocalStore 真实落盘、用完即焚
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    // ---------------- 夹具 ----------------

    private fun video(
        key: String,
        chatId: Long = 1L,
        fileUniqueId: String = "",
    ) = VideoItem(
        key = key,
        chatId = chatId,
        messageId = key.hashCode().toLong(),
        channelTitle = "频道$chatId",
        caption = "",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = null,
        fileUniqueId = fileUniqueId,
        mediaType = 0,
        dateEpochSec = 1_700_000_000,
    )

    // ---------------- filterSeenIdentities：双轨判定 ----------------

    @Test
    fun `key 精确命中即剔除并计数`() {
        // 行为：网关直拉产出里的条目 key 已在已看底账 → 源头剔除，命中数如实回报
        val items = listOf(video("k1"), video("k2"), video("k3"))
        val (out, hits) = filterSeenIdentities(items, setOf("k1", "k3"), emptySet())
        assertEquals(1, out.size)
        assertEquals("k2", out.first().key)
        assertEquals(2, hits)
    }

    @Test
    fun `指纹命中剔除跨频道转发的同片`() {
        // 行为（A 根因主形态）：同一视频被搬运到多个频道——key 各异、fileUniqueId 相同，
        // key 轨管不住"换台又见同片"，指纹轨必须兜住
        val seen = listOf(video("k1", chatId = 1, fileUniqueId = "uidX"))
        store.markSeen(seen.first())
        val refetched = listOf(
            video("kA", chatId = 2, fileUniqueId = "uidX"), // 同片换频道重发
            video("kB", chatId = 3, fileUniqueId = "uidY"), // 不同片
        )
        val (out, hits) = filterSeenIdentities(refetched, emptySet(), setOf("uidX"))
        assertEquals(1, out.size)
        assertEquals("kB", out.first().key)
        assertEquals(1, hits)
    }

    @Test
    fun `无指纹条目只走 key 轨不误伤`() {
        // 行为：fileUniqueId 为空（JNI 侧理论可空兜底空串）的条目不能因空指纹
        // 命中空指纹集（旧 key 轨数据/无指纹条目的宁漏勿错杀口径）
        val items = listOf(video("k1"), video("k2"))
        val (out, hits) = filterSeenIdentities(items, setOf("k2"), setOf("uidX"))
        assertEquals(1, out.size)
        assertEquals("k1", out.first().key)
        assertEquals(1, hits)
    }

    @Test
    fun `双集皆空时整体直通零开销`() {
        // 行为：开关关闭 / R94 触底重温兜底态 / provider 未注入 → 空集直通，
        // 顺序与内容原样保留（重温语义不受源头过滤影响）
        val items = listOf(video("k1"), video("k2"))
        val (out, hits) = filterSeenIdentities(items, emptySet(), emptySet())
        assertEquals(items, out)
        assertEquals(0, hits)
    }

    // ---------------- B 端到端：分屏显示落账 → 同片不再回流 ----------------

    @Test
    fun `分屏显示落账后同片跨频道重发被滤掉`() {
        // 行为（B：显示即看过）：画布显示过的条目经 markSeen 落进 seen_ids.log
        // 底账——同一批内容（含换频道重发的同片）再经网关直拉产出时被
        // filterSeenIdentities 源头剔除，退分屏/重启后主 Feed 不再推
        val displayedOnGrid = listOf(
            video("grid1", chatId = 1, fileUniqueId = "uidG1"),
            video("grid2", chatId = 1, fileUniqueId = "uidG2"),
        )
        displayedOnGrid.forEach { store.markSeen(it) } // markGridDisplayedSeenImpl 落账侧等价行为
        val (seenKeys, seenUids) = store.loadSeenIdentities()
        val refetched = listOf(
            video("feed1", chatId = 2, fileUniqueId = "uidG1"), // 同片换频道重发 → 滤
            video("grid2", chatId = 1, fileUniqueId = "uidG2"), // 原 key 重现 → 滤
            video("feed2", chatId = 3, fileUniqueId = "uidN1"), // 新片 → 留
        )
        val (out, hits) = filterSeenIdentities(refetched, seenKeys, seenUids)
        assertEquals(1, out.size)
        assertEquals("feed2", out.first().key)
        assertEquals(2, hits)
    }

    @Test
    fun `重复显示同批内容幂等零写盘`() {
        // 行为：500ms 轮询指纹比对会把同一批可见格反复登记（markSeenLocked
        // 同 key 幂等），底账行数不随重复登记增长——量级安全（轮播/换一拨
        // 不会冲垮 seen_ids.log）的前提条件
        val batch = listOf(video("g1", fileUniqueId = "u1"), video("g2", fileUniqueId = "u2"))
        repeat(5) { batch.forEach { store.markSeen(it) } }
        val log = File(tempFolder.root, "store/seen_ids.log")
        assertEquals(2, log.readLines().count { it.isNotBlank() })
    }
}
