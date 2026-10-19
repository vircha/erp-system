package com.heping.erpnative

import android.app.Application
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.device.BtPrinter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ErpNativeApp : Application() {
    // 🔴 打印预热用的进程级 scope：App 存活期间一直有效，
    // 只用来做「静默连上次那台打印机」这种一次性后台动作，打印完成即结束。
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        BtPrinter.init(this)
        // 🔴 2026-10-15 经理要求「不手动连接，点打印直接出单」：
        // 冷启动就静默连上上次用的打印机，等用户走到开单页时已经连好了。
        // warmUp 内部只在「蓝牙已开 + 有配对记录 + 未连上」时才动作，不会凭空弹配对框。
        BtPrinter.warmUp(this, appScope)
        installCrashHandler()
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
