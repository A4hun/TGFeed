package dev.tgfeed.ui

/**
 * R422 UI 测试 testTag 常量单一出处（只做节点定位，不改行为）。
 * 测试断言只用 tag、节点计数、布尔状态，永不读取界面文字。
 */
internal object UiTestTags {
    // R413 关键词屏蔽卡
    const val KW_MUTE_INPUT = "kwMute_input"
    const val KW_MUTE_ADD = "kwMute_add"
    const val KW_MUTE_CHIP = "kwMute_chip"
    const val KW_MUTE_CHIP_DELETE = "kwMute_chipDelete"

    // R418b 时间线
    const val TIMELINE_LIST = "timeline_list"
    const val TIMELINE_CARD_PREFIX = "timeline_card_"
    fun timelineCard(index: Int) = "${TIMELINE_CARD_PREFIX}${index}"

    // R415 分屏格子
    const val GRID_CANVAS = "grid_canvas"
    const val GRID_CELL_PREFIX = "grid_cell_"
    fun gridCell(id: String) = "${GRID_CELL_PREFIX}${id}"
}
