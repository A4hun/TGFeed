package dev.tgfeed

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.feed.visibilityGate
import dev.tgfeed.ui.AuthScreen
import dev.tgfeed.ui.FeedScreen
import dev.tgfeed.ui.theme.TgFeedTheme

class MainActivity : ComponentActivity() {

    // 后台行为分派：0=继续播放（audioBackground 开则前台服务保活，回前台即停）/ 1=暂停 / 2=自动弹悬浮窗
    override fun onStop() {
        super.onStop()
        val container = (application as App).container
        // 记忆播放：退后台先落盘当前条进度（与 backgroundAction 无关，无条件保存）——
        // 这是"退后台后被杀进程"前的最后保存机会；实现侧同步写盘，不依赖异步协程
        container.saveCurrentProgress()
        // H2：退后台标记——时间线轮询暂停（R229 起；R239 时间线=首页 tab 同语义）
        feedVm()?.appInForeground = false
        // 音频卡（AudioCard 自管播放器）后台暂停：按"退后台实际按暂停处理"口径
        // （R158：0 档未开后台音频时也兜底暂停——音频卡裸续播同属后台电量杀手）
        container.audioCardsPaused.value = container.prefs.effectiveBackgroundPause
        // —— PiP 分派（优先于常规后台分派）——
        if (pipSessionActive) {
            if (isInPictureInPictureMode) {
                // 仍在画中画（典型：PiP 中熄屏/被遮蔽）：PiP 小窗用户可见，视频继续播，
                // 跳过常规后台分派——不落闸、不起音频 FGS、不自动弹 App 内悬浮窗
                android.util.Log.i("BGGate", "onStop: PiP 进行中，跳过后台分派")
                return
            }
            // 曾处 PiP 且现在已不在 = 用户关掉了桌面小窗：关掉小窗=停播。
            // 先解除闸豁免再落闸，闸的一刀切才能真正落到悬浮池
            pipSessionActive = false
            dev.tgfeed.core.playback.PlayerPool.setPipExempt(false)
            container.pauseAllPlayback()      // Feed 池 + 共享音频（双保险；popout 场景下本就停着）
            container.pauseFloatingPlayback() // 悬浮池：桌面小窗的播放源
            // 闸按"退后台实际按暂停处理"照常落下（R158 起含 0 档未开后台音频的兜底暂停）：
            // 防迟到的播放触发（翻页/装填回调）在关窗后重新出声
            dev.tgfeed.core.playback.PlayerPool.setBackgroundGate(container.prefs.effectiveBackgroundPause)
            android.util.Log.i("BGGate", "onStop: PiP 小窗已关闭，停播")
            return
        }
        android.util.Log.i(
            "BGGate",
            "onStop: backgroundAction=${container.prefs.backgroundAction} " +
                "audioBackground=${container.prefs.audioBackground} " +
                "pauser注册=${container.currentPlayerPauser != null}",
        )
        when (container.prefs.backgroundAction) {
            1 -> container.pauseAllPlayback()
            2 -> container.popoutCurrent()
            else -> {
                if (container.prefs.audioBackground) {
                    // P1-9：onStop 里 startForegroundService 在 targetSdk 34 下不属于后台
                    // 启动豁免清单——待机桶降级/OEM 收紧时抛 ForegroundServiceStartNotAllowedException，
                    // 在 onStop 里抛出即闪退。包 runCatching，失败降级为暂停播放（合规兜底，
                    // 宁可静音也不崩）
                    val started = runCatching {
                        dev.tgfeed.core.playback.AudioBackgroundService.start(this)
                    }.onFailure {
                        android.util.Log.w("MainActivity", "后台音频 FGS 启动被拒，降级为暂停播放: ${it.message}")
                    }.isSuccess
                    if (!started) container.pauseAllPlayback()
                } else {
                    // R158 后台电量兜底（改默认行为）：0 档"继续播放"的唯一合规承载是
                    // audioBackground=true 的前台服务；未开启时旧行为=onStop 什么都不做，
                    // 退后台视频在黑屏里继续解码+下载+出声（体检实锤 CPU 12~124%、
                    // 网络 ~220KB/s 持续）——按暂停兜底。回前台由 onStart 的
                    // effectiveBackgroundPause 分支自动续播"被闸暂停的"内容（与 1 档同链路）
                    android.util.Log.i("BGGate", "onStop: 继续播放档未开后台音频，兜底暂停（R158 电量修复）")
                    container.pauseAllPlayback()
                }
            }
        }
        // 后台闸在分派之后落下（仅"实际按暂停处理"档）：一次性 pauseAllPlayback 挡不住
        // onStop 之后才到的播放触发（迟到 playUri 装填/翻页落定/清晰度热切都会后置
        // playWhenReady=true），闸落下期间由 PlayerPool 监听器当场打回；onStart 抬闸。
        // 必须在分派之后：落闸会一刀切暂停全部在册播放器，若先于 pauser 执行会把
        // "是否真在播"的快照全打成 false，回前台的续播清单就永远为空（实测踩坑）；
        // onStop 全程主线程同步执行，分派与落闸之间不可能插入播放触发，顺序安全
        dev.tgfeed.core.playback.PlayerPool.setBackgroundGate(container.prefs.effectiveBackgroundPause)
    }

    override fun onStart() {
        super.onStart()
        val container = (application as App).container
        // H2：回前台标记——时间线轮询恢复（R229 起；R239 时间线=首页 tab 同语义）
        feedVm()?.appInForeground = true
        container.audioCardsPaused.value = false
        dev.tgfeed.core.playback.AudioBackgroundService.stop(this)
        // 抬闸必须先于续播：闸还落着时 resumeAllPlayback 的 play() 会被闸当场打回
        dev.tgfeed.core.playback.PlayerPool.setBackgroundGate(false)
        // 退后台"实际按暂停处理"（R158：1=暂停档，或 0 档未开后台音频的兜底暂停）时：
        // 回前台自动续播"被后台闸暂停的"播放（用户手动暂停的不动）。
        // 弹悬浮窗（2）模式不调：悬浮窗在播时恢复页内播放会双重出声；
        // 0 档+后台音频开着（FGS 承载续播）也不调——AudioBackgroundService 上方已停
        if (container.prefs.effectiveBackgroundPause) container.resumeAllPlayback()
    }

    // ---------------- 系统画中画（PiP，真悬浮窗） ----------------
    //
    // 场景边界（与 App 内悬浮窗 popout 叠加共存，互相不替代）：
    //  - 弹着 App 内悬浮窗退后台 → 进 PiP，视频在桌面小窗续播；
    //  - 没弹悬浮窗退后台 → 不拦，走既有"后台播放设置"分派（该停就停）；
    //  - 音频卡不进 PiP——退后台继续走 MediaSession 通知栏路线
    //   （pipCandidateProvider 只认悬浮视频窗，见 FloatingPlayerHost）。
    //
    // 生命周期事实：进 PiP 后 Activity 只 pause 不 stop（onStop 不触发），
    // configChanges 已声明齐全，组合与播放器全程存活，视频不断流。

    /**
     * PiP 会话活跃标记：onPictureInPictureModeChanged(true) 置位；
     * onResume（点回全屏）/ onStop（关掉小窗，已消费）清除。
     * 用于在 onStop 区分"用户关掉 PiP 小窗"与"PiP 中熄屏"（后者 isInPictureInPictureMode 仍 true）
     */
    private var pipSessionActive = false

    /**
     * 用户按 Home/切任务时回调（Activity 尚可见，是进系统画中画的可靠入口时机；
     * onStop 里再调 enterPictureInPictureMode 已太晚，部分机型直接抛异常）。
     * 熄屏/被其他 Activity 覆盖不触发本回调，不会误进 PiP。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    /** 尝试进系统画中画：仅当悬浮窗栈里有视频正在播放（候选由 FloatingPlayerHost 提供） */
    private fun maybeEnterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // PiP 避让：系统悬浮窗在飞时不再进画中画——两者同为桌面悬浮，功能重复；
        // 悬浮窗由前台服务保活，退桌面续播不依赖 PiP
        if (dev.tgfeed.ui.floating.SystemFloatingController.hasActiveWindows()) return
        val item = container.pipCandidateProvider?.invoke() ?: return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(pipAspectRatio(item))
                    .build(),
            )
        }.onFailure {
            // 进 PiP 失败（如厂商限制/瞬时状态不允许）：静默回落，onStop 走常规后台分派
            android.util.Log.w("MainActivity", "进入画中画失败: ${it.message}")
        }
    }

    /**
     * PiP 小窗宽高比：跟随视频真实尺寸。系统合法区间约 0.418~2.39，
     * 越界钳制（setAspectRatio 越界直接抛 IllegalArgumentException）；未知尺寸回落 16:9
     */
    private fun pipAspectRatio(item: dev.tgfeed.core.td.VideoItem): Rational {
        val w = item.width
        val h = item.height
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val r = w.toFloat() / h.toFloat()
        return when {
            r < 0.418410f -> Rational(41841, 100000) // 系统下限（竖长条压到最窄）
            r > 2.390000f -> Rational(239, 100)      // 系统上限（宽扁压到最宽）
            else -> Rational(w, h)
        }
    }

    /**
     * 进出系统画中画：进 = Compose 切"只留视频画面"（isInPipMode → 悬浮层满窗纯视频，
     * 藏全部 UI 控件/进度条/按钮），同时豁免 PlayerPool 后台闸（PiP 小窗用户可见，
     * 是继续播放场景）；出（点回全屏）= 状态翻回，完整 UI 即时恢复，后台纪律复原。
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.isInPipMode.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) pipSessionActive = true
        dev.tgfeed.core.playback.PlayerPool.setPipExempt(isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        // 从 PiP 点回全屏：onPictureInPictureModeChanged(false) 后接 onResume（无 onStop），
        // PiP 会话结束，清除标记（UI 已由 isInPipMode=false 恢复）
        pipSessionActive = false
        // 悬浮窗权限引导回流：从系统「显示在其他应用上」设置页返回后重查授权态；
        // 新授权成功时，FloatingPlayerHost 下一轮同步会把 App 内浮层迁成系统悬浮窗
        dev.tgfeed.ui.floating.SystemFloatingController.refreshOverlayPermission(this)
    }

    /**
     * 取 activity 作用域的共享 FeedViewModel（与 FeedScreen/GridScreen 同一实例）。
     * R52：只查不建——旧实现 ViewModelProvider.get（无 key 形态）在冷启动 onStart 就会
     * 凭空造出一个无人消费的"默认 key"VM（与 FeedScreen 的 "gw"+hash key 实例不是同一个），
     * 其 init 协程把收藏/历史/统计等整套磁盘预载 + 收藏复活体检（GetMessage 网络）
     * 全部白跑一遍，挤占启动关键期的 IO 与 TDLib 请求额度；现按同款 key 现查，没有即 null。
     */
    private fun feedVm(): dev.tgfeed.core.feed.FeedViewModel? {
        val container = (application as App).container
        return runCatching {
            viewModelStore.get("gw" + System.identityHashCode(container.gateway.value))
        }.getOrNull() as? dev.tgfeed.core.feed.FeedViewModel
    }

    // ---------------- t.me / tg:// 深链接链（系统选择器 → 本 Activity） ----------------

    /**
     * t.me / tg:// 深链的解析目标（parseDeepLink 产出，routeDeepLink 消费）。
     * 解析在 intent 到达时同步完成（纯字符串处理），网络动作全部推迟到网关 Ready 之后。
     */
    private sealed interface DeepLinkTarget {
        /** t.me/<name>：公开频道 → 切 ChannelFeedSource 开刷 */
        data class Channel(val username: String) : DeepLinkTarget

        /**
         * t.me/<name>/<msgId>：帖子 → 开频道 Feed 并锚定定位到该条。
         * messageId 已按 [TDLIB_MSG_ID_FACTOR] 换算成 TDLib 内部 id（换算依据见 companion 注释），
         * 不是链接里的裸序号
         */
        data class Post(val username: String, val messageId: Long) : DeepLinkTarget

        /**
         * t.me/c/<internalId>[/<msgId>]：私有频道内部链（chatId 已按 -100 前缀口径换算，
         * msgId 已按 [TDLIB_MSG_ID_FACTOR] 换算），已加入才能开
         */
        data class Private(val chatId: Long, val messageId: Long?) : DeepLinkTarget

        /** t.me/+xxx / t.me/joinchat/xxx：邀请链接 → CheckChatInviteLink/JoinChatByInviteLink */
        data class Invite(val url: String) : DeepLinkTarget
    }

    /** 冷启动/未登录时暂存的待路由深链（进程重建后由 onCreate 从 intent 重新解析，无需持久化） */
    private var pendingDeepLink: DeepLinkTarget? = null

    /** 深链"等网关 Ready"协程幂等闸：多个深链连续到达时只保留一个等待协程，后者覆盖前者 */
    private var deepLinkDispatching = false

    /** 本 Activity 的容器快捷访问（各处现取，避免 onCreate 局部变量传递） */
    private val container: AppContainer get() = (application as App).container

    private companion object {
        /** 深链全链路观测 tag（与 TdLibGateway 深链解析同一 tag，一条 logcat -s 过滤即可分诊） */
        const val TAG_FLOW = "TGFlow"

        /** TG 公开用户名规则：4~32 位 [A-Za-z0-9_]（与 FeedScreen.parseTmeTarget 同一口径） */
        val TME_USERNAME_REGEX = Regex("[A-Za-z0-9_]{4,32}")

        /**
         * 链接消息序号 → TDLib 内部 message.id 的换算因子（2^20）。
         * 实证（2025-10-08 平板 logcat，TdLibGateway 锚点日志 5/5 样本整除）：
         * TDLib Message.id = t.me 链接里的帖子序号 × 1048576——如链接序号 12191 的内部 id
         * 是 12783190016。深链若拿裸序号当 messageId 去 GetChatHistory 锚定，该 id 比频道
         * 第一条消息（序号 1 → 内部 id 1048576）还小，TDLib 查不到 → 锚定页拉空 →
         * 空态"这个源暂时没有视频"（实测翻车根因）。反向口径的既有佐证：
         * 分享链接生成侧 TdLibGateway.getMessageLink 是 TDLib 内部 id → 链接，
         * 由 TDLib 自己 ÷2^20，与本因子互逆。
         */
        const val TDLIB_MSG_ID_FACTOR = 1_048_576L
    }

    /**
     * 从 intent.data 解析 t.me / tg:// 深链目标；不支持/不认识的形态返回 null（静默忽略，
     * 不回落浏览器——用户是在咱们 App 里，再弹选择器会套娃）。
     * 与 FeedScreen.parseTmeTarget 的关系：那边是 caption 点击分流（只要用户名，链接形态
     * 已由 caption 正则粗筛）；这边是系统 intent 接链，需要 msgId/私有/邀请全形态，故独立实现。
     */
    private fun parseDeepLink(uri: android.net.Uri?): DeepLinkTarget? {
        if (uri == null) return null
        android.util.Log.i(TAG_FLOW, "深链接收: scheme=${uri.scheme} host=${uri.host} path=${uri.encodedPath}")
        val result = parseDeepLinkInner(uri)
        android.util.Log.i(TAG_FLOW, "深链解析: target=${result ?: "不支持/忽略"}")
        return result
    }

    /** parseDeepLink 的纯解析部分（不带日志，日志统一在外层出入口打） */
    private fun parseDeepLinkInner(uri: android.net.Uri): DeepLinkTarget? {
        // tg://resolve?domain=<name>&post=<msgId>（官方"View in Telegram"按钮产出）
        // 注意 scheme/host 是平台可空类型，常量在前调 equals 防 NPE
        if ("tg".equals(uri.scheme, ignoreCase = true)) {
            if (!"resolve".equals(uri.host, ignoreCase = true)) return null
            val domain = uri.getQueryParameter("domain") ?: return null
            if (!domain.matches(TME_USERNAME_REGEX)) return null
            val post = uri.getQueryParameter("post")?.toLongOrNull()
            return if (post != null && post > 0) {
                // post 是链接裸序号，×TDLIB_MSG_ID_FACTOR 换算成 TDLib 内部 messageId
                DeepLinkTarget.Post(domain, post * TDLIB_MSG_ID_FACTOR)
            } else {
                DeepLinkTarget.Channel(domain)
            }
        }
        val host = uri.host?.lowercase() ?: return null
        if (host != "t.me" && host != "www.t.me") return null
        // pathSegments 已自动剥首尾斜杠与解码；空段（双斜杠）防一手
        val segs = uri.pathSegments.filter { it.isNotBlank() }
        val head = segs.firstOrNull() ?: return null
        return when {
            // 邀请链接：t.me/+hash / t.me/joinchat/hash —— 整链原样交给 CheckChatInviteLink
            head.startsWith("+") || head.equals("joinchat", ignoreCase = true) ->
                DeepLinkTarget.Invite(uri.toString())
            // 私有频道内部链：t.me/c/<internalId>[/<msgId>] —— chatId = -(1e12 + internalId)
            //（Telegram 公开约定：链接数字是真实 chatId 剥掉 -100 前缀的部分）
            head.equals("c", ignoreCase = true) -> {
                val internalId = segs.getOrNull(1)?.toLongOrNull() ?: return null
                // msgId 同为链接裸序号，×TDLIB_MSG_ID_FACTOR 换算成 TDLib 内部 id；
                // 无 msgId 的 t.me/c/<id> 形态照常收（msgId=null，开频道 Feed 不锚定）
                val msgId = segs.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
                    ?.times(TDLIB_MSG_ID_FACTOR)
                DeepLinkTarget.Private(-(1_000_000_000_000L + internalId), msgId)
            }
            // t.me/s/<name>[/<msgId>]：网页"频道预览"形态，等价于公开频道链接
            head.equals("s", ignoreCase = true) -> {
                val name = segs.getOrNull(1) ?: return null
                if (!name.matches(TME_USERNAME_REGEX)) return null
                val msgId = segs.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
                    ?.times(TDLIB_MSG_ID_FACTOR)
                if (msgId != null) DeepLinkTarget.Post(name, msgId) else DeepLinkTarget.Channel(name)
            }
            // 公开用户名：t.me/<name>[/<msgId>]；addlist 合集不支持（无公开用户名可解析）
            head.matches(TME_USERNAME_REGEX) && !head.equals("addlist", ignoreCase = true) -> {
                val msgId = segs.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                    ?.times(TDLIB_MSG_ID_FACTOR)
                if (msgId != null) DeepLinkTarget.Post(head, msgId) else DeepLinkTarget.Channel(head)
            }
            // iv 即时预览、share/、addstickers 等其余形态：不接
            else -> null
        }
    }

    /**
     * 派发暂存的深链：网关已 Ready 直接路由；未 Ready（冷启动占位网关/TDLib 自判中/
     * 未登录）则起一个协程等 Ready。flatMapLatest 跟随容器网关实例替换（重配/演示↔真实
     * 切换时换的是新实例的 state 流），不会等在一个已被丢弃的网关对象上。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dispatchPendingDeepLink() {
        if (deepLinkDispatching) return // 已有等待协程：pendingDeepLink 总是最新值，覆盖语义
        val current = container.gateway.value
        if (current.state.value is GatewayState.Ready) {
            val target = pendingDeepLink ?: return
            pendingDeepLink = null
            routeDeepLink(current, target)
            return
        }
        deepLinkDispatching = true
        lifecycleScope.launch {
            // R52：loadLibrary/TdClient 构造挪下主线程（本协程默认跑主线程）
            withContext(Dispatchers.IO) { container.ensureGateway() } // 幂等：与 Compose LaunchedEffect 那处互为兜底
            container.gateway
                .flatMapLatest { it.state }
                .first { it is GatewayState.Ready }
            deepLinkDispatching = false
            val target = pendingDeepLink ?: return@launch
            pendingDeepLink = null
            // Ready 时容器里的网关可能已换实例，路由必须取现值
            routeDeepLink(container.gateway.value, target)
        }
    }

    /** 深链路由主入口（网关已 Ready 后调用）。网络动作挂 IO，UI 反馈回主线程 */
    private fun routeDeepLink(gateway: dev.tgfeed.core.td.TdGateway, target: DeepLinkTarget) {
        val td = gateway as? dev.tgfeed.core.td.TdLibGateway
        if (td == null) {
            // 演示模式（Mock 网关）无公开解析/加入能力
            toast("演示模式无法打开 t.me 链接，请先登录 Telegram 账号")
            return
        }
        android.util.Log.i(TAG_FLOW, "深链路由: 分支=${target.javaClass.simpleName} target=$target")
        lifecycleScope.launch {
            when (target) {
                is DeepLinkTarget.Channel -> {
                    when (val r = withContext(Dispatchers.IO) { td.resolvePublicChannel(target.username) }) {
                        is dev.tgfeed.core.td.PublicChannelResolve.Channel -> {
                            android.util.Log.i(TAG_FLOW, "深链落地: 公开频道 chatId=${r.brief.id} 无锚点")
                            openChannelFeed(gateway, r.brief)
                        }
                        dev.tgfeed.core.td.PublicChannelResolve.NotFound ->
                            toast("频道不存在或已失效")
                        dev.tgfeed.core.td.PublicChannelResolve.NotChannel ->
                            toast("这不是频道（可能是用户/群组）")
                        is dev.tgfeed.core.td.PublicChannelResolve.Failed ->
                            toast("频道暂时无法打开，请检查网络/代理后重试")
                    }
                }
                is DeepLinkTarget.Post -> {
                    when (val r = withContext(Dispatchers.IO) { td.resolvePublicChannel(target.username) }) {
                        is dev.tgfeed.core.td.PublicChannelResolve.Channel -> {
                            android.util.Log.i(TAG_FLOW, "深链落地: 公开频道 chatId=${r.brief.id} 锚定 msgId=${target.messageId}")
                            openChannelFeedAt(gateway, r.brief, target.messageId)
                        }
                        dev.tgfeed.core.td.PublicChannelResolve.NotFound ->
                            toast("频道不存在或已失效")
                        dev.tgfeed.core.td.PublicChannelResolve.NotChannel ->
                            toast("这不是频道（可能是用户/群组）")
                        is dev.tgfeed.core.td.PublicChannelResolve.Failed ->
                            toast("频道暂时无法打开，请检查网络/代理后重试")
                    }
                }
                is DeepLinkTarget.Private -> {
                    // R47 三态拆分：GetChat 命中（含群组）/ 未加入但链接可探 / 真私有未加入
                    // rawLink 重建为规范化形态（剥掉 ?single 等 query——GetMessageLinkInfo 不认）
                    val internalId = -target.chatId - 1_000_000_000_000L
                    val rawLink = if (target.messageId != null) {
                        "https://t.me/c/$internalId/${target.messageId / TDLIB_MSG_ID_FACTOR}"
                    } else {
                        "https://t.me/c/$internalId"
                    }
                    android.util.Log.i(TAG_FLOW, "深链路由: Private 分支 chatId=${target.chatId} rawLink=$rawLink")
                    when (val r = withContext(Dispatchers.IO) { td.resolveDeepLinkChat(target.chatId, rawLink) }) {
                        is dev.tgfeed.core.td.DeepLinkChatResolve.Joined -> {
                            android.util.Log.i(
                                TAG_FLOW,
                                "深链落地: 已加入 chatId=${r.brief.id} isChannel=${r.isChannel} 锚点=${target.messageId != null}",
                            )
                            // 群组落地 toast 无需特判：openChannelFeed(At) 用的是会话标题，
                            // 群名/频道名自然区分（"定位到「群名」的帖子"照常即可）
                            when {
                                target.messageId != null ->
                                    openChannelFeedAt(gateway, r.brief, target.messageId)
                                else -> openChannelFeed(gateway, r.brief)
                            }
                        }
                        is dev.tgfeed.core.td.DeepLinkChatResolve.OpenableViaLink -> {
                            android.util.Log.i(
                                TAG_FLOW,
                                "深链落地: 未加入但链接可开 chatId=${r.brief.id} 锚点=${target.messageId != null}",
                            )
                            when {
                                target.messageId != null ->
                                    openChannelFeedAt(gateway, r.brief, target.messageId)
                                else -> openChannelFeed(gateway, r.brief)
                            }
                        }
                        dev.tgfeed.core.td.DeepLinkChatResolve.Inaccessible -> {
                            android.util.Log.i(TAG_FLOW, "深链落地: 不可达 chatId=${target.chatId}（真私有未加入）")
                            toast("该频道为私有且你未加入——可先去 Telegram 打开这条链接加入后再来")
                        }
                        is dev.tgfeed.core.td.DeepLinkChatResolve.Failed -> {
                            // R169 F6：探测失败（网络/超时/限流）≠ 真不可达——给重试口径，
                            // 不再把可打开的公开链误报成"先去官方客户端加入"（R70 同病止血）
                            android.util.Log.w(TAG_FLOW, "深链落地: 探测失败 chatId=${target.chatId}（${r.reason}）")
                            toast("频道暂时无法打开，请检查网络/代理后重试")
                        }
                    }
                }
                is DeepLinkTarget.Invite -> {
                    android.util.Log.i(TAG_FLOW, "深链路由: Invite 分支（邀请链接）")
                    toast("正在通过邀请链接加入…")
                    when (val r = withContext(Dispatchers.IO) { td.resolveInviteLink(target.url) }) {
                        is dev.tgfeed.core.td.InviteLinkResolve.Joined -> {
                            if (r.justJoined) toast("已加入「${r.brief.title}」")
                            openChannelFeed(gateway, r.brief)
                        }
                        dev.tgfeed.core.td.InviteLinkResolve.RequestSent ->
                            toast("已提交加入申请，等待管理员审核")
                        dev.tgfeed.core.td.InviteLinkResolve.Declined ->
                            toast("无法加入该频道（链接失效或被限制）")
                        is dev.tgfeed.core.td.InviteLinkResolve.Failed -> toast(r.message)
                    }
                }
            }
        }
    }

    /** 取与 FeedScreen 同 key 的共享 FeedViewModel（"gw"+identityHashCode 约定，见 FeedScreen） */
    private fun sharedFeedVm(gateway: dev.tgfeed.core.td.TdGateway): dev.tgfeed.core.feed.FeedViewModel? =
        runCatching {
            androidx.lifecycle.ViewModelProvider(
                this,
                dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
            ).get(
                "gw" + System.identityHashCode(gateway),
                dev.tgfeed.core.feed.FeedViewModel::class.java,
            )
        }.getOrNull()

    /** 剥落所有覆盖层/二级页露出 Feed（深链落点统一前置动作），再切到单屏 Feed 模式 */
    private fun revealFeed() {
        container.closeChannelHome()
        container.closeSearch()
        container.closeDiscover()
        container.closeMine()
        container.showFeed()
    }

    /**
     * 深链落点：切到该频道单频道 Feed 直接开刷（用户口径"一个链接开刷整个频道"）。
     * 不打开频道主页覆盖层——主页是"看信息/挑着看"，链接点进来的意图是"直接刷"。
     */
    private fun openChannelFeed(gateway: dev.tgfeed.core.td.TdGateway, brief: dev.tgfeed.core.td.ChannelBrief) {
        sharedFeedVm(gateway)?.let { vm ->
            // 复位媒体 tab 与筛选器：防本地过滤静默吃掉频道内容（终案实锤陷阱，
            // 与搜索/热榜/频道主页 onPlay 跳转前的处理同款）
            vm.resetMediaTypeTab(-1)
            vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
            // R183 自愈线索（深链 brief 携带 title/username）
            vm.switchSource(
                dev.tgfeed.core.feed.ChannelFeedSource(
                    gateway, brief.id,
                    openTitle = brief.title, openUsername = brief.username,
                ),
            )
        }
        revealFeed()
        toast("开刷「${brief.title}」")
    }

    /**
     * 帖子链接落点：开频道 Feed 并定位到指定消息。
     * openChannelAt 只用 key/chatId/messageId 锚定+定位，其余字段占位（热榜 onPlay 同款先例）；
     * 锚定首拉让目标必落在首页，contextJumpKey 链路由 FeedScreen 观察后滚动定位。
     */
    private fun openChannelFeedAt(
        gateway: dev.tgfeed.core.td.TdGateway,
        brief: dev.tgfeed.core.td.ChannelBrief,
        messageId: Long,
    ) {
        sharedFeedVm(gateway)?.let { vm ->
            vm.resetMediaTypeTab(-1)
            vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
            vm.openChannelAt(
                dev.tgfeed.core.td.VideoItem(
                    key = "tg:${brief.id}:$messageId",
                    chatId = brief.id,
                    messageId = messageId,
                    channelTitle = brief.title,
                    caption = "",
                    durationSec = 0,
                    width = 0,
                    height = 0,
                    fileId = -1,
                    sizeBytes = 0,
                    playUri = null,
                ),
            )
        }
        revealFeed()
        toast("定位到「${brief.title}」的帖子")
    }

    /** 深链反馈 Toast（短驻留，不挡开刷） */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * R397 批量入分屏·宿主收口单口（六入口共用）：种进容器片单（统一机制
     * App.addBatchToGrid，去重+探针计数行在容器内）→ 如实报账 Toast → 开分屏
     * （既有入口链 container.showGrid；调用方先剥落自己的子页/覆盖层——宿主 when
     * 链子页分支先于 grid 分支，不剥落会被压住）。
     * 报账口径：实增 0=「都已在分屏」；部分重复=实增数+跳过数都报；探针行
     * （R397 批量入分屏:来源=source 条数=N）零内容脱敏在容器侧。
     */
    private fun seedGridAndOpen(
        source: String,
        items: List<dev.tgfeed.core.td.VideoItem>,
    ) {
        if (items.isEmpty()) return // 空批守卫：入口面已按非空显钮，这里兜底零动作
        val added = container.addBatchToGrid(source, items)
        toast(
            when {
                added == 0 -> "这批都已在分屏片单里"
                added < items.size -> "已加入分屏 $added 条（重复 ${items.size - added} 条已跳过）"
                else -> "已加入分屏 $added 条"
            },
        )
        container.showGrid()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.tgfeed.core.diag.ColdStartProbe.mark("MainActivity.onCreate") // R52 冷启动打点
        enableEdgeToEdge()
        // t.me/tg:// 深链冷启动接链：先解析暂存，setContent 之后由 dispatchPendingDeepLink
        // 等网关 Ready 再路由（网关未就绪时 resolvePublicChannel 会被 awaitAuthorized 挂起，
        // 必须先暂存）
        pendingDeepLink = parseDeepLink(intent?.data)
        val container = (application as App).container
        // R203 那年今日通知点进（冷启路径）：带周年 extra 直开 TL-2 页——路由是状态
        // 驱动，setContent 时分支即渲染；网关 Loading 期页面自身展示加载/空态，不依赖网关
        intent?.getIntExtra(dev.tgfeed.core.onthisday.OnThisDayPush.EXTRA_OPEN_ON_THIS_DAY_YEAR, 0)
            ?.takeIf { it in 1..dev.tgfeed.core.onthisday.MAX_YEARS }
            ?.let { container.openOnThisDay(it) }
        // Android 13+ 通知运行时权限（后台音频通知用；拒绝不影响功能，仅通知不进抽屉）。
        // R52：只在首次冷启动问一次——旧实现每次冷启动都弹系统权限框（用户拒绝后依然
        // 反复弹），权限框盖住启动页，实测拖慢"首内容可见"感知
        if (android.os.Build.VERSION.SDK_INT >= 33 && !container.prefs.notifPermAsked) {
            container.prefs.notifPermAsked = true
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001,
            )
        }
        setContent {
            // H6：首帧绘制后才真正创建 TDLib 客户端（loadLibrary 38MB 不挡冷启动）
            // R52：再挪下主线程——loadLibrary+TdClient 构造实测平板 130~250ms，
            // 同步跑在 Compose 主线程回调里会拖住启动期每一帧
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.ensureGateway() }
            }
            TgFeedTheme {
                val gateway by container.gateway.collectAsState()
                val state by gateway.state.collectAsState()
                val demoMode by container.demoMode.collectAsState()
                val viewMode by container.viewMode.collectAsState()
                val showSettings by container.showSettings.collectAsState()
                val showDownloads by container.showDownloads.collectAsState()
                val showWatchReport by container.showWatchReport.collectAsState()
                val showAbout by container.showAbout.collectAsState()
                val showChannelHealth by container.showChannelHealth.collectAsState()
                val showTasteMap by container.showTasteMap.collectAsState()
                // R205 事件研究器页（发现页深功能区进；与口味星图同级的状态驱动路由）
                val showEventResearch by container.showEventResearch.collectAsState()
                val showWatchHistory by container.showWatchHistory.collectAsState()
                // R199 我的收藏页（收藏查看模块；与观看历史同级的子页路由）
                val showFavorites by container.showFavorites.collectAsState()
                // R203 那年今日页（TL-2；我的页行 + RT-2 通知直达的状态驱动路由）
                val showOnThisDay by container.showOnThisDay.collectAsState()
                // R202 时间维度旗舰页（时光机 TL-1/事件考古 TL-4/此刻横截面 DS-1 三模式）
                val showTimeMachine by container.showTimeMachine.collectAsState()
                // R222 hashtag 时间轴页（caption #标签双选弹层「时间轴探索」进）
                val showHashtagTimeline by container.showHashtagTimeline.collectAsState()
                val showPlaylists by container.showPlaylists.collectAsState()
                // R387 播放队列（节目单）页（镜像 showPlaylists 的状态驱动路由）
                val showPlayQueue by container.showPlayQueue.collectAsState()
                // R391 播放台列表页（多队列管理面；单台编辑页 showPlayQueue 的宿主）
                val showQueueStations by container.showQueueStations.collectAsState()
                val showSearch by container.showSearch.collectAsState()
                val channelHome by container.showChannelHome.collectAsState()
                // 系统画中画态：true 时悬浮层切"满窗纯视频"（藏全部 UI 控件/进度条/按钮）
                val inPip by container.isInPipMode
                val needsConfig = !container.hasApiCredentials()

                // 未配置凭证且未选过演示模式 → 强制先显示配置/登录页
                val showFeed = state is GatewayState.Ready && (!needsConfig || demoMode)

                // 启动等待路由（修"登录页/代理配置页一闪而过"）：
                // ensureGateway 在首帧后的 LaunchedEffect 才异步建网关（见上方），此前占位网关
                // state 恒为 Loading；真实网关建好后到 TDLib 首判（Ready 或 NeedPhone…）之前
                // 也是 Loading。旧路由对 Loading 直接落到 else → AuthScreen，已授权用户启动时
                // 会先闪一下登录/代理配置页才被 Ready 顶掉。
                // 规则：已有凭证且上次是 real（或上次是 demo 演示模式）时，启动阶段的
                // Loading 一律显示黑底居中 spinner 等网关自判；真需要人工登录（NeedPhone/
                // NeedCode/NeedPassword/Error）时 AuthScreen 正常接管，不会被困在 spinner。
                // startupPhase 只在"状态从未离开过 Loading"的纯启动窗口成立——首次出现
                // 任何非 Loading 态（Ready 进 Feed / NeedPhone 进登录）即永久退出本规则：
                // 登录流程中途或进 Feed 后断线重连的瞬态 Loading 都不会被拉回全屏 spinner
                var startupPhase by remember { mutableStateOf(true) }
                // Feed 双击退出提示的时间戳（0=不显示）：BackHandler 里写入，覆盖层读取显示
                var feedExitHintAt by remember { mutableStateOf(0L) }
                // R177 底栏高亮跟手页位：顶层 pager 滚动中实时取 targetPage（拖拽过半即换
                // 目标、程序动画瞬达目标），静止取 settledPage——底栏三键的选中态由此驱动
                // （任务书「滑动中实时高亮目标页」）。pager 不在场（二级页/登录）时保持末值，
                // 此时底栏本就隐藏。
                var barVisualPage by remember { mutableStateOf(container.topLevelPage.value) }
                // R189 底栏双击「我的」=profile 重拉戳（递增即触发 MineScreen 重拉；
                // 双击处理器先清网关 profile 缓存再递增——真重拉）
                var mineProfileRefreshTickInt by remember { mutableStateOf(0) }
                if (state !is GatewayState.Loading) startupPhase = false
                val startupWaiting = startupPhase && state is GatewayState.Loading &&
                    ((!needsConfig && container.prefs.lastMode == "real") || demoMode)

                // ================= R177 顶层 HorizontalPager（design-proposal §2.2，第三刀）=================
                // R177 顶层页位 = AppContainer.topLevelPage（单值：0 首页/1 发现/2 我的，
                // R173 的 showDiscover/showMine 两位布尔收敛于此；R229~R238 期间曾是
                // 四页——R239 时间线撤顶层页回首页 tab，页序复原三页）；pager 化后
                // 路由状态机快照吃页码，底栏可见性/让位通道（LocalBottomBarInset）沿用
                // 第一刀全套。
                val topLevelRoute = dev.tgfeed.ui.resolveTopLevelRoute(
                    dev.tgfeed.ui.TopLevelRouteSnapshot(
                        showFeed = showFeed,
                        viewMode = viewMode,
                        topLevelPage = container.topLevelPage.value,
                    ),
                )
                val overlayPageOpen = showSettings || showAbout || showDownloads || showWatchReport ||
                    showChannelHealth || showTasteMap || showWatchHistory || showPlaylists ||
                    showPlayQueue || showQueueStations ||
                    showSearch || showFavorites || showEventResearch || showOnThisDay ||
                    showTimeMachine || showHashtagTimeline || channelHome != null
                val barVisible = dev.tgfeed.ui.resolveBottomBarVisible(
                    dev.tgfeed.ui.BottomBarVisibilityInput(
                        topLevel = topLevelRoute,
                        overlayPageOpen = overlayPageOpen,
                        feedChromeHidden = container.feedChromeHidden.value,
                        inPip = inPip,
                    ),
                )
                // R180 Feed 页台前信号合成（需求①导航闸的输入，写入 AppContainer.feedPageActive，
                // FeedScreen 播控闸消费——裁决矩阵见 core/playback/NavPlaybackPolicy）：
                // ① 顶层页位==首页：barVisualPage 滚动中实时取 targetPage（拖拽过半即翻转——
                //    横滑切页的过渡声窗在半程即掐断，不必等落定+dispose）、静止取 settledPage；
                // ② 无子页整页路由（设置/搜索/下载等 when 分支压顶时 pager 离场，本路主要是
                //    feed 页将 dispose 前的一帧兜底）；③ 无频道主页覆盖层（Feed 保持组合的主路径，
                //    闸是这条路的唯一守卫）；④ 视图模式==feed（分屏 GridScreen 是独立台前页，
                //    有自己的池，不经本信号）；⑤ R239 时间线 overlay 不在场（时间线=feed 页内
                //    整层覆盖——播放让位归 R180 navGate 既有机制：组合着但非激活=暂停+记账，
                //    关层回 tab 抬闸续播，与滑去发现页同一语义）。LaunchedEffect 键控写入：
                //    状态翻转后一帧内生效。
                // R417：收敛为播放占有权仲裁（core/playback/PlaybackOwnership 纯函数）——
                // feedActive 由持有者同源派生（== FEED），分屏池同读 playbackOwner
                val playbackOwner = dev.tgfeed.core.playback.resolvePlaybackOwner(
                    dev.tgfeed.core.playback.PlaybackOwnershipSnapshot(
                        showFeed = showFeed,
                        viewMode = viewMode,
                        topLevelPage = barVisualPage,
                        subPageOpen = overlayPageOpen && channelHome == null,
                        channelHomeOpen = channelHome != null,
                        timelineOn = container.homeTimelineOn.value,
                    ),
                )
                val feedActive = playbackOwner == dev.tgfeed.core.playback.PlaybackOwner.FEED
                androidx.compose.runtime.LaunchedEffect(playbackOwner) {
                    container.playbackOwner.value = playbackOwner
                    container.feedPageActive.value = feedActive
                    android.util.Log.i("R417 占有权", "路由切换 owner=${playbackOwner.name}")
                }
                // 底栏让位高度：显隐同拍同曲线（220ms emphasized tween，§5.2「底栏显隐」行）。
                // 分屏模式（viewMode=grid）由 topLevelRoute=null → 恒 0（任务书裁决默认值）
                val barInset by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (barVisible) dev.tgfeed.ui.components.kit.bottomBarHeight() else 0.dp,
                    animationSpec = androidx.compose.animation.core.tween(
                        dev.tgfeed.ui.theme.TgFeedTokens.AnimMed,
                        easing = dev.tgfeed.ui.theme.MotionTokens.EmphasizedEasing,
                    ),
                    label = "barInset",
                )
                // 底栏三键的点击语义（R177 起与手势同一条滚页路径：openXxx/showFeed 走
                // 容器滚页请求 → pager animateScrollToPage，与横滑共用落定转场）。
                // 首页 = 回推荐流（潜进源时清掉，裁决默认值⑥；关注/推荐不动）；
                // 发现/我的 = 对等切换（页码请求互斥自然成立，热榜 sheet 在发现页里）。
                // R239 三页化：PURSUE 路由已撤（时间线=首页 tab，底栏点「首页」时
                // homeTimelineOn 收层归 feed 内容——showFeed 内处理，见 PursueScreen）
                val onTopLevelTap: (dev.tgfeed.ui.TopLevelRoute) -> Unit = { route ->
                    when (route) {
                        dev.tgfeed.ui.TopLevelRoute.HOME -> {
                            container.closeDiscover()
                            container.closeMine()
                            container.showFeed()
                            val feedVmNow = sharedFeedVm(gateway)
                            if (feedVmNow != null && dev.tgfeed.ui.shouldResetToAggregate(feedVmNow.source)) {
                                feedVmNow.showAggregate()
                            }
                        }
                        dev.tgfeed.ui.TopLevelRoute.DISCOVER -> {
                            container.closeMine()
                            container.openDiscover()
                        }
                        dev.tgfeed.ui.TopLevelRoute.MINE -> {
                            container.closeDiscover()
                            container.openMine()
                        }
                    }
                }
                // R189 底栏三键双击刷新（双击已激活页=刷新该页的流；判定在 BottomNavBar
                // 的共享窗口状态机——单击已激活 = 无操作，本回调只在「已激活+双击」时触发）：
                // 首页=刷新当前流（潜进源/关注/推荐各刷各的；R239 时间线 overlay 在场时
                // = 时间线强刷 nonce——「首页当前内容」是时间线）；发现=refreshDiscovery
                //（金矿快照陈值先回+后台重建整链路）；我的=清 profile 缓存+重拉（戳递增）。
                val onTopLevelDoubleTap: (dev.tgfeed.ui.TopLevelRoute) -> Unit = { route ->
                    val feedVmNow = sharedFeedVm(gateway)
                    when (route) {
                        dev.tgfeed.ui.TopLevelRoute.HOME ->
                            if (container.homeTimelineOn.value) {
                                // R239：时间线 tab 在台前——双击首页=刷新时间线（nonce 令）
                                container.requestTimelineRefresh()
                            } else {
                                feedVmNow?.refresh()
                            }
                        dev.tgfeed.ui.TopLevelRoute.DISCOVER -> feedVmNow?.refreshDiscovery()
                        dev.tgfeed.ui.TopLevelRoute.MINE -> {
                            (gateway as? dev.tgfeed.core.td.TdLibGateway)?.refreshMyProfile()
                            mineProfileRefreshTickInt++
                            android.util.Log.i("R189Dock", "双击我的：profile 缓存已清，重拉戳递增")
                        }
                    }
                }

                // ================= R177/R239 顶层 pager 三页页体（原 when 分支体搬入，声明不组合）=================
                // 页体用本地组合 lambda 声明：R189 保活制（beyondViewportPageCount=2）下三页
                // 常驻组合——每页收「settledPage == 本页」的 pageActive，页内 BackHandler
                // 挂 enabled 门控（Compose 后注册者优先，非激活页 handler 不许吃返回键：
                // 我的页 handler 注册在页 0 之后，不门控的话首页双击退出会被它吞掉）。
                // R239 时间线=feed 页内 overlay 层（feedPage 尾部挂载）——不占页位，
                // 其 BackHandler 晚于 feed 双处理器声明=时间线台前时先吃返回。
                // 二级页开着时 pager 整体离场（保活只管横滑往返域，R182 落点记忆不变）。
                val feedPage: @Composable (Boolean) -> Unit = { feedPageActive ->
                    // ================ R182 返回语义矩阵（消费序，完整表见 r182_notes.md）================
                    // L0 页内模态（动作面板/抽屉/快拍/全屏/清屏——FeedScreen 内部声明，后组合者优先
                    // 压过本区全部处理器）→ L1 源栈 ∪ L2 路由子页 pending（**时刻戳统一消费序**：
                    // 同一跳转处理器内先入源栈（戳 n）后置 pending（戳 n+1）——路由跳转的第一次
                    // 返回恒先回子页=R177 语义逐点保持，子页关掉后再弹源栈还原跳转前的源；feed
                    // 内后续跳转（如试吃频道里点 #标签）戳更新者先回=「上一级」直觉）→ L3 双击
                    // 退出（栈底）→ 退出。
                    // 覆盖层/二级页开着时本页不在组合——那些场景返回键行为不变（逐层剥落）；
                    // 在发现/我的页按返回由各页自己的 BackHandler 拦（返回矩阵：先回首页）。
                    val backVm = sharedFeedVm(gateway)
                    val sourceStackDeep = backVm != null && backVm.sourceBackStack.isNotEmpty()
                    // R245 时间线滚动位托管+返回保位令（实锤③：点缩略→播放→返回→
                    // 回用户离开时的位置，不拽顶/底）：listState 声明在 homeTimelineOn
                    // 门控之外——onPlay 走 showFeed 收层只撤 PursueScreen 组合，state 留
                    // 在 feedPage 作用域（页 0 常驻组合 R189 保活）播放往返不清位；返
                    // 回经 PursueScreen(hostedListState) 注入原位恢复。returnLanding 由
                    // 下方 PURSUE 返回消费处置位（区分「播放返回保位」与「新开滚底」
                    // 两语义——PursueScreen 激活消费复位）。
                    val pursueListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    var pursueReturnLanding by remember { mutableStateOf(false) }
                    // R245 键锚：首可见卡 key+偏移（流增长平移索引下恒准——见
                    // PursueScreen positionAnchor KDoc）
                    val pursuePositionAnchor = remember {
                        androidx.compose.runtime.mutableStateOf<Pair<String?, Int>>(null to 0)
                    }
                    val routePendingActive = container.searchReturnPending.value ||
                        container.discoverReturnPending.value ||
                        container.pursueReturnPending.value ||
                        container.channelHomeReturnPending.value != null ||
                        container.watchHistoryReturnPending.value ||
                        container.downloadsReturnPending.value ||
                        container.tasteMapReturnPending.value ||
                        container.gridReturnPending.value ||
                        container.favoritesReturnPending.value ||
                        container.onThisDayReturnPending.value ||
                        container.timeMachineReturnPending.value ||
                        container.hashtagTimelineReturnPending.value
                    // 主层 Feed 返回键双击退出（修"主层按返回直接退 App"）——R182 起为
                    // **栈底语义**：第一次按 Toast 提示，2 秒内第二次按才真正 finish；
                    // 源栈/路由 pending 有货时本处理器禁用（下方统一消费器接管）。
                    // R189 保活门控：feed 页非落定页（滑去发现/我的）时禁用——否则
                    // 被后注册的我的/发现页 handler 抢先，也会误吃非激活态的返回。
                    var lastFeedBackAt by remember { mutableStateOf(0L) }
                    androidx.activity.compose.BackHandler(
                        enabled = feedPageActive && !sourceStackDeep && !routePendingActive,
                    ) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastFeedBackAt < 2_000L) {
                            this@MainActivity.finish()
                        } else {
                            lastFeedBackAt = now
                            // 双击退出提示（修"按返回像没反应"）：Toast 一闪而过太弱，
                            // 换成页面底部醒目横幅，2 秒自动消失（见下方覆盖层）
                            feedExitHintAt = now
                        }
                    }
                    // R182 L1/L2 统一返回消费器（声明在双击退出之后=压过它；页内 L0 更晚
                    // 声明仍最先消费）。动作按「最新跳转先回」择一执行，弹一次消费一层。
                    // R189 保活门控：feed 页非落定页时禁用（返回语义归激活页自己的 handler）
                    androidx.activity.compose.BackHandler(
                        enabled = feedPageActive && (sourceStackDeep || routePendingActive),
                    ) {
                        val candidates = mutableListOf<dev.tgfeed.core.feed.BackConsumption>()
                        backVm?.sourceBackStack?.lastOrNull()?.let {
                            candidates += dev.tgfeed.core.feed.BackConsumption.PopSource(it.stamp)
                        }
                        if (container.searchReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.SEARCH, container.searchReturnStamp,
                            )
                        }
                        if (container.discoverReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.DISCOVER, container.discoverReturnStamp,
                            )
                        }
                        // R229 时间线格进 Feed 播放的 pending：返回键先回时间线（R239
                        // 起=openPursue 落首页 tab；时间线流在 VM 键控存活）
                        if (container.pursueReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.PURSUE, container.pursueReturnStamp,
                            )
                        }
                        if (container.channelHomeReturnPending.value != null) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.CHANNEL_HOME, container.channelHomeReturnStamp,
                            )
                        }
                        if (container.watchHistoryReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.WATCH_HISTORY, container.watchHistoryReturnStamp,
                            )
                        }
                        if (container.downloadsReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.DOWNLOADS, container.downloadsReturnStamp,
                            )
                        }
                        if (container.tasteMapReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.TASTE_MAP, container.tasteMapReturnStamp,
                            )
                        }
                        if (container.gridReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.GRID, container.gridReturnStamp,
                            )
                        }
                        // R199 收藏页 onPlay 进了 Feed：返回先回收藏页（封面墙在 VM 键集驱动下原样）
                        if (container.favoritesReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.FAVORITES, container.favoritesReturnStamp,
                            )
                        }
                        // R203 那年今日页 onPlay 进了 Feed：返回先回本页（一次性源条目在 VM 里还在）
                        if (container.onThisDayReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.ON_THIS_DAY, container.onThisDayReturnStamp,
                            )
                        }
                        // R202 时光机墙点格子进了 Feed：返回先回本页（横截面源条目在 VM 里还在；
                        // 重进=Free 模式重开——锚点丢失是可接受损失，墙的即时性优先）
                        if (container.timeMachineReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.TIME_MACHINE, container.timeMachineReturnStamp,
                            )
                        }
                        // R222 hashtag 时间轴点节点进了 Feed：返回先回本页（VM 里 hashtagTimeline
                        // 状态还在，同 tag 重进不重扫；频道筛选是页内私有态，重进复位=可接受）
                        if (container.hashtagTimelineReturnPending.value) {
                            candidates += dev.tgfeed.core.feed.BackConsumption.Route(
                                dev.tgfeed.core.feed.RoutePendingKind.HASHTAG_TIMELINE, container.hashtagTimelineReturnStamp,
                            )
                        }
                        when (val pick = dev.tgfeed.core.feed.FeedBackStackPolicy.pickBackConsumption(candidates)) {
                            is dev.tgfeed.core.feed.BackConsumption.PopSource ->
                                backVm?.popSourceBack()
                            is dev.tgfeed.core.feed.BackConsumption.Route -> when (pick.kind) {
                                // 从搜索点了结果进播放：返回键先回搜索结果页（结果在 VM 里还在，不重搜）
                                dev.tgfeed.core.feed.RoutePendingKind.SEARCH -> {
                                    container.searchReturnPending.value = false
                                    container.openSearch(container.pendingSearchQuery.value)
                                }
                                // 从发现页试吃/热榜进了频道 Feed：返回键先回发现页（候选/榜单还在）
                                dev.tgfeed.core.feed.RoutePendingKind.DISCOVER -> {
                                    container.discoverReturnPending.value = false
                                    container.openDiscover()
                                }
                                // R229 时间线格进了 Feed：返回键先回时间线（R239 起
                                // openPursue=回首页页位+开时间线 tab；流在
                                // PursueViewModel 里还在，重进零 bootstrap）
                                dev.tgfeed.core.feed.RoutePendingKind.PURSUE -> {
                                    container.pursueReturnPending.value = false
                                    // R245 返回保位：标记本次激活=播放往返返回——
                                    // PursueScreen 跳过进页滚底，回用户离开时的位置
                                    pursueReturnLanding = true
                                    container.openPursue()
                                }
                                // 从频道主页点了条目进播放：返回键先回该频道主页（位置还在）
                                dev.tgfeed.core.feed.RoutePendingKind.CHANNEL_HOME -> {
                                    val brief = container.channelHomeReturnPending.value
                                    container.channelHomeReturnPending.value = null
                                    if (brief != null) container.openChannelHome(brief)
                                }
                                // R182 L2 缺口补齐：观看历史/下载/星图点进 Feed，返回先回该子页
                                dev.tgfeed.core.feed.RoutePendingKind.WATCH_HISTORY -> {
                                    container.watchHistoryReturnPending.value = false
                                    container.openWatchHistory()
                                }
                                dev.tgfeed.core.feed.RoutePendingKind.DOWNLOADS -> {
                                    container.downloadsReturnPending.value = false
                                    container.openDownloads()
                                }
                                dev.tgfeed.core.feed.RoutePendingKind.TASTE_MAP -> {
                                    container.tasteMapReturnPending.value = false
                                    container.openTasteMap()
                                }
                                // 分屏全屏单播进 Feed：返回先回分屏（格子布局在 gridPlaylist 里还在）
                                dev.tgfeed.core.feed.RoutePendingKind.GRID -> {
                                    container.gridReturnPending.value = false
                                    container.showGrid()
                                }
                                // R199 收藏页点条目进 Feed：返回先回收藏页（收藏列表在 VM 里还在）
                                dev.tgfeed.core.feed.RoutePendingKind.FAVORITES -> {
                                    container.favoritesReturnPending.value = false
                                    container.openFavorites()
                                }
                                // R203 那年今日页点条目进 Feed：返回先回本页（年轮策展在 VM 一次性源里还在）
                                dev.tgfeed.core.feed.RoutePendingKind.ON_THIS_DAY -> {
                                    container.onThisDayReturnPending.value = false
                                    container.openOnThisDay()
                                }
                                // R202 时光机墙点条目进 Feed：返回先回本页（Free 模式重开，锚点自然复位）
                                dev.tgfeed.core.feed.RoutePendingKind.TIME_MACHINE -> {
                                    container.timeMachineReturnPending.value = false
                                    container.openTimeMachine(dev.tgfeed.ui.TimeMachineIntent.Free())
                                }
                                // R222 hashtag 时间轴点条目进 Feed：返回先回本页（同 tag 结果
                                // 在 VM 缓存里，重进零成本；回跳 tag 由 pending 载荷携带——
                                // close 已清一次性意图，CHANNEL_HOME brief 载荷同模式）
                                dev.tgfeed.core.feed.RoutePendingKind.HASHTAG_TIMELINE -> {
                                    val backTag = container.hashtagTimelineReturnTag.value
                                    container.hashtagTimelineReturnPending.value = false
                                    container.hashtagTimelineReturnTag.value = null
                                    if (backTag != null) container.openHashtagTimeline(backTag)
                                }
                            }
                            null -> Unit // 候选瞬时空（竞态兜底）：不放行给系统默认，等下次按键
                        }
                    }
                    FeedScreen(
                    gateway = gateway,
                    prefs = container.prefs,
                    onOpenConfig = { container.openConfig() },
                    onGridMode = { container.showGrid() },
                    onPopout = { container.addFloating(it) },
                    onAddToGrid = { container.addToGrid(it) },
                    pendingJumpKey = container.pendingJumpKey.value,
                    onJumpHandled = { container.consumeJump() },
                    // 频道主页入口（P0）：视频页右栏头像点击 → 覆盖层路由打开该频道主页
                    onOpenChannelHome = { item ->
                        container.openChannelHome(
                            dev.tgfeed.core.td.ChannelBrief(item.chatId, item.channelTitle, null),
                        )
                    },
                    // R202 TL-4 事件考古：视频/图音长按面板「事件考古」格 → 时光机页
                    //（Event 模式：该条发布时刻 ±48h 全网横截面）
                    onOpenTimeMachine = { item ->
                        container.openTimeMachine(dev.tgfeed.ui.TimeMachineIntent.Event(item))
                    },
                    // R222 #话题双选弹层「时间轴探索」：caption #标签 → hashtag 时间轴页
                    //（全史时间轴+封面墙，点节点播放；引擎=R205 事件研究器复用）
                    onOpenHashtagTimeline = { tag -> container.openHashtagTimeline(tag) },
                    // R233④ 长按功能面板「在星图中查看」：写一次性运镜意图后开星图——
                    // 装配完成后运镜飞向该条目所属恒星（timeMachineIntent 同模式）
                    onOpenTasteMap = { item -> container.openTasteMapFly(item.chatId) },
                    // R215 ② feed 那年今日卡播放流：个人记忆整批 → 一次性源进 Feed 定位
                    // 起播——OnThisDayScreen onPlay 同款成熟链（revive 复活→复位 tab/筛选→
                    // TimeWindowFeedSource→轮询 requestFeedJump）；无页面切换（卡就在
                    // Feed 里），L1 源栈返回键即「刷完回正常 feed」
                    onPlayOnThisDay = { item, batch, label ->
                        val vm = runCatching {
                            androidx.lifecycle.ViewModelProvider(
                                this@MainActivity,
                                dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                            ).get(
                                "gw" + System.identityHashCode(gateway),
                                dev.tgfeed.core.feed.FeedViewModel::class.java,
                            )
                        }.getOrNull()
                        if (vm != null) {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val revived = vm.reviveDetachedItems(batch)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    vm.resetMediaTypeTab(-1)
                                    vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                    // 潜进源面包屑标题对齐真实源（先设标题后切源）
                                    vm.sourceTitle.value = label
                                    vm.switchSource(
                                        dev.tgfeed.core.feed.TimeWindowFeedSource(
                                            crossSection = revived,
                                            windowLabel = label,
                                        ),
                                    )
                                    lifecycleScope.launch {
                                        val deadline = System.currentTimeMillis() + 10_000
                                        var jumped = false
                                        while (System.currentTimeMillis() < deadline) {
                                            if (vm.items.value.any { it.key == item.key }) {
                                                container.requestFeedJump(item.key)
                                                jumped = true
                                                break
                                            }
                                            kotlinx.coroutines.delay(200)
                                        }
                                        if (!jumped) {
                                            android.util.Log.w(
                                                "TGFlow",
                                                "那年今日卡跳转超时放弃: key=${item.key.takeLast(12)}",
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            container.requestFeedJump(item.key)
                        }
                    },
                    // caption t.me 频道链接（含未加入的公开频道）→ 同一频道主页覆盖层
                    onOpenChannelBrief = { brief -> container.openChannelHome(brief) },
                    // 主页覆盖层开着时 Feed 暂停出声、关掉恢复（FeedScreen 内部播放闸）
                    channelHomeOpen = channelHome != null,
                )
                // ================= R239 时间线 overlay（首页一等 tab：关注|时间线|推荐） =================
                // homeTimelineOn=true 时整层压在 FeedScreen 之上（深底不透明——视觉零透）：
                // - FeedScreen 保持组合（R189 保活零破坏——横滑往返/关层回 feed 原视频
                //   原位）；播放让位走 R180 navGate（feedActive 合成含 !homeTimelineOn，
                //   上方已改）；本层 z 序在 FeedScreen 之后=盖顶。
                // - BackHandler 在 PursueScreen 内声明（晚于 feed 双处理器=后注册者
                //   优先）：时间线台前按返回=关层回 feed；关层后 feed 返回链接管。
                // - deepIndex 注入共享 FeedViewModel 的实例（写回与爬虫同一把写锁——
                //   Read-Through Cache 用户修正③的串行纪律）。
                // - 切「关注/推荐」tab=收层+共享 VM 切流（FeedScreen 顶栏同款语义，
                //   pauseAudioOnTrackSwitch 由 navGate 暂停闸承接）。
                if (container.homeTimelineOn.value) {
                    val feedVmForTabs = sharedFeedVm(gateway)
                    dev.tgfeed.ui.PursueScreen(
                        gateway = gateway,
                        deepIndex = feedVmForTabs?.deepIndex,
                        // R418 时间线共享可见性谓词（屏蔽频道/不感兴趣/关键词）
                        visibility = { feedVmForTabs?.visibilityGate() },
                        pageActive = feedPageActive,
                        onSelectFollowing = {
                            container.homeTimelineOn.value = false
                            feedVmForTabs?.let { vmNow ->
                                vmNow.sourceTitle.value = "关注"
                                vmNow.showFollowing()
                            }
                        },
                        onSelectAggregate = {
                            container.homeTimelineOn.value = false
                            feedVmForTabs?.let { vmNow ->
                                vmNow.sourceTitle.value = "推荐"
                                vmNow.showAggregate()
                            }
                        },
                        onBack = { container.closePursue() },
                        onPlay = { batch, startKey, label ->
                            // R229 编排原样迁移（R222 hashtag 时间轴 onPlay 同链：共享 VM 复位
                            // tab/筛选 → 一次性源装邻域窗条目 → pending 置位 → 轮询定位跳转；
                            // 条目 playUri=null 走 ensurePlayable 按条现取链——历史轮索引
                            // fallback 条目同口径）。脱敏：label 进 UI 面包屑，日志不打。
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    vm.resetMediaTypeTab(-1)
                                    vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                    // 潜进源面包屑标题对齐真实源（先设标题后切源）
                                    vm.sourceTitle.value = label
                                    vm.switchSource(
                                        dev.tgfeed.core.feed.TimeWindowFeedSource(batch, label),
                                    )
                                    // R182 L2 pending：在切源入栈之后置位（时刻戳晚于入栈，
                                    // r202 戳序竞态口径照抄）——Feed 返回键先回时间线 tab
                                    container.pursueReturnPending.value = true
                                    container.pursueReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                                    lifecycleScope.launch {
                                        val deadline = System.currentTimeMillis() + 10_000
                                        var jumped = false
                                        while (System.currentTimeMillis() < deadline) {
                                            if (vm.items.value.any { it.key == startKey }) {
                                                container.requestFeedJump(startKey)
                                                jumped = true
                                                break
                                            }
                                            kotlinx.coroutines.delay(200)
                                        }
                                        if (!jumped) {
                                            android.util.Log.w(
                                                "TGFlow",
                                                "时间线跳转超时放弃: key=${startKey.takeLast(12)}",
                                            )
                                        }
                                    }
                                }
                            } else {
                                container.requestFeedJump(startKey)
                                container.pursueReturnPending.value = true
                                container.pursueReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            }
                            // 落点=feed 内容（showFeed 收时间线层+回首页页位；
                            // 返回键经 pending 回时间线 tab——卡流在 PursueViewModel 里还在）
                            container.showFeed()
                        },
                        // R240 点卡（非缩略区）→频道主页覆盖层：与 feed 视频页右栏头像
                        // 同链（brief 现场拼装）。时间线层保持在场——ChannelHome 渲染序
                        // 在后=z 序盖顶，back 先关主页回时间线（后注册者优先）；底栏/
                        // feedActive 让位走 overlayPageOpen 既有机制零新增门控
                        onOpenChannelHome = { item ->
                            container.openChannelHome(
                                dev.tgfeed.core.td.ChannelBrief(item.chatId, item.channelTitle, null),
                            )
                        },
                        // 双击刷新令（顶栏/底栏双击已激活时间线 → timelineRefreshRequest
                        // nonce 递增 → PursueScreen 消费触发 vm.requestRefresh 强刷）
                        refreshTick = container.timelineRefreshRequest.value,
                        onRefreshTimeline = { container.requestTimelineRefresh() },
                        // R245 播放往返保位（实锤③）：托管滚动位+返回保位令注入——
                        // listState 在门控外 remember（收层撤组合不清位），返回激活
                        // 跳过滚底原位恢复（新开仍滚底，见 PursueScreen KDoc）
                        hostedListState = pursueListState,
                        returnLanding = pursueReturnLanding,
                        onReturnLandingConsumed = { pursueReturnLanding = false },
                        // R245 键锚：离开组合回写/返回激活消费（key 重定位恒回刚才那条）
                        positionAnchor = pursuePositionAnchor,
                    )
                }
                }
                val discoverPage: @Composable (Boolean) -> Unit = { pageActive ->
                    // 发现页（候选频道试吃/收/弃 + 热榜双 tab；R177 起为顶层 pager 第 1 页）。
                    // showSearch 分支整页压在 pager 之上：热词 chip 带词跳搜索时发现页
                    // 保持开着（页位不动），搜索页返回即回发现页；搜索页进播放时再统一
                    // 剥落（见 showSearch 分支）
                    dev.tgfeed.ui.DiscoverScreen(
                        gateway = gateway,
                        onBack = { container.closeDiscover() },
                        onOpenChannel = { brief ->
                            // 试吃：切到该频道单频道 Feed。必须用与 FeedScreen 同 key 的共享 VM
                            //（"gw"+identityHashCode(gateway)，见 FeedScreen 的 viewModel(key=…)）——
                            // feedVm() 那个默认 key 是另一个实例，切在它身上回 Feed 看不到
                            runCatching {
                                val previewVm = androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity, // 页体 lambda 里 this 无接收者，显式指回 Activity
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                                // R109 #7：试吃前先复位媒体 tab/筛选器——否则 Feed 侧残留的
                                // 类型 tab/筛选条件会套在试吃频道上（频道里没有该类型时
                                // 整页空白，像"试吃没反应"）；与热榜/观看历史 onPlay 同款复位
                                previewVm.resetMediaTypeTab(-1)
                                previewVm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                // R183 自愈线索：发现页候选的 title/username（深索引候选
                                // TDLib 无本地会话，Chat not found 时按线索解析建会话）
                                previewVm.switchSource(
                                    dev.tgfeed.core.feed.ChannelFeedSource(
                                        gateway, brief.id,
                                        openTitle = brief.title, openUsername = brief.username,
                                    ),
                                )
                            }
                            // 试吃落点 = 首页页位：closeDiscover 清「回发现页」标记（下方
                            // 重新置位），closeMine 的回首页请求与 showFeed() 的页 0 请求
                            // 同一快照内合并、落点由后者裁定（原「剥掉我的页露出 Feed」
                            // 的路由语义在新模型里由页码请求承接）
                            container.closeDiscover()
                            container.closeMine()
                            // 标记"从发现页试吃进了频道 Feed"：Feed 里按返回键先回发现页。
                            // 注意顺序：closeDiscover 内部会清 discoverReturnPending，必须在它
                            // 之后再置位（与下方搜索 onPlay 的 searchReturnPending 同款先例）
                            container.discoverReturnPending.value = true
                            // R182 时刻戳（须晚于上面的切源入栈——返回消费器按「最新跳转先回」
                            // 定序，路由跳转第一次返回恒先回子页）
                            container.discoverReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.showFeed()
                        },
                        // 热词 chip：带词跳搜索页自动开搜（openSearch 既有通道传词）。
                        // 不关发现页——showSearch 分支优先渲染，发现页压在下面当返回落点
                        onSearchHotWord = { word -> container.openSearch(word) },
                        // R205 WK-3 事件研究器：发现页深功能区常驻行 → 独立研究器路由
                        onOpenEventResearch = { container.openEventResearch() },
                        // R202 时间维度旗舰：发现页主位行（仲裁口径）→ 时光机页（此刻模式进，
                        // 页内拖滑块穿越任意时刻=时光机本体）
                        onOpenTimeMachine = {
                            container.openTimeMachine(dev.tgfeed.ui.TimeMachineIntent.Now)
                        },
                        // R173 热榜并入发现页双 tab（原独立 PlazaBoardScreen 路由撤销）：
                        // 热榜条目点击 = 锚定切源 + 轮询定位跳 Feed——与原分支同一链路，
                        // 剥落层改为发现页（返回键先回发现页，试吃同口径）
                        prefs = container.prefs,
                        onPlazaPlay = { entry ->
                            // 进 Feed 定位播放：共享 VM（同 "gw"+identityHashCode key 约定）先复位
                            // 媒体 tab 与筛选器（防本地过滤吃掉目标条目），再锚定目标 messageId
                            // 切频道源（openChannelAt 首页必含目标），轮询等条目进列表后走
                            // requestFeedJump 链路定位——观看历史/搜索 onPlay 的成熟手法
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                vm.resetMediaTypeTab(-1)
                                vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                // openChannelAt 只用 key/chatId/messageId 三个字段（锚定+定位），
                                // 其余字段占位即可——真条目由锚定拉页从 TDLib 取回
                                vm.openChannelAt(
                                    dev.tgfeed.core.td.VideoItem(
                                        key = entry.key,
                                        chatId = entry.chatId,
                                        messageId = entry.messageId,
                                        channelTitle = entry.channelTitle,
                                        caption = entry.title,
                                        durationSec = 0,
                                        width = 0,
                                        height = 0,
                                        fileId = -1,
                                        sizeBytes = 0,
                                        playUri = null,
                                    ),
                                )
                                lifecycleScope.launch {
                                    val deadline = System.currentTimeMillis() + 10_000
                                    var jumped = false
                                    while (System.currentTimeMillis() < deadline) {
                                        if (vm.items.value.any { it.key == entry.key }) {
                                            container.requestFeedJump(entry.key)
                                            jumped = true
                                            break
                                        }
                                        kotlinx.coroutines.delay(200)
                                    }
                                    if (!jumped) {
                                        android.util.Log.w(
                                            "TGFlow",
                                            "热榜跳转超时放弃: key=${entry.key.takeLast(12)}",
                                        )
                                    }
                                }
                            } else {
                                container.requestFeedJump(entry.key)
                            }
                            container.closeDiscover()
                            // 返回键先回发现页（热榜 tab 的家）——closeDiscover 会清
                            // discoverReturnPending，必须在它之后再置位（试吃同款先例）
                            container.discoverReturnPending.value = true
                            // R182 时刻戳（须晚于上面的切源入栈——返回消费器按「最新跳转先回」
                            // 定序，路由跳转第一次返回恒先回子页）
                            container.discoverReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeMine() // 同快照合并：落点由紧随的 showFeed() 页 0 请求裁定
                            container.showFeed()
                        },
                        // R209 路由收敛：热榜空态的广场开启引导改就地开管理 sheet
                        //（PlazaBoardContent 内部），「跳我的页找 TG 广场」旧路由撤销
                        // R204 TL-3 时间盲盒播放出口：随机时刻+随机频道抽中的条目 →
                        // 与热榜 onPlay 同一条链路（复位 tab/筛选 → openChannelAt 锚定
                        // 切源（盲盒条目 key/chatId/messageId 全真——直接用条目本体，
                        // 不用占位重建）→ 轮询等条目进列表 → requestFeedJump 定位起播）
                        onPlayTimeItem = { blindboxItem ->
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                vm.resetMediaTypeTab(-1)
                                vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                vm.openChannelAt(blindboxItem)
                                lifecycleScope.launch {
                                    val deadline = System.currentTimeMillis() + 10_000
                                    var jumped = false
                                    while (System.currentTimeMillis() < deadline) {
                                        if (vm.items.value.any { it.key == blindboxItem.key }) {
                                            container.requestFeedJump(blindboxItem.key)
                                            jumped = true
                                            break
                                        }
                                        kotlinx.coroutines.delay(200)
                                    }
                                    if (!jumped) {
                                        android.util.Log.w(
                                            "TGFlow",
                                            "时间盲盒跳转超时放弃: key=${blindboxItem.key.takeLast(12)}",
                                        )
                                    }
                                }
                            } else {
                                container.requestFeedJump(blindboxItem.key)
                            }
                            container.closeDiscover()
                            // 返回键先回发现页（盲盒的家）——closeDiscover 会清
                            // discoverReturnPending，必须在它之后再置位（热榜同款先例）
                            container.discoverReturnPending.value = true
                            container.discoverReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeMine()
                            container.showFeed()
                        },
                        // R177 落定页门控：进页刷新只在落定到发现页时触发（0↔2 程序滚页
                        // 路过本页的中转组合不刷——refreshDiscovery 是网络+图谱整链路）
                        pageActive = pageActive,
                        // R209 外部热榜入口令（我的页「TG 广场」行→openDiscoverPlaza
                        // 递增；R232 起热榜=发现页入口行点开的 sheet）：nonce 变化且
                        // 本页落定即开热榜 sheet（页内 LaunchedEffect 等窗）——统一唯一
                        // 入口的落点侧；消费即清槽（consumeDiscoverPlazaTabRequest，
                        // 防二级页往返重放旧令）
                        plazaTabRequestSeq = container.discoverPlazaTabRequest.value,
                        onPlazaTabRequestConsumed = { container.consumeDiscoverPlazaTabRequest() },
                    )
                }
                // R239：R229 追更页 lambda 撤销——时间线升首页一等 tab（feed 页内
                // overlay 层，见上方 feedPage 尾部的 PursueScreen 挂载），顶层第 2
                // 页位不复存在（页序回三页，pager when 见下）。
                val minePage: @Composable (Boolean) -> Unit = { minePageActive ->
                    // 「我的」页（信息架构收敛的统一入口；R177 起为顶层 pager 页——R239
                    // 时间线撤顶层页后为第 2 页）：从我的页点进子页时子页整页压在 pager
                    // 之上（topLevelPage 不动），子页返回即回到我的页（pager 重建
                    // initialPage 落点记忆）；我的页自身按返回走页内 BackHandler 回首页
                    // （closeMine 落点矩阵）。
                    dev.tgfeed.ui.MineScreen(
                        gateway = gateway,
                        prefs = container.prefs,
                        onBack = { container.closeMine() },
                        pageActive = minePageActive,
                        // R189 底栏双击「我的」=profile 重拉的戳（onTopLevelDoubleTap 递增）
                        refreshTick = mineProfileRefreshTickInt,
                        // R209 热榜统一唯一入口（用户实锤②归位）：我的页「TG 广场」行
                        // 改跳转——发现页+选中热榜 tab（openDiscoverPlaza=滚页请求+
                        // nonce 令，与底栏点按同一条滚页通道）
                        onOpenPlazaBoard = { container.openDiscoverPlaza() },
                        onOpenSettings = { container.openConfig() },
                        onOpenDownloads = { container.openDownloads() },
                        onOpenPlaylists = { container.openPlaylists() },
                        // R391 播放台列表页入口（一期「播放队列」行的二期升级位：
                        // 多套节目单管理面+今日台生成；编辑面=台列表页内嵌跳转）
                        onOpenPlayQueue = { container.openQueueStations() },
                        // R199 我的收藏页入口（收藏查看模块；计数徽章数据在页内取共享 VM）
                        onOpenFavorites = { container.openFavorites() },
                        // R203 那年今日页入口（TL-2；时间回看语义组——观看历史旁）
                        onOpenOnThisDay = { container.openOnThisDay() },
                        onOpenWatchHistory = { container.openWatchHistory() },
                        onOpenWatchReport = { container.openWatchReport() },
                        onOpenChannelHealth = { container.openChannelHealth() },
                        // 口味星图（R143）：互链图谱+观看行为的星系图
                        onOpenTasteMap = { container.openTasteMap() },
                    )
                }

                Box(Modifier.fillMaxSize()) {
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.tgfeed.ui.components.kit.LocalBottomBarInset provides barInset,
                ) {
                when {
                    // 关于页(设置页「关于」区进入;纯静态内容零网络。分支排在
                    // showSettings 之前=覆盖页先于宿主匹配的层级不变式,同
                    // showWatchReport;返回剥落回设置)
                    showAbout -> dev.tgfeed.ui.AboutScreen(
                        onBack = { container.closeAbout() },
                    )
                    // 观看报告页
                    showWatchReport -> dev.tgfeed.ui.WatchReportScreen(
                        gateway = gateway,
                        onBack = { container.closeWatchReport() },
                    )
                    // 频道体检页（R132：僵尸/变质频道一键断舍离；从我的页进入时压在它之上，
                    // 返回剥落回我的页——与观看报告/观看历史同一状态驱动路由模式）
                    showChannelHealth -> dev.tgfeed.ui.ChannelHealthScreen(
                        gateway = gateway,
                        onBack = { container.closeChannelHealth() },
                    )
                    // 观看历史页（优先级与观看报告同级；从我的页进时压在它之上，返回剥落回我的页）
                    showWatchHistory -> dev.tgfeed.ui.WatchHistoryScreen(
                        gateway = gateway,
                        onBack = { container.closeWatchHistory() },
                        onPlay = { item ->
                            // 回 Feed 定位续播：照下载页/搜索页 onPlay 的成熟手法——
                            // 复位媒体 tab 与筛选器（防本地过滤吃掉目标条目）→ 切到"已看历史"源
                            //（VM 现成公开 API，源 = 内存条目 + 磁盘历史全量，目标必在其中）→
                            // 轮询等条目进列表后发 requestFeedJump；片内进度由 watch_progress
                            // 自动续播（VideoPage 起播读 vm.getProgress），无需额外传参
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                vm.resetMediaTypeTab(-1)
                                vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                // R199 同类顺手修（一行）：切"已看历史"源前先设顶栏标题——
                                // 旧路径不设，切源后潜进源面包屑仍显示旧源名（源/标题错位）；
                                // 与本刀收藏页 onPlay 的"收藏夹"标题同一处理手法
                                vm.sourceTitle.value = "已看历史"
                                vm.showHistory()
                                lifecycleScope.launch {
                                    val deadline = System.currentTimeMillis() + 10_000
                                    var jumped = false
                                    while (System.currentTimeMillis() < deadline) {
                                        if (vm.items.value.any { it.key == item.key }) {
                                            container.requestFeedJump(item.key)
                                            jumped = true
                                            break
                                        }
                                        kotlinx.coroutines.delay(200)
                                    }
                                    if (!jumped) {
                                        android.util.Log.w(
                                            "TGFlow",
                                            "观看历史跳转超时放弃: key=${item.key.takeLast(12)}",
                                        )
                                    }
                                }
                            } else {
                                container.requestFeedJump(item.key)
                            }
                            container.closeWatchHistory()
                            // R182 L2 缺口补齐：标记"从观看历史点条目进了 Feed"——Feed 里
                            // 按返回键先回观看历史页（历史源在 VM 里还在）。置位须在
                            // closeWatchHistory 之后（它清位），时刻戳晚于上面的切源入栈。
                            container.watchHistoryReturnPending.value = true
                            container.watchHistoryReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeMine() // R177：落点=首页页位（closeMine 回首页请求与 showFeed 同快照合并、后者裁定）
                            container.showFeed()
                        },
                    )
                    // R199 我的收藏页（收藏查看模块）：全出血封面墙（自适应列）+ 按时间/
                    // 按频道双视图 + 取消收藏角标；点条目回 Feed 定位播放——收藏夹源
                    // 现成（FavoritesFeedSource），整单收藏进 Feed 可上下连刷回味
                    showFavorites -> dev.tgfeed.ui.FavoritesScreen(
                        gateway = gateway,
                        onBack = { container.closeFavorites() },
                        onPlay = { item ->
                            // 回 Feed 定位播放：照观看历史 onPlay 的成熟手法——复位媒体
                            // tab 与筛选器（防本地过滤吃掉目标条目）→ 设源名标题 → 切到
                            // "收藏夹"源（一次性全量返回，目标必在其中）→ 轮询等条目进
                            // 列表后发 requestFeedJump 定位
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                vm.resetMediaTypeTab(-1)
                                vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                // 潜进源面包屑标题对齐真实源（先设标题后切源，FeedScreen
                                // 收藏夹 chip 同款口径）
                                vm.sourceTitle.value = "收藏夹"
                                vm.showFavorites()
                                lifecycleScope.launch {
                                    val deadline = System.currentTimeMillis() + 10_000
                                    var jumped = false
                                    while (System.currentTimeMillis() < deadline) {
                                        if (vm.items.value.any { it.key == item.key }) {
                                            container.requestFeedJump(item.key)
                                            jumped = true
                                            break
                                        }
                                        kotlinx.coroutines.delay(200)
                                    }
                                    if (!jumped) {
                                        android.util.Log.w(
                                            "TGFlow",
                                            "收藏跳转超时放弃: key=${item.key.takeLast(12)}",
                                        )
                                    }
                                }
                            } else {
                                container.requestFeedJump(item.key)
                            }
                            container.closeFavorites()
                            // R182 L2 pending：标记"从收藏页点条目进了 Feed"——Feed 里
                            // 按返回键先回收藏页（收藏列表在 VM 里还在）。置位须在
                            // closeFavorites 之后（它清位），时刻戳晚于上面的切源入栈
                            //（返回消费器按「最新跳转先回」定序）
                            container.favoritesReturnPending.value = true
                            container.favoritesReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeMine() // R177：落点=首页页位（同快照合并，showFeed 裁定）
                            container.showFeed()
                        },
                        // R397 批量入分屏（入口②·收藏夹）：整批收藏倒进分屏——收藏页
                        // 从我的页进，剥落链与 onPlay 同款（收藏页+我的页让路）
                        onBatchToGrid = { items ->
                            container.closeFavorites()
                            container.closeMine()
                            seedGridAndOpen("收藏", items)
                        },
                    )
                    // R203 那年今日页（TL-2）：年轮 pills + 任意日期回看 + 精选策展列表；
                    // 点条目回 Feed 定位播放（一次性源=本年轮策展整批，搜索页 onPlay 同款
                    // revive 链——DB 条目无 playUri，fileUniqueId 换发复活后起播走 ensurePlayable）
                    showOnThisDay -> dev.tgfeed.ui.OnThisDayScreen(
                        gateway = gateway,
                        initialYearsAgo = container.onThisDayInitialYear.value,
                        onBack = { container.closeOnThisDay() },
                        onPlay = { item, batch ->
                            // 回 Feed 定位播放：复位媒体 tab 与筛选器（防本地过滤吃掉目标
                            // 条目）→ IO 线程复活 DB 条目（reviveDetachedItems）→ 一次性源
                            // 装入本年轮策展整批（目标必在其中）→ 轮询等条目进列表后
                            // requestFeedJump 定位——搜索页 onPlay 的成熟手法
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val revived = vm.reviveDetachedItems(batch)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        vm.resetMediaTypeTab(-1)
                                        vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                        // 潜进源面包屑标题对齐真实源（先设标题后切源）
                                        vm.sourceTitle.value = "那年今日"
                                        // R202 TimeWindowFeedSource 复用（时光机墙同款一次性源：
                                        // 策展页显什么，滑进去刷的就是什么）
                                        vm.switchSource(
                                            dev.tgfeed.core.feed.TimeWindowFeedSource(
                                                crossSection = revived,
                                                windowLabel = "那年今日",
                                            ),
                                        )
                                        lifecycleScope.launch {
                                            val deadline = System.currentTimeMillis() + 10_000
                                            var jumped = false
                                            while (System.currentTimeMillis() < deadline) {
                                                if (vm.items.value.any { it.key == item.key }) {
                                                    container.requestFeedJump(item.key)
                                                    jumped = true
                                                    break
                                                }
                                                kotlinx.coroutines.delay(200)
                                            }
                                            if (!jumped) {
                                                android.util.Log.w(
                                                    "TGFlow",
                                                    "那年今日跳转超时放弃: key=${item.key.takeLast(12)}",
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                container.requestFeedJump(item.key)
                            }
                            container.closeOnThisDay()
                            // R182 L2 pending：标记"从那年今日页点条目进了 Feed"——Feed 里
                            // 按返回键先回本页（年轮策展在 VM 一次性源里还在）。置位须在
                            // closeOnThisDay 之后（它清位），时刻戳晚于切源入栈（统一消费序）
                            container.onThisDayReturnPending.value = true
                            container.onThisDayReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeMine() // R177：落点=首页页位（同快照合并，showFeed 裁定）
                            container.showFeed()
                        },
                    )
                    // R202 时间维度旗舰页（全网时光机 TL-1/事件考古 TL-4/此刻横截面 DS-1
                    // 三模式一屏）：滑块→有界横截面查询→精炼封面墙；点格子回 Feed 定位
                    // 播放——一次性源=横截面整批（墙显什么，滑进去刷的就是什么），
                    // IO 复活链（reviveDetachedItems）与那年今日 onPlay 同款
                    showTimeMachine -> dev.tgfeed.ui.TimeMachineScreen(
                        gateway = gateway,
                        intent = container.timeMachineIntent.value ?: dev.tgfeed.ui.TimeMachineIntent.Free(),
                        onBack = { container.closeTimeMachine() },
                        onPlay = { batch, label, item ->
                            // 回 Feed 定位播放：复位媒体 tab 与筛选器（防本地过滤吃掉目标
                            // 条目）→ IO 复活 DB 条目（fileUniqueId 换发）→ 一次性源装入
                            // 横截面整批（目标必在其中）→ 轮询等条目进列表后 requestFeedJump
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val revived = vm.reviveDetachedItems(batch)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        vm.resetMediaTypeTab(-1)
                                        vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                        // 潜进源面包屑标题对齐真实源（先设标题后切源）
                                        vm.sourceTitle.value = label
                                        vm.switchSource(
                                            dev.tgfeed.core.feed.TimeWindowFeedSource(revived, label),
                                        )
                                        // R182 L2 pending：**在切源入栈之后置位**（时刻戳晚于
                                        // 入栈——返回消费器按「最新跳转先回」择新，戳序反转
                                        // 会被源栈弹出抢先吃掉返回键；R199 收藏页的切源是同步
                                        // 调用故无此竞态，本页切源在异步 Main 跳转内、pending
                                        // 必须同跳置位）。置位须在 closeTimeMachine 之后语义
                                        // 由 close 先行清位再置回来保持（close 已在外层跑过，
                                        // 此处置位不会被清）。
                                        container.timeMachineReturnPending.value = true
                                        container.timeMachineReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                                        lifecycleScope.launch {
                                            val deadline = System.currentTimeMillis() + 10_000
                                            var jumped = false
                                            while (System.currentTimeMillis() < deadline) {
                                                if (vm.items.value.any { it.key == item.key }) {
                                                    container.requestFeedJump(item.key)
                                                    jumped = true
                                                    break
                                                }
                                                kotlinx.coroutines.delay(200)
                                            }
                                            if (!jumped) {
                                                android.util.Log.w(
                                                    "TGFlow",
                                                    "时光机跳转超时放弃: key=${item.key.takeLast(12)}",
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                container.requestFeedJump(item.key)
                                container.timeMachineReturnPending.value = true
                                container.timeMachineReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            }
                            container.closeTimeMachine()
                            container.closeDiscover() // 发现页/我的页若压在下面则剥落，落点=首页页位
                            container.closeMine()
                            container.showFeed()
                        },
                    )
                    // R222 hashtag 时间轴页（caption #标签 → 双选弹层「时间轴探索」进；
                    // 引擎=R205 EventResearcher 复用，数据=深索引 hashtag 精确词全史召回）：
                    // 横向时间轴（密度墙+频道身份色节点）+ 封面墙；点节点/格子回 Feed
                    // 定位播放——一次性源=过滤后整墙（墙显什么，滑进去刷的就是什么），
                    // IO 复活链（reviveDetachedItems）与时光机 onPlay 同款成熟链
                    showHashtagTimeline -> dev.tgfeed.ui.HashtagTimelineScreen(
                        gateway = gateway,
                        tag = container.hashtagTimelineTag.value ?: "",
                        onBack = { container.closeHashtagTimeline() },
                        onPlay = { batch, label, item ->
                            // 回跳 tag 同步段捕获（下方 closeHashtagTimeline 会清一次性
                            // 意图 hashtagTimelineTag，异步块后读必为 null——载荷同拍定格）
                            val timelineTag = container.hashtagTimelineTag.value
                            // 回 Feed 定位播放：复位媒体 tab 与筛选器（防本地过滤吃掉目标
                            // 条目）→ IO 复活 DB 条目（fileUniqueId 换发）→ 一次性源装入
                            // 时间轴整墙（目标必在其中）→ 轮询等条目进列表后 requestFeedJump
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                // R222b 即时切换：**不做整批预复活**（reviveDetachedItems 对
                                // 138 条实测 58s——切源/pending/跳转全被压在后面，期间返回键
                                // 落双击退出提示=用户实锤「点了没反应」）。TimeWindowFeedSource
                                // 条目 playUri=null 本就走 ensurePlayable 按条现取链（深搜索
                                // 结果进 Feed 同款已上线行为）；switchSource 主线程段零 IO
                                //（R165 切源家族口径），Main 派发在 close+showFeed 之后执行
                                // ——pending 戳序（入栈 n → pending n+1）与 r202 口径保持。
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    vm.resetMediaTypeTab(-1)
                                    vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                    // 潜进源面包屑标题对齐真实源（先设标题后切源）
                                    vm.sourceTitle.value = label
                                    vm.switchSource(
                                        dev.tgfeed.core.feed.TimeWindowFeedSource(batch, label),
                                    )
                                    // R182 L2 pending：**在切源入栈之后置位**（时刻戳晚于
                                    // 入栈——r202 戳序竞态口径）。回跳 tag 走 pending 载荷
                                    //（close 已清一次性意图，同步段捕获值在此处定格）。
                                    container.hashtagTimelineReturnPending.value = true
                                    container.hashtagTimelineReturnTag.value = timelineTag
                                    container.hashtagTimelineReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                                    lifecycleScope.launch {
                                        val deadline = System.currentTimeMillis() + 10_000
                                        var jumped = false
                                        while (System.currentTimeMillis() < deadline) {
                                            if (vm.items.value.any { it.key == item.key }) {
                                                container.requestFeedJump(item.key)
                                                jumped = true
                                                break
                                            }
                                            kotlinx.coroutines.delay(200)
                                        }
                                        if (!jumped) {
                                            android.util.Log.w(
                                                "TGFlow",
                                                "hashtag 时间轴跳转超时放弃: key=${item.key.takeLast(12)}",
                                            )
                                        }
                                    }
                                }
                            } else {
                                container.requestFeedJump(item.key)
                                container.hashtagTimelineReturnPending.value = true
                                container.hashtagTimelineReturnTag.value = timelineTag
                                container.hashtagTimelineReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            }
                            container.closeHashtagTimeline()
                            container.closeDiscover() // 发现页/我的页若压在下面则剥落，落点=首页页位
                            container.closeMine()
                            container.showFeed()
                        },
                        // R397 批量入分屏（入口⑥·#话题标签页）：过滤后整墙种进分屏——
                        // 剥落链与 onPlay 同款（话题页/发现页/我的页让路）
                        onBatchToGrid = { items ->
                            container.closeHashtagTimeline()
                            container.closeDiscover()
                            container.closeMine()
                            seedGridAndOpen("话题", items)
                        },
                    )
                    // M1 全量搜索页（已加入频道 100% 覆盖；优先级与发现页同级，压在 pager 之上）
                    showSearch -> dev.tgfeed.ui.SearchScreen(
                        gateway = gateway,
                        initialQuery = container.pendingSearchQuery.value,
                        onBack = { container.closeSearch() },
                        // 答案区频道行点击（R48 带词进频道）：切到该频道内匹配当前搜索词的 Feed——
                        // 落地即出该频道匹配该词的资源（SearchFeedSource 频道内搜索，FeedSource.kt 现成），
                        // 修"点了直接跳视频推荐页，没有展示匹配到的资源"的实证批评；词空兜底单频道
                        // Feed（旧行为）。必须用与 FeedScreen 同 key 的共享 VM
                        // （同 DiscoverScreen.onOpenChannel 先例，feedVm() 默认 key 是另一个实例）
                        onOpenChannel = { chatId, query ->
                            runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                ).switchSource(
                                    if (query.isBlank()) {
                                        dev.tgfeed.core.feed.ChannelFeedSource(gateway, chatId)
                                    } else {
                                        dev.tgfeed.core.feed.SearchFeedSource(gateway, query, chatId)
                                    },
                                )
                            }
                            // 返回键先回搜索页（与搜索 onPlay 同口径：closeSearch 会清
                            // searchReturnPending，必须在它之后再置位）
                            container.closeSearch()
                            container.searchReturnPending.value = true
                            // R182 时刻戳（晚于切源入栈——返回消费器按「最新跳转先回」定序）
                            container.searchReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            // 搜索页若从发现页进来（发现页压在下面）：进频道 Feed 必须剥落
                            // 发现页/我的页，否则 showFeed 被路由压住露出旧页（试吃同坑同款）
                            container.closeDiscover()
                            container.closeMine()
                            container.showFeed()
                        },
                        onPlay = { item, allResults ->
                            // 切源+跳转：把混排后的完整结果（本地+服务端）包成一次性源
                            //（修复：之前只装服务端池，本地条目不在源里 → 点了本地播了别的）
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                val q = container.pendingSearchQuery.value
                                android.util.Log.i("TGFlow", "搜索 onPlay: 点中「${item.caption.take(20)}」 key=${item.key.takeLast(12)} 结果池=${allResults.size}")
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val revived = vm.reviveDetachedItems(allResults)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                // 搜索结果含图/音：内容分区 tab 归"全部"，防所点条目被 Feed 侧 tab 滤掉
                                vm.resetMediaTypeTab(-1)
                                // 复位筛选器（设过筛选会把所点条目滤掉、跳转落空）：在切源前复位
                                vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                vm.switchSource(object : dev.tgfeed.core.feed.FeedSource {
                                    // 带 searchQuery 标识搜索源（与空态深度/机器人搜索入口同一约定）
                                    override val request = dev.tgfeed.core.td.FeedRequest(searchQuery = q)
                                    private var served = false
                                    override suspend fun nextPage(limit: Int): dev.tgfeed.core.td.FeedPage {
                                        if (served) return dev.tgfeed.core.td.FeedPage(emptyList(), null)
                                        served = true
                                        return dev.tgfeed.core.td.FeedPage(revived, nextCursor = null)
                                    }
                                    override fun reset() { served = false }
                                })
                                // pendingJumpKey 的 LaunchedEffect 只在 key 变化时触发一轮：
                                // switchSource 刚清空列表时立即发跳转会落空，
                                // 故轮询等所点条目进列表后再发跳转（一次性源一页给完，通常 <1s）
                                lifecycleScope.launch {
                                    val deadline = System.currentTimeMillis() + 10_000
                                    var jumped = false
                                    while (System.currentTimeMillis() < deadline) {
                                        if (vm.items.value.any { it.key == item.key }) {
                                            container.requestFeedJump(item.key)
                                            jumped = true
                                            android.util.Log.i("TGFlow", "跳转成功: key=${item.key.takeLast(12)}")
                                            break
                                        }
                                        kotlinx.coroutines.delay(200)
                                    }
                                    if (!jumped) android.util.Log.w("TGFlow", "跳转超时放弃: key=${item.key.takeLast(12)} 当前列表=${vm.items.value.size} 条")
                                }
                                }
                            }
                                }
                            // 标记"从搜索点了结果进播放"：Feed 里按返回键先回搜索结果页。
                            // 注意顺序：closeSearch 内部会清 searchReturnPending，必须在它之后再置位
                            container.closeSearch()
                            container.searchReturnPending.value = true
                            // R182 时刻戳（晚于切源入栈——返回消费器按「最新跳转先回」定序）
                            container.searchReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            // 搜索页若是从发现页热词进来的（发现页未关、压在搜索页之下）：
                            // 进播放必须剥落发现页（及可能垫底的我的页），否则 showFeed 会被
                            // 发现页/我的页路由压住露出旧页（与试吃/下载/热榜 onPlay 同坑同款处理）
                            container.closeDiscover()
                            container.closeMine()
                            container.showFeed()
                        },
                        // R397 批量入分屏（入口①·搜索结果页）：当前所见整批种进分屏并
                        // 直接开格——剥落链与 onPlay 同款（搜索页/发现页/我的页都让路）
                        onBatchToGrid = { items ->
                            container.closeSearch()
                            container.closeDiscover()
                            container.closeMine()
                            seedGridAndOpen("搜索", items)
                        },
                    )
                    // 下载管理页
                    showDownloads -> dev.tgfeed.ui.DownloadsScreen(
                        gateway = gateway,
                        onBack = { container.closeDownloads() },
                        onPlay = { key ->
                            // 修断链：下载条目多半不在当前 Feed 列表里，光 requestFeedJump 会永久落空。
                            // 照 SearchScreen 的 onPlay 模式：一次性源 + 轮询等条目进列表再跳
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            val target = vm?.downloadRecords?.value?.firstOrNull { it.key == key }
                            if (vm != null && target != null) {
                                vm.resetMediaTypeTab(-1)
                                vm.switchSource(object : dev.tgfeed.core.feed.FeedSource {
                                    override val request = dev.tgfeed.core.td.FeedRequest()
                                    private var served = false
                                    override suspend fun nextPage(limit: Int): dev.tgfeed.core.td.FeedPage {
                                        if (served) return dev.tgfeed.core.td.FeedPage(emptyList(), null)
                                        served = true
                                        return dev.tgfeed.core.td.FeedPage(listOf(target.toVideoItem()), nextCursor = null)
                                    }
                                    override fun reset() { served = false }
                                })
                                lifecycleScope.launch {
                                    val deadline = System.currentTimeMillis() + 10_000
                                    while (System.currentTimeMillis() < deadline) {
                                        if (vm.items.value.any { it.key == key }) {
                                            container.requestFeedJump(key)
                                            break
                                        }
                                        kotlinx.coroutines.delay(200)
                                    }
                                }
                            } else {
                                container.requestFeedJump(key)
                            }
                            container.closeDownloads()
                            // R182 L2 缺口补齐：标记"从下载页点条目进了 Feed"——Feed 里按
                            // 返回键先回下载管理页。置位须在 closeDownloads 之后（它清位），
                            // 时刻戳晚于上面的切源入栈。
                            container.downloadsReturnPending.value = true
                            container.downloadsReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            // 剥落补全：下载页可从设置页进（设置→下载管理行）——不关设置页
                            // 的话 showFeed 被 showSettings 分支压住，跳转落在设置页之下
                            // （试吃/搜索 onPlay 剥落发现/我的的同坑同款）
                            container.closeSettings()
                            container.closeMine() // R177：落点=首页页位（closeMine 的回首页请求与紧随的 showFeed 同快照合并）
                            container.showFeed()
                        },
                    )
                    // 播放列表管理页（整单连播 / 重命名 / 删除；优先级与下载页同级，压在 Feed 之上）
                    showPlaylists -> dev.tgfeed.ui.PlaylistsScreen(
                        gateway = gateway,
                        onBack = { container.closePlaylists() },
                    )
                    // R387 播放队列（节目单）页：播放/从头播 + 四档排序 chips + 拖拽
                    // 编辑 + 加频道/稍后再看入单/清空（排 showPlaylists 之后、
                    // showSettings 之前=R305 层级先例）。
                    // R391 起本页=单台编辑页（编辑对象=活跃台；宿主=播放台列表页
                    // showQueueStations——本分支排在台列表分支**之前**=嵌套覆盖页
                    // 先于宿主匹配（showEventResearch 分支前 L1943 同款不变式），
                    // 编辑页返回=剥落回台列表。
                    showPlayQueue -> dev.tgfeed.ui.QueueScreen(
                        gateway = gateway,
                        onBack = { container.closePlayQueue() },
                        // R414 开播落点：编辑页/台列表/我的页三层剥落回 Feed 连播
                        // （旧件只关编辑页=落回台列表/我的页，用户实锤「跳到我的页」）
                        onPlayStarted = {
                            container.closePlayQueue()
                            container.closeQueueStations()
                            container.closeMine()
                            container.showFeed()
                        },
                        // R397 批量入分屏（入口⑤·队列编辑面）：活跃台点名单条倒进分屏
                        // ——编辑页/台列表/我的页三层剥落让路（grid 分支在 when 链
                        // feed 分支内，压在其上的路由须先关）
                        onBatchToGrid = { items ->
                            container.closePlayQueue()
                            container.closeQueueStations()
                            container.closeMine()
                            seedGridAndOpen("队列", items)
                        },
                    )
                    // R391 播放台列表页：多套单卡片（名字/条目数/顺序档/最近动态）
                    // + 一键开台（点击台卡=切源播单）+ 台 CRUD + 今日台生成钮
                    // （#7 入口）。排 showPlayQueue 之后=宿主位（编辑页在前面分支
                    // 压住本页时不匹配）；入口=我的页「播放台」行。
                    showQueueStations -> dev.tgfeed.ui.QueueStationsScreen(
                        gateway = gateway,
                        onBack = { container.closeQueueStations() },
                        onOpenStationEditor = { container.openPlayQueue() },
                        // R414 开台落点：台列表/我的页剥落回 Feed 连播
                        onStationStarted = {
                            container.closeQueueStations()
                            container.closeMine()
                            container.showFeed()
                        },
                        // R397 批量入分屏（入口⑤·台卡行尾）：台内点名单条倒进分屏
                        // ——台列表/我的页剥落让路（编辑页未开时无需关）
                        onBatchToGrid = { items ->
                            container.closeQueueStations()
                            container.closeMine()
                            seedGridAndOpen("播放台", items)
                        },
                    )
                    // R205 事件研究器页（WK-3 时间维度高级研究；R215 起活口入口=设置页
                    // 高级区「时间维度工具」卡，发现页入口隐藏不删码）。
                    // R305 分支归位（层级修复）：本分支必须排在 showSettings 之前——
                    // 本 when 链首匹配独占渲染（分支序=层级优先级），研究器从设置页内
                    // 打开（openEventResearch 不关设置）时若排在设置之后，showSettings
                    // 分支恒先匹配，研究器页根本不进组合——用户实锤：点了没反应，按返回
                    // 键设置剥落才露出研究器。不变式（同坑先例：showWatchReport/
                    // showDownloads 均在 showSettings 之前）：**凡可从某覆盖页内打开的
                    // 覆盖页，其分支必须排在宿主分支之前**。
                    showEventResearch -> dev.tgfeed.ui.EventResearchScreen(
                        gateway = gateway,
                        onBack = { container.closeEventResearch() },
                        onOpenChannel = { brief ->
                            // 点共现簇中继 = 试吃该频道——与发现页候选卡同一条链
                            runCatching {
                                val previewVm = androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                                previewVm.resetMediaTypeTab(-1)
                                previewVm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                // R183 自愈线索：研究器簇中继的 title（深索引条目，
                                // TDLib 无本地会话时按线索解析建会话）
                                previewVm.switchSource(
                                    dev.tgfeed.core.feed.ChannelFeedSource(
                                        gateway, brief.id,
                                        openTitle = brief.title, openUsername = brief.username,
                                    ),
                                )
                            }
                            container.closeEventResearch()
                            // R305 剥落补全：研究器可从设置页进——不关设置页，紧随的
                            // showFeed 会被 showSettings 分支压住（下载页 onPlay 的
                            // closeSettings 同坑同款补全）
                            container.closeSettings()
                            container.closeDiscover()
                            container.closeMine()
                            // 返回语义：试吃返回先回发现页（与发现页候选卡同口径，
                            // 置位须在 closeDiscover 之后——它清位）
                            container.discoverReturnPending.value = true
                            container.discoverReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.showFeed()
                        },
                    )
                    // 设置页优先级最高（Feed/分屏之上）
                    showSettings -> run {
                        // 自动连播改走共享 VM 的内存态：原实现只写 prefs，播放行为消费的
                        // vm.autoAdvance（StateFlow）当次会话不更新，且开关 UI 不跟手。
                        // VM 未建时（理论上设置页必从 Feed 进、VM 已存在）回落纯 prefs 写入
                        val settingsVm = feedVm()
                        val vmAutoAdvance = settingsVm?.autoAdvance?.collectAsState()?.value
                        dev.tgfeed.ui.SettingsScreen(
                        gateway = gateway,
                        prefs = container.prefs,
                        autoAdvance = vmAutoAdvance ?: container.prefs.autoAdvance,
                        onApplyConfig = { id, hash, enabled, host, port ->
                            container.applyConfigAndConnect(id, hash, enabled, host, port)
                            container.closeSettings()
                        },
                        onSetAutoAdvance = { enabled ->
                            val target = settingsVm
                            // setAutoAdvance 内部已同步落 prefs；VM 不在时才只写 prefs
                            if (target != null) target.setAutoAdvance(enabled) else container.prefs.autoAdvance = enabled
                        },
                        onBack = { container.closeSettings() },
                        effectiveApiId = container.displayApiId,
                        effectiveApiHash = container.displayApiHash,
                        onOpenDownloads = { container.openDownloads() },
                        onOpenWatchReport = { container.openWatchReport() },
                        onOpenAbout = { container.openAbout() },
                    )
                    }
                    // 口味星图页（R143：DiscoveryGraph 互链图谱+观看行为渲染的可交互星系图；
                    // 压在我的页之上，返回剥落回我的页——与频道体检/观看历史同一状态驱动路由模式）
                    showTasteMap -> dev.tgfeed.ui.TasteMapScreen(
                        gateway = gateway,
                        onBack = { container.closeTasteMap() },
                        onOpenChannel = { brief ->
                            // 点星试吃：与发现页候选卡同一条链——共享 FeedScreen 的 VM
                            //（同 key 约定），复位媒体 tab/筛选（防残留过滤吃掉目标条目）后
                            // 切单频道源
                            runCatching {
                                val previewVm = androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                                previewVm.resetMediaTypeTab(-1)
                                previewVm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                // R183 自愈线索：发现页候选的 title/username（深索引候选
                                // TDLib 无本地会话，Chat not found 时按线索解析建会话）
                                previewVm.switchSource(
                                    dev.tgfeed.core.feed.ChannelFeedSource(
                                        gateway, brief.id,
                                        openTitle = brief.title, openUsername = brief.username,
                                    ),
                                )
                            }
                            container.closeTasteMap()
                            // R182 L2 缺口补齐：标记"从星图点星试吃进了 Feed"——Feed 里
                            // 按返回键先回星图页。置位须在 closeTasteMap 之后（它清位），
                            // 时刻戳晚于上面的切源入栈。
                            container.tasteMapReturnPending.value = true
                            container.tasteMapReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeMine() // R177：落点=首页页位（同快照合并，showFeed 裁定；原「剥掉我的页露出 Feed」语义由页码请求承接）
                            container.showFeed()
                        },
                        // ---- R233 二期参数 ----
                        // ④ 从 feed 跳入的一次性运镜意图（消费即清位——装配完成后
                        // 运镜飞向该条目所属恒星；星不在图=星图页诚实 Toast）
                        flyTargetChatId = container.tasteMapFlyTarget.value,
                        onFlyConsumed = { container.clearTasteMapFlyTarget() },
                        // ⑤ 点行星→R222 双选「时间轴探索」：关星图开 hashtag 时间轴页
                        //（时间轴返回=回 pager/feed；星图侧会话不保——一次性弹层语义）
                        onOpenHashtagTimeline = { tag ->
                            container.closeTasteMap()
                            container.openHashtagTimeline(tag)
                        },
                        // ⑤ 点行星→R222 双选「搜索播放」：旧行径原样（共享 VM 切
                        //「标签：#tag」源混合双腿开刷）+回星图返回语义（置位须在
                        // closeTasteMap 之后——它清位，试吃 onOpenChannel 同口径）
                        onHashtagSearchPlay = { tag ->
                            val tagVm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (tagVm != null) {
                                tagVm.resetMediaTypeTab(-1)
                                tagVm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                tagVm.sourceTitle.value = "标签：#$tag"
                                tagVm.hashtagSearch(tag)
                            }
                            container.closeTasteMap()
                            container.tasteMapReturnPending.value = true
                            container.tasteMapReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.showFeed()
                        },
                        // R269 行星内容流（波次二主路径）：点行星（画布/信息卡 tag 云）/
                        // 飞入门「看内容·直接开刷」行 → 该 tag 全宇宙内容流直刷。镜像上方
                        // onHashtagSearchPlay 链（共享 VM 复位 tab/筛选→标题→切源→关图→
                        // 回星图 pending 置位在关图之后），差别只在源=playTagContent
                        // （纯本地 tag_items 倒排+LIKE 兜底——零 Search 族，星图红线）；
                        // 主线程段零 IO（R222b 口径：召回在源 nextPage 的 IO 调度里跑）
                        onPlayTagContent = { tag ->
                            val tagVm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (tagVm != null) {
                                tagVm.resetMediaTypeTab(-1)
                                tagVm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                tagVm.sourceTitle.value = "标签：#$tag"
                                tagVm.playTagContent(tag)
                            }
                            container.closeTasteMap()
                            container.tasteMapReturnPending.value = true
                            container.tasteMapReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.showFeed()
                        },
                        // ② 点尘埃→直接播放该条：batch=该 tag 样本条目（星图页已
                        // 物化），R222b 无预复活口径镜像 hashtag 时间轴 onPlay——
                        // TimeWindowFeedSource 条目 playUri=null 本走 ensurePlayable
                        // 按条现取链；返回键回星图（pending 置位在切源入栈之后）
                        onPlayDust = { item, batch, label ->
                            val dustVm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (dustVm != null) {
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    dustVm.resetMediaTypeTab(-1)
                                    dustVm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                    dustVm.sourceTitle.value = label
                                    dustVm.switchSource(
                                        dev.tgfeed.core.feed.TimeWindowFeedSource(batch, label),
                                    )
                                    container.closeTasteMap()
                                    // R182 L2 pending：切源入栈之后置位（时刻戳晚于入栈）且**须在
                                    // closeTasteMap 之后**（它清位——旧序 pending 被即清，尘埃
                                    // 播放按返回回不了星图；R269 16384 真机实锤，同类独例）
                                    container.tasteMapReturnPending.value = true
                                    container.tasteMapReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                                    container.showFeed()
                                    lifecycleScope.launch {
                                        val deadline = System.currentTimeMillis() + 10_000
                                        var jumped = false
                                        while (System.currentTimeMillis() < deadline) {
                                            if (dustVm.items.value.any { it.key == item.key }) {
                                                container.requestFeedJump(item.key)
                                                jumped = true
                                                break
                                            }
                                            kotlinx.coroutines.delay(200)
                                        }
                                        if (!jumped) {
                                            android.util.Log.w(
                                                "TGFlow",
                                                "星图尘埃跳转超时放弃: key=${item.key.takeLast(12)}",
                                            )
                                        }
                                    }
                                }
                            } else {
                                container.requestFeedJump(item.key)
                            }
                        },
                    )
                    // R305：事件研究器分支已上移至 showPlaylists 之后、showSettings
                    // 之前（原在本处——showSettings/showTasteMap 之后，设置页内打开时
                    // 恒被设置分支压住，真机实锤「点了被盖住、按返回才露出」）
                    showFeed && viewMode == "grid" -> dev.tgfeed.ui.GridScreen(
                        gateway = gateway,
                        onExit = {
                            // 分屏自身退出 = 用户显式离开分屏（非跳转）：清 R182 回分屏
                            // 标记后回 Feed，返回语义不再倒回分屏
                            container.gridReturnPending.value = false
                            container.showFeed()
                        },
                        customItems = container.gridPlaylist,
                        onAddCustom = { container.addToGrid(it) },
                        onRemoveCustom = { container.removeFromGrid(it) },
                        onFullscreenItem = { item ->
                            // 全屏单播：退出分屏并让 Feed 定位到该条
                            container.requestFeedJump(item.key)
                            container.showFeed()
                            // R182 viewMode 级跳转：标记"从分屏全屏单播进了 Feed"——
                            // Feed 里按返回键先回分屏（格子布局在 gridPlaylist 里还在）
                            container.gridReturnPending.value = true
                            container.gridReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                        },
                        onPopoutToFloating = { container.addFloating(it) },
                    )
                    // R177 顶层 HorizontalPager（design-proposal §2.2 第三刀落点；
                    // R229 四页化→R239 回三页——时间线撤顶层页升首页 tab）：首页(0)
                    // ←→ 发现(1) ←→ 我的(2)，页体 = 上方三个页面 lambda（时间线=
                    // feed 页内 overlay 层，不占页位）。生命周期取舍（默认离场制：
                    // 与第一刀底栏切换同一 dispose/重建链，FeedScreen 靠
                    // vm.currentIndex 恢复位置、DisposableEffect 存进度释放播放器）
                    // 与升级路径见 TopLevelPagerHost 头注释。
                    showFeed -> run {
                        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                            // initialPage 只在 pager（重）建时读一次：二级页往返后回到离开
                            // 时的页（「关掉设置回到我的页」的落点记忆）；路由侧滚页请求
                            // 由下方 ② 动画到达
                            initialPage = container.topLevelPage.value.coerceIn(
                                0,
                                dev.tgfeed.ui.TopLevelRoute.entries.lastIndex,
                            ),
                            pageCount = { dev.tgfeed.ui.TopLevelRoute.entries.size },
                        )
                        // ① 滑动落定回写容器：手势路径的事实源（页码/返回语义/底栏的唯一
                        // 真相）。路由侧写入（requestTopLevelPage）不走这里——两条写入
                        // 路在 AppContainer 上汇合成同一位。
                        androidx.compose.runtime.LaunchedEffect(pagerState) {
                            androidx.compose.runtime.snapshotFlow { pagerState.settledPage }
                                .collect { container.onTopLevelPageSettled(it) }
                        }
                        // ② 路由滚页请求消费（底栏点按/返回键回首页/深链落点/子页 onPlay
                        // 跳转）：消费即清槽（防 pager 重建后重放旧请求）；与手势松手共用
                        // 同一枚落定转场（pageSettleSpec = tween AnimMed/EmphasizedEasing
                        // ——R232 slide+fade：slide=pager 物理位移、fade=相邻页待命 α0.5→1；
                        // §2.2「点底栏=与手势同一条动画路径」两处消费单一定义纪律保持）
                        androidx.compose.runtime.LaunchedEffect(pagerState) {
                            androidx.compose.runtime.snapshotFlow { container.topLevelPageRequest.value }
                                .filterNotNull()
                                .collect { req ->
                                    container.consumeTopLevelPageRequest()
                                    if (pagerState.settledPage != req.page) {
                                        pagerState.animateScrollToPage(
                                            req.page,
                                            animationSpec = dev.tgfeed.ui.components.kit.pageSettleSpec(),
                                        )
                                    }
                                }
                        }
                        // ③ 底栏高亮跟手（任务书「滑动中实时高亮目标页」）：滚动中实时取
                        // targetPage（拖拽过半即换目标、程序动画瞬达目标），静止取
                        // settledPage——底栏三键选中态由此驱动
                        androidx.compose.runtime.LaunchedEffect(pagerState) {
                            androidx.compose.runtime.snapshotFlow {
                                if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.settledPage
                            }.collect { barVisualPage = it }
                        }
                        dev.tgfeed.ui.components.kit.TopLevelPagerHost(
                            state = pagerState,
                            // 顶层手势闸（消歧矩阵 #8/#9/#10 行，任务书「清屏模式下顶层横滑禁用」）：
                            // 清屏/全屏/源面板开窗时关闸——chrome 隐藏时手势归视频；
                            // 频道主页覆盖层/系统 PiP 压在 pager 之上（不整页替换）也关闸，
                            // 压顶期间横滑不许穿透切页；R374 时间线台前（homeTimelineOn ∧
                            // 首页页位——时间线已是一等公民界面）同关闸：overlay 内横滑归
                            // R308 overlaySwipeHost 就地终局（左滑回推荐/右滑去关注/慢漂移
                            // 不动），用户横滑甩进发现页的 slop 竞速漏洞（竖轴先越界时宿主
                            // 让位、残余横分量被 pager 拾走）连根封死；点按路由不受影响
                            // （animateScrollToPage 不走手势通道——保活语义原样：经底栏去
                            // 发现/我的后 homeTimelineOn 保持原值，回首页时间线原样恢复）
                            userScrollEnabled = dev.tgfeed.ui.topLevelSwipeEnabled(
                                container.feedChromeHidden.value,
                                overlayOpen = channelHome != null || inPip,
                                timelineInFront = dev.tgfeed.ui.timelineOverlayInFront(
                                    container.homeTimelineOn.value,
                                    container.topLevelPage.value,
                                ),
                            ),
                        ) { page ->
                            when (page) {
                                // R189 保活门控：页体收「settledPage == 本页」（settledPage 是
                                // 状态读——落定变更时仅本页重组拿新值，与发现页落定门控同款）
                                0 -> feedPage(pagerState.settledPage == 0)
                                // 落定页门控（settledPage 是状态读——落定变更时仅本页
                                // 重组拿新值）：发现页的进页刷新按「落定到本页」触发
                                1 -> discoverPage(pagerState.settledPage == 1)
                                // R189 保活门控（同上；R239 三页化后我的=第 2 页——
                                // 时间线=feed 页内 overlay 不占页位）
                                else -> minePage(pagerState.settledPage == 2)
                            }
                        }
                    }
                    // 启动等待（见上方 startupWaiting 说明）：R52 品牌启动页——
                    // 与主题 windowBackground（splash_brand）同款黑底居中 logo，
                    // 窗口背景 → Compose 首帧视觉无缝衔接，logo 下方挂 spinner；
                    // 等 ensureGateway 建网关 + TDLib 自判，不再闪登录/代理配置页
                    startupWaiting -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(
                                R.drawable.ic_launcher_foreground,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(108.dp),
                        )
                        // 统一 spinner 规格：40dp/3dp/TextHigh + trackColor（修"白点"——
                        // M3 1.3.0 动画首帧退化成小圆点，轨道色兜底显示完整圆环）
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 160.dp)
                                .size(40.dp),
                            color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
                            strokeWidth = 3.dp,
                            trackColor = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                        )
                    }
                    else -> {
                    AuthScreen(
                        state = state,
                        gateway = gateway,
                        prefs = container.prefs,
                        needsConfig = needsConfig,
                        onApplyConfig = { id, hash, enabled, host, port ->
                            container.applyConfigAndConnect(id, hash, enabled, host, port)
                        },
                        onDemo = { container.useMockGateway() },
                    )
                    }
                }
                // 返回键收口（修"主页开着按返回直接退出应用"）：主页覆盖层开着时先消费
                // 关闭回 Feed，不放行给系统默认（退出应用）。声明在覆盖层**之前**——
                // Compose BackHandler 后组合者优先：覆盖层内部更晚注册的返回处理
                // （ChannelHomeScreen 页级 onBack、频道考古抽屉等页内模态）依次压过
                // 本收口，最内层模态先消费返回键；Feed 内"回搜索结果页"等更早注册的
                // 返回处理仍被本收口/页级 handler 压住（R178 抽屉化顺序修正：旧序在
                // 覆盖层之后会把考古抽屉的返回吃成整层关闭）
                androidx.activity.compose.BackHandler(enabled = channelHome != null) {
                    container.closeChannelHome()
                }
                // 频道主页覆盖层（CHANNEL-HOME-FINAL P0）：压在 Feed 之上
                //（Feed 是 when 分支照常组合、pagerState 不动；主页开着期间播放由
                // FeedScreen 的 channelHomeOpen 播放闸暂停，关闭后恢复），
                // BackHandler 由上方 MainActivity 级收口 + ChannelHomeScreen 内部双保险；悬浮窗仍压最顶
                channelHome?.let { brief ->
                    dev.tgfeed.ui.ChannelHomeScreen(
                        gateway = gateway,
                        brief = brief,
                        onBack = { container.closeChannelHome() },
                        onOpenItem = { item ->
                            // 点格子回 Feed 定位：先复位媒体 tab + 筛选器（否则本地过滤
                            // 会静默吃掉目标条目导致定位超时——终案 §2.4 的实锤陷阱），
                            // 再走 vm.openChannelAt 锚定首拉 + contextJumpKey 定位链路
                            val vm = runCatching {
                                androidx.lifecycle.ViewModelProvider(
                                    this@MainActivity,
                                    dev.tgfeed.core.feed.FeedViewModelFactory(gateway, application),
                                ).get(
                                    "gw" + System.identityHashCode(gateway),
                                    dev.tgfeed.core.feed.FeedViewModel::class.java,
                                )
                            }.getOrNull()
                            if (vm != null) {
                                vm.resetMediaTypeTab(-1)
                                vm.setFilter(dev.tgfeed.core.feed.FeedFilter())
                                vm.openChannelAt(item)
                            }
                            // 标记"从频道主页点了条目进播放"：Feed 里按返回键回主页（位置还在）
                            container.channelHomeReturnPending.value = brief
                            // R182 时刻戳（晚于上面的切源入栈——返回消费器按「最新跳转先回」
                            // 定序，路由跳转第一次返回恒先回子页）
                            container.channelHomeReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
                            container.closeChannelHome()
                            container.showFeed()
                        },
                        // R397 批量入分屏（入口③·频道主页媒体墙）：当前 tab 口径整墙
                        // （作品=类型筛选+剧集链聚合散格/合集=图集代表格）种进分屏——
                        // 剥落链与 onOpenItem 同款（频道主页覆盖层让路；grid 分支在 when
                        // 链 feed 分支内，压在其上的覆盖层须先关）
                        onBatchToGrid = { items ->
                            container.closeChannelHome()
                            seedGridAndOpen("频道主页", items)
                        },
                    )
                }
                } // CompositionLocalProvider(LocalBottomBarInset) 收口

                // R173 底部导航栏（z 序：压一级页、被悬浮窗压——§2.7 悬浮窗零改动）。
                // 显隐动画 = 220ms emphasized tween 位移 + fade（§5.2「底栏显隐」行）；
                // 让位高度走 LocalBottomBarInset（同拍同曲线），与 Feed 底部 chrome 联动
                androidx.compose.animation.AnimatedVisibility(
                    visible = barVisible,
                    enter = androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.tween(
                            dev.tgfeed.ui.theme.TgFeedTokens.AnimMed,
                            easing = dev.tgfeed.ui.theme.MotionTokens.EmphasizedEasing,
                        ),
                    ) { it } + androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimMed),
                    ),
                    exit = androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core.tween(
                            dev.tgfeed.ui.theme.TgFeedTokens.AnimMed,
                            easing = dev.tgfeed.ui.theme.MotionTokens.EmphasizedEasing,
                        ),
                    ) { it } + androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimFast),
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    dev.tgfeed.ui.components.kit.BottomNavBar(
                        // R177：底栏选中态跟手——barVisualPage 在 pager 滚动中实时取
                        // targetPage（拖拽过半即换目标），静止取 settledPage；pager 不在
                        // 场时回落路由解析值
                        current = dev.tgfeed.ui.topLevelPageToRoute(barVisualPage)
                            ?: topLevelRoute
                            ?: dev.tgfeed.ui.TopLevelRoute.HOME,
                        onTabTap = onTopLevelTap,
                        // R189 双击刷新：双击已激活页 = 刷新该页的流（判定在组件内共享件）
                        onTabDoubleTap = onTopLevelDoubleTap,
                        gateway = gateway,
                    )
                }

                // 悬浮窗层：压在所有页面之上，可无限叠加。
                // pipMode：系统画中画中焦点窗满窗纯视频渲染（真悬浮窗的桌面小窗内容），
                // 其余悬浮窗隐藏保活；退出 PiP 即恢复原 App 内悬浮窗布局
                if (container.floatingItems.isNotEmpty()) {
                    dev.tgfeed.ui.floating.FloatingPlayerHost(
                        gateway = gateway,
                        windows = container.floatingItems,
                        onClose = { container.removeFloating(it) },
                        pipMode = inPip,
                    )
                }
                // 双击退出提示横幅（修"按返回像没反应"）：Feed 主层按一次返回时，
                // 底部醒目胶囊横幅"再按一次退出"，2 秒自动消失。
                // PiP 中不渲染（声明在悬浮层之后，不盖会压住桌面小窗的纯视频画面）。
                // R173：底栏在场时横幅上移到 底栏上沿+16dp（§2.1——被底栏挡住的提示等于没有）
                val showExitHint = !inPip && feedExitHintAt != 0L &&
                    android.os.SystemClock.elapsedRealtime() - feedExitHintAt < 2_000L
                if (showExitHint) {
                    androidx.compose.runtime.LaunchedEffect(feedExitHintAt) {
                        kotlinx.coroutines.delay(2_000)
                        feedExitHintAt = 0L
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Text(
                            "再按一次退出",
                            color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                // R226：72dp 硬编码=56+16 的复写（横屏 dock 44dp 时旧值
                                // 多抬 28dp）——收敛统一钥匙 barInset+16，与底栏显隐
                                // 同拍动画；底栏隐藏分支维持 96dp 旧口径（避开视频页底
                                // 部信息区/进度细线的可视让位，非 dock 语义）
                                .padding(bottom = if (barVisible) barInset + 16.dp else 96.dp)
                                .background(
                                    dev.tgfeed.ui.theme.TgFeedTokens.OverlayBgStrong,
                                    RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusPill),
                                )
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
                }
            }
        }
        // 冷启动带链进来的：setContent 装好后派发（内部等网关 Ready 再路由）
        dispatchPendingDeepLink()
        // R52 冷启动打点：setContent 返回（首帧尚未绘制）→ 首帧绘制 → 授权 Ready → 首内容可见
        dev.tgfeed.core.diag.ColdStartProbe.mark("setContent 返回")
        window.decorView.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    dev.tgfeed.core.diag.ColdStartProbe.mark("首帧绘制")
                    return true
                }
            },
        )
        markColdStartContentVisible()
    }

    /**
     * R52 冷启动打点：等网关授权 Ready → 等共享 FeedViewModel 首屏条目到货 →
     * reportFullyDrawn（logcat 出系统"Fully drawn"耗时行）。失败静默，绝不影响主流程。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun markColdStartContentVisible() {
        lifecycleScope.launch {
            runCatching {
                container.gateway.flatMapLatest { it.state }.first { it is GatewayState.Ready }
                dev.tgfeed.core.diag.ColdStartProbe.mark("网关授权 Ready")
                val vm = sharedFeedVm(container.gateway.value) ?: return@launch
                vm.items.first { it.isNotEmpty() }
                // 首内容已进列表，下一帧即用户可见：上报完全绘制（logcat 有系统耗时行）
                reportFullyDrawn()
                dev.tgfeed.core.diag.ColdStartProbe.mark("首内容可见（reportFullyDrawn）")
            }
        }
    }

    /**
     * 深链热路径（Manifest launchMode=singleTop）：应用在栈顶时再点 t.me 链接走到这里。
     * 必须 setIntent 更新——否则下一次冷启动/重建读到的还是旧 intent（旧链接会复活）。
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // R203 那年今日通知点进（热启路径，singleTop）：带周年 extra 直开 TL-2 页
        // （与深链同槽位语义：后到的意图生效）
        intent.getIntExtra(dev.tgfeed.core.onthisday.OnThisDayPush.EXTRA_OPEN_ON_THIS_DAY_YEAR, 0)
            .takeIf { it in 1..dev.tgfeed.core.onthisday.MAX_YEARS }
            ?.let { container.openOnThisDay(it) }
        val target = parseDeepLink(intent.data) ?: return
        // 新链覆盖旧链：连着点两个链接时后到的生效（pendingDeepLink 是单槽位）
        pendingDeepLink = target
        dispatchPendingDeepLink()
    }
}
