package dev.tgfeed.core.tastemap

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R351 雾团 fBm 絮化**单源**（r344 审计 B 级最后一项——「雾=单 blob 同心叠的
 * 贴图感」病根收口；core 纯件，零 Android 依赖，JVM 单测可跑——TasteTerminalFx
 * 同族模式：GL（GlFrameBuilder）渲染端只画不算，絮化参数一处改全局生效——
 * 纪律§一类问题收敛）：
 *
 *  - **fBm 值噪声**（[vnoise01]/[fbm01]）：3 octave 1D 值噪声——格点哈希=
 *    [TasteSpace.h01] 同族单口+smoothstep 插值+等阶权和归一（零增益）；
 *    非整倍频=格点错位（防周期条纹）。确定性零 Random 零系统钟——同雾同絮
 *    （R 系铁律）；
 *  - **blob 族**（r344 B-8「同雾 2~3 blob 哈希错位叠画」字面）：[blobCountOf]
 *    每雾 2~3；[blobOffset01] 世界系 3D 错位向（哈希球面 2 DOF——绕飞真视差，
 *    屏面错位随相机自转=贴纸感）+fBm 模长；[blobRadius01] 半径系数（fBm 只缩
 *    不胀）；[blobAlphaPack01] alpha 配比包（fBm 权+归一化）。R281 双层视差
 *    层族原值（[FRONT_ALPHA]/[BACK_ALPHA_TOTAL]/[FRONT_R]/[BACK_R]/
 *    [LAYER_DEPTH_OFF]）随迁本件常量=原值直引零调；
 *  - **量级零增益红线**（宪法一章第三条+R252 FOG_ALPHA_MAX 收敛先例+
 *    remnantFiberErode01「侵蚀只做形状不做增益」同款纪律）：配比包归一化
 *    Σ恒=[ALPHA_PACK_SUM]（絮化只重分布不放大）；半径系数 ≤1（雾外廓不因
 *    絮化外扩）；错位模长 ≤[OFFSET_MAX]（块间恒重叠=弥散连体不散架）；
 *  - **弥散语义保义**（宪法 L119：星云=弥散例外显式占行——絮化≠锐化）：
 *    blob 边弧=NEBULA_FS 既有径向 falloff+fbm 密度场原样（块内丝缕 R281 已
 *    在）——本件零纹理零剖面锐化，只把同心整团拆错位絮块。
 *
 * 消费端：GL buildNebulas blob 循环（单端——Canvas 已随 R345 退役）。种子=
 * 雾团身份哈希（渲染器既有 churn 团位哈希折叠 Int——雾团无 chatId，聚类产物
 * 以质心为身份锚；同族纪律与星按 chatId 哈希同义）。
 */
object FogFlocFx {

    // ==================== blob 族结构常量（R281 层族原值迁入——量级红线） ====================

    /** 每雾 blob 数下限（r344 B-8「2~3 blob」下界；2=前后双层族下限保义） */
    const val BLOB_COUNT_MIN = 2

    /** 每雾 blob 数上限（3=絮状观感与帧账的平衡点：雾 op 峰值 6 团×3） */
    const val BLOB_COUNT_MAX = 3

    /** 前层 alpha 配比（R281 原值 0.55——近层弱配比原样） */
    const val FRONT_ALPHA = 0.55f

    /** 后层 alpha 配比合计（R281 原值 0.80——后层 1~2 blob 分摊共享） */
    const val BACK_ALPHA_TOTAL = 0.80f

    /**
     * alpha 配比包恒和（FRONT+BACK=1.35 R281 双层原值——**量级零增益锚**：
     * 絮化只重分布不放大，[blobAlphaPack01] 归一化的目标值单一出处）。
     */
    const val ALPHA_PACK_SUM = FRONT_ALPHA + BACK_ALPHA_TOTAL

    /** 前层半径基（R281 原值 0.90——近层略缩） */
    const val FRONT_R = 0.90f

    /** 后层半径基（R281 原值 1.10——远层略胀=层间错位的纵深读法） */
    const val BACK_R = 1.10f

    /**
     * 前后层深度错位（×雾半径，R281 原值 ±0.35：双层视差伪体积的层间距——
     * k=0 前层取正（近相机）、k≥1 后层取负；raymarch 真体积须用户另拍板）。
     */
    const val LAYER_DEPTH_OFF = 0.35f

    // ==================== fBm 值噪声（确定性 3 octave） ====================

    /** 值噪声格点哈希通道基（h01 第二参基点——remnant 族 221 区隔的独立通道段） */
    private const val LAT_CH = 501

    /** 值噪声 octave 倍频（非整倍=格点错位防周期条纹；第三阶 2.17×2.09） */
    private const val OCT2_MUL = 2.17f
    private const val OCT2_OFF = 3.1f
    private const val OCT3_MUL = 4.53f
    private const val OCT3_OFF = 7.7f

    /**
     * 1D 值噪声（格点=整数 lattice：[TasteSpace.h01] 哈希+smoothstep 插值）：
     * 值域 [0,1)。x 可为任意实数（blob 序号的噪声坐标）；确定性（同 seed 同
     * 格同值——零 Random 零系统钟）。
     */
    fun vnoise01(seed: Int, x: Float): Float {
        val i = floor(x)
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = TasteSpace.h01(seed.toLong(), (i + LAT_CH).toInt())
        val b = TasteSpace.h01(seed.toLong(), (i + LAT_CH + 1).toInt())
        return a + (b - a) * u
    }

    /**
     * fBm 值噪声（3 octave：0.50/0.30/0.20 等阶权和=1 零增益；每阶种子 +101
     * 通道错开=三个独立噪声实例）。值域 [0,1)。r344 B-8「fBm 侵蚀」的确定性
     * 近似——雾形态扰动的噪声底座（半径/密度/错位模长的求值源）。
     */
    fun fbm01(seed: Int, x: Float): Float =
        0.50f * vnoise01(seed, x) +
            0.30f * vnoise01(seed + 101, x * OCT2_MUL + OCT2_OFF) +
            0.20f * vnoise01(seed + 202, x * OCT3_MUL + OCT3_OFF)

    // ==================== blob 族（同雾 2~3 blob 错位叠） ====================

    /** blob 错位模长下限（×雾半径——前层贴雾心档：错位非零即有絮感） */
    const val OFFSET_MIN = 0.08f

    /**
     * blob 错位模长上限（×雾半径）：0.45 内 blob 间恒重叠=**弥散连体不散架**
     * （宪法弥散占行——絮块是雾的内部结构不是散点群；上限=错位域单口）。
     */
    const val OFFSET_MAX = 0.45f

    /** blob 半径系数下限（fBm 扰动域下界——絮块有大有小：0.58 仍远大于边缘 falloff 带） */
    const val RADIUS_MIN = 0.58f

    /**
     * 团位哈希折叠系数（churn 种子 ×8191 折叠 Int——h01 第二参族口径；渲染端
     * churn Float 原样另传 shader（NEBULA_FS 相位与本族 Int 各走各口））。
     */
    const val SEED_FOLD = 8191f

    /**
     * 雾絮种子（团位哈希折叠 Int）：churn 种子（cx×0.7+cy×1.3+cz×1.7——渲染器
     * 既有确定性身份锚）×[SEED_FOLD] 折叠。**种子=团位哈希**（诚实记档：雾团无
     * chatId——聚类产物以质心为身份；哈希族纪律同义=星按 chatId 哈希、雾按团位
     * 哈希）——零 Random 零系统钟，同雾同絮铁律的入口单口。
     */
    fun flocSeedOf(churnSeed: Float): Int = (churnSeed * SEED_FOLD).toInt()

    /**
     * 每雾 blob 数（2~3——seed 哈希；负 seed 余数归一（Kotlin % 负域防 1，
     * remnantFilaments 同式））。絮状观感的低成本实现：错位叠画而非新纹理
     * （r344 B-8）。
     */
    fun blobCountOf(seed: Int): Int =
        BLOB_COUNT_MIN + ((seed % 2) + 2) % 2

    /**
     * blob 世界系错位向量（单位雾半径的倍数，写 [out] 长 ≥3：ox/oy/oz）：
     * 方向=哈希球面 2 DOF（余弦均匀——skyDirection 同构）；模长=fBm
     * [OFFSET_MIN]..[OFFSET_MAX]。**世界系=绕飞真视差**（错位不随相机自转
     * ——与屏面错位的贴纸感区分）；调用方乘雾世界半径加到团心上。
     */
    fun blobOffset01(seed: Int, k: Int, out: FloatArray) {
        val u1 = TasteSpace.h01(seed.toLong(), k * 31 + 521)
        val u2 = TasteSpace.h01(seed.toLong(), k * 31 + 523)
        val theta = u1 * 6.2831853f
        val cphi = 2f * u2 - 1f
        val sphi = sqrt((1f - cphi * cphi).coerceAtLeast(0f))
        val mag = OFFSET_MIN + (OFFSET_MAX - OFFSET_MIN) * fbm01(seed + 601, k * 1.7f + 0.9f)
        out[0] = sphi * cos(theta) * mag
        out[1] = cphi * mag
        out[2] = sphi * sin(theta) * mag
    }

    /**
     * blob 半径系数（[RADIUS_MIN]..1.0——fBm 扰动雾半径：絮块有大有小）：
     * **上限 1=只缩不胀**（雾外廓不因絮化外扩——量级红线；层基
     * [FRONT_R]/[BACK_R] 由调用方乘）。
     */
    fun blobRadius01(seed: Int, k: Int): Float =
        RADIUS_MIN + (1f - RADIUS_MIN) * fbm01(seed + 701, k * 1.9f + 2.7f)

    /**
     * blob alpha 配比包（写 [out] 前 [blobCount] 槽）：k=0 前层配比
     * [FRONT_ALPHA]、k≥1 后层均分 [BACK_ALPHA_TOTAL]；各乘 fBm 权
     * （0.62~1.0——絮块明暗不均=密度剖面扰动），再按 Σ=[ALPHA_PACK_SUM]
     * 归一——**配比和恒 1.35 R281 原值**（絮化只做形状不做增益——量级
     * 零增益单口；错位使重叠下降、总墨量恒定=弥散量级原样）。
     */
    fun blobAlphaPack01(seed: Int, out: FloatArray, blobCount: Int) {
        val n = blobCount.coerceIn(BLOB_COUNT_MIN, BLOB_COUNT_MAX)
        var sum = 0f
        for (k in 0 until n) {
            val base = if (k == 0) FRONT_ALPHA else BACK_ALPHA_TOTAL / (n - 1)
            val w = 0.62f + 0.38f * fbm01(seed + 801, k * 2.1f + 4.3f)
            out[k] = base * w
            sum += out[k]
        }
        val s = ALPHA_PACK_SUM / sum
        for (k in 0 until n) out[k] *= s
    }
}
