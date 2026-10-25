package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R303c P1 生产输入构建器单测（TasteEmergentSource——接互链真源的核心件）：
 * 家弹簧带口径/节点构建（质量 λ 温启初值）/链对构建（互链∪top-8 Jaccard∪
 * 双星束缚态、s 单口、strong L、留重确定性）/裁决回退链（四拒收码+诚实 mode）/
 * 温启参数与搬家帽（§6.9——warmFinal 位置级钳+投影兜底）/自行钩子（帽+归一+
 * 零向量不动）。纪律锚照 R303b 模式：纯 JVM、确定性断言、缺源诚实降档反例。
 */
class TasteEmergentSourceTest {

    private val S = TasteEmergentSource
    private val F = TasteEmergentForces

    // ==================== 家弹簧（§3.8 观察者径向偏好） ====================

    @Test
    fun `家弹簧-常看订阅进近带且投入度单调`() {
        val nowMs = 1_700_000_000_000L
        // 满投入（订阅+刚看+观看=全池最大）→ 近带下沿
        val hot = S.rPrefOf(36000L, nowMs, true, 0.5f, nowMs, 36000L)
        assertTrue("常看星应进近带，得 $hot", hot >= S.HOME_NEAR && hot <= S.HOME_NEAR_MAX)
        assertEquals(S.HOME_NEAR, hot, 1e-3f)
        // 陌生星 → 中远带（volume01=1 大频道靠中远带下沿）
        val stranger = S.rPrefOf(0L, 0L, false, 1f, nowMs, 36000L)
        assertEquals(S.HOME_MID_MIN, stranger, 1e-3f)
        val strangerSmall = S.rPrefOf(0L, 0L, false, 0f, nowMs, 36000L)
        assertEquals(S.HOME_MID_MAX, strangerSmall, 1e-3f)
        // 投入度单调：同观看账，越久没看越远（fresh01 衰减）
        var prev = -1f
        for (days in 0..90 step 10) {
            val lastMs = nowMs - days * 86_400_000L
            val r = S.rPrefOf(36000L, lastMs, true, 0.5f, nowMs, 36000L)
            assertTrue("断更 $days 天 rPref=$r 应递远（≥ $prev）", r >= prev)
            prev = r
        }
        // 订阅加成：同条件下订阅者更近
        val sub = S.rPrefOf(1000L, nowMs - 30L * 86_400_000L, true, 0.5f, nowMs, 36000L)
        val noSub = S.rPrefOf(1000L, nowMs - 30L * 86_400_000L, false, 0.5f, nowMs, 36000L)
        assertTrue("订阅加成应更近：sub=$sub ≤ noSub=$noSub", sub <= noSub)
    }

    @Test
    fun `家弹簧-从未看过但订阅走缺省档不越带`() {
        val nowMs = 1_700_000_000_000L
        val r = S.rPrefOf(0L, 0L, true, 0.5f, nowMs, 1000L)
        assertTrue("缺省档也必须在近带内：$r", r >= S.HOME_NEAR && r <= S.HOME_NEAR_MAX)
    }

    // ==================== 节点构建（生产字段→引擎口径） ====================

    private fun spec(i: Int, items: Int = 100 + i * 10, lastAct: Long = 1_700_000_000L) =
        TasteEmergentSource.StarSpec(
            chatId = 1000L + i, itemsCount = items, rho = 4f + i,
            lastActivitySec = lastAct, volume01 = 0.5f,
            watchSec = if (i % 3 == 0) 5000L else 0L,
            lastWatchMs = if (i % 3 == 0) 1_699_900_000_000L else 0L,
            subscribed = i % 4 == 0,
        )

    @Test
    fun `节点-质量归一与断更λ与缺据诚实`() {
        val nowSec = 1_700_010_000L
        val specs = listOf(spec(0, items = 1000), spec(1, items = 100), spec(2, items = 10))
        val n = specs.size
        val nodes = S.buildNodes(
            specs, FloatArray(n) { it * 100f }, FloatArray(n), FloatArray(n),
            nowMs = 1_700_010_000_000L, maxWatchSec = 5000L, nowSec = nowSec,
        )
        // 质量归一：最大条数星 mass01=1
        assertEquals(1f, nodes[0].mass01, 1e-4f)
        assertTrue("小星质量应 >0 且 <1", nodes[2].mass01 > 0f && nodes[2].mass01 < 1f)
        // 断更 10000s/86400 ≈ 0.116 天 → λ≈0.998（近 1）
        assertTrue("短断更 λ 应 ≈1（得 ${nodes[0].lambda}）", nodes[0].lambda > 0.99f)
        // lastActivitySec=0（缺据）→ λ=1（不冤枉活星）
        val noData = S.buildNodes(
            listOf(spec(0, lastAct = 0L)), FloatArray(1), FloatArray(1), FloatArray(1),
            nowMs = 0L, maxWatchSec = 0L, nowSec = nowSec,
        )
        assertEquals(1f, noData[0].lambda, 0f)
        // ρ=bodyRadius 直传
        assertEquals(4f, nodes[0].rho, 0f)
    }

    @Test
    fun `节点-温启初值优先缓存平衡位`() {
        val specs = listOf(spec(0), spec(1))
        val spX = floatArrayOf(111f, 222f)
        val spY = floatArrayOf(333f, 444f)
        val spZ = floatArrayOf(555f, 666f)
        val cold = S.buildNodes(specs, spX, spY, spZ, 0L, 0L, 0L)
        assertEquals(111f, cold[0].x, 0f)
        assertEquals(222f, cold[1].x, 0f)
        val warm = HashMap<Long, FloatArray>()
        warm[1000L] = floatArrayOf(7f, 8f, 9f) // 只有首星有缓存位
        val warmNodes = S.buildNodes(specs, spX, spY, spZ, 0L, 0L, 0L, warm)
        assertEquals(7f, warmNodes[0].x, 0f)
        assertEquals(8f, warmNodes[0].y, 0f)
        assertEquals(9f, warmNodes[0].z, 0f)
        // 无缓存位的星回落 V3 初值
        assertEquals(222f, warmNodes[1].x, 0f)
    }

    // ==================== 链对构建（互链真源∪top-8 Jaccard∪双星束缚态） ====================

    @Test
    fun `链对-互链真源边直接成对且w进s`() {
        // 两星无 tag 指纹、无 MDS 距离信号，只有互链边 w=8（p95 归一后 link 项=0.5）
        val nodes = listOf(
            TasteEmergentLayout.Node(10L, 1f, 5f, 1f, 500f, 100f, 0f, 0f),
            TasteEmergentLayout.Node(20L, 1f, 5f, 1f, 500f, -100f, 0f, 0f),
        )
        val indexOf = mapOf(10L to 0, 20L to 1)
        val interlink = mapOf(10L to mapOf(20L to 8))
        val links = S.buildLinks(indexOf, nodes, interlink, emptyMap(), { _, _ -> 1f }, emptyList())
        assertEquals(1, links.size)
        val l = links[0]
        assertEquals(0, l.a)
        assertEquals(1, l.b)
        assertTrue("互链对 s 应 ≥ 阈值（w=8 归一 0.5）", l.s >= F.S_PAIR_MIN)
        assertEquals(F.idealDist(5f, 5f, l.s), l.L, 1e-3f)
        assertFalse(l.strong)
    }

    @Test
    fun `链对-top8-Jaccard邻成对且池外频道静默丢弃`() {
        // A={p,q,r,s} B={q,r,s,u}：共享 3（j=0.6）；C={p,t} 与 A 共享 p（j=0.2）、
        // 与 B 零共享——B-C 无任何证据不应成对；池外频道 99 有全同指纹不得进对
        val nodes = listOf(
            TasteEmergentLayout.Node(1L, 1f, 5f, 1f, 500f, 100f, 0f, 0f),
            TasteEmergentLayout.Node(2L, 1f, 5f, 1f, 500f, -100f, 0f, 0f),
            TasteEmergentLayout.Node(3L, 1f, 5f, 1f, 500f, 0f, 100f, 0f),
        )
        val indexOf = mapOf(1L to 0, 2L to 1, 3L to 2)
        val fp = mapOf(
            1L to mapOf("p" to 1, "q" to 1, "r" to 1, "s" to 1),
            2L to mapOf("q" to 1, "r" to 1, "s" to 1, "u" to 1),
            3L to mapOf("p" to 1, "t" to 1),
            // 池外频道（有指纹但不在节点池）——不得成对
            99L to mapOf("p" to 1, "q" to 1, "r" to 1, "s" to 1),
        )
        val links = S.buildLinks(indexOf, nodes, emptyMap(), fp, { _, _ -> 0.9f }, emptyList())
        val pairs = links.map { minOf(it.a, it.b) to maxOf(it.a, it.b) }.toHashSet()
        assertTrue("A-B 高 Jaccard 应成对", (0 to 1) in pairs)
        // A-C：j=1/5=0.2 → s=0.3·(0.2/0.3)+0.2·(1−0.9/0.9)=0.2 ≥0.08 → 在
        assertTrue("A-C 弱共享应成对", (0 to 2) in pairs)
        // B-C 零共享 tag 且无互链 → 不成对
        assertFalse("B-C 零证据不应成对", (1 to 2) in pairs)
        // 池外频道绝不成对
        for (l in links) {
            assertTrue(l.a < 3 && l.b < 3)
        }
        // 确定性：同输入重跑逐位一致
        val links2 = S.buildLinks(indexOf, nodes, emptyMap(), fp, { _, _ -> 0.9f }, emptyList())
        assertEquals(linkSig(links), linkSig(links2))
    }

    @Test
    fun `链对-双星束缚态无证据也成对L走强核口径`() {
        val nodes = listOf(
            TasteEmergentLayout.Node(10L, 1f, 6f, 1f, 500f, 100f, 0f, 0f),
            TasteEmergentLayout.Node(20L, 1f, 4f, 1f, 500f, -100f, 0f, 0f),
        )
        val indexOf = mapOf(10L to 0, 20L to 1)
        // 无互链边、无指纹、mds=满距——s≈0 但双星对必须保留（束缚态不蒸发）
        val links = S.buildLinks(
            indexOf, nodes, emptyMap(), emptyMap(),
            { _, _ -> 1f }, listOf(10L to 20L),
        )
        assertEquals(1, links.size)
        val l = links[0]
        assertTrue(l.strong)
        // L=1.5·(ρa+ρb)（§3.3）
        assertEquals(1.5f * (6f + 4f), l.L, 1e-4f)
    }

    @Test
    fun `链对-留重帽与确定性序`() {
        // 40 星全连通互链 → 候选 780 对；断言排序口径 s 降序 + 重跑逐位一致
        val n = 40
        val nodes = (0 until n).map {
            TasteEmergentLayout.Node(it.toLong() * 2 + 1, 1f, 5f, 1f, 500f, 100f, 0f, 0f)
        }
        val indexOf = HashMap<Long, Int>(n * 2)
        nodes.forEachIndexed { i, nd -> indexOf[nd.chatId] = i }
        val interlink = HashMap<Long, HashMap<Long, Int>>()
        for (i in 0 until n) for (j in i + 1 until n) {
            interlink.getOrPut(nodes[i].chatId) { HashMap() }[nodes[j].chatId] = (i + j) % 17 + 1
        }
        val links = S.buildLinks(indexOf, nodes, interlink, emptyMap(), { a, b -> abs(a - b).toFloat() / n }, emptyList())
        assertEquals(n * (n - 1) / 2, links.size)
        for (k in 1 until links.size) {
            assertTrue("s 降序破平确定性：${links[k - 1].s} ≥ ${links[k].s}", links[k - 1].s >= links[k].s)
        }
        val again = S.buildLinks(indexOf, nodes, interlink, emptyMap(), { a, b -> abs(a - b).toFloat() / n }, emptyList())
        assertEquals(linkSig(links), linkSig(again))
    }

    // ==================== 裁决（回退链机检件） ====================

    /** Link 无 equals（引擎件不为本测试加负担）——确定性比较走字段元组 */
    private fun linkSig(ls: List<TasteEmergentLayout.Link>) =
        ls.map { "${it.a},${it.b},${it.s},${it.L},${it.strong}" }

    /** 健康合成位：10 星环半径 3000（θ 均匀展开——非退化样板） */
    private fun healthyRing(n: Int = 10, radius: Float = 3000f): FloatArray {
        val pos = FloatArray(3 * n)
        for (i in 0 until n) {
            pos[3 * i] = radius * cos(i * 6.2831853f / n)
            pos[3 * i + 1] = (i - n / 2) * 300f
            pos[3 * i + 2] = radius * sin(i * 6.2831853f / n)
        }
        return pos
    }

    @Test
    fun `裁决-四拒收码与诚实mode`() {
        val ring = healthyRing()
        // 违约>0 → 拒
        val v1 = S.verdictOf(10, ring, 3, 3000f, 200)
        assertFalse(v1.accept)
        assertTrue(v1.reason.startsWith("硬核违约"))
        // R90 塌缩 → 拒
        val v2 = S.verdictOf(10, ring, 0, 300f, 200)
        assertFalse(v2.accept)
        assertTrue(v2.reason.startsWith("云幅塌缩"))
        // R90 散架 → 拒
        val v3 = S.verdictOf(10, ring, 0, 20000f, 200)
        assertFalse(v3.accept)
        assertTrue(v3.reason.startsWith("云幅散架"))
        // 坐标非有限 → 拒（NaN 防御）
        val badPos = FloatArray(3) { Float.NaN }
        val v4 = S.verdictOf(1, badPos, 0, 3000f, 200)
        assertFalse(v4.accept)
        assertTrue(v4.reason.startsWith("坐标非有限值"))
        // 健康布局+链对足 → EMERGED 收
        val v5 = S.verdictOf(10, ring, 0, 3000f, 200)
        assertTrue(v5.reason, v5.accept)
        assertEquals(TasteEmergentSource.Mode.EMERGED, v5.mode)
        // 链对稀疏 → SEED 诚实档（不冒充涌现，但布局照收——星图可用）
        val v6 = S.verdictOf(10, ring, 0, 3000f, 40)
        assertTrue(v6.accept)
        assertEquals(TasteEmergentSource.Mode.SEED, v6.mode)
    }

    @Test
    fun `裁决-全同点退化拒收`() {
        // 10 星全在同一点（x=z=0）→ θ 桶/坐标格全退化 → 拒（R267 竖线塌方同族防线）
        val collapsed = FloatArray(30)
        val v = S.verdictOf(10, collapsed, 0, 0f, 200)
        assertFalse(v.accept)
        assertTrue(v.reason.startsWith("布局退化") || v.reason.startsWith("云幅塌缩"))
    }

    // ==================== 温启（§6.5 条款三 + §6.9 搬家帽） ====================

    @Test
    fun `温启参数-80轮不重施晕漂零浸泡`() {
        val base = TasteEmergentForces.Params(kHome = 0.1f, soakSteps = 10000)
        val warm = S.warmParams(base)
        assertEquals(S.WARM_ROUNDS, warm.roundsMax)
        assertFalse(warm.applyHaloDrift)
        assertEquals(0, warm.soakSteps)
        // 其余参数原样继承
        assertEquals(base.kHome, warm.kHome, 0f)
        assertEquals(base.kR, warm.kR, 0f)
        assertEquals(base.gLay, warm.gLay, 0f)
    }

    @Test
    fun `温启-搬家帽钳住大变星且终态过裁决`() {
        // 24 星环（缓存平衡位，半径 4200≈家弹簧中远带）→ 一颗星数据大变（陌生→
        // 常看：rPref 从 4200 跳近带）→ 温启弛豫想把它拉走近 3000——§6.9 帽把
        // 终位钳在 900 内；终态违约投影清零+R90 有界 → 过裁决
        val n = 24
        val radius = 4200f
        val specs = ArrayList<TasteEmergentSource.StarSpec>(n)
        for (i in 0 until n) {
            // i=0 大变星：常看+订阅；其余陌生
            specs += TasteEmergentSource.StarSpec(
                chatId = 1000L + i, itemsCount = 100 + i, rho = 5f,
                lastActivitySec = 1_700_000_000L, volume01 = 0.25f, // rPref≈4200
                watchSec = if (i == 0) 36000L else 0L,
                lastWatchMs = if (i == 0) 1_699_900_000_000L else 0L,
                subscribed = i == 0,
            )
        }
        val spX = FloatArray(n)
        val spY = FloatArray(n)
        val spZ = FloatArray(n)
        val warm = HashMap<Long, FloatArray>(n * 2)
        for (i in 0 until n) {
            val a = i * 6.2831853f / n
            spX[i] = radius * cos(a)
            spY[i] = (i % 5 - 2) * 500f
            spZ[i] = radius * sin(a)
            warm[specs[i].chatId] = floatArrayOf(spX[i], spY[i], spZ[i])
        }
        val nodes = S.buildNodes(
            specs, spX, spY, spZ,
            nowMs = 1_700_010_000_000L, maxWatchSec = 36000L, nowSec = 1_700_010_000L,
            warm = warm,
        )
        // 温启 80 轮（无链对——纯家弹簧/基底/斥力弛豫）
        val r = TasteEmergentLayout.shape(nodes, emptyList(), S.warmParams(TasteEmergentForces.Params(kR = 2.5e4f, gLay = 7.6e4f, kHome = 0.1f, lSpan = 400f)))
        val fin = S.warmFinal(r, nodes, warm)
        var maxMove = 0f
        for (i in 0 until n) {
            val w = warm.getValue(specs[i].chatId)
            val dx = fin.positions[3 * i] - w[0]
            val dy = fin.positions[3 * i + 1] - w[1]
            val dz = fin.positions[3 * i + 2] - w[2]
            maxMove = maxOf(maxMove, sqrt(dx * dx + dy * dy + dz * dz))
        }
        assertTrue(
            "§6.9 单次搬家帽：最大位移 $maxMove 应 ≤ ${S.WARM_MOVE_CAP}（含投影兜底余量）",
            maxMove <= S.WARM_MOVE_CAP + 60f,
        )
        val v = S.verdictOf(n, fin.positions, fin.violations, fin.r90, 0)
        assertTrue("温启终态应过裁决：${v.reason}（R90=${fin.r90}）", v.accept)
        assertEquals(TasteEmergentSource.Mode.SEED, v.mode) // 零链对=诚实 seed 档
    }

    // ==================== 自行钩子（R315-J 挂 P3） ====================

    @Test
    fun `自行-位移帽与方向归一与零向量不动`() {
        val warm = HashMap<Long, FloatArray>()
        warm[1L] = floatArrayOf(0f, 0f, 0f)
        warm[2L] = floatArrayOf(100f, 0f, 0f)
        // 强度 1、方向未归一（长度 5）——应按单位向量位移帽整
        S.applyProperMotion(
            warm,
            listOf(
                TasteEmergentSource.ProperMotion(1L, 5f, 0f, 0f, 1f),
                TasteEmergentSource.ProperMotion(2L, 0f, 0f, 0f, 1f), // 零向量=不挪
            ),
        )
        val m = sqrt(warm.getValue(1L)[0] * warm.getValue(1L)[0])
        assertEquals(S.PROPER_MOTION_MOVE_CAP, m, 1e-3f)
        assertEquals(0f, warm.getValue(1L)[1], 0f)
        assertEquals(0f, warm.getValue(1L)[2], 0f)
        // 零向量星不动
        assertEquals(100f, warm.getValue(2L)[0], 0f)
        // 强度 0=不动；帽线性：0.5 强度=半帽
        val warm2 = HashMap<Long, FloatArray>()
        warm2[1L] = floatArrayOf(0f, 0f, 0f)
        S.applyProperMotion(warm2, listOf(TasteEmergentSource.ProperMotion(1L, 0f, 1f, 0f, 0.5f)))
        assertEquals(S.PROPER_MOTION_MOVE_CAP * 0.5f, warm2.getValue(1L)[1], 1e-3f)
    }

    @Test
    fun `自行-不在表频道零影响`() {
        val warm = HashMap<Long, FloatArray>()
        warm[1L] = floatArrayOf(10f, 10f, 10f)
        S.applyProperMotion(warm, listOf(TasteEmergentSource.ProperMotion(99L, 1f, 0f, 0f, 1f)))
        assertEquals(10f, warm.getValue(1L)[0], 0f)
    }
}
