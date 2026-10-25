package dev.tgfeed.ui.floating

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem

/**
 * 应用内悬浮窗宿主：为 windows 列表中的每个条目渲染一个独立悬浮小窗。
 *
 * 叠层模型：不盖任何全屏透明层，每个窗口是一个独立 Box，直接作为兄弟节点
 * 发射进 MainActivity 顶层 Box（压在所有页面之上）。空区没有可命中节点，
 * 下方 Feed 的竖滑翻页与点击完全不受影响；窗口本体命中时事件不会穿透到下层的页面。
 *
 * 资源纪律：悬浮层自带一个容量 8 的 PlayerPool（key 统一加 "float:" 前缀），
 * 与 Feed/分屏的池互不抢实例；宿主离开组合（全部关窗/退出登录等）时 releaseAll。
 * 单窗的 acquire/release 见 [FloatingWindow]。
 *
 * @param windows 实时增删的开窗列表（SnapshotStateList 直接传入即可）
 * @param onClose 关窗回调（由容器把条目从 windows 移除）
 * @param pipMode 系统画中画模式：true 时焦点窗满窗纯视频渲染（无窗框/控件/手势），
 * 其余悬浮窗保持组合不渲染（播放器存活、静音续播，退出 PiP 立即复原）。
 * 默认 false，App 内悬浮窗原行为不受影响
 */
@Composable
fun FloatingPlayerHost(
    gateway: TdGateway,
    windows: List<VideoItem>,
    onClose: (VideoItem) -> Unit,
    pipMode: Boolean = false,
) {
    val context = LocalContext.current
    // 悬浮层独立播放器池（容量 8）：超过 8 窗时按 LRU 淘汰最久未用的 busy 实例
    val playerPool = remember { PlayerPool(context.applicationContext, maxPlayers = 8, lowMemoryMode = true) }
    // 宿主销毁时整体释放（单窗的 release 由各窗口自己的 DisposableEffect 负责）
    DisposableEffect(playerPool) {
        onDispose { playerPool.releaseAll() }
    }

    // 焦点窗 key：点击任意窗口即置顶 + 独占音量（其余窗静音）
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // 叠层顺序（末尾渲染在最上）：开窗追加到末尾、点窗移到末尾、关窗移除
    val zOrder = remember { mutableStateListOf<String>() }
    val keys = windows.map { it.key }
    LaunchedEffect(keys) {
        // 同步开窗/关窗到叠层顺序；List 结构化相等，keys 重建不触发无效重启
        zOrder.removeAll { it !in keys }
        keys.forEach { if (it !in zOrder) zOrder.add(it) }
        // 焦点窗被关掉（或首次开窗）时，焦点落到最新开的窗
        if (focusedKey == null || focusedKey !in keys) focusedKey = keys.lastOrNull()
    }

    // PiP 链路接线（系统画中画）：
    //  - pipCandidateProvider：MainActivity.onUserLeaveHint 调它判断"有在播的悬浮视频窗"
    //    才进 PiP；playWhenReady 口径 = 播放意图（缓冲中也算在播），用户手动暂停的不进；
    //    mediaType==0 守卫——音频退后台继续走 MediaSession 通知栏路线，不进 PiP
    //  - floatingPauser：用户关掉桌面 PiP 小窗 = 停播，MainActivity.onStop 调它暂停悬浮池
    val appContainer = (context.applicationContext as? dev.tgfeed.App)?.container
    val windowsNow by rememberUpdatedState(windows)
    val focusedKeyNow by rememberUpdatedState(focusedKey)
    DisposableEffect(playerPool) {
        appContainer?.pipCandidateProvider = {
            val k = focusedKeyNow ?: windowsNow.lastOrNull()?.key
            val item = windowsNow.firstOrNull { it.key == k }
            if (item != null && item.mediaType == 0 &&
                playerPool.peek("float:" + item.key)?.playWhenReady == true
            ) {
                item
            } else {
                null
            }
        }
        appContainer?.floatingPauser = { playerPool.pauseAll() }
        onDispose {
            // 宿主离开组合（全部关窗/退出登录）：注销 PiP 通道，不悬空引用
            appContainer?.pipCandidateProvider = null
            appContainer?.floatingPauser = null
        }
    }

    // —— 系统悬浮窗分派（系统级悬浮窗改造） ——
    // 已授权：所有悬浮窗交给 SystemFloatingController（WindowManager 系统级窗口，
    // 前台服务保活，退桌面/切 App 继续飘，支持多开）；未授权：回落下方 App 内浮层
    //（原行为，"不许崩不许死路"的兜底路径）。
    val overlayGranted by SystemFloatingController.overlayGranted
    val onCloseNow by rememberUpdatedState(onClose)

    // 进组合即重查一次授权态（覆盖"权限在进程存活期间被授/被收"的回流路径）
    LaunchedEffect(Unit) { SystemFloatingController.refreshOverlayPermission(context) }

    // 已授权时把开窗清单差量同步给系统悬浮窗控制器（新条目开窗、消失的关窗）
    LaunchedEffect(overlayGranted, keys) {
        if (overlayGranted && windows.isNotEmpty()) {
            SystemFloatingController.syncWindows(context, gateway, windows)
        }
    }

    // 控制器 → UI 回调：悬浮层侧关窗/播完/被拒 → 从清单移除；单击画面 → 回 App 定位
    DisposableEffect(Unit) {
        val cb = object : SystemFloatingController.UiCallback {
            override fun onWindowRemoved(key: String) {
                windowsNow.firstOrNull { it.key == key }?.let { onCloseNow(it) }
            }

            override fun onReturnToApp(key: String) {
                appContainer?.requestFeedJump(key)
                appContainer?.showFeed()
                windowsNow.firstOrNull { it.key == key }?.let { onCloseNow(it) }
            }
        }
        SystemFloatingController.uiCallback = cb
        onDispose {
            if (SystemFloatingController.uiCallback === cb) SystemFloatingController.uiCallback = null
        }
    }

    // 首次弹悬浮窗且未授权：一句话说明 + 跳系统设置页；"暂不"/拒绝后保持 App 内
    // 浮层（现有行为），每进程只弹一次，不死路不纠缠
    var showOverlayRationale by remember { mutableStateOf(false) }
    LaunchedEffect(overlayGranted, windows.isNotEmpty()) {
        if (!overlayGranted && windows.isNotEmpty() && !SystemFloatingController.rationaleShown) {
            showOverlayRationale = true
        }
    }
    if (showOverlayRationale) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                SystemFloatingController.rationaleShown = true
                showOverlayRationale = false
            },
            text = {
                androidx.compose.material3.Text("悬浮窗需要「显示在其他应用上」权限，开启后退回桌面也能继续悬浮播放")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    SystemFloatingController.rationaleShown = true
                    showOverlayRationale = false
                    SystemFloatingController.openOverlaySettings(context)
                }) { androidx.compose.material3.Text("去开启") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    SystemFloatingController.rationaleShown = true
                    showOverlayRationale = false
                }) { androidx.compose.material3.Text("暂不") }
            },
        )
    }

    // 已授权时窗口全在系统层渲染（App 内不重复渲染，PiP 候选由 MainActivity 的
    // 悬浮窗避让拦截）；未授权走 App 内浮层（原行为）
    if (!overlayGranted) {
    // 按叠层顺序逐个发射窗口：后开的天然压在上面，点过的窗被移到末尾同样置顶
    // PiP 内容窗 = 焦点窗（无焦点记录时回落最新开窗），PiP 里只有它满窗可见
    val pipContentKey = focusedKey ?: windows.lastOrNull()?.key
    zOrder.forEach { k ->
        val item = windows.firstOrNull { it.key == k } ?: return@forEach
        key(k) {
            FloatingWindow(
                item = item,
                gateway = gateway,
                playerPool = playerPool,
                focused = k == focusedKey,
                // 层叠错位用"开窗序"（windows 列表中的位置），关窗后其余窗位置不跳变
                cascadeIndex = keys.indexOf(k).coerceAtLeast(0),
                onFocus = {
                    focusedKey = k
                    zOrder.remove(k)
                    zOrder.add(k)
                },
                onClose = { onClose(item) },
                // 系统画中画：内容窗满窗纯视频；其余悬浮窗隐藏保活（播放器不停）
                pipMode = pipMode && k == pipContentKey,
                hiddenInPip = pipMode && k != pipContentKey,
            )
        }
    }
    }
}
