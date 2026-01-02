package com.example.zkpapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    // 👇 1. Rust Library Load karna
    // Yeh App start hote hi 'libzkp_mobile_logic.so' ko dhoond kar memory mein layega
    companion object {
        init {
            System.loadLibrary("zkp_mobile_logic")
        }
    }

    // 👇 2. Rust Function ka Wada (Declaration)
    // Hum Kotlin ko bata rahe hain ke ye function bahar (Rust mein) mojood hai
    external fun stringFromRust(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.myTextView)
        val button = findViewById<Button>(R.id.myButton)

        button.setOnClickListener {
            // 👇 3. Rust ko Call Karein!
            // Jab button dabega, hum Rust se text mangwayenge
            val messageFromRust = stringFromRust()
            
            textView.text = messageFromRust
            Toast.makeText(this, "Rust Connected! 🦀⚡", Toast.LENGTH_SHORT).show()
        }
    }
}
