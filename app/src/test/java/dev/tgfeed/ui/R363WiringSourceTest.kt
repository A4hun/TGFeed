package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R363 TDLib 裸发送无超时兜底修复 wiring 源码锚（R361WiringSourceTest 同款
 * user.dir 源定位模式）。病根：广场族裸 client.send 在 TDLib 隐性限流
 * （请求挂起无响应的环境病，R361 官方广场创建卡死实锤）时协程永挂、
 * UI「正在加入广场…」转圈到永远。本批修法收敛成共享件后的四道结构锚：
 *  - **裸 send 绝迹①**：GatewayPlaza.kt 全文件八处 impl（findChat/checkChat/
 *    createChat/join/renameTitle/sendText/fetchRecentTexts/fetchHistoryPage）
 *    每个 client.send 都裹在 withTdTimeout 内，广场族再无裸发送；
 *  - **包装单源②**：withTdTimeout 唯一定义在 GatewaySendReq.kt（与 sendReq
 *    同文件的超时共享件家），广场两文件（GatewayPlaza/PlazaService）零自建
 *    超时包装（withTimeout/withTimeoutOrNull 零出现——不许各写一份）；
 *  - **时长单口③**：PLAZA_TD_TIMEOUT_MS=12s（找/建/加入/发送/拉取同档）与
 *    PLAZA_TD_HISTORY_TIMEOUT_MS=20s（拉历史放宽档）唯一出处，广场族源码零
 *    裸时长字面量；
 *  - **诚实失败④**：超时按各函数既有返回型别回落（findChat→Failed(网络超时)、
 *    其余→false/空列表/null），withTdTimeout 落「TDLib 请求超时（可能限流）」
 *    W 日志（R144 教训：无日志的静默超时=零证据），KDoc 记隐性限流判词。
 */
class R363WiringSourceTest {

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

    private fun gatewayPlaza(): String = src("dev/tgfeed/core/td/GatewayPlaza.kt")

    private fun gatewaySendReq(): String = src("dev/tgfeed/core/td/GatewaySendReq.kt")

    private fun plazaService(): String = src("dev/tgfeed/core/plaza/PlazaService.kt")

    /** 取 impl 函数体切片（声明起至下一 internal suspend fun 或文件尾） */
    private fun implBody(plaza: String, fnName: String): String {
        val start = plaza.indexOf("fun TdLibGateway.$fnName")
        assertTrue("$fnName 应在场", start >= 0)
        val rest = plaza.substring(start)
        val next = rest.indexOf("internal suspend fun", 10)
        return if (next > 0) rest.substring(0, next) else rest
    }

    // ==================== ① 裸 send 绝迹：八处 impl 全走统一超时包装 ====================

    @Test
    fun `裸send绝迹_广场族八处impl每个send都裹在包装内`() {
        val plaza = gatewayPlaza()
        // 八处 impl 逐个点名（任务书全文件逐个过口径）
        val impls = listOf(
            "plazaFindChatImpl", "plazaCheckChatImpl", "plazaCreateChatImpl",
            "plazaJoinImpl", "renameChatTitleImpl", "plazaSendTextImpl",
            "plazaFetchRecentTextsImpl", "plazaFetchHistoryPageImpl",
        )
        for (fn in impls) {
            assertTrue(
                "$fn 必须走 withTdTimeout 统一超时包装（隐性限流挂起不再永挂）",
                implBody(plaza, fn).contains("withTdTimeout("),
            )
        }
        // 每个 client.send 出现点向前 240 字符窗内必须有 withTdTimeout(（裹尸布式锚）
        val sendIdxs = Regex("client\\.send\\(").findAll(plaza).map { it.range.first }.toList()
        assertEquals("GatewayPlaza 现有 8 处 client.send（全部被包装）", 8, sendIdxs.size)
        for (idx in sendIdxs) {
            val window = plaza.substring(maxOf(0, idx - 240), idx)
            assertTrue("client.send 必须裹在 withTdTimeout 内（索引 $idx）", window.contains("withTdTimeout("))
        }
        // PlazaService 侧零裸发送（ensurePlaza 调用链全走 gateway 门面）
        assertFalse("PlazaService 不得出现裸 client.send（一律走 gateway 门面）", plazaService().contains("client.send("))
    }

    // ==================== ② 包装单源：withTdTimeout 唯一定义，广场零自建 ====================

    @Test
    fun `包装单源_withTdTimeout唯一定义于超时共享件文件`() {
        val sendReq = gatewaySendReq()
        assertTrue(
            "withTdTimeout 须定义在 GatewaySendReq.kt（与 sendReq 同家超时共享件）",
            sendReq.contains("internal suspend fun <T> withTdTimeout("),
        )
        // 定义唯一：全广场族两文件零自建包装函数
        assertFalse("GatewayPlaza 不得自建超时包装函数", gatewayPlaza().contains("fun withTdTimeout"))
        assertFalse("PlazaService 不得自建超时包装函数", plazaService().contains("fun withTdTimeout"))
        // 广场两文件零 withTimeout/withTimeoutOrNull 裸用（一切超时一定要走单源包装）
        for ((name, body) in mapOf("GatewayPlaza" to gatewayPlaza(), "PlazaService" to plazaService())) {
            assertFalse("$name 不得裸用 withTimeout（走 withTdTimeout 单源）", body.contains("withTimeout("))
            assertFalse("$name 不得裸用 withTimeoutOrNull（走 withTdTimeout 单源）", body.contains("withTimeoutOrNull("))
        }
    }

    // ==================== ③ 时长单口：12s 默认档+20s 历史档常量唯一出处 ====================

    @Test
    fun `时长单口_12s默认档与20s历史档常量唯一定义`() {
        val sendReq = gatewaySendReq()
        assertTrue(
            "广场族超时时长单源常量 PLAZA_TD_TIMEOUT_MS=12s 应在场（任务书定值）",
            sendReq.contains("internal const val PLAZA_TD_TIMEOUT_MS = 12_000L"),
        )
        assertTrue(
            "拉历史放宽档 PLAZA_TD_HISTORY_TIMEOUT_MS=20s 应在场（任务书定值）",
            sendReq.contains("internal const val PLAZA_TD_HISTORY_TIMEOUT_MS = 20_000L"),
        )
        // 拉历史引用放宽档常量（而非另写数字）
        assertTrue(
            "plazaFetchHistoryPageImpl 须引用 PLAZA_TD_HISTORY_TIMEOUT_MS 放宽档",
            implBody(gatewayPlaza(), "plazaFetchHistoryPageImpl").contains("PLAZA_TD_HISTORY_TIMEOUT_MS"),
        )
        // 广场族源码零裸时长字面量（12s/20s 数字只许在常量定义处出现一次）
        val plaza = gatewayPlaza()
        assertFalse("GatewayPlaza 不得出现裸 12s 字面量", plaza.contains("12_000"))
        assertFalse("GatewayPlaza 不得出现裸 20s 字面量", plaza.contains("20_000"))
    }

    // ==================== ④ 诚实失败：超时按既有型别回落+日志判词+KDoc 判词 ====================

    @Test
    fun `诚实失败_超时回落各函数既有型别且日志判词在场`() {
        val plaza = gatewayPlaza()
        val sendReq = gatewaySendReq()
        // findChat：超时 → Failed(网络超时)——「查不了」语义，绝不进创建分支
        assertTrue(
            "plazaFindChatImpl 超时须回落 PlazaFindResult.Failed(网络超时…)",
            implBody(plaza, "plazaFindChatImpl").contains("PlazaFindResult.Failed(\"网络超时"),
        )
        // 包装日志判词：超时必落「TDLib 请求超时（可能限流）」W 行
        assertTrue(
            "withTdTimeout 须落「TDLib 请求超时（可能限流）」日志（R144 无证据静默超时教训）",
            sendReq.contains("TDLib 请求超时（可能限流）"),
        )
        // KDoc 判词：隐性限流=请求挂起无响应的环境病+本批止血口径
        assertTrue("withTdTimeout KDoc 须记隐性限流判词", sendReq.contains("隐性限流"))
        assertTrue("withTdTimeout KDoc 须记「请求挂起无响应」环境病判词", sendReq.contains("请求挂起无响应"))
        assertTrue("withTdTimeout KDoc 须记零行为回破判词（超时只是上限闸）", sendReq.contains("零行为回破"))
        // 各 impl 的超时回落件（?: error 折算进既有 runCatching 失败路）
        assertTrue(
            "GatewayPlaza 超时回落件「TDLib 请求超时（可能限流）」应在场",
            plaza.contains("TDLib 请求超时（可能限流）"),
        )
    }
}
