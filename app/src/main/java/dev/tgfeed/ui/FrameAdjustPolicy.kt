package dev.tgfeed.ui

/**
 * R227 画面调整（旋转 + 镜像）策略纯函数：
 * 把「旋转步进 / 镜像符号跟旋转态换轴 / 枢纽菜单行文案」的决策从 Composable 里抽成
 * 纯函数，与 ShareHubPolicy/MediaRailPolicy 同一族（UI 策略纯函数 + 单测守护）。
 *
 * 背景：R218 镜像翻转在手动旋转 90°/270° 后方向反了——Compose graphicsLayer 的点
 * 变换序 = T·R·S（scale 先在**局部坐标系**生效、再被 rotationZ 旋到屏幕系）：
 *  - 0°/180°：局部 X 轴 = 屏幕横轴 → 局部 scaleX=-1 = 屏幕**左右**翻 ✓；
 *  - 90°/270°（rotatedOdd）：局部 X 轴已被旋到屏幕**竖向** → 局部 scaleX=-1 落到
 *    屏幕上是**上下**翻（R218 方向 bug 根因，用户实锤「镜像搞成了上下翻转」）。
 *
 * 修法口径（任务书）：镜像在**屏幕坐标系**恒=左右翻转——rotatedOdd 时镜像符号换轴
 * 到局部 scaleY（局部 Y 轴被旋到屏幕横轴，局部 scaleY=-1 = 屏幕左右翻）。本文件是
 * 该换轴裁决的**唯一来源**（纪律§一：VideoSurface 播放层与分屏格 FrameTransform
 * 两处消费一处实现，不许各写一份）。
 *
 * 几何推导备忘（供翻案复核）：局部点 (a,b)，rotationZ=90°（顺时针）时局部→屏幕映射
 * 为 (-b,a)：内容原右侧 (10,0) 镜像后 (-10,0) → 屏幕 (0,-10)=屏上方，而旋转态单看
 * 内容右侧应在屏下——即 90° 下局部 scaleX 翻转 = 屏幕纵向互换（上下翻）；同理局部
 * scaleY 翻转时内容原上缘 (0,-10) → 镜像 (0,10) → 屏 (-10,0)=屏左，而旋转态单看应在
 * 屏右——即 90° 下局部 scaleY 翻转 = 屏幕横向互换（左右翻）✓。
 */

/** 枢纽菜单行（四选项统一枚举；口径与 FrameAdjustHubSheet 渲染序一致） */
enum class FrameAdjustRow { ROTATE_LEFT, ROTATE_RIGHT, MIRROR, RESET }

/**
 * 角度归一化到 [0, 360)：存储与 UI 全链统一口径（-90 → 270，450 → 90），
 * 防外部传入未归一值（右轨 +90 步进链路历史上产生过 360/负角）。
 */
fun frameAdjustNormalized(deg: Int): Int = ((deg % 360) + 360) % 360

/**
 * 旋转步进：deltaDeg 取 +90（向右/顺时针）或 -90（向左/逆时针），结果归一 [0,360)。
 * 向左从 0° 起 = 270°（逆时针一圈语义，不特判）。
 */
fun rotateFrame(deg: Int, deltaDeg: Int): Int = frameAdjustNormalized(deg + deltaDeg)

/** 画面是否处于非默认姿态（旋转非 0 或已镜像）——面板格红点角标/恢复默认行可用性判据 */
fun isFrameAdjusted(rotationDeg: Int, mirrored: Boolean): Boolean =
    frameAdjustNormalized(rotationDeg) != 0 || mirrored

/**
 * 镜像符号（X 轴）：仅当「已镜像且旋转为偶数档（0/180°）」时为 -1——
 * 此时局部 X 轴 = 屏幕横轴，局部 scaleX=-1 = 屏幕左右翻。
 * rotatedOdd 时镜像符号换轴到 [frameMirrorScaleY]（本轴恒 +1）。
 */
fun frameMirrorScaleX(rotationDeg: Int, mirrored: Boolean): Float =
    if (mirrored && frameAdjustNormalized(rotationDeg) % 180 == 0) -1f else 1f

/**
 * 镜像符号（Y 轴）：仅当「已镜像且旋转为奇数档（90/270°）」时为 -1——
 * 此时局部 Y 轴已被旋到屏幕横轴，局部 scaleY=-1 = 屏幕左右翻（方向修复核心）。
 * 偶数档时本轴恒 +1（镜像符号在 X 轴上）。
 */
fun frameMirrorScaleY(rotationDeg: Int, mirrored: Boolean): Float =
    if (mirrored && frameAdjustNormalized(rotationDeg) % 180 != 0) -1f else 1f

/** 当前姿态的如实描述（枢纽行/格子的当前态指示共用一处口径） */
fun frameAdjustStateText(rotationDeg: Int, mirrored: Boolean): String {
    val deg = frameAdjustNormalized(rotationDeg)
    return when {
        deg == 0 && !mirrored -> "未调整"
        deg != 0 && mirrored -> "旋转 ${deg}° · 已镜像"
        deg != 0 -> "旋转 ${deg}°"
        else -> "已镜像"
    }
}

/**
 * 枢纽菜单行副文案（当前态指示）：每行说明自己的动作语义 + 全局当前态。
 * 恢复默认行在已是默认姿态时给「已是默认姿态」——行可用性（禁用）由
 * [frameAdjustRowEnabled] 裁决，文案如实配合（R201 禁用态如实说明口径）。
 */
fun frameAdjustRowSubtitle(row: FrameAdjustRow, rotationDeg: Int, mirrored: Boolean): String {
    val deg = frameAdjustNormalized(rotationDeg)
    return when (row) {
        FrameAdjustRow.ROTATE_LEFT -> "逆时针旋转 90°（当前 ${deg}°）"
        FrameAdjustRow.ROTATE_RIGHT -> "顺时针旋转 90°（当前 ${deg}°）"
        FrameAdjustRow.MIRROR ->
            if (mirrored) "已镜像，点按恢复" else "画面左右翻转"
        FrameAdjustRow.RESET ->
            if (isFrameAdjusted(deg, mirrored)) "当前：${frameAdjustStateText(deg, mirrored)}"
            else "已是默认姿态"
    }
}

/**
 * 枢纽菜单行可用性：向左/向右旋转与镜像恒可用（姿态动作不存在不可用态）；
 * 恢复默认行仅在有可清除项（非默认姿态）时可用——默认态置灰+如实文案，
 * 不假装可点（R201「无公开链接」行同款口径）。
 */
fun frameAdjustRowEnabled(row: FrameAdjustRow, rotationDeg: Int, mirrored: Boolean): Boolean =
    when (row) {
        FrameAdjustRow.RESET -> isFrameAdjusted(rotationDeg, mirrored)
        else -> true
    }
