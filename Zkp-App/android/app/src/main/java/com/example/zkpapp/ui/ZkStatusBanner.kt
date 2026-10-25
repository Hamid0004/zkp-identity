package com.example.zkpapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

enum class ZkStatus { Info, Success, Error }

/** Colored banner showing a status message (info / success / error). */
@Composable
fun ZkStatusBanner(
    message: String,
    status: ZkStatus,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val background: Color
    val foreground: Color
    when (status) {
        ZkStatus.Info -> {
            background = scheme.primaryContainer
            foreground = scheme.primary
        }
        ZkStatus.Success -> {
            background = scheme.tertiaryContainer
            foreground = scheme.tertiary
        }
        ZkStatus.Error -> {
            background = scheme.errorContainer
            foreground = scheme.error
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, foreground)
    ) {
        Text(
            text = message,
            color = foreground,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Small uppercase-style label used above a group of content. */
@Composable
fun ZkSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF020810)
@Composable
private fun ZkStatusBannerPreview() {
    ZKAuthTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            ZkSectionLabel("STATUS")
            ZkStatusBanner("Verification started", ZkStatus.Info)
            ZkStatusBanner("Proof verified", ZkStatus.Success)
            ZkStatusBanner("Something went wrong", ZkStatus.Error)
        }
    }
}