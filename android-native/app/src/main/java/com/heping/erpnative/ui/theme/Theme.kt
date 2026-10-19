package com.heping.erpnative.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主题模式：跟随系统 / 浅色 / 深色（持久化存 0/1/2） */
enum class ThemeMode(val code: Int) {
    SYSTEM(0), LIGHT(1), DARK(2);
    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: SYSTEM
    }
}

/**
 * 紫色主题（方案 B）：取自 dribbble "City intro animation" 调色板
 *   #29264A 深靛紫（顶栏/深色区）  #766798 灰紫（次要信息）
 *   #C9C0D9 浅紫灰（描边）        #F5EDFE 极浅紫（页面底）
 * 取自 c-purple 色阶补齐 Material3 所需槽位，保证深浅两套都可读。
 */
private val Light = lightColorScheme(
    primary = Color(0xFF534AB7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEEDFE),
    onPrimaryContainer = Color(0xFF26215C),
    secondary = Color(0xFF766798),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5EDFE),
    onSecondaryContainer = Color(0xFF29264A),
    background = Color(0xFFFAF8FD),
    onBackground = Color(0xFF26215C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF26215C),
    surfaceVariant = Color(0xFFEEEDFE),
    onSurfaceVariant = Color(0xFF5A5470),
    outline = Color(0xFFC9C0D9),
    error = Color(0xFFA32D2D),
    onError = Color(0xFFFFFFFF)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFA9EC),
    onPrimary = Color(0xFF26215C),
    primaryContainer = Color(0xFF3C3489),
    onPrimaryContainer = Color(0xFFEEEDFE),
    secondary = Color(0xFFC9C0D9),
    onSecondary = Color(0xFF29264A),
    secondaryContainer = Color(0xFF443C7A),
    onSecondaryContainer = Color(0xFFF5EDFE),
    background = Color(0xFF1A1729),
    onBackground = Color(0xFFE9E6F2),
    surface = Color(0xFF241F3D),
    onSurface = Color(0xFFE9E6F2),
    surfaceVariant = Color(0xFF332C52),
    onSurfaceVariant = Color(0xFFC9C0D9),
    outline = Color(0xFF5A5470),
    error = Color(0xFFF09595),
    onError = Color(0xFF501313)
)

/** 统一字号层级：标题加粗、正文 15sp、说明 12sp，全 App 一致 */
private val ErpTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 12.sp)
)

/** 统一圆角：卡片 16dp、小控件 12dp，全 App 一致 */
private val ErpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** 运行时主题状态：改它即可让整个 App 实时换肤（MainActivity 的 setContent 读取本状态） */
object AppThemeState {
    val mode = mutableStateOf(ThemeMode.SYSTEM)
}

@Composable
fun ErpTheme(content: @Composable () -> Unit) {
    val mode = AppThemeState.mode.value
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = ErpTypography,
        shapes = ErpShapes,
        content = content
    )
}
