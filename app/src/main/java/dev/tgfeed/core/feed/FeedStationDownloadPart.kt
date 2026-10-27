package dev.tgfeed.core.feed

import android.os.Environment
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.store.DownloadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// ---------------- R398 播放编排四期 #9（离线联动·整单下载）VM 编排 ----------------
//
// 三件编排（r398_notes §2.2 定案；实现在本件+openPlayQueueImpl 一行挂点，
// 下载系统本体（FeedDownloadPart downloadImpl/saveToGallery/MediaStore 两段式）
// 与播放引擎（ensurePlayable/VideoLoad/Media3）内部零改只编排）：
//  ①台级「下载整单」downloadPlayStationImpl——对台内点名单条条目**逐条串行**
//    走既有单条下载链（断续幂等=台账在即跳过；出门前一键长跑批不风暴）；
//  ②徽记数据=UI 侧 stations+downloadRecords 双 StateFlow 派生（零新状态，
//    StationDownloads.stationDownloadCount 纯函数口径）；
//  ③离线按单播=applyStationOfflinePlan（openPlayQueueImpl buildPlayPlan 后
//    一行挂点——Single 段命中台账换本地绝对路径 playUri，无记录零行为变化）。
//
// 红线自查（r398_notes §0）：一/二/三期结论零碰只消费（挂点式最小刀沿
// R392b 先例）；R398 件零进 tgfeed-oss。

/** 单条兜底等待上限：15 分钟（超时=该条仍在途，继续后续条——下次点击幂等补） */
internal const val STATION_DOWNLOAD_PER_ITEM_MS: Long = 15 * 60_000L

/**
 * 台级「下载整单」（台卡行尾钮）：对台内**点名单条条目**（非跳过 Item，
 * StationDownloads.stationDownloadItems 口径——频道条目归一期开台链判词）
 * 逐条走**既有单条下载链** [downloadImpl]（下载系统复用零新内部）。
 *
 * **断续幂等**（r398_notes §2.2）：key 已在下载台账（DownloadRecord 磁盘账
 * 的内存镜像）=跳过；`_downloads` 该 key Running=在途跳过——重开 app/
 * 中断后重点只补缺，台卡徽记 N/M 随完成推进（台账变→StateFlow 重组）。
 *
 * **串行判词**：downloadImpl 的 `_downloads.value = _downloads.value + (…)`
 * 与 `_downloadRecords` 置顶都是读改写——并发多路会丢笔（**既有并发竞态=
 * 他人域不修只绕**；丢笔面=内存台账丢条→徽记虚低，磁盘账不丢重开自愈）；
 * 本编排逐条等终态（Done/Failed）再发下一条=编排侧规避；串行同时把
 * TDLib/流服务器负载压平（出门前一键长跑批不风暴）。每条 15 分钟兜底
 * （超时=该条仍在途如常后台磨，后续条继续发；既有 poller 终态覆盖竞态的
 * 窄窗（check 与 write 间被终态插入）最坏表现=该条状态卡 Running、本等待
 * 走超时——记录先于终态落盘故徽计/台账不受影响，如实注记）。
 *
 * @return 实发条数（0=已下满/全在途/台失联/纯频道台——UI Toast 如实分流）；
 * 发起本身 fire-and-forget（进度走既有 _downloads StateFlow+台账）
 */
internal fun FeedViewModel.downloadPlayStationImpl(stationId: String): Int {
    val station = playStations.firstOrNull { it.id == stationId }
    if (station == null) {
        android.util.Log.i("R398", "台下载:台失联(列表换代)静默返回")
        return 0
    }
    val items = stationDownloadItems(station)
    if (items.isEmpty()) return 0 // 纯频道台/空台（UI 侧守卫，这里兜底）
    val downloaded = _downloadRecords.value.mapTo(HashSet()) { it.key }
    val running = _downloads.value.filterValues { it is DownloadStatus.Running }.keys
    val pending = items.filter { it.key !in downloaded && it.key !in running }
    android.util.Log.i(
        "R398",
        "台下载:台尾=${stationId.takeLast(6)} 总=${items.size} " +
            "已下=${items.size - pending.size} 实发=${pending.size}（计数口径,零内容）",
    )
    if (pending.isEmpty()) return 0
    viewModelScope.launch(Dispatchers.IO) {
        for (item in pending) {
            downloadImpl(item) // 既有单条下载链（Running 置位同步发生在返回前）
            // 等该条终态再发下一条（串行判词见 KDoc；超时=继续后续，幂等补留下次）
            withTimeoutOrNull(STATION_DOWNLOAD_PER_ITEM_MS) {
                _downloads.first { map ->
                    map[item.key] is DownloadStatus.Done || map[item.key] is DownloadStatus.Failed
                }
            }
        }
        android.util.Log.i("R398", "台下载:批收口 条数=${pending.size}（计数口径）")
    }
    return pending.size
}

/**
 * 离线按单播段接线（openPlayQueueImpl 挂点，r398_notes §2.2 ③）：
 * 下载台账快照→key→本地绝对路径表→[applyDownloadedSegments] 纯函数换
 * Single 段 playUri。
 *
 * 台账读取=[_downloadRecords].value 快照（StateFlow.value 线程安全；挂点在
 * 开单 IO 协程内执行）；探针行=计数口径零内容（V70 票③断言面）。
 */
internal fun FeedViewModel.applyStationOfflinePlan(plan: List<PlaySegment>): List<PlaySegment> {
    val records: List<DownloadRecord> = _downloadRecords.value
    val singleCount = plan.count { it is PlaySegment.Single }
    if (records.isEmpty() || singleCount == 0) return plan
    val localUriByKey = HashMap<String, String>(records.size)
    for (r in records) localUriByKey[r.key] = localStationDownloadUri(r.path)
    val out = applyDownloadedSegments(plan, localUriByKey)
    val wired = plan.count { it is PlaySegment.Single && it.item.key in localUriByKey }
    android.util.Log.i(
        "R398",
        "开单离线接线:下载段=$wired/单段=$singleCount（计数口径,零内容）",
    )
    return out
}

/**
 * 下载记录路径→可播本地地址（VM 侧 Android API；**纯拼接还原**：
 * deleteDownloadedFile 同款先例——绝对路径（API 26-28 落盘形态）直出；
 * 相对展示路径（API 29+ "Movies/TGFeed/x"）拼外部存储根还原，零前缀假设
 * （R360 更名前 TGFed 旧档同解零迁移）。
 *
 * **不继承 Media3 相对路径疑病**（r398_notes §1.2 字节码双实证：相对路径按
 * 进程 CWD=/ 解析必打不开——下载页 toVideoItem 直塞相对路径为既有面疑病
 * 另批挂账，本编排层还原绝对路径）；含 '#'/'?' 的裸路径由既有收口链兜
 * （safeInitialPlayUri 拒收→ensurePlayable 短路改道 file:// 编码=R80 既有
 * 链零新逻辑）。
 */
@Suppress("DEPRECATION") // getExternalStorageDirectory：deleteDownloadedFile 同款用法（纯拼接还原）
internal fun localStationDownloadUri(path: String): String {
    val raw = File(path)
    return if (raw.isAbsolute) {
        path
    } else {
        File(Environment.getExternalStorageDirectory(), path).absolutePath
    }
}
