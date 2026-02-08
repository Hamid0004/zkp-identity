package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class OfflineMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_menu)

        // 1. TRANSMIT (Sender) -> Opens LoginActivity (Animated QR)
        findViewById<Button>(R.id.btnTransmit).setOnClickListener {
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Scan Passport First!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // LoginActivity ab Animated QR generate karega
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        // 2. VERIFY (Receiver) -> Opens VerifierActivity (Scanner)
        findViewById<Button>(R.id.btnVerifyOffline).setOnClickListener {
            // VerifierActivity ab Offline Chunks scan karne ke liye ready hai
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }
    }
}