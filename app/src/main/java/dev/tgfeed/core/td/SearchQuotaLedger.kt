package dev.tgfeed.core.td

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R184 账号级搜索配额账本（SearchPublicChat + SearchChatsOnServer 全族共享）。
 *
 * 案发背景：账号被服务端封 SearchPublicChat 9.2 小时（Too Many Requests: retry after
 * 32560——账号级 429）。既有防线全是「车道级」（查询/收割/爬取各管各的 FloodBudget）
 * 且「事后型」（FloodCircuitBreaker 只在撞 429 后静默）——没有任何一张
 * 「账号 × 搜索族」总账，也没有「触不到服务端红线」的前置保守节流。
 *
 * 治理口径（用户拍板四条）：
 *  1. **封禁窗持久化**：429 的 retry-after 落盘（banUntil，取 max 不缩短）；
 *     封禁窗内 [tryAcquire] 直接 false——**连试都不许试**（连试会续封/升级，
 *     TG 洪水惩罚是累进的）。横跨进程重启（内存态重启清零是假防守，R44 同判词）。
 *  2. **主动保守节流**：不等 429 才退——默认总量 12 次/小时（极保守），后台/爬虫
 *     车道份额极小（「几乎全拒」是设计目标）；用户动作也过预算闸。
 *  3. **累进记忆**：历史 429 事件数落盘（banHistory）；有效配额 = 基数 × 0.75^次数，
 *     下限 0.25——账号越红越省着用。
 *  4. **三车道优先级**：USER_ACTION（用户动作）> BACKGROUND（后台解析）>
 *     CRAWLER（爬虫/验证）。总量剩余 ≤ 半数时非用户车道全拒（保用户动作余量）。
 *
 * 与 [FloodCircuitBreaker] 的分工：熔断表管「每方法族的半开试探状态机」（R58，
 * 解封后逐发试探防瞬间放量）；本账本管「账号桶总预算 + 封禁窗 + 累进衰减」。
 * 429 上报经 TdLibGateway.reportAccountSearch429 同时写两处（双族同账同静默）。
 *
 * 纯函数可测：构造注入 [clock]；[dir] 传 null 即纯内存态（单测用），
 * 真实网关注入 filesDir 落 search_quota.json（tmp+rename 原子写，AnswerStore 同口径）。
 */
class SearchQuotaLedger(
    private val dir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 搜索请求车道（优先级即拒发顺序：CRAWLER 最先被拒，USER_ACTION 最后） */
    enum class Lane(val label: String) {
        /** 用户动作（点击答案行/深链/搜索提交/@提及）——最优先，保底余量专属 */
        USER_ACTION("用户动作"),

        /** 后台解析（EntityResolver 管线/答案标题批量/进发现页懒解析/广场查找） */
        BACKGROUND("后台解析"),

        /** 爬虫与设备端验证（热词榜收割/实体发现扫描/代理测试——最先被拒） */
        CRAWLER("爬虫/验证"),
    }

    // ---------------- 内存态（唯一事实源；null = 未加载） ----------------

    /** 滑动窗内已放行的发送时间戳（毫秒）× 车道——窗口外的在读盘/每次判定时惰性剪枝 */
    private var stamps: ArrayList<Pair<Long, Lane>>? = null

    /** 封禁截止时刻（毫秒）；0 = 未封。429 上报取 max 不缩短 */
    private var banUntil = 0L

    /** 历史 429 事件数（累进记忆：次数越多配额越低） */
    private var banHistory = 0

    /** 最近一次 429 时刻（诊断） */
    private var lastBanAt = 0L

    private val file: File? get() = dir?.let { File(it, FILE_NAME) }

    // ---------------- 判定（纯策略核） ----------------

    /**
     * 预算判定（入口唯一）：封禁窗内 / 车道份额耗尽 / 总量耗尽 / 总量余量进保底区
     * 都拒绝；放行即记账（时间戳入窗）。线程安全（@Synchronized）。
     */
    @Synchronized
    fun tryAcquire(lane: Lane): Boolean {
        val now = clock()
        pruneLocked(now)
        val st = loadLocked()
        if (now < banUntil) {
            Log.d(
                TAG,
                "搜索账本: 封禁窗内拒发（车道=${lane.label} 剩 ${remainingBanSecLocked()}s，" +
                    "历史封禁 $banHistory 次）——连试都不试，防续封",
            )
            return false
        }
        val totalCap = totalCapLocked()
        val used = st.size
        if (used >= totalCap) {
            Log.d(TAG, "搜索账本: 总量耗尽拒发（车道=${lane.label} 用 $used/$totalCap，历史封禁 $banHistory 次）")
            return false
        }
        val laneCap = laneCapLocked(lane)
        if (st.count { it.second == lane } >= laneCap) {
            Log.d(TAG, "搜索账本: 车道份额耗尽拒发（${lane.label} ≤$laneCap/h）")
            return false
        }
        // 保底余量：总量剩余进半数区后，非用户车道全拒（用户动作的最后防线）
        if (lane != Lane.USER_ACTION && totalCap - used <= userReserveLocked()) {
            Log.d(TAG, "搜索账本: 保底余量区拒发（${lane.label}，剩 $used/$totalCap 中保 ${userReserveLocked()} 给用户动作）")
            return false
        }
        st.add(now to lane)
        // 每次记账即写盘：搜索调用本就是超低频事件（≤12/h），逐条持久化换取
        // 「重启不清零」的精确语义（阈值节流会让最后几发在重启时丢失——假防守）
        flushLocked()
        return true
    }

    /**
     * R378b 记账顺序正治（R379 挂账①收口）：统一发送口被 withTdTimeout 内
     * silentGuard **前置拒发**（配速/静默两味——REJECT=零请求零承压）时，回退
     * 本口刚记的这 1 枚令牌：「连试都没试」不该烧账号配额（记账与真实发送对齐，
     * 与封禁窗/车道拒发的零记账同口径）。超时/真发送失败**不退**（请求已发=
     * 保守记账，R364「已记预算不退」判词零改）。回退口径：摘除最近 1 枚同车道
     * stamp——并发下同车道同窗多枚时任意一枚等价（每次前置拒发恰对应一次刚记
     * 的记账，窗内总量恒等于真实发送数）。
     */
    @Synchronized
    fun refund(lane: Lane) {
        val st = loadLocked()
        val idx = st.indexOfLast { it.second == lane }
        if (idx < 0) return
        st.removeAt(idx)
        flushLocked()
        Log.d(TAG, "搜索账本: 前置拒发回退 1 枚（${lane.label}）——零请求不烧配额（R379 挂账① 记账顺序正治）")
    }

    /**
     * 上报一次 429（封禁窗持久化 + 累进记忆）：banUntil 取 max 不缩短（多条泳道并发
     * 撞墙时后到的小 retry-after 不得把先到的大封禁冲短——与熔断表同口径）；
     * 服务端没给 retry-after 按 [DEFAULT_BAN_SEC] 兜底（不能照打）。立即落盘。
     * @return 本次生效的封禁秒数（诊断/上抛用）
     */
    @Synchronized
    fun report429(retryAfterSec: Long): Long {
        loadLocked()
        val now = clock()
        val waitSec = if (retryAfterSec > 0) retryAfterSec else DEFAULT_BAN_SEC
        val newUntil = now + waitSec * 1000L
        if (newUntil > banUntil) banUntil = newUntil
        banHistory++ // 累进记忆：每个 429 事件都计数（封禁期内闸拒零发送，不会重复上报）
        lastBanAt = now
        flushLocked()
        Log.w(
            TAG,
            "搜索账本: 429 上报——封禁窗 ${waitSec}s（账号级，跨进程持久）" +
                "历史封禁累计 $banHistory 次，配额衰减至 ${decayFactorLocked().formatDecay()}",
        )
        return waitSec
    }

    /** 封禁剩余秒数（不在封禁窗 = 0；纯读不加载副作用之外的变更） */
    @Synchronized
    fun remainingBanSec(): Long = remainingBanSecLocked()

    /** 历史封禁事件数（诊断/设置页展示） */
    @Synchronized
    fun banHistoryCount(): Int {
        loadLocked()
        return banHistory
    }
    /** 当前有效总量上限（衰减后；诊断） */
    @Synchronized
    fun totalCap(): Int {
        loadLocked()
        return totalCapLocked()
    }

    /** 诊断快照：账面一句话（设置页/日志） */
    @Synchronized
    fun snapshot(): String {
        val st = loadLocked()
        pruneLocked(clock())
        return "封禁剩=${remainingBanSecLocked()}s 历史封禁=$banHistory 次 " +
            "配额=${st.size}/${totalCapLocked()}/h（用户${st.count { it.second == Lane.USER_ACTION }} " +
            "后台${st.count { it.second == Lane.BACKGROUND }} 爬虫验证${st.count { it.second == Lane.CRAWLER }}）"
    }

    /** 进程退出前兜底落盘（网关 close() 链调用；纯内存态（dir=null）空转） */
    @Synchronized
    fun flush() {
        if (dir == null || stamps == null) return
        flushLocked()
    }

    // ---------------- 内部（持锁） ----------------

    private fun loadLocked(): ArrayList<Pair<Long, Lane>> {
        stamps?.let { return it }
        val st = ArrayList<Pair<Long, Lane>>()
        runCatching {
            val f = file ?: return@runCatching
            if (!f.isFile) return@runCatching
            val root = JSONObject(f.readText())
            banUntil = root.optLong("banUntil", 0L)
            banHistory = root.optInt("banHistory", 0)
            lastBanAt = root.optLong("lastBanAt", 0L)
            root.optJSONArray("stamps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONArray(i) ?: continue
                    val ts = o.optLong(0, 0L)
                    val lane = o.optInt(1, -1)
                    if (ts > 0 && lane in 0..2) st.add(ts to Lane.entries[lane])
                }
            }
        }.onFailure { Log.w(TAG, "search_quota.json 读取失败（按空账本重建）: ${it.message}") }
        stamps = st
        if (banUntil > clock() || banHistory > 0) {
            // 重启恢复可见性（验证要点：封禁窗跨进程生效）
            Log.i(
                TAG,
                "搜索账本已从磁盘恢复：封禁剩 ${remainingBanSecLocked()}s 历史封禁 $banHistory 次" +
                    "（有效配额 ${totalCapLocked()}/h）",
            )
        }
        return st
    }

    /** 滑动窗剪枝：1 小时外的时间戳出窗（读盘后/每次判定时；剪枝即落盘保持精确） */
    private fun pruneLocked(now: Long) {
        val st = stamps ?: return
        val cutoff = now - WINDOW_MS
        val before = st.size
        if (before > 0) {
            st.removeAll { it.first < cutoff }
            if (st.size != before) flushLocked()
        }
    }

    /** 累进衰减系数：0.75^历史封禁次数，下限 0.25（账号越红越省） */
    private fun decayFactorLocked(): Double {
        var f = 1.0
        repeat(banHistory.coerceAtMost(MAX_DECAY_EXPONENT)) { f *= 0.75 }
        return f.coerceAtLeast(0.25)
    }

    /** 有效总量：基数 × 衰减，至少 [MIN_TOTAL_CAP]（保用户动作有一口气） */
    private fun totalCapLocked(): Int =
        (BASE_TOTAL_PER_HOUR * decayFactorLocked()).toInt().coerceAtLeast(MIN_TOTAL_CAP)

    /** 车道份额：用户=总量；后台=⌈总量/3⌉；爬虫/验证=⌈总量/6⌉（几乎全拒是设计目标） */
    private fun laneCapLocked(lane: Lane): Int = when (lane) {
        Lane.USER_ACTION -> totalCapLocked()
        Lane.BACKGROUND -> (totalCapLocked() + 2) / 3
        Lane.CRAWLER -> (totalCapLocked() + 5) / 6
    }

    /** 保底余量：总量剩余进此区后非用户车道全拒 */
    private fun userReserveLocked(): Int = (totalCapLocked() + 1) / 2

    private fun remainingBanSecLocked(): Long {
        loadLocked()
        val remain = banUntil - clock()
        return if (remain > 0) remain / 1000 else 0L
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（AnswerStore 同口径）；纯内存态空转 */
    private fun flushLocked() {
        val st = stamps ?: return
        val f = file ?: return
        runCatching {
            val root = JSONObject()
                .put("v", 1)
                .put("banUntil", banUntil)
                .put("banHistory", banHistory)
                .put("lastBanAt", lastBanAt)
            val arr = JSONArray()
            for ((ts, lane) in st) arr.put(JSONArray().put(ts).put(lane.ordinal))
            root.put("stamps", arr)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "search_quota.json 落盘失败（下轮重试）: ${it.message}") }
    }

    private fun Double.formatDecay(): String =
        if (this >= 0.995) "1.0" else ((this * 100).toInt() / 100.0).toString()

    companion object {
        private const val TAG = "SearchQuotaLedger"

        /** 落盘文件名（filesDir 根，与 flood_breaker.json 同级） */
        private const val FILE_NAME = "search_quota.json"

        /** 滑动窗：1 小时 */
        private const val WINDOW_MS = 3_600_000L

        /** 基数总量（次/小时）：极保守默认——「触不到服务端红线」是设计目标 */
        private const val BASE_TOTAL_PER_HOUR = 12

        /** 衰减下限对应的最小总量（保用户动作有一口气，不为零） */
        private const val MIN_TOTAL_CAP = 3

        /** 服务端没给 retry-after 时的兜底封禁时长（秒） */
        private const val DEFAULT_BAN_SEC = 600L

        /** 衰减指数上限（0.75^N 再小也被 0.25 下限接住，防幂运算溢出语义） */
        private const val MAX_DECAY_EXPONENT = 20
    }
}
