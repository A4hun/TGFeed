package dev.tgfeed.core.store

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 频道级入池曝光账（R75-C，度量先行）：channel 维度持久记录——
 * 最近入池时刻 / 累计入池轮数 / 累计进页条数。
 *
 * 口径说明（与 ExposureTracker 的条目级 UI 曝光刻意不同）：
 * 记的是「进池」——fetchAggregated 轮换采样把频道抽进当轮 24 池、给过它被拉取的机会；
 * 进页条数是「该频道本轮实际贡献进页管道的条数」（拉取侧口径，不代表用户滑到）。
 * 之前系统只有条目级曝光账（ExposureTracker）与会话内存态频道疲劳（ChannelFatigueTracker），
 * 没有任何跨会话的「频道从未进池/多久未进池」统计——系统自己都不知道哪些频道在饿死，
 * 保底配额（R75-A 的 poolRecencyProvider 数据源）与效果度量都无从谈起。
 *
 * 落盘 filesDir/channel_exposure.json，小 JSON 全量覆盖写（tmp+rename 原子替换，
 * 与 LocalStore.writeText 同口径），读-改-写同锁完成；懒加载（首次访问才读盘），
 * 全部读写假定在 IO 线程（网关拉取回调链），单文件故障静默降级为空账（度量数据，
 * 丢了只影响保底队列的排序精度，不影响正确性）。
 *
 * JSON 形态：{"v":1, "ch": {"<chatId>": {"last": epochMs, "rounds": n, "items": n}}}
 */
class ChannelExposureLedger(context: Context) {

    /** 单频道账面：最近入池时刻（epoch ms）/ 累计入池轮数 / 累计进页条数 */
    data class Entry(val lastPooledAt: Long, val poolRounds: Int, val pooledItems: Int)

    private val file = File(context.filesDir, "channel_exposure.json")
    private val lock = Any()

    /** 内存账面（copy-on-write 快照，锁外可读）；全部改写走 [lock] */
    @Volatile
    private var table: Map<Long, Entry> = emptyMap()

    /** 懒加载标记：首次访问才读盘，不阻塞 VM 构造 */
    @Volatile
    private var loaded = false

    /** 保底队列数据源：chatId → 上次入池时刻；无记录的频道 = 从未入池（调用方按 0 处理） */
    fun lastPooledAt(): Map<Long, Long> {
        ensureLoaded()
        return table.mapValues { it.value.lastPooledAt }
    }

    /** 账面快照（度量/诊断用）：chatId → 完整账面条目 */
    fun snapshot(): Map<Long, Entry> {
        ensureLoaded()
        return table
    }

    /**
     * 记一轮入池（fetchAggregated 新轮抽池完成，经 FeedViewModel 的 poolReporter 链到达）：
     * 本轮每个进池频道 lastPooledAt=now、poolRounds+1。
     */
    fun recordPoolEntries(ids: Set<Long>, nowMs: Long) {
        if (ids.isEmpty()) return
        runCatching {
            ensureLoaded()
            synchronized(lock) {
                val out = HashMap(table)
                for (id in ids) {
                    val cur = out[id]
                    out[id] = Entry(
                        lastPooledAt = nowMs,
                        poolRounds = (cur?.poolRounds ?: 0) + 1,
                        pooledItems = cur?.pooledItems ?: 0,
                    )
                }
                table = out
                persistLocked()
            }
            Log.d(TAG, "频道入池账：本轮记 ${ids.size} 频道，账面共 ${table.size} 频道")
        }.onFailure { Log.w(TAG, "频道入池账记录失败: ${it.message}") }
    }

    /**
     * 记本轮各频道实际进页条数（网关拉取侧口径：该频道本轮贡献进页管道的条数，
     * 非 UI 曝光）；只累加到已有账面（未入池不会有点数，防御性补零条目不落盘时刻）。
     */
    fun recordPooledItems(counts: Map<Long, Int>) {
        if (counts.isEmpty()) return
        runCatching {
            ensureLoaded()
            synchronized(lock) {
                val out = HashMap(table)
                for ((id, n) in counts) {
                    if (n <= 0) continue
                    val cur = out[id]
                    // 无账面（理论上 poolReporter 先到）时补一条 last=0 的零条目：
                    // 「进过页但不知哪轮进的池」好过漏记
                    out[id] = Entry(
                        lastPooledAt = cur?.lastPooledAt ?: 0L,
                        poolRounds = cur?.poolRounds ?: 0,
                        pooledItems = (cur?.pooledItems ?: 0) + n,
                    )
                }
                table = out
                persistLocked()
            }
        }.onFailure { Log.w(TAG, "频道进页条数记账失败: ${it.message}") }
    }

    /** 懒加载读盘：文件不存在/损坏/形态不对一律空账起步（防御：度量数据不崩主功能） */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
            runCatching {
                val root = JSONObject(text)
                val ch = root.optJSONObject("ch") ?: return@runCatching
                val out = HashMap<Long, Entry>()
                val keys = ch.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue // 坏 key 跳过
                    val e = ch.optJSONObject(k) ?: continue
                    val last = e.optLong("last", 0L)
                    val rounds = e.optInt("rounds", 0)
                    val items = e.optInt("items", 0)
                    if (last < 0L || rounds < 0 || items < 0) continue // 负值脏数据跳过
                    out[id] = Entry(last, rounds, items)
                }
                table = out
            }
        }
    }

    /** 全量原子落盘（tmp+rename，与 LocalStore.writeText 同口径）；必须在 [lock] 内调用 */
    private fun persistLocked() {
        val root = JSONObject()
        val ch = JSONObject()
        for ((id, e) in table) {
            ch.put(
                id.toString(),
                JSONObject()
                    .put("last", e.lastPooledAt)
                    .put("rounds", e.poolRounds)
                    .put("items", e.pooledItems),
            )
        }
        root.put("v", 1).put("ch", ch)
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // rename 失败（个别 ROM/占用）回落直写，尽量不留半截文件
                runCatching { file.writeText(root.toString()) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "频道入池账落盘失败: ${it.message}") }
    }

    private companion object {
        private const val TAG = "ChannelExposureLedger"
    }
}
