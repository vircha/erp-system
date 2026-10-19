package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.heping.erpnative.data.ArSummaryRow
import com.heping.erpnative.data.Customer
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.*
import com.heping.erpnative.util.Fuzzy
import com.heping.erpnative.vm.ArViewModel
import com.heping.erpnative.vm.CustomersViewModel
import kotlinx.coroutines.launch
import java.util.Locale

private enum class CustDlg { None, Pay, Unsettled }

/**
 * 客户页：门店档案 + **应收应付**。
 * 每家门店显示欠款金额 / 未结单数 / 超期单数 / 最长账龄，超期标红，
 * 可直接「收款」（管家婆式挂账核销）或看「未结明细」。
 */
@Composable
fun CustomersScreen(vm: CustomersViewModel, arVm: ArViewModel) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val canEdit = SessionStore.can("customer.edit")
    val canAr = SessionStore.can("ar.view")
    val canArEdit = SessionStore.can("ar.edit")
    var query by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Customer?>(null) }

    // ---- 欠款交互状态 ----
    var dlg by remember { mutableStateOf(CustDlg.None) }
    var activeCust by remember { mutableStateOf<Customer?>(null) }

    LaunchedEffect(Unit) {
        vm.load()
        if (canAr) arVm.loadSummary()
    }

    // 打开收款 / 未结明细前先拉该门店的未结清单
    LaunchedEffect(dlg, activeCust?.id) {
        if (dlg != CustDlg.None && activeCust?.id != null) arVm.loadUnsettled(activeCust!!.id)
    }

    if (showDialog) {
        CustomerEditDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { c, isNew ->
                scope.launch { if (vm.save(c, isNew)) showDialog = false }
            },
            saving = vm.saving
        )
    }

    val cur = activeCust
    if (dlg == CustDlg.Pay && cur != null && arVm.unsettled?.customerId == cur.id) {
        PayDialog(
            vm = arVm, cid = cur.id, shopName = cur.name,
            data = arVm.unsettled!!,
            onDismiss = { dlg = CustDlg.None },
            onPaid = { dlg = CustDlg.None }
        )
    }
    if (dlg == CustDlg.Unsettled && cur != null && arVm.unsettled?.customerId == cur.id) {
        UnsettledDialog(
            vm = arVm, cid = cur.id, shopName = cur.name,
            data = arVm.unsettled!!,
            onDismiss = { dlg = CustDlg.None },
            onGoPay = { dlg = CustDlg.Pay }
        )
    }
    // 加载中先给个提示，避免点了没反应
    if (dlg != CustDlg.None && arVm.unsettledLoading) {
        AlertDialog(
            onDismissRequest = { dlg = CustDlg.None },
            confirmButton = {},
            text = { Text("正在读取未结清单…") }
        )
    }

    // 欠款汇总表：customer_id → 行
    val arById = remember(arVm.summary) { arVm.summary.rows.associateBy { it.customerId } }

    val shown = remember(vm.list, query) {
        Fuzzy.filter(vm.list, query) { listOf(it.name, it.contact, it.phone, it.address, it.remark) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("客户")
        OfflineBanner(vm.offline)

        // ---- 欠款总览卡 ----
        if (canAr && arVm.summary.rows.isNotEmpty()) {
            DebtOverviewCard(arVm.summary.totalDebt, arVm.summary.totalOverdue, arVm.summary.rows.size)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("模糊搜索 名称/联系人/电话/地址（空格拆多关键词）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (canEdit) {
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(onClick = { editing = null; showDialog = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Add, "新增")
                }
            }
        }
        if (query.isNotBlank()) {
            Text(
                "模糊匹配 ${shown.size} / 共 ${vm.list.size} 家",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
            )
        }
        ErrorText(vm.error)
        if (arVm.error != null) ErrorText(arVm.error)

        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (shown.isEmpty()) {
            CenteredMessage(if (query.isNotBlank()) "无匹配门店（试试少输几个字，或用空格拆关键词）" else "暂无客户")
        } else {
            LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(shown) { c ->
                    CustomerCard(
                        c = c, query = query, canEdit = canEdit, canArEdit = canArEdit,
                        ar = if (canAr) arById[c.id] else null,
                        onEdit = { editing = c; showDialog = true },
                        onDelete = { scope.launch { vm.delete(c.id) } },
                        onPay = { activeCust = c; dlg = CustDlg.Pay },
                        onDetail = { activeCust = c; dlg = CustDlg.Unsettled }
                    )
                }
            }
        }
    }
}

/** 顶部欠款总览：全部门店欠款合计 + 超期单数 */
@Composable
private fun DebtOverviewCard(totalDebt: Double, overdueOrders: Int, storeCount: Int) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "全部门店欠款（$storeCount 家）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "¥" + String.format(Locale.US, "%.2f", totalDebt),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (overdueOrders > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "$overdueOrders 单超账期",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("请尽快跟进", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** 一家门店的卡片：有欠款时显示欠款徽章 + 收款按钮 */
@Composable
private fun CustomerCard(
    c: Customer,
    query: String?,
    canEdit: Boolean,
    canArEdit: Boolean,
    ar: ArSummaryRow?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPay: () -> Unit,
    onDetail: () -> Unit
) {
    val hasDebt = ar != null && ar.debt > 0.005
    Card(Modifier.fillMaxWidth().padding(4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = canEdit) { onEdit() }) {
                    HighlightText(c.name, query, style = MaterialTheme.typography.bodyLarge)
                    val sub = listOfNotNull(c.phone, c.contact, c.address).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) HighlightText(sub, query, style = MaterialTheme.typography.labelSmall)
                }
                if (canEdit) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "编辑") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }

            // ---- 欠款徽章 ----
            if (hasDebt) {
                val overdue = ar!!.overdueOrders > 0
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(
                            (if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .copy(alpha = 0.45f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "欠 ¥" + String.format(Locale.US, "%.2f", ar.debt),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (overdue) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${ar.overdueOrders} 单超 ${ar.termDays} 天",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Text(
                            "${ar.unsettled} 单未结 · 最长账龄 ${ar.maxDays} 天 · 账期 ${ar.termDays} 天",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 🔴 同一行按钮统一高度与 flex，别一个放不下就跳行
                    OutlinedButton(
                        onClick = onDetail,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text("未结明细", fontSize = 12.sp, maxLines = 1) }
                    if (canArEdit) {
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = onPay,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("收款", fontSize = 12.sp, maxLines = 1) }
                    }
                }
            } else if (ar != null && ar.unsettled == 0 && ar.debt <= 0.005) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("货款已结清", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDetail,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("查看流水", fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun CustomerEditDialog(
    initial: Customer?,
    onDismiss: () -> Unit,
    onSave: (Customer, Boolean) -> Unit,
    saving: Boolean
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var contact by remember { mutableStateOf(initial?.contact ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var remark by remember { mutableStateOf(initial?.remark ?: "") }
    val isNew = initial == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增客户" else "编辑客户") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称*") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("电话") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("联系人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val c = Customer(
                        id = initial?.id ?: 0,
                        name = name.trim(),
                        type = initial?.type ?: "store",
                        contact = contact.trim(),
                        phone = phone.trim(),
                        address = address.trim(),
                        remark = remark.trim()
                    )
                    onSave(c, isNew)
                },
                enabled = name.isNotBlank() && !saving
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}