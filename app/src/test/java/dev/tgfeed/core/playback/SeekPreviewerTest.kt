package dev.tgfeed.core.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R243 拖拽帧单飞通道：源形态预览资格判定（isPreviewableSource）单测。
 * 口径单一真源：回环流/本地文件可预览（拖拽帧走 retriever），远端 http 不可
 * （降级纯时间气泡）——open() 与 requestDragFrame 两路共用，防口径漂移。
 */
class SeekPreviewerTest {

    @Test
    fun `回环流各形态均可预览`() {
        assertTrue(isPreviewableSource("http://127.0.0.1:35757/v/1255"))
        assertTrue(isPreviewableSource("https://127.0.0.1:35757/v/1255"))
        assertTrue(isPreviewableSource("http://localhost:35757/v/1255"))
        assertTrue(isPreviewableSource("https://localhost:35757/v/1255"))
    }

    @Test
    fun `远端http一律不可预览`() {
        assertFalse(isPreviewableSource("http://example.com/video.mp4"))
        assertFalse(isPreviewableSource("https://cdn.example.com/video.mp4"))
        // 回环 IP 换端口仍认；非回环 IP 不认
        assertTrue(isPreviewableSource("http://127.0.0.1:1/v/1"))
        assertFalse(isPreviewableSource("http://192.0.2.151:39873/v/1"))
    }

    @Test
    fun `存在的本地文件与file前缀可预览`() {
        val f = File.createTempFile("r243", ".mp4")
        try {
            assertTrue(isPreviewableSource(f.absolutePath))
            assertTrue(isPreviewableSource("file://" + f.absolutePath))
            // 不存在的路径不可（file:// 前缀剥离后按裸路径判）
            assertFalse(isPreviewableSource(f.absolutePath + ".missing"))
        } finally {
            f.delete()
        }
    }
}
