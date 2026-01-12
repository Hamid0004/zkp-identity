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

    // 👇 1. RUST CONNECTION
    companion object {
        init {
            System.loadLibrary("zkp_mobile") 
        }
    }

    // 👇 UPDATE: Return type is now STRING (Contains "Verified" + Timings)
    external fun verifyProofFromRust(proof: String): String

    // UI Variables
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // Logic Variables
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

            // 🔄 SESSION RESET LOGIC (From Day 45) - KEEP THIS!
            if (currentIndex == 1 && receivedChunks.size > 1) {
                receivedChunks.clear()
                totalChunksExpected = -1
                runOnUiThread {
                    statusText.text = "🔄 New Session Detected..."
                    statusText.setTextColor(Color.WHITE)
                    progressBar.progress = 0
                }
            }

            // Normal Setup
            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            // Save Chunk
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

    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "⏱️ Verifying..."

        // A. REASSEMBLE
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

        // B. SEND TO RUST
        Thread {
            try {
                // 👇 CALL RUST (Returns detailed Report String)
                val resultReport = verifyProofFromRust(fullProofString)

                runOnUiThread {
                    // Agar report mein "Verified" hai, toh Green karo
                    if (resultReport.contains("Verified")) {
                        statusText.text = resultReport // 👈 SHOW BENCHMARK DATA
                        statusText.setTextColor(Color.GREEN)
                        progressBar.progressDrawable.setColorFilter(Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        // Fail
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
}