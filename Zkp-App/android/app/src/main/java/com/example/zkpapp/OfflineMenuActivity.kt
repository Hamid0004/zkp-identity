package com.example.zkpapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import java.util.EnumMap

class OfflineMenuActivity : AppCompatActivity() {

    companion object {
        init {
            try {
                System.loadLibrary("zkp_mobile")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("ZKP_LION", "Failed to load Rust Library", e)
            }
        }
        private const val QR_SIZE = 800
        private const val FRAME_DELAY_MS = 150L // 🦁 Speed of chunks
    }

    external fun stringFromRust(): String 

    private lateinit var imgQr: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvFrameCounter: TextView // 🦁 New Counter Text
    private lateinit var loader: ProgressBar
    private lateinit var btnTransmit: Button
    private lateinit var btnVerifyOffline: Button
    
    private var animationJob: Job? = null
    private var proofGenerationJob: Job? = null
    private var isTransmitting = false
    private var isGeneratingProof = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_menu)

        // Init UI
        imgQr = findViewById(R.id.imgOfflineQr)
        tvStatus = findViewById(R.id.tvQrStatus)
        tvFrameCounter = findViewById(R.id.tvFrameCounter) // 🦁 Bind View
        loader = findViewById(R.id.loader)
        btnTransmit = findViewById(R.id.btnTransmit)
        btnVerifyOffline = findViewById(R.id.btnVerifyOffline)

        resetUI()

        btnTransmit.setOnClickListener {
            if (!isTransmitting && !isGeneratingProof) {
                startTransmission()
            } else {
                stopTransmission()
            }
        }

        btnVerifyOffline.setOnClickListener {
            stopTransmission()
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }

    // 🦁 START LOGIC
    private fun startTransmission() {
        isGeneratingProof = true
        updateUIForComputing()
        
        proofGenerationJob = lifecycleScope.launch {
            try {
                val jsonResponse = withContext(Dispatchers.IO) { 
                    stringFromRust() 
                }
                
                withContext(Dispatchers.Main) {
                    handleProofResponse(jsonResponse)
                    isGeneratingProof = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isGeneratingProof = false
                    showError("Error: ${e.message}")
                }
            }
        }
    }

    private fun handleProofResponse(response: String) {
        try {
            val jsonArray = JSONArray(response)
            if (jsonArray.length() == 0) {
                showError("Empty proof data")
                return
            }
            
            isTransmitting = true
            updateUIForTransmitting()
            startQrAnimation(jsonArray) // 🦁 Start Loop
            
        } catch (e: Exception) {
            showError("Invalid JSON Data")
        }
    }

    // 🦁 ANIMATION LOOP WITH COUNTER
    private fun startQrAnimation(dataChunks: JSONArray) {
        stopAnimation()
        
        animationJob = lifecycleScope.launch(Dispatchers.Default) {
            val encoder = BarcodeEncoder()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
                put(EncodeHintType.MARGIN, 1)
            }
            val writer = MultiFormatWriter()
            val totalFrames = dataChunks.length()
            val indices = (0 until totalFrames).toMutableList()

            // 🦁 Codespace Emulator ke liye Loop
            while (isActive && isTransmitting) {
                indices.shuffle() // Security Shuffle (RND)
                
                for (i in indices) {
                    if (!isActive || !isTransmitting) break
                    
                    try {
                        val chunkData = dataChunks.getString(i)
                        
                        // 1. Generate Bitmap
                        val matrix = writer.encode(chunkData, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
                        val bitmap = encoder.createBitmap(matrix)
                        
                        // 2. Update UI (Image + Counter)
                        withContext(Dispatchers.Main) { 
                            imgQr.setImageBitmap(bitmap)
                            
                            // 🦁 SHOW COUNTER (e.g., "CHUNK: 3/4")
                            // i+1 kyunki index 0 se shuru hota hai
                            tvFrameCounter.text = "CHUNK: ${i + 1} / $totalFrames"
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    
                    delay(FRAME_DELAY_MS)
                }
            }
        }
    }

    private fun stopTransmission() {
        isTransmitting = false
        isGeneratingProof = false
        cleanupResources()
        resetUI()
    }

    private fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    private fun cleanupResources() {
        stopAnimation()
        proofGenerationJob?.cancel()
        proofGenerationJob = null
    }

    // 🦁 UI STATE UPDATES
    private fun updateUIForComputing() {
        btnTransmit.text = "⏳ COMPUTING..."
        btnTransmit.setBackgroundColor(Color.parseColor("#FF6F00")) 
        btnTransmit.isEnabled = false 
        
        tvStatus.text = "🦁 Computing Proof..."
        tvStatus.setTextColor(Color.WHITE)
        
        tvFrameCounter.visibility = View.INVISIBLE // Hide counter
        loader.visibility = View.VISIBLE
        imgQr.setColorFilter(Color.DKGRAY)
    }
    
    private fun updateUIForTransmitting() {
        btnTransmit.text = "⏹ STOP BROADCAST"
        btnTransmit.setBackgroundColor(Color.parseColor("#D32F2F"))
        btnTransmit.isEnabled = true
        
        tvStatus.text = "📡 Broadcasting Identity..."
        tvStatus.setTextColor(Color.parseColor("#00E676"))
        
        tvFrameCounter.visibility = View.VISIBLE // Show counter
        loader.visibility = View.GONE
        imgQr.clearColorFilter()
    }
    
    private fun resetUI() {
        btnTransmit.text = "📡 TRANSMIT"
        btnTransmit.setBackgroundColor(Color.parseColor("#2E7D32"))
        btnTransmit.isEnabled = true
        
        tvStatus.text = "Ready to Transmit"
        tvStatus.setTextColor(Color.LTGRAY)
        
        tvFrameCounter.visibility = View.INVISIBLE // Hide counter
        loader.visibility = View.GONE
        imgQr.clearColorFilter()
        imgQr.setImageResource(android.R.drawable.ic_menu_gallery)
        imgQr.setColorFilter(Color.DKGRAY)
    }
    
    private fun showError(message: String) {
        stopTransmission()
        tvStatus.text = "❌ $message"
        tvStatus.setTextColor(Color.RED)
        lifecycleScope.launch { delay(3000); if (!isTransmitting) resetUI() }
    }
}