package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.App
import dev.tgfeed.core.playback.ListenQueueController
import dev.tgfeed.core.store.PlaylistMeta
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ---------------- R299 B1 听单（音频队列一等化）· VM 侧代理 ----------------
// 控制器本体在 AppContainer 进程级（core/playback/ListenQueue.kt，ENDED 仲裁/
// 后台连播/锁屏上下曲全在播放器层）；本件只做 UI 侧代理与「存播放列表」的
// PlaylistMeta 底座衔接——FeedViewModel.kt 本件零改动（R292 在飞该文件，避让）。

/** 听单控制器（进程级；AppContainer 懒构造持有） */
internal val FeedViewModel.listenQueue: ListenQueueController
    get() = (app as App).container.listenQueueController

/** 听单实况（UI 订阅：队列 sheet 编辑段/迷你条「N 首」角标） */
val FeedViewModel.listenQueueItems: StateFlow<List<VideoItem>>
    get() = listenQueue.queue

/** 加入听单（队尾）；已在单=false（UI Toast 分流） */
fun FeedViewModel.enqueueListen(item: VideoItem): Boolean = listenQueue.enqueue(item)

/** 下一首播放（插队首） */
fun FeedViewModel.playNextInListen(item: VideoItem) = listenQueue.playNext(item)

/** 从听单移除（按 key） */
fun FeedViewModel.removeFromListen(key: String) = listenQueue.remove(key)

/** 清空听单 */
fun FeedViewModel.clearListenQueue() = listenQueue.clear()

/** 听单点跳播（该条出列立即开播） */
fun FeedViewModel.jumpListen(item: VideoItem) = listenQueue.jumpPlay(item)

/**
 * 整单存为播放列表（PlaylistMeta/playlists.json 底座现成，零新存储——design §2.1）：
 * 听单快照整单落为一个新片单（顺序即连播顺序）；空单/空名忽略。
 * 元数据与条目文件的写入口径与 [createPlaylistImpl] 完全一致（先重读磁盘再合并）。
 */
internal fun FeedViewModel.saveListenQueueAsPlaylistImpl(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val items = listenQueue.snapshot()
    if (items.isEmpty()) return
    val now = System.currentTimeMillis()
    val id = "pl_${now}_${(1000..9999).random()}"
    val meta = PlaylistMeta(id, trimmed, items.map { it.key }, now, now)
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadPlaylists() }.getOrDefault(playlistMetas)
        val newList = current + meta
        playlistMetas = newList
        _playlists.value = newList.map { it.toPlaylist() }
        runCatching {
            store.savePlaylists(newList)
            store.savePlaylistItems(id, items)
        }.onFailure { _error.value = "片单保存失败：${it.message}" }
    }
}
