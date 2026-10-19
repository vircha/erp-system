package com.heping.erpnative.ui.main

import android.Manifest
import com.heping.erpnative.BuildConfig
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.heping.erpnative.ErpNativeApp
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.theme.AppThemeState
import com.heping.erpnative.ui.theme.ThemeMode
import com.heping.erpnative.ui.components.ErrorText
import com.heping.erpnative.ui.components.TopBar
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * v5.5：网页版入口地址（设置页与登录页共用）。
 * 统一取BuildConfig.API_BASE，在 android-native/gradle.properties 的 erpApiBase 里配置，
 * 这样换服务器只需改一行，不必动源码。
 */
val WEB_URL: String = BuildConfig.API_BASE

@Composable
fun SettingsScreen(vm: SettingsViewModel, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isBoss = SessionStore.isBoss

    var showPw by remember { mutableStateOf(false) }
    var showShop by remember { mutableStateOf(false) }
    var showUnbind by remember { mutableStateOf(false) }

    // 各分区默认展开状态：账户信息 + 外观/主题 + 版本/更新 默认展开，其余折叠，降低视觉杂乱
    var expAccount by remember { mutableStateOf(true) }
    var expTheme by remember { mutableStateOf(true) }
    var expUpdate by remember { mutableStateOf(true) }
    var expBt by remember { mutableStateOf(false) }
    var expPw by remember { mutableStateOf(false) }
    var expShop by remember { mutableStateOf(isBoss) }
    // v5.5：账套切换 + 信任设备 + 网页版入口
    var expBook by remember { mutableStateOf(true) }
    var expTrust by remember { mutableStateOf(false) }
    var expWeb by remember { mutableStateOf(false) }

    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshBt(ctx)
    }

    LaunchedEffect(Unit) {
        vm.refreshVersion(ctx)
        vm.checkUpdate()
        vm.loadBooks()
        if (isBoss) vm.loadSettings()
        val granted = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) vm.refreshBt(ctx) else permLauncher.launch(perms)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("设置")

        // 账户信息
        CollapsibleSection("账户信息", expAccount, { expAccount = !expAccount }) {
            Text("账号：${SessionStore.displayName.ifEmpty { "-" }}")
            Spacer(Modifier.height(6.dp))
            Text("角色：${SessionStore.role}")
            Spacer(Modifier.height(6.dp))
            Text("门店：${SessionStore.shopName.ifEmpty { SessionStore.bookName }}")
            Spacer(Modifier.height(6.dp))
            Text("套账 ID：${SessionStore.bookId}")
        }

        // 🔴 v5.5：账套切换（网页版同功能）。所有角色可用。
        CollapsibleSection("账套切换", expBook, { expBook = !expBook }) {
            Text(
                "当前账套：${SessionStore.bookName.ifEmpty { "-" }}（ID ${SessionStore.bookId}）",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            if (vm.books.isEmpty()) {
                Text("正在加载账套列表…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (vm.books.size == 1) {
                Text("该账号只有 1 个账套，无需切换", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                vm.books.forEach { b ->
                    val cur = b.id == SessionStore.bookId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = !vm.switchingBook && !cur) {
                                vm.switchBook(b.id) { ok -> if (ok) restartApp(ctx) }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(b.name, Modifier.weight(1f), fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal)
                        Text("ID ${b.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        when {
                            vm.switchingBook && !cur -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            cur -> Icon(Icons.Filled.Check, "当前", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            else -> Text("切换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
                Text(
                    "切换后 App 会自动重启，各页数据按新账套重新加载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            vm.bookMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelMedium)
            }
        }

        // v5.5：信任设备（本机解绑入口）
        CollapsibleSection("信任设备", expTrust, { expTrust = !expTrust }) {
            Text(
                if (SessionStore.deviceToken != null)
                    "本机已被记住，30 天内可免密登录。不想被记住就在这里解绑。"
                else
                    "本机未开启免密信任（每次登录需输密码）。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { showUnbind = true },
                enabled = SessionStore.deviceToken != null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("解除本机设备信任") }
        }

        // v5.5：网页版入口
        CollapsibleSection("网页版", expWeb, { expWeb = !expWeb }) {
            Text("在浏览器里打开 ERP（与手机版数据实时同步）", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { openWeb(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Language, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("打开网页版")
            }
            Spacer(Modifier.height(6.dp))
            Text(WEB_URL, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 外观 / 主题切换
        CollapsibleSection("外观 / 主题", expTheme, { expTheme = !expTheme }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cur = AppThemeState.mode.value
                val opts = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色"
                )
                opts.forEach { (m, label) ->
                    FilterChip(
                        selected = cur == m,
                        onClick = {
                            AppThemeState.mode.value = m
                            scope.launch { SessionStore.saveThemeMode(m.code) }
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("选择「深色」可夜间护眼；「跟随系统」随手机深色模式自动切换。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 版本 / 更新
        CollapsibleSection("版本 / 更新", expUpdate, { expUpdate = !expUpdate }) {
            Text("版本 v${vm.appVersion}（code ${vm.appCode}）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.checkUpdate() }, enabled = !vm.checking) {
                    if (vm.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("检查更新")
                }
                Spacer(Modifier.width(8.dp))
                if (vm.updateInfo != null) {
                    Button(
                        onClick = { vm.doUpdate(ctx) },
                        enabled = !vm.downloading,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        if (vm.downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("下载并安装 v${vm.updateInfo!!.versionName}")
                    }
                }
            }
            vm.updateMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelMedium)
            }
            // 下载进度条
            if (vm.downloading || vm.dlProgress > 0f) {
                Spacer(Modifier.height(8.dp))
                if (vm.dlTotal > 0) {
                    LinearProgressIndicator(
                        progress = vm.dlProgress,
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                val pct = (vm.dlProgress * 100).toInt()
                Text(
                    if (vm.dlTotal > 0) {
                        "$pct%  ${"%.1f".format(vm.dlBytes / 1048576.0)} / ${"%.1f".format(vm.dlTotal / 1048576.0)} MB"
                    } else {
                        "已下载 ${"%.1f".format(vm.dlBytes / 1048576.0)} MB"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            // 实时日志
            if (vm.updateLog.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("日志", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val scroll = rememberScrollState()
                LaunchedEffect(vm.updateLog.size) { scroll.animateScrollTo(scroll.maxValue) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(8.dp)
                    ) {
                        vm.updateLog.forEach { line ->
                            Text(line, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
            vm.updateInfo?.desc?.let {
                Spacer(Modifier.height(4.dp))
                Text("更新说明：$it", style = MaterialTheme.typography.labelSmall)
            }
            // 备用通道：直接用系统浏览器下载 APK（大包或应用内下载异常时可用）
            vm.updateInfo?.url?.let { url ->
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) {
                    Text("用浏览器下载（备用）", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            var showCrash by remember { mutableStateOf(false) }
            TextButton(onClick = { showCrash = true }) {
                Text("查看上次崩溃日志", fontSize = 12.sp)
            }
            if (showCrash) {
                val app = ctx.applicationContext as android.app.Application
                val txt = ErpNativeApp.readCrashLog(app)
                AlertDialog(
                    onDismissRequest = { showCrash = false },
                    title = { Text("崩溃日志") },
                    text = {
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(if (txt.isBlank()) "暂无崩溃记录" else txt, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { ErpNativeApp.clearCrashLog(app); showCrash = false }) { Text("清空") }
                    },
                    dismissButton = { TextButton(onClick = { showCrash = false }) { Text("关闭") } }
                )
            }
        }

        // 蓝牙打印
        CollapsibleSection("蓝牙打印", expBt, { expBt = !expBt }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bluetooth, "蓝牙")
                Spacer(Modifier.width(8.dp))
                Text("蓝牙打印", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.refreshBt(ctx) }) { Icon(Icons.Filled.Refresh, "刷新") }
            }
            Spacer(Modifier.height(6.dp))
            Text("状态：${if (vm.connecting) "连接中…" else vm.printerStatus}")
            Spacer(Modifier.height(6.dp))
            if (!vm.btEnabled) {
                Button(onClick = {
                    val granted = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
                    if (granted) {
                        try { ctx.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
                    } else permLauncher.launch(perms)
                }) { Text("开启蓝牙") }
            } else {
                if (vm.paired.isEmpty()) {
                    Text("未找到已配对设备，请在系统设置中先配对打印机", style = MaterialTheme.typography.labelSmall)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(vm.paired) { d ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !vm.connecting) { scope.launch { vm.connect(ctx, d) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, Modifier.weight(1f))
                                if (vm.connecting) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else if (d.address == vm.defaultPrinter) Text("默认", style = MaterialTheme.typography.labelSmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // 账户安全（所有角色可用）
        CollapsibleSection("账户安全", expPw, { expPw = !expPw }) {
            Button(onClick = { showPw = true }) { Text("修改登录密码") }
        }

        // 店铺设置（仅老板）
        if (isBoss) {
            CollapsibleSection("店铺设置", expShop, { expShop = !expShop }) {
                Button(onClick = { showShop = true }) { Text("编辑店铺信息") }
            }
        }

        ErrorText(vm.updateMsg?.takeIf { it.startsWith("检查失败") || it.startsWith("更新失败") })

        // 登出
        Button(
            onClick = {
                scope.launch {
                    try { com.heping.erpnative.device.BtPrinter.disconnect() } catch (_: Exception) {}
                    SessionStore.logoutKeepDevice()
                    onLogout()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(48.dp)
        ) { Text("退出登录") }

        Spacer(Modifier.height(24.dp))
    }

    if (showPw) PasswordDialog(onDismiss = { showPw = false }, vm = vm)
    if (showShop) ShopDialog(onDismiss = { showShop = false }, vm = vm)
    if (showUnbind) {
        AlertDialog(
            onDismissRequest = { if (!vm.unbinding) showUnbind = false },
            title = { Text("解除本机设备信任") },
            text = {
                Text("解除后，本机不再保存免密令牌，下次打开需重新输入账号密码。服务器上的信任记录也会一并删除。")
            },
            confirmButton = {
                Button(onClick = {
                    vm.unbindSelf { ok, err ->
                        if (ok) { showUnbind = false; restartApp(ctx) }
                        else toast(ctx, "解绑失败：${err ?: "未知错误"}", long = true)
                    }
                }, enabled = !vm.unbinding) {
                    if (vm.unbinding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("确认解绑")
                }
            },
            dismissButton = { TextButton(onClick = { showUnbind = false }, enabled = !vm.unbinding) { Text("取消") } }
        )
    }
}

/** v5.5：账套切换 / 解绑后必须重启 App——各 ViewModel 内存里缓存着旧账套的数据 */
private fun restartApp(ctx: android.content.Context) {
    try {
        val i = android.content.Intent(ctx, com.heping.erpnative.MainActivity::class.java)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(i)
    } catch (_: Exception) {}
}

private fun openWeb(ctx: android.content.Context) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(WEB_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {}
}

/** 可折叠分区：标题栏点按展开/收起 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, vm: SettingsViewModel) {
    var oldP by remember { mutableStateOf("") }
    var newP by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                OutlinedTextField(value = oldP, onValueChange = { oldP = it }, label = { Text("旧密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = newP, onValueChange = { newP = it }, label = { Text("新密码(≥4位)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.changePassword(oldP, newP) { ok, err ->
                    if (ok) onDismiss() else msg = err
                }
            }, enabled = oldP.isNotBlank() && newP.length >= 4) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ShopDialog(onDismiss: () -> Unit, vm: SettingsViewModel) {
    val s = vm.settings
    var shop by remember { mutableStateOf(s?.shopName ?: "") }
    var title by remember { mutableStateOf(s?.receiptTitle ?: "销售小票") }
    var footer by remember { mutableStateOf(s?.receiptFooter ?: "谢谢惠顾，欢迎再次光临") }
    var warehouse by remember { mutableStateOf(s?.receiptWarehouse ?: "天河仓") }
    var handler by remember { mutableStateOf(s?.receiptHandler ?: "") }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("店铺设置") },
        text = {
            Column {
                OutlinedTextField(value = shop, onValueChange = { shop = it }, label = { Text("店名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("小票标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = footer, onValueChange = { footer = it }, label = { Text("小票底部") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = warehouse, onValueChange = { warehouse = it }, label = { Text("出库仓库（小票抬头）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = handler, onValueChange = { handler = it }, label = { Text("经手人（留空=登录账号名）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.saveSettings(shop, title, footer, warehouse, handler) { ok, err ->
                    if (ok) onDismiss() else msg = err
                }
            }, enabled = !vm.savingSettings) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
