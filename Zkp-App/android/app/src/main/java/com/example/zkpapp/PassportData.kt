package com.example.zkpapp

import android.graphics.Bitmap
import android.os.Parcelable
import com.google.gson.Gson
import kotlinx.parcelize.Parcelize

@Parcelize
data class PassportData(
    val firstName: String,
    val lastName: String,
    val gender: String,
    val documentNumber: String,
    val dateOfBirth: String,
    val expiryDate: String,
    val facePhoto: Bitmap?, // 🖼️ UI ke liye Photo

    // 🔐 Day 69: Raw Bytes for Data Integrity
    // ZKP iska Hash nikaal kar check karega.
    val dg1Raw: ByteArray? = null,

    // 🔐 NEW (Day 70): SOD Raw Bytes (The Signature)
    // Yeh sabse important file hai. Iske andar Digital Signature hota hai.
    // Rust is file ko khol kar Government ki Public Key se verify karega.
    val sodRaw: ByteArray? = null
) : Parcelable {

    // 🌉 BRIDGE: Kotlin -> Rust JSON Converter
    fun toRustJson(): String {
        val rustPayload = mapOf(
            "first_name" to firstName,
            "last_name" to lastName,
            "document_number" to documentNumber,
            "date_of_birth" to dateOfBirth,
            "expiry_date" to expiryDate,
            
            // 🧱 Raw Bytes (Converted to Hex String)
            "dg1_hex" to (dg1Raw?.toHexString() ?: ""),
            
            // 👇 DAY 70 UPDATE: Send SOD to Rust
            "sod_hex" to (sodRaw?.toHexString() ?: "")
        )

        // Gson library magic se Map ko JSON String bana degi
        return Gson().toJson(rustPayload)
    }

    // 🛠️ HELPER: ByteArray -> Hex String ("0A1B2C")
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}