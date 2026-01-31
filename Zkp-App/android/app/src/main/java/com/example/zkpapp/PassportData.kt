package com.example.zkpapp

import android.graphics.Bitmap
import android.os.Parcelable
import com.google.gson.Gson // ✅ JSON Library
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
    
    // 🔐 NEW (Day 69): Raw Bytes for Rust ZKP
    // Yeh wo asli bytes hain jo chip se aaye hain.
    // ZKP inhi ka Hash check karega.
    val dg1Raw: ByteArray? = null 
) : Parcelable {

    // 🌉 BRIDGE: Kotlin -> Rust JSON Converter
    fun toRustJson(): String {
        // Rust ko Bitmap nahi chahiye, usay Hex String chahiye
        val rustPayload = mapOf(
            "first_name" to firstName,
            "last_name" to lastName,
            "document_number" to documentNumber,
            "date_of_birth" to dateOfBirth,
            "expiry_date" to expiryDate,
            // Bytes ko "ABCD..." Hex format mein badalna zaroori hai
            "dg1_hex" to (dg1Raw?.toHexString() ?: "")
        )
        
        // Gson library magic se Map ko JSON String bana degi
        return Gson().toJson(rustPayload)
    }

    // 🛠️ HELPER: ByteArray -> Hex String ("0A1B2C")
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}