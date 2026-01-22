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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PassportActivity : AppCompatActivity() {

    // --------------------------------------------------
    // NFC
    // --------------------------------------------------
    private var nfcAdapter: NfcAdapter? = null

    // --------------------------------------------------
    // UI
    // --------------------------------------------------
    private lateinit var statusText: TextView
    private lateinit var simButton: Button
    private lateinit var progressBar: ProgressBar

    // --------------------------------------------------
    // JNI (future – Day 69+)
    // --------------------------------------------------
    external fun processPassportData(data: ByteArray): String

    // --------------------------------------------------
    // LIFECYCLE
    // --------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        checkHardware()
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null) {
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_MUTABLE
            )
            nfcAdapter?.enableForegroundDispatch(
                this, pendingIntent, null, null
            )
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    // --------------------------------------------------
    // NFC ENTRY (REAL MODE)
    // --------------------------------------------------
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) return

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            statusText.text = "❌ NFC tag detected, but not a passport chip"
            return
        }

        statusText.text = "🔄 ENGINE STARTING (REAL MODE)..."

        val engine = PassportEngine(
            mode = PassportMode.REAL,
            isoDep = isoDep
        )

        runEngine(engine, "Real Passport")
    }

    // --------------------------------------------------
    // SIMULATION MODE
    // --------------------------------------------------
    private fun runSimulation() {
        simButton.isEnabled = false
        statusText.text = "🧪 ENGINE STARTING (SIMULATION MODE)..."

        val engine = PassportEngine(
            mode = PassportMode.SIMULATION,
            isoDep = null
        )

        runEngine(engine, "Simulation")
    }

    // --------------------------------------------------
    // ENGINE RUNNER
    // --------------------------------------------------
    private fun runEngine(engine: PassportEngine, source: String) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ⚙️ THE MAIN WORK
                val passportBytes = engine.start()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text =
                        "✅ ENGINE FINISHED ($source)\n\n" +
                        "State: ${engine.state}\n" +
                        "Bytes: ${passportBytes.size}"
                    
                    simButton.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e("PassportActivity", "Engine failure", e)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "❌ ENGINE ERROR\n${e.message}"
                    simButton.isEnabled = true
                }
            }
        }
    }

    // --------------------------------------------------
    // HELPER FUNCTIONS (UI & HARDWARE)
    // --------------------------------------------------
    private fun setupUI() {
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
        simButton.setOnClickListener { runSimulation() }
        layout.addView(simButton)

        setContentView(layout)
    }

    private fun checkHardware() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
            // ✅ REAL MODE
            statusText.text = "📲 REAL MODE ACTIVE\n\nHold your Passport against the phone back."
            simButton.visibility = View.GONE
        } else {
            // ❌ SIMULATION MODE
            statusText.text = "⚠️ NO NFC DETECTED\n\nSwitched to Simulation Mode."
            simButton.visibility = View.VISIBLE
        }
    }
}