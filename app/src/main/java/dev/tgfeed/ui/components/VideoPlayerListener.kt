package dev.tgfeed.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 播放器监听（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：状态迁移（BUFFERING 读写 + READY 自愈复位 + ENDED 三分支）、
 * 硬错误分诊（R78 fatalReasonOf + R86 内容损坏换源重下）、
 * 真实画面尺寸上报（onVideoSizeChanged）、首帧撤离封面标记（onRenderedFirstFrame）。
 * 可变状态以 [MutableState] 引用传入（页面侧与拆出件操作同一对象，语义与旧"委托闭包"一致）；
 * itemNow/loopOneNow 以 [State] 现读最新值（原 rememberUpdatedState 口径）。
 * R138 增设本文件尾部的点赞回写状态观测板（[LikeWritebackBoard]，design-redesign P0-③）：
 * 播放页的回写派发与状态查询统一收口，VideoPage 只接线不持状态。
 */
@Composable
internal fun VideoPlayerListenerEffect(
    player: Player?,
    gateway: TdGateway,
    feedVm: FeedViewModel,
    itemNowState: State<VideoItem>,
    loopOneNowState: State<Boolean>,
    onEnded: () -> Unit,
    gestureScope: CoroutineScope,
    bufferingState: androidx.compose.runtime.MutableState<Boolean>,
    loadFailedState: androidx.compose.runtime.MutableState<Boolean>,
    failCountState: androidx.compose.runtime.MutableState<Int>,
    retryPendingState: androidx.compose.runtime.MutableState<Boolean>,
    fatalReasonState: androidx.compose.runtime.MutableState<String?>,
    retryItemState: androidx.compose.runtime.MutableState<VideoItem>,
    pausedState: androidx.compose.runtime.MutableState<Boolean>,
    corruptRetryCountState: androidx.compose.runtime.MutableState<Int>,
    playUriState: androidx.compose.runtime.MutableState<String?>,
    retryTickState: androidx.compose.runtime.MutableState<Int>,
    playedVideoRatioState: androidx.compose.runtime.MutableState<Float>,
    firstFrameSeenState: androidx.compose.runtime.MutableState<Boolean>,
    appliedVariantFileIdState: androidx.compose.runtime.MutableState<Int?>,
) {
    val itemNow by itemNowState
    val loopOneNow by loopOneNowState
    var buffering by bufferingState
    var loadFailed by loadFailedState
    var failCount by failCountState
    var retryPending by retryPendingState
    var fatalReason by fatalReasonState
    var retryItem by retryItemState
    var paused by pausedState
    var corruptRetryCount by corruptRetryCountState
    var playUri by playUriState
    var retryTick by retryTickState
    var playedVideoRatio by playedVideoRatioState
    var firstFrameSeen by firstFrameSeenState
    var appliedVariantFileId by appliedVariantFileIdState
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // R80 埋点：播放器状态迁移（IDLE/BUFFERING/READY/ENDED）
                dev.tgfeed.core.playback.PlaybackTrace.log(
                    "state", itemNow.key,
                    when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "state$state"
                    },
                )
                if (state == Player.STATE_READY) {
                    // 播起来了：失败态自愈 + 自动重试链痊愈
                    //（失败计数/退避标记/不可重试病因清零，换路副本回到最新原条目）
                    loadFailed = false
                    failCount = 0
                    retryPending = false
                    fatalReason = null
                    retryItem = itemNow
                }
                if (state == Player.STATE_ENDED) {
                    when {
                        loopOneNow -> {
                            // 单条循环：不上抛 onEnded 跳页，原地 seekTo(0) 重播
                            p.seekTo(0)
                            p.play()
                            paused = false
                        }
                        // 回看豁免（修"往回翻被自动再弹走"）：本条此前被自动连播弹走过、
                        // 用户手动翻回来了（VM 豁免集命中）——再播完也绝不自动跳页，
                        // 原地归零定格待用户点播（单击即从头重播，见 currentOnTap）
                        feedVm.isAutoAdvanceExempt(itemNow.key) -> {
                            android.util.Log.i(
                                "TGFlow",
                                "回看豁免生效：原地定格不重弹 …${itemNow.key.takeLast(12)}",
                            )
                            p.seekTo(0)
                            p.pause()
                            paused = true
                        }
                        // R142 追剧模式：自动连播开且本条在剧集链上还有下一集 →
                        // 跳链内下一集（同频道，识别纯函数见 EpisodeChain.kt；跳集
                        // 动作在 FeedViewModel.advanceEpisodeChain：连播记账 +
                        // resumeScroll 滚动，logcat 出"剧集连播：第 N→M 集"）。
                        // 连播开关直查 VM StateFlow 现值——本 listener 挂在
                        // DisposableEffect(player) 里，闭包参数会停在挂监听那一刻，
                        // 开关翻转后不现读就是旧值。链尾/不在链上/目标已淘汰 → 返回
                        // false，回落下方 onEnded（FeedScreen 的 Feed 顺序连播），行为不劣于旧版
                        feedVm.autoAdvance.value && feedVm.advanceEpisodeChain(itemNow) -> Unit
                        else -> onEnded()
                    }
                }
            }

            // 播放器硬错误（源不可读/解码失败/协议错误等）：旧实现没人接这个回调——
            // 出错后状态停在 IDLE，buffering=false，转圈消失只剩一块黑画面，
            // 看门狗又因"不在缓冲"袖手旁观——"转几圈就停然后全黑"的真凶。
            // R78 重试分诊：先辨病因（fatalReasonOf）——解码失败/格式不支持/资源已失效
            // 这类稳定终态自动重试救不了，直接进失败终态给"换一条看"引导，不假装在努力；
            // 只有真网络类（断连/超时/限流/回环流 500/503 抖动）才进自动退避重试链
            //（与看门狗卡死同一入口，全不用用户点）；
            // retryPending 期间不重复记（错误回调可能连发），4 次全败才转失败终态
            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                val fatal = fatalReasonOf(error)
                // R324 离线分诊（R78 家族新成员）：真网络类病因撞上 NetworkWatch 离线
                // ——离线是「此刻不可重试」的稳定病因（退避重试在离线期=45s 假努力，
                // 违反「不假装在努力」）；并入 fatal 直达终态，文案由 OfflineDegradePolicy
                // 单源出。环境态≠内容坏：离线终态不记死链（recordDeadMedia 只在真
                // fatal 走——零编造红线）
                val offlineNow = !dev.tgfeed.core.net.NetworkWatch.online.value
                val fatalFinal = fatal
                    ?: dev.tgfeed.core.net.OfflineDegradePolicy.playerOfflineFatalText(offlineNow)
                // R80 埋点：失败错误码 + 分诊结果 + 离线标记 + 底层原因（复盘格式错误/误判的关键现场）
                dev.tgfeed.core.playback.PlaybackTrace.log(
                    "err", itemNow.key,
                    "code=${error.errorCode} fatal=${fatalFinal ?: "可重试"} offline=$offlineNow " +
                        "cause=${error.cause?.javaClass?.simpleName}(${error.cause?.message?.take(60)})",
                )
                // R86 内容损坏换源重下（修偶发 3001 Invalid NAL 的残留路径）：
                // 这类错误在并发下载重压下多是 TDLib 侧瞬态内容错乱——R81 复盘实锤：
                // 那次 3001 前泵闸全程静默、块块「验证通过」，坏字节是过了所有闸的，
                // 应用层无法逐字节证真伪，作废本地下载内容强制重拉一次往往即愈。
                // 同条目仅给一次换源重下（corruptRetryCount 计数封顶），二连击才当真坏
                // 文件落 fatalReason 终态（防真坏文件无限重下）；同现场的错误回调连发
                // 由计数封顶 + 下方终态分支的「重下在途不判终态」闸共同挡住
                // R324：离线期跳过换源重下（重下要网络，离线发出去就是必然失败的假努力）
                if (!offlineNow && isCorruptContentError(error) && corruptRetryCount < CORRUPT_RETRY_MAX) {
                    val lib = gateway as? TdLibGateway
                    val itemAtErr = itemNow
                    // 实际在播的 fileId：流地址里带着最准（可能是清晰度变体档），
                    // 本地裸路径回落到已应用变体档/主档
                    val fid = playUri
                        ?.let {
                            Regex("""^http://127\.0\.0\.1:\d+/v/(\d+)""")
                                .find(it)?.groupValues?.get(1)?.toIntOrNull()
                        }
                        ?: appliedVariantFileId?.takeIf { it > 0 }
                        ?: itemAtErr.fileId
                    if (lib != null && fid >= 0) {
                        corruptRetryCount++
                        retryPending = true
                        failCount = 0 // 接管在途的网络退避重试（若有）：内容损坏是更强病因
                        // 闸动静复盘：该文件近期有闸命中 = 闸在拦但漏网；静默 = 口径盲区
                        val gateAt = dev.tgfeed.core.td.TgStreamServer.lastIntegrityGateMs(fid)
                        val gateInfo = if (gateAt != null) {
                            "闸最近命中=${android.os.SystemClock.elapsedRealtime() - gateAt}ms前"
                        } else {
                            "闸全程静默"
                        }
                        android.util.Log.i(
                            "TGFlow",
                            "内容损坏换源重下（code=${error.errorCode}）：fileId=$fid $gateInfo " +
                                "…${itemAtErr.key.takeLast(12)}",
                        )
                        dev.tgfeed.core.playback.PlaybackTrace.log(
                            "srcRetry", itemAtErr.key,
                            "code=${error.errorCode} f=$fid $gateInfo 强制重下重播",
                        )
                        gestureScope.launch {
                            // 先作废重下再 bump retryTick：确保装填 effect 的 ensurePlayable
                            // 读到删除后的新鲜状态（否则可能拿回旧的坏本地路径/假完成态）
                            lib.forceRedownloadVideo(fid)
                            retryItem = itemAtErr.copy(playUri = null)
                            playUri = null
                            retryTick++
                            retryPending = false // 重试已发出：看门狗重新盯梢这次重播
                        }
                        return
                    }
                }
                if (fatalFinal != null) {
                    // R86：换源重下在途期间的同类连发回调不判终态（同一损坏现场的重发，
                    // 错误回调可能连发——没有这道闸，首次 3001 的连发会当场把
                    // 刚发出的换源重下误判成二连击终态）
                    if (retryPending && corruptRetryCount > 0 && isCorruptContentError(error)) return
                    android.util.Log.i(
                        "TGFlow",
                        "不可重试失败直接终态（code=${error.errorCode}）：$fatalFinal …${itemNow.key.takeLast(12)}",
                    )
                    // R163 B5 死媒体记账：终态即死链证据（R158 §⑦ 实锤错误卡连续 8+ 条
                    // 的聚合源头）——per-chatId 记 deadmedia 负反馈，纯内存毫秒级；
                    // 消费：频道死链率降权（DeadMediaPolicy，默认关）+ 丰度分负分量（B4）。
                    // 同一现场错误回调连发已被上方终态闸挡住，这里不会重复记账。
                    // R324：离线终态是环境态不是内容坏——不记死链（离线即愈的失败
                    // 不是该频道的错，记了就是编造负反馈）
                    if (fatal != null) {
                        feedVm.recordDeadMedia(itemNow.chatId)
                    }
                    fatalReason = fatalFinal
                    loadFailed = true
                    retryPending = false
                    return
                }
                if (!retryPending) {
                    retryPending = true
                    failCount++
                }
            }

            // 真实画面尺寸上报：方向判定的最高可信源（旋转/像素宽高比已修正，
            // 与 PlayerView 内部装帧同一套算法，见 displayRatioOf）
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playedVideoRatio = displayRatioOf(videoSize)
            }

            // R93：首帧真正画进渲染面才撤封面——加载→播放平滑过渡的唯一可信时点。
            // 换渲染面（翻页回来 PlayerView 重建）、换源、出错重拉后本回调都会重发，
            // 与装填/重试分支里的 firstFrameSeen 复位配对，全程无黑窗
            override fun onRenderedFirstFrame() {
                firstFrameSeen = true
            }
        }
        p.addListener(listener)
        // 监听挂上时尺寸可能早已就绪（池内复用/重组不重发回调）：现读一次兜底
        playedVideoRatio = displayRatioOf(p.videoSize)
        onDispose { p.removeListener(listener) }
    }
}

// ---------------- 点赞回写状态观测（R138，design-redesign.md §2.3 P0-③） ----------------

/**
 * 本条点赞回写的会话内状态。口径（与网关 R128 实现严格同源）：
 * 取消点赞不回撤、同一条本会话只发一次、失败静默不外抛——
 * 状态板把"看不见的回写"翻译成可查询的态（已送达/未开启/失败静默/尚无记录），
 * 供长按点赞钮的轻量出口展示。
 */
internal enum class LikeWritebackStatus {
    /** 本会话这条还没有回写动作（未点赞过 / 点赞发生在回写开启前） */
    NOT_LIKED,
    /** 回写总闸未开启：点赞只留在本机 */
    OFF,
    /** 已发出、网关受理结果未回（飘心 👍 已混入，送达胶囊待结果） */
    IN_FLIGHT,
    /** 网关已受理：👍 已到原频道，在 Telegram 里公开可见 */
    DELIVERED,
    /** 发送失败（静默口径）：频道不开放表情回应/网络抖动等，本地点赞不受影响 */
    FAILED,
}

/**
 * 点赞回写观测板：进程级（会话内）状态表 + 派发收口。
 *
 * 为什么是进程级静态表而不是 per-VideoPage remember：翻页会销毁重建 VideoPage，
 * 而回写是会话语义（网关侧 likeReactionSent 也是会话账）——划走再划回，
 * "已送达"不该凭空变回"没有记录"。Compose 的全局快照状态放在静态表里照常驱动重组。
 *
 * 派发规则（与 R128 网关口径零冲突，只是把原来 VideoPage 里裸发的一发收进观测）：
 *  - 总闸关：记 OFF 不发（网关内部本来也有同一道闸，双保险）；
 *  - 在途/已送达：不重发（幂等）；
 *  - Mock 演示网关：无回写通道，保持原状不动（状态面板会如实提示演示模式）；
 *  - 其余：IO 协程发 sendLikeReaction，受理=true → DELIVERED，false → FAILED。
 */
internal object LikeWritebackBoard {
    /** 会话内回写状态表："chatId:messageId" → 可观测状态（与网关 likeReactionSent 同键口径） */
    private val states = java.util.concurrent.ConcurrentHashMap<String, MutableState<LikeWritebackStatus>>()

    /**
     * 场景化引导本会话是否已展示过一次（R138）：用户对引导不作选择（8 秒淡出）时
     * 不在本次启动里反复打扰——"一次性"按会话计；冷启动后仍未决定才再现。
     * 做出选择（仅此点赞/开启回写/设置页动总闸）则落 prefs 决策位，永不再现。
     */
    @Volatile
    internal var guideShownThisSession: Boolean = false

    /** 取本条的可观测回写状态（无记录时懒建 NOT_LIKED；跨页重建取到的是同一对象） */
    fun stateOf(item: VideoItem): MutableState<LikeWritebackStatus> =
        states.getOrPut("${item.chatId}:${item.messageId}") { mutableStateOf(LikeWritebackStatus.NOT_LIKED) }

    /**
     * 派发一次回写（双击"未赞→赞"时调用）。只改状态、不做任何 UI——
     * 可见反馈（👍 粒子混入/送达胶囊）由调用侧按状态自取。
     * scope 请传"活得比页面组合久"的作用域（VideoPage 传 activity 级 FeedViewModel
     * 的 viewModelScope）：模拟器实测抓到过"点赞后立刻划走 → 页面 gestureScope
     * 已随组合销毁 → 回写协程被取消、网关侧记一笔静默失败"的现场——
     * 回写是会话语义，不应跟着单页组合陪葬。
     */
    fun dispatch(gateway: TdGateway, scope: CoroutineScope, item: VideoItem, prefs: AppPrefs) {
        val st = stateOf(item)
        if (!prefs.likeReactionWriteback) {
            st.value = LikeWritebackStatus.OFF
            return
        }
        if (st.value == LikeWritebackStatus.IN_FLIGHT || st.value == LikeWritebackStatus.DELIVERED) return
        val real = gateway as? TdLibGateway ?: return
        st.value = LikeWritebackStatus.IN_FLIGHT
        scope.launch(Dispatchers.IO) {
            // 网关内仍有三重闸：总开关现查 + 同一条会话幂等 + 失败静默只记日志
            val accepted = real.sendLikeReaction(item.chatId, item.messageId)
            st.value = if (accepted) LikeWritebackStatus.DELIVERED else LikeWritebackStatus.FAILED
        }
    }
}
