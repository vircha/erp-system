package com.heping.erpnative.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heping.erpnative.ui.components.*
import com.heping.erpnative.vm.ReturnsViewModel

@Composable
fun ReturnsScreen(vm: ReturnsViewModel) {
    LaunchedEffect(Unit) { vm.loadReturns() }

    if (vm.detail != null) {
        OrderDetailDialog(
            detail = vm.detail!!,
            canReturn = false,
            onClose = { vm.clearDetail() },
            onReturnItems = {},
            returning = false
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("退货")
        OfflineBanner(vm.offline)

        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (vm.returns.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无退货单") }
        } else {
            LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(vm.returns) { o ->
                    Card(
                        Modifier.fillMaxWidth().padding(4.dp).clickable { vm.loadDetail(o.id) }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.orderNo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    "${o.customerName ?: "散客"}  ·  ${shortDate(o.orderDate)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (!o.remark.isNullOrBlank())
                                    Text(o.remark, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(fmtMoney(o.payable ?: o.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
