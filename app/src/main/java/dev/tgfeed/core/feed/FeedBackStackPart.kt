package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

// ---------------- R182 源历史栈（返回语义矩阵 L1 主体） ----------------
//
// 用户原则（原话）：「但凡是通过跳转触发的，用户按系统返回键理应回到上一级」。
// Feed 内的源跳转（#标签/ @提及/频道名头像/找相似/深链/手气不错/机器人搜索/
// 路由子页 onPlay 切源……）此前切源即丢上一源——按返回只剩「双击退出」。
// 本件给切源家族补「源历史栈」：切源入栈（快照被离开态），返回键出栈回上一级；
// 栈底才落 R177 顶层返回语义（发现/我的返回回首页 → 首页双击退出）。
//
// 与 L2 路由子页 pending（searchReturnPending 等，R177）的关系——**时刻戳统一
// 消费序**（见 BackNavClock）：同一处理器内先 push（本跳转的源快照）后置 pending
// （戳更大），故路由跳转（试吃/热榜/搜索点结果/频道主页点格子）第一次返回恒先
// 回子页（R177 语义逐点保持），子页关掉后再弹源栈还原跳转前的源；feed 内再跳
// （如试吃频道里点 #标签）戳更新，先弹源栈回该频道——两类跳转按「最新者先回」
// 交错出栈，与用户「上一级」直觉逐例对齐（静态「L1 恒先于 L2」的反例论证见
// r182_notes.md 设计节）。
//
// 例外唯一一个：showAggregate =「回推荐流/回底」的显式用户意图——**清栈**再裸切
// （顶栏 HOME 座位/推荐 chip 走它），防 tab 摇摆拉出无限历史、防回底后返回又翻回
// 已明确离开的旧源。

/**
 * 跳转时刻钟：源栈入栈与 L2 路由 pending 置位的**同一**单调序源（进程级）。
 * 消费侧（MainActivity 返回消费器）按「戳最大者=最新跳转」先回。纯 JVM 实现，
 * 单测可 reset。
 */
object BackNavClock {
    private val seq = java.util.concurrent.atomic.AtomicLong(0L)

    fun next(): Long = seq.incrementAndGet()

    /** 单测专用：归零重置 */
    fun resetForTest() = seq.set(0L)
}

/**
 * 源栈条目：被离开态的完整快照。pop 时按此原位恢复（列表引用即快照——VM 的
 * _items 更新链一律换新 List 实例、VideoItem 不可变，旧实例天然冻结）。
 *
 * @param source 被离开的源实例（持有其游标——恢复后继续向下翻页走 nextPage 续拉）
 * @param title 被离开态的顶栏标题（取 titleAtLastSwitch——进入该态那次切源时的
 *   标题；FeedScreen 各跳转点全部「先设标题后切源」，切源时刻的现值恰是**新**标题，
 *   故上一态的标题须用上一跳的抄录值，见 FeedViewModel.titleAtLastSwitch）
 * @param items 被离开态的列表（空列表=跳转发生在加载期——pop 走整源重拉兜底）
 * @param index 被离开态的页位（恢复后经 resumeScroll 事件滚回）
 * @param mediaTypeTab / filter 被离开态的内容分区与筛选（跳转点先复位再切源的
 *   常见手法会把复位后的值一并快照——按所见即所还，不回滚复位）
 * @param stamp 入栈时刻（BackNavClock）
 */
data class SourceBackStackEntry(
    val source: FeedSource,
    val title: String,
    val items: List<VideoItem>,
    val index: Int,
    val mediaTypeTab: Int,
    val filter: FeedFilter,
    val stamp: Long,
)

/** L1/L2 统一返回消费序的候选（MainActivity 返回消费器构造后交纯策略择新） */
sealed interface BackConsumption {
    val stamp: Long

    /** 弹源栈回上一源 */
    data class PopSource(override val stamp: Long) : BackConsumption

    /** 回路由子页（L2 pending，镜像 R177 三 pending 的消费动作） */
    data class Route(val kind: RoutePendingKind, override val stamp: Long) : BackConsumption
}

/**
 * L2 路由子页 pending 的种类（R182 起新增后四个补齐缺口，前三个 R177 既有；
 * R199 增 FAVORITES、R203 增 ON_THIS_DAY、R202 增 TIME_MACHINE、R222 增
 * HASHTAG_TIMELINE、R229 增 PURSUE——枚举追加、消费逻辑泛型零改动）
 */
enum class RoutePendingKind { SEARCH, DISCOVER, CHANNEL_HOME, WATCH_HISTORY, DOWNLOADS, TASTE_MAP, GRID, FAVORITES, ON_THIS_DAY, TIME_MACHINE, HASHTAG_TIMELINE, PURSUE }

/** 源栈纯策略（单测锚点；VM 实现只做状态搬运，决策全在本件） */
object FeedBackStackPolicy {
    /** 栈深上限：防快速连跳把快照（含列表引用）无限累积 */
    const val STACK_CAP = 10

    /** 弹栈恢复的定位二发延时（一发即发可能早于重组被旧列表索引吞掉；二发兜底） */
    const val RESTORE_REEMIT_DELAY_MS = 120L

    /** 入栈（溢出丢最旧——最近的历史优先保住） */
    fun push(stack: MutableList<SourceBackStackEntry>, entry: SourceBackStackEntry) {
        stack.add(entry)
        while (stack.size > STACK_CAP) stack.removeAt(0)
    }

    /** 出栈：空栈返回 null（消费侧落 L2/L3） */
    fun pop(stack: MutableList<SourceBackStackEntry>): SourceBackStackEntry? =
        if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)

    /** 空快照条目的恢复路径：无列表可原位还——走整源重拉（位置不保，内容必达） */
    fun restoreNeedsReload(entry: SourceBackStackEntry): Boolean = entry.items.isEmpty()

    /** 统一消费序：候选中时刻戳最大者（=最新跳转）先回；全空返回 null */
    fun pickBackConsumption(candidates: List<BackConsumption>): BackConsumption? =
        candidates.maxByOrNull { it.stamp }
}

// ---------------- VM 扩展实现（状态搬运层，决策见 FeedBackStackPolicy） ----------------

/**
 * 切源入栈：快照当前态（调用点=switchSourceImpl 首行，先于任何复位——被离开态
 * 原样冻结）。标题取 [FeedViewModel.titleAtLastSwitch]（口径见条目注释）。
 */
internal fun FeedViewModel.pushSourceBackStackImpl() {
    val entry = SourceBackStackEntry(
        source = source,
        title = titleAtLastSwitch,
        items = _items.value,
        index = _currentIndex.value,
        mediaTypeTab = _mediaTypeTab.value,
        filter = _filter.value,
        stamp = BackNavClock.next(),
    )
    FeedBackStackPolicy.push(sourceBackStack, entry)
}

/** showAggregate（回底）清栈：历史归零，返回语义直接落 R177 顶层 */
internal fun FeedViewModel.clearSourceBackStackImpl() {
    sourceBackStack.clear()
}

/**
 * 弹栈回上一源（返回消费器 L1 动作）。空栈返回 false。
 * - 空快照条目：走整源重拉（switchSourceNoStackImpl——列表/位置不保，内容必达）；
 * - 正常条目：原位恢复（源实例+列表+页位+tab+筛选+标题），续拉游标随源实例自带，
 *   恢复后向下翻页自然续走；页位恢复经 resumeScroll 事件（FeedScreen 收集后
 *   pager.scrollToPage），并置「切源回顶抑制位」让 FeedScreen 的
 *   LaunchedEffect(vm.source){ scrollToPage(0) } 让路（一次性消费）。
 */
internal fun FeedViewModel.popSourceBackImpl(): Boolean {
    val entry = FeedBackStackPolicy.pop(sourceBackStack) ?: return false
    android.util.Log.i(
        "FeedViewModel",
        "源栈弹回：${source.javaClass.simpleName} → ${entry.source.javaClass.simpleName}（余深=${sourceBackStack.size}，条目=${entry.items.size}条@${entry.index}）",
    )
    if (FeedBackStackPolicy.restoreNeedsReload(entry)) {
        // 空快照（跳转时正在加载）：标题仍恢复，列表走整源重拉
        sourceTitle.value = entry.title
        titleAtLastSwitch = entry.title
        switchSourceNoStackImpl(entry.source)
        return true
    }
    // —— 原位恢复：R189 起机械体抽共享核心 applyServedStateSnapshotImpl（弹栈/
    // 列缓存/媒体tab 三消费方同一段代码，机械体单源纪律）；本处差异项：游标族取
    // 「重置」而非快照值（R182 条目不携带游标——弹回后源实例自带游标续拉，媒体
    // tab 游标从头开始）+ 筛选/tab/标题从条目回填 ——
    applyServedStateSnapshotImpl(
        newSource = entry.source,
        items = entry.items,
        index = entry.index,
        mediaTypeTabValue = entry.mediaTypeTab,
        mediaTabCursorValue = null,
        mediaTabExhaustedValue = false,
        snapshotKeysValue = emptySet(),
        filterValue = entry.filter,
        title = entry.title,
    )
    return true
}
