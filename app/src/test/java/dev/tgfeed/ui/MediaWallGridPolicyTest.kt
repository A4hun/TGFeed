package dev.tgfeed.ui

import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R171 二级页适配政策单测（纯函数 + 政策锚）：
 *  - [mediaWallColumns]：封面墙列数口径（与 GridCells.Adaptive 同公式同 px 取整）；
 *  - [MediaWallCellMin]/[SheetMaxWidth]：政策常量锚（防手滑漂移）。
 *  R175 增补：封面墙 pinch 列数档位（⑥ 节）——档位序列/档位代表宽/夹域/手势映射
 *  与「未捏过=默认锚」回归红线。
 *
 * 夹具基准（与 ContentAwareMaxWidthTest 同源）：
 *  - 某平板（1600×2560 @400dpi，density 2.5）→ 竖 640dp / 横 1024dp；
 *  - 主力高分平板：2032×3048 @400dpi → 竖 812.8dp（R36 实测口径）；
 *  - 手机竖屏主流：360dp（2x/3x/3.5x）、393dp（2.75x）。
 *
 * px 域诚实口径：105dp @2.5 取整为 263px（=105.2dp），dp 域直除会把 640dp 竖屏
 * 误判 6 列、真机实为 5 列——本测试按字节码实证公式（roundToPx + 整除）锚定。
 */
class MediaWallGridPolicyTest {

    // ---------------- ① 政策常量锚 ----------------

    @Test
    fun `政策锚_封面墙最小格宽105dp`() {
        assertEquals(105f, MediaWallCellMin.value, 0f)
    }

    @Test
    fun `政策锚_弹层统一限宽560dp`() {
        assertEquals(560f, SheetMaxWidth.value, 0f)
    }

    // ---------------- ② 回归红线：手机竖屏 3 列不变 ----------------
    // 旧口径为 GridCells.Fixed(3)，任何主流手机竖屏不得变列数（用户无感知）。

    @Test
    fun `红线_手机竖屏360dp_全主流密度_频道主页3列`() {
        // 频道主页：间距 2dp、无横向内衬（可用宽=屏宽）
        assertEquals(3, mediaWallColumns(360f, 2f, 2f))
        assertEquals(3, mediaWallColumns(360f, 3f, 2f))
        assertEquals(3, mediaWallColumns(360f, 3.5f, 2f))
    }

    @Test
    fun `红线_手机竖屏393dp_搜索墙3列`() {
        // 搜索墙：间距 6dp、横向内衬 12dp×2（可用宽=屏宽-24）
        assertEquals(3, mediaWallColumns(393f - 24f, 2.75f, 6f))
    }

    @Test
    fun `红线_手机竖屏360dp_搜索墙3列`() {
        // 336dp 可用 @3x：1008px，(1008+18)/(315+18)=3.08 → 3（105dp 是含 12dp 内衬后的临界下界）
        assertEquals(3, mediaWallColumns(336f, 3f, 6f))
    }

    // ---------------- ③ 平板列数涌现（用户实锤修复点） ----------------

    @Test
    fun `华为平板竖屏640dp_频道主页5列`() {
        // 640dp@2.5：1600px，min=263px，gap=5px → (1605)/(268)=5.99 → 5（px 取整致 6 列挤不进）
        assertEquals(5, mediaWallColumns(640f, 2.5f, 2f))
    }

    @Test
    fun `华为平板横屏1024dp_频道主页9列`() {
        // 2560px → 2565/268=9.57 → 9，格宽 (2560-40)/9≈280px=112dp（与手机格物理尺寸同量级）
        assertEquals(9, mediaWallColumns(1024f, 2.5f, 2f))
    }

    @Test
    fun `主力高分平板竖屏812_8dp_频道主页7列`() {
        // 2032px → 2037/268=7.6 → 7，格宽 (2032-30)/7≈286px=114dp
        assertEquals(7, mediaWallColumns(812.8f, 2.5f, 2f))
    }

    @Test
    fun `主力平板搜索墙_内容列限宽640dp内_5列`() {
        // 搜索页内容列被 adaptiveContentWidth 限到 min(812.8×0.86,640)=640dp，
        // 网格可用 616dp@2.5=1540px → 1555/278=5.59 → 5
        assertEquals(5, mediaWallColumns(616f, 2.5f, 6f))
    }

    // ---------------- ④ 不变式：格宽恒不小于最小宽（Adaptive 语义契约） ----------------

    @Test
    fun `不变式_列数n≥2时格宽恒不小于最小格宽`() {
        // n=floor((A+S)/(M+S)) ⟹ n·M+(n-1)·S ≤ A ⟹ 格宽=(A-(n-1)S)/n ≥ M：
        // 公式若漂移（升 Compose 版本/改常量），此不变式先红——提示人工复核政策表。
        for (density in floatArrayOf(2f, 2.5f, 2.75f, 3f, 3.5f)) {
            for (spacing in floatArrayOf(0f, 2f, 6f)) {
                var w = 200f
                while (w <= 1300f) {
                    val n = mediaWallColumns(w, density, spacing)
                    if (n >= 2) {
                        val aPx = (w * density).roundToInt()
                        val sPx = (spacing * density).roundToInt()
                        val mPx = (MediaWallCellMin.value * density).roundToInt()
                        val cellPx = (aPx - (n - 1) * sPx) / n.toFloat()
                        assertTrue(
                            "w=$w d=$density s=$spacing n=$n 格宽 $cellPx < 最小 $mPx",
                            cellPx >= mPx,
                        )
                    }
                    w += 7f
                }
            }
        }
    }

    // ---------------- ⑤ 防御兜底 ----------------

    @Test
    fun `退化_可用宽非正_至少1列`() {
        assertEquals(1, mediaWallColumns(0f, 2.5f, 2f))
        assertEquals(1, mediaWallColumns(-100f, 2.5f, 2f))
    }

    @Test
    fun `退化_密度非正_至少1列`() {
        assertEquals(1, mediaWallColumns(640f, 0f, 2f))
        assertEquals(1, mediaWallColumns(640f, -1f, 2f))
    }

    @Test
    fun `极窄可用_1列兜底_不返回0`() {
        // 90dp 可用 < 105dp 最小宽：Adaptive 语义钳到 1 列（格宽收缩铺满），不是 0 列
        assertEquals(1, mediaWallColumns(90f, 2f, 2f))
    }

    // ---------------- ⑥ R175 pinch 列数档位（档位=封面大小偏好，落盘 AppPrefs） ----------------

    @Test
    fun `政策锚_pinch最密档格宽84dp_默认锚105dp不变`() {
        assertEquals(84f, MediaWallPinchCellFloor.value, 0f)
        assertEquals(0.8f, MediaWallPinchCellFloor.value / MediaWallCellMin.value, 0.001f)
    }

    @Test
    fun `档位序列_各屏上下限涌现`() {
        // 华为平板 640dp 竖 @2.5：84dp 地板 → (1600+5)/(210+5)=7.4 → 7 列封顶
        assertEquals(listOf(2, 3, 4, 5, 6, 7), mediaWallPinchDetents(640f, 2.5f, 2f).toList())
        // 华为平板 1024dp 横：2565/215=11.9 → 11 列封顶
        assertEquals((2..11).toList(), mediaWallPinchDetents(1024f, 2.5f, 2f).toList())
        // 主力手机 393dp 竖 @2.75：105 锚默认 3 列，84dp 地板 → 4 列（密集偏好空间）
        assertEquals(listOf(2, 3, 4), mediaWallPinchDetents(393f, 2.75f, 2f).toList())
        // 320dp 古董机：84dp 地板也只容 3 列（与默认列数同——档位序列退化但不空）
        assertEquals(listOf(2, 3), mediaWallPinchDetents(320f, 2f, 2f).toList())
    }

    @Test
    fun `档位序列_退化输入_至少含最少列数`() {
        assertEquals(listOf(2), mediaWallPinchDetents(0f, 2.5f, 2f).toList())
        assertEquals(listOf(2), mediaWallPinchDetents(-100f, 2.5f, 2f).toList())
        assertEquals(listOf(2), mediaWallPinchDetents(90f, 2f, 2f).toList())
    }

    @Test
    fun `不变式_档位代表宽往返_恰好出该列数`() {
        // 档位落点确定性（px 取整防漂移的核心契约）：对每个档 n，
        // mediaWallColumns(detentCellMin(n)) == n——dp→px→dp 往返 ±0.5px 内不穿档
        for (density in floatArrayOf(2f, 2.5f, 2.75f, 3f, 3.5f)) {
            for (spacing in floatArrayOf(0f, 2f, 6f)) {
                var w = 320f
                while (w <= 1300f) {
                    val detents = mediaWallPinchDetents(w, density, spacing)
                    for (n in detents) {
                        val cellMin = mediaWallDetentCellMin(n, w, density, spacing)
                        assertEquals(
                            "w=$w d=$density s=$spacing detent n=$n cellMin=$cellMin",
                            n,
                            mediaWallColumns(w, density, spacing, cellMin),
                        )
                    }
                    w += 7f
                }
            }
        }
    }

    @Test
    fun `不变式_档位代表宽_格宽不小于地板`() {
        // 档位代表的格子实际宽（(A-(n-1)S)/n px）≥ 最密地板（用户偏好不许暗穿下界）
        for (density in floatArrayOf(2f, 2.5f, 3f)) {
            for (spacing in floatArrayOf(0f, 2f, 6f)) {
                var w = 320f
                while (w <= 1300f) {
                    for (n in mediaWallPinchDetents(w, density, spacing)) {
                        val aPx = (w * density).roundToInt()
                        val sPx = (spacing * density).roundToInt()
                        val cellPx = (aPx - (n - 1) * sPx) / n.toFloat()
                        assertTrue(
                            "w=$w d=$density s=$spacing n=$n cell=$cellPx",
                            cellPx >= MediaWallPinchCellFloor.value * density - 1f,
                        )
                    }
                    w += 7f
                }
            }
        }
    }

    @Test
    fun `夹域_偏好穿界拉回合法域`() {
        // 密过头（60dp）→ 地板 84；巨封面（400dp 超过 2 列代表）→ 2 列代表宽；
        // 界内原样
        val w = 640f
        val d = 2.5f
        val s = 2f
        assertEquals(84f, clampMediaWallCellPref(60f, w, d, s), 0.01f)
        assertEquals(84f, clampMediaWallCellPref(0f, w, d, s), 0.01f)
        val twoCol = mediaWallDetentCellMin(2, w, d, s)
        assertEquals(twoCol, clampMediaWallCellPref(400f, w, d, s), 0.01f)
        assertEquals(120f, clampMediaWallCellPref(120f, w, d, s), 0.01f)
    }

    @Test
    fun `夹域_退化输入_回落默认锚`() {
        assertEquals(105f, clampMediaWallCellPref(120f, 0f, 2.5f, 2f), 0f)
        assertEquals(105f, clampMediaWallCellPref(120f, 640f, 0f, 2f), 0f)
    }

    @Test
    fun `手势映射_捏开列数变少_捏合列数变多`() {
        val detents = mediaWallPinchDetents(640f, 2.5f, 2f) // [2..7]
        // 用户原话场景：5 列捏开 → 3 列、再捏开 → 2 列；捏合回来变多列
        assertEquals(5, mediaWallPinchTargetColumns(5, 1f, detents))      // 起手不动
        assertEquals(4, mediaWallPinchTargetColumns(5, 1.2f, detents))    // 5/1.2=4.17→4
        assertEquals(3, mediaWallPinchTargetColumns(5, 1.45f, detents))   // 5/1.45=3.45→3
        assertEquals(2, mediaWallPinchTargetColumns(5, 2.5f, detents))    // 捏到头 2 列
        assertEquals(3, mediaWallPinchTargetColumns(3, 0.9f, detents))    // 3/0.9=3.33→3
        assertEquals(4, mediaWallPinchTargetColumns(3, 0.78f, detents))   // 3/0.78=3.85→4
        assertEquals(7, mediaWallPinchTargetColumns(3, 0.2f, detents))    // 合到头夹上限
        assertEquals(7, mediaWallPinchTargetColumns(7, 0.5f, detents))    // 起手在上限不再多
    }

    @Test
    fun `手势映射_往返无累积漂移`() {
        // 起始列数锚定在手势开始：同一手势内 scale 往返跨阈值，目标只由
        // startColumns/scale 决定——回起点即回原档（无逐帧乘法漂移）
        val detents = mediaWallPinchDetents(640f, 2.5f, 2f)
        val start = 5
        assertEquals(4, mediaWallPinchTargetColumns(start, 1.3f, detents))
        assertEquals(start, mediaWallPinchTargetColumns(start, 1f, detents))
        assertEquals(3, mediaWallPinchTargetColumns(start, 1.7f, detents))
        assertEquals(start, mediaWallPinchTargetColumns(start, 1f, detents))
    }

    @Test
    fun `手势映射_退化_空档位与非正参数`() {
        assertEquals(5, mediaWallPinchTargetColumns(5, 1.5f, IntArray(0)))
        assertEquals(2, mediaWallPinchTargetColumns(0, 1.5f, intArrayOf(2, 3, 4)))
        assertEquals(3, mediaWallPinchTargetColumns(3, 0f, intArrayOf(2, 3, 4)))
        assertEquals(3, mediaWallPinchTargetColumns(3, -2f, intArrayOf(2, 3, 4)))
    }

    @Test
    fun `回归红线_无偏好默认锚_列数与R171政策表一致`() {
        // R175 不改变未捏过用户的世界：默认锚 105dp 的列数 = 既有政策表全部条目
        assertEquals(3, mediaWallColumns(393f, 2.75f, 2f))
        assertEquals(5, mediaWallColumns(640f, 2.5f, 2f))
        assertEquals(9, mediaWallColumns(1024f, 2.5f, 2f))
    }
}
