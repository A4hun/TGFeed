package dev.tgfeed.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * ImageBitmap 状态。同类现场（纪律§一）全部收编到此：右轨头像/信息区头像/
 * 源面板频道卡/快拍栏/礼包频道卡/底栏我的座位。AudioCard/PhotoCard/VideoPage
 * 的封面解码已在各自 IO 通道（R170/W4），不属本族。
 */

/**
 * 频道头像字节 → [ImageBitmap]（加载+解码双 IO 化）。
 * loader 为空 / 无头像 / 解码失败 → null（调用方回落字母圆）。
 * 同一 chatId 只拉一次（remember 键）；同字节只解一次（状态键）。
 */
@Composable
internal fun rememberChannelAvatarImage(
    chatId: Long,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
): ImageBitmap? {
    var bytes by remember(chatId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(chatId, avatarLoader) {
        if (avatarLoader != null && bytes == null) {
            bytes = runCatching { avatarLoader(chatId) }.getOrNull()
        }
    }
    return rememberDecodedAvatarImage(bytes)
}

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
