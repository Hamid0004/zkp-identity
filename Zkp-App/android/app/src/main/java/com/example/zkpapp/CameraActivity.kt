package com.example.zkpapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    // ✅ Multi-threading safety
    private val scanLocked = AtomicBoolean(false)
    private var lastProcessingTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        viewFinder = findViewById(R.id.viewFinder)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // ✅ ML Kit V2 (Latin) for higher accuracy
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

            // ✅ Image Analysis optimized for MRZ
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
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (e: Exception) {
                Log.e("CameraActivity", "Camera Binding Failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        
        // ⏱ Throttle: Process 1 frame every 600ms to prevent CPU lag
        if (scanLocked.get() || (currentTime - lastProcessingTime < 600)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: return
        lastProcessingTime = currentTime

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                val mrz = extractAndCleanMrz(rawText)

                if (mrz != null && scanLocked.compareAndSet(false, true)) {
                    deliverFinalResult(mrz)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // --------------------------------------------------
    // 🧠 ADVANCED MRZ CLEANING & VALIDATION
    // --------------------------------------------------
    private fun extractAndCleanMrz(text: String): String? {
        // 1. Clean lines (Remove spaces and keep only valid MRZ chars)
        val lines = text.uppercase()
            .replace(" ", "")
            .lines()
            .filter { it.length >= 30 } // Ignore short snippets

        if (lines.size < 2) return null

        // 2. Find TD3 (Passport) 44-character lines
        // Usually, MRZ is the LAST 2 lines of the recognized text
        val potentialL1 = lines[lines.size - 2]
        val potentialL2 = lines[lines.size - 1]

        // 3. Basic ICAO Correction (Common OCR mistakes)
        val l1 = cleanOcrNoise(potentialL1)
        val l2 = cleanOcrNoise(potentialL2)

        // 4. Final Validation for Passport (Type P)
        if (l1.length == 44 && l2.length == 44 && l1.startsWith("P<")) {
            return "$l1\n$l2"
        }

        return null
    }

    private fun cleanOcrNoise(line: String): String {
        return line.replace("(", "<")
            .replace("[", "<")
            .replace("{", "<")
            .replace("}", "<")
            .replace("]", "<")
            .replace("«", "<")
    }

    private fun deliverFinalResult(mrz: String) {
        runOnUiThread {
            Toast.makeText(this, "✅ Passport MRZ Locked", Toast.LENGTH_SHORT).show()
            
            // Stop camera immediately
            cameraProvider?.unbindAll()

            val resultIntent = Intent()
            resultIntent.putExtra("MRZ_DATA", mrz)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    // --------------------------------------------------
    // 🛡️ PERMISSIONS & CLEANUP
    // --------------------------------------------------
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}