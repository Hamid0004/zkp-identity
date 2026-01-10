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

    // ✅ FIXED: Correct native library name
    companion object {
        init {
            System.loadLibrary(""rust_layer"")
        }
    }

    // Rust JNI function
    external fun verifyProofFromRust(proof: String): Boolean

    // UI
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // Logic
    private val receivedChunks = HashMap<Int, String>()
    private var totalChunksExpected = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verifier)

        barcodeView = findViewById(R.id.barcode_scanner)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        // Camera permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                1
            )
        }

        startScanning()
    }

    private fun startScanning() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { processQrData(it) }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    private fun processQrData(data: String) {
        try {
            // Expected: "1/184|BASE64..."
            if (!data.contains("|") || !data.contains("/")) return

            val (header, payload) = data.split("|", limit = 2)
            val (indexStr, totalStr) = header.split("/")

            val index = indexStr.toInt()
            val total = totalStr.toInt()

            if (totalChunksExpected == -1) {
                totalChunksExpected = total
                progressBar.max = total
            }

            if (!receivedChunks.containsKey(index)) {
                receivedChunks[index] = payload

                runOnUiThread {
                    statusText.text =
                        "Caught: ${receivedChunks.size} / $totalChunksExpected"
                    progressBar.progress = receivedChunks.size

                    if (receivedChunks.size == totalChunksExpected) {
                        finishScanning()
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore corrupted frames
        }
    }

    private fun finishScanning() {
        barcodeView.pause()
        statusText.text = "🧩 Stitching & Verifying..."

        val proofBuilder = StringBuilder()
        for (i in 1..totalChunksExpected) {
            val chunk = receivedChunks[i]
                ?: run {
                    statusText.text = "❌ Missing chunk $i"
                    return
                }
            proofBuilder.append(chunk)
        }

        val fullProof = proofBuilder.toString()

        Thread {
            val isValid = verifyProofFromRust(fullProof)

            runOnUiThread {
                if (isValid) {
                    statusText.text = "✅ VERIFIED\nProof is valid"
                    statusText.setTextColor(Color.GREEN)
                } else {
                    statusText.text = "⛔ INVALID\nFake proof"
                    statusText.setTextColor(Color.RED)
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
