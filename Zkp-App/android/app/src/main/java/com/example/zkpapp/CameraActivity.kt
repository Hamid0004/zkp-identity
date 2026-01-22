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
    private var isProcessing = false // Taaki ek baar mein ek hi scan ho

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera) // Ensure XML file sahi bani ho

        viewFinder = findViewById(R.id.viewFinder)

        // Permission check
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Camera Provider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview (Screen par dikhana)
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // Image Analyzer (Text Padne wala dimagh)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            // Back Camera Select karein
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Pehle sab unbind karein phir naya bind karein
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 🧠 IMAGE PROCESSING LOGIC
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class) 
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // Google ML Kit Text Recognizer
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Text mil gaya, ab check karte hain ke MRZ hai ya nahi
                    checkForMrz(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text detection failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // 🕵️ MRZ FILTER (Magic Logic)
    private fun checkForMrz(fullText: String) {
        val lines = fullText.split("\n")
        
        // MRZ Logic: 
        // 1. Line lambi honi chahiye (> 30 chars)
        // 2. Usmein '<<<' hona chahiye (Passport format)
        val mrzLine = lines.firstOrNull { it.length > 30 && it.contains("<<<") }

        if (mrzLine != null) {
            isProcessing = true // Scanning roko
            Log.d(TAG, "MRZ FOUND: $mrzLine")
            
            runOnUiThread {
                Toast.makeText(this, "✅ MRZ DETECTED!", Toast.LENGTH_SHORT).show()
                
                // Result wapas PassportActivity ko bhejein
                val resultIntent = Intent()
                resultIntent.putExtra("MRZ_DATA", mrzLine)
                setResult(RESULT_OK, resultIntent)
                finish() // Camera band karein
            }
        }
    }

    // --- PERMISSION HELPERS ---

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}