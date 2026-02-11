package com.example.zkpapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraActivity - Passport MRZ Scanner
 *
 * Features:
 * - Real-time OCR using ML Kit
 * - Smart MRZ detection algorithm
 * - Flash toggle support
 * - Haptic feedback on success
 * - Thread-safe processing
 * - Automatic throttling to prevent double scans
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnFlash: ImageButton
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var recognizer: TextRecognizer

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var isFlashOn = false

    private val scanLocked = AtomicBoolean(false)
    private var lastProcessingTime = 0L

    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CAMERA = 101
        private const val PROCESSING_INTERVAL_MS = 500L
        private const val MIN_MRZ_LINE_LENGTH = 30
        private const val MIN_MRZ_ANGLE_BRACKETS = 5
        private const val MRZ_LINE_MIN_LENGTH = 40
        private const val MRZ_LINE_MAX_LENGTH = 50
        const val EXTRA_MRZ_DATA = "MRZ_DATA"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        initializeViews()
        initializeMLKit()
        setupFlashButton()
        checkCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun initializeViews() {
        viewFinder = findViewById(R.id.viewFinder)
        btnFlash = findViewById(R.id.btnFlash)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initializeMLKit() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun setupFlashButton() {
        btnFlash.setOnClickListener {
            toggleFlash()
        }
    }

    private fun checkCameraPermission() {
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                showError("Failed to initialize camera")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val preview = buildPreview()
        val imageAnalyzer = buildImageAnalyzer()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider?.unbindAll()

            val camera = cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            cameraControl = camera?.cameraControl

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            showError("Failed to start camera")
            finish()
        }
    }

    private fun buildPreview(): Preview {
        return Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
    }

    private fun buildImageAnalyzer(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }
    }

    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        cameraControl?.enableTorch(isFlashOn)
        btnFlash.alpha = if (isFlashOn) 1.0f else 0.5f
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!shouldProcessImage()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        lastProcessingTime = System.currentTimeMillis()

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                handleOcrSuccess(visionText.text)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR processing failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun shouldProcessImage(): Boolean {
        val currentTime = System.currentTimeMillis()

        return !scanLocked.get() &&
                (currentTime - lastProcessingTime >= PROCESSING_INTERVAL_MS)
    }

    private fun handleOcrSuccess(text: String) {
        val mrz = extractMrz(text)

        if (mrz != null && scanLocked.compareAndSet(false, true)) {
            Log.i(TAG, "MRZ detected successfully")
            performSuccessFeedback()
            deliverResult(mrz)
        }
    }

    private fun extractMrz(rawText: String): String? {
        val normalized = normalizeText(rawText)
        val candidateLines = filterCandidateLines(normalized)

        if (candidateLines.size < 2) return null

        return findMrzPair(candidateLines)
    }

    private fun normalizeText(text: String): String {
        return text.uppercase()
            .replace("«", "<")
            .replace(" ", "")
            .replace("O", "0")
            .replace("I", "1")
    }

    private fun filterCandidateLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { line ->
                line.length > MIN_MRZ_LINE_LENGTH &&
                        line.contains("<")
            }
    }

    private fun findMrzPair(lines: List<String>): String? {
        for (i in 0 until lines.size - 1) {
            val line1 = lines[i]
            val line2 = lines[i + 1]

            if (isValidMrzPair(line1, line2)) {
                Log.d(TAG, "MRZ found:\nLine1: $line1\nLine2: $line2")
                return "$line1\n$line2"
            }
        }
        return null
    }

    private fun isValidMrzPair(line1: String, line2: String): Boolean {
        return line1.startsWith("P") &&
                line1.count { it == '<' } >= MIN_MRZ_ANGLE_BRACKETS &&
                line1.length in MRZ_LINE_MIN_LENGTH..MRZ_LINE_MAX_LENGTH &&
                line2.length in MRZ_LINE_MIN_LENGTH..MRZ_LINE_MAX_LENGTH
    }

    private fun performSuccessFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback failed", e)
        }
    }

    private fun deliverResult(mrz: String) {
        runOnUiThread {
            Toast.makeText(this, "✅ Passport Scanned!", Toast.LENGTH_SHORT).show()

            val resultIntent = Intent().apply {
                putExtra(EXTRA_MRZ_DATA, mrz)
            }

            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA
        )
    }

    private fun showError(message: String) {
        Toast.makeText(this, "❌ $message", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                showError("Camera permission required")
                finish()
            }
        }
    }
}
