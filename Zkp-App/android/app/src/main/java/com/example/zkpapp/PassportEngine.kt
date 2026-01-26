package com.example.zkpapp

import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.delay
import java.security.MessageDigest

// ---------------------------
// ENUMS & STATES
// ---------------------------
enum class PassportMode { REAL, SIMULATION }

sealed class PassportState {
    object IDLE : PassportState()
    object CONNECTING : PassportState()
    object ANALYZING_MRZ : PassportState()
    object BAC_AUTH : PassportState()
    object READING : PassportState()
    object DONE : PassportState()
    data class ERROR(val reason: String) : PassportState()
}

// ---------------------------
// PASSPORT ENGINE
// ---------------------------
class PassportEngine(
    private val mode: PassportMode,
    private val isoDep: IsoDep?,
    private val mrz: String?
) {

    companion object {
        private const val TAG = "PassportEngine"
        private const val DEBUG = true
    }

    var state: PassportState = PassportState.IDLE
        private set

    // ---------------------------
    // START ENGINE (SUSPEND)
    // ---------------------------
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
    // REAL NFC FLOW
    // ---------------------------
    private suspend fun connectRealChip(): ByteArray {
        requireNotNull(isoDep) { "IsoDep required for REAL mode" }
        requireNotNull(mrz) { throw Exception("MRZ required for REAL mode") }

        // 1️⃣ Analyze MRZ
        state = PassportState.ANALYZING_MRZ
        Log.d(TAG, "Using MRZ for BAC: ${maskMrz(mrz)}")

        // 2️⃣ Connect to Passport Chip
        isoDep.timeout = 5000
        isoDep.connect()

        // 3️⃣ Ping Chip
        if (!pingChip()) {
            isoDep.close()
            throw Exception("Passport chip detected but not responding (APDU failed)")
        }

        // 4️⃣ BAC Authentication Placeholder
        state = PassportState.BAC_AUTH
        // TODO: implement BAC using JMRTD library
        delay(300) // simulate authentication

        // 5️⃣ Read Data
        state = PassportState.READING
        val data = readDummyData() // Replace with actual passport file reading
        isoDep.close()
        state = PassportState.DONE
        return data
    }

    private fun pingChip(): Boolean {
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

    private fun readDummyData(): ByteArray {
        // 1KB dummy data for now
        return ByteArray(1024) { (it % 256).toByte() }
    }

    // ---------------------------
    // SIMULATION FLOW
    // ---------------------------
    private suspend fun simulateChip(): ByteArray {
        Log.d(TAG, "Starting Simulation...")
        state = PassportState.ANALYZING_MRZ
        delay(600)

        state = PassportState.BAC_AUTH
        delay(800)

        state = PassportState.READING
        delay(1000)

        state = PassportState.DONE
        return ByteArray(1024) { 0xAA.toByte() }
    }

    // ---------------------------
    // UTILS
    // ---------------------------
    private fun maskMrz(raw: String): String =
        if (raw.length > 10) raw.take(5) + "***" + raw.takeLast(5) else "***"

    private fun debugSnapshot(data: ByteArray) {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

        Log.d(TAG, "====== SNAPSHOT ======")
        Log.d(TAG, "State   : $state")
        Log.d(TAG, "SHA-256 : $sha256")
        Log.d(TAG, "======================")
    }
}
