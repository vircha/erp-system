package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heping.erpnative.data.Product
import com.heping.erpnative.data.PurchaseItem
import com.heping.erpnative.data.PurchaseOrder
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.data.userMessage
import com.heping.erpnative.ui.components.*
import com.heping.erpnative.vm.PurchaseViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 采购与库存（v5.6.9）
 *
 * 🔴 四个子页（与网页端「采购」页一一对应）：
 *    采购建议 = 以销定购该进多少货（建议量 = 净销 - 已入库）
 *    采购单   = 已建采购单（建单即入库，可作废回退）
 *    库存明细 = 账面 / 推导 / 差异 三口径对照
 *    库存流水 = 采购入库 + 销售出库/退货
 *
 * 🔴 采购建议量**只显示服务端给的 suggest_qty**，手机端不重算 ——
 *    网页端、服务端、手机端三处口径必须一致，否则经理会看到两个数字。
 */
private val SUB_TABS = listOf("采购建议" to "sug", "采购单" to "ord", "库存明细" to "inv", "库存流水" to "mv")

@Composable
fun PurchaseScreen(vm: PurchaseViewModel) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("sug") }
    var showNew by remember { mutableStateOf(false) }
    var detailOrder by remember { mutableStateOf<PurchaseOrder?>(null) }
    var detailItems by remember { mutableStateOf<List<PurchaseItem>>(emptyList()) }

    val canViewPurchase = SessionStore.can("purchase.view")
    val canEditPurchase = SessionStore.can("purchase.edit")
    val canViewInv = SessionStore.can("inventory.view")

    // 页签按权限过滤：采购类要 purchase.view，库存类要 inventory.view
    val tabs = SUB_TABS.filter { (label, key) ->
        when (key) {
            "sug", "ord" -> canViewPurchase
            "inv", "mv" -> canViewInv
            else -> true
        }
    }

    // 懒加载：第一次切到该页才拉
    LaunchedEffect(tab) {
        when (tab) {
            "sug" -> if (vm.suggestions.isEmpty()) vm.loadSuggestions()
            "ord" -> if (vm.orders.isEmpty()) vm.loadOrders()
            "inv" -> if (vm.inventory.isEmpty()) vm.loadInventory()
            "mv" -> if (vm.moves.isEmpty()) vm.loadMoves()
        }
    }
    // 供应商（建单时要选）
    LaunchedEffect(Unit) { if (canEditPurchase) vm.loadSuppliers() }

    if (showNew) {
        NewPurchaseDialog(
            vm = vm,
            onDismiss = { showNew = false },
            onSubmit = { ok, no ->
                if (ok) {
                    showNew = false
                    toast(ctx, "采购单 $no 已提交，库存已入库")
                } else {
                    toast(ctx, vm.error ?: "提交失败", long = true)
                }
            }
        )
    }

    detailOrder?.let { o ->
        PurchaseDetailDialog(
            order = o,
            items = detailItems,
            canVoid = canEditPurchase && o.status != "void",
            onVoid = {
                scope.launch {
                    if (vm.voidOrder(o.id)) {
                        detailOrder = null; detailItems = emptyList()
                        toast(ctx, "已作废 ${o.purchaseNo}，库存已退回")
                    } else toast(ctx, vm.error ?: "作废失败", long = true)
                }
            },
            onDismiss = { detailOrder = null; detailItems = emptyList() }
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("采购与库存")
        SubTabs(tabs, tab) { tab = it }
        ErrorText(vm.error)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                "sug" -> SuggestSection(vm, canEditPurchase) { showNew = true }
                "ord" -> OrderSection(vm, canEditPurchase) { o ->
                    scope.launch {
                        val (_, its) = vm.loadDetail(o.id)
                        detailItems = its
                        detailOrder = o
                    }
                }
                "inv" -> InventorySection(vm)
                "mv" -> MoveSection(vm)
            }
        }

        // ---- 草稿条（只在采购建议页显示）----
        if (tab == "sug" && canEditPurchase && vm.draft.isNotEmpty()) {
            DraftBar(vm) { showNew = true }
        }
    }
}

// ================= 通用小组件 =================

@Composable
private fun SubTabs(tabs: List<Pair<String, String>>, cur: String, onPick: (String) -> Unit) {
    if (tabs.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        tabs.forEach { (label, key) ->
            if (key == cur) {
                Button(
                    onClick = { onPick(key) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) { Text(label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center) }
            } else {
                OutlinedButton(
                    onClick = { onPick(key) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) { Text(label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier, neg: Boolean = false, warn: Boolean = false) {
    Column(
        modifier
            .background(
                when {
                    neg -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    warn -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            color = if (neg) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SecCard(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            } else Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** 数量文本：整数不带小数尾巴 */
private fun q(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString()
    else String.format(Locale.US, "%.2f", v)

private fun money(v: Double): String = "¥" + String.format(Locale.US, "%.2f", v)

// ================= 采购建议（v5.7.0：双列网格卡片 + 勾选批量加入，去掉每行「加入」按钮）=================
// 🔴 经理 2026-10-21 反馈：
//    ① 「采购首页的建议订单太占位置」→ 单列大卡片改**双列紧凑网格**。
//    ② 「建议订单里面的加入不知道有什么功能」→ **取消每张卡片的「加入采购单」按钮**，
//       改成「点卡片 = 勾选/取消 + 卡内直接改数量 + 底部统一「加入采购单（N）」」，语义明确。
//    勾选态存在 PurchaseViewModel.sugPicked / sugQty（只在内存，不落库）。

@Composable
private fun SuggestSection(vm: PurchaseViewModel, canEdit: Boolean, onCreate: () -> Unit) {
    if (vm.sugLoading && vm.suggestions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    vm.sugError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }

    val list = vm.suggestions
    val need = list.filter { it.suggestQty > 0.005 }
    val totalQty = need.sumOf { it.suggestQty }
    val totalAmt = need.sumOf { it.suggestQty * it.cost }

    if (list.isEmpty()) {
        CenteredMessage("暂无采购建议数据")
        return
    }

    // 🔴 双列一律 LazyVerticalGrid（❌ 不用 column-count，竖向填充会排到可视区外）
    //    屏宽 < 360dp 时降为单列，避免卡片太窄看不清数字。
    val wide = LocalConfiguration.current.screenWidthDp >= 360
    val ctx = LocalContext.current
    val cols = if (wide) 2 else 1

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatBox("需采购品种", "${need.size}", Modifier.weight(1f))
                        StatBox("建议件数", q(totalQty), Modifier.weight(1f))
                        StatBox("预估金额", money(totalAmt), Modifier.weight(1.2f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "口径：建议采购量 = 净销售数量 − 已采购入库数量（全部入库后推导库存 = 0）。点卡片勾选，卡内可改数量，底部统一加入采购单。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (need.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("当前没有需要采购的商品", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(list, key = { it.id }) { s ->
                SuggestCard(
                    s = s,
                    canEdit = canEdit,
                    picked = vm.isPicked(s.id),
                    already = vm.draft.any { it.productId == s.id },
                    pickedQty = vm.pickedQty(s.id, s.suggestQty),
                    onToggle = { vm.togglePicked(s.id) },
                    onQty = { vm.togglePicked(s.id, it) }
                )
            }
        }

        // ---- 底部常驻操作条 ----
        // 🔴 2026-10-21 经理反馈（网页端原话「选好产品要滑到下面才能找到这几个按钮，太麻烦了」）：
        //    手机端同步 —— 底栏**常驻**（不再等勾选后才出现），
        //    且把「全选 / 清空」从顶部统计卡里挪下来一起放，
        //    这样任何时候抬拇指就够得到，一个都不用滑。
        if (canEdit && need.isNotEmpty()) {
            Surface(shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (vm.pickedCount() > 0) "已选 ${vm.pickedCount()} 个品种" else "未勾选",
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (vm.pickedCount() > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // 🔴 窄屏用 weight(1f) + minWidth 0 + 单行省略，禁flex-wrap
                        OutlinedButton(
                            onClick = { vm.pickAll(need.map { it.id }) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            enabled = vm.pickedCount() < need.size
                        ) { Text("全选", fontSize = 12.sp, maxLines = 1) }
                        OutlinedButton(
                            onClick = { vm.clearPicked() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            enabled = vm.pickedCount() > 0
                        ) { Text("清空", fontSize = 12.sp, maxLines = 1) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val n = vm.addPickedToDraft()
                            if (n > 0) toast(ctx, "已加入 $n 个商品到采购单草稿")
                            else toast(ctx, vm.error ?: "请先勾选商品并填数量", long = true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 9.dp)
                    ) { Text("加入采购单", fontSize = 14.sp) }
                }
            }
        }
    }
}

/** 采购建议双列卡片 */
@Composable
private fun SuggestCard(
    s: com.heping.erpnative.data.PurchaseSuggestion,
    canEdit: Boolean,
    picked: Boolean,
    already: Boolean,
    pickedQty: Double,
    onToggle: () -> Unit,
    onQty: (Double) -> Unit
) {
    val border = if (picked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(if (picked) 2.dp else 1.dp, border),
        colors = CardDefaults.cardColors(
            containerColor = if (picked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    s.name,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                if (s.suggestQty > 0.005) {
                    Text(
                        q(s.suggestQty),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("—", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val sub = listOfNotNull(
                s.spec?.takeIf { it.isNotBlank() },
                s.unit?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            // 双列卡片空间紧，压成两行小字
            Text(
                "已销 ${q(s.soldQty)} · 入库 ${q(s.inQty)}",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
            Text(
                "账面 ${q(s.stockQty)} · 推导 ${q(s.deriveQty)}",
                fontSize = 9.sp,
                color = if (s.stockQty < 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (Math.abs(s.diff) > 0.005) {
                Text(
                    "差异 ${if (s.diff > 0) "+" else ""}${q(s.diff)}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, maxLines = 1
                )
            }
            if (canEdit && s.suggestQty > 0.005) {
                Spacer(Modifier.height(4.dp))
                // 勾选 + 数量输入（不用「加入」按钮了）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = picked, onCheckedChange = { onToggle() },
                        modifier = Modifier.size(28.dp)
                    )
                    OutlinedTextField(
                        value = if (pickedQty > 0) fmtPlain(pickedQty) else "",
                        onValueChange = { txt ->
                            val v = txt.toDoubleOrNull()
                            if (v != null) onQty(v)
                        },
                        label = { Text("数量", fontSize = 9.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, textAlign = TextAlign.Right)
                    )
                }
            }
            if (already) {
                Spacer(Modifier.height(2.dp))
                Text("已在采购单", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            } else if (picked) {
                Spacer(Modifier.height(2.dp))
                Text("✓ 已选", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 数量输入用的纯文本（去尾随 .0） */
private fun fmtPlain(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, neg: Boolean = false) {
    Column(
        modifier.background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            RoundedCornerShape(6.dp)
        ).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            value, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (neg) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ================= 草稿条 =================

@Composable
private fun DraftBar(vm: PurchaseViewModel, onCreate: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.ShoppingCart, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "采购单草稿：${vm.draft.size} 个商品 · ${q(vm.draft.sumOf { it.qty })} 件 · ${money(vm.draftTotal())}",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    "折扣 ${String.format(Locale.US, "%.2f", vm.discount)} · 应付 ${money(vm.draftPayable())}",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onCreate, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("去建单", fontSize = 13.sp)
            }
        }
    }
}

// ================= 采购单列表 =================

@Composable
private fun OrderSection(vm: PurchaseViewModel, canEdit: Boolean, onOpen: (PurchaseOrder) -> Unit) {
    if (vm.ordLoading && vm.orders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    vm.ordError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
    if (vm.orders.isEmpty()) {
        CenteredMessage("还没有采购单")
        return
    }
    LazyColumn(contentPadding = PaddingValues(10.dp)) {
        items(vm.orders, key = { it.id }) { o ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onOpen(o) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.purchaseNo, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (o.status == "void") {
                            Text("已作废", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        } else {
                            Text("已入库", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${o.supplierName?.takeIf { it.isNotBlank() } ?: "未指定供应商"} · ${o.purchaseDate ?: ""} · ${o.itemCount} 项",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "应付 ${money(o.payable)}",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (o.status == "void") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        if (o.status != "void") {
                            Text("已入库 · 点击看明细", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseDetailDialog(
    order: PurchaseOrder,
    items: List<PurchaseItem>,
    canVoid: Boolean,
    onVoid: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmVoid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(order.purchaseNo, fontSize = 16.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "${order.supplierName?.takeIf { it.isNotBlank() } ?: "未指定供应商"} · ${order.purchaseDate ?: ""}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                items.forEach { it ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(it.productName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${q(it.qty)} ${it.unit ?: ""} × ${money(it.price)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(it.subtotal), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("合计", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(money(order.payable), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (order.status == "void") {
                    Spacer(Modifier.height(6.dp))
                    Text("该采购单已作废，库存已退回。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (confirmVoid) {
                TextButton(onClick = { onVoid() }) { Text("确认作废", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { confirmVoid = false }) { Text("取消") }
            } else {
                if (canVoid) {
                    TextButton(onClick = { confirmVoid = true }) {
                        Icon(Icons.Filled.Undo, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("作废", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

// ================= 库存明细 =================

@Composable
private fun InventorySection(vm: PurchaseViewModel) {
    val filters = listOf(
        null to "全部",
        "neg" to "负库存",
        "diff" to "账实差异",
        "alert" to "低于预警"
    )
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            filters.forEach { (k, label) ->
                if (vm.invFilter == k) {
                    Button(
                        onClick = { vm.applyInvFilter(k) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(30.dp)
                    ) { Text(label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center) }
                } else {
                    OutlinedButton(
                        onClick = { vm.applyInvFilter(k) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(30.dp)
                    ) { Text(label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center) }
                }
            }
        }
        if (vm.invLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.invError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(10.dp)) }

        val list = vm.inventory
        if (list.isEmpty() && !vm.invLoading) {
            CenteredMessage("没有符合条件的商品")
            return
        }
        LazyColumn(contentPadding = PaddingValues(10.dp)) {
            items(list, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Text(
                                q(r.stockQty),
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = if (r.stockQty < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(r.unit ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MiniStat("已销", q(r.soldQty), Modifier.weight(1f))
                            MiniStat("已入库", q(r.inQty), Modifier.weight(1f))
                            MiniStat("推导", q(r.deriveQty), Modifier.weight(1f))
                            MiniStat("差异", (if (r.diff > 0) "+" else "") + q(r.diff), Modifier.weight(1f), neg = Math.abs(r.diff) > 0.005)
                        }
                    }
                }
            }
        }
    }
}

// ================= 库存流水 =================

@Composable
private fun MoveSection(vm: PurchaseViewModel) {
    if (vm.mvLoading && vm.moves.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    vm.mvError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
    if (vm.moves.isEmpty()) {
        CenteredMessage("暂无库存流水")
        return
    }
    LazyColumn(contentPadding = PaddingValues(10.dp)) {
        items(vm.moves, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        m.productName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${moveLabel(m.moveType)} · ${m.refNo ?: ""} · ${shortDate(m.ts)}",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    (if (m.qty > 0) "+" else "") + q(m.qty),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (m.qty >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
        }
    }
}

private fun moveLabel(t: String): String = when (t) {
    "purchase_in" -> "采购入库"
    "purchase_void" -> "采购作废"
    "sale_out" -> "销售出库"
    "sale_return" -> "销售退货"
    else -> t
}

// ================= 新建采购单 =================

@Composable
private fun NewPurchaseDialog(
    vm: PurchaseViewModel,
    onDismiss: () -> Unit,
    onSubmit: (Boolean, String) -> Unit
) {
    var supId by remember { mutableStateOf(vm.supplierId) }
    var supName by remember { mutableStateOf(vm.supplierName) }
    var disc by remember { mutableStateOf(String.format(Locale.US, "%.2f", vm.discount)) }
    var remark by remember { mutableStateOf(vm.remark) }
    var showNewSup by remember { mutableStateOf(false) }
    // 🔴 v5.7.0：选商品全屏弹窗（多列网格 + 多选 + 填数量 + 一次性确认）
    var showPick by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 🔴 LocalContext.current 是 @Composable 读法，不能在协程里调 —— 先取出来
    val ctx = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("新建采购单（提交即入库）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "口径：采购入库数量 − 销售数量 = 推导库存 0（不积压）",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // 供应商
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = supName,
                        onValueChange = { supName = it; supId = null },
                        label = { Text("供应商") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { showNewSup = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(56.dp).width(74.dp)
                    ) { Text("新建", fontSize = 12.sp, maxLines = 1) }
                }
                if (vm.suppliers.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        vm.suppliers.forEach { s ->
                            FilterChip(
                                selected = supId == s.id,
                                onClick = { supId = s.id; supName = s.name },
                                label = { Text(s.name, fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))

                // 明细
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("采购明细（${vm.draft.size} 项）", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // 🔴 v5.7.0：点这个进「选商品」全屏弹窗（多列网格 + 可多选 + 可填数量），一次性确认返回
                    OutlinedButton(
                        onClick = {
                            vm.loadPickList()
                            vm.initPickFromDraft()
                            showPick = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("添加商品", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(vm.draft, key = { it.productId }) { d ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "进价 ${money(d.price)} · 小计 ${money(d.subtotal())}",
                                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                OutlinedTextField(
                                    value = fmtQty(d.qty),
                                    onValueChange = { s ->
                                        val v = s.trim().toDoubleOrNull()
                                        vm.updateDraftQty(d.productId, v ?: 0.0)
                                    },
                                    label = { Text("数量", fontSize = 10.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(78.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                OutlinedTextField(
                                    value = fmtQty(d.price),
                                    onValueChange = { s ->
                                        val v = s.trim().toDoubleOrNull()
                                        vm.updateDraftPrice(d.productId, v ?: 0.0)
                                    },
                                    label = { Text("进价", fontSize = 10.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(78.dp)
                                )
                                IconButton(onClick = { vm.removeDraft(d.productId) }) {
                                    Icon(Icons.Filled.Delete, "移除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = disc,
                        onValueChange = { disc = it },
                        label = { Text("整单折扣", fontSize = 10.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text("备注", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("合计 ${money(vm.draftTotal())}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "应付 ${money(vm.draftPayable())}",
                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = !vm.submitting && vm.draft.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                val d = disc.trim().toDoubleOrNull()
                                if (d == null || d <= 0.0 || d > 1.0) {
                                    toast(ctx, "折扣必须在 0~1 之间", long = true)
                                    return@launch
                                }
                                vm.discount = d
                                vm.supplierId = supId
                                vm.supplierName = supName
                                vm.remark = remark
                                val no = vm.submit()
                                onSubmit(no != null, no ?: "")
                            }
                        }
                    ) {
                        if (vm.submitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("提交入库", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showPick) {
        PickProductsDialog(
            vm = vm,
            onDismiss = { showPick = false },
            onConfirm = { n ->
                showPick = false
                toast(ctx, if (n > 0) "已加入 $n 个商品" else "未选中任何商品", long = n == 0)
            }
        )
    }

    if (showNewSup) {
        NewSupplierDialog(
            onDismiss = { showNewSup = false },
            onSave = { name, contact, phone ->
                scope.launch {
                    try {
                        val r = com.heping.erpnative.data.ErpClient.api.createSupplier(
                            mapOf("name" to name, "contact" to contact, "phone" to phone)
                        )
                        vm.loadSuppliers()
                        supId = r.id
                        supName = name
                        showNewSup = false
                    } catch (e: Exception) {
                        toast(ctx, e.userMessage(), long = true)
                    }
                }
            }
        )
    }
}

// ================= 选商品弹窗（v5.7.0）=================
// 🔴 经理 2026-10-21：「可以多选产品并填写好数量再返回采购单，现在点一下就回去一下」
//    这里用**全屏 Dialog + 多列自适应网格**：每张卡片有勾选框 + 数量 + 进价，
//    底部「确定加入（N）」**一次性**写回草稿再关闭，点卡片不会关弹窗。
//    列数：≥600dp 4列/ ≥420dp 3列/ ≥360dp 2列/ 更窄 1 列（自适应，一律 LazyVerticalGrid）。

@Composable
private fun PickProductsDialog(
    vm: PurchaseViewModel,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var kw by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val wDp = LocalConfiguration.current.screenWidthDp
    val cols = when {
        wDp >= 600 -> 4
        wDp >= 420 -> 3
        wDp >= 360 -> 2
        else -> 1
    }
    val view = remember(vm.pickList, kw) {
        val k = kw.trim().lowercase()
        if (k.isEmpty()) vm.pickList else vm.pickList.filter {
            it.name.lowercase().contains(k) ||
                (it.spec ?: "").lowercase().contains(k) ||
                (it.barcode ?: "").contains(k)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("选择商品（可多选 · 可直接填数量）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = kw,
                    onValueChange = { kw = it },
                    label = { Text("商品名 / 规格 / 条码筛选") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 ${vm.pickList.size} 个商品${if (kw.isNotBlank()) "（筛出 ${view.size}）" else ""} · 已选 ${vm.pickSel.size}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.pickAllFiltered(view) }) { Text("全选", fontSize = 12.sp) }
                    TextButton(onClick = { vm.clearPick() }) { Text("清空", fontSize = 12.sp) }
                }
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))

                if (vm.pickLoading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (view.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("没有匹配的商品", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(view, key = { it.id }) { p ->
                            PickCard(
                                p = p,
                                sel = vm.pickSel[p.id],
                                onToggle = { vm.togglePick(p) },
                                onQty = { vm.setPickQty(p, it) },
                                onPrice = { vm.setPickPrice(p, it) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val amt = vm.pickSel.values.sumOf { it.qty * it.price }
                    Column(Modifier.weight(1f)) {
                        Text(
                            // 🔴 Math.round(Double) 返回 **Long**，money() 要Double → 必须 toDouble()
                            "已选 ${vm.pickSel.size} 个 · 合计 ${money(Math.round(amt * 100) / 100.0)}",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                        Text("一次性加入采购单，不再一张张点", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("取消", fontSize = 13.sp) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = vm.pickSel.isNotEmpty(),
                        onClick = { onConfirm(vm.commitPick()) },
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) { Text("确定加入（${vm.pickSel.size}）", fontSize = 13.sp) }
                }
            }
        }
    }
}

/** 选商品网格里的一张卡片：勾选框 + 数量 + 进价 */
@Composable
private fun PickCard(
    p: Product,
    sel: PurchaseViewModel.PickLine?,
    onToggle: () -> Unit,
    onQty: (Double) -> Unit,
    onPrice: (Double) -> Unit
) {
    val on = sel != null
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(
            if (on) 2.dp else 1.dp,
            if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { onToggle() }, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    p.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            Text(
                listOfNotNull(p.spec?.takeIf { it.isNotBlank() }, p.unit?.takeIf { it.isNotBlank() }).joinToString(" · "),
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = if (sel != null && sel.qty > 0) fmtPlain(sel.qty) else "",
                onValueChange = { txt -> val v = txt.toDoubleOrNull(); if (v != null) onQty(v) },
                label = { Text("数量", fontSize = 9.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, textAlign = TextAlign.Right)
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = if (sel != null) fmtPlain(sel.price) else fmtPlain(p.cost),
                onValueChange = { txt -> val v = txt.toDoubleOrNull(); if (v != null) onPrice(v) },
                label = { Text("进价", fontSize = 9.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, textAlign = TextAlign.Right)
            )
        }
    }
}

@Composable
private fun NewSupplierDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增供应商") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称*") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = contact, onValueChange = { contact = it },
                    label = { Text("联系人") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("电话") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), contact.trim(), phone.trim()) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
