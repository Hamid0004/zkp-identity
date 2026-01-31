package com.example.zkpapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SecurityGate {
    private const val TAG = "SecurityGate"

    // 1️⃣ Load Rust Library
    init {
        try {
            System.loadLibrary("zkp_mobile")
            Log.d(TAG, "✅ Rust Library Loaded Successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Failed to load Rust library", e)
        }
    }

    // 2️⃣ Bridge Declaration
    private external fun generateProof(jsonPayload: String): String

    // 3️⃣ Permission Checks (One-Liners for clean code)
    fun canScanMrz(session: PassportSession): Boolean = session.state == SessionState.IDLE
    
    fun canStartNfc(session: PassportSession): Boolean = 
        session.state == SessionState.NFC_READY && session.mrzInfo != null

    fun canSimulate(session: PassportSession): Boolean = true 
    
    fun canReadPassport(session: PassportSession): Boolean = session.state == SessionState.NFC_READY

    // 4️⃣ Send to Rust & RETURN Result (Important for Codespace)
    suspend fun sendToRustForProof(data: PassportData): String {
        return withContext(Dispatchers.Default) {
            try {
                Log.d(TAG, "🚀 Sending to Rust...")
                val rustJson = data.toRustJson()
                
                // Rust ko call kiya aur Jawab pakad liya
                val response = generateProof(rustJson) 
                
                // Jawab wapis bhej diya taaki screen par dikh sake
                response 
            } catch (e: Exception) {
                "❌ Rust Error: ${e.message}"
            }
        }
    }
}