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
    private lateinit var btnScanWeb: Button 

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

        // 🦁 LOGIC SEPARATION & AUTO-START
        val mode = intent.getStringExtra("MODE")
        currentMode = mode ?: ""

        if (mode == "SCAN_LOGIN") {
            // MODE 1: SCANNER ONLY
            // Transmit wale buttons CHUPA DO
            btnTransmit.visibility = View.GONE
            qrImage.visibility = View.GONE
            statusText.text = "🦁 Opening Scanner..."
            
            // Auto-start Scanner
            startQrScanner()

        } else if (mode == "TRANSMIT") {
            // MODE 2: TRANSMITTER ONLY
            // Scanner wala button CHUPA DO
            btnScanWeb.visibility = View.GONE
            statusText.text = "Ready to Transmit Identity"
            
            // Auto-start Animation
            startTransmission()
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
        btnScanWeb = findViewById(R.id.btnGotoScanner)

        btnScanWeb.text = "📷 RESCAN QR"
        btnScanWeb.setBackgroundColor(Color.parseColor("#1976D2")) // Blue
    }

    private fun setupListeners() {
        btnTransmit.setOnClickListener {
            if (!isTransmitting) startTransmission()
        }
        btnScanWeb.setOnClickListener {
            startQrScanner()
        }
    }

    // -----------------------------------------------------------
    // 🔵 PHASE 7: ZK AUTH SCANNER (PORTRAIT FIXED)
    // -----------------------------------------------------------
    private fun startQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("🦁 Scan Login QR from Web")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        
        // 🦁 FIX: Phone Rotation Issue
        // False = Device ki orientation follow karega (Portrait)
        integrator.setOrientationLocked(false) 
        
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(this, "Scan Cancelled", Toast.LENGTH_SHORT).show()
                // Agar cancel kiya to wapis dashboard bhej do (Cleaner UX)
                if(currentMode == "SCAN_LOGIN") finish()
            } else {
                performZkLogin(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun performZkLogin(sessionId: String) {
        statusText.text = "🦁 Verifying with ZkAuth..."
        btnScanWeb.isEnabled = false

        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = sessionId,
                onStatus = { msg -> statusText.text = msg },
                onSuccess = {
                    statusText.text = "✅ Login Approved!"
                    statusText.setTextColor(Color.parseColor("#2E7D32"))
                    Toast.makeText(this@LoginActivity, "Web Login Successful!", Toast.LENGTH_LONG).show()
                    finish()
                },
                onError = { error ->
                    statusText.text = "❌ $error"
                    statusText.setTextColor(Color.RED)
                    btnScanWeb.isEnabled = true
                }
            )
        }
    }

    // -----------------------------------------------------------
    // 🟢 OFFLINE TRANSMIT LOGIC
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
            } catch (e: Exception) { 
                showError("Error: ${e.message}") 
            }
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
            }
        }
    }

    private fun showError(message: String) {
        stopAnimation()
        statusText.text = "❌ $message"
        statusText.setTextColor(Color.RED)
    }
}