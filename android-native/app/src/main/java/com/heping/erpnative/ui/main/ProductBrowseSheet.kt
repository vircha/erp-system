package com.heping.erpnative.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.Product
import com.heping.erpnative.ui.components.ProductImage
import com.heping.erpnative.ui.components.fmtMoney
import com.heping.erpnative.util.Fuzzy
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Check

/**
 * 开单页 · 商品浏览抽屉（v6.0.2）
 *
 * 🔴 经理要求「改成抽屉式，弹窗展示，需要展示产品图，参考管家婆」。
 *   以前的「浏览」是在搜索框下面挤一个 150dp 高的小列表，问题：
 *     · 一屏只能看 4~5 项，几十个商品要疯狂滚动
 *     · 光有文字没有图，同样的名字（比如不同口味同规格）根本分不清
 *     · 会顶掉下方购物车区域，加一个商品页面就跳一下
 *   现在改成从底部升起的抽屉：九宫格带图、点一下加一个、抽屉不关，
 *   可以连续挑十几个，最后点「完成」收起。
 *
 * 🔴 为什么用 ModalBottomSheet 而不是 Navigation 到一个新页面：
 *   开单是「一边看购物车一边挑货」的场景，跳页会丢掉上下文，
 *   而且返回栈会变复杂（之前调返回栈调出过问题）。
 *   底部抽屉既保留上下文，又只占一屏 —— 和管家婆的操作手感一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductBrowseSheet(
    products: List<Product>,
    cartCount: Int,
    onPick: (Product) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(0) }
    // 🔴 2026-11-07 经理要求「已选商品要有标识」：
    //   记录每个商品本次在抽屉里被点了几次（不是购物车总数 —— 购物车里可能本来就有）。
    //   用 mutableStateMap 才能让格子跟着重组。
    val picked = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }

    // 抽屉内的搜索：与主界面搜索互不干扰
    val shown = remember(products, q) {
        if (q.isBlank()) products
        else Fuzzy.filter(products, q) { listOf(it.name, it.barcode, it.spec, it.unit) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------- 标题栏 ----------
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("选择商品", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "共 ${shown.size} 项" + if (added > 0) " · 本次已加 $added 项（${picked.size} 种）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = onDismiss) { Text(if (cartCount > 0) "完成（$cartCount）" else "关闭") }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 抽屉内搜索 ----------
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text("搜商品名 / 条码 / 规格", fontSize = 13.sp) },
                trailingIcon = {
                    if (q.isNotBlank()) {
                        IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, "清空", Modifier.size(18.dp)) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp)
            )

            Spacer(Modifier.height(8.dp))

            // ---------- 九宫格（带图）----------
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (q.isBlank()) "暂无商品" else "无匹配商品",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shown, key = { it.id }) { p ->
                        ProductCell(
                            p = p,
                            pickedCount = picked[p.id] ?: 0,
                            onPick = {
                                onPick(p)
                                added += 1
                                picked[p.id] = (picked[p.id] ?: 0) + 1
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个商品格：图 + 名称 + 价格。
 * @param pickedCount 本次在抽屉里已点了几次（0 = 未选）
 */
@Composable
private fun ProductCell(p: Product, pickedCount: Int, onPick: () -> Unit) {
    val chosen = pickedCount > 0
    Surface(
        // 已选 → 底色带主色调 + 边框高亮，扫一眼就能看出哪些加过
        color = if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = if (chosen) androidx.compose.foundation.BorderStroke(
            1.5.dp, MaterialTheme.colorScheme.primary
        ) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick)
    ) {
        Column(Modifier.padding(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                ProductImage(
                    url = p.image,
                    modifier = Modifier.fillMaxSize(),
                    corner = 10
                )
                // 右上角角标：未选 =「+」，已选 =「✓ 数量」
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(4.dp)
                        .height(22.dp)
                        .widthIn(min = 22.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (chosen) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp)
                        ) {
                            Icon(
                                Icons.Filled.Check, "已选",
                                Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                "$pickedCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        Icon(
                            Icons.Filled.Add, "加入",
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                p.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                fmtMoney(p.price),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
