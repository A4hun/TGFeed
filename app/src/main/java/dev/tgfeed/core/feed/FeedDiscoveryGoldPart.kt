package dev.tgfeed.core.feed

import android.util.Log
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// ---------------- R183 双宝藏发现页 VM 接线（快照单飞 + 宝藏二信号聚合 + 候选包装） ----------------

/** 宝藏二聚合信号（一个频道在用户搜索群里的被传/被搜强度——纯本地收割产物） */
internal data class DiscoveryGroupSignal(
    /** 去重的引用群数（答案库 fromGroup ∪ group_search 边 from） */
    val groupCount: Int,
    /** 热度合计（答案 hits + 边 count——「N 群在搜」「你的搜索群在传」的计数原料） */
    val endorse: Int,
    /** 最近命中毫秒时间戳（诊断用 + R211 时效性 tie-break 原料） */
    val lastTs: Long,
    /** 答案库标题线索（按钮文本↔链接配对产物；待验货候选的标题兜底，空=未知） */
    val hintTitle: String,
    /** 答案库成员数线索（0=未知——冷门判定/卡片成员芯片兜底） */
    val members: Int,
    /**
     * R183 开频道自愈的用户名线索：答案库 backfill 过 chatId 的行自带 username
     * （解析过=TDLib 已建会话的先验），透传给 ChannelBrief.username——开频道
     * 自愈的精确解析路径 + 频道主页 t.me 分享链接原料。空 = 未知（直链答案无用户名）。
     */
    val username: String = "",
    /**
     * R211 直荐口径：该频道的群传证据经用户勾选群 + USER 车道验证（存在+是频道）——
     * 推荐语升格「你的搜索群里有人搜过「X」」、公平轮转 quality 加验证档。
     */
    val verified: Boolean = false,
)

/**
 * 双宝藏候选（公平轮转的池元素）：金矿档案（宝藏一）+ 搜索群信号（宝藏二）+ 广场
 * 佐证（终版修正：降级为徽章原料不进主排序）。[gold] null = 宝藏二纯新面孔
 * （索引还没档案——「待深索引验货」形态，只有群热度背书）。
 */
internal data class DiscoveryGoldCandidate(
    val chatId: Long,
    val title: String,
    val gold: DiscoveryGoldmine.ChannelGold?,
    val signal: DiscoveryGroupSignal?,
    val plazaRecommends: Int,
    val plazaBlocks: Int,
    /** R183 username 透传（宝藏二线索；金矿索引无用户名——挂账：深索引入库，R163 地盘） */
    val username: String = "",
) {
    /** 发布给 UI 的 brief：username 透传（Chat not found 自愈 + 主页分享链接原料） */
    val brief: ChannelBrief get() = ChannelBrief(chatId, title, username.takeIf { it.isNotBlank() })
}

/** 快照文件（files 根——R170 实锤 deepindex 目录是门房辖区，删一切非数字命名 .json） */
internal fun FeedViewModel.discoveryGoldFile(): File =
    File(app.filesDir, DiscoveryGoldmine.SNAPSHOT_FILE)

/**
 * 宝藏一快照获取（R170 预热协调器纪律：陈值先回 + 后台单飞重建）：
 *  - 内存缓存/磁盘快照命中且未过期 → 直接回（毫秒级）；
 *  - 命中但已过 TTL → **陈值先回**，过期重建丢给后台单飞协程（本轮流刷不等重建）；
 *  - 无快照（首次进发现页）→ **后台单飞构建 + 本轮空手返回**（R183 真机修正：首建
 *    在 3.5GB 真库上实测可达分钟级——同步等建 = 页面转圈转三分钟；异步后首轮走
 *    空态文案「深度索引正在后台挖掘」承接，构建完成自动补一轮刷新让候选即时上屏）。
 */
internal suspend fun FeedViewModel.ensureDiscoveryGoldSnapshot(): DiscoveryGoldmine.Snapshot? {
    val file = discoveryGoldFile()
    var snap = discoveryGoldSnapshot
        ?: runCatching { DiscoveryGoldmine.loadSnapshot(file) }.getOrNull()
    if (snap != null) {
        discoveryGoldSnapshot = snap
        if (!snap.isStale()) return snap
        maybeRebuildGoldSnapshotAsync()
        return snap // 陈值先回：档案是小时级稳定事实，陈度无害
    }
    // 无快照：后台单飞构建（构建中再次进页/刷新也走这里——CAS 失败直接空手返回，
    // 防重入闸防双建；完成后的自动补刷把候选送上来）
    if (discoveryGoldBuilding.compareAndSet(false, true)) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val built = buildGoldSnapshotNow()
                // 首建完成 → 自动补一轮刷新（用户还停在发现页时候选自动上屏；
                // refreshDiscoveryImpl 自带防重入闸，与在飞刷新天然互斥）。
                // R377 懒门：背景补刷走网络臂关（allowNetworkArm=false）——纯本地
                // 聚合上屏，搜索族网络臂只挂授权开门/恒许显式刷新两路径
                if (built != null && !_discoveryLoading.value) {
                    Log.i("FeedViewModel", "R183 金矿首建完成，自动补刷一轮发现页候选（网络臂关）")
                    refreshDiscoveryImpl(allowNetworkArm = false)
                }
            } finally {
                discoveryGoldBuilding.set(false)
            }
        }
    }
    return discoveryGoldSnapshot // 本轮 null（空态文案承接，~秒-分钟级后自动补刷）
}

/** 过期快照的后台单飞重建（不阻塞调用方；结果发布到 [discoveryGoldSnapshot] 供下轮使用） */
internal fun FeedViewModel.maybeRebuildGoldSnapshotAsync() {
    if (discoveryGoldBuilding.get()) return
    if (!discoveryGoldBuilding.compareAndSet(false, true)) return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            buildGoldSnapshotNow()
        } finally {
            discoveryGoldBuilding.set(false)
        }
    }
}

/**
 * R183 预热任务体（WarmupCoordinator 错峰窗内调用，用户设计原则「扫描不暴露」）：
 * 快照新鲜 → 直接发布回 true；陈旧/缺失 → 重建（成功回 true，失败回 false 由协调器
 * 静默重试）。索引未建 = 无事可做（按成功口径收场，不空转重试）。撞上发现页侧
 * 正在建（单飞闸被占）→ 回 false 让协调器稍后重试（不抢闸）。
 */
internal fun FeedViewModel.warmGoldSnapshot(): Boolean {
    if (!deepIndex.hasIndex()) return true
    val file = discoveryGoldFile()
    val snap = runCatching { DiscoveryGoldmine.loadSnapshot(file) }.getOrNull()
    if (snap != null && !snap.isStale()) {
        discoveryGoldSnapshot = snap
        return true
    }
    if (!discoveryGoldBuilding.compareAndSet(false, true)) return false // 发现页侧在建：让位重试
    try {
        return buildGoldSnapshotNow() != null
    } finally {
        discoveryGoldBuilding.set(false)
    }
}

/** 构建并落盘金矿快照（IO 协程内调用；调用方须持 [discoveryGoldBuilding] 闸） */
internal fun FeedViewModel.buildGoldSnapshotNow(): DiscoveryGoldmine.Snapshot? {
    if (!deepIndex.hasIndex()) {
        Log.d("FeedViewModel", "R183 金矿快照：索引未建（空快照）")
        return null
    }
    val startedAt = System.currentTimeMillis()
    // deadChannelStats 的 per-chatId 聚合体折成 rate（R163 B5 口径：deadmedia/曝光）
    val dead = runCatching { interactionStats.deadChannelStats() }.getOrDefault(emptyMap())
        .mapValues { it.value.rate }
    val snap = runCatching { DiscoveryGoldmine.buildSnapshot(deepIndex, dead) }
        .onFailure { Log.w("FeedViewModel", "R183 金矿快照构建失败: ${it.message}") }
        .getOrNull() ?: return null
    runCatching { DiscoveryGoldmine.saveSnapshot(discoveryGoldFile(), snap) }
    discoveryGoldSnapshot = snap
    Log.i(
        "FeedViewModel",
        "R183 金矿快照：${snap.channels.size} 频道 耗时 ${System.currentTimeMillis() - startedAt}ms " +
            "（过滤闸拦：广告话术 ${snap.channels.count { it.adRate >= AdPatternFilter.AD_REJECT_RATE }} / " +
            "模板风暴 ${snap.channels.count { it.stormRate >= AdPatternFilter.STORM_REJECT_RATE }} / " +
            "农场 ${snap.channels.count { it.farm }}）",
    )
    return snap
}

/** 宝藏二聚合产物（R211 bundle：信号 + 来源词一次读齐——同一遍答案库扫描两用） */
internal data class GroupSignalBundle(
    val signals: Map<Long, DiscoveryGroupSignal>,
    /** chatId → 群搜来源词（推荐语点名词；答案库关键词按最新命中 + 直荐注册表覆盖） */
    val sourceWords: Map<Long, String>,
)

/**
 * 宝藏二信号聚合（纯本地读，零网络）：答案库全量（**R211 换用带关键词投影
 * [AnswerStore.allKeywordAnswers]**——词与答案一次读齐）+ group_search 收割边 +
 * **R211 直荐注册表**（用户勾选群 USER 车道验证过的频道，verified 标志+词覆盖），
 * 按候选频道聚合出「你的搜索群在传」强度。
 *
 * 群来源口径=收割实体清单（手动 ∪ 用户勾选 ∪ 自动发现的已加入群——本就是用户
 * 自己的群）。答案库/图谱为空（收割未跑）时返回空表，候选池自然只剩宝藏一。
 *
 * R211 非频道过滤闸：[EntityResolver.resolvedNonChannelChatIds]（@人提及/私聊的
 * 解析产物）命中即剔除——防「群友 @人」被回填 chatId 后混进发现候选池冒充频道。
 */
internal fun FeedViewModel.aggregateGroupSignalsEx(real: TdLibGateway?): GroupSignalBundle {
    if (real == null) return GroupSignalBundle(emptyMap(), emptyMap())
    class Agg {
        val groups = HashSet<Long>()
        var endorse = 0
        var lastTs = 0L
        var hintTitle = ""
        var members = 0
        var username = ""
        var verified = false
    }
    val agg = HashMap<Long, Agg>()
    // 来源词账（chatId → (词, 最近命中时刻)）：新命中覆盖旧词
    val wordAgg = HashMap<Long, Pair<String, Long>>()
    // 1) 答案库带词投影（R211：词源换血——群友搜过的词直接来自答案库关键词，
    //    不再依赖用户自己的搜索历史去精确命中）
    val answers = runCatching {
        real.groupHarvester.answerStore.allKeywordAnswers()
    }.getOrDefault(emptyList())
    // 非频道解析结论（只读快照；解析器没跑过=空集，零成本）
    val nonChannelIds = runCatching {
        real.groupHarvester.entityResolver.resolvedNonChannelChatIds()
    }.getOrDefault(emptySet())
    for (ka in answers) {
        val a = ka.answer
        // 用户名链接：解析落图谱后才可归因，这里不直出（旧口径保留）；
        // R211：解析结论是**非频道**的（@人提及）同样不进信号
        if (a.chatId == 0L || a.chatId in nonChannelIds) continue
        val m = agg.getOrPut(a.chatId) { Agg() }
        m.groups += a.fromGroup
        m.endorse += a.hits
        if (a.lastTs > m.lastTs) m.lastTs = a.lastTs
        if (m.hintTitle.isBlank() && a.hintTitle.isNotBlank()) m.hintTitle = a.hintTitle
        if (m.members <= 0 && a.members > 0) m.members = a.members
        // R183 username 线索（backfill 过的行才有——解析过=TDLib 已建会话先验）
        if (m.username.isBlank() && a.username.isNotBlank()) m.username = a.username
        // R211 来源词：新命中的词覆盖（「群友搜『X』找到」的 X 取最近一次）
        val cur = wordAgg[a.chatId]
        if (cur == null || a.lastTs >= cur.second) wordAgg[a.chatId] = ka.keyword to a.lastTs
    }
    // 2) group_search 收割边（图谱侧的同一收割观测——边 from=群、to=资源频道）
    val edgeTop = runCatching {
        real.discoveryGraph.topCandidatesByEdgeType(
            GroupHarvester.EDGE_GROUP_SEARCH,
            DISCOVERY_GROUP_SIGNAL_EDGE_TOP_N,
        )
    }.getOrDefault(emptyList())
    for (gc in edgeTop) {
        if (gc.chatId in nonChannelIds) continue
        val m = agg.getOrPut(gc.chatId) { Agg() }
        for (e in gc.edges) {
            if (e.fromChatId != 0L) m.groups += e.fromChatId
            m.endorse += e.count
            if (e.lastTs > m.lastTs) m.lastTs = e.lastTs
        }
        if (m.members <= 0 && gc.memberCount > 0) m.members = gc.memberCount
        if (m.hintTitle.isBlank() && gc.title.isNotBlank()) m.hintTitle = gc.title
    }
    // 3) R211 直荐注册表（用户勾选群 USER 车道验证过的频道）：verified 标志 +
    //    标题/用户名/成员数线索升级 + 词覆盖（直荐词 = 任务书推荐语点名词）
    val nowMs = System.currentTimeMillis()
    val registry = runCatching {
        real.groupHarvester.groupRecommend.store.entries(nowMs)
    }.getOrDefault(emptyList())
    for (e in registry) {
        if (e.chatId in nonChannelIds) continue
        val m = agg.getOrPut(e.chatId) { Agg() }
        m.groups += e.fromGroup
        m.endorse += 1
        if (e.lastTs > m.lastTs) m.lastTs = e.lastTs
        if (m.hintTitle.isBlank() && e.title.isNotBlank()) m.hintTitle = e.title
        if (m.members <= 0 && e.members > 0) m.members = e.members
        if (m.username.isBlank() && e.username.isNotBlank()) m.username = e.username
        m.verified = true
        if (e.keyword.isNotBlank()) wordAgg[e.chatId] = e.keyword to e.lastTs
    }
    val signals = agg.mapValues { (_, m) ->
        DiscoveryGroupSignal(
            groupCount = m.groups.size,
            endorse = m.endorse,
            lastTs = m.lastTs,
            hintTitle = m.hintTitle,
            members = m.members,
            username = m.username,
            verified = m.verified,
        )
    }
    return GroupSignalBundle(signals, wordAgg.mapValues { it.value.first })
}

/** 宝藏二信号聚合（旧签名薄壳：[aggregateGroupSignalsEx] 的 signals 投影） */
internal fun FeedViewModel.aggregateGroupSignals(real: TdLibGateway?): Map<Long, DiscoveryGroupSignal> =
    aggregateGroupSignalsEx(real).signals

/** 宝藏二信号聚合：group_search 边专项预筛条数（与旧 DISCOVERY_GROUP_SEARCH_TOP_N 同量级） */
internal const val DISCOVERY_GROUP_SIGNAL_EDGE_TOP_N = 40
