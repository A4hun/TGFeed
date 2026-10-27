package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

// ---------------- R398 播放编排四期 #9（离线联动·整单下载）core 纯件 ----------------
//
// 「出门前一键，路上按单播」的语义面（r398_notes §2.1 定案）：
//  - 台级「下载整单」=对台内**点名单条条目**（非跳过 Item）走既有单条下载链
//    （下载系统复用零新内部）；频道条目=「整台按序连播」语义归一期开台链
//    （R397 台卡 seedCount 同口径判词——纯频道台=诚实缺席）；
//  - 「已下载 N/M」徽记=DownloadRecord 既有账派生（**零新台账**——断续
//    幂等的真身=记录在即跳过，磁盘账跨会话）；
//  - 离线按单播=开单快照的 Single 段命中下载记录时换 playUri 为本地文件
//    （**既有本地文件播放路**=下载页 toVideoItem「本地文件路径作 playUri
//    直接播」同语义；编排层还原绝对路径——deleteDownloadedFile 拼接还原
//    同款先例，不继承 Media3 相对路径疑病，见 r398_notes §1.2）。
//
// 纯 Kotlin 零 Android 依赖（JVM 单测直测，PlayChain/QueueSemanticJump 同
// 纪律）；路径还原（Environment API）在 VM 件 FeedStationDownloadPart.kt。

/** 台卡「已下载 N/M」徽记数据（total=非跳过单条条目数；downloaded=其中已下载） */
data class StationDownloadCount(val downloaded: Int, val total: Int)

/**
 * 台内待下载条目集（纯函数）：非跳过 Item 条目的 item 列表（顺序=条目序）。
 *
 * 判词：频道条目不入集（整台连播语义归一期开台链，点台卡已承载；下载面=
 * 点名单条的批量下载——与 R397 台卡 seedCount 同口径）；skipped 条目不入集
 * （播放期排除=下载面同理不浪费流量）。
 */
fun stationDownloadItems(station: PlayQueue): List<VideoItem> =
    station.entries
        .asSequence()
        .filterIsInstance<PlayQueueEntry.Item>()
        .filter { !it.skipped }
        .map { it.item }
        .toList()

/**
 * 台卡「已下载 N/M」徽记（纯函数）：台内非跳过单条条目数与其中 key∈台账数。
 *
 * 判词：M=非跳过 Item 条目数（频道条目不计数——下载面口径同
 * [stationDownloadItems]，徽记与下载钮同口径才不骗）；N=命中
 * [downloadedKeys]（DownloadRecord.key=item.key 唯一判轨）的条目数；
 * M=0（纯频道台/空台）徽记不显示=诚实缺席（UI 侧守卫）。
 */
fun stationDownloadCount(
    station: PlayQueue,
    downloadedKeys: Set<String>,
): StationDownloadCount {
    val items = stationDownloadItems(station)
    val downloaded = items.count { it.key in downloadedKeys }
    return StationDownloadCount(downloaded = downloaded, total = items.size)
}

/**
 * 离线按单播段接线（纯函数）：Single 段 item.key 命中本地地址表→
 * `copy(item = item.copy(playUri = uri))`。
 *
 * 判词（r398_notes §2.1）：
 *  - **下载副本=离线最强凭据，覆盖条目既有 playUri**（TDLib 本地缓存路径
 *    随缓存可亡/回流地址离线必死/下载文件=用户显式保存的最稳离线源——
 *    下载管理页 toVideoItem 同语义「本地文件路径作 playUri 直接播」）；
 *  - **Channel 段零接线**（频道段取数现拉=一期懒分页语义零改；离线面=TDLib
 *    缓存运气（缓存命中段可播，未命中段取数错误上抛卡停=一期诚实口径），
 *    如实注记挂账，不做频道段预取——那是另一张设计）；
 *  - **plan=开单快照面零碰持久化实体**（play_queue.json 条目原样——快照
 *    语义一期判词零改；播放走拷贝副本，断点记账按 item.key 照常反查）；
 *  - 表空/plan 空→原样返回（无下载记录时一期开单行为零变化）。
 *
 * @param plan buildPlayPlan 产物（开单快照段列表）
 * @param localUriByKey item.key→已还原的本地绝对路径（VM 侧由 DownloadRecord
 *   台账+路径还原构造；见 FeedStationDownloadPart.applyStationOfflinePlan）
 */
fun applyDownloadedSegments(
    plan: List<PlaySegment>,
    localUriByKey: Map<String, String>,
): List<PlaySegment> {
    if (localUriByKey.isEmpty() || plan.isEmpty()) return plan
    return plan.map { seg ->
        if (seg is PlaySegment.Single) {
            val uri = localUriByKey[seg.item.key]
            if (uri != null) seg.copy(item = seg.item.copy(playUri = uri)) else seg
        } else {
            seg
        }
    }
}
