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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class VerifierActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("zkp_mobile")
        }
    }

    // 🦁 CONFIG: Aapka Server URL
    private val BASE_URL = "https://crispy-dollop-97xj7vjgx4ph9pgg-3000.app.github.dev/"

    // JNI Function (For Offline Verification)
    external fun verifyProofFromRust(proof: String): String

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // 🏛️ OLD LOGIC VARIABLES
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
                result?.text?.let { processQrData(it) }
            }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    private fun processQrData(data: String) {
        // CASE 1: Old Logic (Pipes)
        if (data.contains("|") && data.contains("/")) {
             processAnimatedChunk(data)
             return
        }
        // CASE 2: New Logic (UUID)
        if (!isRelayProcessRunning && data.length > 20 && !data.contains("|")) {
            handleRelayLogin(data)
        }
    }

    // 🦁 MAIN LOGIC: LOGIN TO WEB
    private fun handleRelayLogin(sessionId: String) {
        isRelayProcessRunning = true
        barcodeView.pause() 

        runOnUiThread {
            statusText.text = "🦁 ID Detected!\nGenerating Proof..."
            statusText.setBackgroundColor(Color.parseColor("#FF9800")) // Orange
            progressBar.isIndeterminate = true
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Generate Proof (Heavy Math)
                val proofOutput = ZkAuth.generateSecureNullifier("123456", "zk_login", sessionId)
                if (proofOutput.startsWith("🔥")) throw Exception("Proof Gen Failed")

                // 2. Upload to Relay Server
                withContext(Dispatchers.Main) { statusText.text = "☁️ Uploading to Web..." }

                // 👇 FIX: Force HTTP/1.1 to prevent "Stream Reset" errors
                val client = OkHttpClient.Builder()
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client) // Attach custom client
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
                        statusText.text = "❌ Server Error: ${response.code()}"
                        statusText.setBackgroundColor(Color.RED)
                        isRelayProcessRunning = false 
                        barcodeView.resume()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "🔥 Net Error: ${e.message}"
                    isRelayProcessRunning = false
                    barcodeView.resume()
                }
            }
        }
    }

    // 🏛️ OLD LOGIC: ANIMATED CHUNK PROCESSING (Offline)
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