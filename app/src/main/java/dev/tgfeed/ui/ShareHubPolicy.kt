package dev.tgfeed.ui

/**
 * R201 分享枢纽（ShareHubSheet）菜单态纯判定：
 * 把「链接解析态 → 三行菜单各自可用/禁用+如实文案」的决策从 Composable 里抽成纯函数，
 * 与 DoubleTapRule/MediaRailPolicy 同一族（UI 策略纯函数 + 单测守护）。
 *
 * 背景：右轨分享钮原直跳 ACTION_SEND（链接），长按面板另有「复制链接/分享卡片」两行
 * ——同一批能力三个入口各写一份。R201 收口为枢纽菜单：点分享 → 弹小菜单三选一
 * （复制链接 / 分享到其他应用 / 分享卡片）。三能力链路本身零改动，本文件只管菜单态。
 *
 * 链接可用性口径：TDLib 的 GetMessageLink 对私有频道/无权限/异常返回 null（网关层
 * runCatching 收敛），Mock 恒有链接。面板打开时先解析（异步），解析结果驱动菜单行：
 *  - 解析中：链接相关两行禁用（副文案「正在获取公开链接…」，不假装可用）；
 *  - 解析到手：两行启用；
 *  - 确定无公开链接：两行禁用 + 副文案如实说明（沿用「该视频没有公开链接」口径，
 *    按媒体类型分词）——禁用而非隐藏：让用户知道「这里为什么不能点」。
 * 分享卡片行恒启用：ShareCard.generate 对 link=null 本就跳过链接行（卡片仍可生成），
 * 不受链接可用性牵连。
 */

/** 枢纽的公开链接解析态（面板生命周期内由 LaunchedEffect 驱动迁移） */
sealed interface ShareHubLink {
    /** 解析中（面板刚开，GetMessageLink 在途） */
    data object Resolving : ShareHubLink

    /** 解析到手（复制/系统分享两行可点） */
    data class Ready(val url: String) : ShareHubLink

    /** 确定无公开链接（getMessageLink 返回 null：私有频道/无权限/解析失败） */
    data object NoPublicLink : ShareHubLink
}

/** 枢纽菜单行（三选项统一枚举；口径与 ShareHubSheet 渲染序一致） */
enum class ShareHubRow { COPY_LINK, SEND_APPS, CARD }

/**
 * 单行菜单态：可用性 + 副文案覆盖（null = 用该行默认描述）。
 * 纯函数，菜单态唯一裁决口——ShareHubSheet 每行渲染前各问一次。
 */
data class ShareHubRowState(val enabled: Boolean, val hint: String? = null)

/** 行可用性：链接相关两行（复制/系统分享）要 Ready 才启用；分享卡片恒可用 */
fun shareHubRowEnabled(row: ShareHubRow, link: ShareHubLink): Boolean = when (row) {
    ShareHubRow.COPY_LINK, ShareHubRow.SEND_APPS -> link is ShareHubLink.Ready
    ShareHubRow.CARD -> true
}

/**
 * 行副文案覆盖：解析中/无公开链接时如实说明（覆盖默认描述），其余回退默认。
 * 仅对链接相关两行生效；分享卡片行不参与（恒可用，无覆盖文案）。
 */
fun shareHubRowHint(row: ShareHubRow, link: ShareHubLink, mediaType: Int): String? =
    if (row == ShareHubRow.CARD) {
        null
    } else {
        when (link) {
            is ShareHubLink.Resolving -> "正在获取公开链接…"
            is ShareHubLink.NoPublicLink -> shareHubNoLinkHint(mediaType)
            is ShareHubLink.Ready -> null
        }
    }

/** 行菜单态（可用性+副文案一次性裁决；上两函数的组合便捷口） */
fun shareHubRowState(row: ShareHubRow, link: ShareHubLink, mediaType: Int): ShareHubRowState =
    ShareHubRowState(
        enabled = shareHubRowEnabled(row, link),
        hint = shareHubRowHint(row, link, mediaType),
    )

/**
 * 无公开链接提示（沿用复制链接链路的既有 Toast 口径，按媒体类型分词）：
 * 0=视频 1=图片 2=音频（口径同 VideoItem.mediaType），其余兜底「该条目」。
 */
fun shareHubNoLinkHint(mediaType: Int): String = when (mediaType) {
    0 -> "该视频没有公开链接"
    1 -> "该图片没有公开链接"
    2 -> "该音频没有公开链接"
    else -> "该条目没有公开链接"
}

/**
 * 系统分享面板（ACTION_SEND chooser）标题：视频沿用右轨直跳时代的原文
 * 「分享视频链接」（字节不变）；图片/音频是枢纽统一后新开的入口，补齐同族文案。
 */
fun shareHubChooserTitle(mediaType: Int): String = when (mediaType) {
    0 -> "分享视频链接"
    1 -> "分享图片链接"
    2 -> "分享音频链接"
    else -> "分享链接"
}
