package dev.tgfeed.ui

/**
 * R194⑥ 媒体带锚定（架构师 17:10 布局终案）纯函数：悬停式顶锚。
 * 前史：R188c 的 fitBottomAnchorShiftPx（底锚+letterbox 挪顶）被终案取代删除
 * （git 历史 r188_notes 可考）——「视频已完全融入底部三导航按钮」实锤判底锚
 * 过冲；锚定口径自此唯一：本件的 [fitHoverAnchorShiftPx]。
 *
 * 问题链（用户打地鼠三段）：R188 前底部空带违和 → R188c 底锚修底缝但底缘压进
 * 透明 dock（视频融入三导航钮）→ 终案两缘收紧、富余进中。
 *
 * 同类收敛（纪律§一）：「FIT 内容满高容器锚定」这一类的唯一判据与口径都在
 * 这里——VideoSurfaceLayer（视频+封面态）与 PhotoCard 主图共用，同一处改全局
 * 生效。同类未修挂账：GridVideoCell（网格缩略图居中=另一视觉语境）、AudioCard
 * （分栏滚动列非 letterbox 形态）、高向适配满高族的布局级限高（见函数 KDoc）。
 */
object FitBottomAnchor {

    // ---------------- R194⑥ 悬停式顶锚（架构师布局终案 17:10，用户补充实锤） ----------------

    /**
     * R194⑥ 悬停式顶锚平移量（px，正值 = 向下平移；负值 = 向上）。
     *
     * 背景（用户打地鼠第三段实锤+终案裁决）：
     * - R188c 的「底锚+letterbox 挪顶」把视频底缘压进透明 dock 后面——用户
     *   「视频已完全融入底部三导航按钮里，不算修好」；
     * - 终案 = **两缘收紧、富余进中**：视频顶锚贴 tab 条小间距（~12dp）；底缘
     *   悬停在 dock 上方小间距（~12dp，画面与三导航钮不重叠）；剩余富余全部
     *   置于视频下缘与说明区之间（读作自然留白/呼吸感）。说明区+进度条照旧贴底。
     *
     * 语义：宽向适配（有垂直 letterbox）的内容带从「垂直居中」平移到「顶锚
     * 预留线」——shift = topReserve − 居中位上缘。带高超出可用预算（顶+底预留
     * 之间）时退化为预算内最高可放（带底 ≤ H − bottomReserve 的悬停红线优先于
     * 顶锚，只裁「富余」不裁内容——高向适配满高族不进本函数，布局级限高挂账）。
     *
     * 红线（矩阵进单测 FitBottomAnchorTest）：
     * - 竖容器恒 0（TikTok 居中惯例，R188 双口径零回归沿用）；
     * - 内容比未知（≤0）恒 0；预留非正（chrome 全退/全屏态）恒 0=居中；
     * - 高向适配（无垂直 letterbox）恒 0——满高内容平移必裁画面（R50 裁切不可
     *   接受红线），布局级限高挂账（r194_notes）。
     *
     * @param containerWidthPx 容器宽（满高列/破列后全屏页），px
     * @param containerHeightPx 容器高，px
     * @param contentRatio FIT 内容宽高比（宽/高）
     * @param topReservePx 内容带目标上缘（tab 底缘 + 小间距），px
     * @param bottomReservePx 内容带下缘下限的预留（navbar+dock+小间距），px
     */
    fun fitHoverAnchorShiftPx(
        containerWidthPx: Int,
        containerHeightPx: Int,
        contentRatio: Float,
        topReservePx: Float,
        bottomReservePx: Float,
    ): Float {
        if (containerWidthPx <= 0 || containerHeightPx <= 0 || contentRatio <= 0f) return 0f
        val containerRatio = containerWidthPx.toFloat() / containerHeightPx
        if (containerRatio <= 1f) return 0f // 竖容器居中红线
        if (contentRatio < containerRatio) return 0f // 高向适配：满高无 letterbox（见 KDoc 挂账）
        val contentHeightPx = containerWidthPx / contentRatio
        if (contentHeightPx >= containerHeightPx) return 0f // 防御：满高不平移
        val centeredTopPx = (containerHeightPx - contentHeightPx) / 2f
        val shift = topReservePx - centeredTopPx
        // 带高超出预算（顶锚放置会侵入底缘悬停线）→ 预算内最高可放（底缘悬停
        // 红线优先）：带底 = 居中底 + s ≤ H − bottomReserve → s ≤ centeredTop − bottom
        val capShift = centeredTopPx - bottomReservePx
        return if (shift > capShift) capShift else shift
    }
}
