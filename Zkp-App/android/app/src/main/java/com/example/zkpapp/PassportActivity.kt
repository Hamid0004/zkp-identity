package com.example.zkpapp

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PassportActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI for now
        statusText = TextView(this)
        statusText.text = "📲 Place Passport against phone back..."
        statusText.textSize = 24f
        statusText.setPadding(50, 50, 50, 50)
        setContentView(statusText)

        // 1. Initialize NFC Adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "This device does not support NFC!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 2. Enable NFC Listening when app is open
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        // 3. Disable NFC Listening when leaving app
        nfcAdapter?.disableForegroundDispatch(this)
    }

    // 4. THIS FUNCTION RUNS WHEN PASSPORT IS TOUCHED
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action || 
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            
            if (tag != null) {
                // Check if it supports IsoDep (Passport Standard)
                val isoDep = IsoDep.get(tag)
                if (isoDep != null) {
                    statusText.text = "✅ PASSPORT DETECTED!\n(Ready for Day 63 logic)"
                    // TODO: Call Rust function here later
                } else {
                    statusText.text = "❌ Tag detected, but not a Passport."
                }
            }
        }
    }
}