package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R325 诞生宣判 wiring 源码锚（R317bWiringSourceTest 同款 user.dir 源定位模式
 * ——UI 侧半场无 JVM 直测面（Android File/Log/DeepSearchIndex 依赖），以源锚锁
 * 「诞生注记进星图」的结构件；行为半体由 TasteBirthJudgmentTest（判据族 core）
 * 承重）：
 *  - **诞生半场结构**（runBirthVerdictR325）：tiers() 批量读 IO 协程+BirthEvidence
 *    全量收集面（raws 金矿账/tagFingerprints 账本/indexTiering 开关——装配口
 *    零改动）+judgeBirthStates/birthPoolOf core 单口消费+池内诞生史点查；
 *  - **合流互斥**（migrateLifeVerdictsR325 诞生层）：isDeathSideKind 单口在最终
 *    kind 上第二道剔除；
 *  - **复用门 birth 冻结车道**：birth 注记冻结+六态照算（fresh kind 不冻结）；
 *  - **跃迁 diff 三通道**：通道①点亮判别（lit toast 年轻变体——零缓存依赖）/
 *    通道②③diffBirthTransitionsR325+reportBirthTransitionsR325（批 3 入队口
 *    **已接 GRAVITY_WAVE**——R328 第二阶段接线：函数体=enqueueGravityWave，锚
 *    随批迁移（R327 期反向锚翻正）；planetsForming 位不入队=贴脸演出归批 C）；
 *  - **主方法零接线铁令**：主 composable 本体零诞生接线标记；
 *  - **信息卡三穿透**：StarInfoCard→VitalsRows→factLine birth/nearDeathLabel。
 */
class R325WiringSourceTest {

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

    private fun tms(): String {
        val file = File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt")
        assertTrue("TasteMapScreen.kt 必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    // ==================== 诞生半场结构（runBirthVerdictR325） ====================

    @Test
    fun `诞生半场-tiers批量读与证据收集面在场`() {
        val t = tms()
        // 唯一新 IO=tiers() 批量读（毫秒级单查——IO 协程段+失败诚实缺据空表）
        assertTrue(
            "诞生半场 tiers() 批量读丢失（唯一新 IO）",
            Regex("vm\\.deepIndex\\.tiers\\(\\)").containsMatchIn(t),
        )
        // 证据收集面：raws 金矿账（goldItems）+idxCount+tagFingerprints 账本+
        // indexTiering 开关——装配口零改动（字段直供，零新收集函数）
        for (anchor in listOf(
            "goldItems = raw\\?\\.goldItems",
            "probeItems = raw\\?\\.idxCount",
            "ledgerTags = tagFp\\[s\\.chatId\\]",
            "tieringEnabled = tieringEnabled",
        )) {
            assertTrue("BirthEvidence 收集面锚丢失：$anchor", Regex(anchor).containsMatchIn(t))
        }
        // core 单口消费（判据族单一出处——零另写判据）
        assertTrue(
            "judgeBirthStates core 单口消费丢失",
            Regex("TasteAstroPhysics\\.judgeBirthStates\\(evidence\\)").containsMatchIn(t),
        )
        assertTrue(
            "birthPoolOf 池选消费丢失（先池选后点查）",
            Regex("TasteAstroPhysics\\.birthPoolOf\\(judged\\)").containsMatchIn(t),
        )
        // 池内诞生史点查（S3 件第四消费——逐颗可取消）
        assertTrue(
            "诞生史池点查丢失（chatPublishHistogram 第四消费）",
            Regex("vm\\.deepIndex\\.chatPublishHistogram\\(b\\.chatId\\)").containsMatchIn(t),
        )
        // 诚实降级：诞生半场失败=终态照常+注记零（runCatching 包裹）
        assertTrue(
            "诞生半场 runCatching 诚实降级丢失",
            Regex("runCatching \\{ runBirthVerdictR325\\(").containsMatchIn(t),
        )
    }

    @Test
    fun `合流互斥-诞生注记后置isDeathSideKind单口`() {
        val t = tms()
        // 互斥第二道：最终 kind 上剔除（migrateLifeVerdictsR325 诞生层——互斥规则单点）
        assertTrue(
            "migrateLifeVerdictsR325 诞生层互斥门丢失（死星不坐月子）",
            Regex("if \\(b == null \\|\\| TasteAstroPhysics\\.isDeathSideKind\\(s\\.kind\\)\\) s else s\\.copy\\(birth = b\\)").containsMatchIn(t),
        )
        // birthById 成表只含非 NOT_BIRTH（缺据零注记）
        assertTrue(
            "注记成表 NOT_BIRTH 过滤丢失",
            Regex("if \\(b\\.stage == TasteAstroPhysics\\.BirthStage\\.NOT_BIRTH\\) continue").containsMatchIn(t),
        )
    }

    // ==================== 复用门 birth 冻结车道（诞生注记冻结、六态照算） ====================

    @Test
    fun `复用门-birth冻结车道与互斥门`() {
        val t = tms()
        // birth 独立冻结车道：六态档 kind 不冻结（fresh 重算），birth 冻结
        assertTrue(
            "复用门 birth 冻结车道丢失（诞生注记冻结、六态照算）",
            Regex("if \\(b == null \\|\\| s\\.birth == b \\|\\| TasteAstroPhysics\\.isDeathSideKind\\(s\\.kind\\)\\)").containsMatchIn(t),
        )
        // verdictApplied 单旗覆盖两侧（复用门判定语句原样——单旗不双旗）
        assertTrue(
            "复用门单旗判定丢失",
            Regex("cached\\.stars\\.isNotEmpty\\(\\) && cached\\.verdictApplied").containsMatchIn(t),
        )
    }

    // ==================== 跃迁 diff 三通道（入队口留位——本批不接导演器） ====================

    @Test
    fun `跃迁diff-三通道结构与入队口接线`() {
        val t = tms()
        // 通道②③：diff 纯函数+上报函数在场（批 3 入队口）
        for (fn in listOf("diffBirthTransitionsR325", "reportBirthTransitionsR325")) {
            assertTrue(
                "跃迁 diff 件 $fn 丢失（三通道②③）",
                Regex("private fun $fn\\(").containsMatchIn(t),
            )
        }
        // 旁路 diff 挂接（缓存 birth vs 宣判 birth 对位比对——跨会话窗；R328 批3
        // 接线后 director/nowMs 透传——runGalaxyLoadR254 尾参透传）
        assertTrue(
            "旁路跃迁 diff 挂接丢失",
            Regex("diffBirthTransitionsR325\\(cached\\.stars, verdictMap\\.stars\\),").containsMatchIn(t) &&
                Regex("reportBirthTransitionsR325\\(\\s*diffBirthTransitionsR325").containsMatchIn(t),
        )
        // 通道①：点亮判别（youngIds 年轻位+instantLine birth 尾参——会话内零缓存依赖）
        assertTrue(
            "点亮年轻判别丢失（通道①）",
            Regex("AstroFacts\\.pickToastStar\\(newly\\.map \\{ it\\.chatId to it\\.kind \\}, youngIdsR327\\)").containsMatchIn(t) &&
                Regex("instantLine\\(ps\\.kind, ps\\.itemsCount, ps\\.recent7, dAgo, fDays, ps\\.birth\\)").containsMatchIn(t),
        )
        // **R328 批3 入队已接**（R320 套回后兑现——r325 C①-3「跃迁演出走 Directive
        // 参数位」）：函数体=UniverseDirector.enqueueGravityWave（GRAVITY_WAVE 同口）
        val fnStart = t.indexOf("private fun reportBirthTransitionsR325(")
        assertTrue("reportBirthTransitionsR325 未找到", fnStart >= 0)
        // 函数体粗截（下一个 private 声明前）——锚入队在位（R327 期反向锚「零导演器
        // 引用」随批3 接线翻正——锚随行为，R317b 收编先例同款）
        val bodyEnd = t.indexOf("\nprivate ", fnStart + 10)
        val body = t.substring(fnStart, if (bodyEnd > 0) bodyEnd else t.length)
        assertTrue(
            "reportBirthTransitionsR325 函数体缺 UniverseDirector.enqueueGravityWave 入队（批3 接线=R328 第二阶段已落）",
            Regex("UniverseDirector\\.enqueueGravityWave\\(director, nowMs, r282DirectorCfg, t\\.chatId, BIRTH_TRANSITION_WAVE_I01\\)").containsMatchIn(body),
        )
        // planetsForming 位不入队（贴脸演出归批 C——函数体零 forming 入队调用）
        assertTrue(
            "planetsForming 入队违例（r325 C②：贴脸件不排导演器）",
            !Regex("enqueueGravityWave\\([^)]*forming").containsMatchIn(body),
        )
    }

    // ==================== 信息卡三穿透（birth→factLine 四行） ====================

    @Test
    fun `信息卡-birth三穿透与最近邻循环行`() {
        val t = tms()
        // 三穿透链：StarInfoCard 计算→VitalsRows 尾参→factLine 传参
        assertTrue(
            "StarInfoCard 最近邻计算丢失（循环行原料层）",
            Regex("nearestDeathSideLabelR325\\(st, starsById\\.values\\)").containsMatchIn(t),
        )
        assertTrue(
            "VitalsRows nearDeathLabel 尾参穿透丢失",
            Regex("nearDeathLabel: String\\? = null").containsMatchIn(t) &&
                Regex("nearDeathLabel = nearDeathLabelR327").containsMatchIn(t),
        )
        assertTrue(
            "factLine birth/nearDeathLabel 传参丢失",
            Regex("birth = st\\.birth").containsMatchIn(t) &&
                Regex("nearDeathLabel = nearDeathLabel").containsMatchIn(t),
        )
        // 最近邻件：isDeathSideKind 单口门+sp 距离门常量
        assertTrue(
            "最近邻 isDeathSideKind 单口门丢失",
            Regex("!TasteAstroPhysics\\.isDeathSideKind\\(s\\.kind\\)\\) continue").containsMatchIn(t),
        )
        // 最近邻距离门常量（空间共在门）——R323 数值收口 core 单源
        // EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2（同值 300²：循环行门与发射星云
        // 星胚脉冲共在窗同数字单一出处——锚随行为翻正）
        assertTrue(
            "最近邻距离门常量丢失（空间共在门——core 单源引用）",
            Regex("BIRTH_CYCLE_NEIGHBOR_D2 = EmissionNebulaFx\\.CYCLE_PULSE_WINDOW_D2").containsMatchIn(t),
        )
    }

    // ==================== 主方法零接线铁令（诞生标记版） ====================

    /** brace 配对量主函数体（TasteMapMethodBudgetTest 同款口径） */
    private fun mainFunctionBody(file: File): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { Regex("^fun TasteMapScreen\\(").containsMatchIn(it) }
        require(start >= 0) { "主 composable TasteMapScreen 未找到" }
        var bodyStart = -1
        for (j in start until lines.size) {
            if (Regex("\\)\\s*\\{\\s*(//.*)?$").containsMatchIn(lines[j])) { bodyStart = j; break }
        }
        require(bodyStart >= 0) { "主函数体开括号未找到" }
        val sb = StringBuilder()
        var depth = 0
        for (j in bodyStart until lines.size) {
            val code = lines[j].split("//")[0]
            depth += code.count { it == '{' } - code.count { it == '}' }
            sb.appendLine(lines[j])
            if (depth == 0 && j > bodyStart) return sb.toString()
        }
        error("主函数体闭括号未配平")
    }

    @Test
    fun `主方法零接线-诞生接线不进主composable本体`() {
        val body = mainFunctionBody(File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt"))
        // 诞生接线全在顶层函数/数据模型/顶层 composable 默认参——主方法本体零
        // birth 标记（回填主方法=寄存器压力回顶——本锚即红）
        assertTrue(
            "主 composable 本体出现诞生接线标记（主方法零接线铁令被破——迁顶层函数！）",
            !Regex("runBirthVerdictR325|BirthEvidence|birthById|nearestDeathSide|youngIdsR327|birth = st\\.birth").containsMatchIn(body),
        )
    }
}
