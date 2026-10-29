@file:OptIn(ExperimentalMaterial3Api::class)

package com.heping.erpnative.ui.main

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heping.erpnative.data.OrderDetail
import com.heping.erpnative.device.EscPos
import com.heping.erpnative.ui.components.toast
import java.io.File
import java.io.FileOutputStream

/**
 * 订单小票预览 / 保存为图片（原生端）。
 *
 * 与网页端 receipt_preview.js 同一套版式逻辑（80mm / 48 列等宽、EAN-13 图形、
 * 底部 2cm 签收留白），只是这里用 android.graphics.Canvas 画到 Bitmap，
 * 再存进系统相册 / 调起分享（发微信给客户）。
 *
 * 之所以不用 Compose 自身截图（drawToBitmap）：布局里字号/换行会随屏幕宽度变，
 * 而 Bitmap 是定尺（80mm × N mm）绝对坐标，画出来的才是所见即所打。
 */
object ReceiptImage {

    const val WIDTH_MM = 80f
    const val TAIL_MM = 20f          // 底部 2cm 签收留白
    private const val PX_PER_MM = 7  // ≈ 180dpi，出图清晰且不至于太大
    private const val SIDE_MM = 4f   // 左右边距（列宽必须减掉边距再除列数，否则右侧金额被裁）

    private const val COLS = 48
    private const val QTY_W = 6
    private const val PRICE_W = 7        // 原价列（折前）
    private const val SALE_W = 7         // 特价列（折后成交价）
    private const val AMT_W = 8
    private const val NUM_W = QTY_W + PRICE_W + SALE_W + AMT_W   // 28

    /**
     * 🔴 2026-10-29：数字区竖线分区 + 数字加粗（解决「6 和 8 太像」）。
     *
     * 版式（与原生实打印 BtPrinter.kt 完全一致）：
     *   LEFT_W(18) + "|"(1) + 数量(6) + 原价(7) + 特价(7) + "|"(1) + 金额(8) = 48 ✓
     * LEFT_W 从 27 降到 18：竖线要从左区抠 2 列出来，
     * 而「条码:」(5 列) + 13 位数字 = 18 列 —— 正好一列不差放下，条码仍与数字同行、不多占行。
     */
    private const val SEP_W = 1
    private const val VBAR = "|"
    private const val LEFT_W = COLS - (SEP_W * 2 + NUM_W)      // 48 - 30 = 18

    private fun isWide(ch: Char): Boolean {
        val k = ch.code
        return (k in 0x1100..0x115F) || (k in 0x2E80..0xA4CF) ||
            (k in 0xAC00..0xD7A3) || (k in 0xF900..0xFAFF) ||
            (k in 0xFE30..0xFE6F) || (k in 0xFF00..0xFF60)
    }
    private fun wOf(s: String): Int {
        var w = 0
        for (ch in s) w += if (isWide(ch)) 2 else 1
        return w
    }
    private fun padR(s: String, n: Int): String {
        val d = n - wOf(s); return if (d > 0) s + " ".repeat(d) else s
    }
    private fun padL(s: String, n: Int): String {
        val d = n - wOf(s); return if (d > 0) " ".repeat(d) + s else s
    }
    private fun padC(s: String, n: Int): String {
        val d = n - wOf(s); if (d <= 0) return s
        val l = d / 2; return " ".repeat(l) + s + " ".repeat(d - l)
    }
    private fun clip(s: String, n: Int): String {
        if (wOf(s) <= n) return s
        var w = 0; val sb = StringBuilder()
        for (ch in s) { val cw = if (isWide(ch)) 2 else 1; if (w + cw > n) break; sb.append(ch); w += cw }
        return sb.toString()
    }
    private fun fmtQty(q: Double): String =
        if (q % 1.0 == 0.0) q.toLong().toString()
        else "%.2f".format(q).trimEnd('0').trimEnd('.')
    private fun n2(v: Double): String = "%.2f".format(v)

    /** 画小票 → Bitmap（80mm 宽，底部含 2cm 留白） */
    fun render(
        detail: OrderDetail,
        shop: String,
        docTitle: String,
        dateTime: String,
        handler: String,
        warehouse: String,
        footer: String
    ): Bitmap {
        val o = detail.order
        val items = detail.items
        val isRet = o.status == "returned" || o.total < 0
        // 🔴 2026-10-15：原来这里取了绝对值，退货单预览/存图会显示成「+xx元」正数，
        // 与服务端落库的负数口径、以及 ESC/POS 打印口径全部不一致。
        // 现在一律原样用服务端值：退货单 total/payable/item.qty/item.subtotal 本身就是负数。
        val before = if (o.total != 0.0) o.total else (o.payable ?: 0.0)
        val pay = if (o.payable != null && o.payable != 0.0) o.payable else o.total

        val mmF = PX_PER_MM.toFloat()          // 每毫米像素（Float 版，供排版计算）
        val widthPx = (WIDTH_MM * PX_PER_MM).toInt()
        val margin = (SIDE_MM * PX_PER_MM).toFloat()
        val colW = (widthPx - margin * 2) / COLS
        // Courier 半角步进= 0.6×字号，反推字号让48 列正好落在可打印区
        val fs = colW / 0.6f
        val lh = fs * 1.55f

        // 🔴 跟网页端同一坑：初始画布必须画得足够高，最后按墨迹裁到「内容 + 2cm」。
        // 若估矮了，底部 2cm 会被吃掉（网页端实测留白变成 8.8cm）。
        // 2026-10-15 起不画条码图形了，每行明细 = 名称 1 行 + 数字/金额 1 行。
        val est = 6f * mmF + lh * 16f + items.size * lh * 2f + TAIL_MM * mmF + 8f
        val heightPx = (est * 4f).toInt().coerceAtLeast(800)

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(AndroidColor.WHITE)

        val paint = Paint().apply {
            isAntiAlias = true
            color = AndroidColor.BLACK
            typeface = android.graphics.Typeface.MONOSPACE
        }
        var y = 4f * PX_PER_MM

        // 逐列定位绘制：中文占 2 个半角列，但中文字面宽 ≠ 2×Courier 半角宽，
        // 必须超格时把字号压到格宽 94% 以内，否则会挤到右邻列糊成一团。
        fun textAt(str: String, baseY: Float, bold: Boolean = false, sizePt: Float = fs,
                   align: String = "left", cols: Int = COLS) {
            paint.isFakeBoldText = bold
            paint.textSize = sizePt
            var s = clip(str, cols)
            s = when (align) {
                "right" -> padL(s, cols)
                "center" -> padC(s, cols)
                else -> padR(s, cols)
            }
            var c = 0
            for (ch in s) {
                val wide = isWide(ch)
                val span = if (wide) 2 else 1
                if (c + span > cols) break
                val x = margin + c * colW
                if (wide) {
                    val cellW = colW * span
                    val wCh = paint.measureText(ch.toString())
                    if (wCh > cellW * 0.94f) {
                        paint.textSize = sizePt * (cellW * 0.94f) / wCh
                        cv.drawText(ch.toString(), x + (cellW - cellW * 0.94f) / 2f, baseY, paint)
                        paint.textSize = sizePt
                    } else {
                        cv.drawText(ch.toString(), x, baseY, paint)
                    }
                } else {
                    cv.drawText(ch.toString(), x, baseY, paint)
                }
                c += span
            }
            paint.textSize = sizePt
        }
        fun text(str: String, bold: Boolean = false, sizePt: Float = fs,
                 align: String = "left", cols: Int = COLS) =
            textAt(str, y, bold, sizePt, align, cols)

        /**
         * 🔴 2026-10-29：倍宽绘制（模拟 ESC/POS 的 `GS ! 0x01` = 宽×2、高×1）。
         *
         * Canvas 没有 ESC/POS 指令，但 `Paint.textScaleX = 2f` 效果完全等价：
         * 字号不变（→ 高度不变、行高不变、纸长不变），每个字符横向拉伸 2 倍。
         *
         * 用途：合计「优惠后金额」的**数字**用倍宽大字 → 竖笔画被拉成 2 点宽，
         * 「6」和「8」一眼可辨。
         *
         * @param startCol 起始列号（0 基）
         *
         * 🔴🔴 2026-10-30 修：前进量必须**按字符实际宽度 × 2**（半角 1 列 → 2 列，
         *    全角 2 列 → 4 列）。原实现写死 `c += 2`，一旦传入汉字就会
         *    「字被挤扁 + 相邻字重叠」—— 经理看到的「优惠后金额几个字不规则」就是它。
         *    🔴 顺带原则：**中文字一律不要走 textWide**（见下方合计段注释），
         *       拉伸矢量汉字观感本来就差，且与实打印 BtPrinter.kt 不一致。
         */
        fun textWide(str: String, baseY: Float, startCol: Int,
                     bold: Boolean = true, sizePt: Float = fs) {
            paint.isFakeBoldText = bold
            paint.textSize = sizePt
            paint.textScaleX = 2f
            var c = startCol
            for (ch in str) {
                val span = (if (isWide(ch)) 2 else 1) * 2   // 字符本身宽度 × 倍宽系数 2
                if (c + span > COLS) break
                cv.drawText(ch.toString(), margin + c * colW, baseY, paint)
                c += span
            }
            paint.textScaleX = 1f
            paint.textSize = sizePt
        }
        fun rule(ch: Char) {
            val th = (0.25f * PX_PER_MM)
            if (ch == '=') {
                cv.drawRect(margin, y - fs * 0.35f, widthPx - margin, y - fs * 0.35f + th, paint)
            } else {
                var c = 0
                while (c < COLS) {
                    cv.drawRect(margin + c * colW, y - fs * 0.35f, margin + (c + 1) * colW, y - fs * 0.35f + th, paint)
                    c += 2
                }
            }
        }

        // 抬头
        text(clip(shop.ifBlank { "销售小票" }, 22), bold = true, sizePt = fs * 1.45f, align = "center")
        y += lh * 1.3f
        text(clip(docTitle, COLS), sizePt = fs * 1.15f, align = "center")
        y += lh * 0.9f
        rule('='); y += lh * 0.8f

        text("往来单位名称:" + o.customerName); y += lh
        text("单据编号:" + o.orderNo); y += lh
        text("单据日期:" + dateTime); y += lh
        val wh = (if (isRet) "入库仓库:" else "出库仓库:") + warehouse
        text(padR("经手人:" + handler, COLS - wOf(wh)) + wh)
        y += lh * 1.05f

        rule('-'); y += lh * 0.9f
        // 🔴 2026-10-29：与原生实打印同步 —— 加 2 条竖线分区，数字区不再串列
        text(padR("序号 名称", LEFT_W) + VBAR + padL("数量", QTY_W) +
            padL("原价", PRICE_W) + padL("特价", SALE_W) + VBAR + padL("金额", AMT_W),
            bold = true)
        y += lh * 0.9f
        rule('-'); y += lh * 0.9f

        // 明细
        var qtySum = 0.0
        items.forEachIndexed { i, it ->
            // 🔴 2026-10-15：原来这里对 qty / price / subtotal 全部取了绝对值，
            // 退货单每行都印成正数，与「合计负数」自相矛盾。
            // 现在一律原样使用服务端落库值：退货单 qty / subtotal 本身就是负数。
            // 单价 price 服务端始终存正数（折后成交价），保持原样即可。
            val q = it.qty
            val p = it.price
            // 原价 = 折前单价；服务端没给就退回成交价（与实打印 origOrPrice() 同口径）
            val op = it.origPrice?.takeIf { it > 0.0 } ?: p
            val amt = if (it.subtotal != 0.0) it.subtotal else q * p
            qtySum += q
            // 🔴 2026-10-29：明细整块加粗 —— 商品名里有数字（200ml/125g）、条码更是纯数字
            text(clip("${i + 1} ${it.productName}", COLS), bold = true)
            y += lh * 0.85f

            val nums = VBAR + padL(fmtQty(q) + (it.unit ?: ""), QTY_W) +
                padL(n2(op), PRICE_W) +
                padL(n2(p), SALE_W) + VBAR +
                padL(n2(amt), AMT_W)
            val bc = (it.barcode ?: "").trim()
            // 🔴 经理要求（2026-10-15）：只显示条码「数字」，不画条形码图形。
            // 图形在小票上又粗又占地方，实际没人扫，纯浪费纸。
            // 布局：左边「条码:1234567890128」紧贴序号列，右边数量/原价/特价/金额靠右。
            val left = if (bc.isEmpty()) "" else "条码:" + bc
            // 🔴 LEFT_W = 18，「条码:」(5 列) + 13 位数字 = 18 列刚好放得下；
            //    放不下就整行独放，绝不截断末位条码。
            if (left.isNotEmpty() && wOf(left) > LEFT_W) {
                text(clip(left, COLS), bold = true)
                y += lh * 0.85f
                text(padR("", LEFT_W) + nums, bold = true)
            } else {
                text(padR(clip(left, LEFT_W), LEFT_W) + nums, bold = true)
            }
            y += lh * 0.95f
        }

        // 合计
        rule('-'); y += lh * 0.95f
        val saved = before - pay
        text(padR("合计数量:" + fmtQty(qtySum), COLS - wOf("合计折前金额:" + n2(before))) + "合计折前金额:" + n2(before),
            bold = true)
        y += lh * 0.95f
        // 🔴 2026-10-29：最关键的「优惠后金额」用**倍宽大字**（模拟 GS ! 0x01：宽×2、高×1）
        //    → 行高不变、纸长不变，但每个数字的竖笔画被拉成 2 点宽 → 6/8 绝不混淆。
        //    与原生实打印同版式：左边「折扣优惠金额」，右边「优惠后金额: + 倍宽数字」；
        //    宽度按 2 列预算，放不下就降级拆两行。
        val savedTxt = "折扣优惠金额:" + n2(saved)
        val payLabel = "优惠后金额:"
        val payNum = n2(pay)
        val payNumW = payNum.length * 2          // 倍宽字符占 2 列
        val rightW = wOf(payLabel) + payNumW
        val avail = COLS - rightW
        if (wOf(savedTxt) <= avail) {
            // 🔴 标签「优惠后金额:」必须**正常宽度**：
            //    ① 与实打印 BtPrinter.kt 完全一致（那里只有 payNum 夹在 DOUBLE_W/DOUBLE_OFF 之间）；
            //    ② 汉是矢量字形，横向拉伸 2 倍会挤扁压扁 → 经理反馈「字不规则」。
            text(padR(savedTxt, avail) + payLabel, bold = true)
            textWide(payNum, y, avail + wOf(payLabel), bold = true)
        } else {
            text(savedTxt, bold = true)
            y += lh * 0.95f
            text(padR("", avail) + payLabel, bold = true)
            textWide(payNum, y, avail + wOf(payLabel), bold = true)
        }
        y += lh
        rule('-'); y += lh
        text("客户签收:____________________"); y += lh
        text(clip(footer, COLS), sizePt = fs * 0.9f, align = "center")

        // 底部 2cm 签收留白：trim 里按墨迹裁 + 补 TAIL_MM
        return trim(bmp)
    }

    /** 裁掉底部多余纯白，返回内容高度 + 2cm 留白的 Bitmap */
    private fun trim(bmp: Bitmap): Bitmap {
        val px = bmp.width
        val buf = IntArray(px)
        var last = 0
        var y = bmp.height - 1
        while (y >= 0) {
            bmp.getPixels(buf, 0, px, 0, y, px, 1)
            var ink = false
            for (c in buf) if (c != AndroidColor.WHITE) { ink = true; break }
            if (ink) { last = y; break }
            y--
        }
        val tail = (TAIL_MM * PX_PER_MM).toInt()
        val finalH = (last + tail).coerceAtMost(bmp.height)
        if (finalH >= bmp.height) return bmp
        val out = Bitmap.createBitmap(px, finalH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()
        return out
    }

    private fun fileName(o: com.heping.erpnative.data.Order): String =
        "订单_" + o.orderNo.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "") + ".png"

    /** 存进系统相册，返回 Uri；失败返回 null */
    fun saveToGallery(ctx: Context, bmp: Bitmap, name: String): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/简单ERP")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            }
        }
        uri
    } catch (e: Exception) {
        // Android 9 及以下 MediaStore 不认 IS_PENDING，退回「公共 Pictures 目录直写」
        try {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "简单ERP"
            )
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(f)
        } catch (e2: Exception) { null }
    }

    /** 调起系统分享（微信 / QQ / 邮件） */
    fun share(ctx: Context, uri: Uri, name: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "发送订单给客户"))
        } catch (e: Exception) {
            toast(ctx, "分享失败：" + (e.message ?: ""))
        }
    }
}

/**
 * 订单预览浮层：显示打印效果，可保存为图片 / 分享给客户。
 *
 * 🔴 两个坑叠在一起，必须同时满足才既不被遮挡又不被按钮挤掉：
 *
 * 1. **必须是独立窗口**（经理 2026-10-15 反馈「预览会隐藏在页面后面」）
 *    订单详情用的是 `AlertDialog`，它会开一个**新窗口**盖在主窗口之上。
 *    如果预览只用普通 `Box` 绘制，它属于主窗口 → 必然被订单详情弹窗压住看不见。
 *    所以外层必须包`Dialog(usePlatformDefaultWidth = false)`，让它自己成为一个窗口，
 *    层级才在 AlertDialog 之上。
 *
 * 2. **布局上图片是主角，按钮不许抢空间**（同一天反馈）
 *    之前用 AlertDialog + 4 行按钮（保存/分享/提示×2）把图片挤到看不见。
 *    现在：图片按宽度自适应高度（aspectRatio）+ weight(1f) 吃掉剩余空间，
 *    按钮压成一行，提示并成一行小字，关闭用右上角 ✕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptPreviewDialog(
    bmp: Bitmap?,
    onSave: () -> Unit,
    onShare: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 🔴 必须 false，否则系统会按默认宽度把内容包在 ~280dp 的容器里
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF000000).copy(alpha = 0.6f))
                // 点击暗区也能关闭，符合预期
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Card(
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.92f)
                    .padding(top = 8.dp)
                    // 🔴 关键：卡片本身吃掉点击，否则点图片会冒泡到暗区把弹窗关掉
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(10.dp)) {
                    // 标题栏
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "订单预览（打印效果）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    if (bmp == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    } else {
                        // 🔴 预览图占满剩余空间；图片按自身宽高比缩放，不裁切
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "订单小票预览",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 提示合并成一行小字
                        Text(
                            "底部已预留 2cm 签收空白 · 只显示条码数字，不打印条形码图形",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        // 按钮压成一行
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSave,
                                modifier = Modifier.weight(1f).height(42.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("保存图片", fontSize = 13.sp, maxLines = 1)
                            }
                            if (onShare != null) {
                                OutlinedButton(
                                    onClick = onShare,
                                    modifier = Modifier.weight(1f).height(42.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("分享给客户", fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}