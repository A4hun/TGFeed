package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ---------------- 口味雷达（WOW-SEARCH W5：口味词自动巡逻全网公开帖子） ----------------

/**
 * 触发一次口味雷达巡逻（IO 协程）：
 * 提取口味词（watch_events 频道权重 × 频道标题分词，黑名单压通用词）
 * → 逐词 searchPublicPosts（每日免费配额，starCount 恒 0，配额见 0/超限即停）
 * → 命中频道聚合去重并进发现页候选池（来源标"雷达"）。
 *
 * 触发时机：进发现页（refreshDiscovery 顺带调用）即自动检查——TasteRadar 内部
 * "每天最多一次"节流，非今日首次调用立即空转返回，零网络成本。
 * 任何失败静默（巡逻是增量收益，绝不影响发现页既有候选与主 Feed）。
 */
internal fun FeedViewModel.runTasteRadarImpl() {
    if (_radarRunning.value) return
    _radarRunning.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val found = runCatching { tasteRadar.patrol() }
                .onFailure {
                    android.util.Log.w("FeedViewModel", "口味雷达巡逻失败: ${it.message}")
                }
                .getOrDefault(emptyList())
            if (found.isEmpty()) return@launch
            // 硬过滤：已加入/已订阅/已屏蔽/软拒绝 TTL 内不进候选池（与 refreshDiscovery 同一可见性口径；
            // 发现 = 探索未加入的新频道。R183 语义修正：已加入集合不再并入深度索引已爬频道集
            // ——深索引爬全宇宙（含未加入频道），「索引过 ≠ 已加入」；权威校验兜底）
            val subscribedNow = _subscriptions.value.mapTo(HashSet()) { it.id }
            val blockedNow = _blockedChannels.value
            val joinedNow = runCatching { gateway.listChannels() }.getOrDefault(emptyList())
                .mapTo(HashSet()) { it.id }
                .apply { addAll(subscribedNow) }
            val joinedDropped = found.count { it.id in joinedNow }
            if (joinedDropped > 0) {
                android.util.Log.d("FeedViewModel", "runTasteRadar: 已加入频道过滤 $joinedDropped 个")
            }
            val now = System.currentTimeMillis()
            val softRejected = runCatching { store.loadDiscoveryCandidateStates() }.getOrDefault(emptyMap())
                .values
                .filter { it.dismissCount > 0 && now - it.lastDismissTs < DISCOVERY_SOFT_REJECT_TTL_MS }
                .mapTo(HashSet()) { it.chatId }
            val fresh = found.filter {
                it.id !in joinedNow && it.id !in blockedNow && it.id !in softRejected
            }
            if (fresh.isEmpty()) return@launch
            _radarCandidates.value = fresh
            // 裸 ID 防线：巡逻命中但标题缺失（"频道 <id>" 占位）的候选不直接进榜——
            // 留在 _radarCandidates 里，等下一次 refreshDiscovery 统一懒解析标题后再出榜
            val (titled, untitled) = fresh.partition { it.title.isNotBlank() && !it.title.startsWith("频道 ") }
            if (untitled.isNotEmpty()) {
                android.util.Log.d("FeedViewModel", "runTasteRadar: ${untitled.size} 个裸ID候选暂缓进榜，待下次刷新解析标题")
            }
            if (titled.isEmpty()) return@launch
            // 权威"已加入"终点校验（与 refreshDiscovery 同一道闸：确认未加入才放行，
            // 已加入/不确定一律剔除；进程内缓存命中零请求，并发 [DISCOVERY_JOIN_CHECK_CONCURRENCY]）
            val realGw = gateway as? TdLibGateway
            val verified = if (realGw == null) {
                titled
            } else {
                coroutineScope {
                    titled.chunked(DISCOVERY_JOIN_CHECK_CONCURRENCY).flatMap { batch ->
                        batch.map { c ->
                            async(Dispatchers.IO) {
                                if (realGw.isJoinedChat(c.id)) null else c
                            }
                        }.map { it.await() }
                    }.filterNotNull()
                }
            }
            val authDropped = titled.size - verified.size
            if (authDropped > 0) {
                android.util.Log.d("FeedViewModel", "runTasteRadar: 权威校验剔除已加入 $authDropped 个")
                // 确认已加入的从巡逻候选池一并清掉（下次 refreshDiscovery 合并不再带它们玩）
                _radarCandidates.value = verified + untitled
            }
            if (verified.isEmpty()) return@launch
            // 巡逻完成即并入发现页候选池（来源标"雷达"，多源命中角标组合升档），
            // 不等用户手动刷新；refreshDiscovery 合并步骤也会收 _radarCandidates（双保险）
            _discoverySources.update { src ->
                val m = HashMap(src)
                for (c in verified) {
                    m[c.id] = if (m.containsKey(c.id)) "${m[c.id]}+雷达" else "雷达"
                }
                m
            }
            // 异步并入的候选没走 ranker，证据文案先给雷达通用行（下次刷新由 ranker 重算覆盖）
            _discoveryEvidence.update { ev ->
                val m = HashMap(ev)
                for (c in verified) m.putIfAbsent(c.id, listOf("雷达巡逻命中的新频道"))
                m
            }
            _discoveryCandidates.update { cur ->
                val known = cur.mapTo(HashSet()) { it.id }
                cur + verified.filterNot { it.id in known }
            }
            // 雷达异步并入的候选同样记臂归属（收/弃时记账用，与 refreshDiscovery 同口径）
            verified.forEach { discoveryArmById[it.id] = "radar" }
            // 进榜即记曝光（与 refreshDiscovery 同一口径）
            runCatching { store.recordDiscoveryImpressions(verified.map { it.id }) }
        } finally {
            _radarRunning.value = false
        }
    }
}
