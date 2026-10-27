package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 网页泳道标题解析器：Telegram 官方公开预览页 `https://t.me/s/<用户名>` 无需登录，
 * HTML 头部 `<meta property="og:title" content="…">` 即频道名。该接口限流按 IP 不按账号，
 * 与 TDLib 的 SearchPublicChat 账号桶完全隔离——账号级 429 封死期（实测 retry after 上万秒）
 * 这是答案区"裸 @用户名"行补真频道名的唯一实时泳道。
 *
 * HTTP 走 java.net.HttpURLConnection（app 无 OkHttp 依赖，不为此引入新依赖）；
 * 代理复用 app 现有 SOCKS5 配置（TdLibGateway AddProxy 同一来源），未配置时直连。
 *
 * 自保：
 *  - 同用户名 in-flight 单飞去重（并发调用搭车等待同一请求）；
 *  - 全批并发上限 [CONCURRENCY]（低并发，防触发 IP 限流）；
 *  - 结果进程内缓存（只存成功；失败下轮可重试）；
 *  - 响应 429 读 Retry-After 头，泳道静默至该时刻（进程内），
 *    静默期所有调用立即返回当前已收集结果（零请求）。
 */
class WebPreviewResolver(
    private val proxyHost: String?,
    private val proxyPort: Int,
) {

    /**
     * 网页泳道解析产物（R48 成员数扩展）：频道名 + 成员数。
     * [members] 来源 = 预览页 og:description 文案里的"N members/subscribers"段
     * （英文逗号分隔数字已去逗号），解析不出 = 0（未知）。
     */
    data class WebChannelInfo(
        val title: String,
        val members: Int = 0,
    )

    companion object {
        private const val TAG = "WebPreviewResolver"

        /** 连接/读取超时（毫秒） */
        private const val TIMEOUT_MS = 8_000

        /** 全批并发上限 */
        private const val CONCURRENCY = 2

        /** 单批最多尝试的用户名数（与答案区标题链的批量口径同量级，防一次长尾） */
        private const val MAX_PER_BATCH = 12

        /** 响应体最多读取字节（og:title 在 <head> 内，截断不影响解析；防异常大页烧内存） */
        private const val MAX_BODY_BYTES = 512 * 1024

        /** 429 未带 Retry-After 时的默认静默秒数 */
        private const val DEFAULT_MUTE_SEC = 60

        /**
         * 成功缓存与死信表的条数上限（R428）：原本两张无界表随会话只增不减
         * （R165 灾变链燃料族）；512 条覆盖一轮会话的预览解析窗口，成功缓存
         * 淘汰后重拉一次、死信淘汰后重探一次（404 复验安全，仅多一请求），均无感。
         */
        private const val CACHE_MAX = 512

        /**
         * og:description 成员数文案（R48）："12,345 members"/"6.7K subscribers"形态，
         * 英文逗号分隔数字去逗号；k/K=×1000、M=×1,000,000 取整（预览页体量缩写口径）。
         */
        private val OG_MEMBERS_REGEX = Regex(
            """(\d[\d,]*(?:\.\d+)?)\s*([kKmM])?\s*(?:members|subscribers)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** 成功结果进程内缓存：小写用户名 → 频道名+成员数（只存成功）；R428 收编为有界（[CACHE_MAX]） */
    private val cache = BoundedLruCache<String, WebChannelInfo>(CACHE_MAX)

    /**
     * 永久死信：预览页实证用户名不存在（页面无 og:title / 含 tgme_page_extra 提示），
     * 本进程不再重试；R428 收编为有界（[CACHE_MAX]）——被淘汰的最久未探死信会
     * 被重探一次，404 复验安全（仅多一请求）。值恒为 Unit，仅当集合用。
     */
    private val dead = BoundedLruCache<String, Unit>(CACHE_MAX)

    /** 同用户名 in-flight 单飞表：并发调用搭车等待同一请求，绝不重复发 */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<WebChannelInfo?>>()

    /** 泳道静默截止时刻（429 Retry-After 驱动）；静默期一切调用立即返回已收集结果，零请求 */
    @Volatile
    private var mutedUntilMs = 0L

    /**
     * 批量解析频道名+成员数（R48）：返回 小写用户名 → [WebChannelInfo]（只含成功项）。
     * 命中缓存/死信/静默期的用户名零请求；静默期立即返回当前已收集结果。
     */
    suspend fun resolveTitles(usernames: List<String>): Map<String, WebChannelInfo> {
        val names = usernames.map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() && dead[it] == null }
            .distinct().take(MAX_PER_BATCH)
        if (names.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, WebChannelInfo>()
        for (n in names) cache[n]?.let { out[n] = it }
        val todo = names.filter { !out.containsKey(it) }
        if (todo.isEmpty()) return out
        if (System.currentTimeMillis() < mutedUntilMs) {
            Log.d(TAG, "webTitles: 泳道静默中（429 限流未到期），直接返回已收集 ${out.size} 条")
            return out
        }
        coroutineScope {
            val sem = Semaphore(CONCURRENCY)
            todo.map { name ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        // 批内某请求触发 429 后，后续用户名零请求直接落空（静默即刻生效）
                        if (System.currentTimeMillis() < mutedUntilMs) return@withPermit
                        val info = fetchSingleFlight(name)
                        if (info != null) {
                            cache[name] = info
                            out[name] = info
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "webTitles: 请求 ${todo.size} 解析 ${out.size}")
        return out
    }

    /** 同用户名单飞：已在飞则搭车等待其结果，否则自己成为发起者 */
    private suspend fun fetchSingleFlight(name: String): WebChannelInfo? {
        val mine = CompletableDeferred<WebChannelInfo?>()
        val prev = inFlight.putIfAbsent(name, mine)
        if (prev != null) return prev.await()
        return try {
            val r = runCatching { fetchOne(name) }
                .onFailure { Log.d(TAG, "webTitles: @$name transient（${it.javaClass.simpleName}）") }
                .getOrNull()
            mine.complete(r)
            r
        } finally {
            inFlight.remove(name, mine)
            if (!mine.isCompleted) mine.complete(null) // 发起者异常退出时唤醒搭车者，防吊死
        }
    }

    /** 单用户名阻塞式抓取（调用方已在 Dispatchers.IO）：成功返回频道名+成员数，失败分类记日志返回 null */
    private fun fetchOne(name: String): WebChannelInfo? {
        if (System.currentTimeMillis() < mutedUntilMs) return null // 静默期零请求
        val url = URL("https://t.me/s/$name")
        val proxy = proxyHost?.takeIf { it.isNotBlank() }
            ?.let { java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(it, proxyPort)) }
        val conn = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            // 伪装普通桌面浏览器：t.me 对裸 Java UA 偶尔回异常页
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            when (val code = conn.responseCode) {
                429 -> {
                    val ra = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: DEFAULT_MUTE_SEC
                    mutedUntilMs = System.currentTimeMillis() + ra * 1000L
                    Log.w(TAG, "webTitles: @$name flood（HTTP 429，Retry-After=${ra}s），泳道静默 ${ra}s")
                    return null
                }
                404 -> {
                    dead[name] = Unit
                    Log.d(TAG, "webTitles: @$name notFound（HTTP 404），进死信")
                    return null
                }
                !in 200..299 -> {
                    Log.d(TAG, "webTitles: @$name transient（HTTP $code）")
                    return null
                }
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < MAX_BODY_BYTES) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            val title = extractOgTitle(body)
            // R48：og:description 里的成员数文案随标题一并解析回填（答案区排序/行尾小字数据源）
            if (title != null) return WebChannelInfo(title, parseOgMembers(body))
            // 用户名不存在判定：页面无 og:title 或含 tgme_page_extra 提示文案 → 永久死信。
            // 前提：响应确实是 Telegram 页面（防代理劫持页/中转异常页把正常用户名误判进死信）
            if (body.contains("tgme", ignoreCase = true) || body.contains("telegram", ignoreCase = true)) {
                dead[name] = Unit
                Log.d(TAG, "webTitles: @$name notFound（页面无 og:title），进死信")
            } else {
                Log.d(TAG, "webTitles: @$name transient（响应非 TG 页面，疑似链路中转异常）")
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** 从 HTML 提取 <meta property="og:title" content="…">（属性顺序/引号形态不挑），并反转义 HTML 实体 */
    private fun extractOgTitle(html: String): String? = extractOgMeta(html, "og:title")

    /** 从 HTML 提取指定 property 的 <meta> content（属性顺序/引号形态不挑），并反转义 HTML 实体 */
    private fun extractOgMeta(html: String, property: String): String? {
        for (m in Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            val prop = Regex("property\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag) ?: continue
            if (!prop.groupValues[2].equals(property, ignoreCase = true)) continue
            val content = Regex("content\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag) ?: continue
            return unescapeHtml(content.groupValues[2]).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * og:description 成员数解析（R48）："12,345 members"/"6.7K subscribers"文案 → 个数
     * （逗号分隔去逗号，k/K=×1000、M=×1,000,000 取整）；无该文案/解析失败返回 0（未知）。
     */
    private fun parseOgMembers(html: String): Int {
        val desc = extractOgMeta(html, "og:description") ?: return 0
        val m = OG_MEMBERS_REGEX.find(desc) ?: return 0
        val base = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        val factor = when (m.groupValues[2].lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }
        return (base * factor).toInt()
    }

    /** HTML 实体反转义：数字实体（&#39; &#x27; 等）+ 常用命名实体；&amp; 必须最后处理防二次反转义 */
    private fun unescapeHtml(s: String): String {
        var out = Regex("&#(x?[0-9a-fA-F]+);").replace(s) { m ->
            val num = m.groupValues[1]
            val code = if (num.startsWith("x", ignoreCase = true)) {
                num.substring(1).toIntOrNull(16)
            } else {
                num.toIntOrNull()
            }
            code?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        out = out.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        return out.replace("&amp;", "&")
    }
}
