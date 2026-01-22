package com.example.zkpapp
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
class PassportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btn = Button(this).apply {
             text = "📷 SCAN PASSPORT (MRZ)"
             setOnClickListener { startActivity(Intent(this@PassportActivity, CameraActivity::class.java)) }
        }
        layout.addView(btn)
        setContentView(layout)
    }
}
