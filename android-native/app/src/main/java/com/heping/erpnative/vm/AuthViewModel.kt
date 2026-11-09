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

    /**
     * 注册（2026-11-07 新增）
     *
     * 🔴 关键约束：**不再传 book_id** —— 客户端根本拿不到套账列表
     *   （服务端已删除公开的 /api/books/public，避免泄漏所有客户的套账名称）。
     *   两种方式二选一：
     *     · 新建套账：传 bookName，注册后自己成为该套账老板
     *     · 邀请码加入：传 inviteCode，服务端用它反查套账，角色为员工
     * 🔴 注册**必须带图形验证码**（服务端强制），所以 captcha 为空时直接返回失败。
     */
    suspend fun register(
        username: String,
        password: String,
        displayName: String,
        bookName: String,
        inviteCode: String,
        captchaText: String,
        trust: Boolean,
        smsCode: String = "",
        emailCode: String = ""
    ): Boolean {
        return try {
            loading = true
            error = null
            trustFull = false
            val cid = captcha?.cid
            if (cid.isNullOrBlank()) {
                error = "验证码未加载，请点验证码图片刷新后重试"
                return false
            }
            val req = RegisterRequest(
                username = username,
                password = password,
                displayName = displayName.takeIf { it.isNotBlank() },
                bookName = bookName.takeIf { it.isNotBlank() },
                inviteCode = inviteCode.takeIf { it.isNotBlank() }?.uppercase(),
                trustDevice = trust,
                device = android.os.Build.MODEL,
                captchaId = cid,
                captcha = captchaText,
                smsCode = smsCode.takeIf { it.isNotBlank() },
                emailCode = emailCode.takeIf { it.isNotBlank() }
            )
            val resp = ErpClient.api.register(req)
            // 🔴 待审核：注册成功但服务端没给 token，不能保存会话，否则会被当成已登录
            if (resp.pending || resp.token.isBlank()) {
                pendingMsg = resp.message ?: "注册成功，等待管理员审核"
                return false
            }
            SessionStore.saveAuth(resp)
            trustFull = resp.trustFull
            true
        } catch (e: Exception) {
            error = e.userMessage()
            // 验证码错/过期 → 自动换一张，免得用户反复提交同一张废图
            loadCaptcha()
            false
        } finally { loading = false }
    }

    // ---------- 邮箱验证码（2026-11-09 主通道） ----------
    /** 邮箱验证是否可用（开启后注册默认走邮箱） */
    var emailRequired by mutableStateOf(false)

    suspend fun loadEmailStatus() {
        emailRequired = try { ErpClient.api.emailStatus().required } catch (_: Exception) { false }
    }

    /** 发送邮箱验证码；返回 null=成功，否则是给用户看的原因 */
    suspend fun sendEmailCode(email: String, captchaText: String): String? {
        val cid = captcha?.cid
        if (cid.isNullOrBlank()) {
            loadCaptcha()
            return "图形验证码未加载，请稍后重试"
        }
        return try {
            ErpClient.api.sendEmailCode(
                mapOf("email" to email, "captcha_id" to cid, "captcha" to captchaText)
            )
            null
        } catch (e: Exception) {
            e.userMessage()
        } finally {
            loadCaptcha()
        }
    }

    // ---------- 短信验证码（2026-11-08） ----------
    /** 服务端是否开启了短信通道：开启后注册必须填短信码 */
    var smsRequired by mutableStateOf(false)
    /** 上一次发送是否走的是 mock（仅服务器日志，自测用） */
    var smsMock by mutableStateOf(false)
    /** 注册后等待审核的提示（非错误） */
    var pendingMsg by mutableStateOf<String?>(null)

    suspend fun loadSmsStatus() {
        smsRequired = try { ErpClient.api.smsStatus().required } catch (_: Exception) { false }
    }

    /**
     * 发送短信验证码。
     * 🔴 必须带图形验证码：短信要花钱，裸接口会被脚本刷爆。
     * @return null=成功；否则返回给用户看的原因
     */
    suspend fun sendSms(phone: String, captchaText: String): String? {
        val cid = captcha?.cid
        if (cid.isNullOrBlank()) {
            loadCaptcha()
            return "图形验证码未加载，请稍后重试"
        }
        return try {
            val r = ErpClient.api.sendSms(
                mapOf("phone" to phone, "captcha_id" to cid, "captcha" to captchaText)
            )
            smsMock = r.mock
            null
        } catch (e: Exception) {
            e.userMessage()
        } finally {
            // 图形码是一次性的，无论成败都换一张
            loadCaptcha()
        }
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
