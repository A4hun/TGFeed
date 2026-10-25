package dev.tgfeed.core.tastemap

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R323 自行（Proper Motion）**单源**（r315 J 章——core 纯件，零 Android 依赖，JVM
 * 单测可跑；[TasteTerminalFx]/[TasteBirthFx] 同模式）：**频道口味漂移的方向与强度**
 * ——近窗 vs 远窗的构成差（视频/图文/音频三票比例变化）→口味空间的漂移矢量。
 *
 * 天文真义→本宇宙映射（J①）：恒星的自行=相对背景的真实位移（矢量，mas/年——
 * 巴纳德星最大）；频道的自行=内容构成随时间的变化方向。数据=[DeepSearchIndex.
 * chatPublishMediaHistogram] 两窗构成点查（S3 直方图点查件的两窗构成消费——
 * per-item dateSec+mediaType 全量历史在库，有界点查非全库扫描）。
 *
 * **诚实红线（J① 原文照办）**：
 *  - 任一窗条数 <[MIN_WINDOW_ITEMS]（8 条）=**缺据不判**（样本太少的构成比是噪声
 *    ——null，矢量与 whyChain 行全不出）；
 *  - 两窗构成差 L1<[DRIFT_L1_GATE]=零漂移（**「没动」也是结论**，不硬找方向——
 *    null；近窗远窗同构成=没有漂移就不画尾巴）。
 *
 * 布局层挂钩（挂 R303 P3——本包只出矢量数据与纯函数**不动当前布局**保形）：构成
 * 漂移矢量=涌现布局「时间箭头·持续通道」的现成数据源（P3 落地后温启弛豫消费）。
 *
 * 确定性：零 Random 零系统钟（nowSec 由调用方传——R 系铁律）；同直方图同矢量。
 */
object ProperMotionFx {

    // ==================== 两窗口径（J①：近 90 天 vs 前 180~90 天） ====================

    /** 近窗宽（秒——90 天：nowSec 往回看） */
    const val NEAR_WINDOW_SEC = 90L * 86_400L

    /** 远窗远界（秒——180 天：远窗=[前 180 天, 前 90 天) 的 90 天窗） */
    const val FAR_WINDOW_SEC = 180L * 86_400L

    /**
     * 单窗最小样本（条）：任一窗 <8=缺据不判（J① 诚实红线——两窗各 8 条是构成比
     * 不至于全噪声的下限；漂移池入口门 =2×本值=必要非充分，点查后仍按实际两窗
     * 计数判）。
     */
    const val MIN_WINDOW_ITEMS = 8

    /**
     * 漂移池帽（BIRTH_POOL 同款有界池姿势——r315 J⑤ 点查成本 ∝ 频道条数，候选集
     * 有界=装配期耗时可控；池外星零点查零漂移=诚实不冒充（「池外不判」与终态/
     * 诞生宣判同哲学））。
     */
    const val DRIFT_POOL_CAP = 30

    /**
     * 漂移池入口体量门（条——两窗各 ≥[MIN_WINDOW_ITEMS] 的必要非充分条件：历史
     * 总条数不足两窗下限的频道结构性缺据，不进池省点查）。
     */
    const val DRIFT_POOL_MIN_ITEMS = MIN_WINDOW_ITEMS * 2

    // ==================== 漂移门与强度（J① 零漂移=诚实结论） ====================

    /**
     * 零漂移门（三票份额差 L1）：|Δ视频|+|Δ图文|+|Δ音频| <0.20=「没动」（近窗远窗
     * 同构成——差小于阈值的构成比变化=噪声级，不硬找方向；**「没动」也是结论**，
     * 矢量/whyChain 行全不出）。
     */
    const val DRIFT_L1_GATE = 0.20f

    /** 强度归一帽（L1 满档——强度01=(L1−门)/(帽−门) 钳 0..1） */
    const val DRIFT_L1_CAP = 1.20f

    // ==================== 方向映射（三基色语义同族——J②） ====================

    /**
     * 三票方向基（色相锚=[TasteNebula.CATEGORY_HUE_*] 公开常量直引——**单一出处，
     * 改就一起改**）：视频化=朝口味角红移方向（20° 暖红）/图文化=青（125°）/
     * 音频化=紫（265°）。方向=Δ三票加权合成归一 2D 单位向（正 Δ 朝该票基向）。
     */
    private val DIR_VIDEO_RAD = Math.toRadians(TasteNebula.CATEGORY_HUE_VIDEO.toDouble())
    private val DIR_PHOTO_RAD = Math.toRadians(TasteNebula.CATEGORY_HUE_PHOTO.toDouble())
    private val DIR_AUDIO_RAD = Math.toRadians(TasteNebula.CATEGORY_HUE_AUDIO.toDouble())

    // ==================== 选中态矢量小尾巴（J② 克制主案——焦点件参数） ====================

    /** 尾巴基长（px——选中/聚焦星旁的极细矢量小尾巴；全景不显示=焦点件零全景成本） */
    const val TAIL_LEN_MIN_PX = 14f

    /** 尾巴长度跨距（px——长度=基长+强度01×跨距，帽=基+跨） */
    const val TAIL_LEN_SPAN_PX = 30f

    /** 尾巴头点粒径（px——矢量指向读法；Canvas/GL 双端同值） */
    const val TAIL_HEAD_PX = 1.6f

    /**
     * 漂移矢量（[driftOf] 出口——**StarNode.drift 注记载体**，缓存 v7 载荷随星走）：
     * 份额差三票/方向 2D 单位向/强度 01/两窗三票原值（whyChain 漂移行「近窗图文 61%
     * vs 远窗 23%」口径的数字全两窗实况——文案归 [AstroFacts]，数据归本件）。
     */
    data class Drift(
        /** 份额差（Δ=近窗份额−远窗份额；正=该票占比在升） */
        val dv: Float,
        val dp: Float,
        val da: Float,
        /** 方向单位向（2D——三票差×三基色基向合成归一；尾巴的屏向） */
        val dirX: Float,
        val dirY: Float,
        /** 强度 01（0..1——L1 门后归一；尾巴长度∝它） */
        val strength01: Float,
        /** 近窗三票（条——文案/序列化原料） */
        val nearVideo: Int,
        val nearPhoto: Int,
        val nearAudio: Int,
        /** 远窗三票（条） */
        val farVideo: Int,
        val farPhoto: Int,
        val farAudio: Int,
    )

    /**
     * 两窗三票聚合计（装配查询原料——[DeepSearchIndex.chatPublishMediaHistogram]
     * 的 (day, mediaType, count) 三元组表按窗归桶）：近窗=[nowSec−90d, nowSec]、
     * 远窗=[nowSec−180d, nowSec−90d)（日桶起点落窗判——日粒度精度 ±1 天对 90 天
     * 窗零影响，[ChatPublishHistogram] 既有口径）；更早的日桶=窗外不计（两窗外的
     * 远古史不参与构成差）。返回 (近窗, 远窗)——调用方先过 [MIN_WINDOW_ITEMS] 门。
     */
    fun windowVotes(
        mediaDayHistogram: List<Triple<Int, Int, Int>>,
        nowSec: Long,
    ): Pair<Votes, Votes> {
        val nearStart = nowSec - NEAR_WINDOW_SEC
        val farStart = nowSec - FAR_WINDOW_SEC
        var nv = 0; var np = 0; var na = 0
        var fv = 0; var fp = 0; var fa = 0
        for ((day, mediaType, count) in mediaDayHistogram) {
            if (count <= 0) continue
            val dayStartSec = day * 86_400L
            if (dayStartSec > nowSec) continue // 时钟偏斜的未来桶：窗外不计（诚实）
            val intoNear = dayStartSec >= nearStart
            val intoFar = !intoNear && dayStartSec >= farStart
            if (!intoNear && !intoFar) continue
            when (mediaType) {
                0 -> if (intoNear) nv += count else fv += count
                1 -> if (intoNear) np += count else fp += count
                2 -> if (intoNear) na += count else fa += count
                else -> continue // 防御：未知类型不入票（不为缺档编造构成）
            }
        }
        return Votes(nv, np, na) to Votes(fv, fp, fa)
    }

    /**
     * 漂移宣判（**纯函数——缓存回读同式重导**，公式单一出处）：
     *  - 任一窗 <[MIN_WINDOW_ITEMS]=**null 缺据不判**（诚实红线①）；
     *  - 份额差 L1<[DRIFT_L1_GATE]=**null 零漂移**（诚实红线②——「没动」也是结论）；
     *  - 过两门=Drift（方向/强度/两窗原值）。
     */
    fun driftOf(near: Votes, far: Votes): Drift? {
        val nTotal = near.total
        val fTotal = far.total
        if (nTotal < MIN_WINDOW_ITEMS || fTotal < MIN_WINDOW_ITEMS) return null
        val dv = near.video.toFloat() / nTotal - far.video.toFloat() / fTotal
        val dp = near.photo.toFloat() / nTotal - far.photo.toFloat() / fTotal
        val da = near.audio.toFloat() / nTotal - far.audio.toFloat() / fTotal
        val l1 = abs(dv) + abs(dp) + abs(da)
        if (l1 < DRIFT_L1_GATE) return null
        var dx = dv * cos(DIR_VIDEO_RAD).toFloat() +
            dp * cos(DIR_PHOTO_RAD).toFloat() +
            da * cos(DIR_AUDIO_RAD).toFloat()
        var dy = dv * sin(DIR_VIDEO_RAD).toFloat() +
            dp * sin(DIR_PHOTO_RAD).toFloat() +
            da * sin(DIR_AUDIO_RAD).toFloat()
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) {
            // 三基向在 2D 线性相关：L1 过门仍可能合成近零向（三票轮转态）——回落
            // 最大 |Δ| 票的基向（主导漂移方向，绝不返回零向硬造方向）
            val (bx, by) = when {
                abs(dv) >= abs(dp) && abs(dv) >= abs(da) ->
                    cos(DIR_VIDEO_RAD).toFloat() to sin(DIR_VIDEO_RAD).toFloat()
                abs(dp) >= abs(da) ->
                    cos(DIR_PHOTO_RAD).toFloat() to sin(DIR_PHOTO_RAD).toFloat()
                else ->
                    cos(DIR_AUDIO_RAD).toFloat() to sin(DIR_AUDIO_RAD).toFloat()
            }
            dx = bx; dy = by
        } else {
            dx /= len; dy /= len
        }
        val strength01 = ((l1 - DRIFT_L1_GATE) / (DRIFT_L1_CAP - DRIFT_L1_GATE)).coerceIn(0f, 1f)
        return Drift(
            dv, dp, da, dx, dy, strength01,
            near.video, near.photo, near.audio,
            far.video, far.photo, far.audio,
        )
    }

    /** 尾巴长度（px——基长+强度×跨距；渲染端焦点件消费） */
    fun tailLenPx(strength01: Float): Float =
        TAIL_LEN_MIN_PX + TAIL_LEN_SPAN_PX * strength01.coerceIn(0f, 1f)

    /**
     * 尾巴透明度（0.30+0.35×强度——极细小尾巴的克制档；渲染端乘星 alpha——
     * labelPaint 族细线的「弱于星体」读法）。
     */
    fun tailAlpha01(strength01: Float): Float =
        0.30f + 0.35f * strength01.coerceIn(0f, 1f)

    /** 单窗三票（[windowVotes] 的窗口计；data class=确定性单测等值锚） */
    data class Votes(val video: Int, val photo: Int, val audio: Int) {
        val total: Int get() = video + photo + audio
    }
}
