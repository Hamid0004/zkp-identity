package com.example.zkpapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    
    // 🧠 THE BRAIN: Data Collection
    // Hum ek Map use karenge taaki duplicate chunks save na hon
    // Format: Index -> Data String
    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1 // Abhi humein nahi pata total kitne hain

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // UI Setup programmatically (Simple rakhne ke liye XML nahi bana rahe)
        setContentView(R.layout.activity_verifier) // ⚠️ Note: Iska XML hum Step 4 mein banayenge

        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        // Camera Permission Request
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }

        // 👁️ START CONTINUOUS SCANNING
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

    // 🧩 STITCHING LOGIC
    private fun processQrData(data: String) {
        try {
            // Expected Format: "1/184|Base64Data..."
            if (!data.contains("|") || !data.contains("/")) return

            val parts = data.split("|", limit = 2)
            val header = parts[0] // "1/184"
            val payload = parts[1] // "Base64..."

            val headerParts = header.split("/")
            val currentIndex = headerParts[0].toInt()
            val total = headerParts[1].toInt()

            // First time setup
            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            // Save Chunk (Agar pehle se nahi hai)
            if (!receivedChunks.containsKey(currentIndex)) {
                receivedChunks[currentIndex] = payload
                
                // Update UI
                runOnUiThread {
                    statusText.text = "Caught: ${receivedChunks.size} / $totalChunksExpected"
                    progressBar.progress = receivedChunks.size
                    
                    // ✅ VICTORY CHECK
                    if (receivedChunks.size == totalChunksExpected) {
                        finishScanning()
                    }
                }
            }

        } catch (e: Exception) {
            // Bad QR code, ignore
        }
    }

    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "🎉 COMPLETE! Reassembling Monster..."
        
        // TODO: Kal hum is poore data ko Rust bhej kar Verify karenge
        // Aaj ke liye bas Toast dikhate hain
        Toast.makeText(this, "All 184 Chunks Collected!", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }
}