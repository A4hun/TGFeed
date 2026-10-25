package dev.tgfeed.core.td

import io.mockk.mockk
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R219 caption 补全优先级单测（R257b 回退链化迁移：buildCaptionOverrides 已更名
 * buildAdjacentNotes——只产链④批注证据；链②相册共享证据独立供给，优先级裁决收口在
 * resolveTitle。本套断言全部走「链」口径，与生产侧 toVideoItem 的裁决路径一致）：
 *
 * 根因（用户实锤）：频道「相册帖（caption 挂在其中一名成员上）→ 紧随一条文字帖」
 * 形态下，旧版形态B（文字帖批注）优先于形态A（相册自身共享 caption），空 caption
 * 成员被喂了帖文（推广/ping 🔔），相册自家正文标题被顶掉——用户看到的标题位
 * 只剩一个铃铛图标。链化后的口径不变：
 *  - 链②（相册共享）＞ 链④（时间邻接批注）——结构证据强于时间邻接；
 *  - 链②命中清链④批注态（批注不得穿透过"带自身 caption 的完整相册"）；
 *  - 纯 ping 帖（无任何字母数字正文的 🔔/🔥/分割线）不进批注位。
 *
 * 接收体手法沿用 AggregateCursorCodecTest：被测函数不触达网关状态，
 * mockk 经 Objenesis 实例化不跑 init 块（不加载 tdjni）。
 */
class CaptionOverridePriorityTest {

    private val gw = mockk<TdLibGateway>()

    // ---------------- 夹具：裸 TdApi.Message 构造（只填被测逻辑读到的字段） ----------------

    private fun textMsg(id: Long, text: String) = TdApi.Message().apply {
        this.id = id
        content = TdApi.MessageText().apply {
            this.text = TdApi.FormattedText().apply { this.text = text }
        }
    }

    private fun videoMsg(id: Long, albumId: Long, caption: String = "") = TdApi.Message().apply {
        this.id = id
        this.mediaAlbumId = albumId
        content = TdApi.MessageVideo().apply {
            this.caption = TdApi.FormattedText().apply { this.text = caption }
        }
    }

    /**
     * 生产同口径的链裁决测试入口：buildAdjacentNotes 产链④证据 + 链②相册共享表
     *（垫底 [albumExtra] + 本段新见——与 buildAdjacentNotes 内部隔断判定同源；生产侧
     * 由 mergedAlbumCaptions 跨页缓存供给，测试段内直扫等价）→ resolveTitle 裁决。
     * 返回（链裁决标题, 页尾批注态）。
     */
    private fun chainTitle(
        m: TdApi.Message,
        msgs: List<TdApi.Message>,
        seed: TdLibGateway.TailNote? = null,
        albumExtra: Map<Long, String> = emptyMap(),
    ): Pair<String?, TdLibGateway.TailNote?> {
        val (notes, tail) = gw.buildAdjacentNotes(msgs, seedNote = seed, albumCapsExtra = albumExtra)
        val albumCaps = HashMap<Long, String>()
        albumCaps.putAll(albumExtra)
        for (x in msgs) {
            if (x.mediaAlbumId == 0L) continue
            val cap = gw.mediaCaptionOf(x)
            if (cap.isNotBlank()) albumCaps[x.mediaAlbumId] = cap
        }
        return gw.resolveTitle(m, TitleChainContext(albumCaps = albumCaps, adjacentNotes = notes)) to tail
    }

    // ---------------- ① 链② ＞ 链④（根因场景） ----------------

    @Test
    fun `相册自身caption胜过紧随文字帖`() {
        // 新→旧：[推广文字帖, 相册caption载体, 相册空成员×2]（同 albumId）
        val msgs = listOf(
            textMsg(100, "看完记得点关注不迷路"),
            videoMsg(99, albumId = 7L, caption = "剧名A 第1期"),
            videoMsg(98, albumId = 7L),
            videoMsg(97, albumId = 7L),
        )
        // 空成员链②拿到相册自家 caption，不是链④紧随文字帖
        assertEquals("剧名A 第1期", chainTitle(msgs[2], msgs).first)
        assertEquals("剧名A 第1期", chainTitle(msgs[3], msgs).first)
        // 批注被带 caption 的相册隔断：页尾无活跃批注
        assertNull(chainTitle(msgs[2], msgs).second)
    }

    @Test
    fun `载体在最旧位_空成员仍取相册caption`() {
        // 相册 caption 载体在组内最旧位置：链②建表是全量预扫，不依赖数组序
        val msgs = listOf(
            textMsg(100, "推广文字"),
            videoMsg(99, albumId = 7L),
            videoMsg(98, albumId = 7L),
            videoMsg(97, albumId = 7L, caption = "剧名B 第2期"),
        )
        assertEquals("剧名B 第2期", chainTitle(msgs[1], msgs).first)
        assertEquals("剧名B 第2期", chainTitle(msgs[2], msgs).first)
    }

    // ---------------- ② ping 帖守卫 ----------------

    @Test
    fun `纯ping帖不进批注位`() {
        // 🔔/🔥/分割线等无字母数字正文的提醒帖：不足以当任何媒体的批注
        val msgs = listOf(
            textMsg(100, "🔔"),
            videoMsg(99, albumId = 0L),
        )
        val (title, tail) = chainTitle(msgs[1], msgs)
        assertNull(title)
        assertNull(tail)
    }

    @Test
    fun `含正文文字帖照常批注`() {
        // 有字母数字正文的文字帖（正常链④）不受守卫误伤
        val msgs = listOf(
            textMsg(100, "本批是剧照花絮 12 张"),
            videoMsg(99, albumId = 0L),
        )
        val (title, tail) = chainTitle(msgs[1], msgs)
        assertEquals("本批是剧照花絮 12 张", title)
        assertEquals("本批是剧照花絮 12 张", tail!!.text)
    }

    // ---------------- ③ 无链②证据时链④照常（既有语义不回归） ----------------

    @Test
    fun `无caption相册_文字帖批注照常`() {
        // 相册全组无 caption（载体不在窗内、缓存也没有）→ 紧随文字帖批注仍然成立
        val msgs = listOf(
            textMsg(100, "这批是舞台直拍"),
            videoMsg(99, albumId = 7L),
            videoMsg(98, albumId = 7L),
        )
        assertEquals("这批是舞台直拍", chainTitle(msgs[1], msgs).first)
        assertEquals("这批是舞台直拍", chainTitle(msgs[2], msgs).first)
    }

    // ---------------- ④ 批注不穿透带 caption 的相册 ----------------

    @Test
    fun `批注被完整相册隔断_不串到更旧媒体`() {
        // 新→旧：[文字帖, 相册(有caption), 更旧的散媒体]——散媒体不得吃穿透的批注
        val msgs = listOf(
            textMsg(100, "本期更新说明"),
            videoMsg(99, albumId = 7L, caption = "剧名C 第3期"),
            videoMsg(98, albumId = 7L),
            videoMsg(97, albumId = 0L), // 相册下方（更旧）的散媒体
        )
        assertEquals("剧名C 第3期", chainTitle(msgs[2], msgs).first)
        assertNull(chainTitle(msgs[3], msgs).first) // 批注没有穿透相册
    }

    // ---------------- ⑤ 跨页缓存垫底（albumCapsExtra）也走链② ----------------

    @Test
    fun `载体不在窗内_跨页缓存垫底仍胜过文字帖`() {
        // 相册 caption 载体落在邻页，跨页缓存（albumCapsExtra）供给共享 caption：
        // 仍然先于本窗内的紧随文字帖
        val msgs = listOf(
            textMsg(100, "推广帖"),
            videoMsg(99, albumId = 7L),
        )
        assertEquals("剧名D 第4期", chainTitle(msgs[1], msgs, albumExtra = mapOf(7L to "剧名D 第4期")).first)
    }

    // ---------------- ⑥ seed 跨页接续 + 被完整相册清位 ----------------

    @Test
    fun `seed批注接续_遇带caption相册即断`() {
        // 上一页页尾批注（seedNote）：本页头部空媒体先接上；随后带 caption 的相册
        // 清批注态，更旧成员不再吃它（与 ④ 同语义的跨页形态）
        // R285 实锤修复配套：TDLib messageId=序号<<20——测试 id 改用真实形态
        val seed = TdLibGateway.TailNote(textMsgId = 200L shl 20, text = "上一页批注", used = 1)
        val msgs = listOf(
            videoMsg(196L shl 20, albumId = 0L),      // head：与 seed 文字帖序号相邻（差 4）
            videoMsg(195L shl 20, albumId = 7L, caption = "剧名E 第5期"),
            videoMsg(194L shl 20, albumId = 7L),
            videoMsg(193L shl 20, albumId = 0L),      // 相册之下的散媒体：不得吃穿透批注
        )
        assertEquals("上一页批注", chainTitle(msgs[0], msgs, seed = seed).first) // 接续成立（seed 邻接校验过）
        assertEquals("剧名E 第5期", chainTitle(msgs[2], msgs, seed = seed).first)
        assertNull(chainTitle(msgs[3], msgs, seed = seed).first)                 // 批注断在完整相册
        assertNull(chainTitle(msgs[0], msgs, seed = seed).second)                // 批注态已被清：无页尾可携带
    }

    @Test
    fun `seed批注接续_无相册时贯穿本页`() {
        // 对照组：没有带 caption 的相册拦路时，seed 批注照旧贯穿到本页空媒体
        // R285 配套：真实 id 形态（序号<<20）
        val seed = TdLibGateway.TailNote(textMsgId = 200L shl 20, text = "上一页批注", used = 1)
        val msgs = listOf(
            videoMsg(196L shl 20, albumId = 0L),
            videoMsg(195L shl 20, albumId = 0L),
        )
        val (notes, tail) = gw.buildAdjacentNotes(msgs, seedNote = seed)
        assertEquals("上一页批注", notes[196L shl 20])
        assertEquals("上一页批注", notes[195L shl 20])
        assertEquals("上一页批注", tail!!.text)
        assertEquals(3, tail!!.used) // 1（上页）+ 本页 2 条
    }

    @Test
    fun `seed接续按序号域判定_原始id差值骗局`() {
        // R285 实锤回归锚：TDLib messageId=序号<<20——真实相邻两消息原始 id 差 2^20，
        // 若按原始 id 比 1..7 恒假（旧 bug：跨页批注接续永不生效）。
        // 本例：序号域相邻（差 2）应接续；若被原始 id 骗则拒绝=测试红。
        val seed = TdLibGateway.TailNote(textMsgId = 198L shl 20, text = "跨页批注", used = 0)
        val msgs = listOf(videoMsg(196L shl 20, albumId = 0L))
        val (notes, _) = gw.buildAdjacentNotes(msgs, seedNote = seed)
        assertEquals("跨页批注", notes[196L shl 20])
    }

    @Test
    fun `seed接续按序号域判定_超窗真拒`() {
        // 序号域差超 ANNOTATE_MAX_MEDIA+1=7 才真拒（防「永远接续」误判）
        val seed = TdLibGateway.TailNote(textMsgId = 300L shl 20, text = "远古批注", used = 0)
        val msgs = listOf(videoMsg(100L shl 20, albumId = 0L))
        val (notes, tail) = gw.buildAdjacentNotes(msgs, seedNote = seed)
        assertEquals(null, notes[100L shl 20])
    }

    // ---------------- ⑦ 计数上界与清位后的独立批注 ----------------

    @Test
    fun `被隔断后的新文字帖重新起批`() {
        // 完整相册清位后，更旧的文字帖开始自己的批注段（清位≠永久废批注）
        val msgs = listOf(
            textMsg(100, "帖一"),
            videoMsg(99, albumId = 7L, caption = "剧名F 第6期"),
            videoMsg(98, albumId = 7L),
            textMsg(97, "帖二"),
            videoMsg(96, albumId = 0L),
        )
        assertEquals("剧名F 第6期", chainTitle(msgs[2], msgs).first)
        assertEquals("帖二", chainTitle(msgs[4], msgs).first) // 帖二在新位置起批，未被帖一串染
        assertEquals("帖二", chainTitle(msgs[4], msgs).second!!.text)
    }

    @Test
    fun `非相册空媒体批注计数上限不变`() {
        // ANNOTATE_MAX_MEDIA 上限语义不回归：第 7 条散媒体起不再吃批注
        val msgs = buildList {
            add(textMsg(100, "批注正文"))
            for (i in 0 until ANNOTATE_MAX_MEDIA + 1) add(videoMsg(99L - i, albumId = 0L))
        }
        val (notes, _) = gw.buildAdjacentNotes(msgs)
        var annotated = 0
        for (i in 0 until ANNOTATE_MAX_MEDIA + 1) if (notes.containsKey(99L - i)) annotated++
        assertEquals(ANNOTATE_MAX_MEDIA, annotated)
    }

    @Test
    fun `旧语义回归锚_纯文字批注场景行为不变`() {
        // 纯链④（无相册参与）与修复前行为完全一致：证明修的是优先级不是批注本身
        val msgs = listOf(
            textMsg(100, "先发图后说明"),
            videoMsg(99, albumId = 0L),
            videoMsg(98, albumId = 0L),
        )
        val (notes, tail) = gw.buildAdjacentNotes(msgs)
        assertEquals("先发图后说明", notes[99L])
        assertEquals("先发图后说明", notes[98L])
        assertTrue(tail!!.used == 2)
    }
}
