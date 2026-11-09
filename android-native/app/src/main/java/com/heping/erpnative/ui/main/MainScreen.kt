package com.heping.erpnative.ui.main

import androidx.compose.foundation.layout.*
import com.heping.erpnative.ui.components.fadeThrough
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heping.erpnative.data.NetState
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.data.SyncManager
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.*
import com.heping.erpnative.vm.PurchaseViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    // 🔴 v5.7.12 经理 2026-11-04：按钮太多 → 采购/商品/客户/报表/资金/提成 收进「功能」宫格。
    //    底部只留 开单 / 订单 / 功能 / 设置 四项；其余 Tab 枚举**保留**供宫格跳转复用。
    Func("功能", Icons.Filled.GridView),
    Sale("开单", Icons.Filled.PointOfSale),
    Orders("订单", Icons.Filled.ReceiptLong),
    // 采购含进价（成本信息），只有 boss/manager 有 purchase.view；员工没这权限时该Tab 自动不显示。
    Purchase("采购", Icons.Filled.LocalShipping),
    Products("商品", Icons.Filled.Inventory2),
    Customers("客户", Icons.Filled.People),
    Reports("报表", Icons.Filled.BarChart),
    MyAr("我的欠款", Icons.Filled.AccountBalanceWallet),
    Settings("设置", Icons.Filled.Settings),
    // 🔴 只从「功能」宫格进入，不出现在底部导航
    Funds("资金流水", Icons.Filled.AttachMoney),
    Commission("员工提成", Icons.Filled.EmojiEvents)
}

/**
 * 可见 Tab。🔴 客户角色只留「开单 / 订单 / 我的欠款」——
 * 注意 customer 的 perms 里带 product.view / customer.view（开单页取价、取本店名要用），
 * 所以**不能只按 perms 过滤**，必须按角色硬限定，否则客户能看到商品库/客户库入口。
 */
private fun visibleTabs(): List<Tab> {
    // 🔴 v5.7.12：底部只留 开单 / 订单 / 功能 / 设置
    if (SessionStore.isCustomer) return listOf(Tab.Sale, Tab.Orders, Tab.MyAr)
    return listOf(Tab.Sale, Tab.Orders, Tab.Func, Tab.Settings)
}

@Composable
fun MainScreen(onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Sale) }
    // 🔴 v5.7.13 系统返回键：从二级页退回上一级，不再直接退桌面。
    //    backStack 记录「来路」；goTo() 统一压栈；BackHandler 弹栈。
    //    一级主页：Sale 不拦截（保留系统默认退出）；其它主页回 Sale（常见 App 行为）。
    var backStack by remember { mutableStateOf(listOf<Tab>()) }
    val mainTabs = listOf(Tab.Sale, Tab.Orders, Tab.Func, Tab.Settings)
    fun goTo(t: Tab) {
        if (t == tab) return
        backStack = backStack + tab
        tab = t
    }
    // 🔴 v5.7.13：最上一级（开单页且无返回栈）按返回 → 气泡提醒一次，
    //    2 秒内再按一次才真正退出（Android 常见「双击退出」惯例，防误触退桌面）。
    var lastBackAt by remember { mutableStateOf(0L) }
    var backToast by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? android.app.Activity
    BackHandler(enabled = true) {
        when {
            backStack.isNotEmpty() -> {
                tab = backStack.last()
                backStack = backStack.dropLast(1)
                backToast = null
            }
            tab != Tab.Sale -> {
                // 一级其它主页（订单/功能/设置）→ 回开单页
                tab = Tab.Sale
                backToast = null
            }
            else -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2000) {
                    activity?.finish()          // 第二次 → 真的退出
                } else {
                    lastBackAt = now
                    backToast = "再返回一次则退出软件"
                }
            }
        }
    }
    // 气泡提醒定时器：2 秒后自动消失并复位双击计时
    backToast?.let { msg ->
        LaunchedEffect(msg, lastBackAt) {
            kotlinx.coroutines.delay(2000)
            backToast = null
            lastBackAt = 0L
        }
    }
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
                backStack = backStack + tab
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
            backStack = backStack.dropLast(1)
        }
    }

    // 🔴 v5.7.4 离线输单：网络恢复时把排队单传上去，并刷新开单页的「待上传」角标
    LaunchedEffect(Unit) {
        NetState.online.collect { online ->
            if (online) {
                try {
                    val r = SyncManager.sync(ctx)
                    if (r.ok > 0) toast(ctx, "已自动上传 ${r.ok} 单离线单据", long = true)
                } catch (_: Exception) { }
            }
            saleVm.refreshPendingCount()
        }
    }

    Scaffold(
        // 🔴🔴 v6.0.1：外层 Scaffold **不再吃系统栏内边距**。
        //   原因：如果由它统一让出状态栏，顶栏 Surface 就画不到状态栏下面 →
        //   状态栏区域露出页面底色、顶栏是白色，中间出现一条色差带（"颜色不一样"）。
        //   现在改为：顶栏自己的 Surface 延伸到状态栏下方（内部 statusBarsPadding），
        //   这样状态栏与顶栏**同一块背景**，颜色天然一致。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 🔴🔴 v6.0（targetSdk 36）底部色差的**终解**：
            //   边到边之后，系统不再替我们画导航栏区域，整块屏幕都由 App 自己绘制。
            //   NavigationBar 带 windowInsets（自动吃掉系统手势区高度），
            //   于是它会把**含手势区在内的整条底部**涂成 containerColor ——
            //   上下同一个组件、同一个色值，物理上不存在接缝。
            //   仍然保留 tonalElevation = 0：否则 M3 会再叠一层 primary 混色而偏亮。
            // 🔴 v6.0.2：底色与页面/窗口底一致 → 导航栏与下方手势区**同一颜色**，
            //   彻底消除「底部那条颜色不一样」。上面加一条细线做视觉分隔。
            Column {
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                visibleTabs().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (t in mainTabs) backStack = emptyList()
                            goTo(t)
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 🔴 v6.0：Tab 之间用**淡入淡出**（300ms）。
            //    同级页面不该有左右方向感 —— 那是「前进/后退」的语义，用错会误导。
            //    AnimatedContent 用 tab 作 key，切页时旧页淡出、新页淡入，不再硬切。
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeThrough() },
                label = "tabSwitch",
                modifier = Modifier.fillMaxSize()
            ) { cur ->
            when (cur) {
                Tab.Func -> FuncScreen(onPick = { picked -> goTo(picked) })
                Tab.Funds -> FundsScreen(onBack = { tab = Tab.Func; backStack = emptyList() })
                Tab.Commission -> CommissionScreen(onBack = { tab = Tab.Func; backStack = emptyList() })
                Tab.Sale -> {
                    // 进开单页时刷新待同步数量（离线条数可能刚被别的入口改过）
                    LaunchedEffect(Unit) { saleVm.refreshPendingCount() }
                    SaleScreen(vm = saleVm, onEditingDone = leaveSale)
                }
                Tab.Orders -> OrdersScreen(vm = ordersVm, onEditOrder = gotoSaleForEdit)
                Tab.Customers -> CustomersScreen(vm = customersVm, arVm = arVm,
                    onBack = { tab = Tab.Func; backStack = emptyList() })
                Tab.Products -> ProductsScreen(vm = productsVm,
                    onBack = { tab = Tab.Func; backStack = emptyList() })
                Tab.Reports -> ReportsScreen(analyticsVm = analyticsVm, arVm = arVm,
                    onBack = { tab = Tab.Func; backStack = emptyList() })
                Tab.MyAr -> MyArScreen(arVm = arVm)
                Tab.Settings -> SettingsScreen(vm = settingsVm, onLogout = onLogout)
                Tab.Purchase -> PurchaseScreen(vm = purchaseVm,
                    onBack = { tab = Tab.Func; backStack = emptyList() })
            }
            }
            // 🔴 气泡放 content 内最后绘制 → 盖在页面内容之上；避开底部导航栏
            backToast?.let { msg ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}
