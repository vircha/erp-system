package com.heping.erpnative.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

/**
 * 业务数据离线缓存：客户 / 商品 / 订单（按日期区间）落本地 DataStore。
 * 网络可用时写入，断网或请求失败时回退读取，保证「点一下就能看、无网也能查」。
 */
private val Context.cacheStore by preferencesDataStore(name = "erp_cache")

object CacheStore {
    private val gson = Gson()
    private val CUSTOMERS = stringPreferencesKey("customers")
    private val PRODUCTS = stringPreferencesKey("products")

    /**
     * 2026-10-19 客户多门店：商品缓存是**按门店价格**存的（客户拿到的是本店价，不是统一价）。
     * 切了门店却读上一家的缓存 = 下单金额直接算错，所以记住这份缓存属于哪个门店。
     * 内部角色看到的是统一价（cid 恒为 0），共用一份缓存。
     */
    @Volatile var lastProductStore: Int? = null
        private set

    private fun ordersKey(from: String, to: String) = stringPreferencesKey("orders_${from}_$to")

    private inline fun <reified T> fromJson(s: String?): List<T> {
        if (s.isNullOrEmpty()) return emptyList()
        return try { gson.fromJson(s, object : TypeToken<List<T>>() {}.type) } catch (_: Exception) { emptyList() }
    }

    private inline fun <reified T> toJson(list: List<T>): String = gson.toJson(list)

    // ---------- 客户 ----------
    suspend fun putCustomers(list: List<Customer>) {
        SessionStore.appContext.cacheStore.edit { it[CUSTOMERS] = toJson(list) }
    }
    suspend fun getCustomers(): List<Customer> =
        fromJson(SessionStore.appContext.cacheStore.data.first()[CUSTOMERS])

    // ---------- 商品 ----------
    // 🔴 2026-10-19 多门店：store = 这份缓存对应的门店（客户角色必传，内部角色传 null）
    suspend fun putProducts(list: List<Product>, store: Int? = null) {
        lastProductStore = store
        SessionStore.appContext.cacheStore.edit { it[PRODUCTS] = toJson(list) }
    }
    suspend fun getProducts(): List<Product> =
        fromJson(SessionStore.appContext.cacheStore.data.first()[PRODUCTS])

    // ---------- 订单（按区间缓存） ----------
    suspend fun putOrders(from: String, to: String, list: List<Order>) {
        SessionStore.appContext.cacheStore.edit { it[ordersKey(from, to)] = toJson(list) }
    }
    suspend fun getOrders(from: String, to: String): List<Order> =
        fromJson(SessionStore.appContext.cacheStore.data.first()[ordersKey(from, to)])

    // ---------- 退货单（固定缓存） ----------
    private val RETURNS = stringPreferencesKey("returns")
    suspend fun putReturns(list: List<Order>) {
        SessionStore.appContext.cacheStore.edit { it[RETURNS] = toJson(list) }
    }
    suspend fun getReturns(): List<Order> =
        fromJson(SessionStore.appContext.cacheStore.data.first()[RETURNS])
}
