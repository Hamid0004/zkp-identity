package com.example.zkpapp

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 1. Initialize UI Elements
        // Make sure IDs match activity_login.xml
        val statusText = findViewById<TextView>(R.id.txtStatus)
        val progressBar = findViewById<ProgressBar>(R.id.loader)
        val titleText = findViewById<TextView>(R.id.lblTitle) 

        // 2. Test Inputs (Simulation)
        val mySecret = "User_Passport_Hash_123"
        val website = "google.com"

        Log.d("ZkAuth", "🚀 Sending Request to Rust...")

        // 3. UI Start State (Show Loading)
        statusText.text = "⏳ Generating Zero-Knowledge Proof..."
        statusText.setTextColor(Color.DKGRAY)
        progressBar.visibility = View.VISIBLE 

        // 4. Background Simulation (Handler)
        // Hum 1 second ka delay dete hain taaki 'Loading' feel aaye aur phir result dikhaye
        Handler(Looper.getMainLooper()).postDelayed({

            // 👇 SAFE CALL (Crash Proof)
            val result = ZkAuth.safeGenerateNullifier(mySecret, website)

            // 🛑 Stop Loading (Hide Spinner)
            progressBar.visibility = View.GONE

            // 5. Result Handling
            if (result.contains("⚠️") || result.contains("🔥")) {
                // Error Case
                statusText.text = result
                statusText.setTextColor(Color.RED)
                Log.e("ZkAuth", "Failed: $result")
            } else {
                // Success Case
                // Result text thoda saaf dikhate hain
                
                statusText.text = "✅ $result"
                statusText.setTextColor(Color.parseColor("#4CAF50")) // Nice Green Color
                
                // Title update for feedback
                titleText?.text = "Identity Verified! 🎉"
                
                Log.d("ZkAuth", "Success: $result")
                Toast.makeText(this, "Login Successful!", Toast.LENGTH_SHORT).show()
            }

        }, 1000) // 1000ms = 1 Second delay
    }
}