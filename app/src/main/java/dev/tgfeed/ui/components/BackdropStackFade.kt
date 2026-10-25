package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer

/**
 * R194①② 底衬换图组件（双镜像播放的渲染层——架构师 17:00 终案）。
 *
 * **为什么替换 Compose `Crossfade`**（用户实锤「背景换主仍闪，还是突然闪一下
 * 就过去了」的机理定位）：`Crossfade` 对新旧两层做**并行双向**淡入淡出
 * （javap 实证 animation 1.7.2 `Transition.Crossfade` 每层 alpha =
 * `animateFloat { if (it == stateForContent) 1f else 0f }`）——中点处两层各
 * ~0.5，合成总不透明度 = α+α′−αα′ ≈ **0.75**：四分之一的黑底从两层之间渗出，
 * 全屏背景亮度中点下坠 25% 再回弹 = 感知上的「闪一下（变暗）就过去」。150ms
 * AnimFast 下这是一个快速暗闪；拉长只是闪得更慢，谷还在。
 *
 * **本组件结构**：基帧（committed，恒全显）+ 淡入帧（fading，alpha 0→1）+
 * 整层明暗（layerOut，null 清屏/暗场用）。合成总不透明度恒 1（新帧画在基帧
 * 之上），亮叠亮过渡 = 纯交叉溶解、零黑谷。
 *
 * **三模式**（[BackdropTransition]，随 target 同拍由调用方给定——FeedScreen
 * 按上报语境裁决）：
 * - [BackdropTransition.SNAP]：同条目镜像帧更新（TextureView 节流快照 ~12fps）——
 *   视频式直换，模糊掩盖低刷率（「两画面同步，用户无感」）；
 * - [BackdropTransition.SOFT]：翻页换主/live 首拍——基帧全显+新帧 AnimMed 淡入
 *   （「新视频首帧起镜像渐入」；无黑谷的亮叠亮交叉溶解）；
 * - [BackdropTransition.THROUGH_DARK]：离散切换（源/tab）——暗场溶解：旧图整层
 *   暗下（exitSpec）→ 换帧 → 新图从暗中亮起（enterSpec），不做亮对亮直切
 *   （架构师 16:55 两式过渡的离散式）。
 *
 * **null 目标**（ZOOM 页清屏）：整层软退场到黑——替代旧「条件移除整块组合」
 * 的单帧硬消失（FIT→ZOOM 翻页窗的黑闪成分）；null→非 null 回程先软回满层。
 *
 * **中断策略**（过渡未完又来新帧）：SNAP 即时转正；SOFT 上一淡入帧即时转正为
 * 基帧（单次小跳，罕见——轨道切换窗另有冻结闸收敛连发）。同帧重报（===）无操作。
 *
 * @param target 当前应显示的帧；null = 清屏（软退场）
 * @param mode 本次 target 变化的过渡模式（调用方与 target 同拍写入）
 * @param enterSpec SOFT 淡入 / THROUGH_DARK 亮起 / null 回程的时长档
 * @param exitSpec null 清屏与 THROUGH_DARK 暗下的时长档（退场 ≤ 进场纪律）
 * @param content 帧绘制（调用方自带 blur/放大——本组件只管层级与 alpha）
 */
internal enum class BackdropTransition { SNAP, SOFT, THROUGH_DARK }

@Composable
internal fun BackdropStackFade(
    target: ImageBitmap?,
    mode: BackdropTransition,
    enterSpec: FiniteAnimationSpec<Float>,
    exitSpec: FiniteAnimationSpec<Float>,
    modifier: Modifier = Modifier,
    content: @Composable (ImageBitmap) -> Unit,
) {
    // 基帧（全显）与淡入帧：两槽结构，槽位在组合树中的位置恒定（换图=槽内换帧）
    var committed by remember { mutableStateOf<ImageBitmap?>(null) }
    var fading by remember { mutableStateOf<ImageBitmap?>(null) }
    val enter = remember { Animatable(0f) }
    val layerOut = remember { Animatable(1f) }
    LaunchedEffect(target, mode) {
        if (target == null) {
            // 清屏：整层（基帧+淡入帧）软退场，落定后清仓（再进非空目标时
            // layerOut 已在 0——先回满层再叠新帧，见下方分支）
            if (committed != null || fading != null) {
                layerOut.animateTo(0f, exitSpec)
                committed = null
                fading = null
            }
        } else {
            if (layerOut.value < 1f) {
                // null→非 null 回程（ZOOM→FIT 翻页窗）：先软回满层（基帧还在，
                // 无黑窗）再叠新帧
                layerOut.animateTo(1f, enterSpec)
            }
            when (mode) {
                BackdropTransition.SNAP -> {
                    // 镜像帧：视频式直换（进行中的淡入帧也一并即时转正）
                    committed = target
                    fading = null
                }
                BackdropTransition.THROUGH_DARK -> {
                    // 暗场溶解：旧图暗下 → 换帧 → 新图从暗中亮起
                    if (committed != null) {
                        layerOut.animateTo(0f, exitSpec)
                    }
                    committed = target
                    fading = null
                    layerOut.animateTo(1f, enterSpec)
                }
                BackdropTransition.SOFT -> {
                    val cur = fading
                    if (cur != null && cur != target) {
                        // 中断：上一淡入帧即时转正为基帧（见类注释中断策略）
                        committed = cur
                    }
                    if (committed == target) {
                        fading = null // 同帧重报/中断后目标恰为基帧：清淡入槽
                    } else {
                        fading = target
                        enter.snapTo(0f)
                        enter.animateTo(1f, enterSpec)
                        // 落定：淡入帧全显（与基帧视觉相同）→ 转正入基帧槽、清淡
                        // 入槽，同帧无缝（同一张图同一 alpha=1）
                        committed = target
                        fading = null
                    }
                }
            }
        }
    }
    Box(modifier.graphicsLayer { alpha = layerOut.value }) {
        committed?.let { frame ->
            Box(Modifier.fillMaxSize()) { content(frame) }
        }
        fading?.let { frame ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = enter.value },
            ) { content(frame) }
        }
    }
}
