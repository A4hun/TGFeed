package dev.tgfeed.core.plaza

import android.util.Log
import dev.tgfeed.BuildConfig
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.PlazaFindResult
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 频道礼包卡（R130 [PACK] 协议的读侧聚合单元）：一条 addlist 合集链接 +
 * 最新一条安利文案 + 分享人数（按发送者去重）。
 */
data class PlazaPack(
    /** t.me/addlist/<slug> 合集邀请链接（读侧白名单校验过） */
    val url: String,
    /** 安利文案（窗口内最新一条；写入侧截断 [PlazaService.MAX_PACK_PITCH_CHARS]） */
    val pitch: String,
    /** 分享人数（按 TG 发送者去重计数） */
    val sharers: Int,
)

/**
 * 广场快照：一次 fetchPlaza() 的聚合结果，全部为「视频 key → 聚合值」。
 * key 即 VideoItem.key（"tg:<chatId>:<messageId>"），与 Feed 侧天然对齐。
 */
data class PlazaSnapshot(
    /** key → 5 分钟内「在看」人数（按 TG 发送者去重：同一人重复报到只算一次） */
    val watching: Map<String, Int> = emptyMap(),
    /** key → 点赞数（按 TG 发送者去重：一人只算一次） */
    val likes: Map<String, Int> = emptyMap(),
    /** key → 弹幕列表（新 → 旧，内容去重，每 key 最多 10 条） */
    val danmaku: Map<String, List<String>> = emptyMap(),
    /** 频道推荐：chatId → 推荐人数（按发送者去重：同一人重复推荐同频道只算一次） */
    val channelRecommends: Map<Long, Int> = emptyMap(),
    /** 频道互链边：fromChatId → toChatId，全站去重（同一条边多人上报只留一份） */
    val graphEdges: List<Pair<Long, Long>> = emptyList(),
    /** 频道避雷：chatId → (标记人数, 代表原因)；人数按发送者去重，代表原因取最新一条 */
    val channelBlocks: Map<Long, Pair<Int, String>> = emptyMap(),
    /** 频道礼包（R130）：addlist 链接去重后的礼包卡列表（分享人数降序） */
    val channelPacks: List<PlazaPack> = emptyList(),
)

/**
 * TG 广场：用一个公开 TG 超级群组当「去中心化后端」，全站用户互通「在看 / 点赞 / 弹幕」。
 * 零服务器：写入 = 经用户自己的 TG 账号往广场群发一条纯文本协议消息；
 * 读取 = 拉广场群最近 100 条消息，本地聚合。
 *
 * 帖子协议（纯文本，一眼可读；向后兼容——旧客户端读到不认识的行直接忽略）：
 *   [W] tg:<chatId>:<messageId>        —— 在看（采样写入：每 key 每进程一次 + 全局 10s 限速）
 *   [L] tg:<chatId>:<messageId>        —— 点赞
 *   [D] tg:<chatId>:<messageId> 内容   —— 弹幕（内容截断 42 字）
 *   [R] tg:<chatId>                    —— 频道推荐（每频道每进程一次 + 全局 10s 限速）
 *   [E] tg:<fromChatId>-><toChatId>    —— 频道互链边（批量：一条消息最多 20 行边，全局 30s 限速）
 *   [B] tg:<chatId> 原因               —— 频道避雷（原因截断 30 字；每频道每进程一次 + 全局 10s 限速）
 *   [PACK] <t.me/addlist 链接> 文案    —— 频道礼包（R130：合集打包成 TG 原生 addlist
 *                                        邀请链接广播；每合集每进程一次 + 全局 30s 限速）
 *
 * 读取侧信任层：key/chatId 白名单校验，不合规的行丢弃；同一发送者对同一对象重复上报去重；
 * 单发送者在本窗口（拉取的 100 条）内协议消息数超过 10 条视为刷屏，该发送者本次全部忽略。
 *
 * 隐私前提：消息以用户本人 TG 账号公开发送，群内所有人（含非本 App 用户）可见发送者身份，
 * 故总开关 AppPrefs.plazaEnabled 默认关，由用户在设置页显式打开后才读写。
 *
 * 容错策略：全程 runCatching，任何一步失败（无权限 / flood wait / 网络 / 广场被炸）
 * 都静默降级为「无广场」，绝不影响主功能。
 *
 * 构造说明：ensurePlaza 需读取 prefs.plazaChannel / prefs.plazaOwner，故在任务约定的
 * gateway 之外追加注入 AppPrefs（SharedPreferences 轻量包装，多实例共享同一底层存储，安全）。
 */
class PlazaService(
    private val gateway: TdLibGateway,
    private val prefs: AppPrefs,
) {

    companion object {
        private const val TAG = "PlazaService"

        /** 「在看」全局限速：同进程任意两条 [W] 之间的最小间隔（防刷屏 / flood wait） */
        private const val WATCHING_MIN_INTERVAL_MS = 10_000L

        /** 「在看」计数窗口：只有 5 分钟内的 [W] 才算「正在看」 */
        private const val WATCHING_WINDOW_SEC = 5 * 60

        /** 弹幕单条最长字符数（写入侧截断；读取侧同样截断兜底） */
        const val MAX_DANMAKU_CHARS = 42

        /** 每个视频 key 最多保留的弹幕条数（取最新） */
        private const val MAX_DANMAKU_PER_KEY = 10

        /** 每次聚合拉取的广场历史条数 */
        private const val FETCH_LIMIT = 100

        /** 「频道推荐」全局限速：同进程任意两条 [R] 之间的最小间隔 */
        private const val RECOMMEND_MIN_INTERVAL_MS = 10_000L

        /** 「互链边共享」全局限速：两条 [E] 批量消息之间的最小间隔（批量写入，限速更严） */
        private const val EDGE_MIN_INTERVAL_MS = 30_000L

        /** 「频道避雷」全局限速：同进程任意两条 [B] 之间的最小间隔 */
        private const val BLOCK_MIN_INTERVAL_MS = 10_000L

        /** 避雷原因单条最长字符数（写入侧截断；读取侧同样截断兜底） */
        private const val MAX_BLOCK_REASON_CHARS = 30

        /** 「频道礼包」全局限速：同进程任意两条 [PACK] 之间的最小间隔（礼包是增长入口型重写入，同互链边口径取严） */
        private const val PACK_MIN_INTERVAL_MS = 30_000L

        /** 频道礼包安利文案单条最长字符数（写入侧截断；读取侧同样截断兜底） */
        private const val MAX_PACK_PITCH_CHARS = 60

        /** 一条 [E] 批量消息最多合并的边数（多行一条消息，别刷屏） */
        private const val MAX_EDGES_PER_POST = 20

        /** 信任层刷屏阈值：单发送者在本窗口（拉取的 100 条）内协议消息数上限，超过则整窗忽略。R205 起公开：弹幕时间轴解析（PlazaDanmakuTimeline）与 fetchPlaza 同口径单一定义 */
        const val SPAM_PROTOCOL_LIMIT = 10

        /** R70 闸三：广场创建冷却——一次创建尝试（成功/失败都计）后 24h 内不再重复创建 */
        private const val CREATE_COOLDOWN_MS = 24 * 3600_000L

        /** key 格式校验：tg:<chatId>:<messageId>（chatId 可为负）。不合规的行视为群内闲聊，忽略。R205 起公开：[D] 行文法解析（parseDanmakuLine）与快照聚合同口径单一定义 */
        val KEY_REGEX = Regex("""tg:-?\d+:\d+""")

        /** chatId 格式校验：tg:<chatId>（[R]/[B] 用）。不合规的行丢弃 */
        private val CHAT_ID_REGEX = Regex("""tg:-?\d+""")

        /** 互链边格式校验：tg:<fromChatId>-><toChatId>（[E] 用）。不合规的行丢弃 */
        private val EDGE_REGEX = Regex("""tg:(-?\d+)->(-?\d+)""")

        /** 频道礼包链接格式校验（[PACK] 用，读写两侧共用）：
         * t.me/addlist/<slug>（slug 字符集取宽：字母数字下划线连字符）。
         * 非此形态（裸 t.me、频道主页链接、闲聊混入）一律不进礼包车道。
         */
        private val PACK_LINK_REGEX = Regex("""https://t\.me/addlist/[A-Za-z0-9_-]+""")

        /** TL-9 弹幕时间旅行（R205）：回放的有界页数上限（×100 条/页；Get 族不走 Search 配额但守 flood 红线） */
        const val DEFAULT_DANMAKU_TIMELINE_PAGES = 3

        /** 协议行判定：以任一协议标签开头（刷屏检测用；含本版新增协议）。R205 起公开：弹幕时间轴解析与 fetchPlaza 同口径单一定义 */
        fun isProtocolLine(line: String): Boolean =
            line.startsWith("[W] ") || line.startsWith("[L] ") || line.startsWith("[D] ") ||
                line.startsWith("[R] ") || line.startsWith("[E] ") || line.startsWith("[B] ") ||
                line.startsWith("[PACK] ")

        /**
         * "[D] <key> <内容>" → (key, 内容)（弹幕行文法的单一定义）：
         * key 过 [KEY_REGEX] 白名单；内容 trim + 截断 [MAX_DANMAKU_CHARS]；空内容/不合规返回 null。
         * R205 起公开：fetchPlaza 快照聚合与弹幕时间轴解析（PlazaDanmakuTimeline）共用此一处，
         * 不许两边各写一份拆分逻辑。
         */
        fun parseDanmakuLine(line: String): Pair<String, String>? {
            if (!line.startsWith("[D] ")) return null
            val rest = line.removePrefix("[D] ")
            val sp = rest.indexOf(' ')
            if (sp <= 0) return null
            val key = rest.substring(0, sp)
            if (!KEY_REGEX.matches(key)) return null
            val content = rest.substring(sp + 1).trim().take(MAX_DANMAKU_CHARS)
            return if (content.isNotEmpty()) key to content else null
        }
    }

    /** 广场 chatId 进程内缓存：Pair(频道用户名, chatId)；用户名被改后自动重新解析 */
    @Volatile
    private var cached: Pair<String, Long>? = null

    /** 「在看」采样：本进程已写过 [W] 的视频 key（每条内容只写一次） */
    private val watchingPosted = ConcurrentHashMap.newKeySet<String>()

    /** 「在看」限速：上一条 [W] 的写入时间戳（System.currentTimeMillis） */
    @Volatile
    private var lastWatchingAt = 0L

    /** 「频道推荐」采样：本进程已上报过 [R] 的 chatId（每频道只报一次） */
    private val recommendPosted = ConcurrentHashMap.newKeySet<Long>()

    /** 「频道推荐」限速：上一条 [R] 的写入时间戳（原子更新，配合 passThrottle） */
    private val lastRecommendAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 「互链边共享」限速：上一条 [E] 批量消息的写入时间戳 */
    private val lastEdgeAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 「频道避雷」采样：本进程已标记过 [B] 的 chatId（每频道只标一次） */
    private val blockPosted = ConcurrentHashMap.newKeySet<Long>()

    /** 「频道避雷」限速：上一条 [B] 的写入时间戳 */
    private val lastBlockAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 「频道礼包」采样：本进程已发过 [PACK] 的合集签名（"名称|成员id指纹"，每合集只发一次） */
    private val packPosted = ConcurrentHashMap.newKeySet<String>()

    /** 「频道礼包」限速：上一条 [PACK] 的写入时间戳（原子更新，配合 passThrottle） */
    private val lastPackAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 最近一次 fetchPlaza 的聚合快照：plazaTrending() 的纯聚合数据源（不触发网络） */
    @Volatile
    private var lastSnapshot = PlazaSnapshot()

    /** ensurePlaza 并发保护：设置页连点 / 多入口并发时只创建一个广场 */
    private val ensureMutex = Mutex()

    /**
     * 找广场频道并加入，返回 chatId；失败返回 null。
     * 流程（R70 三道闸，修无限建群 bug——根因是 SearchPublicChat 被 429 熔断期
     * 「查询失败」与「不存在」都返回 null，owner 设备每次启动都再建一个广场群）：
     *  0) 闸二·落盘复用：prefs.plazaCreatedChatId 有值且 GetChat 验证仍可访问 → 直接复用，
     *     完全不走 SearchPublicChat（熔断期也畅通）；
     *  1) 闸一·三态查找：按 prefs.plazaChannel 搜公开会话——Found 则 JoinChat（幂等）；
     *     Failed（429/熔断/网络，「查不了」≠「不存在」）→ 本轮直接返回 null 绝不创建；
     *     仅 NotFound 且 prefs.plazaOwner=true 才允许进创建分支；
     *     **R361 开源收口硬闸：release 构建（BuildConfig.DEBUG=false）零创建路径——
     *     正式版广场只找不建（创建=作者 debug 构建专属，用户 2026-10-23 拍板）；
     *     找不到=返回 null 由 UI 诚实提示「官方广场可能暂不可用」，绝不替用户建群**；
     *  2) 闸三·创建冷却：即便真 NotFound，一次创建尝试（成功/失败都先落戳）后 24h 内
     *     不再重复创建（戳落盘 prefs，跨进程有效）；创建成功立即把 chatId 落盘供闸二复用；
     *  3) 主路不通（不存在/创建失败/冷却中）且 prefs.plazaChannelBackup 非空 → 回落备用广场
     *     （只找不建，备群默认已由别人建好）；全不通 → null（设置页据 null 提示「可开启我是创建者」）。
     * 成功结果按 (用户名, chatId) 进程内缓存（回落时缓存备名）；用户名变更后自动重新解析。
     *
     * R361：搜索车道参数 [lane]（默认 BACKGROUND，后台读路径 fetchPlaza/写入兜底原样）——
     * 用户手动开广场（设置页/管理面板的总开关手势）由 UI 传 USER_ACTION：用户动作走最高
     * 优先车道，不被后台收割烧尽的 BACKGROUND 小时份额挤死（账号搜索账本三车道优先级
     * 的本义）。备用广场回落是自动行为，恒走 BACKGROUND 不占用用户动作份额。
     */
    suspend fun ensurePlaza(
        lane: dev.tgfeed.core.td.SearchQuotaLedger.Lane = dev.tgfeed.core.td.SearchQuotaLedger.Lane.BACKGROUND,
    ): Long? = runCatching {
        val channel = prefs.plazaChannel // 读取侧已 trim/去 @、空白回落默认
        val backup = prefs.plazaChannelBackup // 备用广场：可空，空 = 不启用
        // 缓存命中判定：主、备任一名字匹配即复用（上次回落成功时缓存的是备名）
        fun cachedHit(): Long? = cached?.let { (name, id) ->
            if (name.equals(channel, ignoreCase = true) ||
                (backup.isNotEmpty() && name.equals(backup, ignoreCase = true))
            ) id else null
        }
        cachedHit()?.let { return@runCatching it }
        // R70 闸二：创建结果落盘复用——GetChat 验证仍可访问就直接用，完全不再 SearchPublicChat。
        // 验证失败不清落盘值（瞬态失败不该丢复用凭据；群真没了由创建分支覆盖新值）
        suspend fun persistedHit(): Long? {
            val id = prefs.plazaCreatedChatId
            if (id == 0L) return null
            if (!gateway.plazaCheckChat(id)) {
                Log.d(TAG, "落盘广场 chatId=$id 暂不可访问，走正常查找流程")
                return null
            }
            Log.i(TAG, "复用落盘广场: chatId=$id（跳过 SearchPublicChat）")
            gateway.plazaJoin(id) // 幂等：已是成员静默吞掉
            cached = channel to id
            prefs.plazaCreated = true
            return id
        }
        persistedHit()?.let { return@runCatching it }
        ensureMutex.withLock {
            // 双检：等锁期间别的协程可能已解析/创建完
            cachedHit()?.let { return@runCatching it }
            persistedHit()?.let { return@runCatching it }
            // 1) 主广场：三态查找——只有确定「不存在」才允许进创建分支
            var chatId: Long? = null
            var resolvedName = channel
            when (val r = gateway.plazaFindChat(channel, lane)) {
                is PlazaFindResult.Found -> {
                    chatId = r.chatId
                    Log.i(TAG, "广场已存在: @$channel -> chatId=$chatId")
                    // R71 建议：找到也顺手落盘（不只创建分支）——老设备升级后第一次找到
                    // 即持久化，下次启动闸二直接复用零搜索（熔断期对老设备也立即免疫）
                    prefs.plazaCreatedChatId = r.chatId
                }
                is PlazaFindResult.Failed -> {
                    // R70 闸一：查询失败 ≠ 不存在——429/熔断/网络异常时本轮直接返回，绝不创建
                    Log.w(TAG, "广场查询失败（非不存在），本轮不创建: ${r.reason}")
                    return@runCatching null
                }
                PlazaFindResult.NotFound -> {
                    // R361 开源收口硬闸（用户 2026-10-23 拍板）：正式版广场创建=
                    // 作者 debug 构建专属；release 构建只找不建，零创建路径
                    if (!BuildConfig.DEBUG) {
                        Log.w(TAG, "广场 @$channel 未找到；正式版只找不建（创建为作者专属构建通道）")
                    } else if (!prefs.plazaOwner) {
                        Log.w(TAG, "广场 @$channel 不存在，且当前用户不是创建者")
                    } else {
                        // R70 闸三：创建冷却——一次尝试（成功/失败都计）后 24h 内不再重复创建
                        val lastAttempt = prefs.plazaCreateAttemptAtMs
                        val cooldownLeftMs = CREATE_COOLDOWN_MS - (System.currentTimeMillis() - lastAttempt)
                        if (lastAttempt != 0L && cooldownLeftMs > 0) {
                            Log.w(TAG, "广场 @$channel 不存在，但创建冷却中（剩 ${cooldownLeftMs / 3600_000L}h），本轮不创建")
                        } else {
                            Log.i(TAG, "广场 @$channel 不存在，当前用户已标记为创建者，创建中…")
                            // 先落戳再创建：失败也进冷却，任何路径都跑不出 24h 一次的硬上限
                            prefs.plazaCreateAttemptAtMs = System.currentTimeMillis()
                            chatId = gateway.plazaCreateChat(channel)
                            if (chatId != null) {
                                // R70 闸二凭据：创建成功立刻落盘，今后零搜索直接复用
                                prefs.plazaCreatedChatId = chatId
                            } else {
                                Log.w(TAG, "创建广场 @$channel 失败，尝试备用广场")
                            }
                        }
                    }
                }
            }
            // 2) 主路不通 → 备用广场（只找不建；与主名相同则不必重试）
            if (chatId == null && backup.isNotEmpty() && !backup.equals(channel, ignoreCase = true)) {
                when (val rb = gateway.plazaFindChat(backup)) {
                    is PlazaFindResult.Found -> {
                        Log.i(TAG, "主广场不可用，已回落备用广场: @$backup -> chatId=${rb.chatId}")
                        chatId = rb.chatId
                        resolvedName = backup
                    }
                    PlazaFindResult.NotFound ->
                        Log.w(TAG, "备用广场 @$backup 也不存在（备群只找不建）")
                    is PlazaFindResult.Failed ->
                        Log.w(TAG, "备用广场 @$backup 查询失败（非不存在），不回落: ${rb.reason}")
                }
            }
            if (chatId != null) {
                // JoinChat 幂等：已是成员/刚创建完会撞「已是成员」类错误，网关侧静默吞掉
                gateway.plazaJoin(chatId)
                cached = resolvedName to chatId
                // 广场已就绪（找到/创建+加入成功）→ 标记已建，设置页藏"我是广场创建者"开关
                //（建群是一次性的事，建完这个开关再没用了）
                prefs.plazaCreated = true
            }
            chatId
        }
    }.onFailure {
        Log.w(TAG, "ensurePlaza 失败: ${it.message}")
    }.getOrNull()

    /**
     * 「在看」采样写入：每个 key 本进程只写一次，且全局两条 [W] 至少间隔 10s。
     * 不满足采样条件 = 静默跳过（不是错误）。
     */
    suspend fun postWatching(item: VideoItem) {
        if (!prefs.plazaEnabled) return
        if (!KEY_REGEX.matches(item.key)) return // Mock/异常 key 不进广场
        if (!watchingPosted.add(item.key)) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastWatchingAt < WATCHING_MIN_INTERVAL_MS) {
                // 被限速丢弃的 key 不算「已写」：释放标记，用户回看时还有机会补报到
                watchingPosted.remove(item.key)
                return
            }
            lastWatchingAt = now
        }
        post("[W] ${item.key}")
    }

    /** 点赞写入：[L] key。用户手势天然低频，不设额外限速；任何失败静默 */
    suspend fun postLike(item: VideoItem) {
        if (!prefs.plazaEnabled) return
        if (!KEY_REGEX.matches(item.key)) return
        post("[L] ${item.key}")
    }

    /** 弹幕写入：[D] key 内容。内容去换行/连续空白、截断 42 字；空内容直接丢弃 */
    suspend fun postDanmaku(item: VideoItem, text: String) {
        if (!prefs.plazaEnabled) return
        if (!KEY_REGEX.matches(item.key)) return
        val content = text.trim().replace(Regex("""\s+"""), " ").take(MAX_DANMAKU_CHARS)
        if (content.isEmpty()) return
        post("[D] ${item.key} $content")
    }

    /**
     * 频道推荐上报：[R] tg:<chatId>。用户"收"了一个发现候选时调用。
     * 节制：每 chatId 本进程只报一次 + 全局两条 [R] 至少间隔 10s；不满足 = 静默跳过。
     */
    suspend fun postChannelRecommend(chatId: Long) {
        if (!prefs.plazaEnabled) return
        if (chatId == 0L) return // 非法 chatId 不进广场
        if (!recommendPosted.add(chatId)) return
        val now = System.currentTimeMillis()
        if (!passThrottle(lastRecommendAt, now, RECOMMEND_MIN_INTERVAL_MS)) {
            // 被限速丢弃的不算「已报」：释放标记，下次还有机会补报
            recommendPosted.remove(chatId)
            return
        }
        post("[R] tg:$chatId")
    }

    /**
     * 频道互链边共享：本地图谱爬到的边广播给全站做冷启动。
     * 批量：一次最多 [MAX_EDGES_PER_POST] 条边合并成多行一条消息（别刷屏），
     * 全局两条 [E] 批量消息至少间隔 30s；超出 20 条的余量由调用方自行分批择时再报。
     */
    suspend fun postGraphEdges(edges: List<Pair<Long, Long>>) {
        if (!prefs.plazaEnabled) return
        // 白名单 + 去重 + 截断：去掉 0 值/自环/重复边，一次最多 20 条
        val clean = edges
            .filter { (from, to) -> from != 0L && to != 0L && from != to }
            .distinct()
            .take(MAX_EDGES_PER_POST)
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!passThrottle(lastEdgeAt, now, EDGE_MIN_INTERVAL_MS)) return
        val payload = clean.joinToString("\n") { (from, to) -> "[E] tg:$from->tg:$to" }
        post(payload)
    }

    /**
     * 频道避雷标记：[B] tg:<chatId> 原因。原因去换行/连续空白、截断 30 字；空原因直接丢弃。
     * 节制：每 chatId 本进程只标一次 + 全局两条 [B] 至少间隔 10s；不满足 = 静默跳过。
     */
    suspend fun postChannelBlock(chatId: Long, reason: String) {
        if (!prefs.plazaEnabled) return
        if (chatId == 0L) return
        val content = reason.trim().replace(Regex("""\s+"""), " ").take(MAX_BLOCK_REASON_CHARS)
        if (content.isEmpty()) return
        if (!blockPosted.add(chatId)) return
        val now = System.currentTimeMillis()
        if (!passThrottle(lastBlockAt, now, BLOCK_MIN_INTERVAL_MS)) {
            // 被限速丢弃的不算「已标」：释放标记，下次还有机会补报
            blockPosted.remove(chatId)
            return
        }
        post("[B] tg:$chatId $content")
    }

    /**
     * 频道礼包上报（R130 生产侧收口）："[PACK] <t.me/addlist 链接> <安利文案>"。
     * 链接由 TdLibGateway.createChannelPackInviteLink 生成（TG 原生合集邀请链接），
     * 别人点开即「整包收下」成自己的 TG 文件夹——自带增长引擎的广播位。
     *
     * 节制与幂等（比 [R]/[B] 更严，礼包是增长入口型广播，刷屏代价最高）：
     *  - 每合集每进程只发一次：签名 = "合集名|成员 id 排序指纹"（同一合集反复点
     *    分享只算已发；改了成员/改名 = 新签名，允许再发）；
     *  - 全局两条 [PACK] 至少间隔 [PACK_MIN_INTERVAL_MS]（30s，与 [E] 批量同口径）；
     *    被限速丢弃的释放采样标记，下次还有机会补发；
     *  - 文案去换行/连续空白、截断 [MAX_PACK_PITCH_CHARS]；空文案丢弃；
     *  - 链接白名单 [PACK_LINK_REGEX]（必须是 t.me/addlist 形态），防闲话混进协议位。
     *
     * @return true = 已发出或本进程此前已发过（幂等语义）；false = 限速跳过/格式不合格/未开启
     */
    suspend fun postChannelPack(name: String, chatIds: List<Long>, url: String, pitch: String): Boolean {
        if (!prefs.plazaEnabled) return false
        if (!PACK_LINK_REGEX.matches(url)) return false
        val content = pitch.trim().replace(Regex("""\s+"""), " ").take(MAX_PACK_PITCH_CHARS)
        if (content.isEmpty()) return false
        // 合集签名：名称 + 成员 id 排序指纹（对成员顺序变化稳定）
        val signature = "$name|${chatIds.sorted().joinToString(",")}"
        if (!packPosted.add(signature)) return true // 幂等：本进程已发过同款礼包
        val now = System.currentTimeMillis()
        if (!passThrottle(lastPackAt, now, PACK_MIN_INTERVAL_MS)) {
            // 被限速丢弃的不算「已发」：释放标记，下次还有机会补发
            packPosted.remove(signature)
            return false
        }
        post("[PACK] $url $content")
        return true
    }

    /**
     * 拉广场最近 [FETCH_LIMIT] 条消息并聚合（信任层全程生效）：
     * [W] 5 分钟内、按发送者去重计数 → watching；[L] 按发送者去重计数 → likes；
     * [D] 解析「key + 内容」→ danmaku（新→旧，去重，每 key 最多 [MAX_DANMAKU_PER_KEY] 条）；
     * [R] 按发送者去重计数 → channelRecommends；[E] 全站去重 → graphEdges；
     * [B] 按发送者去重计数 + 最新原因 → channelBlocks；
     * [PACK] 链接白名单 + 按发送者去重 + 最新文案 → channelPacks（分享人数降序）。
     * 消息逐行解析（[E] 批量消息多行；旧协议单行语义不变），不认识的行 = 群内闲聊/噪声，忽略——
     * 旧客户端读到新协议行自然走到这里，向后兼容。
     * 刷屏检测：单发送者本窗口内协议消息数超过 [SPAM_PROTOCOL_LIMIT] → 该发送者本次全部忽略。
     * 广场总开关关闭或任何失败都返回空快照。
     */
    suspend fun fetchPlaza(): PlazaSnapshot = runCatching {
        if (!prefs.plazaEnabled) return@runCatching PlazaSnapshot()
        val chatId = ensurePlaza() ?: return@runCatching PlazaSnapshot()
        val rows = gateway.plazaFetchRecentTexts(chatId, FETCH_LIMIT)
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()

        // —— 信任层第一遍：刷屏检测。按消息粒度计数（[E] 批量消息多行只算 1 条协议消息）——
        // R130 修正：[W] 采样行不计入刷屏——[W] 是系统自动采样（每 key 每进程一次 +
        // 全局 10s 限速，写入侧已自律），用户无感知也无从节制；把它计入会把重度观看者
        // 的整窗动态（[R]/[B]/[PACK]）全部拉黑，实测模拟器会话 20+ 条 [W] 即触发。
        // [W] 自身的防洪闸不变（key 去重 + 限速），此处只解除"看多了连累其他上报"的误伤
        val protoMsgCount = HashMap<String, Int>()
        for ((text, _, sender) in rows) {
            if (text.lineSequence().any { isProtocolLine(it) && !it.startsWith("[W] ") }) {
                protoMsgCount[sender] = (protoMsgCount[sender] ?: 0) + 1
            }
        }
        val spamSenders = protoMsgCount.filterValues { it > SPAM_PROTOCOL_LIMIT }.keys
        if (spamSenders.isNotEmpty()) Log.w(TAG, "广场刷屏忽略: $spamSenders")

        // —— 第二遍：逐行解析聚合 ——
        val watchingSenders = LinkedHashMap<String, MutableSet<String>>()
        val likeSenders = LinkedHashMap<String, MutableSet<String>>()
        val danmakuMap = LinkedHashMap<String, MutableList<String>>()
        val recommendSenders = LinkedHashMap<Long, MutableSet<String>>() // chatId → 推荐者集合
        val edgeSet = LinkedHashSet<Pair<Long, Long>>() // 全站去重后的互链边
        val blockSenders = LinkedHashMap<Long, MutableSet<String>>() // chatId → 避雷者集合
        val blockReason = LinkedHashMap<Long, String>() // chatId → 代表原因（rows 新→旧，首个即最新）
        val packSenders = LinkedHashMap<String, MutableSet<String>>() // addlist 链接 → 分享者集合
        val packPitch = LinkedHashMap<String, String>() // addlist 链接 → 最新安利文案（rows 新→旧，首个即最新）
        for ((text, date, sender) in rows) {
            if (sender in spamSenders) continue // 刷屏发送者整窗忽略
            var edgeLines = 0 // 单条消息内已解析的 [E] 行数（防一条消息塞几百行刷边）
            for (line in text.lines()) {
                when {
                    line.startsWith("[W] ") -> {
                        val key = line.removePrefix("[W] ").trim()
                        if (KEY_REGEX.matches(key) && nowSec - date <= WATCHING_WINDOW_SEC) {
                            watchingSenders.getOrPut(key) { mutableSetOf() }.add(sender)
                        }
                    }
                    line.startsWith("[L] ") -> {
                        val key = line.removePrefix("[L] ").trim()
                        if (KEY_REGEX.matches(key)) {
                            likeSenders.getOrPut(key) { mutableSetOf() }.add(sender)
                        }
                    }
                    line.startsWith("[D] ") -> {
                        // R205 文法收口：[D] 行「key+内容」拆分走 parseDanmakuLine 单一定义
                        //（弹幕时间轴解析 PlazaDanmakuTimeline 共用同口径，不许两份实现）
                        parseDanmakuLine(line)?.let { (key, content) ->
                            val list = danmakuMap.getOrPut(key) { mutableListOf() }
                            if (list.size < MAX_DANMAKU_PER_KEY && !list.contains(content)) {
                                list.add(content)
                            }
                        }
                    }
                    line.startsWith("[R] ") -> {
                        val id = parseChatId(line.removePrefix("[R] ").trim()) ?: continue
                        recommendSenders.getOrPut(id) { mutableSetOf() }.add(sender)
                    }
                    line.startsWith("[E] ") -> {
                        edgeLines++
                        if (edgeLines > MAX_EDGES_PER_POST) continue // 超出部分丢弃
                        val m = EDGE_REGEX.matchEntire(line.removePrefix("[E] ").trim()) ?: continue
                        val from = m.groupValues[1].toLongOrNull() ?: continue
                        val to = m.groupValues[2].toLongOrNull() ?: continue
                        if (from != to) edgeSet.add(from to to) // 自环视为噪声
                    }
                    line.startsWith("[B] ") -> {
                        val rest = line.removePrefix("[B] ")
                        val sp = rest.indexOf(' ')
                        if (sp > 0) {
                            val id = parseChatId(rest.substring(0, sp)) ?: continue
                            val reason = rest.substring(sp + 1).trim().take(MAX_BLOCK_REASON_CHARS)
                            if (reason.isNotEmpty()) {
                                blockSenders.getOrPut(id) { mutableSetOf() }.add(sender)
                                // 代表原因取最新一条（rows 新→旧，先入为主）
                                if (!blockReason.containsKey(id)) blockReason[id] = reason
                            }
                        }
                    }
                    line.startsWith("[PACK] ") -> {
                        // 频道礼包（R130）："[PACK] <t.me/addlist 链接> <安利文案>"
                        val rest = line.removePrefix("[PACK] ")
                        val sp = rest.indexOf(' ')
                        if (sp > 0) {
                            val url = rest.substring(0, sp)
                            val pitch = rest.substring(sp + 1).trim().take(MAX_PACK_PITCH_CHARS)
                            if (PACK_LINK_REGEX.matches(url) && pitch.isNotEmpty()) {
                                packSenders.getOrPut(url) { mutableSetOf() }.add(sender)
                                // 安利文案取最新一条（rows 新→旧，先入为主）
                                if (!packPitch.containsKey(url)) packPitch[url] = pitch
                            }
                        }
                    }
                    // 其他纯文本/不认识的协议行 = 群内闲聊/噪声/新版协议，忽略
                }
            }
        }
        val snapshot = PlazaSnapshot(
            watching = watchingSenders.mapValues { it.value.size },
            likes = likeSenders.mapValues { it.value.size },
            danmaku = danmakuMap,
            channelRecommends = recommendSenders.mapValues { it.value.size },
            graphEdges = edgeSet.toList(),
            channelBlocks = blockSenders.mapValues { (id, senders) ->
                senders.size to (blockReason[id] ?: "")
            },
            // 频道礼包：按链接去重聚合，分享人数降序（增长入口位，热者优先）
            channelPacks = packSenders.map { (url, senders) ->
                PlazaPack(url, packPitch[url] ?: "", senders.size)
            }.sortedByDescending { it.sharers },
        )
        lastSnapshot = snapshot // 供 plazaTrending() 纯聚合复用
        snapshot
    }.onFailure {
        Log.w(TAG, "fetchPlaza 失败: ${it.message}")
    }.getOrDefault(PlazaSnapshot())

    /**
     * 全站热榜数据口：基于最近一次 fetchPlaza 快照（即近 [FETCH_LIMIT] 条窗口）的纯聚合，
     * key → 加权热度（[L]×3 + [W]×1），按热度降序。非挂起、不触发网络；
     * 尚未拉取过或广场关闭时返回空列表。UI 后续按 key 反查条目接展示。
     */
    fun plazaTrending(): List<Pair<String, Int>> {
        val snap = lastSnapshot
        val score = HashMap<String, Int>()
        snap.likes.forEach { (key, n) -> score[key] = (score[key] ?: 0) + n * 3 }
        snap.watching.forEach { (key, n) -> score[key] = (score[key] ?: 0) + n }
        return score.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /** 通用全局限速：距上次写入不足 intervalMs 返回 false（调用方据此放弃本次写入），否则记录本次时间并放行 */
    private fun passThrottle(
        last: java.util.concurrent.atomic.AtomicLong,
        now: Long,
        intervalMs: Long,
    ): Boolean = synchronized(last) {
        if (now - last.get() < intervalMs) false else {
            last.set(now)
            true
        }
    }

    /**
     * TL-9 弹幕时间旅行（R205 v1 纯回放）：有界分页拉广场历史 → 解析 [D] 行 →
     * 按真实发布时间升序的时间轴。
     *
     * 边界口径（任务书定死）：
     *  - **纯回放**：只解析弹幕行，不做任何社交聚合（无发送者维度展示、无计数）；
     *  - **有界页数**：[maxPages] × 100 条封顶（默认 [DEFAULT_DANMAKU_TIMELINE_PAGES]=3
     *    页 300 条）——GetChatHistory 是 Get 族不走 Search 配额，但 flood 红线仍在：
     *    回放深度克制，进页触发一次，绝不后台轮询；
     *  - 翻页游标 = 每页最老一行 messageId；返回不足整页 = 到底，提前停；
     *  - 广场未开启/未就绪/失败 → [PlazaDanmakuTimeline.EMPTY]（UI 空态承接）。
     */
    suspend fun fetchDanmakuTimeline(maxPages: Int = DEFAULT_DANMAKU_TIMELINE_PAGES): PlazaDanmakuTimeline =
        runCatching {
            if (!prefs.plazaEnabled) return@runCatching PlazaDanmakuTimeline.EMPTY
            val chatId = ensurePlaza() ?: return@runCatching PlazaDanmakuTimeline.EMPTY
            val rows = ArrayList<PlazaHistoryRow>()
            var fromMessageId = 0L
            var pages = 0
            while (pages < maxPages) {
                val page = gateway.plazaFetchHistoryPage(chatId, fromMessageId, FETCH_LIMIT)
                if (page.isEmpty()) break
                rows += page
                pages++
                if (page.size < FETCH_LIMIT) break // 不足整页 = 到底
                fromMessageId = page.minOf { it.messageId } // 翻页游标 = 本页最老一行
            }
            parseDanmakuTimeline(rows).copy(pagesFetched = pages)
        }.onFailure {
            Log.w(TAG, "fetchDanmakuTimeline 失败: ${it.message}")
        }.getOrDefault(PlazaDanmakuTimeline.EMPTY)

    /** "tg:<chatId>" → chatId；不合规返回 null（读取侧白名单过滤） */
    private fun parseChatId(token: String): Long? =
        if (CHAT_ID_REGEX.matches(token)) token.removePrefix("tg:").toLongOrNull() else null

    /** 写入统一入口：解析广场 chatId + 发送；任何失败静默（调用前已过 plazaEnabled 闸） */
    private suspend fun post(payload: String) {
        runCatching {
            val chatId = ensurePlaza() ?: return@runCatching
            gateway.plazaSendText(chatId, payload)
        }.onFailure { Log.w(TAG, "广场写入失败: ${it.message}") }
    }
}
