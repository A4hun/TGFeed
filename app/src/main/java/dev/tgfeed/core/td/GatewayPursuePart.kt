package dev.tgfeed.core.td

import android.util.Log
import dev.tgfeed.core.td.VideoItem
import io.github.tdlibandroid.ktx.updatesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ---------------- R229 追更 tab 网关腿（被动更新流 + 每频道近期媒体） ----------------

/** 本文件日志 tag（追更数据链专用，真机取证用 R229 前缀） */
private const val PURSUE_TAG = "R229Pursue"

/**
 * 追更被动流中枢：TDLib `UpdateNewMessage` 的浅消费者。
 *
 * 机制（「被动 TDLib 更新流，零 Search 族」的任务书口径）：
 *  - TDLib 常连接实时把全部会话的新消息推给客户端（聊天列表能实时刷新的同一机制），
 *    本中枢只订阅不请求——**零 Search 族**（不碰 SearchPublicChat/SearchChatsOnServer/
 *    SearchChatMessages），零额外拉取成本；
 *  - 频道/群口径过滤：chatId < 0（频道/超级群/普通群都是负 id；私聊/Saved Messages
 *    是正 id——与 [listSearchableChatIds] 的会话全集同域）；
 *  - 媒体转换复用 [toVideoItem]（三类型全映射+封面铸造+标题懒解析——R216 供给链件），
 *    非媒体消息转换得 null 自然丢弃；
 *  - SharedFlow 无 replay、缓冲 512 溢出丢最老：订阅方（VM）没跟上也只丢追更卡原料，
 *    绝不反压 TDLib 更新总线；网关销毁时 scope 取消即散场。
 *
 * 惰性起跑：首个订阅者触达 [pursueEvents] 时点火收集协程（幂等）——没人看追更 tab
 * 的会话零开销（协程都不起）。
 */
internal class PursueEventHub(private val gw: TdLibGateway) {

    private val _events = MutableSharedFlow<VideoItem>(
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 追更被动事件流（媒体条目；订阅即收，无 replay） */
    val events: SharedFlow<VideoItem> = _events

    private val started = AtomicBoolean(false)

    /** 点火（幂等）：UpdateNewMessage 收集协程——与网关内其余 4 个收集器同款范式 */
    fun startIfNeeded() {
        if (!started.compareAndSet(false, true)) return
        gw.scope.launch(Dispatchers.IO) {
            runCatching {
                gw.client.updatesOf<TdApi.UpdateNewMessage>().collect { upd ->
                    val chatId = upd.message.chatId
                    if (chatId >= 0L) return@collect // 频道/群口径（负 id）
                    runCatching { gw.toVideoItem(chatId, upd.message, null) }
                        .getOrNull()
                        ?.let { _events.tryEmit(it) }
                }
            }.onFailure {
                Log.w(PURSUE_TAG, "追更被动流收集失败（静默，不影响主链路）: ${it.message}")
            }
        }
    }
}

/** 追更中枢登记表：网关弱键（网关销毁不泄漏）；同网关幂等单例 */
private val pursueHubs: MutableMap<TdLibGateway, PursueEventHub> =
    Collections.synchronizedMap(WeakHashMap<TdLibGateway, PursueEventHub>())

/**
 * 追更被动流入口（VM 消费）：首次触达点火收集协程，之后直取 SharedFlow。
 * 订阅协程死掉（网关 scope 取消）后重进会重新拿 hub（started 幂等保护内协程不重启——
 * scope 已死场景下 VM 本也在重建，安全）。
 */
internal fun TdLibGateway.pursueEvents(): Flow<VideoItem> {
    val hub = synchronized(pursueHubs) { pursueHubs.getOrPut(this) { PursueEventHub(this) } }
    hub.startIfNeeded()
    return hub.events
}

/**
 * 追更 bootstrap 频道清单：会话全集（[listSearchableChatIds]，R216 id-only 枚举
 * TTL 5 分钟——免 GetChat 清扫的那条）取**前 [limit] 个负 id 会话**。
 *
 * 清单序的语义：主列表在前（GetChats 按 chat order≈最近活跃序返回）→ 前缀即
 * 「最近活跃的频道/群」——追更窗（24h）最相关的频道天然排在前面。置顶会话
 * 恒在头部的偏置记录在案（置顶=用户重要频道，偏置可接受）。
 * **R239 全频道覆盖**：limit 传 [Int.MAX_VALUE] 即全量（用户不满⑥「有的频道
 * 更了很多但没展示」——24 上限撤销；清单序保持=活跃频道先出先显的渐进序）。
 * 失败/空清单 → 空表（调用方空态诚实降级，R183 三纪律继承）。
 */
internal suspend fun TdLibGateway.pursueRecentChatIds(limit: Int): List<Long> = withContext(Dispatchers.IO) {
    runCatching { listSearchableChatIds() }
        .getOrDefault(emptyList())
        .filter { it < 0L }
        .take(limit)
        .also {
            Log.d(PURSUE_TAG, "时间线频道清单：候选=${it.size}（清单序=最近活跃前缀）")
        }
}

/**
 * 追更 bootstrap 单频道近期媒体：[fetchFromChannel]（GetChatHistory 本地优先+
 * sendReq 10s 超时+toVideoItem 三类型映射——R216/R169 加固供给链件，零 Search 族）
 * 的追更口径包装：
 *  - `maxRounds=2`：每频道最多翻 2 页×50 条消息（活跃媒体频道首页即够 8 条；
 *    文字频道两轮封顶不深挖——追更要的是「最近的媒体」，不是翻历史）；
 *  - `cascadeBudget=0`：**关评论级联**（追更卡只要主帖媒体，不放大请求量——
 *    H2 追更轮询同款口径的更狠版）；
 *  - 单频道失败 → 空表跳过（不阻塞其余频道）。
 */
internal suspend fun TdLibGateway.pursueChannelRecentMedia(chatId: Long, limit: Int): List<VideoItem> =
    pursueChannelRecentMediaPage(chatId, limit)?.items ?: emptyList()

/**
 * R239 时间线单频道近期媒体**页版**（bootstrap 正路的取数口）：与
 * [pursueChannelRecentMedia] 同一口径，额外透出 [FeedPage.nextCursor]
 * （页尾最老 messageId——历史轮的每频道游标原料）；null=单频道失败（调用方
 * 跳过不阻塞其余频道）。
 */
internal suspend fun TdLibGateway.pursueChannelRecentMediaPage(chatId: Long, limit: Int): FeedPage? =
    withContext(Dispatchers.IO) {
        runCatching {
            fetchFromChannel(
                chatId,
                fromMessageId = 0L,
                limit = limit,
                query = null,
                maxRounds = 2,
                cascadeBudget = java.util.concurrent.atomic.AtomicInteger(0),
            )
        }.getOrNull()
    }

/**
 * R239 时间线**历史轮**单频道页（用户修正②：历史翻页直连 GetChatHistory 游标
 * 推进）：从该频道游标（bootstrap 页尾最老 msgId）继续向前取一页——
 *  - `maxRounds=1`：每轮每频道恰 1 次 GetChatHistory（50 条消息扫描，一轮
 *    全频道并发的预算口径——网络纪律：并发 5+超时+R208 闸在 VM 侧收口）；
 *  - `cascadeBudget=0`：关评论级联（同 bootstrap 口径）；
 *  - 返回 null=失败（游标不动，下轮重试）；nextCursor=null=该频道到底。
 */
internal suspend fun TdLibGateway.pursueChannelHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): FeedPage? =
    withContext(Dispatchers.IO) {
        runCatching {
            fetchFromChannel(
                chatId,
                fromMessageId = fromMessageId,
                limit = limit,
                query = null,
                maxRounds = 1,
                cascadeBudget = java.util.concurrent.atomic.AtomicInteger(0),
            )
        }.getOrNull()
    }
