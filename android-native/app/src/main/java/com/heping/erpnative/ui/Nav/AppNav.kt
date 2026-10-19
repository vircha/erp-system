package com.heping.erpnative.ui.Nav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.ui.login.LoginScreen
import com.heping.erpnative.ui.main.MainScreen

@Composable
fun AppNav() {
    // 🔴 v5.5：启动一律先进 login（网关页）。
    //    免密续期成功也只停在信任面板，由用户点「进入开单」才进系统——与网页版一致。
    //    但恢复/续期必须在这之前做完，LoginScreen 靠 SessionStore.token 判定进面板还是进表单。
    var booted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        SessionStore.restore()
        // 优先用持久化的设备令牌静默续期会话（服务器重启会清空内存会话→所有 token 失效，靠此恢复）
        val dt = SessionStore.deviceToken
        if (dt != null && SessionStore.token == null) {
            try {
                val r = ErpClient.api.trustedLogin(mapOf("device_token" to dt))
                SessionStore.saveAuth(r)
            } catch (_: Exception) { /* 续期失败，走登录表单 */ }
        }
        // 续期后仍无会话（令牌过期/被解绑）→ 丢掉设备令牌，回登录表单，不再反复失败
        if (SessionStore.token == null) {
            SessionStore.logoutKeepDevice()
            SessionStore.forgetDevice()
        }
        booted = true
    }
    if (!booted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val nav = rememberNavController()
    // 401 且无法用设备令牌续期（如未勾选信任/令牌已过期）→ 退回登录页
    ErpClient.onForceLogout = {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            nav.navigate("login") { popUpTo("main") { inclusive = true } }
        }
    }
    NavHost(navController = nav, startDestination = "login") {
        composable("login") {
            LoginScreen(onEntered = {
                nav.navigate("main") { popUpTo("login") { inclusive = true } }
            })
        }
        composable("main") {
            // 进系统时才预取打印资料（账套可能刚在面板上切过）
            LaunchedEffect(Unit) { prefetchPrintMeta() }
            MainScreen(onLogout = {
                nav.navigate("login") { popUpTo("main") { inclusive = true } }
            })
        }
    }
}

/**
 * 预取打印小票要用的资料（出库仓库 / 经手人），缓存进 SessionStore。
 * 失败不影响启动——小票会退回默认值（出库仓库=天河仓）。
 */
private suspend fun prefetchPrintMeta() {
    try {
        val s = ErpClient.api.settings()
        SessionStore.saveReceiptMeta(s.receiptWarehouse, s.receiptHandler)
    } catch (_: Exception) { }
    try {
        val me = ErpClient.api.me()
        val n = me.account?.display_name?.takeIf { it.isNotBlank() } ?: me.account?.username ?: ""
        SessionStore.saveDisplayName(n)
    } catch (_: Exception) { }
}
