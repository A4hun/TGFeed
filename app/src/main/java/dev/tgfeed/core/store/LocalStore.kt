package dev.tgfeed.core.store

import android.content.Context
import android.util.Log
import dev.tgfeed.core.feed.ChannelCollection
import dev.tgfeed.core.feed.PlayQueue
import dev.tgfeed.core.feed.PlayQueueEntry
import dev.tgfeed.core.feed.PlayQueueOrder
import dev.tgfeed.core.td.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 本地持久化层：收藏 / 点赞 / 观看历史。
 *
 * 只用 Android 内置 org.json + java.io，若干 JSON 文件放在
 * `context.filesDir/store/` 下：
 *  - favorites.json：完整 VideoItem 数组（收藏时间倒序，最新在前）
 *  - likes.json：item.key 字符串数组
 *  - history.json：[HistoryEntry] 数组（{"key","ts","item"}，item 为完整 VideoItem JSON，
 *    序列化规则同收藏；老格式记录无 item 字段，仅作 key 名单供"已看过滤"），
 *    按时间倒序，上限 [MAX_HISTORY] 条
 *  - seen_ids.log：已看身份注册表（"已看内容不再出现"的判重底账，append-only 行文本，
 *    每行 "key\tfileUniqueId"）。独立于 history.json 另起一本账的原因：
 *    history 上限 [MAX_HISTORY]=500 且存完整条目（供历史源回放），重度用户几天顶满，
 *    被淘汰的旧记录一出表"已看过滤"即失忆、老内容被当成新内容再推（尤其深度索引
 *    回溯通道专门挖老内容）——本注册表只存身份（key + 内容指纹双轨），单条 ~45B，
 *    容量 [MAX_SEEN_IDS]=100000，追加写 O(1) 不重写全文件，杀进程也不丢
 *    （写透：每条记录当场追加落盘）
 *  - subscriptions.json：[Subscription] 数组，含各频道追更进度 lastSeenMessageId
 *  - watchlater.json：完整 VideoItem 数组（加入时间倒序，最新在前），序列化规则同收藏
 *  - blocked_channels.json：被屏蔽频道的 chatId Long 数组
 *  - disliked.json："不感兴趣"的 item.key 字符串数组（视频粒度黑名单，风格同 likes.json）
 *  - mirrored.json：画面姿态记忆表（R218 建表/R227 扩展为复合态）：
 *    `{"<item.key>": {"r": 旋转角度, "m": 是否镜像}}`——旋转+镜像组合态每条视频独立、
 *    跨会话保持（R218 旧格式为 item.key 字符串数组=纯镜像，读侧自动迁移）
 *  - dislike_count.json："不感兴趣"计数表（JSONObject：计数键 → 累计次数）；
 *    计数键优先取内容指纹 fileUniqueId，无指纹的条目回落 item.key
 *  - blocked_items.json：永久屏蔽表（[BlockedItem] 数组，屏蔽时间倒序）；
 *    连续 ≥2 次"不感兴趣"的内容进本表，按 fileUniqueId（内容指纹）匹配，
 *    同一视频被转发到别的频道（消息 id 不同）也屏蔽
 *  - watch_events.json：[WatchEvent] 数组（观看行为事件，时间倒序，上限 [MAX_WATCH_EVENTS] 条）
 *  - collections.json：[{"name","channelIds":[Long...]}] 用户自组频道合集（名称为主键）
 *  - watch_progress.json：[{"key","posMs","ts"}] 长视频续播进度，最近更新在前，上限 [MAX_WATCH_PROGRESS] 条
 *  - search_history.json：搜索历史字符串数组（新词置顶，上限 [MAX_SEARCH_HISTORY] 条）
 *  - downloads.json：[DownloadRecord] 数组（下载完成登记，新纪录在前；不设上限，
 *    记录与用户相册文件一一对应，由"下载管理页"手动删除，不做静默淘汰）
 *  - playlists.json：[PlaylistMeta] 数组（播放列表元数据：id/name/itemKeys/createdAt/updatedAt，
 *    顺序即创建顺序）；itemKeys 是条目清单的轻量镜像（计数/卡片展示免解析大文件）
 *  - playlist_<id>.json：每个片单一个文件，完整 VideoItem 数组（序列化规则同收藏），
 *    顺序即片单内连播顺序（追加在末尾）
 *  - play_queue.json：播放队列（R387 节目单，一期=单队列实体）：
 *    {name, orderMode, entries:[{type:"channel",id,chatId,title,skipped} |
 *    {type:"item",id,item:完整VideoItem,skipped}], updatedAt, resumeEntryId, resumeKey}；
 *    频道条目轻存 chatId+title（取数现拉），单条条目完整 VideoItem（同收藏 Codec，离线可播）；
 *    队列级断点双字段（播到哪一条，开单跳回用）
 *  - discovery_candidates.json：发现页候选状态表（[DiscoveryCandidateState] 数组：
 *    软拒绝梯度 dismissCount/lastDismissTs（30 天 TTL，二次弃升级永久屏蔽）
 *    + 曝光计数 impressions + 漏斗归因 lastTasteTs/acceptedTs（DISCOVER-UPGRADE P1-a），
 *    DISCOVER-DESIGN §8.2）
 *  - discovery_arms.json：发现来源臂统计表（臂名 → [DiscoveryArmStat] 的 α/β 两浮点，
 *    Thompson 采样先验的落盘底账，DISCOVER-UPGRADE P1-a #3；本轮只建表与收/弃挂钩点，
 *    采样消费在 P1-c）
 *  - exposure.json：推荐赛马曝光表（JSONObject：item.key → {"e":累计进排序页次数,"t":最近曝光毫秒}，
 *    ALGO P1 流量池赛马；上限 [MAX_EXPOSURE_RECORDS] 条，超出按最近曝光时间淘汰最旧）
 *
 * 所有方法都是同步阻塞 IO，调用方（FeedViewModel）负责切到 Dispatchers.IO。
 * 公开方法均加 @Synchronized，文件写采用"临时文件 + rename"，避免崩溃留下半个 JSON。
 *
 * 性能：每个 loadXxx 都带 @Volatile 内存缓存（写时失效重建）。
 * 优化前 WeightedAggregateFeedSource 每翻一页就要 loadBlockedChannels +
 * loadDisliked + loadHistory + loadWatchEvents 共 4 次"读盘 + JSON 全量解析"，
 * 翻页越快磁盘抖动越明显；缓存后读路径全部变为纯内存引用返回。
 * mtime 校验刻意省略：本类是这些文件的唯一写入方（单写入方），
 * 进程内缓存不可能与磁盘失配；多实例场景（多进程）不存在。
 * 注意：缓存命中时返回的是共享实例，调用方一律只读（现有调用方均已核实）。
 */
class LocalStore(context: Context) {

    private val dir = File(context.filesDir, "store")

    /**
     * store 目录的正式访问口（R123 新增）：供需要与同目录文件打交道的组件
     *（如 LinUCB 臂参数/propensity 日志）取目录用。此前 LinUCB 靠反射读私有
     * [dir] 字段，release 构建 R8 改名字段后解析静默失败、功能退化为纯内存
     *（重启丢臂参数）——属 release-only 地雷，故开正式口、删反射桥。
     * 只读暴露目录本身，文件读写仍走本类方法（单写入方口径不变）。
     */
    internal val storageDir: File get() = dir
    private val favoritesFile = File(dir, "favorites.json")
    private val likesFile = File(dir, "likes.json")
    private val historyFile = File(dir, "history.json")
    private val subscriptionsFile = File(dir, "subscriptions.json")
    private val watchLaterFile = File(dir, "watchlater.json")
    private val blockedChannelsFile = File(dir, "blocked_channels.json")
    private val dislikedFile = File(dir, "disliked.json")
    // R413 关键词屏蔽：用户自定义屏蔽词（字符串数组；归一/去重由 KeywordMutePolicy 负责）
    private val mutedKeywordsFile = File(dir, "muted_keywords.json")
    // 广告永久屏蔽：不感兴趣计数表（计数键→次数）+ 内容指纹永久屏蔽表
    private val dislikeCountFile = File(dir, "dislike_count.json")
    private val blockedItemsFile = File(dir, "blocked_items.json")
    private val watchEventsFile = File(dir, "watch_events.json")
    private val progressFile = File(dir, "watch_progress.json")
    private val searchHistoryFile = File(dir, "search_history.json")
    private val downloadsFile = File(dir, "downloads.json")
    // 口味词表（口味雷达的 caption 内容词积累）：word → 累计得分
    private val tasteWordsFile = File(dir, "taste_words.json")
    // 发现页候选状态表（软拒绝梯度 + 曝光计数 + 试吃/收下归因戳，DISCOVER-DESIGN §8.2 + DISCOVER-UPGRADE P1-a）
    private val discoveryCandidatesFile = File(dir, "discovery_candidates.json")
    // 发现来源臂统计表（臂名 → α/β，Thompson 采样先验底账，DISCOVER-UPGRADE P1-a #3）
    private val discoveryArmsFile = File(dir, "discovery_arms.json")
    // 推荐赛马曝光表（item.key → 累计曝光次数/最近曝光毫秒，ALGO P1 流量池赛马）
    private val exposureFile = File(dir, "exposure.json")
    // 画面姿态记忆表（R218 建表/R227 扩展复合态）：旋转+镜像组合态，见 loadFrameAdjust
    private val mirroredFile = File(dir, "mirrored.json")
    // 已看身份注册表（append-only 日志，格式与存在的理由见类头注释）
    private val seenIdsFile = File(dir, "seen_ids.log")

    // ---------------- 内存缓存（写时失效重建） ----------------
    // @Volatile 保证跨线程可见性；读写均在 @Synchronized 方法内，赋值即发布。
    @Volatile private var favoritesCache: List<VideoItem>? = null
    @Volatile private var watchLaterCache: List<VideoItem>? = null
    @Volatile private var blockedChannelsCache: Set<Long>? = null
    @Volatile private var mutedKeywordsCache: Set<String>? = null
    @Volatile private var watchEventsCache: List<WatchEvent>? = null
    @Volatile private var likesCache: Set<String>? = null
    @Volatile private var dislikedCache: Set<String>? = null
    @Volatile private var dislikeCountCache: Map<String, Int>? = null
    @Volatile private var blockedItemsCache: List<BlockedItem>? = null
    @Volatile private var historyCache: List<HistoryEntry>? = null
    @Volatile private var subscriptionsCache: List<Subscription>? = null
    @Volatile private var progressCache: List<ProgressEntry>? = null
    @Volatile private var searchHistoryCache: List<String>? = null
    @Volatile private var downloadsCache: List<DownloadRecord>? = null
    @Volatile private var exposureCache: Map<String, ExposureRecord>? = null
    @Volatile private var frameAdjustCache: Map<String, StoredFrameAdjust>? = null
    // 已看身份注册表内存镜像（三层同生命周期，写时失效重建，读写均在 @Synchronized 内）：
    // seenIdsCache = 对外答案（key 集 + 指纹集，含观看历史并集）；
    // seenIdLines = 日志行插入序镜像（LinkedHashSet 保序，超上限 FIFO 淘汰最旧用）；
    // seenLogKeys = 日志行里 key 段的派生集（append 前 O(1) 判重——只认日志不认并集，
    // 防止"老历史里的条目从不入日志、被淘汰后失忆"的二次泄漏）
    @Volatile private var seenIdsCache: Pair<Set<String>, Set<String>>? = null
    private var seenIdLines: LinkedHashSet<String>? = null
    private var seenLogKeys: HashSet<String>? = null

    // ---------------- 收藏 ----------------

    /** 读取全部收藏，顺序即持久化顺序（收藏时间倒序）；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadFavorites(): List<VideoItem> {
        favoritesCache?.let { return it }
        // R435 损坏检测：区分「文件不存在=合法空态」与「文件在但读不出/整文件解析失败
        // =损坏态」——合法空态照旧缓存空表；损坏态只返回空表维持 UI 可用、**不写缓存**
        //（下次调用重读重试），不再让一次读侧抖动被缓存固化成"永久空表"、再被下一次
        // 全量覆盖写坐实清空（R433 审计 HIGH：收藏永久清零的根因链）
        val text = try {
            readTextChecked(favoritesFile)
        } catch (_: IOException) {
            return emptyList() // 损坏态：文件在但读不出（磁盘抖动/占用），不入缓存
        }
        if (text == null) {
            favoritesCache = emptyList() // 合法空态：从未写过或已被清空，照旧缓存
            return emptyList()
        }
        val list = runCatching {
            val arr = JSONArray(text)
            val out = ArrayList<VideoItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                // 逐条防御：单条字段缺失/类型错（toVideoItem 的 getString 会抛 JSONException）
                // 只跳过该条，其余收藏保留——不再让一条坏记录拖垮整个文件被清空
                runCatching { out += obj.toVideoItem() }
            }
            out
        }.getOrNull() ?: return emptyList() // 整文件解析失败=损坏态：不入缓存，下次重读
        favoritesCache = list
        return list
    }

    /** 全量覆盖写收藏列表（调用方维护好顺序：收藏时间倒序）；写后重建缓存 */
    @Synchronized
    fun saveFavorites(items: List<VideoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        writeText(favoritesFile, arr.toString())
        favoritesCache = items
    }

    // ---------------- 稍后再看 ----------------

    /** 读取全部稍后再看，顺序即持久化顺序（加入时间倒序，最新在前）；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadWatchLater(): List<VideoItem> {
        watchLaterCache?.let { return it }
        val text = readText(watchLaterFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<VideoItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // 逐条防御：单条字段缺失/类型错（toVideoItem 的 getString 会抛 JSONException）
                    // 只跳过该条，其余暂存保留——不再让一条坏记录拖垮整个文件被清空
                    runCatching { out += obj.toVideoItem() }
                }
                out
            }.getOrDefault(emptyList()) // 文件损坏时按无暂存处理，不崩溃
        }
        watchLaterCache = list
        return list
    }

    /** 全量覆盖写稍后再看列表（调用方维护好顺序：加入时间倒序）；写后重建缓存 */
    @Synchronized
    fun saveWatchLater(items: List<VideoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        writeText(watchLaterFile, arr.toString())
        watchLaterCache = items
    }

    // ---------------- 频道屏蔽 ----------------

    /** 读取被屏蔽的频道 id 集合；文件不存在或损坏时返回空集合；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadBlockedChannels(): Set<Long> {
        blockedChannelsCache?.let { return it }
        val text = readText(blockedChannelsFile)
        val set = if (text == null) {
            emptySet()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = LinkedHashSet<Long>(arr.length())
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, 0L)
                    if (id != 0L) out += id // 坏条目跳过
                }
                out
            }.getOrDefault(emptySet())
        }
        blockedChannelsCache = set
        return set
    }

    /** 全量覆盖写屏蔽频道集合；写后重建缓存 */
    @Synchronized
    fun saveBlockedChannels(chatIds: Set<Long>) {
        val arr = JSONArray()
        chatIds.forEach { arr.put(it) }
        writeText(blockedChannelsFile, arr.toString())
        blockedChannelsCache = chatIds
    }

    // ---------------- 关键词屏蔽（R413） ----------------

    /** 读取用户屏蔽词集合（插入序）；文件不存在或损坏时返回空集合；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadMutedKeywords(): Set<String> {
        mutedKeywordsCache?.let { return it }
        val text = readText(mutedKeywordsFile)
        val set = if (text == null) {
            emptySet()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add) // 坏条目跳过
                }
                out
            }.getOrDefault(emptySet())
        }
        mutedKeywordsCache = set
        return set
    }

    /** 全量覆盖写屏蔽词集合；写后重建缓存 */
    @Synchronized
    fun saveMutedKeywords(words: Set<String>) {
        val arr = JSONArray()
        words.forEach { arr.put(it) }
        writeText(mutedKeywordsFile, arr.toString())
        mutedKeywordsCache = words
    }

    // ---------------- 观看行为事件 ----------------

    /** 读取观看行为事件，顺序即持久化顺序（时间倒序，最新在前）；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadWatchEvents(): List<WatchEvent> {
        watchEventsCache?.let { return it }
        val text = readText(watchEventsFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<WatchEvent>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val chatId = obj.optLong("chatId", 0L)
                    if (chatId == 0L) continue // 坏条目跳过
                    out += WatchEvent(
                        chatId = chatId,
                        watchSec = obj.optInt("watchSec", 0),
                        durationSec = obj.optInt("durationSec", 0),
                        ts = obj.optLong("ts", 0L),
                    )
                }
                out
            }.getOrDefault(emptyList())
        }
        watchEventsCache = list
        return list
    }

    /** 追加一条观看事件：新事件置顶、截断到 [MAX_WATCH_EVENTS] 条后落盘（读侧走缓存） */
    @Synchronized
    fun appendWatchEvent(chatId: Long, watchSec: Int, durationSec: Int) {
        val list = mutableListOf(WatchEvent(chatId, watchSec, durationSec, System.currentTimeMillis()))
        list += loadWatchEvents()
        saveWatchEvents(list.take(MAX_WATCH_EVENTS))
    }

    private fun saveWatchEvents(events: List<WatchEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("chatId", e.chatId)
                put("watchSec", e.watchSec)
                put("durationSec", e.durationSec)
                put("ts", e.ts)
            })
        }
        writeText(watchEventsFile, arr.toString())
        watchEventsCache = events
    }

    // ---------------- 点赞 ----------------

    @Synchronized
    fun loadLikes(): Set<String> {
        likesCache?.let { return it }
        val text = readText(likesFile)
        val set = if (text == null) {
            emptySet()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
                }
                out
            }.getOrDefault(emptySet())
        }
        likesCache = set
        return set
    }

    @Synchronized
    fun saveLikes(keys: Set<String>) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        writeText(likesFile, arr.toString())
        likesCache = keys
    }

    // ---------------- 画面姿态记忆（R218 建表 / R227 扩展为旋转+镜像复合态） ----------------

    /**
     * 读取"画面姿态"的 item.key → [StoredFrameAdjust]（旋转角度 + 镜像开关）表。
     * 文件不存在或损坏时返回空表；缓存命中时不碰磁盘。读侧消费方是 UI 侧
     * FrameAdjustStore（进程级快照表，Feed 播放页/分屏格两宿主共享同一真身）。
     *
     * 格式兼容（R227 扩展）：新格式为 JSON 对象 `{"<key>": {"r": 90, "m": true}}`；
     * R218 旧格式为 item.key 字符串数组（纯镜像表）——读侧自动迁移为 {r=0, m=true}，
     * 首次保存时升级为新格式（老用户翻转记忆零丢失）。
     */
    @Synchronized
    fun loadFrameAdjust(): Map<String, StoredFrameAdjust> {
        frameAdjustCache?.let { return it }
        val text = readText(mirroredFile)
        val map = if (text == null) {
            emptyMap()
        } else {
            runCatching {
                val trimmed = text.trim()
                if (trimmed.startsWith("[")) {
                    // R218 旧格式：key 数组 = 纯镜像表，迁移为 r=0/m=true
                    val arr = JSONArray(trimmed)
                    val out = LinkedHashMap<String, StoredFrameAdjust>(arr.length())
                    for (i in 0 until arr.length()) {
                        val k = arr.optString(i)
                        if (k.isNotEmpty()) out[k] = StoredFrameAdjust(rotationDeg = 0, mirrored = true)
                    }
                    out
                } else {
                    // R227 新格式：key → {r, m}
                    val obj = JSONObject(trimmed)
                    val out = LinkedHashMap<String, StoredFrameAdjust>(obj.length())
                    for (k in obj.keys()) {
                        if (k.isEmpty()) continue
                        val e = obj.optJSONObject(k) ?: continue
                        // 逐条防御：坏条目跳过不拖垮整表（收藏表同款口径）
                        runCatching {
                            out[k] = StoredFrameAdjust(
                                rotationDeg = ((e.optInt("r", 0) % 360) + 360) % 360,
                                mirrored = e.optBoolean("m", false),
                            )
                        }
                    }
                    out
                }
            }.getOrDefault(emptyMap()) // 文件损坏时按无记忆处理，不崩溃
        }
        frameAdjustCache = map
        return map
    }

    /**
     * 全量覆盖写"画面姿态"表（UI 侧 FrameAdjustStore 姿态变更后异步落盘走这里）。
     * 角度先归一 [0,360)；默认姿态条目（归一后 r=0 且未镜像）落盘前剔除——表里
     * 只存有调整的条目，"恢复默认"即从表里删项，文件不随使用年限膨胀。
     */
    @Synchronized
    fun saveFrameAdjust(map: Map<String, StoredFrameAdjust>) {
        val obj = JSONObject()
        val out = LinkedHashMap<String, StoredFrameAdjust>(map.size)
        map.forEach { (k, e) ->
            if (k.isEmpty()) return@forEach
            val deg = ((e.rotationDeg % 360) + 360) % 360
            if (deg == 0 && !e.mirrored) return@forEach // 默认态不入盘
            val norm = if (deg == e.rotationDeg) e else e.copy(rotationDeg = deg)
            out[k] = norm
            obj.put(k, JSONObject().put("r", deg).put("m", e.mirrored))
        }
        writeText(mirroredFile, obj.toString())
        frameAdjustCache = out
    }

    // ---------------- 不感兴趣（视频粒度黑名单） ----------------

    /** 读取"不感兴趣"的 item.key 集合；文件不存在或损坏时返回空集合；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadDisliked(): Set<String> {
        dislikedCache?.let { return it }
        val text = readText(dislikedFile)
        val set = if (text == null) {
            emptySet()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
                }
                out
            }.getOrDefault(emptySet())
        }
        dislikedCache = set
        return set
    }

    /** 全量覆盖写"不感兴趣"集合；写后重建缓存 */
    @Synchronized
    fun saveDisliked(keys: Set<String>) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        writeText(dislikedFile, arr.toString())
        dislikedCache = keys
    }

    /**
     * 清空"不感兴趣"黑名单：删 disliked.json + 内存缓存置空。
     * 幂等（文件不存在时 delete 返回 false，直接忽略）；
     * 缓存置空列表而非 null——避免下次读取回扫磁盘，同时保持"已清空"语义。
     */
    @Synchronized
    fun clearDisliked() {
        dislikedFile.delete()
        dislikedCache = emptySet()
    }

    // ---------------- 广告永久屏蔽①：不感兴趣计数（dislike_count.json） ----------------

    /** 读取"不感兴趣"计数表（计数键 → 累计次数）；文件不存在或损坏时返回空表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadDislikeCounts(): Map<String, Int> {
        dislikeCountCache?.let { return it }
        val text = readText(dislikeCountFile)
        val map = if (text == null) {
            emptyMap()
        } else {
            runCatching {
                val obj = JSONObject(text)
                val out = LinkedHashMap<String, Int>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.isEmpty()) continue // 坏 key 跳过
                    val v = obj.optInt(k, 0)
                    if (v > 0) out[k] = v // 非正计数无意义，跳过
                }
                out.toMap()
            }.getOrDefault(emptyMap()) // 文件损坏时按无计数处理，不崩溃
        }
        dislikeCountCache = map
        return map
    }

    /**
     * 累计一次"不感兴趣"：计数键 +1 后落盘，返回累计后的次数。
     * 计数键由调用方决定（内容指纹 fileUniqueId 优先，无指纹回落 item.key），
     * 本方法只做计数与落盘，"几次算永久"的阈值判定在调用方（FeedViewModel）。
     * @Synchronized 内完成"读-改-写"，多线程累计不丢次数。
     */
    @Synchronized
    fun bumpDislikeCount(counterKey: String): Int {
        if (counterKey.isEmpty()) return 0
        val cur = LinkedHashMap(loadDislikeCounts())
        val next = (cur[counterKey] ?: 0) + 1
        cur[counterKey] = next
        saveDislikeCounts(cur)
        return next
    }

    /**
     * 撤销一次"不感兴趣"：计数 -1（到 0 删除记录）。
     * 防误杀语义：很久以前点过一次又撤销的，不该让未来一次误点直接顶到永久屏蔽阈值。
     * 已进永久屏蔽表的不受影响（屏蔽是独立结论，撤销单次黑名单不解封）。
     */
    @Synchronized
    fun decayDislikeCount(counterKey: String) {
        if (counterKey.isEmpty()) return
        val cur = LinkedHashMap(loadDislikeCounts())
        val old = cur[counterKey] ?: return // 本来就没有计数，不碰磁盘
        if (old > 1) cur[counterKey] = old - 1 else cur.remove(counterKey)
        saveDislikeCounts(cur)
    }

    private fun saveDislikeCounts(map: Map<String, Int>) {
        val obj = JSONObject()
        for ((k, v) in map) if (v > 0) obj.put(k, v)
        writeText(dislikeCountFile, obj.toString())
        dislikeCountCache = map
    }

    // ---------------- 广告永久屏蔽②：内容指纹永久屏蔽表（blocked_items.json） ----------------

    /** 读取永久屏蔽表（屏蔽时间倒序，最新在前）；文件不存在或损坏时返回空列表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadBlockedItems(): List<BlockedItem> {
        blockedItemsCache?.let { return it }
        val text = readText(blockedItemsFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<BlockedItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("key")
                    val uid = obj.optString("fileUniqueId")
                    // 坏条目跳过：两种身份都空则永远无法匹配，留着只会占位置
                    if (key.isEmpty() && uid.isEmpty()) continue
                    out += BlockedItem(
                        key = key,
                        fileUniqueId = uid,
                        title = obj.optString("title"),
                        channelTitle = obj.optString("channelTitle"),
                        ts = obj.optLong("ts", 0L),
                    )
                }
                out
            }.getOrDefault(emptyList()) // 文件损坏时按无屏蔽处理，不崩溃
        }
        blockedItemsCache = list
        return list
    }

    /**
     * 加入永久屏蔽表：同身份去重（身份 = fileUniqueId，无指纹时回落 item.key），新条目置顶。
     * 返回 true 表示确实新加（调用方据此刷新内存态/剔除当前列表）；已在表返回 false 且不碰磁盘。
     */
    @Synchronized
    fun addBlockedItem(item: BlockedItem): Boolean {
        val identity = item.fileUniqueId.ifEmpty { item.key }
        if (identity.isEmpty()) return false
        val cur = loadBlockedItems()
        if (cur.any { it.fileUniqueId.ifEmpty { it.key } == identity }) return false // 已在表，幂等
        saveBlockedItems(listOf(item) + cur)
        return true
    }

    /**
     * 按身份（fileUniqueId 或 item.key，任一匹配）移出永久屏蔽表（屏蔽管理入口）；
     * 本来就没有则返回 false 且不碰磁盘（同 removeProgress 语义）
     */
    @Synchronized
    fun removeBlockedItem(identity: String): Boolean {
        if (identity.isEmpty()) return false
        val cur = loadBlockedItems()
        val newList = cur.filterNot { it.fileUniqueId == identity || it.key == identity }
        if (newList.size == cur.size) return false
        saveBlockedItems(newList)
        return true
    }

    private fun saveBlockedItems(items: List<BlockedItem>) {
        val arr = JSONArray()
        items.forEach { b ->
            arr.put(JSONObject().apply {
                put("key", b.key)
                put("fileUniqueId", b.fileUniqueId)
                put("title", b.title)
                put("channelTitle", b.channelTitle)
                put("ts", b.ts)
            })
        }
        writeText(blockedItemsFile, arr.toString())
        blockedItemsCache = items
    }

    /**
     * R300 多设备同步合并用：全量覆盖写永久屏蔽表（调用方=MultiSync 已按 SyncMergePolicy
     * 裁决出终值清单；语义同 saveBlockedChannels 全量覆盖写）。只加不改：新增公开口，
     * 既有 add/removeBlockedItem 逐条口原样保留。
     */
    @Synchronized
    fun saveBlockedItemsMerged(items: List<BlockedItem>) = saveBlockedItems(items)

    // ---------------- 观看历史 ----------------

    /**
     * 读取观看历史：Pair(item.key, 时间戳毫秒)，按时间倒序；缓存命中时不碰磁盘。
     * 供"已看过滤"等只需要 key 名单的调用方；需要条目本体走 [loadHistoryItems]。
     */
    @Synchronized
    fun loadHistory(): List<Pair<String, Long>> =
        loadHistoryEntries().map { it.key to it.ts }

    /**
     * 读取观看历史的完整条目（按观看时间倒序；老格式无本体的记录自动跳过）。
     * G4 修复：history.json 自本版起照收藏同款 toJson/toVideoItem 存完整 VideoItem，
     * 重启后"已看历史"源不再依赖当前内存条目补全。
     */
    @Synchronized
    fun loadHistoryItems(): List<VideoItem> =
        loadHistoryEntries().mapNotNull { it.item }

    /** 读磁盘 + 填充缓存（调用方必须已持有本类监视器，即在 @Synchronized 方法内调用） */
    private fun loadHistoryEntries(): List<HistoryEntry> {
        historyCache?.let { return it }
        val text = readText(historyFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<HistoryEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // 兼容老格式 {"key","ts"}：无条目本体时 item=null——
                    // key/ts 保留供"已看过滤"，本体缺失不参与历史源回放
                    val item = obj.optJSONObject("item")
                        ?.let { runCatching { it.toVideoItem() }.getOrNull() }
                    val key = item?.key ?: obj.optString("key")
                    if (key.isEmpty()) continue
                    out += HistoryEntry(key, obj.optLong("ts", 0L), item)
                }
                out.sortedByDescending { it.ts } // 兜底排序，正常写入时已有序
            }.getOrDefault(emptyList())
        }
        historyCache = list
        return list
    }

    /**
     * 追加一条观看记录：完整条目入库、同 key 去重、置顶、截断到 [MAX_HISTORY] 条后落盘
     * （列表即新→旧序，截尾 = 先进先出淘汰最旧；读侧走缓存）。
     * 同事务登记已看身份（[markSeenLocked]）：history 的 500 条上限只是回放窗口，
     * "已看不重推"的判重底账在 seen_ids.log，不受此截断影响。
     */
    @Synchronized
    fun appendHistory(item: VideoItem) {
        val list = mutableListOf(HistoryEntry(item.key, System.currentTimeMillis(), item))
        loadHistoryEntries().filterTo(list) { it.key != item.key }
        saveHistory(list.take(MAX_HISTORY))
        markSeenLocked(item)
    }

    private fun saveHistory(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("key", e.key)
                put("ts", e.ts)
                // 有本体的写完整条目 JSON（重启后可还原）；老格式记录无本体则不写该字段
                if (e.item != null) put("item", e.item.toJson())
            })
        }
        writeText(historyFile, arr.toString())
        historyCache = entries
    }

    /**
     * 只登记已看身份、不写观看历史（R94：分屏实际播放 / 主 pager 快甩经过页等
     * "看过但不算一条观看记录"的场景）。与 [appendHistory] 共用 [markSeenLocked]
     * 底账（seen_ids.log 双轨：key + 内容指纹），已登记者纯内存返回零写盘。
     */
    @Synchronized
    fun markSeen(item: VideoItem) {
        markSeenLocked(item)
    }

    /**
     * 清空观看历史：删 history.json + 内存缓存置空。
     * 幂等（文件不存在时 delete 返回 false，直接忽略）；
     * 缓存置空列表而非 null——避免下次读取回扫磁盘，同时保持"已清空"语义。
     * 已看身份注册表同步清空：清空历史是用户显式要求"一切重来"，已看过滤也随之失忆
     * （否则清完历史后已看内容仍被双轨底账挡在 Feed 外，与"清空"语义自相矛盾）。
     */
    @Synchronized
    fun clearHistory() {
        historyFile.delete()
        historyCache = emptyList()
        seenIdsFile.delete()
        seenIdLines = LinkedHashSet()
        seenLogKeys = HashSet()
        seenIdsCache = null // 置 null 而非空集：下次读取重建（= 空日志 ∪ 空历史）
    }

    /**
     * 删除单条观看历史（观看历史页"长按删除"用，调用入口为 FeedViewModel.removeHistory）：
     * 同 key 移除后落盘；本来就没有则返回 false 且不碰磁盘（同 removeProgress 语义）。
     * 注意：本方法只保证"经同一 LocalStore 实例"的读写一致；历史的全局唯一写入方是
     * FeedViewModel 持有的实例（其 historyCache 已预热），其他实例请勿调用本方法写盘。
     */
    @Synchronized
    fun removeHistoryEntry(key: String): Boolean {
        if (key.isEmpty()) return false
        val cur = loadHistoryEntries()
        val newList = cur.filterNot { it.key == key }
        if (newList.size == cur.size) return false // 无此记录，省一次落盘
        saveHistory(newList)
        // 已看身份同步移除：删掉记录 = 允许它重新出现。append-only 日志不支持原地删行，
        // 过滤后整体重写一次（用户显式低频操作，量小无压力）；
        // 只移除该 key 的行——同一片在别频道的转发副本是独立观看记录，不连带；
        // 其指纹若已无任何日志行持有，在下次 loadSeenIdentities 重建时自然脱落。
        // 先强制载入日志：本会话可能还没读过（内存镜像为 null）但磁盘文件已存在，
        // 不载入就判"不在日志"跳过重写，key 会在下次读盘时复活
        val lines = loadSeenIdLines()
        val keys = seenLogKeys ?: HashSet<String>().also { seenLogKeys = it } // 理论非空，防御兜底
        if (key in keys) {
            val kept = lines.filterTo(LinkedHashSet()) { it.substringBefore('\t') != key }
            lines.clear()
            lines.addAll(kept)
            keys.remove(key)
            runCatching { writeText(seenIdsFile, lines.joinToString("\n") + "\n") }
        }
        seenIdsCache = null // 历史并集变了，对外答案重建
        return true
    }

    // ---------------- 已看身份注册表（"已看内容不再出现"的判重底账） ----------------

    /**
     * 读取已看身份（双轨）：first = 已看 item.key 集，second = 已看内容指纹 fileUniqueId 集。
     * 结果 = seen_ids.log 日志 ∪ 观看历史（历史并集是老版本迁移兜底：升级前没有日志文件，
     * 已有历史记录立即获得判重效力；日志自记录之日起独立存活，不受 [MAX_HISTORY] 淘汰影响）。
     * 缓存命中时不碰磁盘；调用方对返回集合只读。
     */
    @Synchronized
    fun loadSeenIdentities(): Pair<Set<String>, Set<String>> {
        seenIdsCache?.let { return it }
        val lines = loadSeenIdLines()
        val keys = HashSet<String>(lines.size + 64)
        val uids = HashSet<String>(lines.size / 2 + 64)
        for (ln in lines) {
            val tab = ln.indexOf('\t')
            if (tab < 0) {
                if (ln.isNotEmpty()) keys += ln
            } else {
                keys += ln.substring(0, tab)
                val uid = ln.substring(tab + 1)
                if (uid.isNotEmpty()) uids += uid
            }
        }
        // 迁移兜底：观看历史里的 key/指纹并入（覆盖"日志建立前就已看过"的存量）
        for (e in loadHistoryEntries()) {
            keys += e.key
            val uid = e.item?.fileUniqueId
            if (!uid.isNullOrEmpty()) uids += uid
        }
        val pair = keys to uids
        seenIdsCache = pair
        return pair
    }

    /** 读日志文件 + 填充行序镜像与 key 派生集（须在 @Synchronized 方法内调用） */
    private fun loadSeenIdLines(): LinkedHashSet<String> {
        seenIdLines?.let { return it }
        val lines = LinkedHashSet<String>()
        runCatching {
            if (seenIdsFile.isFile) {
                seenIdsFile.bufferedReader().useLines { seq ->
                    for (ln in seq) if (ln.isNotBlank()) lines += ln
                }
            }
        }
        seenIdLines = lines
        seenLogKeys = lines.mapTo(HashSet(lines.size + 64)) { it.substringBefore('\t') }
        return lines
    }

    /**
     * R176 关注流已看回收间隔闸的原料：最近观看时刻表。
     * first = item.key → 最近观看毫秒时刻；second = fileUniqueId → 最近观看毫秒时刻
     * （双轨与 [loadSeenIdentities] 同口径——同片换频道转发也要认得出「最近看过」）。
     * 取自观看历史（[MAX_HISTORY] 条窗口）：不在表里 = 不在最近观看窗口内，
     * 调用方按「很久以前」处理（回收间隔闸放行）。同 key 多次观看取最新一次。
     * 每次调用现读磁盘（历史条目量 ≤500，读侧又常命中 [historyCache] 预热，
     * 关注流每供给轮取一次，开销可忽略）。
     */
    @Synchronized
    fun loadWatchTimestamps(): Pair<Map<String, Long>, Map<String, Long>> {
        val entries = loadHistoryEntries()
        val keyTs = HashMap<String, Long>(entries.size + 16)
        val uidTs = HashMap<String, Long>(entries.size / 2 + 16)
        for (e in entries) {
            // 列表按时间倒序（最新在前）：先到者优先，后到的更老不覆盖
            if (e.key !in keyTs) keyTs[e.key] = e.ts
            val uid = e.item?.fileUniqueId
            if (!uid.isNullOrEmpty() && uid !in uidTs) uidTs[uid] = e.ts
        }
        return keyTs to uidTs
    }

    /**
     * 登记一条已看身份（仅由 [appendHistory] 在锁内调用；非 @Synchronized，靠调用方持锁）。
     * 判重只认日志自身（[seenLogKeys]）不认并集：若该 key 只在老历史里而从未入日志，
     * 必须补登——否则历史按 [MAX_HISTORY] 淘汰该条后，已看证据随之消失（二次泄漏）。
     * 同 key 已登记 = 纯内存返回，零写盘（翻回看过的条目不产生 IO）。
     * 超 [MAX_SEEN_IDS] 时 FIFO 淘汰最旧 1/10 并整体重写一次（摊销：每 2000 条新记录
     * 才摊到一次全量写）。
     */
    private fun markSeenLocked(item: VideoItem) {
        val lines = loadSeenIdLines()
        val keys = seenLogKeys ?: return
        if (item.key in keys) return
        val line = item.key + "\t" + item.fileUniqueId
        runCatching {
            dir.mkdirs()
            seenIdsFile.appendText(line + "\n") // 写透：每条当场落盘，杀进程不丢
        }
        lines += line
        keys += item.key
        seenIdsCache = null // 写时失效，下次读重建
        if (lines.size > MAX_SEEN_IDS) {
            val keep = lines.drop(MAX_SEEN_IDS - MAX_SEEN_IDS / 10)
            lines.clear()
            lines.addAll(keep)
            keys.clear()
            for (ln in lines) keys += ln.substringBefore('\t')
            runCatching { writeText(seenIdsFile, lines.joinToString("\n") + "\n") }
        }
    }

    // ---------------- 搜索历史 ----------------

    /** 读取搜索历史：字符串数组，新词在前；文件不存在或损坏时返回空列表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadSearchHistory(): List<String> {
        searchHistoryCache?.let { return it }
        val text = readText(searchHistoryFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
                }
                out
            }.getOrDefault(emptyList()) // 文件损坏时按无历史处理，不崩溃
        }
        searchHistoryCache = list
        return list
    }

    /** 记录一个搜索词：trim 后忽略空串、同词去重置顶、截断到 [MAX_SEARCH_HISTORY] 条后落盘（读侧走缓存） */
    @Synchronized
    fun saveSearchHistory(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        val list = mutableListOf(t)
        loadSearchHistory().filterTo(list) { it != t } // 同词去重，新词置顶
        val trimmed = list.take(MAX_SEARCH_HISTORY)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        writeText(searchHistoryFile, arr.toString())
        searchHistoryCache = trimmed
    }

    // ---------------- VideoItem 序列化 ----------------

    /**
     * 序列化规则：
     *  - coverBytes 不持久化（量大且可由 TDLib 重新获取）
     *  - playUri 仅当为 http(s) / asset:/// 直链（Mock 条目）时保留；
     *    TDLib 的本地缓存路径重启后可能失效，一律存 null
     */
    private fun VideoItem.toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("chatId", chatId)
        put("messageId", messageId)
        put("channelTitle", channelTitle)
        put("caption", caption)
        put("durationSec", durationSec)
        put("width", width)
        put("height", height)
        put("fileId", fileId)
        put("sizeBytes", sizeBytes)
        put("mimeType", mimeType)
        put("dateEpochSec", dateEpochSec)
        // CRITICAL-2 修复：补存 fileUniqueId 与 mediaType。
        // fileId 是 TDLib 会话级 id、进程重启即失效，fileUniqueId（File.remote.uniqueId）
        // 跨会话稳定，是重启后向 TDLib 换发新 fileId（GetRemoteFile）的唯一凭据；
        // mediaType 不落盘则重启后图文/音频收藏全部反序列化成视频（0），起播必败
        put("fileUniqueId", fileUniqueId)
        put("mediaType", mediaType)
        // 高清封面 fileId 落盘：本地结果（历史/收藏/稍后）重启后封面可经
        // loadCoverBytes 重下（否则"本地"标记的结果全没封面——用户实测反馈）
        put("coverFileId", coverFileId)
        // R300 favTs：收藏/稍后再看的「加入时刻毫秒」落盘（多设备同步 per-key LWW 依据）；
        // 老文件无此字段反序列化缺省 0=未知（迁移期存在性并集兜底，见 SyncMergePolicy）
        put("favTs", favTs)
        // R299 增量字段（纯增量 optString/optLong/optInt 双向兼容：老文件缺省
        // 回落空串/0/-1，老版本读本字段自动忽略）——听单重启后标题/歌手可直显，
        // 图片条目图集归属与浏览档凭据随单落盘（收藏/历史/片单同 Codec 同收益）：
        put("audioTitle", audioTitle)
        put("audioPerformer", audioPerformer)
        put("albumId", albumId)
        put("photoViewFileId", photoViewFileId)
        put("audioCoverFileId", audioCoverFileId)
        // R80：回环流地址（http://127.0.0.1:<端口>/v/<fileId>）不持久化——端口是本会话
        // 随机分配的、fileId 也是会话级 id，进程重启后必死；带着它恢复只会白撞一次
        // 连接拒绝再靠重试换路自愈（首条观感=起播先失败一次），不如直接存 null 现取
        val keepUri = playUri?.takeIf {
            (it.startsWith("http") && !it.startsWith("http://127.0.0.1:")) ||
                it.startsWith("asset:///")
        }
        put("playUri", keepUri ?: JSONObject.NULL)
    }

    private fun JSONObject.toVideoItem(): VideoItem = VideoItem(
        key = getString("key"),
        chatId = optLong("chatId"),
        messageId = optLong("messageId"),
        channelTitle = optString("channelTitle"),
        caption = optString("caption"),
        durationSec = optInt("durationSec"),
        width = optInt("width"),
        height = optInt("height"),
        fileId = optInt("fileId"),
        sizeBytes = optLong("sizeBytes"),
        mimeType = optString("mimeType").ifBlank { "video/mp4" },
        playUri = if (has("playUri") && !isNull("playUri")) getString("playUri") else null,
        coverBytes = null, // 封面不落盘，反序列化恒为 null
        dateEpochSec = optInt("dateEpochSec"), // 老文件无此字段时 optInt 缺省为 0
        // 老版本 JSON 无以下字段：fileUniqueId 缺省空串（救不回，播放走 ensurePlayable 报错链）；
        // mediaType 缺省 0（视频）——老版本只收藏视频，缺省值恰好正确
        fileUniqueId = optString("fileUniqueId"),
        mediaType = optInt("mediaType"),
        coverFileId = optInt("coverFileId", -1),
        // R300 favTs 反序列化：老文件缺省 0=未知戳（迁移期存在性并集兜底，SyncMergePolicy）
        favTs = optLong("favTs", 0L),
        // R299 增量字段反序列化（老文件缺省 = 各字段原生缺省值，行为零漂移）
        albumId = optLong("albumId", 0L),
        audioTitle = optString("audioTitle"),
        audioPerformer = optString("audioPerformer"),
        audioCoverFileId = optInt("audioCoverFileId", -1),
        photoViewFileId = optInt("photoViewFileId", -1),
    )

    // ---------------- 订阅追更 ----------------

    /** 读取全部订阅（顺序即持久化顺序）；文件不存在或损坏时返回空列表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadSubscriptions(): List<Subscription> {
        subscriptionsCache?.let { return it }
        val text = readText(subscriptionsFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<Subscription>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val chatId = obj.optLong("chatId", 0L)
                    if (chatId == 0L) continue // 坏条目跳过
                    out += Subscription(
                        chatId = chatId,
                        title = obj.optString("title"),
                        lastSeenMessageId = obj.optLong("lastSeenMessageId", 0L),
                    )
                }
                out
            }.getOrDefault(emptyList())
        }
        subscriptionsCache = list
        return list
    }

    /** 全量覆盖写订阅列表（含各频道追更进度）；写后重建缓存 */
    @Synchronized
    fun saveSubscriptions(subs: List<Subscription>) {
        val arr = JSONArray()
        subs.forEach { s ->
            arr.put(JSONObject().apply {
                put("chatId", s.chatId)
                put("title", s.title)
                put("lastSeenMessageId", s.lastSeenMessageId)
            })
        }
        writeText(subscriptionsFile, arr.toString())
        subscriptionsCache = subs
    }

    // ---------------- 频道合集 ----------------

    private val collectionsFile = File(dir, "collections.json")
    @Volatile private var collectionsCache: List<ChannelCollection>? = null

    /** 读取全部频道合集（顺序即持久化顺序）；文件不存在或损坏时返回空列表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadCollections(): List<ChannelCollection> {
        collectionsCache?.let { return it }
        val text = readText(collectionsFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<ChannelCollection>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    if (name.isEmpty()) continue // 坏条目跳过
                    val idsArr = obj.optJSONArray("channelIds") ?: continue
                    val ids = ArrayList<Long>(idsArr.length())
                    for (j in 0 until idsArr.length()) {
                        val id = idsArr.optLong(j, 0L)
                        if (id != 0L) ids += id // 坏条目跳过
                    }
                    if (ids.isEmpty()) continue // 空合集无意义，跳过
                    out += ChannelCollection(name, ids)
                }
                out
            }.getOrDefault(emptyList()) // 文件损坏时按无合集处理，不崩溃
        }
        collectionsCache = list
        return list
    }

    /** 全量覆盖写频道合集列表；写后重建缓存 */
    @Synchronized
    fun saveCollections(collections: List<ChannelCollection>) {
        val arr = JSONArray()
        collections.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("channelIds", JSONArray().apply { c.channelIds.forEach { put(it) } })
            })
        }
        writeText(collectionsFile, arr.toString())
        collectionsCache = collections
    }

    // ---------------- 长视频续播进度 ----------------

    /** 读取续播进度表：item.key → 已播位置毫秒；文件不存在或损坏时返回空表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadProgressMap(): Map<String, Long> {
        val entries = loadProgressEntries()
        // 每次现拼新 Map 返回，避免调用方拿到共享可变实例（条目数 ≤200，成本可忽略）
        val map = LinkedHashMap<String, Long>(entries.size)
        entries.forEach { map[it.key] = it.posMs }
        return map
    }

    /** 保存/覆盖一条续播进度：同 key 覆盖、置顶（最近更新在前）、截断到 [MAX_WATCH_PROGRESS] 条后原子落盘 */
    @Synchronized
    fun saveProgress(key: String, posMs: Long) {
        val list = mutableListOf(ProgressEntry(key, posMs, System.currentTimeMillis()))
        loadProgressEntries().filterTo(list) { it.key != key } // 同 key 去重，新条目置顶
        saveProgressEntries(list.take(MAX_WATCH_PROGRESS)) // 超出上限按 ts 淘汰最旧（列表即新→旧序）
    }

    /** 移除某条的续播进度（看完的条目清掉，下次从头播）；本来就没有则不碰磁盘 */
    @Synchronized
    fun removeProgress(key: String) {
        val current = loadProgressEntries()
        val list = current.filterNot { it.key == key }
        if (list.size == current.size) return // 无此记录，省一次落盘
        saveProgressEntries(list)
    }

    /**
     * 读取续播进度表（含每 key 的最后更新时间戳，item.key → (posMs, ts)）：
     * 跨设备续播同步（ProgressSync）的 LWW 比较与推送载荷用——[loadProgressMap]
     * 不带 ts 做不了"同 key 比时间戳、新者胜"。迭代序 = 最近更新在前（同 loadProgressMap）。
     */
    @Synchronized
    fun loadProgressTimedMap(): Map<String, Pair<Long, Long>> {
        val entries = loadProgressEntries()
        val map = LinkedHashMap<String, Pair<Long, Long>>(entries.size)
        entries.forEach { map[it.key] = it.posMs to it.ts }
        return map
    }

    /**
     * 以指定时间戳合入一条续播进度（跨设备同步的 LWW 写路径用）：
     * 同 key 覆盖、截断到 [MAX_WATCH_PROGRESS] 条后原子落盘（与 [saveProgress] 同纪律），
     * 但 ts 取调用方给的云端时间戳而非当前时刻，并按 ts 倒序归位——
     * 保真 ts 才能让"新者胜"的比较结论在多设备间传递一致。
     */
    @Synchronized
    fun importProgress(key: String, posMs: Long, ts: Long) {
        val list = loadProgressEntries().filter { it.key != key } + ProgressEntry(key, posMs, ts)
        saveProgressEntries(list.sortedByDescending { it.ts }.take(MAX_WATCH_PROGRESS))
    }

    /** 读磁盘 + 填充缓存（调用方必须已持有本类监视器，即在 @Synchronized 方法内调用） */
    private fun loadProgressEntries(): List<ProgressEntry> {
        progressCache?.let { return it }
        val text = readText(progressFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<ProgressEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("key")
                    if (key.isEmpty()) continue // 坏条目跳过
                    out += ProgressEntry(
                        key = key,
                        posMs = obj.optLong("posMs", 0L),
                        ts = obj.optLong("ts", 0L),
                    )
                }
                out
            }.getOrDefault(emptyList()) // 文件损坏时按无进度处理，不崩溃
        }
        progressCache = list
        return list
    }

    private fun saveProgressEntries(entries: List<ProgressEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("key", e.key)
                put("posMs", e.posMs)
                put("ts", e.ts)
            })
        }
        writeText(progressFile, arr.toString())
        progressCache = entries
    }

    /**
     * 全量覆盖写续播进度表（FeedViewModel.onCleared 的兜底落盘用）：
     * viewModelScope 取消后在途的异步 saveProgress 落盘不再执行，这里把 VM 内存表
     * 一次性同步写盘。已有记录的 ts 原样保留（淘汰排序依据不被打乱），新 key 记当前时刻。
     */
    @Synchronized
    fun saveProgressAll(map: Map<String, Long>) {
        if (map.isEmpty()) return
        val existingTs = loadProgressEntries().associate { it.key to it.ts }
        val now = System.currentTimeMillis()
        val entries = map.entries
            .map { (k, v) -> ProgressEntry(k, v, existingTs[k] ?: now) }
            .sortedByDescending { it.ts }
            .take(MAX_WATCH_PROGRESS)
        saveProgressEntries(entries)
    }

    // ---------------- 记忆播放：最后浏览条目（feed_resume.json） ----------------

    private val feedResumeFile = File(dir, "feed_resume.json")

    /**
     * 读取"最后浏览条目"的 item.key（记忆播放的条目位置恢复点）；
     * 文件不存在/损坏/无 key 字段返回 null。无内存缓存：每进程只在 VM init 读一次。
     */
    @Synchronized
    fun loadLastViewedKey(): String? {
        val text = readText(feedResumeFile) ?: return null
        return runCatching {
            JSONObject(text).optString("key").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * 记录"最后浏览条目"的 item.key（FeedViewModel.onPage 经同 key 节流后调用）。
     * 单 key 小文件原子覆盖写（tmp+rename）；与 watch_progress 分工：
     * 本文件管"条目位置"（不受 5s/97% 阈值影响，翻到即记），
     * watch_progress 管"片内进度位置"（按既有阈值语义）。
     */
    @Synchronized
    fun saveLastViewedKey(key: String) {
        if (key.isEmpty()) return
        runCatching {
            writeText(feedResumeFile, JSONObject().apply {
                put("key", key)
                put("ts", System.currentTimeMillis())
            }.toString())
        }
    }

    // ---------------- 记忆播放：会话标记版（仅会话内有效） ----------------
    // 需求口径：完全退出（杀进程）后不再做记忆播放——想看以前的走播放历史；
    // 同进程内（VM 因页面重建等场景重建）仍允许恢复。
    // 实现：写入时带上进程启动时生成的 sessionId；读取只认本会话标记，
    // 进程死后重开会话标记必然对不上（包括旧版本无 sessionId 字段的文件），返回 null。

    /**
     * 读取"最后浏览条目"的 item.key，**仅当记录带本会话 sessionId 标记时返回**；
     * 文件不存在/损坏/无 key/会话标记不匹配（杀进程重开、旧版文件）一律返回 null。
     * 无内存缓存：每进程只在 VM init 读一次。
     */
    @Synchronized
    fun loadLastViewedKeyForSession(sessionId: String): String? {
        if (sessionId.isEmpty()) return null
        val text = readText(feedResumeFile) ?: return null
        return runCatching {
            val obj = JSONObject(text)
            if (obj.optString("sessionId") != sessionId) return@runCatching null
            obj.optString("key").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * 记录"最后浏览条目"的 item.key 并打上进程会话标记（FeedViewModel.onPage 调用）。
     * 单 key 小文件原子覆盖写（tmp+rename）；与 watch_progress 的分工不变：
     * 本文件管"条目位置"（翻到即记），watch_progress 管"片内进度位置"。
     */
    @Synchronized
    fun saveLastViewedKey(key: String, sessionId: String) {
        if (key.isEmpty()) return
        runCatching {
            writeText(feedResumeFile, JSONObject().apply {
                put("key", key)
                put("ts", System.currentTimeMillis())
                put("sessionId", sessionId)
            }.toString())
        }
    }

    // ---------------- R52 首页第一屏快照（feed_snapshot.json，冷启动秒上屏） ----------------

    private val feedSnapshotFile = File(dir, "feed_snapshot.json")

    /**
     * 读取上次退出前的首页第一屏快照（冷启动过渡用）：
     * 文件不存在/损坏/形态不对一律返回 null（防御：坏 JSON 不崩，调用方按无快照处理）。
     * 条目逐条防御解析：单条字段坏只跳过该条；coverBytes 从 Base64 还原（minithumbnail
     * 糊图占位，首屏封面秒出）。无内存缓存：每进程只在冷启动读一两次。
     *
     * R52b：刻意不加 @Synchronized——本方法无缓存、进程内读者只有 App 容器预读 +
     * VM 兜底两个一次性读，写侧是"临时文件 + rename"原子替换，读到的必是完整旧版
     * 或完整新版；免锁后快照读不再排进 VM init 预载车队（各 loadXxx 共用同一把
     * store 监视器，平板实测车队把快照读拖慢数秒）。
     */
    fun loadFeedSnapshot(): FeedSnapshot? {
        val text = readText(feedSnapshotFile) ?: return null
        // R170b：解析收编 parseSnapshot 共享件（feed/关注流快照同构，单一出处）
        return parseSnapshot(runCatching { JSONObject(text) }.getOrNull() ?: return null)
    }

    /**
     * 覆盖写首页第一屏快照：调用方（FeedViewModel）在聚合源首页首拉到货时传入
     * 当前列表头部条目。条目序列化复用收藏同款 toJson，另补 coverB64（minithumbnail，
     * 单条 ~1-2KB，20 条约 40KB，落盘无压力）；写入沿用"临时文件 + rename"原子口径。
     * 条目数截断到 [MAX_FEED_SNAPSHOT]；空列表不写（避免坏状态覆盖好快照）。
     */
    @Synchronized
    fun saveFeedSnapshot(items: List<VideoItem>, mode: String) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.take(MAX_FEED_SNAPSHOT).forEach { item ->
            arr.put(item.toJson().apply {
                item.coverBytes?.let { put("coverB64", java.util.Base64.getEncoder().encodeToString(it)) }
            })
        }
        runCatching {
            writeText(feedSnapshotFile, JSONObject().apply {
                put("v", 1)
                put("mode", mode)
                put("ts", System.currentTimeMillis())
                put("items", arr)
            }.toString())
        }
    }

    // ---------------- R170b 关注流首页快照（following_snapshot.json，切 tab 秒出内容） ----------------

    private val followingSnapshotFile = File(dir, "following_snapshot.json")

    /**
     * 读取关注流首页快照（R170b：切到「关注」tab 秒出上次内容，网络刷新经既有
     * 去重链后台接续）。形态与 R52 feed 快照同构（items+mode+ts，coverB64 还原
     * minithumbnail 占位）；坏文件/无文件返回 null。文件在 files 根——
     * **不在 deepindex 目录**（R170 实锤 DeepSearchIndex 启动门房会删一切
     * 非数字命名的 .json，外来缓存放门房辖区=每冷启被删一次）。
     */
    fun loadFollowingSnapshot(): FeedSnapshot? {
        val text = readText(followingSnapshotFile) ?: return null
        return parseSnapshot(root = runCatching { JSONObject(text) }.getOrNull() ?: return null)
    }

    /**
     * 覆盖写关注流首页快照：调用方（预热协调器任务/关注流真实轮收尾）传入关注流
     * 列表头部条目（已过已看过滤）。复用 [saveFeedSnapshot] 同款序列化与原子写。
     */
    @Synchronized
    fun saveFollowingSnapshot(items: List<VideoItem>, mode: String) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.take(MAX_FEED_SNAPSHOT).forEach { item ->
            arr.put(item.toJson().apply {
                item.coverBytes?.let { put("coverB64", java.util.Base64.getEncoder().encodeToString(it)) }
            })
        }
        runCatching {
            writeText(followingSnapshotFile, JSONObject().apply {
                put("v", 1)
                put("mode", mode)
                put("ts", System.currentTimeMillis())
                put("items", arr)
            }.toString())
        }
    }

    /** R52/R170b 快照共用的条目数组解析（单条坏只跳过该条；空表返回 null） */
    private fun parseSnapshot(root: JSONObject): FeedSnapshot? {
        val arr = root.optJSONArray("items") ?: return null
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            runCatching {
                val item = obj.toVideoItem().copy(
                    coverBytes = obj.optString("coverB64")
                        .takeIf { it.isNotEmpty() }
                        ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() },
                )
                if (item.key.isNotEmpty() && item.chatId != 0L) out += item
            }
        }
        if (out.isEmpty()) return null
        // R324：补读落盘时点（saveFeedSnapshot/saveFollowingSnapshot 一直在写 "ts"，
        // 旧解析丢弃——离线降级的「N 小时前的快照」诚实时点原料；坏档/缺省=0=无时点）
        return FeedSnapshot(out, root.optString("mode", "real"), root.optLong("ts", 0L))
    }

    // ---------------- 下载记录 ----------------

    /** 读取全部下载记录，顺序即持久化顺序（下载完成时间倒序，最新在前）；缓存命中时不碰磁盘 */
    @Synchronized
    fun listDownloads(): List<DownloadRecord> {
        downloadsCache?.let { return it }
        // R435 损坏检测：口径同 loadFavorites——「文件不存在=合法空态」照旧缓存空表；
        //「文件在但读不出/整文件解析失败=损坏态」返回空表但**不写缓存**（下次重读重试），
        // 不让读侧抖动被缓存固化、再被 addDownload→saveDownloads 全量覆盖写坐实清空
        val text = try {
            readTextChecked(downloadsFile)
        } catch (_: IOException) {
            return emptyList() // 损坏态：不入缓存
        }
        if (text == null) {
            downloadsCache = emptyList() // 合法空态：照旧缓存
            return emptyList()
        }
        val list = runCatching {
            val arr = JSONArray(text)
            val out = ArrayList<DownloadRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("key")
                if (key.isEmpty()) continue // 坏条目跳过
                out += DownloadRecord(
                    key = key,
                    title = obj.optString("title"),
                    channelTitle = obj.optString("channelTitle"),
                    path = obj.optString("path"),
                    ts = obj.optLong("ts", 0L),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    chatId = obj.optLong("chatId", 0L),
                    messageId = obj.optLong("messageId", 0L),
                    durationSec = obj.optInt("durationSec", 0),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    // 老数据无此字段：缺省 0=视频（老版本只有视频能下载成功，缺省恰好正确）
                    mediaType = obj.optInt("mediaType", 0),
                )
            }
            out
        }.getOrNull() ?: return emptyList() // 整文件解析失败=损坏态：不入缓存，下次重读
        downloadsCache = list
        return list
    }

    /** 登记一条下载完成记录：同 key 去重、置顶（最新在前）后落盘（读侧走缓存） */
    @Synchronized
    fun addDownload(record: DownloadRecord) {
        val list = mutableListOf(record)
        list += listDownloads().filterNot { it.key == record.key }
        saveDownloads(list)
    }

    /**
     * 移除一条下载记录并落盘，返回被移除的记录（调用方据此删本地文件）；
     * 本来就没有则返回 null，且不碰磁盘（省一次落盘，同 removeProgress 语义）
     */
    @Synchronized
    fun removeDownload(key: String): DownloadRecord? {
        val current = listDownloads()
        val removed = current.firstOrNull { it.key == key } ?: return null
        saveDownloads(current.filterNot { it.key == key })
        return removed
    }

    private fun saveDownloads(records: List<DownloadRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("key", r.key)
                put("title", r.title)
                put("channelTitle", r.channelTitle)
                put("path", r.path)
                put("ts", r.ts)
                put("sizeBytes", r.sizeBytes)
                // 播放恢复字段（老数据没有，读取侧 optLong 缺省 0）
                put("chatId", r.chatId)
                put("messageId", r.messageId)
                put("durationSec", r.durationSec)
                put("width", r.width)
                put("height", r.height)
                // 媒体类型（0=视频 1=图片 2=音频）：下载页点播放还原条目时按它分派卡片
                put("mediaType", r.mediaType)
            })
        }
        writeText(downloadsFile, arr.toString())
        downloadsCache = records
    }

    // ---------------- 追更通知的频道最新消息基线 ----------------

    private val lastSeenIdsFile = File(dir, "last_seen_message_ids.json")
    @Volatile private var lastSeenIdsCache: Map<Long, Long>? = null

    /**
     * 读取各频道已见最新消息 id 表（chatId → messageId），追更通知轮询的"上次记录"。
     * 文件不存在或损坏时返回空表；缓存命中时不碰磁盘。
     * 注意与 subscriptions.json 的 lastSeenMessageId 不同：那份是"追更源"的刷取进度
     * （用户视角已刷到哪），本表是通知轮询的去抖基线（系统视角上次见到哪），互不混用。
     */
    @Synchronized
    fun loadLastSeenMessageIds(): Map<Long, Long> {
        lastSeenIdsCache?.let { return it }
        val text = readText(lastSeenIdsFile)
        val map = if (text == null) {
            emptyMap()
        } else {
            runCatching {
                val obj = JSONObject(text)
                val out = LinkedHashMap<Long, Long>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue // 坏 key 跳过
                    val v = obj.optLong(k, 0L)
                    if (v > 0L) out[id] = v
                }
                out.toMap()
            }.getOrDefault(emptyMap()) // 文件损坏时按无基线处理，不崩溃
        }
        lastSeenIdsCache = map
        return map
    }

    /** 全量覆盖写各频道已见最新消息 id 表；写后重建缓存 */
    @Synchronized
    fun saveLastSeenMessageIds(map: Map<Long, Long>) {
        val obj = JSONObject()
        for ((id, v) in map) obj.put(id.toString(), v)
        writeText(lastSeenIdsFile, obj.toString())
        lastSeenIdsCache = map
    }

    // ---------------- 播放列表 ----------------

    private val playlistsFile = File(dir, "playlists.json")

    /** 片单元数据内存缓存（写时失效重建，与 favorites 同一模式） */
    @Volatile private var playlistsCache: List<PlaylistMeta>? = null

    /**
     * 各片单条目缓存：playlistId → 条目列表。
     * 普通 HashMap 即可：所有读写都发生在 @Synchronized 方法内（本类监视器保护），
     * 单写入方语义与文件中其他缓存一致（mtime 校验刻意省略，见类头注释）。
     */
    private val playlistItemsCache = HashMap<String, List<VideoItem>>()

    /** 片单条目文件：playlist_<id>.json；id 由调用方（FeedViewModel）生成时已保证文件名安全字符 */
    private fun playlistItemsFile(playlistId: String) = File(dir, "playlist_$playlistId.json")

    /** 读取全部片单元数据（顺序即持久化顺序，创建先后）；文件不存在/损坏返回空列表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadPlaylists(): List<PlaylistMeta> {
        playlistsCache?.let { return it }
        val text = readText(playlistsFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<PlaylistMeta>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // 逐条防御：id/name 缺失的坏条目跳过，其余保留（同收藏的容错口径）
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    if (id.isEmpty() || name.isEmpty()) continue
                    val keysArr = obj.optJSONArray("itemKeys")
                    val keys = ArrayList<String>(keysArr?.length() ?: 0)
                    if (keysArr != null) {
                        for (j in 0 until keysArr.length()) {
                            keysArr.optString(j).takeIf { it.isNotEmpty() }?.let(keys::add)
                        }
                    }
                    val createdAt = obj.optLong("createdAt", 0L)
                    out += PlaylistMeta(
                        id = id,
                        name = name,
                        itemKeys = keys,
                        createdAt = createdAt,
                        // updatedAt 后加字段：老文件缺省回落 createdAt（语义=建单时间）
                        updatedAt = obj.optLong("updatedAt", createdAt),
                    )
                }
                out
            }.getOrDefault(emptyList()) // 文件损坏时按无片单处理，不崩溃
        }
        playlistsCache = list
        return list
    }

    /** 全量覆盖写片单元数据（调用方维护好顺序与 itemKeys 镜像）；写后重建缓存 */
    @Synchronized
    fun savePlaylists(list: List<PlaylistMeta>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("itemKeys", JSONArray().apply { p.itemKeys.forEach { put(it) } })
                put("createdAt", p.createdAt)
                put("updatedAt", p.updatedAt)
            })
        }
        writeText(playlistsFile, arr.toString())
        playlistsCache = list
    }

    /** 读取某片单的全部条目（完整 VideoItem，序列化规则同收藏）；文件不存在/损坏返回空列表；缓存命中时不碰磁盘 */
    @Synchronized
    fun loadPlaylistItems(playlistId: String): List<VideoItem> {
        playlistItemsCache[playlistId]?.let { return it }
        val text = readText(playlistItemsFile(playlistId))
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<VideoItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // 逐条防御：单条字段缺失/类型错只跳过该条（同收藏）
                    runCatching { out += obj.toVideoItem() }
                }
                out
            }.getOrDefault(emptyList())
        }
        playlistItemsCache[playlistId] = list
        return list
    }

    /** 全量覆盖写某片单条目（调用方维护好顺序：片单内连播顺序）；写后更新缓存 */
    @Synchronized
    fun savePlaylistItems(playlistId: String, items: List<VideoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        writeText(playlistItemsFile(playlistId), arr.toString())
        playlistItemsCache[playlistId] = items
    }

    /**
     * 删除某片单的条目文件并清其条目缓存（元数据的移除由调用方走 savePlaylists 一并完成）。
     * 幂等：文件不存在时 delete 返回 false，直接忽略。
     */
    @Synchronized
    fun deletePlaylistItems(playlistId: String) {
        playlistItemsFile(playlistId).delete()
        playlistItemsCache.remove(playlistId)
    }

    // ---------------- R299 听单（音频队列一等化：可编辑持久 up-next 队列） ----------------

    private val listenQueueFile = File(dir, "listen_queue.json")

    /** 听单内存缓存（写时失效重建，与 playlists 同一模式；唯一写入方=ListenQueueController） */
    @Volatile private var listenQueueCache: List<VideoItem>? = null

    /**
     * 读取持久听单（顺序即连播顺序：首=下一首要播的）；文件不存在/损坏返回空列表。
     * 序列化规则与收藏/片单同一 Codec（单条字段缺失只跳该条，不炸整单）。
     */
    @Synchronized
    fun loadListenQueue(): List<VideoItem> {
        listenQueueCache?.let { return it }
        val text = readText(listenQueueFile)
        val list = if (text == null) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<VideoItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    runCatching { out += obj.toVideoItem() }
                }
                out
            }.getOrDefault(emptyList())
        }
        listenQueueCache = list
        return list
    }

    /** 全量覆盖写听单（调用方=ListenQueueController，已维护好顺序与 500 截尾）；写后重建缓存 */
    @Synchronized
    fun saveListenQueue(items: List<VideoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        writeText(listenQueueFile, arr.toString())
        listenQueueCache = items
    }

    // ---------------- R387/R391 播放队列（节目单 → 播放台：多队列 v2 持久层） ----------------

    private val playQueueFile = File(dir, "play_queue.json")

    /**
     * 播放台列表内存缓存（写时失效重建，与 listen_queue 同一模式；
     * 唯一写入方=FeedPlayQueuePart/VM 串行通道）。R391 起存 [PlayStationsState]
     * （台列表+活跃台 id）；一期单队列双函数（loadPlayQueue/savePlayQueue）已随
     * v2 升格退役——同文件双 codec 双缓存会漂（旧路径误调写 v1 覆盖 v2=真风险），
     * 判词见 r391_notes §2.2。
     */
    @Volatile private var playStationsCache: PlayStationsState? = null

    /**
     * 读取播放台列表（R391 v2 档：stations 数组+activeId）。
     *
     * 坏档防御沿用一期逐层口径：文件损坏/坏 JSON=空列表不崩溃（runCatching 兜底）；
     * 台级防御：台对象非 JSONObject/id 空/entries 非 JSON=跳该台其余保留（收藏/
     * 片单同口径，一台坏记录不拖垮整库）；条目级防御（id 缺失/type 未知/channel
     * 条目 chatId=0/item 条目 VideoItem 坏）只跳该条；断点 NULL 哨兵 has+isNull
     * 先守（Android org.json 对 NULL 值 optString 回字面 "null" 字符串，playUri
     * 读侧 L923 同款判式）；orderMode 未知回落 MANUAL（单源反解）。
     *
     * **v1 迁移**（一期单队列实体→迁移台）：顶层有 "entries" 无 "stations" =
     * 一期格式——整单解析为单台（id=legacy 固定串=重读幂等不漂移，名字/档位/
     * 断点全继承），activeId=legacy；迁移在读侧发生（写侧只写 v2），旧装机首次
     * 读即升级。activeId 失联（指向已删台）=null（调用方回落第一台）。
     */
    @Synchronized
    fun loadPlayStations(): PlayStationsState {
        playStationsCache?.let { return it }
        val text = readText(playQueueFile)
        val state = if (text == null) {
            PlayStationsState(emptyList(), null)
        } else {
            runCatching {
                val obj = JSONObject(text)
                if (obj.has("stations")) parseStationsV2(obj) else parseStationV1(obj)
            }.getOrDefault(PlayStationsState(emptyList(), null)) // 文件损坏时按空库处理，不崩溃
        }
        playStationsCache = state
        return state
    }

    /** v2 档解析：stations 数组逐台防御 + activeId NULL 哨兵守 */
    private fun parseStationsV2(obj: JSONObject): PlayStationsState {
        val arr = obj.optJSONArray("stations")
        val stations = ArrayList<PlayQueue>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val id = s.optString("id")
                if (id.isEmpty()) continue // 台身份是主键，缺失即坏台（同条目 id 口径）
                val (resumeEntryId, resumeKey) = parseResume(s)
                stations += PlayQueue(
                    id = id,
                    name = s.optString("name").ifBlank { PlayQueue.DEFAULT_NAME },
                    orderMode = PlayQueueOrder.fromName(s.optString("orderMode")),
                    entries = parseEntries(s.optJSONArray("entries")),
                    updatedAt = s.optLong("updatedAt", 0L),
                    resumeEntryId = resumeEntryId,
                    resumeKey = resumeKey,
                    chainTo = parseChainTo(s),
                )
            }
        }
        // R402 毒态自愈守门：历史缺陷（生成覆盖分支同 id 双插）曾落重复条 →
        // 启动即 LazyColumn 重复 key 崩溃循环。读侧按 id 去重（保留位序最前条）
        // ——毒机免数据手术自恢复；正常档零变化（id 本是主键唯一）。
        val deduped = stations.toList().distinctBy { it.id }
        val activeId = if (obj.has("activeId") && !obj.isNull("activeId"))
            obj.optString("activeId").ifBlank { null } else null
        return PlayStationsState(deduped, activeId)
    }

    /** v1 档解析（一期单队列→迁移台）：顶层 entries/name/orderMode/断点全继承 */
    private fun parseStationV1(obj: JSONObject): PlayStationsState {
        val (resumeEntryId, resumeKey) = parseResume(obj)
        val station = PlayQueue(
            id = PlayQueue.LEGACY_ID,
            name = obj.optString("name").ifBlank { PlayQueue.DEFAULT_NAME },
            orderMode = PlayQueueOrder.fromName(obj.optString("orderMode")),
            entries = parseEntries(obj.optJSONArray("entries")),
            updatedAt = obj.optLong("updatedAt", 0L),
            resumeEntryId = resumeEntryId,
            resumeKey = resumeKey,
        )
        return PlayStationsState(listOf(station), PlayQueue.LEGACY_ID)
    }

    /**
     * 条目数组解析（v1/v2 共用，一期逐条防御口径原样沿续）：
     * id 缺失/type 未知/channel 条目 chatId=0/item 条目 VideoItem 坏——只跳该条
     * 其余保留（一条坏记录不拖垮整单）。
     */
    private fun parseEntries(entriesArr: JSONArray?): List<PlayQueueEntry> {
        val entries = ArrayList<PlayQueueEntry>(entriesArr?.length() ?: 0)
        if (entriesArr != null) {
            for (i in 0 until entriesArr.length()) {
                val e = entriesArr.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.isEmpty()) continue // 坏条目跳过（同片单 id/name 缺失口径）
                val skipped = e.optBoolean("skipped", false)
                when (e.optString("type")) {
                    "channel" -> {
                        val chatId = e.optLong("chatId", 0L)
                        if (chatId == 0L) continue // chatId 是频道段命根，缺失即坏条目
                        entries += PlayQueueEntry.Channel(
                            id = id,
                            chatId = chatId,
                            title = e.optString("title"),
                            skipped = skipped,
                        )
                    }
                    "item" -> {
                        val itemObj = e.optJSONObject("item") ?: continue
                        // VideoItem 单条字段缺失/类型错（toVideoItem 的 getString 会抛）
                        // 只跳该条（同收藏/片单/听单逐条防御）
                        runCatching {
                            entries += PlayQueueEntry.Item(id, itemObj.toVideoItem(), skipped)
                        }
                    }
                    // 未知 type（未来版本条目）：跳过不炸整单（读侧前向兼容）
                }
            }
        }
        return entries
    }

    /** 断点双字段解析（v1 顶层/v2 台对象共用）：缺失/NULL 哨兵/空串一律回落 null（从头播语义） */
    private fun parseResume(obj: JSONObject): Pair<String?, String?> {
        val resumeEntryId = if (obj.has("resumeEntryId") && !obj.isNull("resumeEntryId"))
            obj.optString("resumeEntryId").ifBlank { null } else null
        val resumeKey = if (obj.has("resumeKey") && !obj.isNull("resumeKey"))
            obj.optString("resumeKey").ifBlank { null } else null
        return resumeEntryId to resumeKey
    }

    /**
     * 接棒台解析(R392 #5 增量字段,parseResume 同款 NULL 哨兵口径):缺失/NULL
     * 哨兵/空串一律回落 null(=不接,向后兼容:旧档无键→不接);v1 段不读
     * (parseStationV1 冻结零改——一期档无接棒概念,迁移台恒不接合法)。
     */
    private fun parseChainTo(obj: JSONObject): String? =
        if (obj.has("chainTo") && !obj.isNull("chainTo"))
            obj.optString("chainTo").ifBlank { null } else null

    /**
     * 全量覆盖写播放台列表（调用方=FeedPlayQueuePart 已在模型内维护好各台顺序与
     * 200 截尾；只写 v2——迁移在读侧发生）；写后重建缓存。
     */
    @Synchronized
    fun savePlayStations(state: PlayStationsState) {
        val obj = JSONObject().apply {
            put("v", 2)
            put("activeId", state.activeId ?: JSONObject.NULL)
            put("stations", JSONArray().apply {
                state.stations.forEach { q ->
                    put(stationJson(q))
                }
            })
        }
        writeText(playQueueFile, obj.toString())
        playStationsCache = state
    }

    /** 单台 JSON（v2 stations 数组元素；entries 写法与一期逐字段对称） */
    private fun stationJson(q: PlayQueue): JSONObject = JSONObject().apply {
        put("id", q.id)
        put("name", q.name)
        put("orderMode", q.orderMode.name)
        put("entries", JSONArray().apply {
            q.entries.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("skipped", e.skipped)
                    when (e) {
                        is PlayQueueEntry.Channel -> {
                            put("type", "channel")
                            put("chatId", e.chatId)
                            put("title", e.title)
                        }
                        is PlayQueueEntry.Item -> {
                            put("type", "item")
                            put("item", e.item.toJson())
                        }
                    }
                })
            }
        })
        put("updatedAt", q.updatedAt)
        put("resumeEntryId", q.resumeEntryId ?: JSONObject.NULL)
        put("resumeKey", q.resumeKey ?: JSONObject.NULL)
        // R392 #5 接棒台三态落盘(null=不接/next 哨兵=下一台/指定台 id)
        put("chainTo", q.chainTo ?: JSONObject.NULL)
    }

    // ---------------- 分屏画布记忆（GAPS3 #8） ----------------

    private val gridCanvasFile = File(dir, "grid_canvas.json")

    // 内存缓存（与文件中其他缓存同一模式：写时失效重建；null 也是合法值，故另加 loaded 门闩）
    @Volatile private var gridCanvasCache: GridCanvasSnapshot? = null
    @Volatile private var gridCanvasLoaded = false

    /**
     * 读取分屏画布快照（上次退出分屏时落盘的槽位/跨度/顺序/缩放/关格表 + 条目本体表）。
     * 文件不存在或损坏返回 null（调用方按无记忆处理，走默认初始画布）；缓存命中时不碰磁盘。
     */
    @Synchronized
    fun loadGridCanvas(): GridCanvasSnapshot? {
        if (gridCanvasLoaded) return gridCanvasCache
        val text = readText(gridCanvasFile)
        val snap: GridCanvasSnapshot? = if (text == null) {
            null
        } else {
            runCatching {
                val obj = JSONObject(text)
                val slotsArr = obj.optJSONArray("slots")
                val slots = ArrayList<GridCanvasSlot>(slotsArr?.length() ?: 0)
                if (slotsArr != null) {
                    for (i in 0 until slotsArr.length()) {
                        val o = slotsArr.optJSONObject(i) ?: continue
                        val slotId = o.optString("slotId")
                        val contentKey = o.optString("contentKey")
                        if (slotId.isEmpty() || contentKey.isEmpty()) continue // 坏条目跳过
                        slots += GridCanvasSlot(
                            slotId = slotId,
                            contentKey = contentKey,
                            chatId = o.optLong("chatId", 0L),
                            gridX = o.optInt("x", 0),
                            gridY = o.optInt("y", 0),
                            spanW = o.optInt("w", 1),
                            spanH = o.optInt("h", 1),
                        )
                    }
                }
                if (slots.isEmpty()) return@runCatching null // 无槽位的快照没有恢复价值
                // 条目本体表：逐条防御（同收藏口径），单条坏记录只跳过该条
                val itemsArr = obj.optJSONArray("items")
                val items = LinkedHashMap<String, VideoItem>(itemsArr?.length() ?: 0)
                if (itemsArr != null) {
                    for (i in 0 until itemsArr.length()) {
                        val o = itemsArr.optJSONObject(i) ?: continue
                        runCatching { o.toVideoItem() }.getOrNull()?.let { items[it.key] = it }
                    }
                }
                // 字符串数组 → LinkedHashSet（保序去重；空/缺字段回落空集合）
                fun stringSet(name: String): Set<String> {
                    val arr = obj.optJSONArray(name) ?: return emptySet()
                    val out = LinkedHashSet<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
                    }
                    return out
                }
                GridCanvasSnapshot(
                    scale = obj.optDouble("scale", 1.0).toFloat(),
                    tx = obj.optDouble("tx", 0.0).toFloat(),
                    ty = obj.optDouble("ty", 0.0).toFloat(),
                    slots = slots,
                    closedKeys = stringSet("closed"),
                    items = items,
                    customKeys = stringSet("custom").toList(),
                    savedAt = obj.optLong("savedAt", 0L),
                )
            }.getOrNull() // 文件损坏按无记忆处理，不崩溃
        }
        gridCanvasCache = snap
        gridCanvasLoaded = true
        return snap
    }

    /** 全量覆盖写分屏画布快照（防抖 1s 与退出兜底由调用方做）；写后重建缓存 */
    @Synchronized
    fun saveGridCanvas(snap: GridCanvasSnapshot) {
        val obj = JSONObject().apply {
            put("version", 1)
            put("scale", snap.scale.toDouble())
            put("tx", snap.tx.toDouble())
            put("ty", snap.ty.toDouble())
            put("savedAt", snap.savedAt)
            put("slots", JSONArray().apply {
                snap.slots.forEach { s ->
                    put(JSONObject().apply {
                        put("slotId", s.slotId)
                        put("contentKey", s.contentKey)
                        put("chatId", s.chatId)
                        put("x", s.gridX)
                        put("y", s.gridY)
                        put("w", s.spanW)
                        put("h", s.spanH)
                    })
                }
            })
            put("closed", JSONArray().apply { snap.closedKeys.forEach { put(it) } })
            put("custom", JSONArray().apply { snap.customKeys.forEach { put(it) } })
            // 条目本体表（序列化规则同收藏）：槽位基底 + 换片后的当前内容 + 片单条目；
            // fileId 是 TDLib 会话级 id，跨进程恢复由调用方走 fileUniqueId 换发链
            put("items", JSONArray().apply { snap.items.values.forEach { put(it.toJson()) } })
        }
        writeText(gridCanvasFile, obj.toString())
        gridCanvasCache = snap
        gridCanvasLoaded = true
    }

    // ---------------- 文件读写 ----------------

    private fun readText(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (_: Exception) {
        null // 读失败按空数据处理（老口径：20+ loader 沿用；损坏敏感 loader 走 [readTextChecked]）
    }

    /**
     * R435 损坏检测读（loadFavorites/listDownloads 两个最高价值 loader 先行换装，
     * 其余同型 loader 后续照抄口径）：三态——null=文件不存在（合法空态，可缓存空表）；
     * 非 null=正常内容；上抛 [IOException]=文件在但读不出（损坏态）——调用方据此
     * 「返回空表但**不写缓存**」，读侧瞬时失败不再被缓存固化成永久空表、
     * 再被下一次全量覆盖写坐实清空。
     */
    private fun readTextChecked(file: File): String? {
        if (!file.isFile) return null
        return file.readText() // IOException 原样上抛，由调用方裁决
    }

    /** 先写临时文件再 rename，保证落盘的原子性 */
    private fun writeText(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (moveAtomic(tmp, file)) return
        // R435：rename 失败不再降级「直接覆盖写」——非原子半写正是读侧损坏源
        //（file.writeText 半途打断=半截 JSON，下次启动被当损坏处理整表弃读）。
        // 换名 .tmp2 整写重试一次；仍失败记日志并跳过本次写（调用方下次落盘
        // 自然重试），宁可少写一次不可写坏既有文件
        tmp.delete()
        val tmp2 = File(dir, file.name + ".tmp2")
        try {
            tmp2.writeText(text)
            if (moveAtomic(tmp2, file)) return
            tmp2.delete()
            Log.w("LocalStore", "落盘 rename 两次失败，跳过本次写：${file.name}")
        } catch (e: Exception) {
            tmp2.delete()
            Log.w("LocalStore", "落盘 .tmp2 重试异常，跳过本次写：${file.name} ${e.javaClass.simpleName}")
        }
    }

    /**
     * R435 原子替换移动（tmp → 正式文件）：[File.renameTo] 在 Windows 上目标已存在时
     * 稳定失败（旧代码被迫留「降级直写」的根因）；NIO [Files.move] 的
     * REPLACE_EXISTING+ATOMIC_MOVE 在 Linux（rename(2)）与 NTFS（MoveFileEx）上
     * 都是同卷原子覆盖。minSdk 26 起 NIO.2 全量在场，无需 desugaring。
     * 同目录移动不支持原子标志的冷门文件系统上退普通替换移动（仍是整文件替换非半写）。
     */
    private fun moveAtomic(src: File, dst: File): Boolean = try {
        val s = src.toPath()
        val d = dst.toPath()
        try {
            java.nio.file.Files.move(
                s, d,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        /** 观看历史上限 */
        const val MAX_HISTORY = 500

        /** 观看行为事件上限（观看报告数据源；300 对重度用户连一周都不够，提到 5000——单条约 50B，5000 条 ≈ 250KB 可忽略） */
        const val MAX_WATCH_EVENTS = 5000

        /** 续播进度上限：超出按 ts 淘汰最旧（200 太少：重度用户刷一天就顶满，提到 1000） */
        const val MAX_WATCH_PROGRESS = 1000

        /** 搜索历史上限 */
        const val MAX_SEARCH_HISTORY = 20

        /** 口味词表上限：超出按得分淘汰最低分词 */
        const val MAX_TASTE_WORDS = 300

        /** 发现页候选状态表上限：超出按"最近弃置时间 → 曝光次数"淘汰最旧 */
        const val MAX_DISCOVERY_CANDIDATE_STATES = 500

        /** 推荐赛马曝光表上限（item.key 条数）：超出按最近曝光时间淘汰最旧 */
        const val MAX_EXPOSURE_RECORDS = 3000

        /**
         * 已看身份注册表上限（日志行数）：单条 ~45B，100000 条 ≈ 4.5MB；
         * 对照 MAX_HISTORY=500——历史是"回放窗口"（存完整条目），本表是"判重底账"
         * （只存身份），容量放大 200 倍才把"已看过"这件事记得比用户刷得久。
         * R94 由 20000 提到 100000：重度用户（数百频道、累计刷数万条）实测会顶穿
         * 20000 触发 FIFO 汰换，最老的已看记录一出表即失忆、老内容被当成新内容重推；
         * 100000 行读盘一次约百毫秒级且全程命中内存缓存，代价可忽略。
         */
        const val MAX_SEEN_IDS = 100000

        /** R52 首页第一屏快照条数上限：一屏过渡用，20 条足够撑到网络首拉到货 */
        const val MAX_FEED_SNAPSHOT = 20
    }

    // ---------------- 口味词表（口味雷达的 caption 内容词积累） ----------------

    @Volatile private var tasteWordsCache: Map<String, Double>? = null

    /** 读口味词表（word → 累计得分；按得分降序返回前 [MAX_TASTE_WORDS]） */
    @Synchronized
    fun loadTasteWords(): Map<String, Double> {
        tasteWordsCache?.let { return it }
        val out = LinkedHashMap<String, Double>()
        if (tasteWordsFile.isFile) {
            runCatching {
                val obj = org.json.JSONObject(tasteWordsFile.readText())
                obj.keys().forEach { k -> out[k] = obj.optDouble(k, 0.0) }
            }
        }
        val sorted = out.entries.sortedByDescending { it.value }
            .take(MAX_TASTE_WORDS)
            .associate { it.key to it.value }
        tasteWordsCache = sorted
        return sorted
    }

    /** 喂口味词（观看时从 caption 提取）：命中词加分，超上限按得分淘汰最低 */
    @Synchronized
    fun addTasteWords(words: List<String>, score: Double = 1.0) {
        if (words.isEmpty()) return
        val cur = LinkedHashMap(loadTasteWords())
        words.forEach { w -> if (w.isNotBlank()) cur[w] = (cur[w] ?: 0.0) + score }
        val trimmed = cur.entries.sortedByDescending { it.value }
            .take(MAX_TASTE_WORDS)
            .associate { it.key to it.value }
        runCatching {
            val obj = org.json.JSONObject()
            trimmed.forEach { (k, v) -> obj.put(k, v) }
            writeText(tasteWordsFile, obj.toString())
        }
        tasteWordsCache = trimmed
    }

    // ---------------- 发现页候选状态表（软拒绝梯度 + 曝光计数，DISCOVER-DESIGN §8.2） ----------------
    //
    // 语义（梯度负反馈）：
    //  - 轻：首次"弃" → dismissCount=1 + lastDismissTs=now，30 天 TTL 内候选不再出现（可复出）；
    //  - 中：复出后再次被弃（dismissCount ≥ 1 的二次弃）→ 调用方升级进 blocked_channels 永久屏蔽；
    //    本表只记次数与时间戳，是否永久由 FeedViewModel.dismissDiscoveryCandidate 判定；
    //  - impressions：候选每次进入发现页榜单 +1，是打分器曝光疲劳（×0.85ⁿ）的 n。
    //
    // 落盘 discovery_candidates.json：JSONArray，逐条防御式解析（单条坏记录只跳该条）。

    @Volatile private var discoveryStatesCache: Map<Long, DiscoveryCandidateState>? = null

    /** 读候选状态表（chatId → 状态）；文件缺失/损坏按空表处理，不崩溃；缓存命中不碰磁盘 */
    @Synchronized
    fun loadDiscoveryCandidateStates(): Map<Long, DiscoveryCandidateState> {
        discoveryStatesCache?.let { return it }
        val text = readText(discoveryCandidatesFile)
        val map: Map<Long, DiscoveryCandidateState> = if (text == null) {
            emptyMap()
        } else {
            runCatching {
                val arr = JSONArray(text)
                val out = LinkedHashMap<Long, DiscoveryCandidateState>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optLong("chatId", 0L)
                    if (id == 0L) continue // 坏条目跳过
                    out[id] = DiscoveryCandidateState(
                        chatId = id,
                        dismissCount = obj.optInt("dismissCount", 0),
                        lastDismissTs = obj.optLong("lastDismissTs", 0L),
                        impressions = obj.optInt("impressions", 0),
                        // P1-a 新增字段：老数据无此两字段，optLong 缺省 0（= 从未试吃/未收下），天然兼容
                        lastTasteTs = obj.optLong("lastTasteTs", 0L),
                        acceptedTs = obj.optLong("acceptedTs", 0L),
                    )
                }
                out
            }.getOrDefault(emptyMap())
        }
        discoveryStatesCache = map
        return map
    }

    /** 全量覆盖写候选状态表；超上限按"最近弃置时间 → 曝光次数"淘汰最旧；写后重建缓存 */
    @Synchronized
    fun saveDiscoveryCandidateStates(map: Map<Long, DiscoveryCandidateState>) {
        val trimmed: Map<Long, DiscoveryCandidateState> = if (map.size <= MAX_DISCOVERY_CANDIDATE_STATES) {
            map
        } else {
            map.values
                .sortedWith(
                    compareByDescending<DiscoveryCandidateState> { it.lastDismissTs }
                        .thenByDescending { it.impressions },
                )
                .take(MAX_DISCOVERY_CANDIDATE_STATES)
                .associateBy { it.chatId }
        }
        val arr = JSONArray()
        trimmed.values.forEach { s ->
            arr.put(JSONObject().apply {
                put("chatId", s.chatId)
                put("dismissCount", s.dismissCount)
                put("lastDismissTs", s.lastDismissTs)
                put("impressions", s.impressions)
                put("lastTasteTs", s.lastTasteTs)
                put("acceptedTs", s.acceptedTs)
            })
        }
        writeText(discoveryCandidatesFile, arr.toString())
        discoveryStatesCache = trimmed
    }

    /** 记一次"弃"：dismissCount +1、lastDismissTs = now；返回更新后的状态（调用方据 dismissCount 判定是否升级永久屏蔽） */
    @Synchronized
    fun recordDiscoveryDismiss(chatId: Long, nowMs: Long): DiscoveryCandidateState {
        val cur = LinkedHashMap(loadDiscoveryCandidateStates())
        val prev = cur[chatId]
        val next = if (prev == null) {
            DiscoveryCandidateState(chatId, dismissCount = 1, lastDismissTs = nowMs, impressions = 0)
        } else {
            prev.copy(dismissCount = prev.dismissCount + 1, lastDismissTs = nowMs)
        }
        cur[chatId] = next
        saveDiscoveryCandidateStates(cur)
        return next
    }

    /** 批量记曝光：进入发现页榜单的候选 impressions +1（打分器曝光疲劳的数据源） */
    @Synchronized
    fun recordDiscoveryImpressions(chatIds: Collection<Long>) {
        if (chatIds.isEmpty()) return
        val cur = LinkedHashMap(loadDiscoveryCandidateStates())
        for (id in chatIds) {
            val prev = cur[id]
            cur[id] = if (prev == null) {
                DiscoveryCandidateState(id, dismissCount = 0, lastDismissTs = 0L, impressions = 1)
            } else {
                prev.copy(impressions = prev.impressions + 1)
            }
        }
        saveDiscoveryCandidateStates(cur)
    }

    /**
     * 清掉某候选的状态记录（彻底清零语义，供调试/重置通道使用）。
     * 注意：发现页"收"自 P1-a 起不再走这里——收下改记 acceptedTs（[recordDiscoveryAccept]），
     * 归因证据（曝光/试吃/过往弃记录）全量保留。
     */
    @Synchronized
    fun clearDiscoveryCandidateState(chatId: Long) {
        val cur = LinkedHashMap(loadDiscoveryCandidateStates())
        if (cur.remove(chatId) != null) saveDiscoveryCandidateStates(cur)
    }

    /**
     * 记一次"试吃"（DISCOVER-UPGRADE P1-a #1：发现漏斗补全 曝光→试吃→收/弃 的中间环）。
     * 只刷 lastTasteTs，不动曝光/软拒绝/收字段；无记录的候选新建一条全零状态只带试吃戳。
     */
    @Synchronized
    fun recordDiscoveryTaste(chatId: Long, nowMs: Long) {
        val cur = LinkedHashMap(loadDiscoveryCandidateStates())
        val prev = cur[chatId]
        cur[chatId] = prev?.copy(lastTasteTs = nowMs)
            ?: DiscoveryCandidateState(chatId, dismissCount = 0, lastDismissTs = 0L, impressions = 0, lastTasteTs = nowMs)
        saveDiscoveryCandidateStates(cur)
    }

    /**
     * 记一次"收"（DISCOVER-UPGRADE P1-a #2，替换旧的"收下即清空"）：
     * 只追加 acceptedTs 成交戳，dismissCount/lastDismissTs/impressions/lastTasteTs 全部保留——
     * "它当时被曝光了几次、试吃过没有、弃没弃过"是来源臂信誉与漏斗分析的归因证据，收下不再销毁。
     * 副作用口径（刻意接受）：用户退订后该频道再被发现时，旧曝光疲劳与弃计数延续——
     * 反复弃过的候选复出后再次被弃会直接升级永久屏蔽，语义上正是"二次确认"。
     */
    @Synchronized
    fun recordDiscoveryAccept(chatId: Long, nowMs: Long) {
        val cur = LinkedHashMap(loadDiscoveryCandidateStates())
        val prev = cur[chatId]
        cur[chatId] = prev?.copy(acceptedTs = nowMs)
            ?: DiscoveryCandidateState(chatId, dismissCount = 0, lastDismissTs = 0L, impressions = 0, acceptedTs = nowMs)
        saveDiscoveryCandidateStates(cur)
    }

    // ---------------- 发现来源臂统计（discovery_arms.json，DISCOVER-UPGRADE P1-a #3） ----------------
    //
    // 每来源臂一行 Beta 后验（α=收下命中数，β=弃未命中数；新臂从均匀先验 (0,0) 起记，
    // Thompson 采样消费侧自行 +1 先验——P1-c 落地时取样 θ~Beta(α+1, β+1)）。
    // 臂名口径（FeedViewModel.discoveryArmName 产出）：
    //   link / fwd_src / fwd_dst / comment / search_hit / co_watch / similar / radar / official / other。
    // 本轮只建表 + 收/弃挂钩点；采样分配（探索桶按比例分臂）在 P1-c 消费。
    // 落盘：JSONObject{臂名: {"alpha": x, "beta": y}}；缓存/原子写/损坏容错与本类其他表同款。

    @Volatile private var discoveryArmsCache: Map<String, DiscoveryArmStat>? = null

    /** 读来源臂统计表（臂名 → α/β）；文件缺失/损坏按空表处理，不崩溃；缓存命中不碰磁盘 */
    @Synchronized
    fun loadDiscoveryArms(): Map<String, DiscoveryArmStat> {
        discoveryArmsCache?.let { return it }
        val text = readText(discoveryArmsFile)
        val map: Map<String, DiscoveryArmStat> = if (text == null) {
            emptyMap()
        } else {
            runCatching {
                val obj = JSONObject(text)
                val out = LinkedHashMap<String, DiscoveryArmStat>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.isEmpty()) continue // 坏 key 跳过
                    val o = obj.optJSONObject(k) ?: continue // 坏条目跳过
                    out[k] = DiscoveryArmStat(
                        alpha = o.optDouble("alpha", 0.0).coerceAtLeast(0.0),
                        beta = o.optDouble("beta", 0.0).coerceAtLeast(0.0),
                    )
                }
                out.toMap()
            }.getOrDefault(emptyMap())
        }
        discoveryArmsCache = map
        return map
    }

    /** 臂命中一次（"收"）：α+1；臂名为空忽略。返回更新后的统计（调用方可观测用） */
    @Synchronized
    fun bumpDiscoveryArmHit(arm: String): DiscoveryArmStat? {
        if (arm.isBlank()) return null
        val cur = LinkedHashMap(loadDiscoveryArms())
        val prev = cur[arm] ?: DiscoveryArmStat(0.0, 0.0)
        val next = prev.copy(alpha = prev.alpha + 1.0)
        cur[arm] = next
        saveDiscoveryArms(cur)
        return next
    }

    /** 臂未命中一次（"弃"）：β+1；臂名为空忽略。返回更新后的统计（调用方可观测用） */
    @Synchronized
    fun bumpDiscoveryArmMiss(arm: String): DiscoveryArmStat? {
        if (arm.isBlank()) return null
        val cur = LinkedHashMap(loadDiscoveryArms())
        val prev = cur[arm] ?: DiscoveryArmStat(0.0, 0.0)
        val next = prev.copy(beta = prev.beta + 1.0)
        cur[arm] = next
        saveDiscoveryArms(cur)
        return next
    }

    /** 全量覆盖写来源臂统计表；写后重建缓存（原子写与本类其他表同款） */
    private fun saveDiscoveryArms(map: Map<String, DiscoveryArmStat>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, JSONObject().apply {
                put("alpha", v.alpha)
                put("beta", v.beta)
            })
        }
        writeText(discoveryArmsFile, obj.toString())
        discoveryArmsCache = map
    }

    // ---------------- 推荐赛马曝光表（exposure.json，ALGO P1 流量池赛马） ----------------

    /**
     * 读曝光表：item.key → [ExposureRecord]（累计进排序页次数 + 最近曝光毫秒）。
     * 缓存命中不碰磁盘；文件不存在/损坏返回空表。
     */
    @Synchronized
    fun loadExposureRecords(): Map<String, ExposureRecord> {
        exposureCache?.let { return it }
        val text = readText(exposureFile)
        val map = if (text == null) {
            emptyMap()
        } else {
            runCatching {
                val obj = JSONObject(text)
                val out = HashMap<String, ExposureRecord>(obj.length())
                for (k in obj.keys()) {
                    val o = obj.optJSONObject(k) ?: continue // 坏条目跳过
                    out[k] = ExposureRecord(
                        exposures = o.optInt("e", 0),
                        lastTs = o.optLong("t", 0L),
                        completed = o.optBoolean("c", false), // R110：老数据无此字段缺省 false
                    )
                }
                out
            }.getOrDefault(emptyMap())
        }
        exposureCache = map
        return map
    }

    /**
     * 全量覆盖写曝光表（tmp+rename 原子写）：超出 [MAX_EXPOSURE_RECORDS] 按 lastTs
     * 淘汰最旧。缓存的是防御性拷贝——调用方（ExposureTracker）持有的活表继续原地
     * 增量，不会污染本类"缓存实例只读"的约定。
     */
    @Synchronized
    fun saveExposureRecords(map: Map<String, ExposureRecord>) {
        val trimmed = if (map.size <= MAX_EXPOSURE_RECORDS) {
            LinkedHashMap(map)
        } else {
            val out = LinkedHashMap<String, ExposureRecord>(MAX_EXPOSURE_RECORDS)
            map.entries.sortedByDescending { it.value.lastTs }
                .take(MAX_EXPOSURE_RECORDS)
                .forEach { out[it.key] = it.value }
            out
        }
        val obj = JSONObject()
        for ((k, r) in trimmed) {
            obj.put(k, JSONObject().apply {
                put("e", r.exposures)
                put("t", r.lastTs)
                if (r.completed) put("c", true) // 缺省不写，老格式零膨胀
            })
        }
        writeText(exposureFile, obj.toString())
        exposureCache = trimmed
    }

    // ---------------- 数据迁移（备份/恢复）说明 ----------------
    //
    // filesDir/store/ 由 core/backup/BackupManager 以"整目录快照"方式打进数据包：
    // 本类未逐一点名的落盘文件（taste_radar.json、playlist_<id>.json 等）以及将来新增的
    // 落盘文件都会被自动纳入导出——新增文件无需改动备份代码。
    // 导入语义 = 整目录清旧换新 + 重启生效：进程内各 @Volatile 缓存不做失效处理
    // （冷启动后自然按新文件重建），恢复完成后调用方不得在不重启的情况下继续读写本目录。
}

/**
 * 订阅条目（store 层持久化模型）：
 * lastSeenMessageId = 追更已读水位——"用户真看到过的最旧一条"的 message id
 *（R122 起由 SubscriptionFeedSource.commitSeenProgress 按真实观看推进，
 *  打开追更源本身不再推进；水位之后（更新）的内容视为未读）。
 */
/**
 * R52 首页第一屏快照：上次退出前聚合首页头部条目的落盘形态。
 * [mode] = 写入时的网关模式（"real"/"demo"）——读取方据此避免把真实账号的快照
 * 摆进演示模式（或反之），跨模式条目播放链不同，混摆必出错。
 * [savedAtMs] = R324 落盘时点（毫秒；saveFeedSnapshot 的 "ts" 字段补读——离线
 * 降级「展示的是 N 小时前的快照」的诚实时点；旧档/坏档缺省 0=无时点不带标注）。
 */
data class FeedSnapshot(
    val items: List<VideoItem>,
    val mode: String,
    val savedAtMs: Long = 0L,
)

data class Subscription(
    val chatId: Long,
    val title: String,
    val lastSeenMessageId: Long = 0L,
)

/**
 * 画面姿态记录（store 层持久化模型，mirrored.json，R218 镜像建表 / R227 扩展复合态）：
 * rotationDeg = 手动旋转角度（归一 [0,360)：90/270 为宽高互换档）；mirrored = 屏幕系
 * 左右镜像开关。UI 侧消费方 FrameAdjustStore（快照表真身）与 FrameAdjustPolicy
 * （换轴镜像/菜单态纯函数）共用本模型。默认姿态（r=0 且 m=false）不入表。
 */
data class StoredFrameAdjust(
    val rotationDeg: Int = 0,
    val mirrored: Boolean = false,
)

/**
 * 永久屏蔽条目（store 层持久化模型，blocked_items.json）：
 * 连续 ≥2 次"不感兴趣"的内容进入本表，永不进 Feed。
 * 匹配身份优先 fileUniqueId（File.remote.uniqueId，跨会话/跨频道稳定的内容指纹——
 * 同一广告换消息重发、被转发到别的频道，消息 id 变了指纹不变，依然命中）；
 * 无指纹的条目（Mock/老数据）回落 item.key（只挡该条消息）。
 * title/channelTitle 不参与匹配，仅供"屏蔽管理"列表展示；ts 为屏蔽时刻毫秒。
 */
data class BlockedItem(
    val key: String,
    val fileUniqueId: String,
    val title: String,
    val channelTitle: String,
    val ts: Long,
)

/**
 * 观看行为事件（store 层持久化模型）：
 * 一次翻页结算产生一条——在该条目上停留 watchSec 秒（上限 60 分钟防挂机），
 * durationSec 为条目自身时长（供加权混排计算观看比例），ts 为结算时刻毫秒。
 */
data class WatchEvent(
    val chatId: Long,
    val watchSec: Int,
    val durationSec: Int,
    val ts: Long,
)

/**
 * 播放台列表快照（R391 多队列 v2 档的 store 层持久化模型，play_queue.json）：
 * stations = 全部播放台（顺序即持久化序）；activeId = 活跃台 id
 * （null=无活跃台；失联=指向已删台时调用方回落第一台）。
 */
data class PlayStationsState(
    val stations: List<PlayQueue>,
    val activeId: String?,
)

/**
 * 推荐赛马曝光记录（store 层持久化模型，exposure.json，ALGO P1 流量池赛马）：
 * exposures = 该 item.key 累计进入排序结果页的次数（页级曝光口径，由 rankWeightedPage
 * 输出时记账）；lastTs = 最近一次曝光毫秒（曝光表淘汰与排查用）；
 * completed = 完播标记（R110：进度 ≥97% 清除续播点时由 FeedViewModel 登记——
 * 续播点清掉后"完读"证据随之消失，赛马晋级取不到最强正信号，此标记补上该缺口）。
 */
data class ExposureRecord(
    val exposures: Int,
    val lastTs: Long,
    val completed: Boolean = false,
)

/**
 * 发现页候选状态（store 层持久化模型，discovery_candidates.json）：
 * 软拒绝梯度（dismissCount/lastDismissTs，30 天 TTL、二次弃升级永久屏蔽的判定数据）
 * + 曝光计数（impressions，DiscoveryRanker 曝光疲劳 ×0.85ⁿ 的 n）
 * + 漏斗归因戳（lastTasteTs/acceptedTs，DISCOVER-UPGRADE P1-a：
 *   试吃显式记录 + "收"保留归因改记成交戳；老数据无此两字段，反序列化缺省 0）。
 */
data class DiscoveryCandidateState(
    val chatId: Long,
    val dismissCount: Int,   // 被"弃"次数：0=未弃过；1=软拒绝中；≥2=已升级永久屏蔽（留档）
    val lastDismissTs: Long, // 最近一次被弃时刻（毫秒）；0 = 从未被弃
    val impressions: Int,    // 累计进榜曝光次数
    val lastTasteTs: Long = 0L, // 最近一次"试吃"时刻（毫秒）；0 = 从未试吃
    val acceptedTs: Long = 0L,  // "收"下成交时刻（毫秒）；0 = 未收过（收下不再清记录，归因保留）
)

/**
 * 发现来源臂的 Beta 后验底账（store 层持久化模型，discovery_arms.json，
 * DISCOVER-UPGRADE P1-a #3）：α = 累计"收"命中数，β = 累计"弃"未命中数；
 * 均匀先验 (+1,+1) 由 Thompson 采样消费侧（P1-c）取样时自行叠加，本表只记原始计数。
 */
data class DiscoveryArmStat(
    val alpha: Double,
    val beta: Double,
)

/**
 * 长视频续播进度（store 层持久化模型）：
 * key = item.key，posMs = 已播位置毫秒，ts = 该进度最后更新时间毫秒
 * （淘汰与排序依据，列表恒为 ts 倒序）。
 */
data class ProgressEntry(
    val key: String,
    val posMs: Long,
    val ts: Long,
)

/**
 * 观看历史条目（store 层内存模型，history.json）：
 * key = item.key，ts = 观看时刻毫秒，item = 完整条目本体（自 G4 修复起随记录落盘，
 * 供重启后"已看历史"源直接回放）；老格式记录只有 key/ts，item 为 null。
 */
private data class HistoryEntry(
    val key: String,
    val ts: Long,
    val item: VideoItem?,
)

/**
 * 播放列表元数据（store 层持久化模型，playlists.json）：
 * id 为本机唯一标识（同时是条目文件名 playlist_<id>.json 的一部分，只含文件名安全字符）；
 * itemKeys 是该片单条目 key 的轻量镜像（卡片条数展示免解析 playlist_<id>.json 大文件，
 * 与条目文件由调用方同事务维护一致）；createdAt/updatedAt 为毫秒时间戳，
 * updatedAt 只在内容增删时推进（改名不算内容更新）。
 */
data class PlaylistMeta(
    val id: String,
    val name: String,
    val itemKeys: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 下载完成记录（store 层持久化模型，downloads.json）：
 * key = item.key；title = 展示标题（下载时取 caption 首行，空则回退频道名）；
 * path = 落盘位置（API 29+ 是 "Movies/TGFeed/xxx" 展示相对路径，API 26–28 是绝对路径），
 * ts = 下载完成时刻毫秒；列表恒为 ts 倒序（最新在前）。
 */
data class DownloadRecord(
    val key: String,
    val title: String,
    val channelTitle: String,
    val path: String,
    val ts: Long,
    val sizeBytes: Long,
    // 播放恢复用（带默认值，老数据反序列化缺省）
    val chatId: Long = 0L,
    val messageId: Long = 0L,
    val durationSec: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    // 媒体类型（0=视频 1=图片 2=音频；带默认值，老数据缺省 0=视频恰好正确）
    val mediaType: Int = 0,
) {
    /**
     * 还原成可播条目：本地文件路径作 playUri 直接播，不走 ensurePlayable 链。
     * mediaType 一并带上——图/音条目才能分派到 PhotoCard/AudioCard，不再被当视频起播（G1 连锁修复）
     */
    fun toVideoItem(): dev.tgfeed.core.td.VideoItem = dev.tgfeed.core.td.VideoItem(
        key = key,
        chatId = chatId,
        messageId = messageId,
        channelTitle = channelTitle,
        caption = title,
        durationSec = durationSec,
        width = width,
        height = height,
        fileId = -1, // 本地文件，无远端 id
        sizeBytes = sizeBytes,
        playUri = path,
        mediaType = mediaType,
    )
}

/**
 * 分屏画布槽位快照（store 层持久化模型，grid_canvas.json 的 slots 数组元素）：
 * slotId = 槽位稳定身份（初始 = 基底内容 key）；contentKey = 退出时该槽正在播的内容 key
 * （播完换片后与 slotId 不同）；gridX/gridY/spanW/spanH = 退出时的画布逻辑格位与跨度
 * （格位仅作记录，恢复时展示位次由槽位顺序 + 跨度经流式排位重算）；
 * chatId = 内容所属频道（内容条目救不回时"同频道新片替位"的匹配依据）。
 */
data class GridCanvasSlot(
    val slotId: String,
    val contentKey: String,
    val chatId: Long,
    val gridX: Int,
    val gridY: Int,
    val spanW: Int,
    val spanH: Int,
)

/**
 * 分屏画布快照（GAPS3 #8，grid_canvas.json）：
 * scale/tx/ty = 画布变换（缩放 + 平移）；slots 顺序即槽位展示顺序（恢复时写回 orderOverride）；
 * closedKeys = 被 × 关掉的格表；items = 条目本体表（基底/当前内容/片单条目 key → 完整
 * VideoItem，序列化规则同收藏；fileId 为会话级 id，跨进程恢复需经
 * FeedViewModel.reviveDetachedItems 按 fileUniqueId 换发）；customKeys = 自定义片单内容
 * key 顺序（非片单模式为空表）。
 */
data class GridCanvasSnapshot(
    val scale: Float,
    val tx: Float,
    val ty: Float,
    val slots: List<GridCanvasSlot>,
    val closedKeys: Set<String>,
    val items: Map<String, VideoItem>,
    val customKeys: List<String>,
    val savedAt: Long,
)
