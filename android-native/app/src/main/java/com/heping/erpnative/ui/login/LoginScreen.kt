package com.heping.erpnative.ui.login

import android.content.Intent
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.launch
import com.heping.erpnative.BuildConfig
import com.heping.erpnative.data.Book
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.ErrorText
import com.heping.erpnative.ui.components.LoadingBox
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.ui.main.WEB_URL
import com.heping.erpnative.vm.AuthViewModel

/** APK 下载页地址。随API_BASE 走，改服务器只需改 gradle.properties 的 erpApiBase。 */
private val APK_URL = "${BuildConfig.API_BASE}/downloads/apk"

@Composable
fun SvgImage(svg: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> ImageView(ctx) },
        modifier = modifier,
        update = { iv ->
            try {
                val picture = SVG.getFromString(svg).renderToPicture()
                iv.setImageDrawable(android.graphics.drawable.PictureDrawable(picture))
            } catch (_: Exception) { iv.setImageDrawable(null) }
        }
    )
}

/**
 * v5.5 登录/首页网关。
 *
 * 行为变更（对齐网页版）：
 * - 免密（信任设备）成功后**不再自动进系统**，停在这里显示信任面板
 * - 面板三个按钮：进入开单 / 选择其他账套 / 更换账号
 * - 账密登录成功后同样停在面板，由用户点「进入开单」
 * - 面板底部提供「手机版下载」；设置页另有「打开网页版」入口
 *
 * @param onEntered 已进入系统（由 AppNav 导航到 main）
 */
@Composable
fun LoginScreen(onEntered: () -> Unit, vm: AuthViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 面板态：有会话但还没进系统
    var panel by remember { mutableStateOf(false) }
    var booting by remember { mutableStateOf(true) }
    var showBooks by remember { mutableStateOf(false) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captchaText by remember { mutableStateOf("") }
    var trust by remember { mutableStateOf(true) }

    // 启动：已有会话（含设备令牌续期成功）→ 显示面板，不进系统
    LaunchedEffect(Unit) {
        if (SessionStore.token != null && vm.loadMe()) panel = true
        booting = false
    }

    if (booting) {
        LoadingBox()
        return
    }

    if (panel) {
        TrustedPanel(
            vm = vm,
            showBooks = showBooks,
            onToggleBooks = { showBooks = !showBooks },
            onEnter = { onEntered() },
            onSwitchAccount = {
                scope.launch {
                    SessionStore.logoutKeepDevice()
                    vm.me = null
                    panel = false; showBooks = false
                    username = ""; password = ""
                }
            },
            onUnbind = {
                scope.launch {
                    if (vm.unbindSelf()) {
                        toast(ctx, "已关闭本设备的免密信任")
                        panel = false; showBooks = false
                        username = ""; password = ""
                    }
                }
            }
        )
        return
    }

    // ---------------- 账密登录表单 ----------------
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("简单ERP", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { openUrl(ctx, WEB_URL) }) {
            Icon(Icons.Filled.Language, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("打开网页版", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                vm.precheck(it)
            },
            label = { Text("账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (vm.needCaptcha) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = captchaText,
                    onValueChange = { captchaText = it.uppercase() },
                    label = { Text("验证码") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                if (vm.captcha != null) {
                    SvgImage(svg = vm.captcha!!.svg, Modifier
                        .height(48.dp)
                        .width(120.dp)
                        .clickable { vm.loadCaptcha() })
                }
            }
            Text("点图刷新验证码", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = trust, onCheckedChange = { trust = it })
            Text("信任此设备（30 天免密登录）")
        }

        ErrorText(vm.error)

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    vm.loading = true
                    val ok = vm.login(username, password, captchaText, trust)
                    if (ok) {
                        if (vm.trustFull) {
                            toast(ctx, "已达 4 台信任设备上限，本机未被记住。请到设置 → 账户安全 解除一台后再试", long = true)
                        }
                        // v5.5：登录成功也停在面板，由用户点「进入开单」
                        if (vm.loadMe()) panel = true else onEntered()
                    }
                }
            },
            enabled = !vm.loading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("登录")
        }

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { openUrl(ctx, APK_URL) }) {
            Text("下载手机版（安卓 APK）", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * v5.5 信任面板：免密已生效但**不自动进系统**，让用户明确选择。
 * 与网页版 #trusted-panel 结构一一对应。
 */
@Composable
private fun TrustedPanel(
    vm: AuthViewModel,
    showBooks: Boolean,
    onToggleBooks: () -> Unit,
    onEnter: () -> Unit,
    onSwitchAccount: () -> Unit,
    onUnbind: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = vm.me
    val acc = me?.account
    val name = acc?.display_name?.takeIf { it.isNotBlank() } ?: acc?.username ?: "本机用户"
    val cur = me?.book
    val books = vm.books

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("简单ERP", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(28.dp))

        // 头像
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.take(1),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            if (books.size > 1)
                "免密登录中 · 当前「${cur?.name ?: "-"}」，共 ${books.size} 个账套"
            else "30 天内免密登录 · ${me?.roleLabel ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onEnter,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("进入开单", fontSize = 16.sp) }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onToggleBooks,
                enabled = books.size > 1,
                modifier = Modifier.weight(1f).height(44.dp)
            ) { Text("选择其他账套", fontSize = 13.sp) }
            OutlinedButton(
                onClick = onSwitchAccount,
                modifier = Modifier.weight(1f).height(44.dp)
            ) { Text("更换账号", fontSize = 13.sp) }
        }
        if (books.size <= 1) {
            Text(
                "该账号只有 1 个账套，无需切换",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showBooks && books.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    books.forEach { b ->
                        val isCur = b.id == cur?.id
                        BookRow(
                            b = b,
                            current = isCur,
                            busy = vm.switching,
                            onClick = {
                                if (!isCur) {
                                    scope.launch {
                                        if (vm.switchBook(b.id)) {
                                            toast(ctx, vm.switchMsg ?: "已切换账套")
                                            onToggleBooks()
                                        } else {
                                            toast(ctx, vm.switchMsg ?: "切换失败", long = true)
                                        }
                                    }
                                } else {
                                    onToggleBooks()
                                }
                            }
                        )
                    }
                }
            }
        }
        ErrorText(vm.switchMsg?.takeIf { it.startsWith("切换失败") })

        Spacer(Modifier.height(20.dp))
        Text(
            "信任期间本设备可免密进入，如需关闭请点下方按钮",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onUnbind) {
            Text("关闭本设备的免密信任", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { openUrl(ctx, WEB_URL) }) {
            Icon(Icons.Filled.Language, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("改用网页版", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BookRow(b: Book, current: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = !busy && !current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(b.name, Modifier.weight(1f), fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (current) {
            Icon(Icons.Filled.Check, "当前", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider()
}

private fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {}
}
