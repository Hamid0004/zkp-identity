package com.example.zkpapp.security

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
}