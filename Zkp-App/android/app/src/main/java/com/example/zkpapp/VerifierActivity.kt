cd /workspaces/Zkp-learning-/Zkp-App/android/app/src/main/java/com/example/zkpapp

cat <<EOF > VerifierActivity.kt
package com.example.zkpapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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

    // 👇 1. RUST CONNECTION
    companion object {
        init {
            // ✅ FIX: Quotes added around the name!
            System.loadLibrary("zkp_mobile") 
        }
    }
    
    // Rust function declaration
    external fun verifyProofFromRust(proof: String): Boolean

    // UI Variables
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // Logic Variables
    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier) // Creates the UI

        // Link UI components
        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        // Request Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }

        startScanning()
    }

    private fun startScanning() {
        // Continuous decoding (scanning)
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
            // Expected Format: "1/184|Base64Data..."
            if (!data.contains("|") || !data.contains("/")) return

            val parts = data.split("|", limit = 2)
            val header = parts[0] 
            val payload = parts[1] 

            val headerParts = header.split("/")
            val currentIndex = headerParts[0].toInt()
            val total = headerParts[1].toInt()

            // Setup Progress Bar on first valid scan
            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            // Store Data if new
            if (!receivedChunks.containsKey(currentIndex)) {
                receivedChunks[currentIndex] = payload

                runOnUiThread {
                    statusText.text = "Caught: \${receivedChunks.size} / \$totalChunksExpected"
                    progressBar.progress = receivedChunks.size

                    // Trigger finish when all chunks are collected
                    if (receivedChunks.size == totalChunksExpected) {
                        finishScanning() 
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted scans
        }
    }

    // 👇 2. LOGIC (Debugging + Rust Call)
    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "🧩 Stitching & Verifying..."

        // A. REASSEMBLE
        val fullProofBuilder = StringBuilder()
        for (i in 1..totalChunksExpected) {
            if (receivedChunks.containsKey(i)) {
                fullProofBuilder.append(receivedChunks[i])
            } else {
                statusText.text = "❌ Error: Missing Chunk #\$i"
                return
            }
        }
        val fullProofString = fullProofBuilder.toString()

        println("Sending to Rust: Size = \${fullProofString.length}")

        // B. SEND TO RUST (Background Thread)
        Thread {
            val isValid = verifyProofFromRust(fullProofString)

            runOnUiThread {
                if (isValid) {
                    // 🎉 SUCCESS
                    statusText.text = "✅ VERIFIED!\nProof is Valid."
                    statusText.setTextColor(Color.GREEN)
                    progressBar.progressDrawable.setColorFilter(Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    // ❌ FAILURE
                    statusText.text = "⛔ INVALID!\nFake Proof Detected."
                    statusText.setTextColor(Color.RED)
                    progressBar.progressDrawable.setColorFilter(Color.RED, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); barcodeView.resume() }
    override fun onPause() { super.onPause(); barcodeView.pause() }
}
EOF