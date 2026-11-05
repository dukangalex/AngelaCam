package com.dukangalex.angelacam

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.net.URL

/**
 * 更新提示（2026-10-28 审计处置，类名沿用以免动调用点）：
 * 本应用不再下载、安装更新包——「侧载应用自带装包能力」正是 Play Protect
 * 把应用判为有害的主要行为信号，且 REQUEST_INSTALL_PACKAGES 属于高敏感权限。
 * 现在只做一件事：弹窗告知新版本，用户点「前往下载新版本」后用浏览器打开
 * GitHub Release 页自行下载安装（与关于页的手动更新指引一致）。
 * 跳转前照旧校验域名白名单与 https，不打开白名单外的地址。
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    /** 允许打开的域名白名单（与 UpdateChecker 一致） */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com"
    )

    /** 直接在浏览器打开经过白名单校验的 APK 地址，不经过发行说明页。 */
    fun openApkDownload(activity: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val url = URL(info.downloadUrl)
            require(url.protocol == "https" && url.host in ALLOWED_HOSTS) { "下载地址不在白名单" }
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
        } catch (e: Exception) {
            Log.w(TAG, "打开 APK 下载失败", e)
            Toast.makeText(
                activity,
                activity.getString(R.string.update_open_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 关于页的手动检查仍保留发行说明确认流程。 */
    fun promptUpdate(activity: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            showUpdateDialog(activity, info)
            return
        }
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        source.lifecycle.removeObserver(this)
                        if (!activity.isFinishing && !activity.isDestroyed &&
                            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            showUpdateDialog(activity, info)
                        }
                    }
                    Lifecycle.Event.ON_DESTROY -> source.lifecycle.removeObserver(this)
                    else -> Unit
                }
            }
        }
        activity.lifecycle.addObserver(observer)
    }

    private fun showUpdateDialog(activity: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        val notes = info.notes.trim().ifEmpty { activity.getString(R.string.update_notes_empty) }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, info.version))
            .setMessage(notes)
            .setPositiveButton(R.string.about_open_releases) { _, _ -> openReleasePage(activity, info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun openReleasePage(activity: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        try {
            val url = URL(info.htmlUrl)
            require(url.protocol == "https" && url.host in ALLOWED_HOSTS) { "地址不在白名单" }
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
        } catch (e: Exception) {
            Log.w(TAG, "打开下载页面失败", e)
            Toast.makeText(
                activity,
                activity.getString(R.string.update_open_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
