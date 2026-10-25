package dev.tgfeed.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------- 频道身份色（R222 共享件；类问题收敛，见注释） ----------------

/**
 * 频道身份色：按 chatId 稳定取一个柔和底色（floorMod 避免负值）。
 *
 * **身份函数非强调色**（R207 纪律口径：字母头像调色板/封面回落色是「同一频道
 * 恒同色」的身份映射，不算强调色滥用——不受「4 语义上限」约束）。
 *
 * ## 为什么建这个共享件（AGENT-DISCIPLINE §一 类问题收敛）
 * 同一 8 色「柔彩调色板 + floorMod(chatId) 取色」逻辑在本仓已存在 **8 份私有副本**
 * （PanelAvatarPalette/HealthAvatarPalette/HomeAvatarPalette/DiscoverAvatarPalette/
 * PackAvatarPalette/AvatarPalette/StoriesAvatarPalette/RailAvatarPalette——各文件
 * 私有 val，值同源不同名）。R222 的 hashtag 时间轴需要第 9 个消费者（节点按频道
 * 着色）——按纪律**不许再抄第 9 份**，收敛到本处单一定义。
 *
 * ## 既有 8 副本的迁移挂账（显式挂账，不静默）
 * 8 处私有副本各自绑定了各自页面的视觉验收（部分副本色值/长度有微差），统一替换
 * 是「功能零变化」红线下的纯视觉重构——按 R207 治理流程（盘点→对照→像素取证）
 * 独立一刀处理，不在本任务夹带（并发期最小改动面）。后人做视觉治理轮时把 8 处
 * 私有副本逐一指向本函数即可。
 */
object ChannelIdentity {

    /** 柔彩调色板：8 色环形（与各私有副本同族的色相带） */
    val Palette = listOf(
        Color(0xFF5C6BC0), // 柔靛蓝
        Color(0xFF26A69A), // 柔青绿
        Color(0xFFEF5350), // 柔红
        Color(0xFFAB47BC), // 柔紫
        Color(0xFFFFA726), // 柔橙
        Color(0xFF42A5F5), // 柔蓝
        Color(0xFF66BB6A), // 柔绿
        Color(0xFFEC407A), // 柔粉
    )

    /** 按 chatId 稳定取色（同一频道恒同色；floorMod 挡 Long.MIN_VALUE 负值） */
    fun colorOf(chatId: Long): Color =
        Palette[Math.floorMod(chatId, Palette.size)]
}
