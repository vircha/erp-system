package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.Order
import java.util.Locale

/**
 * 🔴 结算状态标签 / 明细行（2026-10-17 应收应付）。
 *
 * 三条铁律：
 *   1. 欠款 = payable − settled（退货单 payable 为负，会自动抵减欠款）
 *   2. **只看 settled 推导状态，绝不看 orders.status**
 *      （status 是「开单时收没收款」的原始语义，被收款/撤销污染过两次）
 *   3. 已收金额永远只累加在 settled，订单金额本身不动
 */
@Composable
fun SettleTag(o: Order) {
    val st = o.settleState()
    if (st == "settled") {
        TagChip("已结清", MaterialTheme.colorScheme.primary)
    } else if (st == "partial") {
        TagChip("部分收 ¥${fmt2(o.settled ?: 0.0)}", MaterialTheme.colorScheme.tertiary)
    } else {
        TagChip("未收款", MaterialTheme.colorScheme.error)
    }
}

/** 订单详情里的一行：结算：应收 ¥x · 已收 ¥x · 未结 ¥x */
@Composable
fun SettleDetailRow(o: Order) {
    val due = o.payable.takeIf { it != 0.0 } ?: o.total
    val paid = o.settled ?: 0.0
    val left = o.left()
    val unpaid = left > 0.005
    Row(
        Modifier.fillMaxWidth()
            .background(
                (if (unpaid) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer).copy(alpha = 0.3f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("结算", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            "应收 ¥${fmt2(due)} · 已收 ¥${fmt2(paid)} · 未结 ¥${fmt2(left)}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (unpaid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun TagChip(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(top = 2.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)