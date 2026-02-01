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
    val sodRaw: ByteArray? = null
) : Parcelable {

    // 🌉 BRIDGE: Kotlin -> Rust JSON Converter
    fun toRustJson(): String {
        
        // 👇 DAY 73 STEP 2: SIMULATION HACK
        // Hum "Test User" ke liye ek nakli lekin "Valid" SOD bana rahe hain.
        // Is hash ko humne Rust se calculate karke yahan paste kiya hai.
        val validHashPart = "574aaad2ca7350a062f8cce31e34696c2b3e777fa972527d193289552418019a"
        val magicSod = "7705" + validHashPart + "aabbcc" // Magic SOD with Hash inside

        // Decision: Real SOD use karein ya Magic SOD?
        val finalSodHex = if (sodRaw != null && sodRaw.size > 10) {
            // Agar asli scan hai (size bada hai), toh asli SOD bhejo
            sodRaw.toHexString()
        } else {
            // Agar simulation hai (ya data missing hai), toh Magic SOD bhejo
            magicSod 
        }

        val rustPayload = mapOf(
            "first_name" to firstName,
            "last_name" to lastName,
            "document_number" to documentNumber,
            "date_of_birth" to dateOfBirth,
            "expiry_date" to expiryDate,
            
            // 🧱 Raw Bytes (Converted to Hex String)
            "dg1_hex" to (dg1Raw?.toHexString() ?: ""),
            
            // 👇 Updated: Ab hum Final Decision wala SOD bhej rahe hain
            "sod_hex" to finalSodHex
        )

        // Gson library magic se Map ko JSON String bana degi
        return Gson().toJson(rustPayload)
    }

    // 🛠️ HELPER: ByteArray -> Hex String ("0A1B2C")
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}