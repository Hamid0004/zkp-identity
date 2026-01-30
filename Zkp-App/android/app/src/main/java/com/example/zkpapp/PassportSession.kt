package com.example.zkpapp

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// 1️⃣ Session State Enum
enum class SessionState {
    IDLE,
    MRZ_SCANNED,
    NFC_READY,
    READING,
    DONE,
    ERROR
}

// 2️⃣ MRZ Info Holder
@Parcelize
data class MrzInfo(
    val raw: String,
    val documentNumber: String,
    val dateOfBirth: String,
    val expiryDate: String
) : Parcelable

// 3️⃣ Main Session Holder
@Parcelize
data class PassportSession(
    val mrzInfo: MrzInfo? = null,
    val state: SessionState = SessionState.IDLE
) : Parcelable