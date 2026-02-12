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

        // 🟦 BLUE BUTTON: SCAN QR TO LOGIN (Phase 7 - ZkAuth)
        // Logic: Seedha Camera khulega web login ke liye
        findViewById<Button>(R.id.btnScanQrLogin).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("MODE", "WEB_LOGIN") // 🦁 Special Mode
                startActivity(intent)
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // 🟧 ORANGE BUTTON: CREATE ID
        findViewById<Button>(R.id.btnScanPassport).setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // 🟩 GREEN BUTTON: OFFLINE IDENTITY (Phase 8)
        // Logic: LoginActivity khulega jahan Transmit/Verify buttons honge
        findViewById<Button>(R.id.btnOfflineIdentity).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("MODE", "OFFLINE_DASHBOARD") // 🦁 Dashboard Mode
                startActivity(intent)
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // ⬜ GREY BUTTON: TEST PROOF (Direct Verifier)
        findViewById<Button>(R.id.btnTestProof).setOnClickListener {
            startActivity(Intent(this, VerifierActivity::class.java))
        }
    }
}