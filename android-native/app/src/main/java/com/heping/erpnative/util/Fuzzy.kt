package com.heping.erpnative.util

/**
 * 统一模糊搜索工具。
 * 与网页端 public/js/app.js 的 norm / fuzzyScore 保持同语义：
 *  1) 归一化：全角转半角、转小写、去掉空白与常见分隔符（- _ / . , 、 · : ; # ( ) 等）；
 *  2) 多关键词：按空白拆分，全部命中才算匹配（AND）；
 *  3) 双档匹配：前缀 > 子串 > 子序列（允许不连续），按相关度排序。
 */
object Fuzzy {

    private val SEP = Regex("[\\s\\-_/\\\\.,，、。·:：;；#()（）\\[\\]【】\"'’“”`]+")
    private val WS = Regex("\\s+")

    /** 归一化字符串 */
    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                ch == '\u3000' -> ' '
                else -> ch
            }
            sb.append(c)
        }
        return SEP.replace(sb.toString().lowercase(), "")
    }

    /** 子序列匹配：tok 的字符按序出现在 field 中（允许不连续） */
    fun isSubseq(tok: String, field: String): Boolean {
        if (tok.isEmpty()) return true
        var i = 0
        var j = 0
        while (j < field.length && i < tok.length) {
            if (field[j] == tok[i]) i++
            j++
        }
        return i == tok.length
    }

    private val CJK = Regex("[\\u3400-\\u9fff]")
    fun hasCJK(s: String): Boolean = CJK.containsMatchIn(s)

    /** 单个关键词对单个字段的得分：前缀 1000 > 子串 600 > 子序列 120 > 不匹配 0 */
    fun tokenScore(field: String, tok: String): Int {
        if (tok.isEmpty()) return 1
        if (field.isEmpty()) return 0
        val i = field.indexOf(tok)
        if (i == 0) return 1000 - minOf(field.length, 200)
        if (i > 0) return 600 - minOf(i, 100)
        // 子序列兜底只对含中文的关键词开放（中文常跳跃输入）；条码/规格等字母数字要求精确子串
        if (!hasCJK(tok)) return 0
        return if (isSubseq(tok, field)) 120 - minOf(field.length, 100) else 0
    }

    /** 查询打分：全部关键词命中返回总分，否则 -1；空查询返回 0 */
    fun score(query: String?, fields: List<String?>): Int {
        val q = (query ?: "").trim()
        if (q.isEmpty()) return 0
        val toks = q.split(WS).map { norm(it) }.filter { it.isNotEmpty() }
        if (toks.isEmpty()) return 0
        val nf = fields.map { norm(it) }.filter { it.isNotEmpty() }
        if (nf.isEmpty()) return 0
        var total = 0
        for (t in toks) {
            var best = 0
            for (f in nf) { val s = tokenScore(f, t); if (s > best) best = s }
            if (best == 0) return -1
            total += best
        }
        return total + if (toks.size > 1) 50 else 0
    }

    /** 是否命中（空查询视为命中） */
    fun matches(query: String?, fields: List<String?>): Boolean = score(query, fields) >= 0

    /** 过滤 + 按相关度稳定排序；fieldsOf 返回该条目参与匹配的字段 */
    fun <T> filter(list: List<T>, query: String?, fieldsOf: (T) -> List<String?>): List<T> {
        val q = (query ?: "").trim()
        if (q.isEmpty()) return list
        val scored = ArrayList<Triple<T, Int, Int>>(list.size)
        list.forEachIndexed { i, it ->
            val sc = score(q, fieldsOf(it))
            if (sc >= 0) scored.add(Triple(it, i, sc))
        }
        scored.sortWith(compareByDescending<Triple<T, Int, Int>> { it.third }.thenBy { it.second })
        return scored.map { it.first }
    }

    /** 命中片段在原串中的区间（左闭右开），供 Compose 高亮使用 */
    fun highlightRanges(text: String?, query: String?): List<IntRange> {
        val raw = text ?: return emptyList()
        val q = (query ?: "").trim()
        if (q.isEmpty() || raw.isEmpty()) return emptyList()
        val toks = q.split(WS).map { norm(it) }.filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val nz = StringBuilder()
        val idx = ArrayList<Int>(raw.length)
        for (i in raw.indices) {
            val c = norm(raw[i].toString())
            if (c.isNotEmpty()) { nz.append(c); idx.add(i) }
        }
        if (nz.isEmpty()) return emptyList()
        val marks = BooleanArray(raw.length)
        for (t in toks) {
            var from = 0
            while (true) {
                val at = nz.indexOf(t, from)
                if (at < 0) break
                for (k in idx[at]..idx[at + t.length - 1]) marks[k] = true
                from = at + 1
            }
        }
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < marks.size) {
            if (marks[i]) {
                var j = i
                while (j < marks.size && marks[j]) j++
                out.add(i until j)
                i = j
            } else i++
        }
        return out
    }
}
