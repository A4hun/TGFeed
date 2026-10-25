package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R349 天体全员 3D 化 LOD 形态线 wiring 源码锚（R342WiringSourceTest 同款 user.dir
 * 源定位模式——GL 渲染管线无 JVM 直测面，以源锚锁**政策件单源+门槛换轨+blend
 * 双态+探针**的结构面；数值半体由 TasteLodPolicyTest/GlFrameBuilderTest/
 * TasteStarGlowTest 三件承重）：
 *  - **绝迹锚①双端镜像常量族**：GlFrameBuilder 三门槛（MICRO_STAR_PX 2.2/
 *    STAR_BALL_MIN_R 30/STAR_CORE_MIN_R 10）+TasteStarGlow 域界五常量与旧五档
 *    id——**签名级断言**（const val 声明绝迹；退役注释留裁决史实不自伤=R348 先例；
 *    「同值同改」双端镜像族终结——r341 §7.1 常量退役列）；
 *  - **绝迹锚②旧字面门**：换轨前裸字面（r≥6 流星岩/r≥2.2 黑洞/rP<2.5 行星/
 *    r<2.5 星团）绝迹——r349_notes §3 门槛表「旧门」列全灭；
 *  - **接线锚③形态线三态+blend 双态**：buildStarBody 主路（k=blend01(r,effFloor,
 *    ceil)/低模双闸/R355 ambient 平涂退役=槽 22 恒 0（真 3D 即刻生效——blend 只留
 *    点淡出侧）/帽满回点档全亮点+statLodDropped 诚实记账）；
 *  - **接线锚④全类门槛换轨消费面**：微星并点/行星/黑洞视界淡入/星团/流星岩/
 *    星风流门（r349_notes §3 门槛表「新门」列逐行在场；R355 dp 化=帧字段两端）；
 *  - **接线锚⑤芒闪退场+政策件单源**：twinkle ×pointFadeK/芒让位 pointFadeK
 *    （resolved 无芒无闪 r341 §5.2）/TasteStarGlow 委托三式+cepheid 球门；
 *  - **接线锚⑥探针+回退序+R321 槽位纪律**：statLodLine 节流行/回退三入参字段
 *    （+R355 密度入参）/渲染器 level 0..4 状态机推送+升降级日志/lowSphereBuf
 *    槽 22=R355 退役位恒 0 槽 23 预留恒 0（全槽无条件写）/低模球籍预选。
 */
class R349WiringSourceTest {

    private val mainSourceRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = File(dir, "src/main/java")
                if (candidate.isDirectory) return candidate
                val nested = File(dir, "app/src/main/java")
                if (nested.isDirectory) return nested
                dir = dir.parentFile ?: return@repeat
            }
            error("找不到主源码根（user.dir=${System.getProperty("user.dir")}）")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun glFrameBuilder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
    private fun starGlow(): String = src("dev/tgfeed/core/tastemap/TasteStarGlow.kt")
    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")

    // ==================== ① 绝迹锚：双端镜像常量族（签名级） ====================

    @Test
    fun `绝迹-双端镜像常量族与旧五档id（签名级史实注释不自伤）`() {
        val fb = glFrameBuilder()
        // GlFrameBuilder 三门槛镜像常量（r349_notes §0-5——旧 24~30 crossfade 时代的
        // 双端镜像纪律随形态线单源收口终结）
        for (name in listOf("MICRO_STAR_PX", "STAR_BALL_MIN_R", "STAR_CORE_MIN_R")) {
            assertTrue(
                "GlFrameBuilder 镜像常量 $name 声明应绝迹（R349 单源收口 TasteLodPolicy）",
                !Regex("const val $name").containsMatchIn(fb),
            )
        }
        val sg = starGlow()
        // TasteStarGlow 域界五常量+旧五档 id（形态档三态=政策件 formTierOf 单源；
        // TIER_WARM/COOL/WHITE 为色温桶异族不在绝迹域）
        for (name in listOf(
            "MICRO_TIER_MAX_PX", "FAR_TIER_MAX_PX", "CLOSEUP_TIER_MIN_PX",
            "CROSSFADE_START_PX", "CROSSFADE_END_PX",
            "TIER_NONE", "TIER_FAR", "TIER_MID", "TIER_CROSSFADE", "TIER_CLOSEUP",
        )) {
            assertTrue(
                "TasteStarGlow 域界常量 $name 声明应绝迹（政策件单源——「同值同改」镜像族终结）",
                !Regex("const val $name").containsMatchIn(sg),
            )
        }
    }

    // ==================== ② 绝迹锚：旧字面门（§3 门槛表旧门列全灭） ====================

    @Test
    fun `绝迹-旧字面门（r349门槛表旧门列全灭）`() {
        val fb = glFrameBuilder()
        assertTrue("流星体岩石旧门 r≥6f 应绝迹（新门=POINT_CEIL_PX=1.5）",
            !Regex("Meteor && r >= 6f").containsMatchIn(fb))
        assertTrue("黑洞视界/盘旧门 r≥2_2f 应绝迹（新门=effFloor=2）",
            !Regex("r >= 2\\.2f").containsMatchIn(fb))
        assertTrue("行星旧门 rP<2_5f 应绝迹（新门=effFloor+blend）",
            !Regex("rP < 2\\.5f").containsMatchIn(fb))
        assertTrue("星团成员云旧门 r<2_5f 应绝迹（新门=effFloor=2）",
            !Regex("if \\(r < 2\\.5f\\)").containsMatchIn(fb))
    }

    // ==================== ③ 接线锚：形态线三态+blend 双态（主路） ====================

    @Test
    fun `接线-形态线三态与blend双态（buildStarBody主路）`() {
        val fb = glFrameBuilder()
        // k=blend01(r, effFloor, ceil)——形态线单源消费（点/带/3D 三态的 k；
        // R355 dp 化=窗两端帧字段透传）
        assertTrue(
            "buildStarBody 必须消费政策件 blend01(r, effFloor, ceil)",
            Regex("val k = TasteLodPolicy\\.blend01\\(r, lodSolidFloorPx, lodPointCeilPx\\)").containsMatchIn(fb),
        )
        // 球形态可用性先判（低模=运行帽+预选籍双闸；全模=MAX_SPHERES 帽）
        assertTrue(
            "低模球籍双闸（运行帽+lowSphereOk 预选）必须在场",
            Regex("lowSphereCount < lodInstanceCap && si >= 0 && si < lowSphereOk\\.size && lowSphereOk\\[si\\]").containsMatchIn(fb),
        )
        // 点 alpha=α×0.92×pointK（被截断星回点档全亮点 pointK=1——帽界换籍 ≤8% 阶差机制；
        // R355 终裁 blend 只留点淡出侧：pointK=1−k=形态交接唯一存活侧）
        assertTrue(
            "点形态 alpha=α×0.92×pointK（pointFadeK 让位/帽满回点档全亮双语义）",
            Regex("val pointK = if \\(spherePossible\\) 1f - k else 1f").containsMatchIn(fb) &&
                Regex("alpha \\* 0\\.92f \\* pointK").containsMatchIn(fb),
        )
        // R355 终裁：ambient 平涂退役=低模球装填不带形态参（真 3D 即刻生效——
        // 球面明暗从第一像素在场；源锚细锚见 R355WiringSourceTest）
        assertTrue(
            "addLowSphere 调用不带 ambient 形态参（平盘期彻底删除）",
            Regex("addLowSphere\\(\\s*s, color, temp, alpha \\* k, seed, t, depth, focal, r, wx, wy, wz, extShiftK,\\s*si,\\s*\\)").containsMatchIn(fb),
        )
        // 帽满/无籍=诚实记账（不静默隐形）
        assertTrue(
            "statLodDropped 截断记账必须在场（超帽策略诚实账）",
            Regex("statLodDropped\\+\\+").containsMatchIn(fb),
        )
    }

    // ==================== ④ 接线锚：全类门槛换轨消费面（§3 表新门列） ====================

    @Test
    fun `接线-全类门槛换轨消费面（r349门槛表新门列逐行在场）`() {
        val fb = glFrameBuilder()
        // 微星并点 2.2→点档上限（R355 dp 化=帧字段 [lodPointCeilPx] 单源）
        assertTrue(
            "微星并点线必须=点档上限帧字段（并点云门槛随形态线统一）",
            Regex("r < lodPointCeilPx && canMicroMerge").containsMatchIn(fb),
        )
        // 行星 2.5→球门+blend（点 0.9×(1−k)/球 0.96×k）
        assertTrue(
            "行星 blend 接线（blend01(rP, effFloor, ceil)+点 ×(1−k)）必须在场",
            Regex("val k = TasteLodPolicy\\.blend01\\(rP, lodSolidFloorPx, lodPointCeilPx\\)").containsMatchIn(fb) &&
                Regex("appear \\* 0\\.9f \\* \\(1f - k\\)").containsMatchIn(fb),
        )
        // 黑洞视界/盘 2.2→球门+视界球 ×bhK blend 淡入
        assertTrue(
            "黑洞视界球 blend 淡入（bhK+addHorizon alpha 参数）必须在场",
            Regex("val bhK = TasteLodPolicy\\.blend01\\(r, lodSolidFloorPx, lodPointCeilPx\\)").containsMatchIn(fb) &&
                Regex("addHorizon\\(wx, wy, wz, r \\* depth / focal, bhK\\)").containsMatchIn(fb),
        )
        // 星团成员云 2.5→effFloor（成员粒径下限 0.7~1.4 保粒语义另锚）
        assertTrue(
            "星团云门必须=effFloor（随母体统一）",
            Regex("if \\(r < lodSolidFloorPx\\) return").containsMatchIn(fb),
        )
        // 流星体岩石 6→点档上限+blend（点+岩石双态）
        assertTrue(
            "流星岩门必须=点档上限帧字段 且 blend 双态（rockK）在场",
            Regex("METEOR && r >= lodPointCeilPx").containsMatchIn(fb) &&
                Regex("val rockK = TasteLodPolicy\\.blend01\\(r, lodSolidFloorPx, lodPointCeilPx\\)").containsMatchIn(fb),
        )
        // 星风流门随全模线（旧 STAR_CORE_MIN_R=10 中间档线退役——回退②同走）
        assertTrue(
            "星风流门必须=effLowMax（焦点件随全模线——诚实降级）",
            Regex("if \\(r >= lodLowMeshMaxPx && \\(s\\.chatId").containsMatchIn(fb),
        )
    }

    // ==================== ⑤ 接线锚：芒闪退场+政策件单源（六函数委托） ====================

    @Test
    fun `接线-芒闪退场与政策件单源（六函数委托）`() {
        val fb = glFrameBuilder()
        // resolved 无闪：twinkle ±8% ×pointFadeK（r341 §5.2 物理立规；R355 窗两端帧字段）
        assertTrue(
            "twinkle 幅度必须乘 pointFadeK（3D 档星不眨眼）",
            Regex("0\\.08f \\* TasteLodPolicy\\.pointFadeK\\(r, lodSolidFloorPx, lodPointCeilPx\\)").containsMatchIn(fb),
        )
        // resolved 无芒：星芒让位系数消费政策件（R339b pointFormAlpha 同义已单源）
        assertTrue(
            "星芒让位必须消费 pointFadeK（芒不骑球=红旗反向锚）",
            Regex("val pfa = TasteLodPolicy\\.pointFadeK\\(r, lodSolidFloorPx, lodPointCeilPx\\)").containsMatchIn(fb),
        )
        val sg = starGlow()
        // TasteStarGlow 委托三式（r341 §2.3 收编协议——零第二套；R355 阈值参透传
        // 缺省 mdpi 档——渲染器主路传帧字段）
        assertTrue(
            "crossfade01 必须=政策件 blend01 委托（窗位单源 [1_2,1_6]dp）",
            Regex("fun crossfade01\\(\\s*rPx: Float,\\s*solidFloorPx: Float = TasteLodPolicy\\.SOLID_FLOOR_DP,\\s*pointCeilPx: Float = TasteLodPolicy\\.POINT_CEIL_DP,\\s*\\): Float = TasteLodPolicy\\.blend01\\(rPx, solidFloorPx, pointCeilPx\\)").containsMatchIn(sg),
        )
        assertTrue(
            "pointFormAlpha 必须=政策件 pointFadeK 委托（同义单源收编）",
            Regex("fun pointFormAlpha\\(\\s*rPx: Float,\\s*solidFloorPx: Float = TasteLodPolicy\\.SOLID_FLOOR_DP,\\s*pointCeilPx: Float = TasteLodPolicy\\.POINT_CEIL_DP,\\s*\\): Float = TasteLodPolicy\\.pointFadeK\\(rPx, solidFloorPx, pointCeilPx\\)").containsMatchIn(sg),
        )
        assertTrue(
            "tierOf 必须=政策件 formTierOf 委托（三态互斥完备单源）",
            Regex("fun tierOf\\(\\s*rPx: Float,\\s*solidFloorPx: Float = TasteLodPolicy\\.SOLID_FLOOR_DP,\\s*pointCeilPx: Float = TasteLodPolicy\\.POINT_CEIL_DP,\\s*\\): Int = TasteLodPolicy\\.formTierOf\\(rPx, solidFloorPx, pointCeilPx\\)").containsMatchIn(sg),
        )
        // cepheid 球门 30→SOLID_FLOOR（搏动只在看得见球时成立；R355 阈值参透传）
        assertTrue(
            "cepheidRadiusCoef 球门必须=阈值参（旧 30px 贴脸窗退役）",
            Regex("if \\(rPx >= solidFloorPx\\) CEPHEID_R_COEF_CLOSEUP").containsMatchIn(sg),
        )
    }

    // ==================== ⑥ 接线锚：探针+回退序+R321 槽位纪律 ====================

    @Test
    fun `接线-LOD探针回退序与R321槽位纪律`() {
        val fb = glFrameBuilder()
        // statLodLine 探针（r341 §4.1 口径——纯计数+bias/帽记账 ~6s 节拍；R355
        // 密度+px 阈值行=两密度档对比断言原料）
        assertTrue(
            "statLodLine 探针节流（statTickR349%360）与日志行必须在场",
            Regex("statTickR349 % 360 == 0").containsMatchIn(fb) && fb.contains("R349 LOD: ") &&
                Regex("密度=\\$\\{lodDensity\\} 阈px=").containsMatchIn(fb),
        )
        // 回退三入参字段+密度入参（builder 纯入参——状态由渲染器持有）+帧首单口换算
        assertTrue(
            "回退三入参+密度入参字段+单口换算帧首算必须在场（bias/密度/帽旋钮单口）",
            Regex("var lodSolidBias = 1f").containsMatchIn(fb) &&
                Regex("var lodMeshBias = 1f").containsMatchIn(fb) &&
                Regex("var lodInstanceCap = MAX_SPHERE_I").containsMatchIn(fb) &&
                Regex("var lodDensity = 1f").containsMatchIn(fb) &&
                Regex("lodSolidFloorPx = TasteLodPolicy\\.effectiveSolidFloor\\(lodDensity, lodSolidBias\\)").containsMatchIn(fb) &&
                Regex("lodPointCeilPx = TasteLodPolicy\\.pointCeilPx\\(lodDensity\\)").containsMatchIn(fb),
        )
        val r = renderer()
        // 渲染器 LOD 回退序状态机（R376 重排：L1=①mesh∞几何降级零掉档/L2=②帽64/
        // L3=③帽32/L4=④bias1.5 形态降级最后手段——旧序 L1=bias=「3D 消失」根因
        // 正治；锚随行为显式改写非静默解除）+R355 密度推入（渲染器持态）
        assertTrue(
            "渲染器必须逐帧推送三旋钮+密度（lodLevel 状态机单源持有）",
            Regex("builder\\.lodMeshBias = if \\(lodLevel >= 1\\) Float\\.POSITIVE_INFINITY else 1f").containsMatchIn(r) &&
                Regex("builder\\.lodSolidBias = if \\(lodLevel >= 4\\) 1\\.5f else 1f").containsMatchIn(r) &&
                Regex("builder\\.lodInstanceCap = lodInstanceCapOf\\(lodLevel\\)").containsMatchIn(r) &&
                Regex("builder\\.lodDensity = lodDensity").containsMatchIn(r),
        )
        assertTrue(
            "渲染器升降级日志行必须在场（回退序可观测性——V52-③ 断言原料）",
            r.contains("R349 LOD回退: "),
        )
        // R321 槽位纪律：lowSphereBuf 全槽无条件写（槽 22=R355 退役位恒 0/槽 23=预留恒 0）
        assertTrue(
            "R321 槽位纪律：槽 22=退役位恒 0+槽 23 预留恒 0 必须在场（帧间残留结构性不可达）",
            Regex("lowSphereBuf\\[i \\+ 22\\] = 0f").containsMatchIn(fb) &&
                Regex("lowSphereBuf\\[i \\+ 23\\] = 0f").containsMatchIn(fb),
        )
        // 低模球籍预选（最小屏径优先出球籍——top-cap by rPx 插排）
        assertTrue(
            "selectLowSpheresR349 预选与出籍回写必须在场（超帽策略单口）",
            Regex("private fun selectLowSpheresR349\\(").containsMatchIn(fb) &&
                Regex("lowSphereOk\\[lowSelIdx\\[k\\]\\] = true").containsMatchIn(fb),
        )
    }
}
