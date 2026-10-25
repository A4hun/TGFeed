package dev.tgfeed.core.feed

/**
 * R183 垃圾/广告过滤闸（终版架构「双宝藏全量 → 过滤闸 → 公平轮转」的核心工程件）。
 *
 * 设计依据（用户拍板）：发现模块 = 全量合格频道的展示场，**唯一的门是垃圾/广告过滤闸**——
 * 不做口味筛选、不做分值个性化排序。垃圾信号三路：
 *  1. **caption 广告话术族**：推广/加V/联系方式/引流话术在采样 caption 里的密度
 *    （[adRateOf]；博彩词表 [AdBlacklist.isSpam] 已有的第一信号在调用方先过一遍，
 *    这里补通用广告话术族——高精确度词为主，宁漏勿杀：资源频道 caption 本就带
 *    「福利/资源」类行业词，误杀=发现页报废，与 AdBlacklist 同一保守口径）；
 *  2. **同频道模板风暴**：规范化 caption（数字折叠为 # 后比对——「第3集/第4集」这类
 *    正常连载去数字后同模板，但那是单次连载天然形态）在高频重复窗内的占比
 *    （[stormRateOf]）：短时间批量同模板 = 引流号/洗稿号行为特征；
 *  3. **跨频道同指纹（spam 农场）**：同一条规范化 caption 出现在 ≥[FARM_MIN_CHANNELS]
 *    个频道（[farmChannelsOf]）——一个内容农场批量开号转发同一段话术，单频道视角
 *    各自都「正常」，只有跨频道比对才现形；
 *  4. **双零拦截**（用户口径原话）：群内零安利 + 索引零丰度（未探针或 R 低于
 *    [IndexTierPolicy.RICHNESS_DOWN_SCORE]）的频道直接拦——没有任何一路宝藏
 *    为它背书，进发现流只会稀释合格候选。
 *
 * 纯函数集合，全部 JVM 可单测；判定阈值集中在 companion 常量表（一处改全局生效）。
 */
object AdPatternFilter {

    /**
     * 通用广告/引流话术族（小写 contains 匹配）。
     * 收录标准：几乎只出现在广告 caption 里的高鉴别度片段（商务合作/招代理/加V看片
     * 类）。「防失联/备用号/上车」这类资源频道行业常用词**刻意不收**——误杀代价
     * 高于漏杀（保守口径与 [AdBlacklist] 一致：过滤闸拦的是广告，不是行业黑话）。
     */
    private val AD_PHRASES = listOf(
        // 商务/推广位
        "商务合作", "广告合作", "承接推广", "推广位", "招广告", "投放合作", "广告投放",
        // 代理/拉人体系
        "招代理", "代理加盟", "招商加盟", "兼职日结", "日入过百", "月入过万", "稳赚不赔",
        "带你赚钱", "上车费", "入会费", "会员费仅需", "付费进群", "收费群",
        // 联系方式/加V引流
        "加v", "加vx", "加威", "加微信", "微信号", "vx号", "私聊客服", "联系客服",
        "客服qq", "飞机客服", "唯一客服", "官方客服", "私我", "私信我", "滴滴我",
        // 低俗引流话术
        "大福利", "福利姬", "约炮", "同城可约", "上门服务", "一条龙服务", "过夜",
        "实体店", "线下可约", "面基",
        // 赌博引流补充（AdBlacklist 博彩词表外围的变体）
        "秒到账", "带你回血", "带你上岸", "下分", "上分", "彩票代理",
    )

    /**
     * caption 规范化（模板/指纹比对用）：小写 + 连续数字折叠为 # + 去空白。
     * 「【4K蓝光】第03集」与「【4K蓝光】第12集」归一为同模板——正常连载的集数
     * 变化不构成「不同内容」；同一话术换数字的批量转发则天然现形。
     */
    fun normalizeCaption(text: String): String =
        text.lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), "")

    /** caption 是否命中广告话术族（空串/短于 4 字不判——链接残留碎片不构成广告证据） */
    fun isAdCaption(text: String): Boolean {
        if (text.length < 4) return false
        val lower = text.lowercase()
        return AD_PHRASES.any { it in lower }
    }

    /**
     * 信号一：采样 caption 里广告话术密度（0..1）。
     * 空样本返回 0（无证据不臆测——与 richnessScore 的 probed 语义同口径）。
     */
    fun adRateOf(captions: List<String>): Double {
        if (captions.isEmpty()) return 0.0
        return captions.count { isAdCaption(it) }.toDouble() / captions.size
    }

    /**
     * 信号二：同频道模板风暴率（0..1）——出现频次最高的规范化模板在样本里的占比。
     * 高产连载频道「同模板+集数递增」正常占比 20~40%（一页翻完同一部剧）；短时间
     * 批量同模板刷屏（占样本主体）才是引流号行为。占比 ≥ [STORM_REJECT_RATE] 拒。
     */
    fun stormRateOf(captions: List<String>): Double {
        if (captions.size < STORM_MIN_SAMPLE) return 0.0
        val counts = HashMap<String, Int>(captions.size)
        for (c in captions) {
            val n = normalizeCaption(c)
            if (n.length >= STORM_MIN_TEMPLATE_LEN) counts[n] = (counts[n] ?: 0) + 1
        }
        val max = counts.values.maxOrNull() ?: 0
        return max.toDouble() / captions.size
    }

    /**
     * 信号三：跨频道同指纹（spam 农场）——按 [normalizeCaption] 分组，同一指纹
     * （长度 ≥ [FARM_MIN_TEMPLATE_LEN]，短话术「感谢关注」天然跨频道不构成农场证据）
     * 出现在 ≥ [FARM_MIN_CHANNELS] 个频道 = **共享指纹**；共享指纹行占该频道非空样本
     * ≥ [FARM_SHARE_REJECT] 才标记该频道（R183 真机修正：初版「转发过一次共享话术就
     * 整频道拉黑」实锤 192/453 被标，其中大量高丰度好频道只是转发过一次「极搜搜索
     * 引擎」推广（86 频道同文）——一次转发 ≠ 农场，主体内容就是共享话术才是）。
     * 输入 = 各频道（chatId → 采样 caption 列表）；返回被标记的 chatId 集。
     */
    fun farmChannelsOf(captionsByChannel: Map<Long, List<String>>): Set<Long> {
        if (captionsByChannel.size < FARM_MIN_CHANNELS) return emptySet()
        // 指纹 → 出现过的频道集合（每频道每指纹记一次存在——一条话术刷 20 遍仍是一个指纹证据）
        val channelsByFingerprint = HashMap<String, MutableSet<Long>>()
        for ((chatId, captions) in captionsByChannel) {
            val seen = HashSet<String>()
            for (c in captions) {
                val n = normalizeCaption(c)
                if (n.length < FARM_MIN_TEMPLATE_LEN) continue
                if (seen.add(n)) channelsByFingerprint.getOrPut(n) { HashSet() } += chatId
            }
        }
        // 共享指纹集合（≥MIN_CHANNELS 个频道共现过的指纹本体）
        val sharedFingerprints = channelsByFingerprint.entries
            .filter { it.value.size >= FARM_MIN_CHANNELS }
            .mapTo(HashSet()) { it.key }
        if (sharedFingerprints.isEmpty()) return emptySet()
        // 二趟（份额制）：共享指纹行占该频道非空样本 ≥ FARM_SHARE_REJECT 才标记——
        // 转发过一次推广的好频道（份额 1/256）不误杀；主体内容就是共享话术才是农场
        val flagged = HashSet<Long>()
        for ((chatId, captions) in captionsByChannel) {
            val nonEmpty = captions.filter { it.isNotBlank() }
            if (nonEmpty.size < FARM_MIN_SAMPLE) continue
            val sharedRows = nonEmpty.count { normalizeCaption(it) in sharedFingerprints }
            if (sharedRows.toDouble() / nonEmpty.size >= FARM_SHARE_REJECT) flagged += chatId
        }
        return flagged
    }

    /** 过滤闸判定结果：PASS / REJECT（附人话原因——日志与诊断用） */
    data class Verdict(val pass: Boolean, val reason: String)

    /**
     * 过滤闸总判定（纯函数）：
     *  - 广告话术密度 ≥ [AD_REJECT_RATE] → 拒（「广告话术密度 X%」）；
     *  - 模板风暴率 ≥ [STORM_REJECT_RATE] → 拒（「同模板刷屏 X%」）；
     *  - spam 农场指纹命中 → 拒（「跨频道批量转发」）；
     *  - 双零拦截：[groupEndorse] == 0 且（未探针 ∨ 丰度 < [IndexTierPolicy.RICHNESS_DOWN_SCORE]）
     *    → 拒（「零安利零丰度」）——两路宝藏都没背书的频道不进展示场。
     * 过闸顺序即此序（先最便宜的布尔判定）；[adRate]/[stormRate] 由调用方预先算好
     * （快照构建时顺带聚合，判定零重复计算）。
     */
    fun classify(
        probed: Boolean,
        richness: Double,
        adRate: Double,
        stormRate: Double,
        farmFlag: Boolean,
        groupEndorse: Int,
    ): Verdict = when {
        adRate >= AD_REJECT_RATE -> Verdict(false, "广告话术密度 ${(adRate * 100).toInt()}%")
        stormRate >= STORM_REJECT_RATE -> Verdict(false, "同模板刷屏 ${(stormRate * 100).toInt()}%")
        farmFlag -> Verdict(false, "跨频道批量转发（spam 农场指纹）")
        groupEndorse <= 0 && (!probed || richness < IndexTierPolicy.RICHNESS_DOWN_SCORE) ->
            Verdict(false, "零安利零丰度（双零拦截）")
        else -> Verdict(true, "")
    }

    // ---- 判定阈值（一处改全局生效；companion 不适用于 object，常量直接进对象体） ----

    /** 信号一拒线：广告话术占样本比例 ≥50% = 广告频道（一半以上内容在打广告） */
    const val AD_REJECT_RATE = 0.5

    /** 信号二拒线：单一模板占样本 ≥60% = 刷屏号（正常连载去数字后 20~40%） */
    const val STORM_REJECT_RATE = 0.6

    /** 信号二最小样本：样本 <8 条不判风暴（太少没统计意义） */
    const val STORM_MIN_SAMPLE = 8

    /** 信号二最短模板长（规范化后）：「谢谢关注」级短句不构成模板证据 */
    const val STORM_MIN_TEMPLATE_LEN = 8

    /** 信号三成 farm 最少频道数：同指纹 ≥3 频道 = 批量转发农场 */
    const val FARM_MIN_CHANNELS = 3

    /** 信号三最短指纹长：短话术天然跨频道共现（如「更新」），长话术才是同源证据 */
    const val FARM_MIN_TEMPLATE_LEN = 12

    /**
     * 信号三 farm 份额拒线（R183 真机修正）：共享指纹行占该频道非空样本 ≥50% 才标记
     * ——主体内容就是共享话术 = 农场；转发过一次推广（份额 1/N）的好频道不误杀
     */
    const val FARM_SHARE_REJECT = 0.5

    /** 信号三最小样本：非空样本 <8 条不判份额（与风暴最小样本同值但语义独立） */
    const val FARM_MIN_SAMPLE = 8
}
