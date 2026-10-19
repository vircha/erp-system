package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heping.erpnative.data.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.CacheStore
import com.heping.erpnative.data.Customer
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.isNetworkError
import kotlinx.coroutines.launch

class CustomersViewModel : ViewModel() {
    var list by mutableStateOf<List<Customer>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var offline by mutableStateOf(false)

    fun load(q: String? = null) {
        viewModelScope.launch {
            loading = true; offline = false
            try {
                val r = ErpClient.api.customers(q?.takeIf { it.isNotBlank() })
                list = r
                CacheStore.putCustomers(r)
            } catch (e: Exception) {
                offline = isNetworkError(e)
                error = if (offline) null else e.userMessage()
                if (list.isEmpty()) list = CacheStore.getCustomers()
            } finally { loading = false }
        }
    }

    suspend fun save(c: Customer, isNew: Boolean): Boolean {
        return try {
            saving = true
            if (isNew) ErpClient.api.createCustomer(c) else ErpClient.api.updateCustomer(c.id, c)
            load()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
        finally { saving = false }
    }

    suspend fun delete(id: Int): Boolean {
        return try {
            ErpClient.api.deleteCustomer(id)
            load()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
    }
}
