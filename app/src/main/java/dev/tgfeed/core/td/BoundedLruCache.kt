package dev.tgfeed.core.td

/**
 * 有界 LRU 缓存（R169 F7 家族共享件）。
 *
 * 背景（R168 病灶族普查 F7「无界资源增长」，P0-1 的通用底座）：core/td 网关里一批
 * 进程级 ConcurrentHashMap 缓存只进不出（coverBytesCache / searchBotCache /
 * commentsCache / channelAvatarCache…），长会话缓涨正是 R165 实锤灾变链
 * （native 耗尽 → TDLib 冻死 → 裸 send 永挂 → ANR）的燃料。
 *
 * 修法照抄仓内 R155 正解（CoverExtractor 内存 LRU / msgCoverMemCache / CoverStore
 * 磁盘 LRU 同族），本类是它们的通用化身，供各缓存收编（一处改全局生效，防
 * 「同一种数字与逻辑在多个文件各写一份」的补丁文化复发）：
 *  - 条数上限 [maxEntries]，超出淘汰最久未访问者（访问序 LRU：get 也刷新顺位）；
 *  - 写入前经 [transform] 加工（如封面字节降采样）；transform 在锁外执行，不阻塞
 *    其他读者，且约定自身兜底异常、失败返回原值；
 *  - 读写全经内部锁，get/set 语义与原 ConcurrentHashMap 的方括号语法兼容
 *    （operator get/set），现存读写点换类型零改动。
 *
 * 纯 JVM 无 Android 依赖；单测锚定见 BoundedLruCacheTest。
 */
internal class BoundedLruCache<K, V>(
    private val maxEntries: Int,
    private val transform: (V) -> V = { it },
) {
    init {
        require(maxEntries > 0) { "maxEntries 必须为正: $maxEntries" }
    }

    /** 访问序 LRU（get 也算访问会刷新顺位）；全部读写经 synchronized(map) */
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    /** 写入：transform 在锁外执行（解码/重编码耗时操作不得持锁），完成后原子入表并触发淘汰 */
    operator fun set(key: K, value: V) {
        val stored = transform(value)
        synchronized(map) { map[key] = stored }
    }

    /**
     * 移除单条并返回旧值（未命中返回 null）：TTL 过期清理等调用方主动逐条场景
     * （R428 searchBotCache 收编——LRU 只管容量上限，条目级过期语义仍归调用方）。
     */
    fun remove(key: K): V? = synchronized(map) { map.remove(key) }

    /** 当前条数（观测/单测用） */
    fun size(): Int = synchronized(map) { map.size }

    /** 清空（单测/整体重建用） */
    fun clear() = synchronized(map) { map.clear() }
}
