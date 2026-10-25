package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * R380 分屏「全部暂停/全部播放」失灵 wiring 源码锚（R366/R367/R375 同款 user.dir
 * 源定位模式）。
 * 根因判词（用户 11:3x 实锤「分屏播放模块的全部暂停全部播放功能不好使」）：
 * 分屏音频格（GridAudioCell）自管 ExoPlayer 不在 PlayerPool——
 * ①「全部暂停」playerPool.pauseAll() 只拉停池内 busy，池外自管音频格照播不止
 *   （同病位：后台暂停通道 MainActivity.onStop → container.pauseAllPlayback →
 *   本池 pauseAll 同样够不到）；
 * ②「全部播放」恢复循环只遍历 playGrants（授权=用户点过播放的视频格），
 *   音频格从不入授权集（无 onRequestPlay 接线）→永不被恢复；
 * ③钮态源 anyPlaying 只看 playingNow（isPlaying 真出声口径）——缓冲窗
 *   isPlaying=false 但意图在，按钮误读「全部播放」且点击 no-op
 *   （play() 落在已是 true 的意图上零变化，R375 病根的全停钮形态）。
 * 修法：池外自管播放器登记收编（PlayerPool externals 三口 + pauseAll 广播域
 * 扩覆盖）+ 全停音频快照恢复面（内容 key 守卫防换片后自动开播）+ 钮态源换
 * PlaybackTogglePolicy 意图口径（playingIntentNow 表，isPlaying 表喂轮播/记账
 * 零改——R375 两口径分职负锚）。
 */
class R380WiringSourceTest {

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

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun playerPool(): String = src("dev/tgfeed/core/playback/PlayerPool.kt")
    private fun gridScreen(): String = src("dev/tgfeed/ui/GridScreen.kt")
    private fun gridVideoCell(): String = src("dev/tgfeed/ui/GridVideoCell.kt")
    private fun gridMediaCells(): String = src("dev/tgfeed/ui/GridMediaCells.kt")

    /**
     * ① 池外收编源锚：PlayerPool externals 登记表三口（attach/detach/peek）
     * + pauseAll 广播域扩覆盖（busy 与 externals 一起拉停）——分屏音频格的自管
     * 播放器从此进全停/后台暂停广播域。
     */
    @Test
    fun pool_external_registry_and_pause_all_broadcast_domain() {
        val text = playerPool()
        assertTrue(
            "externals 登记表应在场（池外自管播放器收编的存储位）",
            text.contains("private val externals = LinkedHashMap<String, ExoPlayer>()"),
        )
        assertTrue(
            "attachExternal 登记口应在场",
            text.contains("fun attachExternal(key: String, player: ExoPlayer)"),
        )
        assertTrue(
            "detachExternal 注销口应在场（销毁/换片注销，登记生命周期与格子同进退）",
            text.contains("fun detachExternal(key: String)"),
        )
        assertTrue(
            "peekExternal 查询口应在场（「全部播放」恢复面取实例）",
            text.contains("fun peekExternal(key: String): ExoPlayer? = externals[key]"),
        )
        // pauseAll 广播域：busy 与 externals 一起拉停（两行在同一方法体内）
        val pauseAll = text.substringAfter("fun pauseAll()").substringBefore("\n    }")
        assertTrue(
            "pauseAll 应拉停池内 busy（既有行为）",
            pauseAll.contains("busy.values.forEach { it.pause() }"),
        )
        assertTrue(
            "pauseAll 应连池外 externals 一起拉停（R380 收编核心——修「全部暂停后音频格照播」）",
            pauseAll.contains("externals.values.forEach { it.pause() }"),
        )
        // 与进程级 attachExternalBackgroundGate 分职：那把刀给跨页面共享音频播放器，
        // 两者并存不混淆
        assertTrue(
            "进程级 attachExternalBackgroundGate 应保持在场（分职不吞并）",
            text.contains("fun attachExternalBackgroundGate(player: ExoPlayer)"),
        )
    }

    /**
     * ② 全停广播域覆盖锚：toggleAllPlayback 暂停分支=快照捕获（被拉停音频格）+
     * pauseAll（广播域含池外）；音频格组合期登记/销毁注销（GridMediaCells）；
     * 后台暂停通道（背景 pauser）零改动仍走 pauseAll——同广播域自动受益。
     */
    @Test
    fun pause_all_broadcast_covers_external_audio_cells() {
        val screen = gridScreen()
        assertTrue(
            "全停分支应先落音频快照（audioPausedByAll，恢复面）再 pauseAll",
            screen.contains("audioPausedByAll = gridSlots.filter {") &&
                screen.contains("it.content.mediaType == 2 && playingIntentNow[it.slotId] == true") &&
                screen.contains("}.associate { it.slotId to it.content.key }"),
        )
        // 快照判据用意图口径：缓冲中的音频=会被全停拉停=该进恢复面
        val pauseBranch = screen.substringAfter("if (anyPlaying) {").substringBefore("} else {")
        assertTrue(
            "全停分支应调 playerPool.pauseAll()（广播域）",
            pauseBranch.contains("playerPool.pauseAll()"),
        )
        // 音频格登记口：渲染循环 selfPlayerRegistrar → attachExternal/detachExternal
        assertTrue(
            "渲染循环应有自管播放器登记口（attach/detach 同一闭包）",
            screen.contains("if (p != null) playerPool.attachExternal(selfKey, p) else playerPool.detachExternal(selfKey)"),
        )
        // key 单源：登记方与恢复消费方共用 gridAudioExternalKey
        assertTrue(
            "grid:audio: 登记 key 应单源定义",
            screen.contains("private fun gridAudioExternalKey(slotId: String) = \"grid:audio:\$slotId\""),
        )
        // 后台暂停通道零改动仍走 pauseAll（externals 收编自动受益，注释判词在场）
        val bgComment = screen.substringAfter("R380：pauseAll 已含池外自管音频格")
        assertTrue(
            "后台暂停通道判词应落档（注释守档）",
            bgComment.contains("externals 收编"),
        )
        // 音频格本体：组合期上报登记、销毁先注销再释放
        val cells = gridMediaCells()
        assertTrue(
            "音频格组合期应上报登记（DisposableEffect 体上报播放器）",
            cells.contains("currentOnSelfPlayerChanged.value(player)"),
        )
        // 顺序锚：注销（null 上报）先于 release（同一 onDispose 块内）
        val reg = cells.substringAfter("currentOnSelfPlayerChanged.value(player)")
        val detachIdx = reg.indexOf("currentOnSelfPlayerChanged.value(null)")
        val releaseIdx = reg.indexOf("player.release()")
        assertTrue(
            "销毁应先注销（null 上报）再 release（顺序锚）",
            detachIdx in 0 until releaseIdx,
        )
    }

    /**
     * ③ 全播恢复面锚：视频侧 R230 授权格恢复口径零改（playGrants peek+play
     * 原样）；池外音频格按快照恢复——槽位仍在且内容 key 匹配（换片/关格后的
     * 新内容不自动开播——「不自动播」对池外格同样成立）+ 恢复后快照清空
     * （不粘滞，R230 ② allPaused 粘滞病不复活）。
     */
    @Test
    fun play_all_resume_grants_unchanged_and_audio_snapshot_guard() {
        val screen = gridScreen()
        assertTrue(
            "视频侧授权格恢复口径应原样（R230 零改负锚）",
            screen.contains("for (id in playGrants) playerPool.peek(\"grid:\$id\")?.play()"),
        )
        assertTrue(
            "池外音频恢复应走 peekExternal（登记表取实例）",
            screen.contains("playerPool.peekExternal(gridAudioExternalKey(id))?.play()"),
        )
        assertTrue(
            "恢复面应有内容 key 守卫（换片后的新内容不自动开播）",
            screen.contains("if (slot.content.key == contentKey) {"),
        )
        assertTrue(
            "恢复面应先查槽位仍在（关格后的陈快照跳过）",
            screen.contains("val slot = gridSlots.firstOrNull { it.slotId == id } ?: continue"),
        )
        assertTrue(
            "恢复后快照应清空（一轮全停一轮恢复，不粘滞）",
            screen.contains("audioPausedByAll = emptyMap()"),
        )
        assertFalse(
            "不应引入「全部自动开播」（未点过的格不拉流——安全口径保留）",
            screen.contains("for (slot in gridSlots) playerPool.peekExternal"),
        )
    }

    /**
     * ④ 钮态源意图口径锚：anyPlaying=playingNow ∪ playingIntentNow（意图口径为主，
     * 真出声口径兜时序洞）；意图表由视频格/音频格 listener（onPlayWhenReadyChanged
     * + onPlaybackStateChanged）+ 初值同步喂——R375 PlaybackTogglePolicy 单源消费。
     */
    @Test
    fun button_state_source_uses_playback_toggle_policy_intent() {
        val screen = gridScreen()
        assertTrue(
            "anyPlaying 应并入意图口径表（playingIntentNow）——修缓冲窗钮态误读+点击 no-op",
            screen.contains(
                "derivedStateOf { playingNow.values.any { it } || playingIntentNow.values.any { it } }",
            ),
        )
        assertTrue(
            "渲染循环应有意图口径上报口（playIntentReporter）",
            screen.contains("val playIntentReporter: (Boolean) -> Unit = { intent ->"),
        )
        assertTrue(
            "分派层应接线意图上报（onPlayIntentChanged = playIntentReporter）",
            screen.contains("onPlayIntentChanged = playIntentReporter,"),
        )
        val video = gridVideoCell()
        assertTrue(
            "视频格 listener 应上报意图（onPlayWhenReadyChanged→userPlayingIntent 单源）",
            video.contains("override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int)") &&
                video.contains("PlaybackTogglePolicy.userPlayingIntent(playWhenReady, p.playbackState)"),
        )
        assertTrue(
            "视频格 onPlaybackStateChanged 应上报意图（ENDED/IDLE 例外态由策略内判）",
            video.contains("PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, state)"),
        )
        assertTrue(
            "视频格应初值同步（重挂/自愈重 acquire 时监听器后到防读旧档）",
            video.contains("R380 初值同步"),
        )
        val cells = gridMediaCells()
        assertTrue(
            "音频格 tap 开关应换意图口径（R375 挂账 2 收口——修「缓冲时点暂停不生效」）",
            cells.contains("if (playIntent) player.pause() else player.play()") &&
                cells.contains("PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, state)"),
        )
        assertTrue(
            "音频格中央图标应换意图口径（缓冲窗不误挂 ⏸）",
            cells.contains("if (playIntent) Icons.Filled.PlayArrow else Icons.Filled.Pause"),
        )
        assertFalse(
            "音频格旧 isPlaying 开关形态应退场",
            cells.contains("if (playing) player.pause() else player.play()"),
        )
    }

    /**
     * ⑤ 轮播/记账零改负锚：playingNow（isPlaying 真出声）继续喂自动轮播换片判据
     * 与观看记账——R375 两口径分职不回退；GridVideoCell ⏸ 门呈现口径（R375 基线）
     * 零碰；意图表不得混入轮播判据。
     */
    @Test
    fun auto_rotate_and_watch_accounting_keep_is_playing_semantics() {
        val screen = gridScreen()
        // 自动轮播判据：可见+没在播（isPlaying 表）+未授权——三条件原样
        assertTrue(
            "自动轮播应仍用 playingNow（isPlaying 真出声口径）换片判据",
            screen.contains("it.slotId in visibleSlotIds && playingNow[it.slotId] != true &&"),
        )
        assertFalse(
            "轮播判据不应混入意图口径表（缓冲中=意图在但换片判据不动——分职不回退）",
            screen.contains("playingIntentNow[it.slotId] != true"),
        )
        // 在播上报（isPlaying 表）与观看计时起表原样成对：playingReporter 块整体
        // 位于 playIntentReporter 锚之前，块内三要素齐
        val reporter = screen.substring(
            screen.indexOf("val playingReporter"),
            screen.indexOf("val playIntentReporter"),
        )
        assertTrue(
            "playingReporter 应仍写 playingNow + 观看计时（真出声口径零改）",
            reporter.contains("playingNow[slot.slotId] = true") &&
                reporter.contains("settleGridWatch(slot.slotId)"),
        )
        val video = gridVideoCell()
        assertTrue(
            "⏸ 门呈现口径应零碰（R375 基线锚——手动暂停项不被 !buffering 吞）",
            video.contains("if (player != null && !isPlaying && (paused || (!buffering && !player.playWhenReady)))"),
        )
        // 视频格 tap 开关仍是 R375 意图口径（不因本批回退）
        assertTrue(
            "视频格点按开关应保持 R375 意图口径",
            video.contains("if (PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, p.playbackState))"),
        )
    }

    /**
     * ⑥ 池外格上报完整闭环锚：音频格意图上报四处同步（初值/state/pwr/释放），
     * 意图表与自管播放器登记都随格子生命周期进出（关格清理三连）。
     */
    @Test
    fun external_cell_intent_report_and_lifecycle_cleanup() {
        val cells = gridMediaCells()
        // 意图上报四处：初值 / onPlaybackStateChanged / onPlayWhenReadyChanged / 释放
        assertTrue(
            "音频格意图初值同步应在场（挂监听时播放器可能已带意图）",
            cells.contains("val initialIntent ="),
        )
        assertTrue(
            "音频格 onPlayWhenReadyChanged 应更新并上报意图",
            cells.contains("playIntent = PlaybackTogglePolicy.userPlayingIntent(playWhenReady, player.playbackState)") &&
                cells.contains("currentOnPlayIntentChanged.value(playIntent)"),
        )
        assertTrue(
            "音频格释放时应清意图（currentOnPlayIntentChanged.value(false)）",
            cells.contains("currentOnPlayIntentChanged.value(false)"),
        )
        val screen = gridScreen()
        // 关格清理三连：isPlaying 表 + 意图表 + 全停快照
        val closeBlock = screen.substringAfter("spans.remove(slot.slotId)").substringBefore("// R230 ⑥：关格即收授权")
        assertTrue(
            "关格应清意图表（不留陈意图）",
            closeBlock.contains("playingIntentNow.remove(slot.slotId)"),
        )
        assertTrue(
            "关格应清全停快照（不留陈恢复面）",
            closeBlock.contains("audioPausedByAll = audioPausedByAll - slot.slotId"),
        )
    }
}
