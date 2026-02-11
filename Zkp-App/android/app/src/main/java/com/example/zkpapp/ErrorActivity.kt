package com.example.zkpapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ErrorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI created programmatically to avoid XML errors
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)
        layout.setBackgroundColor(android.graphics.Color.parseColor("#8B0000")) // Dark Red

        val title = TextView(this)
        title.text = "🦁 APP CRASHED!"
        title.textSize = 24f
        title.setTextColor(android.graphics.Color.WHITE)
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        layout.addView(title)

        val errorView = TextView(this)
        val errorText = intent.getStringExtra("error_log") ?: "Unknown Error"
        errorView.text = errorText
        errorView.textSize = 12f
        errorView.setTextColor(android.graphics.Color.YELLOW)
        errorView.setPadding(0, 20, 0, 20)
        layout.addView(errorView)

        val copyButton = Button(this)
        copyButton.text = "COPY ERROR"
        copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", errorText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }
        layout.addView(copyButton)

        val restartButton = Button(this)
        restartButton.text = "RESTART APP"
        restartButton.setOnClickListener {
            val i = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
            i?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
            finish()
        }
        layout.addView(restartButton)

        setContentView(layout)
    }
}