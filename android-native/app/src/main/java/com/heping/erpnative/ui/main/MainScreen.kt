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
import com.heping.erpnative.vm.PurchaseViewModel
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    // 🔴 v5.7.0 经理 2026-10-21 指定顺序：开单 订单 采购 商品 客户 报表 我的欠款 设置
    //    （采购提到商品/客户前面；设置保持最后）
    Sale("开单", Icons.Filled.PointOfSale),
    Orders("订单", Icons.Filled.ReceiptLong),
    // 采购含进价（成本信息），只有 boss/manager 有 purchase.view；员工没这权限时该Tab 自动不显示。
    Purchase("采购", Icons.Filled.LocalShipping),
    Products("商品", Icons.Filled.Inventory2),
    Customers("客户", Icons.Filled.People),
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
            // 🔴 采购页：采购建议/采购单要 purchase.view，库存明细/流水要 inventory.view。
            //    两者都拿不到就整个页签不显示（staff 只有 inventory.view → 只看得到库存那两页）。
            Tab.Purchase -> SessionStore.can("purchase.view") || SessionStore.can("inventory.view")
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
    // 🔴 v5.6.9：采购与库存（采购建议 / 采购单 / 库存明细 / 库存流水）
    val purchaseVm: PurchaseViewModel = viewModel()

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
                Tab.Purchase -> PurchaseScreen(vm = purchaseVm)
            }
        }
    }
}
