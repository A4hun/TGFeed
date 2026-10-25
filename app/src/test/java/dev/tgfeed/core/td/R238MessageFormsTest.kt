package dev.tgfeed.core.td

import dev.tgfeed.core.feed.albumThreadCarrierOf
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R238「10 种 TG 消息形态全量枚举」单测（架构师强制令：caption/评论提取链路
 * 逐形态锚定，少一种不算完成）：
 *
 *  - form1 纯视频单条：content.caption.text 直读 + commentCount（replyInfo.replyCount）
 *  - form2 纯图片单条：同上（mediaType=1）
 *  - form4 混合类型相册：caption 载体（图片成员）+ 空 caption 视频成员 → 共享
 *    caption 补全（R219 形态A 的 toVideoItem 落点）；评论线程挂在载体成员
 *  - form5 转发：forwardInfo 与 caption 提取正交（转发的 caption 在消息自身
 *    content 上，不在 origin）
 *  - form6 回复消息：replyTo 与 caption 提取正交
 *  - form9 圆形视频（video_note）：MessageVideoNote 无 caption 字段
 *    （javap 实证 MessageVideoNote{videoNote, isViewed, isSecret}）→ 不产出条目
 *    （锚定现状：不冒充标题，也不误吞）
 *  - form10 文档伪装视频：mime 以 video/ 开头的文档按视频产出（旧版只认 audio/，
 *    视频文档整条丢弃）；非媒体文档（zip 等）仍不收录
 *  - 相册评论线程载体选取（albumThreadCarrierOf 纯函数）：组内 commentCount>0
 *    成员优先；全 0（DB 直出早期帧）回退组内首位（messageId 最小）；非相册自返回
 *
 * 接收体手法沿用 CaptionOverridePriorityTest：mockk 经 Objenesis 实例化不跑
 * init 块（不加载 tdjni）；被测路径不触达网关状态（titleOverride 绕开
 * channelTitle；thumbnail 置空绕开铸造登记；File.local 未完成绕开磁盘校验）。
 */
class R238MessageFormsTest {

    private val gw = mockk<TdLibGateway>()

    init {
        // 图片分支的封面档登记（mintCoverFileId→coverFileMinted 属性读）：真 Map 桩
        // （compute 真执行；视频分支 thumbnail=null 不触达，图片分支恒触达）
        every { gw.coverFileMinted } returns java.util.concurrent.ConcurrentHashMap()
    }

    // ---------------- 夹具：裸 TdApi.Message 构造（只填被测逻辑读到的字段） ----------------

    /** 空壳 File（local 未完成→completedLocalPath 返 null，不触磁盘；remote 兜 uniqueId 读点） */
    private fun bareFile() = TdApi.File().apply {
        local = TdApi.LocalFile()
        remote = TdApi.RemoteFile()
    }

    private fun interaction(replyCount: Int) = TdApi.MessageInteractionInfo().apply {
        replyInfo = TdApi.MessageReplyInfo().apply { this.replyCount = replyCount }
    }

    private fun videoMsg(
        id: Long,
        albumId: Long = 0L,
        caption: String = "",
        replyCount: Int = 0,
        forwardInfo: TdApi.MessageForwardInfo? = null,
        replyTo: TdApi.MessageReplyTo? = null,
    ) = TdApi.Message().apply {
        this.id = id
        this.chatId = -100L
        this.mediaAlbumId = albumId
        this.forwardInfo = forwardInfo
        this.replyTo = replyTo
        if (replyCount > 0) interactionInfo = interaction(replyCount)
        content = TdApi.MessageVideo().apply {
            this.caption = TdApi.FormattedText().apply { text = caption }
            video = TdApi.Video().apply { video = bareFile() }
        }
    }

    private fun photoMsg(id: Long, albumId: Long = 0L, caption: String = "", replyCount: Int = 0) =
        TdApi.Message().apply {
            this.id = id
            this.chatId = -100L
            this.mediaAlbumId = albumId
            if (replyCount > 0) interactionInfo = interaction(replyCount)
            content = TdApi.MessagePhoto().apply {
                this.caption = TdApi.FormattedText().apply { text = caption }
                photo = TdApi.Photo().apply {
                    sizes = arrayOf(
                        TdApi.PhotoSize().apply {
                            photo = bareFile()
                            width = 640
                            height = 960
                        },
                    )
                }
            }
        }

    private fun docMsg(id: Long, mime: String, caption: String = "", fileName: String = "f.bin") =
        TdApi.Message().apply {
            this.id = id
            this.chatId = -100L
            content = TdApi.MessageDocument().apply {
                this.caption = TdApi.FormattedText().apply { text = caption }
                document = TdApi.Document().apply {
                    mimeType = mime
                    this.fileName = fileName
                    document = bareFile()
                }
            }
        }

    private fun videoNoteMsg(id: Long) = TdApi.Message().apply {
        this.id = id
        this.chatId = -100L
        content = TdApi.MessageVideoNote().apply {
            videoNote = TdApi.VideoNote().apply { video = bareFile() }
        }
    }

    // ---------------- form1：纯视频单条（caption + 评论数） ----------------

    @Test
    fun `form1 单视频_caption直读_评论数透传`() = runBlocking {
        val item = gw.toVideoItem(-100L, videoMsg(1, caption = "标题甲 第一期", replyCount = 37), null, titleOverride = "频道甲")
        assertEquals("标题甲 第一期", item!!.caption)
        assertEquals(37, item.commentCount)
        assertEquals(0, item.mediaType)
    }

    @Test
    fun `form1 无线程_评论数零`() = runBlocking {
        val item = gw.toVideoItem(-100L, videoMsg(2, caption = "x"), null, titleOverride = "频道甲")
        assertEquals(0, item!!.commentCount)
    }

    // ---------------- form2：纯图片单条 ----------------

    @Test
    fun `form2 单图片_caption直读`() = runBlocking {
        val item = gw.toVideoItem(-100L, photoMsg(3, caption = "图集标题"), null, titleOverride = "频道甲")
        assertEquals("图集标题", item!!.caption)
        assertEquals(1, item.mediaType)
        assertEquals(0, item.commentCount)
    }

    // ---------------- form4：混合类型相册（用户实锤形态） ----------------

    @Test
    fun `form4 混合相册_视频成员吃相册共享caption`() = runBlocking {
        // 载体=图片成员（带 caption+线程）；视频成员 caption 空 → 链②相册共享（R257b 口径）
        val item = gw.toVideoItem(
            -100L,
            videoMsg(10, albumId = 7L, replyCount = 0),
            null,
            titleOverride = "频道甲",
            titleCtx = TitleChainContext(albumCaps = mapOf(7L to "相册共享文案")),
        )
        assertEquals("相册共享文案", item!!.caption)
        assertEquals(7L, item.albumId)
    }

    @Test
    fun `form4 相册成员_自身线程计数透传`() = runBlocking {
        val item = gw.toVideoItem(
            -100L,
            photoMsg(11, albumId = 7L, caption = "载体文案", replyCount = 37),
            null,
            titleOverride = "频道甲",
        )
        assertEquals(37, item!!.commentCount)
    }

    // ---------------- form5：转发（caption 在消息自身 content 上） ----------------

    @Test
    fun `form5 转发消息_caption照读`() = runBlocking {
        // javap 实证 Message.forwardInfo: MessageForwardInfo{origin, date, ...}——
        // 转发不改写 content：caption 就在本消息 content.caption 上
        val fwd = TdApi.MessageForwardInfo().apply {
            origin = TdApi.MessageOriginChannel().apply { chatId = -200L }
        }
        val item = gw.toVideoItem(
            -100L,
            videoMsg(12, caption = "转发带来的标题", forwardInfo = fwd),
            null,
            titleOverride = "频道甲",
        )
        assertEquals("转发带来的标题", item!!.caption)
    }

    // ---------------- form6：带回复的消息 ----------------

    @Test
    fun `form6 回复消息_caption照读`() = runBlocking {
        val replyTo = TdApi.MessageReplyToMessage().apply {
            chatId = -100L
            messageId = 99L
        }
        val item = gw.toVideoItem(
            -100L,
            videoMsg(13, caption = "回复帖标题", replyTo = replyTo),
            null,
            titleOverride = "频道甲",
        )
        assertEquals("回复帖标题", item!!.caption)
    }

    // ---------------- form9：圆形视频（无 caption 字段，锚定丢弃现状） ----------------

    @Test
    fun `form9 圆形视频_不产出条目`() = runBlocking {
        // javap 实证 MessageVideoNote{videoNote, isViewed, isSecret}——无 caption 位，
        // 标题无从提取（TG 设计如此）；当前口径不产出（不冒充标题）
        assertNull(gw.toVideoItem(-100L, videoNoteMsg(14), null, titleOverride = "频道甲"))
    }

    // ---------------- form10：文档伪装视频 ----------------

    @Test
    fun `form10 视频文档_按视频产出带caption`() = runBlocking {
        val item = gw.toVideoItem(
            -100L,
            docMsg(15, mime = "video/mp4", caption = "文档伪装的标题", fileName = "demo.mp4"),
            null,
            titleOverride = "频道甲",
        )
        assertEquals("文档伪装的标题", item!!.caption)
        assertEquals(0, item.mediaType)
        assertEquals("video/mp4", item.mimeType)
    }

    @Test
    fun `form10 音频文档_保持音频归类`() = runBlocking {
        val item = gw.toVideoItem(
            -100L,
            docMsg(16, mime = "audio/mpeg", caption = "音频文档"),
            null,
            titleOverride = "频道甲",
        )
        assertEquals(2, item!!.mediaType)
    }

    @Test
    fun `form10 非媒体文档_不收录`() = runBlocking {
        assertNull(
            gw.toVideoItem(
                -100L,
                docMsg(17, mime = "application/zip", caption = "压缩包"),
                null,
                titleOverride = "频道甲",
            ),
        )
    }

    // ---------------- 相册评论线程载体选取（纯函数） ----------------

    private fun member(id: Long, albumId: Long = 7L, count: Int = 0) = VideoItem(
        key = "tg:-100:$id", chatId = -100L, messageId = id, channelTitle = "频道甲",
        caption = "", durationSec = 0, width = 0, height = 0, fileId = -1, sizeBytes = 0,
        playUri = null, albumId = albumId, commentCount = count,
    )

    @Test
    fun `载体选取_组内带计数成员优先`() {
        val group = listOf(member(5), member(6, count = 37), member(7))
        assertEquals(6L, albumThreadCarrierOf(group, member(5)).messageId)
    }

    @Test
    fun `载体选取_全零回退组内首位`() {
        // DB 直出早期帧：commentCount 全 0 → 相册发送序首位（线程与 caption 同挂首位惯例）
        val group = listOf(member(7), member(5), member(6))
        assertEquals(5L, albumThreadCarrierOf(group, member(7)).messageId)
    }

    @Test
    fun `载体选取_非相册条目自返回`() {
        val single = member(9, albumId = 0L)
        assertEquals(single.key, albumThreadCarrierOf(emptyList(), single).key)
    }
}
