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
        setContentView(R.layout.activity_login) // Iska layout XML banana padega

        // UI Elements dhoondein
        // (Filhal assume kar rahe hain ke XML mein ye IDs hongi)
        // val btnLogin = findViewById<Button>(R.id.btnLogin)
        // val txtResult = findViewById<TextView>(R.id.txtResult)

        // 🦁 TESTING DAY 76 (Console Test)
        // Jab tak XML ready nahi hai, hum Logcat mein test karte hain:
        
        val testSecret = "Hamid_Passport_Secret_Key"
        val testWebsite = "facebook.com"

        Log.d("ZkAuth", "Generating Nullifier for $testWebsite...")
        
        // 👇 ASLI JAADU YAHAN HAI
        val nullifier = ZkAuth.generateNullifier(testSecret, testWebsite)
        
        Log.d("ZkAuth", "🦁 RESULT: $nullifier")
        
        // Screen par dikhane ke liye Toast
        Toast.makeText(this, "Nullifier Generated! Check Logs", Toast.LENGTH_LONG).show()
    }
}