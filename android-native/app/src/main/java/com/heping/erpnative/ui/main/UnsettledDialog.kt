package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heping.erpnative.data.ArUnsettledResp
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.ArViewModel

/**
 * 未结明细弹窗：看清单 + 改账期 + 转到收款。
 * 超期只提醒不拦截（账期默认 30 天，由经理定）。
 */
@Composable
fun UnsettledDialog(
    vm: ArViewModel,
    cid: Int,
    shopName: String,
    data: ArUnsettledResp,
    onDismiss: () -> Unit,
    onGoPay: () -> Unit
) {
    val ctx = LocalContext.current
    var showTerm by remember { mutableStateOf(false) }
    val canEdit = SessionStore.can("ar.edit")

    if (showTerm) {
        TermDialog(
            vm = vm, cid = cid, shopName = shopName, current = data.termDays,
            onDismiss = { showTerm = false },
            onSaved = { showTerm = false; onDismiss() }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.86f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("未结明细", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(shopName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }

                // 总览 + 账期
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("欠款合计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 🔴🔴 同PayDialog：标签是「欠款合计」就必须用 debt（含退货冲减），
                            //    totalLeft 不含退货 —— 显示会比实际欠款偏大。
                            Text(
                                "¥" + fmtUnsettled(data.debt),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            // 把口径写出来：有退货冲减时数字变小是正常的，不是算错
                            if (data.returnCredit != 0.0) {
                                Text(
                                    "待收 " + fmtUnsettled(data.totalLeft) +
                                        " − 退货 " + fmtUnsettled(Math.abs(data.returnCredit)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${data.orders.size} 张未结单", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("账期 ${data.termDays} 天", style = MaterialTheme.typography.labelMedium)
                            if (canEdit) {
                                TextButton(onClick = { showTerm = true }, contentPadding = PaddingValues(0.dp)) {
                                    Text("改账期", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "按开单日期从早到晚排列，收款时自动从最早那单开始结",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (data.orders.isEmpty()) {
                        Text("该门店已全部结清 🎉", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        data.orders.forEach { o ->
                            val bg = if (o.overdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else Color.Transparent
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .background(bg, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(o.orderNo, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            if (o.overdue) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("超期", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Text(
                                            "开单 ${o.orderDate} · 账龄 ${o.days} 天",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("未结 ¥${fmtUnsettled(o.left)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                        Text("应收 ¥${fmtUnsettled(o.due)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (o.state == "partial") {
                                    Text(
                                        "已收 ¥${fmtUnsettled(o.paid)}（部分收款）",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("关闭", fontSize = 15.sp)
                    }
                    if (canEdit) {
                        Button(
                            onClick = { onGoPay() },
                            enabled = data.orders.isNotEmpty(),
                            modifier = Modifier.weight(1.4f).height(48.dp)
                        ) { Text("记一笔收款", fontSize = 15.sp) }
                    }
                }
            }
        }
    }
}

/** 改账期弹窗（默认 30 天） */
@Composable
private fun TermDialog(
    vm: ArViewModel,
    cid: Int,
    shopName: String,
    current: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf(current.toString()) }
    val n = d.trim().toIntOrNull()
    val valid = n != null && n in 0..365

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改账期") },
        text = {
            Column {
                Text(shopName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = d, onValueChange = { d = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("账期天数（0-365）") },
                    singleLine = true,
                    isError = d.isNotBlank() && !valid,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "超过账期未结的订单会在欠款清单里标红提醒（不影响继续开单）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    vm.setTerm(cid, n!!) { ok ->
                        if (ok) { toast(ctx, "账期已设为 $n 天"); onSaved() }
                        else toast(ctx, vm.error ?: "保存失败")
                    }
                },
                enabled = valid
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun fmtUnsettled(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)