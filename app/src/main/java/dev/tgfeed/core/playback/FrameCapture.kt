package dev.tgfeed.core.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * 播放帧截图落相册（与视频下载 Movies/TGFeed 同思路，改 Pictures/TGFeed）。
 *
 * API 29+ 走 MediaStore.Images 两段式（RELATIVE_PATH + IS_PENDING），分区存储下无需任何权限；
 * API 26–28 需要 WRITE_EXTERNAL_STORAGE（Manifest 未声明），直接抛异常由调用方 Toast 兜底。
 */
object FrameCapture {

    /** 相册子目录：Pictures/TGFeed */
    private const val ALBUM_NAME = "TGFeed"

    /**
     * 把一帧位图写入公共相册 `Pictures/TGFeed/`，返回展示用相对路径。
     * 必须在 IO 线程调用；任何一步失败抛异常（半成品记录会被清掉）。
     */
    fun saveToGallery(context: Context, frame: Bitmap, displayName: String): String {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "保存截图需要 Android 10 及以上" }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1) // 挂起态：写入期间其他 App 不可见
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore 插入失败")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                // 视频帧不带透明通道，JPEG 92 质量兼顾体积与观感
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 92, out)) error("帧压缩失败")
            } ?: error("无法打开相册输出流")
            // 写完解除挂起，相册立即可见
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // 失败时清掉半成品记录
            throw e
        }
        return "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME/$displayName"
    }
}
