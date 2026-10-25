package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.fetchFromChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- TG 广场（去中心化社交层：公开 TG 群当后端） ----------------
/** 广场服务：Mock 网关下不可用（null） */

internal fun FeedViewModel.plazaPrefsEnabled(): Boolean =
    dev.tgfeed.core.prefs.AppPrefs(app).plazaEnabled

/** 点赞上报广场（服务内自带每 key 一次 + 10s 限速，这里无需节流） */
internal fun FeedViewModel.plazaPostLikeImpl(item: VideoItem) {
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled()) plazaService?.postLike(item)
    }
}

/** 发弹幕到广场（[D] 协议；服务内截断/限速） */
internal fun FeedViewModel.plazaPostDanmakuImpl(item: VideoItem, text: String) {
    val t = text.trim()
    if (t.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled()) plazaService?.postDanmaku(item, t)
    }
}

/** 观看上报广场（采样语义在服务内） */
internal fun FeedViewModel.plazaPostWatchingImpl(item: VideoItem) {
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled()) plazaService?.postWatching(item)
    }
}

/** 频道推荐上报广场（[R] 协议；服务内每频道一次 + 10s 限速，这里无需节流） */
internal fun FeedViewModel.plazaPostChannelRecommendImpl(chatId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled()) plazaService?.postChannelRecommend(chatId)
    }
}

/** 频道互链边共享（[E] 协议批量；服务内 20 条/消息 + 30s 限速） */
internal fun FeedViewModel.plazaPostGraphEdgesImpl(edges: List<Pair<Long, Long>>) {
    if (edges.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled()) plazaService?.postGraphEdges(edges)
    }
}

/** 频道避雷标记（[B] 协议；服务内每频道一次 + 10s 限速、原因截断 30 字） */
internal fun FeedViewModel.plazaPostChannelBlockImpl(chatId: Long, reason: String) {
    val r = reason.trim()
    if (r.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled()) plazaService?.postChannelBlock(chatId, r)
    }
}

/** 全站热榜：基于最近一次广场快照的纯聚合（L×3+W×1 降序），不触发网络；空 = 未拉取/无数据 */
internal fun FeedViewModel.plazaTrendingImpl(): List<Pair<String, Int>> = plazaService?.plazaTrending() ?: emptyList()

// ---------------- P1-d 全站热榜（plazaTrending 的 key → 内容卡解析） ----------------

/** 热榜内容卡列表（热度降序，与 plazaTrending 同序）；空 = 未开启/未拉取/无数据 */

/**
 * 刷新全站热榜内容卡（IO 协程；PlazaBoardSheet 打开/手动刷新触发）：
 *  1. 快照兜底：[_plaza] 还没拉到（如刚开启广场、轮询首轮未归）时先主动拉一次；
 *     [forceFetch] = true 时无条件重拉（手动刷新语义）；
 *  2. plazaTrending() 取前 [PLAZA_BOARD_MAX] 个 key，解析出 (chatId, messageId)；
 *  3. **R232 两段发布（慢链修·用户实锤「有数据但出得慢」）**：快照聚合完成即先发
 *     一版（标题回落「频道 X」=老链反查失败的既有回落文案）——快照与反查间的
 *     整窗转圈就此消灭；反查到位后按 entry.key 原地升级真标题/封面（列表不跳）；
 *  4. **R232 反查改锚定单发+并发（慢链修）**：每频道恰 1 次 GetChatHistory——
 *     `from = 该频道最新上榜 messageId + inclusiveFirst`（深链锚定口径：锚点消息
 *     必入窗），`maxRounds=1` 不补量、`cascadeBudget=0` 关评论级联（R229 追更
 *     bootstrap 同款先例——反查只要主帖 caption+minithumbnail，级联纯属浪费）；
 *     [PLAZA_BOARD_FETCH_CONCURRENCY] 路并发（R229 同款先例，10 频道≈2 批）——
 *     老链=串行 10 频道×（1-3 轮+级联），墙钟线性叠加是「出得慢」的主嫌；
 *  5. 拉不到/页外老帖回落频道名 + 无封面（卡片仍展示热度/在看/赞，宁缺毋滥）；
 *  任何失败静默（热榜是增量收益，绝不影响主功能）；广场未开启/Mock 网关清空返回。
 */
internal fun FeedViewModel.refreshPlazaBoardImpl(forceFetch: Boolean = false) {
    if (_plazaBoardLoading.value) return // 防重入
    if (!plazaPrefsEnabled()) {
        _plazaBoard.value = emptyList()
        return
    }
    val svc = plazaService ?: run {
        _plazaBoard.value = emptyList() // Mock 网关无广场
        return
    }
    _plazaBoardLoading.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (forceFetch || _plaza.value == null) {
                _plaza.value = svc.fetchPlaza()
            }
            val snap = _plaza.value
            val parsed = svc.plazaTrending().take(PLAZA_BOARD_MAX)
                .mapNotNull { (key, heat) ->
                    parsePlazaKey(key)?.let { (chatId, msgId) -> PlazaParsedKey(key, chatId, msgId, heat) }
                }
            if (parsed.isEmpty()) {
                _plazaBoard.value = emptyList()
                return@launch
            }
            // 反查累积表（按频道批到货后原地升级发布）
            val itemByKey = HashMap<String, VideoItem>()
            val channelTitles = HashMap<Long, String>()
            // R232 第一段发布：快照聚合即出榜（标题/封面暂缺走既有回落）
            publishPlazaBoard(parsed, snap, itemByKey, channelTitles)
            // R232 锚定反查（每频道恰 1 请求、零级联）+ 批内并发：
            val td = gateway as? TdLibGateway
            if (td != null) {
                val byChannel = parsed.groupBy { it.chatId }
                for (batch in byChannel.keys.take(PLAZA_BOARD_FETCH_MAX).chunked(PLAZA_BOARD_FETCH_CONCURRENCY)) {
                    batch.map { chatId ->
                        // 锚点=该频道上榜键里最大的 messageId（最新上榜帖）：窗口=锚点
                        // 起向旧 50 条，该频道其余上榜键命中同窗（比老链「频道顶 50 条」
                        // 对老帖命中率只高不低）
                        val anchor = byChannel[chatId]!!.maxOf { it.messageId }
                        async {
                            runCatching {
                                td.fetchFromChannel(
                                    chatId = chatId,
                                    fromMessageId = anchor,
                                    limit = PLAZA_BOARD_PAGE_LIMIT,
                                    query = null,
                                    maxRounds = 1,
                                    cascadeBudget = java.util.concurrent.atomic.AtomicInteger(0),
                                    inclusiveFirst = true,
                                )
                            }.getOrNull()
                        }
                    }.awaitAll().forEach { page ->
                        // 回填该批全部页（同频道多条热榜只花一次请求——按频道分组的语义保持）
                        if (page != null) {
                            for (item in page.items) itemByKey[item.key] = item
                            val t = page.items.firstOrNull()?.channelTitle
                            if (!t.isNullOrBlank()) channelTitles[page.items.first().chatId] = t
                        }
                    }
                    // 批完成即原地升级（滚动两段：首批到货先升一半，全批到货升满）
                    publishPlazaBoard(parsed, snap, itemByKey, channelTitles)
                }
            }
            // 终态（反查全失败/异常面：回落字段与老链一致）
            publishPlazaBoard(parsed, snap, itemByKey, channelTitles)
        } finally {
            _plazaBoardLoading.value = false
        }
    }
}

/**
 * 热榜内容卡发布（R232 两段发布的共用出口——同一条映射逻辑不写两份）：
 * parsed（热度序）+ 快照计数 + 已反查到的条目/频道名 → [_plazaBoard]。
 * 标题优先级：反查 caption 首行 > 频道名 > 「频道 X」（老链回落不变）。
 */
private fun FeedViewModel.publishPlazaBoard(
    parsed: List<PlazaParsedKey>,
    snap: dev.tgfeed.core.plaza.PlazaSnapshot?,
    itemByKey: Map<String, VideoItem>,
    channelTitles: Map<Long, String>,
) {
    _plazaBoard.value = parsed.map { p ->
        val item = itemByKey[p.key]
        PlazaBoardEntry(
            key = p.key,
            chatId = p.chatId,
            messageId = p.messageId,
            heat = p.heat,
            watching = snap?.watching?.get(p.key) ?: 0,
            likes = snap?.likes?.get(p.key) ?: 0,
            title = item?.caption?.lineSequence()?.firstOrNull()
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: channelTitles[p.chatId]
                ?: "频道 ${p.chatId}",
            channelTitle = item?.channelTitle ?: channelTitles[p.chatId].orEmpty(),
            coverBytes = item?.coverBytes,
        )
    }
}

/** 热榜 key 解析的中间载体（"tg:<chatId>:<messageId>" → 拆解 + 热度分） */
internal class PlazaParsedKey(val key: String, val chatId: Long, val messageId: Long, val heat: Int)

/** 解析广场 key（"tg:<chatId>:<messageId>"）；不合规返回 null（防御：信任层已过滤，双保险） */
internal fun FeedViewModel.parsePlazaKey(key: String): Pair<Long, Long>? {
    if (!key.startsWith("tg:")) return null
    val parts = key.removePrefix("tg:").split(":")
    if (parts.size != 2) return null
    val chatId = parts[0].toLongOrNull() ?: return null
    val msgId = parts[1].toLongOrNull() ?: return null
    return chatId to msgId
}

// ---------------- P1-d 图谱边广场共享（本地新边抽样上报，给全站做冷启动） ----------------

/** 抽样命中后的待报边缓冲：攒满 [PLAZA_EDGE_BATCH_SIZE] 走一批 [E] 批量协议 */

/**
 * 本地图谱新增边的广场抽样上报（P1-d）：[PLAZA_EDGE_SAMPLE_RATE]=20% 抽样防刷屏，
 * 命中入缓冲攒批，满批经 [plazaPostGraphEdges] 上报（限速在服务内）。
 * 由 DiscoveryGraph.newEdgeListener 在持锁态回调——必须轻量非阻塞、不得回调图谱。
 */
internal fun FeedViewModel.onNewGraphEdgeForPlaza(from: Long, to: Long) {
    if (!plazaPrefsEnabled()) return
    if (kotlin.random.Random.nextFloat() >= PLAZA_EDGE_SAMPLE_RATE) return
    plazaEdgeBuffer.offer(from to to)
    // 缓冲收口：长期凑不满一批时防无限积压（超上限丢最旧）
    while (plazaEdgeBuffer.size > PLAZA_EDGE_BUFFER_MAX) plazaEdgeBuffer.poll()
    if (plazaEdgeBuffer.size >= PLAZA_EDGE_BATCH_SIZE) {
        val batch = ArrayList<Pair<Long, Long>>(PLAZA_EDGE_BATCH_SIZE)
        while (batch.size < PLAZA_EDGE_BATCH_SIZE) {
            batch += plazaEdgeBuffer.poll() ?: break
        }
        if (batch.isNotEmpty()) plazaPostGraphEdges(batch)
    }
}

internal fun FeedViewModel.startPlazaPollingImpl() {
    if (!plazaPollerStarted.compareAndSet(false, true)) return
    viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            if (plazaPrefsEnabled()) {
                _plaza.value = plazaService?.fetchPlaza()
            }
            kotlinx.coroutines.delay(60_000)
        }
    }
}

// ---------------- TL-9 弹幕时间旅行（R205 v1 纯回放） ----------------

/**
 * 弹幕时间轴 UI 状态：loading + 时间轴本体（EMPTY.events 空 + scannedRows=0 =
 * 广场未开启/未就绪/拉取失败；拉到了但 events 空 = 广场里还没弹幕——两种空
 * 语义由 UI 分别承接，不互相冒充）。
 */
data class DanmakuTimelineUiState(
    val loading: Boolean = false,
    val timeline: dev.tgfeed.core.plaza.PlazaDanmakuTimeline = dev.tgfeed.core.plaza.PlazaDanmakuTimeline.EMPTY,
)

/**
 * 刷新弹幕时间轴（回放视图数据口）：进回放面板触发一次（**不轮询**——回放是
 * 用户主动的即席动作，flood 纪律靠有界页数在 fetchDanmakuTimeline 内收口）。
 * 防重入；广场未开启/Mock 网关 → EMPTY（UI 空态承接）。
 */
internal fun FeedViewModel.refreshPlazaDanmakuTimelineImpl() {
    if (_plazaDanmakuTimeline.value.loading) return
    viewModelScope.launch(Dispatchers.IO) {
        _plazaDanmakuTimeline.value = _plazaDanmakuTimeline.value.copy(loading = true)
        val tl = runCatching {
            if (plazaPrefsEnabled()) {
                plazaService?.fetchDanmakuTimeline() ?: dev.tgfeed.core.plaza.PlazaDanmakuTimeline.EMPTY
            } else {
                dev.tgfeed.core.plaza.PlazaDanmakuTimeline.EMPTY
            }
        }.getOrDefault(dev.tgfeed.core.plaza.PlazaDanmakuTimeline.EMPTY)
        _plazaDanmakuTimeline.value = DanmakuTimelineUiState(loading = false, timeline = tl)
    }
}

// ---------------- P1-d 广场常量（热榜解析 + 图谱边共享抽样，FeedViewModel 广场段配套） ----------------

/** 全站热榜：入榜条数上限（plazaTrending 截取；key→内容卡解析成本随条数线性涨） */
private const val PLAZA_BOARD_MAX = 30

/** 全站热榜：key→内容卡反查的频道预算（每频道恰 1 次锚定请求，防 flood——R232 起请求量=频道数，不再乘级联轮次） */
private const val PLAZA_BOARD_FETCH_MAX = 10

/** 全站热榜：反查并发路数（R229 追更 bootstrap 同款先例：Semaphore 语义由 chunked 批承载） */
private const val PLAZA_BOARD_FETCH_CONCURRENCY = 5

/** 全站热榜：锚定反查窗口（锚点消息起向旧 50 条；maxRounds=1 封顶单请求） */
private const val PLAZA_BOARD_PAGE_LIMIT = 30

/** 图谱边广场共享抽样率：20%（防刷屏；[E] 协议另有 30s 全局限速 + 20 条/消息上限兜底） */
private const val PLAZA_EDGE_SAMPLE_RATE = 0.20f

/** 图谱边共享攒批条数：凑满一批走一条 [E] 批量消息（与 PlazaService.MAX_EDGES_PER_POST 同值） */
private const val PLAZA_EDGE_BATCH_SIZE = 20

/** 图谱边共享缓冲上限：长期凑不满一批时丢最旧收口，防无限积压 */
private const val PLAZA_EDGE_BUFFER_MAX = 100
