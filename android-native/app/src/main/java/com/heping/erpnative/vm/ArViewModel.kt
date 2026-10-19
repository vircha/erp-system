package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * 应收应付（管家婆式：挂账 + 收款单 + 核销）。
 *
 * 🔴🔴 自动分配口径（与服务端 / 网页版完全一致）：
 *   **按未结单日期升序，从最早那张开始结清，结完溢出到下一单。**
 *   服务端 `unsettledOrders` 已用 `ORDER BY 日期 ASC, id ASC` 排好序，
 *   这里直接顺着 list 顺序分配即可，**不要在前端重排**。
 */
class ArViewModel : ViewModel() {
    // ---- 汇总 ----
    var summary by mutableStateOf(ArSummaryResp())
    var summaryLoading by mutableStateOf(false)

    // ---- 某门店的未结清单 ----
    var unsettled by mutableStateOf<ArUnsettledResp?>(null)
    var unsettledLoading by mutableStateOf(false)

    // ---- 收款提交 ----
    var paying by mutableStateOf(false)

    // ---- 报表 ----
    var aging by mutableStateOf<ArAgingResp?>(null)
    var agingLoading by mutableStateOf(false)
    var payments by mutableStateOf<PaymentsResp?>(null)
    var paymentsLoading by mutableStateOf(false)
    var rankProducts by mutableStateOf<List<RankProduct>>(emptyList())
    var rankStores by mutableStateOf<List<RankStore>>(emptyList())
    var stockAlert by mutableStateOf<List<StockAlert>>(emptyList())
    var reportLoading by mutableStateOf(false)

    var error by mutableStateOf<String?>(null)

    fun clearError() { error = null }

    fun loadSummary() {
        viewModelScope.launch {
            summaryLoading = true; error = null
            try { summary = ErpClient.api.arSummary() }
            catch (e: Exception) { error = errMsg(e) }
            finally { summaryLoading = false }
        }
    }

    fun loadUnsettled(cid: Int) {
        viewModelScope.launch {
            unsettledLoading = true; error = null
            try { unsettled = ErpClient.api.arUnsettled(cid) }
            catch (e: Exception) { error = errMsg(e); unsettled = null }
            finally { unsettledLoading = false }
        }
    }

    fun loadAging(cid: Int? = null) {
        viewModelScope.launch {
            agingLoading = true; error = null
            try { aging = ErpClient.api.arAging(cid) }
            catch (e: Exception) { error = errMsg(e) }
            finally { agingLoading = false }
        }
    }

    fun loadPayments(cid: Int? = null) {
        viewModelScope.launch {
            paymentsLoading = true; error = null
            try { payments = ErpClient.api.arPayments(cid) }
            catch (e: Exception) { error = errMsg(e) }
            finally { paymentsLoading = false }
        }
    }

    fun loadStockAlert() {
        viewModelScope.launch {
            reportLoading = true; error = null
            try { stockAlert = ErpClient.api.reportStockAlert() }
            catch (e: Exception) { error = errMsg(e) }
            finally { reportLoading = false }
        }
    }

    fun loadRanks(from: String? = null, to: String? = null) {
        viewModelScope.launch {
            reportLoading = true; error = null
            try {
                rankProducts = ErpClient.api.reportProducts(from, to)
                rankStores = ErpClient.api.reportStores(from, to)
            } catch (e: Exception) { error = errMsg(e) }
            finally { reportLoading = false }
        }
    }

    /** 设置门店账期（0-365 天），成功后重拉汇总与未结清单 */
    fun setTerm(cid: Int, days: Int, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            error = null
            try {
                ErpClient.api.arSetTerm(mapOf("customer_id" to cid, "term_days" to days))
                loadSummary()
                if (unsettled?.customerId == cid) loadUnsettled(cid)
                onDone(true)
            } catch (e: Exception) { error = errMsg(e); onDone(false) }
        }
    }

    /**
     * 提交收款单。
     * @param items 核销明细（UI 层负责自动分配或人工调整）
     * @return 成功时返回生成的 SK 单号
     */
    fun pay(
        cid: Int, amount: Double, discount: Double,
        method: String, payDate: String, remark: String,
        items: List<ArPayItem>,
        onDone: (String?) -> Unit
    ) {
        viewModelScope.launch {
            paying = true; error = null
            try {
                val r = ErpClient.api.arPay(
                    ArPayRequest(
                        customerId = cid, amount = amount, discount = discount,
                        method = method, payDate = payDate, remark = remark, items = items
                    )
                )
                // 收款后刷新：汇总 + 该门店未结清单
                loadSummary()
                loadUnsettled(cid)
                onDone(r.payNo)
            } catch (e: Exception) { error = errMsg(e); onDone(null) }
            finally { paying = false }
        }
    }

    /** 撤销收款单：核销金额退回 orders.settled */
    fun undoPay(id: Int, cid: Int?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            error = null
            try {
                ErpClient.api.arPayUndo(id)
                loadSummary()
                if (cid != null && unsettled?.customerId == cid) loadUnsettled(cid)
                if (payments != null) loadPayments(unsettled?.customerId)
                onDone(true)
            } catch (e: Exception) { error = errMsg(e); onDone(false) }
        }
    }

    companion object {
        fun errMsg(e: Exception): String =
            if (isNetworkError(e)) "网络异常，请检查连接" else e.userMessage()

        fun ymd(d: Calendar): String = String.format(
            Locale.US, "%04d-%02d-%02d",
            d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH)
        )

        fun todayStr(): String = ymd(Calendar.getInstance())

        /**
         * 🔴 管家婆式自动分配：**从最早那张未结单开始结清，结完溢出到下一单**。
         *
         * @param orders 服务端已按日期升序返回的未结清单（顺序即优先级）
         * @param total  本次收款实收 + 优惠总额
         * @return orderId → 该单分到的金额（含优惠，按 earliest-first 溢出）
         */
        fun autoAlloc(orders: List<ArOrder>, total: Double): Map<Int, Double> {
            val out = LinkedHashMap<Int, Double>()
            if (total <= 0.005) return out
            var left = round2(total)
            for (o in orders) {
                if (left <= 0.005) break
                val use = round2(minOf(left, o.left))
                if (use <= 0.005) continue
                out[o.orderId] = use
                left = round2(left - use)
            }
            return out
        }

        fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}