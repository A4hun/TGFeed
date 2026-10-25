package dev.tgfeed.core.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * 无缩略图视频的封面补救：从本地视频文件抽一帧当封面。
 *
 * 背景：部分频道发的视频 TDLib 不带 thumbnail（minithumbnail 也没有时封面是大块马赛克）。
 * 播过一次后本地就有文件，抽 1 秒处的帧缓存下来，以后所有界面（Feed/分屏/搜索）
 * 都用它当封面。
 * 两级缓存：内存 LRU（key=条目 key，上限 [MAX_MEM]）+ 磁盘 filesDir/covers/<hash>.jpg。
 *
 * R155 两处止血（实测长会话 native 堆 50MB→3.9GB 只增不减，本缓存是实锤贡献者）：
 *  1. 内存缓存从无界 ConcurrentHashMap 改 [MAX_MEM] 条访问序 LRU——封面使用面
 *     是"可见区那几张"，64 条上限足够，淘汰后走磁盘缓存/重抽，无感；
 *  2. 抽帧结果入缓存前统一降采样到长边 [MAX_DIM]px——旧口径全分辨率原图
 *     （1080p 单张 8.3MB native 堆），而消费面最大的全屏模糊底衬/分屏小格/搜索缩略
 *     全是低细节场景，960px 档（单张约 0.5~2MB）视觉无损、内存砍 4~9 倍。
 */
object CoverExtractor {
    private const val TAG = "CoverExtractor"

    /** 内存缓存上限（条）：超出淘汰最久未访问者 */
    private const val MAX_MEM = 64

    /** 缓存帧长边上限（px）：全屏模糊底衬/小格封面都是低细节消费，更高分辨率纯浪费 */
    private const val MAX_DIM = 960

    /** 访问序 LRU（全部读写经 memGet/memPut/invalidate 加锁；get 也算访问会刷新顺位） */
    private val mem = object : LinkedHashMap<String, Bitmap>(MAX_MEM, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_MEM
    }

    @Synchronized
    private fun memGet(key: String): Bitmap? = mem[key]

    @Synchronized
    private fun memPut(key: String, bmp: Bitmap) {
        mem[key] = bmp
    }

    /** 长边超 [MAX_DIM] 的帧等比缩到档内（已在档内原样返回）；缩放失败原样返回 */
    private fun downscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val longEdge = maxOf(w, h)
        if (longEdge <= MAX_DIM || w <= 0 || h <= 0) return src
        val scale = MAX_DIM.toFloat() / longEdge
        return runCatching {
            Bitmap.createScaledBitmap(
                src,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrDefault(src)
    }

    /**
     * 取或抽：有缓存直接给；没有就从 [localPath] 抽 1 秒处的帧。
     * 必须在 IO 线程调用（MediaMetadataRetriever 会读文件）。
     */
    fun getOrExtract(key: String, localPath: String?, cacheDir: File): Bitmap? {
        memGet(key)?.let { return it }
        if (localPath.isNullOrBlank()) return null
        val f = File(localPath.removePrefix("file://"))
        if (!f.isFile) return null

        // 磁盘缓存键 = 条目 key（tg:chatId:messageId，稳定唯一），不再用路径 hash：
        // 旧实现 (path+mtime).hashCode() 是 32 位哈希会碰撞，撞到就显示别的视频的封面
        // 且永远命中不更新（用户实测"封面不对、搜多少次都不更新"）。
        // 消息的视频内容不可变，按条目 key 存语义正确。
        // covers_v3：covers_v2 时代 fetchCoverFrame 对落盘旧条目的会话级 fileId 不验身
        // 就抽帧（R74 前），同号异文件会把别的视频画面错存成本条目封面且永久命中；
        // R74 起抽帧源 id 先验身/反解换发（见 FeedViewModel.fetchCoverFrame），
        // 存量 v2 缓存无法逐张区分对错，整体废弃换代（帧可重抽，弃错无代价）。
        val dir = File(cacheDir, "covers_v3").apply { mkdirs() }
        val safeKey = key.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val cacheFile = File(dir, "$safeKey.jpg")
        if (cacheFile.isFile) {
            // R155：磁盘缓存可能存着修复前的全分辨率旧图，读出后同样过降采样档再入内存
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
            }.getOrNull()?.let { downscale(it) }
            if (bmp != null) {
                memPut(key, bmp)
                return bmp
            }
        }

        // 抽帧：1 秒处（开头常是黑帧/Logo，1 秒更可能是正片）
        val bmp = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                retriever.getFrameAtTime(1_000_000L) // 微秒
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()?.let { downscale(it) } ?: return null

        runCatching {
            cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        memPut(key, bmp)
        Log.d(TAG, "抽帧封面已生成: $key")
        return bmp
    }

    /** 强制重抽：清掉该 key 的内存+磁盘缓存（封面错了能救） */
    @Synchronized
    fun invalidate(key: String, cacheDir: File) {
        mem.remove(key)
        val safeKey = key.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        File(File(cacheDir, "covers_v3"), "$safeKey.jpg").delete()
    }

    /** 只读内存缓存（UI 侧先看有没有抽好的） */
    fun peek(key: String): Bitmap? = memGet(key)

    /** 写入内存缓存（抽帧完成后让各 UI 立即可见） */
    fun put(key: String, bmp: Bitmap) {
        memPut(key, bmp)
    }
}
