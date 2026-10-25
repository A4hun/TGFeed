package dev.tgfeed.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.launch

/**
 * 首次配置（API 凭证 + 代理）→ 登录（手机号 → 验证码 → 可选两步验证）。
 * 也可以直接进入演示模式。
 */
@Composable
fun AuthScreen(
    state: GatewayState,
    gateway: TdGateway,
    prefs: AppPrefs,
    needsConfig: Boolean,
    onApplyConfig: (apiId: Int, apiHash: String, proxyEnabled: Boolean, proxyHost: String, proxyPort: Int) -> Unit,
    onDemo: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf("phone") }
    LaunchedEffect(state) {
        when (state) {
            // 扫码登录阶段 _state 恒为 NeedPhone（扫码态由 qrLoginLink 表达），
            // 不能把用户从二维码页顶回手机号页
            is GatewayState.NeedPhone -> if (stage != "qr") stage = "phone"
            is GatewayState.NeedCode -> stage = "code"
            is GatewayState.NeedPassword -> stage = "password"
            else -> Unit
        }
    }
    // 网关实例被替换（扫码兜底 close+重建 / 重新配置）时强制回手机号页——
    // 新实例没有扫码上下文，停在二维码页只会干等"正在获取二维码…"
    LaunchedEffect(gateway) { if (stage == "qr") stage = "phone" }

    // 纯黑沉浸底：登录页也吃 TgFeedTheme 配色，不再自成一套
    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
                .widthIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            // 品牌区：App 名走主题主色（电报蓝），slogan 点明产品形态
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "TGFeed",
                    style = MaterialTheme.typography.displaySmall,
                    color = TgFeedTokens.TgBlue, // 品牌色走 token（= 主题 primary 电报蓝）
                )
                Text(
                    "把 Telegram 刷成抖音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TgFeedTokens.TextMedium,
                    modifier = Modifier.padding(top = TgFeedTokens.GapXs),
                )
            }

            Text(
                "连接 Telegram",
                style = MaterialTheme.typography.headlineSmall,
                color = TgFeedTokens.TextHigh, // 显式指定，不吃 LocalContentColor 默认值
            )

            // 连接状态实时横幅：让用户看见"连没连上"（治"发送中干等"）
            val connState by gateway.connectionState.collectAsState()
            val (connColor, connIcon) = when (connState) {
                "已连接" -> TgFeedTokens.SuccessGreen to "✓"
                "等待网络" -> TgFeedTokens.WarningRed to "✗"
                else -> TgFeedTokens.TgBlue to "…"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusField))
                    .background(TgFeedTokens.SurfaceHigh)
                    .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(connIcon, color = connColor, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(TgFeedTokens.GapSm))
                Text(
                    "网络：$connState",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (connState == "等待网络" || connState == "连接中") {
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Text(
                        "连不上就检查代理/VPN",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // R244 代理决策可见性：网关首启探测的结果直接上登录页——自动启用本地代理
            // （灰，安心信息）或配置代理不可达改直连（红，警示）。治实锤②「app 走直连
            // 连不上 TG 却毫无提示」：旧版探测失败只写 logcat，用户侧全程无感。
            val proxyHint by gateway.proxyHint.collectAsState()
            proxyHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    // 「已自动…」开头 = 好消息用中性色；其余（不可达警示）走 token 警示红
                    color = if (hint.startsWith("已自动")) TgFeedTokens.TextMedium else TgFeedTokens.WarningRed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state is GatewayState.Error) {
                // 错误提示统一走 token 警示红（= 主题 error）
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TgFeedTokens.WarningRed,
                )
            }

            if (needsConfig) {
                ConfigSection(prefs, onApplyConfig)
            } else {
                // 登录方式切换 tab（仅手机号/扫码阶段显示；验证码/两步密码是手机号路径的
                // 后续态，不给跳走，防状态机错乱）
                if (stage == "phone" || stage == "qr") {
                    LoginModeTabs(stage) { mode ->
                        if (mode == "qr") {
                            stage = "qr"
                            // 幂等发起：已处扫码等待态时网关内部直接返回；
                            // 上次失败时下发 link 未到，再点本 tab 即重试
                            scope.launch { (gateway as? TdLibGateway)?.startQrLogin() }
                        } else if (stage == "qr") {
                            // 取消扫码：清本地态即可（TDLib 侧的查询随 token 过期自然失效，
                            // 之后提交手机号时会被隐式取消——见 TdLibGateway.submitPhone 注释）
                            scope.launch { (gateway as? TdLibGateway)?.cancelQrLogin() }
                            stage = "phone"
                        }
                    }
                }
                when (stage) {
                    "phone" -> {
                        var phone by remember { mutableStateOf("") }
                        var submitting by remember { mutableStateOf(false) }
                        AuthField(
                            value = phone,
                            onValueChange = { phone = it },
                            placeholder = "手机号（含区号，如 +86…）",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 防连点：提交中禁用按钮（连点会撞 Telegram 的"another authorization query"）
                        PrimaryButton(text = if (submitting) "发送中…" else "发送验证码") {
                            if (!submitting) {
                                submitting = true
                                scope.launch {
                                    gateway.submitPhone(phone)
                                    kotlinx.coroutines.delay(3000) // 3 秒内不允许重发
                                    submitting = false
                                }
                            }
                        }
                    }

                    "qr" -> {
                        // 扫码登录页：二维码（白底卡 + 静区）+ 等待确认提示 + 返回手机号登录。
                        // 链接流 keyed by 网关实例：兜底重建后自动切到新实例的流
                        val tdGateway = gateway as? TdLibGateway
                        val linkFlow = remember(tdGateway) { tdGateway?.qrLoginLink }
                        val link = linkFlow?.collectAsState()?.value
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(232.dp)
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusField))
                                    .background(Color.White), // 扫码器要高对比白底
                                contentAlignment = Alignment.Center,
                            ) {
                                if (link != null) {
                                    QrCodeImage(content = link, modifier = Modifier.size(216.dp))
                                } else {
                                    CircularProgressIndicator(
                                        Modifier.size(40.dp),
                                        color = TgFeedTokens.TextLow,
                                        strokeWidth = 3.dp,
                                    )
                                }
                            }
                        }
                        Text(
                            "用已登录的 Telegram 扫一扫",
                            style = MaterialTheme.typography.titleSmall,
                            color = TgFeedTokens.TextHigh,
                        )
                        Text(
                            if (link != null) "等待确认…（Telegram → 设置 → 设备 → 扫码）"
                            else "正在获取二维码…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextMedium,
                        )
                        TextButton(
                            onClick = {
                                scope.launch { tdGateway?.cancelQrLogin() }
                                stage = "phone"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("返回手机号登录", color = TgFeedTokens.TgBlue)
                        }
                    }

                    "code" -> {
                        var code by remember { mutableStateOf("") }
                        AuthField(
                            value = code,
                            onValueChange = { code = it },
                            placeholder = "验证码",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryButton(text = "登录") {
                            scope.launch { gateway.submitCode(code) }
                        }
                    }

                    "password" -> {
                        var pw by remember { mutableStateOf("") }
                        AuthField(
                            value = pw,
                            onValueChange = { pw = it },
                            placeholder = "两步验证密码",
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryButton(text = "验证") {
                            scope.launch { gateway.submitPassword(pw) }
                        }
                    }
                }
            }

            if (state is GatewayState.Loading) {
                // 加载 spinner 对齐 kit AppLoading 规格：40dp / 3dp / TextHigh / 轨道白 20%
                // （不用 AppLoading 本体：它内建 fillMaxSize，在可滚动 Column 里会吃到无限高约束）
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(40.dp),
                    color = TgFeedTokens.TextHigh,
                    trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.20f),
                    strokeWidth = 3.dp,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

            TextButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) {
                // 次级入口：品牌蓝文字（显式指定，不吃默认 primary 注入以外的颜色）
                Text("进入演示模式（无需配置）", color = TgFeedTokens.TgBlue)
            }
        }
    }
}

/** 登录方式切换（手机号 / 扫码）：胶囊容器里的双选段，选中态走品牌蓝底 */
@Composable
private fun LoginModeTabs(stage: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("phone" to "手机号登录", "qr" to "扫码登录").forEach { (mode, label) ->
            val active = stage == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(if (active) TgFeedTokens.TgBlue else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
                )
            }
        }
    }
}

/**
 * 二维码渲染：自研 [QrCode] 编码器产出模块矩阵，Canvas 逐黑模块画方块。
 * 四周留白 4 个模块当静区（quiet zone，ISO/IEC 18004 强制要求，缺了部分扫码器拒识）。
 * 白底由调用方容器提供（黑模块之外不画任何像素）。
 */
@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { runCatching { QrCode.encodeText(content) }.getOrNull() }
    if (matrix == null) {
        // 超出 V6-L 容量（134 字节）才会走到——tg://login 链接实测 44~71 字节，纯兜底
        Text(
            "二维码生成失败，请改用手机号登录",
            color = TgFeedTokens.WarningRed,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Canvas(modifier) {
        val n = matrix.size
        val quiet = 4
        val cell = size.minDimension / (n + quiet * 2)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix[y][x]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** 主按钮统一规格：全圆角胶囊 + 50dp 高 + AccentRed 底白字加粗（与 AppEmptyState 主钮同一语言） */
@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
        colors = ButtonDefaults.buttonColors(
            containerColor = TgFeedTokens.AccentRed,
            contentColor = TgFeedTokens.TextHigh,
            disabledContainerColor = TgFeedTokens.AccentRed.copy(alpha = 0.35f),
            disabledContentColor = TgFeedTokens.TextHigh.copy(alpha = 0.60f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
    ) { Text(text, fontWeight = FontWeight.Bold, color = TgFeedTokens.TextHigh) }
}

/**
 * 登录页输入框统一规格：无边框胶囊（SurfaceHigh 底 + RadiusPill，指示线全透明），
 * 与搜索页输入框同一语言；占位文案 TextLow、输入文字/光标 TextHigh（全部显式指定）。
 */
@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, color = TgFeedTokens.TextLow) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TgFeedTokens.TextHigh),
        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
        colors = TextFieldDefaults.colors(
            focusedTextColor = TgFeedTokens.TextHigh,
            unfocusedTextColor = TgFeedTokens.TextHigh,
            cursorColor = TgFeedTokens.TextHigh,
            focusedContainerColor = TgFeedTokens.SurfaceHigh,
            unfocusedContainerColor = TgFeedTokens.SurfaceHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
    )
}

/** 首次配置：API 凭证（my.telegram.org 申请）+ SOCKS5 代理 */
@Composable
private fun ConfigSection(
    prefs: AppPrefs,
    onApply: (apiId: Int, apiHash: String, proxyEnabled: Boolean, proxyHost: String, proxyPort: Int) -> Unit,
) {
    // 凭证一律不回显（含用户手填过的）——已存的值依然生效，只是不显示；
    // 想换直接填新值覆盖。防截图/借设备外泄
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var proxyEnabled by remember { mutableStateOf(prefs.proxyEnabled) }
    var proxyHost by remember { mutableStateOf(prefs.proxyHost) }
    var proxyPort by remember { mutableStateOf(prefs.proxyPort.toString()) }

    Text(
        "首次使用需要 API 凭证",
        style = MaterialTheme.typography.titleMedium,
        color = TgFeedTokens.TextHigh, // 显式指定，不吃 LocalContentColor 默认值
    )
    Text(
        "打开 my.telegram.org → API development tools 申请，粘贴到下面",
        style = MaterialTheme.typography.bodySmall,
        color = TgFeedTokens.TextMedium,
    )

    AuthField(
        value = apiId,
        onValueChange = { apiId = it },
        placeholder = "API ID",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    AuthField(
        value = apiHash,
        onValueChange = { apiHash = it },
        placeholder = "API Hash",
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "走代理（SOCKS5）",
            Modifier.weight(1f),
            color = TgFeedTokens.TextHigh, // 显式指定，不吃 LocalContentColor 默认值
        )
        Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
    }
    if (proxyEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            AuthField(
                value = proxyHost,
                onValueChange = { proxyHost = it },
                placeholder = "主机",
                modifier = Modifier.weight(2f),
            )
            AuthField(
                value = proxyPort,
                onValueChange = { proxyPort = it },
                placeholder = "端口",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "模拟器保持 127.0.0.1（已做 adb reverse 指向电脑 Clash）；真机不用填——" +
                "手机上有 Clash/V2Ray 等代理软件时 App 会自动检测接入（R244）",
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextMedium,
        )
    }

    PrimaryButton(
        text = "保存并连接",
        enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
    ) {
        val id = apiId.trim().toIntOrNull() ?: return@PrimaryButton
        val port = proxyPort.trim().toIntOrNull() ?: 7897
        onApply(id, apiHash.trim(), proxyEnabled, proxyHost.trim(), port)
    }
}
