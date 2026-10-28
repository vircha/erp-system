package com.heping.erpnative

import android.app.Application
import com.heping.erpnative.data.NetState
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.data.SyncManager
import com.heping.erpnative.device.BtPrinter
import com.heping.erpnative.data.logd
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ErpNativeApp : Application() {
    // 🔴 打印预热用的进程级 scope：App 存活期间一直有效，
    // 只用来做「静默连上次那台打印机」这种一次性后台动作，打印完成即结束。
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        BtPrinter.init(this)
        // 🔴🔴 v5.7.4 离线输单：注册网络状态监听。
        //   网络由「离线 → 恢复」时自动触发一次同步（把本地排队的单传上去）。
        //   ⚠️ 必须在 SessionStore.init 之后（NetState 只需要 applicationContext）。
        NetState.init(this)
        observeNetworkForSync()
        // 🔴 2026-10-15 经理要求「不手动连接，点打印直接出单」：
        // 冷启动就静默连上上次用的打印机，等用户走到开单页时已经连好了。
        // warmUp 内部只在「蓝牙已开 + 有配对记录 + 未连上」时才动作，不会凭空弹配对框。
        BtPrinter.warmUp(this, appScope)
        installCrashHandler()
    }

    /**
     * 🔴 v5.7.4 网络恢复 → 自动同步离线队列。
     *
     * 🔴 为什么用「上一帧是离线、这一帧是联网」作触发条件：
     *   onAvailable 每次网络切换都会回调（包括从 WiFi 切到 4G），
     *   无脑每次都同步 = 白白耗电耗流量。
     *   只在「真的断过网又连回来」时才同步，才是用户期待的行为。
     */
    private fun observeNetworkForSync() {
        appScope.launch {
            var wasOffline = false
            NetState.online.collect { now ->
                if (now && wasOffline) {
                    // 有网了 → 把排队里的单传上去
                    try {
                        val r = SyncManager.sync(this@ErpNativeApp)
                        if (r.ok > 0) logd("Sync", "网络恢复，已自动同步 ${r.ok} 单")
                    } catch (_: Exception) { }
                }
                wasOffline = !now
            }
        }
    }

    /** 崩渍（闪退）堆栈写入 crash.log，便于在设置页查看并反馈 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = File(filesDir, "crash.log")
                FileWriter(f, true).use { w ->
                    w.write("===== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())} =====\n")
                    w.write("thread: ${t.name}\n")
                    w.write("${e.javaClass.name}: ${e.message}\n")
                    e.stackTrace.take(40).forEach { w.write("    at $it\n") }
                    w.write("\n")
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        fun readCrashLog(app: Application, maxChars: Int = 4000): String {
            return try {
                val f = File(app.filesDir, "crash.log")
                if (!f.exists()) return ""
                val txt = f.readText()
                if (txt.length <= maxChars) txt else txt.takeLast(maxChars)
            } catch (_: Throwable) { "" }
        }

        fun clearCrashLog(app: Application) {
            try { File(app.filesDir, "crash.log").delete() } catch (_: Throwable) {}
        }
    }
}
