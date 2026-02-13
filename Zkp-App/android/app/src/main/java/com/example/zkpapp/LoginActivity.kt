package com.example.zkpapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.* import org.json.JSONArray
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
    private var qrCardView: View? = null
    private lateinit var statusText: TextView
    private lateinit var btnTransmit: Button
    
    private var animationJob: Job? = null
    @Volatile private var isTransmitting = false
    private var currentMode = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initializeUI()

        if (!IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ Identity Missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupListeners()

        val mode = intent.getStringExtra("MODE")
        currentMode = mode ?: "TRANSMIT"

        if (currentMode == "WEB_LOGIN") {
            qrCardView?.visibility = View.GONE
            qrImage.visibility = View.GONE
            btnTransmit.visibility = View.GONE
            statusText.text = "🦁 Starting Web Scanner..."
            statusText.setTextColor(Color.WHITE)
            startWebQrScanner()
        } else {
            qrCardView?.visibility = View.VISIBLE
            qrImage.visibility = View.VISIBLE
            btnTransmit.visibility = View.VISIBLE
            btnTransmit.text = "TRANSMIT IDENTITY"
            statusText.text = "Ready to Share Identity"
        }
        
        findViewById<View>(R.id.btnGotoScanner)?.visibility = View.GONE
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

        try {
            val parent1 = qrImage.parent as? ViewGroup
            val parent2 = parent1?.parent as? ViewGroup
            val parent3 = parent2?.parent as? ViewGroup 
            qrCardView = parent3 as? View ?: parent2 as? View
        } catch (e: Exception) {
            Log.e("ZKP", "Could not find CardView parent", e)
        }
    }

    private fun setupListeners() {
        btnTransmit.setOnClickListener {
            if (!isTransmitting) startTransmission()
        }
    }

    private fun startWebQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setCaptureActivity(PortraitCaptureActivity::class.java)
        integrator.setOrientationLocked(true)
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
                if (currentMode == "WEB_LOGIN") finish()
            } else {
                performZkLogin(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun performZkLogin(sessionId: String) {
        qrCardView?.visibility = View.GONE 
        qrImage.visibility = View.GONE
        btnTransmit.visibility = View.GONE

        statusText.text = "🦁 Generating Proof..."
        statusText.setTextColor(Color.parseColor("#FF9800"))
        statusText.textSize = 20f

        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = sessionId,
                onStatus = { msg -> statusText.text = msg },
                onSuccess = {
                    statusText.text = "✅ Login Approved!"
                    statusText.setTextColor(Color.parseColor("#2E7D32"))
                    Toast.makeText(this@LoginActivity, "Web Login Successful!", Toast.LENGTH_LONG).show()
                    
                    // 🦁 FIX: Removed 'delay()' and used Handler
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1500)
                },
                onError = { error ->
                    statusText.text = error
                    statusText.setTextColor(Color.RED)
                    Toast.makeText(this@LoginActivity, error, Toast.LENGTH_LONG).show()
                    
                    // 🦁 FIX: Removed 'delay()' and used Handler
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 2500)
                }
            )
        }
    }

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
                statusText.setTextColor(Color.LTGRAY)
            }
        }
    }

    private fun showError(message: String) {
        stopAnimation()
        statusText.text = "❌ $message"
        statusText.setTextColor(Color.RED)
    }
}