package com.dukangalex.angelacam

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 更新包下载与安装（隐私设计）：
 * - 下载地址必须通过 UpdateChecker 的域名白名单校验
 * - 安装包存放在应用私有目录，下载前清理旧包
 * - 通过 FileProvider 一次性授权给系统安装器，不暴露给其他应用
 * - 界面只显示通用提示，不暴露网络异常原文
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private val executor = Executors.newSingleThreadExecutor()

    /** 允许的下载域名白名单：只允许 GitHub（与 UpdateChecker 一致） */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com"
    )

    /** 弹出更新提示框 */
    fun promptUpdate(activity: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        val notes = info.notes.trim().ifEmpty { activity.getString(R.string.update_notes_empty) }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, info.version))
            .setMessage(notes)
            .setPositiveButton(R.string.update_download) { _, _ -> download(activity, info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(activity: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        val progressDialog = AlertDialog.Builder(activity)
            .setMessage(R.string.update_downloading)
            .setCancelable(false)
            .create()
        progressDialog.show()

        executor.execute {
            try {
                val file = downloadWithRedirects(activity, info.downloadUrl, info.version)
                    ?: throw IllegalStateException("download failed")
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    installApk(activity, file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载更新包失败", e)
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 手动处理跳转：每一跳都校验域名白名单（不用 instanceFollowRedirects，
     * 避免自动跳转绕过白名单）。
     */
    private fun downloadWithRedirects(
        activity: AppCompatActivity,
        startUrl: String,
        version: String
    ): File? {
        var url = URL(startUrl)
        repeat(5) {
            require(url.host in ALLOWED_HOSTS) { "下载主机不在白名单" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 20000
                // 静态 UA，不带设备/版本信息；不使用 Cookie
                setRequestProperty("User-Agent", "AngelaCam")
                instanceFollowRedirects = false
            }
            try {
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        // 下载前清理旧包，不堆积
                        val dir = File(activity.externalCacheDir, "updates").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        val file = File(dir, "AngelaCam_${version}.apk")
                        conn.inputStream.use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (file.length() == 0L) throw IllegalStateException("empty file")
                        return file
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw IllegalStateException("redirect without location")
                        url = URL(url, location) // 支持相对跳转
                    }
                    else -> throw IllegalStateException("bad response")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("too many redirects")
    }

    private fun installApk(activity: AppCompatActivity, file: File) {
        // Android 8+：需要用户授予"安装未知应用"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.update_need_install_permission)
                .setPositiveButton(R.string.update_go_settings) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "打开安装权限设置失败", e)
                    }
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "启动安装失败", e)
            Toast.makeText(
                activity,
                activity.getString(R.string.update_download_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
