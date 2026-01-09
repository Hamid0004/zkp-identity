package com.example.zkpapp

import android.content.Intent // Import for switching screens
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 1. Loading the Rust Engine
    companion object {
        init {
            System.loadLibrary("rust_layer")
        }
    }

    // 2. Declaring the Rust Function
    private external fun runZkp(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // IMPORTANT: Connect to the XML layout from Step 2
        setContentView(R.layout.activity_main)

        // UI Elements ko find karein
        val txtStatus: TextView = findViewById(R.id.sample_text)
        val btnMagic: Button = findViewById(R.id.btn_magic)
        val btnScan: Button = findViewById(R.id.btn_scan) // Step 5: New Button

        // 3. Magic Button Logic (Proof Generation)
        btnMagic.setOnClickListener {
            txtStatus.text = "⚡ Computing ZKP..."
            // Rust function call
            val resultMessage = runZkp()
            txtStatus.text = resultMessage
            // (Note: QR Code generation logic usually goes here)
        }

        // 4. Scan Button Logic (Step 5)
        btnScan.setOnClickListener {
            // Verifier Activity kholne ka code
            val intent = Intent(this, VerifierActivity::class.java)
            startActivity(intent)
        }
    }
}