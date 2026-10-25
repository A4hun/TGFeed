package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.MainActivity
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.PlaylistMeta
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---------------- 播放列表（LocalStore playlists.json + playlist_<id>.json 持久化） ----------------

/** PlaylistMeta → UI 模型投影（条数取 itemKeys.size：免解析 playlist_<id>.json 大文件） */
internal fun PlaylistMeta.toPlaylist() = Playlist(id, name, itemKeys.size, updatedAt)

/**
 * 新建片单：trim 后空名忽略；立即持久化（元数据 + 条目文件）。
 * id 只需本机唯一且文件名安全（playlist_<id>.json）：时间戳 + 随机段，无外部输入字符。
 * [firstItem] 非空时建单即加入为第一条（"加入片单"选择器里的"新建并加入"路径）——
 * 建单与加条目在同一个 IO 协程内串行完成，避免拆成两次 launch 在 IO 线程池里乱序
 * （add 先于 create 落盘时会读不到新片单而静默丢弃）。
 */
internal fun FeedViewModel.createPlaylistImpl(name: String, firstItem: VideoItem? = null) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val now = System.currentTimeMillis()
    val id = "pl_${now}_${(1000..9999).random()}"
    val items = if (firstItem != null) listOf(firstItem) else emptyList()
    val meta = PlaylistMeta(id, trimmed, items.map { it.key }, now, now)
    viewModelScope.launch(Dispatchers.IO) {
        // 先重读磁盘再合并：与可能并发的增删互不覆盖（store 读走内存缓存，开销可忽略）
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

/** 删除片单：元数据与条目文件一并删；正在刷该片单（一次性源）时当前列表不受影响（下次切源自然失效） */
internal fun FeedViewModel.deletePlaylistImpl(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadPlaylists() }.getOrDefault(playlistMetas)
        val newList = current.filterNot { it.id == id }
        if (newList.size == current.size) return@launch // 本来就没有
        playlistMetas = newList
        _playlists.value = newList.map { it.toPlaylist() }
        runCatching {
            store.savePlaylists(newList)
            store.deletePlaylistItems(id)
        }.onFailure { _error.value = "片单删除失败：${it.message}" }
    }
}

/** 重命名片单：空名忽略；不推进 updatedAt（改名不算内容更新，卡片"最新更新时间"只看内容增删） */
internal fun FeedViewModel.renamePlaylistImpl(id: String, name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadPlaylists() }.getOrDefault(playlistMetas)
        if (current.none { it.id == id }) return@launch // 片单已不存在
        val newList = current.map { if (it.id == id) it.copy(name = trimmed) else it }
        playlistMetas = newList
        _playlists.value = newList.map { it.toPlaylist() }
        runCatching { store.savePlaylists(newList) }
            .onFailure { _error.value = "片单重命名失败：${it.message}" }
    }
}

/**
 * 把条目加入片单末尾（末尾 = 整单连播顺序的队尾）；同 key 去重：已在片单则忽略，
 * 不重复占位。条目完整落盘（序列化规则同收藏），元数据的 itemKeys 镜像与
 * updatedAt 同事务推进。片单不存在时静默忽略。
 */
internal fun FeedViewModel.addToPlaylistImpl(playlistId: String, item: VideoItem) {
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadPlaylists() }.getOrDefault(playlistMetas)
        val meta = current.firstOrNull { it.id == playlistId } ?: return@launch
        val items = runCatching { store.loadPlaylistItems(playlistId) }.getOrDefault(emptyList())
        if (items.any { it.key == item.key }) return@launch // 已在片单：幂等
        val newItems = items + item
        val newList = current.map {
            if (it.id == playlistId) {
                it.copy(itemKeys = newItems.map { v -> v.key }, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        playlistMetas = newList
        _playlists.value = newList.map { p -> p.toPlaylist() }
        runCatching {
            store.savePlaylistItems(playlistId, newItems)
            store.savePlaylists(newList)
        }.onFailure { _error.value = "加入片单失败：${it.message}" }
    }
}

/** 从片单移除一条（按 item.key）；本来就没有则不碰磁盘（省一次落盘，同 removeProgress 语义） */
internal fun FeedViewModel.removeFromPlaylistImpl(playlistId: String, key: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadPlaylists() }.getOrDefault(playlistMetas)
        if (current.none { it.id == playlistId }) return@launch
        val items = runCatching { store.loadPlaylistItems(playlistId) }.getOrDefault(emptyList())
        val newItems = items.filterNot { it.key == key }
        if (newItems.size == items.size) return@launch // 无此条目
        val newList = current.map {
            if (it.id == playlistId) {
                it.copy(itemKeys = newItems.map { v -> v.key }, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        playlistMetas = newList
        _playlists.value = newList.map { p -> p.toPlaylist() }
        runCatching {
            store.savePlaylistItems(playlistId, newItems)
            store.savePlaylists(newList)
        }.onFailure { _error.value = "片单更新失败：${it.message}" }
    }
}

/**
 * 整单连播：切到一次性源放该片单全部条目（条目顺序即连播顺序，播完自然到底）。
 * 一次性源模式与搜索/下载回放一致：结果一页给完、nextCursor 恒 null、reset 可重放。
 * 空片单静默忽略（UI 侧已用条数守卫 + Toast，这里兜底）。
 * 内容分区 tab 直接归"全部"（不走 setMediaTypeTab——它会先对旧源 refresh 一次，浪费一轮拉取）：
 * 片单里的图文/音频不该被 Feed tab 滤掉（与 MainActivity 搜索回放的口径一致）。
 */
internal fun FeedViewModel.openPlaylistImpl(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val items = runCatching { store.loadPlaylistItems(id) }.getOrDefault(emptyList())
        if (items.isEmpty()) return@launch
        // 回主线程切源：结算当前观看计时、清空列表、从片单第一条起播（与 openBlindBox 同一线程语义）
        launch(Dispatchers.Main) {
            _mediaTypeTab.value = -1
            switchSource(object : FeedSource {
                override val request = FeedRequest()
                internal var served = false
                override suspend fun nextPage(limit: Int): dev.tgfeed.core.td.FeedPage {
                    if (served) return dev.tgfeed.core.td.FeedPage(emptyList(), null)
                    served = true
                    return dev.tgfeed.core.td.FeedPage(items, nextCursor = null)
                }
                override fun reset() { served = false }
            })
        }
    }
}
