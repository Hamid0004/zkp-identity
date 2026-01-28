package com.example.zkpapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.zkpapp.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

class PassportActivity : AppCompatActivity() {

    // ============================
    // VARIABLES
    // ============================
    private var nfcAdapter: NfcAdapter? = null
    
    // 🔒 Thread-safe flag to prevent double scanning crashes
    private val isNfcBusy = AtomicBoolean(false)

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

            // MRZ Capture karte hi info populate karo
            val mrzInfo = MrzInfo(
                raw = rawMrz,
                documentNumber = "PENDING",
                dateOfBirth = "PENDING",
                expiryDate = "PENDING"
            )

            session = PassportSession(
                mrzInfo = mrzInfo,
                state = SessionState.NFC_READY
            )

            mrzInfoText.text = "✅ MRZ CAPTURED (Key Generated)\nWaiting for Chip..."
            mrzInfoText.setTextColor(Color.parseColor("#0A7D00")) // Dark Green
            mrzInfoText.visibility = View.VISIBLE

            statusText.text = "📲 HOLD PHONE TO PASSPORT NOW"
            statusText.setTextColor(Color.BLUE)
            
            Toast.makeText(this, "Key Ready. Tap Passport.", Toast.LENGTH_SHORT).show()
        }

    // ============================
    // LIFECYCLE
    // ============================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🛡️ SECURITY: Prevent Screenshots of Passport Data
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setupUI()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            statusText.text = "⚠️ NFC not supported (Simulation only)"
            camButton.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    // ✅ Robust NFC Dispatch Setup (Android 12+ Compatible)
    private fun enableNfcDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT 
            else 
                PendingIntent.FLAG_UPDATE_CURRENT

            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
            val techList = arrayOf(arrayOf(IsoDep::class.java.name))

            adapter.enableForegroundDispatch(this, pendingIntent, filters, techList)
        }
    }

    // ============================
    // NFC HANDLING (SECURE GATED)
    // ============================
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // 1. Check if Busy (Debounce)
        if (isNfcBusy.get()) return

        // 2. Gate Check
        if (!SecurityGate.canStartNfc(session)) {
            Toast.makeText(this, "Scan MRZ first!", Toast.LENGTH_LONG).show()
            statusText.text = "❌ MRZ REQUIRED FIRST"
            statusText.setTextColor(Color.RED)
            return
        }

        // 3. Tag Detection
        if (intent.action !in listOf(NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TAG_DISCOVERED)) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val isoDep = tag?.let { IsoDep.get(it) }

        if (isoDep == null) {
            statusText.text = "❌ Not a passport chip (IsoDep missing)"
            return
        }

        // 4. Start Engine
        isNfcBusy.set(true) // Lock NFC
        session = session.copy(state = SessionState.READING)
        
        statusText.text = "🔄 CONNECTING TO CHIP...\nDO NOT MOVE PASSPORT"
        statusText.setTextColor(Color.DKGRAY)
        
        startEngine(PassportMode.REAL, isoDep)
    }

    // ============================
    // SIMULATION
    // ============================
    private fun runSimulation() {
        if (!SecurityGate.canSimulate(session)) {
            Toast.makeText(this, "Simulation disabled after MRZ scan", Toast.LENGTH_LONG).show()
            return
        }
        session = PassportSession() // Reset
        startEngine(PassportMode.SIMULATION, null)
    }

    // ============================
    // ENGINE RUNNER (The Brain)
    // ============================
    private fun startEngine(mode: PassportMode, isoDep: IsoDep?) {
        progressBar.visibility = View.VISIBLE
        camButton.isEnabled = false
        simButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Connection Timeout extend karo for real chips
                isoDep?.timeout = 5000 

                val engine = PassportEngine(
                    mode = mode,
                    isoDep = isoDep,
                    mrz = session.mrzInfo?.raw
                )

                // ⚙️ JMRTD Processing
                val dataBytes = engine.start()
                val resultString = String(dataBytes, Charsets.UTF_8)

                withContext(Dispatchers.Main) {
                    handleSuccess(resultString, mode)
                }

            } catch (e: Exception) {
                Log.e("PassportActivity", "Engine error", e)
                withContext(Dispatchers.Main) {
                    handleError(e)
                }
            } finally {
                // Always close resource
                try { isoDep?.close() } catch (e: Exception) {} 
            }
        }
    }

    // ✅ SUCCESS HANDLER
    private fun handleSuccess(result: String, mode: PassportMode) {
        isNfcBusy.set(false) // Unlock NFC
        progressBar.visibility = View.GONE
        camButton.isEnabled = true
        simButton.isEnabled = true
        session = session.copy(state = SessionState.DONE)
        
        performHapticFeedback()

        // Parse: "SUCCESS|Name|DocNum"
        val parts = result.split("|")
        val status = parts[0]

        if (status == "SUCCESS" || status == "SIMULATION") {
            val name = parts.getOrElse(1) { "Unknown" }
            val docNum = parts.getOrElse(2) { "Unknown" }

            statusText.text = "🎉 CHIP UNLOCKED!\n\n" +
                    "👤 Name: $name\n" +
                    "📄 Doc: $docNum"
            
            statusText.setTextColor(Color.parseColor("#006400")) // Dark Green
            statusText.setBackgroundColor(Color.parseColor("#E8F5E9"))
        } else {
            statusText.text = "⚠️ Data Read but Unknown Format"
        }
    }

    // ❌ ERROR HANDLER
    private fun handleError(e: Exception) {
        isNfcBusy.set(false) // Unlock NFC
        progressBar.visibility = View.GONE
        camButton.isEnabled = true
        simButton.isEnabled = true
        session = session.copy(state = SessionState.ERROR)

        val errorMsg = e.message ?: "Unknown Error"
        
        statusText.setTextColor(Color.RED)
        statusText.setBackgroundColor(Color.TRANSPARENT)

        if (errorMsg.contains("Tag was lost")) {
            statusText.text = "❌ CONNECTION LOST\nDon't move the passport!"
        } else if (errorMsg.contains("Access Denied") || errorMsg.contains("BAC")) {
            statusText.text = "❌ AUTH FAILED\nMRZ doesn't match Chip."
        } else {
            statusText.text = "❌ ERROR: $errorMsg"
        }
    }

    // 📳 VIBRATION
    private fun performHapticFeedback() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(200)
        }
    }

    // ============================
    // UI SETUP (Programmatic)
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
            setPadding(0, 0, 0, 20)
        }

        statusText = TextView(this).apply {
            text = "1. Scan MRZ (Camera)\n2. Tap Passport (NFC)"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.DKGRAY)
            setPadding(20, 40, 20, 40)
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