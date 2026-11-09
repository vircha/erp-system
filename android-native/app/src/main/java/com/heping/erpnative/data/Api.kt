package com.heping.erpnative.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import com.heping.erpnative.BuildConfig
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// ---------- 会话持久化（DataStore） ----------
private val Context.dataStore by preferencesDataStore(name = "erp_session")

object SessionStore {
    private val TOKEN = stringPreferencesKey("token")
    private val DEVICE = stringPreferencesKey("device_token")
    private val ROLE = stringPreferencesKey("role")
    private val PERMS = stringPreferencesKey("perms")   // 逗号分隔
    private val BOOK_ID = longPreferencesKey("book_id")
    private val BOOK_NAME = stringPreferencesKey("book_name")
    private val SHOP = stringPreferencesKey("shop_name")
    private val THEME = intPreferencesKey("theme_mode")  // 0=跟随系统 1=浅色 2=深色
    private val DISPLAY = stringPreferencesKey("display_name")      // 经手人（打印小票用）
    private val WAREHOUSE = stringPreferencesKey("receipt_warehouse") // 出库仓库（打印小票用）
    private val HANDLER = stringPreferencesKey("receipt_handler")     // 经手人（打印小票用，空=用登录账号名）
    private val CUSTOMER_ID = intPreferencesKey("customer_id")        // 客户账号绑定的门店（0=无）
    // 2026-10-19：客户下单员可负责多家门店，这里存全部授权门店 id（逗号分隔）
    private val CUSTOMER_IDS = stringPreferencesKey("customer_ids")
    // 当前正在下单的门店 id（2026-10-19 多门店切换器用；非法值会被回落成默认门店）
    private val SALE_STORE = intPreferencesKey("sale_store")

    /** 出库仓库默认值（与纸质送货单一致） */
    const val DEFAULT_WAREHOUSE = "天河仓"

    private lateinit var ctx: Context

    @Volatile var token: String? = null
        private set
    @Volatile var deviceToken: String? = null
        private set

    // 内存态（供 UI 直接读取，免每次查库）
    // 🔴🔴 2026-10-29：role / perms 必须是 **Compose 响应式状态**。
    // 原因：OrdersScreen 在组合期读 SessionStore.can("order.edit") 来决定是否渲染「编辑」按钮；
    // 如果 perms 是普通 var，当 perms 晚于首帧组合到达（restore() 未完成 / 401 续期异步写入），
    // canEdit 会被固化成 false 且**永不重算** → 表现为「编辑按钮不见了」。
    // 改成 mutableStateOf 后，任何写入都会触发重组，按钮随之出现。
    var role: String by mutableStateOf("staff")
    var perms: List<String> by mutableStateOf(emptyList())
    var bookId: Int = 1
    var bookName: String = ""
    var shopName: String = ""
    var themeMode: Int = 0   // 0=跟随系统 1=浅色 2=深色
    var displayName: String = ""                       // 打印小票「经手人」
    var receiptWarehouse: String = DEFAULT_WAREHOUSE   // 打印小票「出库仓库」
    var receiptHandler: String = ""                    // 打印小票「经手人」固定值（空=用登录账号名）
    /** 客户账号绑定的门店 id（0 = 非客户账号 / 未绑定）；多门店下这是**默认门店** */
    var customerId: Int = 0

    /**
     * 2026-10-19 客户下单员可负责多家门店。
     * 这是他被授权的**全部**门店；[customerId] 只是默认门店。
     * 🔴 所有数据隔离判断都要查这个集合，比等号安全 —— 单值只当「没指定时的回落值」。
     */
    var customerIds: List<Int> = emptyList()

    /** 当前下单的门店（多门店切换器选中项）；非法值会在读取时回落成默认门店 */
    var saleStore: Int = 0
        get() {
            if (!isCustomer) return 0
            val ids = customerIds.ifEmpty { listOf(customerId) }.filter { it > 0 }
            return if (ids.contains(field)) field else (customerId.takeIf { ids.contains(it) } ?: ids.firstOrNull() ?: 0)
        }

    val isBoss get() = role == "boss"
    val isManager get() = role == "boss" || role == "manager"
    /** 客户下单角色：只能给自己门店下单 + 看自己的订单/欠款 */
    val isCustomer get() = role == "customer"

    /** 客户当前正在下单的门店是否归他管（服务端还会再校验一次，这里只管界面别给错选项） */
    fun custStoreAllowed(id: Int): Boolean =
        if (!isCustomer) true else customerIds.ifEmpty { listOf(customerId) }.contains(id)

    fun can(perm: String): Boolean = perms.contains("*") || perms.contains(perm)

    fun init(context: Context) { ctx = context.applicationContext }
    val appContext: Context get() = ctx

    suspend fun saveAuth(a: AuthResponse) {
        token = a.token
        deviceToken = a.deviceToken
        role = a.role
        perms = a.perms
        bookId = a.book?.id ?: 1
        bookName = a.book?.name ?: ""
        shopName = a.shopName
        customerId = a.customerId ?: a.account?.customerId ?: 0
        // 2026-10-19 多门店：服务端两个来源都给（顶层 customerIds / account.customer_ids）
        customerIds = (a.customerIds.ifEmpty { a.account?.customerIds ?: emptyList() })
            .filter { it > 0 }.ifEmpty { listOfNotNull(customerId.takeIf { i -> i > 0 }) }
        displayName = a.account?.display_name?.takeIf { it.isNotBlank() } ?: a.account?.username ?: ""
        ctx.dataStore.edit {
            it[TOKEN] = Crypto.encrypt(a.token)
            if (a.deviceToken != null) it[DEVICE] = Crypto.encrypt(a.deviceToken)
            it[ROLE] = a.role
            it[PERMS] = a.perms.joinToString(",")
            it[CUSTOMER_ID] = customerId
            it[CUSTOMER_IDS] = customerIds.joinToString(",")
            it[BOOK_ID] = (a.book?.id ?: 1).toLong()
            it[BOOK_NAME] = a.book?.name ?: ""
            it[SHOP] = a.shopName
            it[DISPLAY] = displayName
        }
    }

    /** 启动时尝试从持久化恢复登录态；返回是否已有会话（或有设备令牌可用） */
    suspend fun restore(): Boolean {
        val p = ctx.dataStore.data.first()
        // 🔴🔴 2026-11-07 修复「信任设备不起作用、每次都要输密码」：
        //   原实现是 `val t = p[TOKEN] ?: return false` —— 没有**会话令牌**就直接返回，
        //   于是 deviceToken 永远没被读出来 → 上层拿不到设备令牌 → 免密彻底失效。
        //   只要发生过一次「会话被清」（退出登录会保留设备令牌但删掉 TOKEN；
        //   服务器重启会让旧 TOKEN 失效后走到清会话分支），之后就再也免密不了。
        //   正确顺序：**先读设备令牌**，再读会话令牌；两者有其一就算可恢复。
        deviceToken = p[DEVICE]?.let { try { Crypto.decrypt(it) } catch (_: Exception) { null } }
        val t = p[TOKEN]
        token = t?.let { try { Crypto.decrypt(it) } catch (_: Exception) { null } }
        if (token == null && deviceToken == null) return false
        role = p[ROLE] ?: "staff"
        perms = (p[PERMS] ?: "").split(",").filter { it.isNotBlank() }
        bookId = (p[BOOK_ID] ?: 1L).toInt()
        bookName = p[BOOK_NAME] ?: ""
        shopName = p[SHOP] ?: ""
        themeMode = p[THEME] ?: 0
        displayName = p[DISPLAY] ?: ""
        receiptWarehouse = p[WAREHOUSE]?.takeIf { it.isNotBlank() } ?: DEFAULT_WAREHOUSE
        receiptHandler = p[HANDLER] ?: ""
        customerId = p[CUSTOMER_ID] ?: 0
        // 2026-10-19：老版本升级上来时 CUSTOMER_IDS 是空的 → 回落成默认门店，不能是空集合
        customerIds = (p[CUSTOMER_IDS] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .ifEmpty { listOfNotNull(customerId.takeIf { i -> i > 0 }) }
        saleStore = p[SALE_STORE] ?: 0
        return true
    }

    /** 缓存打印用的设置（出库仓库 / 经手人）；拉完 /api/settings 后调用 */
    suspend fun saveReceiptMeta(warehouse: String, handler: String = "") {
        receiptWarehouse = warehouse.ifBlank { DEFAULT_WAREHOUSE }
        receiptHandler = handler
        ctx.dataStore.edit { it[WAREHOUSE] = receiptWarehouse; it[HANDLER] = receiptHandler }
    }

    /** 找回当前账号名（经手人）：登录态恢复后若为空，用 /api/me 兜底 */
    suspend fun saveDisplayName(name: String) {
        if (name.isBlank()) return
        displayName = name
        ctx.dataStore.edit { it[DISPLAY] = name }
    }

    suspend fun saveProfile(
        role: String, perms: List<String>, bookId: Int, bookName: String, shopName: String,
        customerId: Int = this.customerId,
        customerIds: List<Int> = this.customerIds
    ) {
        this.role = role; this.perms = perms; this.bookId = bookId; this.bookName = bookName; this.shopName = shopName
        this.customerId = customerId
        // 传空列表 = 这次没带（如切套账后重登），保留原集合，别把门店授权清空
        val ids = customerIds.filter { it > 0 }.ifEmpty { this.customerIds.filter { i -> i > 0 } }
            .ifEmpty { listOfNotNull(customerId.takeIf { i -> i > 0 }) }
        this.customerIds = ids
        ctx.dataStore.edit {
            it[ROLE] = role; it[PERMS] = perms.joinToString(",")
            it[BOOK_ID] = bookId.toLong(); it[BOOK_NAME] = bookName; it[SHOP] = shopName
            it[CUSTOMER_ID] = customerId
            it[CUSTOMER_IDS] = ids.joinToString(",")
        }
    }

    /** 2026-10-19 切换当前下单门店（多门店切换器调用） */
    suspend fun setSaleStore(id: Int) {
        if (!custStoreAllowed(id)) return          // 不属于他的门店，忽略
        saleStore = id
        ctx.dataStore.edit { it[SALE_STORE] = id }
    }

    /** 持久化主题模式（0=跟随系统 1=浅色 2=深色） */
    suspend fun saveThemeMode(mode: Int) {
        themeMode = mode
        ctx.dataStore.edit { it[THEME] = mode }
    }

    suspend fun clear() {        role = "staff"; perms = emptyList(); bookId = 1; bookName = ""; shopName = ""
        displayName = ""; receiptWarehouse = DEFAULT_WAREHOUSE; receiptHandler = ""; customerId = 0
        customerIds = emptyList(); saleStore = 0
        ctx.dataStore.edit { it.clear() }
    }

    /** 手动退出：只清会话态，保留设备令牌（退出 ≠ 解绑，信任设备 30 天内仍免密） */
    suspend fun logoutKeepDevice() {
        token = null
        role = "staff"; perms = emptyList(); bookId = 1; bookName = ""; shopName = ""; displayName = ""
        // 🔴 原来忘了清 customerId：退出登录后内存里还留着上一个客户的门店 id，
        //    下一个（不同门店的）账号登进来时可能被当成自己的门店。2026-10-19 一并清掉。
        customerId = 0; customerIds = emptyList(); saleStore = 0
        ctx.dataStore.edit {
            it.remove(TOKEN); it.remove(ROLE); it.remove(PERMS)
            it.remove(BOOK_ID); it.remove(BOOK_NAME); it.remove(SHOP); it.remove(DISPLAY)
            it.remove(CUSTOMER_ID); it.remove(CUSTOMER_IDS); it.remove(SALE_STORE)
        }
    }

    /**
     * v5.5：丢弃设备令牌（免密续期失败 / 用户点「关闭本设备的免密信任」后调用）。
     * 只删 DEVICE，不动主题、打印机等本地偏好。
     */
    suspend fun forgetDevice() {
        deviceToken = null
        ctx.dataStore.edit { it.remove(DEVICE) }
    }
}

// ---------- Retrofit 接口 ----------
interface ErpApi {
    @GET("api/captcha")
    suspend fun captcha(): CaptchaResponse

    @POST("api/login/precheck")
    suspend fun precheck(@Body b: Map<String, String>): PrecheckResponse

    @POST("api/login")
    suspend fun login(@Body b: LoginRequest): AuthResponse

    // 🔴 2026-11-07：改用强类型 RegisterRequest。
    //   原来传 Map<String,String> → trust_device 只能写 "0"/"1" 字符串，
    //   而服务端是 `if (b.trust_device)` 判真值 → "0" 也是真 → 永远被信任。必须用 Boolean。
    // 短信验证码（2026-11-08）
    @GET("api/sms/status")
    suspend fun smsStatus(): SmsStatus

    @POST("api/sms/send")
    suspend fun sendSms(@Body b: Map<String, String>): SmsSendResp

    // 邮箱验证码（2026-11-09 主通道）
    @GET("api/email/status")
    suspend fun emailStatus(): SmsStatus

    @POST("api/email/send")
    suspend fun sendEmailCode(@Body b: Map<String, String>): SmsSendResp

    @POST("api/register")
    suspend fun register(@Body b: RegisterRequest): AuthResponse

    @POST("api/trusted-login")
    suspend fun trustedLogin(@Body b: Map<String, String>): AuthResponse

    @GET("api/me")
    suspend fun me(): MeResponse

    @GET("api/books/my")
    suspend fun myBooks(): List<Book>

    // 客户
    @GET("api/customers")
    suspend fun customers(@Query("q") q: String? = null): List<Customer>

    @POST("api/customers")
    suspend fun createCustomer(@Body b: Customer): IdResponse

    @PUT("api/customers/{id}")
    suspend fun updateCustomer(@Path("id") id: Int, @Body b: Customer): OkResponse

    @DELETE("api/customers/{id}")
    suspend fun deleteCustomer(@Path("id") id: Int): OkResponse

    // 商品
    @GET("api/products")
    suspend fun products(
        @Query("q") q: String? = null,
        // 🔴 2026-10-19 客户多门店：必须带上当前门店，服务端才返回**那家店**的历史送货价。
        //    不带的话客户切了门店，加进购物车的还是默认门店的价格。
        @Query("customer_id") customerId: Int? = null
    ): List<Product>

    @POST("api/products")
    suspend fun createProduct(@Body b: Product): IdResponse

    @PUT("api/products/{id}")
    suspend fun updateProduct(@Path("id") id: Int, @Body b: Product): OkResponse

    @DELETE("api/products/{id}")
    suspend fun deleteProduct(@Path("id") id: Int): OkResponse

    // 🔴 2026-11-07 商品图片：走 base64 JSON（项目无 multer，手写 multipart 易出边界 bug）。
    //    客户端已把图压到 ~200KB，base64 后仍远低于服务端 10MB 上限。
    @POST("api/products/{id}/image")
    suspend fun uploadProductImage(@Path("id") id: Int, @Body b: Map<String, String>): Map<String, String>

    @DELETE("api/products/{id}/image")
    suspend fun deleteProductImage(@Path("id") id: Int): OkResponse

    // 🔴 2026-11-07 平台图库匹配：按条码（优先）/名称（模糊）在**全平台**找同类商品图。
    //    服务端只回一个本地图片地址，**不透露图来自哪个账套/用户**（匹配全在后台做）。
    @POST("api/product-image/match")
    suspend fun matchProductImage(@Body b: Map<String, String>): Map<String, String>

    // 开单 / 订单
    @POST("api/orders")
    suspend fun createOrder(@Body b: CreateOrderRequest): OrderCreated

    @GET("api/orders")
    suspend fun orders(
        @Query("date") date: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("status") status: String? = null,
        @Query("min") min: String? = null,
        @Query("max") max: String? = null
    ): List<Order>

    /** 开单模板（行=门店、列=产品、单位 EA、带汇总公式）——流式下载 */
    @Streaming
    @GET("api/orders/template")
    suspend fun orderTemplate(): okhttp3.ResponseBody

    @GET("api/orders/{id}")
    suspend fun orderDetail(@Path("id") id: Int): OrderDetail

    @PUT("api/orders/{id}")
    suspend fun updateOrder(@Path("id") id: Int, @Body b: CreateOrderRequest): OrderCreated

    @DELETE("api/orders/{id}")
    suspend fun deleteOrder(@Path("id") id: Int): OkResponse

    @POST("api/orders/delete-batch")
    suspend fun deleteOrdersBatch(@Body b: Map<String, List<Int>>): BatchDeleteResp

    @POST("api/orders/{id}/return")
    suspend fun returnOrder(@Path("id") id: Int): ReturnedOrder

    @POST("api/orders/{id}/return-items")
    suspend fun returnOrderItems(@Path("id") id: Int, @Body b: ReturnItemsRequest): ReturnedOrder

    @GET("api/customer-prices")
    suspend fun customerPrices(@Query("customer_id") cid: Int): CustomerPriceResp

    // ---------- 应收应付（管家婆式挂账 + 收款核销） ----------
    /** 全部门店欠款汇总（报表 + 客户页徽章） */
    @GET("api/ar/summary")
    suspend fun arSummary(): ArSummaryResp

    /** 某门店未结清单（**服务端已按日期升序**=自动分配最早优先的依据） */
    @GET("api/ar/unsettled")
    suspend fun arUnsettled(@Query("customer_id") cid: Int): ArUnsettledResp

    /** 收款单：核销到具体销售单 */
    @POST("api/ar/pay")
    suspend fun arPay(@Body b: ArPayRequest): ArPayResp

    /** 撤销收款单（把核销金额退回 orders.settled） */
    @DELETE("api/ar/pay/{id}")
    suspend fun arPayUndo(@Path("id") id: Int): OkResponse

    /** 收款流水（不传 customer_id = 全部） */
    @GET("api/ar/payments")
    suspend fun arPayments(@Query("customer_id") cid: Int? = null): PaymentsResp

    /** 账龄分析四档 + 明细 */
    @GET("api/ar/aging")
    suspend fun arAging(@Query("customer_id") cid: Int? = null): ArAgingResp

    /** 设置门店账期天数（默认 30） */
    @POST("api/ar/term")
    suspend fun arSetTerm(@Body b: Map<String, Any>): ArTermResp

    // ---------- 报表中心 ----------
    @GET("api/report/products")
    suspend fun reportProducts(@Query("from") from: String? = null, @Query("to") to: String? = null): List<RankProduct>

    @GET("api/report/stores")
    suspend fun reportStores(@Query("from") from: String? = null, @Query("to") to: String? = null): List<RankStore>

    @GET("api/report/stock-alert")
    suspend fun reportStockAlert(): List<StockAlert>

    // 设置
    @GET("api/settings")
    suspend fun settings(): Settings

    @PUT("api/settings")
    suspend fun updateSettings(@Body b: Map<String, String>): SettingsOk

    @PUT("api/settings/password")
    suspend fun changePassword(@Body b: Map<String, String>): OkResponse

    // 统计
    @GET("api/stats")
    suspend fun stats(): Stats

    // ---------- 资金管理（2026-11-04） ----------
    @GET("api/funds")
    suspend fun funds(@Query("from") from: String? = null, @Query("to") to: String? = null,
                      @Query("direction") direction: String? = null): FundsResp

    @POST("api/funds")
    suspend fun addFund(@Body body: FundBody): Map<String, Any>

    @DELETE("api/funds/{id}")
    suspend fun deleteFund(@Path("id") id: Int): Map<String, Any>

    // ---------- 员工提成（2026-11-04） ----------
    @GET("api/commission/config")
    suspend fun commissionConfig(): CommissionRateResp

    @PUT("api/commission/config")
    // 🔴 body 必须 Any：全局设置传 {rate, mode}，按员工设置传 {account_id, mode, rate}，
    //    删除专属规则传 {account_id}。用 Map<String, Double> 会把 mode/account_id 挡在编译期外。
    suspend fun setCommissionRate(@Body body: Map<String, Any>): Map<String, Any>

    @GET("api/commission/mine")
    suspend fun commissionMine(@Query("from") from: String? = null, @Query("to") to: String? = null): CommissionMineResp

    @GET("api/commission/all")
    suspend fun commissionAll(@Query("from") from: String? = null, @Query("to") to: String? = null): CommissionAllResp

    @GET("api/analysis/monthly")
    suspend fun monthly(): List<Monthly>

    @GET("api/analytics")
    suspend fun analytics(
        @Query("range") range: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): AnalyticsResp

    @GET("api/shipments/daily")
    suspend fun shipmentsDaily(@Query("date") date: String): ShipmentDaily

    // 信任设备
    // 🔴 1.5.1：接口返回结构从数组改成 { devices, max, used, left }（多了上限信息）。
    //   必须同步改这里 —— 否则 Gson 会把整个对象解析成 List，直接抛
    //   JsonSyntaxException，设置页一打开就崩。
    @GET("api/trusted-devices")
    suspend fun trustedDevices(): TrustedDevicesResponse

    @DELETE("api/trusted-devices/{id}")
    suspend fun deleteTrusted(@Path("id") id: Int): OkResponse

    @DELETE("api/trusted-devices")
    suspend fun deleteAllTrusted(@Query("keep_token") keepToken: String? = null): OkResponse

    @POST("api/books/switch")
    suspend fun switchBook(@Body b: Map<String, Int>): BookSwitchResponse

    // ---------- 账套管理（v5.7.4：手机端补齐网页版已有的账套增/改） ----------
    // 🔴 全部仅 boss 可用，服务端会再校验一次；前端也按角色隐藏入口。
    @POST("api/books")
    suspend fun createBook(@Body b: Map<String, String>): BookCreateResponse

    /** 加入口令：传空串 = 清除口令（网页版「清除」按钮走同一个接口） */
    @PUT("api/books/join-password")
    suspend fun setJoinPassword(@Body b: Map<String, String>): JoinPwdResponse

    @GET("api/books/invite")
    suspend fun bookInvite(): BookInviteResponse

    @POST("api/books/invite/reset")
    suspend fun resetInvite(): InviteResetResponse

    /** 删除账套：需传 confirm_name = 账套名；服务端会级联清空该账套全部数据 */
    @POST("api/books/delete")
    suspend fun deleteBook(@Body b: Map<String, String>): BookDeleteResponse

    // ---------- 采购与库存（v5.6.9；管家婆「以销定购」）----------
    // 🔴 口径：建议采购量 = max(0, 净销售数量 - 已采购入库数量)，采购入库后推导库存 = 0。
    //    与网页端/服务端完全一致，手机端只是同一套接口的另一张皮。

    @GET("api/suppliers")
    suspend fun suppliers(): List<Supplier>

    @POST("api/suppliers")
    suspend fun createSupplier(@Body b: Map<String, String>): IdResponse

    /** 采购建议（以销定购） */
    @GET("api/purchase-suggestions")
    suspend fun purchaseSuggestions(): List<PurchaseSuggestion>

    /** 库存明细（账面 / 推导 / 差异 三口径）；only = neg | diff | alert */
    @GET("api/inventory/summary")
    suspend fun inventorySummary(
        @Query("kw") kw: String? = null,
        @Query("only") only: String? = null
    ): List<InventoryRow>

    /** 库存流水（采购读 stock_moves，销售从 order_items 反推） */
    @GET("api/stock-moves")
    suspend fun stockMoves(@Query("product_id") productId: Int? = null): List<StockMove>

    @GET("api/purchases")
    suspend fun purchases(): List<PurchaseOrder>

    /** 新建采购单（建单即入库） */
    @POST("api/purchases")
    suspend fun createPurchase(@Body b: CreatePurchaseRequest): PurchaseCreated

    @GET("api/purchases/{id}")
    suspend fun purchaseDetail(@Path("id") id: Int): PurchaseOrderDetail

    /** 作废采购单（回退库存） */
    @DELETE("api/purchases/{id}")
    suspend fun voidPurchase(@Path("id") id: Int): OkResponse
}

// ---------- 客户端单例 ----------
object ErpClient {
    private const val BASE = BuildConfig.API_BASE
    /** 401 会话失效且无设备令牌可续期时，由 AppNav 注入此回调切回登录页 */
    var onForceLogout: (() -> Unit)? = null

    val api: ErpApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val t = SessionStore.token
                val req = if (t != null) original.newBuilder().header("Authorization", "Bearer $t").build() else original
                val resp = chain.proceed(req)
                // 会话失效：尝试用持久化的设备令牌静默续期（覆盖服务器重启清空内存会话的情况）
                if (resp.code == 401 && original.header("X-Erp-Retry") == null
                    && !original.url.encodedPath.endsWith("/api/trusted-login")) {
                    val dt = SessionStore.deviceToken
                    if (dt != null) {
                        try {
                            val refreshed = runBlocking { ErpClient.api.trustedLogin(mapOf("device_token" to dt)) }
                            runBlocking { SessionStore.saveAuth(refreshed) }
                            resp.close()
                            val retry = original.newBuilder()
                                .header("Authorization", "Bearer ${refreshed.token}")
                                .header("X-Erp-Retry", "1")
                                .build()
                            return@Interceptor chain.proceed(retry)
                        } catch (_: Exception) { /* 续期失败，落到下面处理 */ }
                    }
                    // 🔴 2026-11-07：续期失败只清「会话」，**必须保留设备令牌**。
                    //   原来调的是 clear()（连 DEVICE 一起抹），等于一次偶发失败就把这台设备
                    //   永久踢出信任名单 —— 下次必须重新输密码，正是经理反馈的现象。
                    runBlocking { SessionStore.logoutKeepDevice() }
                    onForceLogout?.invoke()
                }
                resp
            })
            .build()
        Retrofit.Builder()
            .baseUrl(BASE)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ErpApi::class.java)
    }
}

// ---------- 错误解析 ----------
private val gson = Gson()

fun Throwable.userMessage(): String {
    if (this is HttpException) {
        return try {
            val body = this.response()?.errorBody()?.string()
            if (!body.isNullOrEmpty()) {
                val m = gson.fromJson(body, Map::class.java)
                (m["error"] as? String) ?: body
            } else "请求失败（${this.code()}）"
        } catch (e: Exception) { "请求失败（${this.code()}）" }
    }
    return this.message ?: "未知错误"
}

// 简易日志
fun logd(tag: String, msg: String) { Log.d("ErpNative/$tag", msg) }

/** 仅真正的网络层异常（断网/超时/无法连接）才算「离线」；HTTP 错误（如 401 登录失效）不算，避免在线却误报离线 */
fun isNetworkError(e: Throwable): Boolean = e is java.io.IOException
