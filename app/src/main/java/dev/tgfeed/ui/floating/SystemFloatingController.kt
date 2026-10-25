package dev.tgfeed.ui.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import dev.tgfeed.MainActivity
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 系统悬浮窗控制器（进程级单例）：FloatingPlayerHost 在"已授权"状态下把开窗清单
 * 同步到这里，由它在 WindowManager 层管理真正的系统级悬浮窗（TYPE_APPLICATION_OVERLAY，
 * 退桌面/切 App 继续飘着播）。
 *
 * 三者关系：
 *  - 本控制器 = 在飞窗口的唯一权威（[holders]），驱动 [SystemFloatingService] 保活；
 *  - 每个窗口一个 [OverlayWindowHolder]：WindowManager 窗口 + ComposeView + 独立播放器；
 *  - 播放器来自专属 PlayerPool（容量 = [MAX_WINDOWS]，窗数上限即池上限——池满在开窗前
 *    就被拦下 Toast 提示，池的 LRU 淘汰路径永不可达，故 PlayerPool 零改动）。
 *
 * 音频纪律（多开防混音炸耳朵）：同时只有"最后触摸的窗"出声，其余窗 player.volume=0
 * 静音续播。选静音不选暂停的理由：① 暂停会冻结进度，与"悬浮窗挂着随时回来看"的心智
 * 不符；② 暂停/恢复与后台闸、播放态持久化等机制耦合，时序竞态多；③ 静音瞬时切换
 * 无重缓冲。与 App 内浮层（FloatingWindow）/分屏（GridScreen）的既有纪律一致。
 * 另：悬浮池每台播放器 acquire 后关闭系统音频焦点自动管理（setAudioAttributes(attrs,
 * false)），防多实例互抢焦点被系统级 auto-pause（行为不可控），也防悬浮窗抢焦点误伤
 * Feed 正在播的内容；代价是来电时悬浮窗不自动闪避——与其他悬浮球类 App 行为一致，
 * 可接受。
 *
 * 后台闸：开窗期间置 PlayerPool.pipExempt=true——系统悬浮窗与 PiP 同为"小窗用户可见"
 * 的继续播放场景，退后台不该被闸一刀切暂停；两者互斥（有悬浮窗在飞时 MainActivity
 * 不进 PiP），豁免标记不打架。全关窗即复原。
 */
object SystemFloatingController {

    /** 悬浮窗数量上限 = 播放器池容量（多数设备硬解并发 4~6 路，悬浮窗还与 Feed 并发，取保守 4） */
    const val MAX_WINDOWS = 4

    /** 悬浮窗权限授权态（Compose 可观察；FloatingPlayerHost 进组合 + MainActivity.onResume 刷新） */
    val overlayGranted = mutableStateOf(false)

    /** 本会话是否已弹过权限说明（"首次"口径：每进程一次；拒绝后回落 App 内浮层，不死路） */
    var rationaleShown = false

    /** 控制器 → UI 层回调（FloatingPlayerHost 注册/注销；按引用比对防误摘后来者） */
    interface UiCallback {
        /** 窗口在悬浮层侧被移除（用户点 × / 播完 / 池满或类型被拒）：把该条目从悬浮清单删掉 */
        fun onWindowRemoved(key: String)

        /** 单击画面：回 App 定位到该视频（跳转 Feed + 关闭对应悬浮窗） */
        fun onReturnToApp(key: String)
    }

    var uiCallback: UiCallback? = null

    private const val PREFS = "system_floating_overlay"

    private val holders = LinkedHashMap<String, OverlayWindowHolder>() // 插入序 = 开窗序
    private var playerPool: PlayerPool? = null
    private var appContext: Context? = null
    // R230 追加4：连播换片时 onPlaybackEnded 里也要开新窗（openWindow 需要网关）——
    // syncWindows 每轮先记下当前网关（开窗必经 sync，无窗时无消费方，时序安全）
    private var tdGateway: TdGateway? = null
    private var lastDisplay: Display? = null
    private var focusedKey: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** MainActivity PiP 避让查询：是否有系统悬浮窗在飞（主线程调用，非 Compose 快照） */
    fun hasActiveWindows(): Boolean = holders.isNotEmpty()

    /**
     * 重查悬浮窗授权态（FloatingPlayerHost 进组合一次 + MainActivity.onResume 每次）。
     * 授权被收回时系统已自动摘除所有悬浮窗 View，这里兜底清册；条目留在悬浮清单里，
     * UI 侧因 overlayGranted=false 回落渲染 App 内浮层——无缝降级，不死路。
     */
    fun refreshOverlayPermission(context: Context) {
        val now = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        if (overlayGranted.value && !now) detachAll(notifyUi = false)
        overlayGranted.value = now
    }

    /** 跳系统「显示在其他应用上」设置页；少数 ROM 缺该页时回落应用详情页 */
    fun openOverlaySettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * 把 UI 侧悬浮清单同步成系统悬浮窗（差量：新条目开窗、清单里消失的关窗）。
     * 仅授权态下工作；调用方为主线程（Compose LaunchedEffect）。
     */
    fun syncWindows(hostContext: Context, gateway: TdGateway, items: List<VideoItem>) {
        if (!overlayGranted.value) return
        val app = hostContext.applicationContext
        appContext = app
        tdGateway = gateway
        // MuMu 多显示器坑：悬浮窗必须落在 App 所在 display，不能假设 display 0
        lastDisplay = displayOf(hostContext)
        val keys = items.map { it.key }
        // 关：清单里已没有的条目
        val it = holders.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keys) {
                e.value.detach()
                it.remove()
            }
        }
        // 开：清单里新增/未开的条目（同 key 去重——悬浮清单允许重复 key，系统窗只开一个）
        for (item in items.distinctBy { it.key }) {
            if (holders.containsKey(item.key)) continue
            if (holders.size >= MAX_WINDOWS) {
                toast("悬浮窗满了（最多 $MAX_WINDOWS 个）")
                uiCallback?.onWindowRemoved(item.key)
                continue
            }
            if (item.mediaType != 0) {
                // 图文/音频没有可悬浮的画面（与 PiP 候选同一口径，只服务视频）
                toast("仅视频支持悬浮窗")
                uiCallback?.onWindowRemoved(item.key)
                continue
            }
            openWindow(app, gateway, item)
        }
        // 展示尺寸可能变了（旋转/换显示器）：把在飞窗口钳回屏内
        holders.values.forEach { it.clampToBounds() }
        updateServiceState()
    }

    private fun openWindow(app: Context, gateway: TdGateway, item: VideoItem) {
        val pool = playerPool
            ?: PlayerPool(app, maxPlayers = MAX_WINDOWS, lowMemoryMode = true).also { playerPool = it }
        val winCtx = windowContextFor(app, lastDisplay)
        val wm = runCatching { winCtx.getSystemService(WindowManager::class.java) }.getOrNull() ?: return
        val (sw, sh) = screenSize(wm)
        val holder = OverlayWindowHolder(
            key = item.key,
            item = item,
            windowContext = winCtx,
            windowManager = wm,
            screenW = sw,
            screenH = sh,
            gateway = gateway,
            playerPool = pool,
            parentScope = scope,
            cascadeIndex = holders.size,
            geometry = readGeometry(app),
            callbacks = windowCallbacks,
        )
        if (!holder.attach()) {
            // addView 抛异常（典型：权限在检查与开窗之间被收回）：toast 说明并退回清单外
            toast("悬浮窗开启失败")
            uiCallback?.onWindowRemoved(item.key)
            return
        }
        holders[item.key] = holder
        // 新开的窗抢焦点出声（音频纪律：最后触摸者出声）
        focusedKey = item.key
        applyAudioDiscipline()
        // 单声源纪律延伸：开窗即暂停页内播放，防"同一视频 App 内+悬浮窗"双重出声。
        //（App 内浮层靠音频焦点抢占隐式达成同样效果；悬浮池关闭了焦点自动管理——
        //  多实例互抢会被系统级 auto-pause 且时序不可控——故这里必须显式暂停。
        //  无注册 pauser 时（如分屏在页）空转，安全）
        (app as? dev.tgfeed.App)?.container?.pauseAllPlayback()
        updateServiceState()
    }

    /** 窗口侧事件回调（holder → 控制器） */
    private val windowCallbacks = object : OverlayWindowHolder.Callbacks {
        override fun onTouched(key: String) {
            // 落下即抢焦点：本窗出声、其余静音
            if (focusedKey != key) {
                focusedKey = key
                applyAudioDiscipline()
            }
        }

        override fun onCloseRequested(key: String) {
            removeHolder(key)
            uiCallback?.onWindowRemoved(key)
        }

        override fun onPlaybackEnded(key: String) {
            // R230 追加4 播完自动连播（开关默认开，AppPrefs 全局共享——App 内浮窗
            // 控制条与系统窗右上常驻组两处开关同源）：容器队列取下一条——关旧窗、
            // 原位开新窗（几何记忆共享=落点不跳）、容器清单换装（宿主 sync 差量对账
            // 不重开不重关）；开关关/队列尽 → 维持旧行为（播完自动关窗）
            val app = appContext as? dev.tgfeed.App
            val prefs = app?.let { dev.tgfeed.core.prefs.AppPrefs(it) }
            val next = if (prefs?.floatingAutoAdvance == true) {
                app?.container?.advanceFloatingQueue(key)
            } else {
                null
            }
            if (next != null && app != null && tdGateway != null) {
                val old = holders[key]?.item
                removeHolder(key)
                if (old != null) app.container.removeFloating(old)
                app.container.addFloating(next)
                openWindow(app, tdGateway!!, next)
                android.util.Log.i(
                    "FloatAdvance",
                    "系统小窗连播：…${key.takeLast(10)} → …${next.key.takeLast(10)}",
                )
            } else {
                // 视频播完的窗自动关
                removeHolder(key)
                uiCallback?.onWindowRemoved(key)
            }
        }

        override fun onReturnToApp(key: String) {
            // 单击画面：回 App 定位到该视频，对应悬浮窗关闭（App 内回到全屏播放语义）
            bringAppToFront()
            uiCallback?.onReturnToApp(key)
            removeHolder(key)
        }

        override fun onDragEnded(key: String, widthFraction: Float, dockLeft: Boolean, yFraction: Float) {
            saveGeometry(widthFraction, dockLeft, yFraction)
        }
    }

    private fun removeHolder(key: String) {
        holders.remove(key)?.detach()
        // 焦点窗被关：焦点落到最新开的窗（无窗则清）
        if (focusedKey == key) focusedKey = holders.keys.lastOrNull()
        applyAudioDiscipline()
        updateServiceState()
    }

    /**
     * 回收全部悬浮窗（服务销毁/权限收回）。notifyUi=true 时同步清悬浮清单，
     * 防清单残留导致授权态下"系统窗没了、App 内浮层也不渲染"的幽灵态；
     * 权限收回路径传 false——清单保留，UI 回落 App 内浮层无缝接管。
     */
    fun detachAll(notifyUi: Boolean) {
        if (holders.isNotEmpty()) {
            val keys = holders.keys.toList()
            keys.forEach { holders.remove(it)?.detach() }
            focusedKey = null
            if (notifyUi) keys.forEach { uiCallback?.onWindowRemoved(it) }
        }
        updateServiceState()
    }

    /** SystemFloatingService.onDestroy 回调：进程/服务销毁路径的整体回收 */
    internal fun onServiceDestroyed() {
        detachAll(notifyUi = true)
    }

    /** 音频纪律落地：焦点窗出声、其余静音（新开窗/触摸/关窗后统一调） */
    private fun applyAudioDiscipline() {
        holders.forEach { (k, h) -> h.setAudible(k == focusedKey) }
    }

    private fun updateServiceState() {
        val app = appContext ?: return
        if (holders.isNotEmpty()) {
            // 系统悬浮窗 = 用户可见的继续播放场景：豁免后台闸（与 PiP 同一语义；
            // 两者互斥——有悬浮窗在飞时 MainActivity 不进 PiP，豁免标记不冲突）
            PlayerPool.setPipExempt(true)
            SystemFloatingService.start(app)
        } else {
            PlayerPool.setPipExempt(false)
            SystemFloatingService.stop(app)
        }
    }

    /** 把 App 唤到前台（singleTop 复用主 Activity，不叠新实例） */
    private fun bringAppToFront() {
        val app = appContext ?: return
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun toast(msg: String) {
        val app = appContext ?: return
        runCatching { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---- 展示上下文（MuMu 多显示器：悬浮窗落在 App 所在 display，不假设 display 0） ----

    private fun displayOf(context: Context): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Activity 上下文返回其所在 display；应用上下文返回 null（回落默认 display）
            runCatching { context.display }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            (context as? Activity)?.windowManager?.defaultDisplay
        }

    private fun windowContextFor(app: Context, display: Display?): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && display != null) {
            // window context 绑死 display + overlay 类型：尺寸度量与窗口落点都在 App 所在屏
            runCatching {
                app.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null,
                )
            }.getOrElse { app }
        } else {
            app // API 26~29 无 window context：回落默认 display（单屏设备行为不变）
        }

    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    // ---- 尺寸/位置记忆（全局单槽：新窗开在上次拖放的位置与宽度） ----

    private fun prefs(app: Context): SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readGeometry(app: Context): OverlayGeometry {
        val p = prefs(app)
        return OverlayGeometry(
            widthFraction = p.getFloat("width_fraction", 0.45f).coerceIn(0.2f, 1f),
            dockLeft = p.getBoolean("dock_left", false),
            yFraction = p.getFloat("y_fraction", 0.25f).coerceIn(0f, 0.9f),
        )
    }

    private fun saveGeometry(widthFraction: Float, dockLeft: Boolean, yFraction: Float) {
        val app = appContext ?: return
        prefs(app).edit()
            .putFloat("width_fraction", widthFraction.coerceIn(0.2f, 1f))
            .putBoolean("dock_left", dockLeft)
            .putFloat("y_fraction", yFraction.coerceIn(0f, 0.9f))
            .apply()
    }
}

/** 悬浮窗几何记忆：宽度占屏比 / 停靠左缘还是右缘 / 纵坐标占屏比 */
internal data class OverlayGeometry(
    val widthFraction: Float,
    val dockLeft: Boolean,
    val yFraction: Float,
)
