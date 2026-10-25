package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R364 TDLib 裸发送超时兜底二期 wiring 源码锚（R363WiringSourceTest 同款
 * user.dir 源定位模式，锚从广场族扩到本批两件）。类=「suspend 的 TDLib 请求无超时
 * 兜底」——TDLib 隐性限流（请求挂起无响应的环境病）时协程永挂：
 *  - **裸 send 绝迹①**：GatewayBotSearch.kt 搜索族 12 处 client.send（含 Guarded
 *    发送口本体 searchPublicChatGuardedEx/searchChatsOnServerGuarded——有熔断/账本
 *    但原无超时，正是 R361 永挂真身）全裹 withTdTimeout；GatewayDownload.kt 下载族
 *    19 处裸 send 同裹（翻译主请求的既有 20s withTimeout 不在本批口径，保持原样）；
 *  - **包装单源②**：withTdTimeout 唯一定义在 GatewaySendReq.kt，二期两文件零自建
 *    超时包装（不许各写一份）；
 *  - **时长单口③**：默认档=PLAZA_TD_TIMEOUT_MS 12s（withTdTimeout 默认值，搜索族
 *    真机腿锚 12s 落定）；存储治理重扫描加档 STORAGE_TD_TIMEOUT_MS=30s 唯一出处，
 *    二期两文件零裸时长字面量；
 *  - **诚实失败④**：超时按各函数既有型别回落（Guarded 口→Transient/null、下载族
 *    →false/-1f/null/error 上抛，熔断/账本/配额计费语义零改——不报成功、不上报
 *    429、已记预算不退），KDoc/注释记隐性限流判词与上限闸口径。
 */
class R364WiringSourceTest {

    private val mainSourceRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = File(dir, "src/main/java")
                if (candidate.isDirectory) return candidate
                val nested = File(dir, "app/src/main/java")
                if (nested.isDirectory) return nested
                dir = dir.parentFile ?: return@repeat
            }
            error("找不到主源码根（user.dir=${System.getProperty("user.dir")}）")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun botSearch(): String = src("dev/tgfeed/core/td/GatewayBotSearch.kt")

    private fun download(): String = src("dev/tgfeed/core/td/GatewayDownload.kt")

    private fun gatewaySendReq(): String = src("dev/tgfeed/core/td/GatewaySendReq.kt")

    /** 取 impl 函数体切片（声明起至下一 internal suspend fun 或文件尾） */
    private fun implBody(fileText: String, fnName: String): String {
        val start = fileText.indexOf("fun TdLibGateway.$fnName")
        assertTrue("$fnName 应在场", start >= 0)
        val rest = fileText.substring(start)
        val next = rest.indexOf("internal suspend fun", 10)
        return if (next > 0) rest.substring(0, next) else rest
    }

    /** 每个 client.send 前窗 240 字符内必须裹指定包装之一（裹尸布式锚，R363 同口径） */
    private fun assertAllSendsWrapped(fileText: String, fileLabel: String, vararg wrappers: String) {
        val sendIdxs = Regex("client\\.send\\(").findAll(fileText).map { it.range.first }.toList()
        for (idx in sendIdxs) {
            val window = fileText.substring(maxOf(0, idx - 240), idx)
            assertTrue(
                "$fileLabel 的 client.send 必须裹在超时包装内（索引 $idx）",
                wrappers.any { window.contains(it) },
            )
        }
    }

    // ==================== ① 裸 send 绝迹：搜索族 12 处全走统一超时包装 ====================

    @Test
    fun `裸send绝迹_搜索族十二处send全裹包装`() {
        val bot = botSearch()
        val sendCount = Regex("client\\.send\\(").findAll(bot).count()
        assertEquals("GatewayBotSearch 现有 12 处 client.send（R363 普查口径）", 12, sendCount)
        assertAllSendsWrapped(bot, "GatewayBotSearch", "withTdTimeout(")
        // 关键函数逐个点名（Guarded 发送口本体=永挂真身，必须在列）
        val fns = listOf(
            "queryBotOnce", "searchPublicChatGuardedEx", "searchChatsOnServerGuarded",
            "clickBotCallback", "harvestHotWordBoardImpl", "awaitBoardMessage",
            "clickBoardButton", "resolveBotChatViaServerSearch",
        )
        for (fn in fns) {
            assertTrue(
                "$fn 必须走 withTdTimeout 统一超时包装（隐性限流挂起不再永挂）",
                implBody(bot, fn).contains("withTdTimeout("),
            )
        }
        // Guarded 口本体的真发送点：超时按 Transient 诚实回落（熔断/账本语义零改）
        assertTrue(
            "searchPublicChatGuardedEx 超时须回落 GuardedSearchResult.Transient（瞬态同族）",
            bot.contains("?: return GuardedSearchResult.Transient"),
        )
        assertTrue(
            "searchChatsOnServerGuarded 超时须回落 null（既有失败语义）",
            implBody(bot, "searchChatsOnServerGuarded").contains("?: return null"),
        )
    }

    // ==================== ①b 裸 send 绝迹：下载族 19 处全裹（翻译 20s 既有闸不动） ====================

    @Test
    fun `裸send绝迹_下载族十九处send全裹包装`() {
        val dl = download()
        val sendCount = Regex("client\\.send\\(").findAll(dl).count()
        assertEquals("GatewayDownload 现有 20 处 client.send（19 裸 + 翻译既有 20s 闸）", 20, sendCount)
        // 每个 send 前窗必须裹 withTdTimeout 或翻译既有 withTimeout（零裸发送）
        assertAllSendsWrapped(dl, "GatewayDownload", "withTdTimeout(", "withTimeout(")
        assertEquals(
            "下载族 19 处裸 send 全部裹 withTdTimeout（翻译 1 处既有 withTimeout 不计）",
            19,
            Regex("withTdTimeout\\(").findAll(dl).count(),
        )
        assertEquals(
            "翻译主请求的既有 20s withTimeout 保持唯一（R364 不动该闸）",
            1,
            Regex("(?<!Td)withTimeout\\(").findAll(dl).count(),
        )
        // 关键函数逐个点名
        val fns = listOf(
            "ensurePlayableImpl", "verifyVideoFileIdentity", "ensurePlayableVariantImpl",
            "forceRedownloadVideoImpl", "prioritizeImpl", "downloadProgressImpl",
            "reviveFileIdImpl", "optimizeStorageImpl", "forwardToSavedImpl",
            "translateCaptionImpl", "observeMessageDatabase", "enforceStorageWatermark",
        )
        for (fn in fns) {
            assertTrue(
                "$fn 必须走 withTdTimeout 统一超时包装",
                implBody(dl, fn).contains("withTdTimeout("),
            )
        }
    }

    // ==================== ② 包装单源：withTdTimeout 唯一定义，二期两文件零自建 ====================

    @Test
    fun `包装单源_二期两文件零自建零裸超时`() {
        val sendReq = gatewaySendReq()
        assertTrue(
            "withTdTimeout 须唯一定义在 GatewaySendReq.kt（与 sendReq 同家超时共享件）",
            sendReq.contains("internal suspend fun <T> withTdTimeout("),
        )
        for ((name, body) in mapOf("GatewayBotSearch" to botSearch(), "GatewayDownload" to download())) {
            assertFalse("$name 不得自建超时包装函数（纪律§一：共享件单源）", body.contains("fun withTdTimeout"))
            assertFalse("$name 不得裸用 withTimeoutOrNull（走 withTdTimeout 单源）", body.contains("withTimeoutOrNull("))
        }
        assertFalse(
            "GatewayBotSearch 不得裸用 withTimeout（搜索族一切超时走单源包装）",
            botSearch().contains("withTimeout("),
        )
    }

    // ==================== ③ 时长单口：12s 默认档复用 + 30s 存储重扫描档唯一出处 ====================

    @Test
    fun `时长单口_默认档复用与存储档30s常量唯一`() {
        val sendReq = gatewaySendReq()
        assertTrue(
            "withTdTimeout 默认档须引用 PLAZA_TD_TIMEOUT_MS（12s，搜索族真机腿锚定值）",
            sendReq.contains("timeoutMs: Long = PLAZA_TD_TIMEOUT_MS"),
        )
        assertTrue(
            "存储治理重扫描放宽档 STORAGE_TD_TIMEOUT_MS=30s 应在场（R364 二期加档）",
            sendReq.contains("internal const val STORAGE_TD_TIMEOUT_MS = 30_000L"),
        )
        // 存储族四处重扫描必须引用单口常量（而非另写数字）
        val dl = download()
        assertTrue(
            "enforceStorageWatermark 须引用 STORAGE_TD_TIMEOUT_MS 放宽档",
            implBody(dl, "enforceStorageWatermark").contains("STORAGE_TD_TIMEOUT_MS"),
        )
        assertTrue(
            "optimizeStorageImpl 须引用 STORAGE_TD_TIMEOUT_MS 放宽档",
            implBody(dl, "optimizeStorageImpl").contains("STORAGE_TD_TIMEOUT_MS"),
        )
        // 二期两文件零裸时长字面量（12s/30s 数字只许在常量定义处出现一次）
        for ((name, body) in mapOf("GatewayBotSearch" to botSearch(), "GatewayDownload" to dl)) {
            assertFalse("$name 不得出现裸 12s 字面量", body.contains("12_000"))
            assertFalse("$name 不得出现裸 30s 字面量", body.contains("30_000"))
        }
    }

    // ==================== ④ 诚实失败：超时按既有型别回落+判词在场 ====================

    @Test
    fun `诚实失败_超时回落各函数既有型别且判词在场`() {
        val bot = botSearch()
        val dl = download()
        val sendReq = gatewaySendReq()
        // 超时回落件「TDLib 请求超时（可能限流）」进既有 runCatching 失败路（两文件都在场）
        assertTrue("GatewayBotSearch 超时回落件应在场", bot.contains("TDLib 请求超时（可能限流）"))
        assertTrue("GatewayDownload 超时回落件应在场", dl.contains("TDLib 请求超时（可能限流）"))
        // KDoc/注释判词：隐性限流=请求挂起无响应的环境病（两文件同族判词）
        assertTrue("GatewayBotSearch 须记隐性限流判词", bot.contains("隐性限流"))
        assertTrue("GatewayDownload 须记隐性限流判词", dl.contains("隐性限流"))
        // 包装单源 KDoc：R364 二期收编口径（搜索族含 Guarded 口本体+下载族）
        assertTrue("withTdTimeout KDoc 须记 R364 二期收编判词", sendReq.contains("R364 二期收编"))
        assertTrue("withTdTimeout KDoc 须记零行为回破判词（超时只是上限闸）", sendReq.contains("零行为回破"))
        // Guarded 口配额计费语义零改锚：超时回落在账本记账之后、成功回报之前
        val guarded = implBody(bot, "searchPublicChatGuardedEx")
        val acquireIdx = guarded.indexOf("searchQuotaLedger.tryAcquire")
        val timeoutIdx = guarded.indexOf("?: return GuardedSearchResult.Transient")
        val reportIdx = guarded.indexOf("floodBreaker.reportSuccess(family)")
        assertTrue("Guarded 口须有账本记账/超时回落/成功回报三锚", acquireIdx > 0 && timeoutIdx > 0 && reportIdx > 0)
        assertTrue(
            "超时回落必须位于账本记账之后、成功回报之前（计费语义零改：预算照记不退、不谎报成功）",
            acquireIdx < timeoutIdx && timeoutIdx < reportIdx,
        )
    }
}
