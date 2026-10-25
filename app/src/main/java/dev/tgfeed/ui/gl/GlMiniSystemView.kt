package dev.tgfeed.ui.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.tgfeed.core.tastemap.GestureFeelPolicy
import dev.tgfeed.core.tastemap.StarSystemMini
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.ui.theme.TgFeedTokens
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R310 世界卡·恒星系 3D 小模型视图（用户需求「360° 无死角拖动观测、可点击交互」
 * 的 GL 壳——数学底座 [StarSystemMini] 纯件单测锚定）：
 *
 *  - **TextureView 承载**（普通视图层——世界卡是 Compose 叠加窗，SurfaceView 打孔
 *    会穿卡底；TextureView 合层天然正确，VideoPage 族先例）+ 独立 EGL14 ES3 上下文
 *    （RGBA8+深度 16——真球/环带真穿插吃深度缓冲；MSAA4 优先回退无 AA）；
 *  - **渲染件全复用 R281 底座**（零新造）：GlSphereMesh 球网格 / GlRingMesh 环带 /
 *    GlShaders.SPHERE（恒星真球）/PLANET（行星真球）/RING（土星环 mode 0）/KEPLER
 *    （轨道线——VS 开普勒求值与主图逐位同式）/WPOINT（恒星光晕+选中光环，全常量
 *    属性无 VBO 单点绘制）；程序经 GlShaders.buildProgram 建成；
 *  - **render-on-demand**（60fps 铁律的迷你口径）：专属 HandlerThread + Choreographer
 *    按需出帧——拖动/惯性/选中变化/尺寸变化才渲染，**静置零 GPU 占空**；惯性在
 *    GL 线程 vsync 步进（GestureFeelPolicy.decayVel 同族阻尼）；
 *  - **帧零分配**：VAO×3+全 VBO 初始化期预分配；uniform 位置缓存；颜色/矩阵暂存
 *    全字段化；热路径唯二分配=每渲染帧一枚 SpaceCam 值对象+一次快照（主图帧循环
 *    同款先例——按需帧非连续帧，量级可忽略）；
 *  - **生命周期=世界卡**：Compose DisposableEffect 离场即 shutdown（渲染线程停+
 *    程序/VBO/EGL 全释放——「关上即释放」）；GL 建链失败→onFatal→调用方诚实降级
 *    （撤掉模型段，世界卡 R301 既有信息段照旧——probeEs3 预闸+运行时双保险）；
 *  - **手势**（纪律§四：单 pointerInput 节点自收 down/move/up 判 tap-vs-drag——
 *    同节点双 pointerInput=互杀红线）：单指拖动=轨道相机旋转（deg/px 走
 *    GestureFeelPolicy.turnDegPerPx 同族），松手=时间窗测速惯性滑行，点按=
 *    StarSystemMini.pickPlanet 命中（深度近者胜）→选中高亮+onPlanetTap 换绑。
 *
 * 在飞协调：本件全新增——GlShaders/GlFrameBuilder/TasteGlRenderer 零改动（R309
 * 行星微光渲染层在飞区不撞）；着色器 uniform 接口以 R281 现网为准（R309 改的是
 * FS 内插常数非 uniform 面——若后续加 uniform 缺省 0 视觉安全）。
 */
internal class GlMiniSystemView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    // ==================== 共享状态（UI 线程写 / GL 线程读——lock 小临界） ====================

    private val lock = Object()
    private val cam = StarSystemMini.OrbitCam()
    private var sys: StarSystemMini.MiniSystem? = null
    private var sysKeyChat = 0L
    private var sysKeyPlanets: List<TasteTagPlanets.TagPlanet>? = null
    private var starColorArgb = 0xFFFFD27D.toInt()
    private var selIdx = -1
    private var selectedFold: String? = null
    private var vw = 0
    private var vh = 0
    private var dirty = true
    private var running = false
    private var glFailed = false
    private var modelDirty = true

    /** 点按热区兜底（px——PLANET_HIT_DP 适配值由 Compose 侧注入） */
    var slopPx = 40f
    /** 行星点选回调（UI 线程——世界卡换绑链接线） */
    var onPlanetTap: ((String) -> Unit)? = null
    /** GL 致命回调（UI 线程 post——调用方撤模型段诚实降级） */
    var onFatal: (() -> Unit)? = null

    // ==================== GL 线程（render-on-demand） ====================

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var surfaceTex: SurfaceTexture? = null
    private val kickQueued = AtomicBoolean(false)
    private var torn = false

    init {
        isOpaque = false // 透明合层——卡底色透出，模型浮其上
        isClickable = false // 触摸不消费——冒泡回 Compose 手势层（AndroidView 互操作惯例）
        surfaceTextureListener = this
    }

    // ---------- UI 线程口（Compose 手势层/重组驱动） ----------

    /** 模型装配（planets 身份+chatId 变才重建——重组幂等） */
    fun setModel(chatId: Long, colorArgb: Int, planets: List<TasteTagPlanets.TagPlanet>) {
        if (planets.isEmpty()) return
        synchronized(lock) {
            starColorArgb = colorArgb
            if (chatId != sysKeyChat || planets !== sysKeyPlanets) {
                sys = StarSystemMini.layout(chatId, planets)
                sysKeyChat = chatId
                sysKeyPlanets = planets
                modelDirty = true
                reselectLocked()
                Log.i(
                    "TasteMap",
                    "R310 3D模型: 布局 行星=${sys?.planets?.size ?: 0} 包络=${"%.1f".format(sys?.extent ?: 0f)}",
                )
            }
            dirty = true
        }
        requestRender()
    }

    /** 选中行星（世界卡 target.tag 换绑联动——fold 尺子与卡内同一把） */
    fun setSelectedTag(tag: String?) {
        synchronized(lock) {
            val f = tag?.let { SearchTextKit.fold(it) }
            if (f == selectedFold) return
            selectedFold = f
            reselectLocked()
            dirty = true
        }
        requestRender()
    }

    private fun reselectLocked() {
        val s = sys
        val f = selectedFold
        selIdx = if (s == null || f == null) {
            -1
        } else {
            s.planets.indexOfFirst { SearchTextKit.fold(it.tag) == f }
        }
    }

    fun dragStart() {
        synchronized(lock) {
            cam.dragStart()
            dirty = true
        }
        requestRender()
    }

    /** 拖动一步（deg/px=GestureFeelPolicy.turnDegPerPx(视口宽)——同族手感零私抄） */
    fun dragBy(tMs: Long, dxPx: Float, dyPx: Float) {
        synchronized(lock) {
            val w = vw
            if (w <= 0) return
            cam.dragBy(tMs, dxPx, dyPx, GestureFeelPolicy.turnDegPerPx(w.toFloat()))
            dirty = true
        }
        requestRender()
    }

    fun dragEnd(nowMs: Long) {
        var vy: Float
        var vp: Float
        var gy: Float
        var gp: Float
        synchronized(lock) {
            cam.dragEnd(nowMs)
            vy = cam.yawVelDeg
            vp = cam.pitchVelDeg
            gy = cam.gestureYawDeg
            gp = cam.gesturePitchDeg
            dirty = true
        }
        Log.i(
            "TasteMap",
            "R310 3D模型: 拖动 Δyaw=${"%.0f".format(gy)}° Δpitch=${"%.0f".format(gp)}°" +
                "；惯性初速 yaw=${"%.0f".format(vy)}°/s pitch=${"%.0f".format(vp)}°/s",
        )
        requestRender()
    }

    /** 点按命中（UI 线程——纯数学微秒级；命中=选中高亮+回调换绑） */
    fun tapAt(x: Float, y: Float) {
        var hit = -1
        var tag: String? = null
        synchronized(lock) {
            val s = sys ?: return
            if (vw <= 0 || vh <= 0) return
            val cam3 = StarSystemMini.camOf(
                cam.snapshot(),
                StarSystemMini.fitDist(s.extent, vw.toFloat(), vh.toFloat()),
            )
            hit = StarSystemMini.pickPlanet(x, y, vw.toFloat(), vh.toFloat(), cam3, s, slopPx, pickBuf)
            if (hit >= 0) {
                tag = s.planets[hit].tag
                selectedFold = SearchTextKit.fold(tag)
                reselectLocked()
                dirty = true
            }
        }
        if (hit >= 0 && tag != null) {
            Log.i("TasteMap", "R310 3D模型: 点选命中 第${hit + 1}颗（tag长=${tag?.length ?: 0}）——选中高亮+换绑")
            requestRender()
            onPlanetTap?.invoke(tag!!)
        } else {
            Log.i("TasteMap", "R310 3D模型: 点选未命中")
        }
    }

    private val pickBuf = FloatArray(4)

    // ---------- 生命周期 ----------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surfaceTex = st
        synchronized(lock) {
            vw = w
            vh = h
            dirty = true
        }
        if (thread == null && !torn) {
            val t = HandlerThread("r310-mini-gl")
            t.start()
            thread = t
            val hd = Handler(t.looper)
            handler = hd
            hd.post { glSetup(st) }
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        synchronized(lock) {
            vw = w
            vh = h
            dirty = true
        }
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        teardownSync()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Compose DisposableEffect 离场口（世界卡关=模型死——线程+EGL 全释放，幂等） */
    fun shutdown() {
        torn = true
        teardownSync()
    }

    private fun teardownSync() {
        val t = thread ?: return
        val hd = handler
        thread = null
        handler = null
        if (hd != null) {
            val latch = CountDownLatch(1)
            hd.post {
                glTeardown()
                latch.countDown()
            }
            runCatching { latch.await(600, TimeUnit.MILLISECONDS) }
        }
        t.quitSafely()
        synchronized(lock) { running = false }
        Log.i("TasteMap", "R310 3D模型: 释放（世界卡关/表面销毁）")
    }

    // ---------- 按需出帧（Choreographer vsync——惯性期自续帧，静置零帧） ----------

    private fun requestRender() {
        if (glFailed || torn) return
        if (!kickQueued.compareAndSet(false, true)) return
        val hd = handler ?: run {
            kickQueued.set(false)
            return
        }
        hd.post {
            kickQueued.set(false)
            if (!running || glFailed) return@post
            scheduleVsync()
        }
    }

    /** GL 线程：vsync 回调登记（cbPosted 只在本线程读写） */
    private var cbPosted = false
    private var lastVsyncNanos = -1L

    private fun scheduleVsync() {
        if (cbPosted) return
        cbPosted = true
        Choreographer.getInstance().postFrameCallback { nanos ->
            cbPosted = false
            onVsync(nanos)
        }
    }

    private fun onVsync(frameNanos: Long) {
        if (!running || glFailed) return
        val dtMs = if (lastVsyncNanos < 0L) {
            16.7f
        } else {
            ((frameNanos - lastVsyncNanos) / 1e6f).coerceIn(0f, 50f)
        }
        lastVsyncNanos = frameNanos
        var inertiaAlive = false
        var doRender = false
        synchronized(lock) {
            if (!cam.dragging && (cam.yawVelDeg != 0f || cam.pitchVelDeg != 0f)) {
                cam.stepInertia(dtMs)
                dirty = true
            }
            if (cam.yawVelDeg != 0f || cam.pitchVelDeg != 0f) inertiaAlive = true
            if (dirty) {
                dirty = false
                doRender = true
            }
        }
        if (doRender) renderFrame()
        if (inertiaAlive) scheduleVsync() // 惯性自续帧（停机即断链=静置零帧）
    }

    // ---------- GL 装配/渲染（GL 线程专属） ----------

    private var eglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var glReady = false

    private fun glSetup(st: SurfaceTexture) {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay 空")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) error("eglInitialize 败")
            val cfg = chooseConfig(msaa = true) ?: chooseConfig(msaa = false) ?: error("无可用 EGL 配置")
            eglCtx = EGL14.eglCreateContext(
                eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (eglCtx == EGL14.EGL_NO_CONTEXT) error("ES3 上下文建败")
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, cfg, st, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) error("窗面建败")
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)) error("makeCurrent 败")
            EGL14.eglSwapInterval(eglDisplay, 1)
            glCore.setup()
            glReady = true
            synchronized(lock) {
                running = true
                dirty = true
            }
            Log.i("TasteMap", "R310 3D模型: GL 管线建成（程序×5+球网格+环带+轨道圈——R281 件复用）")
            requestRender()
        } catch (t: Throwable) {
            glFailed = true
            Log.i("TasteMap", "R310 3D模型: GL 致命 ${t.javaClass.simpleName}: ${t.message}——世界卡撤模型段兜底")
            glTeardown()
            post { onFatal?.invoke() }
        }
    }

    private fun chooseConfig(msaa: Boolean): android.opengl.EGLConfig? {
        val attrs = if (msaa) {
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, 4,
                EGL14.EGL_NONE,
            )
        } else {
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
        }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return null
        return configs[0]
    }

    private fun glTeardown() {
        if (glReady) {
            runCatching { glCore.release() }
            glReady = false
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglCtx)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglCtx = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    /** GL 线程逐帧（按需帧）：快照→渲染→换面 */
    private fun renderFrame() {
        val s: StarSystemMini.MiniSystem?
        val pose: StarSystemMini.CamPose
        val sel: Int
        val argb: Int
        val w: Int
        val h: Int
        val rebuildColors: Boolean
        synchronized(lock) {
            s = sys
            pose = cam.snapshot()
            sel = selIdx
            argb = starColorArgb
            w = vw
            h = vh
            rebuildColors = modelDirty
            modelDirty = false
        }
        if (!glReady || w <= 0 || h <= 0) return
        val sysNow = s ?: return
        if (rebuildColors) glCore.rebuildColors(sysNow, argb)
        val dist = StarSystemMini.fitDist(sysNow.extent, w.toFloat(), h.toFloat())
        val cam3 = StarSystemMini.camOf(pose, dist)
        glCore.draw(sysNow, cam3, w.toFloat(), h.toFloat(), sel, argb)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // ==================== GL 渲染核（GL 线程 confined——零锁） ====================

    private val glCore = GlCore()

    private class GlCore {
        // ---- 程序（R281 源串复用） ----
        private var progSphere = 0
        private var progPlanet = 0
        private var progRing = 0
        private var progOrbit = 0
        private var progPoint = 0
        // ---- 网格/顶点（初始化期预分配——帧零分配） ----
        private var vaoSphere = 0
        private var vaoRing = 0
        private var vaoOrbit = 0
        private var vaoHalo = 0
        private var sphereIdxCount = 0
        private var ringIdxCount = 0
        private val bufIds = IntArray(4)
        private val vaoIds = IntArray(4)
        // ---- 暂存（字段化——帧零分配） ----
        private val vp = FloatArray(16)
        private val tmpV = FloatArray(16)
        private val tmpP = FloatArray(16)
        private val hsv = FloatArray(3)
        private val rgbA = FloatArray(3)
        private val rgbB = FloatArray(3)
        private val rgbC = FloatArray(3)
        private val planetRgb = FloatArray(StarSystemMini.MAX_PLANETS * 3)
        // ---- 恒星色三件（rebuildColors 算定） ----
        private var starTemp = GlPalette.TEMP_WHITE
        private var starSeed = 0f
        private var camX = 0f
        private var camY = 0f
        private var camZ = 0f
        // uniform 位置缓存
        private lateinit var uSphere: IntArray
        private lateinit var uPlanet: IntArray
        private lateinit var uRing: IntArray
        private lateinit var uOrbit: IntArray
        private lateinit var uPoint: IntArray

        fun setup() {
            progSphere = GlShaders.buildProgram(GlShaders.SPHERE_VS, GlShaders.SPHERE_FS)
            progPlanet = GlShaders.buildProgram(GlShaders.PLANET_VS, GlShaders.PLANET_FS)
            progRing = GlShaders.buildProgram(GlShaders.RING_VS, GlShaders.RING_FS)
            progOrbit = GlShaders.buildProgram(GlShaders.KEPLER_VS, GlShaders.KEPLER_LINE_FS)
            progPoint = GlShaders.buildProgram(GlShaders.WPOINT_VS, GlShaders.POINT_FS)
            uSphere = locs(progSphere, SPHERE_U)
            uPlanet = locs(progPlanet, PLANET_U)
            uRing = locs(progRing, RING_U)
            uOrbit = locs(progOrbit, ORBIT_U)
            uPoint = locs(progPoint, POINT_U)
            // ---- VAO/VBO 三件（球/环带/轨道圈） ----
            GLES30.glGenBuffers(4, bufIds, 0)
            GLES30.glGenVertexArrays(4, vaoIds, 0)
            // 球（loc0=法线 3f）
            val sVerts = floatBuf(GlSphereMesh.buildVertices())
            val sIdx = shortBuf(GlSphereMesh.buildIndices())
            sphereIdxCount = GlSphereMesh.STACKS * GlSphereMesh.SLICES * 6
            vaoSphere = vaoIds[0]
            GLES30.glBindVertexArray(vaoSphere)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufIds[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sVerts.capacity() * 4, sVerts, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufIds[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, sIdx.capacity() * 2, sIdx, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            // 环带（loc0=pos 3f, loc1=radTheta 2f, stride 20）
            val rVerts = floatBuf(GlRingMesh.buildVertices())
            val rIdx = shortBuf(GlRingMesh.buildIndices())
            ringIdxCount = GlRingMesh.indexCount
            vaoRing = vaoIds[1]
            GLES30.glBindVertexArray(vaoRing)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufIds[2])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, rVerts.capacity() * 4, rVerts, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufIds[3])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, rIdx.capacity() * 2, rIdx, GLES30.GL_STATIC_DRAW)
            // 轨道圈（loc0=aNu 1f；aCoef/aSizeW/aColor=常量属性逐 draw 给——独立 VBO）
            val orbitN = StarSystemMini.ORBIT_SEGMENTS + 1
            val nus = FloatArray(orbitN)
            for (k in 0 until orbitN) nus[k] = k.toFloat() / StarSystemMini.ORBIT_SEGMENTS * 6.2831853f
            val orbBuf = IntArray(1)
            GLES30.glGenBuffers(1, orbBuf, 0)
            orbitVbo = orbBuf[0]
            vaoOrbit = vaoIds[2]
            GLES30.glBindVertexArray(vaoOrbit)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, orbitVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, nus.size * 4, floatBuf(nus), GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 1, GLES30.GL_FLOAT, false, 4, 0)
            // 光晕 VAO（全常量属性——零数组）
            vaoHalo = vaoIds[3]
            GLES30.glBindVertexArray(vaoHalo)
            GLES30.glBindVertexArray(0)
        }

        private var orbitVbo = 0

        /** 模型换装（行星色族+恒星三件色——镜像 GlFrameBuilder.addSphere/buildPlanetBody 同式） */
        fun rebuildColors(sys: StarSystemMini.MiniSystem, starArgb: Int) {
            // 恒星三段色（镜像 addSphere：s 钳 0.38..0.62 / v 钳 0.62..0.88 + 温度档分支）
            GlPalette.hsvOf(starArgb, hsv)
            hsv[1] = hsv[1].coerceIn(0.38f, 0.62f)
            hsv[2] = hsv[2].coerceIn(0.62f, 0.88f)
            val sh = hsv[0]
            val ss = hsv[1]
            val sv = hsv[2]
            starTemp = when {
                (sh in 10f..70f) || sh >= 340f -> GlPalette.TEMP_WARM
                sh in 170f..310f -> GlPalette.TEMP_COOL
                else -> GlPalette.TEMP_WHITE
            }
            when (starTemp) {
                GlPalette.TEMP_WARM -> GlPalette.hsvToRgbF(28f, 0.42f, 1f, rgbA)
                GlPalette.TEMP_COOL -> GlPalette.hsvToRgbF(215f, 0.10f, 1f, rgbA)
                else -> GlPalette.hsvToRgbF(sh, ss * 0.35f, 1f, rgbA)
            }
            GlPalette.hsvToRgbF(sh, ss, sv, rgbB)
            when (starTemp) {
                GlPalette.TEMP_WARM -> GlPalette.hsvToRgbF(358f, (ss + 0.25f).coerceAtMost(0.9f), sv * 0.30f, rgbC)
                GlPalette.TEMP_COOL -> GlPalette.hsvToRgbF(48f, 0.52f, sv * 0.55f, rgbC)
                else -> GlPalette.hsvToRgbF((sh + 14f) % 360f, ss, sv * 0.38f, rgbC)
            }
            starSeed = TasteTagPlanets.h01(sys.hashCode() and 0x7FFFFFFF, 373) * 97f
            // 行星色族（镜像 buildPlanetBody：恒星色 HSV 相位移 hueShiftDeg，饱钳 0.42..0.60）
            for (i in sys.planets.indices) {
                val p = sys.planets[i]
                GlPalette.hsvOf(starArgb, hsv)
                hsv[0] = (hsv[0] + p.hueShiftDeg + 360f) % 360f
                hsv[1] = hsv[1].coerceIn(0.42f, 0.60f)
                val c = GlPalette.hsvToRgb(hsv[0], hsv[1], hsv[2])
                planetRgb[i * 3] = ((c shr 16) and 0xFF) / 255f
                planetRgb[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
                planetRgb[i * 3 + 2] = (c and 0xFF) / 255f
            }
        }

        /** 逐帧绘制（快照驱动——状态已在 renderFrame 侧拷定，本口纯 GL） */
        fun draw(
            sys: StarSystemMini.MiniSystem,
            cam: TasteSpace.SpaceCam,
            w: Float,
            h: Float,
            selIdx: Int,
            starArgb: Int,
        ) {
            camX = cam.px
            camY = cam.py
            camZ = cam.pz
            GLES30.glViewport(0, 0, w.toInt(), h.toInt())
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GlCameraMatrix.viewProjectionOf(cam, w, h, StarSystemMini.MINI_LENS, vp, tmpV, tmpP)
            val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
            // ---- 1. 光晕族（深度关——球体后画自会盖心；恒星光晕+选中光环） ----
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(progPoint)
            GLES30.glUniformMatrix4fv(uPoint[PT_VP], 1, false, vp, 0)
            GLES30.glUniform1f(uPoint[PT_FOCAL], focal)
            GLES30.glBindVertexArray(vaoHalo)
            // 恒星光晕（体色淡晕=主图点星光晕同族观感）
            val sr = ((starArgb shr 16) and 0xFF) / 255f
            val sg = ((starArgb shr 8) and 0xFF) / 255f
            val sb = (starArgb and 0xFF) / 255f
            drawHalo(0f, 0f, 0f, StarSystemMini.STAR_R * 6.0f, sr, sg, sb, 0.16f, rgbA[0], rgbA[1], rgbA[2])
            // 选中光环（选中行星的「你在这」锚——体色亮晕）
            if (selIdx >= 0 && selIdx < sys.planets.size) {
                val p = sys.planets[selIdx]
                drawHalo(
                    p.x, p.y, p.z, p.worldR * 5.4f,
                    planetRgb[selIdx * 3], planetRgb[selIdx * 3 + 1], planetRgb[selIdx * 3 + 2], 0.42f,
                    1f, 1f, 1f,
                )
            }
            // ---- 2. 恒星真球（深度写开） ----
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDepthMask(true)
            GLES30.glUseProgram(progSphere)
            GLES30.glUniformMatrix4fv(uSphere[SH_VP], 1, false, vp, 0)
            GLES30.glUniform3f(uSphere[SH_CENTER], 0f, 0f, 0f)
            GLES30.glUniform1f(uSphere[SH_RADIUS], StarSystemMini.STAR_R)
            GLES30.glUniform3f(uSphere[SH_CAMPOS], camX, camY, camZ)
            GLES30.glUniform3f(uSphere[SH_CORE], rgbA[0], rgbA[1], rgbA[2])
            GLES30.glUniform3f(uSphere[SH_STAR], rgbB[0], rgbB[1], rgbB[2])
            GLES30.glUniform3f(uSphere[SH_EDGE], rgbC[0], rgbC[1], rgbC[2])
            GLES30.glUniform1f(uSphere[SH_ALPHA], 1f)
            GLES30.glUniform1f(uSphere[SH_GRAIN], 0.20f)
            GLES30.glUniform1f(uSphere[SH_SEED], starSeed)
            GLES30.glUniform1f(uSphere[SH_SPIN], 0f)
            GLES30.glUniform1f(uSphere[SH_TIME], 0f)
            GLES30.glUniform1f(uSphere[SH_SPOT], 0.35f)
            GLES30.glUniform1f(uSphere[SH_FLAME], 0.45f)
            GLES30.glUniform1f(uSphere[SH_HOT], if (starTemp == GlPalette.TEMP_COOL) 1f else 0f)
            // R311 临边昏暗系数（SPHERE_FS 契约新增——小模型恒星按旧固定 u=0.66 等价档：
            // 中性 0.62≈太阳实测档，观感与主图旧版一致）
            GLES30.glUniform1f(uSphere[SH_LIMB], 0.62f)
            GLES30.glBindVertexArray(vaoSphere)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIdxCount, GLES30.GL_UNSIGNED_SHORT, 0)
            // ---- 3. 行星真球族（真 3D 平行光=行星→恒星向量） ----
            GLES30.glUseProgram(progPlanet)
            GLES30.glUniformMatrix4fv(uPlanet[PL_VP], 1, false, vp, 0)
            GLES30.glUniform3f(uPlanet[PL_CAMPOS], camX, camY, camZ)
            for (i in sys.planets.indices) {
                val p = sys.planets[i]
                // 光向=行星→恒星（恒星=原点——向量即 −pos 归一）
                var lx = -p.x
                var ly = -p.y
                var lz = -p.z
                val ll = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-6f)
                lx /= ll
                ly /= ll
                lz /= ll
                // 选中提亮（×1.25——点选反馈的第三层：光环+轨道线+本体）
                val boost = if (i == selIdx) 1.25f else 1f
                GLES30.glUniform3f(uPlanet[PL_CENTER], p.x, p.y, p.z)
                GLES30.glUniform1f(uPlanet[PL_RADIUS], p.worldR)
                GLES30.glUniform3f(uPlanet[PL_LIGHT], lx, ly, lz)
                GLES30.glUniform3f(
                    uPlanet[PL_BASE],
                    (planetRgb[i * 3] * boost).coerceAtMost(1f),
                    (planetRgb[i * 3 + 1] * boost).coerceAtMost(1f),
                    (planetRgb[i * 3 + 2] * boost).coerceAtMost(1f),
                )
                GLES30.glUniform1f(uPlanet[PL_SEED], p.seedF)
                GLES30.glUniform1f(uPlanet[PL_SPIN], p.spin0)
                GLES30.glUniform1f(uPlanet[PL_GAS], if (p.gas) 1f else 0f)
                GLES30.glUniform1f(uPlanet[PL_ALPHA], 1f)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIdxCount, GLES30.GL_UNSIGNED_SHORT, 0)
            }
            // ---- 4. 行星环（深度测不写——与母球真穿插；土星环 mode 0） ----
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(progRing)
            GLES30.glUniformMatrix4fv(uRing[RG_VP], 1, false, vp, 0)
            GLES30.glUniform3f(uRing[RG_CAMPOS], camX, camY, camZ)
            GLES30.glBindVertexArray(vaoRing)
            for (i in sys.planets.indices) {
                val p = sys.planets[i]
                if (!p.ringed) continue
                var lx = -p.x
                var ly = -p.y
                var lz = -p.z
                val ll = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-6f)
                lx /= ll
                ly /= ll
                lz /= ll
                GLES30.glUniform3f(uRing[RG_CENTER], p.x, p.y, p.z)
                GLES30.glUniform1f(uRing[RG_RADIUS], p.worldR)
                GLES30.glUniform1f(uRing[RG_TILTX], p.ringTiltX)
                GLES30.glUniform1f(uRing[RG_TILTZ], p.ringTiltZ)
                GLES30.glUniform3f(uRing[RG_CENTER_FS], p.x, p.y, p.z)
                GLES30.glUniform3f(uRing[RG_LIGHT], lx, ly, lz)
                GLES30.glUniform3f(uRing[RG_COLOR], planetRgb[i * 3], planetRgb[i * 3 + 1], planetRgb[i * 3 + 2])
                GLES30.glUniform1f(uRing[RG_PLANETR], p.worldR)
                GLES30.glUniform1f(uRing[RG_ALPHA], if (i == selIdx) 1f else 0.85f)
                GLES30.glUniform1f(uRing[RG_SEED], p.seedF)
                GLES30.glUniform1f(uRing[RG_TIME], 0f)
                GLES30.glUniform1f(uRing[RG_MODE], 0f)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, ringIdxCount, GLES30.GL_UNSIGNED_SHORT, 0)
            }
            // ---- 5. 轨道线（KEPLER 开普勒 VS——与主图逐位同式；选中轨道提亮） ----
            GLES30.glUseProgram(progOrbit)
            GLES30.glUniformMatrix4fv(uOrbit[KB_VP], 1, false, vp, 0)
            GLES30.glUniform3f(uOrbit[KB_CENTER], 0f, 0f, 0f)
            GLES30.glUniform1f(uOrbit[KB_FOCAL], focal)
            val incSin = kotlin.math.sin(sys.incRad)
            val incCos = kotlin.math.cos(sys.incRad)
            GLES30.glUniform1f(uOrbit[KB_INCSIN], incSin)
            GLES30.glUniform1f(uOrbit[KB_INCCOS], incCos)
            GLES30.glBindVertexArray(vaoOrbit)
            GLES30.glVertexAttrib1f(1, 1f) // aCoef=1（径向系数恒一）
            GLES30.glVertexAttrib1f(2, 0f) // aSizeW=0（线径不由点径驱动）
            for (i in sys.planets.indices) {
                val p = sys.planets[i]
                GLES30.glUniform1f(uOrbit[KB_PERI], p.peri)
                GLES30.glUniform1f(uOrbit[KB_SEMIR], p.orbitSemi)
                GLES30.glUniform1f(uOrbit[KB_ECC], p.ecc)
                if (i == selIdx) {
                    GLES30.glVertexAttrib4f(3, sr, sg, sb, 0.42f)
                } else {
                    GLES30.glVertexAttrib4f(3, 1f, 1f, 1f, 0.10f)
                }
                GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, StarSystemMini.ORBIT_SEGMENTS + 1)
            }
            GLES30.glBindVertexArray(0)
        }

        /** 世界点光晕单点（全常量属性——attribute-less 绘制，ES3 核心口径） */
        private fun drawHalo(
            x: Float, y: Float, z: Float, sizeW: Float,
            r: Float, g: Float, b: Float, a: Float,
            cr: Float, cg: Float, cb: Float,
        ) {
            GLES30.glVertexAttrib3f(0, x, y, z)
            GLES30.glVertexAttrib1f(1, sizeW)
            GLES30.glVertexAttrib1f(2, 0.55f) // 核心比（软边内实段）
            GLES30.glVertexAttrib4f(3, r, g, b, a)
            GLES30.glVertexAttrib3f(4, cr, cg, cb)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1)
        }

        fun release() {
            val progs = intArrayOf(progSphere, progPlanet, progRing, progOrbit, progPoint)
            for (p in progs) if (p != 0) GLES30.glDeleteProgram(p)
            progSphere = 0; progPlanet = 0; progRing = 0; progOrbit = 0; progPoint = 0
            GLES30.glDeleteBuffers(4, bufIds, 0)
            if (orbitVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(orbitVbo), 0)
            GLES30.glDeleteVertexArrays(4, vaoIds, 0)
            orbitVbo = 0
        }

        companion object {
            // uniform 名表（位置缓存下标——编译期定序）
            private val SPHERE_U = arrayOf(
                "uVP", "uCenter", "uRadius", "uCamPos", "uCore", "uStar", "uEdge",
                "uAlpha", "uGrainAmp", "uSeed", "uSpin", "uTime", "uSpotAmp", "uFlameAmp", "uHot",
                "uLimbK", // R311：临边昏暗系数（SPHERE_FS 契约新增——色温从动）
            )
            private const val SH_VP = 0
            private const val SH_CENTER = 1
            private const val SH_RADIUS = 2
            private const val SH_CAMPOS = 3
            private const val SH_CORE = 4
            private const val SH_STAR = 5
            private const val SH_EDGE = 6
            private const val SH_ALPHA = 7
            private const val SH_GRAIN = 8
            private const val SH_SEED = 9
            private const val SH_SPIN = 10
            private const val SH_TIME = 11
            private const val SH_SPOT = 12
            private const val SH_FLAME = 13
            private const val SH_HOT = 14
            private const val SH_LIMB = 15 // R311：临边昏暗系数
            private val PLANET_U = arrayOf(
                "uVP", "uCenter", "uRadius", "uCamPos", "uLightDir", "uBase", "uSeed", "uSpin", "uGas", "uAlpha",
            )
            private const val PL_VP = 0
            private const val PL_CENTER = 1
            private const val PL_RADIUS = 2
            private const val PL_CAMPOS = 3
            private const val PL_LIGHT = 4
            private const val PL_BASE = 5
            private const val PL_SEED = 6
            private const val PL_SPIN = 7
            private const val PL_GAS = 8
            private const val PL_ALPHA = 9
            private val RING_U = arrayOf(
                "uVP", "uCenter", "uRadius", "uTiltX", "uTiltZ",
                "uCamPos", "uCenter", "uLightDir", "uColor", "uPlanetR", "uAlpha", "uSeed", "uTime", "uMode",
            )
            private const val RG_VP = 0
            private const val RG_CENTER = 1
            private const val RG_RADIUS = 2
            private const val RG_TILTX = 3
            private const val RG_TILTZ = 4
            private const val RG_CAMPOS = 5
            private const val RG_CENTER_FS = 6 // uCenter 的 FS 面（同名双查——VS/FS 各自位置）
            private const val RG_LIGHT = 7
            private const val RG_COLOR = 8
            private const val RG_PLANETR = 9
            private const val RG_ALPHA = 10
            private const val RG_SEED = 11
            private const val RG_TIME = 12
            private const val RG_MODE = 13
            private val ORBIT_U = arrayOf("uVP", "uCenter", "uPeri", "uIncSin", "uIncCos", "uSemiR", "uEcc", "uFocalPx")
            private const val KB_VP = 0
            private const val KB_CENTER = 1
            private const val KB_PERI = 2
            private const val KB_INCSIN = 3
            private const val KB_INCCOS = 4
            private const val KB_SEMIR = 5
            private const val KB_ECC = 6
            private const val KB_FOCAL = 7
            private val POINT_U = arrayOf("uVP", "uFocalPx")
            private const val PT_VP = 0
            private const val PT_FOCAL = 1
        }
    }

    companion object {
        /** EGL context client version 属性（EGL14 未定义 ES3 位——0x40=EGL_OPENGL_ES3_BIT_KHR） */
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private fun locs(prog: Int, names: Array<String>): IntArray {
            val out = IntArray(names.size)
            for (i in names.indices) out[i] = GLES30.glGetUniformLocation(prog, names[i])
            return out
        }

        private fun floatBuf(arr: FloatArray): java.nio.FloatBuffer =
            ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(arr); position(0) }

        private fun shortBuf(arr: ShortArray): java.nio.ShortBuffer =
            ByteBuffer.allocateDirect(arr.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                .apply { put(arr); position(0) }
    }
}

/**
 * R310 世界卡·恒星系 3D 小模型（Compose 壳）：单 pointerInput 手势节点
 * （§四红线——tap/drag 同节点自判，零互杀）；行星表空=不装配（诚实空态）。
 * R342 起为**共享件**（世界卡/恒星信息卡两消费者）：星系内容卡直接复用本件；
 * 非星系模型卡沿「单 pointerInput 自收 down/move/up 判 tap-vs-drag」模式新写
 * （第二消费者出现才抽通用手势件——YAGNI）。**凡挂本件的卡，卡根必挂
 * [dev.tgfeed.ui.tasteSheetGestureGate]（R342 隔离政策一票纪律）**。
 *
 * @param selectedTag 世界卡当前绑定 tag（选中高亮联动源——fold 尺子同卡内；
 *   信息卡全景档传 null=零高亮）
 * @param onPlanetTap 点行星回调（宿主接线=同星换绑 keepPose 原链）
 * @param onFatal GL 致命回调（宿主撤模型段——诚实降级）
 * @param onDragStateChange R342：拖动态回调（down=true/松手=false——卡根
 *   手势门防误收卡旗的协调口；默认 null=零行为变化。闭包捕获 remember 态
 *   对象=跨重组有效；pointerInput 重建即先复位防陈旧旗）
 */
@Composable
internal fun StarSystemMiniModel(
    chatId: Long,
    starColorArgb: Int,
    planets: List<TasteTagPlanets.TagPlanet>,
    selectedTag: String?,
    onPlanetTap: (String) -> Unit,
    onFatal: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStateChange: ((Boolean) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val slopPx = with(density) { GestureFeelPolicy.PLANET_HIT_DP.dp.toPx() }
    val viewRef = androidx.compose.runtime.remember { arrayOfNulls<GlMiniSystemView>(1) }
    Box(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.InkBlack)
            .pointerInput(chatId) {
                // R342：重建即复位拖动态旗（换星/卡重组清陈旧 true——门防误收不卡死）
                onDragStateChange?.invoke(false)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val v = viewRef[0] ?: return@awaitEachGesture
                    v.dragStart()
                    onDragStateChange?.invoke(true)
                    var movedPx = 0f
                    var isTap = true
                    val downMs = down.uptimeMillis
                    var lastX = down.position.x
                    var lastY = down.position.y
                    val downId = down.id
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == downId } ?: break
                        if (ch.pressed) {
                            val dx = ch.position.x - lastX
                            val dy = ch.position.y - lastY
                            lastX = ch.position.x
                            lastY = ch.position.y
                            movedPx += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                            if (movedPx > viewConfiguration.touchSlop) isTap = false
                            if (dx != 0f || dy != 0f) v.dragBy(ch.uptimeMillis, dx, dy)
                            ch.consume()
                        } else {
                            if (isTap && ch.uptimeMillis - downMs <= TAP_MAX_MS) {
                                v.tapAt(ch.position.x, ch.position.y)
                            }
                            v.dragEnd(ch.uptimeMillis)
                            onDragStateChange?.invoke(false)
                            break
                        }
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GlMiniSystemView(ctx).apply {
                    this.slopPx = slopPx
                    viewRef[0] = this
                }
            },
            update = { v ->
                // 回调每重组换装（factory 只跑一巡——换装保最新捕获，防世界卡换绑后
                // target/foldTag 陈旧）
                v.onPlanetTap = { tag -> onPlanetTap(tag) }
                v.onFatal = { onFatal() }
                v.slopPx = slopPx
                v.setModel(chatId, starColorArgb, planets)
                v.setSelectedTag(selectedTag)
            },
            onRelease = { v ->
                if (viewRef[0] === v) viewRef[0] = null
                v.shutdown()
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            viewRef[0]?.shutdown()
            viewRef[0] = null
        }
    }
}

/** 点按时长上限（毫秒——判定常数非动效时长，不入 MotionTokens） */
private const val TAP_MAX_MS = 400L

/**
 * R342 模型区高度三档共享口（世界卡/恒星信息卡同用——单一出处；随屏高自适应：
 * 世界卡无上滚故防矮屏/横屏顶行挤出，信息卡滚动体同尺防卡身过长）。
 * 自 [PlanetWorldCard] 原内联 when 段抽出（API 只加——两卡统一）。
 */
internal fun miniModelHeightDp(screenHeightDp: Int): androidx.compose.ui.unit.Dp = when {
    screenHeightDp >= 700 -> 180.dp
    screenHeightDp >= 560 -> 148.dp
    else -> 118.dp
}
