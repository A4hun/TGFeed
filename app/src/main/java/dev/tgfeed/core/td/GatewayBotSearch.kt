package dev.tgfeed.core.td

import android.util.Log
import dev.tgfeed.core.feed.AdBlacklist
import dev.tgfeed.core.feed.AnswerStore
import dev.tgfeed.core.feed.HintTitle
import dev.tgfeed.core.feed.GroupHarvester
import dev.tgfeed.core.prefs.AppPrefs
import io.github.tdlibandroid.ktx.TdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi

// ---------------- 搜索机器人（第三方全网资源搜索） ----------------

/**
 * 经由第三方 TG 搜索机器人（如"极搜" jisou）做全网资源搜索。
 *
 * 背景原理：这类搜索机器人用爬虫账号遍历公开频道抓消息、自建搜索引擎；
 * 用户把关键词私聊发给机器人，机器人回复匹配的频道/帖子 t.me 链接列表。
 * 本方法的集成路径：TDLib 私聊机器人发关键词 → 轮询读回复 → 解析回复里的
 * t.me 链接 → GetMessageLinkInfo 把链接解析回消息 → 复用 toVideoItem 出可播条目。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * SearchPublicChat(String username) -> Chat{id: long, type: ChatType, title: String}
 * SendMessage(long chatId, MessageTopic topicId, InputMessageReplyTo replyTo,
 *             MessageSendOptions options, ReplyMarkup replyMarkup, InputMessageContent)
 *   -> Message{id: long, isOutgoing: boolean, date: int, content: MessageContent, ...}
 *   —— 注意鉴别：1.8.67 是 6 参版本（含 ReplyMarkup replyMarkup），
 *      比任务假设的 5 参版本多一个参数；可空对象参数统一传 null
 *      （与本文件 SearchChatMessages/ForwardMessages 的既有用法一致）
 * InputMessageText(FormattedText text, LinkPreviewOptions linkPreviewOptions, boolean clearDraft)
 * FormattedText(String text, TextEntity[] entities)（entities 传空数组，不传 null）
 * GetChatHistory(long chatId, long fromMessageId, int offset, int limit, boolean onlyLocal)
 *   -> Messages{totalCount, messages: Message[]}（与 fetchFromChannel 同一签名）
 * GetMessageLinkInfo(String url) -> MessageLinkInfo{isPublic, chatId, message: Message, ...}
 *   —— 频道主页类链接（无消息 id）message 字段为 null，解析侧跳过
 * TextEntity{offset: int, length: int, type: TextEntityType}；
 * TextEntityTypeUrl（标记型，URL 取原文子串）/ TextEntityTypeTextUrl{url: String}
 * Message.replyMarkup: ReplyMarkup；
 * ReplyMarkupInlineKeyboard{rows: InlineKeyboardButton[][], forceReply: boolean}；
 * InlineKeyboardButton{text: String, iconCustomEmojiId: long, style: ButtonStyle,
 *                      type: InlineKeyboardButtonType}；
 * InlineKeyboardButtonTypeUrl{url: String}（按钮链接在此，其余按钮类型无 url）；
 * InlineKeyboardButtonTypeCallback{data: byte[]}（回调按钮 payload，翻页按钮在此）；
 * GetCallbackQueryAnswer(long chatId, long messageId, CallbackQueryPayload payload)
 *   -> CallbackQueryAnswer{text: String, showAlert: boolean, url: String}
 *   —— 注意鉴别：本 AAR 没有 ClickInlineKeyboardButton（javap 实证找不到类；
 *      它是后续 TDLib 版本对同一 TL 方法的改名），GetCallbackQueryAnswer
 *      是等价前身，CallbackQueryPayloadData(byte[] data) 包裹按钮 payload
 *
 * 增强行为：
 * 1) 结果进程内缓存：key = "主机器人:关键词"（trim+小写），TTL 10 分钟，命中直接返回；
 * 2) 备用机器人轮换：主机器人超时/无结果/抛错时，按序试 AppPrefs.searchBotFallbacks
 *    里的备用机器人（去 @/空白、与主去重、列表去重，最多 [BOT_MAX_FALLBACKS] 个），
 *    每个给完整 [BOT_REPLY_TIMEOUT_MS] 窗口，日志记录结果来自哪个机器人；
 * 3) 链接提取覆盖 inline 键盘按钮（很多机器人把结果放按钮里，见 extractTmeLinks）；
 * 4) GetMessageLinkInfo 解析 [BOT_LINK_CONCURRENCY] 路并发（原串行，10 条最坏 10×单条耗时）；
 * 5) 多消息收割：首条带链接回复出现后不收工，等 [BOT_QUIET_WINDOW_MS] 静默窗口
 *    （回复集合含编辑不再变化）再汇总——机器人拆多条发的结果不再漏收；
 * 6) 按钮翻页：回复的 replyMarkup 里有文本含"下一页/更多/next/more"（大小写不敏感）
 *    的回调按钮时点击翻页（GetCallbackQueryAnswer，签名见上方鉴别），点击后等
 *    [BOT_PAGE_TIMEOUT_MS] 让机器人编辑/新发下一页。最多 [BOT_MAX_PAGES] 页；
 *    提前判停：连续 [BOT_STALE_PAGES_STOP] 页无新增链接即收工，正常搜索不会翻满上限。
 *
 * 失败语义：所有候选机器人都没产出才返回空表，绝不向上抛。
 * 注意：单机器人最坏等待 ≈ [BOT_REPLY_TIMEOUT_MS] + [BOT_MAX_PAGES]×[BOT_PAGE_TIMEOUT_MS]
 * （默认 12+180≈192 秒硬保险丝；实际靠空转判停会早得多收工），
 * 轮换再乘以候选数（默认最多 3 个候选），外加链接解析耗时。
 */
internal suspend fun TdLibGateway.searchViaBotImpl(botUsername: String, query: String): List<VideoItem> = runCatching {
    awaitAuthorized()
    val mainBot = botUsername.trim().removePrefix("@")
    require(mainBot.isNotEmpty() && query.isNotBlank()) { "机器人用户名或关键词为空" }

    // 1) 缓存命中直接返回（过期条目顺手清理，防表无限膨胀）
    val cacheKey = "${mainBot.lowercase()}:${query.trim().lowercase()}"
    searchBotCache[cacheKey]?.let { (timestamp, items) ->
        if (System.currentTimeMillis() - timestamp < BOT_CACHE_TTL_MS) {
            Log.d(TAG, "searchViaBot: 命中缓存 $cacheKey（${items.size} 条）")
            return@runCatching items
        }
        searchBotCache.remove(cacheKey)
    }

    // 2) 候选机器人：主 + 备用（AppPrefs 逗号分隔；去 @/空白、与主去重、列表去重，
    //    最多 BOT_MAX_FALLBACKS 个备用）
    val fallbacks = appPrefs.searchBotFallbacks
        .split(',')
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotEmpty() && !it.equals(mainBot, ignoreCase = true) }
        .distinct()
        .take(BOT_MAX_FALLBACKS)
    val candidates = listOf(mainBot) + fallbacks

    // 3) 依次尝试：任一候选出结果即收工写缓存；日志记录结果来自哪个机器人
    for ((index, bot) in candidates.withIndex()) {
        val items = queryBotOnce(bot, query)
        // R44：SearchPublicChat 熔断静默/刚撞 429（null）——不轮换备用 bot 直接空结果
        // 收场（旧版"429→换下一个 bot 打满 3 发"是撞墙放大器：封号级限流下每次用户
        // 搜索把全部候选各打 1 发，结果缓存只存非空又导致每次重搜重新打满）
        if (items == null) {
            Log.w(TAG, "searchViaBot: SearchPublicChat 熔断/半开限量中（剩 ${floodBreaker.remainingSec(FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT)}s），不轮换备用 bot，本轮空结果")
            return@runCatching emptyList()
        }
        if (items.isNotEmpty()) {
            Log.i(TAG, "searchViaBot: 结果来自 @$bot（第 ${index + 1}/${candidates.size} 个候选）q=$query 共 ${items.size} 条")
            searchBotCache[cacheKey] = System.currentTimeMillis() to items
            return@runCatching items
        }
        if (index < candidates.lastIndex) {
            Log.w(TAG, "searchViaBot: @$bot 超时/无结果，轮换备用机器人 @${candidates[index + 1]}")
        }
    }
    Log.w(TAG, "searchViaBot: ${candidates.size} 个候选机器人均无结果 q=$query")
    emptyList()
}.onFailure {
    Log.w(TAG, "searchViaBot 失败 bot=$botUsername q=$query: ${it.message}")
}.getOrDefault(emptyList())

/**
 * 对单个机器人执行一次完整查询：解析会话 → 发关键词 → 静默窗口收割回复
 * （含"下一页/更多"按钮翻页，最多 [BOT_MAX_PAGES] 页、连续 [BOT_STALE_PAGES_STOP]
 * 页无新增链接即提前收工）→ 提取链接 → 并发解析。
 * 任何一步失败（机器人不存在/未回复/超时/链接解析失败）都返回空表，
 * 由 searchViaBot 决定是否轮换下一个候选机器人。
 *
 * R44：返回值改为可空——null 专指"SearchPublicChat 熔断静默/刚撞 429"，
 * searchViaBot 据此**停止轮换备用 bot 直接收场**（封号级限流下"429→换下一个 bot
 * 打满 3 发"是撞墙放大器）；空表仍是普适失败语义（可轮换）。
 */
internal suspend fun TdLibGateway.queryBotOnce(username: String, query: String): List<VideoItem>? = runCatching {
    // R44 熔断闸（先于预算记账）：SearchPublicChat 静默中不烧预算令牌、一发不打
    val searchFamily = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    if (floodBreaker.isSilenced(searchFamily)) {
        Log.d(TAG, "searchViaBot: @$username SearchPublicChat 熔断静默中（剩 ${floodBreaker.remainingSec(searchFamily)}s），不发不记账")
        return@runCatching null
    }
    // 0) flood 查询车道记账（P0 防守）：每个候选机器人的完整查询会话记 1 次；
    //    额度上限逐次从 prefs 刷新（设置改动免重启）；预算耗尽静默降级空结果——
    //    防风控优先于单次搜索成功率
    floodBudgetQuery.maxOpsPerHour = appPrefs.floodQueryPerHour
    if (!floodBudgetQuery.tryAcquire()) {
        Log.w(TAG, "searchViaBot: @$username 超出每小时查询预算，本轮降级为空")
        return@runCatching emptyList()
    }
    // 1) 解析机器人会话（SearchPublicChat 同时承担"打开会话"的作用，后续才能发消息）。
    //    R44+R58+R184 过闸：null 时再看一眼闸——熔断/半开限量/账号预算拒（429 已上报）
    //    返回 null 叫停轮换；其余失败（网络类等）回空表保留既有轮换语义
    val chat = sendSearchPublicChatGuarded(username, SearchQuotaLedger.Lane.USER_ACTION)
    if (chat == null) {
        if (floodBreaker.stateOf(searchFamily) != FloodCircuitBreaker.BreakerState.CLOSED) {
            Log.w(TAG, "searchViaBot: @$username 触发 SearchPublicChat 限流熔断/半开限量，本候选收枪")
            return@runCatching null
        }
        Log.w(TAG, "searchViaBot: @$username 会话解析失败，本候选降级为空")
        return@runCatching emptyList()
    }
    Log.d(TAG, "searchViaBot: @$username -> chatId=${chat.id} (${chat.title})")

    // 2) 发送关键词。发送时间戳用于区分"本次查询的回复"与机器人的历史消息
    //    R364：超时兜底——隐性限流挂起时 12s 到点按本候选失败回落（error→runCatching
    //    →空表，轮换语义不变），协程不再永挂
    val startSec = (System.currentTimeMillis() / 1000L).toInt()
    withTdTimeout(what = "searchViaBot/SendMessage @$username") {
        client.send(
            TdApi.SendMessage(
                chat.id, null, null, null, null,
                TdApi.InputMessageText(TdApi.FormattedText(query, emptyArray()), null, false),
            )
        )
    } ?: error("TDLib 请求超时（可能限流）")

    // 3) 收割回复（两阶段：等首条带链接回复 → 静默窗口收割 + 按钮翻页）。
    //    原实现"见到第一条带链接回复就收工"会漏收两类分页结果：
    //    a) 机器人把结果拆成多条消息依次发送（后续消息被丢弃）；
    //    b) 结果页带"下一页/更多"回调按钮，要点按钮机器人才编辑/新发下一页。
    //    只认"非自己发的（!isOutgoing，天然排除刚发的关键词）且时间戳不早于发送时刻"的消息；
    //    回复表按消息 id 归并——GetChatHistory 每次拉到的是消息最新状态，
    //    覆盖旧拷贝即天然吸收"翻页时编辑原消息"的形态。
    val startMs = System.currentTimeMillis()
    val firstReplyDeadline = startMs + BOT_REPLY_TIMEOUT_MS
    // 硬保险丝：首条等待 + 每页响应预算，防机器人无限分页拖死协程
    // （实际收工远早于此——见下方空转判停，连续 BOT_STALE_PAGES_STOP 页无新增链接即停）
    val hardDeadline = firstReplyDeadline + BOT_MAX_PAGES * BOT_PAGE_TIMEOUT_MS
    val repliesById = LinkedHashMap<Long, TdApi.Message>()
    val extraLinks = ArrayList<String>() // 按钮应答（CallbackQueryAnswer.url）附带的链接
    val clickedPayloads = HashSet<String>() // 已点过的按钮 payload key，防按钮不变时死循环
    var lastSignature = ""
    var lastChangeMs = 0L
    var firstLinkSeenMs = 0L
    var pagesClicked = 0
    var pageClickMs = 0L // 最近一次点击翻页按钮的时刻；>0 表示在等机器人翻页响应
    var lastPageLinkCount = 0 // 上次翻页前已收割的去重链接数（空转判停用）
    var stalePages = 0 // 连续"翻页后无新增链接"的页数
    var lastFooterTotal = -1 // R60 页脚"共 N 条结果"最近一次观测值（总数变化才记日志，防刷屏）
    while (true) {
        val now = System.currentTimeMillis()
        if (now >= hardDeadline) break
        if (firstLinkSeenMs == 0L && now >= firstReplyDeadline) break
        delay(BOT_POLL_INTERVAL_MS)
        val history = runCatching {
            // R364：超时兜底——单轮拉取挂起 12s 到点按 null 计（与发送失败同口径等下一轮；
            // 收割环硬截止 firstReplyDeadline/hardDeadline 原样兜底，超时只是单轮上限闸）
            withTdTimeout(what = "searchViaBot/GetChatHistory @$username") {
                client.send(TdApi.GetChatHistory(chat.id, 0, 0, BOT_HISTORY_LIMIT, false))
            }
        }.getOrNull() ?: continue // 单轮拉取失败/超时不计数，等下一轮
        history.messages.filterNotNull()
            .filter { !it.isOutgoing && it.date >= startSec - 3 }
            .forEach { repliesById[it.id] = it }
        val replies = repliesById.values.toList()
        // 变化签名 = 消息 id + 编辑时间戳 + 链接 + 回调按钮：
        // 机器人编辑翻页时 editDate/内容/按钮会变，签名随之变，静默计时重置
        val signature = replies.joinToString("|") { m ->
            "${m.id}:${m.editDate}:${extractTmeLinks(m).joinToString(",")}:${callbackButtonsSignature(m)}"
        }
        if (signature != lastSignature) {
            lastSignature = signature
            lastChangeMs = System.currentTimeMillis()
            pageClickMs = 0L // 有新变化即认为翻页响应已到达（或正常的新回复）
            if (firstLinkSeenMs == 0L && replies.any { extractTmeLinks(it).isNotEmpty() }) {
                firstLinkSeenMs = lastChangeMs
                Log.d(TAG, "searchViaBot: @$username 首条带链接回复出现，转入静默窗口收割")
            }
            continue
        }
        if (firstLinkSeenMs == 0L) continue // 还在"等首条带链接回复"阶段
        // 静默判定：平时用短窗口（BOT_QUIET_WINDOW_MS）；点击翻页后放宽到
        // BOT_PAGE_TIMEOUT_MS，给慢机器人编辑/新发下一页的时间
        val quietLimit = if (pageClickMs > 0L) BOT_PAGE_TIMEOUT_MS else BOT_QUIET_WINDOW_MS
        if (System.currentTimeMillis() - lastChangeMs < quietLimit) continue
        // 本页收齐。空转判停：本页去重链接总数没超过上次翻页前，记一页空转；
        // 连续 BOT_STALE_PAGES_STOP 页空转即收工（防真翻满 BOT_MAX_PAGES 页失控）
        val linksNow = (replies.flatMap { extractTmeLinks(it) } + extraLinks).distinct().size
        if (pagesClicked > 0) {
            if (linksNow <= lastPageLinkCount) {
                stalePages++
                if (stalePages >= BOT_STALE_PAGES_STOP) {
                    Log.d(TAG, "searchViaBot: @$username 连续 $stalePages 页无新增链接，提前收工")
                    break
                }
            } else {
                stalePages = 0
            }
        }
        lastPageLinkCount = linksNow
        // R60 翻页提前判停：应答页脚自报告"共 N 条结果，第 x/y 页"（soso 功能地图实证形态）。
        // 已知总页数 y 时翻到 y 即收——当前页 ≥ y 时"下一页"按钮存在也不再点，
        // 省下的每一发翻页回调都是 flood 预算；页脚缺省/变体拿不到（null）则按现状不判停。
        // 页脚总数 N 顺手记日志观测（"该词全网共 N 条"——后续产品可用，本轮先观测）
        val footer = parseBotPageFooter(replies)
        if (footer != null) {
            if (footer.total > 0 && footer.total != lastFooterTotal) {
                lastFooterTotal = footer.total
                Log.i(
                    TAG,
                    "searchViaBot: @$username 页脚解析：该词全网共 ${footer.total} 条结果" +
                        "（第 ${footer.page}/${footer.pages} 页）",
                )
            }
            if (footer.pages > 0 && footer.page >= footer.pages) {
                Log.d(
                    TAG,
                    "searchViaBot: @$username 已到末页（第 ${footer.page}/${footer.pages} 页），" +
                        "翻页按钮存在也不再点，提前收工",
                )
                break
            }
        }
        // 找"下一页"类回调按钮翻页；没有/页数满/按钮已点过则收割结束
        val next = findNextPageButton(replies)
        if (next != null && pagesClicked < BOT_MAX_PAGES && clickedPayloads.add(next.payloadKey)) {
            pagesClicked++
            pageClickMs = System.currentTimeMillis()
            lastChangeMs = pageClickMs
            Log.d(TAG, "searchViaBot: @$username 点击翻页按钮「${next.text}」（第 $pagesClicked/$BOT_MAX_PAGES 页）")
            clickBotCallback(chat.id, next)?.let(extraLinks::add)
            continue
        }
        break
    }
    val replies = repliesById.values.toList()
    if (replies.isEmpty()) {
        Log.w(TAG, "searchViaBot: @$username 在 ${BOT_REPLY_TIMEOUT_MS / 1000} 秒内无回复")
        return@runCatching emptyList()
    }

    // 4) 汇总全部回复里的 t.me 链接（新消息优先）+ 翻页应答附带链接，去重、限量
    val links = (replies.sortedByDescending { it.date }.flatMap { extractTmeLinks(it) } + extraLinks)
        .distinct()
        .take(BOT_MAX_LINKS)
    Log.d(TAG, "searchViaBot: @$username 回复 ${replies.size} 条（翻页 $pagesClicked 次），提取链接 ${links.size} 个")

    // 5) 5 路并发把链接解析回消息（原串行 10 条太慢；Semaphore 限流防 flood wait）。
    //    awaitAll 保序（与 links 同序），单条失败只跳过该条。
    //    注意：message 来自别的频道，chatId 必须用 msg.chatId（不是机器人会话 id），
    //    后续播放链 ensurePlayable 按 chatId+fileId 走，天然支持跨频道条目
    val sem = Semaphore(BOT_LINK_CONCURRENCY)
    val resolvedMsgs = coroutineScope {
        links.map { link ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching {
                        // 频道主页/邀请类链接无消息（message 为 null），filterNotNull 筛掉
                        // R364：超时兜底——单条解析挂起 12s 到点按该条失败跳过（不连坐整批）
                        withTdTimeout(what = "searchViaBot/GetMessageLinkInfo") {
                            client.send(TdApi.GetMessageLinkInfo(link))
                        } ?: error("TDLib 请求超时（可能限流）")
                    }.onFailure { Log.w(TAG, "searchViaBot: 链接解析失败 $link: ${it.message}") }
                        .getOrNull()?.message
                }
            }
        }.awaitAll().filterNotNull()
    }
    // 相册共享 caption（形态A 结果集内版）：机器人常把同相册多个成员的链接一起返回，
    // 带 caption 的成员把说明共享给同组空成员（mediaAlbumId 只在单频道内有归组意义，
    // 先按 chatId 分组）。形态B 不适用——链接解析结果不是连续时间线
    val botCapsByChat = resolvedMsgs.filter { it.mediaAlbumId != 0L }
        .groupBy { it.chatId }
        .mapValues { (_, ms) -> albumSharedCaptions(ms) }
    val items = resolvedMsgs.mapNotNull { m ->
        runCatching {
            toVideoItem(m.chatId, m, null, titleCtx = TitleChainContext(albumCaps = botCapsByChat[m.chatId] ?: emptyMap()))
        }.getOrNull()
    }
    items.distinctBy { it.key }
}.onFailure {
    Log.w(TAG, "searchViaBot: @$username 查询失败: ${it.message}")
}.getOrDefault(emptyList())

/**
 * 从一条消息里提取 t.me 链接（统一规范化成 https://t.me/… 全形式）。
 * 三个来源：
 * 1) MessageText 文本实体 + 正则兜底（走 [extractTmeLinks] 的 FormattedText 重载）；
 * 2) inline 键盘按钮链接（很多搜索机器人把结果放 ReplyMarkupInlineKeyboard 的 url
 *    按钮里。javap 实证链：Message.replyMarkup → ReplyMarkupInlineKeyboard.rows
 *    → InlineKeyboardButton.type 为 InlineKeyboardButtonTypeUrl 时取其 url；
 *    回调/登录等其他按钮类型没有 url，自然跳过）。
 * 非 t.me 链接（推广外链、tg:// 深链等）在规范化时丢弃。
 */
internal fun TdLibGateway.extractTmeLinks(msg: TdApi.Message): List<String> {
    val out = ArrayList<String>()
    // 文本实体 + 纯文本正则（仅 MessageText；纯按钮/图片+按钮消息没有文本，靠下面按钮提取）
    (msg.content as? TdApi.MessageText)?.text?.let { out += extractTmeLinks(it) }
    // inline 键盘按钮：rows 是 Java 二维数组（InlineKeyboardButton[][]），
    // 平台类型层级一律安全调用，防御 JNI 侧 null
    (msg.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows?.forEach { row ->
        row?.forEach { button ->
            (button?.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url
                ?.let { normalizeTmeLink(it) }?.let(out::add)
        }
    }
    return out
}

/**
 * 从一段 FormattedText 提取 t.me 链接（实体 + 正则兜底 + 归一化）。
 * 实体两路：TextUrl 用实体自带 url，Url 用 offset/length 取原文子串——
 * 与 Java 字符串同为 UTF-16 下标，Kotlin substring 直接可用。
 * 供消息正文（MessageText.text）与资源嗅探钩子的媒体 caption 共用同一解析口径。
 */
internal fun TdLibGateway.extractTmeLinks(ft: TdApi.FormattedText): List<String> {
    val raw = ArrayList<String>()
    ft.entities?.forEach { e ->
        when (val t = e.type) {
            is TdApi.TextEntityTypeTextUrl -> t.url?.let(raw::add) // 平台类型，防御 null
            is TdApi.TextEntityTypeUrl ->
                runCatching { ft.text.substring(e.offset, e.offset + e.length) }
                    .getOrNull()?.let(raw::add)
            else -> {}
        }
    }
    raw += TME_LINK_REGEX.findAll(ft.text).map { it.value }
    return raw.mapNotNull { normalizeTmeLink(it) }
}

/**
 * R211 bot 应答 @用户名提及提取（纯函数）：把正文里的 `@channel` 提及实体
 * （TextEntityTypeMention——t.me 链接提取的既有口径不认它）归一化成
 * `https://t.me/<name>` 全形式链接，与 [extractTmeLinks] 的产物同形态同下游。
 *
 * 语义边界（与 t.me 链接提取的差异，刻意保守）：
 *  - 只收**文本实体里的提及**（TDLib 判定的 @名），不做正则兜底——正文里
 *    "@abc" 没被 TDLib 标实体时不强猜（假提及远比漏提及常见）；
 *  - 用户名合法性校验（长度 ≤32、`[A-Za-z0-9_]`——TG 官方约束），不合法丢弃
 *    （防把 emoji 群昵称之类误当提及）；
 *  - 提及的可能是**人**不是频道：消费侧（GroupHarvester/直荐管线）各自按
 *    isChannel / 非频道过滤闸把关，本函数只负责形态归一。
 */
internal fun TdLibGateway.extractMentionNameLinks(ft: TdApi.FormattedText): List<String> {
    val out = ArrayList<String>()
    ft.entities?.forEach { e ->
        if (e.type !is TdApi.TextEntityTypeMention) return@forEach
        val raw = runCatching { ft.text.substring(e.offset, e.offset + e.length) }
            .getOrNull() ?: return@forEach
        val name = raw.trim().removePrefix("@")
        if (name.length in 1..MAX_MENTION_NAME_LEN && MENTION_NAME_REGEX.matches(name)) {
            out += "https://t.me/$name"
        }
    }
    return out
}

/** R211 @提及用户名长度上限（TG 官方约束 32） */
internal const val MAX_MENTION_NAME_LEN = 32

/** R211 @提及用户名合法字符（TG 官方约束：字母数字下划线） */
internal val MENTION_NAME_REGEX = Regex("""[A-Za-z0-9_]+""")

/**
 * t.me 链接规范化：去协议/www 前缀、去尾斜杠、剔除裸域名（t.me 本身）；
 * 非 t.me 链接返回 null。频道主页（无消息 id）/邀请链接不排除，
 * 由 GetMessageLinkInfo 解析侧的 message==null 自然过滤。
 */
internal fun TdLibGateway.normalizeTmeLink(link: String): String? {
    var s = link.trim().removeSuffix("/")
    s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    if (!s.startsWith("t.me/")) return null
    val path = s.removePrefix("t.me/")
    return if (path.isBlank()) null else "https://t.me/$path"
}

/**
 * 答案消息的"链接→标题线索"提取（SearchPublicChat 被 429 封号级限流时
 * （实测 retry after 15000s+ ≈ 4 小时），答案区标题的**零网络兜底源**）。
 * 四路配对（实证：jisou 类 bot 的答案几乎不走按钮，链接内联在文本里）：
 *  0. 文本实体配对（最准优先）：TextUrl 实体覆盖的锚文本即该链接的标题，
 *     jisou 实证格式"📢频道名 成员数k"整行都是可点实体，实体↔url 天然成对；
 *  1. 内联按钮配对：按钮文本即频道名（只收非泛标签）；
 *  2. 文本行配对：链接所在行的剩余文本当标题（"频道名 - t.me/xxx"），
 *     链接独占一行时取上一行的净文本（"频道名\n👉 t.me/xxx"形态）；
 *  3. 位置配对：jisou 实证格式——正文若干行"📢频道名 成员数k"，inline 键盘
 *     URL 按钮按行优先顺序与文本行一一对应（按钮文本是序号类泛标签，前两路
 *     都配不出）。仅当 📢行数 == 按钮链接数且都 > 0 时按下标配对；数量不等
 *     整条消息放弃位置配对（防错位张冠李戴），前两路结果照常返回。
 * 链接归一化与 [extractTmeLinks] 同形态（https://t.me/…，保留 query 串——
 * 答案链接同过 normalizeTmeLink，两侧 key 天然对齐）。
 *
 * R48：返回值升级为 链接→[HintTitle]（标题+成员数）——实体配对/位置配对两路的
 * 行尾成员数 token（"184k"/"340.2K"/"🔞133k" 形态）在 [cleanHintText] 剥离前先解析出数值；
 * 标题合并口径不变（先到先得，先到的配对路更准），成员数只在旧值未知时补。
 */
internal fun TdLibGateway.extractLinkTitleHintsImpl(m: TdApi.Message): Map<String, HintTitle> {
    val out = LinkedHashMap<String, HintTitle>()
    // 合并入口：标题先到先得（配对路 0>1>2>3 的精度序），成员数仅补未知（0）的旧值
    fun putHint(link: String, title: String, members: Int) {
        val prev = out[link]
        when {
            prev == null -> out[link] = HintTitle(title, members)
            prev.members <= 0 && members > 0 -> out[link] = prev.copy(members = members)
            else -> {}
        }
    }
    // 0) 文本实体配对（最准，优先）：TextEntityTypeTextUrl 实体覆盖的文本段就是
    // 该链接的锚文本——jisou 实证格式的"📢频道名 成员数k"行整行都是可点实体，
    // 实体自带 url 与锚文本天然成对，无需任何位置猜测（前三路都配不出这种格式：
    // 按钮是序号、文本行无链接、位置配对要求数量相等也常被翻页按钮破坏）
    (m.content as? TdApi.MessageText)?.text?.let { ft ->
        ft.entities?.forEach { e ->
            val url = (e.type as? TdApi.TextEntityTypeTextUrl)?.url ?: return@forEach
            val norm = normalizeTmeLink(url) ?: return@forEach
            val anchor = runCatching { ft.text.substring(e.offset, e.offset + e.length) }.getOrNull()
            val hint = anchor?.let { cleanHintText(it) } ?: return@forEach
            // 行尾成员数解析（R48）：实体跨度两种切法都兜住——整行可点时锚文本自带
            // 成员数；只盖频道名时成员数 token 落在同行剩余文本。统一扩到实体所在
            // 整行再解析（"频道名 184k"→ members=184000），标题仍用锚文本口径
            putHint(norm, hint, parseMemberCount(lineOf(ft.text, e.offset, e.offset + e.length)))
        }
    }
    // 1) 内联按钮配对
    (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows?.forEach { row ->
        row?.forEach { btn ->
            val url = (btn?.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url ?: return@forEach
            val label = btn?.text?.trim().orEmpty()
            // 至少含一个字母/数字（滤纯 emoji 按钮），最长 40 字符（防把长文案当标题）
            if (label.length > 40 || label.none { it.isLetterOrDigit() }) return@forEach
            if (label.lowercase() in GENERIC_HINT_LABELS) return@forEach
            normalizeTmeLink(url)?.let { putHint(it, label, 0) } // 按钮文本无成员数口径，标 0
        }
    }
    // 2) 文本行配对
    val text = (m.content as? TdApi.MessageText)?.text?.text
        ?: return out.also { logHintYield(it) }
    var prevLineHint: String? = null // 上一行的净文本（无链接行才更新）
    for (line in text.lines()) {
        val matches = TME_LINK_REGEX.findAll(line).toList()
        val linksInLine = matches.mapNotNull { normalizeTmeLink(it.value) }
        if (linksInLine.isEmpty()) {
            // 无链接行：净文本留给"链接独占一行"的下行配对；引导语不当标题
            prevLineHint = cleanHintText(line)
                .takeIf { it != null && !NON_TITLE_PREV_LINES.any { p -> it!!.startsWith(p) } }
                ?: prevLineHint
            continue
        }
        if (linksInLine.size == 1) {
            // 同行配对：剥掉链接（含协议的原始形态）后的剩余文本当标题
            val residue = cleanHintText(line.removeRange(matches[0].range))
            val hint = residue ?: prevLineHint // 链接独占行：取上一行净文本
            if (hint != null) putHint(linksInLine[0], hint, 0)
        }
        // 多链接行不配对（罗列行分不清哪个文本属于哪个链接）
        prevLineHint = null
    }
    // R48 排障：多标题应答消息的 📢 行形态掩码（结构形状，无真实文本）——
    // 确认行尾成员数 token 的真实形态（是否 k/K 后缀、是否落行尾）
    if (out.size >= 5) {
        text.lines().filter { it.trimStart().startsWith("📢") }.take(3).forEach { line ->
            Log.d(TAG, "extractLinkTitleHints: 📢行形态=${maskShape(line.take(40))}")
        }
    }
    // 3) 位置配对：📢行（标题候选）与 inline 键盘 URL 按钮（行优先序）按下标一一配对；
    // 📢行行尾成员数在清洗剥离前一并解析（标题与成员数同出一行，天然对齐）
    val trumpetHints = text.lines().mapNotNull { line ->
        if (!line.trimStart().startsWith("📢")) return@mapNotNull null
        // 清洗（去📢前缀/去行尾成员数/去残留分隔符）后 ≥2 个字母/数字/CJK 才算标题候选
        cleanHintText(line)?.takeIf { hint -> hint.count { ch -> ch.isLetterOrDigit() } >= 2 }
            ?.let { it to parseMemberCount(line) }
    }
    if (trumpetHints.isNotEmpty()) {
        val btnLinks = ArrayList<String>()
        (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows?.forEach { row ->
            row?.forEach { btn ->
                (btn?.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url
                    ?.let { normalizeTmeLink(it) }?.let(btnLinks::add)
            }
        }
        // 数量不等放弃本条消息的位置配对（防错位张冠李戴）；putHint 不覆盖前两路标题成果
        if (trumpetHints.size == btnLinks.size) {
            trumpetHints.forEachIndexed { i, (hint, members) -> putHint(btnLinks[i], hint, members) }
        }
    }
    return out.also { logHintYield(it) }
}

/** 线索提取产出诊断（R48 成员数链排障）：只记条数统计，不落任何标题/链接文本 */
internal fun TdLibGateway.logHintYield(out: Map<String, HintTitle>) {
    Log.d(TAG, "extractLinkTitleHints: 标题=${out.size} 条，带成员数=${out.values.count { it.members > 0 }} 条")
}

/**
 * 行形态掩码（R48 排障专用）：字母→a、数字→0、CJK→字，其余符号原样——
 * 只保留结构形状（可辨认"行尾是否有 000k 形态成员数"），不还原任何真实名称
 */
internal fun TdLibGateway.maskShape(s: String): String = buildString(s.length) {
    for (ch in s) append(
        when {
            ch.isDigit() -> '0'
            ch.code > 0x2E7F -> '字'
            ch.isLetter() -> 'a'
            else -> ch
        },
    )
}

/**
 * 行尾成员数 token 数值解析（R48，与 [cleanHintText] 的剥离同一 [MEMBER_COUNT_TAIL_REGEX]
 * 口径——在剥离之前把数值解析出来）："184k"→184000、"340.2K"→340200、"🔞133k"→133000
 * （k/K=×1000 取整）；无成员数 token / 数值解析失败返回 0（未知）。
 */
internal fun TdLibGateway.parseMemberCount(raw: String): Int {
    val tail = MEMBER_COUNT_TAIL_REGEX.find(raw.trimEnd())?.value ?: return 0
    val num = MEMBER_COUNT_NUM_REGEX.find(tail)?.value ?: return 0
    val base = num.dropLast(1).toDoubleOrNull() ?: return 0
    // R435 修6：乘千后可能溢出 Int（如 "2147483k"）——先算 Long 再安全收敛，超 Int 上限回退 0（未知）
    val total = (base * 1000).toLong()
    return if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else 0
}

/** 正文中 [start, end) 区间所在的整行（按 \n 扩边，越界安全收拢；R48 成员数整行解析用） */
internal fun TdLibGateway.lineOf(text: String, start: Int, end: Int): String {
    val cs = start.coerceIn(0, text.length)
    val ce = end.coerceIn(cs, text.length)
    val ls = if (cs <= 0) 0 else {
        val p = text.lastIndexOf('\n', cs - 1)
        if (p < 0) 0 else p + 1
    }
    val le = text.indexOf('\n', ce).let { if (it < 0) text.length else it }
    return text.substring(ls, le)
}

/**
 * 标题线索文本清洗：去行首装饰（序号/项目符号/emoji/分隔符——dropWhile 到第一个
 * 字母数字为止，📢 前缀同此天然剥掉，CJK 字符天然保留）、去常见前缀标签
 * （"频道：""名称："）、去行尾成员数 token（"频道名 184k"→"频道名"，jisou
 * 📢行形态；无成员数的行两次 trimEnd 幂等，行为与原来一致）、去行尾残留
 * 分隔符与空白。成品 2..40 字符且含字母/数字/CJK 才算线索，否则 null。
 */
internal fun TdLibGateway.cleanHintText(raw: String): String? {
    var s = raw.dropWhile { ch -> !ch.isLetterOrDigit() }
    // 常见前缀标签剥离（"频道：xxx" / "名称: xxx" / "频道名 xxx"）
    s = s.replace(Regex("""^(频道名|频道|名称|群名|群名称|链接)[\s:：\-—]*"""), "")
    s = s.trimEnd(' ', '\t', ':', '：', '-', '—', '|', '·', '。')
    // 行尾成员数 token 剥离（先 trimEnd 让成员数贴到行尾才锚得住；
    // 剥完可能残留"频道名 -"形态的分隔符，下面再清一次）
    s = s.replace(MEMBER_COUNT_TAIL_REGEX, "")
    s = s.trimEnd(' ', '\t', ':', '：', '-', '—', '|', '·', '。')
    if (s.length < 2 || s.length > 40) return null
    if (s.none { ch -> ch.isLetterOrDigit() }) return null
    if (s.lowercase() in GENERIC_HINT_LABELS) return null
    if (s.contains("http", ignoreCase = true) || s.contains("t.me", ignoreCase = true)) return null
    return s
}

/**
 * R184 统一发送口五态结果（SearchPublicChat 全族唯一真发送点）：
 *  - [Hit]：解析命中（含 resolve-once 缓存命中——零搜索零预算）；
 *  - [Rejected]：**未发送**——封禁窗/熔断静默/账本预算拒（连试都不试，防续封）；
 *  - [NotFound]：400 用户名类稳定终态（不存在/不可见/被封）；
 *  - [FloodWait]：429 限流（已上报熔断表+账号账本，retryAfterSec 供退避）；
 *  - [Transient]：其余瞬态失败（网络/超时）。
 * 分类语义经各调用方映射：熔断期降级/轮换停止/死信/限流提示各自诚实上抛。
 */
internal sealed class GuardedSearchResult {
    class Hit(val chat: TdApi.Chat) : GuardedSearchResult()
    object Rejected : GuardedSearchResult()
    object NotFound : GuardedSearchResult()
    class FloodWait(val retryAfterSec: Int) : GuardedSearchResult()
    object Transient : GuardedSearchResult()
}

/**
 * R184 SearchPublicChat 统一发送口（全 app 唯一真发送点，替代七处各自为政的闸）：
 *  0) **resolve-once 缓存**：命中 → GetChat(chatId) 验证 → 直通 [GuardedSearchResult.Hit]
 *     （零搜索零预算——SearchPublicChat 副作用=建会话，GetChat 命中即无需重解析）；
 *     GetChat 失败 = 缓存失效 → 移除条目走真解析；
 *  1) 熔断闸（R44+R58 三态：静默/半开间隔未到 REJECT 一发不打）；
 *  2) 账号级配额账本（[SearchQuotaLedger]，三车道；封禁窗内拒发=**连试都不试**）；
 *  3) 真发送：成功→喂 channelTitleCache + 回写 resolve-once 缓存 + 半开试探回报；
 *     429→[reportAccountSearch429]（双族熔断+账本一次写齐）；400→NotFound；其余→Transient。
 *     R378b 记账顺序正治（R379 挂账①收口）：withTdTimeout 内 silentGuard 前置拒发
 *     （配速/静默，block 零执行=零请求）→回退本口刚记的账本令牌（[SearchQuotaLedger.refund]，
 *     拒发不烧账号配额）+Rejected；超时/发送失败照旧不退（R364 语义零改）。
 *
 * R364：两处发送（resolve-once 缓存验身 GetChat + 真发送 SearchPublicChat）裹
 * [withTdTimeout] 超时上限闸——本口有熔断/账本但原无超时，正是 R361 隐性限流
 * 永挂的真身；超时按 [GuardedSearchResult.Transient] 回落（「其余瞬态失败」同族），
 * 熔断/账本/配额计费语义零改（不报成功、不上报 429、已记预算不退）。
 *
 * @param lane 请求车道（默认后台解析；用户动作/爬虫验证显式传）——代理验证模式
 *   （AppPrefs.agentVerifySearchQuota）下一律折算为 CRAWLER（最低优先级）
 */
internal suspend fun TdLibGateway.searchPublicChatGuardedEx(
    username: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND,
): GuardedSearchResult {
    val key = username.trim().removePrefix("@")
    if (key.isEmpty()) return GuardedSearchResult.NotFound
    awaitAuthorized() // 未授权上抛（与各既有调用方口径一致）；先授权再查缓存防误清
    // 0) resolve-once 缓存（R184 核心：会话已建=GetChat 直通，永不重解析）
    resolveOnceCache.lookup(key)?.let { cached ->
        // R364：超时兜底——缓存验身挂起 12s 到点按「缓存失效」回落（移除条目走真解析，
        // 与 GetChat 失败同口径；验身不烧搜索预算，超时只是上限闸）
        val chat = runCatching {
            withTdTimeout(what = "searchPublicChatEx/GetChat 缓存验身 @$key") { client.send(TdApi.GetChat(cached.chatId)) }
        }.getOrNull()
        if (chat != null) {
            Log.d(TAG, "searchPublicChatEx: @$key resolve-once 缓存命中（GetChat 直通 chatId=${cached.chatId}，零搜索零预算）")
            return GuardedSearchResult.Hit(chat)
        }
        resolveOnceCache.remove(key)
        Log.d(TAG, "searchPublicChatEx: @$key 缓存条目失效（GetChat 未命中），移除后走真解析")
    }
    // 1) 熔断闸（R44+R58：静默/半开间隔未到都一发不打）
    val family = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    val probing = when (floodBreaker.gate(family)) {
        FloodCircuitBreaker.GateDecision.REJECT -> {
            Log.d(TAG, "searchPublicChatEx: 熔断/半开限量中（剩 ${floodBreaker.remainingSec(family)}s），跳过 @$key")
            return GuardedSearchResult.Rejected
        }
        FloodCircuitBreaker.GateDecision.PROBE -> true
        FloodCircuitBreaker.GateDecision.PASS -> false
    }
    // 2) 账号级配额账本（三车道；封禁窗内=连试都不试）
    val laneEffective = effectiveSearchLane(lane)
    if (!searchQuotaLedger.tryAcquire(laneEffective)) {
        Log.d(TAG, "searchPublicChatEx: 账号搜索预算拒发 @$key（车道=${lane.label}）")
        return GuardedSearchResult.Rejected
    }
    // 3) 真发送
    return try {
        // R364：超时兜底——隐性限流（请求挂起无响应）时 12s 到点按 Transient 诚实回落；
        // 熔断/账本语义零改（不报成功、不上报 429、预算照记不退），真协程取消原样上抛。
        // R379 挂账① 正治（R378b 收口）：silentGuard 前置拒发（配速/静默两味）发生在
        // block 之前——block 零执行=零请求，回退刚记的账本令牌（拒发不烧账号配额；
        // 超时=请求已挂起 12s，保守记账原样不退）
        var blockRan = false
        val sent = withTdTimeout(what = "searchPublicChatEx/SearchPublicChat @$key") {
            blockRan = true
            client.send(TdApi.SearchPublicChat(key))
        }
        if (sent == null && !blockRan) {
            searchQuotaLedger.refund(laneEffective)
            Log.d(TAG, "searchPublicChatEx: 前置拒发（配速/静默）@$key 零请求，已回退账本记账（车道=${lane.label}）")
            return GuardedSearchResult.Rejected
        }
        val chat = sent ?: return GuardedSearchResult.Transient
        if (probing) floodBreaker.reportSuccess(family) // R58：试探成功回报，推进闭合计数
        channelTitleCache[chat.id] = chat.title
        val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        resolveOnceCache.record(key, chat.id, chat.title, isChannel) // R184：搜完建缓存
        GuardedSearchResult.Hit(chat)
    } catch (e: TdException) {
        // 429 判定不信库的 isFloodWait（老格式漏新版 "Too Many Requests: retry after N"，R44 实证）
        val flood = e.code == 429 || e.isFloodWait
        val retryAfter = if (flood) parseRetryAfterSec(e.message) else 0
        if (flood) reportAccountSearch429(retryAfter.toLong()) // R184：双族熔断+账号账本一次写齐
        Log.d(TAG, "searchPublicChatEx: @$key 失败 code=${e.code} msg=${e.message} flood=$flood retryAfter=${retryAfter}s")
        when {
            flood -> GuardedSearchResult.FloodWait(retryAfter)
            e.code == 400 -> GuardedSearchResult.NotFound
            else -> GuardedSearchResult.Transient
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // 协程取消不是失败，必须上抛（调用方循环靠它停止）
    } catch (e: Throwable) {
        Log.d(TAG, "searchPublicChatEx: @$key 异常 ${e.javaClass.simpleName}: ${e.message}")
        GuardedSearchResult.Transient
    }
}

/**
 * SearchPublicChat 过闸发送（R44 熔断 + R58 半开三态 + **R184 缓存/账号账本**）：
 * 旧签名的薄 shim（Hit→chat，其余 null）——六个既有调用点的语义零改动自动升级
 * （缓存直通/预算拒/双族 429 上报都在 [searchPublicChatGuardedEx] 内）。
 * 需要区分「未发送被拒」与「发送失败」的调用方（如 ensureChannelOpenable 的限流
 * 降级）请直接用 [searchPublicChatGuardedEx]。
 */
internal suspend fun TdLibGateway.sendSearchPublicChatGuarded(
    username: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND,
): TdApi.Chat? = when (val r = searchPublicChatGuardedEx(username, lane)) {
    is GuardedSearchResult.Hit -> r.chat
    else -> null
}

/**
 * R184 SearchChatsOnServer 统一发送口（三处裸点 S14/S15/S16 的收编）：
 * 熔断闸（[FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER]——与 SearchPublicChat
 * 事故实证至少是账号桶邻位，任一族撞 429 双族同静默）+ 账号账本三车道 + 429 统一上报。
 * 返回 null = 未发送（封禁/熔断/预算拒）或发送失败——调用方按既有 null 语义降级；
 * 详见 [searchPublicChatGuardedEx] 的五态版（SearchChatsOnServer 各调用方暂无
 * 「未发送 vs 失败」区分需求，null 合并足够诚实）。
 * R364：真发送裹 [withTdTimeout] 超时上限闸——隐性限流挂起 12s 到点按 null 诚实
 * 回落（既有失败语义）；熔断/账本/配额计费语义零改（不报成功、不上报 429、
 * 已记预算不退）。R378b 记账顺序正治（R379 挂账①）：前置拒发（配速/静默，
 * block 零执行=零请求）回退刚记的账本令牌——拒发不烧账号配额。
 */
internal suspend fun TdLibGateway.searchChatsOnServerGuarded(
    query: String,
    filter: TdApi.SearchChatTypeFilter?,
    limit: Int,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND,
): TdApi.Chats? {
    awaitAuthorized() // 幂等（Ready 即返）；先授权防半授权态误判
    val family = FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER
    val probing = when (floodBreaker.gate(family)) {
        FloodCircuitBreaker.GateDecision.REJECT -> {
            Log.d(TAG, "searchChatsOnServer: 熔断/半开限量中（剩 ${floodBreaker.remainingSec(family)}s），跳过「$query」")
            return null
        }
        FloodCircuitBreaker.GateDecision.PROBE -> true
        FloodCircuitBreaker.GateDecision.PASS -> false
    }
    val laneEffective = effectiveSearchLane(lane)
    if (!searchQuotaLedger.tryAcquire(laneEffective)) {
        Log.d(TAG, "searchChatsOnServer: 账号搜索预算拒发「$query」（车道=${lane.label}）")
        return null
    }
    return try {
        // R364：超时兜底——隐性限流挂起 12s 到点按 null 诚实回落（既有失败语义；
        // 熔断/账本零改：不报成功、不上报 429、预算照记不退）。
        // R379 挂账① 正治（R378b 收口）：前置拒发（配速/静默）发生在 block 之前——
        // block 零执行=零请求，回退刚记的账本令牌（超时=已挂起 12s 保守记账不退）
        var blockRan = false
        val found = withTdTimeout(what = "searchChatsOnServer/SearchChatsOnServer limit=$limit") {
            blockRan = true
            client.send(TdApi.SearchChatsOnServer(query, filter, limit))
        }
        if (found == null && !blockRan) {
            searchQuotaLedger.refund(laneEffective)
            Log.d(TAG, "searchChatsOnServer: 前置拒发（配速/静默）「$query」零请求，已回退账本记账（车道=${lane.label}）")
            return null
        }
        val chats = found ?: return null
        if (probing) floodBreaker.reportSuccess(family)
        chats
    } catch (e: TdException) {
        val flood = e.code == 429 || e.isFloodWait
        if (flood) reportAccountSearch429(parseRetryAfterSec(e.message).toLong())
        Log.d(TAG, "searchChatsOnServer: 「$query」失败 code=${e.code} msg=${e.message} flood=$flood")
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "searchChatsOnServer: 「$query」异常 ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}

/** 从 TDLib 错误消息解析 retry-after 秒数（新格式 "retry after N" + 老格式 FLOOD_WAIT_N 兜底；解析不出 = 0） */
internal fun TdLibGateway.parseRetryAfterSec(message: String?): Int {
    if (message.isNullOrEmpty()) return 0
    return Regex("""retry after (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""FLOOD_WAIT_(\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: 0
}

/**
 * 收割专用·消息 t.me 链接提取（私有 extractTmeLinks 的公开门面）：
 * 文本实体 + 正则兜底 + inline 按钮 url 三源，归一化全形式（与嗅探/机器人搜索同一口径）。
 */
internal fun TdLibGateway.extractMessageTmeLinksImpl(msg: TdApi.Message): List<String> = extractTmeLinks(msg)

/**
 * R211 收割专用·@用户名提及提取的公开门面（见 [extractMentionNameLinks]）：
 * 只取 MessageText 正文实体提及（媒体 caption 的提及收割口径暂不含——bot 应答
 * 的频道指名几乎全在正文），归一化 t.me 全形式与 t.me 链接产物同下游。
 */
internal fun TdLibGateway.extractMessageMentionLinksImpl(msg: TdApi.Message): List<String> =
    (msg.content as? TdApi.MessageText)?.text?.let { extractMentionNameLinks(it) } ?: emptyList()

/**
 * R61 组件级·收割专用答案链接提取（extractAnswerLinksFiltered 的公开门面）：
 * 正文链接全收，垃圾按钮逐组件剔链不连坐整条；
 * 返回 (保留链接, 被剔的垃圾按钮链接数, 正文链接数)。
 */
internal fun TdLibGateway.extractMessageAnswerLinksFilteredImpl(msg: TdApi.Message): Triple<List<String>, Int, Int> =
    extractAnswerLinksFiltered(msg)

/** 翻页按钮定位结果：所属消息 id + 回调 payload + 按钮文本 + payload 去重 key */
internal class BotNextPageButton(
    val messageId: Long,
    val payload: ByteArray,
    val text: String,
    val payloadKey: String,
)

/** R60 应答页脚解析产物：[total] 全网结果总数（缺省 -1）；[page]/[pages] 当前页/总页数（缺省 -1） */
internal class BotPageFooter(val total: Int, val page: Int, val pages: Int)

/**
 * R60 应答页脚"共 N 条结果，第 x/y 页"解析（翻页提前判停 + 总数观测共用）。
 * soso 功能地图实证：搜索应答正文页脚自报告总量与页码（单消息原地编辑形态，
 * 页脚随最新编辑态走，故取最新一条 MessageText 应答的正文解析）。
 * 兼容缺省/变体：页码紧凑（第 3/12 页）与展开（当前第 1 页，共 67 页）两种形态都认；
 * 拿不到页脚返回 null——调用方按现状不判停，绝不误判。
 */
internal fun TdLibGateway.parseBotPageFooter(replies: List<TdApi.Message>): BotPageFooter? {
    for (m in replies.sortedByDescending { it.date }) {
        val text = (m.content as? TdApi.MessageText)?.text?.text ?: continue
        var total = -1
        var page = -1
        var pages = -1
        BOT_FOOTER_TOTAL_REGEX.find(text)?.let { r ->
            total = r.groupValues[1].filter { it.isDigit() }.toIntOrNull() ?: -1
        }
        // 两种页码形态分组布局一致：group(1)=当前页 group(2)=总页数
        val pageMatch = BOT_FOOTER_PAGE_SLASH_REGEX.find(text)
            ?: BOT_FOOTER_PAGE_VERBOSE_REGEX.find(text)
        if (pageMatch != null) {
            page = pageMatch.groupValues[1].toIntOrNull() ?: -1
            pages = pageMatch.groupValues[2].toIntOrNull() ?: -1
        }
        if (total > 0 || (page > 0 && pages > 0)) return BotPageFooter(total, page, pages)
    }
    return null
}

/**
 * R60 自指防污染判定：链接是否"指向搜索实体自身的深链"。
 * 背景（soso 功能地图实证）：搜索应答里的"相关热词"是 bot 自家深链
 * （t.me/<应答bot>?start=search_… 形态——bot 自身用户名 + start 查询参数），
 * 点开 = 替用户再发起一次搜索，**不是资源答案**；若当普通 t.me 链接收进
 * 答案库/图谱，会造成答案递归自指污染（答案里套着搜索引擎自己）。
 * 判定口径（两个条件同时满足）：
 *  1. 链接用户名段 == 应答实体用户名 或 已知搜索 bot 配置名
 *     （searchBotUsername + searchBotFallbacks，大小写不敏感）；
 *  2. 带 start= 查询参数（bot 主页链/startgroup 拉群链不在此列，避免误伤正常推荐）。
 */
internal fun TdLibGateway.isSelfReferentialSearchLinkImpl(link: String, responderUsername: String?): Boolean {
    if (!SELF_REF_START_PARAM_REGEX.containsMatchIn(link)) return false
    val uname = AnswerStore.usernameFromLink(link)
    if (uname.isEmpty()) return false
    if (!responderUsername.isNullOrEmpty() &&
        uname.equals(responderUsername.removePrefix("@"), ignoreCase = true)
    ) {
        return true
    }
    return knownSearchBotNames().any { uname.equals(it, ignoreCase = true) }
}

/** 已知搜索 bot 名清单（既有配置：主 + 备用；去 @/空白、去重）——R211 收编网关成员口（跨包消费） */
internal fun TdLibGateway.knownSearchBotNamesImpl(): List<String> =
    (listOf(appPrefs.searchBotUsername) + appPrefs.searchBotFallbacks.split(','))
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * 在机器人回复里找"下一页/更多"类回调按钮（新回复优先——翻页按钮通常挂在最新结果页上）。
 * 只认 InlineKeyboardButtonTypeCallback（url 按钮的链接已被 extractTmeLinks 收割，
 * 其他类型点了也没法翻页）；按钮文本对 [BOT_NEXT_BUTTON_KEYWORDS] 做小写 contains
 * 匹配，命中其一即候选。"上一页"不含任何关键词，天然排除。
 *
 * 签名已 javap 实证：InlineKeyboardButton{text: String, type: InlineKeyboardButtonType}；
 * InlineKeyboardButtonTypeCallback{data: byte[]}；ReplyMarkupInlineKeyboard{
 * rows: InlineKeyboardButton[][]}（均为 JNI 平台类型，层级安全调用防御 null）
 */
internal fun TdLibGateway.findNextPageButton(replies: List<TdApi.Message>): BotNextPageButton? {
    for (m in replies.sortedByDescending { it.date }) {
        val rows = (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows ?: continue
        for (row in rows) {
            for (btn in row) {
                val type = btn?.type as? TdApi.InlineKeyboardButtonTypeCallback ?: continue
                val text = btn?.text?.takeIf { it.isNotBlank() } ?: continue
                val lower = text.lowercase()
                if (BOT_NEXT_BUTTON_KEYWORDS.none { it in lower }) continue
                val data = type.data ?: continue // JNI 平台类型，防御 null
                return BotNextPageButton(
                    m.id, data, text,
                    android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP),
                )
            }
        }
    }
    return null
}

/**
 * 消息回调按钮的签名串（按钮文本 + payload Base64），供收割循环感知
 * "机器人翻页编辑了按钮"。非回调按钮（url 等）只计文本；无键盘返回空串。
 */
internal fun TdLibGateway.callbackButtonsSignature(msg: TdApi.Message): String {
    val rows = (msg.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows ?: return ""
    return buildString {
        for (row in rows) {
            for (btn in row) {
                append(btn?.text).append('/')
                val d = (btn?.type as? TdApi.InlineKeyboardButtonTypeCallback)?.data
                if (d != null) append(android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP))
                append(';')
            }
        }
    }
}

/**
 * 点击机器人消息的 inline 回调按钮（翻页用），返回按钮应答里附带的 t.me 链接（可空）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetCallbackQueryAnswer(long chatId, long messageId, CallbackQueryPayload payload)
 *   -> CallbackQueryAnswer{text: String, showAlert: boolean, url: String}
 *   —— 注意鉴别：本 AAR 没有 ClickInlineKeyboardButton（javap 实证找不到类；
 *      它是后续 TDLib 版本对同一 TL 方法 getBotCallbackAnswer 的改名），
 *      GetCallbackQueryAnswer 是等价前身
 * CallbackQueryPayloadData(byte[] data)（包裹 InlineKeyboardButtonTypeCallback.data）
 *
 * 分页型机器人通常返回空应答并编辑原消息（新链接由收割轮询吸收）；
 * 个别机器人把结果页 URL 直接放在应答 url 里，顺手收割。任何失败返回 null
 * （翻页是增量收益，绝不影响已收割结果）。
 */
internal suspend fun TdLibGateway.clickBotCallback(chatId: Long, btn: BotNextPageButton): String? = runCatching {
    // R364：超时兜底——按钮应答挂起 12s 到点按 null 回落（翻页是增量收益，不连坐已收割结果）
    val answer = withTdTimeout(what = "clickBotCallback/GetCallbackQueryAnswer msg=${btn.messageId}") {
        client.send(
            TdApi.GetCallbackQueryAnswer(chatId, btn.messageId, TdApi.CallbackQueryPayloadData(btn.payload))
        )
    } ?: error("TDLib 请求超时（可能限流）")
    answer.url?.takeIf { it.isNotBlank() }?.let { normalizeTmeLink(it) }
}.onFailure {
    Log.w(TAG, "searchViaBot: 翻页按钮点击失败 msg=${btn.messageId}: ${it.message}")
}.getOrNull()

// ---------------- R65 热词榜收割（热词源：/start 主菜单 → "热搜"按钮 → 三子榜 × 翻页） ----------------

/**
 * R65 热词榜收割（融合 soso 功能地图借鉴点⑤）：对配置的主搜索 bot
 * （AppPrefs.searchBotUsername）走菜单协议拿全网热搜词榜。
 * 协议形态（功能地图 §1.3 实证）：/start 主菜单 → 点"热搜"按钮（callback
 * m=hotkey）→ 榜单消息 40 词/页 × 2 页 × 3 子榜 tab（b=a/g/p）；全部交互在
 * 同一条消息上**原地编辑**完成（翻页/tab 切换都不落新消息）；热词按钮 payload
 * 形如 m=s&key=<词哈希>（词哈希不落明文，词本身在按钮文本上）。
 *
 * 走查步骤（全部复用 searchViaBot 实证零件：SendMessage / GetChatHistory 轮询 /
 * GetCallbackQueryAnswer + CallbackQueryPayloadData 按钮点击）：
 *  1. 发 /start → 等主菜单应答（带 m=hotkey 回调按钮的消息）；
 *  2. 点"热搜"进榜 → 等该消息编辑出榜单形态（出现 m=s&key= 热词按钮）；
 *  3. 三子榜（a 为进榜默认榜，再切 g/p）逐个收：收当前页词 → 追"下一页"
 *     （m=hotkey&b=<榜>&page=n+1）→ 无下一页按钮或满 [HOTWORD_MAX_PAGES_PER_BOARD] 收工；
 *  4. 词表清洗（[sanitizeBoardWord]，与 GroupHarvester.sanitizeKeyword 同口径：
 *     trim/去空白、长度 1..[HOTWORD_MAX_WORD_CHARS]、不含 t.me 链接、非 "/" 指令前缀、
 *     过 AdBlacklist 垃圾词闸——广告词不收，合规闸在入库前生效）。
 *
 * flood 纪律：整轮走查在**查询车道**（floodBudgetQuery，与 searchViaBot 同车道）
 * 只记 1 次预算（会话解析 + 发送 /start + 轮询 + 全部按钮点击打包计费，口径同
 * queryBotOnce）；会话解析主链 SearchPublicChat 自带熔断闸+账号搜索账本
 * （R184：静默/封禁中一发不打，resolve-once 缓存命中零搜索），熔断期转
 * SearchChatsOnServer 旁路（过闸版——R184 双族同静默：封禁期旁路也不打，防续封；用户名验身防仿冒，见
 * [resolveBotChatViaServerSearch]）；预算耗尽 / 双路解析未中 / 任一步超时或失败
 * 都返回 null——调用方保留旧词表静默降级（本轮不更新）。
 *
 * [onAttempt]：真正发起网络流程（预算记账通过、会话解析成功）时回调一次——
 * 调用方（GroupHarvester/HotWordBoard）据此推进每日节流戳；网关不碰存储。
 *
 * 日志纪律：只记结构性指标（子榜词数/点击数/耗时），绝不落榜词原文。
 *
 * @return 清洗去重后的词表（榜序：子榜 a→g→p、页序）；null = 本轮失败/降级
 */
internal suspend fun TdLibGateway.harvestHotWordBoardImpl(onAttempt: () -> Unit = {}): List<String>? = runCatching {
    awaitAuthorized()
    val bot = appPrefs.searchBotUsername.trim().removePrefix("@")
    if (bot.isEmpty()) {
        Log.d(TAG, "harvestHotWordBoard: 未配置主搜索 bot，本轮跳过")
        return@runCatching null
    }
    // R44 熔断观测（不提前收场）：SearchPublicChat 静默中主链自然一发不打
    // （sendSearchPublicChatGuarded 内置闸拒发返回 null），转深链旁路解析继续——
    // 热词榜是每日一轮的低频活，不值得为熔断整轮弃收
    val searchFamily = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    if (floodBreaker.isSilenced(searchFamily)) {
        Log.d(
            TAG,
            "harvestHotWordBoard: SearchPublicChat 熔断静默中" +
                "（剩 ${floodBreaker.remainingSec(searchFamily)}s），主链不打、转深链旁路",
        )
    }
    // flood 查询车道记账：整轮走查记 1 次（额度上限逐次从 prefs 刷新，设置改动免重启）
    floodBudgetQuery.maxOpsPerHour = appPrefs.floodQueryPerHour
    if (!floodBudgetQuery.tryAcquire()) {
        Log.w(TAG, "harvestHotWordBoard: 超出每小时查询预算，本轮降级（旧词表继续服役）")
        return@runCatching null
    }
    // 会话解析主链 SearchPublicChat（自带熔断闸+账号账本：静默中一发不打直接 null；
    // R184：resolve-once 缓存命中时零搜索——bot 名解析过一次后跨进程直通）；
    // 熔断期旁路 SearchChatsOnServer 服务端搜索（过闸版——双族同静默后封禁期此路
    // 也不打，保守取舍：宁可本轮降级也不续封）；每日一轮最多 3 次请求，同记在本次
    // 预算内，用户名逐一验身防同名误中
    val chat = sendSearchPublicChatGuarded(bot, SearchQuotaLedger.Lane.CRAWLER)
        ?: resolveBotChatViaServerSearch(bot)
    if (chat == null) {
        Log.w(TAG, "harvestHotWordBoard: @$bot 会话解析失败（主链熔断/旁路未中），本轮降级")
        return@runCatching null
    }
    // 真正发起网络流程——调用方推进每日节流戳（此后成败都占当日额度）
    onAttempt()
    val t0 = System.currentTimeMillis()

    // 1) 发 /start 拿主菜单（菜单协议唯一一条主动消息，其余全走按钮回调）
    //    R364：超时兜底——隐性限流挂起 12s 到点按本轮失败降级（error→runCatching→null，
    //    旧词表继续服役），协程不再永挂
    val startSec = (t0 / 1000L).toInt()
    withTdTimeout(what = "harvestHotWordBoard/SendMessage /start @$bot") {
        client.send(
            TdApi.SendMessage(
                chat.id, null, null, null, null,
                TdApi.InputMessageText(TdApi.FormattedText("/start", emptyArray()), null, false),
            )
        )
    } ?: error("TDLib 请求超时（可能限流）")
    // 2) 等主菜单应答（带"热搜"入口按钮 m=hotkey）并点进入榜
    val menu = awaitBoardMessage(chat.id, 0L, startSec, BOT_REPLY_TIMEOUT_MS, null) { m ->
        findBoardButton(m) { kv -> kv["m"] == "hotkey" } != null
    }
    if (menu == null) {
        Log.w(TAG, "harvestHotWordBoard: @$bot ${BOT_REPLY_TIMEOUT_MS / 1000}s 内未见主菜单热搜入口，本轮降级")
        return@runCatching null
    }
    val entryPayload = findBoardButton(menu) { kv -> kv["m"] == "hotkey" }!!
    var clicks = 0
    if (!clickBoardButton(chat.id, menu.id, entryPayload)) return@runCatching null
    clicks++
    // 3) 等榜单成形（原地编辑出热词按钮）；协议外 bot 可能新发榜单消息，宽松兜底认新消息
    val formed = awaitBoardMessage(
        chat.id, menu.id, startSec, BOT_PAGE_TIMEOUT_MS, boardSignature(menu),
    ) { m -> extractBoardWords(m).isNotEmpty() }
        ?: awaitBoardMessage(
            chat.id, 0L, startSec, BOT_PAGE_TIMEOUT_MS, boardSignature(menu),
        ) { m -> m.id != menu.id && extractBoardWords(m).isNotEmpty() }
    if (formed == null) {
        Log.w(TAG, "harvestHotWordBoard: @$bot 点热搜后榜单未成形，本轮降级")
        return@runCatching null
    }
    var current: TdApi.Message = formed

    // 4) 三子榜逐个收：a（进榜默认榜）→ g → p；每榜页 1 起手再追翻页链
    val words = LinkedHashSet<String>()
    val boardCounts = LinkedHashMap<String, Int>() // 子榜 → 新增词数（结构化日志用）
    val subBoards = listOf("a", "g", "p")
    for ((idx, b) in subBoards.withIndex()) {
        if (idx > 0) {
            // 切子榜 tab：payload m=hotkey&b=<榜>；与翻页按钮的区分：tab 无 page 参数
            // 或恒指页 1（榜单形态变体兼容——实测过 tab 带 page=1 的形态）
            val tab = findBoardButton(current) { kv ->
                kv["m"] == "hotkey" && kv["b"] == b &&
                    (kv["page"] == null || kv["page"] == "1")
            }
            if (tab == null) {
                Log.d(
                    TAG,
                    "harvestHotWordBoard: @$bot 第 $idx 个子榜无 tab 按钮，跳过；" +
                        "板面非词按钮结构=${describeBoardButtons(current)}",
                )
                continue
            }
            val sig = boardSignature(current)
            if (!clickBoardButton(chat.id, current.id, tab)) continue
            clicks++
            val next = awaitBoardMessage(
                chat.id, current.id, startSec, BOT_PAGE_TIMEOUT_MS, sig,
            ) { m -> extractBoardWords(m).isNotEmpty() }
            if (next == null) {
                Log.d(TAG, "harvestHotWordBoard: @$bot 第 $idx 个子榜切换后榜单未刷新，跳过该榜")
                continue
            }
            current = next
        }
        // 收当前子榜：页 1 → 翻页链（page=n+1）直到无下一页/满硬顶
        var page = 1
        var boardAdded = 0
        while (true) {
            for (w in extractBoardWords(current)) {
                sanitizeBoardWord(w)?.let { if (words.add(it)) boardAdded++ }
            }
            if (page >= HOTWORD_MAX_PAGES_PER_BOARD) break
            val nextPayload = findBoardButton(current) { kv ->
                kv["m"] == "hotkey" && kv["b"] == b && kv["page"]?.toIntOrNull() == page + 1
            } ?: break // 无下一页按钮 = 本子榜收齐（真正的收工信号）
            val sig = boardSignature(current)
            if (!clickBoardButton(chat.id, current.id, nextPayload)) break
            clicks++
            val next = awaitBoardMessage(
                chat.id, current.id, startSec, BOT_PAGE_TIMEOUT_MS, sig,
            ) { m -> extractBoardWords(m).isNotEmpty() } ?: break // 翻页未生效：按已收页收场
            current = next
            page++
        }
        boardCounts[b] = boardAdded
    }
    Log.i(
        TAG,
        "harvestHotWordBoard: @$bot 走查完成：子榜=${boardCounts.size}/${subBoards.size} $boardCounts" +
            " 点击=$clicks 总词=${words.size} 耗时=${System.currentTimeMillis() - t0}ms",
    )
    // 空词表按失败处理（协议变更的探测信号）：不落盘，旧词表继续服役
    words.toList().takeIf { it.isNotEmpty() }
}.onFailure {
    Log.w(TAG, "harvestHotWordBoard 失败（本轮降级，旧词表继续服役）: ${it.message}")
}.getOrNull()

/**
 * 榜单/菜单消息轮询等待（R65）：每 [BOT_POLL_INTERVAL_MS] 拉一次会话历史，
 * 直到出现满足 [match] 的消息或超时（返回 null）。
 * [messageId] > 0 时只认该消息（原地编辑协议：翻页/tab 切换都编辑同一条）；
 * =0 认任意非本人近期消息（找主菜单/兜底认新发榜单消息用）。
 * [changedFromSig] 非 null 时要求板面签名与之不同——点击按钮后等"编辑已生效"，
 * 防止拿到点击前的旧板面误收一遍。
 */
internal suspend fun TdLibGateway.awaitBoardMessage(
    chatId: Long,
    messageId: Long,
    startSec: Int,
    timeoutMs: Long,
    changedFromSig: String?,
    match: (TdApi.Message) -> Boolean,
): TdApi.Message? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        delay(BOT_POLL_INTERVAL_MS)
        val history = runCatching {
            // R364：超时兜底——单轮拉取挂起 12s 到点按 null 计（等下一轮；deadline 仍兜底）
            withTdTimeout(what = "awaitBoardMessage/GetChatHistory chatId=$chatId") {
                client.send(TdApi.GetChatHistory(chatId, 0, 0, BOT_HISTORY_LIMIT, false))
            }
        }.getOrNull() ?: continue // 单轮拉取失败/超时不计数，等下一轮
        val hit = history.messages.filterNotNull()
            .filter { !it.isOutgoing && it.date >= startSec - 3 }
            .filter { messageId == 0L || it.id == messageId }
            .sortedByDescending { it.id }
            .firstOrNull { m ->
                if (changedFromSig != null && boardSignature(m) == changedFromSig) {
                    return@firstOrNull false // 点击前的旧板面，等编辑生效
                }
                match(m)
            }
        if (hit != null) return hit
    }
    return null
}

/** 板面签名（R65）：编辑时间戳 + 回调按钮签名——原地编辑后签名必变（沿用收割循环同款口径） */
internal fun TdLibGateway.boardSignature(m: TdApi.Message): String = "${m.editDate}:${callbackButtonsSignature(m)}"

/**
 * 回调 payload 解码为 kv 表（m=hotkey&b=a&page=2 形态，功能地图 §2 协议速查）。
 * 非本协议 payload（不可解码/不以 m= 起手）返回 null。
 */
internal fun TdLibGateway.parseBotPayloadKv(data: ByteArray?): Map<String, String>? {
    val s = data?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() } ?: return null
    if (!s.startsWith("m=")) return null
    val out = LinkedHashMap<String, String>()
    for (seg in s.split('&')) {
        val i = seg.indexOf('=')
        if (i > 0) out[seg.substring(0, i)] = seg.substring(i + 1)
    }
    return out
}

/**
 * 榜单/菜单消息里按 payload 条件找回调按钮（返回 payload 字节串，直接喂
 * GetCallbackQueryAnswer + CallbackQueryPayloadData）；只认 Callback 类型按钮
 * （Url 按钮没有 payload，天然跳过）。
 */
internal fun TdLibGateway.findBoardButton(m: TdApi.Message, match: (Map<String, String>) -> Boolean): ByteArray? {
    val rows = (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows ?: return null
    for (row in rows) {
        for (btn in row) {
            val type = btn?.type as? TdApi.InlineKeyboardButtonTypeCallback ?: continue
            val kv = parseBotPayloadKv(type.data) ?: continue
            if (match(kv)) return type.data
        }
    }
    return null
}

/**
 * 板面非词按钮的结构化描述（R65 诊断用）：只落 payload 白名单键值
 * （m/b/page/f/t——模块/子榜/页码/功能/类型，全是结构参数），其余键只落键名
 * （如 key 的词哈希、u 的用户 id 一律不落值）；m=s 热词按钮整颗跳过。
 * 供"tab 找不到"类协议漂移排查，日志纪律：词原文/哈希/uid 绝不进日志。
 */
internal fun TdLibGateway.describeBoardButtons(m: TdApi.Message): String {
    val rows = (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows ?: return "无键盘"
    val out = ArrayList<String>()
    for (row in rows) {
        for (btn in row) {
            val type = btn?.type as? TdApi.InlineKeyboardButtonTypeCallback ?: continue
            val kv = parseBotPayloadKv(type.data) ?: continue
            if (kv["m"] == "s") continue // 热词按钮整颗跳过（哈希不落日志）
            out += kv.entries.joinToString(",") { (k, v) ->
                if (k in BOARD_LOG_VALUE_WHITELIST) "$k=$v" else k
            }
        }
    }
    return if (out.isEmpty()) "无回调按钮" else out.joinToString(" | ")
}

/**
 * 榜单消息的热词按钮词表（R65）：payload m=s&key=<词哈希> 的按钮，按钮文本即词。
 * 此处只做提取（原文），清洗（长度/链接/广告词闸）在 [sanitizeBoardWord] 入库侧。
 */
internal fun TdLibGateway.extractBoardWords(m: TdApi.Message): List<String> {
    val rows = (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows ?: return emptyList()
    val out = ArrayList<String>()
    for (row in rows) {
        for (btn in row) {
            val type = btn?.type as? TdApi.InlineKeyboardButtonTypeCallback ?: continue
            val kv = parseBotPayloadKv(type.data) ?: continue
            if (kv["m"] != "s" || !kv.containsKey("key")) continue
            val w = btn?.text?.trim().orEmpty()
            if (w.isNotEmpty()) out += w
        }
    }
    return out
}

/**
 * 热词清洗（R65，与 GroupHarvester.sanitizeKeyword 同口径——复用其判定纪律）：
 * trim/折叠空白后 1..[HOTWORD_MAX_WORD_CHARS] 字符、不含 t.me 链接、非 "/" 指令前缀、
 * 过 AdBlacklist 垃圾词闸（广告词不收，合规闸在入库前生效）；
 * 另有榜单形态特有一条：按钮文本被截断的词（尾部省略号 "…"/"..."）不是可搜的
 * 完整词，不收（实测榜单按钮标签超长会被 bot 截断展示）。不满足返回 null。
 */
internal fun TdLibGateway.sanitizeBoardWord(text: String?): String? {
    val w = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
    if (w.isEmpty() || w.length > HOTWORD_MAX_WORD_CHARS) return null
    if (w.startsWith("/")) return null
    if (w.endsWith("…") || w.endsWith("...")) return null // 截断展示形态，非完整词
    if (w.contains("t.me/", ignoreCase = true)) return null
    if (AdBlacklist.isSpam(w)) return null
    return w
}

/** 点榜单/菜单按钮（GetCallbackQueryAnswer + CallbackQueryPayloadData，clickBotCallback 同款链路）；失败静默 false */
internal suspend fun TdLibGateway.clickBoardButton(chatId: Long, messageId: Long, payload: ByteArray): Boolean = runCatching {
    // R364：超时兜底——按钮应答挂起 12s 到点按 false（点击失败走既有跳过/收场路）
    withTdTimeout(what = "clickBoardButton/GetCallbackQueryAnswer msg=${messageId}") {
        client.send(TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadData(payload)))
    } ?: error("TDLib 请求超时（可能限流）")
    true
}.onFailure {
    Log.w(TAG, "harvestHotWordBoard: 按钮点击失败 msg=$messageId: ${it.message}")
}.getOrDefault(false)

/**
 * R65 热词榜·旁路解析（SearchPublicChat 熔断期的保命链）：
 * SearchChatsOnServer(bot 名, Bot 过滤器) 服务端搜索拿候选 chatId（与熔断封禁的
 * SearchPublicChat **不同方法族**，且每日只在熔断期调用一次），逐个 GetChat +
 * GetUser 验身——只认私聊（ChatTypePrivate）且活跃用户名精确等于配置名的
 * （防同名仿冒 bot 误中，热词榜只信配置的那一个 bot）。
 * 整轮最多 1 次搜索 + 候选数 × 2 次验身（搜索限 3 候选），由入口查询车道记账覆盖。
 * 任何失败返回 null（调用方按本轮降级处理）。
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * SearchChatsOnServer(String, SearchChatTypeFilter, int) -> Chats{totalCount, chatIds: long[]}；
 * SearchChatTypeFilterBot()；GetChat(long) -> Chat；GetUser(long) -> User{usernames: Usernames{
 * activeUsernames: String[], editableUsername, ...}}。
 */
internal suspend fun TdLibGateway.resolveBotChatViaServerSearch(
    bot: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.CRAWLER,
): TdApi.Chat? = runCatching {
    // R184：过闸（熔断+账号账本）——封禁窗内连试都不试（双族同静默后此旁路在封禁期
    // 自然失效，这是保守取舍：宁可热词榜降级也不续封）
    val found = searchChatsOnServerGuarded(bot, TdApi.SearchChatTypeFilterBot(), 3, lane)
        ?: return@runCatching null
    val ids = found.chatIds ?: return@runCatching null // JNI 平台类型，防御 null
    if (ids.isEmpty()) return@runCatching null
    for (id in ids) {
        // R364：超时兜底——旁路验身挂起 12s 到点按该候选失败跳过（continue 同口径）
        val chat = runCatching {
            withTdTimeout(what = "resolveBotViaServer/GetChat chatId=$id") { client.send(TdApi.GetChat(id)) }
        }.getOrNull() ?: continue
        val userId = (chat.type as? TdApi.ChatTypePrivate)?.userId ?: continue
        val user = runCatching {
            withTdTimeout(what = "resolveBotViaServer/GetUser userId=$userId") { client.send(TdApi.GetUser(userId)) }
        }.getOrNull() ?: continue
        val names = (user.usernames?.activeUsernames ?: emptyArray()) +
            (user.usernames?.editableUsername?.takeIf { it.isNotBlank() }?.let { arrayOf(it) } ?: emptyArray())
        if (names.any { it.equals(bot, ignoreCase = true) }) {
            Log.d(TAG, "harvestHotWordBoard: 旁路解析成功 @$bot -> chatId=${chat.id}")
            return@runCatching chat
        }
    }
    Log.d(TAG, "harvestHotWordBoard: 旁路搜索 @$bot 命中 ${ids.size} 候选但用户名验身全不符，放弃旁路")
    null
}.onFailure {
    Log.d(TAG, "harvestHotWordBoard: 旁路解析失败 @$bot: ${it.message}")
}.getOrNull()
