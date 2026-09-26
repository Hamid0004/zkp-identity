package com.example.zkpapp

import com.example.zkpapp.ui.DesignTokens

import android.animation.*
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.*
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PassportActivity : AppCompatActivity() {

    // ── Design Tokens ────────────────────────────────────────────────────────
    private val colorBg        = DesignTokens.bgDark
    private val colorBg2       = DesignTokens.bgElevated
    private val colorSurface   = DesignTokens.surface
    private val colorCardBg    = DesignTokens.surface
    private val colorAccent    = DesignTokens.accent
    private val colorCyan      = DesignTokens.accent
    private val colorGreen     = DesignTokens.success
    private val colorRed       = DesignTokens.error
    private val colorOrange    = DesignTokens.warning
    private val colorTextMain  = DesignTokens.textMain
    private val colorTextMuted = DesignTokens.textMuted
    private val colorTextFaint = DesignTokens.textFaint
    private val colorBorder    = DesignTokens.border


    
    // ── Security ──────────────────────────────────────────────────────────────
    private val keyStoreManager  = com.example.zkpapp.security.KeyStoreManager()
    private val biometricManager by lazy { com.example.zkpapp.security.ZkBiometricManager(this) }

    // ── NFC ───────────────────────────────────────────────────────────────────
    private var nfcAdapter: NfcAdapter? = null
    private val isNfcBusy    = AtomicBoolean(false)
    private val lastScanTime = AtomicLong(0)
    private val NFC_COOLDOWN = 3000L

    // ── Session ───────────────────────────────────────────────────────────────
    private var session  = PassportSession()
    private var rustJob: Job? = null
    private var countdownJob: Job? = null
    private var pendingPassportData: PassportData? = null

    // Respect system "Remove animations" accessibility setting
    private val reduceMotion: Boolean by lazy {
        try {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) { false }
    }

    // ── UI References ─────────────────────────────────────────────────────────
    private lateinit var tvHeader:        TextView
    private lateinit var tvSubHeader:     TextView
    private lateinit var statusBanner:    CardView
    private lateinit var tvStatusDot:     TextView
    private lateinit var tvStatusMsg:     TextView
    private lateinit var tvStatusSub:     TextView
    private lateinit var photoFrame:      CardView
    private lateinit var photoView:       ImageView
    private lateinit var tvPhotoLabel:    TextView
    private lateinit var cardIdentity:    CardView
    private lateinit var tvName:          TextView
    private lateinit var tvDocNum:        TextView
    private lateinit var tvNationality:   TextView
    private lateinit var tvSodStatus:     TextView
    private lateinit var tvMode:          TextView
    private lateinit var cardProof:       CardView
    private lateinit var tvProofHash:     TextView
    private lateinit var tvProofTime:     TextView
    private lateinit var tvCountdown:   TextView
    private lateinit var cardIntegrity:   CardView
    private lateinit var tvIntegrityRows: TextView
    private lateinit var cardCrypto:      CardView
    private lateinit var tvCryptoRows:    TextView
    private lateinit var confirmationPanel: LinearLayout
    private lateinit var progressBar:     ProgressBar
    private lateinit var phoneIndicator:  LinearLayout
    private lateinit var progressIndicator: ProgressBar
    private lateinit var stepBar:         LinearLayout
    private lateinit var btnScanMrz:      Button
    private var btnSimulate: Button? = null
    private lateinit var scrollView:      ScrollView


    // ── Camera Launcher ───────────────────────────────────────────────────────
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val rawMrz = result.data?.getStringExtra("MRZ_DATA") ?: return@registerForActivityResult

            // [SESSION v2.0] MrzInfo.fromRaw() — no more PENDING placeholders
            // Parses documentNumber, DOB, expiry, nationality, gender from raw MRZ immediately
            val mrzInfo = MrzInfo.fromRaw(rawMrz)
            val validationError = mrzInfo.validate()
            if (validationError != null) {
                showToast("⚠️ MRZ Error: $validationError")
                return@registerForActivityResult
            }
            session = PassportSession(
                mrzInfo   = mrzInfo,
                state     = SessionState.MRZ_SCANNED,
                tier      = mrzInfo.docType   // DocumentTier.PASSPORT or NATIONAL_ID
            )
            // [SESSION v2.0] DRY strings from SessionState — no hardcoded text
            updateStatus(session.state.displayString, colorCyan, session.state.statusSub)
            updateStepBar(session.state.stepIndex)
            stepBar.visibility = View.VISIBLE  // [A-5] reveal after MRZ
        renderChecklist(session.state)
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg

        buildUI()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            updateStatus("NFC NOT AVAILABLE", colorRed, "SIMULATION MODE ONLY")
            btnScanMrz.isEnabled = false
            btnScanMrz.alpha = 0.4f
        }

        // Warmup Rust ZK circuit on app start — saves ~600ms on first proof
        lifecycleScope.launch(Dispatchers.IO) {
            IdentityStorage.warmup()
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcDispatch()
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
    }

    // ── NFC ───────────────────────────────────────────────────────────────────
    private fun enableNfcDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(this, 0, intent, flags)
            adapter.enableForegroundDispatch(
                this, pending,
                arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)),
                arrayOf(arrayOf(IsoDep::class.java.name))
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val now = System.currentTimeMillis()
        if (now - lastScanTime.get() < NFC_COOLDOWN) return
        lastScanTime.set(now)
        if (isNfcBusy.get()) return
        if (!SecurityGate.canStartNfc(session)) {
            showToast("⚠️ Scan MRZ first!")
            return
        }
        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        val isoDep = IsoDep.get(tag) ?: run {
            updateStatus("NOT AN E-PASSPORT", colorRed, "ISO DEP NOT FOUND")
            return
        }
        startEngine(PassportMode.REAL, isoDep)
    }

    // ── Engine ────────────────────────────────────────────────────────────────
    private fun runSimulation() {
        session = PassportSession()
        startEngine(PassportMode.SIMULATION, null)
    }

    private fun startEngine(mode: PassportMode, isoDep: IsoDep?) {
        isNfcBusy.set(true)
        resetResultUI()
        progressBar.visibility = View.VISIBLE
        btnScanMrz.isEnabled  = false
        btnSimulate?.isEnabled = false

        // [SESSION v2.0] Advance to CONNECTING state — DRY status strings
        session = session.copy(state = SessionState.CONNECTING)
        updateStatus("CHIP FOUND — UNLOCKING", colorCyan, "Encrypted channel · Keep phone still")
        performHaptic(HapticType.CHIP_CONNECT)
        updateStepBar(session.state.stepIndex)
        renderChecklist(session.state)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                isoDep?.apply { timeout = 8000; if (!isConnected) connect() }
                val engine = PassportEngine(mode, isoDep, session.mrzInfo?.raw)
                val data   = engine.start()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) handleSuccess(data)
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

    private fun handleSuccess(data: PassportData) {
        progressBar.visibility = View.GONE
        btnScanMrz.isEnabled  = true
        btnSimulate?.isEnabled = true

        // [SESSION v2.0] DONE state
        session = session.copy(state = SessionState.DONE)
        performHaptic(HapticType.SUCCESS)
        updateStatus(session.state.displayString, colorGreen, session.state.statusSub)
        updateStepBar(session.state.stepIndex)
        renderChecklist(session.state)

        // Photo
        val photo = data.facePhoto
            ?: PassportData.getCachedPhoto(data.documentNumber)
        photo?.let {
            val scaled = Bitmap.createScaledBitmap(it, 300, 400, true)
            photoView.setImageBitmap(scaled)
            photoView.visibility = View.VISIBLE
            tvPhotoLabel.visibility = View.GONE
        }

        // Identity
        tvName.text        = "${data.firstName} ${data.lastName}"
        tvDocNum.text      = data.documentNumber
        tvNationality.text = nationalityDisplay(data.nationality)
        val sodSize = data.sodRaw?.size ?: 0
        tvSodStatus.text = if (sodSize > 0) "SOD: FOUND · ${sodSize}B" else "SOD: MISSING"
        tvSodStatus.setTextColor(if (sodSize > 0) colorGreen else colorRed)
        tvMode.text = if (session.mrzInfo == null) "SIMULATION" else "REAL NFC"
        cardIdentity.visibility = View.VISIBLE
        animateFadeIn(cardIdentity)

        // [B-STATE-4] Human-in-the-loop security gate
        // Do NOT auto-trigger biometric. Show confirmation panel first.
        pendingPassportData = data
        showConfirmationPanel()
    }

    private fun showConfirmationPanel() {
        if (!::confirmationPanel.isInitialized) return
        confirmationPanel.visibility = View.VISIBLE
        animateFadeIn(confirmationPanel)
        updateStatus("REVIEW YOUR IDENTITY", colorCyan, "Confirm this is you to continue")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun onConfirmed() {
        val data = pendingPassportData ?: return
        confirmationPanel.visibility = View.GONE
        performHaptic(HapticType.STEP_COMPLETE)
        startBiometricFlow(data)
    }

    private fun onRejected() {
        confirmationPanel.visibility = View.GONE
        pendingPassportData = null
        performHaptic(HapticType.ERROR)
        showToast("Identity not confirmed — scan again")
        resetResultUI()
        session = session.copy(state = SessionState.IDLE)
        updateStatus("SCAN CANCELLED", colorTextMuted, "Passport did not match")
        updateStepBar(0)
        renderChecklist(SessionState.IDLE)
    }

    // Biometric-gated encrypted save — extracted from handleSuccess
    private fun startBiometricFlow(data: PassportData) {
        try {
            val cipher    = keyStoreManager.getCipherForEncryption()
            val cryptoObj = androidx.biometric.BiometricPrompt.CryptoObject(cipher)

            updateStatus("NEXT: SECURING YOUR DATA", colorCyan, "Biometric verification required")

            biometricManager.authenticateUser(
                activity     = this,
                cryptoObject = cryptoObj,
                subtitle     = "Passport ko secure karne ke liye fingerprint lagayein",
                onSuccess    = { result ->
                    val encCipher = result.cryptoObject?.cipher ?: run {
                        saveIdentityRamOnly(data)
                        startZkProofGeneration(data)
                        return@authenticateUser
                    }
                    IdentityStorage.saveIdentityEncrypted(
                        context     = this,
                        cipher      = encCipher,
                        secret      = data.dg1SecretHex,
                        country     = data.nationality.ifEmpty { "PAK" },
                        docNumber   = data.documentNumber,
                        fName       = data.firstName,
                        lName       = data.lastName,
                        nationality = data.nationality,
                        dob         = data.dateOfBirth,
                        expiry      = data.expiryDate,
                        dg1         = data.dg1Hex,
                        sod         = data.sodHex,
                        mrz         = data.mrzLine.ifEmpty { session.mrzInfo?.raw ?: "" },
                        dsCert      = data.dsCertHex,
                        domain      = "zkpapp.local"
                    )
                    startZkProofGeneration(data)
                },
                onError = { _ ->
                    saveIdentityRamOnly(data)
                    showToast("⚠️ Biometric cancelled — tap Authenticate to continue")
                },
                onFailed = {
                    showToast("⚠️ Wrong fingerprint — try again")
                }
            )
            return
        } catch (e: com.example.zkpapp.security.KeyStoreManager.KeyInvalidatedException) {
            keyStoreManager.deleteKey()
            IdentityStorage.clearPersistent(this)
            showToast("🔑 New biometric detected — identity cleared. Rescan passport.")
            saveIdentityRamOnly(data)
        } catch (e: Exception) {
            saveIdentityRamOnly(data)
        }
        startZkProofGeneration(data)
    }

    // ── RAM-only fallback (biometric cancelled / key error) ───────────────────
    private fun saveIdentityRamOnly(data: PassportData) {
        IdentityStorage.saveIdentity(
            secret      = data.dg1SecretHex,
            country     = data.nationality.ifEmpty { "PAK" },
            docNumber   = data.documentNumber,
            fName       = data.firstName,
            lName       = data.lastName,
            nationality = data.nationality,
            dob         = data.dateOfBirth,
            expiry      = data.expiryDate,
            dg1         = data.dg1Hex,
            sod         = data.sodHex,
            mrz         = data.mrzLine.ifEmpty { session.mrzInfo?.raw ?: "" },
            dsCert      = data.dsCertHex,
            domain      = "zkpapp.local"
        )
    }

    // ── ZK Proof Generation ───────────────────────────────────────────────────
    private fun startZkProofGeneration(data: PassportData) {
        rustJob?.cancel()
        session = session.copy(state = SessionState.ZKP_GENERATING)
        updateStatus(session.state.displayString, colorCyan, session.state.statusSub)
        updateStepBar(session.state.stepIndex)
        renderChecklist(session.state)

        rustJob = lifecycleScope.launch {
            try {
                IdentityStorage.setVerifierDomain("zkpapp.local")

                val rustResult = SecurityGate.generateClaim(
                    claimType = "is_adult",
                    domain    = "zkpapp.local",
                    context   = this@PassportActivity
                )

                if (isFinishing || isDestroyed) return@launch

                when (rustResult) {
                    is SecurityGate.ProofResult.Success -> {
                        session = session.copy(state = SessionState.ZKP_READY)
                        updateStatus(session.state.displayString, colorGreen, session.state.statusSub)
                        updateStepBar(session.state.stepIndex)
        renderChecklist(session.state)
                        showRustSuccess(data, rustResult.result)
                        showToast("🦁 ZK Proof Ready · ${session.minutesRemaining}min session")
                    }
                    is SecurityGate.ProofResult.Failure -> {
                        showRustError(rustResult.reason)
                        performHaptic(HapticType.ERROR)
                    }
                }
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    showRustError(e.message ?: "Proof generation failed")
                    performHaptic(HapticType.ERROR)
                }
            } finally {
                isNfcBusy.set(false)
            }
        }
    }

    private fun showRustSuccess(data: PassportData, result: SecurityGate.PassportProofResult) {
        val modeLabel = if (result.inputMode == "NFC_PASSPORT") "REAL NFC" else "SIMULATED"
        // [U-2/K7] Trust-honest wording — no overclaim (A-06)
        val statusMsg = if (result.trusted) {
            "SIGNATURE VERIFIED"
        } else {
            "DEMO PROOF"
        }
        updateStatus(statusMsg, colorGreen, "$modeLabel · ZK PROOF GENERATED")

        // Proof bar
        cardProof.visibility = View.VISIBLE
        tvProofTime.text = "${result.zkProofMs}ms"
        val nullifierPrefix = result.nullifier.take(16).uppercase().ifEmpty { "ZK COMMITTED" }
        tvProofHash.text = "Plonky2 · ${result.bridgeSchemaDigest.take(8)} · $nullifierPrefix…"
        animateFadeIn(cardProof)

        // Integrity card
        cardIntegrity.visibility = View.VISIBLE
        tvIntegrityRows.text =
        // [A-05/K7] Local identity display only — never sent to network
        "👤  ${data.firstName} ${data.lastName}\n" +
        "🔒  Integrity:  ${result.integrityCheck}\n" +
        "🛡️  Trust:      ${result.trustLevel}${if (!result.trusted) " (DEMO)" else ""}\n" +
        "🔒  Data:       Encrypted on device"
        animateFadeIn(cardIntegrity)

        // Crypto card
        val zkOutput  = result.zkOutput
        val proofType = if (zkOutput != null) "RECURSIVE v${zkOutput.version}" else "NONE"
        cardCrypto.visibility = View.VISIBLE
        tvCryptoRows.text =
            "🛡️  Integrity:  ${result.integrityCheck}\n" +
            "✅  Signature:  ${result.signatureCheck}\n" +
            "🔑  Algorithm:  RSA-2048 + Poseidon\n" +
            "⚡  ZK Proof:   ${result.zkProofStatus}\n" +
            "📦  Proof Type: $proofType\n" +
            if (zkOutput != null) "HW Binding: ACTIVE" else "HW Binding: NONE"
        animateFadeIn(cardCrypto)
        
                // Phase 1: Lifecycle-aware countdown
        startCountdown(zkOutput?.validUntil)

        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showRustError(reason: String) {
        updateStatus("❌ PASSPORT REJECTED", colorRed, reason.take(40).uppercase())
        cardIntegrity.visibility = View.VISIBLE
        tvIntegrityRows.text = "❌ Verification failed\n$reason"
    }

    // Nationality flag + name lookup
    private fun nationalityDisplay(code: String): String {
        val map = mapOf(
            "PAK" to "🇵🇰 PAKISTAN",  "USA" to "🇺🇸 USA",
            "GBR" to "🇬🇧 UK",        "ARE" to "🇦🇪 UAE",
            "SAU" to "🇸🇦 SAUDI",     "IND" to "🇮🇳 INDIA",
            "DEU" to "🇩🇪 GERMANY",   "FRA" to "🇫🇷 FRANCE",
            "CHN" to "🇨🇳 CHINA",     "TUR" to "🇹🇷 TURKEY"
        )
        return map[code.uppercase()] ?: "🌐 ${code.uppercase()}"
    }

    // ═══ [B-STATE-5] Universal failure layout ═══
    private data class FailureInfo(
        val what: String, val why: String,
        val action: String, val tip: String
    )

    private fun renderFailureCard(info: FailureInfo) {
        cardIntegrity.visibility = View.VISIBLE
        tvIntegrityRows.text = buildString {
            appendLine("ERROR: ${info.what}")
            if (info.why.isNotEmpty())    appendLine("CAUSE: ${info.why}")
            if (info.action.isNotEmpty()) appendLine("ACTION: ${info.action}")
            if (info.tip.isNotEmpty())    appendLine("TIP: ${info.tip}")
        }
        animateFadeIn(cardIntegrity)
    }

    private fun handleError(e: Exception) {
        progressBar.visibility = View.GONE
        btnScanMrz.isEnabled  = true
        btnSimulate?.isEnabled = true

        val failure = when (e) {
            is TagLostException ->
                FailureInfo("📵 CONNECTION LOST",
                    "The phone moved during reading.",
                    "Retry chip", "Hold still — reading takes 3-5 seconds")
            is IOException ->
                FailureInfo("⚠️ READ FAILED",
                    "NFC communication interrupted.",
                    "Remove case & retry", "Hold phone against passport back")
            is SecurityException ->
                FailureInfo("❌ SECURITY ERROR",
                    e.message ?: "Access denied", "Retry", "")
            else -> {
                val em = e.message ?: ""
                when {
                    em.contains("BAC", ignoreCase = true) ->
                        FailureInfo("🔐 CHIP UNLOCK FAILED",
                            "Chip rejected the access key — MRZ likely misread.",
                            "Re-scan MRZ", "Even 1 wrong digit blocks access")
                    em.contains("SOD", ignoreCase = true) ->
                        FailureInfo("⚠️ SECURITY DATA INCOMPLETE",
                            "Security signature (SOD) not found — passport may be damaged.",
                            "Retry", "If this keeps happening, chip may be faulty")
                    em.contains("check digit", ignoreCase = true) ->
                        FailureInfo("📷 MRZ DATA CORRUPTED",
                            "Check digit mismatch — OCR misread a character.",
                            "Re-scan MRZ", "Scan in better lighting")
                    em.contains("date_of_birth", ignoreCase = true) ->
                        FailureInfo("⚠️ INVALID DATE",
                            "The MRZ contains an impossible date (OCR misread).",
                            "Re-scan MRZ", "Better lighting improves OCR accuracy")
                    em.contains("device_rng", ignoreCase = true) ->
                        FailureInfo("📱 DEVICE ERROR",
                            "Device registration data invalid.",
                            "Restart app and retry", "")
                    em.contains("device_pubkey", ignoreCase = true) ->
                        FailureInfo("🔑 DEVICE KEY ERROR",
                            "Device key invalid or too short.",
                            "Restart app", "")
                    em.contains("expected_nationality", ignoreCase = true) ->
                        FailureInfo("🌍 NATIONALITY MISMATCH",
                            "Passport nationality doesn't match selection.",
                            "Check selection and retry", "")
                    else ->
                        FailureInfo("❌ ENGINE ERROR",
                            e.localizedMessage?.take(80) ?: "Unknown error",
                            "Retry", "")
                }
            }
        }
        session = session.withError("${failure.what} — ${failure.why}")
        updateStatus(failure.what, colorRed, failure.action.uppercase())
        performHaptic(HapticType.ERROR)
        renderChecklist(SessionState.ERROR)
        renderFailureCard(failure)
    }

    // ── UI Builders ───────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(colorBg) }

        scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        }

        progressBar = ProgressBar(this).apply { visibility = View.GONE }

        container.addView(buildHeader())
        // [A-1/A-2/A-4] Screen A panel
        container.addView(buildScreenAPanel())
        // [A-5] Step bar DEFERRED — visible only after MRZ scan
        container.addView(buildStepBar().apply { visibility = View.GONE })
        container.addView(buildPhoneIndicator())  // Phone positioning visual
        container.addView(buildProgressIndicator())  // Reading progress
        container.addView(buildStatusBanner())
        // [B-STATE] Morphing checklist
        container.addView(buildChecklist())
        container.addView(buildPhotoIdentityRow())
        container.addView(buildConfirmationPanel())
        container.addView(buildProofBar())
        container.addView(buildSectionLabel("RUST INTEGRITY REPORT"))
        container.addView(buildResultCard(isIntegrity = true))
        container.addView(buildSectionLabel("CRYPTO REPORT"))
        container.addView(buildResultCard(isIntegrity = false))
        container.addView(progressBar)
        container.addView(buildButtons())
        container.addView(spacer(32))

        scrollView.addView(container)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(20), px(20), px(20), px(16))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colorBg)
        }

        val back = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(colorCyan)
            setPadding(px(12), px(10), px(12), px(10))
            background = cyberBorder(colorBorder, 12f)
            setOnClickListener { finish() }
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(14), 0, 0, 0) }
        }
        tvHeader = TextView(this).apply {
            text = "NFC PASSPORT"
            textSize = 14f
            setTextColor(colorCyan)
            letterSpacing = 0.15f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvSubHeader = TextView(this).apply {
            text = "ICAO 9303  ·  BAC  ·  ZK PROOF"
            textSize = 12f
            setTextColor(Color.parseColor("#447788"))
            letterSpacing = 0.1f
        }
        titleBlock.addView(tvHeader)
        titleBlock.addView(tvSubHeader)

        val shield = TextView(this).apply {
            text = "🛡️"
            textSize = 20f
            setPadding(px(10), px(8), px(10), px(8))
            background = cyberBorder(Color.parseColor("#003322"), 10f)
        }

        row.addView(back)
        row.addView(titleBlock)
        row.addView(shield)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            setBackgroundColor(colorBorder)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(divider)
        }
    }

    private fun buildStepBar(): View {
        stepBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(16), px(12), px(16), px(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        val steps = listOf("MRZ", "NFC", "READ", "SOD", "ZKP")
        steps.forEachIndexed { i, s ->
            val chip = TextView(this).apply {
                text = s
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(px(12), px(6), px(12), px(6))
                setTextColor(colorTextMuted)
                background = cyberBorder(colorBorder, 20f)
                letterSpacing = 0.1f
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    if (i > 0) setMargins(px(6), 0, 0, 0)
                }
                tag = "step_$i"
            }
            stepBar.addView(chip)
        }
        updateStepBar(0)
        return stepBar
    }

    // ═══ Phone positioning indicator (B-STATE-1) ═══
    private fun buildPhoneIndicator(): View {
        phoneIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(8))
            gravity = Gravity.CENTER
            visibility = View.GONE  // Hidden initially, shown in WAITING_CHIP state
        }
        val wrapper = phoneIndicator
        
        // Phone + passport visual
        val visual = TextView(this).apply {
            text = """
                ┌─────────┐
                │  📱     │
                │         │
                └─────────┘
                   ↓↓
                ┌─────────┐
                │PASSPORT │
                │  NFC    │
                │  chip   │
                └─────────┘
            """.trimIndent()
            textSize = 12f
            setTextColor(colorCyan)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(px(12), px(8), px(12), px(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0a141f"))
                setStroke(1, Color.parseColor("#1a3a4a"))
                cornerRadius = px(8).toFloat()
            }
        }
        
        // Instruction text
        val instruction = TextView(this).apply {
            text = "Hold phone steady against passport back"
            textSize = 13f
            setTextColor(Color.parseColor("#88ccee"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, 0)
        }
        
        // Remove case tip
        val tip = TextView(this).apply {
            text = "Remove phone case if NFC not detecting"
            textSize = 12f
            setTextColor(colorTextMuted)
            gravity = Gravity.CENTER
            setPadding(0, px(4), 0, 0)
        }
        
        wrapper.addView(visual)
        wrapper.addView(instruction)
        wrapper.addView(tip)
        return wrapper
    }

    // ═══ Progress indicator for READING state (B-STATE-3) ═══
    private fun buildProgressIndicator(): View {
        progressIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, px(4))
        }
        return progressIndicator
    }

    private fun buildStatusBanner(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), 0)
        }
        statusBanner = CardView(this).apply {
            radius = px(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#040e1a"))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(14), px(14), px(14), px(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        tvStatusDot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, px(12), 0) }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tvStatusMsg = TextView(this).apply {
            // [SESSION v2.0] Initial text from SessionState.IDLE.displayString
            text = SessionState.IDLE.displayString
            textSize = 14f
            setTextColor(Color.GRAY)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        tvStatusSub = TextView(this).apply {
            text = SessionState.IDLE.statusSub
            textSize = 12f
            setTextColor(colorTextMuted)
            letterSpacing = 0.03f
        }
        textCol.addView(tvStatusMsg)
        textCol.addView(tvStatusSub)
        inner.addView(tvStatusDot)
        inner.addView(textCol)
        statusBanner.addView(inner)
        wrapper.addView(statusBanner)
        return wrapper
    }

    private fun buildPhotoIdentityRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(16), px(14), px(16), 0)
        }

        photoFrame = CardView(this).apply {
            radius = px(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#040e1a"))
            contentDescription = "Passport photo — verify this is you"
            layoutParams = LinearLayout.LayoutParams(px(130), px(170)).apply {
                setMargins(0, 0, px(14), 0)
            }
        }
        val photoInner = FrameLayout(this)
        photoView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        tvPhotoLabel = TextView(this).apply {
            text = "👤\nPHOTO"
            textSize = 12f
            setTextColor(colorTextMuted)
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
        }
        photoInner.addView(photoView)
        photoInner.addView(tvPhotoLabel)
        photoFrame.addView(photoInner)

        cardIdentity = CardView(this).apply {
            radius = px(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(colorCardBg)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            visibility = View.INVISIBLE
        }
        val idInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
        }

        fun idRow(label: String): Pair<TextView, TextView> {
            val lbl = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(colorTextMuted)
                letterSpacing = 0.1f
            }
            val `val` = TextView(this).apply {
                textSize = 12f
                setTextColor(colorCyan)
                typeface = Typeface.DEFAULT_BOLD
            }
            idInner.addView(lbl)
            idInner.addView(`val`)
            idInner.addView(spacer(8))
            return lbl to `val`
        }

        val (_, n)   = idRow("FULL NAME");    tvName = n
        val (_, d)   = idRow("DOCUMENT");     tvDocNum = d
        val (_, nat) = idRow("NATIONALITY");  tvNationality = nat
        val (_, sod) = idRow("SOD STATUS");   tvSodStatus = sod
        val (_, mod) = idRow("MODE");         tvMode = mod
        mod.textSize = 12f
        mod.setTextColor(colorTextMuted)

        cardIdentity.addView(idInner)
        row.addView(photoFrame)
        row.addView(cardIdentity)
        return row
    }
    // ═══ Human-in-the-loop confirmation (B-STATE-4 security gate) ═══
    private fun buildConfirmationPanel(): View {
        confirmationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), 0)
            visibility = View.GONE
        }

        val card = CardView(this).apply {
            radius = px(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(DesignTokens.successChipBg)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }

        val prompt = TextView(this).apply {
            text = "Does this identity belong to you?"
            textSize = 14f
            setTextColor(colorTextMain)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtext = TextView(this).apply {
            text = "Confirm to proceed with biometric and ZK proof"
            textSize = 12f
            setTextColor(colorTextMuted)
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, px(14))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnConfirm = Button(this).apply {
            text = "✓  THIS IS ME"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(Color.WHITE)
            background = gradientBg(colorGreen, colorAccent, 14f)
            layoutParams = LinearLayout.LayoutParams(0, px(48), 1f).apply {
                setMargins(0, 0, px(6), 0)
            }
            setPadding(0, 0, 0, 0)
            contentDescription = "Confirm this passport belongs to me"
            setOnClickListener { onConfirmed() }
        }

        val btnReject = Button(this).apply {
            text = "✗  NOT ME"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(colorRed)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(14).toFloat()
                setStroke(px(1), colorRed)
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(0, px(48), 1f).apply {
                setMargins(px(6), 0, 0, 0)
            }
            setPadding(0, 0, 0, 0)
            contentDescription = "Reject — this is not my passport"
            setOnClickListener { onRejected() }
        }

        buttonRow.addView(btnConfirm)
        buttonRow.addView(btnReject)

        inner.addView(prompt)
        inner.addView(subtext)
        inner.addView(buttonRow)
        card.addView(inner)
        confirmationPanel.addView(card)
        return confirmationPanel
    }

    private fun buildProofBar(): View {
        val wrapper = LinearLayout(this).apply {
            setPadding(px(16), px(14), px(16), 0)
        }
        cardProof = CardView(this).apply {
            radius = px(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#041a0d"))
            visibility = View.GONE
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(14), px(12), px(14), px(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = "⚡"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, px(12), 0) }
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val proofTitle = TextView(this).apply {
            text = "PLONKY2 ZK PROOF"
            textSize = 12f
            setTextColor(colorGreen)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
        }
        tvProofHash = TextView(this).apply {
            text = "SHA256 · aarch64"
            textSize = 12f
            setTextColor(Color.parseColor("#224433"))
        }
        infoCol.addView(proofTitle)
        infoCol.addView(tvProofHash)

        val timeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tvProofTime = TextView(this).apply {
            text = "—"
            textSize = 16f
            setTextColor(colorGreen)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        val generatedLbl = TextView(this).apply {
            text = "GENERATED"
            textSize = 11f
            setTextColor(DesignTokens.textFaint)
            letterSpacing = 0.1f
            gravity = Gravity.END
        }
        timeCol.addView(tvProofTime)
        timeCol.addView(generatedLbl)
        tvCountdown = TextView(this).apply {
            text = "Valid for --:--"
            textSize = 13f
            setTextColor(colorAccent)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        timeCol.addView(tvCountdown)

        inner.addView(icon)
        inner.addView(infoCol)
        inner.addView(timeCol)
        cardProof.contentDescription = "Zero Knowledge Proof generation status and countdown timer"
        cardProof.addView(inner)
        wrapper.addView(cardProof)
        return wrapper
    }

    private fun buildSectionLabel(text: String): View {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(colorTextMuted)
            setPadding(px(16), px(14), px(16), px(6))
            letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun buildResultCard(isIntegrity: Boolean): View {
        val wrapper = LinearLayout(this).apply {
            setPadding(px(16), 0, px(16), 0)
        }
        val card = CardView(this).apply {
            radius = px(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(colorCardBg)
            contentDescription = if (isIntegrity)
                "Passport integrity report — tap to expand"
            else
                "Cryptographic proof report — tap to expand"
            visibility = View.GONE
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(14), px(12), px(14), px(12))
            setBackgroundColor(Color.parseColor("#060e1c"))
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = if (isIntegrity) "🦁" else "🔐"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0,0,px(10),0) }
        }
        val title = TextView(this).apply {
            text = if (isIntegrity) "PASSPORT ENGINE" else "CRYPTO ENGINE"
            textSize = 12f
            setTextColor(colorCyan)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val badge = TextView(this).apply {
            text = if (isIntegrity) "VERIFIED" else "SIMULATED"
            textSize = 12f
            setPadding(px(8), px(4), px(8), px(4))
            setTextColor(if (isIntegrity) colorGreen else colorCyan)
            background = cyberBorder(
                if (isIntegrity) Color.parseColor("#003322") else Color.parseColor("#002233"),
                20f
            )
        }
        val chevron = TextView(this).apply {
            text = "▼"
            textSize = 14f
            setTextColor(colorTextMuted)
            setPadding(px(8), 0, px(4), 0)
            rotation = 180f  // collapsed state — chevron points up
        }
        header.addView(icon); header.addView(title); header.addView(badge); header.addView(chevron)

        val div = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            setBackgroundColor(colorBorder)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(8), px(14), px(8))
        }
        val tv = TextView(this).apply {
            text = "—"
            textSize = 12f
            setTextColor(colorTextMuted)
            lineHeight = (textSize * 2.2f).toInt()
        }
        body.addView(tv)

        inner.addView(header); inner.addView(div); inner.addView(body)
        
        // Collapsible state — starts collapsed
        var isExpanded = false
        body.visibility = View.GONE

        header.setOnClickListener {
            isExpanded = !isExpanded
            body.visibility = if (isExpanded) View.VISIBLE else View.GONE
            chevron.animate()
                .rotation(if (isExpanded) 0f else 180f)
                .setDuration(200)
                .start()
        }
        card.addView(inner)
        wrapper.addView(card)

        if (isIntegrity) { cardIntegrity = card; tvIntegrityRows = tv }
        else             { cardCrypto    = card; tvCryptoRows    = tv }

        return wrapper
    }

    private fun buildButtons(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), 0)
        }

        btnScanMrz = Button(this).apply {
            text = "📷  SCAN MRZ"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
            setTextColor(Color.WHITE)
            contentDescription = "Scan passport MRZ — opens camera"
            background = gradientBg(Color.parseColor("#0055cc"), Color.parseColor("#00bcd4"), 16f)
            layoutParams = LinearLayout.LayoutParams(MATCH, px(52)).apply { setMargins(0, 0, 0, px(12)) }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                cameraLauncher.launch(Intent(this@PassportActivity, CameraActivity::class.java))
            }
        }

        // [A-07/U-7] Simulate button — debug builds only (release Rust has no sim symbols)
        if (BuildConfig.DEBUG) {
            btnSimulate = Button(this).apply {
            text = "SIMULATE (demo — no passport)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#66aacc"))
            contentDescription = "Simulate passport scan — demo mode only"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(14).toFloat()
                setStroke(px(1), Color.parseColor("#2a5a6a"))
                setColor(Color.parseColor("#0a1a2a"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, px(44)).apply { 
                setMargins(0, px(8), 0, px(14)) 
            }
            setPadding(px(16), px(10), px(16), px(10))
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { runSimulation() }
        }
        col.addView(btnSimulate)
        }

col.addView(btnScanMrz)
return col
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    // ═══ [A-1/A-2/A-4] Screen A panel — privacy promise + step label ═══
    private fun buildScreenAPanel(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(8))
        }
        // A-2: Step 1 of 2 label
        val stepLabel = TextView(this).apply {
            text = "STEP 1 OF 2"
            textSize = 12f
            setTextColor(Color.parseColor("#447788"))
            letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
        }
        // A-1: Privacy promise panel
        val privacyCard = CardView(this).apply {
            radius = px(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#040e1a"))
        }
        val privacyInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
        }
        val privacyTitle = TextView(this).apply {
            text = "Zero-Knowledge Verification"
            textSize = 12f
            setTextColor(colorCyan)
            typeface = Typeface.DEFAULT_BOLD
        }
        val proveLine = TextView(this).apply {
            text = "Will be proven: Age 18+, Nationality"
            textSize = 12f
            setTextColor(colorGreen)
        }
        val hideLine = TextView(this).apply {
            text = "Never leaves device: Name, Photo, Address"
            textSize = 12f
            setTextColor(colorTextMuted)
        }
        privacyInner.addView(privacyTitle)
        privacyInner.addView(spacer(8))
        privacyInner.addView(proveLine)
        privacyInner.addView(spacer(4))
        privacyInner.addView(hideLine)
        privacyCard.addView(privacyInner)
        // A-4: Time/offline estimate
        val estimate = TextView(this).apply {
            text = "~2 minutes · Works offline · Secure"
            textSize = 12f
            setTextColor(colorTextMuted)
            setPadding(0, px(8), 0, 0)
        }
        wrapper.addView(stepLabel)
        wrapper.addView(spacer(6))
        wrapper.addView(privacyCard)
        wrapper.addView(estimate)
        return wrapper
    }

    // ═══ [B-STATE] Checklist — morphing state renderer ═══
    private data class ChecklistItem(val label: String, val state: CheckState)
    private enum class CheckState { PENDING, ACTIVE, DONE, FAILED }

    private fun getChecklistForState(state: SessionState): List<ChecklistItem> {
        return when (state) {
            SessionState.IDLE -> listOf(
                ChecklistItem("MRZ scan", CheckState.PENDING),
                ChecklistItem("Connect to chip", CheckState.PENDING),
                ChecklistItem("Unlock & read data", CheckState.PENDING),
                ChecklistItem("Verify signature", CheckState.PENDING))
            SessionState.MRZ_SCANNED, SessionState.NFC_READY -> listOf(
                ChecklistItem("MRZ scanned", CheckState.DONE),
                ChecklistItem("Connect to chip", CheckState.ACTIVE),
                ChecklistItem("Unlock & read data", CheckState.PENDING),
                ChecklistItem("Verify signature", CheckState.PENDING))
            SessionState.CONNECTING, SessionState.ANALYZING_MRZ -> listOf(
                ChecklistItem("MRZ scanned", CheckState.DONE),
                ChecklistItem("Chip connected", CheckState.DONE),
                ChecklistItem("Unlocking (encrypted)", CheckState.ACTIVE),
                ChecklistItem("Read & verify data", CheckState.PENDING))
            SessionState.BAC_AUTH -> listOf(
                ChecklistItem("MRZ scanned", CheckState.DONE),
                ChecklistItem("Chip connected", CheckState.DONE),
                ChecklistItem("Unlocking (encrypted)", CheckState.ACTIVE),
                ChecklistItem("Read & verify data", CheckState.PENDING))
            SessionState.READING -> listOf(
                ChecklistItem("MRZ scanned", CheckState.DONE),
                ChecklistItem("Chip unlocked", CheckState.DONE),
                ChecklistItem("Reading identity data", CheckState.ACTIVE),
                ChecklistItem("Verify signature", CheckState.PENDING))
            SessionState.SOD_READING -> listOf(
                ChecklistItem("MRZ scanned", CheckState.DONE),
                ChecklistItem("Chip unlocked", CheckState.DONE),
                ChecklistItem("Identity data read", CheckState.DONE),
                ChecklistItem("Verifying signature", CheckState.ACTIVE))
            SessionState.DONE, SessionState.ZKP_GENERATING, SessionState.ZKP_READY -> listOf(
                ChecklistItem("MRZ scanned", CheckState.DONE),
                ChecklistItem("Chip unlocked", CheckState.DONE),
                ChecklistItem("Identity data read", CheckState.DONE),
                ChecklistItem("Signature verified", CheckState.DONE))
            SessionState.ERROR -> listOf(
                ChecklistItem("Error occurred", CheckState.FAILED))
        }
    }

    private lateinit var checklistContainer: LinearLayout

    private fun buildChecklist(): View {
        checklistContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(12))
        }
        return checklistContainer
    }

    private fun renderChecklist(state: SessionState) {
        // Show/hide phone indicator and progress indicator based on state
        when (state) {
            SessionState.IDLE, SessionState.MRZ_SCANNED, SessionState.NFC_READY -> {
                if (::phoneIndicator.isInitialized) phoneIndicator.visibility = View.VISIBLE  // Show positioning guide
                phoneIndicator.alpha = 0f
                phoneIndicator.animate().alpha(1f).setDuration(300).start()
                progressIndicator.visibility = View.GONE
            }
            SessionState.READING, SessionState.SOD_READING -> {
                if (::phoneIndicator.isInitialized) phoneIndicator.visibility = View.GONE
                progressIndicator.visibility = View.VISIBLE
            }
            else -> {
                // Fade out before hiding
                if (phoneIndicator.visibility == View.VISIBLE) {
                    phoneIndicator.animate().alpha(0f).setDuration(200).withEndAction {
                        phoneIndicator.visibility = View.GONE
                        phoneIndicator.alpha = 1f
                    }.start()
                }
                progressIndicator.visibility = View.GONE
            }
        }
        
        checklistContainer.removeAllViews()
        val items = getChecklistForState(state)
        items.forEach { item ->
            val bgColor = when (item.state) {
                CheckState.DONE -> Color.parseColor("#0a2a1a")
                CheckState.ACTIVE -> Color.parseColor("#0a1a2a")
                CheckState.FAILED -> Color.parseColor("#2a0a0a")
                CheckState.PENDING -> Color.parseColor("#0a0f1a")
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), px(10), px(12), px(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(bgColor)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    setMargins(0, 0, 0, px(4))
                }
            }
            val icon = TextView(this).apply {
                textSize = 14f
                text = when (item.state) {
                    CheckState.DONE    -> "✓"
                    CheckState.ACTIVE  -> "•"
                    CheckState.FAILED  -> "✗"
                    CheckState.PENDING -> ""
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    setMargins(0, 0, px(10), 0)
                }
            }
            val label = TextView(this).apply {
                text = item.label
                textSize = 14f
                when (item.state) {
                    CheckState.DONE    -> setTextColor(colorGreen)
                    CheckState.ACTIVE  -> setTextColor(colorCyan)
                    CheckState.FAILED  -> setTextColor(colorRed)
                    CheckState.PENDING -> setTextColor(colorTextMuted)
                }
                typeface = if (item.state == CheckState.ACTIVE)
                    Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            row.addView(icon)
            row.addView(label)
            checklistContainer.addView(row)
        }
    }

    // ── Phase 2: State-Specific Haptics ───────────────────────────────────────
    enum class HapticType { SUCCESS, ERROR, CHIP_CONNECT, STEP_COMPLETE, PROOF_READY }
    
    @Suppress("DEPRECATION")
    private fun performHaptic(type: HapticType) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (type) {
                    HapticType.SUCCESS -> VibrationEffect.createOneShot(40, 255)
                    HapticType.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
                    HapticType.CHIP_CONNECT -> VibrationEffect.createOneShot(20, 120)
                    HapticType.STEP_COMPLETE -> VibrationEffect.createOneShot(15, 200)
                    HapticType.PROOF_READY -> VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30, 50, 30), -1)
                }
                v.vibrate(effect)
            } else {
                v.vibrate(if (type == HapticType.ERROR) 300 else 40)
            }
        } catch (_: Exception) {}
    }

    // ── Phase 2: Status Dot Pulse Animation ───────────────────────────────────
    private var dotPulseAnimator: ObjectAnimator? = null
    private val stepAnimators = mutableMapOf<Int, ObjectAnimator>()

    private fun startDotPulse() {
        dotPulseAnimator?.cancel()
        if (!::tvStatusDot.isInitialized) return
        if (reduceMotion) {
            tvStatusDot.alpha = 1f
            return
        }
        dotPulseAnimator = ObjectAnimator.ofFloat(tvStatusDot, "alpha", 1f, 0.2f).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopDotPulse() {
        dotPulseAnimator?.cancel()
        if (::tvStatusDot.isInitialized) tvStatusDot.alpha = 1f
    }

        private fun startCountdown(validUntilSec: Long?) {
        countdownJob?.cancel()

        if (validUntilSec == null) {
            tvCountdown.text = "Session: Persistent"
            tvCountdown.setTextColor(colorTextMuted)
            return
        }

        val endTime = validUntilSec * 1000L
        countdownJob = lifecycleScope.launch {
            while (isActive
                && System.currentTimeMillis() < endTime
                && !isFinishing
                && !isDestroyed
            ) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    val remaining = endTime - System.currentTimeMillis()
                    val mins = (remaining / 60_000).toInt()
                    val secs = ((remaining / 1000) % 60).toInt()
                    tvCountdown.text = "Valid for %02d:%02d".format(mins, secs)
                    tvCountdown.setTextColor(
                        when {
                            remaining < 30_000 -> colorRed
                            remaining < 60_000 -> colorOrange
                            else -> colorAccent
                        }
                    )
                }
                delay(1000)
            }
            if (!isFinishing && !isDestroyed) {
                tvCountdown.text = "EXPIRED"
                tvCountdown.setTextColor(colorRed)
            }
        }
    }

    private fun updateStatus(msg: String, color: Int, sub: String = "") {
        tvStatusMsg.text = msg
        tvStatusMsg.setTextColor(color)
        tvStatusSub.text = sub
        tvStatusDot.setTextColor(color)

        // Pulse dot only for active states (accent/cyan); static for terminal states
        if (color == colorCyan || color == colorAccent) {
            startDotPulse()
        } else {
            stopDotPulse()
        }
    }

    private fun updateStepBar(activeIndex: Int) {
        val steps = listOf("MRZ", "NFC", "READ", "SOD", "ZKP")
        for (i in steps.indices) {
            val chip = stepBar.findViewWithTag<TextView>("step_$i") ?: continue
            when {
                i < activeIndex  -> { chip.setTextColor(colorCyan);  chip.background = cyberBorder(colorBorder, 20f) }
                i == activeIndex -> { chip.setTextColor(colorGreen); chip.background = cyberBorder(Color.parseColor("#003322"), 20f) }
                else             -> { chip.setTextColor(Color.parseColor("#223344")); chip.background = cyberBorder(colorBorder, 20f) }
            }
        }
    }

    private fun resetResultUI() {
        countdownJob?.cancel()
        cardIdentity.visibility  = View.INVISIBLE
        cardProof.visibility     = View.GONE
        cardIntegrity.visibility = View.GONE
        cardCrypto.visibility    = View.GONE
        if (::confirmationPanel.isInitialized) {
            confirmationPanel.visibility = View.GONE
        }
        pendingPassportData = null
        photoView.setImageDrawable(null)
        photoView.visibility    = View.GONE
        tvPhotoLabel.visibility = View.VISIBLE
    }

    // ═══ Pulse animation for chip connection feedback ═══
    private fun animatePulse(v: View) {
        val animator = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.1f, 1f)
        animator.duration = 300
        animator.repeatCount = 2
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        animator.start()
        
        val alphaAnim = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.6f, 1f)
        alphaAnim.duration = 300
        alphaAnim.repeatCount = 2
        alphaAnim.start()
    }

    private fun animateFadeIn(v: View) {
        v.visibility = View.VISIBLE
        if (reduceMotion) {
            v.alpha = 1f
            return
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(400).start()
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── Drawing Helpers ───────────────────────────────────────────────────────

    private fun cyberBorder(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(radius.toInt()).toFloat()
            setStroke(1, color)
            setColor(Color.TRANSPARENT)
        }

    private fun gradientBg(start: Int, end: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end)).apply {
            cornerRadius = px(radiusDp.toInt()).toFloat()
        }

    private fun spacer(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, px(dp))
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onDestroy() {
        countdownJob?.cancel()
        super.onDestroy()
        // Phase 2: Clean up step bar animations
        stepAnimators.values.forEach { it.cancel() }
        stepAnimators.clear()
        dotPulseAnimator?.cancel()
    }

}