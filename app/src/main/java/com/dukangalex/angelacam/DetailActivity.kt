package com.dukangalex.angelacam

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dukangalex.angelacam.databinding.ActivityDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 照片/视频查看器 v1.2.0：
 * 左右滑动切换、删除、编辑（照片）、设为壁纸（照片）、详细信息、分享、幻灯片。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POSITION = "extra_position"
        private const val TAG = "Detail"
        private const val SLIDESHOW_INTERVAL = 3000L
        private const val WALLPAPER_MAX_SIZE = 2048
        private const val DETAIL_MAX_SIZE = 2048
    }

    private lateinit var binding: ActivityDetailBinding
    private var items: MutableList<CaptureStore.Item> = mutableListOf()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var playingVideoPos = -1
    private var slideshowOn = false
    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (!slideshowOn || items.isEmpty()) return
            val next = (binding.viewPager.currentItem + 1) % items.size
            binding.viewPager.setCurrentItem(next, true)
            uiHandler.postDelayed(this, SLIDESHOW_INTERVAL)
        }
    }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            stopPlayingVideo(except = position)
            updatePageUi(position)
            startVideoIfNeeded(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.registerOnPageChangeCallback(pageCallback)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSlideshow.setOnClickListener { toggleSlideshow() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnEdit.setOnClickListener { openEditor() }
        binding.btnWallpaper.setOnClickListener { chooseWallpaperTarget() }
        binding.btnInfo.setOnClickListener { showInfo() }
        binding.btnShare.setOnClickListener { share() }

        // 列表加载含逐条存活探测（IO），放后台线程，不再卡 onCreate 主线程
        ioExecutor.execute {
            val loaded = CaptureStore.list(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (loaded.isEmpty()) {
                    finish()
                    return@runOnUiThread
                }
                items = loaded.toMutableList()
                val startPos = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(items.indices)
                binding.viewPager.adapter = PagerAdapter()
                binding.viewPager.setCurrentItem(startPos, false)
                updatePageUi(startPos)
                startVideoIfNeeded(startPos)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (slideshowOn) {
            uiHandler.postDelayed(slideshowRunnable, SLIDESHOW_INTERVAL)
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(slideshowRunnable)
        stopPlayingVideo(except = -1)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(slideshowRunnable)
        binding.viewPager.unregisterOnPageChangeCallback(pageCallback)
        ioExecutor.shutdown()
    }

    private fun currentItem(): CaptureStore.Item? =
        items.getOrNull(binding.viewPager.currentItem)

    private fun updatePageUi(position: Int) {
        val item = items.getOrNull(position) ?: return
        binding.tvCounter.text = "${position + 1} / ${items.size}"
        val isPhoto = !item.isVideo
        binding.btnEdit.visibility = if (isPhoto) View.VISIBLE else View.GONE
        binding.btnWallpaper.visibility = if (isPhoto) View.VISIBLE else View.GONE
    }

    // ---------- 滑动分页适配器 ----------

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class PhotoVH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
        inner class VideoVH(val videoView: VideoView) : RecyclerView.ViewHolder(videoView)

        override fun getItemViewType(position: Int): Int =
            if (items[position].isVideo) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val videoView = VideoView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val controller = MediaController(parent.context)
                controller.setAnchorView(videoView)
                videoView.setMediaController(controller)
                VideoVH(videoView)
            } else {
                val imageView = ImageView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                PhotoVH(imageView)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val uri = Uri.parse(items[position].uri)
            when (holder) {
                is PhotoVH -> {
                    // 后台采样解码（上限 2048）：原 setImageURI 在主线程全分辨率
                    // 同步解码，12MP 单张约 48MB，滑动必卡且有 OOM 风险
                    holder.imageView.tag = items[position].uri
                    holder.imageView.setImageDrawable(null)
                    ioExecutor.execute {
                        val bmp = loadSampledBitmap(uri, DETAIL_MAX_SIZE)
                        runOnUiThread {
                            if (holder.imageView.tag == items.getOrNull(position)?.uri) {
                                holder.imageView.setImageBitmap(bmp)
                            }
                        }
                    }
                }
                is VideoVH -> {
                    holder.videoView.setVideoURI(uri)
                    holder.videoView.setOnPreparedListener { mp ->
                        if (binding.viewPager.currentItem == holder.bindingAdapterPosition) {
                            mp.start()
                            playingVideoPos = holder.bindingAdapterPosition
                        }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VideoVH) {
                holder.videoView.stopPlayback()
            }
            if (holder is PhotoVH) {
                holder.imageView.tag = null
                holder.imageView.setImageDrawable(null)
            }
            super.onViewRecycled(holder)
        }
    }

    private fun findVideoHolder(position: Int): PagerAdapter.VideoVH? {
        val rv = binding.viewPager.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(position) as? PagerAdapter.VideoVH
    }

    private fun startVideoIfNeeded(position: Int) {
        val vh = findVideoHolder(position) ?: return
        try {
            if (!vh.videoView.isPlaying) vh.videoView.start()
            playingVideoPos = position
        } catch (e: Exception) {
            Log.w(TAG, "视频播放失败", e)
        }
    }

    private fun stopPlayingVideo(except: Int) {
        if (playingVideoPos >= 0 && playingVideoPos != except) {
            try {
                findVideoHolder(playingVideoPos)?.videoView?.let {
                    if (it.isPlaying) it.pause()
                }
            } catch (e: Exception) {
                Log.w(TAG, "视频暂停失败", e)
            }
            playingVideoPos = -1
        }
    }

    // ---------- 幻灯片 ----------

    private fun toggleSlideshow() {
        slideshowOn = !slideshowOn
        binding.btnSlideshow.alpha = if (slideshowOn) 1f else 0.45f
        if (slideshowOn) {
            uiHandler.postDelayed(slideshowRunnable, SLIDESHOW_INTERVAL)
            Toast.makeText(this, R.string.toast_slideshow_on, Toast.LENGTH_SHORT).show()
        } else {
            uiHandler.removeCallbacks(slideshowRunnable)
        }
    }

    // ---------- 删除 ----------

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setPositiveButton(R.string.delete_confirm_ok) { _, _ -> doDelete() }
            .setNegativeButton(R.string.delete_confirm_cancel, null)
            .show()
    }

    private fun doDelete() {
        val pos = binding.viewPager.currentItem
        val item = items.getOrNull(pos) ?: return
        val uri = Uri.parse(item.uri)
        try {
            contentResolver.delete(uri, null, null)
            CaptureStore.remove(this, uri.toString())
            Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
            return
        }
        stopPlayingVideo(except = -1)
        if (slideshowOn) toggleSlideshow()
        items.removeAt(pos)
        if (items.isEmpty()) {
            finish()
            return
        }
        binding.viewPager.adapter?.notifyItemRemoved(pos)
        val newPos = pos.coerceIn(items.indices)
        // notify 后若位置未变不会触发 onPageSelected，手动刷新
        updatePageUi(binding.viewPager.currentItem.coerceIn(items.indices))
        if (binding.viewPager.currentItem != newPos) {
            binding.viewPager.setCurrentItem(newPos, false)
        } else {
            startVideoIfNeeded(newPos)
        }
    }

    // ---------- 编辑 ----------

    private fun openEditor() {
        val item = currentItem() ?: return
        if (item.isVideo) {
            Toast.makeText(this, R.string.toast_video_edit_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditActivity::class.java).apply {
            putExtra(EditActivity.EXTRA_URI, item.uri)
        }
        startActivity(intent)
    }

    // ---------- 设为壁纸 ----------

    private fun chooseWallpaperTarget() {
        val item = currentItem() ?: return
        if (item.isVideo) return
        val options = arrayOf(
            getString(R.string.wallpaper_direct),
            getString(R.string.wallpaper_edit_first)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_choose_mode)
            .setItems(options) { _, which ->
                if (which == 0) {
                    chooseWallpaperDestination(item)
                } else {
                    val intent = Intent(this, EditActivity::class.java).apply {
                        putExtra(EditActivity.EXTRA_URI, item.uri)
                        putExtra(EditActivity.EXTRA_WALLPAPER_MODE, true)
                    }
                    startActivity(intent)
                }
            }
            .show()
    }

    private fun chooseWallpaperDestination(item: CaptureStore.Item) {
        val options = arrayOf(
            getString(R.string.wallpaper_home),
            getString(R.string.wallpaper_lock),
            getString(R.string.wallpaper_both)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_title)
            .setItems(options) { _, which -> doSetWallpaper(item, which) }
            .show()
    }

    private fun doSetWallpaper(item: CaptureStore.Item, which: Int) {
        ioExecutor.execute {
            try {
                val bitmap = loadSampledBitmap(Uri.parse(item.uri), WALLPAPER_MAX_SIZE)
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

    private fun loadSampledBitmap(uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    // ---------- 详细信息 ----------

    private fun showInfo() {
        val item = currentItem() ?: return
        ioExecutor.execute {
            val text = buildInfoText(item)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.info_title)
                    .setMessage(text)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show()
            }
        }
    }

    private fun buildInfoText(item: CaptureStore.Item): String {
        val uri = Uri.parse(item.uri)
        var displayName = item.name
        var size = -1L
        var width = 0
        var height = 0
        var dateModified = 0L
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
                    MediaStore.MediaColumns.DATE_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0) ?: displayName
                    size = c.getLong(1)
                    width = c.getInt(2)
                    height = c.getInt(3)
                    dateModified = c.getLong(4)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取媒体信息失败", e)
        }
        val typeStr = if (item.isVideo) getString(R.string.type_video) else getString(R.string.type_photo)
        val sizeStr = if (size >= 0) formatSize(size) else "—"
        val dimStr = if (width > 0 && height > 0) "$width × $height" else "—"
        val dateStr = if (dateModified > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(dateModified * 1000))
        } else "—"
        return "${getString(R.string.info_name)}：$displayName\n" +
            "${getString(R.string.info_type)}：$typeStr\n" +
            "${getString(R.string.info_dimensions)}：$dimStr\n" +
            "${getString(R.string.info_size)}：$sizeStr\n" +
            "${getString(R.string.info_date)}：$dateStr"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---------- 分享 ----------

    private fun share() {
        val item = currentItem() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_SHORT).show()
        }
    }
}
