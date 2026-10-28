package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.Order
import com.heping.erpnative.data.OrderDetail
import com.heping.erpnative.ui.components.TopBar
import com.heping.erpnative.ui.components.OfflineBanner
import com.heping.erpnative.ui.components.HighlightText
import com.heping.erpnative.ui.components.fmtMoney
import com.heping.erpnative.ui.components.fmtQty
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.shortDate
import com.heping.erpnative.util.Fuzzy
import com.heping.erpnative.vm.CartLine
import com.heping.erpnative.vm.SaleViewModel
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

@Composable
fun SaleScreen(vm: SaleViewModel, onEditingDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCustomer by remember { mutableStateOf(false) }
    var showStorePicker by remember { mutableStateOf(false) }   // 2026-10-19 客户多门店：切换下单门店
    var productQuery by remember { mutableStateOf("") }
    var showSuccess by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    var printingOrderId by remember { mutableStateOf(-1) }
    var moreMenu by remember { mutableStateOf(false) }        // 更多录入方式菜单（模板/导入/图片）
    var browseProducts by remember { mutableStateOf(false) }  // 未输入关键词时是否展开商品列表
    var detailIdx by remember { mutableStateOf<Int?>(null) }  // 购物车明细弹窗（改数量/单价/删除）
    // 单据模式存在 ViewModel 里（切换时要清空购物车/客户，UI 自己持有管不住）
    val mode = vm.mode
    val isReturn = vm.isReturnMode
    val isEditing = vm.isEditing

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bm = loadScaledBitmap(ctx, uri)
            if (bm != null) vm.parseOcrBitmap(bm, ctx)
            else toast(
                ctx,
                "读取图片失败：图片可能过大或格式不支持。可先截图再试，或改用「导入表格」",
                long = true
            )
        }
    }

    val pickTable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { /* 部分 ROM 不支持，忽略即可 */ }
            vm.importTableFile(ctx, uri)
        }
    }

    // 打开系统文件选择器挑 xlsx / csv
    val launchTablePicker: () -> Unit = {
        try {
            pickTable.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/csv", "text/comma-separated-values",
                    "application/vnd.ms-excel", "*/*"
                )
            )
        } catch (e: Exception) {
            toast(ctx, "无法打开文件选择器：${e.message ?: ""}")
        }
    }

    LaunchedEffect(Unit) {
        vm.loadCustomers()
        vm.loadProducts()
    }
    // 🔴 客户账号：开单只能给自己门店，加载完客户列表后自动锁定，不给选别家。
    //    服务端 POST /api/orders 也会强制改写 customer_id，这里只是让界面不误导。
    // 2026-10-19 多门店：锁定的是**当前选中门店**（SessionStore.saleStore），可切换。
    LaunchedEffect(SessionStore.customerId, vm.customers.size) {
        val cid = if (SessionStore.isCustomer) SessionStore.saleStore else SessionStore.customerId
        if (SessionStore.isCustomer && cid > 0 && vm.selectedCustomer?.id != cid) {
            vm.customers.firstOrNull { it.id == cid }?.let { vm.selectCustomer(it) }
        }
    }

    if (showCustomer) {
        CustomerPicker(
            customers = vm.customers,
            onPick = { vm.selectCustomer(it); showCustomer = false },
            onDismiss = { showCustomer = false }
        )
    }

    // 2026-10-19 客户多门店：只列他被授权的门店
    if (showStorePicker) {
        val mine = vm.customers.filter { SessionStore.custStoreAllowed(it.id) }
        StorePickerDialog(
            stores = mine,
            currentId = vm.selectedCustomer?.id ?: -1,
            onPick = { c ->
                showStorePicker = false
                scope.launch { vm.switchStore(c.id) }
            },
            onDismiss = { showStorePicker = false }
        )
    }

    if (showSuccess && vm.lastResult != null) {
        val r = vm.lastResult!!
        AlertDialog(
            // 🔴 2026-10-15 经理反馈「点了打印弹窗就没了，要打两次单」：
            // 原来 confirmButton 里 finally { showSuccess = false } 无条件关窗，
            // 打印成功也关、失败也关。现在改成：
            // ① 打印后**不关窗**，可以直接再点一次打印（打第二张、第三张联单）
            // ② 也不在这里 clearCart() —— 单据明细以服务端为准，
            //    提前清购物车会让「再打一张」拿到空数据
            // ③ 想结束就点「完成」或窗体外区域，那时才清
            onDismissRequest = { showSuccess = false; vm.clearCart() },
            title = { Text(if (isReturn) "退货单已生成" else "开单成功") },
            text = {
                Column {
                    Text("单号：${r.orderNo}")
                    Text(if (isReturn) "应退：${fmtMoney(r.payableSigned ?: -r.payable)}" else "应收：${fmtMoney(r.payable)}")
                    if (vm.printHint.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            vm.printHint,
                            fontSize = 12.sp,
                            color = if (vm.printOk) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            printing = true
                            vm.printHint = "正在打印…"
                            try {
                                val res = vm.printLast(ctx)
                                vm.printOk = (res == com.heping.erpnative.device.PrintResult.SUCCESS)
                                vm.printHint = if (vm.printOk) {
                                    "✅ 已打印，可继续点「再打一张」打联单"
                                } else {
                                    "❌ ${res.message}，可点重试或去设置页检查打印机"
                                }
                            } catch (e: Exception) {
                                vm.printOk = false
                                vm.printHint = "❌ 打印出错：${e.message ?: "未知错误"}"
                            } finally {
                                printing = false
                                // 🔴 关键：这里不再 showSuccess = false，弹窗保留
                            }
                        }
                    },
                    enabled = !printing
                ) { Text(if (printing) "打印中…" else if (vm.printHint.isEmpty()) "打印小票" else "再打一张") }
            },
            dismissButton = {
                TextButton(onClick = { showSuccess = false; vm.clearCart() }) { Text("完成") }
            }
        )
    }

    // 图片开单：识别失败 → 弹窗（含识别原文，便于核对）
    if (vm.ocrError != null) {
        OcrErrorDialog(
            msg = vm.ocrError!!,
            raw = vm.ocrRaw,
            onClose = { vm.dismissOcr() }
        )
    }

    // 开单模板：下载结果 / 使用提示
    vm.templateMsg?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.clearTemplateMsg() },
            title = { Text("开单模板") },
            text = { Text(m, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.clearTemplateMsg() }) { Text("知道了") } }
        )
    }

    // 购物车明细：改数量 / 单价 / 删除（点购物车行的名称区域打开）
    val detailLine = detailIdx?.let { i -> vm.cart.getOrNull(i) }
    if (detailIdx != null && detailLine != null) {
        CartLineDetailDialog(
            line = detailLine,
            onQty = { vm.setQty(detailIdx!!, it) },
            onPrice = { vm.setPrice(detailIdx!!, it) },
            onRemove = { vm.removeAt(detailIdx!!); detailIdx = null },
            onDismiss = { detailIdx = null }
        )
    }

    // 图片开单：预览确认
    vm.ocrTable?.let { t ->
        OcrPreviewDialog(
            table = t,
            customers = vm.customers,
            products = vm.products,
            generating = vm.generating,
            onCancel = { vm.dismissOcr() },
            onConfirm = { finals ->
                vm.dismissOcr()
                vm.generateOcrOrders(finals)
            }
        )
    }

    // 图片开单：生成结果（可逐单打印）
    vm.genResults?.let { results ->
        OcrResultDialog(
            results = results,
            printingOrderId = printingOrderId,
            onPrint = { id ->
                scope.launch {
                    printingOrderId = id
                    try {
                        val d = ErpClient.api.orderDetail(id)
                        val res = printOrder(ctx, d)
                        toast(ctx, res.message)
                    } catch (e: Exception) {
                        toast(ctx, "获取订单失败：${e.message ?: "网络错误"}")
                    } finally {
                        printingOrderId = -1
                    }
                }
            },
            onClose = { vm.dismissGenResults() }
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            when {
                isEditing -> "修改订单 ${vm.editingOrderNo ?: ""}"
                isReturn -> "退货单"
                else -> "开单"
            }
        )
        OfflineBanner(vm.offline, vm.pendingSyncCount)
        // 编辑订单提示条：说清能干什么 + 给一个放弃修改的出口
        if (isEditing) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "正在修改原单，可自由增删商品、改数量/单价/单品折扣。保存后单号与原单一致。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    TextButton(onClick = {
                        vm.cancelEdit(); onEditingDone()
                    }) { Text("放弃修改", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        // 销售单 / 退货单 模式切换（默认销售单）。编辑订单时隐藏：编辑的是销售单，不能切
        // 🔴 客户账号不给退货单入口（服务端 POST 也会拒 type='return'）
        if (!isEditing && !SessionStore.isCustomer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeTab("销售单", mode == "sale", Modifier.weight(1f)) { vm.switchMode("sale") }
            ModeTab("退货单", mode == "return", Modifier.weight(1f)) { vm.switchMode("return") }
        }
        }
        // 退货单模式给一句说明：跟开销售单一样手工录商品，不需要指定原单
        if (isReturn) {
            Text(
                "手工录入退货商品即可，无需指定原销售单。数量按正数填写，系统自动记为负数并回补库存。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // 一行搞定：选择客户 + 更多录入方式（模板/导入/图片收进菜单，省屏幕）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (SessionStore.isCustomer) {
                // 🔴🔴 2026-10-20 经理（网页版）两次反馈「我都不知道原来门店可以选择的」，手机版同步。
                //   原来只给一个 36dp 的「切换门店」小按钮，跟旁边的文字一个视觉层级，
                //   看不出这里能点。改成整块可点的卡片：标题 + 店名大字 + 右侧绿色「切换 ›」。
                //   多门店才可点；只有一家门店就显示「已锁定」，不给假入口。
                val canSwitch = SessionStore.customerIds.size > 1
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                            .border(
                                if (canSwitch) 1.5.dp else 1.dp,
                                if (canSwitch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            )
                            .then(if (canSwitch) Modifier.clickable { showStorePicker = true } else Modifier)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "当前下单门店",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                vm.selectedCustomer?.name ?: "我的门店",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (canSwitch) {
                            Row(
                                Modifier
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("切换", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                Text("›", fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        } else {
                            Text(
                                "已锁定",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(7.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(7.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Text(
                        if (canSwitch) "价格按当前门店的历史送货价带出 · 点上方卡片可换门店"
                        else "价格按本店历史送货价带出",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
            } else {
            Button(
                onClick = { showCustomer = true },
                modifier = Modifier.weight(1f).height(42.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Text(vm.selectedCustomer?.name ?: (if (isReturn) "选择退货客户" else "选择客户"), maxLines = 1, fontSize = 14.sp)
            }
            }
            // 表格/图片批量录入只对销售单有意义，退货单必须是手工确认的明细。
            // 🔴 客户账号连这两种入口都不给（会暴露全店商品/门店维度）
            if (!isReturn && !SessionStore.isCustomer) {
            Spacer(Modifier.width(4.dp))
            Box {
                OutlinedButton(
                    onClick = { moreMenu = true },
                    modifier = Modifier.height(42.dp).width(44.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (vm.ocrProcessing || vm.downloadingTemplate) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.MoreVert, "更多录入方式", Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("下载开单模板") },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp)) },
                        enabled = !vm.downloadingTemplate && !vm.ocrProcessing,
                        onClick = { moreMenu = false; vm.downloadTemplate(ctx) }
                    )
                    DropdownMenuItem(
                        text = { Text("导入表格开单") },
                        leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(18.dp)) },
                        enabled = !vm.ocrProcessing,
                        onClick = { moreMenu = false; launchTablePicker() }
                    )
                    DropdownMenuItem(
                        text = { Text("图片开单") },
                        leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp)) },
                        enabled = !vm.ocrProcessing,
                        onClick = {
                            moreMenu = false
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                }
            }
            }
        }

        // 商品搜索（本地模糊过滤）+ 可折叠列表：默认不占地方，一输入就展开
        // 🔴 2026-10-20 历史优先：不搜词时，把该店**有历史成交价**的商品排前面。
        //   依据是 priceMap（= customer_prices，那张表就是这个门店的「记忆」）。
        //   手机版本来就是全量列表（点「浏览」展开），不存在网页版「默认只显示 8 项」的问题，
        //   所以这里只需要重排 + 给一句提示，不需要「展开全部」按钮。
        val hasHistory = vm.priceMap.isNotEmpty()
        val shownProducts = remember(vm.products, productQuery, hasHistory) {
            val hit = Fuzzy.filter(vm.products, productQuery) { listOf(it.name, it.barcode, it.spec, it.unit) }
            if (productQuery.isNotBlank() || !hasHistory) {
                hit
            } else {
                val (withPrice, withoutPrice) = hit.partition { vm.hasStorePrice(it.id) }
                withPrice + withoutPrice
            }
        }
        val histCount = remember(vm.products, hasHistory) {
            if (!hasHistory) 0 else vm.products.count { vm.hasStorePrice(it.id) }
        }
        val listOpen = productQuery.isNotBlank() || browseProducts
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1f).height(40.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (productQuery.isEmpty()) {
                    Text(
                        if (isReturn) "搜退货商品 / 条码 / 规格（空格拆多关键词）"
                        else "搜商品名 / 条码 / 规格（空格拆多关键词）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = productQuery,
                    onValueChange = { productQuery = it; if (it.isNotBlank()) browseProducts = false },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (productQuery.isBlank()) {
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { browseProducts = !browseProducts },
                    modifier = Modifier.height(40.dp).width(54.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if (browseProducts) "收起" else "浏览", fontSize = 12.sp) }
            }
        }
        if (listOpen) {
            // 历史优先的提示只在「没搜词 + 真的有历史价」时出现 —— 否则是句废话
            if (productQuery.isBlank() && histCount > 0) {
                Text(
                    "常买的前 ${minOf(histCount, 12)} 项已按本店历史成交价排在最前（该店有历史价 $histCount 项 / 全部 ${vm.products.size} 项）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (productQuery.isNotBlank()) "模糊匹配 ${shownProducts.size} 项，按相关度排序"
                else "全部商品 ${shownProducts.size} 项",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).padding(horizontal = 8.dp)
            ) {
                items(shownProducts) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.addToCart(p) }
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HighlightText(
                            p.name, productQuery,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(fmtMoney(p.price), style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { vm.addToCart(p) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Add, "添加", Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        // 购物车：每个商品只占一行（名称/单价×数量=小计 + 步进 + 删除），点名称可改单价
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isEditing) "正在修改的商品 ${vm.cart.size} 项"
                else if (isReturn) "退货单 ${vm.cart.size} 项"
                else "购物车 ${vm.cart.size} 项",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (vm.cart.isNotEmpty()) {
                TextButton(
                    onClick = { vm.clearCart() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("清空", fontSize = 12.sp) }
            }
        }
        if (vm.cart.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isReturn) "还没有退货商品\n在上面搜索框输入商品名或条码，点 + 加入退货单"
                    else "还没有商品\n在上面搜索框输入商品名或条码，点 + 加入购物车",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            itemsIndexed(vm.cart, key = { _, l -> l.product.id }) { idx, line: CartLine ->
                // 数量用本地文本态：允许删空、允许中间态，绝不在输入过程中改写用户输入
                var qtyText by remember(line.product.id) { mutableStateOf(fmtQty(line.qty)) }
                var qtyFocused by remember(line.product.id) { mutableStateOf(false) }
                // 仅「未聚焦」时跟随外部变化（±按钮、明细弹窗改完）；正在输入时绝不打断，
                // 否则输入 0 会被 setQty 的最小值(1)回写、末尾字符删不掉。
                LaunchedEffect(line.qty, qtyFocused) {
                    if (!qtyFocused && qtyText.toDoubleOrNull() != line.qty) qtyText = fmtQty(line.qty)
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 🔴 2026-10-20 客户账号：不点开明细弹窗（那里面能改单价）。
                        //    数量的加减在行尾有独立按钮，不受影响 —— 客户该能改数量。
                        //    服务端本来就强制按 customer_prices 取价，这里只是不给「改了没反应」的假入口。
                        Column(
                            Modifier.weight(1f)
                                .clickable(enabled = !SessionStore.isCustomer) { detailIdx = idx }
                        ) {

                            Text(
                                line.product.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            // 有折扣的行要把「折前→折后」显式摆出来，避免店员看不懂钱怎么算的
                            Text(
                                buildString {
                                    append(fmtMoney(line.price))
                                    if (line.discount != 1.0) append(" ×${fmtDisc(line.discount)}=${fmtMoney(line.unitPrice())}")
                                    append(" × ${fmtQty(line.qty)} = ${fmtMoney(line.subtotal())}")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (line.discount != 1.0) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            // 2026-10-17：经理反馈「单品设置价格后没显示优惠金额」。
                            // 上面的折前→折后要人自己算，这里直接把「省了多少」印出来。
                            // 只在真有让利时显示，原价行不写免得误导。
                            if (line.saveAmount() > 0.005) {
                                Text(
                                    "省 ${fmtMoney(line.saveAmount())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1
                                )
                            }
                        }
                        // 单品折扣：有的商品有、有的没有，逐行独立
                        // 🔴 2026-10-20 客户账号：只读展示（服务端已强制取价，见 ItemDiscountBox 注释）
                        ItemDiscountBox(
                            key = line.product.id,
                            value = line.discount,
                            onChange = { d -> vm.setItemDiscount(idx, d) },
                            readBack = { vm.cart.getOrNull(idx)?.discount },
                            readOnly = SessionStore.isCustomer
                        )
                        IconButton(
                            onClick = {
                                vm.changeQty(idx, -1.0)
                                // 步进后立刻回读实际数量（避免与正在编辑的文本不一致）
                                qtyText = fmtQty(vm.cart.getOrNull(idx)?.qty ?: line.qty)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Remove, "减少", Modifier.size(18.dp))
                        }
                        Box(
                            Modifier.width(56.dp).height(34.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicTextField(
                                value = qtyText,
                                onValueChange = { s ->
                                    // 只收数字；允许留空（否则末尾的 0 删不掉）
                                    if (s.isEmpty() || s.matches(Regex("^\\d{0,7}(\\.\\d{0,3})?$"))) {
                                        qtyText = s
                                        s.toDoubleOrNull()?.let { q -> vm.setQty(idx, q) }
                                    }
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                                    qtyFocused = f.isFocused
                                    // 失焦才规整：空/非法回到实际数量；"02" -> "2"
                                    if (!f.isFocused) qtyText = fmtQty(line.qty)
                                }
                            )
                        }
                        IconButton(
                            onClick = {
                                vm.changeQty(idx, 1.0)
                                qtyText = fmtQty(vm.cart.getOrNull(idx)?.qty ?: line.qty)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Add, "增加", Modifier.size(18.dp))
                        }
                        IconButton(onClick = { vm.removeAt(idx) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // 优惠明细条（2026-10-17）：把账摊开让店员能自己验算
        //   折前合计 − 单品优惠 − 整单优惠 = 优惠合计，且 折前合计 − 优惠合计 = 应收
        // 🔴 只在真有让利时占位；没有折扣就完全不显示，免得平白多一条。
        // 放底部固定条「上方」而不是里面 —— 里面已经塞了折扣框+合计+提交，塞不下会挤变形。
        if (vm.totalSave() > 0.005) {
            Surface(tonalElevation = 1.dp) {
                Text(
                    buildString {
                        append("折前 ${fmtMoney(vm.cartTotalBefore())}")
                        if (vm.itemSave() > 0.005) append(" · 单品优惠 -${fmtMoney(vm.itemSave())}")
                        if (vm.orderSave() > 0.005) append(" · 整单优惠 -${fmtMoney(vm.orderSave())}")
                        append(" · 优惠合计 -${fmtMoney(vm.totalSave())}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 🔴 2026-10-20 客户账号：明说折扣/单价是只读的，省得客户去点灰框。
        //    数量照常可改（加减按钮和数字框都在）。
        if (SessionStore.isCustomer) {
            Text(
                "单价与折扣按本店历史价带出，不能修改；数量可改",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            )
        }

        // 底部固定条：折扣 + 合计/应收 + 提交（始终可见，不用滑到底）
        Surface(tonalElevation = 2.dp, shadowElevation = 8.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("折扣", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                    // 🔴 2026-10-20 客户账号：整单折扣只读展示。
                    //   服务端对客户强制 discount=1，前端这里不给输入框（避免「改了金额不变」的困惑）。
                    if (SessionStore.isCustomer) {
                        Box(
                            Modifier.width(58.dp).height(32.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                vm.discountInput,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                    Box(
                        Modifier.width(58.dp).height(32.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicTextField(
                            value = vm.discountInput,
                            onValueChange = { s ->
                                if (s.isEmpty() || s.matches(Regex("^\\d{0,2}(\\.\\d{0,2})?$"))) vm.discountInput = s
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (isReturn) "合计退货 ${fmtMoney(vm.cartTotal())}" else "合计 ${fmtMoney(vm.cartTotal())}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (isReturn) "应退 ${fmtMoney(vm.payable())}" else "应收 ${fmtMoney(vm.payable())}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = {
                        // 先抓住单号：submit() 成功后会清掉 editingOrderNo
                        val editingNo = vm.editingOrderNo ?: ""
                        scope.launch {
                            // 提交失败必须明确报出来：以前静默吞掉 500，看着像「点了没反应」
                            if (!vm.submit()) {
                                toast(ctx, "提交失败：" + (vm.error ?: "请重试"), long = true)
                            } else if (isEditing) {
                                // 编辑保存成功：购物车已被清空（再点会误开新单），直接回订单页
                                toast(ctx, "已保存修改 $editingNo，单号不变")
                                showSuccess = false
                                onEditingDone()
                            } else if (vm.offlineNotice != null) {
                                // 🔴 v5.7.4 离线保存成功：明确告诉用户「已存本地、联网自动传」
                                toast(ctx, vm.offlineNotice!!, long = true)
                                vm.clearOfflineNotice()
                                showSuccess = true
                            } else {
                                showSuccess = true
                            }
                        }
                    },
                    enabled = !vm.submitting && vm.selectedCustomer != null && vm.cart.isNotEmpty(),
                    modifier = Modifier.height(44.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp)
                ) {
                    if (vm.submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(
                        when {
                            isEditing -> "保存修改"
                            isReturn -> "提交退货单"
                            else -> "提交"
                        },
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/** 销售单 / 退货单 模式切换标签 */
@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier.height(42.dp)) { Text(label, fontSize = 14.sp) }
    else OutlinedButton(onClick = onClick, modifier = modifier.height(42.dp)) { Text(label, fontSize = 14.sp) }
}

/** 购物车单行明细：改数量 / 改单价 / 删除（点购物车行的名称区域打开） */
@Composable
private fun CartLineDetailDialog(
    line: CartLine,
    onQty: (Double) -> Unit,
    onPrice: (Double) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var qtyText by remember { mutableStateOf(fmtQty(line.qty)) }
    var priceText by remember { mutableStateOf(fmtQty(line.price)) }
    val unit = line.product.unit?.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.product.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("数量" + (unit?.let { "（$it）" } ?: ""), style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { s ->
                        if (s.isEmpty() || s.matches(Regex("^\\d{0,7}(\\.\\d{0,3})?$"))) {
                            qtyText = s
                            s.toDoubleOrNull()?.let(onQty)
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("单价", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { s ->
                        if (s.isEmpty() || s.matches(Regex("^\\d{0,9}(\\.\\d{0,2})?$"))) {
                            priceText = s
                            s.toDoubleOrNull()?.let(onPrice)
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                // 🔴 2026-10-18：原来写 `line.qty * line.price`（折前×数量），
                // 与购物车行的 `line.subtotal()`（折后）口径不一致，同一行两个金额对不上，
                // 经理看着以为优惠没算。这里统一用 subtotal()，并在有单品折扣时
                // 把折前→折后和「省了多少」一起摆出来。
                Column {
                    Text(
                        "小计 ${fmtMoney(line.subtotal())}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (line.saveAmount() > 0.005) {
                        Text(
                            "门店价 ${fmtMoney(line.price * line.qty)} − 折扣 ${fmtDisc(line.discount)} = 省 ${fmtMoney(line.saveAmount())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = onRemove) {
                Text("删除此商品", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun CustomerPicker(
    customers: List<com.heping.erpnative.data.Customer>,
    onPick: (com.heping.erpnative.data.Customer) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(q, customers) {
        Fuzzy.filter(customers, q) { listOf(it.name, it.contact, it.phone, it.address, it.remark) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择客户") },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("模糊搜索 名称/电话/联系人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (q.isNotBlank()) Text("匹配 ${filtered.size} 家", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered) { c ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(10.dp)) {
                            Column {
                                HighlightText(c.name, q, style = MaterialTheme.typography.bodyLarge)
                                if (!c.phone.isNullOrEmpty()) HighlightText(c.phone, q, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
/**
 * 2026-10-19 客户下单员多门店：只列他**被授权**的门店。
 * 服务端也会再校验一次，这里少列只是界面干净，不构成安全边界。
 */
private fun StorePickerDialog(
    stores: List<com.heping.erpnative.data.Customer>,
    currentId: Int,
    onPick: (com.heping.erpnative.data.Customer) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下单门店") },
        text = {
            Column {
                Text(
                    "切换后商品价格会按该门店的历史送货价重新带出，已录入的商品会清空。",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(stores) { c ->
                        // 🔴 2026-10-20 卡片化：原来靠店名前拼一个 "✅ " emoji 标当前项。
                        //    emoji 字形在各系统/各字号下都不一样，对不齐也不够醒目 →
                        //    改成主色描边 + 右上角「当前」文字 pill（跟网页版 .store-switch-item 一致）。
                        val on = c.id == currentId
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                            else MaterialTheme.colorScheme.surface)
                                .border(
                                    if (on) 1.5.dp else 1.dp,
                                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { if (!on) onPick(c) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                if (!c.phone.isNullOrEmpty()) {
                                    Text(c.phone!!, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (on) {
                                Text(
                                    "当前",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            } else {
                                Text("选择 ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ReturnPickerDialog(
    orders: List<com.heping.erpnative.data.Order>,
    onPick: (com.heping.erpnative.data.Order) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择退货订单") },
        text = {
            if (orders.isEmpty()) {
                Text("近 30 天暂无可退订单")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(orders) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(o) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(o.orderNo, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${o.customerName ?: "散客"}  ·  ${shortDate(o.orderDate)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(fmtMoney(o.payable ?: o.total), style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}


// ===================== 单品折扣 =====================
/**
 * 折扣显示：0.9 -> "0.9"；1.0 -> "1"；0.85 -> "0.85"
 * 用 Locale.US 固定小数点写法，避免部分机型 locale 把 "." 变成 ","。
 */
private fun fmtDisc(d: Double): String =
    if (d % 1.0 == 0.0) String.format(Locale.US, "%d", d.toLong())
    else String.format(Locale.US, "%.2f", d).trimEnd('0')

/**
 * 购物车每一行的「单品折扣」输入框。
 * 打折的行边框变主色，一眼能看出哪些行有折扣、哪些没有。
 *
 * 输入体验与数量框保持一致：允许留空、允许 "0." 这种中间态，
 * 失焦才回写规整值 —— 不然删末尾字符会被立刻回写成原值，删不动。
 *
 * 🔴 readOnly=true（2026-10-20 客户账号）：改成纯展示的静态 Text。
 *   服务端 `_forceStorePrice` 已经强制按 customer_prices 取价，框里改什么都不影响金额 ——
 *   但让客户能改却发现「金额没变」会以为系统坏了，所以直接不给输入框。
 *   用灰底 + 虚线感（低对比边框）表达「看得到、碰不到」。
 */
@Composable
private fun ItemDiscountBox(
    key: Any?,
    value: Double,
    onChange: (Double) -> Unit,
    readBack: () -> Double?,
    readOnly: Boolean = false
) {
    val hasDiscount = value != 1.0
    if (readOnly) {
        Box(
            Modifier
                .width(48.dp)
                .height(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                fmtDisc(value),
                fontSize = 14.sp,
                color = if (hasDiscount) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    var text by remember(key) { mutableStateOf(fmtDisc(value)) }
    var focused by remember(key) { mutableStateOf(false) }
    // 未聚焦时跟随外部变化（批量设折扣 / 明细弹窗改完）
    LaunchedEffect(value, focused) {
        if (!focused) {
            val cur = readBack() ?: value
            if (text.toDoubleOrNull() != cur) text = fmtDisc(cur)
        }
    }
    Box(
        Modifier
            .width(48.dp)
            .height(34.dp)
            .border(
                1.dp,
                if (hasDiscount) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = text,
            onValueChange = { s ->
                if (s.isEmpty() || s.matches(Regex("^\\d{0,1}(\\.\\d{0,2})?$"))) {
                    text = s
                    s.toDoubleOrNull()?.let { d -> if (d > 0) onChange(d) }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Center,
                color = if (hasDiscount) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                focused = f.isFocused
                if (!f.isFocused) text = fmtDisc(readBack() ?: value)
            }
        )
    }
}
