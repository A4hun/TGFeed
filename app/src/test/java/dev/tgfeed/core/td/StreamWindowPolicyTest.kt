package dev.tgfeed.core.td

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldPreemptDownloadWindow]（R166 窗口抢占闸纯函数）的行为单测：
 * 闸的使命=「seek 跳变必插队、顺序推进不搅局」——TDLib 同文件只有一个下载窗口
 * 锚点，跳变查询与在途属主裸竞速是用户实锤「必须整个视频下载完才能快进」的根因。
 * 锚定五类口径：完成态免抢、兜底让位、覆盖内/紧贴前缘免抢（顺序泵块）、
 * 前向/后向跳变必抢（seek 与 moov 尾探针）。
 */
class StreamWindowPolicyTest {

    @Test
    fun `文件已完成免抢_全文件可读无需插队`() {
        // 整载完成 → isDownloadingCompleted → 任何 off 都直接认领，取消属主毫无意义
        assertTrue(!shouldPreemptDownloadWindow(0, 0, 100, completed = true, inFallback = false))
        assertTrue(!shouldPreemptDownloadWindow(90, 0, 5, completed = true, inFallback = false))
    }

    @Test
    fun `整下兜底表内让位_兜底整载优先不再互杀`() {
        // 分段持续失败才进的兜底：泵转轮询推水位，段查询前的抢占闸也见牌让位
        assertFalse(shouldPreemptDownloadWindow(90, 0, 5, completed = false, inFallback = true))
    }

    @Test
    fun `覆盖内与紧贴前缘免抢_顺序泵块与在途下载同锚合并`() {
        // 顺序泵块：off 落在 [winStart, winEnd]（含两端）都算连续——覆盖内直接命中，
        // 紧贴前缘（off == winEnd）是下一顺序块的自然衔接，取消属主=白扔一轮取消/重握手
        assertFalse(shouldPreemptDownloadWindow(0, 0, 512, completed = false, inFallback = false))
        assertFalse(shouldPreemptDownloadWindow(512, 0, 512, completed = false, inFallback = false))
        assertFalse(shouldPreemptDownloadWindow(256, 0, 512, completed = false, inFallback = false))
        // 锚非 0 的窗口（seek 后重锚的窗）：同理
        assertFalse(shouldPreemptDownloadWindow(9_000_000, 9_000_000, 9_002_048, completed = false, inFallback = false))
        // off 恰在窗口锚点（后向 seek 回窗口起点）：覆盖内
        assertFalse(shouldPreemptDownloadWindow(9_000_000, 9_000_000, 9_050_000, completed = false, inFallback = false))
    }

    @Test
    fun `前向跳变必抢_seek到未下载区是插队核心场景`() {
        // 用户实锤主场景：窗口在 [0, 5MB)，seek 到 90% 位置——必须先取消在途整载
        // （WiFi 预载/残留整载），本段查询才能立即重锚
        assertTrue(shouldPreemptDownloadWindow(90_000_000, 0, 5_242_880, completed = false, inFallback = false))
        // 紧贴前缘之外一格（off == winEnd+1）：不连续即跳变
        assertTrue(shouldPreemptDownloadWindow(513, 0, 512, completed = false, inFallback = false))
    }

    @Test
    fun `后向跳变必抢_seek回窗口之前的洞区`() {
        // 窗口重锚到尾部后 seek 回头部空洞（moov 在头但跳着看）：洞区必须重新拉，
        // 同样要先取消属主（窗口锚在别处）
        assertTrue(shouldPreemptDownloadWindow(1_000, 90_000_000, 90_512_000, completed = false, inFallback = false))
    }

    @Test
    fun `moov尾探针场景_起点在文件尾必抢`() {
        // R145 尾探针：连接起点在文件末尾 1MB 内，窗口在头——异锚必抢（R72「两边
        // 窗口全灭」的老互杀现场，抢占闸+fileMutex 串行后探针确定性插队）
        val total = 100_000_000L
        val tailStart = total - 256 * 1024
        assertTrue(shouldPreemptDownloadWindow(tailStart, 0, 5_242_880, completed = false, inFallback = false))
    }

    @Test
    fun `空窗口零前缀_一切off都算跳变`() {
        // 窗口尚未推进（prefix=0，锚 0）：除 off=0（同锚起点）外都抢；
        // off=0 与 winStart 重合=连续，免抢（起播首块不空耗取消）
        assertFalse(shouldPreemptDownloadWindow(0, 0, 0, completed = false, inFallback = false))
        assertTrue(shouldPreemptDownloadWindow(1, 0, 0, completed = false, inFallback = false))
    }
}
