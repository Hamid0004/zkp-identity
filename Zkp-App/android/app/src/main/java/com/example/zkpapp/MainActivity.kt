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

        // 🟦 BLUE BUTTON: SCAN QR TO LOGIN (Phase 7 - Web)
        // Logic: Yeh 'LoginActivity' kholega jo ab sirf Web Scanner hai
        findViewById<Button>(R.id.btnScanQrLogin).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("MODE", "WEB_LOGIN") 
                startActivity(intent)
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // 🟧 ORANGE BUTTON: CREATE ID
        findViewById<Button>(R.id.btnScanPassport).setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // 🟩 GREEN BUTTON: OFFLINE IDENTITY (Phase 8 - Dashboard)
        // 🦁 FIX: Isay 'LoginActivity' se hata kar 'OfflineMenuActivity' par lagaya gaya hai
        findViewById<Button>(R.id.btnOfflineIdentity).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                // ✅ CORRECT PATH: Offline Menu (QR + Transmit/Verify Buttons)
                val intent = Intent(this, OfflineMenuActivity::class.java)
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