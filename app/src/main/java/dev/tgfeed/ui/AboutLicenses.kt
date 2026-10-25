package dev.tgfeed.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens

/** 一条第三方组件许可条目 */
data class ThirdPartyLicenseEntry(
    /** 组件名(面向用户的称呼) */
    val component: String,
    /** 许可名(如实;拿不准的以官方仓库为准,行可点前往核对) */
    val license: String,
    /** 一行说明:坐标/版本/形态/出处 */
    val note: String,
    /** 官方仓库或主页(点击行在系统浏览器打开) */
    val url: String,
)

/**
 * 运行时依赖(随安装包分发)。与 app/build.gradle.kts 的依赖声明一一对应——
 * **增删依赖必须同步本清单**(许可页对用户说谎=漏报组件)。
 * 许可名如实:Apache-2.0/GPL-3.0/Boost/JSON License 等按各组件官方声明
 * 或其发布件(POM)声明填写;内嵌 native 编译产物的注明上游出处。
 */
internal val RUNTIME_LICENSES: List<ThirdPartyLicenseEntry> = listOf(
    ThirdPartyLicenseEntry(
        component = "Kotlin 标准库",
        license = "Apache-2.0",
        note = "构建链自动注入的 kotlin-stdlib",
        url = "https://kotlinlang.org",
    ),
    ThirdPartyLicenseEntry(
        component = "kotlinx-coroutines-android 1.8.1",
        license = "Apache-2.0",
        note = "Kotlin 协程运行时",
        url = "https://github.com/Kotlin/kotlinx-coroutines",
    ),
    ThirdPartyLicenseEntry(
        component = "Jetpack Compose",
        license = "Apache-2.0",
        note = "界面框架:compose-bom 2024.09.02(ui / foundation / material3 / material-icons-extended)",
        url = "https://developer.android.com/jetpack/compose",
    ),
    ThirdPartyLicenseEntry(
        component = "AndroidX Core KTX 1.13.1",
        license = "Apache-2.0",
        note = "core-ktx 核心扩展",
        url = "https://cs.android.com/androidx",
    ),
    ThirdPartyLicenseEntry(
        component = "AndroidX Activity Compose 1.9.2",
        license = "Apache-2.0",
        note = "Activity 与 Compose 互操作层",
        url = "https://cs.android.com/androidx",
    ),
    ThirdPartyLicenseEntry(
        component = "AndroidX Lifecycle 2.8.5",
        license = "Apache-2.0",
        note = "生命周期四件:runtime-compose / viewmodel-compose / runtime-ktx / viewmodel-ktx",
        url = "https://cs.android.com/androidx",
    ),
    ThirdPartyLicenseEntry(
        component = "AndroidX SavedState 1.2.1",
        license = "Apache-2.0",
        note = "savedstate-ktx 状态保存",
        url = "https://cs.android.com/androidx",
    ),
    ThirdPartyLicenseEntry(
        component = "Media3(ExoPlayer)1.4.1",
        license = "Apache-2.0",
        note = "播放器三件:media3-exoplayer / media3-ui / media3-session",
        url = "https://github.com/androidx/media3",
    ),
    ThirdPartyLicenseEntry(
        component = "nextlib-media3ext 1.7.1-0.9.0",
        license = "GPL-3.0",
        note = "Media3 的 FFmpeg 软解扩展;内含 FFmpeg 编译产物,FFmpeg 许可以 ffmpeg.org 为准",
        url = "https://github.com/anilbeesetti/nextlib",
    ),
    ThirdPartyLicenseEntry(
        component = "TDLib",
        license = "Boost Software License 1.0",
        note = "Telegram 官方客户端库;预编译 AAR(core-release / ktx-release)由构建脚本下载",
        url = "https://github.com/tdlib/td",
    ),
    ThirdPartyLicenseEntry(
        component = "ML Kit 文字识别(中文)16.0.1",
        license = "Apache-2.0",
        note = "com.google.mlkit:text-recognition-chinese,全离线 bundled 模型(识别不外发数据)",
        url = "https://developers.google.com/ml-kit/vision/text-recognition/v2",
    ),
)

/**
 * 演示模式内容出处:未配置 Telegram 凭证时的内置示例。
 */
internal val DEMO_CONTENT_LICENSES: List<ThirdPartyLicenseEntry> = listOf(
    ThirdPartyLicenseEntry(
        component = "Blender 开源影片(演示视频)",
        license = "CC-BY 3.0",
        note = "Blender 基金会开源影片片段:Big Buck Bunny / Sintel / Jellyfish",
        url = "https://www.blender.org/about/projects/",
    ),
)

/**
 * 开发与测试专用(不打进安装包)。与 app/build.gradle.kts 的 testImplementation
 * 依赖一一对应;单测期的 JSON 实现/模拟框架等,用户安装包里不存在这些代码。
 */
internal val TEST_ONLY_LICENSES: List<ThirdPartyLicenseEntry> = listOf(
    ThirdPartyLicenseEntry(
        component = "JUnit 4.13.2",
        license = "Eclipse Public License 1.0",
        note = "单元测试框架",
        url = "https://junit.org/junit4/",
    ),
    ThirdPartyLicenseEntry(
        component = "MockK 1.13.13",
        license = "Apache-2.0",
        note = "测试模拟库",
        url = "https://mockk.io",
    ),
    ThirdPartyLicenseEntry(
        component = "org.json 20240303",
        license = "JSON License",
        note = "测试用 JSON 实现(替换安卓打桩版);许可详情以其官方仓库为准",
        url = "https://github.com/stleary/JSON-java",
    ),
)

/**
 * 开源许可页(关于页内的子页):本项目 GPL-3.0 + 第三方组件许可清单 +
 * 演示内容注记 + 测试专用件说明,可滚动;点组件行在系统浏览器打开其官方仓库
 * (许可名与去向用户可自行核对)。
 */
@Composable
fun AboutLicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                // 自适应内容列宽必须在前(与设置页/关于页同款约束次序)
                .adaptiveContentWidth()
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            AboutTopBar(title = "开源许可", onBack = onBack)
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
            ) {
                // ---------------- 本项目 ----------------
                AboutCard("本项目") {
                    LicenseEntryRow(
                        entry = ThirdPartyLicenseEntry(
                            component = "TGFeed",
                            license = "GPL-3.0",
                            note = "本应用自身;许可全文见仓库 LICENSE 文件",
                            url = AboutLinks.REPO_URL,
                        ),
                    ) { openInBrowser(context, AboutLinks.REPO_URL) }
                }

                // ---------------- 第三方组件(随安装包分发) ----------------
                AboutCard("第三方组件") {
                    Text(
                        "以下组件随安装包分发,与构建脚本的依赖声明一一对应;点行可打开官方仓库核对许可",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    RUNTIME_LICENSES.forEach { entry ->
                        LicenseEntryRow(entry) { openInBrowser(context, entry.url) }
                    }
                }

                // ---------------- 演示内容 ----------------
                AboutCard("演示内容") {
                    Text(
                        "未配置 Telegram 凭证时,演示模式使用的示例内容出处与许可",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    DEMO_CONTENT_LICENSES.forEach { entry ->
                        LicenseEntryRow(entry) { openInBrowser(context, entry.url) }
                    }
                }

                // ---------------- 开发与测试专用(不进安装包) ----------------
                AboutCard("开发与测试专用") {
                    Text(
                        "以下仅用于开发与单元测试,不打进安装包(用户设备上不存在这些代码)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    TEST_ONLY_LICENSES.forEach { entry ->
                        LicenseEntryRow(entry) { openInBrowser(context, entry.url) }
                    }
                }
            }
        }
    }
}

/** 许可条目行:组件名 + 许可名(蓝,许可信息主色)+ 说明 + 官方链接,整行可点外开 */
@Composable
private fun LicenseEntryRow(entry: ThirdPartyLicenseEntry, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onOpen)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.component,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
            )
            Text(
                entry.license,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TgBlue,
            )
            Text(
                entry.note,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
            )
            Text(
                entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(20.dp),
        )
    }
}
