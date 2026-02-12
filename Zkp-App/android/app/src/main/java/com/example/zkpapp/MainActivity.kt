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
        // 🟦 BUTTON 1: SCAN QR TO LOGIN
        // =========================================================
        // 🦁 FIX: Ab ye "LoginActivity" (QR Generator) kholega.
        // User apna QR dikhayega login karne ke liye.
        findViewById<Button>(R.id.btnScanQrLogin).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // =========================================================
        // 🟧 BUTTON 2: SCAN PASSPORT
        // =========================================================
        findViewById<Button>(R.id.btnScanPassport).setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // =========================================================
        // 🟩 BUTTON 3: OFFLINE IDENTITY
        // =========================================================
        findViewById<Button>(R.id.btnOfflineIdentity).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                Toast.makeText(this, "⚠️ No Identity Found!", Toast.LENGTH_SHORT).show()
            }
        }

        // =========================================================
        // ⬜ BUTTON 4: TEST PROOF (OFFLINE)
        // =========================================================
        // 🦁 FIX: Ab ye "VerifierActivity" (Scanner) kholega.
        // Ye testing tool hai check karne ke liye ke proof sahi hai ya nahi.
        findViewById<Button>(R.id.btnTestProof).setOnClickListener {
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }
}