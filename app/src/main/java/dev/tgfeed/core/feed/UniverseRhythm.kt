package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R204 WK-2 频道宇宙作息表（timeline-opportunities WK-2 / WOW-SEARCH W3）：
 * 「TG 世界几点醒」——全网已索引频道按时段（小时×星期）的发布密度。
 *
 * 数据口径（诚实边界，UI 足注同步标注）：
 *  - 源=本地深索引 items（已索引频道的全量/采样时间戳），**不是**服务端全网——
 *    覆盖 N 个频道如实播报；怪兽频道按月采样（R46）对小时分布无偏；
 *  - 小时按**本机时区**折算（snapshot 记录 tzOffsetSec；DST 漂移按构建时刻近似——
 *    作息表语义容差远大于夏令时一小时）；
 *  - 服务端 getChatActivityDays 是**日级**直方图（无小时维度）——本表不走它；
 *    月历原语归 TL-5 单频道编年史，各就各位不混用。
 *
 * 性能纪律（R183 LIKE 全表扫教训 + R170 合规范式）：
 *  一条 `GROUP BY 小时, 星期` 的全表聚合（2M 行级 ~4-10s，读 dateEpochSec 必须回表）
 *  绝不进请求路径——**后台单飞 + 磁盘快照**（R170 陈值先回：读快照毫秒级，过期
 *  [SNAPSHOT_TTL_MS] 才由调用方后台重建，重建中陈值照用）。快照放 files 根
 *  （门房辖区之外的既定纪律，与 discovery_gold_snapshot.json 同款）。
 *
 * 纯函数集合（JVM 可单测）+ 快照 IO 两件；不持可变状态、不反向依赖 VM 层。
 */
object UniverseRhythm {

    /** 快照文件名（files 根；所有权=宇宙作息表，门房辖区之外） */
    const val SNAPSHOT_FILE = "universe_rhythm_snapshot.json"

    /** 快照格式版本（老版本/损坏文件按无快照重建，不崩） */
    private const val SNAPSHOT_VERSION = 1

    /** 快照 TTL（12 小时）：作息分布是天级稳定事实，陈度无害 */
    const val SNAPSHOT_TTL_MS = 12L * 3600_000

    /** 星期标签（下标=周内序，0=周日；epoch 0 日=1970-01-01 周四，SQL 侧 +4 归位） */
    val WEEKDAY_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")

    /**
     * 作息矩阵：24 小时 × 7 星期（行=小时，列=星期；[matrix] 下标 = hour*7 + weekday）。
     * [hours]/[weekdays] 为边际合计；[items]/[channels] 为覆盖口径（诚实足注用）。
     */
    data class Rhythm(
        val builtAtMs: Long,
        val tzOffsetSec: Int,
        val channels: Int,
        val items: Long,
        val hours: IntArray,
        val weekdays: IntArray,
        val matrix: IntArray,
    ) {
        fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
            builtAtMs <= 0 || nowMs - builtAtMs > SNAPSHOT_TTL_MS

        /** 有料判定（零频道/零条 = 索引未建或空库——UI 出诚实空态） */
        fun hasData(): Boolean = items > 0 && hours.any { it > 0 }
    }

    /**
     * 构建作息矩阵（IO 协程调用；**唯一重查询**，调用方负责后台单飞防重入）：
     * 一条 GROUP BY 全表聚合（小时×星期同刻算出，一次回表扫描）+ 一条覆盖索引
     * COUNT(DISTINCT chatId)（idx_items_chat_msg 纯索引扫描，秒级）。
     * 索引未建/查询失败返回 hasData()=false 的空矩阵（空库也是事实，别让每次都重建）。
     */
    fun build(db: SQLiteDatabase, tzOffsetSec: Int, nowMs: Long = System.currentTimeMillis()): Rhythm {
        val hours = IntArray(24)
        val weekdays = IntArray(7)
        val matrix = IntArray(24 * 7)
        var items = 0L
        // R266 v11 双臂（D4 现状口径：每次转发各计一份）：items 臂 + refs 臂
        // （repost_refs）同形聚合，格值相加合并；refs 臂独立容错（升级窗口期 v10
        // 库文件无此表——缺席=存量形态直通）
        val armSql = { table: String ->
            "SELECT ((dateEpochSec+?)/3600)%24 AS h, ((dateEpochSec/86400)+4)%7 AS wd, COUNT(*) " +
                "FROM $table GROUP BY h, wd"
        }
        for (table in listOf("items", "repost_refs")) {
            runCatching {
                // 小时（本机时区）：((dateEpochSec+tz)/3600)%24；星期（UTC 日归一）：((day)+4)%7，0=周日
                db.rawQuery(armSql(table), arrayOf(tzOffsetSec.toString())).use { c ->
                    while (c.moveToNext()) {
                        val h = c.getInt(0)
                        val wd = c.getInt(1)
                        val n = c.getInt(2)
                        if (h in 0..23 && wd in 0..6) {
                            hours[h] += n
                            weekdays[wd] += n
                            matrix[h * 7 + wd] += n // 双臂相加（旧单臂赋值语义的推广）
                            items += n
                        }
                    }
                }
            }
        }
        val channels = runCatching {
            // 覆盖频道数=发布侧全集（refs-only 频道也算已索引）；refs 缺席回退 items 单表
            runCatching {
                db.rawQuery(
                    "SELECT COUNT(*) FROM (SELECT chatId FROM items UNION SELECT chatId FROM repost_refs)",
                    null,
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }.getOrElse {
                db.rawQuery("SELECT COUNT(DISTINCT chatId) FROM items", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
        return Rhythm(nowMs, tzOffsetSec, channels, items, hours, weekdays, matrix)
    }

    // ---------------- 纯格式化（JVM 可单测） ----------------

    /** 峰值小时下标（并列取最先；无数据 -1） */
    fun peakHour(r: Rhythm): Int {
        var best = -1
        var bestN = 0
        for (h in r.hours.indices) if (r.hours[h] > bestN) {
            bestN = r.hours[h]
            best = h
        }
        return best
    }

    /** 峰值星期下标（并列取最先；无数据 -1） */
    fun peakWeekday(r: Rhythm): Int {
        var best = -1
        var bestN = 0
        for (w in r.weekdays.indices) if (r.weekdays[w] > bestN) {
            bestN = r.weekdays[w]
            best = w
        }
        return best
    }

    /** 最沉睡小时（发布最少且 >0；全 0 返回 -1——空矩阵别硬凑结论） */
    fun quietHour(r: Rhythm): Int {
        var worst = -1
        var worstN = Int.MAX_VALUE
        for (h in r.hours.indices) if (r.hours[h] in 1 until worstN) {
            worstN = r.hours[h]
            worst = h
        }
        return worst
    }

    /** 小时柱状归一化（0..1，峰值=1；24 长，供 Canvas 直画） */
    fun hourBars(r: Rhythm): FloatArray {
        val out = FloatArray(24)
        var max = 0
        for (v in r.hours) if (v > max) max = v
        if (max <= 0) return out
        for (h in r.hours.indices) out[h] = r.hours[h].toFloat() / max
        return out
    }

    /** 星期柱状归一化（0..1；7 长） */
    fun weekdayBars(r: Rhythm): FloatArray {
        val out = FloatArray(7)
        var max = 0
        for (v in r.weekdays) if (v > max) max = v
        if (max <= 0) return out
        for (w in r.weekdays.indices) out[w] = r.weekdays[w].toFloat() / max
        return out
    }

    /** 覆盖足注（诚实口径）：「基于本地深索引 203 个频道 · 412.6 万条」 */
    fun coverageLine(r: Rhythm): String {
        val itemsText = if (r.items >= 10_000) {
            val w = r.items / 10_000
            val d = r.items % 10_000 / 1_000
            if (d == 0L) "${w} 万条" else "${w}.${d} 万条"
        } else {
            "${r.items} 条"
        }
        return "基于本地深索引 ${r.channels} 个频道 · $itemsText"
    }

    // ---------------- 快照落盘/读盘（LocalStore 同款：临时文件 + rename 原子写） ----------------

    /** 快照原子落盘（IO 协程；失败静默——陈值快照还在，下次重建再试） */
    fun saveSnapshot(file: File, r: Rhythm) {
        runCatching {
            val root = JSONObject()
                .put("v", SNAPSHOT_VERSION)
                .put("builtAtMs", r.builtAtMs)
                .put("tz", r.tzOffsetSec)
                .put("channels", r.channels)
                .put("items", r.items)
                .put("hours", JSONArray(r.hours.toList()))
                .put("weekdays", JSONArray(r.weekdays.toList()))
                .put("matrix", JSONArray(r.matrix.toList()))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）
                tmp.delete()
            }
        }
    }

    /** 快照读盘（IO 协程；文件缺失/损坏/版本不符返回 null——调用方按无快照重建） */
    fun loadSnapshot(file: File): Rhythm? {
        return runCatching {
            if (!file.isFile) return@runCatching null
            val root = JSONObject(file.readText())
            if (root.optInt("v", -1) != SNAPSHOT_VERSION) return@runCatching null
            val hours = intArrayOfJson(root.optJSONArray("hours") ?: return@runCatching null, 24)
                ?: return@runCatching null
            val weekdays = intArrayOfJson(root.optJSONArray("weekdays") ?: return@runCatching null, 7)
                ?: return@runCatching null
            val matrix = intArrayOfJson(root.optJSONArray("matrix") ?: return@runCatching null, 24 * 7)
                ?: return@runCatching null
            Rhythm(
                builtAtMs = root.optLong("builtAtMs", 0L),
                tzOffsetSec = root.optInt("tz", 0),
                channels = root.optInt("channels", 0),
                items = root.optLong("items", 0L),
                hours = hours,
                weekdays = weekdays,
                matrix = matrix,
            )
        }.getOrNull()
    }

    /** JSON 数组 → 定长 IntArray（长度不符/坏元素返回 null——防半截快照当真用） */
    private fun intArrayOfJson(arr: JSONArray, expectLen: Int): IntArray? {
        if (arr.length() != expectLen) return null
        val out = IntArray(expectLen)
        for (i in 0 until expectLen) {
            val v = arr.optInt(i, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) return null
            out[i] = v
        }
        return out
    }
}
