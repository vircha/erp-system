package com.heping.erpnative.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.TopBar
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.SettingsViewModel

/**
 * 🔴 v5.7.4 账套管理全屏弹窗（经理 2026-10-28：「移动端没有账套添加或修改功能」）。
 *
 * 功能与网页版「账套」分区完全对齐：
 *  1. 账套列表 + 切换（切换后 App 重启，商品/订单/客户按新账套隔离）
 *  2. 新建账套            → POST /api/books（仅老板）
 *  3. 加入口令 保存/清除   → PUT /api/books/join-password（口令加密存储，不可查看）
 *  4. 邀请码 复制/重置     → GET /api/books/invite + POST /api/books/invite/reset
 *  5. 删除当前账套        → POST /api/books/delete（需输入账套名确认，级联清空全部数据）
 */
@Composable
fun BookManageDialog(
    onDismiss: () -> Unit,
    vm: SettingsViewModel,
    onLoggedOut: () -> Unit
) {
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var delConfirm by remember { mutableStateOf("") }
    var showDel by remember { mutableStateOf(false) }

    fun copyToClip(label: String, text: String) {
        try {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            toast(ctx, "$label 已复制")
        } catch (_: Exception) {
            toast(ctx, "复制失败，请手动记下")
        }
    }

    Dialog(
        onDismissRequest = { if (!vm.bookBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopBar("账套管理")
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ---------- 账套列表 ----------
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("我的账套", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(6.dp))
                                if (vm.books.isEmpty()) {
                                    Text(
                                        "正在加载…",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                vm.books.forEach { b ->
                                    val cur = b.id == SessionStore.bookId
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 44.dp)
                                            .clickable(enabled = !vm.switchingBook && !cur) {
                                                vm.switchBook(b.id) { ok -> if (ok) restartApp(ctx) }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            b.name + if (cur) "（当前）" else "",
                                            Modifier.weight(1f),
                                            fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "ID ${b.id}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        if (cur) {
                                            Icon(
                                                Icons.Filled.Check, "当前",
                                                Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        } else if (vm.switchingBook) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("切换", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ---------- 新建账套 ----------
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("新建账套", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "新账套会独立保存自己的商品、客户、订单与库存。仅老板可建。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    label = { Text("账套名称") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val n = newName.trim()
                                        if (n.isEmpty()) {
                                            toast(ctx, "账套名称必填")
                                            return@Button
                                        }
                                        vm.createBook(n) { ok, _ -> if (ok) newName = "" }
                                    },
                                    enabled = !vm.bookBusy && newName.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (vm.bookBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text("创建账套")
                                }
                            }
                        }
                    }

                    // ---------- 加入口令 ----------
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("加入口令", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "员工注册时选择「加入本账套」需填写该口令。口令加密存储，保存后不可查看，只能重设或清除。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = pwd,
                                    onValueChange = { pwd = it },
                                    label = { Text("新口令（留空 = 不修改）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            if (pwd.isBlank()) {
                                                toast(ctx, "请先输入新口令")
                                                return@Button
                                            }
                                            vm.setJoinPassword(pwd, false) { ok, _ -> if (ok) pwd = "" }
                                        },
                                        enabled = !vm.bookBusy && pwd.isNotBlank(),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("保存口令") }
                                    OutlinedButton(
                                        onClick = { vm.setJoinPassword("", true) { _, _ -> } },
                                        enabled = !vm.bookBusy,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("清除口令") }
                                }
                            }
                        }
                    }

                    // ---------- 邀请码 ----------
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("邀请员工", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                val inv = vm.inviteInfo
                                if (inv == null) {
                                    Text(
                                        "尚未获取邀请码",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { vm.loadInvite { } },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("获取邀请码") }
                                } else {
                                    Text(
                                        "账套：${inv.bookName}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        inv.inviteCode,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "加入口令：${if (inv.hasPassword) "已设置" else "未设置"} · 重置后旧邀请码立即失效",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                copyToClip(
                                                    "邀请码",
                                                    "【${inv.bookName}】进销存注册邀请\n套账：${inv.bookName}\n邀请码：${inv.inviteCode}\n" +
                                                                "注册时选择「加入：${inv.bookName}」，加入口令处填邀请码即可"
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("复制邀请文案") }
                                        OutlinedButton(
                                            onClick = { vm.resetInvite { } },
                                            enabled = !vm.bookBusy,
                                            modifier = Modifier.weight(1f)
                                        ) { Text("重置邀请码") }
                                    }
                                }
                            }
                        }
                    }

                    // ---------- 删除账套 ----------
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "删除当前账套",
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "不可恢复：将永久删除「${SessionStore.bookName}」的全部订单、客户、商品、库存、价目及账户。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { showDel = true },
                                    enabled = !vm.bookBusy,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("删除当前账套") }
                            }
                        }
                    }
                }

                vm.bookManageMsg?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    // 删除确认（必须输入账套名）
    if (showDel) {
        AlertDialog(
            onDismissRequest = { if (!vm.bookBusy) showDel = false },
            title = { Text("永久删除账套") },
            text = {
                Column {
                    Text(
                        "将永久删除该账套全部数据，且不可恢复。请输入账套名「${SessionStore.bookName}」确认：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = delConfirm,
                        onValueChange = { delConfirm = it },
                        label = { Text("账套名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = SessionStore.bookName
                        if (delConfirm.trim() != name) {
                            toast(ctx, "名称不一致")
                            return@Button
                        }
                        vm.deleteBook(name) { ok, loggedOut ->
                            if (ok) {
                                showDel = false
                                if (loggedOut) {
                                    toast(ctx, "账套已删除，请重新登录")
                                    onLoggedOut()
                                } else {
                                    toast(ctx, "账套已删除")
                                    onDismiss()
                                }
                            }
                        }
                    },
                    enabled = !vm.bookBusy && delConfirm.trim() == SessionStore.bookName,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { showDel = false }) { Text("取消") } }
        )
    }
}
