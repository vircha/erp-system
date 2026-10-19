package com.heping.erpnative.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.Product
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.CenteredMessage
import com.heping.erpnative.ui.components.ErrorText
import com.heping.erpnative.ui.components.HighlightText
import com.heping.erpnative.ui.components.OfflineBanner
import com.heping.erpnative.ui.components.TopBar
import com.heping.erpnative.ui.components.fmtMoney
import com.heping.erpnative.util.Fuzzy
import com.heping.erpnative.vm.ProductsViewModel
import kotlinx.coroutines.launch

@Composable
fun ProductsScreen(vm: ProductsViewModel) {
    val scope = rememberCoroutineScope()
    val canEdit = SessionStore.can("product.edit")
    var query by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Product?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    // 本地模糊过滤（多关键词 AND + 前缀/子串/子序列），按相关度排序
    val shown = remember(vm.list, query) {
        Fuzzy.filter(vm.list, query) { listOf(it.name, it.barcode, it.spec, it.unit) }
    }

    if (showDialog) {
        ProductEditDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { p, isNew ->
                scope.launch { if (vm.save(p, isNew)) showDialog = false }
            },
            saving = vm.saving
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("商品")
        OfflineBanner(vm.offline)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("模糊搜索 名称/条码/规格（空格拆多关键词）") },
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
                "模糊匹配 ${shown.size} / 共 ${vm.list.size} 个商品",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
            )
        }
        ErrorText(vm.error)
        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (shown.isEmpty()) {
            CenteredMessage(if (query.isNotBlank()) "无匹配商品（试试少输几个字，或用空格拆关键词）" else "暂无商品")
        } else {
            LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(shown) { p ->
                    Card(Modifier.fillMaxWidth().padding(4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(enabled = canEdit) { editing = p; showDialog = true }) {
                                HighlightText(p.name, query, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                val sub = listOfNotNull(p.spec?.takeIf { it.isNotBlank() }, (p.unit ?: "").takeIf { it.isNotBlank() }).joinToString(" · ")
                                Text(
                                    "${fmtMoney(p.price)}  ·  库存 ${if (p.qty % 1 == 0.0) p.qty.toInt() else p.qty}" + (if (sub.isNotEmpty()) "  ·  $sub" else ""),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (canEdit) {
                                IconButton(onClick = { editing = p; showDialog = true }) { Icon(Icons.Filled.Edit, "编辑") }
                                IconButton(onClick = { scope.launch { vm.delete(p.id) } }) {
                                    Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductEditDialog(
    initial: Product?,
    onDismiss: () -> Unit,
    onSave: (Product, Boolean) -> Unit,
    saving: Boolean
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var barcode by remember { mutableStateOf(initial?.barcode ?: "") }
    var spec by remember { mutableStateOf(initial?.spec ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "个") }
    var price by remember { mutableStateOf((initial?.price ?: 0.0).toString()) }
    var cost by remember { mutableStateOf((initial?.cost ?: 0.0).toString()) }
    var qty by remember { mutableStateOf((initial?.qty ?: 0.0).toString()) }
    var low by remember { mutableStateOf((initial?.lowThreshold ?: 0.0).toString()) }
    var showScanner by remember { mutableStateOf(false) }
    val isNew = initial == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增商品" else "编辑商品") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称*") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(value = spec, onValueChange = { spec = it }, label = { Text("规格") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("单位") }, singleLine = true, modifier = Modifier.width(90.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = barcode, onValueChange = { barcode = it }, label = { Text("条码") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    // 扫码按钮：点一下调起摄像头，识别到条码/二维码自动填入并退出
                    OutlinedButton(
                        onClick = { showScanner = true },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "扫码", modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("扫码")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("售价") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(value = cost, onValueChange = { cost = it }, label = { Text("成本") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("库存") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(value = low, onValueChange = { low = it }, label = { Text("低库存") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = Product(
                        id = initial?.id ?: 0,
                        name = name.trim(),
                        barcode = barcode.trim(),
                        spec = spec.trim(),
                        unit = unit.trim().ifEmpty { "个" },
                        price = price.toDoubleOrNull() ?: 0.0,
                        cost = cost.toDoubleOrNull() ?: 0.0,
                        qty = qty.toDoubleOrNull() ?: 0.0,
                        lowThreshold = low.toDoubleOrNull() ?: 0.0
                    )
                    onSave(p, isNew)
                },
                enabled = name.isNotBlank() && !saving
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    // 扫码窗口：识别成功 → 填入条码 → 自动关闭
    if (showScanner) {
        ScannerDialog(
            title = "扫商品条码",
            hint = "把商品条码或二维码对准取景框",
            onResult = { raw, _ ->
                barcode = raw.trim()
                showScanner = false
            },
            onDismiss = { showScanner = false }
        )
    }
}
