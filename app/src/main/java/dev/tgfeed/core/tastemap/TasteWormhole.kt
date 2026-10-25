package dev.tgfeed.core.tastemap

/**
 * R235 虫洞纯函数（口味星图四期·RPG 层·零 Android 依赖单测可跑）。
 *
 * **「长按行星（tag）→ 虫洞跳跃 → 到达共享该 tag 的另一颗恒星旁」**（任务书
 * 原文）——星际考古的空间快线（R233 关联弧是「看见共振」，虫洞是「穿越过去」）：
 *
 *  - **目标选星** [pickTarget]：tagProfiles 里含该 tag 的**其他**频道中——在图
 *    优先 → 该 tag 计数降序（最大共振伙伴）→ chatId 升序（确定性破平）。无
 *    目标=0（调用方诚实 Toast「该 tag 还没有别的恒星」——没有证据不冒充虫洞）；
 *  - **相机路径** [camPath]：from → 目标星锚（R233 星锚 260 停船口径）的
 *    easeInOutCubic 加速段 + **中段纵深鼓包**（dive 在 transit 中点前冲
 *    [TUNNEL_BULGE] 世界单位=dist_eff 深陷——「运镜加速→拉出扭曲视觉=虫洞隧道
 *    感」的数学半边；视觉半边=渲染器隧道叠加段，强度同用 sin(π·p)）；
 *  - [WORMHOLE_SEC]=1.5s（transit 时长——比 R231 双击运镜 0.9s 慢：穿越要
 *    有「钻过去」的过程感）。
 */
object TasteWormhole {

    /** 虫洞 transit 时长（秒） */
    const val WORMHOLE_SEC = 1.5f

    /**
     * 中段纵深鼓包（世界单位）：transit 中点 dive 前冲量——dist_eff 深陷=
     * 星野向外涌+近裁面吞掉大半天体（穿隧道），p→1 收敛回停船 dive。
     */
    const val TUNNEL_BULGE = 700f

    /**
     * 目标选星（纯函数，确定性）：含 [tag] 的其他频道（≠[focusChatId]）里——
     * 在图（[onMapIds]）优先 → 该 tag 计数降序 → chatId 升序。返回 0=无目标。
     */
    fun pickTarget(
        tag: String,
        focusChatId: Long,
        tagProfiles: Map<Long, Map<String, Int>>,
        onMapIds: Set<Long>,
    ): Long {
        if (tag.isBlank() || tagProfiles.isEmpty()) return 0L
        val fold = tag.lowercase()
        // 全量收集后排序（HashMap 遍历序不定——排序后同数据同目标，确定性纪律）
        val cands = ArrayList<Pair<Long, Int>>(tagProfiles.size)
        for ((chatId, profile) in tagProfiles) {
            if (chatId == focusChatId || chatId == 0L) continue
            val count = profile[fold] ?: continue
            cands += chatId to count
        }
        if (cands.isEmpty()) return 0L
        cands.sortWith(
            compareByDescending<Pair<Long, Int>> { (it.first in onMapIds).compareTo(false) }
                .thenByDescending { it.second }
                .thenBy { it.first },
        )
        return cands.first().first
    }

    /** easeInOutCubic（0→0、1→1、中段最陡——「加速→减速」的运镜手感） */
    fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
    }

    /**
     * 虫洞相机路径（纯函数）：progress 01 → 相机四元组（写 [out]，长 ≥4：
     * x/y/zoom/dive）。
     *  - p=0：fromCam 原样（含 roll 语义由调用方保留——虫洞不引入滚转）；
     *  - p=1：目标星锚（(tx, ty)、[tZoom]、[tDive]=R233 星锚 260 停船口径）；
     *  - 中段：dive 鼓包（sin(π·p)×[TUNNEL_BULGE] 前冲——隧道数学半边）+ zoom
     *    略收（视场收到 1.35 再放开——「钻管道」的焦段感）。
     */
    fun camPath(
        fromCam: TasteFlight.FlightCam,
        tx: Float,
        ty: Float,
        tZoom: Float,
        tDive: Float,
        p01: Float,
        out: FloatArray,
    ) {
        val p = p01.coerceIn(0f, 1f)
        val e = easeInOutCubic(p)
        val bulge = kotlin.math.sin(p * Math.PI.toFloat()) * TUNNEL_BULGE
        out[0] = fromCam.x + (tx - fromCam.x) * e
        out[1] = fromCam.y + (ty - fromCam.y) * e
        // zoom：中段收到 1.35（不管两端 zoom 多大——钻管道的窄视场）再回到 tZoom
        val zoomMid = 1.35f
        val zoomTarget = fromCam.zoom + (zoomMid - fromCam.zoom) * (2f * p).coerceAtMost(1f)
        val zoomEnd = zoomMid + (tZoom - zoomMid) * ((2f * p - 1f).coerceAtLeast(0f))
        out[2] = if (p < 0.5f) zoomTarget else zoomEnd
        out[3] = fromCam.dive + (tDive - fromCam.dive) * e + bulge
    }

    /**
     * R242 空间相机虫洞路径（SpaceCam 口径，写 [out] 长 ≥5：px/py/pz/yaw/pitch）：
     *  - p=0：fromCam 原样；p=1：目标星锚（星位 − 视星方向×[stopDist] 停船）且
     *    朝向对准星（到达即行星层可见的 R233 井内停船口径）；
     *  - 中段：**前冲鼓包**（sin(π·p)×[TUNNEL_BULGE] 沿路径方向过冲=更贴近目标
     *    ——星野向外涌+近裁面吞掉大半天体的「穿隧道」数学半边；渲染隧道叠加
     *    同 sin(π·p) 强度）；
     *  - 朝向：从起跳朝向平滑转向目标星（easeInOutCubic 同式）。
     */
    fun camPathSpace(
        fromCam: TasteSpace.SpaceCam,
        tx: Float,
        ty: Float,
        tz: Float,
        stopDist: Float,
        p01: Float,
        out: FloatArray,
    ) {
        val p = p01.coerceIn(0f, 1f)
        val e = easeInOutCubic(p)
        val bulge = kotlin.math.sin(p * Math.PI.toFloat()) * TUNNEL_BULGE
        // 目标星锚（沿连线退 stopDist）
        var dx = tx - fromCam.px
        var dy = ty - fromCam.py
        var dz = tz - fromCam.pz
        var len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) len = 1e-4f
        dx /= len; dy /= len; dz /= len
        val gx = tx - dx * stopDist
        val gy = ty - dy * stopDist
        val gz = tz - dz * stopDist
        // 插值位 + 中段过冲（沿起→锚方向）
        val ex = gx - fromCam.px
        val ey = gy - fromCam.py
        val ez = gz - fromCam.pz
        val elen = kotlin.math.sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-4f)
        val px = fromCam.px + ex * e + (ex / elen) * bulge
        val py = fromCam.py + ey * e + (ey / elen) * bulge
        val pz = fromCam.pz + ez * e + (ez / elen) * bulge
        // 朝向：从插值位看向星（每帧按当前位解——收敛到「停在星前、看着星」）
        val adx = tx - px
        val ady = ty - py
        val adz = tz - pz
        val alen = kotlin.math.sqrt(adx * adx + ady * ady + adz * adz).coerceAtLeast(1e-4f)
        val tYaw = Math.toDegrees(kotlin.math.atan2(adx.toDouble(), adz.toDouble())).toFloat()
        val tPitch = Math.toDegrees(kotlin.math.asin((ady / alen).coerceIn(-1f, 1f).toDouble())).toFloat()
        val yaw = TasteSpace.angDiff(fromCam.yawDeg, tYaw) * e
        val pitch = TasteSpace.angDiff(fromCam.pitchDeg, tPitch) * e
        out[0] = px
        out[1] = py
        out[2] = pz
        out[3] = TasteSpace.wrapDeg(fromCam.yawDeg + yaw)
        out[4] = (fromCam.pitchDeg + pitch)
            .coerceIn(-GestureFeelPolicy.PITCH_LIMIT_DEG, GestureFeelPolicy.PITCH_LIMIT_DEG)
    }

    /** 隧道叠加强度（0..1）：sin(π·p)——中段最强（「拉出扭曲」的视觉半边同式） */
    fun tunnelIntensity(p01: Float): Float = kotlin.math.sin(p01.coerceIn(0f, 1f) * Math.PI.toFloat())
}
