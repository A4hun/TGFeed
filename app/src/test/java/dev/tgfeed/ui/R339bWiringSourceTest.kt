package dev.tgfeed.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R339b 二阶段接线 wiring 源码锚（R343WiringSourceTest 同款 user.dir 源定位模式
 * ——行为数学半体由 GlFrameBuilderTest（crossfade 能量守恒/星芒门账）与
 * GlTextureBakerTest（芒剖面单源）承重；本件锁死渲染端结构红线）：
 *
 *  - **探针口径闸**（V38-⑤ 依赖）：『R339 星芒门: 候选=.. 帽=5 入选=..』
 *    TasteMapX tag+~6s 节拍（%360）——票面口径逐字锁死；
 *  - **R242 r 门十字绝迹闸**：GL 侧 r≥4 门十字（STAR_SPIKE_MIN_R 斜刺/五停点
 *    线性渐变）退役——亮度 top 门 [TasteStarGlow.SPIKE_MAX_STARS] 替位；
 *  - **Canvas 零改动负锚**（架构师 21:0x 令；R345 退役已收口）：TasteFlightRenderer
 *    位图三档绘制（L2/FAR/CLOSEUP 死亡值引用）+spikeBmp R242 双子原「在场等
 *    R345 整删」锚随删翻正为绝迹锚——类本体已整类物理删除；
 *  - **芒剖面单源闸**：GL 芒形（GlTextureBaker.bakeSpike）消费 core
 *    [TasteStarGlow.spikeAlpha01]——(1−u)³ 快衰减双端同源（Canvas 双子
 *    buildSpike 随 R345 走，不在本锚域）。
 */
class R339bWiringSourceTest {

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

    private fun gl(): String = source("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun glTex(): String = source("dev/tgfeed/ui/gl/GlTextureBaker.kt")

    private fun canvas(): String = source("dev/tgfeed/ui/TasteFlightRenderer.kt")

    // ==================== 探针口径（V38-⑤ 依赖） ====================

    @Test
    fun `星芒门探针行口径（候选 帽 入选 三字段+TasteMapX+6s 节拍——V38 票面逐字）`() {
        val src = gl()
        // 票面口径『R339 星芒门: 候选=.. 帽=5 入选=..』——字段序与文案逐字锁死
        assertTrue(
            "探针行必须按 V38-⑤ 票面口径逐字（候选/帽/入选三字段序）",
            src.contains("\"R339 星芒门: 候选=\${statSpikeCand} 帽=\${TasteStarGlow.SPIKE_MAX_STARS} 入选=\${statSpikeStars}\""),
        )
        // TasteMapX 通道（logcat -s TasteMapX 驱动口径）+~6s 一条节拍（%360）
        val probeIdx = src.indexOf("R339 星芒门: 候选=")
        val tagBefore = src.lastIndexOf("TasteMapX", probeIdx)
        assertTrue("探针必须走 TasteMapX tag", tagBefore in 0 until probeIdx)
        assertTrue("~6s 节拍（%360 == 0——statTickR321 同族口径）", src.contains("statTickR339 % 360 == 0"))
    }

    // ==================== R242 r 门十字绝迹（GL 侧） ====================

    @Test
    fun `R242 r 门十字绝迹（STAR_SPIKE_MIN_R 斜刺与 r大于4 门退役——亮度 top 门替位）`() {
        val src = gl()
        // 旧门遗族绝迹（GL 侧）：r≥4 门十字注释与 12px 斜刺门
        assertFalse("R242 r≥4 门注释必须退役", src.contains("r≥4 横竖两向"))
        assertFalse("STAR_SPIKE_MIN_R（12f 斜刺门）必须物理删除（GL 侧）", src.contains("STAR_SPIKE_MIN_R"))
        // 新门在场：亮度 top 门（selectSpikesR339 单口+SPIKE_MAX_STARS 帽）
        assertTrue("亮度 top 门预扫单口必须在场", src.contains("private fun selectSpikesR339"))
        assertTrue("top 门帽=core 单源 SPIKE_MAX_STARS", src.contains("TasteStarGlow.SPIKE_MAX_STARS"))
        // R242 五停点线性渐变剖面绝迹（换 (1−u)³ 单源——GlTextureBakerTest 剖面锚）
        assertFalse("五停点渐变退役", glTex().contains("0.90f - (t - 0.5f) / 0.2f * 0.46f"))
    }

    // ==================== Canvas 零改动负锚（架构师 21:0x 令；R345 已整删收口） ====================

    @Test
    fun `Canvas 零接线负锚（位图三档与 spikeBmp 双子已随 R345 整删——类本体不在场）`() {
        val src = canvas()
        // 本批零 Canvas 编辑红线：无 R339b 标记（R339 一阶段死亡值引用为 R334
        // 时代存量——非本批接线）
        assertFalse("TasteFlightRenderer 必须零 R339b 标记（Canvas 免做红线）", src.contains("R339b"))
        // R345 Canvas 退役收口：位图三档绘制引用（L1/L2/FAR）与 spikeBmp 双子随
        // TasteFlightRenderer 整类物理删除绝迹——原「在场等 R345 删」锚随删翻正
        assertFalse("L2 死亡层绘制引用已随 R345 整删绝迹", src.contains("TasteStarGlow.L2_R"))
        assertFalse("L1 承重位图绘制引用已随 R345 整删绝迹", src.contains("TasteStarGlow.L1_R"))
        assertFalse("FAR 死亡层绘制引用已随 R345 整删绝迹", src.contains("TasteStarGlow.FAR_R"))
        assertFalse("spikeBmp Canvas 双子已随 R345 整删绝迹", src.contains("spikeBmp"))
        assertFalse("Canvas 渲染器类本体必须绝迹（R345 整类删除）", src.contains("internal class TasteFlightRenderer"))
    }

    // ==================== 芒剖面单源（GL 芒形=core 剖面） ====================

    @Test
    fun `GL 芒形剖面单源（bakeSpike 消费 spikeAlpha01——(1−u)³ 快衰减 core 同源）`() {
        val src = glTex()
        assertTrue(
            "bakeSpike 必须消费 TasteStarGlow.spikeAlpha01（剖面单源——绘制端零第二套衰减公式）",
            src.contains("TasteStarGlow.spikeAlpha01(u * 2f)"),
        )
        // 纵向三行柔边保留（细线亚像素抗锯齿——行结构原样）
        assertTrue("纵向柔边行结构原样", src.contains("3, 4 -> 1f"))
    }
}
