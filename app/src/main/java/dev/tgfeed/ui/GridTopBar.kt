package dev.tgfeed.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.tgfeed.core.feed.DownloadStatus
import dev.tgfeed.core.feed.FeedSource
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.Playlist
import dev.tgfeed.core.feed.SearchFeedSource
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.store.GridCanvasSlot
import dev.tgfeed.core.store.GridCanvasSnapshot
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.safeInitialPlayUri
import dev.tgfeed.ui.components.HeartBurst
import dev.tgfeed.ui.components.LikeHeartsLayer
import dev.tgfeed.ui.components.fatalReasonOf
import dev.tgfeed.ui.components.kit.AppListItem
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppSheet
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 分屏顶栏与工具岛（R129 从 GridScreen.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：GridTopBar（搜索胶囊 / 居中胶囊工具岛 / 更多溢出菜单 / 片单胶囊的排布）
 * 与其岛内件 ToolIslandSlot / ToolIslandDivider / GridPlaylistCapsule。
 * 槽位集合、尺寸档位（uiScale）、激活态动效与互斥逻辑一行不动。
 */

/**
 * 分屏顶栏（从 GridScreen 主函数抽出：主组合函数过大曾触发 Android 12 的 DEX VerifyError）。
 *
 * P0-2 整改（走查 G-B/G-C）：一座**居中**胶囊工具岛承载全部动作——
 * 岛容器 = OverlayBgStrong 底 + RadiusPill 全圆角 + OverlayHairline 0.5dp 发丝描边；
 * 槽位 [搜索 / 从片单添加 / 擂台 / 列数 / 换一批 / 更多 / 关闭]，44dp 触控位 + IconLg 图标，
 * 尺寸整体乘 uiScale 跟随窗口档位；关闭钮收进岛内末槽，不再单独漂浮（修 G-C 视觉割裂）。
 * 低频状态开关（自动轮播 / 全停全播 / 专注 / 全局静音）收进「更多」溢出菜单——
 * 功能一项不丢（状态与各格子逻辑零改动），岛上只留高频槽位（G-B：7 个无标签图标语义不明）。
 * 搜索展开 = 岛位形变为同语言输入胶囊；片单模式搜索槽位从岛上移除（语义互斥），
 * 片单胶囊居左、工具岛居右防窄屏互叠（Feed 模式无片单胶囊，岛居中）。
 */
@Composable
internal fun GridTopBar(
    isCustomMode: Boolean,
    customCount: Int,
    searchExpanded: Boolean,
    searchText: String,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    allPaused: Boolean,
    anyPlaying: Boolean,
    onToggleAllPlayback: () -> Unit,
    autoRotate: Boolean,
    onToggleAutoRotate: () -> Unit,
    focusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    // PK 擂台模式：左右两格对打 + 格底投票钮
    pkMode: Boolean,
    onTogglePkMode: () -> Unit,
    muteAll: Boolean,
    onToggleMuteAll: () -> Unit,
    onExitGrid: () -> Unit,
    onPickLocal: () -> Unit,
    onClearCustom: () -> Unit,
    // P0-2 新增：从命名片单整单添加到画布（弹片单选择器，与空画布引导态主按钮同一入口）
    onAddFromPlaylist: () -> Unit,
    // P0-2 新增：列数（密度）档位循环 4→3→2 列等效缩放
    onCycleColumns: () -> Unit,
    // 换一批：可见格一起走换片链换新内容（与画布空白双击同一动作；
    // 此前只有双击手势没有可发现入口——"分屏没有刷新能力"修复）
    onRefreshSlots: () -> Unit,
) {
    // 工具岛尺寸随窗口档位缩放（手机竖屏基准 1.0，平板 1.15/1.1，横屏 0.92）
    val uiScale = rememberWindowSize().uiScale
    // 「更多」溢出菜单显隐（自动轮播/全停全播/专注/全局静音四个低频开关的收纳处）
    var showMoreMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchExpanded && !isCustomMode) {
            // 搜索展开：岛位形变为输入胶囊（同 44dp 全圆角容器语言），右侧"取消"文字钮
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp * uiScale)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBgStrong)
                    .border(
                        0.5.dp,
                        TgFeedTokens.OverlayHairline,
                        RoundedCornerShape(TgFeedTokens.RadiusPill),
                    ),
            ) {
                TextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier.fillMaxSize(),
                    singleLine = true,
                    placeholder = {
                        Text("搜索视频关键词", style = MaterialTheme.typography.bodySmall)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSubmitSearch()
                        onSearchExpandedChange(false)
                    }),
                )
            }
            Text(
                "取消",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .clickable {
                        onSearchTextChange("")
                        onSearchExpandedChange(false)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            // 片单胶囊居左 + 工具岛居中（片单模式岛居右防窄屏互叠）：Box 叠放布局
            Box(Modifier.fillMaxWidth()) {
            if (isCustomMode) {
                // 片单胶囊：替换裸 TextButton 片单头（标题 / 添加 / 清空收进一颗胶囊）
                Box(Modifier.align(Alignment.CenterStart)) {
                    GridPlaylistCapsule(
                        count = customCount,
                        onPickLocal = onPickLocal,
                        onClearCustom = onClearCustom,
                    )
                }
            }
            // 工具岛：居中胶囊容器（OverlayBgStrong 底 + RadiusPill + OverlayHairline 描边）；
            // 片单模式移除搜索槽位，且岛居右避让片单胶囊
            Row(
                Modifier
                    .align(if (isCustomMode) Alignment.CenterEnd else Alignment.Center)
                    .height(44.dp * uiScale)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBgStrong)
                    .border(
                        0.5.dp,
                        TgFeedTokens.OverlayHairline,
                        RoundedCornerShape(TgFeedTokens.RadiusPill),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isCustomMode) {
                    ToolIslandSlot(
                        icon = Icons.Filled.Search,
                        contentDesc = "搜索",
                        active = false,
                        onClick = { onSearchExpandedChange(true) },
                    )
                    ToolIslandDivider()
                }
                // 从片单添加：命名片单整单倒入分屏片单（与空画布引导态主按钮同一入口）
                ToolIslandSlot(
                    icon = Icons.Filled.Add,
                    contentDesc = "从片单添加",
                    active = false,
                    onClick = onAddFromPlaylist,
                )
                ToolIslandDivider()
                // PK 擂台：画布收成左右两个大格对打 + 格底"哪边赢"投票钮（奖杯图标）
                ToolIslandSlot(
                    icon = Icons.Filled.EmojiEvents,
                    contentDesc = if (pkMode) "退出擂台模式" else "擂台模式（两格对打投票）",
                    active = pkMode,
                    onClick = onTogglePkMode,
                )
                ToolIslandDivider()
                // 列数（密度）：循环 4→3→2 列等效缩放（连续捏合缩放不动，这是档位快捷入口）
                ToolIslandSlot(
                    icon = Icons.Filled.GridView,
                    contentDesc = "切换格子列数（密度）",
                    active = false,
                    onClick = onCycleColumns,
                )
                ToolIslandDivider()
                // 换一批：可见格一起走换片链（空白双击手势的岛上可发现入口）
                ToolIslandSlot(
                    icon = Icons.Filled.Refresh,
                    contentDesc = "换一批（可见格换新内容）",
                    active = false,
                    onClick = onRefreshSlots,
                )
                ToolIslandDivider()
                // 更多：低频状态开关收纳（自动轮播 / 全停全播 / 专注 / 全局静音），
                // 功能全部保留、只是不再各占一个岛上槽位；有任一开启时槽位亮激活态提示
                Box {
                    ToolIslandSlot(
                        icon = Icons.Filled.MoreVert,
                        contentDesc = "更多开关（轮播 / 全停全播 / 专注 / 静音）",
                        active = autoRotate || allPaused || focusMode || muteAll,
                        onClick = { showMoreMenu = true },
                    )
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (autoRotate) "自动轮播 · 开" else "自动轮播 · 关") },
                            onClick = {
                                onToggleAutoRotate()
                                showMoreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (allPaused || !anyPlaying) "全部播放" else "全部暂停") },
                            onClick = {
                                onToggleAllPlayback()
                                showMoreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (focusMode) "专注模式 · 开" else "专注模式 · 关") },
                            onClick = {
                                onToggleFocusMode()
                                showMoreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (muteAll) "全局静音 · 开" else "全局静音 · 关") },
                            onClick = {
                                onToggleMuteAll()
                                showMoreMenu = false
                            },
                        )
                    }
                }
                ToolIslandDivider()
                // 关闭：收进岛内末槽（原岛外独立漂浮大 X 拆除，修 G-C 两容器割裂）
                ToolIslandSlot(
                    icon = Icons.Filled.Close,
                    contentDesc = "退出多窗格",
                    active = false,
                    onClick = onExitGrid,
                )
            }
            }
        }
    }
}

/**
 * 工具岛槽位（P0-2 定稿）：44dp 触控位 × [TgFeedTokens.IconLg] 22dp 图标，
 * 整体乘 uiScale 跟随窗口档位；
 * 激活态 = 8dp 圆角白 α0.12 衬底淡入 + 图标 tint 动画到 AccentRed（150ms），
 * 图标切换走 150ms Crossfade——取代旧版瞬间换图标 + 直接染红。
 */
@Composable
private fun ToolIslandSlot(
    icon: ImageVector,
    contentDesc: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val uiScale = rememberWindowSize().uiScale
    val tint by animateColorAsState(
        targetValue = if (active) TgFeedTokens.AccentRed else TgFeedTokens.TextHigh,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "tint",
    )
    val underlay by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "underlay",
    )
    Box(
        Modifier
            .size(44.dp * uiScale) // 44dp 触控位 × 窗口档位
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (underlay > 0f) {
            Box(
                Modifier
                    .size(30.dp * uiScale)
                    .graphicsLayer { alpha = underlay }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            )
        }
        Crossfade(targetState = icon, animationSpec = tween(TgFeedTokens.AnimFast), label = "icon") { ic ->
            Icon(
                ic,
                contentDescription = contentDesc,
                tint = tint,
                modifier = Modifier.size(TgFeedTokens.IconLg * uiScale),
            )
        }
    }
}

/** 工具岛槽位间 1px 发丝分隔（白 α0.06），高度随 uiScale */
@Composable
private fun ToolIslandDivider() {
    val uiScale = rememberWindowSize().uiScale
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp * uiScale)
            .background(Color.White.copy(alpha = 0.06f)),
    )
}

/**
 * 片单胶囊（§5.3）：替换裸压在画面上的 TextButton 片单头——
 * "片单 · N" 13sp + 低权重清空小钮 + 22dp AccentRed 圆 ＋（添加本地视频：主操作才配用红）。
 * 设计稿把"清空"收进片单管理 bottom sheet（sheet 尚未存在），暂以低权重图标保留入口；
 * 叠放封面小图待片单管理一并做（P0 之外）。
 */
@Composable
private fun GridPlaylistCapsule(
    count: Int,
    onPickLocal: () -> Unit,
    onClearCustom: () -> Unit,
) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBgStrong)
            .border(
                0.5.dp,
                TgFeedTokens.OverlayHairline,
                RoundedCornerShape(TgFeedTokens.RadiusPill),
            )
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "片单 · $count",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            maxLines = 1,
        )
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClearCustom),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = "清空片单",
                tint = TgFeedTokens.TextLow,
                modifier = Modifier.size(TgFeedTokens.IconSm),
            )
        }
        Spacer(Modifier.size(4.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.AccentRed)
                .clickable(onClick = onPickLocal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "添加本地视频",
                tint = Color.White,
                modifier = Modifier.size(TgFeedTokens.IconSm),
            )
        }
    }
}
