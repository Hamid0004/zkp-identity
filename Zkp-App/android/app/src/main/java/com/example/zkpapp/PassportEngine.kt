package com.example.zkpapp

import android.nfc.tech.IsoDep
import android.util.Log
import java.security.MessageDigest

// ---------------------------
// MODE DEFINITIONS
// ---------------------------
sealed class PassportMode {
    object REAL : PassportMode()
    object SIMULATION : PassportMode()
}

// ---------------------------
// STATE MACHINE
// ---------------------------
sealed class PassportState {
    object IDLE : PassportState()
    object CONNECTING : PassportState()
    object CONNECTED : PassportState()
    object READING : PassportState()
    object DONE : PassportState()
    data class ERROR(val reason: String) : PassportState()
}

// ---------------------------
// CORE ENGINE
// ---------------------------
class PassportEngine(
    private val mode: PassportMode,
    private val isoDep: IsoDep?
) {

    companion object {
        private const val TAG = "PassportEngine"
        private const val DEBUG = true   // 🔒 Turn OFF for release
    }

    var state: PassportState = PassportState.IDLE
        private set

    fun start(): ByteArray {
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
    private fun connectRealChip(): ByteArray {
        requireNotNull(isoDep) { "IsoDep required for REAL mode" }

        isoDep.timeout = 5000
        isoDep.connect()
        state = PassportState.CONNECTED

        if (!pingChip()) {
            isoDep.close()
            throw Exception("Passport chip not responding")
        }

        state = PassportState.READING
        val data = readPlaceholderData()

        isoDep.close()
        state = PassportState.DONE
        return data
    }

    private fun pingChip(): Boolean {
        val apdu = byteArrayOf(
            0x00,
            0xA4.toByte(),
            0x00,
            0x0C,
            0x02,
            0x3F,
            0x00
        )

        val response = isoDep!!.transceive(apdu)
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]

        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }

    private fun readPlaceholderData(): ByteArray {
        return ByteArray(1024) { index ->
            (index % 256).toByte()
        }
    }

    // ---------------------------
    // SIMULATION FLOW
    // ---------------------------
    private fun simulateChip(): ByteArray {
        state = PassportState.READING
        Thread.sleep(700)
        state = PassportState.DONE
        return ByteArray(1024) { 0xFF.toByte() }
    }

    // ---------------------------
    // 🔬 DEBUG SNAPSHOT (READ-ONLY)
    // ---------------------------
    private fun debugSnapshot(data: ByteArray) {
        if (data.isEmpty()) {
            Log.w(TAG, "DEBUG: Output is EMPTY")
            return
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

        val head = data.take(32)
            .joinToString(" ") { "%02X".format(it) }

        Log.d(TAG, "====== ENGINE DEBUG SNAPSHOT ======")
        Log.d(TAG, "Mode      : ${mode::class.simpleName}")
        Log.d(TAG, "State     : $state")
        Log.d(TAG, "Length    : ${data.size} bytes")
        Log.d(TAG, "SHA-256   : $sha256")
        Log.d(TAG, "Head(32)  : $head")
        Log.d(TAG, "==================================")
    }
}
