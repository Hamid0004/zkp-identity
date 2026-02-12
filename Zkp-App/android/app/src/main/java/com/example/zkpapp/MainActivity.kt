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

        // 🦁 1. SCAN QR TO LOGIN (Blue Button)
        // Connection: Opens LoginActivity (QR Generator)
        findViewById<Button>(R.id.btnScanQrLogin).setOnClickListener {
            // Agar Identity nahi hai to pehle passport scan karne ka bolo
            if (IdentityStorage.hasIdentity()) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                 // Or allow login if it's meant to generate QR
                 // For now, let's open it directly based on your request
                 startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        // 🦁 2. SCAN PASSPORT (Orange Button)
        // Connection: Opens PassportActivity (Already Existing)
        findViewById<Button>(R.id.btnScanPassport).setOnClickListener {
            startActivity(Intent(this, PassportActivity::class.java))
        }

        // 🦁 3. OFFLINE IDENTITY (Green Button)
        // Connection: Opens LoginActivity (QR Generator for Offline Proof)
        findViewById<Button>(R.id.btnOfflineIdentity).setOnClickListener {
            if (IdentityStorage.hasIdentity()) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                Toast.makeText(this, "⚠️ Please Scan Passport First!", Toast.LENGTH_SHORT).show()
            }
        }

        // 🦁 4. TEST PROOF (Grey Button)
        // Connection: Opens VerifierActivity (Scanner) for testing
        findViewById<Button>(R.id.btnTestProof).setOnClickListener {
             startActivity(Intent(this, VerifierActivity::class.java))
        }
    }
}