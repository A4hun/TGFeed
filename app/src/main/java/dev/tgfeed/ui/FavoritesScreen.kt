package dev.tgfeed.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.OverlayInfoChip
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * R199 我的收藏页（收藏查看模块——修「咔咔一顿收藏，根本没有地方查看回味」）。
 *
 * 纯读侧模块：数据层（toggleFavorite/LocalStore）零改动，只消费共享 VM 的
 * `favoriteItems`（@Volatile，收藏时间倒序）与 `favorites` key 集 StateFlow——
 * 数据层每分支先写 favoriteItems 后写 _favorites（FeedActionsPart 顺序保证），
 * 故 `remember(键集) { favoriteItems.toList() }` 在任何收藏增删后即重读，
 * 取消收藏列表即时减格，零新状态通道。
 *
 * 结构（媒体墙 R171 口径）：
 * - AppTopBar（返回 + 标题「我的收藏」 + 计数）；
 * - 视图切换胶囊：按时间（默认=数据层收藏时间倒序）/ 按频道（分组视图，组序=组内
 *   最新收藏在前，组内保序——纯策略见 FavoritesListPolicy）；
 * - 全出血封面墙：GridCells.Adaptive(MediaWallCellMin)，3:4 格（频道主页墙同范式，
 *   适配点是列数不是容器宽——平板/手机列数随屏涌现）；
 * - 点格子 → onPlay（MainActivity 编排：切收藏夹源回 Feed 定位播放，返回键先回本页
 *   ——R182 L2 pending）；
 * - 取消收藏：格子右上角星形角标（40dp 热区/24dp 视觉，GridScreen 拖柄同口径），
 *   点按即 vm.toggleFavorite 取消 + Toast「已取消收藏」——列表即时减一格。
 * - 空态：诚实引导卡（「看视频时点右轨的星标」）。
 * - R397 批量入分屏（六入口之二·收藏夹）：视图切换胶囊行下「分屏播放 N 条」文字钮
 *   ——整批收藏（时间倒序原样）种进分屏片单并直接开分屏（统一机制 App.addBatchToGrid；
 *   收藏条目 playUri=null 时入格走格内 ensurePlayable 现拉，离线可播口径不变）。
 */
@Composable
fun FavoritesScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    // R397 批量入分屏：宿主（MainActivity）收口——种进容器片单 + 剥落本页 + 开分屏；
    // 钮文案实数=本页 items.size，实增数/重复数由宿主 Toast 如实报账
    onBatchToGrid: (List<VideoItem>) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    // 系统返回键 = 返回上一页（状态驱动路由，不在返回栈里，不拦会退到桌面）
    BackHandler(onBack = onBack)

    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（"gw"+identityHashCode
    // key 约定）：取消收藏走它的公共 API toggleFavorite（单写入方语义），收藏键集
    // 同源——视频页星标与本页取消天然同步
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )

    // 收藏键集（活流）：任何渠道的收藏增删（视频页星标/本页取消）都发射新集
    val favoriteKeys by vm.favorites.collectAsState()
    // 键集一变即重读条目表（收藏时间倒序不变量由数据层维护，读侧不重排）——
    // 数据层"先条目后键集"的写序保证此处读到的条目表必不旧于键集
    val items = remember(favoriteKeys) { vm.favoriteItems.toList() }

    // 视图模式：false=按时间（默认，列表原样=收藏时间倒序）；true=按频道分组
    var groupByChannel by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "我的收藏",
                onBack = onBack,
                actions = {
                    // 计数（次级信息）：非空时顶栏右缘一枚
                    if (items.isNotEmpty()) {
                        Text(
                            "${items.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = TgFeedTokens.TextLow,
                        )
                    }
                },
            )

            // 视图切换胶囊行（空态无分组意义，收起）
            if (items.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    AppPill(
                        text = "按时间",
                        selected = !groupByChannel,
                        onClick = { groupByChannel = false },
                    )
                    AppPill(
                        text = "按频道",
                        selected = groupByChannel,
                        onClick = { groupByChannel = true },
                    )
                }
                // R397 批量入分屏：整批收藏倒进分屏（钮文案实数；分组视图下同批全量——
                // 种子集=数据层条目表，与视图分组无关，所见条目一条不少）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onBatchToGrid(items) }) {
                        Icon(
                            Icons.Outlined.GridView,
                            contentDescription = null,
                            tint = TgFeedTokens.AccentRed,
                        )
                        Spacer(Modifier.width(TgFeedTokens.GapXs))
                        Text(
                            "分屏播放 ${items.size} 条",
                            color = TgFeedTokens.AccentRed,
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                // 空态：诚实引导（收收藏的地方在视频页右轨，不说"暂无数据"这种废话）
                AppEmptyState(
                    icon = Icons.Outlined.Star,
                    title = "还没有收藏",
                    subtitle = "看视频时点右轨的星标，喜欢的都会存到这里",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                // 分组视图的平铺行（时间视图=全条目行）；时间视图不需要纯函数重排
                val rows = remember(items, groupByChannel) {
                    if (groupByChannel) groupFavoritesByChannel(items)
                    else items.map { FavoritesListRow.Entry(it) }
                }
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    // R171 口径：列数随实测宽涌现（Adaptive + MediaWallCellMin 锚），
                    // 手机竖屏 3 列、平板竖 5/横 9 列——与频道主页作品墙同款
                    columns = GridCells.Adaptive(MediaWallCellMin),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        top = TgFeedTokens.GapXs,
                        bottom = TgFeedTokens.GapLg,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GridGap),
                    verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GridGap),
                ) {
                    for (row in rows) {
                        when (row) {
                            is FavoritesListRow.Header -> item(
                                key = "fav_header_${row.chatId}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                FavoritesGroupHeader(row)
                            }
                            is FavoritesListRow.Entry -> item(key = row.item.key) {
                                FavoriteWallCell(
                                    item = row.item,
                                    gateway = gateway,
                                    onPlay = { onPlay(row.item) },
                                    onRemove = {
                                        // 取消收藏：复用 toggleFavorite 公共 API（本条在
                                        // 收藏列表里，翻转=取消）——即时反馈（键集发射→
                                        // 列表重读→格子即刻消失）+ Toast 兜底确认
                                        vm.toggleFavorite(row.item)
                                        Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 以下为本页私有组件 ====================

/** 频道分组视图的组头：频道名（SemiBold）+ 组内条数（次级灰） */
@Composable
private fun FavoritesGroupHeader(header: FavoritesListRow.Header) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = TgFeedTokens.GapLg,
                end = TgFeedTokens.GapLg,
                top = TgFeedTokens.GapLg,
                bottom = TgFeedTokens.GapXs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
    ) {
        Text(
            header.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TgFeedTokens.TextHigh,
            maxLines = 1,
        )
        Text(
            "${header.count} 条",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
        )
    }
}

/**
 * 收藏墙单格：3:4 封面 + 类型角标（左下）+ 取消收藏星标（右上，40dp 热区/24dp
 * 视觉——GridScreen 格位拖柄同口径）。
 * 封面链（频道主页 MediaGridCell 同族）：CoverStore 抽帧（播过就有）→ coverBytes
 * minithumbnail → 高清按需（视频 loadCoverBytes / 音频 loadAudioCoverBytes）→
 * 消息反解 loadCoverBytesByMessage（老收藏重启后 coverFileId 失效的兜底，历史页
 * 同款）→ 图片走原图采样解码（loadPhotoCoverBitmap，与频道主页墙共用一件）。
 */
@Composable
private fun FavoriteWallCell(
    item: VideoItem,
    gateway: TdGateway,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val cover: ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, item.key) {
        CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            // minithumbnail 占位解码（收藏落盘不带 coverBytes，此分支多为内存命中后的空跳）
            value = item.coverBytes?.let { bytes ->
                withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
                }
            }
        }
        val lib = gateway as? TdLibGateway
        if (value == null && lib != null) {
            // 高清封面（R169：取字节+解码整段下放 IO，几十格成串解码不挡组合线程）
            val hd: ImageBitmap? = withContext(Dispatchers.IO) {
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
            // 消息反解兜底（网关侧内存+磁盘双缓存，同 key 不重复下载）：
            // 收藏条目跨会话恢复，coverFileId 是会话级 id——失效后走 chatId+messageId
            // 反解消息真缩略图（观看历史页同款链路）
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
            // 图片（mediaType=1）高清封面：无 coverFileId，走原图采样解码链（共享件）
            if (value == null && item.mediaType == 1 && item.fileId >= 0) {
                val photoHd = withContext(Dispatchers.IO) {
                    runCatching { loadPhotoCoverBitmap(lib, item) }.getOrNull()
                }
                if (photoHd != null) value = photoHd
            }
        }
    }.value

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
            // 无封面占位：类型字（频道主页墙同款兜底）
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
        // 类型角标（左下，OverlayInfoChip 唯一实现）：视频=时长；图集=图集；音频=♪
        val badge = when (item.mediaType) {
            0 -> formatDuration(item.durationSec)
            1 -> if (item.albumId != 0L) "🖼 图集" else "图"
            else -> "♪ 音频"
        }
        OverlayInfoChip(
            text = badge,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp),
            contentPadding = PaddingValues(
                horizontal = 5.dp,
                vertical = 2.dp,
            ),
        )
        // 取消收藏角标（右上）：热区 40dp（TouchMin）视觉 24dp 圆在内层——GridScreen
        // 焦点格拖柄同口径；控件底 OverlayBg（OverlayStyles P2：控件底唯一 token）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(TgFeedTokens.TouchMin)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.OverlayBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "取消收藏",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
