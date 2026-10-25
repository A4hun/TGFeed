package dev.tgfeed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.tgfeed.ui.frameMirrorScaleX
import dev.tgfeed.ui.frameMirrorScaleY
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.roundToInt

/**
 * 视频画面层（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：播放器渲染面（TextureView 版 PlayerView 装帧/旋转/捏合层变换）、
 * R93 首帧前的封面压顶、player 未就绪时的封面占位 + 加载文案列。
 * playerViewRef 引用回写经 [onPlayerViewReady] 回调交还调用方（同一 MutableState）。
 * [BoxScope] 扩展接收者：占位加载列的 Modifier.align 依赖调用方 Box 作用域（纯搬运保持原解析）。
 */

/**
 * 1) 视频画面；playUri 未就绪时封面/黑底占位。
 *
 * R188 底部空带收口（graphicsLayer 平移贴底，最小手术面）：
 * [videoBottomShiftPx] / [coverBottomShiftPx] = FIT 内容下锚平移量
 * （dev.tgfeed.ui.FitBottomAnchor 纯函数算出，调用方传值）——横屏满高列里
 * 宽内容上下对称的 letterbox 改为整条挪顶部，内容下缘贴容器底（=屏底）、
 * 延伸进 dock 后面（媒体延伸、交互 chrome 不动）。视频层与封面层**各按自身
 * 比例**下锚（视频 = 实测比/元数据/封面代理链；封面 = 位图自身比）：元数据
 * 错向片源（元数据 16:9 实播竖片）下，封面不会被按错误锚比平移出底——每层
 * 贴自己的底，首帧交接时底缘不跳（R159b 一拍成型的同款 parity 思路）。
 * 比未知时纯函数回 0 = 居中现状兜底；竖容器恒 0（居中惯例红线）。
 */
@Composable
internal fun BoxScope.VideoSurfaceLayer(
    player: ExoPlayer?,
    cover: ImageBitmap?,
    useZoom: Boolean,
    fillToScreen: Boolean,
    rotationDeg: Int,
    zoomScale: Float,
    zoomOffset: Offset,
    firstFrameSeen: Boolean,
    loadFailed: Boolean,
    dlProgress: Float,
    sizeBytes: Long,
    uiScale: Float,
    rotatedOdd: Boolean,
    videoBottomShiftPx: Float,
    coverBottomShiftPx: Float,
    // R218 镜像翻转（R227 并入复合态）：true=镜像开（屏幕系左右翻转——播放层
    // 镜像符号跟旋转态换轴，见下方实现注释）。调用方从 FrameAdjustStore 键级
    // 缓存读（每条视频独立的旋转+镜像复合记忆，跨会话保持）
    mirrored: Boolean = false,
    onPlayerViewReady: (PlayerView?) -> Unit,
) {
    // R218 镜像/R227 方向修复：镜像在**屏幕坐标系**恒=左右翻转。播放层 graphicsLayer
    // 点变换序 = T·R·S（scale 先在局部系生效、再被 rotationZ 旋到屏幕系）——
    // rotatedOdd（90°/270°）时局部 X 轴已被旋到屏幕竖向，局部 scaleX=-1 落到屏幕
    // 是**上下翻**（R218 方向 bug 根因，用户实锤）。修法=镜像符号跟旋转态换轴
    //（偶数档挂 scaleX、奇数档换到 scaleY——局部 Y 被旋到屏幕横轴，等效屏幕左右
    // 翻），裁决在 FrameAdjustPolicy 纯函数（唯一来源）。封面两层（R93 压顶+占位
    // 主图）的 graphicsLayer **不含 rotationZ**（封面从不跟旋转——R174 时代既有
    // 口径）→ 局部 X 恒=屏幕横轴，恒用 scaleX 承载镜像符号（无需换轴）。
    // 只进各媒体层的层变换（渲染层），不碰布局/手势/进度条——「只翻画面不翻
    // 交互层」。模糊底衬层（1.25f Crop+blur，装饰层）翻转不可辨识，刻意不动。
    val coverMirrorSign = if (mirrored) -1f else 1f
    if (player != null) {
        AndroidView(
            factory = { ctx ->
                // 换 TextureView（player_cell.xml，分屏格子同款）：
                // 默认 PlayerView 是 SurfaceView 自带不透明黑底，FIT 留边时会把
                // 下面的模糊底衬整个盖住（黑边就是这么来的）；TextureView 是普通视图，
                // 背景透明时留边区域透出下面的底衬，且 pinch 缩放/graphicsLayer 都正常
                (android.view.LayoutInflater.from(ctx)
                    .inflate(dev.tgfeed.R.layout.player_cell, null) as PlayerView).apply {
                    useController = false
                    resizeMode = if (fillToScreen) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // R159b 起播黑边回归修复（用户华为实锤「加载态上下黑边、起播后
                    // 消失」）：Media3 PlayerView 内置 shutter（exo_player_view.xml 的
                    // exo_shutter：match_parent 黑色 View，z 序在 surface 之上）首帧
                    // 渲染前恒在场、盖满整个列——封面/视频的 letterbox 边际（列幅
                    // ≠内容比时，如华为 1024dp 宽列幅 1.47 配 16:9 内容上下各 ~137px）
                    // 直露纯黑；旧列内模糊层（30dp Crop 铺满列）曾把它盖住，R159b
                    // 删模糊层后暴露，起播 shutter 退场时黑带翻底衬=第二处可见变化。
                    // 透明化后加载态边际直接透出外层底衬（模糊封面色），与起播后
                    // letterbox 观感一致——加载/播放两态背景同层，首帧只剩
                    // 「占位→画面」一处变化。shutter 的防闪职责只剩视频区（防
                    // TextureView 旧帧/杂点闪现），该区恒有 R93 封面压顶或视频在场，
                    // 兜底不缺位；TextureView 在 surface_type 下首帧前本身透明。
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }.also { onPlayerViewReady(it) }
            },
            update = {
                it.player = player
                // 填充模式热切换：与上面"填充判定"同一口径——
                // 同向填满 Crop；异向 FIT（黑边由模糊底衬填掉）。
                // 全屏一律 FIT（横屏全屏放竖屏视频 Crop 会裁没上下）。
                it.resizeMode = if (useZoom) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            // 视图销毁（翻页回收）时清掉引用，截图不再摸旧视图
            onRelease = { onPlayerViewReady(null) },
            modifier = Modifier
                .fillMaxSize()
                // 手动旋转 90°/270°：宽高互换布局（本层按"屏幕横过来"的尺寸测量，
                // 内容在横过来的盒子里按 FIT 装帧），居中后层变换旋转回来——
                // 旋转后恰好满宽/满高，天然适配屏幕，不需要任何缩放系数
                // （前两版翻车：放大填满裁内容 / 拿屏幕比算缩放缩成小竖条）。
                // 180° 不换（宽高不变）；0° 不转。R227 抽为 FrameTransform.frameRotationLayout
                // 共享件（分屏格媒体层同款互换布局），字节等价搬运
                .frameRotationLayout(rotationDeg)
                // 双指捏合缩放 + 手动旋转：画面层做层变换（零重组，graphicsLayer
                // 只作用于渲染，手势层/进度条等兄弟浮层不受影响）。
                // R188 下锚平移并入同一层变换（translationY = 捏合平移 + 贴底下移，
                // 两变换天然叠加兼容；draw-phase 改值不触发布局）。
                // R218 镜像翻转再并入：与 R174b 缩放=乘法叠加（zoomScale 恒正，
                // 符号只来自镜像）；与 R188c 平移不同维度互不干扰；翻转不改宽高比
                // （R159 列宽感知零影响）。
                // R227 方向修复：镜像符号换轴（rotatedOdd 时局部 scaleX=屏幕上下翻
                // 的 bug 根因——改挂局部 scaleY 等效屏幕左右翻），裁决走
                // FrameAdjustPolicy 纯函数（与分屏格 FrameTransform 同一来源）
                .graphicsLayer {
                    rotationZ = rotationDeg.toFloat()
                    scaleX = zoomScale * frameMirrorScaleX(rotationDeg, mirrored)
                    scaleY = zoomScale * frameMirrorScaleY(rotationDeg, mirrored)
                    translationX = zoomOffset.x
                    translationY = zoomOffset.y + videoBottomShiftPx
                },
        )
        // R93：首帧未上屏前封面全程压顶（onRenderedFirstFrame 驱动撤离）——
        // player 一 acquire 占位分支就退场，但装填→首帧就绪有数百毫秒窗口，
        // TextureView 黑底会透出（"黑一下再闪回"的根因）；封面保持到首帧真正
        // 画出来，加载→播放平滑过渡。绘制口径与下方占位分支一致，
        // 起播/换源/重试全程无黑屏闪烁
        // R159b 起播一拍：列内模糊底衬只在手动旋转形态保留（PlaybackStartPolicy
        // .keepPlaceholderBackdrop）——常规 FIT 形态页透明，外层全屏底衬（同图
        // 同拍）本就铺在页下透上来，letterbox 前后是同一层、占位退场零背景变化；
        // 旧实现列内再画一份口径不同（列裁剪/30dp/1.25x vs 窗裁剪/28dp/1.3x）的
        // 模糊层，首帧退场时 letterbox 背景跟着换口径 = 用户实测「起播背景跳变」
        // 成分之一。ZOOM 填满形态封面 Crop 铺满整列，模糊层本就不可见（顺带省
        // 一次模糊绘制）
        if (!firstFrameSeen && cover != null) {
            if (dev.tgfeed.ui.PlaybackStartPolicy.keepPlaceholderBackdrop(rotatedOdd)) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.25f
                            scaleY = 1.25f
                        }
                        .blur(30.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // R174：封面压顶期与播放层同享捏合变换（否则首帧前捏合只有
                    // chrome 在退、画面纹丝不动——两条反馈不同拍）；口径与
                    // AndroidView 的 graphicsLayer 完全一致
                    // R188：封面按自身位图比下锚（与播放层各自的 letterbox/2
                    // 平移——首帧交接时底缘不跳）
                    // R218：封面同翻（观感一致——占位/压顶期与起播后翻转态不跳变）；
                    // R227：封面层无 rotationZ（不跟旋转），恒 scaleX 承载镜像=
                    // 屏幕左右翻（不随播放层换轴——见函数头 coverMirrorSign 注释）
                    .graphicsLayer {
                        scaleX = zoomScale * coverMirrorSign
                        scaleY = zoomScale
                        translationX = zoomOffset.x
                        translationY = zoomOffset.y + coverBottomShiftPx
                    },
                contentScale = if (useZoom) ContentScale.Crop else ContentScale.Fit,
            )
        }
    } else {
        // player 未就绪（起播/下载等待）：封面可有可无，加载提示必须有——
        // 修"无封面条目黑屏无进度"：旧实现只在有封面时显示"下载中 x% · y MB"，
        // 无封面只剩一个裸转圈。改为统一走下方的加载文案列，封面只作为底图存在
        if (cover != null) {
            // 模糊底衬（R159b 起播一拍：仅手动旋转形态保留，判据与口径见上方 R93
            // 分支同款注释——PlaybackStartPolicy.keepPlaceholderBackdrop）：
            // Crop 铺满 + 放大 1.25 倍 + 高斯模糊，遮住两侧/上下黑边
            // （Modifier.blur 仅 Android 12+ 生效，低版本自动退化为无模糊，依然可用）
            if (dev.tgfeed.ui.PlaybackStartPolicy.keepPlaceholderBackdrop(rotatedOdd)) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.25f
                            scaleY = 1.25f
                        }
                        .blur(30.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            // 封面主图与播放层同一套尺寸口径（useZoom 同源，R77）：填满模式
            // Crop 与起播后 ZOOM 观感一致，FIT 模式原比例完整可见——
            // 消除"加载中不裁、起播后才裁"的双链跳变
            Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // R174：加载占位期同享捏合变换（口径见上方播放分支同款注释）
                    // R188：封面按自身位图比下锚（同上）
                    // R218：封面同翻（同上——加载态即翻转，起播零跳变）；
                    // R227：封面层无 rotationZ（不跟旋转），恒 scaleX 承载镜像
                    //（同上 coverMirrorSign 口径）
                    .graphicsLayer {
                        scaleX = zoomScale * coverMirrorSign
                        scaleY = zoomScale
                        translationX = zoomOffset.x
                        translationY = zoomOffset.y + coverBottomShiftPx
                    },
                contentScale = if (useZoom) ContentScale.Crop else ContentScale.Fit,
            )
            // 加载文案可读性靠文字自带阴影（OverlayTextShadow），不再压 35% 黑罩——
            // 用户反馈"加载时有一层阴影罩、播放时没了"：去掉这层，封面原色直出
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 注意：失败出口（"加载失败·点按重试"）不在这里——失败时播放器往往已
            // acquire（player!=null，黑的是 PlayerView），本分支根本不参与组合，
            // 旧实现把失败 UI 藏在这里等于永远不可见。统一走下方顶层失败浮层（4.2）；
            // 失败终态下本转圈也让位（loadFailed 时不再"转圈+失败"双提示）
            if (!loadFailed) {
                // 页面级加载统一规格：40dp / 3dp / TextHigh（随 uiScale 缩放）
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp * uiScale),
                    color = TgFeedTokens.TextHigh,
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.height(10.dp))
                // 加载进度可视化（R78 口径统一：起播/缓冲只说"正在加载"，不再混用
                // "下载中/缓冲中"）：真实条目显示百分比（Mock/进度未知保持纯文案）
                val downloading = dlProgress >= 0f && dlProgress < 1f
                Text(
                    if (downloading) "正在加载 ${(dlProgress * 100).roundToInt().coerceIn(0, 100)}%"
                    else "正在加载…",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium.withOverlayShadow(),
                )
                // 已加载大小：总量（item.sizeBytes）已知时才展示
                if (downloading && sizeBytes > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已加载 ${formatSize((sizeBytes * dlProgress).toLong())} / ${formatSize(sizeBytes)}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall.withOverlayShadow(),
                    )
                }
            }
        }
    }
}
