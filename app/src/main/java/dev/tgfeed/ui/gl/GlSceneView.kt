package dev.tgfeed.ui.gl

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.tgfeed.ui.TasteFlightFrame

/**
 * R277 GL 场景层 Compose 壳（方案 c 的混合架构落地件）：
 *  - GLSurfaceView（SurfaceView 打孔合成——Compose 弹层/信息卡/名签叠加在其
 *    **上方**天然正确；手势挂槽位 Modifier=本壳的 modifier 入参，与 Canvas
 *    同一位零改动）；
 *  - **ES 3.0 探针闸**（[probeEs3]）：EGL 离屏 1×1 pbuffer 建 ES3 上下文——
 *    失败=设备不可用（R345 GL-only 后转诚实降级诊断——由政策件裁决，本壳根本不被装配）；
 *    探针结果进程级缓存（零重复开销）；
 *  - 生命周期：ON_PAUSE/ON_RESUME 驱动 GLSurfaceView 队列休眠/唤醒（渲染线程
 *    不再空转——后台零 GPU 占空）；EGL 上下文保活（preserveEGLContextOnPause）
 *    + 丢失全量重建（渲染器 onSurfaceCreated 纪律）双保险；
 *  - 崩溃回调换装：渲染器 onFatal → 本壳回调 Compose 侧（主线程 post）——R345 后转
 *    诚实降级（诊断行+重试）由调用方（TasteMapScreen 槽位段）做。
 */
@Composable
internal fun TasteGlScene(
    modifier: Modifier,
    frameProvider: () -> TasteFlightFrame?,
    onFatal: (Throwable) -> Unit,
    onHealthyMilestone: () -> Unit,
    onSessionActive: (Boolean) -> Unit,
    onRendererReady: (TasteGlRenderer) -> Unit,
) {
    val renderer = remember { TasteGlRenderer(frameProvider, onFatal, onHealthyMilestone) }
    val glView = remember { mutableListOf<GLSurfaceView>() } // 生命周期 observer 捕获位
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                // ES 3.0 上下文（探针已过的设备此调用可靠；EGL 配置=RGB888 无深度——
                // P0 画家序不吃深度缓冲，省一份显存）
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY // 活宇宙常动=vsync 自驱
                // R355 LOD 阈值 DPI 化：屏密度推入渲染器（displayMetrics.density——
                // dp 基准阈值×密度=px 阈值；换算单口=builder.build 帧首一次）
                renderer.lodDensity = ctx.resources.displayMetrics.density
                glView += this
                onSessionActive(true) // 会话旗标立（渲染活性=旗标——崩溃旗语义见政策件）
            }
        },
        update = { view ->
            // 帧装配 lambda 捕获 composition 局部量——重组即刷新（快照换装零竞态）
            renderer.frameProvider = frameProvider
            // R355 密度随重组刷新（折叠/外接屏换显示=displayMetrics 跟新；同值幂等）
            renderer.lodDensity = view.resources.displayMetrics.density
            onRendererReady(renderer)
        },
        onRelease = { view ->
            glView -= view
            onSessionActive(false) // 干净退出清旗
            view.onPause() // 渲染线程停（视图树摘离即休眠——泄漏兜底）
        },
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 后台暂停=清旗（进程被杀不算 GL 崩溃——只立「渲染中」的窗）
                Lifecycle.Event.ON_RESUME -> glView.forEach { it.onResume(); }.also { onSessionActive(true) }
                Lifecycle.Event.ON_PAUSE -> glView.forEach { it.onPause() }.also { onSessionActive(false) }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** ES3 探针结果（进程级缓存——0=未探/1=可用/2=不可用） */
@Volatile
private var es3ProbeCache = 0

/**
 * ES 3.0 探针闸（崩溃回退三闸之①——「GL 可用即 GL」的可用判据）：
 *  1. 系统申报：ActivityManager.deviceConfigurationInfo.reqGlEsVersion ≥ 3.0；
 *  2. 实测建上下文：EGL 离屏 1×1 pbuffer + client version 3 真建——驱动申报
 *     与真实能力不符时以实测为准（华为低配档保险丝的第一道）；
 *  全程异常=不可用（绝不炸调用方）。结果进程级缓存（探针一次，~ms 级）。
 */
internal fun probeEs3(context: Context): Boolean {
    es3ProbeCache.let { if (it != 0) return it == 1 }
    val ok = runCatching { probeEs3Uncached(context) }.getOrDefault(false)
    es3ProbeCache = if (ok) 1 else 2
    Log.i("TasteMapX", "R277 ES3 探针: ${if (ok) "可用" else "不可用——R345 后诚实降级诊断"}")
    return ok
}

private fun probeEs3Uncached(context: Context): Boolean {
    // 申报闸（快路径——系统自报不足 3.0 就不必实测了）
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    val declared = am.deviceConfigurationInfo.reqGlEsVersion
    if (declared < 0x00030000) return false
    // 实测闸（EGL 1.4 API 建离屏 ES3 上下文）
    val egl = javax.microedition.khronos.egl.EGLContext.getEGL() as javax.microedition.khronos.egl.EGL10
    val display = egl.eglGetDisplay(javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY)
    if (display == javax.microedition.khronos.egl.EGL10.EGL_NO_DISPLAY) return false
    val version = IntArray(2)
    if (!egl.eglInitialize(display, version)) return false
    var surface: javax.microedition.khronos.egl.EGLSurface? = null
    var ctx: javax.microedition.khronos.egl.EGLContext? = null
    try {
        val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
        val numConfig = IntArray(1)
        val configSpec = intArrayOf(
            javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT（ES3 上下文在 ES2 配置上建=Android 通行口径）
            javax.microedition.khronos.egl.EGL10.EGL_SURFACE_TYPE, javax.microedition.khronos.egl.EGL10.EGL_PBUFFER_BIT,
            javax.microedition.khronos.egl.EGL10.EGL_NONE,
        )
        if (!egl.eglChooseConfig(display, configSpec, configs, 1, numConfig) || numConfig[0] <= 0) return false
        val ctxAttrs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, javax.microedition.khronos.egl.EGL10.EGL_NONE)
        ctx = egl.eglCreateContext(display, configs[0], javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, ctxAttrs)
        if (ctx == null || ctx == javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT) return false
        surface = egl.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_WIDTH, 1,
                javax.microedition.khronos.egl.EGL10.EGL_HEIGHT, 1,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            ),
        )
        if (surface == null || surface == javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE) return false
        if (!egl.eglMakeCurrent(display, surface, surface, ctx)) return false
        // 真查版本串（建得成 ≠ 报得对——驱动口径复核）
        val ver = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION) ?: ""
        Log.i("TasteMapX", "R277 ES3 探针: 上下文建成 version=$ver")
        return true
    } finally {
        egl.eglMakeCurrent(
            display,
            javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
            javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
            javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT,
        )
        surface?.let { egl.eglDestroySurface(display, it) }
        ctx?.let { egl.eglDestroyContext(display, it) }
        egl.eglTerminate(display)
    }
}

/** EGL context client version 属性（EGL10 未定义常量——EGL14 同值 0x3098） */
private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
