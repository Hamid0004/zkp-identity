package com.example.zkpapp

import android.Manifest
import android.content.pm.PackageManager
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

    // 👇 1. RUST CONNECTION (Yeh naya hai, isay zaroor add karein)
    companion object {
        init {
            System.loadLibrary("zkp_mobile") // Library load ki
        }
    }
    // Rust function declare kiya (Jo verify karega)
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
            // Expected Format: "1/184|Base64Data..."
            if (!data.contains("|") || !data.contains("/")) return

            val parts = data.split("|", limit = 2)
            val header = parts[0] 
            val payload = parts[1] 

            val headerParts = header.split("/")
            val currentIndex = headerParts[0].toInt()
            val total = headerParts[1].toInt()

            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            if (!receivedChunks.containsKey(currentIndex)) {
                receivedChunks[currentIndex] = payload
                
                runOnUiThread {
                    statusText.text = "Caught: ${receivedChunks.size} / $totalChunksExpected"
                    progressBar.progress = receivedChunks.size
                    
                    // Jab saare tukde mil jayen, tab finish call karo
                    if (receivedChunks.size == totalChunksExpected) {
                        finishScanning() 
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore bad data
        }
    }

    // 👇 2. UPDATED LOGIC (Aapka sawal yahan hai)
    // Is function ko replace kiya gaya hai Rust Logic ke saath
    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "🧩 Stitching & Verifying..."
        
        // A. REASSEMBLE (Tukdon ko jodna)
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

        // B. SEND TO RUST (Background Thread par)
        Thread {
            // Rust ko call kiya
            val isValid = verifyProofFromRust(fullProofString)
            
            runOnUiThread {
                if (isValid) {
                    // 🎉 SUCCESS UI
                    statusText.text = "✅ VERIFIED!\nProof is Valid."
                    statusText.setTextColor(android.graphics.Color.GREEN)
                    // Progress bar ko Green kar diya
                    progressBar.progressDrawable.setColorFilter(android.graphics.Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    // ❌ FAILURE UI
                    statusText.text = "⛔ INVALID!\nFake Proof Detected."
                    statusText.setTextColor(android.graphics.Color.RED)
                }
            }
        }.start()
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