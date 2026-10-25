package dev.tgfeed.core.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R262b 门房白名单回归锚（isBackfillStateFileName 纯函数）：
 * deepindex 库目录启动迁移门房删一切非数字命名 .json（R170 立规）——R228/R236/R238
 * 回填断点状态文件曾全在刀口上：每次进程启动被删 → 回填 Job 跨重启从第 0 行重扫
 * （西西弗斯洞，「进度文件在动但 parsed_title 不涨」的隐形放大器）。本组锚定
 * 白名单覆盖现行与换代文件名，且不误伤数字频道 JSON/杂件。
 */
class BackfillGatekeeperTest {

    @Test
    fun `白名单覆盖现行三代断点文件`() {
        assertTrue(isBackfillStateFileName("caption_backfill_v8.json"))
        assertTrue(isBackfillStateFileName("caption_backfill_v8r2.json"))
        assertTrue(isBackfillStateFileName("tags_backfill_v9.json"))
        assertTrue(isBackfillStateFileName("tags_backfill_v9r2.json"))
        assertTrue(isBackfillStateFileName("album_caption_backfill.json"))
        // R285 链式回填断点（链②相册共享+链④邻近批注）
        assertTrue(isBackfillStateFileName("chain_caption_backfill.json"))
    }

    @Test
    fun `白名单前缀覆盖未来换代与半写 tmp 件`() {
        assertTrue(isBackfillStateFileName("caption_backfill_v8r3.json"))
        assertTrue(isBackfillStateFileName("tags_backfill_v9r2.json.tmp"))
        assertTrue(isBackfillStateFileName("album_caption_backfill.json.tmp"))
        assertTrue(isBackfillStateFileName("chain_caption_backfill.json.tmp"))
    }

    @Test
    fun `R287 合并导入断点在白名单内`() {
        // import_merge_v1.json 非数字命名本就过门房数字硬门；白名单是双保险
        assertTrue(isBackfillStateFileName("import_merge_v1.json"))
        assertTrue(isBackfillStateFileName("import_merge_v1.json.tmp"))
        assertTrue(isBackfillStateFileName("import_merge_v2.json")) // 未来换代
    }

    @Test
    fun `不误伤数字频道JSON与门房该删的杂件`() {
        assertFalse(isBackfillStateFileName("-1001234567890.json"))
        assertFalse(isBackfillStateFileName("777000.json"))
        assertFalse(isBackfillStateFileName("discovery_graph.json"))
        assertFalse(isBackfillStateFileName("random.json.tmp"))
    }
}
