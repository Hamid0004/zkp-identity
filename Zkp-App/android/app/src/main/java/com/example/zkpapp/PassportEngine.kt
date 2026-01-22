package com.example.zkpapp

import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.delay
import java.security.MessageDigest

// ---------------------------
// ENUMS & STATES
// ---------------------------
enum class PassportMode {
    REAL,
    SIMULATION
}

sealed class PassportState {
    object IDLE : PassportState()
    object CONNECTING : PassportState()
    object ANALYZING_MRZ : PassportState() // New State
    object BAC_AUTH : PassportState()      // New State (Future)
    object READING : PassportState()
    object DONE : PassportState()
    data class ERROR(val reason: String) : PassportState()
}

// ---------------------------
// CORE ENGINE
// ---------------------------
class PassportEngine(
    private val mode: PassportMode,
    private val isoDep: IsoDep?,
    private val mrz: String? // ✅ Updated: Accepts MRZ from Activity
) {

    companion object {
        private const val TAG = "PassportEngine"
        private const val DEBUG = true
    }

    var state: PassportState = PassportState.IDLE
        private set

    // ✅ Suspend function for Coroutines (No blocking UI)
    suspend fun start(): ByteArray {
        state = PassportState.CONNECTING

        return try {
            val output = when (mode) {
                PassportMode.REAL -> connectRealChip()
                PassportMode.SIMULATION -> simulateChip()
            }

            if (DEBUG) debugSnapshot(output)
            output
        } catch (e: Exception) {
            state = PassportState.ERROR(e.message ?: "Unknown error")
            Log.e(TAG, "ENGINE ERROR", e)
            throw e
        }
    }

    // ---------------------------
    // 📲 REAL NFC FLOW
    // ---------------------------
    private suspend fun connectRealChip(): ByteArray {
        requireNotNull(isoDep) { "IsoDep required for REAL mode" }

        // 1. Log MRZ (Masked for privacy)
        if (mrz != null) {
            state = PassportState.ANALYZING_MRZ
            Log.d(TAG, "Using MRZ for BAC: ${maskMrz(mrz)}")
        } else {
            Log.w(TAG, "⚠️ No MRZ provided. BAC will fail in production.")
        }

        // 2. Connect
        isoDep.timeout = 5000
        isoDep.connect()
        
        // 3. Ping (Basic Check)
        if (!pingChip()) {
            isoDep.close()
            throw Exception("Passport chip detected but not responding (APDU failed)")
        }

        // 4. Future: JMRTD BAC Logic goes here
        // state = PassportState.BAC_AUTH
        // performBac(isoDep, mrz)

        // 5. Read Data
        state = PassportState.READING
        // delay(500) // Small delay to stabilize connection
        
        val data = readPlaceholderData() // Currently dummy data

        isoDep.close()
        state = PassportState.DONE
        return data
    }

    private fun pingChip(): Boolean {
        // Simple Select Applet Command
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0x3F, 0x00)
        
        return try {
            val response = isoDep!!.transceive(apdu)
            val sw1 = response[response.size - 2]
            val sw2 = response[response.size - 1]
            sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed", e)
            false
        }
    }

    private fun readPlaceholderData(): ByteArray {
        // Generating 1KB of dummy data representing passport files
        return ByteArray(1024) { index -> (index % 256).toByte() }
    }

    // ---------------------------
    // 🧪 SIMULATION FLOW
    // ---------------------------
    private suspend fun simulateChip(): ByteArray {
        Log.d(TAG, "Starting Simulation...")
        
        state = PassportState.ANALYZING_MRZ
        delay(600) // Simulate processing time

        state = PassportState.BAC_AUTH
        delay(800) // Simulate Security Check

        state = PassportState.READING
        delay(1000) // Simulate Data Transfer

        state = PassportState.DONE
        return ByteArray(1024) { 0xAA.toByte() }
    }

    // ---------------------------
    // 🛡️ UTILS
    // ---------------------------
    private fun maskMrz(raw: String): String {
        return if (raw.length > 10) {
            raw.substring(0, 5) + "***" + raw.substring(raw.length - 5)
        } else {
            "***"
        }
    }

    private fun debugSnapshot(data: ByteArray) {
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

        Log.d(TAG, "====== SNAPSHOT ======")
        Log.d(TAG, "State   : $state")
        Log.d(TAG, "SHA-256 : $sha256")
        Log.d(TAG, "======================")
    }
}