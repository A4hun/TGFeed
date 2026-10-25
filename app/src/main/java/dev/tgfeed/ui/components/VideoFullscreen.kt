package dev.tgfeed.ui.components

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.td.VideoItem

/**
 * 横屏全屏与方向控制（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：进入/退出全屏闭包（经 [VideoFullscreenHooks] 返回给功能面板与退出钮）、
 * 全屏态返回键拦截、销毁兜底还原方向、全屏沉浸（隐藏系统栏）、
 * 旋转自动进/退全屏（prevOrientation 竖→横跳变判定）、换片恢复旋转自由。
 * fullscreen/autoFullscreen 以 [androidx.compose.runtime.MutableState] 传入（页面侧同一对象）；
 * orientation/windowWidthDp 由页面读取后传入（页面布局仍要用）。
 */

/** 全屏进出闭包句柄：enter = 手动全屏（计信号+锁横屏），exit = 退出（恢复 UNSPECIFIED） */
internal class VideoFullscreenHooks(
    val enter: () -> Unit,
    val exit: () -> Unit,
)

@Composable
internal fun VideoFullscreenController(
    context: Context,
    feedVm: FeedViewModel,
    item: VideoItem,
    isActive: Boolean,
    orientation: Int,
    fullscreenState: androidx.compose.runtime.MutableState<Boolean>,
    autoFullscreenState: androidx.compose.runtime.MutableState<Boolean>,
): VideoFullscreenHooks {
    // 横屏全屏：直接改 Activity 方向。Manifest 已声明
    // configChanges="orientation|screenSize|keyboardHidden"，旋转不重建 Activity，
    // 组合不销毁，PlayerPool（FeedScreen remember）与播放器实例存活，PlayerView 仅重排尺寸。
    val activity = context as? Activity
    var fullscreen by fullscreenState
    var autoFullscreen by autoFullscreenState
    val enterFullscreen = {
        // P1.5 #3：主动全屏（功能面板按钮）= 高投入强正信号；旋转自动全屏不经本闭包，天然区分"主动"
        feedVm.noteActiveFullscreen(item)
        fullscreen = true
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    // 退出全屏一律恢复 UNSPECIFIED（跟随系统/设备物理方向），不再强锁竖屏：
    // 旧实现手动退出会钉成 SENSOR_PORTRAIT——横屏平板/横屏模拟器用户退出全屏后
    // 整个屏幕被掰成竖屏（走查 P1-5 实测复现），必须跟随系统；竖屏手机退出时
    // 设备本就是竖的，UNSPECIFIED 自然回竖，观感不变。横屏自动全屏也不会因此
    // 误重进：自动进入靠 prevOrientation 的竖→横跳变触发（见下方 effect），
    // 退出时设备已在横屏、无跳变，不会刚退出又被拉回全屏
    val exitFullscreen = {
        fullscreen = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // 全屏态拦截系统返回键 = 退出全屏（方向跟随系统）
    BackHandler(enabled = fullscreen) { exitFullscreen() }
    // 兜底恢复：本页在全屏态被销毁（滑出 pager 缓存 / 离开 Feed）时还原方向设置
    // （恢复 UNSPECIFIED 跟随系统，与 exitFullscreen 同口径，不锁竖屏）
    val fullscreenNow by rememberUpdatedState(fullscreen)
    DisposableEffect(Unit) {
        onDispose {
            if (fullscreenNow) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // 全屏沉浸（走查 P1-4：旧实现全屏从不隐藏系统栏）：进入全屏隐藏状态栏+导航栏
    // （手势上滑可临时呼出），退出/销毁时恢复。WindowInsetsControllerCompat 只切换
    // 可见性，不动 MainActivity 的 edge-to-edge 配置；隐藏后 Compose 侧
    // statusBarsPadding/navigationBarsPadding 随 insets 归零自动收起
    val localView = LocalView.current
    DisposableEffect(fullscreen) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, localView) }
        if (fullscreen && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 旋转自动全屏：仅当前激活页 + 横屏视频（宽>高）时，设备"从竖屏旋到横屏"自动进全屏；
    // 启动瞬间设备就横着时**不触发**（否则平板横屏进 App 首个横屏视频直接全屏无 UI，
    // 用户看到的就是"默认清屏"——实测踩坑）。判定靠 prevOrientation：只有从竖屏转过来才算
    // 自动进入的不锁 requestedOrientation（设备物理已在横屏，无需锁定），
    // 这样旋回竖屏时 LocalConfiguration 才会变回 portrait，顺势自动退出；
    // 手动全屏（操作栏按钮，锁 SENSOR_LANDSCAPE）不受此逻辑影响
    // 记录上一次方向：启动时初始化为当前方向，只有真实旋转（竖→横）才触发自动全屏
    var prevOrientation by remember { mutableStateOf(orientation) }
    LaunchedEffect(orientation, isActive, item.key) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val rotatedToLandscape = prevOrientation == Configuration.ORIENTATION_PORTRAIT && landscape
        when {
            rotatedToLandscape && isActive && item.width > item.height && !fullscreen -> {
                autoFullscreen = true
                fullscreen = true
            }
            !landscape && autoFullscreen && fullscreen -> {
                autoFullscreen = false
                exitFullscreen() // 旋转驱动的退出：设备已回竖，UNSPECIFIED 跟随即可
            }
            !isActive && autoFullscreen -> autoFullscreen = false
        }
        prevOrientation = orientation
    }

    // 换片即恢复旋转自由：本页激活且非全屏时把 requestedOrientation 还原为 UNSPECIFIED，
    // 保证整个 App 始终响应旋转（旋转自动全屏链路依赖这一点）。
    // 历史上退出全屏会锁 SENSOR_PORTRAIT、靠本 effect 兜底解锁；现退出即 UNSPECIFIED
    // （exitFullscreen 同口径），本 effect 退化为幂等保险：
    //  - 新页面的 prevOrientation 会按当前方向重新初始化，
    //    还原 UNSPECIFIED 不会误触发上面的自动全屏。
    LaunchedEffect(item.key, isActive) {
        if (isActive && !fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    return VideoFullscreenHooks(enterFullscreen, exitFullscreen)
}
