package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ---------------- R204 DS-5 首发溯源（同 fileUniqueId 跨频道最早出现，数据侧） ----------------

/**
 * 首发溯源数据口：用户点按才触发（WK-1 考古学家先例「点按付费」，零被动成本）。
 *  - 三级缓存：会话内存 → 磁盘账本（跨会话，LRU 512）→ 全表扫（扫完回写账本）；
 *  - [firstSeenLock] 串行化并发查证（第二个同指纹调用等锁后走内存命中，不重复扫）；
 *  - [FirstSeenOutcome] 三态：NotApplicable（Mock/本地文件无指纹）/ Missed（索引内
 *    无同指纹副本——诚实空态）/ Found（判定）。查证中的转圈态由 UI 侧 busy 状态承担。
 * 「重新查证」= [forceRescan]=true 绕缓存（账本陈旧时用户兜底口）。
 */
internal sealed interface FirstSeenOutcome {
    /** 条目无指纹（Mock/本地文件/老收藏）：无从溯源，UI 如实说明 */
    data object NotApplicable : FirstSeenOutcome

    /** 深索引里没有同指纹副本（只看得见已索引频道——覆盖外不可见） */
    data object Missed : FirstSeenOutcome

    /** 溯源判定（副本升序，首位=首发） */
    data class Found(val verdict: FirstSeenLedger.Verdict) : FirstSeenOutcome
}

internal fun FeedViewModel.firstSeenImpl(
    item: dev.tgfeed.core.td.VideoItem,
    forceRescan: Boolean = false,
): FirstSeenOutcome {
    return synchronized(firstSeenLock) {
    val fp = item.fileUniqueId
    if (fp.isBlank()) return FirstSeenOutcome.NotApplicable
    // ① 会话内存
    if (!forceRescan) {
        firstSeenMemory[fp]?.let { return it }
    }
    // ② 磁盘账本（同步 IO：点按触发低频口，毫秒级小文件读可接受；不进请求路径）
    if (!forceRescan) {
        val ledger = runCatching {
            FirstSeenLedger.load(File(app.filesDir, FirstSeenLedger.LEDGER_FILE))
        }.getOrDefault(emptyMap())
        ledger[fp]?.let {
            firstSeenMemory[fp] = FirstSeenOutcome.Found(it)
            return FirstSeenOutcome.Found(it)
        }
    }
    // ③ 索引序点查（只读连接；v11 起走 idx_items_fuid/idx_refs_fuid 毫秒级。
    // R428 就绪门：指纹索引未就绪（升级窗口期后台在建）query 直接返回 null 落
    // Missed——绝不回退全表扫（4M 行秒级~十秒级），索引建成后重查/重新查证即得真判定）
    val dbFile = tierDbFile()
    if (!dbFile.exists()) return FirstSeenOutcome.Missed
    val outcome: FirstSeenOutcome = runCatching {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            val v = FirstSeenLedger.query(
                d, fp, item.chatId, item.messageId,
                fuidIndexReady = deepIndex.fuidIndexReadyCached(d),
            )
            if (v == null) FirstSeenOutcome.Missed else FirstSeenOutcome.Found(v)
        }
    }.getOrElse {
        Log.w("FeedViewModel", "首发溯源查询失败 fp尾号=${fp.takeLast(6)}: ${it.message}")
        FirstSeenOutcome.Missed
    }
    // 扫描结果回写：命中入账本（跨会话免重扫）；Missed 只进内存（索引长出新副本前
    // 反复 Missed 不落盘——账本留给真判定，防 Missed 被账本钉死）
    if (outcome is FirstSeenOutcome.Found) {
        runCatching { FirstSeenLedger.save(File(app.filesDir, FirstSeenLedger.LEDGER_FILE), outcome.verdict) }
    }
    firstSeenMemory[fp] = outcome
    outcome
    }
}

/** 溯源取数的 IO 包装（UI 协程直调；内部单飞/缓存都在 [firstSeenImpl]） */
internal suspend fun FeedViewModel.firstSeenOutcomeFor(
    item: dev.tgfeed.core.td.VideoItem,
    forceRescan: Boolean = false,
): FirstSeenOutcome = withContext(Dispatchers.IO) { firstSeenImpl(item, forceRescan) }
