package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R367 行星闪烁修复 wiring 源码锚（R363/R366 同款 user.dir 源定位模式）。
 * 根因判词（华为 08:19 实锤「行星一会消失一会儿出来来回闪烁」）：
 *  - 旧帽 [PLANET_LRU_CAP]=12 < 预取 [PLANET_PREFETCH_TOP]=24——入场预取自己驱逐自己
 *    （top-24 装完只剩后 12 名，前 12 大系统全被自逐）；
 *  - 相机驻留密集星域时井内可见域超帽——每拍装配必驱逐一个**正在渲染**的行星系
 *    （planetSysSnapshot 查表落空=整星行星层硬切消失，无淡出），下拍重装再现→
 *    再逐下一个，循环=闪烁（尘埃档同病：尘埃星条件含 cachedIds 逐出即熄）；
 *  - 旧钉住面只盖焦点三态（R233 原修），井内其余可见星全是驱逐候选。
 * 修法四件：①帽 12→36（预取量+井内余量）；②井内可见域全集并入钉住（可见即不逐，
 * 驱逐只吃井外远星/离场星）；③装配幂等复核（三路并发竞装后到者让位）；④帽被
 * 井内占满=诚实拒绝零驱逐（探针一星一记会话去重）。红线：星图手势/运镜零改动。
 */
class R367WiringSourceTest {

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

    private fun mapScreen(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")

    /** assemblePlanetSystemR254 函数体切片（声明起至下一顶层 private suspend fun 或文件尾） */
    private fun assembleBody(text: String): String {
        val start = text.indexOf("private suspend fun assemblePlanetSystemR254")
        assertTrue("assemblePlanetSystemR254 应在场", start >= 0)
        val rest = text.substring(start)
        val next = rest.indexOf("\nprivate suspend fun", 10)
        return if (next > 0) rest.substring(0, next) else rest
    }

    /**
     * ①帽 36 单源 + 预取对齐 + R367 判词在 KDoc——帽≥预取=预取自驱逐绝迹的数学闸。
     */
    @Test
    fun cap_36_single_source_above_prefetch_top() {
        val text = mapScreen()
        val defs = Regex("private const val PLANET_LRU_CAP = (\\d+)").findAll(text).toList()
        assertTrue("PLANET_LRU_CAP 必须唯一出处（单源），实到 ${defs.size} 处", defs.size == 1)
        val cap = defs.first().groupValues[1].toInt()
        val prefetch = Regex("private const val PLANET_PREFETCH_TOP = (\\d+)")
            .find(text)?.groupValues?.get(1)?.toInt() ?: error("PLANET_PREFETCH_TOP 应在场")
        assertTrue("帽 $cap 必须 ≥ 预取量 $prefetch（预取自驱逐绝迹）", cap >= prefetch)
        val capKdocWindow = text.substring(maxOf(0, text.indexOf("PLANET_LRU_CAP") - 900), text.indexOf("PLANET_LRU_CAP"))
        assertTrue("帽 KDoc 应记 R367 根因判词", capKdocWindow.contains("R367"))
    }

    /**
     * ②井内可见域并入钉住：pinned 初值=inWellIds 全集 + 焦点三态并入（可见即不逐）。
     */
    @Test
    fun inwell_visible_domain_pinned_union() {
        val body = assembleBody(mapScreen())
        assertTrue(
            "钉住初值应取井内可见域全集（ctx.inWellIds.value）",
            body.contains("HashSet<Long>(ctx.inWellIds.value)"),
        )
        assertTrue("焦点三态仍并入钉住（selectedId/nearFocusId/dustChatId）", body.contains("ctx.selectedId.value?.let"))
        assertTrue(body.contains("ctx.nearFocusId.longValue"))
        assertTrue(body.contains("ctx.dustChatId.longValue"))
        // 钉住面=R367 扩界判词在场（扩界注释=后来者不退化回三态的守档）
        assertTrue("钉住扩界注释应记 R367 判词", body.substring(body.indexOf("淘汰保护")).let { it.contains("井内可见域") })
    }

    /**
     * ③装配幂等复核：synchronized 块内 containsKey 复查必须在 put 之前（三路并发
     * 竞装后到者让位——时序锚）。
     */
    @Test
    fun idempotent_recheck_precedes_put_in_critical_section() {
        val body = assembleBody(mapScreen())
        val syncStart = body.indexOf("synchronized(ctx.planetLru) {", body.indexOf("val pinned"))
        assertTrue("临界区应在场", syncStart > 0)
        val recheck = body.indexOf("if (ctx.planetLru.containsKey(chatId)) return", syncStart)
        val put = body.indexOf("ctx.planetLru[chatId] = entry", syncStart)
        assertTrue("幂等复核应在场", recheck > 0)
        assertTrue("put 应在场", put > 0)
        assertTrue("幂等复查必须先于 put（时序锚）", recheck < put)
    }

    /**
     * ④轮询每拍发布井内可见域：收集门=ahead ∧ ratio<APPEAR_RATIO（与渲染门同阈），
     * 整集换血发布（星漂出井一拍内失钉）。
     */
    @Test
    fun poll_beat_publishes_inwell_ids_with_render_gate() {
        val text = mapScreen()
        val publish = text.indexOf("ctx.inWellIds.value = wellIds.toHashSet()")
        assertTrue("井内集合发布应在场（轮询拍尾）", publish > 0)
        val collect = text.indexOf("if (ahead && ratio < TasteTagPlanets.APPEAR_RATIO) wellIds += s.chatId")
        assertTrue("井内收集门应在场（ahead ∧ APPEAR_RATIO 同渲染门）", collect > 0)
        val pollStart = text.indexOf("private suspend fun CoroutineScope.pollGalaxyBeatsR254")
        assertTrue("收集与发布都应落在轮询体内", pollStart in 0 until collect && collect < publish)
    }

    /**
     * ⑤诚实拒绝零驱逐：victim==null 分支=探针行（计数口径零内容）+ 先于 remove/put
     * 返回；探针走会话去重账防刷屏。
     */
    @Test
    fun honest_refuse_zero_eviction_with_session_dedup() {
        val body = assembleBody(mapScreen())
        val refuse = body.indexOf("if (victim == null) {")
        assertTrue("诚实拒绝分支应在场", refuse > 0)
        val probe = body.indexOf("R233 装配拒绝", refuse)
        assertTrue("拒绝探针行应在场（计数口径）", probe > 0)
        assertTrue("探针应走会话去重（planetRefuseLog）", body.substring(refuse, refuse + 600).contains("planetRefuseLog.add"))
        val remove = body.indexOf("ctx.planetLru.remove(victim)", refuse)
        val put = body.indexOf("ctx.planetLru[chatId] = entry", refuse)
        // 拒绝分支内 return 在 remove/put 之前（零驱逐零装入——拒绝不破坏在住集）
        val ret = body.indexOf("return", refuse)
        assertTrue("拒绝分支应先 return（零驱逐）", ret in 0 until minOf(remove.takeIf { it > 0 } ?: Int.MAX_VALUE, put.takeIf { it > 0 } ?: Int.MAX_VALUE))
    }
}
