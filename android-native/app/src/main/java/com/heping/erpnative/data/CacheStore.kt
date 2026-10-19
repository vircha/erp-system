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
    suspend fun putProducts(list: List<Product>) {
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
