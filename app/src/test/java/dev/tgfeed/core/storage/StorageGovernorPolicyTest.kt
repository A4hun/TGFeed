package dev.tgfeed.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [StorageGovernor] 治理纯函数行为单测（R196）：
 *  - [tempFragmentAction]：temp 碎片三闸（活跃排除 / 新鲜度窗口 / TDLib 在飞·完成态），
 *    使命=「播放与在飞永误伤、死残留必回收」——用户机 3GB 现场就是死残留没人收；
 *  - [fullLoadEvictionCandidates]：R195 全载登记表的「最近 N 条」保留语义（活跃让路）；
 *  - [storageBucketOf]/[measureStorageOverview]：概览分桶口径（守卫红线的可测试表达：
 *    deepindex 归 DEEP_INDEX 只量不动、db.sqlite 归 TD_DB 不碰、temp 归 TD_TEMP 可清）；
 *  - [fmtMb]/[fmtGb]：清理日志与概览卡的读数口径。
 */
class StorageGovernorPolicyTest {

    // ---------------- T0：temp 碎片处置判定 ----------------

    @Test
    fun `活跃文件永不删_泵供流与兜底在飞排除`() {
        // 文件名=fileId（用户机实测形态 temp/7275），在活跃集（泵供流/整下兜底/预热）
        // → 无论多老都让路（mtime 陈旧度只是辅助，不做唯一判据）
        val active = setOf(7275, 7309)
        assertEquals(TempAction.KEEP_ACTIVE, tempFragmentAction("7275", 99L * 60_000L, active, staleMs = 15 * 60_000L))
        assertEquals(TempAction.KEEP_ACTIVE, tempFragmentAction("7309", 24 * 3_600_000L, active, staleMs = 15 * 60_000L))
    }

    @Test
    fun `新鲜窗口内保留_滑走后的回看盘命中价值期`() {
        // 15min 常态口径内：死残留也先留着（R166 语义——滑走段盘命中秒开）
        assertEquals(TempAction.KEEP_FRESH, tempFragmentAction("7222", 14 * 60_000L, emptySet(), staleMs = 15 * 60_000L))
        // 压力口径 2min：14min 老 → 可清
        assertEquals(TempAction.DELETE, tempFragmentAction("7222", 14 * 60_000L, emptySet(), staleMs = 2 * 60_000L))
    }

    @Test
    fun `TDLib在飞让路_WiFi预载不在活跃集也有二道闸`() {
        // GetFile 实证 isDownloadingActive=true（WiFi 预载/seek 抢占重下）：活跃集没收录
        // 也得让路——陈旧度过了照样 KEEP_ACTIVE
        assertEquals(
            TempAction.KEEP_ACTIVE,
            tempFragmentAction("7230", 3_600_000L, emptySet(), staleMs = 15 * 60_000L, tdDownloadingActive = true),
        )
    }

    @Test
    fun `完成态不碰_归水位闸管temp清扫不越权`() {
        // 暂存改名中途的边缘态（isDownloadingCompleted）：删除完成文件是 T2 水位闸
        // （LRU 最旧优先）的职权，T0 只清碎片
        assertEquals(
            TempAction.KEEP_COMPLETED,
            tempFragmentAction("7203", 3_600_000L, emptySet(), staleMs = 15 * 60_000L, tdCompleted = true),
        )
    }

    @Test
    fun `死残留必删_用户机3GB现场的主口径`() {
        // 不在活跃集 + 过新鲜窗 + TDLib 无在飞/未完成 → 删（先 DeleteFile 一致删，孤儿裸删）
        assertEquals(TempAction.DELETE, tempFragmentAction("7203", 16 * 60_000L, emptySet(), staleMs = 15 * 60_000L))
        assertEquals(
            TempAction.DELETE,
            tempFragmentAction("7203", 16 * 60_000L, emptySet(), staleMs = 15 * 60_000L, tdDownloadingActive = false),
        )
    }

    @Test
    fun `非数字文件名_杂散暂存只走新鲜度`() {
        // TDLib 其他杂散暂存（非 fileId 命名）：无从查 TDLib 态，只按新鲜度判
        assertEquals(TempAction.KEEP_FRESH, tempFragmentAction("tmp-xyz", 1_000L, emptySet(), staleMs = 15 * 60_000L))
        assertEquals(TempAction.DELETE, tempFragmentAction("tmp-xyz", 20 * 60_000L, emptySet(), staleMs = 15 * 60_000L))
    }

    // ---------------- T1：全载保留上限（R195 接口语义） ----------------

    @Test
    fun `保留最近N条_其余按最久未用淘汰`() {
        val entries = listOf(
            FullLoadEntry(101, "a", 1_000L),
            FullLoadEntry(102, "b", 3_000L),
            FullLoadEntry(103, "c", 2_000L),
            FullLoadEntry(104, "d", 500L),
        )
        // N=3：留 102/103/101（新→旧前三），淘汰 104（最久未用）
        val victims = fullLoadEvictionCandidates(entries, keepCount = 3, activeFileIds = emptySet())
        assertEquals(listOf(104), victims.map { it.fileId })
    }

    @Test
    fun `活跃条目让路_本轮不淘汰等下轮`() {
        val entries = listOf(
            FullLoadEntry(101, "a", 1_000L),
            FullLoadEntry(104, "d", 500L), // 最旧但在播（用户正看，touch 过的旧登记）
        )
        val victims = fullLoadEvictionCandidates(entries, keepCount = 1, activeFileIds = setOf(104))
        assertEquals(emptyList<Int>(), victims.map { it.fileId })
    }

    @Test
    fun `keep为0关闭淘汰_登记只做登记`() {
        // AppPrefs.storageFullLoadRetention=0 语义：R195 预热照常登记，不做全载淘汰
        val entries = listOf(FullLoadEntry(101, "a", 1L), FullLoadEntry(102, "b", 2L))
        assertTrue(fullLoadEvictionCandidates(entries, keepCount = 0, activeFileIds = emptySet()).isEmpty())
        assertTrue(fullLoadEvictionCandidates(emptyList(), keepCount = 3, activeFileIds = emptySet()).isEmpty())
    }

    // ---------------- 概览分桶（守卫红线的可测试表达） ----------------

    @Test
    fun `分桶口径_deepindex与db与files与temp各归其位`() {
        assertEquals(StorageBucket.DEEP_INDEX, storageBucketOf("deepindex/search.db"))
        assertEquals(StorageBucket.DEEP_INDEX, storageBucketOf("deepindex"))
        assertEquals(StorageBucket.TD_TEMP, storageBucketOf("tdlib/files/temp/7275"))
        assertEquals(StorageBucket.TD_TEMP, storageBucketOf("tdlib/temp/.nomedia"))
        assertEquals(StorageBucket.TD_DB, storageBucketOf("tdlib/db.sqlite-wal"))
        assertEquals(StorageBucket.TD_FILES, storageBucketOf("tdlib/files/videos/xxx.mp4"))
        assertEquals(StorageBucket.OTHER, storageBucketOf("goldmine.json"))
        assertEquals(StorageBucket.OTHER, storageBucketOf("store/downloads.json"))
    }

    @Test
    fun `概览量取_模拟用户机现场的分项对账`() {
        // 复刻用户机形态：deepindex 4.9G 索引 + temp 2.9G 碎片 + 完成缓存 + 消息库
        val dir = java.nio.file.Files.createTempDirectory("r196_overview").toFile()
        try {
            mkdirsWithFile(dir, "deepindex/search.db", 4_900_000_000L)
            mkdirsWithFile(dir, "deepindex/search.db-wal", 1_000L)
            mkdirsWithFile(dir, "tdlib/files/temp/7275", 1_250_000_000L)
            mkdirsWithFile(dir, "tdlib/files/temp/7309", 1_370_000_000L)
            mkdirsWithFile(dir, "tdlib/temp/.nomedia", 0L)
            mkdirsWithFile(dir, "tdlib/files/videos/v1.mp4", 2_800_000_000L)
            mkdirsWithFile(dir, "tdlib/db.sqlite", 1_600_000_000L)
            mkdirsWithFile(dir, "tdlib/td.binlog", 2_000_000L)
            mkdirsWithFile(dir, "goldmine.json", 30_000L)

            val ov = measureStorageOverview(dir)
            assertEquals(4_900_001_000L, ov.deepIndexBytes)
            assertEquals(2_620_000_000L, ov.tempBytes) // 只算碎片，不算 .nomedia
            assertEquals(2_800_000_000L, ov.tdlibCacheBytes)
            assertEquals(1_600_000_000L, ov.messageDbBytes)
            assertEquals(
                listOf(ov.deepIndexBytes, ov.tempBytes, ov.tdlibCacheBytes, ov.messageDbBytes, 2_030_000L).sum(),
                ov.totalBytes,
            ) // .nomedia(0)+binlog(2M)+json(30K) 归 OTHER
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------- 读数口径 ----------------

    @Test
    fun `MB与GB读数_一位小数`() {
        assertEquals("0.0", fmtMb(0))
        assertEquals("1.5", fmtMb(1_572_864L)) // 1.5 MiB
        assertEquals("2756.0", fmtMb(2_889_875_456L)) // 2756 MiB 整
        assertEquals("12.2", fmtGb(13_099_650_253L)) // ≈12.2 GiB（用户机口径量级）
        assertEquals("0.5", fmtGb(536_870_912L)) // 512 MiB = 0.5 GiB
    }

    private fun mkdirsWithFile(root: File, rel: String, size: Long) {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        if (size <= 256L * 1024) {
            f.writeBytes(ByteArray(size.toInt()))
        } else {
            // 大文件稀疏占位（File.length 走表观大小，免得测试真写 2.9GB）
            java.io.RandomAccessFile(f, "rw").use { it.setLength(size) }
        }
    }
}
