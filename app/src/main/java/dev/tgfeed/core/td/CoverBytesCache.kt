package dev.tgfeed.core.td

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

// ---------------- 高清封面字节缓存策略（R169 P0-1 / F7） ----------------

/**
 * 高清封面字节缓存上限（条）：fileId → JPEG 字节，单条缩略图档 ≈10-100KB、
 * 128 条 ≈1.3-13MB——覆盖"最近若干屏 + 回访窗口"，淘汰后走磁盘层
 * （msg_covers）或秒级重拉（封面小图），用户无感（R155 CoverExtractor 同论证）。
 * 家族口径：CoverExtractor 64 条（Bitmap）/ msgCoverMemCache 256 条（ByteArray），
 * 本表存的是"高清档"字节（音频专辑封面可到数百 KB），取中间档。
 */
internal const val COVER_BYTES_CACHE_MAX = 128

/**
 * 封面字节入缓存的尺寸上限（长边 px）：超档等比降到档内再重编码入表。
 * 与 R155 CoverExtractor 的 960px 档同值同论证——封面消费面（网格小格/
 * 全屏模糊底衬/搜索缩略）全是低细节场景，960px 视觉无损、内存砍数倍。
 * （CoverExtractor 那份是文件级私有常量，跨 core/td 与 core/playback 统一
 * 收敛挂账 census P2-2；本常量是 core/td 侧唯一真源。）
 */
internal const val COVER_BYTES_MAX_DIM = 960

/** 降采样重编码的 JPEG 质量（与 CoverExtractor 落盘同档 85） */
internal const val COVER_BYTES_JPEG_QUALITY = 85

/**
 * 封面字节降采样（[coverBytesCache] 的 transform，R169 P0-1）：
 * 先量尺寸（inJustDecodeBounds 只解析头，开销微秒级）；长边 ≤[COVER_BYTES_MAX_DIM]
 * 或尺寸不明（≤0，解码不了的怪字节原样放行由消费端兜底）→ 原样返回；
 * 超档 → 解码、等比缩到档内、重编码 JPEG 返回。
 * 任何一步失败（解码/缩放/编码异常）返回原字节——降采样是内存优化，
 * 失败不构成功能失败，绝不因此丢封面。
 * 纯 CPU 操作，无 IO；调用方约定在 IO 协程（ChannelHomeScreen R169 起三分支统一 IO）。
 */
internal fun downscaleCoverBytes(src: ByteArray): ByteArray {
    if (src.isEmpty()) return src
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    if (longEdge <= COVER_BYTES_MAX_DIM) return src
    val reencoded = runCatching {
        val full = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return@runCatching null
        val scale = COVER_BYTES_MAX_DIM.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            full,
            (full.width * scale).toInt().coerceAtLeast(1),
            (full.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val buf = ByteArrayOutputStream(maxOf(full.byteCount / 2, 4096))
        scaled.compress(Bitmap.CompressFormat.JPEG, COVER_BYTES_JPEG_QUALITY, buf)
        buf.toByteArray()
    }.getOrNull()
    return reencoded?.takeIf { it.isNotEmpty() } ?: src
}
