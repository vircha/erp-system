package com.heping.erpnative.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.heping.erpnative.util.Fuzzy
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar

/** 轻量 Toast 工具（主线程调用，用于打印结果等瞬时提示） */
fun toast(context: Context, msg: String, long: Boolean = false) {
    Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun fmtMoney(v: Double): String = "¥%.2f".format(v)

/**
 * 数量/单价的编辑态文本：去掉多余小数位。
 * 24.0 -> "24"；0.5 -> "0.5"；1.2345 -> "1.234"。
 * 用于输入框显示，避免 Double.toString() 出现 "24.0" / "1.0" 这类多余尾巴。
 */
fun fmtQty(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "0"
    val r = Math.round(v * 1000.0) / 1000.0
    return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
}

fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

fun nowStr(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

fun shortDate(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    // 截取前 10 位 yyyy-MM-dd（兼容 created_at 长格式）
    return s.take(10)
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorText(msg: String?) {
    if (!msg.isNullOrEmpty()) {
        Text(
            msg,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** 搜索命中高亮：命中的字符加粗并着色（配合 Fuzzy.highlightRanges） */
@Composable
fun HighlightText(
    text: String?,
    query: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val raw = text ?: ""
    val ranges = remember(raw, query) { Fuzzy.highlightRanges(raw, query) }
    val base = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    if (ranges.isEmpty()) {
        Text(raw, style = base, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow)
    } else {
        val hl = MaterialTheme.colorScheme.primary
        val ann = buildAnnotatedString {
            var i = 0
            for (r in ranges) {
                val s = r.first.coerceIn(0, raw.length)
                val e = (r.last + 1).coerceIn(0, raw.length)
                if (s > i) append(raw.substring(i, s))
                if (e > s) withStyle(SpanStyle(color = hl, fontWeight = FontWeight.Bold)) { append(raw.substring(s, e)) }
                i = e
            }
            if (i < raw.length) append(raw.substring(i))
        }
        Text(ann, style = base, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow)
    }
}

@Composable
fun CenteredMessage(msg: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun TopBar(title: String) {
    Surface(shadowElevation = 2.dp) {
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

// ---------- 日期辅助 ----------
fun daysAgo(n: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -n) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.time)
}

fun monthStart(): String {
    val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.time)
}

/** 毫秒（UTC 零点，DatePicker 返回）转本地 yyyy-MM-dd，避免跨时区差一天 */
fun ymdFromMillis(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

// ---------- 离线提示条 ----------
@Composable
fun OfflineBanner(visible: Boolean) {
    if (!visible) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.Center) {
            Text(
                "⚠ 离线模式：显示已缓存数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
