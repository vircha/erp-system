package com.heping.erpnative.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.*
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Sale("开单", Icons.Filled.PointOfSale),
    Orders("订单", Icons.Filled.ReceiptLong),
    Customers("客户", Icons.Filled.People),
    Products("商品", Icons.Filled.Inventory2),
    Reports("报表", Icons.Filled.BarChart),
    MyAr("我的欠款", Icons.Filled.AccountBalanceWallet),
    Settings("设置", Icons.Filled.Settings)
}

/**
 * 可见 Tab。🔴 客户角色只留「开单 / 订单 / 我的欠款」——
 * 注意 customer 的 perms 里带 product.view / customer.view（开单页取价、取本店名要用），
 * 所以**不能只按 perms 过滤**，必须按角色硬限定，否则客户能看到商品库/客户库入口。
 */
private fun visibleTabs(): List<Tab> {
    if (SessionStore.isCustomer) return listOf(Tab.Sale, Tab.Orders, Tab.MyAr)
    return Tab.values().filter { t ->
        when (t) {
            // 报表页含销售分析（analysis.view）与货款（ar.view）两种权限，
            // 员工没 ar.view 就退回只显示销售那组，别整页藏起来。
            Tab.Reports -> SessionStore.can("analysis.view") || SessionStore.can("ar.view")
            Tab.MyAr -> false          // 我的欠款仅客户账号
            else -> true
        }
    }
}

@Composable
fun MainScreen(onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Sale) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val saleVm: SaleViewModel = viewModel()
    val ordersVm: OrdersViewModel = viewModel()
    val customersVm: CustomersViewModel = viewModel()
    val productsVm: ProductsViewModel = viewModel()
    val analyticsVm: AnalyticsViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    // 🔴 应收应付（挂账 + 收款核销），客户页与报表页共用同一个实例
    val arVm: ArViewModel = viewModel()

    // 编辑订单：订单页点「编辑」→ 装车到 SaleViewModel → 切到开单页自由改（可增删商品）
    // 开单页保存/取消后要跳回订单页，所以这里留一个「保存后回到哪」的记忆。
    var saleReturnTab by remember { mutableStateOf<Tab?>(null) }
    val gotoSaleForEdit: (Int) -> Unit = { id ->
        scope.launch {
            if (saleVm.prepareEdit(id)) {
                saleReturnTab = Tab.Orders
                tab = Tab.Sale
            } else {
                toast(ctx, "打开订单失败：" + (saleVm.error ?: "请重试"), long = true)
            }
        }
    }
    val leaveSale: () -> Unit = {
        val back = saleReturnTab
        saleReturnTab = null
        if (back != null) {
            ordersVm.loadOrders()
            tab = back
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                visibleTabs().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Sale -> SaleScreen(vm = saleVm, onEditingDone = leaveSale)
                Tab.Orders -> OrdersScreen(vm = ordersVm, onEditOrder = gotoSaleForEdit)
                Tab.Customers -> CustomersScreen(vm = customersVm, arVm = arVm)
                Tab.Products -> ProductsScreen(vm = productsVm)
                Tab.Reports -> ReportsScreen(analyticsVm = analyticsVm, arVm = arVm)
                Tab.MyAr -> MyArScreen(arVm = arVm)
                Tab.Settings -> SettingsScreen(vm = settingsVm, onLogout = onLogout)
            }
        }
    }
}
