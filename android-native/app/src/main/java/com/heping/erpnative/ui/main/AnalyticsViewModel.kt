package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.AnalyticsResp
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.ShipmentDaily
import com.heping.erpnative.data.isNetworkError
import com.heping.erpnative.data.userMessage
import kotlinx.coroutines.launch
import java.util.Calendar

class AnalyticsViewModel : ViewModel() {
    var range by mutableStateOf("today")
    var from by mutableStateOf(todayStr())
    var to by mutableStateOf(todayStr())
    var data by mutableStateOf<AnalyticsResp?>(null)
    var ship by mutableStateOf<ShipmentDaily?>(null)
    var shipDate by mutableStateOf(todayStr())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // 🔴 报表中心：商品排行 / 门店排行（rankLoaded 供页签懒加载判断，避免反复拉）
    var rankProducts by mutableStateOf<List<com.heping.erpnative.data.RankProduct>>(emptyList())
    var rankStores by mutableStateOf<List<com.heping.erpnative.data.RankStore>>(emptyList())
    var rankLoaded by mutableStateOf(false)
    var reportLoading by mutableStateOf(false)

    fun loadRanks(from: String? = null, to: String? = null) {
        viewModelScope.launch {
            reportLoading = true; error = null
            try {
                rankProducts = ErpClient.api.reportProducts(from, to)
                rankStores = ErpClient.api.reportStores(from, to)
                rankLoaded = true
            } catch (e: Exception) {
                error = if (isNetworkError(e)) "网络异常，请检查连接" else e.userMessage()
            } finally { reportLoading = false }
        }
    }

    fun chooseRange(r: String) {
        range = r
        if (r != "custom") load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                data = ErpClient.api.analytics(
                    range,
                    if (range == "custom") from else null,
                    if (range == "custom") to else null
                )
            } catch (e: Exception) {
                error = if (isNetworkError(e)) "网络异常，请检查连接" else e.userMessage()
            } finally { loading = false }
        }
    }

    fun loadShipments() {
        viewModelScope.launch {
            loading = true; error = null
            try { ship = ErpClient.api.shipmentsDaily(shipDate) }
            catch (e: Exception) {
                error = if (isNetworkError(e)) "网络异常，请检查连接" else e.userMessage()
            } finally { loading = false }
        }
    }

    companion object {
        fun todayStr(): String {
            val c = Calendar.getInstance()
            val m = c.get(Calendar.MONTH) + 1
            val d = c.get(Calendar.DAY_OF_MONTH)
            return "" + c.get(Calendar.YEAR) + "-" + (if (m < 10) "0" else "") + m + "-" + (if (d < 10) "0" else "") + d
        }
    }
}
