package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R366 TDLib 裸发送超时兜底三期 + liveAsk 写闸 wiring 源码锚（R363/R364 同款
 * user.dir 源定位模式）。类=「suspend 的 TDLib 请求无超时兜底」——TDLib 隐性限流
 * （请求挂起无响应的环境病）时协程永挂；本批收口 R364 挂账的高危三面：
 *  - **裸 send 绝迹①**：GatewayLiveSearch.kt 6 处（liveAsk 发词 SendMessage=「已问
 *    N 实体，等待应答…」长转真身）/GatewayDiscovery.kt 21 处（发现枚举/简介/档案/
 *    加入态判定链）/GatewayChannelDirectory.kt 8 处（开频道自愈/深链/邀请链兜底）
 *    全裹 withTdTimeout（逐函数点名+前窗 240 字符裹尸布锚，R363 同口径）；
 *  - **liveAsk 写闸②（§十 缺口收口，R364 涉写事故正治）**：askSearchEntityImpl 发词
 *    路径接 accountWriteBlocked（闸在开头发送前，闸开=如实提示零发送零永挂）；
 *    VM 侧 liveAskSearchImpl 同口预检 + LiveAskState.writeBlocked 如实提示
 *    「写保护中」（不落假「等待应答」长转）；
 *  - **包装单源③**：withTdTimeout 唯一定义在 GatewaySendReq.kt，三文件零自建
 *    超时包装、零 withTimeoutOrNull、零裸 12_000/30_000 字面量；
 *  - **诚实失败④**：超时按各函数既有型别回落（主列表枚举超时 error 上抛不落空表
 *    冒充枚举成功；邀请链超时如实报「超时」不落「无效/失败」泛词；liveAsk 发送
 *    超时 sentOk=false 不记一词一轮冷却），真协程取消不吞。
 */
class R366WiringSourceTest {

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

    private fun liveSearch(): String = src("dev/tgfeed/core/td/GatewayLiveSearch.kt")

    private fun discovery(): String = src("dev/tgfeed/core/td/GatewayDiscovery.kt")

    private fun channelDirectory(): String = src("dev/tgfeed/core/td/GatewayChannelDirectory.kt")

    private fun gatewaySendReq(): String = src("dev/tgfeed/core/td/GatewaySendReq.kt")

    private fun feedSearchPart(): String = src("dev/tgfeed/core/feed/FeedSearchPart.kt")

    private fun feedModels(): String = src("dev/tgfeed/core/feed/FeedModels.kt")

    private fun searchScreen(): String = src("dev/tgfeed/ui/SearchScreen.kt")

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

    // ==================== ①a 裸 send 绝迹：LiveSearch 6 处全裹（liveAsk 永挂真身） ====================

    @Test
    fun `裸send绝迹_LiveSearch六处send全裹包装`() {
        val ls = liveSearch()
        val sendCount = Regex("client\\.send\\(").findAll(ls).count()
        assertEquals("GatewayLiveSearch 现有 6 处 client.send（R364 普查口径）", 6, sendCount)
        assertAllSendsWrapped(ls, "GatewayLiveSearch", "withTdTimeout(")
        assertEquals("LiveSearch 6 处 send 全裹 withTdTimeout", 6, Regex("withTdTimeout\\(").findAll(ls).count())
        // 关键函数逐个点名（askSearchEntity=「等待应答」长转真身，必须在列）
        val fns = listOf(
            "searchViaGroupImpl", "resolveLinkVideoItemImpl", "askSearchEntityImpl",
            "resolveLinksToItems", "harvestAnswerTitleHintsImpl",
        )
        for (fn in fns) {
            assertTrue(
                "$fn 必须走 withTdTimeout 统一超时包装（隐性限流挂起不再永挂）",
                implBody(ls, fn).contains("withTdTimeout("),
            )
        }
        // liveAsk 发词超时=诚实报失败（不落假「等待应答」）：SendMessage 超时 null 即收
        val ask = implBody(ls, "askSearchEntityImpl")
        assertTrue(
            "askSearchEntity 发词超时须 null 回落收场（sentOk 留 false，不记一词一轮冷却）",
            ask.contains("askSearchEntity/SendMessage") && ask.contains("?: return@runCatching"),
        )
    }

    // ==================== ①b 裸 send 绝迹：Discovery 21 处全裹 ====================

    @Test
    fun `裸send绝迹_Discovery二十一处send全裹包装`() {
        val d = discovery()
        val sendCount = Regex("client\\.send\\(").findAll(d).count()
        assertEquals("GatewayDiscovery 现有 21 处 client.send（R364 普查口径）", 21, sendCount)
        assertAllSendsWrapped(d, "GatewayDiscovery", "withTdTimeout(")
        assertEquals("Discovery 21 处 send 全裹 withTdTimeout", 21, Regex("withTdTimeout\\(").findAll(d).count())
        val fns = listOf(
            "isBotUserImpl", "listHarvestGroupCandidatesImpl", "listDiscoveryProfilesImpl",
            "fetchSupergroupDescriptionImpl", "probeUserBotProfileImpl", "getMyProfileImpl",
            "fetchSupergroupProfileImpl", "harvestChatFolderInviteImpl", "joinStatusOfImpl",
        )
        for (fn in fns) {
            assertTrue("$fn 必须走 withTdTimeout 统一超时包装", implBody(d, fn).contains("withTdTimeout("))
        }
        // 主列表枚举超时=本轮失败上抛（不落空表冒充「枚举成功但无群」污染保守保留口径）
        assertEquals(
            "两处主列表 GetChats 超时须 error 上抛（既有失败语义）",
            2,
            Regex("\\?: error\\(\"TDLib 请求超时（可能限流）\"\\)").findAll(d).count(),
        )
    }

    // ==================== ①c 裸 send 绝迹：ChannelDirectory 8 处全裹 ====================

    @Test
    fun `裸send绝迹_ChannelDirectory八处send全裹包装`() {
        val cd = channelDirectory()
        val sendCount = Regex("client\\.send\\(").findAll(cd).count()
        assertEquals("GatewayChannelDirectory 现有 8 处 client.send（R364 普查口径）", 8, sendCount)
        assertAllSendsWrapped(cd, "GatewayChannelDirectory", "withTdTimeout(")
        assertEquals(
            "ChannelDirectory 8 处 send 全裹 withTdTimeout",
            8,
            Regex("withTdTimeout\\(").findAll(cd).count(),
        )
        val fns = listOf("ensureChannelOpenableImpl", "resolvePublicChannelImpl", "resolveInviteLinkImpl")
        for (fn in fns) {
            assertTrue("$fn 必须走 withTdTimeout 统一超时包装", implBody(cd, fn).contains("withTdTimeout("))
        }
        // 邀请链超时如实报「超时」不落「无效/失败」泛词；真协程取消不吞
        assertTrue("CheckChatInviteLink 超时须如实报超时", cd.contains("链接检查超时（网络可能限流），请稍后重试"))
        assertTrue("JoinChatByInviteLink 超时须如实报超时且结果未知", cd.contains("加入请求超时（网络可能限流），结果未知"))
        assertTrue("邀请链不得吞真协程取消", implBody(cd, "resolveInviteLinkImpl").contains("catch (e: kotlinx.coroutines.CancellationException)"))
    }

    // ==================== ② liveAsk 写闸：§十 缺口收口（R364 涉写事故正治） ====================

    @Test
    fun `liveAsk写闸_发词路径过accountWriteBlocked且如实提示`() {
        val ls = liveSearch()
        // 网关侧权威拦截点：闸在发词 SendMessage 之前
        val ask = implBody(ls, "askSearchEntityImpl")
        val gateIdx = ask.indexOf("accountWriteBlocked()")
        val sendIdx = ask.indexOf("TdApi.SendMessage(")
        assertTrue("askSearchEntityImpl 须接 accountWriteBlocked 写闸（§十）", gateIdx > 0)
        assertTrue("写闸须位于发词 SendMessage 之前（闸开零发送）", sendIdx > 0 && gateIdx < sendIdx)
        assertTrue("闸开须如实日志零发送", ask.contains("被账号写保护拦截（§十测试模式，未发词）"))
        assertTrue("KDoc 须记 R364 事故判词", ls.contains("R364 事故判词"))
        // VM 侧同口预检 + 如实提示态（不落假「等待应答」长转）
        val fsp = feedSearchPart()
        assertTrue("liveAskSearchImpl 须同口预检写闸", fsp.contains("real.accountWriteBlocked()"))
        assertTrue("闸开须发布 writeBlocked 态", fsp.contains("writeBlocked = true"))
        assertTrue("LiveAskState 须有 writeBlocked 字段", feedModels().contains("writeBlocked"))
        val ss = searchScreen()
        assertTrue("LiveAskSection 须渲染写闸态", ss.contains("state.writeBlocked"))
        assertTrue("写闸态须如实提示「写保护中」", ss.contains("写保护中：本轮未发词"))
    }

    // ==================== ③ 包装单源：withTdTimeout 唯一定义，三文件零自建 ====================

    @Test
    fun `包装单源_三期三文件零自建零裸超时`() {
        val sendReq = gatewaySendReq()
        assertTrue(
            "withTdTimeout 须唯一定义在 GatewaySendReq.kt（与 sendReq 同家超时共享件）",
            sendReq.contains("internal suspend fun <T> withTdTimeout("),
        )
        assertTrue("withTdTimeout KDoc 须记 R366 三期收编判词", sendReq.contains("R366 三期收编"))
        for ((name, body) in mapOf(
            "GatewayLiveSearch" to liveSearch(),
            "GatewayDiscovery" to discovery(),
            "GatewayChannelDirectory" to channelDirectory(),
        )) {
            assertFalse("$name 不得自建超时包装函数（纪律§一：共享件单源）", body.contains("fun withTdTimeout"))
            assertFalse("$name 不得裸用 withTimeoutOrNull（走 withTdTimeout 单源）", body.contains("withTimeoutOrNull("))
            assertFalse("$name 不得裸用 withTimeout（一切超时走单源包装）", body.contains("withTimeout("))
            assertFalse("$name 不得出现裸 12s 字面量", body.contains("12_000"))
            assertFalse("$name 不得出现裸 30s 字面量", body.contains("30_000"))
        }
        assertTrue(
            "withTdTimeout 默认档须引用 PLAZA_TD_TIMEOUT_MS（12s 单口常量族）",
            sendReq.contains("timeoutMs: Long = PLAZA_TD_TIMEOUT_MS"),
        )
    }
}
