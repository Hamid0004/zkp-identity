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
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class VerifierActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("zkp_mobile") 
        }
    }

    external fun verifyProofFromRust(proof: String): String

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // Logic Variables
    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 
    private var lastVerifiedProofString: String? = null 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier)

        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
        
        // 🔋 Hidden Stress Test (Long Press)
        statusText.setOnLongClickListener {
            if (lastVerifiedProofString != null) {
                runBatteryStressTest(lastVerifiedProofString!!)
            } else {
                Toast.makeText(this, "Scan a proof first!", Toast.LENGTH_SHORT).show()
            }
            true
        }

        startScanning()
    }

    private fun startScanning() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { rawData ->
                    processQrData(rawData)
                }
            }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    private fun processQrData(data: String) {
        try {
            if (!data.contains("|") || !data.contains("/")) return

            val parts = data.split("|", limit = 2)
            val header = parts[0] 
            val payload = parts[1] 

            val headerParts = header.split("/")
            val currentIndex = headerParts[0].toInt()
            val total = headerParts[1].toInt()

            // 🔄 SMART SESSION RESET
            if (currentIndex == 1 && receivedChunks.containsKey(1)) {
                val oldPayload = receivedChunks[1]
                if (oldPayload != payload) {
                    receivedChunks.clear()
                    totalChunksExpected = -1
                    lastVerifiedProofString = null
                    runOnUiThread {
                        statusText.text = "🔄 Scanning New Identity..."
                        statusText.setTextColor(Color.WHITE)
                        statusText.setBackgroundColor(Color.TRANSPARENT) // Reset BG
                        progressBar.progress = 0
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
                    // Cleaner Progress Text
                    val percent = (receivedChunks.size * 100) / totalChunksExpected
                    statusText.text = "📥 Receiving... $percent%"
                    progressBar.progress = receivedChunks.size

                    if (receivedChunks.size == totalChunksExpected) {
                        finishScanning() 
                    }
                }
            }
        } catch (e: Exception) { }
    }

    // 👇 SENSORY FEEDBACK: Sound & Vibrate 📳🔊
    private fun triggerSuccessFeedback() {
        try {
            // 1. Sound (Beep)
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)

            // 2. Vibration (Haptic)
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(150)
            }
        } catch (e: Exception) { }
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemInBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedMemInBytes / (1024 * 1024) 
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun runBatteryStressTest(proofData: String) {
        statusText.text = "🔥 Stress Testing (100x)..."
        statusText.setTextColor(Color.YELLOW)
        
        Thread {
            val startBattery = getBatteryLevel()
            val startTime = System.currentTimeMillis()
            
            for (i in 1..100) {
                 verifyProofFromRust(proofData)
            }
            
            val endTime = System.currentTimeMillis()
            val endBattery = getBatteryLevel()
            val batteryDrop = startBattery - endBattery
            val totalTime = endTime - startTime

            runOnUiThread {
                val report = "🔋 EFFICIENCY REPORT\n" +
                             "Loops: 100 | Drop: $batteryDrop%\n" +
                             "Total Time: ${totalTime}ms"
                statusText.text = report
                statusText.setTextColor(Color.CYAN)
                triggerSuccessFeedback() // Beep on finish
            }
        }.start()
    }

    private fun finishScanning() {
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
            try {
                val ramBefore = getMemoryUsage()
                val resultReport = verifyProofFromRust(fullProofString)
                val ramAfter = getMemoryUsage()
                val ramPeak = if(ramAfter > ramBefore) ramAfter else ramBefore

                runOnUiThread {
                    if (resultReport.contains("Verified")) {
                        // ✨ SUCCESS UI POLISH ✨
                        triggerSuccessFeedback() // 🔊 + 📳
                        
                        statusText.text = "🎉 IDENTITY VERIFIED!\n" +
                                          "⚡ Speed: Fast | 💾 RAM: ${ramPeak}MB"
                        
                        // Green Background for clear visual cue
                        statusText.setBackgroundColor(Color.parseColor("#2E7D32")) // Dark Green
                        statusText.setTextColor(Color.WHITE)
                        statusText.setPadding(20, 20, 20, 20)
                        
                        progressBar.progressDrawable.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                        
                    } else {
                        // ❌ FAILURE UI
                        statusText.text = "⛔ INVALID PROOF DETECTED"
                        statusText.setBackgroundColor(Color.parseColor("#C62828")) // Red
                        statusText.setTextColor(Color.WHITE)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { statusText.text = "Error" }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); barcodeView.resume() }
    override fun onPause() { super.onPause(); barcodeView.pause() }
}