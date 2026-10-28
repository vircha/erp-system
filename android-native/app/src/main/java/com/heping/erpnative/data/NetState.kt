package com.heping.erpnative.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 🔴🔴 v5.7.4 网络状态检测（离线输单的前置）。
 *
 * 🔴 为什么不能只看「请求有没有抛异常」：
 *   请求失败可能是 401（会话失效）、400（参数错）—— 这些是**业务错误**，不该判成离线。
 *   离线只指「网络层不通」→ ConnectivityManager 才是权威判据。
 *
 * ⚠️ 注意：ConnectivityManager 只知道「有没有连上网」，**不知道服务器是否可达**
 *   （连着 WiFi 但服务器宕机，它仍报「已连接」）。所以真正判断离线仍以
 *   请求抛 IOException 为准；本类用于「提前给用户提示 + 网络恢复时触发同步」。
 */
object NetState {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    private var cm: ConnectivityManager? = null
    private var registered = false

    /** 启动时调用：注册网络回调 + 读一次当前状态 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        val c = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = c
        refresh(ctx)
        if (registered) return
        registered = true
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            c.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { refresh(ctx) }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        } catch (_: Exception) {
            // 注册失败（极少数机型）→ 退回「按请求异常判断」的既有逻辑，不影响主流程
        }
    }

    /** 主动刷新一次当前网络状态 */
    fun refresh(context: Context) {
        val c = cm ?: (context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager).also { cm = it }
        if (c == null) { _online.value = true; return }
        _online.value = try {
            val n = c.activeNetwork ?: return
            val caps = c.getNetworkCapabilities(n) ?: return
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    /** 当前是否有网（供 UI 读） */
    fun isOnline(): Boolean = _online.value
}
