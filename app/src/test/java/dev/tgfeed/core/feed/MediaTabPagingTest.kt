package dev.tgfeed.core.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R216 媒体 tab 供给链轮尾决策单测（用户实锤「音频/图文 tab 固定数量后上滑不加载」）：
 *  - [MediaTabPaging.supplyStuck]（R94 触底自愈武装 stuck 判据的 R216 修正）：
 *    已看墙（页回而 0 入列、游标推进、永不到 allEnded）必须算受阻——旧口径只认
 *    mediaTabExhausted 时该形态自愈永不武装=列表冻死；纯网络失败轮（零页）不算
 *    （网络恢复还有货，不能误开重温）；
 *  - [MediaTabPaging.dryPullsBreak]（干墙熔断）：媒体 tab 零入列非空列表达上限即收
 *    （干爬半价）；冷启动空列表不收（稀疏类型深挖语义保留）；有入列轮/未达页数上限/
 *    非媒体路径不收。
 */
class MediaTabPagingTest {

    // ---------------- supplyStuck（触底自愈武装信号） ----------------

    @Test
    fun `抽空终态=受阻（旧口径逐字保留）`() {
        assertTrue(MediaTabPaging.supplyStuck(mediaTabExhausted = true, pagesArrived = 0))
    }

    @Test
    fun `页回零入列=受阻（R216 新口径：已看墙形态自愈不再漏接）`() {
        // 已看墙实锤形态：页都回来了（拉取成功）但 0 入列、未到 allEnded
        assertTrue(MediaTabPaging.supplyStuck(mediaTabExhausted = false, pagesArrived = 1))
        assertTrue(MediaTabPaging.supplyStuck(mediaTabExhausted = false, pagesArrived = 12))
    }

    @Test
    fun `纯网络失败轮零页=不受阻（网络恢复还有货，不误开重温）`() {
        assertFalse(MediaTabPaging.supplyStuck(mediaTabExhausted = false, pagesArrived = 0))
    }

    @Test
    fun `抽空且页回=受阻（两形态叠加仍单口径）`() {
        assertTrue(MediaTabPaging.supplyStuck(mediaTabExhausted = true, pagesArrived = 3))
    }

    // ---------------- dryPullsBreak（干墙熔断） ----------------

    @Test
    fun `干墙形态=收轮（媒体tab零入列非空列表达上限）`() {
        assertTrue(
            MediaTabPaging.dryPullsBreak(
                useMediaFetch = true, appendedThisRound = 0, itemsSize = 50, pulls = 6,
            ),
        )
        assertTrue(
            MediaTabPaging.dryPullsBreak(
                useMediaFetch = true, appendedThisRound = 0, itemsSize = 1, pulls = 12,
            ),
        )
    }

    @Test
    fun `冷启动空列表不收（稀疏类型满额深挖语义保留）`() {
        assertFalse(
            MediaTabPaging.dryPullsBreak(
                useMediaFetch = true, appendedThisRound = 0, itemsSize = 0, pulls = 12,
            ),
        )
    }

    @Test
    fun `有入列轮不收（队列保底break先行）`() {
        assertFalse(
            MediaTabPaging.dryPullsBreak(
                useMediaFetch = true, appendedThisRound = 40, itemsSize = 50, pulls = 12,
            ),
        )
    }

    @Test
    fun `未达页数上限不收（跨干窗桥接照常）`() {
        assertFalse(
            MediaTabPaging.dryPullsBreak(
                useMediaFetch = true, appendedThisRound = 0, itemsSize = 50, pulls = 5,
            ),
        )
    }

    @Test
    fun `非媒体路径不收（全部tab换窗续拉语义不动）`() {
        assertFalse(
            MediaTabPaging.dryPullsBreak(
                useMediaFetch = false, appendedThisRound = 0, itemsSize = 50, pulls = 12,
            ),
        )
    }
}
