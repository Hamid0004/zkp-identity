package com.example.zkpapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.zkpapp.auth.ZkAuthManager
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        init {
            try {
                System.loadLibrary("zkp_mobile")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("ZKP", "Native library failed to load", e)
            }
        }
    }

    // 🦁 NOTE: Yeh JNI function zaroori hai kyunki ZkAuthManager isay use kar sakta hai proof generate karne ke liye.
    external fun stringFromRust(): String

    private lateinit var statusText: TextView
    
    // UI Containers to hide (White Box Fix)
    private var qrCardView: View? = null 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 1. Init UI
        statusText = findViewById(R.id.tvStatus)
        
        // Find CardView to hide it (Safed Dabba Fix)
        val qrImage = findViewById<View>(R.id.imgDynamicQr)
        val parent1 = qrImage?.parent as? View
        val parent2 = parent1?.parent as? View
        val parent3 = parent2?.parent as? View
        qrCardView = parent3 ?: parent2 // Attempt to find the CardView

        // 2. Identity Check
        if (!IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ Identity Missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 3. Clean UI for Web Login
        // Hum Transmit buttons aur QR images ko chupayenge
        qrCardView?.visibility = View.GONE
        findViewById<View>(R.id.imgDynamicQr)?.visibility = View.GONE
        findViewById<View>(R.id.btnTransmit)?.visibility = View.GONE
        findViewById<View>(R.id.btnGotoScanner)?.visibility = View.GONE

        // 4. Start Process
        statusText.text = "🦁 Starting Web Scanner..."
        statusText.setTextColor(Color.WHITE)
        
        startWebQrScanner()
    }

    // -----------------------------------------------------------
    // 🔵 WEB LOGIN LOGIC (Phase 7)
    // -----------------------------------------------------------
    
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
                // User ne back button dabaya
                finish()
            } else {
                // QR Scan ho gaya -> Login Process Karo
                performZkLogin(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun performZkLogin(sessionId: String) {
        statusText.text = "🦁 Generating Proof..."
        statusText.setTextColor(Color.parseColor("#FF9800")) // Orange
        statusText.textSize = 20f

        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = sessionId,
                onStatus = { msg -> statusText.text = msg },
                onSuccess = {
                    statusText.text = "✅ Login Approved!"
                    statusText.setTextColor(Color.parseColor("#2E7D32")) // Green
                    Toast.makeText(this@LoginActivity, "Web Login Successful!", Toast.LENGTH_LONG).show()
                    
                    // Handler Fix (No Coroutine Delay Error)
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1500)
                },
                onError = { error ->
                    statusText.text = error
                    statusText.setTextColor(Color.RED)
                    Toast.makeText(this@LoginActivity, error, Toast.LENGTH_LONG).show()

                    // Handler Fix
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 2500)
                }
            )
        }
    }
}