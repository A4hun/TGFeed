package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.MainActivity
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 同步读某条的续播进度（毫秒，无记录返回 0）：VideoPage 起播 seekTo 用，走纯内存缓存，可在任意线程调用 */
internal fun FeedViewModel.getProgressImpl(key: String): Long = progressMap[key] ?: 0L

/**
 * 播放进度落盘（VideoPage 失焦/销毁时由 FeedScreen 回调）：
 *  - posMs < 5 秒：看了没几秒，不记（秒滑/误触不产生续播点）；
 *  - 已知时长且 posMs ≥ 时长的 97%：基本看完，清掉续播点（下次从头播）；
 *  - 其余：记录进度，下次刷到/切回来从该位置续播。
 * 内存表同步更新，磁盘写在 IO 线程执行。
 */
internal fun FeedViewModel.saveProgressImpl(item: VideoItem, posMs: Long) {
    when {
        posMs < PROGRESS_MIN_MS -> return // 不足 5 秒不记
        item.durationSec > 0 && posMs >= item.durationSec * PROGRESS_DONE_PER_MILLE -> {
            // 基本看完：清掉续播点
            progressMap = progressMap - item.key
            viewModelScope.launch(Dispatchers.IO) {
                // R110：完播登记赛马底账——续播点一清"看完"证据即消失，
                // 完播未互动的条目此前永远评不上赛马晋级（最强正信号漏判）
                runCatching { ExposureTrackerHolder.current()?.noteCompleted(item.key) }
                // 落盘以执行时的内存现值为准：若期间用户又看了一段（同 key 新值入表），
                // 不能把新进度误删——内存里有就不执行 remove
                if (!progressMap.containsKey(item.key)) {
                    runCatching { store.removeProgress(item.key) }
                    // R122：删除墓碑记账——看完清掉的续播点随下次推送传播到云端，
                    // 对端 pull 不再被旧值复活（协议 V2，见 ProgressSync 类注释）
                    progressSync?.noteLocalDeletion(item.key)
                }
                // 跨设备接力：落盘之后同协程节流推（推送载荷含墓碑，删除也能同步到对端）。
                // 必须挂在写协程尾部——另起协程的话 Dispatchers.IO 多线程不保证先写后推，
                // 推上云的快照会慢一拍
                maybeAutoPushProgress()
            }
        }
        else -> {
            progressMap = progressMap + (item.key to posMs)
            viewModelScope.launch(Dispatchers.IO) {
                // 落盘以执行时的内存现值为准（可能比发起时更新——如 onStop 的
                // saveProgressSync 已写入更晚的位置），防"排队的旧值回滚新值"
                val latest = progressMap[item.key]
                if (latest != null) runCatching { store.saveProgress(item.key, latest) }
                maybeAutoPushProgress() // 同上：先写后推，同协程保序
            }
        }
    }
}

/**
 * 同步落盘版进度保存（MainActivity.onStop / 离开 Feed 的路径用）：
 * 与 [saveProgress] 同一记录语义（<5s 不记 / ≥97% 清除），但磁盘写在调用线程
 * 当场完成——进程随时可能被杀，等不起 viewModelScope 的异步落盘。
 * 小文件（tmp+rename，KB 级），onStop 主线程调用耗时毫秒级。
 * 不触发云端推送（网络操作不能上主线程；下次 saveProgress 的节流推会带上）。
 */
internal fun FeedViewModel.saveProgressSyncImpl(item: VideoItem, posMs: Long) {
    when {
        posMs < PROGRESS_MIN_MS -> return
        item.durationSec > 0 && posMs >= item.durationSec * PROGRESS_DONE_PER_MILLE -> {
            progressMap = progressMap - item.key
            runCatching { store.removeProgress(item.key) }
            // R122：删除墓碑同步记账（同步小文件写，与 removeProgress 同纪律，
            // 进程随后被杀也不能丢——否则下次 pull 云端旧值复活该条目）
            progressSync?.noteLocalDeletion(item.key)
            // R110：完播登记赛马底账（同 saveProgress 口径；本函数在 onStop 主线程跑，
            // 登记甩 IO 协程——进程真死丢失这一次标记可接受，绝不在主线程写盘）
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { ExposureTrackerHolder.current()?.noteCompleted(item.key) }
            }
        }
        else -> {
            progressMap = progressMap + (item.key to posMs)
            runCatching { store.saveProgress(item.key, posMs) }
        }
    }
}

/**
 * 记忆播放 · 会话内恢复定位：把 Feed 恢复到上次浏览的条目（条目位置记忆）。
 *
 * 仅会话内有效：pendingResumeKey 只从带本进程会话标记的 feed_resume.json 读入，
 * 杀进程重开后标记不匹配 → 不恢复（用户口径：跨进程"接着看"走播放历史）；
 * 同进程内 VM 重建（页面重建等场景）标记不变，恢复照常。
 * 切设置/分屏/下载页再返回不走本路径（VM 存活，initialPage + 切源守卫已保持位置）。
 *
 * 只在"VM 新建后的首屏"生效一次（[positionRestored] 闸；切源/刷新会置位，
 * 防止旧源位置误恢复到新源列表）。列表内容语义：
 *  - 首屏列表已含该 key（同源内容未变）：定位 _currentIndex 并通知 UI 滚动；
 *  - 不含（聚合源重拉后内容已变/已看过滤剔除）：从观看历史取条目本体
 *    （history.json 自 G4 起存完整 VideoItem），向 TDLib 换发本会话 fileId 后
 *    插到列表头部、定位到 0——"从上次那条接着看"优先于"从新鲜首条开始"；
 *  - 历史里也没有（被淘汰/从未记录）：放弃恢复，保持自然首条。
 * 片内进度由 watch_progress 既有链路负责（VideoPage 起播 seek getProgress）。
 *
 * 调用点：首屏 loadMore 收尾（主路径）+ init 预载协程末尾（磁盘慢于网络的补试）。
 * 挂起函数（复活 fileId 走网关），须在协程里调用。
 */
internal suspend fun FeedViewModel.tryRestorePosition() {
    if (positionRestored || !resumeReady) return
    if (_items.value.isEmpty()) return // 列表还没到，等下一次调用
    positionRestored = true
    val key = pendingResumeKey ?: return
    pendingResumeKey = null
    val idx = _items.value.indexOfFirst { it.key == key }
    if (idx >= 0) {
        _currentIndex.value = idx
        _resumeScroll.tryEmit(key)
        return
    }
    // 列表里没有：历史本体复活插头部（复活失败/无历史 → 放弃，不打扰自然首条）
    val body = runCatching {
        store.loadHistoryItems().firstOrNull { it.key == key }
    }.getOrNull() ?: return
    val real = gateway as? TdLibGateway
    val revived = if (real != null) {
        // 换发本会话 fileId（fileId 是会话级，进程重启即失效）；失败回落原本体——
        // 旧 fileId 起播时走 ensurePlayable 既有报错链，不在这里放大处理
        runCatching { reviveOne(body, real) }.getOrNull() ?: body
    } else {
        body // Mock 直链条目永久有效，无需换发
    }
    // 类型对齐当前媒体 tab（修"视频 tab 首条变图文/音频"）：本路径直插 _items 头部，
    // 绕过了 loadMore 的 pass 过滤链——上次停留在图文/音频条目时（同进程 VM 重建后
    // 回默认视频 tab），该条目会被插进视频 tab 的列表头。类型不符即放弃恢复，
    // 保持自然首条（positionRestored 已置位，本判定只生效一次，语义不受损）。
    val mediaTab = _mediaTypeTab.value
    if (mediaTab != -1 && revived.mediaType != mediaTab) return
    _items.update { list ->
        if (list.any { it.key == revived.key }) list else listOf(revived) + list
    }
    _currentIndex.value = 0
    _resumeScroll.tryEmit(revived.key)
}

/**
 * 分屏"全屏单播"定位兜底：格子内容来自分屏自持源（gridFeedSource，游标独立推进、
 * 跨进出分屏存活），一般不在 Feed 的 [_items] 里——R33 起两列表基本不重合，
 * Feed 侧按 key 定位必然落空（实测 idx=-1，跳转静默失效）。
 * 这里在跳转发起前把目标条目插进列表（紧跟当前停留位之后：跳转后下滑接着原列表刷、
 * 上滑回到离开 Feed 时的那条）；已存在则原样不动。
 * 条目是分屏会话内刚拉到的活条目（fileId/playUri 本会话有效），无需复活换发。
 * 刻意不动 mediaTypeTab/筛选（setMediaTypeTab 会 refresh 清空列表，把刚插入的条目冲掉）；
 * 分页器不按 tab 过滤，插入的非当前 tab 类型条目照常可见可播，下次刷新自然归位。
 */
internal fun FeedViewModel.offerJumpTargetImpl(item: VideoItem) {
    _items.update { list ->
        if (list.any { it.key == item.key }) {
            list
        } else {
            val at = (_currentIndex.value + 1).coerceIn(0, list.size)
            list.toMutableList().apply { add(at, item) }
        }
    }
}

// ---------------- 跨设备续播云端同步（Saved Messages 中转；core/feed/ProgressSync） ----------------

/**
 * 进度变化后的自动推送（跨设备接力主链路）：saveProgress 落盘后触发，
 * [AUTO_PUSH_THROTTLE_MS]（30s）节流，别每条进度都推；
 * 总闸 progressSyncEnabled 关着直接返回，不产生任何网络请求。
 * 注意 ProgressSync 内部另有 60s 硬限流，两层叠加后真实推送间隔 ≥60s——
 * 30s 这层是先把 VM→同步层的触发频次压下来，失败重试风暴由同步层限流兜底。
 */
internal fun FeedViewModel.maybeAutoPushProgress() {
    if (!prefs.progressSyncEnabled) return
    val now = System.currentTimeMillis()
    if (now - lastAutoPushAtMs < AUTO_PUSH_THROTTLE_MS) return
    lastAutoPushAtMs = now
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { progressSync?.pushProgress() }
    }
}

/**
 * 拉取云端进度并合入本地 + 重建内存进度表（init 自动拉 / 开关打开即时拉 / 手动拉共用）。
 * 合入后必须重建 progressMap——store.saveProgress 只更新磁盘与 store 缓存，
 * 本类的 progressMap 是独立副本，不重建则 getProgress 读不到新值。
 * 返回合入条数；无云端消息/失败一律静默为 0（同步是锦上添花，绝不影响主流程）。
 */
internal suspend fun FeedViewModel.pullProgressIntoMemory(): Int {
    val sync = progressSync ?: return 0 // Mock 演示模式无云端
    val merged = runCatching { sync.pullProgress() }.getOrDefault(0)
    progressMap = runCatching { store.loadProgressMap() }.getOrDefault(progressMap)
    return merged
}

/**
 * 推送本机观看进度到 Telegram「收藏夹」（Saved Messages），供本账号其他设备拉取。
 * 总闸：AppPrefs.progressSyncEnabled（默认开）——关着直接返回，不产生任何网络请求。
 * 60s 限流在 ProgressSync 内部；任何失败静默（同步是锦上添花，绝不影响主流程）。
 * 调用时机：设置页"立即同步"按钮（手动）；自动链路走 [maybeAutoPushProgress]（30s 节流）。
 */
internal fun FeedViewModel.syncProgressPushImpl() {
    if (!prefs.progressSyncEnabled) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { progressSync?.pushProgress() }
        // R300：手动「立即同步」顺带推四域脏状态（总闸在 pushAll 内自查）
        runCatching { multiSync?.pushAll() }
    }
}

/**
 * 从「收藏夹」拉取云端观看进度并合入本地，完成后经 [onResult] 回 Toast 文案
 * （回调在 viewModelScope 协程里发出，UI 直接 Toast 即可）。
 * 开关关闭时不发请求、回引导文案；任何失败静默收敛为"没有更新"类文案，不暴露内部错误。
 */
internal fun FeedViewModel.syncProgressPullImpl(onResult: (String) -> Unit) {
    if (!prefs.progressSyncEnabled) {
        onResult("请先在设置中开启「跨设备续播」")
        return
    }
    if (progressSync == null) {
        onResult("演示模式不支持云端同步")
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val merged = pullProgressIntoMemory()
        // R300：同一次「立即同步」顺带拉四域状态（收藏/稍后/追更+水位/屏蔽）
        val states = if (prefs.cloudSyncEnabled) {
            runCatching { pullStateIntoMemory() }.getOrDefault(0)
        } else 0
        onResult(
            when {
                merged > 0 && states > 0 -> "已同步 $merged 条观看进度、$states 项收藏/订阅/屏蔽状态"
                merged > 0 -> "已同步 $merged 条观看进度"
                states > 0 -> "已同步 $states 项收藏/订阅/屏蔽状态"
                else -> "云端没有更新的内容"
            }
        )
    }
}
