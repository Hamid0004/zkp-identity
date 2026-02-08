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
            // 🛡️ Security Check: Kya Identity hai?
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener // 🛑 Stop here if no ID
            }
            
            // ✅ Agar Identity hai, to Scanner kholo
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }

        // =========================================================
        // 🟠 BUTTON 2: CREATE IDENTITY (Scan Passport)
        // =========================================================
        val btnPassport: Button = findViewById(R.id.btnPassport)
        btnPassport.setOnClickListener {
            // Passport Dashboard khulega
            val intent = Intent(this, PassportActivity::class.java)
            startActivity(intent)
        }

        // =========================================================
        // ⚪ BUTTON 3: OFFLINE IDENTITY TOOLS (Next Page)
        // =========================================================
        // ⚠️ Note: XML ID updated to 'btnOfflineMenu' to match layout
        val btnOfflineMenu: Button = findViewById(R.id.btnOfflineMenu)
        btnOfflineMenu.setOnClickListener {
            // Ab hum 'Menu Page' kholenge jahan Transmit/Verify buttons hain
            val intent = Intent(this, OfflineMenuActivity::class.java)
            startActivity(intent)
        }
    }

    // 🦁 DAY 84: SECURITY HARDENING (RAM Cleanup)
    // Jab user App close kare, to sensitive data memory se uda do.
    override fun onDestroy() {
        super.onDestroy()
        if (IdentityStorage.hasIdentity()) {
            IdentityStorage.clear()
        }
    }
}