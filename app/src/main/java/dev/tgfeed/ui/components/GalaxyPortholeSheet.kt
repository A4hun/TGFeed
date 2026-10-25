package dev.tgfeed.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.tastemap.GalaxyPorthole
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.GalaxyPortholeLoader
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.components.kit.SheetStaggeredSection
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * R301 星图舷窗（design-dual-form.md §2 落地）：feed 内半屏 sheet——**不跳页**就能
 * 看见「这条视频在星图哪里」（视频继续播，声音不断——feed 形态不被打断）。
 *
 * 内容（自上而下）：
 *  - **微缩星图**：以该频道恒星为圆心的邻域静态快照（ShareCard 同源绘制件
 *    drawTasteMapDust/drawTasteMapField——海报与 App 同一套笔触；位图磁盘缓存，
 *    内容指纹 key——R254 指纹族，邻域不变秒开）；所在恒星**脉动光环**（Compose
 *    Canvas 双环呼吸，≤2ms/帧——静态快照上唯一的活物）；该视频的 tag 对应行星
 *    高亮标记（真相位——TasteTagPlanets 同源几何，见 GalaxyPortholeLoader
 *    .loadPlanetMarker）。
 *  - **邻星 pills**（合并边权 top-5，横滚）：点=飞过去（宿主走 R233
 *    openTasteMapFly 链原样，落点=那颗邻星）。
 *  - **时期行**：该频道该 tag 的发布密集期（月度分桶峰月判词——ChannelEraPolicy
 *    族同尺）+条数。
 *  - **[🚀 进入星图]**：现有 openTasteMapFly 链原样承接（舷窗是反查的新家，不是
 *    反查的替代——要看全宇宙随时可进）。
 *
 * 诚实缺省矩阵：演示模式/图谱空=邻域独奏（中心星+光环照常）；无 tag=时期行/行星
 * 标记整段不出；装配查询异常=快照黑底星点底幕仍出（栏位文字提示）。全部本地只读，
 * 零 Search 族（R184 红线）。
 *
 * @param item 当前条目（恒星=其频道）
 * @param tag 舷窗行星标记/时期行的标签（galaxyTagOf 同源——宿主传入；null=无坐标
 *   证据，标记与时期行不渲染）
 * @param onEnterGalaxy 「进入星图」/邻星 pill 共用出口（参数=目标 chatId；宿主负责
 *   运镜拼接与真实跳图链）
 */
@Composable
internal fun GalaxyPortholeSheet(
    item: VideoItem,
    tag: String?,
    gateway: TdGateway,
    vm: FeedViewModel,
    onEnterGalaxy: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application

    // ---- 邻域小装配+时期弧+行星标记+快照位图（IO 一次装齐；换条目/换 tag 重装） ----
    val data by produceState<GalaxyPortholeLoader.Neighborhood?>(null, item.chatId) {
        value = withContext(Dispatchers.IO) {
            runCatching { GalaxyPortholeLoader.loadNeighborhood(gateway, item.chatId, item.channelTitle) }
                .getOrNull()
        }
    }
    val era by produceState<GalaxyPortholeLoader.TagEraInfo?>(null, item.chatId, tag) {
        if (tag == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { GalaxyPortholeLoader.loadTagEra(app, item.chatId, tag) }.getOrNull()
        }
    }
    val marker by produceState<GalaxyPortholeLoader.PlanetMarker?>(null, item.chatId, tag) {
        if (tag == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { GalaxyPortholeLoader.loadPlanetMarker(vm, item.chatId, tag) }.getOrNull()
        }
    }
    val snapshot by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, data) {
        val d = data ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                GalaxyPortholeLoader.portholeSnapshot(app, d.layout, tag, d.neighbors)
            }.getOrNull()?.asImageBitmap()
        }
    }
    // 跨页呼应落账（舷窗看过=下次进星图该星脉动欢迎——R282 flashStar 原语消费源）
    androidx.compose.runtime.LaunchedEffect(item.chatId) {
        withContext(Dispatchers.IO) { GalaxyPortholeLoader.markSeen(app, item.chatId) }
    }

    AppSheetMotion(
        onDismiss = onDismiss,
        title = "✦ 这条视频在星图里",
        anchor = SheetAnchor.BOTTOM,
    ) { sheet ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg)
                .padding(bottom = TgFeedTokens.GapMd),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            // ---------------- 微缩星图（静态快照+脉动光环+行星标记） ----------------
            SheetStaggeredSection(delayMs = 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                        .background(Color.Black),
                ) {
                    val snap = snapshot
                    if (snap != null) {
                        Image(
                            bitmap = snap,
                            contentDescription = "邻域星图快照",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 脉动光环+行星标记覆盖层（与快照同幅——ContentScale.Fit 下中心
                    // 即世界中心；快照按 min(w,h) 取景，覆盖层同一换算式）
                    PortholePulseOverlay(marker = marker, tag = tag)
                }
            }
            // ---------------- 邻星 pills（点=飞过去） ----------------
            SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs) {
                val neighbors = data?.neighbors.orEmpty()
                if (neighbors.isEmpty()) {
                    Text(
                        "邻星证据不足（图谱还没收录它的互链）——进星图看全貌",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "邻星（关联弧 ${neighbors.size} 条）",
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextLow,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val titles = data?.layout?.nodes?.associate { it.chatId to it.title }.orEmpty()
                            neighbors.take(GalaxyPorthole.PILLS_SHOW).forEach { (id, w) ->
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                        .background(TgFeedTokens.OverlayBg)
                                        .border(
                                            0.5.dp, TgFeedTokens.OverlayHairline,
                                            RoundedCornerShape(TgFeedTokens.RadiusPill),
                                        )
                                        .clickable { sheet.dismiss { onEnterGalaxy(id) } }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(GalaxyPorthole.NEIGHBOR_COLOR)),
                                    )
                                    Text(
                                        "★${titles[id]?.ifBlank { null } ?: "未收录频道"}（$w）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TgFeedTokens.TextMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (neighbors.size > GalaxyPorthole.PILLS_SHOW) {
                                Text(
                                    "+${neighbors.size - GalaxyPorthole.PILLS_SHOW}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgFeedTokens.TextLow,
                                )
                            }
                        }
                    }
                }
            }
            // ---------------- 时期行（该频道该 tag 的密集发布期） ----------------
            val eraNow = era
            if (tag != null && eraNow != null && eraNow.total > 0) {
                SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs * 2) {
                    Text(
                        buildString {
                            append("时期：该频道 #").append(tag).append(" 共 ").append(eraNow.total).append(" 条")
                            eraNow.peakLabel?.let { append(" · 高峰 ").append(it) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // ---------------- [🚀 进入星图]（舷窗是反查的新家，跳图链降级为卡内首键） ----------------
            SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs * 2) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.TgBlue)
                        .clickable { sheet.dismiss { onEnterGalaxy(item.chatId) } }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🚀 进入星图",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TgFeedTokens.TextHigh,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * 舷窗脉动光环+tag 行星标记覆盖层（快照之上唯一的活物——Compose Canvas 轻动画，
 * ≤2ms/帧，零逐帧分配）：
 *  - 所在恒星**脉动光环**：双环错相呼吸（1.6s 周期——「你在这里」的活体感）；
 *    锚点=视窗中心（布局不变式：中心星恒锚世界中心+快照/覆盖层同一取景换算）。
 *  - tag 行星标记：确定性相位锚点（[GalaxyPorthole.tagMarkerOffset]——与星图内
 *    该行星同一哈希相位）+轨道椭圆（压扁 0.62=TagOrbitCloud 同款轨道面语言）；
 *    高亮色=中心星暖金，随光环同拍呼吸。
 */
@Composable
private fun PortholePulseOverlay(
    marker: GalaxyPortholeLoader.PlanetMarker?,
    tag: String?,
) {
    val pulse by rememberInfiniteTransition(label = "portholePulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(MotionTokens.PortholePulseCycleMs), RepeatMode.Restart),
        label = "p",
    )
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // 快照取景换算（与 GalaxyPortholeLoader.renderSnapshot 同一式：min(w,h)/2
        // × SNAPSHOT_FIT / FRAME_EXTENT——世界单位→覆盖层像素）
        val pxPerWorld = (minOf(size.width, size.height) / 2f * GalaxyPorthole.SNAPSHOT_FIT) /
            GalaxyPorthole.FRAME_EXTENT
        // 中心星屏半径（布局常量 26 世界单位——GalaxyPorthole.layoutNeighborhood 锚定）
        val corePx = 26f * pxPerWorld
        // 双环错相呼吸（第二环错半相——涟漪感）
        for (phase in floatArrayOf(0f, 0.5f)) {
            val t = (pulse + phase) % 1f
            val r = corePx * (1.5f + 1.3f * t)
            drawCircle(
                color = Color(GalaxyPorthole.CENTER_COLOR).copy(alpha = 0.55f * (1f - t)),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx() * (1f - 0.5f * t)),
            )
        }
        // 恒星本体呼吸微亮（底盘常亮+呼吸沿——快照星点之上的「活」锚）
        drawCircle(
            color = Color(GalaxyPorthole.CENTER_COLOR).copy(alpha = 0.22f + 0.14f * pulse),
            radius = corePx * 1.05f,
            center = Offset(cx, cy),
        )
        // tag 行星标记（真相位+轨道椭圆；高亮随呼吸）
        val m = marker
        if (m != null && tag != null) {
            val off = GalaxyPorthole.tagMarkerOffset(m.phase01, m.orbitR01)
            val orbR = kotlin.math.hypot(off[0], off[1]) * pxPerWorld
            // 轨道椭圆（压扁 0.62——TagOrbitCloud 同款轨道面观感）
            drawOval(
                color = Color(GalaxyPorthole.CENTER_COLOR).copy(alpha = 0.30f),
                topLeft = Offset(cx - orbR, cy - orbR * 0.62f),
                size = androidx.compose.ui.geometry.Size(orbR * 2f, orbR * 1.24f),
                style = Stroke(width = 0.9.dp.toPx()),
            )
            val mx = cx + off[0] * pxPerWorld
            val my = cy + off[1] * pxPerWorld
            val dotR = (3.5f + 3.5f * m.sizeR01).dp.toPx()
            drawCircle(
                color = Color(GalaxyPorthole.CENTER_COLOR).copy(alpha = 0.30f + 0.20f * pulse),
                radius = dotR * 2.2f,
                center = Offset(mx, my),
            )
            drawCircle(
                color = Color(GalaxyPorthole.CENTER_COLOR),
                radius = dotR,
                center = Offset(mx, my),
            )
        }
    }
}
