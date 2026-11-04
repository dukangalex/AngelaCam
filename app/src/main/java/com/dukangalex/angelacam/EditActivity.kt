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
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityEditBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 照片编辑器（仅照片）：
 * 裁剪（中心裁剪，多比例）、旋转（90° + 拉正微调 ±45°）、水平/垂直镜像、
 * 滤镜（6 种）、调节（曝光/亮度/对比度/高光/阴影/色温/色调/饱和度）、
 * 一键自动增强、按住对比原图、一键重置。
 * 结构编辑基于原图重建；预览用降采样图，保存时按全分辨率（上限 4096）重走同一管线，
 * 避免编辑保存把照片压小。外部图片按 EXIF 方向自动摆正。
 */
class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        /** 壁纸模式：编辑后直接设为壁纸（参照 Google 壁纸流程） */
        const val EXTRA_WALLPAPER_MODE = "extra_wallpaper_mode"
        private const val TAG = "EditPhoto"
        private const val MAX_SIZE = 2048
        private const val SAVE_MAX_SIZE = 4096

        private const val TOOL_CROP = 0
        private const val TOOL_ROTATE = 1
        private const val TOOL_FILTER = 2
        private const val TOOL_ADJUST = 3

        private const val STATE_CROP_W = "crop_w"
        private const val STATE_CROP_H = "crop_h"
        private const val STATE_ROTATION = "rotation"
        private const val STATE_STRAIGHTEN = "straighten"
        private const val STATE_FLIP_H = "flip_h"
        private const val STATE_FLIP_V = "flip_v"
        private const val STATE_FILTER = "filter"
        private const val STATE_EXPOSURE = "exposure"
        private const val STATE_BRIGHTNESS = "brightness"
        private const val STATE_CONTRAST = "contrast"
        private const val STATE_HIGHLIGHTS = "highlights"
        private const val STATE_SHADOWS = "shadows"
        private const val STATE_TEMPERATURE = "temperature"
        private const val STATE_TINT = "tint"
        private const val STATE_SATURATION = "saturation"
        private const val STATE_ADJUSTMENT = "adjustment"
    }

    private lateinit var binding: ActivityEditBinding
    private val executor = Executors.newSingleThreadExecutor()

    private var sourceUri: Uri? = null
    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null
    /** 色调 LUT 处理后的预览图；为 null 表示直接用 workingBitmap */
    private var tonalBitmap: Bitmap? = null
    private var tonalGen = 0

    private var wallpaperMode = false
    private var screenRatioW = 0f
    private var screenRatioH = 0f

    // 结构编辑状态（基于原图重建）
    private var cropRatioW = 0f // 0=原图
    private var cropRatioH = 0f
    private var rotation = 0 // 累计 0/90/180/270
    private var straightenDeg = 0f // 拉正 -45..45
    private var flipH = false
    private var flipV = false

    // 色彩编辑状态
    private var filterIndex = 0
    private var exposure = 0 // -100..100
    private var brightness = 0 // -100..100
    private var contrast = 100 // 0..200，100=1.0
    private var highlights = 0 // -100..100
    private var shadows = 0 // -100..100
    private var temperature = 0 // -100..100，负=冷 正=暖
    private var tint = 0 // -100..100，负=绿 正=品红
    private var saturation = 100 // 0..200，100=1.0
    private var selectedAdjustmentIndex = 0
    private var currentTool = TOOL_CROP
    private var adjustmentStartX = 0f
    private var adjustmentStartValue = 0
    @Volatile private var structureGeneration = 0
    private var pendingStructurePreview: Runnable? = null
    private var filterPreviewSource: Bitmap? = null
    private var filterPreviewBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NavigationBarInsets.applyTo(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        val uri = Uri.parse(uriStr)
        sourceUri = uri
        wallpaperMode = intent.getBooleanExtra(EXTRA_WALLPAPER_MODE, false)
        if (wallpaperMode) {
            val dm = resources.displayMetrics
            screenRatioW = dm.widthPixels.toFloat()
            screenRatioH = dm.heightPixels.toFloat()
            binding.tvTitle.text = getString(R.string.edit_set_wallpaper)
            binding.btnSave.text = getString(R.string.edit_set_wallpaper)
            binding.btnCropScreen.visibility = View.VISIBLE
        }

        binding.btnCancel.setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = attemptExit()
        })
        binding.btnSave.setOnClickListener {
            if (wallpaperMode) applyAsWallpaper() else save()
        }
        setupCompareButton()
        binding.ivPreview.setOnTouchListener { _, event -> handleAdjustmentTouch(event) }
        binding.tvStraighten.contentDescription = getString(R.string.straighten_tap_reset)
        binding.tvStraighten.setOnClickListener {
            straightenDeg = 0f
            binding.seekStraighten.progress = 90
            updateStraightenLabel()
            rebuildWorking()
        }
        updateStraightenLabel()

        binding.tabCrop.setOnClickListener { showTool(TOOL_CROP) }
        binding.tabRotate.setOnClickListener { showTool(TOOL_ROTATE) }
        binding.tabFilter.setOnClickListener { showTool(TOOL_FILTER) }
        binding.tabAdjust.setOnClickListener { showTool(TOOL_ADJUST) }

        binding.btnCropOriginal.setOnClickListener { setCrop(0f, 0f); markCropSelected(binding.btnCropOriginal) }
        binding.btnCrop11.setOnClickListener { setCrop(1f, 1f); markCropSelected(binding.btnCrop11) }
        binding.btnCrop43.setOnClickListener { setCrop(4f, 3f); markCropSelected(binding.btnCrop43) }
        binding.btnCrop32.setOnClickListener { setCrop(3f, 2f); markCropSelected(binding.btnCrop32) }
        binding.btnCrop169.setOnClickListener { setCrop(16f, 9f); markCropSelected(binding.btnCrop169) }
        binding.btnCrop45.setOnClickListener { setCrop(4f, 5f); markCropSelected(binding.btnCrop45) }
        binding.btnCrop916.setOnClickListener { setCrop(9f, 16f); markCropSelected(binding.btnCrop916) }
        binding.btnCropScreen.setOnClickListener {
            setCrop(screenRatioW, screenRatioH)
            markCropSelected(binding.btnCropScreen)
        }

        binding.btnRotateLeft.setOnClickListener { rotation = (rotation + 270) % 360; rebuildWorking() }
        binding.btnRotateRight.setOnClickListener { rotation = (rotation + 90) % 360; rebuildWorking() }
        binding.btnFlip.setOnClickListener { flipH = !flipH; rebuildWorking() }
        binding.btnFlipVertical.setOnClickListener { flipV = !flipV; rebuildWorking() }
        binding.seekStraighten.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    straightenDeg = (progress - 90) / 2f
                    updateStraightenLabel()
                    rebuildWorking(80L)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { rebuildWorking() }
        })

        binding.btnFilterNone.setOnClickListener { setFilter(0, binding.btnFilterNone) }
        binding.btnFilterBw.setOnClickListener { setFilter(1, binding.btnFilterBw) }
        binding.btnFilterSepia.setOnClickListener { setFilter(2, binding.btnFilterSepia) }
        binding.btnFilterCool.setOnClickListener { setFilter(3, binding.btnFilterCool) }
        binding.btnFilterWarm.setOnClickListener { setFilter(4, binding.btnFilterWarm) }
        binding.btnFilterVivid.setOnClickListener { setFilter(5, binding.btnFilterVivid) }

        listOf(
            binding.btnAdjustExposure, binding.btnAdjustBrightness,
            binding.btnAdjustContrast, binding.btnAdjustHighlights,
            binding.btnAdjustShadows, binding.btnAdjustTemperature,
            binding.btnAdjustTint, binding.btnAdjustSaturation
        ).forEachIndexed { index, button ->
            button.setOnClickListener { selectAdjustment(index) }
        }
        listOf(
            binding.ivFilterNone, binding.ivFilterBw, binding.ivFilterSepia,
            binding.ivFilterCool, binding.ivFilterWarm, binding.ivFilterVivid
        ).forEachIndexed { index, image ->
            image.setOnClickListener { filterButtons()[index].performClick() }
        }
        updateAdjustmentSelection()

        binding.btnAutoEnhance.setOnClickListener { autoEnhance() }
        binding.btnReset.setOnClickListener { resetAll() }

        if (savedInstanceState == null) {
            markCropSelected(binding.btnCropOriginal)
            setFilter(0, binding.btnFilterNone)
        } else {
            restoreEditState(savedInstanceState)
        }
        showTool(TOOL_CROP)

        executor.execute {
            try {
                originalBitmap = loadBitmap(uri, MAX_SIZE)
                runOnUiThread {
                    if (originalBitmap == null) {
                        Toast.makeText(this, R.string.toast_image_load_failed, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        if (wallpaperMode && savedInstanceState == null) {
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
                    Toast.makeText(this, R.string.toast_image_load_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        structureGeneration++
        pendingStructurePreview?.let { binding.ivPreview.removeCallbacks(it) }
        pendingStructurePreview = null
        executor.shutdown()
        // 不手工 recycle 三张共享位图：后台任务（LUT/自动增强）可能仍在读取，
        // 手工回收与之竞态会 use-after-recycle 崩溃；页面退出后由 GC 回收即可
    }

    // ---------- 状态保存（深色模式切换/进程重建不丢编辑成果） ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(STATE_CROP_W, cropRatioW)
        outState.putFloat(STATE_CROP_H, cropRatioH)
        outState.putInt(STATE_ROTATION, rotation)
        outState.putFloat(STATE_STRAIGHTEN, straightenDeg)
        outState.putBoolean(STATE_FLIP_H, flipH)
        outState.putBoolean(STATE_FLIP_V, flipV)
        outState.putInt(STATE_FILTER, filterIndex)
        outState.putInt(STATE_EXPOSURE, exposure)
        outState.putInt(STATE_BRIGHTNESS, brightness)
        outState.putInt(STATE_CONTRAST, contrast)
        outState.putInt(STATE_HIGHLIGHTS, highlights)
        outState.putInt(STATE_SHADOWS, shadows)
        outState.putInt(STATE_TEMPERATURE, temperature)
        outState.putInt(STATE_TINT, tint)
        outState.putInt(STATE_SATURATION, saturation)
        outState.putInt(STATE_ADJUSTMENT, selectedAdjustmentIndex)
    }

    private fun restoreEditState(state: Bundle) {
        cropRatioW = state.getFloat(STATE_CROP_W, 0f)
        cropRatioH = state.getFloat(STATE_CROP_H, 0f)
        rotation = state.getInt(STATE_ROTATION, 0)
        straightenDeg = state.getFloat(STATE_STRAIGHTEN, 0f)
        flipH = state.getBoolean(STATE_FLIP_H, false)
        flipV = state.getBoolean(STATE_FLIP_V, false)
        filterIndex = state.getInt(STATE_FILTER, 0)
        exposure = state.getInt(STATE_EXPOSURE, 0)
        brightness = state.getInt(STATE_BRIGHTNESS, 0)
        contrast = state.getInt(STATE_CONTRAST, 100)
        highlights = state.getInt(STATE_HIGHLIGHTS, 0)
        shadows = state.getInt(STATE_SHADOWS, 0)
        temperature = state.getInt(STATE_TEMPERATURE, 0)
        tint = state.getInt(STATE_TINT, 0)
        saturation = state.getInt(STATE_SATURATION, 100)
        selectedAdjustmentIndex = state.getInt(STATE_ADJUSTMENT, 0).coerceIn(0, 7)
        binding.seekStraighten.progress = (straightenDeg * 2 + 90).roundToInt()
        updateStraightenLabel()
        markCropSelected(cropButtonForState())
        highlightSelected(filterButtons(), filterButtonForState())
        updateAdjustmentSelection()
    }

    private fun cropButtonForState(): Button = when {
        cropRatioW == 0f && cropRatioH == 0f -> binding.btnCropOriginal
        cropRatioW == 1f && cropRatioH == 1f -> binding.btnCrop11
        cropRatioW == 4f && cropRatioH == 3f -> binding.btnCrop43
        cropRatioW == 3f && cropRatioH == 2f -> binding.btnCrop32
        cropRatioW == 16f && cropRatioH == 9f -> binding.btnCrop169
        cropRatioW == 4f && cropRatioH == 5f -> binding.btnCrop45
        cropRatioW == 9f && cropRatioH == 16f -> binding.btnCrop916
        else -> binding.btnCropScreen
    }

    private fun filterButtonForState(): Button =
        filterButtons().getOrElse(filterIndex) { binding.btnFilterNone }

    private fun filterButtons(): List<Button> = listOf(
        binding.btnFilterNone, binding.btnFilterBw, binding.btnFilterSepia,
        binding.btnFilterCool, binding.btnFilterWarm, binding.btnFilterVivid
    )

    /** 是否有未保存的编辑（结构/滤镜/任一调节不等于默认值） */
    private fun isEdited(): Boolean =
        cropRatioW > 0f || rotation != 0 || straightenDeg != 0f || flipH || flipV ||
            filterIndex != 0 || !isTonalNeutral() ||
            temperature != 0 || tint != 0 || saturation != 100

    /** 退出前确认：有未保存编辑时先问一句，避免误触丢成果 */
    private fun attemptExit() {
        if (!isEdited()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_discard_title)
            .setMessage(R.string.edit_discard_message)
            .setPositiveButton(R.string.edit_discard_ok) { _, _ -> finish() }
            .setNegativeButton(R.string.edit_discard_cancel, null)
            .show()
    }

    // ---------- 加载（含 EXIF 摆正） ----------

    /** 采样解码 + 按 EXIF 方向摆正；maxSide 为最大边上限 */
    private fun loadBitmap(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxSrc = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSrc / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        return applyExifOrientation(uri, decoded)
    }

    private fun applyExifOrientation(uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (out !== bmp) bmp.recycle()
        return out
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
        currentTool = tool
        updateAdjustGestureHint()
    }

    private fun markCropSelected(selected: Button) {
        val buttons = listOf(
            binding.btnCropOriginal, binding.btnCrop11, binding.btnCrop43,
            binding.btnCrop32, binding.btnCrop169, binding.btnCrop45,
            binding.btnCrop916, binding.btnCropScreen
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
        highlightSelected(filterButtons(), button)
        updatePreview()
    }

    private fun adjustmentButtons(): List<Button> = listOf(
        binding.btnAdjustExposure, binding.btnAdjustBrightness,
        binding.btnAdjustContrast, binding.btnAdjustHighlights,
        binding.btnAdjustShadows, binding.btnAdjustTemperature,
        binding.btnAdjustTint, binding.btnAdjustSaturation
    )

    private fun adjustmentLabelRes(index: Int): Int = when (index) {
        0 -> R.string.adjust_exposure
        1 -> R.string.adjust_brightness
        2 -> R.string.adjust_contrast
        3 -> R.string.adjust_highlights
        4 -> R.string.adjust_shadows
        5 -> R.string.adjust_temperature
        6 -> R.string.adjust_tint
        else -> R.string.adjust_saturation
    }

    private fun adjustmentValue(index: Int): Int = when (index) {
        0 -> exposure
        1 -> brightness
        2 -> contrast - 100
        3 -> highlights
        4 -> shadows
        5 -> temperature
        6 -> tint
        else -> saturation - 100
    }

    private fun selectAdjustment(index: Int) {
        selectedAdjustmentIndex = index.coerceIn(0, 7)
        updateAdjustmentSelection()
    }

    private fun updateAdjustmentSelection() {
        val buttons = adjustmentButtons()
        highlightSelected(buttons, buttons[selectedAdjustmentIndex.coerceIn(0, buttons.lastIndex)])
        updateAdjustGestureHint()
    }

    private fun updateAdjustGestureHint() {
        binding.tvAdjustGestureHint.text = getString(
            R.string.adjust_gesture_format,
            getString(adjustmentLabelRes(selectedAdjustmentIndex)),
            formatSigned(adjustmentValue(selectedAdjustmentIndex))
        )
        binding.tvAdjustGestureHint.visibility = if (currentTool == TOOL_ADJUST) View.VISIBLE else View.GONE
    }

    private fun formatSigned(value: Int): String = when {
        value > 0 -> "+$value"
        else -> value.toString()
    }

    private fun handleAdjustmentTouch(event: MotionEvent): Boolean {
        if (currentTool != TOOL_ADJUST) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                adjustmentStartX = event.x
                adjustmentStartValue = adjustmentValue(selectedAdjustmentIndex)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val density = resources.displayMetrics.density
                val delta = ((event.x - adjustmentStartX) / density).roundToInt()
                setAdjustmentValue(selectedAdjustmentIndex, adjustmentStartValue + delta)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            else -> return true
        }
    }

    private fun setAdjustmentValue(index: Int, value: Int) {
        val v = value.coerceIn(-100, 100)
        if (v == adjustmentValue(index)) return
        when (index) {
            0 -> exposure = v
            1 -> brightness = v
            2 -> contrast = v + 100
            3 -> highlights = v
            4 -> shadows = v
            5 -> temperature = v
            6 -> tint = v
            else -> saturation = v + 100
        }
        updateAdjustGestureHint()
        if (index <= 4) refreshTonalPreview() else updatePreview()
    }

    private data class StructureState(
        val cropRatioW: Float,
        val cropRatioH: Float,
        val rotation: Int,
        val straightenDeg: Float,
        val flipH: Boolean,
        val flipV: Boolean
    )

    private fun currentStructureState() = StructureState(
        cropRatioW, cropRatioH, rotation, straightenDeg, flipH, flipV
    )

    /** 后台重建结构预览；拖动拉正时做短暂合并，避免每一帧都旋转整张图。 */
    private fun rebuildWorking(delayMs: Long = 0L) {
        val src = originalBitmap ?: return
        val state = currentStructureState()
        val generation = ++structureGeneration
        tonalGen++
        pendingStructurePreview?.let { binding.ivPreview.removeCallbacks(it) }
        val task = Runnable {
            pendingStructurePreview = null
            executor.execute {
                if (generation != structureGeneration) return@execute
                val built = applyStructure(src, state)
                runOnUiThread {
                    if (generation == structureGeneration && originalBitmap === src && !isFinishing && !isDestroyed) {
                        // 旧工作图可能仍被 LUT 任务读取；交给 GC 回收，避免并发 use-after-recycle。
                        workingBitmap = built
                        tonalBitmap = null
                        filterPreviewSource = null
                        filterPreviewBitmap = null
                        refreshTonalPreview()
                    } else if (built !== src && !built.isRecycled) {
                        built.recycle()
                    }
                }
            }
        }
        pendingStructurePreview = task
        if (delayMs > 0L) binding.ivPreview.postDelayed(task, delayMs) else task.run()
    }

    /**
     * 对任意位图应用当前结构编辑（中心裁剪 → 旋转/拉正/镜像）。
     * 不回收输入位图；无实际变换时返回输入本身。
     */
    private fun applyStructure(src: Bitmap, state: StructureState = currentStructureState()): Bitmap {
        var bmp = src
        var owned = false

        if (state.cropRatioW > 0 && state.cropRatioH > 0) {
            val target = state.cropRatioW / state.cropRatioH
            val srcRatio = bmp.width.toFloat() / bmp.height
            val w: Int
            val h: Int
            if (srcRatio > target) {
                w = (bmp.height * target).toInt(); h = bmp.height
            } else {
                w = bmp.width; h = (bmp.width / target).toInt()
            }
            if (w in 1 until bmp.width || h in 1 until bmp.height) {
                val cropped = Bitmap.createBitmap(
                    bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h
                )
                if (owned) bmp.recycle()
                bmp = cropped
                owned = true
            }
        }

        // 90° 旋转后的内容尺寸（拉正的内接裁剪按此比例计算）
        val contentW = if (state.rotation % 180 == 0) bmp.width else bmp.height
        val contentH = if (state.rotation % 180 == 0) bmp.height else bmp.width

        val totalRotation = state.rotation + state.straightenDeg
        if (totalRotation != 0f || state.flipH || state.flipV) {
            val matrix = Matrix()
            if (totalRotation != 0f) matrix.postRotate(totalRotation)
            if (state.flipH || state.flipV) {
                matrix.postScale(if (state.flipH) -1f else 1f, if (state.flipV) -1f else 1f)
            }
            val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (owned) bmp.recycle()
            bmp = transformed
            owned = true

            if (state.straightenDeg != 0f) {
                // 旋转后中心裁剪到与内容同比例的最大内接矩形，避免黑角
                val a = contentW.toFloat() / contentH
                val theta = Math.toRadians(abs(state.straightenDeg).toDouble())
                val cosT = cos(theta).toFloat()
                val sinT = sin(theta).toFloat()
                val halfW = contentW / 2f
                val halfH = contentH / 2f
                val x = min(halfW / (cosT + sinT / a), halfH / (sinT + cosT / a))
                var cw = (2 * x).toInt()
                var ch = (2 * x / a).toInt()
                cw = min(cw, bmp.width)
                ch = min(ch, bmp.height)
                if (cw > 0 && ch > 0 && (cw < bmp.width || ch < bmp.height)) {
                    val cropped = Bitmap.createBitmap(
                        bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch
                    )
                    bmp.recycle()
                    bmp = cropped
                }
            }
        }
        return bmp
    }

    // ---------- 色调 LUT（曝光/亮度/对比度/高光/阴影） ----------

    private fun isTonalNeutral(): Boolean =
        exposure == 0 && brightness == 0 && contrast == 100 && highlights == 0 && shadows == 0

    private fun buildLut(): IntArray {
        val lut = IntArray(256)
        val expFactor = 2.0.pow(exposure * 0.015).toFloat()
        val s = shadows / 100f
        val h = highlights / 100f
        val bOff = brightness / 100f * 0.30f
        val c = contrast / 100f
        for (i in 0..255) {
            var x = i / 255f * expFactor
            val xc = x.coerceIn(0f, 1f)
            x += s * 0.28f * (1f - xc).pow(3)
            x += h * 0.28f * xc.pow(3)
            x += bOff
            x = (x - 0.5f) * c + 0.5f
            lut[i] = (x.coerceIn(0f, 1f) * 255f).roundToInt()
        }
        return lut
    }

    private fun applyLut(src: Bitmap): Bitmap {
        val lut = buildLut()
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            pixels[idx] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 色调参数变化后重算预览（后台执行，丢弃过期结果） */
    private fun refreshTonalPreview() {
        val src = workingBitmap ?: return
        if (isTonalNeutral()) {
            tonalBitmap = null
            updatePreview()
            return
        }
        val gen = ++tonalGen
        executor.execute {
            // 排队期间参数又变了就直接跳过，不再整帧算完才丢弃（防拖动滑杆排队滞后）
            if (gen != tonalGen) return@execute
            val result = applyLut(src)
            runOnUiThread {
                if (gen == tonalGen && workingBitmap === src) {
                    tonalBitmap = result
                    updatePreview()
                }
                // 过期结果不手工回收，交给 GC（与共享位图的竞态考量一致）
            }
        }
    }

    // ---------- 色彩矩阵（滤镜/饱和度/色温/色调） ----------

    private fun buildColorMatrix(index: Int = filterIndex): ColorMatrix {
        val sat = ColorMatrix().apply { setSaturation(saturation / 100f) }
        // 色温/色调：通道缩放（暖=加红减蓝，品红=减绿）
        val tk = temperature / 100f
        val nk = tint / 100f
        val rS = 1f + 0.18f * tk + 0.05f * nk
        val gS = 1f + 0.04f * tk - 0.16f * nk
        val bS = 1f - 0.22f * tk + 0.05f * nk
        val tempTint = ColorMatrix(
            floatArrayOf(
                rS, 0f, 0f, 0f, 0f,
                0f, gS, 0f, 0f, 0f,
                0f, 0f, bS, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val adjust = ColorMatrix()
        adjust.postConcat(tempTint)
        adjust.postConcat(sat)

        val filter = filterMatrix(index)
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
        val bmp = tonalBitmap ?: workingBitmap ?: return
        binding.ivPreview.setImageBitmap(bmp)
        binding.ivPreview.colorFilter = ColorMatrixColorFilter(buildColorMatrix())
        updateFilterPreviews()
    }

    /** 以当前照片为底图，给每种滤镜都显示一张轻量、实时着色的预览缩略图。 */
    private fun updateFilterPreviews() {
        val src = tonalBitmap ?: workingBitmap ?: return
        val thumbnail = if (filterPreviewSource === src) {
            filterPreviewBitmap ?: return
        } else {
            val scale = minOf(1f, 96f / maxOf(src.width, src.height))
            val width = (src.width * scale).roundToInt().coerceAtLeast(1)
            val height = (src.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, width, height, true).also {
                filterPreviewSource = src
                filterPreviewBitmap = it
            }
        }
        val previewViews = listOf(
            binding.ivFilterNone, binding.ivFilterBw, binding.ivFilterSepia,
            binding.ivFilterCool, binding.ivFilterWarm, binding.ivFilterVivid
        )
        previewViews.forEachIndexed { index, image ->
            image.setImageBitmap(thumbnail)
            image.colorFilter = ColorMatrixColorFilter(buildColorMatrix(index))
        }
    }

    /** 按住"对比原图"：按下显示未调色的工作图，松开恢复编辑效果 */
    private fun setupCompareButton() {
        binding.btnCompare.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    workingBitmap?.let { binding.ivPreview.setImageBitmap(it) }
                    binding.ivPreview.colorFilter = null
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    updatePreview()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- 自动增强 / 重置 ----------

    /** 基于直方图统计的一键增强：拉开黑白场、按平均亮度修曝光/阴影/高光、补饱和 */
    private fun autoEnhance() {
        val src = workingBitmap ?: return
        executor.execute {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val hist = IntArray(256)
            var satSum = 0.0
            var count = 0
            val step = maxOf(1, minOf(w, h) / 96)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = pixels[y * w + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val lum = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                    hist[lum]++
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    satSum += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
                    count++
                    x += step
                }
                y += step
            }
            if (count == 0) return@execute
            fun percentile(pct: Double): Int {
                val target = count * pct
                var acc = 0
                for (i in 0..255) {
                    acc += hist[i]
                    if (acc >= target) return i
                }
                return 255
            }
            var lumSum = 0L
            for (i in 0..255) lumSum += i.toLong() * hist[i]
            val mean = lumSum.toDouble() / count / 255.0
            val black = percentile(0.02) / 255f
            val white = percentile(0.98) / 255f
            val p10 = percentile(0.10) / 255f
            val p90 = percentile(0.90) / 255f
            val meanSat = satSum / count

            val range = (white - black).coerceAtLeast(0.05f)
            val newContrast = (100f / range * 100f).toInt().coerceIn(100, 145)
            val newBrightness = ((0.5f - (black + white) / 2f) * 60f).toInt().coerceIn(-25, 25)
            val newExposure = when {
                mean < 0.32 -> 20
                mean > 0.70 -> -15
                else -> ((0.45 - mean) * 40).toInt().coerceIn(-20, 20)
            }
            val newShadows = when {
                p10 < 0.06f -> 25
                p10 < 0.10f -> 12
                else -> 0
            }
            val newHighlights = when {
                p90 > 0.97f -> -25
                p90 > 0.93f -> -12
                else -> 0
            }
            val newSaturation = when {
                meanSat < 0.18 -> 120
                meanSat < 0.28 -> 110
                else -> 100
            }
            runOnUiThread {
                exposure = newExposure
                brightness = newBrightness
                contrast = newContrast
                highlights = newHighlights
                shadows = newShadows
                saturation = newSaturation
                updateAdjustmentSelection()
                refreshTonalPreview()
                updatePreview()
                Toast.makeText(this, R.string.edit_auto_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetAll() {
        cropRatioW = 0f
        cropRatioH = 0f
        rotation = 0
        straightenDeg = 0f
        flipH = false
        flipV = false
        filterIndex = 0
        exposure = 0
        brightness = 0
        contrast = 100
        highlights = 0
        shadows = 0
        temperature = 0
        tint = 0
        saturation = 100
        selectedAdjustmentIndex = 0
        binding.seekStraighten.progress = 90
        updateStraightenLabel()
        updateAdjustmentSelection()
        markCropSelected(binding.btnCropOriginal)
        highlightSelected(filterButtons(), binding.btnFilterNone)
        rebuildWorking()
        Toast.makeText(this, R.string.edit_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun updateStraightenLabel() {
        val angle = if (kotlin.math.abs(straightenDeg) < 0.05f) "0" else String.format(Locale.US, "%+.1f", straightenDeg)
        binding.tvStraighten.text = getString(R.string.straighten_value, angle)
    }

    // ---------- 保存 / 壁纸 ----------

    /** 对已完成结构编辑的位图应用 LUT + 色彩矩阵，返回新位图（调用方负责回收） */
    private fun renderColorBitmap(structured: Bitmap): Bitmap {
        val base = if (isTonalNeutral()) structured else applyLut(structured)
        val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(buildColorMatrix())
        }
        canvas.drawBitmap(base, 0f, 0f, paint)
        if (base !== structured) base.recycle()
        return result
    }

    private fun save() {
        if (workingBitmap == null) return
        binding.btnSave.isEnabled = false
        executor.execute {
            try {
                // 保存走全分辨率管线：重新按上限 4096 解码原图，套用同一套编辑状态
                val uri = sourceUri ?: throw IllegalStateException("无源图")
                val full = loadBitmap(uri, SAVE_MAX_SIZE)
                val base = full ?: workingBitmap ?: throw IllegalStateException("渲染失败")
                val structured = applyStructure(base)
                val result = renderColorBitmap(structured)
                if (structured !== base) structured.recycle()
                if (full != null) full.recycle()

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
                val outUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert 失败")
                contentResolver.openOutputStream(outUri)?.use { os ->
                    if (!result.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
                        throw IllegalStateException("JPEG 压缩失败")
                    }
                } ?: throw IllegalStateException("无法打开输出流")
                result.recycle()
                CaptureStore.add(this, outUri, "image/jpeg", name)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.edit_saved), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存失败", e)
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyAsWallpaper() {
        if (workingBitmap == null) return
        binding.btnSave.isEnabled = false
        executor.execute {
            try {
                val base = workingBitmap ?: throw IllegalStateException("渲染失败")
                val result = renderColorBitmap(base)
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
