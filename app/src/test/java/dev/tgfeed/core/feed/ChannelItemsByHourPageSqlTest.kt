package dev.tgfeed.core.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R262b C-① 修复在 R266 两段法时代的回归锚（HOUR_WINDOW_PREDICATE_SQL 常量）：
 * R259c 机上双形态实证——时段窗查询全参数 TEXT 绑定，小时谓词 `…%24=?` 右值 '23'
 * 是 TEXT 且等号两侧皆无亲和性 → SQLite 不做转换、INTEGER<TEXT 恒成立 → 等值恒假
 * → 热力条点选内容窗恒空（「该时段暂无索引样本」误报）。修复=右值 CAST 出
 * INTEGER 亲和性。R266 两段法重写后谓词收口为本常量（两臂同施），本组锚定其形状。
 */
class ChannelItemsByHourPageSqlTest {

    @Test
    fun `小时谓词右值CAST数值化`() {
        assertTrue(HOUR_WINDOW_PREDICATE_SQL.contains("/3600%24 = CAST(? AS INTEGER)"))
    }

    @Test
    fun `病灶形状不在场（裸等值右值）`() {
        // 旧病灶形状：%24=? （TEXT 绑定恒假）
        assertFalse(HOUR_WINDOW_PREDICATE_SQL.contains("%24=?"))
        assertFalse(HOUR_WINDOW_PREDICATE_SQL.contains("%24 =?"))
    }

    @Test
    fun `参数序锚定（tz 在前 hour 在后）`() {
        // 2 个占位符，序：tz(+表达式内) → hour(CAST 内)；与调用点 extraArgs 同序
        assertTrue(HOUR_WINDOW_PREDICATE_SQL.count { it == '?' } == 2)
        val tzIdx = HOUR_WINDOW_PREDICATE_SQL.indexOf("(dateEpochSec+?)/3600")
        val hourIdx = HOUR_WINDOW_PREDICATE_SQL.indexOf("CAST(? AS INTEGER)")
        assertTrue(tzIdx in 1 until hourIdx)
    }

    @Test
    fun `tz 参数留在算术表达式内（TEXT 绑定可行的唯一位置）`() {
        // 直方图不受影响的原因：+ 算术运算对 TEXT 操作数做数值化转换。
        // 若有人把 tz 挪到比较位须同步 CAST——锚定其留在 (dateEpochSec+?) 内。
        assertTrue(HOUR_WINDOW_PREDICATE_SQL.contains("(dateEpochSec+?)/3600%24"))
    }

    @Test
    fun `调用点两臂同施该谓词`() {
        // 防「常量修了但调用点没用」漂移：channelItemsByHourPage 必须经本常量进两段法
        val src = java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/dev/tgfeed/core/feed/DeepSearchIndex.kt",
        ).readText()
        val fn = src.substring(src.indexOf("fun channelItemsByHourPage("))
            .substring(0, 1200)
        assertTrue(fn.contains("extraPredicate = HOUR_WINDOW_PREDICATE_SQL"))
    }
}
