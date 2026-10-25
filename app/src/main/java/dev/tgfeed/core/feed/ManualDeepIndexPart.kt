package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R191 用户手动指定频道建立深度索引（用户设计：左抽屉频道列表行加「深度索引」按钮，
 * 点按=立即对该频道建深索引）。
 *
 * 分两层（与 IndexTieringPart 同款结构——纯函数在上 JVM 可单测，VM 实现在下）：
 *  1. **钉住集合存取**：[parseChatIdCsv]/[chatIdCsvOf]（AppPrefs.deepIndexPinnedChatIds
 *     的序列化内核，逗号分隔 chatId 沿用 searchGroupChatIds 既有范式）；
 *  2. **行状态台账**：[ManualIndexRowState] + 纯转移函数（钉住/开爬/进度/完成/取消钉
 *     ——左抽屉频道行按钮的渲染依据）。**台账只收钉住频道**（[FeedViewModel.requestDeepIndex]
 *     创建条目）——调度器全库爬取对台账零扰动（无条目即零发射，重组开销有界）。
 *
 * 档位语义（唯一权威在 [IndexTierPolicy.evaluateTier] 规则 0）：钉住=T0 等待遇
 * （50k 全量上限+头刷提频+采样闸豁免——R163 全套 T0 语义），自动评估**不降档**
 * （用户意志高于丰度/互动信号）；**未关注也可钉**（钉=深索引授权，不等关注）；
 * 取消钉=移除集合回自动评估（复位口径 [IndexTierPolicy.manualUnpinRestoreTier]）。
 *
 * 立即建库路径：点按后调度器活着 → 优先车道插队（R163 B4 的 M1/M9 同一插队口，
 * 不新增第二发送口——预算内插队不绕配额：indexChannel 内部 crawlBudget 照记，
 * 时机闸/探索配额对优先车道不适用）；调度器没跑（自动索引关/未启动）→ 直接起
 * IO 建库协程（设置页手动入口同款链路，单飞闸防同频道双爬）。
 */

// ---------------- 纯函数（JVM 可单测） ----------------

/**
 * 逗号分隔 chatId 解析（[dev.tgfeed.core.prefs.AppPrefs.deepIndexPinnedChatIds] 的
 * 存取内核）：非法项丢弃（坏值不崩——读取侧按空表/部分表重建）；集合去重。
 */
fun parseChatIdCsv(raw: String): Set<Long> =
    raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

/** chatId 集合序列化：稳定序（升序）输出——同集合写盘字节幂等，便于 diff 与校验 */
fun chatIdCsvOf(ids: Set<Long>): String =
    ids.sorted().joinToString(",") { it.toString() }

/**
 * R191 频道行「深度索引」按钮的行状态（台账条目只存在于**钉住频道**）：
 *  - [pinned]：钉住态（填充色图标）；
 *  - [building]：本轮在爬（spinner + 「已索引 N 条」行内进度）；
 *  - [indexedCount]：鲜活计数（爬取中=onProgress 累计值；完成后=DB 终值）——
 *    UI 信号行「索引共 N 条」与统计快照（channelCardMeta，5min 轮次刷新）取大者；
 *  - [doneAtMs]：最近一次建库完成时刻（>0 且非 building = ✓ 闪示态，
 *    [MANUAL_INDEX_CHECK_MS] 后回落钉住填充态）。
 */
data class ManualIndexRowState(
    val pinned: Boolean = true,
    val building: Boolean = false,
    val indexedCount: Int = 0,
    val doneAtMs: Long = 0L,
)

/** 完成态 ✓ 闪示时长（毫秒）：打勾短暂可见后回落「已钉住」填充态 */
internal const val MANUAL_INDEX_CHECK_MS = 5_000L

/** 转移·钉住请求（点按）：置钉 + 开爬（清上一轮 ✓ 戳） */
fun manualRowOnPinRequested(state: ManualIndexRowState?): ManualIndexRowState =
    (state ?: ManualIndexRowState()).copy(pinned = true, building = true, doneAtMs = 0L)

/** 转移·取消钉：在爬则保留 building（本轮照跑完、行内进度继续）；否则返回 null=移除台账 */
fun manualRowOnUnpinned(state: ManualIndexRowState): ManualIndexRowState? =
    if (state.building) state.copy(pinned = false) else null

/** 转移·开爬（派工侧钩子）：building=true + 清 ✓ 戳（新一轮开始） */
fun manualRowOnStart(state: ManualIndexRowState): ManualIndexRowState =
    state.copy(building = true, doneAtMs = 0L)

/** 转移·页进度（indexChannel onProgress 回调，累计口径） */
fun manualRowOnProgress(state: ManualIndexRowState, n: Int): ManualIndexRowState =
    state.copy(building = true, indexedCount = n.coerceAtLeast(0))

/** 转移·完成：building=false + 终值计数 + ✓ 戳 */
fun manualRowOnDone(state: ManualIndexRowState, finalCount: Int, nowMs: Long): ManualIndexRowState =
    state.copy(building = false, indexedCount = finalCount.coerceAtLeast(0), doneAtMs = nowMs)

/** 渲染判定·✓ 闪示态（钉住 + 不在爬 + 本会话完成过且戳未被定时器清掉） */
fun manualRowShowCheck(state: ManualIndexRowState): Boolean =
    state.pinned && !state.building && state.doneAtMs > 0L

// ---------------- VM 实现 ----------------

/**
 * 点按「深度索引」=钉住 + 立即建库（用户动作=最高优先级）：
 *  1. 行台账即时置 building（spinner 即时反馈，主线程零 DB 触碰）；
 *  2. IO 协程：钉住集合落盘（AppPrefs，读写锁内合并防连点竞态）→ 档位评估
 *     （钉住分支直通 T0，[IndexTieringPart.evaluateAndSetTierImpl]）；
 *  3. 立即建库：调度器活着 → 优先车道插队（预算内插队）；调度器没跑 → 直接
 *     建库协程（进度/收尾同链路钩子）。
 * 幂等：重复点按同一频道由 UI 态（钉住=确认取消弹窗）挡住；集合合并天然幂等。
 */
internal fun FeedViewModel.requestDeepIndexImpl(chatId: Long) {
    if (chatId == 0L) return
    _manualIndexRows.update { rows ->
        rows + (chatId to manualRowOnPinRequested(rows[chatId]))
    }
    viewModelScope.launch(Dispatchers.IO) {
        // 钉住集合读改写（锁内合并——快速连点不同频道时不互覆盖）
        val pinned = manualPinMutex.withLock {
            val next = prefs.deepIndexPinnedChatIds + chatId
            runCatching { prefs.deepIndexPinnedChatIds = next }
            deepIndexPinnedChatIds = next
            next
        }
        android.util.Log.d(
            "FeedViewModel",
            "R191 手动钉住深索引：chatId=$chatId（钉住集合 ${pinned.size} 个）→ T0 等待遇，自动评估不降档",
        )
        // 档位 T0 落库（评估钉住分支直通；升档 urgent 的优先插队在评估内已做）
        runCatching { evaluateAndSetTierImpl(chatId) }
        // 立即建库：优先车道（预算内插队）或直接建库（调度器没跑）
        if (indexSchedulerAlive) {
            enqueuePriorityCrawlImpl(chatId)
        } else {
            runCatching {
                deepIndex.indexChannel(gateway, chatId, tiering = indexTiering) { n ->
                    onManualIndexRowProgressImpl(chatId, n)
                }
            }.onFailure { _error.value = "深度索引构建失败：${it.message}" }
            onManualIndexRowFinishedImpl(chatId)
        }
    }
}

/**
 * 取消钉住（确认弹窗后调用）：移除钉住集合，频道回自动评估——
 *  - 分档开 → 档位复位 LEGACY 后完整重估（钉住期间的 T0 不被「维持现状」规则锚住，
 *    用户意志撤回后档位回到 D/R 信号本来面目）；
 *  - 分档关 → 复位 LEGACY（现状等效）。
 * 已建内容不动（配额收紧只影响后续爬取深度）；在爬轮次照跑完（行内进度继续）。
 */
internal fun FeedViewModel.cancelDeepIndexPinImpl(chatId: Long) {
    if (chatId == 0L) return
    _manualIndexRows.update { rows ->
        val cur = rows[chatId] ?: return@update rows
        val next = manualRowOnUnpinned(cur) ?: return@update rows - chatId
        rows + (chatId to next)
    }
    viewModelScope.launch(Dispatchers.IO) {
        val pinned = manualPinMutex.withLock {
            val next = prefs.deepIndexPinnedChatIds - chatId
            runCatching { prefs.deepIndexPinnedChatIds = next }
            deepIndexPinnedChatIds = next
            next
        }
        android.util.Log.d(
            "FeedViewModel",
            "R191 取消钉住：chatId=$chatId（余 ${pinned.size} 个）→ 回自动评估",
        )
        runCatching {
            when (val restore = IndexTierPolicy.manualUnpinRestoreTier(indexTiering.enabled)) {
                null -> {
                    // 分档开：复位未分类态再重估（evaluate 的「维持现状」不会锚住 T0）
                    deepIndex.setTier(chatId, IndexTierPolicy.IndexTier.LEGACY)
                    evaluateAndSetTierImpl(chatId)
                }
                else -> deepIndex.setTier(chatId, restore)
            }
        }
    }
}

/**
 * 派工侧钩子·开爬（FeedIndexPart.dispatchCrawl 调用）：台账有条目才置 building
 * （=钉住频道；全库常规爬取对台账零扰动——无条目返回原引用，StateFlow 同值不发射）。
 */
internal fun FeedViewModel.onManualIndexRowStartImpl(chatId: Long) {
    _manualIndexRows.update { rows ->
        val cur = rows[chatId] ?: return@update rows
        rows + (chatId to manualRowOnStart(cur))
    }
}

/**
 * 派工侧钩子·页进度（indexChannel onProgress 回调，累计已索引条数）：同样只动
 * 既有条目（IO 线程调用；StateFlow 同值合流，页粒度天然限频）。
 */
internal fun FeedViewModel.onManualIndexRowProgressImpl(chatId: Long, n: Int) {
    _manualIndexRows.update { rows ->
        val cur = rows[chatId] ?: return@update rows
        rows + (chatId to manualRowOnProgress(cur, n))
    }
}

/**
 * 派工侧钩子·收尾（dispatchCrawl finally / 手动直接建库路径）：终值计数=DB 现值
 * （chatId 索引命中 COUNT，毫秒级）+ ✓ 戳（[MANUAL_INDEX_CHECK_MS] 后定时清掉回落
 * 钉住填充态）；已取消钉（非 building 台账持有者）收尾即移除条目。
 */
internal fun FeedViewModel.onManualIndexRowFinishedImpl(
    chatId: Long,
    nowMs: Long = System.currentTimeMillis(),
) {
    val finalCount = runCatching { deepIndex.indexedCount(chatId) }.getOrDefault(0)
    var doneStamp = 0L
    _manualIndexRows.update { rows ->
        val cur = rows[chatId] ?: return@update rows
        if (!cur.pinned) return@update rows - chatId // 取消钉后收尾：台账回收
        doneStamp = nowMs
        rows + (chatId to manualRowOnDone(cur, finalCount, nowMs))
    }
    if (doneStamp != 0L) {
        viewModelScope.launch {
            delay(MANUAL_INDEX_CHECK_MS)
            clearManualIndexRowDoneMarkImpl(chatId, doneStamp)
        }
        android.util.Log.d(
            "FeedViewModel",
            "R191 手动深索引完成：chatId=$chatId 已索引 $finalCount 条",
        )
    }
}

/** ✓ 戳清除（定时器到点；戳已被新一轮覆盖则不动——新一轮自带新戳） */
private fun FeedViewModel.clearManualIndexRowDoneMarkImpl(chatId: Long, doneStamp: Long) {
    _manualIndexRows.update { rows ->
        val cur = rows[chatId] ?: return@update rows
        if (cur.doneAtMs != doneStamp) return@update rows
        rows + (chatId to cur.copy(doneAtMs = 0L))
    }
}

/**
 * 启动载入（VM init 调用）：钉住集合 prefs → 内存镜像 + 行台账（全部 building=false
 * ——进程重启后没有幸存爬取）。同步毫秒级（SharedPreferences 内存缓存读）。
 */
internal fun FeedViewModel.loadManualDeepIndexRowsImpl() {
    runCatching {
        val pinned = prefs.deepIndexPinnedChatIds
        deepIndexPinnedChatIds = pinned
        if (pinned.isNotEmpty()) {
            _manualIndexRows.value = pinned.associateWith { ManualIndexRowState(pinned = true) }
            android.util.Log.d(
                "FeedViewModel",
                "R191 钉住深索引集合载入：${pinned.size} 个频道（T0 等待遇）",
            )
        }
    }
}
