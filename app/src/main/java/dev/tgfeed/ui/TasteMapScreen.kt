package dev.tgfeed.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.DeepSearchIndex
import dev.tgfeed.core.feed.DiscoveryGraph
import dev.tgfeed.core.feed.DiscoveryGoldmine
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.SearchEntity
import dev.tgfeed.core.feed.discoveryGoldFile
import dev.tgfeed.core.feed.episodeChains
import dev.tgfeed.core.feed.titleLine
import dev.tgfeed.core.playback.ShareCard
import dev.tgfeed.core.store.WatchEvent
import dev.tgfeed.core.tastemap.AstroFacts
import dev.tgfeed.core.tastemap.BinaryStarPolicy
import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.ChannelEraPolicy
import dev.tgfeed.core.tastemap.CmbBackdropFx
import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.core.tastemap.EmissionNebulaFx
import dev.tgfeed.core.tastemap.GalaxyPorthole
import dev.tgfeed.core.tastemap.GestureFeelPolicy
import dev.tgfeed.core.tastemap.LayoutHealth
import dev.tgfeed.core.tastemap.ProperMotionFx
import dev.tgfeed.core.tastemap.RocheTidalFx
import dev.tgfeed.core.tastemap.TrojanFx
import dev.tgfeed.core.tastemap.TasteAchievements
import dev.tgfeed.core.tastemap.TasteAstroPhysics
import dev.tgfeed.core.tastemap.TasteBirthFx
import dev.tgfeed.core.tastemap.TasteDepthSpread
import dev.tgfeed.core.tastemap.TasteEmergentLayout
import dev.tgfeed.core.tastemap.TasteEmergentSource
import dev.tgfeed.core.tastemap.TasteDiscoveryLog
import dev.tgfeed.core.tastemap.TasteStarlight
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteGalaxyClassifier
import dev.tgfeed.core.tastemap.TasteGalaxyPerturb
import dev.tgfeed.core.tastemap.TasteLifeReplay
import dev.tgfeed.core.tastemap.TasteNebula
import dev.tgfeed.core.tastemap.TasteShipFx
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteSpace.SpaceCam
import dev.tgfeed.core.tastemap.TasteScaleSigma
import dev.tgfeed.core.tastemap.TasteStarColor
import dev.tgfeed.core.tastemap.TasteSpreadExpand
import dev.tgfeed.core.tastemap.TasteTagAffinity
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.core.tastemap.TasteTagSpread
import dev.tgfeed.core.tastemap.TasteTerminalFx
import dev.tgfeed.core.tastemap.TasteTour
import dev.tgfeed.core.tastemap.TasteVoyageLog
import dev.tgfeed.core.tastemap.TasteWormhole
import dev.tgfeed.core.tastemap.UniverseDirector
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.gl.GlEnginePolicy
import dev.tgfeed.ui.gl.GlFrameMonitor
import dev.tgfeed.ui.gl.GlTextureBaker
import dev.tgfeed.ui.gl.StarSystemMiniModel
import dev.tgfeed.ui.gl.TasteGlRenderer
import dev.tgfeed.ui.gl.TasteGlScene
import dev.tgfeed.ui.gl.miniModelHeightDp
import dev.tgfeed.ui.gl.probeEs3
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ==================== 常量 ====================

/**
 * 恒星（频道）上限（R223b 全宇宙版：300→600——meta 全表∪金矿∪DiscoveryProfile∪
 * SearchEntity 全本地只读口径，距离矩阵 600²=36 万次毫秒级、MDS 全流程 Default
 * 线程亚秒-秒级；设计文档 §2.2/§2.3 可行性核算）。超出按「看过>订阅>体量降序」
 * 留重（大频道先留，观看语义永不挤出）。
 */
private const val MAX_STARS = 600

/** 连线上限（超过按合并权重留重） */
private const val MAX_LINKS = 800

// ---- R223b 非频道天体护栏（设计 §1.4 实数规模预期：脉冲星/星团为十量级、流星体
//      为个位数量级——护栏防极端账号把 MDS 规模顶爆；按 chatId 升序截断=确定性） ----

/** 脉冲星（bot）上限：SearchEntity.KIND_BOT ∪ 私聊 bot 实证 */
private const val MAX_PULSARS = 48

/** 星团（群组）上限：CHAT_BASICGROUP ∪ 非频道 CHAT_SUPERGROUP */
private const val MAX_CLUSTERS = 48

/** 流星（真人私聊用户）上限 */
private const val MAX_METEORS = 24

/** R223b 海报旧口径池上限（=R206 旧 MAX_STARS：海报内容集合冻结在三源封闭岛，
 *  「现有静态版不变」红线的量纲件——星系版海报是三期拍板项） */
private const val LEGACY_POSTER_STARS = 300

// ---- R223b 非频道天体基色（频道星仍走三基相内容构成混色） ----
/** 脉冲星：蓝白（射电脉冲的冷光） */
private const val PULSAR_COLOR = 0xFFBFD9FF.toInt()

/** 星团：暖米白（恒星成团的聚合光） */
private const val CLUSTER_COLOR = 0xFFE3DCC6.toInt()

/** 流星：淡蓝（游荡的用户不在内容引力井里） */
private const val METEOR_COLOR = 0xFFCFE0F2.toInt()

/** 世界坐标系边长：布局产出统一归一到 [0,1000]³（z 为深度轴），渲染/命中/海报共用这一坐标系 */
private const val WORLD = 1000f

/** 点半径区间（世界单位）：R152c 真机反馈"光晕糊成大色块"的收敛——本体上限 44→26
 *  （实心小圆清晰锐利），最小点 3.2，没看过的暗点 2.2（亮度同步抬到可见档） */
private const val RADIUS_MIN = 3.2f
private const val RADIUS_MAX = 26f
private const val DIM_RADIUS = 2.2f

/** 半径幂曲线指数：sqrt(时长比)^此值——压低中段、抬高头部，点的大小分层比线性更夸张 */
private const val RADIUS_GAMMA = 1.35f

/** 内容类型基色（R152c 真机反馈"颜色太艳"的降饱和改版：大红大黄大绿大紫的纯饱和色
 *  廉价——低饱和+微发光的"暗夜柔光点"高级感；色相锚不变（20°/125°/265° 三相分离
 *  依旧），饱和压到 ~0.4-0.6、明度抬到 ~0.8+；海报 legend 与 App 内共用）：
 *  视频=柔光陶土橙、图文=柔光鼠尾草绿、音频=柔光薰衣草紫 */
private const val TYPE_VIDEO = 0xFFE08B5A.toInt()
private const val TYPE_PHOTO = 0xFF81D179.toInt()
private const val TYPE_AUDIO = 0xFFA484DB.toInt()

/** 三个基相（度）：starColor 圆插值与基色常量同源（改就一起改） */
private const val HUE_VIDEO = 20f
private const val HUE_PHOTO = 125f
private const val HUE_AUDIO = 265f

/** 暗点色（没看过的频道）：暗蓝白低饱和（R152c 亮度微抬——真机上近黑背景里 0.32 档
 *  透明度的暗点几乎隐形，大片"空黑"其实是没看过的频道摊在整张图上看不见） */
private const val TYPE_DIM = 0xFF96A9C9.toInt()

/** R206 创世纪自动播放全程时长（秒）：光标 0→1 的默认剧场节奏（大爆炸正放）；
 *  R314 延时摄影换代=本值 × 倍速档（TasteFlight.TIMELAPSE_SPEED_STEPS） */
private const val GENESIS_PLAY_SEC = 12f

/** R206 彗星轮询周期（毫秒）：search.db MAX(rowid) 差分（O(1) 探测，只读 WAL） */
private const val COMET_POLL_MS = 4000L

/** R206 在播频道轮询周期（毫秒）：items/currentIndex/nowPlayingAudio 纯内存读 */
private const val PLAYING_POLL_MS = 2000L

// ---- 高维口味向量 → 3D 嵌入参数（R152：MDS 降维取代旋臂/核球/球晕的结构化星系布局。
// 用户方向纠偏原话"不是这种的，非得搞一个星系漩涡背景，我理解更像一簇高维向量映射到
// 三维空间"——星的位置改由频道间真实相似性决定（相似聚簇、相异远离），不做任何造型） ----

/** 特征向量维度数：内容构成 3（视频/图文/音频占比）+ 时长风格 1 + 观看行为 3
 *  （时长对数/新鲜度/互动对数）+ 体量 1（索引条数对数）+ 订阅位 1 + 频道规模 1（成员数对数） */
private const val FEAT_DIMS = 10

/** 特征分块权重（乘到标准化维度上）：内容构成 ×1 主导簇结构（同类频道自然聚簇、
 *  混合频道散在簇间），时长风格/观看强度次之（簇内梯度），互动/体量/订阅/成员数只做微调 */
private val FEAT_WEIGHTS = floatArrayOf(1.0f, 1.0f, 1.0f, 0.30f, 0.30f, 0.20f, 0.15f, 0.12f, 0.08f, 0.08f)

/** 观看新鲜度衰减尺度（天）：exp(-days/60)，两月没看衰减到 1/e */
private const val FRESH_DAYS = 60f

/** 幂迭代取前三特征向量的轮数（经典 MDS/Torgerson 初值；确定性正弦种子，零随机成分） */
private const val EIG_ITERS = 40

/** SMACOF 应力精修轮数（Guttman 变换单调收敛；每轮 O(n²)，300 星全流程毫秒级） */
private const val MDS_REFINE_ITERS = 60

/** 嵌入云团目标半径（世界单位，中心 500,500,0）：离群频道如实远置（离群也是数据），
 *  视口由投影包围盒取景适配收进画布 */
private const val CLOUD_R = 330f

/** 开场揭示动画时长（R223b：640→1500——分批 3×200 波次淡入的总时长，每批 ~500ms，
 *  easeOutCubic 与创世纪凝聚同款缓动件） */
private const val REVEAL_MS = 1500

// ---- R233 行星层/星际考古常量（设计 §分期二；LOD 阈=井深比无量纲口径，
//      见 core TasteTagPlanets APPEAR/FULL/DUST_RATIO——与设备/分辨率无关） ----

/**
 * 行星系统 LRU 容量（井内存活频道数：飞过即缓存，淘汰纯内存无落盘）。
 * R367 判词（帽 12 的两宗罪）：①帽 12 < 预取 [PLANET_PREFETCH_TOP]=24——入场预取
 * 自己驱逐自己（top-24 按体量降序装完只剩后 12 名，最可能被飞近的前 12 大系统全被
 * 自逐出）；②相机驻留密集星域时井内可见域（行星层点亮域）超帽——每拍装配必驱逐一
 * 个**正在屏上渲染**的行星系（[GalaxyLogicCtx.planetSysSnapshot] 查表落空=整星行星
 * 层硬切消失，无淡出），下拍它又是井内未装配→重装再现→再逐下一个，循环=用户
 * 2026-10-24 08:19 实锤「行星一会消失一会儿出来来回闪烁」（尘埃档同病：尘埃星条件
 * 含 cachedIds，逐出即熄）。R367 修：帽抬到预取量+井内余量 36，配装配处**井内
 * 钉住**（可见即不逐）；内存账=36 条 × ≤240 样本行投影 ≈ 4MB 峰值上界（常态
 * 井内+预取远低于满帽）。
 */
private const val PLANET_LRU_CAP = 120

/**
 * R412 每拍装配上限（井内未装配星按井深比近→远取前 N 个，单协程串行装——IO 线程，
 * 不卡 UI）。旧口径每 400ms 只装 1 个：绕飞扫过的井内星远多于此吞吐=「大部分恒星
 * 旁边没有行星」用户实锤（2026-10-27 13:2x）。
 */
private const val PLANET_ASSEMBLE_PER_BEAT = 4

/** 全池大频道指纹预取数（体量降序 top N——关联弧的主现场是巨头互连） */
private const val PLANET_PREFETCH_TOP = 24

/** 行星层轮询周期（毫秒）：井深比扫描/装配候选/近焦星/尘埃星 O(n)（非逐帧） */
private const val PLANET_POLL_MS = 400L

/** 指纹预取错峰间隔（毫秒）：不抢装配/爬虫 IO */
private const val PLANET_PREFETCH_GAP_MS = 300L

// R268b：行星/尘埃/弧命中热区（14dp/14dp）、飞进行星门宽（22 单位）、收卡阈值
// （64dp）、星命中兜底（22dp）等手感常数全部收口 GestureFeelPolicy（唯一出处，
// design-gesture.md §九）——本文件零手感魔数，消费点直引政策件。

// ---- R248 星名点亮发现机制（用户原创「名字可以搞成探索形式的点亮机制」） ----

/**
 * 发现井深比阈：**大于**行星层 [TasteTagPlanets.APPEAR_RATIO]（6.0）——名字先亮
 * （10 处）、行星后现（6 处）、到达态井×4.6 全展开。「先发现再看行星」红线的
 * 量纲件；与停船/井深比同一无量纲口径（3D 距离÷井半径）。
 */
private const val DISCOVER_RATIO = 10.0f

/** 会话发现动画账的有效期（ms）：渲染侧 revealIds 的剪枝窗（过期即清零常驻） */
private const val REVEAL_TTL_MS = 3000L

// ---- R248 信息卡重设计 + 编年史融入（R246 融入星图信息卡，不做独立页） ----

// ---- R250 信息卡增强常量（动态预览/tag 行星云——替换 R248 的 PREVIEW_COUNT=3
//      写死与 PREVIEW_SAMPLE_LIMIT=12 初载页：可见数 N 改为屏宽算术动态口径） ----

/** 代表作缩略步长（dp）：格宽 84+间距 8——LazyRow 一格的步进账（可见数 N 的分母） */
private const val PREVIEW_STEP_DP = 92

/** 代表作懒加载护栏（累计装载数上限——怪兽频道不无限装载，预览窗封顶） */
private const val PREVIEW_MAX = 120

/** tag 行星云行星上限（count 降序 top N——信息卡克制度） */
private const val TAG_CLOUD_MAX_PLANETS = 7

/** tag 行星云点行星热区下限（dp） */
private const val TAG_CLOUD_MIN_TOUCH_DP = 28

// ---- R253 信息卡交互升级常量（①热力条点选②融合布局+下滑收起） ----

// R253② 信息卡 drag-to-dismiss 口径：**起手时滚动在顶**才累计（中段起手=纯滚动
// 不误收）；净下滑 ≥ GestureFeelPolicy.CARD_DISMISS_DRAG_DP（64dp=刻意一划：
// >横滑漂移、<内容短卡的一划）且纵向主导（> |dx|）收卡——纯观察 Initial pass
// 零消费零干扰（纪律§四手势挂载位：共同祖先+Initial pass=唯一可靠位）。

// ---- R234 三期常量（尘埃带/星座轨迹/时间联动） ----

/** 星座轨迹站点上限（history.json 今日观看串——典型日 10-50 站，护栏防极端日刷爆渲染） */
private const val CONST_TRAIL_MAX_STOPS = 120

// ---- R237 真机体验修复常量 ----

// ---- R249 星系物理常量（双星对量纲；布局期单一出处） ----

/** 双星对上限（「互相绕转」的可见对数——克制不铺满） */
private const val MAX_BINARIES = 6

// R283：双星判定门（旧 BINARY_MIN_WEIGHT=8 合并边权硬门）迁入 BinaryStarPolicy
// 政策件（维度化：互转/互链/共现+内容同源+时间分布——单一出处，量纲校准见其 KDoc）

/** 首开引导浮层的持久键（tgfeed_ui prefs——一次性教学；**R242 手势模型全换**：
 *  v2 键重弹一轮（拖=环顾/捏=飞/双击=飞向星——旧 v1 教学已失效）；
 *  **R268b 升 v3 重弹一轮**：文案修正「星野跟手」→「像转头看四周」（转头模型
 *  星野实际反向移动——R242 验证原话；design-gesture.md §7 提案②用户终审批准） */
private const val GUIDE_PREF_KEY = "taste_map_guide_v3"

// ==================== 数据模型（本页私有） ====================

/**
 * 一颗星的装配原料 + 展示态（颜色/半径/亮度/名签/互动账都在装配期定死——它们只依赖数据
 * 不依赖坐标；布局阶段只补 x/y/z/度数与天体分档）。
 *
 * R223b 全宇宙版两套口径（海报零回归的机制，设计 §1.3 KDoc 成文要求）：
 *  - [radius]/[alpha]/[colorArgb] = **旧观看口径**（√观看时长比^γ/观看新鲜度/观看构成
 *    混色）——海报（ShareCard）与信息卡对比条继续消费，语义与 R206 完全一致；
 *  - [bodyRadius]/[bodyAlpha]/[bodyColor] = **体量主轴口径**（√条数比^γ 体量×观看
 *    混合 / 观看新鲜度∨内容活跃度双源取强 / 索引基因构成兜底混色）——App 渲染器
 *    与命中测试消费（交互飞行语义）。
 */
private data class MapStarRaw(
    val chatId: Long,
    val title: String,       // 多来源拼表（频道列表/订阅/内存条目/图谱节点/金矿），未知为空串
    val watchSec: Long,      // 累计观看秒数（watch_events 全量）
    val lastWatchMs: Long,   // 最近一次观看时刻（0=从未）
    val videoSec: Long,      // 内容类型构成：视频观看秒数（事件级近似推导）
    val photoSec: Long,      // 图文秒数
    val audioSec: Long,      // 音频秒数
    val memberCount: Int,    // 图谱懒加载的成员数（-1=未知）；星团天体=尘点云规模原料
    val subscribed: Boolean, // 是否在订阅表
    val colorArgb: Int,      // 内容类型构成混色（旧口径：观看构成>投票>暗星色）
    val alpha: Float,        // 亮度（旧口径：观看新鲜度+互动账）
    val radius: Float,       // 半径（旧口径：√观看时长比^γ；海报消费）
    val label: String?,      // 头部频道名签（其余星不挂牌）
    val likeCount: Int = 0,  // R147 互动账：点过赞的条数（likes.json 的 key 前缀解析）
    val favCount: Int = 0,   // R147 互动账：收藏的条数（内存收藏表）
    val danmakuCount: Int = 0, // R147 互动账：该频道条目上的广场弹幕条数（含你发的）
    // ---- R152 高维口味向量的特征原料（构成回落 + 体量 + 时长风格） ----
    val idxCount: Int = 0,     // 体量维度：深度索引条数（0=未索引）
    val gVideo: Int = 0,       // 构成回落：视频条数（索引基因优先，无索引用内存条目投票）
    val gPhoto: Int = 0,       // 构成回落：图文条数
    val gAudio: Int = 0,       // 构成回落：音频条数
    val avgDurSec: Long = 0L,  // 时长风格：索引口径平均时长（0=无时长数据）
    // ---- R206 创世纪星生序：频道首见（索引最早条目日期与首次观看事件的较早者；
    //      0=无证据——创世纪里最后诞生，没有证据不冒充古老） ----
    val firstSeenMs: Long = 0L,
    // ---- R223b 天体档案原料（金矿快照 6h TTL；缺失=判据数据缺省，分档器按恒星
    //      诚实缺省绝不冒充特殊天体） ----
    val goldItems: Int = 0,          // 金矿条数（与 idxCount 互证，体量分位取 max）
    val goldLastActivitySec: Long = 0L, // 最近活动 epoch 秒（0=未知）
    val goldRecent7: Int = -1,       // 近 7 天条数（-1=无金矿档案）
    val goldPrev7: Int = -1,         // 再前 7 天条数（-1=无金矿档案）
    val goldTier: Int = -1,          // R163 档位 persisted（1=T1=系统验货徽章环）
    // ---- R223b 非频道天体（脉冲星/星团/流星：实体类型装配期即定，不走六态矩阵） ----
    val entityKind: CelestialKind? = null,
    // ---- R223b 体量主轴口径（App 渲染器/命中测试；见类 KDoc 两套口径段） ----
    val bodyRadius: Float = DIM_RADIUS,
    val bodyAlpha: Float = 0.55f,
    val bodyColor: Int = TYPE_DIM,
    // ---- R311 色彩引擎：连续色温（K，黑体谱——TasteStarColor.starKelvinOf 三轴合成；
    //      0=走旧三档 temp 桶（实体天体/海报口径不破）） ----
    val tempK: Float = 0f,
    // ---- R250 个人足迹（条目级看过账）：history.json key 去重窗（500 条滚动）内
    //      该频道看过的条数——「看过 N 条」的诚实口径（窗口滚动=窗口外不计） ----
    val watchedCount: Int = 0,
)

/** 连线装配原料：两端 chatId（a<b）+ 合并权重 */
private data class MapLinkRaw(val a: Long, val b: Long, val weight: Int)

/** R234 星座轨迹站点（history.json 口径）：今日看过的一个频道站 + 观看时刻 */
private class TodayStop(val chatId: Long, val tsMs: Long)

/** 装配中间态：原料星 + 原料连线 + 全图累计观看 + R206 今晚星轨频道串 + R223b 海报
 *  旧口径池（布局阶段的输入） */
private class MapAssembly(
    val raws: List<MapStarRaw>,
    val links: List<MapLinkRaw>,
    val totalWatchSec: Long,
    val todayTrail: List<Long>,
    /** R223b 海报零回归件：旧三源封闭池（watch>订阅>图谱补齐 300）同序重算——
     *  buildPosterData 按它过滤内容集合（见其 KDoc） */
    val legacyPosterIds: Set<Long> = emptySet(),
    /** R234 星座轨迹原料：history.json 今日观看站点串（时间升序、连续同频道去重） */
    val todayStops: List<TodayStop> = emptyList(),
    /** R251⑥ 互联分类账（图谱快照边按无向对×三类合并——编年史「互联档案」卷原料） */
    val rels: Map<Long, List<NeighborRel>> = emptyMap(),
    /** R283 双星证据维度化原料（BinaryStarPolicy.PairEvidence 全候选对：图谱三维
     *  计数直供 + top-K 候选经编年史⑥数据底座增强内容同源/时间跨度） */
    val binaryEvidence: List<BinaryStarPolicy.PairEvidence> = emptyList(),
    /** R321 双星对附加账（洛希同源 W/特洛伊共源 W——与 R283 增强环同域：图谱
     *  forward_src 上游交集零新查询 + PairEvidence 现役同源字段直取） */
    val binaryExtras: List<BinaryPairExtra> = emptyList(),
    /** R267 基因指纹（LayoutHealth.genomeFp 打包：基因组频道数+总条数对数桶）——
     *  成品缓存换装门原料（不再只看频道数 ±5%）；0=无基因组（演示/新装） */
    val genomeFp: Long = 0L,
    /** R267 装配数据源退化实锤：基因组空 ∧ meta 全集≥16（批迁/迁移中间态锁窗——
     *  空扫描被「诚实缺省」降级喂给 MDS 的防范闸；调用方据此重试而非直接产退化布局） */
    val sourceDegenerate: Boolean = false,
    /** R303c P1：tags 账本指纹（chatId→fold 键→计数——DeepSearchIndex.chatTagLedger
     *  只读批量口，R236 全频道历史口径；涌现布局场 Jaccard 引力对的真源；空=诚实
     *  降档（回填窗口期），互链/距离两路仍可成对） */
    val tagFingerprints: Map<Long, Map<String, Int>> = emptyMap(),
    /** R317b：白洞宣判证据（装配口账面在场口径收集——三账在场位单源
     *  [TasteTerminalFx.whiteholeAccountOf]：like=likes.json 读取成功位/fav=
     *  favorites.json 磁盘直读位/danmaku=广场快照在场位；缺席账=-1 缺据不判）。
     *  空表=无频道候选；宣判协程消费（judgeWhiteHoles core 单口） */
    val whiteholeEvidence: List<TasteAstroPhysics.WhiteholeEvidence> = emptyList(),
)

/** 一颗星（频道）在星图上的完整展示态：不可变，布局产出 x/y/z 与天体分档后整体发布 */
internal data class StarNode(
    val chatId: Long,
    val title: String,
    val watchSec: Long,
    val lastWatchMs: Long,
    val videoSec: Long,
    val photoSec: Long,
    val audioSec: Long,
    val memberCount: Int,
    val subscribed: Boolean,
    val degree: Int,       // 无向连接数（合并后的互链/共现边数）
    val colorArgb: Int,    // 旧观看口径（海报消费；见 MapStarRaw 两套口径 KDoc）
    val alpha: Float,      // 旧口径亮度（海报消费）
    val radius: Float,     // 旧口径半径（海报消费）
    val label: String?,
    val likeCount: Int,    // R147 互动账（信息卡"最近互动"行）
    val favCount: Int,
    val danmakuCount: Int,
    val hasComp: Boolean,  // R152c：有任何构成证据（观看秒数/索引基因/投票）——默认取景按它聚焦口味云团
    val x: Float,
    val y: Float,
    val z: Float,          // R147 深度轴（世界单位，±zSpan 内）：**海报口径**（MDS 第三维原值——ShareCard 消费，R231 纵深重建不动它=海报零回归）
    val flightZ: Float = z, // R231 纵深口径（三带分布；R256 后仅 TasteNebula 聚类原料）
    // ---- R242 空间口径（App 全消费方：球面化宇宙坐标，Y 向上、以初始相机位为原点；
    //      R256 起 TasteSpace.spherifyV3 产出（θ=口味角/φ=独立均匀/r=体量分位）——
    //      渲染/命中/运镜/井深比/尘埃带/星轨全走这一份） ----
    val spX: Float = 0f,
    val spY: Float = 0f,
    val spZ: Float = 0f,
    val firstSeenMs: Long = 0L, // R206 星生序（创世纪凝聚的时间戳，0=无证据最后诞生）
    // ---- R223b 全宇宙天体档（分档器纯函数产出，布局期补齐；信息卡"天体档案"段
    //      与渲染器六态笔刷的单一数据源） ----
    val kind: CelestialKind = CelestialKind.STAR, // 天体类型（六态+脉冲星+星团+流星）
    val tier: Int = -1,           // R163 档位（1=T1=验货徽章环；-1=LEGACY/未知）
    val itemsCount: Int = 0,      // 体量条数（max(idxCount, 金矿 items)——档案行+半径口径）
    val lastActivitySec: Long = 0L, // 最近活动（档案行"断更 N 天"）
    val recent7: Int = -1,        // 近 7 天条数（档案行"爆发"提示；-1=无金矿证据）
    val prev7: Int = -1,
    val bodyRadius: Float = DIM_RADIUS, // 体量主轴半径（App 渲染器/命中测试口径）
    val bodyAlpha: Float = 0.55f,       // 体量口径亮度（双源取强）
    val bodyColor: Int = TYPE_DIM,      // 体量口径颜色（R311 色彩引擎：黑体连续色温×构成微调）
    /** R311 连续色温（K；0=旧三档桶——实体天体）——渲染端芯/缘色由它连续导出 */
    val tempK: Float = 0f,
    // ---- R235 未探索星云原料（索引基因构成三票——星云类别色投票用） ----
    val gVideo: Int = 0,
    val gPhoto: Int = 0,
    val gAudio: Int = 0,
    // ---- R250 个人足迹（条目级看过条数）：信息卡「看过 N 条」单一数据源 ----
    val watchedCount: Int = 0,
    // ---- R317b 终态宣判注入（null=未宣判/缺据）：黑洞前史行（爆发巅峰/活跃寿命/
    //      停更至今）的原料——[TasteAstroPhysics.factLine] hist 参直供；缓存 v4
    //      载荷随星走（回读深等成立） ----
    val hist: TasteAstroPhysics.PublishFeatures? = null,
    // ---- R326 诞生侧注记（注记制——零新枚举：数据类字段而非 kind 制，幼态非新
    //      种类）：[TasteAstroPhysics.judgeBirthStates] 宣判产出（stage/
    //      planetsForming/probeItems/tagsCount/诞生史增强位）——**装配侧暂不接线**
    //      （wiring 归 R327 批 B：runLifeVerdictR325 诞生半场注记后置+缓存 v5 载荷
    //      随星走）；null=未宣判/缺据=默认零行为变化 ----
    val birth: TasteAstroPhysics.BirthFeatures? = null,
    // ---- R323 自行漂移注记（r315 J 章——注记制同 birth 模式）：[runDriftVerdictR323]
    //      漂移半场产出（两窗三票经 [ProperMotionFx.driftOf] 宣判——方向/强度/两窗
    //      原值）；null=未入池/缺据（任一窗<8 条）/零漂移（差<门「没动」也是结论）
    //      =零矢量零漂移行默认零行为；缓存 v7 载荷存两窗三票原值回读纯函数重导 ----
    val drift: ProperMotionFx.Drift? = null,
)

/** 一条无向连线：端点坐标已烘焙（布局只跑一次，渲染零查表）。
 *  双口径：x1/y1/z1/z2=海报口径（MDS）；sp*=R242 空间口径（App 渲染/命中消费） */
private data class StarLink(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val z1: Float,         // R147：端点深度（投影后连线天然带透视斜率）——海报口径
    val z2: Float,
    val spX1: Float = 0f,  // R242：空间口径端点（App 投影消费）
    val spY1: Float = 0f,
    val spZ1: Float = 0f,
    val spX2: Float = 0f,
    val spY2: Float = 0f,
    val spZ2: Float = 0f,
    val a: Long,           // 端点 chatId（a<b）：点星邻居高亮判定
    val b: Long,
    val alpha: Float,      // 透明度 ∝ 边权（对数归一）
)

/** 星图成品：布局后的星 + 连线 + 深度标尺（渲染、命中测试、海报导出共用） */
private data class TasteMapData(
    val stars: List<StarNode>,
    val links: List<StarLink>,
    val zSpan: Float,          // R147 深度标尺：星 z 已按它归一（±1 内为主）——海报口径（MDS z）
    val gridHalf: Float,       // R152 基准面网格半幅（世界单位，云团半径）——海报口径
    val gridZ: Float,          // R152 基准面所在深度（世界单位）——海报口径
    val totalWatchSec: Long,
    val todayTrail: List<Long> = emptyList(), // R206 今晚星轨：今日观看频道串（时间序、去连续重复、已在图）
    // ---- R223b 全宇宙版 ----
    /** 旧三源封闭池同序重算（watch>订阅>图谱补齐 300）——海报内容集合按它过滤，
     *  「现有静态版不变」红线的机制件（见 buildPosterData KDoc） */
    val legacyPosterIds: Set<Long> = emptySet(),
    /** 天体分档统计（左上统计胶囊/布局日志；空=装配失败态） */
    val kindCounts: Map<CelestialKind, Int> = emptyMap(),
    /** R234 星座轨迹：history.json 今日观看站点（时间升序、连续同频道去重、在图） */
    val todayStops: List<TodayStop> = emptyList(),
    // ---- R248 信息卡重设计 ----
    /** 相邻恒星关联账：chatId → [(对端, 合并边权)]（权重降序）——「相邻恒星 ★A
     *  （W 条关联）·点=飞过去」的数据源 */
    val neighborArcs: Map<Long, List<NeighborArc>> = emptyMap(),
    // ---- R249 星系物理 ----
    /** 全库正体量频道均值（条数——黑洞科普倍数行与奥本海默均值门的原料；0=无统计） */
    val meanItems: Float = 0f,
    /** 双星对（合并边权 top 互链对——「互相转发频繁」的代理证据；≤6 对） */
    val binaryPairs: List<BinaryPair> = emptyList(),
    // ---- R251 编年史四卷 ----
    /** 互联分类账：chatId → [(对端, 互转/互链/共现三类计数)]（total 降序）——
     *  「互联档案」卷「与频道A 互转 23 次 · 与频道B 共现 45 次」的数据源 */
    val neighborRels: Map<Long, List<NeighborRel>> = emptyMap(),
    /** R267 布局健康闸：true=本轮布局 θ 塌缩实锤、已回退纯哈希方位（球面展开保住、
     *  口味方向语义本轮牺牲）——退化品**绝不写成品缓存**（防钉死） */
    val layoutDegraded: Boolean = false,
    /** R267 数据源退化实锤：装配原料空/单值（批迁中间态/特征方差维不足）——
     *  调用方据此重试；退化品同样不落盘 */
    val sourceDegenerate: Boolean = false,
    /** R267 基因指纹（装配期 LayoutHealth.genomeFp 打包值）——缓存换装门原料 */
    val genomeFp: Long = 0L,
    /** R303c P2：涌现布局在场标记（true=sp 坐标=四力平衡位；false=V3 球面降级位）
     *  ——缓存头口径+温启初值合法性判定（上次涌现位的星图才作温启起点）+探针 */
    val emergent: Boolean = false,
    /** R317b：终态宣判已注入（true=kinds 已按宣判矩阵迁移+hist 注入+kindCounts
     *  扩容——缓存 v4 尾口径：复用门=零 IO 跳过重查；false=宣判未完成/未跑） */
    val verdictApplied: Boolean = false,
)

/** R248 相邻恒星条目（信息卡）：对端 chatId + 合并边权（关联证据条数） */
private class NeighborArc(val chatId: Long, val weight: Int)

/**
 * R251⑥ 互联分类账条目（编年史「互联档案」卷）：对端 chatId + 三类合并计数——
 * 互转（forward_src/dst 转发边）/互链（link+comment 引用边）/共现（co_watch 同会话
 * 观看序列）；total=合并边权（与 [NeighborArc.weight] 同口径——两类账同一证据源）。
 * 传播计数 forwardCount 是 W6 未入库字段——分类账不冒充传播数据（R246 §3.4 挂账）。
 */
internal class NeighborRel(val chatId: Long, val forward: Int, val link: Int, val coWatch: Int) {
    val total: Int get() = forward + link + coWatch
}

/**
 * R249 双星对（星系物理）：两端 chatId + 合并边权（「互相转发频繁」的代理证据）；
 * R321 洛希/拉格朗日附加账（默认 0=缺据诚实降级，不冒充）：
 * [sharedW]=同源证据 W 条（PairEvidence sharedDirected+sharedCoOccur——潮汐预警行
 * 原料）；[trojanW]=共源上游 W 条（DiscoveryGraph forward_src 交集——特洛伊簇
 * 亮度/特洛伊群行原料，0=无共源零尘埃）。
 */
private class BinaryPair(
    val a: Long,
    val b: Long,
    val weight: Int,
    val sharedW: Int = 0,
    val trojanW: Int = 0,
)

/** R321 双星对附加账（装配 IO 期与 R283 增强环同域产出——对齐增强候选对） */
private class BinaryPairExtra(val a: Long, val b: Long, val sharedW: Int, val trojanW: Int)

// ==================== 页面 ====================

/**
 * 口味星图页（R143 落地 / R152 数据可视化重做 / R152b 终版定稿 / **R206 自由深空
 * 2.0** / **R223b 全宇宙一期** / **R231 纵深空间+飞行手势**，ideas.md #14）：把每个
 * 频道拼成**高维口味向量**，MDS 降维映射进 3D 空间渲染成可在其中**自由飞行**的
 * **数据点云**。
 * （用户方向纠偏原话："不是这种的，非得搞一个星系漩涡背景，我理解更像一簇高维向量
 * 映射到三维空间"——R147/R151 的星系审美假旋臂/星云/尘埃带全部删除；R152b 按用户
 * 追加回归**背景星点**：深空星点背景 + 数据点云前景两个层次，克制到明显是装饰。）
 *
 *  - **R231 纵深空间+飞行手势**（用户两实锤：①天体全在平面上没立体感②进不去
 *    纵深——用户原创手势方案）：z 轴从 MDS 第三维（λ3 塌缩=薄层）重建为**三带
 *    纵深分布**（TasteDepthSpread 纯函数：近/中/远三带+带内散布+椭球渐缩，XY
 *    聚类结构一概不动，MDS z 留给海报=双口径零回归）；相机加**独立纵深轴
 *    dive**（dist_eff=distOf(zoom)−dive 可过零=穿越云团「被星包围」）；**飞行
 *    手势=一指按住不动+另一指上下移**（FlightGestureMachine 纯状态机判定：锚指
 *    <8dp+动指纵向显著→纵深模式，上推=前进/下拉=后退；与捏合/平移一锁定制死
 *    互斥；两指捏合=缩放/两指一起移=平移照旧走 gestureStep——R210 红线）；
 *    **双击天体=运镜飞向它**（τ=0.9s 穿越星域的电影感长运镜）；**透视增强**
 *    （sizeOf=persp^1.45 远星明显小近星大+FOV 随 dive 动态广角=速度感）。
 *
 *  - **R223b 全宇宙（一期）**：节点池从「看过/订阅/图谱」三源封闭岛（上限 300）
 *    打开成**深索引全宇宙**——meta 全表 ∪ 金矿快照 ∪ DiscoveryProfile ∪
 *    SearchEntity ∪ watch/sub（全部本地只读，零 Search 族，R184 红线），恒星系
 *    天体 600±、脉冲星（bot）/星团（群组）十量级、流星（真人用户）个位数量级；
 *  - **六态天体笔刷**（TasteGalaxyClassifier 纯函数分档，判据全本地）：恒星（活跃
 *    <60 天）/原恒星（首见<90 天，吸积壳）/红巨星（top30% 体量+低活跃，膨胀暖晕）
 *    /白矮星（断更>180 天，小而极亮）/超新星（recent7>2×prev7 爆发环）/脉冲星
 *    （周期闪灭双束）+星团（成员尘点云）+流星（星系外围游荡）——**状态可随时间
 *    迁移（重算即迁移），「星系活着」**；缺据一律按恒星诚实缺省（不冒充特殊天体）；
 *  - **布局 = MDS 全量扩容 + 互链弹簧微扰**（默认开，设置页可关）：Torgerson+
 *    SMACOF 管线一行不改，MDS 收敛坐标为初值叠加固定 20 轮弱弹簧（劲度 0.15）+
 *    最小间距斥力（TasteGalaxyPerturb 纯函数；固定轮数+固定初值=确定性不破，z 深度
 *    轴不动）——相连频道聚到邻域而不塌缩，口味簇结构保留；
 *  - **LOD 三保险**（600+ 节点 60fps 预算件，渲染器实施）：屏外剔除 / 微星合并
 *    （投影半径<1.5px 的频道天体按量化色分桶并入单遍 drawPoints）/ 分批 3×200
 *    波次淡入；
 *  - 特征向量（每频道 [FEAT_DIMS] 维，数据全现成零网络）：内容构成（视频/图文/音频
 *    占比：观看秒数构成 > 深度索引基因构成 > 内存条目投票三级回落）+ 时长风格（索引
 *    平均时长）+ 观看行为（累计时长对数、观看新鲜度、互动次数对数）+ 体量（索引条数
 *    对数）+ 订阅位 + 频道规模；分块加权后单位化，余弦距离近的频道 = 口味相近；
 *  - 布局 = 纯数学降维（Kotlin 本地实现，确定性，同数据同布局绝不跳位）：距离矩阵
 *    O(n²) 一次算 → 经典 MDS（Torgerson 双中心化 + 幂迭代取
 *    前三特征向量）给初值 → SMACOF 应力精修（Guttman 变换 60 轮单调收敛）→
 *    居中归一到世界坐标。**位置由真实数据结构决定，不是摆造型**：同类频道聚簇、
 *    相异远离、混合口味的散在簇间——用户能读出"我的口味分布"；
 *  - **相机 = R206 自由飞行**（替换 R147b 轨道相机——用户病灶定论"要无尽深空探索
 *    的感觉"）：世界系相机质点 (camX, camY, −dist) 沿 +z 观察，**拖拽=空间平移**
 *    （星野在指尖滑走，中深度跟手、近星快远星慢=z 视差——深空感核心）、**双指=俯冲/
 *    拉远**（dolly^0.35+lens^0.65 复合，表观倍率=zoom；俯冲时视差增强）、双指旋转=
 *    滚转；**松手惯性滑行**（速度按 0.94^(dt/16.7) 衰减——"飘在太空里"的手感）；
 *    星系是无限空间中的一个天体，没有固定中心锚。投影/雾/取景纯数学收口
 *    core.tastemap.TasteFlight（单测 24 例锚定）；
 *  - **全套特效（R206，全 Canvas 自绘零布局侵入）**：①创世纪（时间轴拖动=星系
 *    从虚无逐颗凝聚，星生序=频道首见时间：索引最早条目 MIN(dateEpochSec)×既有多列
 *    GROUP BY 顺带 + 首次观看事件，双源取早；无证据星最后诞生）②连线流光（互链边
 *    上按边权分布的流动光点）③星系呼吸（星晕 chatId 相位脉动 + 背景星云三团缓转）
 *    ④彗星=爬虫（轮询 search.db MAX(rowid) 差分，O(1) 只读；索引增长→彗星飞过，
 *    新频道→在图星爆闪）⑤今晚星轨（今日观看=流星串光路：星→星世界坐标飞行+尾迹）
 *    ⑥点星反馈（粒子爆散+相机推进惯性运镜，原点开链路保留）⑦在播频道=当前最亮星
 *    （灯塔双光柱旋转扫掠）。特效引擎 FxEngine 帧驱动+对象池+确定性哈希（单测锚定），
 *    渲染器=R345 起 GL 唯一引擎 TasteGlRenderer（R281 真球/真环/后处理全家——Canvas 老 TasteFlightRenderer 已整类退役；GL 不可用走诚实降级诊断件）；
 *  - 点星信息卡 = R147 直接保留 + **R223b 天体档案段**（天体类型/存量/断更或爆发
 *    时长/tier 验货徽章——「按本地索引观测」诚实口径）：观看时长对比条（vs TOP1）/
 *    最近互动标记（点过赞/收藏/广场弹幕）/ 频道基因一行（R133 ChannelGenome）/
 *    互链邻居预览（可点跳选）→ 试吃入口（未加入频道照常可试吃）；
 *  - 海报 = "我的口味星图 · N 个频道"整图 + 图例 + 品牌条，ShareCard.generateTasteMap
 *    渲染 1080×1440 后走 ShareCard 的 MediaStore 链存公共相册（**R223b 零回归**：
 *    内容集合仍=旧三源封闭池同序重算（legacyPosterIds）、半径/亮度/颜色走旧观看
 *    口径——ShareCard 代码与「两相机两语义」边界零动；全宇宙节点坐标随 MDS 扩容
 *    更新属同一布局引擎的固有全局性，口味簇结构同构）。
 *
 * 数据全本地零网络：watch_events（观看账）+ likes/收藏/广场快照（互动账）+ 订阅表 +
 * 深度索引（search.db 只读聚合：meta 全表/items 基因）+ 金矿快照（6h TTL 陈值先回）
 * + DiscoveryProfile（1h TTL 类型化）+ SearchEntity（bot 档案）+ 图谱快照（真实
 * 网关才有；演示模式无金矿/类型化实体，星只剩看过/订阅的，照样成图——诚实降级）。
 * 从"我的"页进入，状态驱动路由。
 */
@Composable
fun TasteMapScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    // 点星试吃：切到该频道单频道 Feed（宿主接线与发现页候选卡同一条链）
    onOpenChannel: (ChannelBrief) -> Unit,
    // ---- R233 二期参数（全部默认值=旧调用行为不变） ----
    /** 从 feed 长按「在星图中查看」跳入的一次性运镜意图：进页装配完成后运镜飞向
     *  该条目所属恒星（R231 双击运镜同款 τ=0.9s 穿越星域；星不在图=诚实 Toast） */
    flyTargetChatId: Long? = null,
    /** 运镜意图消费回调（宿主清一次性意图容器——timeMachineIntent 同模式收口） */
    onFlyConsumed: () -> Unit = {},
    /** 点行星→R222 双选「时间轴探索」：容器路由开 hashtag 时间轴页。
     *  R269 触发收窄：画布点行星已改内容流直刷——本回调现由飞进行星门弹层的
     * 「时间轴探索」行触发（时间轴页=层内二级入口，任务书口径保留） */
    onOpenHashtagTimeline: (String) -> Unit = {},
    /** 点行星→R222 双选「搜索播放」：旧行径（「标签：#tag」源混合双腿开刷）+回星图
     *  返回语义——宿主收口（试吃 onOpenChannel 同款链）。R269 触发收窄同上行注释 */
    onHashtagSearchPlay: (String) -> Unit = {},
    /** 点尘埃→直接播放该条（batch=该 tag 样本条目，R222b 无预复活口径由宿主编排） */
    onPlayDust: (item: dev.tgfeed.core.td.VideoItem, batch: List<dev.tgfeed.core.td.VideoItem>, label: String) -> Unit = { _, _, _ -> },
    /** R269 行星内容流：点行星（画布/信息卡 tag 云）→ 该 tag 全宇宙内容流直刷
     * （纯本地 tag_items 倒排+LIKE 兜底——任务书口径；宿主切源+关图+回星图返回语义，
     *  与 onHashtagSearchPlay 同款链换 playTagContent 源）。飞进行星门仍弹双选层
     * （其首行「看内容·直接开刷」=同一回调——飞行接近意图不如点按明确，弹层可
     *  取消留图；时间轴探索=层内二级入口保留） */
    onPlayTagContent: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    // 系统返回键 = 返回我的页（状态驱动路由，不拦会退到桌面）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（观看账/订阅/内存条目同源）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val scope = rememberCoroutineScope()

    // ---- R316c：状态声明区（原「数据与视图状态」→「R248 信息卡状态」全段
    //      250 行）整体迁 [tasteGalaxyStateR316c] 状态袋（寄存器预算收尾主承重
    //      刀——语句原样搬移）；主方法仅解包后段直用句柄（委托读写法零变化），
    //      ctx 构造改读袋字段 a.X（值同一对象——R316b 调用点接线同款） ----
    val a = tasteGalaxyStateR316c(app, context, flyTargetChatId)
    val mapDataState = a.mapDataState
    var mapData by mapDataState
    val selectedIdState = a.selectedIdState
    var selectedId by selectedIdState
    val loadPhaseState = a.loadPhaseState
    var loadPhase by loadPhaseState
    val canvasSizeState = a.canvasSizeState
    var canvasSize by canvasSizeState
    val knownIdsState = a.knownIdsState
    var knownIds by knownIdsState
    val playingChatIdState = a.playingChatIdState
    var playingChatId by playingChatIdState
    val worldDiveTagState = a.worldDiveTagState
    var worldDiveTag by worldDiveTagState
    val spreadTagState = a.spreadTagState
    var spreadTag by spreadTagState
    val showGuideState = a.showGuideState
    var showGuide by showGuideState
    val achievementState = a.achievementState
    var achievement by achievementState
    val lastShareCountState = a.lastShareCountState
    var lastShareCount by lastShareCountState
    val starlightBookState = a.starlightBookState
    var starlightBook by starlightBookState
    val engine = a.engine
    val voyageFile = a.voyageFile
    val discoverFile = a.discoverFile
    val sessionLit = a.sessionLit
    val entryFlash = a.entryFlash
    val diveFlash = a.diveFlash
    val guidePrefs = a.guidePrefs
    val tourPrefs = a.tourPrefs
    val universeDirector = a.universeDirector
    val universeEventsFile = a.universeEventsFile
    val directorProbeAnchor = a.directorProbeAnchor
    val wormholePath = a.wormholePath

    // ---- R223b 装配 + MDS 降维嵌入 + 互链微扰（进页一次；读盘/索引聚合/金矿/类型化
    //      实体在 IO，O(n²) 矩阵与迭代+20 轮微扰在 Default——金矿快照陈值先回、
    //      DiscoveryProfile 1h TTL 缓存命中=零成本；两者未命中时本轮按缺据诚实降级
    //      （恒星缺省/无实体天体），后台单飞重建后下次进页补齐）。
    //      **R254 三段式装载**（分钟级→秒级，P0 性能刀）：
    //      ① 缓存命中=直接装填成品星图（磁盘二进制毫秒级，用户立刻看到星点）；
    //      ② 全量重建照跑（首开=用户可见阶段文案；缓存命中=后台静默——重建期间
    //      用户已在看星图）；③ 重建结果原子落盘供下次秒开；±5% 频道数换装门
    //      （门内=沿用缓存视图只更新缓存文件=星图零跳动；门外=失真到必须换装）。
    //      缓存口径陈值无害与金矿 6h TTL 同族（重建每次进页都跑、文件每次都新）。
    //      装载体收口在顶层挂起函数 runGalaxyLoadR254（大方法字节码瘦身——
    //      composable 内只留一条薄调用，JIT/LMK 压力同步下降） ----
    LaunchedEffect(gateway) {
        runGalaxyLoadR254(
            gateway, vm, app,
            setMap = {
                mapData = it
                selectedId = null
            },
            setPhase = { loadPhase = it },
            // R328：导演器句柄透传（薄调用面零逻辑——跃迁入队归顶层件）
            director = universeDirector,
        )
    }

    // ---- R248 已知星集合装载（点亮机制——开图即已知的星不匿名）：持久账本 ∪
    //      已看过 ∪ 已订阅 ∪ 特殊天体（bot/群组/私聊=用户自己的会话，天生已知）。
    //      持久账本只记「飞近点亮」的新发现（已知三源动态判定——落盘会陈旧不落）。
    //      R254：装载体收口顶层 [loadKnownStarsR248]（大方法字节码瘦身） ----
    LaunchedEffect(mapData) {
        val d = mapData ?: return@LaunchedEffect
        if (d.stars.isEmpty()) return@LaunchedEffect
        knownIds = loadKnownStarsR248(d, discoverFile, sessionLit)
    }

    // ---- R301 跨页呼应（design-dual-form §2.2 可选件=终审批准「做」）：feed 舷窗
    //      看过的星，本次进图一次脉动欢迎（R282 导演器 flashStar 原语直喂——引擎
    //      直调族同 executeDirectiveR282；账本一次性消费=同星下次进场不再欢迎，
    //      直到舷窗再看它；7 天 TTL 内有效）。装配+已知装载就绪后跑一次（欢迎
    //      不是每帧语义）；refreshLitTitles 的 mapData 拷贝发布由 [welcomedOnce]
    //      闸挡住不重复欢迎 ----
    var welcomedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(mapData, knownIds) {
        val d = mapData ?: return@LaunchedEffect
        if (welcomedOnce || knownIds == null || d.stars.isEmpty()) return@LaunchedEffect
        welcomedOnce = true
        val inMap = d.stars.mapTo(HashSet()) { it.chatId }
        val welcomes = withContext(Dispatchers.IO) {
            runCatching { GalaxyPortholeLoader.consumeWelcomes(app, inMap) }.getOrDefault(emptyList())
        }
        welcomes.take(6).forEach { engine.flashStar(it, 3.5f) }
        if (welcomes.isNotEmpty()) {
            android.util.Log.i(
                "TasteMapX",
                "R301 跨页呼应: 舷窗看过 ${welcomes.size} 颗——脉动欢迎（尾号${welcomes.take(6).joinToString(",") { ((it % 10000L + 10000L) % 10000L).toString() }}）",
            )
        }
    }

    // ---- R235① 成就评定（纯本地统计——零 Search 族；等阶=飞船酷度的单一驱动源）：
    //      发现频道=在图频道恒星数 / 观看=watch_events 全量 / 收藏=内存收藏表 /
    //      探索 tag=你消费过的内容（历史+收藏+稍后再看+当前条目）itemTags 去重 /
    //      分享=航行账本计数 / 六态=星图天体分档键集。mapData 变或分享计数变重评。
    //      R254：评定体收口顶层 [evaluateAchievementsR254]（大方法字节码瘦身）；
    //      R316：本局部委托亦迁顶层 [evaluateAchievementsR316]（二次瘦身） ----
    LaunchedEffect(mapData) {
        if (mapData == null) return@LaunchedEffect
        val shareCount = withContext(Dispatchers.IO) {
            runCatching { TasteVoyageLog.load(voyageFile).shareCount }.getOrDefault(0)
        }
        evaluateAchievementsR316(vm, mapDataState, achievementState, knownIdsState, lastShareCountState, shareCount)
    }
    // R248：已知星装载完成即重评（点亮数从 0 缺省到真值）
    LaunchedEffect(knownIds) {
        if (knownIds == null) return@LaunchedEffect
        evaluateAchievementsR316(vm, mapDataState, achievementState, knownIdsState, lastShareCountState, lastShareCount)
    }
    // 飞船特效档（成就等阶驱动——「勋章越高飞船越酷」）
    // R302：等级地板取高不回收（Lv3 跃迁波纹/Lv8 传奇涂装预尝——等阶五阶仍是
    // 身份天花板，等级给「先尝到」；effectiveShipStyle 单口）
    val shipStyle = remember(achievement, starlightBook) {
        TasteStarlight.effectiveShipStyle(
            TasteAchievements.shipStyleOf(achievement?.rank ?: TasteAchievements.Rank.BRONZE),
            starlightBook?.level ?: 1,
        )
    }

    // ---- R233 行星系统惰性装配（L2 引力井触发；深索引只读公共口 channelItemsPage
    //      ——索引导航回表恒 ≤240 行，与频道总量无关；样本条目留在条目里：尘埃环
    //      相位与点尘埃播放批共用一份数据零二次查询）。
    //      R236：行星排序/计数换 tags 表 topTags 账本（全频道历史——治 r233「近端
    //      样本偏差」挂账）；尘埃环/播放批仍近端样本；关联弧指纹同步账本口径
    //      （fold(display)——与 tags.tag 同键）。ledger 空（回填窗口期/无 tag 频道）
    //      回退样本聚合（R233 原版行为，不断层）。R254：本函数已整体迁出为顶层
    //      [assemblePlanetSystemR254]（大方法字节码瘦身——语句原样，零行为变化） ----

    // ---- R248 空名补捞（发现时刻的标题竞速修复——类问题：装配标题拼表是时点
    //      快照，活图/金矿在装配后才到账的频道名会让「星名浮现」饿死成无名发现。
    //      发现时对空名星用活图∪金矿现查回填 mapData（拷贝发布——场景/弧/星云
    //      随 remember(mapData) 重建；sessionLit 兜住已知装载 effect 重跑的竞速窗）。
    //      R254：本函数已整体迁出为顶层 [refreshLitTitlesR254]（大方法字节码瘦身） ----

    // ---- R233 井深比分档轮询（400ms 一拍，非逐帧）：近焦星/尘埃星判定 + 井内
    //      但未装配的星惰性入队 + 大频道指纹预取（关联弧主现场）+ 焦点星按需现算 ----
    // R258：planetHitPx/arcHitPx 命中像素常量随迁 TasteMapCanvasLayer（唯一消费位）
    // 全池最大体量（井半径归一分母）与频道类恒星表（行星/指纹适用域）
    val channelStars = remember(mapData) {
        mapData?.stars?.filter {
            it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
        }.orEmpty()
    }
    val wellMaxVol = remember(channelStars) { channelStars.maxOfOrNull { it.itemsCount } ?: 0 }
    // 轨道点暂存（飞进行星门/命中测试共用——帧零分配）
    val orbitScratch = remember { FloatArray(3) }
    // ---- R249 v2 N 体物理场（**声明位在轮询 effect 之前**：轮询/tapStar/行星
    //      几何全消费它的当帧渲染位——「位置单一来源」；scene 与 binaryChatIds
    //      同源 mapData=同序对齐。mutableStateOf 委托（cam 同模式）：帧循环
    //      LaunchedEffect(Unit) 捕获首帧绑定——委托活读保证换场后读到新场对象） ----
    val scene = remember(mapData) { buildFlightScene(mapData, engine) }
    val binaryChatIds = remember(mapData) {
        val pairs = mapData?.binaryPairs ?: emptyList()
        LongArray(pairs.size * 2) { k ->
            val p = pairs[k / 2]
            if (k % 2 == 0) p.a else p.b
        }
    }
    val physicsState = remember { mutableStateOf<TasteGalaxyPhysics?>(null) }
    var physics by physicsState
    LaunchedEffect(scene, binaryChatIds) {
        physics = TasteGalaxyPhysics.build(scene.stars, binaryChatIds)?.also { p ->
            p.step(0f) // 初值步（t=0=初始相位；探针随步出「N体」首行）
        }
    }
    LaunchedEffect(physics) {
        while (true) {
            delay(TasteGalaxyPhysics.TICK_MS)
            physics?.step(engine.timeSec)
        }
    }
    // 井深比/发现的当前位暂存（N 体渲染位——轮询零分配）
    val pollPos = remember { FloatArray(3) }
    // ---- R254 逻辑域句柄（状态对象引用容器——轮询/装配/召回/运镜逻辑体迁出主
    //      方法后的统一接线；每次重组重建=纯引用零语义） ----
    val logicCtx = GalaxyLogicCtx(
        vm = vm,
        gateway = gateway,
        app = app,
        context = context,
        scope = scope,
        engine = engine,
        mapData = mapDataState,
        canvasSize = canvasSizeState,
        cam = a.camState,
        lens = a.lensState,
        knownIds = knownIdsState,
        revealIds = a.revealIdsState,
        physics = physicsState,
        selectedId = selectedIdState,
        nearFocusId = a.nearFocusIdState,
        dustChatId = a.dustChatIdState,
        lockChatId = a.lockChatIdState,
        planetSysSnapshot = a.planetSysSnapshotState,
        tagProfiles = a.tagProfilesState,
        planetAssembling = a.planetAssemblingState,
        inWellIds = a.inWellIdsState,
    complexesR385 = a.complexesR385State,
        planetRefuseLog = a.planetRefuseLog,
        flyIntoArmed = a.flyIntoArmedState,
        planetChoiceTag = a.planetChoiceTagState,
        genesisOn = a.genesisOnState,
        playingChatId = playingChatIdState,
        spreadModel = a.spreadModelState,
        spreadLoading = a.spreadLoadingState,
        fling = a.fling,
        focusFlight = a.focusFlight,
        tour = a.galaxyTour,
        reveal = a.reveal,
        planetLru = a.planetLru,
        sessionLit = sessionLit,
        discoverFile = discoverFile,
        orbitScratch = orbitScratch,
        pollPos = pollPos,
        // R258 视图层句柄（拆出的布局 composable/逻辑函数统一接线——只加不改）
        // R345：renderer 句柄随 Canvas 渲染器整类退役拆除
        shipEngine = a.shipEngine,
        wormhole = a.wormhole,
        wormholeMenuTag = a.wormholeMenuTagState,
        wormholeFromChat = a.wormholeFromChatState,
        genesis01 = a.genesis01State,
        genesisPlaying = a.genesisPlayingState,
        genesisSpeedIdx = a.genesisSpeedIdxState,
        constellationOn = a.constellationOnState,
        frameTick = a.frameTickState,
        onLits = {
            evaluateAchievementsR316(vm, mapDataState, achievementState, knownIdsState, lastShareCountState, lastShareCount)
        },
        // R301 世界卡句柄
        worldCard = a.worldCardState,
        worldCam = a.worldCam,
        // R302 星辉句柄（只加不改）
        starlightFile = a.starlightFile,
        starlightBook = starlightBookState,
        levelUpEvent = a.levelUpEventState,
        guidanceLine = a.guidanceLineState,
        beaconPos = a.beaconPosState,
        nebulaCellsByChat = a.nebulaCellsState,
        warpCruise = a.warpCruiseState,
        witnessSession = a.witnessSession,
        planetLitSession = a.planetLitSession,
        litToast = a.litToastState,
        // ---- R316 二次拆分句柄（只加不改） ----
        achievement = achievementState,
        showAchievements = a.showAchievementsState,
        sessionSubscribed = a.sessionSubscribedState,
        exporting = a.exportingState,
        exportingVoyage = a.exportingVoyageState,
        showGuide = showGuideState,
        worldDiveTag = worldDiveTagState,
        lastShareCount = lastShareCountState,
        spreadTag = spreadTagState,
        voyageFile = voyageFile,
    )
    // ---- R302 星辉装载（已知星就绪=点亮口径稳定后三源重算一次；此后点亮/虫洞/
    //      目击各拍各自刷新——重算即迁移，等级永远是账本的纯函数） ----
    LaunchedEffect(mapData, knownIds) {
        if (mapData == null || knownIds == null) return@LaunchedEffect
        refreshStarlightR302(logicCtx, wellMaxVol)
    }
    // ---- R282 导演器场景排程（账本热身→候选筛选→散布排程；换场重跑=重排） ----
    LaunchedEffect(scene) {
        directorSceneSetupR282(logicCtx, universeDirector, universeEventsFile)
    }

    // ---- R233 井深比分档轮询（400ms 一拍，非逐帧）：近焦星/尘埃星判定 + 井内
    //      但未装配的星惰性入队 + 大频道指纹预取（关联弧主现场）+ 焦点星按需现算。
    //      R254：轮询体已整体迁出为顶层 [pollGalaxyBeatsR254]（大方法字节码瘦身） ----
    LaunchedEffect(mapData, canvasSize) {
        val d = mapData ?: return@LaunchedEffect
        if (d.stars.isEmpty()) return@LaunchedEffect
        pollGalaxyBeatsR254(logicCtx, channelStars, wellMaxVol)
    }

    // ---- R234① 尘埃带召回（选 tag → 毫秒级倒排检索 → 传播模型聚合）：
    //      R236 换轨：深索引 tags 表倒排公共只读口 deepIndex.tagItems（v9 tag_items
    //      元素级倒排+rowid 回表——LIKE 设备旧路 3-8 分钟→毫秒级，治 r234 挂账
    //      「尘埃带召回慢路径实测量级」）；R228 item_tags 元素级精确口径不变
    //      （TasteTagSpread.build/hasTag 原样）；换 tag/清空即取消（LaunchedEffect 键）。
    //      R254：召集体已迁出为顶层 [spreadTagRecallR254]（大方法字节码瘦身） ----
    LaunchedEffect(spreadTag) {
        spreadTagRecallR254(logicCtx, spreadTag)
    }

    // ---- 首次成图 + 视口就绪：初见方向（常看星的加权质心方向）+ 播揭示 ----
    // R242 球面宇宙：不再有「取景缩放」（全向相机+真透视自然取景）；初见方向=
    // 观看时长加权的星域质心方向（首屏看向你的核心口味星域——近壳常看星为主）。
    // R254：定向体已迁出为顶层 [firstViewAimR254] ----
    LaunchedEffect(mapData, canvasSize) {
        firstViewAimR254(logicCtx)
    }

    // ---- R233④ 从 feed 跳入：一次性运镜意图（装配/取景就绪后执行；双击运镜
    //      同款 τ=0.9s 穿越星域；星不在图=诚实 Toast 不硬飞）。**声明位在
    //      取景 effect 之后**：同一轮 key 变更里取景先跑（重置 cam/focusFlight），
    //      本 effect 后跑（写入运镜目标）——顺序反了会被取景重置杀掉运镜。
    //      R254：运镜体已迁出为顶层 [flyTargetJumpR254] ----
    LaunchedEffect(flyTargetChatId, mapData, canvasSize) {
        val target = flyTargetChatId ?: return@LaunchedEffect
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        flyTargetJumpR254(logicCtx, target, onFlyConsumed, wellMaxVol)
    }

    // ---- R271 开局引导巡航触发（新用户带飞——与 v3 引导浮层不叠加：浮层=文字教学
    //      在屏时不开；浮层关闭（showGuide→false）本 effect 重跑自然衔接带飞。
    //      频控=AppPrefs taste_tour_count（≤2 次）+星数增长基线；启航延迟 1.8s
    //      让揭示动画播完；窗内任何手势/手动运镜/feed 跳入即刻取消（让位单口=
    //      abortGalaxyTourR271）。R271：触发体收口顶层 [maybeAutoStartGalaxyTourR271]） ----
    LaunchedEffect(mapData, knownIds, showGuide) {
        maybeAutoStartGalaxyTourR271(logicCtx, tourPrefs, showGuide, flyTargetChatId)
    }

    val stars = mapData?.stars.orEmpty()
    // R316b：links/selectedStar 派生随迁 TasteMapStageR316（单消费位——语句原样）
    // 邻居表（R248 信息卡「相邻恒星」：chatId → [(对端星, 合并边权)] 权重降序——
    // 「★频道A（12 条关联）· 点=飞过去」的数据源）
    val starsById = remember(stars) { stars.associateBy { it.chatId } }
    // R316b：binaryByChat 派生随迁 TasteMapStageR316（单消费位——语句原样）

    // ---- R206 渲染场景（一次性构建）：星按 z 降序静态预排（飞行相机深度=z+dist
    //      对 z 单调——画家序与平移/缩放/滚转无关，零逐帧排序）；流光/流星路线
    //      装进引擎；创世纪量程从星生序求出（**R249 v2：scene/物理场声明已前移
    //      到轮询 effect 之前**——位置单一来源的消费序要求） ----
    // R316b：genesisSpan 派生随迁 TasteMapStageR316（单消费位——语句原样）
    // R258：genesisDateFmt 随迁延时控制件（R356 收纳换代更名——旧件四枚整体退役）；todayStartMs/nowMs/
    // spreadCursorMs/showConstellation 随迁 TasteMapCanvasLayer；genesisStats 随迁
    // TasteMapStatsLegend（均为单消费位派生量——大方法字节码瘦身，语义零变化）

    // R316b：spreadRender 派生随迁 TasteMapStageR316（单消费位——语句原样）

    // ---- R206 帧循环（60fps 目标）：withFrameNanos 每拍推进 惯性/焦点运镜/创世纪
    //      自动播放/粒子引擎，并 bump 帧时钟（draw 相位失效——composition 不逐帧重组）。
    //      页面离开即随 LaunchedEffect 取消 ----
    LaunchedEffect(Unit) {
        // R316c：帧循环体整体迁顶层 [galaxyFrameLoopR316c]（寄存器预算收尾——
        //      语句原样，状态经 ctx 句柄；本 effect 捕获收窄为 9 参薄调用）
        galaxyFrameLoopR316c(
            logicCtx, stars, starsById, wellMaxVol, shipStyle,
            universeDirector, universeEventsFile, directorProbeAnchor, wormholePath,
        )
    }

    // R258：命中测试四件（tapStar/tapPlanet/tapDust/tapArc）+ 关联弧派生 remember +
    // 密度像素常量/投影暂存 + 运镜函数族（zoomBy/flyToStar/flyToNearest/flyToRandom/
    // backToOverview/startWormhole）全部迁出主方法——TasteMapCanvasLayer 局部函数
    // 与顶层 R258 函数（大方法字节码瘦身主承重件，语句原样零行为变化）

    // ---- R206 彗星=爬虫：轮询深索引 rowid 差分（MAX(rowid) O(1)、新行 SELECT DISTINCT
    //      有界只扫增量；OPEN_READONLY WAL 与 loadIndexGenomes 同款纪律——绝不建库
    //      绝不写库）。索引增长→彗星飞过；新爬到的频道在图→星爆闪（TTL 2.5s）----
    LaunchedEffect(mapData) {
        val dbFile = File(File(app.filesDir, "deepindex"), "search.db")
        if (!dbFile.isFile) return@LaunchedEffect
        var lastRow = -1L
        var lastRefsRow = -1L // R266 v11：refs 游标（纯转发流量补盲）
        while (true) {
            delay(COMET_POLL_MS)
            val res = withContext(Dispatchers.IO) {
                runCatching { pollDeepIndexCrawl(dbFile, lastRow, lastRefsRow) }.getOrNull()
            } ?: continue // 库被爬虫写事务短暂占锁等：本轮跳过，下轮再试
            val grew = (lastRow >= 0 && res.maxRowId > lastRow) ||
                (lastRefsRow >= 0 && res.refsMaxRowId > lastRefsRow)
            if (grew) {
                // R282：彗星的节奏与轨迹叙事收口宇宙导演器（节奏闸五门+排程+
                // 账本——引擎直调退场）；在图新频道=新星候选错拍随彗星入队
                // （「彗星掠过处新星点亮」的叙事序；同星冷却闸防轰炸）
                var onMapNew = 0
                for (chatId in res.newChats) {
                    if (chatId in scene.onMapIds && onMapNew < r282CrawlBuf.size) {
                        r282CrawlBuf[onMapNew++] = chatId
                    }
                }
                UniverseDirector.enqueueCrawl(
                    universeDirector, System.currentTimeMillis(), r282DirectorCfg,
                    (res.maxRowId % 100_000L).toInt(), r282CrawlBuf, onMapNew,
                )
                android.util.Log.i(
                    "TasteMapX",
                    "R282 爬虫脉冲: 索引行 $lastRow→${res.maxRowId} refs $lastRefsRow→${res.refsMaxRowId}" +
                        " 新频道=${res.newChats.size} 在图=$onMapNew（已报导演器——节奏闸接管发车）",
                )
            }
            lastRow = res.maxRowId
            lastRefsRow = res.refsMaxRowId
        }
    }

    // ---- R206 在播频道（灯塔）：轮询当前播放条目（items[currentIndex]，音频播放
    //      优先）——纯内存状态读，2s 一拍 ----
    LaunchedEffect(Unit) {
        while (true) {
            delay(PLAYING_POLL_MS)
            val cur = vm.items.value.getOrNull(vm.currentIndex.value)
            val audio = vm.nowPlayingAudio.value
            playingChatId = (audio ?: cur)?.chatId ?: 0L
        }
    }

    // ---- R316b：海报导出 onExport / 星轨折线 buildVoyageTrail / 航行日志导出
    //      onExportVoyage / 世界卡开关俯冲三件（openWorldCardR301/closeWorldCardR301/
    //      startWorldDiveR301）全部迁顶层——[exportTasteMapPosterR316] /
    //      [buildVoyageTrailR316] / [exportVoyagePosterR316] / [openWorldCardR316] /
    //      [closeWorldCardR316] / [startWorldDiveR316]（语句原样搬移、状态经
    //      GalaxyLogicCtx 句柄，零行为变化）；调用点收口舞台件 [TasteMapStageR316] ----

    // ---- R301 俯冲白闪推进（世界卡「直接开刷」置 worldDiveTag 触发）：白闪 320ms
    //      满帧=R269 内容流链回调点（接缝藏白闪；页切走后本组合消亡——snapTo 复位是
    //      防御写法，正常路径到不了） ----
    LaunchedEffect(worldDiveTag) {
        val tag = worldDiveTag ?: return@LaunchedEffect
        diveFlash.animateTo(1f, tween(WORLD_CARD_DIVE_MS))
        worldDiveTag = null
        onPlayTagContent(tag)
        runCatching { diveFlash.snapTo(0f) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        AppTopBar(title = "口味星图", onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            // ---------------- R316b：Box 内容整体迁出 [TasteMapStageR316]（深空画布→
            //      门控浮件→卡片/sheet/overlay 语句原样——二次瘦身主承重件，R258 模式
            //      照做；主方法只留 Column+AppTopBar+Box+一次调用，零行为变化） ----------------
            TasteMapStageR316(
                ctx = logicCtx,
                mapData = mapData,
                starsById = starsById,
                channelStars = channelStars,
                wellMaxVol = wellMaxVol,
                scene = scene,
                shipStyle = shipStyle,
                loadPhase = loadPhase,
                universeDirector = universeDirector,
                entryFlash = entryFlash,
                diveFlash = diveFlash,
                guidePrefs = guidePrefs,
                onPlayDust = onPlayDust,
                onOpenChannel = onOpenChannel,
                onOpenHashtagTimeline = onOpenHashtagTimeline,
                onHashtagSearchPlay = onHashtagSearchPlay,
                onPlayTagContent = onPlayTagContent,
            )
        }
    }
}


// ==================== R316c 三次拆分迁出件（主方法寄存器预算收尾） ====================
//
// 病灶：R316b 拆后主方法 units 已达标（9545≤10200）但寄存器 253>200 预算
// （R258 实测安全档 10201/200）——峰值主嫌疑=帧循环 LaunchedEffect 在主方法内
// 直接捕获 ~30 个局部状态（suspend lambda 构造点需同时物化全部捕获值）。本节
// 收尾刀：帧循环体整体迁顶层 [galaxyFrameLoopR316c]（R254 thin-effect 模式，
// runGalaxyLoadR254 同款）——语句原样搬移；状态经 [GalaxyLogicCtx] 句柄同名
// 委托/引用（var cam by ctx.cam 等委托读写法零变化，同一 MutableState 对象）。

/**
 * R316c 帧循环体（主方法 LaunchedEffect(Unit) 内 225 行整体迁出——语句原样）。
 * 前导=ctx 句柄同名局部委托/引用；wellMaxVol/shipStyle/stars/starsById/
 * universeDirector/universeEventsFile/directorProbeAnchor/wormholePath 九参直传
 * （原 lambda 捕获值，effect 创建同一时刻求值——零语义变化）。
 */
private suspend fun galaxyFrameLoopR316c(
    logicCtx: GalaxyLogicCtx,
    stars: List<StarNode>,
    starsById: Map<Long, StarNode>,
    wellMaxVol: Int,
    shipStyle: TasteAchievements.ShipStyle,
    universeDirector: UniverseDirector.State,
    universeEventsFile: File,
    directorProbeAnchor: LongArray,
    wormholePath: FloatArray,
) {
    // ---- R316c ctx 句柄同名局部前导（委托读写法零变化——同一 MutableState 对象） ----
    var cam by logicCtx.cam
    var physics by logicCtx.physics
    val genesisOn by logicCtx.genesisOn
    var genesisPlaying by logicCtx.genesisPlaying
    var genesis01 by logicCtx.genesis01
    val genesisSpeedIdx by logicCtx.genesisSpeedIdx
    val warpCruise by logicCtx.warpCruise
    var lockChatId by logicCtx.lockChatId
    val worldCard by logicCtx.worldCard
    var frameTick by logicCtx.frameTick
    val canvasSize by logicCtx.canvasSize
    val lens by logicCtx.lens
    // ---- R316c ctx 字段纯引用（同对象句柄） ----
    val fling = logicCtx.fling
    val focusFlight = logicCtx.focusFlight
    val worldCam = logicCtx.worldCam
    val galaxyTour = logicCtx.tour
    val wormhole = logicCtx.wormhole
    val shipEngine = logicCtx.shipEngine
    val engine = logicCtx.engine
    val pollPos = logicCtx.pollPos
    val scope = logicCtx.scope
    val voyageFile = logicCtx.voyageFile
    android.util.Log.i("TasteMap", "R242 探针: 帧循环启动 stars=${stars.size}（SpaceCam 空间相机）")
    android.util.Log.i("TasteMapX", "R242 探针: tag变体（TasteMap 被滤则只出本行）stars=${stars.size}")
    var lastNs = -1L
    var lastSpeedBucket = 0 // R235 尾迹探针的档位持有（0=<0.5 / 1=≥0.5）
    var warpCruiseSpeed = 0f // R302 曲速巡航速度（指数趋近满速——启动不踹脸）
    var flingWasLive = false // R268b 惯性探针：惯性接管沿（初速行）与停稳行的状态沿
    var flingProbeAt = 0L // R268b 惯性探针：120ms 节流
    val arriveProj = FloatArray(4) // R235 虫洞到达爆散的投影暂存（帧循环局部——projPt 声明在后方不可捕获）
    val voyageAim = FloatArray(2) // R342 航行停船位朝向暂存（帧循环局部一次分配）
    while (true) {
        withFrameNanos { ns ->
            val dtMs = if (lastNs < 0) 16.7f else ((ns - lastNs) / 1_000_000f).coerceIn(4f, 48f)
            val dtSec = dtMs / 1000f
            lastNs = ns
            // 惯性滑行（松手 >110ms 后接管；手势期速度只记录不应用）
            // R242：角速度（转头）+飞行速度（沿视线）双通道
            // R268b：接管延迟/停机阈/衰减曲线全收口 GestureFeelPolicy
            val upMs = android.os.SystemClock.uptimeMillis()
            val idle = upMs - fling.at > GestureFeelPolicy.FLING_TAKEOVER_IDLE_MS
            if (idle && (abs(fling.yawVel) > GestureFeelPolicy.FLING_EPS_DEG ||
                    abs(fling.pitchVel) > GestureFeelPolicy.FLING_EPS_DEG ||
                    abs(fling.flyVel) > GestureFeelPolicy.FLING_EPS_UNITS)
            ) {
                cam = TasteSpace.drift(cam, fling.yawVel, fling.pitchVel, fling.flyVel, dtSec)
                fling.yawVel = GestureFeelPolicy.decayVel(fling.yawVel, dtMs)
                fling.pitchVel = GestureFeelPolicy.decayVel(fling.pitchVel, dtMs)
                fling.flyVel = GestureFeelPolicy.decayVel(fling.flyVel, dtMs)
                // R268b 惯性探针（logcat 事件级取证：接管初速/衰减曲线/停稳时刻——
                // 手感验收锚；120ms 节流零刷屏，非惯性期零日志）
                if (!flingWasLive) {
                    flingWasLive = true
                    flingProbeAt = 0L // 接管首行必打（初速）
                }
                if (upMs - flingProbeAt >= 120L) {
                    flingProbeAt = upMs
                    android.util.Log.i(
                        "TasteMap",
                        "R268b 惯性: 松手后${upMs - fling.at}ms yaw=%.1f°/s pitch=%.1f°/s fly=%.0fu/s".format(
                            fling.yawVel, fling.pitchVel, fling.flyVel,
                        ),
                    )
                }
            } else if (flingWasLive) {
                // 惯性归零（或被新手势打断）——停稳时刻取证
                flingWasLive = false
                if (idle) {
                    android.util.Log.i(
                        "TasteMap",
                        "R268b 惯性: 停稳（松手后${upMs - fling.at}ms 归零）",
                    )
                }
            }
            // 点星/双击推进（惯性运镜；手势打断已在手势处置 false）
            // R242：approach=位置趋近停船位+朝向转向目标星（τ 分档：单击 0.18s
            // 干脆 / 双击运镜 0.9s 穿越星域）
            // R249 v2：目标星重锚——N 体下星持续移动（v~1 u/s，10 分钟可漂百级
            // 单位≈停船距离量级），运镜目标每帧取当帧渲染位（位置单一来源：
            // 点=所见=飞到所见处；烘焙 sp 只作触发瞬间的初值）
            if (focusFlight.active && focusFlight.targetChatId != 0L) {
                val phys = physics
                if (phys != null && phys.posOfChat(focusFlight.targetChatId, pollPos)) {
                    focusFlight.tx = pollPos[0]
                    focusFlight.ty = pollPos[1]
                    focusFlight.tz = pollPos[2]
                }
            }
            if (focusFlight.active) {
                // ---- R342 D3：voyage 模式（距离正比时长航行——easePose smoothstep
                //      =加速-巡航-缓出「真的在航行」；停船位每帧从当帧重锚目标
                //      重解=N 体跟随（漂移≤~4u 无感）；p01≥1=时间驱动端点精确
                //      落位+到达探针——时长确定性=V46 时长档断言的根） ----
                val vFrom = if (focusFlight.voyageMode) focusFlight.voyageFrom else null
                if (vFrom != null) {
                    focusFlight.voyageP01 += dtSec / focusFlight.voyageSec
                    val p01 = focusFlight.voyageP01
                    val tx = focusFlight.tx
                    val ty = focusFlight.ty
                    val tz = focusFlight.tz
                    val vdx = tx - vFrom.px
                    val vdy = ty - vFrom.py
                    val vdz = tz - vFrom.pz
                    val vLen = sqrt(vdx * vdx + vdy * vdy + vdz * vdz)
                    var sx = vFrom.px
                    var sy = vFrom.py
                    var sz = vFrom.pz
                    if (vLen > focusFlight.stopDist + 0.5f && vLen > 1e-4f) {
                        // 停船位 = 星位 − 视星方向×stopDist（approach 同式——已在停船
                        // 距内=位置原地只转朝向）
                        sx = tx - vdx / vLen * focusFlight.stopDist
                        sy = ty - vdy / vLen * focusFlight.stopDist
                        sz = tz - vdz / vLen * focusFlight.stopDist
                    }
                    val stopCam = SpaceCam(sx, sy, sz, vFrom.yawDeg, vFrom.pitchDeg)
                    val aim = TasteSpace.aimAt(stopCam, tx, ty, tz, voyageAim)
                    val to = SpaceCam(sx, sy, sz, aim[0], aim[1])
                    if (p01 >= 1f) {
                        cam = to // 端点精确（easePose p01=1 恒返目标）
                        focusFlight.active = false
                        focusFlight.voyageMode = false
                        focusFlight.voyageFrom = null
                        android.util.Log.i(
                            "TasteMap",
                            "R342 航行: 到达 用时=${"%.1f".format((upMs - focusFlight.voyageStartMs) / 1000f)}s（p01=1 端点精确——时长档断言锚）",
                        )
                    } else {
                        cam = TasteSpace.easePose(vFrom, to, p01)
                    }
                } else {
                // R303c P3：位姿模式=银河视角仪式运镜（easePose 缓动到完整位姿——
                // 位置+朝向双收敛；到位=位移<12u ∧ 双轴角差<2°（approachArrived 同口径））
                val poseTgt = if (focusFlight.poseMode) focusFlight.poseTarget else null
                if (poseTgt != null) {
                    val k = 1f - exp(-dtSec / focusFlight.tau)
                    cam = TasteSpace.easePose(cam, poseTgt, k)
                    val pdx = poseTgt.px - cam.px
                    val pdy = poseTgt.py - cam.py
                    val pdz = poseTgt.pz - cam.pz
                    val arrived = sqrt(pdx * pdx + pdy * pdy + pdz * pdz) < 12f &&
                        abs(TasteSpace.angDiff(cam.yawDeg, poseTgt.yawDeg)) < 2f &&
                        abs(TasteSpace.angDiff(cam.pitchDeg, poseTgt.pitchDeg)) < 2f
                    if (arrived) {
                        cam = poseTgt // 端点精确落位（easePose p01=1 恒返目标）
                        focusFlight.active = false
                        focusFlight.poseMode = false
                        focusFlight.poseTarget = null
                    }
                } else {
                    val k = 1f - exp(-dtSec / focusFlight.tau)
                    cam = TasteSpace.approach(
                        cam, focusFlight.tx, focusFlight.ty, focusFlight.tz, focusFlight.stopDist, k,
                    )
                    if (TasteSpace.approachArrived(
                            cam, focusFlight.tx, focusFlight.ty, focusFlight.tz, focusFlight.stopDist,
                        )
                    ) {
                        focusFlight.active = false
                    }
                }
                }
            }
            // ---- R301 世界卡相机（design-dual-form §3「承载期间相机不闲着」）：
            //      泊入=focusFlight 既有链（开卡时置位——落闸后本段自然开环绕）；
            //      环绕=绕宿主星水平缓转并瞄准（60s/圈——TasteSpace.orbitAround
            //      纯数学一帧一次）；关卡=回位插值（easePose 0.8s 回开卡前位姿）。
            //      让位=abortGalaxyTourR271 单口（手势/巡航/虫洞在飞=本段空转） ----
            if (worldCam.returnActive) {
                val rf = worldCam.returnFrom
                val rt = worldCam.returnTo
                if (rf == null || rt == null) {
                    worldCam.returnActive = false
                } else {
                    worldCam.returnP01 += dtSec / WORLD_CARD_RETURN_SEC
                    if (worldCam.returnP01 >= 1f) {
                        cam = rt // 端点精确（回位落点零残差——easePose p01=1 恒返 to）
                        worldCam.returnActive = false
                    } else {
                        cam = TasteSpace.easePose(rf, rt, worldCam.returnP01)
                    }
                }
            } else {
                val wcNow = worldCard
                if (wcNow != null && worldCam.orbitEnabled && !focusFlight.active &&
                    !wormhole.active && !galaxyTour.active
                ) {
                    val wStar = starsById[wcNow.chatId]
                    if (wStar != null) {
                        // 环绕中心=宿主星 N 体当帧渲染位（位置单一来源——环绕所见星）
                        val hasWp = physics?.posOfChat(wcNow.chatId, pollPos) == true
                        val wx = if (hasWp) pollPos[0] else wStar.spX
                        val wy = if (hasWp) pollPos[1] else wStar.spY
                        val wz = if (hasWp) pollPos[2] else wStar.spZ
                        cam = TasteSpace.orbitAround(
                            cam, wx, wy, wz, WORLD_CARD_ORBIT_RAD_PER_SEC * dtSec,
                        )
                    }
                }
            }
            // ---- R271 开局引导巡航导演（四段带飞：推近→绕转→点亮→驻留→缓出；
            //      绕转段直驱 cam，其余段经 focusFlight 既有链；任何手势让位见
            //      abortGalaxyTourR271——active=false 即自然停转零竞态） ----
            if (galaxyTour.active) {
                stepGalaxyTourR271(logicCtx, dtSec, upMs, wellMaxVol)
            }
            // 延时摄影自动播放（R314 倍速档：大爆炸正放 12s 基底 × 档速，
            // 到底自停——×16=0.75s 全程扫过=「看星区演化快进」的剧场档）
            if (genesisOn && genesisPlaying) {
                val speed = TasteFlight.TIMELAPSE_SPEED_STEPS[
                    genesisSpeedIdx.coerceIn(0, TasteFlight.TIMELAPSE_SPEED_STEPS.size - 1),
                ]
                val next = genesis01 + dtSec * speed / GENESIS_PLAY_SEC
                genesis01 = if (next >= 1f) { genesisPlaying = false; 1f } else next
            }
            // ---- R235② 虫洞 transit（1.5s 加速穿越：camPathSpace 前冲鼓包+隧道
            //      叠加；到达=星锚停船+爆散+账本。任何手势打断（手势处置置 false） ----
            if (wormhole.active) {
                wormhole.p01 += dtSec / TasteWormhole.WORMHOLE_SEC
                if (wormhole.p01 >= 1f) {
                    wormhole.p01 = 1f
                    wormhole.active = false
                    // 到达态=星锚停船位（camPathSpace p=1 的同口径）；R249 v2：
                    // 星锚取 N 体当帧渲染位（transit 1.5s 内星漂移≈1 单位——重锚
                    // 保证停船后星在舱外所见=命中/渲染同一位置）
                    val target = starsById[wormhole.targetChatId]
                    if (target != null) {
                        val hasWp = physics?.posOfChat(target.chatId, pollPos) == true
                        val wx = if (hasWp) pollPos[0] else target.spX
                        val wy = if (hasWp) pollPos[1] else target.spY
                        val wz = if (hasWp) pollPos[2] else target.spZ
                        val d = TasteSpace.distTo(cam, wx, wy, wz)
                        val ux = (wx - cam.px) / d
                        val uy = (wy - cam.py) / d
                        val uz = (wz - cam.pz) / d
                        cam = SpaceCam(
                            wx - ux * wormhole.stopDist,
                            wy - uy * wormhole.stopDist,
                            wz - uz * wormhole.stopDist,
                            cam.yawDeg,
                            cam.pitchDeg,
                        )
                        if (canvasSize.width > 0) {
                            val focal = TasteSpace.focalOf(
                                canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                            ) * lens
                            TasteSpace.project(
                                wx, wy, wz, cam, focal,
                                canvasSize.width / 2f, canvasSize.height / 2f, arriveProj,
                            )
                            engine.burst(arriveProj[0], arriveProj[1], 16, 360f, (target.chatId % 100_000L).toInt())
                        }
                    }
                    lockChatId = wormhole.targetChatId
                    android.util.Log.i(
                        "TasteMapX",
                        "R235 虫洞到达: 尾号${(wormhole.targetChatId % 10000L + 10000L) % 10000L} " +
                            "停船=${wormhole.stopDist.toInt()}",
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { TasteVoyageLog.bumpWormhole(voyageFile) }
                        }
                        // R302：虫洞 +5 星辉（虫洞账是星辉分项真源——重算即迁移，
                        // 增量账本不写；跳完即刷新等级/指引）
                        refreshStarlightR302(logicCtx, wellMaxVol)
                    }
                } else {
                    TasteWormhole.camPathSpace(
                        SpaceCam(wormhole.fromX, wormhole.fromY, wormhole.fromZ, wormhole.fromYaw, wormhole.fromPitch),
                        wormhole.tx, wormhole.ty, wormhole.tz, wormhole.stopDist, wormhole.p01, wormholePath,
                    )
                    cam = SpaceCam(wormholePath[0], wormholePath[1], wormholePath[2], wormholePath[3], wormholePath[4])
                }
            }
            // ---- R235① 飞船航迹步进（相机速度→流场/粒子/波纹——渲染器只读） ----
            val shipMinSide = min(canvasSize.width, canvasSize.height).toFloat().coerceAtLeast(1f)
            val shipFocal = TasteSpace.focalOf(
                canvasSize.width.toFloat().coerceAtLeast(1f),
                canvasSize.height.toFloat().coerceAtLeast(1f),
            ) * lens
            shipEngine.stepSpace(cam, shipFocal, dtSec, shipStyle, shipMinSide)
            // R235 事件级探针：尾迹档位（speed01 跨 0.5 档才出一条——验证矩阵①取证件）
            val speedBucket = if (shipEngine.speed01 >= 0.5f) 1 else 0
            if (speedBucket != lastSpeedBucket) {
                lastSpeedBucket = speedBucket
                android.util.Log.i(
                    "TasteMap",
                    "R235 尾迹档位: speed01=%.2f 档=${TasteAchievements.shipDescOf(shipStyle)}".format(shipEngine.speed01),
                )
            }
            // ---- R302 曲速巡航（Lv6「长按星空白处=持续冲刺」）：帧循环沿视线
            //      推进（flyForward 纯数学一帧一次）；虫洞 transit 持相机时让位；
            //      停止=abortGalaxyTourR271 单口同停（任何手势/运镜/点按） ----
            if (warpCruise && !wormhole.active) {
                warpCruiseSpeed += (GestureFeelPolicy.WARP_CRUISE_UNITS_PER_SEC - warpCruiseSpeed) *
                    (1f - exp(-dtSec / GestureFeelPolicy.WARP_CRUISE_TAU_SEC))
                cam = TasteSpace.flyForward(cam, warpCruiseSpeed * dtSec)
            } else {
                warpCruiseSpeed = 0f
            }
            // ---- R282 宇宙导演器节拍（排程到期→节奏闸→指令执行；漂移相位
            //      推进；流星节奏档统管；账本节流落盘；分钟探针）——主线程
            //      同帧零竞态；导演器零写入 N 体（物理保形红线） ----
            universeDirector.transitHold = wormhole.active
            // R385：装配复合体快照经 ctx.complexesR385 读（Stage 装配期发布——主
            // 线程单写读，R367 inWellIds 同纪律；起飞点=所在复合体，换代跟新）
            directorTickR282(
                logicCtx, universeDirector, universeEventsFile, directorProbeAnchor, wellMaxVol,
                logicCtx.complexesR385.value,
            )
            engine.step(dtSec)
            frameTick = ns
        }
    }
}

/**
 * R316c 状态袋（主方法状态声明区迁出后的纯引用容器——R254 [GalaxyLogicCtx]
 * 同款纪律：每字段=主方法原局部句柄的同一对象（MutableState/文件/引擎），
 * 袋每次重组重建亦零语义）。flightAnchor（R258 迁出后主方法已零消费）留
 * 在袋函数体内不进袋——remember 槽位原样保留。
 */
private class GalaxyStateBagR316c(
    val mapDataState: MutableState<TasteMapData?>,
    val loadPhaseState: MutableState<String?>,
    val selectedIdState: MutableState<Long?>,
    val exportingState: MutableState<Boolean>,
    val camState: MutableState<SpaceCam>,
    val lensState: MutableState<Float>,
    val canvasSizeState: MutableState<IntSize>,
    val reveal: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val engine: TasteFlight.FxEngine,
    val frameTickState: MutableLongState,
    val fling: FlingState,
    val focusFlight: FocusFlightState,
    val galaxyTour: GalaxyTourState,
    val tourPrefs: AppPrefs,
    val lockChatIdState: MutableLongState,
    val flyIntoArmedState: MutableState<Boolean>,
    val playingChatIdState: MutableState<Long>,
    val genesisOnState: MutableState<Boolean>,
    val genesisPlayingState: MutableState<Boolean>,
    val genesis01State: MutableState<Float>,
    val genesisSpeedIdxState: MutableState<Int>,
    val planetChoiceTagState: MutableState<String?>,
    val worldCardState: MutableState<WorldCardTarget?>,
    val worldCam: WorldCamState,
    val entryFlash: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val worldDiveTagState: MutableState<String?>,
    val diveFlash: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val nearFocusIdState: MutableLongState,
    val dustChatIdState: MutableLongState,
    val planetSysSnapshotState: MutableState<Map<Long, TastePlanetEntry>>,
    val tagProfilesState: MutableState<Map<Long, Map<String, Int>>>,
    val planetLru: LinkedHashMap<Long, TastePlanetEntry>,
    val planetAssemblingState: MutableState<Boolean>,
    /** R367 井内可见域集合（井深比<APPEAR_RATIO 的频道星 chatId——轮询每拍发布；装配驱逐的钉住原料） */
    val inWellIdsState: MutableState<Set<Long>>,
    /** R385 复合体快照（Stage 装配期一次发布——起飞点判定/帧循环经 ctx 只读；主线程单写读） */
    val complexesR385State: MutableState<List<DeepSpaceNebula.Complex>>,
    /** R367 装配拒绝会话去重（可见域占满诚实拒绝的探针行去重账） */
    val planetRefuseLog: MutableSet<Long>,
    val spreadTagState: MutableState<String?>,
    val spreadModelState: MutableState<TasteTagSpread.SpreadModel?>,
    val spreadLoadingState: MutableState<Boolean>,
    val constellationOnState: MutableState<Boolean>,
    val shipEngine: TasteShipFx.ShipTrailEngine,
    val achievementState: MutableState<TasteAchievements.AchievementReport?>,
    val showAchievementsState: MutableState<Boolean>,
    val wormhole: WormholeState,
    val wormholeMenuTagState: MutableState<String?>,
    val wormholeFromChatState: MutableLongState,
    val exportingVoyageState: MutableState<Boolean>,
    val voyageFile: File,
    val wormholePath: FloatArray,
    val universeDirector: UniverseDirector.State,
    val universeEventsFile: File,
    val directorProbeAnchor: LongArray,
    val guidePrefs: android.content.SharedPreferences,
    val showGuideState: MutableState<Boolean>,
    val discoverFile: File,
    val knownIdsState: MutableState<Set<Long>?>,
    val sessionLit: MutableSet<Long>,
    val revealIdsState: MutableState<Map<Long, Long>>,
    val litToastState: MutableState<AstroLitToast?>,
    val lastShareCountState: MutableState<Int>,
    val starlightFile: File,
    val starlightBookState: MutableState<TasteStarlight.Book?>,
    val levelUpEventState: MutableState<Int>,
    val guidanceLineState: MutableState<String?>,
    val beaconPosState: MutableState<FloatArray?>,
    val nebulaCellsState: MutableState<Map<Long, Int>>,
    val warpCruiseState: MutableState<Boolean>,
    val witnessSession: HashSet<String>,
    val planetLitSession: HashSet<String>,
    val sessionSubscribedState: MutableState<Set<Long>>,
)

/**
 * R316c 状态袋装配（主方法「数据与视图状态」→「R248 信息卡状态」全段 250 行
 * 整体迁出——语句原样；entryFlash 收束 effect 与 nebulaCells 装格 effect 随迁
 * （仍为组合里第 1/2 个 effect，零顺序变化）。寄存器预算收尾主承重刀：主方法
 * 峰值=本区 69 个持久局部+ctx 构造 69 宽 range（dexdump 实测）。
 */
@Composable
private fun tasteGalaxyStateR316c(
    app: android.app.Application,
    context: android.content.Context,
    flyTargetChatId: Long?,
): GalaxyStateBagR316c {
    // ---- 数据与视图状态 ----
    val mapDataState = remember { mutableStateOf<TasteMapData?>(null) }
    var mapData by mapDataState
    // ---- R254 首开进度文案（装配/布局阶段回调驱动；缓存命中恒 null=文案与旧版
    //      完全一致——只在无缓存首开的等待期出现「正在投影你的口味宇宙：阶段…」） ----
    // R316c：显式状态句柄（状态袋接线用——委托读写法零变化）
    val loadPhaseState = remember { mutableStateOf<String?>(null) }
    var loadPhase by loadPhaseState
    val selectedIdState = remember { mutableStateOf<Long?>(null) }
    var selectedId by selectedIdState
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val exportingState = remember { mutableStateOf(false) }
    var exporting by exportingState
    // ---- R242 第一人称宇宙相机（替换 R206/R231 轨道俯瞰平面星图相机）：SpaceCam
    //      =3D 空间质点+yaw/pitch 全方位视角；单指拖=环顾、捏合=飞、双击星=运镜
    //      飞向它——投影/手势/运镜数学全收口 core.tastemap.TasteSpace ----
    val camState = remember { mutableStateOf(SpaceCam.ORIGIN) }
    var cam by camState
    // 焦距倍率（+/- 无障碍按钮的「望远镜」——渲染端 focal×lens；1=标准 100° 视场）
    val lensState = remember { mutableStateOf(1f) }
    var lens by lensState
    val canvasSizeState = remember { mutableStateOf(IntSize.Zero) }
    var canvasSize by canvasSizeState
    // 点星基因行（R133 频道基因，VM 内部切 IO 现算；未索引/条数不足 null 不渲染）
    // 开场揭示（点云淡入；graphicsLayer 整层做，零逐点开销）
    val reveal = remember { Animatable(0f) }
    // ---- R206 特效全家 ----
    // 粒子/流星/彗星/呼吸时钟引擎（非快照状态：帧循环 step、GL 渲染器直读）
    // R345：Canvas 渲染器 TasteFlightRenderer 已整类退役——GL 恒为唯一引擎（诊断降级件见槽位段）
    val engine = remember { TasteFlight.FxEngine() }
    // 帧时钟：帧循环每拍 bump 一次 → 只失效 GL 叠加层的 draw 相位（composition 不逐帧重组）
    // R258：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val frameTickState = remember { mutableLongStateOf(0L) }
    var frameTick by frameTickState
    // 惯性速度持有（手势期记录，松手 >110ms 后帧循环接管衰减——旧心跳同款节奏）
    val fling = remember { FlingState() }
    // 点星/双击运镜（惯性运镜目标；任何手势立即打断）
    val focusFlight = remember { FocusFlightState() }
    // ---- R271 开局引导巡航（新用户带飞：推近→绕亮星半圈→点亮未探索星→缓出；
    //      频控 AppPrefs 两键（≤2 次），任何手势即刻让位；剧本政策件=TasteTour） ----
    val galaxyTour = remember { GalaxyTourState() }
    val tourPrefs = remember { AppPrefs(app) }
    // 双击运镜锁定星（锁定环渲染+飞进行星门的目标锚——「锁定+飞向它」）
    val lockChatIdState = remember { mutableLongStateOf(0L) }
    var lockChatId by lockChatIdState
    // R242 飞进行星门再武装（触达后闩锁，全行星离场 2×门宽再武装——防原地反复弹）
    val flyIntoArmedState = remember { mutableStateOf(true) }
    var flyIntoArmed by flyIntoArmedState
    // 在播频道（灯塔）：轮询 items/currentIndex/nowPlayingAudio 纯内存读
    val playingChatIdState = remember { mutableStateOf(0L) }
    var playingChatId by playingChatIdState
    // 延时摄影（R314 换名——原 R206 创世纪星生序时间轴；内部标识沿用 genesis 族
    // 零改名churn）：光标 0..1，1=全亮（关闭态恒 1）
    val genesisOnState = remember { mutableStateOf(false) }
    var genesisOn by genesisOnState
    // R258：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val genesisPlayingState = remember { mutableStateOf(false) }
    var genesisPlaying by genesisPlayingState
    val genesis01State = remember { mutableStateOf(1f) }
    var genesis01 by genesis01State
    // R314 延时摄影倍速档（TIMELAPSE_SPEED_STEPS 下标——点按循环 ×1/×4/×16，
    // 模式加速光阴零新增手势）
    val genesisSpeedIdxState = remember { mutableStateOf(0) }
    var genesisSpeedIdx by genesisSpeedIdxState

    // ---- R233 行星层状态（引力井惰性装配 LRU + 指纹表 + 轮询派生的焦点/尘埃星） ----
    // 点行星→R222 双选弹层的标签词（宿主在本页；弹层复用 HashtagChoiceSheet）
    val planetChoiceTagState = remember { mutableStateOf<String?>(null) }
    var planetChoiceTag by planetChoiceTagState

    // ---- R301 行星世界卡（design-dual-form §3 落地：**点行星新默认=世界卡**——
    //      R269 直进内容流的有意行为变更，用户终审批准；「直接开刷」降为卡内首键。
    //      世界卡=星图内半屏 sheet 不退出：条数/时期弧/代表作封面墙/同 tag 其他
    //      恒星 pills；相机泊入环绕（帧循环段消费 worldCam），关卡=相机回位） ----
    val worldCardState = remember { mutableStateOf<WorldCardTarget?>(null) }
    var worldCard by worldCardState
    // 世界卡相机状态袋（回位姿势/环绕许可/回位插值——帧循环直读，手势让位经
    // abortGalaxyTourR271 单口同族语义）
    val worldCam = remember { WorldCamState() }
    // ---- R301 A 案运镜（错觉拼接）：星图侧接缝两件——
    //      ① 进场白闪：feed 跳入意图在场时首帧白闪 250ms 收束（对侧光点收缩+白闪
    //      峰值切页，本侧从白里落成星图——两帧断裂双向藏）；
    //      ② 世界卡俯冲白闪（反向：「直接开刷」=扎向行星+白闪 320ms，满帧回调
    //      R269 内容流链） ----
    val entryFlash = remember { Animatable(if (flyTargetChatId != null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entryFlash.value > 0f) entryFlash.animateTo(0f, tween(MotionTokens.GalaxyEntryFlashMs))
    }
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val worldDiveTagState = remember { mutableStateOf<String?>(null) }
    var worldDiveTag by worldDiveTagState
    val diveFlash = remember { Animatable(0f) }
    // 飞近恒星（井圈视半径最大且过中距档）——关联弧的「飞近焦点态」
    val nearFocusIdState = remember { mutableLongStateOf(0L) }
    var nearFocusId by nearFocusIdState
    // 贴脸尘埃档唯一许可星（同屏井圈最大且系统已装配）
    val dustChatIdState = remember { mutableLongStateOf(0L) }
    var dustChatId by dustChatIdState
    // LRU 快照（渲染帧输入——发布副本，读侧零锁）；accessOrder=true：命中即提升
    val planetSysSnapshotState = remember { mutableStateOf<Map<Long, TastePlanetEntry>>(emptyMap()) }
    var planetSysSnapshot by planetSysSnapshotState
    // 频道 tag 指纹（chatId → fold 键计数）：行星系统副产物 + 大频道后台预取
    val tagProfilesState = remember { mutableStateOf<Map<Long, Map<String, Int>>>(emptyMap()) }
    var tagProfiles by tagProfilesState
    val planetLru = remember { LinkedHashMap<Long, TastePlanetEntry>(16, 0.75f, true) }
    val planetAssemblingState = remember { mutableStateOf(false) }
    var planetAssembling by planetAssemblingState
    // R367 井内可见域集合（驱逐钉住原料——轮询每拍全量重发布，井内集合换血零陈值）
    val inWellIdsState = remember { mutableStateOf<Set<Long>>(emptySet()) }
    // R367 装配拒绝会话去重（探针行一星一记防刷屏）
    val planetRefuseLog = remember { HashSet<Long>() }
    // R385 复合体快照（Stage 装配期一次发布——新星起飞点判定原料；主线程单写读）
    val complexesR385State = remember { mutableStateOf<List<DeepSpaceNebula.Complex>>(emptyList()) }

    // ---- R234 三期状态：尘埃带（关键字传播）+ 星座轨迹（独立开关） ----
    // 选中的传播关键字（点行星→双选弹层「看传播」写入；null=零尘埃带）
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val spreadTagState = remember { mutableStateOf<String?>(null) }
    var spreadTag by spreadTagState
    // 传播模型（hashtag 召回聚合——装配期烘焙世界坐标的原料）
    val spreadModelState = remember { mutableStateOf<TasteTagSpread.SpreadModel?>(null) }
    var spreadModel by spreadModelState
    // 召回装载态（R236 换轨 tags 倒排=毫秒级；保留装载态承接冷缓存首查/异常回退）
    val spreadLoadingState = remember { mutableStateOf(false) }
    var spreadLoading by spreadLoadingState
    // 星座开关（R314 星轨=星座合并：R206 今晚星轨暗光路+流星 与 R234 星座轨迹
    // 亮足迹归一为唯一星座层）——AppPrefs taste_constellation 持久化（默认开=
    // 流星常驻氛围零回退；右上星标翻转即写回）；延时摄影光标到「今天」才亮
    // （未来时代不提前泄底今晚的足迹）
    // R258：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val constellationOnState = remember { mutableStateOf(tourPrefs.tasteConstellation) }
    var constellationOn by constellationOnState

    // ---- R235 四期状态：RPG 层（飞船航迹/成就/虫洞/未探索星云/航行日志） ----
    // 飞船航迹引擎（帧循环 step、渲染器只读——FxEngine 同款纪律）
    val shipEngine = remember { TasteShipFx.ShipTrailEngine() }
    // 成就评定（装配一次；分享计数变化后重评）
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val achievementState = remember { mutableStateOf<TasteAchievements.AchievementReport?>(null) }
    var achievement by achievementState
    // 成就面板开关（侧栏「成就」入口）
    val showAchievementsState = remember { mutableStateOf(false) }
    var showAchievements by showAchievementsState
    // 虫洞 transit 状态（长按行星→「虫洞跳跃」；帧循环推进；任何手势打断）
    val wormhole = remember { WormholeState() }
    // 虫洞菜单（长按行星弹「虫洞跳跃 #tag」；null=无菜单）
    // R258：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val wormholeMenuTagState = remember { mutableStateOf<String?>(null) }
    var wormholeMenuTag by wormholeMenuTagState
    val wormholeFromChatState = remember { mutableLongStateOf(0L) }
    var wormholeFromChat by wormholeFromChatState
    // R316b：nebulae 派生随迁 TasteMapStageR316（单消费位——语句原样）
    // 航行日志导出中（按钮态）
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val exportingVoyageState = remember { mutableStateOf(false) }
    var exportingVoyage by exportingVoyageState
    // 航行账本文件（分享/虫洞计数——星图自持，LocalStore 零改动）
    val voyageFile = remember {
        File(File(app.filesDir, "galaxy"), TasteVoyageLog.FILE_NAME)
    }
    // 虫洞路径暂存（帧循环复用——零逐帧分配；R242 空间口径 5 元组：px/py/pz/yaw/pitch）
    val wormholePath = remember { FloatArray(5) }

    // ---- R282 活宇宙 2.0·宇宙导演器（「星系图谱运动起来活起来」的导演半边）：
    //      导演器=core 纯函数件（UniverseDirector：节奏闸五门/开场排程/事件账本），
    //      本页只持状态+账本句柄——节拍在帧循环（directorTickR282）、场景排程在
    //      effect（directorSceneSetupR282）、爬虫脉冲在彗星 effect 改道入队；
    //      **物理保形红线：导演器零写入 N 体**（事件全是叠加视觉层指令） ----
    val universeDirector = remember { UniverseDirector.State() }
    // 事件账本文件（galaxy 自持——TasteVoyageLog 同款纪律，LocalStore 零改动）
    val universeEventsFile = remember {
        File(File(app.filesDir, "galaxy"), UniverseDirector.EVENTS_FILE)
    }
    // 分钟探针锚（帧循环节流——60s 一条节律账）
    val directorProbeAnchor = remember { longArrayOf(0L) }

    // ---- R237 真机体验修复状态（四实锤：手势无反应/进不去探索/聚拢/功能不可感知） ----
    // 飞行手势锚点反馈（draw 相位直读的持有实例——机器每批同步；frameTick 每拍
    // 失效 Canvas draw=零额外重组成本，见 detectFlightGestures anchorOut）
    val flightAnchor = remember { FlightGestureMachine.AnchorUi() }
    // 首开引导浮层（tgfeed_ui prefs 持久键——一次性，键值升版本可重弹）
    val guidePrefs = remember {
        context.getSharedPreferences("tgfeed_ui", android.content.Context.MODE_PRIVATE)
    }
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val showGuideState = remember { mutableStateOf(!guidePrefs.getBoolean(GUIDE_PREF_KEY, false)) }
    var showGuide by showGuideState

    // ---- R248 星名点亮状态（发现机制） ----
    // 点亮账本文件（galaxy 自持——TasteVoyageLog 同款纪律，LocalStore 零改动）
    val discoverFile = remember {
        File(File(app.filesDir, "galaxy"), TasteDiscoveryLog.FILE_NAME)
    }
    // 已知星集合（null=装载中——装载完成前未知判定不生效，点星走旧行为）：
    // persisted ∪ 已看过 ∪ 已订阅 ∪ 特殊天体（自己的会话）∪ 会话新发现
    val knownIdsState = remember { mutableStateOf<Set<Long>?>(null) }
    var knownIds by knownIdsState
    // 会话点亮账（非快照普通集合——mapData 变更（补名拷贝）触发已知装载 effect
    // 重跑时，持久化 IO 可能尚未落盘——会话账兜住「刚点亮又回未知」的竞速窗）
    val sessionLit = remember { HashSet<Long>() }
    // 会话新发现（chatId → 发现时刻 ms——渲染打字机名签+光环的驱动；3s 剪枝）
    val revealIdsState = remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var revealIds by revealIdsState
    // ---- R312 点亮即时科普小卡（发现时刻一拍至多一颗——overlay 消费，5.2s 自收） ----
    val litToastState = remember { mutableStateOf<AstroLitToast?>(null) }
    var litToast by litToastState
    // 成就重评用的分享账快照（发现点亮后重评需带最近一次口径）
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val lastShareCountState = remember { mutableStateOf(0) }
    var lastShareCount by lastShareCountState

    // ---- R302 星辉等级状态（design-rpg-loop 成长循环——账本/曲线/解锁全在
    //      core.tastemap.TasteStarlight 纯函数件；本页只持状态+薄接线） ----
    // 星辉增量账本文件（galaxy 自持——TasteVoyageLog 同款纪律，LocalStore 零改动）
    val starlightFile = remember {
        File(File(app.filesDir, "galaxy"), TasteStarlight.FILE_NAME)
    }
    // 星辉全账（三源重算快照——null=未装载；装载/点亮/虫洞/目击后刷新）
    val starlightBookState = remember { mutableStateOf<TasteStarlight.Book?>(null) }
    var starlightBook by starlightBookState
    // 升级演出事件（刚升到的等级；0=无演出——一次性播放后归零）
    val levelUpEventState = remember { mutableStateOf(0) }
    var levelUpEvent by levelUpEventState
    // 「最近的下一枚」指引行（成就面板航海图+罗盘 HUD 共用文案）
    val guidanceLineState = remember { mutableStateOf<String?>(null) }
    var guidanceLine by guidanceLineState
    // 指引信标目标（世界系 xyz——星图上画环；null=无空间目标）
    val beaconPosState = remember { mutableStateOf<FloatArray?>(null) }
    var beaconPos by beaconPosState
    // 星域成员表（chatId → 星云格键——首踏星域判定；口味系聚类与渲染同内脏）
    val nebulaCellsState = remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var nebulaCellsByChat by nebulaCellsState
    LaunchedEffect(mapData) {
        val d = mapData ?: return@LaunchedEffect
        nebulaCellsByChat = TasteNebula.nebulaCellIndexOf(
            d.stars.map {
                TasteNebula.NebulaStarIn(
                    it.chatId, it.x, it.y, it.flightZ, it.watchSec, it.subscribed,
                    it.gVideo, it.gPhoto, it.gAudio,
                )
            },
        )
    }
    // 曲速巡航（Lv6：长按星空白处=持续冲刺；让位=abortGalaxyTourR271 单口同停）
    val warpCruiseState = remember { mutableStateOf(false) }
    var warpCruise by warpCruiseState
    // 会话目击去重（事件实例键——IO 落盘窗内的防重闸）
    val witnessSession = remember { HashSet<String>() }
    // 会话行星点亮去重（账本键——IO 落盘窗内的防重闸；装载时由账本预填）
    val planetLitSession = remember { HashSet<String>() }



    // ---- R248 信息卡状态：R253② 迁入 StarInfoCard（God 函数瘦身）——主函数仅保留
    //      会话级订阅覆盖（跨开合会话反馈，组件经回调读写）与 genomeLine 迁出 ----
    // R316 二次拆分：显式状态句柄（GalaxyLogicCtx 接线用——委托读写法零变化）
    val sessionSubscribedState = remember { mutableStateOf(emptySet<Long>()) }
    var sessionSubscribed by sessionSubscribedState
    return GalaxyStateBagR316c(
        mapDataState = mapDataState,
        loadPhaseState = loadPhaseState,
        selectedIdState = selectedIdState,
        exportingState = exportingState,
        camState = camState,
        lensState = lensState,
        canvasSizeState = canvasSizeState,
        reveal = reveal,
        engine = engine,
        frameTickState = frameTickState,
        fling = fling,
        focusFlight = focusFlight,
        galaxyTour = galaxyTour,
        tourPrefs = tourPrefs,
        lockChatIdState = lockChatIdState,
        flyIntoArmedState = flyIntoArmedState,
        playingChatIdState = playingChatIdState,
        genesisOnState = genesisOnState,
        genesisPlayingState = genesisPlayingState,
        genesis01State = genesis01State,
        genesisSpeedIdxState = genesisSpeedIdxState,
        planetChoiceTagState = planetChoiceTagState,
        worldCardState = worldCardState,
        worldCam = worldCam,
        entryFlash = entryFlash,
        worldDiveTagState = worldDiveTagState,
        diveFlash = diveFlash,
        nearFocusIdState = nearFocusIdState,
        dustChatIdState = dustChatIdState,
        planetSysSnapshotState = planetSysSnapshotState,
        tagProfilesState = tagProfilesState,
        planetLru = planetLru,
        planetAssemblingState = planetAssemblingState,
        inWellIdsState = inWellIdsState,
    complexesR385State = complexesR385State,
        planetRefuseLog = planetRefuseLog,
        spreadTagState = spreadTagState,
        spreadModelState = spreadModelState,
        spreadLoadingState = spreadLoadingState,
        constellationOnState = constellationOnState,
        shipEngine = shipEngine,
        achievementState = achievementState,
        showAchievementsState = showAchievementsState,
        wormhole = wormhole,
        wormholeMenuTagState = wormholeMenuTagState,
        wormholeFromChatState = wormholeFromChatState,
        exportingVoyageState = exportingVoyageState,
        voyageFile = voyageFile,
        wormholePath = wormholePath,
        universeDirector = universeDirector,
        universeEventsFile = universeEventsFile,
        directorProbeAnchor = directorProbeAnchor,
        guidePrefs = guidePrefs,
        showGuideState = showGuideState,
        discoverFile = discoverFile,
        knownIdsState = knownIdsState,
        sessionLit = sessionLit,
        revealIdsState = revealIdsState,
        litToastState = litToastState,
        lastShareCountState = lastShareCountState,
        starlightFile = starlightFile,
        starlightBookState = starlightBookState,
        levelUpEventState = levelUpEventState,
        guidanceLineState = guidanceLineState,
        beaconPosState = beaconPosState,
        nebulaCellsState = nebulaCellsState,
        warpCruiseState = warpCruiseState,
        witnessSession = witnessSession,
        planetLitSession = planetLitSession,
        sessionSubscribedState = sessionSubscribedState,
    )
}

// ==================== R316b 二次拆分迁出件（主方法 15062 units/267 寄存器复发救火） ====================
//
// 病灶：R258 拆后六轮加料（R271/R282/R301/R302/R312/R314）把主方法顶回 15062
// DEX units/267 寄存器（>256 档——ART 校验 copy1 v260 超线、进星图即 VerifyError，
// R316 基线 dexdump 实锤）。本节为二次迁出件：**语句原样搬移、状态经
// [GalaxyLogicCtx] R316 句柄（只加不改）读写，零行为变化**（R258 同款模式）。
// 机检闸防第三次复发：TasteMapMethodBudgetTest（主函数行数预算）+
// scripts/r316_dex_budget.py（dexdump 精确仪预算断言）。

/**
 * R316 成就评定委托（主方法局部函数 evaluateAchievements 迁顶层——语句原样，
 * 状态经句柄读写；评定体仍是 [evaluateAchievementsR254]）。
 */
private fun evaluateAchievementsR316(
    vm: FeedViewModel,
    mapData: MutableState<TasteMapData?>,
    achievement: MutableState<TasteAchievements.AchievementReport?>,
    knownIds: MutableState<Set<Long>?>,
    lastShareCount: MutableState<Int>,
    shareCount: Int,
) {
    val d = mapData.value ?: return
    achievement.value = evaluateAchievementsR254(vm, d, knownIds.value, shareCount)
    lastShareCount.value = shareCount
}

/**
 * R316 海报导出体（主方法局部函数 onExport 迁顶层——语句原样，状态经句柄）：
 * 整图 + 标题渲染成 1080×1440，走 ShareCard 的 MediaStore 链存公共相册。
 * R322：生成器换轨 TastePosterV2（真渲染口径快照——M 章设计；V1 生成器
 * 留库退役不删，R303 先例）。生成耗时在此记账（用户导出等待体验）。
 */
private fun exportTasteMapPosterR316(ctx: GalaxyLogicCtx, stars: List<StarNode>, links: List<StarLink>) {
    if (ctx.exporting.value) return
    ctx.scope.launch {
        ctx.exporting.value = true
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val d = ctx.mapData.value ?: return@runCatching false
                if (d.stars.isEmpty()) return@runCatching false
                val t0 = android.os.SystemClock.elapsedRealtime()
                val card = TastePosterV2.generate(buildPosterDataV2(ctx, d))
                val genMs = android.os.SystemClock.elapsedRealtime() - t0
                android.util.Log.i("TasteMap", "R322 海报V2生成: 星数=${d.stars.size} 徽章=${d.kindCounts.size} 耗时=${genMs}ms")
                ShareCard.saveToGallery(ctx.context, card)
            }.getOrDefault(false)
        }
        ctx.exporting.value = false
        android.util.Log.i("TasteMap", "海报导出: ok=$ok 星数=${stars.size} 连线=${links.size}")
        // R235：海报导出计入分享账（成就「分享 N 次」funnel 之一）
        val shareCount = withContext(Dispatchers.IO) {
            runCatching { TasteVoyageLog.bumpShare(ctx.voyageFile).shareCount }.getOrDefault(0)
        }
        evaluateAchievementsR316(ctx.vm, ctx.mapData, ctx.achievement, ctx.knownIds, ctx.lastShareCount, shareCount)
        Toast.makeText(
            ctx.context,
            if (ok) "海报已存到相册 Pictures/TGFeed，可到相册分享" else "海报保存失败",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/** R316 星轨→世界折线（主方法局部函数 buildVoyageTrail 迁顶层——语句原样；
 *  站点串映射在图恒星；<2 站=诚实无光路 null——R234 星座轨迹同族口径） */
private fun buildVoyageTrailR316(
    starsById: Map<Long, StarNode>,
    stops: List<TodayStop>,
): TasteVoyagePoster.VoyageTrail? {
    if (stops.size < 2) return null
    val xs = FloatArray(stops.size)
    val ys = FloatArray(stops.size)
    val zs = FloatArray(stops.size)
    val ts = LongArray(stops.size)
    var n = 0
    for (s in stops) {
        val st = starsById[s.chatId] ?: continue
        xs[n] = st.spX; ys[n] = st.spY; zs[n] = st.spZ; ts[n] = s.tsMs; n++
    }
    return if (n >= 2) TasteVoyagePoster.VoyageTrail(n, xs, ys, zs, ts) else null
}

/**
 * R316 航行日志导出体（主方法局部函数 onExportVoyage 迁顶层——语句原样，状态经
 * 句柄；ShareCard 同源管线：saveToGallery 保存链复用，绘制独立文件
 * TasteVoyagePoster——ShareCard.kt 零改动=海报零回归红线）：当前视角星图快照 +
 * 今日/本周星轨 + 成就徽章 + 统计 → 1080×1440 分享海报。
 */
private fun exportVoyagePosterR316(
    ctx: GalaxyLogicCtx,
    starsById: Map<Long, StarNode>,
    nebulaCount: Int,
) {
    if (ctx.exportingVoyage.value) return
    ctx.scope.launch {
        ctx.exportingVoyage.value = true
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val d = ctx.mapData.value ?: return@runCatching false
                if (d.stars.isEmpty()) return@runCatching false
                // 本周星轨（history.json 近 7 天站点串——连续同频道去重，R234 口径同族）
                val weekAgoMs = System.currentTimeMillis() - 7L * 86_400_000L
                val stops = ArrayList<TodayStop>(64)
                runCatching {
                    val tsByKey = HashMap<String, Long>(256)
                    for ((k, ts) in ctx.vm.store.loadHistory()) tsByKey[k] = ts
                    val raw = ArrayList<TodayStop>(64)
                    for (item in ctx.vm.store.loadHistoryItems()) {
                        val ts = tsByKey[item.key] ?: 0L
                        if (ts >= weekAgoMs && item.chatId != 0L) raw += TodayStop(item.chatId, ts)
                    }
                    raw.sortBy { it.tsMs }
                    var lastChat = 0L
                    for (s in raw) {
                        if (s.chatId != lastChat) {
                            if (s.chatId in starsById) stops += s
                            lastChat = s.chatId
                        }
                    }
                }
                val poster = TasteVoyagePoster.generate(
                    TasteVoyagePoster.VoyageCardData(
                        stars = d.stars.map {
                            TasteVoyagePoster.VoyageStar(
                                it.spX, it.spY, it.spZ, it.bodyRadius, it.bodyColor, it.bodyAlpha, it.chatId,
                            )
                        },
                        cam = ctx.cam.value,
                        lens = ctx.lens.value,
                        focalPxDevice = TasteSpace.focalOf(
                            ctx.canvasSize.value.width.toFloat().coerceAtLeast(1f),
                            ctx.canvasSize.value.height.toFloat().coerceAtLeast(1f),
                        ) * ctx.lens.value,
                        deviceCanvasW = ctx.canvasSize.value.width.coerceAtLeast(1),
                        weekTrail = buildVoyageTrailR316(starsById, stops),
                        todayTrail = buildVoyageTrailR316(starsById, d.todayStops),
                        report = ctx.achievement.value,
                        starCount = d.stars.count {
                            it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
                        },
                        nebulaCount = nebulaCount,
                    ),
                )
                ShareCard.saveToGallery(ctx.context, poster)
            }.getOrDefault(false)
        }
        ctx.exportingVoyage.value = false
        android.util.Log.i("TasteMapX", "R235 航行日志导出: ok=$ok")
        // 分享账 +1（成就 funnel 之一——与海报导出同口径）
        val shareCount = withContext(Dispatchers.IO) {
            runCatching { TasteVoyageLog.bumpShare(ctx.voyageFile).shareCount }.getOrDefault(0)
        }
        evaluateAchievementsR316(ctx.vm, ctx.mapData, ctx.achievement, ctx.knownIds, ctx.lastShareCount, shareCount)
        Toast.makeText(
            ctx.context,
            if (ok) "航行日志已存到相册 Pictures/TGFeed，可到相册分享" else "航行日志保存失败",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * R316 开行星世界卡（主方法局部函数 openWorldCardR301 迁顶层——语句原样，状态经
 * 句柄）：与点星信息卡互斥（两张底卡不叠）→ 行星系统装配幂等确保 → 记回位姿势
 * （[keepPose]=卡间换绑/卡在场再点行星时保留最初回位点）→ 相机泊入（focusFlight
 * 既有链：停船=井×4.6×0.48≈井×2.2 中距，行星层清晰聚焦；到达后帧循环自然开
 * 环绕）。星不在图=只开卡不运镜（卡内数据照常——诚实缺省）。
 */
private fun openWorldCardR316(
    ctx: GalaxyLogicCtx,
    starsById: Map<Long, StarNode>,
    wellMaxVol: Int,
    chatId: Long,
    tag: String,
    keepPose: Boolean = false,
) {
    if (chatId == 0L || tag.isBlank()) return
    ctx.selectedId.value = null // 与点星信息卡互斥
    abortGalaxyTourR271(ctx, "世界卡开")
    ctx.scope.launch { runCatching { assemblePlanetSystemR254(ctx, chatId) } }
    if (!keepPose || ctx.worldCam.savedPose == null) ctx.worldCam.savedPose = ctx.cam.value
    ctx.worldCam.returnActive = false
    ctx.worldCam.returnP01 = 0f
    ctx.worldCam.orbitEnabled = true // 新卡会话重新许可环绕（abort 单口刚置 false）
    ctx.worldCard.value = WorldCardTarget(chatId, tag)
    val s = starsById[chatId] ?: return
    val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(s.itemsCount, wellMaxVol)) * 0.48f
    beginStarVoyageR342(ctx, s, stop) // R342 D3：泊入=航行档（距离正比时长——到位后环绕自然开）
    android.util.Log.i(
        "TasteMap",
        "R301 世界卡: 开 tag=#$tag 宿主尾号${(chatId % 10000L + 10000L) % 10000L} 泊入停船=${stop.toInt()}",
    )
}

/** R316 关行星世界卡（×/返回键）：相机回位=easePose 0.8s 回开卡前位姿（帧循环段消费） */
private fun closeWorldCardR316(ctx: GalaxyLogicCtx) {
    if (ctx.worldCard.value == null) return
    ctx.worldCard.value = null
    val to = ctx.worldCam.savedPose
    if (to != null) {
        ctx.worldCam.returnFrom = ctx.cam.value
        ctx.worldCam.returnTo = to
        ctx.worldCam.returnP01 = 0f
        ctx.worldCam.returnActive = true
    }
    android.util.Log.i("TasteMap", "R301 世界卡: 关（相机回位）")
}

/**
 * R316 世界卡「直接开刷」俯冲（主方法局部函数 startWorldDiveR301 迁顶层——语句
 * 原样，状态经句柄；A 案反向错觉拼接）：卡直接关（离页无回位）+相机扎向宿主星
 * （停船×0.25、τ=0.12s 急俯冲=进入行星的错觉）+白闪 320ms——满帧回调 R269 内容
 * 流链（接缝藏白闪；feed 侧首帧白闪收束=对侧半环）。
 */
private fun startWorldDiveR316(
    ctx: GalaxyLogicCtx,
    starsById: Map<Long, StarNode>,
    wellMaxVol: Int,
    chatId: Long,
    tag: String,
) {
    ctx.worldCard.value = null
    ctx.worldCam.orbitEnabled = false
    ctx.worldCam.returnActive = false
    val s = starsById[chatId]
    if (s != null) {
        val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(s.itemsCount, wellMaxVol)) * 0.25f
        ctx.focusFlight.beginWorldFlight()
        ctx.focusFlight.tx = s.spX
        ctx.focusFlight.ty = s.spY
        ctx.focusFlight.tz = s.spZ
        ctx.focusFlight.stopDist = stop
        ctx.focusFlight.tau = 0.12f
        ctx.focusFlight.targetChatId = s.chatId
    }
    ctx.worldDiveTag.value = tag
    android.util.Log.i(
        "TasteMap",
        "R301 世界卡俯冲: tag=#$tag（320ms 白闪→内容流）",
    )
}

/**
 * R316b 星图舞台（主方法 Box 内容整体迁出——二次瘦身主承重件，R258 模式照做）：
 * 深空画布层 → 加载/空态 → 统计图例/右上工具/传播胶囊/探索条/延时摄影/缩放/导出/
 * 虫洞菜单/成就入口+面板/星辉 HUD/升级演出/点亮科普卡 → 点星信息卡 → 双选弹层 →
 * 行星世界卡 → 白闪接缝 → 首开引导。**语句原样搬移**（句柄别名逐字对齐主方法
 * 旧文——`val logicCtx = ctx` 与 by 委托组），零行为变化。
 */
@Composable
private fun BoxScope.TasteMapStageR316(
    ctx: GalaxyLogicCtx,
    mapData: TasteMapData?,
    starsById: Map<Long, StarNode>,
    channelStars: List<StarNode>,
    wellMaxVol: Int,
    scene: FlightScene,
    shipStyle: TasteAchievements.ShipStyle,
    loadPhase: String?,
    universeDirector: UniverseDirector.State,
    entryFlash: Animatable<Float, AnimationVector1D>,
    diveFlash: Animatable<Float, AnimationVector1D>,
    guidePrefs: android.content.SharedPreferences,
    onPlayDust: (item: VideoItem, batch: List<VideoItem>, label: String) -> Unit,
    onOpenChannel: (ChannelBrief) -> Unit,
    onOpenHashtagTimeline: (String) -> Unit,
    onHashtagSearchPlay: (String) -> Unit,
    onPlayTagContent: (String) -> Unit,
) {
    // ---- R316b 句柄别名组（`val logicCtx = ctx`+by 委托——主方法 Box 旧文逐字
    //      对齐，状态读写语句零改写；单消费位派生量随迁） ----
    val logicCtx = ctx
    val vm = ctx.vm
    val gateway = ctx.gateway
    val app = ctx.app
    val discoverFile = ctx.discoverFile
    var selectedId by ctx.selectedId
    val knownIds by ctx.knownIds
    var spreadTag by ctx.spreadTag
    var spreadModel by ctx.spreadModel
    var planetChoiceTag by ctx.planetChoiceTag
    var showAchievements by ctx.showAchievements
    val achievement by ctx.achievement
    val exporting by ctx.exporting
    val exportingVoyage by ctx.exportingVoyage
    var worldCard by ctx.worldCard
    val worldCam = ctx.worldCam
    var litToast by ctx.litToast
    var levelUpEvent by ctx.levelUpEvent
    val starlightBook by ctx.starlightBook
    val guidanceLine by ctx.guidanceLine
    val warpCruise by ctx.warpCruise
    val genesisOn by ctx.genesisOn
    val wormholeMenuTag by ctx.wormholeMenuTag
    var sessionSubscribed by ctx.sessionSubscribed
    var showGuide by ctx.showGuide
    val planetSysSnapshot by ctx.planetSysSnapshot
    val stars = mapData?.stars.orEmpty()
    val links = mapData?.links.orEmpty()
    val selectedStar = stars.firstOrNull { it.chatId == selectedId }
    // R249 双星对查询表（chatId → 对——信息卡科普行的伴侣原料）
    val binaryByChat = remember(mapData) {
        val m = HashMap<Long, BinaryPair>()
        mapData?.binaryPairs?.forEach { bp ->
            m[bp.a] = bp
            m[bp.b] = bp
        }
        m
    }
    val genesisSpan = scene.span
    // 未探索星云（mapData 布局后烘焙一次——渲染期零计算除投影）
    // R242：聚类仍在**口味平面**做（内容相似语义——TasteNebula 原口径不动），雾团
    // 中心映射到空间系（R256 V3 方向口径：聚类质心口味角+哈希 φ → 方向 × 620 半径，
    // 雾罩住该方向的近壳成员；发现感的真身=逐星 discoveryBoost）
    // R258：计算体收口顶层 [mapNebulaClustersV3]（大方法字节码瘦身——语句原样）
    val nebulae = remember(mapData) { mapNebulaClustersV3(mapData) }
    // ---- R373 深空星云复合体（design-nebula P2：星表密度场双源并置涌现——装配
    //      一次+数据换代一次（remember(mapData) 键控）；演示宇宙（Mock 网关）
    //      预标定保底——真实库（TdLib 网关）永不掺入；探针见 mapDeepSpaceR373） ----
    val demoModeR373 = gateway !is TdLibGateway
    val deepSpaceR373 = remember(mapData, demoModeR373) { mapDeepSpaceR373(mapData, demoModeR373) }
    // ---- R385 P4 语义联动（design-nebula §3.3-4）：复合体在场报告行——Stage
    //      装配一次 O(n)（金矿候选 complexIndexOf 容纳同口计数），String 经参
    //      穿透成就面板+信息卡词条浏览器（零卡内重算）；摇篮=0=null 整行不出
    //      （全库点亮=成就非缺口——AstroFacts.nebulaComplexLine 单口裁决） ----
    val nebulaComplexLineR385 = remember(deepSpaceR373, mapData) {
        nebulaComplexReportR385(deepSpaceR373, mapData)
    }
    // R385 滑移账（CanvasLayer 会话级——last/from 双 Result：数据换代 2s 平衡位移
    // 滑移窗状态；Stage 持有经参直供 CanvasLayer 帧组装消费，主线程单写读）
    val deepSpaceSlideR385 = remember { DeepSpaceSlideR385() }
    // R385 复合体快照发布+滑移账换代检测（装配期一次——ctx.complexesR385 帧循环
    // 只读：起飞点判定原料；滑移账 last 代基线更新+真变（格键身份集 diff——引用
    // 换代但身份集不变=零行为）才启动 2s 窗+diff 探针行；LaunchedEffect 键=装配
    // 体换代跟新，主线程单写读零竞态）
    LaunchedEffect(deepSpaceR373) {
        logicCtx.complexesR385.value = deepSpaceR373.complexes
        deepSpaceSlideR385.onGenerationR385(deepSpaceR373.complexes, deepSpaceR373.enrichClouds)
    }
    // ---- R234② 尘埃带渲染模型（源/带端点/粒子世界坐标一次烘焙——渲染期零计算除投影） ----
    val spreadRender = remember(spreadModel, stars) {
        spreadModel?.let { m -> buildSpreadRender(m, stars.associateBy { it.chatId }) }
    }

    // ---- R319 宇宙底幕：奥伯斯带装配+CMB 纹理预热（舞台件装载 effect——IO 协程
    //      进页一次离帧路径；effect 键 stars=换图（宣判换装/空名补捞拷贝发布）
    //      自动重算；差集收口顶层 [buildOlbersZoneR319] 不进装配期（预算余量+
    //      金矿 6h TTL 独立陈化两理由见其 KDoc）；CMB 烘焙毫秒级~几十毫秒离主
    //      线程付一次（R345 Canvas 退役后预热直调 GlTextureBaker 烘焙单源——
    //      @Synchronized 线程安全，GL 线程上传帧内恒缓存命中） ----
    val cmbZoneR319 = remember { mutableStateOf<CmbBackdropFx.OlbersZone?>(null) }
    LaunchedEffect(stars) {
        val seedFp = mapData?.genomeFp ?: 0L
        withContext(Dispatchers.IO) {
            GlTextureBaker.bakeCmbCached(seedFp)
            cmbZoneR319.value = buildOlbersZoneR319(vm, stars)
        }
    }

    // ---------------- 深空画布：天幕（方向星+星云）→ 盘面网格 → 星轨光路 →
    //      边+流光 → 星（径向渐变光晕/凝聚/爆闪/灯塔）→ 流星/彗星/爆散 →
    //      名签（全自绘零布局侵入） ----------------
    // R258：画布层整体迁出 TasteMapCanvasLayer（手势/命中/draw 语句原样——
    // 主方法字节码瘦身主承重件，语义零变化）
    // ---- R356 设置内迁接线（舞台态直供——画布层/快捷组共用；键/默认/写法与
    //      设置页退役行逐句同款=迁移语义零变化；舞台件持有=主方法零接线） ----
    val stagePrefsR356 = remember { AppPrefs(ctx.context.applicationContext) }
    val cmbOnR356 = remember { mutableStateOf(stagePrefsR356.tasteCmb) }
    val linkLinesOnR356 = remember { mutableStateOf(stagePrefsR356.tasteLinkLines) }
    val emergentOnR356 = remember { mutableStateOf(stagePrefsR356.tasteEmergentLayout) }
    val perturbOnR356 = remember { mutableStateOf(stagePrefsR356.tasteLinkPerturb) }
    var quickTogglesOpenR356 by remember { mutableStateOf(false) }
    var quickNoteR356 by remember { mutableStateOf("") }
    var voyageMenuOpenR356 by remember { mutableStateOf(false) }
    // 信息卡开=浮件让位（旧口径）——开态随手收起防回卡后残留
    LaunchedEffect(selectedStar) {
        if (selectedStar != null) {
            quickTogglesOpenR356 = false
            voyageMenuOpenR356 = false
        }
    }
    TasteMapCanvasLayer(
        ctx = logicCtx,
        stars = stars,
        scene = scene,
        wellMaxVol = wellMaxVol,
        starsById = starsById,
        nebulae = nebulae,
        // R373 深空复合体+富集云（装配派生直供——帧字段同名直通）
        complexes = deepSpaceR373.complexes,
        enrichClouds = deepSpaceR373.enrichClouds,
        // R385 滑移账（CanvasLayer 会话级直供——帧组装 2s 平衡位移滑移窗消费：
        // null=恒等直通帧零分配；窗内 slideOf 一次求值两字段共用）
        slideR385 = deepSpaceSlideR385,
        universeDirector = universeDirector,
        shipStyle = shipStyle,
        spread = spreadRender,
        // R319 宇宙底幕（帧字段原料直供——cmbOn 在层内 remember 回页重读生效）
        cmbSeedFp = mapData?.genomeFp ?: 0L,
        cmbZone = cmbZoneR319.value,
        // R356 底幕/连线开关舞台态直供（图标快捷组即时翻转——键/默认/门零变化）
        cmbOn = cmbOnR356,
        linkLinesOn = linkLinesOnR356,
        onPlayDust = onPlayDust,
        // R301：点行星→世界卡（宿主开卡+相机泊入；信息卡 tag 云同链见下）
        onPlanetTap = { entry, p -> openWorldCardR316(logicCtx, starsById, wellMaxVol, entry.chatId, p.tag) },
    )

    // ---------------- 加载 / 空态 ----------------
    val d = mapData
    when {
        d == null -> AppLoading(
            // R254 阶段文案（首开可见进度——缓存命中走不到这里，文案零变化）
            text = loadPhase?.let { "正在投影你的口味宇宙：$it" } ?: "正在投影你的口味宇宙…",
            modifier = Modifier.align(Alignment.Center),
        )
        d.stars.isEmpty() -> AppEmptyState(
            icon = Icons.Outlined.AutoAwesome,
            title = "还没有口味数据",
            subtitle = "订阅几个频道、刷一阵子，你的口味分布会在这里成像",
            modifier = Modifier.align(Alignment.Center),
        )
    }

    // ---------------- 左上统计胶囊 + 图例（R223b：天体口径统计 + 六态图例） ----------------
    // R258：整体迁出 TasteMapStatsLegend（genesisStats 派生随迁——单消费位）
    if (stars.isNotEmpty()) {
        TasteMapStatsLegend(
            ctx = logicCtx,
            mapData = mapData,
            stars = stars,
            linkCount = links.size,
            channelStars = channelStars,
            genesisSpan = genesisSpan,
        )
    }

    // ---------------- R356 星图快捷切换组（口味星图设置内迁——顶栏单触发钮
    //      点开下拉图标组；旧右上星座星标+延时时钟两钮退役=用户「跟其他功能
    //      合并」拍板：星座开关入组、延时入口入启航菜单。写键/vm 同步与设置
    //      页退役行逐句同款=迁移语义零变化） ----------------
    if (stars.isNotEmpty() && selectedStar == null) {
        TasteMapQuickTogglesR356(
            open = quickTogglesOpenR356,
            toggles = listOf(
                TasteQuickToggleR356(
                    label = "星座", hint = "星座 · 即时",
                    icon = Icons.Outlined.Star,
                    on = ctx.constellationOn.value,
                    onToggle = {
                        // 旧右上星标钮语句原样（R314 唯一星座开关口径——翻转
                        // ctx 态+写键，即时生效）
                        val next = !ctx.constellationOn.value
                        ctx.constellationOn.value = next
                        stagePrefsR356.tasteConstellation = next
                        quickNoteR356 = "星座 " + if (next) "开" else "关"
                    },
                ),
                TasteQuickToggleR356(
                    label = "底幕", hint = "底幕 · 即时",
                    icon = Icons.Outlined.BlurOn,
                    on = cmbOnR356.value,
                    onToggle = {
                        // 纯渲染件即时生效（舞台态直供画布层帧组装）
                        val next = !cmbOnR356.value
                        cmbOnR356.value = next
                        stagePrefsR356.tasteCmb = next
                        quickNoteR356 = "底幕 " + if (next) "开" else "关"
                    },
                ),
                TasteQuickToggleR356(
                    label = "连线", hint = "连线 · 即时",
                    icon = Icons.Outlined.Hub,
                    on = linkLinesOnR356.value,
                    onToggle = {
                        val next = !linkLinesOnR356.value
                        linkLinesOnR356.value = next
                        stagePrefsR356.tasteLinkLines = next
                        quickNoteR356 = "连线 " + if (next) "开" else "关"
                    },
                ),
                TasteQuickToggleR356(
                    label = "涌现", hint = "涌现 · 重进生效",
                    icon = Icons.Outlined.BubbleChart,
                    on = emergentOnR356.value,
                    onToggle = {
                        // 布局重算类：与设置页退役行同款双写（键+vm 运行时态）
                        val next = !emergentOnR356.value
                        emergentOnR356.value = next
                        stagePrefsR356.tasteEmergentLayout = next
                        vm.galaxyEmergentLayout = next
                        quickNoteR356 = "涌现 · 重进生效"
                    },
                ),
                TasteQuickToggleR356(
                    label = "聚拢", hint = "聚拢 · 重进生效",
                    icon = Icons.Outlined.Compress,
                    on = perturbOnR356.value,
                    onToggle = {
                        val next = !perturbOnR356.value
                        perturbOnR356.value = next
                        stagePrefsR356.tasteLinkPerturb = next
                        vm.galaxyLinkPerturb = next
                        quickNoteR356 = "聚拢 · 重进生效"
                    },
                ),
            ),
            note = quickNoteR356,
            onTrigger = { quickTogglesOpenR356 = !quickTogglesOpenR356 },
            onDismiss = { quickTogglesOpenR356 = false },
            onHint = { quickNoteR356 = it },
        )
    }

    // ---------------- R234① 尘埃带状态胶囊（TopCenter：tag+传播统计+关闭；
    //      R236 tags 倒排毫秒级——装载态承接冷缓存首查/异常回退；空态=该
    //      标签还没有跨频道传播记录） ----------------
    // R258：整体迁出 TasteMapSpreadCapsule
    val spreadTagNow = spreadTag
    if (spreadTagNow != null) {
        TasteMapSpreadCapsule(
            ctx = logicCtx,
            sTag = spreadTagNow,
            spreadRender = spreadRender,
            onClose = {
                spreadTag = null
                spreadModel = null
            },
        )
    }

    // ---------------- R356 启航径向菜单（探索条+右下导出组+延时入口收纳单钮
    //      ——用户「都收纳一下…太low了」：五件两字图标沿用旧钮=视觉延续；
    //      让位口径=旧探索条（信息卡/延时剧场/虫洞菜单在场=隐藏；剧场期导出
    //      不可达属收纳代价挂账 r356_notes §二C） ----------------
    if (selectedStar == null && stars.isNotEmpty() && !genesisOn && wormholeMenuTag == null) {
        TasteMapVoyageHubR356(
            open = voyageMenuOpenR356,
            actions = listOf(
                TasteVoyageActionR356("恒星", Icons.Outlined.NearMe) {
                    flyToNearestStarR258(ctx, channelStars, stars, wellMaxVol)
                },
                TasteVoyageActionR356("随机", Icons.Outlined.Shuffle) {
                    flyToRandomStarR258(ctx, channelStars, wellMaxVol)
                },
                // 延时=大爆炸自动正放（旧底中胶囊语义）；旧右上时钟「就位现在
                // 不播」变体随第二入口退役（R314 删重复入口同款判词——暂停/
                // 倍速/退出全在开态控制条）
                TasteVoyageActionR356("延时", Icons.Outlined.AutoAwesome, enabled = genesisSpan.valid) {
                    ctx.genesisOn.value = true
                    ctx.genesis01.value = 0f
                    ctx.genesisPlaying.value = true
                },
                TasteVoyageActionR356("日志", Icons.Outlined.MilitaryTech, busy = exportingVoyage) {
                    exportVoyagePosterR316(logicCtx, starsById, nebulae.size)
                },
                TasteVoyageActionR356("海报", Icons.Outlined.Share, busy = exporting) {
                    exportTasteMapPosterR316(logicCtx, stars, links)
                },
            ),
            onTrigger = { voyageMenuOpenR356 = !voyageMenuOpenR356 },
            onDismiss = { voyageMenuOpenR356 = false },
        )
    }

    // ---------------- R314 宇宙延时摄影控制（R356 收纳换代：关态胶囊入口退役
    //      →启航菜单「延时」件承载；开态控制条原样保留——播放/光年读数/倍速
    //      /退出=模式激活期的播放控制器非常驻入口） ----------------
    if (selectedStar == null && stars.isNotEmpty() && genesisOn && genesisSpan.valid) {
        TasteMapGenesisActiveBar(ctx = logicCtx, genesisSpan = genesisSpan)
    }

    // ---------------- 左下缩放按钮组（+/- 单指档位缩放，绕画布中心） ----------------
    // R258：整体迁出 TasteMapZoomButtons
    if (selectedStar == null && stars.isNotEmpty()) {
        TasteMapZoomButtons(ctx = logicCtx)
    }


    // ---------------- R235② 虫洞跳跃菜单（长按行星弹出：#tag → 共振恒星） ----------------
    // R258：整体迁出 TasteMapWormholeMenu
    val wTag = wormholeMenuTag
    if (wTag != null && selectedStar == null && stars.isNotEmpty()) {
        TasteMapWormholeMenu(
            ctx = logicCtx,
            wTag = wTag,
            starsById = starsById,
            wellMaxVol = wellMaxVol,
        )
    }

    // ---------------- R235① 侧栏「成就」入口（左缘中点竖钮——面板开关） ----------------
    // R258：整体迁出 TasteMapAchievementEntry
    if (stars.isNotEmpty() && selectedStar == null && wormholeMenuTag == null) {
        TasteMapAchievementEntry(
            rankArgb = TasteAchievements.colorOf(achievement?.rank ?: TasteAchievements.Rank.BRONZE),
            onClick = { showAchievements = true },
        )
    }

    // ---------------- R235① 成就面板（右侧抽屉：当前等阶+各勋章进度+下一阶差距） ----------------
    // R258：整体迁出 TasteMapAchievementPanel
    // R302：编年史勋章矩阵（面板/指引同一出处 chronMedalsOfR302——纪律§一）
    val chronMedals = remember(starlightBook, knownIds, mapData) {
        starlightBook?.let { chronMedalsOfR302(logicCtx, it) } ?: emptyList()
    }
    if (showAchievements) {
        TasteMapAchievementPanel(
            rep = achievement,
            book = starlightBook,
            chronMedals = chronMedals,
            guidance = guidanceLine,
            // R312 学习路径钩子：图鉴缺口「去哪找」的原料（kindCounts 键集=已见类
            // ——R352 起原料改穿透 mapData 整体，键集由面板内派生，口径零变）
            // R352 反茧房·探索边界段三原料：mapData+nebulae（雾区/产房派生）+
            // olbers zone（视界统计 rawDiff——与画布层底幕同一 zone 单源）
            mapData = mapData,
            olbers = cmbZoneR319.value,
            nebulae = nebulae,
            // R385 复合体在场报告行（Stage 装配一次直供——缺据 null 整行不出）
            nebulaComplexLineR385 = nebulaComplexLineR385,
            onClose = { showAchievements = false },
        )
    }

    // ---------------- R302 星辉罗盘 HUD（Lv2 解锁件——升级差 ≤20% 时顶部
    //      胶囊「再点亮 N 颗即升级」；屏缘 chevron 提亮同档（帧字段 hintBoost）） ----------------
    val starlightBookNow = starlightBook
    if (starlightBookNow != null && starlightBookNow.compassLit && selectedStar == null && !genesisOn) {
        StarlightCompassHudR302(starlightBookNow)
    }

    // ---------------- R302 曲速巡航 HUD（Lv6——巡航中停止口径提示） ----------------
    if (warpCruise) {
        WarpCruiseHudR302()
    }

    // ---------------- R302 升级演出（全屏光雨+跃迁波纹环+横幅 2.8s 一次性——
    //      UI 层演出，导演器不介入） ----------------
    if (levelUpEvent > 0) {
        StarlightLevelUpOverlayR302(
            level = levelUpEvent,
            onDone = { levelUpEvent = 0 },
        )
    }

    // ---------------- R312 点亮即时科普小卡（发现时刻的科普钩子：顶中
    //      overlay，LIT_TOAST_MS 自收；点=开该星信息卡看「为什么」；
    //      信息卡已开时不叠（点星场景下科普段就在卡里）） ----------------
    val litToastNow = litToast
    if (litToastNow != null && selectedStar == null) {
        AstroLitToastCard(
            toast = litToastNow,
            onOpen = {
                android.util.Log.i(
                    "TasteMapX",
                    "R312 点亮科普: 小卡点按→开信息卡 尾号${(litToastNow.chatId % 10000L + 10000L) % 10000L}",
                )
                selectedId = litToastNow.chatId
                litToast = null
            },
            onDone = { if (litToast === litToastNow) litToast = null },
        )
    }

    // ---------------- R248 点星信息卡重设计（星名点亮+编年史融入）：
    //      频道名（发现时刻在星上浮现，卡内常驻大字）→ 判词行（结论先行）
    //      → 代表作预览（点=播放）→ 内容构成条 → 相邻恒星（W 条关联·点=
    //      飞过去）→ 试吃/订阅/档案 → 展开档案=编年史卷宗（R246 设计的
    //      一期两卷：内容演化/更新律动——同 sheet 内折叠，不跳页）。
    //      旧卡段处置（同类挂账不静默删）：观看对比条撤出（判词行承担
    //      结论先行——R246 北极星）；R133 基因行=判词回落层；互动计数
    //      压入事实行；天体档案行原样保留。
    //      R253② 布局融合（用户图逐条）：固定头（频道名·星型徽章+判词行
    //      ——滚动不消失）→ 滚动体[基础信息区默认展开：代表作预览→发布
    //      节奏 24h（可点选）→内容趋势→个人足迹→天体档案→科普行→构成条
    //      →tag 行星云→相邻恒星→按钮行]→ [展开档案 ↓]折叠头 → 六卷全量
    //      （AnimatedVisibility/卷折叠原样）；整卡 drag-to-dismiss 下滑收起。 ----------------

    // ---------------- R248/R253 点星信息卡（StarInfoCard 独立组件——R253 God
    //      函数瘦身迁出：卡面+装载链整体移入；接口化仅 selectedId 回调/订阅
    //      覆盖/行星装配三处，语义零改） ----------------
    if (selectedStar != null) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            StarInfoCard(
                st = selectedStar,
                gateway = gateway,
                vm = vm,
                mapData = mapData,
                starsById = starsById,
                binaryByChat = binaryByChat,
                planetSysSnapshot = planetSysSnapshot,
                discoverFile = discoverFile,
                subscribedOverride = sessionSubscribed,
                // R319 观测视界半径（奥伯斯带 rObs 直供；0=zone 缺席=第七问缺据不出）
                cmbHorizonR = cmbZoneR319.value?.rObs ?: 0f,
                // R352 反茧房：茧房外差集计数（同一 zone 单拍读数——rawDiff 全量真值；
                // -1=zone 缺席缺据=第七问追加句不出）
                cmbHorizonBeyondN = cmbZoneR319.value?.rawDiff ?: -1,
                // R385 复合体在场报告行（Stage 装配一次直供——词条浏览器 nebula
                // 词条增段行；null=摇篮 0/缺据不出）
                nebulaComplexLineR385 = nebulaComplexLineR385,
                onSubscribeSession = { id -> sessionSubscribed = sessionSubscribed + id },
                onSelectStar = { id -> selectedId = id },
                onFlyStar = { s -> flyToStarR258(logicCtx, s, wellMaxVol) },
                // R254c 编译修复：局部函数已迁顶层 [assemblePlanetSystemR254]
                //（R254 大方法瘦身），此处经 logicCtx 接线（语义零变化）。
                assemblePlanets = { id -> assemblePlanetSystemR254(logicCtx, id) },
                onPlayDust = onPlayDust,
                onOpenChannel = onOpenChannel,
                // R301：信息卡 tag 云点 tag=开世界卡（与画布点行星同一去处——
                // 同行星语义同新家；卡内首键「直接开刷」=R269 内容流原链）
                onPlayTagContent = { tag ->
                    selectedStar?.let { openWorldCardR316(logicCtx, starsById, wellMaxVol, it.chatId, tag) }
                },
            )
        }
    }

    // ---------------- R233⑤ 点行星→R222 双选弹层（原样复用；宿主挂星图页） ----------------
    // 「时间轴探索」=容器路由开 hashtag 时间轴页；「搜索播放」=旧行径原样
    //（「标签：#tag」源混合双腿——链体在宿主收口，回星图返回语义同试吃链）。
    // R234① 加第三选「看传播」：选 tag→尘埃带（关键字在星系中的传播路径，
    // 与创世纪滑杆联动=传播时光机）。
    // R269：加首行「看内容·直接开刷」（onPlayTagContent=该 tag 全宇宙内容流
    // 纯本地直刷——波次二主路径，时间轴由此降为层内二级入口）。**触发收窄**：
    // 画布点行星已改直进内容流（tap 分支），本层现由飞进行星门触发（飞行
    // 接近意图不如点按明确——层可取消留图，再武装闸原样）。
    val choiceTag = planetChoiceTag
    if (choiceTag != null) {
        dev.tgfeed.ui.components.HashtagChoiceSheet(
            tag = choiceTag,
            onPlayContent = {
                onPlayTagContent(choiceTag)
                planetChoiceTag = null
            },
            onExploreTimeline = {
                onOpenHashtagTimeline(choiceTag)
                planetChoiceTag = null
            },
            onSearchPlay = {
                onHashtagSearchPlay(choiceTag)
                planetChoiceTag = null
            },
            onShowSpread = {
                spreadTag = choiceTag
                planetChoiceTag = null
            },
            onDismiss = { planetChoiceTag = null },
        )
    }

    // ---------------- R301 行星世界卡（点行星新默认——星图内半屏 sheet
    //      不退出：条数/时期弧/代表作封面墙/同 tag 其他恒星 pills/底三键
    //      [▶ 直接开刷]=R269 内容流原链首键；相机泊入环绕+关卡回位） ----------------
    val wcNow = worldCard
    if (wcNow != null) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            PlanetWorldCard(
                target = wcNow,
                starsById = starsById,
                planetSysSnapshot = planetSysSnapshot,
                vm = vm,
                gateway = gateway,
                app = app,
                onPlayDust = onPlayDust,
                onPlayContent = { tag -> startWorldDiveR316(logicCtx, starsById, wellMaxVol, wcNow.chatId, tag) },
                onOpenTimeline = { tag ->
                    // 离页出口（时间轴/搜索播放）：卡直接关不运镜回位（页将切走）
                    worldCard = null
                    worldCam.orbitEnabled = false
                    worldCam.returnActive = false
                    onOpenHashtagTimeline(tag)
                },
                onSearchPlay = { tag ->
                    worldCard = null
                    worldCam.orbitEnabled = false
                    worldCam.returnActive = false
                    onHashtagSearchPlay(tag)
                },
                onFlyToStar = { id, tag ->
                    // 同 tag 其他恒星=飞过去+卡片换绑（回位姿势保留最初开卡点）
                    openWorldCardR316(logicCtx, starsById, wellMaxVol, id, tag, keepPose = true)
                },
                onClose = { closeWorldCardR316(logicCtx) },
                // R312：科普行宇宙计数原料（信息卡同尺）
                kindCounts = mapData?.kindCounts.orEmpty(),
            )
        }
    }

    // ---------------- R301 A 案运镜叠加层（顶位）：进场白闪收束（feed→星图
    //      接缝）+ 世界卡俯冲白闪（星图→feed 接缝） ----------------
    if (entryFlash.value > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = entryFlash.value }
                .background(Color.White),
        )
    }
    if (diveFlash.value > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = diveFlash.value }
                .background(Color.White),
        )
    }

    // ---------------- R237e 首开引导浮层（一次性教学——治实锤①的另一半
    //      「用户不知道手势怎么用」：手势图解 + 双键（带我去最近的恒星
    //      =直接入井看行星层；先自己逛逛=关闭）。持久键 tgfeed_ui prefs；
    //      装配完成且星图非空才弹（按钮可用）；点任一键即持久不再弹） ----------------
    // R258：层体迁出 TasteMapGuideOverlay（dismissGuide 局部函数留主函数——
    // prefs 持久写语义原样）
    fun dismissGuide() {
        showGuide = false
        guidePrefs.edit().putBoolean(GUIDE_PREF_KEY, true).apply()
    }
    if (showGuide && mapData != null && stars.isNotEmpty()) {
        TasteMapGuideOverlay(
            onFlyNearest = {
                dismissGuide()
                flyToNearestStarR258(logicCtx, channelStars, stars, wellMaxVol)
            },
            onDismiss = { dismissGuide() },
        )
    }
}

// ==================== R258 结构性拆分（P0 VerifyError 根治——主方法字节码瘦身） ====================
//
// 病灶：TasteMapScreen 主 composable 内联树（Compose inline 语义下 Row/Column/Box/
// remember 计算体全部内联进主方法字节码）曾达 ~32k+ DEX units/359 寄存器——D8
// 寄存器分配产出 JVM 容忍但 ART 拒绝的 copy 型冲突（进页即 VerifyError，R253
// 16384 实测取证）。本节全部为主方法迁出件：**语句原样搬移、状态改经
// [GalaxyLogicCtx] 句柄读写，零行为变化**（R253 StarInfoCard/R254 逻辑体同款模式）。

/** R258 未探索星云映射体（主方法 remember 计算体迁出——语句原样零行为变化） */
private fun mapNebulaClustersV3(mapData: TasteMapData?): List<TasteNebula.NebulaCluster> {
    val clusters = mapData?.let { d ->
        TasteNebula.nebulaClusters(
            d.stars.map {
                TasteNebula.NebulaStarIn(
                    it.chatId, it.x, it.y, it.flightZ, it.watchSec, it.subscribed,
                    it.gVideo, it.gPhoto, it.gAudio,
                )
            },
        )
    }.orEmpty()
    val tmp = FloatArray(3)
    return clusters.map { c ->
        // 质心（口味系）→ 空间方向（R256 V3 口径：口味角+哈希 φ）→ 雾心=方向×620
        TasteSpace.spherifyV3(
            atan2(-(c.cy - 500f), c.cx - 500f),
            c.count * 31L + 7L,
            0.5f,
            tmp,
        )
        val len = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2])
        if (len > 1e-4f) {
            // R252 雾世界半径 460→240：460×焦距/620≈945px 屏半径巨幅圆片缩到
            // ~490px（配合 FOG_ALPHA_MAX 0.26→0.05——「发光大圆片」双半边根治）
            TasteNebula.NebulaCluster(
                tmp[0] / len * 620f, tmp[1] / len * 620f, tmp[2] / len * 620f,
                240f, c.colorArgb, c.count, c.dominant,
            )
        } else {
            c
        }
    }.also { mapped ->
        // R235 事件级探针：星云装配（团数/成员/类别——验证矩阵④的取证件）
        if (mapped.isNotEmpty()) {
            android.util.Log.i(
                "TasteMapX",
                "R235 星云装配: 团=${mapped.size} 成员=${mapped.sumOf { it.count }} " +
                    "类别=${mapped.joinToString(",") { "${it.dominant}${it.count}r${it.radius.toInt()}" }}（R242 空间映射）",
            )
        } else {
            android.util.Log.i("TasteMap", "R235 星云装配: 0 团（未探索频道不足或全被看过）")
        }
    }
}

/**
 * R373 深空星云复合体装配派生（design-nebula §3.2/§十一 富集锚——主方法
 * remember 计算体迁出件，mapNebulaClustersV3 同模式）：星表（位置真源 sp+
 * 诞生态注记+活跃度+色温）→ [DeepSpaceNebula.complexesOf] 双源密度场
 * （摇篮=未探索质量+候选加权；富集=全星表——同一低通管线两原料）。
 * **位置真源 agnostic**：只消费星表位（V3/涌现布局谁产的就跟谁——R303c
 * 换轨零接口改自动跟随）。[demo]=gateway 判 MockTdGateway（演示宇宙预标定
 * 保底——真实库永不掺入）。探针=装配一次+数据换代一次（remember(mapData)
 * 键控），脱敏纯计数（摇篮数/总质量/富集数/两帽——TasteMapX tag）。
 */
private fun mapDeepSpaceR373(mapData: TasteMapData?, demo: Boolean): DeepSpaceNebula.Result {
    if (mapData == null) return DeepSpaceNebula.Result(emptyList(), emptyList(), 0f)
    val nowSec = System.currentTimeMillis() / 1000L
    val stars = mapData.stars.map { s ->
        // lastActivityDays 装配期算好（core 纯函数确定性纪律——镜像
        // scanEmissionFogsR323 的口径：断更天数，-1=无证据）
        val lastActDays = if (s.lastActivitySec > 0L) (nowSec - s.lastActivitySec) / 86_400L else -1L
        DeepSpaceNebula.StarIn(
            chatId = s.chatId,
            x = s.x,
            y = s.y,
            spX = s.spX,
            spY = s.spY,
            spZ = s.spZ,
            watchSec = s.watchSec,
            subscribed = s.subscribed,
            goldCandidate = s.birth?.stage == TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE,
            recent7 = s.recent7,
            lastActivityDays = lastActDays,
            tempK = s.tempK,
        )
    }
    val out = DeepSpaceNebula.complexesOf(stars, demo)
    android.util.Log.i(
        "TasteMapX",
        "R373 复合体: 摇篮=${out.complexes.size} 质量=${"%.0f".format(out.cradleMass)} " +
            "富集=${out.enrichClouds.size} 帽=${DeepSpaceNebula.CPX_ALPHA_MAX}/${DeepSpaceNebula.ENRICH_ALPHA_MAX}" +
            if (demo) "（演示保底）" else "",
    )
    return out
}

/**
 * R385 复合体在场报告行派生（design-nebula §3.3-4——[nebulaComplexLineR385]
 * 的 remember 计算体）：金矿候选（NEBULA_CANDIDATE 注记）星以世界位过
 * [DeepSpaceNebula.complexIndexOf] 容纳判定**同口计数**（「最活跃候选」的最
 * 诚实可得代理——候选无活跃度梯度，云内在场=它将在这里点亮的证词）；三原料
 * （摇篮团数/云内候选数/富集云数）→ [AstroFacts.nebulaComplexLine] 单口组行。
 * 装配一次 O(候选数×≤4 团) 纯读；摇篮 0/星表缺席=null（单口诚实门，不冒充）。
 */
private fun nebulaComplexReportR385(
    deepSpace: DeepSpaceNebula.Result,
    mapData: TasteMapData?,
): String? {
    val cpx = deepSpace.complexes
    if (cpx.isEmpty()) return null
    val stars = mapData?.stars.orEmpty()
    var candidates = 0
    for (s in stars) {
        if (s.birth?.stage != TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE) continue
        if (DeepSpaceNebula.complexIndexOf(s.spX, s.spY, s.spZ, cpx) >= 0) candidates++
    }
    return AstroFacts.nebulaComplexLine(cpx.size, candidates, deepSpace.enrichClouds.size)
}

/**
 * R323 发射星云派生体（[mapNebulaClustersV3] 的**平行表**——与 nebulae 同序同长：
 * 第 i 条=第 i 团雾的发射判据/星胚粒数/亮档色/种子。r315 K① 团级门在批 5 侧组合：
 *  - **金矿候选位**=[TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE] 诞生态注记
 *   （R326/R327 宣判产出——未探索∧[TasteAstroPhysics.goldEvidenceOf] 星级单口；
 *   r325 F5「两批共享一个星级判据单口」的字面兑现）——**装配期两集合已有零新
 *   请求**（金矿快照缺失→注记零→占比 0→恒普通雾零冒充，结构上不可绕）；
 *  - **成员表**=[TasteNebula.nebulaCellMembers]（与 nebulaClusters 同内脏同序
 *   1:1——零聚类逻辑复制；同输入重跑同结果=与渲染雾团逐位对位）；
 *  - 普通雾=emission false（渲染端零绘制零成本早退——R235 雾笔刷现状原样）。
 * 装配探针 r315 K⑤ 口径（脱敏计数——发射 N/总 M 团+候选中位 K；V43 消费）。
 * R352：[logProbe] 默认 true（画布层装配口径零变）；成就面板探索边界段静默
 * 复用（logProbe=false——统计读数非装配，防重复 K⑤ 探针行）。
 */
private fun mapEmissionFogsR323(
    mapData: TasteMapData?,
    nebulae: List<TasteNebula.NebulaCluster>,
    logProbe: Boolean = true,
): List<EmissionNebulaFx.Fog> {
    if (mapData == null || nebulae.isEmpty()) return emptyList()
    // 金矿候选集合（星级位=NEBULA_CANDIDATE 注记直取零新判据）
    val goldIds = HashSet<Long>()
    for (s in mapData.stars) {
        if (s.birth?.stage == TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE) goldIds += s.chatId
    }
    // 雾团成员表（与渲染雾团同内脏同序——mapNebulaClustersV3 同输入同聚类）
    val starsIn = mapData.stars.map {
        TasteNebula.NebulaStarIn(
            it.chatId, it.x, it.y, it.flightZ, it.watchSec, it.subscribed,
            it.gVideo, it.gPhoto, it.gAudio,
        )
    }
    val cells = TasteNebula.nebulaCellMembers(starsIn)
    // 对位防御：长度不齐（聚类内脏变更的极端态）=全员普通雾（绝不对错位冒充发射）
    val aligned = nebulae.size == cells.size
    val out = ArrayList<EmissionNebulaFx.Fog>(nebulae.size)
    var emissionN = 0
    val candCounts = ArrayList<Int>(nebulae.size)
    for (i in nebulae.indices) {
        if (!aligned || i >= cells.size) {
            out += EmissionNebulaFx.Fog(false, 0, i, 0)
            continue
        }
        val (cellKey, members) = cells[i]
        var gold = 0
        for (m in members) {
            if (m.chatId in goldIds) gold++
        }
        candCounts += gold
        val emission = EmissionNebulaFx.isEmission(members.size, gold)
        if (emission) emissionN++
        val bright = if (emission) EmissionNebulaFx.brightColorOf(nebulae[i].colorArgb) else 0
        val embryos = if (emission) EmissionNebulaFx.embryoCountOf(gold) else 0
        out += EmissionNebulaFx.Fog(emission, embryos, cellKey, bright)
    }
    if (logProbe && nebulae.isNotEmpty()) {
        val sortedCands = candCounts.sorted()
        android.util.Log.i(
            "TasteMap",
            "R323 发射星云: 发射=${emissionN}/总=${nebulae.size} 团" +
                "（候选中位=${if (sortedCands.isEmpty()) 0 else sortedCands[sortedCands.size / 2]}）",
        )
    }
    return out
}

/** R258 望远镜倍率（+/- 按钮档位 1.5）：渲染 focal×lens——自由飞行的无障碍标配。
 *  主方法局部函数迁出（语句原样，状态经 ctx，零行为变化） */
private fun zoomByR258(ctx: GalaxyLogicCtx, factor: Float) {
    abortGalaxyTourR271(ctx, "望远镜变焦") // R271：任何手势立即中断让位
    ctx.focusFlight.active = false
    ctx.wormhole.active = false
    ctx.lens.value = GestureFeelPolicy.lensClamp(ctx.lens.value * factor) // R268b 量程收口政策件
}

/**
 * R342 D3 航行运镜单口（用户点击天体「去它那儿」类触发的收口——原多处
 * copy-paste 触发块去重）：focusFlight **voyage 模式**起手（时长=
 * GestureFeelPolicy.voyageSec(D0) 距离正比+下限——近星 1.2s/远星 3.6s
 * 「跨光年感」；easePose smoothstep=加速-巡航-缓出）。距离口径=N 体当帧
 * 渲染位（点=所见——与爆散投影同源判据）。
 * 消费面：双击天体 GOTO/探索运镜族 [flyToStarR258]（引导带飞+点未知星+
 * 信息卡邻星）/弧命中/世界卡泊入 openWorldCardR316。
 * **不走本口**（非航行语义——D3 协调声明见 r342_notes）：单击注目 glance
 * （注目非航行）/R271 巡航段（剧本已验证节奏）/R303c 银河视角（仪式位姿）/
 * feed 跳入 A 案（「睁眼就在星前」）/虫洞（传送语义）/世界卡俯冲（进场错觉）。
 */
private fun beginStarVoyageR342(
    ctx: GalaxyLogicCtx,
    s: StarNode,
    stop: Float,
) {
    val cam = ctx.cam.value
    val hasP = ctx.physics.value?.posOfChat(s.chatId, ctx.pollPos) == true
    val tx = if (hasP) ctx.pollPos[0] else s.spX
    val ty = if (hasP) ctx.pollPos[1] else s.spY
    val tz = if (hasP) ctx.pollPos[2] else s.spZ
    val d0 = TasteSpace.distTo(cam, tx, ty, tz)
    val tSec = GestureFeelPolicy.voyageSec(d0)
    ctx.focusFlight.beginVoyage(cam, tSec)
    ctx.focusFlight.tx = s.spX
    ctx.focusFlight.ty = s.spY
    ctx.focusFlight.tz = s.spZ
    ctx.focusFlight.stopDist = stop
    ctx.focusFlight.targetChatId = s.chatId // R249 v2：运镜目标跟随（N 体移动星）
    ctx.fling.yawVel = 0f
    ctx.fling.pitchVel = 0f
    ctx.fling.flyVel = 0f
    android.util.Log.i(
        "TasteMap",
        "R342 航行: 启航 目标尾号${(s.chatId % 10000L + 10000L) % 10000L} " +
            "距离=${d0.toInt()} 时长=${"%.1f".format(tSec)}s（距离正比+下限——旧 τ 恒 0.9s「嗖一下」对症刀）",
    )
}

/** R258 探索运镜（R237b 函数族——focusFlight 既有机制复用：井×4.6 停船口径与
 *  R233 feed 跳入/R235 虫洞同一式；零新相机数学）。主方法局部函数迁出零变化。
 *  R342 D3：运镜本体换航行档（距离正比时长——引导带飞/点未知星/信息卡邻星
 *  「飞过去」同一函数收三链）。 */
private fun flyToStarR258(ctx: GalaxyLogicCtx, s: StarNode, wellMaxVol: Int) {
    abortGalaxyTourR271(ctx, "手动探索运镜") // R271：手动带飞=巡航让位（本会话不再自动播）
    // R268b：停船收口政策件（井×4.6 钳 [80,260]）
    val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(s.itemsCount, wellMaxVol))
    beginStarVoyageR342(ctx, s, stop) // R342：航行档（时长=距离正比+下限）
    ctx.lockChatId.longValue = s.chatId
    val cv = ctx.canvasSize.value
    ctx.engine.burst(cv.width / 2f, cv.height / 2f, 8, 240f, (s.chatId % 100_000L).toInt())
    android.util.Log.i(
        "TasteMap",
        "R237 探索运镜: 目标尾号${(s.chatId % 10000L + 10000L) % 10000L} " +
            "停船=${stop.toInt()}（井×4.6 到达即行星层）",
    )
}

/** R258 飞往最近恒星：相机 3D 最近（优先有索引体量的频道星=行星层有材料；全特殊
 *  天体回退全体）；R249 v2：距离口径=N 体当帧渲染位。主方法局部函数迁出零变化 */
private fun flyToNearestStarR258(
    ctx: GalaxyLogicCtx,
    channelStars: List<StarNode>,
    stars: List<StarNode>,
    wellMaxVol: Int,
) {
    val pool = channelStars.filter { it.itemsCount > 0 }.ifEmpty { channelStars }.ifEmpty { stars }
    val phys = ctx.physics.value
    val cam = ctx.cam.value
    val best = pool.minByOrNull { s ->
        val hasNp = phys != null && phys.posOfChat(s.chatId, ctx.pollPos)
        val dx = (if (hasNp) ctx.pollPos[0] else s.spX) - cam.px
        val dy = (if (hasNp) ctx.pollPos[1] else s.spY) - cam.py
        val dz = (if (hasNp) ctx.pollPos[2] else s.spZ) - cam.pz
        dx * dx + dy * dy + dz * dz
    } ?: return
    flyToStarR258(ctx, best, wellMaxVol)
}

/** R258 飞往随机恒星：随机一颗有体量的频道星（探索偶遇语义——确定性不适用）。
 *  主方法局部函数迁出零变化 */
private fun flyToRandomStarR258(ctx: GalaxyLogicCtx, channelStars: List<StarNode>, wellMaxVol: Int) {
    val pool = channelStars.filter { it.itemsCount > 0 }.ifEmpty { channelStars }
    if (pool.isEmpty()) return
    flyToStarR258(ctx, pool[Random.nextInt(pool.size)], wellMaxVol)
}

/** R314：「回到全景」按钮已删（用户拍板：纯手势缩小即回全景——捏合至
 *  [TasteFlight.ZOOM_MIN] 全宇宙同框/左下「−」钮/R271 巡航缓出自动回原点，
 *  三条退路在场）；原 backToOverviewR258 随唯一调用点退役。 */

/**
 * R303c P3：**银河视角仪式运镜**（§7.2 L4 的仪式性入口——一键退到银心距 7000/
 * 俯角 25° 看全银河）。触发=**双击空白处**（双击天体=飞向它/双击空白=退看全貌，
 * 语义对称；手势集合零新增——R314「回到全景按钮已删」的教训：不加按钮复用
 * 既有双击；乘法缩放曲线（P3a）让纯捏合也可达 L4，本运镜给的是**固定仪式位**
 * （位置+朝向完整位姿，focusFlight 位姿模式缓动 τ=0.9s 电影感））。任何手势
 * 让位（focusFlight.active=false 既有语义）；目标位姿=TasteScaleSigma 单口。
 */
private fun startGalaxyViewR303c(ctx: GalaxyLogicCtx) {
    abortGalaxyTourR271(ctx, "银河视角") // R271：任何手势级运镜立即中断让位
    ctx.wormhole.active = false
    ctx.fling.yawVel = 0f
    ctx.fling.pitchVel = 0f
    ctx.fling.flyVel = 0f
    ctx.selectedId.value = null
    ctx.focusFlight.active = true
    ctx.focusFlight.poseMode = true
    ctx.focusFlight.poseTarget = TasteScaleSigma.galaxyViewPose()
    ctx.focusFlight.tau = TasteScaleSigma.GALAXY_VIEW_TAU_SEC
    ctx.focusFlight.targetChatId = 0L
    android.util.Log.i("TasteMap", "R303c 银河视角: 双击空白→银心位 7000/25°（位姿模式 τ=${ctx.focusFlight.tau}s）")
}

/** R258 虫洞跳跃（R235②：长按行星 tag → 共享该 tag 的另一颗恒星旁）：目标选星
 *  收口 TasteWormhole.pickTarget（在图优先→tag 计数降序→确定性）；transit 1.5s
 *  （camPathSpace 加速段+前冲鼓包；渲染器隧道叠加同 sin(π·p) 强度）；到达=井×4.6
 *  星锚停船。无目标=诚实 Toast。主方法局部函数迁出（语句原样零行为变化） */
private fun startWormholeR258(
    ctx: GalaxyLogicCtx,
    tag: String,
    fromChatId: Long,
    starsById: Map<Long, StarNode>,
    wellMaxVol: Int,
) {
    ctx.wormholeMenuTag.value = null
    abortGalaxyTourR271(ctx, "虫洞跳跃") // R271：手动跳跃=巡航让位
    val targetId = TasteWormhole.pickTarget(tag, fromChatId, ctx.tagProfiles.value, starsById.keys)
    if (targetId == 0L) {
        Toast.makeText(ctx.context, "该 tag 还没有别的恒星（没有共振证据不冒充虫洞）", Toast.LENGTH_LONG).show()
        return
    }
    val star = starsById[targetId] ?: return
    val cam = ctx.cam.value
    // R268b：停船收口政策件（井×4.6 钳 [80,260]）
    val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(star.itemsCount, wellMaxVol))
    ctx.wormhole.active = true
    ctx.wormhole.p01 = 0f
    ctx.wormhole.fromX = cam.px
    ctx.wormhole.fromY = cam.py
    ctx.wormhole.fromZ = cam.pz
    ctx.wormhole.fromYaw = cam.yawDeg
    ctx.wormhole.fromPitch = cam.pitchDeg
    ctx.wormhole.tx = star.spX
    ctx.wormhole.ty = star.spY
    ctx.wormhole.tz = star.spZ
    ctx.wormhole.stopDist = stop
    ctx.wormhole.targetChatId = targetId
    ctx.focusFlight.active = false
    ctx.fling.yawVel = 0f
    ctx.fling.pitchVel = 0f
    ctx.fling.flyVel = 0f
    ctx.selectedId.value = null
    android.util.Log.i(
        "TasteMapX",
        "R235 虫洞跳跃: tag=#$tag 自尾号${(fromChatId % 10000L + 10000L) % 10000L} " +
            "→尾号${(targetId % 10000L + 10000L) % 10000L} 停船=${stop.toInt()}",
    )
}

/**
 * R258 深空画布层（主方法 Canvas 巨块整体迁出——手势/命中测试/draw/锚点环语句
 * 原样，状态经 [GalaxyLogicCtx] 句柄读写，零行为变化）：天幕→网格→星轨→边+流光
 * →星→流星/彗星/爆散→名签（渲染器全自绘零布局侵入）；单击=尘埃>行星>弧>恒星
 * 命中优先级、双击=锁定+运镜、长按行星=虫洞菜单、飞行手势=环顾/前进/捏合。
 * tapStar/tapPlanet/tapDust/tapArc 为本函数局部函数（原主方法局部函数随迁——
 * 编译产物本就是独立 synthetic 方法，字节码归属零变化）；arcs/时间光标/星座门
 * 派生 remember 随迁（单消费位）。
 */
/**
 * R309b tap 命中件（行星/尘埃共用）：hit=命中对象；inBody=落点在行星**视觉本体盘
 * 内**（纯本体半径，不含 14dp slop 地板——贴脸档本体盘远大于 slop，盘内点按=
 * 明确行星意图）；nearestPx=最近候选屏距（未命中也有值——「行星公转扫过尘埃环、
 * 盘内恒有尘粒抢命中」的定量取证件，点按罕发代价可忽略）。
 * R309c 起分发层消费 inBody：盘内=行星赢尘埃（世界卡语义），盘外尘埃优先不变。
 */
private data class TapHit<T>(val hit: T?, val inBody: Boolean, val nearestPx: Float)

@Composable
private fun TasteMapCanvasLayer(
    ctx: GalaxyLogicCtx,
    stars: List<StarNode>,
    scene: FlightScene,
    wellMaxVol: Int,
    starsById: Map<Long, StarNode>,
    nebulae: List<TasteNebula.NebulaCluster>,
    /** R373 深空复合体（摇篮——密度场涌现；空=复合体层零绘制零成本早退） */
    complexes: List<DeepSpaceNebula.Complex>,
    /** R373 富集反射微光云（终审增补——热区色温从动） */
    enrichClouds: List<DeepSpaceNebula.EnrichCloud>,
    /** R385 平衡位移滑移账（CanvasLayer 会话级——数据换代 2s 滑移窗；帧组装
     *  [DeepSpaceSlideR385.frameOfR385] 消费：null=恒等直通帧零分配，R303c 口径） */
    slideR385: DeepSpaceSlideR385,
    /** R282 宇宙导演器状态（星云漂移相位读口——帧组装摇曳用；只读不写） */
    universeDirector: UniverseDirector.State,
    shipStyle: TasteAchievements.ShipStyle,
    spread: TasteSpreadRender?,
    /** R319 宇宙底幕：CMB 纹理种子（genomeFp 基因指纹——同库同纹理换库换底色；
     *  位图/纹理按它缓存，变更窗才重烘重传；0=演示/无基因组=固定种子） */
    cmbSeedFp: Long = 0L,
    /** R319 奥伯斯带（R_obs+暗点世界位+差集记账；null=星表空缺据整层缺省——
     *  舞台件装载 effect 产出直供，帧字段 cmbZone 同名直通） */
    cmbZone: CmbBackdropFx.OlbersZone? = null,
    /** R356 底幕开关改舞台态直供（图标快捷组即时翻转——旧「层内 remember 进页
     *  一次读」随设置页入口退役；帧组装逐帧取 .value，键/默认/渲染门零变化） */
    cmbOn: MutableState<Boolean>,
    /** R356 互链连线开关同款（舞台态直供——即时翻转；键/默认/门零变化） */
    linkLinesOn: MutableState<Boolean>,
    onPlayDust: (item: VideoItem, batch: List<VideoItem>, label: String) -> Unit,
    // R301：点行星→行星世界卡（design-dual-form §3 终审批准的行为变更——R269 直进
    // 内容流退役为卡内首键；宿主开卡+相机泊入）
    onPlanetTap: (entry: TastePlanetEntry, planet: TasteTagPlanets.TagPlanet) -> Unit,
) {
    val genesisSpan = scene.span
    // 名签浮现门槛（R152c 口径）：屏幕半径达到 5dp 才挂牌；避让在绘制端做（共享
    // ShareCard.placeTasteLabels——海报与 App 同一个避让实现），撞车只留胜者
    val density = LocalDensity.current
    val labelMinPx = with(density) { 5.dp.toPx() }
    val labelTextPx = with(density) { 11.dp.toPx() }
    // 点星命中兜底半径：小星至少 22dp 热区（R268b 热区族收口 GestureFeelPolicy）
    val hitSlopPx = with(density) { GestureFeelPolicy.STAR_HIT_MIN_DP.dp.toPx() }
    val planetHitPx = with(density) { GestureFeelPolicy.PLANET_HIT_DP.dp.toPx() }
    val arcHitPx = with(density) { GestureFeelPolicy.ARC_HIT_DP.dp.toPx() }
    // 投影临时数组（命中测试复用；渲染器自持另一份——R242 投影写 4 元组）
    val projPt = remember { FloatArray(4) }
    // screenRay 射线暂存（tapStar 射线法）
    val rayScratch = remember { FloatArray(3) }
    // 弧命中二次贝塞尔采样暂存
    val arcScratch = remember { FloatArray(4) }
    // R237a 锚点视觉反馈持有（draw 相位直读——手势机器每批同步；随迁件：唯一消费位）
    val flightAnchor = remember { FlightGestureMachine.AnchorUi() }

    // ---- R234② 时间联动派生量：now 扩展有效光标 + 星座轨迹可见门（随迁件） ----
    val todayStartMs = remember {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    // ---- R323 发射星云平行表（与 nebulae 同序同长——组件内派生不进主方法；
    //      nebulae 与 ctx.mapData 同源于 mapData（主方法 remember(mapData)），
    //      键=nebulae 即随 mapData 重派生（宣判换装=NEBULA_CANDIDATE 注记落地窗）） ----
    val emissionFogsR323 = remember(nebulae) { mapEmissionFogsR323(ctx.mapData.value, nebulae) }
    val nowMs = remember { System.currentTimeMillis() } // 会话期取值（跨午夜拖滑杆属极端，忽略）
    // 有效光标：延时摄影开=光标 0..1 → [星生min, now]（now 扩展——星系先凝成、后段
    // 覆盖近期时代）；延时摄影关=MAX_VALUE（一切尘埃恒可见=「现在」态）
    val spreadCursorMs = if (ctx.genesisOn.value && genesisSpan.valid) {
        TasteTagSpread.effCursorMs(genesisSpan.minMs, ctx.genesis01.value, nowMs)
    } else {
        Long.MAX_VALUE
    }
    // 星座层可见门（R314 星轨=星座合并：暗光路/流星/亮足迹同一门径）：持久开关开
    // ∧（延时摄影关 ∨ 有效光标已到「今天」）——未来时代不提前泄底今晚的足迹；
    // R234「滑杆拖到今天自动点亮」双门径随持久开关退役（用户显式开关优先）
    val showConstellation = ctx.constellationOn.value &&
        (!ctx.genesisOn.value || !genesisSpan.valid || spreadCursorMs >= todayStartMs)

    // ---- R356 互链连线/宇宙底幕两开关：旧「层内 remember 进页一次读」退役
    //      （设置入口内迁星图图标组后常量读法=翻转后画面不变像坏了）——改舞台
    //      件持有 MutableState 经 [linkLinesOn]/[cmbOn] 参数直供，帧组装逐帧取
    //      .value（R319 帧字段口径保留；taste_constellation 同族即时生效；
    //      键/默认/渲染门零变化） ----

    // ---- R233③ 内容关联弧（焦点态=点选恒星 ∨ 飞近恒星；判据/排序收口
    //      core.TasteTagAffinity；端点空间坐标烘焙进弧——渲染/命中零查表） ----
    val arcs = remember(ctx.selectedId.value, ctx.nearFocusId.longValue, ctx.tagProfiles.value, stars) {
        val focusId = ctx.selectedId.value ?: ctx.nearFocusId.longValue.takeIf { it != 0L }
        if (focusId == null || focusId == 0L) return@remember emptyList<TasteArc>()
        val focusTags = ctx.tagProfiles.value[focusId] ?: return@remember emptyList()
        if (focusTags.isEmpty()) return@remember emptyList()
        val byId = stars.associateBy { it.chatId }
        val focusStar = byId[focusId] ?: return@remember emptyList()
        // R233 焦点态弧探针（事件级取证：焦点星切换/指纹表变更时弧表重算）
        android.util.Log.i(
            "TasteMap",
            "R233 焦点弧: 焦点尾号${(focusId % 10000L + 10000L) % 10000L} " +
                "指纹=${focusTags.size}tag 已知频道=${ctx.tagProfiles.value.size}（弧表即将重算）",
        )
        // 弧色=焦点星色相混白（共振色——与互链白流光区分；tag 输出脱敏不进色）
        val fHsv = FloatArray(3)
        android.graphics.Color.colorToHSV(focusStar.bodyColor, fHsv)
        fHsv[1] = fHsv[1].coerceAtMost(0.35f)
        fHsv[2] = maxOf(fHsv[2], 0.88f)
        val color = android.graphics.Color.HSVToColor(fHsv)
        val targets = TasteTagAffinity.arcTargets(focusTags, focusId, ctx.tagProfiles.value)
        if (targets.isEmpty()) {
            android.util.Log.i("TasteMap", "R233 焦点弧: 0 条（无共振伙伴）")
            emptyList()
        } else {
            val focus = focusStar
            targets.mapNotNull { t ->
                val p = byId[t.chatId] ?: return@mapNotNull null
                TasteArc(
                    focus.spX, focus.spY, focus.spZ,
                    p.spX, p.spY, p.spZ,
                    t.chatId, color,
                    0.45f + 0.35f * t.jaccard,
                    focus.chatId, // R249 v2：源端 chatId（N 体端点跟随）
                )
            }
        }
    }

    // 点星命中测试（**screenRay 射线法**——任务书点选口径）：屏幕点 → 视线射线 →
    // 逐星「点到射线垂距 vs 屏半径热区」（热区世界半径 = bodyR ∨ slop 换算）；
    // 创世纪开启时未诞生的星不参与命中（宇宙还没凝出它，点了也不该选上）。
    // R223b：命中半径走体量主轴口径 bodyRadius（App 交互语义——与渲染器一致）。
    // R249 v2：**点=所见**——命中走 N 体当帧渲染位（与渲染 pass1 同一位置来源）。
    fun tapStar(pos: Offset): StarNode? {
        val cv = ctx.canvasSize.value
        if (cv.width <= 0 || cv.height <= 0) return null
        val cx = cv.width / 2f
        val cy = cv.height / 2f
        val cam = ctx.cam.value
        val focal = TasteSpace.focalOf(cv.width.toFloat(), cv.height.toFloat()) * ctx.lens.value
        val ray = TasteSpace.screenRay(pos.x, pos.y, cam, focal, cx, cy, rayScratch)
        val genesis = ctx.genesisOn.value && genesisSpan.valid
        val phys = ctx.physics.value
        val usePhys = phys != null && phys.n == stars.size
        var best: StarNode? = null
        var bestD2 = Float.MAX_VALUE
        for (i in stars.indices) {
            val s = stars[i]
            if (genesis && !TasteFlight.bornVisible(s.firstSeenMs, genesisSpan, ctx.genesis01.value)) continue
            val px = if (usePhys) phys!!.renderX[i] else s.spX
            val py = if (usePhys) phys!!.renderY[i] else s.spY
            val pz = if (usePhys) phys!!.renderZ[i] else s.spZ
            val dx = px - cam.px
            val dy = py - cam.py
            val dz = pz - cam.pz
            val t = dx * ray[0] + dy * ray[1] + dz * ray[2] // 沿射线深度
            if (t < TasteSpace.NEAR) continue // 背后/贴脸不命中
            val perp2 = dx * dx + dy * dy + dz * dz - t * t
            // 命中半径（世界单位）：星本体 ∨ STAR_HIT_MIN_DP(22dp) 屏热区换算（slop×深度/focal）
            val r = max(s.bodyRadius, hitSlopPx * t / focal)
            if (perp2 <= r * r && perp2 < bestD2) {
                bestD2 = perp2
                best = s
            }
        }
        return best
    }

    // ---- R233 行星/尘埃/弧命中（与渲染端同一投影/井深比/轨道几何单口；
    //      R249 v2：行星中心=恒星 N 体当前位——与渲染同一口径） ----
    // R309b：返回值升级 TapHit（命中对+本体盘内旗标+最近候选屏距——语义见件注释）
    fun tapPlanet(pos: Offset): TapHit<Pair<TastePlanetEntry, TasteTagPlanets.TagPlanet>> {
        val cv = ctx.canvasSize.value
        if (cv.width <= 0 || cv.height <= 0) return TapHit(null, false, Float.MAX_VALUE)
        val cx = cv.width / 2f
        val cy = cv.height / 2f
        val cam = ctx.cam.value
        val focal = TasteSpace.focalOf(cv.width.toFloat(), cv.height.toFloat()) * ctx.lens.value
        val phys = ctx.physics.value
        var bestEntry: TastePlanetEntry? = null
        var bestPlanet: TasteTagPlanets.TagPlanet? = null
        var bestD2 = Float.MAX_VALUE
        var bestInBody = false
        var nearestD2 = Float.MAX_VALUE
        for (entry in ctx.planetSysSnapshot.value.values) {
            val s = starsById[entry.chatId] ?: continue
            val hasPp = phys?.posOfChat(s.chatId, ctx.pollPos) == true
            val psx = if (hasPp) ctx.pollPos[0] else s.spX
            val psy = if (hasPp) ctx.pollPos[1] else s.spY
            val psz = if (hasPp) ctx.pollPos[2] else s.spZ
            val ratio = galaxyWellRatio(psx, psy, psz, s.itemsCount, wellMaxVol, cam)
            if (ratio >= TasteTagPlanets.APPEAR_RATIO) continue
            val seed = (s.chatId % 100_000L).toInt()
            val inc = galaxyOrbitInclination(seed)
            val planets = entry.system.planets
            val maxShow = min(planets.size, TasteTagPlanets.LOD_NEAR_PLANETS)
            // R328 成型档命中门同式（**点=所见**——渲染端成型坡道放缓后命中域随
            // 坡道同门：成型态行星慢浮现=慢可点；非成型=APPEAR_RAMP 逐位等值）
            val rampW = if (s.birth?.planetsForming == true) {
                TasteBirthFx.formingRampWidth(TasteTagPlanets.APPEAR_RAMP)
            } else {
                TasteTagPlanets.APPEAR_RAMP
            }
            for (i in 0 until maxShow) {
                val p = planets[i]
                if (TasteTagPlanets.appearAlpha(ratio, i, maxShow, rampW) < 0.35f) break
                val rW = planetOrbitRadius(p, s.bodyRadius) // R247 渲染同一式（bodyR 锚）
                // R249 开普勒三件（渲染/命中同一式——椭圆+真近点角+近日点偏角）
                val nu = TasteTagPlanets.keplerTheta(p, ctx.engine.timeSec)
                val rOrb = TasteTagPlanets.keplerOrbitRadius(p, rW, nu)
                planetOrbitPoint(psx, psy, psz, rOrb, nu + TasteTagPlanets.keplerPeriapsis(p), inc, ctx.orbitScratch)
                if (!TasteSpace.project(ctx.orbitScratch[0], ctx.orbitScratch[1], ctx.orbitScratch[2], cam, focal, cx, cy, projPt)) continue
                val dx = pos.x - projPt[0]
                val dy = pos.y - projPt[1]
                val d2 = dx * dx + dy * dy
                if (d2 < nearestD2) nearestD2 = d2
                val bodyPx = planetWorldRadius(p, s.bodyRadius, rW) * projPt[3] // 视觉本体屏半径
                val r = max(bodyPx, planetHitPx)
                if (d2 <= r * r && d2 < bestD2) {
                    bestD2 = d2
                    bestEntry = entry
                    bestPlanet = p
                    bestInBody = d2 <= bodyPx * bodyPx
                }
            }
        }
        val hit = if (bestEntry != null && bestPlanet != null) bestEntry!! to bestPlanet!! else null
        return TapHit(hit, hit != null && bestInBody, kotlin.math.sqrt(nearestD2))
    }

    /** 尘埃命中（贴脸档）：hit=（条目表下标, 行星）——调用方据此取 VideoItem 播放；
     *  nearestPx=最近尘粒屏距（R309b 取证件，语义见 TapHit 注释） */
    fun tapDust(pos: Offset): TapHit<Pair<TasteTagPlanets.TagPlanet, Int>> {
        val dustId = ctx.dustChatId.longValue
        val cv = ctx.canvasSize.value
        if (dustId == 0L || cv.width <= 0) return TapHit(null, false, Float.MAX_VALUE)
        val entry = ctx.planetSysSnapshot.value[dustId] ?: return TapHit(null, false, Float.MAX_VALUE)
        val s = starsById[dustId] ?: return TapHit(null, false, Float.MAX_VALUE)
        val cam = ctx.cam.value
        val phys = ctx.physics.value
        val hasDp = phys?.posOfChat(s.chatId, ctx.pollPos) == true
        val dsx = if (hasDp) ctx.pollPos[0] else s.spX
        val dsy = if (hasDp) ctx.pollPos[1] else s.spY
        val dsz = if (hasDp) ctx.pollPos[2] else s.spZ
        val dist = TasteSpace.distTo(cam, dsx, dsy, dsz)
        if (TasteTagPlanets.dustAlpha(dist) <= 0f) return TapHit(null, false, Float.MAX_VALUE)
        val cx = cv.width / 2f
        val cy = cv.height / 2f
        val focal = TasteSpace.focalOf(cv.width.toFloat(), cv.height.toFloat()) * ctx.lens.value
        val seed = (dustId % 100_000L).toInt()
        val inc = galaxyOrbitInclination(seed)
        val items = entry.items
        var bestIdx = -1
        var bestPlanet: TasteTagPlanets.TagPlanet? = null
        var bestD2 = Float.MAX_VALUE
        var nearestD2 = Float.MAX_VALUE
        for (p in entry.system.planets) {
            val rW = planetOrbitRadius(p, s.bodyRadius) // R247 渲染同一式（bodyR 锚）
            val peri = TasteTagPlanets.keplerPeriapsis(p) // R249 开普勒件（同一式）
            for (idx in p.itemIdx) {
                val item = items.getOrNull(idx) ?: continue
                val ang = TasteTagPlanets.dustAngleOf(item.dateEpochSec.toLong(), p.dustMinSec, p.dustMaxSec)
                val jr = TasteTagPlanets.keplerOrbitRadius(p, rW, ang) *
                    TasteTagPlanets.dustRadiusCoef(seed, idx)
                planetOrbitPoint(dsx, dsy, dsz, jr, ang + peri, inc, ctx.orbitScratch)
                if (!TasteSpace.project(ctx.orbitScratch[0], ctx.orbitScratch[1], ctx.orbitScratch[2], cam, focal, cx, cy, projPt)) continue
                val dx = pos.x - projPt[0]
                val dy = pos.y - projPt[1]
                val d2 = dx * dx + dy * dy
                if (d2 < nearestD2) nearestD2 = d2
                if (d2 <= planetHitPx * planetHitPx && d2 < bestD2) {
                    bestD2 = d2
                    bestIdx = idx
                    bestPlanet = p
                }
            }
        }
        val hit = if (bestIdx >= 0 && bestPlanet != null) bestPlanet!! to bestIdx else null
        return TapHit(hit, false, kotlin.math.sqrt(nearestD2))
    }

    /** 弧命中：屏幕二次贝塞尔 20 点采样（galaxyArcControl 单口——与绘制同一式） */
    fun tapArc(pos: Offset): TasteArc? {
        val cv = ctx.canvasSize.value
        if (arcs.isEmpty() || cv.width <= 0) return null
        val cx = cv.width / 2f
        val cy = cv.height / 2f
        val cam = ctx.cam.value
        val focal = TasteSpace.focalOf(cv.width.toFloat(), cv.height.toFloat()) * ctx.lens.value
        var best: TasteArc? = null
        var bestD2 = Float.MAX_VALUE
        val r2 = arcHitPx * arcHitPx
        for (arc in arcs) {
            if (!TasteSpace.ahead(arc.ax, arc.ay, arc.az, cam) || !TasteSpace.ahead(arc.bx, arc.by, arc.bz, cam)) continue
            TasteSpace.project(arc.ax, arc.ay, arc.az, cam, focal, cx, cy, projPt)
            val x1 = projPt[0]
            val y1 = projPt[1]
            TasteSpace.project(arc.bx, arc.by, arc.bz, cam, focal, cx, cy, projPt)
            val x2 = projPt[0]
            val y2 = projPt[1]
            galaxyArcControl(arc.ax, arc.ay, arc.az, arc.bx, arc.by, arc.bz, arcScratch)
            TasteSpace.project(arcScratch[0], arcScratch[1], arcScratch[2], cam, focal, cx, cy, projPt)
            val x3 = projPt[0]
            val y3 = projPt[1]
            // 20 点采样二次贝塞尔（足够密——弧长量级的段距 < 命中带宽）
            for (k in 0..20) {
                val t = k / 20f
                val u = 1f - t
                val qx = u * u * x1 + 2f * u * t * x3 + t * t * x2
                val qy = u * u * y1 + 2f * u * t * y3 + t * t * y2
                val dx = pos.x - qx
                val dy = pos.y - qy
                val d2 = dx * dx + dy * dy
                if (d2 <= r2 && d2 < bestD2) {
                    bestD2 = d2
                    best = arc
                }
            }
        }
        return best
    }

    // ==================== R277 渲染引擎解析（R345 GL-only 收口——政策件 GlEnginePolicy 唯一判定口） ====================
    // 用户 21:0x 裁决「新引擎已经好了，把老的干掉」：Canvas 老渲染引擎退役——GL 恒为
    // 唯一引擎。es3 探针未过或 GL 帧内致命 → **诚实降级**（黑屏+「渲染引擎诊断」行
    // +R277 探针日志+重试钮重建 GL 场景——失败可见可修，不再静默回 Canvas）。
    // 崩溃计数（帧内异常账+会话旗标 native 崩覆盖账）保留为诊断账本：≥2 诊断行升级
    // 「反复崩溃」提示；健康会话 30s 自愈清零。
    val glPrefs = remember { AppPrefs(ctx.app) }
    var glDegraded by remember { mutableStateOf(false) } // 本会话诚实降级沿（GL 致命后诊断件接管槽位）
    val glRetryNonce = remember { androidx.compose.runtime.mutableIntStateOf(0) } // 重试计数（key 换代=GL 场景整件重建）
    val glRendererState = remember { mutableStateOf<TasteGlRenderer?>(null) }
    val glMainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val glResolved = remember {
        // 冷启动会话旗标决算（native 崩覆盖闸——政策件纯函数判定）
        if (glPrefs.glSessionActive) {
            glPrefs.glCrashCount = GlEnginePolicy.crashCountOnColdStart(true, glPrefs.glCrashCount)
            glPrefs.glSessionActive = false
            android.util.Log.i(
                "TasteMapX",
                "R277 会话旗标决算: 上次 GL 会话非正常结束——崩溃计数=${glPrefs.glCrashCount}",
            )
        }
        // R345 旧偏好语义收口：render_engine=1（Canvas 档）已退役——读到旧值映射 GL+日志一行
        if (GlEnginePolicy.legacyCanvasPref(glPrefs.renderEngine)) {
            android.util.Log.i(
                "TasteMapX",
                "R345 Canvas 档退役: 旧偏好 render_engine=1 → 映射 GL（渲染引擎唯一=OpenGL）",
            )
        }
        val es3 = when (glPrefs.glEs3Probe) {
            1 -> true
            2 -> false
            else -> probeEs3(ctx.app).also { glPrefs.glEs3Probe = if (it) 1 else 2 }
        }
        GlEnginePolicy.resolve(es3).also { r ->
            android.util.Log.i(
                "TasteMapX",
                "R277 引擎解析: 档=OPENGL es3=$es3 " +
                    "崩溃计数=${glPrefs.glCrashCount} → ${r.engine}（${r.reason}）",
            )
        }
    }
    val glActive = glResolved.engine == GlEnginePolicy.Engine.OPENGL && !glDegraded
    // GL 帧内异常 → 主线程诚实降级+落账（渲染器回调在 GL 线程——post 回主线程写态）
    val onGlFatal: (Throwable) -> Unit = remember {
        { _ ->
            glMainHandler.post {
                glPrefs.glCrashCount = glPrefs.glCrashCount + 1
                glPrefs.glSessionActive = false
                glDegraded = true
                android.util.Log.i("TasteMapX", "R345 GL 降级: 帧内异常——崩溃计数=${glPrefs.glCrashCount} 本会话转诊断行（不再静默回 Canvas）")
            }
        }
    }
    // GL 健康会话自愈（30s 零异常里程——崩溃计数清零；强停假阳性由此兜回）
    val onGlHealthy: () -> Unit = remember {
        {
            glMainHandler.post {
                if (GlEnginePolicy.shouldResetCrashCount(GlEnginePolicy.HEALTHY_RESET_MS, glPrefs.glCrashCount)) {
                    glPrefs.glCrashCount = 0
                    android.util.Log.i("TasteMapX", "R277 自愈: 健康会话 30s——崩溃计数清零")
                }
            }
        }
    }
    // 帧率监管（Choreographer 帧回调+掉帧计数——GL 档/诊断降级档同口径出数）
    val frameMonitor = remember(glActive) { GlFrameMonitor(if (glActive) "GL" else "诊断") }
    DisposableEffect(glActive) {
        frameMonitor.start()
        onDispose { frameMonitor.stop() }
    }
    // GL 会话旗标回调（立/清与渲染活性同步——后台暂停=清（进程被杀不算 GL 崩溃））
    val glSessionActive: (Boolean) -> Unit = remember {
        { active -> glPrefs.glSessionActive = active }
    }
    DisposableEffect(Unit) {
        onDispose { if (glActive) glPrefs.glSessionActive = false } // 干净退出清旗
    }
    // GL 叠加层名签件（Compose 层——混合架构红利；避让共享 ShareCard.placeTasteLabels）
    val glLabelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() } }
    val glSelLabelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val glLabelCands = remember { ArrayList<ShareCard.TasteLabelCand>(12) }

    // ---- R277 帧组装提取（两引擎同一组装段——TasteFlightFrame 契约一字不动） ----
    fun assembleFlightFrame(): TasteFlightFrame {
        // R302 迷雾帧级质心（无点亮星=无雾：reach 归 0；纯算术零分配——
        // r302MistBuf 模块级暂存同 r282Proj 先例）
        val mistOn = mistFrameR302(
            scene.stars, ctx.knownIds.value, ctx.starlightBook.value?.level ?: 1, r302MistBuf,
        )
        // R385 平衡位移滑移消费（§3.3-3 R303c 口径：数据换代 2s 窗内
        // [DeepSpaceNebula.slideOf] 一次求值两字段（complexes/enrichClouds）共用
        // 直供漂移摇曳——质心/半径线性滑移+新云半径自 0 凝出+消散云即刻退场；
        // null=恒等直通（首装/无窗/窗已关）零分配直用装配原值）
        val slidR385 = slideR385.frameOfR385(complexes, enrichClouds)
        // R373 复合体+富集云漂移摇曳（R282 同款量化缓存——帧组装一次求值两字段共用）
        val driftedR373 = driftDeepSpaceR373(
            slidR385?.complexes ?: complexes,
            slidR385?.enrichClouds ?: enrichClouds,
            universeDirector,
        )
        return TasteFlightFrame(
        cam = ctx.cam.value,
        lens = ctx.lens.value,
        stars = scene.stars,
        edges = scene.edges,
        engine = ctx.engine,
        genesisOn = ctx.genesisOn.value,
        genesisSpan = genesisSpan,
        genesisT01 = ctx.genesis01.value,
        playingChatId = ctx.playingChatId.value,
        selectedId = ctx.selectedId.value ?: 0L,
        labelMinPx = labelMinPx,
        labelTextPx = labelTextPx,
        // R223b 分批淡入进度（0..1；reveal 完成后走微星合并快路径）
        revealT = ctx.reveal.value,
        // R242 双击运镜锁定星（锁定环渲染）
        lockChatId = ctx.lockChatId.longValue,
        // ---- R233 行星层 + 内容关联弧 ----
        planetSys = ctx.planetSysSnapshot.value,
        arcs = arcs,
        dustChatId = ctx.dustChatId.longValue,
        wellMaxVol = wellMaxVol,
        // ---- R234 尘埃带 + 星座轨迹 ----
        spread = spread,
        spreadCursorMs = spreadCursorMs,
        constellation = scene.constTrail,
        showConstellation = showConstellation,
        // ---- R235 RPG 层：未探索星云 + 飞船航迹 + 虫洞隧道 ----
        // R282：星云走导演器漂移摇曳（受控缓慢形变——量化缓存零逐帧分配）
        nebulae = driftNebulaeR282(nebulae, universeDirector),
        // ---- R373 深空星云复合体（design-nebula P2：双源密度场涌现——R282 同款
        //      亚感知摇曳（场冻结+drift 摇曳），量化缓存保帧路径零分配） ----
        complexes = driftedR373.cpx,
        enrichClouds = driftedR373.clouds,
        ship = ctx.shipEngine,
        shipStyle = shipStyle,
        wormholeP01 = if (ctx.wormhole.active) ctx.wormhole.p01 else -1f,
        // ---- R248 星名点亮：已知集合 + 会话发现动画账 + 帧时钟 ----
        discoveredIds = ctx.knownIds.value ?: emptySet(),
        revealIds = ctx.revealIds.value,
        nowMs = System.currentTimeMillis(),
        // ---- R249 星系物理场：井表+微轨漂移（渲染期直读） ----
        gravity = ctx.physics.value,
        // ---- R302 星辉层（design-rpg-loop：远视=等级函数/罗盘提亮=升级差≤20%/
        //      星域迷雾=已点亮质心+等级雾界——质心逐帧纯算术零分配） ----
        discoverNearStart = TasteStarlight.discoverNearStartOf(ctx.starlightBook.value?.level ?: 1),
        hintBoost = ctx.starlightBook.value?.compassLit == true,
        mistCx = if (mistOn) r302MistBuf[0] else 0f,
        mistCy = if (mistOn) r302MistBuf[1] else 0f,
        mistCz = if (mistOn) r302MistBuf[2] else 0f,
        mistReach = if (mistOn) r302MistBuf[3] else 0f,
        // R314 互链连线开关（进页取值——与 tasteLinkPerturb 同「下次进星图生效」口径）
        linkLines = linkLinesOn.value,
        // ---- R319 宇宙底幕（taste_cmb 一键一层：CMB 纹理+奥伯斯视界+「光在路上」
        //      暗点；种子=genomeFp 同库同纹理；zone=舞台 effect 差集装配直供） ----
        cmbOn = cmbOn.value,
        cmbSeedFp = cmbSeedFp,
        cmbZone = cmbZone,
        // ---- R321 近距关系批（洛希潮汐形变+拉格朗日特洛伊簇的帧输入——场景级
        //      构建一次（buildFlightScene 顶层函数——主方法零接线纪律）） ----
        binaries = scene.binaries,
        // ---- R323 发射星云批（平行表与 nebulae 同序——driftNebulaeR282 摇曳
        //      保序 1:1，星胚偏移相对雾心=随摇曳自动跟随） ----
        emissionFogs = emissionFogsR323,
    )
    }

    // 槽位 Modifier 链（两引擎同一条——手势/尺寸/透明度零改动；R269 tap/导航段在链体内）
    val slotMod = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 0.25f + 0.75f * ctx.reveal.value
            }
            .onSizeChanged { ctx.canvasSize.value = it }
            .pointerInput(stars) {
                // R242 双击天体=锁定+运镜飞向它（井×4.6 停船=到达即行星层
                // 可见+锁定环；τ=0.9s 穿越星域；不开信息卡=纯运镜）；
                // 单击行为原样（选中+爆散+轻推进）。onDoubleTap 注册后单击
                // 判定走系统双击窗（~300ms 延迟）——Android 标准代价。
                detectTapGestures(
                    // R235② 长按行星（tag）→「虫洞跳跃」菜单（同 tag 恒星快线；
                    // 命中走 tapPlanet 同一轨道几何单口——R233 命中优先级的兄弟事件。
                    // 长按空白/长按恒星=无动作（tapStar 档不进虫洞——tag 语义在行星上））
                    onLongPress = { pos ->
                        val planet = tapPlanet(pos).hit
                        if (planet != null) {
                            val (entry, p) = planet
                            ctx.engine.burst(pos.x, pos.y, 8, 220f, p.tag.hashCode())
                            ctx.wormholeFromChat.longValue = entry.chatId
                            ctx.wormholeMenuTag.value = p.tag
                            android.util.Log.i(
                                "TasteMap",
                                "R235 虫洞菜单: 行星 #${p.tag}（宿主频道尾号${(entry.chatId % 10000L + 10000L) % 10000L}）",
                            )
                        } else if (TasteStarlight.isUnlocked(
                                ctx.starlightBook.value?.level ?: 1, "warp_cruise",
                            ) && tapStar(pos) == null
                        ) {
                            // R302 曲速巡航（Lv6——长按**星空白处**开关：启动=持续
                            // 冲刺，停止=任意手势/再长按空白（让位单口同停）；长按
                            // 行星/恒星原样无动作——不误触既有语义）
                            if (ctx.warpCruise.value) {
                                abortGalaxyTourR271(ctx, "曲速巡航手动停止")
                            } else {
                                abortGalaxyTourR271(ctx, "曲速巡航启动")
                                ctx.focusFlight.active = false
                                ctx.wormhole.active = false
                                ctx.warpCruise.value = true
                                ctx.engine.burst(pos.x, pos.y, 12, 300f, 3020)
                                android.util.Log.i("TasteMapX", "R302 曲速巡航: 启动（长按星空白处——Lv6 手势新能力）")
                            }
                        }
                    },
                    onDoubleTap = { pos ->
                        abortGalaxyTourR271(ctx, "双击") // R271：任何手势立即中断让位
                        // R303c P3：双击空白=银河视角仪式运镜（双击天体=飞向它的
                        // 对称语义；R314「回到全景按钮已删」教训——不加按钮复用双击）
                        val s = tapStar(pos) ?: run {
                            startGalaxyViewR303c(ctx)
                            return@detectTapGestures
                        }
                        ctx.wormhole.active = false // R235：任何手势立即接管相机（打断虫洞 transit）
                        val cam = ctx.cam.value
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val focal = TasteSpace.focalOf(size.width.toFloat(), size.height.toFloat()) * ctx.lens.value
                        // R249 v2：爆闪投影走当帧渲染位（点=所见——反馈粒子
                        //      落在所见星位上，不落在烘焙旧位）
                        val hasBp = ctx.physics.value?.posOfChat(s.chatId, ctx.pollPos) == true
                        val bpx = if (hasBp) ctx.pollPos[0] else s.spX
                        val bpy = if (hasBp) ctx.pollPos[1] else s.spY
                        val bpz = if (hasBp) ctx.pollPos[2] else s.spZ
                        if (TasteSpace.project(bpx, bpy, bpz, cam, focal, cx, cy, projPt)) {
                            ctx.engine.burst(projPt[0], projPt[1], 14, 340f, (s.chatId % 100_000L).toInt())
                        }
                        // 运镜目标：星位 + 井×4.6 停船（到达即行星层可见——
                        // 「接近它自然看见旁边的小行星」的字面口径）+ 锁定环
                        // R268b：停船收口政策件；R342 D3：航行档（距离正比时长）
                        val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(s.itemsCount, wellMaxVol))
                        beginStarVoyageR342(ctx, s, stop)
                        ctx.lockChatId.longValue = s.chatId
                        android.util.Log.i(
                            "TasteMap",
                            "R242 双击运镜: 目标尾号${(s.chatId % 10000L + 10000L) % 10000L} " +
                                "停船=${stop.toInt()}（井×4.6 到达即行星层）锁定环已立",
                        )
                    },
                ) { pos ->
                    // R233 命中优先级（小目标优先，大目标兜底）：尘埃 > 行星 > 弧 > 恒星
                    // ——R309c 例外：行星**本体盘内**行星赢（inBody=落点在行星视觉本体
                    // 盘内，明确行星意图→世界卡；贴脸档行星公转扫过尘埃环、盘内恒有
                    // 尘粒抢 14dp 命中=「点行星跳播放页」吞按根因）；盘外照旧尘埃优先
                    //（slop 区让尘=R233 小目标优先语义零回退，环带粒仍可点）。
                    abortGalaxyTourR271(ctx, "点按") // R271：任何手势立即中断让位
                    ctx.wormhole.active = false // R235：任何手势立即接管相机（打断虫洞 transit）
                    // R309 取证件保留：双侧恒求值+最近屏距逐按记账（点按罕发代价可忽略
                    // ——V28 专席据「盘内/尘埃最近/归属」三栏实锤命中归属）。
                    val dustH = if (ctx.dustChatId.longValue != 0L) tapDust(pos) else null
                    val planetH = tapPlanet(pos)
                    // R309c 分发判定（唯一行为变化点）：盘内→尘埃让位；盘外→尘埃优先
                    val dust = if (planetH.inBody) null else dustH?.hit
                    val planet = if (dust == null) planetH.hit else null
                    val arc = if (dust == null && planet == null && arcs.isNotEmpty()) tapArc(pos) else null
                    android.util.Log.i(
                        "TasteMap",
                        "R309 tap诊断: 行星最近=${planetH.nearestPx.let { if (it > 9999f) -1 else it.toInt() }}px" +
                            " 尘埃最近=${dustH?.nearestPx?.let { if (it > 9999f) -1 else it.toInt() } ?: -1}px" +
                            " 盘内=${planetH.inBody}" +
                            " 归属=${if (dust != null) "尘埃" else if (planet != null) "行星" else if (arc != null) "弧" else "恒星/空"}",
                    )
                    if (dust != null) {
                        // 点尘埃→直接播放该条（batch=该 tag 样本条目时间倒序——
                        // 「墙显什么刷什么」口径；宿主走 R222b 无预复活链）
                        val (p, idx) = dust
                        val entry = ctx.planetSysSnapshot.value[ctx.dustChatId.longValue]
                        val item = entry?.items?.getOrNull(idx)
                        if (item != null && entry != null) {
                            ctx.engine.burst(pos.x, pos.y, 10, 260f, (item.messageId % 100_000L).toInt())
                            val batch = p.itemIdx.reversed().mapNotNull { entry.items.getOrNull(it) }
                            android.util.Log.i(
                                "TasteMap",
                                "R233 尘埃命中: 播放该条（批 ${batch.size} 条）",
                            )
                            onPlayDust(item, batch, "星图尘埃 · #${p.tag}")
                        }
                    } else if (planet != null) {
                        // R301：点行星→行星世界卡（星图内半屏 sheet 不退出——世界展开，
                        // 人留在星图里；R269 直进内容流退役为卡内首键「直接开刷」，
                        // 用户终审批准的行为变更；飞进行星门弹层不受影响原样保留）
                        val (entry, p) = planet
                        ctx.engine.burst(pos.x, pos.y, 10, 300f, p.tag.hashCode())
                        android.util.Log.i(
                            "TasteMap",
                            "R301 点行星: tag=#${p.tag}（宿主频道尾号${(entry.chatId % 10000L + 10000L) % 10000L}）→世界卡",
                        )
                        onPlanetTap(entry, p)
                    } else if (arc != null) {
                        // 点弧→运镜飞向对方恒星（双击运镜同款纯运镜；井×4.6 停船）
                        val target = starsById[arc.toChatId]
                        if (target != null) {
                            ctx.engine.burst(pos.x, pos.y, 10, 300f, (arc.toChatId % 100_000L).toInt())
                            android.util.Log.i(
                                "TasteMap",
                                "R233 弧命中: 运镜→尾号${(arc.toChatId % 10000L + 10000L) % 10000L}",
                            )
                            // R268b：停船收口政策件；R342 D3：航行档（距离正比时长）
                            val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(target.itemsCount, wellMaxVol))
                            beginStarVoyageR342(ctx, target, stop)
                            ctx.lockChatId.longValue = target.chatId
                        }
                    } else {
                        val s = tapStar(pos)
                        // R248 未知星（未点亮频道星）：**没有名字没有信息**——
                        // 点=锁定并飞向它（运镜途中井深比过阈=发现时刻，星名
                        // 打字机浮现）；装载完成前的空窗按旧行为（已知处理）
                        val knownNow = ctx.knownIds.value
                        val isUnknownStar = s != null && knownNow != null &&
                            s.chatId !in knownNow &&
                            s.kind != CelestialKind.PULSAR && s.kind != CelestialKind.CLUSTER &&
                            s.kind != CelestialKind.METEOR
                        if (isUnknownStar) {
                            flyToStarR258(ctx, s!!, wellMaxVol)
                            android.util.Log.i(
                                "TasteMapX",
                                "R248 点未知星: 尾号${(s.chatId % 10000L + 10000L) % 10000L} " +
                                    "运镜接近（未知信号——飞近点亮）",
                            )
                        } else {
                            ctx.selectedId.value = s?.chatId
                            if (s != null) {
                                // 点星反馈：粒子爆散+轻推进运镜（τ=0.18s 干脆——R206 原语义）
                                val cam = ctx.cam.value
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val focal = TasteSpace.focalOf(size.width.toFloat(), size.height.toFloat()) * ctx.lens.value
                                // R249 v2：爆闪/停船距离走当帧渲染位（点=所见）
                                val hasTp = ctx.physics.value?.posOfChat(s.chatId, ctx.pollPos) == true
                                val tpx = if (hasTp) ctx.pollPos[0] else s.spX
                                val tpy = if (hasTp) ctx.pollPos[1] else s.spY
                                val tpz = if (hasTp) ctx.pollPos[2] else s.spZ
                                if (TasteSpace.project(tpx, tpy, tpz, cam, focal, cx, cy, projPt)) {
                                    ctx.engine.burst(projPt[0], projPt[1], 14, 340f, (s.chatId % 100_000L).toInt())
                                }
                                // 轻推进：向星前进到剩余 45% 距离处停（注视感，不吞星）
                                // R268b：停船/τ 收口政策件（glanceStop=×0.45 下限 120）
                                val dist = TasteSpace.distTo(cam, tpx, tpy, tpz)
                                ctx.focusFlight.beginWorldFlight()
                                ctx.focusFlight.tx = s.spX
                                ctx.focusFlight.ty = s.spY
                                ctx.focusFlight.tz = s.spZ
                                ctx.focusFlight.stopDist = GestureFeelPolicy.glanceStop(dist)
                                ctx.focusFlight.tau = GestureFeelPolicy.GLANCE_TAU_SEC
                                ctx.focusFlight.targetChatId = s.chatId // R249 v2：运镜目标跟随
                                ctx.fling.yawVel = 0f
                                ctx.fling.pitchVel = 0f
                                ctx.fling.flyVel = 0f
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // R242 手势（判定层 FlightGestureMachine 原样复用，映射层换）：
                //  - 单指拖拽=**视角旋转**（左滑=向左转头/上滑=抬头——街景语义
                //    内容跟手；R241 方向断言并入：星野四向跟手单测锚定）；
                //  - 双指张开=**前进**（沿视线）/捏合=后退（zoomFactor→行程限幅）；
                //  - R231 锚指+动指纵向手势保留映射为前进/后退（第二飞行通道）；
                //  - 双指拧转不进相机（新模型无滚转）——事件照收不映射；
                //  - 松手惯性：角速度+飞行速度双通道（帧循环指数衰减——手感常数
                //    R268b 起全收口 GestureFeelPolicy）。
                // R268b 提案①（用户终审批准）：fling 初速「末事件×60」→**时间窗
                //  测速**（最近 ≤100ms 事件位移和÷各自事件间隔和）——120Hz 屏旧式
                //  低估约一半初速（松手滑行短一截=隐性手感分裂）；三通道各持一窗。
                val slopPx = GestureFeelPolicy.ANCHOR_SLOP_DP.dp.toPx()
                val engagePx = GestureFeelPolicy.ANCHOR_ENGAGE_DP.dp.toPx()
                val degPerPx = GestureFeelPolicy.turnDegPerPx(size.width.toFloat())
                val flyPerPx = GestureFeelPolicy.anchorFlyPerPx(size.height.toFloat())
                val yawVelWin = GestureFeelPolicy.FlingVelocityWindow()
                val pitchVelWin = GestureFeelPolicy.FlingVelocityWindow()
                val flyVelWin = GestureFeelPolicy.FlingVelocityWindow()
                detectFlightGestures(slopPx, engagePx, onGesture = { g ->
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (g.flight) {
                        // 锚指+动指纵向：上推（dy<0）=沿视线前进（R231 手势保留）
                        abortGalaxyTourR271(ctx, "锚指飞行") // R271：任何手势立即中断让位
                        ctx.focusFlight.active = false
                        ctx.lockChatId.longValue = 0L
                        ctx.wormhole.active = false // R235：任何手势立即接管相机
                        ctx.fling.yawVel = 0f
                        ctx.fling.pitchVel = 0f
                        val d = -g.moveDy * flyPerPx // R241 机器语义解耦（diveDy→moveDy）：消费方定符号=上推前进
                        ctx.cam.value = TasteSpace.flyForward(ctx.cam.value, d)
                        flyVelWin.push(nowMs, d)
                        ctx.fling.flyVel = flyVelWin.velocity(nowMs) // 时间窗测速（世界单位/秒）
                        ctx.fling.at = nowMs
                    } else {
                        // 变换：pan=视角旋转（跟手）/zoom=捏合飞行/rotation=吸收不映射
                        abortGalaxyTourR271(ctx, "转头/捏合") // R271：任何手势立即中断让位
                        ctx.focusFlight.active = false
                        ctx.lockChatId.longValue = 0L
                        ctx.wormhole.active = false // R235：任何手势立即接管相机
                        if (g.panX != 0f || g.panY != 0f) {
                            ctx.cam.value = TasteSpace.turnBy(ctx.cam.value, g.panX, g.panY, degPerPx)
                            // 记录松手惯性角速度（度/秒——帧循环在松手 >110ms 后接管衰减）
                            yawVelWin.push(nowMs, g.panX * degPerPx)
                            pitchVelWin.push(nowMs, -g.panY * degPerPx)
                            ctx.fling.yawVel = yawVelWin.velocity(nowMs)
                            ctx.fling.pitchVel = pitchVelWin.velocity(nowMs)
                        }
                        if (g.zoomFactor != 1f) {
                            // R303c P3：乘法缩放（§7.1 速度∝当前高度）——dRef=相机到
                            // 最近星距离（Google Earth 海拔口径；事件级 O(n) 扫 600 星非
                            // 帧级；sp 平衡位=位置真源）。高空每满手势距离×2（L4→L2
                            // 5~6 捏）；近地面回落线性细控（政策件单口）。
                            val camNow = ctx.cam.value
                            var dRef2 = Float.MAX_VALUE
                            for (s in stars) {
                                val dx = s.spX - camNow.px
                                val dy = s.spY - camNow.py
                                val dz = s.spZ - camNow.pz
                                val dd = dx * dx + dy * dy + dz * dz
                                if (dd < dRef2) dRef2 = dd
                            }
                            val dRef = sqrt(dRef2)
                            val step = GestureFeelPolicy.pinchFlyUnitsAlt(g.zoomFactor, dRef)
                            ctx.cam.value = TasteSpace.flyByAlt(ctx.cam.value, g.zoomFactor, dRef)
                            // 惯性测速与直推同一位移源（时间窗内的速度=真实手感）——
                            // 旧口径 pinchFlyUnits(factor) 与实际位移在高空档脱节
                            flyVelWin.push(nowMs, step)
                            ctx.fling.flyVel = flyVelWin.velocity(nowMs)
                        }
                        ctx.fling.at = nowMs
                    }
                    g.probe?.let { android.util.Log.i("TasteMap", it) }
                }, anchorOut = flightAnchor)
            }

    // ---- R277 槽位分流（同一 slotMod 手势链：GL SurfaceView 之上——R345 起 GL 唯一引擎） ----
    if (glActive) {
        // GL 场景层（SurfaceView 打孔——Compose 弹层/信息卡在其上方天然正确）；
        // R345 重试口径：key(nonce) 换代=GL 场景整件重建（新渲染器新 GLSurfaceView=
        // 一次全新 GL 尝试——诊断件「重试」钮驱动的唯一路径）
        androidx.compose.runtime.key(glRetryNonce.intValue) {
            TasteGlScene(
                modifier = slotMod,
                frameProvider = { assembleFlightFrame() },
                onFatal = onGlFatal,
                onHealthyMilestone = onGlHealthy,
                onSessionActive = glSessionActive,
                onRendererReady = { glRendererState.value = it },
            )
        }
        // GL 叠加层（名签+锚点环——混合架构核心红利：文本/手势反馈留 Compose）：
        // 无 pointerInput=命中透明（事件落 GL 槽位——Compose 命中只收 pointerInput 节点）
        Canvas(Modifier.fillMaxSize()) {
            ctx.frameTick.longValue // 帧时钟失效锚（帧循环同拍失效——每拍重绘）
            drawFlightAnchor(flightAnchor, ctx.engine.timeSec)
            drawStarlightBeaconR302(ctx) // R302 指引信标（「最近的下一枚」画在星图上）
            val glr = glRendererState.value ?: return@Canvas
            val cands = glr.labelSnapshot
            if (cands.isNotEmpty()) {
                glLabelCands.clear()
                for (gc in cands) {
                    glLabelCands.add(ShareCard.TasteLabelCand(gc.sx, gc.sy, gc.r, gc.text, gc.alpha))
                }
                drawIntoCanvas { c ->
                    ShareCard.placeTasteLabels(c.nativeCanvas, glLabelCands, glLabelPaint, labelTextPx)
                }
            }
            // 选中星名签（R277 自 Canvas 段迁移的口径——屏位/截断/样式沿用）
            glr.selLabelSnapshot?.let { sl ->
                glSelLabelPaint.textSize = labelTextPx
                glSelLabelPaint.color = 0xE6FFFFFF.toInt()
                val maxW = size.width * 0.42f
                var text = sl.title
                if (glSelLabelPaint.measureText(text) > maxW) {
                    val n = glSelLabelPaint.breakText(text, true, maxW - glSelLabelPaint.measureText("…"), null)
                    text = text.substring(0, n.coerceAtLeast(0)).trimEnd() + "…"
                }
                val tx = minOf(sl.sx + sl.r * 1.6f + 11f, size.width - glSelLabelPaint.measureText(text) - 4f)
                drawIntoCanvas { c ->
                    c.nativeCanvas.drawText(
                        text, tx,
                        sl.sy - (glSelLabelPaint.descent() + glSelLabelPaint.ascent()) / 2f,
                        glSelLabelPaint,
                    )
                }
            }
        }
    } else {
        // R345 诚实降级件（GL 不可用/本会话致命——黑屏+「渲染引擎诊断」行+重试钮；
        // Canvas 老引擎退役后失败不再静默换渲染器——可见可修）
        TasteGlDiagnosticLayer(
            es3Missing = glResolved.engine == GlEnginePolicy.Engine.DIAGNOSTIC,
            reason = glResolved.reason,
            crashCount = glPrefs.glCrashCount,
            onRetry = {
                glDegraded = false
                glRetryNonce.intValue += 1
                android.util.Log.i("TasteMapX", "R345 GL 重试: 重建 GL 场景（第 ${glRetryNonce.intValue} 次）")
            },
        )
    }
}

/**
 * R345 诚实降级件（Canvas 退役后的 GL 失败路径——用户红线「失败可见可修」）：
 * 黑屏底+「渲染引擎诊断」行（原因+崩溃账）+重试钮（重建 GL 场景=新一次尝试；
 * es3 缺失档不显重试——硬件探针未过重试不可能改变结果，诚实不给假希望）。
 * 机读面：R277 ES3 探针/R277 引擎解析/R281 GL 致命/R345 GL 降级日志行同口径在场。
 */
@Composable
private fun TasteGlDiagnosticLayer(
    es3Missing: Boolean,
    reason: String,
    crashCount: Int,
    onRetry: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050708)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "渲染引擎诊断",
                style = MaterialTheme.typography.titleMedium,
                color = TgFeedTokens.TextHigh,
            )
            Text(
                if (es3Missing) {
                    "设备 OpenGL ES 3.0 探针未过——星图渲染引擎无法启动。\n日志检索「R277 ES3 探针」可查探针详情。"
                } else {
                    "OpenGL 渲染引擎本会话异常退出（原因=$reason，崩溃计数=$crashCount）。\n日志检索「R281 GL 致命」可查异常详情。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (!es3Missing && crashCount >= GlEnginePolicy.CRASH_FALLBACK_THRESHOLD) {
                Text(
                    "已连续 $crashCount 次 GL 会话异常——若重试无效请反馈此诊断行",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgFeedTokens.AccentRed,
                )
            }
            if (!es3Missing) {
                FilledTonalButton(onClick = onRetry) {
                    Text("重试 OpenGL 渲染")
                }
            }
        }
    }
}

/**
 * R277 锚点视觉反馈共享件（自 TasteMapCanvasLayer 的 Canvas 内容段提取——语句原样
 * 零行为变化）：GL 槽位态由叠加层 Canvas 调用、Canvas 态由槽位 Canvas 调用——
 * 单一定义（AGENT-DISCIPLINE §一 类问题纪律）。锁定前暗环/锁定后亮环+方向箭头；
 * R268b 量纲全收口 GestureFeelPolicy。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlightAnchor(
    flightAnchor: FlightGestureMachine.AnchorUi,
    timeSec: Float,
) {
    if (!flightAnchor.visible) return
    val ax = flightAnchor.x
    val ay = flightAnchor.y
    val pulse = GestureFeelPolicy.anchorPulse(timeSec)
    if (flightAnchor.engaged) {
        val ring = GestureFeelPolicy.ANCHOR_RING_RADIUS_DP.dp.toPx()
        // 外亮环（accent 脉冲）+ 内芯点
        drawCircle(
            color = TgFeedTokens.AccentRed.copy(alpha = 0.85f * pulse),
            radius = ring,
            center = Offset(ax, ay),
            style = Stroke(width = GestureFeelPolicy.ANCHOR_RING_STROKE_DP.dp.toPx()),
        )
        drawCircle(
            color = TgFeedTokens.AccentRed.copy(alpha = 0.28f * pulse),
            radius = ring * 0.55f,
            center = Offset(ax, ay),
        )
        // 方向箭头（chevron：上=前进飞行 / 下=后退——飞行方向的即时读数）
        val dirUp = flightAnchor.dirUp
        val arm = GestureFeelPolicy.ANCHOR_ARROW_ARM_DP.dp.toPx()
        val gap = GestureFeelPolicy.ANCHOR_ARROW_GAP_DP.dp.toPx()
        val tipY = if (dirUp) ay - ring - gap else ay + ring + gap
        val baseY = tipY + if (dirUp) arm else -arm
        val stroke = GestureFeelPolicy.ANCHOR_RING_STROKE_DP.dp.toPx() // 箭头描边同环族
        val chevColor = TgFeedTokens.AccentRed.copy(alpha = 0.9f * pulse)
        drawLine(chevColor, Offset(ax - arm, baseY), Offset(ax, tipY), stroke)
        drawLine(chevColor, Offset(ax, tipY), Offset(ax + arm, baseY), stroke)
    } else {
        // 候选锚指暗环（双指在场未锁定——「看见你的手势了」）
        drawCircle(
            color = Color.White.copy(alpha = 0.30f * pulse),
            radius = GestureFeelPolicy.ANCHOR_CANDIDATE_RING_RADIUS_DP.dp.toPx(),
            center = Offset(ax, ay),
            style = Stroke(width = GestureFeelPolicy.ANCHOR_CANDIDATE_RING_STROKE_DP.dp.toPx()),
        )
    }
}

/**
 * R328 回放统计四元（genesisStats 派生值——Triple 升四元：已熄计数并入，r325
 * 批D「回放统计行升级」并入本批）：[born]=光标处已诞生频道数/[died]=其中已熄
 * （TasteLifeReplay.diedVisible——lastActivity 无证据恒不计熄）/[bornItems]=
 * 已诞生频道的条数合计/[tagKinds]=**活着**频道的去重 tag 数（星熄则行星隐）。
 */
private data class GenesisLifeStats(val born: Int, val died: Int, val bornItems: Long, val tagKinds: Int)

/**
 * R258 左上统计胶囊 + 图例（主方法迁出——语句原样零行为变化）：天体分档统计 +
 * R248 发现进度胶囊 + R234② 创世纪时间语言统计（genesisStats 派生随迁——单消费位）
 * + 内容类型/六态/特殊天体三行图例。
 */
@Composable
private fun BoxScope.TasteMapStatsLegend(
    ctx: GalaxyLogicCtx,
    mapData: TasteMapData?,
    stars: List<StarNode>,
    linkCount: Int,
    channelStars: List<StarNode>,
    genesisSpan: TasteFlight.GenesisSpan,
) {
    // R234② 创世纪统计→R314 延时摄影统计（时间语言：此刻 N 个频道 · 已熄 M 颗 ·
    // X 条内容 · K 个 tag——光标推进实时重算 O(n)；R328 生灭回放数据面：已熄计数
    // +tag 生死门（星熄则行星隐）走 TasteLifeReplay 单口；K=已装配行星系统
    // （LRU∪预取指纹）中**活着**频道的去重 tag 数（r325 C①-4「tag 行星随恒星
    // 光标生死」）。R236：指纹换 tags 账本口径（topTags 全频道历史——治
    // 「近端样本」挂账；账本空频道回退样本口径，覆盖随存量聚合收敛）
    val genesisStats = if (ctx.genesisOn.value && genesisSpan.valid) {
        remember(ctx.genesis01.value, stars, ctx.tagProfiles.value) {
            var born = 0
            var died = 0
            var bornItems = 0L
            val firstSeenById = HashMap<Long, Long>(stars.size)
            val lastActById = HashMap<Long, Long>(stars.size)
            for (s in stars) {
                firstSeenById[s.chatId] = s.firstSeenMs
                lastActById[s.chatId] = s.lastActivitySec
                if (TasteFlight.bornVisible(s.firstSeenMs, genesisSpan, ctx.genesis01.value)) {
                    born++
                    bornItems += s.itemsCount
                    // R328 已熄计数（生灭回放数据轨）：光标越过 lastActivity+死亡
                    // 延迟=已熄（无证据恒生不冒充死亡——TasteLifeReplay 单口；只数
                    // 已诞生的星：未诞生的星不算「熄」而是「还没凝出」）
                    if (TasteLifeReplay.diedVisible(s.lastActivitySec, genesisSpan, ctx.genesis01.value)) died++
                }
            }
            val tagSet = HashSet<String>(64)
            for ((chatId, fp) in ctx.tagProfiles.value) {
                val fs = firstSeenById[chatId] ?: continue
                // R328 tag 行星生死门：星熄则行星隐（组合门单口 aliveVisible=
                // bornVisible ∧ ¬diedVisible——r325 C①-4 死亡侧补门）
                if (TasteLifeReplay.aliveVisible(fs, lastActById[chatId] ?: 0L, genesisSpan, ctx.genesis01.value)) {
                    tagSet.addAll(fp.keys)
                }
            }
            GenesisLifeStats(born, died, bornItems, tagSet.size)
        }
    } else null
    Column(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 20.dp, top = 8.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.OverlayBgStrong)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // 天体分档统计（纯读数，输出脱敏——只有计数无频道名）
            val special = mapData?.kindCounts.orEmpty()
            val channelCount = stars.size - (special[CelestialKind.PULSAR] ?: 0) -
                (special[CelestialKind.CLUSTER] ?: 0) - (special[CelestialKind.METEOR] ?: 0)
            Text(
                "${stars.size} 个天体 · $channelCount 恒星 · $linkCount 条互链 · 累计观看 ${fmtWatch(mapData?.totalWatchSec ?: 0L)}",
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextMedium,
                maxLines = 1,
            )
        }
        // ---- R248 发现进度胶囊（页头统计：「已探索 N/M 恒星」+进度条——
        //      装载完成才出现；N=点亮数（持久∪已知∪会话），M=在图频道恒星数） ----
        val knownStats = ctx.knownIds.value
        if (knownStats != null) {
            val litCount = channelStars.count { it.chatId in knownStats }
            val total = channelStars.size
            val p01 = if (total > 0) litCount.toFloat() / total else 0f
            Column(
                Modifier
                    .width(196.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBgStrong)
                    .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        tint = TgFeedTokens.StarYellow,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        "已探索 $litCount/$total 颗恒星",
                        style = MaterialTheme.typography.labelMedium,
                        color = TgFeedTokens.TextMedium,
                        maxLines = 1,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(Color(0x14FFFFFF)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(p01.coerceIn(0f, 1f))
                            .background(TgFeedTokens.StarYellow.copy(alpha = 0.85f)),
                    )
                }
            }
        }
        // R234② 创世纪时间语言统计（R314 起=延时摄影光标驱动）：光标推进=星系
        // 逐频道凝聚，这行随光标实时变化
        //（「此刻 N 个频道 · M 条内容 · K 个 tag」；R236 K=已装配频道的 tags 账本
        // 去重口径——诚实标注）
        if (genesisStats != null) {
            val (bornCh, diedCh, bornItems, tagKinds) = genesisStats
            Row(
                Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBgStrong)
                    .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    // R328 生灭回放读法：已熄 M 颗（r325 批D「回放统计行升级」——
                    // 光标扫过=看见整条链的「生灭编年史」读数）
                    "此刻 $bornCh 个频道 · 已熄 $diedCh 颗 · ${fmtItems(bornItems.toInt())} · $tagKinds 个 tag（账本口径）",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("视频" to TYPE_VIDEO, "图文" to TYPE_PHOTO, "音频" to TYPE_AUDIO, "没看过" to TYPE_DIM)
                .forEach { (name, c) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(c)),
                        )
                        Text(name, style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
                    }
                }
        }
        // R223b 六态+特殊天体图例（按本地索引观测——金矿 6h 快照口径，诚实标注）
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                "恒星" to TYPE_VIDEO, "原恒星" to 0xFFA9D4FF.toInt(), "红巨星" to 0xFFE8A06A.toInt(),
                "白矮星" to 0xFFF6FAFF.toInt(), "超新星" to 0xFFFFE9C4.toInt(),
            ).forEach { (name, c) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(c)),
                    )
                    Text(name, style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                "脉冲星·bot" to PULSAR_COLOR, "星团·群组" to CLUSTER_COLOR, "流星·用户" to METEOR_COLOR,
                "断更判定按本地索引观测" to 0,
            ).forEach { (name, c) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (c != 0) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(c)),
                        )
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
                }
            }
        }
    }
}

/** R258 尘埃带状态胶囊（主方法迁出——语句原样零行为变化）：tag+传播统计+关闭；
 *  R236 tags 倒排毫秒级——装载态承接冷缓存首查/异常回退；空态=该标签还没有跨
 *  频道传播记录 */
@Composable
private fun BoxScope.TasteMapSpreadCapsule(
    ctx: GalaxyLogicCtx,
    sTag: String,
    spreadRender: TasteSpreadRender?,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp, start = 20.dp, end = 20.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = TgFeedTokens.AccentRed,
            modifier = Modifier.size(TgFeedTokens.IconSm),
        )
        val sm = ctx.spreadModel.value
        val desc = when {
            ctx.spreadLoading.value -> "尘埃带召回中…（tags 索引检索）"
            spreadRender != null -> "#${spreadRender.tag} · ${spreadRender.channelCount} 频道 · ${spreadRender.recalled} 条" +
                if (spreadRender.sampled) " · 留重${TasteTagSpread.MAX_CHANNELS}" else ""
            sm != null && sm.channels.isNotEmpty() -> "#$sTag · 传播频道都不在星图内"
            else -> "#$sTag · 还没有跨频道传播记录"
        }
        Text(
            desc,
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextMedium,
            maxLines = 1,
        )
        Icon(
            Icons.Outlined.Close,
            contentDescription = "关闭尘埃带",
            tint = TgFeedTokens.TextMedium,
            modifier = Modifier
                .size(TgFeedTokens.IconLg)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(2.dp),
        )
    }
}

/** R314 宇宙延时摄影开态控制条（R356 收纳换代：关态胶囊入口退役→启航菜单
 *  「延时」件承载入口，本件只在模式激活期在场）：播放/暂停 + 光年读数胶囊
 *  （距离=时间：光标回望时长内光传播的距离——「光行 5.9 光年 · 回望 2163 天前」，
 *  看得越远=越古老；core 纯函数 TasteFlight.lightTravelOf/lookbackTextOf）+
 *  倍速档（点按 ×1/×4/×16 循环——模式加速光阴，零新增手势守 R268 归口）+
 *  退出。播到底自停于「现在」；剧场中相机手势原样（可飞近任意星区看演化快进）。 */
@Composable
private fun BoxScope.TasteMapGenesisActiveBar(
    ctx: GalaxyLogicCtx,
    genesisSpan: TasteFlight.GenesisSpan,
) {
    // 光年读数（距离=时间）：有效光标与尘埃带联动同口径（effCursorMs now 扩展），
    // 回望 Δ=now−光标——光标越回拉=望向越深空的古老光芒
    val effMs = TasteTagSpread.effCursorMs(genesisSpan.minMs, ctx.genesis01.value, System.currentTimeMillis())
    val lookbackMs = (System.currentTimeMillis() - effMs).coerceAtLeast(0L)
    val readout = if (ctx.genesis01.value >= 1f) {
        "现在 · 光行 0"
    } else {
        "光行 ${TasteFlight.lightTravelOf(lookbackMs)} · 回望${TasteFlight.lookbackTextOf(lookbackMs)}"
    }
    val speedSteps = TasteFlight.TIMELAPSE_SPEED_STEPS
    val speedIdx = ctx.genesisSpeedIdx.value.coerceIn(0, speedSteps.size - 1)
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .avoidBottomBar() // R226 统一避让钥匙（同上）
            .padding(start = 20.dp, end = 20.dp, bottom = 88.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (ctx.genesisPlaying.value) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (ctx.genesisPlaying.value) "暂停延时摄影" else "播放延时摄影",
            tint = TgFeedTokens.TextMedium,
            modifier = Modifier
                .size(TgFeedTokens.IconLg)
                .clip(CircleShape)
                .clickable {
                    if (!ctx.genesisPlaying.value && ctx.genesis01.value >= 1f) ctx.genesis01.value = 0f // 到底后再播=从头
                    ctx.genesisPlaying.value = !ctx.genesisPlaying.value
                }
                .padding(4.dp),
        )
        // 光年读数胶囊（weight 弹性位——原低端滑杆位）
        Text(
            readout,
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextMedium,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // 倍速档（点按循环 ×1→×4→×16——模式加速光阴；快进时相机手势原样，
        // 飞近星区即「看星区演化快进」）
        Box(
            Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable {
                    ctx.genesisSpeedIdx.value = (speedIdx + 1) % speedSteps.size
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val sv = speedSteps[speedIdx]
            Text(
                "×${sv.toInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = if (speedIdx == 0) TgFeedTokens.TextMedium else TgFeedTokens.AccentRed,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = "退出延时摄影",
            tint = TgFeedTokens.TextMedium,
            modifier = Modifier
                .size(TgFeedTokens.IconLg)
                .clip(CircleShape)
                .clickable {
                    ctx.genesisOn.value = false
                    ctx.genesis01.value = 1f
                    ctx.genesisPlaying.value = false
                }
                .padding(4.dp),
        )
    }
}

/** R258 左下缩放按钮组（主方法迁出——语句原样零行为变化）：+/- 单指档位缩放 */
@Composable
private fun BoxScope.TasteMapZoomButtons(ctx: GalaxyLogicCtx) {
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .avoidBottomBar() // R226 统一避让钥匙（同上）
            .padding(start = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // (无障碍描述, 图标, 缩放档位)：1.5 倍进退（R268b 步进收口政策件）
        val zoomButtons = listOf(
            Triple("放大", Icons.Filled.Add, GestureFeelPolicy.LENS_STEP),
            Triple("缩小", Icons.Filled.Remove, 1f / GestureFeelPolicy.LENS_STEP),
        )
        zoomButtons.forEach { (label, icon, factor) ->
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.OverlayBgStrong)
                    .border(0.5.dp, TgFeedTokens.OverlayHairline, CircleShape)
                    .clickable { zoomByR258(ctx, factor) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = TgFeedTokens.TextMedium,
                    modifier = Modifier.size(TgFeedTokens.IconLg),
                )
            }
        }
    }
}

/** R258 虫洞跳跃菜单（R235② 主方法迁出——语句原样零行为变化）：长按行星弹出
 *（#tag → 共振恒星） */
@Composable
private fun BoxScope.TasteMapWormholeMenu(
    ctx: GalaxyLogicCtx,
    wTag: String,
    starsById: Map<Long, StarNode>,
    wellMaxVol: Int,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .avoidBottomBar()
            .padding(bottom = 150.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.AccentRed.copy(alpha = 0.16f))
                .clickable { startWormholeR258(ctx, wTag, ctx.wormholeFromChat.longValue, starsById, wellMaxVol) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Explore,
                contentDescription = null,
                tint = TgFeedTokens.AccentRed,
                modifier = Modifier.size(TgFeedTokens.IconSm),
            )
            Text(
                "虫洞跳跃 · #$wTag",
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = "关闭虫洞菜单",
            tint = TgFeedTokens.TextMedium,
            modifier = Modifier
                .size(TgFeedTokens.IconLg)
                .clip(CircleShape)
                .clickable { ctx.wormholeMenuTag.value = null }
                .padding(2.dp),
        )
    }
}

/** R258 侧栏「成就」入口（R235① 主方法迁出——语句原样零行为变化）：左缘中点
 *  竖钮=面板开关 */
@Composable
private fun BoxScope.TasteMapAchievementEntry(rankArgb: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .padding(start = 12.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.MilitaryTech,
            contentDescription = "成就",
            tint = Color(rankArgb),
            modifier = Modifier.size(TgFeedTokens.IconLg),
        )
    }
}

/** R258 成就面板（R235① 主方法迁出——语句原样零行为变化）：右侧抽屉=当前等阶+
 *  各勋章进度+下一阶差距。
 *  R302 航海图化（design-rpg-loop §3——面板从「展示柜」变「航海图」）：星辉等级卡
 * （进度环+下一解锁）+探索者编年史组 7 枚（行为叙事型）+「最近的下一枚」指引行
 * （完成路径画在星图上——信标/方向词）+目击图鉴组（Lv4 门）。
 *  R352 反茧房「🕳 探索边界」段（面板尾部——三素材叙事串联零新视觉件）：视界
 * 统计行（[olbers] zone rawDiff——茧房边界数字）+图鉴缺口族（R312 六态册既有+
 * R331 终态奇观册挂账随段收口）+产房指引行（[nebulae]+[mapData] 派生发射星云）；
 * 缺据=行整条不出（[AstroFacts.edgeHorizonLine]/[edgeNurseryLine] 单口裁决）。 */
@Composable
private fun BoxScope.TasteMapAchievementPanel(
    rep: TasteAchievements.AchievementReport?,
    book: TasteStarlight.Book?,
    chronMedals: List<TasteAchievements.Medal>,
    guidance: String?,
    /** R352 起穿透整体 mapData（旧参 kindsSeen=kindCounts 键集改面板内派生，口径零变）：
     * 图鉴缺口行族+探索边界段共用原料（雾区/产房派生走 stars birth 注记） */
    mapData: TasteMapData?,
    /** R352 视界统计原料（R319 奥伯斯带——与画布层底幕同一 zone 单源；null=星表空缺据） */
    olbers: CmbBackdropFx.OlbersZone?,
    /** R352 雾区/产房原料（未探索星云聚类——舞台件 nebulae 单消费位直供） */
    nebulae: List<TasteNebula.NebulaCluster>,
    /** R385 复合体在场报告行（Stage 装配一次 String 直供——摇篮 0/缺据=null
     * 整行不出：wonderCountsLine 行后同族样式，R331 奇观册消费口对齐） */
    nebulaComplexLineR385: String? = null,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(340.dp)
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(0.dp)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(TgFeedTokens.GapLg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            // 头行：等阶徽章色点 + 等阶名 + 关闭
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(TasteAchievements.colorOf(rep?.rank ?: TasteAchievements.Rank.BRONZE))),
                )
                Text(
                    TasteAchievements.labelOf(rep?.rank ?: TasteAchievements.Rank.BRONZE),
                    style = MaterialTheme.typography.titleMedium,
                    color = TgFeedTokens.TextHigh,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭成就面板",
                    tint = TgFeedTokens.TextMedium,
                    modifier = Modifier
                        .size(TgFeedTokens.IconLg)
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(2.dp),
                )
            }
            // 飞船行（「勋章越高飞船越酷」的用户语言）
            Text(
                "飞船 · ${TasteAchievements.shipDescOf(TasteAchievements.shipStyleOf(rep?.rank ?: TasteAchievements.Rank.BRONZE))}",
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextMedium,
            )
            // 下一阶差距（已传奇=成就全达成行）
            Text(
                rep?.nextGapLine ?: "已抵达最高等阶——传奇星海旅人",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            // ---------------- R302 星辉等级卡（探索行为成长轴——与等阶双轨：
            //      等阶=身份/星辉=成长；「无经验条焦虑」=进度环+自然语言） ----------------
            if (book != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "✦ 星辉 Lv${book.level}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFFFE9A8),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            "本级 ${book.progress.into}/${book.progress.need}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextLow,
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(Color(0x14FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(book.progress.progress01)
                                .background(Color(0xFFFFE9A8)),
                        )
                    }
                    Text(
                        book.nextUnlock?.let {
                            "再攒 ${book.progress.need - book.progress.into} 星辉升 Lv${it.level}——解锁「${it.label}」（${it.desc}）"
                        } ?: "八级解锁全通——等级继续涨，星海继续为你展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                    // 已解锁能力行（查表即得——无概率）
                    val unlocked = book.unlocks
                    if (unlocked.isNotEmpty()) {
                        Text(
                            "已解锁：${unlocked.joinToString(" · ") { it.label }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextMedium,
                        )
                    }
                }
            }
            // ---------------- R302 「最近的下一枚」指引行（航海图核心——完成路径
            //      画在星图上：空间目标=信标金环，方向=八方罗盘词） ----------------
            if (guidance != null) {
                Text(
                    "🧭 $guidance",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
            // ---------------- R302 探索者编年史（行为叙事型勋章组 7 枚——只加不改：
            //      不参与等阶晋升；判据=core TasteStarlight.chronicleMedals 纯函数） ----------------
            if (book != null) {
                Text(
                    "探索者编年史",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                    modifier = Modifier.padding(top = TgFeedTokens.GapSm),
                )
                for (medal in chronMedals) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                medal.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = TgFeedTokens.TextMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                if (medal.met) "✓ 达成" else "${medal.current} / ${medal.target} ${medal.unit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (medal.met) TgFeedTokens.AccentRed else TgFeedTokens.TextLow,
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                .background(Color(0x14FFFFFF)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(medal.progress01)
                                    .background(
                                        Color(
                                            if (medal.met) TasteAchievements.colorOf(
                                                TasteAchievements.Rank.LEGEND,
                                            ) else 0xCCFFFFFF.toInt(),
                                        ),
                                    ),
                            )
                        }
                    }
                }
                // ---------------- R302 目击图鉴（Lv4 解锁——宇宙事件收集册） ----------------
                Text(
                    "目击图鉴",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                    modifier = Modifier.padding(top = TgFeedTokens.GapSm),
                )
                if (TasteStarlight.isUnlocked(book.level, "witness_atlas")) {
                    Text(
                        "新星 ×${book.counts.nova} · 超新星 ×${book.counts.supernova} · 彗星 ×${book.counts.comet}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    Text(
                        "目击=事件发火时你在它邻域（井深比<15）；彗星在场即目击。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                    // R312 学习路径钩子：三事件科普注（AstroFacts 单口——目击了
                    // 什么，顺手学到什么）
                    Text(
                        AstroFacts.eventGloss(UniverseDirector.EventKind.NOVA) + "\n" +
                            AstroFacts.eventGloss(UniverseDirector.EventKind.SUPERNOVA) + "\n" +
                            AstroFacts.eventGloss(UniverseDirector.EventKind.COMET),
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                } else {
                    Text(
                        "🔒 Lv4 解锁目击图鉴——宇宙事件收集册",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
            }
            // 五阶勋章进度（分组：白银/黄金/钻石/传奇——组头+组内进度条行）
            // R248：探索里程碑组（点亮口径——不参与等阶晋升，独立进度展示）
            val groups = listOf(
                "白银" to listOf("silver_channels", "silver_watch"),
                "黄金" to listOf("gold_channels", "gold_watch", "gold_favs"),
                "钻石" to listOf("diamond_channels", "diamond_tags", "diamond_shares"),
                "传奇" to listOf("legend_channels", "legend_watch", "legend_kinds"),
                "探索里程碑" to listOf("explore_m10", "explore_m50", "explore_m100", "explore_m300"),
            )
            if (rep != null) {
                for ((tierName, keys) in groups) {
                    Text(
                        "${tierName}勋章",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextMedium,
                        modifier = Modifier.padding(top = TgFeedTokens.GapSm),
                    )
                    for (key in keys) {
                        val medal = rep.medals.firstOrNull { it.key == key } ?: continue
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    medal.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgFeedTokens.TextMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    if (medal.met) "✓ 达成" else "${medal.current} / ${medal.target} ${medal.unit}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (medal.met) TgFeedTokens.AccentRed else TgFeedTokens.TextLow,
                                )
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    .background(Color(0x14FFFFFF)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(medal.progress01)
                                        .background(
                                            Color(
                                                if (medal.met) TasteAchievements.colorOf(
                                                    TasteAchievements.Rank.LEGEND,
                                                ) else 0xCCFFFFFF.toInt(),
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            // ---------------- R352 反茧房「🕳 探索边界」段（三素材叙事串联——零新
            //      视觉件纯文本行族；段序=视界统计→图鉴缺口→金矿摇篮指引，产房行
            //      收尾=破茧 call-to-action） ----------------
            // 视界统计原料（R319 zone rawDiff 全量真值——与信息卡 ⑦ 行/画布层底幕
            // 同一 zone 单拍读数，换图 LaunchedEffect(stars) 重算后此键自动跟新）
            val edge = remember(mapData, nebulae, olbers) { edgeStatsR352(mapData, nebulae, olbers) }
            Text(
                "🕳 探索边界",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextMedium,
                modifier = Modifier.padding(top = TgFeedTokens.GapSm),
            )
            // 行 1 视界统计（缺据/零差集=整行不出——AstroFacts 单口裁决）
            AstroFacts.edgeHorizonLine(edge.beyondN)?.let { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
            // ---------------- R312 学习路径钩子：天体图鉴缺口「去哪找」（AstroFacts
            //      单口——见齐天体形态勋章的科普引导；全见过=整段不出。
            //      R352 起 kindsSeen 改面板内派生（mapData.kindCounts 键集，口径零变）） ----------------
            val kindsSeen = mapData?.kindCounts?.keys.orEmpty()
            val kindHints = AstroFacts.unseenKindHints(kindsSeen)
            if (kindHints.isNotEmpty()) {
                Text(
                    "天体图鉴 · 还没见过（点亮它们顺便解锁传奇）",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                )
                for (hint in kindHints) {
                    Text(
                        "· $hint",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
            }
            // ---------------- R331 终态奇观册（图鉴缺口族第二半——UI 接线挂账随
            //      R352 段收口：r331_notes ①节规格抄行=wonderCountsLine+
            //      unseenWonderHints 两行族；奇观册四席与六态册同类同修零静默） ----------------
            Text(
                "天体图鉴 · 终态奇观（宣判制天体——勋章外奇观）",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextMedium,
            )
            Text(
                AstroFacts.wonderCountsLine(mapData?.kindCounts.orEmpty()),
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextMedium,
            )
            // R385 P4 报告行（wonderCountsLine 行后同族样式——§3.3-4 复合体在场
            // 叙述的动态计数半边；摇篮 0/缺据=null 整行不出=成就非缺口不冒充）
            nebulaComplexLineR385?.let { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
            for (hint in AstroFacts.unseenWonderHints(kindsSeen)) {
                Text(
                    "· $hint",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
            // 产房行（段尾——R323 发射星云=金矿摇篮指引；缺据/零雾=整行不出）
            AstroFacts.edgeNurseryLine(edge.fogN, edge.nurseryN, edge.nurseryChannels, edge.fogChannels)?.let { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
            // 诚实注脚（口径说明——数据全本地；R352 扩一行：探索边界段数据口径）
            Text(
                "勋章进度按本地统计：深索引频道、观看历史、收藏、话题与分享计数（分享=分享面板与海报导出的本地账本）；天体形态按当前星图观测。",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            Text(
                "探索边界按金矿快照（约 6 小时刷新）与当前星图观测——茧房外的光与产房里的新频道，都在等爬虫带回。",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
        }
    }
}

/**
 * R352 探索边界段数据包（[TasteMapAchievementPanel] 私有派生件——组合期一次
 * O(n) 纯读，渲染零成本）：视界差集计数直取 [CmbBackdropFx.OlbersZone].rawDiff
 * （全量真值——非渲染 120 帽截断数）；产房计数走 [mapEmissionFogsR323] 平行表
 * **单口复用零第二判据**（团级门=成员过半金矿候选，logProbe=false 面板静默复用
 * 防重复装配日志）；[fogChannels]/[nurseryChannels]=雾团成员频道合计（nebulae
 * count 诚实账）。缺据全零——行不出的裁决归 [AstroFacts] 两单口。
 */
private class EdgeStatsR352(
    val beyondN: Int,
    val fogN: Int,
    val nurseryN: Int,
    val nurseryChannels: Int,
    val fogChannels: Int,
)

/** R352 探索边界派生（纯函数——面板 remember 体收口顶层，主方法/舞台件零计算体） */
private fun edgeStatsR352(
    mapData: TasteMapData?,
    nebulae: List<TasteNebula.NebulaCluster>,
    olbers: CmbBackdropFx.OlbersZone?,
): EdgeStatsR352 {
    val fogs = if (mapData != null && nebulae.isNotEmpty()) {
        mapEmissionFogsR323(mapData, nebulae, logProbe = false)
    } else {
        emptyList()
    }
    var nurseryN = 0
    var nurseryChannels = 0
    // 对位防御与平行表同款（长度不齐=全员普通雾——绝不对错位冒充发射）
    for (i in fogs.indices) {
        if (fogs[i].emission && i < nebulae.size) {
            nurseryN++
            nurseryChannels += nebulae[i].count
        }
    }
    var fogChannels = 0
    for (n in nebulae) fogChannels += n.count
    return EdgeStatsR352(
        beyondN = olbers?.rawDiff ?: 0,
        fogN = nebulae.size,
        nurseryN = nurseryN,
        nurseryChannels = nurseryChannels,
        fogChannels = fogChannels,
    )
}

/** R258 首开引导浮层（R237e 主方法迁出——语句原样零行为变化）：手势图解 + 双键
 *（带我去最近的恒星=直接入井看行星层；先自己逛逛=关闭） */
@Composable
private fun TasteMapGuideOverlay(onFlyNearest: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x96000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
                .background(TgFeedTokens.OverlayBgStrong)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
                .padding(TgFeedTokens.GapLg),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
            ) {
                Icon(
                    Icons.Outlined.Explore,
                    contentDescription = null,
                    tint = TgFeedTokens.AccentRed,
                    modifier = Modifier.size(TgFeedTokens.IconLg),
                )
                Text(
                    "深空飞行 · 上手三招",
                    style = MaterialTheme.typography.titleMedium,
                    color = TgFeedTokens.TextHigh,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                Text(
                    // R268b 提案②（用户终审批准）：「星野跟手」与转头模型实际相反
                    // （R242 验证原话=星野反向移动）——改「像转头看四周」口径
                    "① 单指拖动 = 环顾四周（像转头看四周）· 双指张开 = 向前飞 · 捏合 = 后退",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                Text(
                    "② 双击恒星 = 锁定并飞向它（飞近会浮现行星=话题，尘埃=内容条目）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                Text(
                    "③ 朝任何方向飞都有星（近大远小）；一指按住+另一指上推也能前进",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onFlyNearest,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                ) {
                    Text(
                        "带我去最近的恒星",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                ) {
                    Text("先自己逛逛", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

// ==================== 装配（IO）：全宇宙（R223b）+ 观看账 + 图谱快照 → 原料星线 ====================

/** R152 深度索引单频道基因聚合（一条 GROUP BY 的行投影）：count=条数（体量）、
 *  video/photo=索引口径两类条数、audio=总数−视频−图文（兜历史脏类型值）、avgDur=平均时长；
 *  firstDateSec=R206 星生序原料：该频道在索引里的最早条目日期（秒，0/NULL=无） */
private class GenomeLite(val count: Int, val video: Int, val photo: Int, val audio: Int, val avgDurSec: Long, val firstDateSec: Long)

/**
 * R223b 深索引 meta 全表频道 id（宇宙集合的底座）：`SELECT chatId FROM meta` 主键
 * 全表扫毫秒级（DeepSearchIndex.indexedChannelIds 同款只读 SQL；其 companion 私有
 * 引不到，OPEN_READONLY 只读纪律与 loadIndexGenomes 同款——绝不建库绝不写库）。
 * 注意口径：深索引覆盖**频道+群组+私聊**（listSearchableChats 全覆盖口径），本方法
 * 只搬 id；类型化（频道=恒星/群组=星团/bot=脉冲星/用户=流星）由装配侧
 * DiscoveryProfile（1h TTL）+ SearchEntity 判定，无类型证据的按频道诚实处理。
 * 库不存在/查询异常返回空集。
 */
// ==================== R253② StarInfoCard（点星信息卡独立组件——God 函数瘦身） ====================

/**
 * R253② 点星信息卡（R248 判词+R250 七件+R251 六卷+R253 交互的完整卡面——自
 * TasteMapScreen 主函数整体迁出）。**迁出动机（类问题）**：主函数直体累积超 JVM
 * 64KB 方法上限（dexdump 实测 eaf7d8f 期 46090 DEX units；Compose 内容 lambda
 * 编译内联进主方法——卡面 900 行+装载链四 effect 全在其中，累积后 Method too
 * large 实锤）。迁出后主方法甩出两万指令档。**零语义改**：状态/effect/卡面
 * 代码逐字搬运（装载链守卫/护栏/竞速防护/换星复位全保留——R248/R250/R251
 * 红线）；仅三处接口化：① selectedId 读写改 [onSelectStar] 回调与 st.chatId
 * （卡开时恒等）；② sessionSubscribed 主函数持有（会话级订阅反馈跨开合不丢）
 * 经 [subscribedOverride]/[onSubscribeSession]；③ 状态生命周期=卡开合（关卡即
 * 弃、重开重置装载——原 effect 换星复位对可见行为等价）。
 */
@Composable
private fun StarInfoCard(
    st: StarNode,
    gateway: TdGateway,
    vm: FeedViewModel,
    mapData: TasteMapData?,
    starsById: Map<Long, StarNode>,
    binaryByChat: Map<Long, BinaryPair>,
    planetSysSnapshot: Map<Long, TastePlanetEntry>,
    discoverFile: File,
    subscribedOverride: Set<Long>,
    /** R319 观测视界半径 R_obs（奥伯斯带 rObs；默认 0=zone 缺席缺据——whyChain
     *  第七问「贴近观测视界」整行不出；>0 时半径比 |sp|/R_obs ≥0.85 才出） */
    cmbHorizonR: Float = 0f,
    /** R352 茧房外差集计数（奥伯斯带 rawDiff 全量真值；默认 -1=zone 缺席缺据——
     *  第七问「茧房外还有 N 个」追加句不出；与成就面板探索边界段同一数字同源） */
    cmbHorizonBeyondN: Int = -1,
    /** R385 复合体在场报告行（Stage 装配一次 String 经参穿透零卡内重算——词条
     *  浏览器浏览 nebula 词条时增段行；null=摇篮 0/缺据整行不出） */
    nebulaComplexLineR385: String? = null,
    onSubscribeSession: (Long) -> Unit,
    onSelectStar: (Long?) -> Unit,
    onFlyStar: (StarNode) -> Unit,
    assemblePlanets: suspend (Long) -> Unit,
    onPlayDust: (item: dev.tgfeed.core.td.VideoItem, batch: List<dev.tgfeed.core.td.VideoItem>, label: String) -> Unit,
    onOpenChannel: (ChannelBrief) -> Unit,
    // R269：卡内 tag 行星云点 tag → 该 tag 全宇宙内容流直刷（原 R248/R250 直跳
    // 时间轴页退役——任务书「不是先跳时间轴页」；时间轴=星图弹层二级入口保留）
    onPlayTagContent: (String) -> Unit,
) {
    val context = LocalContext.current
    // R133 基因行（判词回落层——迁入件）
    var genomeLine by remember { mutableStateOf<String?>(null) }

    // ---- R248 信息卡重设计状态（编年史融入——R246 判词/两卷按需装载） ----
    // 编年史档案摘要（null=未装载/无数据；装载中=chronicleLoading）
    var chronicle by remember { mutableStateOf<ChannelEraPolicy.EraDigest?>(null) }
    var chronicleLoading by remember { mutableStateOf(false) }
    // 档案卷宗展开开关（换星复位）
    var chronicleOpen by remember { mutableStateOf(false) }
    // ---- R251 四卷卷宗状态（③峰作品④里程碑⑤沉寂=loadChronicleVolumes 按星装载；
    //      ⑥互联=装配期 neighborRels 直供零查询）。卷折叠头：①②默认展开（保
    //      R248 行为），③~⑥默认折叠（密度红线：每卷 ≤3 信息组，深看逐卷展开；
    //      remember(selectedId)=换星自动复位） ----
    var chronVolumes by remember { mutableStateOf<ChronicleVolumes?>(null) }
    // R258：vol1Open~vol6Open 随迁 StarCardChronicleVolumes（唯一消费位——
    // StarInfoCard 抽卡后仍 14.6k DEX units/279 寄存器逼近 ART 校验线，同款再拆）
    // ---- R250 信息卡增强状态（动态预览窗/节奏微图/发现日期） ----
    // 代表作预览已装载窗（点=播放；初载 N+N/2，右滑临近尾懒加载下一批——批量
    // =ceil(N/2)；batch 播放批=当前已装载窗）。R408b：窗三态（items/exhausted/
    // loading）收编共享件 PreviewWindowState（行星世界卡同件）
    val previewWin = remember { PreviewWindowState() }
    // 24h 小时直方图（R250 节奏微图原料；null=未装载/无日期证据——微图不出）
    var rhythmHours by remember { mutableStateOf<IntArray?>(null) }
    // 发现日期账（R250：chatId → 发现时刻 ms；0/缺席=存量无日期不冒充）
    var discoveredTs by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    // ---- R250 动态预载口径（用户方案）：可见数 N=卡面内容宽÷缩略步长（屏宽
    //      -2×20dp 面板边距-2×GapLg 内衬——LocalConfiguration 算术，零测量竞速）；
    //      初载=N+ceil(N/2)；懒载批=ceil(N/2)。屏幕变（分屏/旋屏）随 screenWidthDp
    //      重算（R408b 收编共享口 rememberPreviewDims） ----
    val previewDims = rememberPreviewDims()
    val previewN = previewDims.n
    val previewHalf = previewDims.half
    // 预览 LazyRow 滚动态（remember(selectedId)——换星重建=滚动归零）
    val previewListState = remember(st.chatId) { LazyListState() }
    // ---- R253① 24h 热力条点选状态（发布节奏→代表作时段过滤）：null=未选=全量窗；
    //      选中=该本地钟点时段窗（hourWin 与 previewWin 同一套动态装载口径——
    //      换数据源不换行为：初载 N+ceil(N/2)、懒载批 ceil(N/2)、PREVIEW_MAX 护栏） ----
    var rhythmHour by remember { mutableStateOf<Int?>(null) }
    val hourWin = remember { PreviewWindowState() }
    // ---- R248 点星档案装载（信息卡重设计）：R133 基因行（判词回落层）+ 编年史
    //      摘要（ChannelEraPolicy 纯函数——本地索引频道域聚合）+ 代表作预览
    //      样本（行星 LRU 命中零查询，未命中按需 channelItemsPage）。换星重查，
    //      档案展开态复位 ----
    //      R250 四项拓展：①预览改动态装载（初载 N+N/2——右滑懒加载见下个 effect）
    //      ②编年史装载附带 24h 小时直方图（本地时区口径）③发现日期账装载
    //      ④点星即装配行星系统（tag 行星云原料——与飞近装配同一 LRU 同一份数据
    //      零新查询族；与轮询装配并飞时最坏重复装一次（同值覆写，无害）） ----
    LaunchedEffect(st.chatId) {
        genomeLine = null
        chronicle = null
        chronicleLoading = false
        chronicleOpen = false
        previewWin.reset()
        rhythmHours = null
        discoveredTs = emptyMap()
        // R253①：热力条点选/时段窗复位（换星回全量预览——点选态不跨星残留）
        rhythmHour = null
        hourWin.reset()
        val id = st.chatId
        // R250④：点星即装配（LRU 入口幂等——已装配立即返回；选中星钉住不淘汰）
        runCatching { assemblePlanets(id) }
        genomeLine = runCatching { vm.channelGenomeLine(id) }.getOrNull()
        chronicleLoading = true
        val cachedItems = planetSysSnapshot[id]?.items
        val initialCount = previewDims.initialCount
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // R250①：初载窗=N+N/2——LRU 命中直接切片零查询（样本同序：近端
                // messageId 新→旧）；未命中按需 channelItemsPage（短路容错→空窗）
                val previews = if (cachedItems != null) {
                    cachedItems.take(initialCount)
                } else {
                    runCatching { vm.deepIndex.channelItemsPage(id, initialCount, 0) }
                        .getOrDefault(emptyList())
                }
                val (digest, hours) = loadChannelEraDigest(vm, id)
                StarCardLoad(digest, hours, previews, TasteDiscoveryLog.loadDated(discoverFile))
            }.getOrNull()
        }
        chronicle = loaded?.digest
        rhythmHours = loaded?.hourHist
        // 短窗=频道条数不足初载数（右滑也无下一批——诚实封窗）
        previewWin.setInitial(loaded?.previews.orEmpty(), initialCount)
        val previewItems = previewWin.items
        discoveredTs = loaded?.discovered ?: emptyMap()
        chronicleLoading = false
        android.util.Log.i(
            "TasteMap",
            "R250 档案装载: 尾号${(id % 10000L + 10000L) % 10000L} " +
                "预览=${previewItems.size}（N=$previewN 初载=$initialCount${if (cachedItems != null) "·LRU切片" else ""}）" +
                "小时槽=${rhythmHours?.count { it > 0 } ?: 0} " +
                "峰=${ChannelEraPolicy.peakHourLabelOf(rhythmHours ?: IntArray(0)) ?: "-"} " +
                "发现=${if ((discoveredTs[id] ?: 0L) > 0L) "有日期" else "无日期"} " +
                "行星=${planetSysSnapshot[id]?.system?.planets?.size ?: 0}（tag行星云）",
        )
        // R249 科普行探针（验证矩阵④的 kind 对位件；输出脱敏无频道名）
        val st9 = mapData?.stars?.firstOrNull { it.chatId == id }
        if (st9 != null) {
            val meanI = mapData?.meanItems ?: 0f
            val multiple = if (meanI > 0f && st9.itemsCount > 0) st9.itemsCount / meanI else 0f
            android.util.Log.i(
                "TasteMapX",
                "R249 科普: kind=${TasteGalaxyClassifier.labelOf(st9.kind)} 存量=${st9.itemsCount} " +
                    "倍数=${if (multiple > 0f) "%.1f".format(multiple) else "-"} " +
                    "近7天=${st9.recent7}（信息卡将出对应彩蛋行）",
            )
        }
        android.util.Log.i(
            "TasteMap",
            "R248 档案装载: 尾号${(id % 10000L + 10000L) % 10000L} " +
                "判词=${chronicle?.verdict ?: "（薄）"} 预览=${previewItems.size} " +
                "演化卷=${chronicle?.evolution?.size ?: 0}段 节奏=${chronicle?.rhythm?.label ?: "-"}" +
                if (cachedItems != null) "（行星LRU命中）" else "",
        )
    }

    // ---- R251 四卷卷宗装载（③峰作品④里程碑⑤沉寂——digest 在场才取：同空态同
    //      纪律；⑥互联走装配 neighborRels 零查询）。独立 effect 与 R250 卡面装载
    //      并行不互绕；key 含 chronicle=null→digest 到货即触发装载（判读完再挖卷） ----
    LaunchedEffect(st.chatId, chronicle) {
        chronVolumes = null
        val id = st.chatId
        val digest = chronicle ?: return@LaunchedEffect
        // ⑤ 金矿最近活动日（索引滞后兜底：末段未闭合不误判「已沉寂」）
        val lastActSec = mapData?.stars?.firstOrNull { it.chatId == id }?.lastActivitySec ?: 0L
        val volumes = withContext(Dispatchers.IO) {
            runCatching { loadChronicleVolumes(vm, id, lastActSec) }.getOrNull()
        }
        // 换星/清空竞速护栏：装载结果只回写当前选中星（且 digest 未被换星重置）
        if (st.chatId == id && chronicle === digest) {
            chronVolumes = volumes
            android.util.Log.i(
                "TasteMapX",
                "R251 卷装载: 尾号${(id % 10000L + 10000L) % 10000L} " +
                    "峰作品=${volumes?.works?.size ?: 0} 里程碑=${volumes?.milestones?.size ?: 0} " +
                    "沉寂行=${volumes?.dormant?.size ?: 0} 短停更=${volumes?.shortPauses ?: 0} " +
                    "互联=${mapData?.neighborRels?.get(id)?.size ?: 0}",
            )
        }
    }

    // ---- R250 代表作懒加载（用户方案：右滑临近尽头装下一批）：snapshotFlow 观察
    //      LazyRow 滚动位（末位可见 index 变化即重估——滚动驱动正典模式）；触发线
    //      只算**完全可见**格（visibleItemsInfo 含半露格——真机实证 N=12 静止时
    //      last=12（12 全+1 半），用半露格会把触发线前移一格=未滑即触发）；末位
    //      全可见距已装载尾 < N/2（严格）批量=ceil(N/2)；空批∨全重∨护栏封顶=
    //      exhausted；previewLoading 只作 collect 内守卫（**不作 effect key**——
    //      key 含它会在装载置位时自取消重启、真机实证两行判定无批次的死循环）。
    //      channelItemsPage messageId 新→旧有界分页——offset=已装载数（与 LRU
    //      样本同序，两源无缝衔接）。R408b：触发 Effect/批追加（护栏+去重+封窗）
    //      收编共享件 PreviewNearEndEffect/appendBatch（行星世界卡同件，行为零变化） ----
    PreviewNearEndEffect(previewListState, previewHalf, st.chatId, previewWin.exhausted) { lastFull, total ->
        // 判定探针（事件级取证——验证矩阵②：触发时滚动位快照）
        android.util.Log.i(
            "TasteMap",
            "R250 懒载判定: 尾号${(st.chatId % 10000L + 10000L) % 10000L} " +
                "last全可见=$lastFull total=$total exhausted=${previewWin.exhausted}",
        )
        val id = st.chatId
        // ---- R253① 时段窗模式先行（热力条选中=hourWin 懒载分支；未选=全量窗
        //      原逻辑。同一 LazyRow 同一触发线——滚动临近尾时装「当前窗」的下一批；
        //      守卫/批量/护栏/去重与全量窗逐条同口径——同一共享件） ----
        val h = rhythmHour
        if (h != null) {
            val r = hourWin.appendBatch(previewHalf) { offset, limit ->
                vm.deepIndex.channelItemsByHourPage(id, h, localTzOffsetSec(), limit, offset)
            } ?: return@PreviewNearEndEffect
            android.util.Log.i(
                "TasteMap",
                "R253 时段批次: 尾号${(id % 10000L + 10000L) % 10000L} 时=$h offset=${r.offset} " +
                    "到货=${r.fresh} 累计=${hourWin.items.size}（批=${r.batch}" +
                    if (hourWin.exhausted) " 封窗）" else "）",
            )
            return@PreviewNearEndEffect
        }
        val r = previewWin.appendBatch(previewHalf) { offset, limit ->
            vm.deepIndex.channelItemsPage(id, limit, offset)
        } ?: return@PreviewNearEndEffect
        android.util.Log.i(
            "TasteMap",
            "R250 预览批次: 尾号${(id % 10000L + 10000L) % 10000L} offset=${r.offset} " +
                "到货=${r.fresh} 累计=${previewWin.items.size}（N=$previewN 批=${r.batch}" +
                if (previewWin.exhausted) " 封窗）" else "）",
        )
    }

    // ---- R253① 时段窗初载（热力条点选→代表作过滤为该时段发布内容）：选中即取
    //      N+ceil(N/2) 同动态预览初载口径（channelItemsByHourPage——小时谓词与热力条
    //      直方图同式同 tz，**所见即所得**：点 22 时格=取 22:00~23:00 发布条目）；短窗
    //      =exhausted 诚实封窗；换星/换时段重查重置；时段窗与全量窗共用 LazyRow——
    //      模式切换滚动归零。取消点选（null）只清窗不取数——全量窗 previewItems 原样
    //      在场即回全量 ----
    LaunchedEffect(st.chatId, rhythmHour) {
        // 模式/换星切换：预览滚动位归零（两窗同一 LazyRow，换窗从最新格看起）
        runCatching { previewListState.scrollToItem(0) }
        hourWin.reset()
        val id = st.chatId
        val h = rhythmHour ?: return@LaunchedEffect
        val initialCount = previewDims.initialCount
        hourWin.loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { vm.deepIndex.channelItemsByHourPage(id, h, localTzOffsetSec(), initialCount, 0) }
                .getOrDefault(emptyList())
        }
        // 换星/换时段竞速护栏：装载结果只回写当前选中星的当前选中时段
        if (st.chatId == id && rhythmHour == h) {
            hourWin.setInitial(loaded, initialCount)
            android.util.Log.i(
                "TasteMap",
                "R253 时段窗口: 尾号${(id % 10000L + 10000L) % 10000L} 时=$h " +
                    "到货=${loaded.size}（初载=$initialCount${if (hourWin.exhausted) "·封窗" else ""}）",
            )
        }
    }

    val neighbors = remember(st.chatId, mapData, starsById) {
        val arcs = mapData?.neighborArcs ?: return@remember emptyList<Pair<StarNode, Int>>()
        arcs[st.chatId].orEmpty().mapNotNull { a -> starsById[a.chatId]?.let { it to a.weight } }
    }
    val chron = chronicle
    val subscribedNow = st.subscribed || st.chatId in subscribedOverride
    // R253② 滚动位=体块（换星复位 remember(st.chatId)——新星从顶看起）
    val cardScrollState = remember(st.chatId) { ScrollState(0) }
    // R342 D2：模型拖动态旗（卡根手势门防误收卡——3D 模型区拖动中下滑不收卡；
    // remember 态对象跨重组稳定=onDragStateChange 闭包捕获有效）
    val cardModelDragging = remember { mutableStateOf(false) }
    // ---- R253② drag-to-dismiss（往下一滑收起整卡）：R342 D2 起收编共享手势门
    //      [tasteSheetGestureGate]（同 pass 同常数同语义——一处定义两卡同用；
    //      门=命中占用（星图隔离）+Initial 纯观察不消费（零干扰卡内滚动/
    //      LazyRow/按钮/模型）+净下滑≥64dp 纵向主导收卡（起手在顶才计——
    //      中段起手=纯滚动不误收；内容短于视口恒在顶=任意一划即收）+模型
    //      拖动旗防误收；未挂门的卡=非交互区命中透明=手势穿透（R342 根因） ----
    Box(
        Modifier
            .fillMaxWidth()
            .tasteSheetGestureGate(
                key = st.chatId,
                onDismissDown = { onSelectStar(null) },
                canStart = { cardScrollState.value == 0 },
                modelDragging = cardModelDragging,
            ),
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .avoidBottomBar() // R226 统一避让钥匙（同上）
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
            .heightIn(max = 460.dp)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        // ---- R253② 固定头（滚动体滚走也不消失——判词=结论先行北极星
        //      常驻；顶部抓手条=下滑收起可发现性提示） ----
        // R258：整体迁出 StarCardFixedHeader（信息卡二次瘦身）
        StarCardFixedHeader(
            st = st,
            chron = chron,
            chronicleLoading = chronicleLoading,
            genomeLine = genomeLine,
            subscribedNow = subscribedNow,
            onClose = { onSelectStar(null) },
        )
        // ---- R253② 滚动体（基础信息区默认展开+展开档案折叠——heightIn
        //      (max=460) 语义原样：短内容自然高（fill=false 不撑）、
        //      长内容体滚；drag-to-dismiss 的「在顶」判定即体块滚动位） ----
        Box(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(cardScrollState),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
        // 事实行已挪进固定头（R278 项3：R253 设计稿「固定头含事实行」，深滚不丢）
        // ---- R253② 代表作预览（基础信息区首段·动态装载）：可见数 N=
        //      卡面内容宽÷缩略步长，初载 N+N/2，右滑临近尾 <N/2 懒加载
        //      下一批（批量=ceil(N/2)）；点=播放（batch=当前显示窗，
        //      onPlayDust 原链）。R253① 热力条点选=数据源切为该时段窗
        //      （hourItems——发布时间落在该钟点的最新样本；标签行同步
        //      标时段）；无样本不出（诚实空态）；换星/换时段滚动归零 ----
        // R258：整体迁出 StarCardPreviewSection（信息卡二次瘦身）
        StarCardPreviewSection(
            st = st,
            rhythmHour = rhythmHour,
            previewItems = previewWin.items,
            hourItems = hourWin.items,
            hourExhausted = hourWin.exhausted,
            previewListState = previewListState,
            gateway = gateway,
            onPlayDust = onPlayDust,
        )
        // ---- R253① 发布节奏 24h 热力条（可点选）：每格可点（tap x 反解
        //      槽位=整格命中）；点选→代表作过滤为该时段+该格高亮（白描
        //      边+淡蓝底衬）+标签行出「22:00~23:00 · N 条」（N=直方图
        //      口径时段总数——hourFilterLabelOf 单口）；再点同格=取消
        //      恢复全部；0 条格不响应（无数据可过滤，诚实）。峰时金标+
        //      余淡蓝=MonthlyBarsCanvas 同族两色系（R207 语法内）；静态
        //      Canvas 非逐帧（红线三）；无日期证据（全零/未装载）整段不出 ----
        // R258：段体迁出 StarCardRhythmSection（点选置位留卡——rhythmHour 本卡持有）
        val hourHist = rhythmHours
        if (hourHist != null && hourHist.sum() > 0) {
            StarCardRhythmSection(
                hourHist = hourHist,
                rhythmHour = rhythmHour,
                onHourTap = { h ->
                    // 同格再点=取消（恢复全部）；点新格=切时段
                    val newHour = if (rhythmHour == h) null else h
                    rhythmHour = newHour
                    android.util.Log.i(
                        "TasteMap",
                        "R253 时段点选: 时=$h 开=${newHour != null} " +
                            "N=${hourHist[h]} 尾号${(st.chatId % 10000L + 10000L) % 10000L}",
                    )
                },
            )
        }
        // R258：内容趋势/个人足迹/天体档案/科普行/构成条 五段迁出 StarCardVitalsRows
        //（语句原样零行为变化——mateTitle 查询上提为入参纯读）
        // R312：科普段挂 VitalsRows 尾（kindCounts/体量分位直供——分位开卡算一次
        //       O(n) 纯函数，「为什么」链与本星实况的原料）
        val stRank01R312 = remember(st.chatId, mapData) {
            val d = mapData
            if (d == null || st.itemsCount <= 0) {
                0f
            } else {
                TasteGalaxyClassifier.volumeRankOf(
                    st.itemsCount,
                    IntArray(d.stars.size) { idx -> d.stars[idx].itemsCount },
                )
            }
        }
        // ---- R327 循环行原料（星云候选∧近旁终态星空间共在——sp 最近邻一次
        //      查询 O(n) 卡开时算：静态 sp 坐标非渲染热路径；非候选星恒 null
        //      零扫描；remember(chatId, birth)=换星/注记换代重算） ----
        val nearDeathLabelR327 = remember(st.chatId, st.birth) {
            if (st.birth?.stage != TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE) {
                null
            } else {
                nearestDeathSideLabelR325(st, starsById.values)
            }
        }
        // ---- R321 双星段增强行原料（r315-D③/I③/r331 预清单）：同源分 top 门
        //      （warningSharedW 单源——撞车档才出潮汐预警行）+共源 W（特洛伊群行；
        //      无共源=行不出）；缺据 0=整行不出现绝不编造 ----
        val binR321 = binaryByChat[st.chatId]
        val topSharedW = remember(mapData) { mapData?.binaryPairs?.maxOfOrNull { it.sharedW } ?: 0 }
        val rocheSharedW = RocheTidalFx.warningSharedW(binR321?.sharedW ?: 0, topSharedW)
        StarCardVitalsRows(
            st = st,
            chron = chron,
            discoveredMs = discoveredTs[st.chatId] ?: 0L,
            meanItems = mapData?.meanItems ?: 0f,
            bin = binR321,
            mateTitle = binR321?.let { bp -> starsById[if (bp.a == st.chatId) bp.b else bp.a]?.title },
            kindCounts = mapData?.kindCounts.orEmpty(),
            volumeRank01 = stRank01R312,
            // R319 观测视界半径（科普段第七问原料——穿透链 StarInfoCard→此处）
            cmbHorizonR = cmbHorizonR,
            // R352 茧房外计数（穿透链同层：第七问追加句原料——缺据句不追加）
            cmbHorizonBeyondN = cmbHorizonBeyondN,
            // R385 复合体在场报告行（穿透链第三层——词条浏览器 nebula 增段行原料）
            nebulaComplexLineR385 = nebulaComplexLineR385,
            // R327 循环行原料（穿透链第二层：StarInfoCard→VitalsRows→factLine）
            nearDeathLabel = nearDeathLabelR327,
            // R321 双星段两行原料（穿透链同层：潮汐预警/特洛伊群——缺据行不出）
            partnerSharedW = rocheSharedW,
            partnerTrojanW = binR321?.trojanW ?: 0,
        )
        // ---- R250 tag 行星云（迷你轨道图——tag 小行星环绕）：点星即装配
        //      （档案装载 effect 触发 assemblePlanetSystem，选中星 LRU
        //      钉住）；频率高=轨道靠内（本系统内相对归元）；行星位置=
        //      phase01 确定性相位（**静态面板非逐帧**——红线三）；点
        //      行星（≥28dp 热区）→ R269 内容流直刷（该 tag 全宇宙内容，
        //      onPlayTagContent——任务书「不是先跳时间轴页」：原 R222
        //      时间轴直发改挂星图弹层二级入口）；无 tag=整段不出现
        //      （R248 诚实空态同哲学） ----
        // R258：整体迁出 StarCardTagCloud（信息卡二次瘦身）
        // R342 D1：模型区升 3D（世界卡共享件第二消费者——360° 拖动+pill 行+2D 兜底）
        StarCardTagCloud(
            cloudEntry = planetSysSnapshot[st.chatId],
            st = st,
            onPlayTagContent = onPlayTagContent,
            modelDragging = cardModelDragging,
        )
        // 相邻恒星（R248：★频道A（W 条关联）——W=合并边权；点=运镜飞
        // 过去（R237 运镜族）+选中切换到那颗星）
        // R258：段体迁出 StarCardNeighbors（飞行+选链回调留卡——onFlyStar/
        // onSelectStar 接线零改）
        StarCardNeighbors(
            neighbors = neighbors,
            onFly = { nb ->
                onFlyStar(nb)
                onSelectStar(nb.chatId)
            },
        )
        // 按钮行（任务书口径）：[试吃]（主=onOpenChannel 原链）[订阅]
        // （vm.addSubscription 本地订阅写——口内查重；已订阅置灰）。
        // R253②：原第三颗[展开档案]按钮退役——折叠入口移至下方全宽
        // 折叠头（同状态双入口=歧义，单一装订口）
        // R258：整体迁出 StarCardButtons（信息卡二次瘦身）
        StarCardButtons(
            st = st,
            subscribedNow = subscribedNow,
            vm = vm,
            onOpenChannel = onOpenChannel,
            onSubscribeSession = onSubscribeSession,
        )
        // ---- R253② [展开档案 ↓] 折叠头（信息卡+档案融合装订口）：基础
        //      信息区默认全可见，六卷全量收进本折叠——点击展开/收起
        //      （chronicleOpen 既有开关/卷内装载/卷折叠零改；R248 时代
        //      按钮行第三颗「展开档案」退役=同状态双入口歧义）。全宽
        //      中性胶囊（ChronicleVolumeHeader 同形制放大：图标+文案+
        //      本地档案量摘要——薄档案不出摘要） ----
        // R258：整体迁出 StarCardFoldHeader（开关状态留卡——chronicleOpen 本卡持有）
        StarCardFoldHeader(
            chronicleOpen = chronicleOpen,
            starTail = (st.chatId % 10000L + 10000L) % 10000L,
            archiveCount = chron?.totalCount ?: -1,
            onToggle = { chronicleOpen = !chronicleOpen },
        )
        // ---- 展开档案=编年史六卷卷宗（R246 设计融入星图信息卡——R248 一期
        //      两卷 + R251 补全四卷：③巅峰作品④资源里程碑⑤沉寂复活⑥互联
        //      档案；每卷独立折叠头：①②默认展开（保 R248 行为）、③~⑥默认
        //      折叠（密度红线：每卷 ≤3 信息组）——全部同 sheet 内不跳页 ----
        // R258：整体迁出 StarCardChronicleVolumes（vol1~6Open 随迁=唯一消费位；
        // playWork/playMilestone/playReturn 局部函数随迁——语句原样零行为变化）
        StarChronicleDossier(
            st = st,
            chronicleOpen = chronicleOpen,
            chron = chron,
            chronicleLoading = chronicleLoading,
            chronVolumes = chronVolumes,
            neighborRels = mapData?.neighborRels?.get(st.chatId).orEmpty(),
            starsById = starsById,
            gateway = gateway,
            onFlyStar = onFlyStar,
            onSelectStar = onSelectStar,
            onPlayDust = onPlayDust,
        )
        } // R253② 滚动体（基础信息区+折叠档案）Column 结束
        } // R253② 滚动体 Box 结束
    } // R253② 卡 Column 结束
    } // R253② drag-to-dismiss 祖先 Box 结束
}

// ==================== R258 信息卡二次拆分（StarInfoCard 抽卡后仍 14.6k DEX units/
// 279 寄存器逼近 ART 校验线——同款模式再拆；全部语句原样搬移零行为变化） ====================

/** R258 信息卡固定头（R253② 原块迁出）：抓手条 + 头行（天体色点+频道名+星型徽章
 *  +关闭）+ 判词行（结论先行北极星常驻）。
 * R278 项3：事实行（观看/最近/连线/成员/订阅态+互动计数）自滚动体挪入本固定头——
 * R253 设计稿口径「固定头含事实行」（r259 C-⑤ 注记挂账：旧实现在滚动体，深滚滚走） */
@Composable
private fun ColumnScope.StarCardFixedHeader(
    st: StarNode,
    chron: ChannelEraPolicy.EraDigest?,
    chronicleLoading: Boolean,
    genomeLine: String?,
    subscribedNow: Boolean,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.OverlayHairline),
        )
        // 头行：天体色点 + 频道名（大字）+ 关闭（R223b：色点走体量口径颜色）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(st.bodyColor)),
            )
            Text(
                st.title.ifBlank { "未收录频道" },
                style = MaterialTheme.typography.titleMedium,
                color = TgFeedTokens.TextHigh,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // ---- R250 星型徽章（中性胶囊·相邻恒星 pill 同形制）：色点=
            //      体色身份色（R207 身份色豁免）+ labelOf(kind) 文字——
            //      红巨星/白矮星/黑洞…全类型一致出现（类型即语义，
            //      不挑类型不冒充稀有） ----
            Row(
                Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBg)
                    .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(st.bodyColor)),
                )
                Text(
                    TasteGalaxyClassifier.labelOf(st.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Outlined.Close,
                contentDescription = "关闭频道信息",
                tint = TgFeedTokens.TextMedium,
                modifier = Modifier
                    .size(TgFeedTokens.IconLg)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(2.dp),
            )
        }
        // 判词行（编年史北极星：结论先行——「视觉向·日报型·高峰2024上半年」
        // 10 秒读完频道一生；无索引=基因行回落，再无=诚实薄档案态）
        Text(
            when {
                chronicleLoading -> "档案判读中…"
                chron?.verdict != null -> chron.verdict
                genomeLine != null -> genomeLine
                else -> "档案很薄——深度索引还没爬完这个频道"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 事实行（R147 压缩行）：观看/最近/连线/成员/订阅态 + 互动计数
        //（R278 项3：自滚动体原样挪入固定头——深滚不丢，语句零改动）
        Text(
            buildString {
                append(if (st.watchSec > 0) "观看 ${fmtWatch(st.watchSec)}" else "还没看过")
                if (st.lastWatchMs > 0) append(" · ${fmtLastWatch(st.lastWatchMs)}")
                append(" · 连线 ${st.degree} 条")
                if (st.memberCount > 0) append(" · 成员 ${fmtMember(st.memberCount)}")
                append(if (subscribedNow) " · 已订阅" else " · 未订阅")
                if (st.likeCount > 0 || st.favCount > 0) {
                    append(" · 赞 ${st.likeCount} · 藏 ${st.favCount}")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** R258 代表作预览段（R253② 原块迁出）：动态装载 LazyRow（点=播放 onPlayDust 原链）；
 *  R253① 时段窗模式（hourItems）与全量窗同一 LazyRow */
@Composable
private fun StarCardPreviewSection(
    st: StarNode,
    rhythmHour: Int?,
    previewItems: List<VideoItem>,
    hourItems: List<VideoItem>,
    hourExhausted: Boolean,
    previewListState: LazyListState,
    gateway: TdGateway,
    onPlayDust: (item: VideoItem, batch: List<VideoItem>, label: String) -> Unit,
) {
    val displayPreview = if (rhythmHour != null) hourItems else previewItems
    if (rhythmHour != null || previewItems.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                rhythmHour?.let { h ->
                    "代表作预览 · ${ChannelEraPolicy.hourRangeLabelOf(h) ?: "该"} 时段"
                } ?: "代表作预览 · 最新样本",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            if (displayPreview.isEmpty()) {
                // 时段窗初载在途占位（查询失败/短窗=exhausted 诚实收口）
                Text(
                    if (hourExhausted) "该时段暂无索引样本" else "该时段样本装载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            } else {
                // R408b：LazyRow 收编共享件 PreviewThumbRow（行星世界卡同件）
                PreviewThumbRow(
                    items = displayPreview,
                    listState = previewListState,
                    gateway = gateway,
                ) { item ->
                    onPlayDust(
                        item,
                        displayPreview,
                        if (rhythmHour != null) {
                            "时段代表作 · ${st.title.ifBlank { "频道" }}"
                        } else {
                            "频道代表作 · ${st.title.ifBlank { "频道" }}"
                        },
                    )
                }
            }
        }
    }
}

// ---------------- R408b 代表作动态装载共享件（恒星信息卡 R250/R253 原逻辑抽出——
//      恒星信息卡全量窗/时段窗 + 行星世界卡同一套：可见数 N 与半批口径、初载 N+ceil(N/2)、
//      右滑临近尾懒载 ceil(N/2)、PREVIEW_MAX 护栏、key 去重、空批封窗。一处改两卡生效） ----

/** 代表作可见数 N 与半批（懒载批量/触发线）口径；初载=N+half */
internal data class PreviewDims(val n: Int, val half: Int) {
    val initialCount: Int get() = n + half
}

/**
 * 可见数 N 纯函数：卡面内容宽（屏宽-2×20dp 面板边距-2×GapLg 内衬）÷缩略步长，下限 2。
 * 恒星信息卡与行星世界卡同一卡壳几何（同边距同内衬），同一把尺子。
 */
internal fun previewDimsOf(screenWidthDp: Int, gapLgDp: Int): PreviewDims {
    val n = ((screenWidthDp - 40 - 2 * gapLgDp) / PREVIEW_STEP_DP).coerceAtLeast(2)
    return PreviewDims(n, (n + 1) / 2)
}

/** 屏幕变（分屏/旋屏）随 screenWidthDp 重算——LocalConfiguration 算术，零测量竞速 */
@Composable
private fun rememberPreviewDims(): PreviewDims {
    val conf = LocalConfiguration.current
    return remember(conf.screenWidthDp) {
        previewDimsOf(conf.screenWidthDp, TgFeedTokens.GapLg.value.toInt())
    }
}

/** 代表作动态窗状态（一窗一份：已装载窗/到尾/装载中——装载中只作守卫，不作 effect key） */
@androidx.compose.runtime.Stable
private class PreviewWindowState {
    var items by mutableStateOf<List<VideoItem>>(emptyList())
    var exhausted by mutableStateOf(false)
    var loading by mutableStateOf(false)

    fun reset() {
        items = emptyList()
        exhausted = false
        loading = false
    }

    /** 初载落窗：短窗（不足初载数）=诚实封窗（右滑也无下一批） */
    fun setInitial(loaded: List<VideoItem>, initialCount: Int) {
        items = loaded
        exhausted = loaded.size < initialCount
        loading = false
    }
}

/** 懒载批合并结果（纯函数产物） */
internal data class PreviewMerge<T>(val items: List<T>, val freshCount: Int, val exhausted: Boolean)

/**
 * 懒载批合并纯函数：key 去重（装载窗与索引增量竞速的极端位错——同一 key 不二进窗）；
 * 空批∨全重=封窗；累计 ≥ max（护栏）=封窗。
 */
internal fun <T> mergePreviewBatch(
    current: List<T>,
    more: List<T>,
    max: Int,
    keyOf: (T) -> Any,
): PreviewMerge<T> {
    val seen = current.mapTo(HashSet(current.size)) { keyOf(it) }
    val fresh = more.filter { keyOf(it) !in seen }
    if (fresh.isEmpty()) return PreviewMerge(current, 0, true)
    val merged = current + fresh
    return PreviewMerge(merged, fresh.size, merged.size >= max)
}

/** 懒载批次执行回执（调用侧打探针用） */
private data class PreviewAppendOutcome(val offset: Int, val fresh: Int, val batch: Int)

/**
 * 懒载下一批（含护栏+去重+封窗）：守卫 exhausted/loading；offset=已装载数（与初载同序
 * 无缝衔接）；offset 越护栏即封窗不取数（返回 null）；fetch 在 IO 线程、异常=空批=封窗。
 */
private suspend fun PreviewWindowState.appendBatch(
    batch: Int,
    fetch: (offset: Int, limit: Int) -> List<VideoItem>,
): PreviewAppendOutcome? {
    if (exhausted || loading) return null
    val offset = items.size
    if (offset <= 0 || offset >= PREVIEW_MAX) {
        exhausted = true
        return null
    }
    loading = true
    val more = withContext(Dispatchers.IO) {
        runCatching { fetch(offset, batch) }.getOrDefault(emptyList())
    }
    val merged = mergePreviewBatch(items, more, PREVIEW_MAX) { it.key }
    items = merged.items
    if (merged.exhausted) exhausted = true
    loading = false
    return PreviewAppendOutcome(offset, merged.freshCount, batch)
}

/**
 * 临近尾触发 Effect（R250 原形）：snapshotFlow 观察 LazyRow 滚动位；触发线只算**完全
 * 可见**格（半露格会把触发线前移一格=未滑即触发）；末位全可见距已装载尾 < half（严格）
 * 即触发 onNearEnd(lastFull, total)。keys 由调用侧给（换窗/封窗重启）；onNearEnd 走
 * rememberUpdatedState——effect 不因 lambda 重建而重启。
 */
@Composable
private fun PreviewNearEndEffect(
    listState: LazyListState,
    half: Int,
    vararg keys: Any?,
    onNearEnd: suspend (lastFull: Int, total: Int) -> Unit,
) {
    val latest by androidx.compose.runtime.rememberUpdatedState(onNearEnd)
    LaunchedEffect(*keys) {
        snapshotFlow {
            val info = listState.layoutInfo
            val end = info.viewportEndOffset
            val lastFull = info.visibleItemsInfo.lastOrNull { it.offset + it.size <= end }?.index ?: -1
            lastFull >= 0 && info.totalItemsCount > 0 &&
                (info.totalItemsCount - 1 - lastFull) < half
        }.distinctUntilChanged().collect { shouldLoad ->
            if (!shouldLoad) return@collect
            val info2 = listState.layoutInfo
            val lastFull = info2.visibleItemsInfo
                .lastOrNull { it.offset + it.size <= info2.viewportEndOffset }?.index ?: -1
            latest(lastFull, info2.totalItemsCount)
        }
    }
}

/** 代表作 LazyRow 行（3:4 缩略格 PreviewThumbCell；点=onPlay(该条)） */
@Composable
private fun PreviewThumbRow(
    items: List<VideoItem>,
    listState: LazyListState,
    gateway: TdGateway,
    onPlay: (VideoItem) -> Unit,
) {
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items, key = { it.key }) { item ->
            PreviewThumbCell(item = item, gateway = gateway) { onPlay(item) }
        }
    }
}

/** R258 发布节奏 24h 热力条段（R253① 原块迁出；点选置位回调留卡） */
@Composable
private fun StarCardRhythmSection(
    hourHist: IntArray,
    rhythmHour: Int?,
    onHourTap: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "发布节奏 · 24 小时",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            // 点选读数标签优先（取消时回落峰时标签——一格二态不叠出）
            rhythmHour?.let { h ->
                ChannelEraPolicy.hourFilterLabelOf(hourHist, h)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextMedium,
                    )
                }
            } ?: ChannelEraPolicy.peakHourLabelOf(hourHist)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.StarYellow,
                )
            }
        }
        HourHeatCanvas(
            hours = hourHist,
            selectedHour = rhythmHour,
            onHourTap = onHourTap,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0 时", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
            Text("23 时", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
        }
    }
}

/** R258 内容趋势/个人足迹/天体档案/科普行/构成条 五段（原块迁出零变化）：
 *  R250 趋势微图+足迹行 / R223b 天体档案 / R249 科普行 / R147 构成条；
 *  R312：段尾挂 [StarCardScienceSection]（科普段升级——只加不改）；
 *  R327：[nearDeathLabel]=循环行原料（StarInfoCard 层 sp 最近邻终态星产出；
 *  null=无共在=整行不出——factLine birth 参第三穿透层，R319 cmbHorizonR 同模式） */
@Composable
private fun StarCardVitalsRows(
    st: StarNode,
    chron: ChannelEraPolicy.EraDigest?,
    discoveredMs: Long,
    meanItems: Float,
    bin: BinaryPair?,
    mateTitle: String?,
    kindCounts: Map<CelestialKind, Int>,
    volumeRank01: Float,
    /** R319 观测视界半径 R_obs（0=zone 缺席缺据——第七问整行不出；穿透至科普段） */
    cmbHorizonR: Float = 0f,
    /** R352 茧房外差集计数（-1=zone 缺席缺据——第七问追加句不出；穿透至科普段） */
    cmbHorizonBeyondN: Int = -1,
    /** R385 复合体在场报告行（穿透链第三层：StarInfoCard→VitalsRows→
     *  ScienceSection→词条浏览器——null=摇篮 0/缺据增段行不出） */
    nebulaComplexLineR385: String? = null,
    /** R327 循环行原料（近旁终态星天体名——null=无空间共在整行不出） */
    nearDeathLabel: String? = null,
    /** R321 潮汐预警行原料（同源分 top 对的 W——0=缺据/非 top 整行不出） */
    partnerSharedW: Int = 0,
    /** R321 特洛伊群行原料（共源上游 W——0=无共源整行不出） */
    partnerTrojanW: Int = 0,
) {
    // 内容趋势（R250 原样：近 12 月折线——节奏微图同族紧随）
    if (chron != null && chron.monthly.size >= 2) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "内容趋势 · 近 12 月",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            TrendSparkCanvas(chron.monthly)
        }
    }
    // ---- R250 个人足迹行（你与这颗星的关系账）：发现日期（账本带
    //      日期才出——存量点亮=日期未知不冒充）· 看过 N 条（history
    //      .json 条目级去重账 500 条滚动窗）· 收藏 N 条 · 累计时间
    //      （fmtWatch 单一口径）；四段缺席即跳，全缺整行不出
    //      （诚实缺省——R248 同哲学） ----
    val discFmt = remember { SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA) }
    val footParts = buildList {
        if (discoveredMs > 0L) add("你于 ${discFmt.format(Date(discoveredMs))} 发现")
        if (st.watchedCount > 0) add("看过 ${st.watchedCount} 条")
        if (st.favCount > 0) add("收藏 ${st.favCount} 条")
        if (st.watchSec > 0) add("累计 ${fmtWatch(st.watchSec)}")
    }
    if (footParts.isNotEmpty()) {
        Text(
            footParts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // R223b 天体档案段（原样保留：天体类型 + 断更/爆发时长 + 体量 +
    // tier 验货徽章；「按本地索引观测」诚实口径——金矿 6h 快照）
    Text(
        buildCelestialArchiveLine(st, System.currentTimeMillis(), meanItems),
        style = MaterialTheme.typography.labelSmall,
        color = TgFeedTokens.TextLow,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    // ---- R249 天体科普行（任务书③知识彩蛋：天文知识×频道数据融合——
    //      factLine 纯函数单口；黑洞=奥本海默极限倍数/白矮星=停更与
    //      存量/超新星=爆发倍数/双星=伴侣关联；缺据诚实回落；
    //      R317b：hist=宣判注入的直方图特征（黑洞前史行原料——类星体相行走
    //      recent7 直判不需要 hist）；
    //      R327：birth=诞生宣判注入的注记（诞生四行——吸积/诞生史/行星成型/
    //      循环行；nearDeathLabel=循环行空间共在原料） ----
    val nowCardMs = System.currentTimeMillis()
    Text(
        TasteAstroPhysics.factLine(
            kind = st.kind,
            items = st.itemsCount,
            recent7 = st.recent7,
            prev7 = st.prev7,
            lastActivityDays = if (st.lastActivitySec > 0L) {
                (nowCardMs / 1000L - st.lastActivitySec) / 86_400L
            } else {
                -1L
            },
            firstSeenDays = if (st.firstSeenMs > 0L) {
                (nowCardMs - st.firstSeenMs) / 86_400_000L
            } else {
                -1L
            },
            meanItems = meanItems,
            partnerTitle = mateTitle,
            partnerWeight = bin?.weight ?: 0,
            hist = st.hist, // R317b：终态宣判注入（null=未宣判——前史行不出）
            birth = st.birth, // R327：诞生宣判注入（null=未宣判——诞生四行全不出）
            nearDeathLabel = nearDeathLabel, // R327：循环行空间共在（null=整行不出）
            partnerSharedW = partnerSharedW, // R321：潮汐预警行（同源 top——0=行不出）
            partnerTrojanW = partnerTrojanW, // R321：特洛伊群行（共源 W——0=行不出）
        ),
        style = MaterialTheme.typography.labelSmall,
        color = TgFeedTokens.TextMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    // ---- R312 科普段（信息卡科普升级：折叠头「它为什么是{类型}」→ 展开=完整
    //      科普（真天文+本宇宙语义）+本星实况+「为什么」解释链+相关天体推荐；
    //      factLine 紧凑单行原样保留（R249 契约不动），本段=它的展开形态） ----
    StarCardScienceSection(
        st = st,
        meanItems = meanItems,
        kindCounts = kindCounts,
        volumeRank01 = volumeRank01,
        bin = bin,
        mateTitle = mateTitle,
        cmbHorizonR = cmbHorizonR,
        cmbHorizonBeyondN = cmbHorizonBeyondN,
        // R385 复合体在场报告行穿透（词条浏览器增段行原料——零卡内重算）
        nebulaComplexLineR385 = nebulaComplexLineR385,
    )
    // 内容类型构成条（R147 原件原样保留）：三段比例条 + 百分比说明
    val total = st.videoSec + st.photoSec + st.audioSec
    if (total > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill)),
            ) {
                Box(Modifier.weight(max(st.videoSec, 1).toFloat()).background(Color(TYPE_VIDEO)))
                Box(Modifier.weight(max(st.photoSec, 1).toFloat()).background(Color(TYPE_PHOTO)))
                Box(Modifier.weight(max(st.audioSec, 1).toFloat()).background(Color(TYPE_AUDIO)))
            }
            Text(
                "视频 %d%% · 图文 %d%% · 音频 %d%%".format(
                    st.videoSec * 100 / total,
                    st.photoSec * 100 / total,
                    st.audioSec * 100 / total,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
        }
    }
}

// ==================== R312 星象科普体系（信息卡科普段/词条浏览器——新件全顶层，
//  瘦身体纪律；文案全走 AstroFacts 纯件单口，数据驱动零编造） ====================

/**
 * R312 信息卡科普段（折叠——不占默认卡密度）：折叠头「🔭 科普 · 它为什么是{类型}」
 * → 展开=①完整科普（词条 what 真天文 + inUniverse 本宇宙语义）②本星实况
 * （AstroFacts.liveFacts 结构化条目，缺据整条不出）③「为什么」解释链
 * （AstroFacts.whyChain 五~六问：类型/颜色/大小/亮度/位置/伴星——判据全翻译
 * 人话带本星数字）④相关天体推荐（AstroRelatedChips——kind 对位词条带宇宙内
 * 计数，0=「还没见到」诚实标注；点=卡内词条浏览器）。
 */
@Composable
private fun StarCardScienceSection(
    st: StarNode,
    meanItems: Float,
    kindCounts: Map<CelestialKind, Int>,
    volumeRank01: Float,
    bin: BinaryPair?,
    mateTitle: String?,
    /** R319 观测视界半径 R_obs（0=zone 缺席缺据——第七问整行不出） */
    cmbHorizonR: Float = 0f,
    /** R352 茧房外差集计数（-1=zone 缺席缺据——第七问追加句不出） */
    cmbHorizonBeyondN: Int = -1,
    /** R385 复合体在场报告行（Stage 装配一次 String 经参穿透——词条浏览器
     *  nebula 词条增段行；null=摇篮 0/缺据整行不出） */
    nebulaComplexLineR385: String? = null,
) {
    val topic = AstroFacts.topicOfKind(st.kind)
    // 换星复位（remember(st.chatId)——卡面 R253② 同族纪律）
    var open by remember(st.chatId) { mutableStateOf(false) }
    var browseKey by remember(st.chatId) { mutableStateOf<String?>(null) }
    // 本星实况+为什么链原料（开卡算一次——全部纯函数，分位由调用方直供；
    // R319：cmbHorizonR 入键——zone 换图重算后半径比跟新（缺据门恒诚实）；
    // R323：st.drift 入键——宣判换装漂移注记落地后漂移行跟新；
    // R352：cmbHorizonBeyondN 入键——zone 换图后茧房外计数跟新）
    val pack = remember(st.chatId, volumeRank01, meanItems, bin?.weight, mateTitle, cmbHorizonR, cmbHorizonBeyondN, st.drift) {
        val nowMs = System.currentTimeMillis()
        val lastActDays = if (st.lastActivitySec > 0L) {
            (nowMs / 1000L - st.lastActivitySec) / 86_400L
        } else {
            -1L
        }
        val firstDays = if (st.firstSeenMs > 0L) {
            (nowMs - st.firstSeenMs) / 86_400_000L
        } else {
            -1L
        }
        val spectral = TasteAstroPhysics.spectralClassOf(st.recent7, lastActDays)
        // 构成源：观看构成优先，无观看走索引基因构成（starColorBody 同一把尺）
        val fromWatch = st.videoSec + st.photoSec + st.audioSec > 0L
        val cv = if (fromWatch) st.videoSec else st.gVideo.toLong()
        val cp = if (fromWatch) st.photoSec else st.gPhoto.toLong()
        val ca = if (fromWatch) st.audioSec else st.gAudio.toLong()
        val facts = AstroFacts.liveFacts(
            AstroFacts.LiveInput(
                kind = st.kind,
                items = st.itemsCount,
                recent7 = st.recent7,
                prev7 = st.prev7,
                lastActivityDays = lastActDays,
                firstSeenDays = firstDays,
                meanItems = meanItems,
                volumeRank01 = volumeRank01,
                spectral = spectral,
                memberCount = st.memberCount,
                partnerTitle = mateTitle,
                partnerWeight = bin?.weight ?: 0,
            ),
        )
        val chain = AstroFacts.whyChain(
            AstroFacts.WhyInput(
                kind = st.kind,
                items = st.itemsCount,
                recent7 = st.recent7,
                prev7 = st.prev7,
                lastActivityDays = lastActDays,
                firstSeenDays = firstDays,
                volumeRank01 = volumeRank01,
                meanItems = meanItems,
                spectral = spectral,
                compVideo = cv,
                compPhoto = cp,
                compAudio = ca,
                compFromWatch = fromWatch,
                watchDaysAgo = if (st.lastWatchMs > 0L) (nowMs - st.lastWatchMs) / 86_400_000L else -1L,
                watchSec = st.watchSec,
                watchedCount = st.watchedCount,
                degree = st.degree,
                binaryWeight = bin?.weight ?: 0,
                memberCount = st.memberCount,
                // R319 观测视界半径比（|sp|/R_obs；-1=zone 缺席缺据——第七问整行不出）
                horizonEdge01 = if (cmbHorizonR > 0f) {
                    sqrt(st.spX * st.spX + st.spY * st.spY + st.spZ * st.spZ) / cmbHorizonR
                } else {
                    -1f
                },
                // R352 茧房外差集计数（第七问追加句原料——缺据/零句不追加）
                horizonBeyondN = cmbHorizonBeyondN,
                // R323 自行漂移注记（漂移行原料——null=缺据/零漂移/未入池整行不出）
                drift = st.drift,
            ),
        )
        facts to chain
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (open) "🔭 收起科普 · ${topic.title}" else "🔭 科普 · 它为什么是${topic.title}（展开）",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.AccentRed,
            modifier = Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .clickable {
                    open = !open
                    if (!open) browseKey = null
                    android.util.Log.i(
                        "TasteMapX",
                        "R312 科普段: ${if (open) "展开" else "收起"} kind=${topic.title} " +
                            "实况=${pack.first.size}条 为什么=${pack.second.size}问 " +
                            "尾号${(st.chatId % 10000L + 10000L) % 10000L}",
                    )
                }
                .padding(vertical = 2.dp),
        )
        if (open) {
            // ① 完整科普（真天文 + 本宇宙语义映射）
            Text(
                "${topic.emoji} ${topic.what}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextMedium,
            )
            Text(
                "本宇宙：${topic.inUniverse}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            // ② 本星实况（「这颗星对应你库里什么数据」——缺据整条不出）
            if (pack.first.isNotEmpty()) {
                Text(
                    "这颗星：",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                )
                for (f in pack.first) {
                    Text(
                        "· $f",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
            }
            // ③ 「为什么」解释链（一问一答）
            for (item in pack.second) {
                Text(
                    item.question,
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    item.answer,
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
            // ④ 相关天体推荐（点=卡内词条浏览器）
            AstroRelatedChips(
                topicKey = topic.key,
                kindCounts = kindCounts,
                browseKey = browseKey,
                // R385 复合体在场报告行穿透（词条浏览器 nebula 词条增段行原料）
                nebulaComplexLineR385 = nebulaComplexLineR385,
                onBrowse = { k ->
                    browseKey = if (browseKey == k) null else k
                    if (browseKey != null) {
                        android.util.Log.i("TasteMapX", "R312 词条浏览: key=$browseKey（相关天体推荐点选）")
                    }
                },
            )
        }
    }
}

/**
 * R312 相关天体 chips + 卡内词条浏览器（信息卡科普段/世界卡科普行共用——纪律§一
 * 收敛件）：chips=词条.related 序直出（kind 对位词条带宇宙内计数：N>0=「×N」、
 * 0=「·未见」诚实标注；语义天体无计数）；点 chip=展开该词条（what+本宇宙语义+
 * 计数行），再点收起。
 */
@Composable
private fun AstroRelatedChips(
    topicKey: String,
    kindCounts: Map<CelestialKind, Int>,
    browseKey: String?,
    /** R385 复合体在场报告行（Stage 装配一次 String 直供——浏览 nebula 词条时
     *  增段行；null=摇篮 0/缺据整行不出；世界卡调用处缺省不传=该场零行星云
     *  词条浏览的语义下不出，诚实门同一） */
    nebulaComplexLineR385: String? = null,
    onBrowse: (String?) -> Unit,
) {
    val rel = AstroFacts.relatedWithCounts(topicKey, kindCounts)
    if (rel.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "相关天体：",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextMedium,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((rt, count) in rel) {
                Text(
                    buildString {
                        append(rt.emoji).append(' ').append(rt.title)
                        when {
                            count == null -> {}
                            count > 0 -> append(" ×$count")
                            else -> append(" ·未见")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (browseKey == rt.key) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.OverlayBg)
                        .border(
                            0.5.dp,
                            if (browseKey == rt.key) TgFeedTokens.TextMedium else TgFeedTokens.OverlayHairline,
                            RoundedCornerShape(TgFeedTokens.RadiusPill),
                        )
                        .clickable { onBrowse(rt.key) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        // 卡内词条浏览器（选中 chip 的完整科普——不出卡不跳页）
        val bt = browseKey?.let { AstroFacts.topic(it) }
        if (bt != null) {
            Text(
                "${bt.emoji} ${bt.title}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextHigh,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                bt.what,
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextMedium,
            )
            Text(
                "本宇宙：${bt.inUniverse}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            val btKind = bt.kind
            if (btKind != null) {
                Text(
                    "你的宇宙现有 ${kindCounts[btKind] ?: 0} 颗",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
            // R385 P4 增段行（§3.3-4 词条+面板：浏览 nebula 词条时复合体在场
            // 叙述的动态计数半边——与静态 inUniverse 增段同一叙事两形态；
            // null=摇篮 0/缺据整行不出——词条浏览零冒充）
            if (bt.key == "nebula" && nebulaComplexLineR385 != null) {
                Text(
                    "· $nebulaComplexLineR385",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
        }
    }
}

/**
 * R312 世界卡行星科普行（轻折叠——「行星是什么」词条+本行星实况）：行星的真天文
 * （开普勒三定律白话）+本宇宙语义（tag=行星/轨道近=核心/尘埃=内容条目考古）+
 * 本行星实况（条数=全频道历史账本口径，0 条=诚实不出数字行）+相关天体 chips
 * （AstroRelatedChips 共用件——小行星/恒星/双星）。
 */
@Composable
private fun PlanetScienceRowR312(
    count: Int,
    kindCounts: Map<CelestialKind, Int>,
) {
    val topic = AstroFacts.topic("planet") ?: return
    var open by remember { mutableStateOf(false) }
    var browseKey by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (open) "🔭 收起行星科普" else "🔭 行星是什么（展开科普）",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.AccentRed,
            modifier = Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .clickable {
                    open = !open
                    if (!open) browseKey = null
                    android.util.Log.i("TasteMapX", "R312 世界卡科普: ${if (open) "展开" else "收起"} 行星词条")
                }
                .padding(vertical = 2.dp),
        )
        if (open) {
            Text(
                "${topic.emoji} ${topic.what}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextMedium,
            )
            Text(
                "本宇宙：${topic.inUniverse}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            // 本行星实况（条数缺据=不出数字行；轨道语义=静态科普口径）
            if (count > 0) {
                Text(
                    "这颗行星：该频道 $count 条（全频道历史账本口径）",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
            Text(
                "· 轨道越近=这个 tag 越核心（半径 ∝ 计数对数反比）\n· 公转=真开普勒：内圈快外圈慢、近日点快远日点慢\n· 贴脸看=尘埃环：内容条目按发布时间布相，一眼读出爆发还是存货",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            AstroRelatedChips(
                topicKey = topic.key,
                kindCounts = kindCounts,
                browseKey = browseKey,
                onBrowse = { k -> browseKey = if (browseKey == k) null else k },
            )
        }
    }
}

/** R258 tag 行星云段（R250 原块迁出）；
 *  R269：点行星→内容流直刷（onPlayTagContent——原 onOpenHashtagTimeline 时间轴
 *  直发退役，时间轴改挂星图弹层二级入口）；装配中占位行原样。
 *  R342 D1：**模型区升 3D**（用户拍板「所有天体卡片的模型区支持 360° 无死角
 *  拖动观察」——世界卡 StarSystemMiniModel 共享件的第二个消费者）：3D 星系
 *  模型（拖动=OrbitCam 360° 旋转+惯性；点行星=pill 同链）+下方 tag pill 行
 *  （3D 模型无逐行星名签——信息卡=本星 tag 全景，名字可读可点是云的语义
 *  本体）；ES3 不可用/GL 致命→TagOrbitCloud 2D 兜底原样（R310 三闸哲学：
 *  撤 3D 留 2D 诚实降级）。[modelDragging]=卡根手势门防误收旗（模型拖动中
 *  下滑不收卡——卡内协调，见 [tasteSheetGestureGate]）。 */
@Composable
private fun StarCardTagCloud(
    cloudEntry: TastePlanetEntry?,
    st: StarNode,
    onPlayTagContent: (String) -> Unit,
    modelDragging: MutableState<Boolean>,
) {
    if (cloudEntry != null && cloudEntry.system.planets.isNotEmpty()) {
        val planets = cloudEntry.system.planets.take(TAG_CLOUD_MAX_PLANETS)
        val tagClick: (String) -> Unit = { tag ->
            android.util.Log.i(
                "TasteMap",
                "R269 行星云点行星: 尾号${(st.chatId % 10000L + 10000L) % 10000L} " +
                    "tag长=${tag.length}→内容流直刷（onPlayTagContent）",
            )
            onPlayTagContent(tag)
        }
        // ---- R342 3D 模型区（世界卡共享件第二消费者；ES3 探针进程级缓存闸） ----
        val appCtx = LocalContext.current
        var miniGlOk by remember(st.chatId) {
            mutableStateOf(
                probeEs3(appCtx).also { ok ->
                    android.util.Log.i(
                        "TasteMap",
                        "R342 信息卡模型: ${if (ok) "3D 星系装配 行星=${planets.size}（GL——360° 拖动）" else "降级=2D 轨道云（ES3 缺席诚实档）"}",
                    )
                },
            )
        }
        if (miniGlOk) {
            Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                StarSystemMiniModel(
                    chatId = st.chatId,
                    starColorArgb = st.bodyColor,
                    planets = planets,
                    selectedTag = null, // 信息卡全景档：零高亮（选中是世界卡单 tag 语义）
                    onPlanetTap = tagClick, // 同 pill 链（点=开世界卡——R301 同行星语义同新家）
                    onFatal = {
                        miniGlOk = false
                        android.util.Log.i("TasteMap", "R342 信息卡模型: 降级=2D 轨道云（GL 致命诚实档）")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(miniModelHeightDp(LocalConfiguration.current.screenHeightDp)),
                    onDragStateChange = { modelDragging.value = it }, // R342 门防误收旗
                )
                // tag pill 行（模型无逐行星名签——保「一眼读出这颗星有哪些 tag」+可点；
                // 样式=TagOrbitCloud pill 同款（世界卡同 tag 恒星 pill 行同语言）
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    planets.forEach { p ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                .background(TgFeedTokens.OverlayBg)
                                .border(
                                    0.5.dp, TgFeedTokens.OverlayHairline,
                                    RoundedCornerShape(TgFeedTokens.RadiusPill),
                                )
                                .clickable { tagClick(p.tag) }
                                .heightIn(min = TAG_CLOUD_MIN_TOUCH_DP.dp)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Box(
                                Modifier
                                    .size((3f + 6f * p.sizeR01.coerceIn(0f, 1f)).dp)
                                    .clip(CircleShape)
                                    .background(Color(st.bodyColor)),
                            )
                            Text(
                                "#${p.tag}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TgFeedTokens.TextMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 88.dp),
                            )
                        }
                    }
                }
            }
        } else {
            // 2D 兜底（ES3 false/GL 致命——R250 原件原样=降级位职责）
            TagOrbitCloud(
                planets = planets,
                starColorArgb = st.bodyColor,
                onTagClick = tagClick,
            )
        }
    } else if (cloudEntry == null) {
        // 装配中占位行（点星触发的装配在途——毫秒级；异常也终将落
        // 空系统=诚实空态，不悬空占位）
        Text(
            "tag 行星云装配中…",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
        )
    }
}

/** R258 相邻恒星段（R248 原块迁出零变化）：★频道A（W 条关联）pill 横滚；
 *  点=[onFly]（运镜飞过去+选中切换——调用侧接线） */
@Composable
private fun StarCardNeighbors(
    neighbors: List<Pair<StarNode, Int>>,
    onFly: (StarNode) -> Unit,
) {
    if (neighbors.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "相邻恒星 ${neighbors.size}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                neighbors.take(5).forEach { (nb, w) ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(TgFeedTokens.OverlayBg)
                            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .clickable {
                                android.util.Log.i(
                                    "TasteMap",
                                    "R248 相邻恒星飞行: 尾号${(nb.chatId % 10000L + 10000L) % 10000L}（$w 条关联）",
                                )
                                onFly(nb)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(nb.colorArgb)),
                        )
                        Text(
                            "${nb.title.ifBlank { "未收录频道" }}（$w 条关联）",
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextMedium,
                            maxLines = 1,
                        )
                    }
                }
                if (neighbors.size > 5) {
                    Text(
                        "+${neighbors.size - 5}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
            }
        }
    }
}

/** R258 按钮行（原块迁出零变化）：[试吃]（主=onOpenChannel 原链）[订阅]
 *（vm.addSubscription 本地订阅写——口内查重；已订阅置灰）[入单]（R387 星图腿：
 * 把这台排进播放队列=「看到→存队列→按序播」的星图入口；幂等分流 Toast） */
@Composable
private fun StarCardButtons(
    st: StarNode,
    subscribedNow: Boolean,
    vm: FeedViewModel,
    onOpenChannel: (ChannelBrief) -> Unit,
    onSubscribeSession: (Long) -> Unit,
) {
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = {
                onOpenChannel(
                    ChannelBrief(st.chatId, st.title.ifBlank { "频道" }, null),
                )
            },
            modifier = Modifier
                .weight(1.4f)
                .height(38.dp),
            shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
        ) {
            Text(
                if (st.subscribed) "去频道刷刷看" else "试吃",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        TextButton(
            onClick = {
                if (!subscribedNow) {
                    vm.addSubscription(
                        ChannelBrief(st.chatId, st.title.ifBlank { "频道" }, null),
                    )
                    onSubscribeSession(st.chatId)
                    Toast.makeText(
                        context,
                        "已订阅（本地关注）——feed 供给将纳入该频道",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            enabled = !subscribedNow,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
        ) {
            Text(
                if (subscribedNow) "已订阅" else "订阅",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        // R387 入单（星图腿）：点星试吃面板里「把这台排进节目单」——存进播放队列
        // 按序播（r387_notes §3.2）；同 chatId 幂等（已在单=Toast 分流不重复入）。
        // 主 composable 零触碰（本函数独立件=预算闸 TasteMapMethodBudgetTest 只数
        // 主函数体行数，笔前对账：主函数行数零增）
        TextButton(
            onClick = {
                val added = vm.enqueueChannelToPlayQueue(st.chatId, st.title.ifBlank { "频道" })
                Toast.makeText(
                    context,
                    if (added) "已排进播放队列" else "已在播放队列里",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
        ) {
            Text(
                "入单",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** R258 [展开档案 ↓] 折叠头（R253② 原块迁出零变化）：全宽中性胶囊（图标+文案+
 *  本地档案量摘要——薄档案 archiveCount<0 不出摘要）；探针+翻转在点击链原序 */
@Composable
private fun StarCardFoldHeader(
    chronicleOpen: Boolean,
    starTail: Long,
    archiveCount: Int,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBg)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .clickable {
                android.util.Log.i(
                    "TasteMap",
                    "R253 档案折叠: 开=${!chronicleOpen} 尾号$starTail",
                )
                onToggle()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (chronicleOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (chronicleOpen) "收起档案" else "展开档案",
            tint = TgFeedTokens.TextMedium,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (chronicleOpen) "收起档案" else "展开档案 · 编年史六卷",
            style = MaterialTheme.typography.labelLarge,
            color = TgFeedTokens.TextMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (archiveCount >= 0) {
            Text(
                "本地档案 ${fmtItems(archiveCount)}",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
            )
        }
    }
}

/** R258 编年史六卷卷宗（R248 一期两卷 + R251 补全四卷——原 AnimatedVisibility
 *  块整体迁出零变化；vol1~6Open 随迁=唯一消费位，remember(st.chatId) 换星复位
 *  语义原样；playWork/playMilestone/playReturn 局部函数随迁）
 *  R381 卷宗卡层次升级：卷头卡化+chevron（LP6/LP9）+卷身档案纸面（LP7）+
 *  ②律动卷连续光流（LP8）+④里程碑小脊柱——呈现段升级，数据口 ChannelEraPolicy
 *  /ChronicleVolumes 零改。 */
@Composable
private fun StarCardChronicleVolumes(
    st: StarNode,
    chronicleOpen: Boolean,
    chron: ChannelEraPolicy.EraDigest?,
    chronicleLoading: Boolean,
    chronVolumes: ChronicleVolumes?,
    mapData: TasteMapData?,
    starsById: Map<Long, StarNode>,
    gateway: TdGateway,
    onFlyStar: (StarNode) -> Unit,
    onSelectStar: (Long?) -> Unit,
    onPlayDust: (item: VideoItem, batch: List<VideoItem>, label: String) -> Unit,
) {
    var vol1Open by remember(st.chatId) { mutableStateOf(true) }
    var vol2Open by remember(st.chatId) { mutableStateOf(true) }
    var vol3Open by remember(st.chatId) { mutableStateOf(false) }
    var vol4Open by remember(st.chatId) { mutableStateOf(false) }
    var vol5Open by remember(st.chatId) { mutableStateOf(false) }
    var vol6Open by remember(st.chatId) { mutableStateOf(false) }
    AnimatedVisibility(visible = chronicleOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
            // 探针口径（输出脱敏——星一律尾号代称）
            val starTail = (st.chatId % 10000L + 10000L) % 10000L
            // ⑥互联原料（图谱边证据独立于深索引——薄档案分支也出卷；
            // 无互联证据=整卷隐藏，R246 数据薄不出卷口径）
            val relsNow = mapData?.neighborRels?.get(st.chatId).orEmpty().filter { it.total > 0 }
            if (chronicleLoading) {
                Text("档案判读中…", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
            } else if (chron == null) {
                // 未索引频道诚实态（R246 降级矩阵——不出伪结论）
                Text(
                    "档案很薄——深度索引还没爬完这个频道。订阅后爬虫会逐步建档，判词与两卷会随后出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
                // ⑥ 互联档案卷（薄档案下唯一不依赖深索引的卷）
                ChronicleInterlinkVolume(
                    rels = relsNow,
                    starsById = starsById,
                    open = vol6Open,
                    onToggle = {
                        android.util.Log.i(
                            "TasteMapX",
                            "R251 卷展开: 卷=⑥互联档案 开=${!vol6Open} 星=尾号$starTail",
                        )
                        vol6Open = !vol6Open
                    },
                    onFly = { nb ->
                        android.util.Log.i(
                            "TasteMapX",
                            "R251 互联飞行: 尾号${(nb.chatId % 10000L + 10000L) % 10000L}（星=尾号$starTail）",
                        )
                        onFlyStar(nb)
                        onSelectStar(nb.chatId)
                    },
                )
            } else {
                // ① 内容演化卷（R248 时代行/转型句原样——包进 R251 卷头折叠）
                if (chron.evolution.isNotEmpty()) {
                    ChronicleVolumeHeader(
                        "① 内容演化",
                        chron.transitions.lastOrNull() ?: "${chron.evolution.size} 段",
                        vol1Open,
                    ) {
                        android.util.Log.i("TasteMapX", "R251 卷展开: 卷=①内容演化 开=${!vol1Open} 星=尾号$starTail")
                        vol1Open = !vol1Open
                    }
                    VolumeFold(vol1Open) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chron.evolution.forEach { seg -> EraSegRow(seg) }
                        chron.transitions.forEach { t ->
                            Text(
                                "▲ $t",
                                style = MaterialTheme.typography.labelSmall,
                                color = TgFeedTokens.TextLow,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    }
                }
                // ② 更新律动卷（判型徽章+近 24 个月柱条原样；惯例/峰段/趋势
                //      行上移卷头摘要——折叠态也可扫读）
                if (chron.monthly.isNotEmpty() || chron.rhythm != ChannelEraPolicy.Rhythm.THIN) {
                    ChronicleVolumeHeader(
                        "② 更新律动",
                        listOfNotNull(
                            chron.weekdayLabel,
                            chron.peakLabel?.let { "高峰在$it" },
                            chron.trendLabel,
                        ).joinToString(" · ").ifBlank { null },
                        vol2Open,
                    ) {
                        android.util.Log.i("TasteMapX", "R251 卷展开: 卷=②更新律动 开=${!vol2Open} 星=尾号$starTail")
                        vol2Open = !vol2Open
                    }
                    VolumeFold(vol2Open) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    .background(TgFeedTokens.TgBlue.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    chron.rhythm.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgFeedTokens.TgBlue,
                                )
                            }
                        }
                        if (chron.monthly.isNotEmpty()) {
                            // R381：离散月柱 → 连续光流（与频道编年史时间脊柱光流带同语言，LP8）
                            ChronicleRhythmFlowCanvas(chron.monthly)
                        }
                    }
                    }
                }
                // ---- R251 ③④⑤ 卷交互（点=播放——onPlayDust 同链 batch=本卷集合；
                //      探针 TasteMapX 事件级——交互存在性断言实机取证） ----
                val volumes = chronVolumes
                val playWorks = volumes?.works?.mapNotNull { it.item }.orEmpty()
                val playMs = volumes?.milestones?.mapNotNull { it.item }.orEmpty()
                val playReturns = volumes?.dormant?.mapNotNull { it.returnItem }.orEmpty()
                fun playWork(row: PeakWorkRow) {
                    val item = row.item ?: return
                    android.util.Log.i(
                        "TasteMapX",
                        "R251 峰作品播放: 证据=${row.work.headline} 星=尾号$starTail",
                    )
                    onPlayDust(item, playWorks, "镇馆峰作 · ${st.title.ifBlank { "频道" }}")
                }
                fun playMilestone(row: MilestoneRow) {
                    val item = row.item ?: return
                    android.util.Log.i(
                        "TasteMapX",
                        "R251 里程碑播放: 标签=${row.ms.label} 星=尾号$starTail",
                    )
                    onPlayDust(item, playMs, "频道里程碑 · ${st.title.ifBlank { "频道" }}")
                }
                fun playReturn(row: DormantRow) {
                    val item = row.returnItem ?: return
                    android.util.Log.i(
                        "TasteMapX",
                        "R251 回归播放: 沉寂${row.h.days}天 星=尾号$starTail",
                    )
                    onPlayDust(item, playReturns, "沉寂与回归 · ${st.title.ifBlank { "频道" }}")
                }
                // ③ 巅峰作品卷（本地代理证据三件：峰日合集/剧集链/时长离群
                // ——W6 forwardCount 入库前不冒充传播数据，依据行写明证据）
                if (volumes != null && volumes.works.isNotEmpty()) {
                    ChronicleVolumeHeader(
                        "③ 巅峰作品",
                        "${volumes.works.size} 件本地证据",
                        vol3Open,
                    ) {
                        android.util.Log.i("TasteMapX", "R251 卷展开: 卷=③巅峰作品 开=${!vol3Open} 星=尾号$starTail")
                        vol3Open = !vol3Open
                    }
                    VolumeFold(vol3Open) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            volumes.works.forEach { row ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (row.item != null) {
                                                Modifier
                                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                                    .clickable { playWork(row) }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    row.item?.let { item ->
                                        PreviewThumbCell(item = item, gateway = gateway) { playWork(row) }
                                    }
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            "${row.work.era} · ${row.work.headline}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TgFeedTokens.TextMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            row.work.reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TgFeedTokens.TextLow,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // ④ 资源里程碑卷（创刊/第 N 条/系列开篇——点行=播放对应条目）
                if (volumes != null && volumes.milestones.isNotEmpty()) {
                    ChronicleVolumeHeader(
                        "④ 资源里程碑",
                        "创刊至今 ${fmtItems(volumes.totalItems)}",
                        vol4Open,
                    ) {
                        android.util.Log.i("TasteMapX", "R251 卷展开: 卷=④资源里程碑 开=${!vol4Open} 星=尾号$starTail")
                        vol4Open = !vol4Open
                    }
                    VolumeFold(vol4Open) {
                        // R381 ④ 里程碑小脊柱：左缘 1dp 发丝竖线贯穿行节点小圆点串成
                        // 迷你时间轴（呼应频道主页时间脊柱——B 案「大事记挂轴」的卷内缩影）
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawLine(
                                        TgFeedTokens.TextLow,
                                        Offset(ChronicleMiniSpineX.toPx(), 0f),
                                        Offset(ChronicleMiniSpineX.toPx(), size.height),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            volumes.milestones.forEach { row ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (row.item != null) {
                                                Modifier
                                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                                    .clickable { playMilestone(row) }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(horizontal = 4.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 行节点（串在小脊柱上：创刊=StarYellow 金标，余=TgBlue）
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (row.ms.label == "创刊") TgFeedTokens.StarYellow else TgFeedTokens.TgBlue,
                                            ),
                                    )
                                    Text(
                                        ChannelEraPolicy.dateLabelOf(row.ms.dateSec).ifBlank { "——" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TgFeedTokens.TextLow,
                                        modifier = Modifier.width(66.dp),
                                        maxLines = 1,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            row.ms.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TgFeedTokens.TextMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                        row.ms.sub?.let { sub ->
                                            Text(
                                                sub,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TgFeedTokens.TextLow,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // ⑤ 沉寂与复活卷（gap>7 天=断更标记；≥30 天独立行+7~30 天
                // 归并计数；点行=播放回归日首条；未归段 WarningRed 语义族）
                if (volumes != null && (volumes.dormant.isNotEmpty() || volumes.shortPauses > 0)) {
                    ChronicleVolumeHeader(
                        "⑤ 沉寂与复活",
                        volumes.hiatusSummary ?: "无断更记录",
                        vol5Open,
                    ) {
                        android.util.Log.i("TasteMapX", "R251 卷展开: 卷=⑤沉寂与复活 开=${!vol5Open} 星=尾号$starTail")
                        vol5Open = !vol5Open
                    }
                    VolumeFold(vol5Open) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            volumes.dormant.forEach { row ->
                                Text(
                                    ChannelEraPolicy.hiatusLineOf(row.h),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.h.returned) TgFeedTokens.TextMedium else TgFeedTokens.WarningRed,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (row.returnItem != null) {
                                                Modifier
                                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                                    .clickable { playReturn(row) }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            } else {
                                                Modifier.padding(horizontal = 4.dp)
                                            },
                                        ),
                                )
                            }
                            if (volumes.shortPauses > 0) {
                                Text(
                                    "另有 ${volumes.shortPauses} 次短停更（7~30 天，并入计数）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgFeedTokens.TextLow,
                                )
                            }
                        }
                    }
                }
                // ⑥ 互联档案卷（互转/互链/共现三类账——点=飞过去同相邻恒星链）
                ChronicleInterlinkVolume(
                    rels = relsNow,
                    starsById = starsById,
                    open = vol6Open,
                    onToggle = {
                        android.util.Log.i(
                            "TasteMapX",
                            "R251 卷展开: 卷=⑥互联档案 开=${!vol6Open} 星=尾号$starTail",
                        )
                        vol6Open = !vol6Open
                    },
                    onFly = { nb ->
                        android.util.Log.i(
                            "TasteMapX",
                            "R251 互联飞行: 尾号${(nb.chatId % 10000L + 10000L) % 10000L}（星=尾号$starTail）",
                        )
                        onFlyStar(nb)
                        onSelectStar(nb.chatId)
                    },
                )
                // 尾注（R246 尾注件：口径诚实——六卷本地证据口径）
                Text(
                    "档案基于本地深索引 ${fmtItems(chron.totalCount)} · 覆盖约 ${chron.coverageDays / 30} 个月 · 六卷口径：峰作品/里程碑/沉寂为本地证据（传播数据待入库），互联为图谱边账",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
        }
    }
}

private fun loadIndexedChatIds(app: android.app.Application): Set<Long> {
    val dbFile = File(File(app.filesDir, "deepindex"), "search.db")
    if (!dbFile.isFile) return emptySet()
    // R235 读侧加固：并发写窗（R236 tags 回填批持锁）偶发 busy→空集=退化工件
    // （真机实锤：系统性 6 星小宇宙图）；取消异常重抛（runCatching 吞
    // CancellationException=被取消的装配照样发布全空图——协作取消正确性）；
    // 单次重试+120ms 退避。
    repeat(2) { attempt ->
        try {
            val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                return d.rawQuery("SELECT chatId FROM meta", null).use { c ->
                    val out = HashSet<Long>(c.count.coerceAtLeast(1))
                    while (c.moveToNext()) out += c.getLong(0)
                    out
                }
            } finally {
                runCatching { d.close() }
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (attempt == 1) return emptySet()
            Thread.sleep(120)
        }
    }
    return emptySet()
}

/**
 * R152 深度索引全频道基因聚合（**IO 线程调用**）：一条 GROUP BY 拉全表，取代逐频道
 * loadIndexGenome 的 N 次开库（300 频道 N 次开库秒级，全表一次毫秒级）。
 * R206 在同一查询顺带加 MIN(dateEpochSec) 列（创世纪星生序原料——本就是全表
 * GROUP BY，边际成本≈0，不另开查询口）。
 * 库文件名与 DeepSearchIndex.DB_NAME("search.db") 同源（其 companion 私有引不到，
 * 字面量镜像——与 FeedGenomePart.loadIndexGenome 同款约定）；库不存在/查询异常返回
 * 空表——绝不为统计建库开写库（纪律同 loadIndexGenome）。
 *
 * **R254 性能刀（分钟级病灶的根治位）**：真库已长到 GB 级（用户实机 5.97GB 实测），
 * 原单线程全表 GROUP BY = 星图每次打开分钟级等待的主瓶颈。本口升级为三层：
 *  1. **指纹缓存**（[GENOME_CACHE_FILE]）：(库大小, meta 行数) 指纹命中=直接回
 *     上次聚合结果（毫秒级）——库增删量在容忍阈内（<2% 或 <64MB、频道数 ±5%）
 *     视为统计上等价（陈值无害——与金矿快照 6h TTL 同族口径：条数对数/构成占比
 *     这类统计量在百万级行上对小幅增量不敏感）；
 *  2. **rowid 分区并行全扫**（失配重算路径）：items 表保留 rowid（建表 SQL 无
 *     WITHOUT ROWID），按 rowid 区间切 N 片、N 条只读连接并行各自 GROUP BY、
 *     纯增量合并（COUNT/SUM/MIN 全可分片合并，合并结果与单线程逐字等值——
 *     星图数据零变化红线不破）；
 *  3. 结果原子落盘（tmp+rename）供下次指纹命中秒回。
 * 并行度=核数一半（2..8 钳制）；单分片 busy 重试 120ms×2 次后按空降级（与
 * loadIndexedChatIds 同族防御口径——锁窗内读被拒=诚实缺省，不重试风暴）。
 */
private fun loadIndexGenomes(app: android.app.Application): Map<Long, GenomeLite> {
    val dbFile = File(File(app.filesDir, "deepindex"), "search.db")
    if (!dbFile.isFile) return emptyMap()
    val dbSize = dbFile.length()
    val metaCount = runCatching { queryMetaRowCount(dbFile) }.getOrDefault(0L)
    val cached = readGenomeRollupCache(app)
    // R267：空基因组缓存不消费（批迁锁窗期的空扫描若落过盘=退化钉死源——视同未命中）
    if (cached != null && cached.second.isNotEmpty() && genomeRollupCacheValid(cached.first, dbSize, metaCount)) {
        android.util.Log.i(
            "TasteMap",
            "R254 基因聚合: 指纹缓存命中 频道=${cached.second.size}（库=$dbSize meta=$metaCount 陈值容忍阈内）",
        )
        return cached.second
    }
    val tScan = android.os.SystemClock.elapsedRealtime()
    val raws = genomeRollupScanParallel(dbFile)
    val genomes = HashMap<Long, GenomeLite>(raws.size * 2)
    for ((id, r) in raws) {
        val count = r[0].toInt()
        if (count <= 0) continue
        genomes[id] = GenomeLite(
            count, r[1].toInt(), r[2].toInt(),
            (count - r[1].toInt() - r[2].toInt()).coerceAtLeast(0),
            if (r[3] > 0L) r[4] / r[3] else 0L,
            r[5],
        )
    }
    // R267 数据源防御：大库扫出空基因组=批迁/迁移锁窗中间态实锤（分区重试耗尽
    // 「诚实缺省」按空降级）——**不落盘**（防空结果钉死缓存下次接着吃），日志实锤
    if (genomes.isEmpty() && (dbSize > 32L * 1024L * 1024L || metaCount >= 16)) {
        android.util.Log.w(
            "TasteMap",
            "R267 基因聚合: 全扫结果为空（库=$dbSize meta=$metaCount 批迁锁窗中间态）——不落盘，等下轮重扫",
        )
    } else {
        writeGenomeRollupCache(app, dbSize, metaCount, genomes)
    }
    android.util.Log.i(
        "TasteMap",
        "R254 基因聚合: 全扫完成 频道=${genomes.size} 耗时=${android.os.SystemClock.elapsedRealtime() - tScan}ms " +
            "分区=${genomeScanPartitionCount()}（库=$dbSize meta=$metaCount——指纹已落盘，下次秒回）",
    )
    return genomes
}

/** 基因分区聚合的原始累加器：[count, video, photo, timed, sumDur, minDate]（0=无） */
private fun genomeScanPartitionCount(): Int =
    (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

/** meta 行数（指纹原料——meta 表一行一会话，量级远小于 items，COUNT 毫秒级） */
private fun queryMetaRowCount(dbFile: File): Long {
    val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    try {
        return d.rawQuery("SELECT COUNT(*) FROM meta", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    } finally {
        runCatching { d.close() }
    }
}

/**
 * 指纹有效性：库大小增量 ≤ max(64MB, 2%) 且 meta 行数（频道/会话数）±5% 内。
 * 增量容忍=陈值无害口径（统计量对小幅增量不敏感）；超阈=频道集合显著变化，
 * 必须重算（任务书「频道数变化超过阈值（±5%）」字面口径）。
 */
private fun genomeRollupCacheValid(cachedFp: LongArray, dbSize: Long, metaCount: Long): Boolean {
    val sizeTol = maxOf(64L * 1024L * 1024L, cachedFp[0] / 50L)
    if (kotlin.math.abs(dbSize - cachedFp[0]) > sizeTol) return false
    if (cachedFp[1] > 0 && kotlin.math.abs(metaCount - cachedFp[1]) * 20L > cachedFp[1]) return false
    return true
}

/**
 * rowid 分区并行全扫（每分片独立只读连接；分片 0 在调用线程跑、其余起短线程，
 * 全部 join 后合并——COUNT/SUM/MIN 纯增量合并与单线程逐字等值）。
 * 返回 chatId → 原始累加器（见 genomeScanPartitionCount 相邻注释）。
 */
private fun genomeRollupScanParallel(dbFile: File): HashMap<Long, LongArray> {
    val maxRow = runCatching {
        val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        } finally {
            runCatching { d.close() }
        }
    }.getOrDefault(0L)
    val parts = genomeScanPartitionCount()
    val partial = ArrayList<HashMap<Long, LongArray>>(parts)
    repeat(parts) { partial += HashMap() }
    if (maxRow <= 0L) {
        // 空表/退化：仍走一次全范围扫（maxRow 探测失败≠表空——诚实兜底）
        genomeScanPartition(dbFile, 0L, Long.MAX_VALUE, partial[0])
        return partial[0]
    }
    val span = maxRow / parts + 1L
    val threads = ArrayList<Thread>(parts - 1)
    for (i in 1 until parts) {
        val lo = i * span
        val hi = if (i == parts - 1) Long.MAX_VALUE else (i + 1) * span
        val target = partial[i]
        val t = Thread {
            runCatching { genomeScanPartition(dbFile, lo, hi, target) }
        }
        t.name = "r254-genome-$i"
        t.start()
        threads += t
    }
    genomeScanPartition(dbFile, 0L, span, partial[0])
    for (t in threads) {
        runCatching { t.join() }
    }
    // 合并（分片 0 的表就地作累计容器——条目量级=会话数，合并毫秒级）
    val out = partial[0]
    for (i in 1 until parts) {
        for ((id, r2) in partial[i]) {
            val acc = out[id]
            if (acc == null) {
                out[id] = r2
            } else {
                for (k in 0..4) acc[k] += r2[k]
                if (r2[5] > 0L && (acc[5] == 0L || r2[5] < acc[5])) acc[5] = r2[5]
            }
        }
    }
    return out
}

/** 单分片扫描：rowid 区间 GROUP BY + busy 重试（2 次×120ms）后按空降级 */
private fun genomeScanPartition(
    dbFile: File,
    rowLo: Long,
    rowHi: Long,
    out: HashMap<Long, LongArray>,
) {
    repeat(2) { attempt ->
        try {
            val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                // R266 v11 双臂同分片（D6 含转发口径）：items 臂与 refs 臂按同一 rowid
                // 区间各扫（refs 行数远少于 items，同分片顺扫边际成本可忽略；两臂
                // rowid 空间各自独立但分片只影响并行均衡不影响正确性——合并语义=
                // COUNT/SUM 相加、MIN 取小，与单线程逐字等值）。refs 臂独立容错
                // （升级窗口期 v10 库无此表——缺席=存量形态直通）
                for (table in listOf("items", "repost_refs")) {
                    runCatching {
                        d.rawQuery(
                            "SELECT chatId, COUNT(*), " +
                                "SUM(CASE WHEN mediaType=0 THEN 1 ELSE 0 END), " +
                                "SUM(CASE WHEN mediaType=1 THEN 1 ELSE 0 END), " +
                                "SUM(CASE WHEN durationSec>0 THEN 1 ELSE 0 END), " +
                                "SUM(CASE WHEN durationSec>0 THEN durationSec ELSE 0 END), " +
                                "MIN(CASE WHEN dateEpochSec>0 THEN dateEpochSec END) " +
                                "FROM $table WHERE rowid >= ? AND rowid < ? GROUP BY chatId",
                            arrayOf(rowLo.toString(), rowHi.toString()),
                        ).use { c ->
                            while (c.moveToNext()) {
                                val id = c.getLong(0)
                                val count = c.getLong(1)
                                if (count <= 0L || id == 0L) continue
                                out.getOrPut(id) { LongArray(6) }.let { r ->
                                    r[0] += count
                                    r[1] += c.getLong(2)
                                    r[2] += c.getLong(3)
                                    r[3] += c.getLong(4)
                                    r[4] += c.getLong(5)
                                    val minDate = if (c.isNull(6)) 0L else c.getLong(6)
                                    if (minDate > 0L && (r[5] == 0L || minDate < r[5])) r[5] = minDate
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { d.close() }
            }
            return
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (attempt == 1) return // 锁窗/异常=诚实缺省（loadIndexedChatIds 同族口径）
            Thread.sleep(120)
        }
    }
}

// ---- R254 基因 rollup 磁盘缓存（galaxy 自持目录——与 discovered_stars.json 同纪律，
//      LocalStore 零改动；DataOutputStream 二进制=600~2000 条目毫秒级读写） ----

private const val GENOME_CACHE_MAGIC = 0x52474643 // "RGFC"
private const val GENOME_CACHE_VERSION = 1

private fun genomeRollupCacheFile(app: android.app.Application): File =
    // R266 v11 撞版本（设计风险 R3 对策）：v1→v2——原地收编不改库文件大小，旧指纹
    // （库大小,meta 行数）不失配会让基因组统计继续含转发陈值；换代=强制重算一次
    File(File(app.filesDir, "galaxy"), "genome_rollup_cache_v2.bin")

/** 读缓存（无/坏/版本不符=null）；返回 (指纹[库大小,meta行数], 基因表) */
private fun readGenomeRollupCache(app: android.app.Application): Pair<LongArray, Map<Long, GenomeLite>>? {
    val f = genomeRollupCacheFile(app)
    if (!f.isFile) return null
    return runCatching {
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f), 1 shl 15)).use { ins ->
            if (ins.readInt() != GENOME_CACHE_MAGIC) return@runCatching null
            if (ins.readInt() != GENOME_CACHE_VERSION) return@runCatching null
            val fp = longArrayOf(ins.readLong(), ins.readLong())
            ins.readLong() // savedAt（诊断用）
            val n = ins.readInt()
            if (n < 0 || n > 100_000) return@runCatching null
            val out = HashMap<Long, GenomeLite>(n * 2)
            repeat(n) {
                val id = ins.readLong()
                out[id] = GenomeLite(
                    ins.readInt(), ins.readInt(), ins.readInt(), ins.readInt(),
                    ins.readLong(), ins.readLong(),
                )
            }
            fp to out
        }
    }.getOrNull()
}

/** 原子落盘（tmp+rename——DiscoveryGoldmine.saveSnapshot 同款防半写） */
private fun writeGenomeRollupCache(app: android.app.Application, dbSize: Long, metaCount: Long, genomes: Map<Long, GenomeLite>) {
    runCatching {
        val f = genomeRollupCacheFile(app)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 15)).use { out ->
            out.writeInt(GENOME_CACHE_MAGIC)
            out.writeInt(GENOME_CACHE_VERSION)
            out.writeLong(dbSize)
            out.writeLong(metaCount)
            out.writeLong(System.currentTimeMillis())
            out.writeInt(genomes.size)
            for ((id, g) in genomes) {
                out.writeLong(id)
                out.writeInt(g.count)
                out.writeInt(g.video)
                out.writeInt(g.photo)
                out.writeInt(g.audio)
                out.writeLong(g.avgDurSec)
                out.writeLong(g.firstDateSec)
            }
        }
        if (!tmp.renameTo(f)) {
            // rename 失败兜底（跨分区/占用）：二进制流拷贝后删临时件
            java.io.FileInputStream(tmp).use { src ->
                java.io.FileOutputStream(f).use { dst -> src.copyTo(dst) }
            }
            tmp.delete()
        }
    }
}

/** R152 装配期特征原料（喂给 MapStarRaw 的新字段）：索引基因优先、投票构成回落后的统一出口 */
private class FeatFallback(val idxCount: Int, val gVideo: Int, val gPhoto: Int, val gAudio: Int, val avgDurSec: Long)

// ==================== R254 星图成品缓存（装配+布局产物二进制磁盘缓存） ====================
// 星图打开链 = 装配(IO 重查询) → MDS 布局(Default) → 成品 TasteMapData；本缓存把
// **成品**整体落盘，再次进页跳过全部计算（磁盘 ~200KB 量级，毫秒级读回+原子换装
// mapData）——「再开 <3s」验收的主承重件。头带 demo/perturb 位（形态或设置口径
// 不符=视为无缓存直走全量路径，绝不给错口径的星图）。

private const val TASTE_MAP_CACHE_MAGIC = 0x54464331 // "TFC1"

/**
 * R267 换代 v1→v2（塌方修复③）：头加 布局算法代次 [LAYOUT_ALGO_GEN] + 基因指纹
 * [LayoutHealth.genomeFp]——代次变更或指纹越阈即作废重算（不再只看频道数 ±5%）。
 * 旧 v1 缓存（含可能钉死的退化布局）读时直拒。
 */
private const val TASTE_MAP_CACHE_VERSION = 7 // R323：payload 星尾 drift（自行两窗三票——J 章批 5）=7——旧 v6 直拒重算（换代=设计动作：R323 漂移注记装载窗；gen7 布局轴零触碰）

/**
 * 布局算法代次（缓存作废闸）：V3 球面=3；R267 健康闸+哈希回退=4；R267 微扰稳定闸
 * （hub 爆炸根治——爆炸期擦线缓存全废）=5；R311 色彩引擎换代（bodyColor 语义=黑体
 * 连续色温×构成微调，旧构成混色缓存全废）=6；R303c 涌现布局换轨（sp 坐标语义=四力
 * 平衡位，V3 球面位=初值+降级位——旧 V3 坐标缓存全废）=7。
 * 旧代次缓存直拒（产物坐标语义不同不能混用；稳定闸前的缓存可能是爆炸云几何
 * ——已在 §6.6 哈希回退出现前写完盘）。加字段/改语义必须 +1 并在此注记沿革。
 */
private const val LAYOUT_ALGO_GEN = 7

private fun tasteMapCacheFile(app: android.app.Application): File =
    // R266 v11 撞版本（设计风险 R3 对策，genome_rollup_cache_v2 同族）：v1→v2——
    // 转发收编后频道条数结构变化，首开旧图沿用 ±5% 门可能放入陈口径星图；换代=
    // 首开走全量重建一次（新口径落盘后照旧秒显）
    File(File(app.filesDir, "galaxy"), "taste_map_cache_v2.bin")

/** 限定字符串写（长度前缀 + UTF-8 字节——不受 DataOutputStream.writeUTF 64KB 上限约束） */
private fun writeCacheString(out: java.io.DataOutputStream, s: String) {
    val b = s.toByteArray(Charsets.UTF_8)
    out.writeInt(b.size)
    out.write(b)
}

private fun readCacheString(ins: java.io.DataInputStream): String? {
    val n = ins.readInt()
    if (n < 0 || n > 1_000_000) return null
    val b = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = ins.read(b, read, n - read)
        if (r < 0) return null
        read += r
    }
    return String(b, Charsets.UTF_8)
}

/** 成品落盘（原子 tmp+rename；失败=false 由调用方日志降级——缓存缺失=下次全量，无害） */
private fun writeTasteMapCache(file: File, d: TasteMapData, demo: Boolean, perturb: Boolean): Boolean {
    return runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(TASTE_MAP_CACHE_MAGIC)
            out.writeInt(TASTE_MAP_CACHE_VERSION)
            out.writeBoolean(demo)
            out.writeBoolean(perturb)
            out.writeBoolean(d.emergent)    // R303c：涌现位/V3 位口径（翻转即作废重算）
            out.writeInt(LAYOUT_ALGO_GEN)   // R267：布局算法代次（读端不符直拒）
            out.writeLong(d.genomeFp)       // R267：基因指纹（换装门原料）
            out.writeLong(System.currentTimeMillis())
            // ---- 星体 ----
            out.writeInt(d.stars.size)
            for (s in d.stars) {
                out.writeLong(s.chatId)
                writeCacheString(out, s.title)
                out.writeLong(s.watchSec)
                out.writeLong(s.lastWatchMs)
                out.writeLong(s.videoSec)
                out.writeLong(s.photoSec)
                out.writeLong(s.audioSec)
                out.writeInt(s.memberCount)
                out.writeBoolean(s.subscribed)
                out.writeInt(s.degree)
                out.writeInt(s.colorArgb)
                out.writeFloat(s.alpha)
                out.writeFloat(s.radius)
                if (s.label != null) {
                    out.writeBoolean(true)
                    writeCacheString(out, s.label)
                } else {
                    out.writeBoolean(false)
                }
                out.writeInt(s.likeCount)
                out.writeInt(s.favCount)
                out.writeInt(s.danmakuCount)
                out.writeBoolean(s.hasComp)
                out.writeFloat(s.x)
                out.writeFloat(s.y)
                out.writeFloat(s.z)
                out.writeFloat(s.flightZ)
                out.writeFloat(s.spX)
                out.writeFloat(s.spY)
                out.writeFloat(s.spZ)
                out.writeLong(s.firstSeenMs)
                out.writeInt(s.kind.ordinal)
                out.writeInt(s.tier)
                out.writeInt(s.itemsCount)
                out.writeLong(s.lastActivitySec)
                out.writeInt(s.recent7)
                out.writeInt(s.prev7)
                out.writeFloat(s.bodyRadius)
                out.writeFloat(s.bodyAlpha)
                out.writeInt(s.bodyColor)
                out.writeFloat(s.tempK) // R311：连续色温（色彩引擎换代随缓存走）
                out.writeInt(s.gVideo)
                out.writeInt(s.gPhoto)
                out.writeInt(s.gAudio)
                out.writeInt(s.watchedCount)
                // R317b 宣判档：星尾 hist（发布直方图特征——前史行原料；null=未宣判）
                val sh = s.hist
                if (sh != null) {
                    out.writeBoolean(true)
                    out.writeInt(sh.items)
                    out.writeLong(sh.firstSec)
                    out.writeLong(sh.lastSec)
                    out.writeFloat(sh.baselinePer30d)
                    out.writeLong(sh.burstBucketSec)
                    out.writeInt(sh.burstPeakCount)
                    out.writeBoolean(sh.hasBurstPeak)
                    out.writeInt(sh.deathPattern.ordinal)
                    out.writeLong(sh.deadDays)
                    out.writeLong(sh.lifetimeDays)
                } else {
                    out.writeBoolean(false)
                }
                // R327 诞生注记（星尾 birth——注记随星走；null=未宣判/缺据；
                // 内嵌诞生史 hist 十字段与星尾 hist 同序列化式——回读深等成立）
                val sb = s.birth
                if (sb != null) {
                    out.writeBoolean(true)
                    out.writeInt(sb.stage.ordinal)
                    out.writeBoolean(sb.planetsForming)
                    out.writeLong(sb.firstSeenMs)
                    out.writeInt(sb.probeItems)
                    out.writeInt(sb.tagsCount)
                    val sbh = sb.hist
                    if (sbh != null) {
                        out.writeBoolean(true)
                        out.writeInt(sbh.items)
                        out.writeLong(sbh.firstSec)
                        out.writeLong(sbh.lastSec)
                        out.writeFloat(sbh.baselinePer30d)
                        out.writeLong(sbh.burstBucketSec)
                        out.writeInt(sbh.burstPeakCount)
                        out.writeBoolean(sbh.hasBurstPeak)
                        out.writeInt(sbh.deathPattern.ordinal)
                        out.writeLong(sbh.deadDays)
                        out.writeLong(sbh.lifetimeDays)
                    } else {
                        out.writeBoolean(false)
                    }
                } else {
                    out.writeBoolean(false)
                }
                // R323 自行漂移注记（星尾 drift——缓存 v7 载荷：**只存两窗三票原值
                // 6 int**，方向/强度/门全回读经 [ProperMotionFx.driftOf] 纯函数重导
                // =公式单一出处（未来调门旧缓存自洽重导）；null=未入池/缺据/零漂移）
                val sd = s.drift
                if (sd != null) {
                    out.writeBoolean(true)
                    out.writeInt(sd.nearVideo)
                    out.writeInt(sd.nearPhoto)
                    out.writeInt(sd.nearAudio)
                    out.writeInt(sd.farVideo)
                    out.writeInt(sd.farPhoto)
                    out.writeInt(sd.farAudio)
                } else {
                    out.writeBoolean(false)
                }
            }
            // ---- 连线 ----
            out.writeInt(d.links.size)
            for (l in d.links) {
                out.writeFloat(l.x1)
                out.writeFloat(l.y1)
                out.writeFloat(l.x2)
                out.writeFloat(l.y2)
                out.writeFloat(l.z1)
                out.writeFloat(l.z2)
                out.writeFloat(l.spX1)
                out.writeFloat(l.spY1)
                out.writeFloat(l.spZ1)
                out.writeFloat(l.spX2)
                out.writeFloat(l.spY2)
                out.writeFloat(l.spZ2)
                out.writeLong(l.a)
                out.writeLong(l.b)
                out.writeFloat(l.alpha)
            }
            out.writeFloat(d.zSpan)
            out.writeFloat(d.gridHalf)
            out.writeFloat(d.gridZ)
            out.writeLong(d.totalWatchSec)
            // ---- 今晚星轨（R206） ----
            out.writeInt(d.todayTrail.size)
            for (id in d.todayTrail) out.writeLong(id)
            // ---- 海报旧池（R223b 红线件） ----
            out.writeInt(d.legacyPosterIds.size)
            for (id in d.legacyPosterIds) out.writeLong(id)
            // ---- 天体分档统计 ----
            out.writeInt(d.kindCounts.size)
            for ((k, v) in d.kindCounts) {
                out.writeInt(k.ordinal)
                out.writeInt(v)
            }
            // ---- 星座轨迹站点（R234） ----
            out.writeInt(d.todayStops.size)
            for (s in d.todayStops) {
                out.writeLong(s.chatId)
                out.writeLong(s.tsMs)
            }
            // ---- 相邻恒星关联账（R248） ----
            out.writeInt(d.neighborArcs.size)
            for ((id, list) in d.neighborArcs) {
                out.writeLong(id)
                out.writeInt(list.size)
                for (a in list) {
                    out.writeLong(a.chatId)
                    out.writeInt(a.weight)
                }
            }
            out.writeFloat(d.meanItems)
            // ---- 双星对（R249；R321：尾加同源/共源附加账——v6 载荷口径） ----
            out.writeInt(d.binaryPairs.size)
            for (p in d.binaryPairs) {
                out.writeLong(p.a)
                out.writeLong(p.b)
                out.writeInt(p.weight)
                out.writeInt(p.sharedW)
                out.writeInt(p.trojanW)
            }
            // ---- 互联分类账（R251⑥） ----
            out.writeInt(d.neighborRels.size)
            for ((id, list) in d.neighborRels) {
                out.writeLong(id)
                out.writeInt(list.size)
                for (r in list) {
                    out.writeLong(r.chatId)
                    out.writeInt(r.forward)
                    out.writeInt(r.link)
                    out.writeInt(r.coWatch)
                }
            }
            // R303c：涌现在场标记（载荷尾字段——旧版读端到此流尽=自然坏流作废）
            out.writeBoolean(d.emergent)
            // R317b：宣判在场标记（载荷尾字段——读端 v4 版本闸先拒 v3；false=宣判
            // 未完成/未跑，下次进页重查）
            out.writeBoolean(d.verdictApplied)
        }
        if (!tmp.renameTo(file)) {
            java.io.FileInputStream(tmp).use { src ->
                java.io.FileOutputStream(file).use { dst -> src.copyTo(dst) }
            }
            tmp.delete()
        }
        true
    }.getOrDefault(false)
}

/** 成品读回（无/坏/版本或 demo/perturb/emergent 口径不符=null——调用方走全量路径；
 *  R303c：emergent 头口径不符=作废重算（AB 开关翻转即换代——涌现位/V3 位不混缓存）） */
private fun readTasteMapCache(file: File, demo: Boolean, perturb: Boolean, emergent: Boolean): TasteMapData? {
    if (!file.isFile) return null
    return runCatching {
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file), 1 shl 16)).use { ins ->
            if (ins.readInt() != TASTE_MAP_CACHE_MAGIC) return@runCatching null
            if (ins.readInt() != TASTE_MAP_CACHE_VERSION) return@runCatching null
            if (ins.readBoolean() != demo) return@runCatching null
            if (ins.readBoolean() != perturb) return@runCatching null
            if (ins.readBoolean() != emergent) return@runCatching null // R303c：AB 开关口径
            // R267：布局算法代次不符=作废（V3 代次变更的旧缓存不再消费）
            if (ins.readInt() != LAYOUT_ALGO_GEN) return@runCatching null
            val cachedGenomeFp = ins.readLong() // R267 基因指纹（换装门原料）
            ins.readLong() // savedAt（诊断用）
            fun readStr(): String = readCacheString(ins) ?: throw java.io.IOException("坏字符串")
            fun readKind(): CelestialKind {
                val o = ins.readInt()
                return if (o in CelestialKind.entries.indices) CelestialKind.entries[o] else CelestialKind.STAR
            }
            // ---- 星体 ----
            val n = ins.readInt()
            if (n < 0 || n > 20_000) return@runCatching null
            val stars = ArrayList<StarNode>(n)
            repeat(n) {
                val chatId = ins.readLong()
                val title = readStr()
                val watchSec = ins.readLong()
                val lastWatchMs = ins.readLong()
                val videoSec = ins.readLong()
                val photoSec = ins.readLong()
                val audioSec = ins.readLong()
                val memberCount = ins.readInt()
                val subscribed = ins.readBoolean()
                val degree = ins.readInt()
                val colorArgb = ins.readInt()
                val alpha = ins.readFloat()
                val radius = ins.readFloat()
                val label = if (ins.readBoolean()) readStr() else null
                stars += StarNode(
                    chatId = chatId,
                    title = title,
                    watchSec = watchSec,
                    lastWatchMs = lastWatchMs,
                    videoSec = videoSec,
                    photoSec = photoSec,
                    audioSec = audioSec,
                    memberCount = memberCount,
                    subscribed = subscribed,
                    degree = degree,
                    colorArgb = colorArgb,
                    alpha = alpha,
                    radius = radius,
                    label = label,
                    likeCount = ins.readInt(),
                    favCount = ins.readInt(),
                    danmakuCount = ins.readInt(),
                    hasComp = ins.readBoolean(),
                    x = ins.readFloat(),
                    y = ins.readFloat(),
                    z = ins.readFloat(),
                    flightZ = ins.readFloat(),
                    spX = ins.readFloat(),
                    spY = ins.readFloat(),
                    spZ = ins.readFloat(),
                    firstSeenMs = ins.readLong(),
                    kind = readKind(),
                    tier = ins.readInt(),
                    itemsCount = ins.readInt(),
                    lastActivitySec = ins.readLong(),
                    recent7 = ins.readInt(),
                    prev7 = ins.readInt(),
                    bodyRadius = ins.readFloat(),
                    bodyAlpha = ins.readFloat(),
                    bodyColor = ins.readInt(),
                    tempK = ins.readFloat(), // R311：连续色温（与写端同位）
                    gVideo = ins.readInt(),
                    gPhoto = ins.readInt(),
                    gAudio = ins.readInt(),
                    watchedCount = ins.readInt(),
                    // R317b 宣判档回读（与写端同位——data class 深等回读自检成立）
                    hist = if (ins.readBoolean()) {
                        TasteAstroPhysics.PublishFeatures(
                            items = ins.readInt(),
                            firstSec = ins.readLong(),
                            lastSec = ins.readLong(),
                            baselinePer30d = ins.readFloat(),
                            burstBucketSec = ins.readLong(),
                            burstPeakCount = ins.readInt(),
                            hasBurstPeak = ins.readBoolean(),
                            deathPattern = TasteAstroPhysics.DeathPattern.entries.getOrElse(ins.readInt()) {
                                TasteAstroPhysics.DeathPattern.ALIVE
                            },
                            deadDays = ins.readLong(),
                            lifetimeDays = ins.readLong(),
                        )
                    } else {
                        null
                    },
                    // R327 诞生注记回读（与写端同位——StarNode 深等含 birth，
                    // 回读自检 stars== 既有语句自动覆盖 round-trip 闸）
                    birth = if (ins.readBoolean()) {
                        TasteAstroPhysics.BirthFeatures(
                            chatId = chatId,
                            stage = TasteAstroPhysics.BirthStage.entries.getOrElse(ins.readInt()) {
                                TasteAstroPhysics.BirthStage.NOT_BIRTH
                            },
                            planetsForming = ins.readBoolean(),
                            firstSeenMs = ins.readLong(),
                            probeItems = ins.readInt(),
                            tagsCount = ins.readInt(),
                            hist = if (ins.readBoolean()) {
                                TasteAstroPhysics.PublishFeatures(
                                    items = ins.readInt(),
                                    firstSec = ins.readLong(),
                                    lastSec = ins.readLong(),
                                    baselinePer30d = ins.readFloat(),
                                    burstBucketSec = ins.readLong(),
                                    burstPeakCount = ins.readInt(),
                                    hasBurstPeak = ins.readBoolean(),
                                    deathPattern = TasteAstroPhysics.DeathPattern.entries.getOrElse(ins.readInt()) {
                                        TasteAstroPhysics.DeathPattern.ALIVE
                                    },
                                    deadDays = ins.readLong(),
                                    lifetimeDays = ins.readLong(),
                                )
                            } else {
                                null
                            },
                        )
                    } else {
                        null
                    },
                    // R323 自行漂移注记回读（与写端同位——两窗三票 6 int 原值经
                    // [ProperMotionFx.driftOf] 纯函数重导：方向/强度/门公式单一
                    // 出处；坏值防御=票数负值回落 null（缺据不判））
                    drift = if (ins.readBoolean()) {
                        val nv = ins.readInt()
                        val np = ins.readInt()
                        val na = ins.readInt()
                        val fv = ins.readInt()
                        val fp = ins.readInt()
                        val fa = ins.readInt()
                        if (nv < 0 || np < 0 || na < 0 || fv < 0 || fp < 0 || fa < 0) {
                            null
                        } else {
                            ProperMotionFx.driftOf(
                                ProperMotionFx.Votes(nv, np, na),
                                ProperMotionFx.Votes(fv, fp, fa),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            // ---- 连线 ----
            val m = ins.readInt()
            if (m < 0 || m > 50_000) return@runCatching null
            val links = ArrayList<StarLink>(m)
            repeat(m) {
                links += StarLink(
                    x1 = ins.readFloat(),
                    y1 = ins.readFloat(),
                    x2 = ins.readFloat(),
                    y2 = ins.readFloat(),
                    z1 = ins.readFloat(),
                    z2 = ins.readFloat(),
                    spX1 = ins.readFloat(),
                    spY1 = ins.readFloat(),
                    spZ1 = ins.readFloat(),
                    spX2 = ins.readFloat(),
                    spY2 = ins.readFloat(),
                    spZ2 = ins.readFloat(),
                    a = ins.readLong(),
                    b = ins.readLong(),
                    alpha = ins.readFloat(),
                )
            }
            val zSpan = ins.readFloat()
            val gridHalf = ins.readFloat()
            val gridZ = ins.readFloat()
            val totalWatchSec = ins.readLong()
            fun readLongList(cap: Int): ArrayList<Long> {
                val k = ins.readInt()
                if (k < 0 || k > cap) throw java.io.IOException("坏长度")
                val out = ArrayList<Long>(k)
                repeat(k) { out += ins.readLong() }
                return out
            }
            val todayTrail = readLongList(100_000)
            val legacyPosterIds = readLongList(100_000).toHashSet()
            val kc = ins.readInt()
            if (kc < 0 || kc > 64) throw java.io.IOException("坏长度")
            val kindCounts = HashMap<CelestialKind, Int>(kc * 2)
            repeat(kc) {
                val k = ins.readInt()
                val v = ins.readInt()
                if (k in CelestialKind.entries.indices) kindCounts[CelestialKind.entries[k]] = v
            }
            val stopsN = ins.readInt()
            if (stopsN < 0 || stopsN > 100_000) throw java.io.IOException("坏长度")
            val todayStops = ArrayList<TodayStop>(stopsN)
            repeat(stopsN) {
                todayStops += TodayStop(ins.readLong(), ins.readLong())
            }
            val arcsN = ins.readInt()
            if (arcsN < 0 || arcsN > 200_000) throw java.io.IOException("坏长度")
            val neighborArcs = HashMap<Long, List<NeighborArc>>(arcsN * 2)
            repeat(arcsN) {
                val id = ins.readLong()
                val k = ins.readInt()
                if (k < 0 || k > 10_000) throw java.io.IOException("坏长度")
                val list = ArrayList<NeighborArc>(k)
                repeat(k) { list += NeighborArc(ins.readLong(), ins.readInt()) }
                neighborArcs[id] = list
            }
            val meanItems = ins.readFloat()
            val bpN = ins.readInt()
            if (bpN < 0 || bpN > 1000) throw java.io.IOException("坏长度")
            val binaryPairs = ArrayList<BinaryPair>(bpN)
            repeat(bpN) {
                // R321：尾读同源/共源附加账（v6 载荷口径——版本闸已拒 v5）
                binaryPairs += BinaryPair(
                    ins.readLong(), ins.readLong(), ins.readInt(),
                    ins.readInt(), ins.readInt(),
                )
            }
            val relN = ins.readInt()
            if (relN < 0 || relN > 200_000) throw java.io.IOException("坏长度")
            val neighborRels = HashMap<Long, List<NeighborRel>>(relN * 2)
            repeat(relN) {
                val id = ins.readLong()
                val k = ins.readInt()
                if (k < 0 || k > 10_000) throw java.io.IOException("坏长度")
                val list = ArrayList<NeighborRel>(k)
                repeat(k) {
                    list += NeighborRel(ins.readLong(), ins.readInt(), ins.readInt(), ins.readInt())
                }
                neighborRels[id] = list
            }
            val emergentFlag = ins.readBoolean() // R303c：涌现在场标记（载荷尾）
            val verdictFlag = ins.readBoolean() // R317b：宣判在场标记（载荷尾——v4）
            TasteMapData(
                stars, links, zSpan, gridHalf, gridZ, totalWatchSec,
                todayTrail, legacyPosterIds, kindCounts, todayStops,
                neighborArcs, meanItems, binaryPairs, neighborRels,
                genomeFp = cachedGenomeFp,
                emergent = emergentFlag,
                verdictApplied = verdictFlag,
            )
        }
    }.getOrNull()?.let { d ->
        // ---- R267 缓存健康自检（塌方修复①收尾：旧包/旧代次写下的退化缓存——θ 塌缩
        //      竖线布局——读时弃用不装填；sp 坐标反推 θ=atan2(spZ,spX) 与 xy 格双口径，
        //      与布局期健康闸同一把尺子） ----
        if (d.stars.size >= LayoutHealth.SOURCE_CHECK_MIN_STARS) {
            val m = d.stars.size
            val th = FloatArray(m)
            val xs = FloatArray(m)
            val ys = FloatArray(m)
            d.stars.forEachIndexed { i, s ->
                th[i] = kotlin.math.atan2(s.spZ, s.spX)
                xs[i] = s.x
                ys[i] = s.y
            }
            val tu = LayoutHealth.thetaUniqueCount(th, m)
            val cu = LayoutHealth.coordUniqueCount(xs, ys, m)
            if (LayoutHealth.layoutDegenerate(tu, cu)) {
                android.util.Log.w(
                    "TasteMap",
                    "R267 缓存健康自检: 退化缓存弃用（θ唯一桶=$tu 坐标格=$cu 星=$m）——走全量重建",
                )
                return@let null
            }
        }
        d
    }
}

/**
 * R223b 全宇宙星图装配（suspend，IO 线程调用——金矿快照/类型化实体是挂起调用）：
 *  1. **节点池=深索引全宇宙**（设计 §五）：meta 全表 ∪ 金矿快照 ∪ DiscoveryProfile
 *     ∪ SearchEntity(bot 档案) ∪ 看过(watchSec>0) ∪ 订阅——全部现成本地只读
 *     （零 Search 族，R184 红线；金矿 6h TTL 陈值先回、DiscoveryProfile 1h TTL
 *     缓存命中零成本，未命中本轮按缺据诚实降级、后台单飞重建后下次进页补齐）；
 *     频道上限 [MAX_STARS]（看过>订阅>索引体量降序留重），非频道天体护栏
 *     [MAX_PULSARS]/[MAX_CLUSTERS]/[MAX_METEORS]。**类型化**：深索引覆盖
 *     频道+群组+私聊（listSearchableChats 全覆盖口径），meta/gold 里的群组→星团、
 *     bot 私聊→脉冲星、真人私聊→流星（DiscoveryProfile 权威判型+isBotUser 实证；
 *     无类型证据的按频道处理——诚实缺省）；
 *  2. 连线：图谱有向边合并成无向边（a<b），search_hit 弱证据边与 0 号伪节点不进图，
 *     超上限按合并权重留重；
 *  3. 内容类型构成：内存条目多数表决（与观看报告同口径）+ 事件级启发式兜底
 *     （durationSec<=0 → 图文；watchSec==durationSec 且 ≤30s → 图文；否则视频）；
 *  4. 互动账（R147 信息卡）：点过赞（likes.json 的 "tg:<chatId>:<msgId>" 前缀解析）/
 *     收藏（内存收藏表按 chatId 计数）/ 广场弹幕（广场快照 danmaku 的 key 前缀解析，
 *     含你自己发的——本地没有"只记自己弹幕"的账本，用全站口径如实标注）；
 *  5. R152 特征原料：深度索引一条 GROUP BY 全频道聚合（条数=体量、三类条数=构成
 *     回落、平均时长=时长风格），无观看账的频道由此获得真实构成——位置仍由数据决定；
 *  6. R223b 天体档案原料：金矿快照（items/lastActivity/recent7/prev7/tier——六态
 *     分档器的输入，缺据=恒星诚实缺省）；
 *  7. R223b 海报旧口径池：legacyPosterIds=旧三源封闭池（watch>订阅>图谱补齐 300）
 *     同序重算——海报内容集合按它过滤，「现有静态版不变」红线机制件。
 */
/**
 * R248 已知星集合装载体（R254 从 composable 抽出的顶层挂起函数——大方法字节码
 * 瘦身，语句原样搬移零行为变化）：持久账本 ∪ 会话点亮 ∪ 已看过/已订阅/特殊天体。
 */
private suspend fun loadKnownStarsR248(
    d: TasteMapData,
    discoverFile: File,
    sessionLit: Set<Long>,
): Set<Long> {
    val persisted = withContext(Dispatchers.IO) {
        runCatching { TasteDiscoveryLog.load(discoverFile) }.getOrDefault(emptySet())
    }
    val known = buildSet {
        addAll(persisted)
        addAll(sessionLit) // 会话点亮兜底（mapData 变更重装载与持久化 IO 的竞速窗）
        for (s in d.stars) {
            if (s.watchSec > 0 || s.subscribed) add(s.chatId)
            if (s.kind == CelestialKind.PULSAR || s.kind == CelestialKind.CLUSTER ||
                s.kind == CelestialKind.METEOR
            ) {
                add(s.chatId)
            }
        }
    }
    android.util.Log.i(
        "TasteMap",
        "R248 已知星装载: 已知=${known.size}/${d.stars.size} " +
            "（持久点亮=${persisted.size} 已看过∪已订阅∪特殊天体=动态）",
    )
    return known
}

/**
 * R235① 成就评定体（R254 从 composable 抽出的顶层纯函数——大方法字节码瘦身，
 * 语句原样搬移零行为变化）：发现频道/tag 探索/收藏/分享/六态/点亮数的等阶评定。
 */
private fun evaluateAchievementsR254(
    vm: FeedViewModel,
    d: TasteMapData,
    knownIds: Set<Long>?,
    shareCount: Int,
): TasteAchievements.AchievementReport {
    val channelCount = d.stars.count {
        it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
    }
    val exploredTags = HashSet<String>(128)
    for (it in vm.items.value) it.itemTags?.forEach { t -> if (t.isNotBlank()) exploredTags += t.lowercase() }
    for (it in vm.favoriteItems) it.itemTags?.forEach { t -> if (t.isNotBlank()) exploredTags += t.lowercase() }
    for (it in vm.watchLaterItems) it.itemTags?.forEach { t -> if (t.isNotBlank()) exploredTags += t.lowercase() }
    for (it in vm.store.loadHistoryItems()) {
        it.itemTags?.forEach { t -> if (t.isNotBlank()) exploredTags += t.lowercase() }
    }
    // R248 点亮恒星数（探索口径——区别于 discoveredChannels 宇宙口径；knownIds
    // 装载前=0 诚实缺省，装载完成即重评一次）
    val litStars = knownIds?.let { k -> d.stars.count { it.chatId in k && it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR } } ?: 0
    val report = TasteAchievements.evaluate(
        TasteAchievements.AchievementStats(
            discoveredChannels = channelCount,
            watchSec = d.totalWatchSec,
            favCount = vm.favoriteItems.size,
            exploredTags = exploredTags.size,
            shareCount = shareCount,
            kindsSeen = d.kindCounts.keys,
            discoveredStars = litStars,
        ),
    )
    android.util.Log.i(
        "TasteMap",
        "R235 成就评定: 等阶=${TasteAchievements.labelOf(report.rank)} " +
            "频道=${channelCount} tag=${exploredTags.size} 收藏=${vm.favoriteItems.size} 分享=$shareCount " +
            "六态=${d.kindCounts.keys.size}/6 R248点亮=$litStars",
    )
    return report
}

/**
 * R254 逻辑域状态句柄（composable 状态的委托目标显式化——抽出的顶层逻辑体引用；
 * 状态对象跨重组稳定，句柄每次重组重建亦可——纯引用容器零语义）。**存在动机**：
 * TasteMapScreen 主方法曾达 34.5K DEX units，超 ART 校验器极限（进页即
 * VerifyError 崩溃）——逻辑体搬出主方法是字节码瘦身的主承重件。
 */
private class GalaxyLogicCtx(
    val vm: FeedViewModel,
    val gateway: TdGateway,
    val app: android.app.Application,
    val context: android.content.Context,
    val scope: CoroutineScope,
    val engine: TasteFlight.FxEngine,
    val mapData: MutableState<TasteMapData?>,
    val canvasSize: MutableState<IntSize>,
    val cam: MutableState<SpaceCam>,
    val lens: MutableState<Float>,
    val knownIds: MutableState<Set<Long>?>,
    val revealIds: MutableState<Map<Long, Long>>,
    val physics: MutableState<TasteGalaxyPhysics?>,
    val selectedId: MutableState<Long?>,
    val nearFocusId: MutableLongState,
    val dustChatId: MutableLongState,
    val lockChatId: MutableLongState,
    val planetSysSnapshot: MutableState<Map<Long, TastePlanetEntry>>,
    val tagProfiles: MutableState<Map<Long, Map<String, Int>>>,
    val planetAssembling: MutableState<Boolean>,
    /** R367 井内可见域集合（轮询每拍发布——井深比<APPEAR_RATIO 的频道星；装配驱逐钉住原料） */
    val inWellIds: MutableState<Set<Long>>,
    /** R385 复合体快照（Stage 装配期一次发布——新星起飞点=所在复合体的判定原料；
     *  帧循环经 ctx 只读，主线程单写读=换代跟新零竞态，R367 inWellIds 先例） */
    val complexesR385: MutableState<List<DeepSpaceNebula.Complex>>,
    /** R367 装配拒绝会话去重（可见域占满诚实拒绝的探针行防刷屏账） */
    val planetRefuseLog: MutableSet<Long>,
    val flyIntoArmed: MutableState<Boolean>,
    val planetChoiceTag: MutableState<String?>,
    val genesisOn: MutableState<Boolean>,
    val playingChatId: MutableState<Long>,
    val spreadModel: MutableState<TasteTagSpread.SpreadModel?>,
    val spreadLoading: MutableState<Boolean>,
    val fling: FlingState,
    val focusFlight: FocusFlightState,
    /** R271 开局引导巡航状态（导演=帧循环 stepGalaxyTourR271；让位=abortGalaxyTourR271） */
    val tour: GalaxyTourState,
    val reveal: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val planetLru: LinkedHashMap<Long, TastePlanetEntry>,
    val sessionLit: MutableSet<Long>,
    val discoverFile: File,
    val orbitScratch: FloatArray,
    val pollPos: FloatArray,
    // ---- R258 视图层句柄（布局 composable/逻辑函数迁出主方法的接线——只加不改） ----
    // R345：renderer 句柄随 Canvas 渲染器整类退役拆除
    val shipEngine: TasteShipFx.ShipTrailEngine,
    val wormhole: WormholeState,
    val wormholeMenuTag: MutableState<String?>,
    val wormholeFromChat: MutableLongState,
    val genesis01: MutableState<Float>,
    val genesisPlaying: MutableState<Boolean>,
    /** R314 延时摄影倍速档下标（TIMELAPSE_SPEED_STEPS——帧循环推进与控制条档钮共用） */
    val genesisSpeedIdx: MutableState<Int>,
    val constellationOn: MutableState<Boolean>,
    val frameTick: MutableLongState,
    /** 发现点亮后的成就重评（evaluateAchievements(lastShareCount) 委托） */
    val onLits: () -> Unit,
    // ---- R301 行星世界卡句柄（tap 分支开卡/手势让位/帧循环环绕与回位的接线） ----
    val worldCard: MutableState<WorldCardTarget?>,
    val worldCam: WorldCamState,
    // ---- R302 星辉句柄（成长循环——账本/曲线/解锁全在 core TasteStarlight 纯件） ----
    /** 星辉增量账本文件（galaxy/starlight.json） */
    val starlightFile: File,
    /** 星辉全账快照（三源重算——null=未装载） */
    val starlightBook: MutableState<TasteStarlight.Book?>,
    /** 升级演出事件（刚升到的等级；0=无演出——一次性播放后归零） */
    val levelUpEvent: MutableState<Int>,
    /** 「最近的下一枚」指引行（成就面板航海图+罗盘 HUD 共用文案） */
    val guidanceLine: MutableState<String?>,
    /** 指引信标目标（世界系 xyz——星图画环；null=无空间目标） */
    val beaconPos: MutableState<FloatArray?>,
    /** 星域成员表（chatId → 星云格键——首踏星域判定） */
    val nebulaCellsByChat: MutableState<Map<Long, Int>>,
    /** 曲速巡航（Lv6——长按星空白处持续冲刺；让位=abortGalaxyTourR271 单口同停） */
    val warpCruise: MutableState<Boolean>,
    /** 会话目击去重（事件实例键——IO 落盘窗内的防重闸） */
    val witnessSession: HashSet<String>,
    /** 会话行星点亮去重（账本键——IO 落盘窗内的防重闸） */
    val planetLitSession: HashSet<String>,
    /** R312 点亮即时科普小卡（发现时刻批处理置位，UI overlay 消费——一拍至多一颗） */
    val litToast: MutableState<AstroLitToast?>,
    // ---- R316 二次拆分句柄（主方法再瘦身——全部只加不改，R258 同款纪律） ----
    /** 成就评定快照（面板/侧缘入口等阶色数据源） */
    val achievement: MutableState<TasteAchievements.AchievementReport?>,
    /** 成就面板开关（侧缘入口置位/面板关闭清零） */
    val showAchievements: MutableState<Boolean>,
    /** 会话级订阅覆盖（信息卡跨开合会话反馈） */
    val sessionSubscribed: MutableState<Set<Long>>,
    /** 海报导出中（按钮态——导出体迁顶层后回写） */
    val exporting: MutableState<Boolean>,
    /** 航行日志导出中（按钮态——导出体迁顶层后回写） */
    val exportingVoyage: MutableState<Boolean>,
    /** 首开引导浮层开关 */
    val showGuide: MutableState<Boolean>,
    /** 世界卡俯冲目标 tag（白闪推进 effect 键——俯冲体迁顶层后回写） */
    val worldDiveTag: MutableState<String?>,
    /** 成就重评用的分享账快照（最近一次口径） */
    val lastShareCount: MutableState<Int>,
    /** 选中的传播关键字（点行星→双选弹层「看传播」写入；null=零尘埃带） */
    val spreadTag: MutableState<String?>,
    /** 航行账本文件（分享/虫洞计数——导出体迁顶层后的落盘接线） */
    val voyageFile: File,
)

/**
 * R233 行星系统惰性装配体（R254 自 composable 抽出——语句原样搬移，状态改经
 * [GalaxyLogicCtx] 句柄读写，零行为变化）。
 */
private suspend fun assemblePlanetSystemR254(ctx: GalaxyLogicCtx, chatId: Long) {
    synchronized(ctx.planetLru) { if (ctx.planetLru.containsKey(chatId)) return }
    val items = withContext(Dispatchers.IO) {
        runCatching {
            ctx.vm.deepIndex.channelItemsPage(chatId, TasteTagPlanets.SAMPLE_LIMIT, 0)
        }.getOrDefault(emptyList())
    }
    val ledger = withContext(Dispatchers.IO) {
        runCatching { ctx.vm.deepIndex.topTags(chatId, TasteTagPlanets.MAX_PLANETS) }
            .getOrDefault(emptyList())
    }
    val system = TasteTagPlanets.build(chatId, items, ledger)
    val entry = TastePlanetEntry(chatId, system, items)
    // 淘汰保护 R367 扩界：焦点三态（选中/近焦/尘埃星——R233 原修）∪ **井内可见域
    // 全集**（井深比 < APPEAR_RATIO 的频道星，与渲染门同一阈=行星层正在点亮的域）
    // 钉在 LRU 里不淘汰。旧钉住面只盖三态：相机驻留密集星域时井内频道星数越过帽，
    // 每拍装配必驱逐一个**正在屏上渲染**的行星系（快照查表落空=整星行星层硬切消失，
    // 无淡出），下拍它又是井内未装配→重装再现→再逐下一个——循环=用户 2026-10-24
    // 08:19 实锤「行星一会消失一会儿出来来回闪烁」（尘埃档同病：尘埃星条件含
    // cachedIds，逐出即熄）。井内钉住后驱逐只吃井外（预取远星/离场星），可见域恒
    // 在场；井内集=轮询每拍现算现发布（N 体当帧位），井外星漂进井后一拍内即入钉。
    val pinned = HashSet<Long>(ctx.inWellIds.value)
    ctx.selectedId.value?.let { pinned += it }
    if (ctx.nearFocusId.longValue != 0L) pinned += ctx.nearFocusId.longValue
    if (ctx.dustChatId.longValue != 0L) pinned += ctx.dustChatId.longValue
    synchronized(ctx.planetLru) {
        // R367 幂等复核：预取/焦点/轮询 wantAssemble 三路并发同星竞装时后到者让位
        //（双 IO 浪费+重复 put 的 LRU 顺序扰动一并收口）
        if (ctx.planetLru.containsKey(chatId)) return
        if (ctx.planetLru.size >= PLANET_LRU_CAP) {
            var victim = ctx.planetLru.keys.firstOrNull { it !in pinned }
            if (victim == null) {
                // R412：帽被井内钉满时不再整拒——驱逐井内**最远**者（焦点三态永不逐）。
                // 旧口径诚实拒绝=新飞近的星永远装不上（13:23 设备实锚 在住=36 帽=36）；
                // 最远者=行星层最暗/最小的一颗，换给更近的星=可见收益最大
                val focus = HashSet<Long>(4)
                ctx.selectedId.value?.let { focus += it }
                if (ctx.nearFocusId.longValue != 0L) focus += ctx.nearFocusId.longValue
                if (ctx.dustChatId.longValue != 0L) focus += ctx.dustChatId.longValue
                victim = inWellFarthestFirstR412.firstOrNull { it in ctx.planetLru && it !in focus && it != chatId }
            }
            if (victim == null) {
                planetRefuseTotalR412.incrementAndGet()
                // 诚实拒绝（零驱逐零装入）：帽被井内可见域占满——宁可拒不装也不闪可见层
                //（探针一星一记会话去重，计数口径零内容）
                if (ctx.planetRefuseLog.add(chatId)) {
                    android.util.Log.w(
                        "TasteMap",
                        "R233 装配拒绝: 尾号${(chatId % 10000L + 10000L) % 10000L} 井内在住=${ctx.planetLru.size} " +
                            "帽=${PLANET_LRU_CAP}（可见域占满——零驱逐，诚实降级）",
                    )
                }
                return
            }
            ctx.planetLru.remove(victim)
            planetEvictTotalR412.incrementAndGet()
        }
        ctx.planetLru[chatId] = entry
        ctx.planetSysSnapshot.value = HashMap(ctx.planetLru)
    }
    // 指纹：账本在场走账本（fold 键=tags.tag；R234 创世纪 K tag 统计随之精确），
    // 空回退样本口径（fingerprintOf 同 fold 键——回填窗口期不断层）
    val fingerprint = if (ledger.isEmpty()) {
        TasteTagPlanets.fingerprintOf(items)
    } else {
        ledger.take(TasteTagPlanets.FINGERPRINT_TAGS).mapNotNull { (display, count) ->
            val fold = SearchTextKit.fold(display)
            if (fold.isEmpty()) null else fold to count
        }.toMap()
    }
    ctx.tagProfiles.value = ctx.tagProfiles.value + (chatId to fingerprint)
    android.util.Log.i(
        "TasteMap",
        "R233 行星装配: 频道尾号${(chatId % 10000L + 10000L) % 10000L} 行星=${system.planets.size} " +
            "样本=${system.sampleCount} 带tag=${system.taggedCount}" +
            if (ledger.isEmpty()) "（账本空：样本口径回退）" else "（tags 账本）",
    )
}

/**
 * R319 奥伯斯带装配（舞台件 LaunchedEffect(stars) IO 协程收口——**不走装配期**，
 * r319 设计定案③差集时机裁决三理由：①assembleTasteGalaxy 预算余量仅 42 units
 * 加块必爆；②金矿快照 6h TTL 独立陈化，钉进缓存载荷=金矿更新后暗点失真；③
 * effect 键 stars=换图（宣判换装/空名补捞拷贝发布）自动重算，IO 线程磁盘兜底
 * 不占装配主路径不占主线程——语义=进页一次离帧路径，与「装配期」同为页级时机）。
 *  - R_obs=sp* 模长 max（装配期既有坐标**只读消费零写入**——r303c 共存红线；
 *    下限 1f 见 [CmbBackdropFx.OlbersZone] 契约注）；星表空=null zone（整层
 *    缺据诚实缺省——渲染端首 if 归零）。
 *  - 暗点差集原料：金矿快照（内存缓存→磁盘兜底**只读绝不重建**——runGalaxyLoadR254
 *    金矿口径同款，重建归发现页单一所有方）− 星池成员（在图=已观测；差集天然
 *    =未观看未订阅未入图三条件）。筛选/排序/帽/布点全走 [CmbBackdropFx] 单源。
 *  - 探针只出计数（脱敏铁律——零频道名零标题）。
 */
private fun buildOlbersZoneR319(vm: FeedViewModel, stars: List<StarNode>): CmbBackdropFx.OlbersZone? {
    if (stars.isEmpty()) return null
    var rObs = 1f
    for (s in stars) {
        val m = sqrt(s.spX * s.spX + s.spY * s.spY + s.spZ * s.spZ)
        if (m > rObs) rObs = m
    }
    val poolIds = HashSet<Long>(stars.size * 2)
    for (s in stars) poolIds.add(s.chatId)
    val gold = vm.discoveryGoldSnapshot
        ?: runCatching { DiscoveryGoldmine.loadSnapshot(vm.discoveryGoldFile()) }.getOrNull()
    val pairs = ArrayList<Pair<Long, Int>>(gold?.channels?.size ?: 0)
    if (gold != null) for (c in gold.channels) pairs.add(c.chatId to c.items)
    val (cands, truncated) = CmbBackdropFx.olbersCandidatesOf(pairs, poolIds)
    val zone = CmbBackdropFx.olbersZoneOf(rObs, cands, cands.size + truncated, truncated)
    android.util.Log.i(
        "TasteMap",
        "R319 奥伯斯带: R_obs=${rObs.toInt()} 暗点=${cands.size}（金矿差集 ${cands.size + truncated} 截断 $truncated" +
            if (gold == null) " 快照缺席=零暗点缺据）" else "）",
    )
    return zone
}

/** R248 空名补捞体（R254 自 composable 抽出——语句原样搬移零行为变化） */
private suspend fun refreshLitTitlesR254(ctx: GalaxyLogicCtx, newly: List<StarNode>) {
    val blanks = newly.filter { it.title.isBlank() }
    if (blanks.isEmpty()) return
    val real = ctx.gateway as? TdLibGateway ?: return
    val graphTitles = runCatching { real.discoveryGraph?.snapshot() }.getOrNull()
        ?.nodes?.filter { it.title.isNotBlank() }?.associate { it.chatId to it.title } ?: emptyMap()
    val goldTitles = withContext(Dispatchers.IO) {
        runCatching { DiscoveryGoldmine.loadSnapshot(ctx.vm.discoveryGoldFile()) }.getOrNull()
    }?.byId()?.filterValues { it.title.isNotBlank() }?.mapValues { it.value.title } ?: emptyMap()
    val updates = HashMap<Long, String>()
    for (s in blanks) {
        val t = graphTitles[s.chatId] ?: goldTitles[s.chatId] ?: continue
        updates[s.chatId] = t
    }
    if (updates.isEmpty()) return
    val d = ctx.mapData.value ?: return
    ctx.mapData.value = d.copy(stars = d.stars.map { st -> updates[st.chatId]?.let { st.copy(title = it) } ?: st })
    android.util.Log.i(
        "TasteMap",
        "R248 空名补捞: ${updates.size} 颗（活图∪金矿现查回填）",
    )
}

/**
 * R233/R242/R248 行星轮询体（R254 自 composable 抽出——语句原样搬移，状态经
 * [GalaxyLogicCtx] 活读写，零行为变化）：大频道预取 + 引力井/尘埃/发现/飞进行
 * 星门一拍判定。CoroutineScope 扩展=原 LaunchedEffect 体 scope 语义同构。
 */
private suspend fun CoroutineScope.pollGalaxyBeatsR254(
    ctx: GalaxyLogicCtx,
    channelStars: List<StarNode>,
    wellMaxVol: Int,
) {
    // R412 行星覆盖体检探针（纯计数零内容）：入场 10s 一行全池账本覆盖分布，
    // 此后每 30s 一行井内覆盖（井内星/已装配/有行星/LRU 在住/累计拒绝）
    launch { planetCoverageProbeR412(ctx, channelStars) }
    // 大频道指纹预取（体量降序 top N，错峰——不抢装配/爬虫 IO）
    launch {
        for (s in channelStars.sortedByDescending { it.itemsCount }.take(PLANET_PREFETCH_TOP)) {
            runCatching { assemblePlanetSystemR254(ctx, s.chatId) }
            delay(PLANET_PREFETCH_GAP_MS)
        }
    }
    while (true) {
        delay(PLANET_POLL_MS)
        val cv = ctx.canvasSize.value
        if (cv.width <= 0 || cv.height <= 0) continue
        var bestNear = 0L
        var bestNearRatio = Float.MAX_VALUE
        var bestDust = 0L
        var bestDustDist = Float.MAX_VALUE
        var wantAssemble = 0L
        val wantList = ArrayList<Pair<Long, Float>>(16) // R412 本拍井内未装配候选
        var wantRatio = Float.MAX_VALUE
        val cachedIds = synchronized(ctx.planetLru) { ctx.planetLru.keys.toHashSet() }
        // ---- R248 星名点亮检测（一拍收集）：未知频道星 井深比 < DISCOVER_RATIO
        //      且投影在屏内（「发现=看见」诚实口径——背后/视野外的星飞过不点亮；
        //      阈值 > APPEAR_RATIO=名字先亮、行星后现） ----
        val known = ctx.knownIds.value
        val newly = ArrayList<StarNode>(4)
        // R367 井内可见域收集（驱逐钉住原料——与渲染门同一阈 APPEAR_RATIO，N 体当帧位）
        val wellIds = ArrayList<Long>(16)
        val wellRatios = ArrayList<Pair<Long, Float>>(16) // R412 驱逐序原料（远→近）
        val probe = FloatArray(4) // 本协程局部投影暂存（每拍复用零分配）
        val focalD = TasteSpace.focalOf(
            cv.width.toFloat(), cv.height.toFloat(),
        ) * ctx.lens.value
        val cxD = cv.width / 2f
        val cyD = cv.height / 2f
        val cam = ctx.cam.value
        for (s in channelStars) {
            // R242：井深比/尘埃深度带=|星−相机| 3D 距离（新相机的距离——任务书口径）
            // R249 v2：位置=N 体当帧渲染位（「发现=看见」对移动星同样诚实——
            // 飞过的星按它现在的位置判定，不按烘焙旧位）
            val hasPos = ctx.physics.value?.posOfChat(s.chatId, ctx.pollPos) == true
            val sx = if (hasPos) ctx.pollPos[0] else s.spX
            val sy = if (hasPos) ctx.pollPos[1] else s.spY
            val sz = if (hasPos) ctx.pollPos[2] else s.spZ
            val ratio = galaxyWellRatio(sx, sy, sz, s.itemsCount, wellMaxVol, cam)
            val dist = TasteSpace.distTo(cam, sx, sy, sz)
            val ahead = dist > TasteSpace.NEAR // 近裁面后的星不焦不尘埃（不可见）
            // R367 井内可见域入集（含未装配——钉住语义=「若已在 LRU 则不可逐」）
            if (ahead && ratio < TasteTagPlanets.APPEAR_RATIO) {
                wellIds += s.chatId
                wellRatios += s.chatId to ratio
            }
            // 近焦星：井深比最小且已进行星浮现档（关联弧的「飞近焦点态」）
            if (ahead && ratio < bestNearRatio && ratio < TasteTagPlanets.APPEAR_RATIO) {
                bestNearRatio = ratio
                bestNear = s.chatId
            }
            // 尘埃星：距离最小且进尘埃深度带 [150,260]（且行星层已亮+系统已装配）
            if (ahead && ratio < TasteTagPlanets.APPEAR_RATIO &&
                dist < bestDustDist && dist < TasteTagPlanets.DUST_GAP_MAX &&
                dist > TasteSpace.NEAR && s.chatId in cachedIds
            ) {
                bestDustDist = dist
                bestDust = s.chatId
            }
            // 井内但未装配（=行星层真正点亮域；预取余量 1.0× 真机校准口径）
            // R412：收集全部候选（下方按井深比近→远取前 PLANET_ASSEMBLE_PER_BEAT 个）
            if (ratio < TasteTagPlanets.APPEAR_RATIO && s.chatId !in cachedIds) {
                wantList += s.chatId to ratio
            }
            if (ratio < wantRatio && ratio < TasteTagPlanets.APPEAR_RATIO && s.chatId !in cachedIds) {
                wantRatio = ratio
                wantAssemble = s.chatId
            }
            // R248：点亮判定（未知 ∨ 井深比过阈 ∨ 屏内可见）
            if (known != null && s.chatId !in known && ratio < DISCOVER_RATIO &&
                TasteSpace.project(sx, sy, sz, cam, focalD, cxD, cyD, probe) &&
                probe[0] > -0.15f * cv.width && probe[0] < 1.15f * cv.width &&
                probe[1] > -0.15f * cv.height && probe[1] < 1.15f * cv.height
            ) {
                newly += s
            }
        }
        // R367 井内可见域发布（整集换血零陈值——星漂出井一拍内即失钉，井外可逐）
        ctx.inWellIds.value = wellIds.toHashSet()
        inWellFarthestFirstR412 = wellRatios.sortedByDescending { it.second }.map { it.first }
        // ---- R248 发现时刻（一拍批量）：集合更新+会话动画账+爆散+持久化+成就
        //      重评+空名补捞（装配时点标题快照之后才到账的频道名——发现机制
        //      的「星名浮现」不能被装配竞速饿死：活图/金矿现查回填 mapData） ----
        if (newly.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            val litIds = newly.map { it.chatId }
            ctx.sessionLit.addAll(litIds)
            ctx.knownIds.value = (known ?: emptySet()) + litIds
            ctx.revealIds.value = (ctx.revealIds.value + newly.associate { it.chatId to nowMs })
                .filterValues { nowMs - it < REVEAL_TTL_MS }
            for (s in newly) {
                val hasPos2 = ctx.physics.value?.posOfChat(s.chatId, ctx.pollPos) == true
                val bx = if (hasPos2) ctx.pollPos[0] else s.spX
                val by = if (hasPos2) ctx.pollPos[1] else s.spY
                val bz = if (hasPos2) ctx.pollPos[2] else s.spZ
                if (TasteSpace.project(bx, by, bz, cam, focalD, cxD, cyD, probe)) {
                    ctx.engine.burst(probe[0], probe[1], 12, 320f, (s.chatId % 100_000L).toInt())
                }
            }
            android.util.Log.i(
                "TasteMapX",
                "R248 发现: ${newly.size} 颗（尾号${newly.joinToString(",") { s -> ((s.chatId % 10000L + 10000L) % 10000L).toString() }}）" +
                    "井深比=${"%.1f".format(newly.minOf { galaxyWellRatio(it.spX, it.spY, it.spZ, it.itemsCount, wellMaxVol, cam) })}（<${DISCOVER_RATIO.toInt()} 在屏点亮）" +
                    "空名=${newly.count { it.title.isBlank() }}",
            )
            ctx.scope.launch {
                // R302 星辉记账（点亮 +10/首踏星域 +25/里程碑红包 +50——点亮账
                // 持久化同链延伸，重算即迁移发布；R248 点亮判定零改只加账）
                awardLightingR302(ctx, litIds, wellMaxVol)
            }
            if (newly.any { it.title.isBlank() }) {
                ctx.scope.launch { refreshLitTitlesR254(ctx, newly) }
            }
            // ---- R312 点亮即时科普小卡（发现时刻的科普钩子——一拍多颗时
            //      pickToastStar 稀有度序选一颗播报；instantLine 全数据驱动，
            //      缺据无数字变体）。
            //      R325/R327 跃迁 diff 三通道①（会话内点亮判别——**零缓存依赖**：
            //      kind 判别+birth 注记在场即判，运行时判——r325 C 章）：年轻星
            //      （PROTOSTAR ∨ 带注记）点亮=「✧ 新星诞生！」变体（点燃拍文案
            //      调性=r325 待用户过目项④默认）；同拍多颗时年轻位内插优先播报 ----
            val youngIdsR327 = newly.filter { s -> youngBirthStarR328(s.kind, s.birth) }
                .map { it.chatId }.toHashSet()
            AstroFacts.pickToastStar(newly.map { it.chatId to it.kind }, youngIdsR327)?.let { pickId ->
                val ps = newly.firstOrNull { it.chatId == pickId }
                if (ps != null) {
                    val dAgo = if (ps.lastActivitySec > 0L) {
                        (nowMs / 1000L - ps.lastActivitySec) / 86_400L
                    } else {
                        -1L
                    }
                    val fDays = if (ps.firstSeenMs > 0L) {
                        (nowMs - ps.firstSeenMs) / 86_400_000L
                    } else {
                        -1L
                    }
                    ctx.litToast.value = AstroLitToast(
                        ps.chatId, ps.kind,
                        AstroFacts.instantLine(ps.kind, ps.itemsCount, ps.recent7, dAgo, fDays, ps.birth),
                        nowMs,
                        // R328 点燃拍位：年轻变体（youngIdsR327 判别单口——R327 通道①
                        // 同口零第二判别式）才携白闪资格；老星 false=现状打字机零加
                        pickId in youngIdsR327,
                    )
                    android.util.Log.i(
                        "TasteMapX",
                        "R312 点亮科普: kind=${TasteGalaxyClassifier.labelOf(ps.kind)} " +
                            "尾号${(ps.chatId % 10000L + 10000L) % 10000L}（一拍 ${newly.size} 颗选稀有度最高）",
                    )
                }
            }
            ctx.onLits()
        }
        if (bestNear != ctx.nearFocusId.longValue) {
            ctx.nearFocusId.longValue = bestNear
            android.util.Log.i(
                "TasteMap",
                "R233 近焦星: 尾号${(bestNear % 10000L + 10000L) % 10000L} 井深比=" +
                    (if (bestNear == 0L) "-" else "%.1f".format(bestNearRatio)),
            )
        }
        if (bestDust != ctx.dustChatId.longValue) {
            ctx.dustChatId.longValue = bestDust
            android.util.Log.i(
                "TasteMap",
                "R233 尘埃星: 尾号${(bestDust % 10000L + 10000L) % 10000L} 星距=" +
                    (if (bestDust == 0L) "-" else "%.0f".format(bestDustDist)),
            )
        }
        // ---- R242 飞进行星（无人深空无缝法则：飞近行星直到足够近→内容门，
        //      R269 后弹层首行=「看内容·直接开刷」该 tag 全宇宙内容流；画布点行星
        //      已改直进内容流——本门保留弹层因飞行接近意图不如点按明确，可取消
        //      留图）：判定=近焦星（∨选中星）的行星 3D 距离 < 行星世界半径+门宽；
        //      弹层在场（planetChoiceTag≠null）不触发，全行星离场 2×门宽再武装 ----
        if (!ctx.genesisOn.value) {
            val gateId = ctx.selectedId.value ?: ctx.nearFocusId.longValue.takeIf { it != 0L }
            val gateStar = channelStars.firstOrNull { it.chatId == gateId }
            val gateSys = gateStar?.let { ctx.planetSysSnapshot.value[it.chatId] }
            if (gateSys != null && gateStar != null) {
                val seed = (gateStar.chatId % 100_000L).toInt()
                val inc = galaxyOrbitInclination(seed) // 渲染同一倾角单口
                val orb = ctx.orbitScratch
                // R249 v2：行星中心=恒星 N 体当前位（与渲染同一口径——门不误触）
                val hasGp = ctx.physics.value?.posOfChat(gateStar.chatId, ctx.pollPos) == true
                val gsx = if (hasGp) ctx.pollPos[0] else gateStar.spX
                val gsy = if (hasGp) ctx.pollPos[1] else gateStar.spY
                val gsz = if (hasGp) ctx.pollPos[2] else gateStar.spZ
                var minGate = Float.MAX_VALUE
                for (p in gateSys.system.planets.take(TasteTagPlanets.LOD_NEAR_PLANETS)) {
                    // R247 轨道/行星半径换锚恒星视觉半径（渲染/命中/飞入门同一式）
                    val rW = planetOrbitRadius(p, gateStar.bodyRadius)
                    val nu = TasteTagPlanets.keplerTheta(p, ctx.engine.timeSec)
                    val rOrb = TasteTagPlanets.keplerOrbitRadius(p, rW, nu)
                    planetOrbitPoint(
                        gsx, gsy, gsz, rOrb,
                        nu + TasteTagPlanets.keplerPeriapsis(p), inc, orb,
                    )
                    val dist = TasteSpace.distTo(cam, orb[0], orb[1], orb[2])
                    val pw = planetWorldRadius(p, gateStar.bodyRadius, rW)
                    if (dist - pw < minGate) {
                        minGate = dist - pw
                    }
                    val gateR = pw + GestureFeelPolicy.FLY_INTO_GATE_UNITS
                    // R302 行星点亮（+3——「首次飞近一颗 tag 行星」与飞进门同一尺
                    //      同一拍；账本幂等+会话防重闸，IO 窗内不重复奖）
                    if (dist < gateR) {
                        val pKey = TasteStarlight.planetKeyOf(gateStar.chatId, p.tag)
                        if (pKey !in ctx.planetLitSession &&
                            ctx.starlightBook.value?.ledger?.planets?.contains(pKey) != true
                        ) {
                            ctx.planetLitSession += pKey
                            ctx.scope.launch { awardPlanetR302(ctx, gateStar.chatId, p.tag, wellMaxVol) }
                        }
                    }
                    if (ctx.flyIntoArmed.value && dist < gateR && ctx.planetChoiceTag.value == null) {
                        ctx.flyIntoArmed.value = false
                        ctx.engine.burst(cv.width / 2f, cv.height / 2f, 10, 300f, p.tag.hashCode())
                        ctx.planetChoiceTag.value = p.tag
                        android.util.Log.i(
                            "TasteMapX",
                            "R242 飞进行星: tag=#${p.tag} 行星距=${"%.0f".format(dist)}（内容门=弹层·R269 首行看内容）",
                        )
                        break
                    }
                }
                // 再武装：弹层已关 + 全行星离场（2×门宽余量）——防「关掉弹层原地再弹」
                if (!ctx.flyIntoArmed.value && ctx.planetChoiceTag.value == null && minGate > 2f * GestureFeelPolicy.FLY_INTO_GATE_UNITS) {
                    ctx.flyIntoArmed.value = true
                }
            }
        }
        // 焦点星（选中优先）无档案 → 按需现算（弧判据的数据面）
        val focusId = ctx.selectedId.value ?: bestNear
        if (focusId != 0L && focusId !in ctx.tagProfiles.value) {
            runCatching { assemblePlanetSystemR254(ctx, focusId) }
        }
        if (wantAssemble != 0L && !ctx.planetAssembling.value) {
            ctx.planetAssembling.value = true
            // R412：每拍按井深比近→远串行装前 N 个（单飞锁不变=同一时刻至多一个装配协程）
            val batch = wantList.sortedBy { it.second }.take(PLANET_ASSEMBLE_PER_BEAT).map { it.first }
            launch {
                try {
                    for (id in batch) runCatching { assemblePlanetSystemR254(ctx, id) }
                } finally {
                    ctx.planetAssembling.value = false
                }
            }
        }
    }
}

/** R412 累计装配拒绝/驱逐计数（会话进程级，探针口径——零内容） */
private val planetRefuseTotalR412 = java.util.concurrent.atomic.AtomicInteger(0)
private val planetEvictTotalR412 = java.util.concurrent.atomic.AtomicInteger(0)

/** R412 井内星按井深比远→近序（轮询每拍整表换装发布——帽满时驱逐最远者的原料） */
@Volatile
private var inWellFarthestFirstR412: List<Long> = emptyList()

/** R412 行星覆盖体检（纯计数零内容；IO 线程查库） */
private suspend fun planetCoverageProbeR412(ctx: GalaxyLogicCtx, channelStars: List<StarNode>) {
    delay(10_000L)
    val ids = channelStars.map { it.chatId }
    val rows = withContext(Dispatchers.IO) {
        runCatching { ctx.vm.deepIndex.planetCoverageCounts(ids, TasteTagPlanets.SAMPLE_LIMIT) }
            .getOrDefault(emptyList())
    }
    val incomplete = withContext(Dispatchers.IO) {
        runCatching { ctx.vm.deepIndex.tagCoverageLikelyIncomplete() }.getOrDefault(true)
    }
    var l0 = 0; var l13 = 0; var l49 = 0; var l10 = 0
    var noSampleRows = 0; var sampleAllNull = 0; var sampleNoTag = 0; var zeroPlanet = 0
    var sNull = 0L; var sEmpty = 0L; var sTagged = 0L; var sRows = 0L
    for (r in rows) {
        when {
            r.ledgerTags == 0 -> l0++
            r.ledgerTags <= 3 -> l13++
            r.ledgerTags <= 9 -> l49++
            else -> l10++
        }
        sRows += r.sampleRows; sNull += r.sampleNull; sEmpty += r.sampleEmpty; sTagged += r.sampleTagged
        if (r.sampleRows == 0) noSampleRows++
        else if (r.sampleNull == r.sampleRows) sampleAllNull++
        if (r.sampleTagged == 0) sampleNoTag++
        if (r.ledgerTags == 0 && r.sampleTagged == 0) zeroPlanet++
    }
    android.util.Log.i(
        "TasteMap",
        "R412 行星覆盖: 全池恒星=${channelStars.size} 查得=${rows.size} 账本非空=${rows.size - l0} " +
            "账本分布 0=${l0} 1-3=${l13} 4-9=${l49} 10+=${l10} | 零行星(账本空且样本无tag)=${zeroPlanet} " +
            "样本无行=${noSampleRows} 样本全未解析=${sampleAllNull} 样本无tag=${sampleNoTag} | " +
            "样本行=${sRows} 未解析=${sNull} 已解析无tag=${sEmpty} 有tag=${sTagged} | " +
            "回填未完成=${incomplete} LRU在住=${synchronized(ctx.planetLru) { ctx.planetLru.size }} " +
            "累计拒绝=${planetRefuseTotalR412.get()} 累计驱逐=${planetEvictTotalR412.get()}",
    )
    while (true) {
        delay(30_000L)
        val well = ctx.inWellIds.value
        var assembled = 0; var withPlanets = 0
        val lruSize: Int
        synchronized(ctx.planetLru) {
            lruSize = ctx.planetLru.size
            for (id in well) {
                val e = ctx.planetLru[id] ?: continue
                assembled++
                if (e.system.planets.isNotEmpty()) withPlanets++
            }
        }
        android.util.Log.i(
            "TasteMap",
            "R412 井内覆盖: 井内星=${well.size} 已装配=${assembled} 有行星=${withPlanets} " +
                "LRU在住=${lruSize} 帽=${PLANET_LRU_CAP} 累计拒绝=${planetRefuseTotalR412.get()} " +
                "累计驱逐=${planetEvictTotalR412.get()}",
        )
    }
}

/** R234① 尘埃带召集体（R254 自 composable 抽出——语句原样搬移零行为变化） */
private suspend fun spreadTagRecallR254(ctx: GalaxyLogicCtx, tag: String?) {
    ctx.spreadModel.value = null
    ctx.spreadLoading.value = false
    if (tag == null) return
    ctx.spreadLoading.value = true
    val items = withContext(Dispatchers.IO) {
        runCatching { ctx.vm.deepIndex.tagItems(tag) }.getOrDefault(emptyList())
    }
    val model = TasteTagSpread.build(tag, items)
    ctx.spreadModel.value = model
    ctx.spreadLoading.value = false
    android.util.Log.i(
        "TasteMap",
        "R234 尘埃带装配: tag=#$tag 召回=${model.recalled}条 聚合频道=${model.channels.size}个 " +
            "源=尾号${(model.sourceChatId % 10000L + 10000L) % 10000L} " +
            "窗=[${model.t0Sec},${model.t1Sec}] 留重=${model.sampled}",
    )
}

/** 首次成图+视口就绪的初见方向体（R254 自 composable 抽出——语句原样搬移零行为变化） */
private suspend fun firstViewAimR254(ctx: GalaxyLogicCtx) {
    val d = ctx.mapData.value ?: return
    if (d.stars.isEmpty()) return
    val cv = ctx.canvasSize.value
    if (cv.width <= 0 || cv.height <= 0) return
    var wx = 0f
    var wy = 0f
    var wz = 0f
    var wgt = 0f
    for (s in d.stars) {
        val w = (s.watchSec + 1f).coerceAtMost(3_600_000f) * if (s.hasComp) 1.6f else 1f
        wx += s.spX * w
        wy += s.spY * w
        wz += s.spZ * w
        wgt += w
    }
    val cam = if (wgt > 0f) {
        val len = sqrt(wx * wx + wy * wy + wz * wz)
        val aim = FloatArray(2)
        TasteSpace.aimAt(SpaceCam.ORIGIN, wx / len, wy / len, wz / len, aim)
        SpaceCam(0f, 0f, 0f, aim[0], (aim[1] * 0.5f)) // 俯仰减半：质心方向常偏高，首屏平视感
    } else {
        SpaceCam.ORIGIN
    }
    ctx.cam.value = cam
    ctx.fling.yawVel = 0f
    ctx.fling.pitchVel = 0f
    ctx.fling.flyVel = 0f
    ctx.focusFlight.active = false
    // R271：取景重置=系统沿（装配换装相机复位）——只取消在飞巡航，**不抑制会话**
    // （触发 effect 键含 mapData，重置后可按频控重排）
    abortGalaxyTourR271(ctx, "取景重置", suppress = false)
    ctx.lockChatId.longValue = 0L
    ctx.reveal.snapTo(0f)
    ctx.reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
    android.util.Log.i(
        "TasteMap",
        "R242 初见方向: yaw=${"%.0f".format(cam.yawDeg)} pitch=${"%.0f".format(cam.pitchDeg)}（观看加权质心）",
    )
}

/** R233④ feed 跳入一次性运镜体（R254 自 composable 抽出——语句原样搬移零行为变化） */
private suspend fun flyTargetJumpR254(
    ctx: GalaxyLogicCtx,
    target: Long,
    onFlyConsumed: () -> Unit,
    wellMaxVol: Int,
) {
    // R269 修复（16384 真机实锤跳入不飞）：R254 抽出件把消费移到首行——mapData 未
    // 就绪窗（canvas 就绪而装配未完，冷装配=秒级常窗）effect 跑到这里=一次性意图被
    // 吞（消费→清位→mapData 到达后 effect 重跑 target 已 null=永不飞）。恢复 R233
    // 原版闸序：**mapData 未就绪直接返回不消费**（effect 键含 mapData，到达后重跑），
    // 全闸通过才消费；星不在图=消费+诚实 Toast（一次性语义同原版）。
    val d = ctx.mapData.value ?: return
    onFlyConsumed()
    abortGalaxyTourR271(ctx, "feed 跳入运镜") // R271：一次性运镜意图优先——巡航让位
    val star = d.stars.firstOrNull { it.chatId == target }
    if (star == null) {
        android.widget.Toast.makeText(ctx.context, "星图里还没有这颗星（未索引且未看过）", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    // 停船点=星前井×4.6（R242 口径：到达即行星层可见；R233 星锚停船语义迁移）
    // R268b：停船/τ 收口政策件
    val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(star.itemsCount, wellMaxVol))
    // ---- R301 A 案星图侧入场（即落拉远，design-dual-form §4）：feed 侧白闪峰值切页
    //      之后，本侧落点=目标星特写（停船距×0.3、直指恒星），focusFlight 从特写位
    //      缓拉远到井×4.6 停船——「睁开眼就在那颗星面前」的空间一致性。远距跳入才落
    //      特写（已在停船距 1.5 倍内=星图内跳入，不跳变直拉） ----
    run {
        val curCam = ctx.cam.value
        val dx = star.spX - curCam.px
        val dy = star.spY - curCam.py
        val dz = star.spZ - curCam.pz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len > stop * 1.5f && len > 1e-3f) {
            val aim = FloatArray(2)
            val closeCam = SpaceCam(
                star.spX - dx / len * stop * 0.3f,
                star.spY - dy / len * stop * 0.3f,
                star.spZ - dz / len * stop * 0.3f,
                curCam.yawDeg, curCam.pitchDeg,
            )
            TasteSpace.aimAt(closeCam, star.spX, star.spY, star.spZ, aim)
            ctx.cam.value = SpaceCam(closeCam.px, closeCam.py, closeCam.pz, aim[0], aim[1])
        }
    }
    ctx.focusFlight.beginWorldFlight()
    ctx.focusFlight.tx = star.spX
    ctx.focusFlight.ty = star.spY
    ctx.focusFlight.tz = star.spZ
    ctx.focusFlight.stopDist = stop
    ctx.focusFlight.tau = GestureFeelPolicy.CRUISE_TAU_SEC
    ctx.focusFlight.targetChatId = star.chatId // R249 v2：运镜目标跟随（N 体移动星）
    ctx.lockChatId.longValue = star.chatId
    ctx.fling.yawVel = 0f
    ctx.fling.pitchVel = 0f
    ctx.fling.flyVel = 0f
    android.util.Log.i(
        "TasteMap",
        "R233 feed 跳入运镜: 目标频道尾号${(target % 10000L + 10000L) % 10000L} " +
            "停船=${stop.toInt()}（井×4.6）",
    )
}

// ==================== R271 开局引导巡航（新用户带飞——剧本/频控/轨道数学收口 core.tastemap.TasteTour） ====================

/**
 * R271 让位单口（**手势优先级最高**——任何手势/手动运镜/feed 跳入/取景重置即刻
 * 中断巡航）：active||pending 在态才打探针（让位实锤）；清态+令牌自增杀延迟窗。
 * [suppress]=true（用户接管）：本次会话不再自动播（不消耗配额——下会话重评）；
 * false（取景重置等系统沿）：只取消本次，会话内可重排。
 */
private fun abortGalaxyTourR271(ctx: GalaxyLogicCtx, reason: String, suppress: Boolean = true) {
    val t = ctx.tour
    if (t.active || t.pending) {
        android.util.Log.i("TasteMap", "R271 运镜让位: $reason（手势/接管优先——巡航即刻中断）")
    }
    t.active = false
    t.pending = false
    t.pendingToken++
    if (suppress) t.sessionSuppress = true
    // R301：世界卡相机同族让位（任何手势/接管即刻停环绕与回位——卡片本身不强制关，
    // 用户可留卡继续读；环绕只在本卡会话内终止，不跨卡残留）
    ctx.worldCam.orbitEnabled = false
    ctx.worldCam.returnActive = false
    // R302：曲速巡航同族让位（任何手势/接管即停——Lv6 手势新能力的唯一停止口）
    if (ctx.warpCruise.value) {
        ctx.warpCruise.value = false
        android.util.Log.i("TasteMapX", "R302 曲速巡航: 停止（$reason——让位单口）")
    }
}

/**
 * R271 巡航规划（推近段启航）：亮星=TasteTour.pickBright（已知优先体量最大——绕转
 * 段井深比 4.6<10，选已知星不抢点亮段的发现时刻）；点亮目标=pickLightTarget（未知
 * ∧ 离亮星最近——轨道终点短跳读得懂；无未知星=0L 跳过点亮段，诚实降级直缓出）。
 * 推近段经 focusFlight 既有链（目标=亮星，停距=TasteTour.pushStop 政策件——恒真
 * 平移，τ=巡航 0.9s）。
 */
private fun planGalaxyTourR271(ctx: GalaxyLogicCtx, wellMaxVol: Int): Boolean {
    val t = ctx.tour
    val d = ctx.mapData.value ?: return false
    val known = ctx.knownIds.value ?: return false
    val tourStars = d.stars.filter {
        it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
    }.map { TasteTour.TourStar(it.chatId, it.itemsCount, it.spX, it.spY, it.spZ, known.contains(it.chatId)) }
    val bright = TasteTour.pickBright(tourStars) ?: return false
    val brightNode = d.stars.firstOrNull { it.chatId == bright.id } ?: return false
    val target = TasteTour.pickLightTarget(tourStars, bright.id)
    t.brightId = bright.id
    t.targetId = target?.id ?: 0L
    t.bx = brightNode.spX
    t.by = brightNode.spY
    t.bz = brightNode.spZ
    // 绕转半径=井×4.6 停船口径（绕转段井深比 4.6——行星层在舷窗外全程可见）
    t.orbitR = GestureFeelPolicy.cruiseStop(galaxyWellRadius(brightNode.itemsCount, wellMaxVol))
    // 推近段：focusFlight 既有链（停距=TasteTour.pushStop——亮星近时收到现距内侧，
    // 推近恒有真平移；远时中距落点，approach 只进不退的空心段实锤修法）
    val pushDist = TasteSpace.distTo(ctx.cam.value, brightNode.spX, brightNode.spY, brightNode.spZ)
    ctx.focusFlight.beginWorldFlight()
    ctx.focusFlight.tx = brightNode.spX
    ctx.focusFlight.ty = brightNode.spY
    ctx.focusFlight.tz = brightNode.spZ
    ctx.focusFlight.stopDist = TasteTour.pushStop(t.orbitR, pushDist)
    ctx.focusFlight.tau = GestureFeelPolicy.CRUISE_TAU_SEC
    ctx.focusFlight.targetChatId = bright.id // R249 v2：运镜目标跟随（N 体移动星）
    ctx.lockChatId.longValue = bright.id
    ctx.fling.yawVel = 0f
    ctx.fling.pitchVel = 0f
    ctx.fling.flyVel = 0f
    val nowMs = android.os.SystemClock.uptimeMillis()
    t.leg = TasteTour.LEG_PUSH
    t.legStartMs = nowMs
    t.tourStartMs = nowMs
    return true
}

/** R271 点亮段启航（绕转毕→飞向未探索星，井×4.6 停船——到达即 R248 发现机制
 *  自动点亮：井深比 4.6<10 在屏过阈，打字机名签+扩散环零新链路白拿） */
private fun startTourLightLegR271(ctx: GalaxyLogicCtx, nowMs: Long, wellMaxVol: Int) {
    val t = ctx.tour
    val target = ctx.mapData.value?.stars?.firstOrNull { it.chatId == t.targetId }
    if (target == null) {
        startTourEaseLegR271(ctx, nowMs) // 目标缺席（换装/漂移出图）——诚实直缓出
        return
    }
    // R268b：停船/τ 收口政策件（井×4.6 钳 [80,260]）
    val stop = GestureFeelPolicy.cruiseStop(galaxyWellRadius(target.itemsCount, wellMaxVol))
    ctx.focusFlight.beginWorldFlight()
    ctx.focusFlight.tx = target.spX
    ctx.focusFlight.ty = target.spY
    ctx.focusFlight.tz = target.spZ
    ctx.focusFlight.stopDist = stop
    ctx.focusFlight.tau = GestureFeelPolicy.CRUISE_TAU_SEC
    ctx.focusFlight.targetChatId = target.chatId // N 体跟随
    ctx.lockChatId.longValue = target.chatId
    t.leg = TasteTour.LEG_LIGHT
    t.legStartMs = nowMs
    android.util.Log.i(
        "TasteMap",
        "R271 运镜段: 绕转→点亮 目标尾号${(target.chatId % 10000L + 10000L) % 10000L} " +
            "停船=${stop.toInt()}（井×4.6 到达即点亮）",
    )
}

/** R271 缓出段启航（回宇宙原点=全景——τ=1.3s 比巡航更缓的离场） */
private fun startTourEaseLegR271(ctx: GalaxyLogicCtx, nowMs: Long) {
    val t = ctx.tour
    ctx.focusFlight.beginWorldFlight()
    ctx.focusFlight.tx = 0f
    ctx.focusFlight.ty = 0f
    ctx.focusFlight.tz = 0f
    ctx.focusFlight.stopDist = 0f
    ctx.focusFlight.tau = TasteTour.EASE_TAU_SEC
    ctx.focusFlight.targetChatId = 0L // 固定世界点（无跟随目标）
    t.leg = TasteTour.LEG_EASE
    t.legStartMs = nowMs
    android.util.Log.i("TasteMap", "R271 运镜段: →缓出（回全景 τ=${TasteTour.EASE_TAU_SEC}s）")
}

/**
 * R271 巡航导演（帧循环逐帧驱动——**只在 tour.active 时被调用**）：
 * 推近/点亮/缓出段=focusFlight 到达沿（active→false）推进；绕转段直驱 SpaceCam
 * （轨道点=TasteTour.orbitPos 政策件，朝向=TasteSpace.aimAt 既有件，亮星中心帧帧
 * 重锚 N 体渲染位）；每段 16s 超时兜底（N 体漂移/收敛角闸卡死也强推下一段——
 * 巡航不挂死）。轨迹探针 250ms 节流（相机位随时间曲线的实锤取证件）。
 */
private fun stepGalaxyTourR271(ctx: GalaxyLogicCtx, dtSec: Float, nowMs: Long, wellMaxVol: Int) {
    val t = ctx.tour
    // 亮星当帧位（N 体渲染位优先——绕的是所见星；场缺位回退烘焙位）
    val hasB = ctx.physics.value?.posOfChat(t.brightId, ctx.pollPos) == true
    val bx = if (hasB) ctx.pollPos[0] else t.bx
    val by = if (hasB) ctx.pollPos[1] else t.by
    val bz = if (hasB) ctx.pollPos[2] else t.bz
    val cam = ctx.cam.value
    val legTimeout = nowMs - t.legStartMs > (TasteTour.LEG_TIMEOUT_SEC * 1000).toLong()
    when (t.leg) {
        TasteTour.LEG_PUSH -> {
            if (!ctx.focusFlight.active || legTimeout) {
                // 推近到位（或超时兜底）→ 绕转段：轨道基 u=相机→亮星起点半径方向
                // （超时沿 focusFlight 仍在飞——先摘权：绕转段导演直驱 cam，两个写者不同框）
                ctx.focusFlight.active = false
                if (TasteTour.orbitBasis(cam.px, cam.py, cam.pz, bx, by, bz, t.basis)) {
                    t.orbitR0 = TasteSpace.distTo(cam, bx, by, bz)
                    t.leg = TasteTour.LEG_ORBIT
                    t.legStartMs = nowMs
                    android.util.Log.i(
                        "TasteMap",
                        "R271 运镜段: 推近→绕转 亮星尾号${(t.brightId % 10000L + 10000L) % 10000L} " +
                            "轨道半径 ${t.orbitR0.toInt()}→${t.orbitR.toInt()} 半圈 ${TasteTour.ORBIT_SEC}s" +
                            (if (legTimeout) "（超时兜底推进）" else ""),
                    )
                } else {
                    startTourLightLegR271(ctx, nowMs, wellMaxVol) // 贴星心退化——直跳点亮段
                }
            }
        }
        TasteTour.LEG_ORBIT -> {
            val p = ((nowMs - t.legStartMs) / 1000f / TasteTour.ORBIT_SEC).coerceIn(0f, 1f)
            val r = TasteTour.orbitRadiusAt(t.orbitR0, t.orbitR, p)
            val theta = TasteTour.orbitTheta(p)
            TasteTour.orbitPos(
                bx, by, bz,
                t.basis[0], t.basis[1], t.basis[2], t.basis[3], t.basis[4], t.basis[5],
                r, theta, t.scratch,
            )
            // 始终盯着亮星（aimAt 既有件——绕转段零新相机数学）
            val nc = SpaceCam(t.scratch[0], t.scratch[1], t.scratch[2], cam.yawDeg, cam.pitchDeg)
            TasteSpace.aimAt(nc, bx, by, bz, t.aim)
            ctx.cam.value = SpaceCam(t.scratch[0], t.scratch[1], t.scratch[2], t.aim[0], t.aim[1])
            if (p >= 1f) startTourLightLegR271(ctx, nowMs, wellMaxVol)
        }
        TasteTour.LEG_LIGHT -> {
            if (!ctx.focusFlight.active || legTimeout) {
                t.leg = TasteTour.LEG_DWELL
                t.legStartMs = nowMs
                android.util.Log.i(
                    "TasteMap",
                    "R271 运镜段: 点亮→驻留（盯星 ${TasteTour.DWELL_MS}ms——打字机名签+扩散环播完）" +
                        (if (legTimeout) "（超时兜底推进）" else ""),
                )
            }
        }
        TasteTour.LEG_DWELL -> {
            // 驻留盯星：focusFlight 逐帧重钉（N 体移动目标跟随——注视不漂；帧循环
            // approach 块在其后跑，到位翻 false 无碍——下一帧导演再钉）
            if (t.targetId != 0L) {
                ctx.focusFlight.beginWorldFlight()
                ctx.focusFlight.targetChatId = t.targetId
            }
            if (nowMs - t.legStartMs >= TasteTour.DWELL_MS) startTourEaseLegR271(ctx, nowMs)
        }
        TasteTour.LEG_EASE -> {
            if (!ctx.focusFlight.active || legTimeout) {
                t.active = false
                val litOk = t.targetId != 0L && ctx.knownIds.value?.contains(t.targetId) == true
                android.util.Log.i(
                    "TasteMap",
                    "R271 运镜收官: 全程${"%.1f".format((nowMs - t.tourStartMs) / 1000f)}s " +
                        "点亮目标尾号${(t.targetId % 10000L + 10000L) % 10000L} 点亮=$litOk" +
                        (if (legTimeout) "（超时兜底收官）" else ""),
                )
            }
        }
    }
    // 轨迹探针（250ms 节流——运镜轨迹=相机位随时间曲线的事件级取证件）
    if (t.active && nowMs - t.probeAt >= TasteTour.PROBE_MS) {
        t.probeAt = nowMs
        val c = ctx.cam.value
        android.util.Log.i(
            "TasteMapX",
            "R271 轨迹: leg=${TasteTour.legName(t.leg)} t=${"%.1f".format((nowMs - t.tourStartMs) / 1000f)}s " +
                "pos=(${"%.0f".format(c.px)},${"%.0f".format(c.py)},${"%.0f".format(c.pz)}) " +
                "yaw=${"%.0f".format(c.yawDeg)} pitch=${"%.0f".format(c.pitchDeg)}",
        )
    }
}

/**
 * R271 自动开播闸（触发 effect 体——LaunchedEffect(mapData, knownIds, showGuide)）：
 * 全闸过才排期——浮层不在屏（两者不叠加）/会话未抑制/星数下限/已知集合已装载/
 * 画布就绪/无 feed 跳入意图/频控放行（TasteTour.shouldAutoPlay）；排期延迟
 * [TasteTour.START_DELAY_MS]（揭示动画播完），窗内让位（令牌失效）即弃航；
 * **启航即计数落盘**（频控=防打扰语义：手势中断也算播过）。
 */
private suspend fun maybeAutoStartGalaxyTourR271(
    ctx: GalaxyLogicCtx,
    prefs: AppPrefs,
    showGuide: Boolean,
    flyTargetChatId: Long?,
) {
    val t = ctx.tour
    if (t.active || t.sessionSuppress) return
    val d = ctx.mapData.value ?: return
    if (showGuide) return // v3 文字教学在屏——关闭后再带飞（浮层=教学，运镜=带飞，不叠加）
    if (d.stars.size < TasteTour.MIN_STARS) return
    if (ctx.knownIds.value == null) return // 已知集合装载完才选得准未探索星
    val cv = ctx.canvasSize.value
    if (cv.width <= 0 || cv.height <= 0) return
    if (flyTargetChatId != null) return // feed 跳入运镜优先（一次性意图不并航）
    val count = prefs.tasteTourCount
    if (!TasteTour.shouldAutoPlay(count, prefs.tasteTourStarBase, d.stars.size)) return
    // 全闸过——排期（重启/重排防护：令牌；旧延迟协程随 effect 重启已被取消）
    t.pending = true
    val token = ++t.pendingToken
    kotlinx.coroutines.delay(TasteTour.START_DELAY_MS)
    if (!t.pending || t.pendingToken != token) return // 延迟窗内已被让位/重排
    if (t.active || t.sessionSuppress) return
    if (ctx.focusFlight.active || ctx.wormhole.active) {
        t.pending = false // 他运镜在飞——让位不抢（本次会话由对方接管语义收尾）
        t.sessionSuppress = true
        return
    }
    if (!planGalaxyTourR271(ctx, galaxyWellMaxVol(ctx))) {
        t.pending = false
        return
    }
    // 启航即计数落盘（频控=防打扰语义：手势中断也算播过；commit 同步双写——
    // apply 异步刷盘在进程被秒杀窗丢写实锤，R277 glSessionActive 同款修法）
    prefs.bumpTasteTour(d.stars.size)
    t.pending = false
    t.active = true
    t.probeAt = 0L
    android.util.Log.i("TasteMap", "R271 频控: 计数 $count→${count + 1} 落盘（星数基线=${d.stars.size}）")
    android.util.Log.i(
        "TasteMapX",
        "R271 开局运镜: 启航（第${count + 1}次/${TasteTour.MAX_AUTO_PLAYS} 星数=${d.stars.size} " +
            "亮星尾号${(t.brightId % 10000L + 10000L) % 10000L} " +
            "目标尾号${if (t.targetId != 0L) ((t.targetId % 10000L + 10000L) % 10000L).toString() else "无——直缓出"}）",
    )
}

/** R271 井半径归一分母现取（channelStars 最大体量——与轮询/运镜族同口径） */
private fun galaxyWellMaxVol(ctx: GalaxyLogicCtx): Int {
    val d = ctx.mapData.value ?: return 0
    return d.stars.filter {
        it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
    }.maxOfOrNull { it.itemsCount } ?: 0
}

// ==================== R282 活宇宙 2.0·宇宙导演器（接线件——逻辑全在顶层，composable 只留薄调用） ====================

/** 导演器配置（全页共享不可变——节奏定标见 UniverseDirector.Config KDoc） */
private val r282DirectorCfg = UniverseDirector.Config()

/** 爬虫脉冲在图频道暂存（主线程单写读——FxEngine 同款线程纪律） */
private val r282CrawlBuf = LongArray(32)

/** 导演指令复用清单（tick 产出按拍清空复用——帧路径零分配） */
private val r282DirectivesBuf = ArrayList<UniverseDirector.Directive>(8)

/** 事件账本会话内存环（节流落盘的源；synchronized 自身——主线程写/IO 线程读快照） */
private val r282Journal = ArrayList<UniverseDirector.Entry>(UniverseDirector.JOURNAL_CAP)

/** 账本最近落盘时刻（≥5s 节流——事件量极小，文件写频次有界） */
private var r282JournalSavedMs = 0L

/** 指令执行投影暂存（主线程帧路径复用——零分配） */
private val r282Proj = FloatArray(4)

/** R385 新星起飞云心投影暂存（主线程帧路径复用——r282Proj 同例零分配） */
private val r385CpxProj = FloatArray(4)

/**
 * 星云漂移量化缓存：相位按 [R282_DRIFT_QUANT] 圈分档——帧组装每帧调用但只在
 * 跨档时重建（≤6 团拷贝，4 分钟周期下 ~4Hz 换档），命中=一次引用读零分配。
 * 线程：帧组装在 GL 模式被 GL 线程调用、Canvas 模式被主线程调用（引擎互斥
 * 不会并发）；@Volatile 引用换装=读后即用安全（缓存内容不可变）。
 */
private class R282DriftCache(
    val q: Int,
    val src: List<TasteNebula.NebulaCluster>,
    val out: List<TasteNebula.NebulaCluster>,
)

@Volatile
private var r282DriftCache: R282DriftCache? = null

/** 漂移量化档（圈）——0.001 圈 ≈ 0.36°（摇曳角幅 3.2° 的 1/9，视觉无级差感） */
private const val R282_DRIFT_QUANT = 0.001

/**
 * R282 星云漂移（帧组装单行调用——「星云旋臂缓慢流转」）：对每团雾心施
 * [UniverseDirector.applyDrift] 摇曳（受控缓慢形变；振荡不漂走=雾团永不
 * 远离成员星域；**零写入 N 体**=物理保形）。量化缓存保帧路径零分配。
 */
private fun driftNebulaeR282(
    src: List<TasteNebula.NebulaCluster>,
    director: UniverseDirector.State,
): List<TasteNebula.NebulaCluster> {
    if (src.isEmpty()) return src
    val phase = UniverseDirector.driftTurnsOf(director)
    val q = (phase / R282_DRIFT_QUANT).toInt()
    val cached = r282DriftCache
    if (cached != null && cached.q == q && cached.src === src) return cached.out
    val out = ArrayList<TasteNebula.NebulaCluster>(src.size)
    val tmp = FloatArray(3)
    for (i in src.indices) {
        val c = src[i]
        UniverseDirector.applyDrift(c.cx, c.cy, c.cz, i, phase, r282DirectorCfg, tmp)
        out += TasteNebula.NebulaCluster(tmp[0], tmp[1], tmp[2], c.radius, c.colorArgb, c.count, c.dominant)
    }
    r282DriftCache = R282DriftCache(q, src, out)
    return out
}

/** R373 复合体漂移量化缓存（镜像 R282DriftCache——双源两表一体缓存） */
private class R373DriftCache(
    val q: Int,
    val cpxSrc: List<DeepSpaceNebula.Complex>,
    val cloudSrc: List<DeepSpaceNebula.EnrichCloud>,
    val cpx: List<DeepSpaceNebula.Complex>,
    val clouds: List<DeepSpaceNebula.EnrichCloud>,
)

/**
 * R385 平衡位移滑移账（design-nebula §3.3-3「数据变化走装配期重算+2s 平衡位移
 * 滑移（R303c 口径），diff 非静默」——CanvasLayer 会话级）：[onGenerationR385]
 * 数据换代检测（**格键身份集 diff**——[DeepSpaceNebula.Complex.cellKey] 确定性
 * 身份锚：引用换代但身份集不变=零行为（避免 remember 键抖动假启动）；真变才
 * 启动 2s 窗+diff 探针行（宣判体系纪律：变化有账可查））；[frameOfR385] 帧组装
 * 消费（窗内 [DeepSpaceNebula.slideOf]——质心/半径线性滑移+新云半径自 0 凝出
 * +消散云即刻退场；**恒等直通帧零分配**（null=调用方用 drifted 原值——
 * slideOf 的恒等内容 Result 分配在账层短路）；t≥1 关窗）。last/from 双
 * [DeepSpaceNebula.Result]（from=换代前装配态=滑移起点；首装=last 空零 diff
 * 零滑移）。主线程单写读（LaunchedEffect+帧组装同线程）。
 */
private class DeepSpaceSlideR385 {
    /** 最近一代装配态（换代检测基线） */
    private var last: DeepSpaceNebula.Result? = null

    /** 滑移窗起点 Result（null=无窗；窗起点=换代前一代） */
    private var from: DeepSpaceNebula.Result? = null

    /** 窗起点时刻（0=无窗） */
    private var startMs = 0L

    /** 数据换代检测（真变=格键身份集 diff 才启动 2s 窗+探针；同身份=零行为） */
    fun onGenerationR385(cpx: List<DeepSpaceNebula.Complex>, clouds: List<DeepSpaceNebula.EnrichCloud>) {
        val prev = last
        last = DeepSpaceNebula.Result(cpx, clouds, 0f)
        if (prev == null) return // 首装=基线无 diff 无滑移
        val prevKeys = prev.complexes.mapTo(HashSet()) { it.cellKey }
        val curKeys = cpx.mapTo(HashSet()) { it.cellKey }
        val added = curKeys - prevKeys
        val gone = prevKeys - curKeys
        val prevEKeys = prev.enrichClouds.mapTo(HashSet()) { it.cellKey }
        val curEKeys = clouds.mapTo(HashSet()) { it.cellKey }
        val addedE = curEKeys - prevEKeys
        val goneE = prevEKeys - curEKeys
        if (added.isEmpty() && gone.isEmpty() && addedE.isEmpty() && goneE.isEmpty()) return
        from = prev
        startMs = System.currentTimeMillis()
        android.util.Log.i(
            "TasteMapX",
            "R385 复合体diff: 摇篮 ${prev.complexes.size}→${cpx.size}" +
                "（新增=${added.size} 消散=${gone.size}）富集 ${prev.enrichClouds.size}→${clouds.size}" +
                "（新增=${addedE.size} 消散=${goneE.size}）——2s 平衡位移滑移启动",
        )
    }

    /**
     * 帧组装滑移求值（窗内=[DeepSpaceNebula.slideOf] 滑移态；**null=恒等直通**
     * ——调用方直用 drifted 原值零分配；t≥1 自动关窗后恒等）。cradleMass 不参与
     * （渲染零消费——探针属装配期 mapDeepSpaceR373 账）。
     */
    fun frameOfR385(
        cpx: List<DeepSpaceNebula.Complex>,
        clouds: List<DeepSpaceNebula.EnrichCloud>,
    ): DeepSpaceNebula.Result? {
        val f = from ?: return null
        if (startMs == 0L) return null
        val t01 = (System.currentTimeMillis() - startMs).toFloat() / DeepSpaceNebula.SLIDE_MS
        if (t01 >= 1f) {
            from = null
            startMs = 0L
            return null
        }
        return DeepSpaceNebula.slideOf(cpx, clouds, f, t01)
    }
}

@Volatile
private var r373DriftCache: R373DriftCache? = null

/**
 * R373 复合体+富集云漂移摇曳（镜像 [driftNebulaeR282]——design-nebula §5.3
 * 运动纪律：**场冻结（构造性零旋转）+摇曳=既有 drift 亚感知**）：对每团质心
 * 施 [UniverseDirector.applyDrift]（受控缓慢形变、振荡不漂走=复合体永不远离
 * 成员星域；零写入星表=物理保形）。量化缓存保帧路径零分配（240ms 量子外
 * 命中恒缓存）。
 */
private fun driftDeepSpaceR373(
    complexes: List<DeepSpaceNebula.Complex>,
    clouds: List<DeepSpaceNebula.EnrichCloud>,
    director: UniverseDirector.State,
): R373DriftCache {
    val phase = UniverseDirector.driftTurnsOf(director)
    val q = (phase / R282_DRIFT_QUANT).toInt()
    val cached = r373DriftCache
    if (cached != null && cached.q == q && cached.cpxSrc === complexes && cached.cloudSrc === clouds) {
        return cached
    }
    val tmp = FloatArray(3)
    val outC = ArrayList<DeepSpaceNebula.Complex>(complexes.size)
    for (i in complexes.indices) {
        val c = complexes[i]
        UniverseDirector.applyDrift(c.cx, c.cy, c.cz, i, phase, r282DirectorCfg, tmp)
        outC += DeepSpaceNebula.Complex(
            tmp[0], tmp[1], tmp[2], c.radius, c.mass, c.axisRad, c.cellKey, c.haMix01, c.ion01, c.members,
        )
    }
    val outE = ArrayList<DeepSpaceNebula.EnrichCloud>(clouds.size)
    for (i in clouds.indices) {
        val e = clouds[i]
        UniverseDirector.applyDrift(e.cx, e.cy, e.cz, i, phase, r282DirectorCfg, tmp)
        outE += DeepSpaceNebula.EnrichCloud(tmp[0], tmp[1], tmp[2], e.radius, e.colorArgb, e.cellKey, e.members)
    }
    val fresh = R373DriftCache(q, complexes, clouds, outC, outE)
    r373DriftCache = fresh
    return fresh
}

/**
 * R282 导演器场景排程体（LaunchedEffect(scene) 薄调用——大方法瘦身纪律）：
 * 账本热身（跨进程重启节奏记忆——重进 App 不被开场排程再炸一轮）→ 装配档案
 * 筛演出候选（超新星=分类器判据族演出级门/新星=从死到活复活）→ 哈希散布排程。
 */
private suspend fun directorSceneSetupR282(
    ctx: GalaxyLogicCtx,
    director: UniverseDirector.State,
    eventsFile: File,
) {
    val d = ctx.mapData.value ?: return
    if (d.stars.isEmpty()) return
    val nowMs = System.currentTimeMillis()
    val entries = withContext(Dispatchers.IO) {
        runCatching { UniverseDirector.loadJournal(eventsFile) }.getOrDefault(emptyList())
    }
    synchronized(r282Journal) {
        if (r282Journal.isEmpty()) r282Journal.addAll(entries)
    }
    UniverseDirector.warmUp(director, entries, nowMs, r282DirectorCfg)
    val cands = UniverseDirector.sceneCandidates(
        d.stars.map { UniverseDirector.StarSig(it.chatId, it.kind, it.recent7, it.prev7) },
    )
    UniverseDirector.scheduleSceneEvents(director, nowMs, r282DirectorCfg, cands)
    android.util.Log.i(
        "TasteMapX",
        "R282 导演装配: 候选=${cands.size}（超新星=${cands.count { it.kind == UniverseDirector.EventKind.SUPERNOVA }} " +
            "新星=${cands.count { it.kind == UniverseDirector.EventKind.NOVA }}）热身账=${entries.size} " +
            "排程=${r282DirectorCfg.startDelayMs / 1000}s 起 ${r282DirectorCfg.spreadWindowMs / 1000}s 窗",
    )
}

/**
 * R282 导演器帧节拍（帧循环 engine.step 前调用——主线程）：tick（漂移积分+
 * 排程过闸）→ 指令执行（FxEngine 翻译层）→ 账本节流落盘 → 流星节奏档 →
 * 分钟探针（60s 一条节律账——「不轰炸」的可证伪口径）。
 */
private fun directorTickR282(
    ctx: GalaxyLogicCtx,
    director: UniverseDirector.State,
    eventsFile: File,
    probeAnchor: LongArray,
    wellMaxVol: Int,
    /** R385 所在复合体判定原料（装配派生复合体快照——新星起飞点接线直供；
     *  帧循环主线程单写读，R367 inWellIds 同纪律） */
    complexesR385: List<DeepSpaceNebula.Complex>,
) {
    val nowMs = System.currentTimeMillis()
    r282DirectivesBuf.clear()
    UniverseDirector.tick(director, nowMs, r282DirectorCfg, r282DirectivesBuf)
    if (r282DirectivesBuf.isNotEmpty()) {
        for (dir in r282DirectivesBuf) {
            executeDirectiveR282(ctx, director, dir, nowMs, complexesR385)
            // R302 目击记账（只读指令流——导演器零改动）：发火瞬间判相机邻域
            awardWitnessR302(ctx, dir.kind, dir.chatId, nowMs, wellMaxVol)
        }
        synchronized(r282Journal) {
            for (dir in r282DirectivesBuf) {
                UniverseDirector.appendEntry(
                    r282Journal,
                    UniverseDirector.Entry(nowMs, dir.kind, dir.chatId, dir.seed, (dir.intensity01 * 1000f).toInt()),
                )
            }
        }
        if (nowMs - r282JournalSavedMs >= 5_000L) {
            r282JournalSavedMs = nowMs
            val snapshot = synchronized(r282Journal) { ArrayList(r282Journal) }
            ctx.scope.launch {
                withContext(Dispatchers.IO) { UniverseDirector.saveJournal(eventsFile, snapshot) }
            }
        }
    }
    // 流星节奏档统管（「用户观看=流星轨迹」的密度——事件演出期减速让戏）
    ctx.engine.meteorPaceScale = UniverseDirector.meteorPaceOf(director, nowMs, r282DirectorCfg)
    // 分钟探针（节律账：发出/闸拒/并发/漂移——TasteMapX 旁路 tag 沿 R231 先例）
    if (probeAnchor[0] == 0L) probeAnchor[0] = nowMs
    if (nowMs - probeAnchor[0] >= 60_000L) {
        probeAnchor[0] = nowMs
        android.util.Log.i(
            "TasteMapX",
            "R282 导演: 分钟账 发出=彗${director.emitted[0]}/新${director.emitted[1]}/超${director.emitted[2]} " +
                "闸拒=[间隔${director.rejected[0]} 类帽${director.rejected[1]} 全局帽${director.rejected[2]} " +
                "并发${director.rejected[3]} 星冷却${director.rejected[4]} 死线${director.rejected[5]}] " +
                "并发=${UniverseDirector.concurrentCount(director, nowMs)} " +
                "漂移=${"%.1f".format(UniverseDirector.driftTurnsOf(director) * 360.0)}° " +
                "流星速=${"%.2f".format(ctx.engine.meteorPaceScale)}",
        )
    }
}

/**
 * R282 指令执行器（哑执行——视觉参数全由导演器组曲定）：彗星=spawnComet
 * （种子轨迹叙事）；新星/超新星=flashStar（世界锚——看向即见）+ 投影位
 * burst（星在屏内才撒粒子——投影走 N 体当帧渲染位=「点=所见」口径）。
 * R385 P4（design-nebula §3.3-1）：NOVA 档新增起飞层——星真在复合体云内时
 * [novaTakeoffR385] 聚尘演出（「星云的尘埃聚成了它」的真位置）；R235
 * 发现感渐显衔接原样零接触。
 */
private fun executeDirectiveR282(
    ctx: GalaxyLogicCtx,
    director: UniverseDirector.State,
    d: UniverseDirector.Directive,
    nowMs: Long,
    /** R385 所在复合体判定原料（装配派生快照直供——主线程单写读） */
    complexesR385: List<DeepSpaceNebula.Complex>,
) {
    when (d.kind) {
        UniverseDirector.EventKind.COMET -> ctx.engine.spawnComet(d.seed, d.cometLifeSec)
        else -> {
            ctx.engine.flashStar(d.chatId, d.flashTtlSec)
            // R323 超新星显式账（循环联动口——TTL 与 GRAVITY_WAVE 档衰减区间重叠
            // 种别不可反推，SUPERNOVA 档独立喂账：发射星云星胚点脉冲消费）
            if (d.kind == UniverseDirector.EventKind.SUPERNOVA) {
                ctx.engine.supernovaFlash(d.chatId, d.flashTtlSec)
            }
            val cv = ctx.canvasSize.value
            val cam = ctx.cam.value
            if (cv.width > 0 && cv.height > 0) {
                val has = ctx.physics.value?.posOfChat(d.chatId, ctx.pollPos) == true
                if (has) {
                    val focal = TasteSpace.focalOf(
                        cv.width.toFloat().coerceAtLeast(1f),
                        cv.height.toFloat().coerceAtLeast(1f),
                    ) * ctx.lens.value
                    if (TasteSpace.project(
                            ctx.pollPos[0], ctx.pollPos[1], ctx.pollPos[2], cam, focal,
                            cv.width / 2f, cv.height / 2f, r282Proj,
                        )
                    ) {
                        ctx.engine.burst(
                            r282Proj[0], r282Proj[1], d.burstParticles, d.burstSpeedPx,
                            d.seed, d.burstLifeSec, d.burstSizePx,
                        )
                        // R385 新星起飞层（§3.3-1 起飞点=所在复合体：星在屏且在云内
                        // 才聚尘——burst 老演出原样叠放不替换；SUPERNOVA 档不起飞=
                        // 爆发是死亡非诞生，语义只归 enrich 半边）
                        if (d.kind == UniverseDirector.EventKind.NOVA && complexesR385.isNotEmpty()) {
                            novaTakeoffR385(ctx, complexesR385, d, cv, cam, focal)
                        }
                    }
                }
            }
        }
    }
    // 事件探针（脱敏=尾号制；并发=节奏闸同口径活窗数）
    val tail = if (d.chatId != 0L) " 尾号${(d.chatId % 10000L + 10000L) % 10000L}" else ""
    android.util.Log.i(
        "TasteMapX",
        "R282 事件: ${d.kind.label}$tail 强度=${"%.2f".format(d.intensity01)} " +
            "并发=${UniverseDirector.concurrentCount(director, nowMs)} 粒子=${d.burstParticles} 彗寿=${"%.1f".format(d.cometLifeSec)}s",
    )
}

/**
 * R385 新星起飞（design-nebula §3.3-1「新星诞生演出起飞点=所在复合体——
 * toast『星云的尘埃聚成了它』的真位置」的接线体）：容纳判定走
 * [DeepSpaceNebula.complexIndexOf]（世界位距质心<radius 的最近团；-1=不在
 * 任何云内）→在云内=云尘自复合体屏域环带（半径=云心投影视半径 [ringPx]，
 * [TasteNebula.fogPx] 屏位账同口径）向星屏位汇聚（[TasteFlight.FxEngine.
 * novaGatherR385]——与 burst 共用爆散账同一池，粒子数/种子与 burst 同源=
 * 同一次演出同量级同账本回放径）；不在云内=零起飞老演出原样（老星复活非
 * 云生——诚实缺省非缺陷）。探针行取证件（diff 非静默——宣判体系纪律）；
 * R235 发现感渐显（飞近渐亮）衔接原样零接触。
 */
private fun novaTakeoffR385(
    ctx: GalaxyLogicCtx,
    complexes: List<DeepSpaceNebula.Complex>,
    d: UniverseDirector.Directive,
    cv: IntSize,
    cam: SpaceCam,
    focal: Float,
) {
    val tail = (d.chatId % 10000L + 10000L) % 10000L
    val ci = DeepSpaceNebula.complexIndexOf(
        ctx.pollPos[0], ctx.pollPos[1], ctx.pollPos[2], complexes,
    )
    if (ci < 0) {
        android.util.Log.i("TasteMapX", "R385 新星起飞: 云外复活 尾号${tail}（零起飞——老演出原样）")
        return
    }
    val c = complexes[ci]
    if (TasteSpace.project(c.cx, c.cy, c.cz, cam, focal, cv.width / 2f, cv.height / 2f, r385CpxProj)) {
        val ringPx = TasteNebula.fogPx(c.radius, r385CpxProj[3])
        ctx.engine.novaGatherR385(
            r282Proj[0], r282Proj[1], ringPx, d.burstParticles, d.seed,
        )
        android.util.Log.i(
            "TasteMapX",
            "R385 新星起飞: 云内聚尘 尾号${tail}（复合体#${ci} 团数${complexes.size} " +
                "环域${"%.0f".format(ringPx)}px 粒子=${d.burstParticles}）",
        )
    }
}

/** R206 彗星爬虫监视体（R254 自 composable 抽出——语句原样搬移零行为变化） */
private suspend fun cometWatchR254(ctx: GalaxyLogicCtx, scene: FlightScene) {    val dbFile = File(File(ctx.app.filesDir, "deepindex"), "search.db")
    if (!dbFile.isFile) return
    var lastRow = -1L
    var lastRefsRow = -1L // R266 v11：refs 游标（纯转发流量补盲）
    while (true) {
        delay(COMET_POLL_MS)
        val res = withContext(Dispatchers.IO) {
            runCatching { pollDeepIndexCrawl(dbFile, lastRow, lastRefsRow) }.getOrNull()
        } ?: continue // 库被爬虫写事务短暂占锁等：本轮跳过，下轮再试
        val grew = (lastRow >= 0 && res.maxRowId > lastRow) ||
            (lastRefsRow >= 0 && res.refsMaxRowId > lastRefsRow)
        if (grew) {
            ctx.engine.spawnComet((res.maxRowId % 100_000L).toInt())
            var onMapFlash = 0
            for (chatId in res.newChats) {
                if (chatId in scene.onMapIds) {
                    ctx.engine.flashStar(chatId)
                    onMapFlash++
                }
            }
            android.util.Log.i(
                "TasteMap",
                "R206 彗星: 索引行 $lastRow→${res.maxRowId} refs $lastRefsRow→${res.refsMaxRowId}" +
                    " 新频道=${res.newChats.size} 在图爆闪=$onMapFlash",
            )
        }
        lastRow = res.maxRowId
        lastRefsRow = res.refsMaxRowId
    }
}

/** R206 在播频道灯塔体（R254 自 composable 抽出——语句原样搬移零行为变化） */
private suspend fun playingLighthouseR254(ctx: GalaxyLogicCtx) {
    while (true) {
        delay(PLAYING_POLL_MS)
        val cur = ctx.vm.items.value.getOrNull(ctx.vm.currentIndex.value)
        val audio = ctx.vm.nowPlayingAudio.value
        ctx.playingChatId.value = (audio ?: cur)?.chatId ?: 0L
    }
}

/**
 * R254 三段式装载体（**顶层挂起函数**——LaunchedEffect 内只留薄调用，composable
 * 字节码不随装载逻辑膨胀：真机「Method too large」编译墙实锤后收口）：
 *  1. **缓存秒显**：taste_map_cache_v1.bin 读回成品 TasteMapData 直接装填（毫秒级
 *     ——用户立刻看到星点）；demo/perturb 口径不符=null 走全量。
 *  2. **全量重建照跑**（首开=可见阶段文案 setPhase；缓存命中=后台静默——用户已在
 *     看星图），重建含装配+MDS 布局全链（与旧版同一条链零变化）。
 *  3. **落盘+换装门**：重建成品原子写缓存（空结果不写——防失败态钉死）；缓存
 *     在场时 ±5% 频道数门：门内=沿用缓存视图（星图零跳动）只更新缓存文件，
 *     门外=换装 setMap(fresh)（数据已失真到必须换）。写后回读自检（data class
 *     深等+账本计数——序列化 round-trip 事件级证据）。
 */
private suspend fun runGalaxyLoadR254(
    gateway: TdGateway,
    vm: FeedViewModel,
    app: android.app.Application,
    setMap: (TasteMapData?) -> Unit,
    setPhase: (String?) -> Unit,
    // R328 批3 跃迁入队：导演器状态（GRAVITY_WAVE 诞生档投递位——null=旧调用面
    // 零行为防御；入队逻辑全在 [reportBirthTransitionsR325] 顶层件，本参只投递句柄）
    director: UniverseDirector.State? = null,
) {
    val cacheFile = tasteMapCacheFile(app)
    val demo = gateway !is TdLibGateway
    val perturb = vm.galaxyLinkPerturb
    // R303c P2：涌现布局 AB 开关（AppPrefs tasteEmergentLayout 默认开=用户亲批
    // 主线换轨；关=V3 经典降级位，拍板项⑦）——翻转后下次进图生效（与
    // tasteLinkPerturb 同口径：布局进页一次算定）
    val emergent = vm.galaxyEmergentLayout
    val tCache = android.os.SystemClock.elapsedRealtime()
    val cached = withContext(Dispatchers.IO) {
        runCatching { readTasteMapCache(cacheFile, demo, perturb, emergent) }.getOrNull()
    }
    if (cached != null && cached.stars.isNotEmpty()) {
        setMap(cached)
        android.util.Log.i(
            "TasteMap",
            "R254 缓存装填: ${android.os.SystemClock.elapsedRealtime() - tCache}ms " +
                "天体=${cached.stars.size} 连线=${cached.links.size}（后台静默重建中）",
        )
    } else {
        setPhase("装配全宇宙数据…")
    }
    // ---- R267 数据源退化重试环（塌方修复②：批迁/迁移活跃期装配读到中间态——
    //      「宁可显示正在组装重试，也不产出退化布局」；≤3 次×15s，耗尽后仍退化=
    //      哈希回退布局兜底可看可飞，但绝不落盘） ----
    // R303c P2 温启：缓存=涌现位成品（头口径已过闸）时，其 sp 坐标=上次平衡位
    //      ——作为本次定形初值（§6.6 增量演化「认熟的星座不搬」；V3 初值只给
    //      冷启/无缓存星）。布局退化/源退化的缓存走全量（健康闸自检已弃用）。
    val warmStart: Map<Long, FloatArray>? = if (cached != null && cached.emergent && emergent) {
        val w = HashMap<Long, FloatArray>(cached.stars.size * 2)
        for (s in cached.stars) w[s.chatId] = floatArrayOf(s.spX, s.spY, s.spZ)
        w
    } else null
    var fresh: TasteMapData
    var assembly: MapAssembly? = null // R317b：宣判协程要消费白洞证据（装配产物）
    var asmAttempt = 0
    while (true) {
        asmAttempt++
        fresh = withContext(Dispatchers.IO) {
            val a = assembleTasteGalaxy(gateway, vm) { setPhase(it) }
            assembly = a
            withContext(Dispatchers.Default) {
                layoutTasteMap(a, perturb = perturb, emergent = emergent, warm = warmStart, onPhase = { setPhase(it) })
            }
        }
        if ((fresh.sourceDegenerate || fresh.layoutDegraded) && asmAttempt < 3) {
            android.util.Log.w(
                "TasteMap",
                "R267 重试: 第 $asmAttempt 次布局退化（源=${fresh.sourceDegenerate} 方位=${fresh.layoutDegraded}）" +
                    "——15s 后重装配等一致快照",
            )
            setPhase("索引数据迁移中——等待一致快照后重组（$asmAttempt/3）…")
            kotlinx.coroutines.delay(15_000L)
            continue
        }
        break
    }
    // 重建成品原子落盘（空结果不写——防装配失败态被钉死成永久缓存；
    // R267：退化品同样**绝不写盘**——竖线塌缩布局钉进缓存=每次开图都塌）
    if (fresh.stars.isNotEmpty() && !fresh.layoutDegraded && !fresh.sourceDegenerate) {
        withContext(Dispatchers.IO) {
            val ok = runCatching { writeTasteMapCache(cacheFile, fresh, demo, perturb) }.getOrDefault(false)
            if (!ok) {
                android.util.Log.w("TasteMap", "R254 缓存写盘失败")
            } else {
                // 回读自检（星/链 data class 深等 + 账本容器计数核对——序列化
                // round-trip 的事件级证据；纯本地毫秒级，不进关键路径热区）
                runCatching {
                    val back = readTasteMapCache(cacheFile, demo, perturb, emergent) ?: return@runCatching
                    val deep = back.stars == fresh.stars && back.links == fresh.links &&
                        back.todayTrail == fresh.todayTrail && back.legacyPosterIds == fresh.legacyPosterIds &&
                        back.kindCounts == fresh.kindCounts && back.zSpan == fresh.zSpan &&
                        back.gridHalf == fresh.gridHalf && back.gridZ == fresh.gridZ &&
                        back.totalWatchSec == fresh.totalWatchSec && back.meanItems == fresh.meanItems &&
                        back.emergent == fresh.emergent && back.verdictApplied == fresh.verdictApplied
                    val shape = back.todayStops.size == fresh.todayStops.size &&
                        back.neighborArcs.size == fresh.neighborArcs.size &&
                        back.binaryPairs.size == fresh.binaryPairs.size &&
                        back.neighborRels.size == fresh.neighborRels.size
                    android.util.Log.i(
                        "TasteMap",
                        "R254 缓存回读自检: ${if (deep && shape) "一致" else "不一致！深等=$deep 形状=$shape"} " +
                            "星=${back.stars.size}/${fresh.stars.size} 链=${back.links.size}/${fresh.links.size}",
                    )
                }
            }
        }
    }
    var swapped = false // R317b：显示图是否=fresh（宣判换装比对基准）
    if (cached == null || cached.stars.isEmpty()) {
        setMap(fresh)
        swapped = true
    } else {
        val n0 = cached.stars.size
        val n1 = fresh.stars.size
        // R267 换装门扩维（塌方修复③「不再只看频道数 ±5%」）：基因指纹漂移（对数桶/
        // 频道数双口径）同样换装——基因组规模翻倍级变化或基因频道集合显著变化时，
        // 沿用旧视图=给失真数据续命
        val genomeSwap = LayoutHealth.genomeDrifted(cached.genomeFp, fresh.genomeFp)
        val swap = n0 > 0 && (kotlin.math.abs(n1 - n0) > (n0 * 0.05f) || genomeSwap)
        // R267：退化新布局**绝不换装**——缓存里的健康视图继续顶住（宁可陈不可塌）
        if (swap && !fresh.layoutDegraded && !fresh.sourceDegenerate) {
            setMap(fresh)
            swapped = true
        }
        android.util.Log.i(
            "TasteMap",
            "R254 重建完成: 天体=$n1 换装=${if (swap) "是" else "否"}" +
                "（频道数门=${n0 > 0 && kotlin.math.abs(n1 - n0) > (n0 * 0.05f)} 基因指纹门=$genomeSwap" +
                "（${cached.genomeFp}→${fresh.genomeFp}）" +
                "${if (fresh.layoutDegraded || fresh.sourceDegenerate) " 新布局退化=拦装拦写" else ""}）",
        )
    }
    setPhase(null)

    // ---- R317b 异步后置宣判（方案①——架构师 09:20 裁定）：装配+布局+缓存写盘+
    //      换装判定之后的**旁路段**（仍在进页 LaunchedEffect 协程内=离页即取消；
    //      宣判未完成=缓存 verdictApplied=false，下次进页重跑，语义自洽）。
    //      R325/R327 合流：管线收口独立函数 [runLifeVerdictR325]（终态半场=R317b
    //      内核收编语句原样+诞生半场 [runBirthVerdictR325]——装配走独立方法铁令；
    //      判据族纯函数在 core，本函数族只做池选→点查→宣判→迁移的编排与探针；
    //      同一 IO 协程同一取消路径：缓存单次换代 v5/verdictApplied 单旗覆盖两侧/
    //      互斥一处状态机（migrateLifeVerdictsR325）/旁路一行调用）。
    //      换装走既有**离屏构建+原子换装**模式：宣判产出新 TasteMapData → 与当前
    //      显示图深等比对 → 有变化才 setMap（宣判演出=新星体登场的唯一时刻——
    //      诞生注记登场与终态宣判**同窗不双演**；无变化不扰用户已开的信息卡/选中
    //      态）→ 无条件重写缓存（verdictApplied=true=下次进页零 IO 复用，两侧
    //      一起复用）。缓存复用门=无换装（±5%+基因指纹门内）∧缓存已宣判 →
    //      [applyCachedVerdictR317b] 零 IO 对位拷贝（防每进页重跑 ~秒级×≤30
    //      点查——r317 实测账：截断池 ~46s 最坏全在异步段消化，同步路径零增量）。
    //      退化品（R267 重试耗尽兜底）不宣判不落盘——六态同步档可看可飞。 ----
    if (fresh.stars.isNotEmpty() && !fresh.layoutDegraded && !fresh.sourceDegenerate) {
        val displayed = if (swapped) fresh else cached
        val reusable = !swapped && cached != null && cached.stars.isNotEmpty() && cached.verdictApplied
        val verdictMap: TasteMapData
        if (reusable && cached != null) {
            verdictMap = applyCachedVerdictR317b(fresh, cached)
        } else {
            verdictMap = runLifeVerdictR325(gateway, vm, fresh, assembly)
            // ---- R325 跃迁 diff 三通道②③（跨会话 stage diff 窗——r325 B①-2）：
            //      全量宣判窗（换装门触发/未宣判重查/v5 首进直拒 v4）对位比对
            //      缓存 birth vs 宣判 birth → 跃迁清单；v5 首进（缓存直拒=null）
            //      =全员随原子换装登场一次（同窗不双演，零跃迁清单=不噪声）。
            //      复用门命中窗 birth 冻结=零 diff 零 IO（r325 论证 2 的代价面）。
            //      **批 3 入队口已接**（R320 套回后 R328 兑现）：跃迁清单喂
            //      [reportBirthTransitionsR325]（函数体=GRAVITY_WAVE 诞生档入队+
            //      探针行；planetsForming 位不排导演器——贴脸演出归批 C） ----
            if (cached != null && cached.stars.isNotEmpty()) {
                reportBirthTransitionsR325(
                    diffBirthTransitionsR325(cached.stars, verdictMap.stars),
                    director,
                    System.currentTimeMillis(),
                )
            }
        }
        // 原子换装：kinds/hist/宣判档产生了真实变化才换（data class 深等=与显示图
        // 逐星对位比对；直方图注入/降档/新星体都算变化——信息卡前史行随之生效）
        if (displayed == null || verdictMap.stars != displayed.stars ||
            verdictMap.kindCounts != displayed.kindCounts
        ) {
            setMap(verdictMap)
        }
        // 宣判成品无条件落缓存（覆盖装配期预写盘；verdictApplied=true 进 v4 载荷）
        withContext(Dispatchers.IO) {
            val ok = runCatching { writeTasteMapCache(cacheFile, verdictMap, demo, perturb) }.getOrDefault(false)
            if (!ok) {
                android.util.Log.w("TasteMap", "R317b 宣判缓存写盘失败（下次进页重查——无害降级）")
            }
        }
    }
}

/**
 * R327 生命宣判合流迁移件（**纯函数——[runLifeVerdictR325] 的可单测核心**；
 * r325 B①-3「互斥需要全局视野」：迁移状态机在一处看全两侧输出）：
 * 终态半场语句原样收编（R317b migrateTerminalVerdictsR317b 语义零改——函数
 * 已被本件收编退役）+
 * 诞生注记后置层。宣判矩阵出口 → 星表 kinds 迁移+hist 注入+kindCounts 扩容
 * → birth 注记回填。
 *  - verdict.kind 非空 → 覆写 kind（黑洞/中子星/夸克星——「大而坍缩非大而晋」的
 *    最终语义；7.5 段 R249 同步晋升的黑洞被宣判确认或降档）；
 *  - 静默巨兽（SILENT_GIANT：三重门过但无爆发史/缓熄未骤停/未在盛宴）→ 六态矩阵
 *    [TasteGalaxyClassifier.classifyChannel] 重算回落（「大而未坍缩=还是巨星」）；
 *  - 超帽（CAPPED）/未入池 → kind 原样（同步路径本就未晋——行为等价）；
 *  - hist（发布直方图特征）→ 注入池内每颗有特征的星（信息卡前史行原料）；
 *  - 白洞（[TasteAstroPhysics.judgeWhiteHoles]）→ kind=WHITE_HOLE 只落在**无终态
 *    宣判**的频道上（终态物理身份（黑洞/中子/夸克）优先于收割关系——同名不并）；
 *  - **R327 诞生注记后置**（birthById 查表回填 StarNode.birth）：互斥第二道=
 *    [TasteAstroPhysics.isDeathSideKind] 单口在**终态迁移后的最终 kind** 上剔除
 *    （judgeBirthStates 已在 fresh kind 判过第一道；终态宣判改写 kind 的星零注记
 *    ——死透的星不坐月子；互斥规则单点，纪律§一）。kindCounts 不动（诞生无
 *    kind——注记不改 kind，计数与终态态同值）。
 */
private fun migrateLifeVerdictsR325(
    stars: List<StarNode>,
    verdictById: Map<Long, TasteAstroPhysics.TerminalVerdict>,
    featuresByChat: Map<Long, TasteAstroPhysics.PublishFeatures>,
    whiteholeIds: Set<Long>,
    birthById: Map<Long, TasteAstroPhysics.BirthFeatures>,
    volumeRankOf: (StarNode) -> Float,
    nowMs: Long,
): Pair<List<StarNode>, Map<CelestialKind, Int>> {
    var silentDemoted = 0
    val migrated = stars.map { s ->
        val v = verdictById[s.chatId]
        val hist = featuresByChat[s.chatId]
        if (v == null) {
            s // 未入池：零点查零宣判（kind/hist 原样——诚实：池外频道不冒充终态）
        } else when {
            v.kind != null -> s.copy(kind = v.kind!!, hist = hist)
            v.reason == TasteAstroPhysics.TerminalReason.SILENT_GIANT -> {
                // 降档：同步 R249 晋升的黑洞被宣判回落（大而未坍缩=还是巨星）
                if (s.kind == CelestialKind.BLACK_HOLE) silentDemoted++
                s.copy(
                    kind = TasteGalaxyClassifier.classifyChannel(
                        TasteGalaxyClassifier.ChannelEvidence(
                            items = s.itemsCount,
                            lastActivitySec = s.lastActivitySec,
                            recent7 = s.recent7,
                            prev7 = s.prev7,
                            firstSeenMs = s.firstSeenMs,
                            volumeRank01 = volumeRankOf(s),
                        ),
                        nowMs,
                    ),
                    hist = hist,
                )
            }
            else -> s.copy(hist = hist) // CAPPED/NOT_TERMINAL：kind 原样（同步未晋）
        }
    }
    // 白洞层：只落在无终态宣判 kind 的频道（终态物理身份优先于收割关系）
    val withWhiteholes = migrated.map { s ->
        if (s.chatId in whiteholeIds && s.kind != CelestialKind.BLACK_HOLE &&
            s.kind != CelestialKind.NEUTRON_STAR && s.kind != CelestialKind.QUARK_STAR
        ) {
            s.copy(kind = CelestialKind.WHITE_HOLE)
        } else {
            s
        }
    }
    // ---- R327 诞生注记后置（终态>诞生互斥第二道——isDeathSideKind 单口在最终
    //      kind 上剔除；birthById 只含非 NOT_BIRTH 注记（runBirthVerdictR325 成表
    //      口径），缺位=未宣判/缺据/半场失败=零注记零行为） ----
    val withBirth = withWhiteholes.map { s ->
        val b = birthById[s.chatId]
        if (b == null || TasteAstroPhysics.isDeathSideKind(s.kind)) s else s.copy(birth = b)
    }
    val kindCounts = HashMap<CelestialKind, Int>()
    for (s in withBirth) kindCounts.merge(s.kind, 1, Int::plus)
    if (silentDemoted > 0) {
        android.util.Log.i("TasteMap", "R317b 静默巨兽降档: $silentDemoted 颗（大而未坍缩=回落六态）")
    }
    return withBirth to kindCounts
}

/**
 * R327 生命宣判合流管线（**异步后置**——r325 B 章方案①合流落形；R317b 终态
 * 半场内核**语句原样收编**零语义变化 + 诞生半场 [runBirthVerdictR325]）：
 *  - **终态半场**：池选（[TasteAstroPhysics.terminalPoolOf] ≤30）→ 逐颗
 *    [DeepSearchIndex.chatPublishHistogram] IO 点查 → [TasteAstroPhysics.publishHistogramFeatures]
 *    → [TasteAstroPhysics.judgeTerminalStates]/[judgeWhiteHoles]；
 *  - **诞生半场**：[runBirthVerdictR325]（[DeepSearchIndex.tiers] 批量读唯一新
 *    IO + [TasteAstroPhysics.judgeBirthStates] 全量判 + [TasteAstroPhysics.birthPoolOf]
 *    池选诞生史点查）；runCatching 包裹——失败=终态照常产出+诞生缺据注记零
 *    （诚实降级，r325 B①-5）；
 *  - **合流迁移** [migrateLifeVerdictsR325]：kinds 迁移+kindCounts 扩容+hist
 *    注入+诞生注记后置（isDeathSideKind 最终 kind 互斥单点）；
 *  - 返回宣判成品（verdictApplied=true 覆盖两半场——单旗语义）。
 * 同一 IO 协程同一取消路径（离页即断逐颗 isActive）；探针两行脱敏计数
 * （终态行原样+诞生行 r325 A⑤ 口径——零频道名，纪律§七）。
 * 全确定性：判据族纯函数零随机零系统钟（nowSec 由本函数一次性传入两半场）。
 */
private suspend fun runLifeVerdictR325(
    gateway: TdGateway,
    vm: FeedViewModel,
    fresh: TasteMapData,
    assembly: MapAssembly?,
): TasteMapData {
    val tV = android.os.SystemClock.elapsedRealtime()
    val nowSec = System.currentTimeMillis() / 1000L
    // ---- 池选：候选=频道星（实体天体（脉冲星/星团/流星）不判终态——判据族口径；
    //      rank01 与布局 7.5 段同一把尺子（全池 items 数组单源） ----
    val n = fresh.stars.size
    val volArr = IntArray(n) { fresh.stars[it].itemsCount }
    val candidates = ArrayList<TasteAstroPhysics.TerminalEvidence>(n)
    for (i in 0 until n) {
        val s = fresh.stars[i]
        val k = s.kind
        if (k == CelestialKind.PULSAR || k == CelestialKind.CLUSTER || k == CelestialKind.METEOR) continue
        candidates += TasteAstroPhysics.TerminalEvidence(
            chatId = s.chatId,
            items = s.itemsCount,
            volumeRank01 = TasteGalaxyClassifier.volumeRankOf(s.itemsCount, volArr),
            recent7 = s.recent7,
            histogram = null,
        )
    }
    val pool = TasteAstroPhysics.terminalPoolOf(candidates)
    // ---- 点查（IO 协程；缺据 null=诚实不宣判绝不冒充；逐颗可取消——离页即断） ----
    val featuresByChat = HashMap<Long, TasteAstroPhysics.PublishFeatures>(pool.size * 2)
    var queried = 0
    var missing = 0
    withContext(Dispatchers.IO) {
        for (c in pool) {
            if (!isActive) return@withContext // 离页取消（阻塞点查间隙让出）
            queried++
            val h = runCatching { vm.deepIndex.chatPublishHistogram(c.chatId) }.getOrNull()
            val f = h?.let { TasteAstroPhysics.publishHistogramFeatures(it.dayHistogram, nowSec) }
            if (f == null) {
                missing++
            } else {
                featuresByChat[c.chatId] = f
            }
        }
    }
    val pooled = pool.map { c ->
        TasteAstroPhysics.TerminalEvidence(c.chatId, c.items, c.volumeRank01, c.recent7, featuresByChat[c.chatId])
    }
    val verdicts = TasteAstroPhysics.judgeTerminalStates(pooled, fresh.meanItems)
    val verdictById = HashMap<Long, TasteAstroPhysics.TerminalVerdict>(verdicts.size * 2)
    for (v in verdicts) verdictById[v.chatId] = v
    // ---- 白洞（core 单口——证据=装配口账面在场口径收集） ----
    val whiteholeIds = HashSet<Long>()
    for (id in TasteAstroPhysics.judgeWhiteHoles(assembly?.whiteholeEvidence ?: emptyList())) whiteholeIds += id
    // ---- 诞生半场（R327——同旁路同 IO 协程族；失败=终态照常+诞生注记零） ----
    val birthById: Map<Long, TasteAstroPhysics.BirthFeatures> =
        runCatching { runBirthVerdictR325(vm, fresh, assembly, nowSec) }.getOrDefault(emptyMap())
    // ---- 漂移半场（R323 自行——第三半场同旁路同取消路径；失败=漂移零注记零行为） ----
    val driftById: Map<Long, ProperMotionFx.Drift> =
        runCatching { runDriftVerdictR323(vm, fresh, nowSec) }.getOrDefault(emptyMap())
    // ---- 合流迁移+扩容+成品 ----
    val rankOf = { s: StarNode -> TasteGalaxyClassifier.volumeRankOf(s.itemsCount, volArr) }
    val (stars, kindCounts) = migrateLifeVerdictsR325(
        fresh.stars, verdictById, featuresByChat, whiteholeIds, birthById, rankOf, nowSec * 1000L,
    )
    // ---- R323 漂移注记注入（纯注记：kind 不动 kindCounts 不动——池外/缺据/零漂移
    //      零注记；缓存 v7 载荷随星走） ----
    val starsWithDrift = if (driftById.isEmpty()) stars else stars.map { s ->
        driftById[s.chatId]?.let { s.copy(drift = it) } ?: s
    }
    // ---- 装配探针（脱敏计数——宣判 diff 的装机实锤行；V31 消费） ----
    run {
        var feast = 0
        var collapsed = 0
        var capped = 0
        var silent = 0
        for (v in verdicts) {
            when (v.reason) {
                TasteAstroPhysics.TerminalReason.BLACK_HOLE_FEASTING -> feast++
                TasteAstroPhysics.TerminalReason.BLACK_HOLE_COLLAPSED -> collapsed++
                TasteAstroPhysics.TerminalReason.BLACK_HOLE_CAPPED -> capped++
                TasteAstroPhysics.TerminalReason.SILENT_GIANT -> silent++
                else -> {}
            }
        }
        android.util.Log.i(
            "TasteMap",
            "R317b 终态宣判: 候选=${candidates.size} 池=${pool.size} 点查=$queried 缺据=$missing " +
                "黑洞=${kindCounts[CelestialKind.BLACK_HOLE] ?: 0}（盛宴=$feast 坍缩=$collapsed）" +
                " 中子=${kindCounts[CelestialKind.NEUTRON_STAR] ?: 0} 夸克=${kindCounts[CelestialKind.QUARK_STAR] ?: 0} " +
                "白洞=${kindCounts[CelestialKind.WHITE_HOLE] ?: 0} 静默=$silent 超帽=$capped " +
                "耗时=${android.os.SystemClock.elapsedRealtime() - tV}ms（异步段——同步装配路径零增量）",
        )
    }
    return fresh.copy(stars = starsWithDrift, kindCounts = kindCounts, verdictApplied = true)
}

/**
 * R327 诞生宣判半场（r325 B① 诞生半场落形——**全本地 O(1) 判据门+两段有界 IO**）：
 * [IO] [DeepSearchIndex.tiers] 批量读（毫秒级单查——诞生侧唯一新 IO；失败=空表
 * 全 LEGACY 诚实缺据）→ BirthEvidence 全量收集（fresh.stars+assembly.raws 的
 * goldItems/idxCount+tagFingerprints 账本+vm.indexTiering 开关——**装配口零改动
 * 零新 IO**）→ [TasteAstroPhysics.judgeBirthStates] 全量判 → [TasteAstroPhysics.birthPoolOf]
 * 池选（≤[TasteAstroPhysics.BIRTH_POOL_CAP] 首见新度降序）→ [IO] 池内
 * [DeepSearchIndex.chatPublishHistogram] 补诞生史（毫秒级×≤30 小频道——逐颗
 * isActive 取消）→ 非 NOT_BIRTH 注记成表（hist 池内回填；缺据 null 整行不出）。
 * 返回 chatId→[TasteAstroPhysics.BirthFeatures]（空表=全缺据/失败=零注记零行为）。
 * 探针行 r325 A⑤ 口径（脱敏计数——分档开/关透出=②态诚实约束可见）。
 */
private suspend fun runBirthVerdictR325(
    vm: FeedViewModel,
    fresh: TasteMapData,
    assembly: MapAssembly?,
    nowSec: Long,
): Map<Long, TasteAstroPhysics.BirthFeatures> {
    val tB = android.os.SystemClock.elapsedRealtime()
    // ---- tiers() 批量读（毫秒级单查；行缺失→-1 LEGACY 诚实缺据——judgeBirthStates
    //      经 effectiveTier 单口过闸：分档关恒 LEGACY ②不判，结构上不可绕） ----
    val tiersMap = withContext(Dispatchers.IO) {
        runCatching { vm.deepIndex.tiers() }.getOrDefault(emptyMap())
    }
    val tieringEnabled = vm.indexTiering.enabled
    // ---- BirthEvidence 全量收集（装配口零改动——raws/tagFingerprints 既有面） ----
    val rawById = HashMap<Long, MapStarRaw>((assembly?.raws?.size ?: 0) * 2)
    if (assembly != null) for (r in assembly.raws) rawById[r.chatId] = r
    val tagFp = assembly?.tagFingerprints ?: emptyMap()
    val n = fresh.stars.size
    val evidence = ArrayList<TasteAstroPhysics.BirthEvidence>(n)
    for (i in 0 until n) {
        val s = fresh.stars[i]
        val raw = rawById[s.chatId]
        evidence += TasteAstroPhysics.BirthEvidence(
            chatId = s.chatId,
            watchSec = s.watchSec,
            subscribed = s.subscribed,
            kind = s.kind,
            firstSeenMs = s.firstSeenMs,
            goldItems = raw?.goldItems ?: 0,
            persistedTier = tiersMap[s.chatId]?.persisted ?: -1,
            tieringEnabled = tieringEnabled,
            probeItems = raw?.idxCount ?: 0,
            ledgerTags = tagFp[s.chatId]?.size ?: 0,
        )
    }
    // ---- 全量判（门本地 O(1)——判据族 core 单口）+池选（先池选后点查） ----
    val judged = TasteAstroPhysics.judgeBirthStates(evidence)
    val pool = TasteAstroPhysics.birthPoolOf(judged)
    // ---- 池内诞生史点查（毫秒级×≤30 小频道；缺据 null=诞生史行整行不出） ----
    var birthQueried = 0
    val histById = HashMap<Long, TasteAstroPhysics.PublishFeatures>(pool.size * 2)
    withContext(Dispatchers.IO) {
        for (b in pool) {
            if (!isActive) return@withContext // 离页取消（同终态半场一条取消路径）
            birthQueried++
            val h = runCatching { vm.deepIndex.chatPublishHistogram(b.chatId) }.getOrNull()
            val f = h?.let { TasteAstroPhysics.publishHistogramFeatures(it.dayHistogram, nowSec) }
            if (f != null) histById[b.chatId] = f
        }
    }
    // ---- 注记成表（仅非 NOT_BIRTH——StarNode.birth=null=零行为缺省） ----
    val out = HashMap<Long, TasteAstroPhysics.BirthFeatures>(judged.size)
    for (b in judged) {
        if (b.stage == TasteAstroPhysics.BirthStage.NOT_BIRTH) continue
        out[b.chatId] = if (b.chatId in histById) b.copy(hist = histById[b.chatId]) else b
    }
    // ---- 装配探针（脱敏计数——r325 A⑤ 装配探针行；V35 消费） ----
    run {
        var acc = 0
        var neb = 0
        var young = 0
        var forming = 0
        for (b in out.values) {
            when (b.stage) {
                TasteAstroPhysics.BirthStage.ACCRETING -> acc++
                TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE -> neb++
                TasteAstroPhysics.BirthStage.PROTOSTAR_YOUNG -> young++
                else -> {}
            }
            if (b.planetsForming) forming++
        }
        android.util.Log.i(
            "TasteMap",
            "R325 诞生宣判: 候选=${evidence.size} 吸积=$acc 星云候选=$neb 年轻=$young " +
                "行星成型=$forming 池=${pool.size} 点查=$birthQueried 史回填=${histById.size} " +
                "耗时=${android.os.SystemClock.elapsedRealtime() - tB}ms" +
                "（分档=${if (tieringEnabled) "开" else "关"}——关=吸积态零=诚实缺据）",
        )
    }
    return out
}

/**
 * R323 自行漂移宣判半场（r315 J 章数据面——**BIRTH_POOL 同款有界池姿势**的第三
 * 半场，r325 B① 旁路同族：装配零改动+IO 协程逐颗 isActive 取消+失败诚实降级）：
 *  - **池选**：频道星（实体三档 PULSAR/CLUSTER/METEOR 排除——判据族口径）∧
 *    体量 ≥[ProperMotionFx.DRIFT_POOL_MIN_ITEMS]（两窗各 8 条的必要非充分门），
 *    体量降序+chatId 破平，帽 [ProperMotionFx.DRIFT_POOL_CAP]——池外零点查零
 *    漂移（诚实：池外不冒充，与终态/诞生宣判同哲学）；
 *  - **点查**：池内逐颗 [DeepSearchIndex.chatPublishMediaHistogram]（S3 直方图
 *    点查件的**两窗构成消费**——同族有界点查，成本 ∝ 频道条数同 chatPublish
 *    Histogram 账；缺据 null=不判绝不冒充）；
 *  - **宣判**：[ProperMotionFx.windowVotes] 两窗归桶 → [ProperMotionFx.driftOf]
 *    （缺据门 8 条/零漂移门 L1——「没动」也是结论=零注记）。
 * 探针行 r315 J⑤ 口径（脱敏——池/点查/缺据/零漂移/漂移计数+top3 漂移候选尾号）。
 */
private suspend fun runDriftVerdictR323(
    vm: FeedViewModel,
    fresh: TasteMapData,
    nowSec: Long,
): Map<Long, ProperMotionFx.Drift> {
    val tD = android.os.SystemClock.elapsedRealtime()
    // ---- 池选（体量降序+chatId 破平——确定性） ----
    val pool = ArrayList<StarNode>(ProperMotionFx.DRIFT_POOL_CAP)
    for (s in fresh.stars) {
        val k = s.kind
        if (k == CelestialKind.PULSAR || k == CelestialKind.CLUSTER || k == CelestialKind.METEOR) continue
        if (s.itemsCount < ProperMotionFx.DRIFT_POOL_MIN_ITEMS) continue
        pool += s
    }
    pool.sortWith(compareByDescending<StarNode> { it.itemsCount }.thenBy { it.chatId })
    if (pool.size > ProperMotionFx.DRIFT_POOL_CAP) pool.subList(ProperMotionFx.DRIFT_POOL_CAP, pool.size).clear()
    // ---- 池内两窗构成点查（IO 协程；缺据 null=诚实不判；逐颗可取消） ----
    var queried = 0
    var missing = 0
    var noDrift = 0
    val out = HashMap<Long, ProperMotionFx.Drift>(pool.size * 2)
    withContext(Dispatchers.IO) {
        for (s in pool) {
            if (!isActive) return@withContext // 离页取消（同终态/诞生半场一条取消路径）
            queried++
            val entries = runCatching { vm.deepIndex.chatPublishMediaHistogram(s.chatId) }.getOrNull()
            if (entries == null) {
                missing++
                continue
            }
            val (near, far) = ProperMotionFx.windowVotes(entries, nowSec)
            val d = ProperMotionFx.driftOf(near, far)
            if (d == null) {
                noDrift++ // 缺据（任一窗<8 条）或零漂移（差<门「没动」也是结论）
            } else {
                out[s.chatId] = d
            }
        }
    }
    // ---- 装配探针（r315 J⑤：top-3 漂移候选——脱敏只出尾号；V43 消费） ----
    run {
        val top3 = out.entries
            .sortedWith(compareByDescending<Map.Entry<Long, ProperMotionFx.Drift>> { it.value.strength01 }.thenBy { it.key })
            .take(3)
        val topStr = if (top3.isEmpty()) {
            "无"
        } else {
            top3.joinToString("/") { e ->
                val tail = (e.key % 10000L + 10000L) % 10000L
                val s = "%.2f".format(e.value.strength01)
                "尾号${tail}强度${s}"
            }
        }
        android.util.Log.i(
            "TasteMap",
            "R323 自行宣判: 池=${pool.size} 点查=$queried 缺据=$missing 零漂移=$noDrift " +
                "漂移=${out.size} top3=$topStr " +
                "耗时=${android.os.SystemClock.elapsedRealtime() - tD}ms（异步段——同步装配路径零增量）",
        )
    }
    return out
}

/**
 * R317b 宣判缓存复用件（零 IO——防每进页重查）：无换装门触发 ∧ 缓存已宣判时，
 * 按 chatId 对位拷贝缓存星的**宣判形**档（hist 非空 ∨ kind∈{黑洞,中子,夸克,白洞}
 * ——只有宣判能产出这四种形），六态同步档走 fresh 重算值（缓存不冻结六态）。
 * 新增星（门内 ±5%）无缓存对位=零宣判（六态原判诚实——下次全量宣判补）。
 * **R327 复用门扩展**（r325 B①-2 单旗两侧一起复用）：birth 注记非空=**独立冻结
 * 车道**（诞生注记冻结、六态照算——kind 仍走 fresh 重算不冻结；verdictApplied
 * 单旗覆盖两侧）；互斥门第二道=[TasteAstroPhysics.isDeathSideKind] 在 fresh kind
 * 上剔除（缓存注记不落到终态星上——死星不坐月子单点口径）。
 * **R323 复用门第三车道**：drift 注记=独立冻结（同 birth 模式——两窗构成差是
 * 慢变量，复用窗缓存直读零重查；verdictApplied 单旗一并覆盖三半场）。
 */
private fun applyCachedVerdictR317b(fresh: TasteMapData, cached: TasteMapData): TasteMapData {
    if (!cached.verdictApplied || fresh.stars.isEmpty() || cached.stars.isEmpty()) return fresh
    val cachedById = HashMap<Long, StarNode>(cached.stars.size * 2)
    for (s in cached.stars) cachedById[s.chatId] = s
    var copied = 0
    var birthCopied = 0
    var driftCopied = 0
    val stars = fresh.stars.map { s ->
        val c = cachedById[s.chatId] ?: return@map s
        val verdictShaped = c.hist != null || c.kind == CelestialKind.BLACK_HOLE ||
            c.kind == CelestialKind.NEUTRON_STAR || c.kind == CelestialKind.QUARK_STAR ||
            c.kind == CelestialKind.WHITE_HOLE
        // R323：drift 注记=**独立冻结车道**（同 birth 模式——两窗构成差是慢变量，
        // 复用窗冻结直读；fresh 侧此时尚无漂移（复用门=零 IO 跳过漂移半场））
        val driftCopiedFlag = c.drift != null && s.drift == null
        if (driftCopiedFlag) driftCopied++
        if (!verdictShaped) {
            // R327：六态档照 fresh 重算（kind 不冻结）；birth 注记非空=冻结（诞生
            // 注记冻结、六态照算——r325 复用门扩展口径；fresh kind 死亡侧不冻=互斥门）
            val b = c.birth
            if (b == null || s.birth == b || TasteAstroPhysics.isDeathSideKind(s.kind)) {
                if (driftCopiedFlag) s.copy(drift = c.drift) else s
            } else {
                birthCopied++
                if (driftCopiedFlag) s.copy(birth = b, drift = c.drift) else s.copy(birth = b)
            }
        } else {
            copied++
            if (c.kind == s.kind && c.hist == s.hist && c.birth == s.birth && c.drift == s.drift) {
                s
            } else {
                s.copy(kind = c.kind, hist = c.hist, birth = c.birth, drift = c.drift)
            }
        }
    }
    val kindCounts = HashMap<CelestialKind, Int>()
    for (s in stars) kindCounts.merge(s.kind, 1, Int::plus)
    android.util.Log.i(
        "TasteMap",
        "R317b 宣判复用: 对位=${cachedById.size} 拷贝=$copied 诞生=$birthCopied 漂移=$driftCopied（零 IO——缓存宣判直读，" +
            "黑洞=${kindCounts[CelestialKind.BLACK_HOLE] ?: 0} 中子=${kindCounts[CelestialKind.NEUTRON_STAR] ?: 0} " +
            "夸克=${kindCounts[CelestialKind.QUARK_STAR] ?: 0} 白洞=${kindCounts[CelestialKind.WHITE_HOLE] ?: 0}）",
    )
    return fresh.copy(stars = stars, kindCounts = kindCounts, verdictApplied = true)
}

// ==================== R325 跃迁 diff 三通道（r325 C 章——通道②跨会话 stage diff
// ==================== +通道③行星成型修饰位 diff；通道①会话内点亮判别在点亮链
// ==================== （pollGalaxyBeatsR254 lit toast 年轻变体——零缓存依赖））

/**
 * R328 年轻星判别**单口**（kind==PROTOSTAR ∨ birth 注记非空非 NOT_BIRTH——
 * r325 C 章通道①会话内判别口径的收敛单源）：lit toast 年轻变体（youngIdsR327
 * 生产位）与点燃拍渲染门（Canvas/GL 的 REVEAL_FX 窗内白闪）共用一处——判据族
 * [TasteAstroPhysics] 收官零碰，本口只组合既有 kind 枚举与注记零新判据（纪律§一
 * 同判别不两写）。
 */
internal fun youngBirthStarR328(
    kind: CelestialKind,
    birth: TasteAstroPhysics.BirthFeatures?,
): Boolean =
    kind == CelestialKind.PROTOSTAR ||
        (birth != null && birth.stage != TasteAstroPhysics.BirthStage.NOT_BIRTH)

/** R325 诞生跃迁条目（diff 清单产物——批 3 GRAVITY_WAVE 入队原料+批 C 触发位原料） */
private data class BirthTransitionR325(
    val chatId: Long,
    /** 旧诞生态（NOT_BIRTH=旧缓存无注记——含 v5 首进全员入图前的缺省态） */
    val from: TasteAstroPhysics.BirthStage,
    /** 新诞生态（NOT_BIRTH=消散——点燃升档或弃探） */
    val to: TasteAstroPhysics.BirthStage,
    /** 行星成型修饰位 false→true（**不排导演器**——贴脸凝缩演出归批 C，r325 C②） */
    val planetsFormingOn: Boolean,
)

/**
 * R325 跨会话诞生跃迁清单（**纯函数确定性**——同输入同输出+chatId 遍历序）：
 * 对位比对旧星表（缓存 birth）vs 新星表（宣判 birth）的 stage 跃迁（含
 * NONE→ACCRETING=聚合开始/→NOT_BIRTH=消散）+planetsForming false→true 修饰位
 * 跃迁。双侧无注记=零条目；新星（旧表无对位）首判注记=from=NOT_BIRTH（聚合
 * 开始读法）。旧表有注记新星表无对位（星已移出图）不算跃迁（移除非跃迁）。
 */
private fun diffBirthTransitionsR325(oldStars: List<StarNode>, newStars: List<StarNode>): List<BirthTransitionR325> {
    if (oldStars.isEmpty() || newStars.isEmpty()) return emptyList()
    val oldBirth = HashMap<Long, TasteAstroPhysics.BirthFeatures>(oldStars.size * 2)
    for (s in oldStars) if (s.birth != null) oldBirth[s.chatId] = s.birth!!
    val out = ArrayList<BirthTransitionR325>(16)
    for (s in newStars) {
        val nb = s.birth
        if (nb == null) {
            // 消散向：旧有注记→新无注记（stage 跃迁→NOT_BIRTH）
            val ob = oldBirth[s.chatId] ?: continue
            out += BirthTransitionR325(s.chatId, ob.stage, TasteAstroPhysics.BirthStage.NOT_BIRTH, false)
            continue
        }
        val ob = oldBirth[s.chatId]
        val from = ob?.stage ?: TasteAstroPhysics.BirthStage.NOT_BIRTH
        val formingOn = nb.planetsForming && ob?.planetsForming != true
        if (from == nb.stage && !formingOn) continue // 双侧同态零跃迁
        out += BirthTransitionR325(s.chatId, from, nb.stage, formingOn)
    }
    return out
}

/**
 * R328 跃迁涟漪强度档（0..1——[UniverseDirector.enqueueGravityWave] 的 intensity01）：
 * 诞生跃迁=「新星点燃涟漪」（r325 C①-3：与超新星爆发闪光同 Fx 族**不同强度档**
 * ——低于宣判缺省档 0.72 的诚实低档：诞生是缓事件非质量剧变）。
 */
private const val BIRTH_TRANSITION_WAVE_I01 = 0.5f

/**
 * R325 跃迁清单上报+**批3 入队**（R320 套回后 R328 兑现——r325 C①-3「跃迁演出
 * 走 Directive 参数位而非新 EventKind」）：stage 跃迁（聚合开始/点燃升档/弃探/
 * 候选进退）经 [UniverseDirector.enqueueGravityWave] 入 **GRAVITY_WAVE 档**（
 * +700ms 错拍/同星同种去重/pendings 帽 32/节奏闸五门照常把关——发车节奏归导演器，
 * 本口只投递）+脱敏计数探针行；planetsForming 位**不入队**（r325 C② 论证：行星
 * 只在贴脸可见，全景级事件是噪声——贴脸凝缩演出归批 C 渲染端）。
 * director=null=旧调用面防御（只出探针行零入队）。
 */
private fun reportBirthTransitionsR325(
    transitions: List<BirthTransitionR325>,
    director: UniverseDirector.State?,
    nowMs: Long,
) {
    if (transitions.isEmpty()) return
    var start = 0
    var gone = 0
    var forming = 0
    var enqueued = 0
    for (t in transitions) {
        if (t.planetsFormingOn) forming++
        if (t.to == TasteAstroPhysics.BirthStage.ACCRETING &&
            t.from == TasteAstroPhysics.BirthStage.NOT_BIRTH
        ) {
            start++
        }
        if (t.to == TasteAstroPhysics.BirthStage.NOT_BIRTH) {
            gone++
        }
        // 批3 入队：stage 跃迁（任意向）→ GRAVITY_WAVE 诞生档涟漪（同星去重+节奏闸
        // 五门在导演器内把关——清单大涌入时天然限流）
        if (t.to != t.from && director != null) {
            UniverseDirector.enqueueGravityWave(director, nowMs, r282DirectorCfg, t.chatId, BIRTH_TRANSITION_WAVE_I01)
            enqueued++
        }
    }
    android.util.Log.i(
        "TasteMap",
        "R325 跃迁diff: 跃迁=${transitions.size}（聚合开始=$start 消散=$gone）行星成型=$forming " +
            "入队=$enqueued（GRAVITY_WAVE 诞生档 I01=${"%.2f".format(BIRTH_TRANSITION_WAVE_I01)}——节奏闸接管发车）",
    )
}

/** R327 循环行空间共在门（世界单位平方）：sp 距离阈 300（discoveryBoost 近雾距
 *  260 同量级=「同一片星域」读法——r315 K 电离朝向最近邻同族口径）。
 *  R323：数值收口 core 单源 [EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2]（循环联动口
 *  空间共在窗同值——同数字单一出处，改就一起改；const→val 引用零行为变化） */
private val BIRTH_CYCLE_NEIGHBOR_D2 = EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2

/**
 * R327 循行原料：origin 星云候选星近旁最近终态星的天体名（[TasteAstroPhysics.isDeathSideKind]
 * 单口门+sp 三维距离 <[BIRTH_CYCLE_NEIGHBOR_D2]；chatId 破平确定性——同输入同输出）。
 * null=无终态星共在（循环行整行不出）。调用方=StarInfoCard 层（starsById 在场，
 * O(n) 卡开时一次——静态 sp 坐标非渲染热路径）。
 */
private fun nearestDeathSideLabelR325(origin: StarNode, stars: Collection<StarNode>): String? {
    var bestD2 = BIRTH_CYCLE_NEIGHBOR_D2
    var bestId = 0L
    var bestKind: CelestialKind? = null
    for (s in stars) {
        if (s.chatId == origin.chatId || !TasteAstroPhysics.isDeathSideKind(s.kind)) continue
        val dx = s.spX - origin.spX
        val dy = s.spY - origin.spY
        val dz = s.spZ - origin.spZ
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 < bestD2 || (d2 == bestD2 && bestKind != null && s.chatId < bestId)) {
            bestD2 = d2
            bestId = s.chatId
            bestKind = s.kind
        }
    }
    return bestKind?.let { TasteGalaxyClassifier.labelOf(it) }
}

private suspend fun assembleTasteGalaxy(
    gateway: TdGateway,
    vm: FeedViewModel,
    // R254 阶段回调（首开进度文案驱动；null=旧行为零开销）
    onPhase: ((String) -> Unit)? = null,
): MapAssembly {
    // R254 装配计时探针（真机瓶颈定位——SystemClock 单调钟）
    val tAsm = android.os.SystemClock.elapsedRealtime()
    fun msSince(prev: Long): Long = android.os.SystemClock.elapsedRealtime() - prev
    val now = System.currentTimeMillis()
    val t1 = android.os.SystemClock.elapsedRealtime()
    val events = runCatching { vm.store.loadWatchEvents() }.getOrDefault(emptyList())
    val real = gateway as? TdLibGateway
    val snap = runCatching { real?.discoveryGraph?.snapshot() }.getOrNull()
    val t2 = android.os.SystemClock.elapsedRealtime()
    // R152 深度索引全频道基因（体量/构成回落/时长风格的特征原料）
    onPhase?.invoke("聚合深索引基因…")
    val genomes = loadIndexGenomes(vm.app)
    val t2b = android.os.SystemClock.elapsedRealtime()
    // ---- R223b 全宇宙数据源（全本地只读；金矿/类型化未命中=本轮缺据诚实降级） ----
    // meta 全表=已爬会话集合（频道+群组+私聊全覆盖口径，主键扫毫秒级）
    val metaIds = loadIndexedChatIds(vm.app)
    val t3 = android.os.SystemClock.elapsedRealtime()
    // 金矿快照**只读取现有**（内存缓存→磁盘快照，毫秒级；绝不触发重建——重建归
    // 发现页/预热协调器单一所有方。星图本就是大库全表 GROUP BY 的重客户端，再叠
    // 一路金矿 rollup 全表走一遍会把 lmkd 内存红线顶爆——真机实测装配期进程被杀
    // 的放大器。陈值无害：档案是小时级稳定事实，缺据=恒星诚实缺省（设计 §1.2
    // 降级兼容），发现页下一轮预热自然补新）
    onPhase?.invoke("读取频道档案…")
    val goldSnapshot = vm.discoveryGoldSnapshot
        ?: runCatching { DiscoveryGoldmine.loadSnapshot(vm.discoveryGoldFile()) }.getOrNull()
    val goldById = goldSnapshot?.byId() ?: emptyMap()
    // 类型化实体档案（1h TTL：CHAT_SUPERGROUP(isChannel)/BASICGROUP/PRIVATE）
    val profiles = if (real == null) emptyList() else runCatching { real.listDiscoveryProfiles() }.getOrDefault(emptyList())
    // bot 实体档案（纯本地读：手动∪勾选∪自动发现的 KIND_BOT——秒回/收割清单的既
    // 有产物；进程内缓存空时落盘兜底，绝不触发重扫）
    val searchEntities = if (real == null) {
        emptyList()
    } else {
        runCatching { real.groupHarvester.knownSearchEntities() }.getOrDefault(emptyList())
    }
    val t4 = android.os.SystemClock.elapsedRealtime()

    // ---- 互动账（R147）+ R317b 白洞口径账面在场位：收口顶层
    //      [collectAccountLedgersR317b]（装配走独立方法铁令——assembleTasteGalaxy
    //      预算闸防溢拆分；三账口径论证见 r317b 笔记②章：like=读取成功位/fav=
    //      磁盘直读位/danmaku=广场快照在场位，缺席=-1 缺据不判白洞） ----
    val ledgers = collectAccountLedgersR317b(vm)
    val likeCount = ledgers.likeCount
    val favCount = ledgers.favCount
    val danmakuCount = ledgers.danmakuCount
    // ---- R250 个人足迹账（条目级看过条数）：history.json 按 key 前缀计数（500 条
    //      去重滚动窗——同一 key 只记一次=「看过」条目级诚实账；窗口滚动=窗口外
    //      不计，绝不冒充全史口径）；key 解析收口 [ledgerChatIdOfKey] 单口 ----
    val watchedCount = HashMap<Long, Int>()
    for ((k, _) in runCatching { vm.store.loadHistory() }.getOrDefault(emptyList())) {
        ledgerChatIdOfKey(k)?.let { watchedCount.merge(it, 1, Int::plus) }
    }

    // ---- R223b 实体类型化（星团/脉冲星/流星判型；类型化权威=DiscoveryProfile，
    //      bot 实证=isBotUser GetUser 本地读（正负缓存）+SearchEntity.KIND_BOT 档案。
    //      私聊逐个实证有界：真人私聊通常个位-十位数，护栏上限 80 次防极端账号） ----
    val profileById = HashMap<Long, TdLibGateway.DiscoveryProfile>(profiles.size * 2)
    for (p in profiles) profileById[p.chatId] = p
    val botChatIds = HashSet<Long>()
    for (e in searchEntities) {
        if (e.kind == SearchEntity.KIND_BOT) botChatIds += e.chatId
    }
    // 私聊分类：bot（脉冲星）或真人（流星）——SearchEntity 已知 bot 的跳过 GetUser
    onPhase?.invoke("实证私聊身份…")
    val t5 = android.os.SystemClock.elapsedRealtime()
    val privateUsers = ArrayList<TdLibGateway.DiscoveryProfile>(16)
    var botProbes = 0
    for (p in profiles.sortedBy { it.chatId }) { // chatId 升序=确定性
        if (p.chatKind != TdLibGateway.DiscoveryProfile.CHAT_PRIVATE) continue
        if (p.userId == 0L) continue
        if (p.chatId in botChatIds) continue
        if (botProbes >= 80) break // 极端账号护栏（诚实：超出部分不判型不进图）
        botProbes++
        val isBot = real != null && runCatching { real.isBotUser(p.userId) }.getOrDefault(false)
        if (isBot) botChatIds += p.chatId else privateUsers += p
    }

    // ---- 标题拼表（先到先得，后到不覆盖）：频道列表 > 订阅 > 内存条目 > 图谱节点
    //      > 金矿快照 > 类型化实体档案 ----
    val titles = HashMap<Long, String>()
    fun putTitle(id: Long, t: String?) {
        if (!t.isNullOrBlank()) titles.putIfAbsent(id, t)
    }
    vm.channels.value.forEach { putTitle(it.id, it.title) }
    vm.subscriptions.value.forEach { putTitle(it.id, it.title) }
    vm.items.value.forEach { putTitle(it.chatId, it.channelTitle) }
    snap?.nodes?.forEach { putTitle(it.chatId, it.title) }
    goldById.values.forEach { putTitle(it.chatId, it.title) }
    profiles.forEach { putTitle(it.chatId, it.title) }
    searchEntities.forEach { putTitle(it.chatId, it.title) }

    // ---- 频道内容类型投票：内存条目（当前 Feed + 收藏 + 稍后再看）多数表决 ----
    val votes = HashMap<Long, IntArray>()
    fun vote(chatId: Long, mediaType: Int) {
        val arr = votes.getOrPut(chatId) { IntArray(3) }
        arr[mediaType.coerceIn(0, 2)]++
    }
    vm.items.value.forEach { vote(it.chatId, it.mediaType) }
    vm.favoriteItems.forEach { vote(it.chatId, it.mediaType) }
    vm.watchLaterItems.forEach { vote(it.chatId, it.mediaType) }

    // 单条事件的内容类型：频道画像优先，缺失走启发式（口径与观看报告 buildWatchReport 一致）
    fun typeOf(e: WatchEvent): Int {
        votes[e.chatId]?.let { arr -> return arr.indices.maxByOrNull { arr[it] } ?: 0 }
        if (e.durationSec <= 0) return 1
        if (e.watchSec > 0 && e.watchSec == e.durationSec && e.durationSec <= 30) return 1
        return 0
    }

    // ---- 观看账：每频道累计秒数 / 最近观看 / 分类型秒数 ----
    class WatchAcc(var sec: Long = 0L, var lastMs: Long = 0L, var v: Long = 0L, var p: Long = 0L, var a: Long = 0L)
    val watch = HashMap<Long, WatchAcc>()
    var totalWatchSec = 0L
    for (e in events) {
        val ws = e.watchSec.coerceAtLeast(0).toLong()
        totalWatchSec += ws
        val acc = watch.getOrPut(e.chatId) { WatchAcc() }
        acc.sec += ws
        if (e.ts > acc.lastMs) acc.lastMs = e.ts
        when (typeOf(e)) {
            1 -> acc.p += ws
            2 -> acc.a += ws
            else -> acc.v += ws
        }
    }

    // ---- R206 星生序 + 今晚星轨原料：每频道首次观看时刻（min ts）；今日事件按时间序
    //      排成频道串（连续重复去重——同一频道连刷算一站，星轨画的是「跳跃」；上限
    //      300 站防极端日刷爆渲染） ----
    val firstWatchMs = HashMap<Long, Long>()
    val todayCal = java.util.Calendar.getInstance()
    todayCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    todayCal.set(java.util.Calendar.MINUTE, 0)
    todayCal.set(java.util.Calendar.SECOND, 0)
    todayCal.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = todayCal.timeInMillis
    val todayEvents = ArrayList<WatchEvent>(64)
    for (e in events) {
        if (e.ts > 0) {
            val prev = firstWatchMs[e.chatId]
            if (prev == null || e.ts < prev) firstWatchMs[e.chatId] = e.ts
        }
        if (e.ts >= todayStart) todayEvents += e
    }
    todayEvents.sortBy { it.ts }
    val todayTrailRaw = ArrayList<Long>(32)
    var lastTrailChat = 0L
    for (e in todayEvents) {
        if (e.chatId != 0L && e.chatId != lastTrailChat) {
            todayTrailRaw += e.chatId
            lastTrailChat = e.chatId
        }
    }

    // R206 星生序：频道首见=索引最早条目日期与首次观看的较早者（毫秒）；双源皆无=0
    // （无证据星在创世纪里最后诞生——没有证据不冒充古老）
    fun firstSeenOf(id: Long): Long {
        val idxMs = (genomes[id]?.firstDateSec ?: 0L) * 1000L
        val watchMs = firstWatchMs[id] ?: 0L
        return if (idxMs > 0 && watchMs > 0) minOf(idxMs, watchMs) else maxOf(idxMs, watchMs)
    }

    // ---- R223b 实体类型表（chatId → 非频道天体；频道=不在表内）----
    val entityTypes = HashMap<Long, CelestialKind>()
    for (p in profiles) {
        when (p.chatKind) {
            TdLibGateway.DiscoveryProfile.CHAT_BASICGROUP -> entityTypes[p.chatId] = CelestialKind.CLUSTER
            TdLibGateway.DiscoveryProfile.CHAT_SUPERGROUP ->
                if (!p.isChannel) entityTypes[p.chatId] = CelestialKind.CLUSTER
            TdLibGateway.DiscoveryProfile.CHAT_PRIVATE ->
                entityTypes[p.chatId] = if (p.chatId in botChatIds) CelestialKind.PULSAR else CelestialKind.METEOR
            else -> {} // CHAT_OTHER（秘密聊天等）：按频道口径处理
        }
    }
    for (e in searchEntities) {
        if (e.kind == SearchEntity.KIND_BOT) entityTypes.putIfAbsent(e.chatId, CelestialKind.PULSAR)
    }

    // ---- 节点池（LinkedHashMap 保持插入顺序：看过 > 订阅 > 索引宇宙体量降序 > 实体天体） ----
    val subIds = vm.subscriptions.value.mapTo(HashSet()) { it.id }
    val byId = snap?.nodes?.associateBy { it.chatId } ?: emptyMap()
    val pool = LinkedHashMap<Long, MapStarRaw>()
    val maxWatch = watch.values.maxOfOrNull { it.sec } ?: 0L
    // 体量主轴归一分母：基因条数 ∪ 金矿条数取最大（全宇宙可比的量纲）
    var maxIdx = 0
    for (g in genomes.values) maxIdx = maxOf(maxIdx, g.count)
    for (g in goldById.values) maxIdx = maxOf(maxIdx, g.items)
    fun addRaw(id: Long, build: () -> MapStarRaw) {
        if (id == 0L || pool.containsKey(id)) return
        pool[id] = build()
    }
    // 星色（R152c 降饱和版）：构成定色相（三基相 20°/125°/265° 圆插值），
    // 同构成频道再用 chatId 哈希做次级差异（色相 ±12° 抖动 + 饱和/亮度微差）——
    // 远看按内容类型成簇、近看颗颗可辨；饱和 0.40~0.58、亮度 0.62~0.78 的
    // "暗夜柔光"档（真机反馈"纯饱和色廉价"，与三基色常量同口径改就一起改）
    fun starColor(chatId: Long, v: Long, p: Long, a: Long): Int {
        val wv = v.toFloat()
        val wp = p.toFloat()
        val wa = a.toFloat()
        if (wv + wp + wa > 0f) {
            // 色相圆插值：三基相的向量加权平均（sin/cos 分量），无构成时不进这里
            val rad = Math.PI / 180.0
            val hx = wv * sin(HUE_VIDEO * rad).toFloat() + wp * sin(HUE_PHOTO * rad).toFloat() + wa * sin(HUE_AUDIO * rad).toFloat()
            val hy = wv * cos(HUE_VIDEO * rad).toFloat() + wp * cos(HUE_PHOTO * rad).toFloat() + wa * cos(HUE_AUDIO * rad).toFloat()
            var hue = Math.toDegrees(atan2(hx.toDouble(), hy.toDouble())).toFloat()
            if (hue < 0f) hue += 360f
            // 次级差异（取模先转正再减中位——负 chatId 的 % 是负数，不转正会全偏向一端）；
            // 抖动压到 ±12°（防个体渐变漂过头读错类型）
            val jit = ((chatId % 41 + 41) % 41 - 20) * 0.6f
            hue = (hue + jit + 360f) % 360f
            val sat = (0.49f + (((chatId % 13 + 13) % 13) - 6) * 0.010f).coerceIn(0.40f, 0.58f)
            val bri = (0.70f + (((chatId % 11 + 11) % 11) - 5) * 0.012f).coerceIn(0.62f, 0.78f)
            return android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))
        }
        // 无观看账 → 条目投票等权圆插值 → 再无则暗星色
        val arr = votes[chatId]
        if (arr != null && arr[0] + arr[1] + arr[2] > 0) {
            return starColor(chatId, arr[0].toLong(), arr[1].toLong(), arr[2].toLong())
        }
        return TYPE_DIM
    }
    // 星半径：sqrt(观看时长比)^γ 归一（γ>1 压低中段抬高头部——top 频道显著更大），
    // 没看过 = 暗星小点
    fun starRadius(sec: Long): Float =
        if (sec > 0 && maxWatch > 0) {
            RADIUS_MIN + (RADIUS_MAX - RADIUS_MIN) * sqrt(sec.toFloat() / maxWatch).pow(RADIUS_GAMMA)
        } else {
            DIM_RADIUS
        }
    // 星亮度（R152 口径对齐设计：亮度按最近互动）：观看新鲜度打底（近 60 天线性
    // 1.0→0.55），互动账（赞+收藏+弹幕）对数再抬至多 +0.30——最近活跃互动过的频道更亮；
    // 没看过 0.55（R152c：真机上 0.32 档的暗点在近黑背景里几乎隐形，"大片空黑"实为
    // 没看过的频道摊满整图却看不见——抬到稳定可见的小暗点档，靠小半径+低饱和区分主次）
    fun starAlpha(sec: Long, lastMs: Long, interacts: Int): Float {
        val fresh = if (sec > 0) {
            val daysSince = (now - lastMs).toFloat() / 86_400_000f
            0.55f + 0.45f * (1f - (daysSince / 60f).coerceIn(0f, 1f))
        } else {
            0.55f
        }
        val boost = if (interacts > 0) 0.30f * (ln1p(interacts.toFloat()) / 2f).coerceIn(0f, 1f) else 0f
        return (fresh + boost).coerceIn(0.55f, 1f)
    }
    // R152 特征原料：索引基因优先（条数=体量、三类条数=构成回落、平均时长=时长风格），
    // 无索引回落内存条目投票构成（与 starColor 三级回落同口径）
    val noFeat = FeatFallback(0, 0, 0, 0, 0L)
    fun featOf(id: Long): FeatFallback {
        val g = genomes[id] ?: return votes[id]?.let { arr ->
            FeatFallback(0, arr[0], arr[1], arr[2], 0L)
        } ?: noFeat
        return FeatFallback(g.count, g.video, g.photo, g.audio, g.avgDurSec)
    }

    // ---- R223b 体量主轴口径三件（App 渲染器消费；旧口径三件（starColor/starRadius/
    //      starAlpha）原样保留给海报——两套口径边界见 MapStarRaw KDoc） ----
    // 体量口径颜色：**R311 色彩引擎版**——黑体色温谱连续值（[starKelvinR311] 三轴合成：
    // 活跃率+年龄+体量，用户「年龄越大的恒星颜色越红」「数据量越大越热亮蓝白」成文）×
    // 内容构成色微调（观看构成>条目投票>索引基因 三级回落同旧口径——「星的颜色=频道
    // 内容的颜色」；封面图主色索引库无数据=诚实替代，见 r311_notes 数据裁决）。
    // 旧构成混色公式退役（R152c 三基相圆插值——「千篇一律」实锤的病根：全宇宙
    // 只剩三基相混出的近似色）。
    fun starColorBodyR311(id: Long, v: Long, p: Long, a: Long, kelvin: Float): Int {
        var content = 0
        if (v + p + a > 0) content = TasteStarColor.compositionArgb(v, p, a)
        if (content == 0) {
            val arr = votes[id]
            if (arr != null && arr[0] + arr[1] + arr[2] > 0) {
                content = TasteStarColor.compositionArgb(arr[0].toLong(), arr[1].toLong(), arr[2].toLong())
            }
        }
        if (content == 0) {
            val g = genomes[id]
            if (g != null && g.video + g.photo + g.audio > 0) {
                content = TasteStarColor.compositionArgb(g.video.toLong(), g.photo.toLong(), g.audio.toLong())
            }
        }
        return TasteStarColor.starColorOfK(kelvin, content)
    }

    /** R311 连续色温（K）：三轴合成单口（活跃=金矿 recent7/断更回落；年龄=首见；
     *  体量=ln1p 归一）——bodyColor 与渲染端芯/缘色同一份 kelvin（单源不裂） */
    fun starKelvinR311(id: Long, vol: Int, goldLastActivitySec: Long, goldRecent7: Int, firstSeenMs: Long): Float {
        val act01 = if (goldRecent7 >= 0) {
            TasteStarColor.activity01Of(goldRecent7)
        } else {
            TasteStarColor.activityFallback01(
                if (goldLastActivitySec > 0L) (now / 1000L - goldLastActivitySec) / 86_400L else -1L,
            )
        }
        val ageDays = if (firstSeenMs > 0L) (now - firstSeenMs).toFloat() / 86_400_000f else -1f
        return TasteStarColor.starKelvinOf(act01, ageDays, TasteStarColor.volume01Of(vol, maxIdx), id)
    }

    // 体量口径半径：√(条数/max条数)^γ 为主轴（体量=全宇宙可比量纲），已观看频道
    // 观看权重并入（0.65 体量+0.35 观看——两轴都有分量的更立体）；零证据=暗星点
    fun bodyRadiusOf(vol: Int, watchSec: Long): Float {
        if (vol <= 0 && watchSec <= 0) return DIM_RADIUS
        val volBase = if (vol > 0 && maxIdx > 0) sqrt(vol.toFloat() / maxIdx).pow(RADIUS_GAMMA) else 0f
        val watchBase = if (watchSec > 0 && maxWatch > 0) sqrt(watchSec.toFloat() / maxWatch).pow(RADIUS_GAMMA) else 0f
        val mix = if (watchSec > 0) 0.65f * volBase + 0.35f * watchBase else volBase
        return RADIUS_MIN + (RADIUS_MAX - RADIUS_MIN) * mix
    }

    // 体量口径亮度：观看新鲜度（旧口径）∨ 内容活跃度（lastActivity 距今天数——全宇宙
    // 频道都能亮：未加入靠索引活跃度发光，已加入双源取强；设计 §1.3 亮度口径）
    fun bodyAlphaOf(sec: Long, lastMs: Long, interacts: Int, lastActivitySec: Long): Float {
        val watchA = starAlpha(sec, lastMs, interacts)
        if (lastActivitySec <= 0L) return watchA
        val days = (now / 1000L - lastActivitySec).toFloat() / 86_400f
        val actA = 0.55f + 0.45f * (1f - (days / 60f).coerceIn(0f, 1f))
        return maxOf(watchA, actA)
    }

    // ---- R223b 原料构建器（频道六态档案原料=金矿；实体天体=类型即定） ----
    fun buildChannelRaw(id: Long, w: WatchAcc?): MapStarRaw {
        val f = featOf(id)
        val inter = (likeCount[id] ?: 0) + (favCount[id] ?: 0) + (danmakuCount[id] ?: 0)
        val gold = goldById[id]
        val vol = maxOf(f.idxCount, gold?.items ?: 0)
        val sec = w?.sec ?: 0L
        val lastMs = w?.lastMs ?: 0L
        val v = w?.v ?: 0L
        val p = w?.p ?: 0L
        val a = w?.a ?: 0L
        val firstSeen = firstSeenOf(id)
        // R311 色彩引擎：kelvin 一次算定（bodyColor 与渲染端芯/缘色同一份——单源不裂）
        val kelvin = starKelvinR311(id, vol, gold?.lastActivitySec ?: 0L, gold?.recent7 ?: -1, firstSeen)
        return MapStarRaw(
            id, titles[id].orEmpty(), sec, lastMs, v, p, a,
            byId[id]?.memberCount ?: -1, id in subIds,
            starColor(id, v, p, a), starAlpha(sec, lastMs, inter), starRadius(sec), null,
            likeCount[id] ?: 0, favCount[id] ?: 0, danmakuCount[id] ?: 0,
            f.idxCount, f.gVideo, f.gPhoto, f.gAudio, f.avgDurSec,
            firstSeen,
            gold?.items ?: 0, gold?.lastActivitySec ?: 0L, gold?.recent7 ?: -1, gold?.prev7 ?: -1, gold?.tier ?: -1,
            null,
            bodyRadiusOf(vol, sec),
            bodyAlphaOf(sec, lastMs, inter, gold?.lastActivitySec ?: 0L),
            starColorBodyR311(id, v, p, a, kelvin),
            kelvin, // R311：连续色温随星走（渲染端芯/缘色连续导出原料）
            watchedCount[id] ?: 0, // R250 个人足迹（条目级看过条数）
        )
    }

    fun buildEntityRaw(id: Long, kind: CelestialKind): MapStarRaw {
        val f = featOf(id)
        val gold = goldById[id]
        val vol = maxOf(f.idxCount, gold?.items ?: 0)
        val members = byId[id]?.memberCount ?: -1
        // 实体展示参数（体量/成员数只做克制的规模提示，笔刷身份即语义）
        val color: Int
        val alpha: Float
        val bodyR: Float
        when (kind) {
            CelestialKind.PULSAR -> {
                color = PULSAR_COLOR
                alpha = 0.92f
                bodyR = 2.4f
            }
            CelestialKind.CLUSTER -> {
                color = CLUSTER_COLOR
                alpha = 0.80f
                // 星团核：基础 + 体量微调（成员尘点云的规模在渲染端按 memberCount 画）
                bodyR = 3.6f + if (vol > 0 && maxIdx > 0) {
                    2.0f * (ln1p(vol.toDouble()) / ln1p(maxIdx.toDouble())).toFloat()
                } else 0f
            }
            else -> { // METEOR
                color = METEOR_COLOR
                alpha = 0.70f
                bodyR = 2.0f
            }
        }
        return MapStarRaw(
            id, titles[id].orEmpty(), 0L, 0L, 0L, 0L, 0L,
            members, false,
            color, alpha, DIM_RADIUS, null,
            0, 0, 0,
            f.idxCount, f.gVideo, f.gPhoto, f.gAudio, f.avgDurSec,
            firstSeenOf(id), // 已索引实体（群组/bot 私聊）有真首见——创世纪诚实参与
            gold?.items ?: 0, gold?.lastActivitySec ?: 0L, gold?.recent7 ?: -1, gold?.prev7 ?: -1, gold?.tier ?: -1,
            kind,
            bodyR,
            alpha,
            color,
        )
    }

    // ---- 1) 看过（实体判型优先：看过的群组=星团不是恒星） ----
    for ((id, w) in watch) {
        if (w.sec <= 0) continue // 只有 0 秒负反馈事件的频道不点亮（跳过不算口味）
        val k = entityTypes[id]
        if (k != null) addRaw(id) { buildEntityRaw(id, k) } else addRaw(id) { buildChannelRaw(id, w) }
    }
    // ---- 2) 订阅（订阅表=频道集合，按定义是恒星） ----
    for (id in subIds) {
        addRaw(id) { buildChannelRaw(id, null) }
    }
    // ---- 3) 索引宇宙补齐（meta 全表 ∪ 金矿，体量降序+chatId 升序确定性留重；
    //      已类型化为群组/bot/用户的 id 不在此进池——它们走第 4 步实体池） ----
    if (pool.size < MAX_STARS) {
        val universe = HashSet<Long>(metaIds.size + goldById.size)
        universe += metaIds
        universe += goldById.keys
        val ranked = universe
            .filter { it != 0L && it !in pool && it !in entityTypes }
            .sortedWith(
                compareByDescending<Long> { maxOf(genomes[it]?.count ?: 0, goldById[it]?.items ?: 0) }
                    .thenBy { it },
            )
        for (id in ranked) {
            if (pool.size >= MAX_STARS) break
            addRaw(id) { buildChannelRaw(id, null) }
        }
    }
    // ---- 4) 实体天体（脉冲星/星团/流星；chatId 升序确定性，量级护栏） ----
    run {
        val capOf = HashMap<CelestialKind, Int>()
        for ((id, k) in entityTypes.entries.sortedBy { it.key }) {
            val cap = when (k) {
                CelestialKind.PULSAR -> MAX_PULSARS
                CelestialKind.CLUSTER -> MAX_CLUSTERS
                else -> MAX_METEORS
            }
            val cur = capOf.getOrDefault(k, 0)
            if (cur >= cap) continue
            capOf[k] = cur + 1
            addRaw(id) { buildEntityRaw(id, k) }
        }
    }

    // ---- R223b 海报旧口径池（「现有静态版不变」红线件）：旧三源封闭池同序重算
    //      watch>0 → 订阅 → 图谱补齐 300（与 R206 装配逐字同构，独立于宇宙池） ----
    val legacyPosterIds = LinkedHashSet<Long>()
    for ((id, w) in watch) {
        if (w.sec > 0) legacyPosterIds += id
    }
    legacyPosterIds += subIds
    if (snap != null) {
        // 图谱写入排序依据：无向度数降序 → 证据量降序 → chatId 升序（结果稳定）
        val degree = HashMap<Long, Int>()
        val evidence = HashMap<Long, Int>()
        for (e in snap.edges) {
            if (e.type == DiscoveryGraph.EDGE_SEARCH_HIT) continue
            if (e.fromChatId == 0L || e.toChatId == 0L) continue
            degree.merge(e.fromChatId, 1, Int::plus)
            degree.merge(e.toChatId, 1, Int::plus)
            evidence.merge(e.fromChatId, e.count, Int::plus)
            evidence.merge(e.toChatId, e.count, Int::plus)
        }
        val ranked = snap.nodes
            .filter { it.chatId != 0L && it.chatId !in legacyPosterIds }
            .sortedWith(
                compareByDescending<DiscoveryGraph.GraphSnapshotNode> { degree[it.chatId] ?: 0 }
                    .thenByDescending { evidence[it.chatId] ?: 0 }
                    .thenBy { it.chatId },
            )
        for (n in ranked) {
            if (legacyPosterIds.size >= LEGACY_POSTER_STARS) break
            legacyPosterIds += n.chatId
        }
    }

    // 名签回填：观看时长头部频道（R152c top 5→8：标签只给最大的头部频道，且绘制端
    // 做了重叠避让——重叠时偏移/只留胜者；小频道默认无名签，点星出信息卡）+ 标志性提亮
    // （R223b：两套口径颜色同步提亮——海报与 App 的头部星都一眼锁定）
    val labeled = pool.values
        .filter { it.watchSec > 0 && it.title.isNotBlank() }
        .sortedByDescending { it.watchSec }
        .take(8)
        .mapTo(HashSet()) { it.chatId }
    for (id in labeled) pool[id]?.let {
        pool[id] = it.copy(label = it.title, colorArgb = boostStarColor(it.colorArgb), bodyColor = boostStarColor(it.bodyColor))
    }

    // ---- 连线：有向边合并成无向边（嵌套表做 (a,b) 键，负数 chatId 无打包碰撞） ----
    val linkWeight = HashMap<Long, HashMap<Long, Int>>()
    // ---- R251⑥ 互联分类账（与连线同源同口径扫一遍边：无向对 × 互转/互链/共现
    //      三类合并计数；search_hit 弱证据边不进——编年史「互联档案」卷原料） ----
    val relAcc = HashMap<Long, HashMap<Long, IntArray>>(64)
    // R321 特洛伊共源上游邻接账（拉格朗点 I①——DiscoveryGraph forward_src 边「A 转自
    // X」的上游邻接；单遍扫边收集，from=在图频道（转发方）、to=任意上游 X（不必在图
    // ——驿站货源是上游源不是星））。零新查询零新索引——只读内存快照一遍。
    val fwdUpAcc = HashMap<Long, HashMap<Long, Int>>(64)
    if (snap != null) {
        for (e in snap.edges) {
            if (e.type == DiscoveryGraph.EDGE_SEARCH_HIT) continue // 弱证据边不画
            if (e.type == DiscoveryGraph.EDGE_FORWARD_SRC && e.fromChatId != 0L && e.fromChatId in pool) {
                fwdUpAcc.getOrPut(e.fromChatId) { HashMap(4) }.merge(e.toChatId, e.count, Int::plus)
            }
            val a = min(e.fromChatId, e.toChatId)
            val b = max(e.fromChatId, e.toChatId)
            if (a == 0L || a !in pool || b !in pool) continue
            linkWeight.getOrPut(a) { HashMap() }.merge(b, e.count, Int::plus)
            val cat = when (e.type) {
                DiscoveryGraph.EDGE_FORWARD_SRC, DiscoveryGraph.EDGE_FORWARD_DST -> 0 // 互转
                DiscoveryGraph.EDGE_CO_WATCH -> 2 // 共现
                else -> 1 // 互链（link+comment=引用族）
            }
            relAcc.getOrPut(a) { HashMap() }.getOrPut(b) { IntArray(3) }[cat] += e.count
        }
    }
    val neighborRels = HashMap<Long, ArrayList<NeighborRel>>(relAcc.size * 2)
    for ((a, bs) in relAcc) {
        val listA = neighborRels.getOrPut(a) { ArrayList(4) }
        for ((b, arr) in bs) {
            val rel = NeighborRel(b, arr[0], arr[1], arr[2])
            listA += rel
            neighborRels.getOrPut(b) { ArrayList(4) } += NeighborRel(a, rel.forward, rel.link, rel.coWatch)
        }
    }
    neighborRels.values.forEach { list -> list.sortByDescending { it.total } }
    // ---- R283 双星证据维度化（BinaryStarPolicy 喂料）：relAcc 三维计数（互转/互链/
    //      共现零成本直供）+ 强证据候选 top-K 查编年史⑥数据底座（interlinkPairSummary
    //      =同指纹内容同源+时间跨度——只读零写；时间预算闸内逐对增强，缺据诚实降级）；
    //      R321 附加账同环收集（洛希同源 W=增强后现役字段直取/特洛伊共源 W=fwdUpAcc
    //      上游交集——只服务有望过门的对，budget 域外对=缺据 0 诚实降级） ----
    val binaryExtras = ArrayList<BinaryPairExtra>(BinaryStarPolicy.ENRICH_TOP_K)
    val binaryEvidence = run {
        val pairs = ArrayList<BinaryStarPolicy.PairEvidence>(relAcc.size)
        for ((a, bs) in relAcc) {
            for ((b, arr) in bs) {
                pairs += BinaryStarPolicy.PairEvidence(a, b, forward = arr[0], link = arr[1], coWatch = arr[2])
            }
        }
        pairs.sortWith(
            compareByDescending<BinaryStarPolicy.PairEvidence> { it.mergedWeight }.thenBy { it.a }.thenBy { it.b },
        )
        val nowSec = System.currentTimeMillis() / 1000L
        val tEn = android.os.SystemClock.elapsedRealtime()
        var enriched = 0
        var queried = 0
        // R321 共源扫描账（强证据候选对索引+共源 W——收口时按增强终态取同源字段）
        val trojIdx = IntArray(BinaryStarPolicy.ENRICH_TOP_K)
        val trojW = IntArray(BinaryStarPolicy.ENRICH_TOP_K)
        var trojN = 0
        for (i in pairs.indices) {
            if (queried >= BinaryStarPolicy.ENRICH_TOP_K) break
            if (android.os.SystemClock.elapsedRealtime() - tEn > BinaryStarPolicy.ENRICH_TIME_BUDGET_MS) break
            val p = pairs[i]
            // 弱证对（图谱强证据不足门）不烧查询——内容底座增强只服务有望过门的对
            if (p.forward + p.link < BinaryStarPolicy.MIN_STRONG_EVIDENCE) continue
            queried++
            // R321 特洛伊共源 W（forward_src 上游交集——无共源=0=零尘埃不冒充驿站）
            val tw = TrojanFx.sharedUpstreamWeight(
                fwdUpAcc[p.a] ?: emptyMap(),
                fwdUpAcc[p.b] ?: emptyMap(),
            )
            if (trojN < trojIdx.size) {
                trojIdx[trojN] = i
                trojW[trojN] = tw
                trojN++
            }
            val s = runCatching { vm.deepIndex.interlinkPairSummary(p.a, p.b) }.getOrNull() ?: continue
            if (s.total <= 0) continue
            pairs[i] = p.copy(
                sharedDirected = s.aToB + s.bToA,
                sharedCoOccur = s.coOccur,
                spanDays = s.spanDays,
                recentDays = if (s.lastDateSec > 0) maxOf(0, ((nowSec - s.lastDateSec) / 86400L).toInt()) else -1,
            )
            enriched++
        }
        // R321 附加账收口：同源 W=增强终态的现役字段（sharedDirected+sharedCoOccur——
        // 潮汐预警行原料）；共源 W=扫描期上游交集（特洛伊簇亮度/特洛伊群行原料）
        for (j in 0 until trojN) {
            val p = pairs[trojIdx[j]]
            binaryExtras += BinaryPairExtra(
                p.a, p.b,
                sharedW = p.sharedDirected + p.sharedCoOccur,
                trojanW = trojW[j],
            )
        }
        if (queried > 0) {
            android.util.Log.i(
                "TasteMap",
                "R283 双星证据增强: 候选=${pairs.size} 查询=$queried 增强=$enriched " +
                    "耗时=${android.os.SystemClock.elapsedRealtime() - tEn}ms",
            )
        }
        pairs
    }
    var linkRaws = linkWeight.flatMap { (a, bs) -> bs.map { (b, w) -> MapLinkRaw(a, b, w) } }
    if (linkRaws.size > MAX_LINKS) {
        linkRaws = linkRaws.sortedByDescending { it.weight }.take(MAX_LINKS)
    }

    // R206 今晚星轨收口：只留在图频道（观看频道全在池内，订阅/宇宙补齐不影响），封顶 300 站
    val todayTrail = todayTrailRaw.filter { pool.containsKey(it) }.take(300)

    // ---- R234 星座轨迹原料：history.json 今日观看（G4 起存完整 VideoItem+观看时刻，
    //      任务书点名口径；与 R206 今晚星轨的 watch_events 口径并存——本串带时间戳，
    //      供「时间序渐变+沿途小星」的明亮版渲染，R206 暗光路+流星零改动）----
    val todayStopsRaw = ArrayList<TodayStop>(32)
    runCatching {
        val tsByKey = HashMap<String, Long>(256)
        for ((k, ts) in vm.store.loadHistory()) tsByKey[k] = ts
        for (it2 in vm.store.loadHistoryItems()) {
            val ts = tsByKey[it2.key] ?: 0L
            if (ts >= todayStart && it2.chatId != 0L) todayStopsRaw += TodayStop(it2.chatId, ts)
        }
    }
    todayStopsRaw.sortBy { it.tsMs }
    val todayStops = ArrayList<TodayStop>(todayStopsRaw.size)
    var lastStopChat = 0L
    for (s in todayStopsRaw) {
        if (s.chatId != lastStopChat) {
            todayStops += s
            lastStopChat = s.chatId
        }
    }
    val constStops = todayStops.filter { pool.containsKey(it.chatId) }.take(CONST_TRAIL_MAX_STOPS)

    android.util.Log.i(
        "TasteMap",
        "R254 装配计时: 总=${msSince(tAsm)}ms 事件账=${t1 - tAsm} 图谱快照=${t2 - t1} 基因聚合=${t2b - t2} " +
            "meta=${t3 - t2b} 金矿与实体=${t4 - t3} bot实证=${t5 - t4} " +
            "池与连线=${msSince(t5)}（事件=${events.size} 档案=${profiles.size}）",
    )
    android.util.Log.i(
        "TasteMap",
        "R223b 全宇宙装配: 天体=${pool.size} 看过=${watch.count { it.value.sec > 0 }} 订阅=${subIds.size} " +
            "meta全集=${metaIds.size} 金矿频道=${goldById.size} 类型化实体=${entityTypes.size} " +
            "脉冲星=${pool.values.count { it.entityKind == CelestialKind.PULSAR }} " +
            "星团=${pool.values.count { it.entityKind == CelestialKind.CLUSTER }} " +
            "流星=${pool.values.count { it.entityKind == CelestialKind.METEOR }} " +
            "连线=${linkRaws.size} 图谱节点=${snap?.nodes?.size ?: 0} 索引基因频道=${genomes.size} " +
            "海报旧池=${legacyPosterIds.size} 演示模式=${gateway !is TdLibGateway} " +
            "首见证据=${firstWatchMs.size} 今日星轨站=${todayTrailRaw.size} " +
            "R234星座轨迹站=${constStops.size}",
    )
    // ---- R311 色彩引擎探针（色温分布 5 桶直方图——「连续谱散布/不撞色」的机器证据；
    //      只出计数零内容——脱敏纪律） ----
    run {
        var tCold = 0; var tWarm = 0; var tSun = 0; var tWhite = 0; var tHot = 0
        for (r in pool.values) {
            if (r.tempK <= 0f) continue
            when {
                r.tempK < 3600f -> tCold++
                r.tempK < 4700f -> tWarm++
                r.tempK < 6200f -> tSun++
                r.tempK < 8200f -> tWhite++
                else -> tHot++
            }
        }
        android.util.Log.i(
            "TasteMap",
            "R311 色彩引擎: 色温谱分布 暗红(<3600K)=$tCold 橙(3600-4700K)=$tWarm 黄白(4700-6200K)=$tSun " +
                "白(6200-8200K)=$tWhite 蓝白炽(>8200K)=$tHot",
        )
    }
    // ---- R267 数据源防御（塌方修复②）：基因组空 ∧ meta 全集≥16 = 批迁/迁移中间态
    //      实锤（分区扫描撞写事务锁窗「诚实缺省」按空降级——正常库绝不可能 meta 有
    //      频道而基因全空）；基因组规模打包成指纹（成品缓存换装门原料） ----
    val genomeFp = run {
        var total = 0L
        for ((_, g) in genomes) total += g.count.toLong()
        if (genomes.isEmpty()) 0L else LayoutHealth.genomeFp(genomes.size, total)
    }
    val sourceDegen = genomes.isEmpty() && metaIds.size >= 16
    if (sourceDegen) {
        android.util.Log.w(
            "TasteMap",
            "R267 装配数据源退化: 基因组=空 meta全集=${metaIds.size}（批迁锁窗中间态实锤——不落盘，等一致快照重试）",
        )
    }
    // ---- R303c P1：tags 账本指纹批量装载（涌现布局场 Jaccard 真源——R236 tags
    //      表全频道历史口径，只读毫秒级点查；回填窗口期/无 tag 库=空表诚实降档，
    //      互链/距离两路仍可成对） ----
    onPhase?.invoke("读取 tag 指纹账本…")
    val tTags = android.os.SystemClock.elapsedRealtime()
    val tagLedger = runCatching { vm.deepIndex.chatTagLedger() }.getOrDefault(emptyMap())
    android.util.Log.i(
        "TasteMap",
        "R303c tag 账本: 频道=${tagLedger.size} 指纹总键=${tagLedger.values.sumOf { it.size }} " +
            "耗时=${android.os.SystemClock.elapsedRealtime() - tTags}ms（空=回填窗口期诚实降档）",
    )
    // ---- R317b 白洞证据收集（装配口账面在场口径——挂账④案 A 落形）：收口顶层
    //      [buildWhiteholeEvidenceR317b]（预算闸拆分；三账哨兵单源
    //      TasteTerminalFx.whiteholeAccountOf——在场=原计数/缺席=-1 缺据不判；
    //      watchSec/watchedCount/goldTier/entityKind=MapStarRaw 字段直供 pool 镜像。
    //      消费方=宣判协程 runLifeVerdictR325 终态半场） ----
    val whiteholeEvidence = buildWhiteholeEvidenceR317b(pool, ledgers)
    return MapAssembly(
        pool.values.toList(), linkRaws, totalWatchSec, todayTrail, legacyPosterIds, constStops,
        neighborRels, binaryEvidence, binaryExtras, genomeFp, sourceDegen, tagLedger, whiteholeEvidence,
    )
}

/** R147/R317b 互动账 item key 解析："tg:<chatId>:<msgId>" → chatId（不合格=null）
 *  ——赞/弹幕/看过三账共用单口（原 assembleTasteGalaxy 局部函数收口顶层——
 *  R317b 预算闸拆分随迁，语义零变化） */
private fun ledgerChatIdOfKey(key: String): Long? {
    if (!key.startsWith("tg:")) return null
    return key.removePrefix("tg:").substringBefore(':').toLongOrNull()
}

/**
 * R317b 互动账账本载体（装配口收集——[collectAccountLedgersR317b] 产物）：
 * 展示值三件（信息卡「最近互动」行/星亮度互动加成的既有口径）+白洞判读件
 * （三账在场位+fav 磁盘直读账——判读标准高于展示标准，见 r317b 笔记②章论证）。
 */
private class R317bAccountLedgers(
    val likeCount: HashMap<Long, Int>,
    val favCount: HashMap<Long, Int>,
    val danmakuCount: HashMap<Long, Int>,
    val likePresent: Boolean,
    val favPresent: Boolean,
    val danmakuPresent: Boolean,
    val favCountDisk: HashMap<Long, Int>,
)

/**
 * R317b 互动账装配收集（**装配走独立方法铁令**——从 assembleTasteGalaxy 预算闸
 * 拆分迁出，语句原样）：like 账=likes.json runCatching isSuccess 在场位（旧
 * getOrDefault 把 IO 失败静默吞成零回馈——现显式在场位，失败=缺据哨兵不判
 * 白洞）；fav 账=**磁盘直读**（favorites.json isSuccess=在场——消除冷启恢复竞窗
 * ：vm.favoriteItems 内存快照在毫秒级恢复窗内可能未就绪，直读磁盘为准；展示值
 * 仍走内存快照原路径零回归）；danmaku 账=广场快照在场位（弹幕账无落盘账本
 * 可读，vm.plaza 快照缺席=诚实缺据不判）。
 */
private fun collectAccountLedgersR317b(vm: FeedViewModel): R317bAccountLedgers {
    val likeLoaded = runCatching { vm.store.loadLikes() }
    val likePresent = likeLoaded.isSuccess
    val likeCount = HashMap<Long, Int>()
    for (k in likeLoaded.getOrDefault(emptySet())) {
        ledgerChatIdOfKey(k)?.let { likeCount.merge(it, 1, Int::plus) }
    }
    val favCount = HashMap<Long, Int>()
    vm.favoriteItems.forEach { favCount.merge(it.chatId, 1, Int::plus) }
    val favLoaded = runCatching { vm.store.loadFavorites() }
    val favPresent = favLoaded.isSuccess
    val favCountDisk = HashMap<Long, Int>()
    for (item in favLoaded.getOrDefault(emptyList())) {
        favCountDisk.merge(item.chatId, 1, Int::plus)
    }
    val plazaSnap = vm.plaza.value
    val danmakuPresent = plazaSnap != null
    val danmakuCount = HashMap<Long, Int>()
    plazaSnap?.danmaku?.keys?.forEach { k ->
        ledgerChatIdOfKey(k)?.let { danmakuCount.merge(it, 1, Int::plus) }
    }
    return R317bAccountLedgers(
        likeCount, favCount, danmakuCount, likePresent, favPresent, danmakuPresent, favCountDisk,
    )
}

/**
 * R317b 白洞证据收集（装配口账面在场口径——挂账④案 A 落形，**装配走独立方法**
 * 预算闸拆分迁出）：三账哨兵单源 [TasteTerminalFx.whiteholeAccountOf]（在场=原
 * 计数/缺席=-1 缺据不判——[TasteAstroPhysics.judgeWhiteHoles] core 既有语义零改）；
 * 其余输入（watchSec/watchedCount/goldTier/entityKind）=MapStarRaw 字段直供。
 * 探针行脱敏（三账在场位透出：缺席≠零回馈，白洞本轮不判）。
 */
private fun buildWhiteholeEvidenceR317b(
    pool: Map<Long, MapStarRaw>,
    ledgers: R317bAccountLedgers,
): List<TasteAstroPhysics.WhiteholeEvidence> {
    val whiteholeEvidence = ArrayList<TasteAstroPhysics.WhiteholeEvidence>(pool.size)
    for (r in pool.values) {
        whiteholeEvidence += TasteAstroPhysics.WhiteholeEvidence(
            chatId = r.chatId,
            watchSec = r.watchSec,
            watchedCount = r.watchedCount,
            goldTier = r.goldTier,
            likeCount = TasteTerminalFx.whiteholeAccountOf(ledgers.likeCount[r.chatId] ?: 0, ledgers.likePresent),
            favCount = TasteTerminalFx.whiteholeAccountOf(ledgers.favCountDisk[r.chatId] ?: 0, ledgers.favPresent),
            danmakuCount = TasteTerminalFx.whiteholeAccountOf(ledgers.danmakuCount[r.chatId] ?: 0, ledgers.danmakuPresent),
            entityKind = r.entityKind,
        )
    }
    android.util.Log.i(
        "TasteMap",
        "R317b 白洞账面: 赞账=${if (ledgers.likePresent) "在场" else "缺据"} " +
            "藏账=${if (ledgers.favPresent) "在场" else "缺据"} " +
            "弹幕账=${if (ledgers.danmakuPresent) "在场" else "缺据（广场快照未装载）"} 候选=${whiteholeEvidence.size} " +
            "（缺据账=白洞本轮不判——绝不冒充零回馈）",
    )
    return whiteholeEvidence
}

// ==================== 布局（Default）：高维口味向量 → 3D 相似度嵌入（R152） ====================

/**
 * R303c P2 涌现布局定形（**装配走独立方法**——layoutTasteMap 的 7.7 段收口）：
 * 位置真源换轨的唯一施工位——四力平衡位过裁决即原地覆写 spX/Y/Z（V3 球面位=
 * 初值+降级位双职责保留）。数据真源：互链=装配无向合并权（DiscoveryGraph 三类
 * 边，§3.1「互链 800 边」=MAX_LINKS 口径）；Jaccard=tags 账本指纹（R236 口径）；
 * 距离=特征余弦矩阵 target（MDS 降级=距离预言机，拍板项③字面落法）；家弹簧=
 * 观看/订阅账（§3.8 正交分解——最爱频道永远在近天区）。温启=缓存平衡位作初值
 * （§6.6 增量演化+§6.9 搬家帽）；冷启=800 轮退火从 V3 松弛。全确定性——
 * 同数据同宇宙（R 系铁律）。返回=涌现在场标记（false=裁决拒收已回退 V3——
 * 探针行已记原因码，零内容脱敏）。
 */
private fun applyEmergentLayoutR303c(
    n: Int,
    raws: List<MapStarRaw>,
    volItems: IntArray,
    volume01: FloatArray,
    spX: FloatArray,
    spY: FloatArray,
    spZ: FloatArray,
    indexOf: Map<Long, Int>,
    target: FloatArray,
    interlinkRaw: List<MapLinkRaw>,
    tagFingerprints: Map<Long, Map<String, Int>>,
    binaryPairs: List<BinaryPair>,
    warm: Map<Long, FloatArray>?,
    nowMs: Long,
    onPhase: ((String) -> Unit)? = null,
): Boolean {
    onPhase?.invoke("四力涌现布局定形…")
    val tEm = android.os.SystemClock.elapsedRealtime()
    var maxWatch = 0L
    for (r in raws) maxWatch = maxOf(maxWatch, r.watchSec)
    val specs = ArrayList<TasteEmergentSource.StarSpec>(n)
    for (i in 0 until n) {
        val r = raws[i]
        specs += TasteEmergentSource.StarSpec(
            chatId = r.chatId, itemsCount = volItems[i], rho = r.bodyRadius,
            lastActivitySec = r.goldLastActivitySec, volume01 = volume01[i],
            watchSec = r.watchSec, lastWatchMs = r.lastWatchMs, subscribed = r.subscribed,
        )
    }
    val nodes = TasteEmergentSource.buildNodes(
        specs, spX, spY, spZ, nowMs, maxWatch, nowMs / 1000L, warm,
    )
    // 互链真源→无向对称登记（buildLinks 口径）
    val interlink = HashMap<Long, HashMap<Long, Int>>(interlinkRaw.size * 2)
    for (l in interlinkRaw) {
        interlink.getOrPut(l.a) { HashMap(4) }[l.b] = l.weight
        interlink.getOrPut(l.b) { HashMap(4) }[l.a] = l.weight
    }
    // 双星束缚态（R283 宣判对——strong 标记，§3.3 强核力唯一现身）
    val strong = ArrayList<Pair<Long, Long>>(binaryPairs.size)
    for (p in binaryPairs) strong += p.a to p.b
    val eLinks = TasteEmergentSource.buildLinks(
        indexOf, nodes, interlink, tagFingerprints,
        { ia, ib -> if (ia != ib) target[ia * n + ib] else 0f }, strong,
    )
    // 定形：温启（缓存平衡位在手）=80 轮增量弛豫；冷启=800 轮退火（§6.5 条款三）
    val warmRun = !warm.isNullOrEmpty()
    val eParams = if (warmRun) {
        TasteEmergentSource.warmParams(TasteEmergentSource.PRODUCTION_PARAMS)
    } else {
        TasteEmergentSource.PRODUCTION_PARAMS
    }
    val result = TasteEmergentLayout.shape(nodes, eLinks, eParams)
    // 终态：温启走搬家帽（§6.9 单星单次 15% 银河半径）+帽后投影兜底；冷启直收
    val fin = if (warmRun) TasteEmergentSource.warmFinal(result, nodes, warm!!) else null
    val positions = fin?.positions ?: result.positions
    val eViolations = fin?.violations ?: result.violationsFinal
    val eR90 = fin?.r90 ?: result.r90
    val v = TasteEmergentSource.verdictOf(n, positions, eViolations, eR90, eLinks.size)
    var applied = false
    if (v.accept) {
        for (i in 0 until n) {
            spX[i] = positions[3 * i]
            spY[i] = positions[3 * i + 1]
            spZ[i] = positions[3 * i + 2]
        }
        applied = true
    }
    android.util.Log.i(
        "TasteMap",
        "R303c 涌现布局: 星=$n 对=${eLinks.size} 强对=${strong.size} 指纹频道=${tagFingerprints.size} " +
            "温启=$warmRun 轮=${result.rounds} 违约=$eViolations R90=${eR90.toInt()} " +
            "应力=%.3f 过闸=${v.accept} mode=${if (v.mode == TasteEmergentSource.Mode.EMERGED) "emerged" else "seed"}" +
            "${if (!v.accept) " 回退(${v.reason})" else ""} 耗时=${android.os.SystemClock.elapsedRealtime() - tEm}ms",
    )
    return applied
}


/**
 * 装配原料 → 星图成品：**高维口味向量的 3D 空间映射**（R152 方向纠偏版——用户原话
 * "不是这种的，非得搞一个星系漩涡背景，我理解更像一簇高维向量映射到三维空间"：
 * 位置由数据结构决定，旋臂/核球/球晕等一切造型手法废弃）。
 *
 *  1. 特征向量（每频道 [FEAT_DIMS] 维，全量本地数据零网络）：内容构成（视频/图文/
 *     音频占比——观看秒数构成 > 深度索引基因构成 > 内存条目投票三级回落）+ 时长风格
 *     （索引平均时长对数）+ 观看行为（累计时长对数、观看新鲜度 exp(-days/60)、互动
 *     次数对数）+ 体量（索引条数对数）+ 订阅位 + 频道规模（成员数对数）；
 *  2. 距离矩阵：加权后单位化取**余弦距离** √(2-2cos)（口味方向相近的频道距离小），
 *     O(n²) 一次算完（300²=9 万次，毫秒级）；三类构成证据全无的频道（没看过也没
 *     索引过）走"未知极"特判——与已知频道近乎满距 0.96、彼此松散小距 0.10~0.26
 *     （确定性配对哈希，聚成一团"未知"云不冒充任何口味）；
 *  3. 降维（纯 Kotlin 本地实现，**确定性**——绝不用随机初始化的 t-SNE，同数据每次
 *     打开位置不跳）：经典 MDS（Torgerson 双中心化 B=-0.5·J·D²·J + 幂迭代取前 3
 *     特征向量给初值）→ SMACOF 应力精修（Guttman 变换 [MDS_REFINE_ITERS] 轮，
 *     应力单调下降）——相似频道聚簇、相异远离，簇结构是数据结构的投影不是摆的；
 *  4. 归一：居中后按云团最大半径归到 [CLOUD_R]（离群频道如实远置——离群也是数据），
 *     x/y 平移到世界中心 (500,500)、z 居中 0；互链边照常按端点坐标烘焙（拓扑保留，
 *     只画线不参与定位）。
 *  5. **R223b 互链弹簧微扰**（[perturb]=设置开关「按互链关系聚拢」，默认开）：
 *     MDS 归一坐标为初值，[TasteGalaxyPerturb] 固定 20 轮弱弹簧+最小间距斥力（只动
 *     x/y——z 深度轴语义不动），收尾云团质心平移回世界中心；关=纯口味几何。
 *  6. **R223b 天体分档**：全池频道条数对数分位 → [TasteGalaxyClassifier] 六态判定
 *     （金矿证据缺据=恒星诚实缺省），与 StarNode 一起发布。
 * 布局日志带簇结构自检（按主导内容类型分簇的簇内离散 vs 簇心间距——定量证据）。
 */
private fun layoutTasteMap(
    assembly: MapAssembly,
    perturb: Boolean = true,
    // R254 阶段回调（首开进度文案驱动；null=旧行为零开销）
    onPhase: ((String) -> Unit)? = null,
    // R303c P2：涌现布局场接线（false=V3 降级位原样——AB 开关；warm=缓存平衡位
    // 温启初值，null=冷启 800 轮从 V3 松弛）
    emergent: Boolean = false,
    warm: Map<Long, FloatArray>? = null,
): TasteMapData {
    // R254 布局计时探针（MDS 各段耗时定位）
    val tLay = android.os.SystemClock.elapsedRealtime()
    val raws = assembly.raws
    if (raws.isEmpty()) {
        return TasteMapData(emptyList(), emptyList(), 160f, 0f, 0f, assembly.totalWatchSec, emptyList(), assembly.legacyPosterIds, emptyMap())
    }
    val n = raws.size
    val now = System.currentTimeMillis()

    // ---- 索引与边表（连线只用拓扑：端点坐标取嵌入结果） ----
    val indexOf = HashMap<Long, Int>(n * 2)
    raws.forEachIndexed { i, r -> indexOf[r.chatId] = i }
    val edgePairs = ArrayList<Pair<Int, Int>>(assembly.links.size)
    for (l in assembly.links) {
        val ia = indexOf[l.a] ?: continue
        val ib = indexOf[l.b] ?: continue
        edgePairs += ia to ib
    }

    // ---- 1) 特征向量（标准化 + 分块加权；行主序 n×FEAT_DIMS）----
    // 归一分母统一取对数尺度（压重尾：万小时频道与百秒频道在同一 0..1 尺度上比形状）；
    // max=0 的维度（演示模式无索引/无互动等）整体置 0——无方差维度不参与区分
    val feat = FloatArray(n * FEAT_DIMS)
    val hasComp = BooleanArray(n) // 有任何构成证据（进余弦距离），否则走未知极特判
    var maxAvgDur = 0L
    var maxWatch = 0L
    var maxInteract = 0
    var maxCount = 0
    var maxMember = 0
    for (r in raws) {
        maxAvgDur = maxOf(maxAvgDur, r.avgDurSec)
        maxWatch = maxOf(maxWatch, r.watchSec)
        maxInteract = maxOf(maxInteract, r.likeCount + r.favCount + r.danmakuCount)
        maxCount = maxOf(maxCount, r.idxCount)
        maxMember = maxOf(maxMember, r.memberCount)
    }
    for (i in 0 until n) {
        val r = raws[i]
        val base = i * FEAT_DIMS
        // 内容构成三级回落：观看秒数构成 > 索引基因条数构成 > 内存条目投票构成
        var cv = r.videoSec.toDouble()
        var cp = r.photoSec.toDouble()
        var ca = r.audioSec.toDouble()
        var ct = cv + cp + ca
        if (ct <= 0.0) {
            val gc = (r.gVideo + r.gPhoto + r.gAudio).toDouble()
            if (gc > 0.0) {
                cv = r.gVideo.toDouble(); cp = r.gPhoto.toDouble(); ca = r.gAudio.toDouble(); ct = gc
            }
        }
        if (ct > 0.0) {
            hasComp[i] = true
            feat[base + 0] = (cv / ct).toFloat()
            feat[base + 1] = (cp / ct).toFloat()
            feat[base + 2] = (ca / ct).toFloat()
        }
        feat[base + 3] = if (r.avgDurSec > 0 && maxAvgDur > 0) ln1p(r.avgDurSec.toFloat()) / ln1p(maxAvgDur.toFloat()) else 0f
        feat[base + 4] = if (r.watchSec > 0 && maxWatch > 0) ln1p(r.watchSec.toFloat()) / ln1p(maxWatch.toFloat()) else 0f
        // 新鲜度：从未看过 = 衰减到 0（参与方向——暗频道彼此方向相近自然成"未知/休眠"角）
        val days = if (r.lastWatchMs > 0) (now - r.lastWatchMs) / 86_400_000f else FRESH_DAYS * 12f
        feat[base + 5] = exp(-days / FRESH_DAYS)
        val inter = r.likeCount + r.favCount + r.danmakuCount
        feat[base + 6] = if (inter > 0 && maxInteract > 0) ln1p(inter.toFloat()) / ln1p(maxInteract.toFloat()) else 0f
        feat[base + 7] = if (r.idxCount > 0 && maxCount > 0) ln1p(r.idxCount.toFloat()) / ln1p(maxCount.toFloat()) else 0f
        feat[base + 8] = if (r.subscribed) 1f else 0f
        feat[base + 9] = if (r.memberCount > 0 && maxMember > 0) ln1p(r.memberCount.toFloat()) / ln1p(maxMember.toFloat()) else 0f
    }
    // 分块加权 + 单位化（余弦语义：向量长度归一，方向 = 口味）
    val unit = FloatArray(n * FEAT_DIMS)
    for (i in 0 until n) {
        if (!hasComp[i]) continue
        val base = i * FEAT_DIMS
        var norm = 0f
        for (d in 0 until FEAT_DIMS) {
            val wv = feat[base + d] * FEAT_WEIGHTS[d]
            unit[base + d] = wv
            norm += wv * wv
        }
        norm = sqrt(norm)
        if (norm > 1e-8f) for (d in 0 until FEAT_DIMS) unit[base + d] /= norm
    }

    // ---- 1.5) R267 数据源健康闸（塌方修复②：MDS 原料退化实锤——特征矩阵方差维
    //      不足=余弦距离矩阵恒同→B 恒 0→幂迭代退化→云团塌缩成点/线；批迁锁窗期
    //      基因扫描空/单值是真机实锤的触发面。检出≠拒绝：打上 sourceDegenerate
    //      标记随成品发布，调用方（runGalaxyLoadR254）据此重试；布局继续走完由
    //      6.5 的 θ 健康闸兜底成哈希方位——重试耗尽后用户仍拿到可看可飞的球面） ----
    val featVarDims = LayoutHealth.featureVarianceDims(feat, n, FEAT_DIMS)
    val sourceDegenerate = assembly.sourceDegenerate ||
        LayoutHealth.sourceDegenerate(featVarDims, n)
    if (sourceDegenerate) {
        android.util.Log.w(
            "TasteMap",
            "R267 数据源退化: 特征方差维=$featVarDims/${FEAT_DIMS} n=$n " +
                "装配闸=${assembly.sourceDegenerate}（批迁/迁移中间态实锤——等待一致快照重试）",
        )
    }

    // ---- 2) 距离矩阵（对称，对角 0；O(n²) 一次算完）----
    onPhase?.invoke("计算口味相似度矩阵…")
    val tFeat = android.os.SystemClock.elapsedRealtime()
    val target = FloatArray(n * n)
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val d: Float = when {
                hasComp[i] && hasComp[j] -> {
                    // 余弦距离 = 单位向量欧氏距离 √(2-2cos)——口味方向相近的频道距离小
                    var dot = 0f
                    val bi = i * FEAT_DIMS
                    val bj = j * FEAT_DIMS
                    for (k in 0 until FEAT_DIMS) dot += unit[bi + k] * unit[bj + k]
                    sqrt((2f - 2f * dot).coerceAtLeast(0f))
                }
                !hasComp[i] && !hasComp[j] -> {
                    // 未知极内群：松散小距（确定性配对哈希——同数据同布局，min/max 排序保证对称）
                    val lo = minOf(raws[i].chatId, raws[j].chatId)
                    val hi = maxOf(raws[i].chatId, raws[j].chatId)
                    val h = ((lo * 31L + hi).hashCode() and 0xFFFF).toFloat() / 65536f
                    0.10f + 0.16f * h
                }
                else -> 0.96f // 未知 vs 已知：近乎满距（没有相似证据就不冒充相近）
            }
            target[i * n + j] = d
            target[j * n + i] = d
        }
    }

    // ---- 3a) 经典 MDS 初值（Torgerson：B = -0.5·J·D²·J 双中心化，幂迭代取前 3 特征向量）----
    onPhase?.invoke("MDS 三维投影…")
    val tDist = android.os.SystemClock.elapsedRealtime()
    val b = FloatArray(n * n)
    run {
        val rowMean = FloatArray(n)
        var total = 0f
        for (i in 0 until n) {
            var s = 0f
            val row = i * n
            for (j in 0 until n) {
                val dd = target[row + j]
                s += dd * dd
            }
            rowMean[i] = s / n
            total += s
        }
        val totalMean = total / (n * n)
        for (i in 0 until n) {
            val row = i * n
            for (j in 0 until n) {
                val dd = target[row + j]
                b[row + j] = -0.5f * (dd * dd - rowMean[i] - rowMean[j] + totalMean)
            }
        }
    }
    // 幂迭代（确定性正弦种子；对已找到的向量做算子收缩 + Gram-Schmidt 双保险正交；
    // 符号按最大绝对分量取正钉死——任何一步都不含随机成分，同数据同结果）
    var X = Array(3) { FloatArray(n) }
    val eigVals = FloatArray(3)
    val found = ArrayList<FloatArray>(3)
    val bvec = FloatArray(n)
    for (k in 0 until 3) {
        var v = FloatArray(n) { sin(it * 0.7172f + k * 2.3999f) + 0.3f }
        var nv = 0f
        for (x in v) nv += x * x
        nv = sqrt(nv)
        for (i in v.indices) v[i] /= nv
        repeat(EIG_ITERS) {
            // bvec = B·v − Σ λ_m·u_m·(u_m·v)：收缩算子 Bₖ 的作用
            for (i in 0 until n) {
                var s = 0f
                val row = i * n
                for (j in 0 until n) s += b[row + j] * v[j]
                bvec[i] = s
            }
            for (m in found.indices) {
                val u = found[m]
                var dot = 0f
                for (i in 0 until n) dot += u[i] * v[i]
                val c = eigVals[m] * dot
                for (i in 0 until n) bvec[i] -= c * u[i]
            }
            // Gram-Schmidt 兜底正交（种子恰好落在已找到方向时兜住）
            for (u in found) {
                var dot = 0f
                for (i in 0 until n) dot += u[i] * bvec[i]
                for (i in 0 until n) bvec[i] -= dot * u[i]
            }
            var ny = 0f
            for (x in bvec) ny += x * x
            ny = sqrt(ny)
            if (ny < 1e-12f) return@repeat
            for (i in 0 until n) v[i] = bvec[i] / ny
        }
        // Rayleigh 特征值（对原始 B）+ 符号钉死 + 坐标 = √λ·v（λ<0 截 0：非欧几里得
        // 残余的负特征值不进坐标，第三维塌了 SMACOF 精修会重新展开）
        var lam = 0f
        for (i in 0 until n) {
            var s = 0f
            val row = i * n
            for (j in 0 until n) s += b[row + j] * v[j]
            lam += v[i] * s
        }
        eigVals[k] = lam
        var mi = 0
        for (i in 1 until n) if (abs(v[i]) > abs(v[mi])) mi = i
        if (v[mi] < 0f) for (i in 0 until n) v[i] = -v[i]
        found += v
        val sv = sqrt(lam.coerceAtLeast(0f))
        for (i in 0 until n) X[k][i] = v[i] * sv
    }

    // ---- 3b) SMACOF 应力精修（Guttman 变换，无权完全图唯一闭式解：
    // X⁺ᵢ = (1/n)·Σ_{j≠i} (−d_ij/dist_ij(X))·(Xⱼ−Xᵢ)，行和为零自动居中，应力单调降）----
    onPhase?.invoke("SMACOF 应力精修…")
    val tTorg = android.os.SystemClock.elapsedRealtime()
    val next = Array(3) { FloatArray(n) }
    val curDist = FloatArray(n * n)
    var cur = X
    var nxt = next
    repeat(MDS_REFINE_ITERS) {
        // 当前构型距离（对称缓存）
        for (i in 0 until n) {
            val row = i * n
            curDist[row + i] = 0f
            for (j in i + 1 until n) {
                val dx = cur[0][i] - cur[0][j]
                val dy = cur[1][i] - cur[1][j]
                val dz = cur[2][i] - cur[2][j]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                curDist[row + j] = d
                curDist[j * n + i] = d
            }
        }
        for (i in 0 until n) {
            var ax = 0f
            var ay = 0f
            var az = 0f
            val row = i * n
            for (j in 0 until n) {
                if (j == i) continue
                val cd = curDist[row + j]
                if (cd <= 1e-5f) continue // 重合点：d 也是 0 或极小，跳过不吹奇异项
                val w = -target[row + j] / cd
                ax += w * (cur[0][j] - cur[0][i])
                ay += w * (cur[1][j] - cur[1][i])
                az += w * (cur[2][j] - cur[2][i])
            }
            nxt[0][i] = ax / n
            nxt[1][i] = ay / n
            nxt[2][i] = az / n
        }
        val swap = cur // 双缓冲轮换：下一轮把结果写进旧缓冲，零分配
        cur = nxt
        nxt = swap
    }
    X = cur
    val tSmacof = android.os.SystemClock.elapsedRealtime()
    // ---- 4) 居中 + 归一到世界坐标（中心 500,500，z 居中 0）----
    // R152c 居中基准改为**有构成证据的口味云团质心**（不再是全体均值）：真数据下
    // 未知极占比不小，全体均值被拉向极点一侧——相机没有平移轴、世界中心即镜头焦点，
    // 云团被推到屏幕一角。把口味云团放到世界中心 = 默认取景满屏居中，极点在侧远方
    // 保持诚实距离（位置相对关系不变，只挪坐标原点）
    var mx = 0f
    var my = 0f
    var mz = 0f
    var m = 0
    for (i in 0 until n) {
        if (!hasComp[i]) continue
        mx += X[0][i]; my += X[1][i]; mz += X[2][i]; m++
    }
    if (m == 0) {
        for (i in 0 until n) { mx += X[0][i]; my += X[1][i]; mz += X[2][i] }
        m = n
    }
    mx /= m; my /= m; mz /= m
    var maxR = 1e-4f
    for (i in 0 until n) {
        X[0][i] -= mx; X[1][i] -= my; X[2][i] -= mz
        val rr = sqrt(X[0][i] * X[0][i] + X[1][i] * X[1][i] + X[2][i] * X[2][i])
        if (rr > maxR) maxR = rr
    }
    val s = CLOUD_R / maxR
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)
    var minZ = Float.MAX_VALUE
    var maxAbsZ = 1f
    for (i in 0 until n) {
        x[i] = WORLD / 2f + X[0][i] * s
        y[i] = WORLD / 2f + X[1][i] * s
        z[i] = X[2][i] * s
        if (z[i] < minZ) minZ = z[i]
        if (abs(z[i]) > maxAbsZ) maxAbsZ = abs(z[i])
    }
    val zSpan = maxAbsZ.coerceAtLeast(40f)
    // 基准面网格：覆盖云团足迹、贴在**口味云团**底部下方（参照平面跟数据主体走，
    // 不被未知极的深度拖走；退化时按 CLOUD_R 兜底）
    val gridHalf = CLOUD_R
    val gridZ = minOf(minZ, -0.15f * CLOUD_R) - 0.06f * CLOUD_R

    // ---- 5) R223b 互链弹簧微扰（默认开；固定 20 轮+固定初值=确定性不破，z 不动） ----
    onPhase?.invoke("互链聚拢与聚类展开…")
    val tNorm = android.os.SystemClock.elapsedRealtime()
    val perturbRms = if (perturb && edgePairs.isNotEmpty()) {
        val edgesFlat = IntArray(edgePairs.size * 2)
        for (e in edgePairs.indices) {
            edgesFlat[e * 2] = edgePairs[e].first
            edgesFlat[e * 2 + 1] = edgePairs[e].second
        }
        val rms = TasteGalaxyPerturb.perturbXy(x, y, n, edgesFlat, edgePairs.size, hasComp)
        // 微扰后云团质心回世界中心（纯平移不重标定——取景稳定；与 MDS 归一同口径）
        TasteGalaxyPerturb.recenterCloud(x, y, n, hasComp, WORLD / 2f, WORLD / 2f)
        rms
    } else {
        -1f // 关（纯口味几何）/无边：诊断哨兵
    }

    // ---- 5.5) R237 聚类自适应展开（真机实锤③「大部分星系聚拢在一起」的修法：
    //      MDS 余弦距离天然紧核——均匀缩放救不了密度；判定量=星体间距（中位最近
    //      邻÷中位星体半径——用户实锤原文口径），径向次线性重映射 r'=R·(r/R)^k
    //      只展开核、离群星原地、簇结构保序（TasteSpreadExpand 纯函数单口）。
    //      全体星共用同一 k——渲染/命中/连线/行星/弧/尘埃带/星轨/星云/海报全吃
    //      同一 x/y 单一来源（双口径 z 不动）；展开后再回世界中心） ----
    val spreadBodyR = FloatArray(n) { raws[it].bodyRadius }
    val spreadK = TasteSpreadExpand.apply(x, y, n, hasComp, spreadBodyR, WORLD / 2f, WORLD / 2f)
    if (spreadK < 1f) {
        TasteGalaxyPerturb.recenterCloud(x, y, n, hasComp, WORLD / 2f, WORLD / 2f)
    }

    // ---- 6) R231 三带纵深重建（App 口径 flightZ；XY/MDS z 一概不动） ----
    // 微扰（只动 x/y）之后取最终 xy 做椭球渐缩；zMds 只取序（λ3 塌缩不影响
    // 带内均匀性）；活跃度=观看时刻∨内容活跃时刻双源取强（常看的星偏近带）
    onPhase?.invoke("三带纵深与球面化…")
    val tPert = android.os.SystemClock.elapsedRealtime()
    val flightZ = FloatArray(n)
    val chatIdArr = LongArray(n) { raws[it].chatId }
    val actMs = LongArray(n) {
        maxOf(raws[it].lastWatchMs, raws[it].goldLastActivitySec * 1000L)
    }
    val bands = TasteDepthSpread.spread(n, z, chatIdArr, x, y, actMs, flightZ)

    // ---- 6.5) R256 V3 均匀球面分配（用户第 6 次拍板：**换方法**——扔掉 MDS xy
    //      推导的带复原/椭球渐缩/高度带整链）：θ=口味角（团簇=团均值口味角+成员
    //      角序偏移压缩 0.4、野星=自身口味角——**相似频道分到相近方向**，口味→方向
    //      是旧链唯一保留语义）；φ=chatId 哈希独立均匀 [-90°,+90°]（**跟口味无关
    //      ——均匀铺满天球**：抬头/低头/四周都有星=3D 包围感）；r=体量对数分位反比
    //      （大频道近 400~800/小频道远 2000~6000——近大远小的深度线索即数据量）；
    //      初始相机=球心=世界原点（你在星星中间）。旧 spherify/spherifyClustered
    //      原样保留（旧口径+存量单测锚定，布局不再消费）；海报继续消费 x/y/z 旧
    //      坐标=零回归 ----
    val volItems = IntArray(n)
    for (i in 0 until n) {
        volItems[i] = maxOf(raws[i].idxCount, raws[i].goldItems)
    }
    val volume01 = FloatArray(n) { TasteGalaxyClassifier.volumeRankOf(volItems[it], volItems) }
    val spX = FloatArray(n)
    val spY = FloatArray(n)
    val spZ = FloatArray(n)
    var blobCount = 0
    // R267 布局健康闸状态（6.6 段产出——成品字段原料）：θ 数组先算后判再球面化
    val thetaArr = FloatArray(n)
    var thetaUniqueN = -1
    var coordUniqueN = -1
    var layoutDegraded = false
    run {
        val spTmp = FloatArray(3)
        // 团分桶（口味空间 240 单位格；键=奇素数交织——R252 修法保留：每格独立团锚）
        val cellKeyOf = LongArray(n)
        val cellIndex = HashMap<Long, Int>(64)
        val sumX = ArrayList<Float>(16)
        val sumY = ArrayList<Float>(16)
        val cnt = ArrayList<Int>(16)
        val cluKey = ArrayList<Long>(16)
        for (i in 0 until n) {
            val cx = Math.floor(x[i] / 240.0).toLong()
            val cy = Math.floor(y[i] / 240.0).toLong()
            val key = cx * 0x85EBCA6BL + cy * 0xC2B2AE35L
            cellKeyOf[i] = key
            val ci = cellIndex.getOrPut(key) {
                sumX += 0f; sumY += 0f; cnt += 0; cluKey += key
                sumX.size - 1
            }
            sumX[ci] = sumX[ci] + x[i]
            sumY[ci] = sumY[ci] + y[i]
            cnt[ci] = cnt[ci] + 1
        }
        // 团锚（确定性：均值口味角——R256 后团高度/径向槽两口径随 spherifyClustered
        // 废弃，φ/r 全在 spherifyV3 内按 chatId/体量独立分配）
        val cluAz = FloatArray(sumX.size)
        for (ci in sumX.indices) {
            val mx = sumX[ci] / cnt[ci]
            val my = sumY[ci] / cnt[ci]
            // 方位约定与旧 spherify 同式：口味 y 向下取反为北
            cluAz[ci] = atan2(-(my - 500f), mx - 500f).toFloat()
        }
        for (i in 0 until n) {
            val blob = TasteSpace.h01(chatIdArr[i], 45) < 0.65f
            // θ：团簇=团均值口味角+成员角序偏移（团簇用口味角+独立 φ）；野星=自身口味角
            thetaArr[i] = if (blob) {
                val ci = cellIndex[cellKeyOf[i]]!!
                // 成员角序偏移：自身口味角相对团角的差压缩 0.4（团内口味角序保留）
                val ownAz = atan2(-(y[i] - 500f), x[i] - 500f).toFloat()
                var off = ownAz - cluAz[ci]
                while (off > 3.1415926f) off -= 6.2831853f
                while (off < -3.1415926f) off += 6.2831853f
                off = (off * 0.4f).coerceIn(-0.3f, 0.3f)
                cluAz[ci] + off
            } else {
                atan2(-(y[i] - 500f), x[i] - 500f).toFloat()
            }
            if (blob) blobCount++
        }
        // ---- 6.6) R267 布局健康闸（塌方修复①——真机实锤：MDS 原料退化→云团塌中心/
        //      一维→θ 唯一值≤2→全天体共面 z≈0→投影成屏幕中央一条竖线；模拟器数据
        //      丰满不塌=数据依赖型，机检全绿兜不住）。检测=θ 量化 64 桶唯一数≤2 或
        //      xy 16u 格唯一数≤2；处置=θ 全体回退纯 chatId 哈希方位（破零保球面展开，
        //      口味方向语义本轮牺牲——任务书裁决「保证不成一条线」）+ layoutDegraded
        //      标记（退化品绝不写成品缓存）+探针实锤。健康时零改动零成本（O(n) 扫） ----
        thetaUniqueN = LayoutHealth.thetaUniqueCount(thetaArr, n)
        coordUniqueN = LayoutHealth.coordUniqueCount(x, y, n)
        layoutDegraded = LayoutHealth.layoutDegenerate(thetaUniqueN, coordUniqueN)
        // 诊断探针（常驻）：口味云几何幅员——θ 塌缩定位的一手证据（薄片云/偏心云实锤）
        run {
            var xMin = Float.MAX_VALUE
            var xMax = -Float.MAX_VALUE
            var yMin = Float.MAX_VALUE
            var yMax = -Float.MAX_VALUE
            for (i in 0 until n) {
                if (x[i] < xMin) xMin = x[i]
                if (x[i] > xMax) xMax = x[i]
                if (y[i] < yMin) yMin = y[i]
                if (y[i] > yMax) yMax = y[i]
            }
            android.util.Log.i(
                "TasteMap",
                "R267 云几何: x幅=[${xMin.toInt()}..${xMax.toInt()}] y幅=[${yMin.toInt()}..${yMax.toInt()}] " +
                    "（中心=500,500 参照）",
            )
        }
        if (layoutDegraded) {
            for (i in 0 until n) thetaArr[i] = LayoutHealth.fallbackTheta(chatIdArr[i])
            android.util.Log.w(
                "TasteMap",
                "R267 布局健康闸: 退化实锤 θ唯一桶=$thetaUniqueN/${LayoutHealth.THETA_BUCKETS} " +
                    "坐标格=$coordUniqueN n=$n → θ 回退纯哈希方位（球面展开保住，口味方向语义本轮牺牲；" +
                    "本布局不落盘不钉死）",
            )
        } else {
            android.util.Log.i(
                "TasteMap",
                "R267 布局健康闸: 健康 θ唯一桶=$thetaUniqueN 坐标格=$coordUniqueN n=$n",
            )
        }
        for (i in 0 until n) {
            TasteSpace.spherifyV3(thetaArr[i], chatIdArr[i], volume01[i], spTmp)
            spX[i] = spTmp[0]
            spY[i] = spTmp[1]
            spZ[i] = spTmp[2]
        }
    }

    // ---- 7) R223b 天体分档（频道六态：体量分位+金矿证据 → 纯函数判定；
    //      R249：分位留数组——黑洞晋升三重门的原料。R256：volItems/volume01 已
    //      前移至 6.5（V3 半径原料），此处原位消费） ----
    onPhase?.invoke("天体分档与星系合成…")
    val tSph = android.os.SystemClock.elapsedRealtime()
    // 全库正体量均值（黑洞科普倍数行+均值门原料；0=无统计=诚实跳过晋升）
    val meanItems = run {
        var sum = 0L
        var cnt = 0
        for (v in volItems) {
            if (v > 0) {
                sum += v
                cnt++
            }
        }
        if (cnt > 0) sum.toFloat() / cnt else 0f
    }
    val kinds = Array(n) { CelestialKind.STAR }
    val rank01Arr = FloatArray(n)
    for (i in 0 until n) {
        val r = raws[i]
        rank01Arr[i] = TasteGalaxyClassifier.volumeRankOf(volItems[i], volItems)
        kinds[i] = r.entityKind ?: TasteGalaxyClassifier.classifyChannel(
            TasteGalaxyClassifier.ChannelEvidence(
                items = volItems[i],
                lastActivitySec = r.goldLastActivitySec,
                recent7 = r.goldRecent7,
                prev7 = r.goldPrev7,
                firstSeenMs = r.firstSeenMs,
                volumeRank01 = rank01Arr[i],
            ),
            now,
        )
    }
    // ---- 7.5) R249 黑洞晋升（奥本海默三重门：P95 分位 ∧ 均值×8 ∧ ≥800 条；
    //      六态判据矩阵一字不动——合格超大频道按体量降序晋升 top≤4；质量终态盖过
    //      瞬态/稳态：爆发中的黑洞=吸积盛宴，死寂的黑洞=静默视界） ----
    var blackHoleCount = 0
    if (meanItems > 0f) {
        val candidates = ArrayList<Int>(16)
        for (i in 0 until n) {
            if (raws[i].entityKind != null) continue // 实体天体不晋升（黑洞=频道终态）
            if (TasteAstroPhysics.isBlackHoleCandidate(volItems[i], rank01Arr[i], meanItems)) {
                candidates += i
            }
        }
        for (i in candidates.sortedByDescending { volItems[it] }.take(TasteAstroPhysics.MAX_BLACK_HOLES)) {
            kinds[i] = CelestialKind.BLACK_HOLE
            blackHoleCount++
        }
    }
    // ---- 7.6) R249 双星对（R283 维度化：BinaryStarPolicy 政策件单一口——互转/互链/
    //      共现三维图谱计数 + 内容同源/时间跨度底座增强；强证据门 ∧ 维度分门；
    //      ≤6 对、每星只入一对、实体天体剔除；量纲校准见政策件 KDoc；
    //      R321 附加账附着（洛希同源 W/特洛伊共源 W——BinaryPairExtra 附加账线性
    //      配对（≤24×≤6 微成本），缺据 0=行不出/簇零渲染诚实降级） ----
    val binaryPairs = ArrayList<BinaryPair>(MAX_BINARIES)
    run {
        val eligible: (Long) -> Boolean = { id -> indexOf[id]?.let { raws[it].entityKind == null } == true }
        val extras = assembly.binaryExtras
        val exOf = HashMap<Long, BinaryPairExtra>(extras.size * 2)
        for (e in extras) {
            exOf[e.a] = e
            exOf[e.b] = e
        }
        for (v in BinaryStarPolicy.selectBinaryPairs(assembly.binaryEvidence, MAX_BINARIES, eligible)) {
            // R321 附加账附着（同对双向查表——a/b 任一端命中且对端一致即取）
            val ex = exOf[v.a] ?: exOf[v.b]
            val matched = ex != null && (ex.a == v.a && ex.b == v.b || ex.a == v.b && ex.b == v.a)
            binaryPairs += BinaryPair(
                v.a, v.b, v.mergedWeight,
                sharedW = if (matched) ex!!.sharedW else 0,
                trojanW = if (matched) ex!!.trojanW else 0,
            )
        }
        // R321 装配探针（r315-D⑤/I⑤ 验证单口径——脱敏只出计数与中位）
        val topShared = binaryPairs.maxOfOrNull { it.sharedW } ?: 0
        val sharedList = binaryPairs.map { it.sharedW }.filter { it > 0 }.sorted()
        val trojList = binaryPairs.map { it.trojanW }.filter { it > 0 }.sorted()
        android.util.Log.i(
            "TasteMapX",
            "R321 洛希: 双星对=${binaryPairs.size} 撞车档=${binaryPairs.count { RocheTidalFx.warningSharedW(it.sharedW, topShared) > 0 }}" +
                "（同源分中位=${if (sharedList.isEmpty()) 0 else sharedList[sharedList.size / 2]}）",
        )
        android.util.Log.i(
            "TasteMapX",
            "R321 拉格朗日: 有共源双星对=${trojList.size}/总对=${binaryPairs.size}" +
                "（共源证据中位=${if (trojList.isEmpty()) 0 else trojList[trojList.size / 2]}）",
        )
    }
    val kindCounts = HashMap<CelestialKind, Int>()
    for (k in kinds) kindCounts.merge(k, 1, Int::plus)

    // ---- 7.7) R303c P2 涌现布局场（**位置真源换轨**：四力平衡位过裁决=成为
    //      spX/Y/Z 真源——渲染/命中/运镜/井深比/尘埃带/星轨全消费这一份；V3 段
    //      保留=初值+降级位双职责，拍板项⑦「V3 常驻降级位」）。定形体收口顶层
    //      [applyEmergentLayoutR303c]（装配走独立方法——R315 工程约束口径；装配期
    //      离帧 Default 线程，帧路径零新增成本）。布局退化/数据源退化（R267 重试
    //      环管辖）不进涌现段——V3 兜底原样。 ----
    var emergentApplied = false
    if (emergent && !layoutDegraded && !sourceDegenerate && n >= 8) {
        emergentApplied = applyEmergentLayoutR303c(
            n, raws, volItems, volume01, spX, spY, spZ, indexOf, target,
            assembly.links, assembly.tagFingerprints, binaryPairs, warm, now, onPhase,
        )
    }

    // ---- 度数与成品合成 ----
    val degree = IntArray(n)
    for ((a, b) in edgePairs) {
        degree[a]++
        degree[b]++
    }
    val stars = raws.mapIndexed { i, r ->
        StarNode(
            chatId = r.chatId,
            title = r.title,
            watchSec = r.watchSec,
            lastWatchMs = r.lastWatchMs,
            videoSec = r.videoSec,
            photoSec = r.photoSec,
            audioSec = r.audioSec,
            memberCount = r.memberCount,
            subscribed = r.subscribed,
            degree = degree[i],
            colorArgb = r.colorArgb,
            alpha = r.alpha,
            radius = r.radius,
            label = r.label,
            likeCount = r.likeCount,
            favCount = r.favCount,
            danmakuCount = r.danmakuCount,
            hasComp = hasComp[i],
            x = x[i],
            y = y[i],
            z = z[i],
            flightZ = flightZ[i],
            // ---- R242 空间口径（App 全消费方：球面化宇宙坐标） ----
            spX = spX[i],
            spY = spY[i],
            spZ = spZ[i],
            firstSeenMs = r.firstSeenMs,
            // ---- R223b 全宇宙天体档 ----
            kind = kinds[i],
            tier = r.goldTier,
            itemsCount = volItems[i],
            lastActivitySec = r.goldLastActivitySec,
            recent7 = r.goldRecent7,
            prev7 = r.goldPrev7,
            bodyRadius = r.bodyRadius,
            bodyAlpha = r.bodyAlpha,
            bodyColor = r.bodyColor,
            tempK = r.tempK, // R311：连续色温（渲染端芯/缘色导出原料）
            // R235 未探索星云原料（索引基因构成三票）
            gVideo = r.gVideo,
            gPhoto = r.gPhoto,
            gAudio = r.gAudio,
            // R250 个人足迹（条目级看过条数）
            watchedCount = r.watchedCount,
        )
    }
    // 连线烘焙端点坐标/深度与透明度（对数归一，重边更实；a/b 留给邻居高亮）
    // R242 双口径：x/y/z=海报口径（MDS）；sp*=空间口径（App 投影消费）
    val maxWeight = assembly.links.maxOfOrNull { it.weight } ?: 1
    val links = assembly.links.mapNotNull { l ->
        val ia = indexOf[l.a] ?: return@mapNotNull null
        val ib = indexOf[l.b] ?: return@mapNotNull null
        val w = l.weight.toFloat()
        val alpha = 0.08f + 0.42f * (ln1p(w) / ln1p(maxWeight.toFloat()))
        StarLink(
            x[ia], y[ia], x[ib], y[ib], z[ia], z[ib],
            spX[ia], spY[ia], spZ[ia], spX[ib], spY[ib], spZ[ib],
            l.a, l.b, alpha,
        )
    }

    // ---- 簇结构自检（布局日志的定量证据：按主导内容类型分簇的簇内离散 vs 簇心间距——
    // 比值明显 <1 即簇结构成立，数据分布聚簇而非均匀散点/圆环）----
    val clusterReport = run {
        val groups = HashMap<Int, ArrayList<Int>>()
        for (i in 0 until n) {
            if (!hasComp[i]) continue
            val base = i * FEAT_DIMS
            val dom = when {
                feat[base] >= feat[base + 1] && feat[base] >= feat[base + 2] -> 0
                feat[base + 1] >= feat[base + 2] -> 1
                else -> 2
            }
            groups.getOrPut(dom) { ArrayList() }.add(i)
        }
        val names = arrayOf("视频", "图文", "音频")
        val centroids = groups.mapValues { (_, idxs) ->
            floatArrayOf(
                idxs.map { x[it] }.average().toFloat(),
                idxs.map { y[it] }.average().toFloat(),
                idxs.map { z[it] }.average().toFloat(),
            )
        }
        val parts = groups.entries.sortedBy { it.key }.mapNotNull { (type, idxs) ->
            if (idxs.size < 2 || !centroids.containsKey(type)) return@mapNotNull null
            val c = centroids[type]!!
            var ss = 0f
            for (i in idxs) {
                val dx = x[i] - c[0]; val dy = y[i] - c[1]; val dz = z[i] - c[2]
                ss += dx * dx + dy * dy + dz * dz
            }
            "${names[type]}${idxs.size}簇rms=${sqrt(ss / idxs.size).toInt()}"
        }
        var minGap = Float.MAX_VALUE
        val cs = centroids.values.toList()
        for (i in cs.indices) for (j in i + 1 until cs.size) {
            val dx = cs[i][0] - cs[j][0]; val dy = cs[i][1] - cs[j][1]; val dz = cs[i][2] - cs[j][2]
            minGap = minOf(minGap, sqrt(dx * dx + dy * dy + dz * dz))
        }
        val known = hasComp.count { it }
        val unknown = n - known
        (if (parts.isEmpty()) "单簇" else parts.joinToString(" ")) +
            (if (cs.size >= 2) " 最小簇心距=${minGap.toInt()}" else "") +
            " 已知构成=$known 未知极=$unknown"
    }
    android.util.Log.i(
        "TasteMap",
        "R254 布局计时: 总=${android.os.SystemClock.elapsedRealtime() - tLay}ms n=$n " +
            "特征=${tFeat - tLay} 距离矩阵=${tDist - tFeat} torgerson幂迭代=${tTorg - tDist} " +
            "smacof${MDS_REFINE_ITERS}轮=${tSmacof - tTorg} 归一=${tNorm - tSmacof} " +
            "微扰展开=${tPert - tNorm} 三带球面化=${tSph - tPert} 分档合成=${android.os.SystemClock.elapsedRealtime() - tSph}",
    )
    android.util.Log.i(
        "TasteMap",
        "R223b 布局完成: 天体=$n 连线=${links.size} MDS(torgerson幂迭代${EIG_ITERS}x3+smacof${MDS_REFINE_ITERS}轮) " +
            "特征值=(${eigVals[0].toInt()},${eigVals[1].toInt()},${eigVals[2].toInt()}) 簇结构: $clusterReport " +
            "微扰=${if (perturbRms >= 0f) "开(末轮RMS=${perturbRms.toInt()})" else "关"} " +
            "R237聚类展开=${if (spreadK < 1f) "k=" + "%.3f".format(spreadK) else "未触发（云团舒展）"} " +
            "天体分档=${kindCounts.entries.sortedBy { it.key.ordinal }.joinToString(" ") { "${TasteGalaxyClassifier.labelOf(it.key)}=${it.value}" }} " +
            "zSpan=$zSpan gridZ=${gridZ.toInt()} " +
            "R256球面V3: θ=口味角（团簇${(blobCount * 100 / n)}%+野星${100 - blobCount * 100 / n}%） " +
            "φ=独立均匀(-90°,90°) r=体量分位[400,6000]（大频道近小频道远；三带口径废弃，flightZ 留作星云聚类原料） " +
            "R249物理: 黑洞晋升=$blackHoleCount（三重门 均值=${"%.0f".format(meanItems)}） " +
            "双星=${binaryPairs.size}（R283 维度分≥${BinaryStarPolicy.MIN_SCORE} top${MAX_BINARIES}） " +
            "团块化=${if (blobCount > 0) "${(blobCount * 100 / n)}%浓核+${100 - blobCount * 100 / n}%野星" else "0（小池退化全野星）"} " +
            "R267健康: θ桶=$thetaUniqueN 格=$coordUniqueN 方差维=$featVarDims " +
            "退化=${if (layoutDegraded) "布局" else ""}${if (sourceDegenerate) "源" else ""}" +
            "${if (!layoutDegraded && !sourceDegenerate) "无" else ""} 基因指纹=${assembly.genomeFp}",
    )
    // ---- R248 相邻恒星关联账（合并边权双向登记，权重降序——信息卡「相邻恒星
    //      ★A（W 条关联）·点=飞过去」的数据源；与连线同源（留重后集合一致）） ----
    val neighborArcs = HashMap<Long, ArrayList<NeighborArc>>(assembly.links.size * 2)
    for (l in assembly.links) {
        neighborArcs.getOrPut(l.a) { ArrayList(4) }.add(NeighborArc(l.b, l.weight))
        neighborArcs.getOrPut(l.b) { ArrayList(4) }.add(NeighborArc(l.a, l.weight))
    }
    neighborArcs.values.forEach { list -> list.sortByDescending { it.weight } }

    return TasteMapData(
        stars, links, zSpan, gridHalf, gridZ, assembly.totalWatchSec,
        assembly.todayTrail, assembly.legacyPosterIds, kindCounts, assembly.todayStops,
        neighborArcs, meanItems, binaryPairs, assembly.rels,
        // R267 健康三字段：退化品随成品发布（调用方据此不落盘/重试/不换装）
        layoutDegraded = layoutDegraded,
        sourceDegenerate = sourceDegenerate,
        genomeFp = assembly.genomeFp,
        // R303c P2：涌现在场标记（缓存头口径+温启初值合法性判定原料）
        emergent = emergentApplied,
    )
}

// ==================== 海报数据（IO） ====================

/**
 * 成品星图 → ShareCard 海报数据（**R223b 零回归红线件**）：
 *  - **内容集合=旧三源封闭池**（[TasteMapData.legacyPosterIds]：watch>订阅>图谱补齐
 *    300 同序重算）——全宇宙扩容不进海报（星系版海报=三期拍板项）；
 *  - **口径=旧观看口径**（radius/alpha/colorArgb——见 MapStarRaw 两套口径 KDoc），
 *    ShareCard.generateTasteMap 渲染管线与图例零改动；
 *  - 坐标随全宇宙 MDS 更新（同一布局引擎同特征向量，簇结构同构）——同一「相机」
 *    对同一口味云团的新一次拍照，这是布局引擎的固有全局性。
 * 标题区 + 图例（名签只给头部 3 个频道，口径与 R206 相同）。
 */
private fun buildPosterData(d: TasteMapData): ShareCard.TasteMapCardData {
    // 旧口径池过滤（空集=纯观看/订阅小图（演示模式），等价旧行为全量直通）
    val posterStars = if (d.legacyPosterIds.isEmpty()) d.stars else d.stars.filter { it.chatId in d.legacyPosterIds }
    val posterEdges = if (d.legacyPosterIds.isEmpty()) d.links else {
        // 边两端都在旧池才进海报（旧池内边=旧三源子图的边——与 R206 同构）
        d.links.filter { it.a in d.legacyPosterIds && it.b in d.legacyPosterIds }
    }
    val labeled = posterStars
        .filter { it.watchSec > 0 && it.title.isNotBlank() }
        .sortedByDescending { it.watchSec }
        .take(3)
        .mapTo(HashSet()) { it.chatId }
    return ShareCard.TasteMapCardData(
        headline = "我的口味星图",
        subLine = "${posterStars.size} 个频道 · ${posterEdges.size} 条互链 · 累计观看 ${fmtWatch(d.totalWatchSec)}",
        stars = posterStars.map { s ->
            ShareCard.TasteMapStar(s.x, s.y, s.z, s.radius, s.colorArgb, s.alpha, s.title.takeIf { s.chatId in labeled }, s.chatId, s.hasComp)
        },
        edges = posterEdges.map { ShareCard.TasteMapEdge(it.x1, it.y1, it.x2, it.y2, it.z1, it.z2, it.a, it.b, it.alpha) },
        zSpan = d.zSpan,
        gridHalf = d.gridHalf,
        gridZ = d.gridZ,
        legend = listOf(
            ShareCard.TasteMapLegendEntry("视频", TYPE_VIDEO),
            ShareCard.TasteMapLegendEntry("图文", TYPE_PHOTO),
            ShareCard.TasteMapLegendEntry("音频", TYPE_AUDIO),
            ShareCard.TasteMapLegendEntry("没看过的频道", TYPE_DIM),
        ),
        brandLine = "TGFeed · 口味星图 · ${SimpleDateFormat("M月d日").format(Date())}",
    )
}

/**
 * R322 成品星图 → TastePosterV2 海报数据（M 章设计——「你此刻宇宙的电影剧照+
 * 观测报告」，导出按钮换轨 V2 的组装侧）：
 *  - **内容集合=全宇宙**（当前视角快照——与星图本体同数据源；V1 的旧三源
 *    封闭池口径随 V1 一并退役留库）；
 *  - **口径=真渲染口径**：sp 三轴+cam/lens 当前视角重投影（TasteVoyagePoster
 *    同机制）×R311 连续色温（tempK 三段色同源导出）×bodyRadius 体量半径
 *    （旧 radius/colorArgb/alpha 字段保留 StarNode 不删——V1 消费面零动）；
 *  - 特殊天体（黑洞/中子/夸克/白洞）带 recent7 喂进食档与形状参数单源
 *    （R317b TasteTerminalFx 海报静态版——徽章行宣判天体与星点签名同口径）；
 *  - 光年行+徽章行=TastePosterV2 纯函数组装（单测锚位，缺据诚实缺省）；
 *    光年行 nowMs 用当前钟（IO 线程取时刻=快照时间戳，非渲染期口径）；
 *  - 名签=头部 3 频道（V1 同口径：watchSec 降序+标题非空；投影像素 ≥7f
 *    才挂签在 V2 绘制端判——小星不进候选与 V1 两遍制同构）。
 */
private fun buildPosterDataV2(ctx: GalaxyLogicCtx, d: TasteMapData): TastePosterV2.V2CardData {
    val labeled = d.stars
        .filter { it.watchSec > 0 && it.title.isNotBlank() }
        .sortedByDescending { it.watchSec }
        .take(3)
        .mapTo(HashSet()) { it.chatId }
    // 频道恒星数（实体档不计——TasteVoyagePoster starCount 同口径）
    val channelStars = d.stars.count {
        it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
    }
    val span = TasteFlight.genesisSpan(d.stars.map { it.firstSeenMs })
    return TastePosterV2.V2CardData(
        stars = d.stars.map { s ->
            TastePosterV2.V2Star(
                s.spX, s.spY, s.spZ, s.bodyRadius, s.bodyColor, s.tempK, s.kind,
                s.bodyAlpha, s.chatId, s.recent7, s.title.takeIf { s.chatId in labeled },
            )
        },
        cam = ctx.cam.value,
        focalPxDevice = TasteSpace.focalOf(
            ctx.canvasSize.value.width.toFloat().coerceAtLeast(1f),
            ctx.canvasSize.value.height.toFloat().coerceAtLeast(1f),
        ) * ctx.lens.value,
        deviceCanvasW = ctx.canvasSize.value.width.coerceAtLeast(1),
        subLine = "$channelStars 个频道 · ${d.links.size} 条互链 · 累计观看 ${fmtWatch(d.totalWatchSec)}",
        lightYearLine = TastePosterV2.lightYearLineOf(span, System.currentTimeMillis()),
        badges = TastePosterV2.badgeChipsOf(d.kindCounts, channelStars),
        methodNote = "球面宇宙投影 · 星色=黑体色温谱×内容构成 · 点大小=体量半径 · 与星图同源口径",
        brandLine = "TGFeed · 口味星图 · ${SimpleDateFormat("M月d日").format(Date())}",
    )
}

// ==================== 小工具 ====================

/** top 星提亮（R152c 降饱和口径）：头部频道亮度抬到标志性档（饱和保持柔光档不放大）——
 *  头部频道在色海里一眼锁定，但不出戏 */
private fun boostStarColor(argb: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[1] = hsv[1].coerceAtMost(0.60f)
    hsv[2] = maxOf(hsv[2], 0.90f)
    return android.graphics.Color.HSVToColor(hsv)
}

/** 观看时长人性化：≥1h "X 小时 Y 分钟" / ≥1min "X 分钟" / 否则 "X 秒"（与观看报告同款文案） */
private fun fmtWatch(sec: Long): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    return when {
        h > 0 -> "$h 小时 $m 分钟"
        m > 0 -> "$m 分钟"
        else -> "$sec 秒"
    }
}

/** 成员数人性化：≥1 万显示 "x.x 万" */
private fun fmtMember(count: Int): String =
    if (count >= 10000) "%.1f 万".format(count / 10000f) else "$count"

/**
 * R223b 天体档案行（信息卡「天体档案」段；设计 §3.4）：
 * 频道=类型+体量+断更/爆发时长+tier 验货徽章（「按本地索引观测」诚实口径）；实体=
 * 类型+规模注解。缺据字段不出（没有证据不臆测），全部输出脱敏（只有计数无频道名）。
 * R249：黑洞分支（存量+奥本海默倍数——[meanItems] 由调用方传入，0=缺据降级）。
 */
private fun buildCelestialArchiveLine(st: StarNode, nowMs: Long, meanItems: Float = 0f): String = buildString {
    append("天体档案：${TasteGalaxyClassifier.labelOf(st.kind)}")
    when (st.kind) {
        CelestialKind.PULSAR -> append(" · bot 应答服务")
        CelestialKind.CLUSTER -> {
            if (st.memberCount > 0) append(" · 群组 · 成员 ${fmtMember(st.memberCount)}")
            else append(" · 群组")
            if (st.itemsCount > 0) append(" · 存量 ${fmtItems(st.itemsCount)}")
        }
        CelestialKind.METEOR -> append(" · 私聊用户 · 游荡在星系边缘（无内容索引）")
        CelestialKind.BLACK_HOLE -> {
            // R249 黑洞：超大聚合频道的质量终态（视界+吸积盘渲染；科普行在紧随其后）
            if (st.itemsCount > 0) append(" · 存量 ${fmtItems(st.itemsCount)}")
            if (meanItems > 0f && st.itemsCount > 0) {
                append(" · 全库均值的 ${"%.1f".format(st.itemsCount / meanItems)} 倍")
            }
            append(" · 事件视界已张开")
        }
        else -> {
            // 频道六态：体量/断更/爆发/tier 徽章
            if (st.itemsCount > 0) append(" · 存量 ${fmtItems(st.itemsCount)}")
            if (st.lastActivitySec > 0L) {
                val days = (nowMs / 1000L - st.lastActivitySec) / 86_400L
                when {
                    days < 0L -> {}
                    days <= 7L -> append(" · 本周活跃")
                    days < 60L -> append(" · ${days}天没更新")
                    days < 180L -> append(" · ${days / 30}个月没更新")
                    else -> append(" · 断更 ${days / 30} 个月")
                }
            }
            if (st.recent7 >= 3 && st.prev7 >= 0 && st.recent7 > 2 * st.prev7) {
                append(" · 近7天 ${st.recent7} 条是前7天的 2 倍以上（爆发中）")
            }
            if (st.tier == 1) append(" · T1 系统验货")
        }
    }
    append(" · 断更判定按本地索引观测")
}

/** 体量条数人性化：≥1 万显示 "x.x 万条" */
private fun fmtItems(count: Int): String =
    if (count >= 10000) "%.1f 万条".format(count / 10000f) else "$count 条"

// ==================== 视图小工具（飞行场景 / 轮询 / 手势持有 / 邻居索引 / 信息卡行） ====================

/**
 * R242 惯性速度持有（非快照状态：手势期记录，帧循环松手后接管衰减）：
 * 角速度（度/秒，转头）+飞行速度（世界单位/秒，沿视线）双通道——速度测速
 * 收口手势消费单一入口（R268b 提案①：GestureFeelPolicy.FlingVelocityWindow
 * ≤100ms 时间窗测速，帧率无关）。
 */
private class FlingState {
    var yawVel = 0f
    var pitchVel = 0f
    var flyVel = 0f
    var at = 0L
}

/** 点星/双击运镜持有（惯性运镜目标；任何手势立即打断置 false）。
 *  R242：目标=世界点 (tx,ty,tz)+停船距离 stopDist（approach=位置趋近+朝向转向）；
 *  tau=运镜时间常数（单击 0.18s 干脆 / 双击 0.9s 穿越星域的电影感）。
 *  R249 v2：[targetChatId]≠0=运镜目标星（N 体下星持续移动——帧循环把 tx/ty/tz
 *  每帧重锚到当帧渲染位，「点=所见=飞到所见处」；0=固定世界点（全景回望）
 *  R303c P3：**位姿模式**（poseMode=true）=银河视角仪式运镜——目标=完整相机
 *  位姿（[poseTarget]，帧循环 easePose 缓动到位）非世界点 approach；世界点飞行
 *  一律经 [beginWorldFlight] 起手（poseMode 清零=位姿飞行让位，防劫持）
 *  R342 D3：**voyage 模式**（voyageMode=true）=距离正比时长的航行运镜——
 *  用户点击天体「去它那儿」的档（[voyageSec] 时长经 GestureFeelPolicy
 *  voyageSec(D0) 距离正比+钳位；p01 时间归一推进+easePose smoothstep=
 *  加速-巡航-缓出；「嗖一下穿越很多光年」的对症刀）；起手=[beginVoyage]，
 *  其余世界飞行路径默认非 voyage（beginWorldFlight 清 voyageMode） */
private class FocusFlightState {
    var active = false
    var tx = 0f
    var ty = 0f
    var tz = 0f
    var stopDist = 0f
    var tau = GestureFeelPolicy.GLANCE_TAU_SEC // R268b 收口政策件（各触发点均覆写）
    var targetChatId = 0L
    // ---- R303c P3：位姿模式（银河视角运镜） ----
    var poseMode = false
    var poseTarget: SpaceCam? = null
    // ---- R342 D3：voyage 模式（距离正比时长航行——帧循环 voyage 分支消费） ----
    var voyageMode = false
    var voyageSec = GestureFeelPolicy.VOYAGE_MIN_SEC
    var voyageP01 = 0f
    var voyageFrom: SpaceCam? = null
    var voyageStartMs = 0L

    /** R303c：世界点飞行起手单口（poseMode 清零——全部世界目标触发点共用） */
    fun beginWorldFlight() {
        active = true
        poseMode = false
        voyageMode = false // R342：voyage 只经 beginVoyage 显式起手（默认非航行档）
    }

    /** R342 D3：航行运镜起手单口（世界点飞行+voyage 模式——时长=距离正比档） */
    fun beginVoyage(from: SpaceCam, durationSec: Float) {
        beginWorldFlight()
        voyageMode = true
        voyageSec = durationSec.coerceAtLeast(0.05f)
        voyageP01 = 0f
        voyageFrom = from
        voyageStartMs = android.os.SystemClock.uptimeMillis()
    }
}

/** R271 开局引导巡航持有（新用户带飞：推近→绕亮星半圈→点亮未探索星→缓出；
 *  剧本/频控/轨道数学全收口 core.tastemap.TasteTour——本件只是运行时状态袋。
 *  让位语义与 focusFlight 同族：任何手势经 abortGalaxyTourR271 即刻清 active/
 *  pending——帧循环导演见 active=false 自然停转，零竞态）。 */
private class GalaxyTourState {
    var active = false // 巡航在飞（导演逐帧驱动）
    var pending = false // 已排期待启航（START_DELAY_MS 延迟窗内——窗内手势即取消）
    var pendingToken = 0 // 启航令牌（旧 effect 延迟窗与让位交叉的防护——令过期即弃航）
    var sessionSuppress = false // 本会话已让位（手动带飞/跳入运镜/手势接管——不消耗配额，下会话再评）
    var leg = TasteTour.LEG_PUSH
    var legStartMs = 0L
    var tourStartMs = 0L
    var brightId = 0L // 绕转主角（亮星）
    var targetId = 0L // 点亮目标（未探索星；0=无目标——跳过点亮段直缓出）
    // 亮星烘焙位兜底（N 体场缺位时用——绕转中心的单一来源=帧帧重锚 posOfChat 优先）
    var bx = 0f
    var by = 0f
    var bz = 0f
    var orbitR = 0f // 绕转终半径（井×4.6 停船口径）
    var orbitR0 = 0f // 绕转起始半径（推近到位实测——无径向跳变）
    val basis = FloatArray(6) // 轨道平面基（u=起点半径方向，v=扫角方向）
    val scratch = FloatArray(3) // 绕转轨道点暂存（帧零分配）
    val aim = FloatArray(2) // aimAt 暂存（帧零分配）
    var probeAt = 0L // 轨迹探针节流
}

/** R235 虫洞 transit 持有（长按行星→「虫洞跳跃」；帧循环推进 1.5s；任何手势
 *  立即打断置 active=false——focusFlight 同款接管语义）。R242 空间口径：
 *  起跳/目标=SpaceCam 五元组原料（位置+朝向），路径收口 TasteWormhole.camPathSpace。 */
private class WormholeState {
    var active = false
    var p01 = 0f
    var fromX = 0f
    var fromY = 0f
    var fromZ = 0f
    var fromYaw = 0f
    var fromPitch = 0f
    var tx = 0f
    var ty = 0f
    var tz = 0f
    var stopDist = 0f
    var targetChatId = 0L
}

/** R206 渲染场景（mapData → 渲染器输入的一次性映射，remember(mapData) 构建） */
private class FlightScene(
    val stars: List<TasteFxStar>,      // R242：空间坐标（渲染器逐帧深度序，无需预排）
    val edges: List<TasteFxEdge>,
    val span: TasteFlight.GenesisSpan, // 创世纪量程（invalid=无星生序证据，入口隐藏）
    val onMapIds: HashSet<Long>,       // 在图频道集合（彗星新频道爆闪的过滤）
    /** R234 星座轨迹（history.json 今日观看→在图恒星空间折线；null=今日 <2 站无光路） */
    val constTrail: TasteConstTrail? = null,
    /** R321 双星对渲染输入（潮汐形变/特洛伊簇原料——洛希/拉格朗日的帧字段真源） */
    val binaries: List<TasteBinaryFx> = emptyList(),
)

/** 场景构建：星表（空间坐标）+ 边表 + 流光/今晚流星路线装进引擎 + 创世纪量程求出
 *  （R223b：渲染星带全宇宙天体档——kind/tier/体量口径三件/星团成员数；
 *  R242：渲染/路线一律走球面化空间坐标——TasteSpace 投影族） */
private fun buildFlightScene(d: TasteMapData?, engine: TasteFlight.FxEngine): FlightScene {
    if (d == null || d.stars.isEmpty()) {
        engine.setLinks(FloatArray(0), 0)
        engine.setRoute(FloatArray(0), FloatArray(0), FloatArray(0), 0)
        return FlightScene(emptyList(), emptyList(), TasteFlight.GenesisSpan(0L, 0L), HashSet())
    }
    val stars = d.stars
        .map {
            // R311 造父变星（周期性更新频道=变星——判据全来自索引库零新增请求；
            // 只挂主序 STAR：特殊天体的身份笔刷语义不被搏动污染）
            val ceph = if (it.kind == CelestialKind.STAR) {
                TasteStarColor.cepheidOf(
                    it.itemsCount, it.firstSeenMs / 1000L, it.lastActivitySec,
                    it.recent7, it.prev7, it.chatId,
                )
            } else {
                null
            }
            TasteFxStar(
                it.chatId, it.spX, it.spY, it.spZ, it.radius, it.colorArgb, it.alpha, it.label, it.firstSeenMs, it.hasComp,
                it.kind, it.tier, it.bodyRadius, it.bodyAlpha, it.bodyColor, it.memberCount,
                it.itemsCount, // R233：体量条数（引力井半径原料）
                it.watchSec <= 0L && !it.subscribed, // R235：未探索星（发现感渐显适用域）
                it.title, // R248：频道名（已发现星名签消费——发现集合由帧侧传入）
                it.recent7, // R249：近 7 天条数（温度谱选光晕色温档原料）
                it.gVideo, it.gPhoto, it.gAudio, // R249 v2：内容构成（金属丰度→表面特征数）
                it.tempK, // R311：连续色温（色彩引擎——芯/缘色导出原料）
                ceph?.periodSec ?: 0f, // R311：变星搏动周期（秒；0=非变星）
                ceph?.amplitude ?: 0f, // R311：变星振幅（周期-光度关系）
                ceph?.phase01 ?: 0f, // R311：变星初相（哈希错相）
                // R328 批C：诞生注记+最近活动（渲染端演出原料——聚合拍进度亮度/
                // 凝缩调制/点燃判别/生灭回放 diedVisible 门；null/0=缺据零演出）
                it.birth,
                it.lastActivitySec,
                // R321 星风门原料：无向连接数（外流通道——isWindy 双门消费）
                it.degree,
                // R323 自行漂移注记（渲染端焦点件原料——选中/聚焦星的小尾巴；
                // null=未入池/缺据/零漂移=零矢量）
                it.drift,
            )
        }
    // R311 变星探针（计数=「周期性更新频道=变星」的机器证据；只出计数——脱敏）
    val cephN = stars.count { it.pulsePeriodSec > 0f }
    if (cephN > 0) {
        android.util.Log.i("TasteMap", "R311 造父变星: 在场搏动星=$cephN")
    }
    val edges = d.links.map {
        TasteFxEdge(it.spX1, it.spY1, it.spZ1, it.spX2, it.spY2, it.spZ2, it.a, it.b, it.alpha)
    }
    // 流光粒子按边权分布（重边流光更密）
    val edgeAlphas = FloatArray(edges.size) { edges[it].alpha }
    engine.setLinks(edgeAlphas, edges.size)
    // 今晚星轨：频道串 → 在图星的世界坐标路线（<2 站=无流星，诚实空态）
    val byId = HashMap<Long, TasteFxStar>(stars.size * 2)
    for (s in stars) byId[s.chatId] = s
    if (d.todayTrail.size >= 2) {
        val wx = FloatArray(d.todayTrail.size)
        val wy = FloatArray(d.todayTrail.size)
        val wz = FloatArray(d.todayTrail.size)
        var n = 0
        for (chatId in d.todayTrail) {
            val s = byId[chatId] ?: continue
            wx[n] = s.x; wy[n] = s.y; wz[n] = s.z; n++
        }
        engine.setRoute(wx, wy, wz, n)
    } else {
        engine.setRoute(FloatArray(0), FloatArray(0), FloatArray(0), 0)
    }
    // R234 星座轨迹：今日观看站点（history.json 口径）→ 在图恒星世界折线（<2 站=无光路）
    var constTrail: TasteConstTrail? = null
    if (d.todayStops.size >= 2) {
        val cxs = FloatArray(d.todayStops.size)
        val cys = FloatArray(d.todayStops.size)
        val czs = FloatArray(d.todayStops.size)
        val cts = LongArray(d.todayStops.size)
        var cn = 0
        for (stop in d.todayStops) {
            val s = byId[stop.chatId] ?: continue
            cxs[cn] = s.x; cys[cn] = s.y; czs[cn] = s.z; cts[cn] = stop.tsMs; cn++
        }
        if (cn >= 2) constTrail = TasteConstTrail(cn, cxs, cys, czs, cts)
    }
    return FlightScene(
        stars, edges,
        TasteFlight.genesisSpan(d.stars.map { it.firstSeenMs }),
        HashSet(byId.keys),
        constTrail,
        // R321 双星对渲染输入（潮汐形变/特洛伊簇原料——洛希/拉格朗日帧字段真源）
        d.binaryPairs.map { TasteBinaryFx(it.a, it.b, it.trojanW) },
    )
}

/**
 * R234 传播模型 → 尘埃带渲染模型（**世界坐标装配期烘焙**——渲染期零计算除投影）：
 *  - 源=模型首发频道；不在图（>600 池截断）=回退到在图最早频道（诚实回退，渲染侧
 *    状态胶囊不冒充「图上没有的源」）；全不在图=null（胶囊转诚实空态文案）；
 *  - 带=源→各在图下游频道（弧控制点 galaxyArcControl 单口——与关联弧同一式）；
 *  - 粒子世界坐标=弧上时间位置（TasteTagSpread.particleU）+ 确定性垂直抖动
 *    （±3.5% 弦长的薄带宽度）；亮度档/发布时刻一并烘焙；
 *  - 带主色=源星色相 + tag 哈希次级抖动（行星色同手法，提亮到传播带的发光档）。
 */
private fun buildSpreadRender(
    model: TasteTagSpread.SpreadModel,
    byId: Map<Long, StarNode>,
): TasteSpreadRender? {
    if (model.channels.isEmpty()) return null
    var source = byId[model.sourceChatId]
    if (source == null) {
        source = model.channels.firstNotNullOfOrNull { byId[it.chatId] } ?: return null
    }
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(source.bodyColor, hsv)
    val hueJit = ((model.tag.hashCode() % 29 + 29) % 29 - 14).toFloat()
    hsv[0] = (hsv[0] + hueJit + 360f) % 360f
    hsv[1] = hsv[1].coerceIn(0.42f, 0.60f)
    hsv[2] = maxOf(hsv[2], 0.82f)
    val color = android.graphics.Color.HSVToColor(hsv)
    val bands = ArrayList<TasteSpreadBand>(model.channels.size)
    for (ch in model.channels) {
        if (ch.chatId == source.chatId) continue // 源自身无带（它是所有带的锚）
        val target = byId[ch.chatId] ?: continue // 不在图的频道诚实跳过（池截断）
        val ctl = FloatArray(3)
        galaxyArcControl(source.spX, source.spY, source.spZ, target.spX, target.spY, target.spZ, ctl)
        val n = ch.particleSec.size
        val pms = LongArray(n)
        val pxs = FloatArray(n)
        val pys = FloatArray(n)
        val pzs = FloatArray(n)
        val pbk = ByteArray(n)
        val seed = (ch.chatId % 100_000L).toInt()
        val dxw = target.spX - source.spX
        val dyw = target.spY - source.spY
        val dzw = target.spZ - source.spZ
        val chord = sqrt(dxw * dxw + dyw * dyw + dzw * dzw)
        // 弦的垂直方向（空间带抖动——任意取向稳定：用与坐标轴最不对齐的基叉积）
        var perpX = -dyw
        var perpY = dxw
        var perpZ = 0f
        if (abs(perpX) + abs(perpY) < 1e-3f) { perpX = 0f; perpY = -dzw; perpZ = dyw }
        val pl = sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ)
        if (pl > 1e-4f) { perpX /= pl; perpY /= pl; perpZ /= pl } else { perpX = 1f; perpY = 0f; perpZ = 0f }
        for (i in 0 until n) {
            val sec = ch.particleSec[i]
            val u = TasteTagSpread.particleU(sec, model.t0Sec, model.t1Sec)
            val iu = 1f - u
            var wx = iu * iu * source.spX + 2f * iu * u * ctl[0] + u * u * target.spX
            var wy = iu * iu * source.spY + 2f * iu * u * ctl[1] + u * u * target.spY
            var wz = iu * iu * source.spZ + 2f * iu * u * ctl[2] + u * u * target.spZ
            val off = (TasteTagPlanets.h01(seed, i * 31 + 11) - 0.5f) * 2f * 0.035f * chord
            wx += perpX * off
            wy += perpY * off
            wz += perpZ * off
            pms[i] = sec * 1000L
            pxs[i] = wx
            pys[i] = wy
            pzs[i] = wz
            pbk[i] = TasteTagSpread.alphaBucketOf(sec, model.t0Sec, model.t1Sec).toByte()
        }
        bands += TasteSpreadBand(
            toChatId = ch.chatId,
            ax = source.spX, ay = source.spY, az = source.spZ,
            bx = target.spX, by = target.spY, bz = target.spZ,
            cx = ctl[0], cy = ctl[1], cz = ctl[2],
            firstMs = ch.firstSec * 1000L,
            itemCount = ch.count,
            particleMs = pms,
            particleX = pxs,
            particleY = pys,
            particleZ = pzs,
            particleBucket = pbk,
        )
    }
    if (bands.isEmpty()) return null // 只有源在图：无可画带（胶囊转空态文案）
    return TasteSpreadRender(
        tag = model.tag,
        sourceChatId = source.chatId,
        sx = source.spX, sy = source.spY, sz = source.spZ,
        t0Ms = model.t0Sec * 1000L,
        t1Ms = model.t1Sec * 1000L,
        colorArgb = color,
        recalled = model.recalled,
        channelCount = model.channels.size,
        sampled = model.sampled,
        bands = bands,
    )
}

/** 彗星轮询单轮结果：索引最大 rowid + refs 最大 rowid + 自上次以来的新频道（首轮=基线，newChats 空） */
private class CrawlPollResult(val maxRowId: Long, val refsMaxRowId: Long, val newChats: List<Long>)

/**
 * 深索引爬行差分（**IO 线程调用**，OPEN_READONLY WAL 只读——loadIndexGenomes 同款
 * 纪律）：MAX(rowid) O(1) 探测；有增长才 SELECT DISTINCT 增量新频道（有界只扫新行，
 * 绝不全表扫）。[lastRow] < 0 = 首轮基线（只取 maxRowId 不取差分）。
 * 库被爬虫写事务短暂占锁 → runCatching 由调用方兜（跳过本轮，下轮再试）。
 *
 * R266 v11 双游标：转发收编后新爬转发在 items 里「先插后转」同事务删走——纯转发
 * 页 items MAX(rowid) 原地不动，单游标口径对转发流量全盲。refs 臂 MAX(rowid)
 * 补盲（升级窗口期 v10 库无此表——独立容错，refsMax=-1 退化为单游标旧口径）。
 */
private fun pollDeepIndexCrawl(dbFile: File, lastRow: Long, lastRefsRow: Long): CrawlPollResult? {
    val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    try {
        val maxRow = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
        var refsMax = -1L
        runCatching {
            refsMax = d.rawQuery("SELECT MAX(rowid) FROM repost_refs", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }
        if (maxRow <= 0L && refsMax <= 0L) return CrawlPollResult(-1L, refsMax, emptyList())
        val itemsGrew = lastRow >= 0L && maxRow > lastRow
        val refsGrew = lastRefsRow >= 0L && refsMax > lastRefsRow
        if (!itemsGrew && !refsGrew) return CrawlPollResult(maxRow, refsMax, emptyList())
        val newChats = ArrayList<Long>(8)
        if (itemsGrew) {
            d.rawQuery(
                "SELECT DISTINCT chatId FROM items WHERE rowid > ? AND chatId != 0",
                arrayOf(lastRow.toString()),
            ).use { c ->
                while (c.moveToNext()) newChats += c.getLong(0)
            }
        }
        if (refsGrew) {
            runCatching {
                d.rawQuery(
                    "SELECT DISTINCT chatId FROM repost_refs WHERE rowid > ? AND chatId != 0",
                    arrayOf(lastRefsRow.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id !in newChats) newChats += id
                    }
                }
            }
        }
        return CrawlPollResult(maxRow, refsMax, newChats)
    } finally {
        runCatching { d.close() }
    }
}

/**
 * R250 点星档案装载产出（档案装载 effect 一次 IO 上下文装回的四件套）：编年史
 * 摘要（R248 原件）+ 24h 小时直方图（R250 节奏微图）+ 代表作预览初载窗（R250
 * 动态装载）+ 发现日期账（R250 个人足迹行）。一车装回=卡面开卡零二次查询。
 */
private class StarCardLoad(
    val digest: ChannelEraPolicy.EraDigest?,
    val hourHist: IntArray,
    val previews: List<VideoItem>,
    val discovered: Map<Long, Long>,
)

/**
 * 本地时区当前偏移秒（R253① 单一出处）：24h 热力条直方图（R250）与时段代表作分页
 * （R253 channelItemsByHourPage）的 `(dateEpochSec+tz)/3600%24` 谓词共用同一取值口径
 * ——点 22 时格=取 22:00~23:00 条目，**所见即所得**（历史帖跨 DST 差异为可容忍近似，
 * 与 R250 直方图注释同口径）。
 */
private fun localTzOffsetSec(): Int =
    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

/**
 * R248 编年史取数（信息卡「展开档案」——**IO 线程调用**）：本地深索引两条频道域
 * 聚合查询（idx_items_chat_msg 前缀毫秒级，怪兽频道也只扫自身区间；OPEN_READONLY
 * 只读纪律与 loadIndexGenomes 同款——绝不建库绝不写库）喂 ChannelEraPolicy 纯函数：
 *  - 半年桶（dateEpochSec/HALF_YEAR_SEC 分组 + 三类条数）→ 内容演化卷+峰段+转型；
 *  - 日直方图（dateEpochSec/86400 分组）→ 节奏判型+月度柱+周内惯例+趋势。
 * R250 加第三条频道域聚合：24h 小时直方图（`(dateEpochSec+tzOffsetSec)/3600%24`
 * ——**本地时区口径**，tz 偏移秒 bind 传入；返回对 (digest, hourHist)）。
 * 未索引/无日期证据/查询异常=digest null（调用方走诚实薄档案态——R246 降级矩阵）。
 */
private fun loadChannelEraDigest(vm: FeedViewModel, chatId: Long): Pair<ChannelEraPolicy.EraDigest?, IntArray> {
    val dbFile = File(File(vm.app.filesDir, "deepindex"), "search.db")
    if (!dbFile.isFile) return null to IntArray(0)
    return runCatching {
        val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // R266 v11 双臂（D4/D6 现状口径含转发）：半年桶/日直方图/小时直方图三查询
            // 各走 items+refs 两臂按桶相加合并（频道域有界；refs 臂独立容错——升级
            // 窗口期 v10 库无此表，缺席=存量形态直通）
            val bucketByHalf = HashMap<Long, IntArray>(32) // half → [total, video, photo]
            for (table in listOf("items", "repost_refs")) {
                runCatching {
                    d.rawQuery(
                        "SELECT dateEpochSec/" + ChannelEraPolicy.HALF_YEAR_SEC + ", COUNT(*), " +
                            "SUM(CASE WHEN mediaType=0 THEN 1 ELSE 0 END), " +
                            "SUM(CASE WHEN mediaType=1 THEN 1 ELSE 0 END) " +
                            "FROM $table WHERE chatId=? AND dateEpochSec>0 GROUP BY 1 ORDER BY 1",
                        arrayOf(chatId.toString()),
                    ).use { c ->
                        while (c.moveToNext()) {
                            val acc = bucketByHalf.getOrPut(c.getLong(0)) { IntArray(3) }
                            acc[0] += c.getInt(1)
                            acc[1] += c.getInt(2)
                            acc[2] += c.getInt(3)
                        }
                    }
                }
            }
            val buckets = bucketByHalf.entries.sortedBy { it.key }.map { (half, acc) ->
                ChannelEraPolicy.EraBucket(
                    half * ChannelEraPolicy.HALF_YEAR_SEC,
                    acc[0],
                    acc[1],
                    acc[2],
                    (acc[0] - acc[1] - acc[2]).coerceAtLeast(0),
                )
            }
            val dayMap = HashMap<Int, Int>(512)
            for (table in listOf("items", "repost_refs")) {
                runCatching {
                    d.rawQuery(
                        "SELECT dateEpochSec/86400, COUNT(*) FROM $table WHERE chatId=? AND dateEpochSec>0 GROUP BY 1 ORDER BY 1",
                        arrayOf(chatId.toString()),
                    ).use { c ->
                        while (c.moveToNext()) dayMap.merge(c.getInt(0), c.getInt(1), Int::plus)
                    }
                }
            }
            val days = dayMap.entries.sortedBy { it.key }.map { ChannelEraPolicy.DayCount(it.key, it.value) }
            // R250：24h 小时直方图（本地时区钟点——用户读「22 时最活跃」才对时；
            // 与既有两查询同 chatId 域同索引路径，GROUP BY 毫秒级）
            val hourMap = HashMap<Int, Int>(24)
            val tzOffSec = localTzOffsetSec() // R253①：tz 单一出处（与时段分页查询同口径）
            for (table in listOf("items", "repost_refs")) {
                runCatching {
                    d.rawQuery(
                        "SELECT (dateEpochSec+?)/3600%24, COUNT(*) FROM $table WHERE chatId=? AND dateEpochSec>0 GROUP BY 1",
                        arrayOf(tzOffSec.toString(), chatId.toString()),
                    ).use { c ->
                        while (c.moveToNext()) hourMap.merge(c.getInt(0), c.getInt(1), Int::plus)
                    }
                }
            }
            val hourRows = hourMap.entries.map { ChannelEraPolicy.HourCount(it.key, it.value) }
            val hourHist = ChannelEraPolicy.hourHistogramOf(hourRows)
            if (buckets.isEmpty() && days.isEmpty()) return null to hourHist
            ChannelEraPolicy.digest(ChannelEraPolicy.EraInput(buckets, days)) to hourHist
        } finally {
            runCatching { d.close() }
        }
    }.getOrDefault(null to IntArray(0))
}

// ==================== R248 信息卡组件（预览格 / 演化时代行 / 律动月度柱） ====================

/**
 * R248 代表作预览格（3:4 缩略）：封面链与时光机墙/频道主页同族（CoverStore 抽帧
 * → coverBytes minithumbnail → loadCoverBytes/loadAudioCoverBytes 高清 →
 * loadCoverBytesByMessage 消息反解 → 图片原图采样）——索引条目 coverBytes 恒
 * null，实际走高清/反解两档；全无=类型字占位（收藏墙同款兜底）。点=播放
 * （onPlayDust 同链——R233 尘埃播放零新链路）。
 */
@Composable
private fun PreviewThumbCell(item: VideoItem, gateway: TdGateway, onPlay: () -> Unit) {
    val cover: androidx.compose.ui.graphics.ImageBitmap? =
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, item.key) {
            CoverStore.map[item.key]?.let { value = it }
            if (value == null) {
                value = item.coverBytes?.let { bytes ->
                    withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
                    }
                }
            }
            val lib = gateway as? TdLibGateway
            if (value == null && lib != null) {
                // 高清封面（取字节+解码整段下放 IO）
                val hd: androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
                    val bytes = when {
                        item.mediaType == 2 && item.audioCoverFileId >= 0 ->
                            runCatching { lib.loadAudioCoverBytes(item) }.getOrNull()
                        item.coverFileId >= 0 ->
                            runCatching { lib.loadCoverBytes(item) }.getOrNull()
                        else -> null
                    }
                    bytes?.let {
                        runCatching { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() }.getOrNull()
                    }
                }
                if (hd != null) value = hd
                // 消息反解兜底（索引条目跨会话 fileId 失效时：chatId+messageId 反解真缩略图）
                if (value == null && item.messageId > 0) {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
                    }
                    if (bytes != null) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
                        }
                    }
                }
                // 图片（mediaType=1）：原图采样解码链（共享件）
                if (value == null && item.mediaType == 1 && item.fileId >= 0) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { loadPhotoCoverBitmap(lib, item) }.getOrNull()
                    }
                }
            }
        }.value
    Column(
        Modifier.width(84.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                .background(TgFeedTokens.SurfaceHigh)
                .clickable(onClick = onPlay),
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 无封面占位：类型字（收藏墙/时光机墙同款兜底）
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (item.mediaType) {
                            1 -> "图"
                            2 -> "音"
                            else -> "视"
                        },
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Text(
            item.titleLine().ifBlank { when (item.mediaType) { 1 -> "图文"; 2 -> "音频"; else -> "视频" } },
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** R248 演化卷时代行（内容演化）：时代标签（峰段金标）+ 三色构成比例条 + 总量 */
@Composable
private fun EraSegRow(seg: ChannelEraPolicy.EvoSeg) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (seg.isPeak) "${seg.label}·峰" else seg.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (seg.isPeak) TgFeedTokens.StarYellow else TgFeedTokens.TextMedium,
            modifier = Modifier.width(82.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill)),
        ) {
            Box(Modifier.weight(max(seg.video, 1).toFloat()).background(Color(TYPE_VIDEO)))
            Box(Modifier.weight(max(seg.photo, 1).toFloat()).background(Color(TYPE_PHOTO)))
            Box(Modifier.weight(max(seg.audio, 1).toFloat()).background(Color(TYPE_AUDIO)))
        }
        Text(
            "${seg.total} 条",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
    }
}

/** R248 律动卷月度柱条（近 24 个月）：峰月金标、余淡蓝——月度节奏一眼可读 */
@Composable
private fun MonthlyBarsCanvas(monthly: List<ChannelEraPolicy.MonthCount>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm)),
    ) {
        val n = monthly.size
        if (n == 0) return@Canvas
        val maxC = monthly.maxOf { it.count }.coerceAtLeast(1)
        val gap = 1.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1.5f)
        val peakIdx = monthly.indices.maxByOrNull { monthly[it].count } ?: -1
        monthly.forEachIndexed { i, m ->
            val h = size.height * (m.count.toFloat() / maxC).coerceAtLeast(0.04f)
            drawRect(
                color = if (i == peakIdx) {
                    TgFeedTokens.StarYellow.copy(alpha = 0.90f)
                } else {
                    TgFeedTokens.TgBlue.copy(alpha = 0.55f)
                },
                topLeft = Offset(i * (bw + gap), size.height - h),
                size = Size(bw, h),
            )
        }
    }
}

/** R381 律动卷连续光流画布高度（原月度柱条 52dp 同量级） */
private val ChronicleRhythmFlowHeight = 52.dp

/**
 * R381 律动卷连续光流（LP8 正治）：近 24 月连续光波+峰月金点——与频道编年史
 * 时间脊柱·光流带同语言（每月一列，列高∝当月条数对 max 归一=连续映射非离散
 * 矩形档位；列身顶亮底暗渐变，列顶 quadratic 连线成光流轮廓）。替换六卷卷宗
 * ②律动卷的 [MonthlyBarsCanvas] 消费位；MonthlyBarsCanvas 本体保留（R301 世界
 * 卡消费位写域外零碰）。空月零绘制（缺据不出）。
 */
@Composable
internal fun ChronicleRhythmFlowCanvas(monthly: List<ChannelEraPolicy.MonthCount>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ChronicleRhythmFlowHeight)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm)),
    ) {
        val n = monthly.size
        if (n == 0) return@Canvas
        val maxC = monthly.maxOf { it.count }.coerceAtLeast(1)
        val colW = size.width / n
        val barW = colW * 0.82f
        val headroom = 5.dp.toPx() // 峰月金点（半径 3dp）不裁边
        val tops = FloatArray(n) { i ->
            val frac = monthly[i].count.toFloat() / maxC
            size.height - (size.height - headroom) * frac
        }
        val peakIdx = monthly.indices.maxByOrNull { monthly[it].count } ?: -1
        // 列身：顶亮底暗渐变（连续亮度承载量级；空月零绘制）
        for (i in 0 until n) {
            if (monthly[i].count <= 0) continue
            val left = i * colW + (colW - barW) / 2f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(TgFeedTokens.TgBlue.copy(alpha = 0.60f), TgFeedTokens.TgBlue.copy(alpha = 0.06f)),
                    startY = tops[i],
                    endY = size.height,
                ),
                topLeft = Offset(left, tops[i]),
                size = Size(barW, size.height - tops[i]),
            )
        }
        // 光流轮廓：列顶锚点为控制点、月中点为轨的 quadratic 平滑连线
        if (n > 1) {
            val xs = FloatArray(n) { (it + 0.5f) * colW }
            val path = Path()
            path.moveTo(xs[0], tops[0])
            path.lineTo((xs[0] + xs[1]) / 2f, (tops[0] + tops[1]) / 2f)
            for (i in 1 until n - 1) {
                path.quadraticTo(xs[i], tops[i], (xs[i] + xs[i + 1]) / 2f, (tops[i] + tops[i + 1]) / 2f)
            }
            path.lineTo(xs[n - 1], tops[n - 1])
            drawPath(path, TgFeedTokens.TgBlue.copy(alpha = 0.85f), style = Stroke(width = 1.dp.toPx()))
        }
        // 峰月金点（峰月金标：StarYellow 实心点坐在峰月列顶）
        if (peakIdx >= 0) {
            drawCircle(
                color = TgFeedTokens.StarYellow,
                radius = 3.dp.toPx(),
                center = Offset(peakIdx * colW + colW / 2f, tops[peakIdx]),
            )
        }
    }
}

// ==================== R250 信息卡新组件（tag 行星云 / 24h 热力条 / 趋势迷你线） ====================

/** R250 行星云布局位（确定性静态布局：pill 中心相对星核的偏移 + 视觉轨道半径） */
private class PlanetCloudPos(
    val planet: TasteTagPlanets.TagPlanet,
    val dxPx: Float,   // pill 中心相对星核中心的 x 偏移（px）
    val dyPx: Float,   // y 偏移（px——已含轨道压扁 0.62）
    val orbitR: Float, // 视觉轨道半径（px——椭圆长半轴）
    val dotDp: Float,  // 行星圆点直径（dp——∝ sizeR01）
)

/**
 * R250 tag 行星云（信息卡内嵌迷你轨道图——tag 小行星环绕）：Canvas 星核（体色+
 * 光晕）+ 每颗行星一圈压扁椭圆轨道（[TasteTagPlanets.ORBIT_FLATTEN]=星图轨道面
 * 观感同语言）；行星=前置 Compose 元素（圆点大小 ∝ sizeR01 + #tag 文字），位置=
 * phase01 确定性相位（**静态面板——每次打开同一布局，非逐帧动画**，红线三）；
 * 轨道距离在本系统内相对归一（频率高=靠内——orbitR01 越小越靠内直映视觉带；
 * 绝对域归一化收敛，不私抄 TasteTagPlanets 私有常量）。点行星（≥
 * [TAG_CLOUD_MIN_TOUCH_DP] 热区 pill）→ [onTagClick]（R222 关键字时间轴直发
 * ——用户口径）。tag 超长省略（widthIn 钳制——怪 tag 不破版）。
 */
@Composable
private fun TagOrbitCloud(
    planets: List<TasteTagPlanets.TagPlanet>,
    starColorArgb: Int,
    onTagClick: (String) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(168.dp),
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val cx = wPx / 2f
        val cy = hPx / 2f
        // 视觉轨道带（px）：内圈让开星核光晕；外圈受高度约束（迷你图克制高度，
        // 宽屏不外扩——稳定观感）
        val rMinPx = with(density) { 34.dp.toPx() }
        val rMaxPx = (hPx / 2f - with(density) { 30.dp.toPx() }).coerceAtLeast(rMinPx + 1f)
        // 本系统内轨道相对归一（等距/单圈=中带——确定性破平）
        val minO = planets.minOf { it.orbitR01 }
        val maxO = planets.maxOf { it.orbitR01 }
        val span = (maxO - minO).coerceAtLeast(1e-4f)
        val positions = ArrayList<PlanetCloudPos>(planets.size)
        for (p in planets) {
            val rVis = if (maxO - minO < 1e-4f) {
                (rMinPx + rMaxPx) / 2f
            } else {
                rMinPx + (p.orbitR01 - minO) / span * (rMaxPx - rMinPx)
            }
            val ang = (p.phase01 * 2.0 * Math.PI).toFloat()
            positions += PlanetCloudPos(
                planet = p,
                dxPx = rVis * cos(ang),
                dyPx = rVis * TasteTagPlanets.ORBIT_FLATTEN * sin(ang),
                orbitR = rVis,
                dotDp = 3f + 6f * p.sizeR01.coerceIn(0f, 1f),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            // 星核（体色身份色 + 光晕）
            val coreR = 5.dp.toPx()
            drawCircle(Color(starColorArgb).copy(alpha = 0.14f), coreR * 3.2f, Offset(cx, cy))
            drawCircle(Color(starColorArgb), coreR, Offset(cx, cy))
            // 轨道椭圆（压扁=星图轨道面观感同语言；发丝中性白）
            val hairline = Stroke(width = 0.8.dp.toPx())
            for (pos in positions) {
                val ry = pos.orbitR * TasteTagPlanets.ORBIT_FLATTEN
                drawOval(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(cx - pos.orbitR, cy - ry),
                    size = Size(pos.orbitR * 2f, ry * 2f),
                    style = hairline,
                )
            }
        }
        positions.forEach { pos ->
            Row(
                Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(pos.dxPx.roundToInt(), pos.dyPx.roundToInt()) }
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBg)
                    .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .clickable { onTagClick(pos.planet.tag) }
                    .heightIn(min = TAG_CLOUD_MIN_TOUCH_DP.dp)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier
                        .size(pos.dotDp.dp)
                        .clip(CircleShape)
                        .background(Color(starColorArgb)),
                )
                Text(
                    "#${pos.planet.tag}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 88.dp),
                )
            }
        }
    }
}

/**
 * R250 24h 热力条（发布节奏微图）：24 槽柱条（本地钟点）；峰时金标+余淡蓝——
 * [MonthlyBarsCanvas] 同族两色系（R207 语法内：金=峰强调同族复用非新色相）。
 * 静态 Canvas 非逐帧（红线三）；调用方保证 sum>0 才入场（全零=无日期证据
 * 整段不出）。
 *
 * R253① 可点选：每格可点（tap x 反解槽位=整格命中，末位 gap 不算；**0 条格不
 * 响应**——无数据可过滤，诚实）；点选态 [selectedHour] 该格白描边+淡蓝底衬
 * （小柱也可见——R207 同族：白 0.85=趋势末环、TgBlue 0.16≈律动徽章底 0.14）、
 * 柱 alpha 升满；点= [onHourTap]（同格再点=取消在调用侧 toggle）。本节点单
 * pointerInput（无第二手势不互杀——纪律§四）；静态绘制零逐帧。
 */
@Composable
private fun HourHeatCanvas(
    hours: IntArray,
    selectedHour: Int?,
    onHourTap: (Int) -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .pointerInput(hours) {
                detectTapGestures { pos ->
                    if (hours.size != ChannelEraPolicy.HOURS_PER_DAY) return@detectTapGestures
                    val gap = 1.dp.toPx()
                    val bw = ((size.width - gap * (ChannelEraPolicy.HOURS_PER_DAY - 1)) / ChannelEraPolicy.HOURS_PER_DAY)
                        .coerceAtLeast(1f)
                    val slot = (pos.x / (bw + gap)).toInt()
                    // 命中=槽内（末位 gap 不算）且该时段有条目（0 条=无可过滤不响应）
                    if (slot in 0 until ChannelEraPolicy.HOURS_PER_DAY &&
                        pos.x < ChannelEraPolicy.HOURS_PER_DAY * (bw + gap) - gap &&
                        hours[slot] > 0
                    ) {
                        onHourTap(slot)
                    }
                }
            },
    ) {
        if (hours.size != ChannelEraPolicy.HOURS_PER_DAY) return@Canvas
        val maxC = hours.max().coerceAtLeast(1)
        val gap = 1.dp.toPx()
        val bw = ((size.width - gap * (ChannelEraPolicy.HOURS_PER_DAY - 1)) / ChannelEraPolicy.HOURS_PER_DAY)
            .coerceAtLeast(1f)
        val peakIdx = hours.indices.maxByOrNull { hours[it] } ?: -1
        hours.forEachIndexed { h, c ->
            val barH = size.height * (c.toFloat() / maxC).coerceAtLeast(0.04f)
            drawRect(
                color = when {
                    h == peakIdx -> TgFeedTokens.StarYellow.copy(alpha = if (selectedHour == h) 1f else 0.90f)
                    selectedHour == h -> TgFeedTokens.TgBlue.copy(alpha = 0.95f)
                    else -> TgFeedTokens.TgBlue.copy(alpha = 0.55f)
                },
                topLeft = Offset(h * (bw + gap), size.height - barH),
                size = Size(bw, barH),
            )
        }
        // 点选高亮（柱后第二遍：整格淡蓝底衬+白描边——小柱/零位也清晰可辨）
        val sel = selectedHour
        if (sel != null && sel in 0 until ChannelEraPolicy.HOURS_PER_DAY) {
            val x = sel * (bw + gap)
            drawRect(
                color = TgFeedTokens.TgBlue.copy(alpha = 0.16f),
                topLeft = Offset(x, 0f),
                size = Size(bw, size.height),
            )
            drawRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(x, 0f),
                size = Size(bw, size.height),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/**
 * R250 内容趋势迷你线（近 12 月折线）：峰点金强调点+末点空心环标记（最新月）
 * ——[MonthlyBarsCanvas] 同族视觉；数据=chron.monthly（近 12 月窗口内取），档案
 * 两卷零改动（红线一）。静态 Canvas；月数 <2 无绘制（调用方门）。
 */
@Composable
private fun TrendSparkCanvas(monthly: List<ChannelEraPolicy.MonthCount>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm)),
    ) {
        val pts = monthly.takeLast(12)
        val n = pts.size
        if (n < 2) return@Canvas
        val maxC = pts.maxOf { it.count }.coerceAtLeast(1)
        val inset = 4.dp.toPx()
        val usableW = (size.width - inset * 2).coerceAtLeast(4f)
        val usableH = (size.height - inset * 2).coerceAtLeast(4f)
        val stepX = usableW / (n - 1)
        fun px(i: Int) = inset + i * stepX
        fun py(c: Int) = inset + usableH * (1f - c.toFloat() / maxC)
        val peakIdx = pts.indices.maxByOrNull { pts[it].count } ?: -1
        val path = Path()
        pts.forEachIndexed { i, m ->
            if (i == 0) path.moveTo(px(i), py(m.count)) else path.lineTo(px(i), py(m.count))
        }
        drawPath(path, TgFeedTokens.TgBlue.copy(alpha = 0.60f), style = Stroke(width = 1.5.dp.toPx()))
        // 峰点金强调（光晕+实心点）
        if (peakIdx >= 0) {
            val peak = Offset(px(peakIdx), py(pts[peakIdx].count))
            drawCircle(TgFeedTokens.StarYellow.copy(alpha = 0.25f), 5.dp.toPx(), peak)
            drawCircle(TgFeedTokens.StarYellow.copy(alpha = 0.95f), 2.5.dp.toPx(), peak)
        }
        // 末点标记（空心环=最新月）
        val lastPt = Offset(px(n - 1), py(pts[n - 1].count))
        drawCircle(Color.White.copy(alpha = 0.85f), 2.5.dp.toPx(), lastPt, style = Stroke(width = 1.2.dp.toPx()))
    }
}

/** 最近观看人性化："今天看过" / "昨天看过" / "N 天前" / "N 周前" / "N 个月前"（从未 = 空串） */
private fun fmtLastWatch(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val days = ((now - ms) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天看过"
        days == 1 -> "昨天看过"
        days < 14 -> "$days 天前看过"
        days < 60 -> "${days / 7} 周前看过"
        else -> "${days / 30} 个月前看过"
    }
}

// ==================== R251 编年史四卷（③巅峰作品④资源里程碑⑤沉寂复活⑥互联档案） ====================

/** R251 链样本上限（episodeChains 候选封顶 CHAIN_CANDIDATE_CAP=800 同源——保新弃旧） */
private const val CHRON_CHAIN_SAMPLE = 800

/** R251 单日窗口取条上限（峰日/回归日/时长离群取条恒有界——爆发日也封顶） */
private const val CHRON_DAY_WINDOW_LIMIT = 64

/** R251 峰作品行：纯函数产出 + 可播放条目（null=窗口取条降级——行仍出，点不可用） */
internal class PeakWorkRow(val work: ChannelEraPolicy.PeakWork, val item: VideoItem?)

/** R251 里程碑行（④：label=创刊/第 N 条/系列开篇；item=null 不可点） */
internal class MilestoneRow(val ms: ChannelEraPolicy.Milestone, val item: VideoItem?)

/** R251 沉寂行（⑤：returned 且取到回归日首条才可点播放） */
internal class DormantRow(val h: ChannelEraPolicy.Hiatus, val returnItem: VideoItem?)

/** R251 四卷卷宗（⑥互联不在内——装配期 neighborRels 直供零查询） */
internal class ChronicleVolumes(
    val works: List<PeakWorkRow>,
    val milestones: List<MilestoneRow>,
    val dormant: List<DormantRow>,
    val shortPauses: Int,
    val hiatusSummary: String?,
    val totalItems: Int,
)

/** R251 条目行投影（ChannelEraPolicy.EraItemRow 的调用侧折好——titleLine 首行） */
private fun eraRowOf(it: VideoItem): ChannelEraPolicy.EraItemRow = ChannelEraPolicy.EraItemRow(
    it.messageId, it.dateEpochSec.toLong(), it.mediaType, it.durationSec, it.albumId, it.titleLine(),
)

/** R251 单日窗口取条（idx_items_date 有界横截面——R202 时间索引原语，设计 §3.1 指定
 *  取法；索引未就绪=空表诚实降级：③证据一缺席/⑤回归条目不可点） */
private fun dayWindowItems(vm: FeedViewModel, chatId: Long, sec: Long, limit: Int): List<VideoItem> =
    vm.deepIndex.timeWindowItems(
        DeepSearchIndex.TimeWindowQuery(sec, sec + 86_399L, setOf(chatId), limit),
    )

/**
 * R251 四卷取数（信息卡「展开档案」③④⑤卷——**IO 线程调用**；与 loadChannelEraDigest
 * 同款 OPEN_READONLY 只读纪律，全部有界（R183「绝不全表扫」——每路恒 ≤频道索引区间）：
 *  - ③ 峰日窗行（日直方图 globalMax 日 → dayWindowItems 单日窗 ≤64 条）；
 *  - ③ 时长离群账（频道域视频时长：COUNT+中位两行法 + 最长行极小投影 raw SQL）；
 *  - ③④ 链样本（channelItemsPage 新→旧 ≤800 → episodeChains 纯函数 → 链首）；
 *  - ④ 里程碑（indexedCount 门内才查；channelItemsPage DESC 偏移=ASC 第 N 条——
 *    索引区间内跳，与全量无关）；
 *  - ⑤ 沉寂（日直方图 → hiatusesOf 纯函数；回归日首条=单日窗最旧条）。
 * 无日期证据=null（调用方整四卷不出——与 digest 同空态同纪律）。
 */
private fun loadChronicleVolumes(vm: FeedViewModel, chatId: Long, lastActivitySec: Long): ChronicleVolumes? {
    val dbFile = File(File(vm.app.filesDir, "deepindex"), "search.db")
    if (!dbFile.isFile) return null
    return runCatching {
        val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // 日直方图（与 loadChannelEraDigest 同形——峰日/断更共用，一条聚合做卷地基）。
            // R266 v11 双臂（D4 现状口径含转发；refs 臂独立容错——升级窗口期直通存量）
            val dayMap = HashMap<Int, Int>(512)
            for (table in listOf("items", "repost_refs")) {
                runCatching {
                    d.rawQuery(
                        "SELECT dateEpochSec/86400, COUNT(*) FROM $table WHERE chatId=? AND dateEpochSec>0 GROUP BY 1 ORDER BY 1",
                        arrayOf(chatId.toString()),
                    ).use { c -> while (c.moveToNext()) dayMap.merge(c.getInt(0), c.getInt(1), Int::plus) }
                }
            }
            val days = dayMap.entries.sortedBy { it.key }.map { ChannelEraPolicy.DayCount(it.key, it.value) }
            if (days.isEmpty()) return null

            // ---- ③ 峰日窗行（globalMax 日；时间索引原语，索引未就绪=证据一诚实缺席） ----
            val peak = days.maxByOrNull { it.count } ?: days.first()
            val peakItems = dayWindowItems(vm, chatId, peak.epochDay * 86_400L, CHRON_DAY_WINDOW_LIMIT)

            // ---- ③ 时长离群账（视频类、时长已知；中位两行法 + 最长行） ----
            // R266 v11 双臂：中位 OFFSET 无法跨臂分解——两臂各取时长列 Kotlin 侧合并
            // 排序取中位（频道域有界：视频条数 ≤ 频道条数上限，纯内存排序毫秒级）
            var medianDur = 0
            run {
                val durs = ArrayList<Int>(1024)
                for (table in listOf("items", "repost_refs")) {
                    runCatching {
                        d.rawQuery(
                            "SELECT durationSec FROM $table WHERE chatId=? AND mediaType=0 AND durationSec>0",
                            arrayOf(chatId.toString()),
                        ).use { c -> while (c.moveToNext()) durs += c.getInt(0) }
                    }
                }
                val nDur = durs.size
                if (nDur > 0) {
                    durs.sort()
                    medianDur = if (nDur % 2 == 1) durs[nDur / 2] else (durs[(nDur - 1) / 2] + durs[nDur / 2]) / 2
                }
            }
            var topRow: ChannelEraPolicy.EraItemRow? = null
            for (table in listOf("items", "repost_refs")) {
                // R266 v11 双臂：两臂各取最长行，Kotlin 侧取更大者（LIMIT 1 有界不变）
                runCatching {
                    d.rawQuery(
                        "SELECT messageId, dateEpochSec, mediaType, durationSec FROM $table " +
                            "WHERE chatId=? AND mediaType=0 AND durationSec>0 AND dateEpochSec>0 " +
                            "ORDER BY durationSec DESC LIMIT 1",
                        arrayOf(chatId.toString()),
                    ).use { c ->
                        if (c.moveToFirst()) {
                            val dur = c.getInt(3)
                            if (topRow == null || dur > topRow!!.durationSec) {
                                topRow = ChannelEraPolicy.EraItemRow(c.getLong(0), c.getLong(1), c.getInt(2), dur, 0L, "")
                            }
                        }
                    }
                }
            }
            // 最长行取回整条（先秒窗后日窗两档——都 miss=极罕见降级行，依据行仍出）
            val topItem = topRow?.let { r ->
                dayWindowItems(vm, chatId, r.dateSec, 16).firstOrNull { it.messageId == r.messageId }
                    ?: dayWindowItems(vm, chatId, r.dateSec, CHRON_DAY_WINDOW_LIMIT)
                        .firstOrNull { it.messageId == r.messageId }
            }

            // ---- ③④ 链样本（新→旧 800 保新弃旧——episodeChains 纯函数复用件） ----
            val chainSample = vm.deepIndex.channelItemsPage(chatId, CHRON_CHAIN_SAMPLE, 0)
            val chainLeads = episodeChains(chainSample).map { ch ->
                val first = ch.episodes.first().item
                ChannelEraPolicy.ChainLead(
                    ch.episodes.size, first.dateEpochSec.toLong(), first.messageId, first.titleLine(),
                )
            }

            // ---- 卷③ 峰作品（纯函数三证据选择）+ 可播放条目池（messageId 直配） ----
            val works = ChannelEraPolicy.peakWorksOf(medianDur, peak.count, peakItems.map(::eraRowOf), topRow, chainLeads)
            val itemById = HashMap<Long, VideoItem>(peakItems.size + chainSample.size + 2)
            for (it in peakItems) if (it.messageId != 0L) itemById[it.messageId] = it
            for (it in chainSample) if (it.messageId != 0L) itemById[it.messageId] = it
            topItem?.let { if (it.messageId != 0L) itemById[it.messageId] = it }
            val workRows = works.map { PeakWorkRow(it, itemById[it.messageId]) }

            // ---- 卷④ 里程碑（COUNT 门内才查：创刊 + 第 N 条 + 系列开篇） ----
            val total = vm.deepIndex.indexedCount(chatId)
            val first = if (total > 0) vm.deepIndex.channelItemsPage(chatId, 1, total - 1).firstOrNull() else null
            val nthItems = ChannelEraPolicy.MILESTONE_STEPS.filter { it <= total }.map { n ->
                n to vm.deepIndex.channelItemsPage(chatId, 1, total - n).firstOrNull()
            }
            if (first != null && first.messageId != 0L) itemById[first.messageId] = first
            for ((_, r) in nthItems) if (r != null && r.messageId != 0L) itemById[r.messageId] = r
            val mss = ChannelEraPolicy.milestonesOf(
                first?.let(::eraRowOf),
                total,
                nthItems.mapNotNull { (n, r) -> r?.let { n to eraRowOf(it) } },
                chainLeads,
            )
            val msRows = mss.map { MilestoneRow(it, itemById[it.messageId]) }

            // ---- 卷⑤ 沉寂与复活（纯函数：gap>7 天标记；30 天独立行；回归窗首条） ----
            val nowDay = (System.currentTimeMillis() / 86_400_000L).toInt()
            val lastActDay = if (lastActivitySec > 0L) (lastActivitySec / 86_400L).toInt() else 0
            val hiatuses = ChannelEraPolicy.hiatusesOf(days, nowDay, lastActDay)
            val dormant = ChannelEraPolicy.dormantEntriesOf(hiatuses)
            val dormantRows = dormant.map { h ->
                val ret = if (h.returned) {
                    dayWindowItems(vm, chatId, h.returnDay * 86_400L, CHRON_DAY_WINDOW_LIMIT)
                        .sortedWith(compareBy({ it.dateEpochSec }, { it.messageId }))
                        .firstOrNull()
                } else {
                    null
                }
                DormantRow(h, ret)
            }

            ChronicleVolumes(
                works = workRows,
                milestones = msRows,
                dormant = dormantRows,
                shortPauses = hiatuses.count { it.days < ChannelEraPolicy.DORMANT_ENTRY_MIN_DAYS },
                hiatusSummary = ChannelEraPolicy.hiatusSummaryOf(hiatuses),
                totalItems = total,
            )
        } finally {
            runCatching { d.close() }
        }
    }.getOrNull()
}

// ==================== R251 卷宗 UI 组件（卷头折叠 / 卷身 / ⑥互联卷；R381 卷宗卡层次升级） ====================

// ---------------- R381 卷宗卡层次几何（单源常量——R381bWiringSourceTest 锚定） ----------------

/** 卷身档案纸面左缩进（卷头浮起卡 vs 卷身内嵌段的层级对比） */
private val ChroniclePaperInset = 8.dp
/** ④里程碑小脊柱：行节点圆心横向位（行内边距 4dp+点半径 3dp——节点串对齐线位） */
private val ChronicleMiniSpineX = 7.dp

/**
 * R251 卷头（六卷共用折叠头）→ R381 卡化（LP6 正治）：SurfaceHigh 圆角（RadiusSm）
 * +0.5dp OverlayHairline 发丝描边（StarCardFoldHeader 同族）+卷名半粗+摘要+
 * chevron 图标（Icons.Outlined.ExpandMore；展开态 AnimFast 旋转 180°=卷头应答，
 * LP9「▸/▾」文本 glyph 退役）。摘要缺席=只出卷名；密度红线：卷头恒 1 行。
 * 点=折叠切换——「R251 卷展开」探针在调用侧。
 */
@Composable
private fun ChronicleVolumeHeader(
    title: String,
    summary: String?,
    open: Boolean,
    onToggle: () -> Unit,
) {
    // chevron 旋转（卷头应答）：收起 0° → 展开 180°（AnimFast 既有档）
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "chronicleVolumeChevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusSm))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = if (open) "收起本卷" else "展开本卷",
            tint = TgFeedTokens.TextLow,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

/**
 * R251 卷身折叠（expandVertically/shrinkVertically + fadeIn/fadeOut，tween=AnimMed
 * 220ms 曲线——R248 折叠面板模式的卷级延展；时序类改动走纪律§四录屏逐帧取证）
 * → R381 卷身档案纸面（LP7 正治）：内容包一层左缩进「纸面」底（Surface α0.35
 * 圆角+左缘 1dp TextLow 发丝装订线）——卷头浮起卡 vs 卷身内嵌段的层级对比。
 */
@Composable
private fun VolumeFold(open: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(TgFeedTokens.AnimMed)) + fadeIn(tween(TgFeedTokens.AnimMed)),
        exit = shrinkVertically(tween(TgFeedTokens.AnimMed)) + fadeOut(tween(TgFeedTokens.AnimMed)),
    ) {
        // 卷身纸面：左缩进 + 半透纸底 + 左缘装订线（发丝竖线）
        Row(
            Modifier
                .padding(start = ChroniclePaperInset)
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                .background(TgFeedTokens.Surface.copy(alpha = 0.35f))
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(TgFeedTokens.TextLow),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * R251⑥ 互联档案卷（互转/互链/共现三类账，主类计数前置——[ChannelEraPolicy.interlink
 * LineOf] 单口文案）：top [ChannelEraPolicy.INTERLINK_MAX] 邻居行 + 星色小头像点 +
 * 点=飞过去（[onFly] 与 R248 相邻恒星 pills 同链）。**无互联证据=整卷隐藏**（R246
 * 数据薄不出卷）；对端星不在图=行不出现（账面双方都在池内才建——正常恒在）。
 */
@Composable
internal fun ChronicleInterlinkVolume(
    rels: List<NeighborRel>,
    starsById: Map<Long, StarNode>,
    open: Boolean,
    onToggle: () -> Unit,
    onFly: (StarNode) -> Unit,
) {
    if (rels.isEmpty()) return
    ChronicleVolumeHeader("⑥ 互联档案", "互联 ${rels.size} 频道", open, onToggle)
    VolumeFold(open) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rels.take(ChannelEraPolicy.INTERLINK_MAX).forEach { rel ->
                val nb = starsById[rel.chatId] ?: return@forEach
                val line = ChannelEraPolicy.interlinkLineOf(rel.forward, rel.link, rel.coWatch)
                if (line.isEmpty()) return@forEach
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                        .clickable { onFly(nb) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(nb.colorArgb)),
                    )
                    Column {
                        Text(
                            nb.title.ifBlank { "未收录频道" },
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextLow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ==================== R301 行星世界卡（design-dual-form §3 落地——点行星新默认） ====================

/** R301 世界卡环绕角速度（rad/s——60s 一圈的舒缓环绕，「泊入港区看这个世界」） */
private const val WORLD_CARD_ORBIT_RAD_PER_SEC = 0.1047f

/** R301 世界卡关窗回位时长（秒——easePose smoothstep 缓动） */
private const val WORLD_CARD_RETURN_SEC = 0.8f

/** R301 世界卡俯冲白闪时长（毫秒——A 案反向错觉拼接） */
private const val WORLD_CARD_DIVE_MS = 320

/** R301 行星世界卡目标（宿主恒星 chatId + tag——行星在快照/LRU 里现查） */
private class WorldCardTarget(val chatId: Long, val tag: String)

/** R312 点亮即时科普小卡自收时长（毫秒——具名常数，MotionTokens 政策合规形态） */
private const val LIT_TOAST_MS = 5_200L

/**
 * R328 点燃白闪 UI 幅——白洗（毫）：星核白闪峰值 IGNITION_FLASH_BOOST=0.8 的
 * 卡面回声取 45%（醒目不糊字——toast 是文字件非星核）。
 */
private const val IGNITION_TOAST_WASH = 0.45f

/** R328 点燃白闪 UI 幅——白边（毫）：峰值 55%（洗层之上描边透读） */
private const val IGNITION_TOAST_RIM = 0.55f

/**
 * R312 点亮即时科普小卡载荷（发现时刻一拍至多一颗——批处理置位、overlay 消费）：
 * [line]=AstroFacts.instantLine 一句话科普（全数据驱动，缺据无数字变体）。
 * R328 [ignition]=点燃拍位（年轻变体专属——kind==PROTOSTAR ∨ birth 注记非空，
 * pollGalaxyBeatsR254 的 youngIdsR327 同判别单口）：true=[AstroLitToastCard]
 * 叠 300ms 一次性点燃白闪（时长/峰值=TasteBirthFx 点燃参数单源——批 C 点燃拍
 * 的 UI 侧半体）；false=老星现状零加。
 */
private class AstroLitToast(
    val chatId: Long,
    val kind: CelestialKind,
    val line: String,
    val atMs: Long,
    val ignition: Boolean = false,
)

/**
 * R301 世界卡相机状态袋（帧循环直读——让位经 abortGalaxyTourR271 单口）：
 * 开卡记 [savedPose]（回位点；卡间换绑 keepPose 保留最初点）；环绕许可
 * [orbitEnabled]（任何手势即刻终止本卡会话环绕——卡不强制关）；回位插值
 * （[returnActive]/[returnP01]/[returnFrom]/[returnTo]，关卡时由
 * closeWorldCardR316 置位——R316b 迁顶层后改名）。
 */
private class WorldCamState {
    var savedPose: SpaceCam? = null
    var orbitEnabled = true
    var returnActive = false
    var returnP01 = 0f
    var returnFrom: SpaceCam? = null
    var returnTo: SpaceCam? = null
}

/**
 * R301 行星世界卡（点行星新默认——design-dual-form §3，用户终审批准的行为变更；
 * R269 直进内容流降为卡内首键「▶ 直接开刷」，链体零改动）。
 *
 * 行星属主语义三层各就各位（诚实口径分行标注）：
 *  - 该频道层：条数（账本口径——topTags 全频道历史）+ 时期弧（该 tag 月度发布
 *    柱条，ChannelEraPolicy 族分桶同尺）+ 代表作（R408b 起动态装载：该频道+该 tag
 *    索引分页，恒星信息卡共享件同口径；索引无货回落行星 itemIdx 近端样本，
 *    点=onPlayDust 单条直播原链）；
 *  - 跨恒星层：同 tag 其他恒星 pills（tagChannels 全站账本 ∩ 在图星——点=飞过去
 *    +卡片换绑，世界卡同时是虫洞的图形化宿主：长按行星的虫洞菜单原样保留）；
 *  - 全宇宙层：「▶ 直接开刷」=该 tag 全宇宙内容流（R269 语义锚）。
 * 底三键：[▶ 直接开刷]（俯冲白闪→R269 链）/ [时间轴] / [搜索播放]（R222 弹层
 * 旧两行降级迁入——飞入门弹层不删，飞进门场景保留）。
 *
 * 相机叙事（主屏侧帧循环段消费 WorldCamState）：开卡=泊入（focusFlight 井×~2.2
 * 中距）→到位后环绕（60s/圈）→关卡=回位（easePose 0.8s）；任何手势即刻让位
 * （abortGalaxyTourR271 单口——卡留着，环绕停）。
 */
@Composable
private fun PlanetWorldCard(
    target: WorldCardTarget,
    starsById: Map<Long, StarNode>,
    planetSysSnapshot: Map<Long, TastePlanetEntry>,
    vm: FeedViewModel,
    gateway: TdGateway,
    app: android.app.Application,
    onPlayDust: (item: VideoItem, batch: List<VideoItem>, label: String) -> Unit,
    onPlayContent: (String) -> Unit,
    onOpenTimeline: (String) -> Unit,
    onSearchPlay: (String) -> Unit,
    onFlyToStar: (chatId: Long, tag: String) -> Unit,
    onClose: () -> Unit,
    // R312：科普行相关天体的宇宙计数原料（信息卡科普段同尺——kindCounts 直供）
    kindCounts: Map<CelestialKind, Int> = emptyMap(),
) {
    // 系统返回=关卡（页级 BackHandler 在更外层——本卡声明在后优先接管）
    BackHandler(onBack = onClose)
    val hostStar = starsById[target.chatId]
    val entry = planetSysSnapshot[target.chatId]
    val foldTag = remember(target.tag) { SearchTextKit.fold(target.tag) }
    val planet = entry?.system?.planets?.firstOrNull { SearchTextKit.fold(it.tag) == foldTag }
    // 代表作快照样本（回落源）：行星 itemIdx（发布时间升序）倒序=近端样本最新——尘埃播放批同序
    val sampleCovers = remember(entry, target.tag) {
        val p = planet ?: return@remember emptyList<VideoItem>()
        p.itemIdx.reversed().mapNotNull { entry?.items?.getOrNull(it) }
    }
    // ---- R408b 代表作动态装载（用户令：行星卡代表作逻辑参考恒星信息卡代表作）——
    //      收编恒星信息卡共享件：可见数 N/半批同尺（rememberPreviewDims）、初载 N+ceil(N/2)、
    //      右滑临近尾懒载 ceil(N/2)（PreviewNearEndEffect+appendBatch：护栏 PREVIEW_MAX+
    //      key 去重+空批封窗）。数据源=本地深度索引「该频道+该 tag」分页
    //      （channelTagItemsPage，messageId 新→旧，恒星侧 channelItemsPage 同序口径）；
    //      索引查不到（未覆盖/查询失败）回落快照样本并封窗（诚实降级——样本不分页） ----
    val coverDims = rememberPreviewDims()
    val coverWin = remember(target.chatId, foldTag) { PreviewWindowState() }
    val coverListState = remember(target.chatId, foldTag) { LazyListState() }
    var coverFromIndex by remember(target.chatId, foldTag) { mutableStateOf(false) }
    // 初载 effect 跨 IO 挂起后读最新样本（装配在途样本可能已刷新——防陈旧捕获）
    val latestSample by androidx.compose.runtime.rememberUpdatedState(sampleCovers)
    LaunchedEffect(target.chatId, foldTag) {
        val id = target.chatId
        val tag = target.tag
        val initialCount = coverDims.initialCount
        coverWin.loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { vm.deepIndex.channelTagItemsPage(id, tag, initialCount, 0) }
                .getOrDefault(emptyList())
        }
        if (loaded.isNotEmpty()) {
            coverFromIndex = true
            coverWin.setInitial(loaded, initialCount)
        } else {
            coverFromIndex = false
            coverWin.setInitial(latestSample, Int.MAX_VALUE) // 回落样本=封窗
        }
        android.util.Log.i(
            "TasteMap",
            "R408b 世界卡代表作初载: 源=${if (coverFromIndex) "索引" else "样本回落"} " +
                "到货=${coverWin.items.size}（N=${coverDims.n} 初载=$initialCount" +
                "${if (coverWin.exhausted) "·封窗" else ""}）",
        )
    }
    // 快照样本晚到（行星系统装配在途）且索引无货：回落窗随样本刷新
    LaunchedEffect(sampleCovers) {
        if (!coverFromIndex && !coverWin.loading && sampleCovers.isNotEmpty()) {
            coverWin.setInitial(sampleCovers, Int.MAX_VALUE)
        }
    }
    PreviewNearEndEffect(coverListState, coverDims.half, target.chatId, foldTag, coverWin.exhausted) { _, _ ->
        if (!coverFromIndex) return@PreviewNearEndEffect
        val id = target.chatId
        val tag = target.tag
        val r = coverWin.appendBatch(coverDims.half) { offset, limit ->
            vm.deepIndex.channelTagItemsPage(id, tag, limit, offset)
        } ?: return@PreviewNearEndEffect
        android.util.Log.i(
            "TasteMap",
            "R408b 世界卡代表作批次: offset=${r.offset} 到货=${r.fresh} 累计=${coverWin.items.size}" +
                "（批=${r.batch}${if (coverWin.exhausted) " 封窗" else ""}）",
        )
    }
    val covers = coverWin.items
    // 时期弧（该频道该 tag 月度柱——GalaxyPortholeLoader 共享装载层；IO 毫秒级）
    val era by produceState<GalaxyPortholeLoader.TagEraInfo?>(null, target.chatId, target.tag) {
        value = withContext(Dispatchers.IO) {
            runCatching { GalaxyPortholeLoader.loadTagEra(app, target.chatId, target.tag) }.getOrNull()
        }
    }
    // 同 tag 其他恒星（tagChannels 全站账本 ∩ 在图星——点=飞过去的可行域）；
    // 权重降序原序（账本口径），裁 6
    val otherStars by produceState<List<Pair<StarNode, Int>>>(emptyList(), target.tag, target.chatId, starsById) {
        value = withContext(Dispatchers.IO) {
            runCatching { vm.deepIndex.tagChannels(target.tag) }.getOrDefault(emptyList())
        }.mapNotNull { (id, cnt) ->
            if (id == target.chatId) return@mapNotNull null
            starsById[id]?.let { it to cnt }
        }.take(6)
    }
    // 条数口径：行星账本计数（topTags 全频道历史）优先；时期弧总数兜底
    val itemCount = planet?.count ?: era?.total ?: 0
    // R342 D2：模型拖动态旗（卡根手势门防误收卡——模型区纵拖=俯仰旋转不收卡）
    val cardModelDragging = remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            // R342 D2：共享手势门（穿透根治——本卡根此前无 pointerInput=非交互
            // 区命中透明→down 下探画布槽手势层=「卡上滑动把星图拖动」的用户实锤；
            // 门=命中占用（兄弟层排他→星图隔离，星图侧零改动红线）+Initial 纯
            // 观察不消费+下滑收卡（onClose=相机回位链——信息卡同款卡语言）+
            // 模型拖动旗防误收。**新卡一票纪律：凡星图内弹出的卡必挂本门**）
            .tasteSheetGestureGate(
                key = target.chatId,
                onDismissDown = { onClose() },
                modelDragging = cardModelDragging,
            )
            .navigationBarsPadding()
            .avoidBottomBar() // R226 统一避让钥匙（同信息卡）
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        // ---- 头行：◉ #tag · 绕 ★宿主 转 + 关闭 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(hostStar?.bodyColor ?: GalaxyPorthole.CENTER_COLOR)),
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Column(Modifier.weight(1f)) {
                Text(
                    "◉ #${target.tag}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TgFeedTokens.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "绕 ★${hostStar?.title?.ifBlank { null } ?: "未收录频道"} 转",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = TgFeedTokens.TextMedium,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
            )
        }
        // ---- R310 恒星系 3D 小模型（用户需求「360° 无死角拖动观测+可点击交互」）：
        //      恒星真球+tag 行星+行星环 GL 渲染（R281 底座复用）；拖动=轨道相机旋转
        //      （惯性阻尼 GestureFeelPolicy 同族）；点行星=选中高亮+同星换绑
        //      （onFlyToStar(target.chatId, ·)=openWorldCardR316 keepPose 原链（R316b 迁顶层）——宿主
        //      零新接线）；GL 致命/不支持=撤段兜底（R301 信息段照旧）；关卡即释放；
        //      R342：+onDragStateChange（门防误收旗）+高度三档改共享口 miniModelHeightDp ----
        val miniPlanets = entry?.system?.planets
        var miniGlOk by remember(target.chatId) { mutableStateOf(probeEs3(app)) }
        if (!miniPlanets.isNullOrEmpty() && miniGlOk) {
            // 模型高三档随屏高（R301 世界卡无上滚——矮屏/横屏防顶行挤出卡外；
            // R342 收口共享口与信息卡同尺——单一出处）
            val miniModelH = miniModelHeightDp(LocalConfiguration.current.screenHeightDp)
            StarSystemMiniModel(
                chatId = target.chatId,
                starColorArgb = hostStar?.bodyColor ?: GalaxyPorthole.CENTER_COLOR,
                planets = miniPlanets,
                selectedTag = target.tag,
                onPlanetTap = { tag ->
                    if (SearchTextKit.fold(tag) != foldTag) {
                        android.util.Log.i(
                            "TasteMap",
                            "R310 世界卡3D: 点行星换绑（tag 长=${tag.length}）——卡内联动",
                        )
                        onFlyToStar(target.chatId, tag) // 同星换绑=keepPose 原链
                    }
                },
                onFatal = { miniGlOk = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(miniModelH),
                onDragStateChange = { cardModelDragging.value = it }, // R342 门防误收旗
            )
        }
        // ---- 条数 + 时期弧（该频道层） ----
        Text(
            buildString {
                append("该频道 ").append(itemCount).append(" 条")
                era?.peakLabel?.let { append(" · 高峰 ").append(it) }
                if (planet == null && itemCount == 0) append("（索引样本外——该频道近端样本未命中此 tag）")
            },
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val eraNow = era
        if (eraNow != null && eraNow.monthly.size >= 2) {
            MonthlyBarsCanvas(eraNow.monthly)
        }
        // ---- R312 行星科普行（轻折叠——「这是什么」词条+本行星实况+相关天体；
        //      信息卡科普段同族共用件 AstroRelatedChips） ----
        PlanetScienceRowR312(
            count = itemCount,
            kindCounts = kindCounts,
        )
        // ---- 代表作（R408b 动态装载：共享件 PreviewThumbRow；点=单条直播原链；批=当前已装载窗） ----
        if (covers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "代表作（点=直接播）",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
                PreviewThumbRow(
                    items = covers,
                    listState = coverListState,
                    gateway = gateway,
                ) { item ->
                    android.util.Log.i(
                        "TasteMap",
                        "R301 世界卡代表作: 播放该条（批 ${covers.size} 条）",
                    )
                    onPlayDust(item, covers, "行星世界 · #${target.tag}")
                }
            }
        }
        // ---- 同 tag 其他恒星（跨恒星层——点=飞过去+卡片换绑） ----
        if (otherStars.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "同 tag 的其他恒星 ${otherStars.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    otherStars.forEach { (nb, cnt) ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                .background(TgFeedTokens.OverlayBg)
                                .border(
                                    0.5.dp, TgFeedTokens.OverlayHairline,
                                    RoundedCornerShape(TgFeedTokens.RadiusPill),
                                )
                                .clickable {
                                    android.util.Log.i(
                                        "TasteMap",
                                        "R301 世界卡同tag恒星: 尾号${(nb.chatId % 10000L + 10000L) % 10000L}（$cnt 条）——飞过去+换绑",
                                    )
                                    onFlyToStar(nb.chatId, target.tag)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(nb.bodyColor)),
                            )
                            Text(
                                "★${nb.title.ifBlank { "未收录频道" }}（$cnt）",
                                style = MaterialTheme.typography.labelSmall,
                                color = TgFeedTokens.TextMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        // ---- 底三键（全宇宙层主键 + R222 旧两行降级迁入） ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { onPlayContent(target.tag) },
                modifier = Modifier
                    .weight(1.4f)
                    .height(38.dp),
                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
            ) {
                Text(
                    "▶ 直接开刷",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = { onOpenTimeline(target.tag) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
            ) {
                Text("时间轴", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            TextButton(
                onClick = { onSearchPlay(target.tag) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
            ) {
                Text("搜索播放", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

// ==================== R302 RPG 成长循环（design-rpg-loop 终审稿落地——星辉等级单成长轴/
//      探索者编年史勋章组/数值地狱防火墙。账本/曲线/解锁/迷雾=core TasteStarlight
//      纯函数件；本节=屏幕侧薄接线（装载/记账/演出/指引）。红线：R235 五阶判据零触碰、
//      R248 点亮判定零改（星辉只加账）、R282 导演器零改（目击只读指令流）、虫洞人人
//      可用不回收、零 Search 族、迷雾只降亮 ≤20% 绝不锁内容） ====================

/** R302 信标投影暂存（两叠加 Canvas 共用——主线程 draw 同拍单消费者，r282Proj 先例） */
private val r302BeaconProj = FloatArray(4)

/** R302 迷雾质心帧级暂存（cx,cy,cz,reach——帧组装单消费者，主线程） */
private val r302MistBuf = FloatArray(4)

/**
 * R302 星辉三源重算+发布（薄接线单口）：IO 读点亮账/虫洞账/增量账重算 → 发布
 * 快照 → 升级检测（等级跳变=演出事件）→ 指引重解算。**重算即迁移**：等级永远是
 * 账本的纯函数，旧机升级/账本手删/口径调整全由重算自愈。
 */
private suspend fun refreshStarlightR302(ctx: GalaxyLogicCtx, wellMaxVol: Int) {
    val galaxyDir = ctx.starlightFile.parentFile ?: return
    val book = withContext(Dispatchers.IO) {
        runCatching { TasteStarlight.loadProgress(galaxyDir) }.getOrNull()
    } ?: return
    publishStarlightR302(ctx, book, wellMaxVol)
}

/** R302 发布星辉快照（主线程）：等级跳变=演出事件（首次装载 prev=null 不演） */
private fun publishStarlightR302(ctx: GalaxyLogicCtx, book: TasteStarlight.Book, wellMaxVol: Int) {
    val prev = ctx.starlightBook.value
    ctx.starlightBook.value = book
    // 会话防重闸预填（账本=真源——点亮账跨会话持久，闸只兜 IO 窗）
    ctx.planetLitSession.addAll(book.ledger.planets)
    ctx.witnessSession.addAll(book.ledger.witness)
    if (prev != null && book.level > prev.level) {
        ctx.levelUpEvent.value = book.level
        android.util.Log.i(
            "TasteMapX",
            "R302 升级: Lv${prev.level}→Lv${book.level} 星辉=${book.total} " +
                "（点亮${book.litCount} 行星${book.ledger.planets.size} 星域${book.ledger.cells.size} " +
                "虫洞${book.jumps} 目击${book.ledger.witness.size} 红包${book.ledger.milestones.size}）" +
                "解锁=${TasteStarlight.UNLOCKS.firstOrNull { it.level == book.level }?.label ?: "—"}",
        )
    }
    resolveGuidanceR302(ctx, book, wellMaxVol)
}

/**
 * R302 点亮记账（发现时刻同拍——R248 持久化写链的唯一延伸点）：
 * 点亮账 addAll（原样）→ 首踏星域格判账（同格一次 +25）→ 里程碑红包
 * （10/50/100/300 跨档 +50）→ 增量账落盘 → 三源重算发布。
 */
private suspend fun awardLightingR302(ctx: GalaxyLogicCtx, litIds: List<Long>, wellMaxVol: Int) {
    val r = withContext(Dispatchers.IO) {
        val lit = runCatching { TasteDiscoveryLog.addAll(ctx.discoverFile, litIds) }
            .getOrDefault(emptySet())
        var ledger = TasteStarlight.loadLedger(ctx.starlightFile)
        var changed = false
        val cellsNow = ctx.nebulaCellsByChat.value
        val newCells = ArrayList<Int>(2)
        for (id in litIds) {
            val cell = cellsNow[id] ?: continue
            val (l2, added) = TasteStarlight.addCell(ledger, cell)
            if (added) {
                ledger = l2
                newCells += cell
                changed = true
            }
        }
        val (l3, newMilestones) = TasteStarlight.claimMilestones(ledger, lit.size)
        if (newMilestones.isNotEmpty()) {
            ledger = l3
            changed = true
        }
        if (changed) TasteStarlight.saveLedger(ctx.starlightFile, ledger)
        val galaxyDir = ctx.starlightFile.parentFile
        val book = galaxyDir?.let {
            runCatching { TasteStarlight.loadProgress(it) }.getOrNull()
        }
        Triple(book, newCells, newMilestones)
    }
    val book = r.first ?: return
    publishStarlightR302(ctx, book, wellMaxVol)
    if (r.second.isNotEmpty() || r.third.isNotEmpty()) {
        android.util.Log.i(
            "TasteMapX",
            "R302 星辉记账: 首踏星域格=${r.second} 里程碑红包=${r.third} → 总额=${book.total} Lv${book.level}",
        )
    }
}

/**
 * R302 目击记账（导演指令执行同帧调用——主线程）：**目击判定=终审稿 §3 井深比
 * 口径**（新星/超新星发火瞬间相机对该星井深比 <[TasteStarlight.WITNESS_WELL_RATIO]；
 * 彗星无位置锚=在场即目击，诚实口径成文）。会话去重闸 → IO 落账 → 重算发布。
 * 只读指令流——导演器零改动（三原则红线）。
 */
private fun awardWitnessR302(
    ctx: GalaxyLogicCtx,
    kind: UniverseDirector.EventKind,
    chatId: Long,
    epochMs: Long,
    wellMaxVol: Int,
) {
    val witnessed = if (kind == UniverseDirector.EventKind.COMET) {
        true // 彗星划过天幕=你在场即目击（chatId=0 无位置锚）
    } else {
        val star = ctx.mapData.value?.stars?.firstOrNull { it.chatId == chatId }
        if (star == null) {
            false // 不在图=无目击对象（诚实缺省）
        } else {
            // 井深比取 N 体当帧渲染位（「目击=看见」对移动星同样诚实——点亮同口径）
            val has = ctx.physics.value?.posOfChat(chatId, ctx.pollPos) == true
            val wx = if (has) ctx.pollPos[0] else star.spX
            val wy = if (has) ctx.pollPos[1] else star.spY
            val wz = if (has) ctx.pollPos[2] else star.spZ
            TasteStarlight.witnessedByWell(
                galaxyWellRatio(wx, wy, wz, star.itemsCount, wellMaxVol, ctx.cam.value),
            )
        }
    }
    if (!witnessed) return
    val key = "$epochMs|${kind.ordinal}|$chatId"
    if (!ctx.witnessSession.add(key)) return // 会话去重（IO 窗内防重闸）
    ctx.scope.launch {
        val book = withContext(Dispatchers.IO) {
            val ledger = TasteStarlight.loadLedger(ctx.starlightFile)
            val (l2, added) = TasteStarlight.addWitness(ledger, epochMs, kind, chatId)
            if (!added) return@withContext null
            TasteStarlight.saveLedger(ctx.starlightFile, l2)
            val galaxyDir = ctx.starlightFile.parentFile ?: return@withContext null
            runCatching { TasteStarlight.loadProgress(galaxyDir) }.getOrNull()
        } ?: return@launch
        publishStarlightR302(ctx, book, wellMaxVol)
        val tail = if (chatId != 0L) " 尾号${(chatId % 10000L + 10000L) % 10000L}" else ""
        android.util.Log.i(
            "TasteMapX",
            "R302 目击: ${kind.label}$tail +${TasteStarlight.AWARD_WITNESS} 星辉" +
                "（图鉴 新星${book.counts.nova}/超新星${book.counts.supernova}/彗星${book.counts.comet}）",
        )
    }
}

/**
 * R302 编年史勋章矩阵（成就面板/指引解算同一出处——纪律§一）：点亮率=已知并集
 * 口径（与面板「已探索 N/M」/R248 成就 litStars 同一尺）。
 */
private fun chronMedalsOfR302(ctx: GalaxyLogicCtx, book: TasteStarlight.Book): List<TasteAchievements.Medal> {
    val d = ctx.mapData.value
    val channelStars = d?.stars?.filter {
        it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
    }.orEmpty()
    val known = ctx.knownIds.value
    val litKnown = if (known == null) 0 else channelStars.count { it.chatId in known }
    val litRatio = if (channelStars.isEmpty()) 0f else litKnown.toFloat() / channelStars.size
    return TasteStarlight.chronicleMedals(
        TasteStarlight.ChronicleInput(
            litCount = book.litCount,
            jumps = book.jumps,
            nova = book.counts.nova,
            supernova = book.counts.supernova,
            comet = book.counts.comet,
            cells = book.ledger.cells.size,
            litRatio01 = litRatio,
        ),
    )
}

/**
 * R302 「最近的下一枚」指引解算（成就面板=航海图的核心）：勋章矩阵 → 最近下一枚
 * → 文案+星图信标目标（世界系）。空间目标：点亮族=最近未点亮星；星域行者=最近
 * 未首踏星域格的一颗成员星（雾团的诚实代表——团心在 V3 映射后无成员坐标逆映射，
 * 指到该星域一颗星=「去那边」的落地）；事件族=无空间目标（宇宙的节奏不是地点）。
 */
private fun resolveGuidanceR302(ctx: GalaxyLogicCtx, book: TasteStarlight.Book, wellMaxVol: Int) {
    val d = ctx.mapData.value
    if (d == null || d.stars.isEmpty()) {
        ctx.guidanceLine.value = null
        ctx.beaconPos.value = null
        return
    }
    val known = ctx.knownIds.value
    val channelStars = d.stars.filter {
        it.kind != CelestialKind.PULSAR && it.kind != CelestialKind.CLUSTER && it.kind != CelestialKind.METEOR
    }
    val medals = chronMedalsOfR302(ctx, book)
    when (TasteStarlight.nextChronicleKey(medals)) {
        null -> {
            ctx.guidanceLine.value = "编年史七枚全齐——这片宇宙都是你的故事"
            ctx.beaconPos.value = null
        }
        TasteStarlight.CHRON_VOYAGER -> {
            ctx.guidanceLine.value = "最近的下一枚「穿越者」——长按一颗行星，开启第一次虫洞跳跃"
            ctx.beaconPos.value = null
        }
        TasteStarlight.CHRON_NOVA -> {
            ctx.guidanceLine.value = "最近的下一枚「新星目击者」——新星发火时飞在它附近（井深比<15）即目击，多在星图驻留等一场诞生"
            ctx.beaconPos.value = null
        }
        TasteStarlight.CHRON_SUPERNOVA -> {
            // R348 C7：太空无声——超新星的「巨响」是引力波/中微子不是声音（宪法三章范式四）
            ctx.guidanceLine.value = "最近的下一枚「超新星见证人」——超新星是稀有爆发，驻留星图等一道最亮的闪光"
            ctx.beaconPos.value = null
        }
        TasteStarlight.CHRON_COMET -> {
            ctx.guidanceLine.value = "最近的下一枚「彗星捕手」——彗星掠过时你在场即捕获（${book.counts.comet}/${TasteStarlight.COMET_TARGET}），爬虫发现新频道时回来看看"
            ctx.beaconPos.value = null
        }
        TasteStarlight.CHRON_REGIONS -> {
            // 最近未首踏星域格的成员星（格键=聚类身份锚；sp 烘焙位——N 体漂移远小于星域尺度）
            val cam = ctx.cam.value
            val cellsMap = ctx.nebulaCellsByChat.value
            var bestD2 = Float.MAX_VALUE
            var bx = 0f
            var by = 0f
            var bz = 0f
            var found = false
            for (s in d.stars) {
                val cell = cellsMap[s.chatId] ?: continue
                if (cell in book.ledger.cells) continue
                val dx = s.spX - cam.px
                val dy = s.spY - cam.py
                val dz = s.spZ - cam.pz
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < bestD2) {
                    bestD2 = d2
                    bx = s.spX; by = s.spY; bz = s.spZ
                    found = true
                }
            }
            val remain = TasteStarlight.REGIONS_TARGET - book.ledger.cells.size
            if (found) {
                val dir = TasteStarlight.compassLabel(bx - cam.px, bz - cam.pz)
                ctx.guidanceLine.value = "最近的下一枚「星域行者」——还差 $remain 个星域，${dir}方向雾里有未踏星域"
                ctx.beaconPos.value = floatArrayOf(bx, by, bz)
            } else {
                ctx.guidanceLine.value = "最近的下一枚「星域行者」——还差 $remain 个星域（当前星图无更多成云雾团，等爬虫拓宽边界）"
                ctx.beaconPos.value = null
            }
        }
        else -> {
            // 首光/全图之光：最近未点亮星（屏缘 chevron 的同族目标——信标画环更明确）
            val cam = ctx.cam.value
            var bestD2 = Float.MAX_VALUE
            var bx = 0f
            var by = 0f
            var bz = 0f
            var found = false
            if (known != null) {
                for (s in channelStars) {
                    if (s.chatId in known) continue
                    val dx = s.spX - cam.px
                    val dy = s.spY - cam.py
                    val dz = s.spZ - cam.pz
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 < bestD2) {
                        bestD2 = d2
                        bx = s.spX; by = s.spY; bz = s.spZ
                        found = true
                    }
                }
            }
            val key = TasteStarlight.nextChronicleKey(medals)
            if (key == TasteStarlight.CHRON_FIRST_LIGHT) {
                ctx.guidanceLine.value = "最近的下一枚「首光」——点亮你的第一颗星（飞近未知星即点亮）"
            } else {
                val litKnown = if (known == null) 0 else channelStars.count { it.chatId in known }
                val remain = channelStars.size - litKnown
                ctx.guidanceLine.value = if (found) {
                    "最近的下一枚「全图之光」——还差 $remain 颗未点亮（已探索 $litKnown/${channelStars.size}）"
                } else {
                    "最近的下一枚「全图之光」——还差 $remain 颗（未知星装载中）"
                }
            }
            ctx.beaconPos.value = if (found) floatArrayOf(bx, by, bz) else null
        }
    }
}

/**
 * R302 行星点亮记账（poll 行星拍调用——与飞进行星门同一尺同一拍）：账本幂等
 * （addPlanet 同键不重复奖）；新点才落盘+重算发布。
 */
private suspend fun awardPlanetR302(ctx: GalaxyLogicCtx, chatId: Long, tag: String, wellMaxVol: Int) {
    val book = withContext(Dispatchers.IO) {
        val ledger = TasteStarlight.loadLedger(ctx.starlightFile)
        val (l2, added) = TasteStarlight.addPlanet(ledger, chatId, tag)
        if (!added) return@withContext null
        TasteStarlight.saveLedger(ctx.starlightFile, l2)
        val galaxyDir = ctx.starlightFile.parentFile ?: return@withContext null
        runCatching { TasteStarlight.loadProgress(galaxyDir) }.getOrNull()
    } ?: return
    publishStarlightR302(ctx, book, wellMaxVol)
    android.util.Log.i(
        "TasteMapX",
        "R302 行星点亮: #$tag（宿主尾号${(chatId % 10000L + 10000L) % 10000L}）" +
            "+${TasteStarlight.AWARD_PLANET} 星辉 → 总额=${book.total} Lv${book.level}",
    )
}

/**
 * R302 迷雾质心+雾界帧级计算（帧组装调用——纯算术零分配）：已点亮频道星
 * （已知并集∩频道星）的烘焙位质心 + 等级雾界。[out] 长 ≥4，写 (cx,cy,cz,reach)；
 * 返回 false=无点亮星无雾（out 不动）。
 */
private fun mistFrameR302(
    stars: List<TasteFxStar>,
    knownIds: Set<Long>?,
    level: Int,
    out: FloatArray,
): Boolean {
    if (knownIds == null || knownIds.isEmpty()) return false
    var sx = 0f
    var sy = 0f
    var sz = 0f
    var n = 0
    for (s in stars) {
        if (s.chatId !in knownIds) continue
        if (s.kind == CelestialKind.PULSAR || s.kind == CelestialKind.CLUSTER ||
            s.kind == CelestialKind.METEOR
        ) {
            continue
        }
        sx += s.x; sy += s.y; sz += s.z
        n++
    }
    if (n == 0) return false
    out[0] = sx / n
    out[1] = sy / n
    out[2] = sz / n
    out[3] = TasteStarlight.mistReachOf(level)
    return true
}

/**
 * R302 指引信标（GL/Canvas 两叠加 Canvas 共享单定义——纪律§一）：「最近的下一枚」
 * 的空间目标画脉动金环（屏内可见才画——屏外由 R248 chevron 族指方向）。帧时钟
 * 失效锚由宿主 Canvas 已读（零额外重组）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStarlightBeaconR302(
    ctx: GalaxyLogicCtx,
) {
    val target = ctx.beaconPos.value ?: return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val focal = TasteSpace.focalOf(w, h) * ctx.lens.value
    if (!TasteSpace.project(
            target[0], target[1], target[2], ctx.cam.value, focal, w / 2f, h / 2f, r302BeaconProj,
        )
    ) {
        return // 背后=不画（方向指引由屏缘 chevron 族承担）
    }
    val t = ctx.engine.timeSec
    val pulse = 0.55f + 0.45f * kotlin.math.sin(t * 2.6f)
    val gold = Color(0xFFE8C46A)
    val bx = r302BeaconProj[0]
    val by = r302BeaconProj[1]
    drawCircle(
        color = gold.copy(alpha = 0.55f * pulse),
        radius = 22f + 7f * pulse,
        center = Offset(bx, by),
        style = Stroke(width = 2.2f),
    )
    drawCircle(
        color = gold.copy(alpha = 0.85f * pulse),
        radius = 3.4f,
        center = Offset(bx, by),
    )
}

/**
 * R302 罗盘 HUD（Lv2「星图罗盘」——升级差 ≤20% 时顶部胶囊：「再点亮 N 颗即
 * 升级」；屏缘 chevron 提亮由帧字段 hintBoost 驱动）。
 */
@Composable
private fun BoxScope.StarlightCompassHudR302(book: TasteStarlight.Book) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 54.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "✦ 再点亮 ${book.lightsToNext} 颗即升 Lv${book.level + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextHigh,
            maxLines = 1,
        )
        book.nextUnlock?.let { u ->
            Text(
                "· 解锁「${u.label}」",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
            )
        }
    }
}

/**
 * R302 升级演出（终审稿 §2.1：全屏光雨+跃迁波纹环+「Lv5 · 远视已解锁」横幅——
 * UI 层演出，导演器不介入）：2.8s 一次性，Animatable 单盒驱动；光雨=确定性哈希
 * 落点（同等级同雨——可回放同族哲学），零逐帧分配。
 */
@Composable
private fun StarlightLevelUpOverlayR302(level: Int, onDone: () -> Unit) {
    val p = remember(level) {
        androidx.compose.animation.core.Animatable(0f)
    }
    LaunchedEffect(level) {
        p.animateTo(1f, androidx.compose.animation.core.tween(MotionTokens.GalaxyLevelUpMs))
        onDone()
    }
    val unlock = TasteStarlight.UNLOCKS.firstOrNull { it.level == level }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = p.value
            val env = kotlin.math.sin(kotlin.math.PI.toFloat() * t)
            if (env <= 0.01f) return@Canvas
            val w = size.width
            val h = size.height
            // 光雨（96 滴确定性哈希——同色相随等级微移）
            var i = 0
            while (i < 96) {
                val h1 = ((i * 2654435761L) and 0x7FFFFFFFL).toFloat() / 2147483648f
                val h2 = ((i * 40503 + level * 7919) and 0xFFFF).toFloat() / 65536f
                val speed = 0.35f + 0.65f * h2
                val x = h1 * w
                val y = (((h2 + t * speed * 1.6f) % 1f) * h)
                val rr = 1.6f + 2.6f * h1
                drawCircle(
                    color = Color(0xFFFFE9A8).copy(alpha = env * (0.35f + 0.5f * h2)),
                    radius = rr,
                    center = Offset(x, y),
                )
                i++
            }
            // 跃迁波纹环（屏心扩散——Lv3 预尝件的演出形态）
            val maxR = kotlin.math.hypot(w / 2f, h / 2f)
            drawCircle(
                color = Color(0xFF9FE8E4).copy(alpha = env * 0.5f * (1f - t)),
                radius = maxR * t,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = 3f),
            )
            drawCircle(
                color = Color(0xFF9FE8E4).copy(alpha = env * 0.3f * (1f - t)),
                radius = maxR * t * 0.72f,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = 2f),
            )
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
                .background(TgFeedTokens.OverlayBgStrong)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
                .padding(horizontal = 28.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "✦ 星辉 Lv$level ✦",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFFFE9A8),
                fontWeight = FontWeight.Bold,
            )
            if (unlock != null) {
                Text(
                    "「${unlock.label}」已解锁",
                    style = MaterialTheme.typography.titleMedium,
                    color = TgFeedTokens.TextHigh,
                )
                Text(
                    unlock.desc,
                    style = MaterialTheme.typography.labelMedium,
                    color = TgFeedTokens.TextMedium,
                )
            } else {
                Text(
                    "星海继续为你展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgFeedTokens.TextMedium,
                )
            }
        }
    }
}

/** R302 曲速巡航 HUD（Lv6——巡航中顶部胶囊提示停止口径） */
@Composable
private fun BoxScope.WarpCruiseHudR302() {
    Text(
        "✦ 曲速巡航中 · 任意手势停止",
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 54.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF9FE8E4),
        maxLines = 1,
    )
}

/**
 * R312 点亮即时科普小卡（发现时刻的科普钩子——用户愿景「探索宇宙时顺手学天文」的
 * 第一触点）：顶中 overlay，[LIT_TOAST_MS] 自收；点=开该星信息卡（科普段「为什么」
 * 链在卡里）。文案=AstroFacts.instantLine（全数据驱动，缺据无数字变体）；静态
 * Compose 件零帧循环（60fps 铁律合规）。
 * R328 批 C 点燃拍 UI 半体：[AstroLitToast.ignition]（年轻变体）=卡面 300ms
 * 一次性点燃白闪（白洗+白边；时长/峰值=TasteBirthFx 点燃参数单源——
 * [TasteBirthFx.IGNITION_MS]/[TasteBirthFx.IGNITION_FLASH_BOOST]，包络=
 * [TasteBirthFx.ignitionFlash01] 同式 t=0 峰值线性衰减窗外恒 0）；老星零视觉
 * =现状打字机零改（Animatable 静止零重绘）。
 */
@Composable
private fun BoxScope.AstroLitToastCard(
    toast: AstroLitToast,
    onOpen: () -> Unit,
    onDone: () -> Unit,
) {
    LaunchedEffect(toast.atMs) {
        delay(LIT_TOAST_MS)
        onDone()
    }
    // R328 点燃白闪（一次性窗——R302 升级演出 Animatable 房例同款，有界非循环：
    // 窗后交回静态卡零成本；toast 更替（atMs 键变）即重窗）。**初值=峰值**（§四
    // 时序口径：t=0 峰值从登场帧即成立——免 LaunchedEffect 首拍 16ms 滞后；老星
    // 初值恒 0=零视觉零重绘）
    val ignitionFlash = remember(toast.atMs) {
        Animatable(if (toast.ignition) TasteBirthFx.IGNITION_FLASH_BOOST else 0f)
    }
    LaunchedEffect(toast.atMs) {
        if (toast.ignition) {
            ignitionFlash.animateTo(
                0f,
                tween(
                    durationMillis = TasteBirthFx.IGNITION_MS.toInt(),
                    easing = LinearEasing,
                ),
            )
        }
    }
    val flashK = ignitionFlash.value
    val topic = AstroFacts.topicOfKind(toast.kind)
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 84.dp)
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
            .clickable(onClick = onOpen)
            // R328 白洗 overlay：clip 内尾画（含文字全卡蒙白，300ms 线性衰减；
            // flashK=0 早退=老星零绘制零行为残留）
            .drawWithContent {
                drawContent()
                if (flashK > 0f) drawRect(Color.White.copy(alpha = flashK * IGNITION_TOAST_WASH))
            }
            // R328 点燃白边（随闪值亮起后熄——洗层之上描边透读；窗外 alpha=0 不可见）
            .border(1.dp, Color.White.copy(alpha = flashK * IGNITION_TOAST_RIM), RoundedCornerShape(TgFeedTokens.RadiusLg))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "✨ 点亮了 ${topic.emoji} ${topic.title}",
            style = MaterialTheme.typography.titleSmall,
            color = TgFeedTokens.TextHigh,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            toast.line,
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "点开看「它为什么」→",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
            maxLines = 1,
        )
    }
}
