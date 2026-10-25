package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

/**
 * R337 已看查重统一口（用户 19:12 实锤「刷过了就不要重复推」的语义唯一出处）。
 *
 * 收敛背景（§一类问题）：「item.key 精确命中 ∨ fileUniqueId 指纹命中」的双轨
 * 已看判定此前在 8 处手写同款（loadMore pass 两段/已看命中计数/R2 兴趣召回/
 * R3 图谱召回/历史回溯注入/追更置顶两段/两处快照落盘滤——另有网关侧
 * [dev.tgfeed.core.td.filterSeenIdentities] 与 R216 展开前预滤两处孪生）——
 * 同一判定一处定义别处只准调，三链（视频/图片/音频 tab）与四注入口
 * （正页/回溯/召回/网关直拉）由本件钉成同一把尺。
 *
 * 判定口径（与 R150 网关源头过滤逐字节同义）：
 *  - key 轨：`item.key` 精确命中（"tg:<chatId>:<messageId>"）；
 *  - 指纹轨：`fileUniqueId` 非空且命中（TG 服务端级文件身份证——同片被搬运到
 *    别的频道 key 不同、指纹相同，跨频道转发同片也算看过；无指纹条目只走 key 轨）。
 *
 * 严格语义（R337 定案，取代 R94/R216 触底自愈的静默放宽）：已看排除
 * **永不因供给饥饿而解除**——旧路在连续颗粒无收时置 rewatchRelaxSeen=true
 * 把五处消费口同时洞穿（稀疏类型几页撞墙即武装，已刷条目整窗回灌；视频池深
 * 永不武装=「视频 tab 无此问题、图/音有问题」的机理）。本件 [hardExcludeOn]
 * 的签名即契约：**唯一输入=用户开关**——饥饿的诚实处置是断链停供+观测行
 * （loadMore 尾段「R337 供给饥饿」），重温只走用户显式入口（空态
 * 「新内容刷完了，点击重温已看内容」钮=setExcludeWatched(false) 全局开关）。
 */
internal object SeenDedupPolicy {

    /**
     * 双轨已看判定：key 精确命中 或 内容指纹命中。
     * [seenKeys]/[seenUids] 为空集时恒 false（零开销直通：开关关/底账空）。
     */
    fun isSeen(item: VideoItem, seenKeys: Set<String>, seenUids: Set<String>): Boolean =
        item.key in seenKeys ||
            (item.fileUniqueId.isNotEmpty() && item.fileUniqueId in seenUids)

    /**
     * 供给口查重（「会话内不重复供给 ∧ 已刷不再推」的判定体）：loadMore 正页
     * kept 过滤的查重段。[servedKeys]=当前列表 key 集（会话已供给），另两参=
     * 已看底账双轨。单频道/本地源场景已看集为空时退化为纯会话判重（key 不在
     * 当前列表），与旧行为逐字节等价。
     */
    fun notServedAndUnseen(
        item: VideoItem,
        servedKeys: Set<String>,
        seenKeys: Set<String>,
        seenUids: Set<String>,
    ): Boolean = item.key !in servedKeys && !isSeen(item, seenKeys, seenUids)

    /**
     * 已看硬剔除是否生效：**唯一输入=用户「已看内容不再出现」开关**。
     *
     * R337 严格契约：签名单参即设计——不存在任何「供给状态/饥饿计数/兜底态」
     * 参数能翻转本判定（旧 rewatchRelaxSeen 静默解除路已随本件退役）。供给
     * 饥饿（池被滤空）不放宽排除，诚实降级=停链换显式重温入口。
     */
    fun hardExcludeOn(watchSwitchOn: Boolean): Boolean = watchSwitchOn
}
