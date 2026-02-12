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
        // 🟦 BUTTON 1: SCAN QR TO LOGIN (PHASE 7 - ZkAuth)
        // =========================================================
        findViewById<Button>(R.id.btnScanQrLogin).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("MODE", "SCAN_LOGIN") // 🦁 Batao ke Login karna hai
                startActivity(intent)
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // =========================================================
        // 🟧 BUTTON 2: SCAN PASSPORT (CREATE ID)
        // =========================================================
        findViewById<Button>(R.id.btnScanPassport).setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // =========================================================
        // 🟢 BUTTON 3: OFFLINE IDENTITY (Show QR)
        // =========================================================
        findViewById<Button>(R.id.btnOfflineIdentity).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("MODE", "TRANSMIT") // 🦁 Batao ke QR dikhana hai
                startActivity(intent)
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // =========================================================
        // ⬜ BUTTON 4: TEST PROOF (OFFLINE VERIFIER)
        // =========================================================
        findViewById<Button>(R.id.btnTestProof).setOnClickListener {
            // Yeh offline proof check karne ke liye hai
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }
}