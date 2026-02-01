package com.example.zkpapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PassportActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val isNfcBusy = AtomicBoolean(false)
    private val lastScanTime = AtomicLong(0)
    private val NFC_COOLDOWN_MS = 3000L

    private var session = PassportSession()
    private var rustJob: Job? = null

    private lateinit var statusText: TextView
    private lateinit var photoView: ImageView
    private lateinit var detailsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var camButton: Button
    private lateinit var simButton: Button

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

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {}
    }

    private fun enableNfcDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

            adapter.enableForegroundDispatch(
                this,
                pendingIntent,
                arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)),
                arrayOf(arrayOf(IsoDep::class.java.name))
            )
        }
    }

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

        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        val isoDep = IsoDep.get(tag) ?: run {
            updateStatus("❌ Not an e-passport chip", Color.RED)
            return
        }

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

    private fun startEngine(mode: PassportMode, isoDep: IsoDep?) {
        isNfcBusy.set(true)

        lifecycleScope.launch(Dispatchers.Main) {
            progressBar.visibility = View.VISIBLE
            camButton.isEnabled = false
            simButton.isEnabled = false
            resetUI()
            updateStatus("🔄 READING CHIP DATA...", Color.DKGRAY)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                isoDep?.apply {
                    timeout = 8000
                    if (!isConnected) connect()
                }

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
                try {
                    isoDep?.close()
                } catch (_: Exception) {}
                isNfcBusy.set(false)
            }
        }
    }

    private fun handleSuccess(data: PassportData) {
        progressBar.visibility = View.GONE
        camButton.isEnabled = true
        simButton.isEnabled = true
        session = session.copy(state = SessionState.DONE)
        performHapticFeedback()

        updateStatus("✅ CHIP READ SUCCESS", Color.parseColor("#006400"))

        val sodSize = data.sodRaw?.size ?: 0
        val sodStatus = if (sodSize > 0) "✅ FOUND ($sodSize bytes)" else "❌ MISSING"

        detailsText.text = """
            Name: ${data.firstName} ${data.lastName}
            SOD: $sodStatus
            
            ⏳ VERIFYING SECURITY...
        """.trimIndent()
        detailsText.visibility = View.VISIBLE

        data.facePhoto?.let {
            val safeBitmap = Bitmap.createScaledBitmap(it, 300, 400, true)
            photoView.setImageBitmap(safeBitmap)
            photoView.visibility = View.VISIBLE
        }

        rustJob?.cancel()
        rustJob = lifecycleScope.launch(Dispatchers.IO) {
            val rustResponse = SecurityGate.sendToRustForProof(data)

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext

                val isError = rustResponse.contains("ERROR", true) ||
                        rustResponse.contains("FAIL", true) ||
                        rustResponse.contains("MISMATCH", true)

                val finalColor = if (isError) Color.RED else Color.parseColor("#006400")
                val header = if (isError) "⚠️ VERIFICATION FAILED" else "🦁 VERIFIED BY RUST"

                detailsText.text = """
                    Name: ${data.firstName} ${data.lastName}
                    SOD: $sodStatus
                    
                    $header:
                    $rustResponse
                """.trimIndent()

                updateStatus(
                    if (isError) "❌ PASSPORT REJECTED" else "✅ PASSPORT AUTHENTIC",
                    finalColor
                )

                if (isError) performErrorVibration()
            }
        }

        lifecycleScope.launch {
            delay(15000)
            if (!isFinishing && !isDestroyed) {
                session = PassportSession()
                resetUI()
                updateStatus("Ready for next scan", Color.DKGRAY)
            }
        }
    }

    private fun handleError(e: Exception) {
        progressBar.visibility = View.GONE
        camButton.isEnabled = true
        simButton.isEnabled = true
        session = session.copy(state = SessionState.ERROR)

        val (msg, color) = when (e) {
            is TagLostException -> "⚠️ Chip connection lost. Hold steady." to Color.parseColor("#FF8C00")
            is IOException -> "⚠️ Read failed. Remove phone case & retry." to Color.RED
            is SecurityException -> "❌ Security error: ${e.message}" to Color.RED
            else -> "❌ Error: ${e.localizedMessage ?: "Unknown"}" to Color.RED
        }

        updateStatus(msg, color)
        performErrorVibration()
    }

    private fun updateStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun resetUI() {
        detailsText.text = ""
        detailsText.visibility = View.GONE
        photoView.setImageDrawable(null)
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

    private fun performErrorVibration() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1))
            } else v.vibrate(300)
        } catch (_: Exception) {}
    }

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

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 40)
        }

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
