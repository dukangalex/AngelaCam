package com.dukangalex.angelacam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dukangalex.angelacam.databinding.ActivityQrScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
class QrScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var barcodeScanner: BarcodeScanner? = null
    private val resultHandled = AtomicBoolean(false)

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.qr_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val imageAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (resultHandled.get()) {
            imageProxy.close()
            return@Analyzer
        }
        val mediaImage = imageProxy.image
        val scanner = barcodeScanner
        if (mediaImage == null || scanner == null) {
            imageProxy.close()
            return@Analyzer
        }

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (!resultHandled.get()) {
                        val result = QrCodeResultHandler.firstResult(
                            barcodes.map { barcode ->
                                QrCodeResultHandler.candidateFromDecodedFormat(
                                    barcode.format,
                                    barcode.rawValue
                                )
                            }
                        )
                        if (result != null && resultHandled.compareAndSet(false, true)) {
                            runOnUiThread { showResult(result.text) }
                        }
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "QR scan frame could not be decoded", error)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (error: Exception) {
            Log.w(TAG, "Could not process QR scan frame", error)
            imageProxy.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDone.setOnClickListener { finish() }
        binding.btnScanAgain.setOnClickListener { scanAgain() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis = analysis
                analysis.setAnalyzer(cameraExecutor, imageAnalyzer)
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not start QR scanner camera", error)
                binding.tvInstruction.text = getString(R.string.qr_camera_start_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showResult(text: String) {
        imageAnalysis?.clearAnalyzer()
        binding.finderFrame.visibility = View.GONE
        binding.tvInstruction.visibility = View.GONE
        binding.tvSafetyNote.visibility = View.GONE
        binding.tvResultValue.text = text
        binding.resultPanel.visibility = View.VISIBLE
    }

    private fun scanAgain() {
        resultHandled.set(false)
        binding.resultPanel.visibility = View.GONE
        binding.finderFrame.visibility = View.VISIBLE
        binding.tvInstruction.visibility = View.VISIBLE
        binding.tvSafetyNote.visibility = View.VISIBLE
        imageAnalysis?.setAnalyzer(cameraExecutor, imageAnalyzer)
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        barcodeScanner?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QrScannerActivity"
    }
}
