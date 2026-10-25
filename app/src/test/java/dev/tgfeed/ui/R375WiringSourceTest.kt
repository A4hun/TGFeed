package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * R375 用户侧播放开关换轨 wiring 源码锚（R366/R367 同款 user.dir 源定位模式）。
 * 根因判词（用户 11:2x 实锤「视频/音频正在加载缓冲时，单击屏幕的暂停/播放不生效，
 * 要等完全加载成功才能暂停」）：用户侧开关（点按切换/暂停钮）多处用 isPlaying
 * 判定——缓冲期 isPlaying=false 但 playWhenReady=true（播放意图在），点按被判成
 * 「没在播」→play() 落在已是 true 的意图上零变化＝「不生效」。仓内既有正确口径
 * （PlayerPool 后台闸/导航闸、FeedScreen 视频记账）早已用 playWhenReady，用户侧
 * 开关本批换轨收敛到 PlaybackTogglePolicy 单源。
 * 修法：五消费面（VideoPage 点按/GridVideoCell 格内开关/AudioCard 大圆钮+迷你条/
 * FloatingWindow 胶囊钮/OverlayWindowHolder 系统浮窗开关）动作与呈现换意图口径；
 * 纯「真出声」语义（镜像帧采集/跳变信号两拍基线/封面呼吸/声波/轮播与观看记账）
 * isPlaying 零改（负锚）；ENDED「先归零再播」重播链零破坏（守卫锚）。
 */
class R375WiringSourceTest {

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

    private fun videoPage(): String = src("dev/tgfeed/ui/components/VideoPage.kt")
    private fun gridVideoCell(): String = src("dev/tgfeed/ui/GridVideoCell.kt")
    private fun audioCard(): String = src("dev/tgfeed/ui/components/AudioCard.kt")
    private fun videoLoad(): String = src("dev/tgfeed/ui/components/VideoLoad.kt")
    private fun videoSeekOverlay(): String = src("dev/tgfeed/ui/components/VideoSeekOverlay.kt")

    /**
     * ① 判定单源：userPlayingIntent 全主源码树唯一定义（PlaybackTogglePolicy），
     * 五消费面全部经单源调用（不私有重写判定）。
     */
    @Test
    fun user_playing_intent_single_source_and_five_consumers() {
        val definitions = mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, text) -> Regex("fun userPlayingIntent\\(").containsMatchIn(text) }
            .toList()
        assertTrue(
            "userPlayingIntent 应只在 PlaybackTogglePolicy 单源定义，实到 ${definitions.size} 处",
            definitions.size == 1,
        )
        assertTrue(
            "单源应在 dev/tgfeed/ui/PlaybackTogglePolicy.kt",
            definitions.first().first.invariantSeparatorsPath.endsWith("dev/tgfeed/ui/PlaybackTogglePolicy.kt"),
        )
        val consumers = listOf(
            videoPage(), gridVideoCell(), audioCard(),
            src("dev/tgfeed/ui/floating/FloatingWindow.kt"),
            src("dev/tgfeed/ui/floating/OverlayWindowHolder.kt"),
        )
        consumers.forEachIndexed { i, text ->
            assertTrue(
                "消费面 ${i + 1} 应经 PlaybackTogglePolicy.userPlayingIntent 调用单源",
                Regex("PlaybackTogglePolicy\\.userPlayingIntent\\(").containsMatchIn(text),
            )
        }
    }

    /**
     * ② VideoPage 点按开关换轨：tap 分支意图口径在场、旧 isPlaying 开关形态退场；
     * ENDED 先归零再 play 的重播链原样在场（语义守卫）。
     */
    @Test
    fun video_page_tap_switches_to_intent_keeps_ended_replay_chain() {
        val text = videoPage()
        assertTrue(
            "点按开关应换意图口径（userPlayingIntent(p.playWhenReady, p.playbackState)）",
            Regex("if \\(PlaybackTogglePolicy\\.userPlayingIntent\\(p\\.playWhenReady, p\\.playbackState\\)\\)").containsMatchIn(text),
        )
        assertFalse("旧 isPlaying 开关形态应退场", text.contains("if (p.isPlaying)"))
        // ENDED 重播链（点按停在片尾=先归零再 play）：换轨后必须原样在场
        assertTrue(
            "ENDED 先归零重播链应原样在场（play() 对 ENDED 无效必须先 seekTo(0)）",
            text.contains("if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)"),
        )
        // R375 判词落档（后来者不退化回 isPlaying 口径的守档）
        assertTrue("点按分支应记 R375 判词", text.substring(text.indexOf("R375：判定换 playWhenReady")).contains("病根"))
    }

    /**
     * ③ GridVideoCell 格内开关换轨+⏸ 呈现口径补位：旧 isPlaying 开关退场；手动暂停
     * 项不再被 !buffering 吞（缓冲窗内暂停＝转圈收起+⏸ 顶上）；声波/上报 isPlaying
     * 口径零改（真出声负锚）。
     */
    @Test
    fun grid_cell_switch_and_pause_icon_presentation() {
        val text = gridVideoCell()
        assertTrue(
            "格内开关应换意图口径",
            Regex("PlaybackTogglePolicy\\.userPlayingIntent\\(p\\.playWhenReady, p\\.playbackState\\)").containsMatchIn(text),
        )
        assertFalse("旧 isPlaying 开关形态应退场", text.contains("if (p.isPlaying)"))
        // ⏸ 门：手动暂停项不被 !buffering 吞（缓冲窗内暂停后不再"双无"）
        assertTrue(
            "⏸ 门应放行手动暂停项（paused || (!buffering && !player.playWhenReady)）",
            text.contains("if (player != null && !isPlaying && (paused || (!buffering && !player.playWhenReady)))"),
        )
        assertFalse(
            "旧 ⏸ 全吞门（!buffering 连手动暂停一起吞）应退场",
            text.contains("!isPlaying && !buffering && (paused"),
        )
        // 真出声负锚：声波动效开关源与轮播上报保持 isPlaying
        assertTrue(
            "声波动效开关源应保持 isPlaying（真出声口径零改）",
            text.contains("CellSoundBars(active = isPlaying && !muteAll && volume > 0f)"),
        )
        assertTrue("格内在播状态上报源应保持 isPlaying", text.contains("isPlaying = playing"))
    }

    /**
     * ④ AudioCard 大圆钮+迷你条换轨：点按意图口径、enabled 摘缓冲闸（音频侧第二
     * 病根——缓冲窗不可点）、ENDED 归零链原样、缓冲转圈并入意图闸；封面呼吸保持
     * isPlaying（真出声负锚）。
     */
    @Test
    fun audio_card_buttons_switch_to_intent_keep_ended_chain() {
        val text = audioCard()
        // 大圆钮与迷你条两处动作同轨（意图口径判定）
        assertTrue(
            "音频钮动作应换意图口径（playIntent）且两处在场",
            Regex("if \\(playIntent\\) player\\.pause\\(\\) else player\\.play\\(\\)").findAll(text).count() == 2,
        )
        // enabled 摘缓冲闸：旧「!buffering 吞点按」=用户实锤「加载音频也不能暂停」
        assertFalse(
            "大圆钮 enabled 不应再用 !buffering 吞掉缓冲窗点按",
            text.contains("enabled = playUri != null && !buffering"),
        )
        // ENDED 先归零链（playWhenReady 意图口径下 ENDED 恒落归零分支——语义守卫）
        assertTrue(
            "音频钮 ENDED 先归零链应在场且换 playIntent 判定",
            Regex("if \\(!playIntent && player\\.playbackState == Player\\.STATE_ENDED\\)").containsMatchIn(text),
        )
        // 意图态监听：onPlayWhenReadyChanged 驱动（缓冲窗内点按暂停后图标即时翻回播放）
        assertTrue("意图态应由 onPlayWhenReadyChanged 驱动", text.contains("override fun onPlayWhenReadyChanged("))
        // 缓冲转圈并入意图闸（暂停态收起"还在加载"误读）
        assertTrue("缓冲转圈应并入意图闸（buffering && playIntent）", text.contains("if (buffering && playIntent)"))
        // 真出声负锚：封面呼吸动画保持 isPlaying 口径
        assertTrue(
            "封面呼吸应保持 isPlaying 真出声口径",
            text.contains("val coverScale = if (playing) breathScale else 1f"),
        )
    }

    /**
     * ⑤ 浮窗两开关换轨：FloatingWindow 胶囊钮/OverlayWindowHolder togglePause 意图
     * 口径；失败兜底 pause 与播完自动关窗链（各自既有语义）零改。
     */
    @Test
    fun floating_windows_toggle_switch_to_intent() {
        val floating = src("dev/tgfeed/ui/floating/FloatingWindow.kt")
        assertTrue(
            "悬浮窗胶囊钮应换意图口径",
            Regex("PlaybackTogglePolicy\\.userPlayingIntent\\(p\\.playWhenReady, p\\.playbackState\\)").containsMatchIn(floating),
        )
        assertFalse("FloatingWindow 旧 isPlaying 开关形态应退场", floating.contains("if (p.isPlaying)"))
        // 失败兜底（failed 立即拉停）与缓冲指示 !paused 闸：既有语义零改
        assertTrue("失败兜底 pause 应保持", floating.contains("if (failed) player?.pause()"))
        assertTrue("缓冲指示 !paused 闸应保持", floating.contains("if (buffering && player != null && !paused && !failed)"))

        val holder = src("dev/tgfeed/ui/floating/OverlayWindowHolder.kt")
        assertTrue(
            "系统浮窗 togglePause 应换意图口径",
            Regex("PlaybackTogglePolicy\\.userPlayingIntent\\(p\\.playWhenReady, p\\.playbackState\\)").containsMatchIn(holder),
        )
        assertFalse("OverlayWindowHolder 旧 isPlaying 开关形态应退场", holder.contains("if (p.isPlaying)"))
        // 播完自动关窗链（ENDED→onPlaybackEnded）：既有语义零改
        assertTrue("播完自动关窗链应保持", holder.contains("if (state == Player.STATE_ENDED) callbacks.onPlaybackEnded(key)"))
    }

    /**
     * ⑥ ENDED 重播链零破坏（VideoLoad 装填链本批零碰）：回看已播完视频的
     * 「先归零再 playWhenReady=true」原样在场。
     */
    @Test
    fun video_load_ended_replay_chain_untouched() {
        val text = videoLoad()
        assertTrue(
            "VideoLoad ENDED 归零链应原样在场（回看第二遍入口）",
            text.contains("if (p.playbackState == Player.STATE_ENDED) {") &&
                text.contains("p.seekTo(0)"),
        )
        assertTrue(
            "VideoLoad 起播意图置位应原样在场",
            text.contains("p.playWhenReady = true"),
        )
    }

    /**
     * ⑦ 真出声口径负锚（两口径分职的另一半）：跳变信号采集两拍基线与镜像帧采集
     * 刻意保持 isPlaying——缓冲中位置不前进/无新帧可采，若按意图口径计"在播"会
     * 产出假跳变与空转快照。
     */
    @Test
    fun true_sound_semantics_negative_anchors_kept() {
        val seek = videoSeekOverlay()
        assertTrue(
            "跳变信号两拍基线应保持 isPlaying（缓冲窗不产假跳变）",
            seek.contains("val playing = runCatching { p.isPlaying }.getOrDefault(false)"),
        )
        val vp = videoPage()
        assertTrue(
            "镜像帧采集应保持 isPlaying（渲染侧帧推进判据，负锚）",
            vp.contains("if (!runCatching { p.isPlaying }.getOrDefault(false)) continue"),
        )
    }
}
