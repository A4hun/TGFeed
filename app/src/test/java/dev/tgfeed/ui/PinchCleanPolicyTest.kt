package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R174 捏合清屏纯模型单测（映射曲线 + 滞回矩阵 + 状态机全事件枚举）。
 * 规范锚：design-proposal.md §3.2（连续线性映射、三退通道、滞回反向）、
 * §3.5（与全屏互斥）、§3.3（留守清单——进度条不在渐隐组，行为语义在页侧）。
 */
class PinchCleanPolicyTest {

    // ---------------- ① chrome α 线性映射（提案 §3.2 原式） ----------------

    @Test
    fun `α映射_关键点_1x全显_115x半隐_13x全隐`() {
        assertEquals(1.0f, PinchCleanPolicy.chromeAlphaFor(1.0f), 1e-6f)
        assertEquals(0.5f, PinchCleanPolicy.chromeAlphaFor(1.15f), 1e-6f)
        assertEquals(0.0f, PinchCleanPolicy.chromeAlphaFor(1.3f), 1e-6f)
    }

    @Test
    fun `α映射_1x以下与13x以上钳位`() {
        // <1（弹簧回弹过冲/异常值）钳 1；>1.3 恒 0（4x 也全隐）
        assertEquals(1.0f, PinchCleanPolicy.chromeAlphaFor(0.92f), 1e-6f)
        assertEquals(0.0f, PinchCleanPolicy.chromeAlphaFor(1.5f), 1e-6f)
        assertEquals(0.0f, PinchCleanPolicy.chromeAlphaFor(4.0f), 1e-6f)
    }

    @Test
    fun `α映射_线性中点连续无跳变`() {
        // 1.0→1.3 全程线性：斜率恒 −1/0.3，任意中点不脱离直线
        for (i in 0..6) {
            val z = 1.0f + 0.05f * i
            val expected = 1.0f - (z - 1.0f) / 0.3f
            assertEquals(expected, PinchCleanPolicy.chromeAlphaFor(z), 1e-6f)
        }
    }

    // ---------------- ② cleanMode 滞回（进 ≥1.3 / 退 ≤1.05 / 中间保持） ----------------

    @Test
    fun `滞回_进入沿_130起生效_129不触发`() {
        assertFalse(PinchCleanPolicy.cleanModeFor(1.29f, current = false))
        assertTrue(PinchCleanPolicy.cleanModeFor(1.3f, current = false))
    }

    @Test
    fun `滞回_退出沿_105起复位_106仍保持`() {
        assertTrue(PinchCleanPolicy.cleanModeFor(1.06f, current = true))
        assertFalse(PinchCleanPolicy.cleanModeFor(1.05f, current = true))
        assertFalse(PinchCleanPolicy.cleanModeFor(1.05f, current = false))
    }

    @Test
    fun `滞回_中间带保持现值_不存在非法中间态`() {
        // 1.06~1.29 松手停在中间：clean 维持、α 停在曲线对应值（§3.2 中间态松手行）
        assertTrue(PinchCleanPolicy.cleanModeFor(1.2f, current = true))
        assertFalse(PinchCleanPolicy.cleanModeFor(1.2f, current = false))
    }

    // ---------------- ③ 状态机：捏合正反向全程走查 ----------------

    @Test
    fun `状态机_捏合进清屏_α跟手_滞回全程`() {
        var s = PinchCleanUiState()
        // 1.0 → 1.2 → 1.3（clean 生效、α 归零）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 1.2f))
        assertFalse(s.cleanMode)
        assertEquals(0.3333f, s.chromeAlpha, 1e-3f)
        assertFalse(s.alphaAnimated) // 跟手 snap，无时长
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.2f, 1.3f))
        assertTrue(s.cleanMode)
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
        // 1.3 → 2.0（clean 保持、α 恒 0）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.3f, 2.0f))
        assertTrue(s.cleanMode)
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
        // 反向 2.0 → 1.2（α 沿同曲线渐回、clean 保持）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(2.0f, 1.2f))
        assertTrue(s.cleanMode)
        assertEquals(0.3333f, s.chromeAlpha, 1e-3f)
        // 1.2 → 1.05（clean 复位、α≈0.83）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.2f, 1.05f))
        assertFalse(s.cleanMode)
        assertEquals(0.8333f, s.chromeAlpha, 1e-3f)
        // 1.05 → 1.0（α 全显）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.05f, 1.0f))
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
    }

    @Test
    fun `状态机_教学跨沿_首过115触发_回落再过不重触发`() {
        var s = PinchCleanUiState()
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.1f, 1.16f))
        assertTrue(s.teachFired)
        // 回落到 1.1 再升过 1.15：同页生命周期内只教一次（AppPrefs flag 再把一次
        // 收敛成全局一次）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.16f, 1.1f))
        assertTrue(s.teachFired)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.1f, 1.2f))
        assertTrue(s.teachFired)
        // 起点已在 1.15 以上（面板清屏后无缝续捏的角落）：跨沿未发生不触发
        var s2 = PinchCleanUiState()
        s2 = PinchCleanMachine.reduce(s2, PinchCleanEvent.ZoomChanged(1.2f, 1.25f))
        assertFalse(s2.teachFired)
    }

    @Test
    fun `状态机_教学跨沿_从下方缓慢逼近多次小步只算一次`() {
        var s = PinchCleanUiState()
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 1.05f))
        assertFalse(s.teachFired)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.05f, 1.1f))
        assertFalse(s.teachFired)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.1f, 1.15f))
        assertTrue(s.teachFired)
    }

    // ---------------- ④ 状态机：三条退通道 + 面板入口 + 滑走复位 ----------------

    @Test
    fun `状态机_单击退通道_恢复chrome缩放语义留给渲染侧`() {
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f, alphaAnimated = false)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f))
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertTrue(s.alphaAnimated) // 离散退场走 chrome 进出通用 220ms
        assertEquals(1.4f, s.fadeOrigin, 1e-6f) // 基线重臂到当前缩放（反跳变，见下组）
    }

    @Test
    fun `状态机_双击退通道_目标与单击一致_zoom复位是渲染侧职责`() {
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.FullReset)
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertTrue(s.alphaAnimated)
    }

    @Test
    fun `状态机_面板清屏行_离散进清屏_走220ms渐隐_基线落到当前缩放`() {
        var s = PinchCleanUiState()
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.PanelCleanEntered(zoom = 1.0f))
        assertTrue(s.cleanMode)
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
        assertTrue(s.alphaAnimated)
        // 基线下移 0.3：曲线零点恰在进入时的 zoom 上（α 已与 zoom 解耦，靠基线缝合）
        assertEquals(0.7f, s.fadeOrigin, 1e-6f)
    }

    @Test
    fun `状态机_面板清屏后外捏_α保持零_不回跳曲线值`() {
        // 面板入口解耦角：不移基线的话首个捏合事件会把 α 从 0 拉回 0.83（跳变），
        // 且 cleanMode 被退出带误翻（可见 chrome 弹回再藏回去）。基线缝合后：
        // 外捏全程 α=0、clean 稳定——清屏里放大看局部，天经地义
        var s = PinchCleanMachine.reduce(PinchCleanUiState(), PinchCleanEvent.PanelCleanEntered(zoom = 1.0f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 1.05f))
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
        assertTrue(s.cleanMode)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.05f, 1.3f))
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
        assertTrue(s.cleanMode)
    }

    @Test
    fun `状态机_面板清屏后缩小_α保持隐没_退出只走显式退通道`() {
        // 面板源清屏（基线 0.7）的语义 = 显式开关：曲线零点区延伸到 zoom 下界，
        // 缩回 1x 不会自动回 chrome（手势源清屏才有「捏合回 1x 渐回」行为——
        // 两种来源语义分家：手势是临时态、面板是模式态，退出都走单击/返回键）
        var s = PinchCleanMachine.reduce(PinchCleanUiState(), PinchCleanEvent.PanelCleanEntered(zoom = 1.0f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 1.3f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.3f, 1.15f))
        assertEquals(0.0f, s.chromeAlpha, 1e-6f) // 1.15 > 基线+0.3=1.0：曲线仍在零点区
        assertTrue(s.cleanMode)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.15f, 1.0f))
        assertEquals(0.0f, s.chromeAlpha, 1e-6f) // 缩回 1x 也不回（z=1.0 恰在零点）
        assertTrue(s.cleanMode) // 退出带不可达：面板清屏的退通道 = 单击/返回键
    }

    @Test
    fun `状态机_滑走复位_全清零且snap_教学计数随页复位`() {
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f, teachFired = true, fullscreen = true)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.PageLeft)
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertFalse(s.alphaAnimated)
        assertFalse(s.teachFired)
        assertFalse(s.fullscreen)
    }

    // ---------------- ⑤ 状态机：与全屏互斥（§3.5） ----------------

    @Test
    fun `状态机_进全屏自动退清屏`() {
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.FullscreenChanged(true))
        assertTrue(s.fullscreen)
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
    }

    @Test
    fun `状态机_全屏内pinch只缩放_α与clean与教学全冻结`() {
        // §3.5：全屏内 pinch 仍可缩放画面（几何层叠加），但 chrome 已全隐、
        // 不许让 cleanMode 生效翻转 tap 语义
        var s = PinchCleanUiState(fullscreen = true)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 1.6f))
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertFalse(s.teachFired)
    }

    @Test
    fun `状态机_退全屏_chrome本就全显_状态原样`() {
        var s = PinchCleanUiState(fullscreen = true, cleanMode = false, chromeAlpha = 1f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.FullscreenChanged(false))
        assertFalse(s.fullscreen)
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
    }

    // ---------------- ⑥ 基线重臂：restore 后再捏合的反跳变（提案红线工程化） ----------------

    @Test
    fun `状态机_restore后捏合外放_从当前缩放连续渐隐_无跳变`() {
        // §3.2 退出①（缩放保留）后用户再捏合：α 从 1 起随新手势连续衰减，
        // 不许瞬间拉回绝对曲线（1.4x 处 α=0 的悬崖）——「离散式瞬间全藏会有
        // 跳变惊吓」红线在 restore 角落的执法
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f))
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.4f, 1.55f))
        assertEquals(0.5f, s.chromeAlpha, 1e-3f)
        assertFalse(s.cleanMode) // 1.55 < 1.4+0.3，未到清屏
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.55f, 1.7f))
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
        assertTrue(s.cleanMode) // 1.7 ≥ 1.4+0.3 回到清屏态
    }

    @Test
    fun `状态机_restore后捏回缩_方向语义正确_chrome不被反向清场`() {
        // 绝对曲线的死穴：restore@1.4 后缩回（1.4→1.3）会把刚恢复的 chrome
        // 瞬间全藏（α(1.3)=0）——方向语义全反（用户在缩小，界面该回来）。
        // 基线重臂后：缩回方向 α 钳在 1，chrome 稳如泰山
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.3f, 1.4f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.4f, 1.2f))
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertFalse(s.cleanMode)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.2f, 1.0f))
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertFalse(s.cleanMode)
    }

    @Test
    fun `状态机_完全归1x后基线重臂_下一轮回到提案绝对曲线`() {
        // 非清屏态 zoom 归 1：基线归 1，下一轮捏合从 §3.2 原式起算（1.15→α0.5）
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.4f, 1.0f))
        assertEquals(1f, s.fadeOrigin, 1e-6f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 1.15f))
        assertEquals(0.5f, s.chromeAlpha, 1e-3f)
    }

    @Test
    fun `状态机_清屏态zoom归1_基线不归位_α静止合法`() {
        // z≤1 的基线归位只对非清屏态生效：清屏态（面板入口，基线 0.7）把 zoom 捏回
        // 1x 松手——若归位到 1，下个外捏事件 α 会被拉上曲线（0→0.33+ 跳变）；
        // 保持 0.7 则 α 恒 0，静止合法
        var s = PinchCleanMachine.reduce(PinchCleanUiState(), PinchCleanEvent.PanelCleanEntered(zoom = 1.0f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.3f, 1.0f))
        assertTrue(s.cleanMode)
        assertEquals(0.0f, s.chromeAlpha, 1e-6f) // 曲线在 z<0.7+0.3=1.0 处本就到 0
        assertEquals(0.7f, s.fadeOrigin, 1e-6f) // 不归位
        // 但 α 已到 0 与 zoom=1 的组合：恢复 chrome 只能走退通道（tap）
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.0f))
        assertFalse(s.cleanMode)
        assertEquals(1f, s.fadeOrigin, 1e-6f)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
    }

    @Test
    fun `状态机_退通道幂等_重复派发无二次漂移`() {
        // 退通道只翻 clean/α/基线；重复派发（如单击与返回键背靠背）结果稳定，
        // 中间态松手后无新事件状态静止（chrome 停在当前值，§3.2 中间态松手行）
        val once = PinchCleanMachine.reduce(
            PinchCleanUiState(cleanMode = true, chromeAlpha = 0f),
            PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f),
        )
        val twice = PinchCleanMachine.reduce(once, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f))
        assertEquals(once, twice)
    }

    @Test
    fun `状态机_缩放回到100_α全显clean复位`() {
        // 捏合回 1x 整：α 曲线端点、滞回退出沿同时命中（滑走/双击复位的目标态）
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.4f, 1.0f))
        assertFalse(s.cleanMode)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
    }

    // ---------------- ⑦ R174b 自由缩放：<1x 区间的机器行为（红线：α 曲线 ≥1.0 侧不动） ----------------

    @Test
    fun `自由缩放_软界常量_025至4_单一来源`() {
        // 软界（超界钳值不弹回）：下界 0.25（去 1x 地板）、上界 4（原口径继承）
        assertEquals(0.25f, PinchCleanPolicy.ZOOM_MIN_SCALE, 1e-6f)
        assertEquals(4f, PinchCleanPolicy.ZOOM_MAX_SCALE, 1e-6f)
    }

    @Test
    fun `自由缩放_缩小全程α钳1_清屏映射红线不动`() {
        // <1x：α 曲线在 ≥1.0 侧恒钳 1（缩小不清屏——清屏只挂外放 ≥1.0 段，
        // 提案 §3.2 映射零变化）、cleanMode 维持 false、教学不触发（跨沿只认
        // 1.15 上行）
        var s = PinchCleanUiState()
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.0f, 0.8f))
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertFalse(s.cleanMode)
        assertFalse(s.teachFired)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(0.8f, 0.25f))
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        assertFalse(s.cleanMode)
        assertFalse(s.teachFired)
    }

    @Test
    fun `自由缩放_缩小后基线归1_再外放回提案绝对曲线`() {
        // 基线缝合的 <1x 路径：restore@1.4（基线重臂 1.4）后直接缩到 0.8——
        // newZoom≤1 且非清屏 → 基线归 1；随后 0.8→1.15 从绝对曲线起算
        //（α=0.5、教学首过触发），不残留 1.4 基线的错位曲线
        var s = PinchCleanUiState(cleanMode = true, chromeAlpha = 0f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ExitCleanKeepZoom(zoom = 1.4f))
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.4f, 0.8f))
        assertEquals(1f, s.fadeOrigin, 1e-6f)
        assertEquals(1.0f, s.chromeAlpha, 1e-6f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(0.8f, 1.15f))
        assertEquals(0.5f, s.chromeAlpha, 1e-3f)
        assertTrue(s.teachFired)
    }

    @Test
    fun `自由缩放_缩小再外放跨清屏沿_滞回矩阵照常`() {
        // 0.25→1.35：α 沿绝对曲线衰减、clean 在 1.3 精确翻转——<1x 区间只是
        // 「无事件的静止合法区」，回到 ≥1.0 段一切照旧（状态机对 zoom 符号无感知）
        var s = PinchCleanUiState()
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(0.25f, 1.2f))
        assertFalse(s.cleanMode)
        assertEquals(0.3333f, s.chromeAlpha, 1e-3f)
        s = PinchCleanMachine.reduce(s, PinchCleanEvent.ZoomChanged(1.2f, 1.35f))
        assertTrue(s.cleanMode)
        assertEquals(0.0f, s.chromeAlpha, 1e-6f)
    }

    // ---------------- ⑧ R194③ 自由平移夹域（夹「内容带」而不是「图层」） ----------------

    @Test
    fun `自由平移_内容等于视口_域宽0_跨1x连续`() {
        // s=1：内容恰铺满 → 域宽 0（offset 归零语义继承旧对称式，无跳变）
        assertEquals(0f, PinchCleanPolicy.freePanMaxOffsetAxis(1000f, 1000f), 1e-6f)
    }

    @Test
    fun `自由平移_缩小态_内容整框在窗内任意位可达贴角`() {
        // 内容 500 < 视口 1000：域宽 (1000−500)/2=250——内容边缘恰可触窗边（贴角），
        // 永不出屏（用户实测「拖不到屏幕各个角落」的修复口径：旧图层式会把
        // 内容锁在窗心 ±|s−1|·边/2）
        assertEquals(250f, PinchCleanPolicy.freePanMaxOffsetAxis(500f, 1000f), 1e-6f)
        assertEquals(375f, PinchCleanPolicy.freePanMaxOffsetAxis(250f, 1000f), 1e-6f)
    }

    @Test
    fun `自由平移_放大态_覆盖式加出屏让位_随超出量线性长出`() {
        // 内容 1200 > 视口 1000：基础覆盖域 (1200−1000)/2=100 + 让位 min(200, 250)=200
        // → 300；内容左缘 = 300+600−600=…实际可滑出窗缘 200px（窗可见 800=80%）
        assertEquals(300f, PinchCleanPolicy.freePanMaxOffsetAxis(1200f, 1000f), 1e-6f)
        // 轻微放大（1020）：基础 10 + 让位 min(20,250)=20 → 30——跨 1x 连续小域起步
        assertEquals(30f, PinchCleanPolicy.freePanMaxOffsetAxis(1020f, 1000f), 1e-6f)
    }

    @Test
    fun `自由平移_放大态_出屏让位25百分比封顶_保守界`() {
        // 内容 2000（超出 1000 > 25%·1000）：让位封顶 250 → 域 = 500+250=750；
        // 内容滑至极限时窗轴可见 = 1000−250=750 ≥ 75%（保守界：内容大部分在屏）
        assertEquals(750f, PinchCleanPolicy.freePanMaxOffsetAxis(2000f, 1000f), 1e-6f)
        // 巨幅放大（4000）同样封顶
        assertEquals(1750f, PinchCleanPolicy.freePanMaxOffsetAxis(4000f, 1000f), 1e-6f)
    }

    @Test
    fun `自由平移_域宽两段单调_谷底恰在内容等于视口`() {
        // 形状走查：内容 < 视口段域宽随内容增大递减（越小越自由）、> 视口段递增
        //（越大越可出屏），谷底 0 恰在内容=视口（=1x）处——跨 1x 无跳变无回缩段
        var c = 100f
        var prev = PinchCleanPolicy.freePanMaxOffsetAxis(c, 1000f)
        while (c < 1000f) {
            c += 100f
            val m = PinchCleanPolicy.freePanMaxOffsetAxis(c, 1000f)
            assertTrue("内容段 span=$c 域宽应递减：$m > $prev", m <= prev)
            prev = m
        }
        assertEquals(0f, prev, 1e-6f)
        while (c < 4000f) {
            c += 100f
            val m = PinchCleanPolicy.freePanMaxOffsetAxis(c, 1000f)
            assertTrue("放大段 span=$c 域宽应递增：$m < $prev", m >= prev)
            prev = m
        }
    }

    @Test
    fun `自由平移_视口非正_域宽0_防御`() {
        assertEquals(0f, PinchCleanPolicy.freePanMaxOffsetAxis(500f, 0f), 1e-6f)
        assertEquals(0f, PinchCleanPolicy.freePanMaxOffsetAxis(500f, -10f), 1e-6f)
    }

    @Test
    fun `内容带_宽内容_横带满宽竖按比`() {
        // aspect 1.78（16:9）入竖视口 1000×2000：带宽=1000（满宽），带高=1000/1.78
        val (w, h) = PinchCleanPolicy.fitContentSpan(1.7777f, 1000f, 2000f)
        assertEquals(1000f, w, 1e-3f)
        assertEquals(562.5f, h, 1f)
    }

    @Test
    fun `内容带_竖内容_竖带满高横按比`() {
        // aspect 0.5（竖片）入竖视口 1000×2000：带高=2000（满高），带宽=1000
        val (w, h) = PinchCleanPolicy.fitContentSpan(0.5f, 1000f, 2000f)
        assertEquals(2000f, h, 1e-6f)
        assertEquals(1000f, w, 1e-6f)
    }

    @Test
    fun `内容带_比非法_回退视口口径_旧夹域零回归兜底`() {
        // aspect ≤0（比未知/ZOOM 填满）：带宽=视口 → 域宽走「内容=视口」族（旧口径）
        val (w, h) = PinchCleanPolicy.fitContentSpan(0f, 1000f, 2000f)
        assertEquals(1000f, w, 1e-6f)
        assertEquals(2000f, h, 1e-6f)
        // 非正视口同样回退
        val (w2, h2) = PinchCleanPolicy.fitContentSpan(1.5f, 0f, 0f)
        assertEquals(0f, w2, 1e-6f)
        assertEquals(0f, h2, 1e-6f)
    }
}
