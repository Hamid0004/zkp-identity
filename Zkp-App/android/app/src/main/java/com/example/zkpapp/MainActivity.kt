package com.example.zkpapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.graphics.Color // 👈 Color add kiya

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("zkp_mobile_logic")
        }
    }

    external fun stringFromRust(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.myTextView)
        val button = findViewById<Button>(R.id.myButton)

        button.setOnClickListener {
            // 1. Rust ko call karo
            val message = stringFromRust()
            
            // 2. Logic Check: Kya Error hai?
            if (message.startsWith("❌ Error")) {
                textView.setTextColor(Color.RED) // Danger Color
            } else {
                textView.setTextColor(Color.parseColor("#FF6200EE")) // Normal Purple
            }

            textView.text = message
        }
    }
}
