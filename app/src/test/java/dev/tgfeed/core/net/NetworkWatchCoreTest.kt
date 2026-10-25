package dev.tgfeed.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R324 NetworkWatch 状态机锚（NetworkWatchCore——纯件，构造数据零真实频道）：
 * 初值/事件归约/无事件不变（atMs 不漂移——状态保持期间的诚实时点）/丢失重估沿。
 */
class NetworkWatchCoreTest {

    /** 确定时钟（测试内自增秒）——确定性锚（同数据同宇宙铁律的件级同族） */
    private var fakeNowMs = 1_000_000L
    private fun tick() { fakeNowMs += 1_000L }

    private fun core(online: Boolean = false, metered: Boolean = false) =
        NetworkWatchCore(initialOnline = online, initialMetered = metered, nowMs = { fakeNowMs })

    @Test
    fun `初值-构造即首态（online 与 metered 落位）`() {
        val c = core(online = true, metered = false)
        assertEquals(true, c.state.online)
        assertEquals(false, c.state.metered)
        assertEquals(fakeNowMs, c.state.atMs)
    }

    @Test
    fun `可用沿-默认网络出现置在线（计量口径暂沿用现值）`() {
        val c = core(online = false, metered = false)
        tick()
        val s = c.onDefaultAvailable()
        assertEquals(true, s.online)
        assertEquals(false, s.metered) // 能力回调未到前不编造计量值
    }

    @Test
    fun `能力沿-计量随NOT_METERED取反且置在线`() {
        val c = core(online = true, metered = false)
        tick()
        val wifi = c.onCapabilitiesChanged(notMetered = true) // WiFi：非计量
        assertEquals(true, wifi.online)
        assertEquals(false, wifi.metered)
        tick()
        val cellular = c.onCapabilitiesChanged(notMetered = false) // 移动数据：计量
        assertEquals(true, cellular.online)
        assertEquals(true, cellular.metered)
    }

    @Test
    fun `丢失沿-真断网置离线`() {
        val c = core(online = true, metered = true)
        tick()
        val s = c.onDefaultLost(onlineNow = false, meteredNow = false)
        assertEquals(false, s.online)
        assertEquals(false, s.metered) // 无网络视为非计量（NetworkMeteredBus 语义保持）
    }

    @Test
    fun `丢失沿-切网重估（新默认网络已顶上）保持在线`() {
        val c = core(online = true, metered = false) // WiFi 在线
        tick()
        val s = c.onDefaultLost(onlineNow = true, meteredNow = true) // WiFi 丢、移动数据顶上
        assertEquals(true, s.online)
        assertEquals(true, s.metered)
    }

    @Test
    fun `无事件不变-同值事件不换时刻戳（状态保持期 atMs 不漂移）`() {
        val c = core(online = true, metered = false)
        val first = c.state
        tick()
        tick()
        val again = c.onCapabilitiesChanged(notMetered = true) // 同值能力回调（系统会重发）
        assertEquals(first, again) // 值相等（data class 深等：含 atMs——零漂移锚）
        assertEquals(first.atMs, again.atMs)
    }

    @Test
    fun `状态迁移-变化事件换新时刻戳`() {
        val c = core(online = true, metered = false)
        val t0 = c.state.atMs
        tick()
        val s = c.onCapabilitiesChanged(notMetered = false)
        assertNotEquals(t0, s.atMs)
        assertTrue(s.atMs > t0)
    }

    @Test
    fun `重播种-start现读结果落位（离线重估同链）`() {
        val c = core(online = false, metered = false)
        tick()
        val s = c.reseed(onlineNow = true, meteredNow = true)
        assertEquals(true, s.online)
        assertEquals(true, s.metered)
    }
}
