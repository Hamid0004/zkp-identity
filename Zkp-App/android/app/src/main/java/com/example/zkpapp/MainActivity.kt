package com.example.zkpapp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import java.util.EnumMap

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

    // 👇 UPDATED: Hybrid Strategy (Sequential First -> Then Random)
    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        // 🛠️ SETUP HINTS: Low Error Correction = Cleaner QR
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L 
        hints[EncodeHintType.MARGIN] = 1 
        
        CoroutineScope(Dispatchers.Main).launch {
            val indices = (0 until dataChunks.length()).toMutableList()
            var isFirstLoop = true // 🚩 FLAG: Check if it's the first run
            
            while (isActive) { 
                
                if (isFirstLoop) {
                    // 🟢 ROUND 1: SEQUENTIAL (Line se chalo 1...129)
                    // Isse guarantee milti hai ke har frame kam az kam ek baar screen par aayega.
                    indices.sort() 
                    isFirstLoop = false
                    statusView.text = "🚀 Broadcasting: Initial Sequence..."
                } else {
                    // 🔀 ROUND 2+: RANDOM (Shuffle)
                    // Jo miss ho gaye, unhein pakdne ke liye.
                    indices.shuffle()
                }

                for (i in indices) {
                    val chunkData = dataChunks.getString(i)
                    
                    try {
                        // 👇 Generate High Density QR
                        val matrix = MultiFormatWriter().encode(
                            chunkData, 
                            BarcodeFormat.QR_CODE, 
                            800, 
                            800, 
                            hints 
                        )
                        val bitmap = encoder.createBitmap(matrix)
                        imageView.setImageBitmap(bitmap)
                        
                        // Status Update
                        val mode = if (isFirstLoop) "Seq" else "Rnd"
                        statusView.text = "[$mode] Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) {
                        statusView.text = "⚠️ QR Error"
                    }

                    // ⏱️ TIMING: 130ms (Better Focus)
                    delay(130) 
                }
                delay(200) 
            }
        }
    }
}