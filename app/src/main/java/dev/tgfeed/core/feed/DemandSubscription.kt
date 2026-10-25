package dev.tgfeed.core.feed

import android.content.Context
import android.util.Log
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.SearchTextKit
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 金矿 P4-B·需求订阅（"搜不到的东西出现了就喊我"）。
 *
 * 闭环：搜索页一键订阅当前词（SearchScreen「搜不到？订阅它，出现了喊你」——R148 起
 * 空态/有结果两态都露出入口，修"重用户（关注大量频道、搜索几乎从不落空）永远够不
 * 到订阅入口"的可达性缺口）→ 词表落 AppPrefs.demandSubscriptionsJson（设置页
 * "需求订阅"卡亦可直接加词/删词）→ 收割管道（GroupHarvester.recordLinks）经
 * GoldmineHooks 统一钩子派发（配对关键词, 原始链接）→ 本对象做订阅词匹配，
 * 命中即上交 [MergedNotify]（R148 起到货与追更合并为同一渠道同一条汇总通知，
 * 替代旧"每词一条"的独立通知；点通知经 ACTION_VIEW + t.me 链接深链直达首条内容，
 * MainActivity.parseDeepLink 覆盖用户名/私有 c/ /消息级全形态，onCreate 冷启
 * 与 singleTop onNewIntent 热启两路都接）。
 *
 * 挂接时机：AppPrefs.init 调 [attach]——AppPrefs 在 App.onCreate 即被 AppContainer
 * 构造（App.kt），进程启动早期监听器就挂好，用户无需先打开搜索/设置页；
 * 且收割钩子能触发时 GroupHarvester 已存在（其构造参数就有 AppPrefs），
 * attach 必然先于首次派发。幂等（AtomicBoolean 闸），AppPrefs 多处重复构造无副作用。
 * attach 同时顺带挂接 [MergedNotify]（建/更渠道、删旧 demand_arrivals 渠道、清抽屉
 * 旧分体通知残骸——R148 通知合并迁移在进程启动早期完成，不等首条通知）。
 *
 * 匹配口径（与答案库同用 SearchTextKit.fold 归一：繁→简/全角→半角/小写）：
 *  - fold 后精确相等必中（订阅词 = 群友搜索词的标准命中）；
 *  - 短边 ≥2 字符时双向 contains 也算中（订阅"三体"能吃到群友搜的"三体小说"）；
 *    单字词只允许精确命中——单字 contains 误报爆炸，宁可漏报不可轰炸。
 *
 * 链接过滤口径：钩子派发的是 recordLinks **过滤前**的原始链接（P4-A 图谱首见判定
 * 需要未入库现场），本对象自行做与 P4-A 同款的轻过滤（广告参数链/邀请链丢弃）。
 * 已知残余风险：AdChannelClassifier 实例归 GroupHarvester 私有不可复用，被它独家
 * 拦截的广告频道链接可能漏进一次通知——去重表保证同一链接最多误喊一次，可接受。
 *
 * 去重：同一（fold订阅词, 链接）只通知一次——收割每轮都会给老 (词,链接) 累 hits
 * 反复触发钩子，不去重就是每 15 分钟轰炸。去重表落 prefs（JSON 数组保序，
 * 超 [MAX_NOTIFIED] 掐最老；淘汰代价只是偶发重复通知）。**通知真正发出才记去重**：
 * 未授予通知权限/总开关关闭时不记，授权/开启后下轮收割还能补报
 * （[MergedNotify.onDemandArrival] 返回 false 即回退去重，契约同旧版）。
 * 同词同轮多条新链接合并成一批上交（汇总明细里记"N 条"）；通知本体由 MergedNotify
 * 单条覆盖更新（R148 前本对象自建 demand_arrivals 渠道、按词表槽位派生通知 id
 * 70000+slot（R110 修哈希互顶）——已整体废弃，渠道随迁移删除）。
 */
object DemandSubscription {

    private const val TAG = "DemandSubscription"

    /** 订阅词数硬顶（防无限膨胀；满员 add 返回 false，UI 提示去设置页删除） */
    private const val MAX_SUBSCRIPTIONS = 100

    /** 已通知去重表硬顶（"fold词|链接" 对数；超出掐最老） */
    private const val MAX_NOTIFIED = 1_000

    /** 订阅词最长字符（与收割侧 sanitizeKeyword 的 MAX_KEYWORD_CHARS 同口径） */
    private const val MAX_WORD_CHARS = 64

    /** 应用上下文（attach 注入；null = 未挂接，全程静默空转） */
    @Volatile
    private var appContext: Context? = null

    /** GoldmineHooks 监听器注册闸（幂等；注销函数不保留——本特性生命周期 = 进程） */
    private val attached = AtomicBoolean(false)

    /**
     * prefs 懒构造：attach 时绝不触发（AppPrefs.init 会调 attach，attach 里若回头
     * 构造 AppPrefs 就是相互构造打环）；SharedPreferences 本身是全局缓存单例，
     * 每次现构造 AppPrefs 只是薄包装，开销可忽略。
     */
    private val prefs: AppPrefs? get() = appContext?.let { AppPrefs(it) }

    /**
     * 挂接（幂等）：存 applicationContext + 向 GoldmineHooks 注册收割事件监听器；
     * 同时挂接 [MergedNotify]（R148 合并通知中枢——建/更渠道、删旧到货渠道、
     * 清扫抽屉旧分体通知残骸，迁移随进程启动即完成）。
     * 由 AppPrefs.init 调用（进程启动早期必经点）；UI 层亦可防御性重复调用。
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
        runCatching { MergedNotify.attach(context) }
        if (!attached.compareAndSet(false, true)) return
        GoldmineHooks.registerListener { keyword, links -> onHarvested(keyword, links) }
    }

    // ---------------- 订阅词表 CRUD（UI 线程调用；与收割线程靠对象锁隔离） ----------------

    /** 当前订阅词列表（保持订阅先后序；损坏按空表重建，绝不崩） */
    @Synchronized
    fun list(): List<String> = parseWords(prefs?.demandSubscriptionsJson.orEmpty())

    /** 是否已订阅（fold 归一判定：简繁/全角/大小写不敏感） */
    @Synchronized
    fun contains(word: String): Boolean {
        val folded = foldWord(word) ?: return false
        return list().any { SearchTextKit.fold(it).trim() == folded }
    }

    /**
     * 订阅一个词：归一化（trim/连续空白折叠）+ fold 去重 + 数量硬顶。
     * 返回 false = 词非法或订阅数已满（UI 据以 Toast）；已订阅按成功处理（幂等）。
     */
    @Synchronized
    fun add(word: String): Boolean {
        val normalized = normalizeWord(word) ?: return false
        val folded = SearchTextKit.fold(normalized).trim()
        if (folded.isEmpty()) return false
        val cur = list().toMutableList()
        if (cur.any { SearchTextKit.fold(it).trim() == folded }) return true
        if (cur.size >= MAX_SUBSCRIPTIONS) return false
        cur += normalized
        persistWords(cur)
        return true
    }

    /**
     * 取消订阅（fold 归一匹配）；同时清掉该词的去重记录——
     * 重新订阅后老链接再次到货时能重新提醒（订阅关系归零重来，符合用户心智）。
     */
    @Synchronized
    fun remove(word: String) {
        val folded = foldWord(word) ?: return
        val cur = list()
        val kept = cur.filterNot { SearchTextKit.fold(it).trim() == folded }
        if (kept.size != cur.size) persistWords(kept)
        val notified = loadNotified()
        if (notified.removeAll { it.substringBefore('|') == folded }) persistNotified(notified)
    }

    // ---------------- 到货匹配（GoldmineHooks 派发，运行在收割协程 IO 线程） ----------------

    @Synchronized
    private fun onHarvested(keyword: String?, links: List<String>) {
        if (keyword == null || links.isEmpty()) return // 无配对词（bot 主动发帖）无法匹配订阅词
        val p = prefs ?: return
        if (!p.demandNotifyEnabled) return // 总开关关闭：静默跳过（不记去重，开启后下轮可补报）
        val foldedKw = SearchTextKit.fold(keyword).trim()
        if (foldedKw.isEmpty()) return
        val subs = list()
        if (subs.isEmpty()) {
            Log.d(TAG, "到货判定：订阅词表为空，本轮收割命中词「$keyword」无从匹配（静默跳过）")
            return
        }
        // 钩子派发的是过滤前原始链接：先过与 recordLinks 同口径的轻过滤
        // （广告参数链/邀请链丢弃；AdChannelClassifier 黑名单频道属已知残余风险，见头注释）
        val clean = links.filter { link ->
            if (AdBlacklist.isSpamLink(link)) return@filter false
            val path = link.removePrefix("https://t.me/")
            !path.startsWith("+") && !path.startsWith("joinchat", ignoreCase = true)
        }
        if (clean.isEmpty()) return
        val notified = loadNotified()
        var dirty = false
        for (sub in subs) {
            val foldedSub = SearchTextKit.fold(sub).trim()
            if (foldedSub.isEmpty()) continue
            if (!matches(foldedSub, foldedKw)) continue
            // 该订阅词的新链接 = 本次过闸链接里还没通知过的（LinkedHashSet.add=false 即已见过）
            val fresh = clean.filter { notified.add("$foldedSub|$it") }
            if (fresh.isEmpty()) continue
            dirty = true
            // R148：上交合并通知中枢（与追更共用一条汇总通知）；未真正发出则回退去重下轮补报
            if (!MergedNotify.onDemandArrival(sub, fresh)) {
                notified.removeAll(fresh.mapTo(HashSet()) { "$foldedSub|$it" })
            }
        }
        if (dirty) persistNotified(notified)
    }

    /** 订阅词 vs 收割关键词：fold 后精确相等；或短边 ≥2 字符时双向 contains（单字只许精确） */
    private fun matches(foldedSub: String, foldedKw: String): Boolean {
        if (foldedSub == foldedKw) return true
        val shorter = if (foldedSub.length <= foldedKw.length) foldedSub else foldedKw
        if (shorter.length < 2) return false
        return foldedKw.contains(foldedSub) || foldedSub.contains(foldedKw)
    }

    // ---------------- 持久化（AppPrefs 两个 JSON 串；损坏按空表重建，绝不崩） ----------------

    private fun parseWords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "订阅词表解析失败（按空表重建）: ${it.message}")
            emptyList()
        }
    }

    private fun persistWords(words: List<String>) {
        runCatching {
            val arr = JSONArray()
            words.forEach { arr.put(it) }
            prefs?.demandSubscriptionsJson = arr.toString()
        }.onFailure { Log.w(TAG, "订阅词表落盘失败（下轮重写）: ${it.message}") }
    }

    /** 去重表读取（LinkedHashSet 保插入序：掐最老用；fold词|链接 形态） */
    private fun loadNotified(): LinkedHashSet<String> {
        val raw = prefs?.demandNotifiedJson.orEmpty()
        if (raw.isBlank()) return LinkedHashSet()
        return runCatching {
            val arr = JSONArray(raw)
            val out = LinkedHashSet<String>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
            }
            out
        }.getOrElse {
            Log.w(TAG, "到货去重表解析失败（按空表重建，代价=偶发重复通知）: ${it.message}")
            LinkedHashSet()
        }
    }

    private fun persistNotified(set: LinkedHashSet<String>) {
        runCatching {
            // 有界 LRU：超硬顶掐最老（LinkedHashSet 迭代序 = 插入序）
            val trimmed = if (set.size > MAX_NOTIFIED) set.drop(set.size - MAX_NOTIFIED) else set.toList()
            val arr = JSONArray()
            trimmed.forEach { arr.put(it) }
            prefs?.demandNotifiedJson = arr.toString()
        }.onFailure { Log.w(TAG, "到货去重表落盘失败: ${it.message}") }
    }

    /** 订阅词归一化：trim + 连续空白折叠成单空格；非法（空/超长/带链接）返回 null */
    private fun normalizeWord(word: String): String? {
        val w = word.trim().replace(Regex("\\s+"), " ")
        if (w.isEmpty() || w.length > MAX_WORD_CHARS) return null
        if (w.contains("t.me/", ignoreCase = true)) return null
        return w
    }

    /** 归一化 + fold 的复合（contains/remove 用；非法词返回 null） */
    private fun foldWord(word: String): String? {
        val w = normalizeWord(word) ?: return null
        return SearchTextKit.fold(w).trim().takeIf { it.isNotEmpty() }
    }
}
