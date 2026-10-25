package dev.tgfeed.core.tastemap

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R206 口味星图 2.0——自由深空飞行的**纯数学核心**（零 Android 依赖，单测可跑）：
 *
 *  - **自由飞行相机**（替换 R147b 轨道 yaw/pitch 天球模型的病灶——用户原话要
 *    「无尽深空探索的感觉」）：相机是无限深空中的一个自由质点，位于世界坐标
 *    (x, y, −dist) 沿 +z 观察；拖拽=平移（星野在指尖滑走，无固定中心锚），
 *    双指=俯冲/拉远（dolly+lens 复合缩放），松手=惯性滑行衰减；
 *  - 星的世界坐标（MDS 嵌入产出）原样保留——**3D 深度驱动的视差**是深空感的
 *    核心：平移时近星（z 小）快、远星（z 大）慢；俯冲时 persp 差随 dist 收敛
 *    而放大；
 *  - 创世纪时间轴（星生序=频道首见时间的凝聚可见性判定）；
 *  - 轻量粒子系统（帧驱动 + 定长数组对象池，零逐帧分配）：点星爆散 / 连线流光 /
 *    彗星（爬虫脉冲）/ 今晚流星串 / 星爆闪（新频道诞生）；
 *  - XY 鲁棒取景框（R152c p2..p98 分位口径移植到飞行投影的平面版）；
 *  - R210 手势参照系修正（走查双实锤根因）：[gestureStep] 是 detectTransformGestures
 *    回调→相机的唯一映射（rotation=度直传，滚转零放大泄漏）；[zoomAt] 捏合缩放锚定
 *    双指质心（z=0 参考平面反投影，与 [panBy] 同一「中深度跟手」参照系）；
 *  - **R241 俯仰轴**（用户原创手势「把面前的星图墙仰到棚顶/俯到地面」）：相机
 *    [FlightCam.pitchDeg]=仰角（绕相机 X 轴旋转视锥——正=仰头看棚顶、负=低头看
 *    地面、连续 360°=星图球面化，沉浸态满天星）；[project] 相机系先做俯仰旋量再
 *    投影（pitch=0 逐位=旧式）；一指按住+另一指上滑=[pitchBy] 俯仰增加（**取代
 *    R231 dive 的手势语义**——dive 轴本身保留，运镜/工具条/虫洞继续驱动）。
 *
 * 量纲约定：世界单位与 TasteMapScreen.WORLD=1000 同源（core 层不反向依赖 UI，
 * 常量镜像注释同步——ShareCard.TASTE_WORLD 同款约定）。[D0] 与
 * ShareCard.TASTE_CAM_D=1650 同值同注释；[FOG] 与 ShareCard.TASTE_FOG=900 同值
 * 同注释。渲染器（ui 层 TasteFlightRenderer）与本文件一一分工：这里只算状态与
 * 几何，那边只碰 Canvas/Paint。
 */
object TasteFlight {

    /** 初始相机距离（世界单位）：zoom=1 时相机到云团中心平面 z=0 的距离（镜像 ShareCard.TASTE_CAM_D） */
    const val D0 = 1650f

    /** 深度雾距离（世界单位）：离相机每远此值雾约减半（镜像 ShareCard.TASTE_FOG） */
    const val FOG = 900f

    /** 近平面防护：深度低于此值按此值算（旧轨道投影同款防透视爆炸） */
    const val NEAR_PLANE = 200f

    /** 缩放下限/上限（表观倍率，相对初始取景）：0.05=拉远看全貌（含未知极/离群点） */
    const val ZOOM_MIN = 0.05f

    /** 缩放上限：俯冲到星域邻域（再往里 persp 差会糊掉读数） */
    const val ZOOM_MAX = 10f

    /** dolly 指数：zoom 的 0.35 次方落在相机推进上（dist=D0/zoom^0.35——俯冲的视差增益） */
    const val DOLLY_EXP = 0.35f

    /** lens 指数：zoom 的 0.65 次方落在焦距上（与 dolly 合成表观倍率恰=zoom，两者互补不重复计价） */
    const val LENS_EXP = 0.65f

    /** 惯性速度衰减（每 16.7ms 一帧 ×此值；松手「飘在太空里」的阻尼手感，旧心跳 0.94 同款） */
    const val FLING_DECAY_PER_FRAME = 0.94f

    /** 惯性停机阈（世界单位/秒） */
    const val FLING_EPS = 24f

    /** 惯性接管延迟（手势结束 >110ms 后帧循环才接管速度——手势期速度只记录不应用） */
    const val GESTURE_IDLE_MS = 110L

    // ==================== R231 纵深飞行（dive 轴） ====================

    /** 纵深飞行速率：一屏高上推 = 此值×[D0] 世界单位的前进。
     *  R237 真机收口：1.0→1.5——用户实锤「推了没反应」（小冲程 600px≈0.3 屏只
     *  前进 ~490 单位，概览 dist=1650 到近带两三冲程才见效=感知迟滞）；1.5×
     *  下同冲程前进 ~740 单位，一满屏≈2475≈前界极限——一冲程入深空。 */
    const val FLIGHT_DIVE_PER_SCREEN_H = 1.5f

    /**
     * 纵深飞行前界（dist_eff 下限，**负值=可穿越云团中心飞到远带后方**——「被星
     * 包围」的空间语义；R231 前相机 z 恒 ≤−738 永远在云团外面=用户实锤②的根因）
     */
    const val DIVE_DIST_FRONT = -800f

    /** 纵深飞行后界（dist_eff 上限=4×D0）：拉远看全宇宙的极限 */
    const val DIVE_DIST_BACK = 6600f

    /** 贴脸/背后剔除界：深度低于此值的天体不绘制不命中（R231 前恒不触发） */
    const val DEPTH_CULL = 140f

    /** FOV 动态指数：dive 越深焦距越短（广角=飞入的速度感；只依赖 dive 不依赖 zoom） */
    const val FOV_EXP = 0.35f

    /** FOV 焦距系数钳位（0.78=深潜广角档 / 1.20=后拉微缩焦档） */
    const val FOV_SCALE_MIN = 0.78f
    const val FOV_SCALE_MAX = 1.20f

    /** FOV 基底下限（防 dive<−D0 时负底幂 NaN） */
    const val FOV_BASE_MIN = 300f

    /** 透视增强指数：标记尺寸=radius×persp^此值（远星明显更小/近星更大——纵深感视觉核心） */
    const val PERSP_EXP = 1.45f

    // ==================== R241 俯仰（pitch 轴） ====================

    /** 俯仰速率：一满屏上滑=[PITCH_PER_SCREEN_H] 度（180=半屏冲程把面前的星图「墙」
     *  仰到头顶棚顶；R237 dive 速率同款「真机手感首版拍板值」——挂账用户上手反馈微调） */
    const val PITCH_PER_SCREEN_H = 180f

    /** 俯仰运镜到位容差（度——[approach]/[focusArrived] 的 tPitch 判定） */
    const val PITCH_ARRIVE_EPS = 4f

    // ==================== 相机 ====================

    /**
     * 自由飞行相机状态：世界系相机位置 (x, y)，观察 −dist→+z（dist 恒正），
     * rollDeg 为屏幕滚转。不可变值对象，帧循环 copy 更新。
     *
     * R231 **纵深轴** [dive]：沿 +z 的前进位移（世界单位，正=向星域里飞）——
     * 相机从「dist 完全由 zoom 派生（ZOOM_MAX=10 时 dist≥738，永远在云团外）」
     * 扩展为可独立深入的有效距离 dist_eff = distOf(zoom) − dive（**可过零**=
     * 穿越云团中心飞到远带后方，用户实锤②「进不去纵深」的修法）。dive=0 时
     * 全部旧语义逐位不变（dolly/lens/表观倍率/质心锚定零回归）。
     */
    data class FlightCam(
        val x: Float,
        val y: Float,
        val zoom: Float,
        val rollDeg: Float,
        val dive: Float = 0f,
        /** R241 俯仰角（度，wrap 0..360）：0=平视（旧语义全部不变）；90=仰头看棚顶；
         *  −90≡270=低头看地面；连续旋转=星图球面化。俯仰只旋转视锥不动相机位置
         *  （pan/dive/zoom 语义解耦——[panBy]/[diveBy]/[zoomAt] 各管各的轴） */
        val pitchDeg: Float = 0f,
    ) {
        companion object {
            /** 初始相机（世界中心、初始倍率、无滚转）——取景完成后由 [flightFit] 覆写 x/y */
            val ORIGIN = FlightCam(500f, 500f, 1f, 0f)
        }
    }

    /** 相机推进量：zoom 越大 dist 越小（俯冲）；zoom≤0 防御性按 1 算 */
    fun distOf(zoom: Float): Float = D0 * (if (zoom > 0f) zoom else 1f).pow(-DOLLY_EXP)

    /** R231 有效相机距离：dolly 距离 − 纵深飞行前进量（可过零=飞过云团中心平面） */
    fun camDist(cam: FlightCam): Float = distOf(cam.zoom) - cam.dive

    /** R231 dive 钳位（只裁手势/运镜推进的结果）：dist_eff 进 [DIVE_DIST_FRONT, DIVE_DIST_BACK]。
     *  dive=0 时 distOf(zoom) 恒在界内（[738,4505]）→ 钳位无操作=零语义变化 */
    private fun clampDive(zoom: Float, dive: Float): Float {
        val distEff = (distOf(zoom) - dive).coerceIn(DIVE_DIST_FRONT, DIVE_DIST_BACK)
        return distOf(zoom) - distEff
    }

    /** R231 纵深飞行一步：[deltaWorld] 正=前进（飞入星域）/负=后退；钳位后应用 */
    fun diveBy(cam: FlightCam, deltaWorld: Float): FlightCam =
        cam.copy(dive = clampDive(cam.zoom, cam.dive + deltaWorld))

    /** R231 纵深飞行速率换算：屏幕像素 → 世界前进量（R241 起手势消费方改由
     *  [pitchPerPx] 驱动俯仰——dive 轴的驱动源只剩运镜 approach(tDive)；本函数
     *  保留为 dive 轴的速率口径锚，单测锚定不变） */
    fun divePerPx(screenH: Float): Float = FLIGHT_DIVE_PER_SCREEN_H * D0 / screenH.coerceAtLeast(1f)

    /**
     * R231 FOV 动态焦距系数：**只依赖 dive 不依赖 zoom**——捏合/纯缩放恒 1
     * （R206「表观倍率=zoom」与 R210 质心锚定零回归）；俯冲（dive>0）焦距缩短
     * =广角速度感，后拉（dive<0）微缩焦，钳 [FOV_SCALE_MIN, FOV_SCALE_MAX]。
     */
    fun fovScale(dive: Float): Float {
        val base = (D0 + dive).coerceAtLeast(FOV_BASE_MIN)
        return (D0 / base).pow(FOV_EXP).coerceIn(FOV_SCALE_MIN, FOV_SCALE_MAX)
    }

    /** R231 有效焦距：focalOf × FOV 系数（dive=0 恒等 focalOf——渲染/命中共用） */
    fun focalEff(fitScale: Float, cam: FlightCam): Float =
        focalOf(fitScale, cam.zoom) * fovScale(cam.dive)

    /** R231 透视增强的标记尺寸：radius×persp^PERSP_EXP（persp=1 恒等；渲染/命中
     *  同一函数——远星明显更小近星更大，且热区与绘制口径一致） */
    fun sizeOf(bodyR: Float, persp: Float): Float = bodyR * persp.pow(PERSP_EXP)

    /** R241 俯仰后的视轴深度（未钳）：世界点沿当前视线的深度分量——「相机前方/
     *  多远」类判定的**单一口径**（[depthAhead] 与渲染/命中侧的深度带读数全走这里）。
     *  pitch=0 时恒等 z+dist_eff（旧式）；pitch=90 时=相机正上方的深度语义 */
    fun depthOf(y: Float, z: Float, cam: FlightCam): Float {
        val pitch = Math.toRadians(cam.pitchDeg.toDouble())
        val ps = sin(pitch).toFloat()
        val pc = cos(pitch).toFloat()
        return (y - cam.y) * ps + (z + camDist(cam)) * pc
    }

    /** R241 俯仰版深度在前判定：视轴深度>DEPTH_CULL 才算前方（pitch=90 时头顶星
     *  （y>cam.y）在前方——旧 z 口径会错裁成背后；dive=0 且 pitch=0 时对全图恒真
     *  =零行为变化）。调用方（渲染/命中）必须传天体 y。 */
    fun depthAhead(y: Float, z: Float, cam: FlightCam): Boolean = depthOf(y, z, cam) > DEPTH_CULL

    /** 焦距倍率：lens 部分（表观倍率 = dolly × lens = zoom） */
    fun lensOf(zoom: Float): Float = (if (zoom > 0f) zoom else 1f).pow(LENS_EXP)

    /** 有效焦距（像素）：fitScale 为 zoom=1 时云团中心平面的世界→像素比 */
    fun focalOf(fitScale: Float, zoom: Float): Float = fitScale * D0 * lensOf(zoom)

    /**
     * 飞行投影（App 命中测试/渲染/特效共用唯一实现）：世界 (x,y,z) → 屏幕
     * (sx,sy) + 透视系数（[out] 长 ≥3，调用方持有复用，帧内零分配）。
     *  - **R241 俯仰旋量（绕相机 X 轴旋转视锥）**：相机系 (xc,yc,zc) 先旋
     *    （ycp=yc·cosθ−zc·sinθ；深度 d=yc·sinθ+zc·cosθ——θ=[FlightCam.pitchDeg]
     *    仰角，正=仰头）再投影：u=xc·k、v=−ycp·k。四向锚定：θ=0 逐位=旧式；
     *    θ=+90 头顶正上方点（zc≈0）落屏心（仰头看棚顶）；θ=−90 脚下正下方点
     *    落屏心（低头看地面）；θ=180 身后点落屏心（回望）。深度 d 同时驱动
     *    persp=[out][2] 与近平面钳位；
     *  - 天体在相机背后/贴脸（d<DEPTH_CULL 口径见 [depthAhead]）时本函数不负责
     *    剔除——调用方先判（clamp 到 NEAR_PLANE 只防透视爆炸，不是可见性语义）；
     *  - roll：屏幕坐标绕 (cx,cy) 旋转（双指旋转手势；与俯仰分属投影前/后两个
     *    刚体旋量，互不干扰）。
     */
    fun project(
        x: Float,
        y: Float,
        z: Float,
        cam: FlightCam,
        focal: Float,
        cx: Float,
        cy: Float,
        out: FloatArray,
    ): FloatArray {
        val yc = y - cam.y
        val zc = z + camDist(cam)
        // R241 俯仰旋量：pitch=0 时 pc=1/ps=0 → ycp=yc、d=zc（逐位=旧式，零回归）
        val pitch = Math.toRadians(cam.pitchDeg.toDouble())
        val pc = cos(pitch).toFloat()
        val ps = sin(pitch).toFloat()
        val ycp = yc * pc - zc * ps
        val d = (yc * ps + zc * pc).coerceAtLeast(NEAR_PLANE)
        val k = focal / d
        val u = (x - cam.x) * k
        val v = -ycp * k
        val roll = Math.toRadians(cam.rollDeg.toDouble())
        val cr = cos(roll).toFloat()
        val sr = sin(roll).toFloat()
        out[0] = cx + u * cr - v * sr
        out[1] = cy + u * sr + v * cr
        out[2] = D0 / d
        return out
    }

    /**
     * 命中测试逆变换：把（带 roll 的）屏幕点还原到投影平面坐标（u,v）——
     * 与 [project] 的 roll 互逆，点星判定在 (u,v) 空间做距离。
     */
    fun unroll(px: Float, py: Float, cam: FlightCam, cx: Float, cy: Float, out: FloatArray): FloatArray {
        val dx = px - cx
        val dy = py - cy
        val roll = Math.toRadians(-cam.rollDeg.toDouble())
        val cr = cos(roll).toFloat()
        val sr = sin(roll).toFloat()
        out[0] = cx + dx * cr - dy * sr
        out[1] = cy + dx * sr + dy * cr
        return out
    }

    /**
     * 拖拽平移：Δ屏幕像素 → 相机世界位移。中深度（z=0）星跟手精确；近星滑得
     * 比手指快、远星慢——**视差即深空感**。世界/像素换算 wpp = dist_eff/focal_eff
     * （z=0 平面口径；R231：深潜态 dist_eff 下限 100 保方向稳定）。
     * R241 已知性质（pan=「星图平面滑动」的世界 XY 语义固有几何，任务书「pan 修复
     * 方向后不变」口径内）：相机俯仰 θ≠0 时纵向拖拽的屏效按 cosθ 缩放（±90° 纵向
     * 零屏效、|θ|>90° 纵向反向）——俯仰态用俯仰手势回平视线后再平移（挂账用户
     * 上手反馈，如需任意俯仰下屏向跟手，后续轮次改屏空间平移）。
     */
    fun panBy(cam: FlightCam, dxPx: Float, dyPx: Float, fitScale: Float): FlightCam {
        val wpp = camDist(cam).coerceAtLeast(100f) / focalEff(fitScale, cam)
        return cam.copy(x = cam.x - dxPx * wpp, y = cam.y + dyPx * wpp)
    }

    /** R241 俯仰一步：[deltaDeg] 正=仰头（墙面→棚顶方向）/负=低头（→地面）；
     *  wrap 到 0..360（[rollBy] 同款——长手势浮点不累积）。x/y/zoom/roll/dive 全保持 */
    fun pitchBy(cam: FlightCam, deltaDeg: Float): FlightCam =
        cam.copy(pitchDeg = ((cam.pitchDeg + deltaDeg) % 360f + 360f) % 360f)

    /** R241 俯仰速率换算：屏幕像素 → 度（一满屏=[PITCH_PER_SCREEN_H]，调用方：
     *  pitchBy(cam, −dyPx × 本值)——上滑 dy<0=pitch 增加即仰头） */
    fun pitchPerPx(screenH: Float): Float = PITCH_PER_SCREEN_H / screenH.coerceAtLeast(1f)

    /** 双指捏合缩放（clamp 到 [ZOOM_MIN, ZOOM_MAX]）——**锚定光轴**（z=0 平面上屏心
     *  处的世界点不动；按钮缩放走这条，屏心 pivot 是设计语义） */
    fun zoomBy(cam: FlightCam, factor: Float): FlightCam =
        cam.copy(zoom = (cam.zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX))

    /**
     * R210 捏合缩放（质心锚定版）：dolly+lens 复合缩放**锚定屏幕 pivot（双指质心）**
     * ——z=0 参考平面上 pivot 处的世界星缩放前后屏位不动（指尖的星钉在指尖）。
     *  - pivot 先按当前 roll 反旋到无滚转平面系（与 [unroll] 同式）：滚转态下锚照样
     *    精确（求解在 (u,v) 平面系做，roll 是事后的绕屏心等距变换）；
     *  - 参照平面 z=0 与 [panBy] 同一「中深度跟手」口径（近星越锚胀开/远星缩拢——
     *    视差深空感保留，不是缺陷）；
     *  - **R241 俯仰态精确锚定**：pivot 的相机系射线 (ux,−uy,focal) 反俯仰旋量到
     *    世界系后与 z=0 参考面求交得 W；重解按新焦距/新距离解回 cam.y（解算式与
     *    [project] 严格互逆）。分母 (uy·sinθ+focal·cosθ)≤0=掠射/背向（大俯仰角下
     *    屏角 pivot 的射线与参考面近于平行）——锚无解退化为纯缩放（沿 R231 深潜态
     *    回退先例）。pitch=0 时 t=1/k0、wy=cam.y−uy/k0、y=wy+uy/k1——**与旧实现
     *    逐位同式**（单测锚定）；
     *  - 钳位按实际新 zoom 解算（撞 [ZOOM_MAX]/[ZOOM_MIN] 锚仍成立）；factor=1 恒等；
     *    rollDeg/dive/pitchDeg 原样保留（**缩放绝不引入滚转/纵深/俯仰分量**——纯
     *    dolly/lens）。
     */
    fun zoomAt(
        cam: FlightCam,
        factor: Float,
        pivotX: Float,
        pivotY: Float,
        fitScale: Float,
        cx: Float,
        cy: Float,
    ): FlightCam {
        val newZoom = (cam.zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
        if (newZoom == cam.zoom) return cam
        if (camDist(cam) < 100f) return cam.copy(zoom = newZoom) // R231 深潜态：锚退化
        // pivot 反旋到无滚转平面系（roll 绕屏心）
        val dx = pivotX - cx
        val dy = pivotY - cy
        val roll = Math.toRadians(-cam.rollDeg.toDouble())
        val cr = cos(roll).toFloat()
        val sr = sin(roll).toFloat()
        val ux = dx * cr - dy * sr
        val uy = dx * sr + dy * cr
        // R241 俯仰旋量（与 project 同式）
        val pitch = Math.toRadians(cam.pitchDeg.toDouble())
        val pc = cos(pitch).toFloat()
        val ps = sin(pitch).toFloat()
        val focal0 = focalEff(fitScale, cam)
        val dist0 = camDist(cam)
        // z=0 参考平面反投影：射线 (ux,−uy,focal0) 反旋到世界系，与平面 zc=dist0 求交
        val denom0 = uy * ps + focal0 * pc
        if (denom0 <= 1e-3f * focal0) return cam.copy(zoom = newZoom) // 掠射/背向：锚退化
        val t = dist0 / denom0
        val wx = cam.x + t * ux
        val wy = cam.y + t * (focal0 * ps - uy * pc)
        // 缩放后重解相机：W 仍投影到 pivot（yc' 解算与投影公式严格互逆；pitch=0 时
        // 退化为旧式 y = wy + uy/k1、x = wx − ux/k1）
        val focal1 = focalOf(fitScale, newZoom) * fovScale(cam.dive)
        val dist1 = distOf(newZoom) - cam.dive
        val denom1 = uy * ps + focal1 * pc
        if (denom1 <= 1e-3f * focal1) return cam.copy(zoom = newZoom) // 同上
        val yc1 = dist1 * (focal1 * ps - uy * pc) / denom1
        val zcp1 = yc1 * ps + dist1 * pc
        return FlightCam(
            x = wx - ux * zcp1 / focal1,
            y = wy - yc1,
            zoom = newZoom,
            rollDeg = cam.rollDeg,
            dive = cam.dive,
            pitchDeg = cam.pitchDeg,
        )
    }

    /** 双指旋转滚转（wrap 到 0..360；正度=星野顺时针跟手拧） */
    fun rollBy(cam: FlightCam, degrees: Float): FlightCam =
        cam.copy(rollDeg = ((cam.rollDeg + degrees) % 360f + 360f) % 360f)

    /**
     * R210 手势一步：Compose `detectTransformGestures` 回调四参 → 相机一步的**唯一映射**
     * （纯函数——屏幕层只做薄适配，量纲换算不许在手写接线里二次出现）。
     *
     * **量纲铁律（foundation 1.7.2 字节码实锤）**：pan=质心像素增量（右下正）；
     * zoom=逐事件倍率比；**rotation=「度」不是弧度**（calculateRotation ×180/π）——
     * R206 把度当弧度再 Math.toDegrees 一遍=×57.3 放大灌进滚转，是走查双实锤
     * （捏合绕屏心狂转+残留 roll 反转拖拽参照系）的共同根干，本函数为唯一换算口。
     *
     * 语义：平移跟手（[panBy]）→ 缩放锚定双指质心（[zoomAt]）→ 滚转度直传跟手
     * （[rollBy] 正号）。顺序 pan→zoomAt(新质心) 使「质心处的中深度星全程钉在指尖」。
     */
    fun gestureStep(
        cam: FlightCam,
        panPx: Float,
        panPy: Float,
        zoomFactor: Float,
        rotationDeg: Float,
        pivotX: Float,
        pivotY: Float,
        fitScale: Float,
        cx: Float,
        cy: Float,
    ): FlightCam {
        var c = panBy(cam, panPx, panPy, fitScale)
        if (zoomFactor != 1f) c = zoomAt(c, zoomFactor, pivotX, pivotY, fitScale, cx, cy)
        if (rotationDeg != 0f) c = rollBy(c, rotationDeg)
        return c
    }

    /**
     * R210 拖拽事件速度 → 惯性初速（世界单位/秒，写 [out] 长 ≥2）：与 [drift] 的
     * cam.x+vx·dt 方向严格一致（松手后星野沿拖拽方向滑行）。60Hz 事件口径（Compose
     * 手势逐帧一拍；高刷屏略偏快——已知简化，非方向缺陷）。R231：wpp 与 [panBy]
     * 同口径（有效焦距/有效距离）。
     */
    fun flingVelFromPan(cam: FlightCam, dxPx: Float, dyPx: Float, fitScale: Float, out: FloatArray) {
        val wpp = camDist(cam).coerceAtLeast(100f) / focalEff(fitScale, cam)
        out[0] = -dxPx * wpp * 60f
        out[1] = dyPx * wpp * 60f
    }

    /** 惯性速度衰减：dtMs 毫秒的等效帧数衰减（0.94^(dt/16.7)） */
    fun decayVel(v: Float, dtMs: Float): Float =
        v * FLING_DECAY_PER_FRAME.pow(dtMs / 16.7f)

    /**
     * 惯性一步：把速度应用到相机并衰减。dtSec 秒；返回新相机（速度衰减由调用方
     * 回写持有——相机是值对象、速度是引擎侧可变量，职责分开）。
     */
    fun drift(cam: FlightCam, vx: Float, vy: Float, dtSec: Float): FlightCam =
        cam.copy(x = cam.x + vx * dtSec, y = cam.y + vy * dtSec)

    /**
     * 相机推进（点星 focus 飞行）一步：指数趋近目标 (tx,ty,tZoom[,tDive,tPitch])。
     * [k] = 1−exp(−dt/τ)（τ≈0.18s 的平滑系数，调用方预算好传入）。到位判定
     * 由 [focusArrived] 做。R231：[tDive]=null 时纵深轴保持不动（单指点星
     * 推进原语义）；双击运镜传显式 tDive=纵深目标（每步钳位保 dist_eff 界内）。
     * R241：[tPitch]=null 时俯仰保持不动（旧口径全兼容）；非空走**最短角差插值**
     * （350°→0° 走 −10° 方向不绕远）——运镜飞行时视线自动回平的机制件。
     */
    fun approach(
        cam: FlightCam,
        tx: Float,
        ty: Float,
        tZoom: Float,
        k: Float,
        tDive: Float? = null,
        tPitch: Float? = null,
    ): FlightCam {
        val newZoom = cam.zoom + (tZoom - cam.zoom) * k
        val rawDive = if (tDive == null) cam.dive else cam.dive + (tDive - cam.dive) * k
        val rawPitch = if (tPitch == null) cam.pitchDeg
        else cam.pitchDeg + shortestAngleDelta(cam.pitchDeg, tPitch) * k
        return FlightCam(
            x = cam.x + (tx - cam.x) * k,
            y = cam.y + (ty - cam.y) * k,
            zoom = newZoom,
            rollDeg = cam.rollDeg,
            dive = clampDive(newZoom, rawDive),
            pitchDeg = ((rawPitch % 360f + 360f) % 360f),
        )
    }

    /** 最短角差（度，结果 −180..180）：from→to 的最短旋转方向（俯仰运镜插值用） */
    private fun shortestAngleDelta(fromDeg: Float, toDeg: Float): Float {
        var d = (toDeg - fromDeg) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * focus 飞行到位判定：三轴都进容差（世界 8 单位 / 倍率 2%）；R231 传 [tDive]
     * 时纵深轴也验（容差 30 单位）——null=不检查（旧调用口径不变）。R241 传
     * [tPitch] 时俯仰轴也验（容差 [PITCH_ARRIVE_EPS]）——null=不检查。
     */
    fun focusArrived(
        cam: FlightCam,
        tx: Float,
        ty: Float,
        tZoom: Float,
        tDive: Float? = null,
        tPitch: Float? = null,
    ): Boolean {
        if (tDive != null && abs(cam.dive - tDive) > 30f) return false
        if (tPitch != null && abs(shortestAngleDelta(cam.pitchDeg, tPitch)) > PITCH_ARRIVE_EPS) return false
        return abs(cam.x - tx) < 8f && abs(cam.y - ty) < 8f && abs(cam.zoom - tZoom) < tZoom * 0.02f
    }

    /**
     * 深度雾系数（约 0..1）：persp 越小（离相机越远）越淡。与旧轨道投影同式
     * （away = D0(1/persp − 1)，fog = 1/(1+away/FOG)），拉远/俯冲时雾随 dist 变。
     */
    fun tasteFog(persp: Float): Float {
        val away = D0 / persp - D0
        return 1f / (1f + max(0f, away) / FOG)
    }

    // ==================== 创世纪（星生序） ====================

    /**
     * 创世纪时间量程：星图中「频道首见时间」的 min/max（毫秒）。max ≤ min =
     * 无证据（无索引且无观看账），创世纪模式不可用（诚实隐藏入口）。
     */
    data class GenesisSpan(val minMs: Long, val maxMs: Long) {
        val valid: Boolean get() = maxMs > minMs
        val rangeMs: Long get() = max(0L, maxMs - minMs)
    }

    /** 从星的首见集合求量程（0/负值=无证据，不计入） */
    fun genesisSpan(firstSeenMs: List<Long>): GenesisSpan {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (t in firstSeenMs) {
            if (t <= 0L) continue
            if (t < lo) lo = t
            if (t > hi) hi = t
        }
        return if (lo == Long.MAX_VALUE || hi < lo) GenesisSpan(0L, 0L) else GenesisSpan(lo, hi)
    }

    /** 光标（0..1）→ 量程毫秒 */
    fun cursorMs(span: GenesisSpan, t01: Float): Long =
        span.minMs + (span.rangeMs * t01.coerceIn(0f, 1f)).toLong()

    /**
     * 一颗星的诞生时刻（毫秒）：首见有证据=其本身（量程内 clamp）；**无证据
     * （0）=量程末端**——没有证据就不冒充古老，宇宙凝固到最后一刻才诞生。
     */
    fun bornAtMs(firstSeenMs: Long, span: GenesisSpan): Long =
        if (firstSeenMs > 0L) firstSeenMs.coerceIn(span.minMs, span.maxMs) else span.maxMs

    /** 光标处该星是否已诞生（t01=1 时全体可见——含无证据星） */
    fun bornVisible(firstSeenMs: Long, span: GenesisSpan, t01: Float): Boolean =
        cursorMs(span, t01) >= bornAtMs(firstSeenMs, span)

    /**
     * 诞生「光龄」毫秒：光标越过诞生时刻的时长（≤0=未诞生/刚诞生）。渲染端用
     * 它做凝聚缓动（半径/亮度从 0 淡入）与诞生环光。
     */
    fun birthAgeMs(firstSeenMs: Long, span: GenesisSpan, t01: Float): Long =
        cursorMs(span, t01) - bornAtMs(firstSeenMs, span)

    // ==================== R314 宇宙延时摄影（光年口径读数 + 倍速档） ====================

    /**
     * R314 延时摄影倍速档（×1/×4/×16 循环——「模式加速光阴」零新增手势，R268
     *  归口纪律不破）：帧循环推进步长 = dtSec × 档速 / GENESIS 全程秒（UI 侧常数）。
     */
    val TIMELAPSE_SPEED_STEPS = floatArrayOf(1f, 4f, 16f)

    /** 一天毫秒数（光日/天前换算的单出处） */
    private const val MS_PER_DAY = 86_400_000L

    /** 回归年天数（光年换算口径：1 光年=光走一回归年的距离 ≈9.4607×10^12 km） */
    private const val DAYS_PER_YEAR = 365.25f

    /**
     * R314 光年口径主读数（**距离=时间**：光标回望时长 [deltaMs] 内光传播的距离，
     *  以「光行 X」的单位名呈现——看得越远=越古老，宇宙化替代日历日期）：
     *  <1 分钟=「N 光秒」（1 光秒=299,792.458 km）/ <1 小时=「N 光分」/
     *  <1 天=「N 光时」/ <一回归年=「N 光日」/ 否则「X.X 光年」（一位小数）。
     *  整数档四舍五入，撞到单位上限自动升档（23.6 光时→「1 光日」不产「24 光时」）。
     *  deltaMs ≤0（光标=现在）返回「0 光秒」——UI 侧自行叠加「现在」文案。
     */
    fun lightTravelOf(deltaMs: Long): String {
        if (deltaMs <= 0L) return "0 光秒"
        val sec = deltaMs / 1000.0
        if (deltaMs < 60_000L) return "${(sec + 0.5).toLong().coerceAtLeast(1L)} 光秒"
        val min = (sec / 60.0 + 0.5).toLong()
        if (min < 60L) return "$min 光分"
        val hour = (min / 60.0 + 0.5).toLong()
        if (hour < 24L) return "$hour 光时"
        val day = (deltaMs / MS_PER_DAY.toDouble() + 0.5).toLong()
        if (day < DAYS_PER_YEAR) return "$day 光日"
        val year10 = (day / DAYS_PER_YEAR * 10 + 0.5).toLong()
        return "${year10 / 10}.${year10 % 10} 光年"
    }

    /**
     * R314 回望副读数（人间时长锚——主读数光年口径的陪衬）：<1 分钟=「此刻」/
     *  <1 小时=「N 分钟前」/ <1 天=「N 小时前」/ <一回归年=「N 天前」/否则
     *  「X.X 年前」。与 [lightTravelOf] 同一组分档常数（单出处零私抄）。
     */
    fun lookbackTextOf(deltaMs: Long): String {
        if (deltaMs < 60_000L) return "此刻"
        val min = (deltaMs / 60_000.0 + 0.5).toLong()
        if (min < 60L) return "$min 分钟前"
        val hour = (min / 60.0 + 0.5).toLong()
        if (hour < 24L) return "$hour 小时前"
        val day = (deltaMs / MS_PER_DAY.toDouble() + 0.5).toLong()
        if (day < DAYS_PER_YEAR) return "$day 天前"
        val year10 = (day / DAYS_PER_YEAR * 10 + 0.5).toLong()
        return "${year10 / 10}.${year10 % 10} 年前"
    }

    // ==================== XY 鲁棒取景框（R152c 口径的平面版） ====================

    /**
     * 取景用的轻量星（core 口径：UI 的 StarNode 投影而来；[TasteFlightRenderer]
     * 的绘制星另含颜色/名签等展示字段——两结构单方向映射，不反向依赖）。
     */
    data class FitStar(val x: Float, val y: Float, val z: Float, val radius: Float, val hasComp: Boolean, val labeled: Boolean)

    /**
     * XY 平面取景框（[minX, minY, w, h]）：飞行投影把云团压到 XY 平面（z 只影响
     * 透视 ±10%），取景按 x/y 坐标做 R152c 鲁棒口径——只统计 hasComp 的口味
     * 云团 p2..p98 分位 + 名签星强制并入；证据星不足/退化回退全量盒。
     * 半径余量 1.35 倍（投影平面内的标记占位）。
     */
    fun flightBoundsXy(stars: List<FitStar>): FloatArray {
        val xs = ArrayList<Float>(stars.size)
        val ys = ArrayList<Float>(stars.size)
        var lxMin = Float.MAX_VALUE
        var lxMax = -Float.MAX_VALUE
        var lyMin = Float.MAX_VALUE
        var lyMax = -Float.MAX_VALUE
        for (s in stars) {
            if (s.hasComp) {
                xs.add(s.x)
                ys.add(s.y)
            }
            if (s.labeled) {
                val r = s.radius * 1.35f
                lxMin = min(lxMin, s.x - r)
                lxMax = max(lxMax, s.x + r)
                lyMin = min(lyMin, s.y - r)
                lyMax = max(lyMax, s.y + r)
            }
        }
        if (xs.size < 3) return boundsFullXy(stars)
        xs.sort()
        ys.sort()
        val m = xs.size
        val lo = (m * 0.02f).toInt().coerceIn(0, m - 1)
        val hi = (m * 0.98f).toInt().coerceIn(0, m - 1)
        var minX = xs[lo]
        var maxX = xs[hi]
        var minY = ys[lo]
        var maxY = ys[hi]
        if (lxMin <= lxMax) { // 名签星强制并入（头部频道必须首屏可见）
            minX = min(minX, lxMin)
            maxX = max(maxX, lxMax)
            minY = min(minY, lyMin)
            maxY = max(maxY, lyMax)
        }
        if (minX > maxX || minY > maxY || (maxX - minX) < 1f || (maxY - minY) < 1f) {
            return boundsFullXy(stars)
        }
        return floatArrayOf(minX, minY, (maxX - minX).coerceAtLeast(80f), (maxY - minY).coerceAtLeast(80f))
    }

    /** 全量 XY 包围盒（缩小下限反推：拉远看含未知极/离群点的全貌） */
    fun boundsFullXy(stars: List<FitStar>): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in stars) {
            val r = s.radius * 1.35f
            minX = min(minX, s.x - r)
            maxX = max(maxX, s.x + r)
            minY = min(minY, s.y - r)
            maxY = max(maxY, s.y + r)
        }
        if (minX > maxX || minY > maxY) return floatArrayOf(0f, 0f, 1000f, 1000f)
        return floatArrayOf(minX, minY, (maxX - minX).coerceAtLeast(80f), (maxY - minY).coerceAtLeast(80f))
    }

    // ==================== 粒子引擎（帧驱动 + 对象池） ====================

    /**
     * 轻量特效引擎（**非快照状态**：帧循环 step、渲染器直读；确定性哈希撒种
     * 零 kotlin.random——同数据同轨迹，可单测可复现）：
     *  - 爆散（屏幕空间）：点星反馈的粒子环，swap-remove 池；
     *  - 流光（世界空间）：沿互链边流动的光点（t 参数 0..1 推进回绕）；
     *  - 流星（世界空间）：今晚观看串（星→星插值 + 世界坐标尾迹环）；
     *  - 彗星（屏幕归一化空间）：爬虫脉冲，带尾迹环；
     *  - 星爆闪（chatId → TTL）：新频道诞生/爬到新频道的在图星提亮；
     *  - R320 引力波波前账（chatId → 起始/幅度/寿命，≤2）：演出级事件
     *    （导演器超新星/宣判 GRAVITY_WAVE 档）的时空涟漪——渲染期按波源星
     *    当帧屏位求波心，波前扫过全场星点位移回弹；
     *  - 呼吸时钟 timeSec：星晕脉动/星云缓转/灯塔扫掠的统一相位源。
     */
    class FxEngine(
        burstCap: Int = 96,
        flowCap: Int = 96,
        trailCap: Int = 20,
    ) {
        // ---- 爆散（屏幕像素空间；存活数 count，swap-remove 回收） ----
        val burstCap = burstCap
        internal val bx = FloatArray(burstCap)
        internal val by = FloatArray(burstCap)
        internal val bvx = FloatArray(burstCap)
        internal val bvy = FloatArray(burstCap)
        internal val blife = FloatArray(burstCap) // 剩余秒
        internal val bmax = FloatArray(burstCap)  // 总寿命
        internal val bsize = FloatArray(burstCap)
        var burstCount = 0
            private set

        // ---- 流光（世界空间沿边） ----
        val flowCap = flowCap
        internal val flowEdge = IntArray(flowCap)
        internal val flowT = FloatArray(flowCap)
        internal val flowSpeed = FloatArray(flowCap)
        var flowActive = 0
            private set
        private var edgeCount = 0

        // ---- 流星（世界空间路线） ----
        internal var routeX = FloatArray(0)
        internal var routeY = FloatArray(0)
        internal var routeZ = FloatArray(0)
        var routeSize = 0
            private set
        var meteorSeg = 0          // 当前段（route 点 i → i+1）
            private set
        var meteorT = 0f           // 段内参数 0..1
            private set
        var meteorPause = 0f       // 段间停泊秒数（流星到站后悬停再出发）
            private set
        internal val trailX = FloatArray(trailCap) // 世界坐标尾迹环
        internal val trailY = FloatArray(trailCap)
        internal val trailZ = FloatArray(trailCap)
        val trailCap = trailCap
        var trailHead = 0
            private set
        var trailSize = 0
            private set
        var meteorAlive = false     // 路线 ≥2 点才有流星（诚实空态：今天没看=没有星轨）
            private set

        // ---- 彗星（屏幕归一化 0..1 空间） ----
        var cometX = 0f
            private set
        var cometY = 0f
            private set
        var cometVx = 0f
            private set
        var cometVy = 0f
            private set
        var cometLife = 0f // 剩余秒；≤0 = 无彗星
            private set
        internal val cometTrailX = FloatArray(trailCap)
        internal val cometTrailY = FloatArray(trailCap)
        var cometTrailSize = 0
            private set

        // ---- 星爆闪（chatId → 剩余秒） ----
        val starFlashes = HashMap<Long, Float>()

        // ---- R323 超新星显式账（chatId → 剩余秒——种别显式化：starFlashes 的 TTL
        //      7.0（超新星）与 9.0（引力波宣判档）衰减区间重叠，种别不可由 TTL
        //      反推；循环联动口（EmissionNebulaFx.cyclePulse01 空间共在门）需要
        //      「活跃超新星」的准确身份。feed 账=executeDirectiveR282 SUPERNOVA 档；
        //      衰减与 starFlashes 同式同拍（零额外时序源）。渲染端只读（竞态良性
        //      ——starFlashes 同口径） ----
        val supernovaFlashes = HashMap<Long, Float>()

        /**
         * 超新星演出入账（[UniverseDirector.EventKind.SUPERNOVA] 档专属——
         * [flashStar] 同族 API 只加不改；TTL=Directive.flashTtlSec（7.0s 演出窗））。
         * 消费方=发射星云星胚点脉冲（「超新星遗迹旁新星在孕育」空间共在门）。
         */
        fun supernovaFlash(chatId: Long, ttlSec: Float) {
            if (chatId != 0L && ttlSec > 0f) supernovaFlashes[chatId] = ttlSec
        }

        // ---- R320 引力波波前账（事件级全场涟漪——≤GravityWaveFx.WAVE_CAP 个活跃；
        //      帧驱动非快照状态（step 推进到期回收），渲染器双端（Canvas/GL）直读
        //      同一账=双端同波不漂移）----
        /** 波源星 chatId（事件星=波心锚——渲染期按它投影当帧屏位） */
        internal val waveChatIds = LongArray(GravityWaveFx.WAVE_CAP)

        /** 波前起始时刻（秒——engine.timeSec 时基） */
        internal val waveStartSec = FloatArray(GravityWaveFx.WAVE_CAP)

        /** 波前幅度档（px——GravityWaveFx.ampPxOfFlashTtl 出值：涟漪 4.0/宣判 6.0） */
        internal val waveAmpPx = FloatArray(GravityWaveFx.WAVE_CAP)

        /** 波前寿命（秒——到期 step 回收） */
        internal val waveLifeSec = FloatArray(GravityWaveFx.WAVE_CAP)

        /** 活跃波前数（≤2——节奏闸管并发） */
        var waveCount = 0
            private set

        /**
         * 波前入账（**渲染无关**——本引擎不持屏幕位；波心=渲染期按 [waveChatIds]
         * 投影事件星当帧屏位求值）：同星重波=重置该槽（宣判重演/跃迁叠加取新档）；
         * 池满=最老波让位（一次性脉冲——最老的 closest 到消散最不心疼）。
         */
        fun spawnGravityWave(chatId: Long, ampPx: Float, lifeSec: Float) {
            if (chatId == 0L || ampPx <= 0f) return
            for (i in 0 until waveCount) {
                if (waveChatIds[i] == chatId) {
                    waveStartSec[i] = timeSec
                    waveAmpPx[i] = ampPx
                    waveLifeSec[i] = lifeSec
                    return
                }
            }
            var slot = waveCount
            if (slot >= GravityWaveFx.WAVE_CAP) {
                // 池满：挤掉最老（startSec 最小=龄最大）的槽
                var oldest = 0
                for (i in 1 until waveCount) {
                    if (waveStartSec[i] < waveStartSec[oldest]) oldest = i
                }
                slot = oldest
            } else {
                waveCount = slot + 1
            }
            waveChatIds[slot] = chatId
            waveStartSec[slot] = timeSec
            waveAmpPx[slot] = ampPx
            waveLifeSec[slot] = lifeSec
        }

        /** 呼吸/星云/灯塔统一时钟（秒） */
        var timeSec = 0f
            private set

        /**
         * R282 流星节奏档（宇宙导演器输出——「用户观看=流星轨迹」的密度统管）：
         * 1f=原速（默认=旧行为零变化）；事件演出并发期 0.75/0.5 减速让戏、虫洞
         * transit 期 0 暂停。只作用于流星段推进，尾迹环/路线语义不动。
         */
        var meteorPaceScale = 1f

        /** 确定性 0..1 哈希（同参数同值——撒种即无状态） */
        internal fun h01(a: Int, b: Int): Float =
            (((a * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF).toFloat() / 16777216f

        // ---------------- 爆散 ----------------

        /**
         * 点星爆散：在屏幕 (x,y) 撒 [n] 粒径向粒子（速度 speedPx/sec，确定性
         * 种子 [seed]——同一次点击同轨迹）。池满时最老的一半让位（爆散是短命
         * 瞬态，挤掉最老的最不心疼）。
         */
        fun burst(x: Float, y: Float, n: Int, speedPx: Float, seed: Int, lifeSec: Float = 0.55f, sizePx: Float = 2.6f) {
            var spawned = 0
            for (i in 0 until n) {
                if (burstCount >= burstCap) {
                    // 池满：淘汰已活过半程的最老粒子（找不到就覆盖 0 号）
                    var victim = -1
                    var bestProgress = -1f
                    for (j in 0 until burstCount) {
                        val p = 1f - blife[j] / bmax[j]
                        if (p > bestProgress) { bestProgress = p; victim = j }
                    }
                    if (victim < 0) victim = 0
                    if (bestProgress < 0.5f && spawned > 0) break // 都还年轻且已撒过：宁缺毋滥
                    swapRemoveBurst(victim)
                }
                if (burstCount >= burstCap) break
                val j = burstCount
                val ang = h01(seed, i * 31 + 7) * 6.2831853f
                val spd = speedPx * (0.35f + 0.65f * h01(seed, i * 31 + 13))
                bx[j] = x
                by[j] = y
                bvx[j] = cos(ang) * spd
                bvy[j] = sin(ang) * spd
                blife[j] = lifeSec * (0.7f + 0.6f * h01(seed, i * 31 + 19))
                bmax[j] = blife[j]
                bsize[j] = sizePx * (0.6f + 0.8f * h01(seed, i * 31 + 23))
                burstCount++
                spawned++
            }
        }

        private fun swapRemoveBurst(i: Int) {
            val last = burstCount - 1
            if (i < last) {
                bx[i] = bx[last]; by[i] = by[last]; bvx[i] = bvx[last]; bvy[i] = bvy[last]
                blife[i] = blife[last]; bmax[i] = bmax[last]; bsize[i] = bsize[last]
            }
            if (last >= 0) burstCount = last
        }

        /**
         * R385 新星起飞聚尘（design-nebula §3.3-1「新星诞生演出起飞点=所在复合体
         * ——『星云的尘埃聚成了它』」的演出第二层）：云尘自复合体屏域环带（半径
         * [ringPx]×哈希 0.55..1.0）向星位 (x,y) 汇聚——**向心速度=环距/寿命**
         * （粒子抵达星位时恰好燃尽=「聚成即逝」的字面演出）。与 [burst] 共用爆散
         * 账（同一池同一渲染同一淘汰策略——零新渲染面零新账族），差异仅初位（环带
         * 非心）与速度向（向心非离心）。确定性种子（同演出同轨迹——账本回放同径）。
         * 零新运动源（速度恒定向心=渲染期视觉层，零 N 体写入——保形红线）。
         */
        fun novaGatherR385(
            x: Float,
            y: Float,
            ringPx: Float,
            n: Int,
            seed: Int,
            lifeSec: Float = 0.9f,
            sizePx: Float = 2.2f,
        ) {
            if (ringPx <= 1f || n <= 0) return
            var spawned = 0
            for (i in 0 until n) {
                if (burstCount >= burstCap) {
                    // 池满：与 burst 同策——淘汰已活过半程的最老粒子（找不到就覆盖 0 号）
                    var victim = -1
                    var bestProgress = -1f
                    for (j in 0 until burstCount) {
                        val p = 1f - blife[j] / bmax[j]
                        if (p > bestProgress) { bestProgress = p; victim = j }
                    }
                    if (victim < 0) victim = 0
                    if (bestProgress < 0.5f && spawned > 0) break
                    swapRemoveBurst(victim)
                }
                if (burstCount >= burstCap) break
                val j = burstCount
                val ang = h01(seed, i * 31 + 7) * 6.2831853f
                val rr = ringPx * (0.55f + 0.45f * h01(seed, i * 31 + 13))
                val life = lifeSec * (0.8f + 0.4f * h01(seed, i * 31 + 19))
                bx[j] = x + cos(ang) * rr
                by[j] = y + sin(ang) * rr
                val spd = (rr / life) * (0.85f + 0.3f * h01(seed, i * 31 + 23))
                bvx[j] = -cos(ang) * spd
                bvy[j] = -sin(ang) * spd
                blife[j] = life
                bmax[j] = life
                bsize[j] = sizePx * (0.6f + 0.8f * h01(seed, i * 31 + 29))
                burstCount++
                spawned++
            }
        }

        // ---------------- 流光 ----------------

        /**
         * 装配流光粒子表（互链边表的**索引**引用——渲染器持有同序边坐标表，
         * 按边权（alpha）加权分配 [flowCap] 个流动粒子：重边流光更密）。
         * 空边表=清空。位置由渲染端按 (flowEdge[k], flowT[k]) 插值投影。
         */
        fun setLinks(alpha: FloatArray, count: Int) {
            edgeCount = count.coerceAtLeast(0)
            if (edgeCount == 0) {
                flowActive = 0
                return
            }
            // 权重前缀和（alpha 加权）→ 等距采样落段：重边分到更多流光
            var total = 0f
            for (i in 0 until edgeCount) total += (alpha[i].coerceIn(0f, 1f) + 0.08f)
            var e = 0
            var acc = 0f
            val n = min(flowCap, edgeCount * 2)
            for (k in 0 until n) {
                val target = total * (k + 0.5f) / n
                while (e < edgeCount - 1 && acc + (alpha[e].coerceIn(0f, 1f) + 0.08f) < target) {
                    acc += alpha[e].coerceIn(0f, 1f) + 0.08f
                    e++
                }
                flowEdge[k] = e
                flowT[k] = h01(e, k)
                flowSpeed[k] = 0.12f + 0.10f * h01(e, k * 7 + 3)
            }
            flowActive = n
        }

        // ---------------- 流星（今晚星轨） ----------------

        /** 装配今晚路线（世界坐标点列，时间序）：<2 点=无流星（诚实空态） */
        fun setRoute(wx: FloatArray, wy: FloatArray, wz: FloatArray, count: Int) {
            routeSize = count
            if (count >= 2 && wx.size >= count) {
                if (routeX.size < count) {
                    routeX = wx.copyOf(count); routeY = wy.copyOf(count); routeZ = wz.copyOf(count)
                } else {
                    System.arraycopy(wx, 0, routeX, 0, count)
                    System.arraycopy(wy, 0, routeY, 0, count)
                    System.arraycopy(wz, 0, routeZ, 0, count)
                }
                meteorSeg = 0
                meteorT = 0f
                meteorPause = 0.8f
                meteorAlive = true
                trailSize = 0
                trailHead = 0
            } else {
                routeSize = 0
                meteorAlive = false
                trailSize = 0
            }
        }

        /** 流星当前世界坐标（写进 [out] 长 ≥3；死了不保证语义——用 [meteorAlive] 判） */
        fun meteorPos(out: FloatArray) {
            val i = meteorSeg.coerceIn(0, routeSize - 2)
            val t = meteorT
            out[0] = routeX[i] + (routeX[i + 1] - routeX[i]) * t
            out[1] = routeY[i] + (routeY[i + 1] - routeY[i]) * t
            out[2] = routeZ[i] + (routeZ[i + 1] - routeZ[i]) * t
        }

        /** 流星位置复用缓冲（step 内部专用，避免逐帧分配） */
        private val meteorTmp = FloatArray(3)

        // ---------------- 彗星 ----------------

        /**
         * 彗星入画：从随机边缘斜穿（确定性种子），寿命 [lifeSec] 秒。速度为
         * 归一化坐标/秒（渲染端乘画布尺寸）。
         */
        fun spawnComet(seed: Int, lifeSec: Float = 2.6f) {
            val edge = h01(seed, 1)
            var x: Float
            var y: Float
            var vx: Float
            var vy: Float
            when {
                edge < 0.25f -> { x = 0.02f; y = 0.1f + 0.5f * h01(seed, 2); vx = 0.42f; vy = 0.06f * (if (h01(seed, 3) > 0.5f) 1f else -1f) }
                edge < 0.5f -> { x = 0.98f; y = 0.1f + 0.5f * h01(seed, 2); vx = -0.42f; vy = 0.06f * (if (h01(seed, 3) > 0.5f) 1f else -1f) }
                edge < 0.75f -> { x = 0.1f + 0.8f * h01(seed, 2); y = 0.02f; vx = 0.10f * (if (h01(seed, 3) > 0.5f) 1f else -1f); vy = 0.36f }
                else -> { x = 0.1f + 0.8f * h01(seed, 2); y = 0.98f; vx = 0.10f * (if (h01(seed, 3) > 0.5f) 1f else -1f); vy = -0.36f }
            }
            cometX = x; cometY = y; cometVx = vx; cometVy = vy
            cometLife = lifeSec
            cometTrailSize = 0
        }

        // ---------------- 星爆闪 ----------------

        /**
         * 在图星爆闪（新频道诞生/爬虫爬到）：TTL 秒后自然熄灭。
         * **R320 演出级闪光门**：导演器超新星（ttl=7.0）/GRAVITY_WAVE 宣判档
         * （ttl=9.0）的 flashStar 过 [GravityWaveFx.FLASH_GATE_SEC]=5.0 门 →波前
         * 同时入账（质量剧变事件=时空涟漪——r315 E「同 Directive 第二视觉层」
         * 字面兑现：指令执行器既有 else 分支 flashStar 原语直喂，零屏幕侧改动）。
         * NOVA 复燃（4.5）/舷窗欢迎（3.5）/彗星爆闪（2.5）恒不过门（复燃不是
         * 质量剧变=物理诚实）。
         */
        fun flashStar(chatId: Long, ttlSec: Float = 2.5f) {
            if (chatId != 0L) starFlashes[chatId] = ttlSec
            if (chatId != 0L && GravityWaveFx.isPerformFlashTtl(ttlSec)) {
                spawnGravityWave(
                    chatId,
                    GravityWaveFx.ampPxOfFlashTtl(ttlSec),
                    GravityWaveFx.lifeSecOfFlashTtl(ttlSec),
                )
            }
        }

        // ---------------- 帧步进 ----------------

        /**
         * 帧步进（dtSec 秒，帧循环调用）：推进爆散/流光/流星/彗星/爆闪/时钟。
         * 全部原地更新，零分配（爆闪 HashMap 只在 TTL 归零时 remove）。
         */
        fun step(dtSec: Float) {
            val dt = dtSec.coerceIn(0f, 0.1f)
            timeSec += dt

            // 爆散：匀速+寿命衰减，寿命尽 swap-remove
            var i = 0
            while (i < burstCount) {
                blife[i] -= dt
                if (blife[i] <= 0f) { swapRemoveBurst(i); continue }
                bx[i] += bvx[i] * dt
                by[i] += bvy[i] * dt
                bvx[i] *= 0.96f
                bvy[i] *= 0.96f
                i++
            }

            // 流光：t 推进回绕
            for (k in 0 until flowActive) {
                flowT[k] += flowSpeed[k] * dt
                if (flowT[k] >= 1f) flowT[k] -= 1f
            }

            // 流星：段间停泊 → 段内插值（ease-in-out 微曲率更像滑行）→ 到段尾换段；
            // 尾迹环每步推一格（世界坐标——相机移动时尾迹跟着空间走，不是贴屏残影）
            // R282：整段 dt × meteorPaceScale（导演器节奏档——默认 1f=旧行为零变化）
            if (meteorAlive) {
                val mdt = dt * meteorPaceScale.coerceIn(0f, 1f)
                if (meteorPause > 0f) {
                    meteorPause -= mdt
                } else {
                    meteorT += 0.55f * mdt
                    if (meteorT >= 1f) {
                        meteorT = 0f
                        meteorSeg++
                        meteorPause = 0.7f
                        if (meteorSeg >= routeSize - 1) {
                            meteorSeg = 0 // 循环：今晚的旅程反复上演
                            meteorPause = 2.4f
                            trailSize = 0
                            trailHead = 0
                        }
                    }
                }
                val pos = meteorTmp
                meteorPos(pos)
                trailX[trailHead] = pos[0]
                trailY[trailHead] = pos[1]
                trailZ[trailHead] = pos[2]
                trailHead = (trailHead + 1) % trailCap
                if (trailSize < trailCap) trailSize++
            }

            // 彗星：归一化坐标飞行，出界或寿命尽熄灭
            if (cometLife > 0f) {
                cometLife -= dt
                cometX += cometVx * dt
                cometY += cometVy * dt
                cometTrailX[cometTrailSize % trailCap] = cometX
                cometTrailY[cometTrailSize % trailCap] = cometY
                cometTrailSize++
                if (cometX < -0.1f || cometX > 1.1f || cometY < -0.1f || cometY > 1.1f) cometLife = 0f
            }

            // 星爆闪 TTL
            if (starFlashes.isNotEmpty()) {
                val it = starFlashes.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val left = e.value - dt
                    if (left <= 0f) it.remove() else e.setValue(left)
                }
            }

            // R323 超新星显式账 TTL（starFlashes 同式同拍——演出窗尽即消）
            if (supernovaFlashes.isNotEmpty()) {
                val it = supernovaFlashes.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val left = e.value - dt
                    if (left <= 0f) it.remove() else e.setValue(left)
                }
            }

            // R320 引力波波前：寿命尽 swap-remove（回零成本常态——波窗结束全场静止）
            var wi = 0
            while (wi < waveCount) {
                if (timeSec - waveStartSec[wi] > waveLifeSec[wi]) {
                    val last = waveCount - 1
                    if (wi < last) {
                        waveChatIds[wi] = waveChatIds[last]
                        waveStartSec[wi] = waveStartSec[last]
                        waveAmpPx[wi] = waveAmpPx[last]
                        waveLifeSec[wi] = waveLifeSec[last]
                    }
                    waveCount = last
                    continue
                }
                wi++
            }
        }
    }
}
