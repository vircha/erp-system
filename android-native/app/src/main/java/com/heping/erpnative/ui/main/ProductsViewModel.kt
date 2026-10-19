package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heping.erpnative.data.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.CacheStore
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.Product
import com.heping.erpnative.data.isNetworkError
import kotlinx.coroutines.launch

class ProductsViewModel : ViewModel() {
    var list by mutableStateOf<List<Product>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var offline by mutableStateOf(false)

    fun load(q: String? = null) {
        viewModelScope.launch {
            loading = true; offline = false
            try {
                val r = ErpClient.api.products(q?.takeIf { it.isNotBlank() })
                list = r
                CacheStore.putProducts(r)
            } catch (e: Exception) {
                offline = isNetworkError(e)
                error = if (offline) null else e.userMessage()
                if (list.isEmpty()) list = CacheStore.getProducts()
            } finally { loading = false }
        }
    }

    suspend fun save(p: Product, isNew: Boolean): Boolean {
        return try {
            saving = true
            if (isNew) ErpClient.api.createProduct(p) else ErpClient.api.updateProduct(p.id, p)
            load()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
        finally { saving = false }
    }

    suspend fun delete(id: Int): Boolean {
        return try {
            ErpClient.api.deleteProduct(id)
            // 就地移除该项，不再整列重新拉取（避免删一个就全列表刷新）
            list = list.filter { it.id != id }
            CacheStore.putProducts(list)
            true
        } catch (e: Exception) { error = e.userMessage(); false }
    }
}
