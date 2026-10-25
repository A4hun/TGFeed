package dev.tgfeed.core.tastemap

import java.io.File

/**
 * R248 星名点亮账本（**纯 java.io，零 Android 依赖，JVM 单测可跑**——TasteVoyageLog
 * 同款纪律）。
 *
 * 用户原创机制「名字可以搞成探索形式的点亮机制」的持久件：**飞近点亮**（井深比过
 * 阈值=「发现」时刻，星名浮现）的频道集合——**永久点亮**（下次打开星图名字仍在，
 * 集合单调增长，永不回退）。
 *
 * 口径（诚实标注，KDoc 成文）：
 *  - 本账本只记「飞近点亮」的新发现；开图即已知的星（已看过 ∨ 已订阅 ∨ 特殊天体
 *    =用户自己的会话）**不进账本**（动态判定——看过/订阅态随装配数据走，落盘会
 *    陈旧；已知并集在装配侧现算）；
 *  - 文件=任务书口径 `discovered_stars.json`，**galaxy 自持目录**
 *    （filesDir/galaxy/——R235 航行账本先例：星图自持文件不进 LocalStore 共享层，
 *    LocalStore 零改动=ARCHITECTURE §二共享层零触碰）；
 *  - 格式=JSON 数组，**两级元素**（R250 发现日期拓展，向后兼容）：
 *    `{"id":123,"ts":1699...}`（R250 起新发现记**发现时刻毫秒**）与裸数字
 *    `123`（R248 存量=日期未知）；手写解析（org.json 在 JVM 单测不可用，纯
 *    语法足够且真实 JSON）；原子写（临时文件+rename——半写不损坏旧账）；
 *  - 损坏/缺失/半写=空集诚实缺省（发现进度回零可容忍——飞行再点亮即回，绝不抛）。
 *
 * R250 拓展纪律（信息卡「你于 X 发现」的单一数据源）：
 *  - 单一解析器单尺：[parse] = [parseDated] 的 keys（无第二份解析逻辑）；
 *  - 旧 id 入账**保留原 ts**（幂等合并不改史——[addAll] putIfAbsent 语义）；
 *  - ts=0=存量无日期——「你于 X 发现」不冒充日期（UI 侧 ts>0 才出）。
 */
object TasteDiscoveryLog {

    /** 账本文件名（ui 侧 File(filesDir/galaxy, 此名)） */
    const val FILE_NAME = "discovered_stars.json"

    /** 单条目上限护栏（在图恒星 ≤600，留余量防脏数据膨胀——超出截断保序） */
    private const val MAX_IDS = 4096

    /** 读账本（文件缺失/损坏/半写=空集诚实缺省，绝不抛） */
    fun load(file: File): Set<Long> = loadDated(file).keys

    /**
     * R250 带日期读账本：id → 发现时刻毫秒（0=旧账存量——日期未知不冒充）。
     * 单一解析器单尺：[parseDated] 的 keys 即 [parse]（对象解析出 id——R248
     * 纯 id 断言零改动全绿）。
     */
    fun loadDated(file: File): Map<Long, Long> {
        if (!file.isFile) return emptyMap()
        return runCatching { parseDated(file.readText()) }.getOrDefault(emptyMap())
    }

    /** 原子写（临时文件+rename；失败静默——丢一次可容忍，绝不抛）。
     *  父目录自建（**类问题修复**：filesDir/galaxy/ 全仓无任何创建方——R235 航行
     *  账本同病同修（见其 save），「写前不建父目录=静默丢账」一类）。
     *  R250：ts>0 写对象形式（记发现时刻）；ts=0 写裸数字（存量格式不变——
     *  混合数组=同一文件的向后兼容两级）。 */
    private fun save(file: File, entries: Map<Long, Long>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                entries.entries.joinToString(prefix = "[", postfix = "]", separator = ",") { e ->
                    if (e.value > 0L) "{\"id\":${e.key},\"ts\":${e.value}}" else "${e.key}"
                }
            )
            if (!tmp.renameTo(file)) {
                // rename 跨设备失败兜底（同目录 rename 正常直达；直写保底）
                file.writeText(
                    entries.entries.joinToString(prefix = "[", postfix = "]", separator = ",") { e ->
                        if (e.value > 0L) "{\"id\":${e.key},\"ts\":${e.value}}" else "${e.key}"
                    }
                )
                tmp.delete()
            }
        }
    }

    /**
     * 批量点亮（发现时刻调用：一拍多颗一起入账——单次 IO 写整账 ≤4KB）。
     * 返回合并后的新集合（调用方可直接用）；已在账=幂等无副作用（**旧 id 保留
     * 原 ts 不改史**）。R250：新入账记发现时刻 [nowMs]（默认系统时钟——单测可
     * 注入固定时刻）。
     */
    fun addAll(file: File, chatIds: Collection<Long>, nowMs: Long = System.currentTimeMillis()): Set<Long> {
        if (chatIds.isEmpty()) return load(file)
        val next = LinkedHashMap(loadDated(file))
        for (id in chatIds) {
            if (id != 0L) next.putIfAbsent(id, nowMs)
        }
        val capped: Map<Long, Long> = if (next.size > MAX_IDS) {
            LinkedHashMap<Long, Long>(MAX_IDS * 2).also { m ->
                for ((k, v) in next.entries.take(MAX_IDS)) m[k] = v
            }
        } else {
            next
        }
        save(file, capped)
        return LinkedHashSet(capped.keys)
    }

    /** 单颗点亮（便捷口——内部走 [addAll] 同一条写链，无第二份写逻辑） */
    fun add(file: File, chatId: Long): Set<Long> = addAll(file, listOf(chatId))

    /** 手写 JSON 数字数组解析（纯 JVM——org.json 单测不可用；容错：非数字段跳过）。
     *  R250：= [parseDated] 的 keys（单一解析器单尺）。 */
    internal fun parse(text: String): Set<Long> = parseDated(text).keys

    /**
     * 手写两级解析（R250 发现日期）：顶层逗号分片（花括号深度+字符串感知——对象
     * 内含逗号不劈）；`{"id":x,"ts":y}` → (x,y)；裸数字 → (x, 0=日期未知)；0 与
     * 非数字/缺 id 段跳过。同 id 双现（裸+带日期）保带日期者（日期是证据，裸是
     * 缺省——首见即史）。
     */
    internal fun parseDated(text: String): Map<Long, Long> {
        val t = text.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) return emptyMap()
        val body = t.substring(1, t.length - 1).trim()
        if (body.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, Long>()
        for (part in splitTopLevel(body)) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (p.startsWith("{")) {
                val id = longFieldOf(p, "id") ?: continue
                if (id == 0L) continue
                val ts = longFieldOf(p, "ts") ?: 0L
                val prev = out[id]
                if (prev == null || (prev == 0L && ts > 0L)) out[id] = ts
            } else {
                val v = p.toLongOrNull() ?: continue
                if (v != 0L && v !in out) out[v] = 0L
            }
        }
        return out
    }

    /** 顶层逗号分片（{} 深度+字符串感知——对象内逗号不劈、字符串内逗号不劈） */
    private fun splitTopLevel(body: String): List<String> {
        val out = ArrayList<String>(16)
        val sb = StringBuilder(body.length)
        var depth = 0
        var inStr = false
        for (ch in body) {
            when {
                inStr -> {
                    sb.append(ch)
                    if (ch == '"') inStr = false
                }
                ch == '"' -> {
                    inStr = true
                    sb.append(ch)
                }
                ch == '{' || ch == '[' -> {
                    depth++
                    sb.append(ch)
                }
                ch == '}' || ch == ']' -> {
                    if (depth > 0) depth--
                    sb.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 对象内取整数字段（`"id":123`；缺失/非整数=null） */
    private fun longFieldOf(obj: String, field: String): Long? =
        Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull()
}
