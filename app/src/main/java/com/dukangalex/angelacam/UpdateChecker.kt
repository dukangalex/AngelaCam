package com.dukangalex.angelacam

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查（隐私设计，用户 2026-10-17 批准）：
 * - 仅在用户点击"检查更新"或开启"启动时自动检查"时联网，默认不自动联网
 * - 只访问 GitHub 域名（代码写死白名单）
 * - 不发送任何用户数据：仅一个普通 GET 请求（GitHub API 要求带 User-Agent）
 * - 照片/视频永远不出设备
 * - 开源可审计（GPL-3.0）
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val API_URL = "https://api.github.com/repos/dukangalex/AngelaCam/releases/latest"

    /** 允许联网的域名白名单：只允许 GitHub */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com"
    )

    data class UpdateInfo(
        val version: String, // 如 "1.3.0"
        val notes: String, // release 说明
        val downloadUrl: String, // app-release.apk 下载地址
        val htmlUrl: String // release 页面
    )

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        object UpToDate : Result()
        data class Failed(val reason: String) : Result()
    }

    fun currentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    /** 同步检查，必须在后台线程调用 */
    fun check(context: Context): Result {
        try {
            val apiUrl = URL(API_URL)
            require(apiUrl.host in ALLOWED_HOSTS) { "主机不在白名单" }
            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "AngelaCam")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return Result.Failed("服务器返回 ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val version = json.optString("tag_name", "").removePrefix("v")
                if (version.isEmpty()) return Result.Failed("解析版本失败")
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == "app-release.apk") {
                            downloadUrl = a.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                if (downloadUrl.isEmpty()) return Result.Failed("未找到安装包")
                // 白名单同时验域名与协议：只认 https，防明文降级
                val dl = URL(downloadUrl)
                require(dl.protocol == "https" && dl.host in ALLOWED_HOSTS) { "下载地址不在白名单" }
                val page = URL(json.optString("html_url", ""))
                require(page.protocol == "https" && page.host in ALLOWED_HOSTS) { "页面地址不在白名单" }
                return if (isNewer(version, currentVersion(context))) {
                    Result.Available(
                        UpdateInfo(
                            version = version,
                            notes = json.optString("body", ""),
                            downloadUrl = downloadUrl,
                            htmlUrl = json.optString("html_url", "")
                        )
                    )
                } else {
                    Result.UpToDate
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败", e)
            return Result.Failed(e.message ?: "网络错误")
        }
    }

    /** 语义版本比较："1.3.0" > "1.2.1" */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrNull(i) ?: 0
            val lv = l.getOrNull(i) ?: 0
            if (rv != lv) return rv > lv
        }
        return false
    }
}
