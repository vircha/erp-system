package com.heping.erpnative.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.FundBody
import com.heping.erpnative.data.FundSync
import com.heping.erpnative.data.NetState
import com.heping.erpnative.data.PendingFund
import com.heping.erpnative.data.PendingFundDb
import com.heping.erpnative.data.SessionStore
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 本期区间（当月 1 日 ~ 月末） */
private fun monthRange(): Pair<String, String> {
    val d = LocalDate.now()
    val from = d.withDayOfMonth(1).toString()
    val to = d.withDayOfMonth(d.lengthOfMonth()).toString()
    return from to to
}

private fun money(v: Double): String = "¥" + String.format("%.2f", v)

// ============================ 资金流水 ============================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val (from, to) = monthRange()
    var f by remember { mutableStateOf(from) }
    var t by remember { mutableStateOf(to) }
    var data by remember { mutableStateOf<com.heping.erpnative.data.FundsResp?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(listOf<PendingFund>()) }
    val canEdit = SessionStore.can("fund.edit")
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun refreshPending() {
        try { pending = PendingFundDb(ctx).listAll() } catch (_: Exception) { }
    }

    fun load() {
        scope.launch {
            try { data = ErpClient.api.funds(f, t); err = null }
            catch (e: Exception) { err = e.message }
        }
    }
    // 🔴 联网即自动上传离线记账（与开单同步同思路，独立队列互不阻塞）
    LaunchedEffect(Unit) {
        NetState.online.collect { online -> if (online) {
            try { FundSync.sync(ctx); load(); refreshPending() } catch (_: Exception) { }
        } }
    }

    LaunchedEffect(Unit) { load(); refreshPending() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("资金流水") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } })
        },
        floatingActionButton = {
            if (canEdit) FloatingActionButton(onClick = { showAdd = true }) { Text("+", fontSize = 22.sp) }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = f, onValueChange = { f = it }, label = { Text("起") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("止") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { load() }) { Text("查询") }
            }
            Spacer(Modifier.height(12.dp))
            err?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (pending.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text("离线待上传 ${pending.size} 笔（联网后自动同步）", fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp))
                }
                Column {
                    pending.forEach { p ->
                        Text("· ${p.summary}", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            data?.let { d ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Kpi("收入", money(d.income))
                    Kpi("支出", money(d.expense))
                    Kpi("净额", money(d.net))
                }
                Spacer(Modifier.height(10.dp))
                if (d.rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("本期暂无流水", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn {
                        items(d.rows, key = { it.id }) { r ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${r.fundDate} ${r.category}", fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp)
                                        Text(listOf(r.account, r.counterparty, r.remark)
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(money(r.amount), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                        color = if (r.direction == "in")
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
    if (showAdd) {
        AddFundDialog(
            onDismiss = { showAdd = false },
            onSave = { body ->
                scope.launch {
                    try {
                        // 🔴 先入本地队列（幂等 id），再尝试联网上传；
                        //    失败也留在队列里，联网后由上方 NetState 收集器自动补传。
                        val id = PendingFundDb.newId()
                        val withId = FundBody(id, body.fundDate, body.direction, body.category,
                            body.amount, body.account, body.counterparty, body.remark)
                        PendingFundDb(ctx).enqueue(withId,
                            "${body.fundDate} ${body.category} ${money(body.amount)}")
                        try { FundSync.sync(ctx) } catch (_: Exception) { }
                        showAdd = false; load(); refreshPending()
                    } catch (e: Exception) { err = e.message }
                }
            }
        )
    }
}

@Composable
private fun Kpi(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AddFundDialog(onDismiss: () -> Unit, onSave: (com.heping.erpnative.data.FundBody) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var dir by remember { mutableStateOf("out") }
    var cat by remember { mutableStateOf("货款") }
    var amt by remember { mutableStateOf("") }
    var party by remember { mutableStateOf("") }
    val cats = listOf("货款", "进货", "运费", "工资", "房租", "水电", "其他")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = dir == "out", onClick = { dir = "out" }, label = { Text("支出") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = dir == "in", onClick = { dir = "in" }, label = { Text("收入") })
                }
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(value = amt, onValueChange = { amt = it }, label = { Text("金额") }, singleLine = true)
                OutlinedTextField(value = party, onValueChange = { party = it }, label = { Text("往来单位") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cats.chunked(4).forEach { row ->
                        Column { row.forEach { c ->
                            FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) })
                        } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = amt.toDoubleOrNull()
                if (v == null || v <= 0) return@TextButton
                onSave(FundBody(clientFundId = "", fundDate = date, direction = dir, category = cat, amount = v, account = "", counterparty = party, remark = ""))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================ 员工提成 ============================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommissionScreen(onBack: () -> Unit) {
    // 🔴 老板/经理（commission.edit）看全员，员工看自己（服务端按 placed_by 隔离）
    val canAll = SessionStore.can("commission.edit")
    val scope = rememberCoroutineScope()
    val (from, to) = monthRange()
    var f by remember { mutableStateOf(from) }
    var t by remember { mutableStateOf(to) }
    var mine by remember { mutableStateOf<com.heping.erpnative.data.CommissionMineResp?>(null) }
    var all by remember { mutableStateOf<com.heping.erpnative.data.CommissionAllResp?>(null) }
    var cfg by remember { mutableStateOf<com.heping.erpnative.data.CommissionRateResp?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var rateInput by remember { mutableStateOf("") }
    // 🔴 全局模式：profit 利润分成 / sales 销售额提成
    var modeInput by remember { mutableStateOf("profit") }
    // 正在编辑规则的员工（null=不弹窗）
    var ruleEdit by remember { mutableStateOf<com.heping.erpnative.data.CommissionPerson?>(null) }

    fun load() {
        scope.launch {
            try {
                if (canAll) {
                    cfg = ErpClient.api.commissionConfig()
                    all = ErpClient.api.commissionAll(f, t)
                    mine = ErpClient.api.commissionMine(f, t)
                    rateInput = ((all?.rate ?: 0.5) * 100).toInt().toString()
                    modeInput = (all?.mode ?: cfg?.mode ?: "profit")
                } else {
                    mine = ErpClient.api.commissionMine(f, t)
                    rateInput = ((mine?.rate ?: 0.5) * 100).toInt().toString()
                }
                err = null
            } catch (e: Exception) { err = e.message }
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
        TopAppBar(title = { Text(if (canAll) "员工提成" else "我的提成") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(14.dp)) {
            Text("口径：可选「利润分成（毛利×比例，默认50%）」或「销售额提成（实收×比例）」；退货自动红冲并归集到原开单人；可按员工单独设规则。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = f, onValueChange = { f = it }, label = { Text("起") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("止") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { load() }) { Text("查询") }
            }
            Spacer(Modifier.height(12.dp))
            err?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

            if (canAll) {
                // 🔴 全局规则：模式 + 比例（网页版同款，未单独设的员工按此计算）
                Text("全局规则（未单独设置的员工按此计算）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = modeInput == "profit", onClick = { modeInput = "profit" },
                        label = { Text("利润分成") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = modeInput == "sales", onClick = { modeInput = "sales" },
                        label = { Text("销售额提成") })
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(value = rateInput, onValueChange = { rateInput = it },
                        label = { Text("比例%") }, modifier = Modifier.width(96.dp), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch {
                            val v = (rateInput.toDoubleOrNull() ?: return@launch) / 100.0
                            try {
                                ErpClient.api.setCommissionRate(mapOf("rate" to v, "mode" to modeInput))
                                load()
                            } catch (e: Exception) { err = e.message }
                        }
                    }) { Text("保存") }
                }
                cfg?.let { c ->
                    if (c.rules.isNotEmpty()) Text("已单独设规则的员工：${c.rules.joinToString("、") { it.name }}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                all?.let { a ->
                    LazyColumn {
                        items(a.people, key = { it.id ?: it.name }) { p ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name + (if (p.custom) "（专属规则）" else "（全局）"),
                                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text((if (p.mode == "sales") "销售额提成" else "利润分成") + " ${(p.rate * 100).toInt()}% · 销售 ${money(p.sales)} · 退货 ${money(p.returns)} · 毛利 ${money(p.grossProfit)} · ${p.orders}单(退${p.returnOrders})",
                                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(money(p.commission), fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                            color = MaterialTheme.colorScheme.error)
                                        if (p.id != null) {
                                            Spacer(Modifier.height(4.dp))
                                            TextButton(onClick = { ruleEdit = p }) { Text("设规则", fontSize = 12.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                mine?.let { m ->
                    Text(
                        "计提方式：" + (if (m.mode == "sales") "销售额提成（实收×比例）" else "利润分成（毛利×比例）")
                                + (if (m.custom) "（专属规则）" else "（全局规则）"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Kpi("销售额", money(m.total.sales))
                        Kpi("退货额", money(m.total.returns))
                        Kpi("毛利", money(m.total.grossProfit))
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本期预计提成（比例 ${(m.rate * 100).toInt()}%）", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(m.total.commission), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("每日汇总", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn {
                        items(m.days, key = { it.date }) { d ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(Modifier.padding(11.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.date, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("销售 ${money(d.sales)} · 退货 ${money(d.returns)} · 毛利 ${money(d.grossProfit)}",
                                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(money(d.commission), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        // 🔴 经理确认：员工可看每单提成（只读，服务端按规则算，员工无写接口）
                        item {
                            Text("每单明细（只读）", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(m.orders, key = { it.orderNo }) { o ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(Modifier.padding(11.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text((if (o.isReturn) "[退货] " else "") + o.orderNo + " · " + o.customer,
                                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(o.date + " · 金额 " + money(o.sales) + " · 毛利 " + money(o.grossProfit),
                                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(money(o.commission), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                        color = if (o.isReturn) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            if (mine == null && all == null && err == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    // 🔴 按员工设提成规则（网页版同款）：模式 + 比例；删除专属则回落全局
    ruleEdit?.let { p ->
        var dMode by remember(p.id) { mutableStateOf(p.mode) }
        var dRate by remember(p.id) { mutableStateOf(((p.rate * 100).toInt()).toString()) }
        AlertDialog(
            onDismissRequest = { ruleEdit = null },
            title = { Text("提成规则 · ${p.name}") },
            text = {
                Column {
                    Text("计提方式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        FilterChip(selected = dMode == "profit", onClick = { dMode = "profit" },
                            label = { Text("利润分成") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = dMode == "sales", onClick = { dMode = "sales" },
                            label = { Text("销售额提成") })
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = dRate, onValueChange = { dRate = it },
                        label = { Text("比例 %") }, singleLine = true)
                    Text("全局：${if (cfg?.mode == "sales") "销售额提成" else "利润分成"} ${((cfg?.rate ?: 0.5) * 100).toInt()}%（不设专属则按全局）",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val v = (dRate.toDoubleOrNull() ?: return@launch) / 100.0
                        try {
                            ErpClient.api.setCommissionRate(mapOf(
                                "account_id" to (p.id ?: return@launch), "mode" to dMode, "rate" to v))
                            ruleEdit = null
                            load()
                        } catch (e: Exception) { err = e.message }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (p.custom) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    ErpClient.api.setCommissionRate(mapOf("account_id" to (p.id ?: return@launch)))
                                    ruleEdit = null
                                    load()
                                } catch (e: Exception) { err = e.message }
                            }
                        }) { Text("删除专属") }
                    }
                    TextButton(onClick = { ruleEdit = null }) { Text("取消") }
                }
            }
        )
    }
}
