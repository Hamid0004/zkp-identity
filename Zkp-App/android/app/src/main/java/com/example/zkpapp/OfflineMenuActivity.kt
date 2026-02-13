package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class OfflineMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_menu) // 🦁 Make sure XML file exists

        // 1. TRANSMIT BUTTON (Generate QR)
        // Yeh LoginActivity ko "TRANSMIT" mode mein kholega
        val btnTransmit = findViewById<Button>(R.id.btnTransmit)
        btnTransmit.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.putExtra("MODE", "TRANSMIT") 
            startActivity(intent)
        }

        // 2. VERIFY BUTTON (Scan QR)
        // Yeh VerifierActivity ko kholega (Jo code aapne abhi share kiya tha)
        val btnVerify = findViewById<Button>(R.id.btnVerifyOffline)
        btnVerify.setOnClickListener {
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }
    }
}