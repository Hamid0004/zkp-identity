package com.example.zkpapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
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

    external fun stringFromRust(): String

    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnTransmit: Button
    private lateinit var btnVerify: Button // Formerly btnGotoScanner

    private var animationJob: Job? = null
    @Volatile private var isTransmitting = false
    private var currentMode = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initializeUI()

        // 🛡️ Identity Check
        if (!IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ Identity Missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupListeners()

        // 🦁 MODE SELECTION LOGIC
        val mode = intent.getStringExtra("MODE")
        currentMode = mode ?: "OFFLINE_DASHBOARD"

        if (currentMode == "WEB_LOGIN") {
            // 🔵 PHASE 7: WEB LOGIN MODE (ZkAuth)
            // Hide everything, Start Camera
            qrImage.visibility = View.GONE
            btnTransmit.visibility = View.GONE
            btnVerify.visibility = View.GONE
            statusText.text = "🦁 Starting Web Scanner..."

            startWebQrScanner()

        } else {
            // 🟩 PHASE 8: OFFLINE IDENTITY DASHBOARD
            // Show QR, Transmit Button, AND Verify Button
            qrImage.visibility = View.VISIBLE
            btnTransmit.visibility = View.VISIBLE
            btnVerify.visibility = View.VISIBLE

            btnTransmit.text = "TRANSMIT IDENTITY"
            btnVerify.text = "🔍 SCAN & VERIFY" 
            statusText.text = "Ready to Share Identity"
        }
    }

    override fun onPause() {
        super.onPause()
        stopAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnimation()
    }

    private fun initializeUI() {
        qrImage = findViewById(R.id.imgDynamicQr)
        statusText = findViewById(R.id.tvStatus)
        btnTransmit = findViewById(R.id.btnTransmit)
        btnVerify = findViewById(R.id.btnGotoScanner)
    }

    private fun setupListeners() {
        // Button 1: Transmit Identity (Animation)
        btnTransmit.setOnClickListener {
            if (!isTransmitting) startTransmission()
        }

        // Button 2: Scan & Verify (Opens VerifierActivity)
        btnVerify.setOnClickListener {
            stopAnimation()
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }

    // -----------------------------------------------------------
    // 🔵 LOGIC 1: WEB LOGIN SCANNER (Phase 7)
    // -----------------------------------------------------------
    private fun startWebQrScanner() {
        val integrator = IntentIntegrator(this)
        
        // 🦁 FIX: Force Portrait Mode using custom Activity
        // Make sure PortraitCaptureActivity.kt is created!
        integrator.setCaptureActivity(PortraitCaptureActivity::class.java)
        integrator.setOrientationLocked(true) // Lock Rotation
        
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("🦁 Scan Web Login QR")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(true)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                // Cancel hua to wapis jao
                if (currentMode == "WEB_LOGIN") finish()
            } else {
                // QR Scanned -> ZkAuth Login
                performZkLogin(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun performZkLogin(sessionId: String) {
        statusText.text = "🦁 Generating Proof for Web..."
        
        // 🦁 CRITICAL: Wrapped in coroutine scope for safety
        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = sessionId,
                onStatus = { msg -> statusText.text = msg },
                onSuccess = {
                    Toast.makeText(this@LoginActivity, "Login Successful!", Toast.LENGTH_LONG).show()
                    finish()
                },
                onError = { error ->
                    Toast.makeText(this@LoginActivity, error, Toast.LENGTH_LONG).show()
                    finish()
                }
            )
        }
    }

    // -----------------------------------------------------------
    // 🟢 LOGIC 2: OFFLINE TRANSMIT (Phase 8)
    // -----------------------------------------------------------
    private fun startTransmission() {
        isTransmitting = true
        btnTransmit.isEnabled = false
        btnTransmit.text = "Generating..."
        statusText.text = "Computing Proof..."

        lifecycleScope.launch {
            try {
                val jsonResponse = withContext(Dispatchers.IO) { stringFromRust() }
                handleRustResponse(jsonResponse)
            } catch (e: Exception) { showError("Error: ${e.message}") }
        }
    }

    private fun handleRustResponse(response: String) {
        try {
            val jsonArray = JSONArray(response)
            statusText.text = "Broadcasting Identity..."
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            startQrAnimation(jsonArray)
        } catch (e: Exception) { showError("Invalid Proof") }
    }

    private fun startQrAnimation(dataChunks: JSONArray) {
        stopAnimation()
        animationJob = lifecycleScope.launch(Dispatchers.Default) {
            val encoder = BarcodeEncoder()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
            }
            val totalFrames = dataChunks.length()
            val indices = (0 until totalFrames).toMutableList()
            var mode = 0
            val writer = MultiFormatWriter()

            isTransmitting = true
            while (isActive && isTransmitting) {
                indices.shuffle()
                for (i in indices) {
                    if (!isActive) break
                    try {
                        val matrix = writer.encode(dataChunks.getString(i), BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
                        val bitmap = encoder.createBitmap(matrix)
                        withContext(Dispatchers.Main) { qrImage.setImageBitmap(bitmap) }
                    } catch (_: Exception) {}
                    delay(FRAME_DELAY_MS)
                }
                delay(CYCLE_PAUSE_MS)
            }
        }
    }

    private fun stopAnimation() {
        isTransmitting = false
        animationJob?.cancel()
        animationJob = null
        runOnUiThread {
            if (!isDestroyed) {
                btnTransmit.isEnabled = true
                btnTransmit.text = "TRANSMIT IDENTITY"
                statusText.text = "Ready to Share"
                statusText.setTextColor(Color.DKGRAY)
            }
        }
    }

    private fun showError(message: String) {
        stopAnimation()
        statusText.text = "❌ $message"
        statusText.setTextColor(Color.RED)
    }
}