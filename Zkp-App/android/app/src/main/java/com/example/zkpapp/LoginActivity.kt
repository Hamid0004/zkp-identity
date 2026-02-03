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
import java.util.UUID // 🆕 Day 77: Random ID Generator

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 1. Initialize UI Elements
        val statusText = findViewById<TextView>(R.id.txtStatus)
        val progressBar = findViewById<ProgressBar>(R.id.loader)
        val titleText = findViewById<TextView>(R.id.lblTitle) 

        // 2. User Data (Simulation)
        val mySecret = "User_Passport_Hash_123"
        val website = "google.com"

        // 🆕 DAY 77 SECURITY: Generate Random Challenge
        // Yeh har baar alag hoga, isliye hacker purana hash copy nahi kar sakta.
        val serverChallenge = UUID.randomUUID().toString().substring(0, 8)

        Log.d("ZkAuth", "🎲 Challenge Created: $serverChallenge")

        // 3. UI Start State (Show Loading)
        // User ko dikhao ke hum challenge ke sath bind kar rahe hain
        statusText.text = "🔒 Binding Identity to Challenge: $serverChallenge..."
        statusText.setTextColor(Color.DKGRAY)
        progressBar.visibility = View.VISIBLE 

        // 4. Background Simulation
        Handler(Looper.getMainLooper()).postDelayed({

            // 👇 DAY 77 UPDATE: Pass 3 Arguments (Secret, Domain, Challenge)
            val result = ZkAuth.safeGenerateNullifier(mySecret, website, serverChallenge)

            // 🛑 Stop Loading
            progressBar.visibility = View.GONE

            // 5. Result Handling
            if (result.contains("⚠️") || result.contains("🔥")) {
                // Error Case
                statusText.text = result
                statusText.setTextColor(Color.RED)
                Log.e("ZkAuth", "Failed: $result")
            } else {
                // Success Case
                statusText.text = "✅ Secure Hash:\n$result"
                statusText.setTextColor(Color.parseColor("#4CAF50")) // Green

                // Update Title
                titleText?.text = "Identity Verified! 🛡️"

                Log.d("ZkAuth", "Success: $result")
                Toast.makeText(this, "Secure Login Verified!", Toast.LENGTH_SHORT).show()
            }

        }, 1000) // 1 Second delay
    }
}