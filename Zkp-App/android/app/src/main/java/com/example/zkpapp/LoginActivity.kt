package com.example.zkpapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import java.util.EnumMap

/**
 * LoginActivity - Zero-Knowledge Proof Transmitter 🦁
 * * Features:
 * ✅ Generates ZK Proof via Rust
 * ✅ Animates QR chunks for offline transfer
 * ✅ Robust Error Handling
 */
class LoginActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════
    // 🦁 RUST JNI BRIDGE
    // ═══════════════════════════════════════════════════════════
    companion object {
        init {
            System.loadLibrary("zkp_mobile")
        }

        // QR Animation Constants
        private const val QR_SIZE = 800 
        private const val FRAME_DELAY_MS = 150L // Slower for better scanning
        private const val CYCLE_PAUSE_MS = 300L 
    }

    /**
     * Calls Rust to generate ZK Proof + chunked data
     * Returns: JSON array like ["1/5|data|crc32", "2/5|data|crc32", ...]
     */
    external fun stringFromRust(): String

    // ═══════════════════════════════════════════════════════════
    // 📱 UI COMPONENTS
    // ═══════════════════════════════════════════════════════════
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnTransmit: Button
    private lateinit var btnGotoScanner: Button

    // ═══════════════════════════════════════════════════════════
    // 🎬 ANIMATION STATE
    // ═══════════════════════════════════════════════════════════
    private var animationJob: Job? = null
    @Volatile private var isTransmitting = false

    // ═══════════════════════════════════════════════════════════
    // 🎯 LIFECYCLE METHODS
    // ═══════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initializeUI()
        
        // 🛡️ Security Check
        if (!IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ Please Create Identity First!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupButtonListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnimation()
    }

    override fun onPause() {
        super.onPause()
        if (isTransmitting) {
            animationJob?.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🔧 INITIALIZATION
    // ═══════════════════════════════════════════════════════════

    private fun initializeUI() {
        qrImage = findViewById(R.id.imgDynamicQr)
        statusText = findViewById(R.id.tvStatus)
        btnTransmit = findViewById(R.id.btnTransmit)
        btnGotoScanner = findViewById(R.id.btnGotoScanner)

        statusText.text = "🔐 Ready to Transmit Zero-Knowledge Proof"
        statusText.setTextColor(Color.DKGRAY)
    }

    private fun setupButtonListeners() {
        btnTransmit.setOnClickListener {
            if (isTransmitting) return@setOnClickListener
            startTransmission()
        }

        btnGotoScanner.setOnClickListener {
            stopAnimation()
            startActivity(Intent(this, VerifierActivity::class.java))
            finish()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🚀 PROOF GENERATION
    // ═══════════════════════════════════════════════════════════

    private fun startTransmission() {
        isTransmitting = true

        // UI Feedback
        qrImage.alpha = 1.0f
        btnTransmit.isEnabled = false
        btnTransmit.text = "⏳ Generating Proof..."
        btnTransmit.setBackgroundColor(Color.GRAY)
        statusText.text = "⚡ Computing Zero-Knowledge Proof..."
        statusText.setTextColor(Color.parseColor("#FF9800")) // Orange

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 🦁 1. CALL RUST
                val jsonResponse = stringFromRust()

                withContext(Dispatchers.Main) {
                    handleProofGeneration(jsonResponse)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Critical error: ${e.message}")
                }
            }
        }
    }

    private fun handleProofGeneration(jsonResponse: String) {
        if (jsonResponse.startsWith("Error", ignoreCase = true)) {
            showError(jsonResponse)
            return
        }

        try {
            val jsonArray = JSONArray(jsonResponse)
            val totalChunks = jsonArray.length()

            if (totalChunks == 0) {
                showError("No data chunks received")
                return
            }

            // Success UI
            statusText.text = "📡 Broadcasting ($totalChunks frames)"
            statusText.setTextColor(Color.parseColor("#2E7D32")) // Green
            btnTransmit.text = "🔄 Transmitting..."
            btnTransmit.setBackgroundColor(Color.parseColor("#4CAF50"))

            // 🦁 2. START ANIMATION
            startQrAnimation(jsonArray)

        } catch (e: JSONException) {
            showError("Failed to parse chunks: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🔄 FOUNTAIN ANIMATION LOGIC
    // ═══════════════════════════════════════════════════════════

    private fun startQrAnimation(dataChunks: JSONArray) {
        val totalFrames = dataChunks.length()
        val encoder = BarcodeEncoder()
        
        // Optimize QR for Data Density (Level L)
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L
        hints[EncodeHintType.MARGIN] = 1

        animationJob = CoroutineScope(Dispatchers.Main).launch {
            val indices = (0 until totalFrames).toMutableList()
            var cycleMode = AnimationMode.FORWARD

            while (isActive) {
                // Shuffle Strategy
                when (cycleMode) {
                    AnimationMode.FORWARD -> indices.sort()
                    AnimationMode.REVERSE -> indices.sortDescending()
                    AnimationMode.RANDOM -> indices.shuffle()
                }

                for (i in indices) {
                    if (!isActive) break

                    val chunkData = dataChunks.getString(i)
                    try {
                        val matrix = MultiFormatWriter().encode(
                            chunkData, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints
                        )
                        val bitmap = encoder.createBitmap(matrix)
                        qrImage.setImageBitmap(bitmap)
                    } catch (_: Exception) {}

                    delay(FRAME_DELAY_MS)
                }

                cycleMode = cycleMode.next()
                delay(CYCLE_PAUSE_MS)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🛠️ UTILITY
    // ═══════════════════════════════════════════════════════════

    private fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
        isTransmitting = false
        resetButton()
    }

    private fun showError(message: String) {
        statusText.text = "❌ $message"
        statusText.setTextColor(Color.RED)
        resetButton()
        isTransmitting = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun resetButton() {
        btnTransmit.isEnabled = true
        btnTransmit.text = "📡 TRANSMIT IDENTITY"
        btnTransmit.setBackgroundColor(Color.parseColor("#4CAF50"))
    }

    private enum class AnimationMode {
        FORWARD, REVERSE, RANDOM;
        fun next() = when (this) {
            FORWARD -> REVERSE
            REVERSE -> RANDOM
            RANDOM -> RANDOM
        }
    }
}