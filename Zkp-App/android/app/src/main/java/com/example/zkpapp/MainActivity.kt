package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 🦁 Debouncing Flag (Prevents double clicks)
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🦁 0. CRASH REPORT RECEIVER (Debugging Logic Kept)
        if (intent.hasExtra("CRASH_REPORT")) {
            AlertDialog.Builder(this)
                .setTitle("🦁 App Crashed!")
                .setMessage(intent.getStringExtra("CRASH_REPORT"))
                .setPositiveButton("OK") { _, _ -> }
                .setCancelable(false)
                .show()
        }

        // =========================================================
        // 🟠 BUTTON 1: PASSPORT SCAN (Creates Identity)
        // =========================================================
        // XML ID: btnPassport
        findViewById<Button>(R.id.btnPassport).setOnClickListener { button ->
            handleButtonClick(button as Button, requireIdentity = false) {
                // Opens CameraActivity for MRZ Scanning
                startActivity(Intent(this, CameraActivity::class.java))
            }
        }

        // =========================================================
        // 🟢 BUTTON 2: TRANSMIT IDENTITY (QR Generator)
        // =========================================================
        // XML ID: btnOfflineMenu (Old Logic: TransmitName)
        findViewById<Button>(R.id.btnOfflineMenu).setOnClickListener { button ->
            handleButtonClick(button as Button, requireIdentity = true) {
                Toast.makeText(this, "📡 Starting QR Transmission...", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        // =========================================================
        // 🕵️ BUTTON 3: VERIFIER MODE (Scanner)
        // =========================================================
        // XML ID: btnWebLogin (Old Logic: GotoScanner)
        findViewById<Button>(R.id.btnWebLogin).setOnClickListener { button ->
            // Note: Verifier usually doesn't need identity, but kept 'true' if you want strict mode
            handleButtonClick(button as Button, requireIdentity = false) { 
                startActivity(Intent(this, VerifierActivity::class.java))
            }
        }
    }

    // =========================================================
    // 🛠️ HELPER FUNCTIONS (Logic Preserved)
    // =========================================================

    /**
     * Handles button clicks with Debouncing & Identity Checks
     */
    private fun handleButtonClick(button: Button, requireIdentity: Boolean, action: () -> Unit) {
        if (isProcessing) return

        isProcessing = true
        button.isEnabled = false
        button.alpha = 0.5f

        // 🛡️ Security Check (Only if required)
        if (requireIdentity && !IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_LONG).show()
            resetButton(button)
            return
        }

        // Execute Action
        try {
            action()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // Reset Button after 1.5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            resetButton(button)
        }, 1500)
    }

    private fun resetButton(button: Button) {
        isProcessing = false
        button.isEnabled = true
        button.alpha = 1.0f
    }

    // 🦁 SECURITY: Clean RAM on Close
    override fun onDestroy() {
        super.onDestroy()
        try {
            // Optional: Uncomment if you want to wipe data on every exit
            // IdentityStorage.clear() 
        } catch (_: Exception) {}
    }
}