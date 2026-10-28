package com.heping.erpnative.vm

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.heping.erpnative.data.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.*
import com.heping.erpnative.device.BtPrinter
import com.heping.erpnative.device.EscPos
import com.heping.erpnative.device.PrintResult
import com.heping.erpnative.ocr.OcrTable
import com.heping.erpnative.ocr.TableFile
import com.heping.erpnative.ocr.TableOcr
import com.google.gson.Gson
import com.heping.erpnative.ui.components.daysAgo
import com.heping.erpnative.ui.components.nowStr
import com.heping.erpnative.ui.components.todayStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 金额四位以内抖动统一按「分」取整，和服务端 toFixed(2) 口径保持一致 */
private fun Double.round2(): Double = Math.round(this * 100.0) / 100.0

/**
 * 购物车一行。
 * price 统一按「折前单价」存：
 *   成交单价 = price × discount ；小计 = 成交单价 × qty
 * discount = 1 表示这行不打折（「有的单品有折扣，有的没有」就是这个字段）。
 */
data class CartLine(
    val product: Product,
    var qty: Double,
    var price: Double,
    var discount: Double = 1.0
) {
    /** 折后成交单价 */
    fun unitPrice(): Double = (price * discount).round2()
    /** 本行小计 */
    fun subtotal(): Double = (unitPrice() * qty).round2()
    /**
     * 本行单品折扣让掉的金额（相对**折前**）。
     * 2026-10-17：经理反馈「单品设置价格后没显示优惠金额」——网页版已加，
     * 手机版同步补上，行内直接看得见省了多少。
     */
    fun saveAmount(): Double = ((price - unitPrice()) * qty).round2()

    /**
     * 本行件数（= 数量 ÷ 每箱装量）。
     *
     * 🔴🔴 口径必须与桌面《开单模板》的「合计（件数）」列**完全一致**（经理 2026-10-27 定案）：
     *   - **保留 2 位小数，不进位**（36÷24 = **1.5**；6÷12 = **0.5**；不写成 2 和 1）
     *   - `pack_size = 0`（未维护箱规）→ 返回 **null**，界面上**留空**，
     *     ❌ 绝不能算成 0 —— 0 会被当成真实的「0 件」
     *
     * @return 件数；箱规未维护时 null
     */
    fun packCount(): Double? {
        val ps = product.packSize
        if (ps <= 0) return null
        val v = qty / ps
        // 保留 2 位小数（不进位）—— 与 Excel ROUND(x, 2) 一致
        return Math.round(v * 100.0) / 100.0
    }
}

class SaleViewModel : ViewModel() {
    /** 🔴 v5.7.4 离线队列：把下单请求原样存成 JSON，联网后原样重放 */
    private val gson = Gson()

    var customers by mutableStateOf<List<Customer>>(emptyList())
    var products by mutableStateOf<List<Product>>(emptyList())
    var selectedCustomer by mutableStateOf<Customer?>(null)
    var cart by mutableStateOf<List<CartLine>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false)
    var lastResult by mutableStateOf<OrderCreated?>(null)
    var discountInput by mutableStateOf("1")
    var offline by mutableStateOf(false)

    // 🔴 2026-10-15 经理反馈「点了打印弹窗就没了，要打两次单」：
    // 打印结果提示改放在开单成功弹窗**内部**（打印后不关窗，可连打联单），
    // 这两个字段承载弹窗里的那一行状态文字。
    /** 空=还没打过；有值就显示在弹窗里（成功绿/失败红） */
    var printHint by mutableStateOf("")
    /** printHint 是不是成功态（决定文字颜色） */
    var printOk by mutableStateOf(false)

    /** 提交新一轮开单前清掉上一次的打印提示 */
    fun resetPrintHint() {
        printHint = ""
        printOk = false
    }

    /** 开单模式：sale=销售单 / return=销售退货单 */
    var mode by mutableStateOf("sale")
    val isReturnMode: Boolean get() = mode == "return"

    /** 切换单据模式：必须清空购物车与客户，避免销售单的商品串到退货单里
     *  （不能叫 setMode——会和 mode 属性自动生成的 setter 撞 JVM 签名）
     *  ⚠️ 编辑订单过程中禁止切换：那会把购物车清空，正在改的单子就没了。 */
    fun switchMode(m: String) {
        if (mode == m) return
        if (editingOrderId != null) { error = "正在修改订单 ${editingOrderNo ?: ""}，不能切换单据类型"; return }
        mode = m
        clearCart()
        selectedCustomer = null
        priceMap = emptyMap()
        itemDiscountMap = emptyMap()
        error = null
    }

    // ---------- 编辑订单（跳到开单界面自由改：可增删商品、改量、改价、改折扣） ----------
    /** 非空 = 正在编辑这张单；提交时走 PUT 而不是 POST */
    var editingOrderId by mutableStateOf<Int?>(null)
    var editingOrderNo by mutableStateOf<String?>(null)
    var editingOrderDate by mutableStateOf<String?>(null)
    var editingStatus by mutableStateOf<String?>(null)
    val isEditing: Boolean get() = editingOrderId != null

    /** 从订单列表点「编辑」进来：拉原单 + 补齐商品/客户缓存 → 装车 */
    suspend fun prepareEdit(id: Int): Boolean {
        return try {
            error = null
            val d = ErpClient.api.orderDetail(id)
            // 加购和选客户都依赖这两份列表，缺了就先拉（失败退到本地缓存）
            if (products.isEmpty()) {
                products = try { ErpClient.api.products() } catch (_: Exception) { CacheStore.getProducts() }
                CacheStore.putProducts(products)
            }
            if (customers.isEmpty()) {
                customers = try { ErpClient.api.customers() } catch (_: Exception) { CacheStore.getCustomers() }
                CacheStore.putCustomers(customers)
            }
            val o = d.order
            // 编辑的销售单：万一切到退货模式残留，先强制回到销售单
            if (mode != "sale") { mode = "sale"; clearCart() }
            // 🔴 2026-10-15：编辑「退货单」时必须保持退货模式。
            // 原来这里一律 mode="sale"，编辑退货单时界面显示「应收 xxx」而不是「应退 xxx」，
            // 提交也会带 type=null（虽然服务端 PUT 仍按 o.status 锁定 returned，
            // 但界面口径错误、且 printLast 内存回退分支会拿到 isReturnMode=false）。
            val editingReturn = (o.status ?: "") == "returned" || o.total < 0
            if (editingReturn) {
                mode = "return"
                clearCart()
            }
            editingOrderId = o.id
            editingOrderNo = o.orderNo
            editingOrderDate = o.orderDate?.take(10)
            editingStatus = o.status
            selectedCustomer = customers.firstOrNull { it.id == o.customerId }
            // 编辑态不从门店历史价取价：单价以原单为准，用户想改就自己改
            priceMap = emptyMap()
            itemDiscountMap = emptyMap()
            cart = d.items.map { it.toEditLine() }
            discountInput = fmtDiscInput(o.discount ?: 1.0)
            lastResult = null
            true
        } catch (e: Exception) { error = e.userMessage(); false }
    }

    /** 原单明细 → 购物车行。
     *
     * 🔴🔴 2026-10-20 经理需求「商品信息变动不影响历史已开出的订单，该显示原来的信息就原来的信息」。
     *   原实现是 `products.firstOrNull { it.id == product_id }` **优先用当前商品表**，
     *   商品被删才回退到明细快照 —— 语义正好反了：商品表改了名/规格/条码后，
     *   编辑一张老单（只想改数量）屏幕上显示的就是**新名**，保存后服务端按快照写回，
     *   前后不一致还会让人以为数据被改了。
     *   ✅ 正解：一律用**明细快照**造 Product。编辑态显示的就是开单当时的信息。
     *   （服务端 PUT 也已同步改成沿用原单快照，两端口径一致。）
     *
     *   商品被删也要保住这一行，否则编辑一次就会静默吞掉门店买过的商品。 */
    private fun OrderItem.toEditLine(): CartLine {
        val snapName = productName
        val p = if (snapName.isNotBlank()) {
            Product(id = product_id, name = snapName, barcode = barcode, spec = spec,
                unit = unit ?: "个", price = price)
        } else {
            // 极端兜底：明细快照名为空（理论上不该发生）才回退当前商品表
            products.firstOrNull { it.id == product_id }
                ?: Product(id = product_id, name = "", barcode = barcode, spec = spec,
                    unit = unit ?: "个", price = price)
        }
        val od = (discount ?: 1.0).let { if (it > 0) it else 1.0 }
        val op = origPrice ?: 0.0
        // 有单品折扣的行：price 是折后价，要用折前单价回填，否则再存一次就折两次
        val base = if (od < 1.0 && op > 0) op else price
        // 🔴 2026-10-15：退货单明细 qty 是负数，装车必须取绝对值。
        // 否则购物车显示「-2 件」，用户再提交会让服务端按 qty 再翻一次负号。
        // （提交时客户端一律传正数量，负号统一由服务端按 sign 处理。）
        val q = kotlin.math.abs(qty)
        return CartLine(p, q, base, od.coerceIn(0.1, 1.0))
    }

    private fun fmtDiscInput(d: Double): String {
        val s = String.format(java.util.Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')
        return if (s.isEmpty()) "1" else s
    }

    /** 放弃修改：清编辑态与购物车，回到正常开单 */
    fun cancelEdit() {
        editingOrderId = null; editingOrderNo = null
        editingOrderDate = null; editingStatus = null
        clearCart()
        selectedCustomer = null
        priceMap = emptyMap(); itemDiscountMap = emptyMap()
    }

    /**
     * 门店 × 商品的**历史送货价**记忆（= customer_prices 那张表）。
     * 🔴 2026-10-20 从 private 改成公开的 `mutableStateOf`：商品列表要按它做「历史优先」排序，
     *   而 Compose 只有读到 observable state 才会重绘 —— 挂在普通 var 上排序永远不更新。
     */
    var priceMap by mutableStateOf<Map<Int, Double>>(emptyMap())
    /** 门店 × 商品的单品折扣记忆（选客户时拉回来，加购自动套用） */
    private var itemDiscountMap: Map<Int, Double> = emptyMap()

    /**
     * 2026-10-20 商品列表「历史优先」排序要用。
     * 判据用「有值且 > 0」—— customer_prices 里 0/NULL 是脏数据，不能算「有历史价」。
     */
    fun hasStorePrice(productId: Int): Boolean = (priceMap[productId] ?: 0.0) > 0.0

    fun loadCustomers(q: String? = null) {
        viewModelScope.launch {
            try {
                val list = ErpClient.api.customers(q?.takeIf { it.isNotBlank() })
                customers = list
                CacheStore.putCustomers(list)
                offline = false
            } catch (e: Exception) {
                offline = isNetworkError(e)
                error = if (offline) null else e.userMessage()
                if (customers.isEmpty()) customers = CacheStore.getCustomers()
            }
        }
    }

    fun loadProducts(q: String? = null) {
        viewModelScope.launch {
            try {
                // 🔴 2026-10-19 客户多门店：带上当前门店，服务端才返回该店的历史价
                val cid = if (SessionStore.isCustomer) SessionStore.saleStore.takeIf { it > 0 } else null
                val list = ErpClient.api.products(q?.takeIf { it.isNotBlank() }, cid)
                products = list
                // 🔴 门店变了就别把这份价缓存下来 —— 否则下次断网会读出上一家店的价格
                CacheStore.putProducts(list, cid)
                offline = false
            } catch (e: Exception) {
                offline = isNetworkError(e)
                error = if (offline) null else e.userMessage()
                // 只有缓存属于当前门店才能用，客户切店后不能拿旧门店的价当现价
                val cid = if (SessionStore.isCustomer) SessionStore.saleStore.takeIf { it > 0 } else null
                if (products.isEmpty() && (cid == null || CacheStore.lastProductStore == cid)) {
                    products = CacheStore.getProducts()
                }
            }
        }
    }

    fun selectCustomer(c: Customer?) {
        selectedCustomer = c
        if (c == null) { priceMap = emptyMap(); itemDiscountMap = emptyMap(); return }
        viewModelScope.launch {
            try {
                val r = ErpClient.api.customerPrices(c.id)
                priceMap = r.prices.mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it >= 0 }
                // 历史单品折扣：老数据 discount 可能是 0/NULL，服务端已归一到 0.1~1，这里再兜一次底
                itemDiscountMap = r.itemDiscounts
                    .mapKeys { it.key.toIntOrNull() ?: -1 }
                    .filterKeys { it >= 0 }
                    .mapValues { it.value.coerceIn(0.1, 1.0) }
            } catch (_: Exception) { priceMap = emptyMap(); itemDiscountMap = emptyMap() }
        }
    }

    /**
     * 2026-10-19 客户多门店：切换当前下单门店。
     * 门店一变，**商品价和历史价都得重载** —— 不重载就是拿上一家店的价格下单。
     */
    suspend fun switchStore(id: Int) {
        if (!SessionStore.custStoreAllowed(id)) return      // 不是他的门店，直接忽略
        if (id == selectedCustomer?.id) return
        SessionStore.setSaleStore(id)
        cart = emptyList()                                   // 价格全变，清掉已录入的行
        val c = customers.firstOrNull { it.id == id }
        selectedCustomer = c
        priceMap = emptyMap(); itemDiscountMap = emptyMap()
        loadProducts()
        if (c != null) {
            try {
                val r = ErpClient.api.customerPrices(c.id)
                priceMap = r.prices.mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it >= 0 }
                itemDiscountMap = r.itemDiscounts
                    .mapKeys { it.key.toIntOrNull() ?: -1 }
                    .filterKeys { it >= 0 }
                    .mapValues { it.value.coerceIn(0.1, 1.0) }
            } catch (_: Exception) { }
        }
    }

    fun addToCart(p: Product) {
        val existing = cart.find { it.product.id == p.id }
        if (existing != null) {
            cart = cart.map { if (it.product.id == p.id) it.copy(qty = it.qty + 1) else it }
        } else {
            val price = priceMap[p.id] ?: p.price
            // 该门店对这件商品上次用的折扣，自动带出来（没有记录就是 1，不打折）
            val disc = itemDiscountMap[p.id] ?: 1.0
            cart = cart + CartLine(p, 1.0, price, disc)
        }
    }

    fun changeQty(idx: Int, delta: Double) {
        val list = cart.toMutableList()
        if (idx in list.indices) {
            val nl = list[idx].copy(qty = (list[idx].qty + delta).coerceAtLeast(1.0))
            list[idx] = nl
            cart = list
        }
    }

    fun setQty(idx: Int, qty: Double) {
        val list = cart.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(qty = qty.coerceAtLeast(1.0))
            cart = list
        }
    }

    fun setPrice(idx: Int, price: Double) {
        val list = cart.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(price = price.coerceAtLeast(0.0))
            cart = list
        }
    }

    /** 设置某一行的单品折扣（1 = 不打折） */
    fun setItemDiscount(idx: Int, disc: Double) {
        val list = cart.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(discount = disc.coerceIn(0.1, 1.0))
            cart = list
        }
    }

    /** 整单统一设折扣：只改各行的单品折扣，不动整单折扣字段 */
    fun applyDiscountToAll(disc: Double) {
        val d = disc.coerceIn(0.1, 1.0)
        cart = cart.map { it.copy(discount = d) }
    }

    /** 全部行恢复不打折 */
    fun resetAllDiscounts() { cart = cart.map { it.copy(discount = 1.0) } }

    fun removeAt(idx: Int) {
        cart = cart.toMutableList().also { if (idx in it.indices) it.removeAt(idx) }
    }

    // 合计按「折后单价 × 数量」累加
    fun cartTotal(): Double = cart.sumOf { it.unitPrice() * it.qty }.round2()
    /**
     * 折前合计（完全不打任何折扣的应有金额）。
     * 2026-10-17：要显示「优惠金额」必须有折前这个基准，否则只有单品折扣、
     * 没有整单折扣时总让利会算成 0，看着像「一分钱没让」（网页版踩过这个坑）。
     */
    fun cartTotalBefore(): Double = cart.sumOf { it.price * it.qty }.round2()
    /** 单品折扣累计让利（相对折前） */
    fun itemSave(): Double = cart.sumOf { it.saveAmount() }.round2()
    /** 整单折扣让利（相对含单品折扣的合计） */
    fun orderSave(): Double = (cartTotal() - payable()).round2()
    /** 总让利（相对折前）。⚠️ 必须用折前算，用 cartTotal()-payable() 会漏掉单品折扣那部分 */
    fun totalSave(): Double = (cartTotalBefore() - payable()).round2()
    fun payable(): Double {
        val d = discountInput.toDoubleOrNull() ?: 1.0
        return (cartTotal() * d.coerceIn(0.1, 1.0)).round2()
    }

    fun clearCart() {
        cart = emptyList()
        lastResult = null
        discountInput = "1"
    }

    /**
     * 提交单据。销售单与销售退货单走同一个接口，区别只在 type：
     * type='return' 时服务端会把数量/金额记成负数并回补库存（管家婆式退货单，不必挂原单）。
     * 这里的 qty/price 一律按正数传，负数由服务端统一处理。
     *
     * 编辑模式（editingOrderId != null）走 PUT /api/orders/{id}：整单替换明细，
     * 后端会回补旧明细库存再扣新的，并重算应收。
     *
     * 🔴🔴 v5.7.4 离线输单（经理 2026-10-28：「移动版支持离网输单」）：
     *   断网时不再「提交失败、订单丢失」，而是**存进本地队列**，联网后自动上传。
     *   - 离线单带 client_id（UUID）作为幂等键 → 重试不会重复建单、重复扣库存；
     *   - 编辑已有单**不能**离线（改单要服务端配合，冲突风险大）→ 明确提示；
     *   - 退货单也支持离线（同样是新建一张单，服务端按 type='return' 处理）。
     */
    suspend fun submit(): Boolean {
        val ret = isReturnMode
        val editId = editingOrderId
        val cust = selectedCustomer ?: run {
            error = if (ret) "请先选择退货客户" else "请先选择客户"; return false
        }
        if (cart.isEmpty()) { error = if (ret) "请添加退货商品" else "请添加商品"; return false }
        // 🔴 这三个变量必须在 try **外面**声明：catch 里的离线入队逻辑要用到它们。
        //   （写在 try 内部 → catch 块看不到 → 编译不过）
        // 🔴 2026-10-20 客户账号：整单折扣强制 1（服务端 _forceStorePrice 也会再强制一次，
        //    这里只是不把「折扣由商家定」的东西发出去）。UI 已改成只读，正常走不到非 1 的分支，
        //    但仍显式兜住 —— 免得以后有人把只读去掉就静默把折扣传出去了。
        val d = if (SessionStore.isCustomer) 1.0
                else (discountInput.toDoubleOrNull()?.coerceIn(0.1, 1.0) ?: 1.0)
        // 🔴 幂等键：每次提交一单生成一个 UUID。离线时它也是本地队列的主键。
        val clientId = java.util.UUID.randomUUID().toString()
        val req = CreateOrderRequest(
            customerId = cust.id,
            // price 传折前单价 + 带上该行 discount，服务端会算出成交价 = price × discount；
            // 折扣为 1 的行也照传（值为 1），这样「有的有折扣有的没有」能被正确记忆。
            items = cart.map {
                CreateOrderItem(
                    productId = it.product.id,
                    qty = it.qty,
                    price = it.price,
                    discount = it.discount
                )
            },
            discount = if (d != 1.0) d else null,
            // 退货单的状态由服务端强制为 'returned'，这里不传
            status = if (editId != null) editingStatus else (if (ret) null else "paid"),
            orderDate = if (editId != null) editingOrderDate else null,
            type = if (ret) "return" else null,
            clientOrderId = clientId
        )
        return try {
            submitting = true
            error = null
            val r = if (editId != null) ErpClient.api.updateOrder(editId, req) else ErpClient.api.createOrder(req)
            lastResult = r
            // 新单成功 → 清掉上一单的打印提示，别让新单弹窗里挂着旧提示
            resetPrintHint()
            if (editId != null) {
                // 编辑成功：清编辑态，购物车也清掉，避免误以为还能再提交一次
                editingOrderId = null; editingOrderNo = null
                editingOrderDate = null; editingStatus = null
                clearCart()
                selectedCustomer = null
                priceMap = emptyMap(); itemDiscountMap = emptyMap()
            }
            true
        } catch (e: Exception) {
            // 🔴🔴 断网 → 存本地队列（订单不丢），而不是直接失败
            if (isNetworkError(e)) {
                if (editId != null) {
                    // 改已有单不能离线：老单的明细/金额要跟服务端对齐，盲改会冲突
                    error = "当前无网络，修改已有订单需要联网（请恢复网络后重试）"
                    return false
                }
                if (enqueueOffline(req, cust)) {
                    offline = true
                    // 离线单没有服务端单号 → lastResult 造一个本地占位，让「打印/查看」有东西可显示
                    val p = payable()
                    val signed = if (ret) -p else p
                    lastResult = OrderCreated(
                        orderId = 0,
                        orderNo = "待同步-${clientId.take(8)}",
                        total = signed,
                        discount = d,
                        payable = signed,
                        type = if (ret) "return" else null,
                        totalSigned = signed,
                        payableSigned = signed
                    )
                    resetPrintHint()
                    // 离线单同样清购物车（单已经收下了，不能重复录）
                    clearCart()
                    selectedCustomer = null
                    priceMap = emptyMap(); itemDiscountMap = emptyMap()
                    error = null
                    offlineNotice = "当前无网络，单据已保存在手机本地，联网后会自动上传"
                    refreshPendingCount()
                    return true
                } else {
                    error = "当前无网络，且本地保存失败：${e.userMessage()}"
                    return false
                }
            }
            error = e.userMessage()
            false
        } finally { submitting = false }
    }

    /**
     * 离线单入队。返回是否成功。
     * 🔴 存的是**完整下单请求 JSON** —— 联网后原样重放，服务端算价口径与在线时完全一致。
     */
    private fun enqueueOffline(req: CreateOrderRequest, cust: Customer): Boolean {
        return try {
            val db = LocalOrderDb(SessionStore.appContext)
            db.enqueue(
                clientId = req.clientOrderId ?: java.util.UUID.randomUUID().toString(),
                payload = gson.toJson(req),
                customerName = cust.name,
                amount = payable()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 🔴 离线提示（一次性，UI 看完可清） */
    var offlineNotice by mutableStateOf<String?>(null)
    fun clearOfflineNotice() { offlineNotice = null }

    /** 🔴 待同步数量（角标） */
    var pendingSyncCount by mutableStateOf(0)
        private set

    /** 刷新待同步数量 */
    fun refreshPendingCount() {
        viewModelScope.launch {
            pendingSyncCount = SyncManager.pendingCount(SessionStore.appContext)
        }
    }

    /**
     * 打印刚提交的单据。
     * 🔴 一律从服务端回查已落库的单据（ErpClient.api.orderDetail(orderId)）来打，
     * 不再用内存里的 cart / isReturnMode —— 那两个都是易失状态：
     *  ① 提交成功后 clearCart() 会把购物车清空，打印就变成空小票；
     *  ② isReturnMode 一旦丢失，退货单会被判成销售单，标题印成「销售订单」。
     * 服务端的 status='returned' + 负金额是唯一可信口径。
     */
    suspend fun printLast(ctx: Context): PrintResult {
        val r = lastResult ?: return PrintResult.NO_ORDER
        return withContext(Dispatchers.IO) {
            if (!BtPrinter.isOn()) return@withContext PrintResult.BT_OFF
            val mac = BtPrinter.getLastMac(ctx) ?: return@withContext PrintResult.NO_PRINTER
            // 🔴 2026-10-15 经理要求「不手动连接，点打印直接出单」：
            // 原来这里直接 connect()，未配对时 createBond() 会弹系统配对框，每次都要人点。
            // ensureConnected 先走静默重连（已配对 = 全程无 UI），只有从没配对过才退回来弹框。
            if (!BtPrinter.ensureConnected(ctx)) return@withContext PrintResult.CONNECT_FAIL

            // 回查服务端拿权威明细（失败则退回内存 cart，保证至少能印出东西）
            val detail = try {
                if (r.orderId > 0) ErpClient.api.orderDetail(r.orderId) else null
            } catch (_: Exception) { null }

            val isRet = detail?.order?.let { (it.status ?: "") == "returned" || it.total < 0 }
                ?: isReturnMode

            val lines = detail?.items?.map {
                // 🔴 2026-10-18：改成管家婆式**双价列**（左原价、右特价），理由同 OrdersScreen.printOrder。
                //    origPrice 走折前原价，老单 NULL 时兜回 price，绝不印 0.00。
                val orig = (it.origPrice ?: 0.0).takeIf { v -> v > 0 } ?: it.price
                EscPos.Line(
                    name = it.productName,
                    qty = it.qty,
                    price = it.price,
                    unit = it.unit,
                    barcode = it.barcode?.trim()?.takeIf { s -> s.isNotEmpty() },
                    origPrice = orig
                )
            } ?: cart.map {
                EscPos.Line(
                    name = it.product.name,
                    qty = if (isReturnMode) -it.qty else it.qty,
                    price = it.price * it.discount,   // 折后成交价 → 「特价」列
                    unit = it.product.unit,
                    barcode = it.product.barcode?.trim()?.takeIf { s -> s.isNotEmpty() }
                        ?: it.product.spec?.trim()?.takeIf { s -> s.isNotEmpty() && s.all { ch -> ch.isDigit() } },
                    // CartLine.price 本身就是折前单价 → 「原价」列
                    origPrice = it.price
                )
            }
            if (lines.isEmpty()) return@withContext PrintResult.NO_ORDER

            val shop = SessionStore.shopName.ifEmpty { "销售小票" }
            // 折前合计由明细重算（退货取绝对值口径与打印金额一致）：
            // orders.total = Σ 折后单价×数量，天然漏掉单品折扣的让利，不能直接当折前用。
            // 🔴 2026-10-18：改按 origOrPrice()（折前原价）算，与小票「原价」列同源。
            //    用 price（现已是折后成交价）会把单品折扣的让利漏掉，优惠金额又变 0。
            val beforeFromLines = kotlin.math.abs(lines.sumOf { it.qty * it.origOrPrice() })
            val before = if (isRet) -beforeFromLines else beforeFromLines
            // 退货单金额是负数，打印直接用负数（服务端落库口径）
            val pay = detail?.order?.payable?.takeIf { it != null && it != 0.0 }
                ?: r.payableSigned
                ?: (if (isRet) -r.payable else r.payable)
            val bytes = EscPos.receipt(
                shop = shop,
                orderNo = r.orderNo,
                customer = detail?.order?.customerName ?: selectedCustomer?.name ?: "",
                items = lines,
                beforeDiscount = before,
                payable = pay,
                footer = if (isRet) "退货单已入账，感谢配合" else "谢谢惠顾，欢迎再次光临",
                docTitle = if (isRet) "销售退货单" else "销售订单",
                dateTime = (detail?.order?.orderDate ?: detail?.order?.created_at ?: nowStr())
                    .replace("T", " ").take(16),
                handler = SessionStore.receiptHandler.ifBlank { SessionStore.displayName },
                warehouse = SessionStore.receiptWarehouse,
                isReturn = isRet
            )
            if (BtPrinter.print(bytes)) PrintResult.SUCCESS else PrintResult.SEND_FAIL
        }
    }

    // ---------- 退货（开单界面入口） ----------
    var returnableOrders by mutableStateOf<List<Order>>(emptyList())
    var returnDetail by mutableStateOf<OrderDetail?>(null)
    var returning by mutableStateOf(false)

    fun loadReturnableOrders() {
        viewModelScope.launch {
            try {
                val list = ErpClient.api.orders(from = daysAgo(30), to = todayStr())
                returnableOrders = list.filter { (it.status ?: "") != "returned" }
            } catch (e: Exception) {
                returnableOrders = emptyList()
                error = e.userMessage()
            }
        }
    }

    fun loadReturnDetail(id: Int) {
        viewModelScope.launch {
            try { returnDetail = ErpClient.api.orderDetail(id) }
            catch (e: Exception) { error = e.userMessage() }
        }
    }

    fun clearReturnDetail() { returnDetail = null }

    suspend fun returnOrder(id: Int): Boolean {
        return try {
            returning = true
            ErpClient.api.returnOrder(id)
            returnableOrders = returnableOrders.filter { it.id != id }
            true
        } catch (e: Exception) { error = e.userMessage(); false }
        finally { returning = false }
    }

    /** 部分退货：像开单一样逐项选商品/数量/单价，生成退货单；返回退货单号用于打印 */
    suspend fun submitReturnItems(id: Int, items: List<CreateOrderItem>): ReturnedOrder? {
        return try {
            returning = true
            error = null
            val r = ErpClient.api.returnOrderItems(id, ReturnItemsRequest(items))
            r
        } catch (e: Exception) { error = e.userMessage(); null }
        finally { returning = false }
    }

    // ---------- 识别开单：表格图片 → 各门店当天订单 ----------
    var ocrProcessing by mutableStateOf(false)
    var ocrError by mutableStateOf<String?>(null)
    var ocrTable by mutableStateOf<OcrTable?>(null)
    var ocrRaw by mutableStateOf<String?>(null)   // 识别原文（失败时可查看核对）
    var ocrEngine by mutableStateOf("")           // 本次使用的解析引擎：本地 / 本地(布局重建) / 文件
    var generating by mutableStateOf(false)
    var genResults by mutableStateOf<List<GenResult>?>(null)

    /**
     * 图片 → 订单：ML Kit 本地 OCR 出文字 → 本地规则解析（纯离线，已移除 AI 依赖）。
     * ① 先按「列=门店、行=品项」解析（老分配表）；
     * ② 失败则按 x 中心重建网格，走方向自适应解析（兼容「行=门店、列=产品」的开单模板）。
     */
    fun parseOcrBitmap(bm: Bitmap, ctx: Context) {
        viewModelScope.launch {
            ocrProcessing = true; ocrError = null; ocrTable = null; ocrRaw = null; ocrEngine = ""
            try {
                val lines = withContext(Dispatchers.Default) { TableOcr.extract(bm) }
                ocrRaw = lines.flat
                if (lines.rows.isEmpty()) {
                    ocrError = "图片里没有识别到任何文字。请换一张更清晰的截图（表头、门店/产品、数量都要在画面内，避免反光/模糊）"
                    return@launch
                }
                val stores = customers.map { it.name }
                val prods = products.map { it.name }

                var table = withContext(Dispatchers.Default) { TableOcr.parseLocal(lines) }
                ocrEngine = "本地"
                if (table.storeHeaders.isEmpty() || table.rows.isEmpty()) {
                    val grid = withContext(Dispatchers.Default) { TableOcr.linesToGrid(lines) }
                    val t2 = withContext(Dispatchers.Default) { TableFile.gridToTable(grid, stores, prods) }
                    if (t2.storeHeaders.isNotEmpty() && t2.rows.isNotEmpty()) {
                        table = t2; ocrEngine = "本地(布局重建)"
                    }
                }

                if (table.storeHeaders.isEmpty() || table.rows.isEmpty()) {
                    ocrError = "未能识别出表格结构。可点「查看识别原文」核对 OCR 结果；" +
                        "更稳的做法是用「下载模板」填好后导入 Excel 文件。"
                } else {
                    ocrTable = table
                }
            } catch (e: Exception) {
                val m = e.message ?: e.javaClass.simpleName
                ocrError = if (m.contains("Waiting", true) || m.contains("download", true) || m.contains("model", true))
                    "文字识别引擎未就绪：$m"
                else "识别失败：$m"
            } finally {
                ocrProcessing = false
                bm.recycle()
            }
        }
    }

    /** 直接导入开单表文件（xlsx/csv）：不经过 OCR，最准；方向自动判别 */
    fun importTableFile(ctx: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            ocrProcessing = true; ocrError = null; ocrTable = null; ocrRaw = null; ocrEngine = "文件"
            try {
                val stores = customers.map { it.name }
                val prods = products.map { it.name }
                val t = withContext(Dispatchers.IO) { TableFile.parse(ctx, uri, stores, prods) }
                if (t.storeHeaders.isEmpty() || t.rows.isEmpty()) {
                    ocrError = "文件里没解析出有效的开单数据。请确保：第一行是门店名（或产品名），" +
                        "第一列是产品名（或门店名），数量填在对应单元格里。" +
                        "建议直接用 App 里的「下载模板」生成标准模板再填写。"
                } else {
                    ocrTable = t
                }
            } catch (e: Exception) {
                ocrError = "导入失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                ocrProcessing = false
            }
        }
    }

    // ---------- 开单模板下载 ----------
    var downloadingTemplate by mutableStateOf(false)
    var templateMsg by mutableStateOf<String?>(null)

    fun clearTemplateMsg() { templateMsg = null }

    /** 下载开单模板（行=门店、列=产品、单位 EA、自动带汇总公式），保存并调起表格应用打开 */
    fun downloadTemplate(ctx: Context) {
        if (downloadingTemplate) return
        viewModelScope.launch {
            downloadingTemplate = true; templateMsg = null
            try {
                val body = ErpClient.api.orderTemplate()
                val dir = File(ctx.getExternalFilesDir(null), "templates").apply { mkdirs() }
                val f = File(dir, "开单模板.xlsx")
                withContext(Dispatchers.IO) {
                    body.byteStream().use { ins -> f.outputStream().use { ins.copyTo(it) } }
                }
                // 校验是 xlsx（PK 头）
                val head = ByteArray(2)
                val n = withContext(Dispatchers.IO) { f.inputStream().use { it.read(head) } }
                val ok = n == 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && f.length() > 500
                if (!ok) { templateMsg = "模板下载异常（文件不完整），请重试"; return@launch }
                val opened = openXlsx(ctx, f)
                templateMsg = if (opened)
                    "模板已保存：${f.absolutePath}\n已调起表格应用；填好数量后回 App 点「导入表格」即可自动开单。"
                else
                    "模板已保存：${f.absolutePath}\n（未找到可打开的表格应用，可用文件管理器打开）"
            } catch (e: Exception) {
                templateMsg = "下载模板失败：${e.userMessage()}"
            } finally { downloadingTemplate = false }
        }
    }

    private fun openXlsx(ctx: Context, f: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(ctx, "com.heping.erpnative.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "打开开单模板").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    fun dismissOcr() { ocrTable = null; ocrError = null; ocrRaw = null }
    fun dismissGenResults() { genResults = null }

    data class OcrFinalItem(val product: Product, val qty: Double, val special: Boolean)
    data class OcrFinalStore(val customer: Customer, val rebatePct: Double, val items: List<OcrFinalItem>)

    data class GenResult(
        val label: String, val orderId: Int, val orderNo: String?,
        val payable: Double?, val error: String?
    )

    /** 按门店批量下单：正常价单带返点折扣，特价单无折扣（同店拆两张，方便对单） */
    fun generateOcrOrders(stores: List<OcrFinalStore>) {
        viewModelScope.launch {
            generating = true
            val out = mutableListOf<GenResult>()
            try {
                for (st in stores) {
                    val priceMap = try {
                        ErpClient.api.customerPrices(st.customer.id).prices
                            .mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it >= 0 }
                    } catch (_: Exception) { emptyMap<Int, Double>() }
                    val normal = st.items.filter { !it.special }
                    val specialItems = st.items.filter { it.special }
                    val groups = mutableListOf<Pair<String, List<OcrFinalItem>>>()
                    if (normal.isNotEmpty()) groups.add("正常价" to normal)
                    if (specialItems.isNotEmpty()) groups.add("特价" to specialItems)
                    for ((kind, items) in groups) {
                        val rebate = if (kind == "正常价") st.rebatePct else 0.0
                        val req = CreateOrderRequest(
                            customerId = st.customer.id,
                            items = items.map {
                                CreateOrderItem(
                                    productId = it.product.id, qty = it.qty,
                                    price = priceMap[it.product.id] ?: it.product.price
                                )
                            },
                            discount = if (rebate > 0.001) (100.0 - rebate) / 100.0 else null,
                            status = "paid",
                            remark = "表格导入开单"
                        )
                        out += try {
                            val r = ErpClient.api.createOrder(req)
                            GenResult("${st.customer.name}·$kind", r.orderId, r.orderNo, r.payable, null)
                        } catch (e: Exception) {
                            GenResult("${st.customer.name}·$kind", 0, null, null, e.userMessage())
                        }
                    }
                }
                genResults = out
            } finally { generating = false }
        }
    }
}
