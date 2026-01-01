package com.example.zkpapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Screen se cheezein dhoondo
        val textView = findViewById<TextView>(R.id.myTextView)
        val button = findViewById<Button>(R.id.myButton)

        // Jab Button dabaya jaye
        button.setOnClickListener {
            textView.text = "You Clicked Me! 🎉"
            Toast.makeText(this, "Success!", Toast.LENGTH_SHORT).show()
        }
    }
}
