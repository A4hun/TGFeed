package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeoutException

// ---------------- R363/R364：TDLib 隐性限流止血（null 回落风味超时包装） ----------------

/**
 * 广场族 TDLib 请求超时时长（毫秒）单源——找/建/加入/发送/拉取同一档（R363 任务书
 * 定值 12s）。与 [REQUEST_TIMEOUT_MS]（10s 网关通用标准档）分档的考量：广场开启是
 * 用户手势直达路径（设置页开关手势→ensurePlaza 全链），给隐性限流环境多 2s 余地；
 * 全广场族只许引用本常量，不许各处各写一份数字（纪律§一）。
 */
internal const val PLAZA_TD_TIMEOUT_MS = 12_000L

/**
 * 广场拉历史放宽档（毫秒）：GetChatHistory 大群首拉比单次 Get/Send 重，
 * 同一止血语义下放宽到 20s（R363 任务书定值）。
 */
internal const val PLAZA_TD_HISTORY_TIMEOUT_MS = 20_000L

/**
 * 存储治理重扫描放宽档（毫秒，R364 二期加档）：GetStorageStatistics/OptimizeStorage/
 * GetDatabaseStatistics 是全量文件/消息库扫描（华为平板基线实测秒级~十几秒横跨
 * 首拉窗口，超大号更重），12s 默认档会误伤正常大号——同一止血语义下放宽到 30s。
 * 只许引用本常量，不许各处各写一份数字（纪律§一·单口常量族）。
 */
internal const val STORAGE_TD_TIMEOUT_MS = 30_000L

// ---------------- R169 P0-2 / F1+F13：网关请求统一超时共享件 ----------------

/**
 * 标准请求超时（毫秒）——全仓 TDLib 请求的 10s 唯一真源（F13 口径分裂止血）。
 * 值沿用 R45/R167 先例（GetChatHistory/搜索路径 10s）；月级低频长跳请求
 * （考古探测 30s、封面下载 15s 等专用档）各自另有命名常量，不经本值。
 * census P1-3 的 23 处裸字面量收口到本常量属 P2 挂账，渐进迁移。
 * 改这个值必须同步评估所有 sendReq 调用点（GatewaySendReqTest 锚定 10s）。
 */
internal const val REQUEST_TIMEOUT_MS = 10_000L

/**
 * withTimeout 超时异常 → 普通失败异常（[sendReq] 的核心契约，独立成函数便于单测锚）：
 * TimeoutCancellationException 是 CancellationException 子类，原样上抛会被两类
 * 调用方误读——`catch(CancellationException){throw e}` 的挂起环把它当协程取消
 * 上抛（如 loadAllChatsInList），runCatching 则把它吞成静默取消——超时必须转成
 * 普通异常走「失败」路径（既有 catch 回退路照常接管）；真协程取消不是
 * TimeoutCancellationException，不受影响、仍原样上抛。
 */
internal fun timeoutToFailure(e: TimeoutCancellationException, what: String): TimeoutException =
    TimeoutException("TDLib 请求超时 ${REQUEST_TIMEOUT_MS}ms: $what")

/**
 * 网关统一超时请求（R169 P0-2，F1「无超时挂起」家族的共享正解）：
 * `sendReq(f) = withTimeout(REQUEST_TIMEOUT_MS) { client.send(f) }`。
 *
 * 为什么必须有（R45 家族史 + R165 实锤）：TDLib native 冻死（bad_alloc / mmap OOM）
 * 时裸 `client.send` 无限挂起，协程永占 loading 闸/互斥闸且连 catch 都进不去
 * （F12 连坐：挂死时连失败日志都打不出）。本包装统一兜 10s 超时并落一行 W 日志
 * （R144 教训：无超时版挂死零证据），超时按失败走调用方既有回退路。
 *
 * 迁移次序（R169 本包）：GatewaySync 全 5 处（云同步环一次挂死=永久静默死，最优先）
 * → GatewayComments 28/34/94/100（弹幕）→ GatewayChannelDirectory 128/187/204/210
 * （深链+@名解析）→ TdLibGateway:874 LoadChats（启动枚举）。
 * GatewayFeedFetch 内两处入口由 R167 自收（在飞手术，本包不碰该文件）；
 * 其余 ~110 处裸 send 渐进迁移挂账 family-census P2 清单。
 */
internal suspend fun <T : TdApi.Object> TdLibGateway.sendReq(f: TdApi.Function<T>): T =
    try {
        withTimeout(REQUEST_TIMEOUT_MS) { client.send(f) }
    } catch (e: TimeoutCancellationException) {
        val what = f.javaClass.simpleName
        Log.w(TAG, "TDLib 请求超时（10s 到点，按失败走调用方回退路）: $what")
        throw timeoutToFailure(e, what)
    }

// ---------------- R379：静默限流防线接线（超时信号源 → 熔断状态机） ----------------

/**
 * R379 what→family 映射（单源）：只映射搜索族**真发送**口的 what 前缀——
 * searchPublicChatGuardedEx / searchChatsOnServerGuarded 两个统一发送口的真发送
 * （R184 口径=全 app 唯一真发送点，§九）。**不映射**：
 *  - 外层委托包装（如 plazaFindChat/SearchPublicChat——真发送在内部统一发送口，
 *    映射=同一发送双计打击+双计配速）；
 *  - 验身类（searchPublicChatEx/GetChat 缓存验身——GetChat 非账号桶消耗）；
 *  - GetChatHistory 族（挂起另有归因=TDLib native 冻死/网络劣化，与账号桶静默限流
 *    不同病——静默家族覆盖扩展挂账 R379）；
 *  - SearchChatMessages（会话内消息搜索）/SearchPublicPosts（口味巡逻，另有日配额）
 *    ——非账号桶族。
 * 新增搜索族真发送口时须同步本映射（前缀口径），否则过闸前置/静默打击对它空转。
 */
internal fun silentFloodFamilyOf(what: String): String? = when {
    what.startsWith("searchPublicChatEx/SearchPublicChat") -> FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    what.startsWith("searchChatsOnServer/SearchChatsOnServer") -> FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER
    else -> null
}

/**
 * R363 广场族统一超时包装（null 回落风味，[sendReq] 的姊妹件）：
 * `withTdTimeout(what = "…") { client.send(f) }`——到点返回 null 并落一行 W 日志，
 * 由调用方按既有返回型别折算诚实失败（Failed/false/emptyList/null）。
 *
 * 判词（KDoc 记病与环境归因）：**TDLib 隐性限流 = 请求挂起无响应的环境病**——
 * 不返回 429 也不返回错误，promise 永不落地（R361 官方广场创建卡死实锤：裸
 * `client.send(SearchPublicChat)` 跨三进程复现永挂，UI「正在加入广场…」转圈到永远）。
 * 与 [sendReq] 的差异只是失败风味（throw vs null 回落）：广场族全函数本就
 * runCatching 静默降级，null 回落比异常更顺；本批止血口径=广场族裸 send 全走
 * 本包装，超时只是上限闸——正常网络下一切路径与原逻辑逐位一致（零行为回破）。
 * 真协程取消（外层 Job 取消）抛的不是 TimeoutCancellationException，原样上抛不吞。
 *
 * R364 二期收编：本包装从广场族扩到搜索族（GatewayBotSearch 12 处——含 Guarded
 * 发送口本体 searchPublicChatGuardedEx/searchChatsOnServerGuarded：有熔断/账本
 * 但无超时=隐性限流永挂真身，本包装补上上限闸；熔断/账本/配额计费语义零改，
 * 超时按「其余瞬态失败」同族回落，不报成功、不上报 429、已记预算不退）与下载族
 * （GatewayDownload 19 处；存储治理重扫描走 [STORAGE_TD_TIMEOUT_MS] 放宽档）。
 *
 * R366 三期收编：GatewayLiveSearch 6 处（liveAsk 发词 SendMessage=「已问 N 实体，
 * 等待应答…」长转真身——发送超时如实报失败不落假等待；同批把 liveAsk 发词接上
 * [TdLibGateway.accountWriteBlocked] 写闸，§十 缺口收口）+ GatewayDiscovery 21 处
 * （发现枚举/简介/档案/加入态判定链；主列表 GetChats 超时按既有「失败上抛」语义
 * 走 `?: error(...)` 不落空表冒充枚举成功）+ GatewayChannelDirectory 8 处（开频道
 * 自愈/深链/邀请链兜底；邀请链超时如实报「超时」不落「无效/失败」泛词，真协程
 * 取消不吞）。同类剩余面（TdLibGateway 50/GatewayChannelSearch 4/GatewayInline 4/
 * GatewayCover 6/GatewayAggressivePreload 3/GatewayFeedFetch 1）属 R366 挂账清单，
 * 单独立四期评估。
 *
 * R379 静默限流防线接线（第三层·最后一道机械防线）：本包装=R363~366 沉淀的挂起
 * 信号**唯一消费口**——TG 隐性限流不发 429 只挂起，超时是静默限流在场的唯一信号：
 *  - **过闸前置**：[silentFloodFamilyOf] 命中搜索族真发送的调用，发送前先过
 *    [FloodCircuitBreaker.silentGuard]（[FloodCircuitBreaker.hub] 进程级挂载）——
 *    REJECT=不发请求按既有 null 回落（零请求零承压；拒发行由 silentGuard 内落，
 *    开闸态「限流静默中 family=… 余 H:mm」+配速态两味）；PROBE=放行（R58 计数归
 *    统一发送口，本口不双扣）；PASS=照发。hub 未挂（演示模式）=防线空转照发；
 *  - **静默打击上报**：超时分支向 [FloodCircuitBreaker.silentStrike] 喂打击信号
 *    （与 429 上报并行不替代）——同族 10min 滑窗 ≥3 次超时开闸走退避梯
 *    （30min→2h→8h→24h 封顶，R58 半开状态机复用零另起）。
 * 搜索页 liveAsk 显式提交链零碰（闸与打击都在本包装与熔断表内，行为改动只作用于
 * 搜索族真发送口）。429 既有路径语义零改（负锚）。
 *
 * @param timeoutMs 默认 [PLAZA_TD_TIMEOUT_MS]；拉历史传 [PLAZA_TD_HISTORY_TIMEOUT_MS]
 * @param what 请求名（日志用，强制调用方命名，拒绝无证据静默超时——R144 教训）
 */
internal suspend fun <T> withTdTimeout(
    timeoutMs: Long = PLAZA_TD_TIMEOUT_MS,
    what: String,
    block: suspend () -> T,
): T? {
    // R379 过闸前置（挂起前的拒发）：搜索族真发送先过熔断表静默防线——REJECT=零请求
    // 零承压按既有 null 回落（拒发行已由 silentGuard 落）；hub 未挂=演示模式防线空转
    val family = silentFloodFamilyOf(what)
    if (family != null && FloodCircuitBreaker.hub()?.silentGuard(family) == FloodCircuitBreaker.GateDecision.REJECT) {
        return null
    }
    return try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        // R379 静默打击上报（与 429 上报并行不替代）：超时=隐性限流在场唯一信号，
        // 喂熔断表滑窗计数（≥3 in 10min 开闸走退避梯）
        if (family != null) FloodCircuitBreaker.hub()?.silentStrike(family)
        Log.w(TAG, "TDLib 请求超时（可能限流）: $what（${timeoutMs}ms 无响应，按诚实失败回落）")
        null
    }
}
