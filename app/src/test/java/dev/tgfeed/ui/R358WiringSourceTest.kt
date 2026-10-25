package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R358 语义雾质感革命 wiring 源码锚（R357WiringSourceTest 同款 user.dir 源定位
 * 模式——GL 渲染管线无 JVM 直测面，以源锚锁结构面）：
 *  - **量级锚①FOG_ALPHA_MAX 新值域**：0.05→候选带 [0.10,0.16]（用户 2026-10-23
 *    拍板方案 A「语义雾质感革命」——R252「均匀大圆片+高 alpha」禁区由「形态
 *    丝缕化+带上限」双守：带上界 0.16 ≪ 旧病 0.26；KDoc 量级史+诚实量级自认
 *    在案——真实星云肉眼不可见，本档为梦幻感可读性优先的压缩）；
 *  - **结构锚②NEBULA_FS 四件在场**：脊线 fbm 丝缕层（峰值 alpha 只落纤维丝）/
 *    种子哈希方向角各向异性拉伸（约 1.8:1，零新参数件）/暗尘埃带减法剪影/
 *    雾心色温分层（零新色源——色相仍 uColor 单源）；
 *  - **反锚③语义件零改**：FOG_FADE_NEAR/FAR 渐散原值、EMISSION_ALPHA_MULT=1.6
 *    与 emissionAlphaOf 结构原样（发射分化单源）、NEBULA_FS highp 守档（R357
 *    勿动）+径向 falloff 与 discard 仍走原 d（弥散保义——絮化≠锐化）+零新
 *    uniform（零新参数件）；
 *  - **联动锚④发射帽=2×雾帽单源**：防「发射档被帽钳到普通档之下」语义倒挂
 *    （R323 K②「发射=更亮」+r358_notes §2.2「链不变联动到约 0.21」——帽守
 *    旧值 0.10 会在雾帽 0.13 档造成倒挂，故帽随雾帽联动，判据/乘子零改）。
 */
class R358WiringSourceTest {

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

    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    private fun tasteNebula(): String = src("dev/tgfeed/core/tastemap/TasteNebula.kt")

    private fun emissionFx(): String = src("dev/tgfeed/core/tastemap/EmissionNebulaFx.kt")

    private fun nebulaFsBody(gs: String): String {
        val m = Regex("const val NEBULA_FS = \"\"\"([\\s\\S]*?)\"\"\"").find(gs)
        assertTrue("NEBULA_FS 块必须在场", m != null)
        return m!!.groupValues[1]
    }

    // ==================== ① 量级锚：FOG_ALPHA_MAX 候选带 [0.10,0.16]+KDoc 自认 ====================

    @Test
    fun `量级-雾帽在候选带内且KDoc量级自认在案`() {
        val tn = tasteNebula()
        val m = Regex("const val FOG_ALPHA_MAX = ([\\d.]+)f").find(tn)
        assertTrue("FOG_ALPHA_MAX 常量必须在场", m != null)
        val v = m!!.groupValues[1].toFloat()
        assertTrue(
            "FOG_ALPHA_MAX 须在 R358 候选带 [0.10,0.16] 内（用户 2026-10-23 拍板——带上界 0.16 ≪ R252 旧病 0.26），实读=${v}",
            v in 0.10f..0.16f,
        )
        assertTrue("R252 旧档 0.05 应已翻篇（FOG_ALPHA_MAX=${v}）", v > 0.06f)
        // KDoc 自认：量级史（R252 收敛史）+丝缕承载前提+诚实量级压缩
        assertTrue("KDoc 须含 R252 收敛史自认", tn.contains("R252"))
        assertTrue("KDoc 须含 R358 丝缕承载前提自认（形态丝缕化是 alpha 抬升的前提）", tn.contains("丝缕"))
        assertTrue("KDoc 须含诚实量级压缩自认（真实星云肉眼不可见）", tn.contains("肉眼不可见"))
    }

    // ==================== ② 结构锚：NEBULA_FS 四件在场（丝缕/拉伸/暗带/色分层） ====================

    @Test
    fun `结构-星云着色器丝缕拉伸暗带色分层四件在场`() {
        val fs = nebulaFsBody(glShaders())
        // ① 丝缕层：脊线 fbm（1-abs(2*fbm-1) 脊线噪声天然产纤维脉络）+max 合成走既有门
        assertTrue(
            "丝缕层脊线 fbm 必须在场（ridge=1-abs(2*fbm3(q*2.3+warp*1.1)-1)）",
            fs.contains("float ridge = 1.0 - abs(2.0 * fbm3(q * 2.3 + warp * 1.1) - 1.0);"),
        )
        assertTrue(
            "密度合成 d2=max(den, ridge*0.85) 必须在场（峰值 alpha 只落纤维丝）",
            fs.contains("float d2 = max(den, ridge * 0.85);"),
        )
        // ② 各向异性拉伸：种子哈希固定方向角+短轴压缩（零新参数件，种子即方向）
        assertTrue(
            "种子哈希方向角必须在场（fract(uSeed*黄金比例)）",
            fs.contains("fract(uSeed * 0.6180339887)"),
        )
        assertTrue("短轴压缩 1.8 必须在场（约 1.8:1 拉伸）", fs.contains("st.y *= 1.8;"))
        // ③ 暗尘埃带：减法剪影（纤维亮脊旁刻暗缝——雾体内部结构零新绘制层）
        assertTrue(
            "暗尘埃带 lane 必须在场（smoothstep(0.62,0.88,ridge)*(1-den)）",
            fs.contains("float lane = smoothstep(0.62, 0.88, ridge) * (1.0 - den);"),
        )
        assertTrue("暗带减法式衰减必须在场", fs.contains("a *= 1.0 - 0.55 * lane;"))
        // ④ 雾心色温分层：零新色源（色相仍=uColor 单源——丝亮隙暗明度纵深）
        assertTrue(
            "色分层必须在场（col=uColor*(0.70+1.05*d2)——零新色源）",
            fs.contains("vec3 col = uColor * (0.70 + 1.05 * d2);"),
        )
    }

    // ==================== ③ 反锚：语义件零改（渐散/发射分化/弥散保义/精度守档） ====================

    @Test
    fun `反锚-语义件零改（渐散原值发射分化单源弥散保义highp守档零新参数）`() {
        val tn = tasteNebula()
        // 飞近渐散原值（语义零改）
        assertTrue("FOG_FADE_NEAR=420f 原样", tn.contains("const val FOG_FADE_NEAR = 420f"))
        assertTrue("FOG_FADE_FAR=1500f 原样", tn.contains("const val FOG_FADE_FAR = 1500f"))
        // 发射分化单源原样（乘子 1.6 与 emissionAlphaOf 结构零改）
        val ef = emissionFx()
        assertTrue("EMISSION_ALPHA_MULT=1.6f 原样", ef.contains("const val EMISSION_ALPHA_MULT = 1.6f"))
        assertTrue(
            "emissionAlphaOf 结构原样（乘子+帽单口）",
            ef.contains("(fogAlpha * EMISSION_ALPHA_MULT).coerceAtMost(EMISSION_ALPHA_CAP)"),
        )
        val fs = nebulaFsBody(glShaders())
        // R357 精度守档（fp16 塌噪声实锤——勿动）
        assertTrue("NEBULA_FS 须守 precision highp float（R357）", fs.contains("precision highp float;"))
        // 弥散保义：径向 falloff 与 discard 边界仍走原 d（边缘零锐化）
        assertTrue(
            "径向 falloff 原 d 原样（弥散保义——絮化≠锐化）",
            fs.contains("float fall = 1.0 - smoothstep(0.12, 1.0, d);"),
        )
        assertTrue("discard 圆界原 d 原样", fs.contains("if (d > 1.0) discard;"))
        // 零新 uniform（零新参数件——种子即方向）
        val uniformCount = Regex("uniform\\s").findAll(fs).count()
        assertEquals("NEBULA_FS uniform 仍=uColor/uAlpha/uSeed/uTime 四件（零新参数件）", 4, uniformCount)
        // 彩虹禁令不犯：零 hue(t) 族（色相循环绝迹）
        assertFalse("零随时间色相循环（禁令 12）", Regex("hue|hsv", RegexOption.IGNORE_CASE).containsMatchIn(fs))
    }

    // ==================== ④ 联动锚：发射帽=2×雾帽单源（防发射/普通倒挂） ====================

    @Test
    fun `联动-发射帽2x雾帽单源（防发射档被钳到普通档之下语义倒挂）`() {
        val ef = emissionFx()
        assertTrue(
            "EMISSION_ALPHA_CAP 须=2×FOG_ALPHA_MAX 联动单源（R252 档 2:1 历史比——帽守旧值会致发射档倒挂普通档）",
            ef.contains("const val EMISSION_ALPHA_CAP = TasteNebula.FOG_ALPHA_MAX * 2f"),
        )
        // 数值一致性：解析两侧常量互证（帽=2×雾帽）
        val capM = Regex("const val EMISSION_ALPHA_CAP = TasteNebula\\.FOG_ALPHA_MAX \\* 2f").find(ef)
        assertTrue("帽联动公式在场", capM != null)
        val fogM = Regex("const val FOG_ALPHA_MAX = ([\\d.]+)f").find(tasteNebula())
        assertTrue("雾帽可解析", fogM != null)
        val fogMax = fogM!!.groupValues[1].toFloat()
        // 发射满档=雾帽×1.6 须 > 雾帽（发射恒亮于普通——R323 K② 语义守护）
        assertTrue(
            "发射满档（${fogMax}×1.6=${fogMax * 1.6f}）须亮于普通满档（${fogMax}）且帽（${fogMax * 2f}）不钳链",
            fogMax * 1.6f > fogMax && fogMax * 2f >= fogMax * 1.6f,
        )
    }
}
