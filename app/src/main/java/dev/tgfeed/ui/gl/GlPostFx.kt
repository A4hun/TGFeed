package dev.tgfeed.ui.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R281 GL 后处理管线（任务③——bloom 辉光+镜头光斑；GL 句柄壳，全在 GL 线程）·
 * **R383 P3 体积趟扩建**：
 *
 * 管线（全 RGBA8——ES 3.0 核心零扩展纪律）：
 * ```
 * 场景层（全部 op 回放）→ sceneFbo（RGBA8 纹理 + 深度纹理 R383）
 *   → [R383 体积趟（贴脸窗激活才跑——挂位=blur 前）：
 *      raymarch 半/quarter FBO（深度纹理逐样本遮挡裁剪；raymarch 噪声=单元 2 常驻 3D 体（R392））
 *      → 深度感知合成 sceneFbo→sceneFbo2（premultiplied over）
 *      → front 换装 sceneTex2（ping-pong）+深度附件 invalidate（体积趟已消费）]
 *   → quarter 横向模糊+亮部提取合趟（blurA——Mali 税收口：FBO 切换 6→3，
 *     亮部提取独立半分辨率级退役，见 r281_notes 平板实测节）
 *   → quarter 纵向模糊（blurB）
 *   → 合成上屏（默认 FB：场景 + bloom×0.85 加性 + 程序镜头光斑 top3）
 * ```
 *  - **EGL 丢失重建纪律**：全部句柄由 [recreate] 建成（onSurfaceCreated 调用）；
 *  - resize=[ensureSize] 幂等重建（FBO 尺寸随视口）；帧路径零分配零编译；
 *  - 深度=R383 **纹理化**（GL_DEPTH_COMPONENT24+NEAREST——ES3.0 深度纹理非可滤；
 *    体积趟逐样本遮挡裁剪消费；RB 时代的「深度只活在本帧场景 pass」invalidate
 *    纪律保留——invalidate 点后移到体积趟消费后（体积趟 inactive 时仍在合成趟
 *    首行原位——两路径等价省回写））；
 *  - 默认 FB 只吃合成全屏三角——EGL 配置深度位零诉求。
 */
internal class GlPostFx {

    private var sceneFbo = 0
    private var sceneTex = 0

    /** R383 场景深度纹理（原 RB 纹理化——体积趟 NEAREST 采样消费） */
    private var sceneDepthTex = 0

    /** R383 合成目标 B 面（全幅色 ping-pong——体积合成趟写出，front 换装） */
    private var sceneFbo2 = 0
    private var sceneTex2 = 0

    /** R383 raymarch 体积 FBO（半/quarter 分辨率 RGBA8——步数分档随档重建） */
    private var rayFbo = 0
    private var rayTex = 0
    private var rayW = 0
    private var rayH = 0

    /** front 场景色（beginScene=A 面；体积合成后=B 面——blur/上屏读此口） */
    private var frontFbo = 0
    private var frontTex = 0

    private var blurFboA = 0
    private var blurTexA = 0
    private var blurFboB = 0
    private var blurTexB = 0
    private var curW = 0
    private var curH = 0

    private var progBlur = 0
    private var progComposite = 0
    private var postVbo = 0

    // R383 体积两程序（raymarch+深度感知合成——POST_VS 全屏三角共用）
    private var progRay = 0
    private var progVolComp = 0

    /** R383 体积趟本帧已跑（深度 invalidate 归属位——跑过=体积趟已消费） */
    private var volumeRenderedThisFrame = false

    // uniform 位置（recreate 时一次查定）
    private var uTexBlur = -1
    private var uDirBlur = -1
    private var uThreshBlur = -1
    private var uSceneComp = -1
    private var uBloomComp = -1
    private var uBloomGainComp = -1
    private var uBodyBloomCutComp = -1
    private var uFlarePosComp = -1
    private var uFlareColorComp = -1
    private var uAspectComp = -1

    // R383 raymarch uniform 位（16 件——GlVolumeCall 一束上传）
    private var uCamPosRay = -1
    private var uCamRightRay = -1
    private var uCamUpRay = -1
    private var uCamFwdRay = -1
    private var uTanHalfRay = -1
    private var uVpRay = -1
    private var uSceneDepthRay = -1
    private var uCenterRay = -1
    private var uRadiusRay = -1
    private var uColorHaRay = -1
    private var uColorOiiiRay = -1
    private var uHaMixRay = -1
    private var uAlphaRay = -1
    private var uSeedRay = -1
    private var uTimeRay = -1
    private var uStepsRay = -1

    /** R392 噪声体 sampler 位（单元 2 常驻单源=TasteGlRenderer——本类只指派不绑定） */
    private var uNoiseVolRay = -1

    // R383 体积合成 uniform 位
    private var uSceneVolComp = -1
    private var uVolumeVolComp = -1

    /** 光斑 uniform 暂存（帧零分配纪律——字段级复用，写满 3 槽零填充） */
    private val flarePosArr = FloatArray(12)
    private val flareColArr = FloatArray(9)

    /** bloom 增益（加性合成强度——贴脸亮星溢出观感的总闸） */
    private val bloomGain = 0.85f

    /** bloom 亮部软阈值（亮度 0.70~0.95 带——亮星核/光子环/吸积盘内缘/受光高光溢出） */
    private val bloomThreshold = 0.70f

    /** R412d 贴脸恒星本体 bloom 削减比（大面积亮盘处 ×(1−0.45)——不关闭，保表面细节） */
    private val bodyBloomCut = 0.45f

    /**
     * 建成（onSurfaceCreated 调用——旧句柄先释放）。
     * R392：[noiseVolTex]=渲染器烘焙上传的 3D 噪声体句柄——**纹理绑定本体不重复**
     * （单元 2 常驻单源=TasteGlRenderer），本类只把 progRay 的 uNoiseVol sampler
     * 指派到单元 2；调用点必须排在渲染器噪声体上传之后（EGL 重建=全链重走）。
     */
    fun recreate(noiseVolTex: Int) {
        release()
        progBlur = GlShaders.buildProgram(GlShaders.POST_VS, GlShaders.BLUR_FS)
        progComposite = GlShaders.buildProgram(GlShaders.POST_VS, GlShaders.COMPOSITE_FS)
        // R383 体积两程序（raymarch highp 噪声件+合成 mediump 静件）
        progRay = GlShaders.buildProgram(GlShaders.POST_VS, GlShaders.RAYMARCH_FS)
        progVolComp = GlShaders.buildProgram(GlShaders.POST_VS, GlShaders.VOLUME_COMPOSITE_FS)
        uTexBlur = GLES30.glGetUniformLocation(progBlur, "uTex")
        uDirBlur = GLES30.glGetUniformLocation(progBlur, "uDir")
        uThreshBlur = GLES30.glGetUniformLocation(progBlur, "uThresh")
        uSceneComp = GLES30.glGetUniformLocation(progComposite, "uScene")
        uBloomComp = GLES30.glGetUniformLocation(progComposite, "uBloom")
        uBloomGainComp = GLES30.glGetUniformLocation(progComposite, "uBloomGain")
        uBodyBloomCutComp = GLES30.glGetUniformLocation(progComposite, "uBodyBloomCut")
        uFlarePosComp = GLES30.glGetUniformLocation(progComposite, "uFlarePos")
        uFlareColorComp = GLES30.glGetUniformLocation(progComposite, "uFlareColor")
        uAspectComp = GLES30.glGetUniformLocation(progComposite, "uAspect")
        uCamPosRay = GLES30.glGetUniformLocation(progRay, "uCamPos")
        uCamRightRay = GLES30.glGetUniformLocation(progRay, "uCamRight")
        uCamUpRay = GLES30.glGetUniformLocation(progRay, "uCamUp")
        uCamFwdRay = GLES30.glGetUniformLocation(progRay, "uCamFwd")
        uTanHalfRay = GLES30.glGetUniformLocation(progRay, "uTanHalf")
        uVpRay = GLES30.glGetUniformLocation(progRay, "uVP")
        uSceneDepthRay = GLES30.glGetUniformLocation(progRay, "uSceneDepth")
        uCenterRay = GLES30.glGetUniformLocation(progRay, "uCenter")
        uRadiusRay = GLES30.glGetUniformLocation(progRay, "uRadius")
        uColorHaRay = GLES30.glGetUniformLocation(progRay, "uColorHa")
        uColorOiiiRay = GLES30.glGetUniformLocation(progRay, "uColorOiii")
        uHaMixRay = GLES30.glGetUniformLocation(progRay, "uHaMix")
        uAlphaRay = GLES30.glGetUniformLocation(progRay, "uAlpha")
        uSeedRay = GLES30.glGetUniformLocation(progRay, "uSeed")
        uTimeRay = GLES30.glGetUniformLocation(progRay, "uTime")
        uStepsRay = GLES30.glGetUniformLocation(progRay, "uSteps")
        uNoiseVolRay = GLES30.glGetUniformLocation(progRay, "uNoiseVol")
        // R392：噪声体绑定本体不重复（noiseVolTex 已由 TasteGlRenderer 常驻单元 2）——
        // 此处只指派 sampler；帧路径零新调用
        GLES30.glUseProgram(progRay)
        GLES30.glUniform1i(uNoiseVolRay, 2)
        GLES30.glUseProgram(0)
        uSceneVolComp = GLES30.glGetUniformLocation(progVolComp, "uScene")
        uVolumeVolComp = GLES30.glGetUniformLocation(progVolComp, "uVolume")
        // 全屏三角形（[-1,-1]/[3,-1]/[-1,3]）
        val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val tb = ByteBuffer.allocateDirect(tri.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        tb.put(tri).flip()
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        postVbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, postVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, tb, GLES30.GL_STATIC_DRAW)
        curW = 0
        curH = 0
    }

    /** 视口尺寸变化幂等重建 FBO 组（onDrawFrame 首调/onSurfaceChanged 后首帧） */
    fun ensureSize(w: Int, h: Int) {
        if (w == curW && h == curH && sceneFbo != 0) return
        destroyFbos()
        curW = w
        curH = h
        // 场景 A 面（色+深度纹理——R383 纹理化）
        val ps = makeSceneFbo(w, h)
        sceneFbo = ps[0]; sceneTex = ps[1]; sceneDepthTex = ps[2]
        // 场景 B 面（色——体积合成 ping-pong 目标）
        val p2 = makeColorFbo(w, h)
        sceneFbo2 = p2[0]; sceneTex2 = p2[1]
        val qw = (w / 4).coerceAtLeast(1)
        val qh = (h / 4).coerceAtLeast(1)
        val pa = makeColorFbo(qw, qh)
        blurFboA = pa[0]; blurTexA = pa[1]
        val pb = makeColorFbo(qw, qh)
        blurFboB = pb[0]; blurTexB = pb[1]
    }

    /** 场景 pass 开始（全部 op 回放渲进场景 FBO——A 面带深度纹理） */
    fun beginScene() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, curW, curH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        frontFbo = sceneFbo
        frontTex = sceneTex
        volumeRenderedThisFrame = false
    }

    /**
     * R383 体积趟（design-nebula §5.2——**挂位=blur 前**：场景回放后、endSceneComposite
     * 前；[vol] 由渲染器触发门逐帧填束，inactive 调用方直接跳过本趟）。两 pass：
     *  1. raymarch（rayFbo 半/quarter 分辨率——步数分档随 [GlVolumeCall.resDiv] 幂等
     *     重建；深度纹理逐样本遮挡裁剪+premultiplied 产出）；
     *  2. 深度感知合成（sceneFbo→sceneFbo2 premultiplied over——穿云前方星减光；
     *     front 换装 B 面）。
     * 收尾：深度附件 invalidate（本趟已消费——RB 时代合成趟首行的 invalidate 点
     * 后移至此；inactive 帧仍走原位=两路径等价）+rayTex invalidate（已消费，Mali
     * 瓦片纪律同 blur 两件）。
     */
    fun renderVolume(vol: GlVolumeCall, w: Int, h: Int) {
        if (sceneFbo == 0 || sceneFbo2 == 0 || w <= 0 || h <= 0) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        val div = vol.resDiv.coerceAtLeast(1)
        val rw = (w / div).coerceAtLeast(1)
        val rh = (h / div).coerceAtLeast(1)
        if (rayFbo == 0 || rayW != rw || rayH != rh) {
            if (rayFbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(rayFbo), 0)
                GLES30.glDeleteTextures(1, intArrayOf(rayTex), 0)
                rayFbo = 0; rayTex = 0
            }
            val pr = makeColorFbo(rw, rh)
            rayFbo = pr[0]; rayTex = pr[1]
            rayW = rw; rayH = rh
        }
        // ---- 趟 1：raymarch（rayFbo——miss 像素 discard，先清零防脏读） ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rayFbo)
        GLES30.glViewport(0, 0, rw, rh)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glClearColor(0f, 0f, 0f, 1f) // 场景底色复原（InkBlack 不透明——下帧 beginScene 清色用）
        GLES30.glUseProgram(progRay)
        GLES30.glUniform3f(uCamPosRay, vol.camX, vol.camY, vol.camZ)
        GLES30.glUniform3f(uCamRightRay, vol.camRight[0], vol.camRight[1], vol.camRight[2])
        GLES30.glUniform3f(uCamUpRay, vol.camUp[0], vol.camUp[1], vol.camUp[2])
        GLES30.glUniform3f(uCamFwdRay, vol.camFwd[0], vol.camFwd[1], vol.camFwd[2])
        GLES30.glUniform2f(uTanHalfRay, vol.tanX, vol.tanY)
        GLES30.glUniformMatrix4fv(uVpRay, 1, false, vol.vp, 0)
        GLES30.glUniform3f(uCenterRay, vol.cpxCx, vol.cpxCy, vol.cpxCz)
        GLES30.glUniform1f(uRadiusRay, vol.cpxRadius)
        GLES30.glUniform3f(uColorHaRay, vol.colorHa[0], vol.colorHa[1], vol.colorHa[2])
        GLES30.glUniform3f(uColorOiiiRay, vol.colorOiii[0], vol.colorOiii[1], vol.colorOiii[2])
        GLES30.glUniform1f(uHaMixRay, vol.haMix01)
        GLES30.glUniform1f(uAlphaRay, vol.alphaCap)
        GLES30.glUniform1f(uSeedRay, vol.seed)
        GLES30.glUniform1f(uTimeRay, vol.timeSec)
        GLES30.glUniform1i(uStepsRay, vol.steps.coerceIn(8, 96))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneDepthTex)
        GLES30.glUniform1i(uSceneDepthRay, 0)
        drawPostTri()
        // ---- 趟 2：深度感知合成（sceneFbo→sceneFbo2 premultiplied over） ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo2)
        GLES30.glViewport(0, 0, curW, curH)
        GLES30.glUseProgram(progVolComp)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glUniform1i(uSceneVolComp, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rayTex)
        GLES30.glUniform1i(uVolumeVolComp, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        drawPostTri()
        // ---- 深度附件 invalidate（本趟已消费——Mali 瓦片省回写，纪律同 RB 时代） ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glInvalidateFramebuffer(
            GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_DEPTH_ATTACHMENT), 0,
        )
        // rayTex 本帧已消费完且下帧全量重写——禁止回写（blur 两件同款）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rayFbo)
        GLES30.glInvalidateFramebuffer(
            GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0,
        )
        // front 换装（B 面——后续 blur/上屏读合成后场景）
        frontFbo = sceneFbo2
        frontTex = sceneTex2
        volumeRenderedThisFrame = true
    }

    /**
     * 场景 pass 收尾+后处理链+合成上屏（默认 FB）。
     * [flareBuf]/[flareCount]=镜头光斑源（GlFrameBuilder.FLARE_STRIDE 布局）。
     */
    fun endSceneComposite(flareBuf: FloatArray, flareCount: Int) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        if (!volumeRenderedThisFrame) {
            // 体积趟未跑（inactive 帧）——深度 invalidate 原位点（RB 时代同位置）；
            // 跑过则 renderVolume 已消费后失效，此处不重复
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glInvalidateFramebuffer(
                GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_DEPTH_ATTACHMENT), 0,
            )
        }
        volumeRenderedThisFrame = false
        // ---- 趟 1：横向模糊+亮部提取合趟（front → blurA，quarter） ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
        GLES30.glViewport(0, 0, curW / 4, curH / 4)
        GLES30.glUseProgram(progBlur)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frontTex)
        GLES30.glUniform1i(uTexBlur, 0)
        GLES30.glUniform2f(uDirBlur, 1f / (curW / 4f), 0f)
        GLES30.glUniform1f(uThreshBlur, bloomThreshold)
        drawPostTri()
        // ---- 趟 2：纵向模糊（blurA → blurB，quarter） ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboB)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexA)
        GLES30.glUniform2f(uDirBlur, 0f, 1f / (curH / 4f))
        GLES30.glUniform1f(uThreshBlur, 0f)
        drawPostTri()
        // ---- 合成上屏（场景+bloom 加性+程序光斑） ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, curW, curH)
        GLES30.glUseProgram(progComposite)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frontTex)
        GLES30.glUniform1i(uSceneComp, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexB)
        GLES30.glUniform1i(uBloomComp, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1f(uBloomGainComp, bloomGain)
        GLES30.glUniform1f(uBodyBloomCutComp, bodyBloomCut)
        GLES30.glUniform1f(uAspectComp, curW.toFloat() / curH.coerceAtLeast(1))
        // 光斑 uniform 数组（≤3 源——空槽强度零）
        flarePosArr.fill(0f)
        flareColArr.fill(0f)
        for (i in 0 until flareCount.coerceAtMost(3)) {
            val b = i * GlFrameBuilder.FLARE_STRIDE
            flarePosArr[i * 4] = flareBuf[b]
            flarePosArr[i * 4 + 1] = flareBuf[b + 1]
            flarePosArr[i * 4 + 2] = flareBuf[b + 5] // 强度
            flarePosArr[i * 4 + 3] = flareBuf[b + 6] // 正对度
            flareColArr[i * 3] = flareBuf[b + 2]
            flareColArr[i * 3 + 1] = flareBuf[b + 3]
            flareColArr[i * 3 + 2] = flareBuf[b + 4]
        }
        GLES30.glUniform4fv(uFlarePosComp, 3, flarePosArr, 0)
        GLES30.glUniform3fv(uFlareColorComp, 3, flareColArr, 0)
        drawPostTri()
        // 模糊两件的像素本帧已消费完且下帧全量重写——同样禁止回写（再省 2×quarter 回写）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
        GLES30.glInvalidateFramebuffer(
            GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboB)
        GLES30.glInvalidateFramebuffer(
            GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0,
        )
        // 状态复原（场景 pass 的混合/深度默认）
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawPostTri() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, postVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * 建场景 FBO（A 面——RGBA8 色+**深度纹理 DEPTH_COMPONENT24/NEAREST**，R383
     * 纹理化：体积趟逐样本遮挡裁剪消费；ES3.0 深度纹理非可滤→NEAREST，compare
     * mode 显式 NONE）——返回 [fbo, colorTex, depthTex]
     */
    private fun makeSceneFbo(w: Int, h: Int): IntArray {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_NONE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, w, h, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glGenTextures(1, ids, 0)
        val color = makeColorTex(w, h, ids)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, color, 0)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, tex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("R383 场景 FBO 不完整（w=$w h=$h 深度纹理化）")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return intArrayOf(fbo, color, tex)
    }

    /** 建纯色 FBO（RGBA8 线性——blur/体积 ray/场景 B 面共用）——返回 [fbo, tex] */
    private fun makeColorFbo(w: Int, h: Int): IntArray {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = makeColorTex(w, h, ids)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("R281 GL 后处理 FBO 不完整（w=$w h=$h）")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return intArrayOf(fbo, tex)
    }

    /** RGBA8 线性纹理（w×h——帧零分配口径句柄件；ids[0]=纹理号占位复用） */
    private fun makeColorTex(w: Int, h: Int, ids: IntArray): Int {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return ids[0]
    }

    private fun destroyFbos() {
        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(sceneTex), 0)
            if (sceneDepthTex != 0) GLES30.glDeleteTextures(1, intArrayOf(sceneDepthTex), 0)
            sceneFbo = 0; sceneTex = 0; sceneDepthTex = 0
        }
        if (sceneFbo2 != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo2), 0)
            GLES30.glDeleteTextures(1, intArrayOf(sceneTex2), 0)
            sceneFbo2 = 0; sceneTex2 = 0
        }
        if (rayFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(rayFbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(rayTex), 0)
            rayFbo = 0; rayTex = 0; rayW = 0; rayH = 0
        }
        if (blurFboA != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(blurFboA), 0)
            GLES30.glDeleteTextures(1, intArrayOf(blurTexA), 0)
            blurFboA = 0; blurTexA = 0
        }
        if (blurFboB != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(blurFboB), 0)
            GLES30.glDeleteTextures(1, intArrayOf(blurTexB), 0)
            blurFboB = 0; blurTexB = 0
        }
        frontFbo = 0
        frontTex = 0
    }

    /** 全量释放（onSurfaceCreated 重建前——EGL 丢失纪律） */
    fun release() {
        destroyFbos()
        if (progBlur != 0) GLES30.glDeleteProgram(progBlur)
        if (progComposite != 0) GLES30.glDeleteProgram(progComposite)
        if (progRay != 0) GLES30.glDeleteProgram(progRay)
        if (progVolComp != 0) GLES30.glDeleteProgram(progVolComp)
        if (postVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(postVbo), 0)
        progBlur = 0; progComposite = 0; postVbo = 0
        progRay = 0; progVolComp = 0
        curW = 0; curH = 0
    }
}
