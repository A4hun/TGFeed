package dev.tgfeed.core.tastemap

import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * R303c P1——**涌现布局引擎的生产输入构建器**（纯函数，零 Android 依赖，JVM 单测
 * 锚定）。P0 已交付力场核与定形引擎（TasteEmergentForces/TasteEmergentLayout），
 * 本件把「装配期现役数据」翻译成引擎口径的节点/链对——**接互链真源**（P0 快照
 * 彩排缺源降档的挂账②③在此闭环）：
 *
 *  - **节点**（[StarSpec]→[TasteEmergentLayout.Node]）：质量=ln1p(条数)归一
 *    （TasteGalaxyPhysics 口径）；ρ=bodyRadius 现役体量口径；λ=断更衰变
 *    （金矿 lastActivitySec；缺据=1 不冤枉活星——诚实缺省）；rPref=[rPrefOf]
 *    家弹簧（§3.8 观察者径向偏好——watchSec/lastWatchMs/订阅态合成）；
 *  - **链对**（[buildLinks]）：候选=互链真源边（DiscoveryGraph 三类边无向合并权）
 *    ∪ 每星 top-8 Jaccard 邻（tags 账本指纹倒排累加——与 P0 彩排同算法）∪
 *    双星对（R283 宣判对——束缚态不蒸发）；s=[TasteEmergentForces.strengthOf]
 *    单口合成（互链 P95+Jaccard+MDS 距离三源，全仓唯一出处）；L=
 *    [TasteEmergentForces.idealDist]（strong 对=1.5·(ρa+ρb)——§3.3 强核力在
 *    全图力场的唯一现身）；
 *  - **裁决**（[verdictOf]）：违约=0 ∧ R90 有界 ∧ 非退化 ∧ 坐标有限 → 收下
 *    平衡位；否则回退 V3+原因码（§3.6 失控兜底「运行时定形不过闸=回退 V3」
 *    的机检件）；
 *  - **温启**（§6.5 条款三/§6.6 增量演化）：[warmParams]（80 轮+不重施晕漂）+
 *    [buildNodes] 的 warm 初值（缓存平衡位——「认熟的星座不搬」）+
 *    [warmFinal]（§6.9 单次搬家帽 15% 银河半径+违约投影兜底——结构层慢变条款
 *    的机械化）；首跑（缓存空/代次不符）=冷启 800 轮从 V3 松弛（无帽——换代
 *    一次性重定形由缓存代次宣告）；
 *  - **自行钩子**（R315-J 段挂 P3）：[ProperMotion]+[applyProperMotion]——两窗
 *    构成差矢量经温启初值偏置进布局场（帽同 §6.9），温启弛豫把它消化成新
 *    平衡位。数据源（S3 直方图两窗构成差）归 R317+ 接线，本件只留参数位与帽。
 *
 * 全确定性：候选键=TreeSet(chatId 升序)、留重序=s 降序→a→b 破平、倒排累加
 * 计数序无关——同数据同宇宙（R 系铁律）。零随机零系统钟（时间参数调用方传）。
 */
object TasteEmergentSource {

    // ==================== 常量（§3.8 家弹簧带 / §6.9 搬家帽 / §9 诚实档） ====================

    /** 家弹簧近带下沿（世界单位，§3.8：常看/订阅→近带 400~1200） */
    const val HOME_NEAR = 400f

    /** 家弹簧近带上沿 */
    const val HOME_NEAR_MAX = 1200f

    /** 家弹簧中远带下沿（陌生频道——§3.8「陌生→中远带」） */
    const val HOME_MID_MIN = 2400f

    /** 家弹簧中远带上沿（体量小=更远，近大远小沿用 V3 深度线索语义） */
    const val HOME_MID_MAX = 4800f

    /** 家弹簧新鲜度半衰（天——与特征向量 FRESH_DAYS=60 同族口径） */
    const val HOME_FRESH_DAYS = 60f

    /** 订阅态直接拉档（pull01 加成——订阅是用户亲手写的兴趣声明，R298 先验语义） */
    const val HOME_SUBSCRIBE_BOOST = 0.25f

    /** 温启单星单次搬家帽（世界单位=15%·R_GALAXY，§6.9「防认熟的星座一夜搬家」） */
    const val WARM_MOVE_CAP = 0.15f * TasteEmergentForces.R_GALAXY

    /** 自行钩子位移帽（与温启搬家帽同源同值——R315-J「布局层时间箭头」上限） */
    const val PROPER_MOTION_MOVE_CAP = WARM_MOVE_CAP

    /** 温启轮数上限（§6.5 条款三：局部弛豫 ≤80 轮） */
    const val WARM_ROUNDS = 80

    /** 模式判据：链对 ≥ 本值才算「涌现」档（§9 诚实口径 mode=emerged；风险表
     * 「互链边太稀（引力对 <100）→ mode=seed」） */
    const val SEED_LINKS_MIN = 100

    /** 裁决 R90 下限（世界单位——低于=云塌缩征兆，拒收） */
    const val VERDICT_R90_MIN = 600f

    /** 裁决 R90 上限（世界单位=2.2×R_GALAXY——高于=云散架征兆，拒收） */
    const val VERDICT_R90_MAX = 2.2f * TasteEmergentForces.R_GALAXY

    /**
     * **生产标定组**（P0 快照彩排 18 组网格选定值：lSpan=400/kR=2.5e4/gLay=7.6e4/
     * kHome=0.1——「参数永不手调上线」§3.6：终值由标定回路定稿入库，单测锚
     * safetyReport 合法域；后续再标定=改这里一处+彩排复跑，不许散抄）。
     */
    val PRODUCTION_PARAMS: TasteEmergentForces.Params = TasteEmergentForces.Params(
        kR = 2.5e4f, gLay = 7.6e4f, lSpan = 400f, kHome = 0.1f,
    )

    // ==================== 节点（装配字段→引擎口径） ====================

    /**
     * 生产节点原料（装配期现役字段的直传壳——layoutTasteMap 侧组装）。
     *
     * @property itemsCount 体量条数（max(idxCount, goldItems) 口径）
     * @property rho 体量半径（bodyRadius 现役口径 2.2~26）
     * @property lastActivitySec 最近活动 epoch 秒（金矿档案；0=缺据）
     * @property volume01 体量分位（V3 r 口径同源——家弹簧中远带用）
     */
    class StarSpec(
        val chatId: Long,
        val itemsCount: Int,
        val rho: Float,
        val lastActivitySec: Long,
        val volume01: Float,
        val watchSec: Long,
        val lastWatchMs: Long,
        val subscribed: Boolean,
    )

    /**
     * 家弹簧径向偏好 rPref（§3.8 观察者径向偏好——角向交给四力涌现、径向保留
     * 「最爱频道永远在近天区」的偏心项；正交分解=不破坏三指标）：
     *
     *  - 常看/订阅（watchSec>0 ∨ subscribed）→ 近带 [HOME_NEAR, HOME_NEAR_MAX]，
     *    位置由投入度合成：engage01=ln1p(watch)/ln1p(maxWatch)（0.6 权）+
     *    fresh01=exp(−断更观看天/60)（0.4 权）+订阅加成 0.25——越常看越近；
     *  - 陌生（无观看无订阅）→ 中远带 [HOME_MID_MIN, HOME_MID_MAX] 按体量分位
     *    （大频道近小频道远——近大远小的深度线索沿用 V3 语义）。
     *
     * @param nowMs 当前时刻（ms；lastWatchMs=0=从未看过）
     * @param maxWatchSec 全池最大累计观看秒（0=无观看账=诚实只用订阅/新鲜度缺省档）
     */
    fun rPrefOf(
        watchSec: Long,
        lastWatchMs: Long,
        subscribed: Boolean,
        volume01: Float,
        nowMs: Long,
        maxWatchSec: Long,
    ): Float {
        val familiar = subscribed || watchSec > 0L
        if (!familiar) {
            // 陌生：中远带按体量（volume01 大=近）
            val v = volume01.coerceIn(0f, 1f)
            return HOME_MID_MIN + (HOME_MID_MAX - HOME_MID_MIN) * (1f - v)
        }
        val engage01 = if (maxWatchSec > 0L && watchSec > 0L) {
            (ln1p(watchSec.toFloat()) / ln1p(maxWatchSec.toFloat())).coerceIn(0f, 1f)
        } else 0f
        val fresh01 = if (lastWatchMs > 0L) {
            val days = ((nowMs - lastWatchMs).coerceAtLeast(0L)).toFloat() / 86_400_000f
            exp(-days / HOME_FRESH_DAYS)
        } else {
            if (subscribed) 0.30f else 0.10f // 从未看过但已订阅=中等新鲜度缺省
        }
        var pull01 = 0.6f * engage01 + 0.4f * fresh01
        if (subscribed) pull01 = (pull01 + HOME_SUBSCRIBE_BOOST).coerceAtMost(1f)
        return HOME_NEAR + (HOME_NEAR_MAX - HOME_NEAR) * (1f - pull01.coerceIn(0f, 1f))
    }

    /**
     * 生产节点构建（初值=V3 球面位或温启缓存位）。
     *
     * @param spX/spY/spZ V3 球面坐标（冷启初值——「初值=现役位置」，§6.1）
     * @param warm 温启平衡位（chatId→xyz 交错 3 数组；命中=增量演化初值——§6.6
     *   「认熟的星座不搬」；null=冷启）
     */
    fun buildNodes(
        specs: List<StarSpec>,
        spX: FloatArray,
        spY: FloatArray,
        spZ: FloatArray,
        nowMs: Long,
        maxWatchSec: Long,
        nowSec: Long,
        warm: Map<Long, FloatArray>? = null,
    ): List<TasteEmergentLayout.Node> {
        var maxItems = 0
        for (s in specs) if (s.itemsCount > maxItems) maxItems = s.itemsCount
        val lnMax = ln1p(maxItems.toFloat())
        val out = ArrayList<TasteEmergentLayout.Node>(specs.size)
        for (i in specs.indices) {
            val s = specs[i]
            val mass01 = if (lnMax > 0f) (ln1p(s.itemsCount.toFloat()) / lnMax).coerceIn(0f, 1f) else 0.5f
            // λ：断更衰变（缺据=1——没有证据不断更就不冤枉活星，诚实缺省）
            val staleDays = if (s.lastActivitySec > 0L) {
                ((nowSec - s.lastActivitySec).coerceAtLeast(0L)).toFloat() / 86400f
            } else 0f
            val lambda = TasteEmergentForces.decayLambda(staleDays)
            val rPref = rPrefOf(s.watchSec, s.lastWatchMs, s.subscribed, s.volume01, nowMs, maxWatchSec)
            var x = spX[i]
            var y = spY[i]
            var z = spZ[i]
            val w = warm?.get(s.chatId)
            if (w != null && w.size >= 3) {
                x = w[0]; y = w[1]; z = w[2]
            }
            out += TasteEmergentLayout.Node(s.chatId, mass01, s.rho, lambda, rPref, x, y, z)
        }
        return out
    }

    // ==================== 链对（互链真源 ∪ top-8 Jaccard 邻 ∪ 双星束缚态） ====================

    /**
     * 候选引力对构建（§3.1「互链 800 边+每星 top-8 Jaccard 邻——引力是稀疏的，
     * 这本身就是语义」）：
     *
     *  1. 互链真源边全集（[interlink] 无向合并权>0 的对——DiscoveryGraph 三类边
     *     现役产出直供）；
     *  2. tags 账本指纹倒排累加共享数 → 每星 Jaccard top-8 邻（并集）；
     *  3. 双星对全集（R283 BinaryStarPolicy 宣判对——束缚态不蒸发：无共享证据
     *     也成对，L 走强核口径）；
     *  4. 每候选对：s=[TasteEmergentForces.strengthOf]（wP95/mdsP95 归一锚在本批
     *     候选内结算——无互链源=该项计 0 的诚实降档）；
     *  5. 留重：s≥[TasteEmergentForces.S_PAIR_MIN] ∨ strong；s 降序→a→b 破平取
     *     [TasteEmergentForces.MAX_PAIRS]（确定性序）。
     *
     * @param indexOf chatId→节点下标（不在池的对静默丢弃——调用方池口径）
     * @param interlink 互链真源（a→{b→w} 无向对称登记，w=合并计数）
     * @param fingerprints tags 账本指纹（chatId→fold 键→计数；空=Jaccard 路降档）
     * @param distOf MDS 距离预言机（特征余弦矩阵口径——MDS 降级=距离预言机的
     *   字面落法，拍板项③；签名 (ia,ib)→距离）
     * @param strongPairs 双星对（R283 宣判对——strong 束缚态标记）
     */
    fun buildLinks(
        indexOf: Map<Long, Int>,
        nodes: List<TasteEmergentLayout.Node>,
        interlink: Map<Long, Map<Long, Int>>,
        fingerprints: Map<Long, Map<String, Int>>,
        distOf: (Int, Int) -> Float,
        strongPairs: Collection<Pair<Long, Long>>,
    ): List<TasteEmergentLayout.Link> {
        val n = nodes.size
        if (n == 0) return emptyList()
        val rho = FloatArray(n) { nodes[it].rho }

        // ---- 1) 候选键收集（TreeSet=chatId 升序确定性） ----
        val keys = java.util.TreeSet<Pair<Long, Long>>(compareBy({ it.first }, { it.second }))
        for ((a, bs) in interlink) {
            for (b in bs.keys) {
                if (a == b) continue
                keys += (minOf(a, b) to maxOf(a, b))
            }
        }
        for (p in strongPairs) {
            keys += (minOf(p.first, p.second) to maxOf(p.first, p.second))
        }
        // ---- 2) Jaccard 倒排累加（共享 tag 的对）+ 每星 top-8 ----
        val inPool = HashSet<Long>(n * 2)
        for (i in 0 until n) inPool += nodes[i].chatId
        val inter = HashMap<Pair<Long, Long>, Int>(1024)
        val fpSize = HashMap<Long, Int>(fingerprints.size * 2)
        val inverted = HashMap<String, ArrayList<Long>>(256)
        for ((cid, fp) in fingerprints) {
            if (cid !in inPool) continue
            fpSize[cid] = fp.size
            for (tag in fp.keys) inverted.getOrPut(tag) { ArrayList(4) } += cid
        }
        for (lst in inverted.values) {
            if (lst.size < 2) continue
            lst.sort() // chatId 升序（确定性）
            for (i in lst.indices) {
                val ci = lst[i]
                for (j in i + 1 until lst.size) {
                    inter.merge(ci to lst[j], 1, Int::plus)
                }
            }
        }
        // 每星 top-8 Jaccard 邻（j 降序→对端 chatId 升序破平——确定性）
        val byStar = HashMap<Long, ArrayList<Pair<Long, Float>>>(inPool.size)
        for ((k, it) in inter) {
            val ua = fpSize[k.first] ?: 0
            val ub = fpSize[k.second] ?: 0
            val union = ua + ub - it
            if (union <= 0) continue
            val j = it.toFloat() / union
            byStar.getOrPut(k.first) { ArrayList(8) } += k.second to j
            byStar.getOrPut(k.second) { ArrayList(8) } += k.first to j
        }
        for ((cid, lst) in byStar) {
            lst.sortWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
            for (m in 0 until minOf(TasteEmergentForces.TOPK_NEIGHBOR, lst.size)) {
                val other = lst[m].first
                keys += (minOf(cid, other) to maxOf(cid, other))
            }
        }

        // ---- 3) s 合成（归一锚=候选批内 P95；互链项 w=合并权） ----
        class Cand(val ia: Int, val ib: Int, val a: Long, val b: Long, val w: Int, val jac: Float, val mds: Float, val strong: Boolean)
        val strongSet = HashSet<Pair<Long, Long>>(strongPairs.size * 2)
        for (p in strongPairs) strongSet += (minOf(p.first, p.second) to maxOf(p.first, p.second))
        val cands = ArrayList<Cand>(keys.size)
        val ws = ArrayList<Int>(keys.size)
        val mdsVals = ArrayList<Float>(keys.size)
        for (k in keys) {
            val ia = indexOf[k.first] ?: continue
            val ib = indexOf[k.second] ?: continue
            if (ia == ib) continue
            val w = interlink[k.first]?.get(k.second) ?: 0
            val it = inter[k]
            val jac = if (it != null) {
                val ua = fpSize[k.first] ?: 0
                val ub = fpSize[k.second] ?: 0
                val union = ua + ub - it
                if (union > 0) it.toFloat() / union else 0f
            } else 0f
            val mds = distOf(ia, ib)
            cands += Cand(ia, ib, k.first, k.second, w, jac, mds, k in strongSet)
            if (w > 0) ws += w
            if (mds > 0f) mdsVals += mds
        }
        val wP95 = p95Of(ws)
        val mdsP95 = p95OfFloats(mdsVals)

        // ---- 4) 留重（s 降序→a→b；strong 不蒸发；MAX_PAIRS 帽） ----
        val F = TasteEmergentForces
        class Sc(val link: TasteEmergentLayout.Link, val s: Float, val a: Long, val b: Long)
        val buf = ArrayList<Sc>(cands.size)
        for (c in cands) {
            val s = F.strengthOf(c.w.toFloat(), wP95, c.jac, c.mds, mdsP95)
            if (s < F.S_PAIR_MIN && !c.strong) continue
            val l = if (c.strong) {
                // 束缚态：L=1.5·(ρa+ρb)（§3.3 强核力唯一现身——k_S=10·k_G 在引擎侧）
                1.5f * (rho[c.ia] + rho[c.ib])
            } else {
                F.idealDist(rho[c.ia], rho[c.ib], s)
            }
            buf += Sc(TasteEmergentLayout.Link(c.ia, c.ib, s, l, c.strong), s, c.a, c.b)
        }
        buf.sortWith(compareByDescending<Sc> { it.s }.thenBy { it.a }.thenBy { it.b })
        val out = ArrayList<TasteEmergentLayout.Link>(minOf(buf.size, F.MAX_PAIRS))
        for (sc in buf) {
            if (out.size >= F.MAX_PAIRS) break
            out += sc.link
        }
        return out
    }

    /** 权重 P95（空集/全零=0——归一项计 0 的诚实降档锚） */
    private fun p95Of(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) * 95 / 100].toFloat()
    }

    private fun p95OfFloats(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) * 95 / 100]
    }

    // ==================== 裁决（回退链机检件——§3.6 失控兜底） ====================

    /** 布局模式（§9 诚实口径：mode=emerged/seed——证据不足期不冒充涌现） */
    enum class Mode { EMERGED, SEED }

    /**
     * 裁决产物：accept=false → 调用方保留 V3 位（回退链）；reason 进探针行
     * （脱敏——只原因码+计数，零内容）。
     */
    class Verdict(val accept: Boolean, val mode: Mode, val reason: String)

    /**
     * 平衡位裁决（运行时定形不过闸=回退 V3 的机械化判定）：
     *  ①坐标全有限（NaN/Inf 防御——引擎异常产物绝不进渲染）；
     *  ②违约=0（重合根治硬条款——投影兜底后应恒 0）；
     *  ③R90 ∈ [VERDICT_R90_MIN, VERDICT_R90_MAX]（既不塌缩也不散架）；
     *  ④非退化（LayoutHealth θ 桶/坐标格双口径——布局场退化有旧闸接手）。
     * mode：链对 ≥[SEED_LINKS_MIN]=EMERGED，否则 SEED（诚实档——星图照收，
     * 探针行标注，不冒充）。
     */
    fun verdictOf(n: Int, positions: FloatArray, violations: Int, r90: Float, linkCount: Int): Verdict {
        val mode = if (linkCount >= SEED_LINKS_MIN) Mode.EMERGED else Mode.SEED
        for (v in positions) {
            if (!v.isFinite()) return Verdict(false, mode, "坐标非有限值")
        }
        if (violations != 0) return Verdict(false, mode, "硬核违约=$violations")
        if (r90 < VERDICT_R90_MIN) return Verdict(false, mode, "云幅塌缩R90=${r90.toInt()}")
        if (r90 > VERDICT_R90_MAX) return Verdict(false, mode, "云幅散架R90=${r90.toInt()}")
        if (n > 0) {
            val xs = FloatArray(n) { positions[3 * it] }
            val zs = FloatArray(n) { positions[3 * it + 2] }
            val thetas = FloatArray(n) { kotlin.math.atan2(zs[it], xs[it]) }
            val thetaUnique = LayoutHealth.thetaUniqueCount(thetas, n)
            val coordUnique = LayoutHealth.coordUniqueCount(xs, zs, n)
            if (LayoutHealth.layoutDegenerate(thetaUnique, coordUnique)) {
                return Verdict(false, mode, "布局退化θ桶=$thetaUnique 格=$coordUnique")
            }
        }
        return Verdict(true, mode, "过闸")
    }

    /** 裁决便捷口（直收引擎产物——冷启路径；温启路径走 [warmFinal] 后用 [verdictOf]） */
    fun verdict(r: TasteEmergentLayout.Result, linkCount: Int): Verdict =
        verdictOf(r.n, r.positions, r.violationsFinal, r.r90, linkCount)

    // ==================== 温启（§6.5 条款三增量演化——认熟的星座不搬） ====================

    /**
     * 温启参数（缓存平衡位在手的弛豫档）：80 轮上限+不重施晕漂开局位移
     * （位置已携带墓地轨道态——重施会把平衡位再向晕带拉一段=非增量语义）。
     * 其余参数与冷启同（调用方传基准参数，本口只覆写温启三件）。
     */
    fun warmParams(base: TasteEmergentForces.Params): TasteEmergentForces.Params =
        TasteEmergentForces.Params(
            kG = base.kG, kR = base.kR, kHard = base.kHard, kS = base.kS,
            gLay = base.gLay, lSpan = base.lSpan, kHome = base.kHome, kDE = base.kDE,
            damping = base.damping,
            roundsMax = WARM_ROUNDS,
            applyHaloDrift = false,
            soakSteps = 0, // 温启不浸泡（万步浸泡=验收/判废口径，非生产路径）
        )

    /**
     * 温启终态（§6.9 结构层慢变条款机械化）：
     *  ①**单星单次搬家帽**——终位到温启初值（缓存平衡位）的位移钳 [WARM_MOVE_CAP]
     *    （15% 银河半径；「认熟的星座一夜搬家」防线的位置级投影）；
     *  ②帽后违约复扫+投影兜底（钳回可能造成的近距重合——硬条款「违约=0」
     *    管到交付物落表值，与冷启收尾同纪律）；
     *  ③终态 R90 重算（裁决原料——钳位后云幅可能微变）。
     *
     * @param warm 温启初值表（chatId→xyz——与 buildNodes 的 warm 同源；缺位星
     *   以节点初值兜底=无帽可钳时原样通过）
     */
    class WarmFinal(val positions: FloatArray, val violations: Int, val r90: Float)

    fun warmFinal(
        r: TasteEmergentLayout.Result,
        nodes: List<TasteEmergentLayout.Node>,
        warm: Map<Long, FloatArray>,
    ): WarmFinal {
        val n = r.n
        val pos = r.positions.copyOf()
        val x = FloatArray(n) { pos[3 * it] }
        val y = FloatArray(n) { pos[3 * it + 1] }
        val z = FloatArray(n) { pos[3 * it + 2] }
        val rho = FloatArray(n) { nodes[it].rho }
        // ① 搬家帽（对有温启初值的星）
        for (i in 0 until n) {
            val w = warm[nodes[i].chatId] ?: continue
            if (w.size < 3) continue
            val dx = x[i] - w[0]
            val dy = y[i] - w[1]
            val dz = z[i] - w[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > WARM_MOVE_CAP && d > 1e-6f) {
                val k = WARM_MOVE_CAP / d
                x[i] = w[0] + dx * k
                y[i] = w[1] + dy * k
                z[i] = w[2] + dz * k
            }
        }
        // ② 帽后投影兜底（违约清零——钳回造成的近距重合同款治理）
        TasteEmergentLayout.projectViolations(n, x, y, z, rho)
        val viol = TasteEmergentLayout.countViolations(n, x, y, z, rho)
        // ③ 终态 R90
        val r90 = TasteEmergentLayout.radiusQuantile(n, x, y, z, 0.9f)
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            out[3 * i] = x[i]; out[3 * i + 1] = y[i]; out[3 * i + 2] = z[i]
        }
        return WarmFinal(out, viol, r90)
    }

    // ==================== 自行钩子（R315-J 段挂 P3——两窗构成差矢量预留位） ====================

    /**
     * 自行偏置（频道口味漂移方向——「它正在离开这里」）：温启初值向漂移方向
     * 预挪一段，弛豫把它消化成新平衡位（布局层的「时间箭头·持续通道」）。
     *
     * @property dirX/dirY/dirZ 漂移方向向量（调用方由两窗构成差映射——R315-J
     *   设计：视频化=红移向/图文化=青向/音频化=紫向；S3 直方图数据源归 R317+，
     *   本件不实现方向映射）
     * @property strength01 漂移强度（0..1——两窗构成差归一）
     */
    class ProperMotion(
        val chatId: Long,
        val dirX: Float,
        val dirY: Float,
        val dirZ: Float,
        val strength01: Float,
    ) {
        init {
            require(strength01 >= 0f && strength01 <= 1f) { "漂移强度域 [0,1]" }
        }
    }

    /**
     * 自行偏置施加（温启初值侧——零引擎改动：偏置进初值，80 轮弛豫消化）：
     * 位移=strength01·[PROPER_MOTION_MOVE_CAP]（单星单次帽 15% 银河半径，§6.9）；
     * 方向向量归一（零向量=不挪）。位移是绝对量非增量（同偏置重施不叠加）——
     * 调用方每次温启重算。
     */
    fun applyProperMotion(
        warm: HashMap<Long, FloatArray>,
        motions: List<ProperMotion>,
    ) {
        if (motions.isEmpty()) return
        for (m in motions) {
            val w = warm[m.chatId] ?: continue
            if (w.size < 3) continue
            val len = sqrt(m.dirX * m.dirX + m.dirY * m.dirY + m.dirZ * m.dirZ)
            if (len < 1e-6f || m.strength01 <= 0f) continue
            val d = m.strength01.coerceIn(0f, 1f) * PROPER_MOTION_MOVE_CAP / len
            w[0] = w[0] + m.dirX * d
            w[1] = w[1] + m.dirY * d
            w[2] = w[2] + m.dirZ * d
        }
    }
}
