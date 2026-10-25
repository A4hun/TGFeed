package dev.tgfeed.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * R194④ 频道头像解码共享件（R170「解码挪 IO」榜单 #6 家族同刀收编）。
 *
 * 背景（用户实测「汉堡左抽屉弹出卡顿掉帧」）：头像族旧写法 = LaunchedEffect 拉字节 +
 * `remember(bytes) { BitmapFactory.decodeByteArray(...) }`——**解码跑在组合期
 * （主线程）**。抽屉打开的 spring 动画窗内，频道列表 ~15 行头像字节陆续到货、
 * 逐个触发「主线程解码 + 行重组」，逐帧吃掉帧预算 = 弹簧掉帧的主源。
 *
 * 本件语义：字节加载（loader 自带进程内缓存，行为不变）→ **IO 线程解码** →
 * ImageBitmap 状态（R396 起单跳写状态：解码完成才写，动画窗内行重组减半）。
 * 同类现场（纪律§一）全部收编到此：右轨头像/信息区头像/
 * 源面板频道卡/快拍栏/礼包频道卡/底栏我的座位。AudioCard/PhotoCard/VideoPage
 * 的封面解码已在各自 IO 通道（R170/W4），不属本族。
 */

/**
 * 频道头像字节 → [ImageBitmap]（加载+解码双 IO 化）。
 * loader 为空 / 无头像 / 解码失败 → null（调用方回落字母圆）。
 * 同一 chatId 只拉一次（状态键）；同字节只解一次。
 *
 * **R396 丝滑化（单跳状态）**：旧实现「字节到货」「解码完成」各写一次状态 = 两跳
 * 行重组——抽屉 spring 动画窗内 ~15 行头像陆续到货即 ~30 次行级重组分摊进帧预算
 * （R194④ 已把解码挪 IO 线程，本刀收掉残余的重组税）。本版 produceState 单跳：
 * **解码完成才写一次状态**，窗内行级重组 15×2 → 15×1；IO 纪律零变。
 */
@Composable
internal fun rememberChannelAvatarImage(
    chatId: Long,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
): ImageBitmap? = produceState<ImageBitmap?>(null, chatId, avatarLoader) {
    if (avatarLoader == null) return@produceState
    val bytes = runCatching { avatarLoader(chatId) }.getOrNull() ?: return@produceState
    value = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }?.asImageBitmap()
}.value

/**
 * 已到手的头像字节 → [ImageBitmap]（纯解码口，字节来源任意的现场共用：
 * 如底栏我的座位=profile 快照自带 avatarBytes）。
 */
@Composable
internal fun rememberDecodedAvatarImage(bytes: ByteArray?): ImageBitmap? {
    var image by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        if (bytes != null && bytes.isNotEmpty()) {
            image = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }?.asImageBitmap()
        }
    }
    return image
}
