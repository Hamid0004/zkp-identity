package com.example.zkpapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DesignTokens holds android.graphics.Color Ints; Compose needs its own Color type.
private fun Int.toCompose() = Color(this)

private val ZKAuthColorScheme = darkColorScheme(
    primary = DesignTokens.accent.toCompose(),
    onPrimary = DesignTokens.bgDark.toCompose(),
    primaryContainer = DesignTokens.accentChipBg.toCompose(),
    onPrimaryContainer = DesignTokens.accent.toCompose(),

    secondary = DesignTokens.accentInfo.toCompose(),
    onSecondary = DesignTokens.bgDark.toCompose(),

    tertiary = DesignTokens.success.toCompose(),
    onTertiary = DesignTokens.bgDark.toCompose(),
    tertiaryContainer = DesignTokens.successChipBg.toCompose(),
    onTertiaryContainer = DesignTokens.success.toCompose(),

    error = DesignTokens.error.toCompose(),
    onError = DesignTokens.bgDark.toCompose(),
    errorContainer = DesignTokens.errorChipBg.toCompose(),
    onErrorContainer = DesignTokens.error.toCompose(),

    background = DesignTokens.bgDark.toCompose(),
    onBackground = DesignTokens.textMain.toCompose(),

    surface = DesignTokens.surface.toCompose(),
    onSurface = DesignTokens.textMain.toCompose(),
    surfaceVariant = DesignTokens.bgElevated.toCompose(),
    onSurfaceVariant = DesignTokens.textMuted.toCompose(),

    outline = DesignTokens.border.toCompose(),
)

/**
 * App-wide Compose theme. Colors come from DesignTokens (single source of truth).
 * Typography uses Material 3 defaults for now.
 */
@Composable
fun ZKAuthTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZKAuthColorScheme,
        content = content
    )
}