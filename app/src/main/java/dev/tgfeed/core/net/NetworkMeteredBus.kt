package dev.tgfeed.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.StateFlow

/**
 * R195 计量网络进程级总线（「单源多读」共享件）。
 *
 * R324 起本总线退化为「计量口径的视图」：系统默认网络回调所有权移交
 * [NetworkWatch]（离线检测件——弱网离线可玩的单一出处），全进程「系统默认网络
 * 回调只有一个监听者」的 R195 不变式**保持不变**（只是监听者从本总线换成
 * NetworkWatch）。本总线公开面（state/metered/readMeteredNow/start 四口）签名与
 * 语义零变化——既有消费方（FeedViewModel/GatewayAggressivePreload/FeedOcrPart/
 * IndexCrawlGate）口径不动；内部改为委托 NetworkWatch（计量真值随 NetLinkState
 * 一起由同一回调归约）。
 *
 * 语义（不变）：当前默认网络是否计量（移动数据等按流量计费链路=true；WiFi/无
 * 网络=false）。无网络视为非计量（此刻也播不了）。异常一律回落 false。
 */
object NetworkMeteredBus {

    /** 订阅口（VM 等需要流语义的消费者；真值源=NetworkWatch.metered） */
    val state: StateFlow<Boolean> get() = NetworkWatch.metered

    /** 便捷读（泵/整载器等 1Hz 轮询的消费者；StateFlow.value 读取，无分配） */
    val metered: Boolean get() = NetworkWatch.metered.value

    /**
     * 装机（App.onCreate 调用；幂等。链式带起 NetworkWatch——系统回调注册在那边；
     * FeedViewModel.init 的兜底 start 走同一条链）
     */
    fun start(context: Context) {
        runCatching { NetworkWatch.start(context) }
    }

    /** 现读一次默认网络是否计量（NetworkWatch 初值/onLost 重估共用；异常回落 false）。
     *  保持自持实现（不经 NetworkWatch——纯读函数无环） */
    fun readMeteredNow(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}
