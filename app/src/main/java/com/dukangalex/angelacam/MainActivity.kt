package com.dukangalex.angelacam

import android.Manifest
import android.app.Activity
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
import android.provider.Settings
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
import android.widget.TextView
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
import com.dukangalex.angelacam.databinding.DialogCameraControlsBinding
import com.dukangalex.angelacam.databinding.DialogSettingsBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var quickControlsBinding: DialogCameraControlsBinding? = null
    private lateinit var cameraExecutor: ExecutorService

    /** 非相机后台活（缩略图、拍照后期、更新检查）专用，避免挤占拍照回调线程 */
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraStarted = false

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Android 10 and earlier may resolve these as third-party camera handlers.
    // Android 11+ restricts implicit capture resolution to preinstalled system cameras;
    // callers may still explicitly target this activity when they choose AngelaCam.
    private var externalCaptureAction: String? = null
    private var externalOutputUri: Uri? = null
    private var externalVideoDurationLimitMillis = 0L
    private var externalVideoSizeLimitBytes = 0L

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
    private var autoUpdateCheck = false
    @Volatile private var availableUpdateInfo: UpdateChecker.UpdateInfo? = null
    private var installedVersionView: TextView? = null
    private var updateEntryView: TextView? = null
    private var updateStatusContainer: View? = null

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
    private val externalVideoLimitRunnable = Runnable {
        if (externalCaptureAction == MediaStore.ACTION_VIDEO_CAPTURE) recording?.stop()
    }

    private val qrScannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasCameraPermission()) {
                // Rebind the viewfinder after the scanner activity releases the shared camera.
                if (cameraProvider != null) bindUseCases() else startCamera()
            }
        }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) {
                startCamera()
                if (externalCaptureAction == null) updateThumbnail()
            } else if (externalCaptureAction != null) {
                Toast.makeText(this, R.string.perm_camera_message, Toast.LENGTH_SHORT).show()
                cancelExternalCapture()
            } else {
                showCameraPermissionDenied()
            }
        }

    /**
     * 相机权限被拒：不直接退出（勾过「不再询问」时每次打开都像闪退），
     * 给出去系统设置的补救路径；用户从设置回来后 onResume 会自动启动相机。
     */
    private fun showCameraPermissionDenied() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_camera_title)
            .setMessage(R.string.perm_camera_message)
            .setPositiveButton(R.string.perm_go_settings) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "打开应用设置失败", e)
                    finish()
                }
            }
            .setNegativeButton(R.string.perm_exit) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /** 麦克风权限：只在用户第一次按下录像键时申请（最小权限原则） */
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.toast_mic_denied, Toast.LENGTH_SHORT).show()
            }
            startRecording(withAudio = granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NavigationBarInsets.applyTo(binding.bottomBar)
        cameraExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor = Executors.newSingleThreadExecutor()

        externalCaptureAction = intent.action?.takeIf {
            it == MediaStore.ACTION_IMAGE_CAPTURE || it == MediaStore.ACTION_VIDEO_CAPTURE
        }
        if (intent.action == "android.media.action.IMAGE_CAPTURE_SECURE") {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        externalOutputUri = if (externalCaptureAction != null) readCaptureOutputUri() else null
        if (externalCaptureAction != null && intent.hasExtra(MediaStore.EXTRA_OUTPUT) && externalOutputUri == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        if (externalCaptureAction == MediaStore.ACTION_VIDEO_CAPTURE) {
            photoMode = false
            externalVideoDurationLimitMillis = runCatching {
                intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0).coerceAtLeast(0) * 1000L
            }.getOrDefault(0L)
            externalVideoSizeLimitBytes = runCatching {
                intent.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0L).coerceAtLeast(0L)
            }.getOrDefault(0L)
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mirrorSelfie = prefs.getBoolean(KEY_MIRROR, true)
        shutterSound = prefs.getBoolean(KEY_SHUTTER_SOUND, true)
        tapCapture = prefs.getBoolean(KEY_TAP_CAPTURE, false)
        autoUpdateCheck = prefs.getBoolean(KEY_AUTO_UPDATE, false)
        availableUpdateInfo = loadCachedAvailableUpdate()
        mediaActionSound = MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
        }

        setupGestures()
        binding.btnShutter.setOnClickListener { onShutter() }
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
        binding.btnThumbnail.setOnClickListener { openGallery() }
        binding.btnSettings.setOnClickListener { showQuickControls() }
        updateFlashIcon()
        updateModeUi()
        updateTimerUi()
        updateAspectUi()
        updateGridUi()
        if (externalCaptureAction != null) {
            binding.btnMode.visibility = View.GONE
            binding.btnThumbnail.visibility = View.GONE
            binding.btnSettings.visibility = View.GONE
        }

        requestPermissionsIfNeeded()
        if (externalCaptureAction == null) maybeAutoCheckUpdate()
    }

    override fun onResume() {
        super.onResume()
        availableUpdateInfo = loadCachedAvailableUpdate()
        refreshUpdateEntry()
        // 从系统设置补开权限回来时自动启动相机，不用用户重启应用
        if (!cameraStarted && hasCameraPermission()) startCamera()
        if (hasCameraPermission() && externalCaptureAction == null) updateThumbnail()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(timerRunnable)
        uiHandler.removeCallbacksAndMessages(null)
        quickControlsBinding = null
        cameraExecutor.shutdown()
        backgroundExecutor.shutdown()
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
        cameraStarted = true
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
            Toast.makeText(this, R.string.toast_camera_start_failed, Toast.LENGTH_SHORT).show()
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
        val externalImageCapture = externalCaptureAction == MediaStore.ACTION_IMAGE_CAPTURE
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
                val uri = results.savedUri
                if (uri == null) {
                    runOnUiThread {
                        binding.btnShutter.isEnabled = true
                        Toast.makeText(this@MainActivity, R.string.toast_photo_failed, Toast.LENGTH_SHORT).show()
                        if (externalImageCapture) cancelExternalCapture()
                    }
                    return
                }
                backgroundExecutor.execute {
                    // Keep the existing CameraX/MediaStore path for AngelaCam's own album.
                    postProcessPhoto(uri)
                    CaptureStore.add(this@MainActivity, uri, "image/jpeg", name)
                    if (externalImageCapture) completeExternalPhoto(uri)
                }
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    if (!externalImageCapture) {
                        Toast.makeText(this@MainActivity, R.string.toast_photo_saved, Toast.LENGTH_SHORT).show()
                        updateThumbnail()
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@MainActivity, R.string.toast_photo_failed, Toast.LENGTH_SHORT).show()
                    if (externalImageCapture) cancelExternalCapture()
                }
                Log.e(TAG, "拍照失败", exc)
            }
        })
    }

    /** Accept only the content URI explicitly supplied by the caller. */
    private fun readCaptureOutputUri(): Uri? {
        val candidate = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法读取拍摄输出 URI", e)
            null
        }
        return candidate?.takeIf { it.scheme == "content" }
    }

    private fun completeExternalPhoto(savedUri: Uri) {
        if (externalCaptureAction != MediaStore.ACTION_IMAGE_CAPTURE || isFinishing || isDestroyed) return
        try {
            val resultUri = externalOutputUri ?: savedUri
            if (externalOutputUri != null && externalOutputUri != savedUri) {
                copyContentUri(savedUri, resultUri)
            }
            val result = Intent().apply {
                setDataAndType(resultUri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (externalOutputUri == null) {
                    createResultThumbnail(savedUri)?.let { putExtra("data", it) }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "向调用方返回照片失败", e)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, R.string.toast_photo_failed, Toast.LENGTH_SHORT).show()
                    cancelExternalCapture()
                }
            }
        }
    }

    private fun createResultThumbnail(uri: Uri) = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "生成拍摄结果缩略图失败", e)
        null
    }

    private fun copyContentUri(source: Uri, target: Uri) {
        require(source.scheme == "content" && target.scheme == "content")
        if (source == target) return
        val input = contentResolver.openInputStream(source)
            ?: throw IllegalStateException("无法读取拍摄结果")
        val output = contentResolver.openOutputStream(target, "w")
            ?: throw IllegalStateException("无法写入调用方提供的 URI")
        input.use { inputStream -> output.use { outputStream -> inputStream.copyTo(outputStream) } }
    }

    private fun completeExternalVideo(savedUri: Uri) {
        if (externalCaptureAction != MediaStore.ACTION_VIDEO_CAPTURE || isFinishing || isDestroyed) return
        val outputUri = externalOutputUri
        if (outputUri == null) {
            deliverCaptureResult(savedUri, "video/mp4")
        } else {
            backgroundExecutor.execute {
                try {
                    copyContentUri(savedUri, outputUri)
                    deliverCaptureResult(outputUri, "video/mp4")
                } catch (e: Exception) {
                    Log.e(TAG, "向调用方返回视频失败", e)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this, R.string.toast_record_failed, Toast.LENGTH_SHORT).show()
                            cancelExternalCapture()
                        }
                    }
                }
            }
        }
    }

    private fun deliverCaptureResult(uri: Uri, mimeType: String) {
        val result = Intent().apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                setResult(Activity.RESULT_OK, result)
                finish()
            }
        }
    }

    private fun cancelExternalCapture() {
        if (externalCaptureAction == null || isFinishing || isDestroyed) return
        setResult(Activity.RESULT_CANCELED)
        finish()
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
            // 采样解码，上限 4096（与编辑器保存管线一致）：原先全分辨率解码，
            // 高像素机型单张位图可达上百 MB，有 OOM 后静默跳过裁剪/镜像的风险
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxSrc = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSrc / sample > 4096) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return
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
        val controls = quickControlsBinding ?: return
        controls.btnTimer.isSelected = timerSeconds > 0
        controls.btnTimer.text = when (timerSeconds) {
            3 -> getString(R.string.timer_3s)
            10 -> getString(R.string.timer_10s)
            else -> getString(R.string.timer_off)
        }
        // 倒计时只在拍照模式有效；录像模式下其余快捷控件自动铺满面板。
        controls.timerControl.visibility = if (photoMode) View.VISIBLE else View.GONE
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
                is VideoRecordEvent.Status -> {
                    if (externalCaptureAction == MediaStore.ACTION_VIDEO_CAPTURE &&
                        externalVideoSizeLimitBytes > 0L &&
                        event.recordingStats.numBytesRecorded >= externalVideoSizeLimitBytes
                    ) {
                        recording?.stop()
                    }
                }
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
        if (externalCaptureAction == MediaStore.ACTION_VIDEO_CAPTURE && externalVideoDurationLimitMillis > 0L) {
            uiHandler.removeCallbacks(externalVideoLimitRunnable)
            uiHandler.postDelayed(externalVideoLimitRunnable, externalVideoDurationLimitMillis)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Toast.makeText(this, R.string.toast_record_start, Toast.LENGTH_SHORT).show()
    }

    private fun onRecordFinalize(event: VideoRecordEvent.Finalize, name: String) {
        recording = null
        binding.recordingIndicator.visibility = View.GONE
        uiHandler.removeCallbacks(timerRunnable)
        uiHandler.removeCallbacks(externalVideoLimitRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!event.hasError()) {
            val uri = event.outputResults.outputUri
            CaptureStore.add(this, uri, "video/mp4", name)
            if (externalCaptureAction == MediaStore.ACTION_VIDEO_CAPTURE) {
                completeExternalVideo(uri)
            } else {
                Toast.makeText(this, R.string.toast_video_saved, Toast.LENGTH_SHORT).show()
                updateThumbnail()
            }
        } else {
            Toast.makeText(this, R.string.toast_record_failed, Toast.LENGTH_SHORT).show()
            Log.e(TAG, "录像失败 cause=${event.cause}", event.cause)
            if (externalCaptureAction == MediaStore.ACTION_VIDEO_CAPTURE) cancelExternalCapture()
        }
    }

    // ---------- 模式 / 闪光灯 / 摄像头 / 网格线 ----------

    private fun toggleMode() {
        if (recording != null) {
            Toast.makeText(this, R.string.toast_recording_mode, Toast.LENGTH_SHORT).show()
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
        val currentMode = getString(if (photoMode) R.string.mode_photo else R.string.mode_video)
        binding.btnMode.text = currentMode
        binding.btnMode.contentDescription = getString(R.string.desc_current_mode, currentMode)
        // This chip labels the active mode, so it stays highlighted in both modes.
        binding.btnMode.isSelected = true
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
            Toast.makeText(this, R.string.toast_recording_switch, Toast.LENGTH_SHORT).show()
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
        quickControlsBinding?.btnGrid?.apply {
            isSelected = gridMode != GridOverlayView.MODE_OFF
            setImageResource(
                if (gridMode == GridOverlayView.MODE_OFF) R.drawable.ic_grid_off
                else R.drawable.ic_grid
            )
        }
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
        quickControlsBinding?.btnAspect?.apply {
            isSelected = aspectRatio != ASPECT_4_3
            text = when (aspectRatio) {
                ASPECT_16_9 -> getString(R.string.aspect_16_9)
                ASPECT_1_1 -> getString(R.string.aspect_1_1)
                else -> getString(R.string.aspect_4_3)
            }
        }
    }



    // ---------- 取景快捷控制 ----------
    private fun showQuickControls() {
        val controls = binding.quickControlsPanel
        if (controls.root.visibility == View.VISIBLE) {
            closeQuickControls()
            return
        }
        quickControlsBinding = controls
        updateTimerUi()
        updateAspectUi()
        updateGridUi()
        controls.root.visibility = View.VISIBLE
        binding.btnSettings.contentDescription = getString(R.string.desc_close_quick_controls)
        controls.btnCloseQuickControls.setOnClickListener { closeQuickControls() }
        controls.btnTimer.setOnClickListener { cycleTimer() }
        controls.btnAspect.setOnClickListener { cycleAspect() }
        controls.btnGrid.setOnClickListener { cycleGrid() }
        controls.btnQrScanner.setOnClickListener {
            closeQuickControls()
            qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }
        controls.btnAdvancedSettings.setOnClickListener {
            closeQuickControls()
            showSettings()
        }
    }
    private fun closeQuickControls() {
        binding.quickControlsPanel.root.visibility = View.GONE
        quickControlsBinding = null
        binding.btnSettings.contentDescription = getString(R.string.desc_quick_controls)
    }
    // ---------- 设置弹窗 ----------
    private fun showSettings() {
        val view = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        view.switchMirror.isChecked = mirrorSelfie
        view.switchShutterSound.isChecked = shutterSound
        view.switchTapCapture.isChecked = tapCapture
        view.switchAutoUpdate.isChecked = autoUpdateCheck
        installedVersionView = view.tvInstalledVersion
        updateEntryView = view.tvUpdateAvailable
        updateStatusContainer = view.updateStatusContainer
        view.updateStatusContainer.setOnClickListener {
            availableUpdateInfo?.let { info -> UpdateInstaller.openApkDownload(this, info) }
        }
        refreshUpdateEntry(view.tvUpdateAvailable)
        view.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                mirrorSelfie = view.switchMirror.isChecked
                shutterSound = view.switchShutterSound.isChecked
                tapCapture = view.switchTapCapture.isChecked
                autoUpdateCheck = view.switchAutoUpdate.isChecked
                prefs.edit()
                    .putBoolean(KEY_MIRROR, mirrorSelfie)
                    .putBoolean(KEY_SHUTTER_SOUND, shutterSound)
                    .putBoolean(KEY_TAP_CAPTURE, tapCapture)
                    .putBoolean(KEY_AUTO_UPDATE, autoUpdateCheck)
                    .apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (updateEntryView === view.tvUpdateAvailable) {
                installedVersionView = null
                updateEntryView = null
                updateStatusContainer = null
            }
        }
        dialog.show()
    }

    /**
     * 启动时自动检查更新（默认关闭，用户在设置中开启后才生效；
     * 每天最多检查一次，只连接 GitHub）
     */
    private fun maybeAutoCheckUpdate() {
        if (!autoUpdateCheck) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
        if (now - last < 24 * 60 * 60 * 1000L) return
        backgroundExecutor.execute {
            val result = UpdateChecker.check(this)
            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
            when (result) {
                is UpdateChecker.Result.Available -> {
                    availableUpdateInfo = result.info
                    cacheAvailableUpdate(result.info)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            // 更新只显示在设置行；用户点击提示时直接打开 APK 下载。
                            refreshUpdateEntry()
                        }
                    }
                }
                UpdateChecker.Result.UpToDate -> {
                    availableUpdateInfo = null
                    clearCachedAvailableUpdate()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) refreshUpdateEntry()
                    }
                }
                is UpdateChecker.Result.Failed -> Unit // Keep a previously verified result visible offline.
            }
        }
    }

    private fun loadCachedAvailableUpdate(): UpdateChecker.UpdateInfo? {
        val version = prefs.getString(KEY_AVAILABLE_UPDATE_VERSION, null) ?: return null
        if (!UpdateChecker.isNewer(version, UpdateChecker.currentVersion(this))) {
            clearCachedAvailableUpdate()
            return null
        }
        val releaseUrl = prefs.getString(KEY_AVAILABLE_UPDATE_RELEASE_URL, null).orEmpty()
        val downloadUrl = prefs.getString(KEY_AVAILABLE_UPDATE_DOWNLOAD_URL, null).orEmpty()
        if (releaseUrl.isBlank() || downloadUrl.isBlank()) {
            clearCachedAvailableUpdate()
            return null
        }
        return UpdateChecker.UpdateInfo(
            version = version,
            notes = prefs.getString(KEY_AVAILABLE_UPDATE_NOTES, "").orEmpty(),
            downloadUrl = downloadUrl,
            htmlUrl = releaseUrl
        )
    }

    private fun cacheAvailableUpdate(info: UpdateChecker.UpdateInfo) {
        prefs.edit()
            .putString(KEY_AVAILABLE_UPDATE_VERSION, info.version)
            .putString(KEY_AVAILABLE_UPDATE_NOTES, info.notes)
            .putString(KEY_AVAILABLE_UPDATE_DOWNLOAD_URL, info.downloadUrl)
            .putString(KEY_AVAILABLE_UPDATE_RELEASE_URL, info.htmlUrl)
            .apply()
    }

    private fun clearCachedAvailableUpdate() {
        prefs.edit()
            .remove(KEY_AVAILABLE_UPDATE_VERSION)
            .remove(KEY_AVAILABLE_UPDATE_NOTES)
            .remove(KEY_AVAILABLE_UPDATE_DOWNLOAD_URL)
            .remove(KEY_AVAILABLE_UPDATE_RELEASE_URL)
            .apply()
    }

    private fun refreshUpdateEntry(target: TextView? = updateEntryView) {
        val currentVersion = UpdateChecker.currentVersion(this)
        val currentVersionText = getString(R.string.setting_current_version, currentVersion)
        installedVersionView?.text = currentVersionText
        val info = availableUpdateInfo
        val updateAccessibilityText = info?.let {
            getString(R.string.setting_update_available_accessibility, currentVersion, it.version)
        }
        updateStatusContainer?.isEnabled = info != null
        updateStatusContainer?.contentDescription = updateAccessibilityText ?: currentVersionText

        val view = target ?: return
        view.visibility = if (info == null) View.GONE else View.VISIBLE
        if (info != null) {
            view.text = getString(R.string.setting_update_available, info.version)
            view.contentDescription = updateAccessibilityText
        }
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
        backgroundExecutor.execute {
            try {
                // 只取最新一条（快路径），不再每次全量扫描记录表
                val latest = CaptureStore.latest(this)
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
        private const val KEY_AUTO_UPDATE = "auto_update_check"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_AVAILABLE_UPDATE_VERSION = "available_update_version"
        private const val KEY_AVAILABLE_UPDATE_NOTES = "available_update_notes"
        private const val KEY_AVAILABLE_UPDATE_DOWNLOAD_URL = "available_update_download_url"
        private const val KEY_AVAILABLE_UPDATE_RELEASE_URL = "available_update_release_url"

        private const val ASPECT_4_3 = 0
        private const val ASPECT_16_9 = 1
        private const val ASPECT_1_1 = 2
    }
}
