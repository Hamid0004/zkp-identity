package com.example.zkpapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    @Volatile
    private var scanLocked = false   // 🔒 Prevent multi-detection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        viewFinder = findViewById(R.id.viewFinder)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE
            )
        }
    }

    // --------------------------------------------------
    // CAMERA PIPELINE
    // --------------------------------------------------
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // --------------------------------------------------
    // OCR ANALYSIS
    // --------------------------------------------------
    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (scanLocked) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(image)
            .addOnSuccessListener { result ->
                extractMrz(result.text)
            }
            .addOnFailureListener {
                Log.e(TAG, "OCR failed", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    // --------------------------------------------------
    // MRZ DETECTION LOGIC (CORE DAY‑66)
    // --------------------------------------------------
    private fun extractMrz(rawText: String) {
        val lines = rawText
            .uppercase()
            .replace(" ", "")
            .split("\n")
            .filter { it.length >= 30 }

        // Passport MRZ: 2 or 3 consecutive lines with <<<
        val candidates = lines.filter { it.contains("<<") }

        if (candidates.size >= 2) {
            scanLocked = true

            val mrz = candidates.take(2).joinToString("\n")

            Log.d(TAG, "MRZ DETECTED:\n$mrz")

            runOnUiThread {
                Toast.makeText(this, "✅ MRZ Detected", Toast.LENGTH_SHORT).show()

                val intent = Intent().apply {
                    putExtra("MRZ_DATA", mrz)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
        }
    }

    // --------------------------------------------------
    // PERMISSIONS
    // --------------------------------------------------
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE && hasCameraPermission()) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CODE = 101
    }
}
