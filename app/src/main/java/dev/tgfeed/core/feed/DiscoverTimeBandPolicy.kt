package dev.tgfeed.core.feed

import dev.tgfeed.core.td.ChannelBrief

/**
 * R215 ① 发现页时段切换条·纯策略（时间维度融入式设计，取代 R213 三 tab 方案）。
 *
 * 设计哲学（架构师原话，用户拍板）：时间不是用户「去」的地方，是内容「自带」的
 * 属性——候选卡**原位重排**，不另开门。三档时段 chip 只是排序权重的切换：
 *  - [WEEK] 本周：近 7 天活跃度重排；
 *  - [MONTH] 全月：近 30 天活跃度；
 *  - [YEAR] 全年（默认）：丰度优先=现状，**零变化**（默认档不触发任何查询与重排）。
 *
 * R229「此刻」档移除（用户终审：1h 窗的「正在更新」语义由追更 tab 承接——
 * 频道动态卡流才是「此刻」的天然居所；时段条四档收敛三档，枚举值 NOW 直接删除，
 * DiscoverScreen chips 行与单测同步——消费方按 values() 渲染，零散引用 grep 实证全清）。
 *
 * 数据口径（红线）：全部走 R202 [DeepSearchIndex.timeWindowItems] 共享原语
 * （查询口见 FeedDiscoverTimeBandPart——窗口聚合按频道计数），本文件零 IO。
 * 时间族共享常量单一出处引用：[TimeWindowPolicy.DAY_SEC]（勿再开私有镜像）。
 */
enum class DiscoverTimeBand(
    /** chip 文案（「本周|全月|全年」，UI 直取） */
    val label: String,
    /** 窗口秒长；null = 全年默认档（不查询不重排——现状即答案） */
    val windowSec: Long?,
) {
    WEEK("本周", 7L * TimeWindowPolicy.DAY_SEC),
    MONTH("全月", 30L * TimeWindowPolicy.DAY_SEC),
    YEAR("全年", null),
}

/**
 * 候选重排（时间权重主导）：窗口内计数多者在前，无计数者殿后（保持原序）。
 *
 * - 稳定排序（sortedByDescending 稳定）：平手不乱序——时段切换只做「时间权重
 *   抬升」，不引入新随机性；[DiscoverTimeBand.YEAR] 档调用方直接不重排（零变化）。
 * - 计数表空（索引未就绪/窗口无样本，R183 三纪律的空手口径）→ 原序返回
 *   （诚实降级：切换不崩不空窗，只是本轮无时间信号）。
 * - 「与金矿候选交并」语义：counts 的键集 ⊇/⊆ 候选集均可——交集命中者获得
 *   时间权重，未命中（计数 0）按原序殿后；counts 里非候选的频道无卡片承载，
 *   自然丢弃（发现页只展示金矿候选卡）。
 */
fun rerankCandidatesByTimeWindow(
    candidates: List<ChannelBrief>,
    counts: Map<Long, Int>,
): List<ChannelBrief> {
    if (counts.isEmpty()) return candidates
    return candidates.sortedByDescending { counts[it.id] ?: 0 }
}
