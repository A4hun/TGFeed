package dev.tgfeed.core.tastemap

import kotlin.math.cos
import kotlin.math.sin

/**
 * R323 发射星云=金矿摇篮**单源**（r315 K 章——core 纯件，零 Android 依赖，JVM
 * 单测可跑；[ProperMotionFx] 同模式）：**星云分类学**——未探索雾团按「成员中
 * 金矿候选占比」分化两型：
 *  - **发射星云**=亮档（位图 alpha×[EMISSION_ALPHA_MULT]、饱和度升——发射星云
 *    真的更亮）+内部星胚微光点（金矿候选逐个极暗点=「原恒星胚」，飞近
 *    discoveryBoost 渐显的**提前预告版** alpha 0.05~0.10）+电离朝向（雾朝最近
 *    活跃恒星（O/B 档——[TasteAstroPhysics.spectralClassOf] 单口）一侧更亮：
 *    渲染期一次最近邻）；
 *  - **反射/暗星云**=普通未探索雾（R235 现状原样零改——雾笔刷零改）。
 *
 * 判据（K①——装配期数据全既有，**零新增请求**）：[TasteNebula] 雾团成员（未探索
 * 在图频道）中金矿候选（[TasteAstroPhysics.BirthStage.NEBULA_CANDIDATE] 注记——
 * 未探索∧[TasteAstroPhysics.goldEvidenceOf]，R326/R327 既有宣判产出）占比
 * ≥[EMISSION_RATIO_GATE]。**诚实红线：金矿快照缺失=全部普通雾零冒充**（快照缺失
 * →goldItems 全 0→候选零→占比 0→恒普通——结构上不可绕；诞生半场失败空表同）。
 * 团级门在批 5 侧组合、星级位归判据族单口（r325 F5 域界声明照办）。
 *
 * **循环联动口**（R328 挂账兑现——r325 C①-2「supernova directive×星胚点表→脉冲
 * alpha」）：近旁超新星演出（[cyclePulse01] 空间共在门）×星胚点微亮——「死亡抛出
 * 的物质正在孕育新星」；脉冲参数=[TasteBirthFx.CYCLE_PULSE_*] 单源消费（本批
 * 前空挂零行为，本批接线）。
 *
 * 口径归属（K③）：发射星云属「未探索呈现」族——星云是在图天体层非底幕，与
 * taste_cmb 键无关（雾团在场性 R235 既有不动）。
 *
 * 确定性：零 Random 零系统钟（t 由渲染端传）；种子=雾团格键（装配间稳定）。
 */
object EmissionNebulaFx {

    /**
     * 发射门（占比）：成员中金矿候选 ≥50% 判发射档（K① 原值——半数成员是
     * 「新星原料」=产房成立；不足=普通未探索雾现状原样）。
     */
    const val EMISSION_RATIO_GATE = 0.5f

    /**
     * 发射门判定（**纯函数**——members/goldCandidates 两集合装配期已有零新请求）：
     * 占比 ≥[EMISSION_RATIO_GATE] 且成员非零。金矿快照缺失→goldCandidates=0→
     * 恒 false（**金矿缺失恒普通**——K① 诚实红线；候选数 0 时除零防御同 false）。
     */
    fun isEmission(members: Int, goldCandidates: Int): Boolean =
        members > 0 && goldCandidates > 0 &&
            goldCandidates.toFloat() / members >= EMISSION_RATIO_GATE

    // ==================== 星胚微光点（K② 内部 3~8 星胚） ====================

    /**
     * 星胚粒数帽（K⑤「星胚粒数-候选数单调+帽 8」）：粒数=金矿候选数钳 0..8——
     * **单调+帽**；诚实下界=候选数（候选<3 的边缘团粒数=候选数不冒充——「金矿
     * 候选逐个极暗点」一个候选一个胚，缺位不画）。
     */
    const val EMBRYO_CAP = 8

    /** 星胚粒数（=候选数钳 0..[EMBRYO_CAP]——单调+帽；0=非发射/零候选零绘制） */
    fun embryoCountOf(goldCandidates: Int): Int = goldCandidates.coerceIn(0, EMBRYO_CAP)

    /** 星胚基 alpha 下限（K② 原值族——discoveryBoost 提前预告版 0.05~0.10） */
    const val EMBRYO_ALPHA_MIN = 0.05f

    /** 星胚基 alpha 跨距（0.05~0.10——粒间哈希微差） */
    const val EMBRYO_ALPHA_SPAN = 0.05f

    /**
     * 星胚基 alpha（0.05~0.10 确定性哈希——渲染端乘雾在场度；「预告版」=比
     * discoveryBoost（0~0.45 渐显）更早可见更克制——远看是雾里的暗点，飞近后
     * 星本体渐显接管）。
     */
    fun embryoAlphaBase01(k: Int, seed: Int): Float =
        EMBRYO_ALPHA_MIN + EMBRYO_ALPHA_SPAN * TasteSpace.h01(seed.toLong(), k * 31 + 313)

    /**
     * 星胚合成 alpha（**双端同式消费口**——Canvas/GL 同一公式防两写漂移）：
     * 基 alpha×电离调制（朝电离源侧 0.75~1.00——被照亮读法；无电离源（cos=0）
     * =0.875 中性档）。
     */
    fun embryoAlpha01(k: Int, seed: Int, ionCos01: Float): Float =
        embryoAlphaBase01(k, seed) * (0.75f + 0.25f * ionization01(ionCos01))

    /**
     * 星胚粒径（px——1.2~2.1 确定性哈希；**双端同式消费口**：Canvas drawCircle
     * 半径/GL addPoint size 同值）。
     */
    fun embryoSizePx(k: Int, seed: Int): Float =
        1.2f + 0.9f * TasteSpace.h01(seed.toLong(), k * 31 + 323)

    /**
     * 星胚屏面偏移（单位雾半径——写 [out] 长 ≥2：dx/dy 单位向偏移，模 ≤0.62）：
     * 雾心+偏移×雾屏半径=星胚屏位；偏移相对雾心=雾团 R282 漂移摇曳时星胚自动
     * 跟随（相对偏移不随漂移解耦）。确定性哈希（同雾同胚同位——R 系铁律）。
     */
    fun embryoOffset01(k: Int, seed: Int, out: FloatArray) {
        val ang = TasteSpace.h01(seed.toLong(), k * 31 + 317) * 6.2831853f
        val rFrac = 0.15f + 0.47f * TasteSpace.h01(seed.toLong(), k * 31 + 319)
        out[0] = cos(ang) * rFrac
        out[1] = sin(ang) * rFrac
    }

    // ==================== 亮档（K② 位图 alpha×1.6+饱和度升） ====================

    /** 发射档 alpha 乘子（雾 alpha×1.6——发射星云真的更亮） */
    const val EMISSION_ALPHA_MULT = 1.6f

    /**
     * 发射档 alpha 帽=2×[TasteNebula.FOG_ALPHA_MAX]（联动公式——R252 档帽、雾帽
     * 2:1 历史比的单源化）。R252 档=0.10（K⑤ 验收③「亮度克制——不抢恒星」：
     * fogAlpha 满档 0.05×1.6=0.08 帽下，含电离侧辉极值 ≤0.10）。
     * R358 联动（r358_notes §2.2「×1.6 链不变=联动到约 0.21」）：FOG_ALPHA_MAX
     * 0.05→[0.10,0.16] 带后帽若守 0.10，发射档（×1.6）反被钳到普通档之下——
     * 「发射=更亮」（K②）语义倒挂。帽随雾帽联动：0.13 档=帽 0.26，发射满档
     * ≈0.21 帽下、侧辉余量同比例保留。判据/乘子/侧辉增益/判据矩阵零改——唯帽
     * 量级随雾帽单源联动。
     */
    const val EMISSION_ALPHA_CAP = TasteNebula.FOG_ALPHA_MAX * 2f

    /** 发射档雾 alpha（×[EMISSION_ALPHA_MULT] 帽 [EMISSION_ALPHA_CAP]） */
    fun emissionAlphaOf(fogAlpha: Float): Float =
        (fogAlpha * EMISSION_ALPHA_MULT).coerceAtMost(EMISSION_ALPHA_CAP)

    /**
     * 亮档色（HSV 饱和度×1.35 帽 0.85/明度×1.25 帽 0.90——发射星云更浓更亮；
     * 色相原样（类别投票语义不动）。纯 Kotlin HSV 提取（[TasteStarColor.hsvToArgb]
     * 回填——core 零 Android 依赖纪律）。
     */
    fun brightColorOf(colorArgb: Int): Int {
        val r = ((colorArgb shr 16) and 0xFF) / 255f
        val g = ((colorArgb shr 8) and 0xFF) / 255f
        val b = (colorArgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max <= 0f) return colorArgb // 全黑防御：原样（不为无色造亮）
        val v = max
        val s = (max - min) / max
        val hue = TasteStarColor.hueOfArgb(colorArgb)
        val s2 = (s * 1.35f).coerceAtMost(0.85f)
        val v2 = (v * 1.25f).coerceAtMost(0.90f)
        return TasteStarColor.hsvToArgb(hue, s2, v2)
    }

    // ==================== 电离朝向（K② 雾朝最近活跃星一侧更亮） ====================

    /**
     * 电离方向余弦域系数（0..1=0.5+0.5×cos 钳域）：星胚/侧辉在朝电离源一侧的
     * 亮度调制单一出处（cos01=屏面偏移向·电离向——余弦域 [-1,1] 单调）。
     */
    fun ionization01(cos01: Float): Float =
        0.5f + 0.5f * cos01.coerceIn(-1f, 1f)

    /**
     * 电离侧辉增益帽（侧辉 alpha=发射档 alpha×0.25——「朝星一侧更亮」的克制
     * 幅度：满档 0.08×0.25=0.02 增亮，星侧合计 ≤0.10=[EMISSION_ALPHA_CAP] 顶格）。
     */
    const val IONIZATION_GAIN_MAX = 0.25f

    /** 电离侧辉 alpha（发射档 alpha×[IONIZATION_GAIN_MAX]×[ionization01]） */
    fun ionizationSideGlowAlpha(emissionAlpha: Float, ion01: Float): Float =
        emissionAlpha * IONIZATION_GAIN_MAX * ion01.coerceIn(0f, 1f)

    /** 电离侧辉中心偏移系数（×雾屏半径——朝最近活跃星侧的屏向偏置） */
    const val IONIZATION_OFFSET_COEF = 0.30f

    /** 电离侧辉半径系数（×雾屏半径——小于雾本体的侧辉盘） */
    const val IONIZATION_RADIUS_COEF = 0.55f

    /**
     * 电离源判定（**O/B 档单口**——[TasteAstroPhysics.spectralClassOf] 判据族
     * 单一出处直引）：活跃率→光谱型 O/B=炽热大质量恒星=发射星云的电离源
     * （K②「最近活跃恒星（O/B 档）」）。渲染期单遍星表扫消费（O/B 稀有档）。
     */
    fun isIonizingSource(recent7: Int, lastActivityDays: Long): Boolean {
        val cls = TasteAstroPhysics.spectralClassOf(recent7, lastActivityDays)
        return cls == TasteAstroPhysics.SpectralClass.O || cls == TasteAstroPhysics.SpectralClass.B
    }

    // ==================== 循环联动口（R328 挂账兑现——CYCLE_PULSE_* 消费） ====================

    /**
     * 循环脉冲空间共在窗（世界单位平方）：300²（「同一片星域」读法——r327 循环行
     * BIRTH_CYCLE_NEIGHBOR_D2 同值口径，本口收口 core 单源=同数字单一出处；超新星
     * 遗迹环与雾团共在窗）。
     */
    const val CYCLE_PULSE_WINDOW_D2 = 300f * 300f

    /**
     * 循环脉冲 alpha（R328 挂账参数位**本批接线**——[TasteBirthFx.CYCLE_PULSE_*]
     * 单源消费：0.05~0.10 确定性呼吸×平方衰减）：
     *  - [active]=false（近旁无超新星演出）→恒 0=**空间共在门外零行为**（r325 C①-2
     *    「批 5 未落时联动口空挂零行为」的门外常态）；
     *  - [x01]=归一距离（0=脉冲源心，≥1=窗缘外→0 早退）。
     * 「超新星遗迹旁新星在孕育」——死亡抛出的物质正在孕育新星的空间叙事。
     */
    fun cyclePulse01(x01: Float, active: Boolean, t: Float, seed: Int): Float {
        if (!active) return 0f
        if (x01 >= 1f) return 0f
        return TasteBirthFx.cyclePulseAlpha(t, seed) * TasteBirthFx.cyclePulseDecay01(x01)
    }

    // ==================== 帧输入（平行表条目——与 TasteNebula.NebulaCluster 同序） ====================

    /**
     * 发射星云帧条目（[mapEmissionFogsR323] 产出——与帧字段 nebulae **同序同长
     * 平行表**：普通雾=emission false（零绘制零成本早退））。位置不内嵌（渲染端
     * 用雾心当帧投影——星胚偏移相对雾心=随 R282 漂移摇曳自动跟随）。
     */
    class Fog(
        /** 发射门判决（false=普通雾：R235 现状原样，本条目其余字段不消费） */
        val emission: Boolean,
        /** 星胚粒数（0=非发射/零候选——零绘制） */
        val embryoCount: Int,
        /** 确定性种子（=雾团格键——装配间稳定身份） */
        val seed: Int,
        /** 亮档色（饱和度升——[brightColorOf] 产出；普通雾不消费） */
        val brightArgb: Int,
    )
}
