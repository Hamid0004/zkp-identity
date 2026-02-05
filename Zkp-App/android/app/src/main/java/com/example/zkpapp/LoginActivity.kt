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
import java.util.UUID 

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

        // 🎲 DAY 77/81 SECURITY: Random Challenge
        // Yeh confirm karta hai ke har baar Proof naya banta hai (Replay Attack Protection)
        val serverChallenge = UUID.randomUUID().toString().substring(0, 8)
        Log.d("ZkAuth", "🎲 Challenge Created: $serverChallenge")

        // 3. UI Start State
        statusText.text = "🔒 Generating ZK Proof for Challenge:\n$serverChallenge..."
        statusText.setTextColor(Color.DKGRAY)
        progressBar.visibility = View.VISIBLE 

        // 4. Background Simulation (Run Rust Logic)
        Handler(Looper.getMainLooper()).postDelayed({

            try {
                // 🦁 CALLING NEW RUST LOGIC (Day 78+)
                // Ab ye return karega: "NullifierHash | Base64ProofString"
                val rawResult = ZkAuth.generateSecureNullifier(mySecret, website, serverChallenge)

                // 🛑 Stop Loading
                progressBar.visibility = View.GONE

                // 5. Result Parsing (Split Nullifier & Proof)
                if (rawResult.contains("|")) {
                    val parts = rawResult.split("|")
                    val nullifier = parts[0] // Chota Hash (Display ke liye)
                    val proof = parts[1]     // Bada Proof (Server ke liye)

                    // Success UI
                    statusText.text = "✅ Secure Nullifier Generated:\n$nullifier\n\n(Proof Size: ${proof.length} chars)"
                    statusText.setTextColor(Color.parseColor("#2E7D32")) // Dark Green

                    titleText?.text = "Identity Verified! 🛡️"
                    
                    Log.d("ZkAuth", "✅ Full Proof Generated. Size: ${proof.length}")
                    Toast.makeText(this, "Proof Generation Success!", Toast.LENGTH_SHORT).show()

                } else if (rawResult.startsWith("🔥") || rawResult.contains("Error")) {
                    // Error Case
                    statusText.text = "❌ Error: $rawResult"
                    statusText.setTextColor(Color.RED)
                } else {
                    // Unexpected Format
                    statusText.text = "⚠️ Unknown Format:\n$rawResult"
                    statusText.setTextColor(Color.MAGENTA)
                }

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = "🔥 Crash: ${e.message}"
                statusText.setTextColor(Color.RED)
            }

        }, 500) // Fast Response
    }
}