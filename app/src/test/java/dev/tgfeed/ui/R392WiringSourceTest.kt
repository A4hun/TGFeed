package dev.tgfeed.ui

import dev.tgfeed.ui.gl.GlVolumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R392 星图性能正治·噪声载体革命 wiring 源码锚（R357/R383WiringSourceTest 同款
 * user.dir 源定位模式——GL 渲染管线无 JVM 直测面，以源锚锁结构面；判词引
 * r392_notes §2）：
 *  - **fieldAt 单源锚①**：NEBULA_FS/RAYMARCH_FS 各恰一次 fieldAt 定义+消费次数
 *    锁死（4/6——判词①：构造基因逐字保留，载体=烘焙 3D 纹理单次采样）；
 *  - **载体接线锚②**：uploadTexture3D（GlShaders）+渲染器烘焙上传/常驻单元 2/
 *    sampler 指派+GlPostFx progRay 单元 2 指派（帧路径零新调用）；
 *  - **精度判词锚③**：NEBULA_FS 回归 mediump（R357 highp 病因随载体消亡——
 *    判词③兑现非翻转；uSeed/uTime 显式 highp 保留）+RAYMARCH_FS 保 highp
 *    （射线几何/深度比较需 fp32，与噪声无关）；
 *  - **负锚④**：两 FS 摘除 $NOISE_3D+NEBULA_FS 块零噪声 token（类闸 token 纪律
 *    单件化）+PLANET_FS 存活锚+帧契约/装配侧零碰（判词⑤）；
 *  - **政策锚⑤**：TIER_START=TIER_QUARTER（判词④低开高走）+stepsOf/resDivisorOf
 *    既有口径不动；
 *  - **烘焙器锚⑥**：SIZE=64/WINDOW=16f/单焙纪律（@Volatile+synchronized）。
 */
class R392WiringSourceTest {

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
        // 行尾归一：autocrlf 检出形态（CRLF/LF）不得漂移多行锚（精度判词 startsWith
        // 逐字比对——R423 锚修：worktree 重检出后 CRLF 致锚假红）
        return file.readText().replace("\r\n", "\n")
    }

    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")

    private fun postFx(): String = src("dev/tgfeed/ui/gl/GlPostFx.kt")

    private fun builder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun frameRenderer(): String = src("dev/tgfeed/ui/TasteFlightRenderer.kt")

    private fun baker(): String = src("dev/tgfeed/core/tastemap/NoiseVolumeBaker.kt")

    /** FS 程序整块文本（`const val NAME = """…"""`——R357 同款切法） */
    private fun fsBlockOf(gs: String, name: String): String {
        val m = Regex("const val ${name} = \"\"\"([\\s\\S]*?)\"\"\"").find(gs)
        assertTrue("$name 块必须在场", m != null)
        return m!!.groupValues[1]
    }

    // ==================== ① fieldAt 单源锚（判词①：构造保留，载体=烘焙纹理采样） ====================

    @Test
    fun `fieldAt单源-两FS各恰一定义与消费次数锁死`() {
        val gs = glShaders()
        val neb = fsBlockOf(gs, "NEBULA_FS")
        val ray = fsBlockOf(gs, "RAYMARCH_FS")
        // 定义恰一次（fieldAt=载体唯一采样口——判词①单源纪律）
        assertEquals(
            "NEBULA_FS fieldAt 定义恰一次",
            1,
            Regex("float fieldAt\\(highp vec3 p\\) \\{").findAll(neb).count(),
        )
        assertEquals(
            "RAYMARCH_FS fieldAt 定义恰一次",
            1,
            Regex("float fieldAt\\(highp vec3 p\\) \\{").findAll(ray).count(),
        )
        // 消费次数锁死（fieldAt( 总出现=定义 1+消费 N）：NEBULA=4 次消费
        // （warp 两行+den+ridge——§2 NEBULA_FS 新文逐字），RAYMARCH=6 次消费
        // （shell+warp 两行+den+ridge+色混 w——§2 RAYMARCH_FS 改法五处 fbm3 原位换）
        assertEquals(
            "NEBULA_FS fieldAt 消费=4 次（域扭曲 2+密度 1+脊线 1——构造基因逐字保留）",
            5,
            Regex("fieldAt\\(").findAll(neb).count(),
        )
        assertEquals(
            "RAYMARCH_FS fieldAt 消费=6 次（壳场 1+域扭曲 2+密度 1+脊线 1+色混 1）",
            7,
            Regex("fieldAt\\(").findAll(ray).count(),
        )
    }

    // ==================== ② 载体接线锚（上传+常驻单元 2+sampler 指派） ====================

    @Test
    fun `载体接线-上传与常驻单元2与sampler指派`() {
        // GlShaders：3D 纹理上传口（GL_R8/LINEAR/REPEAT/mipmap——§2 渲染器接线）
        assertTrue("GlShaders.uploadTexture3D 上传口在场", glShaders().contains("fun uploadTexture3D("))
        // TasteGlRenderer：烘焙上传（bakeMemo 单焙消费）+常驻单元 2+GL_TEXTURE_3D 绑定
        val rn = renderer()
        assertTrue(
            "渲染器噪声体上传=GlShaders.uploadTexture3D(NoiseVolumeBaker.bakeMemo()（进程级单焙——一次性税）",
            rn.contains("GlShaders.uploadTexture3D(NoiseVolumeBaker.bakeMemo()"),
        )
        assertTrue("常驻纹理单元 2（GlPostFx 占 0/1——零碰撞）", rn.contains("GLES30.GL_TEXTURE2"))
        assertTrue("GL_TEXTURE_3D 绑定在场", rn.contains("GLES30.GL_TEXTURE_3D"))
        assertTrue(
            "progNebula sampler 指派单元 2（uNoiseVolNeb）",
            rn.contains("glUniform1i(uNoiseVolNeb, 2)"),
        )
        // GlPostFx：progRay sampler 位+单元 2 指派（绑定本体由渲染器常驻承担——
        // renderVolume 帧路径零新调用）
        val px = postFx()
        assertTrue("GlPostFx progRay uNoiseVol sampler 位在场", px.contains("uNoiseVolRay"))
        assertTrue("progRay sampler 指派单元 2", px.contains("glUniform1i(uNoiseVolRay, 2)"))
    }

    // ==================== ③ 精度判词锚（判词③：R357 病因随载体消亡） ====================

    @Test
    fun `精度判词-NEBULA回归mediump与RAYMARCH保highp`() {
        val gs = glShaders()
        val neb = fsBlockOf(gs, "NEBULA_FS")
        // NEBULA_FS 头=mediump 回归（噪声值=纹理 8bit 归一化采样天然够用——
        // R357 精神兑现非翻转）；uSeed/uTime 显式 highp（种子派生角/时漂精度件）
        assertTrue(
            "NEBULA_FS 头 precision mediump float（判词③回归）",
            neb.startsWith("#version 300 es\nprecision mediump float;"),
        )
        assertTrue("uSeed 显式 highp 保留", neb.contains("uniform highp float uSeed;"))
        assertTrue("uTime 显式 highp 保留", neb.contains("uniform highp float uTime;"))
        // RAYMARCH_FS 头=highp 保留（射线几何/深度比较需 fp32——与噪声无关）
        val ray = fsBlockOf(gs, "RAYMARCH_FS")
        assertTrue(
            "RAYMARCH_FS 头 precision highp float 保留（射线几何需 fp32——判词③）",
            ray.startsWith("#version 300 es\nprecision highp float;"),
        )
    }

    // ==================== ④ 负锚（$NOISE_3D 摘除+token 纪律+帧契约零碰） ====================

    @Test
    fun `负锚-两FS摘除NOISE_3D与token纪律与帧契约零碰`() {
        val gs = glShaders()
        // $NOISE_3D 从两 FS 摘除（载体革命——ALU 噪声库退役于两 FS；库本体存活见下）
        val neb = fsBlockOf(gs, "NEBULA_FS")
        val ray = fsBlockOf(gs, "RAYMARCH_FS")
        assertFalse("NEBULA_FS 块不得再引用 \$NOISE_3D（载体=烘焙纹理）", neb.contains("\$NOISE_3D"))
        assertFalse("RAYMARCH_FS 块不得再引用 \$NOISE_3D（载体=烘焙纹理）", ray.contains("\$NOISE_3D"))
        // NEBULA_FS 块零噪声 token（R357 类闸 token 纪律的单件化——含 token 即被
        // 类闸强制 highp，与本块 mediump 设计冲突；中文「噪声/哈希」不触发）
        assertFalse(
            "NEBULA_FS 块零命中 vnoise|fbm|hash\\d（token 纪律——含注释）",
            Regex("vnoise|fbm|hash\\d").containsMatchIn(neb),
        )
        // 存活锚：$NOISE_3D 库与消费面零回破（PLANET_FS 仍在消费——SPHERE 族
        // 载体迁移=P1+ 挂账，本批零碰）
        assertTrue("PLANET_FS 仍消费 \$NOISE_3D（存活锚——SPHERE 族本批零碰）", fsBlockOf(gs, "PLANET_FS").contains("\$NOISE_3D"))
        assertTrue("NOISE_3D 库本体定义存活", gs.contains("private const val NOISE_3D = "))
        // 判词⑤帧契约零碰：GlFrameBuilder NEB_STRIDE=10 原样+装配侧零 NoiseVolumeBaker
        // 引用（噪声体=渲染器全局单件，不进帧字段）；TasteFlightFrame 零 noiseVol
        val b = builder()
        assertTrue("GlFrameBuilder NEB_STRIDE=10 原样（帧契约零碰——判词⑤）", b.contains("NEB_STRIDE = 10"))
        assertFalse("GlFrameBuilder 零 NoiseVolumeBaker 引用（装配侧零碰）", b.contains("NoiseVolumeBaker"))
        assertFalse("TasteFlightFrame 零 noiseVol（帧契约零碰）", frameRenderer().contains("noiseVol"))
    }

    // ==================== ⑤ 政策锚（判词④低开高走+既有口径不动） ====================

    @Test
    fun `政策-TIER_START低开高走与档位函数口径不动`() {
        // 判词④：起始档=TIER_QUARTER（40 步×1/4 分辨率起步——先证明跑得动再给质量，
        // 健康窗升档链自证爬回满配）
        assertEquals(
            "TIER_START=TIER_QUARTER（判词④低开高走）",
            GlVolumePolicy.TIER_QUARTER,
            GlVolumePolicy.TIER_START,
        )
        // stepsOf/resDivisorOf 既有口径不动（档义零改——只起始档语义反转）
        assertEquals("质量档恒 64 步", 64, GlVolumePolicy.stepsOf(GlVolumePolicy.TIER_HALF_HIGH))
        assertEquals("半分辨省税档恒 40 步", 40, GlVolumePolicy.stepsOf(GlVolumePolicy.TIER_HALF_LOW))
        assertEquals("quarter 档恒 40 步", 40, GlVolumePolicy.stepsOf(GlVolumePolicy.TIER_QUARTER))
        assertEquals("quarter 档分辨率除数=4", 4, GlVolumePolicy.resDivisorOf(GlVolumePolicy.TIER_QUARTER))
        assertEquals("半分辨档分辨率除数=2", 2, GlVolumePolicy.resDivisorOf(GlVolumePolicy.TIER_HALF_HIGH))
        assertEquals("半分辨省税档分辨率除数=2", 2, GlVolumePolicy.resDivisorOf(GlVolumePolicy.TIER_HALF_LOW))
    }

    // ==================== ⑥ 烘焙器锚（64立方窗 16 单焙纪律） ====================

    @Test
    fun `烘焙器-常量对表与单焙纪律`() {
        val bk = baker()
        // 窗/尺寸对表（shader 侧 0.0625=1/16 与 WINDOW 单源对表——判词①载体规格）
        assertTrue("SIZE=64（64立方 R8=256KB）", bk.contains("const val SIZE = 64"))
        assertTrue("WINDOW=16f（q 空间周期窗——shader 0.0625 对表）", bk.contains("const val WINDOW = 16f"))
        // 单焙纪律（进程级 memo——@Volatile 双检+synchronized 锁；页内重进零成本）
        assertTrue("@Volatile 持态（跨线程换装纪律）", bk.contains("@Volatile"))
        assertTrue("synchronized 单焙锁在场", bk.contains("synchronized"))
    }
}
