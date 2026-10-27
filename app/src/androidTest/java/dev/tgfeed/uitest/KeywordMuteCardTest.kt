package dev.tgfeed.uitest

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tgfeed.ui.KeywordMuteSettingsContent
import dev.tgfeed.ui.UiTestTags
import dev.tgfeed.uitest.UiTestKit.countTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R413 关键词屏蔽卡回归闸：渲染无状态卡（测试专用内存词表，不碰 LocalStore/用户已有词），
 * 输入添加 → chip 节点 +1 → 点删除 → 恢复。只断言节点计数与启用态。
 */
@RunWith(AndroidJUnit4::class)
class KeywordMuteCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun addThenRemoveRestoresCount() {
        val words = mutableStateOf(linkedSetOf("seedA", "seedB"))
        rule.setContent {
            KeywordMuteSettingsContent(
                words = words.value,
                matchChannel = false,
                onAdd = { w ->
                    val t = w.trim()
                    if (t.isEmpty() || t in words.value) "dup" else {
                        words.value = LinkedHashSet(words.value).apply { add(t) }
                        null
                    }
                },
                onRemove = { w -> words.value = LinkedHashSet(words.value).apply { remove(w) } },
                onSetMatchChannel = {},
                onClear = { words.value = linkedSetOf() },
            )
        }
        val before = rule.countTag(UiTestTags.KW_MUTE_CHIP)
        assertEquals(2, before)
        rule.onNodeWithTag(UiTestTags.KW_MUTE_ADD).assertIsNotEnabled()
        rule.onNodeWithTag(UiTestTags.KW_MUTE_INPUT).performTextInput("r422probe")
        rule.onNodeWithTag(UiTestTags.KW_MUTE_ADD).assertIsEnabled().performClick()
        assertEquals(before + 1, rule.countTag(UiTestTags.KW_MUTE_CHIP))
        // 输入框成功后清空 → 添加按钮回到禁用
        rule.onNodeWithTag(UiTestTags.KW_MUTE_ADD).assertIsNotEnabled()
        // 删除最后一个（刚加的）
        rule.onAllNodesWithTag(UiTestTags.KW_MUTE_CHIP_DELETE, useUnmergedTree = true)[before].performClick()
        assertEquals(before, rule.countTag(UiTestTags.KW_MUTE_CHIP))
        assertEquals(before, words.value.size)
    }
}
