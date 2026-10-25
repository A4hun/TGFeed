package dev.tgfeed.core.td

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 演示模式：用公开测试视频源模拟 Telegram 频道 feed。
 * 用于未配置 api_id/api_hash 时开发与调试 UI / 播放链路。
 */
class MockTdGateway : TdGateway {

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Ready)
    override val state: StateFlow<GatewayState> = _state

    /** 演示模式无网络：固定"已连接" */
    override val connectionState: StateFlow<String> = MutableStateFlow("已连接（演示模式）")

    /** R244 代理决策提示（演示模式无代理概念，恒 null） */
    override val proxyHint: StateFlow<String?> = MutableStateFlow(null)

    /** 演示模式：跟随真实网关的开关语义，关掉就不再产 💬 评论级联演示条目 */
    override var includeCommentResources: Boolean = true

    private val channels = listOf(
        ChannelBrief(-1001, "演示频道 · 动画", null),
        ChannelBrief(-1002, "演示频道 · 广告", null),
        ChannelBrief(-1003, "演示频道 · 混剪", null),
    )

    // v3：扩展 mediaType/mime（默认值保证既有视频条目构造不变），三种媒体类型都出演示
    private data class MockVideo(
        val title: String,
        val uri: String,
        val durationSec: Int,
        val mediaType: Int = 0, // 0=视频 1=图片 2=音频
        val mime: String = "video/mp4",
        // v4：清晰度档位演示（主档+备选，与真实网关 toVideoItem 的产物同构）；
        // Mock 没有真实 TDLib 文件，fileId 恒 -1——VideoPage 见 -1 会回落主档 playUri，
        // 这里只为让功能面板"清晰度"行在演示模式可见
        val variants: List<VideoVariant> = emptyList(),
    )

    /** 本地 assets：无网络也能播，保证演示模式永远可用 */
    private val localVideos = listOf(
        MockVideo("Big Buck Bunny", "asset:///bbb_10s.mp4", 10),
        MockVideo("Jellyfish", "asset:///jellyfish_10s.mp4", 10),
        MockVideo("Sintel", "asset:///sintel_10s.mp4", 10),
    )

    /** 远端：已验证国内直连可访问的测试流 */
    private val remoteVideos = listOf(
        // 多清晰度演示条：主档 720p + 2 个备选档（1080p/480p），验证功能面板"清晰度"行与起播选档链路
        MockVideo(
            "火山引擎演示片",
            "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-720p.mp4",
            27,
            variants = listOf(
                VideoVariant(1080, -1, 0, "video/mp4"),
                VideoVariant(720, -1, 0, "video/mp4"),
                VideoVariant(480, -1, 0, "video/mp4"),
            ),
        ),
        MockVideo("Sintel 预告片", "https://media.w3.org/2010/05/sintel/trailer.mp4", 52),
        MockVideo("Oceans", "https://vjs.zencdn.net/v/oceans.mp4", 46),
        MockVideo("Big Buck Bunny（远端）", "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4", 10),
    )

    private val allVideos = localVideos + remoteVideos

    /** 演示图片：picsum 按种子出固定图；加载失败时 UI 回落封面占位，正好演示该链路 */
    private val mockPhotos = listOf(
        MockVideo("示例图片 · 山巅", "https://picsum.photos/seed/tgfeed-mountain/1280/720.jpg", 0, 1, "image/jpeg"),
        MockVideo("示例图片 · 海面", "https://picsum.photos/seed/tgfeed-sea/1280/720.jpg", 0, 1, "image/jpeg"),
    )

    /** 演示音频：SoundHelix 公开测试音频流（免下载即播，验证音频播放链路） */
    private val mockAudios = listOf(
        MockVideo("示例音频 · SoundHelix Song 1", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3", 372, 2, "audio/mpeg"),
        MockVideo("示例音频 · SoundHelix Song 2", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3", 424, 2, "audio/mpeg"),
    )

    /** 演示池：视频为主，穿插图片/音频，三种媒体类型都能刷到 */
    private val demoPool = allVideos + mockPhotos + mockAudios

    override suspend fun submitPhone(phone: String) = Unit
    override suspend fun submitCode(code: String) = Unit
    override suspend fun submitPassword(password: String) = Unit

    override suspend fun listChannels(): List<ChannelBrief> = channels

    override suspend fun fetchVideos(request: FeedRequest, cursor: String?, limit: Int): FeedPage {
        delay(350) // 模拟网络延迟
        val start = cursor?.removePrefix("o:")?.toIntOrNull() ?: 0
        val query = request.searchQuery?.trim()?.lowercase()
        val pool = request.channelId?.let { id -> channels.filter { it.id == id } } ?: channels
        // R156 稳定性：真实模式的订阅/收藏频道带进演示模式时 pool 为空，
        // 下方 n % pool.size 会除零崩协程——空池直接回空页
        if (pool.isEmpty()) return FeedPage(emptyList(), null)

        val items = mutableListOf<VideoItem>()
        var n = start
        // 搜索过滤会丢弃条目，多扫描几轮补够一页
        while (items.size < limit && n < start + limit * 4) {
            val channel = pool[n % pool.size]
            val media = demoPool[n % demoPool.size]
            val caption = "${media.title} 片段 #${n}"
            if (query == null || caption.lowercase().contains(query) || media.title.lowercase().contains(query)) {
                // 演示 💬 评论级联条目：每 7 条出 1 条（跟随 includeCommentResources 开关）
                val fromComment = includeCommentResources && n % 7 == 3
                items += VideoItem(
                    key = "mock:${channel.id}:$n",
                    chatId = channel.id,
                    messageId = n.toLong(),
                    channelTitle = channel.title,
                    caption = if (fromComment) "💬 $caption" else caption,
                    durationSec = media.durationSec,
                    width = 1280,
                    height = 720,
                    fileId = -1,
                    sizeBytes = 0,
                    playUri = media.uri,
                    mimeType = media.mime,
                    mediaType = media.mediaType,
                    fromComment = fromComment,
                    variants = media.variants, // 多清晰度演示条（仅"火山引擎演示片"配了 2 个备选档）
                    // 递减时间戳：序号越新（n 越小）时间越近，模拟真实频道按时间倒序
                    dateEpochSec = (System.currentTimeMillis() / 1000L - n * 600L).toInt(),
                )
            }
            n++
        }
        return FeedPage(items, "o:$n") // Mock 数据无限翻页
    }

    override suspend fun ensurePlayable(item: VideoItem, priority: Int): String =
        item.playUri ?: error("Mock 条目缺少 playUri")

    /** 演示模式：把除自身外的前两个演示频道当作"相似频道"，验证推荐 UI 链路 */
    suspend fun getSimilarChats(chatId: Long): List<ChannelBrief> {
        delay(300) // 模拟网络延迟，让 UI 的 loading 态可见
        return channels.filter { it.id != chatId }.take(2)
    }

    /** 演示模式：没有真实 TDLib 文件，恒返回 -1（约定：UI 见 -1 不显示百分比） */
    @Suppress("UNUSED_PARAMETER")
    suspend fun downloadProgress(fileId: Int): Float = -1f

    /** 演示模式：没有真实频道头像，恒返回 null（UI 回落字母圆头像） */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getChannelAvatar(chatId: Long): ByteArray? = null

    /** 演示模式：没有真实 TG 会话，直接假装成功 */
    suspend fun forwardToSaved(item: VideoItem): Result<String> {
        delay(300) // 模拟网络延迟，让 UI 的 loading 态可见
        return Result.success("演示模式：假装已转存")
    }

    /** 演示模式：返回伪译文（前缀标记 + 原 caption），验证翻译 UI 链路 */
    suspend fun translateCaption(item: VideoItem, targetLang: String = "zh"): Result<String> {
        if (item.caption.isBlank()) return Result.failure(IllegalStateException("没有可翻译的文字"))
        delay(300)
        return Result.success("【演示译文·$targetLang】${item.caption}")
    }

    /** 演示模式：返回一个假链接 */
    suspend fun getMessageLink(item: VideoItem): String = "https://t.me/demo/${item.messageId}"

    /** 演示模式：没有真实 TG 会话，机器人搜索恒返回空表（UI 提示"机器人未回复"） */
    @Suppress("UNUSED_PARAMETER")
    suspend fun searchViaBot(botUsername: String, query: String): List<VideoItem> {
        delay(500) // 模拟网络延迟，让 UI 的"搜索中"态可见
        return emptyList()
    }

    /** 演示模式：固定 5 条演示弹幕（长短不一，验证匀速滚动时长与轨道分布）；limit 忽略 */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getComments(item: VideoItem, limit: Int = 50): List<String> {
        delay(300) // 模拟网络延迟，让弹幕异步到达的时序在 UI 上可见
        return listOf(
            "前排！",
            "这个剪辑太丝滑了",
            "哈哈哈哈哈已经循环十遍",
            "求 BGM，听歌识曲识别不出来",
            "蹲一个后续，UP 主更新踢我一脚",
        )
    }
}
