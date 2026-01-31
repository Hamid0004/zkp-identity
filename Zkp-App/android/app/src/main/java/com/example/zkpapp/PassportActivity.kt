package com.example.zkpapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PassportActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val isNfcBusy = AtomicBoolean(false)

    // 🧠 NFC Anti-Spam Cooldown
    private val lastScanTime = AtomicLong(0)
    private val NFC_COOLDOWN_MS = 3000L

    private var session = PassportSession()

    private lateinit var statusText: TextView
    private lateinit var photoView: ImageView
    private lateinit var detailsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var camButton: Button
    private lateinit var simButton: Button

    // 📷 MRZ Camera Result
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult

            val rawMrz = result.data?.getStringExtra("MRZ_DATA") ?: return@registerForActivityResult
            val mrzInfo = MrzInfo(rawMrz, "PENDING", "PENDING", "PENDING")
            session = PassportSession(mrzInfo, SessionState.NFC_READY)

            updateStatus("📲 HOLD PHONE TO PASSPORT NOW", Color.BLUE)
            resetUI()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setupUI()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            updateStatus("⚠️ NFC not supported (Simulation Only)", Color.RED)
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

    private fun enableNfcDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            adapter.enableForegroundDispatch(
                this,
                pendingIntent,
                arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)),
                arrayOf(arrayOf(IsoDep::class.java.name))
            )
        }
    }

    // 📡 NFC EVENT
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val now = System.currentTimeMillis()
        if (now - lastScanTime.get() < NFC_COOLDOWN_MS) return
        lastScanTime.set(now)

        if (isNfcBusy.get()) return

        if (!SecurityGate.canStartNfc(session)) {
            Toast.makeText(this, "Scan MRZ first!", Toast.LENGTH_SHORT).show()
            return
        }

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val isoDep = tag?.let { IsoDep.get(it) } ?: return

        startEngine(PassportMode.REAL, isoDep)
    }

    private fun runSimulation() {
        if (!SecurityGate.canSimulate(session)) {
            Toast.makeText(this, "Scan MRZ first", Toast.LENGTH_SHORT).show()
            return
        }
        session = PassportSession()
        startEngine(PassportMode.SIMULATION, null)
    }

    // 🚀 ENGINE RUNNER
    private fun startEngine(mode: PassportMode, isoDep: IsoDep?) {
        isNfcBusy.set(true)
        progressBar.visibility = View.VISIBLE
        camButton.isEnabled = false
        simButton.isEnabled = false

        resetUI()
        updateStatus("🔄 READING DATA & PHOTO...", Color.DKGRAY)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                isoDep?.timeout = 8000
                isoDep?.connect()

                val engine = PassportEngine(mode, isoDep, session.mrzInfo?.raw)
                val passportData = engine.start()

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) handleSuccess(passportData)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) handleError(e)
                }
            } finally {
                try { isoDep?.close() } catch (_: Exception) {}
                isNfcBusy.set(false)
            }
        }
    }

    // ✅ SUCCESS (DAY 70 UPDATE: SHOW SOD STATUS)
    private fun handleSuccess(data: PassportData) {
        progressBar.visibility = View.GONE
        camButton.isEnabled = true
        simButton.isEnabled = true
        session = session.copy(state = SessionState.DONE)
        performHapticFeedback()

        updateStatus("✅ PASSPORT VERIFIED", Color.parseColor("#006400"))

        // 👇 Get JSON & SOD Size
        val rustJson = data.toRustJson()
        val sodSize = data.sodRaw?.size ?: 0
        val sodStatus = if (sodSize > 0) "✅ FOUND ($sodSize bytes)" else "❌ MISSING"

        // 👇 DISPLAY ON SCREEN (DEBUGGING)
        detailsText.text = """
            Name: ${data.firstName} ${data.lastName}
            Gender: ${data.gender}
            DOB: ${data.dateOfBirth}
            SOD: $sodStatus
            
            👇 RUST PAYLOAD (HIDDEN):
            $rustJson
        """.trimIndent()

        detailsText.visibility = View.VISIBLE

        data.facePhoto?.let { bitmap ->
            val safeBitmap = Bitmap.createScaledBitmap(bitmap, 300, 400, true)
            photoView.setImageBitmap(safeBitmap)
            photoView.visibility = View.VISIBLE
        }

        // 🔐 Auto wipe sensitive session after 10s
        lifecycleScope.launch {
            delay(10000)
            session = PassportSession()
        }

        // 🧠 Future ZKP hook
        SecurityGate.sendToRustForProof(data)
    }

    // ❌ ERROR
    private fun handleError(e: Exception) {
        progressBar.visibility = View.GONE
        camButton.isEnabled = true
        simButton.isEnabled = true
        session = session.copy(state = SessionState.ERROR)

        updateStatus("❌ Error: ${e.message}", Color.RED)
    }

    private fun updateStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun resetUI() {
        detailsText.text = ""
        detailsText.visibility = View.GONE
        photoView.setImageBitmap(null)
        photoView.visibility = View.GONE
    }

    private fun performHapticFeedback() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else v.vibrate(200)
        } catch (_: Exception) {}
    }

    // 🎨 UI SETUP
    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.WHITE)
        }

        statusText = TextView(this).apply {
            text = "Passport Scanner Pro"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }

        photoView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(350, 450).apply { setMargins(0, 30, 0, 30) }
            setBackgroundColor(Color.LTGRAY)
            visibility = View.GONE
        }

        detailsText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            visibility = View.GONE
        }

        progressBar = ProgressBar(this).apply { visibility = View.GONE }

        camButton = Button(this).apply {
            text = "📷 SCAN MRZ"
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

        layout.addView(statusText)
        layout.addView(photoView)
        layout.addView(detailsText)
        layout.addView(progressBar)
        layout.addView(camButton)
        layout.addView(spacer)
        layout.addView(simButton)

        setContentView(layout)
    }
}