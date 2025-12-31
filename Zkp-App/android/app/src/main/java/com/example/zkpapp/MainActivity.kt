package com.example.zkpapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 1. Loading the Rust Engine
    // Jaise hi App khulegi, yeh "librust_layer.so" ko memory mein load karega
    companion object {
        init {
            System.loadLibrary("rust_layer")
        }
    }

    // 2. Declaring the Rust Function
    // Hum Kotlin ko bata rahe hain ke "yeh function bahar (native) se aayega"
    private external fun runZkp(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI setup (Programmatically, without XML for now)
        val textView = TextView(this)
        textView.textSize = 20f
        textView.setPadding(50, 50, 50, 50)
        textView.text = "⏳ Generating Zero-Knowledge Proof..."
        setContentView(textView)

        // 3. Calling the Rust Function (Background Thread is better, but simple for now)
        // Yeh line Rust ke 'lib.rs' mein jayegi aur magic karegi
        val resultMessage = runZkp()

        // 4. Updating UI with Result
        textView.text = resultMessage
    }
}