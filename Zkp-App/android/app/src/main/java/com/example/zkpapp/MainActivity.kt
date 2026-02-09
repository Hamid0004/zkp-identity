package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // =========================================================
        // 🔵 BUTTON 1: ONLINE LOGIN (Scan QR)
        // =========================================================
        val btnWebLogin: Button = findViewById(R.id.btnWebLogin)
        btnWebLogin.setOnClickListener {
            // 🛡️ Security Check
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Opens Scanner (Receiver Mode logic for Online)
            startActivity(Intent(this, VerifierActivity::class.java))
        }

        // =========================================================
        // 🟠 BUTTON 2: CREATE IDENTITY (Scan Passport)
        // =========================================================
        val btnPassport: Button = findViewById(R.id.btnPassport)
        btnPassport.setOnClickListener {
            // Passport Reader (NFC)
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // =========================================================
        // 🟢 BUTTON 3: OFFLINE IDENTITY (Direct Sender Mode)
        // =========================================================
        val btnOfflineMenu: Button = findViewById(R.id.btnOfflineMenu)
        btnOfflineMenu.setOnClickListener {
            // 🛡️ Security Check
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 🦁 UPDATE: Bypassed Menu -> Opens Animated QR Generator directly
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // =========================================================
        // ⚪ BUTTON 4: TEST PROOF (Quick Debug)
        // =========================================================
        val btnTest: Button = findViewById(R.id.btnTest)
        btnTest.setOnClickListener {
            // Direct Proof Generation (Useful for benchmarks/testing without checks)
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    // 🦁 SECURITY: Clean RAM on Close
    // Jab user App close kare, to sensitive data memory se uda do.
    override fun onDestroy() {
        super.onDestroy()
        if (IdentityStorage.hasIdentity()) {
            IdentityStorage.clear()
        }
    }
}