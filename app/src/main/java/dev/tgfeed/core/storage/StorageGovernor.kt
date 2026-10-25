package dev.tgfeed.core.storage

import android.os.SystemClock
import android.util.Log
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.ColdStartQuietGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ---------------- R196 存储定期清理链路（StorageGovernor） ----------------
//
// 来龙去脉（用户实锤 2026-10-17）：app 数据 12.2GB，其中 files/tdlib/files/temp/ 3GB
// 在途下载碎片没回收——旧链路只有「小时级轮询 + mtime 2h 陈旧裸删」，无压力触发、
// 无活跃排除、无用户可见性。本件是全仓唯一的存储治理调度中枢（收敛纪律：清理
// 时机/分级/登记/日志只在这里写一份），把旧 TdLibGateway.storageGovernJob 小时循环
// 收编进来。四个消费面：
//  - 网关：构造后 start()，close() 时 cancel（生命周期同网关会话）；
//  - 设置页「存储管理」卡：storageOverview() 概览 + runManualCleanup() 立即清理；
//  - R195 邻居预热：registerFullLoad/touchFullLoad 登记（保留上限淘汰统一在本件执行）；
//  - 压力触发：探针循环自测（temp>阈值 或 TDLib 缓存>阈值 → 提前整轮）。
//
// 守卫红线（永不触碰）：登录态/订阅/设置（shared_prefs + files/prefs）、store/、
// files/deepindex/（R163 分档治理辖区）、tdlib/db.sqlite 三件套、td.binlog。
// 本件只动：tdlib/files/temp 内的碎片文件 + TDLib DeleteFile/OptimizeStorage 两个
// 官方本地接口。消息库超限重建是启动前闸门的事（resetMessageDatabaseIfOverBudget，
// 开库前跑，本件不掺和——运行期 TDLib 持有库文件）。

/** 存储概览分项：设置页「存储管理」卡数据源（全部盘测，TDLib 未起/演示模式也能量） */
data class StorageOverview(
    /** filesDir 全量（含所有分项与业务 json） */
    val totalBytes: Long,
    /** 深度索引库（deepindex/，R163 辖区，本件只量不动） */
    val deepIndexBytes: Long,
    /** TDLib 完成态文件缓存（tdlib/files/，不含 temp；水位闸的治理对象） */
    val tdlibCacheBytes: Long,
    /** 下载暂存碎片（tdlib/files/temp + tdlib/temp；本件 T0 的治理对象） */
    val tempBytes: Long,
    /** TDLib 消息库账面（db.sqlite 三件套；启动前闸门的治理对象，运行期只量不动） */
    val messageDbBytes: Long,
)

/**
 * R195 全载登记表条目：邻居预热发起整载时登记；「滑到该条=无感接管」时 touch
 * 续命；保留上限淘汰按 lastUseMs 新→旧取前 N，其余 DeleteFile 淘汰。
 */
internal data class FullLoadEntry(
    val fileId: Int,
    val key: String?,
    val lastUseMs: Long,
)

/** 一轮整轮清理的释放量记账（清理日志行 + 释放量 trace 数据源） */
internal data class CleanRoundResult(
    val tempFreedBytes: Long,
    val tempRemoved: Int,
    val fullLoadEvicted: Int,
    val fullLoadFreedBytes: Long,
    val watermarkFreedBytes: Long,
) {
    val totalFreedBytes: Long get() = tempFreedBytes + fullLoadFreedBytes + watermarkFreedBytes
}

/** temp 碎片处置判定（纯函数，单测 StorageGovernorPolicyTest） */
internal enum class TempAction { KEEP_ACTIVE, KEEP_FRESH, KEEP_COMPLETED, DELETE }

/** 存储分桶（概览 reduce 的键；纯函数 bucketOf 分派） */
internal enum class StorageBucket { DEEP_INDEX, TD_FILES, TD_TEMP, TD_DB, OTHER }

/**
 * temp 碎片处置判定（纯函数）：
 *  - 文件名是纯数字 = TDLib fileId：在活跃集（泵供流/整下兜底/预热在飞）→ KEEP_ACTIVE；
 *  - 新鲜度窗口内（mtime 距今 < staleMs，回看盘命中价值期）→ KEEP_FRESH；
 *  - TDLib 侧已完成（暂存改名中途的边缘态）→ KEEP_COMPLETED（完成态归水位闸管，
 *    temp 清扫不越权删完成文件）；
 *  - 其余 → DELETE（死残留）。
 * 非数字文件名（TDLib 其他杂散暂存）只按新鲜度判定，无从查活跃/完成态。
 */
internal fun tempFragmentAction(
    name: String,
    ageMs: Long,
    activeFileIds: Set<Int>,
    staleMs: Long,
    tdDownloadingActive: Boolean = false,
    tdCompleted: Boolean = false,
): TempAction {
    val fid = name.toIntOrNull()
    if (fid != null && fid in activeFileIds) return TempAction.KEEP_ACTIVE
    if (ageMs < staleMs) return TempAction.KEEP_FRESH
    if (fid != null && tdDownloadingActive) return TempAction.KEEP_ACTIVE
    if (fid != null && tdCompleted) return TempAction.KEEP_COMPLETED
    return TempAction.DELETE
}

/**
 * 全载保留上限淘汰候选（纯函数）：登记表按 lastUseMs 新→旧取前 [keepCount]，
 * 其余为候选；活跃集内的条目让路（本轮不淘汰，等下轮）。keepCount<=0 返回空
 * （0 = 关闭全载淘汰语义，见 AppPrefs.storageFullLoadRetention 注释）。
 */
internal fun fullLoadEvictionCandidates(
    entries: List<FullLoadEntry>,
    keepCount: Int,
    activeFileIds: Set<Int>,
): List<FullLoadEntry> {
    if (keepCount <= 0 || entries.isEmpty()) return emptyList()
    return entries.sortedByDescending { it.lastUseMs }
        .drop(keepCount)
        .filterNot { it.fileId in activeFileIds }
}

/** 相对路径 → 存储分桶（纯函数；relPath 用 / 分隔、不以 / 开头） */
internal fun storageBucketOf(relPath: String): StorageBucket = when {
    relPath == "deepindex" || relPath.startsWith("deepindex/") -> StorageBucket.DEEP_INDEX
    relPath == "tdlib/temp" || relPath.startsWith("tdlib/temp/") -> StorageBucket.TD_TEMP
    relPath == "tdlib/files/temp" || relPath.startsWith("tdlib/files/temp/") -> StorageBucket.TD_TEMP
    relPath.startsWith("tdlib/db.sqlite") -> StorageBucket.TD_DB
    relPath == "tdlib/files" || relPath.startsWith("tdlib/files/") -> StorageBucket.TD_FILES
    else -> StorageBucket.OTHER
}

/** 治理日志统一 MB 一位小数（全仓存储口径唯一实现；网关侧扩展函数委托到这里） */
internal fun fmtMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f", bytes / 1024.0 / 1024.0)

/** 概览卡 GB 一位小数（与 fmtMb 同源换算，不另写精度） */
internal fun fmtGb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)

/**
 * filesDir 一次 walk 分桶量出存储概览（纯盘测，TDLib 未起/演示模式也能量；
 * 深索引/TDLib 缓存/temp/消息库分项 + 总占用）。纯读（stat 调用），与爬虫/播放
 * 并发安全。governor.storageOverview 与 VM 演示模式路径共用本实现（单源）。
 */
internal fun measureStorageOverview(filesDir: File): StorageOverview = runCatching {
    val buckets = mutableMapOf<StorageBucket, Long>()
    val rootPath = filesDir.absolutePath
    filesDir.walkTopDown().forEach { f ->
        if (!f.isFile) return@forEach
        val rel = f.absolutePath.removePrefix(rootPath).removePrefix(File.separator)
            .replace('\\', '/')
        val bucket = storageBucketOf(rel)
        buckets[bucket] = (buckets[bucket] ?: 0L) + f.length()
    }
    StorageOverview(
        totalBytes = buckets.values.sum(),
        deepIndexBytes = buckets[StorageBucket.DEEP_INDEX] ?: 0L,
        tdlibCacheBytes = buckets[StorageBucket.TD_FILES] ?: 0L,
        tempBytes = buckets[StorageBucket.TD_TEMP] ?: 0L,
        messageDbBytes = buckets[StorageBucket.TD_DB] ?: 0L,
    )
}.getOrDefault(
    StorageOverview(0, 0, 0, 0, 0),
)

/**
 * 存储治理中枢。TDLib 触点全部由网关注入为 lambda（本地操作，无需授权态；
 * 网关换发/关闭时本实例随之废弃，登记表会话级——进程重启后全载文件仍在 TDLib
 * 缓存里由水位闸 LRU 兜底，R195 预热循环会重新登记）。
 */
class StorageGovernor(
    private val scope: CoroutineScope,
    private val filesDir: File,
    internal val prefs: AppPrefs,
    /** TDLib GetFile（幂等本地查询；网关侧 runCatching 包装，失败回 null） */
    private val getFile: suspend (Int) -> TdApi.File?,
    /** TDLib DeleteFile（一致删除：本地副本+账目一起清；失败回 false 走裸删兜底） */
    private val deleteFileTd: suspend (Int) -> Boolean,
    /** TDLib 快账（GetStorageStatisticsFast；未就绪/演示模式回 null） */
    private val storageFastStats: suspend () -> TdApi.StorageStatisticsFast?,
    /** T2 水位两道闸（GatewayDownload.enforceStorageWatermark；返回释放字节） */
    private val enforceWatermark: suspend () -> Long,
    /** 消息库体积观测（GatewayDownload.observeMessageDatabase，只量不动） */
    private val observeMessageDb: suspend () -> Unit,
    /** 活跃 fileId 集（泵供流 streamingConns + 整下兜底 fullDlFallbackFiles；网关拼装） */
    private val activeFileIds: () -> Set<Int>,
    /** 泵是否正在供流（重活错峰用；true=有播放连接在跑） */
    private val isStreamingActive: () -> Boolean,
    /** T2 水位闸开关（cacheLimitBytes>0 才开；0=用户关了自动水位，T0/T1 照跑） */
    private val watermarkEnabled: Boolean,
) {
    companion object {
        private const val TAG = "StorageGov"

        /** 探针循环间隔：5min（便宜盘测 + 快账；整轮到点/压力才跑） */
        internal const val PROBE_INTERVAL_MS = 5 * 60_000L

        /** 压力触发冷却：15min（防压力持续超阈值时每 5min 空转整轮） */
        internal const val PRESSURE_COOLDOWN_MS = 15 * 60_000L

        /** 常态 temp 新鲜度窗口：15min（滑走后的回看盘命中价值期） */
        internal const val TEMP_STALE_MS = 15 * 60_000L

        /** 压力轮 temp 新鲜度窗口：2min（temp 已超阈值，只护最后一口气） */
        internal const val TEMP_STALE_PRESSURE_MS = 2 * 60_000L

        /** T2 重活等播放空闲的上限（超过就硬跑——1h 免疫期本就护着在播文件） */
        private const val HEAVY_IDLE_WAIT_MAX_MS = 10 * 60_000L

        /** T2 空闲等待的轮询步长 */
        private const val HEAVY_IDLE_POLL_MS = 30_000L

        /** 整轮间隔抖动幅度（±，错峰：多设备/多轮不与爬虫收割同拍起跑） */
        private const val ROUND_JITTER_MS = 5 * 60_000L
    }

    /**
     * R195 全载登记表：fileId → 条目。三个写口（registerFullLoad/touchFullLoad/
     * unregisterFullLoad）供 R195 预热链调用；淘汰读口 enforceFullLoadRetention。
     */
    private val fullLoads = ConcurrentHashMap<Int, FullLoadEntry>()

    /** 最近一轮整轮时刻（elapsedRealtime；0=本轮会话还没跑过→首轮探针即整轮） */
    @Volatile
    private var lastFullRoundAtMs = 0L

    /** 最近一次压力触发时刻（elapsedRealtime；冷却判定用） */
    @Volatile
    private var lastPressureRoundAtMs = 0L

    private var started = false
    private var loopJob: Job? = null

    // ---------------- 生命周期 ----------------

    /**
     * 启动治理循环（幂等，重复调用回同一个 job）。首轮错峰：R145 冷启动让路闸等
     * 首屏内容落定再动（沿用旧 storageGovernJob 的 QuietGate 语义），此后 5min
     * 探针常驻。返回 job 供网关 close() 取消（生命周期同网关）。
     */
    fun start(): Job = synchronized(this) {
        loopJob?.let { return it }
        started = true
        scope.launch(Dispatchers.IO) {
            runCatching { ColdStartQuietGate.awaitQuiet() }
                .onFailure { Log.w(TAG, "冷启动让路闸等待异常（直接开跑）: ${it.message}") }
            while (true) {
                runCatching { probeAndMaybeClean() }
                    .onFailure { Log.w(TAG, "治理轮次失败（下拍再试）: ${it.message}") }
                delay(PROBE_INTERVAL_MS)
            }
        }.also { loopJob = it }
    }

    /** 治理循环是否已启动（诊断/测试口） */
    fun isStarted(): Boolean = started

    // ---------------- 探针与整轮调度 ----------------

    /**
     * 一拍探针：量 temp 与 TDLib 快账 → 判定（压力触发 / 到点整轮 / 不动）。
     * 便宜（一次 temp 目录遍历 + 一次 Fast 快账），5min 一拍不抢前台。
     */
    private suspend fun probeAndMaybeClean() {
        val tempBytes = measureTempBytes()
        val fast = runCatching { storageFastStats() }.getOrNull()
        val now = SystemClock.elapsedRealtime()
        val tempLimitBytes = prefs.storageTempPressureMb * 1024L * 1024L
        val totalLimitBytes = prefs.storageTotalPressureGb * 1024L * 1024L * 1024L
        val tempPressure = tempLimitBytes > 0 && tempBytes > tempLimitBytes
        val totalPressure = totalLimitBytes > 0 && (fast?.filesSize ?: 0L) > totalLimitBytes
        if (tempPressure || totalPressure) {
            if (now - lastPressureRoundAtMs < PRESSURE_COOLDOWN_MS) {
                Log.d(TAG, "压力持续超阈（temp=${fmtMb(tempBytes)}MB 缓存=${fmtMb(fast?.filesSize ?: 0)}MB），冷却中不重跑")
                return
            }
            lastPressureRoundAtMs = now
            Log.w(
                TAG,
                "存储压力触发：temp ${fmtMb(tempBytes)}MB${if (tempPressure) "（>阈值）" else ""} / " +
                    "TDLib 缓存 ${fmtMb(fast?.filesSize ?: 0)}MB${if (totalPressure) "（>阈值）" else ""}，提前整轮",
            )
            runFullRound(aggressive = true, cause = "压力触发")
            return
        }
        val intervalMs = prefs.storageGovernIntervalMin * 60_000L +
            (Math.random() * (2 * ROUND_JITTER_MS) - ROUND_JITTER_MS).toLong()
        if (now - lastFullRoundAtMs >= intervalMs) {
            runFullRound(aggressive = false, cause = "定期${prefs.storageGovernIntervalMin}min")
        }
    }

    /**
     * 一轮整轮清理（分级：便宜安全在前，重活在后错峰）：
     *  T0 temp 碎片 → T1 全载保留上限 → T2 水位两道闸 + 消息库观测。
     * aggressive=true（压力/手动）：T0 用 2min 激进口径，重活不再等空闲
     * （用户按了「立即清理」=要现在出数；压力时 1h 免疫期仍护在播文件）。
     */
    private suspend fun runFullRound(aggressive: Boolean, cause: String): CleanRoundResult {
        val t0 = SystemClock.elapsedRealtime()
        val temp = sweepTempFragments(aggressive)
        val evict = enforceFullLoadRetention()
        var watermarkFreed = 0L
        if (watermarkEnabled) {
            if (!aggressive) awaitIdleForHeavyTier()
            runCatching { watermarkFreed = enforceWatermark() }
                .onFailure { Log.w(TAG, "水位闸失败（本轮跳过）: ${it.message}") }
        }
        runCatching { observeMessageDb() }
            .onFailure { Log.d(TAG, "消息库观测失败: ${it.message}") }
        val result = CleanRoundResult(temp.first, temp.second, evict.evicted, evict.freedBytes, watermarkFreed)
        lastFullRoundAtMs = SystemClock.elapsedRealtime()
        if (result.totalFreedBytes > 0L) {
            Log.i(
                TAG,
                "整轮清理完成（$cause，${SystemClock.elapsedRealtime() - t0}ms）：暂存回收 ${fmtMb(result.tempFreedBytes)}MB" +
                    "·${temp.second} 文件 / 全载淘汰 ${evict.evicted} 条·${fmtMb(result.fullLoadFreedBytes)}MB / " +
                    "水位释放 ${fmtMb(result.watermarkFreedBytes)}MB，合计 ${fmtMb(result.totalFreedBytes)}MB",
            )
        } else {
            Log.d(TAG, "整轮清理完成（$cause，${SystemClock.elapsedRealtime() - t0}ms）：无可清项")
        }
        // 无可清也落日志（用户能看见「上次清理」确实在跑），释放 0
        persistCleanLog(result)
        return result
    }

    /** 清理日志落盘（设置页「存储管理」卡数据源）：时刻/释放量/分项摘要 */
    private fun persistCleanLog(result: CleanRoundResult) {
        runCatching {
            prefs.storageLastCleanAtMs = System.currentTimeMillis()
            prefs.storageLastCleanFreedBytes = result.totalFreedBytes
            prefs.storageLastCleanSummary = "暂存 ${fmtMb(result.tempFreedBytes)}MB·${result.tempRemoved} 文件" +
                " / 全载淘汰 ${result.fullLoadEvicted} 条 / 水位 ${fmtMb(result.watermarkFreedBytes)}MB"
        }
    }

    /**
     * T2 重活错峰：等泵不再供流（最多 [HEAVY_IDLE_WAIT_MAX_MS]，超时硬跑）。
     * GetStorageStatistics 全量扫描与 OptimizeStorage 都在 TDLib 内部线程上走，
     * 正在播视频时跑会与泵的窗口查询挤资源（R145 同款理由：治理每小时才一轮，
     * 晚几分钟无感，抢播放才是大事）。
     */
    private suspend fun awaitIdleForHeavyTier() {
        val deadline = SystemClock.elapsedRealtime() + HEAVY_IDLE_WAIT_MAX_MS
        while (isStreamingActive() && SystemClock.elapsedRealtime() < deadline) {
            delay(HEAVY_IDLE_POLL_MS)
        }
    }

    // ---------------- T0：temp 碎片清扫 ----------------

    /**
     * temp 碎片清扫（安全清）：`tdlib/files/temp`（真身，用户 3GB 现场）与
     * `tdlib/temp`（本 TDLib 版空置，防御性覆盖）。安全三闸：
     *  1. 活跃 fileId 排除（文件名即 TDLib fileId；泵供流/整下兜底/预热在飞集）；
     *  2. TDLib 侧在线核验（GetFile：isDownloadingActive=在飞让路——WiFi 预载等
     *     不在活跃集的合法在飞；isDownloadingCompleted=完成态不碰，归水位闸管）；
     *  3. 新鲜度窗口（常态 15min/压力 2min——回看盘命中价值期，别秒删滑走文件）。
     * 删除优先走 TDLib DeleteFile（本地副本与 file_db 账目一致删除，杜绝裸删后
     * 账目仍宣称 downloadedPrefixSize 的假水位风险——R81 零洞闸能兜但伤重访体验）；
     * DeleteFile 失败/文件仍在（TDLib 不认账的孤儿）才裸 File.delete。
     * 非数字文件名的杂散暂存只走新鲜度 + 裸删（无从查 TDLib 态）。
     */
    private suspend fun sweepTempFragments(aggressive: Boolean): Pair<Long, Int> {
        val staleMs = if (aggressive) TEMP_STALE_PRESSURE_MS else TEMP_STALE_MS
        val active = activeFileIds()
        val now = System.currentTimeMillis()
        var freed = 0L
        var removed = 0
        var keptActive = 0
        var keptFresh = 0
        for (root in tempRoots()) {
            val entries = root.listFiles() ?: continue
            for (f in entries) {
                if (!f.isFile || f.name.startsWith(".")) continue
                val len = f.length()
                val age = now - f.lastModified()
                val fid = f.name.toIntOrNull()
                // 两段决断：先按活跃集+新鲜度判（便宜，零 TDLib 调用）；判为删且带
                // fileId 才查 TDLib 侧态复核（GetFile 幂等本地查询）——在飞/完成让路
                var action = tempFragmentAction(f.name, age, active, staleMs)
                if (action == TempAction.DELETE && fid != null) {
                    val file = runCatching { getFile(fid) }.getOrNull()
                    val l = file?.local
                    if (l != null) {
                        action = tempFragmentAction(f.name, age, active, staleMs, l.isDownloadingActive, l.isDownloadingCompleted)
                    }
                }
                when (action) {
                    TempAction.KEEP_ACTIVE -> keptActive++
                    TempAction.KEEP_FRESH -> keptFresh++
                    TempAction.KEEP_COMPLETED -> keptFresh++
                    TempAction.DELETE -> {
                        if (fid != null) {
                            // 一致删除优先；失败不致命（孤儿文件走下面的裸删兜底）
                            runCatching { deleteFileTd(fid) }
                        }
                        if (f.exists() && f.delete()) {
                            freed += len
                            removed++
                        }
                    }
                }
            }
        }
        if (removed > 0) {
            Log.i(
                TAG,
                "temp 碎片清扫（${if (aggressive) "激进" else "常态"}口径）：删 $removed 个回收 ${fmtMb(freed)}MB" +
                    "（活跃让路 $keptActive / 新鲜保留 $keptFresh）",
            )
        } else {
            Log.d(TAG, "temp 碎片清扫：无可清（活跃 $keptActive / 新鲜 $keptFresh）")
        }
        return freed to removed
    }

    private fun tempRoots(): List<File> = listOf(
        File(filesDir, "tdlib/files/temp"),
        File(filesDir, "tdlib/temp"),
    )

    // ---------------- T1：全载保留上限（R195 接口） ----------------

    /**
     * R195 登记口：发起邻居预热整载时调用（幂等：重复登记刷新 lastUse）。
     * 「全载文件保留上限=最近 N 条」由登记表驱动，淘汰统一在本件执行——
     * R195 只管登记与 touch，不做自己的删除链（分工见 r196_notes 二节）。
     */
    fun registerFullLoad(fileId: Int, key: String? = null) {
        if (fileId <= 0) return
        fullLoads[fileId] = FullLoadEntry(fileId, key, SystemClock.elapsedRealtime())
    }

    /** R195 续命口：滑到登记条目（无感接管变「当前」）时调用，保住最近使用位 */
    fun touchFullLoad(fileId: Int) {
        if (fileId <= 0) return
        val e = fullLoads[fileId] ?: return
        fullLoads[fileId] = e.copy(lastUseMs = SystemClock.elapsedRealtime())
    }

    /** R195 撤销口（预热被作废/换目标时；完成的全载文件留在表里等 N 上限淘汰） */
    fun unregisterFullLoad(fileId: Int) {
        fullLoads.remove(fileId)
    }

    /**
     * 全载保留上限淘汰：候选 = fullLoadEvictionCandidates（纯函数判定），
     * 逐条 TDLib DeleteFile（在飞让路：GetFile isDownloadingActive 跳过等下轮）。
     * 释放量按文件账面 size 记（DeleteFile 删的是整个本地副本）。
     */
    private suspend fun enforceFullLoadRetention(): EvictStats {
        val keep = prefs.storageFullLoadRetention
        val candidates = fullLoadEvictionCandidates(fullLoads.values.toList(), keep, activeFileIds())
        if (candidates.isEmpty()) return EvictStats(0, 0L)
        var evicted = 0
        var freed = 0L
        for (victim in candidates) {
            val f = runCatching { getFile(victim.fileId) }.getOrNull() ?: continue
            if (f.local.isDownloadingActive) continue // 在飞（如被 seek 抢占重下）让路
            if (runCatching { deleteFileTd(victim.fileId) }.getOrDefault(false)) {
                fullLoads.remove(victim.fileId)
                freed += maxOf(f.size, f.local.downloadedSize, 0L)
                evicted++
                Log.i(
                    TAG,
                    "全载淘汰：fileId=${victim.fileId}（保留最近 $keep 条之外的登记项）回收 ${fmtMb(maxOf(f.size, 0L))}MB",
                )
            }
        }
        return EvictStats(evicted, freed)
    }

    private data class EvictStats(val evicted: Int, val freedBytes: Long)

    // ---------------- 概览与手动清理（设置页消费面） ----------------

    /**
     * 存储概览（盘测）：filesDir 一次 walk 分桶统计（实现单源
     * [measureStorageOverview]——演示模式 VM 也走它）。深索引/TDLib 缓存/temp/
     * 消息库分项 + 总占用。
     */
    suspend fun storageOverview(): StorageOverview = kotlinx.coroutines.withContext(Dispatchers.IO) {
        measureStorageOverview(filesDir)
    }

    /** temp 目录体积盘测（探针压力判定用；不递归子目录——temp 是平铺布局） */
    private fun measureTempBytes(): Long = tempRoots().sumOf { root ->
        root.listFiles()?.filter { it.isFile }.orEmpty().sumOf { it.length() }
    }

    /**
     * 手动「立即清理」：整轮（激进口径），同步返回给设置页的摘要文本。
     * 与压力/定期轮共用一条分级链（一个口径一套日志），IO 线程由调用方保证
     * （FeedStoragePart launch Dispatchers.IO）。
     */
    suspend fun runManualCleanup(): String {
        val result = runFullRound(aggressive = true, cause = "手动")
        return "清理完成：释放 ${fmtMb(result.totalFreedBytes)}MB" +
            "（暂存 ${fmtMb(result.tempFreedBytes)}MB·${result.tempRemoved} 文件 / " +
            "全载淘汰 ${result.fullLoadEvicted} 条 / 水位 ${fmtMb(result.watermarkFreedBytes)}MB）"
    }
}
