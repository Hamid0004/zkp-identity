package com.example.zkpapp

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class PassportActivity : AppCompatActivity() {

    // NFC Adapter (Hardware Link)
    private var nfcAdapter: NfcAdapter? = null

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var simButton: Button
    private lateinit var progressBar: ProgressBar

    // 🔗 RUST BRIDGE
    external fun processPassportData(data: ByteArray): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🏗️ DYNAMIC UI SETUP
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 100, 50, 50)
        layout.gravity = Gravity.CENTER_HORIZONTAL

        statusText = TextView(this)
        statusText.textSize = 20f
        statusText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        layout.addView(statusText)

        progressBar = ProgressBar(this)
        progressBar.visibility = View.GONE
        layout.addView(progressBar)

        simButton = Button(this)
        simButton.text = "🛠️ SIMULATE SCAN (TEST MODE)"
        simButton.visibility = View.GONE
        layout.addView(simButton)

        setContentView(layout)

        // 🔍 CHECK HARDWARE
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
            // ✅ REAL MODE
            statusText.text = "📲 REAL MODE ACTIVE\n\nHold your Passport against the phone back."
            simButton.visibility = View.GONE
        } else {
            // ❌ SIMULATION MODE
            statusText.text = "⚠️ NO NFC DETECTED\n\nSwitched to Simulation Mode.\nClick button below to test Rust logic."
            simButton.visibility = View.VISIBLE
        }

        // Simulation button click
        simButton.setOnClickListener {
            simButton.isEnabled = false // Disable after first click
            runSimulation()
        }
    }

    // ---------------------------------------------------------
    // 🟢 PATH A: REAL NFC HANDLING
    // ---------------------------------------------------------
    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null) {
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {

            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                val isoDep = IsoDep.get(tag)
                if (isoDep != null) {
                    statusText.text = "✅ PASSPORT CHIP DETECTED!\nReading Data..."
                    // Placeholder bytes until JMRTD integration
                    val dummyRealBytes = ByteArray(10) { 0xAA.toByte() }
                    sendToRust(dummyRealBytes, "Real Chip")
                } else {
                    statusText.text = "❌ Tag detected, but not a Passport chip."
                }
            }
        }
    }

    // ---------------------------------------------------------
    // 🔵 PATH B: SIMULATION HANDLING
    // ---------------------------------------------------------
    private fun runSimulation() {
        statusText.text = "⏳ Generating Fake Passport Data..."
        val fakeBytes = ByteArray(1024) { 0xFF.toByte() }
        sendToRust(fakeBytes, "Simulation")
    }

    // ---------------------------------------------------------
    // 🔗 SHARED: SEND TO RUST ASYNC
    // ---------------------------------------------------------
    private fun sendToRust(dataBytes: ByteArray, source: String) {
        progressBar.visibility = View.VISIBLE
        statusText.text = "⏳ Processing $source Data..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = processPassportData(dataBytes)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "✅ SUCCESS ($source)\n\nRust Says:\n$result"
                }
            } catch (e: Exception) {
                Log.e("PassportActivity", "Rust processing error", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "❌ ERROR ($source)\n${e.message}"
                }
            }
        }
    }
}
