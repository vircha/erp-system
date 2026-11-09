package com.heping.erpnative.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heping.erpnative.ui.theme.ErpMotion
import com.heping.erpnative.ui.theme.ErpRadius
import com.heping.erpnative.ui.theme.ErpSize

/**
 * 四级按钮体系（v6.0 重构）
 *
 * 🔴 为什么要做这个：全项目原有 292 个按钮（Button 172 / OutlinedButton 37 /
 *    TextButton 66 / IconButton 17），但**没有层级规范** —— 一屏常出现两三个
 *    同样重的实心按钮，用户不知道哪个是主操作。
 *
 * 🔴 判定标准：眯眼看屏幕，第一眼该看到的就是主操作。看到两个一样重的 = 没分级。
 *
 * 🔴 每个按钮都必须有 5 个状态：默认 / 按下 / 禁用 / 加载中 / 出错。
 *    按下用颜色加深表达（100ms），不缩放 —— 缩放会让列表里的按钮抖动。
 */

/** 主操作：每屏只允许 1 个。提交订单、保存、结算。 */
@Composable
fun ErpButtonPrimary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val base = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = when {
            !enabled || loading -> base.copy(alpha = 0.38f)
            pressed -> base.copy(alpha = 0.82f)
            else -> base
        },
        animationSpec = tween(ErpMotion.press), label = "primaryBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(ErpSize.buttonPrimary)
            .background(bg, RoundedCornerShape(ErpRadius.md))
            .clickable(
                interactionSource = src,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(
                text,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 次操作：可并列出现。描边、不填充。 */
@Composable
fun ErpButtonSecondary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val c = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = if (pressed) c.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(ErpMotion.press), label = "secondaryBg"
    )
    Row(
        modifier
            .defaultMinSize(minHeight = ErpSize.buttonSecondary)
            .background(bg, RoundedCornerShape(ErpRadius.md))
            .clickable(
                interactionSource = src, indication = null, enabled = enabled, onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (enabled) c else c.copy(alpha = 0.38f)
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(text, color = fg, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
    }
}

/** 三级：纯文字，不抢注意力。查看历史、了解更多。 */
@Composable
fun ErpButtonText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .defaultMinSize(minHeight = ErpSize.buttonTertiary)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) c else c.copy(alpha = 0.38f),
            fontSize = MaterialTheme.typography.bodyMedium.fontSize
        )
    }
}

/**
 * 危险操作：删除、撤销、退货。
 * 🔴 必须离主操作 ≥16dp，且颜色不能比主操作更抢眼。
 */
@Composable
fun ErpButtonDanger(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val e = MaterialTheme.colorScheme.error
    val bg by animateColorAsState(
        targetValue = if (pressed) e.copy(alpha = 0.14f) else if (filled) e else Color.Transparent,
        animationSpec = tween(ErpMotion.press), label = "dangerBg"
    )
    Row(
        modifier
            .defaultMinSize(minHeight = ErpSize.buttonSecondary)
            .background(bg, RoundedCornerShape(ErpRadius.md))
            .clickable(
                interactionSource = src, indication = null, enabled = enabled, onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (!enabled) e.copy(alpha = 0.38f)
        else if (filled) MaterialTheme.colorScheme.onError else e
        Text(text, color = fg, fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontWeight = FontWeight.Medium)
    }
}

/**
 * 图标按钮：热区强制 ≥44dp（图标本身 20dp，外面留白撑够）。
 * 🔴 不要用裸 Icon + clickable —— 那样热区只有图标大小，门店戴手套必然点不中。
 */
@Composable
fun ErpIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier
            .size(ErpSize.iconTouchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription, Modifier.size(20.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}
