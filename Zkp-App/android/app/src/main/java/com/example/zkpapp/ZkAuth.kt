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

    // 2. 🔒 ASLI RUST FUNCTION (JNI Bridge)
    // Rust Function Name: Java_com_example_zkpapp_ZkAuth_generateSecureNullifier
    @JvmStatic
    external fun generateSecureNullifier(secret: String, domain: String, challenge: String): String

    // 3. 🛡️ SAFETY WRAPPER (Crash Proof)
    // App ko crash hone se bachata hai agar library na mile
    fun safeGenerateNullifier(secret: String, domain: String, challenge: String): String {
        return try {
            val result = generateSecureNullifier(secret, domain, challenge)
            if (result.isEmpty()) "Error: Empty Proof from Rust" else result
        } catch (e: UnsatisfiedLinkError) {
            "🔥 Error: Rust Library Missing. Try Rebuilding Project."
        } catch (e: Exception) {
            "🔥 Error: ${e.message}"
        }
    }
}