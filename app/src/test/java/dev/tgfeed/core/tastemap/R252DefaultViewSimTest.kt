package dev.tgfeed.core.tastemap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R252 z 轴默认视角模拟（纯 JVM——无需真机）：复刻 TasteMapScreen 装配链
 * （TasteDepthSpread.spread → 240 网格团块化 → spherify/spherifyClustered 65/35）
 * + 初见相机（原点+观看加权质心 aim，pitch×0.5）+ 3048×2032 横屏视锥，
 * 量测：Y 分布（|Y| 分位/跨度）+ 默认视角屏上/中/下三段星数与仰角分布。
 * 结论进 R252 修法（任务书：z 分布 ±2000+ 与 xy 同量级；抬头低头四周有星）。
 */
class R252DefaultViewSimTest {

    private fun h01s(a: Long, b: Int): Float = TasteSpace.h01(a, b)

    /** 星数（真机量级 577） */
    private val n = 577

    /** 合成口味平面：8 团 + 弥散（1000×1000 世界） */
    private fun synth(): Array<FloatArray> {
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var seed = 12345L
        fun rnd(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFFFFFFL).toFloat() / 16777216f
        }
        val centers = arrayOf(
            floatArrayOf(260f, 300f), floatArrayOf(700f, 260f), floatArrayOf(500f, 620f),
            floatArrayOf(300f, 700f), floatArrayOf(760f, 640f), floatArrayOf(560f, 430f),
            floatArrayOf(180f, 480f), floatArrayOf(820f, 470f),
        )
        val weights = intArrayOf(90, 70, 85, 60, 50, 55, 40, 60) // 团规模
        var i = 0
        for (c in centers.indices) {
            repeat(weights[c]) {
                if (i >= n) return@repeat
                xs[i] = centers[c][0] + (rnd() - 0.5f) * 180f
                ys[i] = centers[c][1] + (rnd() - 0.5f) * 180f
                i++
            }
        }
        while (i < n) { // 弥散野星
            xs[i] = 100f + rnd() * 800f
            ys[i] = 100f + rnd() * 800f
            i++
        }
        return arrayOf(xs, ys)
    }

    @Test
    fun `默认视角模拟`() {
        val (xs, ys) = synth()
        val chatIds = LongArray(n) { 10_000L + it * 7919L }
        // 活跃度（常看星少数大头——真机口径：观看时长长尾）
        var seed = 999L
        fun rnd(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFFFFFFL).toFloat() / 16777216f
        }
        val actMs = LongArray(n) { (Math.pow(rnd().toDouble(), 4.0) * 3.6e9).toLong() } // 长尾
        val zMds = FloatArray(n) { (rnd() - 0.5f) * 80f }
        val flightZ = FloatArray(n)
        val bands = TasteDepthSpread.spread(n, zMds, chatIds, xs, ys, actMs, flightZ)

        // ---- 复刻 TasteMapScreen 6.5 团块化球面 ----
        val cellKeyOf = LongArray(n)
        val cellIndex = HashMap<Long, Int>(64)
        val sumX = ArrayList<Float>(16)
        val sumY = ArrayList<Float>(16)
        val cnt = ArrayList<Int>(16)
        val cluKey = ArrayList<Long>(16)
        for (i in 0 until n) {
            val cx = Math.floor(xs[i] / 240.0).toLong()
            val cy = Math.floor(ys[i] / 240.0).toLong()
            // R252 修复后的团键打包（与 TasteMapScreen 落地版同式——旧 (cx shl 32)|cy
            // 在 h01 掩码下 cx 位全灭=带状根因，见 TasteMapScreen R252 注释）
            val key = cx * 0x85EBCA6BL + cy * 0xC2B2AE35L
            cellKeyOf[i] = key
            val ci = cellIndex.getOrPut(key) {
                sumX += 0f; sumY += 0f; cnt += 0; cluKey += key
                sumX.size - 1
            }
            sumX[ci] = sumX[ci] + xs[i]
            sumY[ci] = sumY[ci] + ys[i]
            cnt[ci] = cnt[ci] + 1
        }
        val cluAz = FloatArray(sumX.size)
        val cluElev = FloatArray(sumX.size)
        val cluSlot = FloatArray(sumX.size)
        for (ci in sumX.indices) {
            val mx = sumX[ci] / cnt[ci]
            val my = sumY[ci] / cnt[ci]
            cluAz[ci] = atan2(-(my - 500f), mx - 500f)
            val key = cluKey[ci]
            cluElev[ci] = (h01s(key, 41) - 0.5f) * 2f * 1.0827f // ±62°
            cluSlot[ci] = h01s(key, 43)
        }
        val spX = FloatArray(n)
        val spY = FloatArray(n)
        val spZ = FloatArray(n)
        val tmp = FloatArray(3)
        var blobCount = 0
        for (i in 0 until n) {
            val blob = h01s(chatIds[i], 45) < 0.65f
            if (blob) {
                val ci = cellIndex[cellKeyOf[i]]!!
                val ownAz = atan2(-(ys[i] - 500f), xs[i] - 500f)
                var off = ownAz - cluAz[ci]
                while (off > PI.toFloat()) off -= 2f * PI.toFloat()
                while (off < -PI.toFloat()) off += 2f * PI.toFloat()
                off = (off * 0.4f).coerceIn(-0.3f, 0.3f)
                TasteSpace.spherifyClustered(
                    xs[i], ys[i], flightZ[i], chatIds[i],
                    cluAz[ci], cluElev[ci], cluSlot[ci], off, tmp,
                )
                blobCount++
            } else {
                TasteSpace.spherify(xs[i], ys[i], flightZ[i], chatIds[i], tmp)
            }
            spX[i] = tmp[0]
            spY[i] = tmp[1]
            spZ[i] = tmp[2]
        }

        // ---- Y 分布量测 ----
        val absY = spY.map { abs(it) }.sorted()
        val r3 = spX.indices.map { sqrt(spX[it] * spX[it] + spY[it] * spY[it] + spZ[it] * spZ[it]) }.sorted()
        println("团块=$blobCount/${n}  bands(near/mid/far)=${bands.toList()}")
        println("Y 分布: min|Y|=${absY.first().toInt()} p25=${absY[n / 4].toInt()} p50=${absY[n / 2].toInt()} " +
            "p75=${absY[n * 3 / 4].toInt()} max=${absY.last().toInt()}")
        println("|Y|>2000 星数=${absY.count { it > 2000f }}  |Y|<300 星数=${absY.count { it < 300f }}")
        println("半径: p10=${r3[n / 10].toInt()} p50=${r3[n / 2].toInt()} p90=${r3[n * 9 / 10].toInt()} max=${r3.last().toInt()}")
        // 仰角分段（全天球覆盖）
        val elevDeg = IntArray(12)
        for (i in 0 until n) {
            val rxy = sqrt(spX[i] * spX[i] + spZ[i] * spZ[i])
            val e = Math.toDegrees(atan2(spY[i].toDouble(), rxy.toDouble()))
            elevDeg[((e + 90.0) / 15.0).toInt().coerceIn(0, 11)]++
        }
        println("仰角 12 段(-90~90°，每段15°): ${elevDeg.toList()}")

        // ---- 初见相机（原点+观看加权质心 aim，pitch×0.5）+ 3048×2032 视锥 ----
        var wx = 0f
        var wy = 0f
        var wz = 0f
        var wgt = 0f
        for (i in 0 until n) {
            val w = (actMs[i] + 1f).coerceAtMost(3_600_000f)
            wx += spX[i] * w
            wy += spY[i] * w
            wz += spZ[i] * w
            wgt += w
        }
        val aim = FloatArray(2)
        TasteSpace.aimAt(TasteSpace.SpaceCam.ORIGIN, wx / wgt, wy / wgt, wz / wgt, aim)
        val cam = TasteSpace.SpaceCam(0f, 0f, 0f, aim[0], aim[1] * 0.5f)
        println("初见: yaw=%.1f pitch=%.1f（质心 pitch=%.1f）".format(cam.yawDeg, cam.pitchDeg, aim[1]))

        val w = 3048f
        val h = 2032f
        val focal = TasteSpace.focalOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val basis = FloatArray(9)
        TasteSpace.basis(cam.yawDeg, cam.pitchDeg, basis)
        val proj = FloatArray(4)
        var top = 0
        var mid = 0
        var bot = 0
        var vis = 0
        val elevVis = IntArray(12)
        for (i in 0 until n) {
            if (!TasteSpace.project(spX[i], spY[i], spZ[i], cam, focal, cx, cy, proj, basis)) continue
            if (proj[0] < 0f || proj[0] > w || proj[1] < 0f || proj[1] > h) continue
            vis++
            when {
                proj[1] < h / 3f -> top++
                proj[1] > h * 2f / 3f -> bot++
                else -> mid++
            }
            val rxy = sqrt(spX[i] * spX[i] + spZ[i] * spZ[i])
            val e = Math.toDegrees(atan2(spY[i].toDouble(), rxy.toDouble()))
            elevVis[((e + 90.0) / 15.0).toInt().coerceIn(0, 11)]++
        }
        println("默认视角屏内: vis=$vis/$n  上1/3=$top 中1/3=$mid 下1/3=$bot")
        println("屏内仰角 12 段: ${elevVis.toList()}")
        // ---- R252 回归锚（根因=团键打包 cx 位在 h01 掩码下全灭→整排同高度带状） ----
        // ① 仰角 12 段无段 >25%：旧键打包实锤 181/577=31.4% 集中在单段（带状盘）
        val maxSeg = elevDeg.max()
        assertTrue("仰角单段 ${maxSeg}/$n 超过 25%（团键带状回归）", maxSeg * 4 <= n)
        // ② z 与 xy 同量级：|Y| 极值 ≥2000（任务书口径）
        assertTrue("|Y| 极值 ${absY.last().toInt()} <2000（z 量级回归）", absY.last() >= 2000f)
        // ③ 默认视角上下都有可见星（「只有前方一条带」的判定锚）
        assertTrue("默认视角上 1/3=$top 下 1/3=$bot（上下可见性回归）", top > 3 && bot > 3)
    }
}
