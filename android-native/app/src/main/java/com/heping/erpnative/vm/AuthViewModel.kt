package com.heping.erpnative.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.heping.erpnative.data.*
import kotlinx.coroutines.launch
import retrofit2.HttpException

class AuthViewModel : ViewModel() {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var needCaptcha by mutableStateOf(false)
    var captcha by mutableStateOf<CaptchaResponse?>(null)
    /** 信任设备已满 4 台：本机登录成功但不会被记住，需 UI 层提示 */
    var trustFull by mutableStateOf(false)

    // ---------- v5.5：信任面板（免密后停留首页，不再自动进系统） ----------
    /** 已拉到 /api/me（面板要显示账号名、当前账套、可选账套列表） */
    var me by mutableStateOf<MeResponse?>(null)
    /** 切换账套进行中 */
    var switching by mutableStateOf(false)
    /** 切换账套的结果提示 */
    var switchMsg by mutableStateOf<String?>(null)

    val books: List<Book> get() = me?.books.orEmpty()
    val curBook: Book? get() = me?.book

    fun loadCaptcha() {
        viewModelScope.launch {
            try { captcha = ErpClient.api.captcha() }
            catch (_: Exception) { captcha = null }
        }
    }

    fun precheck(username: String) {
        if (username.isBlank()) return
        viewModelScope.launch {
            try {
                val r = ErpClient.api.precheck(mapOf("username" to username))
                needCaptcha = r.needCaptcha
                if (needCaptcha && captcha == null) loadCaptcha()
            } catch (_: Exception) {}
        }
    }

    /** 返回是否登录成功 */
    suspend fun login(username: String, password: String, captchaText: String, trust: Boolean): Boolean {
        return try {
            loading = true
            error = null
            trustFull = false
            val req = LoginRequest(
                username = username,
                password = password,
                captcha = if (needCaptcha) captchaText else null,
                captchaId = if (needCaptcha) captcha?.cid else null,
                trustDevice = trust,
                device = android.os.Build.MODEL
            )
            val resp = ErpClient.api.login(req)
            SessionStore.saveAuth(resp)
            trustFull = resp.trustFull
            true
        } catch (e: Exception) {
            error = e.userMessage()
            if (e is HttpException) {
                try {
                    val body = e.response()?.errorBody()?.string()
                    val m = Gson().fromJson(body, Map::class.java)
                    if (m["need_captcha"] == true) {
                        needCaptcha = true
                        if (captcha == null) loadCaptcha()
                    }
                } catch (_: Exception) {}
            }
            false
        } finally { loading = false }
    }

    // ---------- v5.5：面板辅助 ----------

    /** 拉 /api/me 填充面板（进入面板时调用；失败返回 false） */
    suspend fun loadMe(): Boolean = try {
        me = ErpClient.api.me()
        true
    } catch (_: Exception) { false }

    /**
     * 切换账套。成功后写回 SessionStore（role/perms 也可能随账套变），
     * 调用方需重建界面（见 SettingsScreen 的 restartApp）。
     */
    suspend fun switchBook(bookId: Int): Boolean {
        if (switching) return false
        switching = true; switchMsg = null
        return try {
            val r = ErpClient.api.switchBook(mapOf("book_id" to bookId))
            if (r.ok && r.book != null) {
                val m = ErpClient.api.me()
                me = m
                SessionStore.saveProfile(
                    role = m.role,
                    perms = m.perms,
                    bookId = r.book.id,
                    bookName = r.book.name,
                    shopName = r.shopName.ifBlank { m.shopName },
                    customerId = m.customerId
                )
                switchMsg = "已切换到「${r.book.name}」"
                true
            } else {
                switchMsg = "切换失败"
                false
            }
        } catch (e: Exception) {
            switchMsg = "切换失败：${e.userMessage()}"
            false
        } finally { switching = false }
    }

    /** 解除本机免密信任（服务端删记录 + 本地清设备令牌） */
    suspend fun unbindSelf(): Boolean {
        return try {
            val t = SessionStore.token
            ErpClient.api.deleteAllTrusted(t)
            SessionStore.clear()
            me = null
            true
        } catch (e: Exception) {
            error = "解绑失败：${e.userMessage()}"
            false
        }
    }
}
