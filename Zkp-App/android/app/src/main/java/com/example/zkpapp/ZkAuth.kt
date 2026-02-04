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
    // Ye function ab "Nullifier | Proof" return karega
    private external fun generateSecureNullifier(secret: String, domain: String, challenge: String): String

    // 3. 🛡️ PUBLIC SAFETY WRAPPER (Day 78 Logic Added)
    fun safeGenerateNullifier(secret: String, domain: String, challenge: String): String {
        return try {
            // Rust ko call kiya
            val rawResult = generateSecureNullifier(secret, domain, challenge)

            // 🆕 DAY 78: SPLIT LOGIC
            // Rust ka format: "12345NullifierHash | abcdProofBase64..."
            if (rawResult.contains("|")) {
                val parts = rawResult.split("|")
                
                // Hum UI par dikhane ke liye sirf Part 0 (Nullifier) wapis bhejte hain.
                // Note: Part 1 (Proof) hum future mein Server ko bhejenge.
                parts[0] 
            } else {
                // Agar result mein '|' nahi hai (matlab Error message hai)
                rawResult
            }

        } catch (e: UnsatisfiedLinkError) {
            "⚠️ Error: Bridge Broken (Rebuild Rust & Clean Project)"
        } catch (e: Exception) {
            "⚠️ Error: Java Exception - ${e.message}"
        } catch (e: Throwable) {
            "⚠️ Critical Error: Unknown Crash prevented"
        }
    }
}