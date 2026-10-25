package dev.tgfeed.core.td


/**
 * 一条弹幕（评论）数据：带真实发布时间戳与回复标记。
 *
 * 弹幕从纯文本 List<String> 升级而来（DANMAKU-TG 任务 2），供
 * DanmakuOverlay 做"评论真实时间间隔 → 视频进度"的映射（任务 3，算法见 docs/DANMAKU-TG.md §3）。
 *
 * @param text 展示文本（数据层已去空白/去重/超长截断；命中"问出处"的回复带
 *   「回复「摘要」」上下文前缀，DanmakuOverlay 自动识别高亮）
 * @param timestampSec 评论发布时间（Unix 秒，TDLib Message.date；广场弹幕/Mock 为合成值，见 FeedScreen 装配注释）
 * @param isReply true = 该评论是"评论的回复"（replyTo 指向同讨论组内消息），UI 侧加 "↩ " 前缀区分
 */
data class DanmakuItem(
    val text: String,
    val timestampSec: Long,
    val isReply: Boolean,
)

/**
 * caption t.me 链接点击的公开频道解析结果（[TdLibGateway.resolvePublicChannel] 返回值）。
 * 与 resolveChannelBrief 的"null 一统天下"不同，这里把失败分类交给 UI 分别反馈——
 * 修"点未加入频道链接毫无反应"：用户必须知道是频道没了、私有受限、还是网络问题。
 */
sealed interface PublicChannelResolve {
    /** 公开频道（未加入也可浏览：SearchPublicChat 已打开会话，GetChatHistory 无需成员身份） */
    data class Channel(val brief: ChannelBrief) : PublicChannelResolve

    /** 用户名存在但指向用户/机器人/普通群，不是频道——调用方回落系统浏览器 */
    data object NotChannel : PublicChannelResolve

    /** 用户名不存在/已注销（400 USERNAME_NOT_OCCUPIED / USERNAME_INVALID） */
    data object NotFound : PublicChannelResolve

    /** 其余请求失败（网络/限流等），message 供日志与 Toast 参考 */
    data class Failed(val message: String) : PublicChannelResolve
}

/**
 * t.me/c/<internalId>[/<msgId>] 深链的解析结果（[TdLibGateway.resolveDeepLinkChat] 返回值）。
 * 修"私有频道内部链一点就死路"：resolveJoinedChannel 把未加入/是群组/不存在全压成 null，
 * UI 只剩一句 toast。R47 三态拆分后——已加入（含群组）直接开、未加入但链接可探也开、
 * 真私有未加入才给引导文案。R169 再补第四态（F6，照抄 [PlazaFindResult] 三态范式）：
 * 「探测失败」与「真不可达」分家——网络抖动/限流/超时误判 Inaccessible 会把可打开的
 * 公开链错报成"先去官方客户端加入"，用户被拒之门外零提示。
 */
sealed interface DeepLinkChatResolve {
    /**
     * 已加入的会话（GetChat 命中本地已同步会话）。
     * 群组也收（isChannel=false 供 UI 区分文案）——Feed 链 fetchFromChannel/fetchAggregatedMedia
     * 本来就出群组内容，深链不该拒绝。
     */
    data class Joined(val brief: ChannelBrief, val isChannel: Boolean) : DeepLinkChatResolve

    /**
     * 未加入但 GetMessageLinkInfo 经原始链接探到了消息与真实会话
     * （公开频道未加入场景，R41 已实证该 API 对未加入公开频道有效）：照常可开刷。
     */
    data class OpenableViaLink(val brief: ChannelBrief) : DeepLinkChatResolve

    /** 真私有且未加入（或链接本身无消息/无效的服务端权威终态）：重试无益，只能引导用户先去官方客户端加入 */
    data object Inaccessible : DeepLinkChatResolve

    /** 探测失败（网络/超时/限流等瞬态）：「查不了」≠「打不开」，UI 给"稍后再试"口径（R169，F6） */
    data class Failed(val reason: String) : DeepLinkChatResolve
}

/**
 * t.me/+ 邀请链接（含 t.me/joinchat/）的解析+加入结果（[TdLibGateway.resolveInviteLink] 返回值）。
 * 失败分类交 UI 分别反馈，与 [PublicChannelResolve] 同一设计口径。
 */
sealed interface InviteLinkResolve {
    /**
     * 已在频道内、可直接开刷：justJoined=false 表示链接解析时发现本来就是成员
     * （零副作用直通，没有真发 JoinChat）；true 表示本次通过 JoinChatByInviteLink 刚加入
     */
    data class Joined(val brief: ChannelBrief, val justJoined: Boolean) : InviteLinkResolve

    /** 频道开了加入审核：申请已提交，等管理员放行 */
    data object RequestSent : InviteLinkResolve

    /** 加入被拒（频道限制/账号被该频道封禁等） */
    data object Declined : InviteLinkResolve

    /** 其余失败（链接失效/网络/限流/指向的不是频道），message 已转中文可直接 Toast */
    data class Failed(val message: String) : InviteLinkResolve
}

/**
 * 广场查找三态（[TdLibGateway.plazaFindChat] 返回值，R70）。
 * 拆三态的根因：SearchPublicChat 被账号级 429 熔断期，「查询失败」与「频道不存在」
 * 都返回 null，PlazaService.ensurePlaza 拿到 null 且 plazaOwner=true 就再建一个新广场群
 * （无限建群 bug，用户 TG 客户端出现一堆"TGFeed 广场"垃圾群）。拆开后——
 * 只有 [NotFound] 允许进入创建分支；[Failed] 必须按「未知」处理，绝不创建。
 * 失败分类口径与 [PublicChannelResolve] 一致。
 */
sealed interface PlazaFindResult {
    /** 找到：SearchPublicChat 成功命中并返回会话 */
    data class Found(val chatId: Long) : PlazaFindResult

    /** 确定不存在：TDLib 400 用户名类稳定终态（查无此名），重试不会变出结果 */
    data object NotFound : PlazaFindResult

    /** 查询失败：429 限流/熔断静默/网络/其他异常——「查不了」不等于「不存在」 */
    data class Failed(val reason: String) : PlazaFindResult
}

// ---------------- 本人资料模型（TdLibGateway.getMyProfile 配套） ----------------

/**
 * R184 开频道自愈三态（[TdLibGateway.ensureChannelOpenable] 返回值）。
 * 拆三态的根因：R183 事故实锤——深索引候选卡 9/9 连开自愈时，自愈搜索被账号级
 * 429 拒（或本地预算拒），旧版 Boolean 把「限流打不开」与「真打不开」压成同一个
 * false，UI 层无从区分「稍后自动重试」与「频道没了」。拆开后——
 *  - [Success]：会话已建立，直接重读历史；
 *  - [RateLimited]：**限流打不开**（封禁窗/熔断/账号预算拒，未发送或被服务端 429），
 *    UI 给「搜索暂时被限流，稍后自动重试」口径，调用方可入队后台重试（解禁后
 *    EntityResolver 消化建会话+建缓存，用户下次点击直通）；
 *  - [NotFound]：线索确无法建立会话（用户名失效/标题搜不到/命中异频道），
 *    重试同一线索无意义。
 */
sealed interface ChannelOpenHeal {
    /** 会话已建立（GetChat 快路命中 / 解析建会话成功） */
    data object Success : ChannelOpenHeal

    /** 限流打不开：retryAfterSec 供退避（0 = 本地预算拒/未拿到服务端时长） */
    data class RateLimited(val retryAfterSec: Int) : ChannelOpenHeal

    /** 线索无法建立会话（稳定终态，重试同一线索无意义） */
    data object NotFound : ChannelOpenHeal
}


/**
 * 本人资料快照（[TdLibGateway.getMyProfile] 的返回模型）。
 * 文字字段全部可空：TG 允许不设姓/名/用户名，只保证"能拿到什么是什么"，
 * 调用方按可用字段优雅降级；[avatarBytes] 为头像小图字节，null 时调用方回落首字母圆。
 * 数组字段不做内容比较：缓存复用同一实例，引用相等即可满足 Compose 状态去重。
 */
data class MyProfile(
    val name: String?,
    val username: String?,
    val phone: String?,
    val avatarBytes: ByteArray?,
)

// ---------------- P1-b 发现增密配套（TdLibGateway.fetchSupergroupProfile / harvestChatFolderInvite） ----------------

/**
 * 候选频道超级群档案（TdLibGateway.fetchSupergroupProfile 的返回模型，P1-b #8）：
 * memberCount 喂 DiscoveryRanker 的 Q/A；description 里的互链已在网关侧顺手建边；
 * linkedChatId（关联讨论群）/communityId（TG Community 同运营家族 id）本轮只透传，
 * Community 家族扇出（DISCOVER-UPGRADE P1 #11）下一轮消费。
 */
data class SupergroupProfile(
    val chatId: Long,
    val memberCount: Int,
    val description: String,
    val linkedChatId: Long,
    val communityId: Long,
)

/**
 * 频道主页资源统计四元组（[TdLibGateway.probeChannelStats] 返回值，R49）：
 * 视频/图文/音频为服务端 SearchChatMessages totalCount 真实口径；成员数为
 * GetSupergroupFullInfo.memberCount。任一项为 -1 = 该项探测失败/未知，UI 不显示该项。
 */
data class ChannelStatTriple(
    val videoCount: Int,
    val photoCount: Int,
    val audioCount: Int,
    val memberCount: Int,
) {
    /**
     * R175 频道资源总数（「作品 N」角标的服务端口径）：三类计数已知项求和。
     * 单类未知（-1）按 0 计（该类在统计行本就不显示，总数是诚实下界）；
     * **三类全未知返回 null**——探测只救回了成员数时总数不可知，调用方回落
     * 本地已加载口径并如实标注来源，绝不把 0 当总数骗用户。
     * 纯函数（单测锚：MediaWall 之外的口径家）。
     */
    fun resourceTotalOrNull(): Int? {
        if (videoCount < 0 && photoCount < 0 && audioCount < 0) return null
        return maxOf(0, videoCount) + maxOf(0, photoCount) + maxOf(0, audioCount)
    }
}
