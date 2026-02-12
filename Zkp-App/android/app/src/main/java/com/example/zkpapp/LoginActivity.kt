package com.example.zkpapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import java.util.EnumMap

class LoginActivity : AppCompatActivity() {

    companion object {
        init {
            try {
                System.loadLibrary("zkp_mobile")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("ZKP", "Native library failed to load", e)
            }
        }

        private const val QR_SIZE = 800 // 🦁 Adjusted for performance
        private const val FRAME_DELAY_MS = 150L // Faster animation
        private const val CYCLE_PAUSE_MS = 300L
    }

    external fun stringFromRust(): String

    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnTransmit: Button
    private lateinit var btnGotoScanner: Button

    private var animationJob: Job? = null
    @Volatile private var isTransmitting = false

    // -----------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initializeUI()

        // 🛡️ Check Identity Logic
        try {
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please create identity first.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } catch (e: Exception) {
            // Agar storage access mein issue ho to safe side handle karein
            Log.e("ZKP", "Storage Error", e)
        }

        setupListeners()
    }

    override fun onPause() {
        super.onPause()
        stopAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnimation()
    }

    // -----------------------------------------------------------
    // UI Setup
    // -----------------------------------------------------------

    private fun initializeUI() {
        qrImage = findViewById(R.id.imgDynamicQr)
        statusText = findViewById(R.id.tvStatus)
        btnTransmit = findViewById(R.id.btnTransmit)
        btnGotoScanner = findViewById(R.id.btnGotoScanner)

        updateStatus("Ready to transmit Zero-Knowledge Proof", Color.DKGRAY)
    }

    private fun setupListeners() {
        btnTransmit.setOnClickListener {
            if (!isTransmitting) {
                startTransmission()
            }
        }

        btnGotoScanner.setOnClickListener {
            stopAnimation()
            startActivity(Intent(this, VerifierActivity::class.java))
            finish()
        }
    }

    // -----------------------------------------------------------
    // Transmission Flow
    // -----------------------------------------------------------

    private fun startTransmission() {
        isTransmitting = true
        setLoadingState()

        lifecycleScope.launch {
            try {
                // 🦁 Running heavy Rust task on IO thread
                val jsonResponse = withContext(Dispatchers.IO) {
                    stringFromRust()
                }
                handleRustResponse(jsonResponse)

            } catch (e: UnsatisfiedLinkError) {
                showError("Native engine not available (libzkp_mobile.so missing).")
            } catch (e: Exception) {
                showError("Unexpected error: ${e.message}")
            }
        }
    }

    private fun handleRustResponse(response: String) {
        if (response.isBlank()) {
            showError("Empty proof response from Rust.")
            return
        }

        if (response.startsWith("Error", ignoreCase = true)) {
            showError(response)
            return
        }

        try {
            val jsonArray = JSONArray(response)
            if (jsonArray.length() == 0) {
                showError("Proof contains no frames.")
                return
            }

            updateStatus(
                "Broadcasting ${jsonArray.length()} frames...",
                Color.parseColor("#2E7D32") // Green
            )

            startQrAnimation(jsonArray)

        } catch (e: JSONException) {
            showError("Invalid proof format received.")
        }
    }

    // -----------------------------------------------------------
    // QR Animation Engine
    // -----------------------------------------------------------

    private fun startQrAnimation(dataChunks: JSONArray) {
        stopAnimation() // Purani animation rokein

        animationJob = lifecycleScope.launch(Dispatchers.Default) {
            val encoder = BarcodeEncoder()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
                put(EncodeHintType.MARGIN, 1)
            }

            val totalFrames = dataChunks.length()
            val indices = (0 until totalFrames).toMutableList()
            var mode = AnimationMode.FORWARD
            val writer = MultiFormatWriter()

            isTransmitting = true

            while (isActive && isTransmitting) {
                // Shuffle logic
                when (mode) {
                    AnimationMode.FORWARD -> indices.sort()
                    AnimationMode.REVERSE -> indices.sortDescending()
                    AnimationMode.RANDOM -> indices.shuffle()
                }

                for (i in indices) {
                    if (!isActive || !isTransmitting) break

                    try {
                        val chunk = dataChunks.getString(i)
                        
                        // 🦁 QR Generation (Computationally Heavy part)
                        val matrix = writer.encode(
                            chunk,
                            BarcodeFormat.QR_CODE,
                            QR_SIZE,
                            QR_SIZE,
                            hints
                        )
                        val bitmap: Bitmap = encoder.createBitmap(matrix)

                        // 🦁 UI Update on Main Thread
                        withContext(Dispatchers.Main) {
                            qrImage.setImageBitmap(bitmap)
                        }

                    } catch (e: Exception) {
                        Log.e("ZKP", "QR frame error", e)
                    }

                    delay(FRAME_DELAY_MS)
                }

                mode = mode.next()
                delay(CYCLE_PAUSE_MS)
            }
        }
    }

    private fun stopAnimation() {
        isTransmitting = false
        animationJob?.cancel()
        animationJob = null
        
        // UI Reset on Main Thread
        runOnUiThread {
            resetButton()
        }
    }

    // -----------------------------------------------------------
    // UI State Helpers
    // -----------------------------------------------------------

    private fun setLoadingState() {
        btnTransmit.isEnabled = false
        btnTransmit.text = "Generating Proof..."
        btnTransmit.setBackgroundColor(Color.GRAY)
        updateStatus("Computing Zero-Knowledge Proof...", Color.parseColor("#FF9800"))
    }

    private fun resetButton() {
        if (!isDestroyed) {
            btnTransmit.isEnabled = true
            btnTransmit.text = "TRANSMIT IDENTITY"
            btnTransmit.setBackgroundColor(Color.parseColor("#4CAF50"))
        }
    }

    private fun updateStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun showError(message: String) {
        stopAnimation()
        updateStatus("Error: $message", Color.RED)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -----------------------------------------------------------
    // Animation Mode
    // -----------------------------------------------------------

    private enum class AnimationMode {
        FORWARD, REVERSE, RANDOM;

        fun next(): AnimationMode {
            return when (this) {
                FORWARD -> REVERSE
                REVERSE -> RANDOM
                RANDOM -> FORWARD
            }
        }
    }
}