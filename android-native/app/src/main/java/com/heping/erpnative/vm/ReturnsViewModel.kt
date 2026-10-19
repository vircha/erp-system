package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.CacheStore
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.Order
import com.heping.erpnative.data.OrderDetail
import com.heping.erpnative.data.userMessage
import kotlinx.coroutines.launch

class ReturnsViewModel : ViewModel() {
    var returns by mutableStateOf<List<Order>>(emptyList())
    var detail by mutableStateOf<OrderDetail?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false)

    fun loadReturns() {
        viewModelScope.launch {
            loading = true; error = null; offline = false
            try {
                returns = ErpClient.api.orders(status = "returned")
                CacheStore.putReturns(returns)
            } catch (e: Exception) {
                val cached = try { CacheStore.getReturns() } catch (_: Exception) { emptyList() }
                if (cached.isNotEmpty()) { returns = cached; offline = true } else error = e.userMessage()
            } finally { loading = false }
        }
    }

    fun loadDetail(id: Int) {
        viewModelScope.launch {
            try { detail = ErpClient.api.orderDetail(id) }
            catch (e: Exception) { error = e.userMessage() }
        }
    }

    fun clearDetail() { detail = null }
}
