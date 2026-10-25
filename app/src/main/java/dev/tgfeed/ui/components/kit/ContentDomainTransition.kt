package dev.tgfeed.ui.components.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R189 内容域转场（任务书②：横滑切媒体 tab / 级联换列的内容平滑过渡）。
 *
 * **形态**：单容器淡入 + 方向性轻位移——key 变化（轨道切换）时 graphicsLayer
 * alpha snap 0 → tween(AnimSlow=320, EmphasizedEasing) 回 1，translationX 从
 * 方向×[MotionTokens.TrackTransitionSlide] 归零。旧内容瞬时退场、新内容带方向感
 * 淡入（位移方向 = 轨道序前进方向，与手势方向一致）。
 *
 * R194⑤ 可见性加档（用户实测「感知不到」）：AnimMed 220ms + 16dp 在切换窗的
 * 重组/换血负载下常被压进两三帧，观感≈硬切；时长升 AnimSlow=320、位移升 24dp
 * （换轨是页级事件，320ms 仍利落；高频翻页不受影响——本 kit 只吃轨道切换）。
 *
 * **为什么不做真 Crossfade（双分支并存渲染）**：内容域是视频负载——过渡窗内
 * 旧 tab 播放器继续出声 + 新页起播 = 双声重叠/播放器翻倍 churn，不可接受；
 * 单容器淡入零播放器生命周期扰动（旧页 dispose→release 照常瞬时完成），观感
 * 等价（视频画面 TextureView——graphicsLayer alpha 对画面生效，SurfaceView 打洞
 * 问题不存在）。
 *
 * **纪律**（§5.3）：内容域用 tween——时长取 [TgFeedTokens.AnimSlow] 三档主干，
 * 曲线 [MotionTokens.EmphasizedEasing]；动画值只进 graphicsLayer 块（重绘不重组）。
 * 首次组合不播（防干扰冷启动首屏亮相与 R160 加载态时序）。
 *
 * **函数形态而非 Modifier 扩展**：共享树并发期选择性暂存按「新增行含 R189 标记」
 * 切 hunk——扩展函数必须 import 消费（import 行无标记会被滤丢→提交树编译断），
 * 函数形态可全限定调用（MainActivity 全限定惯例同源），消费侧零 import。
 *
 * @param modifier 内容容器的基础修饰（fillMaxSize 等）
 * @param key 轨道标识（任一变化即转场；调用方用 (源实例, 媒体tab) 复合键——tab 切换
 *   与换列（源实例变化）同款触发）
 * @param directionOf 方向裁决（旧 key → 新 key → -1f/0f/+1f；+1 = 前进 = 内容自右侧
 *   淡入（与左滑手势同向），调用方按轨道序算；null = 纯淡入无位移）
 * @return 挂好转场 graphicsLayer 的修饰符（交给内容容器 Box）
 */
@Composable
internal fun contentDomainTransition(
    modifier: Modifier,
    key: Any?,
    directionOf: ((oldKey: Any?, newKey: Any?) -> Float)? = null,
): Modifier {
    val fade = remember { Animatable(1f) }
    var slideDir by remember { mutableStateOf(0f) }
    var prevKey by remember { mutableStateOf<Any?>(null) }
    var armed by remember { mutableStateOf(false) }
    // 首次组合不播（armed 位）；此后 key 变化即转场。prevKey 在效应内先取再换——
    // 方向裁决拿得到「旧轨道」
    LaunchedEffect(key) {
        val prev = prevKey
        prevKey = key
        if (armed && prev != key) {
            slideDir = directionOf?.invoke(prev, key) ?: 0f
            fade.snapTo(0f)
            // R194⑤ 探针（机检锚点）：转场起拍一行——真机复核「切 tab 无转场感」
            // 时先看这条在不在（在=已接线，再看时长是否被负载压扁）
            android.util.Log.i(
                "R194",
                "轨道转场起拍 dir=${slideDir.toInt()} ${TgFeedTokens.AnimSlow}ms",
            )
            fade.animateTo(
                1f,
                tween(
                    TgFeedTokens.AnimSlow,
                    easing = MotionTokens.EmphasizedEasing,
                ),
            )
        }
        armed = true
    }
    val slidePx = with(LocalDensity.current) { MotionTokens.TrackTransitionSlide.toPx() }
    return modifier.graphicsLayer {
        alpha = fade.value
        translationX = (1f - fade.value) * slideDir * slidePx
    }
}
