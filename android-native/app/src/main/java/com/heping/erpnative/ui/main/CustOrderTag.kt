package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.Order

/**
 * 🔴🔴 2026-10-20「客户自助」下单标识。
 *
 * 背景（经理原话）：「客户提交的订单，在管理界面需要用另外显眼的 UI 显示，不然容易弄混」——
 * 一堆单子里分不清是客户自己下的还是业务员代开的。
 *
 * 三条铁律：
 *   1. 判据是服务端 orders.placed_by_role === 'customer'（**强信号**）。
 *      ❌ 绝不用「有备注」「客户名在某列表」这类弱信号 —— 那些会误标商家代开的单。
 *   2. 老订单这三列是 NULL → `isCustomerSelfOrder()` 返回 false → **完全不显示标识**。
 *      🔴 绝不能把 NULL 回落成 "staff"（那等于把全部历史单标成商家单）。
 *   3. 紫色（#8A4FD3）与本 App 主色（#534AB7 深紫）刻意拉开一档，
 *      免得跟「已结清/部分收」这些结算标签混在一起。
 */
private val CUST_TAG = Color(0xFF8A4FD3)

/** 紫色「客户自助」badge。老订单 / 商家代开单返回 null，调用方直接不渲染。 */
@Composable
fun CustOrderTag(o: Order): String? =
    if (o.isCustomerSelfOrder()) "客户自助" else null

@Composable
fun CustOrderBadge(name: String? = null) {
    Text(
        text = if (name.isNullOrBlank()) "客户自助" else "客户自助 · $name",
        fontSize = 10.sp,
        color = CUST_TAG,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 4.dp)
            .background(CUST_TAG.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .border(1.dp, CUST_TAG.copy(alpha = 0.30f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/**
 * 订单卡片的左侧竖条 Modifier。
 * 颜色与 badge 一致 —— 扫列表时**不用逐行读字**就能把客户自助单挑出来。
 */
fun custCardBar(on: Boolean): Modifier =
    if (on) Modifier.border(
        width = 3.dp,
        color = CUST_TAG,
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
    ) else Modifier
