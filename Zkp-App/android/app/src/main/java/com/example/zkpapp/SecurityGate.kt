package com.example.zkpapp.security

import android.util.Log
import com.example.zkpapp.PassportData
import com.example.zkpapp.PassportSession
import com.example.zkpapp.SessionState

object SecurityGate {

    // ---------------------------
    // Can user start camera?
    // ---------------------------
    fun canScanMrz(session: PassportSession): Boolean {
        return session.state == SessionState.IDLE
    }

    // ---------------------------
    // Can NFC start?
    // Requires MRZ first
    // ---------------------------
    fun canStartNfc(session: PassportSession): Boolean {
        return session.state == SessionState.NFC_READY &&
               session.mrzInfo != null
    }

    // ---------------------------
    // Can simulation run?
    // Only allowed before MRZ scan
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
    // ✅ FIX 2: Send Data to Rust (Placeholder)
    // ---------------------------
    fun sendToRustForProof(data: PassportData) {
        Log.d("SecurityGate", "🚀 Sending Passport Data to Rust Layer...")
        Log.d("SecurityGate", "Doc: ${data.documentNumber}, Name: ${data.firstName}")
        // Phase 7 mein yahan asli JNI call aayegi
    }
}