package com.example.zkpapp

// ---------------------------
// SESSION STATES (STRICT FLOW)
// ---------------------------
enum class SessionState {
    IDLE,           // App opened, nothing scanned
    MRZ_SCANNED,    // Camera captured MRZ
    NFC_READY,      // MRZ validated, ready for NFC
    READING,        // Passport chip being read
    DONE,           // Read + verification finished
    ERROR           // Any failure
}

// ---------------------------
// MRZ DATA HOLDER
// ---------------------------
data class MrzInfo(
    val raw: String,
    val documentNumber: String,
    val dateOfBirth: String,
    val expiryDate: String
)

// ---------------------------
// SESSION CONTAINER
// ---------------------------
data class PassportSession(
    val mrzInfo: MrzInfo? = null,
    val state: SessionState = SessionState.IDLE
)
