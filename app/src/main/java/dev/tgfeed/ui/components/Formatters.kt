package dev.tgfeed.ui.components

/** 视频时长：m:ss，超过一小时为 h:mm:ss */
internal fun formatDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/** 文件大小：<1GB 显示 MB（一位小数），≥1GB 显示 GB（两位小数），大文件不再出现四位数 MB */
internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1024f / 1024f
    return if (mb >= 1024f) "%.2f GB".format(mb / 1024f) else "%.1f MB".format(mb)
}

/** 倍速文案：1x / 1.5x / 2x（整数不带小数点） */
internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/**
 * 成员数格式化（R197 收敛：发现页/搜索页答案区/群选择器三处同族一壳）：
 * ≥1万 一位小数「万」（如 3.2万，整万不带小数点，四舍五入）；<1万 原样数字（如 8620）。
 * 调用方需要「N 成员」后缀时自行拼接（发现页/群选择器），搜索页答案区裸用。
 */
internal fun formatMemberCount(n: Int): String {
    if (n < 10_000) return n.toString()
    val w = n / 10_000f
    return if (w == w.toInt().toFloat()) "${w.toInt()}万" else "%.1f万".format(w)
}

/**
 * 相对时间（中文体）：刚刚 / N 分钟前 / N 小时前 / N 天前（R229 收敛进共享件：
 * 追更卡首个消费方；StoriesBar storyAgeLabel / DiscoverScreen freshAgeLabel 两处
 * 旧私有同文案挂账收敛——口径完全一致，各迭代顺手迁此处即可）。
 * nowMs 缺省取当前时刻；负差值（时钟回拨）按 0「刚刚」处理。
 */
internal fun relativeTimeZh(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((nowMs - atMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}
