package com.example.zkpapp

import android.util.Log

object ZkAuth {

    // 1. Library Load karna (Safe Mode)
    init {
        try {
            System.loadLibrary("zkp_mobile")
            Log.d("ZkAuth", "✅ Native Library Loaded Successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("ZkAuth", "❌ CRITICAL: Rust Library NOT Found! ${e.message}")
        } catch (e: Exception) {
            Log.e("ZkAuth", "❌ Error loading library: ${e.message}")
        }
    }

    // 2. 🔒 ASLI RUST FUNCTION (Ab Public hai)
    // ⚠️ IMPORTANT: JNI ke liye '@JvmStatic' zaroori ho sakta hai agar static call ho.
    // Humne 'private' hata diya taaki VerifierActivity isay call kar sake.
    @JvmStatic
    external fun generateSecureNullifier(secret: String, domain: String, challenge: String): String

    // 3. 🛡️ SAFETY WRAPPER (Optional but Good)
    // Ye function crash ko rokta hai agar Rust library load na ho.
    fun safeGenerateNullifier(secret: String, domain: String, challenge: String): String {
        return try {
            // Rust ko call kiya
            val rawResult = generateSecureNullifier(secret, domain, challenge)
            
            // 🦁 DAY 81 FIX: RETURN FULL DATA
            // Hum ab data ko yahan nahi katenge (Split nahi karenge).
            // Pura "Nullifier | Proof" wapis bhejenge taaki:
            // - LoginActivity sirf Nullifier dikha sake.
            // - VerifierActivity pura Proof server ko bhej sake.
            rawResult

        } catch (e: UnsatisfiedLinkError) {
            "🔥 Error: Rust Library Missing (Check Logcat)"
        } catch (e: Exception) {
            "🔥 Error: ${e.message}"
        }
    }
}