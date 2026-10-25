package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Inline bot 盲盒流（R135，ideas.md #4）：把 @gif/@vid 这类"为 inline 而生"的
 * 大公共 bot 变成可刷 FeedSource——接上全 TG 机器人宇宙的即席内容水龙头。
 *
 * 链路：关键词 → GetInlineQueryResults（offset 续拉翻页）→ 逐条
 * SendInlineQueryResultMessage 发进收藏夹（inline 结果只有经消息上下文才有
 * 稳定 fileId）→ 从发出的消息转 VideoItem（视频/图/动图三分支，可播）。
 *
 * 盲盒语义：
 *  - 关键词来自 [CHAOS_KEYWORDS] 混沌词池随机抽（每次 reset 重抽，即"摇一把"），
 *    用户在源面板也可输入自定词（[customQuery]，固定不重抽）；
 *  - 每拉一页都是 bot 即席匹配的当刻内容，同一个词再刷也会换一批；
 *  - 单个 bot 在本词上翻到底（nextOffset 尽）→ 换池内下一个 bot 同词续供
 *    （视频→动图→图片轮着来，内容形态也是盲盒的一部分）。
 *
 * 防机器人纪律（克制三原则，实装在 [nextPage] 编排里）：
 *  1. 节流：单 bot 两次查询至少隔 [BOT_QUERY_MIN_INTERVAL_MS]（进程级账，
 *     跨源实例/跨 loadMore 轮共享）；发收藏夹逐条隔 [SEND_PACING_MS]——
 *     不高频轰炸单个 bot；
 *  2. 降级：bot 拒答/超时/限流/空结果 → 静默换池内下一个 bot；单次 nextPage
 *     里每个 bot 只试一次（[triedBots] 预算账），全池硬失败才向上报错提示
 *     用户，全池空结果只重抽一次词——绝不重试轰炸；
 *  3. 验证墙透传：bot 返回"点按钮验证/启动机器人"类结果（results 空 +
 *     InlineQueryResultsButton）时如实把按钮文案透传给用户，不绕过、不代点
 *     ——这是 bot 的风控决定，绕过既不礼貌也走不通（服务端不吐结果）。
 *
 * 观测日志（TAG="InlineBotFeed"）：每页一行"inline 结果数/转收藏夹数/失败数"，
 * 降级换台、验证墙、限流熔断各有专门行。
 */
class InlineBotFeedSource(
    private val gateway: TdLibGateway,
    customQuery: String? = null,
) : FeedSource {

    /** 搜索源标识（与 DeepSearchFeedSource 同一约定：带 searchQuery 标识搜索源） */
    override val request: FeedRequest =
        FeedRequest(searchQuery = "盲盒:${customQuery?.trim()?.takeIf { it.isNotBlank() } ?: "随机"}")

    /** 用户自定词：非空则固定（reset 不重抽），空 = 每轮从混沌词池随机挑 */
    private val custom = customQuery?.trim()?.takeIf { it.isNotBlank() }

    /** 当前盲盒词（reset 时无自定词则重抽——盲盒"摇一把"的机关） */
    private var keyword: String = custom ?: rollChaosKeyword()

    /** 当前 bot 在 [INLINE_BOT_POOL] 的下标（失败降级/翻到底换台时前移，环转） */
    private var botIndex = 0

    /** 当前 bot 的翻页游标（GetInlineQueryResults.nextOffset 续拉）；null = 从头 */
    private var offset: String? = null

    /**
     * 本次 nextPage 调用内已试过的 bot（一轮预算：每个 bot 至多一次）。
     * nextPage 返回时自然作废（下一轮 loadMore 重新记账），无需清理点。
     */
    private val triedBots = HashSet<String>()

    override suspend fun nextPage(limit: Int): FeedPage {
        triedBots.clear() // 新一轮预算
        var hardFail = 0   // 本轮真失败（解析/拒答/超时）的 bot 个数
        var rerolled = false
        while (true) {
            // 预算闸：全池硬失败（个个报错）→ 如实报错；空结果（bot 正常但没货）→
            // 只重抽一次词再试一轮；已重抽过（或用户自定词）→ 优雅到底，刷新可重摇
            if (triedBots.size >= INLINE_BOT_POOL.size) {
                if (hardFail >= INLINE_BOT_POOL.size) throw allBotsFailed()
                if (rerolled || custom != null) {
                    Log.i(TAG, "盲盒全池无内容（q=$keyword，硬失败 $hardFail 个已另行记日志），本轮到底——刷新可重摇")
                    return FeedPage(emptyList(), null)
                }
                rerolled = true
                triedBots.clear()
                hardFail = 0
                keyword = rollChaosKeyword()
                offset = null
                botIndex = 0
                Log.i(TAG, "盲盒全池空，重抽词再试一轮：q=$keyword")
            }

            val bot = INLINE_BOT_POOL[botIndex]
            triedBots += bot
            // 防熔断解析：正路 SearchPublicChat 熔断时走 SearchChatsOnServer 旁路，
            // 双路全败有 5 分钟负缓存（见 GatewayInline.kt），不会轰炸任何方法族
            val botUserId = runCatching { gateway.resolveInlineBotUserId(bot) }.getOrNull() ?: -1L
            if (botUserId <= 0L) {
                hardFail++
                Log.w(TAG, "盲盒降级：@$bot 用户名解析失败（q=$keyword）")
                botIndex = (botIndex + 1) % INLINE_BOT_POOL.size
                continue
            }

            // 节流闸：进程级 per-bot 最小查询间隔（防 loadMore 链式续拉把 bot 打爆）
            awaitBotQuerySlot(bot)

            val page = gateway.getInlineQueryResults(botUserId, keyword, offset)
            if (page == null) {
                hardFail++
                Log.w(TAG, "盲盒降级：@$bot 拒答/超时/限流（q=$keyword，本轮不再重试它）")
                botIndex = (botIndex + 1) % INLINE_BOT_POOL.size
                continue
            }
            // 验证墙透传：bot 要用户先互动——如实报告，不绕过、不代点、不静默换台装没事
            if (page.startBotText != null) {
                throw IllegalStateException(
                    "盲盒流：@$bot 要求先互动「${page.startBotText}」——请在 Telegram 官方客户端完成验证后再来",
                )
            }

            val media = page.results.filter { it.isPlayableInlineMedia() }
            Log.i(
                TAG,
                "盲盒 @$bot q=$keyword offset=${offset ?: "头"} → inline 结果 ${page.results.size} 条" +
                    "（可播媒体 ${media.size} 条，翻页 ${if (page.nextOffset != null) "有" else "无"}）",
            )
            if (media.isEmpty()) {
                // 本页空：游标还在 = 服务端说后续有货 → 空页照常续拉（调用方链式续页）；
                // 游标尽 = 该 bot 在本词到底 → 换下一个 bot 同词续供（预算闸兜重抽/到底）
                if (page.nextOffset != null) {
                    offset = page.nextOffset
                    return FeedPage(emptyList(), offset)
                }
                botIndex = (botIndex + 1) % INLINE_BOT_POOL.size
                continue
            }

            // 逐条发收藏夹转可播条目：节流 pacing + 连败熔断（防限流放大）。
            // R141 条目标题去 bot 化：不再统一"盲盒流 @bot"（实现词穿透 UI），
            // 改用内容自身信息（结果自带 title/description，空回落媒体类型词）；
            // 本轮"盲盒流 · 词"的身份交代交给 Feed 开场卡
            val cap = minOf(limit, media.size)
            val items = ArrayList<VideoItem>(cap)
            var fails = 0
            var streak = 0
            var fused = false
            for (i in 0 until cap) {
                awaitSendSlot()
                val resultId = media[i].inlineResultId() ?: continue
                val item = runCatching {
                    gateway.sendInlineResultToSaved(page.queryId, resultId, media[i].contentLabel())
                }.getOrNull()
                if (item != null) {
                    items += item
                    streak = 0
                } else {
                    // 单条失败只丢单条（消息发出但内容不可转 = 正常滤损）；
                    // 连败到阈值 = 像限流/拒发 → 立即停发整页，剩下的本页不要了
                    fails++
                    streak++
                    Log.w(TAG, "盲盒转收藏夹失败 1 条（@$bot 第 ${i + 1} 条，连败 $streak）")
                    if (streak >= SEND_FAIL_STREAK_STOP) {
                        fused = true
                        Log.w(TAG, "盲盒发送连败熔断：@$bot 本页停发（防限流放大）")
                        break
                    }
                }
            }

            // 游标推进：本 bot 翻到底 → 换台（下页从下一个 bot 同词续供）；没到底续拉
            if (page.nextOffset == null) {
                offset = null
                botIndex = (botIndex + 1) % INLINE_BOT_POOL.size
            } else {
                offset = page.nextOffset
            }

            // 一条都没转成且发生熔断 → 如实报错（不硬刷）；非熔断的空转换按空页续拉
            if (items.isEmpty()) {
                if (fused) {
                    throw IllegalStateException("盲盒流：发收藏夹连续失败（可能被限流），稍后再试")
                }
                return FeedPage(emptyList(), offset)
            }
            Log.i(
                TAG,
                "盲盒 @$bot q=$keyword 本页产出 ${items.size} 条" +
                    "（inline ${page.results.size} / 转收藏夹 ${items.size} / 失败 $fails 条" +
                    "${if (fused) "，连败熔断停发" else ""}）",
            )
            return FeedPage(items, offset)
        }
    }

    /** 摇一把：游标回起点；无自定词时重抽混沌词（盲盒核心机关） */
    override fun reset() {
        offset = null
        if (custom == null) keyword = rollChaosKeyword()
        // botIndex 不重置：从降级换到的新台起刷更盲盒；节流账仍在管频率，无轰炸风险
    }

    /** 全池硬失败的统一收口（Feed 的 error 态会如实展示给用户，不静默装死） */
    private fun allBotsFailed(): IllegalStateException =
        IllegalStateException("盲盒流：所有机器人都没有响应（可能被限流），稍后再试或换个词摇一把")

    // ---------------- 进程级节流账（跨源实例共享：多入口同时刷也只一份频率） ----------------

    companion object {
        private const val TAG = "InlineBotFeed"

        /**
         * inline bot 池（降级序）：只收"为 inline 而生"的大公共 bot——服务端
         * 专为高频即席查询设计、容忍度高；需要 /start 验证的小 bot 一律不进池。
         *  - vid：视频（InlineQueryResultVideo）
         *  - gif：动图（InlineQueryResultAnimation，TG 的 GIF 实为 mp4，直接可播）
         *  - pic：图片（InlineQueryResultPhoto）
         */
        internal val INLINE_BOT_POOL = listOf("vid", "gif", "pic")

        /**
         * 混沌词池：盲盒摇号用。选词原则：泛兴趣名词（bot 库里存货多）、
         * 视觉系（视频/图两类 bot 都出得来货）、内容安全（无露骨词）；
         * 中英混排——@vid/@gif/@pic 的公开内容目录英文占比高，英文词保底命中率。
         */
        internal val CHAOS_KEYWORDS = listOf(
            "猫咪", "狗狗", "熊猫", "美食", "樱花", "极光", "海浪", "日落", "星空", "旅行",
            "cats", "dogs", "panda", "food", "ocean", "sunset", "space", "city", "dance", "snow",
        )

        /** 单 bot 两次查询的最小间隔（毫秒）：getInlineQueryResults 是 bot 的最敏感入口 */
        internal const val BOT_QUERY_MIN_INTERVAL_MS = 1_500L

        /** 两条"发收藏夹"之间的最小间隔（毫秒）：SendMessage 族请求的礼貌节奏 */
        internal const val SEND_PACING_MS = 350L

        /** 单页内发送连败熔断阈值：连败这么多条立即停发整页（防限流放大） */
        internal const val SEND_FAIL_STREAK_STOP = 3

        /** per-bot 查询节流账（进程级，跨源实例/跨 loadMore 轮共享） */
        private val botQueryLastAt = ConcurrentHashMap<String, Long>()

        /** 发送节流账（进程级单账：收藏夹是单会话，全局一条队） */
        @Volatile
        private var lastSendAt = 0L

        /** 盲盒摇号：从混沌词池随机挑一个（reset 重抽的机关本体） */
        internal fun rollChaosKeyword(): String =
            CHAOS_KEYWORDS[Random.nextInt(CHAOS_KEYWORDS.size)]

        /** 节流闸：等到 [bot] 的查询间隔期满并占位（自旋 delay，不忙等） */
        private suspend fun awaitBotQuerySlot(bot: String) {
            while (true) {
                val now = System.currentTimeMillis()
                val earliest = (botQueryLastAt[bot] ?: 0L) + BOT_QUERY_MIN_INTERVAL_MS
                if (now >= earliest) {
                    botQueryLastAt[bot] = now
                    return
                }
                delay(earliest - now)
            }
        }

        /** 节流闸：等发送间隔期满并占位 */
        private suspend fun awaitSendSlot() {
            while (true) {
                val now = System.currentTimeMillis()
                val earliest = lastSendAt + SEND_PACING_MS
                if (now >= earliest) {
                    lastSendAt = now
                    return
                }
                delay(earliest - now)
            }
        }
    }
}

/**
 * 该 inline 结果是否"发进收藏夹能转出可播条目"：只认 Video/Photo/Animation
 * 三类（toVideoItem 的三分支全覆盖）；Article/Sticker/Contact 等发出去也是
 * 不可播消息，白污染收藏夹，直接滤掉。
 */
private fun TdApi.InlineQueryResult.isPlayableInlineMedia(): Boolean = when (this) {
    is TdApi.InlineQueryResultVideo, is TdApi.InlineQueryResultPhoto,
    is TdApi.InlineQueryResultAnimation,
    -> true
    else -> false
}

/**
 * 该 inline 结果的用户可见标题（R141 条目标题去 bot 化）：用内容自身信息——
 * bot 目录里这条结果自带的名字（视频标题/图片描述），空则回落媒体类型词；
 * bot 用户名不再进条目标题（身份只留在 Feed 开场卡）。
 */
private fun TdApi.InlineQueryResult.contentLabel(): String = when (this) {
    is TdApi.InlineQueryResultVideo -> title.takeIf { it.isNotBlank() } ?: "视频"
    is TdApi.InlineQueryResultPhoto ->
        description?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: "图片"
    is TdApi.InlineQueryResultAnimation -> title.takeIf { it.isNotBlank() } ?: "动图"
    else -> "盲盒内容"
}

/**
 * inline 结果的 resultId：基类 InlineQueryResult 无公共 id 字段（javap 实证，
 * id 只声明在各子类上），三步收集发收藏夹所需的引用 id；其余类型返回 null。
 */
private fun TdApi.InlineQueryResult.inlineResultId(): String? = when (this) {
    is TdApi.InlineQueryResultVideo -> id
    is TdApi.InlineQueryResultPhoto -> id
    is TdApi.InlineQueryResultAnimation -> id
    else -> null
}
