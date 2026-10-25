package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R378b 发现网络治理（池公平分配）wiring 源码锚（R363/R364/R377e/R379 同款
 * user.dir 源定位模式）。四刀全案=r378_notes §2 设计定案（704f330）：
 *  - **刀1 保底份额**：收割 60/h 池原是单令牌桶（池内零车道零优先级零保底
 *    =R372 机制乙池结构病根：重启首时窗发现 30+白名单 32>60、轮首总余量 22
 *    实锚自洽——主收割页面拉取恒饿死=24h 零新答案结构性病根）→FloodBudget
 *    扩第三参 mainReservePercent（默认 0=其余车道实例语义零变）+tryAcquireAux
 *    （拒绝条件=窗内合计+本次+保底>总量）+HARVEST_MAIN_RESERVE_PERCENT=50
 *    单源（辅助合计 ≤30/60、主收割保底 ≥30/60；重分配≠加量，池总量常量零改）；
 *  - **刀2 Resolver 慢车道**：5 名/轮+300s 长间隔+TRANSIENT 与 FLOOD 同款
 *    break（网络劣化窗逐名连烧止血；消化速度受 L4 BACKGROUND 车道封顶零碰）；
 *  - **刀3 白名单缓存免重记**：peekBotUserId 命中零记账（记账与真实发送对齐，
 *    稳态 32 令牌/h 纯浪费归零）；
 *  - **刀4 liveAsk 自词排除**：GoldmineHooks 自词登记表 30min 窗计数口一刀切
 *    （R372 挂账双腿同治；链接侧/显式提交链零碰）。
 *  - 附 R379 挂账① 收口锚（配速拒发回退账本令牌——记账顺序正治）。
 *  - 例5=总请求率零上升负锚（红线：池常量原样+零新池+辅助上限只拒不放）。
 */
class R378bWiringSourceTest {

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

    /** 函数体切片：[signature] 起至 [endMarker]（首个匹配；两者都须在场） */
    private fun sliceBetween(text: String, signature: String, endMarker: String): String {
        val start = text.indexOf(signature)
        assertTrue("锚函数须在场：$signature", start >= 0)
        val end = text.indexOf(endMarker, start + signature.length)
        assertTrue("锚函数终点须在场：$endMarker", end > start)
        return text.substring(start, end)
    }

    /** 全仓（src/main）数一遍代码行里的 FloodBudget( 构造（注释/类声明行除外） */
    private fun countFloodBudgetConstructions(): Int {
        var count = 0
        mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("*") || t.startsWith("//")) return@forEach // KDoc/行注释（镜像声明不是构造）
                    if (t.startsWith("class FloodBudget(")) return@forEach // 类声明
                    if (Regex("""FloodBudget\(""").containsMatchIn(line)) count++
                }
            }
        return count
    }

    private fun harvester(): String = src("dev/tgfeed/core/feed/GroupHarvester.kt")
    private fun resolver(): String = src("dev/tgfeed/core/feed/EntityResolver.kt")
    private fun hooks(): String = src("dev/tgfeed/core/feed/GoldmineHooks.kt")
    private fun discovery(): String = src("dev/tgfeed/core/td/GatewayDiscovery.kt")
    private fun gateway(): String = src("dev/tgfeed/core/td/TdLibGateway.kt")
    private fun liveSearch(): String = src("dev/tgfeed/core/td/GatewayLiveSearch.kt")
    private fun botSearch(): String = src("dev/tgfeed/core/td/GatewayBotSearch.kt")
    private fun ledger(): String = src("dev/tgfeed/core/td/SearchQuotaLedger.kt")
    private fun prefs(): String = src("dev/tgfeed/core/prefs/AppPrefs.kt")
    private fun vm(): String = src("dev/tgfeed/core/feed/FeedViewModel.kt")
    private fun constants(): String = src("dev/tgfeed/core/td/GatewayConstants.kt")
    private fun onThisDay(): String = src("dev/tgfeed/core/onthisday/OnThisDaySource.kt")

    // ==================== ① 保底份额单源锚（刀1） ====================

    @Test
    fun `保底份额单源锚_常量换道五处与主道负锚`() {
        val gh = harvester()
        // 常量唯一出处+值 50（单源；防表达式偷梁换柱用全字面锚）
        assertEquals(
            "HARVEST_MAIN_RESERVE_PERCENT=50 须唯一出处（单源常量）",
            1,
            Regex("HARVEST_MAIN_RESERVE_PERCENT = 50").findAll(gh).count(),
        )
        // 收割池=全仓唯一带第三参的保底实例
        assertTrue(
            "harvestBudget 构造须接第三参（收割池唯一保底实例）",
            gh.contains("FloodBudget(WINDOW_MS, prefs.floodHarvestPerHour, HARVEST_MAIN_RESERVE_PERCENT)"),
        )
        // tryAcquireAux 全仓唯一定义（GroupHarvester.kt 的 FloodBudget 内——单源）
        var auxDefs = 0
        mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f -> auxDefs += Regex("fun tryAcquireAux\\(").findAll(f.readText()).count() }
        assertEquals("tryAcquireAux 须全仓唯一定义（FloodBudget 单源）", 1, auxDefs)
        // 换道五处逐个点名：discover×3 + whitelist×1 + resolver×1
        val disc = sliceBetween(gh, "private suspend fun discoverEntities()", "private fun matchesKeywords")
        assertEquals(
            "discoverEntities 三处实证（配置 bot/超群简介/私聊）须全换辅助车道",
            3,
            Regex("harvestBudget\\.tryAcquireAux\\(\\)").findAll(disc).count(),
        )
        val rw = sliceBetween(gh, "private suspend fun resolveWhitelist()", "private suspend fun harvestEntity(")
        assertEquals(
            "resolveWhitelist 未命中路径须走辅助车道（刀1+刀3 联动）",
            1,
            Regex("harvestBudget\\.tryAcquireAux\\(\\)").findAll(rw).count(),
        )
        val one = sliceBetween(resolver(), "private suspend fun resolveOne(", "private fun applyResolved(")
        assertEquals(
            "EntityResolver.resolveOne 记账口须换辅助车道",
            1,
            Regex("budget\\.tryAcquireAux\\(\\)").findAll(one).count(),
        )
        // 主道负锚：probeIsBot+tryShare 留 tryAcquire（收割数据链归主判词）——恰两处
        assertEquals(
            "GroupHarvester 主车道记账（tryShare 页面拉取+probeIsBot 数据管线）须原样恰两处",
            2,
            Regex("harvestBudget\\.tryAcquire\\(\\)").findAll(gh).count(),
        )
        assertEquals(
            "EntityResolver 不得再走主车道记账",
            0,
            Regex("budget\\.tryAcquire\\(\\)").findAll(resolver()).count(),
        )
        // 轮首/轮末份额探针行在场（16384 设备腿观测口）
        assertTrue("轮首「主保底=」探针字段须在场", gh.contains("主保底=\${harvestBudget.mainReserve()}"))
        assertTrue(
            "轮末「窗内总用=（辅助=）池余=」份额账行须在场",
            gh.contains(
                "本轮收工份额账：窗内总用=\${harvestBudget.windowedCount()}（辅助=\${harvestBudget.windowedAuxCount()}）池余=\${harvestBudget.remaining()}",
            ),
        )
        // 拒绝条件含保底上界语义（不加量锚：只拒不放）
        assertTrue(
            "tryAcquireAux 拒绝条件须含 reserveTokens 上界（辅助被拒在总量−保底以内）",
            gh.contains("if (ops.size + cost + reserveTokensLocked() > maxOpsPerHour) return false"),
        )
    }

    // ==================== ② Resolver 慢车道配速锚（刀2） ====================

    @Test
    fun `Resolver慢车道配速锚_五名长间隔与瞬态不连烧`() {
        val er = resolver()
        assertEquals("RESOLVE_BATCH_PER_ROUND=5 单出处（25→5 慢车道）", 1, Regex("RESOLVE_BATCH_PER_ROUND = 5").findAll(er).count())
        assertEquals("ROUND_DELAY_MS=300_000L 单出处（60s→300s 长间隔）", 1, Regex("ROUND_DELAY_MS = 300_000L").findAll(er).count())
        // 间隔分工注释原样（间隔管瞬时密度，预算管总量）
        assertTrue(er.contains("预算管总量，间隔管瞬时密度"))
        val drain = sliceBetween(er, "private suspend fun drainOnce()", "private fun sweepFromGraph")
        // TRANSIENT 与 FLOOD 同款 break：分支体内须 break（整轮收工）
        val tIdx = drain.indexOf("Outcome.TRANSIENT ->")
        assertTrue("drainOnce 须显式处理 TRANSIENT 分支", tIdx > 0)
        val rIdx = drain.indexOf("Outcome.RESOLVED ->")
        assertTrue("TRANSIENT 分支须在 RESOLVED 之前（穷举 when）", tIdx < rIdx)
        assertTrue(
            "TRANSIENT 分支体须 break 整轮收工（与 FLOOD 同款——网络劣化连烧止血）",
            drain.substring(tIdx, rIdx).contains("break"),
        )
        // 连烧形态绝迹负锚：旧版 else->{} 兜底（TRANSIENT 落入继续循环）不得回归
        assertFalse("drainOnce 不得残留 else->{} 兜底（连烧形态绝迹）", drain.contains("else -> {}"))
        // attempts 语义原样（3 连败进死信，单名瞬态下轮重试）
        assertTrue("MAX_RESOLVE_ATTEMPTS=3 语义原样", er.contains("MAX_RESOLVE_ATTEMPTS = 3"))
    }

    // ==================== ③ 白名单缓存命中跳过锚（刀3） ====================

    @Test
    fun `白名单缓存命中跳过锚_peek先于记账与零发送`() {
        val gh = harvester()
        // 时序锚：peekBotUserId 先于 tryAcquireAux（缓存命中零记账零网络）
        val rw = sliceBetween(gh, "private suspend fun resolveWhitelist()", "private suspend fun harvestEntity(")
        val peekIdx = rw.indexOf("gateway.peekBotUserId(n)")
        val auxIdx = rw.indexOf("harvestBudget.tryAcquireAux()")
        assertTrue("resolveWhitelist 须有 peek 缓存口（刀3）", peekIdx > 0)
        assertTrue("resolveWhitelist 须有辅助记账口（未命中路径）", auxIdx > 0)
        assertTrue(
            "缓存查询必须先于记账口（先记账后查缓存=旧病理回归）",
            peekIdx < auxIdx,
        )
        // peekImpl：读 botUserIdCache 零 send 零 suspend（公共 API 只加不改）
        val gd = discovery()
        val peekImpl = sliceBetween(
            gd,
            "internal fun TdLibGateway.peekBotUserIdImpl",
            "internal suspend fun TdLibGateway.isBotUserImpl",
        )
        assertTrue("peek 实现须读 botUserIdCache", peekImpl.contains("botUserIdCache[key]"))
        assertFalse("peek 不得发起任何请求", peekImpl.contains("client.send"))
        assertFalse("peek 不得是 suspend（零挂起纯读）", peekImpl.contains("suspend"))
        // 键归一同源：与 resolveBotUserIdImpl 同口径（trim/去@/小写）零第二份逻辑
        val resolveImpl = sliceBetween(
            gd,
            "internal suspend fun TdLibGateway.resolveBotUserIdImpl",
            "internal fun TdLibGateway.peekBotUserIdImpl",
        )
        val norm = "username.trim().removePrefix(\"@\").lowercase()"
        assertTrue("resolveBotUserIdImpl 键归一须在场（同源锚）", resolveImpl.contains(norm))
        assertTrue("peekBotUserIdImpl 键归一须同口径（零第二份逻辑）", peekImpl.contains(norm))
        // TdLibGateway 公共 API 只加不改：只读口一行委托
        assertTrue(
            "peekBotUserId 公共口须一行委托（公共 API 只加不改）",
            gateway().contains("fun peekBotUserId(username: String): Long? = peekBotUserIdImpl(username)"),
        )
    }

    // ==================== ④ liveAsk 自词排除锚（刀4） ====================

    @Test
    fun `liveAsk自词排除锚_登记时序与链接侧负锚`() {
        val gls = liveSearch()
        // 时序锚：noteLiveAskSent 在 sentOk=true 之后（词真发出后才登记）
        val ask = sliceBetween(gls, "fun TdLibGateway.askSearchEntityImpl(", "internal fun TdLibGateway.parseLiveBatch")
        val sentIdx = ask.indexOf("sentOk = true")
        val noteIdx = ask.indexOf("GoldmineHooks.noteLiveAskSent(q)")
        assertTrue("askSearchEntityImpl 须有 sentOk 标记（时序锚起点）", sentIdx > 0)
        assertTrue("askSearchEntityImpl 须登记自词（刀4 一行）", noteIdx > 0)
        assertTrue("自词登记必须在 sentOk=true 之后（真发出才登记）", sentIdx < noteIdx)
        // 搜索页 liveAsk 显式提交链（R371 域）零碰负锚：发词 what 原样
        assertTrue("发词 SendMessage what 须原样（R366/R371 域零碰）", ask.contains("askSearchEntity/SendMessage"))
        val hk = hooks()
        // onHarvested 热词段自词判定在场（计数口一刀切）
        val oh = sliceBetween(hk, "fun onHarvested(", "private fun seenInGraph")
        assertTrue("热词计数段须前置自词判定", oh.contains("!isLiveAskSelfLocked(folded, now)"))
        // 链接侧照旧负锚：监听器派发+新发现登记段原样（只有热词计数被排除）
        assertTrue("外挂监听器派发须原样（链接侧零改）", oh.contains("dispatchToListeners(keyword, links)"))
        assertTrue("新发现登记段须原样（链接侧零改）", oh.contains("st.fresh[key] = FreshEntry(chatId, username, now, now, 1)"))
        // 轮末 onHarvested 调用原样保留（其链接侧语义照旧，热词侧被登记表排除）
        assertTrue("轮末 onHarvested 调用须原样保留", ask.contains("GoldmineHooks.onHarvested(q, recorded.toList())"))
        // 窗口常量单出处+登记口在场
        assertEquals(
            "LIVE_ASK_SELF_SUPPRESS_MS=30min 须唯一出处（30min=2×默认轮询 15min 覆盖腿B 回声）",
            1,
            Regex("LIVE_ASK_SELF_SUPPRESS_MS = 30L \\* 60 \\* 1000").findAll(hk).count(),
        )
        assertTrue("noteLiveAskSent 公开口须在场（fold 归一幂等刷新）", hk.contains("fun noteLiveAskSent(word: String)"))
        assertTrue("登记表 liveAskSent 须为纯内存（零落盘语义）", hk.contains("private val liveAskSent = LinkedHashMap<String, Long>()"))
    }

    // ==================== ⑤ 总请求率零上升负锚（红线） ====================

    @Test
    fun `总请求率零上升负锚_池常量原样与零新池`() {
        // 池常量原样：缺省 60+范围 coerceIn(6,300)（重分配≠加量）
        assertTrue(
            "floodHarvestPerHour 缺省须原样 60",
            prefs().contains("sp.getInt(\"flood_harvest_per_hour\", 60)"),
        )
        assertTrue(
            "floodHarvestPerHour 范围须原样 6..300",
            prefs().contains("putInt(\"flood_harvest_per_hour\", v.coerceIn(6, 300))"),
        )
        // 秒回预算常量未改（与收割解耦的既判例）
        assertEquals(
            "LIVE_ASK_BUDGET_PER_HOUR 须唯一出处且原值 60",
            1,
            Regex("LIVE_ASK_BUDGET_PER_HOUR = 60").findAll(constants()).count(),
        )
        assertTrue(
            "秒回车道构造须原样两参（语义零变负锚）",
            gateway().contains("FloodBudget(3_600_000L, LIVE_ASK_BUDGET_PER_HOUR)"),
        )
        assertTrue(
            "查询车道构造须原样两参（语义零变负锚）",
            gateway().contains("FloodBudget(3_600_000L, appPrefs.floodQueryPerHour)"),
        )
        assertTrue(
            "爬取车道构造须原样两参（语义零变负锚）",
            vm().contains("FloodBudget(3_600_000L, prefs.floodCrawlPerHour)"),
        )
        assertTrue(
            "OnThisDay 车道构造须原样两参（语义零变负锚）",
            onThisDay().contains("FloodBudget(3_600_000L, 0)"),
        )
        // 全仓 FloodBudget( 构造恰 5 处（零新池：收割/查询/秒回/爬取/OnThisDay）
        assertEquals(
            "全仓 FloodBudget( 构造须恰 5 处（零新池零拆池——重分配≠加量）",
            5,
            countFloodBudgetConstructions(),
        )
        // R379 挂账①收口：两统一发送口 refund 在场（配速拒发零烧账本令牌）
        val bs = botSearch()
        val guarded = sliceBetween(bs, "fun TdLibGateway.searchPublicChatGuardedEx(", "internal suspend fun TdLibGateway.sendSearchPublicChatGuarded")
        val laneIdx = guarded.indexOf("searchQuotaLedger.tryAcquire")
        val refundIdx = guarded.indexOf("searchQuotaLedger.refund(laneEffective)")
        assertTrue("Guarded 口须有账本记账+前置拒发回退两锚", laneIdx > 0 && refundIdx > 0)
        assertTrue("回退须在记账之后（对应刚记的那枚）", laneIdx < refundIdx)
        val srv = sliceBetween(bs, "fun TdLibGateway.searchChatsOnServerGuarded(", "internal fun TdLibGateway.parseRetryAfterSec")
        assertTrue("SearchChatsOnServer 口须有前置拒发回退", srv.contains("searchQuotaLedger.refund(laneEffective)"))
        // refund 单源：SearchQuotaLedger 唯一定义+回退不越权（只摘最近 1 枚同车道）
        val lg = ledger()
        assertTrue(
            "SearchQuotaLedger.refund 须唯一在场（挂账① 正治单源）",
            Regex("fun refund\\(lane: Lane\\)").findAll(lg).count() == 1,
        )
        assertTrue("refund 回退口径=最近 1 枚同车道 stamp", lg.contains("val idx = st.indexOfLast { it.second == lane }"))
    }
}
