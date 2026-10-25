package dev.tgfeed.core.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R299 B1 听单（音频队列一等化）纯模型：up-next 语义的可编辑持久队列。
 *
 * 口径（design-audio-photo.md §2.1 终审裁决）：
 *  - 「加入听单」入尾（攒着连续听）；「下一首播放」插队首；
 *  - 当前条播完 → 队首出列开播（播过即出队，播客口径），队列空回落 Feed 连播现状；
 *  - [lastPlayed] 记最近一次出列/跳播的条目，支撑锁屏「上一首」=重播刚播完那条
 *    （不插队、不动队首）；
 *  - 同 key 幂等：加入已在单=忽略；插队已在单=提首；上限 [MAX_SIZE] 截尾。
 *
 * 纯 Kotlin 零 Android 依赖（JVM 单测直测）；持久化/播放仲裁见 [ListenQueueController]。
 * 全部方法非线程安全，调用方（controller）约定主线程串行。
 */
class ListenQueueModel {

    /** 队列本体：首=下一首要播的。对外只读视图，变更只能走本类方法。 */
    var items: List<VideoItem> = emptyList()
        private set

    /** 最近一次出列/跳播的条目（锁屏「上一首」的重播对象）；null=本会话还没播过队列条目 */
    var lastPlayed: VideoItem? = null
        private set

    /** 整单替换（冷启动从磁盘恢复的唯一入口） */
    fun replaceAll(restored: List<VideoItem>) {
        items = restored.take(MAX_SIZE)
    }

    /** 加入听单（队尾）；已在单=幂等忽略（返回 false 供 UI Toast 分流） */
    fun enqueue(item: VideoItem): Boolean {
        if (items.any { it.key == item.key }) return false
        items = (items + item).take(MAX_SIZE)
        return true
    }

    /** 下一首播放（插队首）；已在单=提到队首（返回 true，语义=会作为下一首） */
    fun playNext(item: VideoItem): Boolean {
        items = (listOf(item) + items.filterNot { it.key == item.key }).take(MAX_SIZE)
        return true
    }

    /** 从听单移除一条（按 key）；本来就没有返回 false（省一次落盘，同 removeProgress 语义） */
    fun remove(key: String): Boolean {
        val newList = items.filterNot { it.key == key }
        if (newList.size == items.size) return false
        items = newList
        return true
    }

    /** 清空听单（不动 lastPlayed——「上一首」仍可重播刚播完的那条） */
    fun clear() {
        items = emptyList()
    }

    /**
     * 出列开播（onEnded 连播 / 锁屏下一首 / 听单点跳共用）：队首移除并记 lastPlayed。
     * 队列空返回 null（调用方回落各自现状：Feed 连播/锁屏翻页）。
     */
    fun popHead(): VideoItem? {
        val head = items.firstOrNull() ?: return null
        items = items.drop(1)
        lastPlayed = head
        return head
    }

    /** 锁屏「上一首」：重播刚播完的条目，不动队列本体；没播过返回 null */
    fun replayLast(): VideoItem? = lastPlayed

    companion object {
        /** 听单上限（design §6 挂账行：超 500 截尾，防持久化膨胀） */
        const val MAX_SIZE = 500
    }
}

/**
 * R299 B1 听单控制器（进程级，AppContainer 持有）——「队列推进权从 UI 层收到
 * 播放器层」的落点（design §2.2 后台连续听的核心）：
 *
 *  - 挂载：[AppContainer.buildSharedAudioPlayer] 的 .also 里 [attachTo]——监听器
 *    随播放器建造注册，先于一切 UI 层监听（FeedScreen/AudioCard 的 listener 都是
 *    后挂的，ENDED 仲裁顺序确定性=本控制器先跑）。
 *  - ENDED 仲裁唯一收口（FeedScreen 的 ENDED 分支删除改挂这里）：
 *    loopOne（[loopOneProvider]，FeedScreen 注册）→ 归零重播；
 *    队列非空 → 出列连播（IO 协程 ensurePlayable 换地址，主线程装填开播）；
 *    队列空 → [onQueueEmptyEnded]（FeedScreen 注册=现状清在播+退媒体会话，
 *    未注册=纯后台场景兜底 notifyExternalActive(null)）。
 *  - 后台矩阵兼容：后台连播不需要自带闸——音频在后台「还在播」本身就是
 *    R180/R189/R158 关档矩阵放行后的状态（闸拦截=暂停=根本不会有 ENDED），
 *    控制器只在真实播完时推进，天然服从「后台行为=暂停」总开关。
 *  - 地址解析失败（换发失败/网络断）：跳过该条续探下一条，连探 [MAX_CONSECUTIVE_FAIL]
 *    次仍败即停（防全军覆没时死循环烧配额）。
 *  - 预取：开播一条后在 IO 协程预解队首地址进小缓存，曲间衔接零等待。
 *
 * 线程约定：exoPlayer 操作与模型变更全在主线程（监听器回调本就在主线程）；
 * 持久化与地址解析走内部 mainScope 切的 IO 协程。
 */
class ListenQueueController(
    private val store: LocalStore,
    private val gatewayProvider: () -> TdGateway?,
) {
    private val model = ListenQueueModel()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _queue = MutableStateFlow<List<VideoItem>>(emptyList())
    /** 听单实况（UI 订阅：队列 sheet/迷你条计数角标） */
    val queue: StateFlow<List<VideoItem>> = _queue

    /** 在播队列条目的 key（队列连播/跳播开播的；Feed 卡起播的不经此——迷你条高亮判据） */
    @Volatile
    var playingKey: String? = null
        private set

    /**
     * 在播队列条目本体（锁屏/通知元数据的队列侧来源——AudioBackgroundService
     * currentItem() 优先读它，纯后台期 FeedScreen 的 currentItemProvider 已注销）。
     */
    @Volatile
    var nowPlayingItem: VideoItem? = null
        private set

    /**
     * 最近一次 ENDED 被本控制器「队列连播认领」的条目 mediaId（=item.key）。
     * AudioCard.onEnded 的 Feed 连播让位判据：与卡片监听器的执行顺序无关——
     * 控制器挂得早先跑，认领后置位；卡片后跑读到即让位（不跳 Feed 下一条）。
     */
    @Volatile
    var lastEndedClaimKey: String? = null
        private set

    // ---- 外部接线（FeedScreen 注册；null=纯后台/未接线场景的语义兜底） ----

    /** 循环单条开关提供者（FeedScreen 的 loopOne 态）；null=false（后台不循环单条） */
    var loopOneProvider: (() -> Boolean)? = null

    /** 队列空时播完的回落（FeedScreen 注册=现状：退会话+清在播；AudioCard 的 Feed 连播照旧） */
    var onQueueEmptyEnded: (() -> Unit)? = null

    /** 在播条目变更上抛（FeedScreen 注册：写 vm._nowPlayingAudio 驱动迷你条/锁屏元数据） */
    var nowPlayingSink: ((VideoItem?) -> Unit)? = null

    private var player: ExoPlayer? = null

    /** 开播地址小缓存（预取产物：key→已解析 URI，先进先出上限 4） */
    private val resolvedUriCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4
    }

    @Volatile
    private var consecutiveFail = 0

    init {
        // 冷启动恢复持久听单（IO 读盘；恢复完成前队列为空，连播回落现状——等价旧行为）
        mainScope.launch(Dispatchers.IO) {
            val restored = runCatching { store.loadListenQueue() }.getOrDefault(emptyList())
            if (restored.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    model.replaceAll(restored)
                    publish()
                }
            }
        }
    }

    /** 挂到进程级共享音频播放器（AppContainer 建造时唯一调用点；幂等） */
    fun attachTo(player: ExoPlayer) {
        if (this.player === player) return
        this.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) arbitrateEnded(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 卡片接管（用户刷到音频卡开播非队列条目）：清在播队列标记，
                // 迷你条高亮回归卡片上报口径；队列本体不动（听单还在原地等）
                val id = mediaItem?.mediaId
                if (id != null && id != playingKey) {
                    playingKey = null
                    nowPlayingItem = null
                }
            }
        })
    }

    // ---------------- 队列编辑（UI 操作入口；全部主线程调用，幂等） ----------------

    /** 加入听单（队尾）；已在单=false（UI Toast「已在听单」） */
    fun enqueue(item: VideoItem): Boolean {
        assertMain()
        val changed = model.enqueue(item)
        if (changed) afterMutation()
        return changed
    }

    /** 下一首播放（插队首） */
    fun playNext(item: VideoItem) {
        assertMain()
        model.playNext(item)
        afterMutation()
    }

    /** 从听单移除（按 key） */
    fun remove(key: String) {
        assertMain()
        if (model.remove(key)) afterMutation()
    }

    /** 清空听单 */
    fun clear() {
        assertMain()
        if (model.items.isEmpty()) return
        model.clear()
        afterMutation()
    }

    /** 听单点跳播：该条出列立即开播（该条正由播放器持有且在播=点重复行，不重播） */
    fun jumpPlay(item: VideoItem) {
        assertMain()
        if (playingKey == item.key) return
        val p = player
        if (p != null && p.currentMediaItem?.mediaId == item.key && p.playbackState != Player.STATE_ENDED) return
        // 点跳=把该条从队列摘出开播（播客口径=点谁谁先听，其余顺序不动）；
        // 借 remove+playNext+popHead 通道记 lastPlayed（与连播出列同一语义）
        model.remove(item.key)
        model.playNext(item)
        startFromHead()
    }

    /** 当前听单快照（存播放列表等只读消费） */
    fun snapshot(): List<VideoItem> = model.items

    // ---------------- 锁屏/耳机/车机 skip（PlayerPool.sharedSkip*Handler 接线目标） ----------------

    /** 「下一首」：队列非空即队列前进（返回 true 已消费）；空=false（调用方回落 Feed 翻页） */
    fun skipNext(): Boolean {
        if (model.items.isEmpty()) return false
        mainHandler.post {
            if (model.items.isNotEmpty()) startFromHead()
        }
        return true
    }

    /** 「上一首」：重播刚播完的条目（不动队列）；没播过=false（调用方回落 Feed 翻回） */
    fun skipPrev(): Boolean {
        val last = model.replayLast() ?: return false
        mainHandler.post { playResolved(last) }
        return true
    }

    // ---------------- ENDED 仲裁（唯一收口） ----------------

    private fun arbitrateEnded(player: ExoPlayer) {
        val endedKey = player.currentMediaItem?.mediaId
        // 每次播完先清认领标记——不认领的分支（循环/队空）必须把上一轮的
        // 陈旧认领清掉，否则 AudioCard.onEnded 会误读旧 key 让位
        lastEndedClaimKey = null
        // 循环单条：现状口径（原地归零重播、不清在播态、队列不动）
        if (loopOneProvider?.invoke() == true) {
            player.seekTo(0)
            player.play()
            return
        }
        if (model.items.isEmpty()) {
            playingKey = null
            nowPlayingItem = null
            // 队列空：回落现状（FeedScreen 注册的清在播+退会话；未注册兜底退会话）
            onQueueEmptyEnded?.invoke() ?: PlayerPool.notifyExternalActive(null)
            return
        }
        // 队列非空：认领这次播完——Feed 连播让位（AudioCard.onEnded 读 lastEndedClaimKey）
        lastEndedClaimKey = endedKey
        startFromHead()
    }

    /** 队首出列开播（出列记 lastPlayed 在模型内完成）；失败连探下一条，连败三次收手 */
    private fun startFromHead() {
        val next = model.popHead() ?: return
        afterMutation()
        playResolved(next)
    }

    /** 解析并开播一条：先查预取缓存，未中走 ensurePlayable（IO），主线程装填 */
    private fun playResolved(item: VideoItem) {
        val p = player ?: return
        playingKey = item.key
        nowPlayingItem = item
        nowPlayingSink?.invoke(item)
        PlayerPool.notifyExternalActive(p)
        val cached = synchronized(resolvedUriCache) { resolvedUriCache.remove(item.key) }
        if (cached != null) {
            loadAndPlay(p, item, cached)
            return
        }
        mainScope.launch(Dispatchers.IO) {
            val uri = resolveUri(item)
            withContext(Dispatchers.Main) {
                // 装填前校验这条还是「该播的」——解析期间用户可能已手动切歌/清空队列
                if (playingKey == item.key && uri != null) {
                    consecutiveFail = 0
                    loadAndPlay(p, item, uri)
                } else if (playingKey == item.key) {
                    onPlayFailed(item)
                }
            }
        }
    }

    private fun loadAndPlay(player: ExoPlayer, item: VideoItem, uri: String) {
        runCatching {
            player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.key).build())
            player.prepare()
            player.playWhenReady = true
            consecutiveFail = 0
            android.util.Log.i(
                "R299",
                "听单开播 key=…${item.key.takeLast(12)} 余量=${model.items.size}",
            )
            prefetchHead()
        }.onFailure { onPlayFailed(item) }
    }

    private fun onPlayFailed(item: VideoItem) {
        consecutiveFail += 1
        android.util.Log.w("R299", "听单条目解析失败跳过（连败 $consecutiveFail）key=…${item.key.takeLast(12)}")
        if (consecutiveFail >= MAX_CONSECUTIVE_FAIL) {
            android.util.Log.w("R299", "听单连败封顶停播（防死循环烧配额）")
            playingKey = null
            nowPlayingItem = null
            nowPlayingSink?.invoke(null)
            return
        }
        startFromHead()
    }

    /** IO 线程地址解析：自带直链先用；否则 ensurePlayable 现取（Mock/占位网关异常即失败） */
    private suspend fun resolveUri(item: VideoItem): String? {
        item.playUri?.takeIf { it.startsWith("http") || it.startsWith("asset:///") }?.let { return it }
        val gateway = gatewayProvider() ?: return null
        return runCatching { gateway.ensurePlayable(item) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 开播后预解队首地址进缓存（曲间衔接零等待；解析失败静默——开播时还有兜底重试） */
    private fun prefetchHead() {
        val head = model.items.firstOrNull() ?: return
        if (synchronized(resolvedUriCache) { resolvedUriCache.containsKey(head.key) }) return
        mainScope.launch(Dispatchers.IO) {
            val uri = resolveUri(head) ?: return@launch
            synchronized(resolvedUriCache) { resolvedUriCache[head.key] = uri }
        }
    }

    /** 队列变更统一收尾：刷 StateFlow + IO 落盘 */
    private fun afterMutation() {
        publish()
        val snapshot = model.items
        mainScope.launch(Dispatchers.IO) {
            runCatching { store.saveListenQueue(snapshot) }
        }
    }

    private fun publish() {
        _queue.value = model.items
    }

    private fun assertMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "ListenQueueController 仅主线程调用" }
    }

    companion object {
        /** 连续解析失败封顶（防全坏队列死循环） */
        const val MAX_CONSECUTIVE_FAIL = 3
    }
}
