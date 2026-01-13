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

        // 🟢 LOGIC 1: REAL SENDER (Short Click)
        btnMagic.setOnClickListener {
            textView.text = "⏳ Generating Real Proof..."
            
            CoroutineScope(Dispatchers.IO).launch {
                val jsonResponse = stringFromRust() 

                withContext(Dispatchers.Main) {
                    try {
                        val jsonArray = JSONArray(jsonResponse)
                        val totalChunks = jsonArray.length()
                        textView.text = "🎬 Stream: $totalChunks Frames (Real)"

                        playQrAnimation(jsonArray, qrImageView, textView)

                    } catch (e: Exception) {
                        textView.text = "❌ Error: ${e.message}"
                    }
                }
            }
        }

        // ☠️ LOGIC 2: HACKER MODE (Long Press - 2 Seconds)
        btnMagic.setOnLongClickListener {
            textView.text = "⚠️ [TEST MODE] GENERATING MALICIOUS PAYLOAD..."
            textView.setTextColor(android.graphics.Color.RED)
            
            // 1. Create a Fake "Poisoned" Chunk
            val fakePayload = "1/1|ThisIsAFakeProofData_HackerWasHere_12345"
            val fakeJsonArray = JSONArray()
            fakeJsonArray.put(fakePayload)

            // 2. Broadcast Fake Data
            playQrAnimation(fakeJsonArray, qrImageView, textView)
            
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({ textView.setTextColor(android.graphics.Color.WHITE) }, 3000)
            
            true 
        }

        // 🟠 LOGIC 3: RECEIVER (Scan & Verify)
        btnScan.setOnClickListener {
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }
    }

    // 👇 UPDATED STRATEGY: Forward -> Reverse -> Random (Sandwich Strategy)
    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        // 🛠️ SETUP HINTS: Low Error Correction for Density
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L 
        hints[EncodeHintType.MARGIN] = 1 
        
        CoroutineScope(Dispatchers.Main).launch {
            val indices = (0 until dataChunks.length()).toMutableList()
            var loopCount = 0 // 🔢 Keep track of rounds
            
            while (isActive) { 
                
                if (loopCount == 0) {
                    // 🟢 ROUND 1: FORWARD (1 -> 129)
                    indices.sort() 
                    statusView.text = "🚀 Seq-Fwd (1 -> End)..."
                } else if (loopCount == 1) {
                    // 🔵 ROUND 2: REVERSE (129 -> 1) 👈 FIX FOR STUCK FRAMES
                    // Jo frames end mein miss huye (127, 128), wo ab sabse pehle aayenge.
                    indices.sortDescending()
                    statusView.text = "↩️ Seq-Rev (End -> 1)..."
                } else {
                    // 🔀 ROUND 3+: RANDOM (Shuffle)
                    indices.shuffle()
                    statusView.text = "🔀 Random Shuffle..."
                }

                for (i in indices) {
                    val chunkData = dataChunks.getString(i)
                    
                    try {
                        val matrix = MultiFormatWriter().encode(
                            chunkData, 
                            BarcodeFormat.QR_CODE, 
                            800, 
                            800, 
                            hints 
                        )
                        val bitmap = encoder.createBitmap(matrix)
                        imageView.setImageBitmap(bitmap)
                        
                        val mode = when(loopCount) {
                            0 -> "Fwd"
                            1 -> "Rev"
                            else -> "Rnd"
                        }
                        statusView.text = "[$mode] Chunk ${i + 1} / ${dataChunks.length()}"
                    } catch (e: Exception) {
                        statusView.text = "⚠️ QR Error"
                    }

                    // ⏱️ TIMING: 110ms (Slightly faster to catch reverse quickly)
                    delay(110) 
                }
                
                loopCount++
                delay(100) 
            }
        }
    }
}