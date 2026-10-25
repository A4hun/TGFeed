package dev.tgfeed.core.tastemap

import kotlin.math.max
import kotlin.math.sqrt

/**
 * R235 未探索星云纯函数（口味星图四期·RPG 层·零 Android 依赖单测可跑）。
 *
 * **「你没看过的内容区域渲染为暗淡星云（半透明微光雾状）」**（任务书原文）：
 * 深索引里从未观看且未订阅的在图频道恒星（watchSec==0 ∧ !subscribed——「你从来
 * 没看过的频道」的诚实判据）聚成雾团：
 *
 *  - **聚类**=世界 XY 网格（[GRID_DIV]² 格，[MIN_MEMBERS] 星成云）——确定性
 *    （格序行优先、成员按 chatId 排序后聚合——同数据同星云，绝不跳位）；
 *  - **星云颜色=内容类别色**（任务书口径：视频暖/图文绿/音频紫——成员基因构成
 *    多数派；无基因证据=中性暗蓝灰诚实缺省）。色相锚与 TasteMapScreen 三基相
 *    （20°/125°/265°）同值锚定——**改就一起改**（本仓镜像常量纪律同 PULSAR_COLOR
 *    先例）；雾色为低饱和暗档（雾状≠星点色，视觉对象不同所以参数不同）；
 *  - **飞近星云→渐显恒星**（任务书原文「接近=发现感」）：[discoveryBoost]——
 *    未探索星随相机接近渐亮（+alpha 补偿，远=0 不动现状）；[fogAlpha]——雾本
 *    体随接近渐散（飞进雾里雾消散、星浮现=「发现」的叙事）。
 *
 * 上限 [MAX_CLUSTERS]=6 团（帧预算件：每团 1 drawBitmap，渲染端位图缓存）；
 * 超限按成员数留重（并列按格序稳定）。
 */
object TasteNebula {

    /** 聚类网格划分数（世界 1000 ÷ 8 = 125 世界单位格） */
    const val GRID_DIV = 8

    /** 成云最小成员数（<3 星的散点不成云——诚实：孤星不是「区域」） */
    const val MIN_MEMBERS = 3

    /** 星云团数上限（帧预算：每团 1 drawBitmap） */
    const val MAX_CLUSTERS = 6

    /** 星云半径补边（世界单位：成员最大离心距 + 此值——雾罩住成员又不溢出太多） */
    private const val RADIUS_PAD = 42f

    // ---- 类别色（HSV 色相锚与 TasteMapScreen.HUE_* 同值——改就一起改） ----

    /** 视频暖（HUE_VIDEO=20° 镜像） */
    const val CATEGORY_HUE_VIDEO = 20f

    /** 图文绿（HUE_PHOTO=125° 镜像） */
    const val CATEGORY_HUE_PHOTO = 125f

    /** 音频紫（HUE_AUDIO=265° 镜像） */
    const val CATEGORY_HUE_AUDIO = 265f

    /** 无基因证据的中性暗蓝灰（诚实缺省——没有类别证据不冒充彩色星云） */
    const val CATEGORY_HUE_NONE = 215f

    /**
     * 聚类输入（一颗在图频道的未探索判定原料）：调用方从 StarNode/MapStarRaw
     * 映射（[gVideo]/[gPhoto]/[gAudio]=索引基因构成——类别色的投票原料）。
     */
    data class NebulaStarIn(
        val chatId: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val watchSec: Long,
        val subscribed: Boolean,
        val gVideo: Int,
        val gPhoto: Int,
        val gAudio: Int,
    )

    /**
     * 一团未探索星云（不可变，装配期定死——渲染期零计算除投影）：
     * [cz]=成员平均深度（雾平面）；[colorArgb]=类别雾色（低饱和暗档）；
     * [count]=成员频道数（「这片星域藏着 N 个没看过的频道」的诚实账）。
     */
    class NebulaCluster(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val radius: Float,
        val colorArgb: Int,
        val count: Int,
        /** 主导类别名（面板/日志诊断：视频/图文/音频/未知） */
        val dominant: String,
    )

    /** 类别雾色样（hue/sat/bri + 用户语言名） */
    private class CatStyle(val hue: Float, val sat: Float, val bri: Float, val name: String)

    /**
     * 未探索星云聚类（纯函数，确定性）：
     *  1. 过滤未探索星（watchSec==0 ∧ !subscribed）；
     *  2. 按 XY 落格（行优先序遍历）；每格成员按 chatId 升序聚合（浮点求和顺序
     *     稳定=同数据同质心）；
     *  3. ≥[MIN_MEMBERS] 成云：质心+半径（最大成员离心距+[RADIUS_PAD]）+类别色
     *     （基因多数派投票，全零=中性缺省）；
     *  4. 超过 [MAX_CLUSTERS] 按成员数留重（并列按格序——稳定）。
     *
     * R302：聚类内脏抽 [nebulaCells]（格键→成员表，留重后）为共享单一出处——
     * 星域首踏/星域行者的团身份锚=格键（行优先序——装配间稳定），本函数输出
     * 与旧口径逐字节一致（同一份聚类结果两种投影，纪律§一）。
     */
    fun nebulaClusters(stars: List<NebulaStarIn>): List<NebulaCluster> {
        if (stars.isEmpty()) return emptyList()
        val out = ArrayList<NebulaCluster>(MAX_CLUSTERS)
        for ((_, members) in nebulaCells(stars)) {
            var sx = 0f
            var sy = 0f
            var sz = 0f
            for (m in members) {
                sx += m.x; sy += m.y; sz += m.z
            }
            val n = members.size
            val cx = sx / n
            val cy = sy / n
            val cz = sz / n
            var maxR = 0f
            for (m in members) {
                val d = sqrt((m.x - cx) * (m.x - cx) + (m.y - cy) * (m.y - cy))
                if (d > maxR) maxR = d
            }
            // 类别投票（基因构成多数派；全零=中性缺省）
            var v = 0
            var p = 0
            var a = 0
            for (m in members) {
                v += m.gVideo; p += m.gPhoto; a += m.gAudio
            }
            val cat = when {
                v + p + a <= 0 -> CatStyle(CATEGORY_HUE_NONE, 0.18f, 0.42f, "未知")
                v >= p && v >= a -> CatStyle(CATEGORY_HUE_VIDEO, 0.46f, 0.52f, "视频")
                p >= a -> CatStyle(CATEGORY_HUE_PHOTO, 0.40f, 0.48f, "图文")
                else -> CatStyle(CATEGORY_HUE_AUDIO, 0.42f, 0.54f, "音频")
            }
            out += NebulaCluster(
                cx, cy, cz,
                maxR + RADIUS_PAD,
                androidLikeHsv(floatArrayOf(cat.hue, cat.sat, cat.bri)),
                n,
                cat.name,
            )
        }
        return out
    }

    /**
     * R302 星域聚类内脏（共享单一出处）：过滤未探索星 → 按 XY 落格 → 格内成员按
     * chatId 升序 → ≥[MIN_MEMBERS] 成云 → 成员数留重（并列按格序稳定）→
     * ≤[MAX_CLUSTERS] 团。返回 (格键, 成员表) 按留重序（=渲染团序）。
     * [nebulaClusters] 与 [nebulaCellIndexOf] 同走本函数——同数据同聚类。
     */
    private fun nebulaCells(stars: List<NebulaStarIn>): List<Pair<Int, List<NebulaStarIn>>> {
        val world = 1000f
        val cell = world / GRID_DIV
        // 格 → 成员表（行优先=确定性遍历序）
        val cells = HashMap<Int, ArrayList<NebulaStarIn>>(GRID_DIV * GRID_DIV)
        for (s in stars) {
            if (s.watchSec > 0L || s.subscribed) continue
            val gx = (s.x / cell).toInt().coerceIn(0, GRID_DIV - 1)
            val gy = (s.y / cell).toInt().coerceIn(0, GRID_DIV - 1)
            cells.getOrPut(gy * GRID_DIV + gx) { ArrayList(16) }.add(s)
        }
        val cands = ArrayList<Pair<Int, List<NebulaStarIn>>>(cells.size)
        for (key in cells.keys.sorted()) { // 键排序=行优先稳定序
            val members = cells[key]!!
            if (members.size < MIN_MEMBERS) continue
            members.sortBy { it.chatId } // 聚合顺序稳定=质心浮点稳定
            cands += key to members
        }
        // 留重（成员数降序 → 格序稳定破平）
        cands.sortWith(compareByDescending<Pair<Int, List<NebulaStarIn>>> { it.second.size })
        return if (cands.size > MAX_CLUSTERS) cands.subList(0, MAX_CLUSTERS) else cands
    }

    /**
     * R302 星域成员表（首踏星域/星域行者的团身份锚）：chatId → 格键
     * （行优先格序——装配间稳定身份；只含留重后 ≤[MAX_CLUSTERS] 的可见团——
     * 看不见的雾团不算「星域」诚实口径）。与 [nebulaClusters] 同一聚类内脏。
     */
    fun nebulaCellIndexOf(stars: List<NebulaStarIn>): Map<Long, Int> {
        if (stars.isEmpty()) return emptyMap()
        val out = HashMap<Long, Int>(stars.size)
        for ((cellKey, members) in nebulaCells(stars)) {
            for (m in members) out[m.chatId] = cellKey
        }
        return out
    }

    /**
     * R323 星域成员公开出口（**与 [nebulaClusters] 同一聚类内脏、同序 1:1**——第 i
     * 个雾团=第 i 条格键与成员表；零聚类逻辑复制=纪律§一）：发射星云团级门
     * （r315 K①「成员中金矿候选占比≥发射门」）的成员消费口——调用方（批 5 的
     * [mapEmissionFogsR323]）以同输入跑 [nebulaClusters] 后按序对位取成员表，
     * 逐成员查金矿候选位（NEBULA_CANDIDATE 注记——两集合装配期已有零新请求）。
     */
    fun nebulaCellMembers(stars: List<NebulaStarIn>): List<Pair<Int, List<NebulaStarIn>>> =
        if (stars.isEmpty()) emptyList() else nebulaCells(stars)

    /** HSV→ARGB（不引 android.graphics——core 零 Android 依赖纪律的手写同式） */
    private fun androidLikeHsv(hsv: FloatArray): Int {
        val h = (hsv[0] % 360f + 360f) % 360f
        val s = hsv[1].coerceIn(0f, 1f)
        val v = hsv[2].coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        var r = 0f
        var g = 0f
        var b = 0f
        when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        val ri = ((r + m) * 255f).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    // ---- 接近渐变（「飞近星云→渐显恒星」「雾随接近渐散」的量） ----

    /** 雾完全消散距离（相机到雾心 ≤ 此值=零雾——你已飞进这片星域=已发现） */
    const val FOG_FADE_NEAR = 420f

    /** 雾满档参考距离（≥此值=满档雾——远看是暗淡星云） */
    const val FOG_FADE_FAR = 1500f

    /**
     * 雾透明度（0..[FOG_ALPHA_MAX]）：远=满档（远看暗淡星云）、近=渐散（飞近
     * 发现感——雾让位给渐显的星，任务书「飞近星云→渐显恒星」的雾半边）。
     */
    fun fogAlpha(dist3d: Float): Float {
        val t = ((dist3d - FOG_FADE_NEAR) / (FOG_FADE_FAR - FOG_FADE_NEAR)).coerceIn(0f, 1f)
        return FOG_ALPHA_MAX * t
    }

    /**
     * 雾满档透明度。量级史：
     *  - R252 0.26→0.05（用户三实锤①「发光大圆片好丑、全屏光雾」的收敛：
     *    0.26×雾心 0.75≈50 级灰的巨幅圆片压到 ≤10 级灰极微弱暗噪点档——
     *    「未探索区域」语义保住但不再当屏保）；
     *  - R358 0.05→候选带 [0.10,0.16]（用户 2026-10-23 拍板「语义雾质感革命」
     *    方案 A：「没有星云咱们的宇宙很难有那种真实的宇宙那种梦幻感」）。
     *    **形态丝缕化是 alpha 抬升的前提**（NEBULA_FS 脊线 fbm 丝缕+各向异性
     *    拉伸+暗尘埃带同批落地）：峰值 alpha 只落在纤维丝上，丝间留空看得见底
     *    星、宏观轮廓不规则——同样 alpha 在丝缕形态下不读作「大圆片」（R252
     *    病的形态解药，非回潮；带上界 0.16 ≪ 旧病 0.26）。
     *    **诚实量级自认**（宪法一章三条）：真实星云肉眼不可见（长曝光摄影才显
     *    形）——本档为「梦幻感可读性优先」的量级压缩，非真实亮度。
     *    定稿 0.13=设计裁决值（r358_notes §6 架构师 18:2x 口径：撞小米禁测窗，
     *    带内两档对比截图取证挂 [V58] 腿闭环——带上界 0.16 ≪ 旧病 0.26 双守）。
     */
    const val FOG_ALPHA_MAX = 0.13f

    /** 发现感渐显起点距离（相机到星 ≤ 此值开始渐亮） */
    const val DISCOVER_NEAR_START = 700f

    /** 发现感满档距离（≤ 此值=满增亮） */
    const val DISCOVER_NEAR_FULL = 260f

    /** 渐显亮度增益上限（未探索星 alpha 的接近补偿——「渐显恒星」的星半边） */
    const val DISCOVER_BOOST_MAX = 0.45f

    /**
     * 未探索星的发现感增益（0..[DISCOVER_BOOST_MAX]）：相机接近渐亮（远=0
     * =不改现状；近=+0.45 补偿暗星档——「接近=发现感」）。**只对未探索星调用**
     * （看过/订阅的星本来就在亮档，无需发现感）。
     */
    fun discoveryBoost(dist3d: Float): Float = discoveryBoost(dist3d, DISCOVER_NEAR_START)

    /**
     * R302 远视参数化（design-rpg-loop §2.2 Lv5「远视」：渐显起点 700→1200 世界
     * 单位=「看得更远」的字面落地）：[nearStart]=渐显起点距离（等级函数
     * [TasteStarlight.discoverNearStartOf] 的单口出值）；满档距不变。
     */
    fun discoveryBoost(dist3d: Float, nearStart: Float): Float {
        val start = nearStart.coerceAtLeast(DISCOVER_NEAR_FULL + 1f)
        val t = ((start - dist3d) / (start - DISCOVER_NEAR_FULL)).coerceIn(0f, 1f)
        return DISCOVER_BOOST_MAX * t
    }

    /** 雾团投影半径下限（px）：太小不成雾（防远档缩成光点） */
    const val FOG_MIN_PX = 26f

    /** 雾团屏幕半径换算：世界半径 × 焦距比（渲染端调用；下限保底） */
    fun fogPx(radiusWorld: Float, k: Float): Float = max(FOG_MIN_PX, radiusWorld * k)
}
