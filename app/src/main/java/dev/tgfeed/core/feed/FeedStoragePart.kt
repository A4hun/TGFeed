package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.storage.fmtMb
import dev.tgfeed.core.storage.measureStorageOverview
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- R196 存储管理（设置页「存储管理」卡的 VM 数据面） ----------------
//
// 数据两个源：① StorageGovernor（真实网关：概览盘测 + 立即清理整轮 + 清理日志）；
// ② 纯盘测 [measureStorageOverview]（演示/Mock 模式：概览照量、清理按钮走
// 「当前模式不支持」口径——Mock 无 TDLib 客户端无从清）。清理日志行数据源是
// prefs（StorageGovernor 落盘的单一写入方），本 part 只做读侧与格式化。
// StateFlow 字段（_storageOverview/_storageCleanLog）声明在 FeedViewModel 本体
//（同 _downloads 惯例），本文件只挂实现。

/** 清理日志行的占位文案（从未清理过时；VM 字段初值与 buildCleanLogLine 共用单源） */
internal const val CLEAN_LOG_PLACEHOLDER = "自动清理运行中，完成后这里会显示记录"

/**
 * 刷新存储概览 + 清理日志行（IO 线程盘测；设置页进入时/手动清理完成后调用）。
 * 幂等可重入：每次全量重测（walk 数千 stat 调用，毫秒级，值得全量换准确）。
 */
internal fun FeedViewModel.refreshStorageOverviewImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        val overview = (gateway as? TdLibGateway)?.storageGovernor?.storageOverview()
            ?: measureStorageOverview(app.filesDir)
        _storageOverview.value = overview
        _storageCleanLog.value = buildCleanLogLine()
    }
}

/**
 * 清理日志行（一行口径，任务书 2c）：「上次清理：今天 16:20 · 释放 2890.5 MB」；
 * 从未清理过返回占位语（卡片上仍可见，让用户知道这行以后会长出来）。
 * 纯函数（prefs 读 + 格式化），顺手供单测对格式断言。
 */
internal fun FeedViewModel.buildCleanLogLine(): String {
    val at = prefs.storageLastCleanAtMs
    if (at <= 0L) return CLEAN_LOG_PLACEHOLDER
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(at)) ==
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
    val pattern = if (sameDay) "今天 HH:mm" else "M月d日 HH:mm"
    val time = runCatching { SimpleDateFormat(pattern, Locale.CHINA).format(Date(at)) }
        .getOrDefault("")
    return "上次清理：$time · 释放 ${fmtMb(prefs.storageLastCleanFreedBytes)} MB"
}

/**
 * 手动「立即清理」（R196 起 = governor 整轮激进口径，替代旧裸
 * OptimizeStorage(-1…) 无约束调用）：真实网关释放 temp 碎片 + 全载淘汰 + 水位闸，
 * 返回带分项的摘要文本；清理完成后概览与日志行一并刷新（释放量立刻反映到卡上）。
 */
internal fun FeedViewModel.cleanupStorageImpl(onDone: (String) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
        val text = runCatching {
            (gateway as? TdLibGateway)?.storageGovernor?.runManualCleanup()
                ?: "当前模式不支持存储清理"
        }.getOrElse { "清理失败：${it.message}" }
        launch(Dispatchers.IO) { refreshStorageOverviewImpl() }
        launch(Dispatchers.Main) { onDone(text) }
    }
}
