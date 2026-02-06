package com.example.zkpapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.zkpapp.auth.ZkAuthManager
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VerifierActivity : AppCompatActivity() {

    // 🦁 NOTE: Raw Rust loading ab ZkAuthManager handle karta hai.
    
    // JNI Function (For Old Offline Verification only)
    external fun verifyProofFromRust(proof: String): String

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // Variables
    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 
    private var lastVerifiedProofString: String? = null 
    private var isRelayProcessRunning = false 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier)

        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
        
        // 🔋 Stress Test Listener
        statusText.setOnLongClickListener {
            if (lastVerifiedProofString != null) {
                runBatteryStressTest(lastVerifiedProofString!!)
            } else {
                Toast.makeText(this, "Scan a proof first!", Toast.LENGTH_SHORT).show()
            }
            true
        }

        val sessionFromIntent = intent.getStringExtra("SESSION_ID")
        if (sessionFromIntent != null) {
            handleRelayLogin(sessionFromIntent) 
        } else {
            startScanning() 
        }
    }

    private fun startScanning() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (isRelayProcessRunning) return 
                result?.text?.let { rawData -> processQrData(rawData) }
            }
            override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {}
        })
    }

    private fun processQrData(data: String) {
        // CASE 1: Old Logic (Pipes for Offline)
        if (data.contains("|") && data.contains("/")) {
             processAnimatedChunk(data)
             return
        }
        // CASE 2: New Logic (UUID for Relay)
        if (!isRelayProcessRunning && data.length == 36 && !data.contains("|")) {
            handleRelayLogin(data)
        } else if (!isRelayProcessRunning && data.length > 10 && !data.contains("|")) {
            showError("❌ Invalid QR Format")
        }
    }

    // 🦁 MAIN LOGIC: LOGIN TO WEB (CLEANED using Manager)
    private fun handleRelayLogin(sessionId: String) {
        // Stop Camera
        isRelayProcessRunning = true
        barcodeView.pause() 
        progressBar.isIndeterminate = true

        // 🦁 Call the Manager (One-Liner Magic)
        CoroutineScope(Dispatchers.Main).launch {
            ZkAuthManager.startUniversalLogin(
                context = this@VerifierActivity,
                sessionId = sessionId,
                onStatus = { msg ->
                    // Update UI Status
                    statusText.text = msg
                    statusText.setBackgroundColor(Color.parseColor("#FF9800")) // Orange
                },
                onSuccess = {
                    // ✅ Success Handling
                    statusText.text = "✅ LOGIN SUCCESSFUL!"
                    statusText.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
                    triggerSuccessFeedback()
                    Handler(Looper.getMainLooper()).postDelayed({ resetScanner() }, 4000)
                },
                onError = { errorMsg ->
                    // ❌ Error Handling
                    showError(errorMsg)
                }
            )
        }
    }

    // 🔄 Helper: Reset Scanner UI
    private fun resetScanner() {
        if (isDestroyed || isFinishing) return
        isRelayProcessRunning = false
        statusText.text = "🔍 Ready to Scan"
        statusText.setBackgroundColor(Color.TRANSPARENT)
        progressBar.isIndeterminate = false
        barcodeView.resume()
    }

    // ❌ Helper: Show Error & Auto Reset
    private fun showError(msg: String) {
        statusText.text = msg
        statusText.setBackgroundColor(Color.RED)
        progressBar.isIndeterminate = false
        triggerErrorFeedback()
        Handler(Looper.getMainLooper()).postDelayed({ resetScanner() }, 2500)
    }

    // =========================================================
    // 🏛️ OLD LOGIC (Keep as is for Offline Demo)
    // =========================================================

    private fun processAnimatedChunk(data: String) {
        try {
            val parts = data.split("|", limit = 2)
            val header = parts[0] 
            val payload = parts[1] 

            val headerParts = header.split("/")
            val currentIndex = headerParts[0].toInt()
            val total = headerParts[1].toInt()

            if (currentIndex == 1 && receivedChunks.containsKey(1)) {
                val oldPayload = receivedChunks[1]
                if (oldPayload != payload) {
                    receivedChunks.clear()
                    totalChunksExpected = -1
                    lastVerifiedProofString = null
                    runOnUiThread {
                        statusText.text = "🔄 Scanning New Identity..."
                        progressBar.progress = 0
                        progressBar.isIndeterminate = false
                    }
                }
            }

            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            if (!receivedChunks.containsKey(currentIndex)) {
                receivedChunks[currentIndex] = payload
                runOnUiThread {
                    val percent = (receivedChunks.size * 100) / totalChunksExpected
                    statusText.text = "📥 Receiving... $percent%"
                    progressBar.progress = receivedChunks.size
                    if (receivedChunks.size == totalChunksExpected) {
                        finishAnimatedScanning() 
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun finishAnimatedScanning() {
        barcodeView.pause()
        statusText.text = "🔐 Verifying Cryptography..."

        val fullProofBuilder = StringBuilder()
        for (i in 1..totalChunksExpected) {
            if (receivedChunks.containsKey(i)) fullProofBuilder.append(receivedChunks[i])
            else return 
        }
        val fullProofString = fullProofBuilder.toString()
        lastVerifiedProofString = fullProofString

        Thread {
            val resultReport = verifyProofFromRust(fullProofString)
            runOnUiThread {
                if (resultReport.contains("Verified")) {
                    triggerSuccessFeedback()
                    statusText.text = "🎉 PROOF VERIFIED (OFFLINE)"
                    statusText.setBackgroundColor(Color.parseColor("#2E7D32"))
                } else {
                    statusText.text = "⛔ INVALID PROOF"
                    statusText.setBackgroundColor(Color.RED)
                }
            }
        }.start()
    }

    // 📳 Feedback Logic
    private fun triggerSuccessFeedback() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else { v.vibrate(150) }
        } catch (e: Exception) { }
    }

    private fun triggerErrorFeedback() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1))
            } else { v.vibrate(200) }
        } catch (e: Exception) { }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun runBatteryStressTest(proofData: String) {
        statusText.text = "🔥 Stress Testing (100x)..."
        Thread {
            val startBattery = getBatteryLevel()
            for (i in 1..100) verifyProofFromRust(proofData)
            val endBattery = getBatteryLevel()
            runOnUiThread {
                statusText.text = "🔋 Drop: ${startBattery - endBattery}%"
                triggerSuccessFeedback()
            }
        }.start()
    }

    override fun onResume() { super.onResume(); barcodeView.resume() }
    override fun onPause() { super.onPause(); barcodeView.pause() }
}