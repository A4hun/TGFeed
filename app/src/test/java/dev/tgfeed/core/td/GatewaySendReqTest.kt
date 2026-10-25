package dev.tgfeed.core.td

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * [sendReq] / [REQUEST_TIMEOUT_MS]（R169 P0-2，F1+F13）的契约单测：
 *  1. 10s 标准超时是全仓唯一真源（F13 口径分裂锚：改这个值必须过本关——
 *     23 处裸字面量收编是 census P1-3 挂账，收编后都指到这里）；
 *  2. 超时异常转换契约：TimeoutCancellationException（CancellationException 子类）
 *     必须转成普通 TimeoutException——否则 `catch(CancellationException){throw e}`
 *     的挂起环把超时当协程取消上抛、runCatching 吞成静默取消（R165 复合灾变链里
 *     「挂死连失败日志都打不出」的放大器）。
 *     异常实例用真实 withTimeout 触发获取（构造器对 Kotlin 是 internal，反射绕过反而失真）。
 */
class GatewaySendReqTest {

    /** 真实触发一次 withTimeout 超时拿异常实例（1ms 到点，测试瞬时完成） */
    private fun realTimeout(): TimeoutCancellationException = try {
        runBlocking { withTimeout(1) { delay(10_000) } }
        error("预期 withTimeout 超时未发生")
    } catch (e: TimeoutCancellationException) {
        e
    }

    @Test
    fun `标准请求超时唯一真源_10 秒`() {
        assertEquals(10_000L, REQUEST_TIMEOUT_MS)
    }

    @Test
    fun `超时转普通异常_不再是 CancellationException 家族`() {
        val out = timeoutToFailure(realTimeout(), "GetMe")
        assertTrue(out is TimeoutException)
        // 核心契约：不再是 CancellationException——挂起环不会误当协程取消处理
        assertFalse(out is CancellationException)
    }

    @Test
    fun `转换后的消息含函数名与毫秒数_日志可分诊`() {
        val out = timeoutToFailure(realTimeout(), "GetMessageThread")
        assertTrue(out.message!!.contains("GetMessageThread"))
        assertTrue(out.message!!.contains(REQUEST_TIMEOUT_MS.toString()))
    }
}
