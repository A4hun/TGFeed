package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- R299 B3 漫画模式：频道内「话」导航 + 断点续读 ----------------
// （FeedViewModel.kt 本体在 R292 在飞面，本任务零触碰——全部以 part 扩展件落形）

/**
 * 「话」归组纯函数（JVM 单测锚定）：同频道图片条目（messageId 新→旧序输入）按
 * albumId 归话——连续同 albumId(≠0) 成员合成一话；散图（albumId=0）单条一话。
 * 漫画频道天然「频道=作品、消息=话」结构（design §3.2）。
 */
internal object ComicChapterPolicy {

    /** 条目流 → 话列表（输入序保持；同一相册的成员必须连续，跨页拼接由调用方保证） */
    fun chaptersOf(items: List<VideoItem>): List<List<VideoItem>> {
        val out = ArrayList<List<VideoItem>>()
        var curAlbum = 0L
        var cur = ArrayList<VideoItem>()
        fun flush() {
            if (cur.isNotEmpty()) {
                out += cur.toList()
                cur = ArrayList()
            }
        }
        for (it in items) {
            if (it.albumId != 0L && it.albumId == curAlbum) {
                cur += it
            } else {
                flush()
                cur += it
                curAlbum = it.albumId
            }
        }
        flush()
        return out
    }

    /** 锚所在话下标：albumId≠0 按相册归并身份找；散图按 key 找。找不到=-1 */
    fun chapterIndexOf(chapters: List<List<VideoItem>>, anchor: VideoItem): Int {
        if (anchor.albumId != 0L) {
            val i = chapters.indexOfFirst { ch -> ch.any { it.albumId == anchor.albumId } }
            if (i >= 0) return i
        }
        return chapters.indexOfFirst { ch -> ch.any { it.key == anchor.key } }
    }

    /**
     * 邻话择取（dir=+1 下一话=更新时间更早的列表头方向；dir=-1 上一话=更旧）。
     * 输入 chapters 序 = messageId 新→旧（channelItemsPage 序）。越界=null（没有更多话）。
     */
    fun neighborOf(chapters: List<List<VideoItem>>, anchor: VideoItem, dir: Int): List<VideoItem>? {
        val idx = chapterIndexOf(chapters, anchor)
        if (idx < 0) return null
        return chapters.getOrNull(if (dir > 0) idx - 1 else idx + 1)
    }
}

/**
 * 上一话/下一话查询（查看器章导航）：深索引 channelItemsPage（idx_items_chat_msg
 * 前缀导航，毫秒级，R212 口径）按 100 条/页走查同频道图片条目，albumId 归话后
 * 取锚话邻话。走查上限 [MAX_WALK_PAGES] 页防呆（找不到/越界=null → UI Toast
 * 「没有更多话了」）。索引未建/未覆盖该频道 → null（查看器章导航自然不渲染入口）。
 */
internal suspend fun FeedViewModel.comicChapterNeighborImpl(
    pages: List<VideoItem>,
    dir: Int,
): List<VideoItem>? {
    val anchor = pages.firstOrNull() ?: return null
    return withContext(Dispatchers.IO) {
        var offset = 0
        val collected = ArrayList<VideoItem>()
        var result: List<VideoItem>? = null
        var walked = 0
        while (walked < MAX_WALK_PAGES) {
            val batch = runCatching {
                deepIndex.channelItemsPage(anchor.chatId, WALK_PAGE_SIZE, offset)
            }.getOrDefault(emptyList())
            if (batch.isEmpty()) break
            walked++
            offset += batch.size
            collected += batch.filter { it.mediaType == 1 }
            val chapters = ComicChapterPolicy.chaptersOf(collected)
            val anchorIdx = ComicChapterPolicy.chapterIndexOf(chapters, anchor)
            if (anchorIdx >= 0) {
                val neighborIdx = if (dir > 0) anchorIdx - 1 else anchorIdx + 1
                if (neighborIdx in chapters.indices) {
                    result = chapters[neighborIdx]
                    break
                }
                // dir=+1（下一话=更新）：锚话已是走查窗口最新话——索引里没有更新的话
                //（窗口从最新起走），直接判定没有；dir=-1 继续走查更旧页
                if (dir > 0) break
            }
            if (batch.size < WALK_PAGE_SIZE) break // 到底
        }
        result
    }
}

private const val WALK_PAGE_SIZE = 100
private const val MAX_WALK_PAGES = 5

// ---------------- R299 B3 条漫断点续读（progress 链复用，key="comic:"+item.key，px 语义） ----------------

internal fun comicProgressKey(item: VideoItem): String = "comic:" + item.key

/** 读断点（px；无记录=0=从头读）。走 VM 内存表（零盘读，UI 每页组合调用） */
fun FeedViewModel.comicProgressOf(item: VideoItem): Long = progressMap[comicProgressKey(item)] ?: 0L

/**
 * 记断点：内存表同步更新 + IO 落盘（0=读回顶部清断点——「读完归零」语义与
 * 视频完播清续播点同族）。复用 watch_progress 表（MAX_WATCH_PROGRESS 截尾纪律继承）。
 */
fun FeedViewModel.saveComicProgress(item: VideoItem, px: Long) {
    val key = comicProgressKey(item)
    if (px <= 0L) {
        if (!progressMap.containsKey(key)) return // 本来就无断点：省一次落盘
        progressMap = progressMap - key
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.removeProgress(key) } }
    } else {
        progressMap = progressMap + (key to px)
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.saveProgress(key, px) } }
    }
}
