package com.example.zkpapp

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PassportActivity : AppCompatActivity() {

    // ============================
    // STATE & NFC
    // ============================
    private var nfcAdapter: NfcAdapter? = null
    private var scannedMrz: String? = null

    // ============================
    // UI ELEMENTS
    // ============================
    private lateinit var statusText: TextView
    private lateinit var mrzInfoText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var camButton: Button
    private lateinit var simButton: Button

    // ============================
    // MODERN CAMERA RESULT API
    // ============================
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val mrz = result.data?.getStringExtra("MRZ_DATA")
                if (!mrz.isNullOrBlank()) {
                    scannedMrz = mrz.trim()

                    Log.d("PassportActivity", "MRZ captured: $mrz")

                    mrzInfoText.text = "✅ MRZ CAPTURED\n$mrz"
                    mrzInfoText.setTextColor(Color.parseColor("#0A7D00")) // Dark Green
                    mrzInfoText.visibility = View.VISIBLE

                    statusText.text = "Now hold passport against back of phone (NFC)"
                    Toast.makeText(this, "MRZ Saved! Ready for NFC.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "MRZ not detected", Toast.LENGTH_SHORT).show()
                }
            }
        }

    // ============================
    // LIFECYCLE
    // ============================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            statusText.text = "⚠️ NFC not supported (Simulation only)"
            camButton.isEnabled = false // Disable camera if no NFC (optional)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            it.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    // ============================
    // NFC HANDLING (REAL MODE)
    // ============================
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Rule: MRZ must be scanned first
        if (scannedMrz == null) {
            Toast.makeText(this, "⚠️ Please Scan MRZ First!", Toast.LENGTH_LONG).show()
            statusText.text = "❌ SCAN MRZ FIRST\nPassport logic needs MRZ to unlock chip."
            return
        }

        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) return
        
        val isoDep = IsoDep.get(tag)

        if (isoDep == null) {
            statusText.text = "❌ Not a passport chip (IsoDep missing)"
            return
        }

        statusText.text = "🔄 NFC Connected. Starting Engine..."
        
        // Pass the scanned MRZ to the engine
        startEngine(PassportMode.REAL, isoDep)
    }

    // ============================
    // SIMULATION
    // ============================
    private fun runSimulation() {
        startEngine(PassportMode.SIMULATION, null)
    }

    // ============================
    // ENGINE RUNNER
    // ============================
    private fun startEngine(mode: PassportMode, isoDep: IsoDep?) {
        progressBar.visibility = View.VISIBLE
        camButton.isEnabled = false
        simButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ensure PassportEngine accepts 'mrz' in constructor
                val engine = PassportEngine(
                    mode = mode,
                    isoDep = isoDep,
                    mrz = scannedMrz // Passing the MRZ
                )

                val data = engine.start()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text =
                        "✅ READ COMPLETE\n" +
                        "Mode: $mode\n" +
                        "Bytes Read: ${data.size}\n" +
                        "State: ${engine.state}"

                    camButton.isEnabled = true
                    simButton.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e("PassportActivity", "Engine error", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "❌ ERROR: ${e.message}"
                    camButton.isEnabled = true
                    simButton.isEnabled = true
                }
            }
        }
    }

    // ============================
    // UI SETUP (PROGRAMMATIC)
    // ============================
    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Passport Scanner"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "1. Scan MRZ (Camera)\n2. Tap Passport (NFC)"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 40, 0, 40)
        }

        mrzInfoText = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, 0, 0, 40)
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, 40)
        }

        camButton = Button(this).apply {
            text = "📷 SCAN PASSPORT (MRZ)"
            setBackgroundColor(Color.parseColor("#6200EE"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                // Launch Camera using new API
                cameraLauncher.launch(
                    Intent(this@PassportActivity, CameraActivity::class.java)
                )
            }
        }

        // Spacer
        val spacer = View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(1, 40) 
        }

        simButton = Button(this).apply {
            text = "🧪 SIMULATE SCAN"
            setOnClickListener { runSimulation() }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(mrzInfoText)
        layout.addView(progressBar)
        layout.addView(camButton)
        layout.addView(spacer)
        layout.addView(simButton)

        setContentView(layout)
    }
}