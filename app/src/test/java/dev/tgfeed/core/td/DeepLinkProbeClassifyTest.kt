package dev.tgfeed.core.td

import io.github.tdlibandroid.ktx.TdException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException

/**
 * [classifyDeepLinkProbeFailure]（R169 F6，深链探测失败三态分类）的行为单测：
 * 使命=「失败≠不存在」——网络抖动/限流/超时不得误判"真私有不可达"（R70 同病，
 * PlazaFindResult/PublicChatLookup 同范式）。锚定分类契约：
 *  - TDLib 400 类稳定终态 → Inaccessible（服务端权威说此路不通）；
 *  - 429 / 超时（sendReq 转普通 TimeoutException）/ 未知异常 → Failed（可重试）。
 */
class DeepLinkProbeClassifyTest {

    @Test
    fun `400 是服务端稳定终态_判 Inaccessible 重试无益`() {
        val r = classifyDeepLinkProbeFailure(TdException(400, "MESSAGE_LINK_INVALID"), "测试")
        assertEquals(DeepLinkChatResolve.Inaccessible, r)
    }

    @Test
    fun `429 限流是瞬态_判 Failed 不是不存在`() {
        val r = classifyDeepLinkProbeFailure(TdException(429, "Too Many Requests: retry after 30"), "测试")
        assertTrue(r is DeepLinkChatResolve.Failed)
    }

    @Test
    fun `服务端 5xx 是瞬态_判 Failed`() {
        val r = classifyDeepLinkProbeFailure(TdException(500, "Internal"), "测试")
        assertTrue(r is DeepLinkChatResolve.Failed)
    }

    @Test
    fun `sendReq 超时异常_判 Failed 走重试口径`() {
        val r = classifyDeepLinkProbeFailure(TimeoutException("TDLib 请求超时 10000ms: GetMessageLinkInfo"), "测试")
        assertTrue(r is DeepLinkChatResolve.Failed)
    }

    @Test
    fun `未知运行时异常_判 Failed 保守不误杀`() {
        val r = classifyDeepLinkProbeFailure(IllegalStateException("native 崩了"), "测试")
        assertTrue(r is DeepLinkChatResolve.Failed)
    }

    @Test
    fun `Failed 原因非空_携带异常类型与消息供分诊`() {
        val r = classifyDeepLinkProbeFailure(TdException(429, "Too Many Requests"), "测试") as DeepLinkChatResolve.Failed
        assertTrue(r.reason.contains("429") || r.reason.contains("Too Many Requests"))
        assertTrue(r.reason.isNotBlank())
        // 无 message 的异常也要给出可读原因（异常类名兜底，不留空 reason）
        val bare = classifyDeepLinkProbeFailure(RuntimeException(), "测试") as DeepLinkChatResolve.Failed
        assertTrue(bare.reason.isNotBlank())
    }
}
