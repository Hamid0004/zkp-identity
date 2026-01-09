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

    companion object {
        init {
            // 🛠️ FIX: Loading the UNIQUE name 'zkp_mobile'
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

        btnMagic.setOnClickListener {
            textView.text = "⏳ Slicing Data..."
            
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
    }

    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        CoroutineScope(Dispatchers.Main).launch {
            for (cycle in 1..5) { 
                for (i in 0 until dataChunks.length()) {
                    val chunkData = dataChunks.getString(i)
                    try {
                        val bitmap: Bitmap = encoder.encodeBitmap(chunkData, BarcodeFormat.QR_CODE, 600, 600)
                        imageView.setImageBitmap(bitmap)
                        statusView.text = "Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) { }
                    delay(150) 
                }
                delay(1000)
            }
            statusView.text = "✅ Done"
        }
    }
}
