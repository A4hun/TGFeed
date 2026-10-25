package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R235 虫洞+航行账本单测：目标选星确定性（在图优先/计数降序/排他）/无目标诚实
 * 空态/相机路径端点与中段鼓包/easeInOut 端点/隧道强度中峰；账本读写往返/原子
 * bump/损坏文件零账缺省。
 */
class TasteWormholeTest {

    @Test
    fun `选星-排他与确定性`() {
        val profiles = mapOf(
            10L to mapOf("旅行" to 3, "日常" to 1),
            20L to mapOf("旅行" to 7),
            30L to mapOf("旅行" to 7),
            40L to mapOf("科技" to 2),
        )
        val onMap = setOf(10L, 20L, 30L)
        // 在图优先：20/30 都在图 count=7 → chatId 升序破平=20
        assertEquals(20L, TasteWormhole.pickTarget("旅行", 5L, profiles, onMap))
        // 焦点排他：焦点 20 → 30
        assertEquals(30L, TasteWormhole.pickTarget("旅行", 20L, profiles, onMap))
        // 在图压倒计数：40 不在图（即使改大计数也输给在图者）
        val profiles2 = profiles + (40L to mapOf("旅行" to 99))
        assertEquals(20L, TasteWormhole.pickTarget("旅行", 5L, profiles2, onMap))
        // 大小写折叠（R228 fold 口径）：输入大写=折叠到 profile 的 fold 键
        val enProfiles = mapOf(
            10L to mapOf("travel" to 3),
            20L to mapOf("travel" to 5),
        )
        assertEquals(20L, TasteWormhole.pickTarget("TRAVEL", 10L, enProfiles, setOf(10L, 20L)))
        // 无此 tag / 只有自己有：0
        assertEquals(0L, TasteWormhole.pickTarget("美食", 5L, profiles, onMap))
        assertEquals(0L, TasteWormhole.pickTarget("科技", 40L, profiles, onMap))
        assertEquals(0L, TasteWormhole.pickTarget("旅行", 5L, emptyMap(), onMap))
    }

    @Test
    fun `路径-端点恒等与中段鼓包`() {
        val out = FloatArray(4)
        val from = TasteFlight.FlightCam(100f, 100f, 1.0f, 0f, dive = 0f)
        val tZoom = 2.0f
        val tDive = TasteFlight.distOf(tZoom) + 100f - 260f // 目标星锚口径
        // p=0：原样
        TasteWormhole.camPath(from, 800f, 700f, tZoom, tDive, 0f, out)
        assertEquals(from.x, out[0], 1e-4f)
        assertEquals(from.y, out[1], 1e-4f)
        assertEquals(from.zoom, out[2], 1e-4f)
        assertEquals(from.dive, out[3], 1e-4f)
        // p=1：目标锚
        TasteWormhole.camPath(from, 800f, 700f, tZoom, tDive, 1f, out)
        assertEquals(800f, out[0], 1e-4f)
        assertEquals(700f, out[1], 1e-4f)
        assertEquals(tZoom, out[2], 1e-4f)
        assertEquals(tDive, out[3], 1e-3f)
        // 中段：dive 鼓包（远超两端）——「拉出扭曲」的纵深半边
        val bulges = (1..9).map { p ->
            TasteWormhole.camPath(from, 800f, 700f, tZoom, tDive, p / 10f, out)
            out[3] - kotlin.math.min(from.dive, tDive)
        }
        assertTrue(bulges.max()!! > TasteWormhole.TUNNEL_BULGE * 0.5f)
        // 中段 zoom 收窄（钻管道）
        TasteWormhole.camPath(from, 800f, 700f, tZoom, tDive, 0.5f, out)
        assertTrue(out[2] < kotlin.math.max(from.zoom, tZoom))
    }

    @Test
    fun `路径-单调无回跳`() {
        val out = FloatArray(4)
        val from = TasteFlight.FlightCam(100f, 100f, 1.0f, 0f)
        val tDive = 500f
        var prevX = -1f
        for (i in 0..20) {
            TasteWormhole.camPath(from, 800f, 700f, 2f, tDive, i / 20f, out)
            if (prevX >= 0f) assertTrue(out[0] >= prevX)
            prevX = out[0]
        }
    }

    // ==================== R242 空间相机路径（camPathSpace） ====================

    @Test
    fun `空间路径-端点恒等与停船口径`() {
        val out = FloatArray(5)
        val from = TasteSpace.SpaceCam(100f, -200f, 300f, 40f, -10f)
        val stop = 180f
        // p=0：原样（位置与朝向都不动）
        TasteWormhole.camPathSpace(from, -900f, 600f, 2000f, stop, 0f, out)
        assertEquals(from.px, out[0], 1e-4f)
        assertEquals(from.py, out[1], 1e-4f)
        assertEquals(from.pz, out[2], 1e-4f)
        assertEquals(from.yawDeg, out[3], 1e-4f)
        assertEquals(from.pitchDeg, out[4], 1e-4f)
        // p=1：目标星锚（星前 stop 单位停船）且朝向对准星
        TasteWormhole.camPathSpace(from, -900f, 600f, 2000f, stop, 1f, out)
        val cam = TasteSpace.SpaceCam(out[0], out[1], out[2], out[3], out[4])
        assertEquals(stop, TasteSpace.distTo(cam, -900f, 600f, 2000f), 1f)
        val aim = FloatArray(2)
        TasteSpace.aimAt(cam, -900f, 600f, 2000f, aim)
        assertEquals(0f, TasteSpace.angDiff(cam.yawDeg, aim[0]), 2f)
        assertEquals(0f, TasteSpace.angDiff(cam.pitchDeg, aim[1]), 2f)
    }

    @Test
    fun `空间路径-中段前冲鼓包（比线性插值更贴近目标）`() {
        val out = FloatArray(5)
        val from = TasteSpace.SpaceCam(0f, 0f, 0f, 0f, 0f)
        val target = floatArrayOf(0f, 0f, 4000f)
        val stop = 200f
        val goalDist = 4000f - stop
        // 中段 p=0.5：到目标距离应显著小于线性一半（bulge 前冲=穿隧道感）
        TasteWormhole.camPathSpace(from, target[0], target[1], target[2], stop, 0.5f, out)
        val midDist = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        val linearMid = 4000f - (4000f - goalDist) * 0.5f
        assertTrue("中段应前冲（midDist=$midDist < linear=$linearMid）", midDist < linearMid - 100f)
        // 中段朝向已在转向目标（yaw 朝 +Z 即 0 附近——目标在正前方）
        assertTrue(kotlin.math.abs(TasteSpace.angDiff(out[3], 0f)) < 30f)
    }

    @Test
    fun `ease-端点与中点`() {
        assertEquals(0f, TasteWormhole.easeInOutCubic(0f), 1e-6f)
        assertEquals(1f, TasteWormhole.easeInOutCubic(1f), 1e-6f)
        assertEquals(0.5f, TasteWormhole.easeInOutCubic(0.5f), 1e-4f)
        assertTrue(TasteWormhole.easeInOutCubic(0.25f) < 0.25f) // 前段缓
        assertTrue(TasteWormhole.easeInOutCubic(0.75f) > 0.75f) // 后段赶
    }

    @Test
    fun `隧道强度-中峰对称`() {
        assertEquals(0f, TasteWormhole.tunnelIntensity(0f), 1e-6f)
        assertEquals(0f, TasteWormhole.tunnelIntensity(1f), 1e-6f)
        assertEquals(1f, TasteWormhole.tunnelIntensity(0.5f), 1e-4f)
        assertEquals(TasteWormhole.tunnelIntensity(0.3f), TasteWormhole.tunnelIntensity(0.7f), 1e-4f)
    }

    // ==================== 航行账本 ====================

    private fun tmpFile(): File =
        Files.createTempDirectory("voyage").toFile().resolve(TasteVoyageLog.FILE_NAME)

    @Test
    fun `账本-缺失零账`() {
        val f = tmpFile()
        val c = TasteVoyageLog.load(f)
        assertEquals(0, c.shareCount)
        assertEquals(0, c.wormholeJumps)
    }

    @Test
    fun `账本-bump 往返与独立性`() {
        val f = tmpFile()
        assertEquals(1, TasteVoyageLog.bumpShare(f).shareCount)
        assertEquals(2, TasteVoyageLog.bumpShare(f).shareCount)
        // jump 不动 share
        val after = TasteVoyageLog.bumpWormhole(f)
        assertEquals(2, after.shareCount)
        assertEquals(1, after.wormholeJumps)
        // 重新 load=落盘持久
        assertEquals(2, TasteVoyageLog.load(f).shareCount)
        assertEquals(1, TasteVoyageLog.load(f).wormholeJumps)
    }

    @Test
    fun `账本-损坏文件零账缺省`() {
        val f = tmpFile()
        f.parentFile.mkdirs()
        f.writeText("garbage!!!\nshare=notanumber\njump=-5\n")
        val c = TasteVoyageLog.load(f)
        assertEquals(0, c.shareCount) // 解析失败行跳过；jump=-5 钳 0
        assertEquals(0, c.wormholeJumps)
        // bump 修复后正常
        assertEquals(1, TasteVoyageLog.bumpShare(f).shareCount)
        assertEquals(0, TasteVoyageLog.load(f).wormholeJumps)
    }
}
