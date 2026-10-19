package com.heping.erpnative.ocr

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * 直接导入分配表文件（.xlsx / .csv）—— 比截图识别准得多，也不依赖 OCR。
 * xlsx 用 ZipInputStream + XmlPullParser 解析（不引入第三方库，APK 不增大）。
 *
 * 支持两种排布，自动判别方向：
 *   ① 列=门店、行=品项（老分配表）
 *   ② 行=门店、列=产品（本系统导出的开单模板）
 * 输出统一为「列=门店、行=品项」的 OcrTable，供预览弹窗与批量下单复用。
 */
object TableFile {

    private val EXCLUDE = listOf(
        "合计", "小计", "总计", "单位", "品项", "提货", "序号", "备注", "金额", "单价", "规格", "数量", "条码"
    )

    /** 入口：按扩展名/内容判别 xlsx 与 csv；storeNames/productNames 用于判别表格方向 */
    @Throws(Exception::class)
    fun parse(
        ctx: Context,
        uri: Uri,
        storeNames: List<String> = emptyList(),
        productNames: List<String> = emptyList()
    ): OcrTable {
        val name = uri.lastPathSegment.orEmpty()
        val raw = ctx.contentResolver.openInputStream(uri)
            ?: throw Exception("打不开文件")
        val bytes = raw.use { it.readBytes() }
        if (bytes.isEmpty()) throw Exception("文件是空的")
        val grid = when {
            name.endsWith(".xlsx", true) || name.endsWith(".xlsm", true) || isZip(bytes) -> parseXlsx(bytes)
            name.endsWith(".xls", true) && !isZip(bytes) ->
                throw Exception("旧版 .xls 不支持，请在电脑上另存为 .xlsx 或 CSV（逗号分隔）再导入")
            else -> parseCsv(bytes)
        }
        return gridToTable(grid, storeNames, productNames)
    }

    private fun isZip(b: ByteArray) = b.size > 2 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()

    // ---------- xlsx ----------
    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        var shared: List<String> = emptyList()
        var sheet: ByteArray? = null
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name.endsWith("sharedStrings.xml") -> shared = parseSharedStrings(z)
                    e.name.startsWith("xl/worksheets/sheet1.xml") ||
                        (sheet == null && e.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) ->
                        sheet = z.readBytes()
                }
            }
        }
        val s = sheet ?: throw Exception("没找到工作表（sheet1.xml）")
        return parseSheet(s.inputStream(), shared)
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val out = mutableListOf<String>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        var buf = StringBuilder()
        var inSi = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "si") { inSi = true; buf = StringBuilder() }
                    else if (inSi && p.name == "t") buf.append(p.nextText())
                }
                XmlPullParser.END_TAG -> if (p.name == "si") { out.add(buf.toString()); inSi = false }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(input: InputStream, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        var cur: MutableList<String>? = null
        var colIdx = 0
        var cellType: String? = null
        var buf = StringBuilder()
        var grab = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { cur = mutableListOf(); colIdx = 0 }
                    "c" -> {
                        cellType = p.getAttributeValue(null, "t")
                        buf = StringBuilder(); grab = false
                        colIdxFrom(p.getAttributeValue(null, "r"))?.let { colIdx = it }
                    }
                    "v", "t" -> grab = true
                }
                XmlPullParser.TEXT -> if (grab && cur != null) buf.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> grab = false
                    "c" -> {
                        val r = cur
                        if (r != null) {
                            val v = buf.toString()
                            val text = if (cellType == "s") shared.getOrNull(v.trim().toIntOrNull() ?: -1) ?: "" else v
                            while (r.size < colIdx) r.add("")
                            if (colIdx < r.size) r[colIdx] = text else r.add(text)
                            colIdx++
                        }
                    }
                    "row" -> cur?.let { rows.add(it) }
                }
            }
            ev = p.next()
        }
        return rows
    }

    /** "C3" -> 2（0 基列号） */
    private fun colIdxFrom(ref: String?): Int? {
        if (ref.isNullOrEmpty()) return null
        var n = 0
        for (c in ref) {
            if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1)
            else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1)
            else break
        }
        return if (n == 0) null else n - 1
    }

    // ---------- csv ----------
    private fun parseCsv(bytes: ByteArray): List<List<String>> {
        val text = decode(bytes)
        val rows = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { cur.add(sb.toString()); sb.setLength(0) }
        fun endRow() { endCell(); rows.add(cur); cur = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' -> {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else quoted = false
                }
                !quoted && c == '"' -> quoted = true
                !quoted && (c == ',' || c == '\t' || c == ';') -> endCell()
                !quoted && (c == '\n') -> { endRow() }
                !quoted && (c == '\r') -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) endRow()
        return rows
    }

    /** Excel 在中文 Windows 导出的 CSV 多为 GBK，先试 UTF-8 再退 GBK */
    private fun decode(b: ByteArray): String {
        return try {
            val s = String(b, Charsets.UTF_8)
            if (s.contains('�')) throw Exception("bad utf8")
            s
        } catch (_: Exception) {
            try { String(b, Charset.forName("GBK")) } catch (_: Exception) { String(b, Charsets.UTF_8) }
        }
    }

    private fun isTotal(s: String) = s.contains("合计") || s.contains("总计") || s.contains("小计")

    // ---------- 网格 → 表格结构（自动判别方向） ----------
    /**
     * 输出统一结构：storeHeaders = 门店名（列），rows = 品项行（每行 qtys[门店列索引] = 数量）。
     * 方向判定：表头横排匹配门店名多 → 列=门店；首列匹配门店名多 → 行=门店（转置）。
     */
    fun gridToTable(
        grid: List<List<String>>,
        storeNames: List<String> = emptyList(),
        productNames: List<String> = emptyList()
    ): OcrTable {
        if (grid.isEmpty()) return OcrTable(emptyList(), emptyList())
        val ncols = grid.maxOf { it.size }

        // 表头行：前 8 行里「非数字单元格最多」的一行
        var hIdx = 0
        var best = -1
        grid.take(8).forEachIndexed { i, r ->
            val nonNum = (1 until minOf(r.size, ncols)).count {
                r[it].isNotBlank() && TableOcr.numOf(r[it]) == null
            }
            if (nonNum > best) { best = nonNum; hIdx = i }
        }
        val header = grid[hIdx]

        // 方向判定
        val colNames = (1 until ncols).map { header.getOrElse(it) { "" }.trim() }.filter { it.isNotEmpty() }
        val firstCol = (hIdx + 1 until grid.size).map { grid[it].getOrElse(0) { "" }.trim() }.filter { it.isNotEmpty() }
        fun hits(names: List<String>, dict: List<String>): Int =
            if (dict.isEmpty()) 0 else names.count { h -> Fuzzy.matchStore(h, dict) { n -> n }.isNotEmpty() }

        val colStore = hits(colNames, storeNames)
        val rowStore = hits(firstCol, storeNames)
        // 门店名判不出来时（比如名单还没加载），退而用品名判定
        val colProd = hits(colNames, productNames)
        val rowProd = hits(firstCol, productNames)

        val transpose = when {
            rowStore != colStore -> rowStore > colStore       // 门店名命中数说话
            colProd != rowProd -> colProd > rowProd            // 平局：品名命中数说话
            else -> false                                      // 再平局：按老习惯（列=门店）
        }
        return if (transpose) transposeByStore(grid, hIdx, header)
               else byStoreColumns(grid, hIdx, header)
    }

    /** ① 列=门店、行=品项（老分配表） */
    private fun byStoreColumns(grid: List<List<String>>, hIdx: Int, header: List<String>): OcrTable {
        val ncols = grid.maxOf { it.size }
        val cols = mutableListOf<Int>()
        val names = mutableListOf<String>()
        for (c in 1 until ncols) {
            val h = header.getOrElse(c) { "" }.trim()
            if (h.isEmpty()) continue
            if (EXCLUDE.any { h.contains(it) }) continue
            cols += c; names += h
        }
        if (cols.isEmpty()) return OcrTable(emptyList(), emptyList())

        val out = mutableListOf<OcrRow>()
        for (i in (hIdx + 1) until grid.size) {
            val r = grid[i]
            val name = r.getOrElse(0) { "" }.trim()
            if (name.isEmpty()) continue
            if (isTotal(name)) continue
            val qtys = mutableMapOf<Int, Double>()
            cols.forEachIndexed { ci, c ->
                val q = TableOcr.numOf(r.getOrElse(c) { "" })
                if (q != null && q > 0) qtys[ci] = (qtys[ci] ?: 0.0) + q
            }
            if (qtys.isNotEmpty()) out.add(OcrRow(name, qtys))
        }
        return OcrTable(names, out)
    }

    /** ② 行=门店、列=产品（开单模板）→ 转置成统一结构 */
    private fun transposeByStore(grid: List<List<String>>, hIdx: Int, header: List<String>): OcrTable {
        val ncols = grid.maxOf { it.size }
        // 产品列：表头第 1..n 列（排除合计类列）
        val prodCols = mutableListOf<Pair<Int, String>>()
        for (c in 1 until ncols) {
            val h = header.getOrElse(c) { "" }.trim()
            if (h.isEmpty()) continue
            if (isTotal(h)) continue
            prodCols += c to h
        }
        // 门店行：第 0 列为门店名（排除合计与空行）
        val storeRows = mutableListOf<Pair<Int, String>>()
        for (i in (hIdx + 1) until grid.size) {
            val n = grid[i].getOrElse(0) { "" }.trim()
            if (n.isEmpty()) continue
            if (isTotal(n)) continue
            storeRows += i to n
        }
        if (prodCols.isEmpty() || storeRows.isEmpty()) return OcrTable(emptyList(), emptyList())

        val headers = storeRows.map { it.second }
        val out = mutableListOf<OcrRow>()
        for ((c, pname) in prodCols) {
            val qtys = mutableMapOf<Int, Double>()
            storeRows.forEachIndexed { si, (rIdx, _) ->
                val q = TableOcr.numOf(grid[rIdx].getOrElse(c) { "" })
                if (q != null && q > 0) qtys[si] = (qtys[si] ?: 0.0) + q
            }
            if (qtys.isNotEmpty()) out.add(OcrRow(pname, qtys))
        }
        if (out.isEmpty()) return OcrTable(emptyList(), emptyList())
        return OcrTable(headers, out)
    }
}
