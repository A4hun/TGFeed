package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

// ---------------- R204 WK-2 频道宇宙作息表（全索引小时×星期 rollup，数据侧） ----------------

/**
 * 宇宙作息表数据口（R170 重查询合规范式的又一成员）：
 *  - **读快照毫秒级**（陈值先回：作息分布是天级稳定事实，陈度无害）；
 *  - 快照缺失/过期 → **后台单飞重建**（全表 GROUP BY ~4-10s，绝不进请求路径；
 *    重建期间陈值照用，无陈值 UI 出「统计中」态）；
 *  - 触发点=进发现页（DiscoverScreen LaunchedEffect，与 refreshDiscovery 同拍但
 *    互不阻塞——本口零网络，纯本地索引）。
 * 索引未建（库文件不存在）→ 发布 hasData()=false 的空态（诚实呈现「深索引还没建立」，
 * 绝不为统计建库开写库——loadIndexChannelStats 家族纪律）。
 */
internal fun FeedViewModel.refreshUniverseRhythmImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        val file = File(app.filesDir, UniverseRhythm.SNAPSHOT_FILE)
        // ① 快照优先（读盘毫秒级）：读到即当陈值发布，过期继续后台重建
        val snap = runCatching { UniverseRhythm.loadSnapshot(file) }.getOrNull()
        if (snap != null) {
            _universeRhythm.value = snap
            if (!snap.isStale()) return@launch
        }
        // ② 后台单飞重建（并发触发只建一次）
        if (!universeRhythmBuilding.compareAndSet(false, true)) return@launch
        try {
            val dbFile = tierDbFile()
            if (!dbFile.exists()) {
                // 从未建过索引：发空态快照（builtAt=now——空库也是事实，别每次进页都试）
                _universeRhythm.value = UniverseRhythm.Rhythm(
                    builtAtMs = System.currentTimeMillis(),
                    tzOffsetSec = 0,
                    channels = 0,
                    items = 0L,
                    hours = IntArray(24),
                    weekdays = IntArray(7),
                    matrix = IntArray(24 * 7),
                )
                return@launch
            }
            val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
            val built = runCatching {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                    UniverseRhythm.build(d, tz)
                }
            }.getOrElse {
                Log.w("FeedViewModel", "宇宙作息表 rollup 失败（陈值照用）: ${it.message}")
                null
            } ?: return@launch
            _universeRhythm.value = built
            runCatching { UniverseRhythm.saveSnapshot(file, built) }
            Log.i(
                "FeedViewModel",
                "宇宙作息表重建：频道=${built.channels} 条目=${built.items} 峰时=${UniverseRhythm.peakHour(built)}点",
            )
        } finally {
            universeRhythmBuilding.set(false)
        }
    }
}

/** 快照读侧（UI 直查口：DiscoverScreen 进页触发刷新后随手收集） */
internal suspend fun FeedViewModel.universeRhythmNow(): UniverseRhythm.Rhythm? =
    withContext(Dispatchers.IO) {
        _universeRhythm.value ?: runCatching {
            UniverseRhythm.loadSnapshot(File(app.filesDir, UniverseRhythm.SNAPSHOT_FILE))
        }.getOrNull()?.also { _universeRhythm.value = it }
    }
