package dev.tgfeed.ui

import android.app.Application
import android.graphics.Bitmap
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.playback.ShareCard
import dev.tgfeed.core.tastemap.ChannelEraPolicy
import dev.tgfeed.core.tastemap.GalaxyPorthole
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import org.json.JSONObject
import java.io.File

/**
 * R301 星图舷窗/行星世界卡 装载层（ui 包根——TasteVoyageStore 同位惯例）：
 * 舷窗 sheet（feed 侧）与世界卡（星图侧）共享同一邻域口径的取数/缓存/账本。
 *
 * 四族职责（全只读零 Search 族——R184 红线；**全部请在 IO 线程调用**）：
 *  1. **邻域小装配** [loadNeighborhood]：TdLibGateway.discoveryGraph 快照 →
 *     GalaxyPorthole 合并/布局（纯函数收口）；演示模式/无图谱=空邻域诚实降级
 *    （中心星独奏——设计 §2.2「星图从未开过也能出舷窗」）。
 *  2. **tag 时期弧** [loadTagEra]：search.db OPEN_READONLY 有界查询
 *    （loadChannelEraDigest 同款纪律——绝不建库绝不写库；SQL 形状=GalaxyPorthole
 *     .tagEraSql 机检锚单一出处）。
 *  3. **快照位图磁盘缓存** [portholeSnapshot]：ShareCard 同源绘制件
 *    （drawTasteMapDust 底幕 + drawTasteMapField 点云/边/焦点邻边高亮——海报与 App
 *     同一套笔触的第三消费位）；key=GalaxyPorthole.snapshotCacheKey 内容指纹（R254
 *     指纹族思路——邻域内容变才重渲）；filesDir/galaxy/porthole/ 自持目录 LRU 24 张。
 *  4. **跨页呼应账本** [markSeen]/[consumeWelcomes]：舷窗看过的频道落账
 *    （porthole_seen.json，galaxy 自持目录——TasteVoyageLog 同款纪律，LocalStore
 *     零改动）；下次进星图时在图星脉动欢迎（R282 导演器 flashStar 原语的消费源）。
 */
internal object GalaxyPortholeLoader {

    // ==================== 邻域小装配 ====================

    /** 邻域装载成品（布局 + pills 数据源；neighbors=合并权重降序全表，pills 截断归 UI） */
    class Neighborhood(
        val layout: GalaxyPorthole.PortholeLayout,
        val neighbors: List<Pair<Long, Int>>,
    )

    /**
     * 以 [centerChatId] 为根的 1-2 跳邻域小装配（**IO 线程**；O(邻域) 毫秒级——
     * 图谱快照是内存投影，合并/布局纯函数）。演示模式（非 TdLibGateway）=空邻域。
     */
    fun loadNeighborhood(
        gateway: TdGateway,
        centerChatId: Long,
        centerTitle: String,
    ): Neighborhood {
        val real = gateway as? TdLibGateway
            ?: return Neighborhood(
                GalaxyPorthole.layoutNeighborhood(centerChatId, centerTitle, emptyList(), emptyMap()),
                emptyList(),
            )
        val snap = runCatching { real.discoveryGraph.snapshot() }.getOrNull()
            ?: return Neighborhood(
                GalaxyPorthole.layoutNeighborhood(centerChatId, centerTitle, emptyList(), emptyMap()),
                emptyList(),
            )
        val raw = snap.edges.map { GalaxyPorthole.RawEdge(it.fromChatId, it.toChatId, it.type, it.count) }
        val neighbors = GalaxyPorthole.mergedNeighborWeights(centerChatId, raw)
        val titles = HashMap<Long, String>(snap.nodes.size * 2)
        for (n in snap.nodes) if (n.title.isNotBlank()) titles[n.chatId] = n.title
        val keep = neighbors.take(GalaxyPorthole.MAX_NEIGHBORS).mapTo(HashSet()) { it.first }
        val secondHop = GalaxyPorthole.secondHopPairs(raw, keep, centerChatId)
        return Neighborhood(
            GalaxyPorthole.layoutNeighborhood(centerChatId, centerTitle, neighbors, titles, secondHop),
            neighbors,
        )
    }

    // ==================== tag 时期弧 ====================

    /** (频道, tag) 时期弧成品：总条数（索引口径）+近 12 月柱+峰月判词；空=无证据 */
    class TagEraInfo(
        val total: Int,
        val monthly: List<ChannelEraPolicy.MonthCount>,
        val peakLabel: String?,
    )

    /**
     * (频道, tag) 时期弧装载（**IO 线程**；search.db OPEN_READONLY——绝不建库写库）：
     * 发布秒表 → GalaxyPorthole.tagMonthly 月分桶。tag 折叠键=SearchTextKit.fold
     * （tags 表 tag 列同一把尺子——R228/R236 口径）。库缺席/异常/空=TagEraInfo(0)
     * 诚实空态（调用方整段不渲染）。
     */
    fun loadTagEra(app: Application, chatId: Long, tag: String): TagEraInfo {
        val key = SearchTextKit.fold(tag.trim().trimStart('#', '＃'))
        if (key.isEmpty() || chatId == 0L) return TagEraInfo(0, emptyList(), null)
        val dbFile = File(File(app.filesDir, "deepindex"), "search.db")
        if (!dbFile.isFile) return TagEraInfo(0, emptyList(), null)
        val secs = runCatching {
            val d = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            try {
                val out = ArrayList<Long>(256)
                d.rawQuery(
                    GalaxyPorthole.tagEraSql(),
                    arrayOf(key, chatId.toString()),
                ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
                out
            } finally {
                runCatching { d.close() }
            }
        }.getOrNull() ?: return TagEraInfo(0, emptyList(), null)
        val arr = LongArray(secs.size) { secs[it] }
        val monthly = GalaxyPorthole.tagMonthly(arr)
        return TagEraInfo(arr.size, monthly, GalaxyPorthole.peakMonthLabel(monthly))
    }

    // ==================== tag 行星标记（真相位——TasteTagPlanets 同源几何） ====================

    /** 舷窗 tag 行星标记原料：确定性相位/轨道比/大小比/账本条数（null=该频道无此行星） */
    class PlanetMarker(
        val phase01: Float,
        val orbitR01: Float,
        val sizeR01: Float,
        val count: Int,
    )

    /**
     * 行星标记装载（**IO 线程**）：channelItemsPage 240+topTags 24 两索引查询
     * （assemblePlanetSystemR254 同一对公共只读口，毫秒级）+ TasteTagPlanets.build
     * 同源构建——舷窗上的 #tag 行星高亮=星图内该行星的同一确定性相位/轨道（不是
     * 摆拍标记）。无 tag/装配空=null（诚实缺省，舷窗不画标记）。
     */
    fun loadPlanetMarker(vm: FeedViewModel, chatId: Long, tag: String): PlanetMarker? {
        if (chatId == 0L || tag.isBlank()) return null
        return runCatching {
            val items = vm.deepIndex.channelItemsPage(chatId, TasteTagPlanets.SAMPLE_LIMIT, 0)
            val ledger = vm.deepIndex.topTags(chatId, TasteTagPlanets.MAX_PLANETS)
            val system = TasteTagPlanets.build(chatId, items, ledger)
            val fold = SearchTextKit.fold(tag)
            val p = system.planets.firstOrNull { SearchTextKit.fold(it.tag) == fold } ?: return null
            PlanetMarker(p.phase01, p.orbitR01, p.sizeR01, p.count)
        }.getOrNull()
    }

    // ==================== 快照位图磁盘缓存（R254 指纹族） ====================

    /** 快照位图画布（16:10——半屏 sheet 内的微缩星图视窗） */
    private const val SNAP_W = 1080
    private const val SNAP_H = 675

    /** 缓存上限（超出整体清目录重建——舷窗图是小件，简单收口） */
    private const val SNAP_CACHE_MAX = 24

    private fun snapshotDir(app: Application): File = File(File(app.filesDir, "galaxy"), "porthole")

    /**
     * 舷窗邻域静态快照（**IO 线程**）：磁盘缓存命中=解码直出；未命中=ShareCard 同源
     * 绘制（正俯视 yaw=0/pitch=0——z 恒 0 的邻域图是正交感平面云图）+原子落盘。
     * 位图缓存 key=内容指纹（[GalaxyPorthole.snapshotCacheKey]）：邻域不变即秒开，
     * 变了才重渲——feed 侧每帧零渲染成本（Compose 只持一张位图）。
     */
    fun portholeSnapshot(
        app: Application,
        layout: GalaxyPorthole.PortholeLayout,
        tag: String?,
        neighbors: List<Pair<Long, Int>>,
    ): Bitmap? {
        val key = GalaxyPorthole.snapshotCacheKey(layout.centerChatId, tag, neighbors)
        val file = File(snapshotDir(app), "ph_$key.png")
        runCatching {
            if (file.isFile) {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) return bmp
            }
        }
        val bmp = runCatching { renderSnapshot(layout) }.getOrNull() ?: return null
        runCatching {
            val dir = snapshotDir(app)
            dir.mkdirs()
            // LRU 收口：超限按 mtime 留新（R254 族陈值无害——清了只是下次重渲）
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            if (files.size >= SNAP_CACHE_MAX) {
                files.drop(SNAP_CACHE_MAX - 1).forEach { f -> runCatching { f.delete() } }
            }
            val tmp = File(dir, "ph_$key.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) {
                runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                tmp.delete()
            }
        }
        return bmp
    }

    /**
     * 快照渲染（ShareCard 同源绘制件）：深空星点底幕 + 点云/边（焦点=中心星——
     * drawTasteMapField 焦点邻边高亮两遍制白捡「所在恒星的关联弧」）。名签只挂
     * top-3 权重邻星（微缩图密度纪律——避让放置共享件会再淘汰撞车者）。
     */
    private fun renderSnapshot(layout: GalaxyPorthole.PortholeLayout): Bitmap {
        val bmp = Bitmap.createBitmap(SNAP_W, SNAP_H, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        ShareCard.drawTasteMapDust(canvas, 0f, 0f, SNAP_W.toFloat(), SNAP_H.toFloat())
        val scale = (minOf(SNAP_W, SNAP_H) / 2f * GalaxyPorthole.SNAPSHOT_FIT) / GalaxyPorthole.FRAME_EXTENT
        val labelIds = layout.nodes.drop(1).sortedByDescending { it.weight }.take(3).mapTo(HashSet()) { it.chatId }
        val stars = layout.nodes.map { n ->
            ShareCard.TasteMapStar(
                x = n.x, y = n.y, z = 0f,
                radius = n.radius, colorArgb = n.colorArgb, alpha = 1f,
                label = if (n.chatId in labelIds && n.title.isNotBlank()) n.title else null,
                chatId = n.chatId, hasComp = true,
            )
        }
        val edges = layout.edges.map { e ->
            val a = layout.nodes[e.aIdx]
            val b = layout.nodes[e.bIdx]
            ShareCard.TasteMapEdge(
                a.x, a.y, b.x, b.y, 0f, 0f, a.chatId, b.chatId, e.alpha,
            )
        }
        ShareCard.drawTasteMapField(
            canvas, stars, edges,
            yawDeg = 0f, pitchDeg = 0f, scale = scale,
            centerX = SNAP_W / 2f, centerY = SNAP_H / 2f,
            labelMinPx = 6f, labelTextSize = 26f,
            focusId = layout.centerChatId,
        )
        return bmp
    }

    // ==================== 跨页呼应账本（舷窗看过的星 → 星图进场脉动欢迎） ====================

    /** 账本文件（galaxy 自持目录——TasteVoyageLog 同款纪律，LocalStore 零改动） */
    private const val SEEN_FILE = "porthole_seen.json"

    /** 呼应有效期（7 天——「最近在 feed 里看过」的语义窗） */
    private const val WELCOME_TTL_MS = 7L * 86_400_000L

    /** 账本容量（LRU 留新——只记最近看过的 64 颗） */
    private const val SEEN_CAP = 64

    private fun seenFile(app: Application): File = File(File(app.filesDir, "galaxy"), SEEN_FILE)

    /** 舷窗打开即落账（**IO 线程**；原子写——tmp+rename 族） */
    fun markSeen(app: Application, chatId: Long) {
        if (chatId == 0L) return
        runCatching {
            val map = loadSeenMap(app)
            map[chatId] = System.currentTimeMillis()
            // LRU 留新收口
            while (map.size > SEEN_CAP) {
                val oldest = map.minByOrNull { it.value }?.key ?: break
                map.remove(oldest)
            }
            writeSeenMap(app, map)
        }
    }

    /**
     * 星图进场消费（**IO 线程**）：在图且 7 天内舷窗看过的星——取出即清（一次性
     * 呼应：同星下次进场不再欢迎，直到舷窗再看它）。返回 chatId 表（调用方
     * engine.flashStar 逐星脉动）。
     */
    fun consumeWelcomes(app: Application, inMapIds: Set<Long>): List<Long> {
        if (inMapIds.isEmpty()) return emptyList()
        return runCatching {
            val map = loadSeenMap(app)
            if (map.isEmpty()) return emptyList()
            val now = System.currentTimeMillis()
            val hits = map.filter { (id, ts) -> id in inMapIds && now - ts < WELCOME_TTL_MS }.keys.toList()
            if (hits.isNotEmpty()) {
                hits.forEach { map.remove(it) }
                writeSeenMap(app, map)
            }
            hits
        }.getOrDefault(emptyList())
    }

    private fun loadSeenMap(app: Application): java.util.LinkedHashMap<Long, Long> {
        val out = java.util.LinkedHashMap<Long, Long>()
        runCatching {
            val text = seenFile(app).takeIf { it.isFile }?.readText() ?: return out
            val root = JSONObject(text)
            val arr = root.optJSONArray("seen") ?: return out
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    out[o.getLong("c")] = o.getLong("t")
                }
            }
        }
        return out
    }

    private fun writeSeenMap(app: Application, map: Map<Long, Long>) {
        runCatching {
            val dir = File(app.filesDir, "galaxy")
            dir.mkdirs()
            val root = JSONObject()
            val arr = org.json.JSONArray()
            for ((id, ts) in map) {
                arr.put(JSONObject().put("c", id).put("t", ts))
            }
            root.put("seen", arr)
            val file = seenFile(app)
            val tmp = File(dir, "$SEEN_FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                runCatching { file.writeText(root.toString()) }
                tmp.delete()
            }
        }
    }
}
