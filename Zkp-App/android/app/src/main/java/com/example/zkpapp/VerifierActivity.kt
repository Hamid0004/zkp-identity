package com.example.zkpapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * VerifierActivity - The ZK Proof Validator 🕵️‍♂️
 *
 * Features:
 * ✅ Offline Verification (Chunked QR)
 * ✅ Checksum Integrity Check
 * ✅ Rust Native Crypto Engine
 */
class VerifierActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════
    // 🦁 CONFIGURATION & NATIVE LIBS
    // ═══════════════════════════════════════════════════════════
    companion object {
        init {
            System.loadLibrary("zkp_mobile")
        }
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val TIMEOUT_DURATION_MS = 8000L // 8 Seconds Timeout
    }

    // Rust JNI Function
    external fun verifyProofFromRust(proof: String): String

    // ═══════════════════════════════════════════════════════════
    // 📱 UI COMPONENTS
    // ═══════════════════════════════════════════════════════════
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // ═══════════════════════════════════════════════════════════
    // 🧵 STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════
    private val receivedChunks = ConcurrentHashMap<Int, String>()
    private var totalChunksExpected = -1
    private var lastScannedTime = 0L
    @Volatile private var isProcessing = false

    // Watchdog for timeouts
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private lateinit var watchdogRunnable: Runnable

    // Audio Feedback
    private val toneGen by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }

    // ═══════════════════════════════════════════════════════════
    // 🎬 LIFECYCLE
    // ═══════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier)

        // Init UI
        barcodeView = findViewById(R.id.scannerView)
        statusText = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        // Check Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            startScanning()
            startTimeoutWatchdog()
        }
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try { toneGen.release() } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════
    // 📷 SCANNING ENGINE
    // ═══════════════════════════════════════════════════════════

    private fun startScanning() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (isProcessing || result?.text == null) return

                lastScannedTime = System.currentTimeMillis()
                processQrData(result.text)
            }
        })
    }

    private fun processQrData(data: String) {
        // Format: "Index/Total|Payload|Checksum"
        if (data.contains("|") && data.contains("/")) {
            processAnimatedChunk(data)
        }
    }

    private fun processAnimatedChunk(data: String) {
        try {
            val parts = data.split("|")
            val header = parts[0].split("/")

            if (header.size != 2 || parts.size < 2) return

            val currentIndex = header[0].toIntOrNull() ?: return
            val total = header[1].toIntOrNull() ?: return
            val payload = parts[1]

            // 1. Checksum Validation
            if (parts.size == 3 && !validateChecksum(payload, parts[2])) {
                showError("⚠️ Checksum Failed!")
                return
            }

            // 2. New Session Detection
            if (totalChunksExpected != -1 && totalChunksExpected != total) {
                resetSession("🔄 New Identity Detected")
                return
            }

            // 3. Initialize Session
            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            // 4. Store Chunk
            if (!receivedChunks.containsKey(currentIndex)) {
                receivedChunks[currentIndex] = payload

                // Update UI
                runOnUiThread {
                    val progress = receivedChunks.size
                    progressBar.progress = progress
                    statusText.text = "📥 Loading: $progress/$total"
                    statusText.setBackgroundColor(Color.parseColor("#424242"))
                }

                // 5. Complete?
                if (receivedChunks.size == totalChunksExpected) {
                    verifyOfflineProof()
                }
            }

        } catch (_: Exception) {}
    }

    private fun verifyOfflineProof() {
        isProcessing = true
        barcodeView.pause()

        runOnUiThread {
            statusText.text = "🔐 Verifying Math..."
            statusText.setBackgroundColor(Color.parseColor("#FF9800"))
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Reassemble
                val fullProof = buildString {
                    for (i in 1..totalChunksExpected) {
                        append(receivedChunks[i])
                    }
                }

                // Rust Verification
                val report = verifyProofFromRust(fullProof)

                withContext(Dispatchers.Main) {
                    if (report.contains("Verified", ignoreCase = true)) {
                        statusText.text = report
                        statusText.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
                        triggerHapticFeedback(true)
                        delay(5000)
                        resetSession("🔍 Ready to Scan")
                    } else {
                        statusText.text = "❌ FAKE IDENTITY"
                        statusText.setBackgroundColor(Color.RED)
                        triggerHapticFeedback(false)
                        delay(3000)
                        resetSession("❌ Retry")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Error: ${e.message}")
                    resetSession("♻️ Ready")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🛠️ UTILITY
    // ═══════════════════════════════════════════════════════════

    private fun resetSession(msg: String) {
        if (isDestroyed) return
        isProcessing = false
        receivedChunks.clear()
        totalChunksExpected = -1
        lastScannedTime = System.currentTimeMillis()

        runOnUiThread {
            statusText.text = msg
            statusText.setBackgroundColor(Color.TRANSPARENT)
            progressBar.progress = 0
            barcodeView.resume()
        }
    }

    private fun startTimeoutWatchdog() {
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (!isProcessing && receivedChunks.isNotEmpty()) {
                    if (System.currentTimeMillis() - lastScannedTime > TIMEOUT_DURATION_MS) {
                        resetSession("⚠️ Session Timed Out")
                    }
                }
                watchdogHandler.postDelayed(this, 1000)
            }
        }
        watchdogHandler.post(watchdogRunnable)
    }

    private fun validateChecksum(payload: String, checksumStr: String): Boolean {
        return try {
            val expected = checksumStr.toLong()
            val crc = CRC32()
            crc.update(payload.toByteArray())
            crc.value == expected
        } catch (_: Exception) { false }
    }

    private fun triggerHapticFeedback(success: Boolean) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (success) {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else vibrator.vibrate(100)
            } else {
                toneGen.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else vibrator.vibrate(500)
            }
        } catch (_: Exception) {}
    }

    private fun showError(msg: String) {
        runOnUiThread {
            statusText.text = msg
            statusText.setBackgroundColor(Color.RED)
            triggerHapticFeedback(false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanning()
            startTimeoutWatchdog()
        } else {
            Toast.makeText(this, "Permission Required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}