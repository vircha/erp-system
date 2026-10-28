package com.heping.erpnative.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.heping.erpnative.data.NetState
import com.heping.erpnative.data.PendingOrder
import com.heping.erpnative.data.SyncManager
import com.heping.erpnative.ui.components.toast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🔴🔴 v5.7.4 离线同步管理（经理 2026-10-28：「移动版添加数据更新同步功能」）。
 *
 * 放在设置页里，让用户能：
 *  ① 看到「现在能不能上网」；
 *  ② 看到有多少单在本地排队等着上传；
 *  ③ 手动点「立即同步」（不等网络恢复）；
 *  ④ 看到某单同步失败的原因，并能单独重试或删掉。
 *
 * ⚠️ 待同步列表刻意放在**弹窗**里而不是主页面 —— 设置页是竖向滚动的，
 *   插一堆卡片会把其他分区挤下去；弹窗一次看完，也更好点「重试」。
 */
@Composable
fun OfflineSyncDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<PendingOrder>>(emptyList()) }
    var online by remember { mutableStateOf(NetState.isOnline()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        pending = SyncManager.pendingList(ctx)
        online = NetState.isOnline()
    }

    LaunchedEffect(Unit) { reload() }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudUpload, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("离线同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (!busy) onDismiss() }) { Text("✕") }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (online) "网络状态：已联网" else "网络状态：离线（单据会存在本机，联网后自动上传）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text("待同步单据：${pending.size} 单", style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(10.dp))
                Row {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            scope.launch {
                                val r = SyncManager.sync(ctx, force = true)
                                msg = r.message
                                toast(ctx, r.message ?: "同步完成")
                                reload()
                                busy = false
                            }
                        },
                        enabled = !busy && pending.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即同步")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = SyncManager.retryAll(ctx)
                                msg = r.message
                                toast(ctx, r.message ?: "已重试")
                                reload()
                                busy = false
                            }
                        },
                        enabled = !busy && pending.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("全部重试") }
                }

                if (msg != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(msg!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(10.dp))
                if (pending.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("没有待同步的单据", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(pending, key = { it.clientId }) { po ->
                            PendingRow(
                                po = po,
                                busy = busy,
                                onRetry = {
                                    busy = true
                                    scope.launch {
                                        val r = SyncManager.retryOne(ctx, po.clientId)
                                        toast(ctx, r.message ?: "已重试")
                                        reload()
                                        busy = false
                                    }
                                },
                                onDelete = {
                                    busy = true
                                    scope.launch {
                                        SyncManager.deleteOne(ctx, po.clientId)
                                        reload()
                                        busy = false
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** 一条待同步单据 */
@Composable
private fun PendingRow(
    po: PendingOrder,
    busy: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val t = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(po.createdAt))
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(po.customerName.ifEmpty { "（未选客户）" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (po.status == "failed") "同步失败" else "待上传",
                style = MaterialTheme.typography.labelSmall,
                color = if (po.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "金额 ${po.amount}　·　$t　·　单号 ${po.clientId.take(8)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!po.error.isNullOrEmpty()) {
            Text("原因：${po.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDelete, enabled = !busy) { Text("删除", fontSize = 13.sp) }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onRetry, enabled = !busy) { Text("重试", fontSize = 13.sp) }
        }
    }
}
