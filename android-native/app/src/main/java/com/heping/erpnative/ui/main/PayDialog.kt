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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heping.erpnative.data.ArOrder
import com.heping.erpnative.data.ArPayItem
import com.heping.erpnative.data.ArUnsettledResp
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.ArViewModel
import kotlin.math.abs

private val PAY_METHODS = listOf("微信", "支付宝", "现金", "银行转账", "承兑")

/**
 * 收款弹窗（管家婆式：挂账 → 收款单 → 核销）。
 *
 * 🔴 交互要点：
 *   - 未结清单由服务端按日期升序给出，**默认从最早那张开始自动分配**（管家婆逻辑）
 *   - 每张单的分配金额都可手工改，改动后总差额实时显示
 *   - 优惠/抹零单独记账（记 payments.discount），**订单金额一个字都不动**
 *   - 超期（账龄 > 账期）的单整行标红提醒，但**不拦截**——照收
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayDialog(
    vm: ArViewModel,
    cid: Int,
    shopName: String,
    data: ArUnsettledResp,
    onDismiss: () -> Unit,
    onPaid: (String) -> Unit
) {
    val ctx = LocalContext.current
    var amount by remember(data) { mutableStateOf("") }
    var discount by remember(data) { mutableStateOf("") }
    var method by remember { mutableStateOf(PAY_METHODS.first()) }
    var payDate by remember { mutableStateOf(ArViewModel.todayStr()) }
    var remark by remember { mutableStateOf("") }
    // orderId → 本次分配到该单的金额（实收+优惠合计）
    var alloc by remember(data) { mutableStateOf<Map<Int, Double>>(emptyMap()) }

    val amt = amount.trim().toDoubleOrNull() ?: 0.0
    val disc = discount.trim().toDoubleOrNull() ?: 0.0
    val total = ArViewModel.round2(amt + disc)

    // 金额或优惠一变就按管家婆逻辑重算分配（最早单先结）
    LaunchedEffect(amt, disc, data.orders) {
        if (total > 0.005) {
            alloc = ArViewModel.autoAlloc(data.orders, total)
        } else if (total <= 0.005) {
            alloc = emptyMap()
        }
    }

    val allocSum = alloc.values.sumOf { it }
    val diff = ArViewModel.round2(total - allocSum)

    Dialog(
        onDismissRequest = onDismiss,
        // 🔴 收款是小屏上的主要操作，用平台默认宽度会被压缩成一条；显式放宽
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(14.dp)) {
                // 标题
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记一笔收款", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(shopName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }

                // 欠款总览
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("当前欠款", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            // 🔴🔴 用 debt（含退货冲减），**不能用 totalLeft**。
                            //    totalLeft 是「待收单据未结合计」，不含退货抵减 ——
                            //    标签写着「欠款」却给一个没扣退货的数字，比显示错更糟：
                            //    万家便利店-4店会显示 770.70，实际欠款 650.46（退货 -120.24）。
                            //    （与网页版 2026-10-18 的修复口径保持一致）
                            Text(
                                "¥" + String.format(java.util.Locale.US, "%.2f", data.debt),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            // 有退货冲减时把口径写出来，否则数字变小会被当成算错
                            if (data.returnCredit != 0.0) {
                                Text(
                                    "待收 " + String.format(java.util.Locale.US, "%.2f", data.totalLeft) +
                                        " − 退货 " + String.format(java.util.Locale.US, "%.2f", Math.abs(data.returnCredit)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${data.orders.size} 张未结", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("账期 ${data.termDays} 天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // ---- 本次收款 ----
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = amount, onValueChange = { amount = it },
                            label = { Text("本次收款") }, prefix = { Text("¥") },
                            singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = discount, onValueChange = { discount = it },
                            label = { Text("优惠抹零") }, prefix = { Text("¥") },
                            singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "抹零优惠单独记账，不会改动销售单金额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = payDate, onValueChange = { payDate = it },
                            label = { Text("收款日期") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = remark, onValueChange = { remark = it },
                            label = { Text("备注") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("收款方式", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    // 🔴 同一行按钮统一 flex + 固定高，别用 wrap（一个放不下整行就跳下去）
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PAY_METHODS.forEach { m ->
                            if (method == m) {
                                Button(
                                    onClick = { method = m },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.weight(1f).height(32.dp)
                                ) { Text(m, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center) }
                            } else {
                                OutlinedButton(
                                    onClick = { method = m },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.weight(1f).height(32.dp)
                                ) { Text(m, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center) }
                            }
                        }
                    }

                    // ---- 未结清单 ----
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("未结清单（最早在上）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (diff != 0.0) {
                            Text(
                                if (diff > 0) "还差 ¥${abs(diff)} 未分配" else "多分配 ¥${abs(diff)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    if (data.orders.isEmpty()) {
                        Text("该门店已全部结清", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        data.orders.forEach { o -> UnsettledRow(o, alloc[o.orderId] ?: 0.0) { v ->
                            alloc = if (v <= 0.005) alloc - o.orderId else alloc + (o.orderId to ArViewModel.round2(v))
                        } }
                    }

                    // ---- 分配校验提示 ----
                    if (total > 0.005) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text("已分配", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "¥" + String.format(java.util.Locale.US, "%.2f", allocSum) + " / ¥" + String.format(java.util.Locale.US, "%.2f", total),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (abs(diff) <= 0.005) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                }

                // ---- 底部按钮：固定一行同高 ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("取消", fontSize = 15.sp) }
                    Button(
                        onClick = {
                            if (alloc.isEmpty()) { toast(ctx, "请先填写收款金额"); return@Button }
                            val items = alloc.map { (oid, v) -> ArPayItem(orderId = oid, amount = v, discount = 0.0) }
                            vm.pay(cid, amt, disc, method, payDate, remark, items) { no ->
                                if (no != null) { toast(ctx, "已收款：$no"); onPaid(no) } else toast(ctx, vm.error ?: "收款失败")
                            }
                        },
                        enabled = !vm.paying && alloc.isNotEmpty(),
                        modifier = Modifier.weight(1.4f).height(48.dp)
                    ) { Text(if (vm.paying) "提交中…" else "确认收款", fontSize = 15.sp) }
                }
            }
        }
    }
}

/** 未结清单里的一行：超期整行标红，右侧可手工改分配额 */
@Composable
private fun UnsettledRow(o: ArOrder, assigned: Double, onChange: (Double) -> Unit) {
    var txt by remember(o.orderId) {
        mutableStateOf(if (assigned > 0.005) fmtA(assigned) else "")
    }
    // 外部自动分配变化时同步回输入框（用户没在编辑才同步，避免打断输入）
    LaunchedEffect(assigned) {
        if (abs(assigned - (txt.toDoubleOrNull() ?: 0.0)) > 0.005) {
            txt = if (assigned > 0.005) fmtA(assigned) else ""
        }
    }

    val bg = if (o.overdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else Color.Transparent
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.orderNo, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (o.overdue) {
                        Spacer(Modifier.width(5.dp))
                        Text("超期", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    if (o.state == "partial") {
                        Spacer(Modifier.width(5.dp))
                        Text("已收 ¥${fmtA(o.paid)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${o.orderDate} · ${o.days}天 · 未结 ¥${fmtA(o.left)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = txt,
                onValueChange = { v ->
                    txt = v
                    onChange(v.trim().toDoubleOrNull() ?: 0.0)
                },
                label = { Text("分配") },
                prefix = { Text("¥") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.width(122.dp)
            )
        }
    }
}

/** 「按单结清」快捷按钮：把这张单的未结额直接填进分配框 */
@Composable
fun quickFillAllButton(data: ArUnsettledResp, onClick: () -> Unit) {
    if (data.orders.isEmpty()) return
    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Text("全部结清", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

private fun fmtA(v: Double): String =
    if (abs(v - Math.round(v)) < 0.005) String.format(java.util.Locale.US, "%.0f", v)
    else String.format(java.util.Locale.US, "%.2f", v)