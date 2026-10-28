@file:OptIn(ExperimentalMaterial3Api::class)

package com.heping.erpnative.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.device.BtPrinter
import com.heping.erpnative.device.EscPos
import com.heping.erpnative.device.PrintResult
import com.heping.erpnative.ui.components.*
import com.heping.erpnative.util.Fuzzy
import com.heping.erpnative.vm.OrdersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OrdersScreen(
    vm: OrdersViewModel,
    // 点「编辑」交回给 MainScreen：装车后跳到开单页，可自由增删改全部商品
    onEditOrder: (Int) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pickerTarget by remember { mutableStateOf<Int?>(null) }
    var showReturns by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<com.heping.erpnative.data.Order?>(null) }
    // 🔴 2026-10-28：左右滑「同时只展开一行」——滑开新行时旧行自动收回，
    //    避免多行同时露出按钮导致「关不掉」的错觉（经理：滑回正常显示很难）。
    var expandedOrderId by remember { mutableStateOf<Int?>(null) }
    val canDelete = SessionStore.can("order.delete")
    val canEdit = SessionStore.can("order.edit")
    val dateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())

    LaunchedEffect(Unit) { vm.loadOrders() }

    if (pickerTarget != null) {
        DatePickerDialog(
            onDismissRequest = { pickerTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val ymd = ymdFromMillis(millis)
                        if (pickerTarget == 0) vm.from = ymd else vm.to = ymd
                        vm.loadOrders()
                    }
                    pickerTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickerTarget = null }) { Text("取消") } }
        ) { DatePicker(state = dateState) }
    }

    // 删除确认（滑动露出「删除」后触发）
    if (confirmDelete != null) {
        val o = confirmDelete!!
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除订单？") },
            text = {
                Column {
                    Text("单号：${o.orderNo}")
                    Text("客户：${o.customerName ?: "散客"}")
                    Text("金额：${fmtMoney(o.payable ?: o.total)}")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "删除后不可恢复，库存会自动回补。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val (ok, msg) = vm.deleteOrder(o.id)
                        toast(ctx, if (ok) "已删除 ${o.orderNo}" else "删除失败：${msg ?: "未知错误"}")
                        confirmDelete = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }

    if (vm.detail != null) {
        OrderDetailDialog(
            detail = vm.detail!!,
            canReturn = SessionStore.can("order.return") && (vm.detail!!.order.status ?: "") != "returned",
            canEdit = canEdit,
            onClose = { vm.clearDetail() },
            onEditOrder = { id -> vm.clearDetail(); onEditOrder(id) },
            onReturnItems = { items ->
                scope.launch {
                    val srcId = vm.detail!!.order.id
                    val r = vm.submitReturnItems(srcId, items)
                    if (r != null) {
                        vm.clearDetail()
                        try {
                            val d = ErpClient.api.orderDetail(r.orderId)
                            val res = printOrder(ctx, d)
                            toast(ctx, "退货单 ${r.orderNo} ${res.message}")
                        } catch (_: Exception) {
                            toast(ctx, "退货单已生成：${r.orderNo}")
                        }
                    }
                }
            },
            returning = vm.returning
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("订单")
        OfflineBanner(vm.offline)

        // 时间区间选择
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { pickerTarget = 0 }, modifier = Modifier.weight(1f)) {
                Text("开始：${vm.from ?: "全部"}")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { pickerTarget = 1 }, modifier = Modifier.weight(1f)) {
                Text("结束：${vm.to ?: "全部"}")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickChip("今天") { vm.setToday() }
            Spacer(Modifier.width(6.dp))
            QuickChip("近7天") { vm.setLast7() }
            Spacer(Modifier.width(6.dp))
            QuickChip("本月") { vm.setMonth() }
            Spacer(Modifier.width(6.dp))
            QuickChip("全部") { vm.setAll() }
        }
        // 类型筛选：全部 / 仅退货
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickChip("全部") { showReturns = false }
            Spacer(Modifier.width(6.dp))
            QuickChip("仅退货") { showReturns = true }
        }

        // 关键词模糊搜索：订单号 / 门店 / 备注（空格拆多关键词，按相关度排序）
        var keyword by remember { mutableStateOf("") }
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = { Text("模糊搜索：订单号 / 门店 / 备注（空格拆多关键词）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        val byType = if (showReturns) vm.orders.filter { (it.status ?: "") == "returned" } else vm.orders
        val display = remember(byType, keyword) {
            Fuzzy.filter(byType, keyword) { listOf(it.orderNo, it.customerName, it.remark) }
        }
        if (keyword.isNotBlank()) {
            Text(
                "模糊匹配 ${display.size} 张订单",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
            )
        }
        if (canDelete) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { vm.toggleMulti() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(if (vm.multi) "退出多选" else "多选", style = MaterialTheme.typography.labelSmall)
                }
                if (vm.multi) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { vm.selectAll(display.map { it.id }) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Text("全选/取消", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${vm.selected.size}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val (del, skip, err) = vm.deleteSelected()
                                if (err != null) toast(ctx, "删除失败：$err")
                                else toast(ctx, "已删除 $del 张" + if (skip > 0) "，跳过 $skip 张（如已退货）" else "")
                            }
                        },
                        enabled = vm.selected.isNotEmpty() && !vm.deleting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                    ) { Text("删除所选", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (display.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (keyword.isNotBlank()) "无匹配订单（试试少输几个字，或用空格拆关键词）"
                    else if (showReturns) "暂无退货单" else "暂无订单"
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(display, key = { it.id }) { o ->
                    if (vm.multi) {
                        SelectOrderRow(
                            orderNo = o.orderNo,
                            customer = o.customerName ?: "散客",
                            date = shortDate(o.orderDate),
                            amount = fmtMoney(o.payable ?: o.total),
                            returned = (o.status ?: "") == "returned",
                            checked = vm.selected.contains(o.id),
                            onToggle = { vm.toggleSelect(o.id) }
                        )
                    } else {
                    // 右滑：复制 / 打印；左滑：删除
                    SwipeActionsRow(
                        // 🔴 按钮宽度从 76 收到 56，区域宽度同步收窄（经理 10/15：空白太多）
                        startActionsWidth = if (canEdit && o.status != "returned") 170.dp else 114.dp,
                        endActionsWidth = 56.dp,
                        startActions = {
                            if (canEdit && o.status != "returned") {
                                SwipeAction(
                                    icon = Icons.Filled.Edit,
                                    text = "编辑",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    width = 56.dp,
                                    onClick = { onEditOrder(o.id) }
                                )
                            }
                            SwipeAction(
                                icon = Icons.Filled.ContentCopy,
                                text = "复制",
                                tint = MaterialTheme.colorScheme.tertiary,
                                width = 56.dp,
                                onClick = {
                                    scope.launch {
                                        val (ok, msg) = vm.copyOrder(o.id)
                                        toast(ctx, if (ok) "已复制为新订单：$msg" else "复制失败：${msg ?: "未知错误"}")
                                    }
                                }
                            )
                            SwipeAction(
                                icon = Icons.Filled.Print,
                                text = "打印",
                                tint = MaterialTheme.colorScheme.primary,
                                width = 56.dp,
                                onClick = {
                                    scope.launch {
                                        val res = printOrderById(ctx, o.id)
                                        toast(ctx, res.message)
                                    }
                                }
                            )
                        },
                        endActions = {
                            if (canDelete) {
                                SwipeAction(
                                    icon = Icons.Filled.Delete,
                                    text = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    width = 56.dp,
                                    onClick = { confirmDelete = o }
                                )
                            }
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                        // 🔴 互斥：别的行展开时本行强制收回
                        forceCollapsed = (expandedOrderId != null && expandedOrderId != o.id),
                        onExpandedChange = { exp ->
                            expandedOrderId = when {
                                exp -> o.id
                                expandedOrderId == o.id -> null
                                else -> expandedOrderId
                            }
                        },
                        onClick = { vm.loadDetail(o.id) }
                    ) {
                        // 🔴 2026-10-20 客户自助单：紫色左侧竖条 + badge，
                        //    跟商家代开的单一眼分开（判据见 CustOrderTag.kt）
                        val fromCust = o.isCustomerSelfOrder()
                        Card(
                            Modifier.fillMaxWidth().then(custCardBar(fromCust)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = if (fromCust)
                                androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = Color(0xFF8A4FD3).copy(alpha = 0.05f)
                                )
                            else androidx.compose.material3.CardDefaults.cardColors()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        HighlightText(o.orderNo, keyword, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        if (fromCust) {
                                            Spacer(Modifier.width(2.dp))
                                            CustOrderBadge(o.placedByName)
                                        }
                                    }
                                    HighlightText(
                                        "${o.customerName ?: "散客"}  ·  ${shortDate(o.orderDate)}",
                                        keyword,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(fmtMoney(o.payable ?: o.total), style = MaterialTheme.typography.bodyLarge)
                                    if ((o.status ?: "") == "returned")
                                        Text("已退货", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    // 🔴 结算标签由 settled 推导（不看 status）：已结 / 部分收 / 未收
                                    else if (SessionStore.can("ar.view")) SettleTag(o)
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/** 打印订单小票（用已配对的蓝牙打印机），自动连接上次打印机 */
suspend fun printOrder(ctx: android.content.Context, detail: com.heping.erpnative.data.OrderDetail): PrintResult {
    val o = detail.order
    val isReturn = (o.status ?: "") == "returned" || o.total < 0
    val lines = detail.items.map {
        // 🔴 2026-10-18：改成管家婆式**双价列**（左边原价、右边特价）。
        //    · origPrice = 折前原价（服务端 order_items.orig_price）→ 小票「原价」列
        //    · price     = 折后成交单价                          → 小票「特价」列
        //    老单的 orig_price 可能为 NULL，必须用 (it.origPrice ?: 0.0).takeIf{it>0} 兜回 price，
        //    否则「原价」列会印出 0.00，比没有原价更糟。
        // 📌 底部「合计折前金额 / 折扣优惠金额」由 beforeSum 单独算，与这两列不冲突。
        val orig = (it.origPrice ?: 0.0).takeIf { v -> v > 0 } ?: it.price
        EscPos.Line(
            name = it.productName,
            qty = it.qty,
            price = it.price,
            unit = it.unit,
            // 条码只能取 barcode：spec 装的是规格文字（106g/180ml），不是条码。
            // 老单的 barcode 由服务端 GET /api/orders/:id 用商品表回填。
            barcode = it.barcode?.trim()?.takeIf { s -> s.isNotEmpty() },
            origPrice = orig
        )
    }
    // 折前合计同样由明细按 orig_price 重算，不能用 orders.total
    // （total = Σ 折后单价×数量，天然不含单品折扣那部分让利）。
    val beforeSum = lines.sumOf { it.qty * it.origOrPrice() }
    val bytes = EscPos.receipt(
        shop = SessionStore.shopName.ifEmpty { "销售小票" },
        orderNo = o.orderNo,
        customer = o.customerName ?: "散客",
        items = lines,
        beforeDiscount = beforeSum,
        payable = o.payable ?: o.total,
        // 退货单页脚不能写「谢谢惠顾」，否则客户看到退货小票还写欢迎光临会起争议
        footer = if (isReturn) "退货单已入账，感谢配合" else "谢谢惠顾，欢迎再次光临",
        docTitle = if (isReturn) "销售退货" else "销售订单",
        dateTime = (o.orderDate ?: o.created_at ?: "").replace("T", " ").take(16),
        handler = SessionStore.receiptHandler.ifBlank { SessionStore.displayName },
        warehouse = SessionStore.receiptWarehouse,
        isReturn = isReturn
    )
    return withContext(Dispatchers.IO) {
        if (!BtPrinter.isOn()) return@withContext PrintResult.BT_OFF
        val mac = BtPrinter.getLastMac(ctx) ?: return@withContext PrintResult.NO_PRINTER
        // 🔴 同SaleViewModel.printLast：先静默重连，不要每次都弹系统配对框
        if (!BtPrinter.ensureConnected(ctx)) return@withContext PrintResult.CONNECT_FAIL
        if (BtPrinter.print(bytes)) PrintResult.SUCCESS else PrintResult.SEND_FAIL
    }
}

/** 按订单 id 打印：先拉详情再走打印流程（列表里直接滑出「打印」用） */
suspend fun printOrderById(ctx: android.content.Context, id: Int): PrintResult {
    return try {
        val d = ErpClient.api.orderDetail(id)
        printOrder(ctx, d)
    } catch (_: Exception) { PrintResult.FETCH_FAIL }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun OrderDetailDialog(
    detail: com.heping.erpnative.data.OrderDetail,
    canReturn: Boolean,
    canEdit: Boolean = false,
    onClose: () -> Unit,
    onEditOrder: (Int) -> Unit = {},
    onReturnItems: (List<com.heping.erpnative.data.CreateOrderItem>) -> Unit,
    returning: Boolean
) {
    val o = detail.order
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }
    var showReturnEdit by remember { mutableStateOf(false) }
    // 订单预览 / 保存为图片（发电子单给客户）
    var previewBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<android.net.Uri?>(null) }

    fun buildPreview(): android.graphics.Bitmap {
        val dt = (o.orderDate ?: o.created_at ?: "").replace("T", " ").take(16)
        val isRet = o.status == "returned" || o.total < 0
        return ReceiptImage.render(
            detail = detail,
            shop = SessionStore.shopName.ifBlank { "销售小票" },
            docTitle = if (isRet) "销售退货" else "销售订单",
            dateTime = dt,
            handler = SessionStore.receiptHandler.ifBlank { SessionStore.displayName },
            warehouse = SessionStore.receiptWarehouse,
            footer = if (isRet) "退货单已入账，感谢配合" else "谢谢惠顾，欢迎再次光临"
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.orderNo)
                // 🔴 详情页也标出来 —— 列表扫过去忘了，再点开必须还能确认这张单的来源
                if (o.isCustomerSelfOrder()) {
                    Spacer(Modifier.width(4.dp))
                    CustOrderBadge(o.placedByName)
                }
            }
        },
        text = {
            Column {
                Text("客户：${o.customerName ?: "散客"}")
                if (o.isCustomerSelfOrder() && !o.placedByName.isNullOrBlank())
                    Text("下单账号：${o.placedByName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("日期：${o.orderDate ?: ""}")
                if ((o.status ?: "") == "returned") Text("状态：已退货", color = MaterialTheme.colorScheme.error)
                // 🔴 结算信息（应收 / 已收 / 未结）——只看 settled，绝不回写订单金额
                if ((o.status ?: "") != "returned" && SessionStore.can("ar.view")) {
                    Spacer(Modifier.height(6.dp))
                    SettleDetailRow(o)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                // 🔥 2026-10-15：高度从 260 压到 200，给底下的按钮条腾地方（按钮四行时被挡）
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(detail.items) { it ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(it.productName, style = MaterialTheme.typography.bodyMedium)
                                Text("${it.qty} x ${fmtMoney(it.price)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(fmtMoney(it.subtotal), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("合计"); Text(fmtMoney(o.total))
                }
                if ((o.discount ?: 1.0) != 1.0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("折扣"); Text("${(o.discount ?: 1.0)}")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("应收", fontWeight = FontWeight.Bold); Text(fmtMoney(o.payable ?: o.total), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                // 🔴 经理 2026-10-15 反馈：原来四行按钮把预览挤没了。
                // 现在压成两行紧凑按钮条：第一行主操作，第二行次要操作。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                printing = true
                                try {
                                    val res = printOrder(ctx, detail)
                                    toast(ctx, res.message)
                                } finally { printing = false }
                            }
                        },
                        enabled = !printing,
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        if (printing) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        else Text("打印小票", fontSize = 12.sp, maxLines = 1)
                    }
                    // 订单预览：显示打印效果（只含条码数字），可直接发客户
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                previewBmp = withContext(Dispatchers.Default) { buildPreview() }
                                showPreview = true
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("订单预览", fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val bmp = withContext(Dispatchers.Default) { buildPreview() }
                                val name = "订单_" + o.orderNo.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "") + ".png"
                                val uri = withContext(Dispatchers.IO) {
                                    ReceiptImage.saveToGallery(ctx, bmp, name)
                                }
                                if (uri != null) {
                                    // 🔴 经理 2026-10-15 反馈：保存成功后不要再自动弹预览层。
                                    //    「存为图片」就是存到相册，弹一个加载不出来的预览纯属多余，
                                    //    还会挡住列表让人以为卡死。要看效果请点旁边的「订单预览」。
                                    toast(ctx, "已保存到相册：简单ERP/" + name)
                                } else {
                                    toast(ctx, "保存失败：无法写入相册")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("存为图片", fontSize = 12.sp, maxLines = 1)
                    }
                }
                // 第二行：修改订单（已退货的单不给改）。没有编辑权限时不占行。
                if (canEdit && o.status != "returned") {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onClose(); onEditOrder(o.id) },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("修改订单（可增删商品）", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            if (canReturn) {
                Button(onClick = { showReturnEdit = true }, enabled = !returning) {
                    if (returning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("退货")
                }
            } else TextButton(onClick = onClose) { Text("关闭") }
        },
        dismissButton = if (canReturn) { { TextButton(onClick = onClose) { Text("关闭") } } } else null
    )

    if (showPreview) {
        ReceiptPreviewDialog(
            bmp = previewBmp,
            onSave = {
                scope.launch {
                    val b = previewBmp ?: return@launch
                    val name = "订单_" + o.orderNo.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "") + ".png"
                    val uri = withContext(Dispatchers.IO) { ReceiptImage.saveToGallery(ctx, b, name) }
                    if (uri != null) {
                        savedUri = uri
                        toast(ctx, "已保存到相册：简单ERP/" + name)
                    } else toast(ctx, "保存失败：无法写入相册")
                }
            },
            onShare = savedUri?.let { uri ->
                {
                    ReceiptImage.share(
                        ctx, uri,
                        "订单_" + o.orderNo.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "") + ".png"
                    )
                }
            },
            onDismiss = { showPreview = false }
        )
    }

    if (showReturnEdit) {
        ReturnEditDialog(
            detail = detail,
            submitting = returning,
            onClose = { showReturnEdit = false },
            onSubmit = { items -> showReturnEdit = false; onReturnItems(items) }
        )
    }
}

private data class ReturnLine(val item: com.heping.erpnative.data.OrderItem, var qty: Double)

/** 退货编辑：像开单一样逐项调整退货数量/删除条目，默认整单；确认后生成退货单并打印 */
@Composable
private fun ReturnEditDialog(
    detail: com.heping.erpnative.data.OrderDetail,
    submitting: Boolean,
    onClose: () -> Unit,
    onSubmit: (List<com.heping.erpnative.data.CreateOrderItem>) -> Unit
) {
    val o = detail.order
    var lines by remember { mutableStateOf(detail.items.map { ReturnLine(it, it.qty) }) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("退货单 · ${o.orderNo}") },
        text = {
            Column {
                Text("客户：${o.customerName ?: "散客"}", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                Text("逐项调整退货数量（0 = 不退该商品），确认后生成退货单并打印。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    itemsIndexed(lines) { idx, line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.item.productName, style = MaterialTheme.typography.bodyMedium)
                                Text("原 ${fmtNum(line.item.qty)} × ${fmtMoney(line.item.price)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            OutlinedTextField(
                                value = if (line.qty == 0.0) "" else fmtNum(line.qty),
                                onValueChange = { v ->
                                    val d = v.toDoubleOrNull() ?: 0.0
                                    lines = lines.toMutableList().also { it[idx] = line.copy(qty = d.coerceAtLeast(0.0)) }
                                },
                                modifier = Modifier.width(88.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                    }
                }
                if (err != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { lines = lines.map { it.copy(qty = it.item.qty) } }) {
                    Text("整单退（恢复全部数量）")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val items = lines.filter { it.qty > 0 }.map {
                        com.heping.erpnative.data.CreateOrderItem(productId = it.item.product_id, qty = it.qty, price = it.item.price)
                    }
                    if (items.isEmpty()) { err = "请至少保留一个退货商品"; return@Button }
                    onSubmit(items)
                },
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("确认退货")
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !submitting) { Text("取消") } }
    )
}

/** 数字转短文本：整数不带小数点，小数去尾零（退货数量输入框用） */
private fun fmtNum(v: Double): String {
    val s = String.format(java.util.Locale.US, "%.2f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** 多选模式下的订单行（点击整行切换勾选） */
@Composable
private fun SelectOrderRow(
    orderNo: String, customer: String, date: String, amount: String,
    returned: Boolean, checked: Boolean, onToggle: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onToggle() },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(orderNo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text("$customer  ·  $date", style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, style = MaterialTheme.typography.bodyLarge)
                if (returned) Text("已退货", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
