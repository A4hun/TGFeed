package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R285 链④判定核（computeAdjacentNotes）单测：
 *  - 非严格模式=抽核前 buildAdjacentNotes 语义逐项保持（网关路径行为不变的锚）；
 *  - 严格相邻模式（DB 存量回填专用）：序号域缺口=不可见消息，批注链诚实断裂；
 *  - ping 守卫/相册隔断/ANNOTATE_MAX_MEDIA 上限/带文媒体透明 四语义两模式同锚。
 * 行 id 一律真实形态（序号 << [TD_MSG_ID_SEQ_SHIFT]，026dae3 后核内相邻判定
 * 全在序号域）。
 */
class ChainNoteKernelTest {

    private fun mid(n: Long) = n shl TD_MSG_ID_SEQ_SHIFT

    private fun text(n: Long, t: String) = ChainNoteRow(mid(n), albumId = 0L, text = t, caption = "")
    private fun media(n: Long, albumId: Long = 0L, caption: String = "") =
        ChainNoteRow(mid(n), albumId, text = null, caption = caption)

    // ---------------- 非严格模式（网关语义锚） ----------------

    @Test
    fun `非严格_文字帖批注紧随空媒体`() {
        val (notes, tail) = computeAdjacentNotes(listOf(text(100, "本批说明"), media(99), media(98)))
        assertEquals("本批说明", notes[mid(99)])
        assertEquals("本批说明", notes[mid(98)])
        assertEquals(2, tail!!.used)
    }

    @Test
    fun `非严格_不相邻也批注（窗内语义不闸）`() {
        // 网关窗内消息连续可见，序号差>1 不视为断链（旧行为保持）
        val (notes, _) = computeAdjacentNotes(listOf(text(100, "批注"), media(90)))
        assertEquals("批注", notes[mid(90)])
    }

    @Test
    fun `非严格_ping帖重置批注态`() {
        val (notes, tail) = computeAdjacentNotes(listOf(text(100, "🔔🔥"), media(99)))
        assertTrue(notes.isEmpty())
        assertNull(tail)
    }

    @Test
    fun `非严格_带caption相册隔断批注`() {
        val (notes, _) = computeAdjacentNotes(
            listOf(text(100, "推广"), media(99, albumId = 7L, caption = "剧名 第1期"), media(98, albumId = 7L), media(97)),
        )
        assertNull(notes[mid(98)]) // 同组空成员：隔断位自身不吃批注
        assertNull(notes[mid(97)]) // 更旧散媒体：批注不穿透完整相册
    }

    @Test
    fun `非严格_批注上限ANNOTATE_MAX_MEDIA`() {
        val rows = buildList {
            add(text(100, "批注正文"))
            for (i in 0 until ANNOTATE_MAX_MEDIA + 1) add(media(99L - i))
        }
        val (notes, _) = computeAdjacentNotes(rows)
        assertEquals(ANNOTATE_MAX_MEDIA, notes.size)
    }

    @Test
    fun `非严格_带文媒体透明不消费计数`() {
        // 带 caption 媒体在批注源与目标之间：透明（不吃批注、不占计数、不断链）
        val (notes, _) = computeAdjacentNotes(
            listOf(text(100, "批注"), media(99, caption = "自有文案"), media(98)),
        )
        assertNull(notes[mid(99)])
        assertEquals("批注", notes[mid(98)])
    }

    @Test
    fun `非严格_seed跨页接续按序号域判定`() {
        // 026dae3 修复锚随委托沉核：seed 与本段首条序号相邻（差 1..MAX+1）才作数
        val seed = TdLibGateway.TailNote(textMsgId = mid(101), text = "上页批注", used = 1)
        val (notes, _) = computeAdjacentNotes(listOf(media(100), media(99)), seedNote = seed)
        assertEquals("上页批注", notes[mid(100)])
        assertEquals("上页批注", notes[mid(99)])
        // 超窗真拒：序号差 20 > MAX+1
        val far = TdLibGateway.TailNote(textMsgId = mid(120), text = "超窗批注", used = 1)
        val (notes2, _) = computeAdjacentNotes(listOf(media(100)), seedNote = far)
        assertTrue(notes2.isEmpty())
    }

    // ---------------- 严格相邻模式（DB 存量回填口径） ----------------

    @Test
    fun `严格_逐跳相邻照常批注`() {
        val (notes, _) = computeAdjacentNotes(
            listOf(text(100, "批注"), media(99), media(98)), strictAdjacency = true,
        )
        assertEquals("批注", notes[mid(99)])
        assertEquals("批注", notes[mid(98)])
    }

    @Test
    fun `严格_首跳不相邻即不批`() {
        // 源与目标间隔着不可见消息（库内无文字帖行）——语义不可证，宁漏勿错
        val (notes, _) = computeAdjacentNotes(
            listOf(text(100, "批注"), media(98)), strictAdjacency = true,
        )
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `严格_中段缺口断链后续全不批`() {
        val (notes, _) = computeAdjacentNotes(
            listOf(text(100, "批注"), media(99), media(97), media(96)), strictAdjacency = true,
        )
        assertEquals("批注", notes[mid(99)]) // 相邻吃上
        assertNull(notes[mid(97)])           // 缺口断链
        assertNull(notes[mid(96)])           // 断链后不再续
    }

    @Test
    fun `严格_断链后新批注源重新起批`() {
        val (notes, _) = computeAdjacentNotes(
            listOf(text(100, "批注A"), media(97), text(96, "批注B"), media(95)), strictAdjacency = true,
        )
        assertNull(notes[mid(97)])
        assertEquals("批注B", notes[mid(95)])
    }

    @Test
    fun `严格_相册隔断与上限语义不变`() {
        val rows = buildList {
            add(text(200, "推广"))
            add(media(199, albumId = 7L, caption = "剧名 第2期"))
            add(media(198, albumId = 7L))
            add(text(150, "批注"))
            for (i in 0 until ANNOTATE_MAX_MEDIA + 2) add(media(149L - i))
        }
        val (notes, _) = computeAdjacentNotes(rows, strictAdjacency = true)
        assertNull(notes[mid(198)]) // 相册隔断
        assertEquals(ANNOTATE_MAX_MEDIA, (130L..149L).count { notes.containsKey(mid(it)) })
    }
}
