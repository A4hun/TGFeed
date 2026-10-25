package dev.tgfeed.core.feed

/**
 * R262b 死/僵尸频道处置策略（纯函数，零 Android 依赖，单测锚定 DeadChannelPolicyTest）。
 *
 * 背景（全库体检实锤，r262_notes.md）：meta exhausted=1 且 items<100 的频道 166 个
 * （0 条目 103 个）——其中 101 个是媒体清扫全相位到底（mediaPass=3）仍零产出的
 * **真·无媒体死档**（文字频道/媒体已清空的频道），exhausted 出队后仍每 6h 头刷
 * 空转烧请求；另有 3 个 exhausted=1 但媒体清扫相位未走完（mediaPass<3）且低产的
 * **提前终态嫌疑**（历史 bug 期产物），证据不足按复活重爬处置（自证：真空则
 * 运行期标死钩当场收口，见 DeepSearchIndex.persistPage）。
 *
 * 本文件是三处判定的**唯一实现处**（DeepSearchIndex 薄委托，防多份数字漂移）：
 *  1. [shouldMarkDead]：标死——全相位清扫到底仍零产出且非采样的完成态频道；
 *  2. [shouldReviveIncomplete]：复活——提前终态嫌疑（相位未走完+低产）清完成态
 *     回未到底队列重爬自证（v10 迁移一次性分诊用）；
 *  3. [headRefreshIntervalMs]：死档头刷降频探活（6h→7d）；探到新鲜条目由
 *     persistPage 的 fresh>0 钩自动复活（dead=0），死档不是单向门。
 */
internal object DeadChannelPolicy {

    /** 媒体清扫完成相位值（0视频/1图片/2音频全到底后推进到 3；DeepSearchIndex.mediaSweep 口径） */
    const val SWEEP_DONE_PASS = 3

    /** 提前终态复活判定的低产阈值（条）：<100 且相位未走完才够「证据不足」 */
    const val LOW_ITEM_REVIVE_FLOOR = 100

    /** 死档探活头刷间隔：7 天（正常头刷 6h 的 1/28——101 个死档每天省 ~404 次空刷请求） */
    const val DEAD_PROBE_INTERVAL_MS = 7L * 24 * 60 * 60_000L

    /**
     * 标死判定：完成态（[exhausted]）+ 全相位清扫到底（[mediaPass]>=[SWEEP_DONE_PASS]
     * =三类型媒体全扫过仍零产出）+ 零条目 + 非采样（采样频道是刻意的部分覆盖，
     * 零产出可能是采样窗口没命中，不算死档证据）。
     */
    fun shouldMarkDead(exhausted: Boolean, mediaPass: Int, sampled: Boolean, itemCount: Int): Boolean =
        exhausted && itemCount == 0 && mediaPass >= SWEEP_DONE_PASS && !sampled

    /**
     * 复活判定（v10 迁移一次性分诊）：完成态但清扫相位未走完（[mediaPass]<3——
     * 提前终态嫌疑）且低产（<[LOW_ITEM_REVIVE_FLOOR]）。清 exhausted 回未到底队列
     * 重爬自证；≥100 条且相位未走完的 86 个是旧线性爬（GetChatHistory 全程到底，
     * 不涉清扫相位）的合法完成态，不在此列（体检实锤，r262_notes.md 挂账声明）。
     */
    fun shouldReviveIncomplete(exhausted: Boolean, mediaPass: Int, itemCount: Int): Boolean =
        exhausted && mediaPass < SWEEP_DONE_PASS && itemCount < LOW_ITEM_REVIVE_FLOOR

    /**
     * 头刷间隔（毫秒）：死档 7d 探活 / 活档 [baseIntervalMs] 原样（分档差异化由
     *  IndexTierPolicy.headRefreshIntervalMs 上游算好喂入，本函数只做死档降频一刀）。
     */
    fun headRefreshIntervalMs(dead: Boolean, baseIntervalMs: Long): Long =
        if (dead) DEAD_PROBE_INTERVAL_MS else baseIntervalMs

    /** 死档复活：任何爬取路径采到新鲜条目（fresh>0）即清死档标记（死档不是单向门） */
    fun shouldReviveDeadOnFresh(fresh: Int): Boolean = fresh > 0
}
