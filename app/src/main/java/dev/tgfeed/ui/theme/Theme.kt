package dev.tgfeed.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token：从 docs/ui-mockups/feed.png、grid.png 提炼，全 App 统一入口。
 * 组件里不再散落魔法数字，改风格只动这里。
 */
object TgFeedTokens {
    // ---------- 配色 ----------
    /** 沉浸背景·纯黑 */
    val InkBlack = Color(0xFF000000)
    /** 表面一级：底部面板 / 页面卡片 */
    val Surface = Color(0xFF121212)
    /** 表面二级：搜索框 / chip / 菜单 / 图标底托（再浮起一层） */
    val SurfaceHigh = Color(0xFF1C1C1E)
    /** 主色·电报蓝（品牌 / 话题链接 / 主按钮） */
    val TgBlue = Color(0xFF54A9EB)
    /** 强调·抖音红（点赞 / 选中态 / 活跃进度） */
    val AccentRed = Color(0xFFFE2C55)
    /** 收藏星标黄（终稿裁决：降饱和 #FDD835 → #FBBC05，不刺眼） */
    val StarYellow = Color(0xFFFBBC05)
    /** 成功绿（下载完成） */
    val SuccessGreen = Color(0xFF4CAF50)
    /** 警示红（下载失败可重试 / 错误文案） */
    val WarningRed = Color(0xFFFF5252)
    /** 一级文字：标题 / 频道名 / 浮层图标 */
    val TextHigh = Color.White
    /** 二级文字：正文辅助 / 图标下小标签 / 说明 */
    val TextMedium = Color.White.copy(alpha = 0.70f)
    /** 三级文字：弱提示 / 小节标题 */
    val TextLow = Color.White.copy(alpha = 0.45f)

    // ---------- 圆角 ----------
    /** 小圆角：标题点击热区等 */
    val RadiusSm = 8.dp
    /** 中圆角：卡片 / 面板内条目 */
    val RadiusMd = 12.dp
    /** 输入框圆角（比卡片更润一点） */
    val RadiusField = 16.dp
    /** 大圆角：底部面板 / 下拉菜单 */
    val RadiusLg = 18.dp
    /** 全圆角胶囊：chip / 搜索框 / 主按钮（绘制时会钳到高度一半） */
    val RadiusPill = 999.dp

    // ---------- 间距 ----------
    val GapXs = 4.dp
    val GapSm = 8.dp
    val GapMd = 12.dp
    val GapLg = 16.dp
    val GapXl = 24.dp
    /** 右侧操作栏条目间距（终稿裁决：20→26，大间距小图标 = 呼吸感） */
    val RailGap = 26.dp

    // ---------- 图标 ----------
    /** 操作栏图标（终稿裁决：34→30，降整列视觉重量） */
    val RailIconSize = 30.dp
    /** 操作栏轨内文本字号（预留：动词标签已删，倍速计数文本用 13sp，不在此列） */
    val RailLabelSize = 10.sp
    /** 面板动作格图标底托直径 */
    val SheetActionBadge = 48.dp

    // ---------- 分屏（GRID-REDESIGN §8 增补） ----------
    /** 格子圆角：替换直角无缝拼贴（"监控墙感"的最大来源）；焦点环/幽灵/预览框共用 */
    val CellRadius = 10.dp
    /** 格间黑缝：替换 1px 发丝缝，配合圆角形成"卡片悬浮在黑场上"的层次 */
    val GridGap = 2.dp
    /** 焦点环描边宽度 */
    val FocusRingWidth = 2.dp
    /** 音量胶囊高（收起态为 3dp 细音量线） */
    val VolumePillHeight = 28.dp

    // ---------- 浮层容器（全 App 统一三档底） ----------
    /** 控件胶囊底：× / 音量胶囊 / 落区 / 角柄 */
    val OverlayBg = Color.Black.copy(alpha = 0.55f)
    /** 工具岛 / 片单胶囊 / 恢复钮 */
    val OverlayBgStrong = SurfaceHigh.copy(alpha = 0.85f)
    /** 0.5dp 发丝描边（亮画面上勾出浮层轮廓） */
    val OverlayHairline = Color.White.copy(alpha = 0.10f)

    // ---------- 图标尺寸五档（现状 17 种混用，收编） ----------
    val IconXs = 12.dp   // chip 内 / 角标
    val IconSm = 14.dp   // 胶囊前导图标、× glyph
    val IconMd = 18.dp   // 菜单项 / 对话框行内
    val IconLg = 22.dp   // 工具岛槽位 / 标准动作
    val IconXl = 26.dp   // 中央反馈 glyph（含底托时）

    // ---------- 动效节律（全 App 不许出现第二种节奏） ----------
    val AnimFast = 150   // 状态切换
    val AnimMed = 220    // chrome 进出
    val AnimSlow = 320   // 布局重排
    /** 一切可点物的最小热区 */
    val TouchMin = 40.dp

    // ---------- 置灰态（如实禁用的统一视觉口径） ----------
    /** 禁用行/禁用格的内容透明度（R201 从 ActionSheet.SheetAction 私有字面量收编成
     *  token：SheetAction 置灰格与 AppListItem 禁用行两处共用，不再各写一份 0.38） */
    val DisabledContentAlpha = 0.38f
}

/**
 * R173 动效规范总表（design-proposal.md §5 的落地件，与 [TgFeedTokens] 同居于此）。
 *
 * 分工：时长三档 AnimFast/Med/Slow（[TgFeedTokens]）仍是全 App 时长主干；
 * 本表只补 **spring 三枚 + sheet 场景参数 + 强调曲线**——「chrome 用 spring、
 * 内容用 tween、反馈用映射」的物理分工（§5.3 纪律一）在此钉死。
 *
 * 纪律（§5.3 纪律三）：**转场** spring 只许三枚（Sheet/Page/ZoomReset），任何新转场必须
 * 从表里取值，不许自造；反馈应答弹簧只许 LikeBounce 一枚（输入应答域，见该常数注释）；
 * 退场永远 ≤ 进场；stagger 只发生在进场。
 * 机检（R178）：MotionTokensPolicyTest 扫全仓源码——spring 调用必须引用本表、tween
 * 时长不许裸数字字面量（豁免清单见测试）；动效常数唯一真源 = 本表 + TgFeedTokens 三档。
 * 锚定纪律（R173 用户裁决沉淀）：**面板按触发点就近锚定**——触发点在顶栏（☰ 汉堡）
 * → 左抽屉（SheetAnchor.LEFT）；触发点在屏幕下方（长按面板/动作格）→ 底部 sheet
 * （SheetAnchor.BOTTOM）。新弹层照此选形态，不许触发点与来向空间脱节。
 */
object MotionTokens {
    // ---------- spring 三枚（全 App 仅此三枚，见 design-proposal §5.2 表） ----------

    /**
     * 底部 sheet 滑入/滑出：damping 0.88 是「有生命感但不过冲」的临界
     * （理论过冲 <0.4%，≈700dp 面板约 2dp，视觉不可见）；420f 对应「中速面板」
     * ——观感时长 ≈350ms（M3 medium3 档）。chrome 用 spring、不许弹跳。
     */
    const val SheetSpringDamping = 0.88f
    const val SheetSpringStiffness = 420f

    /**
     * 顶层页切换落定（临界阻尼 ≈300ms，M3 medium2）：第三刀横滑 pager 化时消费。
     * **R232 档案化**：页落定改 tween(AnimMed/Emphasized) slide+fade（用户实锤
     * 「横滑切页硬切」），本枚退出消费、保留在表作 §5.2 行历史与回退锚——
     * MotionTokensPolicyTest 的「关键常数仍在」防跑空断言要求其存在（删它须连测改）。
     */
    const val PageSpringDamping = 1f
    const val PageSpringStiffness = 380f

    /**
     * 顶层页相邻页待命（R177 消费，design-proposal §5.2 表「顶层页相邻页待命」行）：
     * 拖拽中相邻页以 0.92 缩放 + α0.5 待命——与 feed 翻页的 0.94/α0.55（FeedScreen 页
     * 过渡族）同族但再退半档，让「翻一页」比「翻一条」的位移感更重。跟手连续（无时长）。
     */
    const val PageStandbyScale = 0.92f

    /** 相邻页待命透明度（§5.2 表行；1f − 0.5×t 线性跟手） */
    const val PageStandbyAlpha = 0.5f

    /** 缩放双击复位（轻微回弹 ≈260ms，内容层允许弹性）：第二刀 pinch 清屏时消费，本刀先入表 */
    const val ZoomResetSpringDamping = 0.82f
    const val ZoomResetSpringStiffness = 500f

    // ---------- 反馈应答弹簧（R178 入表；§5.3 纪律一「反馈」类的离散事件应答，非转场） ----------

    /**
     * 点赞/收藏类点按应答弹跳（1.35→1.0 / 1.18→1.0 回落）：右轨 RailIcon 点按弹跳 +
     * 双击点赞 likeBounce 两处消费方共用。三枚转场 spring 纪律按提案原文限定在转场域
     * （「任何新**转场**必须从表里取值」）；反馈应答是输入物理——一次一跳的过冲就是
     * 消息本身，刻意保留弹性，不入三枚。但同一常数不许两处各写一份（AGENT-DISCIPLINE
     * §一 Q3）——此前 ActionRail 与 VideoPage 各写一份 0.5/1500，R178 收敛至此。
     */
    const val LikeBounceDamping = 0.5f
    const val LikeBounceStiffness = 1500f

    // ---------- sheet 场景参数（design-proposal §4.3） ----------

    /** sheet scrim 进场：tween 180ms（LinearOutSlowIn 观感——先快后慢） */
    const val SheetScrimInMs = 180

    /** sheet scrim 退场：tween 150ms（FastOutLinearIn 观感——先慢后快，退场要利落） */
    const val SheetScrimOutMs = 150

    /** sheet 段落进场 stagger 步进（三段 t = 0 / +40 / +80ms；段内不逐 chip stagger） */
    const val SheetStaggerStepMs = 40

    /** 数字 countUp（体检得分等数字滚动，§5.2 表「数字 countUp 400ms」行；R178 收编自 ChannelHealth 本地常数） */
    const val CountUpMs = 400

    /** sheet 内容高度上限（占屏高比）：内容瘦身后面板回到「面板」体量（§4.2 ~65vh） */
    const val SheetMaxViewportFraction = 0.65f

    /**
     * M3 emphasized 曲线（cubic-bezier(0.2,0,0,1)）：底栏显隐这类 chrome 位移的
     * tween 用它（时长取 [TgFeedTokens.AnimMed]=220 现有档，§5.2 表钉死）。
     */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * R189 轨道内容转场位移（媒体 tab 切换/级联换列时新内容的方向性轻位移，
     * 与内容域 tween 淡入同拍——design-proposal §5.3「内容用 tween」纪律的落地参数；
     * 位移量 = 一个"tab 步"的约六分之一，方向由轨道序决定，见 ContentDomainTransition）。
     */
    // R194⑤ 可见性加档：16→24dp（用户实测 220ms/16dp「感知不到」——320ms/24dp
    // 与时长同拍加档，转场在切换窗负载下仍可感）
    val TrackTransitionSlide = 24.dp

    // ---------- R301 双形态运镜场景时长（design-dual-form §2 舷窗 + §4 错觉拼接 A 案；
    // 运镜接缝/白闪属转场域——§5.3 纪律三「不许第二种节奏」：时长只许从表里取） ----------

    /** feed→星图出场运镜全程（GalaxyWarpOverlay：封面缩光点+沉入深空+白闪藏接缝） */
    const val GalaxyWarpMs = 620

    /** 出场运镜白闪峰值帧（[GalaxyWarpMs] 时间轴 ~87% 处——闪将满时触发真实跳图） */
    const val GalaxyWarpFlashPeakMs = 540

    /** 星图侧进场白闪收束（TasteMapScreen：对侧峰值帧切页后，本侧从白里落成星图） */
    const val GalaxyEntryFlashMs = 250

    /** feed 侧到达白闪收束（FeedScreen：星图跳回 feed 首帧从白收束，一次性播放） */
    const val GalaxyArriveFlashMs = 300

    /**
     * 舷窗所在恒星双环脉动呼吸周期（GalaxyPortholeSheet——连续呼吸周期，AudioCard
     * 封面呼吸同族；立 token 统一「星图族活物」节律，防各件自造脉动周期）。
     */
    const val PortholePulseCycleMs = 1600

    /** R302 星辉升级演出全程（TasteMapScreen：全屏光雨+跃迁波纹环+横幅一次性） */
    const val GalaxyLevelUpMs = 2800
}

/**
 * 字号层级（对照设计稿）：
 * displaySmall 品牌大字 / headlineSmall 页面标题 / titleLarge 频道名·顶栏大标题 /
 * titleMedium 小节标题 / body* 正文与辅助 / label* chip·图标下小标签。
 * body/label 尺寸保持 Material3 默认值，避免影响不能改的旧布局。
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

/**
 * 深色沉浸风主题：纯黑底（视频沉浸）、电报蓝主色、抖音红强调。
 * 全 App 共用，登录页也吃这一套配色。
 */
private val Scheme = darkColorScheme(
    primary = TgFeedTokens.TgBlue,
    onPrimary = Color.White,
    secondary = TgFeedTokens.AccentRed,
    onSecondary = Color.White,
    background = TgFeedTokens.InkBlack,
    onBackground = TgFeedTokens.TextHigh,
    surface = TgFeedTokens.Surface,
    onSurface = TgFeedTokens.TextHigh,
    surfaceVariant = TgFeedTokens.SurfaceHigh,
    onSurfaceVariant = TgFeedTokens.TextMedium,
    outline = Color(0xFF3A3A3C),
    error = TgFeedTokens.WarningRed,
    onError = Color.White,
)

@Composable
fun TgFeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography) {
        // R179 不透明底（用户实锤：发现/设置页透出启动 logo）：页根有裸 Box（非 Surface）
        // 不铺底色时，主题 windowBackground（splash_brand=黑底居中 logo）会从内容列外的
        // 透明区漏出来。主题层一次性铺 InkBlack，所有页面（含未来新增）永绝漏底——
        // 类修法，优于逐页补背景。R52 启动等待页自绘同款 logo（MainActivity 内），不受影响。
        Box(Modifier.fillMaxSize().background(TgFeedTokens.InkBlack)) {
            content()
        }
    }
}
