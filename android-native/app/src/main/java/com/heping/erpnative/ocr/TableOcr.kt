package com.heping.erpnative.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/** OCR 解析出的分配表：门店列 × 品项行 */
data class OcrTable(
    val storeHeaders: List<String>,   // 门店列头原始文本（或 AI 直接给出的客户名）
    val rows: List<OcrRow>            // 品项行
)

data class OcrRow(val name: String, val qtys: Map<Int, Double>)

/** 一个词级 OCR 单元格 */
data class OcrCell(
    val text: String, val cx: Float, val cy: Float,
    val l: Float, val r: Float, val top: Float, val bottom: Float
)

/** 视觉行化后的 OCR 结果：rows=按行分组的单元格；flat=给 AI 用的纯文本表格 */
data class OcrLines(
    val rows: List<List<OcrCell>>,
    val flat: String
)

object TableOcr {

    // 表头排除列（非门店列）
    private val EXCLUDE_KW = listOf(
        "合计", "小计", "总计", "单位", "品项", "提货", "序号", "备注", "金额", "单价", "规格", "数量"
    )
    // 表头识别关键字（门店列头：南区/北区/3店/德亭/超市…）
    private val HEADER_KW = listOf(
        "店", "区", "亭", "合计", "单位", "品项", "超市", "便利", "市场", "商行", "百货", "客"
    )

    private const val SEP = " | "

    /** 第一步：图片 → 文本（ML Kit 中文模型，离线） */
    suspend fun extract(bitmap: Bitmap): OcrLines {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitT()
            toLines(text)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun <T> Task<T>.awaitT(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    /** 元素按 y 坐标自行聚类成视觉行（不信任 ML Kit 的行/块划分） */
    fun toLines(t: Text): OcrLines {
        val els = mutableListOf<OcrCell>()
        for (b in t.textBlocks) for (ln in b.lines) for (e in ln.elements) {
            val bb = e.boundingBox ?: continue
            val txt = e.text.trim()
            if (txt.isEmpty()) continue
            els.add(
                OcrCell(
                    txt, (bb.left + bb.right) / 2f, (bb.top + bb.bottom) / 2f,
                    bb.left.toFloat(), bb.right.toFloat(), bb.top.toFloat(), bb.bottom.toFloat()
                )
            )
        }
        if (els.isEmpty()) return OcrLines(emptyList(), "")

        val heights = els.map { it.bottom - it.top }.sorted()
        val medH = heights[heights.size / 2].coerceAtLeast(8f)
        val rowTol = medH * 0.75f

        val sorted = els.sortedBy { it.cy }
        val clusters = mutableListOf<MutableList<OcrCell>>()
        var cur = mutableListOf<OcrCell>()
        var curCy = sorted.first().cy
        for (e in sorted) {
            if (abs(e.cy - curCy) <= rowTol) {
                cur.add(e); curCy = (curCy * (cur.size - 1) + e.cy) / cur.size
            } else {
                clusters.add(cur); cur = mutableListOf(e); curCy = e.cy
            }
        }
        clusters.add(cur)
        val rows = clusters.map { it.sortedBy { c -> c.l } }
        val flat = rows.joinToString("\n") { line -> line.joinToString(SEP) { it.text } }
        return OcrLines(rows, flat)
    }

    /** 第二步（本地兜底）：文本行 → 表格结构 */
    fun parseLocal(lines: OcrLines): OcrTable {
        val rows0 = lines.rows
        if (rows0.isEmpty()) return OcrTable(emptyList(), emptyList())

        val medH = rows0.flatten()
            .map { it.bottom - it.top }
            .let { if (it.isEmpty()) 16f else it.sorted()[it.size / 2].coerceAtLeast(8f) }

        // 表头行：命中关键字最多的行
        var headerIdx = -1
        var bestHits = 0
        rows0.forEachIndexed { i, line ->
            val hits = line.count { e -> HEADER_KW.any { e.text.contains(it) } }
            if (hits > bestHits) { bestHits = hits; headerIdx = i }
        }
        if (headerIdx < 0 || bestHits < 2) return OcrTable(emptyList(), emptyList())
        val header = rows0[headerIdx]

        // 门店列 = 表头里不含排除关键字的元素
        val storeEls = header.filter { e -> EXCLUDE_KW.none { e.text.contains(it) } }
        if (storeEls.isEmpty()) return OcrTable(emptyList(), emptyList())
        val headers = storeEls.map { it.text }

        // 列归属区间：相邻列头的中点为界（只有一列时 zipWithNext 为空 → 用默认值，避免 minOf 崩溃）
        val bounds = mutableListOf<Pair<Float, Float>>()
        val gap = storeEls.zipWithNext()
            .minOfOrNull { (a, b) -> b.l - a.r }
            ?.let { max(it, medH * 0.2f) }
            ?: medH
        for (i in storeEls.indices) {
            val l = if (i == 0) storeEls[i].l - gap * 0.9f else (storeEls[i - 1].r + storeEls[i].l) / 2f
            val r = if (i == storeEls.size - 1) storeEls[i].r + gap * 0.9f else (storeEls[i].r + storeEls[i + 1].l) / 2f
            bounds.add(l to r)
        }
        val nameRight = storeEls.first().l - gap * 0.15f  // 名称区右界

        val out = mutableListOf<OcrRow>()
        for (i in (headerIdx + 1) until rows0.size) {
            val line = rows0[i]
            val nameEls = line.filter { it.cx < nameRight }
            val name = nameEls.joinToString("") { it.text }
            val qtys = mutableMapOf<Int, Double>()
            for (e in line) {
                if (e.cx < nameRight) continue
                val v = numOf(e.text) ?: continue
                val ci = bounds.indexOfFirst { (l, r) -> e.cx >= l && e.cx < r }
                if (ci >= 0) qtys[ci] = (qtys[ci] ?: 0.0) + v
            }
            val nNorm = Fuzzy.norm(name)
            if (nNorm.isEmpty()) continue
            if (nNorm.contains("合计")) continue // 底部合计行
            if (qtys.isEmpty()) {
                // 品名换行：并入上一行
                if (out.isNotEmpty() && out.last().qtys.isNotEmpty()) {
                    val prev = out.removeAt(out.size - 1)
                    out.add(OcrRow(prev.name + name, prev.qtys))
                }
                continue
            }
            out.add(OcrRow(name, qtys))
        }
        return OcrTable(headers, out)
    }

    /**
     * 把视觉行结果按 x 中心聚类重建为二维网格，交给 TableFile.gridToTable 做方向判别与转置。
     * 这样「行=门店、列=产品」的开单模板拍照上传也能解析（parseLocal 只认「列=门店」的旧表）。
     */
    fun linesToGrid(lines: OcrLines): List<List<String>> {
        val rows = lines.rows
        if (rows.isEmpty()) return emptyList()
        val cells = rows.flatten()
        if (cells.isEmpty()) return emptyList()

        val widths = cells.map { it.r - it.l }.sorted()
        val medW = widths[widths.size / 2].coerceAtLeast(10f)

        // 以 x 中心聚类成列（相邻中心间隔超过半个字宽即视为新列）
        val xs = cells.map { it.cx }.sorted()
        val centers = mutableListOf<Float>()
        var group = mutableListOf(xs[0])
        for (i in 1 until xs.size) {
            if (xs[i] - group.last() <= medW * 1.2f) group.add(xs[i])
            else { centers.add(group.average().toFloat()); group = mutableListOf(xs[i]) }
        }
        centers.add(group.average().toFloat())

        return rows.map { line ->
            val arr = MutableList<String>(centers.size) { "" }
            for (e in line) {
                val ci = centers.indices.minByOrNull { abs(centers[it] - e.cx) } ?: continue
                if (abs(centers[ci] - e.cx) > medW * 1.5f) continue   // 离所有列中心都太远 → 丢弃噪声
                arr[ci] = if (arr[ci].isEmpty()) e.text else arr[ci] + e.text
            }
            arr.toList()
        }
    }

    /** 宽松取数：允许 3 / 3.0 / 3件 / 3 箱 / 1,234 / 全角数字 */
    fun numOf(s0: String): Double? {
        var s = fullHalf(s0).replace(",", "").replace("，", "").replace(" ", "")
        if (s.isEmpty()) return null
        // 去掉常见单位后缀
        s = s.replace(Regex("(件|箱|包|瓶|提|个|袋|支|杯|袋|条|罐|盒|斤|kg|KG|g)$"), "")
        val m = Regex("-?\\d+(\\.\\d+)?").find(s) ?: return null
        // 必须是"看起来就是数字"的单元格，避免把品名里的数字当数量
        val digits = s.count { it.isDigit() }
        return if (digits * 2 >= s.length) m.value.toDoubleOrNull() else null
    }

    private fun fullHalf(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '０'..'９' -> sb.append('0' + (c - '０'))
                '.' -> sb.append('.')
                '．' -> sb.append('.')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** 模糊匹配工具 */
object Fuzzy {

    /** 归一化：全角→半角、去空白与常见标点、小写 */
    fun norm(s0: String): String {
        val sb = StringBuilder()
        for (ch0 in s0) {
            var c = ch0
            when (c) {
                in '０'..'９' -> c = '0' + (c - '０')
                in 'Ａ'..'Ｚ' -> c = 'A' + (c - 'Ａ')
                in 'ａ'..'ｚ' -> c = 'a' + (c - 'ａ')
            }
            val low = c.lowercaseChar()
            if (low.isLetterOrDigit()) sb.append(low)
        }
        return sb.toString()
    }

    /** 最长公共子串长度 */
    fun lcs(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            val ca = a[i - 1]
            for (j in 1..b.length) {
                cur[j] = if (ca == b[j - 1]) prev[j - 1] + 1 else max(prev[j], cur[j - 1])
                if (cur[j] > best) best = cur[j]
            }
            prev = cur
        }
        return best
    }

    /** 相似度 0..1（连续匹配加分：最长公共子串占比） */
    fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val l = lcs(a, b).toDouble()
        return l * 2.0 / (a.length + b.length)
    }

    /** 中文数字映射（"3店"→"三店"，支持 1-10） */
    private val CN = mapOf('0' to "零", '1' to "一", '2' to "二", '3' to "三", '4' to "四",
        '5' to "五", '6' to "六", '7' to "七", '8' to "八", '9' to "九")

    fun digitToCn(s: String): String {
        // 简单处理 1-10：3店→三店；10店→十店
        val m = Regex("^(\\d+)(.*)$").find(s) ?: return s
        val n = m.groupValues[1].toIntOrNull() ?: return s
        val cn = when {
            n in 1..9 -> CN[m.groupValues[1][0]]
            n == 10 -> "十"
            n == 11 -> "十一"
            n == 12 -> "十二"
            else -> null
        } ?: return s
        return cn + m.groupValues[2]
    }

    /** 商品匹配：返回最佳商品或 null（nameOf 置于最后，支持尾随 lambda） */
    fun <T> bestMatch(
        query: String, items: List<T>, threshold: Double = 0.55, nameOf: (T) -> String
    ): Pair<T, Double>? {
        val q = norm(query)
        if (q.isEmpty()) return null
        var best: T? = null
        var bestScore = 0.0
        for (it in items) {
            val n = norm(nameOf(it))
            if (n.isEmpty()) continue
            val s = when {
                n == q -> 1.0
                n.contains(q) || q.contains(n) -> 0.85 + 0.1 * (minOf(n.length, q.length).toDouble() / maxOf(n.length, q.length))
                else -> sim(q, n)
            }
            if (s > bestScore) { bestScore = s; best = it }
        }
        return if (bestScore >= threshold && best != null) best to bestScore else null
    }

    /** 门店列头→客户匹配（数字转中文再试），返回按分排序的候选 */
    fun <T> matchStore(header: String, items: List<T>, nameOf: (T) -> String): List<Pair<T, Double>> {
        val h = norm(header)
        if (h.isEmpty()) return emptyList()
        val hc = norm(digitToCn(header))
        val scored = items.mapNotNull { c ->
            val n = norm(nameOf(c))
            if (n.isEmpty()) return@mapNotNull null
            val s = when {
                n == h || n == hc -> 1.0
                n.contains(h) -> 0.9
                hc != h && n.contains(hc) -> 0.88
                else -> sim(h, n)
            }
            if (s >= 0.5) c to s else null
        }.sortedByDescending { it.second }
        return scored
    }
}
