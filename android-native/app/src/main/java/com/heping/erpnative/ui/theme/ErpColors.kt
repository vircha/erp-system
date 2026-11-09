package com.heping.erpnative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 语义色（v6.0 重构）
 *
 * 🔴 原来这些颜色是**直接写在页面里**的硬编码字面量：
 *    `Color(0xFF2E7D32)` 成功、`Color(0xFFC62828)` 失败、`Color(0xFF8A4FD3)` 客户标签……
 *    问题不是"不好看"，而是**深色主题下这些色值对比度不够**，
 *    而且改一次品牌色要全项目搜索替换。
 *
 * 🔴 这里只放「有语义」的颜色。装饰性颜色一律走 MaterialTheme.colorScheme。
 *    小票打印（ReceiptImage）里的黑白除外 —— 那是印刷用色，与屏幕主题无关。
 */
object ErpColors {

    /** 成功 / 已结清 / 打印成功。浅色下偏深，深色下提亮，保证两种主题都可读。 */
    val success: Color
        @Composable get() = if (isDarkTheme()) Color(0xFF7BC67E) else Color(0xFF2E7D32)

    /** 失败 / 错误 / 打印失败 */
    val danger: Color
        @Composable get() = MaterialTheme.colorScheme.error

    /** 警告 / 欠款 / 待处理 */
    val warning: Color
        @Composable get() = if (isDarkTheme()) Color(0xFFEF9F27) else Color(0xFF854F0B)

    /** 客户端标签（客户下单员）—— 与主色紫区分，避免与老板账号混淆 */
    val customerTag: Color
        @Composable get() = if (isDarkTheme()) Color(0xFFC9A6F5) else Color(0xFF8A4FD3)

    /** 扫码框对准色（相机预览上用，必须高对比，深浅主题都用同一色） */
    val scannerFrame: Color
        get() = Color(0xFF1AAD19)

    /** 图片遮罩（存为图片时压暗底图用） */
    val imageScrim: Color
        get() = Color(0x99000000)
}

/** 主题是深是浅 —— 按页面背景亮度判断，和 ErpTheme 的判定口径一致。 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
