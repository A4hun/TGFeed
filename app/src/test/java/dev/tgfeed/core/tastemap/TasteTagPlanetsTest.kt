package dev.tgfeed.core.tastemap

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R233 行星层纯函数单测：聚合/轨道反比/大小/确定性/尘埃相位时间映射/LOD 常量
 * 口径/指纹留重/空态诚实。
 */
class TasteTagPlanetsTest {

    private fun item(
        key: String,
        msgId: Long,
        dateSec: Int,
        tags: List<String>?,
        chatId: Long = 1000L,
    ) = VideoItem(
        key = key, chatId = chatId, messageId = msgId, channelTitle = "频道A", caption = "",
        durationSec = 0, width = 0, height = 0, fileId = -1, sizeBytes = 0, playUri = null,
        dateEpochSec = dateSec, itemTags = tags,
    )

    private fun sample(): List<VideoItem> = listOf(
        item("a", 10, 100, listOf("舞蹈", "练习")),
        item("b", 9, 200, listOf("舞蹈")),
        item("c", 8, 300, listOf("舞蹈", "日常")),
        item("d", 7, 400, null), // 未解析存量：不进聚合（诚实）
        item("e", 6, 500, listOf("日常", "日常")), // 同条双计同 tag
        item("f", 5, 600, emptyList()), // 已解析无标签：权威空
        item("g", 4, 700, listOf("片段")),
    )

    @Test
    fun `聚合-计数与降序`() {
        val sys = TasteTagPlanets.build(1000L, sample())
        // 舞蹈=3（a/b/c） 日常=3（c 一次+e 两次） 练习=1 片段=1
        assertEquals(3, sys.planets.first().count)
        assertEquals(7, sys.sampleCount) // 样本=7 条（「近端 N 条」口径的 N）
        assertTrue("计数降序", sys.planets.zipWithNext().all { (a, b) -> a.count >= b.count })
        val byTag = sys.planets.associateBy { it.tag }
        assertEquals(3, byTag.getValue("舞蹈").count)
        assertEquals(3, byTag.getValue("日常").count)
        assertEquals(1, byTag.getValue("片段").count)
        assertEquals(5, sys.taggedCount) // 5 条带 tag（d null、f 空表不计）
        assertEquals(4, sys.planets.size) // 舞蹈/日常/练习/片段
    }

    @Test
    fun `聚合-fold归一大小写合并`() {
        val items = listOf(
            item("a", 10, 100, listOf("Dance")),
            item("b", 9, 200, listOf("dance")),
        )
        val sys = TasteTagPlanets.build(1000L, items)
        assertEquals(1, sys.planets.size)
        assertEquals(2, sys.planets.first().count)
    }

    @Test
    fun `轨道-高频tag离恒星更近`() {
        val items = (0 until 6).map { item("k$it", 100L - it, 100, listOf("高频")) } +
            (0 until 2).map { item("l$it", 90L - it, 100, listOf("低频")) }
        val sys = TasteTagPlanets.build(1000L, items)
        val hi = sys.planets.first { it.tag == "高频" }
        val lo = sys.planets.first { it.tag == "低频" }
        assertTrue("高频 tag 轨道应更靠内（${hi.orbitR01} < ${lo.orbitR01}）", hi.orbitR01 < lo.orbitR01)
        assertTrue("轨道界内 0..1", sys.planets.all { it.orbitR01 in 0f..1f })
        // 大小 ∝ sqrt 频率比
        assertTrue(hi.sizeR01 > lo.sizeR01)
    }

    @Test
    fun `确定性-同输入同输出`() {
        val a = TasteTagPlanets.build(1000L, sample())
        val b = TasteTagPlanets.build(1000L, sample())
        assertEquals(a.planets.size, b.planets.size)
        a.planets.zip(b.planets).forEach { (p, q) ->
            assertEquals(p.tag, q.tag)
            assertEquals(p.count, q.count)
            assertEquals(p.orbitR01, q.orbitR01, 0f)
            assertEquals(p.phase01, q.phase01, 0f)
            assertEquals(p.omegaRadPerSec, q.omegaRadPerSec, 0f)
            assertTrue(p.itemIdx.contentEquals(q.itemIdx))
        }
    }

    @Test
    fun `尘埃-itemIdx时间升序与跨度`() {
        val sys = TasteTagPlanets.build(1000L, sample())
        val dance = sys.planets.first { it.tag == "舞蹈" }
        // a(100)/b(200)/c(300) → 升序 100..300
        val dates = dance.itemIdx.map { sample()[it].dateEpochSec }
        assertEquals(listOf(100, 200, 300), dates)
        assertEquals(100L, dance.dustMinSec)
        assertEquals(300L, dance.dustMaxSec)
    }

    @Test
    fun `尘埃-相位时间映射`() {
        // 一圈=时间跨度：min→0、max→2π、中点→π
        assertEquals(0f, TasteTagPlanets.dustAngleOf(100L, 100L, 300L), 1e-4f)
        assertEquals(6.2831853f, TasteTagPlanets.dustAngleOf(300L, 100L, 300L), 1e-4f)
        assertEquals(3.1415926f, TasteTagPlanets.dustAngleOf(200L, 100L, 300L), 1e-4f)
        // 跨度 0（同时段发布）→ 恒 0：聚集是考古信号本身，不假散布
        assertEquals(0f, TasteTagPlanets.dustAngleOf(50L, 50L, 50L), 0f)
        // 出界钳回 [0,1]（脏数据防御）
        assertTrue(TasteTagPlanets.dustAngleOf(999L, 100L, 300L) in 0f..6.2831854f)
    }

    @Test
    fun `上限-MAX_PLANETS按计数留重`() {
        // 90 条 → 30 个 tag 各 3 次 → 超 24 上限按序留重
        val items = (0 until 90).map { i ->
            item("k$i", 1000L - i, 100, listOf("tag${i / 3}"))
        }
        val sys = TasteTagPlanets.build(1000L, items)
        assertEquals(TasteTagPlanets.MAX_PLANETS, sys.planets.size)
        // 留重的全是 3 次档；同计数按 tag 字典序（确定性留重——首行星 tag0）
        assertTrue(sys.planets.all { it.count == 3 })
        assertEquals("tag0", sys.planets.first().tag)
    }

    @Test
    fun `空态-无tag样本诚实空系统`() {
        val items = listOf(
            item("a", 10, 100, null),
            item("b", 9, 200, emptyList()),
        )
        val sys = TasteTagPlanets.build(1000L, items)
        assertTrue(sys.planets.isEmpty())
        assertEquals(2, sys.sampleCount)
        assertEquals(0, sys.taggedCount)
        // 退化口径：单 tag 全 1 次 → 均匀轨道档（不除 0 不塌缩）
        val one = TasteTagPlanets.build(1000L, listOf(item("a", 1, 1, listOf("x")), item("b", 2, 2, listOf("y"))))
        assertEquals(2, one.planets.size)
        assertEquals(one.planets[0].orbitR01, one.planets[1].orbitR01, 0f)
    }

    @Test
    fun `指纹-fold键计数与留重`() {
        val items = (0 until 40).map { i ->
            item("k$i", 100L - i, 100, listOf("t${i % 10}", "t${10 + i % 5}"))
        }
        val fp = TasteTagPlanets.fingerprintOf(items, limit = 6)
        assertEquals(6, fp.size)
        assertEquals(8, fp.getValue("t12")) // t10..t14 各 8 次
        // 计数降序留重：8 次档 5 个全留 + 4 次档 1 个（字典序 t0）
        assertEquals(5, fp.values.count { it == 8 })
        assertEquals(1, fp.values.count { it == 4 })
        assertEquals(4, fp.getValue("t0"))
    }

    @Test
    fun `相位-确定性哈希界内`() {
        val sys = TasteTagPlanets.build(1000L, sample())
        for (p in sys.planets) {
            assertTrue(p.phase01 in 0f..1f)
            assertTrue(p.omegaRadPerSec > 0f)
        }
    }

    @Test
    fun `LOD-档位常量单调`() {
        assertTrue(TasteTagPlanets.LOD_MID_PLANETS < TasteTagPlanets.LOD_NEAR_PLANETS)
        assertTrue(TasteTagPlanets.LOD_NEAR_PLANETS <= TasteTagPlanets.MAX_PLANETS)
        assertTrue(TasteTagPlanets.SAMPLE_LIMIT > 0)
    }

    @Test
    fun `渲染口径-行星tag无井号前缀`() {
        val sys = TasteTagPlanets.build(1000L, sample())
        for (p in sys.planets) assertTrue(!p.tag.startsWith("#"))
    }

    // ==================== R236 账本模式（build(chatId, items, ledger)） ====================

    @Test
    fun `账本-排序与计数换全量口径`() {
        // 账本里「练习」50 次（样本仅 1）——计数/排序跟账本走（治样本偏差）
        val sys = TasteTagPlanets.build(1000L, sample(), listOf("练习" to 50, "舞蹈" to 9))
        assertEquals(2, sys.planets.size)
        assertEquals("练习", sys.planets.first().tag)
        assertEquals(50, sys.planets.first().count)
        assertEquals(9, sys.planets.last().count)
        // 样本面统计保持近端口径（诚实诊断不变）
        assertEquals(7, sys.sampleCount)
        assertEquals(5, sys.taggedCount)
    }

    @Test
    fun `账本-样本外长尾行星尘埃空缺省`() {
        // 账本有、近端样本没有的 tag：行星在场（旧口径整颗缺席）、尘埃空（诚实缺省）
        val sys = TasteTagPlanets.build(1000L, sample(), listOf("考古" to 120))
        assertEquals(1, sys.planets.size)
        val p = sys.planets.first()
        assertEquals("考古", p.tag)
        assertEquals(120, p.count)
        assertTrue(p.itemIdx.isEmpty())
        assertEquals(0L, p.dustMinSec)
        assertEquals(0L, p.dustMaxSec)
    }

    @Test
    fun `账本-空账本回退样本聚合`() {
        // 回填窗口期/无 tag 频道：ledger 空回退 R233 原版行为（不断层）
        val a = TasteTagPlanets.build(1000L, sample(), emptyList())
        val b = TasteTagPlanets.build(1000L, sample())
        assertEquals(b.planets.map { it.tag }, a.planets.map { it.tag })
        assertEquals(b.planets.map { it.count }, a.planets.map { it.count })
        assertEquals(b.planets.map { it.itemIdx.toList() }, a.planets.map { it.itemIdx.toList() })
    }

    @Test
    fun `账本-尘埃样本命中与跨度回填`() {
        val sys = TasteTagPlanets.build(1000L, sample(), listOf("舞蹈" to 3))
        val dance = sys.planets.first()
        assertEquals("舞蹈", dance.tag)
        assertEquals(3, dance.count)
        assertEquals(listOf(100, 200, 300), dance.itemIdx.map { sample()[it].dateEpochSec })
        assertEquals(100L, dance.dustMinSec)
        assertEquals(300L, dance.dustMaxSec)
    }

    @Test
    fun `账本-display原形fold键匹配样本变体`() {
        val items = listOf(
            item("a", 10, 100, listOf("Dance")),
            item("b", 9, 200, listOf("dance")),
        )
        // display=首见原形「Dance」；fold 键命中样本两条大小写变体
        val sys = TasteTagPlanets.build(1000L, items, listOf("Dance" to 9))
        assertEquals(1, sys.planets.size)
        assertEquals("Dance", sys.planets.first().tag) // 行星标签显示原形不降级
        assertEquals(2, sys.planets.first().itemIdx.size)
    }

    @Test
    fun `账本-同fold键去重保计数高者`() {
        val sys = TasteTagPlanets.build(1000L, sample(), listOf("dance" to 3, "Dance" to 5))
        val hits = sys.planets.filter { it.count == 5 || it.count == 3 }
        assertEquals(1, hits.size) // 同 fold 键不裂成两颗行星
    }

    @Test
    fun `账本-上限留重与确定性`() {
        val ledger = (0 until 40).map { "t$it" to (40 - it) } // 计数全降序
        val a = TasteTagPlanets.build(1000L, sample(), ledger)
        val b = TasteTagPlanets.build(1000L, sample(), ledger)
        assertEquals(TasteTagPlanets.MAX_PLANETS, a.planets.size)
        assertTrue("计数降序", a.planets.zipWithNext().all { (x, y) -> x.count >= y.count })
        a.planets.zip(b.planets).forEach { (x, y) ->
            assertEquals(x.tag, y.tag)
            assertEquals(x.count, y.count)
            assertEquals(x.phase01, y.phase01, 0f)
            assertTrue(x.itemIdx.contentEquals(y.itemIdx))
        }
    }

    // ==================== R328 行星成型贴脸拍坡道参重载（API 只加不改锚） ====================

    @Test
    fun `坡道重载-三参与四参APPEAR_RAMP逐位相等`() {
        // API 只加不改机检锚：旧 3 参签名委托 4 参（ramp=APPEAR_RAMP）——现状
        // 调用面（渲染器/命中测试）零行为变化的逐位实证
        for (total in intArrayOf(1, 5, 10, 15)) {
            for (index in 0 until total) {
                for (ratio in floatArrayOf(7.5f, 6.0f, 5.4f, 4.2f, 3.6f, 3.0f, 2.0f)) {
                    assertEquals(
                        "3 参旧签名与 4 参(APPEAR_RAMP) 必须逐位相等：total=$total idx=$index ratio=$ratio",
                        TasteTagPlanets.appearAlpha(ratio, index, total),
                        TasteTagPlanets.appearAlpha(ratio, index, total, TasteTagPlanets.APPEAR_RAMP),
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun `坡道重载-成型档放宽坡道更慢且域内单调`() {
        // 成型档单源消费锚：TasteBirthFx.formingRampWidth(APPEAR_RAMP)
        // =APPEAR_RAMP×FORMING_RAMP_MULT≈1.44（出场更慢=「正在聚成」读法）
        val wide = TasteBirthFx.formingRampWidth(TasteTagPlanets.APPEAR_RAMP)
        assertEquals(
            "成型坡道宽度=基坡道×放缓倍率（TasteBirthFx 单源）",
            TasteTagPlanets.APPEAR_RAMP * TasteBirthFx.FORMING_RAMP_MULT,
            wide,
            1e-4f,
        )
        assertTrue("放宽后坡道应更宽（更慢）", wide > TasteTagPlanets.APPEAR_RAMP)
        for (total in intArrayOf(5, 10, 15)) {
            for (index in 0 until total) {
                var prev = -1f // 首样本允许 0（远档未浮现——单调从「不可见」起步）
                for (ratio in floatArrayOf(7.5f, 6.0f, 5.4f, 4.8f, 4.2f, 3.6f, 3.0f, 2.0f)) {
                    val aWide = TasteTagPlanets.appearAlpha(ratio, index, total, wide)
                    val aBase = TasteTagPlanets.appearAlpha(ratio, index, total)
                    // 域 0..1；ratio 减小（飞近）alpha 单调不减；同位放宽档 ≤ 基档（更慢淡入）
                    assertTrue("域 0..1：ratio=$ratio aWide=$aWide", aWide in 0f..1f)
                    assertTrue("飞近单调不减：prev=$prev now=$aWide", aWide >= prev)
                    assertTrue("同位放宽档不快于基档：ratio=$ratio wide=$aWide base=$aBase", aWide <= aBase)
                    prev = aWide
                }
            }
        }
    }

    @Test
    fun `坡道重载-边界与防御档`() {
        // total≤0 恒 0（空档位——与旧签名同口径）
        assertEquals(0f, TasteTagPlanets.appearAlpha(3.0f, 0, 0, 1.44f), 0f)
        assertEquals(0f, TasteTagPlanets.appearAlpha(3.0f, 0, -1, 1.44f), 0f)
        // ramp≤0 防御档=阶跃（零宽坡道极限——公式除零防御，正常调用域不达）：
        // total=10/index=0 的 appearRatio=APPEAR_RATIO=6.0
        assertEquals(1f, TasteTagPlanets.appearAlpha(5.9f, 0, 10, 0f), 0f)
        assertEquals(0f, TasteTagPlanets.appearAlpha(6.0f, 0, 10, 0f), 0f)
        assertEquals(0f, TasteTagPlanets.appearAlpha(6.1f, 0, 10, -0.5f), 0f)
        // 域钳制：远档（ratio≥appearRatio+ramp）恒 0；贴脸档（ratio≤appearRatio−ramp）恒 1
        assertEquals(0f, TasteTagPlanets.appearAlpha(20f, 0, 10, 1.44f), 0f)
        assertEquals(1f, TasteTagPlanets.appearAlpha(1.0f, 0, 10, 1.44f), 0f)
    }
}

/**
 * R233 内容关联弧判定单测：Jaccard/top-3 共享双判据、防自环、排序确定性、上限留重。
 */
class TasteTagAffinityTest {

    @Test
    fun `判据-Jaccard过线`() {
        // 焦点 {a,b,c,d}；对方 {a,b,c,e}：交 3 并 5 → 0.6 ≥ 0.3 → 弧
        val focus = mapOf("a" to 1, "b" to 1, "c" to 1, "d" to 1)
        val others = mapOf(7L to mapOf("a" to 1, "b" to 1, "c" to 1, "e" to 1))
        val arcs = TasteTagAffinity.arcTargets(focus, 1L, others)
        assertEquals(1, arcs.size)
        assertEquals(7L, arcs[0].chatId)
        assertEquals(0.6f, arcs[0].jaccard, 1e-4f)
        // 焦点 top-3 = a/b/c（同计数字典序），对方有 a → top-rank 共享也在场
        assertEquals("a", arcs[0].sharedTag)
        assertEquals(1, arcs[0].topRankShared)
    }

    @Test
    fun `判据-共享top3放宽大频道`() {
        // 焦点 top3={x,y,z}；对方有几百 tag 且只有 x 共享 → Jaccard 极低但弧成立
        val focus = mapOf("x" to 9, "y" to 8, "z" to 7)
        val otherTags = (0 until 100).associate { "tag$it" to 1 } + mapOf("x" to 1)
        val arcs = TasteTagAffinity.arcTargets(focus, 1L, mapOf(9L to otherTags))
        assertEquals(1, arcs.size)
        assertEquals("x", arcs[0].sharedTag)
        assertTrue("Jaccard 应远低于阈值（${arcs[0].jaccard}）", arcs[0].jaccard < TasteTagAffinity.JACCARD_MIN)
    }

    @Test
    fun `判据-零共享无弧`() {
        val focus = mapOf("a" to 3, "b" to 2, "c" to 1)
        val arcs = TasteTagAffinity.arcTargets(focus, 1L, mapOf(7L to mapOf("x" to 1, "y" to 1)))
        assertTrue(arcs.isEmpty())
    }

    @Test
    fun `防自环-自家不进弧`() {
        val focus = mapOf("a" to 1)
        val arcs = TasteTagAffinity.arcTargets(focus, 7L, mapOf(7L to focus))
        assertTrue(arcs.isEmpty())
    }

    @Test
    fun `排序-topRank优先于Jaccard`() {
        val focus = mapOf("x" to 9, "y" to 8, "z" to 7, "w" to 1, "u" to 1)
        val topRanker = mapOf("x" to 5, "q" to 1, "r" to 1) // 共享 top1=x，Jaccard=1/7
        val jaccarder = mapOf("w" to 1, "u" to 1, "v" to 1) // 交 2 并 6 → 0.33 纯 Jaccard 弧
        val arcs = TasteTagAffinity.arcTargets(focus, 1L, mapOf(2L to topRanker, 3L to jaccarder))
        assertEquals(2, arcs.size)
        assertEquals(2L, arcs[0].chatId) // top-rank=1 优先（虽然 Jaccard 更低）
        assertEquals(1, arcs[0].topRankShared)
        assertEquals("x", arcs[0].sharedTag)
        assertEquals(3L, arcs[1].chatId)
        assertNull(arcs[1].sharedTag)
    }

    @Test
    fun `上限-MAX_ARCS确定性留重`() {
        val focus = mapOf("x" to 9)
        val others = (1L..30L).associateWith { id -> mapOf("x" to 1, "pad$id" to 1) }
        val arcs = TasteTagAffinity.arcTargets(focus, 0L, others)
        assertEquals(TasteTagAffinity.MAX_ARCS, arcs.size)
        // 同判据同计数 → chatId 升序破平（确定性）
        assertEquals(arcs.map { it.chatId }.sorted(), arcs.map { it.chatId })
        // 再跑一遍逐位一致
        val again = TasteTagAffinity.arcTargets(focus, 0L, others)
        assertEquals(arcs.map { it.chatId }, again.map { it.chatId })
    }

    @Test
    fun `空态-空指纹零弧`() {
        assertTrue(TasteTagAffinity.arcTargets(emptyMap(), 1L, mapOf(2L to mapOf("a" to 1))).isEmpty())
        assertTrue(TasteTagAffinity.arcTargets(mapOf("a" to 1), 1L, emptyMap()).isEmpty())
        assertTrue(TasteTagAffinity.arcTargets(mapOf("a" to 1), 1L, mapOf(2L to emptyMap())).isEmpty())
    }

    @Test
    fun `口径-sharedTag为null的纯Jaccard弧`() {
        // 焦点 top3 全不共享但 Jaccard ≥ 0.3
        val focus = mapOf("a" to 9, "b" to 8, "c" to 7, "d" to 1, "e" to 1)
        val other = mapOf("d" to 1, "e" to 1, "f" to 1, "g" to 1, "h" to 1)
        // 交 {d,e}=2 并 8 → 0.25 < 0.3 无弧；并 6 时 2/6=0.33 过线
        val other2 = mapOf("d" to 1, "e" to 1, "f" to 1)
        assertTrue(TasteTagAffinity.arcTargets(focus, 1L, mapOf(2L to other)).isEmpty())
        val arcs = TasteTagAffinity.arcTargets(focus, 1L, mapOf(3L to other2))
        assertEquals(1, arcs.size)
        assertNull(arcs[0].sharedTag)
        assertEquals(0, arcs[0].topRankShared)
        assertNotNull(arcs[0].chatId)
    }
}
