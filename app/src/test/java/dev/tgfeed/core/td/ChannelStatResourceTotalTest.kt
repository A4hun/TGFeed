package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R175 资源总数口径单测（[ChannelStatTriple.resourceTotalOrNull]）：
 * 「作品 N」角标的服务端口径——三类 SearchChatMessages totalCount 已知项求和；
 * 全类未知返回 null（回落本地口径），绝不把 0 冒充总数。
 */
class ChannelStatResourceTotalTest {

    @Test
    fun `三类齐全_求和`() {
        assertEquals(600, ChannelStatTriple(500, 80, 20, memberCount = 1).resourceTotalOrNull())
    }

    @Test
    fun `单类未知_按零计_诚实下界`() {
        // 音频探测失败（-1）：统计行不显示该项，总数 = 视频+图文
        assertEquals(580, ChannelStatTriple(500, 80, -1, memberCount = -1).resourceTotalOrNull())
    }

    @Test
    fun `三类全未知_返回null_回落本地口径`() {
        // 只救回成员数的探测（probeChannelStats 的 ok 只需单路成功）：
        // 总数不可知——回落「已加载口径」，不许把 0 当总数骗用户
        assertNull(ChannelStatTriple(-1, -1, -1, memberCount = 12345).resourceTotalOrNull())
    }

    @Test
    fun `零计数频道_真零不是null`() {
        // 真的空频道（三类都成功且为 0）：总数 0 是诚实的，与「未知 null」语义分离
        assertEquals(0, ChannelStatTriple(0, 0, 0, memberCount = 10).resourceTotalOrNull())
    }

    @Test
    fun `部分成功零计数_求和仍成立`() {
        // 视频成功为 0、图文失败：总数 = 0（诚实下界，非 null）
        assertEquals(0, ChannelStatTriple(0, -1, -1, memberCount = -1).resourceTotalOrNull())
    }
}
