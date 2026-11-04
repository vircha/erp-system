package com.heping.erpnative.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.SessionStore

/**
 * 功能中心（v5.7.12，参考管家婆宫格）
 *
 * 🔴 卡片按 **账户级权限** 过滤（SessionStore.perms 来自 /api/me 的 effectivePerms）。
 *    员工被老板设成「只能开单」后，这里只会剩「我的提成」——采购/商品/客户/报表/资金全隐藏。
 * 🔴 「我的提成」不查权限：服务端 /api/commission/mine 强制锁 placed_by=自己，天然隔离。
 * 🔴 客户角色不进本页（visibleTabs 已硬限定为 开单/订单/我的欠款）。
 */
private data class FuncItem(
    val tab: Tab?,
    val label: String,
    val icon: String,
    val perms: List<String> = emptyList()
)

@Composable
fun FuncScreen(onPick: (Tab) -> Unit) {
    val groups = listOf(
        "进销存" to listOf(
            FuncItem(Tab.Purchase, "采购与库存", "🚚", listOf("purchase.view", "inventory.view")),
            FuncItem(Tab.Products, "商品管理", "📦", listOf("product.view")),
            FuncItem(Tab.Customers, "客户管理", "🏪", listOf("customer.view"))
        ),
        "报表与经营" to listOf(
            FuncItem(Tab.Reports, "经营报表", "📊", listOf("analysis.view", "ar.view")),
            FuncItem(Tab.Commission, "员工提成", "🏅")
        ),
        "资金" to listOf(
            FuncItem(Tab.Funds, "资金流水", "💵", listOf("fund.view"))
        )
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("全部功能", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "按当前账户权限显示 · 权限由老板在设置中配置",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        groups.forEach { (title, items) ->
            val visible = items.filter {
                it.perms.isEmpty() || it.perms.any { p -> SessionStore.can(p) }
            }
            if (visible.isNotEmpty()) {
                item {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    // 简单网格（不用 LazyGrid，避免额外依赖与嵌套滚动冲突）
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        visible.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()) {
                                row.forEach { it2 -> FuncCard(it2, onPick, Modifier.weight(1f)) }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "口径：提成 = 毛利 × 比例（默认 50%），退货自动红冲；采购/商品/客户/报表均已收纳到本页。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun FuncCard(
    item: FuncItem, onPick: (Tab) -> Unit, modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(96.dp).clickable { item.tab?.let(onPick) },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(item.icon, fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(item.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
