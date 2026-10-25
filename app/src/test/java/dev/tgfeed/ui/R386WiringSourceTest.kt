package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R386 云雾形态小米修正批 wiring 源码锚（R373/R355WiringSourceTest 同款 user.dir
 * 源定位——GL 渲染管线无 JVM 直测面，以源锚锁结构面）：
 *  - 根因锚：buildComplexesR373 远档裁剪阈旧 CPX_FAR_MIN_PX=150f 裸 px——400dpi
 *    （2.5×）下表观 60dp 即出多层档（mdpi 设计基线=150dp 同 dp 云被抬进多层）
 *    =密度岔路病（R355 同族史：px 未换 dp）；远距多层=±0.5R 层深+≤0.45R blob
 *    错位在远距投影近同心叠，各层 fbm 丝缕互填丝间留空+层心 falloff 叠亮=
 *    无结构弥散光雾球（用户 08:2x 小米实锤：均匀大团/无丝缕/无留空=R252 家族
 *    三代病形态）；
 *  - 修法锚（R355 范式翻正）：阈 dp 化——CPX_FAR_MIN_DP=150dp 单源×build 帧首
 *    [lodDensity] 单口直乘入帧字段 cpxFarMinPx（帧首四连第四条=R355 锚的诚实
 *    放行位，R355WiringSourceTest 已扩锚）；同 dp 同路由=跨密度形态一致；mdpi
 *    恒等档 150px 数值原样=设计基线路由零变化；
 *  - 丝缕基因保留锚：远档裁剪**只裁层数**（blobCount=1，配比包随裁后层数归一
 *    ——单层=FRONT 配比语义）——种子相拉/ridge 脊线/暗尘带零接触（R392 判词①
 *    翻新：NEBULA_FS 噪声载体=烘焙 3D 纹理 fieldAt 采样，uniform 六件=五浮点
 *    +uNoiseVol sampler 载体件——构造基因字面全保）。
 */
class R386WiringSourceTest {

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

    private fun builder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    /** buildComplexesR373 函数体（到 buildNebulas 前——R373WiringSourceTest 同款切法） */
    private fun cpxBodyOf(b: String): String {
        val start = b.indexOf("private fun buildComplexesR373")
        assertTrue("buildComplexesR373 必须在场", start >= 0)
        return b.substring(start, b.indexOf("private fun buildNebulas"))
    }

    /** NEBULA_FS 程序文本（GlShaders——R386 丝缕基因零接触的旁证锚原料） */
    private fun nebulaFsOf(gs: String): String {
        val start = gs.indexOf("const val NEBULA_FS =")
        assertTrue("NEBULA_FS 必须在场", start >= 0)
        return gs.substring(start, gs.indexOf("\"\"\"", start + 30))
    }

    /**
     * ① 阈值单源锚：CPX_FAR_MIN_DP=150f（dp 基准=mdpi 基线 150px 数值原样）
     * 恰一次声明；旧裸 px 名 CPX_FAR_MIN_PX 全文件退役（密度岔路病根拔除）；
     * buildComplexesR373 消费帧字段 cpxFarMinPx（非 const 直引——密度档活值）。
     */
    @Test
    fun 阈值单源_dp基准150与裸px名退役() {
        val b = builder()
        val decl = Regex("const val CPX_FAR_MIN_DP = 150f").findAll(b).toList()
        assertEquals("CPX_FAR_MIN_DP=150f 单源恰一次（dp 基准——mdpi 恒等档数值原样）", 1, decl.size)
        assertTrue("旧裸 px 阈名 CPX_FAR_MIN_PX 必须绝迹（R386 批密度岔路病根拔除）", !b.contains("CPX_FAR_MIN_PX"))
        val cpx = cpxBodyOf(b)
        assertTrue(
            "远档裁剪消费帧字段 cpxFarMinPx（密度档活值——非 mdpi 死值）",
            Regex("TasteNebula\\.fogPx\\(c\\.radius, proj\\[3\\]\\) < cpxFarMinPx").containsMatchIn(cpx),
        )
    }

    /**
     * ② dp 换算在场锚：build 帧首单口直乘 `cpxFarMinPx = CPX_FAR_MIN_DP *
     * lodDensity`（R355 三连同块第四条——帧字段初值=mdpi 恒等档；R388 翻新：
     * 全文件 lodDensity 直乘=帧首 dp 阈族四条——R386 一条+刀1 隐没带两条+刀2
     * REF 一条）。
     */
    @Test
    fun dp换算_帧首单口直乘与mdpi恒等档() {
        val b = builder()
        assertTrue(
            "build 帧首单口直乘在场（dp×density 入帧字段——R355 同块第四条）",
            Regex("cpxFarMinPx = CPX_FAR_MIN_DP \\* lodDensity").containsMatchIn(b),
        )
        assertTrue(
            "帧字段初值=mdpi 恒等档（build 必重算——JVM 缺省档行为与基线一致）",
            Regex("private var cpxFarMinPx = CPX_FAR_MIN_DP").containsMatchIn(b),
        )
        assertEquals(
            "lodDensity 直乘全文件恰四条（帧首 dp 阈族：R386 一条+R388 刀1 隐没带两条+刀2 REF 一条——别处零乘法，R355 单口纪律不破）",
            4,
            Regex("\\*\\s*lodDensity").findAll(b).count(),
        )
    }

    /**
     * ③ 丝缕基因保留锚：远档只裁层数——clip 体恰 `blobCount = 1` 一行（种子/
     * 半径/配比零改）；配比包装填在裁剪后（随裁后层数归一=单层 FRONT 语义）；
     * NEBULA_FS 丝缕基因在场（ridge 脊线/暗尘带/丝间留空门）+uniform 五件
     * （R388 翻新保义：四件→五件=刀2 uFreqScale 维护性扩展——「丝缕基因的
     * 尺度变换非基因改变」，scale=1 恒等既有基因；丝间留空门=gateLo 标度态
     * 字面，scale=1 位移零=0.40 原值恒等）。
     */
    @Test
    fun 丝缕基因保留_只裁层数零碰shader基因() {
        val b = builder()
        val cpx = cpxBodyOf(b)
        // clip 结构：guard（blobCount>1 且中心投影半径过阈）+体（恰一行层数赋值）
        val clipGuard = cpx.indexOf("if (blobCount > 1 &&")
        assertTrue("远档裁剪 guard 在场（blobCount>1 且投影过阈）", clipGuard >= 0)
        val clipBody = cpx.substring(clipGuard, cpx.indexOf("}", clipGuard))
        assertEquals("远档只裁层数：clip 体恰 blobCount = 1 一行（种子/半径/配比零改）", 1, Regex("blobCount = 1").findAll(clipBody).count())
        assertTrue("clip 体零种子零 uniform 接触（丝缕基因不动）", !clipBody.contains("seed") && !clipBody.contains("13.7"))
        // 配比包在裁剪后装填（随裁后 blobCount 归一——单层=FRONT_ALPHA 语义原样）
        assertTrue(
            "配比包装填在裁剪后（随裁后层数归一）",
            cpx.indexOf("blobCount = 1") < cpx.indexOf("FogFlocFx.blobAlphaPack01(flocSeed, flocAlphaPack, blobCount)"),
        )
        // 种子相拉路径原样（k 层相拉——clip 不改种子基因）
        assertTrue("种子相拉原样（seed + k * 13.7f）", cpx.contains("seed + k * 13.7f"))
        // NEBULA_FS 丝缕基因 + uniform 六件（R388 翻新：第五件 uFreqScale=刀2
        // 频率标度维护性扩展——默认 1.0 既有基因恒等，判词保义；R392 判词①翻新：
        // 第六件 uNoiseVol sampler=烘焙 3D 噪声体——载体件非参数件；ridge 构造
        // 基因字面原样，载体=fieldAt 采样）
        val fs = nebulaFsOf(glShaders())
        assertTrue("ridge 脊线原样（丝缕基因①——R392 载体=fieldAt 烘焙体采样）", fs.contains("float ridge = 1.0 - abs(2.0 * fieldAt(q * 2.3 + warp * 1.1) - 1.0);"))
        assertTrue("暗尘带减法剪影原样（丝缕基因③）", fs.contains("a *= 1.0 - 0.55 * lane;"))
        assertTrue(
            "丝间留空门=gateLo 标度态（R388 稀疏化：scale=1 位移零=0.40 原值恒等——门基因保义）",
            fs.contains("float gateLo = 0.40 + 0.24 * (1.0 - uFreqScale);") &&
                fs.contains("float a = smoothstep(gateLo, 0.82, d2) * fall * uAlpha;"),
        )
        // 枚举含精度限定与 sampler（R392：uSeed/uTime 显式 highp 件+uNoiseVol
        // mediump sampler3D 第六件——regex 须吃精度前缀否则静默漏件）
        val uniforms = Regex("uniform (?:highp |mediump )?(?:float|vec[23]|sampler3D) (u\\w+);").findAll(fs).toList().map { it.groupValues[1] }
        assertEquals(
            "NEBULA_FS uniform 六件（五浮点+uNoiseVol sampler 第六件——R392 判词①载体件）",
            listOf("uColor", "uAlpha", "uSeed", "uTime", "uFreqScale", "uNoiseVol"),
            uniforms,
        )
    }
}
