package dev.tgfeed.core.tastemap

import dev.tgfeed.core.td.VideoItem

/**
 * R234 尘埃带纯函数（口味星图三期·关键字传播可视化·零 Android 依赖，单测可跑）：
 *
 * **尘埃带是数据实体不是装饰**（设计 §3.3 / R151 假旋臂裁决不回退）——每一粒尘埃
 * 对应**一条真实带该 tag 的条目**：选中一个关键字 → hashtag 精确词召回（R222 同链）
 * → 按频道聚合出**传播档案**（该 tag 在各频道的最早出现/最晚出现/条数）→
 * **首发频道=全网最早出现者**（DS-5 首发徽章语义）→ 渲染为「源恒星 → 各下游频道」
 * 的发光粒子带：
 *
 *  - 粒子沿带位置 u = 时间位置（早期近源恒星/晚期近目标频道——「方向性=时间序」）；
 *  - 粒子亮度 = 近新远旧（alpha 三档分桶，渲染端批量 drawPoints）；
 *  - 与创世纪滑杆联动（§分期三）：[effCursorMs] 有效光标之前的尘埃带可见/之后隐去
 *    ——拖滑杆=亲眼看到关键字从哪颗恒星出发、什么时刻扩散到哪几颗。
 *
 * 确定性纪律（R233 同款）：频道留重/粒子抽样/全部排序都是纯函数确定性——同数据
 * 同尘埃带，绝不跳位。
 *
 * 数据口径（诚实标注）：召回上限=深索引 hashtag 模式的既有硬顶；相册展开带出的
 * 无 tag 成员按 [hasTag] 复核剔除（每粒尘埃=真实条目的铁口径）；无日期条目不进
 * 时间叙事（dateEpochSec=0 诚实跳过）。
 */
object TasteTagSpread {

    /** 传播频道上限（防边爆炸：超限按条数留重——大传播频道先留） */
    const val MAX_CHANNELS = 32

    /** 每频道粒子上限（等距抽样留重——LOD 护栏：全带 ≤ MAX_CHANNELS×此值=768 粒） */
    const val MAX_PARTICLES_PER_CHANNEL = 24

    /** 粒子沿带参数下界（0..1）：源恒星侧留出恒星本体呼吸空间 */
    const val U_MIN = 0.18f

    /** 粒子沿带参数上界（0..1）：目标频道侧留出恒星本体呼吸空间 */
    const val U_MAX = 0.94f

    /**
     * 一个频道的传播档案（不可变值对象——装配期定死）。
     *
     * @property chatId 频道 id
     * @property firstSec 该 tag 在此频道的最早条目（Unix 秒——「扩散到达时刻」；
     * 带可见性=创世纪有效光标越过它）
     * @property lastSec 最晚条目（传播窗终点）
     * @property count 该 tag 在此频道的条数（召回口径，抽样前）
     * @property particleSec 保留粒子（真实条目）的发布时刻（**升序**，等距抽样 ≤
     * [MAX_PARTICLES_PER_CHANNEL]——每粒=一条真实条目）
     */
    class ChannelSpread(
        val chatId: Long,
        val firstSec: Long,
        val lastSec: Long,
        val count: Int,
        val particleSec: LongArray,
    )

    /**
     * 一个关键字的传播模型（不可变，装配期定死）。UI 层把 [channels] 映射到星系
     * 恒星坐标（星不在图=诚实回退/空态，见渲染侧）。
     *
     * @property tag 标签词（不含 #）
     * @property sourceChatId 首发频道（[channels] 内 firstSec 最小者；同步频道按
     * chatId 升序破平——确定性）
     * @property channels 下游传播频道表（**firstSec 升序**=传播时间序；含首发频道
     * 本身——渲染侧以源为锚，其余频道各画一条带）
     * @property t0Sec / t1Sec 传播窗（首发时刻 → 全网最晚时刻；相等=同时段传播，
     * 粒子恒聚中段——爆发信号本身，不做假散布）
     * @property recalled 召回总条数（hashtag 检索口径——「N 条」诚实统计的分母）
     * @property datedSec 带日期参与聚合的条数（dateEpochSec>0——无日期条目不进
     * 时间叙事，诚实跳过）
     * @property sampled 频道是否被留重截断（true=召回频道超上限，按条数留重）
     */
    class SpreadModel(
        val tag: String,
        val sourceChatId: Long,
        val channels: List<ChannelSpread>,
        val t0Sec: Long,
        val t1Sec: Long,
        val recalled: Int,
        val datedSec: Int,
        val sampled: Boolean,
    )

    /**
     * 召回条目 → 传播模型（纯函数，确定性）：
     *  1. 成员复核 [hasTag]（相册展开可能带出无 tag 成员——每粒尘埃=真实条目的铁口径）；
     *  2. 按 chatId 聚合（dateEpochSec>0 才进时间叙事）；firstSec/lastSec/count 一次遍历；
     *  3. 频道表 = firstSec 升序 → chatId 升序（传播时间序读法）；超 [MAX_CHANNELS]
     *     时**保首发 + 其余按条数降序留重**（大传播频道先留）后重排回时间序；
     *  4. 每频道粒子=条目日期升序等距抽样 ≤ [MAX_PARTICLES_PER_CHANNEL]；
     *  5. t0=首发频道 firstSec；t1=保留频道最晚 lastSec。
     */
    fun build(tag: String, items: List<VideoItem>): SpreadModel {
        val tagFold = tag.trim().trimStart('#', '＃').lowercase()
        if (tagFold.isEmpty()) return SpreadModel(tag, 0L, emptyList(), 0L, 0L, 0, 0, false)
        // 聚合：chatId → 日期收集（升序——粒子抽样与 first/last 同一份数据）
        class Acc(val chatId: Long) {
            val secs = ArrayList<Long>(16)
        }
        val byChat = HashMap<Long, Acc>(32)
        var dated = 0
        for (item in items) {
            if (!hasTag(item, tagFold)) continue
            val sec = item.dateEpochSec.toLong()
            if (sec <= 0L) continue // 无日期不进时间叙事（诚实跳过）
            dated++
            byChat.getOrPut(item.chatId) { Acc(item.chatId) }.secs += sec
        }
        if (byChat.isEmpty()) {
            // 有召回但无可聚合成员（无日期/无 tag 成员）——recalled 诚实记账
            return SpreadModel(tag, 0L, emptyList(), 0L, 0L, items.size, dated, false)
        }
        val all = byChat.values.map { acc ->
            val secs = acc.secs.sorted() // 升序（确定性——同数据同序）
            ChannelSpread(
                chatId = acc.chatId,
                firstSec = secs.first(),
                lastSec = secs.last(),
                count = secs.size,
                particleSec = sampleParticles(secs),
            )
        }
        // 传播时间序：firstSec 升序 → chatId 升序
        val timeOrder = all.sortedWith(
            compareBy<ChannelSpread> { it.firstSec }.thenBy { it.chatId },
        )
        val source = timeOrder.first()
        val kept: List<ChannelSpread> = if (timeOrder.size <= MAX_CHANNELS) {
            timeOrder
        } else {
            // 留重：首发必留（传播叙事的锚），其余按条数降序（chatId 升序破平）
            val rest = timeOrder.drop(1)
                .sortedWith(
                    compareByDescending<ChannelSpread> { it.count }.thenBy { it.chatId },
                )
                .take(MAX_CHANNELS - 1)
            (listOf(source) + rest).sortedWith(
                compareBy<ChannelSpread> { it.firstSec }.thenBy { it.chatId },
            )
        }
        return SpreadModel(
            tag = tag,
            sourceChatId = source.chatId,
            channels = kept,
            t0Sec = source.firstSec,
            t1Sec = kept.maxOf { it.lastSec },
            recalled = items.size,
            datedSec = dated,
            sampled = timeOrder.size > MAX_CHANNELS,
        )
    }

    /**
     * 成员复核：item_tags 已解析 → 元素级精确（fold 归一整词相等，R228 权威口径）；
     * 未解析（null，含相册展开带出的成员/存量回填未跑到）→ caption 含 #tag 的
     * 大小写不敏感检查（hashtag 模式召回的前提即 # 标记在正文——近似的成员复核，
     * 精确词边界复核归深索引 searchHashtag 本身）。
     */
    fun hasTag(item: VideoItem, tagFold: String): Boolean {
        val tags = item.itemTags
        if (tags != null) return tags.any { it.lowercase() == tagFold }
        if (item.caption.isEmpty()) return false
        return item.caption.lowercase().contains("#$tagFold")
    }

    /**
     * 粒子等距抽样（升序日期，确定性）：n ≤ 上限全保留；否则步长抽样取前
     * [MAX_PARTICLES_PER_CHANNEL] 个（首尾必含——first/last 时刻的粒子是传播史
     * 两端的锚，抽掉就断代）。
     */
    private fun sampleParticles(secsAsc: List<Long>): LongArray {
        if (secsAsc.size <= MAX_PARTICLES_PER_CHANNEL) return secsAsc.toLongArray()
        val out = LongArray(MAX_PARTICLES_PER_CHANNEL)
        val step = (secsAsc.size - 1).toFloat() / (MAX_PARTICLES_PER_CHANNEL - 1)
        for (i in 0 until MAX_PARTICLES_PER_CHANNEL) {
            out[i] = secsAsc[(i * step).toInt()]
        }
        // 末位步长取整可能落在倒数第二格——强制收尾到最晚时刻（传播史终点锚）
        out[MAX_PARTICLES_PER_CHANNEL - 1] = secsAsc.last()
        return out
    }

    // ==================== 渲染/联动派生量（单口——绘制与判定同一式） ====================

    /**
     * 粒子沿带参数（0..1，源→目标）：时间位置 (sec−t0)/(t1−t0) 线性映射到
     * [U_MIN, U_MAX]。跨度 ≤0（同时段传播/单条）→ 恒中段——**同时发布聚在同一
     * 角度是「考古」要读的信号本身**（爆发=亮团），不做假散布。
     */
    fun particleU(sec: Long, t0Sec: Long, t1Sec: Long): Float {
        val span = t1Sec - t0Sec
        if (span <= 0L) return (U_MIN + U_MAX) * 0.5f
        val p = ((sec - t0Sec).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        return U_MIN + (U_MAX - U_MIN) * p.toFloat()
    }

    /**
     * 粒子亮度档（0..[ALPHA_BUCKETS]-1）：近新远旧——时间位置三等分分桶
     * （渲染端按桶批量 drawPoints，一桶一笔=帧预算件）。
     */
    const val ALPHA_BUCKETS = 3

    fun alphaBucketOf(sec: Long, t0Sec: Long, t1Sec: Long): Int {
        val span = t1Sec - t0Sec
        if (span <= 0L) return ALPHA_BUCKETS - 1 // 单点传播：最亮档（短促爆发的观感）
        val p = ((sec - t0Sec).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        val b = (p * ALPHA_BUCKETS).toInt()
        return b.coerceIn(0, ALPHA_BUCKETS - 1)
    }

    // ==================== 创世纪有效光标（now-extended 联动） ====================

    /**
     * **R234 有效光标**（毫秒）：创世纪滑杆 0..1 → [spanMinMs, nowMs] 的线性映射
     * （星生量程 [spanMin, spanMax] 的**now 扩展**——星系先凝成、后段滑杆覆盖近期
     * 时代：「尘埃在凝固后的宇宙里传播」的叙事轴）。既有星生凝聚/日期标签走
     * TasteFlight.cursorMs 原口径零改动（R206/R231 行为不破——本函数只服务
     * 尘埃带/星座轨迹的联动消费方）。
     *
     * 滑杆拖到 1 = now（一切可见）；拖回 0 = 宇宙起点（一切尘埃隐去）。
     */
    fun effCursorMs(spanMinMs: Long, t01: Float, nowMs: Long): Long {
        val range = (nowMs - spanMinMs).coerceAtLeast(0L)
        return spanMinMs + (range * t01.coerceIn(0f, 1f)).toLong()
    }

    /** 带到达判定：有效光标越过该频道 firstSec（tag 扩散到此的时刻）——带曲线可见 */
    fun bandArrived(firstSec: Long, effCursorMs: Long): Boolean =
        effCursorMs >= firstSec * 1000L

    /** 粒子可见判定：有效光标越过条目发布时刻（此刻之前的尘埃带可见/之后的隐去） */
    fun particleVisible(itemSec: Long, effCursorMs: Long): Boolean =
        effCursorMs >= itemSec * 1000L
}
