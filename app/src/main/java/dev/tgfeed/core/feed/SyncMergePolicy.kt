package dev.tgfeed.core.feed

/**
 * R300 多设备同步合并裁决纯件（唯一出处，JVM 单测锚；与 R287 IndexMergePolicy 同方法论）。
 *
 * 配方（design-multi-device-sync.md §3 定稿）：
 * 1. 集合型域（收藏/稍后再看/屏蔽三件套/追更订阅）：per-key LWW + 删除墓碑——
 *    每个 key 记 {added ts, removed ts(墓碑)}，在/不在由 max(added, removed) 裁决；
 * 2. 单调量（追更已读水位 lastSeenMessageId 等游标）取 max，无冲突可言；
 * 3. 平局保本机（两侧 ts 相等不动本机——不动=稳，与 R287「双非空保本机」同哲学）；
 * 4. 时钟异常熔断：对端 ts 超前本机墙钟超过 [MAX_CLOCK_SKEW_MS] 按不可信处理（拒收），
 *    防某台设备时钟错乱把全网状态洗到未来（未来 ts 会恒赢 LWW）；
 * 5. 存量无 ts 条目（favTs 迁移前 ts=0=未知）存在性并集兜底 [mergeSetKey]：
 *    本机在册但无 ts 的条目，远端墓碑不误杀、远端未知存在也不判删——宁多勿丢
 *    （迁移期数据安全优先于精确收敛；新动作带真 ts 后自然进入正轨 LWW）。
 */
object SyncMergePolicy {

    /** 墓碑留存期：30 天（沿用 ProgressSync V2 既有口径，不新抄私份） */
    const val TOMBSTONE_TTL_MS: Long = 30L * 24 * 3600 * 1000

    /** 时钟漂移熔断阈值：对端 ts 超前本机墙钟超过 10 分钟即按异常拒收（平局保本机的推广） */
    const val MAX_CLOCK_SKEW_MS: Long = 10L * 60 * 1000

    /** 单条同步消息正文体积安全上限（字符）：TG 文本消息硬顶 4096，留 marker/页脚余量 */
    const val MAX_PAYLOAD_CHARS: Int = 3800

    /** 集合型域单 key 合并结论：[present]=合并后是否在册；[added]/[removed]=合并后戳对（落台账用） */
    data class KeyMerge(val present: Boolean, val added: Long, val removed: Long)

    /**
     * per-key LWW + 删除墓碑 + 存量未知戳存在性并集裁决。
     *
     * @param localPresent 本机当前是否在册
     * @param localAdded 本机在册戳（0=未知——存量迁移数据；不在册时传 0）
     * @param localRemoved 本机墓碑戳（无墓碑传 0）
     * @param remoteAdded 云端加入戳（0=云端无记录或未知戳存在声明，以 [remoteClaimed] 区分）
     * @param remoteClaimed 云端「a」表是否声明了该 key 的存在（含 ts=0 的未知存在）
     * @param remoteRemoved 云端墓碑戳（无传 0）
     * @param nowMs 本机墙钟（时钟熔断用）
     */
    fun mergeSetKey(
        localPresent: Boolean,
        localAdded: Long,
        localRemoved: Long,
        remoteAdded: Long,
        remoteClaimed: Boolean,
        remoteRemoved: Long,
        nowMs: Long,
    ): KeyMerge {
        // 时钟熔断：对端任一戳超前墙钟超阈值 → 该 key 的云端侧整组不可信（拒收）
        val remoteOk = remoteAdded - nowMs <= MAX_CLOCK_SKEW_MS && remoteRemoved - nowMs <= MAX_CLOCK_SKEW_MS
        val ra = if (remoteOk) remoteAdded else 0L
        val rr = if (remoteOk) remoteRemoved else 0L
        val rc = remoteOk && remoteClaimed

        // 存量未知戳在册条目：存在性并集兜底——宁多勿丢。远端墓碑不误杀
        //（未知 vs 已知无法定序，错杀代价远大于多留）；顺手收编远端已知加入戳。
        if (localPresent && localAdded == 0L) {
            return KeyMerge(present = true, added = maxOf(0L, ra), removed = localRemoved)
        }

        val added = maxOf(localAdded, ra)      // 新者胜；平局保本机（相等不换位）
        val removed = maxOf(localRemoved, rr)
        val present = when {
            removed > added -> false                       // 墓碑赢：删除传播
            added > 0L -> true                             // 已知加入戳赢
            else -> localPresent || rc                     // 全未知：存在性并集（宁多勿丢）
        }
        return KeyMerge(present = present, added = added, removed = removed)
    }

    /** 单调游标裁决（已读水位 lastSeenMessageId）：取 max；负值/异常天然免疫 */
    fun mergeCursor(local: Long, remote: Long): Long = maxOf(local, remote)

    /** 墓碑剪枝：超期（>TTL）可丢（量控淘汰由调用方按 removed 升序掐最旧） */
    fun tombstoneExpired(removedTs: Long, nowMs: Long): Boolean =
        removedTs > 0L && removedTs < nowMs - TOMBSTONE_TTL_MS

    // ---------------- 载荷体积治理（4096 字符硬顶，design §2.1/§5 快照分片兜底） ----------------

    /**
     * 把编码好的单行条目列表按 [maxChars] 贪心切片（不切断单条；输入须已按 key 稳定序排好，
     * 切片确定性=多设备对同一状态切出同样的片）。空输入返回空表（调用方短路不建消息）。
     */
    fun shardPayload(lines: List<String>, maxChars: Int = MAX_PAYLOAD_CHARS): List<List<String>> {
        if (lines.isEmpty()) return emptyList()
        val shards = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var curLen = 0
        for (line in lines) {
            if (curLen > 0 && curLen + line.length + 1 > maxChars) {
                shards += cur
                cur = ArrayList()
                curLen = 0
            }
            cur += line
            curLen += line.length + 1
        }
        if (cur.isNotEmpty()) shards += cur
        return shards
    }
}
