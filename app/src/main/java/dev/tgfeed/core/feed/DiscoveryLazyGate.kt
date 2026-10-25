package dev.tgfeed.core.feed

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * R377 发现页懒加载门（单源类）——用户 2026-10-24 11:4x 拍板原话：
 * 「发现页要调 SearchPublicChat 类接口可能触发限流——改成用户点击发现页时再请求，
 * 不要软件一打开就在后台酷酷工作，容易封号/被限」。
 *
 * 实锚背景（Phase A 盘点，r377_notes §1）：TdLibGateway 授权 Ready+20s 起跑的收割
 * 总闸门同时点火收割主循环（含热词榜+自动发现 CRAWLER 实证）+EntityResolver 积压轮
 * （BACKGROUND 车道）+直荐验证轮（USER_ACTION 车道）——三者全部不感知用户是否打开过
 * 发现页；发现页刷新的两条背景暗道（R211 直荐监听/金矿首建补刷）也在未开页时发起
 * 搜索族网络臂。账号面 10:36 已隐性限流（searchPublicChatEx 12s 超时×3）。
 *
 * 门语义三档（设计定案 §2.1）：
 *  - 关门（冷启→用户首次点开发现页）：三常驻循环空转、刷新网络臂不执行——发现侧
 *    网络工作零发起；本地读照常（金矿快照/热词榜/图谱/答案库——陈旧如实，无网络）；
 *  - 开门（首开，或距上次授权 ≥[WINDOW_MS] 的再开）：授权一轮——kickOnce 预算内
 *    收割 + 刷新网络臂开（预算/熔断/账本语义零改——**加门不是拆预算**）；
 *  - 窗内再开（< [WINDOW_MS]）：零网络——本地聚合照跑复用缓存；门内常驻循环
 *    （15min 收割节拍/解析轮/直荐轮）按既有预算继续干活（窗=用户意图在场窗）。
 *
 * 零持久化（设计裁决 §2.2）：门态进程内存——冷启恒关门是铁律的实现方式；持久化会让
 * 「上个会话开过门」变成冷启自动开工，违背「启动→首开零发起」。
 *
 * 实例挂 [GroupHarvester.discoveryGate]（收割/解析/直荐三管线的构造持有者，零签名
 * 改动）；EntityResolver/SearchGroupRecommender 经 gateway.groupHarvester 既有通道
 * 取用，FeedViewModel 同通道（L147 R211 监听既走此路）。
 */
class DiscoveryLazyGate(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        /** 授权节流窗（毫秒）：任务书「如 ≥30 分钟」取 30——唯一出处（单源常量） */
        const val WINDOW_MS: Long = 30 * 60_000L

        private const val TAG = "R377Gate"
    }

    /** 最近一次授权时刻（毫秒；0 = 本进程从未授权——冷启恒关门的实现基础） */
    @Volatile
    private var lastGrantMs = 0L

    /** 本进程授权计数（开门行「首次/第N次」素材） */
    @Volatile
    private var grantCount = 0

    /** 候门探针去重账：worker 键 → 本关门段是否已记候门行（开门即清，新关门段重来） */
    private val holdLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 用户点开发现页（落定页）的授权判定 + lastGrant 更新：
     * 首开/距上次授权 ≥[WINDOW_MS] → true（同时刷新 lastGrant）；窗内再开 → false。
     * 开门行（I 级，r377_notes §2.5 探针口径）由本口单源记录——授权源唯一，
     * 下拉刷新/换一批/骰子/双击恒许路径不经本口。
     */
    @Synchronized
    fun onUserOpen(): Boolean {
        val now = clock()
        val granted = lastGrantMs == 0L || now - lastGrantMs >= WINDOW_MS
        if (granted) {
            lastGrantMs = now
            grantCount++
            holdLogged.clear() // 门开：候门去重账清零，下一段关门各 worker 重新各记一行
            Log.i(TAG, "开门（${if (grantCount == 1) "首次" else "第${grantCount}次"}）→授权一轮（预算内收割+发现刷新）")
        }
        return granted
    }

    /**
     * 门内会话判定（常驻循环干活条件）：now − lastGrant < [WINDOW_MS]（lastGrant=0
     * 恒 false=冷启恒关门）。用户停发现页超窗（窗过期）→ 常驻循环停——诚实陈旧
     * 优于烧账号；再交互（再开授权/显式刷新恒许）即恢复。
     */
    fun sessionActive(): Boolean {
        val last = lastGrantMs
        return last != 0L && clock() - last < WINDOW_MS
    }

    /**
     * 候门探针（D 级，§2.5 口径）：常驻循环被门拦时上报「〈worker〉候门（未开门）」
     * ——一关门段每 worker 至多一行（防 15min/5min/10min 巡检节奏刷屏）；门开即清。
     * [workerLabel] ∈ 收割轮/解析轮/直荐轮（与常驻循环一一对应）。
     */
    fun noteGateHold(workerLabel: String) {
        if (sessionActive()) {
            holdLogged.remove(workerLabel)
            return
        }
        if (holdLogged.putIfAbsent(workerLabel, true) == null) {
            Log.d(TAG, "${workerLabel}候门（未开门）")
        }
    }
}
