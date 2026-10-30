package com.example.zkpapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.zkpapp.ui.ZKAuthTheme
import com.example.zkpapp.ui.ZkButton
import com.example.zkpapp.ui.ZkCard
import com.example.zkpapp.ui.ZkSecondaryButton
import com.example.zkpapp.ui.ZkSectionLabel
import com.example.zkpapp.ui.ZkStatus
import com.example.zkpapp.ui.ZkStatusBanner

class ErrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val errorText = intent.getStringExtra("error_log") ?: "Unknown Error"

        setContent {
            ZKAuthTheme {
                ErrorScreen(
                    errorText = errorText,
                    onCopy = { copyToClipboard(errorText) },
                    onRestart = { restartApp() }
                )
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", text))
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }

    private fun restartApp() {
        val i = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
        i?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
        finish()
    }
}

@Composable
fun ErrorScreen(
    errorText: String,
    onCopy: () -> Unit,
    onRestart: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ZkStatusBanner(message = "App crashed", status = ZkStatus.Error)

            ZkSectionLabel("ERROR LOG")
            ZkCard {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            ZkSecondaryButton(
                text = "Copy error",
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            )
            ZkButton(
                text = "Restart app",
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF020810)
@Composable
private fun ErrorScreenPreview() {
    ZKAuthTheme {
        ErrorScreen(
            errorText = "java.lang.NullPointerException\n  at com.example.zkpapp.Foo.bar(Foo.kt:42)",
            onCopy = {},
            onRestart = {}
        )
    }
}
