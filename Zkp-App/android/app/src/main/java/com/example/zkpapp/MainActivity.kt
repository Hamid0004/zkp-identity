package com.example.zkpapp

import android.content.Intent // ✅ Correct Import
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

    // 👇 UPDATED: Slower (250ms) & Randomized Animation
    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        CoroutineScope(Dispatchers.Main).launch {
            // Hum indices (0 to 183) ki ek list banayenge
            val indices = (0 until dataChunks.length()).toMutableList()
            
            while (isActive) { 
                // 🔀 STEP 1: SHUFFLE (Har loop mein order change karo)
                // Isse "waiting time" kam lagega aur scanning natural lagegi
                indices.shuffle()

                for (i in indices) {
                    val chunkData = dataChunks.getString(i)
                    
                    try {
                        // QR Generate karo (Size 800x800 for better quality)
                        val bitmap: Bitmap = encoder.encodeBitmap(chunkData, BarcodeFormat.QR_CODE, 800, 800)
                        imageView.setImageBitmap(bitmap)
                        
                        // User ko batao kaunsa chunk chal raha hai
                        statusView.text = "Broadcasting: Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) {
                        statusView.text = "⚠️ QR Error"
                    }

                    // ⏱️ STEP 2: SLOW DOWN (250ms)
                    // Camera ko focus karne ka time milega
                    delay(250) 
                }
                // Loop khatam hone par thoda sa saans lo
                delay(500) 
            }
        }
    }
}