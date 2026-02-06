package com.example.zkpapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 1. Initialize UI
        val statusText = findViewById<TextView>(R.id.txtStatus)
        val progressBar = findViewById<ProgressBar>(R.id.loader)
        val titleText = findViewById<TextView>(R.id.lblTitle)

        // 2. Dummy Data (Offline Test ke liye)
        val mySecret = "User_Passport_Hash_123"
        val website = "google.com"
        
        // Random Challenge (Server ki zaroorat nahi, Local Generate kar rahe hain)
        val localChallenge = UUID.randomUUID().toString().substring(0, 8)

        // 3. UI Start State
        statusText.text = "🦁 Starting Offline Test...\nChallenge: $localChallenge"
        statusText.setTextColor(Color.DKGRAY)
        progressBar.visibility = View.VISIBLE

        // 4. ⚡ EXECUTE OFFLINE PROOF (No Internet Required)
        // Hum IO Dispatcher use karenge taaki UI freeze na ho
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 🦁 KEY FIX: Direct call to ZkAuth Wrapper (No ZkAuthManager!)
                // Yeh function Internet check nahi karega, seedha Math karega.
                val rawResult = ZkAuth.safeGenerateNullifier(
                    secret = mySecret,
                    domain = website,
                    challenge = localChallenge
                )

                // 5. Update UI on Main Thread
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE

                    if (rawResult.contains("|")) {
                        // ✅ SUCCESS CASE
                        val parts = rawResult.split("|")
                        val nullifier = parts[0]
                        val proof = parts[1]

                        statusText.text = "✅ OFFLINE PROOF GENERATED!\n\n" +
                                "Nullifier: $nullifier\n" +
                                "(Proof Size: ${proof.length} chars)\n\n" +
                                "⚠️ Pure Math. No Internet Used."
                        
                        statusText.setTextColor(Color.parseColor("#2E7D32")) // Green
                        
                        // Title Update (Optional, agar ID hai to)
                        titleText?.text = "Offline Test Passed 🛡️"
                        
                        Toast.makeText(applicationContext, "Math Works!", Toast.LENGTH_SHORT).show()

                    } else {
                        // ❌ ERROR CASE (e.g. Rust panic)
                        statusText.text = "❌ Calculation Failed:\n$rawResult"
                        statusText.setTextColor(Color.RED)
                    }
                }
            } catch (e: Exception) {
                // Crash Handler
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "🔥 App Crash: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }
    }
}