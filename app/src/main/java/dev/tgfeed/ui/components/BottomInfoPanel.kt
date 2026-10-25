package dev.tgfeed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.core.feed.CaptionParser
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * caption 可点片段（顺序即优先级）：http(s) 链接 / t.me 裸链 / @用户名 / #话题（兼容中文话题）。
 * 组：1=带 scheme 链接，2=t.me 裸链，3=@用户名（不含 @），4=#话题（不含 #）。
 */
private val CaptionLinkRegex = Regex(
    "(https?://\\S+)|(t\\.me/\\S+)|@([A-Za-z0-9_]+)|#([\\p{L}\\p{N}_]+)",
)

/** 频道首字头像的柔和底色板：按 chatId 哈希取色，同一频道颜色稳定（照 FeedTopBarOverlay 字母圆模式） */
private val PanelAvatarPalette = listOf(
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
private fun panelAvatarColor(chatId: Long): Color =
    PanelAvatarPalette[Math.floorMod(chatId, PanelAvatarPalette.size)]

/**
 * R234④「你在这里」坐标 tag 判定（**共享单一尺子**——本面板坐标行与 R301 舷窗宿主
 * 同一出处，纪律§一）：itemTags（R228 已解析）首枚优先；未解析（null=网络直取/存量
 * 未回填）回退 CaptionParser 现取首枚（空表=已解析无标签=权威空不回退，R228 口径）。
 * null=无坐标证据（坐标行不渲染/舷窗不开——诚实缺省）。
 */
internal fun galaxyTagOf(item: VideoItem): String? {
    val tags = item.itemTags
    return if (tags != null) tags.firstOrNull() else CaptionParser.extractHashtags(item.caption).firstOrNull()
}

/**
 * 底部信息区（抖音式左下角，图文/视频通用——P0-1 合并了 PhotoInfoPanel 分叉）：
 * 频道行（32dp 圆形头像 + 频道名 17sp SemiBold 可点切频道 + 右侧"追"快捷胶囊）/
 * caption（超 2 行可展开收起，http/t.me 链接、@用户名、#话题 高亮可点）/
 * chips 行：时长（仅非图文）+ 广场热度 + "译/原"（行末，B9c：不再压简介第二行）。
 * 文件大小 chip 已移除（F-D：抖音无此物，大小信息不再占信息区）。
 * caption 展示/门看语义对齐原 PhotoInfoPanel：门看原 caption（item.caption 非空即展示），
 * caption 为空且是视频合集成员（albumId≠0 且非图文）时，显示一行低亮占位说明
 * （"🖼 合集成员 · 未附文字说明"，图文不需要——图集有顶部 Stories 分段条）。
 * 有译文时 caption 显示译文，chips 行末 chip 变"原"，可点切回原文。
 * R176：item.recommended=true（关注流推荐兜底位）时 chips 行首多一枚「为你推荐」chip。
 * @param onSearchTag 点击 caption 里的 #话题 触发（参数不含 # 号），默认空实现
 * @param translatedCaption 译文（null = 未翻译），由外层状态驱动
 * @param onTranslate 点击"译" chip 触发，默认空实现
 * @param avatarLoader 频道真头像加载器（chatId → 图片字节，内部有进程内缓存）；null = 回落首字字母圆
 * @param subscribed 当前频道是否已订阅（决定"追"/"已追"灰态），默认 false
 * @param onSubscribe 点击"追"快捷订阅当前频道，默认空实现
 * @param onOpenLink 点击 caption 里的 http(s)/t.me 链接触发（t.me 裸链已补全 https:// 前缀），默认空实现
 * @param onOpenMention 点击 caption 里的 @用户名触发（参数不含 @），默认空实现
 */
@Composable
internal fun BottomInfoPanel(
    item: VideoItem,
    onOpenChannel: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchTag: (String) -> Unit = {},
    translatedCaption: String? = null,
    onTranslate: () -> Unit = {},
    avatarLoader: (suspend (Long) -> ByteArray?)? = null,
    subscribed: Boolean = false,
    onSubscribe: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onOpenMention: (String) -> Unit = {},
    // TG 广场：全站在看数 / 点赞数（0/0 = 不显示）
    plazaWatching: Int = 0,
    plazaLikes: Int = 0,
    // R238 评论数（>0 显示「💬 N 条评论」角标）：相册帖由调用方按组内 max 聚合传入
    // （评论线程属于整个相册帖——载体成员独占 replyCount，其余成员为 0）。
    // 网络直取/DB 直出早期帧可能为 0（诚实缺省，网络页覆盖后出现）。
    commentCount: Int = 0,
    // R238 评论媒体流入口：点按角标拉线程媒体回复插入当前条目后（见
    // FeedViewModel.openCommentStream）；null = 纯展示不可点（非 Feed 场景等）
    onOpenComments: (() -> Unit)? = null,
    // R269/R301 「在星图中定位」：下方 R234「你在这里」宇宙坐标行可点——**R301 起
    // 点按=开星图舷窗**（feed 内半屏 sheet 不跳页：邻域快照+脉动光环+邻星+时期；
    // 舷窗内首键「进入星图」才走 R233 openTasteMapFly 跳图运镜链）；null = 行纯展示
    // 不可点（现状逐字节——非 Feed 宿主/未接线场景）
    onLocateInGalaxy: (() -> Unit)? = null,
) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    // 已翻译时本地记一个"看原文"开关；换视频（key 变）自动回到译文态
    var showOriginal by remember(item.key) { mutableStateOf(false) }
    // 当前实际展示的文案：有译文且没切原文时用译文
    val showingTranslation = translatedCaption != null && !showOriginal
    // R228 caption 结构化：逐条解析标题（深索引 v8 parsed_title）优先——相册成员显示
    // 自己的独立标题+自身标签（原形，# 前缀还原——经 buildCaptionWithLinks 天然可点跳
    // 话题）；null（存量未回填/回退模式/网络直取路径）回落原 caption 全文（R219 口径，
    // 行为不变）。itemTags 非空时以「#tag」还原拼接（该成员条目级∪相册级标签）。
    val parsedDisplay = item.parsedTitle?.let { t ->
        val tags = item.itemTags.orEmpty()
        if (tags.isEmpty()) t.trim() else t.trim() + " " + tags.joinToString(" ") { "#$it" }
    }
    val displayCaption = if (showingTranslation) translatedCaption!! else (parsedDisplay ?: item.caption)
    // caption 可点片段统一着电报蓝（主题主色）
    val tagColor = MaterialTheme.colorScheme.primary
    val currentOnSearchTag by rememberUpdatedState(onSearchTag)
    val currentOnOpenLink by rememberUpdatedState(onOpenLink)
    val currentOnOpenMention by rememberUpdatedState(onOpenMention)
    val captionText = remember(displayCaption, tagColor) {
        buildCaptionWithLinks(
            displayCaption,
            tagColor,
            onLink = { currentOnOpenLink(it) },
            onMention = { currentOnOpenMention(it) },
            onTag = { currentOnSearchTag(it) },
        )
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
        // 频道行：32dp 圆形头像 + 频道名（点按切到该频道 Feed）+ 右侧"追"快捷胶囊
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(chatId = item.chatId, title = item.channelTitle, avatarLoader = avatarLoader)
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                text = "@${item.channelTitle}",
                color = TgFeedTokens.TextHigh,
                // 终稿裁决：频道名是元信息不是标题，titleLarge(20sp Bold) → 17sp SemiBold
                style = TextStyle(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ).withOverlayShadow(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenChannel),
            )
            SubscribePill(subscribed = subscribed, onSubscribe = onSubscribe)
        }
        // caption 门看原 caption（对齐原 PhotoInfoPanel 语义）：非空即展示，
        // 默认收起 2 行、点击或"展开"按钮展开全量（多行可展开）；
        // R228：已解析条目（parsed_title 非空）改看解析标题——相册逐成员独立标题；
        // "译/原"chip 不在此（B9c：挪到底部 chips 行末尾，不再压简介第二行）
        if (parsedDisplay != null || item.caption.isNotBlank()) {
            // Text + LinkAnnotation：点链接/@/话题走各自回调，点其余区域仍走展开/收起
            Text(
                captionText,
                color = TgFeedTokens.TextHigh.copy(alpha = 0.9f),
                // 终稿裁决：caption 维持 14sp，补 lineHeight 20sp（两行展开更透气）
                style = MaterialTheme.typography.bodyMedium
                    .copy(lineHeight = 20.sp)
                    .withOverlayShadow(),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                // fillMaxWidth：保持与旧版（Row 里 weight(1f)）一致的整行点击热区
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            )
            // 文案明显偏长时才给出展开/收起入口，避免短文案多一个噪音按钮
            if (displayCaption.length > 48) {
                Text(
                    if (expanded) "收起" else "展开",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodySmall.withOverlayShadow(),
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
        // 空 caption 的合集成员不显示任何占位文案（用户口径：没有就不显示，
        // 合集导航由 VideoPage 的 AlbumNavRow 胶囊承担，信息区保持干净）
        // chips 行：文件大小（用户口径：时长进度条已展示，大小更有用）/
        // 广场热度 / 译·原（B9c：行末，与简介分行排列）
        Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            // R176 关注流推荐兜底标记：非订阅轮转供给的条目明示来源（用户口径：
            // 不让用户感觉被骗）——关注 tab 刷到推荐位时可见；其他源恒 false 零噪音
            if (item.recommended) {
                InfoChip("为你推荐")
            }
            // R298 推荐解释（推荐→星图单向解释器，design-recommendation.md §5.1）：
            // 推荐引擎注入条目带 recommendReason（「常看 #某tag」=R2 tag 兴趣路命中；
            // 「同好频道在看」=R3 图谱路命中）——推荐第一次变得可解释。tag 命中的
            // chip 可点，走 caption #标签同一通道（onSearchTag→双选弹层→时间轴/星域
            // 消费）；无命中轴的条目不写此字段（红线：无数据不造假，不编理由）
            item.recommendReason?.let { reason ->
                val tagPrefix = "常看 #"
                if (reason.startsWith(tagPrefix)) {
                    val tag = reason.removePrefix(tagPrefix)
                    CaptionActionChip(text = reason, active = false, onClick = { currentOnSearchTag(tag) })
                } else {
                    InfoChip(reason)
                }
            }
            if (item.sizeBytes > 0) {
                InfoChip(formatSize(item.sizeBytes))
            }
            // R238 评论数角标：评论线程回复数（interactionInfo.replyInfo.replyCount）——
            // 用户实锤"37 条评论没显示"。点按进评论媒体流（拉线程媒体回复插入
            // 当前条目后，下滑即看——级联产出的 💬 条目同语义）；不可点场景纯展示。
            if (commentCount > 0) {
                val chipShape = OverlayInfoChipShape
                Text(
                    "💬 $commentCount 条评论",
                    color = TgFeedTokens.TextHigh.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall, // 11sp（InfoChip 同族）
                    modifier = Modifier
                        .background(OverlayInfoChipBg, chipShape)
                        .border(0.5.dp, OverlayInfoChipHairline, chipShape)
                        .then(
                            if (onOpenComments != null) {
                                Modifier.clickable(onClick = onOpenComments)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // 广场热度：全站在看/点赞数（开启广场且有数据才显示）
            if (plazaWatching > 0 || plazaLikes > 0) {
                InfoChip(
                    buildString {
                        if (plazaWatching > 0) append("👥 $plazaWatching 在看")
                        if (plazaWatching > 0 && plazaLikes > 0) append(" · ")
                        if (plazaLikes > 0) append("❤️ $plazaLikes")
                    },
                )
            }
            // 翻译 chip：未翻译="译"（触发翻译）；已翻译="原"/"译"切换原文与译文。
            // 无 caption 时没有可翻译内容，不显示
            if (item.caption.isNotBlank()) {
                CaptionActionChip(
                    text = if (showingTranslation) "原" else "译",
                    // 译文态下"原"高亮提示正在看译文
                    active = showingTranslation,
                    onClick = {
                        if (translatedCaption != null) showOriginal = !showOriginal else onTranslate()
                    },
                )
            }
        }
        // R234④ 「你在这里」宇宙坐标（口味星图三期）：内容自带星系位置感——
        // 星域=本条首个结构化 tag、恒星=频道（每个已索引/看过的频道都是星图上的星，
        // 映射为隐含语义）；无 tag 不渲染（诚实缺省——没有坐标证据不冒充）。
        // tag 判定=共享件 galaxyTagOf（R301 收口——舷窗宿主同尺；两级口径见其 KDoc）。
        val galaxyTag = remember(item.key, item.itemTags, item.caption) {
            galaxyTagOf(item)
        }
        if (galaxyTag != null) {
            // R269：坐标行=「在星图中定位」入口；**R301 语义升级**：点按=开舷窗
            //（feed 内半屏 sheet 不跳页——「看见」而非「跳走」，design-dual-form §2；
            // 跳图链降级为舷窗内首键「进入星图」）。onLocateInGalaxy=null 时行纯
            // 展示（R234 原貌逐字节）；行内文字/样式零改动
            Text(
                "✦ 星域: #$galaxyTag · 恒星: ${item.channelTitle.ifBlank { "未收录" }}",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onLocateInGalaxy != null) {
                    Modifier.clickable(onClick = onLocateInGalaxy)
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * 频道头像（32dp 圆形）：有真头像显示圆形裁剪图，没有回落首字字母圆（底色按 chatId 稳定取色）。
 * loader 内部带进程内缓存，重组/翻页回来只是缓存命中，不会重复触发 TDLib 下载。
 */
@Composable
private fun ChannelAvatar(
    chatId: Long,
    title: String,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
) {
    // R194④：头像解码收编共享件（rememberChannelAvatarImage——IO 解码，
    // 旧写法组合期主线程解码是翻页到货窗的帧预算窃贼，R170 家族）
    val avatarImage = rememberChannelAvatarImage(chatId, avatarLoader)
    if (avatarImage != null) {
        Image(
            bitmap = avatarImage,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        )
    } else {
        // 回落：圆形字母头像（频道名首字大写）
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(panelAvatarColor(chatId)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull()?.uppercase() ?: "?",
                color = TgFeedTokens.TextHigh,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** 频道行右侧的"追"快捷胶囊：未订阅=电报蓝可点"追"；已订阅=灰态"已追"（不可点） */
@Composable
private fun SubscribePill(subscribed: Boolean, onSubscribe: () -> Unit) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Text(
        if (subscribed) "已追" else "追",
        color = if (subscribed) TgFeedTokens.TextLow else TgFeedTokens.TextHigh,
        style = MaterialTheme.typography.labelSmall, // 11sp
        modifier = Modifier
            .padding(start = TgFeedTokens.GapSm)
            .background(
                if (subscribed) OverlayInfoChipBg else TgFeedTokens.TgBlue,
                shape,
            )
            .border(0.5.dp, OverlayInfoChipHairline, shape)
            .clickable(enabled = !subscribed, onClick = onSubscribe)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * 描边信息 chip（时长 / 广场热度）：R162 起样式本体收编 OverlayStyles.OverlayInfoChip
 * （终稿裁决 v2-b：黑 α0.35 底 + 0.5dp 白 α0.18 描边 + 8dp 圆角，比实心深胶囊轻，
 * 暗/亮画面两边都可读——全 App 唯一实现，一处改全局生效）。
 * （不再共用 OverlayChipBg/Shape：进度气泡仍是全圆角实心胶囊，各走各的）
 */
@Composable
private fun InfoChip(text: String) {
    OverlayInfoChip(text)
}

/** chips 行末尾的小动作 chip（译 / 原）：与 InfoChip 同族样式，active 时白底黑字高亮 */
@Composable
private fun CaptionActionChip(text: String, active: Boolean, onClick: () -> Unit) {
    val shape = OverlayInfoChipShape
    Text(
        text,
        color = if (active) Color.Black else TgFeedTokens.TextHigh.copy(alpha = 0.9f),
        style = MaterialTheme.typography.labelSmall, // 11sp
        modifier = Modifier
            .background(if (active) Color.White else OverlayInfoChipBg, shape)
            .border(0.5.dp, OverlayInfoChipHairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 把 caption 里的 http(s)/t.me 链接、@用户名、#话题 渲染成电报蓝可点击片段。
 * 链接上抛完整 URL（t.me 裸链补 https:// 前缀）；@/话题上抛不含前缀的词。
 */
private fun buildCaptionWithLinks(
    caption: String,
    linkColor: Color,
    onLink: (String) -> Unit,
    onMention: (String) -> Unit,
    onTag: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in CaptionLinkRegex.findAll(caption)) {
        val httpUrl = match.groupValues[1]
        val tgUrl = match.groupValues[2]
        val mention = match.groupValues[3]
        val tag = match.groupValues[4]
        // 四类命中共用样式与挂载方式，仅 annotation tag 与点击动作不同
        val linkTag: String
        val action: () -> Unit
        when {
            httpUrl.isNotEmpty() -> {
                linkTag = "url"
                action = { onLink(httpUrl) }
            }

            tgUrl.isNotEmpty() -> {
                linkTag = "url"
                action = { onLink("https://$tgUrl") }
            }

            mention.isNotEmpty() -> {
                linkTag = "mention"
                action = { onMention(mention) }
            }

            else -> {
                linkTag = "hashtag"
                action = { onTag(tag) }
            }
        }
        // 原样拼接，保证 builder 内下标与原串一致
        append(caption.substring(cursor, match.range.first))
        withLink(
            LinkAnnotation.Clickable(
                tag = linkTag,
                linkInteractionListener = { action() },
            ),
        ) {
            append(match.value)
        }
        addStyle(
            SpanStyle(color = linkColor, fontWeight = FontWeight.Medium),
            match.range.first,
            match.range.last + 1,
        )
        cursor = match.range.last + 1
    }
    append(caption.substring(cursor))
}
