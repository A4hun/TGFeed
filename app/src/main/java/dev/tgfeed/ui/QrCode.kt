package dev.tgfeed.ui

/**
 * 极简 QR 编码器（纯 Kotlin、零新依赖）——登录页"扫码登录"专用。
 *
 * 选型背景：app/libs 只有 TDLib 两个 AAR，没有现成 QR 库；为一个登录二维码
 * 引入 zxing 不值得，按任务口径自研。算法遵循 ISO/IEC 18004，实现参考公开领域
 * 的极简实现范式（定位/时序/对齐/格式信息布图 + RS 纠错 + 掩码罚分优选）。
 *
 * 能力边界（刻意收窄，tg://login?token=... 链接实测 44~71 字节，绰绰有余）：
 *  - 仅字节模式（byte mode），UTF-8 输入；
 *  - 纠错级别固定 L（容量最大档）；
 *  - 版本 1~6 自动选最小可容纳版本（V6-L 上限 134 字节），超出抛
 *    IllegalArgumentException（调用方回落纯文本提示）；
 *  - 掩码不固定死：按四条罚分规则从 8 个候选里选最优，避免个别链接撞出
 *    "类定位图案"导致部分扫码器误读。
 * 返回矩阵不含静区（quiet zone），静区由渲染侧（QrCodeImage）留白实现。
 */
object QrCode {

    // ---- 版本 1~6 / 纠错级 L 参数表（ISO/IEC 18004 表 9）----
    /** 各版本数据码字总数 */
    private val DATA_CODEWORDS = intArrayOf(19, 34, 55, 80, 108, 136)

    /** 各版本每个纠错块的纠错码字数 */
    private val EC_PER_BLOCK = intArrayOf(7, 10, 15, 20, 26, 18)

    /** 各版本纠错块数（本表覆盖范围内各块等长，无需长短块分组逻辑） */
    private val NUM_BLOCKS = intArrayOf(1, 1, 1, 1, 1, 2)

    /** 各版本对齐图案中心坐标（V1 无对齐图案） */
    private val ALIGN_POS = arrayOf(
        intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22),
        intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
    )

    // ---- GF(256) 数域表（本原多项式 0x11D），Reed-Solomon 纠错用 ----
    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        // 指数表加倍到 512，乘法取模时免去减 255 的分支
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else GF_EXP[GF_LOG[a] + GF_LOG[b]]

    /**
     * 编码文本为 QR 模块矩阵：size×size 布尔矩阵（true=黑模块）。
     * @throws IllegalArgumentException 内容超过 V6-L 容量（134 字节）
     */
    fun encodeText(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        // 字节模式容量口径：数据码字数 - 2（4bit 模式头 + 8bit 计数 + 终止符的保守占位数）
        val version = (1..6).firstOrNull { data.size <= DATA_CODEWORDS[it - 1] - 2 }
            ?: throw IllegalArgumentException("内容过长（${data.size} 字节），超出 V6-L 容量 134 字节")
        val size = 4 * version + 17

        // ---- 1. 比特流拼装：模式 0100 + 8bit 计数 + 数据 + 终止符 + 0xEC/0x11 交替填充 ----
        val dataCwTotal = DATA_CODEWORDS[version - 1]
        val bits = BooleanArray(dataCwTotal * 8)
        var pos = 0
        fun appendBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                bits[pos] = (value shr i) and 1 == 1
                pos++
            }
        }
        appendBits(0b0100, 4)                 // 字节模式指示符
        appendBits(data.size, 8)              // 版本 1~9 字节模式计数位宽 = 8bit
        for (b in data) appendBits(b.toInt() and 0xFF, 8)
        appendBits(0, minOf(4, dataCwTotal * 8 - pos)) // 终止符（最多 4 个 0）
        while (pos % 8 != 0) appendBits(0, 1)        // 对齐到字节边界
        var padToggle = true
        while (pos < bits.size) {
            appendBits(if (padToggle) 0xEC else 0x11, 8)
            padToggle = !padToggle
        }
        val dataCodewords = ByteArray(dataCwTotal) { i ->
            var v = 0
            for (b in 0..7) v = (v shl 1) or (if (bits[i * 8 + b]) 1 else 0)
            v.toByte()
        }

        // ---- 2. 分块算 Reed-Solomon 纠错码字，再按块交错 ----
        val numBlocks = NUM_BLOCKS[version - 1]
        val blockLen = dataCwTotal / numBlocks // 本表各块等长（V6-L: 2×68）
        val ecLen = EC_PER_BLOCK[version - 1]
        val divisor = rsDivisor(ecLen)
        val ecCodewords = Array(numBlocks) { b -> rsRemainder(dataCodewords, b * blockLen, blockLen, divisor) }
        val allCodewords = ByteArray(dataCwTotal + numBlocks * ecLen)
        var k = 0
        for (i in 0 until blockLen) for (b in 0 until numBlocks) allCodewords[k++] = dataCodewords[b * blockLen + i]
        for (i in 0 until ecLen) for (b in 0 until numBlocks) allCodewords[k++] = ecCodewords[b][i]

        // ---- 3. 功能图案布图（定位/时序/对齐/暗模块/格式信息占位） ----
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }
        fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        fun drawFinder(cx: Int, cy: Int) {
            // 7×7 定位图案 + 一圈浅色分隔符（d==4 环），越界部分裁剪
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunction(x, y, d != 2 && d != 4)
                }
            }
        }
        for (i in 0 until size) { // 时序图案（角上会被定位图案覆盖，次序无碍）
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        for (ax in ALIGN_POS[version - 1]) for (ay in ALIGN_POS[version - 1]) {
            if (isFunction[ay][ax]) continue // 与时序线/定位图案重叠的中心跳过
            for (dy in -2..2) for (dx in -2..2) {
                setFunction(ax + dx, ay + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }
        // 先用掩码 0 画一遍格式信息：目的是把格式区标记为功能区（数据填充跳过），
        // 真正选定掩码后在第 5 步重画覆盖
        drawFormatBits(modules, isFunction, size, 0)

        // ---- 4. 数据位从右下角开始蛇形填充（跳过第 6 列时序线） ----
        var bitIdx = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y][x] && bitIdx < allCodewords.size * 8) {
                        modules[y][x] = (allCodewords[bitIdx ushr 3].toInt() shr (7 - (bitIdx and 7))) and 1 == 1
                        bitIdx++
                    }
                }
            }
            right -= 2
        }

        // ---- 5. 8 个候选掩码逐一试算，按罚分选最优 ----
        var best: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val candidate = Array(size) { y -> BooleanArray(size) { x -> modules[y][x] } }
            for (y in 0 until size) for (x in 0 until size) {
                if (!isFunction[y][x] && maskBit(mask, x, y)) candidate[y][x] = !candidate[y][x]
            }
            drawFormatBits(candidate, null, size, mask)
            val p = penalty(candidate, size)
            if (p < bestPenalty) {
                bestPenalty = p
                best = candidate
            }
        }
        return best!!
    }

    /** 生成 RS 除法多项式（去首项 1，长度=degree）；root 逐轮乘 2 枚举生成根 */
    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 2)
        }
        return result
    }

    /** 数据段 [offset, offset+len) 的 RS 纠错余数（长度=divisor.size） */
    private fun rsRemainder(data: ByteArray, offset: Int, len: Int, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (i in 0 until len) {
            val factor = (data[offset + i].toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (j in result.indices) result[j] = result[j] xor gfMul(divisor[j], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    /**
     * 画格式信息（纠错级 L=01 + 3bit 掩码，BCH(15,5) 编码后异或 0x5412），双副本。
     * isFunction 非 null 时顺手把格式区标记为功能区（数据填充跳过）。
     */
    private fun drawFormatBits(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>?,
        size: Int,
        mask: Int,
    ) {
        val data = (0b01 shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits15 = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits15 shr i) and 1 != 0
        fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction?.let { it[y][x] = true }
        }
        // 第一副本（左上角，绕定位图案）
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        // 第二副本（右上角 + 左下角）
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true) // 暗模块（恒黑）
    }

    /** 8 个掩码图案的条件函数（命中=true 的数据模块要翻转） */
    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> throw IllegalArgumentException("非法掩码 $mask")
    }

    /** ISO/IEC 18004 四条罚分规则（越低越好） */
    private fun penalty(m: Array<BooleanArray>, size: Int): Int {
        var result = 0
        // N1：行/列同色连块，长度≥5 时 +3 起步每超 1 个 +1
        for (y in 0 until size) result += runPenalty(BooleanArray(size) { m[y][it] })
        for (x in 0 until size) result += runPenalty(BooleanArray(size) { m[it][x] })
        // N2：2×2 同色块每块 +3
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
        }
        // N3：行/列出现 1:1:3:1:1（黑黑白白白黑黑黑…即 1011101）且任一侧有 ≥4 白，处 +40
        for (y in 0 until size) result += finderLikePenalty(BooleanArray(size) { m[y][it] })
        for (x in 0 until size) result += finderLikePenalty(BooleanArray(size) { m[it][x] })
        // N4：黑模块占比偏离 50%，每整 5% +10
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) if (m[y][x]) dark++
        val total = size * size
        result += (kotlin.math.abs(dark * 20 - total * 10) / total) * 10
        return result
    }

    private fun runPenalty(line: BooleanArray): Int {
        var penalty = 0
        var run = 1
        for (i in 1 until line.size) {
            if (line[i] == line[i - 1]) {
                run++
                if (i == line.size - 1 && run >= 5) penalty += run - 2
            } else {
                if (run >= 5) penalty += run - 2
                run = 1
            }
        }
        return penalty
    }

    /** 类定位图案 1011101（深浅深浅深，比例 1:1:3:1:1） */
    private val FINDER_PATTERN = booleanArrayOf(true, false, true, true, true, false, true)

    private fun finderLikePenalty(line: BooleanArray): Int {
        var penalty = 0
        for (i in 0..line.size - 7) {
            var match = true
            for (j in 0..6) if (line[i + j] != FINDER_PATTERN[j]) {
                match = false
                break
            }
            if (match) {
                val lightBefore = i >= 4 && (i - 4 until i).none { line[it] }
                val lightAfter = i + 11 <= line.size && (i + 7 until i + 11).none { line[it] }
                if (lightBefore || lightAfter) penalty += 40
            }
        }
        return penalty
    }
}
