package dev.tgfeed.core.td
import android.util.Log
import io.github.tdlibandroid.ktx.TdException
import io.github.tdlibandroid.ktx.updatesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
// ---------------- 跨设备云同步（Saved Messages 中转）薄 TDLib 封装 ----------------
// 原理：Saved Messages（收藏夹）是用户自己的云空间，chatId 恒等于本人 user id。
// 同步 JSON 作为文本消息存进去（进度域 V2：core/feed/ProgressSync；多域 V3：
// core/feed/MultiSync+SyncPayload），定期 EditMessageText 原地编辑同一条（不新发刷屏），
// 另一台设备 GetChatHistory 找到它读出来即可合并；本端编辑后 TG 服务端会把
// UpdateMessageContent 实时推给同账号其他在线设备的 TDLib（事件驱动拉取，R300 首开）。
// 以下方法只搬数据、不做协议解析；全程 runCatching 静默（同步是锦上添花，
// 任何失败都不得影响主功能）。
// R169（P0-2 / F1）：全部 send 过 [sendReq] 统一 10s 超时——云同步是周期环，
// 旧口径一次 native 冻死=环上协程永挂=同步永久静默死（连 W 日志都打不出）；
// 超时按失败走本文件既有 onFailure 静默收尾，下一轮照常重试。
//
// R300 期零（AGENT-DISCIPLINE §十挂账清偿 + design-multi-device-sync.md §5.1 硬前置）：
//  1. **测试闸**：一切写点（sendSyncText/editSyncText/deleteSyncMessages 之外无他）
//     先过 [TdLibGateway.accountWriteBlocked]——测试模式下绝不碰用户真实账号；
//  2. **统一发送口+全局写预算闸**：[SyncWriteBudget] 进程级单例——全域共享 30s 最小
//     写间隔 + FLOOD_WAIT 指数退避（任一写点撞 429 全域静默 max(服务端 retryAfter,
//     30s×2^n)，n 封顶 5 级）——多域扩展后写频次上界=域数×域级节流，必须有全局闸兜底，
//     严禁各域裸调 client.send（§九统一发送口同纪律）；
//  3. **可回滚**：[deleteSyncMessagesImpl] 供诊断通道「清理云同步消息」一键清
//     （用户显式确认，与 deleteTestFolder 同先例不走测试闸）。

/**
 * R300 全局云同步写预算闸（进程级单例；VM/网关重建不丢账——FLOOD_WAIT 退避窗必须
 * 比组件命长，否则重启即重置等于没有退避）。
 *
 * 口径（design §2.1 ④定稿）：
 *  - 全域共享 30s 最小写间隔（[GLOBAL_MIN_INTERVAL_MS]）：任何两个云同步写请求
 *    （SendMessage/EditMessageText/DeleteMessages 清理除外——清理是用户显式动作）
 *    之间至少隔 30s；域级 60s 节流在各同步器内部（ProgressSync 既有，MultiSync 同款），
 *    两层叠加后单域真实写间隔 ≥60s、跨域 ≥30s；
 *  - FLOOD_WAIT 指数退避：任一写点撞 429 → 全域静默到
 *    now + max(服务端 retryAfter, 30s×2^strikes)，strikes 每次撞墙 +1 封顶 5
 *    （30s→16min 上界），一次成功写清零 strikes；静默期一切写调用直接拒（返回 false），
 *    由下一轮域级节流自然重试——撞墙期连试都不试（§九：TG 洪水惩罚累进，连试续封）。
 */
internal object SyncWriteBudget {
    private const val TAG = "SyncWriteBudget"

    /** 全域最小写间隔（毫秒）：跨域写请求的最小间距 */
    private const val GLOBAL_MIN_INTERVAL_MS = 30_000L

    /** 退避基数（毫秒）：撞 429 无 retryAfter 时 30s×2^strikes */
    private const val BACKOFF_BASE_MS = 30_000L

    /** 退避级数封顶（2^5=32，上界 ≈16 分钟） */
    private const val BACKOFF_MAX_STRIKES = 5

    /** 最近一次写请求预约时刻（@Volatile：多协程并发触发；读写经 @Synchronized 闸） */
    @Volatile
    private var lastWriteAtMs = 0L

    /** 全域静默截止时刻（429 退避窗）；0 = 未静默 */
    @Volatile
    private var blockedUntilMs = 0L

    /** 连续撞墙计数（指数退避的指数；一次成功写清零） */
    @Volatile
    private var floodStrikes = 0

    /**
     * 写前预约：静默期/间隔未满 → false（调用方本轮跳过，下轮域级节流再试）；
     * 通过则当场记时刻（原子占位，防并发双写穿闸）。
     */
    @Synchronized
    fun tryEnter(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs < blockedUntilMs) {
            Log.d(TAG, "云同步写被全域静默拒（429 退避剩 ${(blockedUntilMs - nowMs) / 1000}s）")
            return false
        }
        if (nowMs - lastWriteAtMs < GLOBAL_MIN_INTERVAL_MS) return false
        lastWriteAtMs = nowMs
        return true
    }

    /**
     * 批量槽（一次推送的多片/编辑失败回落新建算「一次推送」不该各吃 30s 间隔）：
     * beginBatch 过一次完整闸（30s 间隔+静默窗），槽内 tryEnterBatch 只查静默窗不查间隔。
     * 调用方必须 finally endBatch（槽不释放会锁死后续其他域的推送）。
     */
    @Volatile
    private var batchActive = false

    @Synchronized
    fun beginBatch(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (batchActive) return false // 已有推送在飞（另一域/另一协程），本轮让位
        if (nowMs < blockedUntilMs) return false
        if (nowMs - lastWriteAtMs < GLOBAL_MIN_INTERVAL_MS) return false
        lastWriteAtMs = nowMs
        batchActive = true
        return true
    }

    /** 批量槽内写预约：只查静默窗（429 退避中连试都不试），不查 30s 间隔 */
    @Synchronized
    fun tryEnterBatch(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!batchActive) return false
        if (nowMs < blockedUntilMs) return false
        lastWriteAtMs = nowMs
        return true
    }

    @Synchronized
    fun endBatch() {
        batchActive = false
    }

    /** 一次写成功：清退避计数（静默窗若仍在也一并解除——服务端已放行） */
    @Synchronized
    fun noteSuccess() {
        floodStrikes = 0
        blockedUntilMs = 0L
    }

    /** 撞 429 上报：退避窗取 max(服务端 retryAfter, 30s×2^strikes) 不缩短（同 R44 口径） */
    @Synchronized
    fun noteFlood(retryAfterSec: Int, nowMs: Long = System.currentTimeMillis()) {
        val backoffMs = BACKOFF_BASE_MS shl floodStrikes.coerceAtMost(BACKOFF_MAX_STRIKES)
        floodStrikes = (floodStrikes + 1).coerceAtMost(BACKOFF_MAX_STRIKES + 1)
        val until = nowMs + maxOf(retryAfterSec * 1000L, backoffMs)
        if (until > blockedUntilMs) blockedUntilMs = until
        Log.w(TAG, "云同步写撞 429：全域静默 ${(blockedUntilMs - nowMs) / 1000}s（第 $floodStrikes 级退避）")
    }

    /** 诊断观察口：当前剩余静默毫秒（0=未静默；测试锚用） */
    fun blockedRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        (blockedUntilMs - nowMs).coerceAtLeast(0L)

    /** 测试专用复位（生产路径不调用） */
    @Synchronized
    internal fun resetForTest() {
        lastWriteAtMs = 0L
        blockedUntilMs = 0L
        floodStrikes = 0
    }
}

/**
 * 取 Saved Messages（收藏夹）会话 id；未登录/网络失败返回 null（调用方静默跳过）。
 * 最简路径（javap 实证 + 本文件 forwardToSaved 已实证同用法）：
 * GetMe() -> User{id: long}——Saved Messages 的 chatId 就是本人 user id，
 * 无需 CreatePrivateChat 额外建会话。
 */
internal suspend fun TdLibGateway.getSavedMessagesChatIdImpl(): Long? = runCatching {
    savedMessagesChatId?.let { return@runCatching it }
    awaitAuthorized()
    val me = sendReq(TdApi.GetMe())
    savedMessagesChatId = me.id
    me.id
}.onFailure {
    Log.w(TAG, "getSavedMessagesChatId 失败: ${it.message}")
}.getOrNull()

/**
 * 在指定会话（通常是收藏夹）找最近一条正文含 [marker] 的文本消息 id；
 * 找不到/任何失败返回 null。
 * 只翻最近 50 条：同步消息若被用户日常收藏内容压下去，视为丢失、由发送侧重建
 * （收藏夹是纯人工场景时才会沉底，代价是多发一条新同步消息，可接受）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetChatHistory(long, long, int, int, boolean) -> Messages{messages: Message[]}
 *   （与 fetchFromChannel 同一签名）
 * MessageText{text: FormattedText}；FormattedText{text: String}
 */
internal suspend fun TdLibGateway.findSyncMessageImpl(chatId: Long, marker: String): Long? = runCatching {
    awaitAuthorized()
    val history = sendReq(TdApi.GetChatHistory(chatId, 0, 0, 50, false))
    history.messages.filterNotNull().firstOrNull { m ->
        (m.content as? TdApi.MessageText)?.text?.text?.contains(marker) == true
    }?.id
}.onFailure {
    Log.w(TAG, "findSyncMessage 失败 chatId=$chatId: ${it.message}")
}.getOrNull()

/**
 * R300 多域 V3 拉取口：翻最近 50 条，返回全部正文以 [markerPrefix] 起首的文本消息
 * （messageId → 正文全文），按时间新→旧序。分片域（i/n>1）的多片都在这里一次捞齐。
 * 纯读不过写闸；找不到/任何失败返回空表。
 */
internal suspend fun TdLibGateway.findSyncMessagesImpl(chatId: Long, markerPrefix: String): List<Pair<Long, String>> = runCatching {
    awaitAuthorized()
    val history = sendReq(TdApi.GetChatHistory(chatId, 0, 0, 50, false))
    history.messages.filterNotNull().mapNotNull { m ->
        val text = (m.content as? TdApi.MessageText)?.text?.text
        if (text != null && text.startsWith(markerPrefix)) m.id to text else null
    }
}.onFailure {
    Log.w(TAG, "findSyncMessages 失败 chatId=$chatId: ${it.message}")
}.getOrDefault(emptyList())

/**
 * 发一条纯文本同步消息（首次建立同步消息用），返回新消息 id；任何失败返回 null。
 * R300 期零：写入前三道闸——accountWriteBlocked 测试闸（§十挂账清偿）+ 全域写预算闸
 * （30s 最小间隔+429 退避），撞 429 自动上报退避。
 *
 * 签名已 javap 实证（同 searchViaBot 注释）：
 * SendMessage(long, MessageTopic, InputMessageReplyTo, MessageSendOptions, ReplyMarkup,
 *   InputMessageContent) -> Message{id: long}
 * InputMessageText(FormattedText(text, 空 entities 数组), null, false)
 */
internal suspend fun TdLibGateway.sendSyncTextImpl(chatId: Long, text: String): Long? {
    // R300 期零闸①：测试模式绝不写真实账号（AGENT-DISCIPLINE §十挂账清偿）
    if (accountWriteBlocked()) {
        Log.w(TAG, "sendSyncText 被账号写保护拦截（§十测试模式，未发同步消息）")
        return null
    }
    // R300 期零闸②：全域写预算闸（30s 最小间隔+429 退避静默期连试都不试）
    if (!SyncWriteBudget.tryEnter()) return null
    return runCatching {
        awaitAuthorized()
        val msg = sendReq(
            TdApi.SendMessage(
                chatId, null, null, null, null,
                TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false),
            )
        )
        SyncWriteBudget.noteSuccess()
        Log.d(TAG, "sendSyncText -> chatId=$chatId messageId=${msg.id}（${text.length} 字）")
        msg.id
    }.onFailure {
        reportSyncFlood(it)
        Log.w(TAG, "sendSyncText 失败 chatId=$chatId: ${it.message}")
    }.getOrNull()
}

/**
 * 批量槽内发同步消息（MultiSync 分片推送专用）：测试闸照过，预算走 tryEnterBatch
 * （槽由调用方 beginBatch/endBatch 包住——一次推送的多片不该各吃 30s 全局间隔）。
 */
internal suspend fun TdLibGateway.sendSyncTextBatchedImpl(chatId: Long, text: String): Long? {
    if (accountWriteBlocked()) {
        Log.w(TAG, "sendSyncText(批) 被账号写保护拦截（§十测试模式）")
        return null
    }
    if (!SyncWriteBudget.tryEnterBatch()) return null
    return runCatching {
        awaitAuthorized()
        val msg = sendReq(
            TdApi.SendMessage(
                chatId, null, null, null, null,
                TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false),
            )
        )
        SyncWriteBudget.noteSuccess()
        Log.d(TAG, "sendSyncText(批) -> chatId=$chatId messageId=${msg.id}（${text.length} 字）")
        msg.id
    }.onFailure {
        reportSyncFlood(it)
        Log.w(TAG, "sendSyncText(批) 失败 chatId=$chatId: ${it.message}")
    }.getOrNull()
}

/** 批量槽内编辑同步消息（MultiSync 分片推送专用；闸口径同 [sendSyncTextBatchedImpl]） */
internal suspend fun TdLibGateway.editSyncTextBatchedImpl(chatId: Long, messageId: Long, text: String): Boolean {
    if (accountWriteBlocked()) {
        Log.w(TAG, "editSyncText(批) 被账号写保护拦截（§十测试模式）")
        return false
    }
    if (!SyncWriteBudget.tryEnterBatch()) return false
    return runCatching {
        awaitAuthorized()
        sendReq(
            TdApi.EditMessageText(
                chatId, messageId, null,
                TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false),
            )
        )
        SyncWriteBudget.noteSuccess()
        Log.d(TAG, "editSyncText(批) -> chatId=$chatId messageId=$messageId（${text.length} 字）")
        true
    }.onFailure {
        reportSyncFlood(it)
        Log.w(TAG, "editSyncText(批) 失败 chatId=$chatId messageId=$messageId: ${it.message}")
    }.getOrDefault(false)
}

/** 批量槽内删除同步消息（推送换代清旧片用；仍过测试闸+预算闸——推送路径不是用户显式动作） */
internal suspend fun TdLibGateway.deleteSyncMessagesBatchedImpl(chatId: Long, messageIds: List<Long>): Boolean {
    if (messageIds.isEmpty()) return true
    if (accountWriteBlocked()) return false
    if (!SyncWriteBudget.tryEnterBatch()) return false
    return runCatching {
        awaitAuthorized()
        sendReq(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), true))
        SyncWriteBudget.noteSuccess()
        true
    }.onFailure {
        reportSyncFlood(it)
        Log.w(TAG, "deleteSyncMessages(批) 失败 chatId=$chatId 条数=${messageIds.size}: ${it.message}")
    }.getOrDefault(false)
}

/**
 * 编辑既有同步消息的正文（更新语义，不新发刷屏）；成功 true，任何失败 false。
 * 调用方收到 false 时的典型处理：消息可能已被用户手删，回落 sendSyncText 新建。
 * R300 期零：同 sendSyncText 过测试闸+预算闸（编辑=写操作，§十同族）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * EditMessageText(long chatId, long messageId, ReplyMarkup replyMarkup,
 *   InputMessageContent inputMessageContent) -> Message
 *   —— 4 参版本；replyMarkup 传 null（同步消息无按钮）
 */
internal suspend fun TdLibGateway.editSyncTextImpl(chatId: Long, messageId: Long, text: String): Boolean {
    if (accountWriteBlocked()) {
        Log.w(TAG, "editSyncText 被账号写保护拦截（§十测试模式，未编辑同步消息）")
        return false
    }
    if (!SyncWriteBudget.tryEnter()) return false
    return runCatching {
        awaitAuthorized()
        sendReq(
            TdApi.EditMessageText(
                chatId, messageId, null,
                TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false),
            )
        )
        SyncWriteBudget.noteSuccess()
        Log.d(TAG, "editSyncText -> chatId=$chatId messageId=$messageId（${text.length} 字）")
        true
    }.onFailure {
        reportSyncFlood(it)
        Log.w(TAG, "editSyncText 失败 chatId=$chatId messageId=$messageId: ${it.message}")
    }.getOrDefault(false)
}

/**
 * R300 期零③可回滚口：批量删除同步消息（诊断通道「清理云同步消息」用）。
 * **必须用户显式确认后调用**（UI 层 DangerConfirmDialog 负责确认闸）；
 * 与 deleteTestFolder 同先例：这是用户确认的修复动作，不走 accountWriteBlocked 测试闸，
 * 但仍过全域写预算闸（清理也是写，撞退避窗同样不许硬打）。
 * 返回实际删除成功的批次数（1 批=一次 DeleteMessages 调用，全成或全败）。
 *
 * 签名（TdApi.DeleteMessages，TDLib 1.8 系稳定签名）：
 * DeleteMessages(long chatId, long[] messageIds, boolean revoke) -> Ok
 * revoke=true = 云端删除（Saved Messages 是自己的云空间，本设备+其他设备同账号都消失——
 * 这正是清理语义；对端下次 pull 找不到消息按「云端无数据」处理，不复活）。
 */
internal suspend fun TdLibGateway.deleteSyncMessagesImpl(chatId: Long, messageIds: List<Long>): Boolean {
    if (messageIds.isEmpty()) return true
    if (!SyncWriteBudget.tryEnter()) return false
    return runCatching {
        awaitAuthorized()
        sendReq(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), true))
        SyncWriteBudget.noteSuccess()
        Log.i(TAG, "deleteSyncMessages -> chatId=$chatId 共 ${messageIds.size} 条同步消息已清（用户显式确认）")
        true
    }.onFailure {
        reportSyncFlood(it)
        Log.w(TAG, "deleteSyncMessages 失败 chatId=$chatId 条数=${messageIds.size}: ${it.message}")
    }.getOrDefault(false)
}

/** 写点撞 429 的统一上报（code 新格式 + isFloodWait 老格式双判，R44 实证口径；retryAfter 走现成解析件） */
private fun TdLibGateway.reportSyncFlood(t: Throwable) {
    val e = t as? TdException ?: return
    if (e.code == 429 || e.isFloodWait) {
        SyncWriteBudget.noteFlood(parseRetryAfterSec(e.message))
    }
}

/**
 * 读指定消息的正文纯文本；消息不存在/已被删除/不是文本消息/任何失败返回 null。
 *
 * 签名已 javap 实证：
 * GetMessage(long chatId, long messageId) -> Message{content: MessageContent}
 * MessageText{text: FormattedText{text: String}}
 */
internal suspend fun TdLibGateway.readSyncTextImpl(chatId: Long, messageId: Long): String? = runCatching {
    awaitAuthorized()
    val msg = sendReq(TdApi.GetMessage(chatId, messageId))
    (msg.content as? TdApi.MessageText)?.text?.text
}.onFailure {
    Log.w(TAG, "readSyncText 失败 chatId=$chatId messageId=$messageId: ${it.message}")
}.getOrNull()

/**
 * R300 事件驱动拉取的信号源（design §2.1 实时性行定稿）：
 * 订阅 TDLib UpdateMessageContent 流——本账号任一在线设备（含本端）编辑了
 * Saved Messages 里的同步消息（进度域 V2 marker / 多域 V3 marker 命中即报），
 * TG 服务端实时推送到本端，[onSyncEdited] 回调点火（拉取汇聚/防抖在调用方）。
 *
 * 生命周期：随网关 scope；重复调用安全（内部单飞闸—— watcher 一个网关实例只起一条）。
 * 一切失败静默（事件驱动是增强，轮询保底仍在）。
 *
 * 签名（TdApi.UpdateMessageContent，TDLib 1.8 系稳定形态）：
 * UpdateMessageContent{chatId: long, messageId: long, newContent: MessageContent}
 */
internal fun TdLibGateway.startSyncUpdateWatcherImpl(onSyncEdited: () -> Unit) {
    if (syncUpdateWatcherStarted) return
    syncUpdateWatcherStarted = true
    scope.launch(Dispatchers.IO) {
        runCatching {
            client.updatesOf<TdApi.UpdateMessageContent>().collect { upd ->
                // 只关心 Saved Messages（本人收藏夹）里的文本编辑：同步消息候选
                val savedId = savedMessagesChatId ?: getSavedMessagesChatIdImpl() ?: return@collect
                if (upd.chatId != savedId) return@collect
                val text = (upd.newContent as? TdApi.MessageText)?.text?.text ?: return@collect
                if (text.contains(dev.tgfeed.core.feed.ProgressSync.MARKER) ||
                    text.startsWith(dev.tgfeed.core.feed.SyncPayload.MARKER_PREFIX)
                ) {
                    Log.d(TAG, "同步消息云端编辑事件到达（域=${if (text.startsWith(dev.tgfeed.core.feed.SyncPayload.MARKER_PREFIX)) "v3" else "progress"}）")
                    runCatching { onSyncEdited() }
                }
            }
        }.onFailure {
            Log.w(TAG, "同步更新监听协程退出（静默；轮询保底不受影响）: ${it.message}")
        }
    }
}
