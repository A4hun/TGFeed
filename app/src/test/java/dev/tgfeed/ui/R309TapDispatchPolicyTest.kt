package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R309c 行星点按语义政策锚（用户实锤「点行星直接跳播放页」的防回归机检）：
 *  - 分发序形状锚（tagItemsJoinSql/PlanetGlowPolicyTest 先例）：tap 分发判定
 *    「行星本体盘内行星赢/盘外尘埃优先」以源文本锚定——回退成旧「尘埃无条件
 *    先占」吞按序（贴脸档行星公转扫过尘埃环、盘内恒有尘粒抢 14dp 命中）即红；
 *  - inBody 判定口径锚：本体盘=视觉本体屏半径（planetWorldRadius×投影 scale），
 *    不含 14dp slop 地板——slop 仍属尘埃优先区（R233 小目标优先语义零回退）；
 *  - 诊断记账行锚（V28 专席验证口径）：「R309 tap诊断」逐按记账不得静默退役。
 */
class R309TapDispatchPolicyTest {

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

    private val screenText: String
        get() = File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt").readText()

    @Test
    fun `契约-TapHit命中件在场（命中对+盘内旗标+最近屏距）`() {
        val t = screenText
        // R309b 命中件契约：三字段齐全（缺件=分发判定失据）
        assertTrue(
            "TapHit 命中件丢失（R309b/R309c 契约被回退？）",
            Regex("private data class TapHit<[^>]*>\\(\\s*val hit: T\\?,\\s*val inBody: Boolean,\\s*val nearestPx: Float").containsMatchIn(t),
        )
        // 两侧命中口都升级到 TapHit（旧裸 Pair 返回=分发层拿不到 inBody）
        assertTrue("tapPlanet 未返回 TapHit", "fun tapPlanet(pos: Offset): TapHit<Pair<TastePlanetEntry, TasteTagPlanets.TagPlanet>>".let(t::contains))
        assertTrue("tapDust 未返回 TapHit", "fun tapDust(pos: Offset): TapHit<Pair<TasteTagPlanets.TagPlanet, Int>>".let(t::contains))
    }

    @Test
    fun `口径-本体盘判定用视觉本体半径不含slop地板`() {
        val t = screenText
        // inBody=屏距²≤视觉本体屏半径²——slop 地板（PLANET_HIT_DP）不得混入本体盘判定
        assertTrue(
            "本体盘半径口径漂移（应=planetWorldRadius×投影 scale）",
            t.contains("val bodyPx = planetWorldRadius(p, s.bodyRadius, rW) * projPt[3]"),
        )
        assertTrue(
            "inBody 判定未用 bodyPx（混入 slop=盘区扩权，尘埃可点语义被挤压）",
            t.contains("bestInBody = d2 <= bodyPx * bodyPx"),
        )
    }

    @Test
    fun `分发-行星本体盘内行星赢（盘外尘埃优先不回退）`() {
        val t = screenText
        // R309c 修复本体：盘内→尘埃让位（唯一行为变化点）；盘外照旧 R233 尘埃>行星>弧>恒星
        assertTrue(
            "盘内行星赢判定丢失（R309c 修复被回退？）",
            t.contains("val dust = if (planetH.inBody) null else dustH?.hit"),
        )
        assertTrue(
            "盘外尘埃优先序丢失（R233 小目标优先语义被回退？）",
            t.contains("val planet = if (dust == null) planetH.hit else null"),
        )
        // 旧吞按序（尘埃无条件先占=用户实锤「点行星跳播放页」根因）不得回归
        assertTrue(
            "旧「尘埃无条件先占」吞按序回归（R309c 修复失效）",
            !t.contains("val dust = dustH?.hit"),
        )
        // 尘埃命中路径原样在场（盘外尘埃可点语义不删）
        assertTrue("尘埃命中消费位丢失", t.contains("if (dust != null)"))
        assertTrue("R233 尘埃命中探针行丢失", t.contains("R233 尘埃命中"))
    }

    @Test
    fun `记账-tap诊断行保留（V28专席验证口径）`() {
        val t = screenText
        // 逐按记账行（盘内/两栏最近屏距/归属）——专席实锤命中归属的唯一机器证据
        assertTrue("R309 tap诊断记账行退役", t.contains("R309 tap诊断: "))
        assertTrue("诊断行缺「盘内」栏", t.contains("盘内="))
        assertTrue("诊断行缺「归属」栏", t.contains("归属="))
    }
}
