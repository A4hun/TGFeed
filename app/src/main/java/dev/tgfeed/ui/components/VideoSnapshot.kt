package dev.tgfeed.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import dev.tgfeed.core.playback.FrameCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图链（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * [videoFrameCapture] 返回"抓帧入口"闭包：渲染面是 TextureView 直接取 bitmap；
 * SurfaceView 走 PixelCopy（API 26+）；两条路都拿不到就 Toast"当前渲染模式不支持截图"。
 * 当前 PlayerView 实例经 [playerViewProvider] 现读（页面侧 MutableState 引用，视图重建即失效）。
 */

/**
 * 抓帧入口工厂：截图当前帧存相册（IO 线程写 MediaStore，结果 Toast 回主线程）。
 * 每次重组重建（与旧实现"组合期新建闭包"一致，无 remember）。
 */
internal fun videoFrameCapture(
    context: Context,
    scope: CoroutineScope,
    chatId: Long,
    messageId: Long,
    playerViewProvider: () -> PlayerView?,
): () -> Unit {
    // 截图当前帧存相册（IO 线程写 MediaStore，结果 Toast 回主线程）
    val saveFrame: (Bitmap) -> Unit = { bmp ->
        scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                FrameCapture.saveToGallery(
                    context,
                    bmp,
                    "tgfeed_${chatId}_${messageId}_${System.currentTimeMillis()}.jpg",
                )
            }.getOrNull()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (saved != null) "已保存到相册 $saved" else "截图保存失败（需 Android 10 及以上）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    // 抓帧入口：渲染面是 TextureView 直接取 bitmap；SurfaceView 走 PixelCopy（API 26+）；
    // 两条路都拿不到就 Toast"当前渲染模式不支持截图"
    return {
        val surface = playerViewProvider()?.videoSurfaceView
        when {
            surface == null ->
                Toast.makeText(context, "播放器未就绪，稍后再试", Toast.LENGTH_SHORT).show()
            surface is TextureView -> {
                val bmp = surface.bitmap
                if (bmp != null) {
                    saveFrame(bmp)
                } else {
                    Toast.makeText(context, "未取到当前帧，稍后再试", Toast.LENGTH_SHORT).show()
                }
            }
            surface is SurfaceView && surface.width > 0 && surface.height > 0 -> {
                val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                runCatching {
                    PixelCopy.request(
                        surface,
                        bmp,
                        { result ->
                            if (result == PixelCopy.SUCCESS) {
                                saveFrame(bmp)
                            } else {
                                Toast.makeText(context, "当前渲染模式不支持截图", Toast.LENGTH_SHORT).show()
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                }.onFailure {
                    Toast.makeText(context, "当前渲染模式不支持截图", Toast.LENGTH_SHORT).show()
                }
            }
            else ->
                Toast.makeText(context, "当前渲染模式不支持截图", Toast.LENGTH_SHORT).show()
        }
    }
}
