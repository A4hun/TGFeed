package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R355 LOD 阈值 DPI 化 + 真 3D 即刻生效 wiring 源码锚（R349/R350WiringSourceTest
 * 同款 user.dir 源定位模式——GL 渲染管线无 JVM 直测面，以源锚锁结构面；数值半体
 * 由 TasteLodPolicyTest/GlFrameBuilderTest/TasteStarGlowTest 承重）：
 *  - **单口锚①密度换算唯一现场**：GlFrameBuilder 全件**零散乘 density**——唯
 *    一现场=build() 帧首三连（pointCeilPx/effectiveSolidFloor/effectiveLowMesh
 *    Max 各吃 [lodDensity] 一次）；别处各算一遍=「同族换算」红旗；
 *  - **接线锚②密度注入链**：GlSceneView factory/update 读 Android
 *    displayMetrics.density→渲染器 [@Volatile] 持态→drawFrame 逐帧推入
 *    builder.lodDensity（主线程写/GL 线程读的换装纪律）；
 *  - **绝迹锚③ambient 平涂路径**：SPHERE_SHADE_BODY 无 ambient 参无
 *    max(val,ambient)（**平盘期彻底删除**——真 3D 球面从第一像素在场；球面
 *    明暗签名 ≥1.8 结构成立=shader 层无平盘例外分支）；
 *  - **禁用锚④渲染器内裸缺省参**：GlFrameBuilder 主路必须显式传帧字段（单
 *    参 blend01(r)/pointFadeK(r) 调用=mdpi 缺省口径漏进密度档=红旗——用户
 *    「小米上根本不是 3D 效果」病根的结构性防复发）；
 *  - **绝迹锚⑤旧 px 裸常量族**：POINT_CEIL_PX/SOLID_FLOOR_PX/LOWMESH_MAX_PX
 *    const 声明绝迹（dp 基准三常数在场=度量基准换轨完成）；
 *  - **探针锚⑥statLodLine 密度行**：密度+px 阈值入探针行（V55 两密度档 3D
 *    出现一致性断言的机读原料）。
 */
class R355WiringSourceTest {

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

    private fun glFrameBuilder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")
    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")
    private fun sceneView(): String = src("dev/tgfeed/ui/gl/GlSceneView.kt")
    private fun lodPolicy(): String = src("dev/tgfeed/core/tastemap/TasteLodPolicy.kt")

    // ==================== ① 单口锚：密度换算唯一现场 ====================

    @Test
    fun `单口-密度换算唯一现场（帧首块 零散乘绝迹）`() {
        val fb = glFrameBuilder()
        // 唯一现场：build() 帧首块（一条语句各吃 lodDensity 一次——别处零乘法；
        // R386 增补第四条：复合体远档阈 cpxFarMinPx 直乘——见下放行锚）
        assertTrue(
            "build 帧首单口三连必须在场（pointCeil/effectiveSolidFloor/effectiveLowMeshMax ×lodDensity）",
            Regex("lodPointCeilPx = TasteLodPolicy\\.pointCeilPx\\(lodDensity\\)").containsMatchIn(fb) &&
                Regex("lodSolidFloorPx = TasteLodPolicy\\.effectiveSolidFloor\\(lodDensity, lodSolidBias\\)").containsMatchIn(fb) &&
                Regex("lodLowMeshMaxPx = TasteLodPolicy\\.effectiveLowMeshMax\\(lodDensity, lodMeshBias\\)").containsMatchIn(fb),
        )
        // 「别处各算一遍」红旗：装配器内散乘 density 绝迹（displayMetrics/×density
        // 读侧只在场景壳——纯 Kotlin 件零 Android 依赖红线同守）；R386 诚实放行：
        // 直乘=帧首 dp 阈族（第四条 cpxFarMinPx 复合体远档阈）；R388 放行增补：
        // 刀1 隐没带双阈第五/六条（cpxFarHideMinPx/MaxPx）+刀2 REF 第七条
        // （cpxFilRefPx）——同一帧首块 dp×density 单口换算，别处仍零乘法
        val directMults = Regex("\\*\\s*lodDensity").findAll(fb).toList()
        assertEquals(
            "lodDensity 直乘仅 build 帧首 dp 阈族（R386 一条+R388 刀1 两条+刀2 一条——别处零乘法）",
            4,
            directMults.size,
        )
        assertTrue(
            "R386 放行位=帧首第四条 cpxFarMinPx 直乘（dp 阈单口换算）",
            Regex("cpxFarMinPx = CPX_FAR_MIN_DP \\* lodDensity").containsMatchIn(fb),
        )
        assertTrue(
            "GlFrameBuilder 零散乘 density 应绝迹（displayMetrics 不入装配器）",
            !fb.contains("displayMetrics"),
        )
        // 密度入参缺省=mdpi 恒等档（JVM 基线——行为半体由 GlFrameBuilderTest 密度矩阵锚）
        assertTrue(Regex("var lodDensity = 1f").containsMatchIn(fb))
    }

    // ==================== ② 接线锚：密度注入链（displayMetrics→渲染器→builder） ====================

    @Test
    fun `接线-密度注入链（displayMetrics 读侧到 builder 推入）`() {
        val sv = sceneView()
        // 场景壳=密度唯一读侧（Android Resources.displayMetrics.density；factory
        // 一次+update 随重组刷新=折叠/外接屏换显示跟新）
        assertEquals(
            "displayMetrics.density 读侧应在 GlSceneView factory/update 双在场（唯一 Android 读口）",
            2,
            Regex("resources\\.displayMetrics\\.density").findAll(sv).count(),
        )
        val r = renderer()
        // 渲染器持态（@Volatile 主线程写/GL 线程读）+drawFrame 逐帧推入
        assertTrue(
            "渲染器密度持态必须 @Volatile（跨线程换装纪律）",
            Regex("@Volatile\\s*\n\\s*var lodDensity = 1f").containsMatchIn(r) ||
                Regex("@Volatile[\\s\\S]{0,40}var lodDensity = 1f").containsMatchIn(r),
        )
        assertTrue(
            "drawFrame 必须逐帧推入 builder.lodDensity（帧字段单口消费）",
            Regex("builder\\.lodDensity = lodDensity").containsMatchIn(r),
        )
    }

    // ==================== ③ 绝迹锚：ambient 平涂路径（真 3D 即刻生效） ====================

    @Test
    fun `绝迹-ambient平涂路径（真3D球面从第一像素在场）`() {
        val gs = glShaders()
        // SPHERE_SHADE_BODY：ambient 参+max(val,ambient) 平盘行整族绝迹
        assertTrue(
            "shadeSphere 必须无 ambient 参（平盘期彻底删除——用户终裁）",
            !Regex("float spotAmp, float flameAmp, float hot, float limbK, float ambient").containsMatchIn(gs),
        )
        assertTrue(
            "val = max(val, ambient) 平盘行必须绝迹（球面明暗全档强制）",
            !gs.contains("max(val, ambient)"),
        )
        // SPHERE_FS 尾参随退役（满档恒等字面 0.0 不再传）
        assertTrue(
            "SPHERE_FS 调用尾参=limbK（无 0.0 平盘字面）",
            Regex("uSpotAmp, uFlameAmp, uHot, uLimbK\\);").containsMatchIn(gs),
        )
        // SPHERE_I_VS：槽 22（旧 ambient）停打包=恒 0（槽位契约：缓冲仍 24 槽无条件写）
        assertTrue(
            "SPHERE_I_VS vParams3 第二分量必须恒 0.0（旧 ambient 槽停打包）",
            Regex("vParams3 = vec4\\(iParams2\\.x, 0\\.0, 0\\.0, 0\\.0\\);").containsMatchIn(gs),
        )
        // SPHERE_I_FS 只吃 vParams3.x（临边系数）
        assertTrue(
            "SPHERE_I_FS 尾参=vParams3.x（单参）",
            Regex("vParams2\\.x, vParams2\\.y, vParams2\\.z, vParams2\\.w,\\s*vParams3\\.x\\);").containsMatchIn(gs),
        )
        // 球面明暗签名结构在场：临边昏暗（limbK 色温从动+二次项——中心 val=1/
        // 缘带 val=[0_04,0_42] 域=签名 ≥1.8 的结构半体）
        assertTrue(
            "临边昏暗律必须在场（球面明暗签名结构）",
            Regex("float val = max\\(1\\.0 - limbK \\* om - 0\\.18 \\* om \\* om, 0\\.04\\);").containsMatchIn(gs),
        )
    }

    // ==================== ④ 禁用锚：渲染器内裸缺省参 ====================

    @Test
    fun `禁用-渲染器内裸缺省参（帧字段显式传参强制）`() {
        val fb = glFrameBuilder()
        // 单参调用=mdpi 缺省口径漏进密度档（用户「小米上根本不是 3D 效果」病根的
        // 结构性防复发——主路必须显式传帧字段）
        for (name in listOf("blend01", "pointFadeK", "formTierOf")) {
            assertTrue(
                "GlFrameBuilder 内 TasteLodPolicy.$name 单参调用应绝迹（必须传帧字段）",
                !Regex("TasteLodPolicy\\.$name\\(\\w+\\)").containsMatchIn(fb),
            )
        }
        assertTrue(
            "GlFrameBuilder 内 TasteLodPolicy.meshTierOf 单参调用应绝迹（必须传帧字段）",
            !Regex("TasteLodPolicy\\.meshTierOf\\(\\w+\\)").containsMatchIn(fb),
        )
        for (name in listOf("coreSwellCoef", "cepheidRadiusCoef")) {
            assertTrue(
                "GlFrameBuilder 内 TasteStarGlow.$name 单参调用应绝迹（必须传帧字段）",
                !Regex("TasteStarGlow\\.$name\\(\\w+\\)").containsMatchIn(fb),
            )
        }
        assertTrue(
            "GlFrameBuilder 内 TasteTerminalFx.remnantFiberPoints 单参调用应绝迹（必须传帧字段）",
            !Regex("TasteTerminalFx\\.remnantFiberPoints\\(\\w+\\)").containsMatchIn(fb),
        )
    }

    // ==================== ⑤ 绝迹锚：旧 px 裸常量族（度量基准换轨完成） ====================

    @Test
    fun `绝迹-旧px裸常量族（dp三常数在场）`() {
        val lp = lodPolicy()
        // dp 基准三常数在场（用户终裁口径：1.2/1.6/4dp）
        assertTrue(
            "dp 三常数必须在场（POINT_CEIL_DP=1.2f/SOLID_FLOOR_DP=1.6f/LOWMESH_MAX_DP=4.0f）",
            Regex("const val POINT_CEIL_DP = 1\\.2f").containsMatchIn(lp) &&
                Regex("const val SOLID_FLOOR_DP = 1\\.6f").containsMatchIn(lp) &&
                Regex("const val LOWMESH_MAX_DP = 4\\.0f").containsMatchIn(lp),
        )
        // 旧 px 裸常量族绝迹（形态线度量基准换轨——签名级史实注释不自伤=R348 先例）
        for (name in listOf("POINT_CEIL_PX", "SOLID_FLOOR_PX", "LOWMESH_MAX_PX")) {
            assertTrue(
                "TasteLodPolicy 旧 px 常量 $name 声明应绝迹（dp 基准换轨）",
                !Regex("const val $name").containsMatchIn(lp),
            )
        }
        // 密度钳单口（[0.75,4.0] 防呆——displayMetrics 真值域外收敛 mdpi 侧）
        assertTrue(
            "密度钳必须在换算单口（coerceIn(0.75f, 4f)）",
            Regex("density\\.coerceIn\\(0\\.75f, 4f\\)").containsMatchIn(lp),
        )
    }

    // ==================== ⑥ 探针锚：statLodLine 密度行 ====================

    @Test
    fun `探针-statLodLine密度与px阈值行（V55一致性断言原料）`() {
        val fb = glFrameBuilder()
        // 探针行带密度+三 px 阈值（两密度档 3D 出现一致性断言的机读原料——
        // 同视觉缩放级别下 阈px 密度换算与计数族互证）
        assertTrue(
            "statLodLine 必须带 密度=与 阈px= 三阈值字段（V55 对比原料）",
            Regex("密度=\\$\\{lodDensity\\} 阈px=\\$\\{lodPointCeilPx\\}/\\$\\{lodSolidFloorPx\\}/\\$\\{lodLowMeshMaxPx\\}").containsMatchIn(fb),
        )
    }
}
