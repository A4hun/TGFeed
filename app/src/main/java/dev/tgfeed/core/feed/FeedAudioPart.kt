package dev.tgfeed.core.feed

import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---------------- 音频在播状态（迷你播放条 / 播放队列数据源） ----------------

internal fun FeedViewModel.reportAudioPlayingImpl(item: VideoItem, playing: Boolean) {
    _nowPlayingAudio.value = item
    _audioPlaying.value = playing
}

/** 清空在播音频（播完/共享播放器停清时；迷你条随之消失，媒体会话退占位底座） */
internal fun FeedViewModel.clearNowPlayingAudioImpl() {
    _nowPlayingAudio.value = null
    _audioPlaying.value = false
}

/**
 * 队列跳播：把目标立为当前音频（迷你条/队列高亮即时切换）。
 * pager 跳转由 FeedScreen 接（scrollToPage 到目标条 index）；
 * 目标音频卡激活后自动接管共享播放器开播，并经 [reportAudioPlaying] 校准真实播放态。
 */
internal fun FeedViewModel.jumpToAudioImpl(item: VideoItem) {
    _nowPlayingAudio.value = item
    _audioPlaying.value = true
}

// ---------------- GAPS3 #8：分屏画布记忆恢复辅助 ----------------

/**
 * 对一批"脱离 store 列表"的条目（分屏画布快照 grid_canvas.json 还原出来的）做
 * fileId 复活体检，单条规则与 [reviveStoredItems] 内部的 reviveOne 同口径
 * （http/asset 直链与本地仍在的文件跳过，其余按 fileUniqueId 走 GetRemoteFile 换发）。
 * 返回新列表：无需复活或复活失败的条目原样保留（失败条目起播走 ensurePlayable 报错链）。
 * Mock 网关无远端文件概念，原样返回。挂起函数，请在 IO 语境调用。
 */
internal suspend fun FeedViewModel.reviveDetachedItemsImpl(items: List<VideoItem>): List<VideoItem> {
    val real = gateway as? TdLibGateway ?: return items
    return items.map { reviveOne(it, real) ?: it }
}

/**
 * 无封面条目主动拉视频开头抽帧（用户要求）：无 minithumbnail 且无高清封面时，
 * 部分下载视频头 2MB → 抽 1 秒处的帧 → 进 CoverStore（各 UI 封面位自动换上）。
 * MP4 的 moov 在尾部的文件会抽不到（faststart 未开），失败静默不碍事。
 * R74：fileId 先经 [TdLibGateway.resolveCoverFrameFileId] 验身/按消息反解换发——
 * 落盘条目的会话级旧 id 可能撞上同号异文件，直接抽帧会把别的视频画面错存成
 * 该条目的封面（磁盘缓存按条目 key 持久化，错一次永久错）。
 */
internal fun FeedViewModel.fetchCoverFrameImpl(item: VideoItem) {
    if (item.coverBytes != null || item.coverFileId >= 0) return // 有封面通道就不用
    if (dev.tgfeed.ui.components.CoverStore.contains(item.key)) return // 已有抽帧封面
    val real = gateway as? TdLibGateway ?: return
    if (item.fileId < 0) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val fid = real.resolveCoverFrameFileId(item) ?: return@runCatching
            val path = real.downloadPartialCover(fid) ?: return@runCatching
            val bmp = dev.tgfeed.core.playback.CoverExtractor.getOrExtract(item.key, path, app.cacheDir)
            if (bmp != null) {
                // R155：走上限保护入口（旧直写无界表，长会话泄漏）
                dev.tgfeed.ui.components.CoverStore.put(
                    item.key,
                    bmp.asImageBitmap(),
                )
            }
        }
    }
}
