package dev.tgfeed.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import dev.tgfeed.ui.PinchCleanUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R174b 自由缩放三件套之三：悬浮复位标识判据（[PinchCleanHost.isTransformed]）。
 * scale≠1 或有平移 → chip 显示；复位（1x + 零偏移）→ 淡出。判据是纯状态读，
 * 手势层收尾吸附（|z−1|<ε 吸回 1x）保证浮点残值不残留，精确比较即成立。
 */
class PinchCleanHostTest {

    private fun host(
        zoom: Float = 1f,
        offset: Offset = Offset.Zero,
    ): PinchCleanHost {
        val zoomScale = mutableStateOf(zoom)
        val zoomOffset = mutableStateOf(offset)
        return PinchCleanHost(
            zoomScale = zoomScale,
            zoomOffset = zoomOffset,
            ui = mutableStateOf(PinchCleanUiState()),
            chromeAlpha = mutableStateOf(1f),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test
    fun `判据_复位态不显示`() {
        assertFalse(host(zoom = 1f).isTransformed)
    }

    @Test
    fun `判据_放大态显示`() {
        assertTrue(host(zoom = 2.6f).isTransformed)
    }

    @Test
    fun `判据_缩小态显示_去地板后_025x也是变换态`() {
        assertTrue(host(zoom = 0.25f).isTransformed)
        assertTrue(host(zoom = 0.99f).isTransformed)
    }

    @Test
    fun `判据_仅平移也显示`() {
        assertTrue(host(zoom = 1f, offset = Offset(30f, 0f)).isTransformed)
    }

    @Test
    fun `判据_弹簧终值_恰好1x加零偏移_判据翻假`() {
        // 复位弹簧收尾逐字段写整值（1f/Offset.Zero）：chip 在弹簧落定同帧消失
        val h = host(zoom = 0.4f, offset = Offset(60f, 40f))
        assertTrue(h.isTransformed)
        h.zoomScale.value = 1f
        h.zoomOffset.value = Offset.Zero
        assertFalse(h.isTransformed)
    }
}
