package com.heping.erpnative.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.fmtMoney
import com.heping.erpnative.vm.ArViewModel

/**
 * 我的欠款（仅客户账号，2026-10-18）
 *
 * 客户登录后看自己门店的欠款和未结单据。
 * 🔴 数据隔离由服务端保证：`/api/ar/unsettled` 对 customer 角色会**强制锁定自己的门店**，
 *    这里传不传 customer_id 都一样，传了只为语义清晰。
 */
@Composable
fun MyArScreen(arVm: ArViewModel) {
    // 2026-10-19 多门店：欠款页跟「开单页当前门店」保持一致。
    //    切了门店就换一家店的欠款 —— 与客户在开单页看到的口径统一。
    val cid = if (SessionStore.isCustomer) SessionStore.saleStore else SessionStore.customerId
    val unsettled = arVm.unsettled
    val loading = arVm.unsettledLoading
    val err = arVm.error

    LaunchedEffect(cid) {
        if (cid > 0) arVm.loadUnsettled(cid)
    }

    if (!SessionStore.isCustomer) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Text("该页面仅客户账号可用")
        }
        return
    }
    if (cid <= 0) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Text("账号尚未绑定门店，请联系商家开通")
        }
        return
    }

    val debt = unsettled?.debt ?: 0.0
    val rc = unsettled?.returnCredit ?: 0.0
    val rows = unsettled?.orders ?: emptyList()

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前欠款合计", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        fmtMoney(debt),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (debt > 0.005) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    val term = unsettled?.termDays ?: 30
                    Text(
                        "账期 $term 天" + if (rc != 0.0) " · 已扣退货 " + fmtMoney(kotlin.math.abs(rc)) else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (loading && unsettled == null) {
            item { Text("加载中…", modifier = Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        if (err != null && unsettled == null) {
            item {
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@LazyColumn
        }

        item {
            Text("未结单据（${rows.size}）", fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        if (rows.isEmpty()) {
            item { Text("暂无未结单据", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(rows) { o ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(o.orderNo, fontWeight = FontWeight.SemiBold)
                            Text(fmtMoney(o.left), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(o.orderDate, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "单额 ${fmtMoney(o.due)} · ${o.days} 天",
                                fontSize = 12.sp,
                                color = if (o.overdue) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
