package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

/**
 * R64 封面铸造登记：本会话 toVideoItem 发给 UI 的 coverFileId → 缩略图 remote.uniqueId
 *（空串=铸造时未取到 uniqueId，仅作会话内登记、验身时跳过比对）。
 * coverFileId 是会话级 id，跨进程落盘条目（R52 首屏快照/收藏 JSON/深索引物化）携带的
 * 旧 id 在本会话要么失效、要么撞上会话内重发的同号异文件——后者即"快照封面错图"根因
 * （R52 复活链只换发 fileId 不换发 coverFileId）。loadCoverBytes 只信本表登记在册
 * 且验身相符的 id，其余一律置失效转消息反解链。会话级内存表，不落盘、随网关重建。
 *
 * R74 撞号补牢：R64 版登记表是 fileId→uniqueId 单值，同号 id 被会话内重铸给别的
 * 条目时旧 uniqueId 被新值整体覆盖，随后旧落盘条目拿着撞号的 coverFileId 来取封面，
 * GetFile 验身对上的已是新文件的 uniqueId——闸形同虚设，错图直出（本次回归根因）。
 * 现为每个 id 登记"铸造时 uniqueId + 铸造条目 key 集"：验身①要求调用方条目 key
 * 落在该 id 的铸造 key 集内（撞号即 key 不符，直接转消息反解）；验身②保留
 * uniqueId 比对（同条目重铸但文件已变也能拦）。同一缩略图文件被相册多条消息
 * 共享时 uniqueId 相同、key 集累加，互不影响；id 被重铸给不同文件（uniqueId
 * 不同）则整条替换，旧条目从此只能走消息反解。
 */
internal class MintedCover(
    val uniqueId: String,
    val keys: MutableSet<String>,
)

/**
 * R170 封面字节读盘统一走 IO 调度器（榜单 #6 输入响应家族，类修单一出处）：
 * 旧实现 `java.io.File(path).readBytes()` 跑在**调用方语境**——视频页/音频卡的
 * produceState 协程挂在主调度器上（组合作用域），快甩翻页每页组合时磁盘读直接
 * 压进主线程（重负载页卡顿/翻页被吞的贡献者之一，同类见各 UI 卡的封面解码）。
 * 本文件 4 处读盘点（loadCoverBytes / loadAudioCoverBytes /
 * loadCoverBytesByMessage / readMsgCoverDisk）全部收敛到这里；isFile 校验一并
 * 进 IO。读盘失败返回 null（调用方按无封面回落，语义与旧 runCatching 口径一致）。
 */
internal suspend fun readCoverFileBytesOnIO(path: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val f = java.io.File(path)
            if (f.isFile) f.readBytes() else null
        }.getOrNull()
    }

/**
 * 封面 fileId 铸造登记（toVideoItem 各分支统一经此发给 UI）：登记 fileId→
 *（uniqueId + 条目 key）后返回 id；无缩略图返回 -1（与旧 `?.file?.id ?: -1` 语义一致）。
 */
internal fun TdLibGateway.mintCoverFileId(f: TdApi.File?, itemKey: String): Int {
    if (f == null) return -1
    val uid = f.remote?.uniqueId.orEmpty()
    coverFileMinted.compute(f.id) { _, old ->
        if (old != null && old.uniqueId == uid) {
            old.keys.add(itemKey) // 同文件同 id：相册共享/同条重铸，补登 key 即可
            old
        } else {
            // id 被重铸给不同文件：整条替换，旧铸造 key 随之作废
            MintedCover(uid, java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add(itemKey) })
        }
    }
    return f.id
}

/**
 * 按需加载高清封面：minithumbnail 只有几十像素糊成马赛克（用户实测反馈）。
 * 真缩略图是独立 File（coverFileId）：已下载直接读，没下载同步拉（小图秒回）。
 * W7：网络段补 15s 超时 + 全局并发闸（原无超时无闸，慢代理下会挂死/互抢）。
 * R64 错图闸：coverFileId 是会话级 id，跨进程落盘条目（首屏快照/收藏/深索引物化）
 * 携带的旧 id 在本会话要么失效、要么撞上会话内重发的同号异文件（错图根因）——
 * 只信本会话铸造登记（[coverFileMinted]，toVideoItem mintCoverFileId 记入）的 id，
 * 且下载前 GetFile 验身（remote.uniqueId 对得上铸造登记）；未登记/验身失败一律
 * 置失效转消息反解链（[loadCoverViaMessage]：GetMessage 本地库优先，拿回本会话
 * 真缩略图），宁可慢不可错。全链失败/无 coverFileId 返回 null（调用方回落兜底）。
 */
internal suspend fun TdLibGateway.loadCoverBytesImpl(item: VideoItem): ByteArray? {
    if (item.coverFileId < 0) return null
    // 铸造验身①：非本会话铸造的 id（跨进程落盘残留）、或撞号 id（本会话重铸给了
    // 别的条目，R74 前验身被覆盖语义架空）皆不可信，直接置失效转反解，
    // 连缓存都不查——缓存按 fileId 键，会话内同号异文件的旧条目照样会错图
    val mintedEntry = coverFileMinted[item.coverFileId]
    if (mintedEntry != null && item.key !in mintedEntry.keys) {
        // R74 实锤路径：id 在册但铸造方是别的条目——同号撞车，旧登记 uniqueId
        // 已被覆盖（R64 闸在此失效），必须按不可信处理
        Log.d(TAG, "loadCoverBytes: coverFileId=${item.coverFileId} 撞号（铸造方非本条目），置失效转消息反解 key=${item.key}")
        return loadCoverViaMessage(item)
    }
    val minted = mintedEntry
    if (minted == null) {
        Log.d(TAG, "loadCoverBytes: coverFileId=${item.coverFileId} 非本会话铸造，置失效转消息反解 key=${item.key}")
        return loadCoverViaMessage(item)
    }
    coverBytesCache[item.coverFileId]?.let { return it }
    // 铸造验身②：GetFile 是本地库直读（不占下载闸）——同号 id 被会话内重发给别的
    // 文件时 remote.uniqueId 对不上铸造登记；GetFile 失败同样按不可信处理
    val f0 = runCatching {
        awaitAuthorized()
        client.send(TdApi.GetFile(item.coverFileId))
    }.getOrNull()
    if (f0 == null || (minted.uniqueId.isNotEmpty() && f0.remote?.uniqueId.orEmpty() != minted.uniqueId)) {
        Log.d(TAG, "loadCoverBytes: coverFileId=${item.coverFileId} 验身失败（串号/已失效），转消息反解 key=${item.key}")
        return loadCoverViaMessage(item)
    }
    return runCatching {
        withTimeout(MSG_COVER_TIMEOUT_MS) {
            coverDownloadSem.withPermit {
                var f = f0
                if (!f.local.isDownloadingCompleted || f.local.path.isEmpty()) {
                    f = client.send(TdApi.DownloadFile(item.coverFileId, 32, 0, 0, true))
                }
                val path = f.local.path
                val bytes = if (path.isNotEmpty()) readCoverFileBytesOnIO(path) else null
                if (bytes != null) coverBytesCache[item.coverFileId] = bytes
                bytes
            }
        }
    }.getOrNull()
}

/**
 * 图片浏览链（W1+W2+W3）：为 mediaType=1 的条目同步下载"浏览档"
 *（[VideoItem.photoViewFileId]，≈1280px，0.2~0.8MB）并返回真实本地文件路径；
 * 浏览档缺失（老数据/深度索引条目）时回落最大档原图 fileId。
 *
 * 与 ensurePlayable 的根本区别（修"永远马赛克"，调研病灶#1）：图片解码走
 * BitmapFactory.decodeFile 必须拿到本地路径，而 ensurePlayable 在文件未下完时
 * 返回 http://127.0.0.1 流服务器地址，decodeFile 必败且无任何重试/完成通知
 * → 永久停在 minithumbnail。这里 DownloadFile(synchronous=true) 同步拉完再返回路径，
 * priority=32（W3：正在看的图不再排 priority=1 的队尾，与流分块同级），
 * 外加 15s 超时兜底（与封面链同口径）。
 * 路径原样返回、不过 playableUriOrStream：decodeFile 直接开文件，
 * 不受路径中 '#'/'?' 影响（那是 Uri.parse 的坑，java.io.File 免疫）。
 * 失败/超时返回 null（调用方保留 minithumbnail 占位，不崩链）。
 */
internal suspend fun TdLibGateway.loadPhotoViewPathImpl(item: VideoItem): String? {
    val fileId = if (item.photoViewFileId >= 0) item.photoViewFileId else item.fileId
    if (fileId < 0) return null
    return runCatching {
        awaitAuthorized()
        var f = client.send(TdApi.GetFile(fileId))
        if (!f.local.isDownloadingCompleted || f.local.path.isEmpty()) {
            f = withTimeout(MSG_COVER_TIMEOUT_MS) {
                client.send(TdApi.DownloadFile(fileId, 32, 0L, 0L, true))
            }
        }
        // EISDIR 防御：TDLib 偶发把目录路径标记为已完成，必须是真实文件（教训同 getChannelAvatar）
        f.local.path.takeIf { it.isNotEmpty() && java.io.File(it).isFile }
    }.onFailure {
        Log.w(TAG, "loadPhotoViewPath 失败 ${item.key}: ${it.message}")
    }.getOrNull()
}

/**
 * 按需加载音频专辑封面高清图（v6 音频卡重做配套）：
 * Audio.albumCoverMinithumbnail 只有几十像素，真封面是独立 File
 * （Audio.albumCoverThumbnail.file，fileId 已随 VideoItem.audioCoverFileId 带上）。
 * 复用 loadCoverBytes 的缓存模式（进程内 ConcurrentHashMap，按 fileId 缓存，
 * 与视频封面共用同一张表——TDLib fileId 全局唯一，天然无撞键）：
 * 已下载直接读，没下载同步拉（封面小图秒回）。
 * 失败/无封面返回 null（调用方回落 minithumbnail / 渐变音符占位）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * Audio{albumCoverThumbnail: Thumbnail}；Thumbnail{format, width, height, file: File}；
 * File{id: int, local: LocalFile{path: String, isDownloadingCompleted: boolean}}
 */
internal suspend fun TdLibGateway.loadAudioCoverBytesImpl(item: VideoItem): ByteArray? {
    if (item.audioCoverFileId < 0) return null
    // R74：与 loadCoverBytes 同一道铸造验身闸（audioCoverFileId 同经 mintCoverFileId
    // 登记）；未登记/撞号/验身失败一律转消息反解链（extractMessageCoverFile 对音频
    // 消息取的就是 albumCoverThumbnail，目标文件一致），杜绝同号异文件错图
    val mintedEntry = coverFileMinted[item.audioCoverFileId]
    if (mintedEntry != null && item.key !in mintedEntry.keys) {
        Log.d(TAG, "loadAudioCoverBytes: audioCoverFileId=${item.audioCoverFileId} 撞号（铸造方非本条目），置失效转消息反解 key=${item.key}")
        return loadCoverViaMessage(item)
    }
    val minted = mintedEntry
    if (minted == null) {
        Log.d(TAG, "loadAudioCoverBytes: audioCoverFileId=${item.audioCoverFileId} 非本会话铸造，置失效转消息反解 key=${item.key}")
        return loadCoverViaMessage(item)
    }
    coverBytesCache[item.audioCoverFileId]?.let { return it }
    val f0 = runCatching {
        awaitAuthorized()
        client.send(TdApi.GetFile(item.audioCoverFileId))
    }.getOrNull()
    if (f0 == null || (minted.uniqueId.isNotEmpty() && f0.remote?.uniqueId.orEmpty() != minted.uniqueId)) {
        Log.d(TAG, "loadAudioCoverBytes: audioCoverFileId=${item.audioCoverFileId} 验身失败（串号/已失效），转消息反解 key=${item.key}")
        return loadCoverViaMessage(item)
    }
    return runCatching {
        // W7：与 loadCoverBytes 同口径——15s 超时 + 全局并发闸
        withTimeout(MSG_COVER_TIMEOUT_MS) {
            coverDownloadSem.withPermit {
                var f = f0
                if (!f.local.isDownloadingCompleted || f.local.path.isEmpty()) {
                    f = client.send(TdApi.DownloadFile(item.audioCoverFileId, 32, 0, 0, true))
                }
                val path = f.local.path
                val bytes = if (path.isNotEmpty()) readCoverFileBytesOnIO(path) else null
                if (bytes != null) coverBytesCache[item.audioCoverFileId] = bytes
                bytes
            }
        }
    }.getOrNull()
}

/**
 * 按消息重新解析出当前会话的有效 fileId（复活旧条目的可靠路径）：
 * GetMessage(chatId, messageId) 拿回最新消息内容，从中提取当前媒体文件的 fileId。
 * 比 GetRemoteFile(uniqueId) 可靠——那个 API 收的是 remote.id 不是 uniqueId（货不对板的根因）。
 * 签名已 javap 实证：GetMessage(long, long) → Message
 */
internal suspend fun TdLibGateway.reviveFileIdByMessageImpl(chatId: Long, messageId: Long): Int? = runCatching {
    awaitAuthorized()
    val msg = client.send(TdApi.GetMessage(chatId, messageId))
    when (val c = msg.content) {
        is TdApi.MessageVideo -> c.video.video.id
        is TdApi.MessagePhoto -> c.photo.sizes.lastOrNull()?.photo?.id
        is TdApi.MessageAudio -> c.audio.audio.id
        is TdApi.MessageVoiceNote -> c.voiceNote.voice.id
        is TdApi.MessageDocument -> c.document.document.id
        else -> null
    }
}.getOrNull()

/**
 * R74 抽帧封面源 id 验身/换发：无缩略图视频"拉开头抽帧当封面"的入口闸。
 * item.fileId 是会话级 id，落盘条目（深索引/快照/收藏）携带的旧 id 在新会话
 * 可能已失效、也可能撞上同号异文件——直接抽帧会把别的视频画面错存成该条目的
 * 封面（CoverExtractor 磁盘缓存按条目 key 持久化，错一次永久错）。
 * 规则：GetFile（本地库直读）验 remote.uniqueId 与条目持久化的 fileUniqueId 相符
 * 才信原 id；不符/无凭据则按 chatId+messageId 反解换发本会话新 id
 * （与收藏复活同一口径）；全落空返回 null（宁可无封面，不可错封面）。
 */
internal suspend fun TdLibGateway.resolveCoverFrameFileIdImpl(item: VideoItem): Int? {
    if (item.fileId >= 0 && item.fileUniqueId.isNotEmpty()) {
        val f = runCatching {
            awaitAuthorized()
            client.send(TdApi.GetFile(item.fileId))
        }.getOrNull()
        if (f != null && f.remote?.uniqueId.orEmpty() == item.fileUniqueId) return item.fileId
        Log.d(TAG, "resolveCoverFrameFileId: fileId=${item.fileId} 验身不符，按消息反解换发 key=${item.key}")
    }
    if (item.messageId > 0) return reviveFileIdByMessage(item.chatId, item.messageId)
    return null
}

/**
 * 部分下载视频开头 2MB（封面抽帧用）：无缩略图的视频从原始文件抽帧当封面。
 * 返回本地路径（可能只有前 2MB）；不够或失败返回 null。
 * 调用方必须先经 [resolveCoverFrameFileId] 验身/换发，严禁直接喂落盘旧 id。
 * 签名已 javap 实证：DownloadFile(int fileId, int priority, long offset, long limit, boolean synchronous)
 */
internal suspend fun TdLibGateway.downloadPartialCoverImpl(fileId: Int): String? = runCatching {
    awaitAuthorized()
    // R76：抽帧前缀与泵分块同文件串行（见 videoDlMutexes），防互杀
    val f = videoDlMutex(fileId).withLock {
        client.send(TdApi.DownloadFile(fileId, 32, 0L, 2L * 1024 * 1024, true))
    }
    val p = f.local.path
    if (p.isNotEmpty() && java.io.File(p).isFile && f.local.downloadedSize > 100_000) p else null
}.getOrNull()

// ---------------- 按消息反解封面（深度索引条目封面补救） ----------------

/**
 * 深度索引落盘 JSON 不存 coverFileId/coverBytes（"封面不落盘"），物化出的条目
 * 封面链只能跌到"频道头像兜底"，同频道结果全显示同一张头像（用户看到的"复用同一张"）。
 * 本方法按 chatId+messageId 反解真封面：GetMessage 拿消息 → 提取真缩略图 File
 * （Video.thumbnail / Animation.thumbnail / Photo 尺寸档 / Audio.albumCoverThumbnail /
 *  Document.thumbnail，字段均已 javap 对 core-release.aar（TDLib 1.8.67）实证）→
 * DownloadFile 同步拉小文件 → 返回字节；消息没有真缩略图时回落其自带的
 * minithumbnail（内联字节，零下载，模糊但来源保真，胜过频道头像）。
 * item.coverFileId >= 0 的条目直接转 [loadCoverBytes]（复用既有路径与缓存；
 * R64 起直连链自带铸造验身闸，会话级旧 id 失效/串号会在其内部自动转本反解链）。
 *
 * 缓存/防重复下载（三层）：
 *  1. 内存层 [msgCoverMemCache]：key="chatId:messageId"，LRU 上限 [MSG_COVER_MEM_MAX] 条；
 *     空数组是"查过无封面"墓碑——无封面消息滚走再滚回不再重复 GetMessage；
 *  2. 进程层 [coverBytesCache]（与 loadCoverBytes/loadAudioCoverBytes 共用，按 fileId）：
 *     同一会话内多条消息共享同一缩略图文件时只下载一次；
 *  3. 磁盘层 cacheDir/msg_covers/：key=缩略图 remote.uniqueId（跨重启稳定、跨消息去重），
 *     拿不到 uniqueId 退化为 m<chatId>_<messageId>；超 [MSG_COVER_DISK_MAX] 个文件
 *     按 lastModified 最老先删到八成（封面可重建，删错无代价）。
 * 并发去重：[msgCoverMutexes] 按消息 key 加锁，同 key 并发只跑一份真实加载（双检后干活）。
 * 取消：全程挂起调用（client.send/withLock 都是取消点），调用方 produceState 取消即停；
 * 网络段外加 [MSG_COVER_TIMEOUT_MS] 超时兜底。任何一步失败返回 null（调用方回落频道头像）。
 */
internal suspend fun TdLibGateway.loadCoverBytesByMessageImpl(item: VideoItem): ByteArray? {
    // coverFileId 有效的条目走既有高清封面链（含其自带缓存），不重复造轮子。
    // R64 起直连链内置铸造验身：coverFileId 非本会话铸造/串号会在 loadCoverBytes 内部
    // 置失效并自动转消息反解（W6 图片条目老数据的退路由此对全类型生效），此处不再按
    // 媒体类型分流；直连链整体落空（返回 null）再兜底走一次消息反解，语义与旧版一致
    if (item.coverFileId >= 0) loadCoverBytes(item)?.let { return it }
    return loadCoverViaMessage(item)
}

/**
 * 消息反解封面核心（R64 从 [loadCoverBytesByMessage] 抽出，两处复用）：
 *  1. loadCoverBytesByMessage 兜底段——无 coverFileId / 直连链落空的条目；
 *  2. loadCoverBytes 错图闸——检出会话级 coverFileId 非本会话铸造/串号后的换发链。
 * 三层缓存、同 key 并发去重、15s 超时语义与原链一致（见上方头注释），不再赘述。
 */
internal suspend fun TdLibGateway.loadCoverViaMessage(item: VideoItem): ByteArray? {
    if (item.messageId <= 0L) return null
    val msgKey = "${item.chatId}:${item.messageId}"
    msgCoverMemGet(msgKey)?.let { hit -> return hit.takeIf { it.isNotEmpty() } } // 命中（空数组=墓碑→null）
    val mutex = msgCoverMutexes.getOrPut(msgKey) { Mutex() }
    return try {
        mutex.withLock {
            // 双检：等锁期间同 key 协程可能已把封面填进缓存
            msgCoverMemGet(msgKey)?.let { hit -> return@withLock hit.takeIf { it.isNotEmpty() } }
            val bytes = loadCoverBytesByMessageLocked(item, msgKey)
            // 只缓存成功结果；失败（超时/网络/消息已删）不缓存，下次重组可重试
            if (bytes != null) msgCoverMemPut(msgKey, bytes)
            bytes
        }
    } finally {
        // 仅当映射的还是本实例才移除（并发安全），防互斥锁表无限增长
        msgCoverMutexes.remove(msgKey, mutex)
    }
}

internal fun TdLibGateway.msgCoverMemGet(key: String): ByteArray? = synchronized(msgCoverMemCache) { msgCoverMemCache[key] }

internal fun TdLibGateway.msgCoverMemPut(key: String, bytes: ByteArray) {
    synchronized(msgCoverMemCache) { msgCoverMemCache[key] = bytes }
}

/** 真实加载（已持有同 key 互斥锁）：成功返回字节；明确无封面时自带写墓碑；失败返回 null 且不缓存 */
internal suspend fun TdLibGateway.loadCoverBytesByMessageLocked(item: VideoItem, msgKey: String): ByteArray? {
    return runCatching {
        awaitAuthorized()
        // GetMessage 命中 TDLib 本地消息库时几乎零开销；未命中才走网（超时兜底）
        val msg = withTimeout(MSG_COVER_TIMEOUT_MS) {
            client.send(TdApi.GetMessage(item.chatId, item.messageId))
        }
        val content = msg.content
        val thumbFile = extractMessageCoverFile(content)
        if (thumbFile == null) {
            // 消息没有任何真缩略图：回落内联 minithumbnail；连它也没有就写墓碑
            val mini = extractMessageMiniCover(content)
            msgCoverMemPut(msgKey, mini ?: MSG_COVER_NONE)
            return@runCatching mini
        }
        // 进程内按 fileId 去重（与 loadCoverBytes 共用表：同一缩略图文件只下载一次）
        coverBytesCache[thumbFile.id]?.let { return@runCatching it }
        // 磁盘层：key 优先 remote.uniqueId（跨重启稳定且跨消息去重），拿不到退化为消息维度
        val diskName = thumbFile.remote?.uniqueId?.takeIf { it.isNotBlank() }
            ?.let { MSG_COVER_NAME_REGEX.replace(it, "_") }
            ?: "m${item.chatId}_${item.messageId}"
        readMsgCoverDisk(diskName)?.let { bytes ->
            coverBytesCache[thumbFile.id] = bytes
            return@runCatching bytes
        }
        // 未下载完成才同步拉（缩略图小文件秒回）；TDLib 侧下载是独立线程，
        // 协程挂起在 send 上，produceState 取消即从挂起点退出。
        // W7：下载段并入封面全局并发闸（coverDownloadSem），防一屏格子互抢带宽
        var f = thumbFile
        if (!f.local.isDownloadingCompleted || f.local.path.isEmpty()) {
            f = withTimeout(MSG_COVER_TIMEOUT_MS) {
                coverDownloadSem.withPermit {
                    client.send(TdApi.DownloadFile(f.id, 32, 0L, 0L, true))
                }
            }
        }
        val path = f.local.path
        // EISDIR 防御：TDLib 偶发把目录路径标记为已完成，必须是真实文件（教训同 getChannelAvatar）
        // ——isFile 校验已并入 readCoverFileBytesOnIO（R170 主线程读盘剥离，同一出处）
        val bytes = if (path.isNotEmpty()) readCoverFileBytesOnIO(path) else null
        if (bytes != null && bytes.isNotEmpty()) {
            coverBytesCache[thumbFile.id] = bytes
            writeMsgCoverDisk(diskName, bytes)
            bytes
        } else {
            null
        }
    }.onFailure {
        Log.w(TAG, "loadCoverBytesByMessage 失败 ${item.key}: ${it.message}")
    }.getOrNull()
}

/**
 * 消息内容 → 真缩略图 File（字段均 javap 实证，core-release.aar TDLib 1.8.67）：
 * Video.thumbnail / Animation.thumbnail / Document.thumbnail 是 Thumbnail{file: File}；
 * Audio 取 albumCoverThumbnail；MessagePhoto 没有独立 thumbnail，
 * 从 Photo.sizes（PhotoSize{type: String, photo: File, width, height}）里挑≈320px 档。
 * 无缩略图概念的内容（文本/语音等）返回 null。
 */
internal fun TdLibGateway.extractMessageCoverFile(c: TdApi.MessageContent?): TdApi.File? = when (c) {
    is TdApi.MessageVideo -> c.video.thumbnail?.file
    is TdApi.MessageAnimation -> c.animation.thumbnail?.file
    is TdApi.MessagePhoto -> pickPhotoCoverSize(c.photo.sizes)?.photo
    is TdApi.MessageAudio -> c.audio.albumCoverThumbnail?.file
    is TdApi.MessageDocument -> c.document.thumbnail?.file
    else -> null
}

/** 消息内容 → 内联 minithumbnail 字节（真缩略图缺失时的零下载回落；模糊但来源保真） */
internal fun TdLibGateway.extractMessageMiniCover(c: TdApi.MessageContent?): ByteArray? = when (c) {
    is TdApi.MessageVideo -> c.video.minithumbnail?.data
    is TdApi.MessageAnimation -> c.animation.minithumbnail?.data
    is TdApi.MessagePhoto -> c.photo.minithumbnail?.data
    is TdApi.MessageAudio -> c.audio.albumCoverMinithumbnail?.data
    is TdApi.MessageDocument -> c.document.minithumbnail?.data
    else -> null
}

/**
 * 图片封面档挑选：优先 "m" 档（TG 预设≈320px 方盒，网格封面的清晰度/体积最平衡）；
 * 没有 "m" 取面积 ≥320×320 的最小档；全部更小则拿最大档（仍是该图自己的真身）。
 * PhotoSize{type: String, photo: File, width: int, height: int}（javap 实证）。
 */
internal fun TdLibGateway.pickPhotoCoverSize(sizes: Array<TdApi.PhotoSize>?): TdApi.PhotoSize? {
    if (sizes == null || sizes.isEmpty()) return null
    sizes.firstOrNull { it.type == "m" }?.let { return it }
    val asc = sizes.sortedBy { it.width.toLong() * it.height.toLong() }
    return asc.firstOrNull { it.width.toLong() * it.height.toLong() >= 320L * 320L } ?: asc.last()
}

/**
 * 图片浏览档挑选（W1，toVideoItem 图片分支登记 [VideoItem.photoViewFileId] 用）：
 * 优先 "y" 档（TG 预设≈1280px——全屏 Fit 浏览的清晰度/体积平衡点，配合 PhotoCard
 * 已有的屏宽×2 采样解码足够清晰）；没有 "y" 取 "x"（≈800px）；再无取最长边
 * ≤1280px 的最大档；全部更小则拿最大档（小图原图即浏览档，不再二次缩档）。
 * PhotoSize{type: String, photo: File, width: int, height: int}（javap 实证）。
 */
internal fun TdLibGateway.pickPhotoViewSize(sizes: Array<TdApi.PhotoSize>?): TdApi.PhotoSize? {
    if (sizes == null || sizes.isEmpty()) return null
    sizes.firstOrNull { it.type == "y" }?.let { return it }
    sizes.firstOrNull { it.type == "x" }?.let { return it }
    val asc = sizes.sortedBy { maxOf(it.width, it.height).toLong() }
    return asc.lastOrNull { maxOf(it.width, it.height) <= 1280 } ?: asc.last()
}

/** 读磁盘封面缓存：文件不存在/读失败/空文件一律 null（调用方继续走下载链）。
 *  R170 读盘走 IO 调度器（单一出处 readCoverFileBytesOnIO 同口径） */
internal suspend fun TdLibGateway.readMsgCoverDisk(name: String): ByteArray? =
    readCoverFileBytesOnIO(java.io.File(msgCoverDiskDir, name).absolutePath)
        ?.takeIf { it.isNotEmpty() }

/** 写磁盘封面缓存 + 简易 LRU 清理：超 [MSG_COVER_DISK_MAX] 个文件按最老先删到八成。
 *  R170 写盘+目录扫描同样走 IO 调度器（与读盘同一出处口径：组合协程在主调度器上） */
internal suspend fun TdLibGateway.writeMsgCoverDisk(name: String, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = msgCoverDiskDir
            java.io.File(dir, name).writeBytes(bytes)
            val files = dir.listFiles() ?: return@runCatching
            if (files.size > MSG_COVER_DISK_MAX) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - MSG_COVER_DISK_MAX * 4 / 5)
                    .forEach { it.delete() }
            }
        }
    }
}

// ---------------- 按消息反解封面常量（loadCoverBytesByMessage 配套，文件级私有） ----------------

/** 消息封面内存缓存上限（条）：单条缩略图 ≈30-60KB，256 条 ≈8-15MB；LRU 最久未用先淘汰 */
internal const val MSG_COVER_MEM_MAX = 256

/** 消息封面磁盘缓存上限（个文件）：超限按 lastModified 最老先删到八成（封面可重建，删错无代价） */
private const val MSG_COVER_DISK_MAX = 500

/** GetMessage/DownloadFile 单段超时（毫秒）：封面是锦上添花，不能让坏网络拖死格子 */
internal const val MSG_COVER_TIMEOUT_MS = 15_000L

/** 内存缓存里的"查过无封面"墓碑（共享空数组，只读不写，安全） */
private val MSG_COVER_NONE = ByteArray(0)

/** 磁盘文件名消毒：remote.uniqueId 理论上是 base64url 字符集，防怪字符兜底 */
private val MSG_COVER_NAME_REGEX = Regex("[^A-Za-z0-9._-]")
