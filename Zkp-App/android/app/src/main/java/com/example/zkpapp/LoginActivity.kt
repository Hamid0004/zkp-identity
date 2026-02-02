package com.example.zkpapp

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // UI Elements (Make sure IDs match your XML)
        // XML mein: btnGenerate, txtStatus hone chahiye.
        // Agar XML basic hai, toh hum temporary testing kar rahe hain:

        val statusText = findViewById<TextView>(R.id.txtStatus) // From XML
        
        // 🦁 REAL TEST LOGIC
        // Maan lein user ne ye type kiya:
        val mySecret = "User_Passport_Hash_123"
        val website = "google.com" 

        Log.d("ZkAuth", "🚀 Sending Request to Rust...")
        statusText.text = "⏳ Processing..."

        // 👇 SAFE CALL (Crash Proof)
        val result = ZkAuth.safeGenerateNullifier(mySecret, website)

        // Result handle karna
        if (result.contains("⚠️") || result.contains("🔥")) {
            // Error handling
            statusText.text = result
            statusText.setTextColor(android.graphics.Color.RED)
            Log.e("ZkAuth", "Failed: $result")
        } else {
            // Success
            statusText.text = "✅ $result"
            statusText.setTextColor(android.graphics.Color.GREEN)
            Log.d("ZkAuth", "Success: $result")
            Toast.makeText(this, "Nullifier Generated!", Toast.LENGTH_SHORT).show()
        }
    }
}