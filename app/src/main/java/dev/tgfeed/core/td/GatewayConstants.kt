package dev.tgfeed.core.td

import dev.tgfeed.core.feed.GroupHarvester

internal const val TAG = "TdLibGateway"

/** 深链全链路观测 tag（与 MainActivity 深链路径同一 tag，一条 logcat -s 过滤即可分诊） */
internal const val TAG_FLOW = "TGFlow"
internal const val CONCURRENCY = 5
internal const val MAX_AGGREGATE_CHANNELS = 24

/**
 * R75-A 保底配额位数：每轮 24 池里划出 8 个给"最久未进池"队列（其余照加权抽样）。
 * 500 频道 × 8 位/轮 ≈ 63 轮保证全量至少进池一次——零画像长尾频道从
 * "纯抽奖 4.4%/轮"变成"保底必被轮到"，同时不给加权车道放血过多（2/3 坑位不动）
 */
internal const val GUARANTEED_POOL_SLOTS = 8

/** 轮换采样：命中"最近露脸"记忆的频道权重惩罚系数（候选不足一池时降权回备用） */
internal const val ROTATION_PENALTY = 0.1

/** 无观看数据频道参与配额计算时的探索权重（与 FeedSource 侧 EXPLORE_WEIGHT 一致） */
internal const val EXPLORE_WEIGHT = 0.6

/** 配额下限：权重再低每频道也至少拉 2 条，防止高权重频道把低权重频道"饿死" */
internal const val QUOTA_MIN = 2

/** 配额上限倍率：最高 3×base，防止权重噪声放大成"某频道被拉爆" */
internal const val QUOTA_MAX_FACTOR = 3

/** 弹幕评论单条最长字符数：超长截断（弹幕是短句美学，也避免胶囊比屏宽还宽） */
internal const val MAX_COMMENT_CHARS = 42

/**
 * R80 变体档防呆：明显不是视频的本地路径扩展名（图片/文本类）。
 * 变体档 fileId 无 uniqueId 可验，会话漂移错发时按扩展名兜底识别
 */
internal val NON_VIDEO_PATH_RX = Regex("""\.(jpe?g|png|webp|gif|bmp|heic|txt|json|html?)$""", RegexOption.IGNORE_CASE)

/** 评论级联限流：每频道每页（fetchFromChannel 一次调用）最多级联的帖子数。
 *  级联 = 每帖一次 GetMessageThreadHistory，是最容易撞 flood wait 的增量请求；
 *  频率风险压不住就下调为 1 */
internal const val COMMENT_CASCADE_MAX_PER_PAGE = 3

/** H1 评论级联聚合页级总闸：一页聚合（fetchAggregated 一次调用）内全部频道
 *  共享的级联总次数上限。原实现"每频道各 3 次"在 24 频道下最坏 72 次级联/页；
 *  改为整页封顶 8 次（先到先得，高权重频道并发上天然先抢到配额） */
internal const val AGGREGATE_CASCADE_BUDGET_PER_PAGE = 8

/** H1 listChannels 结果缓存 TTL（5 分钟）：聚合每页都要频道清单，
 *  全量枚举 = LoadChats 全量加载 + GetChats×(2+文件夹数) + 逐 chat GetChat，不能每页都付 */
internal const val CHANNEL_LIST_TTL_MS = 5 * 60_000L

/**
 * R88 频道枚举去上限（用户拍板："就不应该设置什么频道枚举上限"）：
 * LoadChats 单页步长。逐列表循环加载直到 TDLib 报 404（全量加载完成）。
 */
internal const val CHAT_ENUM_PAGE = 200

/** R88 频道枚举去上限：LoadChats 全量加载后 GetChats 一次全取的 limit（覆盖万级会话） */
internal const val CHAT_ENUM_ALL_LIMIT = 100_000

/** P1-4 授权等待超时（毫秒）：超时后 awaitAuthorized 转 Error 态并抛错，不永久挂起 */
internal const val AUTH_TIMEOUT_MS = 30_000L

/** 评论级联：每个讨论线程从最新开始拉取的回复条数（资源帖的资源通常沉在头部） */
internal const val COMMENT_CASCADE_REPLY_LIMIT = 20

/** 形态B 批注关联：一条文字帖最多关联的紧随（更旧）空 caption 媒体条数，
 *  防跨批误关联（资源频道连发多批时，批与批之间靠下一条文字帖断开） */
internal const val ANNOTATE_MAX_MEDIA = 6

/** R257b 标题回退链·链③转发原帖 caption：单频道页级批查预算（页级原子闸先到先得，
 *  耗尽后链自然落④/⑤——回退链唯一发网络请求的环，必须收口） */
internal const val FORWARD_CAPTION_MAX_PER_PAGE = 3

/** R257b 链③聚合页共享预算：24 频道并发各查会放大成请求风暴
 * （评论级联 H1 页级共享原子闸同款模式） */
internal const val AGGREGATE_FORWARD_BUDGET_PER_PAGE = 8

/** R257b 链③原帖 caption TTL 缓存有效期：成功与负缓存（失败/无 caption）同口径 */
internal const val FORWARD_CAPTION_TTL_MS = 30 * 60_000L

/** R257b 链③缓存容量上限：清过期后仍超容则全清重建（缓存重建便宜） */
internal const val FORWARD_CAPTION_CACHE_MAX = 512

/** 形态A 跨页相册 caption 缓存：每频道最多记忆的相册数（LinkedHashMap 插入序掐最老，
 *  防长会话膨胀；一个会话内单频道实际触达的相册数远小于此，够用即可） */
internal const val ALBUM_CAPTION_CACHE_MAX = 120

/** 搜索机器人：回复等待上限（毫秒）。12 秒没等到带链接的回复就放弃，返回空表 */
internal const val BOT_REPLY_TIMEOUT_MS = 12_000L

/** 搜索机器人：GetChatHistory 轮询间隔（毫秒） */
internal const val BOT_POLL_INTERVAL_MS = 1_000L

/** 搜索机器人：每轮拉取的会话历史条数（机器人回复排在最新，30 条足够覆盖） */
internal const val BOT_HISTORY_LIMIT = 30

/** 搜索机器人：单次查询最多解析的 t.me 链接数（防 flood wait/请求风暴）。
 *  分页收割（静默窗口 + 按钮翻页，最多 [BOT_MAX_PAGES] 页）后单机器人
 *  可产出更多链接，10 → 100 */
internal const val BOT_MAX_LINKS = 100

/** 搜索机器人：多消息收割静默窗口（毫秒）。首条带链接回复出现后不收工，
 *  持续轮询直到回复集合（含编辑——机器人常拆多条发送，翻页时也会编辑原消息）
 *  这么久无变化，才认为本页结果到齐 */
internal const val BOT_QUIET_WINDOW_MS = 1_500L

/** 搜索机器人：按钮翻页最多点击次数（硬上限，防机器人无限分页拖死协程）。
 *  另有提前判停：连续 [BOT_STALE_PAGES_STOP] 页无新增链接即停，
 *  正常搜索不会真翻满 30 页 */
internal const val BOT_MAX_PAGES = 30

/** 搜索机器人：连续多少页翻页后无新增链接就提前收工（空转判停） */
internal const val BOT_STALE_PAGES_STOP = 2

/** 搜索机器人：点击翻页按钮后等机器人响应（编辑原消息/新发消息）的时限（毫秒） */
internal const val BOT_PAGE_TIMEOUT_MS = 6_000L

/** 搜索机器人："下一页"类按钮文本关键词（小写 contains 匹配，命中其一即翻页候选） */
internal val BOT_NEXT_BUTTON_KEYWORDS = listOf("下一页", "更多", "next", "more")

/** R60 应答页脚"共 N 条结果"总数解析（soso 功能地图实证形态：🔍 共 N 条结果，当前第 x 页，共 y 页） */
internal val BOT_FOOTER_TOTAL_REGEX = Regex("""共\s*([\d,，\s]+?)\s*条结果""")

/** R60 应答页脚页码·紧凑形态："第 3/12 页"（热词榜/榜单同款"（第 x/10 页）"） */
internal val BOT_FOOTER_PAGE_SLASH_REGEX = Regex("""第\s*(\d+)\s*[/／]\s*(\d+)\s*页""")

/** R60 应答页脚页码·展开形态："当前第 1 页，共 67 页" */
internal val BOT_FOOTER_PAGE_VERBOSE_REGEX = Regex("""第\s*(\d+)\s*页[^\d]{0,8}共\s*(\d+)\s*页""")

/** R60 自指深链的 start= 查询参数特征（[?&]start= 精确匹配——startgroup 拉群链不误伤） */
internal val SELF_REF_START_PARAM_REGEX = Regex("""[?&]start=""", RegexOption.IGNORE_CASE)

/** 搜索机器人：链接解析（GetMessageLinkInfo）并发上限（5 路，防 flood wait） */
internal const val BOT_LINK_CONCURRENCY = 5

/** 搜索机器人：备用机器人最多尝试个数（主机器人超时/空结果后才按序轮换） */
internal const val BOT_MAX_FALLBACKS = 2

/** 搜索机器人：结果进程内缓存 TTL（10 分钟）；命中直接返回，不再问机器人 */
internal const val BOT_CACHE_TTL_MS = 10 * 60_000L

/** R65 热词榜收割：单个子榜最多翻页页数（soso 功能地图实证 40 词/页 × 2 页/子榜；
 *  硬顶留一页余量，"无下一页按钮"才是真正的收工信号） */
internal const val HOTWORD_MAX_PAGES_PER_BOARD = 3

/** R65 热词清洗：词最长字符（与 GroupHarvester.MAX_KEYWORD_CHARS 同口径） */
internal const val HOTWORD_MAX_WORD_CHARS = 64

/** R65 板面按钮诊断日志的值白名单键（结构参数才落值，其余键只落键名） */
internal val BOARD_LOG_VALUE_WHITELIST = setOf("m", "b", "page", "f", "t")

/** 搜索机器人：纯文本兜底正则（机器人回复里没标实体的 t.me 链接） */
internal val TME_LINK_REGEX = Regex("""(?:https?://)?t\.me/[\w/]+""")

/** 发现页·相似频道递归：GetChatSimilarChats 查询并发上限（防 flood wait） */
internal const val DISCOVER_SIMILAR_CONCURRENCY = 3

/** 发现页·相似频道递归：单会话（单网关实例生命周期）内 GetChatSimilarChats 查询总闸。
 *  预算口径见 DISCOVERY-SIGNAL 方案二：5 种子 × (1+~2 层) ≈ 10~15 次/会话 */
internal const val DISCOVER_SIMILAR_MAX_CALLS = 15

/** 金矿 P3·searchViaGroup：单次批量解析的答案链接上限（答案库单词 ≤30 条，取前 20 解析） */
internal const val GROUP_ANSWER_RESOLVE_MAX = 20

/** 答案区"识别中…"标题行懒解析单批上限（SearchPublicChat 换真标题：
 *  比内容卡解析便宜但仍计预算。bot 一次秒回可达 28 条频道链接（实证），
 *  4 个/批只能出 4 行真标题、其余全挂裸 @用户名——频道链接分流后标题解析
 *  是它们的唯一呈现链，提到 12 个/批；3 路并发串行不变慢、仍远低 flood 阈） */
const val ANSWER_TITLE_RESOLVE_MAX = 12

/** 答案区 eager 解析并发上限（GetMessageLinkInfo 2~3 口径取 3，防解析风暴撞 flood wait） */
internal const val ANSWER_INLINE_RESOLVE_CONCURRENCY = 3

/** 按钮标题线索的泛标签黑名单（"打开/进入"类按钮文本不是频道名，不收做标题线索） */
internal val GENERIC_HINT_LABELS = setOf(
    "打开", "进入", "查看", "链接", "点击", "点击打开", "打开频道", "进入频道", "点击进入",
    "立即前往", "直达", "open", "join", "click", "view", "go", "link",
)

/**
 * 行尾成员数 token 剥离（jisou 实证答案行"📢频道名 184k"形态）：
 * \S{0,3} 容忍数字前的 emoji 装饰（如"🔞133k"），\d+(\.\d+)?[kK] 覆盖
 * 184k / 340.2K 小数形态。锚定行尾，频道名中间的 "k" 字样不误伤。
 */
internal val MEMBER_COUNT_TAIL_REGEX = Regex("""\s*\S{0,3}\d+(\.\d+)?[kK]$""")

/** 成员数 token 内的数值段（R48 解析用）：从 [MEMBER_COUNT_TAIL_REGEX] 命中段里取"数字+k/K" */
internal val MEMBER_COUNT_NUM_REGEX = Regex("""\d+(\.\d+)?[kK]""")

/** 文本行配对的上一行引导语黑名单（"找到以下频道："这类引导语不是标题） */
internal val NON_TITLE_PREV_LINES = setOf(
    "找到以下", "为您找到", "搜索结果", "相关频道", "结果如下", "以下是", "为你找到",
    "搜索到以下", "共找到", "共搜索到", "找到",
)


/** 搜索联动·秒回监听：单实体监听窗口硬顶（毫秒）。bot 一般秒回，12 秒收不齐按超时静默收场 */
internal const val LIVE_ASK_WINDOW_MS = 12_000L

/** 搜索联动·秒回监听：应答集合（id+编辑时间签名）稳定这么久即提前收工（秒回场景不必撑满窗口） */
internal const val LIVE_ASK_QUIET_MS = 2_500L

/** 搜索联动·秒回监听：GetChatHistory 轮询间隔（毫秒）（与 searchViaBot 同口径 1s） */
internal const val LIVE_ASK_POLL_MS = 1_000L

/** 搜索联动：单次显式提交最多发词的实体数（KIND_BOT 优先、搜索群其次；防一次提交广播全网） */
internal const val LIVE_ASK_MAX_ENTITIES = 3

/**
 * 搜索联动·秒回专用预算额度（次实体轮/小时，滑动窗口）。额度依据：
 *  - 单实体一轮成本 = 1 发送 + ≤12 轮询 ≈ 13 次请求，60 轮 ≈ 780 次/小时上界
 *    （实际远低：应答签名静默 2.5s 提前收工，正常一轮只轮询 3~5 次）；
 *  - 用户侧节奏上限：一词一轮 10min 冷却 × 单轮封顶 [LIVE_ASK_MAX_ENTITIES] 实体
 *    → 每小时理论上限 6 词 × 3 实体 = 18 轮，60 额度 ≈3 倍冗余——既不纵欲也不饿死；
 *  - 与收割车道（floodHarvestPerHour，后台爬取）彻底解耦：后台把收割预算吃光
 *    也不影响用户主动秒问（实测三实体全被收割预算饿死的教训）。
 */
internal const val LIVE_ASK_BUDGET_PER_HOUR = 60

/** listDiscoveryProfiles 进程内缓存 TTL（1 小时）：发现重扫节奏本来就是每小时一次，
 *  全量枚举 = GetChats×(2+文件夹数) + 逐 chat GetChat，无需比重扫更勤地重建档案 */
internal const val DISCOVERY_PROFILES_TTL_MS = 3_600_000L

/**
 * R197 群选择器（发现页「我的搜索群」sheet）全量群枚举缓存 TTL（5 分钟）：
 * LoadChats 全量 + GetChats×(2+文件夹) + 逐 chat GetChat + GetSupergroup/GetBasicGroup
 * 成员数（数百次 JNI 往返）——sheet 是用户点开的低频动作，TTL 内重开秒回；
 * 5 分钟口径与 listChannels 的 CHANNEL_LIST_TTL_MS 同族（新加入的群最迟 5 分钟可见）。
 */
internal const val HARVEST_PICKER_TTL_MS = 5 * 60_000L

// ---------------- R97 存储治理（R196 调度收编进 StorageGovernor） ----------------

/** 启动前 temp 清扫口径：此刻 TDLib 尚未开库，2 小时前的 temp 必是死残留。
 *  R196 从 24h 收紧（旧口径实锤放走当日全部残骸——用户机 3GB 现场）；当日
 *  更新的残骸留给运行期 StorageGovernor 的精细清扫（活跃排除 + DeleteFile 一致删），
 *  那才是主力。运行期口径常量在 StorageGovernor.kt（含压力激进口径），勿在此重复定义 */
internal const val TEMP_STALE_PREINIT_MS = 2 * 3_600_000L

/** 消息库预算下限：即便缓存限额调得很小，也给消息库留 256MB 保底，防频繁触发重置 */
internal const val DB_BUDGET_MIN_BYTES = 256L * 1024 * 1024

/** 统计失真修复：GetStorageStatistics 的 byChat 明细只按体积返回前 N 个会话，
 *  旧值 100 对数百频道的大号严重漏计（AUDIT-EDGE 已立案）；1000 为官方文档上限 */
internal const val STORAGE_STATS_CHAT_LIMIT = 1000

// ---------------- R244 新设备登录链路修复（P0：新机装包后连不上 TG） ----------------

/**
 * R244 本地 SOCKS5 端口探测清单（顺序即优先级）：
 * Clash for Android 混合口 7890 / Clash SOCKS5 7891 / Clash Verge 7897（本项目默认值同款）/
 * v2rayNG 10808 / 通用 SOCKS5 1080。
 * 背景：TDLib 原生库不读 Android 系统 HTTP 代理——手机上装了 Clash/V2Ray（系统级代理
 * 软件）也必须显式 AddProxy 才走代理，否则直连被墙；用户实锤「app 走直连→连不上」
 * 的根因即此。探测到任一端口即可自动接入（用户装代理软件的意图就是走代理）。
 */
internal val LOCAL_SOCKS_PROBE_PORTS = intArrayOf(7890, 7891, 7897, 10808, 1080)

/** R244 本地端口探测超时（毫秒）：本地回环无网络往返，150ms 秒判（连不通即拒） */
internal const val LOCAL_SOCKS_PROBE_TIMEOUT_MS = 150

/** R244 配置代理探测超时（毫秒）：可能是局域网/远程地址，给足 1500ms */
internal const val CONFIGURED_PROXY_PROBE_TIMEOUT_MS = 1_500

/**
 * R244 登录请求网络监视窗（毫秒）：TDLib 网络不通时对 auth 系请求
 * （SetAuthenticationPhoneNumber / CheckAuthenticationCode / RequestQrCodeAuthentication）
 * **不报错只挂起**（请求在 TDLib 内部等网络）——UI 表现「发送中…」后无任何下文
 * （用户实锤③「静默失败」）。旁路监视窗内没等到任何成功/失败态且 TG 链路未就绪
 * → 写明确网络错误。20s 取国际链路+代理慢的宽松上限（真错误如「手机号格式不对」
 * TDLib 秒回，不受监视影响）。
 */
internal const val AUTH_NET_WATCH_MS = 20_000L

/** R244 网络不可达的用户文案（登录三步 + 扫码发起共用单一真源） */
internal const val AUTH_NET_ERROR_TEXT = "无法连接到 Telegram 服务器，请检查网络/代理设置"
