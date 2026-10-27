package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R392 播放编排三期 wiring 源码锚(R391WiringSourceTest 同款 user.dir 源定位
 * ——VM/UI 接线无 JVM 直测面处以源锚锁结构面;r392b_notes §2/§3):
 *  - core 锚:chainTo 三态一刀+CHAIN_NEXT 哨兵+supplyExhausted 只读访问器
 *    (段走本体零改判词的形态锚)+resolvePlayChain 单源;
 *  - codec 锚:chainTo 增量读侧(has+isNull 先守 NULL 哨兵)写侧(put ?: NULL)
 *    +v1 段冻结负锚(迁移台不继承接棒);
 *  - VM 消费锚:段尽三口+两挂点(recordMediaView 首行/心跳循环一行)+睡眠旗
 *    优先判式+_sleepNow 既有信号衔接+autoAdvance 门+一期开台链复用+单发门;
 *  - 晨间锚:循环(错峰闸+闸点 delay)+五闸判定+generateTodayStationImpl 直调
 *    (R391 链零改只调用)+成功守望+AppPrefs 三键;
 *  - UI 锚:台卡行尾接棒钮+接棒选择器三段+接棒徽记+晨间定时行+对话框;
 *  - 红线负锚:播放链 UI 四件零 R392 引用+TodayQueueGen 零 R392 引用+
 *    TasteMapScreen 零 R392 引用(预算闸安全)。
 */
class R392bWiringSourceTest {

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
            error("找不到主源码根(user.dir=${System.getProperty("user.dir")})")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在:${file.path}", file.isFile)
        return file.readText()
    }

    private fun core(): String = src("dev/tgfeed/core/feed/PlayQueue.kt")

    private fun chain(): String = src("dev/tgfeed/core/feed/PlayChain.kt")

    private fun part(): String = src("dev/tgfeed/core/feed/FeedPlayChainPart.kt")

    private fun queuePart(): String = src("dev/tgfeed/core/feed/FeedPlayQueuePart.kt")

    private fun vm(): String = src("dev/tgfeed/core/feed/FeedViewModel.kt")

    private fun watch(): String = src("dev/tgfeed/core/feed/FeedWatchPart.kt")

    private fun store(): String = src("dev/tgfeed/core/store/LocalStore.kt")

    private fun prefs(): String = src("dev/tgfeed/core/prefs/AppPrefs.kt")

    private fun schedule(): String = src("dev/tgfeed/core/feed/MorningStationSchedule.kt")

    private fun stationsUi(): String = src("dev/tgfeed/ui/QueueStationsScreen.kt")

    // ---------------- ① core 锚 ----------------

    @Test
    fun `接棒实体_chainTo一刀_哨兵单源_只读访问器形态`() {
        assertEquals(
            "chainTo 字段恰一次声明(本件一刀——copy 天然保留,legacy/默认 null=装机行为零变化)",
            1,
            Regex("val chainTo: String\\? = null,").findAll(core()).count(),
        )
        assertEquals("CHAIN_NEXT 哨兵常量单源", 1, Regex("const val CHAIN_NEXT = \"next\"").findAll(core()).count())
        // 段走本体零改判词的形态锚:供给尽=只读访问器(只读既有内部账,非新状态)
        assertEquals(
            "supplyExhausted 只读访问器恰一次",
            1,
            Regex("val supplyExhausted: Boolean").findAll(core()).count(),
        )
        // 访问器只读形态锚:单行判式(CRLF/LF 跨行 contains 会因 autocrlf 检出形态漂移,
        // 跨行锚=测试自缺陷首红教训;get() = exhausted 单行即形态本体)
        assertTrue("访问器=只读 get 形态(段走本体零改)", core().contains("get() = exhausted"))
    }

    @Test
    fun `接棒解析纯函数_单源_显示序判词`() {
        assertTrue("resolvePlayChain 定义单源(PlayChain.kt 新件)", chain().contains("fun resolvePlayChain(active: PlayQueue, stations: List<PlayQueue>): PlayQueue?"))
        assertTrue(
            "显示序=updatedAt 降序判词(所见即所得)",
            chain().contains("stations.sortedByDescending { it.updatedAt }"),
        )
        // 防御判词:末台不循环/失联降级/自指跳过
        assertTrue("末台不循环判词", chain().contains("无下家自然停(不循环)") || chain().contains("无下家→null"))
        assertTrue("自指防御", chain().contains("if (chainTo == active.id) return null"))
    }

    // ---------------- ② codec 增量锚 ----------------

    @Test
    fun `codec增量_chainTo读写_NULL哨兵口径_v1冻结`() {
        assertEquals("读侧 parseChainTo 单源", 1, Regex("private fun parseChainTo\\(obj: JSONObject\\): String\\?").findAll(store()).count())
        assertTrue(
            "读侧 NULL 哨兵 has+isNull 先守(parseResume 同口径)",
            store().contains("obj.has(\"chainTo\") && !obj.isNull(\"chainTo\")"),
        )
        assertTrue("读侧接线(v2 台对象)", store().contains("chainTo = parseChainTo(s),"))
        assertEquals(
            "写侧恰一次(增量字段向后兼容:旧档无键→null=不接)",
            1,
            Regex("put\\(\"chainTo\", q\\.chainTo \\?: JSONObject\\.NULL\\)").findAll(store()).count(),
        )
        // v1 段冻结负锚:parseStationV1 函数体内零 chainTo(一期档无接棒概念,迁移台恒不接)
        val v1Body = store().substringAfter("private fun parseStationV1").substringBefore("\n    private fun parseEntries")
        assertFalse("v1 迁移段零 chainTo(冻结判词)", v1Body.contains("chainTo"))
    }

    // ---------------- ③ VM 段尽消费锚 ----------------

    @Test
    fun `段尽三口_两挂点_供给尽消费尽两闸`() {
        val p = part()
        assertTrue("消费单口在场", p.contains("internal fun FeedViewModel.advancePlayChainAtQueueEnd"))
        assertTrue("图/音挂点口", p.contains("internal fun FeedViewModel.maybeAdvancePlayChainOnMediaEnd"))
        assertTrue("视频挂点口", p.contains("internal fun FeedViewModel.maybeAdvancePlayChainOnTick"))
        assertEquals(
            "两挂点各消费供给尽访问器(supplyExhausted)",
            2,
            Regex("src\\.supplyExhausted").findAll(p).count(),
        )
        // 挂点一刀×2:recordMediaViewImpl 首行 + 心跳循环一行(播放链 UI 零碰判词的落点)
        assertTrue("图/音挂点=recordMediaViewImpl 首行", watch().contains("maybeAdvancePlayChainOnMediaEnd(item)"))
        assertTrue("视频挂点=心跳循环", vm().contains("maybeAdvancePlayChainOnTick()"))
        // 视频路真播完判据+归属判式(watchHeartbeatTick 同款)
        assertTrue("STATE_ENDED 真播完判据", p.contains("player.playbackState == Player.STATE_ENDED"))
        assertTrue("媒体类型门=视频专属", p.contains("last.mediaType != 0"))
    }

    @Test
    fun `睡眠旗优先_既有信号衔接_autoAdvance门_一期链复用`() {
        val p = part()
        // 睡眠旗优先判式在接棒解析之前(顺序锚)
        val sleepIdx = p.indexOf("if (sleepJob != null)")
        val resolveIdx = p.indexOf("resolvePlayChain(queue, playStations)")
        assertTrue("睡眠旗判式在场", sleepIdx >= 0)
        assertTrue("接棒解析在场", resolveIdx > sleepIdx)
        assertTrue("清旗走既有口", p.contains("setSleepTimerImpl(null)"))
        assertTrue("复用 _sleepNow 既有信号(暂停+中央胶囊=通知如实)", p.contains("_sleepNow.tryEmit(Unit)"))
        assertTrue("autoAdvance 门(推进跟全局推进意愿)", p.contains("if (!_autoAdvance.value)"))
        assertTrue("一期开台链复用(fromStart=false=接棒台断点续播)", p.contains("openPlayStationImpl(target.id, fromStart = false)"))
        assertTrue("单发门(同源只结算一次)", p.contains("playChainSettledSource === src"))
    }

    @Test
    fun `台级接棒设置_rename同款_不推进updatedAt`() {
        val q = queuePart()
        assertTrue("setPlayStationChainImpl 在场", q.contains("internal fun FeedViewModel.setPlayStationChainImpl(stationId: String, chainTo: String?): Boolean"))
        assertTrue("活跃台 replaceAll 同步(rename 同款)", q.contains("playQueueModel.replaceAll(playStations[idx])"))
        // 不推进 updatedAt 判词的形态锚:copy 只带 chainTo(台序稳定=CHAIN_NEXT 解析序稳定)
        assertTrue("copy 只带 chainTo(不推进 updatedAt)", q.contains("it[idx] = it[idx].copy(chainTo = chainTo)"))
        assertTrue("指定台配置期在列表防御", q.contains("playStations.none { it.id == chainTo }"))
    }

    // ---------------- ④ 晨间定时锚 ----------------

    @Test
    fun `晨间循环_错峰闸_五闸判定_直调R391链`() {
        val p = part()
        assertTrue("循环装机口", p.contains("internal fun FeedViewModel.startMorningAlarmLoop"))
        assertTrue("错峰=冷启动让路闸(WarmupCoordinator 同款)", p.contains("ColdStartQuietGate.awaitQuiet()"))
        assertTrue("闸点 delay(MorningStationSchedule 单源)", p.contains("MorningStationSchedule.msUntilNextGate"))
        assertTrue("五闸判定口", p.contains("internal fun FeedViewModel.checkMorningOnce"))
        assertTrue("开关闸", p.contains("if (!prefs.morningStationEnabled)"))
        assertTrue("幂等日期闸", p.contains("prefs.lastMorningStationDate == today"))
        assertTrue("迟到窗", p.contains("MorningStationSchedule.withinLateWindow"))
        assertTrue("当日已有今日台只补记不重生成", p.contains("TodayQueueRecipe.STATION_NAME && it.updatedAt >= todayStart"))
        // R391 链零改只调用(红线):直调 generateTodayStationImpl,本批不自带生成逻辑;
        // 计数判别=代码行(后随行注释),KDoc 里的提及不计——纯名计数会把注释算进去
        // (首红教训:恰一次期望被 KDoc 提及撑成 2)
        assertEquals(
            "fire 直调 R391 生成链恰一次(代码行判别,KDoc 提及不计)",
            1,
            Regex("generateTodayStationImpl\\(\\) //").findAll(p).count(),
        )
        assertFalse("本批零 buildTodayQueue 逻辑(生成域不碰)", p.contains("buildTodayQueue"))
        assertTrue("成功守望有界", p.contains("withTimeoutOrNull(15_000L)"))
        assertTrue("成功才落日期(digest 发出才落同纪律)", p.contains("prefs.lastMorningStationDate = today"))
        assertTrue("设置口重挂", p.contains("internal fun FeedViewModel.setMorningStationAlarmImpl"))
    }

    @Test
    fun `晨间VM装机_AppPrefs三键_时间纯件单源`() {
        val v = vm()
        assertTrue("VM init 装机循环", v.contains("startMorningAlarmLoop()"))
        assertTrue("morningAlarmJob 字段(sleepJob 重挂同款纪律)", v.contains("internal var morningAlarmJob: Job? = null"))
        assertTrue("设置委托", v.contains("fun setMorningStationAlarm(enabled: Boolean, minuteOfDay: Int)"))
        val pf = prefs()
        assertTrue("开关键(默认 false=强意图主动开通判词)", pf.contains("get() = sp.getBoolean(\"morning_station_enabled\", false)"))
        assertTrue("闸点键(默认 450=7:30)", pf.contains("get() = sp.getInt(\"morning_station_minute_of_day\", 450)"))
        assertTrue("幂等日期键", pf.contains("last_morning_station_date"))
        val s = schedule()
        assertTrue("迟到窗单源常量", s.contains("const val LATE_WINDOW_MS = 4L * 60 * 60_000L"))
        assertTrue("时区注入(测试确定性)", s.contains("fun msUntilNextGate(minuteOfDay: Int, nowMs: Long, zone: ZoneId): Long"))
    }

    // ---------------- ⑤ UI 锚 ----------------

    @Test
    fun `台卡接棒口_选择器三段_接棒徽记_晨间入口`() {
        val u = stationsUi()
        assertTrue("行尾接棒钮(FastForward)", u.contains("Icons.Outlined.FastForward"))
        assertTrue("接棒选择器三段:不接", u.contains("vm.setPlayStationChain(chain.id, null)"))
        assertTrue("接棒选择器三段:下一台", u.contains("vm.setPlayStationChain(chain.id, PlayQueue.CHAIN_NEXT)"))
        assertTrue("接棒选择器三段:指定台", u.contains("vm.setPlayStationChain(chain.id, other.id)"))
        assertTrue("接棒徽记(失联诚实降级)", u.contains("private fun stationChainLabel"))
        assertTrue("晨间定时入口行(Alarm 图标)", u.contains("Icons.Outlined.Alarm"))
        assertTrue("晨间对话框保存接线", u.contains("vm.setMorningStationAlarm(enabled, minute)"))
        assertTrue("晨间时/分 chips(FlowRow)", u.contains("for (h in 0..23)") && u.contains("for (m in 0..50 step 10)"))
        assertTrue("睡眠衔接说明行(如实告知不造新开关)", u.contains("睡眠播完即停"))
    }

    // ---------------- ⑥ 红线负锚 ----------------

    @Test
    fun `红线_播放链UI零R392_今日单零改_星图零碰`() {
        for (path in listOf(
            "dev/tgfeed/ui/FeedScreen.kt",
            "dev/tgfeed/ui/components/VideoPage.kt",
            "dev/tgfeed/ui/components/AudioCard.kt",
            "dev/tgfeed/ui/components/PhotoCard.kt",
            "dev/tgfeed/core/feed/TodayQueueGen.kt",
            "dev/tgfeed/ui/TasteMapScreen.kt",
        )) {
            assertFalse("播放链 UI/今日单/星图零 R392 引用(零碰判词):$path", src(path).contains("R392"))
        }
    }
}
