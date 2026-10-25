package dev.tgfeed.ui.components

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.tgfeed.core.feed.DownloadStatus
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.td.DanmakuItem
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.safeInitialPlayUri
import dev.tgfeed.ui.PinchCleanEvent
import dev.tgfeed.ui.PlaybackTogglePolicy
import dev.tgfeed.ui.WindowSize
import dev.tgfeed.ui.fontScale
import dev.tgfeed.ui.isFrameAdjusted
import dev.tgfeed.ui.rememberWindowSize
import dev.tgfeed.ui.uiScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Feed 单页：视频画面 + 手势分区 + 全套浮层。
 * 手势分区（按落点 x 坐标）：左 25% / 右 25% 长按 = 2x 快进（按住 1.2s 自动锁定 / 松手取消），
 * 继续按住到 2s 转入连续 ±10s 步进模式（0.3s 一步，松手停；本次手势的 1.2s 锁定届时让位，
 * 按压前已锁定的则不进入连续模式）；
 * 中间 50% 长按 = 弹功能面板（ActionSheet）；单击 = 暂停/继续（清屏态恢复浮层、锁定态解除锁定）；
 * 双击 = 点赞飘心。竖滑翻页与进度条横拖不受影响。
 * R174 捏合清屏（提案 §3）：双指捏合缩放 1-4x 与 chrome（右轨/底部信息区/顶栏经
 * onOverlayStateChange）透明度连续线性映射（1.0→1.3 渐隐、无模式跳变）；≥1.3 清屏态
 * 生效；三退通道 = 单击恢复 chrome（缩放保留）/ 双击彻底复位（zoom 走弹簧）/ 返回键
 * 兜底；捏合回 1x 按同曲线渐回；滑走全复位。与全屏互斥（§3.5 几何层 vs chrome 层）。
 * 增强：起播/缓冲显示下载百分比与已下载大小（Mock 不显示）；横屏视频旋到横屏自动全屏，
 * 旋回竖屏自动退出；长按功能面板可截图当前帧存相册 Pictures/TGFeed（P0-1 右轨收敛后
 * 截图/静音/全屏收进面板）；loopOne 时播完原地循环。
 * 资源纪律沿用旧模式：失焦 pause、dispose release、listener 自加自删。
 *
 * @param onNotInterested 不感兴趣（屏蔽该频道），由功能面板触发
 * @param onWatchLater 稍后再看，由功能面板触发
 * @param onSetSpeed 功能面板倍速档位行：直接设定倍速
 * @param autoAdvance 自动连播开关当前值（面板 Switch 展示）
 * @param onSetAutoAdvance 切换自动连播
 * @param onSetSleepTimer 定时关闭：null = 不开启，否则为分钟数
 * @param sleepNow 定时到点事件流：收到即暂停并中央提示"定时关闭已暂停"
 * @param loopOne 循环单条当前值（Feed 级共享状态）：true 时播完不上抛跳页，原地 seekTo(0) 重播
 * @param onToggleLoop 切换循环单条（面板 Switch 回调）
 * @param onViewContext 查看上下文（功能面板动作）：切到该条所在频道 Feed 并定位到本条，
 * 由 FeedScreen 接 FeedViewModel.openChannelAt；"旋转"动作无参数，页内按 0/90/180/270
 * 循环，角度只对当前条目有效（划走归零；R122 起频道记忆设施已整体删除）
 */
@Composable
internal fun VideoPage(
    item: VideoItem,
    isActive: Boolean,
    gateway: TdGateway,
    playerPool: PlayerPool,
    liked: Boolean,
    favorited: Boolean,
    downloadStatus: DownloadStatus?,
    muted: Boolean,
    speed: Float,
    speedLabel: String,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenChannel: () -> Unit,
    onEnded: () -> Unit,
    // R160 加载态一次成型：本页是否为 pager 的"当前页"（拖拽/甩动过半即翻转，
    // 比 settledPage 早一拍）。底衬上报与页背景透明化挂它——翻页过半的瞬间
    // 新页列内占位与外层全屏底衬同帧换主，不再等落定（用户实测"分层登场"修复）
    isCurrent: Boolean = false,
    // 新增可选参数：点击 caption 里的 #话题（不含 # 号），FeedScreen 不传也能编译
    onSearchTag: (String) -> Unit = {},
    // 新增可选参数（透传给 BottomInfoPanel）：频道真头像加载器；null = 回落首字字母圆
    avatarLoader: (suspend (Long) -> ByteArray?)? = null,
    // 新增可选参数（透传给 BottomInfoPanel）：当前频道是否已订阅（信息区"追"快捷钮态）
    subscribed: Boolean = false,
    // 新增可选参数（透传给 BottomInfoPanel）：点击"追"快捷订阅当前频道
    onSubscribe: () -> Unit = {},
    // 新增可选参数（透传给 BottomInfoPanel）：点击 caption 里的 http(s)/t.me 链接
    onOpenLink: (String) -> Unit = {},
    // 新增可选参数（透传给 BottomInfoPanel）：点击 caption 里的 @用户名（不含 @）
    onOpenMention: (String) -> Unit = {},
    // 新增可选参数：不感兴趣（屏蔽该频道）
    onNotInterested: (VideoItem) -> Unit = {},
    // 新增可选参数：稍后再看
    onWatchLater: (VideoItem) -> Unit = {},
    // 新增可选参数：悬浮窗播放（弹出为可拖可缩放的悬浮小窗）
    onPopout: (VideoItem) -> Unit = {},
    // 新增可选参数：加入自定义分屏片单
    onAddToGrid: (VideoItem) -> Unit = {},
    // 续播：起播后 seekTo 的位置（毫秒，0=从头）；由外层从 ViewModel 查
    progressToRestore: Long = 0L,
    // 失焦/销毁时上报播放位置（毫秒），供续播持久化
    onSaveProgress: (VideoItem, Long) -> Unit = { _, _ -> },
    // 功能面板：转存到 Telegram 收藏夹 / 一键翻译标题
    onForward: (VideoItem) -> Unit = {},
    onTranslate: (VideoItem) -> Unit = {},
    // 翻译结果文本（null=未翻译），由外层状态驱动
    translatedCaption: String? = null,
    // 清屏/全屏状态变化上报（外层借此隐藏顶栏等共享浮层）。
    // R174 扩展：hidden 语义不变（cleanMode||fullscreen，α=0 或全屏时为真）；
    // chromeAlpha 为连续渐隐值（1=全显，捏合跟手）——外层顶栏以 graphicsLayer α 承接
    onOverlayStateChange: (hidden: Boolean, chromeAlpha: Float) -> Unit = { _, _ -> },
    // 填充模式：true=填满裁切（抖音默认），false=完整显示（留黑边）
    fillToScreen: Boolean = true,
    onSetFillMode: (Boolean) -> Unit = {},
    // 新增可选参数：功能面板倍速档位行直接设倍速
    onSetSpeed: (Float) -> Unit = {},
    // 清晰度：全局偏好的目标高度档（0=自动，跟随主档）；功能面板"清晰度"行 chips 高亮与起播选档共用
    qualityHeight: Int = 0,
    // 功能面板清晰度档设定（0=自动 或某档 height；全局偏好，由 FeedScreen 落 AppPrefs）
    onSetQuality: (Int) -> Unit = {},
    // 新增可选参数：自动连播开关当前值
    autoAdvance: Boolean = true,
    // 新增可选参数：切换自动连播
    onSetAutoAdvance: (Boolean) -> Unit = {},
    // 新增可选参数：定时关闭（null=不开启，否则分钟数）
    onSetSleepTimer: (Int?) -> Unit = {},
    // 新增可选参数：定时到点事件（收到即暂停 + 中央提示）
    sleepNow: SharedFlow<Unit>? = null,
    // 新增可选参数：循环单条当前值（Feed 级共享状态，主代理接线时传入）
    loopOne: Boolean = false,
    // 新增可选参数：切换循环单条（功能面板 Switch）
    onToggleLoop: (Boolean) -> Unit = {},
    // R201 分享枢纽：右轨分享钮 / 长按面板「分享」格的统一回调——点按弹
    // ShareHubSheet 小菜单三选一（复制链接/分享到其他应用/分享卡片），链体在
    // FeedScreen 枢纽接线收口（原 onShareCard 直跳 ACTION_SEND、面板另有
    // onShareLink 复制链接，两入口连同图音面板「分享卡片」一并收编）
    onOpenShareHub: (VideoItem) -> Unit = {},
    // R227 画面调整枢纽：长按面板「画面调整」格（原「旋转」+「镜像翻转」两格
    // 收编）的回调——点按弹 FrameAdjustHubSheet 小菜单四选一（向左/向右旋转/
    // 左右镜像/恢复默认）；面板自持 FrameAdjustStore 读写（store 即单一真源，
    // 无能力接线），宿主只需托管弹层（FeedScreen frameAdjustTarget，分享枢纽同款）
    onOpenFrameAdjust: (VideoItem) -> Unit = {},
    // 发弹幕到 TG 广场（功能面板）
    onSendDanmaku: (VideoItem) -> Unit = {},
    // R205 TL-9 弹幕时间旅行（功能面板「弹幕回放」格）：拉广场历史弹幕流渲染
    // 时间轴回放视图（纯回放；宿主 FeedScreen 接线开回放面板）
    onOpenDanmakuTimeline: (VideoItem) -> Unit = {},
    // R204 DS-5 首发溯源（功能面板「首发溯源」格）：同指纹跨频道最早出现判定，
    // 宿主 FeedScreen 接线开 FirstSeenSheet（用户点按才触发全表扫——点按付费）
    onOpenFirstSeen: (VideoItem) -> Unit = {},
    // R202 TL-4 事件考古（功能面板「事件考古」格）：以该条发布时刻为锚点开时光机
    // ±48h 全网横截面（本地索引毫秒级零请求）；宿主 MainActivity 接线开时光机页
    onOpenEventDig: (VideoItem) -> Unit = {},
    // R233 口味星图二期（功能面板「在星图中查看」格）：跳进星图运镜飞向该条目
    // 所属恒星（Galaxy 双形态跳转——宿主 MainActivity 接线一次性运镜意图）
    onOpenTasteMap: (VideoItem) -> Unit = {},
    // 查看上下文（功能面板）：切到该条所在频道 Feed 并定位到本条（FeedViewModel.openChannelAt）
    onViewContext: (VideoItem) -> Unit = {},
    // 弹幕：当前条评论列表（null=未加载）+ 总开关。
    // DANMAKU-TG 任务 2 升级：元素是带真实发布时间戳/回复标记的 DanmakuItem
    //（TdLibGateway.getCommentsRich 产出；广场弹幕/Mock 由 FeedScreen 合成时间戳）
    danmakuComments: List<DanmakuItem>? = null,
    danmakuEnabled: Boolean = false,
    // 弹幕开关切换（操作栏弹幕钮）：外层写 prefs.danmakuEnabled 并翻转状态，即时生效
    onToggleDanmaku: () -> Unit = {},
    // TG 广场：全站在看数 / 点赞数
    plazaWatching: Int = 0,
    plazaLikes: Int = 0,
    // 模糊底衬上报：异向/FIT 时把封面交给外层全屏铺；null = 填满模式无底衬。
    // R159b 起播一拍：第二参 liveFrame = 本次上报的是否 R89 live 实帧（封面/清屏
    // 上报恒 false）——外层据此把「本条 live 制度首拍」并入同帧 snap（背景换血
    // 与首帧上屏同拍合并成「占位→画面」的一部分），之后 R89 常规跟随维持
    // 300ms 渐变口径
    onBackdropChange: (ImageBitmap?, liveFrame: Boolean) -> Unit = { _, _ -> },
    // R194② 轨道切换冻结窗代次：外层解冻时 +1——本页底衬上报 effect 键挂它，
    // 代次翻转=当前页重报一次最新帧（封面/live 实帧/清屏，取决于当下状态）。
    // 冻结窗内上报被外层缓收后，落位页的最新状态靠这一拍补齐（一次软切到位）
    backdropReportGen: Int = 0,
    // R194⑥ 顶栏实测底缘 px（悬停式顶锚的预留基准，外层 onSizeChanged 测得；
    // 0=未测得/无顶栏 → 顶锚让位居中兜底）
    topChromeBottomPx: Float = 0f,
    // 加载失败终态（网络类自动重试 4 次全败，或不可重试的稳定失败）的"换一条看"选项：
    // 外层负责翻到下一页；不传则网络类终态只留"点按重试"
    onSkip: () -> Unit = {},
    // 频道主页入口（右栏头像点击）：外层负责打开该频道主页（CHANNEL-HOME-FINAL P0）
    onOpenChannelHome: () -> Unit = {},
    // R159 内容感知列宽：本条画面宽高比上报（外层 FeedScreen 按条目 key 记账，
    // 落定页据此现算列宽）。两级来源：
    // ①预判（measured=false）：条目元数据缺尺寸（width/height≤0，老收藏/异常源）时，
    //   用封面位图宽高比当代理——加载态封面一到即上报，列宽加载中就放宽；
    //   元数据齐全的预判由外层直接从 item 自算，不经本回调（帧同步，无回调时延）；
    // ②校正（measured=true）：起播后 ExoPlayer onVideoSizeChanged 的真实画面比
    //   （含旋转/像素宽高比修正，与装帧同一口径），可信级最高、不回落。
    onVideoAspectChange: (aspect: Float, measured: Boolean) -> Unit = { _, _ -> },
) {
    // R80：自带地址先过收口判定（含 '#'/'?' 的裸路径置 null 走 ensurePlayable 换流地址）
    val playUriState = remember(item.key) { mutableStateOf(item.safeInitialPlayUri()) }
    var playUri by playUriState
    val bufferingState = remember(item.key) { mutableStateOf(true) }
    var buffering by bufferingState
    // R93 加载黑屏闪烁修复：首帧上屏标记。封面要一直保持到播放器真正把首帧
    // 画进 TextureView（onRenderedFirstFrame 回调）才撤；旧实现 player 一 acquire
    // 封面就随占位分支退场，而装填→首帧就绪之间有数百毫秒窗口，TextureView
    // 黑底透出——用户看到的"黑一下再闪回画面"就是这个窗口。换源/重拉时复位
    val firstFrameSeenState = remember(item.key) { mutableStateOf(false) }
    var firstFrameSeen by firstFrameSeenState
    // 加载看门狗状态：retryTick 在起播 effect 的 key 里（bump 即重拉）
    val retryTickState = remember(item.key) { mutableIntStateOf(0) }
    var retryTick by retryTickState
    // 自动退避重试链：failCount = 已发生的失败事件数（看门狗卡死 / onPlayerError 都喂它）。
    // 1..AUTO_RETRY_MAX 对应 3s/6s/12s/24s 退避后自动重拉；>AUTO_RETRY_MAX 才转 loadFailed
    // 终态。全程无需用户点按；播到 READY 即痊愈清零（见播放器监听）
    val failCountState = remember(item.key) { mutableIntStateOf(0) }
    var failCount by failCountState
    // 退避/重试进行中标记：看门狗与 onPlayerError 据此不再重复记失败
    //（防退避期里 6s 看门狗窗口/连发错误回调对同一现场重复计数）
    val retryPendingState = remember(item.key) { mutableStateOf(false) }
    var retryPending by retryPendingState
    // 重试换路用的条目副本：首次 = 原条目；自动重试时剥掉缓存 playUri
    //（ensurePlayable 对 item.playUri 短路直返——TDLib 抓取时写入的本地路径一旦被
    // 清缓存/删除，每次重试都原样拿回这个死路径，是"点重试还无限失败"的根因之一），
    // 第 2 次起重试连 fileId 也按消息/指纹重新解析（fileId 是会话级 id，可能整个失效）
    val retryItemState = remember(item.key) { mutableStateOf(item) }
    var retryItem by retryItemState
    // 加载失败终态：自动重试链 4 次全败、或撞上不可重试的稳定失败（fatalReason 非空）
    // 才置位（不再由用户点按驱动重试）。置位后页面给"点按重试 / 换一条看"出口，不再黑屏死等
    val loadFailedState = remember(item.key) { mutableStateOf(false) }
    var loadFailed by loadFailedState
    // R78 重试分诊：不可重试失败的病因说明（解码失败/资源失效等稳定终态——
    // 自动重试救不了，onPlayerError 判出后直接进失败终态，不假装在努力）；
    // null = 当前失败属真网络类，走自动退避重试链。判据见 fatalReasonOf
    val fatalReasonState = remember(item.key) { mutableStateOf<String?>(null) }
    var fatalReason by fatalReasonState
    // R86 内容损坏换源重下计数（3001 容器解析/4003 解码失败类）：同条目累计，
    // 刻意不随 READY 清零——坏区在文件中段的真坏文件会「起播 READY→播到坏区再炸」，
    // READY 清零会让它无限重下重播；同条目两连击才进格式终态（那才是真坏文件）。
    // 仅用户手动点按重试时清零（人工确认后再给一轮机会，见失败终态点按分支）
    val corruptRetryCountState = remember(item.key) { mutableIntStateOf(0) }
    var corruptRetryCount by corruptRetryCountState
    val pausedState = remember(item.key) { mutableStateOf(false) }
    var paused by pausedState
    // 双击点赞飘心粒子列表（一次双击爆出 3-5 颗，抖音式多心爆散）
    var hearts by remember(item.key) { mutableStateOf(listOf<HeartParticle>()) }
    // ── R138 点赞回写可见反馈（design-redesign §2.3）：三态 + 会话观测 ──
    // 场景化引导锚点：非 null = 引导气泡显示中（锚在双击落点；划页/8 秒超时即收）
    var likeGuideAnchor by remember(item.key) { mutableStateOf<Offset?>(null) }
    // 状态查询面板显隐（长按点赞钮触发）
    var showLikeStatusSheet by remember(item.key) { mutableStateOf(false) }
    // 送达胶囊闪现（回写受理成功时顶部短现 1.8s）
    var writebackSyncedPill by remember(item.key) { mutableStateOf(false) }
    // 本条回写的会话状态（观测板按 chatId:messageId 记账：划走划回不丢"已送达"）
    val likeWritebackState = LikeWritebackBoard.stateOf(item)
    // 双击点赞脉冲：自增即触发右侧操作栏点赞钮弹簧弹跳（likeBounce 动画见下）
    var likePulse by remember(item.key) { mutableIntStateOf(0) }
    // 点赞钮弹跳动画值：likePulse 变化时先冲到 1.18x 再弹簧回落 1x
    // R178 收编：应答弹跳单一来源 = MotionTokens.LikeBounce（与 ActionRail RailIcon
    // 弹跳同一常数两处各写的类违规收敛；反馈应答弹簧域，见 token 注释）
    val likeBounce = remember { Animatable(1f) }
    LaunchedEffect(likePulse) {
        if (likePulse > 0) {
            likeBounce.snapTo(1.18f)
            likeBounce.animateTo(
                1f,
                spring(
                    dampingRatio = dev.tgfeed.ui.theme.MotionTokens.LikeBounceDamping,
                    stiffness = dev.tgfeed.ui.theme.MotionTokens.LikeBounceStiffness,
                ),
            )
        }
    }
    // 恢复播放时中央闪现 ⏸ 标记
    var flashResume by remember(item.key) { mutableStateOf(false) }
    // 长按 2x 快进态：松手/翻页恢复 speed 档；锁定后松手保持
    var longPressBoost by remember(item.key) { mutableStateOf(false) }
    // 2x 已锁定（长按侧边下滑触发）：顶部胶囊提示，单击任意处解除
    var boostLocked by remember(item.key) { mutableStateOf(false) }
    // 长按中间 50% 弹出的功能面板
    var showSheet by remember(item.key) { mutableStateOf(false) }
    // 清屏播放：隐藏本页一切浮层（操作栏/信息区），只留视频与底部进度细线；单击任意处恢复。
    // R174 起状态本体迁入 PinchCleanHost（zoom↔chrome α 连续映射/滞回/三退通道
    // 状态机，纯模型见 PinchCleanPolicy.kt）；读点一律 pinch.cleanMode（宿主读取视图）
    // 横屏全屏态：隐藏操作栏与信息区，保留进度条与退出按钮
    val fullscreenState = remember(item.key) { mutableStateOf(false) }
    var fullscreen by fullscreenState
    // 定时关闭到点后的中央提示（几秒后自动消失）
    var sleepPausedHint by remember(item.key) { mutableStateOf(false) }
    // 定时关闭档位的本地镜像：仅用于面板 chips 高亮（真实计时状态由 FeedScreen 持有，跨页以它为准）
    var sleepTimerLocal by remember { mutableStateOf<Int?>(null) }
    // 侧边长按 2x 的自动锁定计时协程
    var boostLockJob by remember { mutableStateOf<Job?>(null) }
    // 侧边长按 2s 后进入的连续 ±10s 步进模式（0.3s 一步，松手停）
    var continuousSeek by remember(item.key) { mutableStateOf(false) }
    // 连续步进方向：true = 快进（右 25% 区），false = 快退（左 25% 区）
    var continuousForward by remember(item.key) { mutableStateOf(true) }
    // 连续步进循环协程
    var continuousSeekJob by remember { mutableStateOf<Job?>(null) }
    // 起播/缓冲的下载进度（-1 = 未知或 Mock，不显示百分比）；1s 轮询 TDLib GetFile
    val dlProgressState = remember(item.key) { mutableStateOf(-1f) }
    var dlProgress by dlProgressState
    // 截图用：当前 PlayerView 实例引用（AndroidView factory 写入，onRelease 清空）
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    // 本次全屏是否由"旋转到横屏"自动触发（自动进才自动退；手动全屏不抢退出权）
    val autoFullscreenState = remember(item.key) { mutableStateOf(false) }
    var autoFullscreen by autoFullscreenState
    val gestureScope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // R138：回写决策位/总闸在此现查现读（SharedPreferences 内存哈希读，翻转即时生效，
    // 与网关侧 sendLikeReaction 的"每次调用现查"同一哲学）；实例挂同一 tgfeed.xml
    val appPrefs = remember { dev.tgfeed.core.prefs.AppPrefs(context.applicationContext) }
    // ── R174 捏合清屏（提案 §3）：连续映射 + 状态机 + 三退通道 ─────────────────
    // 共享宿主（VideoPage/PhotoCard 同件，纪律§一收敛）：zoom/offset 手势状态 +
    // 状态机本体 + chrome α 渲染真身 + 复位弹簧，机制说明见 PinchCleanHost.kt；
    // 纯模型/映射曲线/滞回矩阵在 PinchCleanPolicy.kt（单测枚举）
    val pinch = rememberPinchCleanHost(item.key)
    // 双指捏合缩放：0.25x~4x（R174b 自由缩放，去 1x 地板——捏到多大就多大）；
    // 缩放态（≠1x，含 <1x）下单指可平移画面（质心/单指位移跟手）；双击复位
    var zoomScale by pinch.zoomScale
    var zoomOffset by pinch.zoomOffset
    // 首次教学气泡（§3.2）：zoom 首过 1.15 弹一次（AppPrefs 把门，设置页
    // 「重新显示手势操作引导」复位）；2.5s 后淡出。气泡本体在 CoachOverlay.kt
    // 引导体系内（挂现有体系不发明新机制）
    var pinchTeachBubble by remember { mutableStateOf(false) }
    LaunchedEffect(item.key, pinch.teachFired) {
        if (pinch.teachFired && !appPrefs.pinchCleanGuideShown) {
            appPrefs.pinchCleanGuideShown = true
            pinchTeachBubble = true
            delay(2500)
            pinchTeachBubble = false
        }
    }
    // 响应式档位（Responsive.kt）：控件/间距系数走 dp 通道；文本缩放走 sp 通道
    //（TierFontScale 包裹全页下发，只放大 sp 文本，不动 dp 布局与手势热区）
    val windowSize = rememberWindowSize()
    val uiScale = windowSize.uiScale
    val tierDensity = LocalDensity.current.let {
        Density(it.density, it.fontScale * windowSize.fontScale)
    }
    // 智能降档：与 FeedScreen 同款 factory + key 取同一个 activity 作用域 FeedViewModel
    //（key 相同则 viewModel() 直接返回既有实例，factory 不会重建），只读其网络/省电/开关状态。
    // VideoPage 参数表无法再扩（FeedScreen 等调用方本轮冻结），故在此自取
    val feedVm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(
            gateway,
            context.applicationContext as android.app.Application,
        ),
        key = "gw" + System.identityHashCode(gateway),
    )
    val metered by feedVm.networkMetered.collectAsState()
    val batterySaver by feedVm.batterySaver.collectAsState()
    val smartQuality by feedVm.smartQuality.collectAsState()
    // R324 离线降级（播放器面）：NetworkWatch 单源经 VM 镜像——离线终态浮层的
    // 可点重试/稍后再看槽开关（失败浮层调用点消费）
    val networkOnline by feedVm.networkOnline.collectAsState()
    // 画面旋转（右轨旋转钮 / R227 枢纽菜单）：顺时针 90° 步进，0/90/180/270 循环。
    // R122 时代口径 = 页内临时态（remember 挂 item.key，划走归零——用户当年拍板
    // 「不要**频道**记忆」）；R227 按任务书收编为**条目级持久记忆**：旋转与镜像
    // 并入同一 FrameAdjustStore 复合态（mirrored.json 扩展格式，跨会话保持；
    // 枢纽菜单「恢复默认」一键归零）。频道级记忆仍然没有（R122 拍板口径不变）。
    // R122：频道记忆设施（channel_rotation.json / setChannelRotation）零调用死设施，
    // 与"用户拍板不要频道记忆"的现行口径冲突，已整体删除（本注释留作决策锚点）。
    // R218 镜像翻转（每条视频独立记忆，跨会话保持）：与旋转同一 FrameAdjustStore
    // 键级缓存——组合期读快照表即订阅，面板/枢纽调整后画面/封面即时重绘。
    val frameState = FrameAdjustStore.stateOf(context, item.key)
    val rotationDeg = frameState.rotationDeg
    val mirrored = frameState.mirrored
    // 旋转闭环（右轨旋转钮直连：R174 语义不动——+90 步进，姿态源换 store）
    val cycleRotation: () -> Unit = {
        FrameAdjustStore.rotate(context, item.key, +90)
    }
    // ── 自动探测正确旋转姿势的可行性结论（诚实边界）──
    // 做不到可靠自动探测：内容方向与人脸/文字朝向有关，必须跑视觉分析（人脸检测/
    // 文字方向 OCR），本端无此管线。纯元数据启发式已评估，均不可单独作判据：
    //  ① 容器旋转元数据（tkhd matrix / rotate atom）：ExoPlayer 已自动应用
    //    （Format.rotationDegrees 进 videoSize），能拿到的旋转早已转正，
    //    剩下的错向视频恰恰是上传工具剥了元数据的——元数据信道为空；
    //  ② 宽高比 vs 设备方向：竖屏容器里的横屏视频≠错向（大量内容本来就是横的，
    //    本文件 directionMismatch 走 FIT+模糊底衬正是为此），误判率不可接受；
    //  ③ 时长/文件大小与方向无相关性。
    // 若日后要做，可行方案是低置信猜测+可撤销：对"竖屏容器+横屏内容+无旋转元数据"
    // 的条目给一次"是否旋转？"轻提示（不自动转），用户确认后按本次有效处理——
    // 把一次性猜测变成一次确认，误报成本降到最低。
    // 本期只交付手动旋转（R227 起条目级记忆、枢纽一键归零）；频道记忆已按用户拍板删除（见上）。
    // 合集（相册）同组成员 key 表：同 chatId+albumId（albumId≠0 = 一条消息多个媒体）。
    // 订阅 Feed 列表但按"同组 key 表"distinctUntilChanged——列表追加/刷新其他条目
    // 不触发本页重组；组内成员变化（如相册展开补进）才更新标识计数
    val albumGroupKeys by remember(item.key) {
        feedVm.items
            .map { list ->
                if (item.albumId == 0L) emptyList()
                else list.filter { it.chatId == item.chatId && it.albumId == item.albumId }.map { it.key }
            }
            .distinctUntilChanged()
    }.collectAsState(initial = feedVm.albumGroupOf(item).map { it.key })
    // 降级因子生效时目标清晰度降一档（1080→720 / 720→480 / 自动(0)→720）；作用于起播选档与
    // 热切换，功能面板 chips 仍显示用户原设定（qualityHeight 原样透传），
    // 语义="你要的档"与"实际降到的档"分离
    val effectiveQualityHeight = remember(qualityHeight, smartQuality, metered, batterySaver) {
        if (smartQuality && (metered || batterySaver)) degradeQualityHeight(qualityHeight) else qualityHeight
    }
    // ── 清晰度热生效 + 降档可见（GAPS3 #10）────────────────────
    // 本条当前已用于起播的档位 fileId（null = 主档）：档位偏好/降级因子变化时与目标档比对，
    // 不同则换源续播（即切即换，不再"下一条起播才生效"）
    val appliedVariantFileIdState = remember(item.key) { mutableStateOf<Int?>(null) }
    var appliedVariantFileId by appliedVariantFileIdState
    // 热切换标记：true = 本次 playUri 变化由切档触发，装填 effect 换源前取当前位置续播
    val qualityHotSwapState = remember(item.key) { mutableStateOf(false) }
    var qualityHotSwap by qualityHotSwapState
    // 重试换路标记：true = 本次 playUri 重拉由自动重试链发出（区别于滑走滑回的重新起播），
    // 装填 effect 据此对用户手动暂停态做与热切换同款的保护（不偷跑成自动播放）
    val retryReloadState = remember(item.key) { mutableStateOf(false) }
    var retryReload by retryReloadState
    // 智能降档当前是否生效（实际起播档 ≠ 用户设定档）：生效时右上角出"已降档"提示胶囊
    val degradeActive = effectiveQualityHeight != qualityHeight
    var degradeHint by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key, isActive, degradeActive) {
        degradeHint = isActive && degradeActive
        if (degradeHint) {
            // 3 秒淡出：期间降级解除/翻页则由 effect 重启自然收掉
            delay(3000)
            degradeHint = false
        }
    }
    // 手势区宽度（双击分区判定用）
    var boxWidthPx by remember { mutableIntStateOf(0) }
    // 页面容器实测尺寸（onSizeChanged 回写）：视频裁切保护的容器宽高比来源——
    // 平板横屏的限宽列是竖长形（实测 682×812dp），容器方向必须实测，
    // 不能拿屏幕方向冒充（走查 P0-1：16:9 在竖长列被当"同向"裁掉 ~53%）
    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }
    // 播放器上报的真实画面宽高比（onVideoSizeChanged 回写，0=未知）：
    // 方向判定以真实播放画面为准——消息元数据（item.width/height）在旋转标记
    // 缺失/写入不一致的片源上会与实际播放方向相反，按它判定必然裁错（R77）
    val playedVideoRatioState = remember(item.key) { mutableStateOf(0f) }
    var playedVideoRatio by playedVideoRatioState
    // 本页在窗口中的左缘 x（px，onGloballyPositioned 回写）：
    // 全屏破列时据此把内容对齐窗口左缘，实现真正的全屏宽（走查 P1-4）
    var pageLeftInWindowPx by remember { mutableStateOf(0f) }
    // 进度条拖拽状态（ProgressLine 上抛）：非 null = 拖拽中，顶层气泡层据此显隐。
    // 气泡画在页内最顶层（控件层之后），修"拖动时悬停时间气泡被信息区/右轨遮挡"
    var seekDrag by remember(item.key) { mutableStateOf<SeekDragState?>(null) }
    // 拖拽帧预览（R73 缩略图带）——R131 拆至 VideoSeekPreview.kt：
    // 预抽门控（READY 后开抽不抢下载泵）/失焦释放/主路查表+现场抽帧兜底链，说明见该文件
    val seekPreview = rememberVideoSeekPreview(
        item = item,
        isActive = isActive,
        playUri = playUri,
        buffering = buffering,
        seekDrag = seekDrag,
    )
    // 续播提示：起播恢复到上次位置时闪现"已从 X:XX 继续播放"
    var resumeHint by remember(item.key) { mutableStateOf(progressToRestore > 0L) }
    LaunchedEffect(item.key) {
        if (resumeHint) {
            kotlinx.coroutines.delay(2500)
            resumeHint = false
        }
    }
    // 展示期 caption 回填（相册成员 caption 晚到的残余缺口：形态A 带 caption 的成员落在
    // 还没拉到的更旧的页、媒体 tab 锚点预算外等，条目已带空 caption 上屏）：
    // 向网关要一次同组共享/批注 caption，拿到即 patch 回 Feed 列表，信息区即时补全。
    // 请求收敛双闸：LaunchedEffect(item.key) 每条目每次进组合最多触发一次；
    // 网关 peekAlbumCaption 内部先查跨页缓存（免费），miss 的窗口查询按相册 TTL 去重
    LaunchedEffect(item.key) {
        val real = gateway as? TdLibGateway
        if (real != null && item.albumId != 0L && item.caption.isBlank()) {
            val cap = runCatching {
                real.peekAlbumCaption(item.chatId, item.albumId, item.messageId)
            }.getOrNull()
            if (!cap.isNullOrBlank()) feedVm.patchCaption(item.key, cap)
        }
    }

    // 窗口方向与宽（dp）：方向供全屏控制与填充判定共用；宽供全屏破列测量（走查 P1-4）
    val orientation = LocalConfiguration.current.orientation
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    // 横屏全屏与方向控制（R131 拆至 VideoFullscreen.kt；进出闭包经 hooks 交还，口径说明见该文件）
    val fullscreenHooks = VideoFullscreenController(
        context = context,
        feedVm = feedVm,
        item = item,
        isActive = isActive,
        orientation = orientation,
        fullscreenState = fullscreenState,
        autoFullscreenState = autoFullscreenState,
    )

    // §3.2 退通道③兜底：清屏态系统返回键 = 先恢复 chrome（与单击等价，提案 §2.5
    // 「返回=退一层」）。后组合者优先：页内 handler 压过 MainActivity 级双击退出
    // （与 VideoFullscreen 的全屏返回拦截同构；互斥保证两者不会同时 enabled）
    BackHandler(enabled = pinch.cleanMode && !fullscreen) {
        pinch.dispatch(PinchCleanEvent.ExitCleanKeepZoom(zoomScale))
    }
    // §3.5 互斥同步：进全屏自动退清屏（几何层接管）；全屏内 pinch 只缩放不清屏
    // （状态机冻结位，chrome 已全隐、tap 语义不许被清屏翻转）
    LaunchedEffect(item.key, fullscreen) {
        pinch.dispatch(PinchCleanEvent.FullscreenChanged(fullscreen))
    }

    // 下载结果 Toast：只在状态“迁移”到 Done/Failed 时提示，重组不重复弹
    var lastDownloadStatus by remember(item.key) { mutableStateOf(downloadStatus) }
    LaunchedEffect(downloadStatus) {
        val current = downloadStatus
        if (current != lastDownloadStatus) {
            when (current) {
                is DownloadStatus.Done ->
                    Toast.makeText(context, "已保存到相册 Movies/TGFeed", Toast.LENGTH_SHORT).show()
                is DownloadStatus.Failed ->
                    Toast.makeText(context, "下载失败：${current.message}", Toast.LENGTH_LONG).show()
                else -> Unit
            }
            lastDownloadStatus = current
        }
    }

    // 封面占位：先秒出 minithumbnail（即时但糊），再异步换高清缩略图；
    // 连缩略图都没有的条目（coverFileId<0）：播过一次后从本地视频抽帧当封面
    //（CoverStore 共享，Feed/分屏/搜索各处都受益）
    // R170 解码挪 IO（榜单 #6 输入响应家族）：produceState 协程挂在组合作用域=
    // 主调度器上，高清缩略图（可达数百 KB）在主线程 decodeByteArray 是快甩翻页
    // 每页组合时的主线程负载贡献者；网关侧读盘已同步剥离（GatewayCover
    // readCoverFileBytesOnIO），这里把解码也统一挪 IO
    val cover: ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, item.key) {
        // 抽帧封面优先（之前播过已抽好），再 minithumbnail，再高清缩略图
        CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            value = item.coverBytes?.let { bytes ->
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
        if (item.coverFileId >= 0) {
            val lib = gateway as? TdLibGateway
            val bytes = lib?.loadCoverBytes(item)
            if (bytes != null) {
                value = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.asImageBitmap()
                }.getOrNull() ?: value
            }
        }
    }.value

    // ── R159 内容感知列宽：宽高比上报链路（外层据此现算列宽）──────────────────
    // ①封面代理（仅元数据缺尺寸时）：minithumbnail 与原片同比例，封面一到即上报，
    //   加载态列宽就先放宽（用户实测抱怨的正是加载态窄列）；封面换高清后比例不变
    //   不重复上报（同为预判级，后报不覆盖先报无意义）
    LaunchedEffect(item.key, cover) {
        if (item.width <= 0 || item.height <= 0) {
            val c = cover ?: return@LaunchedEffect
            if (c.width > 0 && c.height > 0) {
                onVideoAspectChange(c.width.toFloat() / c.height, false)
            }
        }
    }
    // ②真实画面比校正：onVideoSizeChanged → playedVideoRatio（含旋转/像素宽高比修正），
    //   起播后一跳到位；外层按"与预判差是否 ≥15%"决定维持预判（不跳变）还是换目标
    //   （300ms 动画过渡）。翻回已播过的条目时本状态随 remember(item.key) 归零，
    //   但外层的记账表仍留着实测值，列宽仍正确。
    // R159b 起播一拍（用户第三时序实锤「起播跳宽」）：上报冻结到首帧上屏后——
    // 实测比一旦入表，偏差 ≥15% 的换目标即刻生效，校正 tween 会压在首帧上屏
    // 窗口内（onVideoSizeChanged 只比首帧早 1~3 帧，链路重组延迟下 tween 起跳与
    // 占位→画面同窗）。冻结期间列宽维持预判目标（加载态布局原样过交界），
    // onRenderedFirstFrame 之后才释放，走外层既有 300ms tween（与翻页换形态
    // 同一动画口径）。
    // 注意：useZoom/方向判定不受本冻结影响——PlayerView 的 resizeMode 必须在
    // 首帧渲染前落位（否则首帧按旧模式裁完再跳正），方向链保持「首帧前就位」
    // 是刻意分野（见 PlaybackStartPolicy 头注释）。重新装填/换源时 firstFrameSeen
    // 复位（VideoLoad R93 口径），冻结闸随新源的起拍重新武装。
    LaunchedEffect(item.key, playedVideoRatio, firstFrameSeen) {
        if (playedVideoRatio > 0f &&
            dev.tgfeed.ui.PlaybackStartPolicy.shouldReleaseMeasuredAspect(firstFrameSeen)
        ) {
            android.util.Log.i(
                "R159",
                "起播校正释放 key=…${item.key.takeLast(12)} ratio=" +
                    "%.3f".format(playedVideoRatio) + "（首帧后；预判偏差≥15% 才换目标）",
            )
            onVideoAspectChange(playedVideoRatio, true)
        }
    }

    // R89 动态模糊底衬：播放中按当前位置从 R73 缩略图带查到的最近邻预抽帧（底衬用），
    // 实时跟随画面且零回读，优先于封面。起播缩略图带未就绪时为 null，外层底衬回落封面
    //（见下方 onBackdropChange 上报）
    var liveBackdrop by remember(item.key) { mutableStateOf<ImageBitmap?>(null) }

    // 真实 TDLib 条目首次激活时触发下载；Mock 条目自带 playUri。
    // 清晰度选择：effectiveQualityHeight 非 0 时按"就近匹配"选档（pickVariantByHeight：
    // ≤目标档的最高档，目标低于全部档位时取最低档），改走该档 fileId 的 ensurePlayableVariant；
    // 无备选档 / 自动档 / Mock（变体 fileId=-1）回落主档 ensurePlayable。
    // 智能降档：effectiveQualityHeight = 用户偏好 qualityHeight 经降级因子（计量/省电）降一档后的值。
    // 热生效（GAPS3 #10）：effectiveQualityHeight 入 key——用户切档或降级因子翻转时本 effect
    // 重跑，正在播放的条目也按新档 fileId 重新 ensurePlayable 换源，续播到当前位置
    // （装填 effect 见 qualityHotSwap 分支），不再"下一条起播才生效"
    LaunchedEffect(isActive, item.key, retryTick, effectiveQualityHeight) {
        if (!isActive) return@LaunchedEffect
        val lib = gateway as? TdLibGateway
        // 换路副本：无重试时即原条目；自动重试链已剥掉坏地址/换发新 fileId 时，
        // 这里拿到的就是换路后的条目（retryItem 由重试链先写好再 bump retryTick，
        // 本 effect 因 retryTick 重启时总能读到最新值）
        val base = retryItem
        val pickedVariant = if (effectiveQualityHeight > 0) {
            pickVariantByHeight(base.variants, effectiveQualityHeight)
        } else {
            null
        }
        val variantFileId = pickedVariant?.fileId
        if (pickedVariant != null && variantFileId != null && variantFileId >= 0) {
            android.util.Log.d(
                "VideoPage",
                "清晰度选档：目标 ${effectiveQualityHeight}p → 就近命中 ${pickedVariant.height}p" +
                    "（fileId=$variantFileId，主档高=${base.height}）",
            )
        }
        if (playUri == null) {
            // 首次起播（含自动重试链换路后的重拉）
            playUri = runCatching {
                if (lib != null && variantFileId != null && variantFileId >= 0) {
                    lib.ensurePlayableVariant(base, variantFileId)
                } else {
                    gateway.ensurePlayable(base)
                }
            }.getOrNull()
            appliedVariantFileId = variantFileId
        } else if (variantFileId != appliedVariantFileId) {
            // 档位热切换：目标档与当前起播档不同才换源（同档位的重组零代价跳过）
            val newUri = runCatching {
                if (lib != null && variantFileId != null && variantFileId >= 0) {
                    lib.ensurePlayableVariant(base, variantFileId)
                } else {
                    gateway.ensurePlayable(base)
                }
            }.getOrNull()
            appliedVariantFileId = variantFileId
            // 地址没变（如主档已下载完成、各档回落同一路径）则不动播放器
            if (newUri != null && newUri != playUri) {
                qualityHotSwap = true
                playUri = newUri
            }
        }
    }

    // 无封面条目（minithumbnail/缩略图都没有）的补救：本地文件就绪后抽 1 秒处的帧
    // 当封面，进 CoverStore 共享表（Feed/分屏/搜索的封面位都自动换上）
    LaunchedEffect(item.key, playUri) {
        if (item.coverBytes == null && item.coverFileId < 0) {
            val path = playUri ?: return@LaunchedEffect
            if (path.startsWith("http")) return@LaunchedEffect // 远端流读不了本地文件
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.tgfeed.core.playback.CoverExtractor.getOrExtract(item.key, path, context.cacheDir)
            }
            if (bmp != null) CoverStore.put(item.key, bmp.asImageBitmap()) // R155：走上限保护入口
        }
    }

    // 起播等待/缓冲的下载进度轮询：仅真实 TDLib 条目（fileId>=0）；
    // Mock（fileId<0）与非 TdLibGateway 不启动，dlProgress 保持 -1，UI 不显示百分比。
    // 流式播放期间后台整体下载仍在推进，buffering 时同样可见进度；
    // 下载完成 flow 自动 complete，dlProgress 停在 1f 后被 UI 的 <1f 条件隐藏。
    // R80：进度跟踪以实际播放的文件为准——验身换发后播的是新 fileId，老 id 可能
    // 指向已完成的别的文件（进度恒 1f，看门狗误以为"已下完在等解码"而装瞎，
    // 慢网卡死时永远不进重试链）。流地址里带真实 fileId，优先从地址解析
    LaunchedEffect(item.key, isActive, playUri) {
        dlProgress = -1f
        val lib = gateway as? TdLibGateway ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        val effectiveId = playUri
            ?.let { Regex("""^http://127\.0\.0\.1:\d+/v/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: item.fileId
        if (effectiveId < 0) return@LaunchedEffect
        lib.downloadProgressFlow(effectiveId).collect { dlProgress = it }
    }

    val player: ExoPlayer? = if (isActive && playUri != null) playerPool.acquire(item.key) else null

    // 装填链（R131 拆至 VideoLoad.kt；热切换续播/暂停尊重/片尾钳制/IDLE 重 prepare 口径见该文件）
    VideoLoadEffect(
        player = player,
        item = item,
        feedVm = feedVm,
        playUriState = playUriState,
        loadFailedState = loadFailedState,
        qualityHotSwapState = qualityHotSwapState,
        retryReloadState = retryReloadState,
        pausedState = pausedState,
        firstFrameSeenState = firstFrameSeenState,
    )

    // 加载看门狗 + 自动退避重试链（R131 拆至 VideoRetryChain.kt；判死条件/换路双策略/自愈守卫说明见该文件）
    VideoRetryChainEffects(
        player = player,
        item = item,
        gateway = gateway,
        isActive = isActive,
        playUriState = playUriState,
        bufferingState = bufferingState,
        loadFailedState = loadFailedState,
        failCountState = failCountState,
        retryPendingState = retryPendingState,
        fatalReasonState = fatalReasonState,
        retryItemState = retryItemState,
        retryReloadState = retryReloadState,
        retryTickState = retryTickState,
        dlProgressState = dlProgressState,
    )

    // 静音 / 倍速即时生效（含翻页复用 player 后重新应用；长按快进/锁定时压到 2x）
    // R190：加速切换时刻留 boostToggle 取证（目标速度+当前缓冲水位+播放态）——
    // 真机前后对照的核心口径：切换瞬间水位不回零=零重装填实锤；卡顿时刻水位
    // 走势=垫被 2x 消耗抽干的实锤（嫌疑链分诊见 r190_notes）
    LaunchedEffect(player, muted, speed, longPressBoost) {
        player?.let {
            it.volume = if (muted) 0f else 1f
            val target = if (longPressBoost) 2f else speed
            it.setPlaybackSpeed(target)
            if (target > 1f) {
                dev.tgfeed.core.playback.PlaybackTrace.log(
                    "boostToggle", item.key.takeLast(8),
                    "speed=$target 水位=${runCatching { it.bufferedPosition }.getOrDefault(-1L)}ms 播放=${it.isPlaying}",
                )
            }
        }
    }

    // 划出视野但仍在 pager 缓存内时暂停，避免出声；同时兜底退出长按快进/连续步进态与横屏全屏
    LaunchedEffect(isActive) {
        if (!isActive) {
            longPressBoost = false
            boostLocked = false
            // R174 滑走复位（§3.2 滑走复位行：下一条是干净的新开始）：zoom + 清屏态
            // + α 全清（抖音同款：切走再回来是 1x 原始画面，不带上次残留的缩放/平移）
            pinch.resetForPageLeft()
            continuousSeekJob?.cancel()
            continuousSeekJob = null
            continuousSeek = false
            autoFullscreen = false
            if (fullscreen) fullscreenHooks.exit() // 滑走时退出全屏（方向跟随系统，不锁竖屏）
            // 续播：离开时上报当前位置。注意不能用本组合里的 player——
            // 失焦重组时它被求值为 null（acquire 条件含 isActive），保存会静默丢失。
            // R112：翻页收敛的 releaseAllExcept 先于本 effect 执行（父级 effect 先跑），
            // busy 实例已被摘除、纯 peek 必扑空——peekPosition 回落池内"释放前记账"的
            // 位置快照（先记账后释放），翻走再翻回仍能从上次位置续播
            playerPool.peekPosition(item.key)?.let { onSaveProgress(item, it) }
            playerPool.pause(item.key)
        }
    }

    // 循环单条：loopOne 是 Feed 级共享状态，listener 闭包用 rememberUpdatedState 读最新值，
    // 避免 player 复用时抓到旧值（State 对象同时供拆出的 VideoSeekSignalEffect 现读）
    val loopOneNowState = rememberUpdatedState(loopOne)
    val loopOneNow by loopOneNowState
    // 同理：条目本体也现读（列表刷新可能换同 key 新实例；重试链痊愈复位时以最新条目为准）
    val itemNowState = rememberUpdatedState(item)
    val itemNow by itemNowState

    // P1.5 #2 拖拽位置信号采集（R131 拆至 VideoSeekOverlay.kt；轮询/阈值/防抖口径说明见该文件）
    VideoSeekSignalEffect(
        player = player,
        isActive = isActive,
        item = item,
        loopOneNowState = loopOneNowState,
        feedVm = feedVm,
    )
    // R204 TL-7 本机回放热力（key 级精确层）：本条近 7 天的拖拽落点分布 → 进度条刻度墙。
    // 纯内存聚合（InteractionStats 锁内一遍扫描，微秒级）直取即可；样本 <3 返回 null
    // 不渲染（宁缺勿假）；拖完一条的热力下一次进页才体现（信号落点已入环形缓冲）
    val seekHeat by androidx.compose.runtime.produceState(
        initialValue = null as FloatArray?,
        key1 = item.key,
    ) {
        value = runCatching { feedVm.seekHeatForKey(item.key) }.getOrNull()
    }
    // 播放器监听（R131 拆至 VideoPlayerListener.kt；状态迁移自愈/错误分诊/换源重下/首帧与尺寸上报口径见该文件）
    VideoPlayerListenerEffect(
        player = player,
        gateway = gateway,
        feedVm = feedVm,
        itemNowState = itemNowState,
        loopOneNowState = loopOneNowState,
        onEnded = onEnded,
        gestureScope = gestureScope,
        bufferingState = bufferingState,
        loadFailedState = loadFailedState,
        failCountState = failCountState,
        retryPendingState = retryPendingState,
        fatalReasonState = fatalReasonState,
        retryItemState = retryItemState,
        pausedState = pausedState,
        corruptRetryCountState = corruptRetryCountState,
        playUriState = playUriState,
        retryTickState = retryTickState,
        playedVideoRatioState = playedVideoRatioState,
        firstFrameSeenState = firstFrameSeenState,
        appliedVariantFileIdState = appliedVariantFileIdState,
    )

    DisposableEffect(item.key) {
        onDispose {
            // R80 埋点：切页/销毁取消现场（播放到哪、当时是否在缓冲）
            dev.tgfeed.core.playback.PlaybackTrace.log("gone", item.key, "销毁取消")
            // 续播：销毁时兜底上报位置。不能用闭包捕获的 player——
            // 本 effect 以 item.key 为 key 不随重组重启，捕获的是首次组合的 player
            //（真实条目 playUri 异步就绪，首次组合恒为 null）——按 key 现查池实例；
            // R112：此刻播放器通常已被翻页收敛/淘汰摘除，peekPosition 回落释放前
            // 记账的位置快照，兜底不丢（幂等：与失焦分支同值重复保存无副作用）
            playerPool.peekPosition(item.key)?.let { onSaveProgress(item, it) }
            playerPool.release(item.key)
        }
    }

    // 当前帧提供者注册（分享卡片抓帧用）：激活页且播放中就注册；离开即注销
    val container = (context.applicationContext as? dev.tgfeed.App)?.container
    DisposableEffect(player, isActive) {
        if (player != null && isActive && container != null) {
            container.currentFrameProvider = {
                (playerViewRef?.videoSurfaceView as? TextureView)?.bitmap
            }
        }
        onDispose {
            if (container?.currentFrameProvider != null) container.currentFrameProvider = null
        }
    }

    // 定时关闭到点：暂停 + 中央提示（LaunchedEffect 随 player/sleepNow 重建，自动换流重挂）
    LaunchedEffect(player, sleepNow) {
        val flow = sleepNow ?: return@LaunchedEffect
        flow.collect {
            player?.pause()
            paused = true
            sleepPausedHint = true
        }
    }

    // 单击：清屏态先恢复浮层；2x 锁定态用于解锁；否则暂停/继续
    // 用 rememberUpdatedState 避免 pointerInput 抓住旧闭包
    val currentOnTap = rememberUpdatedState<() -> Unit>(newValue = {
        // R138：引导气泡随任意点按顺手收起（不打断原有动作；8s 超时为兜底）
        if (likeGuideAnchor != null) likeGuideAnchor = null
        when {
            // §3.2 退通道①：清屏态单击 = 恢复 chrome、缩放保留（用户可能还在看局部）
            pinch.cleanMode -> pinch.dispatch(PinchCleanEvent.ExitCleanKeepZoom(zoomScale))
            boostLocked -> {
                boostLocked = false
                longPressBoost = false
            }
            else -> {
                val p = player
                if (p != null) {
                    // R375：判定换 playWhenReady 意图口径（PlaybackTogglePolicy 单源）——
                    // 缓冲中 isPlaying=false 但用户语义上是"在播"（用户实锤「正在加载
                    // 缓冲时单击暂停/播放不生效，要等完全加载成功才能暂停」的病根：旧
                    // isPlaying 口径把缓冲期点按判成"没在播"→play() 落在已是 true 的
                    // 播放意图上零变化＝不生效）。ENDED 例外在策略内：播完态不算在播，
                    // 点按照旧落入下方"先归零再 play"重播链（行为逐字不变）。
                    if (PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, p.playbackState)) {
                        p.pause()
                        paused = true
                    } else {
                        // 停在片尾（ENDED）时点按 = 从头重播：play() 对 ENDED 态无效，
                        // 必须先归零（回看豁免定格后的重播入口也走这里）
                        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                        p.play()
                        paused = false
                        flashResume = true
                    }
                }
            }
        }
    })
    // pointerInput 块只在 item.key 变化时重跑，里面的长按闭包会抓住旧 player 引用
    // （首帧 playUri 未就绪时 player=null，之后 acquire 成功闭包也不会更新）——
    // 连续步进协程必须经 rememberUpdatedState 读最新 player
    val playerNow by rememberUpdatedState(player)
    // 双击分区：缩放态（R174b 起 ≠1x，含 <1x）下任意位置双击 = 复位缩放（优先于
    // 点赞/快进退）；否则左右 25% = 快退/快进 10s；中间 = 点赞飘心（未赞才赞，已赞只飘心）
    var seekHint by remember(item.key) { mutableStateOf<String?>(null) }
    val currentOnDoubleTap = rememberUpdatedState<(Offset) -> Unit>(newValue = { offset ->
        if (zoomScale != 1f) {
            // 缩放态下双击 = 复位缩放 + chrome 全恢复（§3.2 退通道②「彻底复位」）：
            // zoom 走复位弹簧（0.82/500，<1x 起点同弹回），α 走 chrome 进出 220ms，两线并行
            pinch.dispatch(PinchCleanEvent.FullReset)
            pinch.launchZoomResetSpring()
        } else {
        val w = boxWidthPx
        when {
            w > 0 && offset.x < w * 0.25f -> {
                player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
                seekHint = "-10s"
            }
            w > 0 && offset.x > w * 0.75f -> {
                player?.let {
                    val dur = it.duration
                    val target = it.currentPosition + 10_000
                    it.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                }
                seekHint = "+10s"
            }
            else -> {
                if (!liked) {
                    onToggleLike()
                    // R128 点赞回写原频道 Reaction：仅"本次双击真的点了赞"（未赞→赞）才触发，
                    // 已赞双击只飘心不重复回写；取消点赞不回撤（任务口径）。
                    // R138 起改经 LikeWritebackBoard 派发（状态可查/送达可见）：网关侧
                    // 总开关闸 + 同一条幂等 + 失败静默三重口径零改动，只是把裸发的一发收进观测。
                    // scope 用 activity 级 VM（不是页面 gestureScope）：点赞后立刻划页时
                    // 页面协程已随组合销毁，回写不该陪葬（实测实锤，见 dispatch 注释）
                    LikeWritebackBoard.dispatch(gateway, feedVm.viewModelScope, item, appPrefs)
                    // R138 首次场景化引导：回写决策未做且总闸未开时，第一次双击点赞
                    // 就在落点弹一次性轻引导（知情同意放行为现场，不放设置页灰字）。
                    // 会话内只展示一次：不作选择不反复打扰（仍可在设置页/长按状态面板决定）
                    if (!appPrefs.likeWritebackPromptDecided && !appPrefs.likeReactionWriteback &&
                        !LikeWritebackBoard.guideShownThisSession
                    ) {
                        LikeWritebackBoard.guideShownThisSession = true
                        likeGuideAnchor = offset
                    }
                }
                // 右侧点赞钮同步弹簧弹跳（抖音：双击点赞时心形会弹一下）
                likePulse++
                // 抖音式爆心：双击处爆出 3-5 颗心，随机大小/倾角/横漂/上浮高度/时长，
                // 各自错开消散（分布口径 R138 收编进 VideoHearts.likeBurstParticles）。
                // 回写开启且本次真的点了赞：陪衬心里混 1-2 颗 👍 emoji 粒子，区别纯本地赞
                hearts = hearts + likeBurstParticles(
                    x = offset.x,
                    y = offset.y,
                    writebackOn = !liked && appPrefs.likeReactionWriteback,
                )
            }
        }
        }
    })
    // ±10s 提示闪现 700ms
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    // ⏸ 闪现 600ms 后自动消失
    LaunchedEffect(flashResume) {
        if (flashResume) {
            delay(600)
            flashResume = false
        }
    }

    // 清屏/全屏状态上报外层（隐藏顶栏等共享浮层）。
    // R174：chrome α 连续上抛——顶栏跟手渐隐（外层 FeedScreen 以 graphicsLayer α
    // 承接；α=0 时 cleanMode 必已置位，走旧 hidden 语义整块退组合，兼容不变）。
    // isActive 入键：翻页落定即时重报当前值（snapshotFlow 初值自发）——修「滑走
    // 后顶栏卡在隐藏」的陈旧残留（旧实现仅布尔跳变触发、落定页无跳变即沉默，
    // 与滑走复位双保险）
    val onOverlayStateChangeNow = rememberUpdatedState(onOverlayStateChange)
    LaunchedEffect(item.key, isActive) {
        snapshotFlow { pinch.chromeAlpha.value to (pinch.cleanMode || fullscreen) }
            .collect { (alpha, hidden) -> onOverlayStateChangeNow.value(hidden, alpha) }
    }

    // 定时关闭提示 2.6s 后自动消失
    LaunchedEffect(sleepPausedHint) {
        if (sleepPausedHint) {
            delay(2600)
            sleepPausedHint = false
        }
    }

    // ── R138 点赞回写反馈的时序 ──
    // 送达胶囊：观测板状态"跃迁进"已送达才闪现（划回已送达的旧页不重弹，
    // 只有本次真的完成一次回写才感知）
    LaunchedEffect(item.key) {
        var last = likeWritebackState.value
        snapshotFlow { likeWritebackState.value }.collect { s ->
            if (last != LikeWritebackStatus.DELIVERED && s == LikeWritebackStatus.DELIVERED) {
                writebackSyncedPill = true
            }
            last = s
        }
    }
    // 送达胶囊 1.8s 后自动消失
    LaunchedEffect(writebackSyncedPill) {
        if (writebackSyncedPill) {
            delay(1800)
            writebackSyncedPill = false
        }
    }
    // 引导气泡 8 秒未选择自动收起：不替用户做决定（决策位不落盘），
    // 下次首次双击点赞再现；划页由 anchor 的 item.key 记忆自然清零
    LaunchedEffect(likeGuideAnchor) {
        if (likeGuideAnchor != null) {
            delay(8000)
            likeGuideAnchor = null
        }
    }

    // 截图链（R131 拆至 VideoSnapshot.kt）：TextureView 直取 / SurfaceView PixelCopy，
    // 存相册 Movies/TGFeed；当前 PlayerView 引用经 provider 现读
    val captureFrame = videoFrameCapture(
        context = context,
        scope = gestureScope,
        chatId = item.chatId,
        messageId = item.messageId,
        playerViewProvider = { playerViewRef },
    )

    // 填充判定（单一事实来源）：同向填满 Crop；异向 FIT+模糊底衬。
    // 异向裁剪会把短边方向上的内容裁没（用户实测"显示不全"），
    // 异向 FIT + 底衬 = 内容完整可见 + 没有黑边。
    // 底衬由外层（FeedScreen）全屏铺：平板内容区限宽居中，页内铺底衬
    // 只能盖住限宽列、区外还是死黑（"三层配色"就是这么来的）——改由外层铺
    val videoKnown = item.width > 0 && item.height > 0
    // 容器方向/宽高比一律按页面实测尺寸判定，不再拿屏幕方向当容器方向：
    // 平板横屏的限宽列是竖长形（实测 682×812dp，容器比 0.84），屏幕横屏≠容器横屏，
    // 旧口径把 16:9 误判"同向"走 ZOOM 左右裁掉 ~53%（走查 P0-1 实锤）。
    // 首帧 onSizeChanged 未回（容器未知）时退回屏幕方向旧口径，避免口径闪变
    val containerKnown = containerSizePx.width > 0 && containerSizePx.height > 0
    val containerRatio = if (containerKnown) {
        containerSizePx.width.toFloat() / containerSizePx.height.toFloat()
    } else {
        0f
    }
    val containerLandscape = if (containerKnown) {
        containerRatio > 1f
    } else {
        orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    // 手动旋转 90°/270°（奇数档）时宽高互换：resizeMode 一律 FIT 装帧——
    // 填满由画面层 graphicsLayer 的等比放大完成（见下）；若让 ZOOM 先按未旋转
    // 宽高比裁一轮、旋转后再放大，等于双重裁切，内容直接被裁没
    val rotatedOdd = rotationDeg % 180 != 0
    // 视频方向：以播放器上报的真实画面比为准（旋转标记缺失/元数据错向的片源
    // 也能判对）；尺寸未上报时退回消息元数据；两者皆无（未知）不判异向。
    // 旧实现只信元数据：错向片源方向判反 → directionMismatch 漏判 → ZOOM 裁切
    val videoLandscape = when {
        playedVideoRatio > 0f -> playedVideoRatio > 1f
        videoKnown -> item.width > item.height
        else -> false
    }
    val videoDirectionKnown = playedVideoRatio > 0f || videoKnown
    val directionMismatch = videoDirectionKnown && videoLandscape != containerLandscape
    // 横屏容器一律 FIT（R50 用户拍板：横屏下留黑边可接受、裁切不可接受）——
    // 旧实现给横容器留了 ±8% 容差带放行 ZOOM：16:9(1.778) 进 600dp×360dp(1.667)
    // 横屏列失配 6.6% 落在带内走 ZOOM，按列高铺满后 TextureView 越列绘制、
    // 盖住两侧各 80px 遮边（视频破列满屏，走查实锤）；16:10~1.667 档更被真裁
    // 上下 ≤8%。横屏容器即 FIT：横屏视频按短边 fit-center、竖屏视频按高适配，
    // 留白由外层全屏模糊底衬填掉。竖屏容器维持抖音式 ZOOM 铺满（既定观感不动）
    val landscapeContainer = containerKnown && containerRatio > 1f
    // R77 根治：设备横屏即禁用 ZOOM——R50 只看了容器形态，平板横屏的限宽列
    // 是竖长形（实测 682×812dp，容器比 0.84<1），landscapeContainer 永不命中，
    // 列内 ZOOM 把横屏片左右、竖屏片上下裁没（用户实测"加载中不裁、播放后才裁"
    // 即此：封面占位层恒 Fit，播放层走 useZoom，两条链口径不一）。
    // 设备横屏/横屏容器任一成立都 FIT，任何片源、加载/播放任何阶段不裁
    val deviceLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
    val useZoom = fillToScreen && !fullscreen && !deviceLandscape &&
        !landscapeContainer && !directionMismatch && !rotatedOdd
    // R174b 自由缩放：zoom<1（视频缩到窗口中间）——布尔翻转驱动底衬/页背景
    // 接线（derivedStateOf 去抖：逐帧 zoom 写入只在跨 1.0 翻转时触发重组）
    val zoomedOut by remember(item.key) {
        derivedStateOf { pinch.zoomScale.value < 1f }
    }
    // 根背景：异向/FIT 时透明——外层的全屏模糊底衬要能透上来；
    // 同向填满（ZOOM）时保持黑底（视频铺满，底色不可见）。
    // R174b：<1x 缩小态同样透明（留边区透底衬；跨沿瞬间留边面积≈0 无观感跳变）。
    // R160 加载态一次成型：删去"未落定（isActive=false）强制黑底"分支——旧黑底是给
    // "落定前外层底衬还是上一页旧图"打的补丁（灰底透出），但代价是翻页间隙整列黑盒
    // （用户实测①：先出一条黑竖条再分层亮起）。现在外层底衬在翻页过半（isCurrent）
    // 即换成本页封面，进场的透明页透出的底衬恒与本页同图（过半前是旧页图=与旧页自洽，
    // 过半后=本页图），黑盒无从出现；竖屏 ZOOM 模式视频铺满列、黑底本就不可见，不受影响。
    // 规则矩阵见 BackdropPolicy.isVideoPageBackgroundOpaque（单测枚举）。
    val pageBg = if (dev.tgfeed.ui.BackdropPolicy.isVideoPageBackgroundOpaque(useZoom, zoomedOut)) {
        Color.Black
    } else {
        Color.Transparent
    }
    // ── R194⑥ 悬停式顶锚（架构师 17:10 布局终案，取代 R188c 底锚）：媒体带顶缘
    //    贴 tab 条小间距、底缘悬停 dock 上方 ~12dp（不与三导航钮重叠）、富余
    //    全部进中（视频下缘↔说明区之间的自然留白；说明区+进度条照旧贴底）。
    //    锚比链与 R159 列宽上报同源：实测画面比（起播校正）→ 元数据预判 →
    //    封面代理；封面层另按位图自身比单独算（每层锚自己的顶，首帧交接顶缘不跳）。
    val videoAnchorRatio = when {
        playedVideoRatio > 0f -> playedVideoRatio
        videoKnown -> item.width.toFloat() / item.height
        cover != null && cover.width > 0 && cover.height > 0 ->
            cover.width.toFloat() / cover.height
        else -> 0f
    }
    // R194⑥ 悬停式顶锚（架构师 17:10 布局终案）：视频顶缘贴 tab 条小间距、底缘
    // 悬停 dock 上方 ~12dp（画面与三导航钮不重叠——R188c 底锚把底缘压进透明
    // dock 后面=用户「视频已完全融入底部三导航按钮里」实锤的根因）、富余进中
    //（带下缘↔说明区之间的自然留白，说明区+进度条照旧贴底）。纯函数
    // FitBottomAnchor.fitHoverAnchorShiftPx（单测枚举）；视频层与封面层各按自身比
    // 锚定（R188「每层贴自己的底」parity 沿用为「每层锚自己的顶」，首帧交接
    // 顶缘不跳）。全屏态 chrome 全退 → 预留归零 = 居中；竖容器/比未知 → 0 居中
    //（红线零回归，见纯函数 KDoc）。
    val mediaDensity = LocalDensity.current
    val mediaBottomReservePx = if (fullscreen) 0f else with(mediaDensity) {
        WindowInsets.navigationBars.getBottom(this).toFloat() +
            LocalBottomBarInset.current.toPx() + 12.dp.toPx()
    }
    val mediaTopReservePx = if (fullscreen) 0f else topChromeBottomPx + with(mediaDensity) { 12.dp.toPx() }
    // 全屏态 chrome 全退 → 恒 0 = 居中（immersive；预留归零会让顶锚贴屏顶，故门控）
    val videoBottomShiftPx = if (rotatedOdd || fullscreen) 0f else
        dev.tgfeed.ui.FitBottomAnchor.fitHoverAnchorShiftPx(
            containerSizePx.width, containerSizePx.height, videoAnchorRatio,
            mediaTopReservePx, mediaBottomReservePx,
        )
    val coverBottomShiftPx = if (rotatedOdd || fullscreen || cover == null) 0f else
        dev.tgfeed.ui.FitBottomAnchor.fitHoverAnchorShiftPx(
            containerSizePx.width, containerSizePx.height,
            cover.width.toFloat() / cover.height,
            mediaTopReservePx, mediaBottomReservePx,
        )
    // R188c 探针（R174 验证范式）：下锚平移落值——键挂 shift 值本身（列宽 tween
    // 逐值落线、全屏/旋转等容器变化即时重打），含容器实测、锚比、全屏态与首帧
    // 态；真机 logcat 直证（透明 dock 后的像素层无法盲测带区——dock 图标行恒有
    // 锐边，探针是本刀的机检证据通道）。键不含 firstFrameSeen：首帧前后由
    // shift 值变化自然带出（实测比到达即落线）
    LaunchedEffect(item.key, fullscreen, videoBottomShiftPx, coverBottomShiftPx) {
        android.util.Log.i(
            "R188c",
            "下锚 shift=%.1f coverShift=%.1f 容器=%dx%d 锚比=%.3f 全屏=%b 首帧=%b".format(
                videoBottomShiftPx, coverBottomShiftPx,
                containerSizePx.width, containerSizePx.height,
                videoAnchorRatio, fullscreen, firstFrameSeen,
            ),
        )
    }
    // R194③ 内容带/基点现读 State：pointerInput 块实例不随重组刷新（键未变时
    // 闭包捕获的本地 val 会陈旧——锚比在首帧校正、封面到货、列宽 tween 中变化），
    // rememberUpdatedState 引用稳定、手势事件期读恒新
    val pinchAspectInput by androidx.compose.runtime.rememberUpdatedState(
        when {
            useZoom || rotatedOdd -> null
            videoAnchorRatio > 0f -> videoAnchorRatio
            else -> null
        },
    )
    val pinchPanBase by androidx.compose.runtime.rememberUpdatedState(
        Offset(0f, videoBottomShiftPx),
    )
    // 异向/FIT 时上报底衬给外层全屏铺；同向填满时不上报。
    // R174b：填满页缩到 <1x 时改按 FIT 同口径上报（「周围透底衬」；zoomedOut 入键
    // ——跨 1.0 翻转才重跑，回到 ≥1x 恢复 CLEAR_NULL，视频重新铺满、底衬退场）。
    // R89：底衬优先用缩略图带按当前位置查到的预抽帧（实时跟随画面，零回读零卡顿），
    // 带未就绪时回落封面。
    // R160 加载态一次成型：上报条件改挂 isCurrent（=翻页过半）——新页封面在拖拽过半
    // 的瞬间就上报换主，与列内封面占位同帧登场，外层底衬不再等落定+300ms 渐变才到
    // （用户实测③"框外背景图最后才到"修复）。
    // R160 二修（反向翻页竞态根治）：上报/采纳双双收窄到"仅当前页"——离开中的页
    // （isActive 尚真但 isCurrent 已假）不再重发旧封面。一修版曾让 (isActive||isCurrent)
    // 上报 + 外层 settledPage 子句放行：反向翻页过半时新页刚换主、1ms 后就被旧页的
    // isActive 重发夺回、落定才纠正（r160_verify3.mp4 日志实锤）。现在离开页的 null
    // 上报被外层 currentPage 守卫恒挡，竞态无从发生。
    // 无封面时不报：外层保留上一条底衬（模糊近似色），不许清成黑窗；
    // ZOOM/手动旋转的当前页显式报 null（清屏黑边，与旧语义一致——竖屏 ZOOM 视频
    // 铺满列、底衬本就不可见）。规则矩阵见 BackdropPolicy（单测枚举）。
    LaunchedEffect(useZoom, cover, liveBackdrop, item.key, rotatedOdd, isCurrent, zoomedOut, backdropReportGen) {
        val frame = liveBackdrop ?: cover
        when (dev.tgfeed.ui.BackdropPolicy.backdropReportAction(isCurrent, useZoom, rotatedOdd, frame != null, zoomedOut)) {
            dev.tgfeed.ui.BackdropPolicy.BackdropReportAction.QUIET_NULL,
            dev.tgfeed.ui.BackdropPolicy.BackdropReportAction.CLEAR_NULL,
            -> onBackdropChange(null, false)
            // R159b：随帧带 live 实帧标记——外层把本条 live 制度首拍并入同帧 snap
            dev.tgfeed.ui.BackdropPolicy.BackdropReportAction.REPORT ->
                onBackdropChange(frame, liveBackdrop != null)
            dev.tgfeed.ui.BackdropPolicy.BackdropReportAction.SKIP_KEEP_OLD -> Unit
        }
    }

    // R89 底衬实时跟随路径 R194①② 起退役（预览条本身保留，见下方新注释）。
    // R194①② 双镜像播放（架构师 17:00 终案，用户原话「背景图就像全屏版的播放
    // 视频——前台播放视频，后台跟着做模糊背景地播放，两画面同步，用户无感」）：
    // 同一视频解码一次、画面绘制两遍——前台清晰列 + 全屏模糊背层。
    // 生产端 = TextureView 节流快照：把已解码的 GPU 侧帧缩读进**预分配**的短边
    // 144px 小位图（~100KB 回读，与外层模糊渲染目标同尺寸），节拍 80ms（≈12.5fps，
    // v1 保守案——模糊掩盖低刷率）。R83 的教训是「全尺寸 getBitmap 每秒回读」，
    // 本路小位图+节流，红线=快照不打扰前台解码（帧统计前后对照验证）。
    // 旧 R89 预览条供图路径退役（1.5s 粒度跳帧+逐拍渐变=「切 tab 疯狂闪烁追赶」
    // 病根）；预览条本身保留（进度拖拽帧预览仍用），seekPreview 不动。
    // 暂停时跳过本轮（底衬定格当前帧）；<1x 缩放态镜像停拍（背层回落封面口径，
    // R174 zoomedOut 列——架构师红线⑤）；换源/切档随 playUri 重启。
    // 首帧闸（shouldAdoptLiveBackdrop）：首帧上屏前不采纳——底衬保持封面（与列内
    // 占位同图同拍），首拍换血与「占位→画面」同拍合并（R159b 口径不变）。
    LaunchedEffect(player, isActive, useZoom, rotatedOdd, playUri, zoomedOut) {
        val p = player ?: return@LaunchedEffect
        // 2026-10-17 竖屏镜像修复（用户实锤「竖屏背景是死的」）：去掉 useZoom 闸——
        // ZOOM 填满模式也跑镜像循环（TextureView 无论 FIT/ZOOM 都在渲染视频帧，
        // getBitmap 拿到的就是纯视频帧与填充模式无关）。仅手动旋转/缩出态暂停镜像。
        if (!isActive || rotatedOdd || zoomedOut) return@LaunchedEffect
        val uri = playUri ?: return@LaunchedEffect
        var mirror: Bitmap? = null // 预分配快照位图（尺寸随 TextureView 实测重配）
        while (true) {
            delay(dev.tgfeed.ui.PlaybackStartPolicy.MIRROR_FRAME_INTERVAL_MS)
            // 首帧未上屏不采纳镜像帧：底衬维持封面（与占位同图），
            // 画面先上屏、镜像随同拍跟上
            if (!dev.tgfeed.ui.PlaybackStartPolicy.shouldAdoptLiveBackdrop(firstFrameSeen)) continue
            // R375 分职判词：镜像帧采集=渲染侧"帧是否在推进"判据（缓冲中本就无新帧
            // 可采、暂停定格语义不变）——刻意保持 isPlaying 真出声口径，不随用户侧
            // 开关换 playWhenReady 意图口径（负锚，见 R375WiringSourceTest）
            if (!runCatching { p.isPlaying }.getOrDefault(false)) continue // 暂停定格当前帧
            // AspectRatioFrameLayout 会把 TextureView 本身裁到视频比（FIT/ZOOM 变换
            // 都在帧布局层）——getBitmap 拿到的就是无黑边的纯视频帧
            val tv = playerViewRef?.videoSurfaceView as? TextureView ?: continue
            if (tv.width <= 0 || tv.height <= 0) continue
            val short = minOf(tv.width, tv.height)
            val scale = dev.tgfeed.ui.BackdropPolicy.BACKDROP_RENDER_SHORT_EDGE_PX.toFloat() / short
            val w = (tv.width * scale).toInt().coerceAtLeast(1)
            val h = (tv.height * scale).toInt().coerceAtLeast(1)
            if (mirror == null || mirror!!.width != w || mirror!!.height != h) {
                mirror = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            val snap = mirror!!
            if (runCatching { tv.getBitmap(snap) }.getOrNull() == null) continue
            // asImageBitmap 每次新包装实例（javap 实证 ui-graphics 1.7.2）→ 状态
            // 写入触发重组，外层 Image 重绘读同一位图的最新像素
            liveBackdrop = snap.asImageBitmap()
        }
    }
    Box(
        Modifier
            // 全屏破列（走查 P1-4：全屏视频仍被 600dp 限宽列夹住，实测仅占屏宽 53%）：
            // 全屏时按窗口宽测量本页，并按"页在窗口中的实际左缘"回移放置——
            // 内容左缘对齐窗口左缘，天然铺满全屏且居中（与列是否居中无关）。
            // 上报尺寸保持列约束，pager/列布局不受撑动；非全屏完全走原约束。
            // foundation 1.7.2 的 Pager 不裁剪页内容（已核 AAR 字节码无 clipToBounds），
            // 列外部分可正常绘制与接收触摸；即便外层日后加裁剪，也只退回
            // 列内全屏（=修复前现状），不会更糟
            .onGloballyPositioned { pageLeftInWindowPx = it.localToWindow(Offset.Zero).x }
            .then(
                if (fullscreen) {
                    Modifier.layout { measurable, constraints ->
                        val windowW = windowWidthDp.dp.roundToPx()
                        if (windowW > constraints.maxWidth) {
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = 0, minHeight = 0, maxWidth = windowW),
                            )
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.place((-pageLeftInWindowPx).roundToInt(), 0)
                            }
                        } else {
                            // 窗口不比列宽（如竖屏手机列本就铺满）：无需破列
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .fillMaxSize()
            // 容器实测尺寸回写：上方裁切保护（directionMismatch/ratioMismatch）的判定基准
            .onSizeChanged { containerSizePx = it }
            .background(pageBg)
            // 1.5) R174 双指捏合缩放手势（共享件）：挂页面根节点（祖先=事件必然
            //      流经，Initial pass 先于一切后代手势层消费——pager 翻页/tap slop
            //      在捏合期全部饿死，提案 §2.2 消歧矩阵；旧版独立兄弟 Box 在 tap
            //      层背后收不到事件=真机实锤死层，同节点双 pointerInput 又会踩死
            //      tap 层（R170 实锤复现）——祖先挂载是唯一可行位，详见
            //      PinchZoomTracking.kt 头注释）。缩放变化帧上抛机器（chrome α
            //      联动），平移帧不触发（防 restore 后两指平移误清屏）
            .pinchZoomTracking(
                key = item.key,
                zoomScale = pinch.zoomScale,
                zoomOffset = pinch.zoomOffset,
                // R194③ 自由平移：内容带=FIT 视频带（锚比链与 R188 下锚同一口径，
                // 实测比到货自动换带）；ZOOM 填满/手动旋转走 null=旧图层口径
                // （填满内容本就铺满视口；rotatedOdd 宽高互换几何另账，保守不扩）
                contentAspect = { pinchAspectInput },
                // 内容静止位=R188 贴底下锚（宽内容横屏列）；夹域围绕基点
                panBase = { pinchPanBase },
                onZoomChange = { old, new ->
                    pinch.dispatch(PinchCleanEvent.ZoomChanged(old, new))
                },
                onGestureStart = { pinch.cancelZoomReset() },
            ),
    ) {
        // 档位文本缩放：全页 sp 文本（信息区/操作栏/提示胶囊/弹幕/功能面板）随档位统一放大；
        // 只走 LocalDensity.fontScale 通道——dp 布局、手势热区、进度条粗细一律不受影响
        TierFontScale(tierDensity) {
        // 1) 视频画面；playUri 未就绪时封面/黑底占位（R131 拆至 VideoSurface.kt，纯搬运）
        // R188：随帧下发下锚平移量（视频层=锚比链、封面层=位图自身比，见上方计算处）
        VideoSurfaceLayer(
            player = player,
            cover = cover,
            useZoom = useZoom,
            fillToScreen = fillToScreen,
            rotationDeg = rotationDeg,
            zoomScale = zoomScale,
            zoomOffset = zoomOffset,
            firstFrameSeen = firstFrameSeen,
            loadFailed = loadFailed,
            dlProgress = dlProgress,
            sizeBytes = item.sizeBytes,
            uiScale = uiScale,
            rotatedOdd = rotatedOdd,
            videoBottomShiftPx = videoBottomShiftPx,
            coverBottomShiftPx = coverBottomShiftPx,
            // R218 镜像翻转态（R227 起随旋转态换轴——播放层局部 scaleX/scaleY
            // 跟 rotatedOdd 换轴=屏幕系恒左右翻，裁决见 FrameAdjustPolicy 注释）
            mirrored = mirrored,
            onPlayerViewReady = { playerViewRef = it },
        )

        // 底部可读性渐变已删（用户反馈：底部深上部浅的染色层影响了视频原来的色彩；
        // 信息区文字自带 withOverlayShadow 描边，不需要这个染色层）

        // 3) 手势层（标准 detectTapGestures，替换掉自实现手势机——旧实现的手势
        //    协程被 pager 抢断后 CancellationException 无人接，长按永久丢失）：
        //    单击任意处 = 暂停/继续（清屏恢复、锁定解锁）；双击 = 点赞飘心；
        //    长按左右各 25% = 2x 快进（1.2s 自动锁定，单击解除，松手停；继续按住到 2s
        //    转连续 ±10s 步进，松手停）；长按中间 50% = 弹功能面板。
        //    不消费位移事件，竖滑翻页与进度条横拖不冲突。
        //    （R174 捏合手势挂在页面根节点祖先位，见上方 1.5 注释；本层保持独立
        //    pointerInput——同节点双 pointerInput 会踩死 tap 层，R170 实锤复现）
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { boxWidthPx = it.width }
                .pointerInput(item.key) {
                    detectTapGestures(
                        onPress = {
                            // 手势结束（抬起/被消费）时收尾：未锁定的快进随松手取消，
                            // 连续步进模式同步停（锁定态本就只在未进入连续模式时存在）
                            tryAwaitRelease()
                            continuousSeekJob?.cancel()
                            continuousSeekJob = null
                            continuousSeek = false
                            if (!boostLocked) longPressBoost = false
                            boostLockJob?.cancel()
                            boostLockJob = null
                        },
                        onTap = { currentOnTap.value() },
                        onDoubleTap = { offset -> currentOnDoubleTap.value(offset) },
                        onLongPress = { offset ->
                            val leftSide = offset.x < size.width * 0.25f
                            val rightSide = offset.x > size.width * 0.75f
                            if (leftSide || rightSide) {
                                longPressBoost = true
                                // 本次按压开始前是否已处于 2x 锁定态（上一手势遗留）：
                                // 锁定后不再叠加连续快进（避免与 1.2s 自动锁定语义冲突）
                                val lockedAtPress = boostLocked
                                // 按住 1.2s 不松手 → 自动锁定 2x（单击任意处解除）
                                boostLockJob = gestureScope.launch {
                                    delay(1200)
                                    boostLocked = true
                                }
                                // 继续按住到 2s → 转入连续 ±10s 步进（0.3s 一步，松手停）。
                                // 衔接：先 2x（0~2s），2s 时撤销本次手势刚触发的锁定并退出 2x，
                                // 改为常速离散步进；若按压前已锁定则不动（维持锁定 2x）
                                continuousSeekJob = gestureScope.launch {
                                    delay(2000)
                                    if (lockedAtPress || playerNow == null) return@launch
                                    boostLockJob?.cancel()
                                    boostLocked = false
                                    longPressBoost = false
                                    continuousForward = rightSide
                                    continuousSeek = true
                                    seekHint = if (rightSide) "+10s ⏩" else "⏪ -10s"
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    while (true) {
                                        val p = playerNow
                                        if (p != null) {
                                            val target =
                                                p.currentPosition + (if (rightSide) 10_000L else -10_000L)
                                            val dur = p.duration
                                            p.seekTo(
                                                when {
                                                    target < 0L -> 0L
                                                    dur > 0 -> target.coerceAtMost(dur)
                                                    else -> target
                                                },
                                            )
                                        }
                                        // 每步一次轻微触感反馈（系统级触感，无需 VIBRATE 权限）
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        delay(300)
                                    }
                                }
                            } else {
                                showSheet = true
                            }
                        },
                    )
                },
        )

        // 3.5) 长按快进/连续步进提示：顶部胶囊（R131 拆至 VideoPageOverlays.kt，口径说明见该文件）。
        //      R174 清屏下保留（§3.3 留守清单：倍速提示是操作反馈，不属于 chrome）
        if (longPressBoost || continuousSeek) {
            BoostStatusPill(
                continuousSeek = continuousSeek,
                continuousForward = continuousForward,
                boostLocked = boostLocked,
            )
        }

        // 4) 中央反馈：暂停时常驻 ▶，恢复播放时闪现 ⏸；R174 清屏下保留（§3.3
        //    「暂停态中央 ⏸ 留——操作反馈不属于 chrome」，进清屏前已暂停的场景
        //    照常可见）；失败终态下让位给失败浮层（不叠加 ▶）（R131 拆至
        //    VideoPageOverlays.kt）
        if (paused && player != null && !loadFailed) {
            CentralPauseIcon(uiScale = uiScale)
        }
        if (flashResume) {
            CentralResumeFlashIcon(uiScale = uiScale)
        }
        // 缓冲转圈是加载关键反馈，清屏/全屏下保留（R131 拆至 VideoPageOverlays.kt，
        // 口径说明：与起播占位同一句式"正在加载"，失败终态下让位给失败浮层）
        if (buffering && playUri != null && !paused && !loadFailed) {
            BufferingColumn(dlProgress = dlProgress, sizeBytes = item.sizeBytes)
        }

        // 4.15) 自动重试中提示（R131 拆至 VideoPageOverlays.kt；触发窗口与让位规则说明见该文件）
        if (!loadFailed && failCount > 0 && !buffering && player != null) {
            ReconnectPill(failCount = failCount)
        }

        // 4.2) 加载失败兜底：三条到达路径（R78 分诊+R324 离线分诊）说明见
        //      VideoPageOverlays.kt（R131 拆出）。手动重试 = 整条链复位重来（含换路
        //      副本回到原条目、病因清零）；网络类与离线终态可点——内容坏终态不假装
        //      在努力。离线终态透传 [offline]（可点重试）与稍后再看出口（N2 边界
        //      动作降级兜底链——先收着，联网后从稍后再看清单进）
        if (loadFailed) {
            LoadFailedOverlay(
                fatalReason = fatalReason,
                onManualRetry = {
                    loadFailed = false
                    failCount = 0
                    retryPending = false
                    fatalReason = null
                    corruptRetryCount = 0 // R86：人工确认重试，换源重下也给新一轮机会
                    retryItem = item
                    paused = false
                    playUri = null
                    retryTick++
                },
                onSkip = onSkip,
                offline = !networkOnline,
                onWatchLater = { onWatchLater(item) },
            )
        }

        // 4.3) 续播提示：顶部胶囊"已从 X:XX 继续播放"（R131 拆至 VideoPageOverlays.kt）
        if (resumeHint && progressToRestore > 0) {
            ResumeHintPill(progressToRestore = progressToRestore)
        }

        // 4.4) 双击/连续步进的快退快进提示：顶部居中胶囊（R131 拆至 VideoPageOverlays.kt）
        seekHint?.let { hint ->
            SeekHintPill(hint = hint)
        }

        // 4.5) 定时关闭到点提示：中央胶囊（R131 拆至 VideoPageOverlays.kt）
        if (sleepPausedHint) {
            SleepPausePill()
        }

        // 4.6) 降档可见（GAPS3 #10）：智能降档胶囊（R131 拆至 VideoPageOverlays.kt）；
        //      R174 清屏下保留（§3.3 瞬态提示留守，与 ±10s 胶囊同族）
        DegradeQualityPill(
            visible = degradeHint,
            effectiveQualityHeight = effectiveQualityHeight,
        )

        // 4.7) R174 捏合清屏首次教学气泡（§3.2：顶部中央胶囊 2.5s 淡出；AppPrefs
        //      一次性把门，设置页「重新显示手势操作引导」复位——挂现有引导体系）
        PinchCleanCoachBubble(visible = pinchTeachBubble)

        // 5) 双击飘心层（多心爆散，瞬时反馈，清屏/全屏下保留）
        HeartBurstLayer(
            hearts = hearts,
            onHeartDone = { id -> hearts = hearts.filterNot { it.id == id } },
        )

        // 5.2) R138 回写送达胶囊（顶部短现 1.8s；与 BoostStatusPill 同槽位，
        //      瞬时反馈不挑场景，清屏/全屏下保留）
        if (writebackSyncedPill) {
            LikeWritebackSyncedPill()
        }

        // 5.3) R138 首次场景化引导气泡：双击落点上方、非阻断、8s 自动收
        //      （挂载在飘心层之后：压在手势层上，按钮可直接点，不影响翻页手势）
        likeGuideAnchor?.let { anchor ->
            LikeWritebackGuideBubble(
                anchor = anchor,
                pageWidthPx = boxWidthPx,
                onEnableWriteback = {
                    // 开启总闸（setter 顺手置决策位）；本条刚点的赞随即补发，
                    // 让"开启"当场可感知——👍 送达胶囊随后闪现（scope 同上取 VM 级）
                    appPrefs.likeReactionWriteback = true
                    likeGuideAnchor = null
                    LikeWritebackBoard.dispatch(gateway, feedVm.viewModelScope, item, appPrefs)
                },
                onKeepLocal = {
                    // 仅此点赞：本条保持纯本地（回写不发），不再问
                    appPrefs.likeWritebackPromptDecided = true
                    likeGuideAnchor = null
                },
            )
        }

        // 5.5) 弹幕层：评论滚过画面上 2/3 区域（纯展示，不挡手势；清屏时保留——弹幕是内容的一部分）。
        //      真实时间间隔映射（DANMAKU-TG 任务 3）：评论按发布时间间隔权重分布到视频时长
        //     （首条锚到视频发布时刻 item.dateEpochSec，算法见 docs/DANMAKU-TG.md §3），
        //      到点才发射（轮询 player 位置/时长）；回拖/单条循环位置大幅回退时弹幕层内部
        //      重置布点重发；lambda 现读 playerNow，播放器换实例/换源不重启弹幕引擎
        DanmakuOverlay(
            enabled = danmakuEnabled && isActive,
            modifier = Modifier.fillMaxSize(),
            timedComments = danmakuComments, // null = 未加载（弹幕层不渲染）
            videoPublishEpochSec = item.dateEpochSec.toLong(), // 真实间隔映射的零点锚点
            positionProvider = { playerNow?.currentPosition ?: 0L },
            durationProvider = { playerNow?.duration?.takeIf { it > 0 } ?: 0L },
        )

        // 5.9) 播放进度细线（最底部，让到导航栏之上）；清屏/全屏下保留。
        //      z-order 纪律（走查 P0-3 实锤）：本层必须声明在右轨(6)/信息区(7)之前——
        //      热区是全列宽条带（底部上抬 24dp + 36dp 高，上沿距屏底 60dp）且
        //      down 即消费；若声明在控件层之后（压在上），落进热区的「译」chip/
        //      大小 chip/追按钮下缘的点击会被吞成跳进度。声明在前 = 控件层在上
        //      各收各的点击，条带空档仍可点按跳转/横拖 seek，手感不变
        if (player != null) {
            PlaybackProgressLine(
                player = player,
                isActive = isActive,
                emphasize = paused || buffering,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // R173 底栏让位：进度细线与拖拽气泡贴底栏上沿（清屏时底栏退场，随 inset 同拍落回）
                    .padding(bottom = LocalBottomBarInset.current),
                // 拖拽状态上抛：顶层气泡层（下方 8.5）据此画时间+帧预览，
                // z 序压全部控件层——气泡画在 ProgressLine 内部时会被信息区/右轨压盖
                onDragState = { seekDrag = it },
                // R166 duration 兜底：TG 条目元数据时长恒有，moov 未解析（尾置 moov 的
                // 流式起播窗口）时点按/拖拽 seek 不再被 if(d>)0 静默吞掉
                fallbackDurationMs = item.durationSec * 1000L,
                // R204 TL-7 本机回放热力：本条拖拽落点分布刻度墙（纯展示零交互）
                heat = seekHeat,
            )
        }

        // 6) 右侧操作栏（R131 拆至 VideoRailAndInfo.kt；横屏头像让位与底距热区挂钩的说明见该文件）
        // R174 清屏连续映射：cleanMode 门控升级为 chrome α 连续渐隐（α=0 与
        // cleanMode 置位同刻，退组合零跳变；中间态跟手半隐；§3.3 旋转钮随右轨
        // 整体渐隐）。fullscreen 仍是几何层的 chrome 全退（离散门控，不进映射组）
        if (!pinch.cleanMode && !fullscreen) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pinch.chromeAlpha.value }) {
                VideoRightRail(
                orientation = orientation,
                uiScale = uiScale,
                chatId = item.chatId,
                channelTitle = item.channelTitle,
                avatarLoader = avatarLoader,
                subscribed = subscribed,
                onOpenChannelHome = onOpenChannelHome,
                onSubscribe = onSubscribe,
                liked = liked,
                favorited = favorited,
                downloadStatus = downloadStatus,
                // R175 弹幕/分享对调 + 2026-10-16 用户裁决 + R201 整合：右轨分享点按弹
                // 分享枢纽小菜单（ShareHubSheet 三选一：复制链接/分享到其他应用/分享
                // 卡片）；弹幕开关在长按面板（见 9）
                onShare = { onOpenShareHub(item) },
                rotationDeg = rotationDeg,
                onRotate = cycleRotation,
                onToggleLike = onToggleLike,
                onToggleFavorite = onToggleFavorite,
                onDownload = onDownload,
                likeBounce = likeBounce,
                // R138 点赞回写：本条 👍 已送达 → 点赞钮右下角小绿点；
                // 长按点赞钮 → 本条回写状态查询面板（轻量出口）
                likeWritebackDelivered = likeWritebackState.value == LikeWritebackStatus.DELIVERED,
                onLikeLongPress = { showLikeStatusSheet = true },
            )
            } // R174 chrome α 渐隐包裹 Box 收尾（右轨）
        }

        // 6.5) 全屏态退出按钮（R131 拆至 VideoRailAndInfo.kt；清屏时隐藏，系统返回键始终可退。
        //      R174 互斥后 cleanMode 与 fullscreen 不同时为真，本门控等价于 fullscreen）
        if (fullscreen && !pinch.cleanMode) {
            FullscreenExitButton(uiScale = uiScale, onExit = { fullscreenHooks.exit() })
        }

        // 7) 底部信息区（R131 拆至 VideoRailAndInfo.kt；合集导航行与信息面板同迁）
        //    R174 同右轨：cleanMode 门控升级为 chrome α 连续渐隐（§3.3 底部信息区在渐隐组）
        if (!pinch.cleanMode && !fullscreen) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pinch.chromeAlpha.value }) {
                VideoBottomInfo(
                item = item,
                uiScale = uiScale,
                albumGroupKeys = albumGroupKeys,
                feedVm = feedVm,
                onOpenChannel = onOpenChannel,
                onSearchTag = onSearchTag,
                translatedCaption = translatedCaption,
                onTranslate = onTranslate,
                avatarLoader = avatarLoader,
                subscribed = subscribed,
                onSubscribe = onSubscribe,
                plazaWatching = plazaWatching,
                plazaLikes = plazaLikes,
                onOpenLink = onOpenLink,
                onOpenMention = onOpenMention,
                // R269 「在星图中定位」：R234「你在这里」宇宙坐标行改可点——与功能面板
                // 「在星图中查看」格同一回调（R233④ 链：跳星图运镜飞向该条目所属恒星）
                onLocateInGalaxy = { onOpenTasteMap(item) },
            )
            } // R174 chrome α 渐隐包裹 Box 收尾（信息区）
        }

        // 8.5) 进度拖拽顶层气泡（时间 + 目标时刻帧预览）：声明在右轨(6)/信息区(7)
        //      【之后】——z 序压全部控件层，永远不被遮挡（历史实锤：气泡画在
        //      ProgressLine 内部时被信息区盖住）。横向跟随拇指（夹取在页内不越界），
        //      垂直悬在进度条热区上沿之上（R194⑥ 后 58dp）+ 16dp 间距
        seekDrag?.let { drag ->
            SeekDragBubbleLayer(
                drag = drag,
                preview = seekPreview.frameState.value,
                previewUsable = seekPreview.usableState.value,
                cover = cover,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // R173 底栏让位：进度细线与拖拽气泡贴底栏上沿（清屏时底栏退场，随 inset 同拍落回）
                    .padding(bottom = LocalBottomBarInset.current),
            )
        }

        // 8.7) R174b 自由缩放三件套之三：悬浮复位标识（TopStart，避右轨/进度线/
        //      教学/降档/全屏退出全部既位；**不进渐隐组**——清屏里它就是复位出口）。
        //      点按 = 双击退通道②同一套（FullReset + 0.82/500 弹簧）；判据
        //      pinch.isTransformed（scale≠1 或有平移），复位落 1x 后随弹簧终值淡出
        PinchResetChip(
            visible = pinch.isTransformed,
            onReset = {
                pinch.dispatch(PinchCleanEvent.FullReset)
                pinch.launchZoomResetSpring()
            },
        )

        // 9) 长按中间 50% 弹出的功能面板（ModalBottomSheet，实现见 ActionSheet.kt）
        if (showSheet) {
            VideoActionSheet(
                speed = speed,
                autoAdvance = autoAdvance,
                sleepTimerMinutes = sleepTimerLocal,
                fillToScreen = fillToScreen,
                onSetFillMode = onSetFillMode,
                onDismiss = { showSheet = false },
                // 推荐 = 现有点赞回调（已赞时再点会取消赞，与操作栏拇指行为一致）
                onRecommend = { onToggleLike(); showSheet = false },
                onWatchLater = { onWatchLater(item); showSheet = false },
                onNotInterested = { onNotInterested(item); showSheet = false },
                onCache = { onDownload(); showSheet = false },
                onPopout = { onPopout(item); showSheet = false },
                onAddToGrid = { onAddToGrid(item); showSheet = false },
                onForward = { onForward(item); showSheet = false },
                onTranslate = { onTranslate(item); showSheet = false },
                // 倍速档位：直接设定并关面板
                onSetSpeed = { onSetSpeed(it); showSheet = false },
                // 清晰度档位：当前视频备选分辨率直通面板 chips；设定上报并关面板
                //（全局偏好，热生效——当前条即切即换、续播到当前位置）
                variants = item.variants,
                currentQualityHeight = qualityHeight,
                onSetQuality = { onSetQuality(it); showSheet = false },
                // 清屏播放（备份显式入口，§3.5 保留）：关面板 + 离散进清屏
                //（α 走 220ms 渐隐；基线缝合到当前缩放，后续捏合沿曲线无跳变）
                onClearScreen = {
                    pinch.dispatch(PinchCleanEvent.PanelCleanEntered(zoomScale))
                    showSheet = false
                },
                // 定时关闭：本地镜像一份用于高亮，再上报 FeedScreen
                onSetSleepTimer = { minutes ->
                    sleepTimerLocal = minutes
                    onSetSleepTimer(minutes)
                },
                onSetAutoAdvance = onSetAutoAdvance,
                // 循环单条：Feed 级共享状态直通面板 Switch
                loopOne = loopOne,
                onToggleLoop = onToggleLoop,
                // R201 分享收编：原「复制链接」格改单格「分享」——关本面板、弹分享枢纽
                // 小菜单（onOpenShareHub 由右轨与面板两入口共用，链体在 FeedScreen 收口）
                onShare = { onOpenShareHub(item); showSheet = false },
                // R175 弹幕/分享对调：分享卡片（R201 起收编进枢纽菜单）迁右轨后此槽
                // 为弹幕开关（回调/红点角标原样）；
                // 不关面板——图标角标即翻转反馈（与静音行同语义）
                danmakuEnabled = danmakuEnabled,
                onToggleDanmaku = onToggleDanmaku,
                onSendDanmaku = { onSendDanmaku(item); showSheet = false },
                // R205 TL-9 弹幕回放：关本面板、开时间轴回放视图（宿主 FeedScreen 接线）
                onOpenDanmakuTimeline = { onOpenDanmakuTimeline(item); showSheet = false },
                // R204 DS-5 首发溯源：关本面板、开溯源面板（查证成本秒级由面板转圈承接）
                onFirstSeen = { onOpenFirstSeen(item); showSheet = false },
                // R202 TL-4 事件考古：关本面板、开时光机（±48h 全网横截面，索引毫秒级）
                onEventDig = { onOpenEventDig(item); showSheet = false },
                // R233④ 在星图中查看：关本面板、跳星图运镜飞向该条目所属恒星
                onOpenTasteMap = { onOpenTasteMap(item); showSheet = false },
                // 查看上下文：切频道 Feed 并定位到本条（FeedScreen 接 vm.openChannelAt）
                onViewContext = { onViewContext(item); showSheet = false },
                // R227 画面调整收编：原「旋转」+「镜像翻转」两格并为单格——关本面板、
                // 弹画面调整枢纽小菜单（onOpenFrameAdjust 由宿主 FeedScreen 托管；
                // 复合态记忆在 FrameAdjustStore，每条视频独立跨会话保持）；非默认
                // 姿态红点角标（isFrameAdjusted 纯函数——与枢纽菜单态同一裁决）
                frameAdjusted = isFrameAdjusted(rotationDeg, mirrored),
                onOpenFrameAdjust = { onOpenFrameAdjust(item); showSheet = false },
                // P0-1 右轨收编三件套：静音切换不关面板（图标/文案即翻转反馈，与 Switch 行一致）；
                // 全屏/截图先关面板再执行（面板不能跟着进全屏；截图抓的是播放器当前帧，与面板无关）
                muted = muted,
                onToggleMute = {
                    // P1.5 #3：当前静音时点按 = 主动开声音（高投入强正信号，进频道权重）；
                    // 开声时点按（回静音）只是回到默认，不计信号
                    if (muted) feedVm.noteActiveUnmute(item)
                    onToggleMute()
                },
                onToggleFullscreen = {
                    showSheet = false
                    fullscreenHooks.enter()
                },
                onScreenshot = {
                    showSheet = false
                    captureFrame()
                },
            )
        }

        // 9.5) R138 点赞回写状态查询面板（长按点赞钮触发；结论先行的轻量出口，
        //      启停按钮写的正是设置页总闸同一 prefs 键）。
        //      面板开时镜像一份总闸状态：SharedPreferences 读不触发重组，翻转后
        //      状态行/按钮要即时跟着变，得靠本地可观测镜像（每次开面板现读一次）
        if (showLikeStatusSheet) {
            var sheetWritebackOn by remember(showLikeStatusSheet) {
                mutableStateOf(appPrefs.likeReactionWriteback)
            }
            LikeWritebackStatusSheet(
                status = likeWritebackState.value,
                writebackOn = sheetWritebackOn,
                realGateway = gateway is TdLibGateway,
                liked = liked,
                channelTitle = item.channelTitle,
                onEnableWriteback = {
                    appPrefs.likeReactionWriteback = true
                    sheetWritebackOn = true
                },
                onDisableWriteback = {
                    appPrefs.likeReactionWriteback = false
                    sheetWritebackOn = false
                },
                onOpenInTelegram = {
                    // 原频道消息链接（网关解析）→ 系统打开（TG 官方客户端优先，回落浏览器）；
                    // 打不开给明确反馈不装死（与 FeedScreen openExternalLink 同口径）
                    gestureScope.launch {
                        val link = when (gateway) {
                            is TdLibGateway -> gateway.getMessageLink(item)
                            is dev.tgfeed.core.td.MockTdGateway -> gateway.getMessageLink(item)
                            else -> null
                        }
                        if (link != null) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(link),
                                    ),
                                )
                            }.onFailure {
                                Toast.makeText(
                                    context, "没有能打开该链接的应用", Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
                onDismiss = { showLikeStatusSheet = false },
            )
        }
        } // TierFontScale 收尾（全页 sp 文本缩放包裹，开头见 Box 首行）
    }
}

/**
 * 档位文本缩放包裹层：把 LocalDensity 的 fontScale 乘上档位系数后再下发，
 * 只放大 sp 文本（信息区/操作栏/提示胶囊/弹幕），dp 布局与手势热区不受影响。
 * 比整体 dp 缩放安全：不会把手势判定区/进度条热区一起放大造成错位。
 */
@Composable
private fun TierFontScale(density: Density, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

// ---------------- R89 底衬实时跟随轮询 ----------------
// R159b：轮询间隔常数已收编进 PlaybackStartPolicy（单一来源）——常规 500ms 跟随拍
// 之外新增 live 制度未开启期的 80ms 短拍追赶（起播一拍对齐），本文件不再私有持份
