package dev.tgfeed.ui.gl

import android.util.Log
import android.view.Choreographer

/**
 * R277 帧率监管件（任务书口径：Choreographer 帧回调+掉帧计数；60fps 铁律）：
 * 主线程 vsync 节拍实测——星图页在组合期间挂帧回调，统计窗口（5s）滚报：
 * fps/掉帧率（帧间隔 >1.5×vsync 周期=掉一帧以上）。GL 侧渲染帧统计由
 * TasteGlRenderer 自计（渲染线程口径）——两口径并行出数（gfxinfo 双轨期同构）。
 *
 * 零分配纪律：回调自续注册零闭包分配（帧回调对象常驻）；统计窗纯标量。
 */
internal class GlFrameMonitor(
    private val engineTag: String, // "GL" / "诊断"（探针行标识——R345 GL-only 后降级态口径）
) {

    private var running = false
    private var lastNs = 0L
    private var winStartNs = 0L
    private var frames = 0
    private var janky = 0
    private var minDtNs = Long.MAX_VALUE // 窗内最小帧间隔=vsync 周期实测估值
    private var vsyncNs = 16_666_666L // 首窗未测出前按 60Hz 口径

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNs != 0L) {
                val dt = frameTimeNanos - lastNs
                frames++
                if (dt in 1_000_000 until minDtNs) minDtNs = dt // 1ms 下限滤噪声
                // 掉帧=帧间隔 >1.5×vsync 周期（vsync 实测自适应 120Hz 屏）
                if (dt > vsyncNs * 3 / 2) janky++
            }
            lastNs = frameTimeNanos
            if (winStartNs == 0L) winStartNs = frameTimeNanos
            val winElapsed = frameTimeNanos - winStartNs
            if (winElapsed >= 5_000_000_000L) {
                val sec = winElapsed / 1_000_000_000f
                Log.i(
                    "TasteMapX",
                    "R277 帧率[$engineTag]: %.1ffps 掉帧=%.1f%%（%d/%d）vsync=%.1fms".format(
                        frames / sec, janky * 100f / frames.coerceAtLeast(1), janky, frames, vsyncNs / 1e6f,
                    ),
                )
                if (minDtNs != Long.MAX_VALUE) vsyncNs = minDtNs.coerceIn(4_000_000L, 33_000_000L)
                winStartNs = frameTimeNanos
                frames = 0
                janky = 0
                minDtNs = Long.MAX_VALUE
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 挂监管（星图页进入时；重复调幂等） */
    fun start() {
        if (running) return
        running = true
        lastNs = 0L
        winStartNs = 0L
        frames = 0
        janky = 0
        minDtNs = Long.MAX_VALUE
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** 摘监管（星图页离开时——零残留回调） */
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
