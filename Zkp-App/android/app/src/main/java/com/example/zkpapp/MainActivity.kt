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
            System.loadLibrary("zkp_mobile")
        }
    }

    external fun stringFromRust(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize Views
        val textView: TextView = findViewById(R.id.sample_text)
        val qrImageView: ImageView = findViewById(R.id.qr_image)
        val btnMagic: Button = findViewById(R.id.btn_magic) // QR Sender
        val btnScan: Button = findViewById(R.id.btn_scan)   // QR Receiver
        
        // 🆕 NEW: PASSPORT BUTTON (Phase 6)
        val btnPassport: Button = findViewById(R.id.btn_scan_passport) 

        // ---------------------------------------------------------
        // 🟢 LOGIC 1: QR SENDER (Your Sandwich Strategy)
        // ---------------------------------------------------------
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

        // ☠️ LOGIC 2: HACKER MODE (Long Press)
        btnMagic.setOnLongClickListener {
            textView.text = "⚠️ [TEST MODE] GENERATING MALICIOUS PAYLOAD..."
            textView.setTextColor(android.graphics.Color.RED)
            
            val fakePayload = "1/1|ThisIsAFakeProofData_HackerWasHere_12345"
            val fakeJsonArray = JSONArray()
            fakeJsonArray.put(fakePayload)

            playQrAnimation(fakeJsonArray, qrImageView, textView)
            
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({ textView.setTextColor(android.graphics.Color.WHITE) }, 3000)
            
            true 
        }

        // ---------------------------------------------------------
        // 🟠 LOGIC 3: QR RECEIVER (Scan & Verify)
        // ---------------------------------------------------------
        btnScan.setOnClickListener {
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }

        // ---------------------------------------------------------
        // 🛂 LOGIC 4: PASSPORT SCANNER (New for Day 62)
        // ---------------------------------------------------------
        btnPassport.setOnClickListener {
            // Opens the new PassportActivity we just created
            val intent = Intent(this, PassportActivity::class.java)
            startActivity(intent)
        }
    }

    // 👇 YOUR EXISTING ANIMATION LOGIC (UNCHANGED)
    private fun playQrAnimation(dataChunks: JSONArray, imageView: ImageView, statusView: TextView) {
        val encoder = BarcodeEncoder()
        
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L 
        hints[EncodeHintType.MARGIN] = 1 
        
        CoroutineScope(Dispatchers.Main).launch {
            val indices = (0 until dataChunks.length()).toMutableList()
            var loopCount = 0 
            
            while (isActive) { 
                
                if (loopCount == 0) {
                    indices.sort() 
                    statusView.text = "🚀 Seq-Fwd (1 -> End)..."
                } else if (loopCount == 1) {
                    indices.sortDescending()
                    statusView.text = "↩️ Seq-Rev (End -> 1)..."
                } else {
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

                    delay(110) 
                }
                
                loopCount++
                delay(100) 
            }
        }
    }
}