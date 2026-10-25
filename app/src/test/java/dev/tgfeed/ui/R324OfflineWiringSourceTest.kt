package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R324 离线降级矩阵形状源码锚（R317bWiringSourceTest 同款 user.dir 源定位模式——
 * UI 接线无 JVM 直测面，以源锚锁矩阵「七面×三档」的结构件；行为半体由
 * NetworkWatchCoreTest/OfflineDegradePolicyTest/SnapshotSavedAtStoreTest 承重）：
 *  - **单源不变式**：core/net 全域 registerDefaultNetworkCallback 恰一处
 *    （NetworkWatch——第二处系统回调即红：R195「全进程一个监听者」不变式）；
 *  - **七面在场锚**：feed banner+空态/频道页 banner/深搜注/播放器离线分诊+快终态
 *    +稍后再看槽/多设备同步行+禁用态——全部消费 OfflineDegradePolicy 单源文案；
 *  - **时点链锚**：FeedSnapshot.savedAtMs 三位一体（字段/写侧 ts 既有/读侧 optLong）；
 *  - **零改动面锚**：时光机/星图（可用档零改动哲学）与网关域（发送口零新开红线）
 *    不出现 NetworkWatch/OfflineDegradePolicy 引用。
 */
class R324OfflineWiringSourceTest {

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

    private fun src(rel: String): String {
        val file = File(mainSourceRoot, rel)
        assertTrue("源文件必须存在：$rel", file.isFile)
        return file.readText()
    }

    private fun allKotlinFiles(root: File): List<File> {
        val out = ArrayList<File>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { out.add(it) }
        return out
    }

    // ==================== 单源不变式（系统回调唯一监听者） ====================

    @Test
    fun `单源-core全域系统默认网络回调恰一处`() {
        // 只认真实注册调用语法（"registerDefaultNetworkCallback(object"）——R195 注释
        // 里提及的历史字样（FeedViewModel KDoc「取代 VM 私有 registerDefaultNetworkCallback」
        // 等）不算第二处
        val hits = allKotlinFiles(mainSourceRoot)
            .filter { it.readText().contains("registerDefaultNetworkCallback(object") }
        assertEquals(
            "registerDefaultNetworkCallback 真实调用全仓必须恰一处（NetworkWatch 单源——" +
                "R195「全进程一个监听者」不变式，第二处即打回）",
            listOf("dev/tgfeed/core/net/NetworkWatch.kt"),
            hits.map { mainSourceRoot.toPath().relativize(it.toPath()).toString().replace('\\', '/') },
        )
    }

    @Test
    fun `单源-NetworkMeteredBus委托且公开面零变化`() {
        val t = src("dev/tgfeed/core/net/NetworkMeteredBus.kt")
        // 委托：计量真值随 NetworkWatch 走
        assertTrue("NetworkMeteredBus 未委托 NetworkWatch", t.contains("val state: StateFlow<Boolean> get() = NetworkWatch.metered"))
        assertTrue("metered 便捷读未委托", t.contains("val metered: Boolean get() = NetworkWatch.metered.value"))
        // 公开面四口保持（API 只加不改）：start/readMeteredNow 在场且 start 链式带起
        assertTrue("start 链式带起丢失", t.contains("NetworkWatch.start(context)"))
        assertTrue("readMeteredNow 自持实现丢失", t.contains("fun readMeteredNow(context: Context): Boolean"))
    }

    // ==================== 时点链（FeedSnapshot.savedAtMs 三位一体） ====================

    @Test
    fun `时点链-savedAtMs字段与ts读写同位`() {
        val t = src("dev/tgfeed/core/store/LocalStore.kt")
        assertTrue("FeedSnapshot.savedAtMs 字段丢失", t.contains("val savedAtMs: Long = 0L"))
        assertTrue("写侧 ts 落盘丢失（既有行为）", t.contains("""put("ts", System.currentTimeMillis())"""))
        assertTrue("读侧 ts 补读丢失（R324 本笔）", t.contains("""root.optLong("ts", 0L)"""))
        // 两快照族共用 parseSnapshot（R170b 收编口径不破）：两个加载口各自在场
        assertTrue(
            "feed 快照解析口未走 parseSnapshot 共享件",
            t.contains("parseSnapshot(runCatching"),
        )
        assertTrue(
            "关注流快照解析口未走 parseSnapshot 共享件",
            t.contains("parseSnapshot(root ="),
        )
    }

    // ==================== 七面在场锚（降级矩阵接线） ====================

    @Test
    fun `feed面-banner与空态离线行在场`() {
        val t = src("dev/tgfeed/ui/FeedScreen.kt")
        assertTrue("feed 离线 banner 决策未接", t.contains("feedOfflineBannerLine("))
        assertTrue("feed 空态离线副文案未接", t.contains("feedOfflineEmptyHint(networkOnline)"))
        assertTrue("FeedEmptyState offlineHint 槽丢失", t.contains("offlineHint: String? = null"))
        assertTrue("banner 视觉件 OfflineHintPill 未用", t.contains("OfflineHintPill("))
        assertTrue("时点=max(快照,最近到货)口径丢失", t.contains("maxOf(snapshotSavedAtMs, lastFreshAtMs)"))
    }

    @Test
    fun `频道页面-banner在场`() {
        val t = src("dev/tgfeed/ui/ChannelHomeScreen.kt")
        assertTrue("频道页离线 banner 决策未接", t.contains("channelHomeOfflineLine(networkOnline)"))
        assertTrue("频道页未用共享视觉件", t.contains("OfflineHintPill("))
    }

    @Test
    fun `深搜面-等待后缀与图例注在场`() {
        val t = src("dev/tgfeed/ui/SearchScreen.kt")
        assertTrue("搜索等待离线后缀未接", t.contains("searchOfflineSuffix(networkOnline)"))
        assertTrue("搜索图例离线注未接", t.contains("searchLegendOfflineNote(networkOnline)"))
    }

    @Test
    fun `播放器面-离线分诊三件在场`() {
        val listener = src("dev/tgfeed/ui/components/VideoPlayerListener.kt")
        assertTrue("onPlayerError 离线分诊未接", listener.contains("playerOfflineFatalText(offlineNow)"))
        assertTrue("离线终态不记死链（零编造）丢失", listener.contains("不记死链"))
        assertTrue("离线期换源重下闸丢失", listener.contains("if (!offlineNow && isCorruptContentError"))
        val chain = src("dev/tgfeed/ui/components/VideoRetryChain.kt")
        assertTrue("重试链离线短路闸未接", chain.contains("playerOfflineFatalText("))
        assertTrue("重试链离线终态置位丢失", chain.contains("offlineTerminal"))
        val overlay = src("dev/tgfeed/ui/components/VideoPageOverlays.kt")
        assertTrue("LoadFailedOverlay offline 槽丢失", overlay.contains("offline: Boolean = false"))
        assertTrue("LoadFailedOverlay 稍后再看槽丢失", overlay.contains("onWatchLater: (() -> Unit)? = null"))
        assertTrue("离线终态可点（环境态≠内容坏）丢失", overlay.contains("if (fatal == null || offline)"))
        val page = src("dev/tgfeed/ui/components/VideoPage.kt")
        assertTrue("VideoPage 未透传 offline", page.contains("offline = !networkOnline"))
        assertTrue("VideoPage 未透传稍后再看", page.contains("onWatchLater = { onWatchLater(item) }"))
    }

    @Test
    fun `多设备同步面-离线行与禁用态在场`() {
        val t = src("dev/tgfeed/ui/SettingsScreen.kt")
        assertTrue("云同步离线行未接", t.contains("syncOfflineLine(networkOnline)"))
        assertTrue("立即同步离线禁用态丢失", t.contains("clickable(enabled = progressSyncEnabled && networkOnline)"))
    }

    @Test
    fun `VM面-三流与时点戳在场`() {
        val t = src("dev/tgfeed/core/feed/FeedViewModel.kt")
        assertTrue("networkOnline 流丢失", t.contains("val networkOnline: StateFlow<Boolean>"))
        assertTrue("snapshotSavedAtMs 流丢失", t.contains("val snapshotSavedAtMs: StateFlow<Long>"))
        assertTrue("lastFreshAtMs 流丢失", t.contains("val lastFreshAtMs: StateFlow<Long>"))
        assertTrue("快照上屏时点捕获丢失", t.contains("_snapshotSavedAtMs.value = snap.savedAtMs"))
        assertTrue("loadMore 到货时点戳丢失", t.contains("_lastFreshAtMs.value = System.currentTimeMillis()"))
        assertTrue("NetworkWatch 订阅未接", t.contains("NetworkWatch.online.collect { _networkOnline.value = it }"))
        // 关注流快照垫底路径同口径供数（FeedSourcePart）
        val part = src("dev/tgfeed/core/feed/FeedSourcePart.kt")
        assertTrue("关注流快照时点捕获丢失", part.contains("_snapshotSavedAtMs.value = snap.savedAtMs"))
    }

    // ==================== 零改动面锚（矩阵「可用/零改动」档的红线） ====================

    @Test
    fun `零改动面-时光机星图网关域不引离线检测`() {
        // 时光机（纯本地索引直出）与星图（全本地）=可用档零改动（N2 哲学：已离线的
        // 面零改动）；网关域零接触（§九发送口零新开——本包零 client.send 新增）
        for (rel in listOf(
            "dev/tgfeed/ui/TimeMachineScreen.kt",
            "dev/tgfeed/ui/TasteMapScreen.kt",
            "dev/tgfeed/core/td/TdLibGateway.kt",
            "dev/tgfeed/core/td/GatewayFeedFetch.kt",
        )) {
            val t = src(rel)
            assertFalse(
                "$rel 不得引入离线检测（零改动面红线——星图归 R319/网关发送口零新开）",
                t.contains("NetworkWatch") || t.contains("OfflineDegradePolicy"),
            )
        }
    }

    @Test
    fun `装机链-App经MeteredBus链式带起零App改动`() {
        // App.kt 不新增 start（NetworkMeteredBus.start 链式带起 NetworkWatch——热点
        // 文件 App.kt 零触碰）；NetworkWatch 幂等装机+初值现读在场
        val app = src("dev/tgfeed/App.kt")
        assertFalse("App.kt 不应直连 NetworkWatch（装机走总线链）", app.contains("NetworkWatch"))
        val watch = src("dev/tgfeed/core/net/NetworkWatch.kt")
        assertTrue("幂等闸丢失", watch.contains("if (started) return"))
        assertTrue("初值现读丢失", watch.contains("readOnlineNow(app)"))
        assertTrue("离线语义快照 NetLinkState 丢失", watch.contains("data class NetLinkState"))
    }
}
