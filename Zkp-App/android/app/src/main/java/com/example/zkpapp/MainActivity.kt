package com.example.zkpapp

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

    private lateinit var binding: ActivityMainBinding // View Binding agar use kar rahe ho, warna direct IDs
    
    // Rust Library Load karna
    companion object {
        init {
            System.loadLibrary("rust")
        }
    }

    external fn stringFromRust(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView: TextView = findViewById(R.id.sample_text)
        val qrImageView: ImageView = findViewById(R.id.qr_image)
        val btnMagic: Button = findViewById(R.id.btn_magic)

        btnMagic.setOnClickListener {
            textView.text = "⏳ Generating Proof & Slicing..."
            
            // Background Thread par heavy kaam
            CoroutineScope(Dispatchers.IO).launch {
                val jsonResponse = stringFromRust() // 1. Get JSON from Rust

                withContext(Dispatchers.Main) {
                    try {
                        // 2. Parse JSON String to List
                        val jsonArray = JSONArray(jsonResponse)
                        val totalChunks = jsonArray.length()
                        textView.text = "🎬 Starting Stream: $totalChunks Frames"

                        // 3. START THE CINEMA (Animation Loop) 📽️
                        playQrAnimation(jsonArray, qrImageView, textView)

                    } catch (e: Exception) {
                        textView.text = "❌ Error Parsing Data: ${e.message}"
                    }
                }
            }
        }
    }

    // 🔄 Animation Function (Recursive Loop)
    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        CoroutineScope(Dispatchers.Main).launch {
            // Hum infinite loop lagayenge taaki user scan kar sake
            // (Asli app mein hum User ke 'Stop' button ka wait karenge)
            for (cycle in 1..5) { // 5 baar repeat karega session
                for (i in 0 until dataChunks.length()) {
                    
                    val chunkData = dataChunks.getString(i) // Get String: "1/184|..."
                    
                    // Generate QR Bitmap
                    try {
                        val bitmap: Bitmap = encoder.encodeBitmap(chunkData, BarcodeFormat.QR_CODE, 600, 600)
                        imageView.setImageBitmap(bitmap)
                        statusView.text = "📡 Transmitting: Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) {
                        statusView.text = "⚠️ QR Error"
                    }

                    // ⏱️ Frame Rate (Speed of Video)
                    // 200ms = 5 Frames per Second (Safe for Camera)
                    delay(150) 
                }
                delay(1000) // Har cycle ke baad 1 sec ka break
            }
            statusView.text = "✅ Transmission Complete"
        }
    }
}
