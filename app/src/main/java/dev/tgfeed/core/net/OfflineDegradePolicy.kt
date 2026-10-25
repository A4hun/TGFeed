package dev.tgfeed.core.net

/**
 * R324 离线降级文案与决策单一出处（弱网离线可玩——七面降级矩阵的「呈现」半体）。
 *
 * 纪律§一 Q3：同一文案族（离线提示行/数据时点标注/禁用动作提示）不许在五个
 * 消费面各写一份——全部收敛为本纯函数件（零 Android 依赖，JVM 单测锚
 * OfflineDegradePolicyTest）。消费面：FeedScreen（feed 提示行+空态副文案）/
 * ChannelHomeScreen（频道页 banner）/SearchScreen（搜索离线注）/VideoPage 族
 * （播放器离线终态文案）/SettingsScreen（云同步离线行）。
 *
 * 零编造红线（R312 哲学同源）：离线态一律标注真实数据时点——有快照落盘时刻或
 * 本会话到货时刻就带「N 小时前的快照」字样；无任何时点（0=没数据）就只说
 * 「离线前拉到的内容」，绝不编造时点；在线恒 null（零视觉噪声）。
 */
object OfflineDegradePolicy {

    /**
     * 数据时点的人类语言标签（feed banner 的诚实原料）：
     * dataAtMs ≤ 0 或在未来（时钟漂移）= null（无时点，不带标签不编造）；
     * 否则按分钟/小时/昨天/天分档。
     */
    fun stalenessLabel(dataAtMs: Long, nowMs: Long): String? {
        if (dataAtMs <= 0L) return null
        val diffMin = (nowMs - dataAtMs) / 60_000L
        if (diffMin < 0L) return null // 未来时刻（时钟漂移）：不带时点
        return when {
            diffMin < 1L -> "刚刚"
            diffMin < 60L -> "$diffMin 分钟前"
            diffMin < 24L * 60L -> "${diffMin / 60L} 小时前"
            diffMin < 2L * 24L * 60L -> "昨天"
            else -> "${diffMin / (24L * 60L)} 天前"
        }
    }

    /**
     * feed 离线提示行（FeedScreen 顶部胶囊）：
     * 在线 → null（不显示）；离线 + 列表空 → null（空态走 [feedOfflineEmptyHint]
     * 副文案，banner 不叠双行）；离线 + 有内容 → 按时点标签给「离线中 · 展示的是
     * N 小时前的快照」/无时点给「离线中 · 列表为离线前拉到的内容」。
     */
    fun feedOfflineBannerLine(
        online: Boolean,
        itemsEmpty: Boolean,
        dataAtMs: Long,
        nowMs: Long,
    ): String? {
        if (online || itemsEmpty) return null
        val label = stalenessLabel(dataAtMs, nowMs)
        return if (label != null) {
            "离线中 · 展示的是 ${label}的快照"
        } else {
            "离线中 · 列表为离线前拉到的内容"
        }
    }

    /** feed 空态离线副文案（离线且列表空时的解释行；在线 → null） */
    fun feedOfflineEmptyHint(online: Boolean): String? =
        if (online) null else "离线中，暂无缓存内容——联网后自动恢复"

    /** 频道页离线 banner（R212 索引兜底供给的诚实标注；在线 → null） */
    fun channelHomeOfflineLine(online: Boolean): String? =
        if (online) null else "离线中 · 展示的是本地缓存内容"

    /** 搜索等待文案的离线后缀（频道腿 30s 硬顶期间如实告知；在线 → 空串） */
    fun searchOfflineSuffix(online: Boolean): String =
        if (online) "" else "（离线中）"

    /** 搜索结果图例的离线注行（本地结果可用、频道腿不可用的口径；在线 → null） */
    fun searchLegendOfflineNote(online: Boolean): String? =
        if (online) null else "离线中 · 频道搜索暂不可用，仅显示本地结果"

    /**
     * 播放器离线终态病因（R78 分诊家族新成员：离线=「此刻不可重试」的稳定病因）：
     * offline=false → null（在线走既有网络类退避重试链）；true → 离线文案。
     * 注意：文案本身说「点按重试」——离线终态胶囊保持可点（联网后人工重试有
     * 真实意义），与解码失败类「不假装在努力」的不可点终态区分（离线是环境态
     * 不是内容坏，联网即愈）。
     */
    fun playerOfflineFatalText(offline: Boolean): String? =
        if (!offline) null else "离线中，此内容未缓存——联网后点按重试，或先换一条看"

    /** 云同步卡的离线行（MultiSync 网络失败静默的诚实出口；在线 → null） */
    fun syncOfflineLine(online: Boolean): String? =
        if (online) null else "离线中 · 同步已暂停，联网后自动恢复"
}
