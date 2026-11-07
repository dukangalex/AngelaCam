package com.dukangalex.angelacam

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityExternalViewBinding
import java.util.Locale
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
    private var resolvedMimeType: String? = null
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExternalViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NavigationBarInsets.applyTo(binding.root)

        mediaUri = resolveUri()
        if (mediaUri == null) {
            finish()
            return
        }
        isVideo = resolvedMimeType?.startsWith("video/") == true

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener { openEditor() }
        binding.btnWallpaper.setOnClickListener { chooseWallpaperMode() }
        binding.btnShare.setOnClickListener { share() }

        if (isVideo) {
            binding.btnEdit.visibility = View.GONE
            binding.btnWallpaper.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            binding.imageView.visibility = View.GONE
            val mc = MediaController(this)
            mc.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mc)
            binding.videoView.setOnPreparedListener { it.start() }
            binding.videoView.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "外部视频播放失败 what=$what extra=$extra")
                Toast.makeText(this, R.string.toast_video_load_failed, Toast.LENGTH_SHORT).show()
                finish()
                true
            }
            try {
                binding.videoView.setVideoURI(mediaUri)
            } catch (e: Exception) {
                Log.e(TAG, "打开外部视频失败", e)
                Toast.makeText(this, R.string.toast_video_load_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
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

    /** Resolve only expected share/view actions and local or provider-backed content URIs. */
    private fun resolveUri(): Uri? {
        val raw: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> singleStreamUri() ?: firstClipUri()
            Intent.ACTION_SEND_MULTIPLE -> multipleStreamUris().firstOrNull()
            else -> null
        }
        if (raw == null) return null
        if (raw.scheme !in setOf("content", "file")) {
            Log.w(TAG, "拒绝非本地地址 scheme=${raw.scheme}")
            return null
        }

        val declaredMime = normalizeMime(intent.type)
        val providerMime = runCatching { contentResolver.getType(raw) }
            .getOrNull()
            .let(::normalizeMime)
        val supportedProviderMime = providerMime?.takeIf(::isSupportedMediaMime)
        val supportedDeclaredMime = declaredMime?.takeIf(::isSupportedMediaMime)
        if (supportedProviderMime != null && supportedDeclaredMime != null &&
            supportedProviderMime.substringBefore('/') != supportedDeclaredMime.substringBefore('/')
        ) {
            Log.w(TAG, "拒绝与内容类型不匹配的媒体 URI")
            return null
        }

        val inferredMime = raw.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?.let(::normalizeMime)
        resolvedMimeType = supportedProviderMime
            ?: supportedDeclaredMime
            ?: inferredMime?.takeIf(::isSupportedMediaMime)
        if (resolvedMimeType == null) {
            Log.w(TAG, "拒绝类型未知或非图片/视频的 URI")
            return null
        }
        return raw
    }

    private fun singleStreamUri(): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            legacySingleStreamUri()
        }
    }.onFailure { Log.w(TAG, "忽略无效的分享 URI", it) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacySingleStreamUri(): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun multipleStreamUris(): List<Uri> {
        val fromExtra = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                legacyMultipleStreamUris()
            }
        }.onFailure { Log.w(TAG, "忽略无效的分享 URI 列表", it) }.getOrNull()
        return fromExtra?.takeIf { it.isNotEmpty() } ?: clipUris()
    }

    @Suppress("DEPRECATION")
    private fun legacyMultipleStreamUris(): ArrayList<Uri>? =
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    private fun firstClipUri(): Uri? = runCatching {
        intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }.onFailure { Log.w(TAG, "忽略无效的分享 ClipData", it) }.getOrNull()

    private fun clipUris(): List<Uri> = runCatching {
        intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
    }.onFailure { Log.w(TAG, "忽略无效的分享 ClipData", it) }.getOrDefault(emptyList())

    private fun normalizeMime(mime: String?): String? =
        mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun isSupportedMediaMime(mime: String): Boolean =
        mime.startsWith("image/") || mime.startsWith("video/")

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
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: if (isVideo) "video/*" else "image/*"
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
