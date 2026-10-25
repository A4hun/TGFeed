package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

// ---------------- 下载 / 播放 ----------------

internal fun TdLibGateway.videoDlMutex(fileId: Int): Mutex = videoDlMutexes.getOrPut(fileId) { Mutex() }

internal suspend fun TdLibGateway.ensurePlayableImpl(item: VideoItem, priority: Int): String {
    // R80 埋点：播放地址解析发起（结果在下方各 return 点回记）
    dev.tgfeed.core.playback.PlaybackTrace.log(
        "ensure→", item.key, "fileId=${item.fileId} cached=${item.playUri != null}",
    )
    item.playUri?.let { cached ->
        // R80 收口前移（治"经常加载失败"的裸路径漏出）：缓存地址是裸本地路径且
        // 含 '#' 或 '?' 时绝不能直返——MediaItem.setUri(String) 过 Uri.parse 会把
        // '#'/'?' 后段截成 fragment/query，FileDataSource 实锤报
        // "uri has query and/or fragment"（错误码 1004）或读到截断后的目录，
        // 播放器起播即败。任何新链路写入的裸路径都在此强制过 playableUriOrStream
        // 收口判定；http/asset 直链与正常裸路径原样放行
        if (!cached.startsWith("http") && !cached.startsWith("asset:///") &&
            (cached.contains('#') || cached.contains('?'))
        ) {
            val rerouted = playableUriOrStream(cached, item.fileId)
            dev.tgfeed.core.playback.PlaybackTrace.log(
                "ensure←", item.key, "裸路径含特殊字符改道 ${uriKindOf(rerouted)}",
            )
            return rerouted
        }
        dev.tgfeed.core.playback.PlaybackTrace.log(
            "ensure←", item.key, "缓存直返 ${uriKindOf(cached)} …${cached.takeLast(24)}",
        )
        return cached
    }
    require(item.fileId >= 0) { "该条目没有 TDLib fileId" }
    // R166 整下载收编：未完成一律回流服务器边下边播（泵=本文件唯一下载驱动：顺序块
    // +seek 跳变块按需拉），此处不再发起整载。旧口径「先发整载再按完成态分流」让每个
    // 未完成视频都挂一条长期在途的整下载载：TDLib 同文件同时只有一个下载窗口锚点
    // （同锚合并、异锚竞态互杀，R72「尾探针与正播交错两边窗口全灭」实证），泵的跳变
    // 查询（seek 到未下载区）与它竞速，输掉=查询被杀/悬挂 → 水位不前 → 503/截断 →
    // 播放器重试 → 重试链换路重发整载（自持循环），唯一收敛=整载磨完全文件——用户实锤
    // 「必须整个视频下载完才能快进」。收编后整载只剩：WiFi 预载（未在播文件+让位闸）、
    // 显式下载到相册、R86 坏内容强制重下、泵连续失败的最终兜底（fullDownloadFallback，
    // 显式上报）。行为变化挂账：滑走后文件不再后台磨完，回看重播=已下载段盘命中秒开
    // +未下载段按需拉。
    // GetFile 是幂等本地查询（读本地数据库不发网络，见 downloadProgressImpl 注释），
    // 判完成态不需要下载副作用；priority 参数保留接口兼容，流式路径下泵统一用 32。
    // R364：超时兜底——GetFile 虽为幂等本地查询，TDLib native 冻死/隐性限流期同样
    // 可能挂起（R169 sendReq 同族判词）；12s 到点按「未就绪」走既有可重试链，不再永挂
    val f = runCatching {
        withTdTimeout(what = "ensurePlayable/GetFile fileId=${item.fileId}") { client.send(TdApi.GetFile(item.fileId)) }
            ?: error("TDLib 请求超时（可能限流）")
    }.getOrElse { throw IllegalStateException("GetFile 未就绪: ${it.message}") }
    // R80 fileId 验身（"经常加载失败/格式不支持"头号根因，口径不变）：
    // fileId 是会话级 id——快照/收藏/历史等落盘条目跨进程复活后，旧 id 可能已被
    // TDLib 换发给别的文件（实测：视频条目的旧 fileId 指向一张 11KB 缩略图，
    // 整载"完成"回 jpg 路径 → ExoPlayer 嗅探失败 3003 → R78 分诊误判
    // "视频格式不支持或已损坏"进不可重试终态，换路复活链根本没机会跑）。
    // 按 remote.uniqueId（内容指纹，resolveCoverFrameFileId 同口径）验身：
    // 不符即按消息反解/指纹换发真身重取；落空抛异常走"地址未就绪"可重试链，
    // 绝不把别的文件当本片播
    val fv = verifyVideoFileIdentity(f, item)
        ?: throw IllegalStateException("fileId 已失效且换发落空")
    val p = fv.local.path
    if (fv.local.isDownloadingCompleted && p.isNotEmpty() &&
        runCatching { java.io.File(p).isFile }.getOrDefault(false)
    ) {
        // 含 '#'/'?' 的本地路径不过 Uri 解析，走流服务器（见 playableUriOrStream 实锤注释）
        val uri = playableUriOrStream(p, fv.id)
        dev.tgfeed.core.playback.PlaybackTrace.log(
            "ensure←", item.key, "本地完成 ${uriKindOf(uri)} …${uri.takeLast(24)}",
        )
        return uri
    }
    val streamUri = streamServer.url(fv.id)
    dev.tgfeed.core.playback.PlaybackTrace.log("ensure←", item.key, "回流服务器边下边播(无整载)")
    return streamUri
}

/**
 * R80 播放链 fileId 验身（[resolveCoverFrameFileId] 封面闸的同口径姊妹版）：
 * DownloadFile/GetFile 回来的 File 的 remote.uniqueId 与条目持久化指纹不符 =
 * 会话级 fileId 已被 TDLib 换发给别的文件（缩略图/别的视频）。
 * 命中换发：优先按消息反解（reviveFileIdByMessage 拿当前真身），回落按指纹换发；
 * 换发成功则对新 id 取其 File（R166：改 GetFile 幂等查询——旧实现借整载取 File，
 * 等于验身顺手又挂一条在途整载；验身只需要 remote.uniqueId，不需要下载，新 id
 * 未完成时照回流服务器按需拉）；换发落空/换回同号返回 null。
 * 无指纹可验（Mock/老数据缺省空串）时原样信任。
 */
internal suspend fun TdLibGateway.verifyVideoFileIdentity(
    f: TdApi.File,
    item: VideoItem,
): TdApi.File? {
    if (item.fileUniqueId.isEmpty()) return f
    if (f.remote?.uniqueId.orEmpty() == item.fileUniqueId) return f
    Log.w(TAG, "ensurePlayable 验身不符：fileId=${item.fileId} 非条目本体，换发真身 key=${item.key}")
    dev.tgfeed.core.playback.PlaybackTrace.log(
        "idMismatch", item.key, "旧fileId=${item.fileId} 被换发，按消息/指纹重取",
    )
    val newId = (if (item.messageId > 0) reviveFileIdByMessage(item.chatId, item.messageId) else null)
        ?: reviveFileId(item.fileUniqueId)
        ?: return null
    if (newId == item.fileId || newId <= 0) return null // 换回同号=指纹漂移，不敢播
    // 换发结果同样要验身：消息反解对"图片附带视频"条目会拿回图片档 id
    //（reviveFileIdByMessage 的 MessagePhoto 分支取 sizes 最大档），
    // 类型货不对板时若直接播等于换个方式播错文件——新 id 的文件也必须对上指纹
    // R364：超时兜底——换发验身挂起 12s 到点按 null（换发落空）走可重试链
    return runCatching {
        withTdTimeout(what = "verifyVideoFileIdentity/GetFile fileId=$newId") { client.send(TdApi.GetFile(newId)) }
    }.getOrNull()?.takeIf { it.remote?.uniqueId.orEmpty() == item.fileUniqueId }
}

/** R80 埋点用地址分类：local#/local?/local/http/asset/stream（回环流地址单列） */
internal fun TdLibGateway.uriKindOf(uri: String): String = when {
    uri.startsWith("http://127.0.0.1:") -> "stream"
    uri.startsWith("http") -> "http"
    uri.startsWith("asset") -> "asset"
    uri.contains('#') -> "local#"
    uri.contains('?') -> "local?"
    else -> "local"
}

/**
 * 指定清晰度档位起播：与 ensurePlayable 同一条判定链，仅 fileId 换成变体档的
 * （变体档 File 来自 MessageVideo.alternativeVideos[].video，是独立 TDLib file id）。
 * 命中主档（fileId == item.fileId）时直接委托 ensurePlayable，保留 playUri 短路；
 * 已完成下载回本地路径，否则回流式服务器地址边下边播。
 * R166 整下载收编：变体档同样不再发整载（GetFile 判完成，未完成流式按需拉）。
 */
internal suspend fun TdLibGateway.ensurePlayableVariantImpl(item: VideoItem, fileId: Int): String {
    if (fileId == item.fileId) return ensurePlayable(item)
    require(fileId >= 0) { "该清晰度档位没有 TDLib fileId" }
    val f = runCatching {
        // R364：超时兜底——变体档 GetFile 挂起 12s 到点按「未就绪」走既有可重试链
        withTdTimeout(what = "ensurePlayableVariant/GetFile fileId=$fileId") { client.send(TdApi.GetFile(fileId)) }
            ?: error("TDLib 请求超时（可能限流）")
    }.getOrElse { throw IllegalStateException("GetFile 未就绪: ${it.message}") }
    val p = f.local.path
    if (f.local.isDownloadingCompleted && p.isNotEmpty() &&
        runCatching { java.io.File(p).isFile }.getOrDefault(false)
    ) {
        // R80 防呆：变体档 fileId 同为会话级 id，落盘条目跨进程复活后可能已被
        // 换发给非视频文件（变体档无 uniqueId 可验，按扩展名识别明显错发）——
        // 图片/文本路径绝不能当视频地址返回，回落主档链路（主档有指纹验身）
        if (NON_VIDEO_PATH_RX.containsMatchIn(p)) {
            Log.w(TAG, "ensurePlayableVariant 变体档错发非视频文件 fileId=$fileId path=…${p.takeLast(24)}，回落主档")
            dev.tgfeed.core.playback.PlaybackTrace.log(
                "variantMismatch", item.key, "变体fileId=$fileId 错发非视频，回落主档",
            )
            return ensurePlayable(item)
        }
        return playableUriOrStream(p, fileId)
    }
    return streamServer.url(fileId)
}

/**
 * R86 内容损坏换源重下（修偶发 3001 Invalid NAL 的残留路径）：播放层撞上容器
 * 解析/解码失败类错误时调用——作废该文件 TDLib 本地已下载内容并立即从头重拉。
 * R81 的闸只能保证「发出的字节与 TDLib 回报的水位一致」，管不了 TDLib 侧并发
 * 下载期间的瞬态内容不一致（R81 复盘：那次 3001 前闸全程静默、块块验证通过，
 * 坏字节是过了所有闸的）；应用层无法逐字节证真伪，重新下载是唯一自愈手段。
 * 步骤：取消在途下载 → 删除本地文件 → 重启整载；全程与泵分块同锁串行
 *（videoDlMutex，防与流服务器在途同步查询互杀）。返回 false = 发起失败，
 * 播放层重试链自会再走 ensurePlayable 兜底。
 */
internal suspend fun TdLibGateway.forceRedownloadVideoImpl(fileId: Int): Boolean {
    if (fileId < 0) return false
    return runCatching {
        videoDlMutex(fileId).withLock {
            // R364：超时兜底——挂起时各步 12s 到点：取消/删除按静默（best-effort 原语义），
            // 重启整载按 error→false（发起失败，播放层重试链自会再走 ensurePlayable）
            runCatching {
                withTdTimeout(what = "forceRedownload/CancelDownloadFile fileId=$fileId") {
                    client.send(TdApi.CancelDownloadFile(fileId, false))
                }
            }
            runCatching {
                withTdTimeout(what = "forceRedownload/DeleteFile fileId=$fileId") { client.send(TdApi.DeleteFile(fileId)) }
            }
            withTdTimeout(what = "forceRedownload/DownloadFile fileId=$fileId") {
                client.send(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
            } ?: error("TDLib 请求超时（可能限流）")
        }
        Log.i(TAG, "强制重下已发起 fileId=$fileId（内容损坏换源）")
        true
    }.onFailure {
        Log.w(TAG, "强制重下发起失败 fileId=$fileId: ${it.message}")
    }.getOrDefault(false)
}
internal suspend fun TdLibGateway.prioritizeImpl(fileId: Int, priority: Int, offset: Long, limit: Long) {
    // R76：预载与泵分块同文件串行（见 videoDlMutexes），防预热杀掉正播的泵
    // R364：超时兜底——优先级下发挂起 12s 到点按静默（runCatching 原语义，预载是锦上添花）
    runCatching {
        videoDlMutex(fileId).withLock {
            withTdTimeout(what = "prioritize/DownloadFile fileId=$fileId") {
                client.send(TdApi.DownloadFile(fileId, priority, offset, limit, false))
            }
        }
    }
}

/**
 * R145 播放预热（快照秒播的核心一环）：视频头部 2MB 后台预拉。
 *
 * 拍板依据（为什么只热头、不热尾）：
 *  - offset=0 与 ensurePlayable 随后发起的整载同锚点——TDLib 对同偏移请求自然
 *    合并（优先级取高者），**没有互杀窗口**；预热与整载不是两路竞争，是同一路
 *    下载的提前起跑（R72 实锤"同文件不同 offset 并发 DownloadFile 互杀"，跨
 *    offset 的 moov 尾部预热因此不可行——整载一起，尾窗即被冲掉）；
 *  - 起播关键路径 = moov 盒 + 首帧数据：moov 在头的 mp4（规范产出）预热后秒开；
 *    moov 在尾的（TG 上传原片常见）尾部探测仍要走一次网络，由泵的探针连接
 *    伺候（TgStreamServer 侧另有探针块优化，不在此处）。
 * 调用点：快照换发成功后（首条 p32=播放同级，其余 p16=下一条预载级）。
 * prioritize 走同步发送、异步下载：许可只占一瞬，不与泵抢 4 路查询闸。
 */
internal suspend fun TdLibGateway.warmupForPlayback(fileId: Int, priority: Int) {
    if (fileId < 0) return
    runCatching {
        prioritizeImpl(fileId, priority, 0L, 2L * 1024 * 1024)
    }.onFailure {
        Log.d(TAG, "播放预热未发起 fileId=$fileId: ${it.message}")
    }
}

/**
 * 查询 TDLib 文件下载进度：0f..1f；异常或总量未知返回 -1f（调用方/UI 见 -1 不显示百分比）。
 * GetFile 是幂等的本地查询（读本地数据库，不发网络请求），开销小，适合 1s 轮询。
 * 总量优先取 expectedSize——下载中的文件 size 字段可能还是 0（真实大小下载完才回填）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetFile(int fileId) -> File{id: int, size: long, expectedSize: long, local: LocalFile, remote: RemoteFile}
 * LocalFile{path, canBeDownloaded, canBeDeleted, isDownloadingActive, isDownloadingCompleted,
 *           downloadOffset: long, downloadedPrefixSize: long, downloadedSize: long}
 */
internal suspend fun TdLibGateway.downloadProgressImpl(fileId: Int): Float = runCatching {
    // R364：超时兜底——进度查询挂起 12s 到点按 -1f（与查询失败同口径，UI 不显示百分比）
    val f = withTdTimeout(what = "downloadProgress/GetFile fileId=$fileId") { client.send(TdApi.GetFile(fileId)) }
        ?: error("TDLib 请求超时（可能限流）")
    if (f.local.isDownloadingCompleted) return@runCatching 1f
    val total = if (f.expectedSize > 0) f.expectedSize else f.size
    if (total <= 0) return@runCatching -1f
    (f.local.downloadedSize.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}.getOrDefault(-1f)

/**
 * CRITICAL-2 修复配套：按远端文件唯一 id（File.remote.uniqueId）换发本会话新 fileId。
 * 收藏/稍后再看持久化的是会话级 fileId，进程重启即失效；fileUniqueId 跨会话稳定，
 * 是重启后重新解析的唯一凭据。GetRemoteFile 优先查本地文件数据库（离线请求，命中即
 * 返回带本会话新 id 的 File；未命中时按 remoteFileId 向服务器侧解析注册），
 * 返回的 File.id 即可直接喂 ensurePlayable/DownloadFile/GetFile。
 * 解析失败/uniqueId 未知返回 null（调用方保留原条目，播放时走 ensurePlayable 报错链）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetRemoteFile(String remoteFileId, FileType fileType) -> File{id: int, size: long,
 *   expectedSize: long, local: LocalFile, remote: RemoteFile{id, uniqueId, ...}}；
 * fileType 未知可传 null。
 */
internal suspend fun TdLibGateway.reviveFileIdImpl(fileUniqueId: String): Int? = runCatching {
    awaitAuthorized() // 等授权就绪再解析；未授权期间挂起（仅阻塞复活协程，不碍主流程）
    // R364：超时兜底——换发解析挂起 12s 到点按 null（复活失败，调用方走 ensurePlayable 报错链）
    withTdTimeout(what = "reviveFileId/GetRemoteFile") { client.send(TdApi.GetRemoteFile(fileUniqueId, null)) }
        ?.id?.takeIf { it > 0 }
}.getOrNull()

/**
 * 下载进度流（起播等待/缓冲的"转圈焦虑"治理）：1s 轮询 GetFile，
 * 每次发射 0f..1f（总量未知或查询失败发 -1f，UI 见 -1 不显示百分比）；
 * 下载完成时以 1f 收尾并自动 complete。
 * GetFile 是幂等本地查询（读本地数据库，不发网络请求），开销小；
 * 轮询随收集方取消而停止（VideoPage 翻页/销毁即停）。
 */
internal fun TdLibGateway.downloadProgressFlowImpl(fileId: Int): Flow<Float> = flow {
    while (true) {
        val p = downloadProgress(fileId)
        emit(p)
        if (p >= 1f) return@flow
        delay(1_000)
    }
}

/**
 * 清理 TDLib 本地缓存，返回人类可读的清理摘要。
 * 参数全部用 -1（TDLib 默认限额：不删刚创建不久的文件、不碰缩略图/头像/贴纸）；
 * returnDeletedFileStatistics=true → 返回的统计即"本次删掉的部分"。
 *
 * 签名已 javap 实证：
 * OptimizeStorage(long size, int ttl, int count, int immunityDelay, FileType[] fileTypes,
 *                 long[] chatIds, long[] excludeChatIds, boolean returnDeletedFileStatistics, int chatLimit)
 *   -> StorageStatistics{size, count, byChat}
 */
internal suspend fun TdLibGateway.optimizeStorageImpl(): String {
    // R364：超时兜底——存储重扫描走 30s 放宽档（STORAGE_TD_TIMEOUT_MS；全量扫描对大号
    // 偏重），到点上抛走调用方既有失败路（原裸 send 语义=失败上抛，零行为回破）
    val stats = withTdTimeout(STORAGE_TD_TIMEOUT_MS, what = "optimizeStorage/OptimizeStorage") {
        client.send(
            TdApi.OptimizeStorage(-1L, -1, -1, -1, null, null, null, true, -1)
        )
    } ?: error("TDLib 请求超时（可能限流）")
    val mb = stats.size / 1024.0 / 1024.0
    val text = String.format(java.util.Locale.US, "清理了 %.1f MB（%d 个文件）", mb, stats.count)
    Log.i(TAG, "optimizeStorage: $text")
    return text
}

// ---------------- 转存收藏夹 / 翻译 ----------------

/**
 * 把视频消息转发到"收藏夹"（Saved Messages，即自己的聊天）。
 * GetMe 拿自己的 user id 作为转发目标；先用 GetMessage 检查保护标记——
 * 注意：本 AAR（TDLib 1.8.67）的 Message 已没有 canBeForwarded 字段，
 * 受保护（noforwards）内容由 canBeSaved=false 表达，此时直接失败。
 *
 * 签名已 javap 实证：
 * GetMe() -> User{id: long}
 * GetMessage(long chatId, long messageId) -> Message{canBeSaved: boolean}
 * ForwardMessages(long chatId, MessageTopic topicId, long fromChatId, long[] messageIds,
 *                 MessageSendOptions options, boolean sendCopy, boolean removeCaption) -> Messages
 */
internal suspend fun TdLibGateway.forwardToSavedImpl(item: VideoItem): Result<String> = runCatching {
    awaitAuthorized()
    // R364：超时兜底——三步发送各裹 12s 上限闸，挂起到点按 Result.failure 诚实回落
    val msg = withTdTimeout(what = "forwardToSaved/GetMessage chatId=${item.chatId}") {
        client.send(TdApi.GetMessage(item.chatId, item.messageId))
    } ?: error("TDLib 请求超时（可能限流）")
    if (!msg.canBeSaved) error("该频道禁止转发")
    val me = withTdTimeout(what = "forwardToSaved/GetMe") { client.send(TdApi.GetMe()) }
        ?: error("TDLib 请求超时（可能限流）")
    // sendCopy=false 保留转发来源；目标=自己 id 即收藏夹
    withTdTimeout(what = "forwardToSaved/ForwardMessages chatId=${item.chatId}") {
        client.send(
            TdApi.ForwardMessages(
                me.id, null, item.chatId, longArrayOf(item.messageId), null, false, false
            )
        )
    } ?: error("TDLib 请求超时（可能限流）")
    Log.i(TAG, "forwardToSaved: chatId=${item.chatId} messageId=${item.messageId} -> 收藏夹")
    "已转存到收藏夹"
}

/**
 * 一键翻译 caption（默认目标语言中文）。caption 为空直接失败；
 * 结果按条目缓存，重复点击不再请求 TDLib。
 *
 * 签名已 javap 实证：
 * TranslateMessageText(long chatId, long messageId, String toLanguageCode, String tone)
 *   -> FormattedText{text: String}
 * （tone 为 1.8.67 新增字段，空串 = 默认语气）
 *
 * 两处断点修复（"点'译'没反应"）：
 *  1. 先 GetMessage 预热本地缓存：TranslateMessageText 只认 TDLib 缓存里的消息，
 *     进程重启/缓存被清后直接翻译必吃 "Message not found"（与 forwardToSaved 同款处理）；
 *  2. 20s 超时兜底：ktx 的 send 没有超时，网络/代理卡死时协程永久挂起——
 *     UI 表现就是点"译"后既无译文也无失败提示；超时按失败抛出，
 *     FeedActions 折成"翻译失败：…"Toast，用户可见可重试。
 */
internal suspend fun TdLibGateway.translateCaptionImpl(item: VideoItem, targetLang: String = "zh"): Result<String> = runCatching {
    if (item.caption.isBlank()) error("没有可翻译的文字")
    captionTranslationCache[item.key]?.let { return@runCatching it }
    awaitAuthorized()
    // 预热消息缓存（失败不致命，继续尝试翻译——消息可能其实还在缓存里）
    // R364：超时兜底——预热挂起 12s 到点按静默（best-effort 原语义，翻译主请求另有 20s 闸）
    runCatching {
        withTdTimeout(what = "translateCaption/GetMessage 预热 chatId=${item.chatId}") {
            client.send(TdApi.GetMessage(item.chatId, item.messageId))
        }
    }
    val translated = kotlinx.coroutines.withTimeout(20_000L) {
        client.send(
            TdApi.TranslateMessageText(item.chatId, item.messageId, targetLang, "")
        )
    }
    val text = translated.text
    if (text.isBlank()) error("翻译结果为空")
    captionTranslationCache[item.key] = text
    Log.i(TAG, "translateCaption: messageId=${item.messageId} -> $targetLang (${text.length} 字)")
    text
}

// ---------------- 存储治理（R97 起；R196 调度收编进 StorageGovernor） ----------------

/** 治理日志统一用 MB 一位小数，方便直接读数对账（R196 收敛：实现单源在 core/storage，此处仅委托） */
internal fun TdLibGateway.fmtMb(bytes: Long): String = dev.tgfeed.core.storage.fmtMb(bytes)

/** 量出 TDLib 消息库三件套（db.sqlite + wal + shm）的磁盘总占用；文件不存在按 0 计 */
internal fun TdLibGateway.messageDatabaseBytes(filesDir: java.io.File): Long {
    val dir = java.io.File(filesDir, "tdlib")
    return listOf("db.sqlite", "db.sqlite-wal", "db.sqlite-shm", "db.sqlite-journal")
        .sumOf { name -> java.io.File(dir, name).let { if (it.exists()) it.length() else 0L } }
}

/**
 * 启动前闸门·消息库超限重置（仅在 TDLib 开库前调用）：
 * db.sqlite 三件套总量超 [dbBudgetBytes] 时整体删除——TDLib 1.8.67 没有消息库体积控制
 * API（javap/strings 实证只有文件级 GC，无消息保留上限），sqlite 不删文件也永远缩不了容，
 * 故唯一可靠的根治是预算内放行、超预算重建。消息/会话缓存均可从服务器重建；
 * td.binlog（文件元数据账）与 files/ 媒体缓存不动，已下载的视频记录不受影响。
 * 红线：只动 files/tdlib/ 内 TDLib 自有缓存，files/deepindex/ 与业务 json 一律不碰。
 */
internal fun TdLibGateway.resetMessageDatabaseIfOverBudget(filesDir: java.io.File) {
    val total = messageDatabaseBytes(filesDir)
    Log.i(TAG, "存储治理·启动闸门: 消息库 ${fmtMb(total)} MB / 预算 ${fmtMb(dbBudgetBytes)} MB")
    if (total <= dbBudgetBytes) return
    val dir = java.io.File(filesDir, "tdlib")
    var freed = 0L
    for (name in listOf("db.sqlite", "db.sqlite-wal", "db.sqlite-shm", "db.sqlite-journal")) {
        val f = java.io.File(dir, name)
        if (f.exists()) {
            val len = f.length()
            if (f.delete()) freed += len else Log.w(TAG, "存储治理·启动闸门: 删除失败 $name")
        }
    }
    Log.w(TAG, "存储治理·启动闸门: 消息库超限已重置，回收 ${fmtMb(freed)} MB，本次启动将全量重同步会话")
}

/**
 * temp 死残留清扫（**预初始化专用**，TDLib 开库前跑）：扫 files/tdlib/temp/ 与
 * files/tdlib/files/temp/（下载暂存区），删除修改时间早于 [staleMs] 的普通文件，
 * 返回回收字节数。目录本身与隐藏文件（.nomedia）不删。
 * R196 起：口径收紧为 2h（旧 24h 实锤放走当日全部残骸——用户机 3GB 现场就是它）；
 * 运行期的精细清扫（活跃排除 + TDLib DeleteFile 一致删除 + 压力触发）由
 * StorageGovernor.sweepTempFragments 接管，本函数只服务启动前（TDLib 未开库，
 * DeleteFile 无从发，只能裸删 + 保守陈旧度）。
 */
internal fun TdLibGateway.sweepTempDirs(filesDir: java.io.File, staleMs: Long): Long {
    val now = System.currentTimeMillis()
    val roots = listOf(
        java.io.File(filesDir, "tdlib/temp"),
        java.io.File(filesDir, "tdlib/files/temp"),
    )
    var freed = 0L
    var removed = 0
    for (root in roots) {
        val entries = root.listFiles() ?: continue
        for (f in entries) {
            if (!f.isFile || f.name.startsWith(".")) continue
            if (now - f.lastModified() < staleMs) continue
            val len = f.length()
            if (f.delete()) {
                freed += len
                removed++
            }
        }
    }
    if (removed > 0) {
        Log.i(TAG, "存储治理·temp 清扫: 删 $removed 个残留文件，回收 ${fmtMb(freed)} MB")
    } else {
        Log.d(TAG, "存储治理·temp 清扫: 无死残留")
    }
    return freed
}

/**
 * 运行期消息库观测：量磁盘真实占用（含 wal——Android 应用多被强杀收不到干净退出，
 * wal 跨会话只涨不缩，正是用户实测 34G 的来源）；超预算只记日志，
 * 实际缩容由下次启动前的闸门执行（运行期 TDLib 持有库文件，删了就是库损坏）。
 * 过半预算时附 GetDatabaseStatistics 明细（TDLib 官方自检串），辅助判断增长来源。
 */
internal suspend fun TdLibGateway.observeMessageDatabase(filesDir: java.io.File) {
    val total = messageDatabaseBytes(filesDir)
    if (total > dbBudgetBytes) {
        Log.w(TAG, "存储治理·消息库观测: ${fmtMb(total)} MB 已超预算 ${fmtMb(dbBudgetBytes)} MB，将在下次启动时重置重建")
    } else {
        Log.i(TAG, "存储治理·消息库观测: ${fmtMb(total)} MB / 预算 ${fmtMb(dbBudgetBytes)} MB")
    }
    if (total > dbBudgetBytes / 2) {
        // R364：超时兜底——诊断明细走 30s 重扫描档，挂起到点按静默（观测件不连坐治理轮；
        // 超时 null 不落明细行，withTdTimeout 的 W 行已是证据）
        runCatching {
            withTdTimeout(STORAGE_TD_TIMEOUT_MS, what = "observeMessageDatabase/GetDatabaseStatistics") {
                client.send(TdApi.GetDatabaseStatistics())
            }
        }
            .onSuccess { stats -> if (stats != null) Log.i(TAG, "存储治理·消息库明细: ${stats.statistics.take(600)}") }
            .onFailure { Log.d(TAG, "存储治理·消息库明细获取失败: ${it.message}") }
    }
}

// ---------------- R145 冷启动让路闸（存储治理用） ----------------

/**
 * R145 冷启动让路闸：首屏内容落定前，低优先级的重活（存储治理首轮等）靠边站。
 * FeedViewModel 在首轮拉取结论处调 [markFeedSettled]；等待方 [awaitQuiet] 在
 * 落定后追加一段宽限（后台派生风暴——收割/快拍/发现页扫描都排在头几秒），
 * 迟迟无信号（无 VM 场景/拉取失败）最多等 [FALLBACK_MS] 放行，绝不永久挂起。
 * 纯内存静态（一次落定终身有效），标记幂等。
 *
 * R208 追加（全局启动协调·单源化）：让路窗收口后再过 [SystemStormGate]——
 * 系统开机静默期+负载退避持有中则继续等。本闸是 WarmupCoordinator 错峰闸与
 * StorageGovernor 首轮/水位闸的公共入口，风暴闸并入这里=全部既有消费方
 * （R170b 预热任务/R196 治理轮）零接线改动继承开机风暴治理，时机闸语义：
 * 各消费方执行逻辑零改动，只延后起跑。
 */
internal object ColdStartQuietGate {
    @Volatile
    private var settledAtMs = 0L

    private const val FALLBACK_MS = 150_000L

    /** 首屏内容落定标记（FeedViewModel 首轮结论处调用；幂等，只认第一次） */
    fun markFeedSettled() {
        if (settledAtMs == 0L) settledAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** 等待冷启动窗口结束（已落定 → 宽限 [graceMs]；未落定 → 最多 [FALLBACK_MS] 兜底放行） */
    suspend fun awaitQuiet(graceMs: Long = 20_000L) {
        waitForFeedSettled(graceMs)
        // R208 开机风暴闸追加：冷启动让路窗收口后，系统级风暴（开机静默期+负载
        // 退避）仍持有则继续等。已放行时直通零开销（后续轮语义不变）；持有中
        // 每 5s 复查、超限兜底放行（见 SystemStormGate.awaitClear）。
        runCatching { SystemStormGate.awaitClear("冷启动静默窗") }
            .onFailure { Log.w(TAG, "风暴闸等待异常（直接放行）: ${it.message}") }
    }

    private suspend fun waitForFeedSettled(graceMs: Long) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val settled = settledAtMs
            if (settled > 0L) {
                val remain = settled + graceMs - android.os.SystemClock.elapsedRealtime()
                if (remain > 0) delay(remain)
                return
            }
            if (android.os.SystemClock.elapsedRealtime() - t0 >= FALLBACK_MS) return
            delay(1_000)
        }
    }
}

/**
 * 水位检查：两道闸。第一道视频专项（FileTypeVideo 超限额清回）；第二道全类型总量
 * （fileTypes=null，总账超限额清回）——用户对"缓存上限"的语义是总缓存。
 * OptimizeStorage 的 size 语义是"清理后期望的总量上限"，
 * 删除顺序由 TDLib 按最旧（最久未访问）优先保证；免疫期显式 1h
 * （覆盖在播+预载窗口；默认 24h 实测等于当天不设防，见 R97 验证记录）。
 *
 * R97 修复统计失真：byChat 明细只按体积返回前 chatLimit 个会话，旧值 100 对
 * 数百频道的大号严重漏计（限额形同虚设）；现用 [STORAGE_STATS_CHAT_LIMIT]=1000，
 * 并同时记录 GetStorageStatisticsFast 的全量总账（不受 chatLimit 影响），
 * 日志里两组数对照，漏计一眼可辨。
 *
 * 签名已 javap 实证：
 * GetStorageStatistics(int chatLimit) -> StorageStatistics{size, count, byChat}
 * StorageStatisticsByChat{chatId, size, count, byFileType: StorageStatisticsByFileType[]}
 * StorageStatisticsByFileType{fileType: FileType, size: long, count: int}
 * GetStorageStatisticsFast() -> StorageStatisticsFast{filesSize, fileCount, databaseSize, ...}
 * FileTypeVideo()；OptimizeStorage 签名见 optimizeStorage() 注释
 *
 * R196：返回值改为本轮释放字节（视频专项 + 全类型两道合计）——StorageGovernor
 * 的清理日志/释放量 trace 消费；调用方由 StorageGovernor 调度（不再有网关内小时循环）。
 */
internal suspend fun TdLibGateway.enforceStorageWatermark(): Long {
    // R145 冷启动让路：首轮治理延到首屏内容落定再跑——GetStorageStatistics(1000)
    // 全量文件扫描 + GetDatabaseStatistics 消息库盘点都在 TDLib 内部线程/锁上
    // 走，冷启动头几秒与首拉请求挤同一批资源（华为平板基线：治理 5s 横跨首拉
    // 窗口起点）；治理每小时一轮，首轮晚几十秒无感。落定后本闸直通（后续轮零开销）。
    runCatching { ColdStartQuietGate.awaitQuiet() }
    var freedTotal = 0L
    // 全量总账（便宜、不受 chatLimit 截断）：filesSize=全部文件缓存，databaseSize=消息库账面
    // R364：超时兜底——快账观测挂起 30s 到点按静默（观测件不连坐两道闸；超时 null 不落总账行，
    // withTdTimeout 的 W 行已是证据）
    runCatching {
        withTdTimeout(STORAGE_TD_TIMEOUT_MS, what = "enforceStorageWatermark/GetStorageStatisticsFast") {
            client.send(TdApi.GetStorageStatisticsFast())
        }
    }
        .onSuccess { fast ->
            if (fast != null) {
                Log.i(
                    TAG,
                    "存储治理·总账: 文件缓存 ${fmtMb(fast.filesSize)} MB（${fast.fileCount} 个）" +
                        " / 消息库账面 ${fmtMb(fast.databaseSize)} MB / 日志 ${fmtMb(fast.logSize)} MB",
                )
            }
        }
    // R364：超时兜底——全量扫描挂起 30s 到点上抛（StorageGovernor 侧 runCatching 既有失败路，
    // 本轮治理降级不永挂）
    val stats = withTdTimeout(STORAGE_TD_TIMEOUT_MS, what = "enforceStorageWatermark/GetStorageStatistics") {
        client.send(TdApi.GetStorageStatistics(STORAGE_STATS_CHAT_LIMIT))
    } ?: error("TDLib 请求超时（可能限流）")
    // 只关心视频：汇总所有会话里 FileTypeVideo 的体积
    val videoBytes = stats.byChat.sumOf { byChat ->
        byChat.byFileType
            .filter { it.fileType is TdApi.FileTypeVideo }
            .sumOf { it.size }
    }
    Log.i(
        TAG,
        "存储治理·文件水位: 视频缓存 ${fmtMb(videoBytes)} MB / 限额 ${fmtMb(cacheLimitBytes)} MB" +
            "（全类型总账 ${fmtMb(stats.size)} MB，明细覆盖 ${stats.byChat.size} 个会话）",
    )
    if (videoBytes <= cacheLimitBytes) {
        Log.i(TAG, "存储治理·文件水位: 视频未超限，跳过视频专项清理")
    } else {
        // 免疫期显式给 1h（覆盖在播+预载窗口）：TDLib 默认 24h 会让"当天重度刷机"
        // 在限额之上白攒一天（模拟器实锤：2.3G 超限时按默认免疫清出 0 字节）
        // R364：超时兜底——视频专项清理挂起 30s 到点上抛（本轮治理降级，下轮再试）
        val deleted = withTdTimeout(STORAGE_TD_TIMEOUT_MS, what = "enforceStorageWatermark/OptimizeStorage 视频专项") {
            client.send(
                TdApi.OptimizeStorage(
                    cacheLimitBytes, -1, -1, 3600,
                    arrayOf(TdApi.FileTypeVideo()), null, null, true, STORAGE_STATS_CHAT_LIMIT,
                )
            )
        } ?: error("TDLib 请求超时（可能限流）")
        freedTotal += deleted.size
        Log.i(
            TAG,
            "存储治理·文件水位: 已清理 ${fmtMb(deleted.size)} MB（${deleted.count} 个视频文件）",
        )
    }
    // 第二道闸·全类型总量：视频只是大头，文档/图文同样占地（实测 binge 一小时
    // documents 堆 1.9G、全类型总账 7.2G 而视频只占 1.4G）。fileTypes=null = 全类型，
    // 同样按最久未访问优先、1h 免疫——"缓存上限"对用户的语义就是总缓存，不是单类型。
    if (stats.size > cacheLimitBytes) {
        // R364：超时兜底——全类型清理挂起 30s 到点上抛（本轮治理降级，下轮再试）
        val deletedAll = withTdTimeout(STORAGE_TD_TIMEOUT_MS, what = "enforceStorageWatermark/OptimizeStorage 全类型") {
            client.send(
                TdApi.OptimizeStorage(
                    cacheLimitBytes, -1, -1, 3600,
                    null, null, null, true, STORAGE_STATS_CHAT_LIMIT,
                )
            )
        } ?: error("TDLib 请求超时（可能限流）")
        freedTotal += deletedAll.size
        Log.i(
            TAG,
            "存储治理·全类型水位: 总账 ${fmtMb(stats.size)} MB 超限额，已清理 ${fmtMb(deletedAll.size)} MB（${deletedAll.count} 个文件）",
        )
    } else {
        Log.i(TAG, "存储治理·全类型水位: 总账 ${fmtMb(stats.size)} MB 未超限")
    }
    return freedTotal
}
