package dev.tgfeed.core.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R283 编年史⑥互联卷数据底座 SQL 形状锚（ChannelItemsByHourPageSqlTest 手法）：
 * 三只读口的取数纪律——频道域索引导航/点查连接/就绪门/零写/纯件收口——
 * 防「常量修了调用点没用」「全表扫悄悄引入」两类漂移。
 */
class InterlinkSqlShapeTest {

    private val src: String by lazy {
        java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/dev/tgfeed/core/feed/DeepSearchIndex.kt",
        ).readText()
    }

    private fun fnBody(name: String, span: Int = 5200): String {
        val i = src.indexOf(name)
        assertTrue("函数须存在：$name", i >= 0)
        return src.substring(i).substring(0, minOf(span, src.length - i))
    }

    @Test
    fun `三口只读（金矿只读连接+无写调用）`() {
        for (fn in listOf("fun interlinkSharedTimeline(", "fun interlinkPairSummary(", "fun interlinkPartnersOf(")) {
            val body = fnBody(fn)
            assertTrue("$fn 须走 openGoldDb 只读连接", body.contains("openGoldDb()"))
            assertFalse("$fn 禁写", body.contains("execSQL"))
            assertFalse("$fn 禁写事务", body.contains("beginTransaction"))
        }
    }

    @Test
    fun `方向臂走 canonRowid 点查（refs 的 chatId 索引导航）`() {
        val body = fnBody("private fun collectInterlinkPairRows(")
        assertTrue(body.contains("ON r.canonRowid = i.rowid"))
        assertTrue(body.contains("r.chatId=?"))
    }

    @Test
    fun `共现臂走 refs fuid 点查（REFS_FUID_INDEX 导航）`() {
        val body = fnBody("private fun collectInterlinkPairRows(")
        assertTrue(body.contains("ON r1.fuid = r2.fuid"))
    }

    @Test
    fun `残双canonical臂挂在fuid索引就绪门内（未就绪不扫）`() {
        val body = fnBody("private fun collectInterlinkPairRows(")
        // 门须先于该臂 SQL：_fuidIndexReady 判定出现在 items∩items 连接之前
        val gate = body.indexOf("_fuidIndexReady.value")
        val arm0 = body.indexOf("ON i1.fileUniqueId = i2.fileUniqueId")
        assertTrue("就绪门须在场", gate >= 0)
        assertTrue("臂0 SQL 须在场", arm0 >= 0)
        assertTrue("臂0 须在就绪门之后", gate < arm0)
    }

    @Test
    fun `全臂行数cap在场（防巨型频道失控）`() {
        for (fn in listOf("private fun collectInterlinkPairRows(", "fun interlinkPartnersOf(")) {
            val body = fnBody(fn)
            assertTrue("$fn 逐臂 cap", body.contains("INTERLINK_ARM_ROW_CAP"))
        }
    }

    @Test
    fun `纯件收口（合并聚合伙伴逻辑不在 SQL 壳内手写）`() {
        assertTrue(fnBody("fun interlinkSharedTimeline(").contains("InterlinkMerge.timeline"))
        assertTrue(fnBody("fun interlinkPairSummary(").contains("InterlinkMerge.summarize"))
        assertTrue(fnBody("fun interlinkPartnersOf(").contains("InterlinkMerge.partners"))
    }

    @Test
    fun `病灶形状不在场（items 侧无 fuid 全表扫谓词）`() {
        // items.fileUniqueId 直查只许出现在就绪门守护的臂0（JOIN 点查形态），
        // 不许出现 WHERE fileUniqueId=? 的裸全表扫形状
        val body = fnBody("private fun collectInterlinkPairRows(")
        assertFalse(body.contains("WHERE i1.fileUniqueId=?"))
        assertFalse(body.contains("WHERE fileUniqueId=?"))
    }
}
