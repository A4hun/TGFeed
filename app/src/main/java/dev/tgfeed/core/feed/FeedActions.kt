package dev.tgfeed.core.feed

import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem

/**
 * Feed 动作门面：把网关层的 Result 折叠成用户可读文案，UI/ViewModel 拿到即可直接弹 Toast。
 *
 * 注意：TdGateway 接口不含这两个能力（接口文件不归本模块改），
 * 这里按实现类型分发；未知实现降级为"不支持"文案而不是崩溃。
 */
class FeedActions(private val gateway: TdGateway) {

    /** 转存到 Telegram 收藏夹；返回值 = 成功提示或失败原因，均可直接展示 */
    suspend fun forwardToSaved(item: VideoItem): String = when (gateway) {
        is TdLibGateway -> gateway.forwardToSaved(item).fold(
            onSuccess = { it },
            onFailure = { friendly("转存失败", it) },
        )
        is MockTdGateway -> gateway.forwardToSaved(item).fold(
            onSuccess = { it },
            onFailure = { friendly("转存失败", it) },
        )
        else -> "当前模式不支持转存"
    }

    /**
     * 一键翻译 caption；成功返回译文，失败返回"翻译失败：…"文案。
     * 调用方如需区分两者，可判前缀或直接用网关层的 Result 版本。
     */
    suspend fun translateCaption(item: VideoItem, targetLang: String = "zh"): String = when (gateway) {
        is TdLibGateway -> gateway.translateCaption(item, targetLang).fold(
            onSuccess = { it },
            onFailure = { friendly("翻译失败", it) },
        )
        is MockTdGateway -> gateway.translateCaption(item, targetLang).fold(
            onSuccess = { it },
            onFailure = { friendly("翻译失败", it) },
        )
        else -> "当前模式不支持翻译"
    }

    /** 把异常折成可读文案：优先用异常自带 message（网关层已尽量写成中文） */
    private fun friendly(prefix: String, t: Throwable): String =
        "$prefix：${t.message ?: t.javaClass.simpleName}"
}
