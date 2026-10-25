package dev.tgfeed.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Copyright
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.BuildConfig
import dev.tgfeed.R
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens

/** 关于页卡片圆角:与设置页分组卡片同一语言 */
private val AboutCardRadius = 16.dp

/**
 * 项目对外链接单源:仓库主页是全工程唯一对外 URL 字面量,其余链接一律由此派生,
 * 改仓库地址只动这里。
 */
object AboutLinks {
    /** 项目主页(GitHub 仓库) */
    const val REPO_URL = "https://github.com/A4hun/TGFeed"

    /** 新版发布页:由主页派生,不另写字面量 */
    const val RELEASES_URL = "$REPO_URL/releases"
}

/** 在系统浏览器中打开链接(应用内不嵌 WebView);无浏览器可接球时如实提示不装死 */
internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "没有能打开该链接的应用", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 关于页:设置页底部「关于」区进入(返回剥落回设置)。纯静态内容,零网络零后台
 * ——页面本身不发起任何请求,所有链接一律经系统浏览器外开。
 *
 * 内容分区:
 * - 版本:应用名 / 版本号(versionName 与内部版本号 versionCode 均实读
 *   [BuildConfig],构建变体如实显示 debug / release,不做任何包装);
 * - 项目主页:GitHub 仓库(地址单源于 [AboutLinks]);
 * - 检查更新:直达 GitHub 发布页。诚实口径:本应用无服务器、零后端,不存在任何
 *   更新探测服务,因此**不做应用内版本比较与升级提示**——这一行只把用户带到
 *   发布页,新版由用户自行查看、自行下载安装;
 * - 开源许可:本项目 GPL-3.0 与第三方组件许可清单(进入许可子页);
 * - 免责与隐私:与 README 一致的三行要点。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    // 许可清单子页态:关于页内两级导航(许可页→关于页→设置页),不新增容器路由;
    // rememberSaveable 令子页往返时关于主内容滚动位/返回位保持
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    // 两级返回:在许可子页先回关于主内容,再回设置
    BackHandler(onBack = { if (showLicenses) showLicenses = false else onBack() })
    if (showLicenses) {
        AboutLicensesPage(onBack = { showLicenses = false })
        return
    }
    val context = LocalContext.current
    // 页面滚动位置:进许可子页往返时保持(rememberSaveable + ScrollState.Saver)
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                // 自适应内容列宽必须在前:fillMaxSize 会先把约束钉成全宽,其后的限宽无效
                .adaptiveContentWidth()
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                // 底部避开手势条,最后一张卡片不贴边
                .navigationBarsPadding(),
        ) {
            AboutTopBar(title = "关于", onBack = onBack)
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
            ) {
                // ---------------- 版本(只读展示行,零网络) ----------------
                AboutCard("版本") {
                    AboutInfoRow(label = "应用名", value = stringResource(R.string.app_name))
                    AboutInfoRow(label = "版本", value = "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
                    AboutInfoRow(label = "构建变体", value = BuildConfig.BUILD_TYPE)
                }

                // ---------------- 项目主页 ----------------
                AboutCard("项目主页") {
                    AboutActionRow(
                        icon = Icons.Outlined.Public,
                        title = "GitHub 仓库",
                        subtitle = "查看源码、提交问题与反馈",
                        onClick = { openInBrowser(context, AboutLinks.REPO_URL) },
                    )
                    Text(
                        AboutLinks.REPO_URL,
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TgBlue,
                    )
                }

                // ---------------- 检查更新(诚实口径见本文件头 KDoc) ----------------
                AboutCard("检查更新") {
                    AboutActionRow(
                        icon = Icons.Outlined.Update,
                        title = "前往发布页",
                        subtitle = "应用内不做自动检查——新版与改动都在发布页,自行查看下载",
                        onClick = { openInBrowser(context, AboutLinks.RELEASES_URL) },
                    )
                    Text(
                        AboutLinks.RELEASES_URL,
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TgBlue,
                    )
                }

                // ---------------- 开源许可 ----------------
                AboutCard("开源许可") {
                    Text(
                        "本项目采用 GPL-3.0;第三方组件逐个列出,含官方仓库链接",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    AboutActionRow(
                        icon = Icons.Outlined.Copyright,
                        title = "查看许可清单",
                        subtitle = "第三方组件、演示内容的许可与出处",
                        onClick = { showLicenses = true },
                    )
                }

                // ---------------- 免责与隐私(与 README 同口径) ----------------
                AboutCard("免责与隐私") {
                    Text(
                        "本项目仅供学习与技术交流,不提供、不聚合任何内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    Text(
                        "所有内容均来自使用者本人 Telegram 账号已加入的频道,经由使用者自己的 API 凭证访问;请遵守 Telegram 服务条款与当地法律法规。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    Text(
                        "本项目与 Telegram 官方无任何关联,不是 Telegram 官方客户端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                }
            }
        }
    }
}

// ==================== 以下为关于页组件(许可子页共用,同视觉语言) ====================

/** 顶部返回栏:40dp 圆形底托返回钮 + 17sp SemiBold 标题 */
@Composable
internal fun AboutTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = TgFeedTokens.GapLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.SurfaceHigh)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TgFeedTokens.TextHigh,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TgFeedTokens.TextHigh,
        )
    }
}

/** 分组卡片:16dp 圆角 + SurfaceHigh 底 + 卡内顶部小字组标题 */
@Composable
internal fun AboutCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AboutCardRadius))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextLow,
        )
        content()
    }
}

/** 只读信息行:左侧灰标签 + 右侧白值(版本卡「应用名/版本/构建变体」三行) */
@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextMedium,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextHigh,
        )
    }
}

/** 图标徽章行:36dp 圆托 + 标题/说明两行 + 尾部箭头,整行可点 */
@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = TgFeedTokens.TextMedium,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
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
