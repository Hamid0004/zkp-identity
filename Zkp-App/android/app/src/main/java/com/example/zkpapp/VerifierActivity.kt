package com.example.zkpapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.zkpapp.models.ProofRequest
import com.example.zkpapp.network.RelayApi
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class VerifierActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("zkp_mobile") 
        }
    }

    // 🦁 DAY 81: RELAY SERVER CONFIGURATION
    // Aapka Codespace URL (Make sure last mein '/' ho)
    private val BASE_URL = "https://crispy-dollop-97xj7vjgx4ph9pgg-3000.app.github.dev/"

    // JNI Function (For Old Logic: Verification)
    external fun verifyProofFromRust(proof: String): String

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // 🏛️ OLD LOGIC VARIABLES (Animated QR)
    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 
    private var lastVerifiedProofString: String? = null 
    private var isRelayProcessRunning = false // Flag to prevent double scanning

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier)

        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        // Permission Check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
        
        // 🔋 Hidden Stress Test (Old Logic Feature)
        statusText.setOnLongClickListener {
            if (lastVerifiedProofString != null) {
                runBatteryStressTest(lastVerifiedProofString!!)
            } else {
                Toast.makeText(this, "Scan a proof first!", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Check if started via Intent (from CameraActivity)
        val sessionFromIntent = intent.getStringExtra("SESSION_ID")
        if (sessionFromIntent != null) {
            handleRelayLogin(sessionFromIntent) // Direct Relay Login
        } else {
            startScanning() // Start Camera
        }
    }

    private fun startScanning() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (isRelayProcessRunning) return // Busy processing

                result?.text?.let { rawData ->
                    processQrData(rawData)
                }
            }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    // 🧠 THE SMART BRAIN: Decides Old vs New Logic
    private fun processQrData(data: String) {
        
        // CASE 1: 🏛️ OLD LOGIC (Animated QR)
        // Format looks like: "1/20|Base64..."
        if (data.contains("|") && data.contains("/")) {
             processAnimatedChunk(data)
             return
        }

        // CASE 2: 🦁 NEW LOGIC (Day 81 Relay Login)
        // Format looks like UUID: "59f92686-ffc3..." (No pipes)
        if (!isRelayProcessRunning && data.length > 20 && !data.contains("|")) {
            handleRelayLogin(data)
        }
    }

    // 🦁 DAY 81 LOGIC: GENERATE & UPLOAD
    private fun handleRelayLogin(sessionId: String) {
        isRelayProcessRunning = true
        barcodeView.pause() // Stop camera

        runOnUiThread {
            statusText.text = "🦁 ID Detected!\nGenerating Proof..."
            statusText.setBackgroundColor(Color.parseColor("#FF9800")) // Orange
            progressBar.isIndeterminate = true
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Generate Proof (Using ZkAuth JNI from Day 78)
                // Note: Using hardcoded secret for Demo binding
                val proofOutput = ZkAuth.generateSecureNullifier("123456", "zk_login", sessionId)

                if (proofOutput.startsWith("🔥")) throw Exception("Proof Gen Failed")

                // 2. Upload to Relay Server
                withContext(Dispatchers.Main) { statusText.text = "☁️ Uploading to Web..." }

                val retrofit = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(RelayApi::class.java)
                val request = ProofRequest(session_id = sessionId, proof_data = proofOutput)
                
                val response = api.uploadProof(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        statusText.text = "✅ LOGIN SUCCESSFUL!\nCheck Laptop Screen"
                        statusText.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
                        triggerSuccessFeedback()
                    } else {
                        statusText.text = "❌ Upload Failed: ${response.code()}"
                        statusText.setBackgroundColor(Color.RED)
                        isRelayProcessRunning = false // Allow retry
                        barcodeView.resume()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "🔥 Error: ${e.message}"
                    isRelayProcessRunning = false
                    barcodeView.resume()
                }
            }
        }
    }

    // 🏛️ OLD LOGIC: ANIMATED CHUNK PROCESSING
    private fun processAnimatedChunk(data: String) {
        try {
            val parts = data.split("|", limit = 2)
            val header = parts[0] 
            val payload = parts[1] 

            val headerParts = header.split("/")
            val currentIndex = headerParts[0].toInt()
            val total = headerParts[1].toInt()

            // Reset Logic
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
            if (receivedChunks.containsKey(i)) {
                fullProofBuilder.append(receivedChunks[i])
            } else { return }
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

    // 👇 SHARED HELPERS (Battery, Sound, Vibrate)
    private fun triggerSuccessFeedback() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(150)
            }
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