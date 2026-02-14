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
        // 🦁 Speed Update: 100ms is better for Fountain Strategy (Faster sweeps)
        private const val FRAME_DELAY_MS = 100L 
    }

    external fun stringFromRust(): String 

    private lateinit var imgQr: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvFrameCounter: TextView
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

        imgQr = findViewById(R.id.imgOfflineQr)
        tvStatus = findViewById(R.id.tvQrStatus)
        tvFrameCounter = findViewById(R.id.tvFrameCounter)
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
                    showError("Rust Error: ${e.message}")
                }
            }
        }
    }

    private fun handleProofResponse(response: String) {
        try {
            val jsonArray = JSONArray(response)
            if (jsonArray.length() == 0) {
                showError("Empty Proof Data")
                return
            }
            
            isTransmitting = true
            updateUIForTransmitting()
            startQrAnimation(jsonArray)
            
        } catch (e: Exception) {
            showError("JSON Error: ${e.message}")
        }
    }

    // 🦁 NEW: FOUNTAIN STRATEGY (FWD -> RWD -> RND)
    private fun startQrAnimation(dataChunks: JSONArray) {
        stopAnimation()
        
        animationJob = lifecycleScope.launch(Dispatchers.Default) {
            val encoder = BarcodeEncoder()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
                put(EncodeHintType.MARGIN, 2)
            }
            val writer = MultiFormatWriter()
            val totalFrames = dataChunks.length()

            // Helper function to render frame (Avoids code duplication)
            suspend fun renderFrame(index: Int, modeLabel: String) {
                try {
                    val chunkData = dataChunks.getString(index)
                    val matrix = writer.encode(chunkData, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
                    val bitmap = encoder.createBitmap(matrix)
                    
                    withContext(Dispatchers.Main) { 
                        if (isTransmitting) {
                            imgQr.clearColorFilter()
                            imgQr.imageTintList = null
                            imgQr.setImageBitmap(bitmap)
                            
                            // 🦁 Dynamic Label: "FWD: 1 / 129"
                            tvFrameCounter.text = "$modeLabel: ${index + 1} / $totalFrames"
                            
                            // Optional: Color coding for modes
                            when(modeLabel) {
                                "FWD" -> tvFrameCounter.setTextColor(Color.parseColor("#00E676")) // Green
                                "RWD" -> tvFrameCounter.setTextColor(Color.parseColor("#FF9800")) // Orange
                                "RND" -> tvFrameCounter.setTextColor(Color.parseColor("#2979FF")) // Blue
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                delay(FRAME_DELAY_MS)
            }

            // 🦁 THE MAIN LOOP
            while (isActive && isTransmitting) {
                
                // 1️⃣ PHASE 1: FORWARD (1 -> End)
                for (i in 0 until totalFrames) {
                    if (!isActive || !isTransmitting) break
                    renderFrame(i, "FWD")
                }

                // 2️⃣ PHASE 2: REVERSE (End -> 1)
                for (i in totalFrames - 1 downTo 0) {
                    if (!isActive || !isTransmitting) break
                    renderFrame(i, "RWD")
                }

                // 3️⃣ PHASE 3: RANDOM BURST (Shuffle)
                // Hum total frames jitna hi random chalayenge taaki sab cover ho jaye
                val randomIndices = (0 until totalFrames).toMutableList()
                randomIndices.shuffle()
                
                for (i in randomIndices) {
                    if (!isActive || !isTransmitting) break
                    renderFrame(i, "RND")
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

    // UI UPDATES
    private fun updateUIForComputing() {
        btnTransmit.text = "⏳ COMPUTING..."
        btnTransmit.setBackgroundColor(Color.parseColor("#FF6F00")) 
        btnTransmit.isEnabled = false 
        
        tvStatus.text = "🦁 Computing Proof..."
        tvStatus.setTextColor(Color.WHITE)
        
        tvFrameCounter.visibility = View.INVISIBLE
        loader.visibility = View.VISIBLE
        imgQr.setColorFilter(Color.DKGRAY) 
    }
    
    private fun updateUIForTransmitting() {
        btnTransmit.text = "⏹ STOP BROADCAST"
        btnTransmit.setBackgroundColor(Color.parseColor("#D32F2F"))
        btnTransmit.isEnabled = true
        
        tvStatus.text = "📡 Broadcasting Identity..."
        tvStatus.setTextColor(Color.parseColor("#00E676"))
        
        tvFrameCounter.visibility = View.VISIBLE
        loader.visibility = View.GONE
        
        imgQr.clearColorFilter()
        imgQr.imageTintList = null
        imgQr.setBackgroundColor(Color.WHITE) 
    }
    
    private fun resetUI() {
        btnTransmit.text = "📡 TRANSMIT"
        btnTransmit.setBackgroundColor(Color.parseColor("#2E7D32"))
        btnTransmit.isEnabled = true
        
        tvStatus.text = "Ready to Transmit"
        tvStatus.setTextColor(Color.LTGRAY)
        
        tvFrameCounter.visibility = View.INVISIBLE
        loader.visibility = View.GONE
        
        imgQr.setImageResource(android.R.drawable.ic_menu_gallery)
        imgQr.setColorFilter(Color.DKGRAY)
        imgQr.imageTintList = null
        imgQr.setBackgroundColor(Color.TRANSPARENT)
    }
    
    private fun showError(message: String) {
        stopTransmission()
        tvStatus.text = "❌ $message"
        tvStatus.setTextColor(Color.RED)
        lifecycleScope.launch { delay(4000); if (!isTransmitting) resetUI() }
    }
}