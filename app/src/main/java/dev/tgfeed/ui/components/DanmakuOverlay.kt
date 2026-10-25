package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/** 弹幕滚动速度（dp/s，全轨道统一）：匀速穿过，时长 = (屏宽 + 胶囊宽) / 速度 */
private const val SPEED_DP_PER_SEC = 90f

/** 轨道高度（含胶囊 + 纵向微抖动余量） */
private val LANE_HEIGHT = 34.dp

/** 轨道数上限：小屏/横屏按高度自适应减少，大屏不超过此值（避免满屏盖视频） */
private const val MAX_LANES = 8

/** 胶囊横向内边距（计入弹幕宽度，影响滚动全程距离） */
private val CAPSULE_PAD_X = 10.dp

/** 胶囊纵向内边距 */
private val CAPSULE_PAD_Y = 3.dp

/** 轨道内纵向微抖动幅度上限：弱化"排队感"，更贴近真实弹幕站 */
private val LANE_JITTER = 6.dp

/** 单条最短滚动时长（防极端窄屏下飞速穿过看不清） */
private const val MIN_DURATION_MS = 1500

/** 时机映射：播放位置轮询间隔（调度协程节拍） */
private const val TIMING_POLL_MS = 250L

/** 时机映射：位置回退超过该值视为回拖/单条循环重播，弹幕布点重置、按点重发 */
private const val SEEK_BACK_THRESHOLD_MS = 1500L

/** 时机映射：到点超过该值仍未发的弹幕直接跳过（快进跨过/评论晚加载时不补喷一屏） */
private const val LATE_SKIP_MS = 3000L

/**
 * 时机映射·最小出点间隔地板（单调修正）：真实时间间隔映射下同一分钟连发的扎堆评论
 * 归一化后出点挤成一团，同一拍到点会瞬间吃光全部轨道、超出的被轨道制丢弃——
 * 地板把密集段摊成细流；远超地板值的大间隔比例关系不受影响（真实时间感不破）
 */
private const val MIN_DUE_GAP_MS = 700L

/**
 * "问出处"回复弹幕的上下文前缀标记（数据层 TdLibGateway.getCommentsRich 产出）：
 * 「回复「被回复摘要」<回复正文>」。识别命中后上下文段染高亮色（见 [ReplyContextColor]），
 * 正文保持白色；不认识的消费方按普通文本显示也无碍（前缀本身可读）。
 * 摘要段限定 1~20 字（数据层截断到 12 字+省略号，这里留余量），非贪婪匹配防「」撞车。
 */
private val REPLY_CONTEXT_REGEX = Regex("""^回复「(.{1,20}?)」(.*)$""")

/** 回复上下文段的高亮色（青蓝，与白色正文区分，一眼认出"这是答别人问的"） */
private val ReplyContextColor = Color(0xFF7ED6FF)

/**
 * 弹幕文本 → 渲染用 AnnotatedString：命中"回复「摘要」"前缀的回复弹幕，
 * 上下文段染 [ReplyContextColor]、正文保持默认白；普通弹幕原样返回（渲染与旧版一致）。
 * 注意：返回串的可见字符与输入逐字一致，文本宽度测量仍用原串即可。
 */
private fun danmakuStyled(text: String): AnnotatedString {
    val m = REPLY_CONTEXT_REGEX.matchEntire(text) ?: return AnnotatedString(text)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = ReplyContextColor)) {
            append("回复「")
            append(m.groupValues[1])
            append("」")
        }
        append(m.groupValues[2])
    }
}

/**
 * 弹幕层：把频道评论以"从右向左匀速滚过"的方式压到视频画面上。
 *
 * 设计要点：
 * - 轨道制：画面上 2/3 区域（扣除 topMargin）按 [LANE_HEIGHT] 均分为若干横向轨道，
 *   每条轨道同一时刻至多一条弹幕，从根上杜绝同轨道重叠/追尾；
 * - 匀速：每条弹幕时长 = (屏宽 + 胶囊宽) / 固定速度（[SPEED_DP_PER_SEC]），
 *   长文本自动拉长时长，任意长度的弹幕视觉速度恒定；
 * - 防重叠 = 时序串行：一条滚出左屏（progress=1）才发同轨道下一条，
 *   再加 250~1400ms 随机空档——天然不追尾，不需要"测速避让"那套复杂逻辑；
 * - 随机感：各轨道首发时间随机错开（0~2.6s）、起始评论索引按轨道错开、
 *   展示顺序整体洗牌、每条在轨道内纵向微抖动；
 * - 性能：滚动进度在 offset{} 的布局期读取，逐帧只触发 layout 阶段，不触发重组；
 * - 生命周期：enabled=false 或翻页导致本组件离开组合时，所有滚动协程随
 *   LaunchedEffect 取消，finally 清掉残留弹幕，不会泄漏；
 * - 时机映射（DANMAKU-TG 任务 3）：带时间戳的弹幕按"评论真实发布间隔"映射到视频
 *   进度——权重 = 相邻评论发布时间差（首条锚到视频发布时刻），归一化到时长，
 *   到点发射；越早的评论越早出现，间隔大的评论间隔也拉开（真实时间感）。
 *   每条只滚一次；回拖/单条循环位置大幅回退时重置布点按点重发；
 * - "问出处"上下文（群组讨论区问答链）：评论文本若以「回复「摘要」」前缀开头
 *   （数据层 getCommentsRich 对"命中资源"的回复评论加的上文标记），
 *   上下文段染高亮青蓝、正文保持白色（见 [danmakuStyled]）；
 *   其余"评论的回复"（isReply=true）加 "↩ " 前缀区分；无前缀弹幕渲染不变。
 *
 * 接线约定（本组件只负责渲染，数据与开关由外层注入）：
 * 评论文本来自 TdLibGateway.getCommentsRich（带时间戳的 DanmakuItem，视频页主路径）/
 * TdLibGateway.getComments / MockTdGateway.getComments（纯文本旧路径），
 * 开关状态来自 AppPrefs.danmakuEnabled。
 *
 * @param comments 纯文本弹幕（数据层已做去空白/去重/超长截断；
 *   回复类评论可能带「回复「摘要」」上下文前缀，本组件自动识别高亮）。
 *   [timedComments] 为 null 时的唯一数据源；非 null 时被忽略
 * @param enabled  false 时完全不渲染，也不启动任何滚动协程
 * @param topMargin 轨道区顶部留白，避开状态栏与"2x 快进中"等顶部胶囊浮层
 * @param timedComments 带时间戳的弹幕（DANMAKU-TG 任务 2/3，视频页主路径）：
 *   非 null 时时机模式按"评论真实时间间隔 → 视频进度"映射
 *   （权重 = 相邻评论发布时间差，归一化到视频时长，算法见 docs/DANMAKU-TG.md §3）；
 *   null = 纯文本旧路径，时机模式退化为"按评论序均匀分布在时长上"
 * @param videoPublishEpochSec 视频发布时刻（Unix 秒，VideoItem.dateEpochSec）：
 *   真实间隔映射的零点锚点；<=0 或晚于首条评论（时钟漂移）时锚到最早评论
 * @param positionProvider 时机映射：当前播放位置（毫秒）轮询源。与 [durationProvider]
 *   成对提供（视频页）；传 null（音频卡等无视频进度的场景）回落旧"轨道空了就来"串行模式
 * @param durationProvider 时机映射：视频总时长（毫秒）轮询源；<=0 = 播放器未就绪（等待）
 */
@Composable
fun DanmakuOverlay(
    comments: List<String> = emptyList(),
    enabled: Boolean,
    modifier: Modifier = Modifier,
    topMargin: Dp = 56.dp,
    timedComments: List<dev.tgfeed.core.td.DanmakuItem>? = null,
    videoPublishEpochSec: Long = 0L,
    positionProvider: (() -> Long)? = null,
    durationProvider: (() -> Long)? = null,
) {
    if (!enabled) return
    // 数据源判空：带时间戳路径以 timedComments 为准（null = 未加载/旧路径用 comments）
    val timed = timedComments
    if (timed == null) {
        if (comments.isEmpty()) return
    } else if (timed.isEmpty()) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 父级未给确定尺寸时不渲染（正常不会：VideoPage 里按 fillMaxSize 挂载）
        if (constraints.maxWidth == Constraints.Infinity ||
            constraints.maxHeight == Constraints.Infinity
        ) {
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val laneHeightPx = with(density) { LANE_HEIGHT.toPx() }
        val topMarginPx = with(density) { topMargin.toPx() }
        // 轨道数：上 2/3 区域容纳几条算几条，至少 1 条、最多 MAX_LANES 条
        val laneCount = ((heightPx * 2f / 3f - topMarginPx) / laneHeightPx)
            .toInt()
            .coerceIn(1, MAX_LANES)
        if (widthPx <= 0f || laneCount <= 0) return@BoxWithConstraints

        // 文本宽度测量：弹幕时长依赖真实文本像素宽，测量样式必须与渲染样式一致
        val textMeasurer = rememberTextMeasurer()
        val textStyle = TextStyle(
            color = Color.White,
            fontSize = 14.sp,
            // 黑描边效果：复用全局浮层文字投影（黑底 75% 透明度 + 6px 模糊）
            shadow = OverlayTextShadow,
        )
        val capsulePadXPx = with(density) { CAPSULE_PAD_X.toPx() }
        val jitterMaxPx = with(density) { LANE_JITTER.toPx() }

        // 展示顺序：入轨先去重（trim + 去空白 + 文本去重）再洗牌；remember 住防重组重洗。
        // 旧实现直接 comments.shuffled()：列表里的重复文本会被多条轨道同时滚、
        // 循环复播时又反复出现——"翻来覆去都是同一条"的观感来源之一
        val queue = remember(comments) {
            comments.map { it.trim() }.filter { it.isNotEmpty() }.distinct().shuffled()
        }
        // 去重后为空：不 early-return（remember 序列会错位），让轨道协程自然空转、
        // 渲染循环因 lane.item 恒 null 什么都不画即可
        // 轨道状态集合：laneCount 变化（如横竖屏切换）时整体重建
        val lanes = remember(laneCount) { List(laneCount) { DanmakuLane() } }

        if (positionProvider != null && durationProvider != null) {
            // ===== 时机映射模式（视频页）：评论映射到视频时长的对应时刻，到点才发射 =====
            // 带时间戳（timedComments != null，视频页主路径，DANMAKU-TG 任务 3）：
            //   真实时间间隔映射——权重 = 相邻评论发布时间差（首条锚到视频发布时刻），
            //   归一化到视频时长。越早的评论越早出现；发布间隔大的评论之间出现间隔也拉开
            //   （视频发布后第 1 天的评论与第 10 天的评论，按 1:10 的比例落在进度条上，
            //   算法与例子见 docs/DANMAKU-TG.md §3）。
            // 无时间戳（timedComments == null，纯文本调用方）：退化为旧的"按序均匀分布"。
            // 弹幕计划：每条 = (展示文本, 间隔权重)；权重 0 合法（与锚点/上一条同刻）
            val timedPlan = remember(comments, timedComments, videoPublishEpochSec) {
                val tl = timedComments
                if (tl != null) {
                    // 清洗 + 按发布时间升序（越早的评论越早出现）+ 文本去重（保最早一条）
                    val sorted = tl.map { it.copy(text = it.text.trim()) }
                        .filter { it.text.isNotEmpty() }
                        .sortedBy { it.timestampSec }
                        .distinctBy { it.text }
                    if (sorted.isEmpty()) {
                        emptyList()
                    } else {
                        // 锚点：视频发布时刻（epoch 秒）；缺失（<=0）/晚于首条评论
                        //（时钟漂移）时锚到最早评论——首条权重归零、落在视频开头
                        val anchor = if (videoPublishEpochSec > 0L &&
                            videoPublishEpochSec <= sorted.first().timestampSec
                        ) {
                            videoPublishEpochSec
                        } else {
                            sorted.first().timestampSec
                        }
                        val plan = sorted.mapIndexed { i, d ->
                            val w = if (i == 0) {
                                (d.timestampSec - anchor).coerceAtLeast(0L)
                            } else {
                                (d.timestampSec - sorted[i - 1].timestampSec).coerceAtLeast(0L)
                            }
                            // 回复标记：评论的回复带 "↩ " 前缀（已带「回复「摘要」」上下文
                            // 前缀的不再重复加——前缀本身已表达回复语义）
                            val display =
                                if (d.isReply && !d.text.startsWith("回复「")) "↩ " + d.text else d.text
                            display to w.toFloat()
                        }
                        // 全部权重为 0（评论全同刻/锚点压平）：无从谈间隔，退化为均匀分布
                        if (plan.all { it.second <= 0f }) plan.map { it.first to 1f } else plan
                    }
                } else {
                    // 纯文本旧路径：不洗牌保序。数据源 getCommentsRich 倒序取线程历史
                    //（最新在前），反转为最早在前——"越早的评论越早出现"
                    comments.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        .asReversed().map { it to 1f }
                }
            }
            LaunchedEffect(timedPlan, widthPx, laneCount) {
                val rnd = Random(System.nanoTime())
                // 等播放器就绪（prepare 前 duration 未知/<=0）
                var durationMs = durationProvider()
                while (durationMs <= 0L) {
                    delay(TIMING_POLL_MS)
                    durationMs = durationProvider()
                }
                val n = timedPlan.size
                if (n == 0) return@LaunchedEffect
                // 出现时刻 = 累计权重占满整段时长：t_i = duration × cum_i / total
                //（真实间隔映射：伸缩求和后 total = 末条评论时间 - 锚点，
                //  首条评论到视频开头的距离 = 它距发布时刻的真实间隔占比）
                val weights = FloatArray(n) { timedPlan[it].second }
                val totalWeight = weights.sum()
                val dueMs = LongArray(n)
                var acc = 0f
                for (i in 0 until n) {
                    acc += weights[i]
                    dueMs[i] = (durationMs * acc / totalWeight).toLong().coerceIn(0L, durationMs)
                }
                // 单条评论：无"间隔"可表达，开场即出（否则权重归一会把它压到片尾）
                if (n == 1) dueMs[0] = 0L
                // 最小出点间隔地板（[MIN_DUE_GAP_MS]，单调修正）：真实间隔映射下同分钟
                // 连发的扎堆评论归一化后出点挤成一团，同一拍到点会瞬间吃光全部轨道、
                // 超出的被丢弃——地板把密集段摊成细流（注意 clamp 到 durationMs 后
                // 末段可能并点，那些评论本就是时间线上最晚的，可接受）
                for (i in 1 until n) {
                    val floor = dueMs[i - 1] + MIN_DUE_GAP_MS
                    if (dueMs[i] < floor) dueMs[i] = floor.coerceAtMost(durationMs)
                }
                val fired = BooleanArray(n) // 到点即消费：同一遍播放内每条只滚一次，不重复
                val scrollJobs = ArrayList<Job>(n) // 在滚弹幕的协程，回拖重置时统一取消
                var lastPos = 0L
                try {
                    while (true) {
                        val pos = positionProvider().coerceAtLeast(0L)
                        // 回拖/单条循环重播（位置大幅回退）：取消在滚弹幕 + 重置布点，重新按点发射
                        if (pos < lastPos - SEEK_BACK_THRESHOLD_MS) {
                            scrollJobs.forEach { it.cancel() } // 各自 finally 清 lane.item
                            scrollJobs.clear()
                            fired.fill(false)
                        }
                        lastPos = pos
                        for (i in 0 until n) {
                            if (fired[i] || dueMs[i] > pos) continue
                            fired[i] = true
                            // 迟到过多（快进跨过/评论加载晚于到点）跳过不补，避免一屏喷满
                            if (pos - dueMs[i] > LATE_SKIP_MS) continue
                            // 找空轨道，全满宁可丢也不叠字（轨道制防重叠的底线不破）
                            val lane = lanes.firstOrNull { it.item == null } ?: continue
                            val text = timedPlan[i].first
                            val textWidthPx = textMeasurer.measure(text, textStyle).size.width.toFloat()
                            if (textWidthPx <= 0f) continue
                            val capsuleWidthPx = textWidthPx + capsulePadXPx * 2f
                            val speedPxPerSec = with(density) { SPEED_DP_PER_SEC.dp.toPx() }
                            val scrollMs = ((widthPx + capsuleWidthPx) / speedPxPerSec * 1000f)
                                .toInt()
                                .coerceAtLeast(MIN_DURATION_MS)
                            lane.item = RollingItem(
                                text = text,
                                styled = danmakuStyled(text),
                                widthPx = capsuleWidthPx,
                                yJitterPx = rnd.nextFloat() * jitterMaxPx,
                            )
                            // 每条一个滚动协程：匀速滚完自清轨道；被取消（翻页/回拖重置）走 finally
                            scrollJobs.add(
                                launch {
                                    try {
                                        lane.progress.snapTo(0f)
                                        lane.progress.animateTo(1f, tween(scrollMs, easing = LinearEasing))
                                    } finally {
                                        lane.item = null
                                    }
                                },
                            )
                        }
                        delay(TIMING_POLL_MS)
                    }
                } finally {
                    lanes.forEach { it.item = null } // 翻页/关开关/列表变更时清残留
                }
            }
        } else {
        // ===== 轨道串行模式（无播放位置来源：音频卡等；原有时机=轨道空了就来） =====
        // 每条轨道一个常驻协程：发一条 → 匀速滚完 → 随机空档 → 下一条（串行即防重叠）。
        // 一条评论全屏只滚一次：轨道按 laneCount 步长瓜分队列（轨道 i 负责 i, i+laneCount, …），
        // 跑完自己那份协程就结束；旧实现 while(true) 循环复播，评论少时每条无限重复滚，
        // 是"弹幕重复显示（翻来覆去）"的根源
        lanes.forEachIndexed { laneIndex, lane ->
            LaunchedEffect(laneIndex, queue, widthPx) {
                val rnd = Random(System.nanoTime() xor (laneIndex * 2654435761L))
                delay(rnd.nextLong(0L, 2600L)) // 轨道间随机错开首发，避免开场整排齐发
                var idx = laneIndex // 起始索引按轨道错开，降低同屏重复概率
                try {
                    while (idx < queue.size) {
                        val text = queue[idx]
                        idx += laneCount // 步长瓜分：每条评论只入轨一次，不循环复播
                        // 胶囊宽 = 文本宽 + 两侧内边距；滚动全程 = 屏宽 + 胶囊宽（从右屏外到左屏外）
                        val textWidthPx = textMeasurer.measure(text, textStyle).size.width.toFloat()
                        if (textWidthPx <= 0f) continue
                        val capsuleWidthPx = textWidthPx + capsulePadXPx * 2f
                        val speedPxPerSec = with(density) { SPEED_DP_PER_SEC.dp.toPx() }
                        val durationMs = ((widthPx + capsuleWidthPx) / speedPxPerSec * 1000f)
                            .toInt()
                            .coerceAtLeast(MIN_DURATION_MS)
                        lane.item = RollingItem(
                            text = text,
                            // 染色构建每条弹幕只做一次（一条几秒才发一条，开销可忽略）；
                            // 可见字符与 text 逐字一致，上方用 text 测宽的结果对 styled 同样成立
                            styled = danmakuStyled(text),
                            widthPx = capsuleWidthPx,
                            yJitterPx = rnd.nextFloat() * jitterMaxPx,
                        )
                        lane.progress.snapTo(0f)
                        // 匀速：线性缓动；被取消时抛 CancellationException 走 finally 清理
                        lane.progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
                        lane.item = null
                        delay(rnd.nextLong(250L, 1400L)) // 同轨道两条之间的随机空档
                    }
                } finally {
                    lane.item = null // 协程被取消（翻页/关开关/列表变更）时清掉残留弹幕
                }
            }
        }
        }

        // 渲染：进度在 offset{} 布局期读取，逐帧只走 layout 不走重组
        lanes.forEachIndexed { laneIndex, lane ->
            val item = lane.item ?: return@forEachIndexed
            val baseYPx = topMarginPx + laneIndex * laneHeightPx
            Text(
                // AnnotatedString 版 Text：字号/投影继承 textStyle，
                // 回复弹幕的上下文段颜色由 styled 内的 SpanStyle 覆盖
                text = item.styled,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .offset {
                        // progress 0 → 1：左边缘从右屏外(widthPx) 滚到左屏外(-胶囊宽)
                        val x = widthPx - lane.progress.value * (widthPx + item.widthPx)
                        IntOffset(x.roundToInt(), (baseYPx + item.yJitterPx).roundToInt())
                    }
                    // 半透明黑胶囊底（复用全局浮层胶囊样式）
                    .background(OverlayChipBg, OverlayChipShape)
                    .padding(horizontal = CAPSULE_PAD_X, vertical = CAPSULE_PAD_Y),
            )
        }
    }
}

/** 一条正在滚动的弹幕（widthPx 为含胶囊内边距的总宽；yJitterPx 为轨道内纵向微抖动）。
 *  原名 DanmakuItem，为避让数据层公开的 dev.tgfeed.core.td.DanmakuItem（DANMAKU-TG 任务 2）
 *  改名 RollingItem——它只表达"渲染中的一条"，与数据契约无关 */
private data class RollingItem(
    val text: String, // 纯文本原文（宽度测量用；与 styled 可见字符逐字一致）
    val styled: AnnotatedString, // 渲染用（"问出处"回复弹幕的上下文段已染色；普通弹幕原样）
    val widthPx: Float,
    val yJitterPx: Float,
)

/** 单条轨道状态：item=null 表示轨道空闲；progress 0→1 = 从右屏外滚到左屏外 */
private class DanmakuLane {
    var item by mutableStateOf<RollingItem?>(null)
    val progress = Animatable(0f)
}
