package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

// ---------------- R133 频道基因鉴定 + 反向探索卡（ideas.md #19 / #17） ----------------
// 体检/画像 Part 家族的新成员，两条小功能共用本文件：
//  1. 频道基因（#19）：从本地深度索引（deepindex/search.db 只读连接）对单频道做一次
//     SQL 聚合，产出 ChannelGenome（条数/首尾时间/三类媒体构成/平均时长），
//     纯函数拼成一行鉴定文案（「日更 3 条 · 主打视频 · 平均 8 分钟 · 几乎无图文」），
//     挂在频道主页简介区；候选卡的等价物用预览页样本现算（见 buildSampleGenomeLine），
//     两路同一套格式化口径，只是数据厚度不同（索引全量 vs 最近一页样本）；
//  2. 反向探索卡（#17）：每周一张"信息茧房打破器"——口味向量负匹配：取长期轨权重
//     top 频道的内容构成加权出口味向量，在"有内容（索引分层有货）+ 曝光账有正面记录
//     （入池曝光账进页条数 > 0 或广场推荐 > 0）+ 与画像距离最远"的频道里选一张，
//     落盘上次展示时间满一周才换（每周换一张）。
// 全部只读本地数据（索引库只读连接 + watch_events 画像 + 入池曝光账 + 广场快照内存态），
// 零网络、零写索引；纪律与 loadIndexChannelStats 相同：库文件不存在直接空手而归，
// 绝不为统计去建库/开写库。

/**
 * 频道基因（#19）：一个频道内容形态的统计画像。
 * 字段口径：[count] 索引条数；[minDateSec]/[maxDateSec] 首尾条目发布时间（跨度 → 更新节奏）；
 * [videoCount]/[photoCount]/[audioCount] 三类媒体条数（→ 内容构成占比）；
 * [timedCount]/[timedSumSec] 有时长条目（视频/音频）的数量与总秒数（→ 平均时长档）。
 */
internal data class ChannelGenome(
    val chatId: Long,
    /** 频道名（items 表 channelTitle 聚合投影；标题兜底源，UI 优先用列表真名） */
    val title: String,
    val count: Int,
    val minDateSec: Long,
    val maxDateSec: Long,
    val videoCount: Int,
    val photoCount: Int,
    val audioCount: Int,
    val timedCount: Int,
    val timedSumSec: Long,
)

/** 反向探索卡（#17）发布模型：与画像负相关的频道 + 广场推荐人数 + 基因鉴定行。
 *  可见性 public：FeedViewModel 的公开状态流要暴露它（与 FeedModels 的
 *  DiscoveryFreshCard 等公开投影模型同一纪律），UI 包直接取用 */
data class ReverseExploreCard(
    val brief: ChannelBrief,
    /** 广场推荐人数（快照缺席/无人推荐 = 0，文案自动回落无数字版） */
    val recommendCount: Int,
    /** 基因鉴定行（选卡时已过条数门槛，保证非空——索引里看得清它是"哪类"） */
    val genomeLine: String,
)

// ---------------- 基因文案格式化（纯函数，索引口径与样本口径共用） ----------------

/**
 * 拼一行基因鉴定文案（索引口径）：「日更 3 条 · 主打视频 · 平均 8 分钟 · 几乎无图文」。
 * 四段语义（R133 口径）：更新节奏+频率档（日更/周更/月更/偶有更新，带条数）→
 * 内容类型构成（主打短视频/长视频/图文/音频，无主导类型时取前两名"混更"）→
 * 平均时长档（秒/分钟/小时自适应）→ 构成注脚（视频压倒性主导时标注"几乎无图文"，
 * 图文主导时标注"几乎无视频"——恰是反向探索最需要的"你从来不看的那类"提示）。
 * 样本不足（[count] < [minCount]）返回 null：宁可不出行，不出小样本伪结论。
 *
 * 频率估计的地板纪律：时间跨度不足 7 天按 7 天算（与 postsPerWeek 的
 * [SCARCITY_MIN_SPAN_MS] 同思路）——刚收录的频道一天冒 3 条不该被吹成"日更 3 条"。
 */
internal fun buildGenomeLine(
    count: Int,
    spanSec: Long,
    video: Int,
    photo: Int,
    audio: Int,
    timedCount: Int,
    timedSumSec: Long,
    minCount: Int = GENOME_MIN_ITEMS,
): String? {
    if (count < minCount) return null
    val parts = ArrayList<String>(4)
    // —— 段一：更新节奏 + 频率 ——
    val spanDays = (spanSec.coerceAtLeast(0L) / 86_400.0).coerceAtLeast(GENOME_MIN_SPAN_DAYS)
    val perDay = count / spanDays
    when {
        perDay >= 1.0 -> parts += "日更 ${perDay.toInt()} 条"
        perDay * 7.0 >= 1.0 -> parts += "周更 ${(perDay * 7.0).toInt()} 条"
        perDay * 30.0 >= 1.0 -> parts += "月更 ${(perDay * 30.0).toInt().coerceAtLeast(1)} 条"
        else -> parts += "偶有更新"
    }
    // —— 段二：内容类型构成 ——
    val v = video.toDouble() / count
    val p = photo.toDouble() / count
    val a = audio.toDouble() / count
    val timedAvg = if (timedCount > 0) timedSumSec.toDouble() / timedCount else 0.0
    val typeName = listOf(
        "视频" to v,
        "图文" to p,
        "音频" to a,
    ).sortedByDescending { it.second }
    when {
        v >= 0.6 -> parts += if (timedAvg in 1.0..480.0) "主打短视频" else "主打长视频"
        p >= 0.6 -> parts += "主打图文"
        a >= 0.6 -> parts += "主打音频"
        else -> parts += "${typeName[0].first}${typeName[1].first}混更"
    }
    // —— 段三：平均时长档（视频/音频等有时长条目；纯图文频道无此段） ——
    if (timedCount >= 3) parts += formatGenomeDuration(timedAvg)
    // —— 段四：构成注脚（只在压倒性偏科时出现，普通频道不出） ——
    if (v >= 0.5 && p <= 0.08) parts += "几乎无图文"
    else if (p >= 0.6 && v <= 0.02) parts += "几乎无视频"
    return parts.joinToString(" · ")
}

/** 时长档格式化（秒 → 分钟 → 小时自适应；小时一位小数、十进制手拼防 Locale 小数点差异） */
private fun formatGenomeDuration(avgSec: Double): String = when {
    avgSec < 60.0 -> "平均 ${avgSec.toInt().coerceAtLeast(1)} 秒"
    avgSec < 3_600.0 -> "平均 ${(avgSec / 60.0).toInt().coerceAtLeast(1)} 分钟"
    else -> {
        val h = avgSec / 3_600.0
        if (h >= 10.0) "平均 ${h.toInt()} 小时" else "平均 ${(h * 10).toInt() / 10}.${(h * 10).toInt() % 10} 小时"
    }
}

/**
 * 候选卡样本基因行（预览页现算）：候选频道不在深度索引里（发现页只推未加入频道，
 * 索引只爬已加入频道——架构上不可能有它的索引统计），但预览拉取本来就带回了最近
 * 一页条目（类型/时长零额外成本），顺手聚一行"近期"口径的等价物：
 * 「近期主打视频 · 平均 8 分钟」。样本只有一页（约 4 条），节奏段不可信故不出，
 * 只出类型构成 + 平均时长，并用"近期"前缀明示口径与索引版不同（诚实标注）。
 * 样本不足 3 条或两类都无主导/无时长 → null（不出伪结论行）。
 */
internal fun buildSampleGenomeLine(items: List<VideoItem>): String? {
    if (items.size < GENOME_SAMPLE_MIN_ITEMS) return null
    var video = 0
    var photo = 0
    var audio = 0
    var timedCount = 0
    var timedSum = 0L
    for (it in items) {
        when (it.mediaType) {
            0 -> video++
            1 -> photo++
            else -> audio++
        }
        if (it.durationSec > 0) {
            timedCount++
            timedSum += it.durationSec
        }
    }
    val n = items.size
    val parts = ArrayList<String>(2)
    when {
        video.toDouble() / n >= 0.6 -> {
            val avg = if (timedCount > 0) timedSum.toDouble() / timedCount else 0.0
            parts += if (avg in 1.0..480.0) "主打短视频" else "主打视频"
        }
        photo.toDouble() / n >= 0.6 -> parts += "主打图文"
        audio.toDouble() / n >= 0.6 -> parts += "主打音频"
        else -> {}
    }
    if (timedCount >= 2) parts += formatGenomeDuration(timedSum.toDouble() / timedCount)
    if (parts.isEmpty()) return null
    return "近期" + parts.joinToString(" · ")
}

/** [ChannelGenome] → 一行鉴定文案（频道主页/反向探索卡共用出口） */
internal fun formatChannelGenome(g: ChannelGenome): String? = buildGenomeLine(
    count = g.count,
    spanSec = (g.maxDateSec - g.minDateSec).coerceAtLeast(0L),
    video = g.videoCount,
    photo = g.photoCount,
    audio = g.audioCount,
    timedCount = g.timedCount,
    timedSumSec = g.timedSumSec,
)

// ---------------- 索引只读聚合（单频道，毫秒级；纪律同 loadIndexChannelStats） ----------------

/**
 * 深度索引单频道基因聚合（**IO 线程调用**）：
 * WHERE chatId=? 走 idx_items_chat_msg 索引前缀，只扫该频道的行；
 * 一条 SQL 出全部字段（条数/首尾时间/两类媒体计数/时长计数与总和/标题投影），
 * 音频数 = 总数 − 视频 − 图文（兜住历史脏类型值，与 UI 类型口径 else→音频 一致）。
 * 库不存在/无条目返回 null——绝不为统计建库开写库。
 */
internal fun FeedViewModel.loadIndexGenome(chatId: Long): ChannelGenome? {
    // 库文件名与 DeepSearchIndex.DB_NAME("search.db") 同源（其 companion 私有引不到，字面量镜像）
    val dbFile = File(File(app.filesDir, "deepindex"), "search.db")
    if (!dbFile.isFile) return null
    val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    try {
        // R266 v11 双臂（D6 含转发口径）：items 臂 + refs 臂（mediaType/durationSec/
        // dateEpochSec/channelTitle 全在 refs 冗余列——免 JOIN）；升级窗口期 v10 库
        // 无 refs 表（独立容错直通存量形态）
        var count = 0
        var minDate = Long.MAX_VALUE
        var maxDate = Long.MIN_VALUE
        var video = 0
        var photo = 0
        var timed = 0
        var durSum = 0L
        var title = ""
        d.rawQuery(
            "SELECT COUNT(*), MIN(dateEpochSec), MAX(dateEpochSec), " +
                "SUM(CASE WHEN mediaType=0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN mediaType=1 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN durationSec>0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN durationSec>0 THEN durationSec ELSE 0 END), " +
                "MAX(channelTitle) " +
                "FROM items WHERE chatId=?",
            arrayOf(chatId.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                count += c.getInt(0)
                if (!c.isNull(1)) minDate = minOf(minDate, c.getLong(1))
                if (!c.isNull(2)) maxDate = maxOf(maxDate, c.getLong(2))
                video += c.getInt(3)
                photo += c.getInt(4)
                timed += c.getInt(5)
                durSum += c.getLong(6)
                title = c.getString(7).orEmpty()
            }
        }
        runCatching {
            d.rawQuery(
                "SELECT COUNT(*), MIN(dateEpochSec), MAX(dateEpochSec), " +
                    "SUM(CASE WHEN mediaType=0 THEN 1 ELSE 0 END), " +
                    "SUM(CASE WHEN mediaType=1 THEN 1 ELSE 0 END), " +
                    "SUM(CASE WHEN durationSec>0 THEN 1 ELSE 0 END), " +
                    "SUM(CASE WHEN durationSec>0 THEN durationSec ELSE 0 END), " +
                    "MAX(channelTitle) " +
                    "FROM repost_refs WHERE chatId=?",
                arrayOf(chatId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    count += c.getInt(0)
                    if (!c.isNull(1)) minDate = minOf(minDate, c.getLong(1))
                    if (!c.isNull(2)) maxDate = maxOf(maxDate, c.getLong(2))
                    video += c.getInt(3)
                    photo += c.getInt(4)
                    timed += c.getInt(5)
                    durSum += c.getLong(6)
                    if (title.isEmpty()) title = c.getString(7).orEmpty()
                }
            }
        }
        if (count <= 0) return null
        return ChannelGenome(
            chatId = chatId,
            title = title,
            count = count,
            minDateSec = if (minDate == Long.MAX_VALUE) 0L else minDate,
            maxDateSec = if (maxDate == Long.MIN_VALUE) 0L else maxDate,
            videoCount = video,
            photoCount = photo,
            audioCount = (count - video - photo).coerceAtLeast(0),
            timedCount = timed,
            timedSumSec = durSum,
        )
    } finally {
        runCatching { d.close() }
    }
}

/**
 * 频道主页基因鉴定行查询（UI 出口，内部切 IO）：索引聚合 + 文案格式化一步到位；
 * 未索引/条数不足返回 null（UI 不渲染该行——没有证据不臆测）。
 * 查询异常记一行日志（低频入口，不刷屏）：静默吞异常曾把列下标错误藏成"永远不出行"。
 */
internal suspend fun FeedViewModel.channelGenomeLineImpl(chatId: Long): String? =
    withContext(Dispatchers.IO) {
        runCatching { loadIndexGenome(chatId) }
            .onFailure { android.util.Log.w("FeedViewModel", "基因鉴定聚合失败 chatId=$chatId: ${it.message}") }
            .getOrNull()?.let { formatChannelGenome(it) }
    }

// ---------------- 反向探索卡（#17：每周一张"信息茧房打破器"） ----------------

/** 周状态落盘模型：{chatId, shownAtMs}（store/reverse_explore.json，tmp+rename 原子写） */
private data class ReverseExploreState(val chatId: Long, val shownAtMs: Long)

/**
 * 反向探索卡刷新入口（DiscoverScreen 进页 LaunchedEffect 触发，IO 协程）：
 *  - 已开启广场且快照未到时最多等 [REVERSE_PLAZA_WAIT_MS]（FeedScreen 启动即轮询，
 *    进发现页多半已在途；等一下能让文案带上真实广场推荐数，未开启/Mock 直通不等待）；
 *  - 周内（距上次选卡 < 7 天）沿用本张卡：标题/基因/推荐数按现值重算重发布
 *    （广场快照晚到时文案即时补正），卡身份不变——"每周一张"的仪式感；
 *  - 满一周（或本张卡失效：被屏蔽/索引清了）才重选，重选时排除上一张
 *    （池子里还有别人时），保证"换一张"是真的换了。
 */
internal fun FeedViewModel.refreshReverseExploreCardImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        if (plazaPrefsEnabled() && plazaService != null && _plaza.value == null) {
            runCatching {
                withTimeoutOrNull(REVERSE_PLAZA_WAIT_MS) { _plaza.first { it != null } }
            }
        }
        runCatching { computeReverseExploreCard() }
            .onFailure {
                android.util.Log.w("FeedViewModel", "反向探索卡计算失败（静默不出卡）: ${it.message}")
                _discoveryReverseCard.value = null
            }
    }
}

/** 选卡/续卡主流程（IO 线程内调用；任何一步失败由调用方兜底清卡） */
private fun FeedViewModel.computeReverseExploreCard() {
    val now = System.currentTimeMillis()
    val prev = loadReverseExploreState()
    if (prev != null && now - prev.shownAtMs < REVERSE_WEEK_MS) {
        // 周内续卡：本张卡仍需"没被屏蔽 + 索引里还有它"，失效才提前换
        val stillOk = prev.chatId !in _blockedChannels.value && loadIndexGenome(prev.chatId) != null
        if (stillOk) {
            publishReverseCard(prev.chatId, now, freshPick = false)
            return
        }
    }
    pickReverseCard(now, excludeId = prev?.chatId)
}

/**
 * 选一张新卡（IO 线程内调用）：口味向量负匹配三步走——
 *  1. 口味向量：长期轨权重（decayedChannelWeights，watch_events 7 天衰减 + 互动加分，
 *     与混排/配额同一份画像）top [REVERSE_TASTE_TOP] 个已索引频道的内容构成
 *     （视频/图文/音频占比，来自索引内容分层 indexedMediaTier）按权重加权平均，归一；
 *     已索引的 top 不足 2 个 → 无口味向量可言，静默不出卡；
 *  2. 候选池：索引里有内容（分层总条数 ≥ [REVERSE_MIN_ITEMS]）∧ 曝光账有正面记录
 *     （入池曝光账进页条数 > 0 或广场推荐 > 0——频道真实产出过内容）∧ 你从来不看
 *     （watch_events 里一次都没出现过）∧ 未屏蔽/非广告黑名单；
 *  3. 负匹配：余弦距离（1 − cos）最大者胜出（距离 < [REVERSE_MIN_DISTANCE] 的不要
 *     ——不够"反向"就别打"开眼"旗号）；同距看广场推荐数、进页条数、内容量。
 * 胜出者再过两道体检：基因聚合成功（条数门槛）+ 最新一条距今天数 ≤ [REVERSE_MAX_FRESH_DAYS]
 * （沉寂频道不开眼）；不过关顺位递补。池子空 → 清卡不出（宁缺毋滥）。
 */
private fun FeedViewModel.pickReverseCard(now: Long, excludeId: Long?) {
    // —— 索引内容分层（视频/图文/音频三张 chatId→条数 表 + 已爬频道集；5 分钟内存缓存） ——
    val videoTier = indexedMediaTier(0)?.first ?: run { _discoveryReverseCard.value = null; return }
    val photoTier = indexedMediaTier(1)?.first ?: videoTier // 同一份缓存，判空一次即可
    val audioTier = indexedMediaTier(2)?.first ?: videoTier

    // —— 口味向量：长期轨 top 频道构成加权 ——
    val events = runCatching { store.loadWatchEvents() }.getOrDefault(emptyList())
    val weights = decayedChannelWeights(events, now, effectiveInteractionBoost(now))
    val watchedIds = events.mapTo(HashSet()) { it.chatId }
    val taste = DoubleArray(3)
    var tasteKnown = 0
    var tasteWeightSum = 0.0
    for ((id, w) in weights.entries.sortedByDescending { it.value }) {
        if (tasteKnown >= REVERSE_TASTE_TOP) break
        val comp = compositionOf(videoTier[id] ?: 0, photoTier[id] ?: 0, audioTier[id] ?: 0) ?: continue
        taste[0] += w * comp[0]
        taste[1] += w * comp[1]
        taste[2] += w * comp[2]
        tasteWeightSum += w
        tasteKnown++
    }
    if (tasteKnown < 2 || tasteWeightSum <= 0.0) {
        // 画像太薄或 top 频道都不在索引里：算不出"你爱看什么"，负匹配无从谈起
        android.util.Log.d("FeedViewModel", "反向探索卡：口味向量证据不足（top 已索引 $tasteKnown 个），本轮不出卡")
        _discoveryReverseCard.value = null
        return
    }
    for (i in taste.indices) taste[i] /= tasteWeightSum

    // —— 候选池 + 负匹配打分 ——
    val exposure = runCatching { channelExposureLedger.snapshot() }.getOrDefault(emptyMap())
    val plazaRec = _plaza.value?.channelRecommends ?: emptyMap()
    val blocked = _blockedChannels.value
    val usernames = HashMap<Long, String>()
    _channels.value.forEach { if (!it.username.isNullOrBlank()) usernames[it.id] = it.username!! }

    data class Cand(val id: Long, val dist: Double, val rec: Int, val pooled: Int, val total: Int)

    val allIds = (videoTier.keys + photoTier.keys + audioTier.keys)
    val cands = ArrayList<Cand>(allIds.size)
    for (id in allIds) {
        val v = videoTier[id] ?: 0
        val p = photoTier[id] ?: 0
        val a = audioTier[id] ?: 0
        val total = v + p + a
        if (total < REVERSE_MIN_ITEMS) continue // 索引里内容太薄，基因不可信
        if (id in blocked || id in watchedIds) continue // 屏蔽 / 你看过（"从来不看"的字面口径）
        val pooled = exposure[id]?.pooledItems ?: 0
        val rec = plazaRec[id] ?: 0
        if (pooled <= 0 && rec <= 0) continue // 曝光账无正面记录：没被系统真实服务过的不开眼
        if (AdChannelClassifier.isBlockedRuntime(id, usernames[id])) continue
        val comp = compositionOf(v, p, a) ?: continue
        val dist = cosineDistance(comp, taste)
        if (dist < REVERSE_MIN_DISTANCE) continue // 不够"反向"的不配打"开眼"旗号
        cands += Cand(id, dist, rec, pooled, total)
    }
    if (cands.isEmpty()) {
        android.util.Log.d("FeedViewModel", "反向探索卡：候选池空（分层频道 ${allIds.size} 个无一过关），本轮不出卡")
        _discoveryReverseCard.value = null
        return
    }
    cands.sortWith(
        compareByDescending<Cand> { it.dist }
            .thenByDescending { it.rec }
            .thenByDescending { it.pooled }
            .thenByDescending { it.total },
    )
    val nowSec = now / 1000L
    // 每周换一张：上一张剔除（池子只剩它自己时保留——总比空着强）
    val ordered = if (excludeId != null && cands.size > 1) cands.filterNot { it.id == excludeId } else cands
    for (cand in ordered.ifEmpty { cands }) {
        val g = loadIndexGenome(cand.id) ?: continue // 理论上不会（分层已保证有货），防御
        if (g.maxDateSec <= 0L || nowSec - g.maxDateSec > REVERSE_MAX_FRESH_DAYS * 86_400L) continue
        val line = formatChannelGenome(g) ?: continue
        // 落盘与发布在 publishReverseCard(freshPick=true) 里一并完成
        publishReverseCard(cand.id, now, freshPick = true, genome = g, genomeLine = line, rec = cand.rec)
        android.util.Log.i(
            "FeedViewModel",
            "反向探索卡：频道尾号${cand.id.toString().takeLast(6)} 距离=${"%.2f".format(cand.dist)} " +
                "广场推荐=${cand.rec} 进页条数=${cand.pooled} 基因「$line」",
        )
        return
    }
    _discoveryReverseCard.value = null
}

/**
 * 发布（或周内重发布）一张卡（IO 线程内调用）：标题优先列表真名 → 订阅名 →
 * 基因聚合的 channelTitle 投影 → 兜底"频道 <id>"；基因行与广场推荐数按现值重算。
 */
private fun FeedViewModel.publishReverseCard(
    chatId: Long,
    now: Long,
    freshPick: Boolean,
    genome: ChannelGenome? = null,
    genomeLine: String? = null,
    rec: Int? = null,
) {
    val g = genome ?: loadIndexGenome(chatId) ?: run {
        _discoveryReverseCard.value = null
        return
    }
    val line = genomeLine ?: formatChannelGenome(g)
    if (line == null) {
        _discoveryReverseCard.value = null
        return
    }
    val title = _channels.value.firstOrNull { it.id == chatId }?.takeIf { it.title.isNotBlank() }?.title
        ?: runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
            .firstOrNull { it.chatId == chatId }?.title
        ?: g.title.takeIf { it.isNotBlank() }
        ?: "频道 $chatId"
    val brief = _channels.value.firstOrNull { it.id == chatId } ?: ChannelBrief(chatId, title, null)
    _discoveryReverseCard.value = ReverseExploreCard(
        brief = if (brief.title.isNotBlank()) brief else brief.copy(title = title),
        recommendCount = rec ?: (_plaza.value?.channelRecommends?.get(chatId) ?: 0),
        genomeLine = line,
    )
    if (freshPick) saveReverseExploreState(chatId, now)
}

/** 三类条数 → L1 归一构成向量；总数 0 返回 null（无内容不参与向量运算） */
private fun compositionOf(video: Int, photo: Int, audio: Int): DoubleArray? {
    val total = (video + photo + audio).toDouble()
    if (total <= 0.0) return null
    return doubleArrayOf(video / total, photo / total, audio / total)
}

/** 余弦距离（1 − cosine）：两向量同向 = 0，正交（视频党 vs 纯图文）= 1 */
private fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    if (na <= 0.0 || nb <= 0.0) return 1.0
    return 1.0 - dot / Math.sqrt(na * nb)
}

// ---------------- 周状态落盘（store/reverse_explore.json，tmp+rename 原子写） ----------------

/** 读上次展示状态；文件缺失/坏 JSON 返回 null（首跑=本周还没出过卡） */
private fun FeedViewModel.loadReverseExploreState(): ReverseExploreState? {
    val f = File(File(app.filesDir, "store"), REVERSE_STATE_FILE)
    if (!f.isFile) return null
    return runCatching {
        val obj = JSONObject(f.readText())
        val id = obj.optLong("chatId", 0L)
        val ts = obj.optLong("shownAtMs", 0L)
        if (id != 0L && ts > 0L) ReverseExploreState(id, ts) else null
    }.getOrNull()
}

/** 落盘上次展示时间（选卡时写一次；周内续卡不重写，周起点稳定） */
private fun FeedViewModel.saveReverseExploreState(chatId: Long, shownAtMs: Long) {
    runCatching {
        val dir = File(app.filesDir, "store")
        dir.mkdirs()
        val f = File(dir, REVERSE_STATE_FILE)
        val tmp = File(dir, "$REVERSE_STATE_FILE.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("chatId", chatId).put("shownAtMs", shownAtMs).toString())
        if (!tmp.renameTo(f)) { // 个别文件系统 rename 不覆盖：先删再改名兜底
            runCatching { f.delete() }
            tmp.renameTo(f)
        }
    }
}

// ---------------- R133 常量（本文件私有） ----------------

/** 索引口径基因鉴定的最小条数门槛：小样本不出伪结论（一行字的信用比覆盖面重要） */
private const val GENOME_MIN_ITEMS = 10

/** 预览样本基因行的最小条数门槛（样本本来就只有一页，门槛只防 1~2 条的噪声） */
private const val GENOME_SAMPLE_MIN_ITEMS = 3

/** 频率估计的时间跨度地板（天）：与 postsPerWeek 的 7 天地板同思路，防新收录频道吹成日更 */
private const val GENOME_MIN_SPAN_DAYS = 7.0

/** 反向探索卡换卡周期：满一周才换下一张（"每周一张"的仪式感） */
private const val REVERSE_WEEK_MS = 7L * 24 * 3600 * 1000

/** 反向探索卡选池的内容量门槛（索引分层总条数）：太薄的频道基因不可信 */
private const val REVERSE_MIN_ITEMS = 120

/** 口味向量取画像 top 频道个数（长期轨权重降序；太少不稳，太多稀释口味） */
private const val REVERSE_TASTE_TOP = 8

/** 负相关距离门槛（1 − cosine）：不够"反向"的不打"开眼"旗号 */
private const val REVERSE_MIN_DISTANCE = 0.35

/** 胜出频道最新一条距今上限（天）：沉寂频道不开眼 */
private const val REVERSE_MAX_FRESH_DAYS = 180L

/** 广场快照晚到宽限：已开启广场且快照未到时最多等这么久（未开启/Mock 直通不等待） */
private const val REVERSE_PLAZA_WAIT_MS = 8_000L

/** 反向探索卡周状态文件名（filesDir/store/ 之下） */
private const val REVERSE_STATE_FILE = "reverse_explore.json"
