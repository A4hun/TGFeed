package dev.tgfeed.core.feed

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.store.DownloadRecord
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载到系统相册 `Movies/TGFeed/`（媒体库可见）。
 * API 29+ 走 MediaStore 两段式（RELATIVE_PATH + IS_PENDING），无需任何存储权限；
 * API 26–28 退化到公共 Movies 目录直写 + 媒体扫描（需要 WRITE_EXTERNAL_STORAGE，
 * Manifest 未声明时会失败并按 [DownloadStatus.Failed] 回报）。
 * 状态照常回报 Running / Done(展示路径) / Failed。
 */
internal fun FeedViewModel.downloadImpl(item: VideoItem) {
    if (_downloads.value[item.key] is DownloadStatus.Running) return
    _downloads.value = _downloads.value + (item.key to DownloadStatus.Running())
    viewModelScope.launch(Dispatchers.IO) {
        // 进度轮询协程：Running 期间每秒查一次 TDLib 文件进度并写回 _downloads。
        // 仅对有真实 TDLib fileId 的条目启动；Mock（fileId=-1）无文件可查，跳过。
        //
        // 与 ensurePlayable 的关系（不冲突）：
        // 未完成下载时 ensurePlayable 返回流服务器 URL，saveToGallery 经
        // HttpURLConnection 从流服务器整段拉取；流服务器内部用 DownloadFile 分段
        // 拉取，同样会推高 TDLib 该文件的 local.downloadedSize——所以这里轮询
        // GetFile 得到的进度对"流式 URL 整段复制"这条路径依然成立。
        val poller = if (item.fileId >= 0) {
            launch(Dispatchers.IO) {
                while (true) {
                    val p = gatewayDownloadProgress(item.fileId)
                    // 只在仍处于 Running 时写回，避免覆盖终态（Done/Failed）；
                    // 状态已迁走说明下载流程已收尾，轮询随之退出
                    if (_downloads.value[item.key] !is DownloadStatus.Running) break
                    _downloads.value =
                        _downloads.value + (item.key to DownloadStatus.Running(p))
                    delay(PROGRESS_POLL_MS)
                }
            }
        } else {
            null
        }
        val result = runCatching {
            val src = gateway.ensurePlayable(item)
            saveToGallery(item, src)
        }
        poller?.cancel() // 完成/失败时停轮询，随后写入终态
        // 下载成功即登记到 downloads.json（下载管理页数据源），内存列表同步置顶；
        // 同 key 重复下载时 store.addDownload 去重置顶，内存侧保持同序
        result.onSuccess { path ->
            // 互动信号入权重：下载成功 +BOOST_DOWNLOAD（CES ×4，"深度保存"按分享级计；
            // 删除下载记录不回退，量小可接受）
            addInteractionBoost(item.chatId, BOOST_DOWNLOAD)
            interactionStats.recordInteraction("download", item.chatId) // 指标闭环：互动率分子
            noteSessionSignal(item.chatId, SESSION_SIGNAL_STRONG_POS) // P1 #4：下载计入连续强正
            val record = DownloadRecord(
                key = item.key,
                // R228：titleLine() 共享件——parsed_title（逐条解析标题）优先，
                // NULL 回退 caption 首行（旧口径行为不变）
                title = item.titleLine()
                    .ifBlank { item.channelTitle },
                channelTitle = item.channelTitle,
                path = path,
                ts = System.currentTimeMillis(),
                sizeBytes = item.sizeBytes,
                chatId = item.chatId,
                messageId = item.messageId,
                durationSec = item.durationSec,
                width = item.width,
                height = item.height,
                mediaType = item.mediaType, // 图/音记录带类型，下载页点播放才能分派到正确的卡
            )
            _downloadRecords.value =
                listOf(record) + _downloadRecords.value.filterNot { it.key == record.key }
            runCatching { store.addDownload(record) }
                .onFailure { _error.value = "下载记录保存失败：${it.message}" }
        }
        _downloads.value = _downloads.value + (item.key to result.fold(
            onSuccess = { DownloadStatus.Done(it) },
            onFailure = { DownloadStatus.Failed(it.message ?: "下载失败") },
        ))
    }
}

/**
 * 查询下载进度（0..1；-1 = 未知/失败，UI 不显示百分比）。
 * 网关扩展方法不进 TdGateway 接口（项目惯例），按实现类型分派。
 */
internal suspend fun FeedViewModel.gatewayDownloadProgress(fileId: Int): Float = when (gateway) {
    is TdLibGateway -> gateway.downloadProgress(fileId)
    is MockTdGateway -> gateway.downloadProgress(fileId)
    else -> -1f
}

// 「清理 TDLib 本地缓存」的 cleanupStorageImpl 已迁往 FeedStoragePart.kt（R196：
// 旧实现裸调 OptimizeStorage 全 -1 参数 ≈ 无清理语义；新实现走 StorageGovernor
// 整轮分级清理并回带释放量。VM 公共 API fun cleanupStorage 委托不变，调用方无感）。

// ---------------- 下载实现 ----------------

/**
 * 按媒体类型分流落相册（G1 修复：旧实现恒用 .mp4 + Video 集合，
 * 图片/音频在 API 29+ 必被 MediaStore 校验拒绝）：
 * 图片 → Images 集合（Pictures/TGFeed，.jpg），
 * 音频 → Audio 集合（Music/TGFeed，.mp3/.m4a/.ogg 按 mimeType），
 * 视频照旧 Video 集合（Movies/TGFeed，.mp4）。
 */
internal fun FeedViewModel.saveToGallery(item: VideoItem, src: String): String {
    val name = "tg_${item.chatId}_${item.messageId}${extensionFor(item)}"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        insertIntoMediaStore(item, src, name)
    } else {
        legacyCopyToPublicDir(item, src, name)
    }
}

/** 扩展名按媒体类型 + mimeType 决定：图片默认 .jpg；音频 .mp3/.m4a/.ogg；视频恒 .mp4 */
internal fun FeedViewModel.extensionFor(item: VideoItem): String = when (item.mediaType) {
    1 -> when (item.mimeType.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        else -> ".jpg" // image/jpeg 及未知图片类型一律按 .jpg
    }
    2 -> when (item.mimeType.lowercase()) {
        "audio/mp4", "audio/x-m4a", "audio/aac" -> ".m4a"
        "audio/ogg", "application/ogg" -> ".ogg"
        else -> ".mp3" // audio/mpeg 及未知音频类型一律按 .mp3
    }
    else -> ".mp4"
}

/** 兜底 MIME：item.mimeType 为空时按媒体类型补给 MediaStore */
internal fun FeedViewModel.defaultMime(mediaType: Int): String = when (mediaType) {
    1 -> "image/jpeg"
    2 -> "audio/mpeg"
    else -> "video/mp4"
}

/** API 29+：MediaStore 两段式写入公共相册，返回展示用相对路径 */
internal fun FeedViewModel.insertIntoMediaStore(item: VideoItem, src: String, name: String): String {
    val resolver = app.contentResolver
    // 集合与相对根目录随媒体类型分派：图片 → Pictures，音频 → Music，视频 → Movies
    val (collection, rootDir) = when (item.mediaType) {
        1 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            Environment.DIRECTORY_PICTURES
        2 -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            Environment.DIRECTORY_MUSIC
        else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            Environment.DIRECTORY_MOVIES
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType.ifBlank { defaultMime(item.mediaType) })
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$rootDir/$ALBUM_NAME")
        put(MediaStore.MediaColumns.IS_PENDING, 1) // 挂起态：写入期间其他 App 不可见
    }
    val uri = resolver.insert(collection, values) ?: error("MediaStore 插入失败")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            openSource(src).use { input -> input.copyTo(out) }
        } ?: error("无法打开相册输出流")
        // 写完解除挂起，相册立即可见
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) } // 失败时清掉半成品记录
        throw e
    }
    return "$rootDir/$ALBUM_NAME/$name"
}

/** API 26–28：直接写公共目录（图片 Pictures / 音频 Music / 视频 Movies）并触发媒体扫描（无权限时抛异常由上层回报 Failed） */
@Suppress("DEPRECATION")
internal fun FeedViewModel.legacyCopyToPublicDir(item: VideoItem, src: String, name: String): String {
    val rootDir = when (item.mediaType) {
        1 -> Environment.DIRECTORY_PICTURES
        2 -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_MOVIES
    }
    val dir = File(Environment.getExternalStoragePublicDirectory(rootDir), ALBUM_NAME)
    dir.mkdirs()
    val dst = File(dir, name)
    openSource(src).use { input -> dst.outputStream().use { input.copyTo(it) } }
    // 扫描 MIME 随条目，避免图片被登记成 video/mp4 进不了系统图库
    MediaScannerConnection.scanFile(
        app, arrayOf(dst.absolutePath),
        arrayOf(item.mimeType.ifBlank { defaultMime(item.mediaType) }), null,
    )
    return dst.absolutePath
}

/**
 * 按来源类型打开输入流：
 *  - TDLib：本地文件路径
 *  - Mock：asset:/// 资产 或 http(s) 直链
 */
internal fun FeedViewModel.openSource(src: String): InputStream = when {
    src.startsWith("asset:///") -> app.assets.open(src.removePrefix("asset:///"))
    src.startsWith("http://") || src.startsWith("https://") ->
        (URL(src).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }.inputStream
    else -> File(src).inputStream()
}

/**
 * 删除一条下载：删记录 + 尽力删本地文件。
 * 路径形态兼容：API 29+ 存的是 "Movies/TGFeed/xxx" 展示相对路径（拼外部存储根还原绝对路径；
 * R360 更名前的存量旧记录为 "Movies/TGFed/xxx"——还原是纯拼接、零前缀假设，新旧两代路径同解，旧文件零迁移零搬家），
 * API 26–28 存的是绝对路径；删完触发媒体扫描清掉 MediaStore 残留行（尽力而为）。
 * 顺手把操作栏的 Done 状态清掉，让该条目回到"可下载"。
 */
internal fun FeedViewModel.removeDownloadImpl(key: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val removed = runCatching { store.removeDownload(key) }.getOrNull() ?: return@launch
        _downloadRecords.value = _downloadRecords.value.filterNot { it.key == key }
        deleteDownloadedFile(removed.path)
        if (_downloads.value[key] is DownloadStatus.Done) {
            _downloads.value = _downloads.value - key
        }
    }
}

/**
 * 批量删除下载（GAPS3 批量操作）：内存列表一次性过滤（UI 只重组一次）+ 逐条删本地文件。
 * 落盘取舍：LocalStore 本轮冻结、无批量写口，仍逐条走 store.removeDownload 落盘
 * （与"全部删除"逐条同款取舍，量小可接受）；以 store 返回的被删记录为准删文件，
 * 中途单条失败只跳过该条、不中断整批；本来就没有的 key 静默忽略。
 * 顺手把这些 key 的 Done 态一次清掉，让它们回到"可下载"。
 */
internal fun FeedViewModel.removeDownloadsImpl(keys: Set<String>) {
    if (keys.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val removedPaths = keys.mapNotNull { k ->
            runCatching { store.removeDownload(k) }.getOrNull()?.path
        }
        _downloadRecords.value = _downloadRecords.value.filterNot { it.key in keys }
        removedPaths.forEach { deleteDownloadedFile(it) }
        // 操作栏 Done 态一次清理（逐条改会触发多次重组）
        val doneKeys = keys.filter { _downloads.value[it] is DownloadStatus.Done }
        if (doneKeys.isNotEmpty()) _downloads.value = _downloads.value - doneKeys.toSet()
    }
}

/**
 * 尽力删本地文件 + 触发媒体扫描清 MediaStore 残留行（removeDownload/removeDownloads 共用）。
 * 路径形态兼容：API 29+ 存的是 "Movies/TGFeed/xxx" 展示相对路径（拼外部存储根还原绝对路径；
 * R360 更名前的存量旧记录为 "Movies/TGFed/xxx"——还原是纯拼接、零前缀假设，新旧两代路径同解，旧文件零迁移零搬家），
 * API 26–28 存的是绝对路径；文件已不存在或删除失败均静默（尽力而为）。
 */
internal fun FeedViewModel.deleteDownloadedFile(path: String) {
    runCatching {
        val raw = File(path)
        val target = if (raw.isAbsolute) {
            raw
        } else {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStorageDirectory(), path)
        }
        if (target.isFile && target.delete()) {
            // 文件已删：扫一次让媒体库同步抹掉该条目（对已不存在的文件是移除语义）
            MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), null, null)
        }
    }
}
