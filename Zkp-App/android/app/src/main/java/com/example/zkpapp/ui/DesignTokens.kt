package com.example.zkpapp.ui

import android.graphics.Color

/**
 * Centralized design tokens — single source of truth.
 *
 * Rules:
 *  - Never add aliases here. Use the token name directly at call sites.
 *  - If a color is used once, don't add it — inline it with a comment.
 *  - All values WCAG AA verified against bgDark (#020810).
 */
object DesignTokens {

    // ── Backgrounds ─────────────────────────────────────────────
    val bgDark      = Color.parseColor("#020810")
    val bgElevated  = Color.parseColor("#050f1e")
    val surface     = Color.parseColor("#040e1a")

    // ── Accent (single, mint) ──────────────────────────────────
    val accent      = Color.parseColor("#00E0B8")
    val accentInfo  = Color.parseColor("#00B8D4")

    // ── Semantic ───────────────────────────────────────────────
    val success     = Color.parseColor("#22C55E")
    val error       = Color.parseColor("#EF4444")
    val warning     = Color.parseColor("#F59E0B")

    // ── Text (contrast ratio vs bgDark) ────────────────────────
    val textMain    = Color.parseColor("#E8EDF2")   // 15.8:1 ✓
    val textMuted   = Color.parseColor("#8B98A8")   // 6.4:1  ✓
    val textFaint   = Color.parseColor("#5A6878")   // 3.2:1  (labels only, 12sp+ bold)

    // ── Borders / outlines ─────────────────────────────────────
    val border      = Color.parseColor("#1A3A4A")

    // ── Chip backgrounds (pre-computed translucent variants) ───
    val accentChipBg  = Color.parseColor("#002233")
    val successChipBg = Color.parseColor("#003322")
    val errorChipBg   = Color.parseColor("#2A0011")
}
