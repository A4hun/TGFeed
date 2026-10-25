package dev.tgfeed.ui.components

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 抽帧封面的 UI 侧共享表（Compose 快照感知）：抽帧完成后写入，
 * 所有读它的封面位自动重组显示新封面。
 * 数据源：core/playback/CoverExtractor（视频抽帧）。
 *
 * R155 上限保护：原为无界表，是本机会话级 native 堆只增不减的实锤泄漏源之一
 *（抽帧帧图在 API26+ 像素落 native 堆，单张 960 档约 1~4MB，长会话无上限可积到 GB 级）。
 * 现以 [MAX_ENTRIES] 为上限做最久未写淘汰（读路径不写快照，避免组合期快照写反模式）：
 *  - 上限按可见区上限定（Feed 前后页 + 分屏 9 格 + 搜索/榜单零星引用），64 张绰绰有余；
 *  - 被淘汰的封面位自动回落既有 minithumbnail/占位链（所有读取方都是 `?.let` 兜底，安全）；
 *  - 读取方零改动：map 仍是公开只读视图，写入一律走 [put]。
 */
object CoverStore {

    /** 封面表上限（张）：超出时淘汰最久未写入者 */
    private const val MAX_ENTRIES = 64

    /** 只读视图：Compose 快照感知，读取即订阅（与旧口径一致） */
    val map: Map<String, ImageBitmap> get() = backing

    /** 内部可变表（快照感知）；写入唯一入口是 [put] */
    private val backing = mutableStateMapOf<String, ImageBitmap>()

    /** 写入顺序登记（非快照结构，避免读路径触发快照写反模式）；value 仅占位 */
    private val writeOrder = LinkedHashMap<String, Boolean>(64, 0.75f, false)

    /**
     * 写入/更新一张抽帧封面（唯一写入入口）。超上限同步淘汰最久未写条目。
     * 主线程/IO 线程都可调（内部加锁；快照写本身线程安全）。
     */
    @Synchronized
    fun put(key: String, value: ImageBitmap) {
        if (backing.containsKey(key)) {
            // 原位更新不算新写入：不动顺序（同一条目重抽封面不该刷新它的"年龄"）
            backing[key] = value
            return
        }
        writeOrder.remove(key)
        writeOrder[key] = true
        backing[key] = value
        while (backing.size > MAX_ENTRIES) {
            val eldest = writeOrder.keys.firstOrNull() ?: break
            writeOrder.remove(eldest)
            backing.remove(eldest)
        }
    }

    /** 是否已有该条目的抽帧封面（写前判重用，与旧 `map.containsKey` 口径一致） */
    fun contains(key: String): Boolean = backing.containsKey(key)
}
