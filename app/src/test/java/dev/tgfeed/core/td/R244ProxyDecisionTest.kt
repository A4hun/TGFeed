package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TdLibGateway.decideProxy]（R244 新设备登录 P0，实锤②代理链路）的决策矩阵单测。
 * 背景：TDLib 原生库不读 Android 系统 HTTP 代理——手机装了 Clash/V2Ray（系统级
 * 代理软件）也必须显式 AddProxy 才走代理；旧逻辑只探测配置地址（默认
 * 127.0.0.1:7897 = 模拟器 adb reverse 场景），真机必失败 → 静默直连 → 被墙。
 * R244 决策口径：配置代理可达照旧用；否则本地常见 SOCKS5 端口命中即自动接入；
 * 全无才直连（由网络旁路监视兜底报错，不再静默）。
 */
class R244ProxyDecisionTest {

    // ---- 配置代理优先（用户/设置页显式配置可达 = 最高优先级） ----

    @Test
    fun `配置代理开且可达_照旧用配置_不算自动`() {
        val d = TdLibGateway.decideProxy(
            configuredEnabled = true, configuredHost = "192.0.2.5", configuredPort = 7897,
            configuredReachable = true, localProbePort = 7890,
        )
        assertEquals(Triple("192.0.2.5", 7897, false), d)
    }

    // ---- 实锤②主场景：配置关/不可达 → 本地代理软件端口自动接入 ----

    @Test
    fun `用户实锤场景_配置关_本地探到代理端口_自动接入并纠正`() {
        // 用户机实测 prefs：proxy_enabled=false + 127.0.0.1:7897（模拟器默认值）
        val d = TdLibGateway.decideProxy(
            configuredEnabled = false, configuredHost = "127.0.0.1", configuredPort = 7897,
            configuredReachable = false, localProbePort = 7890,
        )
        assertEquals(Triple("127.0.0.1", 7890, true), d)
    }

    @Test
    fun `配置地址不可达_本地探到_v2rayNG 端口_自动接入`() {
        val d = TdLibGateway.decideProxy(
            configuredEnabled = true, configuredHost = "127.0.0.1", configuredPort = 7897,
            configuredReachable = false, localProbePort = 10808,
        )
        assertEquals(Triple("127.0.0.1", 10808, true), d)
    }

    @Test
    fun `host 空白_本地探到_自动接入`() {
        val d = TdLibGateway.decideProxy(
            configuredEnabled = true, configuredHost = "", configuredPort = 7897,
            configuredReachable = false, localProbePort = 7891,
        )
        assertEquals(Triple("127.0.0.1", 7891, true), d)
    }

    // ---- 全无兜底：直连（null；由 AUTH_NET_WATCH_MS 旁路监视给用户明确错误） ----

    @Test
    fun `配置不可达且本地无代理_直连`() {
        assertNull(
            TdLibGateway.decideProxy(
                configuredEnabled = true, configuredHost = "127.0.0.1", configuredPort = 7897,
                configuredReachable = false, localProbePort = null,
            )
        )
    }

    @Test
    fun `配置关且本地无代理_直连_不惊扰显式关闭用户`() {
        assertNull(
            TdLibGateway.decideProxy(
                configuredEnabled = false, configuredHost = "127.0.0.1", configuredPort = 7897,
                configuredReachable = false, localProbePort = null,
            )
        )
    }

    // ---- 边界防御 ----

    @Test
    fun `本地探测端口非正数_不当作命中`() {
        // 探测函数约定：firstOrNull 命中才传端口；防御 0/负值污染决策
        assertNull(
            TdLibGateway.decideProxy(
                configuredEnabled = false, configuredHost = "", configuredPort = 0,
                configuredReachable = false, localProbePort = 0,
            )
        )
        assertNull(
            TdLibGateway.decideProxy(
                configuredEnabled = false, configuredHost = "", configuredPort = 0,
                configuredReachable = false, localProbePort = -1,
            )
        )
    }

    @Test
    fun `配置开关关闭时_即使配置地址可达也走本地探测优先级`() {
        // 语义：enabled=false = 不用配置的代理；但本地代理软件（探测命中）仍自动接
        // （用户装代理软件的意图就是走代理——任务书拍板「自动开启」）
        val d = TdLibGateway.decideProxy(
            configuredEnabled = false, configuredHost = "10.0.0.2", configuredPort = 1080,
            configuredReachable = true, localProbePort = 7890,
        )
        assertEquals(Triple("127.0.0.1", 7890, true), d)
    }

    // ---- 探测端口表口径（改表必须过本关：表是用户实锤场景的接入面） ----

    @Test
    fun `本地探测表覆盖主流系统代理软件端口_含项目默认`() {
        // Clash 混合 7890 / Clash SOCKS5 7891 / Clash Verge 7897（项目默认同款）/
        // v2rayNG 10808 / 通用 1080
        val expect = listOf(7890, 7891, 7897, 10808, 1080)
        assertEquals(expect, LOCAL_SOCKS_PROBE_PORTS.toList())
        // 顺序即优先级：Clash 系在前（用户群最常见），v2rayNG 次之
        assertEquals(7890, LOCAL_SOCKS_PROBE_PORTS.first())
    }

    @Test
    fun `网络错误文案单一真源_含明确指引`() {
        // 实锤③要求：网络不可达时给「无法连接到 Telegram 服务器」的明确提示（非静默）
        assertEquals("无法连接到 Telegram 服务器，请检查网络/代理设置", AUTH_NET_ERROR_TEXT)
    }
}
