package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.*
import com.heping.erpnative.ui.components.*
import com.heping.erpnative.vm.AnalyticsViewModel
import com.heping.erpnative.vm.ArViewModel
import java.util.Locale

/**
 * 报表页签（2026-10-17）：三组
 *   销售 = 销售分析 / 商品排行 / 门店排行
 *   货款 = 欠款汇总 / 账龄分析 / 收款流水
 *   库存 = 库存预警
 *
 * 🔴 设计要点：页签**只放标题**，内容整块常驻在下方（不用全屏 AlertDialog 装一长列），
 *    切页签只是换内容；段与段之间留足 20dp 以上间距。
 */
private val TABS_SALES = listOf("销售分析" to "sales", "商品排行" to "prods", "门店排行" to "stores")
private val TABS_AR = listOf("欠款汇总" to "ar", "账龄分析" to "aging", "收款流水" to "flow")
private val TABS_STOCK = listOf("库存预警" to "stock")

@Composable
fun ReportsScreen(analyticsVm: AnalyticsViewModel, arVm: ArViewModel) {
    var tab by remember { mutableStateOf("sales") }

    // 按当前页签懒加载（第一次进来才拉）
    LaunchedEffect(tab) {
        when (tab) {
            "sales" -> if (analyticsVm.data == null) { analyticsVm.load(); analyticsVm.loadShipments() }
            "prods", "stores" -> if (analyticsVm.rankLoaded.not()) analyticsVm.loadRanks()
            "ar" -> if (arVm.summary.rows.isEmpty()) arVm.loadSummary()
            "aging" -> if (arVm.aging == null) arVm.loadAging()
            "flow" -> if (arVm.payments == null) arVm.loadPayments()
            "stock" -> if (arVm.stockAlert.isEmpty()) arVm.loadStockAlert()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("报表")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {

            // ---- 分组页签（只放标题，内容常驻） ----
            if (tab in TABS_SALES.map { it.second }) {
                Text("销售", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                TabRow(TABS_SALES.map { it.first }, tab) { tab = it }
                Spacer(Modifier.height(14.dp))
            }
            if (tab in TABS_AR.map { it.second }) {
                Text("货款", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                TabRow(TABS_AR.map { it.first }, tab) { tab = it }
                Spacer(Modifier.height(14.dp))
            }
            if (tab in TABS_STOCK.map { it.second }) {
                Text("库存", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                TabRow(TABS_STOCK.map { it.first }, tab) { tab = it }
                Spacer(Modifier.height(14.dp))
            }

            analyticsVm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            arVm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            when (tab) {
                "sales" -> {
                    SalesSection(analyticsVm)
                    // 🔴 当天出货汇总（原来在「分析」页）并进来，保留 CSV 导出
                    Spacer(Modifier.height(14.dp))
                    ShipmentSection(analyticsVm)
                }
                "prods" -> RankProductsSection(analyticsVm)
                "stores" -> RankStoresSection(analyticsVm)
                "ar" -> DebtSummarySection(arVm)
                "aging" -> AgingSection(arVm)
                "flow" -> PaymentFlowSection(arVm)
                "stock" -> StockAlertSection(arVm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TabRow(labels: List<String>, cur: String, onPick: (String) -> Unit) {
    // 🔴 手机上多个页签同排：统一 flex + minWidth 0 + 单行省略，别用 wrap（会整块跳行）
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { l ->
            if (l == cur) {
                Button(
                    onClick = { onPick(l) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) { Text(l, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
            } else {
                OutlinedButton(
                    onClick = { onPick(l) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) { Text(l, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun SecCard(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            } else Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

// ---------------- 销售 ----------------

@Composable
private fun SalesSection(vm: AnalyticsViewModel) {
    val ranges = listOf("today" to "当天", "month" to "本月", "year" to "今年", "custom" to "自定义")
    if (vm.range == "custom") {
        SecCard("统计区间", "自定义") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = vm.from, onValueChange = { vm.from = it }, label = { Text("起") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = vm.to, onValueChange = { vm.to = it }, label = { Text("止") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.load() }, modifier = Modifier.fillMaxWidth().height(40.dp)) { Text("查询", fontSize = 14.sp) }
        }
        Spacer(Modifier.height(12.dp))
    }

    SecCard("销售分析", if (vm.range == "custom") null else "统计区间") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ranges.forEach { (k, l) ->
                if (vm.range == k) Button(onClick = { vm.chooseRange(k) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).height(32.dp)) {
                    Text(l, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                } else OutlinedButton(onClick = { vm.chooseRange(k) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).height(32.dp)) {
                    Text(l, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val d = vm.data
        if (d == null) Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text("${d.from} ~ ${d.to}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiCard2("销售总额", fmtM(d.grossSales), Modifier.weight(1f))
                KpiCard2("利润", fmtM(d.profit), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiCard2("利润率", pctS(d.margin), Modifier.weight(1f))
                KpiCard2("订单/数量", "${d.orders}/${num3(d.qty)}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiCard2("退货金额", fmtM(d.returns), Modifier.weight(1f), neg = d.returns > 0)
                KpiCard2("退货率", pctS(d.returnRate), Modifier.weight(1f), neg = d.returnRate > 0.05)
            }
            if (d.insights.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("经营建议", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                d.insights.forEach { ins ->
                    Text("• " + ins.text, fontSize = 12.sp, color = if (ins.type == "warn") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** 当天出货汇总（原「分析」页搬来，门店 × 产品 出货量交叉表，可导出 CSV） */
@Composable
private fun ShipmentSection(vm: AnalyticsViewModel) {
    val ctx = LocalContext.current
    SecCard("当天出货汇总", "负数=退货红冲；仅列出当天有出货/退货的门店与产品") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(value = vm.shipDate, onValueChange = { vm.shipDate = it }, label = { Text("日期") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { vm.loadShipments() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(48.dp).width(64.dp)) {
                Text("查询", fontSize = 13.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    val s = vm.ship
                    if (s != null && s.products.isNotEmpty() && s.stores.isNotEmpty()) {
                        exportShipments(ctx, s)?.let { shareFile(ctx, it) }
                    }
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(48.dp).width(64.dp)
            ) { Text("导出", fontSize = 13.sp, maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
        val s = vm.ship
        when {
            s == null -> Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.products.isEmpty() || s.stores.isEmpty() -> Text("当天无出货/退货", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    ShipCol("门店", 110.dp, true); s.products.forEach { ShipCol(it.name, 90.dp, true) }; ShipCol("合计", 70.dp, true)
                }
                Row {
                    ShipCol("成本价", 110.dp, false); s.products.forEach { ShipCol(num3(it.cost), 90.dp, false) }; ShipCol("", 70.dp, false)
                }
                s.rows.forEach { r ->
                    Row {
                        ShipCol(r.customerName, 110.dp, false)
                        var tot = 0.0
                        s.products.forEach { p ->
                            val q = r.cells.firstOrNull { it.productId == p.id }?.qty ?: 0.0
                            tot += q
                            ShipCol(num3(q), 90.dp, false, q < 0)
                        }
                        ShipCol(num3(tot), 70.dp, false, tot < 0, bold = true)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RankProductsSection(vm: AnalyticsViewModel) {
    SecCard("商品排行", "按销售额降序") {
        if (vm.reportLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val list = vm.rankProducts
        if (!vm.reportLoading && list.isEmpty()) Text("暂无数据", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Row(Modifier.fillMaxWidth()) {
                Txt("商品", 2.2f, bold = true, size = 11)
                Txt("销量", 1f, align = TextAlign.End, bold = true, size = 11)
                Txt("销售额", 1.2f, align = TextAlign.End, bold = true, size = 11)
                Txt("利润", 1.2f, align = TextAlign.End, bold = true, size = 11)
            }
            Spacer(Modifier.height(4.dp))
            list.take(50).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Txt(p.productName, 2.2f, size = 12)
                    Txt(num3(p.qty), 1f, align = TextAlign.End, size = 12)
                    Txt(fmtM(p.sales), 1.2f, align = TextAlign.End, size = 12)
                    Txt(fmtM(p.profit), 1.2f, align = TextAlign.End, size = 12)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RankStoresSection(vm: AnalyticsViewModel) {
    SecCard("门店排行", "按销售额降序") {
        if (vm.reportLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val list = vm.rankStores
        if (!vm.reportLoading && list.isEmpty()) Text("暂无数据", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Row(Modifier.fillMaxWidth()) {
                Txt("门店", 2.2f, bold = true, size = 11)
                Txt("单数", 0.8f, align = TextAlign.End, bold = true, size = 11)
                Txt("销售额", 1.2f, align = TextAlign.End, bold = true, size = 11)
                Txt("利润", 1.2f, align = TextAlign.End, bold = true, size = 11)
            }
            Spacer(Modifier.height(4.dp))
            list.take(50).forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Txt(s.customerName, 2.2f, size = 12)
                    Txt("${s.orders}", 0.8f, align = TextAlign.End, size = 12)
                    Txt(fmtM(s.sales), 1.2f, align = TextAlign.End, size = 12)
                    Txt(fmtM(s.profit), 1.2f, align = TextAlign.End, size = 12)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

// ---------------- 货款 ----------------

@Composable
private fun DebtSummarySection(arVm: ArViewModel) {
    SecCard("欠款汇总", "挂账 + 收款核销（管家婆口径）") {
        if (arVm.summaryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val s = arVm.summary
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiCard2("欠款总额", fmtM(s.totalDebt), Modifier.weight(1f), neg = s.totalDebt > 0.005)
            KpiCard2("欠款门店", "${s.rows.size} 家", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiCard2("超账期单", "${s.totalOverdue} 单", Modifier.weight(1f), neg = s.totalOverdue > 0)
            KpiCard2("已核销", fmtM(s.rows.sumOf { it.totalPaid }), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (s.rows.isEmpty()) Text("🎉 全部门店货款已结清", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        else {
            s.rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.customerName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${r.unsettled} 单未结 · 账期 ${r.termDays} 天 · 最长 ${r.maxDays} 天" +
                                if (r.overdueOrders > 0) " · ${r.overdueOrders} 单超期" else "",
                            fontSize = 11.sp,
                            color = if (r.overdueOrders > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("¥" + fmtM(r.debt), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(Modifier.padding(vertical = 3.dp))
            }
            Text("在「客户」页可直接记一笔收款", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun AgingSection(arVm: ArViewModel) {
    SecCard("账龄分析", "未结金额按账龄分档") {
        if (arVm.agingLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val a = arVm.aging
        if (a == null) Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            // 四档横排：手机上也要一行放下
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                a.buckets.forEachIndexed { i, b ->
                    val bad = i >= 2
                    Column(
                        Modifier.weight(1f)
                            .background(
                                if (bad) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 3.dp, vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(b.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Text("¥" + fmtM(b.amt), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text("${b.n} 单", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("未结最久的单", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            a.detail.take(30).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.customerName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            if (r.overdue) {
                                Spacer(Modifier.width(4.dp))
                                Text("超期", fontSize = 9.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("${r.orderNo} · ${r.orderDate} · ${r.days} 天", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("¥" + fmtM(r.left), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (r.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PaymentFlowSection(arVm: ArViewModel) {
    val ctx = LocalContext.current
    var filterCust by remember { mutableStateOf<Customer?>(null) }
    var showPick by remember { mutableStateOf(false) }

    if (showPick) {
        AlertDialog(
            onDismissRequest = { showPick = false },
            title = { Text("按门店筛选") },
            text = {
                Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                    Text("显示全部", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        filterCust = null; showPick = false; arVm.loadPayments(null)
                    })
                    Spacer(Modifier.height(8.dp))
                    // 复用欠款汇总里的门店列表
                    arVm.summary.rows.forEach { r ->
                        Text(r.customerName, fontSize = 14.sp, modifier = Modifier.clickable {
                            filterCust = Customer(id = r.customerId, name = r.customerName)
                            showPick = false; arVm.loadPayments(r.customerId)
                        })
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPick = false }) { Text("关闭") } }
        )
    }

    SecCard("收款流水", filterCust?.name ?: "全部门店（点下方可筛选）") {
        OutlinedButton(
            onClick = { showPick = true },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().height(34.dp)
        ) { Text("筛选门店", fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        if (arVm.paymentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val p = arVm.payments
        if (p == null) Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiCard2("累计收款", fmtM(p.totalIn), Modifier.weight(1f))
                KpiCard2("累计优惠", fmtM(p.totalDiscount), Modifier.weight(1f), neg = p.totalDiscount > 0)
            }
            Spacer(Modifier.height(10.dp))
            if (p.rows.isEmpty()) Text("还没有收款记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else p.rows.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(r.payNo, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${r.customerName} · ${r.payDate ?: ""} · ${r.method ?: ""}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!r.remark.isNullOrBlank()) Text(r.remark, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("+¥" + fmtM(r.amount), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            if (r.discount > 0.005) Text("优惠 ¥${fmtM(r.discount)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (SessionStore.can("ar.edit")) {
                        TextButton(
                            onClick = { arVm.undoPay(r.id, r.customerId) { ok ->
                                if (ok) toast(ctx, "已撤销 ${r.payNo}") else toast(ctx, arVm.error ?: "撤销失败")
                            } },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("撤销（已收金额会退回该单）", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 3.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun StockAlertSection(arVm: ArViewModel) {
    SecCard("库存预警", "库存 ≤ 最低库存的商品（商品管理里设「低库存阈值」才会进这里）") {
        if (arVm.reportLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val list = arVm.stockAlert
        if (!arVm.reportLoading && list.isEmpty()) Text("🎉 没有缺货商品", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        else {
            Row(Modifier.fillMaxWidth()) {
                Txt("商品", 2.4f, bold = true, size = 11)
                Txt("库存", 1f, align = TextAlign.End, bold = true, size = 11)
                Txt("最低", 1f, align = TextAlign.End, bold = true, size = 11)
            }
            Spacer(Modifier.height(4.dp))
            list.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Txt(s.name, 2.4f, size = 12)
                    Txt(num3(s.qty), 1f, align = TextAlign.End, size = 12, color = MaterialTheme.colorScheme.error)
                    Txt(num3(s.minStock), 1f, align = TextAlign.End, size = 12)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

// ---------------- 小组件 ----------------

@Composable
private fun KpiCard2(label: String, value: String, modifier: Modifier = Modifier, neg: Boolean = false) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (neg) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** 🔴 表格单元：必须在 RowScope 内调用（用 weight 按比例分配列宽）。
 *  写成普通 @Composable + Modifier.weight 会编译不过（weight 只在 RowScope 有）。 */
@Composable
private fun RowScope.Txt(
    s: String, w: Float,
    align: TextAlign = TextAlign.Start,
    bold: Boolean = false,
    size: Int = 12,
    color: androidx.compose.ui.graphics.Color? = null
) {
    Text(
        s,
        modifier = Modifier.weight(w),
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        maxLines = 1,
        color = color ?: MaterialTheme.colorScheme.onSurface
    )
}

internal fun fmtM(v: Double): String = String.format(Locale.US, "%.2f", v)
internal fun pctS(v: Double): String = String.format(Locale.US, "%.1f", v * 100) + "%"
internal fun num3(v: Double): String {
    val s = String.format(Locale.US, "%.2f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

// ---------- 出货汇总导出（CSV → 分享） ----------

@Composable
private fun ShipCol(text: String, width: Dp, header: Boolean, neg: Boolean = false, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width).padding(horizontal = 6.dp, vertical = 6.dp),
        fontSize = if (header) 12.sp else 13.sp,
        fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Normal,
        color = if (neg) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    )
}

private fun csvEsc(s: String): String =
    if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun exportShipments(ctx: android.content.Context, s: ShipmentDaily): java.io.File? {
    if (s.products.isEmpty() || s.stores.isEmpty()) return null
    val sb = StringBuilder("\uFEFF")
    sb.append("门店")
    s.products.forEach { sb.append(',').append(csvEsc(it.name)) }
    sb.append(",合计\n")
    sb.append("成本价")
    s.products.forEach { sb.append(',').append(num3(it.cost)) }
    sb.append(",\n")
    s.rows.forEach { r ->
        sb.append(csvEsc(r.customerName))
        var tot = 0.0
        s.products.forEach { p ->
            val q = r.cells.firstOrNull { it.productId == p.id }?.qty ?: 0.0
            tot += q
            sb.append(',').append(num3(q))
        }
        sb.append(',').append(num3(tot)).append('\n')
    }
    return try {
        val dir = java.io.File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val f = java.io.File(dir, "出货汇总_${s.date}.csv")
        f.writeText(sb.toString(), Charsets.UTF_8)
        f
    } catch (e: Exception) { null }
}

private fun shareFile(ctx: android.content.Context, f: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.heping.erpnative.fileprovider", f)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(i, "导出出货汇总"))
    } catch (_: Exception) {}
}