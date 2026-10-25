package dev.tgfeed.core.tastemap

import dev.tgfeed.core.tastemap.TasteAstroPhysics.BirthStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R326 诞生侧判据族单测（r325 A⑤ 单测锚——TasteTerminalJudgmentTest 26 例的诞生
 * 侧镜像）：四态判据门正反例（金矿缺/分档关/档位族/tag 空/首见零）/终态>诞生
 * 互斥（死星零诞生注记）/诞生内优先级（③>②>①）/行星成型修饰位（叠加②③独立
 * 不立）/缺据回落（demo 全零形态=宇宙年轻）/零载荷注记语义/确定性+输入序=输出
 * 序/birthPoolOf 池选（首见新度降序+chatId 破平+NOT_BIRTH 剔除+帽 30）/池点查
 * 缺据位（hist 恒 null 归 R327 回填）+单口族（goldEvidenceOf/probeProgress01/
 * isDeathSideKind）。
 *
 * 证据构造约定：tieringEnabled 默认开（②的反例=显式关）；persistedTier 默认 -1
 * （LEGACY/缺行——②须显式 tier=2）；watchSec/subscribed 默认 0/false（未探索
 * ——R235 雾团同口径；已探索反例显式传）。
 */
class TasteBirthJudgmentTest {

    private fun ev(
        chatId: Long,
        watchSec: Long = 0L,
        subscribed: Boolean = false,
        kind: CelestialKind = CelestialKind.STAR,
        firstSeenMs: Long = 0L,
        goldItems: Int = 0,
        persistedTier: Int = -1,
        tieringEnabled: Boolean = true,
        probeItems: Int = 0,
        ledgerTags: Int = 0,
    ) = TasteAstroPhysics.BirthEvidence(
        chatId, watchSec, subscribed, kind, firstSeenMs,
        goldItems, persistedTier, tieringEnabled, probeItems, ledgerTags,
    )

    private fun judge(vararg cands: TasteAstroPhysics.BirthEvidence) =
        TasteAstroPhysics.judgeBirthStates(cands.toList())

    // ==================== ① 星云候选（原料在场——金矿证据门） ====================

    @Test
    fun `星云候选-正例与金矿缺反例`() {
        // 正例：未探索+金矿已收录未收割（原料在场——r303 本体论口径）
        val v = judge(ev(1, goldItems = 5)).single()
        assertEquals(BirthStage.NEBULA_CANDIDATE, v.stage)
        assertTrue(!v.planetsForming)
        // 反例：金矿快照缺失/未收录（goldItems==0）→ 普通未探索雾（现状原样，绝不冒充摇篮）
        assertEquals(BirthStage.NOT_BIRTH, judge(ev(1)).single().stage)
        // 零载荷：非②③态 probeItems/tagsCount 恒零（注记语义）
        assertEquals(0, v.probeItems)
        assertEquals(0, v.tagsCount)
    }

    @Test
    fun `星云候选-已探索不判（R235 雾团同口径）`() {
        // watchSec>0=看过；subscribed=订阅——任一即已探索，①②皆不判
        assertEquals(BirthStage.NOT_BIRTH, judge(ev(1, watchSec = 60L, goldItems = 5)).single().stage)
        assertEquals(BirthStage.NOT_BIRTH, judge(ev(1, subscribed = true, goldItems = 5)).single().stage)
    }

    // ==================== ② 吸积盘中（探针档门——分档开关闸） ====================

    @Test
    fun `吸积盘-正例（未探索+T2+分档开）`() {
        val v = judge(ev(1, persistedTier = 2, probeItems = 120)).single()
        assertEquals(BirthStage.ACCRETING, v.stage)
        assertEquals(120, v.probeItems) // 吸积行「已聚 N 条」原料（本地口径不依赖池点查）
    }

    @Test
    fun `吸积盘-分档关恒LEGACY不判（最大诚实约束）`() {
        // 分档关 → effectiveTier 恒 LEGACY → ②不判（链中段缺——用户不开智能分档星图零吸积态）
        assertEquals(
            BirthStage.NOT_BIRTH,
            judge(ev(1, persistedTier = 2, tieringEnabled = false)).single().stage,
        )
        // 金矿账与分档开关正交：分档关照判①（原料在场不依赖爬虫档位）
        assertEquals(
            BirthStage.NEBULA_CANDIDATE,
            judge(ev(1, goldItems = 5, persistedTier = 2, tieringEnabled = false)).single().stage,
        )
    }

    @Test
    fun `吸积盘-非T2档不判（T0、T1、T3、LEGACY缺行）`() {
        for (tier in intArrayOf(-1, 0, 1, 3)) {
            assertEquals("档位 $tier 不判吸积", BirthStage.NOT_BIRTH, judge(ev(1, persistedTier = tier)).single().stage)
        }
    }

    // ==================== ③ 原恒星年轻位（六态对位——不重判） ====================

    @Test
    fun `原恒星-年轻位对位不重判`() {
        // kind==PROTOSTAR（六态判据已判首见<90 天）→ ③年轻位（不重判只对位）
        assertEquals(
            BirthStage.PROTOSTAR_YOUNG,
            judge(ev(1, kind = CelestialKind.PROTOSTAR, firstSeenMs = 42_000_000L)).single().stage,
        )
        // 已探索的 PROTOSTAR 同样对位（③不设未探索门——六态 PROTOSTAR 本就含已探索年轻频道）
        assertEquals(
            BirthStage.PROTOSTAR_YOUNG,
            judge(ev(1, watchSec = 60L, kind = CelestialKind.PROTOSTAR, firstSeenMs = 42_000_000L)).single().stage,
        )
        // 反例：六态非 PROTOSTAR（STAR）+首见再新也不判（90 天门在 classifyChannel——诞生侧不重判）
        assertEquals(
            BirthStage.NOT_BIRTH,
            judge(ev(1, kind = CelestialKind.STAR, firstSeenMs = 42_000_000L, goldItems = 0)).single().stage,
        )
        // 诚实红线双保险：PROTOSTAR 但 firstSeenMs==0（无首见证据）→ 不判③（零证据不冒充年轻）
        assertEquals(
            BirthStage.NOT_BIRTH,
            judge(ev(1, kind = CelestialKind.PROTOSTAR, firstSeenMs = 0L)).single().stage,
        )
    }

    // ==================== ④ 行星成型修饰位（叠加②③——独立不立） ====================

    @Test
    fun `行星成型-修饰位叠加与账本帽`() {
        // ③+账本非空 → 修饰位立（tagsCount 原样）
        val v = judge(ev(1, kind = CelestialKind.PROTOSTAR, firstSeenMs = 1L, ledgerTags = 3)).single()
        assertEquals(BirthStage.PROTOSTAR_YOUNG, v.stage)
        assertTrue(v.planetsForming)
        assertEquals(3, v.tagsCount)
        // ②+账本 40 → 修饰位立+帽 24（min(账本, MAX_PLANETS)——文案与轨道系统同上限不虚报）
        val v2 = judge(ev(2, persistedTier = 2, ledgerTags = 40)).single()
        assertEquals(BirthStage.ACCRETING, v2.stage)
        assertTrue(v2.planetsForming)
        assertEquals(24, v2.tagsCount)
    }

    @Test
    fun `行星成型-账本空不判与独立不立`() {
        // tag 账本空（回填窗/无 tag 库）→ 不判④（整行不出）
        val v = judge(ev(1, kind = CelestialKind.PROTOSTAR, firstSeenMs = 1L, ledgerTags = 0)).single()
        assertTrue(!v.planetsForming)
        assertEquals(0, v.tagsCount)
        // 独立不立①：星云候选（纯原料）+账本非空 → 修饰位不立（原料还没聚成行星）
        val v4 = judge(ev(4, goldItems = 5, ledgerTags = 9)).single()
        assertEquals(BirthStage.NEBULA_CANDIDATE, v4.stage)
        assertTrue(!v4.planetsForming)
        assertEquals(0, v4.tagsCount)
        // 独立不立②：非年轻已探索星+账本非空 → 不立
        val v5 = judge(ev(5, watchSec = 60L, ledgerTags = 9)).single()
        assertTrue(!v5.planetsForming)
    }

    // ==================== 终态>诞生互斥（死星零诞生注记） ====================

    @Test
    fun `终态互斥-死亡五档零诞生注记（死星不坐月子）`() {
        for (kind in listOf(
            CelestialKind.BLACK_HOLE, CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR,
            CelestialKind.WHITE_HOLE, CelestialKind.WHITE_DWARF,
        )) {
            // 全证据满配（未探索+T2+金矿+账本+首见新）也零注记——死亡盖过诞生
            val v = judge(
                ev(1, kind = kind, firstSeenMs = 1L, goldItems = 99, persistedTier = 2, ledgerTags = 9, probeItems = 500),
            ).single()
            assertEquals("死亡档 $kind 零诞生态", BirthStage.NOT_BIRTH, v.stage)
            assertTrue("修饰位同步压制", !v.planetsForming)
            assertEquals(0, v.probeItems)
            assertEquals(0, v.tagsCount)
        }
    }

    @Test
    fun `终态互斥-isDeathSideKind单口五档正其余反`() {
        for (kind in listOf(
            CelestialKind.BLACK_HOLE, CelestialKind.NEUTRON_STAR, CelestialKind.QUARK_STAR,
            CelestialKind.WHITE_HOLE, CelestialKind.WHITE_DWARF,
        )) {
            assertTrue("死亡档 $kind", TasteAstroPhysics.isDeathSideKind(kind))
        }
        for (kind in listOf(
            CelestialKind.STAR, CelestialKind.PROTOSTAR, CelestialKind.RED_GIANT,
            CelestialKind.SUPERNOVA, CelestialKind.PULSAR, CelestialKind.CLUSTER, CelestialKind.METEOR,
        )) {
            assertTrue("非死亡档 $kind", !TasteAstroPhysics.isDeathSideKind(kind))
        }
    }

    @Test
    fun `终态互斥-非死亡档保留诞生资格（超新星红巨星）`() {
        // 超新星=瞬态（爆发中非死透）——照判②（r325 死亡清单只五档）
        val v = judge(ev(1, kind = CelestialKind.SUPERNOVA, persistedTier = 2, ledgerTags = 5)).single()
        assertEquals(BirthStage.ACCRETING, v.stage)
        assertTrue(v.planetsForming)
        // 红巨星=冷却稳态非死亡——照判①
        val v2 = judge(ev(2, kind = CelestialKind.RED_GIANT, goldItems = 5)).single()
        assertEquals(BirthStage.NEBULA_CANDIDATE, v2.stage)
    }

    // ==================== 优先级（诞生内 ③>②>①——链位更晚者胜） ====================

    @Test
    fun `优先级-诞生内③高于②②高于①`() {
        // ③>②：未探索+T2+金矿+PROTOSTAR → PROTOSTAR_YOUNG（点燃起步=比聚合中更晚；probeItems 零载荷）
        val v = judge(ev(1, kind = CelestialKind.PROTOSTAR, firstSeenMs = 1L, goldItems = 5, persistedTier = 2)).single()
        assertEquals(BirthStage.PROTOSTAR_YOUNG, v.stage)
        assertEquals(0, v.probeItems)
        // ②>①：未探索+T2+金矿 → ACCRETING（爬虫已在聚=比纯原料在场更晚）
        val v2 = judge(ev(2, goldItems = 5, persistedTier = 2)).single()
        assertEquals(BirthStage.ACCRETING, v2.stage)
    }

    // ==================== 缺据回落（诚实红线） ====================

    @Test
    fun `缺据回落-demo全零形态四态全零（宇宙年轻）`() {
        // demo/新装：索引缺席（tiers 全空 -1）+金矿缺+账本空+无首见 → 全零=宇宙年轻（不做特判）
        val vs = judge(ev(1), ev(2), ev(3, watchSec = 30L))
        assertTrue(vs.all { it.stage == BirthStage.NOT_BIRTH && !it.planetsForming && it.hist == null })
    }

    @Test
    fun `诞生史增强位-判据出口恒null（池内点查归R327回填）`() {
        // judge 不做 IO：hist 恒 null（缺据整行不出）；R327 半场对 birthPoolOf 池成员点查后 copy 回填
        for (v in judge(
            ev(1, kind = CelestialKind.PROTOSTAR, firstSeenMs = 1L),
            ev(2, persistedTier = 2),
            ev(3, goldItems = 5),
        )) {
            assertTrue(v.hist == null)
        }
    }

    // ==================== 确定性（同输入同输出+输入序=输出序） ====================

    @Test
    fun `确定性与输入序输出序`() {
        assertTrue(judge().isEmpty())
        val cands = listOf(
            ev(7, kind = CelestialKind.PROTOSTAR, firstSeenMs = 1L, ledgerTags = 2),
            ev(3, persistedTier = 2, probeItems = 50),
            ev(9, goldItems = 5),
            ev(1, kind = CelestialKind.WHITE_DWARF, persistedTier = 2, ledgerTags = 9),
        )
        val a = TasteAstroPhysics.judgeBirthStates(cands)
        assertEquals(a, TasteAstroPhysics.judgeBirthStates(cands)) // data class 等值锚
        assertEquals(listOf(7L, 3L, 9L, 1L), a.map { it.chatId }) // 输入序=输出序
    }

    // ==================== birthPoolOf 池选（先池选后点查——诞生史有界预算口） ====================

    @Test
    fun `池选-首见降序帽30与NOT_BIRTH剔除`() {
        assertEquals(30, TasteAstroPhysics.BIRTH_POOL_CAP)
        // 32 颗注记在场（首见 1000..32000）+1 颗 NOT_BIRTH（首见最大也不入池）
        val feats = (0 until 32).map { i ->
            TasteAstroPhysics.BirthFeatures(
                chatId = 100L + i, stage = BirthStage.ACCRETING, planetsForming = false,
                firstSeenMs = (i + 1) * 1000L, probeItems = i, tagsCount = 0,
            )
        } + listOf(
            TasteAstroPhysics.BirthFeatures(300L, BirthStage.NOT_BIRTH, false, 99_000L, 0, 0),
        )
        val pool = TasteAstroPhysics.birthPoolOf(feats)
        assertEquals(30, pool.size)
        assertTrue(pool.none { it.stage == BirthStage.NOT_BIRTH }) // 无注记者剔除
        // 首见降序：最新 32000 在首；帽 30 截掉最老两颗（2000/1000 不入池）
        assertEquals(32_000L, pool.first().firstSeenMs)
        assertEquals(3_000L, pool.last().firstSeenMs)
        assertTrue(pool.none { it.firstSeenMs < 3_000L })
        // 确定性：同输入两跑一致
        assertEquals(pool, TasteAstroPhysics.birthPoolOf(feats))
    }

    @Test
    fun `池选-破平chatId升序`() {
        val feats = listOf(
            TasteAstroPhysics.BirthFeatures(2L, BirthStage.ACCRETING, false, 5000L, 0, 0),
            TasteAstroPhysics.BirthFeatures(1L, BirthStage.ACCRETING, false, 5000L, 0, 0),
            TasteAstroPhysics.BirthFeatures(3L, BirthStage.ACCRETING, false, 9000L, 0, 0),
        )
        // 首见降序（9000 先）+破平 chatId 升序（1 压过 2）
        assertEquals(listOf(3L, 1L, 2L), TasteAstroPhysics.birthPoolOf(feats).map { it.chatId })
    }

    // ==================== 单口族（goldEvidenceOf/probeProgress01） ====================

    @Test
    fun `金矿证据单口-原料在场判定`() {
        assertTrue(TasteAstroPhysics.goldEvidenceOf(1))
        assertTrue(TasteAstroPhysics.goldEvidenceOf(999))
        assertTrue(!TasteAstroPhysics.goldEvidenceOf(0)) // 快照缺失/未收录=缺据
        assertTrue(!TasteAstroPhysics.goldEvidenceOf(-1)) // 防御：负值不判
    }

    @Test
    fun `probeProgress01-归一单调与缺据零`() {
        assertEquals(0f, TasteAstroPhysics.probeProgress01(0), 1e-6f) // 零聚合=静默档
        assertEquals(0f, TasteAstroPhysics.probeProgress01(-5), 1e-6f) // 缺据防御
        assertEquals(0.05f, TasteAstroPhysics.probeProgress01(100), 1e-4f) // 探针一页 100 条=100/2000
        assertEquals(0.5f, TasteAstroPhysics.probeProgress01(1000), 1e-4f)
        assertEquals(1f, TasteAstroPhysics.probeProgress01(2000), 1e-6f) // 升档线=满档
        assertEquals(1f, TasteAstroPhysics.probeProgress01(5000), 1e-6f) // 钳 0..1
        var prev = -1f
        for (i in 0..20) { // 单调不减
            val p = TasteAstroPhysics.probeProgress01(i * 100)
            assertTrue("单调：$prev ≤ $p", p >= prev)
            prev = p
        }
    }

    // ==================== R327 factLine 诞生四行（r325 D⑤ 锚表——API 只加不改） ====================

    private fun birthFeat(
        chatId: Long = 7L,
        stage: BirthStage = BirthStage.ACCRETING,
        planetsForming: Boolean = false,
        firstSeenMs: Long = 1L,
        probeItems: Int = 120,
        tagsCount: Int = 0,
        hist: TasteAstroPhysics.PublishFeatures? = null,
    ) = TasteAstroPhysics.BirthFeatures(chatId, stage, planetsForming, firstSeenMs, probeItems, tagsCount, hist)

    private fun histFeat(deadDays: Long = 2L) = TasteAstroPhysics.PublishFeatures(
        items = 96, firstSec = 1_700_000_000L, lastSec = 1_700_080_000L, baselinePer30d = 24f,
        burstBucketSec = 1_700_000_000L, burstPeakCount = 30, hasBurstPeak = true,
        deathPattern = TasteAstroPhysics.DeathPattern.ALIVE, deadDays = deadDays, lifetimeDays = 9L,
    )

    private fun fact(
        kind: CelestialKind = CelestialKind.STAR,
        firstSeenDays: Long = 34L,
        birth: TasteAstroPhysics.BirthFeatures? = null,
        nearDeathLabel: String? = null,
    ) = TasteAstroPhysics.factLine(
        kind = kind, items = 500, recent7 = 3, prev7 = 2,
        lastActivityDays = 5L, firstSeenDays = firstSeenDays, meanItems = 1304f,
        partnerTitle = null, partnerWeight = 0, hist = null,
        birth = birth, nearDeathLabel = nearDeathLabel,
    )

    @Test
    fun `吸积行-正例与缺据零行`() {
        // 正例：ACCRETING 态——已聚 N 条+升档线 P%（本地 items 口径零依赖池点查）
        val v = fact(birth = birthFeat(stage = BirthStage.ACCRETING, probeItems = 120))
        assertTrue(v.contains("它正在诞生：小样探测中（已聚 120 条"))
        assertTrue(v.contains("升档线 6%）")) // 120/2000=6%
        assertTrue(v.contains("尘埃正落向核心"))
        // 非②态（星云候选）不出吸积行
        val neb = fact(birth = birthFeat(stage = BirthStage.NEBULA_CANDIDATE, probeItems = 120))
        assertFalse(neb.contains("小样探测中"))
    }

    @Test
    fun `诞生史行-hist在场与缺据整行不出`() {
        val h = histFeat(deadDays = 2L)
        // 在场：诞生 K 天+发布 M 条+正快读法（deadDays<7=本周仍有新料）
        val v = fact(birth = birthFeat(stage = BirthStage.PROTOSTAR_YOUNG, hist = h))
        assertTrue(v.contains("✧ 诞生史：诞生 34 天 · 发布 96 条"))
        assertTrue(v.contains("聚得正快（本周仍有新料）"))
        // 已放缓读法（deadDays≥7）
        val v2 = fact(birth = birthFeat(stage = BirthStage.PROTOSTAR_YOUNG, hist = histFeat(deadDays = 30L)))
        assertTrue(v2.contains("已放缓（30 天无新料）"))
        // 缺据：hist=null（池外/点查失败）整行不出
        val v3 = fact(birth = birthFeat(stage = BirthStage.PROTOSTAR_YOUNG, hist = null))
        assertFalse(v3.contains("诞生史"))
        // 首见缺据：K 天段不出（无数字变体零编造）
        val v4 = fact(firstSeenDays = -1L, birth = birthFeat(hist = h))
        assertFalse(v4.contains("诞生 -1 天"))
        assertTrue(v4.contains("✧ 诞生史：发布 96 条"))
    }

    @Test
    fun `行星成型行-修饰位触发与独立不立`() {
        // 修饰位在场：tagsCount 带数字（与轨道系统同上限帽已在判据族锚定）
        val v = fact(birth = birthFeat(stage = BirthStage.PROTOSTAR_YOUNG, planetsForming = true, tagsCount = 5))
        assertTrue(v.contains("它的行星正在成型：5 个话题绕行中（吸积盘的边角料正聚成行星）"))
        // 修饰位缺：整行不出
        val v2 = fact(birth = birthFeat(stage = BirthStage.PROTOSTAR_YOUNG, planetsForming = false))
        assertFalse(v2.contains("行星正在成型"))
    }

    @Test
    fun `循环行-空间共在门与零编造回落`() {
        // 共在成立：星云候选∧近旁终态星（label 由调用方最近邻产出）
        val v = fact(
            birth = birthFeat(stage = BirthStage.NEBULA_CANDIDATE),
            nearDeathLabel = "白矮星",
        )
        assertTrue(v.contains("🌫 它躺在白矮星的余烬旁——上一代恒星的物质正养育新星的摇篮"))
        // 共在不成立（nearDeathLabel=null）：整行不出
        val v2 = fact(birth = birthFeat(stage = BirthStage.NEBULA_CANDIDATE), nearDeathLabel = null)
        assertFalse(v2.contains("余烬旁"))
        // 非星云候选星：label 在场也不出（星云候选门先拦——克制位）
        val v3 = fact(
            birth = birthFeat(stage = BirthStage.ACCRETING),
            nearDeathLabel = "白矮星",
        )
        assertFalse(v3.contains("余烬旁"))
    }

    @Test
    fun `birth尾参-null对既有分支零影响（API只加不改锚）`() {
        // birth=null+nearDeathLabel=null：与 R317b 之前的 factLine 输出逐位一致
        //（黑洞/白矮/原恒星三类代表——诞生四行全不出）
        for (k in listOf(CelestialKind.BLACK_HOLE, CelestialKind.WHITE_DWARF, CelestialKind.PROTOSTAR)) {
            val withNull = TasteAstroPhysics.factLine(
                kind = k, items = 500, recent7 = 3, prev7 = 2,
                lastActivityDays = 5L, firstSeenDays = 34L, meanItems = 1304f,
                partnerTitle = null, partnerWeight = 0, hist = null,
                birth = null, nearDeathLabel = null,
            )
            val without = TasteAstroPhysics.factLine(
                kind = k, items = 500, recent7 = 3, prev7 = 2,
                lastActivityDays = 5L, firstSeenDays = 34L, meanItems = 1304f,
                partnerTitle = null, partnerWeight = 0, hist = null,
            )
            assertEquals("birth 尾参污染 $k 既有分支", without, withNull)
        }
    }
}
