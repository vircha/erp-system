package com.heping.erpnative.vm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heping.erpnative.data.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heping.erpnative.data.ErpClient
import com.heping.erpnative.data.BookInviteResponse
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.data.UpdateInfo
import com.heping.erpnative.device.BtDevice
import com.heping.erpnative.device.BtPrinter
import com.heping.erpnative.device.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : ViewModel() {
    var appVersion by mutableStateOf("")
    var appCode by mutableStateOf(0)
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
    var checking by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var updateMsg by mutableStateOf<String?>(null)

    // 下载进度与实时日志
    var dlProgress by mutableStateOf(0f)      // 0..1
    var dlBytes by mutableStateOf(0L)
    var dlTotal by mutableStateOf(-1L)
    var updateLog by mutableStateOf<List<String>>(emptyList())

    private fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        updateLog = (updateLog + "[$ts] $line").takeLast(200)
    }

    // 蓝牙打印
    var btEnabled by mutableStateOf(false)
    var paired by mutableStateOf<List<BtDevice>>(emptyList())
    var printerStatus by mutableStateOf("未连接")
    var defaultPrinter by mutableStateOf<String?>(null)
    var connecting by mutableStateOf(false)

    // 设置（老板）
    var settings by mutableStateOf<com.heping.erpnative.data.Settings?>(null)
    var savingSettings by mutableStateOf(false)

    // ---------- v5.5：账套切换 + 本机解绑 ----------
    var books by mutableStateOf<List<com.heping.erpnative.data.Book>>(emptyList())
    var switchingBook by mutableStateOf(false)
    var bookMsg by mutableStateOf<String?>(null)
    var unbinding by mutableStateOf(false)

    init {
        // 版本号需 context，延迟到 refreshVersion 时填充
        appVersion = "…"
        appCode = 0
    }

    fun refreshVersion(ctx: Context) {
        appVersion = Updater.currentVersionName(ctx)
        appCode = Updater.currentVersionCode(ctx)
        defaultPrinter = BtPrinter.getLastName(ctx) ?: BtPrinter.getLastMac(ctx)
    }

    fun checkUpdate() {
        viewModelScope.launch {
            checking = true
            try {
                // 网络必须在 IO 线程
                val info = withContext(Dispatchers.IO) { Updater.check() }
                updateInfo = info
                updateMsg = if (info != null) "发现新版本 v${info.versionName}" else "已是最新版本"
            } catch (e: Exception) {
                updateMsg = "检查失败：${e.message}"
            } finally { checking = false }
        }
    }

    /** 下载并安装：全程 IO 线程 + 实时进度/日志 */
    fun doUpdate(ctx: Context) {
        val info = updateInfo ?: return
        viewModelScope.launch {
            downloading = true
            dlProgress = 0f; dlBytes = 0L; dlTotal = -1L
            updateLog = emptyList()
            try {
                // Android 8+ 需用户先授权「安装未知应用」
                if (!Updater.canInstall(ctx)) {
                    log("未获得「安装未知应用」权限，打开系统设置…")
                    updateMsg = "请先允许安装未知应用，然后回来重试"
                    Updater.openInstallPermissionSettings(ctx)
                    return@launch
                }
                log("准备下载 v${info.versionName}")
                // 🔴 v5.7.3：一律走**永久短链**（/downloads/apk），不用 info.url。
                //    info.url 是 App 抓取 update.json 那一刻的快照，旧结构里它指向
                //    带版本号的 R2 直链 → 永久指向旧包（经理反馈「下到旧版本」的根因）。
                //    短链由服务器实时 302 到当时最新版；Updater.download 已显式
                //    instanceFollowRedirects = true，会跟随 302 拿到最终响应。
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(
                        ctx, com.heping.erpnative.ui.main.APK_SHORT_URL,
                        log = { line -> log(line) },
                        onProgress = { done, total ->
                            dlBytes = done; dlTotal = total
                            dlProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        }
                    )
                }
                if (apk == null) {
                    updateMsg = "下载失败，请查看下方日志"
                    return@launch
                }
                log("调起系统安装…")
                val ok = Updater.install(ctx, apk)
                if (!ok) {
                    updateMsg = "调起安装失败，请把日志发给我"
                    log("startActivity 失败")
                } else {
                    updateMsg = "已调起安装，请在系统界面点「安装」"
                }
            } catch (e: Throwable) {
                updateMsg = "更新失败：${e.message}"
                log("异常：${e.javaClass.simpleName} ${e.message}")
            } finally { downloading = false }
        }
    }

    fun refreshBt(ctx: Context) {
        btEnabled = BtPrinter.hasBluetooth() && BtPrinter.isOn()
        printerStatus = if (BtPrinter.isConnected()) "已连接" else (BtPrinter.getLastName(ctx)?.let { "默认：$it（未连接）" } ?: "未连接")
        paired = if (btEnabled) BtPrinter.getPaired() else emptyList()
    }

    fun connect(ctx: Context, device: BtDevice) {
        if (connecting) return
        viewModelScope.launch {
            connecting = true
            try {
                val ok = BtPrinter.connect(ctx, device.address)
                printerStatus = if (ok) "已连接：${device.name}" else "连接失败"
                if (ok) paired = BtPrinter.getPaired()
            } finally { connecting = false }
        }
    }

    fun changePassword(old: String, new: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                ErpClient.api.changePassword(mapOf("old_password" to old, "new_password" to new))
                onResult(true, null)
            } catch (e: Exception) { onResult(false, e.userMessage()) }
        }
    }

    fun loadSettings() {
        if (!SessionStore.isBoss) return
        viewModelScope.launch {
            try {
                settings = ErpClient.api.settings()
                cacheReceiptMeta()
            }
            catch (e: Exception) { updateMsg = e.userMessage() }
        }
    }

    private suspend fun cacheReceiptMeta() {
        val s = settings ?: return
        SessionStore.saveReceiptMeta(s.receiptWarehouse, s.receiptHandler)
    }

    fun saveSettings(
        shopName: String, receiptTitle: String, receiptFooter: String,
        warehouse: String, handler: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            savingSettings = true
            try {
                val r = ErpClient.api.updateSettings(mapOf(
                    "shop_name" to shopName,
                    "receipt_title" to receiptTitle,
                    "receipt_footer" to receiptFooter,
                    "receipt_warehouse" to warehouse,
                    "receipt_handler" to handler
                ))
                SessionStore.saveProfile(SessionStore.role, SessionStore.perms, SessionStore.bookId, SessionStore.bookName, r.shopName)
                SessionStore.saveReceiptMeta(warehouse, handler)
                onResult(true, null)
            } catch (e: Exception) { onResult(false, e.userMessage()) }
            finally { savingSettings = false }
        }
    }

    // ---------- v5.5 ----------

    /** 拉当前账号可访问的账套列表 */
    fun loadBooks() {
        viewModelScope.launch {
            try {
                books = ErpClient.api.myBooks()
            } catch (e: Exception) {
                bookMsg = "账套列表获取失败：${e.userMessage()}"
            }
        }
    }

    /**
     * 切换账套。成功后 [SessionStore] 已更新，调用方需重启 Activity 让各页 ViewModel 重新拉数据
     * （账套一变，商品/订单/客户全按book_id 隔离，内存缓存必须丢）。
     */
    fun switchBook(bookId: Int, onDone: (Boolean) -> Unit) {
        if (switchingBook) return
        viewModelScope.launch {
            switchingBook = true; bookMsg = null
            try {
                val r = ErpClient.api.switchBook(mapOf("book_id" to bookId))
                if (r.ok && r.book != null) {
                    val m = ErpClient.api.me()
                    SessionStore.saveProfile(m.role, m.perms, r.book.id, r.book.name, r.shopName.ifBlank { m.shopName }, m.customerId)
                    bookMsg = "已切换到「${r.book.name}」，正在重启…"
                    onDone(true)
                } else {
                    bookMsg = "切换失败"
                    onDone(false)
                }
            } catch (e: Exception) {
                bookMsg = "切换失败：${e.userMessage()}"
                onDone(false)
            } finally { switchingBook = false }
        }
    }

    /** 解除本机免密信任：服务端删记录 + 本地丢设备令牌 + 清会话 */
    fun unbindSelf(onDone: (Boolean, String?) -> Unit) {
        if (unbinding) return
        viewModelScope.launch {
            unbinding = true
            try {
                val t = SessionStore.token
                ErpClient.api.deleteAllTrusted(t)
                SessionStore.forgetDevice()
                SessionStore.logoutKeepDevice()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.userMessage())
            } finally { unbinding = false }
        }
    }

    // ================= 账套管理（v5.7.4） =================
    // 🔴 经理 2026-10-28：「移动端没有账套添加或修改功能」→ 手机端补齐网页版已有的
    //    账套增改能力：新建账套 / 加入口令 / 邀请码 / 删除账套。
    //    服务端这些接口全部仅 boss 可用（服务端会再校验一次）。

    var bookBusy by mutableStateOf(false)
    var bookManageMsg by mutableStateOf<String?>(null)
    var inviteInfo by mutableStateOf<BookInviteResponse?>(null)

    /** 新建账套。成功后刷新账套列表（服务端已把新账套挂到当前账号下） */
    fun createBook(name: String, onDone: (Boolean, String?) -> Unit) {
        if (bookBusy) return
        viewModelScope.launch {
            bookBusy = true; bookManageMsg = null
            try {
                val r = ErpClient.api.createBook(mapOf("name" to name.trim()))
                loadBooks()
                bookManageMsg = "已创建账套「${r.name.ifBlank { name.trim() }}」"
                onDone(true, null)
            } catch (e: Exception) {
                val m = e.userMessage()
                bookManageMsg = "创建失败：$m"
                onDone(false, m)
            } finally { bookBusy = false }
        }
    }

    /** 设置 / 清除加入口令。clear=true 时传空串（服务端语义 = 清除） */
    fun setJoinPassword(pwd: String, clear: Boolean, onDone: (Boolean, String?) -> Unit) {
        if (bookBusy) return
        viewModelScope.launch {
            bookBusy = true; bookManageMsg = null
            try {
                val r = ErpClient.api.setJoinPassword(mapOf("join_password" to if (clear) "" else pwd.trim()))
                bookManageMsg = if (clear) "加入口令已清除" else "加入口令已保存（加密存储，不可查看）"
                // 刷新邀请码面板里的「已设置/未设置」状态
                runCatching { inviteInfo = ErpClient.api.bookInvite() }
                onDone(true, null)
            } catch (e: Exception) {
                val m = e.userMessage()
                bookManageMsg = "操作失败：$m"
                onDone(false, m)
            } finally { bookBusy = false }
        }
    }

    /** 取当前账套邀请码（供「邀请员工」面板展示 + 一键复制） */
    fun loadInvite(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                inviteInfo = ErpClient.api.bookInvite()
                onDone(true)
            } catch (e: Exception) {
                bookManageMsg = "获取邀请码失败：${e.userMessage()}"
                onDone(false)
            }
        }
    }

    /** 重置邀请码：旧码立即失效 */
    fun resetInvite(onDone: (Boolean) -> Unit) {
        if (bookBusy) return
        viewModelScope.launch {
            bookBusy = true
            try {
                val r = ErpClient.api.resetInvite()
                inviteInfo = (inviteInfo ?: BookInviteResponse()).copy(inviteCode = r.inviteCode)
                bookManageMsg = "邀请码已重置，旧码立即失效"
                onDone(true)
            } catch (e: Exception) {
                bookManageMsg = "重置失败：${e.userMessage()}"
                onDone(false)
            } finally { bookBusy = false }
        }
    }

    /**
     * 删除当前账套（危险操作，需输入账套名确认）。
     * 服务端语义：
     *  - 还有其他账套 → 自动切到第一个，返回 switched=true + 新账套
     *  - 删的是最后一个 → 注销会话，返回 logged_out=true → 前端要跳登录页
     */
    fun deleteBook(confirmName: String, onDone: (Boolean, Boolean) -> Unit) {
        if (bookBusy) return
        viewModelScope.launch {
            bookBusy = true; bookManageMsg = null
            try {
                val r = ErpClient.api.deleteBook(mapOf("confirm_name" to confirmName))
                if (r.loggedOut) {
                    SessionStore.logoutKeepDevice()
                    onDone(true, true)
                } else if (r.book != null) {
                    val m = ErpClient.api.me()
                    SessionStore.saveProfile(
                        m.role, m.perms, r.book.id, r.book.name,
                        r.shopName.ifBlank { m.shopName }, m.customerId
                    )
                    loadBooks()
                    bookManageMsg = "账套已删除，已切换到「${r.book.name}」"
                    onDone(true, false)
                } else {
                    onDone(false, false)
                }
            } catch (e: Exception) {
                val m = e.userMessage()
                bookManageMsg = "删除失败：$m"
                onDone(false, false)
            } finally { bookBusy = false }
        }
    }
}
