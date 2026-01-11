package com.example.zkpapp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType // 👈 NEW IMPORT
import com.google.zxing.MultiFormatWriter // 👈 NEW IMPORT
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel // 👈 NEW IMPORT
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import java.util.EnumMap // 👈 NEW IMPORT

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            // Loading the Rust library
            System.loadLibrary("zkp_mobile")
        }
    }

    external fun stringFromRust(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView: TextView = findViewById(R.id.sample_text)
        val qrImageView: ImageView = findViewById(R.id.qr_image)
        val btnMagic: Button = findViewById(R.id.btn_magic)
        val btnScan: Button = findViewById(R.id.btn_scan)

        // 🟢 LOGIC 1: SENDER (Start Transmission)
        btnMagic.setOnClickListener {
            textView.text = "⏳ Generating Proof & Slicing..."
            
            CoroutineScope(Dispatchers.IO).launch {
                val jsonResponse = stringFromRust() 

                withContext(Dispatchers.Main) {
                    try {
                        val jsonArray = JSONArray(jsonResponse)
                        val totalChunks = jsonArray.length()
                        textView.text = "🎬 Stream: $totalChunks Frames"

                        playQrAnimation(jsonArray, qrImageView, textView)

                    } catch (e: Exception) {
                        textView.text = "❌ Error: ${e.message}"
                    }
                }
            }
        }

        // 🟠 LOGIC 2: RECEIVER (Scan & Verify)
        btnScan.setOnClickListener {
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }
    }

    // 👇 UPDATED: Optimized for High Density (750 Chars)
    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        // 🛠️ SETUP HINTS: Low Error Correction = Cleaner QR for Phone Screens
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L // 👈 KEY CHANGE
        hints[EncodeHintType.MARGIN] = 1 // Remove big white borders
        
        CoroutineScope(Dispatchers.Main).launch {
            val indices = (0 until dataChunks.length()).toMutableList()
            
            while (isActive) { 
                // 🔀 SHUFFLE
                indices.shuffle()

                for (i in indices) {
                    val chunkData = dataChunks.getString(i)
                    
                    try {
                        // 👇 USE MultiFormatWriter TO APPLY HINTS
                        val matrix = MultiFormatWriter().encode(
                            chunkData, 
                            BarcodeFormat.QR_CODE, 
                            800, // Resolution
                            800, 
                            hints // 👈 PASSING OPTIMIZATION HERE
                        )
                        val bitmap = encoder.createBitmap(matrix)
                        
                        imageView.setImageBitmap(bitmap)
                        statusView.text = "Broadcasting: Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) {
                        statusView.text = "⚠️ QR Error"
                    }

                    // ⚡ SPEED: 100ms (Fast Cycle)
                    delay(100) 
                }
                // Loop khatam hone par thoda sa saans lo
                delay(500) 
            }
        }
    }
}