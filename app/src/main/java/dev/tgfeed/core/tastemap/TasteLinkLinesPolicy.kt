package dev.tgfeed.core.tastemap

/**
 * R314 互链连线呈现政策件（用户走查拍板「恒星间实体白线出戏——别用划线」）：
 *
 *  - **默认不划线**（AppPrefs `taste_link_lines` 默认关）：全景不再铺实体白直线网；
 *    开=旧行为逐位（两遍制直线网，想回旧视图的用户自助）。
 *  - **关态=仅焦点星显关联**：选中恒星时其互链边改画**细弱引力弧**（渲染侧复用
 *    galaxyArcControl 二次贝塞尔鼓包——与 R233 共振弧/R234 尘埃带同一族曲线语言，
 *    宇宙里不该有直梁）；延时摄影（genesisOn）期不画（同关联弧「宇宙尚未凝成」门径）。
 *  - 流光（沿边流动的光点）**保留常开**——它不是划线，是无线态下互链结构的可感知
 *    性来源（「引力通道上的光子」）。
 *
 * 纪律§一收口：Canvas（TasteFlightRenderer.drawEdges）与 GL（GlFrameBuilder.buildEdges）
 * 双侧消费同一出处，同样的数字不许两处各写一份。纯 Kotlin 零 Android 依赖，JVM 单测锚定。
 */
object TasteLinkLinesPolicy {

    /** 焦点引力弧的空间贝塞尔采样段数（与 R233 共振弧 20 段同口径） */
    const val FOCUS_ARC_SEGMENTS = 20

    /** 焦点引力弧透明度下限/上限（细弱档——远低于旧焦点直线 0.25~0.9 的加亮档） */
    const val FOCUS_ARC_ALPHA_MIN = 0.10f
    const val FOCUS_ARC_ALPHA_MAX = 0.34f

    /** 焦点引力弧线宽钳（px，细弱档——旧焦点直线钳 1.1~4.2 的收敛版） */
    const val FOCUS_ARC_STROKE_MIN_PX = 0.8f
    const val FOCUS_ARC_STROKE_MAX_PX = 1.6f

    /** 全景连线网（实体直线）画不画：开关唯一裁决 */
    fun drawMesh(linkLines: Boolean): Boolean = linkLines

    /** 焦点引力弧画不画：关态 ∧ 有焦点星 ∧ 非延时摄影期 */
    fun drawFocusArcs(linkLines: Boolean, focusId: Long, genesisOn: Boolean): Boolean =
        !linkLines && focusId != 0L && !genesisOn

    /** 焦点引力弧透明度：边权（烘焙 alpha）微抬后钳进细弱档 */
    fun focusArcAlpha(edgeAlpha: Float): Float =
        (edgeAlpha * 1.1f).coerceIn(FOCUS_ARC_ALPHA_MIN, FOCUS_ARC_ALPHA_MAX)

    /** 焦点引力弧线宽：渲染侧原始宽度（深度换算）钳进细弱档 */
    fun focusArcStroke(rawWidthPx: Float): Float =
        rawWidthPx.coerceIn(FOCUS_ARC_STROKE_MIN_PX, FOCUS_ARC_STROKE_MAX_PX)
}
