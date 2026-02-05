package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 🦁 NOTE: Library load ab 'ZkAuth' object handle karta hai.
    // Yahan dobara load karne ki zaroorat nahi hai.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // =========================================================
        // 🔵 BUTTON 1: WEB LOGIN (The Relay Magic)
        // =========================================================
        // Yeh Day 81 ka Hero hai. QR Scan karega aur Server par Proof bhejega.
        val btnWebLogin: Button = findViewById(R.id.btnWebLogin)
        btnWebLogin.setOnClickListener {
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }

        // =========================================================
        // 🟠 BUTTON 2: CREATE IDENTITY (Passport Scan)
        // =========================================================
        // Yeh Camera kholega aur MRZ read karega.
        val btnPassport: Button = findViewById(R.id.btnPassport)
        btnPassport.setOnClickListener {
            // Hum direct CameraActivity call kar rahe hain (Day 81 Logic)
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        }

        // =========================================================
        // ⚪ BUTTON 3: OFFLINE TEST (Debug Tool)
        // =========================================================
        // Yeh purana screen kholega jahan Proof generate hota hua dikhta hai.
        // Internet ke bina test karne ke liye best hai.
        val btnTest: Button = findViewById(R.id.btnTest)
        btnTest.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }
}