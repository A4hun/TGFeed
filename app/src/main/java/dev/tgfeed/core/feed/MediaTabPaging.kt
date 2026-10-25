package dev.tgfeed.core.feed

/**
 * R216 媒体 tab 供给链轮尾决策（纯函数，零依赖——R212 ChannelHomePaging 同模式）。
 *
 * 背景（用户走查实锤「音频 tab 只加载固定数量、上滑不触发继续加载」的供给侧根因族）：
 *  1. **已看墙形态**：媒体 tab（音频/图文）翻页链路页页回货，但货全被已看源头过滤
 *     （R150）+ 判重吃光——0 入列、游标照常推进、`nextCursor` 永不为 null（allEnded
 *     需全频道服务端真到底，已看墙永远到不了）。旧 R94 触底自愈的 stuck 判据只认
 *     `mediaTabExhausted`（allEnded）→ 自愈永不武装 → 列表原地冻死在固定数量；
 *  2. **干爬成本形态**：0 入列的轮照旧满额连拉 12 窗（12×40 SearchChatMessages
 *     + 每频道最多 4×GetChatHistory 的 caption 展开，实测单窗 19~50s）——数分钟
 *     零产出且 `_loading` 闸锁死一切触发器；
 *  3. **网络失败≠墙**：拉取异常/超时轮一页都没回来，不该计入自愈武装（网络恢复
 *     后还有货），也不能当干墙熔断的依据。
 *
 * 本文件只做**决策**；状态写回（武装重温兜底/收轮）由 FeedViewModel.loadMore 执行。
 */
object MediaTabPaging {

    /**
     * 供给受阻判定（R94 触底自愈武装的 stuck 信号，R216 修正口径）：
     *  - 抽空终态（[mediaTabExhausted]：nextCursor==null 的全频道 allEnded）→ 受阻
     *    （旧口径逐字保留——抽空探测轮颗粒无收的既有形态）；
     *  - 页回而 0 入列（[pagesArrived] > 0：拉取真成功、货被过滤链吃光）→ 受阻
     *    （新口径：已看墙形态——游标照常推进永远到不了 allEnded，旧判据在此形态下
     *    自愈永不武装=「固定数量后上滑不加载」的直接根因）；
     *  - 纯网络失败轮（页数为 0）→ 不受阻：网络恢复后还有货，误开重温会把
     *    「暂时拉不到」误判成「语料库抽干」。
     */
    fun supplyStuck(mediaTabExhausted: Boolean, pagesArrived: Int): Boolean =
        mediaTabExhausted || pagesArrived > 0

    /**
     * 干墙熔断（本轮连拉是否提前收轮）：媒体 tab、本轮 0 入列、列表非空、连拉页数
     * 已达 [cap] → 收轮（已看/判重墙的干爬半价，让供给受阻更快进入轮尾判定——
     * 自愈武装计数/链式断链闸接管）。冷启动（列表为空——稀疏类型靠满额连拉找
     * 首批货）与有入列轮（既有队列保底 break 先行）不收，行为不变。
     */
    fun dryPullsBreak(
        useMediaFetch: Boolean,
        appendedThisRound: Int,
        itemsSize: Int,
        pulls: Int,
        cap: Int = MEDIA_TAB_DRY_PULLS_CAP,
    ): Boolean = useMediaFetch && appendedThisRound == 0 && itemsSize > 0 && pulls >= cap
}
