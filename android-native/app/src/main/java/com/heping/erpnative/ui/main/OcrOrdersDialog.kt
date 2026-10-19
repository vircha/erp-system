package com.heping.erpnative.ui.main

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.Customer
import com.heping.erpnative.data.Product
import com.heping.erpnative.ocr.Fuzzy
import com.heping.erpnative.ocr.OcrTable
import com.heping.erpnative.ui.components.fmtMoney
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.SaleViewModel
import kotlin.math.abs

/** 特价标记本地记忆（按商品 id / 归一化名称） */
object OcrFlags {
    private const val PREFS = "ocr_flags"
    fun isSpecial(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    fun setSpecial(ctx: Context, key: String, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
    }
}

/**
 * 读取图片并按最长边降采样（OCR 用，兼顾精度与速度）
 *
 * ⚠️ 大坑记录：inJustDecodeBounds=true 时 decodeStream【必然返回 null】（这是设计行为，只用来填 outWidth/outHeight）。
 * 早期写法 `openInputStream(uri)?.use { decodeStream(...) } ?: return null` 把「流打开失败」和「解码返回 null」
 * 混成一个 Elvis 判断，结果每一张图都被判成读取失败 —— v1.8~v2.4 的「读取图片失败」就是这个原因。
 * 现在两步分开：先判宽高是否有效，再正式解码；解码 OOM 时自动加大采样重试。
 */
fun loadScaledBitmap(ctx: Context, uri: android.net.Uri, maxDim: Int = 2200): android.graphics.Bitmap? {
    return try {
        val cr = ctx.contentResolver
        // 1) 只取尺寸
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val s1 = cr.openInputStream(uri) ?: return null
        s1.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2) 按最长边算采样率
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

        // 3) 正式解码（OOM 则加倍降采样重试，最多 3 次）
        var bm: android.graphics.Bitmap? = null
        for (i in 0 until 3) {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample * (1 shl i) }
            val s = cr.openInputStream(uri) ?: return null
            bm = try {
                s.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            } catch (_: OutOfMemoryError) { null }
            if (bm != null) break
        }
        bm
    } catch (_: Throwable) { null }
}

private data class DItem(val product: Product?, val raw: String, val qty: Double, val special: Boolean)
private data class DStore(
    val header: String, val customer: Customer?, val rebate: String,
    val items: List<DItem>, val ambiguous: Boolean
)

/** 识别结果预览确认：改客户 / 改商品匹配 / 改数量 / 切换特价，确认后按门店拆单生成 */
@Composable
fun OcrPreviewDialog(
    table: OcrTable,
    customers: List<Customer>,
    products: List<Product>,
    generating: Boolean,
    onCancel: () -> Unit,
    onConfirm: (List<SaleViewModel.OcrFinalStore>) -> Unit
) {
    val ctx = LocalContext.current
    val initial = remember(table, customers, products) {
        table.storeHeaders.mapIndexed { ci, h ->
            val ms = Fuzzy.matchStore(h, customers) { it.name }
            val c = ms.firstOrNull()?.first
            val amb = ms.size > 1 && abs(ms[0].second - ms[1].second) < 0.01
            val rebate = when {
                c == null -> "0"
                c.name.contains("万家") -> "3"
                c.name.contains("德享") || c.name.contains("德亭") -> "5"
                else -> "0"
            }
            val items = table.rows.mapNotNull { row ->
                val q = row.qtys[ci] ?: return@mapNotNull null
                if (q <= 0) return@mapNotNull null
                val m = Fuzzy.bestMatch(row.name, products) { it.name }
                DItem(m?.first, row.name, q, m?.first?.let { p -> OcrFlags.isSpecial(ctx, "p${p.id}") } ?: false)
            }
            DStore(h, c, rebate, items, amb)
        }.filter { it.items.isNotEmpty() }
    }
    var stores by remember { mutableStateOf(initial) }
    val qtyTexts = remember { mutableStateMapOf<Pair<Int, Int>, String>() }
    val rebateTexts = remember { mutableStateMapOf<Int, String>() }
    var pickCustomerFor by remember { mutableStateOf<Int?>(null) }
    var pickProductFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun upd(i: Int, f: (DStore) -> DStore) { stores = stores.toMutableList().also { it[i] = f(it[i]) } }

    fun flagKey(raw: String, p: Product?) = p?.let { "p${it.id}" } ?: Fuzzy.norm(raw)

    val allMatched = stores.isNotEmpty() && stores.all { s ->
        s.customer != null && s.items.all { it.product != null && it.qty > 0 }
    }

    AlertDialog(
        onDismissRequest = { if (!generating) onCancel() },
        title = { Text("识别结果确认（${stores.size} 店）") },
        text = {
            Column {
                Text(
                    "未匹配项标红，点击可手动指定；每行可切「正常价/特价」（同店将拆成两张单，正常价带返点）",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(stores) { si, s ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${si + 1}. ${s.header}",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (s.ambiguous) Text(
                                        "多个相似客户，请确认",
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontSize = 11.sp
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { pickCustomerFor = si },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text(
                                            s.customer?.name ?: "选择客户",
                                            color = if (s.customer == null) MaterialTheme.colorScheme.error else Color.Unspecified,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("返点%", fontSize = 12.sp)
                                    OutlinedTextField(
                                        value = rebateTexts[si] ?: s.rebate,
                                        onValueChange = { v ->
                                            rebateTexts[si] = v
                                            v.toDoubleOrNull()?.let { upd(si) { st -> st.copy(rebate = v) } }
                                        },
                                        modifier = Modifier.width(64.dp).height(52.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                                    )
                                }
                                s.items.forEachIndexed { ii, it ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            it.product?.name ?: "⚠ ${it.raw}",
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { pickProductFor = si to ii },
                                            color = if (it.product == null) MaterialTheme.colorScheme.error else Color.Unspecified
                                        )
                                        OutlinedTextField(
                                            value = qtyTexts[si to ii] ?: it.qty.let { q ->
                                                if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
                                            },
                                            onValueChange = { v ->
                                                qtyTexts[si to ii] = v
                                                v.toDoubleOrNull()?.let { q ->
                                                    upd(si) { st ->
                                                        st.copy(items = st.items.mapIndexed { j, d ->
                                                            if (j == ii) d.copy(qty = q) else d
                                                        })
                                                    }
                                                }
                                            },
                                            modifier = Modifier.width(60.dp).height(50.dp),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                                        )
                                        TextButton(
                                            onClick = {
                                                val nv = !it.special
                                                upd(si) { st ->
                                                    st.copy(items = st.items.mapIndexed { j, d ->
                                                        if (j == ii) d.copy(special = nv) else d
                                                    })
                                                }
                                                OcrFlags.setSpecial(ctx, flagKey(it.raw, it.product), nv)
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp)
                                        ) {
                                            Text(
                                                if (it.special) "特价" else "返点",
                                                fontSize = 12.sp,
                                                color = if (it.special) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = { upd(si) { st -> st.copy(items = st.items.filterIndexed { j, _ -> j != ii }) } },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.Close, "删除", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finals = stores.mapNotNull { s ->
                        val c = s.customer ?: return@mapNotNull null
                        val items = s.items.mapNotNull { d ->
                            d.product?.let { p ->
                                if (d.qty > 0) SaleViewModel.OcrFinalItem(p, d.qty, d.special) else null
                            }
                        }
                        if (items.isEmpty()) null
                        else SaleViewModel.OcrFinalStore(c, s.rebate.toDoubleOrNull() ?: 0.0, items)
                    }
                    if (finals.isNotEmpty()) onConfirm(finals)
                },
                enabled = allMatched && !generating
            ) {
                if (generating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("生成订单")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !generating) { Text("取消") } }
    )

    if (pickCustomerFor != null) {
        val si = pickCustomerFor!!
        SimplePickerDialog(
            title = "列「${stores[si].header}」对应的客户",
            options = customers.map { it.name },
            queryHint = "搜索客户",
            onPick = { idx -> upd(si) { it.copy(customer = customers[idx]) }; pickCustomerFor = null },
            onDismiss = { pickCustomerFor = null }
        )
    }
    if (pickProductFor != null) {
        val (si, ii) = pickProductFor!!
        val raw = stores[si].items[ii].raw
        SimplePickerDialog(
            title = "「$raw」对应的商品",
            options = products.map { "${it.name}  ${fmtMoney(it.price)}" },
            queryHint = "搜索商品",
            initialQuery = raw.filter { !it.isDigit() }.take(8),
            onPick = { idx ->
                val p = products[idx]
                upd(si) { st ->
                    st.copy(items = st.items.mapIndexed { j, d ->
                        if (j == ii) d.copy(product = p, special = OcrFlags.isSpecial(ctx, "p${p.id}")) else d
                    })
                }
                pickProductFor = null
            },
            onDismiss = { pickProductFor = null }
        )
    }
}

/** 通用单选弹窗（带搜索） */
@Composable
fun SimplePickerDialog(
    title: String,
    options: List<String>,
    queryHint: String,
    initialQuery: String = "",
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf(initialQuery) }
    val filtered = remember(q) {
        if (q.isBlank()) options.indices.toList()
        else options.indices.filter { options[it].contains(q, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    label = { Text(queryHint) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(filtered) { idx ->
                        Text(
                            options[idx],
                            Modifier.fillMaxWidth().clickable { onPick(idx) }.padding(10.dp),
                            fontSize = 13.sp
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 识别失败弹窗：显示原因 + 识别原文（便于核对 OCR 到底读到了什么） */
@Composable
fun OcrErrorDialog(msg: String, raw: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var showRaw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("识别未完成", fontSize = 16.sp) },
        text = {
            Column {
                Text(msg, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (!raw.isNullOrBlank()) {
                    TextButton(
                        onClick = { showRaw = !showRaw },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(if (showRaw) "收起识别原文" else "查看识别原文（${raw.length} 字）", fontSize = 12.sp)
                    }
                    if (showRaw) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        ) {
                            val scroll = rememberScrollState()
                            Box(Modifier.fillMaxWidth().verticalScroll(scroll).padding(8.dp)) {
                                Text(raw, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("ocr", raw))
                                toast(ctx, "已复制识别原文")
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("复制原文", fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } }
    )
}

/** 批量生成结果：每单可单独打印 */
@Composable
fun OcrResultDialog(
    results: List<SaleViewModel.GenResult>,
    printingOrderId: Int,
    onPrint: (Int) -> Unit,
    onClose: () -> Unit
) {
    val ok = results.count { it.error == null }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("生成完成：成功 $ok / ${results.size}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(results) { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            if (r.error != null) {
                                Text("失败：${r.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            } else {
                                Text("${r.orderNo}  应收 ${fmtMoney(r.payable ?: 0.0)}", fontSize = 12.sp)
                            }
                        }
                        if (r.error == null && r.orderId > 0) {
                            TextButton(
                                onClick = { onPrint(r.orderId) },
                                enabled = printingOrderId <= 0,
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(if (printingOrderId == r.orderId) "打印中…" else "打印")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } }
    )
}
