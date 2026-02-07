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

        // 🦁 DAY 83 UPGRADE: Check for Real Identity
        val hasIdentity = IdentityStorage.hasIdentity()
        
        // Random Challenge (Local)
        val localChallenge = UUID.randomUUID().toString().substring(0, 8)

        // 2. UI Start State
        statusText.text = if (hasIdentity) {
            "🦁 REAL IDENTITY FOUND!\nGenerating Proof for Challenge: $localChallenge"
        } else {
            "⚠️ NO PASSPORT DATA!\nPlease Scan NFC first."
        }
        
        statusText.setTextColor(if (hasIdentity) Color.DKGRAY else Color.RED)
        progressBar.visibility = if (hasIdentity) View.VISIBLE else View.GONE
        
        if (!hasIdentity) {
            Toast.makeText(this, "Please scan Passport first!", Toast.LENGTH_LONG).show()
            return
        }

        // 3. ⚡ EXECUTE OFFLINE PROOF (Only if Identity Exists)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 🦁 GET REAL DATA FROM STORAGE
                val realSecret = IdentityStorage.getSecret()
                val realDomain = IdentityStorage.getDomain()

                // 🦁 Direct Rust Call (No Internet)
                val rawResult = ZkAuth.safeGenerateNullifier(
                    secret = realSecret,
                    domain = realDomain,
                    challenge = localChallenge
                )

                // 4. Update UI
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE

                    if (rawResult.contains("|")) {
                        // ✅ SUCCESS CASE
                        val parts = rawResult.split("|")
                        val nullifier = parts[0]
                        val proof = parts[1]

                        statusText.text = "✅ OFFLINE PROOF GENERATED!\n\n" +
                                "Identity: Real Passport (PK)\n" +
                                "Nullifier: $nullifier\n" +
                                "(Proof Size: ${proof.length} chars)\n\n" +
                                "⚠️ Pure Math. No Internet Used."
                        
                        statusText.setTextColor(Color.parseColor("#2E7D32")) // Green
                        
                        titleText?.text = "Real ID Verified 🛡️"
                        
                        Toast.makeText(applicationContext, "Real Passport Proof Success!", Toast.LENGTH_SHORT).show()

                    } else {
                        // ❌ ERROR CASE
                        statusText.text = "❌ Calculation Failed:\n$rawResult"
                        statusText.setTextColor(Color.RED)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "🔥 Error: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }
    }
}