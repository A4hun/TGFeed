package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R376 小米星图双病症正治 wiring 源码锚（R349WiringSourceTest 同款 user.dir 源
 * 定位模式——GL 渲染管线无 JVM 直测面，以源锚锁**退役结构面**；数值半体由
 * 既有三件承重）：
 *  - **病症 B 实锤退役**（用户小米截图像素实锚双根因）：①T1 徽章环（常驻 1.35×r
 *    暖棕环 0xFFD9C08A×α0.5——截图2 实测 RGB(97,89,69) 签名）绝迹；②星风泡
 *    （R321 远观 6.0~8.5×r 淡环——截图1 宽场 36/689 颗半径比代码指纹逐位吻合）
 *    绘制/常量/探针三面绝迹+**星风语义保活锚**（门/强度/流/尘埃不对称——语义
 *    不灭退役的只是视觉本体）；
 *  - **病症 A 机制正治**（用户「3D 模型进来时对的，过一会儿 3D 样式消失」）：
 *    ③回退序重排锚（L1=mesh∞ 几何降级零掉档→L4=+bias1.5 形态降级最后手段
 *    ——旧序 L1=bias=时序病根）；④滞回对称锚（升级连续 2 窗门+恢复阈 1.0%
 *    ——旧单窗即升+0.5% 死区=高水位粘滞病根）。
 *  测试名零 / · . 字符（JVM 非法字符纪律——AGENT-DISCIPLINE §七）。
 */
class R376WiringSourceTest {

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
    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")
    private fun starwind(): String = src("dev/tgfeed/core/tastemap/StarwindFx.kt")

    // ==================== ① 病症 B 实锤一：T1 徽章环绝迹 ====================

    @Test
    fun `病症B一-T1徽章环退役绝迹锚（常驻灰圈正治）`() {
        val fb = glFrameBuilder()
        // 色值签名级绝迹（0xFFD9C08A 唯一消费位=徽章环——退役后全件零命中；
        // 退役判词注释不含字面值=史实注释不自伤）
        assertTrue(
            "徽章环色 0xFFD9C08A 应全件绝迹（R376 退役——常驻低饱和环=灰圈读法）",
            !fb.contains("0xFFD9C08A"),
        )
        // 常驻绘制门绝迹（旧件 tier==1 恒画 r*1_35f 环）
        assertTrue(
            "旧徽章环绘制门（s_tier==1 恒画环）应绝迹",
            !Regex("s\\.tier == 1 && r >= 3f").containsMatchIn(fb),
        )
        // 退役判词在场（非静默删除——R348 退役注释先例）
        assertTrue(
            "R376 徽章环退役判词注释必须在场（史实锚）",
            fb.contains("R376 T1 徽章环退役"),
        )
    }

    // ==================== ② 病症 B 实锤二：星风泡三面绝迹 ====================

    @Test
    fun `病症B二-星风泡退役三面绝迹锚（宽场灰圈主根因）`() {
        val fb = glFrameBuilder()
        val sw = starwind()
        // 绘制面：泡消费绝迹（绘制调用+计数+探针行；退役判词注释不含函数名=
        // 史实注释不自伤）
        assertTrue(
            "GlFrameBuilder 泡绘制（bubbleRadiusCoef 消费）应绝迹",
            !fb.contains("bubbleRadiusCoef"),
        )
        assertTrue(
            "GlFrameBuilder 泡计数 windBubbleN 应绝迹（计数随绘制退役）",
            !fb.contains("windBubbleN"),
        )
        assertTrue(
            "GlFrameBuilder 泡探针行（R321 星风GL）应绝迹（零在场零记账零日志噪音）",
            !fb.contains("R321 星风GL"),
        )
        // 常量面：StarwindFx 泡族四常量+双函数签名级绝迹
        for (name in listOf("BUBBLE_R_MIN", "BUBBLE_R_MAX", "BUBBLE_A_MIN", "BUBBLE_A_MAX")) {
            assertTrue(
                "StarwindFx 泡常量 $name 声明应绝迹（R376 泡族清偿）",
                !Regex("const val $name").containsMatchIn(sw),
            )
        }
        for (fn in listOf("fun bubbleRadiusCoef", "fun bubbleAlphaOf")) {
            assertTrue(
                "StarwindFx 泡函数 $fn 声明应绝迹（R376 泡族清偿）",
                !sw.contains(fn),
            )
        }
        // 退役判词在场（史实锚——像素实锚记录）
        assertTrue(
            "R376 星风泡退役判词注释必须在场（36/689 颗像素实锚史实）",
            sw.contains("R376 星风泡退役"),
        )
        assertTrue(
            "GlFrameBuilder 星风泡退役判词注释必须在场",
            fb.contains("R376 星风泡退役"),
        )
    }

    // ==================== ③ 星风语义保活（退役≠灭门） ====================

    @Test
    fun `星风语义保活锚（门强度流尘埃四件与绘制本体切割）`() {
        val sw = starwind()
        assertTrue("星风门 isWindy 必须保活（渲染门/whyChain 同口——语义不灭）", sw.contains("fun isWindy"))
        assertTrue("强度 strength01Of 必须保活（星风流速率/尘埃不对称消费单源）", sw.contains("fun strength01Of"))
        assertTrue("星风流 streamRateOf 必须保活（贴脸焦点件——非圆圈形态）", sw.contains("fun streamRateOf"))
        assertTrue("尘埃不对称 dustAsym01 必须保活（背风密迎风稀——逻辑件零圈形）", sw.contains("fun dustAsym01"))
        assertTrue(
            "星风门常量 W_RECENT7_SAT 必须保活（强度归一上限——非泡族）",
            sw.contains("const val W_RECENT7_SAT"),
        )
    }

    // ==================== ④ 病症 A：回退序重排锚（形态降级最后手段） ====================

    @Test
    fun `病症A-回退序重排锚（mesh首档零掉档与bias压L4）`() {
        val r = renderer()
        // 新序三旋钮映射：L1=mesh∞（几何降级零掉档）/L4=bias1.5（形态降级最后手段）
        assertTrue(
            "L1 必须是 mesh∞（旧序 L1=bias=「3D 消失」根因——R376 重排）",
            Regex("builder\\.lodMeshBias = if \\(lodLevel >= 1\\) Float\\.POSITIVE_INFINITY else 1f").containsMatchIn(r),
        )
        assertTrue(
            "bias 1.5 必须压到 L4（形态降级=最后手段）",
            Regex("builder\\.lodSolidBias = if \\(lodLevel >= 4\\) 1\\.5f else 1f").containsMatchIn(r),
        )
        assertTrue(
            "旧序（L1=bias/L2=mesh）应绝迹",
            !Regex("lodSolidBias = if \\(lodLevel >= 1\\)").containsMatchIn(r) &&
                !Regex("lodMeshBias = if \\(lodLevel >= 2\\)").containsMatchIn(r),
        )
        // 帽映射：2→64 / 3,4→32
        assertTrue(
            "帽收缩映射必须 2→64、3与4→32（R376 L2/L3 档位）",
            Regex("2 -> 64").containsMatchIn(r) && Regex("3, 4 -> 32").containsMatchIn(r),
        )
    }

    // ==================== ⑤ 病症 A：滞回对称锚（单窗风暴不降形态） ====================

    @Test
    fun `病症A-滞回对称锚（升级双窗门与恢复阈1_0）`() {
        val r = renderer()
        // 升级连续 2 窗门（lodBadWins——单窗装配风暴/瞬时尖峰不再即触发）
        assertTrue(
            "升级必须连续 2 窗（lodBadWins 门——单窗即升=旧时序病根）",
            Regex("lodBadWins\\+\\+").containsMatchIn(r) &&
                Regex("lodBadWins >= 2 && lodLevel < 4").containsMatchIn(r),
        )
        // 恢复阈 1.0%（死区 [0.5,2]→[1,2] 减半=高水位粘滞减半）
        assertTrue(
            "恢复阈必须 1.0%（旧 0.5%——高水位粘滞减半）",
            Regex("jankPct < 1\\.0f").containsMatchIn(r),
        )
        assertTrue(
            "旧恢复阈 0.5% 应绝迹",
            !Regex("jankPct < 0\\.5f").containsMatchIn(r),
        )
        // 升级行口径含新序说明（可观测性——探针行口径随行为）
        assertTrue(
            "升级行必须含 R376 序说明（可观测口径随行为）",
            r.contains("R376序:①全低模②帽64③帽32④bias1.5"),
        )
    }
}
