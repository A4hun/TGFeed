package dev.tgfeed.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.components.kit.SheetStaggeredSection
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.launch

/**
 * 顶栏浮层：透明压在视频上（内容区零遮罩——顶部染色渐变已按用户反馈删除，
 * 顶栏文字/图标可读性走 withOverlayShadow 描边），内容让到状态栏之下。
 * 中间（R157 平级双 tab → **R239 三 tab**：关注|时间线|推荐，HomeTabsRow 共享件
 * ——时间线居中一等公民，视觉权重与关注/推荐一致）：单击切流/开时间线 overlay、
 * 双击已激活 = 刷新该流（抖音同款）。R157 前推荐挂下拉箭头+大面板，关注只是
 * 纯 tab，推荐"更一等"把关注比下去；R157 双 tab 平级对称，R239 时间线同规入座。
 * 潜进源（收藏夹/单频道等非关注非推荐）时，左区"内容源"图标右侧跟一枚暗色
 * "· 源名"面包屑（单击仍开源面板、双击刷新当前源），tab 带保持居中不被挤歪。
 * 左侧（R157 源面板入口独立控件化）：「内容源」图标（☰）常驻左缘，点击展开
 * 内容源 AppSheetMotion 动效壳（入口从"推荐∨"标题挪此）；
 * R141 盲盒流在播时图标右侧改放"摇一把"胶囊（面包屑让位，关键词见开场卡）。
 * 右侧（R173 底栏时代）：搜索 + 分屏（进 GridScreen）——顶栏人像「我的」已迁
 * 底栏右端真头像座位（§2.3 顶栏减负，用户域入口唯一化）。
 * 原漏斗"内容筛选"收进源面板「换着看」段 chip；原齿轮"设置"移入"我的"页，顶栏不再放。
 *
 * 搜索入口（搜索 UI 重做裁决）：顶栏只留一枚搜索图标，点击经 onSearch("") 直达全屏
 * 搜索页 SearchScreen（openSearch 通道），不再在顶栏内展开输入框——旧实现展开时
 * 标题/左占位撤出组合、输入框 weight(1f) 从左缘拉到图标前（平板上横贯整屏），
 * 历史 chips 再绝对定位浮在视频上，即用户投诉的"点搜索整个被挤到左侧+历史很丑"。
 * 跳页后顶栏布局恒定无位移，历史/建议由搜索页建议面板统一承载。
 *
 * 内容源面板（SourcePickerSheet，R173 §4.2 底栏时代瘦身版）：AppSheetMotion 左抽屉形态
 * （R173 用户裁决：☰ 触发点在顶栏左缘 → 面板就近左缘滑入，spring 0.88/420 +
 * scrim 收编 OverlayBg + 三段 40ms stagger + 左缘/头部横拖关窗），
 * 自上而下 = 头部（拖拽条+标题+关闭）→「我的流」段（收藏夹/稍后再看/订阅追更/手气不错）
 * →「换着看」段（关键词/频道合集/内容筛选）→「直达频道」段（筛选输入框 + 频道卡列表：
 * 真头像 + 鲜活信号行 + 相似文字钮 + 追/已追胶囊，排序按最近看过优先）。
 * R173 入口去冗余：推荐（顶栏双 tab 冗余）、发现（底栏）、片单（我的页）、已看历史
 * （我的页）四 chip 删除——源面板从此只管「让这股流变什么」。
 */
@Composable
internal fun FeedTopBarOverlay(
    title: String,
    channels: List<ChannelBrief>,
    subscribedIds: Set<Long>,
    onSelectAggregate: () -> Unit,
    onSelectChannel: (ChannelBrief) -> Unit,
    onSelectFavorites: () -> Unit,
    onSelectWatchLater: () -> Unit,
    onSelectSubscriptions: () -> Unit,
    onOpenCollections: () -> Unit,
    onToggleSubscription: (ChannelBrief) -> Unit,
    // "找相似"入口（铃铛左边）：点击推荐与该频道相似的频道；默认空实现，调用方不接线也可编译
    onFindSimilar: (ChannelBrief) -> Unit = {},
    onGridMode: () -> Unit,
    onRefreshFeed: () -> Unit,
    onSearch: (String) -> Unit,
    // 源面板开窗状态镜像：FeedScreen 据此藏快拍条（快拍条在顶栏之后声明会浮在面板
    // scrim 之上，破坏模态纪律）；默认空实现，调用方不接线也可编译
    onSheetOpenChange: (Boolean) -> Unit = {},
    // 兼容参数：顶栏搜索已改道全屏搜索页（点击图标即跳页），历史展示/记录由搜索页
    // 自行承担，本参数不再消费；保留签名仅为不动调用方（FeedScreen）
    searchHistory: List<String> = emptyList(),
    // 兼容参数：同上，不再消费（显式提交写历史已由搜索页 submitSearch 统一做）
    onRecordSearch: (String) -> Unit = {},
    // 内容筛选入口：原顶栏 Tune 按钮已迁入源面板尾部 chip（顶栏只留 源/搜索/分屏/我的）；
    // 默认空实现，调用方不接线也可编译
    onOpenFilter: () -> Unit = {},
    // 内容分区 tab：-1 全部 / 0 视频 / 1 图文 / 2 音频；默认 -1 + 空实现，调用方不接线也可编译
    mediaTypeTab: Int = -1,
    onSelectMediaType: (Int) -> Unit = {},
    // R181 轨道跟手：横滑拖拽进度（拖拽中 tab 带权重从当前 tab 渐变到目标 tab，
    // fraction ≥ 0.5 处颜色/加粗翻转——与 commit 距离同拍，底栏 targetPage 半程翻转
    // 同款范式）；null = 空闲。默认 null + 空实现，调用方不接线也可编译
    railDrag: dev.tgfeed.ui.RailDragVisual? = null,
    // 盲盒模式 / 关键词订阅源入口（选择器快捷胶囊）
    onOpenKeywordFeed: () -> Unit = {},
    // R141 手气不错合并入口（原"手气不错"开频道 + 原"盲盒流"抽一串 两个盲盒心智收敛为一个）：
    // 点击先收起源面板，再开 LuckyPickerSheet（AppSheet 双选项卡：开一个频道 / 抽一串内容）；
    // 默认空实现，调用方不接线也可编译
    onOpenLuckyPicker: () -> Unit = {},
    // R141 盲盒流在播标记：true 时顶栏标题前加骰子标、标题左侧加显式"摇一把"胶囊
    // （点 = 换词重抽，替代"双击标题刷新"这个无 UI 表达的隐藏手势）；
    // 默认 false，调用方不接线也可编译
    blindBoxActive: Boolean = false,
    onShakeBlindBox: () -> Unit = {},
    // R66 追更红点：任一订阅频道有"没看过的新内容"时，源面板"订阅追更"chip 图标右上角
    // 叠小红点角标（不显示数字）；默认 false，调用方不接线也可编译
    followHasNew: Boolean = false,
    // R157 平级双 tab（抖音"关注/推荐"对等物）：followingActive=true 时"关注"高亮、
    // aggregateActive=true 时"推荐"高亮；两者皆 false = 潜进了某个具体源
    // （tab 右侧出"· 源名"面包屑）。单击语义对称：不在播 = 切入该流、在播 = 刷新一批
    // （刷新分支由调用方在回调里判）；默认 false + 空实现，调用方不接线也可编译
    followingActive: Boolean = false,
    onSelectFollowing: () -> Unit = {},
    aggregateActive: Boolean = false,
    // R239 时间线 tab（一等公民居中）：timelineActive=true 时"时间线"高亮——
    // 该态下本顶栏实际被 PursueScreen 整层盖住（时间线自带同款 HomeTabsRow 页头），
    // 本参数只承载语义完备（调用方恒传 appContainer.homeTimelineOn）；点按 =
    // 开时间线 overlay（AppContainer.openPursue）；双击已激活 = 时间线重拉 nonce。
    // 默认 false + 空实现，调用方不接线也可编译
    timelineActive: Boolean = false,
    onSelectTimeline: () -> Unit = {},
    onRefreshTimeline: () -> Unit = {},
    // 频道真头像加载器（chatId → 图片字节，内部有进程内缓存）；
    // 默认 null = 未接线，频道卡保持字母圆头像，调用方不传也可编译
    avatarLoader: (suspend (Long) -> ByteArray?)? = null,
    // R68 频道卡元数据（鲜活信号行 + 最近看过优先排序；chatId → ChannelCardMeta）；
    // 默认空表 = 未接线，频道卡不显示信号行、按名字排序，调用方不传也可编译
    channelMeta: Map<Long, dev.tgfeed.core.feed.ChannelCardMeta> = emptyMap(),
    // R191 手动钉住深索引（频道行「深度索引」按钮）：行状态台账（钉住/在爬/进度/
    // 完成✓）+ 钉/取消钉回调；默认空表 + 空实现，调用方不接线也可编译
    manualIndexRows: Map<Long, dev.tgfeed.core.feed.ManualIndexRowState> = emptyMap(),
    onRequestDeepIndex: (ChannelBrief) -> Unit = {},
    onCancelDeepIndexPin: (ChannelBrief) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    // ☰ / 面包屑点开：本地态 + 镜像上抛（宿主侧 z 序避让等逻辑）
    val openSheet: () -> Unit = {
        sheetOpen = true
        onSheetOpenChange(true)
    }

    // 搜索入口改道裁决（搜索 UI 重做）：顶栏不再内嵌可展开输入框，点击搜索图标
    // 直接 onSearch("")（空词 = 进搜索页不落搜索，停在历史/猜你想搜建议面板）；
    // 旧的"展开 TextField + 历史 chips 浮层"整段移除，聚焦挤压问题随入口改道消失

    Box(
        modifier
            .fillMaxWidth()
            // 顶部染色渐变已删（用户反馈：染色层影响视频原来的色彩；顶栏文字自带描边）
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧区 ≈ 右侧两个图标按钮宽度（搜索/分屏），让双 tab 在整栏中视觉居中。
            // 搜索入口改道跳页后顶栏布局恒定（无展开态），布局不再有条件撤出。
            // R157：源面板入口独立控件化——「内容源」图标（☰）常驻左缘，点击展开源面板
            // （原挂在"推荐∨"标题上的入口挪此；推荐降重为纯 tab 后标题不再兼面板开关）。
            // 图标右侧的剩余宽度给两个互斥的附属件：
            //  · R141 盲盒流在播 → 显式"摇一把"胶囊（点 = 换词重抽）；
            //  · 潜进源（收藏夹/单频道等）→ 暗色"· 源名"面包屑（单击开源面板、双击刷新）。
            Row(
                Modifier.width(132.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = openSheet) {
                    Icon(
                        Icons.Outlined.Menu,
                        contentDescription = "内容源",
                        tint = TgFeedTokens.TextHigh,
                    )
                }
                when {
                    blindBoxActive -> ShakeOncePill(onClick = onShakeBlindBox)
                    !followingActive && !aggregateActive -> {
                        // 潜进源面包屑：暗色小字（次要被动态），weight 限定在左区剩余宽度内
                        // 省略，双 tab 居中位不被长源名挤歪；单击 = 开源面板切换（旧"点标题
                        // 换源"手法延续），双击 = 刷新当前源
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { openSheet() },
                                        onDoubleTap = { onRefreshFeed() },
                                    )
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "· $title",
                                color = TgFeedTokens.TextHigh.copy(alpha = 0.6f),
                                style = TextStyle(fontSize = 15.sp).withOverlayShadow(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // R157 顶栏平级双 tab（抖音式）→ R239 三 tab：关注|时间线|推荐
                // （HomeTabsRow 唯一事实源渲染件——与 PursueScreen 页头共用，防两处
                // 漂移）。推荐不挂下拉箭头/源面板，关注/时间线不被比下去。
                // R189 双击刷新规则：单击已激活 = 无操作（R157「在播时单击=刷新一批」
                // 废止——用户拍板刷新触发收窄为双击）；双击已激活 = onRefreshFeed/
                // onRefreshTimeline 刷新该流；单击未激活 = 纯切换（窗口判定在
                // TopLevelTab 共享件内）
                HomeTabsRow(
                    followingActive = followingActive,
                    timelineActive = timelineActive,
                    aggregateActive = aggregateActive,
                    onSelectFollowing = onSelectFollowing,
                    onSelectTimeline = onSelectTimeline,
                    onSelectAggregate = onSelectAggregate,
                    onRefreshFeed = onRefreshFeed,
                    onRefreshTimeline = onRefreshTimeline,
                )
            }

            // 右侧两入口 = 搜索 / 分屏。
            // （R173 底栏时代：原第三枚人像「我的」迁底栏右端真头像座位——顶栏右区
            // 从 3 图标减到 2，用户域入口唯一化，design-proposal §2.3）
            // 搜索：点击直达全屏搜索页（空词进页停在建议面板），顶栏本身不产生任何
            // 展开/聚焦态——布局在点击前后逐像素一致，只剩路由切换
            IconButton(onClick = { onSearch("") }) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索",
                    tint = TgFeedTokens.TextHigh,
                )
            }
            // 分屏模式（原藏在源面板的 SourceChip，提到顶栏一键直达 GridScreen）
            IconButton(onClick = onGridMode) {
                Icon(
                    Icons.Outlined.GridView,
                    contentDescription = "分屏模式",
                    tint = TgFeedTokens.TextHigh,
                )
            }
        }

        // 内容分区 tab 带（抖音式）：全部 / 视频 / 图文 / 音频
        // （搜索入口已改道跳页，顶栏无展开态，tab 带恒定显示）
        // R181 轨道跟手（用户设计：滑动中 tab 高亮实时跟手）：拖拽期间当前 tab 的选中
        // 权重按 fraction 渐变让渡给目标 tab（颜色/加粗在权重过半翻转=与 commit 判定
        // 同拍；指示条红度同权重渐变）——慢速拖到一半高亮已翻到目标 tab，松手即落位。
        // 拖拽在 L1 边界（无目标 tab）时 railDrag=null，带不动=防误触死区的一部分
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // 音频挪到图文右侧（用户要求）：全部 / 视频 / 图文 / 音频
            listOf(-1 to "全部", 0 to "视频", 1 to "图文", 2 to "音频").forEach { (t, label) ->
                // 选中权重：空闲 = 当前 tab 满权重；拖拽中 = 当前 tab 让渡给目标 tab
                val weight = when {
                    railDrag == null -> if (t == mediaTypeTab) 1f else 0f
                    t == mediaTypeTab -> (1f - railDrag.fraction).coerceIn(0f, 1f)
                    t == railDrag.targetTab -> railDrag.fraction.coerceIn(0f, 1f)
                    else -> 0f
                }
                val selected = weight > 0.5f
                // R189 双击刷新规则（二等公民同口径）：单击未激活 = 纯切换（立即，
                // 不迟滞）；单击已激活 = 无操作；双击已激活 = onRefreshFeed 刷新该流。
                // 判定走 rememberDoubleTapRule 共享窗口状态机（与顶栏双 tab/底栏三键
                // 同一实现——全限定调用免 import 混入并发代理的 hunk）
                val handleBandTap = dev.tgfeed.ui.rememberDoubleTapRule(
                    active = t == mediaTypeTab,
                    onSwitch = { onSelectMediaType(t) },
                    onRefresh = { onRefreshFeed() },
                )
                Column(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = handleBandTap)
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
                    // 选中指示：抖音式短红条（R181：红度随跟手权重渐变）
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .size(width = 18.dp, height = 2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                TgFeedTokens.AccentRed.copy(alpha = weight),
                            ),
                    )
                }
            }
        }
    }

    // 内容源选择面板（R173 迁 AppSheetMotion 动效壳；关窗后回调 onDismiss 落组合）
    if (sheetOpen) {
        SourcePickerSheet(
            channels = channels,
            subscribedIds = subscribedIds,
            onDismiss = {
                sheetOpen = false
                onSheetOpenChange(false)
            },
            onSelectChannel = onSelectChannel,
            onSelectFavorites = onSelectFavorites,
            onSelectWatchLater = onSelectWatchLater,
            onSelectSubscriptions = onSelectSubscriptions,
            onOpenCollections = onOpenCollections,
            onToggleSubscription = onToggleSubscription,
            onFindSimilar = onFindSimilar,
            onOpenFilter = onOpenFilter,
            onOpenKeywordFeed = onOpenKeywordFeed,
            onOpenLuckyPicker = onOpenLuckyPicker,
            avatarLoader = avatarLoader,
            followHasNew = followHasNew,
            channelMeta = channelMeta,
            manualIndexRows = manualIndexRows,
            onRequestDeepIndex = onRequestDeepIndex,
            onCancelDeepIndexPin = onCancelDeepIndexPin,
        )
    }
}

/** 频道字母头像的柔和底色板：按频道 id 哈希取色，同一频道颜色稳定 */
private val AvatarPalette = listOf(
    Color(0xFF5C6BC0), // 柔靛蓝
    Color(0xFF26A69A), // 柔青绿
    Color(0xFFEF5350), // 柔红
    Color(0xFFAB47BC), // 柔紫
    Color(0xFFFFA726), // 柔橙
    Color(0xFF42A5F5), // 柔蓝
    Color(0xFF66BB6A), // 柔绿
    Color(0xFFEC407A), // 柔粉
)

/** 按频道 id 稳定取一个头像底色（floorMod 避免 Long.MIN_VALUE 取负） */
private fun avatarColor(channelId: Long): Color =
    AvatarPalette[Math.floorMod(channelId, AvatarPalette.size)]

/**
 * 内容源选择面板（R173 §4.2 底栏时代瘦身版）：AppSheetMotion 左抽屉形态
 * （用户实锤裁决：☰ 触发点在顶栏左缘，面板从屏底上滑与触发点空间脱节 →
 * 改抽屉左缘滑入；spring 0.88/420 + scrim 收编 OverlayBg + 三段 40ms stagger
 * + 左缘/头部横拖关窗，全高、频道目录填满剩余高度）。
 * 信息架构三段式：头部（拖拽条 + 标题「内容源」 + 关闭 ×，壳内建）
 * →「我的流」段（收藏夹/稍后再看/订阅追更/手气不错；手气是随机流，归「我的流」语义）
 * →「换着看」段（关键词/频道合集/内容筛选；只剩低频调制器）
 * →「直达频道」段（筛选框 + 频道卡列表）。
 * R173 入口去冗余：推荐/发现/片单/已看历史四 chip 删除（推荐=顶栏双 tab、发现=底栏、
 * 片单/历史=我的页行，双入口去重——design-proposal §2.3）。
 * 快捷源点了走回调并关窗；相似/追更胶囊只触发回调、不关窗（与原 DropdownMenu 行为一致）；
 * 内容筛选/手气不错走「先收再开」：面板完全收起后才开 FilterSheet/LuckyPickerSheet
 * （弹层铁律 §3.3：同一任务流不出现两层 sheet 垂直叠）。
 */
@Composable
private fun SourcePickerSheet(
    channels: List<ChannelBrief>,
    subscribedIds: Set<Long>,
    onDismiss: () -> Unit,
    onSelectChannel: (ChannelBrief) -> Unit,
    onSelectFavorites: () -> Unit,
    onSelectWatchLater: () -> Unit,
    onSelectSubscriptions: () -> Unit,
    onOpenCollections: () -> Unit,
    onToggleSubscription: (ChannelBrief) -> Unit,
    onFindSimilar: (ChannelBrief) -> Unit,
    // 内容筛选入口（「换着看」段 chip：面板完全收起后再开 FilterSheet）
    onOpenFilter: () -> Unit = {},
    // 关键词订阅源
    onOpenKeywordFeed: () -> Unit = {},
    // R141 手气不错合并入口（「我的流」段胶囊：面板完全收起后开 LuckyPickerSheet）
    onOpenLuckyPicker: () -> Unit = {},
    avatarLoader: (suspend (Long) -> ByteArray?)? = null,
    // R66 追更红点："订阅追更"chip 的小红点角标（不显示数字）；默认 false 不接线也可编译
    followHasNew: Boolean = false,
    // R68 频道卡元数据：鲜活信号行 + 最近看过优先排序；默认空表（信号行不显示、按名字排序）
    channelMeta: Map<Long, dev.tgfeed.core.feed.ChannelCardMeta> = emptyMap(),
    // R191 手动钉住深索引：频道行「深度索引」按钮（钉/建库/进度/✓/取消钉）
    manualIndexRows: Map<Long, dev.tgfeed.core.feed.ManualIndexRowState> = emptyMap(),
    onRequestDeepIndex: (ChannelBrief) -> Unit = {},
    onCancelDeepIndexPin: (ChannelBrief) -> Unit = {},
) {
    // 频道筛选关键词（contains，忽略大小写）
    var filter by remember { mutableStateOf("") }
    // 筛选框回车收键盘（关窗时壳内也会统一收）
    val keyboard = LocalSoftwareKeyboardController.current

    AppSheetMotion(onDismiss = onDismiss, title = "内容源", anchor = SheetAnchor.LEFT) { sheet ->
        // 快捷源：触发回调并关窗（退场动画壳内播）
        val pick: (() -> Unit) -> Unit = { action ->
            action()
            sheet.dismiss()
        }
        // 顶部域链路面板（同为左抽屉形态）：等本面板完全收起后再打开——抽屉→抽屉
        // 平移衔接、永不叠层（先收再开；R178 起 FilterSheet/LuckyPicker/CollectionsDialog
        // 均已迁 AppSheetMotion 左抽屉）
        val pickChain: (() -> Unit) -> Unit = { action -> sheet.dismiss { action() } }
        val pickFilter: () -> Unit = { pickChain(onOpenFilter) }
        // 手气不错同款：源面板收起后再开 LuckyPickerSheet（FeedScreen 宿主）
        val pickLucky: () -> Unit = { pickChain(onOpenLuckyPicker) }

        Column(Modifier.fillMaxWidth()) {
            // ---------------- 段一「我的流」（t=0）：我攒下的内容形成的流 ----------------
            SheetStaggeredSection(delayMs = 0) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel(
                        "我的流",
                        Modifier.padding(
                            start = TgFeedTokens.GapXl,
                            top = TgFeedTokens.GapSm,
                            bottom = TgFeedTokens.GapSm,
                        ),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TgFeedTokens.GapLg),
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    ) {
                        SourceChip(Icons.Outlined.Star, "收藏夹", { pick(onSelectFavorites) }, Modifier.weight(1f))
                        SourceChip(Icons.Outlined.WatchLater, "稍后再看", { pick(onSelectWatchLater) }, Modifier.weight(1f))
                        // R66：任一订阅频道有未看新内容时，本 chip 图标右上角叠小红点
                        SourceChip(
                            Icons.Outlined.Notifications, "订阅追更",
                            { pick(onSelectSubscriptions) }, Modifier.weight(1f), showDot = followHasNew,
                        )
                        // 实心骰子：描线版辨识度差（用户反馈"看着不像骰子"）。
                        // R141 手气不错并入「我的流」段（随机流也是流，§4.2 段一语义纯化）；
                        // 点开 LuckyPickerSheet 双选项卡（开一个频道/抽一串内容）
                        SourceChip(Icons.Filled.Casino, "手气不错", { pickLucky() }, Modifier.weight(1f))
                    }
                }
            }

            // ---------------- 段二「换着看」（t=+40ms）：低频调制器 ----------------
            SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel(
                        "换着看",
                        Modifier.padding(
                            start = TgFeedTokens.GapXl,
                            top = TgFeedTokens.GapLg,
                            bottom = TgFeedTokens.GapSm,
                        ),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TgFeedTokens.GapLg),
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    ) {
                        SourceChip(Icons.Outlined.ManageSearch, "关键词", { pick(onOpenKeywordFeed) }, Modifier.weight(1f))
                        // 频道合集：同为顶部域链路面板（左抽屉）——先收再开防双抽屉叠层
                        SourceChip(Icons.Outlined.Folder, "频道合集", { pickChain(onOpenCollections) }, Modifier.weight(1f))
                        // 内容筛选（原顶栏漏斗钮；面板完全收起后再开 FilterSheet）
                        SourceChip(Icons.Outlined.Tune, "内容筛选", { pickFilter() }, Modifier.weight(1f))
                    }
                }
            }

            // ---------------- 段三「直达频道」（t=+80ms）：筛选框 + 频道卡列表 ----------------
            if (channels.isNotEmpty()) {
                SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs * 2) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel(
                            "直达频道",
                            Modifier.padding(
                                start = TgFeedTokens.GapXl,
                                top = TgFeedTokens.GapLg,
                                bottom = TgFeedTokens.GapSm,
                            ),
                        )

                        // 全圆角筛选输入框（白 α0.14 底、指示线透明，与顶栏搜索同语言）
                        TextField(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = TgFeedTokens.GapLg),
                            placeholder = { Text("输入频道名筛选", color = TgFeedTokens.TextLow) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = TgFeedTokens.TextLow,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TgFeedTokens.TextHigh),
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = TgFeedTokens.TextHigh,
                                unfocusedTextColor = TgFeedTokens.TextHigh,
                                cursorColor = TgFeedTokens.TextHigh,
                                focusedContainerColor = Color.White.copy(alpha = 0.14f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.14f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        )

                        // contains 过滤（忽略大小写，关键词变化实时刷新）+ R68 排序：
                        // 最近看过的频道排前面（watch_events 口径 lastWatchMs 倒序），
                        // 从没看过的排在后面按频道名字典序
                        val sortedFiltered = remember(channels, filter, channelMeta) {
                            val f = filter.trim()
                            val base = if (f.isEmpty()) channels
                            else channels.filter { it.title.contains(f, ignoreCase = true) }
                            base.sortedWith(
                                compareByDescending<ChannelBrief> { channelMeta[it.id]?.lastWatchMs ?: 0L }
                                    .thenBy { it.title },
                            )
                        }

                        if (sortedFiltered.isEmpty()) {
                            Text(
                                "没有匹配的频道",
                                color = TgFeedTokens.TextLow,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = TgFeedTokens.GapXl),
                            )
                        } else {
                            // 频道目录：抽屉全高剩余空间滚动（weight 填满，频道目录是
                            // 抽屉主体——tgfeed 差异化的核心切换器）
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(top = TgFeedTokens.GapSm),
                            ) {
                                items(sortedFiltered, key = { it.id }) { c ->
                                    ChannelCard(
                                        channel = c,
                                        meta = channelMeta[c.id],
                                        subscribed = c.id in subscribedIds,
                                        avatarLoader = avatarLoader,
                                        manualState = manualIndexRows[c.id],
                                        onClick = { pick { onSelectChannel(c) } },
                                        onFindSimilar = { onFindSimilar(c) },
                                        onToggleSubscription = { onToggleSubscription(c) },
                                        onRequestDeepIndex = { onRequestDeepIndex(c) },
                                        onCancelDeepIndexPin = { onCancelDeepIndexPin(c) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部呼吸空间（导航条让位由壳的 navigationBarsPadding 管）
            Spacer(Modifier.height(TgFeedTokens.GapMd))
        }
    }
}

/**
 * R141 手气不错面板（R178 迁 AppSheetMotion 左抽屉 · 盲盒品牌唯一入口）：原"手气不错"（开一个频道）
 * 与原"盲盒流"（抽一串内容）三个盲盒心智合并为一个品牌下的两个大选项卡。
 * - 「开一个频道」：随机频道随机位置开刷（现手气不错逻辑）；
 * - 「抽一串内容」：预设混沌词大 chip 网格（一次点选即开刷）+「随机摇」通栏按钮
 *   （免填词直开）+ 自定义关键词折叠输入区。说明压成一句人话，无实现词。
 * 调用方（FeedScreen）宿主本面板；两个模式动作都先关面板再触发开刷回调
 * （壳内退场动画播完再开——先收再开）。
 *
 * @param onOpenChannelBlindBox 「摇一个频道开刷」（现手气不错逻辑）
 * @param onOpenInlineBlindBox 抽一串内容：参数 = 选定的词；null = 随机摇（调用方现摇现传）
 */
@Composable
internal fun LuckyPickerSheet(
    onDismiss: () -> Unit,
    onOpenChannelBlindBox: () -> Unit,
    onOpenInlineBlindBox: (String?) -> Unit,
) {
    // 选项卡：0 = 开一个频道（默认，沿用旧手气不错主路）/ 1 = 抽一串内容
    var tab by remember { mutableStateOf(0) }
    // 自定义关键词折叠态 + 输入内容
    var customOpen by remember { mutableStateOf(false) }
    var customWord by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // R178 就近锚定迁移：源面板「我的流」chip 触发点在左上域 → 左抽屉形态（与源面板
    // 同一 AppSheetMotion 壳 + 先收再开链路；AppSheet/ModalBottomSheet 底部形态退役）
    AppSheetMotion(
        onDismiss = onDismiss,
        title = "手气不错",
        anchor = SheetAnchor.LEFT,
    ) { sheet ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg),
        ) {
            // 两个大选项卡（横向 tab，等宽对等选择）：选中红字红描边，与全 App 选中语言一致
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                LuckyModeTab("开一个频道", tab == 0, { tab = 0 }, Modifier.weight(1f))
                LuckyModeTab("抽一串内容", tab == 1, { tab = 1 }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 选项卡内容切换：淡入 + 轻位移（§3.4 状态切换节律）
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(TgFeedTokens.AnimMed)) +
                        slideInVertically(tween(TgFeedTokens.AnimMed)) { it / 12 }) togetherWith
                        fadeOut(tween(TgFeedTokens.AnimFast))
                },
                label = "luckyTab",
            ) { t ->
                Column(Modifier.fillMaxWidth()) {
                    when (t) {
                        // ---- 选项卡一：开一个频道（现手气不错逻辑） ----
                        0 -> {
                            Text(
                                "随机一个频道、随机一个位置，落哪算哪，直接开刷。",
                                color = TgFeedTokens.TextMedium,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(TgFeedTokens.GapMd))
                            Button(
                                onClick = {
                                    // 先收再开：抽屉退场动画播完再开刷（壳统一保证）
                                    sheet.dismiss { onOpenChannelBlindBox() }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TgFeedTokens.AccentRed,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.Casino,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(TgFeedTokens.GapXs))
                                Text("摇一个频道", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        // ---- 选项卡二：抽一串内容（预设词网格 + 随机摇 + 自定词） ----
                        else -> {
                            Text(
                                "机器人现抽现配的一串内容，每个词都是一次开盲盒。",
                                color = TgFeedTokens.TextMedium,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(TgFeedTokens.GapMd))
                            // 预设混沌词 3 列大 chip 网格（每次打开随机摆 12 个，
                            // 单一事实源在 InlineBotFeedSource.CHAOS_KEYWORDS）：
                            // 一次点选即开刷，盲盒观感从选词这步就开始
                            val presets = remember {
                                dev.tgfeed.core.feed.InlineBotFeedSource.CHAOS_KEYWORDS
                                    .shuffled().take(12)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                                presets.chunked(3).forEach { rowWords ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                                        rowWords.forEach { word ->
                                            LuckyWordChip(word, Modifier.weight(1f)) {
                                                sheet.dismiss { onOpenInlineBlindBox(word) }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(TgFeedTokens.GapMd))
                            // 「随机摇」通栏按钮：免填词直开，摇号在调用方完成
                            Button(
                                onClick = {
                                    sheet.dismiss { onOpenInlineBlindBox(null) }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TgFeedTokens.AccentRed,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.Casino,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(TgFeedTokens.GapXs))
                                Text("随机摇", style = MaterialTheme.typography.titleSmall)
                            }
                            Spacer(Modifier.height(TgFeedTokens.GapMd))
                            // 自定义关键词折叠区：「自定义关键词 >」展开输入
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                    .clickable {
                                        customOpen = !customOpen
                                        if (!customOpen) keyboard?.hide()
                                    }
                                    .padding(vertical = TgFeedTokens.GapSm),
                            ) {
                                Text(
                                    "自定义关键词",
                                    color = TgFeedTokens.TextMedium,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (customOpen) "收起" else "展开",
                                    tint = TgFeedTokens.TextLow,
                                    modifier = Modifier
                                        .graphicsLayer { rotationZ = if (customOpen) 180f else 0f }
                                        .size(16.dp),
                                )
                            }
                            if (customOpen) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                                ) {
                                    TextField(
                                        value = customWord,
                                        onValueChange = { customWord = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("输入关键词", color = TgFeedTokens.TextLow) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TgFeedTokens.TextHigh),
                                        shape = CircleShape,
                                        colors = TextFieldDefaults.colors(
                                            focusedTextColor = TgFeedTokens.TextHigh,
                                            unfocusedTextColor = TgFeedTokens.TextHigh,
                                            cursorColor = TgFeedTokens.TextHigh,
                                            focusedContainerColor = Color.White.copy(alpha = 0.14f),
                                            unfocusedContainerColor = Color.White.copy(alpha = 0.14f),
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                keyboard?.hide()
                                                val q = customWord.trim()
                                                if (q.isNotEmpty()) {
                                                    sheet.dismiss { onOpenInlineBlindBox(q) }
                                                }
                                            },
                                        ),
                                    )
                                    Button(
                                        onClick = {
                                            val q = customWord.trim()
                                            if (q.isNotEmpty()) {
                                                sheet.dismiss { onOpenInlineBlindBox(q) }
                                            }
                                        },
                                        enabled = customWord.isNotBlank(),
                                        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = TgFeedTokens.AccentRed,
                                            contentColor = Color.White,
                                        ),
                                        contentPadding = PaddingValues(
                                            horizontal = TgFeedTokens.GapLg,
                                            vertical = TgFeedTokens.GapSm + 4.dp,
                                        ),
                                    ) {
                                        Text("摇一把", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
        }
    }
}

/** 手气不错选项卡头：等宽大卡，选中红字红描边（AccentRed=选中语义），未选中灰字深底 */
@Composable
private fun LuckyModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        color = if (selected) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            .border(
                width = 1.dp,
                color = if (selected) TgFeedTokens.AccentRed else Color.Transparent,
                shape = RoundedCornerShape(TgFeedTokens.RadiusMd),
            )
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapMd),
    )
}

/** 预设混沌词大 chip（3 列网格用）：SurfaceHigh 深底、居中、一次点选即开刷 */
@Composable
private fun LuckyWordChip(
    word: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        word,
        color = TgFeedTokens.TextHigh,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapMd),
    )
}

/** 顶栏"摇一把"胶囊（盲盒流在播时）：骰子星黄点 + 摇一把，点 = 换词重抽 */
@Composable
private fun ShakeOncePill(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBg)
            .border(
                width = 0.5.dp,
                color = TgFeedTokens.OverlayHairline,
                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.Casino,
            contentDescription = null,
            tint = TgFeedTokens.StarYellow,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "摇一把",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 快捷源胶囊：全圆角、SurfaceHigh 底，图标 + 文字居中；[showDot] 时图标右上角叠小红点角标 */
@Composable
private fun SourceChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // R66 追更红点角标：小红点不显示数字（"订阅追更"chip 用），默认无角标
    showDot: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapMd),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                tint = TgFeedTokens.TextHigh,
                modifier = Modifier.size(16.dp),
            )
            if (showDot) {
                // 小红点：7dp 正圆，探出图标右上角（与系统通知角标同语言，不显示数字）
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(TgFeedTokens.AccentRed),
                )
            }
        }
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        Text(
            label,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** R68 段标题：labelSmall + TextLow，信息架构三段式（我的资产/探索/直达频道）的小标题 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TgFeedTokens.TextLow,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

/**
 * R68 鲜活信号行文案（纯本地索引口径，零网络）：
 * "今天更了 N 条 · 索引共 M 条"——今日无更新时退化为"最近更新于 x 天前"；
 * 索引未覆盖（无统计）返回 null，该行不显示。
 * R191 [liveCount]：手动深索引行台账的鲜活计数（爬取中/刚完成的累计值）——与
 * 统计快照 indexCount 取大者（快照 5min 轮次刷新，台账页粒度更鲜）。
 */
private fun livenessText(
    meta: dev.tgfeed.core.feed.ChannelCardMeta?,
    nowSec: Long,
    liveCount: Int = 0,
): String? {
    val count = maxOf(meta?.indexCount ?: 0, liveCount)
    if (count <= 0) return null
    val head = when {
        meta != null && meta.todayCount > 0 -> "今天更了 ${meta.todayCount} 条"
        meta != null && meta.latestSec > 0L -> {
            val days = ((nowSec - meta.latestSec) / 86400L).coerceAtLeast(0L)
            when {
                days == 0L -> "24 小时内有更新" // 与"今天更了 N 条"互斥（该分支 todayCount==0）
                days == 1L -> "昨天有更新"
                days < 30L -> "$days 天前有更新"
                else -> "许久未更新"
            }
        }
        else -> null
    }
    return listOfNotNull(head, "索引共 $count 条").joinToString(" · ")
}

/**
 * R68 频道卡：圆形头像（有真头像显示圆形裁剪图片，没有回落字母圆，id 哈希取柔和底色）
 * + 频道名（单行省略）+ 鲜活信号行（"今天更了 3 条 · 索引共 326 条"口径，索引未覆盖不显示）
 * + 行尾"相似"文字钮（替代 ✨ 图标）+"追/已追"文字胶囊（替代铃铛图标；已追态点击先弹
 * 一次确认再退追，防误触）。
 * R191 频道名与信号行之后加「深度索引」图标钮（数据语义 Storage）：未钉=描线灰
 * （点=钉住+立即建库）；在爬=spinner+行内"已索引 N 条"进度；完成=✓（5s 闪示）；
 * 已钉=填充蓝（点=确认弹窗取消钉——复用"已追"胶囊的防误触确认范式）。
 * 相似/胶囊/深索引钮点击不选中频道、不关窗；点卡片本体选中频道并关窗。
 */
@Composable
private fun ChannelCard(
    channel: ChannelBrief,
    meta: dev.tgfeed.core.feed.ChannelCardMeta?,
    subscribed: Boolean,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    manualState: dev.tgfeed.core.feed.ManualIndexRowState?,
    onClick: () -> Unit,
    onFindSimilar: () -> Unit,
    onToggleSubscription: () -> Unit,
    onRequestDeepIndex: () -> Unit,
    onCancelDeepIndexPin: () -> Unit,
) {
    // R194④：头像解码收编共享件（rememberChannelAvatarImage——IO 解码）。
    // 旧写法 remember(bytes){decodeByteArray} 在组合期主线程解码：抽屉 spring
    // 动画窗内 ~15 行头像陆续到货逐个解码+行重组 = 用户实测「左抽屉弹出卡顿
    // 掉帧」的主源（拉字节本身照旧走 loader 的进程内缓存）
    val avatarImage = rememberChannelAvatarImage(channel.id, avatarLoader)
    // 鲜活信号行（组合期现算相对天数；meta 为流快照，重组即刷新）：
    // 在爬=行内进度（"已索引 N 条"，设置页手动建库同款链路口径）；否则快照计数
    // 与台账终值取大
    val signal = if (manualState?.building == true) {
        if (manualState.indexedCount > 0) "已索引 ${manualState.indexedCount} 条" else "正在建立深度索引…"
    } else {
        livenessText(meta, System.currentTimeMillis() / 1000L, manualState?.indexedCount ?: 0)
    }

    // R68 退追确认：已追态点胶囊先弹一次确认，确认后才真正退追（防误触）
    var confirmUnfollow by remember(channel.id) { mutableStateOf(false) }
    // R191 取消钉确认：已钉态点「深度索引」钮先弹一次确认（与退追胶囊同一防误触范式）
    var confirmUnpinDeepIndex by remember(channel.id) { mutableStateOf(false) }
    if (confirmUnpinDeepIndex) {
        AlertDialog(
            onDismissRequest = { confirmUnpinDeepIndex = false },
            containerColor = TgFeedTokens.SurfaceHigh,
            title = { Text("取消深度索引", color = TgFeedTokens.TextHigh) },
            text = {
                Text(
                    "「${channel.title}」将回到自动评估的索引深度，已索引的内容保留可搜。",
                    color = TgFeedTokens.TextMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpinDeepIndex = false
                    onCancelDeepIndexPin()
                }) {
                    Text("取消钉住", color = TgFeedTokens.AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpinDeepIndex = false }) {
                    Text("保留", color = TgFeedTokens.TextLow)
                }
            },
        )
    }
    if (confirmUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            containerColor = TgFeedTokens.SurfaceHigh,
            title = { Text("退追频道", color = TgFeedTokens.TextHigh) },
            text = {
                Text(
                    "确定不再追更「${channel.title}」吗？",
                    color = TgFeedTokens.TextMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnfollow = false
                    onToggleSubscription()
                }) {
                    Text("退追", color = TgFeedTokens.AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnfollow = false }) {
                    Text("取消", color = TgFeedTokens.TextLow)
                }
            },
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatarImage != null) {
            // 真头像：圆形裁剪，非方形图居中裁满
            Image(
                bitmap = avatarImage,
                contentDescription = channel.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        } else {
            // 回落：圆形字母头像（频道名首字大写，底色按 id 稳定取色）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(avatarColor(channel.id)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    channel.title.firstOrNull()?.uppercase() ?: "?",
                    color = TgFeedTokens.TextHigh,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        // 频道名 + 鲜活信号行：weight(1f) 先为右侧"相似/胶囊"两个固定位留宽，
        // 长名在固定位之前截断为"…"（沿用 B11 口径）；信号行仅索引已覆盖时显示
        Column(
            Modifier
                .weight(1f)
                .padding(end = TgFeedTokens.GapSm),
        ) {
            Text(
                channel.title,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (signal != null) {
                Text(
                    signal,
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // R191「深度索引」图标钮（索引明细区旁）：未钉=描线 Storage（点=钉住+立即
        // 建库）；在爬=小 spinner；完成=✓ 闪示（SuccessGreen）；已钉=填充 Storage
        // （TgBlue=已追胶囊同款"已建立"色语义；点=确认取消钉）。只触发回调，
        // 不选中频道不关窗——触点 32dp 与相邻控件同密度
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable {
                    if (manualState?.pinned == true) confirmUnpinDeepIndex = true
                    else onRequestDeepIndex()
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                manualState?.building == true -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = TgFeedTokens.TgBlue,
                    strokeWidth = 2.dp,
                )
                manualState != null && dev.tgfeed.core.feed.manualRowShowCheck(manualState) -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "深度索引完成",
                    tint = TgFeedTokens.SuccessGreen,
                    modifier = Modifier.size(18.dp),
                )
                manualState?.pinned == true -> Icon(
                    Icons.Filled.Storage,
                    contentDescription = "深度索引已钉住，点按取消",
                    tint = TgFeedTokens.TgBlue,
                    modifier = Modifier.size(18.dp),
                )
                else -> Icon(
                    Icons.Outlined.Storage,
                    contentDescription = "深度索引",
                    tint = TgFeedTokens.TextLow,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        // 相似文字钮（替代 ✨ 图标）：只触发回调，不选中频道
        Text(
            "相似",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .clickable(onClick = onFindSimilar)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        // 追/已追文字胶囊（替代铃铛图标）：未追 SurfaceHigh 底，已追电报蓝浅底蓝字；
        // 已追态点击先弹确认再退追
        Box(
            Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(
                    if (subscribed) TgFeedTokens.TgBlue.copy(alpha = 0.22f)
                    else TgFeedTokens.SurfaceHigh,
                )
                .clickable {
                    if (subscribed) confirmUnfollow = true else onToggleSubscription()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                if (subscribed) "已追" else "追",
                color = if (subscribed) TgFeedTokens.TgBlue else TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
