package com.example.zkpapp

import android.util.Log

object SecurityGate {

    // ---------------------------
    // Can user start camera?
    // ---------------------------
    fun canScanMrz(session: PassportSession): Boolean {
        return session.state == SessionState.IDLE
    }

    // ---------------------------
    // Can NFC start?
    // ---------------------------
    fun canStartNfc(session: PassportSession): Boolean {
        return session.state == SessionState.NFC_READY &&
               session.mrzInfo != null
    }

    // ---------------------------
    // Can simulation run?
    // ---------------------------
    fun canSimulate(session: PassportSession): Boolean {
        return session.state == SessionState.IDLE
    }

    // ---------------------------
    // Can reading begin?
    // ---------------------------
    fun canReadPassport(session: PassportSession): Boolean {
        return session.state == SessionState.NFC_READY
    }

    // ---------------------------
    // Send Data to Rust (Day 69 Final Logic)
    // ---------------------------
    fun sendToRustForProof(data: PassportData) {
        Log.d("SecurityGate", "🚀 PREPARING DATA FOR RUST...")

        // ✅ Convert Kotlin Object to Rust-Friendly JSON
        val rustJson = data.toRustJson()

        Log.d("SecurityGate", "✅ RAW BYTES CAPTURED: ${data.dg1Raw?.size ?: 0} bytes")
        Log.d("SecurityGate", "📦 PAYLOAD TO RUST: $rustJson")
    }
}