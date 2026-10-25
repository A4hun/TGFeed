package dev.tgfeed.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R324 离线检测件（弱网离线可玩——离线降级矩阵的单一出处）。
 *
 * 语义：online = 系统当前有默认网络（ConnectivityManager 的默认网络在场性）。
 * 注意这是「系统认为有默认网络」而非「互联网真的可达」——诚实口径：本件只报
 * 系统链路事实；TG 链路通不通另有真源（TdLibGateway.connectionState，登录后才有
 * 意义，语义正交不合并）。UI 消费面把它当「离线降级的触发信号」，不当「绝对
 * 在线保证」——离线判定只用于诚实标注与免烧假重试，不拦截任何既有动作。
 *
 * 信号链收敛（纪律§一 Q3，R195 同款手术）：系统默认网络回调原本长在
 * NetworkMeteredBus（R195 从 FeedViewModel 上移而来）——本件成立后回调所有权
 * 移交至此（全进程唯一系统默认网络监听者的不变式保持），NetworkMeteredBus 退化为
 * 「计量口径视图」（公开面 state/metered/readMeteredNow/start 零变化，内部委托
 * 本件）——两个网络检测现场收敛成一个，以后一切「有网/无网/是否计量」都从
 * 这里读。
 *
 * 状态流：NetLinkState(online, metered, atMs)——atMs = 本状态落定时刻（状态保持
 * 期间不漂移）。装机点：App.onCreate 的 NetworkMeteredBus.start 链式带起（幂等）；
 * FeedViewModel.init 的兜底 start 同链。零锁（StateFlow 原子写）；异常一律回落
 * 「无默认网络」的诚实初值。
 */
object NetworkWatch {

    private val _state = MutableStateFlow(NetLinkState(online = false, metered = false, atMs = 0L))

    /** 离线降级矩阵的订阅口（online+metered+落定时刻） */
    val state: StateFlow<NetLinkState> = _state.asStateFlow()

    private val _online = MutableStateFlow(false)

    /** 便捷订阅口：系统有无默认网络（VM/UI 消费） */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _metered = MutableStateFlow(false)

    /** 便捷订阅口：当前默认网络是否计量（NetworkMeteredBus 的口径镜像源） */
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    @Volatile
    private var started = false

    /** 纯状态机（事件归约在 core 内，Android 侧只做接线）——JVM 单测锚挂这 */
    internal val core = NetworkWatchCore(initialOnline = false, initialMetered = false)

    /** 装机（App.onCreate 经 NetworkMeteredBus.start 链式调用；幂等。注册系统回调+初值现读） */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        runCatching { publish(core.reseed(readOnlineNow(app), NetworkMeteredBus.readMeteredNow(app))) }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        publish(core.onDefaultAvailable())
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        publish(
                            core.onCapabilitiesChanged(
                                notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                            ),
                        )
                    }

                    override fun onLost(network: Network) {
                        // 默认网络丢失（切网/断网）：按活动网络现值重估（R195 onLost 同语义
                        // 迁移——另一网络可能已顶上默认位，也可能真的没了）
                        publish(
                            core.onDefaultLost(
                                onlineNow = readOnlineNow(app),
                                meteredNow = NetworkMeteredBus.readMeteredNow(app),
                            ),
                        )
                    }
                })
        }
    }

    private fun publish(s: NetLinkState) {
        _state.value = s
        _online.value = s.online
        _metered.value = s.metered
    }

    /** 现读一次系统有无默认网络（start 初值与 onLost 重估共用；异常回落 false=诚实无网） */
    fun readOnlineNow(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val net = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}

/** 网络链路态快照：online=有默认网络；metered=默认网络计量；atMs=本状态落定时刻毫秒 */
data class NetLinkState(
    val online: Boolean,
    val metered: Boolean,
    val atMs: Long,
)

/**
 * 网络态归约纯件（JVM 单测锚 NetworkWatchCoreTest）：
 * 系统默认网络回调事件 → NetLinkState。无事件不变（同值不换时刻戳——状态保持
 * 期间 atMs 不漂移）；事件按「可用/能力变化/丢失重估」三类归约。
 */
class NetworkWatchCore(
    initialOnline: Boolean,
    initialMetered: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var _state = NetLinkState(online = initialOnline, metered = initialMetered, atMs = nowMs())

    /** 当前态（只读视图） */
    val state: NetLinkState get() = _state

    private fun transit(online: Boolean, metered: Boolean): NetLinkState {
        if (_state.online == online && _state.metered == metered) return _state
        _state = NetLinkState(online, metered, nowMs())
        return _state
    }

    /** 默认网络出现：online 置真（计量口径沿用现值——能力回调随后会带真值来） */
    fun onDefaultAvailable(): NetLinkState = transit(online = true, metered = _state.metered)

    /** 默认网络能力变化：默认网络在场即 online 置真；计量随 NET_CAPABILITY_NOT_METERED 取反 */
    fun onCapabilitiesChanged(notMetered: Boolean): NetLinkState =
        transit(online = true, metered = !notMetered)

    /** 默认网络丢失：按系统现值重估（onLost 时刻可能有新默认网络顶上） */
    fun onDefaultLost(onlineNow: Boolean, meteredNow: Boolean): NetLinkState =
        transit(online = onlineNow, metered = meteredNow)

    /** 初值重播种（start 时刻的现读结果；装机前零事件也要有诚实现值） */
    fun reseed(onlineNow: Boolean, meteredNow: Boolean): NetLinkState =
        transit(online = onlineNow, metered = meteredNow)
}
