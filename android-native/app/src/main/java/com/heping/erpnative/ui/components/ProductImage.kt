package com.heping.erpnative.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.heping.erpnative.BuildConfig
import com.heping.erpnative.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

/**
 * 商品图片（v6.0.2）
 *
 * 🔴 为什么自己写而不用 Coil：
 *   本项目上过「升库导致启动闪退」的当（Kotlin 2.4 + Compose BOM 组合错档），
 *   而商品图需求很简单 —— 列表缩略图 + 详情大图，不需要动图、变换、占位动画。
 *   为此引入一个新依赖、还要挑一个和 Kotlin 1.9.24 兼容的版本，风险大于收益。
 *
 * 🔴 三级缓存（缺一都会让列表滚动变卡）：
 *   ① 内存 LruCache —— 同一个商品来回滚动不重复解码
 *   ② 磁盘缓存 —— 断网/弱网下也能显示，且不重复下载
 *   ③ 失败兜底 —— 任何异常都退回默认占位图，**绝不让图片问题影响开单**
 *
 * 🔴 解码必须降采样：手机拍的图 4000×3000，直接全尺寸解码一张就 48MB，
 *   列表里放 60 个直接就 OOM 了。这里按目标显示尺寸算 inSampleSize。
 */
object ProductImageLoader {

    // 约 1/8 可用内存做图片缓存
    private val memCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun key(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun diskFile(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "product_img")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, key(url))
    }

    /** 把服务端下发的相对地址补成绝对地址 */
    fun absolute(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else BuildConfig.API_BASE.trimEnd('/') + "/" + url.trimStart('/')

    /**
     * 取图。返回 null 表示"没有图 / 取不到"，调用方显示占位图。
     * @param targetPx 目标显示边长（px），用于计算降采样倍数
     */
    suspend fun load(ctx: Context, url: String, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            val abs = absolute(url)

            memCache.get(abs)?.let { return@withContext it }

            val df = diskFile(ctx, abs)
            var bmp: Bitmap? = null
            if (df.exists() && df.length() > 0) {
                bmp = decodeFile(df.absolutePath, targetPx)
            }
            if (bmp == null) {
                bmp = download(abs, df, targetPx)
            }
            if (bmp != null) memCache.put(abs, bmp)
            bmp
        }

    private fun decodeFile(path: String, targetPx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(o.outWidth, o.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, opts)
    } catch (_: Throwable) {
        null
    }

    private fun download(url: String, dst: File, targetPx: Int): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            // 先落盘（下次离线可用），再解码
            try { dst.writeBytes(bytes) } catch (_: Throwable) { }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(o.outWidth, o.outHeight, targetPx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    /** 只按 2 的幂降采样（BitmapFactory 的要求），并保证不会缩得比目标还小 */
    private fun sampleSize(w: Int, h: Int, targetPx: Int): Int {
        if (w <= 0 || h <= 0 || targetPx <= 0) return 1
        var s = 1
        while (max(w, h) / (s * 2) >= targetPx) s *= 2
        return s
    }
}

/**
 * 商品图片控件。
 * @param url 服务端下发的相对地址；空/取不到 → 自动显示默认占位图
 */
@Composable
fun ProductImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Int = 10,
    contentScale: ContentScale = ContentScale.Crop
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(corner)
    val placeholder = painterResource(R.drawable.product_placeholder)

    val bmp by produceState<ImageBitmap?>(initialValue = null, url) {
        value = try {
            ProductImageLoader.load(ctx, url ?: "", 512)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Image(
                painter = placeholder,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 把相册/相机返回的 Uri 压成 base64 data URL。
 *
 * 🔴 必须压缩：手机相机一张 4000×3000 的图约 4~8MB，
 *   直接 base64 会超出服务端 10MB 请求上限，且上传要几十秒（门店网络很慢）。
 *   压到最长边 1024px + JPEG 85% 后通常 100~250KB，上传 1 秒内完成。
 *
 * @return "data:image/jpeg;base64,...." ；失败返回 null
 */
suspend fun compressImageToDataUrl(
    ctx: Context,
    uri: android.net.Uri,
    maxPx: Int = 1024,
    quality: Int = 85
): String? = withContext(Dispatchers.IO) {
    try {
        // 先读尺寸，算降采样，避免把整张大图读进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val m = max(bounds.outWidth, bounds.outHeight)
        while (m / (sample * 2) >= maxPx) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@withContext null

        // 再按最长边缩到 maxPx（降采样只能按 2 的幂，最后还要精细缩放一次）
        val longest = max(bmp.width, bmp.height)
        if (longest > maxPx) {
            val scale = maxPx.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }

        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    } catch (_: Throwable) {
        null
    }
}
