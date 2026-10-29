package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heping.erpnative.data.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.CacheStore
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.Order
import com.heping.erpnative.data.OrderDetail
import com.heping.erpnative.data.CreateOrderRequest
import com.heping.erpnative.data.CreateOrderItem
import com.heping.erpnative.data.ReturnItemsRequest
import com.heping.erpnative.data.ReturnedOrder
import com.heping.erpnative.data.isNetworkError
import com.heping.erpnative.ui.components.*
import kotlinx.coroutines.launch

class OrdersViewModel : ViewModel() {
    var orders by mutableStateOf<List<Order>>(emptyList())
    var detail by mutableStateOf<OrderDetail?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var returning by mutableStateOf(false)
    var offline by mutableStateOf(false)
    var from by mutableStateOf(todayStr())
    var to by mutableStateOf(todayStr())

    fun loadOrders(f: String? = from, t: String? = to) {
        if (f != null) from = f
        if (t != null) to = t
        viewModelScope.launch {
            loading = true; error = null; offline = false
            try {
                orders = ErpClient.api.orders(from = from, to = to)
                CacheStore.putOrders(from ?: "null", to ?: "null", orders)
            } catch (e: Exception) {
                val cached = try { CacheStore.getOrders(from ?: "null", to ?: "null") } catch (_: Exception) { emptyList() }
                if (cached.isNotEmpty()) {
                    orders = cached
                    offline = isNetworkError(e)
                    if (!offline) error = e.userMessage()
                } else error = e.userMessage()
            } finally { loading = false }
        }
    }

    fun setToday() = loadOrders(todayStr(), todayStr())
    fun setLast7() = loadOrders(daysAgo(6), todayStr())
    fun setMonth() = loadOrders(monthStart(), todayStr())
    fun setAll() = loadOrders(null, null)

    fun loadDetail(id: Int) {
        viewModelScope.launch {
            try { detail = ErpClient.api.orderDetail(id) }
            catch (e: Exception) { error = e.userMessage() }
        }
    }

    fun clearDetail() { detail = null }

    suspend fun returnOrder(id: Int): Boolean {
        return try {
            returning = true
            ErpClient.api.returnOrder(id)
            loadOrders()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
        finally { returning = false }
    }

    /** 部分退货：逐项商品/数量/单价，生成退货单 */
    suspend fun submitReturnItems(id: Int, items: List<CreateOrderItem>): ReturnedOrder? {
        return try {
            returning = true
            error = null
            val r = ErpClient.api.returnOrderItems(id, ReturnItemsRequest(items))
            loadOrders()
            r
        } catch (e: Exception) { error = e.userMessage(); null }
        finally { returning = false }
    }

    /** 删除订单（后端会回滚库存） */
    suspend fun deleteOrder(id: Int): Pair<Boolean, String?> {
        return try {
            ErpClient.api.deleteOrder(id)
            loadOrders()
            true to null
        } catch (e: Exception) { false to e.userMessage() }
    }

    /** 复制订单：同客户、同商品数量与价格，新建一张今天的订单，返回新单号 */
    suspend fun copyOrder(id: Int): Pair<Boolean, String?> {
        return try {
            val d = ErpClient.api.orderDetail(id)
            val o = d.order
            if (o.customerId <= 0) return false to "该订单无客户信息，无法复制"
            // 🔴 2026-10-29：复制「退货单」必须原样复制成退货单。
            //   退货单明细 qty 是负数，直接传会被服务端 Math.abs 取正 → 复制出一张正数销售单（账目出错）；
            //   status="paid" 也会让它变成普通销售单。正确做法：qty 取绝对值 + type='return'
            //   （服务端据此强制 status='returned'、金额取负、TH 单号、库存回补）。
            val isReturn = o.status == "returned" || o.total < 0
            val req = CreateOrderRequest(
                customerId = o.customerId,
                // 🔴 单品折扣要一起复制：原单带折扣的行，price 是**折后**成交价。
                //   不带 discount 的话服务端会走「price 就是成交价」的兼容分支 → 折扣记忆丢失；
                //   带 discount 时 price 必须是**折前**单价（服务端 lineCalc 拿它乘折扣还原成交价）。
                items = d.items.map {
                    val od = it.discount ?: 1.0
                    val op = it.origPrice ?: 0.0
                    val pr0 = kotlin.math.abs(it.price)
                    // 🔴 只有「折前 × 折扣 ≈ 成交价」时才带 discount。
                    //   orig_price 为 NULL 的老单若仍带 discount → 服务端拿成交价再乘一次折扣 = 二次打折。
                    val hasDisc = od > 0 && od < 1.0 - 1e-9 && op > 0 && kotlin.math.abs(op * od - pr0) < 0.05
                    CreateOrderItem(
                        productId = it.product_id,
                        qty = kotlin.math.abs(it.qty),
                        price = if (hasDisc) op else pr0,
                        discount = if (hasDisc) od else null
                    )
                },
                discount = if ((o.discount) != 1.0) o.discount else null,
                orderDate = todayStr(),
                // 退货单不能传 status='paid' —— 服务端见到 type='return' 会强制置为 returned
                status = if (isReturn) null else "paid",
                remark = "复制自 ${o.orderNo}",
                type = if (isReturn) "return" else null
            )
            val r = ErpClient.api.createOrder(req)
            loadOrders()
            true to r.orderNo
        } catch (e: Exception) { false to e.userMessage() }
    }

    // 注：原「编辑订单」弹窗（只能改数量金额）已废弃。
    // 现在点「编辑」由 MainScreen 装车到 SaleViewModel，跳开单页自由增删改。

    // ---------- 多选批量删除 ----------
    var multi by mutableStateOf(false)
    var selected by mutableStateOf<Set<Int>>(emptySet())
    var deleting by mutableStateOf(false)

    fun toggleMulti() { multi = !multi; selected = emptySet() }
    fun toggleSelect(id: Int) { selected = if (selected.contains(id)) selected - id else selected + id }
    fun selectAll(ids: List<Int>) { selected = if (ids.isNotEmpty() && selected.size == ids.size) emptySet() else ids.toSet() }

    /** 返回 Triple(删除数, 跳过数, 错误信息) */
    suspend fun deleteSelected(): Triple<Int, Int, String?> {
        val ids = selected.toList()
        if (ids.isEmpty()) return Triple(0, 0, null)
        return try {
            deleting = true
            val r = ErpClient.api.deleteOrdersBatch(mapOf("ids" to ids))
            selected = emptySet(); multi = false
            loadOrders()
            Triple(r.deleted, r.skipped.size, null)
        } catch (e: Exception) { Triple(0, 0, e.userMessage()) }
        finally { deleting = false }
    }
}
