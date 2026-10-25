package dev.tgfeed.core.store

import android.content.Context
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R324 快照落盘时点往返锚（FeedSnapshot.savedAtMs——离线降级「N 小时前的快照」
 * 的诚实时点原料；FollowingSnapshotStoreTest 同款 mockk+临时目录，构造数据
 * 零真实频道）：
 *  - save 写 ts（既有行为）→ load 回读 savedAtMs>0（本笔补读的读侧）；
 *  - 坏档/缺 ts（旧版落盘的存量快照）→ 0=无时点（诚实缺省，banner 不编造时点）；
 *  - mode 拒配行为不回归（real/demo 门）。
 */
class SnapshotSavedAtStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    private fun item(chatId: Long, msgId: Long) = VideoItem(
        key = "c${chatId}m$msgId",
        chatId = chatId,
        messageId = msgId,
        channelTitle = "频道$chatId",
        caption = "内容$msgId",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = "http://x/$chatId/$msgId",
        dateEpochSec = 1_700_000_000 + msgId.toInt(),
    )

    @Test
    fun `feed快照-时点往返（save落ts-load回读savedAtMs）`() {
        val before = System.currentTimeMillis()
        store.saveFeedSnapshot(listOf(item(1, 10), item(2, 20)), "real")
        val snap = store.loadFeedSnapshot()
        assertNotNull(snap)
        assertTrue("savedAtMs 必须回读到正值", snap!!.savedAtMs >= before)
        assertEquals("real", snap.mode)
        assertEquals(2, snap.items.size)
    }

    @Test
    fun `关注流快照-时点往返（同构共用parseSnapshot）`() {
        val before = System.currentTimeMillis()
        store.saveFollowingSnapshot(listOf(item(3, 30)), "real")
        val snap = store.loadFollowingSnapshot()
        assertNotNull(snap)
        assertTrue(snap!!.savedAtMs >= before)
    }

    @Test
    fun `旧档缺ts-诚实缺省0（无时点不编造）`() {
        // 模拟 R324 前落盘的存量快照：先正常存一份，再把 "ts" 字段剜掉重写
        //（结构=真实的 v1 旧档形态，杜绝手写 JSON 与解析器的键面漂移；LocalStore
        // 的快照真路径=filesDir/store/feed_snapshot.json——dir 子目录口径）。
        // org.json 键序无保证（HashMap 序）——ts 可能居中（带尾逗号）也可能收尾
        // （带头逗号），两种形态都剜
        store.saveFeedSnapshot(listOf(item(9, 90)), "real")
        val file = java.io.File(tempFolder.root, "store/feed_snapshot.json")
        val legacy = file.readText()
            .replace(Regex(""""ts":\d+,"""), "")
            .replace(Regex(""","ts":\d+"""), "")
        file.writeText(legacy)
        val snap = store.loadFeedSnapshot()
        assertNotNull("缺 ts 的旧档仍可解析（向后兼容）", snap)
        assertEquals("旧档 savedAtMs 诚实缺省 0", 0L, snap!!.savedAtMs)
        assertEquals("条目照常解析", 1, snap.items.size)
    }

    @Test
    fun `坏档-返回null不崩（防御路径不回归）`() {
        val file = java.io.File(tempFolder.root, "store/feed_snapshot.json")
        file.parentFile.mkdirs() // 直写不经 store.writeText（其自建目录）——先建目录
        file.writeText("{not json")
        assertNull(store.loadFeedSnapshot())
    }

    @Test
    fun `空列表不写-不覆盖好快照（既有行为不回归）`() {
        store.saveFeedSnapshot(listOf(item(1, 10)), "real")
        val good = store.loadFeedSnapshot()
        store.saveFeedSnapshot(emptyList(), "real") // 空列表=不写（防坏状态盖好快照）
        val after = store.loadFeedSnapshot()
        assertEquals(good!!.items.size, after!!.items.size)
        assertEquals(good.savedAtMs, after.savedAtMs)
    }
}
