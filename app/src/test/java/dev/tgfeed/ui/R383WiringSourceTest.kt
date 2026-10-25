package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.ui.gl.GlVolumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R383 深空星云复合体 P3 raymarch 贴脸档 wiring 源码锚+纯件锚（R373/R367 同款
 * user.dir 源定位模式；design-nebula **§5.2 主档**+§4.3 等效帽+§六 降级链）：
 *  - ①crossfade 能量守恒常量单源锚：互补函数 volumeFade01=1−nearFade01（core
 *    单源）+数值恒等式真测（nearFade+volumeFade==1 采样）+两侧消费单源（builder
 *    半边=nearFade01 P2 零改锚/渲染器半边=volumeFade01+emissionAlpha01 同帽）；
 *  - ②步数分档常量锚：64/40（§5.2「40~64 步」）+tier→(步数,分辨率除数) 纯函数
 *    真测；
 *  - ③highp 全程源锚：RAYMARCH_FS 头档 highp+噪声消费（R357 类闸联动——新噪声
 *    FS 忘抬档即红）+合成 FS 守 mediump（性能账）+NEBULA_FS 同族 fbm3 常量对表
 *    （GLSL 侧单源：两 FS 块家族数值基因逐一互证，任一侧漂移即红）；
 *  - ④降级链序锚：3→2→1→0 退出回 billboard 纯函数行为测+退出粘滞+渲染器持态
 *    单源消费（demote/promote 无第二份判定）；
 *  - ⑤深度合成挂位锚：renderVolume 先于 endSceneComposite（挂位=blur 前）+
 *    场景深度附件纹理化（DEPTH_COMPONENT24）+ping-pong front 换装+深度 invalidate
 *    后移（体积趟消费后）。
 */
class R383WiringSourceTest {

    private val mainSourceRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = File(dir, "src/main/java")
                if (candidate.isDirectory) return candidate
                val nested = File(dir, "app/src/main/java")
                if (nested.isDirectory) return nested
                dir = dir.parentFile ?: return@repeat
            }
            error("找不到主源码根（user.dir=${System.getProperty("user.dir")}）")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun core(): String = src("dev/tgfeed/core/tastemap/DeepSpaceNebula.kt")

    private fun builder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")

    private fun postFx(): String = src("dev/tgfeed/ui/gl/GlPostFx.kt")

    private fun shaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    private fun fsBlock(gs: String, name: String): String {
        val m = Regex("const val $name = \"\"\"([\\s\\S]*?)\"\"\"").find(gs)
        assertTrue("$name 块必须在场", m != null)
        return m!!.groupValues[1]
    }

    /**
     * ① crossfade 能量守恒常量单源：互补函数恒等式（源锚+数值真测双证）+
     * 窗常量 0.10R/0.80R 单一出处+两侧消费单源（builder 半边 P2 零改/渲染器
     * 半边同帽 emissionAlpha01——总外轮廓恒定的结构凭据）。
     */
    @Test
    fun crossfade能量守恒_互补恒等式与两侧消费单源() {
        val c = core()
        // 互补函数源锚：同一 nearFade01 单源导出（窗=同一 smoothstep——C1 无迟滞）
        assertTrue(
            "体积渐入=1−nearFade01 互补恒等式（core 单源）",
            c.contains("fun volumeFade01(dist: Float, radius: Float): Float = 1f - nearFade01(dist, radius)"),
        )
        // 窗常量单一出处（§5.2 触发门 0.8R——P2 常量只消费零新增；捕获组纯数字=R373 惯例）
        assertEquals("渐出窗内沿 0.10R", "0.10", Regex("const val NEAR_FADE_INNER = ([\\d.]+)f").find(c)?.groupValues?.get(1))
        assertEquals("渐出窗外沿 0.80R（§5.2 同值）", "0.80", Regex("const val NEAR_FADE_OUTER = ([\\d.]+)f").find(c)?.groupValues?.get(1))
        // 数值恒等式真测：全窗采样两权恒和 1（能量守恒——总外轮廓恒定；
        // 容差=fp32 1−a 舍入位（Sterbenz 域外 a<0.5 时 1−a 非精确表示））
        val r = 1500f
        for (d in floatArrayOf(0.02f * r, 0.10f * r, 0.20f * r, 0.45f * r, 0.61f * r, 0.79f * r, 0.80f * r, 1.3f * r, 5f * r)) {
            assertEquals(
                "nearFade+volumeFade 恒和 1（dist=${d}）——α 能量守恒互补 crossfade",
                1f,
                DeepSpaceNebula.nearFade01(d, r) + DeepSpaceNebula.volumeFade01(d, r),
                1e-6f,
            )
        }
        // billboard 半边=P2 builder 原样（nearFade01 消费零改锚）
        assertTrue("builder 半边 nearFade01 消费 P2 原样", builder().contains("DeepSpaceNebula.nearFade01"))
        // 体积半边=渲染器消费（volumeFade01+同帽 emissionAlpha01——同帽单源）
        val rn = renderer()
        assertTrue("渲染器半边 volumeFade01 消费", rn.contains("DeepSpaceNebula.volumeFade01"))
        // R385c 翻新：R385 gas 档维护性扩展（r385_notes §0 架构判词一——两半边
        // 同乘同帽=维护 P3「同帽单源/跳变 ≤8%」不变量的必要扩展非碰结论）：
        // emissionAlpha01 仍同帽单源，仅多两半边共享 gasMult 乘子与同帽钳
        assertTrue(
            "体积 alpha 帽=emissionAlpha01 同帽单源（R385 gas 档扩展：×gasMultR385 两半边同乘+CPX_ALPHA_MAX 同帽钳——P3 不变量判词保义）",
            rn.contains("DeepSpaceNebula.emissionAlpha01(c.ion01) * gasMultR385)") &&
                rn.contains(".coerceAtMost(DeepSpaceNebula.CPX_ALPHA_MAX) * bestFade * eased"),
        )
    }

    /**
     * ② 步数分档常量（§5.2「40~64 步光线步进（步数分档）」）：64/40 落
     * GlVolumePolicy 单源+tier→步数/分辨率除数纯函数真测（quarter 档恒 40 步
     * 省税——降档第二刀砍分辨率不叠步数税）。
     */
    @Test
    fun 步数分档_常量单源与tier映射() {
        assertEquals("步数质量档=64", 64, GlVolumePolicy.STEPS_HIGH)
        assertEquals("步数省税档=40", 40, GlVolumePolicy.STEPS_LOW)
        assertEquals("起始档=半分辨率 64 步（满质量起步）", GlVolumePolicy.TIER_HALF_HIGH, GlVolumePolicy.TIER_START)
        assertEquals(64, GlVolumePolicy.stepsOf(GlVolumePolicy.TIER_HALF_HIGH))
        assertEquals(40, GlVolumePolicy.stepsOf(GlVolumePolicy.TIER_HALF_LOW))
        assertEquals("quarter 档恒 40 步（省税——降档砍分辨率不叠步数）", 40, GlVolumePolicy.stepsOf(GlVolumePolicy.TIER_QUARTER))
        assertEquals(2, GlVolumePolicy.resDivisorOf(GlVolumePolicy.TIER_HALF_HIGH))
        assertEquals(2, GlVolumePolicy.resDivisorOf(GlVolumePolicy.TIER_HALF_LOW))
        assertEquals("quarter 档分辨率除数=4", 4, GlVolumePolicy.resDivisorOf(GlVolumePolicy.TIER_QUARTER))
    }

    /**
     * ③ highp 全程源锚+同族 fbm3 常量对表：RAYMARCH_FS=噪声消费 FS 全程 highp
     * （R357 类闸联动——fp16 塌噪声病根）+合成 FS 守 mediump（无噪声件性能账）
     * +NEBULA_FS 家族数值基因对表（GLSL 侧单源共享常量——两 FS 块任一侧漂移即红；
     * NEBULA_FS 文本零碰红线所迫，单源锁在测试侧的诚实口径）。
     */
    @Test
    fun highp全程_源锚与同族常量对表() {
        val gs = shaders()
        val ray = fsBlock(gs, "RAYMARCH_FS")
        assertTrue(
            "RAYMARCH_FS 必须 precision highp float（R357 噪声 FS 类闸成员）",
            Regex("precision highp float;").containsMatchIn(ray.substring(0, 200)),
        )
        assertTrue("RAYMARCH_FS 噪声消费在场（fbm3 同族密度场）", ray.contains("fbm3"))
        assertTrue("RAYMARCH_FS 共享噪声库单源（NOISE_3D 插值）", gs.contains("\$NOISE_3D") && ray.contains("fbm3(fq"))
        val comp = fsBlock(gs, "VOLUME_COMPOSITE_FS")
        assertTrue(
            "VOLUME_COMPOSITE_FS 守 mediump（无噪声件性能账——R357 守档锚）",
            Regex("precision mediump float;").containsMatchIn(comp.substring(0, 200)),
        )
        // 家族数值基因对表：NEBULA_FS 与 RAYMARCH_FS 逐一互证（GLSL 侧单源共享）
        val neb = fsBlock(gs, "NEBULA_FS")
        // 密度场空间频率+种子 z（R383d Mali S0008 修笔——锚与源同刀翻正：NEBULA_FS 2D
        // 语境 vec3(st*2.1,uSeed)=vec3(vec2,float) 3 对 3 合法原式零碰；RAYMARCH_FS q
        // 升 vec3 后 vec3(vec3,float) 4 分量入 3 槽=GLSL ES 3.00 §5.4.2 违规（Mali 严格
        // 编译器 S0008 硬拒）——修式=种子入 z 槽加法式（家族先例 L1184 同式），锚锁
        // 修复态、回退即红）
        assertTrue("家族基因 * 2.1, uSeed（2D 语境合法式）必须在 NEBULA_FS 在场", neb.contains("* 2.1, uSeed"))
        assertTrue(
            "RAYMARCH_FS 密度场=q*2.1+vec3(0,0,uSeed)（3D 语境升维同式——Mali S0008 修复态，漂移即红）",
            ray.contains("q * 2.1 + vec3(0.0, 0.0, uSeed)"),
        )
        for (gene in listOf(
            "smoothstep(0.12, 1.0",      // 径向 falloff
            "ridge * 0.85",              // 丝缕脊线 max 权
            "smoothstep(0.40, 0.82",     // 密度剖面门
            "smoothstep(0.62, 0.88",     // 暗尘埃带门
            "0.55 * lane",               // 尘埃带减除强度
            "0.70 + 1.05",               // 明度纵深（色温分层同族）
        )) {
            assertTrue("家族基因 $gene 必须在 NEBULA_FS 在场", neb.contains(gene))
            assertTrue("家族基因 $gene 必须在 RAYMARCH_FS 在场（同族 fbm3 单源对表——漂移即红）", ray.contains(gene))
        }
        // R357 类闸联动复诵（新噪声 FS 忘抬档即红的结构面）
        val m = Regex("const val RAYMARCH_FS = \"\"\"#version 300 es\\s+precision (\\w+) float;").find(gs)
        assertTrue("RAYMARCH_FS 头档可解析（R357 类闸同式）", m != null)
        assertEquals("highp", m!!.groupValues[1])
    }

    /**
     * ④ 降级链序锚（§5.2「步数/分辨率降档→仍不过=退出 raymarch 档回远景
     * billboard」——观感降级语义与门连续）：纯函数行为测（3→2→1→0 地板+升档
     * 天花板+退出档 3 窗粘滞）+渲染器持态单源消费（demote/promote 无第二份判定）。
     */
    @Test
    fun 降级链序_退出回billboard与退出粘滞() {
        // 降级链序：64步半→40步半→40步quarter→退出（0 地板）
        var t = GlVolumePolicy.TIER_START
        val chain = ArrayList<Int>(4)
        repeat(3) { t = GlVolumePolicy.demote(t); chain += t }
        assertEquals("三级降档链序=2,1,0（退出=降级链终点）", listOf(2, 1, 0), chain)
        assertEquals("退出档=地板（再降不动）", 0, GlVolumePolicy.demote(0))
        // 升档恢复：0→1→2→3（天花板）
        var u = GlVolumePolicy.TIER_EXIT
        val up = ArrayList<Int>(3)
        repeat(3) { u = GlVolumePolicy.promote(u); up += u }
        assertEquals("升档链=1,2,3（满质量天花板）", listOf(1, 2, 3), up)
        assertEquals("起始档=天花板（再升不动）", 3, GlVolumePolicy.promote(3))
        // 退出粘滞：退出档升回须 3 窗（防退出↔重入拍动），其余 2 窗=R376 滞回同构
        assertEquals(3, GlVolumePolicy.healthyWinsToPromote(0))
        assertEquals(2, GlVolumePolicy.healthyWinsToPromote(1))
        assertEquals(2, GlVolumePolicy.healthyWinsToPromote(2))
        assertEquals(2, GlVolumePolicy.healthyWinsToPromote(3))
        assertTrue("退出档人读名含 billboard（回远景 billboard 语义锚）", GlVolumePolicy.tierLabel(0).contains("billboard"))
        // 渲染器持态单源消费（状态机=GlVolumePolicy 纯函数——无第二份判定）
        val rn = renderer()
        assertTrue("渲染器起始档=TIER_START 单源", rn.contains("private var volumeTier = GlVolumePolicy.TIER_START"))
        assertTrue("降档走 demote 单源", rn.contains("GlVolumePolicy.demote(volumeTier)"))
        assertTrue("升档走 promote 单源", rn.contains("GlVolumePolicy.promote(volumeTier)"))
        assertTrue("退出粘滞走 healthyWinsToPromote 单源", rn.contains("GlVolumePolicy.healthyWinsToPromote(volumeTier)"))
    }

    /**
     * ⑤ 深度合成挂位锚（§5.2「sceneFbo 深度附件纹理化——ES3.0；合成趟挂位=blur
     * 前」）：renderVolume 调用先于 endSceneComposite（drawFrame 内序）+场景深度
     * DEPTH_COMPONENT24 纹理+NEAREST（ES3.0 深度纹理非可滤）+ping-pong front 换装
     * +深度 invalidate 后移（体积趟消费后；inactive 帧仍原位）。
     */
    @Test
    fun 深度合成挂位_blur前与深度纹理化() {
        val rn = renderer()
        val volCall = rn.indexOf("postFx.renderVolume(volCall, viewW, viewH)")
        val compCall = rn.indexOf("postFx.endSceneComposite(")
        assertTrue("体积趟调用在场", volCall > 0)
        assertTrue("后处理链调用在场", compCall > 0)
        assertTrue("挂位=blur 前（renderVolume 先于 endSceneComposite——§5.2 字面）", volCall < compCall)
        val px = postFx()
        // 深度附件纹理化（ES3.0）
        assertTrue(
            "场景深度=DEPTH_COMPONENT24 纹理（RB 纹理化——体积趟 NEAREST 采样消费）",
            px.contains("GL_DEPTH_COMPONENT24") && px.contains("GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT"),
        )
        assertTrue("深度纹理 NEAREST 采样（ES3.0 深度纹理非可滤）", px.contains("GLES30.GL_NEAREST"))
        // 体积趟内：深度消费（uSceneDepth 绑定）+合成 front 换装
        val renderVol = px.substring(px.indexOf("fun renderVolume("), px.indexOf("fun endSceneComposite("))
        assertTrue("raymarch 趟绑定场景深度纹理（逐样本遮挡裁剪）", renderVol.contains("sceneDepthTex"))
        assertTrue("深度感知合成（sceneTex→sceneFbo2 premultiplied over）", renderVol.contains("GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)") && renderVol.contains("sceneFbo2"))
        assertTrue("深度 invalidate 后移（体积趟消费后——Mali 瓦片纪律保留）", renderVol.contains("glInvalidateFramebuffer"))
        assertTrue("front 换装（ping-pong——blur/上屏读合成后场景）", renderVol.contains("frontTex = sceneTex2"))
        // blur/上屏读 front（挂位=blur 前的结构凭据：blur 趟1 消费 frontTex）
        val endComp = px.substring(px.indexOf("fun endSceneComposite("))
        assertTrue("blur 趟1 读 frontTex（体积合成在前）", endComp.contains("GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frontTex)"))
        assertTrue("inactive 帧深度 invalidate 原位（两路径等价）", endComp.contains("if (!volumeRenderedThisFrame)"))
    }
}
