package dev.tgfeed.core.net

/**
 * R195 App 前后台进程级共享信号（@Volatile 单写多读）。
 *
 * 动机：网关侧 WiFi 激进整载器的 B 档（下一条预载）需要前台闸——App 退后台后
 * 不再对新条目发起整载（R158 P0-2 后台静默哲学：退后台的视频预载是后台电量/
 * 流量杀手）。前台信号原本长在 FeedViewModel.appInForeground（MainActivity
 * onStart/onStop 写入），网关够不到。
 *
 * 信号链收敛（纪律§一 Q3）：FeedViewModel 的 appInForeground 属性改为**直通本
 * 总线**的读写代理——MainActivity 既有写入语句原样生效（写穿透到总线），VM 各
 * 消费面（追更轮询/IndexCrawlGate/WiFi 预载）读口径零改动；网关侧与本总线共享
 * 同一真值，全进程单源。
 */
object AppForeground {

    /** App 是否在前台（默认 true；MainActivity onStop/onStart 经 VM 直通属性置位） */
    @Volatile
    var inForeground: Boolean = true
}
