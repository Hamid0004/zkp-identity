package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🦁 0. CRASH REPORT RECEIVER (Debugging)
        // Agar App crash hokar restart hua hai, to yahan Error dikhao
        if (intent.hasExtra("CRASH_REPORT")) {
            AlertDialog.Builder(this)
                .setTitle("🦁 App Crashed!")
                .setMessage(intent.getStringExtra("CRASH_REPORT"))
                .setPositiveButton("OK") { _, _ -> }
                .setCancelable(false)
                .show()
        }

        // =========================================================
        // 🔵 BUTTON 1: ONLINE LOGIN (Scanner Mode)
        // =========================================================
        val btnWebLogin: Button = findViewById(R.id.btnWebLogin)
        btnWebLogin.setOnClickListener {
            // 🛡️ Security Check
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Online Login ke liye humein Website ka QR scan karna hota hai
            startActivity(Intent(this, VerifierActivity::class.java))
        }

        // =========================================================
        // 🟠 BUTTON 2: CREATE IDENTITY (Passport NFC)
        // =========================================================
        val btnPassport: Button = findViewById(R.id.btnPassport)
        btnPassport.setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // =========================================================
        // 🟢 BUTTON 3: OFFLINE IDENTITY (Sender Mode)
        // =========================================================
        val btnOfflineMenu: Button = findViewById(R.id.btnOfflineMenu)
        btnOfflineMenu.setOnClickListener {
            // 🛡️ Security Check
            if (!IdentityStorage.hasIdentity()) {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 🦁 Updates: Direct Logic -> Opens Animated QR Generator (Sender)
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // =========================================================
        // ⚪ BUTTON 4: TEST PROOF (Quick Debug)
        // =========================================================
        val btnTest: Button = findViewById(R.id.btnTest)
        btnTest.setOnClickListener {
            // Direct Proof Generation (Bypassing checks for testing)
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