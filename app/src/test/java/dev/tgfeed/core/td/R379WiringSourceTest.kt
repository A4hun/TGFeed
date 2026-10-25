package dev.tgfeed.core.td

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R379 静默限流完全解决方案 wiring 锚（源锚+纯函数+行为锚，R363/R364/R366 同款
 * user.dir 源定位模式）。类=「TG 隐性限流不发 429 只挂起——超时信号无人消费」：
 * FloodCircuitBreaker（R44/R58）只吃 429，静默期间熔断器全程不开闸，app 继续把
 * 请求烧进死车道（每发 12s 超时=账号持续承压）。本批三件：
 *  - **①静默打击上报**：withTdTimeout 超时分支喂 [FloodCircuitBreaker.silentStrike]
 *    （与 429 上报并行不替代）——判别线：单次超时=网络毛刺，同族 10min 滑窗 ≥3 次
 *    才开闸（防误伤）；退避梯 30min→2h→8h→24h 封顶（无 retry-after 的替代），
 *    consecutiveOpens 持久化、成功闭合清零归一档；R58 半开状态机零另起复用；
 *  - **②过闸前置**：withTdTimeout 发送前过 silentGuard——REJECT=零请求零承压按
 *    既有 null 回落；PROBE=放行（R58 计数归统一发送口不双扣）；PASS=照发；
 *  - **③绝对帽+最小间隔**：搜索族 12/h 绝对帽（USER_ACTION 不豁免）+全域 ≥1s
 *    最小间隔（杜绝 15 发/13ms 突发形态），双族共享账号桶合并计；
 *  - **④诚实降级（最小 UX 钩）**：REJECT 消费方呈现「限流保护中，预计 HH:mm 恢复」；
 *  - **⑤429 既有路径语义零改（负锚）**：report429 的 retry-after 权威/max 不缩短/
 *    半开废弃重熔全原样，本批只加退避梯档位记账行。
 */
class R379WiringSourceTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("r379_breaker").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

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

    private fun breakerSource(): String = src("dev/tgfeed/core/td/FloodCircuitBreaker.kt")

    private fun sendReqSource(): String = src("dev/tgfeed/core/td/GatewaySendReq.kt")

    private fun botSearchSource(): String = src("dev/tgfeed/core/td/GatewayBotSearch.kt")

    private fun channelDirectorySource(): String = src("dev/tgfeed/core/td/GatewayChannelDirectory.kt")

    // ==================== ① 静默打击阈值判定纯函数（判别线） ====================

    @Test
    fun `静默阈值判定纯函数_单次毛刺不开闸滑窗三次才开闸`() {
        val now = 1_000_000_000L
        val w = FloodCircuitBreaker.SILENT_STRIKE_WINDOW_MS
        // 判别线锚：滑窗 10min + 阈值 3
        assertEquals(10 * 60_000L, w)
        assertEquals(3, FloodCircuitBreaker.SILENT_STRIKE_THRESHOLD)
        // 单次/两次 = 毛刺口径不开闸
        assertFalse(FloodCircuitBreaker.silentThresholdMet(listOf(now), now))
        assertFalse(FloodCircuitBreaker.silentThresholdMet(listOf(now, now - 1_000), now))
        // 滑窗内三次 = 开闸
        assertTrue(FloodCircuitBreaker.silentThresholdMet(listOf(now, now - 60_000, now - 120_000), now))
        // 第三次落在滑窗外（10min 前）= 不足三
        assertFalse(
            FloodCircuitBreaker.silentThresholdMet(listOf(now, now - 60_000, now - w - 1), now),
        )
        // 时长格式（日志/UX 共口）：分钟粒度向上取整
        assertEquals("0:30", FloodCircuitBreaker.formatHhMm(1_800))
        assertEquals("2:00", FloodCircuitBreaker.formatHhMm(7_200))
        assertEquals("8:00", FloodCircuitBreaker.formatHhMm(28_800))
        assertEquals("24:00", FloodCircuitBreaker.formatHhMm(86_400))
        assertEquals("0:01", FloodCircuitBreaker.formatHhMm(45)) // 45s 不落「余 0:00」误导
    }

    // ==================== ② 退避梯纯函数 + 状态机升档/闭合清零（行为） ====================

    @Test
    fun `退避梯纯函数_升档封顶与闭合清零归一档`() {
        // 纯函数：1→30min、2→2h、3→8h、≥4→24h 封顶
        assertEquals(30 * 60L, FloodCircuitBreaker.silentBackoffSec(1))
        assertEquals(2 * 3_600L, FloodCircuitBreaker.silentBackoffSec(2))
        assertEquals(8 * 3_600L, FloodCircuitBreaker.silentBackoffSec(3))
        assertEquals(24 * 3_600L, FloodCircuitBreaker.silentBackoffSec(4))
        assertEquals(24 * 3_600L, FloodCircuitBreaker.silentBackoffSec(10))
        assertEquals(30 * 60L, FloodCircuitBreaker.silentBackoffSec(0)) // 防御：≤1 都归一档

        // 状态机走查（注入时钟）：3 打击开闸 30min → 到期半开 3 成功闭合+清零 →
        // 再 3 打击仍 30min（清零归一档实证）→ 不闭合连续打击升档 2h→8h
        val family = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
        var nowMs = 100_000_000L
        val b = FloodCircuitBreaker(tmp) { nowMs }
        fun strikeTriple() {
            repeat(3) { nowMs += 2_000L; b.silentStrike(family) }
        }
        // 第 1 轮：3 打击 → 30min 开闸（滑窗内恰三）
        strikeTriple()
        assertEquals(30 * 60L, b.remainingSec(family))
        // 到期 → 半开（gate 放行首发试探）→ 3 发成功 → 闭合+退避梯清零
        nowMs += 30 * 60_000L + 1
        assertEquals(FloodCircuitBreaker.GateDecision.PROBE, b.gate(family))
        repeat(3) { b.reportSuccess(family) }
        assertEquals(FloodCircuitBreaker.BreakerState.CLOSED, b.stateOf(family))
        // 清零归一档实证：闭合后再 3 打击 → 仍 30min（非 2h）
        strikeTriple()
        assertEquals(30 * 60L, b.remainingSec(family))
        // 不闭合连续打击：半开期试探再撞（超时累计口径）→ 升档 2h
        nowMs += 30 * 60_000L + 1
        assertEquals(FloodCircuitBreaker.GateDecision.PROBE, b.gate(family))
        strikeTriple() // consecutiveOpens=2
        assertEquals(2 * 3_600L, b.remainingSec(family))
        // 再打击 → 8h（第三档；滑窗内旧打击已过期、档位仍在）
        nowMs += 2 * 3_600_000L + 1
        strikeTriple() // consecutiveOpens=3
        assertEquals(8 * 3_600L, b.remainingSec(family))
        // 跨进程实证：新实例（同盘）恢复静默窗与档位
        val b2 = FloodCircuitBreaker(tmp) { nowMs }
        assertEquals(8 * 3_600L, b2.remainingSec(family))
    }

    @Test
    fun `429开闸也计档位_静默退避随之升级`() {
        val family = FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER
        var nowMs = 100_000_000L
        val b = FloodCircuitBreaker(tmp) { nowMs }
        // 429 开闸一次（retry-after 权威：60s）——档位 +1
        b.report429(family, 60)
        assertEquals(60L, b.remainingSec(family))
        // 到期后 3 打击 → consecutiveOpens=2 → 2h（429 开闸计入连续开闸）
        nowMs += 61_000L
        repeat(3) { nowMs += 2_000L; b.silentStrike(family) }
        assertEquals(2 * 3_600L, b.remainingSec(family))
    }

    // ==================== ③ 过闸前置接线源锚 + 行为锚 ====================

    @Test
    fun `过闸前置接线源锚_先过闸后发送超时喂打击`() {
        val s = sendReqSource()
        // 映射单源在场：真发送口前缀精确匹配（外层委托包装/验身类不映射防双计）
        assertTrue(
            "silentFloodFamilyOf 须定义在 GatewaySendReq.kt（单源）",
            s.contains("internal fun silentFloodFamilyOf(what: String): String?"),
        )
        assertTrue(s.contains("startsWith(\"searchPublicChatEx/SearchPublicChat\")"))
        assertTrue(s.contains("startsWith(\"searchChatsOnServer/SearchChatsOnServer\")"))
        val mapStart = s.indexOf("internal fun silentFloodFamilyOf")
        assertTrue(mapStart > 0)
        val mapEnd = s.indexOf("internal suspend fun <T> withTdTimeout(", mapStart)
        val mapBody = s.substring(mapStart, mapEnd)
        assertFalse("映射体不得含 plazaFindChat（外层委托包装不映射——防同一发送双计）", mapBody.contains("plazaFindChat"))
        // 时序锚：silentGuard 过闸在 withTimeout 之前（挂起前的拒发）；silentStrike 在超时 catch 之后
        val fnStart = s.indexOf("internal suspend fun <T> withTdTimeout(")
        assertTrue(fnStart > 0)
        val body = s.substring(fnStart)
        val guardIdx = body.indexOf("silentGuard(family)")
        val timeoutIdx = body.indexOf("withTimeout(timeoutMs)")
        val catchIdx = body.indexOf("catch (e: TimeoutCancellationException)")
        val strikeIdx = body.indexOf("silentStrike(family)")
        assertTrue("withTdTimeout 须先过 silentGuard 再 withTimeout（挂起前拒发）", guardIdx > 0 && guardIdx < timeoutIdx)
        assertTrue("超时分支须喂 silentStrike（超时=隐性限流在场唯一信号）", strikeIdx > catchIdx && catchIdx > 0)
        // 跨文件锚：两统一发送口的真发送 what 与映射前缀逐字对齐（改 what 不改映射=防线空转）
        val bot = botSearchSource()
        assertTrue(bot.contains("searchPublicChatEx/SearchPublicChat @\$key"))
        assertTrue(bot.contains("searchChatsOnServer/SearchChatsOnServer limit=\$limit"))
        // 最小 UX 钩在场：REJECT 消费方诚实降级文案
        val cd = channelDirectorySource()
        assertTrue("限流保护中+预计恢复时刻文案须在场（诚实降级最小钩）", cd.contains("限流保护中，预计"))
    }

    @Test
    fun `过闸前置行为锚_拒发零请求零承压与打击上报`() {
        // 行为锚（真实 withTdTimeout 路径+hub 自挂）：3 次超时→开闸→REJECT 零请求。
        // 打击间隔 ≥1s（配速最小间隔闸对真发送生效——亚秒连打本就该被整形拒发）
        val b = FloodCircuitBreaker(tmp)
        repeat(3) {
            runBlocking {
                assertNull(withTdTimeout(timeoutMs = 1, what = "searchPublicChatEx/SearchPublicChat @行为锚") { delay(5_000) })
            }
            runBlocking { delay(1_100) }
        }
        assertTrue("3 次超时后须开闸（滑窗阈值判定）", b.isSilenced(FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT))
        // REJECT：block 零执行（零请求零承压）+ null 回落
        val fired = AtomicBoolean(false)
        runBlocking {
            assertNull(withTdTimeout(timeoutMs = 5_000, what = "searchPublicChatEx/SearchPublicChat @行为锚2") { fired.set(true); "sent" })
        }
        assertFalse("REJECT 态 block 不得执行（不发请求）", fired.get())
        // 非搜索族零映射：防线不生效（行为零改——GetChat 族等照发）
        runBlocking {
            assertEquals("v", withTdTimeout(timeoutMs = 5_000, what = "behaviorProbe/GetChat") { "v" })
        }
    }

    // ==================== ④ 绝对帽与最小间隔常量单源 + 配速闸行为 ====================

    @Test
    fun `绝对帽与最小间隔常量单源_配速闸双族合并计`() {
        // 常量值锚 + 唯一出处锚（全仓不许第二份数字）
        assertEquals(12, FloodCircuitBreaker.SEARCH_SCOPE_HOURLY_CAP)
        assertEquals(1_000L, FloodCircuitBreaker.SEARCH_SEND_MIN_INTERVAL_MS)
        val bSrc = breakerSource()
        assertEquals("SEARCH_SCOPE_HOURLY_CAP 须唯一出处", 1, Regex("SEARCH_SCOPE_HOURLY_CAP = 12").findAll(bSrc).count())
        assertEquals("SEARCH_SEND_MIN_INTERVAL_MS 须唯一出处", 1, Regex("SEARCH_SEND_MIN_INTERVAL_MS = 1_000L").findAll(bSrc).count())
        for ((name, text) in mapOf(
            "GatewaySendReq" to sendReqSource(),
            "GatewayBotSearch" to botSearchSource(),
            "GatewayChannelDirectory" to channelDirectorySource(),
        )) {
            assertFalse("$name 不得自建帽/间隔常量（纪律§一：单源）", text.contains("SEARCH_SCOPE_HOURLY_CAP"))
            assertFalse("$name 不得自建间隔常量", text.contains("SEARCH_SEND_MIN_INTERVAL_MS"))
        }
        // 双族共享账号桶 scope：两族合并一份配速账
        assertTrue(bSrc.contains("SEARCH_PACE_SCOPE"))
        // 配速闸行为：最小间隔（亚秒双发拒）+绝对帽（第 13 发拒）+非搜索族不配速+窗滑出回血
        var nowMs = 200_000_000L
        val b = FloodCircuitBreaker(tmp) { nowMs }
        val f1 = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
        val f2 = FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER
        assertEquals(FloodCircuitBreaker.GateDecision.PASS, b.silentGuard(f1))
        nowMs += 500
        assertEquals("另一族 0.5s 内也拒（双族合并账号桶）", FloodCircuitBreaker.GateDecision.REJECT, b.silentGuard(f2))
        nowMs += 600
        assertEquals(FloodCircuitBreaker.GateDecision.PASS, b.silentGuard(f2))
        // 攒满 12 发（当前已 2 发；每次间隔 1.1s ≥ 最小间隔）
        repeat(10) { nowMs += 1_100; assertEquals(FloodCircuitBreaker.GateDecision.PASS, b.silentGuard(if (it % 2 == 0) f1 else f2)) }
        nowMs += 1_100
        assertEquals(
            "第 13 发=绝对帽拒（USER_ACTION 不豁免的最后一道机械防线）",
            FloodCircuitBreaker.GateDecision.REJECT,
            b.silentGuard(f1),
        )
        // 非搜索族不配速（GetChatHistory 族零行为改）
        assertEquals(FloodCircuitBreaker.GateDecision.PASS, b.silentGuard(FloodCircuitBreaker.FAMILY_GET_CHAT_HISTORY))
        // 1h 滑出回血
        nowMs += 3_600_000L
        assertEquals(FloodCircuitBreaker.GateDecision.PASS, b.silentGuard(f1))
    }

    // ==================== ⑤ 429 既有路径语义零改（负锚） ====================

    @Test
    fun `429路径零改负锚_retryAfter权威与R58闸原样`() {
        val bSrc = breakerSource()
        // report429 核心语义行逐字在场：retry-after 权威、max 不缩短、半开废弃重熔
        assertTrue(
            "429 等待时长仍 100% 服务端 retry-after（DEFAULT 兜底原样）",
            bSrc.contains("val waitSec = if (retryAfterSec > 0) retryAfterSec else DEFAULT_RETRY_AFTER_SEC"),
        )
        assertTrue("max 不缩短判据原样", bSrc.contains("已有更长静默"))
        assertTrue("半开试探撞 429 废弃重熔日志原样", bSrc.contains("半开试探撞 429"))
        assertTrue("429 熔断日志原样", bSrc.contains("429 熔断："))
        assertTrue("静默截止写入仍按 waitSec（非退避梯）", bSrc.contains("st.families[family] = now + waitSec * 1000L"))
        // R58 半开状态机零另起：gate 的间隔闸/试探放行原样
        assertTrue("半开间隔闸原样", bSrc.contains("if (now - ho.lastProbeAt < PROBE_INTERVAL_MS) return GateDecision.REJECT"))
        assertTrue("半开试探放行日志原样", bSrc.contains("半开试探放行："))
        assertTrue("半开进入原样", bSrc.contains("半开进入："))
        // R58 阈值/间隔常量未改（唯一出处）
        assertEquals(1, Regex("PROBE_SUCCESS_TO_CLOSE = 3").findAll(bSrc).count())
        assertEquals(1, Regex("PROBE_INTERVAL_MS = 2_000L").findAll(bSrc).count())
        assertEquals(1, Regex("DEFAULT_RETRY_AFTER_SEC = 300L").findAll(bSrc).count())
    }
}
