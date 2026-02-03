package com.example.zkpapp

import android.util.Log

object ZkAuth {
    // 1. Library Load karna (Safe Mode)
    init {
        try {
            System.loadLibrary("zkp_mobile")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("ZkAuth", "❌ CRITICAL: Rust Library NOT Found! ${e.message}")
        } catch (e: Exception) {
            Log.e("ZkAuth", "❌ Error loading library: ${e.message}")
        }
    }

    // 2. 🔒 ASLI RUST FUNCTION (Private)
    // Day 77 Update: Naam change kiya aur 'challenge' parameter add kiya
    private external fun generateSecureNullifier(secret: String, domain: String, challenge: String): String

    // 3. 🛡️ PUBLIC SAFETY WRAPPER
    // UI sirf isay call karega. Ye guarantee deta hai ke Crash nahi hoga.
    fun safeGenerateNullifier(secret: String, domain: String, challenge: String): String {
        return try {
            // Updated call to new Rust function
            generateSecureNullifier(secret, domain, challenge)
        } catch (e: UnsatisfiedLinkError) {
            "⚠️ Error: Bridge Broken (Rebuild Rust & Clean Project)"
        } catch (e: Exception) {
            "⚠️ Error: Java Exception - ${e.message}"
        } catch (e: Throwable) {
            "⚠️ Critical Error: Unknown Crash prevented"
        }
    }
}