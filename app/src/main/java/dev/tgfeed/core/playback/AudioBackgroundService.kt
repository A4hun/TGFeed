package dev.tgfeed.core.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.tgfeed.App
import dev.tgfeed.MainActivity
import dev.tgfeed.R
import dev.tgfeed.core.feed.titleLine
import dev.tgfeed.core.td.VideoItem
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 后台音频服务（"听抖音"模式）：镜像 PlayerPool 的当前"主"播放器。
 *
 * 相比占位实现的升级点：
 * - MediaSession 不再绑死占位 player：PlayerPool.acquire/releaseAllExcept 时经
 *   [PlayerPool.sharedActivePlayerListener] 广播主位，这里用 [MediaSession.setPlayer]
 *   把会话热切到真实播放器的 [MirroredPlayer] 转发壳上；无活跃播放时退回占位底座，
 *   会话结构始终保持完整（锁屏/Wear/车机随时可连）。
 * - 通知/锁屏元数据来自真实条目：标题=caption 首行截断（空则频道名）、艺术家=频道名、
 *   封面=coverBytes。真实播放器的 MediaItem 只有裸 URI（VideoPage 不带元数据，本任务不改它），
 *   元数据在转发壳这层注入——会话与平台锁屏读到的都是壳覆写的 getMediaMetadata。
 * - 通知本体用 media3 的 [DefaultMediaNotificationProvider] 生成（播放/暂停/下一曲按钮、
 *   封面大图自动带）；但**不采纳** media3 "暂停即退前台"的默认前台策略（见 onUpdateNotification），
 *   本服务的存在意义就是保活，无论播放/暂停都保持前台挂牌。
 * - 控制映射：暂停/继续透传给真实播放器（音频焦点由 PlayerPool 侧 handleAudioFocus 管）；
 *   "下一条"无原生语义（真实播放器单条媒体），映射成 [PlayerPool.sharedSkipNextHandler]，
 *   由 FeedScreen 主代理接线成翻页。
 *
 * 对外仍仅 [start] / [stop] 两个静态入口，调用时机由 MainActivity 侧决定。
 */
@OptIn(UnstableApi::class)
class AudioBackgroundService : MediaSessionService() {

    // 占位播放器：无活跃播放时的会话底座（永不进媒体），保证会话/通知结构不塌
    private var placeholderPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var notificationProvider: DefaultMediaNotificationProvider? = null

    // 当前镜像壳与其真实播放器（ForwardingPlayer 不透出 delegate，自存一份做同引用去重）
    private var mirroredPlayer: MirroredPlayer? = null
    private var mirroredDelegate: ExoPlayer? = null

    // 池回调可能踩在任意线程上（池方法 @Synchronized，谁调用就用谁的线程）；
    // MediaSession.setPlayer 必须在主线程，统一 post 回主 Looper
    private val mainHandler = Handler(Looper.getMainLooper())

    // 挂到 PlayerPool 伴生的订阅 lambda（onDestroy 摘订阅时按引用比对，防误摘后来者）
    private val poolListener: (ExoPlayer?) -> Unit = { player ->
        mainHandler.post { bindActivePlayer(player) }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val placeholder = ExoPlayer.Builder(this).build()
        placeholderPlayer = placeholder
        // media3 默认通知 provider：复用本服务既有渠道与通知 ID（1001 恰与 media3 默认 ID 相同）
        val provider = DefaultMediaNotificationProvider(
            this,
            { NOTIFICATION_ID }, // NotificationIdProvider：固定 1001
            CHANNEL_ID,
            R.string.app_name, // 渠道名资源（渠道已被 ensureNotificationChannel 建好，不会重复创建）
        )
        // TODO 沿用原实现的取舍：res 下暂无单色状态栏图标，借系统图标兜底
        provider.setSmallIcon(android.R.drawable.ic_media_play)
        notificationProvider = provider
        setMediaNotificationProvider(provider)
        val session = MediaSession.Builder(this, placeholder)
            // 点通知主体回 MainActivity（SINGLE_TOP 避免重复叠实例）
            .setSessionActivity(buildContentIntent())
            .build()
        mediaSession = session
        addSession(session) // 注册进 media3 通知管理器，播放状态/元数据变化自动驱动 onUpdateNotification
        // 订阅主播放器广播；服务可能起得比池晚，先按快照接管一次
        PlayerPool.sharedActivePlayerListener = poolListener
        bindActivePlayer(PlayerPool.lastActivePlayer)
    }

    /** MediaSessionService 契约：任何控制器（锁屏/耳机/Wear/车机）连接都给同一个镜像会话 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先交 media3 路由：通知按钮的 ACTION_MEDIA_BUTTON / 自定义动作 Intent 在这里进会话
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() 拉起后约 5 秒内必须挂牌：立即按当前会话出一次通知
        // （media3 自己的更新是异步 post 的，不等它，主动打一枪兜底）
        mediaSession?.let { onUpdateNotification(it, true) }
        return START_STICKY
    }

    /**
     * 接管 media3 的通知更新：默认实现会在暂停时把服务退出前台（退前台的后台服务随时可被杀），
     * 与本服务"后台保活"的职责冲突——这里无论播放/暂停都保持前台挂牌；
     * 通知内容仍由 media3 默认 provider 生成，标题/封面/播放暂停/下一曲按钮全带。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val provider = notificationProvider ?: return
        val notification = provider.createNotification(
            session,
            session.customLayout,
            ServiceMediaActionFactory(),
        ) { changed ->
            // 封面字节异步解码完成等后续变化：重发前台通知
            postForeground(changed)
        }
        postForeground(notification)
    }

    private fun postForeground(notification: MediaNotification) {
        // 防御：极端时序下（如服务被系统限速重启）startForeground 可能抛异常，宁可丢一次更新不崩服
        runCatching {
            ServiceCompat.startForeground(
                this,
                notification.notificationId,
                notification.notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 划卡清任务即停：只停服务摘通知；不碰 PlayerPool（进程随任务移除很快会被系统回收）
        stopSelf()
    }

    override fun onDestroy() {
        // 退前台摘通知 → 摘池订阅（身份比对防误摘别人的）→ 镜像壳脱离真实播放器 →
        // 先会话后播放器释放（media3 release 幂等，双保险安全）
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (PlayerPool.sharedActivePlayerListener === poolListener) {
            PlayerPool.sharedActivePlayerListener = null
        }
        mirroredPlayer?.detach()
        mirroredPlayer = null
        mirroredDelegate = null
        mediaSession?.release()
        mediaSession = null
        placeholderPlayer?.release()
        placeholderPlayer = null
        super.onDestroy()
    }

    /** 把会话绑到主播放器的镜像壳上；null = 当前无活跃播放，退回占位底座 */
    private fun bindActivePlayer(target: ExoPlayer?) {
        val session = mediaSession ?: return
        if (target == null) {
            mirroredPlayer?.detach()
            mirroredPlayer = null
            mirroredDelegate = null
            val placeholder = placeholderPlayer ?: return
            if (session.player !== placeholder) session.setPlayer(placeholder)
            return
        }
        // 同引用重复上报（翻页 acquire+releaseAllExcept 连发同一实例）直接跳过
        if (mirroredDelegate === target) return
        mirroredPlayer?.detach()
        mirroredDelegate = target
        val wrapper = MirroredPlayer(target)
        wrapper.updateMetadata(currentItem())
        mirroredPlayer = wrapper
        session.setPlayer(wrapper)
    }

    /** 元数据来源：R299 B1 听单连播在播条目优先（纯后台期 FeedScreen 已 dispose、
     *  currentItemProvider 够不到——队列推进权在播放器层，元数据同源）；
     *  否则 AppContainer.currentItemProvider（FeedScreen 注册的"当前条目"） */
    private fun currentItem(): VideoItem? =
        (application as? App)?.container?.let { c ->
            c.listenQueueController.nowPlayingItem ?: c.currentItemProvider?.invoke()
        }

    /**
     * 通知/锁屏元数据映射：
     *  - 视频/图文：标题=caption 首行截 60 字（空 caption 回落频道名）、艺术家=频道名；
     *  - 音频（mediaType=2，音乐播放器语义）：标题=Audio.title（空回落 caption 首行/频道名）、
     *    艺术家=Audio.performer 歌手（空回落频道名）；
     * 封面=coverBytes（minithumbnail，糊但即时，锁屏/通知都够用）；时长给上便于锁屏进度条显示。
     */
    private fun buildMetadata(item: VideoItem?): MediaMetadata {
        if (item == null) {
            return MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setArtist("后台音频播放中")
                .build()
        }
        // R228：titleLine() 共享件——parsed_title（逐条解析标题）优先，NULL 回退
        // caption 首行（旧口径行为不变；锁屏/通知标题随深索引逐条解析升级）
        val captionTitle = item.titleLine().takeIf { it.isNotEmpty() }?.take(60)
        val isAudio = item.mediaType == 2
        return MediaMetadata.Builder()
            .setTitle(
                when {
                    isAudio -> item.audioTitle.ifBlank { captionTitle.orEmpty() }
                        .ifBlank { item.channelTitle }
                    captionTitle.isNullOrBlank() -> item.channelTitle
                    else -> captionTitle
                },
            )
            .setArtist(if (isAudio) item.audioPerformer.ifBlank { item.channelTitle } else item.channelTitle)
            .setArtworkData(item.coverBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setDurationMs(item.durationSec.takeIf { it > 0 }?.times(1000L))
            .build()
    }

    private fun buildContentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureNotificationChannel() {
        // minSdk 26，渠道 API 原生可用；IMPORTANCE_LOW 保证常驻通知静默不打扰
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台音频播放", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /**
     * 真实播放器的转发壳：除元数据与"下一条"外全部透传。
     * - getMediaMetadata/getCurrentMediaItem 覆写注入当前条目的标题/封面/时长；
     * - availableCommands 追加 COMMAND_SEEK_TO_NEXT——锁屏/通知/车机/Wear 的
     *   "下一首"按钮只在命令可用时才点亮；
     * - seekToNext/seekToNextMediaItem 映射成 PlayerPool 的"下一条"信号。
     */
    private inner class MirroredPlayer(delegate: Player) : ForwardingPlayer(delegate) {

        private var metadata: MediaMetadata = MediaMetadata.EMPTY

        // ForwardingPlayer 把 listener 全挂到 delegate 上；元数据是这层覆写的，
        // delegate 永远不会替我们发 onMediaMetadataChanged——自留一份监听表用于补发
        private val listeners = CopyOnWriteArraySet<Player.Listener>()

        // 同一真实播放器换片（池实例复用）时刷新元数据；经 super 直挂 delegate，
        // 不进入自留监听表（它是内部件，不该收到自己补发的事件）
        private val transitionListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMetadata(currentItem())
            }
        }

        init {
            super.addListener(transitionListener)
        }

        /** 壳退役时把内部监听从真实播放器上摘掉（壳被换掉后 delegate 还活着，不能挂着死壳） */
        fun detach() {
            super.removeListener(transitionListener)
        }

        override fun addListener(listener: Player.Listener) {
            listeners.add(listener)
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners.remove(listener)
            super.removeListener(listener)
        }

        fun updateMetadata(item: VideoItem?) {
            metadata = buildMetadata(item)
            listeners.forEach { it.onMediaMetadataChanged(metadata) }
        }

        override fun getMediaMetadata(): MediaMetadata = metadata

        override fun getCurrentMediaItem(): MediaItem? =
            super.getCurrentMediaItem()
                ?.buildUpon()
                ?.setMediaMetadata(metadata)
                ?.build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                super.isCommandAvailable(command)

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                // R299 B1：锁屏/通知「上一首」点亮（队列化后自然成立——
                // 听单 lastPlayed 重播；未接线时按了无反应，同下一首既有口径）
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        override fun seekToNext() = skipToNext()

        override fun seekToNextMediaItem() = skipToNext()

        override fun seekToPrevious() = skipToPrev()

        override fun seekToPreviousMediaItem() = skipToPrev()

        private fun skipToNext() {
            // UI 未接线（onSkipNext 为空）时静默吞掉，锁屏按钮按了无反应但不报错
            PlayerPool.sharedSkipNextHandler?.invoke()
        }

        /** R299 B1：锁屏「上一首」→ 听单 lastPlayed 重播 / FeedScreen 接线期翻回 */
        private fun skipToPrev() {
            PlayerPool.sharedSkipPrevHandler?.invoke()
        }
    }

    /**
     * 通知按钮的动作工厂：media3 自带的 DefaultActionFactory 是包私有类，这里按它的行为
     * 复刻——ACTION_MEDIA_BUTTON + KeyEvent 发回本服务，由 MediaSessionService.onStartCommand
     * 统一路由进会话（会话再派发给当前镜像的播放器）。
     */
    private inner class ServiceMediaActionFactory : MediaNotification.ActionFactory {

        override fun createMediaAction(
            session: MediaSession,
            icon: IconCompat,
            title: CharSequence,
            keyCode: Int,
        ): NotificationCompat.Action =
            NotificationCompat.Action
                .Builder(icon, title, createMediaActionPendingIntent(session, keyCode.toLong()))
                .build()

        override fun createCustomAction(
            session: MediaSession,
            icon: IconCompat,
            title: CharSequence,
            customAction: String,
            extras: Bundle,
        ): NotificationCompat.Action =
            // 默认布局没有自定义按钮，此路径不会走到；兜底走播放/暂停按键通道
            createMediaAction(session, icon, title, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        override fun createCustomActionFromCustomCommandButton(
            session: MediaSession,
            button: androidx.media3.session.CommandButton,
        ): NotificationCompat.Action =
            // 同上，兜底；本服务不注册 customLayout，不会被调用
            createCustomAction(
                session,
                IconCompat.createWithResource(this@AudioBackgroundService, button.iconResId),
                button.displayName ?: "",
                button.sessionCommand?.customAction ?: "",
                button.sessionCommand?.customExtras ?: Bundle.EMPTY,
            )

        override fun createMediaActionPendingIntent(session: MediaSession, keyCode: Long): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(this@AudioBackgroundService, AudioBackgroundService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode.toInt()))
            return PendingIntent.getService(
                this@AudioBackgroundService,
                keyCode.toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "background_audio"
        private const val NOTIFICATION_ID = 1001
        // 保留：旧版通知"停止"按钮的返回动作，兼容已挂出的旧通知点击；新通知由 media3 管理无此按钮
        private const val ACTION_STOP = "dev.tgfeed.action.STOP_BACKGROUND_AUDIO"

        /**
         * 进入后台音频模式：拉起前台服务保活 + 镜像当前播放的媒体通知。
         * 重复调用幂等（onStartCommand 重挂牌覆盖更新）。
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioBackgroundService::class.java),
            )
        }

        /** 退出后台音频模式：停服务摘通知；服务未运行时为无操作。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioBackgroundService::class.java))
        }
    }
}
