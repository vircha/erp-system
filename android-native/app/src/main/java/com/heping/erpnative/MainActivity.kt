package com.heping.erpnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }
}
