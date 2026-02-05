package com.example.zkpapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // =========================================================
        // 🔵 BUTTON 1: WEB LOGIN (Day 81 - Relay)
        // =========================================================
        val btnWebLogin: Button = findViewById(R.id.btnWebLogin)
        btnWebLogin.setOnClickListener {
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }

        // =========================================================
        // 🟠 BUTTON 2: CREATE IDENTITY (Passport Phase 6)
        // =========================================================
        val btnPassport: Button = findViewById(R.id.btnPassport)
        btnPassport.setOnClickListener {
            // 🦁 FIX: Camera nahi, pehle Passport Dashboard khulega
            val intent = Intent(this, PassportActivity::class.java) 
            startActivity(intent)
        }

        // =========================================================
        // ⚪ BUTTON 3: OFFLINE TEST (Debug Tool)
        // =========================================================
        val btnTest: Button = findViewById(R.id.btnTest)
        btnTest.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }
}