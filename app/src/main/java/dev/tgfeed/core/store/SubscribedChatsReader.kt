package dev.tgfeed.core.store

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 订阅频道 chatId 只读器（R203 收敛共享件）。
 *
 * 背景（AGENT-DISCIPLINE §一「同逻辑不许各写一份」）：filesDir/store/subscriptions.json
 * 的只读解析原先私有在 DailyDigest（R63）；R203 那年今日族的页面查询与周年推送同样
 * 需要「订阅频道集合」，第三份拷贝出现前先收口到这里——DailyDigest 与 OnThisDay
 * 两族共用同一解析口径。
 *
 * 口径不变（与 LocalStore.loadSubscriptions 的坏行防御一致）：
 *  - LocalStore 是 subscriptions.json 的唯一写入方且归 FeedViewModel 持有——外部
 *    模块一律**只读直解析**，不建第二个 LocalStore 实例（防缓存双源/写竞态）；
 *  - 文件不存在 → 空表；损坏/坏行（chatId=0）→ 跳过该行，不崩（宁少勿炸）；
 *  - 去重保序（LinkedHashSet，首现序=订阅表持久化顺序）。
 */
object SubscribedChatsReader {

    /**
     * 订阅频道 chatId 列表（去重保序）。
     * IO 线程调用（读盘+解析；文件几十 KB 量级，调用方自持节流，不做缓存——
     * 两族消费方都是低频后台/进页各一次，缓存反而引入失效同步问题）。
     */
    fun load(context: Context): List<Long> {
        val f = File(context.filesDir, "store/subscriptions.json")
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = LinkedHashSet<Long>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optLong("chatId", 0L) ?: 0L
                if (id != 0L) out += id
            }
            out.toList()
        }.getOrDefault(emptyList())
    }
}
