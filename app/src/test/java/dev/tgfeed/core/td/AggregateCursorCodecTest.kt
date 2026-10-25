package dev.tgfeed.core.td

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聚合游标编解码（GatewayFeedFetch.kt 的 [TdLibGateway.encodeCursor]/[TdLibGateway.decodeCursor]，
 * fetchAggregated 的游标协议： "chatId:msgId|chatId:msgId"，msgId=-1 表示该频道已到底）行为单测。
 *
 * 被测函数是 TdLibGateway 的扩展函数但完全不触达接收体状态，故用 mockk 造一个
 * 不触发构造器（不加载 native 库）的假接收体即可在纯 JVM 下测试。
 */
class AggregateCursorCodecTest {

    /** 假接收体：mockk 经 Objenesis 实例化，不跑 TdLibGateway 的 init 块（不加载 tdjni） */
    private val gateway = mockk<TdLibGateway>()

    @Test
    fun `编码解码往返保持内容不变`() {
        // 行为：任意合法游标表 编码→解码 往返恒等（含到底标记 -1 与极大值边界）
        val map = linkedMapOf(
            123L to 456789L,
            7L to -1L, // -1 = 该频道已到底
            Long.MAX_VALUE to 0L, // 边界：最大 chatId、游标 0（从头开始）
            -100L to 99L, // 负 chatId（讨论组等场景）也应无损往返
        )
        val decoded = gateway.decodeCursor(gateway.encodeCursor(map))
        assertEquals(map, decoded)
    }

    @Test
    fun `空表编码为空串且解码回空表`() {
        // 行为边界：空游标表 → 空串；空串/null 都解码为空表（首轮 cursor=null 语义）
        assertEquals("", gateway.encodeCursor(emptyMap()))
        assertEquals(emptyMap<Long, Long>(), gateway.decodeCursor(null))
        assertEquals(emptyMap<Long, Long>(), gateway.decodeCursor(""))
        assertEquals(emptyMap<Long, Long>(), gateway.decodeCursor("   "))
    }

    @Test
    fun `解码容忍坏段只跳过该段`() {
        // 行为：逐段防御——无法解析的段（缺冒号、非数字键、非数字值）只跳过该段，
        // 其余段正常解析；游标是跨进程传递的不可信输入，不能让一段坏数据拖垮整链
        val decoded = gateway.decodeCursor("1:100|坏段|2:200|x:300|3:不是数字|4:400")
        assertEquals(mapOf(1L to 100L, 2L to 200L, 4L to 400L), decoded)
    }

    @Test
    fun `解码对同键后值覆盖前值`() {
        // 行为：同一 chatId 出现多次时 toMap 语义——后者覆盖前者
        // （构造上不该出现，锁定 toMap 的实际行为防无意变更）
        assertEquals(mapOf(1L to 9L), gateway.decodeCursor("1:5|1:9"))
    }

    @Test
    fun `解码对多余冒号段取前两段`() {
        // 行为：段内多个冒号时 split 后取前两段（"1:2:3" → 1:2），多余部分忽略——
        // 锁定当前宽容解析口径（真实游标值是消息 id，本就不会含冒号）
        assertEquals(mapOf(1L to 2L), gateway.decodeCursor("1:2:3"))
    }

    @Test
    fun `编码段格式为 chatId 冒号 msgId 竖线分隔`() {
        // 行为：编码格式锁定（协议的另一半）——键值对冒号连接、段间竖线分隔、保持表序
        assertEquals("5:100|9:-1", gateway.encodeCursor(linkedMapOf(5L to 100L, 9L to -1L)))
    }
}
