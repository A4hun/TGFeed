package dev.tgfeed.ui.gl

import android.opengl.GLES30
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.core.tastemap.TasteTerminalFx

/**
 * R277 GL 着色器族（android.opengl 薄壳——着色器源+编译链接+纹理上传；
 * 全部在 onSurfaceCreated 一次建成，帧路径零编译）。
 *
 * 四着色器：点精灵（圆形软边）/线段/纹理四边形/真球（UV 球网格+光照颗粒）。
 * 坐标约定：点/线/四边形顶点=**屏像素**（CPU 侧 TasteSpace.project 已投影，
 * 与 Canvas 逐位同式），着色器只做 屏 px→NDC；真球=世界坐标×uVP 矩阵
 * （GlCameraMatrix 与 project 的等价性有单测锚）。
 */
internal object GlShaders {

    // ==================== 屏空间公共段 ====================

    private const val SCREEN_VS_HEADER = """#version 300 es
uniform vec2 uViewport;
vec2 toNdc(vec2 px) {
    return vec2(px.x / uViewport.x * 2.0 - 1.0, 1.0 - px.y / uViewport.y * 2.0);
}
"""

    // ==================== 点精灵（圆形软边+核心双段色） ====================

    const val POINT_VS = SCREEN_VS_HEADER + """
layout(location=0) in vec2 aPos;
layout(location=1) in float aSize;
layout(location=2) in float aCore;
layout(location=3) in vec4 aColor;
layout(location=4) in vec3 aCoreColor;
out vec4 vColor;
out vec3 vCore;
out float vCoreR;
void main() {
    gl_Position = vec4(toNdc(aPos), 0.0, 1.0);
    gl_PointSize = aSize;
    vColor = aColor;
    vCore = aCoreColor;
    vCoreR = aCore;
}
"""

    const val POINT_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
in vec3 vCore;
in float vCoreR;
out vec4 fragColor;
void main() {
    vec2 pc = gl_PointCoord * 2.0 - 1.0;
    float dd = length(pc);
    if (dd > 1.0) discard;
    float edge = 1.0 - smoothstep(vCoreR, 1.0, dd);
    // 核心双段混色（coreRatio 内=核心色，过渡带 0.25——中距星白热核观感）
    float coreMix = smoothstep(max(vCoreR - 0.25, 0.0), vCoreR, dd);
    vec3 col = mix(vCore, vColor.rgb, coreMix);
    fragColor = vec4(col, vColor.a * edge);
}
"""

    // ==================== 线段 ====================

    const val LINE_VS = SCREEN_VS_HEADER + """
layout(location=0) in vec2 aPos;
layout(location=1) in vec4 aColor;
out vec4 vColor;
void main() {
    gl_Position = vec4(toNdc(aPos), 0.0, 1.0);
    vColor = aColor;
}
"""

    const val LINE_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() { fragColor = vColor; }
"""

    // ==================== 纹理四边形 ====================

    const val QUAD_VS = SCREEN_VS_HEADER + """
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
layout(location=2) in vec4 aColor;
out vec2 vUV;
out vec4 vColor;
void main() {
    gl_Position = vec4(toNdc(aPos), 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
}
"""

    const val QUAD_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUV;
in vec4 vColor;
out vec4 fragColor;
void main() {
    vec4 tex = texture(uTex, vUV);
    fragColor = vec4(tex.rgb * vColor.rgb, tex.a * vColor.a);
}
"""

    // ==================== 真球（恒星 UV 球网格+光照颗粒——20:20 追加令②试点） ====================

    /**
     * 顶点=单位球法线（位置=法线）；uCenter/uRadius 摆放；uVP=viewProjection。
     * 世界位与法线双 varying（fragment 光照/颗粒采样原料）。
     *
     * **R321 契约跟改（申报件——r315 风险章预判「若须进 shader=契约跟改申报义务」
     * 实锤）**：潮汐形变的逐实例非均匀缩放 CPU 端不可行（真球=共享静态网格 VBO+
     * 逐实例 uniform——网格不能逐星重上传），加 `uTideAxis`(vec3)+`uTideGrow`(vec2)
     * 两 uniform+一行顶点变换：`pn=aNormal×(1+grow.y)+uTideAxis×(dot×grow.x)`——
     * **缺省 0=恒等式**（uTideGrow=(0,0) 时 pn=aNormal 逐位=旧输出——GlMiniSystemView
     * 头注「若后续加 uniform 缺省 0 视觉安全」正是本模式：世界卡小模型零代码改动
     * 零行为变化；R311 GlMiniSystemView 契约跟改三行先例同径，已向父代理申报）。
     * vNormal 保持原法线（形变=渲染期纯视觉变换——光照近似不越权，R282 保形口径）。
     */
    const val SPHERE_VS = """#version 300 es
uniform mat4 uVP;
uniform vec3 uCenter;
uniform float uRadius;
uniform vec3 uTideAxis;
uniform vec2 uTideGrow;
layout(location=0) in vec3 aNormal;
out vec3 vNormal;
out vec3 vWorldPos;
void main() {
    vec3 pn = aNormal * (1.0 + uTideGrow.y) + uTideAxis * (dot(aNormal, uTideAxis) * uTideGrow.x);
    vec3 wp = uCenter + pn * uRadius;
    vWorldPos = wp;
    vNormal = aNormal;
    gl_Position = uVP * vec4(wp, 1.0);
}
"""

    /**
     * R349 球体着色共用体（**SPHERE_FS 与 SPHERE_I_FS 共源单源——一类一件**：
     * 全模逐实例 uniform 路径与低模 instancing 路径走**同一**三段色温梯度/色球
     * 壳/临边昏暗/米粒组织/黑子/火苗方程——mesh 档差异只在参数来源（uniform vs
     * 实例属性），观感连续是换模边界的零 pop 依据）。
     * **R355 终裁：blend 带均匀底光项 ambient 整体退役**（用户「平盘期彻底删除
     * （不是压缩）——球从第一像素=真 3D 临边昏暗」；旧均匀底光抬亮行删除——
     * **球面明暗签名（中心/边缘 ≥1.8）结构成立**：中心 val=1 vs
     * 缘带 val=[0.04,0.42]（limbK 0.40~0.85 色温从动）+芯/缘三段色梯度；满档/
     * 带内球同式=全档强制（无平盘例外分支）。GlMiniSystemView 同源程序零牵连
     * （legacy SPHERE 调用不带新参）。
     * **R357：消费本体的 FS 精度档 mediump转highp——移动 GPU 的 mediump=真 fp16
     * （上限 65504），hash3 中间积 p.x·p.y·p.z·(分量之和) 最大约 25 万溢出致噪声塌缩
     * （米粒/黑子/火苗全塌=光滑球，小米实锤；桌面 GL mediump 按 fp32 跑不塌）。**
     */
    private const val SPHERE_SHADE_BODY = """
float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), u.x),
            mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), u.x), u.y),
        mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), u.x),
            mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), u.x), u.y),
        u.z);
}
float fbm3(vec3 p) {
    return (vnoise3(p) * 0.5 + vnoise3(p * 2.0 + 7.3) * 0.25 + vnoise3(p * 4.0 + 13.1) * 0.125) / 0.875;
}
float fbm2(vec3 p) {
    return (vnoise3(p) * 0.5 + vnoise3(p * 2.0 + 7.3) * 0.25) / 0.75;
}
// R412d 贴脸米粒 Voronoi（3D 细胞：x=F1 y=F2 z=胞随机值；hash3 复用，27 邻胞单 hash
// 派生抖动向量——仅贴脸分支调用，远景零成本）
vec3 voro3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    float f1 = 8.0;
    float f2 = 8.0;
    float id = 0.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 o = vec3(float(x), float(y), float(z));
                float h = hash3(i + o);
                vec3 r = o + fract(h * vec3(1.0, 17.13, 131.7)) * 0.8 + 0.1 - f;
                float dd = dot(r, r);
                if (dd < f1) { f2 = f1; f1 = dd; id = h; }
                else if (dd < f2) { f2 = dd; }
            }
        }
    }
    return vec3(sqrt(f1), sqrt(f2), id);
}
vec4 shadeSphere(
    vec3 n, vec3 worldPos, vec3 camPos,
    vec3 core, vec3 star, vec3 edge,
    float alpha, float grainAmp, float seed, float spin, float time,
    float spotAmp, float flameAmp, float hot, float limbK
) {
    vec3 v = normalize(camPos - worldPos);
    float ct = clamp(dot(n, v), 0.0, 1.0);
    float d = sqrt(max(0.0, 1.0 - ct * ct));
    // 三段色温梯度
    float t1 = smoothstep(0.0, 0.40, d);
    float t2 = smoothstep(0.60, 0.97, d);
    vec3 rgb = mix(mix(core, star, t1), edge, t2);
    // R311 色球壳层（Hα 红壳=色球本色：氢 656nm 深红；壳厚随活动烈度）——
    // 色相层（先色相后亮度律=真实分层）
    float chromo = smoothstep(0.90, 0.94, d) * (1.0 - smoothstep(0.965, 0.985, d));
    if (chromo > 0.001) {
        float shell = chromo * (0.35 + 0.45 * flameAmp);
        rgb = mix(rgb, vec3(0.78, 0.16, 0.10), shell * 0.55);
    }
    // R311 临边昏暗真实化（线性律系数随色温从动+二次项——旧固定
    // u=0.66 全星同昏暗=同质化帮凶；μ=cosθ）+色球内缘环
    float om = 1.0 - ct;
    float val = max(1.0 - limbK * om - 0.18 * om * om, 0.04);
    float rim = smoothstep(0.86, 0.92, d) * (1.0 - smoothstep(0.94, 0.98, d));
    val *= 1.0 + 0.22 * rim;
    // 自转采样位（绕 Y 轴缓转——颗粒/黑子/火苗同转=「活着的恒星」）
    float cs = cos(spin);
    float sn = sin(spin);
    vec3 np = vec3(n.x * cs + n.z * sn, n.y, -n.x * sn + n.z * cs);
    // R311 湍流颗粒→真米粒组织（3D fBm 锚世界法线——绕飞有侧面；缓时漂
    // churn=米粒组织沸腾演化）+胞界暗脉（中位等值线=胞界网络）
    vec3 cellP = np * 7.0 + seed + vec3(time * 0.021, 0.0, -time * 0.013);
    float g = fbm3(cellP);
    // R409b 贴脸细节八度（屏径自适应——fwidth 估法线空间像素跨度 pxN：
    // 第 k 八度频率 7×2^k，周期屏像素≈1/(f·pxN)；周期 ≥4px 起淡入、≥8px 满幅；
    // 远/中档 pxN 大→权重 0→分支跳过=逐位旧输出；fBm 振幅律续接（零均值叠加））
    float pxN = length(fwidth(np));
    float wd1 = 1.0 - smoothstep(0.125, 0.25, 56.0 * pxN);
    if (wd1 > 0.001) {
        g += (vnoise3(cellP * 8.0 + 21.7) - 0.5) * 0.075 * wd1;
        float wd2 = 1.0 - smoothstep(0.125, 0.25, 112.0 * pxN);
        if (wd2 > 0.001) {
            g += (vnoise3(cellP * 16.0 + 37.9) - 0.5) * 0.045 * wd2;
            float wd3 = 1.0 - smoothstep(0.125, 0.25, 224.0 * pxN);
            if (wd3 > 0.001) {
                g += (vnoise3(cellP * 32.0 + 53.3) - 0.5) * 0.03 * wd3;
            }
        }
    }
    float gc = clamp((g - 0.5) * 1.6, -0.5, 0.5) + 0.5;
    val *= 1.0 + grainAmp * (gc - 0.5) * 2.0;
    float lane = 1.0 - abs(2.0 * g - 1.0);
    val *= 1.0 - grainAmp * 0.55 * smoothstep(0.55, 1.0, lane);
    // 黑子斑（低频 fBm 阈值——冷/白星暗斑、热星亮斑 faculae；R249 数据方程：
    // metallicity=内容多样性定斑量）
    float sn2 = fbm2(np * 2.3 + seed * 1.71);
    float spot = smoothstep(0.62, 0.74, sn2) * spotAmp;
    val *= mix(1.0 - 0.28 * spot, 1.0 + 0.18 * spot, hot);
    // R412d 贴脸表面丰富化（仅 wd1>0=近距离分支；wd1=0 时整段跳过=远景逐位旧输出）：
    // ① Voronoi 米粒（亮心暗边胞）② 超米粒网格调制 ③ 黑子本影/半影放射纹
    // ④ 临边光斑网纹（limb 附近亮网）⑤ 临边毛刺增强
    if (wd1 > 0.001) {
        vec3 gp = np * 40.0 + seed * 3.1 + vec3(0.0, 0.0, time * 0.035);
        vec3 vo = voro3(gp);
        float edgeW = vo.y - vo.x;
        float cellC = smoothstep(0.0, 0.32, edgeW);
        // ② 超米粒（≈米粒 6 倍尺度低频——调米粒对比与微亮度）
        float sg = vnoise3(np * 6.5 + seed * 1.37 + vec3(time * 0.004, 0.0, 0.0));
        // 黑子区内米粒被压制（磁场抑制对流）
        float gAmp = (0.16 + 0.10 * sg) * wd1 * (1.0 - 0.8 * spot);
        val *= 1.0 + gAmp * ((cellC - 0.55) * 1.4 + (vo.z - 0.5) * 0.35);
        val *= 1.0 + 0.06 * wd1 * (sg - 0.5);
        // ③ 黑子本影/半影放射纹（梯度向量求放射方向；仅斑区内）
        if (spot > 0.02) {
            vec3 sp = np * 2.3 + seed * 1.71;
            float e = 0.02;
            vec3 gr = vec3(fbm2(sp + vec3(e, 0.0, 0.0)), fbm2(sp + vec3(0.0, e, 0.0)),
                fbm2(sp + vec3(0.0, 0.0, e))) - sn2;
            gr -= n * dot(gr, n);
            vec3 rd = normalize(gr + vec3(1e-5));
            vec3 td = cross(n, rd);
            float umb = smoothstep(0.70, 0.78, sn2) * spotAmp;
            float pen = clamp(spot - umb, 0.0, 1.0);
            float fil = vnoise3(vec3(dot(np, td) * 70.0, dot(np, rd) * 8.0, seed * 5.0));
            float cold = 1.0 - hot;
            val *= 1.0 - wd1 * cold * (0.45 * umb + pen * 0.30 * (fil - 0.35));
            val *= 1.0 + wd1 * hot * pen * 0.20 * fil;
        }
        // ④ 临边光斑网纹（米粒胞界亮网×超米粒掩模，越近临边越显）
        float fac = (1.0 - smoothstep(0.0, 0.10, edgeW)) * smoothstep(0.55, 0.93, d)
            * smoothstep(0.35, 0.75, sg);
        val *= 1.0 + wd1 * 0.45 * fac;
        // ⑤ 临边毛刺增强（缘带高频角向焰丝）
        float bandC = smoothstep(0.84, 0.92, d) * (1.0 - smoothstep(0.975, 1.0, d));
        if (bandC > 0.001) {
            float sp2 = vnoise3(np * 60.0 + vec3(0.0, time * 0.6, seed * 1.9));
            val *= 1.0 + wd1 * bandC * (0.25 + 0.6 * flameAmp) * (sp2 - 0.45);
        }
    }
    // 色球火苗（缘带角向高频 fBm 随时间缓漂——焰舌起伏；活动烈度定幅）
    float band = smoothstep(0.80, 0.90, d) * (1.0 - smoothstep(0.97, 1.0, d));
    if (band > 0.001 && flameAmp > 0.001) {
        float fl = fbm2(np * 14.0 + vec3(0.0, time * 0.35, seed * 2.3));
        float flick = 0.6 + 0.4 * sin(time * 1.3 + fl * 9.0);
        val *= 1.0 + band * flameAmp * (fl - 0.42) * 1.15 * flick;
    }
    // R355 blend 带均匀底光项退役（平盘期彻底删除——真 3D 即刻生效：上方临边
    // 昏暗/色温梯度/米粒组织=球面明暗从第一像素全权在场）
    // alpha 硬边（0.955 同式）
    float a = d < 0.955 ? 1.0 : 1.0 - smoothstep(0.955, 1.0, d);
    float outA = a * alpha;
    if (outA < 0.004) discard;
    return vec4(rgb * val, outA);
}
"""

    /**
     * 真球 fragment（与 ballBitmapOf 同参数方程的 shader 形态——R267b 湍流颗粒的
     * GL 根治形态；R349 起着色体=[SPHERE_SHADE_BODY] 共源单源，本壳只做 uniform
     * →参数映射；R355 ambient 尾参随平盘退役删除——真 3D 球面全档在场）。
     */
    const val SPHERE_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorldPos;
uniform vec3 uCamPos;
uniform vec3 uCore;
uniform vec3 uStar;
uniform vec3 uEdge;
uniform float uAlpha;
uniform float uGrainAmp;
uniform float uSeed;
uniform float uSpin;
uniform float uTime;
uniform float uSpotAmp;
uniform float uFlameAmp;
uniform float uHot;
uniform float uLimbK;
out vec4 fragColor;
$SPHERE_SHADE_BODY
void main() {
    fragColor = shadeSphere(
        normalize(vNormal), vWorldPos, uCamPos,
        uCore, uStar, uEdge,
        uAlpha, uGrainAmp, uSeed, uSpin, uTime,
        uSpotAmp, uFlameAmp, uHot, uLimbK);
}
"""

    // ==================== R349 低模球 instancing（SPHERE_I——2~8px 批档） ====================

    /**
     * 低模球顶点着色器（**instancing——OP_ROCK 先例同款**）：网格=GlSphereMesh
     * 低模 8×6（location 0 法线即位置）；实例属性 location 2..7（divisor=1——
     * [GlFrameBuilder.SPHERE_I_STRIDE] 语义：位+径/芯色+alpha/星色+颗粒/缘色+种子/
     * 自转+时间+黑子+火苗/热星+临边+R355 退役位+预留）。
     * **Mali 跨阶段同名 uniform 精度对齐审计（R281 RING uCenter 案纪律）**：本
     * 程序无跨阶段同名 uniform（uVP 仅 VS/uCamPos 仅 FS）——结构性满足；uCamPos
     * 在 FS 侧显式 mediump（与 SPHERE_FS 同档——同名不同程序互不牵连）。
     * 潮汐形变不设实例槽（低模档形变亚 10px 不可辨——潮汐=全模档专享，
     * r349_notes §2.5 裁决）。R355：槽 22（旧 blend 带底光 ambient）随平盘退役
     * 停打包——vParams3.y 恒 0（槽位契约保持：缓冲仍 24 槽无条件写 0）。
     */
    const val SPHERE_I_VS = """#version 300 es
uniform mat4 uVP;
layout(location=0) in vec3 aNormal;
layout(location=2) in vec4 iPosR;
layout(location=3) in vec4 iColorCore;
layout(location=4) in vec4 iColorStar;
layout(location=5) in vec4 iColorEdge;
layout(location=6) in vec4 iParams1;
layout(location=7) in vec4 iParams2;
out vec3 vNormal;
out vec3 vWorldPos;
out vec3 vCore;
out vec3 vStar;
out vec3 vEdge;
out vec4 vParams1;
out vec4 vParams2;
out vec4 vParams3;
void main() {
    vec3 wp = iPosR.xyz + aNormal * iPosR.w;
    vWorldPos = wp;
    vNormal = aNormal;
    vCore = iColorCore.rgb;
    vStar = iColorStar.rgb;
    vEdge = iColorEdge.rgb;
    vParams1 = vec4(iColorCore.a, iColorStar.a, iColorEdge.a, iParams1.x);
    vParams2 = iParams1;
    vParams3 = vec4(iParams2.x, 0.0, 0.0, 0.0);
    gl_Position = uVP * vec4(wp, 1.0);
}
"""

    /**
     * 低模球 fragment：着色体=[SPHERE_SHADE_BODY] 共源单源（与全模 SPHERE_FS
     * 同一光照/颗粒/色温方程——换模零 pop 的观感连续锚；R355 真 3D 即刻生效：
     * blend 带内球=同一真球面明暗——**球面明暗签名全档强制**）；实例参数经打包
     * varying 解包（vParams1=alpha/颗粒/种子/自转；vParams2=时间/黑子/火苗/
     * 热星；vParams3.x=临边系数。R355：vParams3.y=旧 ambient 退役位恒 0）。
     * uCamPos=逐 draw uniform（全实例共享）。
     */
    const val SPHERE_I_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorldPos;
in vec3 vCore;
in vec3 vStar;
in vec3 vEdge;
in vec4 vParams1;
in vec4 vParams2;
in vec4 vParams3;
uniform mediump vec3 uCamPos;
out vec4 fragColor;
$SPHERE_SHADE_BODY
void main() {
    fragColor = shadeSphere(
        normalize(vNormal), vWorldPos, uCamPos,
        vCore, vStar, vEdge,
        vParams1.x, vParams1.y, vParams1.z, vParams1.w,
        vParams2.x, vParams2.y, vParams2.z, vParams2.w,
        vParams3.x);
}
"""

    // ==================== R280 贴脸日冕（程序冕流 shader——非位图烘焙） ====================

    /**
     * 日冕顶点着色器：单位四边形（aPos∈[−1,1]²）× uHalfPx + uCenterPx=屏像素摆放
     * （屏空间件——冕流从球缘外飘，与真球同锚点）。vLocal 留 [−1,1] 局部坐标。
     */
    const val CORONA_VS = SCREEN_VS_HEADER + """
layout(location=0) in vec2 aPos;
uniform vec2 uCenterPx;
uniform float uHalfPx;
out vec2 vLocal;
void main() {
    vLocal = aPos;
    gl_Position = vec4(toNdc(uCenterPx + aPos * uHalfPx), 0.0, 1.0);
}
"""

    /**
     * 日冕 fragment（镜像 Canvas drawGlowStar 日冕段的程序shader形态）：
     * 12 道冕流（角向等分格+格内哈希束长 0.28~0.83r/呼吸 0.55+0.45·breathe/
     * 缓旋 t×0.022——与 Canvas 同参数），球缘 0.98r 起外飘（局部坐标 1.0=球半径
     * r——uHalfPx=r×1.6 时 0.98r=0.6125 局部），盘内 discard 不洗白球面。
     * 色=核色（uCore），alpha=uAlpha×(0.05+0.10·breathe)（Canvas 同式）。
     * R311 日珥（球缘外伸拱形焰舌——真日珥=磁力线拱，非直刺）：4 道拱（哈希
     * 扇区），拱高随 uFlameAmp（活动烈度）——径向 0.98r..1.30r 的弧形束
     * （角向窄带×拱形径向轮廓），色=Hα 深红偏橙（0.85,0.25,0.12）混核色。
     */
    const val CORONA_FS = """#version 300 es
precision highp float;
in vec2 vLocal;
uniform vec3 uCore;
uniform float uAlpha;
uniform float uSeed;
uniform float uTime;
uniform float uFlameAmp;
out vec4 fragColor;
float hash1(float p) {
    return fract(sin(p * 127.1 + uSeed * 311.7) * 43758.5453);
}
void main() {
    // 球半径归一（四边形半幅=1.9r 恒比——0.98=球缘外飘起点；盘内 discard 不洗白球面）
    float d = length(vLocal) * 1.9;
    if (d < 0.98) discard;
    float ang = atan(vLocal.y, vLocal.x) + uTime * 0.022;
    float seg = 6.2831853 / 12.0;
    float cell = floor(ang / seg);
    float ca = fract(ang / seg) - 0.5;
    float h1 = hash1(cell * 17.0 + 211.0);
    float h2 = hash1(cell * 17.0 + 213.0);
    float h3 = hash1(cell * 17.0 + 215.0);
    float h4 = hash1(cell * 17.0 + 217.0);
    float breathe = 0.5 + 0.5 * sin(uTime * (0.24 + 0.3 * h2) + h4 * 6.28);
    float len = (0.28 + 0.55 * h3) * (0.55 + 0.45 * breathe);
    // 束形：角向窄带（cell 中心）× 径向 0.98..0.98+len 渐隐
    float streak = pow(max(0.0, 1.0 - abs(ca) * 6.0), 1.6);
    float radial = 1.0 - smoothstep(0.98, 0.98 + len, d);
    float a = uAlpha * (0.05 + 0.10 * breathe) * streak * radial;
    vec3 col = uCore;
    // ---- R311 日珥拱（4 道哈希扇区——磁力线拱形焰舌，高度随活动烈度） ----
    if (uFlameAmp > 0.02 && d < 1.30) {
        float pseg = 6.2831853 / 4.0;
        float pcell = floor(ang / pseg);
        float pca = fract(ang / pseg) - 0.5;
        float ph1 = hash1(pcell * 31.0 + 503.0);
        float ph2 = hash1(pcell * 31.0 + 507.0);
        // 拱形轮廓：径向上限=0.98+高×拱形（拱顶在扇区中心——磁力线拱的观感）
        // 拱高=哈希×活动烈度（0.10~0.30 球半径——贴脸窗内的可视拱）
        float pH = (0.10 + 0.20 * ph1) * uFlameAmp;
        float arch = max(0.0, 1.0 - (pca / 0.42) * (pca / 0.42));
        float flickP = 0.7 + 0.3 * sin(uTime * (0.5 + 0.8 * ph2) + ph1 * 6.28);
        // top 下限 0.981（arch→0 时 smoothstep(0.98,0.98) 双缘重合=GLSL 未定义行为——
        // Mali 严格档可 NaN；R281 精度案同类防御）
        float top = max(0.98 + pH * arch * flickP, 0.981);
        float inArch = step(d, top) * step(0.98, d);
        // 束形：角向 pow 收束 × 径向向上渐隐（拱顶最亮）
        float shape = pow(max(0.0, 1.0 - abs(pca) * 4.2), 2.0) * inArch;
        float radFade = 1.0 - smoothstep(0.98, top, d);
        float pa = uAlpha * 0.34 * uFlameAmp * shape * (0.35 + 0.65 * radFade);
        // Hα 深红偏橙（日珥本色）混核色
        vec3 pCol = mix(vec3(0.85, 0.25, 0.12), uCore, 0.30);
        col = mix(col, pCol, clamp(pa * 6.0, 0.0, 1.0));
        a += pa;
    }
    if (a < 0.003) discard;
    fragColor = vec4(col, a);
}
"""

    // ==================== R281 世界系公共段 ====================

    /** 世界系投影公共头（uVP×世界位——与 GlCameraMatrix/TasteSpace.project 逐位同式） */
    private const val WORLD_VS_HEADER = """#version 300 es
uniform mat4 uVP;
"""

    /** 3D 值噪声族（PLANET/RING/ROCK/NEBULA/STRIP 共用段——与真球 hash3 同式）。R357：消费 FS 全 highp 化（fp16 塌噪声实锤——精度档=纹理存亡）。R392：NEBULA/RAYMARCH 两域摘除（噪声载体=烘焙 3D 纹理），存活消费者=PLANET_FS */
    private const val NOISE_3D = """
float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), u.x),
            mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), u.x), u.y),
        mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), u.x),
            mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), u.x), u.y),
        u.z);
}
float fbm3(vec3 p) {
    return (vnoise3(p) * 0.5 + vnoise3(p * 2.0 + 7.3) * 0.25 + vnoise3(p * 4.0 + 13.1) * 0.125) / 0.875;
}
"""

    /** 软边点精灵公共 FS（SKY/KEPLER 尘点——与 POINT_FS core=本色的观感等价） */
    private const val SOFT_POINT_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() {
    vec2 pc = gl_PointCoord * 2.0 - 1.0;
    float dd = length(pc);
    if (dd > 1.0) discard;
    float edge = 1.0 - smoothstep(0.72, 1.0, dd);
    fragColor = vec4(vColor.rgb, vColor.a * edge);
}
"""

    // ==================== R281 天幕 GPU 投影（任务⑤：760 方向星静态 VBO——CPU 投影归零） ====================

    /**
     * 天幕顶点着色器：方向锚定（wp=camPos+dir×9000——无穷远语义）×uVP；
     * 闪烁（tw/量化桶 alpha）在 VS 算（uTime uniform）——与 buildSky 同式镜像。
     */
    const val SKY_VS = WORLD_VS_HEADER + """
uniform vec3 uCamPos;
uniform float uTime;
layout(location=0) in vec3 aDir;
layout(location=1) in vec3 aColor;
layout(location=2) in float aBaseA;
layout(location=3) in float aPhase;
layout(location=4) in float aSize;
out vec4 vColor;
void main() {
    vec3 wp = uCamPos + aDir * 9000.0;
    gl_Position = uVP * vec4(wp, 1.0);
    gl_PointSize = aSize;
    float tw = 0.70 + 0.30 * sin(uTime * (0.4 + aPhase * 0.11) + aPhase);
    float q = min(floor(tw * 4.0), 3.0);
    vColor = vec4(aColor, aBaseA * (0.62 + 0.13 * q));
}
"""

    const val SKY_FS = SOFT_POINT_FS

    // ==================== R281 开普勒程序（任务⑤：轨道线+尘埃点的 GPU 轨道求值） ====================

    /**
     * 开普勒顶点着色器：顶点只载 ν 真近点角/径向系数/世界尺寸/色——
     * 世界位=r(ν)=a(1−e²)/(1+e·cosν)×coef + 轨道面旋转（planetOrbitPoint 同式镜像），
     * 点精灵尺寸=worldSize×uFocalPx/depth（近大远小真透视）。
     * 轨道线=GL_LINES 回放（aSizeW 忽略）；尘埃=GL_POINTS（软边 FS）。
     */
    const val KEPLER_VS = WORLD_VS_HEADER + """
uniform vec3 uCenter;
uniform float uPeri;
uniform float uIncSin;
uniform float uIncCos;
uniform float uSemiR;
uniform float uEcc;
uniform float uFocalPx;
layout(location=0) in float aNu;
layout(location=1) in float aCoef;
layout(location=2) in float aSizeW;
layout(location=3) in vec4 aColor;
out vec4 vColor;
void main() {
    float th = aNu + uPeri;
    float rOrb = uSemiR * (1.0 - uEcc * uEcc) / (1.0 + uEcc * cos(aNu)) * aCoef;
    float ct = cos(th);
    float st = sin(th);
    vec3 wp = uCenter + vec3(ct * rOrb, -st * rOrb * uIncSin, st * rOrb * uIncCos);
    vec4 clip = uVP * vec4(wp, 1.0);
    gl_Position = clip;
    gl_PointSize = aSizeW * uFocalPx / max(clip.w, 1e-4);
    vColor = aColor;
}
"""

    const val KEPLER_FS = SOFT_POINT_FS

    /** 开普勒线段 FS（纯色——与 LINE_FS 同式） */
    const val KEPLER_LINE_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() { fragColor = vColor; }
"""

    // ==================== R281 世界点（星团成员 3D 球分布等） ====================

    /** 世界点顶点着色器：世界位×uVP；尺寸=worldSize×uFocalPx/depth——近大远小团内视差 */
    const val WPOINT_VS = WORLD_VS_HEADER + """
uniform float uFocalPx;
layout(location=0) in vec3 aPos;
layout(location=1) in float aSizeW;
layout(location=2) in float aCore;
layout(location=3) in vec4 aColor;
layout(location=4) in vec3 aCoreColor;
out vec4 vColor;
out vec3 vCore;
out float vCoreR;
void main() {
    vec4 clip = uVP * vec4(aPos, 1.0);
    gl_Position = clip;
    gl_PointSize = aSizeW * uFocalPx / max(clip.w, 1e-4);
    vColor = aColor;
    vCore = aCoreColor;
    vCoreR = aCore;
}
"""

    // ==================== R281 世界三角带（彗星双尾——屏轨迹反投影入世界=真视差） ====================

    const val STRIP_VS = WORLD_VS_HEADER + """
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aUV;
layout(location=2) in vec4 aColor;
out vec2 vUV;
out vec4 vColor;
void main() {
    gl_Position = uVP * vec4(aPos, 1.0);
    vUV = aUV;
    vColor = aColor;
}
"""

    /** 尾迹 FS：沿尾渐暗×横向软边×fbm 侵蚀（离子/尘埃两层共用，色由顶点载） */
    const val STRIP_FS = """#version 300 es
precision highp float;
in vec2 vUV;
in vec4 vColor;
uniform float uTime;
uniform float uSeed;
out vec4 fragColor;
float hash2(vec2 p) {
    p = fract(p * vec2(0.3183, 0.3679) + vec2(0.1, 0.17));
    p *= 17.0;
    return fract(p.x * p.y * (p.x + p.y));
}
float vnoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1, 0)), u.x),
               mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), u.x), u.y);
}
float fbm2(vec2 p) {
    return (vnoise2(p) * 0.5 + vnoise2(p * 2.0 + 7.3) * 0.25 + vnoise2(p * 4.0 + 13.1) * 0.125) / 0.875;
}
void main() {
    float along = vUV.x;        // 0=头端 1=尾端
    float across = abs(vUV.y);  // 0=中轴 1=缘
    float body = pow(1.0 - along, 1.35);
    float edge = 1.0 - smoothstep(0.30, 1.0, across);
    float n = fbm2(vec2(along * 7.0 - uTime * 0.9 + uSeed, vUV.y * 2.0 + uSeed * 1.7));
    float erosion = smoothstep(0.22, 0.72, n * 0.75 + body * 0.35);
    float a = vColor.a * body * edge * erosion;
    if (a < 0.004) discard;
    fragColor = vec4(vColor.rgb, a);
}
"""

    // ==================== R281 行星真球（任务①：真 3D 平行光——晨昏线/相位随视角真变） ====================

    /** 行星顶点着色器（复用球网格——与真球 SPHERE_VS 同式） */
    const val PLANET_VS = WORLD_VS_HEADER + """
uniform vec3 uCenter;
uniform float uRadius;
layout(location=0) in vec3 aNormal;
out vec3 vNormal;
out vec3 vWorldPos;
void main() {
    vec3 wp = uCenter + aNormal * uRadius;
    vWorldPos = wp;
    vNormal = aNormal;
    gl_Position = uVP * vec4(wp, 1.0);
}
"""

    /**
     * 行星 fragment（planetBitmapOf 六件套的 shader 真身——位图烘焙整体消解）：
     *  - **真 3D 平行光**（uLightDir=行星→恒星世界向量）：晨昏线 smoothstep 过渡、
     *    夜面 6% 环境光、受光高光——飞绕行星=真相位（Canvas「受光面绕屏心旋转」根治病除）；
     *  - 程序表面：气态=纬度条带 fbm 湍流扰动+风暴斑；岩质=低频大陆+高频陨击+极冠；
     *  - 大气 fresnel 缘光（受光侧加强）+自转采样位（绕极轴缓转）。
     */
    const val PLANET_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorldPos;
uniform vec3 uCamPos;
uniform vec3 uLightDir;
uniform vec3 uBase;
uniform float uSeed;
uniform float uSpin;
uniform float uGas;
uniform float uAlpha;
out vec4 fragColor;
$NOISE_3D
void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(uCamPos - vWorldPos);
    float ndl = dot(n, uLightDir);
    // 自转采样位（绕 Y 极轴缓转——表面纹真视差）
    float cs = cos(uSpin);
    float sn = sin(uSpin);
    vec3 np = vec3(n.x * cs + n.z * sn, n.y, -n.x * sn + n.z * cs);
    vec3 alb = uBase;
    if (uGas > 0.5) {
        // 气态：纬度条带（湍流扰动带缘）+ 风暴斑
        float bands = sin((np.y + fbm3(np * 3.0 + uSeed) * 0.35) * 14.0 + uSeed * 3.0);
        alb *= 1.0 + 0.10 * bands;
        float storm = smoothstep(0.72, 0.92, vnoise3(np * 4.0 + vec3(0.0, 0.0, uSeed)));
        alb = mix(alb, alb * 0.80, storm * 0.6);
    } else {
        // 岩质：低频大陆板块 + 高频陨击麻点 + 极冠
        float cont = fbm3(np * 2.6 + uSeed);
        alb *= 0.86 + 0.28 * cont;
        float crat = smoothstep(0.68, 0.78, vnoise3(np * 9.0 + uSeed * 1.7));
        alb *= 1.0 - 0.22 * crat;
        float cap = smoothstep(0.78, 0.88, abs(np.y));
        alb = mix(alb, vec3(0.90, 0.93, 0.97), cap * 0.75);
    }
    // 真 3D 光照：晨昏线柔和过渡；R309 夜面微光可发现性（用户实锤「行星全黑融进
    // 背景」——旧 6% 环境光=死黑区）：夜面=反射恒星光感的冷蓝底光（口径件
    // TasteTagPlanets.PLANET_NIGHT_FLOOR/TINT 单源，Canvas 位图族同族）；天花板
    // 远低于恒星（bloom 阈 0.70，夜面全链 ≤0.30 量级，受光面不变）。
    float day = smoothstep(-0.10, 0.14, ndl);
    float dif = clamp(ndl, 0.0, 1.0);
    vec3 nightAmb = alb * ${TasteTagPlanets.PLANET_NIGHT_FLOOR} * vec3(${TasteTagPlanets.PLANET_NIGHT_TINT_R}, ${TasteTagPlanets.PLANET_NIGHT_TINT_G}, ${TasteTagPlanets.PLANET_NIGHT_TINT_B});
    vec3 col = alb * (0.10 + 1.05 * dif) * day + nightAmb * (1.0 - day);
    // 受光侧高光（bloom 原料——亮行星受光面溢出）
    vec3 hv = normalize(uLightDir + v);
    float spec = pow(clamp(dot(n, hv), 0.0, 1.0), 24.0) * 0.20 * day;
    col += vec3(spec);
    // 大气 fresnel 缘光（受光侧加强；R309 夜面缘光下限 PLANET_RIM_FLOOR——剪影可辨）
    float fres = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 2.6);
    col += uBase * fres * mix(${TasteTagPlanets.PLANET_RIM_FLOOR}, 0.55, day);
    // 边缘 alpha（硬边+极薄渐隐——与真球同口径）
    float ct = clamp(dot(n, v), 0.0, 1.0);
    float d = sqrt(max(0.0, 1.0 - ct * ct));
    float a = d < 0.96 ? 1.0 : 1.0 - smoothstep(0.96, 1.0, d);
    float outA = a * uAlpha;
    if (outA < 0.004) discard;
    fragColor = vec4(col, outA);
}
"""

    // ==================== R281 真 3D 环带（任务①：土星环+黑洞吸积盘——前后两弧法消解） ====================

    /**
     * 环带顶点着色器：局部 XZ 环面片 ×uRadius → 绕 X 倾 [uTiltX] 再绕 Z 倾 [uTiltZ]
     * → 绕世界 Y 进动 [uPrec]（R350 预留槽 15 启用——**0=恒等**：土星环/吸积盘
     * 既有调用逐位不变；遗迹环慢进动=倾斜面节点线绕 Y 缓旋）→ 平移 uCenter——
     * 倾斜圆环带（穿环面飞行=环带从头顶划过的几何底座）。
     */
    const val RING_VS = WORLD_VS_HEADER + """
uniform vec3 uCenter;
uniform float uRadius;
uniform float uTiltX;
uniform float uTiltZ;
uniform float uPrec;
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aRadTheta;
out vec3 vWorldPos;
out vec2 vRT;
out vec3 vPlaneN;
void main() {
    float cx = cos(uTiltX);
    float sx = sin(uTiltX);
    float cz = cos(uTiltZ);
    float sz = sin(uTiltZ);
    vec3 p = aPos * uRadius;
    p = vec3(p.x, p.y * cx - p.z * sx, p.y * sx + p.z * cx);
    p = vec3(p.x * cz - p.y * sz, p.x * sz + p.y * cz, p.z);
    vec3 nrm = vec3(0.0, 1.0, 0.0);
    nrm = vec3(nrm.x, nrm.y * cx - nrm.z * sx, nrm.y * sx + nrm.z * cx);
    nrm = vec3(nrm.x * cz - nrm.y * sz, nrm.x * sz + nrm.y * cz, nrm.z);
    float cp = cos(uPrec);
    float sp = sin(uPrec);
    p = vec3(p.x * cp + p.z * sp, p.y, -p.x * sp + p.z * cp);
    nrm = vec3(nrm.x * cp + nrm.z * sp, nrm.y, -nrm.x * sp + nrm.z * cp);
    vWorldPos = uCenter + p;
    vRT = aRadTheta;
    vPlaneN = nrm;
    gl_Position = uVP * vec4(vWorldPos, 1.0);
}
"""

    /**
     * 环带 fragment（三模）：
     *  - 土星环（uMode=0）：B/C/A 分带+卡西尼缝径向轮廓×环纹颗粒；平行光双面受光+
     *    **行星影**（片元→恒星光线与行星球的解析求交——影区压暗）；掠射角渐隐防闪；
     *  - 吸积盘（uMode=1）：内炽白金→外橙红（DISK_HOT/DISK_COOL 镜像）+**较差自转**
     *    streaks（角速度 ∝ r^-1.5 开普勒律——内快外慢的真实吸积流）+自发光不吃影
     *    （发光溢出由 bloom 接管）；
     *  - **致密遗迹环（uMode=2，R350）**：[REMNANT_RING_R] 带中心 ±
     *    [REMNANT_RING_BAND_HALF] 窄带（单源内插——超新星遗迹壳同族参数直引）、
     *    **无较差自转 streaks**（遗迹=死透的物质壳非吸积流——静态细纹颗粒只有
     *    纹理没有剪切流）、色=uColor（COMPACT_TINT 蓝白）、自发光不吃影（余温
     *    物质壳语义）；uPlanetR 影柱域外（影柱=土星环模专享）。
     */
    const val RING_FS = """#version 300 es
precision highp float;
in vec3 vWorldPos;
in vec2 vRT;
in vec3 vPlaneN;
uniform vec3 uCamPos;
uniform highp vec3 uCenter; // 与 VS 同名 uniform=精度必须显式对齐（Mali 严格链接器实锤：mediump/highp 混档拒链）
uniform vec3 uLightDir;
uniform vec3 uColor;
uniform float uPlanetR;
uniform float uAlpha;
uniform float uSeed;
uniform float uTime;
uniform float uMode;
out vec4 fragColor;
float hash2(vec2 p) {
    p = fract(p * vec2(0.3183, 0.3679) + vec2(0.1, 0.17));
    p *= 17.0;
    return fract(p.x * p.y * (p.x + p.y));
}
float vnoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1, 0)), u.x),
               mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), u.x), u.y);
}
void main() {
    float rr = 1.24 + vRT.x * (2.35 - 1.24); // 实际半径比（×行星半径）
    vec3 l = normalize(uLightDir);
    // 掠射角渐隐（环面几乎侧对相机时防带纹闪）
    vec3 vdir = normalize(uCamPos - vWorldPos);
    float grazing = smoothstep(0.02, 0.10, abs(dot(normalize(vPlaneN), vdir)));
    vec3 col;
    float a;
    if (uMode < 0.5) {
        if (rr < 1.30 || rr > 2.20) discard;
        // 分带轮廓：C 环弱 / B 环亮 / 卡西尼缝暗 / A 环中
        float band;
        if (rr < 1.53) band = 0.30;
        else if (rr < 1.86) band = 0.95;
        else if (rr < 1.98) band = 0.10;
        else band = 0.55;
        float grain = vnoise2(vec2(vRT.y * 160.0, rr * 40.0) + uSeed);
        band *= 0.82 + 0.36 * grain;
        float edgeIn = smoothstep(1.30, 1.34, rr);
        float edgeOut = 1.0 - smoothstep(2.12, 2.20, rr);
        // 平行光双面受光 + 行星影（解析 ray-sphere）
        float light = 0.30 + 0.85 * abs(dot(normalize(vPlaneN), l));
        vec3 oc = vWorldPos - uCenter;
        float b = dot(oc, l);
        float cc = dot(oc, oc) - uPlanetR * uPlanetR;
        float disc = b * b - cc;
        if (disc > 0.0 && -b - sqrt(disc) > 0.0) light *= 0.22;
        a = uAlpha * band * edgeIn * edgeOut * grazing;
        col = uColor * light;
    } else if (uMode < 1.5) {
        if (rr < 1.62) discard;
        float t = (rr - 1.62) / (2.35 - 1.62);
        float glow = 1.0 - t;
        vec3 hot = vec3(1.0, 0.914, 0.769);
        vec3 cool = vec3(0.847, 0.565, 0.345);
        col = mix(cool, hot, glow * glow);
        // 较差自转 streaks（内快外慢——开普勒流的真实剪切）
        float swirl = uTime * 0.9 * pow(max(2.0 - rr, 0.05), 1.5);
        float streak = vnoise2(vec2(vRT.y * 40.0 + swirl * 6.0 + uSeed, rr * 9.0));
        col *= 0.72 + 0.55 * streak;
        a = uAlpha * (0.16 + 0.62 * glow * glow);
        a *= smoothstep(1.62, 1.68, rr) * grazing;
    } else {
        // R350 致密遗迹环（mode=2）：[REMNANT_RING_R] 带中心窄带——参数单源内插
        // （core TasteTerminalFx——壳/遗迹同族零第二套）；无较差自转 streaks
        // （静态细纹=余温物质壳的纹理非剪切流）；色=uColor（COMPACT_TINT 蓝白）
        float bandC = ${TasteTerminalFx.REMNANT_RING_R};
        float bandW = ${TasteTerminalFx.REMNANT_RING_BAND_HALF};
        if (rr < bandC - bandW || rr > bandC + bandW) discard;
        float band = 1.0 - smoothstep(0.0, bandW, abs(rr - bandC));
        float grain = vnoise2(vec2(vRT.y * 160.0, rr * 40.0) + uSeed);
        band *= 0.82 + 0.28 * grain;
        col = uColor;
        a = uAlpha * band * grazing;
    }
    if (a < 0.004) discard;
    fragColor = vec4(col, a);
}
"""

    // ==================== R281 岩石实例（任务②：哈希多面体——instancing 一次 draw） ====================

    /**
     * 岩石顶点着色器（instancing）：网格=GlRockMesh 三角汤（单位方向+面法线）；
     * **径向位移在 VS 按实例种子做**（hash3(方向×3.7+种子)——同方向同位移=汤缝水密，
     * 一网格服务全变体）；实例翻滚=Rodrigues 轴角旋转（轴+角打包 iSpinAxis）。
     */
    const val ROCK_VS = WORLD_VS_HEADER + """
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 iPosScale;
layout(location=3) in vec4 iSpinAxis;
layout(location=4) in vec4 iColor;
layout(location=5) in vec4 iLightEmber;
layout(location=6) in float iSeed;
out vec3 vNormal;
out vec3 vLocal;
out vec3 vWorldPos;
out vec4 vColor;
out vec3 vLight;
out float vEmber;
float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
void main() {
    float disp = 0.80 + 0.38 * hash3(aPos * 3.7 + vec3(iSeed * 17.13, iSeed * 7.31, iSeed * 3.17));
    vec3 p = aPos * disp;
    vec3 ax = normalize(iSpinAxis.xyz);
    float ca = cos(iSpinAxis.w);
    float sa = sin(iSpinAxis.w);
    vec3 pr = p * ca + cross(ax, p) * sa + ax * dot(ax, p) * (1.0 - ca);
    vec3 nr = aNormal * ca + cross(ax, aNormal) * sa + ax * dot(ax, aNormal) * (1.0 - ca);
    vec3 wp = iPosScale.xyz + pr * iPosScale.w;
    vNormal = nr;
    vLocal = pr;
    vWorldPos = wp;
    vColor = iColor;
    vLight = iLightEmber.xyz;
    vEmber = iLightEmber.w;
    gl_Position = uVP * vec4(wp, 1.0);
}
"""

    /**
     * 岩石 fragment：凿面漫反射（面法线×平行光）+低频色斑（vnoise3 锚局部向）
     * +热缘（ember>0：fresnel 暖红缘——流星体烧蚀/彗核受光热侧）；
     * 深度写开=岩石间/与行星真遮挡。
     */
    const val ROCK_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vLocal;
in vec3 vWorldPos;
in vec4 vColor;
in vec3 vLight;
in float vEmber;
uniform vec3 uCamPos;
uniform float uTime;
out vec4 fragColor;
float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), u.x),
            mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), u.x), u.y),
        mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), u.x),
            mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), u.x), u.y),
        u.z);
}
void main() {
    vec3 n = normalize(vNormal);
    float dif = clamp(dot(n, normalize(vLight)), 0.0, 1.0);
    float mottle = vnoise3(vLocal * 5.0);
    // R309 同类扫描：夜面环境 0.20→ROCK_NIGHT_FLOOR（小行星/尘埃岩同病——
    // 贴脸档暗粒融背景；口径件 TasteTagPlanets 单源，仍远低于 bloom 阈）
    vec3 col = vColor.rgb * (${TasteTagPlanets.ROCK_NIGHT_FLOOR} + 0.92 * dif) * (0.82 + 0.36 * mottle);
    if (vEmber > 0.001) {
        vec3 v = normalize(uCamPos - vWorldPos);
        float fres = pow(1.0 - abs(dot(n, v)), 2.0);
        float pulse = 0.72 + 0.28 * sin(uTime * 2.3);
        col += vec3(1.0, 0.52, 0.22) * fres * vEmber * pulse * 0.55;
    }
    fragColor = vec4(col, vColor.a);
}
"""

    // ==================== R281 体积星云（任务④：双层视差程序雾——域扭曲 fbm 密度场） ====================

    /** 星云顶点着色器（屏空间四边形——双层世界深度差由装配层投影承担） */
    const val NEBULA_VS = SCREEN_VS_HEADER + """
layout(location=0) in vec2 aPos;
uniform vec2 uCenterPx;
uniform float uHalfPx;
out vec2 vLocal;
void main() {
    vLocal = aPos;
    gl_Position = vec4(toNdc(uCenterPx + aPos * uHalfPx), 0.0, 1.0);
}
"""

    /**
     * 星云 fragment（Canvas 单张雾贴纸的体积化真身）：
     * 域扭曲 fbm 密度场（warp 双 octave + 时漂=缓 churn）×径向 falloff；
     * alpha=TasteNebula.fogAlpha 同口径（装配层乘好传入）——丝缕结构是程序生成的，
     * 两层前后真深度差=飞近时层间视差（伪体积的诚实第一步；真 raymarch 挂账）。
     *
     * **R358 语义雾质感革命**（哈勃级纱幔——结构承载量级，用户 2026-10-23 拍板
     * 方案 A「没有星云咱们的宇宙很难有那种真实的宇宙那种梦幻感」）四件：
     *  ① 丝缕层=脊线 fbm（ridge=1−|2·fbm−1|）——峰值 alpha 只落在纤维丝上，
     *     丝间留空看得见底星（R252「均匀大圆片+高 alpha」红线的形态解药：同样
     *     alpha 在丝缕形态下不读作圆盘）；
     *  ② 各向异性拉伸=种子哈希固定方向角+旋转矩阵两行短轴压缩（约 1.8:1，
     *     零新参数件，种子即方向）——宏观轮廓拉长不规则，逐雾异构；
     *  ③ 暗尘埃带=smoothstep(ridge)×(1−den) 减法式剪影——纤维亮脊旁刻暗缝，
     *     哈勃式尘埃带观感（雾体内部结构，零新绘制层）；
     *  ④ 雾心色温分层=col×(0.70+1.05·d2) 丝亮隙暗明度纵深——色相仍=uColor
     *     单源（类别色/发射亮档色 R323 零改，零新色源）。
     * 弥散保义红线：边缘仍=既有径向 falloff（d 未受拉伸——絮化≠锐化 R351 口径
     * 沿用）；语义件（渐散/双层视差/絮化/发射分化）全在装配层零改。
     * R357：precision highp 守档（fp16 塌噪声实锤——精度=纹理存亡）。**R392 起
     * 翻篇**：噪声载体=烘焙纹理后 highp 病因消亡，本 FS 回归 mediump（判词③，
     * 详见下段 R392 注记——R357 精神的兑现而非翻转）。
     *
     * **R388 刀2 丝缕频率锁定**（用户 2026-10-25 14:1x 裁决根治刀——判词「丝缕
     * 基因的尺度变换非基因改变」）：第五件 uniform `uFreqScale`（**默认 1.0=
     * 既有设计基因原样**）——q.xy 系数标度=**同一噪声场的 zoom**（z=种子身份槽
     * 不动=同一云同一丝缕身份，非换基因；`vec3(st*2.1, uSeed)` 构造行字面零碰）
     * ：装配端 scale=clamp(rPx/REF, floor, 1.0)——远（小 quad）→低频→**粗丝
     * 留空大**（任何屏距丝缕像素宽恒定），近（大 quad）→scale=1 既有基因；
     * R358 判词「丝间留空看得见底星」在远距由恒定丝宽真正成立。**稀疏化配套**
     * （可配套项落地）：alpha 门下沿随标度抬升 gateLo=0.40+0.24·(1−scale)
     * （远档只留最亮数缕）；scale=1 位移零=R358 门基因（0.40..0.82）恒等。
     * 雾族行恒 1.0（本批病灶=复合体行——雾族远档形态另案）。
     *
     * **R392 载体革命（r392_notes §2①判词）**：噪声**构造**（4 次采样配比/域扭曲/
     * 脊线/暗带/门/色温分层/各向异性/freqScale/时漂平移）逐字保留，**载体**由逐像素
     * ALU fbm3 换烘焙 3D 纹理单次采样（uNoiseVol——渲染器常驻单元 2 单源）；上记
     * R357 highp 守档判词随载体消亡（噪声值=纹理 8bit 归一化，mediump 采样天然
     * 够用——§2③），FS 默认精度回归 mediump（uSeed/uTime 显式 highp 保留）。
     */
    const val NEBULA_FS = """#version 300 es
precision mediump float;
in vec2 vLocal;
uniform vec3 uColor;
uniform float uAlpha;
uniform highp float uSeed;
uniform highp float uTime;
uniform float uFreqScale;
uniform mediump sampler3D uNoiseVol; // R392 噪声载体革命：烘焙 3D 噪声体（逐像素 ALU 求值退役）
out vec4 fragColor;
// R392 载体函数：烘焙体采样（窗 16 q 单位周期——REPEAT 平铺无缝；0.0625=1/16
// 与 NoiseVolumeBaker.WINDOW 单源对表）；种子=平移窗（原 z=uSeed 切片语义的
// 载体内等价身份映射——场统计基因不变）
float fieldAt(highp vec3 p) {
    highp vec3 so = fract(vec3(uSeed * 0.1031, uSeed * 0.173, uSeed * 0.3113));
    // R404 textureLod 显式 LOD0:隐式导数在 RAYMARCH_FS 发散循环(逐像素 t1/t0
    // 不同 + break 分支)的 lane 上=ES3 未定义行为→mip 链塌到 1³ 全局均值→密度
    // 门饱和=无结构白体(用户白色圆饼实锚——两域 fieldAt 同体共用,显式 LOD0=
    // 64³ 原始纹理零依赖导数,唯一正解,语义单源不变)
    return textureLod(uNoiseVol, p * 0.0625 + so, 0.0).r;
}
void main() {
    float d = length(vLocal);
    if (d > 1.0) discard;
    float fall = 1.0 - smoothstep(0.12, 1.0, d);
    // R358 ②各向异性拉伸：种子哈希固定方向角（黄金比例哈希——零新参数件，
    // 种子即方向），旋转矩阵两行后短轴压缩 1.8 = 雾沿 ang 向拉长（约 1.8:1）
    highp float ang = fract(uSeed * 0.6180339887) * 3.14159265;
    float ca = cos(ang);
    float sa = sin(ang);
    vec2 st = vec2(ca * vLocal.x - sa * vLocal.y, sa * vLocal.x + ca * vLocal.y);
    st.y *= 1.8;
    vec3 q = vec3(st * 2.1, uSeed);
    // R388 刀2 频率锁定：q.xy 系数标度=同一噪声场 zoom（尺度变换非基因改变——
    // 丝缕噪声频率×scale：远小 quad 低频=粗丝留空大、丝缕像素宽恒定；
    // warp/den/ridge 全域随 zoom 一致缩放=图案级尺度变换）
    q.xy *= uFreqScale;
    vec3 warp = vec3(
        fieldAt(q + vec3(0.0, uTime * 0.013, 0.0)),
        fieldAt(q + vec3(5.2, 1.3, -uTime * 0.011)),
        0.0);
    float den = fieldAt(q + warp * 1.4 + vec3(0.0, 0.0, uTime * 0.017));
    // R358 ①丝缕层：脊线噪声天然产纤维脉络（峰值 alpha 只落在纤维丝上）
    float ridge = 1.0 - abs(2.0 * fieldAt(q * 2.3 + warp * 1.1) - 1.0);
    float d2 = max(den, ridge * 0.85);
    // R388 刀2 稀疏化配套：远档（scale<1）alpha 门下沿随标度抬升（0.40→0.40+
    // 0.24·(1−scale)=floor 档 0.556——只留最亮数缕，稀粗丝观感的对比半边）；
    // scale=1 位移零=R358 门基因 0.40 恒等（家族对表在两半边共在窗恒等成立）
    float gateLo = 0.40 + 0.24 * (1.0 - uFreqScale);
    float a = smoothstep(gateLo, 0.82, d2) * fall * uAlpha;
    // R358 ③暗尘埃带：纤维亮脊旁刻暗缝（减法式剪影——雾体内部结构，零新绘制层）
    float lane = smoothstep(0.62, 0.88, ridge) * (1.0 - den);
    a *= 1.0 - 0.55 * lane;
    // R358 ④雾心色温分层（零新色源）：丝亮隙暗的明度纵深——色相仍=uColor 单源
    vec3 col = uColor * (0.70 + 1.05 * d2);
    if (a < 0.004) discard;
    fragColor = vec4(col, a);
}
"""

    // ==================== R281 后处理三件套（任务③：bloom+镜头光斑——FBO 管线） ====================

    /** 全屏三角形顶点着色器（[-1,-1]/[3,-1]/[-1,3] 一三角盖全屏——零四边形浪费） */
    const val POST_VS = """#version 300 es
layout(location=0) in vec2 aPos;
out vec2 vUV;
void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * 可分离高斯模糊（quarter 分辨率 9-tap——横纵两趟 ping-pong）。
     * **R281 Mali 税收口**：亮部提取并入首趟（uThresh>0 时软阈值——brightFBO 整级
     * 退役：6 次 FBO 切换→3 次，Mali 瓦片冲刷税减半）。
     */
    const val BLUR_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uDir;
uniform float uThresh; // >0=首趟带亮部提取（软阈值）
in vec2 vUV;
out vec4 fragColor;
void main() {
    vec3 s = texture(uTex, vUV).rgb * 0.227027;
    s += texture(uTex, vUV + uDir * 1.3846).rgb * 0.316216;
    s += texture(uTex, vUV - uDir * 1.3846).rgb * 0.316216;
    s += texture(uTex, vUV + uDir * 3.2308).rgb * 0.070270;
    s += texture(uTex, vUV - uDir * 3.2308).rgb * 0.070270;
    if (uThresh > 0.0) {
        float lum = dot(s, vec3(0.2126, 0.7152, 0.0722));
        s *= smoothstep(uThresh, uThresh + 0.25, lum);
    }
    fragColor = vec4(s, 1.0);
}
"""

    /**
     * 合成（场景+bloom 加性+**程序镜头光斑**）：
     * top3 亮星（装配层收集）屏位/色/强度/正对度 uniform——正对（近屏心）亮星时
     * 主晕+鬼影串（源→屏心轴对称排布，解析高斯斑零纹理）+晕环峰现；
     * uAspect 校正=鬼影恒圆。
     */
    const val COMPOSITE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomGain;
uniform vec4 uFlarePos[3];
uniform vec3 uFlareColor[3];
uniform float uAspect;
uniform float uBodyBloomCut; // R412d 贴脸恒星本体 bloom 适度削减比（0=旧行为）
in vec2 vUV;
out vec4 fragColor;
void main() {
    vec3 sc = texture(uScene, vUV).rgb;
    vec3 bl = texture(uBloom, vUV).rgb;
    // R412d：大面积亮盘（贴脸恒星本体：模糊后 bloom≈原亮度）处削减泛光，保表面细节；
    // 远景小亮星核 bloom≪原亮度→比值门关闭=原样（光晕外圈不受影响）
    float lS = dot(sc, vec3(0.2126, 0.7152, 0.0722));
    float lB = dot(bl, vec3(0.2126, 0.7152, 0.0722));
    float body = smoothstep(0.55, 0.90, lB / max(lS, 0.001)) * smoothstep(0.45, 0.80, lS);
    vec3 col = sc + bl * uBloomGain * (1.0 - uBodyBloomCut * body);
    vec2 ndc = vec2(vUV.x * 2.0 - 1.0, 1.0 - vUV.y * 2.0);
    ndc.x *= uAspect;
    for (int i = 0; i < 3; i++) {
        float inten = uFlarePos[i].z * uFlarePos[i].w;
        if (inten <= 0.004) continue;
        vec2 src = uFlarePos[i].xy;
        src.x *= uAspect;
        vec3 fc = uFlareColor[i];
        // 主晕（源位紧致高斯）
        float d0 = length(ndc - src);
        col += fc * exp(-d0 * d0 * 260.0) * 0.30 * inten;
        // 鬼影串（源→屏心轴：源与对侧排布）
        vec2 axis = -src;
        col += fc * exp(-pow(length(ndc - (src + axis * 0.45)) * 30.0, 2.0)) * 0.16 * inten;
        col += fc.brg * exp(-pow(length(ndc - (src + axis * 0.80)) * 22.0, 2.0)) * 0.13 * inten;
        col += fc * exp(-pow(length(ndc - (src + axis * 1.30)) * 16.0, 2.0)) * 0.10 * inten;
        // 晕环（轴上 1.05 处的细环）
        float dr = abs(length(ndc - (src + axis * 1.05)) - 0.16);
        col += fc * exp(-dr * dr * 22000.0) * 0.12 * inten;
    }
    fragColor = vec4(col, 1.0);
}
"""

    // ==================== R383 P3 raymarch 体积（design-nebula §5.2 贴脸档） ====================

    /**
     * R383 深空复合体 raymarch 体积趟 FS（半/quarter 分辨率 FBO——步数分档 uniform）。
     * **精度 R392 判词③：precision highp float 保留**——保留理由已从 R357 的噪声
     * ALU（hash3 中间积约 25 万在 fp16 溢出塌噪声，彼时精度档=纹理存亡）换为
     * **射线几何/深度比较**（光线重建/球交/uVP 深度裁剪需 fp32，与噪声无关）；
     * 噪声值改由 uNoiseVol 烘焙体采样（8bit 归一化，mediump 采样天然够用）。
     * 密度=**NEBULA_FS 同族噪声场**（**R392 载体革命**：噪声载体=NoiseVolumeBaker
     * 烘焙 64³ 3D 纹理，两 FS 各以 fieldAt 单源采样同一纹理单件；构造基因
     * （2.1 与 0.12,1.0 与 0.40,0.82 与 0.85 与 0.62,0.88 与 0.55 与 0.70,1.05）
     * 两半边对表仍在，由 R383WiringSourceTest 常量对表锚锁定——NEBULA_FS 文本
     * 零碰红线所迫，单源锁在测试侧，两文件任一侧漂移即红；**载体不再逐字共享**
     * ——$NOISE_3D GLSL 噪声库从本块摘除，逐像素 ALU 求值退役）×径向
     * falloff×层调制（低频壳场 0.55..1.0——§4.2 层结构体积化）。
     * 累积=前向背面离散合成：自辉 acc+消光 τ（每步 aStep=dens×ds×σ；σ=−ln(1−
     * uAlpha)×0.5 标定=**穿心直径满密度积分 α 恰=uAlpha——等效帽 §4.3 结构性
     * 成立**，uAlpha 装配侧已含 crossfade 权与 ramp）。抖动=ign interleave
     * gradient noise（**静态 hash 无时间项——R343 零闪烁纪律**）防带状；早出
     * trans<0.05（α≥0.95 护栏——等效帽 0.12 下恒不触发的结构纪律，如实注记）。
     * 深度感知：uSceneDepth（场景深度纹理 NEAREST）逐样本裁剪——样本 z01 ≥
     * sceneZ=被前景几何遮挡不累积（穿云前方星减光由合成趟 over 达成；偏红=P4
     * 语义未接）。输出=premultiplied rgb+α（VOLUME_COMPOSITE_FS over 口径）。
     * 各向异性注记：NEBULA_FS 的 2D 局部系拉伸（st.y×1.8）是 billboard 空间
     * 结构，体积档形态各向异性由密度场本身承担（同族基因非逐结构复制）。
     */
    const val RAYMARCH_FS = """#version 300 es
precision highp float;
uniform vec3 uCamPos;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform vec3 uCamFwd;
uniform vec2 uTanHalf;
uniform mat4 uVP;
uniform highp sampler2D uSceneDepth; // R383 深度纹理采样（ES3.0 采样器默认 lowp 损深度值——显式 highp）
uniform vec3 uCenter;
uniform float uRadius;
uniform vec3 uColorHa;
uniform vec3 uColorOiii;
uniform float uHaMix;
uniform float uAlpha;
uniform float uSeed;
uniform float uTime;
uniform int uSteps;
in vec2 vUV;
out vec4 fragColor;
uniform mediump sampler3D uNoiseVol;
// R392 载体函数：烘焙体采样（窗 16 q 单位周期——REPEAT 平铺无缝；0.0625=1/16
// 与 NoiseVolumeBaker.WINDOW 单源对表）；种子=平移窗（原 z=uSeed 切片语义的
// 载体内等价身份映射——场统计基因不变）
float fieldAt(highp vec3 p) {
    highp vec3 so = fract(vec3(uSeed * 0.1031, uSeed * 0.173, uSeed * 0.3113));
    // R404 textureLod 显式 LOD0:隐式导数在 RAYMARCH_FS 发散循环(逐像素 t1/t0
    // 不同 + break 分支)的 lane 上=ES3 未定义行为→mip 链塌到 1³ 全局均值→密度
    // 门饱和=无结构白体(用户白色圆饼实锚——两域 fieldAt 同体共用,显式 LOD0=
    // 64³ 原始纹理零依赖导数,唯一正解,语义单源不变)
    return textureLod(uNoiseVol, p * 0.0625 + so, 0.0).r;
}
// R383 ign 蓝噪 hash 抖动（interleave gradient noise——静态无时间项=R343 零闪烁）
float ign(vec2 p) {
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}
void main() {
    // 光线重建（GlCameraMatrix 同约定：NDC y 向上；tan 半角=NDC→相机系方向比）
    vec2 ndc = vUV * 2.0 - 1.0;
    vec3 dir = normalize(uCamFwd + uTanHalf.x * ndc.x * uCamRight + uTanHalf.y * ndc.y * uCamUp);
    // 射线-球求交（复合体包围球；相机在球内时 t0 钳 0）
    vec3 oc = uCamPos - uCenter;
    float bb = dot(oc, dir);
    float cc = dot(oc, oc) - uRadius * uRadius;
    float disc = bb * bb - cc;
    if (disc <= 0.0) discard;
    float sq = sqrt(disc);
    float t0 = max(-bb - sq, 0.0);
    float t1 = -bb + sq;
    if (t1 <= t0) discard;
    // 场景深度（前景几何遮挡裁剪终点——穿云前方星不被体积覆盖）
    float sceneZ = texture(uSceneDepth, vUV).r;
    // 步进（步数分档；蓝噪 hash 抖动起点防带状）
    float dt = (t1 - t0) / float(uSteps);
    float t = t0 + dt * ign(gl_FragCoord.xy);
    vec3 acc = vec3(0.0);
    float trans = 1.0;
    // τ 标定：穿心直径满密度积分 α 恰=uAlpha（等效帽——uAlpha 装配侧已含 crossfade 权）
    float sigma = -log(1.0 - min(uAlpha, 0.95)) * 0.5;
    for (int i = 0; i < uSteps; i++) {
        if (t >= t1) break;
        vec3 p = uCamPos + dir * t;
        vec3 q = (p - uCenter) / uRadius;
        float r = length(q);
        if (r < 1.0) {
            // 径向 falloff（NEBULA_FS 同族基因：1−smoothstep(0.12, 1.0, r)）
            float fall = 1.0 - smoothstep(0.12, 1.0, r);
            if (fall > 0.004) {
                // 层调制（§4.2 层结构体积化——低频壳场 0.55..1.0）
                float shell = 0.55 + 0.45 * fieldAt(vec3(q * 1.7) + vec3(0.0, 0.0, uSeed + 7.7));
                // 密度场（NEBULA_FS 同族基因：q×2.1+种子 z+域扭曲+脊线+max(den,ridge×0.85)）
                vec3 fq = q * 2.1 + vec3(0.0, 0.0, uSeed);
                vec3 warp = vec3(
                    fieldAt(fq + vec3(0.0, uTime * 0.013, 0.0)),
                    fieldAt(fq + vec3(5.2, 1.3, -uTime * 0.011)),
                    0.0);
                float den = fieldAt(fq + warp * 1.4 + vec3(0.0, 0.0, uTime * 0.017));
                float ridge = 1.0 - abs(2.0 * fieldAt(fq * 2.3 + warp * 1.1) - 1.0);
                float d2 = max(den, ridge * 0.85);
                // 暗尘埃带（NEBULA_FS 同族基因：减法式剪影）
                float lane = smoothstep(0.62, 0.88, ridge) * (1.0 - den);
                float dens = smoothstep(0.40, 0.82, d2) * fall * shell * (1.0 - 0.55 * lane);
                if (dens > 0.004) {
                    // 深度感知：样本在前景几何之后（z01 ≥ sceneZ）=被遮挡不累积
                    vec4 clip = uVP * vec4(p, 1.0);
                    float z01 = clip.z / clip.w * 0.5 + 0.5;
                    if (z01 < sceneZ) {
                        // Hα/OIII fBm 权混场（§4.1——同族 0.6 抖动窗；零 hue(t)）
                        float w = clamp(uHaMix + (fieldAt(fq * 0.8 + vec3(3.3, 7.7, 1.1)) - 0.5) * 0.6, 0.0, 1.0);
                        vec3 col = mix(uColorOiii, uColorHa, w) * (0.70 + 1.05 * d2);
                        // 前向背面离散合成：累积自辉+消光 τ
                        float aStep = dens * dt / uRadius * sigma;
                        acc += trans * aStep * col;
                        trans *= 1.0 - aStep;
                        if (trans < 0.05) break; // α≥0.95 早出护栏（等效帽 0.12 下恒不触发——结构纪律）
                    }
                }
            }
        }
        t += dt;
    }
    float alpha = 1.0 - trans;
    if (alpha < 0.004) discard;
    fragColor = vec4(acc, alpha); // premultiplied（合成趟 over 口径）
}
"""

    /**
     * R383 体积合成趟 FS（深度感知合成——挂位=blur 前；无噪声件守 mediump=R357
     * 性能账）：场景+体积 premultiplied over——`s×(1−α)+acc`（穿云前方星被体积
     * 减光=over 天然达成；偏红 tint=P4 语义未接）；输出进 sceneFbo2（全幅色
     * ping-pong——后续 blur/合成趟读换装后的 front 纹理）；α 恒 1（不透明场景）。
     */
    const val VOLUME_COMPOSITE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uScene;
uniform sampler2D uVolume;
in vec2 vUV;
out vec4 fragColor;
void main() {
    vec3 s = texture(uScene, vUV).rgb;
    vec4 v = texture(uVolume, vUV); // premultiplied rgb + α（raymarch 趟产出）
    fragColor = vec4(s * (1.0 - v.a) + v.rgb, 1.0);
}
"""

    // ==================== 编译/链接壳 ====================

    /** 编译+链接（失败抛 IllegalStateException 带 info log——TasteGlRenderer 捕获走回退账） */
    fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("R277 GL 链接着色器失败: $log")
        }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, src)
        GLES30.glCompileShader(id)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(id)
            GLES30.glDeleteShader(id)
            throw IllegalStateException("R277 GL 编译着色器失败(type=$type): $log")
        }
        return id
    }

    /** ARGB IntArray → GL 纹理（直接字节缓冲 RGBA 排——Android GLES 只收 direct buffer） */
    fun uploadTexture(pixels: IntArray, w: Int, h: Int): Int {
        val buf = java.nio.ByteBuffer.allocateDirect(pixels.size * 4)
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
            buf.put(((p ushr 24) and 0xFF).toByte())
        }
        buf.rewind()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        return ids[0]
    }

    /** R392 3D 噪声体上传（GL_R8 单通道——LINEAR 三线性+三向 REPEAT 平铺+mipmap 链；句柄纪律同 uploadTexture） */
    fun uploadTexture3D(data: ByteArray, size: Int): Int {
        val buf = java.nio.ByteBuffer.allocateDirect(data.size)
        buf.order(java.nio.ByteOrder.nativeOrder())
        buf.put(data)
        buf.flip()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_R8, size, size, size, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_3D)
        return ids[0]
    }
}
