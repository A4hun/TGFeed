package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R343 怠速治理 wiring 源码锚（R325WiringSourceTest 同款 user.dir 源定位模式
 * ——UI 物理场无 JVM 直测面（Android Log/SystemClock 依赖），结构件以源锚
 * 锁死；行为数学半体由 TasteNBodyIdleMotionTest（core 纯件）承重）：
 *
 *  - **D3 时钟域同符号闸**（复发闸——R343 根因类）：beginRenderFrame 相位钟与
 *    stepLocked/build 落钟必须同为 SystemClock.uptimeMillis（历史坑=帧组装
 *    epoch 墙钟混入相位减法→恒钳 1.0 死拍）；
 *  - **D1 退役干净闸**：icGalacticCircular/mEnc（R249 旋转曲线 IC）在物理场
 *    绝迹——全场缓旋退役不残留；
 *  - **D2 对结构闸**：双星孤立二体（createState(2)+软化圆速度单口+场冻结标记）
 *    在场；
 *  - **A1/A2 假运动清剿闸**（r344 审计 A 级——R345 Canvas 退役后 GL 单端）：微星
 *    18 桶同频呼吸与星团刚体缓旋（swirl=t×0.05）绝迹；
 *  - **渲染器零接线负锚**：GL×4 四个 beginRenderFrame 调用位原样（R343 修复单点
 *    收敛在物理场内部=渲染器文件零改动红线；R345 Canvas 侧两调用位随渲染器整类
 *    退役）+帧组装 nowMs epoch 语义不动（reveal 老化口径）+5s 步进环原样
 *    （节拍设计保留）。
 */
class R343WiringSourceTest {

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

    private fun source(relPath: String): String {
        val file = File(mainSourceRoot, relPath)
        assertTrue("源文件必须存在：$relPath", file.isFile)
        return file.readText()
    }

    private fun phys(): String = source("dev/tgfeed/ui/TasteGalaxyPhysics.kt")

    private fun nbody(): String = source("dev/tgfeed/core/tastemap/TasteNBody.kt")

    private fun gl(): String = source("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun tms(): String = source("dev/tgfeed/ui/TasteMapScreen.kt")

    // ==================== D3 时钟域同符号（复发闸） ====================

    @Test
    fun `D3-相位钟与步进钟同域uptime`() {
        val p = phys()
        // 相位钟自取 uptime（与 lastStepMs 同域——单口收敛一处函数体）
        assertTrue(
            "beginRenderFrame 相位钟必须自取 SystemClock.uptimeMillis（时钟域修复）",
            Regex("renderPhase01\\(\\s*android\\.os\\.SystemClock\\.uptimeMillis\\(\\)\\s*,\\s*lastStepMs\\s*,\\s*TICK_MS").containsMatchIn(p),
        )
        // 步进侧落钟同符号（build+stepLocked 两处——历史坑即两侧异符号）
        assertTrue(
            "stepLocked 落钟必须是 uptime（同域契约）",
            Regex("lastStepMs = android\\.os\\.SystemClock\\.uptimeMillis\\(\\)").containsMatchIn(p),
        )
        // 死拍形绝迹：lastStepMs 不得落 epoch 墙钟
        assertFalse(
            "lastStepMs 不得用 System.currentTimeMillis（时钟域错配类复发）",
            Regex("lastStepMs = System\\.currentTimeMillis").containsMatchIn(p),
        )
        // 相位数学单源消费（core 单口——别处不重写相位公式）
        assertTrue(
            "相位数学必须单源 TasteNBody.renderPhase01 消费",
            Regex("TasteNBody\\.renderPhase01").containsMatchIn(p),
        )
        assertTrue(
            "core 单源 renderPhase01 必须在场",
            Regex("fun renderPhase01\\(nowMs: Long, lastStepMs: Long, tickMs: Long\\): Float").containsMatchIn(nbody()),
        )
    }

    // ==================== D1 退役干净（绝迹闸） ====================

    @Test
    fun `D1-旋转曲线IC在物理场绝迹`() {
        val p = phys()
        assertFalse(
            "旋转曲线 IC 调用必须从物理场绝迹（全场缓旋退役——R249 旋转曲线 IC 撤除）",
            Regex("TasteNBody\\.icGalacticCircular").containsMatchIn(p),
        )
        assertFalse(
            "旋转曲线包络质量前缀和（mEnc）必须绝迹",
            Regex("mEnc").containsMatchIn(p),
        )
        // 场冻结标记在场（构造性零旋转——非钳位补丁）
        assertTrue("场冻结结构标记必须在场", Regex("场冻结").containsMatchIn(p))
    }

    // ==================== D2 双星孤立二体（对结构闸） ====================

    @Test
    fun `D2-双星孤立二体对结构在场`() {
        val p = phys()
        assertTrue(
            "双星 IC 必须消费软化圆速度单口 binaryCircularVelSoftened",
            Regex("TasteNBody\\.binaryCircularVelSoftened").containsMatchIn(p),
        )
        assertTrue(
            "每对必须为孤立二体 mini N 体（createState(2,…)）",
            Regex("createState\\(\\s*2\\s*,").containsMatchIn(p),
        )
        assertTrue(
            "core 单源 binaryCircularVelSoftened 必须在场",
            Regex("fun binaryCircularVelSoftened\\(mSum: Float, d: Float\\): Float").containsMatchIn(nbody()),
        )
        // 步进只步对（field 零步进=冻结）：step 内对表步进消费
        assertTrue(
            "stepLocked 必须经 pairStates 步进（对间孤立）",
            Regex("for \\(k in pairStates\\.indices\\)").containsMatchIn(p),
        )
    }

    // ==================== A1/A2 假运动清剿（r344 审计 A 级——R345 Canvas 退役后 GL 单端） ====================

    @Test
    fun `A1-微星桶级同频呼吸GL退役`() {
        val g = gl()
        assertFalse(
            "GL 微星桶级呼吸必须退役",
            Regex("sin\\(t \\* 1\\.1f").containsMatchIn(g),
        )
        assertTrue("GL A1 退役标记必须在场", Regex("R343 A1").containsMatchIn(g))
        // 恒稳档保留（远星恒稳——alpha 基档原语义；R345 Canvas 侧同锚随渲染器整类退役）
        assertTrue(
            "GL 微星恒稳 0.90 档必须在场",
            Regex("MICRO_TIER_A\\[tier\\] \\* 0\\.90f").containsMatchIn(g),
        )
    }

    @Test
    fun `A2-星团刚体缓旋GL退役`() {
        val g = gl()
        assertFalse(
            "GL 星团刚体缓旋必须退役",
            Regex("swirl = t \\* 0\\.05f").containsMatchIn(g),
        )
        assertTrue("GL A2 退役标记必须在场", Regex("R343 A2").containsMatchIn(g))
    }

    // ==================== 渲染器零接线负锚（单口收敛红线） ====================

    @Test
    fun `渲染器调用位零改动与帧时钟语义原样`() {
        // 调用位原样（R345 Canvas 退役后 GL×4——R343 修复收口物理场内部=渲染器零改动）
        assertEquals(
            "GL beginRenderFrame 调用位必须原样四处",
            4,
            Regex("beginRenderFrame\\(f\\.nowMs\\)").findAll(gl()).count(),
        )
        // 帧组装 nowMs 保持 epoch（reveal 老化等其他消费者零扰动）
        assertTrue(
            "帧组装 nowMs 必须保持 System.currentTimeMillis（epoch——reveal 口径）",
            Regex("nowMs = System\\.currentTimeMillis\\(\\)").containsMatchIn(tms()),
        )
        // 5s 步进环原样（节拍设计保留——TICK_MS 零改动）
        assertTrue(
            "物理步进环必须原样（delay(TICK_MS)+step）",
            Regex("delay\\(TasteGalaxyPhysics\\.TICK_MS\\)[\\s\\S]{0,60}physics\\?\\.step\\(engine\\.timeSec\\)").containsMatchIn(tms()),
        )
        assertEquals(
            "步进周期必须保持 5s（TICK_MS=5000——设计节拍保留）",
            "const val TICK_MS = 5000L",
            Regex("const val TICK_MS = 5000L").find(phys())?.value,
        )
    }
}
