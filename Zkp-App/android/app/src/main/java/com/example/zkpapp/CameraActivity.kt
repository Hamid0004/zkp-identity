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

class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var recognizer: TextRecognizer
    private var cameraProvider: ProcessCameraProvider? = null

    // ✅ Thread Safety & Throttle
    private val scanLocked = AtomicBoolean(false)
    private var lastProcessingTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        viewFinder = findViewById(R.id.viewFinder)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // ✅ Initialize ML Kit
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            // ✅ Analysis Setup
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
            } catch (e: Exception) {
                Log.e("CameraActivity", "Camera Binding Failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // ⏱ Throttle: 600ms delay to keep device cool
        if (scanLocked.get() || (currentTime - lastProcessingTime < 600)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        
        lastProcessingTime = currentTime
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Extract Logic
                val mrz = extractAndCleanMrz(visionText.text)

                if (mrz != null && scanLocked.compareAndSet(false, true)) {
                    performSuccessFeedback() // 📳 Vibrate
                    deliverFinalResult(mrz)
                }
            }
            .addOnFailureListener { e ->
                Log.w("CameraActivity", "ML Kit failed", e)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // --------------------------------------------------
    // 🧠 SMART MRZ FINDER (Real Passport Logic)
    // --------------------------------------------------
    private fun extractAndCleanMrz(text: String): String? {
        val rawLines = text.uppercase().lines()

        // Step 1: Filter lines that are "Passport-ish" (contain '<' and long enough)
        val validLines = rawLines.map { line ->
            line.replace(" ", "").trim()
        }.filter { it.length > 30 && it.contains("<") }

        if (validLines.size < 2) return null

        // Step 2: Smart Search Loop
        for (i in 0 until validLines.size - 1) {
            val line1 = cleanOcrNoise(validLines[i])
            val line2 = cleanOcrNoise(validLines[i + 1])

            // ✅ STRICT CHECK:
            // 1. Line 1 starts with "P<" (Standard Passport)
            // 2. Both lines are exactly 44 characters (ICAO 9303 Standard)
            if (line1.startsWith("P<") && line1.length == 44 && line2.length == 44) {
                Log.d("CameraActivity", "MRZ Found: \n$line1\n$line2")
                return "$line1\n$line2"
            }
        }

        return null
    }

    // 🧹 Cleaning common OCR mistakes (e.g. '[' instead of '<')
    private fun cleanOcrNoise(line: String): String {
        return line.replace("(", "<")
            .replace("[", "<")
            .replace("{", "<")
            .replace("}", "<")
            .replace("]", "<")
            .replace("«", "<") // Common OCR error for <<
            .replace(" ", "")
    }

    // 📳 Haptic Feedback (Vibration)
    private fun performSuccessFeedback() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(100)
            }
        } catch (e: Exception) {
            // Ignore if vibration fails
        }
    }

    private fun deliverFinalResult(mrz: String) {
        runOnUiThread {
            Toast.makeText(this, "✅ Passport MRZ Locked", Toast.LENGTH_SHORT).show()

            // Stop camera immediately
            cameraProvider?.unbindAll()

            val resultIntent = Intent()
            // 🦁 Note: Ensure PassportActivity expects "MRZ_DATA"
            resultIntent.putExtra("MRZ_DATA", mrz)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    // --------------------------------------------------
    // 🛡️ PERMISSIONS & CLEANUP
    // --------------------------------------------------
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}