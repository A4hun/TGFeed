package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R312 星象科普体系单测：词条表完整性（全 CelestialKind 对位/related 键可解/任务书
 * 18 类清单覆盖）/本星实况（数据驱动带数字·缺据整条不出零编造）/「为什么」解释链
 * （判据翻译人话带本星数字·七问序·双星条目门·R331 ⑧⑨ 星域/尺度行）/点亮小卡
 * （instantLine 全类非空+pickToastStar 稀有度序确定性）/学习路径钩子
 * （unseenKindHints/unseenWonderHints/wonderCountsLine/eventGloss）/确定性
 * （同输入两跑逐位一致）。
 */
class AstroFactsTest {

    // ==================== 词条表 ====================

    @Test
    fun `词条表-全CelestialKind有对位且键唯一`() {
        // 全枚举必有词条（编译期 first{} 兜底=运行期单测锚定）
        for (k in CelestialKind.entries) {
            assertNotNull("缺 $k 的词条", AstroFacts.TOPICS.firstOrNull { it.kind == k })
        }
        // 键唯一
        assertEquals(AstroFacts.TOPICS.size, AstroFacts.TOPICS.map { it.key }.toSet().size)
        // related 键全可解
        for (t in AstroFacts.TOPICS) {
            for (rk in t.related) {
                assertNotNull("${t.key} 的相关键 $rk 无词条", AstroFacts.topic(rk))
            }
        }
    }

    @Test
    fun `词条表-任务书18类清单覆盖`() {
        val required = listOf(
            "star", "protostar", "red_giant", "white_dwarf", "supernova", "pulsar", // 恒星六态
            "neutron_star", "quark_star", "black_hole", "white_hole", "quasar", // 致密/预留席位
            "planet", "asteroid", "nebula", "cluster", "comet", "meteor", "binary", // 语义天体
            "accretion_disk", // R327 诞生链中段补环（吸积盘——r325 E 章）
            "gravity_wave", "eclipse", // R331 R320 在飞概念词条预落（r315 E/G 章③节）
        )
        for (key in required) {
            val t = AstroFacts.topic(key)
            assertNotNull("缺词条 $key", t)
            assertTrue(t!!.what.isNotBlank() && t.inUniverse.isNotBlank())
        }
        // R317 三席位兑现 kind 对位（终态宣判判据族入库——judgeTerminalStates）；
        // quasar 仍无枚举对位（黑洞高活动态修饰——不改枚举，r315 H 段设计决策）
        assertEquals(CelestialKind.NEUTRON_STAR, AstroFacts.topic("neutron_star")!!.kind)
        assertEquals(CelestialKind.QUARK_STAR, AstroFacts.topic("quark_star")!!.kind)
        assertEquals(CelestialKind.WHITE_HOLE, AstroFacts.topic("white_hole")!!.kind)
        assertNull("quasar 不该有枚举对位（黑洞高活动态修饰不改枚举）", AstroFacts.topic("quasar")!!.kind)
        // R317b wiring 已落地：quasar 词条从「暂未在星图标注」改为接线实况
        //（盛宴黑洞经 factLine 相行+渲染进食档呈现——非静默变更，断言随行为更新）
        assertTrue(AstroFacts.topic("quasar")!!.inUniverse.contains("已接线"))
        assertFalse(AstroFacts.topic("quasar")!!.inUniverse.contains("暂未"))
        // R327 吸积盘=kind=null 语义席位（镜像 quasar 模式——呈现态词条不改枚举）
        assertNull(
            "accretion_disk 不该有枚举对位（呈现态语义席位——镜像 quasar 模式）",
            AstroFacts.topic("accretion_disk")!!.kind,
        )
        // 宣判已接线口径（R327 wiring 落地——非静默变更，断言随行为更新）
        assertTrue(AstroFacts.topic("accretion_disk")!!.inUniverse.contains("小样探测"))
        assertTrue(AstroFacts.topic("accretion_disk")!!.howToFind != null)
        // R331 两现象词条=kind=null 语义席位（现象不改枚举不进 when 穷尽面——
        // 演出与 eventGloss 事件注归 R320 仲裁区）；howToFind+related 侧挂浮现路径
        assertNull("gravity_wave 不该有枚举对位（现象词条——R320 演出归其在飞批）", AstroFacts.topic("gravity_wave")!!.kind)
        assertNull("eclipse 不该有枚举对位（现象词条——R320 几何归其在飞批）", AstroFacts.topic("eclipse")!!.kind)
        assertTrue(AstroFacts.topic("gravity_wave")!!.howToFind != null)
        assertTrue(AstroFacts.topic("eclipse")!!.howToFind != null)
        // R331 图鉴奇观册 howToFind 补值（三新 kind 零覆盖缺口闭合——黑洞原生在库）
        assertTrue(AstroFacts.topic("neutron_star")!!.howToFind != null)
        assertTrue(AstroFacts.topic("quark_star")!!.howToFind != null)
        assertTrue(AstroFacts.topic("white_hole")!!.howToFind != null)
        // 浮现路径锚：supernova/planet 的 related 侧挂（词条浏览器 chips 即可达）
        assertTrue("gravity_wave" in AstroFacts.topic("supernova")!!.related)
        assertTrue("eclipse" in AstroFacts.topic("planet")!!.related)
        assertTrue("supernova" in AstroFacts.topic("gravity_wave")!!.related)
        assertTrue("planet" in AstroFacts.topic("eclipse")!!.related)
    }

    @Test
    fun `词条表-诞生链叙事序锚（防插错位）`() {
        // r325 E⑤ 单测锚：nebula→accretion_disk→protostar→planet 四键在 TOPICS
        // 中的相对序（吸积盘=星云与原恒星之间的中段链位——插错位本锚即红）
        val keys = AstroFacts.TOPICS.map { it.key }
        val iNebula = keys.indexOf("nebula")
        val iDisk = keys.indexOf("accretion_disk")
        val iProto = keys.indexOf("protostar")
        val iPlanet = keys.indexOf("planet")
        assertTrue(iNebula >= 0 && iDisk >= 0 && iProto >= 0 && iPlanet >= 0)
        assertTrue(
            "诞生链叙事序断裂：nebula($iNebula)→accretion_disk($iDisk)→protostar($iProto)→planet($iPlanet)",
            iNebula < iDisk && iDisk < iProto && iProto < iPlanet,
        )
        // 计数锚：TOPICS=25（18 原始+cmb/olbers（R319）+accretion_disk（R327）
        // +gravity_wave/eclipse（R331 预落）+lagrange（R321 批 4）
        // +proper_motion（R323 批 5——锚随行为变更显式更新））
        assertEquals(25, AstroFacts.TOPICS.size)
        // 对位更新锚：protostar 点燃句/planet 成型句/supernova 循环半句/nebula
        // related 衔接（inUniverse 增句不重写——既有段保留）
        assertTrue(AstroFacts.topic("protostar")!!.inUniverse.contains("新星诞生"))
        assertTrue(AstroFacts.topic("planet")!!.inUniverse.contains("正在成型"))
        assertTrue(AstroFacts.topic("supernova")!!.inUniverse.contains("灰烬中诞生"))
        assertTrue("nebula" in AstroFacts.topic("accretion_disk")!!.related)
        assertTrue("accretion_disk" in AstroFacts.topic("nebula")!!.related)
        assertTrue("accretion_disk" in AstroFacts.topic("planet")!!.related)
        assertTrue("accretion_disk" in AstroFacts.topic("protostar")!!.related)
    }

    @Test
    fun `词条表-R321拉格朗日点词条（批4概念席位）`() {
        // r315-I③/r331 预清单：kind=null 语义席位（现象词条不改枚举不进 when 穷尽面
        // ——R331 gravity_wave/eclipse 同模式）+「韦伯望远镜就停在 L2」现实应用
        assertNull("lagrange 不该有枚举对位（现象席位——特洛伊簇=渲染件非天体）", AstroFacts.topic("lagrange")!!.kind)
        val t = AstroFacts.topic("lagrange")!!
        assertTrue(t.what.contains("韦伯望远镜") && t.what.contains("L2"))
        assertTrue(t.what.contains("特洛伊") && t.what.contains("等边三角形"))
        // 本宇宙映射：共源双星语义+诚实红线（无共源=空驿站）
        assertTrue(t.inUniverse.contains("共源"))
        assertTrue(t.inUniverse.contains("有货没货数据说话"))
        // 浮现路径锚：binary 词条 related 补 lagrange（词条浏览器 chips 即可达——r315-I③）
        assertTrue("lagrange" in AstroFacts.topic("binary")!!.related)
        assertTrue("binary" in t.related)
        // related 键全可解（既有词条表完整性测试同口径——本条为 R321 侧显式锚）
        for (rk in t.related) assertTrue(AstroFacts.topic(rk) != null)
    }

    @Test
    fun `词条表-R323自行词条与nebula双型文案（批5概念席位）`() {
        // r315-J③/r331 预清单：proper_motion=kind=null 语义席位（数据判据件非天体
        // 种类——不改枚举不进 when 穷尽面，lagrange 同模式）+巴纳德星科普
        assertNull("proper_motion 不该有枚举对位（数据件席位——漂移矢量=渲染件非天体）", AstroFacts.topic("proper_motion")!!.kind)
        val pm = AstroFacts.topic("proper_motion")!!
        assertTrue(pm.what.contains("巴纳德星"))
        assertTrue(pm.what.contains("自行"))
        // 本宇宙映射：两窗构成差语义+诚实红线（样本不足或没动=不画）+小尾巴可见
        assertTrue(pm.inUniverse.contains("漂移"))
        assertTrue(pm.inUniverse.contains("小尾巴"))
        assertTrue(pm.inUniverse.contains("样本不足或没动=不画（诚实）"))
        // 浮现路径锚：nebula↔proper_motion 双向 related（词条浏览器 chips 即可达）
        assertTrue("proper_motion" in AstroFacts.topic("nebula")!!.related)
        assertTrue("nebula" in pm.related)
        for (rk in pm.related) assertTrue(AstroFacts.topic(rk) != null)
        // K③ nebula 双型文案锚：发射=产房/反射=普通未探索（判据=成员金矿候选占比）
        val neb = AstroFacts.topic("nebula")!!
        assertTrue(neb.inUniverse.contains("分两型"))
        assertTrue(neb.inUniverse.contains("发射星云"))
        assertTrue(neb.inUniverse.contains("产房"))
        assertTrue(neb.inUniverse.contains("普通星云"))
        assertTrue(neb.inUniverse.contains("星胚"))
        // 既有段保留（inUniverse 增句不重写旧语义——爬虫聚合句仍在）
        assertTrue(neb.inUniverse.contains("先在这里聚合"))
    }

    @Test
    fun `为什么链-R323漂移行（漂移注记门与缺据零漂移不出）`() {
        // 漂移注记在场=行出（两窗原值全数字——J③ 口径「近窗Y A% vs 远窗X B%」）
        val d = ProperMotionFx.driftOf(
            ProperMotionFx.Votes(12, 28, 8),  // 近窗：图文 58% 主导
            ProperMotionFx.Votes(30, 10, 8),   // 远窗：视频 62.5% 主导
        )
        assertNotNull("构造漂移样例失败（近 48 条/远 48 条应过门）", d)
        val chain = AstroFacts.whyChain(whyInput(drift = d))
        val q = chain.firstOrNull { it.question.contains("漂移") }
        assertTrue("漂移注记在场必有漂移行", q != null)
        val a = q!!.answer
        assertTrue(a.contains("从视频主导转向图文主导"))
        assertTrue(a.contains("近窗图文 58%"))
        assertTrue(a.contains("远窗视频 62%"))
        assertTrue(a.contains("小尾巴"))
        // 缺据 null=整条不出（默认口径——既有计数锚零回归的机制面）
        assertNull(
            "drift=null 缺据=行不出",
            AstroFacts.whyChain(whyInput()).firstOrNull { it.question.contains("漂移") },
        )
        // 位置锚：漂移行插在条件行族（⑥''——星风行后、视界行前区域）
        val driftIdx = chain.indexOfFirst { it.question.contains("漂移") }
        val domainIdx = chain.indexOfFirst { it.question.contains("聚在这片星域") }
        assertTrue(driftIdx in 0 until domainIdx)
    }

    @Test
    fun `为什么链-R321星风行（条件行门与缺据不出）`() {
        // 星风门与渲染同一单口（StarwindFx.isWindy——activity01Of 饱和导出+degree>0）
        val windy = AstroFacts.whyChain(whyInput(recent7 = 14, degree = 5))
        val q = windy.firstOrNull { it.question.contains("星风") }
        assertTrue(" windy 星必有星风行（recent7=14+degree=5）", q != null)
        val a = q!!.answer
        // 行内数字全本星（本周 X 条+N 条通道——缺据绝不编造的反面=在场必带真数字）
        assertTrue(a.contains("本周发布 14 条"))
        assertTrue(a.contains("5 条"))
        assertTrue(a.contains("14 条/周"))
        assertTrue(a.contains("粒子流"))
        // R376 星风泡环退役：文案随视觉本体除泡（「星风泡」字样应绝迹——锚随行为改写）
        assertFalse(a.contains("星风泡"))
        // 缺据三门全不出：门外（13）/无通道（degree=0）/无金矿档案（recent7=-1）
        assertNull("13 条<门=微风不可见=行不出", AstroFacts.whyChain(whyInput(recent7 = 13, degree = 5)).firstOrNull { it.question.contains("星风") })
        assertNull("degree=0=无外流通道=行不出", AstroFacts.whyChain(whyInput(recent7 = 30, degree = 0)).firstOrNull { it.question.contains("星风") })
        assertNull("recent7=-1 缺据=行不出", AstroFacts.whyChain(whyInput(recent7 = -1, degree = 5)).firstOrNull { it.question.contains("星风") })
        // 默认口径（recent7=0）不出——既有计数锚零回归的机制面
        assertNull(AstroFacts.whyChain(whyInput()).firstOrNull { it.question.contains("星风") })
        // 位置锚：星风行插在 ⑧ 星域行之前（条件行族——伴星/星风/视界与恒出行分界）
        val windyIdx = windy.indexOfFirst { it.question.contains("星风") }
        val domainIdx = windy.indexOfFirst { it.question.contains("聚在这片星域") }
        assertTrue(windyIdx in (domainIdx - 1) until domainIdx)
    }

    // ==================== 本星实况 ====================

    private fun liveInput(
        kind: CelestialKind = CelestialKind.RED_GIANT,
        items: Int = 12345,
        recent7: Int = 0,
        prev7: Int = 2,
        lastActivityDays: Long = 187L,
        firstSeenDays: Long = 620L,
        meanItems: Float = 6699f,
        volumeRank01: Float = 0.83f,
        spectral: TasteAstroPhysics.SpectralClass = TasteAstroPhysics.SpectralClass.K,
        memberCount: Int = -1,
        partnerTitle: String? = null,
        partnerWeight: Int = 0,
    ) = AstroFacts.LiveInput(
        kind, items, recent7, prev7, lastActivityDays, firstSeenDays,
        meanItems, volumeRank01, spectral, memberCount, partnerTitle, partnerWeight,
    )

    @Test
    fun `本星实况-红巨星带全数字（任务书示例口径）`() {
        val facts = AstroFacts.liveFacts(liveInput())
        // 质量档=存量+分位
        assertTrue(facts.any { it.contains("质量档") && it.contains("1.2 万条") && it.contains("83%") })
        // 光度=近 7 天
        assertTrue(facts.any { it.contains("光度") && it.contains("近 7 天发布 0 条") })
        // 温度谱
        assertTrue(facts.any { it.contains("温度谱") && it.contains("K 型") })
        // 年龄+断更
        assertTrue(facts.any { it.contains("年龄") && it.contains("620 天") })
        assertTrue(facts.any { it.contains("距上次辐射：187 天") })
        // 无黑洞倍率/无双星伴侣/无成员规模
        assertFalse(facts.any { it.contains("奥本海默倍率") })
        assertFalse(facts.any { it.contains("双星伴侣") })
        assertFalse(facts.any { it.contains("成员规模") })
    }

    @Test
    fun `本星实况-缺据诚实整条不出零编造`() {
        val facts = AstroFacts.liveFacts(
            liveInput(
                items = 0, recent7 = -1, prev7 = -1,
                lastActivityDays = -1L, firstSeenDays = -1L,
                meanItems = 0f, volumeRank01 = 0f,
            ),
        )
        // 缺据维度整条不出现
        assertFalse(facts.any { it.contains("质量档") })
        assertFalse(facts.any { it.contains("光度") })
        assertFalse(facts.any { it.contains("年龄") })
        assertFalse(facts.any { it.contains("距上次辐射") })
        // 温度谱恒出但带缺省诚实标注
        assertTrue(facts.any { it.contains("温度谱") && it.contains("缺据中性缺省") })
        // 零编造：任何行都不许带 -1/0 条 这类哨兵泄漏
        assertFalse(facts.any { it.contains("-1") })
    }

    @Test
    fun `本星实况-黑洞倍率与双星伴侣`() {
        val facts = AstroFacts.liveFacts(
            liveInput(
                kind = CelestialKind.BLACK_HOLE, items = 50276, meanItems = 6699f,
                partnerTitle = "伴星甲", partnerWeight = 42,
            ),
        )
        assertTrue(facts.any { it.contains("奥本海默倍率") && it.contains("7.5 倍") })
        assertTrue(facts.any { it.contains("双星伴侣") && it.contains("42 条关联证据") })
        // 黑洞不出温度谱段（光晕=吸积盘笔刷，非光谱型档）
        assertFalse(facts.any { it.contains("温度谱") })
    }

    // ==================== 「为什么」解释链 ====================

    private fun whyInput(
        kind: CelestialKind = CelestialKind.RED_GIANT,
        items: Int = 12345,
        recent7: Int = 0,
        prev7: Int = 2,
        lastActivityDays: Long = 187L,
        firstSeenDays: Long = 620L,
        volumeRank01: Float = 0.83f,
        meanItems: Float = 6699f,
        spectral: TasteAstroPhysics.SpectralClass = TasteAstroPhysics.SpectralClass.K,
        compVideo: Long = 0L,
        compPhoto: Long = 96L,
        compAudio: Long = 4L,
        compFromWatch: Boolean = false,
        watchDaysAgo: Long = -1L,
        watchSec: Long = 0L,
        watchedCount: Int = 0,
        degree: Int = 3,
        binaryWeight: Int = 0,
        memberCount: Int = -1,
        horizonEdge01: Float = -1f,
        horizonBeyondN: Int = -1,
        drift: ProperMotionFx.Drift? = null,
    ) = AstroFacts.WhyInput(
        kind, items, recent7, prev7, lastActivityDays, firstSeenDays,
        volumeRank01, meanItems, spectral, compVideo, compPhoto, compAudio,
        compFromWatch, watchDaysAgo, watchSec, watchedCount, degree, binaryWeight, memberCount,
        horizonEdge01, horizonBeyondN, drift,
    )

    @Test
    fun `为什么链-固定七问序（无伴星无视界）`() {
        val chain = AstroFacts.whyChain(whyInput())
        assertEquals(7, chain.size)
        assertTrue(chain[0].question.contains("为什么是红巨星"))
        assertTrue(chain[1].question.contains("颜色"))
        assertTrue(chain[2].question.contains("大小"))
        assertTrue(chain[3].question.contains("亮度"))
        assertTrue(chain[4].question.contains("位置"))
        // R331 ⑧⑨ 追加行（恒出——涌现布局星域聚簇+σ 尺度旅程）
        assertTrue(chain[5].question.contains("聚在这片星域"))
        assertTrue(chain[6].question.contains("一粒光点"))
    }

    @Test
    fun `为什么链-红巨星判据翻译带本星数字`() {
        val answer = AstroFacts.whyChain(whyInput())[0].answer
        // 判据=分位门+活跃门，双双带数字
        assertTrue(answer.contains("83%") && answer.contains("70%"))
        assertTrue(answer.contains("187 天") && answer.contains("60 天"))
    }

    @Test
    fun `为什么链-各类型判据口径`() {
        // 白矮星=死亡线
        assertTrue(
            AstroFacts.whyChain(whyInput(kind = CelestialKind.WHITE_DWARF, lastActivityDays = 213L))[0]
                .answer.contains("180"),
        )
        // 超新星=爆发倍数
        val sn = AstroFacts.whyChain(
            whyInput(kind = CelestialKind.SUPERNOVA, recent7 = 9, prev7 = 3),
        )[0].answer
        assertTrue(sn.contains("9") && sn.contains("3.0 倍"))
        // 黑洞=三重门
        assertTrue(
            AstroFacts.whyChain(
                whyInput(kind = CelestialKind.BLACK_HOLE, items = 50276, volumeRank01 = 0.99f),
            )[0].answer.contains("三重门"),
        )
        // 原恒星=新生线
        assertTrue(
            AstroFacts.whyChain(whyInput(kind = CelestialKind.PROTOSTAR, firstSeenDays = 34L))[0]
                .answer.contains("90"),
        )
        // 恒星缺据=诚实缺省
        assertTrue(
            AstroFacts.whyChain(
                whyInput(
                    kind = CelestialKind.STAR, items = 0, recent7 = -1, prev7 = -1,
                    lastActivityDays = -1L, volumeRank01 = 0f,
                ),
            )[0].answer.contains("诚实缺省"),
        )
        // 实体天体=判型口径
        assertTrue(
            AstroFacts.whyChain(whyInput(kind = CelestialKind.PULSAR))[0].answer.contains("bot"),
        )
    }

    @Test
    fun `为什么链-颜色构成主导与缺据`() {
        // 图文主导 → 青绿
        val c1 = AstroFacts.whyChain(whyInput())[1].answer
        assertTrue(c1.contains("图文") && c1.contains("96%") && c1.contains("青绿"))
        assertTrue(c1.contains("K 型")) // 温度谱同链
        // 视频主导+观看源
        val c2 = AstroFacts.whyChain(
            whyInput(compVideo = 80L, compPhoto = 20L, compAudio = 0L, compFromWatch = true),
        )[1].answer
        assertTrue(c2.contains("暖橙") && c2.contains("按你看过的构成"))
        // 无构成 → 诚实暗星色
        val c3 = AstroFacts.whyChain(
            whyInput(compVideo = 0L, compPhoto = 0L, compAudio = 0L),
        )[1].answer
        assertTrue(c3.contains("暗星色"))
        // 实体=身份色
        assertTrue(
            AstroFacts.whyChain(whyInput(kind = CelestialKind.CLUSTER))[1].answer.contains("身份色"),
        )
    }

    @Test
    fun `为什么链-双星伴星条目门`() {
        // 有伴星=八问（⑥ 条件行索引不动——⑧⑨ append 其后）
        val withBin = AstroFacts.whyChain(whyInput(binaryWeight = 72))
        assertEquals(8, withBin.size)
        assertTrue(withBin[5].question.contains("伴星"))
        assertTrue(withBin[5].answer.contains("72 条"))
        // 无伴星=七问（条目整条不出，⑧⑨ 恒出）
        assertEquals(7, AstroFacts.whyChain(whyInput(binaryWeight = 0)).size)
    }

    @Test
    fun `为什么链-R319观测视界第七问门（缺据与内部星不出）`() {
        // 缺据（默认 -1=zone 缺席）＝整行不出（七问原样）
        assertEquals(7, AstroFacts.whyChain(whyInput()).size)
        // 内部星（半径比 <0.85 观测带外）＝不出
        assertEquals(7, AstroFacts.whyChain(whyInput(horizonEdge01 = 0.5f)).size)
        assertEquals(7, AstroFacts.whyChain(whyInput(horizonEdge01 = 0.849f)).size)
        // 观测带内（≥0.85）＝第七问出场：问题名+本星百分比+奥伯斯/光在路上语义
        //（索引 5 不动——⑧⑨ append 其后）
        val withHz = AstroFacts.whyChain(whyInput(horizonEdge01 = 0.93f))
        assertEquals(8, withHz.size)
        assertTrue(withHz[5].question.contains("观测视界"))
        assertTrue(withHz[5].answer.contains("93%"))
        assertTrue(withHz[5].answer.contains("奥伯斯"))
        assertTrue(withHz[5].answer.contains("光还在路上"))
        // 伴星+视界同场=九问（两条件门独立叠加）
        assertEquals(9, AstroFacts.whyChain(whyInput(binaryWeight = 72, horizonEdge01 = 0.93f)).size)
    }

    @Test
    fun `为什么链-R331星域行与尺度行（⑧⑨ degree 双分支+σ 口径）`() {
        // ⑧ 星域聚簇：degree>0 带本星互联数字（默认 degree=3）
        val a1 = AstroFacts.whyChain(whyInput())[5].answer
        assertTrue(a1.contains("涌现布局"))
        assertTrue(a1.contains("3 条互链/共现边"))
        assertTrue(a1.contains("力的平衡"))
        // degree=0：诚实句不冒充（无互联证据）
        val a2 = AstroFacts.whyChain(whyInput(degree = 0))[5].answer
        assertTrue(a2.contains("没有互联证据"))
        assertFalse(a2.contains("0 条"))
        // ⑨ 尺度旅程：σ 对数口径+银河视角+乘法缩放+层连续（TasteScaleSigma 同源）
        val s = AstroFacts.whyChain(whyInput())[6].answer
        assertTrue(s.contains("σ"))
        assertTrue(s.contains("每远一格翻倍"))
        assertTrue(s.contains("双击空白"))
        assertTrue(s.contains("银河位"))
        assertTrue(s.contains("不是每颗星"))
        assertTrue(s.contains("速度∝高度"))
        assertTrue(s.contains("连续淡出"))
        // 两行恒出（实体天体同出——尺度是全图概念）
        assertEquals(7, AstroFacts.whyChain(whyInput(kind = CelestialKind.PULSAR)).size)
        assertEquals(7, AstroFacts.whyChain(whyInput(kind = CelestialKind.CLUSTER)).size)
    }

    @Test
    fun `为什么链-亮度与大小诚实缺省`() {
        // 全无证据=暗点档+暗星小点
        val chain = AstroFacts.whyChain(
            whyInput(
                kind = CelestialKind.STAR, items = 0, recent7 = -1, prev7 = -1,
                lastActivityDays = -1L, firstSeenDays = -1L, volumeRank01 = 0f,
            ),
        )
        assertTrue(chain[2].answer.contains("暗星小点"))
        assertTrue(chain[3].answer.contains("暗点档"))
        // 看过并入增重
        val watched = AstroFacts.whyChain(whyInput(watchSec = 7200L, watchedCount = 31, watchDaysAgo = 3L))
        assertTrue(watched[2].answer.contains("31 条") && watched[2].answer.contains("观看"))
        assertTrue(watched[3].answer.contains("3 天前看过"))
    }

    // ==================== 点亮即时科普小卡 ====================

    @Test
    fun `点亮小卡-全类非空且数据变体带数字`() {
        for (k in CelestialKind.entries) {
            assertTrue(AstroFacts.instantLine(k, 0, -1, -1L, -1L).isNotBlank())
        }
        assertTrue(AstroFacts.instantLine(CelestialKind.WHITE_DWARF, 100, -1, 187L, -1L).contains("187 天"))
        assertTrue(AstroFacts.instantLine(CelestialKind.PROTOSTAR, 0, -1, -1L, 34L).contains("34 天"))
        assertTrue(AstroFacts.instantLine(CelestialKind.SUPERNOVA, 0, 9, -1L, -1L).contains("9 条"))
        // 缺据变体无哨兵泄漏
        assertFalse(AstroFacts.instantLine(CelestialKind.WHITE_DWARF, 0, -1, -1L, -1L).contains("-1"))
    }

    @Test
    fun `点亮小卡-R327年轻变体与缺据回落`() {
        val young = TasteAstroPhysics.BirthFeatures(
            chatId = 7L,
            stage = TasteAstroPhysics.BirthStage.ACCRETING,
            planetsForming = false, firstSeenMs = 1L, probeItems = 120, tagsCount = 0,
        )
        // 触发门①：birth 注记非空（ACCRETING 吸积中——宣判后）
        val v1 = AstroFacts.instantLine(CelestialKind.STAR, 500, 3, -1L, 12L, young)
        assertTrue(v1.startsWith("✧ 新星诞生！") && v1.contains("诞生才 12 天"))
        // 触发门②：kind==PROTOSTAR（六态年轻档——birth=null 宣判窗前照判）
        val v2 = AstroFacts.instantLine(CelestialKind.PROTOSTAR, 0, -1, -1L, 5L)
        assertTrue(v2.startsWith("✧ 新星诞生！") && v2.contains("诞生才 5 天"))
        // 首见缺据：无数字变体（零编造——缺 K 天段）
        val v3 = AstroFacts.instantLine(CelestialKind.STAR, 500, 3, -1L, -1L, young)
        assertEquals("✧ 新星诞生！星云的尘埃聚成了它", v3)
        // 老星=现状文案（instantLine 原分支零改——birth=null 非 PROTOSTAR）
        assertEquals(
            "稳定核聚变的主序星——持续更新就是它的燃烧",
            AstroFacts.instantLine(CelestialKind.STAR, 500, 3, -1L, 40L),
        )
        assertEquals(
            "死而不灭的致密残骸",
            AstroFacts.instantLine(CelestialKind.WHITE_DWARF, 0, -1, -1L, -1L),
        )
        // NOT_BIRTH 注记不触发年轻变体（互斥/缺据档——回落原分支）
        val nb = TasteAstroPhysics.BirthFeatures(
            7L, TasteAstroPhysics.BirthStage.NOT_BIRTH, false, 1L, 0, 0,
        )
        assertEquals(
            "稳定核聚变的主序星——持续更新就是它的燃烧",
            AstroFacts.instantLine(CelestialKind.STAR, 500, 3, -1L, 40L, nb),
        )
    }

    @Test
    fun `点亮播报选星-R327年轻位内插与确定性`() {
        val youngIds = setOf(2L)
        // 同 kind 内年轻星先（年轻位内插——原恒位序不动：STAR 档内 2 压过 8）
        assertEquals(
            2L,
            AstroFacts.pickToastStar(
                listOf(8L to CelestialKind.STAR, 2L to CelestialKind.STAR),
                youngIds,
            ),
        )
        // 跨档不越权：老黑洞（rank 1）仍压过年轻恒星（rank 11）——原相对序零改
        assertEquals(
            9L,
            AstroFacts.pickToastStar(
                listOf(9L to CelestialKind.BLACK_HOLE, 2L to CelestialKind.STAR),
                youngIds,
            ),
        )
        // youngIds 空=原行为（同档 chatId 小者先——2 胜 8）
        assertEquals(
            2L,
            AstroFacts.pickToastStar(listOf(8L to CelestialKind.STAR, 2L to CelestialKind.STAR)),
        )
        // 确定性：同输入两跑一致
        val lit = listOf(8L to CelestialKind.STAR, 2L to CelestialKind.STAR, 5L to CelestialKind.PROTOSTAR)
        assertEquals(
            AstroFacts.pickToastStar(lit, youngIds),
            AstroFacts.pickToastStar(lit, youngIds),
        )
    }

    @Test
    fun `点亮播报选星-稀有度序与确定性`() {
        // 黑洞压过一切
        val lit = listOf(
            5L to CelestialKind.STAR,
            3L to CelestialKind.RED_GIANT,
            9L to CelestialKind.BLACK_HOLE,
            1L to CelestialKind.PROTOSTAR,
        )
        assertEquals(9L, AstroFacts.pickToastStar(lit))
        // 无黑洞按序：超新星>白矮星>红巨星>原恒星>…>恒星
        assertEquals(
            1L,
            AstroFacts.pickToastStar(
                listOf(7L to CelestialKind.STAR, 1L to CelestialKind.SUPERNOVA, 4L to CelestialKind.WHITE_DWARF),
            ),
        )
        // 同类平手=chatId 小者先（确定性）
        assertEquals(
            2L,
            AstroFacts.pickToastStar(listOf(8L to CelestialKind.STAR, 2L to CelestialKind.STAR)),
        )
        // 空表=null
        assertNull(AstroFacts.pickToastStar(emptyList()))
    }

    // ==================== 相关天体推荐 ====================

    @Test
    fun `相关推荐-计数与语义天体诚实`() {
        val counts = mapOf(
            CelestialKind.PROTOSTAR to 12,
            CelestialKind.RED_GIANT to 0, // 在库为零=「还没见到」诚实 0
        )
        val rel = AstroFacts.relatedWithCounts("star", counts)
        // star.related=protostar/red_giant/planet/binary 四枚
        assertEquals(4, rel.size)
        assertEquals("protostar", rel[0].first.key)
        assertEquals(12, rel[0].second)
        assertEquals(0, rel[1].second) // 红巨星 0=诚实计数（不隐藏）
        assertNull(rel[2].second) // 行星=无语义枚举对位——不带计数
        assertNull(rel[3].second) // 双星同理
        // 未知 key=空表
        assertTrue(AstroFacts.relatedWithCounts("no_such_key", counts).isEmpty())
    }

    // ==================== 学习路径钩子 ====================

    @Test
    fun `图鉴缺口-全见过空表未见过六行`() {
        assertTrue(AstroFacts.unseenKindHints(TasteAchievements.SIX_KINDS).isEmpty())
        val hints = AstroFacts.unseenKindHints(emptySet())
        assertEquals(TasteAchievements.SIX_KINDS.size, hints.size)
        for (h in hints) assertTrue(h.contains("："))
        // 部分见过=只出缺口（「原恒星：」含子串「恒星：」——断言用 startsWith 精确口径）
        val partial = AstroFacts.unseenKindHints(setOf(CelestialKind.STAR, CelestialKind.PULSAR))
        assertEquals(TasteAchievements.SIX_KINDS.size - 2, partial.size)
        assertFalse(partial.any { it.startsWith("恒星：") })
    }

    // ==================== R331 终态奇观册（图鉴另册——勋章外奇观） ====================

    @Test
    fun `图鉴奇观册-缺口行与稀有度序`() {
        // 全见过=空表（夸克/黑洞/中子/白洞四席全见过）
        assertTrue(AstroFacts.unseenWonderHints(TasteAchievements.WONDER_KINDS.toSet()).isEmpty())
        // 空集=四行，稀有度序=清单序（夸克>黑洞>中子>白洞——rarityRankOf 头部四档）
        val hints = AstroFacts.unseenWonderHints(emptySet())
        assertEquals(4, hints.size)
        assertEquals("夸克星", hints[0].substringBefore("："))
        assertEquals("黑洞", hints[1].substringBefore("："))
        assertEquals("中子星", hints[2].substringBefore("："))
        assertEquals("白洞", hints[3].substringBefore("："))
        for (h in hints) assertTrue(h.contains("：") && h.length > h.indexOf("：") + 1)
        // 部分见过=只出缺口（黑洞在册不因 SIX_KINDS 过滤误吞——勋章外另册独立）
        val partial = AstroFacts.unseenWonderHints(setOf(CelestialKind.BLACK_HOLE))
        assertEquals(3, partial.size)
        assertFalse(partial.any { it.startsWith("黑洞：") })
        // 奇观册与六态册零交集（黑洞/三终态不进等阶判据=R315-L③ 勋章外口径）
        assertTrue(TasteAchievements.WONDER_KINDS.none { it in TasteAchievements.SIX_KINDS })
    }

    @Test
    fun `图鉴奇观册-目击计数行诚实零与确定性`() {
        // 计数在场：稀有度序直出+颗数
        val line = AstroFacts.wonderCountsLine(
            mapOf(
                CelestialKind.BLACK_HOLE to 4,
                CelestialKind.NEUTRON_STAR to 1,
                CelestialKind.WHITE_HOLE to 2,
            ),
        )
        assertTrue(line.contains("黑洞 4 颗"))
        assertTrue(line.contains("中子星 1 颗"))
        assertTrue(line.contains("白洞 2 颗"))
        // 夸克缺席=「还没见到」诚实标注（0 不是错误是缺口本身）
        assertTrue(line.contains("夸克星 还没见到"))
        // 空表=四席全「还没见到」（宣判未落/新库的诚实形态）
        val empty = AstroFacts.wonderCountsLine(emptyMap())
        assertTrue(empty.contains("黑洞 还没见到"))
        assertTrue(empty.contains("夸克星 还没见到"))
        assertEquals(4, empty.count { it == '·' } + 1)
        // 确定性：同输入两跑逐位一致
        assertEquals(line, AstroFacts.wonderCountsLine(
            mapOf(
                CelestialKind.BLACK_HOLE to 4,
                CelestialKind.NEUTRON_STAR to 1,
                CelestialKind.WHITE_HOLE to 2,
            ),
        ))
    }

    @Test
    fun `目击事件科普注-三事件非空互异`() {
        val glosses = UniverseDirector.EventKind.entries.map { AstroFacts.eventGloss(it) }
        assertTrue(glosses.all { it.isNotBlank() })
        assertEquals(glosses.size, glosses.toSet().size)
    }

    // ==================== R352 反茧房·探索边界段（成就面板叙事串联） ====================

    @Test
    fun `探索边界-视界行计数与缺据门`() {
        // beyondN>0=出行：全量计数在场+彗星预告钩子（C④ 白话）+金矿口径
        val line = AstroFacts.edgeHorizonLine(37)!!
        assertTrue(line.contains("37 个频道"))
        assertTrue(line.contains("光在路上"))
        assertTrue(line.contains("金矿"))
        assertTrue(line.contains("彗星"))
        // 缺据/零差集（0 与 -1 面板侧不可分）=整行不出（缺据不冒充）
        assertEquals(null, AstroFacts.edgeHorizonLine(0))
        assertEquals(null, AstroFacts.edgeHorizonLine(-1))
        // 确定性：同输入两跑逐位一致
        assertEquals(line, AstroFacts.edgeHorizonLine(37))
    }

    @Test
    fun `探索边界-产房行三态与诚实退句`() {
        // 产房在场=破茧去处句：团数+产房内频道数+星胚语义（「等着点亮成原恒星」
        // 与星云词条 inUniverse 同口径）
        val nursery = AstroFacts.edgeNurseryLine(fogN = 6, nurseryN = 2, nurseryChannels = 11, fogChannels = 30)!!
        assertTrue(nursery.contains("2 团发射星云"))
        assertTrue(nursery.contains("11 个频道"))
        assertTrue(nursery.contains("产房"))
        assertTrue(nursery.contains("金矿候选"))
        // 无产房=普通暗雾诚实退句（金矿候选不过半不冒充摇篮——团数+全雾频道账）
        val plain = AstroFacts.edgeNurseryLine(fogN = 5, nurseryN = 0, nurseryChannels = 0, fogChannels = 22)!!
        assertTrue(plain.contains("5 团暗雾"))
        assertTrue(plain.contains("22 个没看过的频道"))
        assertTrue(plain.contains("金矿候选过半"))
        // 零雾（在图频道全看过=茧房内无雾）=整行不出（不编造「未探索」）
        assertEquals(null, AstroFacts.edgeNurseryLine(fogN = 0, nurseryN = 0, nurseryChannels = 0, fogChannels = 0))
        // 确定性
        assertEquals(nursery, AstroFacts.edgeNurseryLine(fogN = 6, nurseryN = 2, nurseryChannels = 11, fogChannels = 30))
    }

    @Test
    fun `为什么链-第七问茧房外追加句缺省关`() {
        // 追加句在场：>0 时计数句插在「你还没观测到它」与「整片宇宙」之间
        val withN = AstroFacts.whyChain(whyInput(horizonEdge01 = 0.93f, horizonBeyondN = 42))[5].answer
        assertTrue(withN.contains("茧房外共有 42 个频道的光还在路上"))
        assertTrue(withN.indexOf("还没观测到它") < withN.indexOf("茧房外共有"))
        assertTrue(withN.indexOf("茧房外共有") < withN.indexOf("整片宇宙"))
        // 缺据（默认 -1）＝句不追加——⑦ 行本体与缺省文案原样（既有锚零破）
        val noN = AstroFacts.whyChain(whyInput(horizonEdge01 = 0.93f))[5].answer
        assertFalse(noN.contains("茧房外"))
        // 零差集同缺据（0 不冒充「有 N 个」）
        assertFalse(AstroFacts.whyChain(whyInput(horizonEdge01 = 0.93f, horizonBeyondN = 0))[5].answer.contains("茧房外"))
        // 行数与门零改：追加句是答案内子句——八问结构原样（缺据/内部星七问）
        assertEquals(8, AstroFacts.whyChain(whyInput(horizonEdge01 = 0.93f, horizonBeyondN = 42)).size)
        assertEquals(7, AstroFacts.whyChain(whyInput(horizonEdge01 = 0.5f, horizonBeyondN = 42)).size)
    }

    // ==================== 确定性 ====================

    @Test
    fun `确定性-同输入两跑逐位一致`() {
        val li = liveInput()
        assertEquals(AstroFacts.liveFacts(li), AstroFacts.liveFacts(li))
        val wi = whyInput(binaryWeight = 7)
        val a = AstroFacts.whyChain(wi)
        val b = AstroFacts.whyChain(wi)
        assertEquals(a.map { it.question }, b.map { it.question })
        assertEquals(a.map { it.answer }, b.map { it.answer })
        assertEquals(
            AstroFacts.instantLine(CelestialKind.RED_GIANT, 1, 2, 3L, 4L),
            AstroFacts.instantLine(CelestialKind.RED_GIANT, 1, 2, 3L, 4L),
        )
    }
}
