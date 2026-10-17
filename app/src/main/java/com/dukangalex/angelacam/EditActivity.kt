package com.dukangalex.angelacam

import android.app.WallpaperManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityEditBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 照片编辑器（仅照片）：
 * 裁剪（原图/1:1/4:3/16:9 中心裁剪）、旋转（左右 90°）、镜像、
 * 滤镜（6 种）、调节（亮度/对比度/饱和度）。
 * 结构编辑基于原图重建（无损撤销）；色彩编辑实时预览；保存为新副本。
 */
class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        /** 壁纸模式：编辑后直接设为壁纸（参照 Google 壁纸流程） */
        const val EXTRA_WALLPAPER_MODE = "extra_wallpaper_mode"
        private const val TAG = "EditPhoto"
        private const val MAX_SIZE = 2048

        private const val TOOL_CROP = 0
        private const val TOOL_ROTATE = 1
        private const val TOOL_FILTER = 2
        private const val TOOL_ADJUST = 3
    }

    private lateinit var binding: ActivityEditBinding
    private val executor = Executors.newSingleThreadExecutor()

    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null

    private var wallpaperMode = false
    private var screenRatioW = 0f
    private var screenRatioH = 0f

    // 结构编辑状态（基于原图重建）
    private var cropRatioW = 0f // 0=原图
    private var cropRatioH = 0f
    private var rotation = 0 // 累计 0/90/180/270
    private var flipped = false

    // 色彩编辑状态
    private var filterIndex = 0
    private var brightness = 0 // -100..100
    private var contrast = 100 // 0..200，100=1.0
    private var saturation = 100 // 0..200，100=1.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        val uri = Uri.parse(uriStr)
        wallpaperMode = intent.getBooleanExtra(EXTRA_WALLPAPER_MODE, false)
        if (wallpaperMode) {
            // 屏幕比例（竖屏），参照 Google 壁纸默认按屏幕裁剪
            val dm = resources.displayMetrics
            screenRatioW = dm.widthPixels.toFloat()
            screenRatioH = dm.heightPixels.toFloat()
            binding.tvTitle.text = getString(R.string.edit_set_wallpaper)
            binding.btnSave.text = getString(R.string.edit_set_wallpaper)
            binding.btnCropScreen.visibility = View.VISIBLE
        }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener {
            if (wallpaperMode) applyAsWallpaper() else save()
        }

        binding.tabCrop.setOnClickListener { showTool(TOOL_CROP) }
        binding.tabRotate.setOnClickListener { showTool(TOOL_ROTATE) }
        binding.tabFilter.setOnClickListener { showTool(TOOL_FILTER) }
        binding.tabAdjust.setOnClickListener { showTool(TOOL_ADJUST) }

        binding.btnCropOriginal.setOnClickListener { setCrop(0f, 0f); markCropSelected(binding.btnCropOriginal) }
        binding.btnCrop11.setOnClickListener { setCrop(1f, 1f); markCropSelected(binding.btnCrop11) }
        binding.btnCrop43.setOnClickListener { setCrop(4f, 3f); markCropSelected(binding.btnCrop43) }
        binding.btnCrop169.setOnClickListener { setCrop(16f, 9f); markCropSelected(binding.btnCrop169) }
        binding.btnCropScreen.setOnClickListener {
            setCrop(screenRatioW, screenRatioH)
            markCropSelected(binding.btnCropScreen)
        }

        binding.btnRotateLeft.setOnClickListener { rotation = (rotation + 270) % 360; rebuildWorking() }
        binding.btnRotateRight.setOnClickListener { rotation = (rotation + 90) % 360; rebuildWorking() }
        binding.btnFlip.setOnClickListener { flipped = !flipped; rebuildWorking() }

        binding.btnFilterNone.setOnClickListener { setFilter(0, binding.btnFilterNone) }
        binding.btnFilterBw.setOnClickListener { setFilter(1, binding.btnFilterBw) }
        binding.btnFilterSepia.setOnClickListener { setFilter(2, binding.btnFilterSepia) }
        binding.btnFilterCool.setOnClickListener { setFilter(3, binding.btnFilterCool) }
        binding.btnFilterWarm.setOnClickListener { setFilter(4, binding.btnFilterWarm) }
        binding.btnFilterVivid.setOnClickListener { setFilter(5, binding.btnFilterVivid) }

        setupAdjustSeek(binding.seekBrightness) { brightness = it - 100; updatePreview() }
        setupAdjustSeek(binding.seekContrast) { contrast = it; updatePreview() }
        setupAdjustSeek(binding.seekSaturation) { saturation = it; updatePreview() }

        markCropSelected(binding.btnCropOriginal)
        setFilter(0, binding.btnFilterNone)
        showTool(TOOL_CROP)

        executor.execute {
            try {
                originalBitmap = loadSampledBitmap(uri)
                runOnUiThread {
                    if (originalBitmap == null) {
                        Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        if (wallpaperMode) {
                            // 壁纸模式默认按屏幕比例裁剪（参照 Google 壁纸）
                            setCrop(screenRatioW, screenRatioH)
                            markCropSelected(binding.btnCropScreen)
                        } else {
                            rebuildWorking()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载图片失败", e)
                runOnUiThread {
                    Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        originalBitmap?.recycle()
        workingBitmap?.recycle()
    }

    // ---------- 加载 ----------

    /** 按最大边 MAX_SIZE 采样解码，避免大图 OOM */
    private fun loadSampledBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > MAX_SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    // ---------- 工具页签 ----------

    private fun showTool(tool: Int) {
        binding.cropOptions.visibility = if (tool == TOOL_CROP) View.VISIBLE else View.GONE
        binding.rotateOptions.visibility = if (tool == TOOL_ROTATE) View.VISIBLE else View.GONE
        binding.filterOptions.visibility = if (tool == TOOL_FILTER) View.VISIBLE else View.GONE
        binding.adjustOptions.visibility = if (tool == TOOL_ADJUST) View.VISIBLE else View.GONE
        val active = 0xFF4CAF50.toInt()
        val idle = 0xFFFFFFFF.toInt()
        binding.tabCrop.setTextColor(if (tool == TOOL_CROP) active else idle)
        binding.tabRotate.setTextColor(if (tool == TOOL_ROTATE) active else idle)
        binding.tabFilter.setTextColor(if (tool == TOOL_FILTER) active else idle)
        binding.tabAdjust.setTextColor(if (tool == TOOL_ADJUST) active else idle)
    }

    private fun markCropSelected(selected: Button) {
        val buttons = listOf(
            binding.btnCropOriginal, binding.btnCrop11,
            binding.btnCrop43, binding.btnCrop169, binding.btnCropScreen
        )
        highlightSelected(buttons, selected)
    }

    private fun highlightSelected(buttons: List<Button>, selected: Button) {
        val active = 0xFF4CAF50.toInt()
        val idle = 0xFFFFFFFF.toInt()
        buttons.forEach { it.setTextColor(if (it == selected) active else idle) }
    }

    // ---------- 结构编辑 ----------

    private fun setCrop(w: Float, h: Float) {
        cropRatioW = w
        cropRatioH = h
        rebuildWorking()
    }

    private fun setFilter(index: Int, button: Button) {
        filterIndex = index
        val buttons = listOf(
            binding.btnFilterNone, binding.btnFilterBw, binding.btnFilterSepia,
            binding.btnFilterCool, binding.btnFilterWarm, binding.btnFilterVivid
        )
        highlightSelected(buttons, button)
        updatePreview()
    }

    private fun setupAdjustSeek(seekBar: SeekBar, onChange: (Int) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** 从原图按当前结构状态重建工作位图 */
    private fun rebuildWorking() {
        val src = originalBitmap ?: return
        var bmp = src

        // 中心裁剪
        if (cropRatioW > 0 && cropRatioH > 0) {
            val target = cropRatioW / cropRatioH
            val srcRatio = bmp.width.toFloat() / bmp.height
            bmp = if (srcRatio > target) {
                val w = (bmp.height * target).toInt()
                Bitmap.createBitmap(bmp, (bmp.width - w) / 2, 0, w, bmp.height)
            } else {
                val h = (bmp.width / target).toInt()
                Bitmap.createBitmap(bmp, 0, (bmp.height - h) / 2, bmp.width, h)
            }
        }

        // 旋转 + 镜像（中间位图不是原图时回收）
        if (rotation != 0 || flipped) {
            val matrix = Matrix()
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (flipped) matrix.postScale(-1f, 1f)
            val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (bmp !== src) bmp.recycle()
            bmp = transformed
        }

        workingBitmap?.let { if (it !== originalBitmap) it.recycle() }
        workingBitmap = bmp
        updatePreview()
    }

    // ---------- 色彩编辑 ----------

    private fun buildColorMatrix(): ColorMatrix {
        // 调节矩阵：饱和度 → 对比度 → 亮度
        val sat = ColorMatrix().apply { setSaturation(saturation / 100f) }
        val c = contrast / 100f
        val t = (1f - c) * 128f
        val con = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val b = brightness * 2.55f
        val bri = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val adjust = ColorMatrix()
        adjust.postConcat(bri)
        adjust.postConcat(con)
        adjust.postConcat(sat)

        // 滤镜矩阵（后应用）
        val filter = filterMatrix(filterIndex)
        filter.postConcat(adjust)
        return filter
    }

    private fun filterMatrix(index: Int): ColorMatrix {
        return when (index) {
            1 -> ColorMatrix().apply { setSaturation(0f) } // 黑白
            2 -> ColorMatrix( // 复古 sepia
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            3 -> ColorMatrix( // 冷调
                floatArrayOf(
                    0.92f, 0f, 0f, 0f, 0f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 1.12f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            4 -> ColorMatrix( // 暖调
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 6f,
                    0f, 1.02f, 0f, 0f, 2f,
                    0f, 0f, 0.88f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            5 -> { // 鲜艳：饱和度 1.4 + 对比度 1.06
                val vivid = ColorMatrix(
                    floatArrayOf(
                        1.06f, 0f, 0f, 0f, -8f,
                        0f, 1.06f, 0f, 0f, -8f,
                        0f, 0f, 1.06f, 0f, -8f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                vivid.postConcat(ColorMatrix().apply { setSaturation(1.4f) })
                vivid
            }
            else -> ColorMatrix() // 原图
        }
    }

    private fun updatePreview() {
        val bmp = workingBitmap ?: return
        binding.ivPreview.setImageBitmap(bmp)
        binding.ivPreview.colorFilter = ColorMatrixColorFilter(buildColorMatrix())
    }

    // ---------- 保存 ----------

    /** 按当前编辑状态渲染最终位图（含滤镜/调节），调用方负责回收 */
    private fun renderFinalBitmap(): Bitmap? {
        val bmp = workingBitmap ?: return null
        val matrix = buildColorMatrix()
        val result = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return result
    }

    private fun save() {
        if (workingBitmap == null) return
        binding.btnSave.isEnabled = false
        executor.execute {
            try {
                val result = renderFinalBitmap() ?: throw IllegalStateException("渲染失败")

                val name = "AngelaCam_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                }_edited.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AngelaCam")
                    }
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert 失败")
                contentResolver.openOutputStream(uri)?.use { os ->
                    if (!result.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
                        throw IllegalStateException("JPEG 压缩失败")
                    }
                } ?: throw IllegalStateException("无法打开输出流")
                result.recycle()
                CaptureStore.add(this, uri, "image/jpeg", name)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.edit_saved), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存失败", e)
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- 壁纸模式 ----------

    /** 壁纸模式：渲染当前编辑效果，直接设为壁纸（不另存文件） */
    private fun applyAsWallpaper() {
        if (workingBitmap == null) return
        binding.btnSave.isEnabled = false
        executor.execute {
            try {
                val result = renderFinalBitmap() ?: throw IllegalStateException("渲染失败")
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    chooseWallpaperTarget(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "渲染失败", e)
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this, R.string.wallpaper_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun chooseWallpaperTarget(bitmap: Bitmap) {
        val options = arrayOf(
            getString(R.string.wallpaper_home),
            getString(R.string.wallpaper_lock),
            getString(R.string.wallpaper_both)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_title)
            .setItems(options) { _, which -> doSetWallpaper(bitmap, which) }
            .setOnCancelListener { bitmap.recycle() }
            .show()
    }

    private fun doSetWallpaper(bitmap: Bitmap, which: Int) {
        executor.execute {
            try {
                val wm = WallpaperManager.getInstance(this)
                if (which == 0 || which == 2) {
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                }
                if (which == 1 || which == 2) {
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                }
                runOnUiThread {
                    Toast.makeText(this, R.string.wallpaper_done, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置壁纸失败", e)
                runOnUiThread {
                    Toast.makeText(this, R.string.wallpaper_fail, Toast.LENGTH_SHORT).show()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
