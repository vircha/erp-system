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

    // 🔴 2026-11-07：保存后要能拿到 id，新建商品才能紧接着上传图片。
    //    原来 save 只返回 Boolean，新建完拿不到 id → 图片只能等下次编辑再加。
    var lastSavedId by mutableStateOf(0)
        private set

    suspend fun save(p: Product, isNew: Boolean): Boolean {
        return try {
            saving = true
            lastSavedId = if (isNew) {
                val r = ErpClient.api.createProduct(p)
                r.id
            } else {
                ErpClient.api.updateProduct(p.id, p)
                p.id
            }
            load()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
        finally { saving = false }
    }

    /** 上传商品图片（base64 data URL）；成功后重新拉列表让缩略图更新 */
    suspend fun uploadImage(id: Int, dataUrl: String): Boolean {
        return try {
            ErpClient.api.uploadProductImage(id, mapOf("data" to dataUrl))
            load()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
    }

    /**
     * 平台图库匹配：按条码优先、名称模糊找同类商品图。
     * @return 本地图片地址（空串 = 没匹配到）
     */
    suspend fun matchImage(barcode: String, name: String, excludeId: Int): String {
        return try {
            val r = ErpClient.api.matchProductImage(
                mapOf(
                    "barcode" to barcode,
                    "name" to name,
                    "exclude_id" to excludeId.toString()
                )
            )
            r["image"] ?: ""
        } catch (e: Exception) { "" }
    }

    /** 删除商品图片 → 回落到默认占位图 */
    suspend fun removeImage(id: Int): Boolean {
        return try {
            ErpClient.api.deleteProductImage(id)
            load()
            true
        } catch (e: Exception) { error = e.userMessage(); false }
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
