package dev.tgfeed.core.feed

/**
 * R266 跨频道转发冗余存储层合并·判定纯函数（storage-merge-design.md 终审稿的
 * 代码落形；D1~D8 推荐值终审通过）。**JVM 可单测**（零 Android 依赖）。
 *
 * 裁决口径唯一出处（防多处私抄漂移）：
 *  - D1 canonical=组内首发：组内 (dateEpochSec ASC, messageId ASC) 最小行——
 *    与 R204 FirstSeenLedger 首发口径同源；选定后**冻结不换马**（后发现更早
 *    副本只收编为 refs，不换 canonical——换马收益=caption 质量微差，成本=
 *    refs 全组重挂+tag 重算，不值）；
 *  - D2 转发者独立 caption **忠实保留**：refs.caption 覆盖列 NULL=与 canonical
 *    相同；非 NULL（含空串）=本发布实际文案（回滚保真+频道页语义零变化；
 *    实测保留成本 ≈55MB，44.2% 组有独立文案）；
 *  - R12 冲突防御闸：组内 mediaType/sizeBytes 有任何分歧=指纹误判嫌疑，
 *    **宁不并不错并**（快照实测 750,560 组双字段零冲突——此闸只防未来脏数据）。
 */
object RepostMergePolicy {

    /**
     * D1 canonical 裁决：组内首发=(dateEpochSec, messageId) 最小者。
     * 同刻同组（转发风暴同秒多发）按 messageId 升序稳定兜底（R204 W4 同口径：
     * 首发判定别被转发时差骗过）。空组调用=调用方 bug（批迁/写入路径都先判 ≥2）。
     */
    fun <T> canonicalOf(rows: List<T>, dateOf: (T) -> Long, msgIdOf: (T) -> Long): T {
        require(rows.isNotEmpty()) { "canonicalOf 空组" }
        var best = rows[0]
        for (i in 1 until rows.size) {
            val r = rows[i]
            val d = dateOf(r)
            val bd = dateOf(best)
            if (d < bd || (d == bd && msgIdOf(r) < msgIdOf(best))) best = r
        }
        return best
    }

    /**
     * D2 caption 覆盖列取值：与 canonical 文案相同 → null（覆盖列 NULL=共享，
     * 零冗余存储）；不同 → 本发布实际文案（**含空串**——转发者清空文案也是
     * 独立语义，忠实保留）。
     */
    fun captionOverride(itemCaption: String, canonCaption: String): String? =
        if (itemCaption == canonCaption) null else itemCaption

    /**
     * R12 组可合并闸：组内全部 (mediaType, sizeBytes) 一致才可收编。
     * 任何一对分歧=同指纹不同文件的实锤嫌疑（fuid 可靠性破了），跳过收编+
     * 日志留证（重复行原样保留，宁可不省这份空间也不错并两份内容）。
     */
    fun groupMergeable(typeAndSize: List<Pair<Int, Long>>): Boolean {
        if (typeAndSize.size < 2) return true
        val first = typeAndSize[0]
        for (i in 1 until typeAndSize.size) {
            if (typeAndSize[i] != first) return false
        }
        return true
    }
}
