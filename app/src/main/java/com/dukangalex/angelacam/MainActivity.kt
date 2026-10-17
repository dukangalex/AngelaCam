package com.dukangalex.angelacam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.dukangalex.angelacam.databinding.ActivityMainBinding
import com.dukangalex.angelacam.databinding.DialogSettingsBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var torchOn = false
    private var photoMode = true
    private var timerSeconds = 0 // 0=关, 3, 10
    private var gridMode = GridOverlayView.MODE_OFF
    private var countingDown = false
    // v1.1.0 新增调节参数
    private var aspectRatio = ASPECT_4_3 // 0=4:3, 1=16:9, 2=1:1
    private var mirrorSelfie = true
    private var shutterSound = true
    private var tapCapture = false

    private lateinit var prefs: SharedPreferences
    private var mediaActionSound: MediaActionSound? = null

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private val uiHandler = Handler(Looper.getMainLooper())
    private var recordStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val s = ((SystemClock.elapsedRealtime() - recordStartMs) / 1000).toInt()
            binding.tvTimer.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            uiHandler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) {
                startCamera()
                updateThumbnail()
            } else {
                Toast.makeText(this, "需要相机权限才能使用", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    /** 麦克风权限：只在用户第一次按下录像键时申请（最小权限原则） */
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "已拒绝麦克风权限，将静音录制", Toast.LENGTH_SHORT).show()
            }
            startRecording(withAudio = granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mirrorSelfie = prefs.getBoolean(KEY_MIRROR, true)
        shutterSound = prefs.getBoolean(KEY_SHUTTER_SOUND, true)
        tapCapture = prefs.getBoolean(KEY_TAP_CAPTURE, false)
        mediaActionSound = MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
        }

        setupGestures()
        binding.btnShutter.setOnClickListener { onShutter() }
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
        binding.btnThumbnail.setOnClickListener { openGallery() }
        binding.btnTimer.setOnClickListener { cycleTimer() }
        binding.btnGrid.setOnClickListener { cycleGrid() }
        binding.btnAspect.setOnClickListener { cycleAspect() }
        binding.btnSettings.setOnClickListener { showSettings() }
        updateFlashIcon()
        updateModeUi()
        updateTimerUi()
        updateAspectUi()
        updateGridUi()

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) updateThumbnail()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(timerRunnable)
        uiHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        mediaActionSound?.release()
        mediaActionSound = null
    }

    /** 音量键快门 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 权限 ----------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        // 最小权限：启动只申请相机（+ Android 9 及以下写存储）。
        // 麦克风等到用户真正录像时再按需申请。
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCamera()
            updateThumbnail()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- 相机启动与绑定 ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .setTargetAspectRatio(
                if (aspectRatio == ASPECT_16_9) AspectRatio.RATIO_16_9
                else AspectRatio.RATIO_4_3
            )
            .build()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            if (torchOn && lensFacing == CameraSelector.LENS_FACING_BACK) {
                camera?.cameraControl?.enableTorch(true)
            }
            setupExposureBar()
        } catch (e: Exception) {
            Log.e(TAG, "绑定相机用例失败", e)
            Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 快门 ----------

    private fun onShutter() {
        if (countingDown) return
        if (photoMode) {
            if (timerSeconds > 0) startCountdown() else takePhoto()
        } else {
            onVideoShutter()
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "AngelaCam_${timestamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AngelaCam")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        binding.btnShutter.isEnabled = false
        if (shutterSound) {
            try {
                mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                Log.w(TAG, "快门音播放失败", e)
            }
        }
        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                results.savedUri?.let { uri ->
                    // v1.1.0: 1:1 画幅中心裁剪 / 前置自拍镜像
                    postProcessPhoto(uri)
                    CaptureStore.add(this@MainActivity, uri, "image/jpeg", name)
                }
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@MainActivity, "已保存到相册", Toast.LENGTH_SHORT).show()
                    updateThumbnail()
                }
            }

            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@MainActivity, "拍照失败：${exc.message}", Toast.LENGTH_SHORT).show()
                }
                Log.e(TAG, "拍照失败", exc)
            }
        })
    }

    /**
     * 拍照后期处理（后台线程调用）：
     * - 画幅比 1:1：中心裁剪正方形
     * - 前置摄像头 + 自拍镜像开：水平翻转（与预览一致）
     */
    private fun postProcessPhoto(uri: Uri) {
        val needCrop = aspectRatio == ASPECT_1_1
        val needMirror = mirrorSelfie && lensFacing == CameraSelector.LENS_FACING_FRONT
        if (!needCrop && !needMirror) return
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val src = BitmapFactory.decodeStream(input)
            input.close()
            if (src == null) return
            var out = src
            if (needCrop) {
                val side = minOf(out.width, out.height)
                val x = (out.width - side) / 2
                val y = (out.height - side) / 2
                out = Bitmap.createBitmap(out, x, y, side, side)
                if (out !== src) src.recycle()
            }
            if (needMirror) {
                val matrix = Matrix().apply { preScale(-1f, 1f) }
                val flipped = Bitmap.createBitmap(out, 0, 0, out.width, out.height, matrix, true)
                if (flipped !== out) out.recycle()
                out = flipped
            }
            contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                os.write(bos.toByteArray())
            }
            out.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "照片后期处理失败", e)
        }
    }

    // ---------- 倒计时拍摄 ----------

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        updateTimerUi()
    }

    private fun updateTimerUi() {
        binding.btnTimer.text = when (timerSeconds) {
            3 -> getString(R.string.timer_3s)
            10 -> getString(R.string.timer_10s)
            else -> getString(R.string.timer_off)
        }
        // 倒计时只在拍照模式有效
        binding.btnTimer.visibility = if (photoMode) View.VISIBLE else View.INVISIBLE
    }

    private fun startCountdown() {
        countingDown = true
        binding.tvCountdown.visibility = View.VISIBLE
        var remaining = timerSeconds
        val r = object : Runnable {
            override fun run() {
                if (!countingDown) return
                if (remaining > 0) {
                    binding.tvCountdown.text = remaining.toString()
                    remaining--
                    uiHandler.postDelayed(this, 1000)
                } else {
                    binding.tvCountdown.visibility = View.GONE
                    countingDown = false
                    takePhoto()
                }
            }
        }
        uiHandler.post(r)
    }

    // ---------- 录像 ----------

    private fun onVideoShutter() {
        if (recording != null) {
            recording?.stop()
            return
        }
        if (hasAudioPermission()) {
            startRecording(withAudio = true)
        } else {
            // 按需申请：拒绝也不阻断，静音录制
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording(withAudio: Boolean) {
        val vc = videoCapture ?: return
        if (recording != null) return
        val name = "AngelaCam_${timestamp()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/AngelaCam")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val pending = vc.output.prepareRecording(this, output).apply {
            if (withAudio) withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecordStart()
                is VideoRecordEvent.Finalize -> onRecordFinalize(event, name)
                else -> { /* 忽略其他事件 */ }
            }
        }
    }

    private fun onRecordStart() {
        binding.recordingIndicator.visibility = View.VISIBLE
        binding.tvTimer.text = "00:00"
        recordStartMs = SystemClock.elapsedRealtime()
        uiHandler.post(timerRunnable)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Toast.makeText(this, "开始录像", Toast.LENGTH_SHORT).show()
    }

    private fun onRecordFinalize(event: VideoRecordEvent.Finalize, name: String) {
        recording = null
        binding.recordingIndicator.visibility = View.GONE
        uiHandler.removeCallbacks(timerRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!event.hasError()) {
            event.outputResults.outputUri.let { uri ->
                CaptureStore.add(this, uri, "video/mp4", name)
            }
            Toast.makeText(this, "视频已保存到相册", Toast.LENGTH_SHORT).show()
            updateThumbnail()
        } else {
            Toast.makeText(this, "录像失败：${event.error}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "录像失败 cause=${event.cause}", event.cause)
        }
    }

    // ---------- 模式 / 闪光灯 / 摄像头 / 网格线 ----------

    private fun toggleMode() {
        if (recording != null) {
            Toast.makeText(this, "录像中，无法切换模式", Toast.LENGTH_SHORT).show()
            return
        }
        photoMode = !photoMode
        if (photoMode && torchOn) {
            torchOn = false
            camera?.cameraControl?.enableTorch(false)
        }
        updateModeUi()
        updateFlashIcon()
        updateTimerUi()
    }

    private fun updateModeUi() {
        binding.btnMode.text =
            if (photoMode) getString(R.string.mode_video) else getString(R.string.mode_photo)
    }

    private fun cycleFlash() {
        if (photoMode) {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                else -> ImageCapture.FLASH_MODE_AUTO
            }
            imageCapture?.flashMode = flashMode
        } else {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        }
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        val res = if (photoMode) {
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
                else -> R.drawable.ic_flash_auto
            }
        } else {
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        }
        binding.btnFlash.setImageResource(res)
    }

    private fun switchCamera() {
        if (recording != null) {
            Toast.makeText(this, "录像中，无法切换摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        torchOn = false
        bindUseCases()
    }

    /** 参考线三态循环：关闭 → 九宫格 → 十字 */
    private fun cycleGrid() {
        gridMode = when (gridMode) {
            GridOverlayView.MODE_OFF -> GridOverlayView.MODE_THIRDS
            GridOverlayView.MODE_THIRDS -> GridOverlayView.MODE_CROSS
            else -> GridOverlayView.MODE_OFF
        }
        updateGridUi()
    }

    private fun updateGridUi() {
        binding.gridOverlay.mode = gridMode
        binding.btnGrid.setImageResource(
            if (gridMode == GridOverlayView.MODE_OFF) R.drawable.ic_grid_off
            else R.drawable.ic_grid
        )
    }

    // ---------- 画幅比 ----------

    /** 画幅比循环：4:3 → 16:9 → 1:1（1:1 拍摄后中心裁剪） */
    private fun cycleAspect() {
        aspectRatio = when (aspectRatio) {
            ASPECT_4_3 -> ASPECT_16_9
            ASPECT_16_9 -> ASPECT_1_1
            else -> ASPECT_4_3
        }
        updateAspectUi()
        bindUseCases() // 16:9 需要重建 ImageCapture
    }

    private fun updateAspectUi() {
        binding.btnAspect.text = when (aspectRatio) {
            ASPECT_16_9 -> getString(R.string.aspect_16_9)
            ASPECT_1_1 -> getString(R.string.aspect_1_1)
            else -> getString(R.string.aspect_4_3)
        }
    }

    // ---------- 设置弹窗 ----------

    private fun showSettings() {
        val view = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        view.switchMirror.isChecked = mirrorSelfie
        view.switchShutterSound.isChecked = shutterSound
        view.switchTapCapture.isChecked = tapCapture
        view.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                mirrorSelfie = view.switchMirror.isChecked
                shutterSound = view.switchShutterSound.isChecked
                tapCapture = view.switchTapCapture.isChecked
                prefs.edit()
                    .putBoolean(KEY_MIRROR, mirrorSelfie)
                    .putBoolean(KEY_SHUTTER_SOUND, shutterSound)
                    .putBoolean(KEY_TAP_CAPTURE, tapCapture)
                    .apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 曝光补偿 ----------

    private fun setupExposureBar() {
        val cam = camera ?: return
        val expState = cam.cameraInfo.exposureState
        if (!expState.isExposureCompensationSupported) {
            binding.exposureBar.visibility = View.GONE
            return
        }
        val range = expState.exposureCompensationRange
        val step = expState.exposureCompensationStep.toDouble()
        binding.exposureBar.visibility = View.VISIBLE
        binding.seekExposure.max = range.upper - range.lower
        binding.seekExposure.progress = 0 - range.lower
        updateEvText(0, step)
        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val index = range.lower + progress
                cam.cameraControl.setExposureCompensationIndex(index)
                updateEvText(index, step)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateEvText(index: Int, step: Double) {
        binding.tvEv.text = String.format(Locale.US, "%+.1f", index * step)
    }

    // ---------- 手势：缩放 / 对焦 ----------

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val zoomState = cam.cameraInfo.zoomState.value ?: return true
                    val newRatio = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(newRatio)
                    return true
                }
            }
        )
        tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (tapCapture && photoMode && recording == null) {
                        onShutter()
                    } else {
                        focusAt(e.x, e.y)
                    }
                    return true
                }
            }
        )
        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) {
                tapDetector.onTouchEvent(event)
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val factory = binding.previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "对焦失败", e)
        }
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        val d = resources.getDimensionPixelSize(R.dimen.focus_ring_size)
        ring.x = x - d / 2f
        ring.y = y - d / 2f
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.animate().cancel()
        ring.animate()
            .alpha(0f)
            .setStartDelay(600)
            .setDuration(300)
            .withEndAction { ring.visibility = View.INVISIBLE }
            .start()
    }

    // ---------- 缩略图 / 相册 ----------

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    /** 缩略图只显示本应用自己的拍摄记录（无需存储读取权限） */
    private fun updateThumbnail() {
        if (!hasCameraPermission()) return
        cameraExecutor.execute {
            try {
                val latest = CaptureStore.list(this).firstOrNull()
                if (latest == null) {
                    runOnUiThread { binding.btnThumbnail.setImageDrawable(null) }
                    return@execute
                }
                val uri: Uri = Uri.parse(latest.uri)
                val thumb: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        contentResolver,
                        android.content.ContentUris.parseId(uri),
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
                runOnUiThread { binding.btnThumbnail.setImageBitmap(thumb) }
            } catch (e: Exception) {
                Log.w(TAG, "加载缩略图失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "AngelaCam"

        private const val PREFS_NAME = "angelacam_settings"
        private const val KEY_MIRROR = "mirror_selfie"
        private const val KEY_SHUTTER_SOUND = "shutter_sound"
        private const val KEY_TAP_CAPTURE = "tap_capture"

        private const val ASPECT_4_3 = 0
        private const val ASPECT_16_9 = 1
        private const val ASPECT_1_1 = 2
    }
}
