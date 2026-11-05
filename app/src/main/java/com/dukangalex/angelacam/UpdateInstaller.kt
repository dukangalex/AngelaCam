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
 * 安全更新入口：应用不在自身进程下载或安装 APK。设置页的新版本状态行单击后，
 * 直接通过浏览器打开经 HTTPS 与域名白名单校验的 APK 下载地址；关于页的确认弹窗
 * 仍然打开 GitHub Release 页面。保留此类与现有调用点，避免改变其它更新流程。
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
