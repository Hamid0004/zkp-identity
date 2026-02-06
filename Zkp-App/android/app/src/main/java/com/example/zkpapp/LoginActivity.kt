package com.example.zkpapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.zkpapp.auth.ZkAuthManager
import kotlinx.coroutines.launch
import java.util.UUID

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val status = findViewById<TextView>(R.id.txtStatus)
        val loader = findViewById<ProgressBar>(R.id.loader)

        val challenge = UUID.randomUUID().toString().substring(0, 8)

        loader.visibility = View.VISIBLE
        status.text = "🔒 Waiting for ZK Login…"
        status.setTextColor(Color.DKGRAY)

        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = challenge,
                onStatus = {
                    status.text = it
                },
                onSuccess = {
                    loader.visibility = View.GONE
                    status.text = "✅ Login Successful"
                    status.setTextColor(Color.parseColor("#2E7D32"))
                },
                onError = {
                    loader.visibility = View.GONE
                    status.text = it
                    status.setTextColor(Color.RED)
                }
            )
        }
    }
}