package com.heping.erpnative.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.heping.erpnative.BuildConfig
import com.google.gson.Gson
import com.heping.erpnative.data.UpdateInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private val gson = Gson()
    private val UPDATE_URL = "${BuildConfig.API_BASE}/downloads/update.json"
    private const val AUTHORITY = "com.heping.erpnative.fileprovider"

    fun currentVersionCode(context: Context): Int {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).versionCode
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionCode
            }
        } catch (_: Exception) { 0 }
    }

    fun currentVersionName(context: Context): String {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName ?: "?"
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }
        } catch (_: Exception) { "?" }
    }

    /** 拉取远端 update.json，返回 UpdateInfo；网络失败返回 null（必须在 IO 线程调用） */
    fun check(): UpdateInfo? {
        return try {
            val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            gson.fromJson(text, UpdateInfo::class.java)
        } catch (e: Exception) { null }
    }

    private fun dlDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        val dir = File(base, "updates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 下载 APK（必须在 IO 线程调用）
     * @param log 实时日志；@param onProgress (已下载字节, 总字节)
     */
    fun download(
        context: Context, url: String,
        log: (String) -> Unit = {}, onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? {
        return try {
            log("连接服务器…")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) { log("服务器返回 HTTP $code"); conn.disconnect(); return null }

            val total = try { conn.getHeaderField("Content-Length")?.toLong() ?: -1L } catch (_: Exception) { -1L }
            log(if (total > 0) "开始下载，共 %.1f MB".format(total / 1048576.0) else "开始下载（大小未知）")

            val apk = File(dlDir(context), "app-update.apk")
            if (apk.exists()) apk.delete()

            var downloaded = 0L
            var lastReport = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 256 * 1024 || (total > 0 && downloaded == total)) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                            log(
                                if (total > 0) "已下载 %.1f MB / %.1f MB".format(downloaded / 1048576.0, total / 1048576.0)
                                else "已下载 %.1f MB".format(downloaded / 1048576.0)
                            )
                        }
                    }
                }
            }
            conn.disconnect()
            onProgress(downloaded, total)
            log("下载结束，共 %.1f MB".format(downloaded / 1048576.0))

            if (total > 0 && downloaded != total) { log("体积与宣告不符，已丢弃"); apk.delete(); return null }
            if (apk.length() < 1024 * 1024) { log("文件过小（疑似错误页），已丢弃"); apk.delete(); return null }
            val head = ByteArray(2)
            apk.inputStream().use { if (it.read(head) != 2) { log("文件头读取失败"); apk.delete(); return null } }
            if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
                log("文件头不是 ZIP/APK，已丢弃"); apk.delete(); return null
            }
            log("校验通过（APK 头正常）")
            apk
        } catch (e: Throwable) {
            log("异常：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** Android 8+ 需用户授予「安装未知应用」权限 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    /** 打开系统「允许安装未知应用」设置页 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    /** 调起系统安装；返回是否成功拉起 */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(context, AUTHORITY, apk)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            // 部分国产 ROM 需要显式把 URI 读权限授予安装器
            val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (ri in targets) {
                context.grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
