package com.example.zkpapp

import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.zkpapp.auth.ZkAuthManager
import com.example.zkpapp.ui.ZKAuthTheme
import com.example.zkpapp.ui.ZkSectionLabel
import com.example.zkpapp.ui.ZkStatus
import com.example.zkpapp.ui.ZkStatusBanner
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch

/**
 * LoginActivity - Web QR Login with Benchmark (Jetpack Compose UI)
 *
 * Flow:
 * 1. Scan Web QR -> Generate ZKP Proof
 * 2. Benchmark display -> Redirect back to Dashboard
 * 3. Handles errors gracefully and allows rescan
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        init {
            try {
                System.loadLibrary("zkp_mobile")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("ZKP", "Native library failed to load", e)
            }
        }
    }

    private var statusText by mutableStateOf("🦁 Starting Web Scanner...")
    private var statusKind by mutableStateOf(ZkStatus.Info)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Identity must exist
        if (!IdentityStorage.hasIdentity()) {
            Toast.makeText(this, "⚠️ Identity Missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            ZKAuthTheme {
                LoginScreen(status = statusText, kind = statusKind)
            }
        }

        showStatus("🦁 Starting Web Scanner...", ZkStatus.Info)
        startWebQrScanner()
    }

    private fun showStatus(text: String, kind: ZkStatus) {
        statusText = text
        statusKind = kind
    }

    // ------------------------------
    // Web QR Scanner
    // ------------------------------
    private fun startWebQrScanner() {
        IntentIntegrator(this).apply {
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setOrientationLocked(true)
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("🦁 Scan Web Login QR")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            initiateScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.let { result ->
            if (result.contents == null) finish()
            else performZkLogin(result.contents)
        } ?: super.onActivityResult(requestCode, resultCode, data)
    }

    // ------------------------------
    // ZKP Proof Generation + Benchmark
    // ------------------------------
    private fun performZkLogin(sessionId: String) {
        showStatus("🦁 Generating Proof...", ZkStatus.Info)

        lifecycleScope.launch {
            ZkAuthManager.startUniversalLogin(
                context = this@LoginActivity,
                sessionId = sessionId,

                onStatus = { msg -> showStatus(msg, ZkStatus.Info) },

                onSuccess = { meta ->
                    triggerVibration(150)

                    val benchmarkReport = """
                        ✅ LOGIN APPROVED!

                        ⏱️ Time: ${meta.generation_time_ms} ms
                        📦 Size: ${meta.proof_size_bytes} bytes
                        ⚙️ Gates: ${meta.num_gates}
                        🔑 ID: #${meta.proof_id}

                        (Redirecting to Dashboard in 4s...)
                    """.trimIndent()

                    showStatus(benchmarkReport, ZkStatus.Success)

                    // Redirect back to Dashboard instead of OfflineMenu
                    Handler(Looper.getMainLooper()).postDelayed({
                        Toast.makeText(this@LoginActivity, "Login Successful!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }, 4000)
                },

                onError = { error ->
                    Toast.makeText(this@LoginActivity, error, Toast.LENGTH_SHORT).show()
                    triggerVibration(500)
                    showStatus("🦁 Scan Web Login QR", ZkStatus.Info)
                    Handler(Looper.getMainLooper()).postDelayed({ startWebQrScanner() }, 800)
                }
            )
        }
    }

    private fun triggerVibration(durationMs: Long) {
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.let { vibrator ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }
}

@Composable
fun LoginScreen(status: String, kind: ZkStatus) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ZK Identity Proof",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Proving Identity without revealing Data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ZkSectionLabel("DYNAMIC IDENTITY PROOF")
            ZkStatusBanner(
                message = status,
                status = kind,
                // TalkBack announces status changes automatically
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

            Text(
                text = "Powered by Rust + Plonky2",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF020810)
@Composable
private fun LoginScreenPreview() {
    ZKAuthTheme {
        LoginScreen(status = "🦁 Generating Proof...", kind = ZkStatus.Info)
    }
}