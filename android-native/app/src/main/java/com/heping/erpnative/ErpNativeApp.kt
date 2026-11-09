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

    /**
     * 🔴🔴 v6.0 关键修复：崩溃捕获必须装在 [attachBaseContext]，不能装在 onCreate。
     *
     *   Android 的启动顺序是：
     *     ① Application.attachBaseContext()
     *     ② **所有 ContentProvider.onCreate()**   ← ML Kit 的 MlKitInitProvider 在这里初始化
     *     ③ Application.onCreate()
     *
     *   所以：任何在「原生库加载 / Provider 初始化」阶段炸掉的崩溃，
     *   如果处理只能在 ③ 装，就永远抓不到 —— **表现正是「闪退且毫无日志」**。
     *   装到 ① 才能覆盖整段启动期。
     */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        installCrashHandler()
    }

    override fun onCreate() {
        super.onCreate()
        // 🔴🔴 v6.0 防御式启动：每一步独立 try/catch。
        //   原来任何一步抛异常（比如某些 ROM 上取不到蓝牙适配器、DataStore 初始化失败）
        //   都会让整个进程崩掉，表现就是「闪退」。这里改成「哪步坏了就跳过哪步」，
        //   把异常记进日志，App 仍能起来 —— 起来之后才谈得上修。
        step("SessionStore.init") { SessionStore.init(this) }
        step("BtPrinter.init") { BtPrinter.init(this) }
        // 🔴🔴 v5.7.4 离线输单：注册网络状态监听。
        //   网络由「离线 → 恢复」时自动触发一次同步（把本地排队的单传上去）。
        //   ⚠️ 必须在 SessionStore.init 之后（NetState 只需要 applicationContext）。
        step("NetState.init") { NetState.init(this) }
        step("observeNetworkForSync") { observeNetworkForSync() }
        // 🔴 2026-10-15 经理要求「不手动连接，点打印直接出单」：
        // 冷启动就静默连上上次用的打印机，等用户走到开单页时已经连好了。
        // warmUp 内部只在「蓝牙已开 + 有配对记录 + 未连上」时才动作，不会凭空弹配对框。
        step("BtPrinter.warmUp") { BtPrinter.warmUp(this, appScope) }
    }

    /** 执行一步启动初始化；失败只记日志，不中断启动。 */
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            try {
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                File(filesDir, "crash.log").appendText("===== 启动步骤失败: $name =====\n$sw\n")
            } catch (_: Throwable) {
            }
        }
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
            // 🔴 写两份：内部目录给「设置页查看日志」用；
            //    外部私有目录（Android/data/<包名>/files/）可用文件管理器或 USB 直接拷出，
            //    这样即使 App 打不开也能把堆栈取出来。两条路都留，才不会再抓瞎。
            val targets = ArrayList<File>(2)
            targets.add(File(filesDir, "crash.log"))
            try { getExternalFilesDir(null)?.let { targets.add(File(it, "crash.log")) } } catch (_: Throwable) {}
            for (f in targets) {
                try {
                    FileWriter(f, true).use { w ->
                        w.write("===== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())} =====\n")
                        w.write("thread: ${t.name}\n")
                        w.write("${e.javaClass.name}: ${e.message}\n")
                        e.stackTrace.take(40).forEach { w.write("    at $it\n") }
                        w.write("\n")
                    }
                } catch (_: Throwable) {
                }
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
