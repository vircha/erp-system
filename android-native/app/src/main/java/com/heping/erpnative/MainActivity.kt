package com.heping.erpnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.Nav.AppNav
import com.heping.erpnative.ui.theme.AppThemeState
import com.heping.erpnative.ui.theme.ErpTheme
import com.heping.erpnative.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 启动时套用已持久化的主题偏好（restore() 已在开机时填充 SessionStore.themeMode）
            LaunchedEffect(Unit) {
                AppThemeState.mode.value = ThemeMode.fromCode(SessionStore.themeMode)
            }
            ErpTheme {
                // 🔴 v5.7.15 经理「手机是全面屏的话，手机底部的导航条区域是白色的，不好看」：
                //   根因 = 窗口从没设过 navigationBarColor，系统用自己的默认色（浅色下就是纯白），
                //   于是底部手势条/三键区是一条和 App 无关的白带（深色主题下更刺眼）。
                //   做法：把状态栏 / 导航栏底色都绑到当前配色（background），
                //   图标明暗按背景亮度自动切（isAppearanceLight*Bars）→ 深浅主题都不用手配。
                //   🔴 必须写在 ErpTheme 内部读 MaterialTheme：换肤时 SideEffect 重跑，颜色跟着变。
                // 🔴 颜色取「紧邻控件」的底色，而不是页面背景色 ——
                //   状态栏紧贴 TopBar（Surface = surface），导航栏紧贴底部 NavigationBar（= surfaceContainer）。
                //   取背景色会留一道明显色差，取相邻控件色才「连成一片」。
                val barTop = MaterialTheme.colorScheme.surface
                val barBottom = MaterialTheme.colorScheme.surfaceContainer
                val darkBg = barBottom.luminance() < 0.5f
                val view = LocalView.current
                SideEffect {
                    val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
                    @Suppress("DEPRECATION")
                    w.statusBarColor = barTop.toArgb()
                    @Suppress("DEPRECATION")
                    w.navigationBarColor = barBottom.toArgb()
                    // 🔴 手势导航（Android 10+）默认会给导航条叠一层**半透明遮罩**，
                    //    即使设了 navigationBarColor 也会被糊成灰白。必须显式关掉。
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        w.isNavigationBarContrastEnforced = false
                        w.isStatusBarContrastEnforced = false
                    }
                    WindowCompat.getInsetsController(w, view).apply {
                        isAppearanceLightStatusBars = !(barTop.luminance() < 0.5f)
                        isAppearanceLightNavigationBars = !darkBg
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }
}
