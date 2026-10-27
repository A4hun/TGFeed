package dev.tgfeed.core.store

import android.content.Context
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LocalStore] 的行为单测：原子写（tmp+rename）、损坏容错、老格式迁移兜底、
 * FIFO 容量淘汰、已看身份双轨登记。
 *
 * 用 mockk 假 Context 把 filesDir 指到 JUnit 临时目录，LocalStore 真实落盘——
 * 每个用例独立目录、用完即焚，测试可独立重复跑。
 */
class LocalStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    /** LocalStore 的真实落盘目录（filesDir/store） */
    private lateinit var storeDir: File

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
        storeDir = File(tempFolder.root, "store")
    }

    private fun video(key: String, chatId: Long = 1L) = VideoItem(
        key = key,
        chatId = chatId,
        messageId = 1L,
        channelTitle = "频道$chatId",
        caption = "标题$key",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = null,
        fileUniqueId = "uid_$key",
        mediaType = 0,
        dateEpochSec = 1_700_000_000,
    )

    // ---------------- R300 favTs（多设备同步加入时刻） ----------------

    @Test
    fun `favTs落盘往返保真`() {
        val items = listOf(video("a").copy(favTs = 1_700_000_000_000L), video("b").copy(favTs = 5L))
        store.saveFavorites(items)
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        assertEquals(listOf(1_700_000_000_000L, 5L), fresh.loadFavorites().map { it.favTs })
    }

    @Test
    fun `老格式无favTs缺省为0_未知戳`() {
        // 迁移兜底：存量收藏 JSON 无 favTs 字段 → 反序列化 0=未知（SyncMergePolicy 存在性并集兜底）
        storeDir.mkdirs()
        File(storeDir, "favorites.json").writeText(
            """[{"key":"old1","chatId":1,"messageId":2}]"""
        )
        assertEquals(0L, store.loadFavorites().single().favTs)
    }

    // ---------------- 原子写（tmp + rename） ----------------

    @Test
    fun `落盘后不留 tmp 残骸且内容可完整读回`() {
        // 行为：writeText 是"临时文件 + rename"原子写——写完后目录里只有目标文件，
        // 没有任何 .tmp 残留（rename 成功时临时文件已被改名走）
        store.saveFavorites(listOf(video("a"), video("b"), video("c")))
        assertTrue(File(storeDir, "favorites.json").isFile)
        val leftovers = storeDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertEquals("落盘后不应有 .tmp 残骸：$leftovers", 0, leftovers.size)
        // 读回内容与原写入一致（新实例绕开内存缓存，强制走磁盘验证落盘内容）
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        assertEquals(listOf("a", "b", "c"), fresh.loadFavorites().map { it.key })
    }

    @Test
    fun `上一轮崩溃留下的 tmp 文件不污染读取`() {
        // 行为：崩溃可能留下半截 .tmp 文件——读取只认正式文件，tmp 残骸被忽略
        storeDir.mkdirs()
        File(storeDir, "favorites.json.tmp").writeText("{\"半截 json")
        assertTrue(store.loadFavorites().isEmpty())
    }

    // ---------------- 损坏容错 ----------------

    @Test
    fun `整文件损坏按空数据处理不崩溃`() {
        storeDir.mkdirs()
        File(storeDir, "history.json").writeText("这不是 json {{{")
        assertTrue(store.loadHistory().isEmpty())
        // 损坏后仍可正常覆写恢复
        store.appendHistory(video("x"))
        assertEquals(listOf("x"), store.loadHistory().map { it.first })
    }

    @Test
    fun `单条坏记录只跳过该条其余保留`() {
        // 行为：逐条防御——数组里缺 key 的坏条目（getString 抛异常）被跳过，
        // 其余收藏正常读回，不再让一条坏记录拖垮整个文件
        storeDir.mkdirs()
        File(storeDir, "favorites.json").writeText(
            """[{"key":"good1","chatId":1},{"chatId":2},{"key":"good2","chatId":3}]"""
        )
        assertEquals(listOf("good1", "good2"), store.loadFavorites().map { it.key })
    }

    // ---------------- R435 损坏检测（三态口径） ----------------

    @Test
    fun `收藏整文件损坏不入缓存_文件修复后同实例读回`() {
        // 行为：整文件解析失败=损坏态——返回空表维持 UI 可用但**不写缓存**（下次调用
        // 重读重试）；老口径空表入缓存，读侧一次抖动会被缓存固化成永久空表、再被下一
        // 次全量覆盖写坐实清空（R433 审计 HIGH 根因链）
        storeDir.mkdirs()
        File(storeDir, "favorites.json").writeText("这不是 json {{{")
        assertTrue(store.loadFavorites().isEmpty())
        // 文件修复（外部恢复/下一轮成功写入）后：同实例再读能看到新内容——证明
        // 损坏态没把空表灌进缓存
        File(storeDir, "favorites.json").writeText("""[{"key":"a","chatId":1,"messageId":2}]""")
        assertEquals(listOf("a"), store.loadFavorites().map { it.key })
    }

    @Test
    fun `下载记录整文件损坏不入缓存_文件修复后同实例读回`() {
        // 口径同收藏：downloads.json 整文件解析失败=损坏态不入缓存，修复后同实例自愈
        storeDir.mkdirs()
        File(storeDir, "downloads.json").writeText("这不是 json {{{")
        assertTrue(store.listDownloads().isEmpty())
        File(storeDir, "downloads.json").writeText(
            """[{"key":"a","title":"t","channelTitle":"c","path":"p","ts":1}]"""
        )
        assertEquals(listOf("a"), store.listDownloads().map { it.key })
    }

    @Test
    fun `收藏文件不存在按合法空态缓存`() {
        // 反向锚：文件不存在=合法空态（从未写过/已清空）——空表入缓存是**正确**行为，
        // 与损坏态（不入缓存）区分；真实场景里磁盘表只会由本实例自己的 save 写入
        // （写后重建缓存），不会出现"外部塞文件同实例看不见"的错位
        assertTrue(store.loadFavorites().isEmpty())
        storeDir.mkdirs()
        File(storeDir, "favorites.json").writeText("""[{"key":"a","chatId":1,"messageId":2}]""")
        assertTrue("合法空态应命中缓存不再重读磁盘", store.loadFavorites().isEmpty())
    }

    // ---------------- 序列化口径 ----------------

    @Test
    fun `序列化保留跨会话字段并丢弃会话语义字段`() {
        // 行为：fileUniqueId（跨会话内容指纹）/mediaType/dateEpochSec 落盘保留；
        // coverBytes 不落盘；playUri 只保留 http 直链与 asset——
        // 回环流地址（http://127.0.0.1:端口）与裸本地路径重启后必失效，落盘即埋雷
        val items = listOf(
            video("http").copy(playUri = "https://example.com/v.mp4", mediaType = 1),
            video("loopback").copy(playUri = "http://127.0.0.1:8080/v/7"),
            video("asset").copy(playUri = "asset:///demo.mp4"),
            video("local").copy(playUri = "/data/local/tmp/x.mp4"),
        )
        store.saveFavorites(items)
        val back = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        }).loadFavorites().associateBy { it.key }

        assertEquals("https://example.com/v.mp4", back.getValue("http").playUri)
        assertNull(back.getValue("loopback").playUri) // 回环流地址不持久化
        assertEquals("asset:///demo.mp4", back.getValue("asset").playUri)
        assertNull(back.getValue("local").playUri) // 裸本地路径不持久化
        assertEquals("uid_http", back.getValue("http").fileUniqueId)
        assertEquals(1, back.getValue("http").mediaType)
        assertEquals(1_700_000_000, back.getValue("http").dateEpochSec)
        assertNull(back.getValue("http").coverBytes) // 封面字节不落盘
    }

    // ---------------- FIFO 容量淘汰 ----------------

    @Test
    fun `观看历史超过 500 条按先进先出淘汰最旧`() {
        // 行为：history.json 上限 MAX_HISTORY=500，列表即新→旧序，截尾 = 淘汰最旧。
        // 追加 505 条后只剩最新 500 条（k505..k6），最老的 k5..k1 被淘汰
        for (i in 1..505) store.appendHistory(video("k$i"))
        val history = store.loadHistory()
        assertEquals(500, history.size)
        assertEquals("k505", history.first().first) // 最新在前
        assertEquals("k6", history.last().first) // 第 501 旧 = 第 6 条入队的
        assertFalse(history.any { it.first == "k5" })
    }

    @Test
    fun `历史淘汰不失忆已看底账`() {
        // 行为：appendHistory 同事务把 key 登记进 seen_ids.log（容量 10 万的判重底账）——
        // 即使该条已被 500 上限从历史淘汰，"已看不重推"仍然记得它
        for (i in 1..505) store.appendHistory(video("k$i"))
        assertFalse(store.loadHistory().any { it.first == "k1" }) // k1 已被淘汰
        val (seenKeys, seenUids) = store.loadSeenIdentities()
        assertTrue("被淘汰的历史条目仍应在已看底账里", "k1" in seenKeys)
        assertTrue("uid_k1" in seenUids) // 内容指纹双轨同样保留
    }

    @Test
    fun `已看登记幂等且独立于观看历史`() {
        // 行为：markSeen 只登记身份不写历史；同 key 重复登记零写盘（日志行数不变）
        store.markSeen(video("s1"))
        assertTrue(store.loadHistory().isEmpty()) // 不产生观看历史
        val log = File(storeDir, "seen_ids.log")
        assertEquals(1, log.readLines().size)
        store.markSeen(video("s1")) // 重复登记
        assertEquals("同 key 重复登记不应追加日志行", 1, log.readLines().size)
        assertTrue("s1" in store.loadSeenIdentities().first)
    }

    @Test
    fun `观看时刻表按最新一次观看取值且双轨齐备`() {
        // 行为（R176 回收间隔闸原料）：loadWatchTimestamps 返回 key/指纹双轨的
        // 最近观看时刻；列表新→旧序，同 key 重看先到者优先（=最新一次）；
        // 老格式记录（无本体）只有 key 轨、无指纹轨
        storeDir.mkdirs()
        File(storeDir, "history.json").writeText(
            """[
                {"key":"new1","ts":300,"item":{"key":"new1","chatId":1,"messageId":1,"channelTitle":"c","caption":"x","durationSec":30,"width":720,"height":1280,"fileId":7,"sizeBytes":1024,"fileUniqueId":"uid_new1"}},
                {"key":"new1","ts":100},
                {"key":"new2","ts":200,"item":{"key":"new2","chatId":2,"messageId":2,"channelTitle":"c","caption":"x","durationSec":30,"width":720,"height":1280,"fileId":7,"sizeBytes":1024,"fileUniqueId":"uid_new2"}},
                {"key":"oldfmt","ts":50}
            ]"""
        )
        val (keyTs, uidTs) = store.loadWatchTimestamps()
        assertEquals(300L, keyTs.getValue("new1")) // 同 key 重看取最新（先到者优先）
        assertEquals(200L, keyTs.getValue("new2"))
        assertEquals(50L, keyTs.getValue("oldfmt"))
        assertEquals(300L, uidTs.getValue("uid_new1")) // 指纹轨与 key 轨同口径
        assertEquals(200L, uidTs.getValue("uid_new2"))
        assertFalse(uidTs.containsKey("uid_oldfmt")) // 无本体的老记录没有指纹轨
    }

    // ---------------- 老格式迁移兜底 ----------------

    @Test
    fun `老格式历史记录无条目本体仍可参与已看判重`() {
        // 行为（迁移）：老版本 history.json 只有 {"key","ts"} 没有 item 本体——
        // loadHistory 照常读出 key/ts（已看过滤需要），loadHistoryItems 跳过无本体记录
        // （历史源回放需要本体），loadSeenIdentities 把老记录并入判重底账
        storeDir.mkdirs()
        File(storeDir, "history.json").writeText(
            """[{"key":"old1","ts":123},{"key":"old2","ts":100}]"""
        )
        assertEquals(listOf("old1" to 123L, "old2" to 100L), store.loadHistory())
        assertTrue("无本体的老记录不参与历史回放", store.loadHistoryItems().isEmpty())
        val (seenKeys, _) = store.loadSeenIdentities()
        assertTrue("old1" in seenKeys && "old2" in seenKeys)
    }

    @Test
    fun `新格式历史记录带完整本体可直接回放`() {
        // 行为：自 G4 修复起历史记录带完整 VideoItem——重启后（新实例）不依赖
        // 当前内存条目即可回放
        store.appendHistory(video("h1", chatId = 42L))
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        val items = fresh.loadHistoryItems()
        assertEquals(1, items.size)
        assertEquals("h1", items[0].key)
        assertEquals(42L, items[0].chatId)
        assertEquals("uid_h1", items[0].fileUniqueId)
    }

    // ---------------- 搜索历史（去重 + FIFO） ----------------

    @Test
    fun `搜索历史同词去重置顶且超上限淘汰最旧`() {
        // 行为：MAX_SEARCH_HISTORY=20——新词置顶；同词再搜去重置顶不产生重复；
        // 超 20 条截尾淘汰最旧
        store.saveSearchHistory("  ") // 空白词忽略
        assertTrue(store.loadSearchHistory().isEmpty())
        for (i in 1..25) store.saveSearchHistory("词$i")
        store.saveSearchHistory("词20") // 已在表里的词：置顶去重
        val history = store.loadSearchHistory()
        assertEquals(20, history.size)
        assertEquals("词20", history.first()) // 刚搜的词置顶
        assertEquals(1, history.count { it == "词20" }) // 无重复
        assertFalse("词1" in history) // 最旧的已被淘汰
    }

    // ---------------- 续播进度 ----------------

    @Test
    fun `续播进度同键覆盖置顶且可移除`() {
        // 行为：saveProgress 同 key 覆盖旧值并置顶（最近更新在前）；removeProgress
        // 移除存在的 key；移除不存在的 key 不碰磁盘（表内容不变）
        store.saveProgress("k1", 1000L)
        store.saveProgress("k2", 2000L)
        store.saveProgress("k1", 3000L) // 覆盖
        val map = store.loadProgressMap()
        assertEquals(2, map.size) // 同 key 不产生第二条
        assertEquals(3000L, map["k1"])
        store.removeProgress("k2")
        assertEquals(mapOf("k1" to 3000L), store.loadProgressMap())
        store.removeProgress("不存在")
        assertEquals(mapOf("k1" to 3000L), store.loadProgressMap()) // 无此记录不动磁盘
    }

    // ---------------- 观看行为事件 ----------------

    @Test
    fun `观看事件追加置顶且字段完整读回`() {
        // 行为：appendWatchEvent 新事件置顶（时间倒序），chatId/watchSec/durationSec/ts
        // 完整落盘——这是 decayedChannelWeights 的数据源，字段丢了画像就哑了
        store.appendWatchEvent(chatId = 1L, watchSec = 30, durationSec = 60)
        store.appendWatchEvent(chatId = 2L, watchSec = 1, durationSec = 30)
        val events = store.loadWatchEvents()
        assertEquals(2, events.size)
        assertEquals(2L, events[0].chatId) // 最新在前
        assertEquals(1, events[0].watchSec)
        assertEquals(30, events[0].durationSec)
        assertTrue(events[0].ts > 0L)
        assertEquals(1L, events[1].chatId)
    }

    // ---------------- 画面姿态记忆（R218 镜像建表 / R227 扩展旋转+镜像复合态） ----------------

    @Test
    fun `画面姿态复合态落盘可跨实例读回`() {
        // 行为：旋转+镜像组合态全量落盘 mirrored.json——重启后（新实例，绕开内存缓存
        // 强制走磁盘）逐 key 恢复复合态；纯旋转条目/纯镜像条目/组合条目三形态都保真
        assertTrue("无文件时按无姿态记忆处理", store.loadFrameAdjust().isEmpty())
        store.saveFrameAdjust(
            mapOf(
                "k1" to StoredFrameAdjust(rotationDeg = 90, mirrored = false),
                "k2" to StoredFrameAdjust(rotationDeg = 0, mirrored = true),
                "k3" to StoredFrameAdjust(rotationDeg = 270, mirrored = true),
            ),
        )
        val saved = store.loadFrameAdjust()
        assertEquals(StoredFrameAdjust(90, false), saved["k1"])
        assertEquals(StoredFrameAdjust(0, true), saved["k2"])
        assertEquals(StoredFrameAdjust(270, true), saved["k3"])
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        assertEquals("跨实例（=跨会话）读回完整复合态", 3, fresh.loadFrameAdjust().size)
        assertEquals(StoredFrameAdjust(270, true), fresh.loadFrameAdjust()["k3"])
        // 恢复默认（reset 的落盘侧）：条目从表移除后落盘，重启后该 key 无记忆
        val rest = fresh.loadFrameAdjust() - "k3"
        fresh.saveFrameAdjust(rest)
        val after = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        assertNull(after.loadFrameAdjust()["k3"])
        assertEquals(2, after.loadFrameAdjust().size)
    }

    @Test
    fun `R218旧数组格式读侧自动迁移为纯镜像态`() {
        // 行为：旧版 mirrored.json 是 item.key 字符串数组（R218 落盘格式）——
        // 读侧迁移为 {r=0, m=true}，老用户翻转记忆零丢失；下次保存升级新格式
        storeDir.mkdirs()
        File(storeDir, "mirrored.json").writeText("""["old1","old2"]""")
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        val migrated = fresh.loadFrameAdjust()
        assertEquals(StoredFrameAdjust(0, true), migrated["old1"])
        assertEquals(StoredFrameAdjust(0, true), migrated["old2"])
        // 保存后格式升级：重开实例读回同值（新格式链路）
        fresh.saveFrameAdjust(migrated)
        val after = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        assertEquals(StoredFrameAdjust(0, true), after.loadFrameAdjust()["old1"])
    }

    @Test
    fun `画面姿态表损坏按空处理且可覆写恢复`() {
        // 行为：半截 JSON/非 JSON 内容不崩溃——按无姿态记忆处理；覆写后恢复正常
        storeDir.mkdirs()
        File(storeDir, "mirrored.json").writeText("这不是 json {{{")
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        assertTrue(fresh.loadFrameAdjust().isEmpty())
        fresh.saveFrameAdjust(mapOf("m9" to StoredFrameAdjust(180, true)))
        assertEquals(StoredFrameAdjust(180, true), fresh.loadFrameAdjust()["m9"])
    }

    @Test
    fun `默认姿态条目落盘剔除不膨胀`() {
        // 行为：r=0 且未镜像的条目保存时剔除（表里只存有调整的条目）；
        // 角度落盘前归一（360/负角不外溢给读侧）
        store.saveFrameAdjust(
            mapOf(
                "k1" to StoredFrameAdjust(0, false),   // 默认态：不入盘
                "k2" to StoredFrameAdjust(360, false), // 归一 0 且未镜像：不入盘
                "k3" to StoredFrameAdjust(90, false),  // 保留
            ),
        )
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        val loaded = fresh.loadFrameAdjust()
        assertNull(loaded["k1"])
        assertNull(loaded["k2"])
        assertEquals(StoredFrameAdjust(90, false), loaded["k3"])
    }
}
