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
            startActivity(Intent(this, VerifierActivity::class.java))
        }

        // =========================================================
        // 🟠 BUTTON 2: CREATE IDENTITY (Scan Passport)
        // =========================================================
        val btnPassport: Button = findViewById(R.id.btnPassport)
        btnPassport.setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // =========================================================
        // 🟢 BUTTON 3: OFFLINE MENU (New Green Button)
        // =========================================================
        val btnOfflineMenu: Button = findViewById(R.id.btnOfflineMenu)
        btnOfflineMenu.setOnClickListener {
            // Opens the Menu with Transmit/Verify options
            startActivity(Intent(this, OfflineMenuActivity::class.java))
        }

        // =========================================================
        // ⚪ BUTTON 4: TEST PROOF (Old Debug Logic)
        // =========================================================
        val btnTest: Button = findViewById(R.id.btnTest)
        btnTest.setOnClickListener {
            // Direct Proof Generation (Quick Test)
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    // 🦁 SECURITY: Clean RAM on Close
    override fun onDestroy() {
        super.onDestroy()
        if (IdentityStorage.hasIdentity()) {
            IdentityStorage.clear()
        }
    }
}