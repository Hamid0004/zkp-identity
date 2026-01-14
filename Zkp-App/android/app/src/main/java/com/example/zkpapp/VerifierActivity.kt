package com.example.zkpapp

import android.Manifest
import android.content.Context // 👈 New Import
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager // 👈 New Import
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast // 👈 New Import
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

    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 
    
    // 👇 Valid Proof ko save karenge Stress Test ke liye
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
        
        // 🔋 STRESS TEST TRIGGER (Long Press on Text)
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

            // 🔄 SMART SESSION RESET LOGIC
            if (currentIndex == 1 && receivedChunks.containsKey(1)) {
                val oldPayload = receivedChunks[1]
                if (oldPayload != payload) {
                    receivedChunks.clear()
                    totalChunksExpected = -1
                    lastVerifiedProofString = null // Reset stored proof
                    runOnUiThread {
                        statusText.text = "🔄 New Session..."
                        statusText.setTextColor(Color.WHITE)
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
                    statusText.text = "Caught: ${receivedChunks.size} / $totalChunksExpected"
                    progressBar.progress = receivedChunks.size

                    if (receivedChunks.size == totalChunksExpected) {
                        finishScanning() 
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore bad scans
        }
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemInBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedMemInBytes / (1024 * 1024) 
    }

    // 👇 NEW: Get Battery Percentage
    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // 👇 NEW: The Stress Test Function (100 Iterations)
    private fun runBatteryStressTest(proofData: String) {
        statusText.text = "🔥 Running Stress Test (100x)..."
        statusText.setTextColor(Color.YELLOW)
        
        Thread {
            val startBattery = getBatteryLevel()
            val startTime = System.currentTimeMillis()
            
            // 🔄 LOOP 100 TIMES
            var successCount = 0
            for (i in 1..100) {
                 val result = verifyProofFromRust(proofData)
                 if (result.contains("Verified")) successCount++
            }
            
            val endTime = System.currentTimeMillis()
            val endBattery = getBatteryLevel()
            val batteryDrop = startBattery - endBattery
            val totalTime = endTime - startTime

            runOnUiThread {
                val report = "🔋 STRESS TEST COMPLETE\n" +
                             "Loops: 100\n" +
                             "Time Taken: ${totalTime}ms\n" +
                             "Battery Drop: $batteryDrop%"
                
                statusText.text = report
                
                if (batteryDrop == 0) {
                    statusText.setTextColor(Color.CYAN) // Perfect Score
                } else {
                    statusText.setTextColor(Color.WHITE)
                }
            }
        }.start()
    }

    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "⏱️ Verifying..."

        val fullProofBuilder = StringBuilder()
        for (i in 1..totalChunksExpected) {
            if (receivedChunks.containsKey(i)) {
                fullProofBuilder.append(receivedChunks[i])
            } else {
                return
            }
        }
        val fullProofString = fullProofBuilder.toString()
        
        // Save for Stress Test
        lastVerifiedProofString = fullProofString

        Thread {
            try {
                val ramBefore = getMemoryUsage()
                val resultReport = verifyProofFromRust(fullProofString)
                val ramAfter = getMemoryUsage()
                val ramPeak = if(ramAfter > ramBefore) ramAfter else ramBefore

                runOnUiThread {
                    if (resultReport.contains("Verified")) {
                        val finalMsg = "$resultReport\n💾 RAM: ${ramPeak}MB Used"
                        statusText.text = finalMsg
                        statusText.setTextColor(Color.GREEN)
                        progressBar.progressDrawable.setColorFilter(Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
                        
                        // Tip for user
                        Toast.makeText(this, "Long Press Text for Battery Test", Toast.LENGTH_LONG).show()
                    } else {
                        statusText.text = resultReport
                        statusText.setTextColor(Color.RED)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    statusText.text = "Error"
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); barcodeView.resume() }
    override fun onPause() { super.onPause(); barcodeView.pause() }
}