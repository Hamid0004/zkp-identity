package com.example.zkpapp

import android.content.Intent // 👈 Yeh import zaroori hai nayi activity ke liye
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray

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
        val btnScan: Button = findViewById(R.id.btn_scan) // 👈 Getting the new button

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

        // 🟠 LOGIC 2: RECEIVER (Scan & Verify) - YEH MISSING THA
        btnScan.setOnClickListener {
            // Nayi Screen (VerifierActivity) kholo
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }
    }

    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        CoroutineScope(Dispatchers.Main).launch {
            // Infinite loop for demo purposes (User can stop by pressing Back)
            while (isActive) { 
                for (i in 0 until dataChunks.length()) {
                    
                    val chunkData = dataChunks.getString(i)
                    
                    try {
                        val bitmap: Bitmap = encoder.encodeBitmap(chunkData, BarcodeFormat.QR_CODE, 600, 600)
                        imageView.setImageBitmap(bitmap)
                        statusView.text = "Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) {
                        statusView.text = "⚠️ QR Error"
                    }

                    delay(100) // Fast speed (100ms) for video effect
                }
                delay(1000) // Pause before restarting loop
            }
        }
    }
}
