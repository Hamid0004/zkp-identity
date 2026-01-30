package com.example.zkpapp

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// ---------------------------
// 1️⃣ SESSION STATES (STRICT FLOW)
// ---------------------------
enum class SessionState {
    IDLE,           // App abhi khuli hai
    MRZ_SCANNED,    // Camera ne MRZ scan kar liya
    NFC_READY,      // Chip scan ke liye taiyar
    READING,        // Chip se data padha ja raha hai
    DONE,           // Sab kuch mukammal ho gaya
    ERROR           // Koi galti ho gayi
}

// ---------------------------
// 2️⃣ MRZ DATA HOLDER (Parcelable)
// ---------------------------
@Parcelize
data class MrzInfo(
    val raw: String,
    val documentNumber: String,
    val dateOfBirth: String,
    val expiryDate: String
) : Parcelable

// ---------------------------
// 3️⃣ SESSION CONTAINER (Parcelable)
// ---------------------------
@Parcelize
data class PassportSession(
    val mrzInfo: MrzInfo? = null,
    val state: SessionState = SessionState.IDLE
) : Parcelable