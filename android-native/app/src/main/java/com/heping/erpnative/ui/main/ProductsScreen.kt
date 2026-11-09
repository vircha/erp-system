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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import com.heping.erpnative.ui.components.ProductImage
import com.heping.erpnative.ui.components.compressImageToDataUrl
import androidx.compose.runtime.rememberCoroutineScope
import com.heping.erpnative.ui.components.toast

@Composable
fun ProductsScreen(vm: ProductsViewModel, onBack: () -> Unit = {}) {
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
            onSave = { p, isNew, imageData ->
                scope.launch {
                    if (vm.save(p, isNew)) {
                        // 有选图就先落库拿到 id，再上传图片
                        val id = if (isNew) vm.lastSavedId else p.id
                        if (imageData != null && id > 0) vm.uploadImage(id, imageData)
                        showDialog = false
                    }
                }
            },
            saving = vm.saving,
            vm = vm
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("商品", onBack)
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
                items(shown, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().padding(4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 🔴 2026-11-07 商品缩略图：没有图片时自动显示默认占位图
                            ProductImage(
                                url = p.image,
                                modifier = Modifier.size(56.dp),
                                corner = 10
                            )
                            Spacer(Modifier.width(10.dp))
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
    onSave: (Product, Boolean, String?) -> Unit,
    saving: Boolean,
    vm: ProductsViewModel
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var barcode by remember { mutableStateOf(initial?.barcode ?: "") }
    var spec by remember { mutableStateOf(initial?.spec ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "个") }
    var price by remember { mutableStateOf((initial?.price ?: 0.0).toString()) }
    var cost by remember { mutableStateOf((initial?.cost ?: 0.0).toString()) }
    var qty by remember { mutableStateOf((initial?.qty ?: 0.0).toString()) }
    var low by remember { mutableStateOf((initial?.lowThreshold ?: 0.0).toString()) }
    // 每箱装量（件数换算用）：网页版商品编辑已有此字段，手机端补齐，
    // 否则手机端无法维护箱规 → 件数永远算不出来。
    var pack by remember { mutableStateOf((initial?.packSize ?: 0).toString()) }
    var showScanner by remember { mutableStateOf(false) }
    val isNew = initial == null

    // 🔴 2026-11-07 商品图片
    val ctx = LocalContext.current
    val scope2 = rememberCoroutineScope()
    // 选完图压缩后的 base64 data URL（null = 没改图片，沿用原来的）
    var imageData by remember { mutableStateOf<String?>(null) }
    // 仅用于"选完立刻预览"，避免等上传完才看到
    var previewBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var compressing by remember { mutableStateOf(false) }
    // 🔴 「获取同类照片」匹配到的图（服务端本地地址；不含任何来源信息）
    var matchedImage by remember { mutableStateOf<String?>(null) }
    var matching by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            compressing = true
            scope2.launch {
                val d = compressImageToDataUrl(ctx, uri)
                compressing = false
                if (d != null) {
                    imageData = d
                    // 解出来做预览
                    previewBmp = try {
                        val b64 = d.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (_: Throwable) { null }
                }
            }
        }
    }

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
                Spacer(Modifier.height(6.dp))
                // 🔴 每箱装量（件数换算用）：件数 = 数量 ÷ 这个值，**保留 2 位小数不进位**。
                //    0 = 不换算（单品销售/未维护箱规）→ 界面上件数列留空。
                OutlinedTextField(
                    value = pack,
                    onValueChange = { s -> if (s.isEmpty() || s.matches(Regex("^\\d{0,6}$"))) pack = s },
                    label = { Text("每箱装量（0=不换算）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                // 🔴 商品图片：拍照/相册选图 → 压缩 → 随保存一起上传
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp)) {
                        val pb = previewBmp
                        if (pb != null) {
                            Image(
                                bitmap = pb,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            ProductImage(
                                url = matchedImage ?: initial?.image,
                                modifier = Modifier.fillMaxSize(),
                                corner = 10
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            OutlinedButton(
                                onClick = { pickImage.launch("image/*") },
                                enabled = !compressing,
                                modifier = Modifier.height(40.dp).weight(1f)
                            ) {
                                Text(if (compressing) "处理中…" else if (initial?.image.isNullOrBlank() && matchedImage == null) "上传图片" else "更换", fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            // 🔴 平台图库：按条码优先、名称模糊自动匹配，全程后台完成，不暴露来源
                            OutlinedButton(
                                onClick = {
                                    matching = true
                                    scope2.launch {
                                        val got = vm.matchImage(
                                            barcode = barcode.trim(),
                                            name = name.trim(),
                                            excludeId = initial?.id ?: 0
                                        )
                                        matching = false
                                        if (got.isNotBlank()) {
                                            matchedImage = got
                                            imageData = null
                                            previewBmp = null
                                        } else {
                                            toast(ctx, "平台暂无同类图片，可手动上传")
                                        }
                                    }
                                },
                                enabled = !matching,
                                modifier = Modifier.height(40.dp).weight(1f)
                            ) {
                                Text(if (matching) "匹配中…" else "同类照片", fontSize = 12.sp)
                            }
                        }
                        Text(
                            if (matchedImage != null) "已从平台图库匹配，保存后生效"
                            else "不添加则显示默认图",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        lowThreshold = low.toDoubleOrNull() ?: 0.0,
                        packSize = pack.toIntOrNull() ?: 0,
                        // 图片：本机选的图走 base64 上传（image 留空避免覆盖）；
                        // 平台匹配的直接用地址（服务端已校验只接受本站路径）
                        image = when {
                            imageData != null -> initial?.image
                            matchedImage != null -> matchedImage
                            else -> initial?.image
                        }
                    )
                    onSave(p, isNew, imageData)
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
