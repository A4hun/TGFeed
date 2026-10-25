package dev.tgfeed.ui.components

import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.tgfeed.ui.frameMirrorScaleX
import dev.tgfeed.ui.frameMirrorScaleY

/**
 * 画面姿态层变换共享件（R227，方向修复的渲染侧唯一实现）：
 * 「手动旋转 90°/270° 的宽高互换布局 + 镜像符号跟旋转态换轴」的完整组合，
 * 供分屏格（GridVideoCell 四处媒体层）直接挂用；VideoSurface 播放层因需与
 * R174b 缩放/平移合并在同一 graphicsLayer（draw-phase 读 State 零重组红线），
 * 消费同源的 [frameRotationLayout] + [frameMirrorScaleX]/[frameMirrorScaleY]
 * 纯函数拼自己的层——布局互换与换轴裁决仍是同一份逻辑（纪律§一：一处实现，
 * 两宿主消费，不许各写一份）。
 *
 * 换轴原理（R218 方向 bug 根因，详见 FrameAdjustPolicy 头注释）：Compose
 * graphicsLayer 点变换序 = T·R·S——scale 在**局部坐标系**生效后才被 rotationZ
 * 旋到屏幕系；rotatedOdd（90°/270°）时局部 X 轴已被旋到屏幕竖向，局部
 * scaleX=-1 落到屏幕是**上下翻**——修法=镜像符号换到局部 scaleY（局部 Y 被旋到
 * 屏幕横轴，等效屏幕左右翻），恒等式：**镜像在屏幕坐标系 = 左右翻转**。
 */

/**
 * 旋转宽高互换布局（VideoSurface R174 手动旋转搬运共享化，字节等价）：
 * rotatedOdd 时本层按「屏幕横过来」的尺寸测量（宽高互换约束），内容在横过来
 * 的盒子里 FIT 装帧，居中放置——配合 graphicsLayer 的 rotationZ 旋回后恰好
 * 满宽/满高，天然适配容器，无需任何缩放系数。0°/180° 走恒等布局。
 */
internal fun Modifier.frameRotationLayout(rotationDeg: Int): Modifier = layout { measurable, constraints ->
    if (rotationDeg % 180 != 0) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val placeable = measurable.measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = h, maxHeight = w),
        )
        layout(w, h) {
            placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
        }
    } else {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * 完整画面姿态变换（分屏格四处媒体层用）：宽高互换布局 + rotationZ 旋转 +
 * 换轴镜像（FrameAdjustPolicy 纯函数裁决符号）。挂媒体层（画面族）——
 * 「只转/翻画面不碰交互层」：文本/转圈/菜单等兄弟浮层不挂本件。
 * 缩放/平移不需要的宿主直接用；需要合并缩放平移的宿主（VideoSurface）
 * 用上面的零件拼自己的单层。
 */
internal fun Modifier.frameTransform(rotationDeg: Int, mirrored: Boolean): Modifier =
    frameRotationLayout(rotationDeg)
        .graphicsLayer {
            rotationZ = rotationDeg.toFloat()
            scaleX = frameMirrorScaleX(rotationDeg, mirrored)
            scaleY = frameMirrorScaleY(rotationDeg, mirrored)
        }
