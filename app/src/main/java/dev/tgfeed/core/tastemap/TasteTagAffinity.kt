package dev.tgfeed.core.tastemap

/**
 * R233 内容关联弧判定（口味星图二期·星际考古·纯函数，零 Android 依赖单测可跑）。
 *
 * 两恒星（频道）共享 tag → **内容关联边**（设计 §3.2）：与互链/转发边（结构性关系：
 * 频道主主动引用，实线+流光）相对，这是**语义性关系**——内容自己撞出来的共振，
 * 渲染为虚线弧。判据（设计原文口径）：
 *
 *  - **Jaccard(A,B) ≥ [JACCARD_MIN]**（tag 集重合度）；**或**
 *  - **共享任一 top-3 tag**（焦点星最热的 3 个话题，对方也发过——比纯 Jaccard
 *    语义直白且对大频道公平：两个各几百 tag 的频道 Jaccard 天然稀释，但「最热
 *    话题相同」仍是实打实的共振）。
 *
 * 防边爆炸（设计 §3.2「平时不画」）：本函数只产出**焦点星单端的弧目标表**（调用方
 * 焦点态限定——点选/飞近某恒星时才算它一家的）；表内按「共享 top-rank 优先 →
 * Jaccard 降序 → chatId 升序」留重 [MAX_ARCS] 条（确定性序，同数据同弧）。
 *
 * 指纹来源=近端样本口径（TasteTagPlanets.fingerprintOf，「按近端样本观测」诚实
 * 标注——探针档/未回填频道指纹薄 → 弧稀疏=没有证据不冒充关联）。
 */
object TasteTagAffinity {

    /** Jaccard 阈值（设计原文 0.3） */
    const val JACCARD_MIN = 0.30f

    /** 焦点星弧目标上限（焦点态单端 ≤8 条——全图 200 上限是设计的三期全图口径，
     *  焦点态 8 条已超过一眼可读的密度；超限按确定性序留重） */
    const val MAX_ARCS = 8

    /**
     * 一条弧目标（焦点星 → 对方恒星）。
     *
     * @property chatId 对方频道 id
     * @property jaccard tag 集 Jaccard（0..1，诊断/次级排序用）
     * @property sharedTag 共享的焦点星 top-3 tag（null=纯 Jaccard 过线无 top-3 共享；
     * 弧色/信息行锚——「你们共振在哪」）
     * @property topRankShared 共享的 top-rank（1..3；0=无 top-3 共享，纯 Jaccard 弧）
     */
    class ArcTarget(
        val chatId: Long,
        val jaccard: Float,
        val sharedTag: String?,
        val topRankShared: Int,
    )

    /**
     * 焦点星 → 弧目标表（纯函数，确定性）：
     *
     * @param focusTags 焦点星 tag 指纹（TasteTagPlanets.fingerprintOf 产物，fold 键）
     * @param focusChatId 焦点频道 id（自家不来自环）
     * @param others 全部已知指纹（chatId → 指纹；LRU 系统 ∪ 后台预取大频道 ∪ 按需现算）
     */
    fun arcTargets(
        focusTags: Map<String, Int>,
        focusChatId: Long,
        others: Map<Long, Map<String, Int>>,
    ): List<ArcTarget> {
        if (focusTags.isEmpty() || others.isEmpty()) return emptyList()
        // 焦点星 top-3（count 降序 → 键升序破平，确定性）
        val top3 = focusTags.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3)
            .mapIndexed { rank, e -> e.key to (rank + 1) }
        val out = ArrayList<ArcTarget>(others.size)
        for ((chatId, tags) in others) {
            if (chatId == focusChatId || tags.isEmpty()) continue
            var inter = 0
            // 并集=|A|+|B|−|A∩B|；小表遍历大表查（HashMap O(1)）
            if (tags.size <= focusTags.size) {
                for (k in tags.keys) if (focusTags.containsKey(k)) inter++
            } else {
                for (k in focusTags.keys) if (tags.containsKey(k)) inter++
            }
            val union = focusTags.size + tags.size - inter
            val jac = if (union <= 0) 0f else inter.toFloat() / union
            var sharedTag: String? = null
            var topRank = 0
            for ((tag, rank) in top3) {
                if (tags.containsKey(tag)) {
                    sharedTag = tag
                    topRank = rank
                    break
                }
            }
            if (jac >= JACCARD_MIN || topRank > 0) {
                out += ArcTarget(chatId, jac, sharedTag, topRank)
            }
        }
        out.sortWith(
            compareByDescending<ArcTarget> { it.topRankShared }
                .thenByDescending { it.jaccard }
                .thenBy { it.chatId },
        )
        return if (out.size > MAX_ARCS) out.take(MAX_ARCS) else out
    }
}
