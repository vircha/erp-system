package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.CreatePurchaseRequest
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.InventoryRow
import com.heping.erpnative.data.OkResponse
import com.heping.erpnative.data.Product
import com.heping.erpnative.data.PurchaseCreated
import com.heping.erpnative.data.PurchaseItem
import com.heping.erpnative.data.PurchaseLineReq
import com.heping.erpnative.data.PurchaseOrder
import com.heping.erpnative.data.PurchaseSuggestion
import com.heping.erpnative.data.StockMove
import com.heping.erpnative.data.Supplier
import com.heping.erpnative.data.userMessage
import kotlinx.coroutines.launch

/**
 * 采购与库存（v5.6.9；管家婆「以销定购」口径）
 *
 * 🔴 核心口径（与网页端/服务端一致，**不要在这里另立算法**）：
 *    建议采购量 = max(0, 净销售数量 - 已采购入库数量)
 *    净销售 = Σ order_items.qty（销售正、退货负）；已入库 = Σ purchase_items.qty（received）
 *    → 服务端 /api/purchase-suggestions 直接给出 suggest_qty，手机端**只显示不重算**。
 *
 * 采购单是「建单即入库」（服务端事务里加库存 + 写 stock_moves）。
 * 作废会把库存退回去并写一条 purchase_void 流水。
 */
class PurchaseViewModel : ViewModel() {

    // ---- 采购建议 ----
    var suggestions by mutableStateOf<List<PurchaseSuggestion>>(emptyList())
    var sugLoading by mutableStateOf(false)
    var sugError by mutableStateOf<String?>(null)

    // ---- 采购单 ----
    var orders by mutableStateOf<List<PurchaseOrder>>(emptyList())
    var ordLoading by mutableStateOf(false)
    var ordError by mutableStateOf<String?>(null)

    // ---- 库存明细 ----
    var inventory by mutableStateOf<List<InventoryRow>>(emptyList())
    var invLoading by mutableStateOf(false)
    var invError by mutableStateOf<String?>(null)
    /** 库存筛选：null=全部 / neg=负库存 / diff=账实差异 / alert=低于预警 */
    var invFilter by mutableStateOf<String?>(null)
    var invKeyword by mutableStateOf("")

    // ---- 库存流水 ----
    var moves by mutableStateOf<List<StockMove>>(emptyList())
    var mvLoading by mutableStateOf(false)
    var mvError by mutableStateOf<String?>(null)

    // ---- 供应商 ----
    var suppliers by mutableStateOf<List<Supplier>>(emptyList())

    // ---- v5.7.0 选商品弹窗（全量商品 + 多选 + 每项数量/进价）----
    var pickList by mutableStateOf<List<Product>>(emptyList())
        private set
    var pickLoading by mutableStateOf(false)
        private set
    var pickKeyword by mutableStateOf("")
    /** 已选：productId -> PickLine */
    var pickSel by mutableStateOf<Map<Int, PickLine>>(emptyMap())

    data class PickLine(val productId: Int, val name: String, val spec: String?, val unit: String?,
                        val qty: Double, val price: Double)

    fun loadPickList() {
        if (pickList.isNotEmpty()) return
        viewModelScope.launch {
            pickLoading = true
            try { pickList = ErpClient.api.products() } catch (e: Exception) { error = e.userMessage() } finally { pickLoading = false }
        }
    }

    /** 打开选商品时用当前草稿预填已选项 */
    fun initPickFromDraft() {
        if (pickSel.isNotEmpty()) return
        pickSel = draft.associate { d ->
            d.productId to PickLine(d.productId, d.name, d.spec, d.unit, d.qty, d.price)
        }
    }

    fun togglePick(p: Product) {
        val cur = pickSel[p.id]
        pickSel = if (cur != null) pickSel - p.id
        else pickSel + (p.id to PickLine(p.id, p.name, p.spec, p.unit, 1.0, p.cost))
    }

    /** 🔴 名字必须与建议页的 isPicked 区分开，否则 JVM 签名撞车（Conflicting overloads） */
    fun isPickSel(id: Int): Boolean = pickSel.containsKey(id)

    fun setPickQty(p: Product, qty: Double) {
        val cur = pickSel[p.id]
        if (qty <= 0.0001) { if (cur != null) pickSel = pickSel - p.id; return }
        val price = cur?.price ?: (p.cost)
        val line = cur ?: PickLine(p.id, p.name, p.spec, p.unit, 0.0, price)
        pickSel = pickSel + (p.id to line.copy(qty = qty))
    }

    fun setPickPrice(p: Product, price: Double) {
        val cur = pickSel[p.id] ?: PickLine(p.id, p.name, p.spec, p.unit, 1.0, price)
        pickSel = pickSel + (p.id to cur.copy(price = if (price < 0) 0.0 else price))
    }

    fun pickAllFiltered(list: List<Product>) {
        list.forEach { p -> if (!pickSel.containsKey(p.id)) togglePick(p) }
    }

    fun clearPick() { pickSel = emptyMap() }

    /** 一次性把选中的商品写回草稿，返回写入条数 */
    fun commitPick(): Int {
        var n = 0
        pickSel.values.forEach { v ->
            if (v.qty <= 0.0001) return@forEach
            val exist = draft.firstOrNull { it.productId == v.productId }
            draft = if (exist == null) {
                draft + DraftLine(v.productId, v.name, v.spec, v.unit, v.qty, v.price)
            } else {
                draft.map { if (it.productId == v.productId) it.copy(qty = v.qty, price = v.price) else it }
            }
            n++
        }
        pickSel = emptyMap()
        return n
    }

    // ---- 采购草稿（购物车）----
    var draft by mutableStateOf<List<DraftLine>>(emptyList())
    var supplierId by mutableStateOf<Int?>(null)
    var supplierName by mutableStateOf("")
    var discount by mutableStateOf(1.0)
    var remark by mutableStateOf("")

    var submitting by mutableStateOf(false)
    var lastCreated by mutableStateOf<PurchaseCreated?>(null)
    var error by mutableStateOf<String?>(null)

    /** 草稿一行：数量 + 进价都可改 */
    data class DraftLine(
        val productId: Int,
        val name: String,
        val spec: String? = null,
        val unit: String? = null,
        val qty: Double,
        val price: Double,
        /** 从采购建议带过来的建议量（用于提示，不参与计算） */
        val suggestQty: Double = 0.0
    ) {
        fun subtotal(): Double = Math.round(qty * price * 100.0) / 100.0
    }

    // ================= 加载 =================

    fun loadSuggestions(force: Boolean = false) {
        viewModelScope.launch {
            sugLoading = true; sugError = null
            try {
                suggestions = ErpClient.api.purchaseSuggestions()
            } catch (e: Exception) {
                sugError = e.userMessage()
            } finally { sugLoading = false }
        }
    }

    fun loadOrders() {
        viewModelScope.launch {
            ordLoading = true; ordError = null
            try {
                orders = ErpClient.api.purchases()
            } catch (e: Exception) {
                ordError = e.userMessage()
            } finally { ordLoading = false }
        }
    }

    fun loadInventory() {
        viewModelScope.launch {
            invLoading = true; invError = null
            try {
                inventory = ErpClient.api.inventorySummary(
                    kw = invKeyword.takeIf { it.isNotBlank() },
                    only = invFilter
                )
            } catch (e: Exception) {
                invError = e.userMessage()
            } finally { invLoading = false }
        }
    }

    // 🔴 方法名**不能**叫 setInvFilter / setInvKeyword ——
    //    属性 invFilter/invKeyword 的 setter 生成的 JVM 签名完全一样，会报
    //    "Platform declaration clash"（SaleViewModel.switchMode 就是这么撞过 setMode）。
    fun applyInvFilter(f: String?) {
        invFilter = f
        loadInventory()
    }

    fun applyInvKeyword(k: String) {
        invKeyword = k
    }

    fun loadMoves() {
        viewModelScope.launch {
            mvLoading = true; mvError = null
            try {
                moves = ErpClient.api.stockMoves()
            } catch (e: Exception) {
                mvError = e.userMessage()
            } finally { mvLoading = false }
        }
    }

    fun loadSuppliers() {
        viewModelScope.launch {
            try {
                suppliers = ErpClient.api.suppliers()
            } catch (_: Exception) { }
        }
    }

    // ================= 草稿 =================

    // ---- v5.7.0 采购建议页勾选态（经理 2026-10-21：去掉每行「加入」按钮，改为点卡片勾选 + 底部批量加入）----
    /** 已勾选采购建议的商品 id（只存在于当前页内存，不落库） */
    var sugPicked by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** 勾选时改过的采购数量：productId -> qty；没改过的用 suggest_qty */
    var sugQty by mutableStateOf<Map<Int, Double>>(emptyMap())
        private set

    fun isPicked(id: Int): Boolean = sugPicked.contains(id)

    /** 勾选/取消一个建议商品；qty 非空时一并记录数量 */
    fun togglePicked(id: Int, qty: Double? = null) {
        sugPicked = if (sugPicked.contains(id)) sugPicked - id else sugPicked + id
        if (qty != null) sugQty = sugQty + (id to qty)
    }

    fun pickAll(ids: List<Int>) {
        sugPicked = ids.toSet()
    }

    fun clearPicked() {
        sugPicked = emptySet()
        sugQty = emptyMap()
    }

    fun pickedCount(): Int = sugPicked.size

    fun pickedQty(id: Int, fallback: Double): Double = sugQty[id] ?: fallback

    /** 批量把勾选的建议加入采购草稿。返回实际加入的个数。 */
    fun addPickedToDraft(): Int {
        var n = 0
        var skipped = 0
        sugPicked.forEach { id ->
            val s = suggestions.firstOrNull { it.id == id } ?: return@forEach
            val qty = pickedQty(id, s.suggestQty)
            if (qty <= 0.005) { skipped++; return@forEach }
            val exist = draft.firstOrNull { it.productId == id }
            draft = if (exist == null) {
                draft + DraftLine(
                    productId = s.id, name = s.name, spec = s.spec, unit = s.unit,
                    qty = qty, price = s.cost, suggestQty = s.suggestQty
                )
            } else {
                draft.map {
                    if (it.productId == id) it.copy(qty = qty, price = s.cost) else it
                }
            }
            n++
        }
        clearPicked()
        if (skipped > 0) error = "$skipped 个商品数量为 0，已跳过"
        if (n > 0) loadSuggestions()      // 加入后「已在采购单」标记要更新
        return n
    }

    /** 把一条采购建议加进草稿（已在草稿里则数量累加） */
    fun addSuggestion(s: PurchaseSuggestion) {
        val q = s.suggestQty
        if (q <= 0.005) return
        val exist = draft.find { it.productId == s.id }
        draft = if (exist == null) {
            draft + DraftLine(
                productId = s.id, name = s.name, spec = s.spec, unit = s.unit,
                qty = q, price = s.cost, suggestQty = q
            )
        } else {
            draft.map {
                if (it.productId == s.id)
                    it.copy(qty = Math.round((it.qty + q) * 1000.0) / 1000.0, suggestQty = it.suggestQty + q)
                else it
            }
        }
    }

    fun removeDraft(productId: Int) {
        draft = draft.filter { it.productId != productId }
    }

    fun updateDraftQty(productId: Int, qty: Double) {
        draft = draft.map {
            if (it.productId == productId) it.copy(qty = if (qty < 0) 0.0 else qty) else it
        }
    }

    fun updateDraftPrice(productId: Int, price: Double) {
        draft = draft.map {
            if (it.productId == productId) it.copy(price = if (price < 0) 0.0 else price) else it
        }
    }

    fun clearDraft() {
        draft = emptyList()
        supplierId = null
        supplierName = ""
        discount = 1.0
        remark = ""
    }

    fun draftTotal(): Double =
        Math.round(draft.sumOf { it.subtotal() } * 100.0) / 100.0

    fun draftPayable(): Double =
        Math.round(draftTotal() * discount * 100.0) / 100.0

    // ================= 提交 / 作废 =================

    /**
     * 提交采购单（建单即入库）。
     * @return 成功返回单号，失败返回 null（错误信息在 [error]）
     */
    suspend fun submit(): String? {
        if (draft.isEmpty()) { error = "采购明细不能为空"; return null }
        val bad = draft.firstOrNull { it.qty <= 0 }
        if (bad != null) { error = "「${bad.name}」数量必须大于 0"; return null }
        if (discount <= 0.0 || discount > 1.0) { error = "折扣必须在 0~1 之间"; return null }
        return try {
            submitting = true
            error = null
            val r = ErpClient.api.createPurchase(
                CreatePurchaseRequest(
                    supplierId = supplierId,
                    supplierName = supplierName.ifBlank { null },
                    items = draft.map { PurchaseLineReq(it.productId, it.qty, it.price) },
                    discount = discount,
                    purchaseDate = com.heping.erpnative.ui.components.todayStr(),
                    remark = remark.ifBlank { null }
                )
            )
            lastCreated = r
            clearDraft()
            // 提交后三处数据都变了：建议量、库存、流水、采购单
            loadSuggestions()
            loadInventory()
            loadMoves()
            loadOrders()
            r.purchaseNo
        } catch (e: Exception) {
            error = e.userMessage()
            null
        } finally { submitting = false }
    }

    /** 作废采购单（服务端回退库存 + 写 purchase_void 流水） */
    suspend fun voidOrder(id: Int): Boolean {
        return try {
            error = null
            ErpClient.api.voidPurchase(id)
            loadOrders()
            loadInventory()
            loadMoves()
            loadSuggestions()
            true
        } catch (e: Exception) {
            error = e.userMessage()
            false
        }
    }

    /** 拉采购单详情（弹窗展示明细） */
    suspend fun loadDetail(id: Int): Pair<PurchaseOrder?, List<PurchaseItem>> {
        return try {
            val d = ErpClient.api.purchaseDetail(id)
            // 列表接口的 PurchaseOrder 没有 items，这里用详情头部字段拼一个精简对象
            PurchaseOrder(
                id = d.id, purchaseNo = d.purchaseNo, supplierId = d.supplierId,
                supplierName = d.supplierName, total = d.total, payable = d.payable,
                discount = d.discount, purchaseDate = d.purchaseDate, status = d.status,
                remark = d.remark, placedByName = d.placedByName, createdAt = d.createdAt,
                itemCount = d.items.size
            ) to d.items
        } catch (e: Exception) {
            error = e.userMessage()
            null to emptyList()
        }
    }
}
