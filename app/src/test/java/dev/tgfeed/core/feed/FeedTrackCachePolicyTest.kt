package dev.tgfeed.core.feed

import android.content.Context
import dev.tgfeed.core.store.LocalStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R189 轨道缓存纯策略单测（媒体 tab/换列往返的内存快照口径，r189_notes B/C 节）。
 *
 * 覆盖：快照可用性判定（非空/筛选漂移/已看开关漂移）、LRU 容量逐出（重写保护）、
 * 列归属分类（trackColumnKindOf——列缓存寻址的键口径：关注/推荐/潜进三分）。
 * FakeGateway/LocalStore 手法沿用 FollowingFeedSourceTest（mockk Context+TemporaryFolder）。
 */
class FeedTrackCachePolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    /** 假网关：只满足 FeedSource 构造签名（本测试不触网络/盘——快照判定是纯函数） */
    private class FakeGateway : dev.tgfeed.core.td.TdGateway {
        override val state: StateFlow<dev.tgfeed.core.td.GatewayState> =
            MutableStateFlow(dev.tgfeed.core.td.GatewayState.Ready)
        override val connectionState: StateFlow<String> = MutableStateFlow("已连接")
        override val proxyHint: StateFlow<String?> = MutableStateFlow(null)
        override var includeCommentResources: Boolean = false
        override suspend fun submitPhone(phone: String) {}
        override suspend fun submitCode(code: String) {}
        override suspend fun submitPassword(password: String) {}
        override suspend fun listChannels(): List<dev.tgfeed.core.td.ChannelBrief> = emptyList()
        override suspend fun fetchVideos(
            request: dev.tgfeed.core.td.FeedRequest,
            cursor: String?,
            limit: Int,
        ): dev.tgfeed.core.td.FeedPage = dev.tgfeed.core.td.FeedPage(emptyList(), null)
        override suspend fun ensurePlayable(item: dev.tgfeed.core.td.VideoItem, priority: Int): String = ""
    }

    private val gateway = FakeGateway()

    private fun item(key: String) = dev.tgfeed.core.td.VideoItem(
        key = key, chatId = 1L, messageId = 1, channelTitle = "频道A", caption = "内容",
        durationSec = 30, width = 720, height = 1280, fileId = 7, sizeBytes = 1024L,
        playUri = null, dateEpochSec = 1_700_000_000,
    )

    private fun tabSnap(
        items: List<dev.tgfeed.core.td.VideoItem>,
        filter: FeedFilter = FeedFilter(),
        excludeWatched: Boolean = false,
    ) = MediaTabSnapshot(
        items = items, index = 0, mediaTabCursor = null, mediaTabExhausted = false,
        snapshotKeys = emptySet(), filter = filter, excludeWatched = excludeWatched,
    )

    private fun colSnap(
        items: List<dev.tgfeed.core.td.VideoItem>,
        filter: FeedFilter = FeedFilter(),
        excludeWatched: Boolean = false,
    ) = ColumnTrackSnapshot(
        source = FollowingFeedSource(gateway, store),
        items = items, index = 0, mediaTypeTab = 0,
        mediaTabCursor = null, mediaTabExhausted = false, snapshotKeys = emptySet(),
        filter = filter, excludeWatched = excludeWatched,
    )

    // ---------------- 可用性判定（命中条件：非空 + 全局条件未漂移） ----------------

    @Test
    fun `空列表快照不可用_加载中不入缓存`() {
        assertFalse(FeedTrackCachePolicy.tabSnapshotUsable(tabSnap(emptyList()), FeedFilter(), false))
        assertFalse(FeedTrackCachePolicy.columnSnapshotUsable(colSnap(emptyList()), FeedFilter(), false))
    }

    @Test
    fun `非空且条件一致_可用`() {
        assertTrue(FeedTrackCachePolicy.tabSnapshotUsable(tabSnap(listOf(item("k1"))), FeedFilter(), false))
        assertTrue(FeedTrackCachePolicy.columnSnapshotUsable(colSnap(listOf(item("k1"))), FeedFilter(), false))
    }

    @Test
    fun `筛选漂移_两种快照都作废`() {
        val drifted = FeedFilter(minDurationSec = 30)
        assertFalse(FeedTrackCachePolicy.tabSnapshotUsable(tabSnap(listOf(item("k1"))), drifted, false))
        assertFalse(FeedTrackCachePolicy.columnSnapshotUsable(colSnap(listOf(item("k1"))), drifted, false))
    }

    @Test
    fun `已看开关漂移_两种快照都作废`() {
        assertFalse(FeedTrackCachePolicy.tabSnapshotUsable(tabSnap(listOf(item("k1")), excludeWatched = true), FeedFilter(), false))
        assertFalse(FeedTrackCachePolicy.columnSnapshotUsable(colSnap(listOf(item("k1")), excludeWatched = false), FeedFilter(), true))
    }

    // ---------------- LRU 容量逐出（putCapped：重写挪最新端、超容逐最旧） ----------------

    @Test
    fun `超容量逐最旧_重写保护`() {
        val map = LinkedHashMap<String, Int>()
        FeedTrackCachePolicy.putCapped(map, "a", 1, cap = 2)
        FeedTrackCachePolicy.putCapped(map, "b", 2, cap = 2)
        // a 被重写（挪到最新端）后，超容逐的是 b（不是 a）
        FeedTrackCachePolicy.putCapped(map, "a", 10, cap = 2)
        FeedTrackCachePolicy.putCapped(map, "c", 3, cap = 2)
        assertEquals(setOf("a", "c"), map.keys)
        assertEquals(10, map["a"])
    }

    @Test
    fun `容量内不逐出_超容逐最旧`() {
        val map = LinkedHashMap<Pair<String, Int>, Int>()
        repeat(FeedTrackCachePolicy.TAB_SNAPSHOT_CAP) { i ->
            FeedTrackCachePolicy.putCapped(map, "src$i" to i, i, cap = FeedTrackCachePolicy.TAB_SNAPSHOT_CAP)
        }
        assertEquals(FeedTrackCachePolicy.TAB_SNAPSHOT_CAP, map.size)
        // 再放一个：逐出最旧（src0）
        FeedTrackCachePolicy.putCapped(map, "new" to 0, 99, cap = FeedTrackCachePolicy.TAB_SNAPSHOT_CAP)
        assertEquals(FeedTrackCachePolicy.TAB_SNAPSHOT_CAP, map.size)
        assertNull(map["src0" to 0])
        assertEquals(99, map["new" to 0])
    }

    // ---------------- 列归属分类（列缓存寻址键） ----------------

    @Test
    fun `列归属_关注推荐潜进三分`() {
        assertTrue(trackColumnKindOf(FollowingFeedSource(gateway, store)) == TrackColumnKind.FOLLOWING)
        assertTrue(trackColumnKindOf(AggregateFeedSource(gateway)) == TrackColumnKind.RECOMMEND)
        assertNull(trackColumnKindOf(FavoritesFeedSource(store)))
        assertNull(trackColumnKindOf(null))
    }
}
