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
import com.example.zkpapp.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PassportActivity : AppCompatActivity() {

    // ============================
    // NFC
    // ============================
    private var nfcAdapter: NfcAdapter? = null

    // 🔐 Central secure session
    private var session = PassportSession()

    // ============================
    // UI ELEMENTS
    // ============================
    private lateinit var statusText: TextView
    private lateinit var mrzInfoText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var camButton: Button
    private lateinit var simButton: Button

    // ============================
    // CAMERA RESULT (MRZ → SESSION)
    // ============================
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult

            val rawMrz = result.data?.getStringExtra("MRZ_DATA") ?: return@registerForActivityResult

            val mrzInfo = MrzInfo(
                raw = rawMrz,
                documentNumber = rawMrz.take(9),
                dateOfBirth = "UNKNOWN",
                expiryDate = "UNKNOWN"
            )

            session = PassportSession(
                mrzInfo = mrzInfo,
                state = SessionState.NFC_READY
            )

            mrzInfoText.text = "✅ MRZ CAPTURED\n${mrzInfo.raw}"
            mrzInfoText.setTextColor(Color.parseColor("#0A7D00"))
            mrzInfoText.visibility = View.VISIBLE

            statusText.text = "Now hold passport against back of phone (NFC)"
            Toast.makeText(this, "MRZ validated. NFC unlocked.", Toast.LENGTH_SHORT).show()
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
            camButton.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            val intent = Intent(this, javaClass).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
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
    // NFC HANDLING (SECURE GATED)
    // ============================
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (!SecurityGate.canStartNfc(session)) {
            Toast.makeText(this, "Scan MRZ first", Toast.LENGTH_LONG).show()
            statusText.text = "❌ MRZ REQUIRED BEFORE NFC"
            return
        }

        if (intent.action !in listOf(
                NfcAdapter.ACTION_TECH_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED
            )
        ) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val isoDep = tag?.let { IsoDep.get(it) }

        if (isoDep == null) {
            statusText.text = "❌ Not a passport chip"
            return
        }

        session = session.copy(state = SessionState.READING)
        statusText.text = "🔄 NFC Connected. Reading passport..."
        startEngine(PassportMode.REAL, isoDep)
    }

    // ============================
    // SIMULATION (LOCKED BY GATE)
    // ============================
    private fun runSimulation() {
        if (!SecurityGate.canSimulate(session)) {
            Toast.makeText(this, "Simulation disabled after MRZ scan", Toast.LENGTH_LONG).show()
            return
        }

        session = PassportSession() // Reset to IDLE
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
                val engine = PassportEngine(
                    mode = mode,
                    isoDep = isoDep,
                    mrz = session.mrzInfo?.raw
                )

                val data = engine.start()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    session = session.copy(state = SessionState.DONE)

                    statusText.text =
                        "✅ READ COMPLETE\nMode: $mode\nBytes Read: ${data.size}\nState: ${engine.state}"

                    camButton.isEnabled = true
                    simButton.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e("PassportActivity", "Engine error", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    session = session.copy(state = SessionState.ERROR)

                    statusText.text = "❌ ERROR: ${e.message}"
                    camButton.isEnabled = true
                    simButton.isEnabled = true
                }
            }
        }
    }

    // ============================
    // UI SETUP
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

        progressBar = ProgressBar(this).apply { visibility = View.GONE }

        camButton = Button(this).apply {
            text = "📷 SCAN PASSPORT (MRZ)"
            setBackgroundColor(Color.parseColor("#6200EE"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                cameraLauncher.launch(Intent(this@PassportActivity, CameraActivity::class.java))
            }
        }

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }

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
