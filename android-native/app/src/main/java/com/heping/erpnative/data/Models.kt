package com.heping.erpnative.data

import com.google.gson.annotations.SerializedName

// ---------- 鉴权 ----------
data class Account(
    val id: Int = 0,
    val username: String = "",
    val display_name: String? = null,
    val role: String = "staff",
    @SerializedName("roleLabel") val roleLabel: String = "",
    val book_id: Int = 1,
    val active: Int = 1,
    val created_at: String? = null,
    /** 客户账号绑定的门店 id（仅 role=customer 有值；多门店下这是**默认门店**） */
    @SerializedName("customer_id") val customerId: Int? = null,
    // 2026-10-19：客户下单员可负责多家门店，这里是全部门店
    @SerializedName("customer_ids") val customerIds: List<Int> = emptyList()
)

data class Book(val id: Int = 1, val name: String = "", val role: String? = null)

data class AuthResponse(
    val token: String = "",
    val account: Account? = null,
    val book: Book? = null,
    val role: String = "staff",
    @SerializedName("roleLabel") val roleLabel: String = "",
    val perms: List<String> = emptyList(),
    @SerializedName("shopName") val shopName: String = "",
    @SerializedName("device_token") val deviceToken: String? = null,
    // 客户下单角色：绑定的门店 id。客户登录后一切数据都以它为边界（服务端也会强制锁定）。
    @SerializedName("customerId") val customerId: Int? = null,
    // 2026-10-19 多门店：他负责的**全部**门店。customerId 只是默认门店。
    @SerializedName("customerIds") val customerIds: List<Int> = emptyList(),
    // 信任设备已满 4 台：本机未下发令牌，登录仍成功但不会被记住
    @SerializedName("trust_full") val trustFull: Boolean = false
)

data class PrecheckResponse(
    @SerializedName("need_captcha") val needCaptcha: Boolean = false,
    val fails: Int = 0
)

data class CaptchaResponse(val cid: String = "", val svg: String = "")

data class LoginRequest(
    val username: String,
    val password: String,
    @SerializedName("trust_device") val trustDevice: Boolean = false,
    val device: String? = null,
    @SerializedName("captcha_id") val captchaId: String? = null,
    val captcha: String? = null
)

data class RegisterRequest(
    val username: String,
    val password: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("book_id") val bookId: Int? = null,
    @SerializedName("book_name") val bookName: String? = null,
    @SerializedName("join_password") val joinPassword: String? = null,
    @SerializedName("trust_device") val trustDevice: Boolean = false,
    val device: String? = null,
    @SerializedName("captcha_id") val captchaId: String,
    val captcha: String
)

data class MeResponse(
    val account: Account? = null,
    val book: Book? = null,
    val role: String = "staff",
    @SerializedName("roleLabel") val roleLabel: String = "",
    val perms: List<String> = emptyList(),
    @SerializedName("shopName") val shopName: String = "",
    val books: List<Book> = emptyList(),
    /** 客户账号绑定的门店 id（0 = 非客户账号；多门店下是**默认门店**） */
    @SerializedName("customerId") val customerId: Int = 0,
    // 2026-10-19 多门店：他负责的全部门店，门店切换器的数据源
    @SerializedName("customerIds") val customerIds: List<Int> = emptyList()
)

// ---------- 客户 ----------
data class Customer(
    val id: Int = 0,
    val book_id: Int = 1,
    val name: String = "",
    val type: String? = "store",
    val contact: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val remark: String? = null,
    val created_at: String? = null
)

// ---------- 商品 ----------
data class Product(
    val id: Int = 0,
    val book_id: Int = 1,
    val name: String = "",
    val barcode: String? = null,
    val spec: String? = null,
    val unit: String? = "个",
    val price: Double = 0.0,
    val cost: Double = 0.0,
    // 列表接口 JOIN 出来的库存字段
    val qty: Double = 0.0,
    @SerializedName("low_threshold") val lowThreshold: Double = 0.0,
    val created_at: String? = null
)

// ---------- 开单 / 订单 ----------
data class OrderItem(
    val id: Int = 0,
    val order_id: Int = 0,
    val product_id: Int = 0,
    @SerializedName("product_name") val productName: String = "",
    val spec: String? = null,
    // 商品条码快照（2026-10-15 后端起随明细下发；老单由服务端用商品表回填）。
    // ⚠️ spec 装的是规格文字（106g/180ml），打印条码只能用 barcode。
    val barcode: String? = null,
    val unit: String? = null,
    val qty: Double = 0.0,
    val price: Double = 0.0,
    val cost: Double = 0.0,
    val subtotal: Double = 0.0,
    // 单品折扣相关：price = 折后成交单价，origPrice = 折前单价，discount = 该行折扣率
    val discount: Double? = null,
    @SerializedName("orig_price") val origPrice: Double? = null
)

data class Order(
    val id: Int = 0,
    val book_id: Int = 1,
    @SerializedName("order_no") val orderNo: String = "",
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("customer_name") val customerName: String = "",
    val total: Double = 0.0,
    val payable: Double = 0.0,
    val discount: Double = 1.0,
    @SerializedName("order_date") val orderDate: String? = null,
    val status: String = "paid",
    val remark: String? = null,
    val created_at: String? = null,
    @SerializedName("return_of") val returnOf: Int? = null,
    // 🔴 2026-10-20 客户自助下单标识：服务端 orders 表新加了 placed_by / placed_by_role /
    //    placed_by_name 三列，GET /api/orders 用的是 `SELECT *`，所以字段已经在返回里，
    //    这里加两行就能接上，**不需要改服务端**。
    //    判据用 placedByRole == "customer"（强信号），❌ 不用「有备注」之类的弱信号。
    @SerializedName("placed_by_role") val placedByRole: String? = null,
    @SerializedName("placed_by_name") val placedByName: String? = null,
    // 🔴 已被收款核销的金额（2026-10-17 应收应付上线）。
    //    欠款 = payable − settled；结清状态一律由金额推导，**不看 status**。
    val settled: Double? = null
) {
    /**
     * 是不是客户用下单账号自助提交的单（而不是业务员代开）。
     * 老订单这三列是 NULL → false → 不显示标识。
     * 🔴 绝不能把 NULL 回落成 "staff" —— 那等于把全部历史单标成商家单。
     */
    fun isCustomerSelfOrder(): Boolean = (placedByRole ?: "") == "customer"

    /** 未结金额：退货单 payable 为负，会自动抵减欠款 */
    fun left(): Double = (payable.takeIf { it != 0.0 } ?: total) - (settled ?: 0.0)

    /** unpaid=未收款 / partial=部分收款 / settled=已结清 */
    fun settleState(): String {
        val l = left()
        return if (l <= 0.005) "settled" else if ((settled ?: 0.0) > 0.005) "partial" else "unpaid"
    }
}

data class OrderDetail(val order: Order = Order(), val items: List<OrderItem> = emptyList())

data class CreateOrderItem(
    @SerializedName("product_id") val productId: Int,
    val qty: Double,
    // ⚠️ 一旦带上 discount，服务端会把 price 当作「折前单价」，
    //    成交单价 = price × discount。不带 discount 则 price 就是成交价（兼容旧逻辑）。
    val price: Double? = null,
    val discount: Double? = null
)

data class CreateOrderRequest(
    @SerializedName("customer_id") val customerId: Int,
    val items: List<CreateOrderItem>,
    val discount: Double? = null,
    @SerializedName("order_date") val orderDate: String? = null,
    val status: String? = null,
    val remark: String? = null,
    // 单据类型：null/sale=销售单；return=销售退货单（管家婆式：手工录品，服务端存负数并回补库存）
    val type: String? = null,
    // 可选关联的原销售单号（仅用于追溯，允许为空）
    @SerializedName("return_of") val returnOf: Int? = null
)

data class OrderCreated(
    @SerializedName("order_id") val orderId: Int = 0,
    @SerializedName("order_no") val orderNo: String = "",
    val total: Double = 0.0,
    val discount: Double = 1.0,
    val payable: Double = 0.0,
    val type: String? = null,                                          // sale / return
    // 退货单用：服务端返回带符号的金额（负数）
    @SerializedName("total_signed") val totalSigned: Double? = null,
    @SerializedName("payable_signed") val payableSigned: Double? = null
)

data class ReturnedOrder(
    @SerializedName("order_id") val orderId: Int = 0,
    @SerializedName("order_no") val orderNo: String = ""
)

/** 部分退货请求：逐项指定退货商品与数量/单价 */
data class ReturnItemsRequest(
    val items: List<CreateOrderItem>
)

data class CustomerPriceResp(
    val prices: Map<String, Double> = emptyMap(),
    // 门店 × 商品的单品折扣记忆（key = product_id），没有记录的商品不在 map 里
    @SerializedName("item_discounts") val itemDiscounts: Map<String, Double> = emptyMap(),
    val discount: Double = 1.0,
    @SerializedName("has_history") val hasHistory: Boolean = false,
    @SerializedName("last_order_id") val lastOrderId: Int? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

// ---------- 设置 ----------
data class Settings(
    @SerializedName("shop_name") val shopName: String = "",
    @SerializedName("receipt_title") val receiptTitle: String = "销售小票",
    @SerializedName("receipt_footer") val receiptFooter: String = "谢谢惠顾，欢迎再次光临",
    @SerializedName("list_title") val listTitle: String = "商品清单",
    @SerializedName("receipt_warehouse") val receiptWarehouse: String = "天河仓",
    @SerializedName("receipt_handler") val receiptHandler: String = "",
    @SerializedName("join_password") val joinPassword: String = ""
)

// ---------- 统计 ----------
data class LowStock(val name: String = "", val qty: Double = 0.0, val low: Double = 0.0)

data class Stats(
    @SerializedName("todaySales") val todaySales: Double = 0.0,
    @SerializedName("todayOrders") val todayOrders: Int = 0,
    @SerializedName("lowStock") val lowStock: List<LowStock> = emptyList()
)

data class Monthly(
    val month: String = "",
    val sales: Double = 0.0,
    val qty: Double = 0.0,
    val profit: Double = 0.0,
    val orders: Int = 0
)

// ---------- 信任设备 ----------
data class TrustedDevice(
    val id: Int = 0,
    val device: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_used_at") val lastUsedAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    val expired: Boolean = false
)

/** 🔴 1.5.1：/api/trusted-devices 的返回结构。
 *  以前是裸数组，现在包成对象以便带上「最多 4 台」的占用信息。 */
data class TrustedDevicesResponse(
    val devices: List<TrustedDevice> = emptyList(),
    val max: Int = 4,
    val used: Int = 0,
    val left: Int = 0
)

// ---------- 通用响应 ----------
data class IdResponse(val id: Int = 0)
data class OkResponse(val ok: Boolean = false, val removed: Int = 0)
data class SettingsOk(val ok: Boolean = false, @SerializedName("shop_name") val shopName: String = "")
data class BookSwitchResponse(val ok: Boolean = false, val book: Book? = null, @SerializedName("shop_name") val shopName: String = "")

// ---------- 在线更新（update.json） ----------
data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    // 🔴 v5.7.3：服务器端 url 现在是**永久短链** /downloads/apk（永不变、永远指向最新版）。
    //    r2Url 是 R2 预签名直链（服务器 302 目标），本 App 不直接用它。
    val url: String = "",
    @SerializedName("r2Url") val r2Url: String = "",
    val desc: String = "",
    val force: Boolean = false
)

// ---------- 数据分析 ----------
data class ProductStat(
    val pid: Int = 0,
    val name: String = "",
    val qty: Double = 0.0,
    @SerializedName("ret_qty") val retQty: Double = 0.0,
    val sales: Double = 0.0,
    val profit: Double = 0.0,
    val margin: Double = 0.0
)
data class Insight(
    val type: String = "info",   // good / info / warn
    val text: String = ""
)
data class AnalyticsResp(
    val range: String = "",
    val from: String = "",
    val to: String = "",
    @SerializedName("grossSales") val grossSales: Double = 0.0,
    val sales: Double = 0.0,
    val returns: Double = 0.0,
    @SerializedName("returnRate") val returnRate: Double = 0.0,
    @SerializedName("returnCount") val returnCount: Int = 0,
    val profit: Double = 0.0,
    val margin: Double = 0.0,
    val orders: Int = 0,
    val qty: Double = 0.0,
    val products: List<ProductStat> = emptyList(),
    val insights: List<Insight> = emptyList()
)
data class ShipProduct(
    val id: Int = 0,
    val name: String = "",
    val unit: String? = null,
    val cost: Double = 0.0
)
data class ShipStore(val id: Int = 0, val name: String = "")
data class ShipCell(
    @SerializedName("product_id") val productId: Int = 0,
    val qty: Double = 0.0,
    val amount: Double = 0.0
)
data class ShipRow(
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("customer_name") val customerName: String = "",
    val cells: List<ShipCell> = emptyList()
)
data class ShipmentDaily(
    val date: String = "",
    val stores: List<ShipStore> = emptyList(),
    val products: List<ShipProduct> = emptyList(),
    val rows: List<ShipRow> = emptyList()
)

// ---------- 批量删除订单 ----------
data class BatchSkip(val id: Int = 0, val reason: String = "")
data class BatchDeleteResp(
    val ok: Boolean = false,
    val deleted: Int = 0,
    val skipped: List<BatchSkip> = emptyList()
)

// ==========================================================================
// 应收应付（2026-10-17 上线，管家婆式：挂账 + 收款单 + 核销）
//
// 🔴🔴 三条铁律（与网页版 / 服务端完全一致，改动前先想清楚）：
//   1. 欠款 = COALESCE(payable,total) − COALESCE(settled,0)，只认这两个数
//   2. **订单金额永远不改**：payable / total / status 一律不回写；
//      抹零优惠记在 payments.discount，settled += amount + discount
//   3. 自动分配 = 管家婆逻辑：**按未结单日期升序，从最早那张开始结清**，
//      结完溢出到下一单（服务端 ORDER BY 日期 ASC, id ASC 已保证顺序）
// ==========================================================================

/** 未结清单里的一张单 */
data class ArOrder(
    @SerializedName("order_id") val orderId: Int = 0,
    @SerializedName("order_no") val orderNo: String = "",
    @SerializedName("order_date") val orderDate: String = "",
    val due: Double = 0.0,
    val paid: Double = 0.0,
    val left: Double = 0.0,
    /** unpaid / partial / settled */
    val state: String = "unpaid",
    /** 账龄天数（距开单日） */
    val days: Int = 0,
    /** 该门店账期天数（默认 30） */
    val term: Int = 30,
    val overdue: Boolean = false
)

/** GET /api/ar/unsettled 的返回 */
data class ArUnsettledResp(
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("term_days") val termDays: Int = 30,
    @SerializedName("total_left") val totalLeft: Double = 0.0,
    /**
     * 欠款总额 = 待收未结 + 退货抵减（负数）。
     * 🔴 2026-10-18：别再用 totalLeft 当欠款——它不含退货冲减，
     *    会和客户页/报表页对不上（同一个门店三个地方三个数）。
     */
    val debt: Double = 0.0,
    /** 退货抵减额（负数）；非 0 时界面要显示「已扣退货」 */
    @SerializedName("return_credit") val returnCredit: Double = 0.0,
    val orders: List<ArOrder> = emptyList()
)

/** 收款明细的一行（核销到哪张单） */
data class ArPayItem(
    @SerializedName("order_id") val orderId: Int,
    val amount: Double,
    val discount: Double = 0.0
)

/** POST /api/ar/pay 请求体 */
data class ArPayRequest(
    @SerializedName("customer_id") val customerId: Int,
    val amount: Double,
    val discount: Double = 0.0,
    val method: String = "微信",
    @SerializedName("pay_date") val payDate: String? = null,
    val remark: String? = null,
    val items: List<ArPayItem> = emptyList()
)

/** POST /api/ar/pay 返回 */
data class ArPayResp(
    @SerializedName("payment_id") val paymentId: Int = 0,
    @SerializedName("pay_no") val payNo: String = "",
    val amount: Double = 0.0,
    val discount: Double = 0.0,
    @SerializedName("unsettled_left") val unsettledLeft: Int = 0
)

/** 一家门店的欠款汇总行 */
data class ArSummaryRow(
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("customer_name") val customerName: String = "",
    val orders: Int = 0,
    val unsettled: Int = 0,
    @SerializedName("total_due") val totalDue: Double = 0.0,
    @SerializedName("total_paid") val totalPaid: Double = 0.0,
    val debt: Double = 0.0,
    @SerializedName("term_days") val termDays: Int = 30,
    @SerializedName("overdue_orders") val overdueOrders: Int = 0,
    @SerializedName("max_days") val maxDays: Int = 0
)

/** GET /api/ar/summary */
data class ArSummaryResp(
    val rows: List<ArSummaryRow> = emptyList(),
    @SerializedName("total_debt") val totalDebt: Double = 0.0,
    @SerializedName("total_overdue") val totalOverdue: Int = 0
)

/** 账龄四档中的一档 */
data class ArBucket(val label: String = "", val n: Int = 0, val amt: Double = 0.0)

/** 账龄明细里的一张单 */
data class ArAgingRow(
    @SerializedName("order_id") val orderId: Int = 0,
    @SerializedName("order_no") val orderNo: String = "",
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("customer_name") val customerName: String = "",
    val left: Double = 0.0,
    val days: Int = 0,
    @SerializedName("order_date") val orderDate: String = "",
    val overdue: Boolean = false
)

/** GET /api/ar/aging */
data class ArAgingResp(
    val buckets: List<ArBucket> = emptyList(),
    val total: Double = 0.0,
    val detail: List<ArAgingRow> = emptyList()
)

/** 一张收款单（流水列表） */
data class Payment(
    val id: Int = 0,
    @SerializedName("pay_no") val payNo: String = "",
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("customer_name") val customerName: String = "",
    val amount: Double = 0.0,
    val discount: Double = 0.0,
    /** amount + discount，服务端算好的「本次核销总额」 */
    val total: Double = 0.0,
    val method: String? = null,
    @SerializedName("pay_date") val payDate: String? = null,
    val remark: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

/** GET /api/ar/payments */
data class PaymentsResp(
    val rows: List<Payment> = emptyList(),
    @SerializedName("total_in") val totalIn: Double = 0.0,
    @SerializedName("total_discount") val totalDiscount: Double = 0.0
)

/** POST /api/ar/term 返回 */
data class ArTermResp(
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("term_days") val termDays: Int = 30
)

// ---------- 报表中心：排行 / 库存预警 ----------
data class RankProduct(
    @SerializedName("product_id") val productId: Int = 0,
    @SerializedName("product_name") val productName: String = "",
    val unit: String? = null,
    val spec: String? = null,
    val qty: Double = 0.0,
    val sales: Double = 0.0,
    val profit: Double = 0.0
)

data class RankStore(
    @SerializedName("customer_id") val customerId: Int = 0,
    @SerializedName("customer_name") val customerName: String = "",
    val orders: Int = 0,
    val qty: Double = 0.0,
    val sales: Double = 0.0,
    val profit: Double = 0.0
)

/** 库存预警行（服务端已把 inventory.low_threshold 映射成 min_stock） */
data class StockAlert(
    val id: Int = 0,
    val name: String = "",
    val spec: String? = null,
    val unit: String? = null,
    val qty: Double = 0.0,
    @SerializedName("min_stock") val minStock: Double = 0.0,
    val gap: Double = 0.0
)

// ================= 采购与库存（v5.6.9；管家婆「以销定购」口径）=================
//
// 🔴 业务口径（经理 2026-10-21 明确，与网页端/服务端完全一致）：
//    建议采购量 = max(0, 净销售数量 - 已采购入库数量)
//      净销售数量 = Σ order_items.qty（销售正、退货负，自动抵消）
//      已采购入库 = Σ purchase_items.qty（status='received' 的采购单）
//    采购入库后：推导库存 = (已入库 - 已销) + (已销 - 已入库) = 0
//    ✅ **故意不依赖 inventory.qty**：账面库存有历史误差，但采购建议必须准。
//    ✅ derive_qty = max(0, 已入库 - 已销)（理论库存）；diff = 账面 - 推导（历史误差，只呈现不自动改）
//    🔴 2026-10-21 经理确认：推导库存为负按0（没进货就是 0，不是亏空）。
//      服务端 4 处口径已统一 clamp，本App 只**直接显示**服务端返回值，不做本地二次计算，
//      所以服务端改口径后无需重新发版即自动生效。

/** 采购建议行（/api/purchase-suggestions） */
data class PurchaseSuggestion(
    val id: Int = 0,
    val name: String = "",
    val spec: String? = null,
    val unit: String? = null,
    val barcode: String? = null,
    val cost: Double = 0.0,
    val price: Double = 0.0,
    /** 净销售数量（销售正、退货负） */
    @SerializedName("sold_qty") val soldQty: Double = 0.0,
    /** 已采购入库数量 */
    @SerializedName("in_qty") val inQty: Double = 0.0,
    /** 账面库存（inventory.qty） */
    @SerializedName("stock_qty") val stockQty: Double = 0.0,
    /** 建议采购量 = max(0, sold - in) */
    @SerializedName("suggest_qty") val suggestQty: Double = 0.0,
    /** 采购入库后的预计账面库存 */
    @SerializedName("stock_after") val stockAfter: Double = 0.0,
    /** 推导库存 = 已入库 - 已销 */
    @SerializedName("derive_qty") val deriveQty: Double = 0.0,
    /** 账面 - 推导 = 历史误差 */
    val diff: Double = 0.0
)

/** 库存明细行（/api/inventory/summary） */
data class InventoryRow(
    val id: Int = 0,
    val name: String = "",
    val spec: String? = null,
    val barcode: String? = null,
    val unit: String? = null,
    @SerializedName("stock_qty") val stockQty: Double = 0.0,
    @SerializedName("low_threshold") val lowThreshold: Double = 0.0,
    @SerializedName("sold_qty") val soldQty: Double = 0.0,
    @SerializedName("in_qty") val inQty: Double = 0.0,
    @SerializedName("last_move") val lastMove: String? = null,
    @SerializedName("derive_qty") val deriveQty: Double = 0.0,
    val diff: Double = 0.0
)

/** 库存流水行（/api/stock-moves） */
data class StockMove(
    val id: Int = 0,
    /** 时间戳（采购来自 stock_moves.created_at，销售来自 orders.created_at） */
    val ts: String? = null,
    @SerializedName("product_id") val productId: Int = 0,
    @SerializedName("product_name") val productName: String = "",
    /** purchase_in / purchase_void / sale_out / sale_return */
    @SerializedName("move_type") val moveType: String = "",
    val qty: Double = 0.0,
    @SerializedName("ref_type") val refType: String? = null,
    @SerializedName("ref_id") val refId: Int? = null,
    @SerializedName("ref_no") val refNo: String? = null,
    val remark: String? = null,
    val operator: String? = null,
    /** purchase / sale —— 采购侧读表，销售侧从 order_items 反推 */
    val source: String? = null
)

/** 供应商（/api/suppliers） */
data class Supplier(
    val id: Int = 0,
    val name: String = "",
    val contact: String? = null,
    val phone: String? = null,
    val remark: String? = null
)

/** 采购单（/api/purchases 列表 & /api/purchases/:id 详情头部） */
data class PurchaseOrder(
    val id: Int = 0,
    @SerializedName("purchase_no") val purchaseNo: String = "",
    @SerializedName("supplier_id") val supplierId: Int? = null,
    @SerializedName("supplier_name") val supplierName: String? = null,
    val total: Double = 0.0,
    val payable: Double = 0.0,
    val discount: Double = 1.0,
    @SerializedName("purchase_date") val purchaseDate: String? = null,
    /** received（已入库） / void（已作废） */
    val status: String = "received",
    val remark: String? = null,
    @SerializedName("placed_by_name") val placedByName: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    /** 明细行数（列表接口带；详情接口用 items.size） */
    @SerializedName("item_count") val itemCount: Int = 0
)

/** 采购单明细行 */
data class PurchaseItem(
    val id: Int = 0,
    @SerializedName("purchase_id") val purchaseId: Int = 0,
    @SerializedName("product_id") val productId: Int = 0,
    @SerializedName("product_name") val productName: String = "",
    val spec: String? = null,
    val barcode: String? = null,
    val unit: String? = null,
    val qty: Double = 0.0,
    val price: Double = 0.0,
    val subtotal: Double = 0.0
)

/** 采购单详情（头部 + 明细） */
data class PurchaseOrderDetail(
    val id: Int = 0,
    @SerializedName("purchase_no") val purchaseNo: String = "",
    @SerializedName("supplier_id") val supplierId: Int? = null,
    @SerializedName("supplier_name") val supplierName: String? = null,
    val total: Double = 0.0,
    val payable: Double = 0.0,
    val discount: Double = 1.0,
    @SerializedName("purchase_date") val purchaseDate: String? = null,
    val status: String = "received",
    val remark: String? = null,
    @SerializedName("placed_by_name") val placedByName: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    val items: List<PurchaseItem> = emptyList()
)

/** 采购单明细行（提交用） */
data class PurchaseLineReq(
    @SerializedName("product_id") val productId: Int = 0,
    val qty: Double = 0.0,
    /** 进价；null = 服务端用商品表 cost */
    val price: Double? = null
)

/** 新建采购单请求（建单即入库） */
data class CreatePurchaseRequest(
    @SerializedName("supplier_id") val supplierId: Int? = null,
    @SerializedName("supplier_name") val supplierName: String? = null,
    val items: List<PurchaseLineReq> = emptyList(),
    val discount: Double = 1.0,
    @SerializedName("purchase_date") val purchaseDate: String? = null,
    val remark: String? = null
)

/** 新建采购单返回 */
data class PurchaseCreated(
    val id: Int = 0,
    @SerializedName("purchase_no") val purchaseNo: String = "",
    val total: Double = 0.0,
    val payable: Double = 0.0,
    @SerializedName("item_count") val itemCount: Int = 0
)

