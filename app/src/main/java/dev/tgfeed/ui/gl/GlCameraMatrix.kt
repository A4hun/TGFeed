package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.TasteSpace
import kotlin.math.cos
import kotlin.math.sin

/**
 * R277 GL 相机矩阵（**纯 Kotlin 零 android 依赖**——真球档（r≥30px 恒星 UV 球）
 * 的 view/projection 矩阵推导件）。
 *
 * 与 Canvas 路径的等价锚（单测不变式）：对任意世界点，本矩阵变换到 NDC 后与
 * [TasteSpace.project] 的屏像素满足 `ndcX = 2·sx/w − 1`、`ndcY = 1 − 2·sy/h`——
 * 即 GL 真球与 CPU 投影的点/线/四边形在同一屏位（真球试点不与点云层错位）。
 *
 * 约定（与 TasteSpace 同坐标系）：世界系 Y 向上；相机系 = 右 r / 上 u / 前 f
 * （深度=沿前向正值）；NDC y 向上（GL 惯例——project 的屏 y 向下已含翻转）。
 * 帧零分配纪律：全部函数写调用方数组，零临时分配。
 */
internal object GlCameraMatrix {

    /** 远裁面（世界单位）：天幕方向星锚 9000 距离——余量到 12000 */
    const val FAR = 12000f

    /**
     * view 矩阵（列主序写 [out] 长 16）：world→camera：
     * `p_cam = (dot(d,r), dot(d,u), dot(d,f))`，d=world−camPos。
     * 基向量为 TasteSpace.basis 同式内联展开（帧零分配——不调带暂存分配的口）：
     * f=(sinYaw·cosPitch, sinPitch, cosYaw·cosPitch)；r=(cosYaw,0,−sinYaw)；u=f×r。
     * 列主序元素排布：m[col*4+row]。
     */
    fun viewOf(cam: TasteSpace.SpaceCam, out: FloatArray) {
        val yaw = Math.toRadians(cam.yawDeg.toDouble())
        val pitch = Math.toRadians(cam.pitchDeg.toDouble())
        val sy = sin(yaw).toFloat()
        val cy = cos(yaw).toFloat()
        val sp = sin(pitch).toFloat()
        val cp = cos(pitch).toFloat()
        val fx = sy * cp
        val fy = sp
        val fz = cy * cp
        val rx = cy
        val rz = -sy
        // u = f × r
        val ux = fy * rz - fz * 0f
        val uy = fz * rx - fx * rz
        val uz = fx * 0f - fy * rx
        // 行 0 = r^T，行 1 = u^T，行 2 = f^T；平移列 = −dot(轴, camPos)
        out[0] = rx; out[4] = 0f; out[8] = rz; out[12] = -(rx * cam.px + rz * cam.pz)
        out[1] = ux; out[5] = uy; out[9] = uz; out[13] = -(ux * cam.px + uy * cam.py + uz * cam.pz)
        out[2] = fx; out[6] = fy; out[10] = fz; out[14] = -(fx * cam.px + fy * cam.py + fz * cam.pz)
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
    }

    /**
     * projection 矩阵（列主序写 [out] 长 16）：camera→clip：
     * `x_clip = 2·focal/w · rx`，`y_clip = 2·focal/h · uy`，`w_clip = depth`（透视
     * 除法在 GPU）——除完即 `ndcX = rx·focal/(depth·w/2)`，与 TasteSpace.project
     * 的 `sx = w/2 + rx·focal/depth` 经 `ndcX = 2·sx/w − 1` 逐位同式。
     * z 映射 [NEAR, FAR]→[−1,1]（P0 深度测试关闭，z 仅作完备性/未来深度件）。
     */
    fun projectionOf(w: Float, h: Float, lens: Float, out: FloatArray) {
        val focal = TasteSpace.focalOf(w, h) * lens
        val n = TasteSpace.NEAR
        val f = FAR
        out.fill(0f)
        out[0] = 2f * focal / w
        out[5] = 2f * focal / h
        // depth 正向前（相机系 +z=前方）：z_ndc = A + B/depth；depth=n→−1，depth=f→+1
        out[10] = (f + n) / (f - n)
        out[14] = -2f * f * n / (f - n)
        out[11] = 1f // w_clip = depth
    }

    /** a × b 复合（列主序 4×4——out = a·b；三个数组两两不得同引用） */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
        val tmp = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var s = 0f
                for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
                tmp[col * 4 + row] = s
            }
        }
        System.arraycopy(tmp, 0, out, 0, 16)
    }

    /** 便捷口：相机+屏尺寸 → 复合 viewProjection（列主序写 [out] 长 16；两暂存由调用方供） */
    fun viewProjectionOf(
        cam: TasteSpace.SpaceCam,
        w: Float,
        h: Float,
        lens: Float,
        out: FloatArray,
        scratchV: FloatArray,
        scratchP: FloatArray,
    ) {
        viewOf(cam, scratchV)
        projectionOf(w, h, lens, scratchP)
        multiply(scratchP, scratchV, out)
    }
}
