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
import com.example.zkpapp.auth.ZkAuthManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.integration.android.IntentIntegrator
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

        private const val QR_SIZE = 800
        private const val FRAME_DELAY_MS = 150L
        private const val CYCLE_PAUSE_MS = 300L
    }

    // Rust Function for Offline Proof
    external fun stringFromRust(): String

    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnTransmit: Button
    private lateinit var btnScanWeb: Button // Renamed from btnGotoScanner

    private var animationJob: Job? = null
    @Volatile private var isTransmitting = false

    // -----------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initializeUI()

        // 🛡️ Security Check
        try {
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please create identity first.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } catch (e: Exception) {
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
        btnScanWeb = findViewById(R.id.btnGotoScanner) // Using existing ID

        // Update Button Text for Clarity
        btnScanWeb.text = "📷 SCAN WEB QR (LOGIN)"
        btnScanWeb.setBackgroundColor(Color.parseColor("#1976D2")) // Blue for Scan

        updateStatus("Choose Mode: Transmit or Scan", Color.DKGRAY)
    }

    private fun setupListeners() {
        // 🟢 MODE 1: OFFLINE TRANSMIT (For Green Button)
        btnTransmit.setOnClickListener {
            if (!isTransmitting) {
                startTransmission()
            }
        }

        // 🔵 MODE 2: WEB SCANNER (For Blue Button)
        btnScanWeb.setOnClickListener {
            stopAnimation()
            startQrScanner()
        }
    }

    // -----------------------------------------------------------
    // 🔵 SCANNER LOGIC (ZkAuth)
    // -----------------------------------------------------------

    private fun startQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("🦁 Scan Login QR from Web Screen")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(true)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(this, "Scan Cancelled", Toast.LENGTH_SHORT).show()
            } else {
                // QR Found -> Start ZkAuth Login
                performZkLogin(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun performZkLogin(sessionId: String) {
        updateStatus("🦁 Verifying with ZkAuth...", Color.parseColor("#FF9800"))
        btnScanWeb.isEnabled = false

        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = sessionId,
                onStatus = { msg -> updateStatus(msg, Color.DKGRAY) },
                onSuccess = {
                    updateStatus("✅ Login Approved!", Color.parseColor("#2E7D32"))
                    Toast.makeText(this@LoginActivity, "Web Login Successful!", Toast.LENGTH_LONG).show()
                    finish()
                },
                onError = { error ->
                    showError(error)
                    btnScanWeb.isEnabled = true
                }
            )
        }
    }

    // -----------------------------------------------------------
    // 🟢 TRANSMITTER LOGIC (Offline Animation)
    // -----------------------------------------------------------

    private fun startTransmission() {
        isTransmitting = true
        setLoadingState()

        lifecycleScope.launch {
            try {
                val jsonResponse = withContext(Dispatchers.IO) {
                    stringFromRust()
                }
                handleRustResponse(jsonResponse)
            } catch (e: UnsatisfiedLinkError) {
                showError("Native engine missing.")
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    private fun handleRustResponse(response: String) {
        if (response.startsWith("Error", ignoreCase = true) || response.isBlank()) {
            showError(response)
            return
        }

        try {
            val jsonArray = JSONArray(response)
            if (jsonArray.length() == 0) {
                showError("No proof frames.")
                return
            }

            updateStatus("Broadcasting Identity...", Color.parseColor("#4CAF50"))
            startQrAnimation(jsonArray)

        } catch (e: JSONException) {
            showError("Invalid proof format.")
        }
    }

    private fun startQrAnimation(dataChunks: JSONArray) {
        stopAnimation()

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
                when (mode) {
                    AnimationMode.FORWARD -> indices.sort()
                    AnimationMode.REVERSE -> indices.sortDescending()
                    AnimationMode.RANDOM -> indices.shuffle()
                }

                for (i in indices) {
                    if (!isActive || !isTransmitting) break
                    try {
                        val matrix = writer.encode(dataChunks.getString(i), BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
                        val bitmap = encoder.createBitmap(matrix)
                        withContext(Dispatchers.Main) { qrImage.setImageBitmap(bitmap) }
                    } catch (_: Exception) {}
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
        runOnUiThread { resetButton() }
    }

    // -----------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------

    private fun setLoadingState() {
        btnTransmit.isEnabled = false
        btnTransmit.text = "Generating..."
        updateStatus("Computing Proof...", Color.DKGRAY)
    }

    private fun resetButton() {
        if (!isDestroyed) {
            btnTransmit.isEnabled = true
            btnTransmit.text = "TRANSMIT IDENTITY"
            btnScanWeb.isEnabled = true
        }
    }

    private fun updateStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun showError(message: String) {
        stopAnimation()
        updateStatus("❌ $message", Color.RED)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class AnimationMode {
        FORWARD, REVERSE, RANDOM;
        fun next() = when (this) {
            FORWARD -> REVERSE
            REVERSE -> RANDOM
            RANDOM -> FORWARD
        }
    }
}