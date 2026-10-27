package dev.tgfeed.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.DemandSubscription
import dev.tgfeed.core.feed.FeedFilter
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.GroupAnswerRow
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.LiveAskState
import dev.tgfeed.core.feed.SearchSortOrder
import dev.tgfeed.core.feed.applySearchSortOrder
// R299 B4 OCR 命中标注（FeedOcrConsumePart 扩展件；FeedViewModel.kt 本体零触碰）
import dev.tgfeed.core.feed.ocrHitKeysImpl
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.FilterSheet
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppSectionTitle
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分层搜索页（市面搜索产品口径重做：本地先答 + 服务端流式汇入，SEARCH-ARCH §2 分层呈现落地）。
 *
 * 分层时序（打字停笔 300ms 防抖后同时发起两路）：
 *  1. L3 本地即时层：vm.localSearchInstant（收藏/稍后/历史/深度索引 contains 匹配，纯本地
 *     毫秒级返回），结果立即上屏、封面左上角标"本地"徽标——打字就有结果，不等网络；
 *  2. L1 服务端全量扇出：vm.fullSearch（逐频道 SearchChatMessages，边到边聚合），
 *     结果到达即并入网格——没有"等 540 个频道搜完"的卡顿感；频道腿总时长 30 秒硬顶，
 *     超时自动收尾（剩余频道随滚动 fullSearchMore 续搜），不再无限磨。
 *  两组结果在同一网格混排：本地在前、服务端在后，按 item.key 去重（同一条目本地优先保留）。
 *
 * 其他产品化要点：
 *  - 空词时搜索框下方是建议面板：搜索历史 chips + 口味词建议（vm.tasteSuggestions）chips，
 *    点击直接搜（走显式提交路径写历史）；清空搜索框即回到建议面板；
 *  - 覆盖度不当主角：底部常驻覆盖度条撤掉，改为结果网格右下角浮动小进度 pill
 *    （"已搜 x/y 频道·N 条结果" + 迷你转圈 + "停止"小字），半透明不挡结果；
 *    只在频道腿进行中显示，本地+频道腿结束/超时/停止后自动消失（不再定格残留）；
 *  - 结果网格上方一行小字图例"本地 N · 频道 M"，标明两路构成；
 *  - 结果卡统一结构（视频/图文/音频同构）：封面（无封面=类型图标占位）+ 类型角标 +
 *    时长 chip + 标题 2 行（caption 命中词高亮 buildCaptionHighlight）+ 来源频道；
 *  - 空态纪律：本地有结果时不显示空态；两路全空且本轮搜索结束才显示 kit AppEmptyState
 *    （"没有找到相关内容"，主按钮=本地深度搜索；主按钮下方再挂页面级次级文字钮
 *    "用机器人搜全网"——"本地没有就搜全网"的核心兜底，走 searchViaBot 原路径）；
 *    防抖窗口内（query ≠ lastSearched）一律显示加载态（kit AppLoading），不闪空态；
 *  - 类型 tab / Tune 筛选（FeedFilter）对本地与服务端结果同口径生效（上屏前统一过滤）；
 *  - 滚动接近底部自动续搜（vm.fullSearchMore）；点条目 onPlay(item) 回 Feed 定位播放。
 *  - 搜索联动·"群友刚答的"区（显式提交限定，vm.liveAskSearch）：回车/点搜索/带词进页自动开搜
 *    （openSearch 入口）时后台并行把关键词发给已发现搜索实体（KIND_BOT 优先、搜索群其次，一词一轮冷却 + 秒回专用预算车道 +
 *    黑名单剔除；与收割车道解耦，后台收割吃光预算不饿死秒问），监听窗口（≤12s，静默 2.5s 提前收工）内应答实时解析流入结果页顶部新区；
 *  - 两个答案区（"群友刚答的"实时 + "群友搜到过"历史）统一 eager 解析口径（用户拍板"直接展示成内容，不跳转"）：
 *    每区每词/轮前 8 条链接批量解析成可播条目（秒回区占秒回车道、历史区占查询车道，整批各记 1 次，
 *    失败进死信不反复重试），解析出的以内容卡横滑区内联展示（网格同款 SearchResultCell + 真图通道）、
 *    点击原地播放（onPlay 现有链）；解析不出/没轮到的回落频道标题行（图谱零网络标题，没标题"识别中…"），
 *    点击无动作；纯文本回复照旧答案卡；bot 没回/超时静默收场，绝不挡主搜索结果；
 *    过闸链接回灌 AnswerStore/图谱/热词。
 *  - R371 多维度排序（用户 10:4x 实锤「业界该有的能力咱们都得具备」）：结果网格上方
 *    「相关度/最新/频道热度/时长」四档 chips（单源件 core/feed/SearchSort.kt；默认档=
 *    相关度=现状混排**原样不动**（负锚）；纯客户端排序零新网络请求；频道热度=本地图谱
 *    成员数查表（未知沉底诚实降级）；档位 AppPrefs.searchSortOrder 跨会话记忆）；
 *  - R371 返回不重搜（用户实锤「预览完返回他会重新搜索」根因修复）：页内已提交词回写
 *    容器 pendingSearchQuery（doSearch/submitSearch 提交点+页内清态镜像清零）——返回键
 *    消费器 openSearch(令牌)→initialQuery 恒等于本词，R107/H5「结果属于本词」判定恒
 *    命中=零重搜零 liveAsk 重发，返回页首帧结果瞬时在场（本地层毫秒补跑）；
 *  # 开头查询走双模式（话题精确 + 全文宽泛，网关层合并），搜索框下方有解释文案。
 *  - R397 批量入分屏：结果区头部（排序 chips 行下、两路图例上）「分屏播放 N 条」
 *    文字钮——把当前所见结果（mergedResults，混排去重+排序+类型筛选后=屏幕所显）
 *    整批种进分屏片单并直接开分屏（统一机制 App.addBatchToGrid；null playUri 的
 *    本地命中条目入格后走格内 ensurePlayable 现拉，与 Feed 侧同款链）。
 */
@Composable
fun SearchScreen(
    gateway: TdGateway,
    initialQuery: String = "",
    onPlay: (VideoItem, List<VideoItem>) -> Unit,
    onBack: () -> Unit,
    // 答案区频道行点击（R48 带词进频道）：打开该频道内匹配当前搜索词的 Feed——
    // 第二参数是该行所在答案区自己的搜索词（秒回区=state.query，历史区=lastSearched），
    // 落地即出匹配资源而非通用频道流；词空时 MainActivity 兜底单频道 Feed（旧行为）。
    // 不挂默认值：本函数体巨大，默认参数掩码叠加触发过 ART VerifyError（实证），
    // 唯一调用方 MainActivity 已显式传入
    onOpenChannel: (Long, String) -> Unit,
    // R397 批量入分屏：宿主（MainActivity）收口——种进容器片单 + 剥落本页 + 开分屏。
    // 实数在钮文案（mergedResults.size），实增数/重复数由宿主 Toast 如实报账。
    // 同上不挂默认值（VerifyError 纪律，唯一调用方显式传入）
    onBatchToGrid: (List<VideoItem>) -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    // 与 FeedScreen 同一实例的共享 VM（同 key）：全量搜索状态跨页共享，onPlay 切源也切在它身上
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    // 服务端车道结果（边到边聚合上屏）
    val results by vm.fullSearchResults.collectAsState()
    val coverage by vm.fullSearchCoverage.collectAsState()
    val searching by vm.fullSearchLoading.collectAsState()
    // R324 离线降级（深搜面·可用档）：本地即时层+深索引离线照常出结果；频道腿（服务
    // 端搜索）离线不可用——等待文案与结果图例如实标注（NetworkWatch 单源）
    val networkOnline by dev.tgfeed.core.net.NetworkWatch.online.collectAsState()
    // 后台波段标记：30s 前台硬顶后剩余频道低强度续搜中（胶囊切低亮"后台继续搜索中…"）
    val bgSearching by vm.fullSearchBackground.collectAsState()
    // 倒排表建表进度（非 null = 正在构建/重建，此时召回可能基于部分索引）
    val indexBuild by vm.invertedBuildProgress.collectAsState()
    // 搜索结果也吃方向/时长/发布时间筛选（复用 Feed 的 FilterSheet + FeedFilter）
    val searchFilter by vm.searchFilter.collectAsState()
    // 搜索历史 chips 数据源（原始值）
    val searchHistoryRaw by vm.searchHistory.collectAsState()
    // B4 脏数据治理：历史里混有 URL 编码原文（%23asmr，存/读层在 LocalStore/VM 不做编解码，
    // 编码串由外部入口带入），这里在显示与点击搜索前统一解码还原（#asmr）；
    // 解码后可能撞词，distinct 去重防重复 chip
    val searchHistory = remember(searchHistoryRaw) {
        searchHistoryRaw.map { urlDecodeHistoryTerm(it) }.distinct()
    }
    // 单条历史删除（chip 右侧 ×）：core 侧 VM/LocalStore 无可调用的删除接口且不在本专项
    // 可动范围，删除态由 UI 层自持——独立 SharedPreferences 文件持久化"已删词集"
    // （展示口径 = 解码后词），展示时过滤；再次显式搜索同一词会自动复活
    // （submitSearch 里同步移出删除集，与 recordSearch 重新入列同拍）
    val uiStatePrefs = remember {
        app.getSharedPreferences("tgfeed_ui", android.content.Context.MODE_PRIVATE)
    }
    var deletedHistory by remember {
        mutableStateOf(uiStatePrefs.getStringSet("search_history_deleted", emptySet()) ?: emptySet())
    }
    val removeHistoryTerm: (String) -> Unit = { term ->
        // StringSet 返回值不得原地改（SharedPreferences 约定），一律构造新集再写回
        val next = deletedHistory + term
        deletedHistory = next
        uiStatePrefs.edit().putStringSet("search_history_deleted", next).apply()
    }
    // 实际展示的历史 = 已解码去重 − 已删词集（空列表时建议面板整节隐藏，不留光杆标题）
    val visibleHistory = remember(searchHistory, deletedHistory) {
        searchHistory.filterNot { it in deletedHistory }
    }
    var showFilterSheet by remember { mutableStateOf(false) }

    // 本地腿协程 + 模糊开关 prefs + 机器人兜底文字钮（searchViaBot 调用 + Toast 的 context）
    val scope = rememberCoroutineScope()
    val prefs = dev.tgfeed.core.prefs.AppPrefs(app)
    val context = LocalContext.current
    // R182 容器快捷访问：三个空态兜底跳转（深度搜索/机器人/资源群）切源回 Feed 时
    // 置「回搜索页」pending（FeedScreen 同款手法——appContainer 直取）
    val appContainer = (context.applicationContext as dev.tgfeed.App).container

    /**
     * R182 兜底跳转收口：切源（switchSource 已入源栈）→ 关搜索页 → 置「回搜索页」
     * pending + 时刻戳（须晚于切源入栈——返回消费器按「最新跳转先回」定序，第一次
     * 返回恒先回搜索页）→ 剥落发现/我的（搜索页若从发现热词进来，垫底页必须剥掉，
     * 否则 showFeed 被压住露出旧页——onPlay 同坑同款）。
     */
    val jumpToFeedFromFallback: () -> Unit = {
        onBack() // closeSearch（内部清 searchReturnPending——故 pending 须在它之后再置位）
        appContainer.searchReturnPending.value = true
        appContainer.searchReturnStamp = dev.tgfeed.core.feed.BackNavClock.next()
        appContainer.closeDiscover()
        appContainer.closeMine()
        appContainer.showFeed()
    }

    var query by remember { mutableStateOf(initialQuery) }
    // 最近一次已发起搜索的词：防抖 effect 与显式提交共用，防同词重复扇出；
    // 同时是结果卡的命中词高亮词（搜的是什么词就高亮什么词）。
    // 返回页（结果还在 VM）时初始化为本次关键词：否则防抖 effect 当"新词"
    // 重搜一遍把还在的结果清空（实测返回后频道结果消失成"频道 0"）。
    // R107/H5 修复：返回页判定从"VM 里还有结果"改为"结果属于本次进页词"
    // （比对结果关联词 fullSearchQuery）——结果只在 fullSearch 入口清，
    // 搜 A 退出后带词 B 进页时 VM 里仍是 A 的残留，旧口径会误判"已搜过 B"
    // （显示 A 的结果且不发 B 的搜索，货不对板）。
    // R371 同族洞收口：判定不再要求"结果非空"——isNotEmpty 是旧口径赘条件，
    // 令「已搜过但 0 命中/搜索在飞」的词返回时被当新词重扇出（quota 白烧+
    // 杀掉在飞会话重启扇出）；会话关联词相等即"已搜过"（0 结果也是有效结论）
    var lastSearched by remember {
        mutableStateOf(
            if (initialQuery.isNotBlank() &&
                vm.fullSearchQuery == initialQuery.trim()
            ) {
                initialQuery
            } else {
                ""
            },
        )
    }
    // 金矿 P3·资源群答案（答案库缓存层）：fullSearch 入口刷新；Pair.first=所属关键词，
    // 只对"当前已提交词"展示——防连续打字时旧词答案在新词加载态里闪现
    val groupAnswersState by vm.groupAnswers.collectAsState()
    val currentAnswers = groupAnswersState?.takeIf { it.first == lastSearched }?.second.orEmpty()
    // 搜索联动·资源群秒回（实时本轮问答，与上方历史答案一实一历）：显式提交时
    // vm.liveAskSearch 发起一轮；只对"当前已提交词"展示（同 groupAnswers 口径），
    // 无回复且窗口已关时 LiveAskSection 整区隐藏
    val liveAskState by vm.liveAsk.collectAsState()
    val currentLiveAsk = liveAskState?.takeIf { it.query == lastSearched }
    // "群友搜到过"区增强行（eager 解析后：可播内容卡前排 + 识别中标题行沉底）；
    // 只对"当前已提交词"展示（同 currentAnswers 口径）。原始答案流保留给"在资源群里搜"按钮用
    val groupAnswerRowsState by vm.groupAnswerRows.collectAsState()
    val currentAnswerRows = groupAnswerRowsState?.takeIf { it.first == lastSearched }?.second.orEmpty()
    // L3 本地即时层结果（页面态；与服务端结果混排、本地在前）
    var localResults by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    // 本地即时层完成标记：值=本地腿已返回的词（null=本地腿还没回/词已换）。
    // 加载态靠它区分"本地还没回（纯转圈）"与"本地无命中、频道腿还在搜（如实告知进度）"——
    // 空结果也要定格：本地秒级无命中是有效结论，不该一直裸转圈等频道腿
    var localDoneFor by remember { mutableStateOf<String?>(null) }
    // 模糊搜索开关（AppPrefs.fuzzySearch，默认关=精确：fold 归一 + 空格分段 AND；
    // 开=bigram 词位/错别字 ED≤1/同义词扩展）。简繁归一常开，不算模糊。
    // 类型 tab 行右端"模糊"chip 读写；切换后已搜词按新口径强制重搜
    var fuzzySearch by remember { mutableStateOf(prefs.fuzzySearch) }
    // R371 排序档（多维度排序，单源件 core/feed/SearchSort.kt）：跨会话记忆
    // （AppPrefs.searchSortOrder，缺省 RELEVANCE=现状混排负锚——默认档不改变
    // 本地在前+服务端 searchScore 序）；切换即写 prefs 并触发 mergedResults 重排
    //（纯客户端，零新网络请求）
    var sortOrder by remember { mutableStateOf(SearchSortOrder.fromName(prefs.searchSortOrder)) }
    // R371 频道热度档数据源（chatId→成员数；VM 本地查表 IO 线程消化，图谱∪发现
    // 档案零网络）：仅切到该档才查（其余档零成本），未知沉底诚实降级
    var memberCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    // 口味词建议（空词建议面板"猜你想搜"chips；进页一次性提取，纯本地同步计算）
    var tasteWords by remember { mutableStateOf<List<String>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 顶栏与建议面板的限宽居中统一走 adaptiveContentWidth（kit 三档口径），
    // 且与"是否聚焦/是否有输入/是否有结果"全部解耦——旧版 landscapeIdle 以
    // query.isBlank() 为条件，横屏下一打字输入框从居中 480dp 瞬间跳成全宽、
    // 清空又跳回（"输入位移"根因之一）；本版宽度只随窗口档位变化，三态同轴

    // 硬件返回键（修"搜索页返回断裂"：结果页 back×1 无反应、back×2 直退桌面）：
    // 结果态 → 先清回搜索初始态（取消在飞搜索）；初始态 → 剥落回 Feed
    androidx.activity.compose.BackHandler {
        if (lastSearched.isNotBlank() || query.isNotBlank()) {
            query = ""
            lastSearched = ""
            // R371：页内清回初始态同步清容器令牌（令牌恒镜像页面已提交词——
            // 用户刚清掉的结果态不该再被返回链拿旧词恢复出来）
            appContainer.pendingSearchQuery.value = ""
            localResults = emptyList()
            vm.cancelFullSearch()
        } else {
            onBack()
        }
    }

    // 发起一次搜索（同词去重；空词忽略）：本地即时层先上屏，服务端扇出边到边并入。
    // 两条腿独立真并行：本地腿在独立协程里跑（纯本地毫秒级），绝不等/不并 fullSearch 的
    // 网络腿；新词触发时 fullSearch 入口 Job.cancel 真取消上一代网络任务（整链停扇出，
    // 不只是丢弃结果），VM 内 localSearchGen 代次同时让旧词本地扫描在检查点中止
    val doSearch: (String) -> Unit = { raw ->
        val q = raw.trim()
        if (q.isNotEmpty() && q != lastSearched) {
            lastSearched = q
            // R371 修复：已提交词回写容器令牌（返回链单点）。pendingSearchQuery 原本只有
            // openSearch 入口写——页内手动打词/改词从不回写，令牌停留在进页词（空词进页
            // 手打=空串；带词进页后改词=旧词）；返回键消费器 openSearch(令牌)→initialQuery
            // 拿到脱钩令牌：空串=整页重置成建议面板（结果不可见，重打词=重搜——用户
            // 10:4x 实锤「预览完返回他会重新搜索」），旧词=顶回旧词且「结果属于本词」
            // 判定失守→真空开搜分支真开火（旧词重扇出+liveAsk 重发=quota 浪费）。
            // 回写后令牌恒镜像页面已提交词，返回恒恢复本词（结果还在 VM=瞬时在场）
            appContainer.pendingSearchQuery.value = q
            localDoneFor = null // 新词：本地腿重跑，完成标记先复位（加载态回到纯转圈）
            // L3 本地即时层：IO 协程毫秒级返回，不等网络先上屏（标"本地"）。
            // 过期词防护：返回时当前输入已变则丢弃（连续打字时旧词结果不回灌新词列表）
            scope.launch {
                val local = vm.localSearchInstant(q)
                if (query.trim() == q) {
                    localResults = local
                    localDoneFor = q // 本地腿已回：空结果也定格"本地无命中"（加载态换进度文案）
                }
            }
            // L1 服务端全量扇出：结果边到边聚合上屏（30 秒硬顶，超时自动收尾）
            vm.fullSearch(q)
        }
    }

    // 显式提交（IME 搜索键 / 建议 chip 点击）：写搜索历史 + 立即双路搜索（跳过防抖）。
    // 先置 query/lastSearched 再搜：防抖 effect 因 q == lastSearched 不再重复触发
    val submitSearch: (String) -> Unit = { term ->
        val q = term.trim()
        if (q.isNotEmpty()) {
            query = q
            lastSearched = q
            // R371：显式提交同样回写令牌（与 doSearch 同一语义的第二个提交点——
            // 历史chip/IME回车/建议chip都走这里；注释锚见 doSearch 处，不再重复）
            appContainer.pendingSearchQuery.value = q
            localDoneFor = null // 同 doSearch：本地腿重跑，完成标记先复位
            keyboard?.hide()
            vm.recordSearch(q) // 仅显式提交写历史（防抖实时搜索不写，同 recordSearch 约定）
            // 复活口径：显式搜过的词若曾被 × 删除，同步移出删除集——
            // 与 recordSearch 重新把它写入历史同拍，避免"搜了却不回历史列表"
            if (q in deletedHistory) {
                val next = deletedHistory - q
                deletedHistory = next
                uiStatePrefs.edit().putStringSet("search_history_deleted", next).apply()
            }
            scope.launch {
                val local = vm.localSearchInstant(q)
                if (query.trim() == q) {
                    localResults = local // 过期词防护，同 doSearch
                    localDoneFor = q
                }
            }
            vm.fullSearch(q)
            // 搜索联动：仅显式提交才并行问搜索实体（资源群秒回区数据源）——
            // 一词一轮冷却/收割车道预算/黑名单剔除全在 VM 与网关侧；防抖实时搜索绝不触发
            // （输入过程不发词，只在显式提交时发）；无实体/无回复静默收场，不挡主搜索
            vm.liveAskSearch(q)
        }
    }

    // 进页：自动获焦；口味词建议一次性提取（空词建议面板数据源）；
    // 带进关键词（顶栏提交跳进来）时自动开搜。
    // 返回键回搜索页（服务端结果还在 VM 里）时不重搜：直接展示，但本地层补跑一次——
    // 页面态 localResults 随重组丢失，不补跑则本地车道缺席、混排序被破坏
    LaunchedEffect(Unit) {
        // R437 H5：只在「新搜索会话」自动获焦弹键盘（搜索页第一诉求=进页即输入）；
        // 返回页（服务端结果还在 VM，initialQuery 与 fullSearchQuery 同词）不弹——
        // 键盘半屏盖住刚恢复的结果列表，用户还得先收键盘才能看内容。
        val returningWithResults = initialQuery.isNotBlank() && vm.fullSearchQuery == initialQuery.trim()
        if (!returningWithResults) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        tasteWords = vm.tasteSuggestions(8)
        if (initialQuery.isNotBlank()) {
            // R107/H5：返回页判定从"VM 有无结果"改为"结果是否属于本词"
            // （比对结果关联词 fullSearchQuery）——搜 A 退出后 VM 残留 A 的结果，
            // 带词 B 进页必须照常发起 B 的搜索，而不是把 A 的残留当 B 的结果展示。
            // R371 同族洞收口：判定不再要求结果非空（isNotEmpty=旧口径赘条件）——
            // 已搜过但 0 命中/搜索在飞的词，返回时照常恢复（空态/加载态如实呈现），
            // 不再当新词重扇出（quota 白烧+杀在飞会话重启扇出）；0 结果同词 10 分钟
            // 会话缓存不落盘（空结果不缓存），本判定是它唯一的零成本复跑守门
            val resultsBelongToInitial = vm.fullSearchQuery == initialQuery.trim()
            if (!resultsBelongToInitial) {
                doSearch(initialQuery)
                // 搜索联动：带词进页自动开搜同样并行问搜索实体（与显式提交同口径——
                // 顶栏历史 chip/发现页热词 chip/猜你想搜全走 openSearch 这条入口，
                // 不挂则最高频入口完全漏问）；一词一轮冷却闸兜底，重复进页/同词连进不重发。
                // 注意只挂"真空开搜"分支：返回页（结果还在 VM）分支没开搜就不问
                vm.liveAskSearch(initialQuery)
            } else {
                lastSearched = initialQuery // 标记已搜过，防抖 effect 不再重复扇出
                // R371 验证探针（脱敏口径=词长零内容）：返回恢复分支在场=零重搜的
                // logcat 实证锚（与 FeedSearchPart「R371 全量搜索入口」行互为反证对）
                android.util.Log.i("TGFlow", "R371 返回恢复零重搜：词长=${initialQuery.length} 本地补跑")
                localResults = vm.localSearchInstant(initialQuery)
                localDoneFor = initialQuery // 本地腿补跑完成，定格完成标记
            }
        }
    }

    // 输入防抖：停笔 300ms 自动搜（对标市面搜索的"打字就有结果"；本地层毫秒级先上屏，
    // 服务端扇出贵但结果边到边并入，不需要更长防抖）。清空搜索框时清掉本地结果回建议面板
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            localResults = emptyList()
            localDoneFor = null // 回到建议面板：本地腿完成标记一并复位
            return@LaunchedEffect
        }
        if (q == lastSearched) {
            // 同词回退场景：清空搜索框后重打同一个词，服务端结果还在 VM 里，
            // 但页面态本地结果刚被清空——补跑一次本地层（毫秒级，不触发服务端扇出）
            if (localResults.isEmpty()) localResults = vm.localSearchInstant(q)
            localDoneFor = q
            return@LaunchedEffect
        }
        delay(300)
        doSearch(q)
    }

    // 两路混排（本地在前）+ 按 key 去重（同一条目本地优先保留，"本地"徽标不丢）。
    // W5：再按内容指纹 fileUniqueId 去重（对齐 Feed 链路 FeedSource.dedupeByFile 口径——
    // 该函数是其文件私有，此处内联同语义实现）：跨频道转发的同一张图/同一片只留一条，
    // 否则网格里同封面成排出现（"搜索封面大量重复"实锤之一）。
    // 本地序列在前，同文件冲突时本地命中优先保留。
    // 媒体类型 tab 与 FeedFilter 在上屏前对两路同口径套用（与服务端 publish 口径一致）
    val mediaTab by vm.searchMediaTab.collectAsState()
    // R371：排序档/热度查表并入 remember 键（RELEVANCE 档排序器原样返回=负锚不改序）
    val mergedResults = remember(localResults, results, mediaTab, searchFilter, sortOrder, memberCounts) {
        val seen = HashSet<String>()
        val seenFiles = HashSet<String>() // fileUniqueId 内容指纹（空串=无远端 id，不参与去重）
        val out = ArrayList<Pair<VideoItem, Boolean>>() // second=true 表示本地命中
        fun offer(item: VideoItem, local: Boolean) {
            if (!seen.add(item.key)) return
            if (item.fileUniqueId.isNotEmpty() && !seenFiles.add(item.fileUniqueId)) return
            out += item to local
        }
        localResults.asSequence()
            .filter { mediaTab < 0 || it.mediaType == mediaTab }
            .filter { searchFilter.matches(it) }
            .forEach { offer(it, true) }
        results.forEach { offer(it, false) }
        // R371 多维度排序（单源排序器）：默认档 RELEVANCE 原样返回不重排
        applySearchSortOrder(out, sortOrder, memberCounts)
    }
    // R371 频道热度档查表（仅该档触发；key=chatId 集合走结构性相等——只有结果频道集
    // 真变化才重查，后台波段流式汇入不逐拍刷表；memberCounts 更新→mergedResults
    // 重算→集合不变即止，链路自稳定）
    val resultChatIds = remember(mergedResults) { mergedResults.map { it.first.chatId }.toSet() }
    LaunchedEffect(sortOrder, resultChatIds) {
        if (sortOrder == SearchSortOrder.CHANNEL_HEAT && resultChatIds.isNotEmpty()) {
            memberCounts = vm.channelMemberCountsForSort(resultChatIds)
        }
    }

    // R299 B4 OCR 命中标注（「来自图中文字」徽标数据源，design-audio-photo §4.2）：
    // 结果批/查询词变化时 IO 批量判定（低信息 caption ∧ OCR 文本含词位）；
    // item_ocr 表不在（R292/T1b 未放量）= 空集零噪音；300ms 防抖跟随结果流增长，
    // 标注是锦上添花绝不阻塞渲染。
    var ocrHitKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(lastSearched, mergedResults.size) {
        if (lastSearched.isBlank() || mergedResults.isEmpty()) {
            ocrHitKeys = emptySet()
            return@LaunchedEffect
        }
        delay(300)
        ocrHitKeys = vm.ocrHitKeysImpl(mergedResults.map { it.first }, lastSearched)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ---------- 顶栏（kit AppTopBar 骨架，输入框内嵌标题位）：返回圆钮 + 胶囊输入框 + 筛选漏斗 ----------
            // （statusBarsPadding 已挂在最外层 Column 上，本行不再重复加）
            // 整行按 adaptiveContentWidth 限宽居中为一个整体：返回键/输入框/筛选图标的
            // 相对位置只随窗口档位变化，聚焦前后逐像素一致——本行没有任何以焦点/输入/
            // 结果为条件的分支，位移在结构上不可能发生；平板竖屏 640dp、超宽横屏 480dp
            // 居中，手机竖屏行为与原全宽一致
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier
                    // 限宽在前、fillMaxWidth 在后（顺序反了限宽成死代码，同 P1-6 教训）
                    .adaptiveContentWidth()
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = TgFeedTokens.GapLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 返回圆钮：kit AppIconButton（44dp OverlayBg 圆底，与全部二级页统一语言）
                AppIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                // 无边框胶囊输入框：SurfaceHigh 底 + RadiusPill，进页自动获焦弹键盘。
                // weight(1f) 吃满限宽行内剩余宽：返回键与筛选图标钉在列两端，输入框居中段
                TextField(
                    value = query,
                    onValueChange = { query = it }, // 只更新状态，搜索交给防抖 effect / 回车
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text("搜索视频/图文/音频", color = TgFeedTokens.TextLow)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TgFeedTokens.TextHigh),
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = TgFeedTokens.TextHigh,
                        unfocusedTextColor = TgFeedTokens.TextHigh,
                        cursorColor = TgFeedTokens.TextHigh,
                        focusedContainerColor = TgFeedTokens.SurfaceHigh,
                        unfocusedContainerColor = TgFeedTokens.SurfaceHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch(query) }),
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                // 筛选漏斗：钉在顶栏右端（原 tab 行右端的 30dp 小圆钮升格进顶栏）。
                // 非默认筛选（任一维度生效）时染主强调红，提示"有筛选条件生效中"——
                // 色彩纪律：蓝只留给链接/话题/命中词，状态提示走 AccentRed
                AppIconButton(
                    icon = Icons.Outlined.Tune,
                    contentDescription = "筛选搜索结果",
                    onClick = { showFilterSheet = true },
                    tint = if (searchFilter != FeedFilter()) {
                        TgFeedTokens.AccentRed
                    } else {
                        TgFeedTokens.TextHigh.copy(alpha = 0.90f)
                    },
                )
            }
            }

            // 媒体类型过滤 tab（治"搜索结果混进图片/音频"）：默认全部，可只搜视频/图文/音频；
            // 本地与服务端结果同口径生效（混排处统一过滤）。
            // 语言统一为 Feed 页同款下划线式：选中 AccentRed 短红条 + TextHigh 加粗，未选 TextMedium。
            // 与顶栏同一限宽居中列（adaptiveContentWidth 统一口径）：平板上 chrome 两轴对齐，
            // 结果网格仍全宽铺开不受影响
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier
                    .adaptiveContentWidth()
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // SE-G 修复保留：4 个 tab 放进可横滑的子行并吃满剩余宽度——窄屏（或"模糊·开"
                // 变宽）时 tab 区内部滚动让位，右端"模糊"开关始终完整可见，不再被右缘裁掉。
                //（原来 6 个子件直接挤在一个不可滚动的 Row 里，总宽超屏后末尾两件被推出右边界）
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(22.dp), // 与 Feed 顶栏 tab 同距
                ) {
                    listOf(-1 to "全部", 0 to "视频", 1 to "图文", 2 to "音频").forEach { (t, label) ->
                        val selected = mediaTab == t
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { vm.setSearchMediaTab(t) }
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label,
                                color = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                            // 选中指示：抖音式短红条（照 FeedTopBarOverlay 规格：18×2.5dp，上距 3dp）
                            Box(
                                Modifier
                                    .padding(top = 3.dp)
                                    .size(width = 18.dp, height = 2.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (selected) TgFeedTokens.AccentRed else Color.Transparent),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(TgFeedTokens.GapSm))
                // 模糊搜索开关（AppPrefs.fuzzySearch，默认关=精确：fold 归一 + 空格分段 AND；
                // 开=bigram 词位/错别字 ED≤1/同义词扩展。简繁归一常开，不算模糊）：
                // kit AppPill 钉在右端（选中=AccentRed 底白字，色彩纪律不再用蓝）。
                // 切换即写 prefs，已有搜索词破同词去重闸强制重搜——本地层现读 prefs、
                // 服务端建新会话读 prefs，搜索结果缓存键带口径位（VM 侧 searchCacheKey），
                // 不会命中旧口径缓存，口径即换即生效
                AppPill(
                    text = if (fuzzySearch) "模糊·开" else "模糊",
                    selected = fuzzySearch,
                    onClick = {
                        fuzzySearch = !fuzzySearch
                        prefs.fuzzySearch = fuzzySearch
                        if (query.trim().isNotEmpty()) {
                            lastSearched = "" // 破 doSearch 的同词去重闸，强制按新口径重搜
                            doSearch(query)
                        }
                    },
                )
            }
            }

            // 双模式解释文案（# 开头时）：话题精确 + 全文宽泛已合并（SEARCH-ARCH §3.4 解释义务的 M1 简版）
            val trimmedQuery = query.trim()
            if (trimmedQuery.startsWith("#")) {
                Text(
                    "话题 $trimmedQuery（精确）+ 全文 ${trimmedQuery.removePrefix("#")}（宽泛），已合并去重",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = TgFeedTokens.GapLg,
                        vertical = TgFeedTokens.GapXs,
                    ),
                )
            }

            // ---------- 结果区 ----------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    // 空词 → 建议面板（搜索历史 + 猜你想搜，点击直接搜）；清空搜索框即回到这里
                    // 展示口径走 visibleHistory（已滤除 × 删除的词），删除即时生效
                    query.isBlank() -> SuggestionPanel(
                        searchHistory = visibleHistory,
                        tasteWords = tasteWords,
                        onPick = submitSearch,
                        onRemoveHistory = removeHistoryTerm,
                    )
                    // 等待首结果：防抖窗口内（query ≠ lastSearched）或本地/服务端都还没回 →
                    // 加载态（kit AppLoading，spinner 规格与全 App 统一）。守住这条就不闪
                    // "没有找到"空态（空态只在搜索结束后判定）。
                    // 加载态上方顺带挂"打字即建议"：历史/口味词里含当前输入的词，
                    // 点击直接搜——等结果时不空等（市面搜索的下拉建议的轻量版）
                    mergedResults.isEmpty() && (searching || trimmedQuery != lastSearched) -> {
                        // 打字即建议：已删历史词不再参与联想（visibleHistory 口径）
                        val inlineSuggestions = remember(query, visibleHistory, tasteWords) {
                            (visibleHistory + tasteWords)
                                .filter { it != trimmedQuery && it.contains(trimmedQuery, ignoreCase = true) }
                                .distinct()
                                .take(8)
                        }
                        Column(Modifier.fillMaxSize()) {
                            // 搜索联动·"群友刚答的"（实时本轮问答）：置顶于历史区之上——实时优先于历史
                            currentLiveAsk?.let {
                                LiveAskSection(state = it, gateway = gateway, vm = vm, onPlay = onPlay, onOpenChannel = onOpenChannel)
                            }
                            // 金矿 P3：答案库命中即秒出（eager 解析后内容卡直出，先于频道腿/机器人）
                            if (currentAnswerRows.isNotEmpty()) {
                                GroupAnswerSection(
                                    rows = currentAnswerRows, gateway = gateway, vm = vm,
                                    highlight = lastSearched, onPlay = onPlay, onOpenChannel = onOpenChannel,
                                )
                            }
                            if (inlineSuggestions.isNotEmpty()) {
                                LazyRow(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                                ) {
                                    items(inlineSuggestions, key = { it }) { term ->
                                        SuggestionChip(
                                            text = term,
                                            icon = { Icon(Icons.Outlined.Search, null, tint = TgFeedTokens.TextLow, modifier = Modifier.size(12.dp)) },
                                            onClick = { submitSearch(term) },
                                        )
                                    }
                                }
                            }
                            // 本地腿未回：纯"正在搜索…"；本地腿已回且无命中、频道腿还在搜：
                            // 如实告知进度（AppLoading 文案档即 TextMedium）而非裸转圈死等——
                            // "本地/索引没有"是秒级结论，频道腿只是后台补全（30 秒硬顶自动收尾）
                            AppLoading(
                                text = if (localDoneFor == trimmedQuery && searching) {
                                    // R324：离线时频道腿如实标注（本地秒级结论照常先行上屏——
                                    // 深搜面离线=可用档，频道腿只是暂不可用的补全腿）
                                    val offlineSuffix =
                                        dev.tgfeed.core.net.OfflineDegradePolicy.searchOfflineSuffix(networkOnline)
                                    if (coverage.second > 0) {
                                        "本地未命中，正在搜索频道…（${coverage.first}/${coverage.second}）$offlineSuffix"
                                    } else {
                                        "本地未命中，正在搜索频道…$offlineSuffix"
                                    }
                                } else {
                                    "正在搜索…"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // 两路全空且本轮搜索已结束 → kit AppEmptyState 统一空态；
                    // 主按钮保留"本地深度搜索"出口（搜已索引的全量内容，命中后回 Feed 展示）
                    mergedResults.isEmpty() && lastSearched.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                        // 搜索联动·"群友刚答的"（实时本轮问答）：本地/频道虽空，刚问的 bot 应答可能秒到
                        currentLiveAsk?.let {
                            LiveAskSection(state = it, gateway = gateway, vm = vm, onPlay = onPlay, onOpenChannel = onOpenChannel)
                        }
                        // 金矿 P3：答案库命中——本地/频道腿虽空，群友历史答案内容卡直出（eager 已解析）；
                        // 附"在资源群里搜"入口：答案链接批量解析成可播条目回 Feed（一次性源，同机器人结果模式）
                        if (currentAnswers.isNotEmpty()) {
                            GroupAnswerSection(
                                rows = currentAnswerRows, gateway = gateway, vm = vm,
                                highlight = lastSearched, onPlay = onPlay, onOpenChannel = onOpenChannel,
                            )
                            var groupSearching by remember { mutableStateOf(false) }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = TgFeedTokens.GapMd),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (groupSearching) {
                                        "正在解析资源群答案…"
                                    } else {
                                        "在资源群里搜（${currentAnswers.size} 条答案）"
                                    },
                                    color = if (groupSearching) TgFeedTokens.TextLow else TgFeedTokens.TgBlue,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                        .clickable(enabled = !groupSearching) {
                                            groupSearching = true
                                            scope.launch {
                                                val groupItems = (gateway as? dev.tgfeed.core.td.TdLibGateway)
                                                    ?.searchViaGroup(lastSearched)
                                                    .orEmpty()
                                                groupSearching = false
                                                if (groupItems.isNotEmpty()) {
                                                    // 结果包成一次性源回 Feed 展示（与机器人结果同一模式）
                                                    vm.switchSource(object : dev.tgfeed.core.feed.FeedSource {
                                                        override val request =
                                                            dev.tgfeed.core.td.FeedRequest(searchQuery = lastSearched)
                                                        private var served = false
                                                        override suspend fun nextPage(limit: Int) =
                                                            if (served) dev.tgfeed.core.td.FeedPage(emptyList(), null)
                                                        else {
                                                            served = true
                                                            dev.tgfeed.core.td.FeedPage(groupItems, null)
                                                        }
                                                        override fun reset() { served = false }
                                                    })
                                                    // R182：兜底跳转统一收口（回搜索页
                                                    // pending + 剥落垫底页 + 落 Feed 页位）
                                                    jumpToFeedFromFallback()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        "资源群答案解析为空（预算已尽或链接已失效）",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
                                )
                            }
                        }
                        AppEmptyState(
                            icon = Icons.Outlined.SearchOff,
                            // 答案区块在上方时口径改为"本地/频道未命中"，不与答案区块打架
                            title = if (currentAnswers.isEmpty()) "没有找到相关内容" else "本地与已加入频道未命中",
                            subtitle = "换个关键词，或去设置确认索引已建立",
                            actionText = "本地深度搜索",
                            onAction = {
                                vm.deepSearch(lastSearched)
                                // R182：兜底跳转统一收口（回搜索页 pending + 剥落垫底页 + 落 Feed 页位）
                                jumpToFeedFromFallback()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // 金矿 P4-B·需求订阅入口："搜不到？订阅它，出现了喊你"——
                        // 订阅后收割管道到货即发系统通知（管理入口在设置页"需求订阅"卡）
                        DemandSubscribeRow(term = lastSearched)
                        // 次级兜底文字钮（页面级，不占 kit 槽；TextMedium 文字 + 点击热区）：
                        // "用机器人搜全网"——"本地没有就搜全网"的核心兜底心智，走 searchViaBot 原路径
                        // （10min 缓存/备用机器人轮换/翻页收割都在网关层）；搜索中切加载文案并禁用防连点
                        var botSearching by remember { mutableStateOf(false) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = TgFeedTokens.GapXl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (botSearching) "机器人搜索中…（可能要翻页，耐心等）" else "用机器人搜全网",
                                color = if (botSearching) TgFeedTokens.TextLow else TgFeedTokens.TextMedium,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                    .clickable(enabled = !botSearching) {
                                        botSearching = true
                                        scope.launch {
                                            val botResults = (gateway as? dev.tgfeed.core.td.TdLibGateway)
                                                ?.searchViaBot(prefs.searchBotUsername, lastSearched)
                                                .orEmpty()
                                            botSearching = false
                                            if (botResults.isNotEmpty()) {
                                                // 结果包成一次性源回 Feed 展示（原路径原语义）
                                                vm.switchSource(object : dev.tgfeed.core.feed.FeedSource {
                                                    override val request =
                                                        dev.tgfeed.core.td.FeedRequest(searchQuery = lastSearched)
                                                    private var served = false
                                                    override suspend fun nextPage(limit: Int) =
                                                        if (served) dev.tgfeed.core.td.FeedPage(emptyList(), null)
                                                        else {
                                                            served = true
                                                            dev.tgfeed.core.td.FeedPage(botResults, null)
                                                        }
                                                    override fun reset() { served = false }
                                                })
                                                // R182：兜底跳转统一收口（回搜索页
                                                // pending + 剥落垫底页 + 落 Feed 页位）
                                                jumpToFeedFromFallback()
                                            } else {
                                                Toast.makeText(context, "机器人也没搜到", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    // 文字钮热区补足 TouchMin：横 GapLg + 纵 GapMd（≈44dp 高）
                                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
                            )
                        }
                    }
                    // 结果网格：本地在前 + 服务端边到边并入；上方小字图例标明两路构成
                    else -> {
                        val gridState = rememberLazyGridState()
                        // 滚动接近底部（余 6 格）自动续搜：未到顶频道翻页 + 尾部频道惰性扇出
                        val loadMoreTrigger = remember {
                            derivedStateOf {
                                val info = gridState.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull()?.index
                                    ?: return@derivedStateOf false
                                last >= info.totalItemsCount - 6
                            }
                        }
                        LaunchedEffect(loadMoreTrigger.value, lastSearched) {
                            if (loadMoreTrigger.value) vm.fullSearchMore()
                        }
                        Column(Modifier.fillMaxSize()) {
                            // 搜索联动·"群友刚答的"（实时本轮问答）：置顶于历史区之上——实时优先于历史
                            currentLiveAsk?.let {
                                LiveAskSection(state = it, gateway = gateway, vm = vm, onPlay = onPlay, onOpenChannel = onOpenChannel)
                            }
                            // 金矿 P3·"群友搜到过"区块：命中即顶部秒出（eager 解析成内容卡，与两路结果并存）
                            if (currentAnswerRows.isNotEmpty()) {
                                GroupAnswerSection(
                                    rows = currentAnswerRows, gateway = gateway, vm = vm,
                                    highlight = lastSearched, onPlay = onPlay, onOpenChannel = onOpenChannel,
                                )
                            }
                            // R148 可达性修复：需求订阅入口在有结果态同样露出（重用户搜索
                            // 几乎从不落空，旧版空态专属入口对这批用户等于不存在）——
                            // 摆在结果网格之上、图例之前，一行文字钮不抢结果区空间；
                            // 文案切换"到货提醒"语境（见 DemandSubscribeRow 注释）
                            DemandSubscribeRow(term = lastSearched, resultsFound = true)
                            // R371 多维度排序行（用户 10:4x「业界该有的能力咱们都得具备」）：
                            // 结果网格上方一行 kit AppPill（与「模糊」chip 同族视觉，选中=
                            // AccentRed 底白字）；四档=相关度（默认=现状混排负锚）/最新
                            //（发布时间）/频道热度（本地图谱成员数，未知沉底）/时长；纯客户端
                            // 排序零新网络请求；档位 AppPrefs.searchSortOrder 跨会话记忆
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                            ) {
                                SearchSortOrder.values().forEach { o ->
                                    AppPill(
                                        text = o.label,
                                        selected = sortOrder == o,
                                        onClick = {
                                            if (sortOrder != o) {
                                                sortOrder = o
                                                prefs.searchSortOrder = o.name
                                            }
                                        },
                                    )
                                }
                            }
                            // R397 批量入分屏（六入口之一·搜索结果页）：当前所见=种子集——
                            // mergedResults 已是混排去重+排序+类型筛选后的上屏口径，所见即所种。
                            // 文字钮与 DemandSubscribeRow/排序 chips 同族语言（结果区头部不抢网格空间）
                            if (mergedResults.isNotEmpty()) {
                                TextButton(
                                    onClick = { onBatchToGrid(mergedResults.map { it.first }) },
                                ) {
                                    Icon(
                                        Icons.Outlined.GridView,
                                        contentDescription = null,
                                        tint = TgFeedTokens.AccentRed,
                                    )
                                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                                    Text(
                                        "分屏播放 ${mergedResults.size} 条",
                                        color = TgFeedTokens.AccentRed,
                                    )
                                }
                            }
                            // 两路构成图例："本地 N · 频道 M"（本地=收藏/稍后/历史/索引即时命中）
                            val localCount = mergedResults.count { it.second }
                            Text(
                                "本地 $localCount · 频道 ${mergedResults.size - localCount}",
                                color = TgFeedTokens.TextLow,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(
                                    horizontal = TgFeedTokens.GapLg,
                                    vertical = TgFeedTokens.GapXs,
                                ),
                            )
                            // R324 离线降级图例注行：离线时频道腿结果恒空/迟到超时——
                            // 如实标注「仅显示本地结果」（在线 null 不显示，零视觉噪声）
                            dev.tgfeed.core.net.OfflineDegradePolicy.searchLegendOfflineNote(networkOnline)?.let { note ->
                                Text(
                                    note,
                                    color = TgFeedTokens.TextLow,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = TgFeedTokens.GapLg,
                                        vertical = TgFeedTokens.GapXs,
                                    ),
                                )
                            }
                            // 倒排表建表进行中：如实告知"结果可能不全"，建完自动消失（进度流结束置 null）
                            indexBuild?.let { (done, total) ->
                                Text(
                                    "正在构建搜索索引 $done/$total，结果可能不全",
                                    color = TgFeedTokens.TextLow,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = TgFeedTokens.GapLg,
                                    ),
                                )
                            }
                            LazyVerticalGrid(
                                // R171 分辨率适配：列数随实测宽涌现（Adaptive），替写死
                                // Fixed(3)——与频道主页封面墙同一口径（MediaWallCellMin，
                                // 政策表见 Responsive.mediaWallColumns）；手机竖屏仍 3 列
                                columns = GridCells.Adaptive(MediaWallCellMin),
                                state = gridState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = TgFeedTokens.GapMd),
                                // B6 修复：右下浮动进度胶囊（"已搜 x/y 频道·N 条结果 停止"，约 26dp 高 +
                                // 上下 12dp 外边距）可见时给网格底部留出 56dp 让位区，最后一行卡片文字可
                                // 整体滚出胶囊覆盖范围；胶囊只在频道腿进行中显示（完成/超时/停止即消失），
                                // 不显示时无底部留白，不浪费一屏高度
                                contentPadding = PaddingValues(
                                    bottom = if (searching && coverage.second > 0) 56.dp else 0.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(mergedResults, key = { it.first.key }) { (item, isLocal) ->
                                    // 本地命中带"本地"徽标；服务端结果的身份标识即格子里的频道名
                                    SearchResultCell(
                                        item = item,
                                        gateway = gateway,
                                        highlight = lastSearched,
                                        badge = if (isLocal) "本地" else null,
                                        // R299 B4：低信息 caption 条目的 OCR 命中标注
                                        //（「来自图中文字」——无 caption 图片第一次能对
                                        // 用户建立内容预期；item_ocr 未放量时恒 false）
                                        ocrHit = item.key in ocrHitKeys,
                                        vm = vm,
                                    ) {
                                        // 用户操作打点：点了哪个格子（标题+key 后8位）
                                        android.util.Log.i("TGFlow", "点击搜索格子: 「${item.caption.take(20)}」 key=${item.key.takeLast(12)}")
                                        // 把混排后的完整结果（本地+服务端）传给播放——
                                        // 之前只传服务端池，本地条目根本没进源（货不对板根因）
                                        onPlay(item, mergedResults.map { it.first })
                                    }
                                }
                            }
                        }
                        // 覆盖度压到角落：网格右下浮动小 pill（半透明，不挡结果），
                        // 只在频道腿进行中显示——"已搜 x/y 频道 · N 条结果" + 迷你转圈 + "停止"；
                        // 本地+频道腿结束/超时（30 秒硬顶）/手动停止后自动消失，不再定格残留
                        // （旧版完成态常驻角落，给人"搜索永远没完"的错觉）
                        if (searching && coverage.second > 0) {
                            val done = coverage.first
                            val total = coverage.second
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .avoidBottomBar() // R226 统一避让钥匙（二级页 inset 恒 0，类覆盖防回归）
                                    .padding(TgFeedTokens.GapMd)
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    // 后台波段（30s 前台硬顶后低强度续搜）：低亮不挡视线
                                    .background(
                                        TgFeedTokens.SurfaceHigh.copy(alpha = if (bgSearching) 0.55f else 0.88f),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                if (!bgSearching) {
                                    // 迷你转圈：颜色/轨道色与页面级 spinner 统一（退化态渲染保底），
                                    // 尺寸保持 10dp 内联规格——40dp 会撑爆这颗 26dp 高的胶囊
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        color = TgFeedTokens.TextHigh,
                                        strokeWidth = 1.5.dp,
                                        trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    if (bgSearching) {
                                        "后台继续搜索中 $done/$total · ${mergedResults.size} 条"
                                    } else {
                                        "已搜 $done/$total 频道 · ${mergedResults.size} 条结果"
                                    },
                                    color = if (bgSearching) TgFeedTokens.TextLow else TgFeedTokens.TextMedium,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "停止",
                                    color = TgFeedTokens.TgBlue,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                        .clickable { vm.cancelFullSearch() }
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 搜索结果筛选面板：复用 Feed 的 FilterSheet（方向/时长/发布时间），
        // 应用后经 vm.setSearchFilter 即时重排服务端结果；本地结果在混排处即时过滤
        if (showFilterSheet) {
            FilterSheet(
                current = searchFilter,
                onApply = { vm.setSearchFilter(it) },
                onDismiss = { showFilterSheet = false },
            )
        }
    }
}

/**
 * 空词建议面板（搜索 UI 重做·A2 口径）：搜索历史 + 猜你想搜两小节。
 *
 * 视觉/布局规格：
 * - 面板整体按 adaptiveContentWidth 限宽水平居中，与顶栏输入框同轴（不限态：
 *   竖屏/横屏/平板同一口径，宽度不随聚焦或输入变化）；
 * - 小节标题用 kit AppSectionTitle（霓虹红引导竖条 + 粗体白字，发现页 v3 同款）；
 * - 历史词 = 描边胶囊 chips（SurfaceHigh 深底 + 0.5dp 发丝描边，与发现页热词 chip
 *   同族），FlowRow 流式折行排布，每颗右侧带 × 删除（走 [onRemoveHistory]）；
 *   历史为空整节隐藏，不留"搜索历史"光杆标题；
 * - 猜你想搜 = 同族描边胶囊（Search 前导图标，无 ×），与历史区以小节标题明确分隔；
 * - 点击任意 chip 直接搜（走显式提交路径：写历史 + 本地即时层 + 服务端扇出）；
 * - 面板可竖滑（历史满上限 + 口味词折行后小屏可能超出一屏）。
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow 仍是 ExperimentalLayoutApi（foundation 1.7）
@Composable
private fun SuggestionPanel(
    searchHistory: List<String>,
    tasteWords: List<String>,
    onPick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            // 限宽在前、fillMaxWidth 在后（顺序反了限宽成死代码，同 P1-6 教训）
            .adaptiveContentWidth()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TgFeedTokens.GapLg),
    ) {
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Text(
            "输入关键词，深入每一个已加入的频道搜索",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (searchHistory.isNotEmpty()) {
            Spacer(Modifier.height(TgFeedTokens.GapXl))
            AppSectionTitle("搜索历史")
            Spacer(Modifier.height(TgFeedTokens.GapMd))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                searchHistory.forEach { term ->
                    HistoryTermChip(
                        text = term,
                        onClick = { onPick(term) },
                        onRemove = { onRemoveHistory(term) },
                    )
                }
            }
        }
        if (tasteWords.isNotEmpty()) {
            Spacer(Modifier.height(TgFeedTokens.GapXl))
            AppSectionTitle("猜你想搜")
            Spacer(Modifier.height(TgFeedTokens.GapMd))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                tasteWords.forEach { term ->
                    SuggestionChip(
                        text = term,
                        icon = { Icon(Icons.Outlined.Search, null, tint = TgFeedTokens.TextLow, modifier = Modifier.size(12.dp)) },
                        onClick = { onPick(term) },
                    )
                }
            }
        }
        // 底部留白：末排 chip 不贴屏底
        Spacer(Modifier.height(TgFeedTokens.GapXl))
    }
    }
}

/**
 * 金矿 P4-B·需求订阅入口："搜不到？订阅「X」，出现了喊你"。
 * 订阅词落 AppPrefs（DemandSubscription 统一管理），收割管道每轮入库的新链接
 * 命中订阅词即并入订阅更新汇总通知（R148 起与追更合并）、点通知深链直达内容。
 * 已订阅时转灰字"已订阅，点击取消"。
 * 色彩纪律：未订阅是动作入口走 TgBlue（链接/动作色），已订阅是状态展示走 TextLow。
 *
 * R148 可达性修复：入口原来只挂在搜索**空态**——重用户（关注大量频道，本地+频道腿
 * 几乎总有结果，实测 596 频道账号连生僻词都能命中 1 条）永远到不了空态，订阅入口
 * 形同虚设（用户口径："我明明关注那么多频道，可需求订阅的到货通知显示无订阅"——
 * 根因是根本没有可达的加词入口，不是订阅表读丢）。现在空态/有结果两态都挂本行：
 * [resultsFound] 仅切换文案（空态"搜不到"语境 vs 有结果"到货提醒"语境），行为同一条。
 */
@Composable
private fun DemandSubscribeRow(term: String, resultsFound: Boolean = false) {
    if (term.isBlank()) return
    val context = LocalContext.current
    // remember(term)：换词重查订阅态；点击后本地态即时翻转，不等下轮重组
    var subscribed by remember(term) { mutableStateOf(DemandSubscription.contains(term)) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = TgFeedTokens.GapSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (subscribed) {
                "已订阅「$term」，到货会通知你（点击取消）"
            } else if (resultsFound) {
                "订阅「$term」，有更好的到货提醒你"
            } else {
                "搜不到？订阅「$term」，出现了喊你"
            },
            color = if (subscribed) TgFeedTokens.TextLow else TgFeedTokens.TgBlue,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                .clickable {
                    if (subscribed) {
                        DemandSubscription.remove(term)
                        subscribed = false
                        Toast.makeText(context, "已取消订阅「$term」", Toast.LENGTH_SHORT).show()
                    } else if (DemandSubscription.add(term)) {
                        subscribed = true
                        Toast.makeText(context, "已订阅「$term」，资源到货时通知你", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "订阅失败：词非法或订阅数已满，可去设置页管理", Toast.LENGTH_SHORT).show()
                    }
                }
                // 文字钮热区补足 TouchMin：横 GapLg + 纵 GapMd（≈44dp 高，与下方机器人钮同口径）
                .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
        )
    }
}

/**
 * 答案区频道行成员数小字格式化（R48）：≥1万 一位小数"万"（如 3.2万，整数万不带小数点），
 * <1万 原样数字（如 8620）。0=未知由调用方整体不渲染（不留空位）。
 * （R197 收敛：核心折算在 components/Formatters.formatMemberCount 一处定义，此处薄委托）
 */
private fun formatMemberCount(members: Int): String = dev.tgfeed.ui.components.formatMemberCount(members)

/**
 * 金矿 P3·"群友搜到过"区块：答案库（群友在资源搜索群的历史搜索沉淀）命中词的内容直出区。
 * eager 解析口径（用户拍板"直接展示成内容，不跳转"）：VM 侧已把前 8 条答案链接批量解析成
 * 可播条目（一词每进程一轮、查询车道整批 1 次记账、失败进死信），本区纯展示零网络：
 *  - 已解析（item != null，VM 已排前面）：内容卡横滑区——复用搜索结果网格同款 SearchResultCell
 *    （封面走 CoverStore/loadCoverBytes/loadCoverBytesByMessage 真图通道），
 *    点击原地播放（onPlay 现有链，与搜索结果格同一播放口径，不跳走）；
 *  - 解析不出/没轮到的：频道行（图谱零网络标题；没标题直报 @用户名），chatId 已知即可点
 *    打开该频道 Feed（onOpenChannel，与发现页"试吃"同链），行尾箭头 affordance。
 * 色彩纪律：卡面/标题行走 TextHigh/TextMedium，计数走 TextLow（蓝色只留链接/话题/命中词，
 * 本区已无链接行，不占蓝）。
 */
@Composable
private fun GroupAnswerSection(
    rows: List<GroupAnswerRow>,
    gateway: TdGateway,
    vm: FeedViewModel,
    highlight: String,
    onPlay: (VideoItem, List<VideoItem>) -> Unit,
    // R48 带词进频道：第二参数传本区搜索词（highlight = 当前已提交词）
    onOpenChannel: (Long, String) -> Unit = { _, _ -> },
) {
    if (rows.isEmpty()) return // 行未构建完成（eager 解析异步）时不闪现空壳标题
    val scope = rememberCoroutineScope() // 点击即解析（resolveAnswerChatId）的挂起承载
    val playable = rows.filter { it.item != null } // VM 已排序（可播前排），这里只分组不打乱
    val pending = rows.filter { it.item == null }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
    ) {
        // 小节标题统一 A2 口径：kit AppSectionTitle（红引导竖条 + 粗体白字）
        AppSectionTitle("群友搜到过（${rows.size}）")
        Spacer(Modifier.height(TgFeedTokens.GapXs))
        // 已解析成可播条目：内容卡横滑区（卡片与搜索结果网格同一件，点击原地播放）
        if (playable.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                items(playable, key = { it.answer.link }) { row ->
                    val item = row.item!!
                    // LazyRow 子项宽度无界：外层钉 150dp，网格卡内部的 fillMaxWidth 才有界
                    Box(Modifier.width(150.dp)) {
                        SearchResultCell(item = item, gateway = gateway, highlight = highlight, vm = vm) {
                            onPlay(item, playable.mapNotNull { it.item })
                        }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapXs))
        }
        // 解析不出/没轮到的：频道行（最多 6 行）——chatId 已知（直连答案/标题解析回填）
        // 即可点打开该频道 Feed（与发现页"试吃"同链，未加入公开频道也可读），行尾挂箭头
        // affordance 告知可点；chatId 未就位时不可点（点击无动作，等解析回填后自动升级可点）
        pending.take(6).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                    // chatId 已就位直接开；未就位的用户名链接"点击即解析"
                    // （批量链漏掉的，用户点的那一下现场 SearchPublicChat 换 chatId 再开）；
                    // R48：两种路径都带着本区搜索词进频道（落地即出匹配资源）
                    .clickable(enabled = row.openChatId != 0L || row.answer.username.isNotBlank()) {
                        if (row.openChatId != 0L) onOpenChannel(row.openChatId, highlight)
                        else scope.launch {
                            val id = vm.resolveAnswerChatId(row.answer.link, row.answer.username)
                            if (id != null) onOpenChannel(id, highlight)
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        row.title.isNotBlank() -> row.title
                        // 标题未沉淀时直报用户名，不再挂"识别中…"——它暗示"仍在解析"，
                        // 但频道主页类链接永远出不了内容卡，用户名本身就是确定身份
                        // （真标题由 VM 侧 resolveAnswerTitles 懒解析到位后自动替换）
                        row.answer.username.isNotBlank() -> "@${row.answer.username}"
                        row.answer.chatId != 0L -> "频道"
                        else -> row.answer.link
                    },
                    color = if (row.title.isNotBlank()) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // R48 成员数小字（"3.2万"形态）；未知（0）不渲染——不留空位
                if (row.answer.members > 0) {
                    Text(
                        formatMemberCount(row.answer.members),
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                }
                if (row.answer.hits > 1) {
                    Text(
                        "×${row.answer.hits}",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                // 可点频道行的箭头 affordance（告知"点进去能刷这个频道"；
                // 用户名行可"点击即解析"同算可点）
                if (row.openChatId != 0L || row.answer.username.isNotBlank()) {
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "打开频道",
                        tint = TgFeedTokens.TextLow,
                        modifier = Modifier.size(TgFeedTokens.IconSm),
                    )
                }
            }
        }
    }
}

/**
 * 搜索联动·"群友刚答的"区块：显式提交后并行问已发现搜索实体（KIND_BOT 优先）的实时应答区。
 * eager 解析口径（与"群友搜到过"同）：VM 侧把流入的链接答案按前 8 条批量解析成可播条目
 * （秒回车道整批记账，首批到达即解析 + 窗口关闭补一批），本区纯展示零网络：
 *  - 已解析：内容卡横滑区（SearchResultCell 网格同款卡 + 真图通道，封面角标 = 来源实体名），
 *    点击原地播放（onPlay 现有链，不跳走）；
 *  - 解析不出/没轮到的：频道行 + 来源实体小字（没标题直报 @用户名），chatId 已知即可点
 *    打开该频道 Feed（onOpenChannel，与发现页"试吃"同链），行尾箭头 affordance；
 *  - 纯文本回复照旧答案卡（正文 + "来自 XX"来源标）。
 * 等待态：已发词未回时标题行挂迷你转圈"等待应答…"；窗口已关仍无内容 → 整区不显示（静默收场）。
 * R366 写闸态：账号写保护测试闸开（state.writeBlocked）→ 标题行如实提示「写保护中：
 * 本轮未发词」，零转圈零发送（§十 缺口收口，R364 涉写事故正治）。
 */
@Composable
private fun LiveAskSection(
    state: LiveAskState,
    gateway: TdGateway,
    vm: FeedViewModel,
    onPlay: (VideoItem, List<VideoItem>) -> Unit,
    // R48 带词进频道：第二参数传本区搜索词（state.query = 本轮秒问词）
    onOpenChannel: (Long, String) -> Unit = { _, _ -> },
) {
    val hasContent = state.links.isNotEmpty() || state.texts.isNotEmpty()
    // R366：写闸拦截态要留区如实提示（无内容也非等待，唯独 writeBlocked 不隐藏）
    if (!hasContent && !state.asking && !state.writeBlocked) return // 无回复且窗口已关：整区不显示（静默收场）
    val scope = rememberCoroutineScope() // 点击即解析（resolveAnswerChatId）的挂起承载
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 小节标题统一 A2 口径（kit AppSectionTitle）；等待态转圈/计数文案照旧跟在标题后
            AppSectionTitle("群友刚答的")
            if (state.writeBlocked) {
                // R366 liveAsk 写闸（§十）：测试闸开=如实提示零发送，不落假「等待应答」长转
                Spacer(Modifier.width(TgFeedTokens.GapSm))
                Text(
                    "写保护中：本轮未发词（测试闸 agent_no_account_write 已开）",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (state.asking) {
                // 轻量等待态：迷你转圈（10dp 内联规格，与覆盖度胶囊同款）+ 已问实体数
                Spacer(Modifier.width(TgFeedTokens.GapSm))
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = TgFeedTokens.TextHigh,
                    strokeWidth = 1.5.dp,
                    trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                )
                Spacer(Modifier.width(TgFeedTokens.GapXs))
                Text(
                    "已问 ${state.entities} 个搜索实体，等待应答…",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(TgFeedTokens.GapXs))
        // 已解析成可播条目：内容卡横滑区（卡片与搜索结果网格同一件；封面角标 = 来源实体名）
        val playableRows = state.links.filter { it.item != null }
        if (playableRows.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                items(playableRows, key = { it.answer.link }) { la ->
                    val item = la.item!!
                    // LazyRow 子项宽度无界：外层钉 150dp，网格卡内部的 fillMaxWidth 才有界
                    Box(Modifier.width(150.dp)) {
                        SearchResultCell(
                            item = item,
                            gateway = gateway,
                            highlight = state.query,
                            badge = la.fromTitle, // 来源标：哪个 bot/群秒回的（封面左上角暗胶囊小字）
                            vm = vm,
                        ) {
                            onPlay(item, playableRows.mapNotNull { it.item })
                        }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapXs))
        }
        // 解析不出/没轮到的：频道行（最多 6 行）+ 来源实体小字——chatId 已知即可点
        // 打开该频道 Feed（与发现页"试吃"同链，未加入公开频道也可读），行尾箭头告知可点；
        // chatId 未就位时不可点（等标题解析回填 openChatId 后自动升级可点）
        state.links.filter { it.item == null }.take(6).forEach { la ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                    // chatId 已就位直接开；未就位的用户名链接"点击即解析"
                    // （批量链漏掉的，用户点的那一下现场 SearchPublicChat 换 chatId 再开）；
                    // R48：两种路径都带着本轮秒问词进频道（落地即出匹配资源）
                    .clickable(enabled = la.openChatId != 0L || la.answer.username.isNotBlank()) {
                        if (la.openChatId != 0L) onOpenChannel(la.openChatId, state.query)
                        else scope.launch {
                            val id = vm.resolveAnswerChatId(la.answer.link, la.answer.username)
                            if (id != null) onOpenChannel(id, state.query)
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        la.title.isNotBlank() -> la.title
                        // 与"群友搜到过"同口径：标题未沉淀直报用户名，不挂"识别中…"
                        // （真标题由 VM 侧 kickLiveTitleResolve 到位后自动替换）
                        la.answer.username.isNotBlank() -> "@${la.answer.username}"
                        la.answer.chatId != 0L -> "频道"
                        else -> la.answer.link
                    },
                    color = if (la.title.isNotBlank()) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // R48 成员数小字（"3.2万"形态）；未知（0）不渲染——不留空位
                if (la.answer.members > 0) {
                    Text(
                        formatMemberCount(la.answer.members),
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                }
                // 来源实体标（哪个 bot/群秒回的）
                Text(
                    la.fromTitle,
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                // 可点频道行的箭头 affordance（告知"点进去能刷这个频道"；
                // 用户名行可"点击即解析"同算可点）
                if (la.openChatId != 0L || la.answer.username.isNotBlank()) {
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "打开频道",
                        tint = TgFeedTokens.TextLow,
                        modifier = Modifier.size(TgFeedTokens.IconSm),
                    )
                }
            }
        }
        // 纯文本答案卡（最多 3 条，照旧）：SurfaceHigh 卡 + 正文 + 来源标
        state.texts.take(3).forEach { ta ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                    .background(TgFeedTokens.SurfaceHigh)
                    .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
            ) {
                Text(
                    ta.text,
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "来自 ${ta.fromTitle}",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 描边胶囊建议 chip（A2 口径，与发现页热词 chip 同族）：
 * SurfaceHigh 深底 + 0.5dp OverlayHairline 发丝描边 + RadiusPill 全圆角；
 * 前导小图标（12dp，TextLow）+ 词（TextHigh），点击整颗触发搜索。
 * 建议面板"猜你想搜"与输入中"打字即建议"共用本样式。
 */
@Composable
private fun SuggestionChip(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
    ) {
        icon()
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        Text(text, color = TgFeedTokens.TextHigh, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * 搜索历史 chip（A2 描边胶囊同族）：History 前导图标 + 词 + 右侧 × 删除。
 * × 是独立小热区（圆底 clip + clickable），点击只删该条历史、不触发整颗 chip 的搜索
 * （Compose 嵌套 clickable：内层消费事件，外层不会再收到）；
 * 点 chip 本体照旧直接搜（显式提交路径）。
 */
@Composable
private fun HistoryTermChip(
    text: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, shape)
            .clickable(onClick = onClick)
            // 左 GapMd 与建议 chip 同规格；右端留给 × 的热区内边距，纵 GapXs 定高
            .padding(start = TgFeedTokens.GapMd, end = TgFeedTokens.GapXs)
            .padding(vertical = TgFeedTokens.GapXs),
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        Text(text, color = TgFeedTokens.TextHigh, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        Icon(
            Icons.Filled.Close,
            contentDescription = "删除该条搜索历史",
            tint = TgFeedTokens.TextLow,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(TgFeedTokens.GapXs)
                .size(TgFeedTokens.IconSm),
        )
    }
}

/**
 * 单个搜索结果格（视频/图文/音频三类统一结构）：
 * 封面（无封面时类型图标占位，AppEmptyState 的 TextLow 线框图标语言）+ 右上角类型角标
 * + 右下角时长 chip + 标题 2 行（caption，命中词染蓝）+ 来源频道 1 行。
 * [highlight] 为当前已发起搜索的词：标题里命中片段染电报蓝，空串不高亮。
 * [badge] 非空时在封面左上角叠一个小徽标（本地即时命中标"本地"，小字 TextLow 暗胶囊，
 * 不用蓝块——蓝色只留给链接/话题/命中词）；服务端结果不传，其来源身份由格子里的频道名承担。
 */
@Composable
private fun SearchResultCell(
    item: VideoItem,
    gateway: TdGateway,
    highlight: String,
    badge: String? = null,
    // R299 B4：OCR 命中标注（低信息 caption ∧ 图中文字含查询词位）——左上角
    // 「本地」徽标下方叠第二枚暗胶囊；false/无 badge 时布局与旧版逐字节一致
    ocrHit: Boolean = false,
    vm: FeedViewModel? = null,
    onClick: () -> Unit,
) {
    // 封面解码（IO 线程）：抽帧封面 → minithumbnail 秒出 → 高清缩略图换上
    val cover by produceState<ImageBitmap?>(initialValue = null, item.key) {
        dev.tgfeed.ui.components.CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                item.coverBytes?.let { bytes ->
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }.getOrNull()
                }
            }
        }
        val lib = gateway as? dev.tgfeed.core.td.TdLibGateway
        if (lib != null && item.coverFileId >= 0) {
            val bytes = withContext(Dispatchers.IO) { runCatching { lib.loadCoverBytes(item) }.getOrNull() }
            if (bytes != null) {
                value = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }.getOrNull() ?: value
            }
        }
        // 深度索引条目没有 coverBytes（落盘 JSON 不存封面字节）、coverFileId 也可能是
        // 跨重启失效的会话级 id，上面两链落空时：按 chatId+messageId 反解消息真缩略图
        //（GetMessage→Video.thumbnail/Photo 尺寸档/Audio.albumCoverThumbnail→DownloadFile，
        // 网关侧内存+磁盘双缓存，同 key 不重复下载；W6 后图片条目的失效 coverFileId
        // 也会在网关侧自动落到本链），修"同频道搜索结果全复用同一张频道头像"的货不对板。
        // 解码放 IO 线程，防快速滚动掉帧
        if (value == null && lib != null && item.messageId > 0) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
            }
            if (bytes != null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }.getOrNull()
                } ?: value
            }
        }
        // 无封面最终兜底（本地条目老数据没有 coverFileId 且消息反解也失败）：用频道头像，不再是"视"灰占位
        if (value == null && lib != null) {
            val avatar = withContext(Dispatchers.IO) {
                runCatching { lib.getChannelAvatar(item.chatId) }.getOrNull()
            }
            if (avatar != null) {
                value = runCatching {
                    BitmapFactory.decodeByteArray(avatar, 0, avatar.size).asImageBitmap()
                }.getOrNull()
            }
        }
        // 还没有：视频条目主动拉开头抽帧（用户要求"封面可以从原始视频拿"）
        if (value == null && item.mediaType == 0 && vm != null) {
            vm.fetchCoverFrame(item)
        }
    }
    // 类型图标：0 视频 / 1 图文 / 2 音频——无封面占位与右上角类型角标共用同一 glyph
    val typeIcon = when (item.mediaType) {
        1 -> Icons.Outlined.Photo
        2 -> Icons.Outlined.MusicNote
        else -> Icons.Outlined.PlayArrow
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick),
    ) {
        // 封面区（三类同构）：封面图 / 类型图标占位 + 角标叠层
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp),
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // 无封面占位：透明底（融进页面深色底，不再是独立的灰块）+ 淡类型图标——
                // 用户反馈"统一灰底+小图标很 low"，改透明让占位区与页面同色、更干净
                Box(
                    Modifier.fillMaxSize().background(TgFeedTokens.InkBlack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        tint = TgFeedTokens.TextLow.copy(alpha = 0.5f),
                        modifier = Modifier.size(TgFeedTokens.IconXl),
                    )
                }
            }
            // 类型角标（右上角，OverlayBg 胶囊 + 白图标）：混排网格里一眼区分视频/图文/音频
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(TgFeedTokens.OverlayBg, RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .padding(3.dp),
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(TgFeedTokens.IconXs),
                )
            }
            // 来源徽标（左上角）：本地即时命中标"本地"——样式收敛为小字 TextLow 暗胶囊，
            // 不再用蓝块（蓝色只留给链接/话题/命中词高亮）
            // R299 B4：OCR 命中标「来自图中文字」叠其下（同暗胶囊语言）
            if (badge != null || ocrHit) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    if (badge != null) {
                        Text(
                            badge,
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(TgFeedTokens.OverlayBg, RoundedCornerShape(TgFeedTokens.RadiusPill))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (ocrHit) {
                        if (badge != null) Spacer(Modifier.height(3.dp))
                        Text(
                            "来自图中文字",
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(TgFeedTokens.OverlayBg, RoundedCornerShape(TgFeedTokens.RadiusPill))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            // 时长 chip（右下角，胶囊语言；图文 durationSec=0 天然不显示，不出"0:00"）
            if (item.durationSec > 0) {
                Text(
                    formatDuration(item.durationSec),
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(TgFeedTokens.OverlayBg, RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        // 文案区：标题（caption，最多 2 行，命中词高亮）在上，来源频道 1 行在下
        Column(Modifier.padding(horizontal = TgFeedTokens.GapSm, vertical = TgFeedTokens.GapXs)) {
            // 命中词高亮：remember 缓存 AnnotatedString，重组不重建正则；
            // 无 caption 时以频道名兜底标题位（此时下方来源行省略，避免同一行字重复两次）
            val titleText = remember(item.caption, item.channelTitle, highlight) {
                buildCaptionHighlight(item.caption.ifBlank { item.channelTitle }, highlight, TgFeedTokens.TgBlue)
            }
            Text(
                titleText,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 来源频道（caption 非空才单列；服务端结果的来源身份即此行）
            if (item.caption.isNotBlank()) {
                Text(
                    item.channelTitle,
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * caption 命中词高亮：把搜索词（去 #/＃ 前缀、按中英空格拆词去重）在
 * caption 里的所有命中片段染成电报蓝。大小写不敏感；空词/无命中退化为纯文本。
 * 模式照 BottomInfoPanel.buildCaptionWithLinks：buildAnnotatedString 顺序拼接 +
 * addStyle 片段染色（这里只染色，不挂链接点击）。
 */
private fun buildCaptionHighlight(caption: String, query: String, color: Color): AnnotatedString {
    val terms = query.split(' ', '　')
        .map { it.trim().trimStart('#', '＃') }
        .filter { it.isNotEmpty() }
        .distinct()
    if (terms.isEmpty() || caption.isEmpty()) return AnnotatedString(caption)
    val regex = terms.joinToString("|") { Regex.escape(it) }
        .toRegex(RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var cursor = 0
        for (m in regex.findAll(caption)) {
            // 防御交叠/零宽命中：区间无效直接跳过（Regex.escape 后理论不会出现）
            if (m.range.first < cursor || m.value.isEmpty()) continue
            append(caption.substring(cursor, m.range.first))
            val start = length
            append(m.value)
            addStyle(SpanStyle(color = color), start, length)
            cursor = m.range.last + 1
        }
        append(caption.substring(cursor))
    }
}

/**
 * B4：历史词显示/点击前的 URL 解码还原（%23asmr → #asmr）。
 * 不含 % 的词（绝大多数）原样返回，零开销；含 % 时按 UTF-8 解码，
 * 解码失败（非法转义如孤立 %）退回原串，绝不崩溃。
 */
private fun urlDecodeHistoryTerm(term: String): String =
    if ('%' !in term) {
        term
    } else {
        runCatching { java.net.URLDecoder.decode(term, Charsets.UTF_8.name()) }
            .getOrDefault(term)
    }
