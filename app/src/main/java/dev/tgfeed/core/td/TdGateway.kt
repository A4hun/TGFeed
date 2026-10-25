package dev.tgfeed.core.td

import kotlinx.coroutines.flow.StateFlow

/** TDLib 网关状态 */
sealed interface GatewayState {
    data object Loading : GatewayState
    data object NeedPhone : GatewayState
    data object NeedCode : GatewayState
    data object NeedPassword : GatewayState
    data object Ready : GatewayState
    data class Error(val message: String) : GatewayState
}

/** 频道摘要 */
data class ChannelBrief(
    val id: Long,
    val title: String,
    val username: String?,
    // R284 时效感知（仅 listSearchableChats 目录枚举填真值，其余路径缺省 0=无时效语义）：
    val ttlSec: Int = 0,               // Chat.messageAutoDeleteTime 秒（0=不自毁；javap 实证本 AAR 在场）
    val lastMessageEpochSec: Long = 0, // Chat.lastMessage.date 秒（0=未知——新鲜度信号）
)

/**
 * 视频清晰度档位：一条视频的可选分辨率之一。
 * height = 竖向分辨率（1080/720/480…，UI 标注 "1080p"）；
 * fileId = 该档对应的 TDLib 文件 id（Mock 恒为 -1，仅用于演示档位 UI）；
 * sizeBytes = 该档文件大小；mimeType 沿用主档（TDLib AlternativeVideo 无 mime 字段）。
 */
data class VideoVariant(
    val height: Int,
    val fileId: Int,
    val sizeBytes: Long,
    val mimeType: String,
)

/** Feed 的最小单元：一条可播放的视频 */
data class VideoItem(
    val key: String,           // 全局唯一 id
    val chatId: Long,
    val messageId: Long,
    val channelTitle: String,
    val caption: String,
    val durationSec: Int,
    val width: Int,
    val height: Int,
    val fileId: Int,           // TDLib file id；Mock 恒为 -1
    val sizeBytes: Long,
    val playUri: String?,      // 已可播放的地址（http 或本地文件）；null = 需先 ensurePlayable
    val mimeType: String = "video/mp4",
    val coverBytes: ByteArray? = null, // minithumbnail 字节，用于封面模糊占位（TDLib 有效）
    val dateEpochSec: Int = 0,   // 消息发布时间（Unix 秒）；老收藏数据反序列化缺省为 0
    // v2 文件级去重：TDLib 远端文件唯一 id（File.remote.uniqueId），
    // 同一视频被转发到多个频道时 uniqueId 相同，可据此跨频道识别同片；
    // Mock/本地文件没有远端 id，缺省空串（不参与去重）
    val fileUniqueId: String = "",
    // v3 多媒体扩展（均带默认值，不破坏既有构造与收藏 JSON 的反序列化）：
    val mediaType: Int = 0,          // 媒体类型：0=视频 1=图片 2=音频
    val fromComment: Boolean = false, // true = 来自评论级联（caption 已带 💬 前缀）
    // v4 清晰度与图集扩展（均带默认值，不破坏既有构造与收藏 JSON 的反序列化）：
    val albumId: Long = 0,           // 图集归组 id（Message.mediaAlbumId，javap 实证存在）；0=非图集
    val variants: List<VideoVariant> = emptyList(), // 清晰度档位：主档+备选，按 height 降序去重；空/单档=无选择
    // v5 高清封面：minithumbnail（coverBytes）只有几十像素糊成马赛克，
    // 真缩略图是独立 File（Video.thumbnail.file.id），需要时按需下载
    val coverFileId: Int = -1,
    // v6 音频卡重做（均带默认值，不破坏既有构造与收藏 JSON 的反序列化）：
    val audioTitle: String = "",       // 音乐标题（Audio.title，javap 实证）；空 = 回落 caption 首行
    val audioPerformer: String = "",   // 歌手（Audio.performer，javap 实证）；空则副标题只显示频道名
    // 专辑封面高清图 fileId（Audio.albumCoverThumbnail.file.id，javap 实证
    // Audio.albumCoverThumbnail: Thumbnail{file: File}）；经 TdLibGateway.loadAudioCoverBytes 下载，
    // 与 coverFileId 分离：coverFileId 语义专指视频封面，音频封面走独立字段不污染视频链路
    val audioCoverFileId: Int = -1,
    // 语音波形（VoiceNote.waveform，javap 实证 public byte[] waveform）：
    // 每字节高/低 5bit 各编码一个 0..31 的幅度采样；仅语音消息（MessageVoiceNote）有，其余音频为 null
    val waveBytes: ByteArray? = null,
    // v7 图片浏览档（图片加载优化 W1）：Photo.sizes 里 ≈1280px 的 "y" 档
    //（无 "y" 取 "x"≈800px，再无取最长边 ≤1280px 的最大档，全更小取最大档）对应的 fileId，
    // 专供全屏浏览/分屏格解码——浏览不再整载最大档原图（2~8MB → 0.2~0.8MB）。
    // fileId 字段保持最大档原图不动，仅供下载保存/分享链使用。非图片条目恒 -1
    val photoViewFileId: Int = -1,
    // v8 供给位标记（R176 关注流推荐兜底层）：true = 本条来自推荐兜底层（网关聚合路），
    // 不是订阅频道轮转供给——UI 信息区据此显示「为你推荐」chip 明示来源（用户口径：
    // 不让用户感觉被骗）。会话级标记：刻意不落盘（toJson 不含），重启/跨会话自然归零
    val recommended: Boolean = false,
    // v9 caption 结构化（R228）：深索引 v8 落库的逐条解析标题/结构化标签，rowToItem 读出。
    //  - parsedTitle：null = 未解析/回退模式（消费方回退 caption 首行，见 titleLine()）；
    //  - itemTags：null = 未解析（存量未回填——tag 查询回退 hashtagHits 旧口径）；
    //    空表 = 已解析但无标签（权威空，tag 查询据此精确排除）；元素为原形标签（无 # 前缀）。
    // 与 recommended 同为**派生数据**：刻意不落盘（toJson 不含，收藏/历史 JSON 反序列化
    // 自然回 null），语义源头在 items.parsed_title/item_tags（入库时解析，展示不重算）。
    val parsedTitle: String? = null,
    val itemTags: List<String>? = null,
    // v10 评论数（R238）：本条 TG 帖的评论线程回复数（Message.interactionInfo.replyInfo.
    // replyCount，javap 实证 int 字段）。会话级派生数据——刻意不落盘（toJson 不含，
    // 收藏/历史 JSON 反序列化自然回 0，同 recommended 先例）：评论数随时间增长，落盘
    // 必陈旧。相册（mediaAlbumId≠0）成员各自的 replyCount 只在携带线程的成员上非 0，
    // 消费方（VideoBottomInfo）按相册组取 max 聚合展示——评论线程属于整个相册帖。
    // DB 直出条目（rowToItem）无此字段：恒 0=不显示角标（网络页覆盖后出现，诚实缺省）。
    val commentCount: Int = 0,
    // v11 收藏/暂存时刻（R300 多设备同步 favTs）：条目被收入收藏/稍后再看时的"加入时刻毫秒"，
    // 跨设备 per-key LWW 裁决依据（SyncMergePolicy 唯一裁决出处）；
    // 存量数据与消费态条目缺省 0=未知（迁移期由存在性并集兜底，宁多勿丢）。
    // 语义同 commentCount：只有落在收藏/稍后清单里的条目此字段才有意义
    val favTs: Long = 0,
    // v12 推荐解释（R298 真推荐引擎）：本条由哪路召回命中、因何入选（会话级派生标记，
    // 刻意不落盘——toJson 不含，与 recommended 同先例，收藏/历史 JSON 反序列化自然回
    // null）。非 null = 推荐引擎注入条目，值=人话原因（「常看 #某tag」/「同好频道在看」），
    // UI 信息区据此出「为什么推这条」chip（红线：无数据不造假——无命中轴时不写此字段，
    // 不编理由）。
    val recommendReason: String? = null,
)

/**
 * R80：条目自带 playUri 进播放器前的收口判定。
 * 裸本地路径含 '#' 或 '?' 时绝不能直喂 ExoPlayer——MediaItem.setUri(String) 过
 * Uri.parse 会把后段截成 fragment/query，FileDataSource 实锤报
 * "uri has query and/or fragment"（1004）或读到截断后的目录（2000），起播即败。
 * 命中时返回 null：消费链随之走 ensurePlayable（TdLibGateway 短路点会再过
 * playableUriOrStream 换流服务器地址/file:// 编码地址）。
 * http/asset 直链与正常裸路径原样保留。
 * 注意：PhotoCard 这类按裸路径 decodeFile 的消费者不要用本函数（它不过 Uri 解析）。
 */
fun VideoItem.safeInitialPlayUri(): String? {
    val u = playUri ?: return null
    return if (!u.startsWith("http") && !u.startsWith("asset:///") &&
        (u.contains('#') || u.contains('?'))
    ) {
        null
    } else {
        u
    }
}

/** Feed 请求：频道 / 搜索 / 聚合 */
data class FeedRequest(
    val channelId: Long? = null,       // 指定频道；null = 聚合全部
    val searchQuery: String? = null,   // 精准搜索关键词
)

data class FeedPage(
    val items: List<VideoItem>,
    val nextCursor: String?,           // null = 到底了
)

/**
 * 数据层统一入口。当前有两个实现：
 *  - [TdLibGateway]：真实 TDLib（需 api_id/api_hash）
 *  - [MockTdGateway]：演示模式（公开测试视频，开发 UI 用）
 */
interface TdGateway {
    val state: StateFlow<GatewayState>

    /** 连接状态（面向用户的中文可读）：连接中/连接代理中/同步中/已连接/等待网络 */
    val connectionState: StateFlow<String>

    /**
     * R244 代理决策可见性（null = 无事发生 / 无代理概念）：
     * 登录/连接期代理决策的人话提示，例「已自动启用本地代理 127.0.0.1:7890（检测到
     * 手机代理软件）」「代理 127.0.0.1:7897 不可达，直连中——连不上请检查 VPN/代理」。
     * 背景：旧版代理探测失败后**静默直连**（只有 logcat 日志），用户侧「app 走直连
     * 连不上 TG」却毫无提示（新设备登录 P0 实锤②）。AuthScreen 展示；Mock/占位网关恒 null。
     */
    val proxyHint: StateFlow<String?>

    /**
     * v3 网关级开关：是否级联拉取评论区的资源视频（真实网关生效，默认开）。
     * 关闭后不再调 GetMessageThreadHistory，彻底免除级联带来的 flood wait 风险。
     * 设置页可直接赋值；Mock 里用它控制 💬 演示条目的产出。
     */
    var includeCommentResources: Boolean

    suspend fun submitPhone(phone: String)
    suspend fun submitCode(code: String)
    suspend fun submitPassword(password: String)

    suspend fun listChannels(): List<ChannelBrief>

    /** 拉取一页视频；cursor 传 null 表示从头开始 */
    suspend fun fetchVideos(request: FeedRequest, cursor: String?, limit: Int = 20): FeedPage

    /** 确保条目可播放，返回可交给 ExoPlayer 的地址 */
    suspend fun ensurePlayable(item: VideoItem, priority: Int = 1): String

    /**
     * 调整下载优先级（预加载用）。
     * offset/limit > 0 时只拉指定分段（TDLib 分段下载）——用于"只拉前缀"的省带宽预热。
     */
    suspend fun prioritize(fileId: Int, priority: Int, offset: Long = 0, limit: Long = 0) {}

    /**
     * R195 WiFi 激进整载：下一条预载目标推送。PreloadScheduler 每次翻页落定时
     * 调用（同一份「下一个非图媒体」选择顺手推前 1~2 条 fileId）；网关侧
     * AggressivePreloadManager 消费——当前整载完成后按序整载这些条目，
     * 新推送不含的旧目标=滑走作废，队列超时也作废。Mock/演示网关空实现。
     */
    fun setAggressivePreloadNextTargets(nextFileIds: List<Int>) {}
}
