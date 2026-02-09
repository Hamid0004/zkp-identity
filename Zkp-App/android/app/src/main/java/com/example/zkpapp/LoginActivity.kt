package com.example.zkpapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import java.util.EnumMap

class LoginActivity : AppCompatActivity() {

    // 👇 JNI Connection (Rust Logic)
    companion object {
        init { System.loadLibrary("zkp_mobile") }
    }
    // Note: Rust function should return JSON Array of chunks
    external fun stringFromRust(): String

    // UI Elements
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 1. Initialize UI
        qrImage = findViewById(R.id.imgDynamicQr)
        statusText = findViewById(R.id.tvStatus)
        val btnGotoScanner: Button = findViewById(R.id.btnGotoScanner)

        // 🦁 2. SECURITY CHECK: Real Identity Exists?
        if (!IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ No Identity Found! Scan Passport First.", Toast.LENGTH_LONG).show()
            finish() // Close Activity if no ID
            return
        }

        // 3. Start Proof Generation (Animated QR)
        startProofGeneration()

        // 4. Button Logic: Switch to Receiver Mode
        btnGotoScanner.setOnClickListener {
            finish() // Close Sender
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }

    private fun startProofGeneration() {
        statusText.text = "⏳ Generating Real ID Proof..."
        statusText.setTextColor(Color.DKGRAY)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 🦁 CALL RUST (This uses Real ID inside Rust to generate chunks)
                val jsonResponse = stringFromRust() 
                
                withContext(Dispatchers.Main) {
                    // Check if Rust returned an error message instead of JSON
                    if (jsonResponse.startsWith("Error")) {
                        statusText.text = "❌ $jsonResponse"
                        statusText.setTextColor(Color.RED)
                    } else {
                        // Success: Parse JSON chunks
                        val jsonArray = JSONArray(jsonResponse)
                        val totalChunks = jsonArray.length()
                        
                        statusText.text = "🛡️ Real ID Verified!\nBroadcasting $totalChunks Frames..."
                        statusText.setTextColor(Color.parseColor("#2E7D32")) // Green
                        
                        // Start Animation Loop
                        playQrAnimation(jsonArray)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "🔥 Error: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }
    }

    // 🔄 ANIMATION ENGINE (Forward -> Reverse -> Random)
    private fun playQrAnimation(dataChunks: JSONArray) {
        val encoder = BarcodeEncoder()
        
        // QR Settings (Low Error Correction for Speed)
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L 
        hints[EncodeHintType.MARGIN] = 1 
        
        CoroutineScope(Dispatchers.Main).launch {
            val indices = (0 until dataChunks.length()).toMutableList()
            var loopCount = 0 
            
            while (isActive) { 
                // Strategy: Change sequence every loop to help scanner catch missed frames
                if (loopCount == 0) {
                    indices.sort() // Forward (1 -> End)
                } else if (loopCount == 1) {
                    indices.sortDescending() // Reverse (End -> 1)
                } else {
                    indices.shuffle() // Random (Cleanup)
                }

                for (i in indices) {
                    val chunkData = dataChunks.getString(i)
                    try {
                        val matrix = MultiFormatWriter().encode(
                            chunkData, BarcodeFormat.QR_CODE, 800, 800, hints 
                        )
                        val bitmap = encoder.createBitmap(matrix)
                        qrImage.setImageBitmap(bitmap)
                    } catch (e: Exception) { }

                    delay(110) // ⚡ Speed: 110ms per frame
                }
                loopCount++
                delay(100) // Small pause between loops
            }
        }
    }
}