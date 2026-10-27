package dev.tgfeed.core.feed

// ---------------- R392 #5 自动连播链:接棒解析纯函数 ----------------
//
// 定位(r392b_notes §2.2):PlayQueue.chainTo 三态(null=不接/CHAIN_NEXT=同列表
// 下一台/指定台 id)→ 接棒目标台的解析收口。纯 Kotlin 零 Android 依赖(JVM
// 单测直测,buildPlayPlan/TodayQueueGen 同纪律);消费方=VM 层段尽位
// (FeedPlayChainPart.advancePlayChainAtQueueEnd)——解析与消费分立:本件只答
// 「接谁」,开台走一期开单链(openPlayStationImpl)。
//
// 判词链(r392b_notes §2.2 定案):
//  - **显示序=updatedAt 降序**:CHAIN_NEXT 的「同列表下一台」按台列表显示序
//    (QueueStationsScreen 发布序=updatedAt 降序)解析——用户配置时所见列表
//    即解析序,所见即所得;断点记账不推进 updatedAt(setResume 一期口径)
//    =播放期间台序稳定,解析序不因播放漂移。
//  - **不循环**:当前已是显示序末台→无下家→null(自然停);循环开台=后续期
//    诚实挂账,不在本件隐式做。
//  - **失联诚实降级**:指定台 id 不在列表(被删/坏档)→null 自然停,不硬指。
//  - **自指防御**:chainTo==自身 id=配置谬误,跳过(返回 null)不接。
//  - **空台判词**:接棒目标是空台时本件仍返回字面目标(不做跳跃选择——
//    「接谁」是用户显式配置,替用户跳过谁=越权);空台死端由开单链空单守卫
//    兜住(openPlayQueueImpl plan.isEmpty() 静默不切=链停在空台,自然停语义)。

/**
 * 接棒解析(纯函数):活跃台的 [PlayQueue.chainTo] 三态 → 接棒目标台。
 *
 * @param active 播完的台(段尽的那台;取 VM 侧活跃台实况)
 * @param stations 台列表(底账全集;CHAIN_NEXT 的「下一台」按显示序=updatedAt
 *   降序解析——与 QueueStationsScreen 发布序同源)
 * @return 接棒目标台;null=不接(自然停:不接/无下家/失联/自指/活跃台不在列表)
 */
fun resolvePlayChain(active: PlayQueue, stations: List<PlayQueue>): PlayQueue? {
    val chainTo = active.chainTo ?: return null // 不接:播完即停(一期语义保留)
    if (chainTo == PlayQueue.CHAIN_NEXT) {
        // 同列表下一台:显示序=updatedAt 降序(所见即所得判词);
        // 稳定排序保并列(同时刻)台底账原序
        val ordered = stations.sortedByDescending { it.updatedAt }
        val idx = ordered.indexOfFirst { it.id == active.id }
        if (idx < 0) return null // 活跃台不在列表(哨兵/失联)——无处可接
        if (idx >= ordered.size - 1) return null // 已是显示序末台:无下家自然停(不循环)
        return ordered[idx + 1]
    }
    // 指定台:自指=配置谬误跳过;失联(被删/坏档)=null 诚实降级自然停
    if (chainTo == active.id) return null
    return stations.firstOrNull { it.id == chainTo }
}
