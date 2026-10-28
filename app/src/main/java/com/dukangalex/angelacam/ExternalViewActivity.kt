package com.dukangalex.angelacam

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityExternalViewBinding
import java.util.concurrent.Executors

/**
 * 系统"打开方式"入口（v1.5.0）：
 * - intent-filter：VIEW 图片、VIEW 视频、SEND 图片、SEND_MULTIPLE 图片
 * - 在系统"使用以下方式打开"/分享列表中出现，与相册、Snapseed 同列
 * - 仅查看/编辑/设为壁纸/分享，不导入到本应用相册（相册只收录本应用拍摄内容）
 * - 通过发送方授予的临时 URI 权限读取，不申请存储读取权限
 */
class ExternalViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExternalView"
        private const val MAX_SIZE = 2048
        private const val WALLPAPER_MAX_SIZE = 2048
    }

    private lateinit var binding: ActivityExternalViewBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var mediaUri: Uri? = null
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExternalViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaUri = resolveUri()
        if (mediaUri == null) {
            finish()
            return
        }
        val mime = contentResolver.getType(mediaUri!!) ?: ""
        isVideo = mime.startsWith("video")

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener { openEditor() }
        binding.btnWallpaper.setOnClickListener { chooseWallpaperMode() }
        binding.btnShare.setOnClickListener { share() }

        if (isVideo) {
            binding.btnEdit.visibility = View.GONE
            binding.btnWallpaper.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            binding.imageView.visibility = View.GONE
            binding.videoView.setVideoURI(mediaUri)
            val mc = MediaController(this)
            mc.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mc)
            binding.videoView.setOnPreparedListener { it.start() }
        } else {
            binding.videoView.visibility = View.GONE
            binding.imageView.visibility = View.VISIBLE
            val uri = mediaUri!!
            executor.execute {
                try {
                    val bmp = loadSampledBitmap(uri)
                    runOnUiThread {
                        if (bmp == null) {
                            Toast.makeText(this, R.string.toast_image_load_failed, Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            binding.imageView.setImageBitmap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载图片失败", e)
                    runOnUiThread {
                        Toast.makeText(this, R.string.toast_image_load_failed, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    /**
     * VIEW 取 intent.data；SEND/SEND_MULTIPLE 取 EXTRA_STREAM（多选只取第一张）。
     * 只接受 content/file 两类本地地址：本入口是 exported 的，任意应用都能发 intent
     * 过来，若照单全收 http(s) 地址，VideoView 会以本应用身份联网取流——等于被无
     * 网络权限的应用借道联网，也违反本应用「只连接 GitHub」的承诺。
     */
    private fun resolveUri(): Uri? {
        val raw: Uri? = intent.data ?: when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.firstOrNull()
            }
            else -> null
        }
        if (raw == null) return null
        return if (raw.scheme == "content" || raw.scheme == "file") {
            raw
        } else {
            Log.w(TAG, "拒绝非本地地址 scheme=${raw.scheme}")
            null
        }
    }

    private fun loadSampledBitmap(uri: Uri, maxSize: Int = MAX_SIZE) =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > maxSize) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解码失败", e)
            null
        }

    // ---------- 编辑 ----------

    private fun openEditor() {
        val uri = mediaUri ?: return
        if (isVideo) return
        val intent = Intent(this, EditActivity::class.java).apply {
            putExtra(EditActivity.EXTRA_URI, uri.toString())
        }
        startActivity(intent)
    }

    // ---------- 设为壁纸 ----------

    private fun chooseWallpaperMode() {
        val uri = mediaUri ?: return
        if (isVideo) return
        val options = arrayOf(
            getString(R.string.wallpaper_direct),
            getString(R.string.wallpaper_edit_first)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_choose_mode)
            .setItems(options) { _, which ->
                if (which == 0) {
                    chooseWallpaperTarget(uri)
                } else {
                    val intent = Intent(this, EditActivity::class.java).apply {
                        putExtra(EditActivity.EXTRA_URI, uri.toString())
                        putExtra(EditActivity.EXTRA_WALLPAPER_MODE, true)
                    }
                    startActivity(intent)
                }
            }
            .show()
    }

    private fun chooseWallpaperTarget(uri: Uri) {
        val options = arrayOf(
            getString(R.string.wallpaper_home),
            getString(R.string.wallpaper_lock),
            getString(R.string.wallpaper_both)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_title)
            .setItems(options) { _, which -> doSetWallpaper(uri, which) }
            .show()
    }

    private fun doSetWallpaper(uri: Uri, which: Int) {
        executor.execute {
            try {
                val bitmap = loadSampledBitmap(uri, WALLPAPER_MAX_SIZE)
                    ?: throw IllegalStateException("图片解码失败")
                val wm = WallpaperManager.getInstance(this)
                if (which == 0 || which == 2) {
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                }
                if (which == 1 || which == 2) {
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                }
                bitmap.recycle()
                runOnUiThread {
                    Toast.makeText(this, R.string.wallpaper_done, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置壁纸失败", e)
                runOnUiThread {
                    Toast.makeText(this, R.string.wallpaper_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- 分享 ----------

    private fun share() {
        val uri = mediaUri ?: return
        val mime = contentResolver.getType(uri) ?: if (isVideo) "video/*" else "image/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_SHORT).show()
        }
    }
}
