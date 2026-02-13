package com.example.zkpapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
                e.printStackTrace()
            }
        }
        private const val QR_SIZE = 800
        private const val FRAME_DELAY_MS = 150L
    }

    // 🦁 Rust JNI Function (Make sure Rust side matches this name!)
    external fun stringFromRust(): String 

    private lateinit var imgQr: ImageView
    private lateinit var tvStatus: TextView
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

        // Initialize UI Elements
        imgQr = findViewById(R.id.imgOfflineQr)
        tvStatus = findViewById(R.id.tvQrStatus)
        loader = findViewById(R.id.loader)
        btnTransmit = findViewById(R.id.btnTransmit)
        btnVerifyOffline = findViewById(R.id.btnVerifyOffline)

        // Set initial UI state
        resetUI()

        // 🦁 TRANSMIT BUTTON - Toggle between Start/Stop
        btnTransmit.setOnClickListener {
            if (!isTransmitting && !isGeneratingProof) {
                startTransmission()
            } else {
                stopTransmission()
            }
        }

        // 🦁 VERIFY BUTTON - Opens Camera Scanner
        btnVerifyOffline.setOnClickListener {
            stopTransmission()
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        if (isTransmitting) {
            stopAnimation()
        }
    }

    override fun onResume() {
        super.onResume()
        // Agar pehle transmit ho raha tha aur user wapis aaya, to restart logic yahan lag sakti hai.
        // Filhal hum clean state rakhte hain taaki confusion na ho.
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }

    // 🦁 START TRANSMISSION Logic
    private fun startTransmission() {
        isGeneratingProof = true
        updateUIForComputing()
        
        proofGenerationJob = lifecycleScope.launch {
            try {
                // Background Thread: Generate Proof
                val jsonResponse = withContext(Dispatchers.IO) { 
                    stringFromRust() 
                }
                
                // Main Thread: Update UI
                handleProofResponse(jsonResponse)
                isGeneratingProof = false
            } catch (e: Exception) {
                isGeneratingProof = false
                showError("Error: ${e.message ?: "Rust JNI Failure"}")
            }
        }
    }

    // 🦁 HANDLE RESPONSE
    private fun handleProofResponse(response: String) {
        try {
            val jsonArray = JSONArray(response)
            
            if (jsonArray.length() == 0) {
                showError("Empty proof data")
                return
            }
            
            isTransmitting = true
            updateUIForTransmitting()
            startQrAnimation(jsonArray)
            
        } catch (e: Exception) {
            showError("Invalid Data: ${e.message}")
        }
    }

    // 🦁 ANIMATION LOOP
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

            while (isActive && isTransmitting) {
                indices.shuffle() // Security Shuffle
                
                for (i in indices) {
                    if (!isActive || !isTransmitting) break
                    
                    try {
                        val matrix = writer.encode(dataChunks.getString(i), BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
                        val bitmap = encoder.createBitmap(matrix)
                        
                        withContext(Dispatchers.Main) { 
                            imgQr.setImageBitmap(bitmap) 
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

    // 🦁 UI UPDATES
    private fun updateUIForComputing() {
        btnTransmit.text = "⏳ COMPUTING..."
        btnTransmit.setBackgroundColor(Color.parseColor("#FF6F00")) // Orange
        btnTransmit.isEnabled = false // Prevent double click
        
        tvStatus.text = "🦁 Computing Zero-Knowledge Proof..."
        tvStatus.setTextColor(Color.WHITE)
        
        loader.visibility = View.VISIBLE
        imgQr.setColorFilter(Color.DKGRAY)
    }
    
    private fun updateUIForTransmitting() {
        btnTransmit.text = "⏹ STOP BROADCAST"
        btnTransmit.setBackgroundColor(Color.parseColor("#D32F2F")) // Red
        btnTransmit.isEnabled = true
        
        tvStatus.text = "📡 Broadcasting Identity..."
        tvStatus.setTextColor(Color.parseColor("#00E676")) // Neon Green
        
        loader.visibility = View.GONE
        imgQr.clearColorFilter()
    }
    
    private fun resetUI() {
        btnTransmit.text = "📡 TRANSMIT"
        btnTransmit.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
        btnTransmit.isEnabled = true
        
        tvStatus.text = "Ready to Transmit"
        tvStatus.setTextColor(Color.LTGRAY)
        
        loader.visibility = View.GONE
        imgQr.clearColorFilter()
        imgQr.setImageResource(android.R.drawable.ic_menu_gallery)
        imgQr.setColorFilter(Color.DKGRAY)
    }
    
    private fun showError(message: String) {
        stopTransmission()
        tvStatus.text = "❌ $message"
        tvStatus.setTextColor(Color.RED)
        
        lifecycleScope.launch {
            delay(3000)
            if (!isTransmitting) resetUI()
        }
    }
}