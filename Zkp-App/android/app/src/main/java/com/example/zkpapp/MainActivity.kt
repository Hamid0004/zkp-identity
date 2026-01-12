package com.example.zkpapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
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

    // 👇 Returns Report String (Verified + Time + RAM info from Rust logs if added)
    external fun verifyProofFromRust(proof: String): String

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier)

        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
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

            // 🔄 SMART SESSION RESET LOGIC 🧠
            // Check: Agar Chunk 1 aaya hai, toh kya yeh naya proof hai?
            if (currentIndex == 1 && receivedChunks.containsKey(1)) {
                val oldPayload = receivedChunks[1]
                
                // Agar Payload different hai, toh matlab Naya Proof hai -> Reset
                if (oldPayload != payload) {
                    receivedChunks.clear()
                    totalChunksExpected = -1
                    runOnUiThread {
                        statusText.text = "🔄 New Session Detected..."
                        statusText.setTextColor(Color.WHITE)
                        progressBar.progress = 0
                    }
                }
                // Agar Payload same hai, toh ignore karo (Sender loop repeat kar raha hai)
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

    // 👇 RAM Usage Calculator
    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemInBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedMemInBytes / (1024 * 1024) // Convert to MB
    }

    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "⏱️ Verifying..."

        val fullProofBuilder = StringBuilder()
        for (i in 1..totalChunksExpected) {
            if (receivedChunks.containsKey(i)) {
                fullProofBuilder.append(receivedChunks[i])
            } else {
                statusText.text = "❌ Error: Missing Chunk #$i"
                return
            }
        }
        val fullProofString = fullProofBuilder.toString()

        Thread {
            try {
                // Measure RAM Before
                val ramBefore = getMemoryUsage()

                // Call Rust
                val resultReport = verifyProofFromRust(fullProofString)
                
                // Measure RAM After
                val ramAfter = getMemoryUsage()
                val ramPeak = if(ramAfter > ramBefore) ramAfter else ramBefore

                runOnUiThread {
                    if (resultReport.contains("Verified")) {
                        val finalMsg = "$resultReport\n💾 RAM: ${ramPeak}MB Used"
                        statusText.text = finalMsg
                        statusText.setTextColor(Color.GREEN)
                        progressBar.progressDrawable.setColorFilter(Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        statusText.text = resultReport
                        statusText.setTextColor(Color.RED)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    statusText.text = "💥 ERROR: ${e.message}"
                    statusText.setTextColor(Color.YELLOW)
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); barcodeView.resume() }
    override fun onPause() { super.onPause(); barcodeView.pause() }
}}