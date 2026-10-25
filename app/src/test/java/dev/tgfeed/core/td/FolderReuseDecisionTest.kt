package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R220 频道礼包查重决策单测（[decideFolderReuse] 纯函数）：
 * 建夹前「同频道集合已存在→复用」的全部分支与优先级。
 *
 * 背景口径：R130 测试污染实锤——每次生成都在用户账号里 CreateChatFolder 堆一个
 * 垃圾夹；查重决策是修根的第一道闸（第二道是 agentNoAccountWrite 测试闸）。
 *
 * 数据纪律：频道 id 全部合成代称（-100xxx 合成值），无真实频道。
 */
class FolderReuseDecisionTest {

    private val ids = setOf(-100111L, -100222L, -100333L)

    private fun cand(
        folderId: Int,
        title: String = "合集A",
        memberIds: Set<Long> = ids,
        isShareable: Boolean = false,
        hasMyInviteLinks: Boolean = false,
    ) = FolderDupCandidate(folderId, title, memberIds, isShareable, hasMyInviteLinks)

    // ---------------- 零命中 → 建新夹 ----------------

    @Test
    fun `零命中_无任何夹_建新夹`() {
        assertEquals(FolderReuseDecision.CreateNew, decideFolderReuse(emptyList(), "合集A", ids))
    }

    @Test
    fun `零命中_频道集合不同_建新夹`() {
        val other = listOf(cand(1, memberIds = setOf(-100999L)))
        assertEquals(FolderReuseDecision.CreateNew, decideFolderReuse(other, "合集A", ids))
    }

    @Test
    fun `零命中_集合是子集不等于_建新夹`() {
        // 子集/超集都不算命中——只认集合完全相等，防误收编别人的夹
        val subset = listOf(cand(1, memberIds = setOf(-100111L, -100222L)))
        assertEquals(FolderReuseDecision.CreateNew, decideFolderReuse(subset, "合集A", ids))
    }

    // ---------------- 命中分支（优先级从高到低） ----------------

    @Test
    fun `命中_已有邀请链接_直接复用链接`() {
        val cs = listOf(cand(1, hasMyInviteLinks = true))
        assertEquals(
            FolderReuseDecision.ReuseExistingLink(1),
            decideFolderReuse(cs, "合集A", ids),
        )
    }

    @Test
    fun `命中_已是可分享合集_在其上导出`() {
        val cs = listOf(cand(1, isShareable = true))
        assertEquals(
            FolderReuseDecision.ExportOnExisting(1),
            decideFolderReuse(cs, "合集A", ids),
        )
    }

    @Test
    fun `命中_同名普通夹_收编遗孤`() {
        val cs = listOf(cand(1, title = "合集A"))
        assertEquals(
            FolderReuseDecision.AdoptOrphan(1),
            decideFolderReuse(cs, "合集A", ids),
        )
    }

    @Test
    fun `命中_异名普通夹_防误伤用户私夹_建新夹`() {
        // 同频道集合但名字对不上且不可分享：多半是用户自建普通夹——不收编不转换，
        // 照旧建新夹（宁可多建也不动用户私夹；此路走 CreateChatFolder 主路）
        val cs = listOf(cand(1, title = "我的私夹"))
        assertEquals(FolderReuseDecision.CreateNew, decideFolderReuse(cs, "合集A", ids))
    }

    // ---------------- 优先级 ----------------

    @Test
    fun `优先级_有链接压过可分享`() {
        val cs = listOf(
            cand(1, isShareable = true),
            cand(2, hasMyInviteLinks = true),
        )
        assertEquals(FolderReuseDecision.ReuseExistingLink(2), decideFolderReuse(cs, "合集A", ids))
    }

    @Test
    fun `优先级_可分享压过同名遗孤`() {
        val cs = listOf(
            cand(1, title = "合集A"),
            cand(2, title = "合集A", isShareable = true),
        )
        assertEquals(FolderReuseDecision.ExportOnExisting(2), decideFolderReuse(cs, "合集A", ids))
    }

    @Test
    fun `优先级_同名遗孤压过异名普通夹`() {
        val cs = listOf(
            cand(1, title = "我的私夹"),
            cand(2, title = "合集A"),
        )
        assertEquals(FolderReuseDecision.AdoptOrphan(2), decideFolderReuse(cs, "合集A", ids))
    }

    // ---------------- 成员集合口径 ----------------

    @Test
    fun `成员口径_包含顺序无关_集合相等即命中`() {
        // memberIds 是 pinned+included 并集：调用侧已去序化，这里校验集合语义
        val cs = listOf(cand(1, memberIds = setOf(-100333L, -100111L, -100222L)))
        assertEquals(FolderReuseDecision.AdoptOrphan(1), decideFolderReuse(cs, "合集A", ids))
    }
}
