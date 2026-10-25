package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R159b 起播一拍对齐策略单测（纯函数全枚举）。
 * 用户第三时序实锤「起播瞬间背景/宽度跳变」的修复口径：
 * - 交界拍 = 首帧真正画进渲染面（onRenderedFirstFrame），拍上只许「占位→画面」；
 * - 宽度校正（实测比 ≥15% 换目标）冻结到首帧渲染后释放（像素基线：修前素材
 *   全是元数据正确条目零宽度跳——宽度残余只在偏差条目发作，负对照见
 *   r159_notes.md 像素证据节）；
 * - live 底衬首帧上屏前不采纳、未采纳期短拍追赶（修前签名：r160_verify4
 *   3.188s 起播同帧外带 -37 阶跃 + 300ms 异步溶解 + 外右带永久 -27）；
 * - 列内占位模糊底衬仅手动旋转（外层显式清屏）形态保留（修前签名：r160_repro
 *   5.889s 列内上带单帧 -9.5 阶跃=列内模糊↔外层底衬口径差换血）；
 * - 方向链（useZoom/resizeMode）保持「首帧前就位」——不在本策略（刻意分野，
 *   见 PlaybackStartPolicy 头注释）。
 */
class PlaybackStartPolicyTest {

    // ---------------- ① 宽度校正冻结：首帧渲染前不释放 ----------------

    @Test
    fun `实测比释放_首帧未上屏一律冻结`() {
        // 加载态（含 onVideoSizeChanged 已到、首帧未渲染的 1~3 帧窗口）实测比
        // 不上报——预判偏差 ≥15% 的换目标不许压在首帧上屏窗口内
        // （用户实测「起播跳宽」根因）
        assertFalse(PlaybackStartPolicy.shouldReleaseMeasuredAspect(firstFrameSeen = false))
    }

    @Test
    fun `实测比释放_首帧上屏后放行`() {
        // 冻结到首帧渲染后：释放走外层既有 300ms tween（与翻页换形态同口径）
        assertTrue(PlaybackStartPolicy.shouldReleaseMeasuredAspect(firstFrameSeen = true))
    }

    // ---------------- ② live 底衬采纳闸：首帧前不换血 ----------------

    @Test
    fun `live底衬采纳_首帧未上屏不采纳`() {
        // 底衬保持封面（与列内占位同图同拍），不许底衬先于画面换血
        assertFalse(PlaybackStartPolicy.shouldAdoptLiveBackdrop(firstFrameSeen = false))
    }

    @Test
    fun `live底衬采纳_首帧上屏后放行`() {
        assertTrue(PlaybackStartPolicy.shouldAdoptLiveBackdrop(firstFrameSeen = true))
    }

    // ---------------- ③ 镜像节拍（R194①② 双镜像：R89 轮询族退役） ----------------

    @Test
    fun `镜像节拍_常数单一来源锚`() {
        // 80ms ≈ 12.5fps（v1 保守案：模糊掩盖低刷率）；快照不打扰前台解码是红线
        //（帧统计前后对照，真机验证项）
        assertEquals(80L, PlaybackStartPolicy.MIRROR_FRAME_INTERVAL_MS)
    }

    @Test
    fun `镜像节拍_旧R89轮询族已退役防回潮`() {
        // R89 预览条供图路径退役（1.5s 粒度跳帧+逐拍渐变=「切 tab 疯狂闪烁」病根）：
        // 常数与取值函数删除后不得回潮——反射断言符号不在
        val members = PlaybackStartPolicy::class.java.declaredMethods.map { it.name }
        assertTrue(
            "liveBackdropPollIntervalMs 应已退役（镜像直拍取代两档轮询）",
            "liveBackdropPollIntervalMs" !in members,
        )
        val fields = PlaybackStartPolicy::class.java.declaredFields.map { it.name }
        assertTrue("LIVE_BACKDROP_POLL_MS 应已退役", "LIVE_BACKDROP_POLL_MS" !in fields)
        assertTrue("LIVE_BACKDROP_FAST_POLL_MS 应已退役", "LIVE_BACKDROP_FAST_POLL_MS" !in fields)
    }

    // ---------------- ④ 列内占位模糊底衬：仅手动旋转保留 ----------------

    @Test
    fun `占位模糊底衬_常规形态删除`() {
        // FIT：外层全屏底衬同图同拍透上来（列内层退场=口径差换血根因）；
        // ZOOM：封面 Crop 铺满整列本就不可见（顺带省一次模糊绘制）
        assertFalse(PlaybackStartPolicy.keepPlaceholderBackdrop(rotatedOdd = false))
    }

    @Test
    fun `占位模糊底衬_手动旋转保留`() {
        // 外层底衬被显式 CLEAR_NULL 清屏、letterbox 无图可透——列内模糊层兜底
        assertTrue(PlaybackStartPolicy.keepPlaceholderBackdrop(rotatedOdd = true))
    }
}
