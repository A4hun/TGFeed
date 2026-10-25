package dev.tgfeed.core.td

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R184 账号级搜索配额账本纯函数单测：三车道份额/保底余量/滑动窗/封禁窗持久化
 * （重启恢复）/累进衰减/429 取 max 不缩短/持久化往返。
 */
class SearchQuotaLedgerTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("r184_quota").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private var nowMs = 1_000_000L
    private val clock = { nowMs }

    private fun ledger(persist: Boolean = true) =
        SearchQuotaLedger(if (persist) tmp else null, clock)

    // ---------------- 基础预算 ----------------

    @Test
    fun `基础放量与车道份额`() {
        val l = ledger()
        // 默认 12/h：用户 12、后台 ⌈12/3⌉=4、爬虫验证 ⌈12/6⌉=2
        repeat(12) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION)) }
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION)) // 车道=总量耗尽
    }

    @Test
    fun `后台车道份额是总量的三分之一`() {
        val l = ledger()
        repeat(4) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) }
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
    }

    @Test
    fun `爬虫车道份额是总量的六分之一`() {
        val l = ledger()
        repeat(2) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.CRAWLER)) }
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.CRAWLER))
    }

    @Test
    fun `保底余量区非用户车道全拒`() {
        val l = ledger()
        // 先烧 8 发用户动作（12-8=4 ≤ 保底 6 → 进入保底区）
        repeat(8) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION)) }
        // 用户动作仍可放（自己的保底）；后台/爬虫全拒
        assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.CRAWLER))
    }

    @Test
    fun `后台爬虫吃量后用户动作仍有保底`() {
        val l = ledger()
        // 后台 4 + 爬虫 1 = 5 发（总量剩 7 > 保底 6，未进保底区）
        repeat(4) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) }
        assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.CRAWLER))
        // 后台份额（⌈12/3⌉=4）已用完
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
        // 用户动作全额可用：5+7=12 恰好打满总量
        repeat(7) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION)) }
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
    }

    // ---------------- 滑动窗 ----------------

    @Test
    fun `滑动窗一小时后回血`() {
        val l = ledger()
        repeat(4) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) }
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
        nowMs += 3_600_001L // 窗口整体滑出
        assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
    }

    @Test
    fun `窗口未滑出前不回血`() {
        val l = ledger()
        repeat(4) { assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) }
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
        nowMs += 1_800_000L // 30 分钟：仍在 1 小时窗内
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
    }

    // ---------------- 封禁窗 ----------------

    @Test
    fun `封禁窗内连试都不试`() {
        val l = ledger()
        assertEquals(32560L, l.report429(32560L))
        // 任何车道零放行（连试都不试——防续封）
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.CRAWLER))
        assertEquals(32560L, l.remainingBanSec())
    }

    @Test
    fun `封禁窗到期自动解封`() {
        val l = ledger()
        l.report429(600L)
        nowMs += 601_000L
        assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
        assertEquals(0L, l.remainingBanSec())
    }

    @Test
    fun `429取max不缩短已有封禁`() {
        val l = ledger()
        l.report429(32560L)
        nowMs += 1000L
        l.report429(60L) // 后到的小 retry-after 不得冲短先到的大封禁
        assertEquals(32559L, l.remainingBanSec())
    }

    @Test
    fun `服务端没给retry_after按兜底封`() {
        val l = ledger()
        assertEquals(600L, l.report429(0L))
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
    }

    // ---------------- 累进记忆 ----------------

    @Test
    fun `历史封禁递减配额`() {
        val l = ledger()
        assertEquals(12, l.totalCap())
        l.report429(60L)
        assertEquals(9, l.totalCap()) // 12 × 0.75
        nowMs += 61_000L
        l.report429(60L)
        assertEquals(6, l.totalCap()) // 12 × 0.75² = 6.75 → toInt 6
        nowMs += 61_000L
        l.report429(60L)
        assertEquals(5, l.totalCap()) // 12 × 0.75³ = 5.06 → 5
        assertEquals(3, l.banHistoryCount())
    }

    @Test
    fun `衰减下限不低于3`() {
        val l = ledger()
        nowMs += 61_000L
        repeat(8) {
            l.report429(60L)
            nowMs += 61_000L
        }
        assertEquals(3, l.totalCap()) // 0.75^8 ≈ 0.10 → 下限 0.25×12=3
    }

    // ---------------- 持久化 ----------------

    @Test
    fun `封禁窗与历史跨进程恢复`() {
        val a = ledger()
        a.report429(32560L)
        a.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND) // 被拒（封禁窗内）不记账
        val b = ledger() // 新实例=模拟重启
        assertEquals(32560L, b.remainingBanSec())
        assertEquals(1, b.banHistoryCount())
        assertEquals(9, b.totalCap())
        assertFalse(b.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
    }

    @Test
    fun `已用时间戳跨进程恢复`() {
        val a = ledger()
        repeat(4) { assertTrue(a.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) }
        assertFalse(a.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND))
        val b = ledger()
        assertFalse(b.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) // 份额跨进程保持
        nowMs += 3_600_001L
        assertTrue(b.tryAcquire(SearchQuotaLedger.Lane.BACKGROUND)) // 滑出后回血
    }

    @Test
    fun `纯内存态不落盘也不炸`() {
        val l = ledger(persist = false)
        assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
        l.report429(60L)
        l.flush() // 空转不炸
        assertFalse(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
    }

    @Test
    fun `脏文件按空账本重建不炸`() {
        File(tmp, "search_quota.json").writeText("not a json")
        val l = ledger()
        assertTrue(l.tryAcquire(SearchQuotaLedger.Lane.USER_ACTION))
        assertEquals(0, l.banHistoryCount())
    }
}
