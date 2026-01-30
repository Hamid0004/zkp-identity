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
    // Send Data to Rust
    // ---------------------------
    fun sendToRustForProof(data: PassportData) {
        Log.d("SecurityGate", "🚀 Sending Passport Data to Rust Layer...")
        Log.d("SecurityGate", "Doc: ${data.documentNumber}, Name: ${data.firstName}")
    }
}