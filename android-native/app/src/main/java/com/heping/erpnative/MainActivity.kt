package com.heping.erpnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.Nav.AppNav
import com.heping.erpnative.ui.theme.AppThemeState
import com.heping.erpnative.ui.theme.ErpTheme
import com.heping.erpnative.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 🔴🔴 v6.0 重构（targetSdk 34 → 36）：系统栏方案**整体重做**。
        //
        //   旧做法（v5.7.15 / v5.7.16）是设 window.statusBarColor / navigationBarColor，
        //   但这两个 API 从 Android 15（API 35）起**对面向 35+ 的应用直接失效**：
        //     · setStatusBarColor      → 完全无效（官方已废弃）
        //     · setNavigationBarColor  → 手势导航下无效；三键导航只剩 80% 不透明默认值
        //   所以升完 targetSdk 后，底部那条色差会重新出现，而且**怎么调都调不掉**。
        //
        //   正解：enableEdgeToEdge() 让内容真正画到系统栏下面，
        //        再由 Compose 的 WindowInsets 决定谁需要让出位置（Scaffold 自动处理），
        //        导航栏区域由我们自己的 NavigationBar 着色 —— 同一个组件、同一个色值，
        //        物理上不可能再有接缝。
        // 🔴🔴 v6.0.3 底部色差真凶：`enableEdgeToEdge()` 的**默认** navigationBarStyle 是
        //   SystemBarStyle.auto(lightScrim, darkScrim)，其中 lightScrim = 90% 不透明白色！
        //   所以系统导航栏位置上永远蒙着一层白 → 和页面底色 / 我们的 NavigationBar 都不一样。
        //   必须显式传 **COLOR_TRANSPARENT** 才真正透明。
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

        // 🔴🔴 v6.0.1 保险：再显式关一次「让系统栏占据布局空间」。
        //   enableEdgeToEdge() 在个别 ROM / 老版本上会被主题或系统覆盖掉，
        //   一旦失效 → 内容被系统栏挤在中间，上下各留一条白边（就是「没有全屏」）。
        //   主题里已经把系统栏设为透明、窗口底色改成页面底色，三道一起兜。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 让内容延伸进刘海/挖孔区域（对应主题里的 shortEdges）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 🔴🔴 崩溃日志展示必须放在 super.onCreate() **之后**。
        //   上一版（fix2）把它放在了最前面，而它内部会调 setContentView() ——
        //   在 super.onCreate() 之前调 setContentView 属于非法操作，必抛异常。
        //   结果：只要磁盘上存在上一次的 crash.log（必有），App 就**每次启动都崩**，
        //   形成自锁死循环 —— 这就是「fix2 还是闪退」的直接原因。
        if (showCrashIfAny()) return

        // 🔴🔴 v6.0 保险：把首次组合包在 try/catch 里。
        //   首次组合是在 setContent 内同步执行的，异常会直接抛到调用方 —— 这里能接住。
        //   接住后用纯 View 把堆栈画出来，用户当场就能看到原因，不必等下次启动。
        try {
            setContent {
                // 启动时套用已持久化的主题偏好（restore() 已在开机时填充 SessionStore.themeMode）
                LaunchedEffect(Unit) {
                    AppThemeState.mode.value = ThemeMode.fromCode(SessionStore.themeMode)
                }
                ErpTheme {
                    // 🔴 边到边之后，只剩一件事要手动做：**系统栏图标的明暗**。
                    //   背景色由各页面自己的 Surface / NavigationBar 决定，
                    //   这里按页面背景亮度切换图标黑白，保证时钟 / 电量 / 返回键看得清。
                    val bg = MaterialTheme.colorScheme.background
                    val darkBg = bg.luminance() < 0.5f
                    val view = LocalView.current
                    SideEffect {
                        val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
                        WindowCompat.getInsetsController(w, view).apply {
                            isAppearanceLightStatusBars = !darkBg
                            isAppearanceLightNavigationBars = !darkBg
                        }
                    }
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppNav()
                    }
                }
            }
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    /** 把异常堆栈用纯 View 画出来（Compose 已经不可信时用）。 */
    private fun showFatal(t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val detail = sw.toString()

        // 同时落盘，便于事后取证
        try {
            java.io.File(filesDir, "crash.log").appendText("===== 首次组合失败 =====\n$detail\n")
        } catch (_: Throwable) {}

        val pad = (14 * resources.displayMetrics.density).toInt()
        val tv = android.widget.TextView(this).apply {
            text = detail
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(android.widget.ScrollView(this).apply { addView(tv) })
    }

    /**
     * 若有历史崩溃日志，用**纯 View** 铺满整屏展示。
     * 🔴 必须在本方法返回后调用 recreate()，不能阻塞后续启动；且日志看一次就改名，
     *    否则用户每次打开都被日志页拦住，等于 App「打不开」。
     * 返回 true 表示已接管界面。
     */
    private fun showCrashIfAny(): Boolean {
        val f = java.io.File(filesDir, "crash.log")
        if (!f.exists()) return false
        val log = try {
            f.readText().takeLast(6000)
        } catch (_: Throwable) {
            return false
        }
        if (log.isBlank()) return false

        // 🔴 立刻改名，保证「只展示一次」—— 不然下次启动又被拦住，用户会认为 App 坏了
        val shown = java.io.File(filesDir, "crash_${System.currentTimeMillis()}.log")
        try { f.renameTo(shown) } catch (_: Throwable) {}

        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = android.widget.TextView(this).apply {
            text = log
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }

        val btnGo = android.widget.Button(this).apply {
            text = "继续进入 App"
            setOnClickListener { recreate() }
        }
        val btnCopy = android.widget.Button(this).apply {
            text = "复制日志"
            setOnClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
            }
        }

        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnGo, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnCopy, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        return true
    }
}
